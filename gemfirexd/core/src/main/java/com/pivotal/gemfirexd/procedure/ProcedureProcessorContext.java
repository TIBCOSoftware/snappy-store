/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package com.pivotal.gemfirexd.procedure;

import java.sql.Connection;

/**
 * Context object passed to a {@link ProcedureResultProcessor} in its
 * <code>init</code> method.
 * 
 * @author Eric Zoerner
 */
public interface ProcedureProcessorContext {

  /**
   * Get the array of incoming result sets for a given result set number, where
   * each array element is a result set provided by a procedure executing
   * on some server. The size of the array is equal to the number of servers
   * executing this procedure.
   *
   * @param resultSetNumber the index of the result set corresponding to the
   *        result sets declared for this procedure.
   * @return the incoming result sets for this resultSetNumber from all servers
   *         executing this procedure.
   */
  IncomingResultSet[] getIncomingResultSets(int resultSetNumber);
  
  /**
   * Get the array of incoming result sets for the out parameters provided
   * by the executing procedures. The size of the array is equal to the number
   * of servers executing this procedure. Each of these result sets will only
   * have one "row" in it, one Object[] corresponding to the out parameters.
   *
   * @return the incoming result sets that contain the one array of out
   * parameters from that server.
   */
  IncomingResultSet[] getIncomingOutParameters();
  
  /**
   * Returns the whereClause for this execution,
   * or null if there wasn't one
   */
  String getFilter();
  
  /**
   * Returns the table name in the format "schemaName.tableName"
   * if this procedure was
   * executed with an ON TABLE clause, or null
   * otherwise.
   */
  String getTableName();
  
  /**
   * Returns an array of colocated tables in this
   * server in the format "schemaName.tableName".
   * If this procedure was not called with an ON TABLE
   * clause, then this method returns null.
   */
  String[] getColocatedTableNames();
  
  /**
   * Return the name of this procedure.
   */
  String getProcedureName();
  
  /**
   * A "nested" JDBC connection that can be used by
   * the processor to execute SQL. If the SQL query string
   * begins with the "&lt;local&gt;" escape sequence, then execution
   * will be restricted to local execution only.
   * This Connection is the same Connection obtained with
   * <code>DriverManager.getConnection("jdbc:default:connection");</code>
   */
  Connection getConnection();
  
  /**
   * Return true if this is a re-attempt occurring after a member
   * of the distributed system has failed. For some procedure implementations
   * that are doing write operations, special handling may be necessary
   * if this is a possible duplicate invocation on this data.
   */
  boolean isPossibleDuplicate();

  /**
   * Returns true if the specified table is a partitioned
   * table, false if it is a replicated table.
   *
   * @param tableName name of table as "schemaName.tableName",
   * or just "tableName" with default schema assumed.
   */
  boolean isPartitioned(String tableName);
}
