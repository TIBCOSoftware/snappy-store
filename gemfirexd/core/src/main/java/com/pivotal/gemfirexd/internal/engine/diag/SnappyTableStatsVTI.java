/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
import java.util.Iterator;
import java.util.List;

import com.gemstone.gemfire.cache.execute.FunctionException;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplate;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplateNoAllNodesRoute;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.FunctionExecutionException;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdSingleResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.message.LeadNodeGetStatsMessage;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.ui.SnappyRegionStats;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Virtual table for snappydata table statistics.
 */
public class SnappyTableStatsVTI extends GfxdVTITemplate
    implements GfxdVTITemplateNoAllNodesRoute {

  private final Logger logger = LoggerFactory.getLogger(getClass().getName());

  private Iterator<?> tableStats;
  private SnappyRegionStats currentTableStats;

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  @Override
  public boolean next() throws SQLException {
    if (this.tableStats == null) {
      try {
        if (logger.isDebugEnabled()) {
          logger.debug("SnappyTableStatsVTI: getting table stats from lead " +
              Misc.getLeadNode());
        }
        GfxdSingleResultCollector collector = new GfxdSingleResultCollector();
        LeadNodeGetStatsMessage msg = new LeadNodeGetStatsMessage(collector);
        this.tableStats = ((List<?>)msg.executeFunction()).iterator();
      } catch (SQLException se) {
        throw se;
      } catch (StandardException se) {
        throw PublicAPI.wrapStandardException(se);
      } catch (RuntimeException re) {
        String message;
        if ((re instanceof FunctionException) ||
            (re instanceof FunctionExecutionException) &&
                re.getCause() != null) {
          message = re.getCause().getMessage();
        } else {
          message = re.getMessage();
        }
        throw Util.newEmbedSQLException(SQLState.LANG_UNEXPECTED_USER_EXCEPTION,
            new Object[] { message }, re);
      } catch (Throwable t) {
        throw TransactionResourceImpl.wrapInSQLException(t);
      }
    }
    if (this.tableStats.hasNext()) {
      this.currentTableStats = (SnappyRegionStats)this.tableStats.next();
      this.wasNull = false;
      if (logger.isDebugEnabled()) {
        logger.debug("SnappyTableStatsVTI: read: " + this.currentTableStats);
      }
      return true;
    } else {
      return false;
    }
  }

  @Override
  protected Object getObjectForColumn(int columnNumber) throws SQLException {
    switch (columnNumber) {
      case 1: // TABLE
        return this.currentTableStats.getTableName();
      case 2: // IS_COLUMN_TABLE
        return this.currentTableStats.isColumnTable();
      case 3: // IS_REPLICATED_TABLE
        return this.currentTableStats.isReplicatedTable();
      case 4: // ROW_COUNT
        return this.currentTableStats.getRowCount();
      case 5: // SIZE_IN_MEMORY
        return this.currentTableStats.getSizeInMemory();
      case 6: // TOTAL_SIZE
        return this.currentTableStats.getTotalSize();
      default:
        throw new GemFireXDRuntimeException("unexpected column=" +
            columnNumber + " for SnappyTablesStatsVTI");
    }
  }

  /**
   * Metadata
   */

  private static final String TABLE = "TABLE";

  private static final String IS_COLUMN_TABLE = "IS_COLUMN_TABLE";

  private static final String IS_REPLICATED_TABLE = "IS_REPLICATED_TABLE";

  private static final String ROW_COUNT = "ROW_COUNT";

  private static final String SIZE_IN_MEMORY = "SIZE_IN_MEMORY";

  private static final String TOTAL_SIZE = "TOTAL_SIZE";

  private static final ResultColumnDescriptor[] columnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor(TABLE,
          Types.VARCHAR, false, 512),
      EmbedResultSetMetaData.getResultColumnDescriptor(IS_COLUMN_TABLE,
          Types.BOOLEAN, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(IS_REPLICATED_TABLE,
          Types.BOOLEAN, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(ROW_COUNT,
          Types.BIGINT, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(SIZE_IN_MEMORY,
          Types.BIGINT, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(TOTAL_SIZE,
          Types.BIGINT, false)
  };

  private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(
      columnInfo);
}
