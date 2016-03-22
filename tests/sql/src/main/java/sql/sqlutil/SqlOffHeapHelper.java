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
package sql.sqlutil;

import hydra.TestConfig;
import hydra.gemfirexd.FabricServerPrms;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import memscale.OffHeapHelper;
import util.TestException;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;

public class SqlOffHeapHelper {

  /** Return a List of off-heap memory Chunks where lob objects reside
   * 
   * @param aChunk The base chunk for the lobs.
   * @param regionName The name of the region (table) storing the offHeapValue.
   * @param key The key for the offHeapValue.
   * @return A List of Chunks for the off-heap memory segments where the lobs reside.
   */
  public static List<Chunk> getLobChunks(Chunk aChunk, String regionName, Object key) {
    List<Chunk> aList = new ArrayList<Chunk>();
    //for gemfirexd tests, the value in off-heap memory (a row) can refer to other rows in off-heap memory
    // drill down to those other rows to consider those values in off-heap memory
    if (aChunk instanceof OffHeapRowWithLobs) {
      //Log.getLogWriter().info("xxx found OffHeapByteSource ");
      OffHeapRowWithLobs ohbs = (OffHeapRowWithLobs)aChunk;
      int numLobs = ohbs.readNumLobsColumns(false);
      //Log.getLogWriter().info("xxx numLobs is " + numLobs);
      if (numLobs <= 0) {
        throw new TestException("For key " + key + " + in region " + regionName + " the off-heap memory byte source " +
            ohbs + " has lobs " + true + ", but the number of lobs is " + numLobs);
      }
      for (int i = 1; i <= numLobs; i++) { // element 0 is the base row, so start with index 1
        Object byteSrc = ohbs.getGfxdByteSource(i);
        //Log.getLogWriter().info("xxx processing lob " + i);
        if (byteSrc instanceof Chunk) {
          //Log.getLogWriter().info("xxx lob is a chunk");
          Chunk lobChunk = (Chunk)byteSrc;
          aList.add(lobChunk);
        }
      }
    }
    return aList;
  }
  
  /** Return the off-heap memory size configured with FabricServerPrms.
   * 
   * @return The off-heap memory size or null if none.
   */
  public static String getOffHeapMemorySize() {
    String offHeapMemorySize = TestConfig.tab().stringAt(FabricServerPrms.offHeapMemorySize, null);
    return offHeapMemorySize;
  }
  
  /** Return whether the given Region is a global index table.
   * 
   * @param aRegion The region to test.
   * @return true if aRegion is a global index table, false otherwise.
   */
  private static boolean isGlobalIndexTable(Region aRegion) {
    Object attr = aRegion.getUserAttribute();
    if (attr != null) {
      if (attr instanceof GemFireContainer) {
        GemFireContainer container = (GemFireContainer)attr;
        return container.isGlobalIndex();
      }
    }
    return false;
  }
  
  /** Verify that all user tables (and excluding system tables) are enabled with
   *  off-heap memory.
   */
  public static void verifyUserTablesEnabledWithOffHeap() {
    Set<Region<?, ?>> allRegions = OffHeapHelper.getAllRegions();
    if (allRegions == null) {
      return;
    }
    List<String> userTablesList = new ArrayList<String>();
    for (Region aRegion: allRegions) {
      if (!aRegion.getAttributes().getDataPolicy().withStorage()) {
        continue;
      }
      if (aRegion.getAttributes().getDataPolicy().withPartitioning()) {
        if (aRegion.getAttributes().getPartitionAttributes().getLocalMaxMemory() == 0) {
          continue;
        }
      }
      String regionName = aRegion.getFullPath();
      boolean isSystemTable = regionName.startsWith("/SYS");
      boolean isSchema = (regionName.indexOf("/") == regionName.lastIndexOf("/"));
      boolean isGlobalIndexTable = isGlobalIndexTable(aRegion);
      boolean expectOffHeapEnabled = !isSystemTable & !isSchema & !isGlobalIndexTable;
      if (expectOffHeapEnabled) {
        userTablesList.add(regionName);
      }
    }
    OffHeapHelper.verifyRegionsEnabledWithOffHeap(userTablesList);
  }
  
}
