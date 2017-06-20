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

package com.pivotal.gemfirexd.internal.engine.distributed.metadata;

import java.util.Iterator;

import com.gemstone.gemfire.internal.cache.InternalPartitionResolver;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ForeignKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromBaseTable;

/**
 * This class represents a table present in the query. It wraps details about
 * the columns of the table & constraints
 * 
 * @author Asif, swale
 * @since GemFireXD
 * @see FromBaseTable#computeQueryInfo
 */
public final class TableQueryInfo extends AbstractQueryInfo {

  final private int tableID;
  //This is a two dimensional int array whose key ( 0 column) is the actual column position
  //while value ( 1 column) is the virtual position. Thus if the table has key1 & key2
  // such that key1 has actual position 1 & key2 actual position 2,
  // but if the composite key is defined key2 , key1 , then the virtual
  //position ( i.e the value of the SortedMap will be 2, 1)

  final private int[][] pkColumns;
  final private LocalRegion region;
  final private TableDescriptor td;
  //0 based index keeping track of number of PR based tables in the query
  //For the top query , the first PR table will have the number 0 and incremental count for others.
  //For subquery , if it is dependent, then the QueryInfoContext will have 1 reserved for the first table
  // of the independent query on which this is dependent , with which the subquery is to be colocated.
  //Hence for subquery, the count will start from 1.
  private int tableNumberForColocationCriteria ;

  private FromBaseTable baseTableNode;

  //DUMMY UNITIIALIZED
  TableQueryInfo() {
    this.tableID =0;
    this.pkColumns = null;
    this.region = null;
    this.td = null;   
    this.tableNumberForColocationCriteria = -1;
  }

  TableQueryInfo(TableQueryInfo master, QueryInfoContext qic) {
    this.tableID =0;
    this.pkColumns = null;
    this.region = master.region;
    this.td = master.td;  
    //This will be the first table for the correlated subquery
    this.tableNumberForColocationCriteria = 0; 
    qic.addToTableCount(1);
    qic.addToPRTableCount(1);
  }

  public TableQueryInfo(final FromBaseTable node, final QueryInfoContext qic)
      throws StandardException {
    if (qic.getRootQueryInfo().isSelect()) {
      this.baseTableNode = node;
    }

    this.tableID = node.getTableNumber();
    this.td = node.getTableDescriptor();
    this.region = node.getRegion(false);

    // If the region is a Replicated Region or if it is a PR with just
    // itself as a member then we should go with Derby's Activation Object

    //TODO:Asif If the query has multiple PR but only single node system ,we need to tackle it
    // [sumedh] Need to add the replicated tables also since they can tie up
    // two partitioning columns
    this.tableNumberForColocationCriteria = qic.getTableCount();
    qic.addToTableCount(1);
    if (this.region.getDataPolicy().withPartitioning()) {
      qic.addToPRTableCount(1);
    }

    // Asif: Ensure that the array of primary key columns is a
    // sorted one with the actual poistions of the columns present
    // in the table in the increasing order. Currently assuming that
    // this is the case
    final int pks[] = GemFireXDUtils.getPrimaryKeyColumns(this.td);
    this.pkColumns = getIndexKeyColumns(pks);
  }

  public static int[][] getIndexKeyColumns(final int pks[]) {
    if (pks != null) {
      final int[][] idxColumns = new int[pks.length][2];
      idxColumns[0][0] = pks[0];
      idxColumns[0][1] = 1;

      for (int i = 1; i < pks.length; ++i) {
        inner: for (int indx = i - 1; indx > -1; --indx) {
          int currActualPos = idxColumns[indx][0];
          int currVirtualPos = idxColumns[indx][1];
          if (currActualPos < pks[i]) {
            // Found the position
            // Pick the next to sort
            idxColumns[indx + 1][0] = pks[i];
            // Fill virtual poistion
            idxColumns[indx + 1][1] = i + 1;
            break inner;
          }
          else {
            // Advance the current index data to next & create an hole
            idxColumns[indx + 1][0] = currActualPos;
            idxColumns[indx + 1][1] = currVirtualPos;
            if (indx == 0) {
              // Reached the end just set the toSort at 0
              idxColumns[0][0] = pks[i];
              idxColumns[0][1] = i + 1;
            }
          }
        }
      }
      return idxColumns;
    }
    return null;
  }
  
  @Override
  public boolean isPrimaryKeyBased() {
    return this.pkColumns != null;
  }
  
  public int[][] getPrimaryKeyColumns() {
    return this.pkColumns;
  }
  
  public int getTableNumber() {
    return this.tableID;
  }

  @Override
  public LocalRegion getRegion() {
    return this.region;
  }

  @Override
  public final String getTableName() {
    return this.td.getName();
  }

  @Override
  public final String getFullTableName() {
    final String schemaName = getSchemaName();
    return schemaName != null ? (schemaName + '.' + getTableName())
        : getTableName();
  }

  @Override
  public final String getSchemaName() {
    return this.td.getSchemaName();
  }

  TableDescriptor getTableDescriptor() {
    return this.td;
  }

  public int getNumColumns() {
    return this.td.getNumberOfColumns();
  }

  boolean isPartitionedRegion() {
    // If the region is a Replicated Region or if it is a PR with just
    // itself as a member then we should go with Derby's Activation Object
    return this.region.getDataPolicy().withPartitioning();
  }

  boolean isEmptyDataPolicy() {
    // If the region is a Replicated Region or if it is a PR with just
    // itself as a member then we should go with Derby's Activation Object
    return !this.region.getDataPolicy().withStorage();
  }

  String getMasterTableName() {
    assert this.region instanceof PartitionedRegion;
    InternalPartitionResolver rslvr = (InternalPartitionResolver)
        ((PartitionedRegion)this.region).getPartitionResolver();
    return rslvr.getMasterTable(true /* get root master*/);
  }

  @Override
  public String toString() {
    return "TableQueryInfo: region="
        + (this.region != null ? this.region.getFullPath() : "(null)");
  }

  /**
   * @return an int indicating the relative fixed position for the PR tables
   *         occuring in the Select Query. It will be zero based index. For
   *         replicated tables, the number returned is -1
   */
  final int getTableNumberforColocationCriteria() {
    return this.tableNumberForColocationCriteria;
  }

  final void setTableNumberforColocationCriteria(int v) {
    this.tableNumberForColocationCriteria = v;
  }

  int getPartitioningColumnCount() {
    if (this.region.getDataPolicy().withPartitioning()) {
      InternalPartitionResolver rslvr = (InternalPartitionResolver)
          ((PartitionedRegion)this.region).getPartitionResolver();
      return rslvr.getPartitioningColumnsCount();
    }
    else {
      return 0;
    }
  }

  /**
   * 
   * @return ForeignKeyConstraintDescriptor if present or null.
   * @throws StandardException
   */
  ForeignKeyConstraintDescriptor getForeignKeyConstraintDescriptorIfAny()
      throws StandardException {
    ForeignKeyConstraintDescriptor fcd = null;
    ConstraintDescriptorList cdl = td.getConstraintDescriptorList();
    Iterator<?> itr = cdl.iterator();
    while (itr.hasNext()) {
      ConstraintDescriptor cd = (ConstraintDescriptor)itr.next();
      if (cd.getConstraintType() == DataDictionary.FOREIGNKEY_CONSTRAINT) {
        fcd = (ForeignKeyConstraintDescriptor)cd;
        break;
      }
    }
    return fcd;
  }

  boolean isCheckTypeConstraintPresent() throws StandardException {
    ConstraintDescriptorList cdl = td.getConstraintDescriptorList();
    Iterator<?> itr = cdl.iterator();
    boolean present = false;
    while (itr.hasNext()) {
      ConstraintDescriptor cd = (ConstraintDescriptor)itr.next();
      if (cd.getConstraintType() == DataDictionary.CHECK_CONSTRAINT) {
        present = true;
        break;
      }
    }
    return present;
  }

  final FromBaseTable getTableNode() {
    return this.baseTableNode;
  }

  final void setBaseTableNodeAsNull() {
    this.baseTableNode = null;
  }

  @Override
  public boolean isTableVTI() {
    return td.getTableType() == TableDescriptor.VTI_TYPE;
  }

  @Override
  public boolean routeQueryToAllNodes() {
    return this.td.routeQueryToAllNodes();
  }

  @Override
  public int hashCode() {
    return this.region.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other != null && other.getClass() == TableQueryInfo.class) {
      return this.region == ((TableQueryInfo)other).region;
    }
    return false;
  }
}
