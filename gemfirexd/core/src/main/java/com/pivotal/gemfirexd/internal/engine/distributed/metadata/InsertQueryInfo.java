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
/**
 * 
 */
package com.pivotal.gemfirexd.internal.engine.distributed.metadata;

import com.gemstone.gemfire.internal.cache.AbstractRegion;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;

/**
 * @author vivekb
 *
 */
public class InsertQueryInfo extends DMLQueryInfo {
  
  /**
   * 
   */
  private static final long serialVersionUID = 1L;
  private final SelectQueryInfo subSelectInfo;
  private boolean isPutDml = false;


  public InsertQueryInfo(QueryInfoContext qic, SelectQueryInfo selectInfo, Boolean isPutDml) throws StandardException {
    super(qic);
    this.subSelectInfo = selectInfo;
    this.isPutDml = isPutDml;
  }
  
  @Override
  public boolean isInsert() {
    return true;
  }

  public boolean isPutDML() {
    return isPutDml;
  }

  @Override
  public boolean hasSubSelect() {
    return subSelectInfo != null;
  }
  
  public static void checkSupportedInsertSubSelect(SelectQueryInfo sInfo)
      throws StandardException {
    if (sInfo.hasUnionNode() || sInfo.hasIntersectOrExceptNode()) {
      if (!(sInfo.getDriverTableQueryInfo() != null && sInfo
          .getDriverTableQueryInfo().isPartitionedRegion())) {
        // Mark 'Union / Intersect / Except' supported on Replicated Tables
        return;
        // Mark 'Intersect/ Except' Unsupported for Partitioned table
        // Mark 'Union All' Supported for Partitioned table
        // Mark 'Union' Supported for Partitioned table (filtered by Distinct
        // below)
      }
    }
    if (sInfo.isQuery(QueryInfo.HAS_DISTINCT, QueryInfo.HAS_DISTINCT_SCAN,
        QueryInfo.HAS_GROUPBY, QueryInfo.HAS_ORDERBY)
        || sInfo.isOuterJoinSpecialCase()
        || sInfo.hasIntersectOrExceptNode()
        || sInfo.isRemoteGfxdSubActivationNeeded()) {
      throw StandardException
          .newException(SQLState.NOT_IMPLEMENTED,
              "inserts as sub selects not supported for selects which needs aggregation");
    }
  }

  /**
   * @return the subSelectInfo
   */
  public SelectQueryInfo getSubSelectQueryInfo() {
    if (this.hasSubSelect()) {
      return subSelectInfo;
    }
    throw new UnsupportedOperationException(
        "InsertQueryInfo is used for SubSelect only");
  }

  @Override
  public void setInsertAsSubSelect(boolean b, String targetTableName) {
    if (this.hasSubSelect()) {
      subSelectInfo.setInsertAsSubSelect(b, targetTableName);
      return;
    }
    throw new UnsupportedOperationException(
        "InsertQueryInfo is used for SubSelect only");
  }

  @Override
  public boolean isInsertAsSubSelect() {
    if (this.hasSubSelect()) {
      return subSelectInfo.isInsertAsSubSelect();
    }
    return false;
  }

  @Override
  public String getTargetTableName() {
    if (this.hasSubSelect()) {
      return subSelectInfo.getTargetTableName();
    }
    throw new UnsupportedOperationException(
        "InsertQueryInfo is used for SubSelect only");
  }

  @Override
  public boolean isTableVTI() {
    if (this.hasSubSelect()) {
      return subSelectInfo.isTableVTI();
    }
    throw new UnsupportedOperationException(
        "InsertQueryInfo is used for SubSelect only");
  }

  @Override
  public boolean routeQueryToAllNodes() {
    if (this.hasSubSelect()) {
      return subSelectInfo.routeQueryToAllNodes();
    }
    return false;
  }

  @Override
  public boolean stopTraversal() {
    return false;
  }

  @Override
  public boolean skipChildren(Visitable node) throws StandardException {
    return false;
  }

  @Override
  public Visitable visit(Visitable node) throws StandardException {
    return node;
  }

  @Override
  public LocalRegion getRegion() {
    if (region != null) {
      return region;
    }
    if (this.hasSubSelect()) {
      region = subSelectInfo.getRegion();
      return region;
    }
    throw new UnsupportedOperationException(
        "InsertQueryInfo is used for SubSelect only");
  }
  
  private String schema;
  private String tableName;
  private LocalRegion region;
  
  @Override
  public String getSchemaName() {
    return this.schema;
  }
  
  @Override
  public String getTableName() {
    return this.tableName;
  }

  public void setSchemaTableAndRegion(String schema, String table, LocalRegion lr) {
    this.schema = schema;
    this.tableName = table;
    this.region = lr;
  }
}
