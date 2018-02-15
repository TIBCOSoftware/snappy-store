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
import java.util.ArrayList;
import java.util.List;

import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;

public class SnappyRegionStatsCollectorResult extends GfxdDataSerializable {
  private transient List<SnappyRegionStats> combinedStats = new ArrayList<>();
  private transient List<SnappyIndexStats> indexStats = new ArrayList<>();


  public void addRegionStat(SnappyRegionStats stats) {
    combinedStats.add(stats);
  }
  public void addIndexStat(SnappyIndexStats stats) {
    indexStats.add(stats);
  }
  public void addAllIndexStat(List<SnappyIndexStats> stats) {
    indexStats.addAll(stats);
  }

  public SnappyRegionStatsCollectorResult() {
  }

  public List<SnappyRegionStats> getRegionStats() {
    return combinedStats;
  }
  public List<SnappyIndexStats> getIndexStats() {
    return indexStats;
  }

  @Override
  public byte getGfxdID() {
    return SNAPPY_REGION_STATS_RESULT;
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    out.writeInt(combinedStats.size());
    for (SnappyRegionStats stats : combinedStats) {
      InternalDataSerializer.writeString(stats.getTableName(), out);
      InternalDataSerializer.writeLong(stats.getTotalSize(), out);
      InternalDataSerializer.writeLong(stats.getSizeInMemory(), out);
      InternalDataSerializer.writeLong(stats.getRowCount(), out);
      InternalDataSerializer.writeBoolean(stats.isColumnTable(), out);
      InternalDataSerializer.writeBoolean(stats.isReplicatedTable(), out);
    }
    out.writeInt(indexStats.size());
    for (SnappyIndexStats stats : indexStats) {
      InternalDataSerializer.writeString(stats.getIndexName(), out);
      InternalDataSerializer.writeLong(stats.getRowCount(), out);
      InternalDataSerializer.writeLong(stats.getSizeInMemory(), out);
    }
  }

  @Override
  public void fromData(DataInput in) throws IOException {
    int size = in.readInt();
    while (size > 0) {
      size--;
      String regionName = InternalDataSerializer.readString(in);
      long totalSize = InternalDataSerializer.readLong(in);
      long memorySize = InternalDataSerializer.readLong(in);
      long count = InternalDataSerializer.readLong(in);
      boolean isColumnTable = InternalDataSerializer.readBoolean(in);
      boolean isReplicated = InternalDataSerializer.readBoolean(in);
      addRegionStat(new SnappyRegionStats(regionName, totalSize, memorySize, count, isColumnTable, isReplicated));
    }
    int numIndex = in.readInt();
    while (numIndex > 0) {
      numIndex--;
      String indexName = InternalDataSerializer.readString(in);
      long rowCount = InternalDataSerializer.readLong(in);
      long sizeInMemory = InternalDataSerializer.readLong(in);
      addIndexStat(new SnappyIndexStats(indexName, rowCount, sizeInMemory));
    }
  }

}
