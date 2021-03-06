/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.server.service

import java.sql.SQLException
import java.util.UUID

import scala.util.control.NonFatal

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, Dataset, SQLContext}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.catalog.CatalogTableType
import org.apache.spark.sql.execution.command._
import org.apache.spark.sql.execution.datasources.CreateTable
import org.apache.spark.sql.server.{SQLServer, SQLServerConf, SQLServerEnv}
import org.apache.spark.sql.server.SQLServerConf._
import org.apache.spark.sql.server.service.postgresql.{Metadata => PgMetadata}
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.{Utils => SparkUtils}


/** The states of an [[ExecuteStatementOperation]]. */
sealed trait OperationState
case object INITIALIZED extends OperationState
case object RUNNING extends OperationState
case object FINISHED extends OperationState
case object CANCELED extends OperationState
case object CLOSED extends OperationState
case object ERROR extends OperationState
case object UNKNOWN extends OperationState
case object PENDING extends OperationState

/** Query type executd in [[ExecuteStatementOperation]]. */
sealed trait OperationType {
  override def toString: String = getClass.getSimpleName.stripSuffix("$")
}

object BEGIN extends OperationType
object FETCH extends OperationType
object SELECT extends OperationType


private[server] abstract class Operation {

  private val timeout = SQLServerEnv.sqlConf.sqlServerIdleOperationTimeout

  protected[this] var state: OperationState = INITIALIZED
  private var lastAccessTime: Long = System.currentTimeMillis()

  def run(): Unit
  def cancel(): Unit
  def close(): Unit

  protected[this] def setState(newState: OperationState): Unit = {
    lastAccessTime = System.currentTimeMillis()
    state = newState
  }

  private[service] def isTimeOut(current: Long): Boolean = {
    if (timeout == 0) {
      true
    } else if (timeout > 0) {
      Seq(FINISHED, CANCELED, CLOSED, ERROR).contains(state) &&
        lastAccessTime + timeout <= current
    } else {
      lastAccessTime + -timeout <= current
    }
  }
}

private[server] case class ExecuteStatementOperation(
    sessionId: Int,
    statement: String,
    isCursor: Boolean)
   (sqlContext: SQLContext,
    activePools: java.util.Map[Int, String]) extends Operation with Logging {

  private val sqlParser = SQLServerEnv.sqlParser

  private val statementId = UUID.randomUUID().toString()

  private var resultSet: DataFrame = _
  private var rowIter: Iterator[InternalRow] = _

  lazy val queryType: OperationType = statement match {
    case s if s.contains("BEGIN") => BEGIN
    case _ if isCursor => FETCH
    case _ => SELECT
  }

  override def cancel(): Unit = {
    logInfo(
      s"""Cancelling query with $statementId;
         | $statement
       """.stripMargin)
    if (statementId != null) {
      sqlContext.sparkContext.cancelJobGroup(statementId)
    }
    setState(CANCELED)
    SQLServer.listener.onStatementCanceled(statementId)
  }

  def schema(): StructType = {
    Option(resultSet).map(_.schema).getOrElse(StructType(Seq.empty))
  }

  def iterator(): Iterator[InternalRow] = {
    Option(rowIter).getOrElse(Iterator.empty)
  }

  override def close(): Unit = {
    // RDDs will be cleaned automatically upon garbage collection.
    sqlContext.sparkContext.clearJobGroup()
    logDebug(s"CLOSING $statementId")
    setState(CLOSED)
  }

  override def run(): Unit = {
    logInfo(
      s"""Running query with $statementId;
         | $statement
       """.stripMargin)
    setState(RUNNING)

    // Always use the latest class loader provided by SQLContext's state.
    Thread.currentThread().setContextClassLoader(sqlContext.sharedState.jarClassLoader)

    SQLServer.listener.onStatementStart(statementId, sessionId, statement, statementId)
    sqlContext.sparkContext.setJobGroup(statementId, statement, true)

    if (activePools.containsKey(sessionId)) {
      val pool = activePools.get(sessionId)
      sqlContext.sparkContext.setLocalProperty("spark.scheduler.pool", pool)
    }

    try {
      resultSet = Dataset.ofRows(sqlContext.sparkSession, sqlParser.parsePlan(statement))
      logDebug(resultSet.queryExecution.toString())
      SQLServer.listener.onStatementParsed(statementId, resultSet.queryExecution.toString())
      rowIter = {
        val useIncrementalCollect = SQLServerEnv.sqlConf.sqlServerIncrementalCollectEnabled
        if (useIncrementalCollect) {
          resultSet.queryExecution.executedPlan.executeToIterator()
        } else {
          resultSet.queryExecution.executedPlan.executeCollect().iterator
        }
      }
    } catch {
      case NonFatal(e) =>
        if (state == CANCELED) {
          logWarning(
            s"""Cancelled query with $statementId
               |$statement
             """.stripMargin)
          throw new SQLException(e.toString)
        } else {
          logError(
            s"""Error executing query with with $statementId
               | $statement
             """.stripMargin)
          setState(ERROR)
          SQLServer.listener.onStatementError(
            statementId, e.getMessage, SparkUtils.exceptionString(e))
          // In this case, pass through the exception
          throw e
        }
    }

    setState(FINISHED)
    SQLServer.listener.onStatementFinish(statementId)

    // Based on the assumption that DDL commands succeed, we then update internal states
    resultSet.queryExecution.logical match {
      case SetCommand(Some((SQLServerConf.SQLSERVER_POOL.key, Some(value)))) =>
        logInfo(s"Setting spark.scheduler.pool=$value for future statements in this session.")
        activePools.put(sessionId, value)
      case CreateDatabaseCommand(dbName, _, _, _, _) =>
        PgMetadata.registerDatabase(dbName, sqlContext)
      case CreateTable(desc, _, _) =>
        val dbName = desc.identifier.database.getOrElse("default")
        val tableName = desc.identifier.table
        PgMetadata.registerTable(dbName, tableName, desc.schema, desc.tableType, sqlContext)
      case CreateTableCommand(table, _) =>
        val dbName = table.identifier.database.getOrElse("default")
        val tableName = table.identifier.table
        PgMetadata.registerTable(dbName, tableName, table.schema, table.tableType, sqlContext)
      case CreateViewCommand(table, _, _, _, _, child, _, _, _) =>
        val dbName = table.database.getOrElse("default")
        val tableName = table.identifier
        val qe = sqlContext.sparkSession.sessionState.executePlan(child)
        val schema = qe.analyzed.schema
        PgMetadata.registerTable(dbName, tableName, schema, CatalogTableType.VIEW, sqlContext)
      case CreateFunctionCommand(dbNameOption, funcName, _, _, _) =>
        val dbName = dbNameOption.getOrElse("default")
        PgMetadata.registerFunction(dbName, funcName, sqlContext)
      case DropDatabaseCommand(dbName, _, _) =>
        logInfo(s"Drop a database `$dbName` and refresh database catalog information")
        PgMetadata.refreshDatabases(dbName, sqlContext)
      case DropTableCommand(table, _, _, _) =>
        val dbName = table.database.getOrElse("default")
        val tableName = table.identifier
        logInfo(s"Drop a table `$dbName.$tableName` and refresh table catalog information")
        PgMetadata.refreshTables(dbName, sqlContext)
      case DropFunctionCommand(dbNameOption, funcName, _, _) =>
        val dbName = dbNameOption.getOrElse("default")
        logInfo(s"Drop a function `$dbName.$funcName` and refresh function catalog information")
        PgMetadata.refreshFunctions(dbName, sqlContext)
      case _ =>
    }
  }
}
