/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.tools.planexporter.AccessDatabase

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.tools.planexporter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.gemstone.gemfire.LogWriter;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireResultSet;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * This class will perform the distributed system connection establishment,
 * querying the member nodes required, close the connection.
 * 
 * Created from AccessDatabase class under DERBY-10.7-PlanExporter tool
 */
public final class AccessDistributedSystem {

  public final EmbedConnection conn;

  private final LanguageConnectionContext lcc;

  private final String schema;

  private final String queryID;
  
  private final String userQueryStr;

  private final boolean schemaExists;

  private boolean isLocal = true;

  private boolean isDerbyActivation = false;

  private TreeNode[] data;

  private String xmlPlanFragment;

  private LogWriter logger = null;

  /**
   * @return the stmt_id
   */
  public String getQueryID() {
    return queryID;
  }

  public String getUserQueryStr() {
    return userQueryStr;
  }
  
  private int depth = 0;

  public int getDepth() {
    return depth;
  }

  public AccessDistributedSystem getClone(String stmtUUID) throws SQLException {
    return new AccessDistributedSystem(conn, schema, stmtUUID, null);
  }

  private String xmlDetails = "";

  // set of variables to identify values of XPlain tables
  private static final int ID = 0;

  private static final int P_ID = 1;

  private static final int NODE_TYPE = 2;

  private static final int NO_OF_OPENS = 3;

  private static final int INPUT_ROWS = 4;

  private static final int RETURNED_ROWS = 5;

  private static final int VISITED_PAGES = 6;

  private static final int SCAN_QUALIFIERS = 7;

  private static final int NEXT_QUALIFIERS = 8;

  private static final int SCANNED_OBJECT = 9;

  private static final int SCAN_TYPE = 10;

  private static final int SORT_TYPE = 11;

  private static final int NO_OF_OUTPUT_ROWS_BY_SORTER = 12;

  // GemStone changes BEGIN
  private static final int EXEC_TIME = 13;

  // distribution related
  private static final int MEMBER_NODE = 14;

  private static final int PERCENT_EXEC_TIME = 15;

  private static final int NODE_DETAILS = 16;

  private static final int RANK = 17;

  // GemStone changes END
  /**
   * Utility class to access distributed system.
   * 
   * @param dburl
   *          GemFireXD connection URL
   * @param props
   *          connection properties
   * @param aSchema
   *          target schema
   * @param aQuery
   *          either statement UUID or query string itself.
   * @param queryParameters
   *          query parameter constants
   */
  public AccessDistributedSystem(String dburl, Properties props,
      String aSchema, String aQuery,
      ArrayList<ArrayList<Object>> queryParameters)
      throws InstantiationException, IllegalAccessException,
      ClassNotFoundException, SQLException {
    this(createConnection(dburl, props), aSchema, aQuery, queryParameters);
  }

  /**
   * information like user credentials etc won't be required as nested
   * connection is created out of it. Current schema becomes the target schema
   * to be profiled.
   * 
   * @param conn
   *          user connection.
   * @param aQuery
   *          query string.
   * @param queryParameters
   *          query parameter constants
   * @param aSchema
   * 
   */
  public AccessDistributedSystem(EmbedConnection conn, String aQuery,
      ArrayList<ArrayList<Object>> queryParameters) throws SQLException {
    this(createConnection(conn), null, aQuery, queryParameters);
  }

  public AccessDistributedSystem(EmbedConnection aConn, String aSchema,
      String aQuery, ArrayList<ArrayList<Object>> queryParameters)
      throws SQLException {

    conn = aConn;
    lcc = conn.getLanguageConnection();

    logger = Misc.getCacheLogWriter();

    boolean runtimeOnOff = lcc != null ? lcc.getRunTimeStatisticsMode() : false;

    try {
      // switch off the flag for preparation.
      if (lcc != null) {
        lcc.setRunTimeStatisticsMode(false, true);
      }

      if (aSchema == null) {
        ResultSet rs = conn.createStatement().executeQuery(
            "values CURRENT SCHEMA");

        if (rs.next()) {
          aSchema = rs.getString(1);
        }
        else {
          throw GemFireXDRuntimeException.newRuntimeException(
              "Current schema couldn't be determined ", null);
        }

        rs.close();
      }

      schema = aSchema;
      schemaExists = schemaExists();

      if (schemaExists) {
        setSchema();
      }

      // Allow PREVIOUS as a keyword here for debugging purposes
      if (aQuery.matches("PREVIOUS")) {
        userQueryStr = null;
        // We want the most recently run query that has an execution plan
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT STMT_ID FROM SYS.STATEMENTPLANS ORDER BY XPLAIN_TIME DESC");

        if (rs.next()) {
          queryID = rs.getString(1);
        }
        else {
          // maybe there is no previous statement!
          throw GemFireXDRuntimeException.newRuntimeException(
              "Previous statement to explain couldn't be determined ", null);
        }
        rs.close();
      }
      else {
        String type = XPLAINUtil.getStatementType(aQuery);
        if (type == null || type.length() <= 0) {
          userQueryStr = null;
          // UUID
          queryID = aQuery.trim();
          assert queryID.length() == 36 && queryID.split("-").length == 5: "Wrong UUID="
              + queryID;
        }
        else {
          userQueryStr = aQuery;
          queryID = recordExecutionPlan(type, aQuery, queryParameters);
        }
      }
    } finally {
      if (lcc != null) {
        lcc.setRunTimeStatisticsMode(runtimeOnOff, true);
      }
    }
  }

  public boolean setRuntimeStatisticsMode(boolean onOrOff) {
    final boolean currentstate = lcc.getRunTimeStatisticsMode();
    lcc.setRunTimeStatisticsMode(onOrOff, true);
    return currentstate;
  }

  private String recordExecutionPlan(String type, String aQuery,
      ArrayList<ArrayList<Object>> queryParameters) throws SQLException {

    if (type == XPLAINUtil.DDL_STMT_TYPE) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "DDLs cannot be profiled", null);
    }
    String stUUID = null;

    try {

      prepareExecution();

      if (type == XPLAINUtil.SELECT_APPROXIMATE_STMT_TYPE
          || type == XPLAINUtil.SELECT_STMT_TYPE) {

        final ResultSet rs;
        if (queryParameters != null) {
          if (queryParameters.size() > 1) {
            throw PublicAPI.wrapStandardException(StandardException
                .newException(SQLState.LANG_ONLY_ONE_VALUE_LIST_ALLOWED,
                    queryParameters));
          }
          PreparedStatement ps = conn.prepareStatement(aQuery);
          setParameters(ps, queryParameters);
          rs = ps.executeQuery();
        }
        else {
          rs = conn.createStatement().executeQuery(aQuery);
        }

        // just to consume every row/column data of the user query.
        // Misc.resultSetToXMLElement(rs, false);
        {
          ResultSetMetaData rsMD = rs.getMetaData();
          int numCols = rsMD.getColumnCount();
          int rowCount = 0;
          while (rs.next()) {
            StringBuilder row = new StringBuilder();
            rowCount++;
            for (int index = 1; index <= numCols; ++index) {
              String colName = rsMD.getColumnName(index);
              Object value = rs.getObject(index);
              row.append(colName).append("=").append(value);
            }
            if (SanityManager.DEBUG) {
              if (GemFireXDUtils.TracePlanGeneration) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_GENERATION,
                    "Retrieved row " + rowCount + " as " + row.toString());
              }
            }
          }
        }

        rs.close();

        assert rs instanceof EmbedResultSet;
        com.pivotal.gemfirexd.internal.iapi.sql.ResultSet iapiResultSet = ((EmbedResultSet)rs)
            .getSourceResultSet();

        // if we are executing with a derby activation, CreateResultSet
        // shouldn't try to
        // generate compressed plan for isLocallyExecuted = false.
        isDerbyActivation = !(iapiResultSet instanceof AbstractGemFireResultSet);
        return iapiResultSet.getExecutionPlanID().toString();
      }
      else if (type == XPLAINUtil.INSERT_STMT_TYPE
          || type == XPLAINUtil.UPDATE_STMT_TYPE
          || type == XPLAINUtil.DELETE_STMT_TYPE
          || type == XPLAINUtil.CALL_STMT_TYPE) {

        EmbedStatement est = (EmbedStatement)conn.createStatement();
        try {
          est.execute(aQuery);
          return est.getResultsToWrap().getExecutionPlanID().toString();
        } finally {
          est.close();
        }
      }
      // } catch (IOException e) {
      // throw GemFireXDRuntimeException.newRuntimeException(
      // "IOException in plan capturing", e);
      // } catch (ParserConfigurationException e) {
      // throw GemFireXDRuntimeException.newRuntimeException(
      // "ParserConfigurationException in plan capturing", e);
    } finally {
      clear();
    }

    ResultSet st_id = conn.createStatement().executeQuery(
        "select STMT_ID, ORIGIN_MEMBER_ID from sys.statementPlans ");

    if (!st_id.next()) {
      st_id.close();
      ResultSet plans = conn.createStatement().executeQuery(
          "select STMT_ID, ORIGIN_MEMBER_ID from sys.statementPlans ");
      StringBuilder sb = new StringBuilder(
          "Profile information missing for the statement: ").append(aQuery);
      sb.append("Statement Plans captured in thes system: \n");
      reportError(sb, plans);

      throw GemFireXDRuntimeException.newRuntimeException(sb.toString(), null);
    }

    stUUID = st_id.getString(1).trim();
    String originated = st_id.getString(2);

    while (st_id.next()) {
      if (stUUID.equalsIgnoreCase(st_id.getString(1).trim())) {
        continue;
      }
      StringBuilder sb = new StringBuilder();
      sb.append("Multiple statement id found... do explicit query on sys.statementPlans & provide statement UUID \n");
      sb.append("stid=").append(stUUID).append(" originated=")
          .append(originated).append("\n");
      sb.append("stid=").append(st_id.getString(1)).append(" originated=")
          .append(st_id.getString(2)).append("\n");
      reportError(sb, st_id);
      throw GemFireXDRuntimeException.newRuntimeException(sb.toString(), null);
    }
    st_id.close();

    return stUUID;
  }

  private void setParameters(PreparedStatement ps,
      ArrayList<ArrayList<Object>> queryParameters) throws SQLException {
    for (ArrayList<Object> v : queryParameters) {
      int i = 1;
      for (Object o : v) {
        ps.setObject(i++, o);
      }
    }
  }

  private StringBuilder reportError(StringBuilder sb, ResultSet r)
      throws SQLException {
    while (r.next()) {
      sb.append("stid=").append(r.getString(1)).append(" originated=")
          .append(r.getString(2)).append("\n");
    }
    r.close();
    return sb;
  }

  private void prepareExecution() throws SQLException {
    final boolean current = conn.getAutoCommit();
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    try {
      st.execute("call syscs_util.set_explain_connection(1)");
      st.execute("call syscs_util.set_statistics_timing(1)");
    } finally {
      st.close();
      conn.setAutoCommit(current);
    }
  }

  private void clear() {
    // boolean savedState = lcc.getRunTimeStatisticsMode();
    // try {
    // lcc.setRunTimeStatisticsMode(false, true);
    lcc.setStatsEnabled(lcc.statsEnabled(), lcc.timeStatsEnabled(), false);
    /*
    conn.createStatement().execute(
        "call syscs_util.set_explain_connection(0)");
    } catch (SQLException e) {
    throw GemFireXDRuntimeException
        .newRuntimeException(
            "AccessDistributedSystem: SQLException in switching off explain connection ",
            e);
    }
    finally {
    lcc.setRunTimeStatisticsMode(savedState, true);
    }
    */
  }

  private static EmbedConnection createConnection(EmbedConnection conn)
      throws SQLException {
    // final InternalDriver id = InternalDriver.activeDriver();
    // if (id != null) {
    // Connection conn = id.connect("jdbc:default:connection", null);
    // if (conn != null)
    // return conn;
    // }
    // if (conn instanceof EmbedConnection) {
    final InternalDriver id = conn.getLocalDriver();
    if (id != null) {
      Connection nestedconn = id.getNewNestedConnection(conn);
      if (nestedconn != null) {
        assert nestedconn instanceof EmbedConnection;
        return (EmbedConnection)nestedconn;
      }
    }
    // }
    throw Util.noCurrentConnection();
  }

  /**
   * Current Client Driver is unsupported. There needs a mechanism to collect
   * distributed query plan & ship it to client as XML string & rest of the
   * processing happen there..
   * 
   * @param props
   * @throws InstantiationException
   * @throws IllegalAccessException
   * @throws ClassNotFoundException
   * @throws SQLException
   */
  private static EmbedConnection createConnection(String dbURL, Properties props)
      throws InstantiationException, IllegalAccessException,
      ClassNotFoundException, SQLException {

    if (dbURL.indexOf("://") != -1) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "Client driver is unsupported by this utility ... ", null);
    }
    else {
      Class.forName("io.snappydata.jdbc.EmbeddedDriver").newInstance();
    }

    // Get a connection
    return (EmbedConnection)DriverManager.getConnection(dbURL, props);

  }

  /**
   * Set the schema of the current connection to the XPLAIN schema in which the
   * statistics can be found.
   * 
   * @throws SQLException
   *           if an error happens while accessing the database
   */
  private void setSchema() throws SQLException {
    PreparedStatement setSchema = conn.prepareStatementByPassQueryInfo(-1,
        "SET SCHEMA ?", false, false, false, null, 0, 0);
    setSchema.setString(1, schema);
    setSchema.execute();
    setSchema.close();
  }

  /**
   * Check if there is a schema in the database that matches the schema name
   * that was passed in to this instance.
   */
  private boolean schemaExists() throws SQLException {
    boolean found = false;
    ResultSet result = conn.getMetaData().getSchemas();
    while (result.next()) {
      if (result.getString(1).equals(schema)) {
        found = true;
        break;
      }
    }
    result.close();
    return found;
  }

  public boolean verifySchemaExistance() {
    return schemaExists;
  }

  /**
   * <p>
   * This method creates the queries such that after execution of the query it
   * will return XML data fragments.
   * </P>
   * 
   * @throws SQLException
   * */
  public void createXMLFragment() throws SQLException {

    /*
    long totalExecTime = 0;
    
    createXMLData("select 'id=\"' ||RS_ID|| '\"' "
        + "from " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS "
        + "where STMT_ID = ? order by INSERT_ORDER", ID);

    createXMLData("select PARENT_RS_ID " + "from " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS "
        + "where STMT_ID = ? order by INSERT_ORDER", P_ID);

    createXMLData("select 'name=\"' ||OP_IDENTIFIER|| '\"' "
        + "from " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS "
        + "where STMT_ID = ? order by INSERT_ORDER", NODE_TYPE);
    
    createXMLData("select 'rank=\"' ||TRIM(CHAR(RANK))|| '\"' "
        + "from " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS "
        + "where STMT_ID = ? order by INSERT_ORDER", RANK);

    // Display info in milliseconds, not nanoseconds
    createXMLData(
        "select 'execute_time=\"' "
            + "|| TRIM(CHAR(cast(EXECUTE_TIME as double)/1000000))|| ' ms\"' "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSET_TIMINGS a RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.timing_id = b.timing_id) "
            + "where STMT_ID = ? order by INSERT_ORDER",
        EXEC_TIME);
    
    // Percentage of total execute time
    totalExecTime = totalExecTime();
    createXMLData(
        "select 'percent_exec_time=\"' "
            + "|| char(cast(cast(EXECUTE_TIME as double)/" + totalExecTime + " * 100 as decimal(10,2))) "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSET_TIMINGS a RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.timing_id = b.timing_id) "
            + "where STMT_ID = ? order by INSERT_ORDER",
        PERCENT_EXEC_TIME);

    createXMLData("select 'no_opens=\"' " + "|| TRIM(CHAR(NO_OPENS))|| '\"' "
        + "from " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS "
        + "where STMT_ID = ? order by INSERT_ORDER", NO_OF_OPENS);

    createXMLData("select 'input_rows=\"' "
        + "|| TRIM(CHAR(INPUT_ROWS))|| '\"' " + "from " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS "
        + "where STMT_ID = ? order by INSERT_ORDER", INPUT_ROWS);

    createXMLData("select 'returned_rows=\"' "
        + "|| TRIM(CHAR(RETURNED_ROWS))|| '\"' " + "from " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS "
        + "where STMT_ID = ? order by INSERT_ORDER", RETURNED_ROWS);

    createXMLData(
        "select 'visited_pages=\"'"
            + "|| TRIM(CHAR(NO_VISITED_PAGES))|| '\"' "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SCAN_PROPS a "
            + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.scan_rs_id = b.scan_rs_id) "
            // + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SCAN_PROPS "
            // + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            // + "NATURAL RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            + "where STMT_ID = ? order by b.INSERT_ORDER",
        VISITED_PAGES);

    createXMLData(
        "select 'scan_qualifiers=\"'"
            + "||SCAN_QUALIFIERS|| '\"' "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SCAN_PROPS a "
            + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.scan_rs_id = b.scan_rs_id) "
            // + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SCAN_PROPS "
            // + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            // + "NATURAL RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            + "where STMT_ID = ? order by b.INSERT_ORDER",
        SCAN_QUALIFIERS);

    createXMLData(
        "select 'next_qualifiers=\"'"
            + "||NEXT_QUALIFIERS|| '\"' "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SCAN_PROPS a "
            + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.scan_rs_id = b.scan_rs_id) "
            // + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SCAN_PROPS "
            // + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            // + "NATURAL RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            + "where STMT_ID = ? order by b.INSERT_ORDER",
        NEXT_QUALIFIERS);

    createXMLData(
        "select 'scanned_object=\"'"
            + "||SCAN_OBJECT_NAME|| '\"' "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SCAN_PROPS a "
            + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.scan_rs_id = b.scan_rs_id) "
            // + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SCAN_PROPS "
            // + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            // + "NATURAL RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            + "where STMT_ID = ? order by b.INSERT_ORDER",
        SCANNED_OBJECT);

    createXMLData(
        "select 'scan_type=\"'"
            + "||TRIM(SCAN_TYPE)|| '\"' "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SCAN_PROPS a "
            + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.scan_rs_id = b.scan_rs_id) "
            // + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SCAN_PROPS "
            // + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            // + "NATURAL RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            + "where STMT_ID = ? order by b.INSERT_ORDER",
        SCAN_TYPE);

    createXMLData(
        "select 'sort_type=\"'"
            + "||TRIM(SORT_TYPE)|| '\"' "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SORT_PROPS a "
            + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.sort_rs_id = b.sort_rs_id) "
            // + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SORT_PROPS "
            // + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            // + "NATURAL RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            + "where STMT_ID = ? order by b.INSERT_ORDER",
        SORT_TYPE);

    createXMLData(
        "select 'sorter_output=\"'"
            + "||TRIM(CHAR(NO_OUTPUT_ROWS))|| '\"' "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SORT_PROPS a "
            + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.sort_rs_id = b.sort_rs_id) "
            // + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SORT_PROPS "
            // + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            // + "NATURAL RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            + "where STMT_ID = ? order by b.INSERT_ORDER",
        NO_OF_OUTPUT_ROWS_BY_SORTER);

    // Add in OP_DETAILS for some nodes for additional details that 
    // do not fall into scan_qualifiers or any other column
    createXMLData("select 'node_details=\"' ||OP_DETAILS|| '\"' "
        + "from " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS "
        + "where STMT_ID = ? order by INSERT_ORDER", NODE_DETAILS);
    
    // -----------------------------------
    // distribution related........
    // -----------------------------------

    createXMLData(
        "select 'member_node=\"'"
            + "||case when TRIM(a.DIST_OBJECT_TYPE) like '"
            + XPLAINUtil.OP_QUERY_SEND
            + "' or TRIM(a.DIST_OBJECT_TYPE) like '"
            + XPLAINUtil.OP_RESULT_SEND
            + "' then a.TARGET_MEMBER "
            + " when TRIM(a.DIST_OBJECT_TYPE) like '"
            + XPLAINUtil.OP_QUERY_SCATTER
            + "' then a.PRUNED_MEMBERS_LIST "
            + " else a.ORIGINATOR end || '\"' , a.dist_rs_id,  b.OP_IDENTIFIER, a.TARGET_MEMBER, a.ORIGINATOR, a.DIST_OBJECT_TYPE "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_DIST_PROPS a "
            + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.dist_rs_id = b.dist_rs_id ) "
            // + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SORT_PROPS "
            // + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            // + "NATURAL RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            + "where STMT_ID = ? order by b.INSERT_ORDER",
        MEMBER_NODE);

    //    createXMLData(
    //        "select 'execute_time=\"'"
    //            + "||TRIM(CHAR(SCATTER_TIME))|| '\"' "
    //            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_DIST_PROPS a "
    //            + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.dist_rs_id = b.dist_rs_id and a.PARENT_DIST_RS_ID is null) "
    //            // + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SORT_PROPS "
    //            // + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
    //            // + "NATURAL RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
    //            + "where STMT_ID = ? order by b.INSERT_ORDER",
    //        EXEC_TIME);
    
    createXMLData(
        "select 'execute_time=\"'"
            + "||TRIM(CHAR(cast((SER_DESER_TIME + PROCESS_TIME + THROTTLE_TIME) as double)/1000000))|| ' ms\"' "
            + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_DIST_PROPS a "
            + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS b on a.dist_rs_id = b.dist_rs_id ) " // and a.PARENT_DIST_RS_ID is not null) "
            // + "from (" + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_SORT_PROPS "
            // + "RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            // + "NATURAL RIGHT OUTER JOIN " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS) "
            + "where STMT_ID = ? order by b.INSERT_ORDER",
        EXEC_TIME);
    final StatisticsCollectionObserver observer = StatisticsCollectionObserver.getInstance();
    if (observer != null) {
      observer.observeXMLData(data);
    }
    */

    final String qry = "select PLAN_XML_FRAGMENT from "
        + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS where STMT_ID = ?";
    PreparedStatement ps = conn.prepareStatementByPassQueryInfo(-1, qry, false,
        false, false, null, 0, 0);
    ps.setString(1, getQueryID());
    ResultSet r = ps.executeQuery();
    if (r.next()) {
      xmlPlanFragment = r.getString(1);
    }

    if (SanityManager.ASSERT) {
      if (GemFireXDUtils.TracePlanAssertion) {
        List<String> morePlans = new ArrayList<String>();
        int plans= 0;
        while (r.next()) {
          plans++;
          morePlans.add(r.getString(1));
        }
        if( plans > 0) {
          for(String p : morePlans) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_ASSERTION, "Extra Plans: " + p);
          }
          SanityManager.THROWASSERT(plans + " plans detected expected only one plan");
        }
      }
    }
    r.close();
    ps.close();
  }

  /**
   * Generating the XML tree
   * 
   * @return all xml elements as a String
   */
  public String getXmlString() {

    return xmlPlanFragment;

    /*
    for (int i = 0; i < data.length; i++) {
      // assume only one root element for any query
      if (data[i].getDepth() == 0) {// root element

        xmlDetails += indent(1);
        xmlDetails += data[i].toString();
        getChildren(1, data[i].getId());
        xmlDetails += indent(1) + "</node>\n";
        break;
      }
    }
    return xmlDetails;
    */
  }

  /**
   * 
   * @param j
   *          indent needed
   * @return indent as a string
   */
  public String indent(int j) {
    String str = "";
    for (int i = 0; i <= j + 1; i++)
      str += "    ";

    return str;
  }

  /**
   * marking the depth of each element
   */
  public void markTheDepth() {
    int i = 0;
    while (data[i].getParent().indexOf("null") == -1)
      i++;
    data[i].setDepth(depth); // root
    findChildren(i, depth);
  }

  public void markRemote() {
    isLocal = false;
  }

  public boolean isRemote() {
    return !isLocal;
  }

  public boolean isDerbyActivation() {
    return isDerbyActivation;
  }

  /**
   * 
   * @param idx
   *          current element's index
   * @param dep
   *          current examining depth
   */
  private void findChildren(int idx, int dep) {
    if (dep > depth)
      depth = dep;

    for (int i = 0; i < data.length; i++) {

      if (data[i].getParent().indexOf("null") == -1) {
        if ((data[idx].getId()).indexOf(data[i].getParent()) != -1 && i != idx) {
          data[i].setDepth(dep + 1);
          findChildren(i, dep + 1);
        }
      }
    }
  }

  /**
   * 
   * @return whether the initialization is successful or not
   * @throws SQLException
   */
  public boolean initializeDataArray() throws SQLException {
    if (noOfNodes() == 0) {
      return false;
    }
    else {
      data = new TreeNode[noOfNodes()];
      for (int i = 0; i < data.length; i++) {
        data[i] = new TreeNode();
      }
      return true;
    }

  }

  /**
   * 
   * @return total # of nodes
   * @throws SQLException
   */
  private int noOfNodes() throws SQLException {
    PreparedStatement ps = conn.prepareStatementByPassQueryInfo(-1,
        "select count(*) from " + GfxdConstants.PLAN_SCHEMA
            + ".SYSXPLAIN_RESULTSETS where STMT_ID = ?", false, false, false,
        null, 0, 0);
    ps.setString(1, getQueryID());
    ResultSet results = ps.executeQuery();
    results.next();
    int no = results.getInt(1);
    results.close();
    ps.close();
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TracePlanGeneration) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_GENERATION,
            GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_resultsets contain " + no
                + " rows for " + getQueryID());
        if (!isRemote()) {
          dumpXPLAINResultSets(no == 0 ? "" : " where stmt_id='" + getQueryID()
              + "'");
        }
      }
    }
    return no;
  }

  private void dumpXPLAINResultSets(String condition) throws SQLException {
    StringBuilder sb = new StringBuilder("----------- Dumping "
        + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS ---------- \n");
    PreparedStatement ps = conn.prepareStatementByPassQueryInfo(-1,
    // "select stmt_id , rs_id, op_identifier, timing_id from " +
    // GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS "
        "select stmt_id , plan_xml_fragment from " + GfxdConstants.PLAN_SCHEMA
            + ".SYSXPLAIN_RESULTSETS " + condition, false, false, false, null, 0, 0);
    ResultSet results = ps.executeQuery();
    int rowNum = 0;
    while (results.next()) {
      sb.append(++rowNum).append(" ").append(results.getString(1)).append(" ")
          .append(results.getString(2))
          /*.append(" ")
          .append(results.getString(3)).append(" ")
          .append(results.getString(4))*/.append("\n");
    }

    SanityManager.DEBUG_PRINT("dump:" + GfxdConstants.TRACE_PLAN_GENERATION,
        sb.toString());

    results.close();
    ps.close();

    /*
    sb = new StringBuilder("----------- Dumping " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS_TIMING ---------- \n");
    ps = conn
        .prepareStatementByPassQueryInfo(
            -1,
            "select stmt_id, b.timing_id, b.execute_time from "
                + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSETS a left outer join " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_RESULTSET_TIMINGS b on a.timing_id = b.timing_id "
                + condition, false, false, false, null);
    
    results = ps.executeQuery();
    rowNum = 0;
    while (results.next()) {
      sb.append(++rowNum).append(" ").append(results.getString(1)).append(" ")
          .append(results.getString(2)).append(" ")
          .append(results.getObject(3)).append("\n");
    }

    SanityManager.DEBUG_PRINT("dump:"+GfxdConstants.TRACE_PLAN_GENERATION,
        sb.toString());
    
    results.close();
    ps.close();
    */

  }

  /**
   * 
   * @return the &lt;statement&gt; element
   * @throws SQLException
   */
  public String statement() throws SQLException {
    PreparedStatement ps = conn.prepareStatementByPassQueryInfo(-1,
        "select STMT_TEXT from " + GfxdConstants.PLAN_SCHEMA
            + ".SYSXPLAIN_STATEMENTS where STMT_ID = ?", false, false, false,
        null, 0, 0);
    ps.setString(1, getQueryID());
    ResultSet results = ps.executeQuery();
    results.next();
    String statement = results.getString(1);
    results.close();
    ps.close();

    /*Removing possible occurrences of special XML characters
     * from a query statement with XML representation.*/
    statement = escapeForXML(statement);

    return "<statement>" + statement + "</statement>\n";
  }

  public String member() {
    return isLocal ? "" : "<member>"
        + escapeForXML(Misc.getGemFireCache().getDistributedSystem()
            .getDistributedMember().getId()) + "</member>";
  }

  /**
   * Escape characters that have a special meaning in XML.
   * 
   * @param text
   *          the text to escape
   * @return the text with special characters escaped
   */
  private static String escapeForXML(String text) {
    StringBuilder sb = new StringBuilder();

    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      switch (ch) {
        case '&':
          sb.append("&amp;");
          break;
        case '<':
          sb.append("&lt;");
          break;
        case '>':
          sb.append("&gt;");
          break;
        case '\'':
          sb.append("&apos;");
          break;
        case '"':
          sb.append("&quot;");
          break;
        default:
          sb.append(ch);
      }
    }

    return sb.toString();
  }

  /**
   * This method is needed since in the case of XML attributes we have to filter
   * the quotation (&quot;) marks that is compulsory. eg:
   * scanned_object="A &quot;quoted&quot;  table name";
   * 
   * @param text
   *          attribute string to be checked
   * @return modified string
   */
  private String escapeInAttribute(String text) {
    if (text.indexOf('"') == -1)
      return text;
    String correctXMLString = escapeForXML(text.substring(
        text.indexOf('"') + 1, text.length() - 1));
    return text.substring(0, text.indexOf('"') + 1) + correctXMLString + "\"";
  }

  /**
   * 
   * @return XPLAIN_TIME of
   *         " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_STATEMENTS
   * @throws SQLException
   */
  public String time() throws SQLException {
    PreparedStatement ps = conn.prepareStatementByPassQueryInfo(-1,
        "select '<time>'||TRIM(CHAR(XPLAIN_TIME))||" + "'</time>' from "
            + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_STATEMENTS "
            + "where STMT_ID = ?", false, false, false, null, 0, 0);
    ps.setString(1, getQueryID());
    ResultSet results = ps.executeQuery();
    results.next();
    String time = results.getString(1);
    results.close();
    ps.close();

    return time + "\n";
  }

  // GemStone changes BEGIN
  /**
   * 
   * @return XPLAIN_TIME of
   *         " + GfxdConstants.PLAN_SCHEMA + ".SYSXPLAIN_STATEMENTS
   * @throws SQLException
   */
  public String begin_end_exe_time() throws SQLException {
    final String timeDiffFn = "TRIM(CHAR(END_EXE_TIME_L - BEGIN_EXE_TIME_L))";
    PreparedStatement ps = conn
        .prepareStatementByPassQueryInfo(
            -1,
            "select '<begin_exe_time>'||TRIM(CHAR(BEGIN_EXE_TIME))||"
                + "'</begin_exe_time>'||'\n'||'<end_exe_time>'||TRIM(CHAR(END_EXE_TIME))||'</end_exe_time>'||"
                + "'\n'||'<elapsed_time>'||" + timeDiffFn
                + "||'</elapsed_time>' " + " from ("
                + GfxdConstants.PLAN_SCHEMA
                + ".SYSXPLAIN_STATEMENTS b) where STMT_ID = ?", false, false,
            false, null, 0, 0);
    ps.setString(1, getQueryID());
    ResultSet results = ps.executeQuery();
    results.next();
    String time = results.getString(1);
    results.close();
    ps.close();

    return time + "\n";
  }

  // GemStone changes END

  /**
   * 
   * @return stmt_id as a XML element
   */
  public String stmt_id() {
    return isLocal ? "<stmt_id>" + getQueryID() + "</stmt_id>\n" : "";
  }

  /**
   * closing the connection to the database
   */
  public void closeConnection() {
    try {
      if (conn != null) {
        conn.close();
      }
    } catch (SQLException sqlExcept) {
    }
  }

  public LanguageConnectionContext getLanguageConnectionContext() {
    return lcc;
  }

  public LogWriter logger() {
    return logger;
  }

  /**
   * 
   * @return data array of TreeNode Objects
   */
  public TreeNode[] getData() {
    return data;
  }

  /**
   * test method to check whether its part of distribution .
   * 
   * @param n
   * @return
   */
  public static boolean isMessagingEntry(String n) {
    return XPLAINUtil.OP_QUERY_SCATTER.equals(n)
        || XPLAINUtil.OP_QUERY_SEND.equals(n)
        || XPLAINUtil.OP_QUERY_RECEIVE.equals(n)
        || XPLAINUtil.OP_RESULT_SEND.equals(n)
        || XPLAINUtil.OP_RESULT_RECEIVE.equals(n)
        || XPLAINUtil.OP_RESULT_HOLDER.equals(n);
  }
}
