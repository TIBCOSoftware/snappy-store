
/*

 Derived from source files from the Derby project.

 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to you under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

/*
 This file was based on the MemStore patch written by Knut Magne, published
 under the Derby issue DERBY-2798 and released under the same license,
 ASF, as described above. The MemStore patch was in turn based on Derby source
 files.
*/

package com.pivotal.gemfirexd.internal.engine.access.index;

import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Properties;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.CustomEvictionAttributes;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.EvictionAttributes;
import com.gemstone.gemfire.cache.EvictionCriteria;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.partitioned.PREntriesIterator;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

/**
 * A heap object corresponds to an instance of a heap conglomerate. It caches
 * information which makes it fast to open heap controllers from it.
 */
public final class GlobalHashIndex extends MemIndex {

  /* Constructors for This class: */
  /**
   * Zero arg constructor for Monitor to create empty object.
   */
  public GlobalHashIndex() {
  }

  @Override
  protected void allocateMemory(Properties properties, int tmpFlag)
      throws StandardException {
    // Get base table region.
    final PartitionAttributesFactory<?, ?> pafact =
      new PartitionAttributesFactory<Object, Object>();
    RegionAttributes<?, ?> ra = this.baseContainer.getRegionAttributes();
    final PartitionAttributes<?, ?> pa = ra.getPartitionAttributes();
    // check if parent region is partitioned
    if (pa == null) {
      throw StandardException.newException(SQLState.TABLE_NOT_PARTITIONED,
          this.baseContainer.getQualifiedTableName(),
          "creating global index (region attributes: " + ra + ')');
    }
    // global index does not support case-sensitivity setting
    if (properties.containsKey(GfxdConstants.INDEX_CASE_SENSITIVE_PROP)) {
      throw StandardException.newException(
          SQLState.INDEX_CASE_INSENSITIVITY_NOT_SUPPORTED,
          "primary/unique key constraint");
    }
    AttributesFactory<?, ?> afact = new AttributesFactory<Object, Object>();
    if (ra.getDataPolicy().withHDFS()) {
      LogWriterI18n log = InternalDistributedSystem.getLoggerI18n();

      CustomEvictionAttributes cea = ra.getCustomEvictionAttributes();
      if (cea == null 
          && ra.getDataPolicy().withPersistence()
          && Version.GFXD_13.compareTo(GemFireXDUtils.getCurrentDDLVersion()) < 0) {
        if (log.fineEnabled()) {
          log.fine("Creating global index without HDFS storage for table " + baseContainer.getTableName());
        }
        
        // #51382, we skip writing the index to HDFS
        afact.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
        
        // Even if we don't plan on using hdfs, make sure that the proper 
        // eviction attributes are set on the region.
        GemFireCacheImpl gfi = Misc.getMemStore().getGemFireCache();
        ra = gfi.setEvictionAttributesForLargeRegion(ra);
      } else {
        afact.setDataPolicy(ra.getDataPolicy());
        afact.setHDFSStoreName(ra.getHDFSStoreName());
        afact.setHDFSWriteOnly(ra.getHDFSWriteOnly());

        EvictionCriteria ec = new GlobalIndexCustomEvictionCriteria();
        afact.setCustomEvictionAttributes(ec, 0L, 0L);
      }
      
      if (ra.getDataPolicy().withPersistence()) {
        afact.setDiskStoreName(ra.getDiskStoreName());
        afact.setDiskSynchronous(ra.isDiskSynchronous());
        // also copy overflow to disk eviction policy
        EvictionAttributes evictAttrs = ra.getEvictionAttributes();
        if (evictAttrs != null && evictAttrs.getAction().isOverflowToDisk()) {
          afact.setEvictionAttributes(evictAttrs);
        }
      }
    }
    else if (ra.getDataPolicy().withPersistence()) {
      afact.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
      afact.setDiskStoreName(ra.getDiskStoreName());
      afact.setDiskSynchronous(ra.isDiskSynchronous());
      // also copy overflow to disk eviction policy
      EvictionAttributes evictAttrs = ra.getEvictionAttributes();
      if (evictAttrs != null && evictAttrs.getAction().isOverflowToDisk()) {
        afact.setEvictionAttributes(evictAttrs);
      }
    }
    else {
      afact.setDataPolicy(DataPolicy.PARTITION);
    }
    pafact.setLocalMaxMemory(pa.getLocalMaxMemory());
    pafact.setTotalNumBuckets(pa.getTotalNumBuckets());
    pafact.setRedundantCopies(pa.getRedundantCopies());
    pafact.setRecoveryDelay(pa.getRecoveryDelay());
    afact.setPartitionAttributes(pafact.create());
    afact.setConcurrencyLevel(ra.getConcurrencyLevel());
    afact.setInitialCapacity(ra.getInitialCapacity());
    afact.setConcurrencyChecksEnabled(ra.getConcurrencyChecksEnabled());
    properties.put(GfxdConstants.REGION_ATTRIBUTES_KEY, afact.create());
    properties.put(MemIndex.PROPERTY_BASECONGLOMID, this.baseContainer.getId());
  }

  public int getType() {
    return GLOBALHASHINDEX;
  }

  @Override
  public String getIndexTypeName() {
    return GLOBAL_HASH_INDEX;
  }

  @Override
  protected MemIndexCostController newMemIndexCostController() {
    Hash1IndexCostController retval = new Hash1IndexCostController();
    return retval;
  }

  @Override
  protected MemIndexScanController newMemIndexScanController() {
    GlobalHashIndexScanController retval = new GlobalHashIndexScanController();
    return retval;
  }

  @Override
  protected MemIndexController newMemIndexController() {
    GlobalHashIndexController retval = new GlobalHashIndexController();
    return retval;
  }

  /**
   * Return my format identifier.
   * 
   * @see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId
   */
  @Override
  public int getTypeFormatId() {
    throw new UnsupportedOperationException("not storable");
  }

  @Override
  public void dumpIndex(String marker) {
    if (marker == null) {
      marker = "Global hash index iteration";
    }
    PartitionedRegion region = (PartitionedRegion)this.container.getRegion();
    String idMark = " [ID: " + this.baseContainer.getId() + ']';
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
        "##### For GlobalHashIndex " + this.container + idMark + " Region "
            + region + marker + " begin #####");

    PREntriesIterator<?> irl = (PREntriesIterator<?>)region.getSharedDataView()
        .getLocalEntriesIterator((InternalRegionFunctionContext)null, false,
            false, true, region);
    final String lineSep = SanityManager.lineSeparator;
    StringBuilder sb = new StringBuilder().append(lineSep);
    while (irl.hasNext()) {
      RowLocation rl = (RowLocation)irl.next();
      sb.append("\tkey=");
      ArrayUtils.objectString(rl.getKey(), sb);
      sb.append(", value=");
      BucketRegion br = irl.getHostedBucketRegion();
      {
        @Released Object v = ((RegionEntry)rl).getValueOffHeapOrDiskWithoutFaultIn(br);
        try {
          ArrayUtils.objectStringNonRecursive(v, sb);
        } finally {
          OffHeapHelper.release(v);
        }
      }
      sb.append(", bucketId=");
      sb.append(br.getId());
      sb.append(", isPrimary=");
      sb.append(br.getBucketAdvisor().isPrimary());
      sb.append(lineSep);
      if (sb.length() > (4 * 1024 * 1024)) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX, sb.toString());
        sb.setLength(0);
      }
    }
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX, sb.toString());

    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
        "##### For GlobalHashIndex " + this.container + idMark + " Region "
            + region + marker + " end #####");
  }
}

class GlobalIndexCustomEvictionCriteria implements EvictionCriteria<Object, Object> {

  @Override
  public Iterator<Entry<Object, Object>> getKeysToBeEvicted(long currentMillis,
      Region<Object, Object> region) {
    return null;
  }

  @Override
  public boolean doEvict(EntryEvent<Object, Object> event) {
    final EntryEventImpl ev = (EntryEventImpl)event;
    final RegionEntry re = ev.getRegionEntry();
    LocalRegion region = ev.getRegion();
    if (region.getLogWriterI18n().fineEnabled()) {
      region.getLogWriterI18n().fine(
          " The entry is " + re + " and the event is " + event
              + " re marked for eviction " + re.isMarkedForEviction());
    }

    if (re != null) {
      if (ev.getTXState() == null && re.hasAnyLock()) {
        return false;
      }
      if (re.isMarkedForEviction()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isEquivalent(EvictionCriteria<Object, Object> other) {
    return false;
  }
}
