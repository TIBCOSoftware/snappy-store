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
package com.pivotal.gemfirexd.tools.sizer;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.regex.Pattern;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSParallelGatewaySenderQueue;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.distributed.DistributedLockService;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisee;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.cache.partitioned.PREntriesIterator;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventImpl;
import com.gemstone.gemfire.internal.cache.wan.parallel.ConcurrentParallelGatewaySenderQueue;
import com.gemstone.gemfire.internal.cache.wan.serial.SerialGatewaySenderQueue;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.pivotal.gemfirexd.Constants;
import com.pivotal.gemfirexd.Constants.QueryHints.SizerHints;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableHashtable;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.snappy.ColumnBatchKey;

/**
 * This class helps in finding out memory footprint per Region and divides
 * memory usage between GemFireXD, GemFire and java usages. <BR>
 * <BR>
 * Optionally, it prints class inclusion sequence much like stack trace and per
 * member memory. <BR>
 * <BR>
 * Indexes are accounted separately than regions. <BR>
 * 
 * @author soubhikc
 * 
 */
public class ObjectSizer {
  static GemFireXDInstrumentation SIZE_OF_UTIL = GemFireXDInstrumentation
      .getInstance();
  static final com.gemstone.gemfire.cache.util.ObjectSizer GEM_SIZER =
      o -> (int)SIZE_OF_UTIL.sizeof(o);

  private final static ObjectSizer _this = new ObjectSizer();

  private ObjectSizer() {
  }

  public static ObjectSizer getInstance(final boolean isNew) {
    return isNew ? new ObjectSizer() : _this;
  }

  static Runtime runtime = Runtime.getRuntime();

  private boolean withMemoryFootPrint;

  private boolean logObjectGraph;

  private boolean logTopConsumers;

  private boolean traceOutput;

  private boolean traceVerbose;

  private boolean withSecondaries;

  private boolean initializeMode = true;

  private boolean isForInternalUse = false;

  private final Map<Object, ArrayList<Object>> initialset = new java.util.IdentityHashMap<Object, ArrayList<Object>>();

  private final com.gemstone.gnu.trove.THashMap seen = new com.gemstone.gnu.trove.THashMap(
      com.gemstone.gnu.trove.TObjectIdentityHashingStrategy.getInstance());

  private final HashMap<Class<?>, Pair> classBreakup = new HashMap<Class<?>, Pair>();

  private long gemfirexdSize = 0;

  private long gfeSize = 0;

  private long othersSize = 0;

  private String keyDelimeter;

  public final static String logPrefix = "objectsizer: ";

  public final static String sizerHints = Property.PROPERTY_RUNTIME_PREFIX
      + ".sizerHints";

  public static interface PreQualifier {
    boolean qualifyPartialRow(String tableName, String indexName,
        String indexType, String queueName, String queueType,
        long constantOverhead) throws StandardException;
  };

  public static final long SZ_REF = ReflectionSingleObjectSizer.REFERENCE_SIZE;

  public void initialize(boolean isForInternalUse, String keyDelimiter) {

    this.initializeMode = true;

    this.withMemoryFootPrint = false;
    this.logObjectGraph = false;
    this.logTopConsumers = false;
    this.traceOutput = false;
    this.traceVerbose = false;
    this.withSecondaries = true;
    this.isForInternalUse = isForInternalUse;
    this.keyDelimeter = keyDelimiter;

    GemFireContainer regionContainer = null;
    GemFireContainer indexContainer = null;

    for (final GemFireContainer c : Misc.getMemStore().getAllContainers()) {

      if (c == null) {
        continue;
      }

      Region<?, ?> region = c.getRegion();

      if (region == null) {
        if (c.getBaseContainer() != null && indexContainer == null) {
          if (c.getBaseContainer().getQualifiedTableName()
              .equalsIgnoreCase("SYS.SYSTABLES")) {
            indexContainer = c;
          }
        }
        continue;
      }

      if (c.getQualifiedTableName().equalsIgnoreCase("SYS.SYSTABLES")) {
        /* 
         * warmup region so that standard objects gets excluded
         * while accounting for other regions.
         */
        regionContainer = c;
        continue;
      }

    }

    if (SanityManager.DEBUG) {
      if (regionContainer == null || indexContainer == null) {
        if (!this.isForInternalUse) {
          SanityManager
              .THROWASSERT("ObjectSizer: No initialization regions "
                  + "found, table=" + regionContainer + ", index="
                  + indexContainer);
        }
      }
    }

    try {
      if (this.withMemoryFootPrint) {
        if (!this.isForInternalUse) {
          SanityManager.DEBUG_PRINT("TRACE", logPrefix
              + "Initializing region footprint with " + regionContainer);
        }
        determineRegionMemoryFootPrint(regionContainer, null, null, null);

        if (!this.isForInternalUse) {
          SanityManager.DEBUG_PRINT("TRACE", logPrefix
              + "Initializing index footprint with " + indexContainer);
        }
        estimateIndexFootPrint(indexContainer);

        this.initializeMode = false;
      }

    } catch (Exception e) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "GfxdConfigMessage#getTargetContainers", e);
    } finally {
      this.reset(true);
    }

  }

  public void setForInternalUse(boolean isForInternalUse) {
    this.isForInternalUse = isForInternalUse;
  }

  public void setQueryHints(FormatableHashtable queryHints) {
    if (queryHints == null) {
      return;
    }

    Object o = queryHints.get(sizerHints);
    if (o != null) {
      assert (o instanceof String);
      String[] settings = ((String)o).split(",");
      for (String v : settings) {
        if (v == null || v.length() <= 0) {
          continue;
        }

        SizerHints hint;
        try {
          hint = SizerHints.valueOf(v);
        } catch (RuntimeException re) {
          // ignore
          continue;
        }
        switch (hint) {
          case withMemoryFootPrint:
            this.withMemoryFootPrint = true;
            break;
          case logObjectReferenceGraph:
            this.logObjectGraph = true;
            break;
          case logTopConsumers:
            this.logTopConsumers = true;
            break;
          case traceOutput:
            this.traceOutput = true;
            break;
          case traceVerbose:
            this.traceVerbose = true;
            break;
          default:
            break;
        }
      }
    }

    o = queryHints.get(Constants.QueryHints.withSecondaries.name());

    if (o != null) {
      assert (o instanceof String);
      this.withSecondaries = Boolean.parseBoolean(((String)o));
    }

  }

  public static void getTargetContainers(
      ArrayList<GemFireContainer> targetRegions) {
    for (final GemFireContainer c : Misc.getMemStore().getAllContainers()) {

      if (c == null) {
        continue;
      }

      // [sumedh] shouldn't this be enough instead of looking at the
      // region and its split etc?
      //
      // SB: yes, now that we have clear way to identify user tables and
      // initialization
      // is separated out, this should be enough along with user defined
      // temporary
      // tables (local regions).
      if (!c.isApplicationTable() || c.isTemporaryContainer()
          || !c.isInitialized()) {
        continue;
      }

//      if (c.getRegion() != null && c.getRegion().getHDFSStoreName() != null) {
//        continue;
//      }

      /*
      Region<?, ?> region = c.getRegion();

      if (region == null) {
        continue;
      }

      if (region instanceof LocalRegion) {
        if (((LocalRegion)region).isUsedForMetaRegion()) {
          continue;
        }
      }

      if (region.getFullPath().contains("/__PR")) {
        continue;
      }
      
      if (region.getFullPath().startsWith("/SESSION")) {
        continue;
      }

      if (region.getFullPath().startsWith("/SYS/")) {
        continue;
      }

      /*
       * User Tables are always nested one level deep.
       *
      String[] reg = region.getFullPath().split("/");
      if (reg.length < 3) {
        continue;
      }
      */

      targetRegions.add(c);
    }
  }

  public void logSizes(LinkedHashMap<String, Object[]> sizes) {

    long totalSz = 0;
    StringBuilder sb = new StringBuilder();
    Iterator<Map.Entry<String, Object[]>> sz = sizes.entrySet().iterator();

    for (int i = 0, size = sizes.size(); sz.hasNext(); i++) {

      Map.Entry<String, Object[]> sze = sz.next();
      String profiledObjectName = sze.getKey();
      final long[] szeVal = (long[])sze.getValue()[0];
      long gemfirexdSize = szeVal[0], gfeSize = szeVal[1], otherSize = szeVal[2];
      totalSz += gemfirexdSize + gfeSize + otherSize;
      if (i == 0) {
        sb.append(logPrefix).append("Table (").append(profiledObjectName)
            .append("\n\tsize ").append(totalSz).append(" = ")
            .append(gemfirexdSize).append("+").append(gfeSize).append("+")
            .append(otherSize).append(" (").append(totalSz / 1048576.0)
            .append(" mb)").append("\n");
      }
      else if (i < (size - 1)) {

        long tSz = gemfirexdSize + gfeSize + otherSize;
        sb.append("\t").append(logPrefix).append("Index (")
            .append(profiledObjectName).append(") ").append(tSz).append(" = ")
            .append(gemfirexdSize).append("+").append(gfeSize).append("+")
            .append(otherSize).append(" (").append(tSz / 1048576.0)
            .append(" mb)").append("\n");
      }
      else {
        long entrySz = szeVal[0];
        long entryCount = szeVal[1];
        long maxEntrySz = szeVal[2];
        sb.append("\t").append(logPrefix).append("Region Entry Size ")
            .append(entrySz).append(" (").append(entrySz / 1048576.0)
            .append(" mb) for ").append(entryCount)
            .append(" rows with max entry size ").append(maxEntrySz)
            .append("\n");
      }
    }
    sb.append("\t\t").append(logPrefix).append("Total: ").append(totalSz)
        .append(" (").append(totalSz / 1048576.0).append(" mb) ").append("\n");

    if (!this.isForInternalUse) {
      SanityManager.DEBUG_PRINT("info:LOG_SIZES", sb.toString());
    }
  }

  public long sizeOfObject(final Object root, Exclusions exclusions)
      throws IllegalArgumentException, IllegalAccessException,
      InterruptedException {
    if (exclusions == null) {
      exclusions = this.new Exclusions();
    }
    long retVal = this.startEstimation(root, exclusions);
    this.reset(false);
    return retVal;
  }

  public LinkedHashMap<String, Object[]> size(final GemFireContainer c,
      final PreQualifier preQualifier) throws IllegalArgumentException,
      IllegalAccessException, InterruptedException, StandardException {
    final LinkedHashMap<String, Object[]> retEstimates = new LinkedHashMap<String, Object[]>();
    try {

      final String baseTableContainerName = c.getQualifiedTableName();

      final LocalRegion reg = c.getRegion();

      final GfxdIndexManager idxMgr = (GfxdIndexManager)reg.getIndexUpdater();

      List<GemFireContainer> indexes = (idxMgr != null ? idxMgr.getAllIndexes()
          : Collections.<GemFireContainer> emptyList());

      estimateRegionEntryValueSizes(c, retEstimates, preQualifier);

      if (!this.isForInternalUse) {
        final Set<GatewaySender> senders = c.getGatewaySenders(c.getRegion());
        final Set<AsyncEventQueue> asyncQueues = c.getAsyncEventQueues(c
            .getRegion());
        estimateQueueSizes(c, senders, asyncQueues, retEstimates,
            baseTableContainerName, preQualifier);
      }

      estimateIndexEntryValueSizes(baseTableContainerName, indexes,
          retEstimates, preQualifier);

      this.reset(true);

      if (this.withMemoryFootPrint) {
        determineRegionMemoryFootPrint(c, baseTableContainerName, retEstimates,
            preQualifier);

        determineIndexMemoryFootPrint(baseTableContainerName, indexes,
            retEstimates, preQualifier);
      }

    } finally {
      this.initializeMode = false;
      this.reset(true);
    }

    return retEstimates.size() > 0 ? retEstimates : null;
  }

  public void done() {
    this.initialset.clear();
    this.initializeMode = true;
    cleanupGarbage();
  }

  public long getConsumedMemory() throws InterruptedException {
    cleanupGarbage();

    if (!this.isForInternalUse) {
      System.out.println(logPrefix + "Total: " + runtime.totalMemory()
          + " free " + runtime.freeMemory());
    }
    return (runtime.totalMemory() - runtime.freeMemory());
  }

  private void estimateRegionEntryValueSizes(final GemFireContainer c,
      final LinkedHashMap<String, Object[]> retEstimates,
      final PreQualifier preQualifier) throws StandardException {

    final long constantOverhead = c.estimateMemoryOverhead(SIZE_OF_UTIL);

    if (preQualifier != null
        && !preQualifier.qualifyPartialRow(c.getQualifiedTableName(), null,
            null, null, null, constantOverhead)) {
      return;
    }

    if (!this.isForInternalUse) {
      SanityManager.DEBUG_PRINT("TRACE", logPrefix
          + "Estimating Entry Size/Value Size for " + c);
    }

    final String containerName = c.getQualifiedTableName();

    // estimate Data size i.e. region's values size
    final Iterator<?> entryIter = c.getEntrySetIterator(null,
        !this.withSecondaries, 0, false);

    iterateRegionEntries(c, retEstimates, containerName, constantOverhead,
        entryIter);
  }

  private void estimateQueueSizes(final GemFireContainer container,
      Set<GatewaySender> senders, Set<AsyncEventQueue> asyncQueues,
      LinkedHashMap<String, Object[]> retEstimates,
      final String baseTableContainerName, PreQualifier preQualifier)
      throws StandardException {

    for (AsyncEventQueue q : asyncQueues) {

      AsyncEventQueueImpl asyncQ = (AsyncEventQueueImpl)q;

      final long constantOverhead = asyncQ.estimateMemoryFootprint(SIZE_OF_UTIL);
      final String qId = q.getId();
      final String qType = (q.isParallel() ? "PARALLEL " : "SERIAL ")
          + (qId
              .startsWith(HDFSStoreFactoryImpl.DEFAULT_ASYNC_QUEUE_ID_FOR_HDFS) ? "HDFS "
              : "") + "QUEUE";

      if (preQualifier != null
          && !preQualifier.qualifyPartialRow(baseTableContainerName, null,
              null, qId, qType, constantOverhead)) {
        continue;
      }

      SanityManager.DEBUG_PRINT("TRACE", logPrefix
          + "Estimating Entry Size/Value Size for asyncqueue " + qId);

      iterateRegionQueue(container, asyncQ.getSender().getQueues(),
          retEstimates, baseTableContainerName + this.keyDelimeter + "" /*indexName*/
              + this.keyDelimeter + "" /*indexType*/+ this.keyDelimeter + qId
              + this.keyDelimeter + qType, constantOverhead);
    }

    for (GatewaySender g : senders) {

      AbstractGatewaySender sender = (AbstractGatewaySender)g;

      final long constantOverhead = sender.estimateMemoryFootprint(SIZE_OF_UTIL);
      final String qType = (g.isParallel() ? "PARALLEL " : "SERIAL ")
          + "GATEWAY";

      if (preQualifier != null
          && !preQualifier.qualifyPartialRow(baseTableContainerName, null,
              null, g.getId(), qType, constantOverhead)) {
        continue;
      }

      SanityManager.DEBUG_PRINT("TRACE", logPrefix
          + "Estimating Entry Size/Value Size for gateway " + g.getId());

      iterateRegionQueue(
          container,
          sender.getQueues(),
          retEstimates,
          baseTableContainerName + this.keyDelimeter + "" /*indexName*/
              + this.keyDelimeter + "" /*indexType*/+ this.keyDelimeter
              + g.getId() + this.keyDelimeter + qType, constantOverhead);
    }

  }

  private void iterateRegionQueue(final GemFireContainer container,
      Set<RegionQueue> queues, LinkedHashMap<String, Object[]> retEstimates,
      String key, final long constantOverhead) throws StandardException {

    for (RegionQueue q : queues) {
      final LocalRegion r; 
      Class<?> c = q.getClass();
      if (c == SerialGatewaySenderQueue.class) {
        SerialGatewaySenderQueue serialQ = (SerialGatewaySenderQueue)q;
        r = (LocalRegion) serialQ.getRegion();
      }
      else if (c == ConcurrentParallelGatewaySenderQueue.class) {
    	  ConcurrentParallelGatewaySenderQueue parallelQ = (ConcurrentParallelGatewaySenderQueue)q;
        r = (LocalRegion) parallelQ.getRegion();
      }
      else if (c == HDFSParallelGatewaySenderQueue.class) {
        HDFSParallelGatewaySenderQueue hdfsParallelQ = (HDFSParallelGatewaySenderQueue)q;
        r = (LocalRegion) hdfsParallelQ.getRegion();
      }
      else {
        if (SanityManager.ASSERT) {
          SanityManager.THROWASSERT("UnKnown Queue type " + c);
        }
        r = null;
        throw StandardException.newException(SQLState.UNKNOWN_QUEUE_TYPE);
      }

      final Iterator<?> entryIter = GemFireContainer.getEntrySetIteratorForRegion (r);
      iterateRegionEntries(container, retEstimates, key, constantOverhead,
          entryIter);
    }

  }

  private void iterateRegionEntries(final GemFireContainer c,
      final LinkedHashMap<String, Object[]> retEstimates, final String key,
      final long constantOverhead, final Iterator<?> entryIter)
      throws StandardException {
    final PREntriesIterator<?> prEntryIter;
    final boolean generatDistributionInfo;
    if (!this.isForInternalUse && entryIter instanceof PREntriesIterator<?>) {
      prEntryIter = (PREntriesIterator<?>)entryIter;
      generatDistributionInfo = true;
    }
    else {
      prEntryIter = null;
      generatDistributionInfo = false;
    }

    boolean isSingleEntrySizeComputed = false;
    long singleEntrySize = 0;
    long entrySize = 0;
    long keySize = 0;
    long valInMemorySize = 0;
    long valInOffheapSize = 0;
    long maxSize = 0;
    long maxOffheapSize = 0;
    long rowCount = 0;
    long columnRowCount = 0;
    long keyInMemoryCount = 0;
    long valInMemoryCount = 0;
    long valInOffheapCount = 0;
    final boolean agentAttached = SIZE_OF_UTIL.isAgentAttached();
    boolean isValueTypeEvaluated = false;
    final boolean isColumnTable = isColumnTable(c.getQualifiedTableName());
    int numColumnsInColumnTable = -1;
    long dvdArraySize = 0;
    boolean gatewayEntries = false;
    boolean offHeapEntries = false;
    final LocalRegion region = c.getRegion();
    int maxPrimaryBucketKeyLength = 0, maxSecondaryBucketKeyLength = 0;
    int maxPrimaryBucketValueLength = 0, maxSecondaryBucketValueLength = 0;

    final TreeMap<Integer, Long> primaryBucketDist = generatDistributionInfo ? new TreeMap<Integer, Long>()
        : null;
    final TreeMap<Integer, Long> secondaryBucketDist = generatDistributionInfo ? new TreeMap<Integer, Long>()
        : null;

    long currentBucketEntryCount = 0;
    int currentBucketId = -1;

    while (entryIter.hasNext()) {
      final Object entry = entryIter.next();
      if (entry == null) {
        continue;
      }
      
      rowCount++;

      if (generatDistributionInfo) {
        assert prEntryIter != null;
        currentBucketEntryCount++;
        if (currentBucketId != prEntryIter.getBucketId()) {
          if (currentBucketId != -1 && currentBucketEntryCount > 0) {
            if (prEntryIter.getBucket().getBucketAdvisor().isPrimary()) {
              primaryBucketDist.put(currentBucketId, currentBucketEntryCount);
              int len = Misc.getLength(currentBucketId);
              maxPrimaryBucketKeyLength = len > maxPrimaryBucketKeyLength ? len
                  : maxPrimaryBucketKeyLength;
              len = Misc.getLength(currentBucketEntryCount);
              maxPrimaryBucketValueLength = len > maxPrimaryBucketValueLength ? len
                  : maxPrimaryBucketValueLength;
            }
            else {
              secondaryBucketDist.put(currentBucketId, currentBucketEntryCount);
              int len = Misc.getLength(currentBucketId);
              maxSecondaryBucketKeyLength = len > maxSecondaryBucketKeyLength ? len
                  : maxSecondaryBucketKeyLength;
              len = Misc.getLength(currentBucketEntryCount);
              maxSecondaryBucketValueLength = len > maxSecondaryBucketValueLength ? len
                  : maxSecondaryBucketValueLength;
            }
          }
          currentBucketId = prEntryIter.getBucketId();
          currentBucketEntryCount = 0;
        }
      }

      if (agentAttached) {
        entrySize += SIZE_OF_UTIL.sizeof(entry);
        if (entry instanceof DiskEntry) {
          entrySize += SIZE_OF_UTIL.sizeof(((DiskEntry)entry).getDiskId());
        }
      }
      else if (!isSingleEntrySizeComputed) {
        singleEntrySize = SIZE_OF_UTIL.sizeof(entry);
        if (entry instanceof DiskEntry) {
          singleEntrySize += SIZE_OF_UTIL.sizeof(((DiskEntry)entry).getDiskId());
        }
        isSingleEntrySizeComputed = true;
      }

      @Retained @Released Object value = null;
      Object k;
      ColumnBatchKey batchKey = null;
      AbstractRegionEntry re = null;
      try {
        // base region
        if (entry instanceof RowLocation) {
          final RowLocation rl = (RowLocation)entry;
          k = rl.getRawKey();
          if (entry instanceof AbstractRegionEntry) {
            re = (AbstractRegionEntry)entry;
            SimpleMemoryAllocatorImpl.skipRefCountTracking();
            value = RegionEntryUtils.getValueInVM((AbstractRegionEntry)entry,
                region);
            SimpleMemoryAllocatorImpl.unskipRefCountTracking();
          }
          else {
            SimpleMemoryAllocatorImpl.skipRefCountTracking();
            value = rl.getValueWithoutFaultIn(c);
            SimpleMemoryAllocatorImpl.unskipRefCountTracking();
          }
        }
        // global index region / gateway queues.
        else {
          assert (entry instanceof AbstractRegionEntry);
          re = (AbstractRegionEntry)entry;
          k = re.getRawKey();
          assert k != null;
          SimpleMemoryAllocatorImpl.skipRefCountTracking();
          value = re.getValue(c.getRegion());
          SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        }

        if (k != null) {
          keyInMemoryCount++;
          // keySize += SIZE_OF_UTIL.sizeof(k);
          if (isColumnTable) {
            batchKey = (ColumnBatchKey)k;
            keySize += batchKey.getSizeInBytes();
          } else {
            keySize += CachedDeserializableFactory.calcMemSize(
                k, GEM_SIZER, true);
          }
        }

        // No need to add SZ_REF since it is already accounted for by entrySize
        if (!agentAttached) {
          if (value != null) {
            if (!isValueTypeEvaluated) {
              if (isColumnTable) {
                numColumnsInColumnTable = batchKey.getNumColumnsInTable(
                    c.getQualifiedTableName());
              } else if (value instanceof DataValueDescriptor[]) {
                dvdArraySize = SIZE_OF_UTIL.sizeof(value);
              } else if (value instanceof GatewaySenderEventImpl) {
                gatewayEntries = true;
              } else if (value instanceof Chunk) {
                offHeapEntries = true;
              }
              isValueTypeEvaluated = true;
            }
            if (isColumnTable) {
              int valueSize = ((Sizeable)value).getSizeInBytes();
              columnRowCount += batchKey.getColumnBatchRowCount(prEntryIter.getHostedBucketRegion(),
                  re, numColumnsInColumnTable);
              valInMemoryCount++;
              valInMemorySize += valueSize;
              if (valueSize > maxSize) {
                maxSize = valueSize;
              }
            } else if (dvdArraySize > 0) {
              DataValueDescriptor[] dvds = (DataValueDescriptor[])value;
              valInMemoryCount++;
              valInMemorySize += dvdArraySize;
              for (DataValueDescriptor v : dvds) {
                valInMemorySize += CachedDeserializableFactory.calcMemSize(
                    v.getObject(), GEM_SIZER, true);
              }
            } else if (gatewayEntries) {
              GatewaySenderEventImpl event = (GatewaySenderEventImpl)value;
              int offHeapSize = 0;
              SimpleMemoryAllocatorImpl.skipRefCountTracking();
              value = event.getRawValue();
              SimpleMemoryAllocatorImpl.unskipRefCountTracking();
              if (value instanceof Chunk) {
                // fix for 49921
                final Chunk chunkVal = (Chunk)value;
                // chunkVal is released as "value" in the finally block
                // outside the while loop
                valInOffheapCount++;
                offHeapSize = chunkVal.getSizeInBytes();
                valInOffheapSize += offHeapSize;
                if (offHeapSize > maxOffheapSize) {
                  maxOffheapSize = offHeapSize;
                }
              }
              valInMemoryCount++; // event object is in heap memory
              int eventSize = event.getSizeInBytes() - offHeapSize;
              valInMemorySize += eventSize;
              if (eventSize > maxSize) {
                maxSize = eventSize;
              }
            } else if (offHeapEntries) {
              final Chunk chunkVal = (Chunk)value;
              int offHeapSize = chunkVal.getSizeInBytes();
              valInOffheapCount++;
              valInOffheapSize += offHeapSize;
              if (offHeapSize > maxOffheapSize) {
                maxOffheapSize = offHeapSize;
              }
            } else {
              int valueSize = CachedDeserializableFactory.calcMemSize(
                  value, GEM_SIZER, true);
              valInMemoryCount++;
              valInMemorySize += valueSize;
              if (valueSize > maxSize) {
                maxSize = valueSize;
              }
            }
          }
          continue;
        }
        OUTER: while (value != null) {

          final Class<?> valClass = value.getClass();
          if (valClass == byte[].class) {
            valInMemoryCount++;
            long len = SIZE_OF_UTIL.sizeof(value);
            valInMemorySize += len;
            if (len > maxSize) {
              maxSize = len;
            }
            break OUTER;
          }
          else if (valClass == byte[][].class) {

            if (isColumnTable(c.getQualifiedTableName())) {
              columnRowCount += getRowCountFromColumnTable(c, (byte[][])value);
            }

            valInMemoryCount++;
            byte[][] values = ((byte[][])value);  // add in size of Object[]
            long len = SIZE_OF_UTIL.sizeof(value);
            for (byte[] val : values) {
              if (val != null) {
                len += SIZE_OF_UTIL.sizeof(val); // add in size of each nested byte[]
              }
            }

            valInMemorySize += len;
            if (len > maxSize) {
              maxSize = len;
            }
            break OUTER;
          }
          else if (valClass == DataValueDescriptor[].class) {
            valInMemoryCount++;
            valInMemorySize += SIZE_OF_UTIL.sizeof(value); // add in size of Object[]
            for (DataValueDescriptor v : (DataValueDescriptor[])value) {
              valInMemorySize += SIZE_OF_UTIL.sizeof(v.getObject());
            }
            break OUTER;
          }
          else if (valClass == OffHeapRowWithLobs.class) {
            valInOffheapCount++;
            final OffHeapRowWithLobs bs = (OffHeapRowWithLobs)value;
            long len = bs.getSize();
            for (int index = bs.readNumLobsColumns(true); index >= 1; index--) {
              len += bs.getLobDataSizeLength(index);
            }
            valInOffheapSize += len;
            if (len > maxOffheapSize) {
              maxOffheapSize = len;
            }
            break OUTER;
          }
          else if (valClass == OffHeapRow.class) {
            valInOffheapCount++;
            final OffHeapRow bs = (OffHeapRow)value;
            long len = bs.getSize();
            valInOffheapSize += len;
            if (len > maxOffheapSize) {
              maxOffheapSize = len;
            }
            break OUTER;
          }
          else if (Chunk.class.isAssignableFrom(valClass)) {
            valInOffheapCount++;
            final Chunk chunkVal = (Chunk)value;
            long len = chunkVal.getSizeInBytes();
            valInOffheapSize += len;
            if (len > maxOffheapSize) {
              maxOffheapSize = len;
            }
            break OUTER;
          }
          else if (GatewaySenderEventImpl.class.isAssignableFrom(valClass)) {
            valInMemorySize += SIZE_OF_UTIL.sizeof(value);
            SimpleMemoryAllocatorImpl.skipRefCountTracking();
            value = ((GatewaySenderEventImpl)value).getRawValue();
            SimpleMemoryAllocatorImpl.unskipRefCountTracking();
            if (value instanceof Chunk) {
              // fix for 49921
              final Chunk chunkVal = (Chunk)value;
              // chunkVal is released as "value" in the finally block outside the while loop
              valInOffheapCount++;
              valInMemorySize += SIZE_OF_UTIL.sizeof(chunkVal);
              int len = chunkVal.getSize();
              if (value instanceof OffHeapRowWithLobs) {
                OffHeapRowWithLobs ohbytes = (OffHeapRowWithLobs)chunkVal;
                for (int index = ohbytes.readNumLobsColumns(true); index >= 1; index--) {
                  len += ohbytes.getLobDataSizeLength(index);
                }
              }

              valInOffheapSize += len;
              if (len > maxOffheapSize) {
                maxOffheapSize = len;
              }
              break OUTER;
            } else {
              continue;
            }
          }
          else {
            
            if (SanityManager.ASSERT) {
              SanityManager.THROWASSERT("UnHandled Value Type " + valClass);
            }
            break OUTER;
          }
        }
      } finally {
        OffHeapHelper.releaseWithNoTracking(value);
      }
    }

    // if singleEntrySize
    if (isSingleEntrySizeComputed) {
      entrySize = (rowCount * singleEntrySize);
    }

    final StringBuilder bucketDistInfo = new StringBuilder();

    if (generatDistributionInfo && primaryBucketDist.size() > 0) {
      createDistributionInfo(bucketDistInfo, "Primary Bucket Distribution: ",
          primaryBucketDist, maxPrimaryBucketKeyLength,
          maxPrimaryBucketValueLength);
      primaryBucketDist.clear();
    }

    if (generatDistributionInfo && secondaryBucketDist.size() > 0) {
      createDistributionInfo(bucketDistInfo, "Secondary Bucket Distribution: ",
          secondaryBucketDist, maxSecondaryBucketKeyLength,
          maxSecondaryBucketValueLength);
      secondaryBucketDist.clear();
    }

    Object[] val = retEstimates.get(key);

    if (val == null) {
      retEstimates.put(key, new Object[] {
          new long[] { constantOverhead, entrySize, keySize, valInMemorySize,
              valInOffheapSize, rowCount, keyInMemoryCount, valInMemoryCount,
              valInOffheapCount, columnRowCount }, bucketDistInfo });
    }
    else {
      long[] numericVals = (long[])val[0];
      int i = 0;
      numericVals[i++] += constantOverhead;
      numericVals[i++] += entrySize;
      numericVals[i++] += keySize;
      numericVals[i++] += valInMemorySize;
      numericVals[i++] += valInOffheapSize;
      numericVals[i++] += rowCount;
      numericVals[i++] += keyInMemoryCount;
      numericVals[i++] += valInMemoryCount;
      numericVals[i++] += valInOffheapCount;
      numericVals[i++] += columnRowCount;

      if (bucketDistInfo.length() > 0) {
        StringBuilder info = (StringBuilder)val[1];
        info.append(bucketDistInfo);
      }
    }

  }

  private static final Pattern columnTableRegex =
      Pattern.compile(".*" + SystemProperties.SHADOW_SCHEMA_NAME + "(.*)" +
          SystemProperties.SHADOW_TABLE_SUFFIX);
  private Boolean isColumnTable(String fullyQualifiedTable) {
    return columnTableRegex.matcher(fullyQualifiedTable).matches();
  }

  private int getRowCountFromColumnTable(GemFireContainer c, byte[][] value) throws StandardException {
    int rowCount = 0;
    RowFormatter rf = c.getRowFormatter(value);
    ColumnDescriptor cd = c.getTableDescriptor().getColumnDescriptor("NUMROWS");
    if (cd != null)
      rowCount = rf.getColumn(cd.getPosition(), value).getInt();

    return rowCount;
  }

  private static void createDistributionInfo(StringBuilder miscInfo,
      String msg, TreeMap<Integer, Long> bucketDist, int maxKeyLength,
      int maxValueLength) {

    TreeSet<Map.Entry<Integer, Long>> bucketsByValue = Misc
        .sortByValue(bucketDist);

    final Long min = bucketsByValue.last().getValue();
    final Long max = bucketsByValue.first().getValue();
    final int numIntervals = (int)Math.ceil(Math.sqrt(bucketDist.size()));

    miscInfo.append(msg).append(bucketDist.size()).append(" buckets with min ")
        .append(min).append(" entries and max ").append(max)
        .append(" entries.").append(SanityManager.lineSeparator);

    // ---------------- bucket distribution ------------------
    if (bucketDist.size() < PartitionAttributesFactory.GLOBAL_MAX_BUCKETS_DEFAULT) {
      boolean isFirst = true;
      int columns = 0;
      for (Map.Entry<Integer, Long> e : bucketsByValue) {
        columns++;
        if (isFirst) {
          miscInfo.append("{");
          isFirst = false;
        }
        else {
          miscInfo.append(",");
        }
        miscInfo.append("B");
        for (int l = Misc.getLength(e.getKey()); l < maxKeyLength; l++)
          miscInfo.append('0');
        miscInfo.append(e.getKey()).append("=");
        for (int l = Misc.getLength(e.getValue()); l < maxValueLength; l++)
          miscInfo.append(' ');
        miscInfo.append(e.getValue());

        if (columns >= numIntervals) {
          miscInfo.append(SanityManager.lineSeparator);
          columns = 0;
        }
      }
      miscInfo.append("}").append(SanityManager.lineSeparator);
    }

    // ---------------- histogram ------------------
    miscInfo.append(SanityManager.lineSeparator);
    Misc.histogramToString(Misc.createHistogram(null, bucketsByValue), miscInfo);
    miscInfo.append(SanityManager.lineSeparator);

  }

  public void estimateIndexEntryValueSizes(
      final String baseTableContainerName, List<GemFireContainer> indexes,
      final LinkedHashMap<String, Object[]> retEstimates,
      final PreQualifier preQualifier) throws StandardException,
      IllegalArgumentException, IllegalAccessException, InterruptedException {

    for (final GemFireContainer index : indexes) {

      /*if (index.getRegion() != null) {
        estimateRegionEntryValueSizes(index, retEstimates);
        return;
      }
      else*/if (index.isLocalIndex()) {
        final String indexName = index.getTableName();
        final String indexType = "LOCAL";
        final long constantOverhead = index
            .estimateMemoryOverhead(SIZE_OF_UTIL);

        if (preQualifier != null
            && !preQualifier.qualifyPartialRow(baseTableContainerName,
                indexName, indexType, null, null, constantOverhead)) {
          continue;
        }

        if (!this.isForInternalUse && this.traceOutput) {
          SanityManager.DEBUG_PRINT("TRACE", logPrefix
              + "Estimating Index Entry Size/Value Size for " + index);
        }

        ConcurrentSkipListMap<Object, Object> localIndex = index
            .getSkipListMap();
        final Set<Map.Entry<Object, Object>> entrySet = localIndex.entrySet();
        final Iterator<Map.Entry<Object, Object>> entryIter = entrySet
            .iterator();
        final boolean agentAttached = SIZE_OF_UTIL.isAgentAttached();

        final long[] entryOverhead = localIndex.estimateMemoryOverhead(
            entrySet, SIZE_OF_UTIL, this.traceVerbose);
        if (this.traceOutput) {
          if (entryOverhead != null) {
            SanityManager.DEBUG_PRINT(
                "TRACE",
                logPrefix + "Index Entry Overhead of " + index + " "
                    + Arrays.toString(entryOverhead));
          }
        }
        assert entryOverhead == null || entryOverhead.length == 3;
        long keySize = 0;
        long valSize = 0;
        long valOffheapSize = 0;
        long rowCount = 0;
        long keyInMemoryCount = 0;
        final long valInMemoryCount = 0;
        final long valOffheapCount = 0;
        long ccikSize = -1;
        long byteArraySize = -1;
        long rlArraySize = -1;

        while (entryIter.hasNext()) {
          rowCount++;
          Map.Entry<Object, Object> entry = entryIter.next();
          Object k = entry.getKey();
          if (k instanceof CompactCompositeIndexKey) {
            if (ccikSize < 0) {
              ccikSize = SIZE_OF_UTIL.sizeof(k);
            }
            final byte[] keyBytes = ((CompactCompositeIndexKey)k).getKeyBytes();
            if (keyBytes != null) {
              if (byteArraySize < 0) {
                byteArraySize = SIZE_OF_UTIL.sizeof(keyBytes);
              }
              keyInMemoryCount++;
              keySize += keyBytes.length;
              keySize += byteArraySize;
            }
          }
          else if (k instanceof DataValueDescriptor) {
            keySize += SIZE_OF_UTIL.sizeof(k);
          }
          if (this.traceOutput) {
            SanityManager.DEBUG_PRINT("TRACE", logPrefix + "Index Key Size of "
                + index + " " + keySize);
          }
          Object v = entry.getValue();
          valSize += SZ_REF;
          if (agentAttached) {
            valSize += sizeOfObject(v, this.new Exclusions() {
              @Override
              boolean shallExclude(ClassTraverser c) {
                if (super.shallExclude(c)) {
                  return true;
                }
                if (c.member instanceof RowLocation) {
                  return true;
                }
                return false;
              }
            });
          }
          else if (v != null) {
            Class<?> valClass = v.getClass();
            if (valClass == RowLocation[].class) {
              if (rlArraySize < 0) {
                rlArraySize = SIZE_OF_UTIL.sizeof(v);
              }
              valSize += rlArraySize;
              valSize += ((RowLocation[])v).length * SZ_REF;
            }
            else if (valClass == ConcurrentTHashSet.class) {
              valSize += ((ConcurrentTHashSet<?>)v)
                  .estimateMemoryOverhead(SIZE_OF_UTIL);
            }
          }

          if (this.traceOutput) {
            SanityManager.DEBUG_PRINT("TRACE", logPrefix
                + "Index Value Size of " + index + " " + valSize);
          }
        }

        retEstimates.put(baseTableContainerName + this.keyDelimeter + indexName
            + this.keyDelimeter + indexType, new Object[] { new long[] {
            constantOverhead, entryOverhead[0] + entryOverhead[1], keySize,
            valSize, valOffheapSize, rowCount, keyInMemoryCount,
            valInMemoryCount, valOffheapCount } });

      } // end of local index estimation

    } // end of all container iteration
  }

  private void determineRegionMemoryFootPrint(final GemFireContainer c,
      final String baseTableContainerName,
      final LinkedHashMap<String, Object[]> retEstimates,
      final PreQualifier preQualifier) throws IllegalArgumentException,
      IllegalAccessException, InterruptedException, StandardException {

    if (preQualifier != null
        && !preQualifier.qualifyPartialRow(c.getQualifiedTableName(), null,
            null, null, null, 0)) {
      return;
    }

    if (!this.isForInternalUse) {
      SanityManager.DEBUG_PRINT("TRACE", logPrefix
          + "Determining memory footprint for " + c);
    }

    final Region<?, ?> root = c.getRegion();

    Exclusions regionExclusions = this.new Exclusions() {
      private final String regionInFocus = root.getFullPath();

      private final GemFireContainer containerInFocus = c;

      @Override
      public boolean shallExclude(ClassTraverser c) {

        if (super.shallExclude(c))
          return true;

        if (c.member instanceof GemFireContainer) {
          return !containerInFocus.equals(c.member);
        }

        if (c.member instanceof Region<?, ?>) {
          return !super.regionNameCheck(regionInFocus,
              ((Region<?, ?>)c.member).getFullPath());
        }

        if (c.member instanceof Bucket) {
          return !super.regionNameCheck(regionInFocus, ((Bucket)c.member)
              .getPartitionedRegion().getFullPath());
        }

        if (c.member instanceof DistributionAdvisor) {
          return !super.regionNameCheck(regionInFocus,
              ((DistributionAdvisor)c.member).getAdvisee().getFullPath());
        }

        if (c.member instanceof ProxyBucketRegion) {
          return !super.regionNameCheck(regionInFocus,
              ((ProxyBucketRegion)c.member).getBucketAdvisor().getAdvisee()
                  .getFullPath());
        }

        if (c.member instanceof GfxdIndexManager) {
          return true;
        }

        return false;
      }
    };

    long totalFootprint = this.startEstimation(c, regionExclusions);
    SanityManager.DEBUG_PRINT("TRACE", logPrefix + " Total Footprint for " + c
        + " " + totalFootprint);

    if (retEstimates != null) {
      retEstimates.put(baseTableContainerName + " (gemfirexd,gemfire,others) ",
          new Object[] { this.getSizes() });
    }

  }

  private void determineIndexMemoryFootPrint(final String containerName,
      final List<GemFireContainer> indexes,
      final LinkedHashMap<String, Object[]> retEstimates,
      final PreQualifier preQualifier) throws IllegalArgumentException,
      IllegalAccessException, InterruptedException, StandardException {

    // estimate for indexes now.
    for (final GemFireContainer index : indexes) {

      if (preQualifier != null
          && !preQualifier.qualifyPartialRow(containerName,
              index.getTableName(), "LOCAL", null, null, 0)) {
        continue;
      }

      if (!this.isForInternalUse) {
        SanityManager.DEBUG_PRINT("TRACE", logPrefix
            + "Determining memory footprint for " + index);
      }
      estimateIndexFootPrint(index);
      long[] sizes = this.getSizes();
      retEstimates.put(
          Misc.getFullTableName(containerName, index.getTableName(), null)
              + " (gemfirexd,gemfire,others) ", new Object[] { sizes });
    }
  }

  private void estimateIndexFootPrint(final GemFireContainer index)
      throws IllegalArgumentException, IllegalAccessException,
      InterruptedException {

    Exclusions indexExclusions = this.new Exclusions() {
      private final GemFireContainer indexInFocus = index;

      @Override
      public boolean shallExclude(ClassTraverser c) {

        if (super.shallExclude(c))
          return true;

        // TODO will have to cater to Global Indexes.
        if (c.member instanceof Region<?, ?>) {
          // super.regionNameCheck(regionInFocus, (Region<?,?>)c.member)
          return true;
        }

        if (!ObjectSizer.this.initializeMode) {

          // ignore any classes of such category for indexes.
          if (c.member instanceof DistributionAdvisee) {
            return true;
          }
        }

        // try to exclude any other indexes of the same Table
        if (c.member instanceof GemFireContainer) {
          return !(c.member == indexInFocus);
        }

        return false;
      }
    };

    long totalFootprint = this.startEstimation(index, indexExclusions);
    if (!this.isForInternalUse) {
      SanityManager.DEBUG_PRINT("TRACE", logPrefix + " Total Footprint for "
          + index + " " + totalFootprint);
    }
  }

  private long[] getSizes() throws InterruptedException {
    long[] sz = new long[3];
    sz[0] = gemfirexdSize;
    sz[1] = gfeSize;
    sz[2] = othersSize;
    clearSizing();
    return sz;
  }

  private void clearSizing() {
    gemfirexdSize = 0;
    gfeSize = 0;
    othersSize = 0;
  }

  private void reset(boolean clearSeenList) {
    clearSizing();
    classBreakup.clear();
    if (clearSeenList) {
      seen.clear();
    }
  }
  
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="DM_GC")
  private void cleanupGarbage() {
    // we don't need to call gc for production scenario.
    if (!this.withMemoryFootPrint) {
      return;
    }
    
    runtime.gc();
    runtime.runFinalization();
    /*  
        runtime.gc();
        runtime.gc();
        Thread.sleep(100);
        runtime.runFinalization();
        Thread.sleep(200);

        runtime.gc();
        runtime.gc();
        runtime.gc();
        Thread.sleep(200);
        runtime.runFinalization();
        Thread.sleep(500);
    */
  }

  LinkedBlockingDeque<ClassTraverser> sink = new LinkedBlockingDeque<ClassTraverser>();

  class Exclusions {
    boolean shallExclude(ClassTraverser c) {

      if (c.parentContext == null) {
        return false;
      }

      if (c.member == null) {
        return true;
      }

      if ((c.member instanceof WeakReference<?>)
          && (c.member instanceof SoftReference<?>)) {
        return true;
      }

      /*
       *  Thread:ClassLoader(AppClassLoader) -> contextClassLoader
       */
      if (c.member instanceof ClassLoader && c.parentContext != null
          && c.parentContext.member instanceof Thread) {
        return true;
      }

      /*
       * estimate separately 
       */
      if (c.member instanceof InternalResourceManager) {
        return true;
      }

      /*
       * singleton
       */
      if (c.member instanceof InternalDistributedSystem) {
        return true;
      }

      /*
       * limited instances.
       */
      if (c.member instanceof DistributedLockService) {
        return true;
      }

      // metadata fixed cost.
      if (c.member instanceof GfxdDataDictionary) {
        return true;
      }

      // cached objects to be estimated separately. (ThreadLocal:...)
      if (c.member instanceof ContextManager) {
        return true;
      }

      // GemFireCache is singleton in nature, estimate separately.
      if (c.member instanceof GemFireCacheImpl) {
        return true;
      }

      // GemFireStore is singleton in nature, estimate separately.
      if (c.member instanceof GemFireStore) {
        return true;
      }

      // FabricDatabase is singleton in nature, estimate separately.
      if (c.member instanceof FabricDatabase) {
        return true;
      }

      if (!ObjectSizer.this.initializeMode) {

        // we want to accomodate the static cost of
        // connection/lcc/statementCache etc when in
        // initializationMode.
        if (c.member instanceof Connection) {
          return true;
        }

      }

      return false;
    };

    private boolean regionNameCheck(String targetRegion, String currRegion) {
      return currRegion.equals(targetRegion)
          || currRegion.contains(targetRegion.replaceAll("/", "_"));
    }

  } // end of Exclusions

  /**
   * @param root
   * @param exclusions
   * @return total size
   * @throws IllegalArgumentException
   * @throws IllegalAccessException
   * @throws InterruptedException
   */
  private long startEstimation(Object root, Exclusions exclusions)
      throws IllegalArgumentException, IllegalAccessException,
      InterruptedException {

    long size = 0;

    searchAndEstimate(null, root, exclusions);

    if (traceOutput) {
      SanityManager.DEBUG_PRINT("TRACE", logPrefix + "Object sizes after root "
          + gemfirexdSize + "/" + gfeSize + "/" + othersSize + " = "
          + (gemfirexdSize + gfeSize + othersSize));
    }

    ClassTraverser rootC = sink.peek();

    while (!sink.isEmpty()) {
      ClassTraverser c = sink.poll();
      if (c == null) {
        continue;
      }
      report(c);
      while (c.moveNext()) {
        searchAndEstimate(c, c.currentMemberFieldValue(), exclusions);
      }
    }

    if (sink.size() != 0) {
      throw new AssertionError("not all objects got consumed");
    }

    Region<?, ?> r = null;
    if (root instanceof GemFireContainer
        && ((r = (((GemFireContainer)root).getRegion())) != null
            && !r.getFullPath().contains("/SYS") || (r == null && ((GemFireContainer)root)
            .getBaseId() != null)) || !(root instanceof GemFireContainer)) {
      if (rootC != null) {
        LogWriter logger = Misc.getCacheLogWriter();
        StringBuilder sb = rootC.getTopConsumers();
        if (sb != null) {
          logger.info(sb.toString());
        }
      }
    }

    size = this.gemfirexdSize + this.gfeSize + this.othersSize;

    return size;
  }

  private void report(ClassTraverser c) throws IllegalArgumentException,
      IllegalAccessException {

    if (!logObjectGraph) {
      return;
    }

    LogWriter logger = Misc.getCacheLogWriter();

    StringBuilder sb = c.getStack("");
    if (sb == null) {
      return;
    }
    String msg = sb.append("===== EOF ======").toString();
    logger.info(msg);
    System.out.println(msg);
    msg = null;
  }

  private void searchAndEstimate(ClassTraverser currentContext, Object member,
      Exclusions exclusions) throws IllegalArgumentException,
      IllegalAccessException {

    if (member == null) {
      return;
    }

    ClassTraverser c = createInstance(currentContext, member);

    if (exclusions.shallExclude(c)) {
      if (this.traceOutput) {
        SanityManager
            .DEBUG_PRINT("TRACE", logPrefix + " Excluding " + c.member);
      }
      return;
    }

    // lets not add the classes for which we have already visited.
    if (!c.seeAndEstimate(null)) {
      if (this.traceOutput) {
        SanityManager.DEBUG_PRINT("TRACE", logPrefix + " Excluding Members of "
            + c.member);
      }
      return;
    }

    sink.offer(c);

    Class<?> clazz = member.getClass();

    while (clazz != null) {

      Field[] fields = clazz.getDeclaredFields();
      for (Field field : fields) {
        Class<?> fieldType = field.getType();
        field.setAccessible(true);

        if (Modifier.isStatic(field.getModifiers())) {
          c.seeAndEstimate(fieldType);
          continue;
        }

        if (!fieldType.isPrimitive()) {
          if (!Modifier.isStatic(field.getModifiers())) {
            c.addField(field);
          }
        }
      }

      clazz = clazz.getSuperclass();
    } // end of while

  }

  private static class Pair {
    long memSize = 0;

    ArrayList<ClassTraverser> referencePaths;

    Class<?> clazz;
  }

  enum product {
    GEMFIREXD, GFE, OTHER
  }

  public ClassTraverser createInstance(ClassTraverser parent, Object member) {
    return new ClassTraverser(parent, (parent != null ? parent.fieldIndex
        : -1), member);
  }
  
  private class ClassTraverser {

    /**
     * list of fields of a class that will be estimated.
     */
    private final ArrayList<Field> fields = new ArrayList<Field>();

    private int fieldIndex = -1;

    /**
     * Class Member that is estimated.
     */
    private final Object member;

    /**
     * Pointer to parent class
     */
    private ClassTraverser parentContext = null;

    /**
     * Field index of the parent {@link ClassTraverser#fields} list from which
     * current class is getting included.
     */
    private int parentFieldIndex = -1;

    ClassTraverser(ClassTraverser parent, int parentFieldIndex, Object member) {
      this.parentContext = parent;
      this.member = member;
      this.parentFieldIndex = parentFieldIndex;

      /*if (parent == null && this.member instanceof GemFireContainer
          && ((GemFireContainer)this.member).getBaseId() != null) {
        returnTopConsumers = true;
      }
      else if (parent != null) {
        returnTopConsumers = parent.returnTopConsumers;
      }*/
    }

    public void addField(Field f) {
      fields.add(f);
    }

    public StringBuilder getStack(String defaultIndentation)
        throws IllegalArgumentException, IllegalAccessException {
      if (arrayLength != -1 && arrayIndex > 0) {
        return null;
      }
      StringBuilder sb = new StringBuilder(defaultIndentation + "Stack of ");
      getStack(sb, 0, defaultIndentation);
      return sb;
    }

    private static final int TOP_CONSUMER_COUNT = 20;

    private static final int NUM_STACK_COUNT = 10;

    public StringBuilder getTopConsumers() throws IllegalArgumentException,
        IllegalAccessException {

      if (!ObjectSizer.this.logTopConsumers) {
        ObjectSizer.this.classBreakup.clear();
        return null;
      }

      StringBuilder sb = new StringBuilder("Top Consumers for ")
          .append(this.member).append(" with gemfirexd/gemfire/other sizes: ")
          .append(ObjectSizer.this.gemfirexdSize).append("/")
          .append(ObjectSizer.this.gfeSize).append("/")
          .append(ObjectSizer.this.othersSize);

      Pair[] entries = ObjectSizer.this.classBreakup.values().toArray(
          new Pair[1]);

      Arrays.<Pair> sort(entries, new Comparator<Pair>() {

        @Override
        public int compare(Pair o1, Pair o2) {
          return (o1.memSize < o2.memSize ? 1 : o1.memSize > o2.memSize ? -1
              : 0);
        }

      });

      int i = 0;

      for (Pair entry : entries) {

        if (entry == null)
          continue;

        sb.append("\n").append(entry.clazz).append("  ").append(entry.memSize)
            .append(" (").append(entry.referencePaths.size()).append(")");
        HashMap<String, Integer> reportStack = new HashMap<String, Integer>();

        // if(entry.clazz.isInstance(String.class) ) {
        // continue;
        // }

        if (i++ <= TOP_CONSUMER_COUNT) {

          ClassTraverser[] allocations = entry.referencePaths
              .toArray(new ClassTraverser[1]);

          Arrays.<ClassTraverser> sort(allocations,
              new Comparator<ClassTraverser>() {

                @Override
                public int compare(ClassTraverser o1, ClassTraverser o2) {
                  long o1size = SIZE_OF_UTIL.sizeof(o1.member);
                  long o2size = SIZE_OF_UTIL.sizeof(o2.member);
                  return (o1size < o2size ? 1 : o1size > o2size ? -1 : 0);
                }

              });

          for (ClassTraverser ct : allocations) {
            StringBuilder currentStack = ct.getStack("\t");
            String searchStack = currentStack.toString().replaceAll(
                "(?m)(.*)@.*\\) ", "$1) ");
            Integer repeatCount = reportStack.get(searchStack);
            if (repeatCount != null) {
              reportStack.put(searchStack, repeatCount + 1);
              continue;
            }

            if (reportStack.size() > NUM_STACK_COUNT)
              break;
            reportStack.put(searchStack, Integer.valueOf(1));
          }

          for (Map.Entry<String, Integer> e : reportStack.entrySet()) {
            sb.append("\n\t(").append(e.getValue().longValue()).append(") ")
                .append(e.getKey().toString());
          }
        }
      }
      ObjectSizer.this.classBreakup.clear();
      return sb;
    }

    private boolean seeAndEstimate(Class<?> memberClass) {
      if (memberClass == null) {
        return estimate(member, member.getClass());
      }
      else {
        return estimate(memberClass, memberClass);
      }
    }

    private String getCanonicalName(Class<?> c) {
      try {
        return c.getCanonicalName();
      } catch (IncompatibleClassChangeError ignore) {
        return null;
      }
    }

    private product addTo(Class<?> objectClass) {
      String clName = getCanonicalName(objectClass);
      if (clName == null) {
        clName = objectClass.getName();
      }
      if (clName.indexOf("com.pivotal.gemfirexd") >= 0) {
        return product.GEMFIREXD;
      }
      else if (clName.indexOf("com.gemstone.gemfire") >= 0) {
        return product.GFE;
      }
      else {
        return product.OTHER;
      }
    }

    private long sizeOf(Object obj2estimate) {

      boolean isVisited = ObjectSizer.this.initialset.containsKey(obj2estimate);
      boolean isSeen = ObjectSizer.this.seen.containsKey(obj2estimate);

      if (isVisited && isSeen) {
        // consider the reference size of PR but return positive for its child
        // to be included.
        // else, BucketRegion(s) won't get considered.
        if (Region.class.isInstance(obj2estimate)
            && "/__PR".equals(((Region<?, ?>)obj2estimate).getFullPath())) {
          return SZ_REF;
        }

        if (PartitionedRegionDataStore.class.isInstance(obj2estimate)) {
          return SZ_REF;
        }

        if (ConcurrentHashMap.class.isInstance(obj2estimate)) {
          Field f = (parentContext != null ? parentContext
              .mappingField(parentFieldIndex) : null);
          if (f != null && f.getName().contains("localBucket2RegionMap")) {
            return SZ_REF;
          }
        }
      }
      isSeen = isVisited || isSeen;

      if (isSeen) {
        // negate to indicate Seen
        return -SZ_REF;
      }

      if (obj2estimate instanceof com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey) {
        byte[] key = ((com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey)obj2estimate)
            .getKeyBytes();
        long sz = 0;
        if (key != null) {
          sz = key.length;
        }
        sz += SIZE_OF_UTIL.sizeof(obj2estimate);
        ObjectSizer.this.seen.put(obj2estimate, null);
        return -sz;
      }
      else if (obj2estimate instanceof byte[][]) {
        long sz = SIZE_OF_UTIL.sizeof(obj2estimate);
        for (byte[] b : (byte[][])obj2estimate) {
          sz += SIZE_OF_UTIL.sizeof(b);
        }
        return sz;
      }

      return SIZE_OF_UTIL.sizeof(obj2estimate);
    }

    private boolean estimate(Object obj2estimate, Class<?> objectClass) {

      long objectsize = sizeOf(obj2estimate);

      boolean isSeen = false;

      // -ve is seen indicator .
      if (objectsize < 0) {
        objectsize = -objectsize;
        isSeen = true;
      }
      else if (obj2estimate.getClass().isPrimitive()) {
        isSeen = true;
      }

      if (!isSeen) {
        product type = addTo(objectClass);
        if (type == product.GEMFIREXD) {
          ObjectSizer.this.gemfirexdSize += objectsize;
        }
        else if (type == product.GFE) {
          ObjectSizer.this.gfeSize += objectsize;
        }
        else {
          ObjectSizer.this.othersSize += objectsize;
        }

        if (!isSeen) {
          if (ObjectSizer.this.initializeMode) {
            ObjectSizer.this.initialset.put(obj2estimate, null);
          }
          else {
            ObjectSizer.this.seen.put(obj2estimate, null);
          }
        }
      }

      if (ObjectSizer.this.logTopConsumers) {
        Pair p = ObjectSizer.this.classBreakup.get(objectClass);

        if (p == null) {
          p = new Pair();
          p.referencePaths = new ArrayList<ClassTraverser>();
          p.clazz = objectClass;
          ObjectSizer.this.classBreakup.put(objectClass, p);
        }

        p.memSize += SIZE_OF_UTIL.sizeof(obj2estimate);
        p.referencePaths.add(this);
      }

      // return whether added or not
      return !isSeen;
    }

    private void getStack(StringBuilder sb, int indent,
        String defaultIndentation) throws IllegalArgumentException,
        IllegalAccessException {

      StringBuilder tabs = new StringBuilder(defaultIndentation + "\t");
      for (int i = indent; i > 0; i--) {
        tabs.append("\t");
      }

      String className = getCanonicalName(member.getClass());
      // if( className != null && className.contains("java.") ) {
      // sb.append(tabs).append(member.getClass().getCanonicalName()).append(
      // "\n");
      // return;
      // }

      sb.append(tabs);
      if (this.member instanceof Region<?, ?>) {
        Region<?, ?> region = ((Region<?, ?>)this.member);
        sb.append(region.getClass().getSimpleName()).append(" (")
            .append(region.getFullPath()).append(" )");
      }
      else if (this.member instanceof GemFireContainer) {
        GemFireContainer container = ((GemFireContainer)this.member);
        sb.append(container.getClass().getSimpleName()).append(" (")
            .append(container.getQualifiedTableName()).append(" )");
      }
      else if (this.member instanceof Field) {
        Field f = (Field)this.member;
        String valueType = this.member.getClass().getSimpleName();
        sb.append(f.getDeclaringClass().getSimpleName()).append(":")
            .append(f.getType().getSimpleName()).append("(").append(valueType)
            .append("@")
            .append(Integer.toHexString(System.identityHashCode(this.member)))
            .append(") -> ").append(f.getName());
      }
      else {
        sb.append(className);
      }

      reportSizes(sb, this.member).append("\n");

      if (defaultIndentation.length() == 0) {
        tabs.append("\t");
        long fieldSizes = 0;
        for (Field f : fields) {
          sb.append(tabs);
          Object value = f.get(this.member);
          String valueType = (value == null ? "NULL" : value.getClass()
              .getSimpleName());
          sb.append(f.getDeclaringClass().getSimpleName()).append(":")
              .append(f.getType().getSimpleName()).append("(")
              .append(valueType).append("@")
              .append(Integer.toHexString(System.identityHashCode(value)))
              .append(") -> ").append(f.getName());

          if (Modifier.isStatic(f.getModifiers())
              && (!ObjectSizer.this.initialset.containsKey(value) || !ObjectSizer.this.seen
                  .containsKey(value))) {
            fieldSizes += SIZE_OF_UTIL.sizeof(f.getType());
            sb.append(" :: ").append(SIZE_OF_UTIL.sizeof(f.getType()));
          }
          sb.append("\n");
        }
        sb.append(tabs).append(" ~ all fields size to ").append(fieldSizes)
            .append("\n");
      }

      ClassTraverser pc = parentContext;
      int pfi = parentFieldIndex;

      Field[] lastFewFrames = new Field[5];
      int skipFramesCounter = 0;
      while (pc != null) {

        Field f = pc.fields.get(pfi);
        if (!lastFewFramesSame(lastFewFrames, f)) {
          tabs.append("\t");
          sb.append(tabs);
          f.setAccessible(true);
          Object value = f.get(pc.member);
          value = (value == null ? "null" : value);

          if (skipFramesCounter != 0) {
            sb.append("... skipped ").append(skipFramesCounter)
                .append(" similar frames ... ").append("\n").append(tabs);
            skipFramesCounter = 0;
          }

          sb.append(f.getDeclaringClass().getSimpleName());
          if (pc.member instanceof Region<?, ?>) {
            sb.append("(").append(((Region<?, ?>)pc.member).getFullPath())
                .append(")");
          }
          String valueType = (value == null ? "NULL" : value.getClass()
              .getSimpleName());
          sb.append(":").append(f.getType().getSimpleName()).append("(")
              .append(valueType).append("@")
              .append(Integer.toHexString(System.identityHashCode(value)))
              .append(") -> ").append(f.getName());

          sb.append(" :: ").append(SIZE_OF_UTIL.sizeof(value)).append("\n");
        }
        else {
          skipFramesCounter++;
        }

        pfi = pc.parentFieldIndex;
        pc = pc.parentContext;
      }

      tabs = null;
    }

    private StringBuilder reportSizes(StringBuilder sb, Object value) {
      sb.append(" :: ").append(SIZE_OF_UTIL.sizeof(value)).append("  ::  ");

      // sb.append(ObjectSizer.this.gemfirexdSize).append(" + ").append(
      // ObjectSizer.this.gfeSize).append(" + ").append(
      // ObjectSizer.this.othersSize).append(" = ").append(
      // ObjectSizer.this.gfeSize + ObjectSizer.this.gemfirexdSize
      // + ObjectSizer.this.othersSize);

      return sb;
    }

    private boolean lastFewFramesSame(Field[] stack, Field current) {

      boolean lastTwoFramesSame = true;

      for (int i = 0; i < stack.length; i++) {

        // initially populate the stack
        if (stack[i] == null) {
          stack[i] = current;
          lastTwoFramesSame = false;
          break;
        }
        // find out stack frames are repeating itself
        else if (!(stack[0].getDeclaringClass().equals(
            current.getDeclaringClass()) && stack[0].getType().equals(
            current.getType()))) {
          lastTwoFramesSame = false;
        }

        // pop out topmost frame
        if (i + 1 >= stack.length) {
          stack[i] = current;
        }
        else if (stack[i + 1] != null) {
          stack[i] = stack[i + 1];
        }
      }

      return lastTwoFramesSame;
    }

    public boolean moveNext() {
      if (arrayLength != -1) {
        if (!(++arrayIndex < arrayLength)) {
          arrayLength = -1;
          arrayIndex = -1;
          // lets move out of array to next field.
        }
        else {
          // array is still having elements to consume
          return true;
        }
      }

      return (++fieldIndex) < fields.size();
    }

    private int arrayLength = -1;

    private int arrayIndex = -2;

    private Field mappingField(int fldindex) {
      if (fldindex < 0 || fldindex >= fields.size()) {
        return null;
      }
      return fields.get(fldindex);
    }

    public Object currentMemberFieldValue() throws IllegalArgumentException,
        IllegalAccessException {

      Object value = null;
      if (fieldIndex >= fields.size()) {
        return null;
      }
      Field f = fields.get(fieldIndex);
      Class<?> clazz = f.getType();
      Class<?> componentType = clazz.getComponentType();

      // primitive one's are taken care during sizeOf(..)
      if (clazz.isArray() && !componentType.isPrimitive()) {

        Object valarray = f.get(this.member);
        if (valarray == null) {
          return null;
        }

        if (arrayLength == -1) {
          arrayLength = Array.getLength(valarray);
          // return the array in itself and then its elements...
          value = valarray;
        }

        if (value == null) {
          if (++arrayIndex < arrayLength) {
            value = Array.get(valarray, arrayIndex);
          }
          else {
            arrayLength = -1;
            arrayIndex = -2;
          }
        }
      }
      else {
        value = f.get(this.member);
      }

      return value;
    }
  }

}
