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

package com.pivotal.gemfirexd.internal.engine.ui;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.VersionedDataSerializable;
import com.gemstone.gemfire.internal.shared.Version;

public class SnappyRegionStats implements VersionedDataSerializable {

  private boolean isColumnTable = false;
  private String tableName;
  private long rowCount = 0;
  private long sizeInMemory = 0;
  private long sizeSpillToDisk = 0;
  private long totalSize = 0;
  private boolean isReplicatedTable = false;
  private int bucketCount;

  public SnappyRegionStats() {
  }

  public SnappyRegionStats(String tableName) {
    this.tableName = tableName;
  }

  public SnappyRegionStats(String tableName, long totalSize, long sizeInMemory,
      long rowCount, boolean isColumnTable, boolean isReplicatedTable,
      int bucketCount) {
    this.tableName = tableName;
    this.totalSize = totalSize;
    this.sizeInMemory = sizeInMemory;
    this.sizeSpillToDisk = totalSize - sizeInMemory;
    this.rowCount = rowCount;
    this.isColumnTable = isColumnTable;
    this.isReplicatedTable = isReplicatedTable;
    this.bucketCount = bucketCount;
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

  public long getSizeSpillToDisk() {
    return sizeSpillToDisk;
  }

  public void setSizeSpillToDisk(long sizeSpillToDisk) {
    this.sizeSpillToDisk = sizeSpillToDisk;
  }

  public long getTotalSize() {
    return this.totalSize;
  }

  public void setTotalSize(long totalSize) {
    this.totalSize = totalSize;
  }

  public int getBucketCount() {
    return this.bucketCount;
  }

  public void setBucketCount(int bucketCount) {
    this.bucketCount = bucketCount;
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
    combinedStats.setSizeSpillToDisk(stats.sizeSpillToDisk + this.sizeSpillToDisk);
    combinedStats.setTotalSize(stats.totalSize + this.totalSize);
    combinedStats.setColumnTable(this.isColumnTable || stats.isColumnTable);
    combinedStats.setReplicatedTable(this.isReplicatedTable());
    combinedStats.setBucketCount(this.bucketCount);
    return combinedStats;
  }

  private static Version[] serializationVersions = new Version[] { Version.STORE_162 };

  @Override
  public Version[] getSerializationVersions() {
    return serializationVersions;
  }

  public void toDataPre_STORE_1_6_2_0(final DataOutput out) throws IOException {
    DataSerializer.writeString(tableName, out);
    out.writeLong(totalSize);
    out.writeLong(sizeInMemory);
    out.writeLong(sizeSpillToDisk);
    out.writeLong(rowCount);
    out.writeBoolean(isColumnTable);
    out.writeBoolean(isReplicatedTable);
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    toDataPre_STORE_1_6_2_0(out);
    out.writeInt(bucketCount);
  }

  public void fromDataPre_STORE_1_6_2_0(DataInput in) throws IOException {
    this.tableName = DataSerializer.readString(in);
    this.totalSize = in.readLong();
    this.sizeInMemory = in.readLong();
    this.sizeSpillToDisk = in.readLong();
    this.rowCount = in.readLong();
    this.isColumnTable = in.readBoolean();
    this.isReplicatedTable = in.readBoolean();
  }

  @Override
  public void fromData(DataInput in) throws IOException {
    fromDataPre_STORE_1_6_2_0(in);
    this.bucketCount = in.readInt();
  }

  @Override
  public String toString() {
    return "RegionStats for " + tableName + ": totalSize=" + totalSize +
        " sizeInMemory=" + sizeInMemory + " sizeSpillToDisk=" + sizeSpillToDisk +
        " rowCount=" + rowCount + " isColumnTable=" + isColumnTable + " isReplicatedTable=" +
        isReplicatedTable + " bucketCount=" + bucketCount;
  }
}
