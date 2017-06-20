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
import java.sql.SQLException;
import java.sql.Statement;
/**
 * A context object that can be passed into a PROCEDURE
 * by declaring it as an argument in the static method implementation.
 * 
 * @author Eric Zoerner
 */
public interface ProcedureExecutionContext {

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
   * the procedure to execute SQL. If the SQL query string
   * begins with the "&lt;local&gt;" escape sequence, then execution
   * will be restricted to local execution only, and only on
   * the data "assigned" to this procedure on this server.
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
  
  /**
   * Create and return an empty container for an output result set.
   *
   * @param resultSetNumber the index to assign to this result set as one of the
   *        result sets declared for this procedure.
   */
  OutgoingResultSet getOutgoingResultSet(int resultSetNumber);
  
  /**
   * Checks whether the stored procedure's CallableStatement has been 
   * cancelled. <br><br> 
   * The statement may be cancelled due to any of the following reasons:<br>
   * 1) Query getting timed out due to query time out that was set using {@link Statement#setQueryTimeout(int)}
   * 2) Default critical memory limit getting breached <br><br>
   * 
   * If the statement is cancelled, this function will throw a {@link SQLException}
   *  
   * @throws SQLException
   */
  public void checkQueryCancelled() throws SQLException;
}
