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
package com.pivotal.gemfirexd.internal.engine.sql.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.SortedSet;

import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TupleDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * @author yjing
 * 
 */
public final class DistributionDescriptor extends TupleDescriptor {

  public final static int NONE = 0;

  public final static int REPLICATE = 1;

  //Policy number range belonging to policy type partitioning
  //If changing these numbers , also modify method #isPartitioned
  public final static int COLOCATE = 2;

  public final static int PARTITIONBYGENERATEDKEY = 3;

  public final static int PARTITIONBYPRIMARYKEY = 4;

  public final static int PARTITIONBYEXPRESSION = 5;

  public final static int PARTITIONBYRANGE = 6;

  public final static int PARTITIONBYLIST = 7;

  //Policy number range belonging to policy type partitioning
  
  public final static int LOCAL = 8;

  public static boolean TEST_BYPASS_DATASTORE_CHECK = false;

  // the partition policy.
  private final int policy;

  private String[] columns;

  private ArrayList<ArrayList<DataValueDescriptor>> values;

  private final int redundancy;

  private final int maxPartSize;

  private String colocateTable;

  private int[] columnPositions;

  private final boolean isPersistent;

  private SortedSet<String> serverGroups;

  public DistributionDescriptor(int policy, String[] columns, int redundancy,
      int maxPartSize, String colocateTable, boolean isPersistent,
      SortedSet<String> serverGroups) {
    this.policy = policy;
    this.columns = columns != null ? columns.clone() : null;
    this.values = null;
    this.redundancy = redundancy;
    this.maxPartSize = maxPartSize;
    this.colocateTable = colocateTable;
    this.isPersistent = isPersistent;
    this.serverGroups = serverGroups;
  }

  public final String getColocateTableName() {
    return this.colocateTable;
  }

  public void setColocateTableName(String colocatedTable) {
    this.colocateTable = colocatedTable;
  }

  public void addValueSet(ArrayList<DataValueDescriptor> valueSet) {
    if (this.values == null) {
      this.values = new ArrayList<ArrayList<DataValueDescriptor>>();
    }
    this.values.add(valueSet);
  }

  public void setPartitionColumnNames(String[] cols) {
    this.columns = cols != null ? cols.clone() : null;
  }

  public final String[] getPartitionColumnNames() {
    return this.columns;
  }

  public void setColumnPositions(int[] cols) {
    if (cols != null && cols.length > 0) {
      this.columnPositions = cols.clone(); // don't change original
      Arrays.sort(this.columnPositions);
    }
    else {
      this.columnPositions = null;
    }
  }

  public final int[] getColumnPositionsSorted() {
    return this.columnPositions;
  }

  public int getMaxPartSize() {
    return this.maxPartSize;
  }

  public ArrayList<ArrayList<DataValueDescriptor>> getPartitionColumnValues() {
    return this.values;
  }

  public int getPolicy() {
    return this.policy;
  }

  public int getRedundancy() {
    return this.redundancy;
  }

  public boolean getPersistence() {
    return this.isPersistent;
  }
  
  public boolean isPartitioned() {
    //TODO:Asif:  check if this is a good logic
    return  this.policy >= COLOCATE &&  this.policy <= PARTITIONBYLIST;
  }

  public SortedSet<String> getServerGroups() {
    return this.serverGroups;
  }

  public void setServerGroups(SortedSet<String> sgs) {
    this.serverGroups = sgs;
  }

  public void resolveColumnPositions(TableDescriptor td)
      throws StandardException {
    assert td != null: "Table descriptor should be non-null!";
    // the partition column is the generated key
    if (this.columns == null) {
      return;
    }
    this.columnPositions = new int[this.columns.length];

    // Bitmap to ensure no duplicates exist in the list of columns
    boolean[] dupDetectBitmap = new boolean[Limits.DB2_MAX_COLUMNS_IN_TABLE+1];
    int columnPosition = 0;

    // Clear bitmap for dup detection
    Arrays.fill(dupDetectBitmap, false);

    for (int index = 0; index < this.columns.length; ++index) {
      ColumnDescriptor cd = td.getColumnDescriptor(this.columns[index]);
      if (cd == null) {
        SanityManager.DEBUG_PRINT("warning:syntax", "Failed to find column "
            + this.columns[index] + " in TableDescriptor " + td);
        throw StandardException.newException(SQLState.LANG_SYNTAX_ERROR,
            "Failed to find column " + this.columns[index] + " in table "
                + td.getQualifiedName());
      }
      columnPosition = cd.getPosition();
      if (!dupDetectBitmap[columnPosition]) {
        // We haven't seen this column yet, set bit
        dupDetectBitmap[columnPosition] = true;
      }
      else {
        // We already saw this column, throw dup error
        throw StandardException.newException(
            SQLState.LANG_DUPLICATE_COLUMN_NAME_CREATE, this.columns[index]);
      }
      this.columnPositions[index] = cd.getPosition();
    }
    Arrays.sort(this.columnPositions);
  }

  /**
   * Checks if there are any datastores available for this table or database
   * object.
   * 
   * @throws StandardException
   *           if no datastore is available for this table or database object
   *           with SQLState {@link SQLState#LANG_INVALID_MEMBER_REFERENCE}
   */
  public static void checkAvailableDataStore(
      final LanguageConnectionContext lcc,
      final SortedSet<String> serverGroups, final String op)
      throws StandardException {
    final GemFireStore memStore;
    if (lcc != null && !lcc.isConnectionForRemote()
        && (memStore = Misc.getMemStore()).initialDDLReplayDone()
        && memStore.getDDLStmtQueue() != null
        && !TEST_BYPASS_DATASTORE_CHECK) {
      GfxdDistributionAdvisor advisor = memStore.getDistributionAdvisor();
      if (advisor.adviseDataStore(serverGroups, true) == null) {
        throw StandardException.newException(SQLState.NO_DATASTORE_FOUND,
            "execution of " + op + " in " + (serverGroups == null
            || serverGroups.size() == 0 ? "distributed system"
                : "server groups '" + serverGroups + "'"));
      }
    }
  }

  public int getPartitioningColumnIdx(String colName) {
    for (int i = 0; i < columns.length; i++) {
      if (columns[i].equalsIgnoreCase(colName)) {
        return i;
      }
    }
    return -1;
  }
}
