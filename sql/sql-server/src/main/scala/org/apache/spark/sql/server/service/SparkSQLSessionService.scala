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

import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.server.SQLServer


private[server] trait SessionService {

  def openSession(userName: String, passwd: String, ipAddress: String, dbName: String,
    state: SessionState): Int
  def getSessionState(sessionId: Int): SessionState
  def closeSession(sessionId: Int): Unit
  def executeStatement(sessionId: Int, statement: String, isCursor: Boolean)
    : ExecuteStatementOperation
}

private[server] class SparkSQLSessionService(pgServer: SQLServer)
    extends CompositeService with SessionService {

  private var sessionManager: SessionManager = _
  private var operationManager: OperationManager = _

  override def init(conf: SQLConf) {
    if (conf.contains("spark.yarn.keytab")) {
      // If you have enabled Kerberos, the following 2 params must be set
      val principalName = conf.getConfString("spark.yarn.keytab")
      val keytabFilename = conf.getConfString("spark.yarn.principal")
      SparkHadoopUtil.get.loginUserFromKeytab(principalName, keytabFilename)
    }

    sessionManager = new SessionManager(pgServer)
    addService(sessionManager)
    operationManager = new OperationManager(pgServer)
    addService(operationManager)
    super.init(conf)
  }

  override def openSession(userName: String, passwd: String, ipAddress: String, dbName: String,
      state: SessionState): Int = {
    sessionManager.openSession(userName, passwd, ipAddress, dbName, state)
  }

  override def getSessionState(sessionId: Int): SessionState = {
    sessionManager.getSession(sessionId)._2
  }

  override def closeSession(sessionId: Int): Unit = {
    sessionManager.closeSession(sessionId)
  }

  override def executeStatement(sessionId: Int, statement: String, isCursor: Boolean)
    : ExecuteStatementOperation = {
    operationManager.newExecuteStatementOperation(
      sessionManager.getSession(sessionId)._1,
      sessionId,
      statement,
      isCursor)
  }
}
