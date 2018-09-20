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

package com.pivotal.gemfirexd.internal.engine.diag;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplate;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdConfigMessage;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;

/**
 * A virtual table that shows some statistics for queries that have been invoked
 * since the last call to SYS.SET_QUERYSTATS(1). This includes stats
 * for nodes where a query got executed, number of invocations, aggregate time
 * for invocations etc. Also shows the query plans on various nodes.
 * 
 * This virtual table can be invoked by calling it directly:
 * 
 * <PRE>
 * select * from SYS.QUERYSTATS
 * </PRE>
 * 
 * @author swale
 */
public class QueryStatisticsVTI extends GfxdVTITemplate {

  private Iterator<HashMap<String, Object>> memberResults;

  private HashMap<String, Object> currentStats;

  public boolean next() throws SQLException {
    if (this.memberResults == null) {
      try {
        final MemberSingleResultCollector rc = new MemberSingleResultCollector();
        final DistributedMember myId = Misc.getDistributedSystem()
            .getDistributionManager().getDistributionManagerId();
        /* [sb] restricting to self for fixing #43219
         * 
        final GfxdConfigMessage<TreeMap<String, HashMap<String, Object>>> msg =
          new GfxdConfigMessage<TreeMap<String, HashMap<String, Object>>>(rc,
            null, GfxdConfigMessage.Operation.GET_QUERYSTATS, null, false);
         */
        final GfxdConfigMessage<TreeMap<String, HashMap<String, Object>>> msg =
            new GfxdConfigMessage<>(rc,
              Collections.singleton(myId), GfxdConfigMessage
                  .Operation.GET_QUERYSTATS, null, false);
        this.memberResults = msg.executeFunction().values().iterator();
      } catch (SQLException ex) {
        throw ex;
      } catch (Throwable t) {
        throw Util.javaException(t);
      }
    }
    if (this.memberResults.hasNext()) {
      this.currentStats = this.memberResults.next();
      this.wasNull = false;
      return true;
    }
    return false;
  }

  @Override
  public boolean getBoolean(int columnNumber) throws SQLException {
    ResultColumnDescriptor desc = columnInfo[columnNumber - 1];
    if (desc.getType().getJDBCTypeId() != Types.BOOLEAN) {
      throw dataTypeConversion("boolean", desc);
    }
    final String columnName = desc.getName();
    Object stats = this.currentStats.get(columnName);
    if (stats != null) {
      this.wasNull = false;
      return (Boolean)stats;
    }
    else {
      throw new GemFireXDRuntimeException("unexpected columnName " + columnName
          + " for query: " + this.currentStats.get(QUERYSTRING));
    }
  }

  @Override
  public int getInt(int columnNumber) throws SQLException {
    ResultColumnDescriptor desc = columnInfo[columnNumber - 1];
    if (desc.getType().getJDBCTypeId() != Types.INTEGER) {
      throw dataTypeConversion("integer", desc);
    }
    final String columnName = desc.getName();
    Object stats = this.currentStats.get(columnName);
    if (stats != null) {
      this.wasNull = false;
      return (Integer)stats;
    }
    else {
      throw new GemFireXDRuntimeException("unexpected columnName " + columnName
          + " for query: " + this.currentStats.get(QUERYSTRING));
    }
  }

  @Override
  public long getLong(int columnNumber) throws SQLException {
    ResultColumnDescriptor desc = columnInfo[columnNumber - 1];
    if (desc.getType().getJDBCTypeId() != Types.BIGINT) {
      throw dataTypeConversion("long", desc);
    }
    final String columnName = desc.getName();
    Object stats = this.currentStats.get(columnName);
    if (stats != null) {
      this.wasNull = false;
      return (Long)stats;
    }
    else {
      throw new GemFireXDRuntimeException("unexpected columnName " + columnName
          + " for query: " + this.currentStats.get(QUERYSTRING));
    }
  }

  @Override
  public Object getObjectForColumn(int columnNumber) {
    ResultColumnDescriptor desc = columnInfo[columnNumber - 1];
    final String columnName = desc.getName();
    return this.currentStats.get(columnName);
  }

  @Override
  public void close() throws SQLException {
    super.close();
    this.currentStats = null;
  }

  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  /** Metadata */

  public static final String QUERYSTRING = "QUERY";

  @SuppressWarnings("WeakerAccess")
  public static final String MEMBERID = "MEMBERID";

  public static final String PARAMSSIZE = "PARAMSSIZE";

  public static final String QUERYPLAN = "PLAN";

  public static final String NUMINVOCATIONS = "NUMINVOCATIONS";

  public static final String TOTALNANOS = "TOTALTIME";

  public static final String DISTRIBNANOS = "DISTRIBUTIONTIME";

  public static final String SERNANOS = "SERIALIZATIONTIME";

  public static final String EXECNANOS = "EXECUTIONTIME";

  public static final String ORMNANOS = "ORMTIME";

  private static final ResultColumnDescriptor[] columnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor(QUERYSTRING,
          Types.VARCHAR, false, 512),
      EmbedResultSetMetaData.getResultColumnDescriptor(MEMBERID, Types.VARCHAR,
          false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(PARAMSSIZE,
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(QUERYPLAN, Types.VARCHAR,
          false, 2048),
      EmbedResultSetMetaData.getResultColumnDescriptor(NUMINVOCATIONS,
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(TOTALNANOS,
          Types.BIGINT, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(DISTRIBNANOS,
          Types.BIGINT, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(SERNANOS, Types.BIGINT,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(EXECNANOS, Types.BIGINT,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(ORMNANOS, Types.BIGINT,
          false),
  };

  private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(
      columnInfo);

  /**
   * Simple result collector for query statistic results that will add the
   * member to the map received for statistics and return the list of maps
   * received.
   * 
   * @author swale
   */
  private class MemberSingleResultCollector extends
      TreeMap<String, HashMap<String, Object>> implements
      ResultCollector<Object, TreeMap<String, HashMap<String, Object>>> {

    private static final long serialVersionUID = 1996707111592217057L;

    @SuppressWarnings("unchecked")
    public void addResult(DistributedMember memberId,
        Object resultOfSingleExecution) {
      final ArrayList<HashMap<String, Object>> result =
        (ArrayList<HashMap<String, Object>>)resultOfSingleExecution;
      // null result indicates that SET_QUERYSTATS procedure has not been
      // executed yet
      if (result != null) {
        for (HashMap<String, Object> statsMap : result) {
          statsMap.put(MEMBERID, memberId.getId());
          put((String)statsMap.get(QUERYSTRING) + ':' + memberId.getId(),
              statsMap);
        }
      }
    }

    public TreeMap<String, HashMap<String, Object>> getResult()
        throws FunctionException {
      return this;
    }

    public TreeMap<String, HashMap<String, Object>> getResult(long timeout,
        TimeUnit unit) throws FunctionException, InterruptedException {
      throw new AssertionError(
          "getResult with timeout not expected to be invoked for GemFireXD");
    }

    public void clearResults() {
      clear();
    }

    public void endResults() {
      // nothing to be done
    }
  }
}
