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

package com.pivotal.gemfirexd.internal.engine.ddl;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gnu.trove.TLongHashSet;
import com.gemstone.gnu.trove.TObjectIntHashMap;
import com.gemstone.gnu.trove.TObjectIntProcedure;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegionQueue.QueueValue;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.messages.GfxdSystemProcedureMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdReadWriteLock;
import com.pivotal.gemfirexd.internal.engine.locks.impl.GfxdReentrantReadWriteLock;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * A {@link DistributedRegion} specialization for GemFireXD DDL region queue
 * that sets up the region for DDL statements and overrides methods
 * from base class appropriately.
 * 
 * @author swale
 * @since 6.5
 */
public final class GfxdDDLRegion extends DistributedRegion {
  
  private final GfxdDDLRegionQueue queue;

  private final GfxdReadWriteLock conflationLock;

  private final Object preInitSync;

  private boolean preInitDone;

  public static final int DDL_REGION_UUID_RECORD_INTERVAL = 4;
  
  // A list of indexes which were in this vm but not which
  // came from ddl region gii
  private final transient TLongHashSet olderIndexes;

  private TLongHashSet conflatedDDLIds = new TLongHashSet();;
  
  private GfxdDDLRegion(GfxdDDLRegionQueue queue, String regionName,
      RegionAttributes<Long, RegionValue> attrs, LocalRegion parentRegion,
      GemFireCacheImpl cache, InternalRegionArguments internalRegionArgs) {
    super(regionName, attrs, parentRegion, cache, internalRegionArgs);
    this.queue = queue;
    this.conflationLock = new GfxdReentrantReadWriteLock(
        "GfxdDDLRegion.Conflation", false);
    this.preInitSync = new Object();
    this.preInitDone = false;
    this.olderIndexes = new TLongHashSet();
  }

  @SuppressWarnings("deprecation")
  public static GfxdDDLRegion createInstance(GfxdDDLRegionQueue queue,
      GemFireCacheImpl cache, String regionName,
      CacheListener<Long, RegionValue> listener, boolean persistDD,
      String persistentDir) throws TimeoutException, RegionExistsException,
      IOException, ClassNotFoundException {
    assert regionName != null: "expected the regionName to be non-null";

    // using deprecated AttributesFactory since we have to call the internal
    // createVMRegion method
    final com.gemstone.gemfire.cache.AttributesFactory<Long, RegionValue> afact
      = new com.gemstone.gemfire.cache.AttributesFactory<Long, RegionValue>();
    afact.setScope(Scope.DISTRIBUTED_ACK);
    afact.setInitialCapacity(1000);
    // we don't want versioning to interfere in DDL region ever
    afact.setConcurrencyChecksEnabled(false);

    if (persistDD) {
      DiskStoreFactory dsf =  cache.createDiskStoreFactory();
      // Set disk directories
      File[] diskDirs = new File[1];
      diskDirs[0] = GemFireStore.createPersistentDir(persistentDir,
          GfxdConstants.DEFAULT_PERSISTENT_DD_SUBDIR).toFile();
      dsf.setDiskDirs(diskDirs);
      if(DiskStoreFactory.DEFAULT_MAX_OPLOG_SIZE < 10) {
        dsf.setMaxOplogSize(DiskStoreFactory.DEFAULT_MAX_OPLOG_SIZE);
      }
      else {
        dsf.setMaxOplogSize(10);
      }
      // writes use fsync
      dsf.setSyncWrites(true);
      DiskStoreImpl dsImpl = (DiskStoreImpl)GemFireStore.createDiskStore(dsf,
          GfxdConstants.GFXD_DD_DISKSTORE_NAME, cache.getCancelCriterion());
      dsImpl.setUsedForInternalUse();
      afact.setDiskSynchronous(true);
      afact.setDiskStoreName(GfxdConstants.GFXD_DD_DISKSTORE_NAME);
      afact.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);
      // overflow this region to disk as much as possible since we don't
      // need it to be in memory
      afact.setEvictionAttributes(EvictionAttributes.createLRUEntryAttributes(
          1, EvictionAction.OVERFLOW_TO_DISK));
    }
    else {
      afact.setDataPolicy(DataPolicy.REPLICATE);
    }
    if (listener != null) {
      afact.addCacheListener(listener);
    }

    // Set the meta-region flag for this region (this is the convention
    // for bypassing authorization checks, for example). Remaining flags
    // are the same as those set by Cache#createRegion().
    InternalRegionArguments internalRegionArgs = new InternalRegionArguments()
        .setDestroyLockFlag(true).setRecreateFlag(false)
        .setSnapshotInputStream(null).setImageTarget(null)
        .setIsUsedForMetaRegion(true).setIsUsedForPartitionedRegionAdmin(false)
        // record generated UUIDs frequently so we don't create many holes on
        // restart while there is no performance problem with DDLs
        .setUUIDRecordInterval(DDL_REGION_UUID_RECORD_INTERVAL);
    final GfxdDDLRegion region = new GfxdDDLRegion(queue, regionName, afact
        .create(), null, cache, internalRegionArgs);
    return (GfxdDDLRegion)cache.createVMRegion(regionName, afact.create(),
        internalRegionArgs.setInternalMetaRegion(region));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void preGetInitialImage() {
    // this is now used to record the schema versions of tables after recovery
    // from old product data files that did not support explicit schema
    // versioning; this allows us to interpret the format of rows correctly
    // after restarting in new product or further ALTER TABLE's that change the
    // schema subsequently
    if (GemFireXDUtils.TracePersistIndex) {
      GfxdIndexManager
          .traceIndex("GfxdDDLRegion::preGetInitialImage called");
    }
    final DiskStoreImpl dsi = getDiskStore();
    // dsi can be null on locator/admin
    if (dsi == null) {
      endPreInit();
      return;
    }
    boolean usingPre11Schema = false;
    Version version;
    for (Oplog oplog : dsi.getPersistentOplogSet(getDiskRegion())
        .getAllOplogs()) {
      // for the mixed pre 1.1 and >= 1.1 case, the
      // PRE11_RECOVERY_SCHEMA_VERSION property would already have been written
      // to the database the first time, so that will be used directly and this
      // processing here can be skipped
      if ((version = oplog.getDataVersionIfOld()) != null
          && Version.SQLF_1099.compareTo(version) > 0) {
        usingPre11Schema = true;
        break;
      }
    }
    // determine the schema versions of all the tables at the time of recovery
    if (usingPre11Schema) {
      // the set of DDL IDs recovered from disk for old pre 1.1 product version
      // that did not support schema versioning
      final TObjectIntHashMap pre11TableSchemaVer = new TObjectIntHashMap();
      // determine if this is an ALTER TABLE requiring change of schema using a
      // regular expression search for ADD/DROP [COLUMN]
      Pattern addDropColSearch = Pattern.compile("\\s+(ADD|DROP)\\s+(\\w+)",
          Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
      for (RegionEntry entry : this.entries.regionEntries()) {
        if (!entry.isDestroyedOrRemoved()) {
          Object value = ((RegionValue)entry.getValue(this)).getValue();
          if (value instanceof DDLConflatable) {
            DDLConflatable ddl = (DDLConflatable)value;
            String fullTableName;
            int schemaVersion;
            if (GemFireXDUtils.TracePersistIndex) {
              GfxdIndexManager
                  .traceIndex("GfxdDDLRegion::preGetInitialImage ddl: "
                      + ddl + " is index: " + ddl.isCreateIndex());
            }
            if (ddl.isCreateTable()) {
              fullTableName = ddl.getRegionToConflate();
              if (!pre11TableSchemaVer.containsKey(fullTableName)) {
                pre11TableSchemaVer.put(fullTableName, 1);
              }
            }
            else if (ddl.isAlterTable()) {
              String alterText = ddl.getValueToConflate();
              Matcher match = addDropColSearch.matcher(alterText);
              String alterToken;
              if (match.find()) {
                alterToken = match.group(2);
                if (alterToken == null || alterToken.equalsIgnoreCase("COLUMN")
                    || (!alterToken.equalsIgnoreCase("CONSTRAINT")
                     && !alterToken.equalsIgnoreCase("PRIMARY")
                     && !alterToken.equalsIgnoreCase("UNIQUE")
                     && !alterToken.equalsIgnoreCase("FOREIGN")
                     && !alterToken.equalsIgnoreCase("CHECK"))) {
                  fullTableName = ddl.getRegionToConflate();
                  if ((schemaVersion = pre11TableSchemaVer
                      .get(fullTableName)) > 0) {
                    pre11TableSchemaVer.put(fullTableName, schemaVersion + 1);
                  }
                  else {
                    // schema version starts at 1 and will be 2 after the first
                    // ALTER TABLE ADD/DROP COLUMN
                    pre11TableSchemaVer.put(fullTableName, 2);
                  }
                }
              }
            }
            else if (ddl.isCreateIndex()) {
              String indexName = ddl.getKeyToConflate();
              if (GemFireXDUtils.TracePersistIndex) {
                GfxdIndexManager
                    .traceIndex("GfxdDDLRegion::preGetInitialImage usingPre11Schema adding index: "
                        + indexName + " with ddlId: " + ddl.getId() + " to older indexes list");
              }
              this.olderIndexes.add(ddl.getId());
            }
          }
          else if (value instanceof GfxdSystemProcedureMessage) {
            // if we have already persisted the database property then nothing
            // needs to be done
            GfxdSystemProcedureMessage msg = (GfxdSystemProcedureMessage)value;
            // check for persisted PRE11_RECOVERY_SCHEMA_VERSIONs
            if (msg.getSysProcMethod() == GfxdSystemProcedureMessage
                .SysProcMethod.setDatabaseProperty) {
              Object[] params = msg.getParameters();
              String key = (String)params[0];
              if (key != null && key.startsWith(
                  GfxdConstants.PRE11_RECOVERY_SCHEMA_VERSION)) {
                pre11TableSchemaVer.clear();
                break;
              }
            }
          }
        }
      }
      // now write the PRE11_RECOVERY_SCHEMA_VERSION database properties for all
      // found regions before GII so that it is sent to GII destinations also
      if (pre11TableSchemaVer.size() > 0) {
        pre11TableSchemaVer.forEachEntry(new TObjectIntProcedure() {
          @Override
          public boolean execute(Object table, int schemaVersion) {
            final LogWriter logger = getLogWriterI18n().convertToLogWriter();
            if (logger.infoEnabled()) {
              logger.info("DDL recovery: persisting schema version for "
                  + "pre 1.1 data to " + schemaVersion + " for table " + table);
            }
            GfxdSystemProcedureMessage msg = new GfxdSystemProcedureMessage(
                GfxdSystemProcedureMessage.SysProcMethod.setDatabaseProperty,
                new Object[] { GfxdConstants.PRE11_RECOVERY_SCHEMA_VERSION +
                    table, Integer.toString(schemaVersion) }, null, 1, 1, null);
            final EntryEventImpl event = newUpdateEntryEvent(
                Long.valueOf(newUUID(true)), new RegionValue(msg, 1), null);
            // explicitly mark event as local
            event.setSkipDistributionOps();
            validatedPut(event, CachePerfStats.getStatTime());
            // TODO OFFHEAP: validatedPut calls freeOffHeapResources
            return true;
          }
        });
      }
    }
    else {
      // Not using pre11schema
      if (GemFireXDUtils.TracePersistIndex) {
        GfxdIndexManager
            .traceIndex("GfxdDDLRegion::preGetInitialImage not using pre11 schema");
      }
      for (RegionEntry entry : this.entries.regionEntries()) {
        // keys can now be String for persisting VMID
        if (!entry.isDestroyedOrRemoved() && entry.getKeyCopy() instanceof Long) {
          Object value = ((RegionValue)entry.getValue(this)).getValue();
          if (value instanceof DDLConflatable) {
            DDLConflatable ddl = (DDLConflatable)value;
            if (ddl.isCreateIndex()) {
              String indexName = ddl.getKeyToConflate();
              if (GemFireXDUtils.TracePersistIndex) {
                GfxdIndexManager
                    .traceIndex("GfxdDDLRegion::preGetInitialImage adding index: "
                        + indexName + " to older indexes list");
              }
              this.olderIndexes.add(ddl.getId());
            }

          }
        }
      }
    }
    endPreInit();
  }

  private void endPreInit() {
    synchronized (this.preInitSync) {
      this.preInitDone = true;
      this.preInitSync.notifyAll();
    }
  }

  public boolean waitForPreInit(long waitMillis) throws InterruptedException {
    synchronized (this.preInitSync) {
      if (!this.preInitDone) {
        if (waitMillis <= 0) {
          waitMillis = Integer.MAX_VALUE;
        }
        this.preInitSync.wait(waitMillis);
        return this.preInitDone;
      }
      else {
        return true;
      }
    }
  }

  public TLongHashSet getOlderIndexes() {
    return this.olderIndexes;
  }
  /**
   * Override for the method to allow localDestroy on this region despite being
   * replicated.
   */
  @Override
  protected void checkIfReplicatedAndLocalDestroy(EntryEventImpl event) {
  }

  /**
   * Takes the read lock for conflation so that conflation of multiple entries
   * will be an atomic operation.
   */
  @Override
  protected boolean chunkEntries(InternalDistributedMember sender,
      int CHUNK_SIZE_IN_BYTES, boolean includeValues,
      @SuppressWarnings("rawtypes") RegionVersionVector versionVector,
      HashSet unfinishedKeys, boolean unfinishedKeysOnly, InitialImageFlowControl flowControl,
      TObjectIntProcedure proc) throws IOException {
    if (GemFireXDUtils.TraceConflation) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
          "GfxdDDLRegion#chunkEntries: starting wait on conflation and "
              + "DD read lock with chunkSize=" + CHUNK_SIZE_IN_BYTES);
    }
    // take the DD read lock to ensure that any ongoing DDL ops will be
    // part of GII and will be replayed even if the new member is not a
    // target for that GfxdDDLMessage
    GfxdDataDictionary dd = Misc.getMemStore().getDatabase()
        .getDataDictionary();
    boolean lockedForConflation = false;
    if (dd != null) {
      dd.lockForReadingRT(null);
    }
    try {
      lockForConflation(false);
      lockedForConflation = true;
      if (GemFireXDUtils.TraceConflation) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
            "GfxdDDLRegion#chunkEntries: done wait on conflation and "
                + "DD read lock with chunkSize=" + CHUNK_SIZE_IN_BYTES);
      }
      return super.chunkEntries(sender, CHUNK_SIZE_IN_BYTES, includeValues,
          versionVector, unfinishedKeys, unfinishedKeysOnly, flowControl, proc);
    } finally {
      if (lockedForConflation) {
        unlockForConflation(false);
      }
      if (dd != null) {
        dd.unlockAfterReading(null);
      }
      if (GemFireXDUtils.TraceConflation) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
            "GfxdDDLRegion#chunkEntries: done with getting entries with "
                + "chunkSize=" + CHUNK_SIZE_IN_BYTES);
      }
    }
  }

  @Override
  protected boolean virtualPut(EntryEventImpl event, boolean ifNew,
      boolean ifOld, Object expectedOldValue, boolean requireOldValue,
      long lastModified, boolean overwriteDestroyed) throws TimeoutException,
      CacheWriterException {
    if (GemFireXDUtils.TraceDDLQueue) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE,
          "GfxdDDLRegion#virtualPut: putting new event " + event);
    }
    boolean result = super.virtualPut(event, ifNew, ifOld, expectedOldValue,
        requireOldValue, lastModified, overwriteDestroyed);
    final Operation op = event.getOperation();
    // do the queue manipulation and conflation outside the entry lock
    // to avoid deadlocks with chunkEntries(...) (see one of the failures
    // in bug #40854)
    final boolean isRemote = (event.isOriginRemote() || op.isNetSearch());
    final Object key;
    if (isRemote && (key = event.getKey()) instanceof Long) {
      final Long ddlQueueId = (Long)key;
      final RegionValue regionVal = (RegionValue)event.getNewValue();
      if (op.isCreate() || !result) {
        if (GemFireXDUtils.TraceDDLQueue) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE,
              "GfxdDDLRegion#virtualPut: adding event to queue " + event);
        }
        final QueueValue qVal = new QueueValue(ddlQueueId, regionVal);
        // wait for DDL replay to flush the last batch, if required
        final GemFireStore memStore = Misc.getMemStore();
        memStore.acquireDDLReplayLock(false);
        this.queue.lockQueue(true);
        try {
          // conflation only if queue GII is complete else it can happen twice
          // and possibly in incorrect order (i.e. drop => create can remove
          //   create when conflation is done in queue populate after GII)
          if (this.queue.isInitialized()) {
            final ArrayList<QueueValue> conflatedItems = new ArrayList<>(4);
            this.queue.addToQueue(qVal, true, conflatedItems);
            doConflate(conflatedItems, qVal);
          }
          else {
            this.queue.addToQueue(qVal, false, null);
          }
        } finally {
          this.queue.unlockQueue(true);
          memStore.releaseDDLReplayLock(false);
        }
      }
    }
    return result;
  }

  /**
   * Prevent distribution of update for events explicitly marked as local since
   * that will be done by {@link GfxdDDLFinishMessage}.
   */
  @Override
  protected void distributeUpdate(final EntryEventImpl event,
      final long lastModified) {
    if (!event.getSkipDistributionOps()) {
      super.distributeUpdate(event, lastModified);
    }
  }

  @Override
  protected void basicDestroy(EntryEventImpl event, boolean cacheWrite,
      Object expectedOldValue) throws EntryNotFoundException,
      CacheWriterException, TimeoutException {
    if (GemFireXDUtils.TraceDDLQueue) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE,
          "GfxdDDLRegion#basicDestroy: destroying for event " + event);
    }
    super.basicDestroy(event, cacheWrite, expectedOldValue);
    final Object key;
    if ((event.isOriginRemote() || event.getOperation().isNetSearch())
        && (key = event.getKey()) instanceof Long) {
      final RegionValue val = (RegionValue)event.getOldValue();
      this.queue.lockQueue(true);
      try {
        this.queue.removeFromQueue(new QueueValue((Long)key, val));
      } finally {
        this.queue.unlockQueue(true);
      }
    }
  }

  /**
   * Conflate given items from this region taking the appropriate lock to avoid
   * GII from happening concurrently on partially destroyed entries.
   */
  void doConflate(List<QueueValue> conflateItems, Object qVal) {
    if (conflateItems != null && conflateItems.size() > 0) {
      final boolean doLog = GemFireXDUtils.TraceConflation
          | DistributionManager.VERBOSE;
      // since region conflation is tried from multiple places (virtualPut as
      //   well as after populateQueue) we can have EntryNotFoundExceptions
      // that are ignored and logged when TraceConflation is turned on
      lockForConflation(true);
      try {
        for (QueueValue val : conflateItems) {
          if (doLog) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
                "GfxdDDLRegion: conflating entry {key=" + val.getKey()
                    + ", value=" + val.getValue() + "} for entry {" + qVal
                    + "} from region");
          }
          this.conflatedDDLIds .add(val.getKey());
          try {
            localDestroy(val.getKey());
          } catch (EntryNotFoundException ex) {
            if (doLog) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
                  "GfxdDDLRegion#doConflate: got EntryNotFoundException for "
                      + "key " + val.getKey() + " in region");
            }
          }
        }
        /* now this entry itself will be part for conflateItems if required
         * (e.g. when merging it should not be removed)
        if (currentKey != null) {
          try {
            if (doLog) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
                  "GfxdDDLRegion: conflating key [" + currentKey
                      + "] from region");
            }
            localDestroy(currentKey);
          } catch (EntryNotFoundException ex) {
            if (doLog) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
                  "GfxdDDLRegion#doConflate: got EntryNotFoundException for "
                      + "current key " + currentKey + " in region");
            }
          }
        }
        */
      } finally {
        unlockForConflation(true);
      }
    }
  }

  public TLongHashSet getConflatedDDLIds() {
    return this.conflatedDDLIds;
  }
  
  final void lockForConflation(boolean exclusive) {
    acquireLock(this.conflationLock, exclusive, this);
  }

  final void unlockForConflation(boolean exclusive) {
    releaseLock(this.conflationLock, exclusive);
  }

  final GfxdDDLRegionQueue getDDLQueue() {
    return this.queue;
  }

  static final void acquireLock(GfxdReadWriteLock rwLock, boolean exclusive,
      GfxdDDLRegion region) {
    final Object owner = Thread.currentThread();
    if (exclusive) {
      rwLock.attemptWriteLock(-1, owner);
    }
    else {
      rwLock.attemptReadLock(-1, owner);
    }
  }

  static final void releaseLock(GfxdReadWriteLock rwLock, boolean exclusive) {
    if (exclusive) {
      rwLock.releaseWriteLock(Thread.currentThread());
    }
    else {
      rwLock.releaseReadLock();
    }
  }

  /**
   * Encapsulates the value and sequenceId.
   * 
   * @author swale
   */
  public static final class RegionValue extends GfxdDataSerializable
      implements Sizeable {

    private Object value;

    long sequenceId;

    /** empty constructor for deserialization */
    public RegionValue() {
    }

    RegionValue(Object value, long sequenceId) {
      this.value = value;
      this.sequenceId = sequenceId;
    }

    public Object getValue() {
      return this.value;
    }

    @Override
    public byte getGfxdID() {
      return GfxdSerializable.DDL_REGION_VALUE;
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeObject(this.value, out);
      out.writeLong(this.sequenceId);
    }

    @Override
    public void fromData(DataInput in) throws IOException,
            ClassNotFoundException {
      super.fromData(in);
      this.value = DataSerializer.readObject(in);
      this.sequenceId = in.readLong();
    }

    @Override
    public int getSizeInBytes() {
      return Misc.getMemStoreBooting().getObjectSizer().sizeof(this.value) + 8;
    }

    @Override
    public String toString() {
      return this.value + " [sequence: " + this.sequenceId + "]";
    }
  }
}
