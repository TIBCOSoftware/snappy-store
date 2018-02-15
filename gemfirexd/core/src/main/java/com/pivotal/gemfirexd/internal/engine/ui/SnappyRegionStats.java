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

package com.pivotal.gemfirexd.internal.engine.ui;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;

public class SnappyRegionStats implements DataSerializable {

  private boolean isColumnTable = false;
  private String tableName;
  private long rowCount = 0;
  private long sizeInMemory = 0;
  private long totalSize = 0;
  private Boolean isReplicatedTable = false;

  public SnappyRegionStats() {
  }

  public SnappyRegionStats(String tableName) {
    this.tableName = tableName;
  }

  public SnappyRegionStats(String tableName, long totalSize, long sizeInMemory,
      long rowCount, boolean isColumnTable, boolean isReplicatedTable) {
    this.tableName = tableName;
    this.totalSize = totalSize;
    this.sizeInMemory = sizeInMemory;
    this.rowCount = rowCount;
    this.isColumnTable = isColumnTable;
    this.isReplicatedTable = isReplicatedTable;
  }

  public void setTotalSize(long totalSize) {
    this.totalSize = totalSize;
  }

  public String getTableName() {
    return tableName;
  }

  public void setTableName(String tableName) {
    this.tableName = tableName;
  }

  public boolean isColumnTable() {
    return isColumnTable;
  }

  public boolean isReplicatedTable() {
    return isReplicatedTable;
  }


  public void setReplicatedTable(boolean replicatedTable) {
    this.isReplicatedTable = replicatedTable;
  }

  public void setColumnTable(boolean columnTable) {
    isColumnTable = columnTable;
  }

  public long getRowCount() {
    return rowCount;
  }

  public void setRowCount(long rowCount) {
    this.rowCount = rowCount;
  }

  public long getSizeInMemory() {
    return sizeInMemory;
  }

  public void setSizeInMemory(long sizeInMemory) {
    this.sizeInMemory = sizeInMemory;
  }

  public long getTotalSize() {
    return this.totalSize;
  }

  public SnappyRegionStats getCombinedStats(SnappyRegionStats stats) {
    String tableName = this.isColumnTable ? stats.tableName : this.tableName;
    SnappyRegionStats combinedStats = new SnappyRegionStats(tableName);

    if (this.isReplicatedTable()) {
      combinedStats.setRowCount(stats.rowCount);
    } else {
      combinedStats.setRowCount(stats.rowCount + this.rowCount);
    }

    combinedStats.setSizeInMemory(stats.sizeInMemory + this.sizeInMemory);
    combinedStats.setTotalSize(stats.totalSize + this.totalSize);
    combinedStats.setColumnTable(this.isColumnTable || stats.isColumnTable);
    combinedStats.setReplicatedTable(this.isReplicatedTable());
    return combinedStats;
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    DataSerializer.writeString(tableName, out);
    out.writeLong(totalSize);
    out.writeLong(sizeInMemory);
    out.writeLong(rowCount);
    out.writeBoolean(isColumnTable);
    out.writeBoolean(isReplicatedTable);
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    this.tableName = DataSerializer.readString(in);
    this.totalSize = in.readLong();
    this.sizeInMemory = in.readLong();
    this.rowCount = in.readLong();
    this.isColumnTable = in.readBoolean();
    this.isReplicatedTable = in.readBoolean();
  }

  @Override
  public String toString() {
    return "RegionStats for " + tableName + ": totalSize=" + totalSize +
        " sizeInMemory=" + sizeInMemory + " rowCount=" + rowCount +
        " isColumnTable=" + isColumnTable + " isReplicatedTable=" + isReplicatedTable;
  }
}
