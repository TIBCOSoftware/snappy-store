/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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
import java.util.Collections;
import java.util.Iterator;
import java.util.ListIterator;

import com.gemstone.gemfire.internal.cache.ExternalTableMetaData;
import com.gemstone.gemfire.internal.cache.PolicyTableData;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.pivotal.gemfirexd.internal.catalog.ExternalCatalog;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplate;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplateNoAllNodesRoute;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.shared.common.reference.Limits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A virtual table that shows the hive tables and their columns
 * in a de-normalized form.
 */
public class SysPoliciesVTI extends GfxdVTITemplate
    implements GfxdVTITemplateNoAllNodesRoute {


  private final Logger logger = LoggerFactory.getLogger(getClass().getName());

  private Iterator<PolicyTableData> policyDatas;
  private PolicyTableData currentPolicyMeta;
  private ListIterator<ExternalTableMetaData.Column> currentTableColumns;
  private ExternalTableMetaData.Column currentTableColumn;

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  @Override
  public boolean next() {
    if (this.policyDatas == null) {
      final ExternalCatalog hiveCatalog;
      if (
          (hiveCatalog = Misc.getMemStore().getExternalCatalog()) != null) {
        try {
          this.policyDatas = null;//hiveCatalog.getHiveTables(true).iterator();
        } catch (Exception e) {
          // log and move on
          logger.warn("ERROR in retrieving Policies : " + e.toString());
          this.policyDatas = Collections.emptyIterator();
        }
      } else {
        this.policyDatas = Collections.emptyIterator();
      }
      //this.currentTableColumns = Collections.emptyListIterator();
    }
    while (true) {
      if (this.currentTableColumns.hasNext()) {
        this.currentTableColumn = this.currentTableColumns.next();
        this.wasNull = false;
        return true;
      } else if (this.policyDatas.hasNext()) {
        this.currentPolicyMeta = this.policyDatas.next();
        this.currentTableColumns = this.currentPolicyMeta.columns.listIterator();
      } else {
        this.currentPolicyMeta = null;
        this.currentTableColumn = null;
        return false;
      }
    }
  }

  @Override
  protected Object getObjectForColumn(int columnNumber) throws SQLException {
    switch (columnNumber) {
      case 1: // Policy Name
        return this.currentPolicyMeta.policyName;
      case 2: // TABLE SCHEMA NAME
        return this.currentPolicyMeta.schemaName;
      case 3: // TABLE
        return this.currentPolicyMeta.tableName;
      case 4: // For
        return this.currentPolicyMeta.policyFor;
      case 5: // Apply To
        return this.currentPolicyMeta.policyApplyTo;
      case 6: // filter
           return this.currentPolicyMeta.filter;
      case 7: //owner
        return this.currentPolicyMeta.owner;
      default:
        throw new GemFireXDRuntimeException("unexpected column=" +
            columnNumber + " for HiveTablesVTI");
    }
  }

  /**
   * Metadata
   */

  private static final String POLICYNAME = "NAME";

  private static final String TABLE_SCHEMA_NAME = "TABLESCHEMANAME";
  private static final String TABLE = "TABLENAME";

  private static final String FOR = "POLICYFOR";

  private static final String APPLYTO = "APPLYTO";

  private static final String FILTER = "FILTER";

  private static final String OWNER = "OWNER";


  private static final ResultColumnDescriptor[] columnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor(POLICYNAME,
          Types.VARCHAR, false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(TABLE_SCHEMA_NAME,
          Types.VARCHAR, false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(TABLE,
          Types.VARCHAR, false, 512),
      EmbedResultSetMetaData.getResultColumnDescriptor(FOR,
          Types.VARCHAR, false, 64),
      EmbedResultSetMetaData.getResultColumnDescriptor(APPLYTO,
          Types.VARCHAR, true, Limits.DB2_VARCHAR_MAXWIDTH),
      EmbedResultSetMetaData.getResultColumnDescriptor(FILTER,
          Types.VARCHAR, false, Limits.DB2_VARCHAR_MAXWIDTH),
      EmbedResultSetMetaData.getResultColumnDescriptor(OWNER,
          Types.VARCHAR, true, 512),

  };

  private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(
      columnInfo);
}
