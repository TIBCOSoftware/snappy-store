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
package com.gemstone.gemfire.internal.cache;

import java.io.PrintStream;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.cache.EvictionAction;
import com.gemstone.gemfire.cache.EvictionAlgorithm;
import com.gemstone.gemfire.compression.Compressor;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.ClassPathLoader;
import com.gemstone.gemfire.internal.CopyOnWriteHashSet;
import com.gemstone.gemfire.internal.cache.DiskInitFile.DiskRegionFlag;
import com.gemstone.gemfire.internal.cache.persistence.DiskRegionView;
import com.gemstone.gemfire.internal.cache.persistence.PersistenceAdvisorImpl;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberPattern;
import com.gemstone.gemfire.internal.cache.versions.DiskRegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionHolder;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import joptsimple.internal.Strings;

/**
 * Code shared by both DiskRegion and RecoveredDiskRegion.
 *
 * @author Darrel Schneider
 *
 * @since prPersistSprint2
 */
public abstract class AbstractDiskRegion implements DiskRegionView {
  
  public static final boolean TRACE_VIEW = Boolean.getBoolean("gemfire.TRACE_PERSISTENT_VIEW") || PersistenceAdvisorImpl.TRACE;

  //////////////////////  Instance Fields  ///////////////////////

  private final DiskStoreImpl ds;
  private final Long id;
  private long uuid;
  private long clearOplogEntryId = DiskStoreImpl.INVALID_ID;
  private RegionVersionVector clearRVV;
  private byte lruAlgorithm;
  private byte lruAction;
  private int lruLimit;
  private int concurrencyLevel = 16;
  private int initialCapacity = 16;
  private float loadFactor = 0.75f;
  private boolean statisticsEnabled;
  private boolean isBucket;
  /** True if a persistent backup is needed */
  private boolean backup;

  /** Additional flags that are persisted to the meta-data. */
  private final EnumSet<DiskRegionFlag> flags;

  /**
   * A flag used to indicate that this disk region
   * is being recreated using already existing data on the disk. 
   */
  private boolean isRecreated;
  private boolean configChanged;
  private boolean aboutToDestroy;
  private boolean aboutToDestroyDataStorage;
  private String partitionName;
  private int startingBucketId;
  private String compressorClassName;
  private Compressor compressor;
  private boolean enableOffHeapMemory;
  private final LogWriterI18n logger;
  private final boolean isMetaTable;

  /**
   * Records the version vector of what has been persisted to disk.
   * This may lag behind the version vector of what is in memory, because
   * updates may be written asynchronously to disk. We need to keep track
   * of exactly what has been written to disk so that we can record a version
   * vector at the beginning of each oplog.
   * 
   * The version vector of what is in memory is held in is held 
   * in LocalRegion.versionVector. 
   */
  private RegionVersionVector versionVector;
  
  /**
   * A flag whether the current version vector accurately represents
   * what has been written to this members disk.
   */
  private volatile boolean rvvTrusted = true;
  
  public static final long INVALID_UUID = -1L;

  /**
   * @param ds
   *          the disk store used for this disk region
   * @param name
   *          the name of this disk region
   * @param uuid
   *          A system-wide unique ID for the region. Buckets of same region can
   *          have same IDs. Essentially the name+uuid combination should be
   *          unique cluster-wide and uniform on all nodes for an instance of
   *          region created in the cluster. This is currently passed for a
   *          region via InternalRegionArguments.setUUID and is always zero for
   *          GemFire regions (#48335). One safe way to generate UUIDs is using
   *          LocalRegion.newUUID.
   */
  protected AbstractDiskRegion(DiskStoreImpl ds, String name, long uuid) {
    DiskRegionView drv;
    if (uuid != 0 && uuid != INVALID_UUID) {
      drv = ds.getDiskInitFile().getDiskRegionByName(name);
      // if UUID does not match then we know that this is a new region creation
      long drvUUID;
      if (drv != null && (drvUUID = drv.getUUID()) != 0
          && drvUUID != INVALID_UUID && drvUUID != uuid) {
        // explicitly destroy the region (#48335)
        ds.destroyRegion(name, false);
      }
    }
    drv = ds.getDiskInitFile().takeDiskRegionByName(name);
    if (drv != null) {
      // if we found one in the initFile then we take it out of it and this
      // one we are constructing will replace it in the diskStore drMap.
      this.ds = drv.getDiskStore();
      this.id = drv.getId();
      this.uuid = drv.getUUID();
      this.backup = drv.isBackup();
      this.clearOplogEntryId = drv.getClearOplogEntryId();
      this.clearRVV = drv.getClearRVV();
      this.lruAlgorithm = drv.getLruAlgorithm();
      this.lruAction = drv.getLruAction();
      this.lruLimit = drv.getLruLimit();
      this.concurrencyLevel = drv.getConcurrencyLevel();
      this.initialCapacity = drv.getInitialCapacity();
      this.loadFactor = drv.getLoadFactor();
      this.statisticsEnabled = drv.getStatisticsEnabled();
      this.isBucket = drv.isBucket();
      this.flags = drv.getFlags();
      this.partitionName = drv.getPartitionName();
      this.startingBucketId = drv.getStartingBucketId();
      this.myInitializingId = drv.getMyInitializingID();
      this.myInitializedId = drv.getMyPersistentID();
      this.aboutToDestroy = drv.wasAboutToDestroy();
      this.aboutToDestroyDataStorage = drv.wasAboutToDestroyDataStorage();
      this.onlineMembers = new CopyOnWriteHashSet<PersistentMemberID>(drv.getOnlineMembers());
      this.offlineMembers = new CopyOnWriteHashSet<PersistentMemberID>(drv.getOfflineMembers());
      this.equalMembers = new CopyOnWriteHashSet<PersistentMemberID>(drv.getOfflineAndEqualMembers());
      this.isRecreated = true;
      //Use the same atomic counters as the previous disk region. This ensures that
      //updates from threads with a reference to the old region update this disk region
      //See 49943
      this.numOverflowOnDisk = ((AbstractDiskRegion)drv).numOverflowOnDisk;
      this.numEntriesInVM = ((AbstractDiskRegion)drv).numEntriesInVM;
      this.numOverflowBytesOnDisk = ((AbstractDiskRegion)drv).numOverflowBytesOnDisk;
      this.entries = drv.getRecoveredEntryMap();
      this.readyForRecovery = drv.isReadyForRecovery();
      this.recoveredEntryCount = drv.getRecoveredEntryCount();
      this.recoveryCompleted = ((AbstractDiskRegion)drv).recoveryCompleted;
      this.versionVector = drv.getRegionVersionVector();
      this.compressorClassName = drv.getCompressorClassName();
      this.compressor = drv.getCompressor();
      this.enableOffHeapMemory = drv.getEnableOffHeapMemory();
      if (drv instanceof PlaceHolderDiskRegion) {
        this.setRVVTrusted(((PlaceHolderDiskRegion) drv).getRVVTrusted());
      }
    } else {
      //This is a brand new disk region.
      this.ds = ds;
//       {
//         DiskRegion existingDr = ds.getByName(name);
//         if (existingDr != null) {
//           throw new IllegalStateException("DiskRegion named " + name + " already exists with id=" + existingDr.getId());
//         }
//       }
      this.id = ds.generateRegionId();
      this.flags = EnumSet.noneOf(DiskRegionFlag.class);
      this.onlineMembers = new CopyOnWriteHashSet<PersistentMemberID>();
      this.offlineMembers = new CopyOnWriteHashSet<PersistentMemberID>();
      this.equalMembers = new CopyOnWriteHashSet<PersistentMemberID>();
      this.isRecreated = false;
      this.versionVector = new DiskRegionVersionVector(ds.getDiskStoreID());
      this.numOverflowOnDisk = new AtomicLong();
      this.numEntriesInVM = new AtomicLong();
      this.numOverflowBytesOnDisk = new AtomicLong();
    }
    this.logger = ds.logger;
    this.isMetaTable = LocalRegion.isMetaTable(name);
  }

  protected AbstractDiskRegion(DiskStoreImpl ds, long id, String name) {
    this.ds = ds;
    this.id = id;
    this.flags = EnumSet.noneOf(DiskRegionFlag.class);
    this.onlineMembers = new CopyOnWriteHashSet<PersistentMemberID>();
    this.offlineMembers = new CopyOnWriteHashSet<PersistentMemberID>();
    this.equalMembers = new CopyOnWriteHashSet<PersistentMemberID>();
    this.isRecreated = true;
    this.backup = true;
    this.versionVector = new DiskRegionVersionVector(ds.getDiskStoreID());
    this.logger = ds.logger;
    this.numOverflowOnDisk = new AtomicLong();
    this.numEntriesInVM = new AtomicLong();
    this.numOverflowBytesOnDisk = new AtomicLong();
    this.isMetaTable = LocalRegion.isMetaTable(name);
  }
  /**
   * Used to initialize a PlaceHolderDiskRegion for a region that is being closed
   * @param drv the region that is being closed
   */
  protected AbstractDiskRegion(DiskRegionView drv) {
    this.ds = drv.getDiskStore();
    this.id = drv.getId();
    this.uuid = drv.getUUID();
    this.backup = drv.isBackup();
    this.clearOplogEntryId = drv.getClearOplogEntryId();
    this.clearRVV = drv.getClearRVV();
    this.lruAlgorithm = drv.getLruAlgorithm();
    this.lruAction = drv.getLruAction();
    this.lruLimit = drv.getLruLimit();
    this.concurrencyLevel = drv.getConcurrencyLevel();
    this.initialCapacity = drv.getInitialCapacity();
    this.loadFactor = drv.getLoadFactor();
    this.statisticsEnabled = drv.getStatisticsEnabled();
    this.isBucket = drv.isBucket();
    this.flags = drv.getFlags();
    this.partitionName = drv.getPartitionName();
    this.startingBucketId = drv.getStartingBucketId();
    this.myInitializingId = null; // fixes 43650
    this.myInitializedId = drv.getMyPersistentID();
    this.aboutToDestroy = false;
    this.aboutToDestroyDataStorage = false;
    this.onlineMembers = new CopyOnWriteHashSet<PersistentMemberID>(drv.getOnlineMembers());
    this.offlineMembers = new CopyOnWriteHashSet<PersistentMemberID>(drv.getOfflineMembers());
    this.equalMembers = new CopyOnWriteHashSet<PersistentMemberID>(drv.getOfflineAndEqualMembers());
    this.isRecreated = true;
    this.numOverflowOnDisk = new AtomicLong();
    this.numEntriesInVM = new AtomicLong();
    this.numOverflowBytesOnDisk = new AtomicLong();
    this.entries = drv.getRecoveredEntryMap();
    this.readyForRecovery = drv.isReadyForRecovery();
    this.recoveredEntryCount = 0; // fix for bug 41570
    this.recoveryCompleted = ((AbstractDiskRegion)drv).recoveryCompleted;
    this.versionVector = drv.getRegionVersionVector();
    this.compressorClassName = drv.getCompressorClassName();
    this.compressor = drv.getCompressor();
    this.enableOffHeapMemory = drv.getEnableOffHeapMemory();
    this.logger = ds.logger;
    this.isMetaTable = drv.isMetaTable();
  }

  //////////////////////  Instance Methods  //////////////////////
  
  public abstract String getName();

  public String getFullPath() {
    return getName();
  }

  public final DiskStoreImpl getDiskStore() {
    return this.ds;
  }

  public boolean isInternalColumnTable() {
    if (isBucket) {
      return getName().contains(StoreCallbacks.SHADOW_TABLE_BUCKET_TAG);
    } else {
      return getName().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX);
    }
  }

  abstract void beginDestroyRegion(LocalRegion region);

  public void resetRVV() {
    this.versionVector = new DiskRegionVersionVector(ds.getDiskStoreID());
  }

  public final Long getId() {
    return this.id;
  }
  public final long getUUID() {
    return this.uuid;
  }
  public long getClearOplogEntryId() {
    return this.clearOplogEntryId;
  }
  public void setClearOplogEntryId(long v) {
    this.clearOplogEntryId = v;
  }
  
  public RegionVersionVector getClearRVV() {
    return this.clearRVV;
  }

  public void setClearRVV(RegionVersionVector rvv) {
    this.clearRVV = rvv;
  }

  public void setConfig(byte lruAlgorithm, byte lruAction, int lruLimit,
                        int concurrencyLevel, int initialCapacity,
                        float loadFactor, boolean statisticsEnabled,
                        boolean isBucket, EnumSet<DiskRegionFlag> flags,
                        long uuid, String partitionName, int startingBucketId,
                        String compressorClassName, boolean enableOffHeapMemory) {
    this.lruAlgorithm = lruAlgorithm;
    this.lruAction = lruAction;
    this.lruLimit = lruLimit;
    this.concurrencyLevel = concurrencyLevel;
    this.initialCapacity = initialCapacity;
    this.loadFactor = loadFactor;
    this.statisticsEnabled = statisticsEnabled;
    this.isBucket = isBucket;
    if (flags != null && flags != this.flags) {
      this.flags.clear();
      this.flags.addAll(flags);
    }
    this.uuid = uuid;
    this.partitionName = partitionName;
    this.startingBucketId = startingBucketId;
    this.compressorClassName = compressorClassName;
    if (!ds.isOffline()) {
      createCompressorFromClassName();
    }
    this.enableOffHeapMemory = enableOffHeapMemory;
    if (this.getCompressor() != null && this.enableOffHeapMemory) {
      GemFireCacheImpl gfc = GemFireCacheImpl.getInstance();
      if (gfc != null) {
        if (gfc.getOffHeapStore() != null) {
          gfc.getOffHeapStore().setCompressor(getCompressor());
        }
      }
    }
  }
  
  public void createCompressorFromClassName() {
    if (Strings.isNullOrEmpty(compressorClassName)) {
      compressor = null;
    } else {
      try {
        @SuppressWarnings("unchecked")
        Class<Compressor> compressorClass = (Class<Compressor>) ClassPathLoader.getLatest().forName(compressorClassName);
        this.compressor = compressorClass.newInstance();
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(LocalizedStrings.DiskInitFile_UNKNOWN_COMPRESSOR_0_FOUND
            .toLocalizedString(compressorClassName), e);
      } catch (InstantiationException e) {
        throw new IllegalArgumentException(LocalizedStrings.DiskInitFile_UNKNOWN_COMPRESSOR_0_FOUND
            .toLocalizedString(compressorClassName), e);
      } catch (IllegalAccessException e) {
        throw new IllegalArgumentException(LocalizedStrings.DiskInitFile_UNKNOWN_COMPRESSOR_0_FOUND
            .toLocalizedString(compressorClassName), e);
      }
    }
  }

  public EvictionAttributesImpl getEvictionAttributes() {
    return new EvictionAttributesImpl()
      .setAlgorithm(getActualLruAlgorithm())
      .setAction(getActualLruAction())
      .internalSetMaximum(getLruLimit());
  }

  public byte getLruAlgorithm() {
    return this.lruAlgorithm;
  }
  public EvictionAlgorithm getActualLruAlgorithm() {
    return EvictionAlgorithm.parseValue(getLruAlgorithm());
  }
  
  public byte getLruAction() {
    return this.lruAction;
  }
  public EvictionAction getActualLruAction() {
    return EvictionAction.parseValue(getLruAction());
  }
  public int getLruLimit() {
    return this.lruLimit;
  }
  public int getConcurrencyLevel() {
    return this.concurrencyLevel;
  }
  public int getInitialCapacity() {
    return this.initialCapacity;
  }
  public float getLoadFactor() {
    return this.loadFactor;
  }
  public boolean getStatisticsEnabled() {
    return this.statisticsEnabled;
  }
  public boolean isBucket() {
    return this.isBucket;
  }

  @Override
  public EnumSet<DiskRegionFlag> getFlags() {
    return this.flags;
  }

  public String getPartitionName() {
    return this.partitionName;
  }

  public int getStartingBucketId() {
    return this.startingBucketId;
  }

  public String getPrName() {
    assert isBucket();
    String bn = PartitionedRegionHelper.getBucketName(getName());
    return PartitionedRegionHelper.getPRPath(bn);
  }


  private PersistentMemberID myInitializingId = null;
  private PersistentMemberID myInitializedId = null;
  private final CopyOnWriteHashSet<PersistentMemberID> onlineMembers;
  private final CopyOnWriteHashSet<PersistentMemberID> offlineMembers;
  private final CopyOnWriteHashSet<PersistentMemberID> equalMembers;

  
  public PersistentMemberID addMyInitializingPMID(PersistentMemberID pmid) {
    PersistentMemberID result = this.myInitializingId;
    this.myInitializingId = pmid;
    if(result != null) {
      this.myInitializedId = result;
    }
    return result;
  }
  public void markInitialized() {
    assert this.myInitializingId != null;
    this.myInitializedId = this.myInitializingId;
    this.myInitializingId = null;
  }

  public boolean addOnlineMember(PersistentMemberID pmid) {
    return this.onlineMembers.add(pmid);
  }
  public boolean addOfflineMember(PersistentMemberID pmid) {
    return this.offlineMembers.add(pmid);
  }
  public boolean addOfflineAndEqualMember(PersistentMemberID pmid) {
    return this.equalMembers.add(pmid);
  }
  public boolean rmOnlineMember(PersistentMemberID pmid) {
    return this.onlineMembers.remove(pmid);
  }
  public boolean rmOfflineMember(PersistentMemberID pmid) {
    return this.offlineMembers.remove(pmid);
  }
  public boolean rmEqualMember(PersistentMemberID pmid) {
    return this.equalMembers.remove(pmid);
  }
  public void markBeginDestroyRegion() {
    this.aboutToDestroy = true;
  }
  public void markBeginDestroyDataStorage() {
    this.aboutToDestroyDataStorage = true;
  }
  
  public void markEndDestroyRegion() {
    this.onlineMembers.clear();
    this.offlineMembers.clear();
    this.equalMembers.clear();
    this.myInitializedId = null;
    this.myInitializingId = null;
    this.aboutToDestroy = false;
    this.isRecreated = false;
  }
  
  public void markEndDestroyDataStorage() {
    this.myInitializedId = null;
    this.myInitializingId = null;
    this.aboutToDestroyDataStorage = false;
  }
  
  // PersistentMemberView methods
  public PersistentMemberID getMyInitializingID() {
    DiskInitFile dif = this.ds.getDiskInitFile();
    if (dif == null) return this.myInitializingId;
    synchronized (dif) {
      return this.myInitializingId;
    }
  }
  public PersistentMemberID getMyPersistentID() {
    DiskInitFile dif = this.ds.getDiskInitFile();
    if (dif == null) return this.myInitializedId;
    synchronized (dif) {
      return this.myInitializedId;
    }
  }
  public Set<PersistentMemberID> getOnlineMembers() {
    DiskInitFile dif = this.ds.getDiskInitFile();
    if (dif == null) return this.onlineMembers.getSnapshot();
    synchronized (dif) {
      return this.onlineMembers.getSnapshot();
    }
  }
  public Set<PersistentMemberID> getOfflineMembers() {
    DiskInitFile dif = this.ds.getDiskInitFile();
    if (dif == null) return this.offlineMembers.getSnapshot();
    synchronized (dif) {
      return this.offlineMembers.getSnapshot();
    }
  }
  public Set<PersistentMemberID> getOfflineAndEqualMembers() {
    DiskInitFile dif = this.ds.getDiskInitFile();
    if (dif == null) return this.equalMembers.getSnapshot();
    synchronized (dif) {
      return this.equalMembers.getSnapshot();
    }
  }
  public Set<PersistentMemberPattern> getRevokedMembers() {
    //DiskInitFile dif = this.ds.getDiskInitFile();
    return ds.getRevokedMembers();
  }
  public void memberOffline(PersistentMemberID persistentID) {
    this.ds.memberOffline(this, persistentID);
    if(TRACE_VIEW || logger.fineEnabled()) {
      logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - member offline " + persistentID);
    }
  }
  public void memberOfflineAndEqual(PersistentMemberID persistentID) {
    this.ds.memberOfflineAndEqual(this, persistentID);
    if(TRACE_VIEW || logger.fineEnabled()) {
      logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - member offline and equal " + persistentID);
    }
  }
  public void memberOnline(PersistentMemberID persistentID) {
    this.ds.memberOnline(this, persistentID);
    if(TRACE_VIEW || logger.fineEnabled()) {
      logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - member online " + persistentID);
    }
  }
  public void memberRemoved(PersistentMemberID persistentID) {
    this.ds.memberRemoved(this, persistentID);
    if(TRACE_VIEW || logger.fineEnabled()) {
      logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - member removed " + persistentID);
    }
  }
  public void memberRevoked(PersistentMemberPattern revokedPattern) {
    this.ds.memberRevoked(revokedPattern);
    if(TRACE_VIEW || logger.fineEnabled()) {
      logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - member revoked " + revokedPattern);
    }
  }
  public void setInitializing(PersistentMemberID newId) {
    this.ds.setInitializing(this, newId);
    if(TRACE_VIEW || logger.fineEnabled()) {
      logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - initializing local id: " + getMyInitializingID());
    }
  }
  public void setInitialized() {
    this.ds.setInitialized(this);
    if(TRACE_VIEW || logger.fineEnabled()) {
      logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - initialized local id: " + getMyPersistentID());
    }
  }
  public PersistentMemberID generatePersistentID() {
    return this.ds.generatePersistentID(this);
  }
  public boolean isRecreated() {
    return this.isRecreated;
  }
  public boolean hasConfigChanged() {
    return this.configChanged;
  }
  public void setConfigChanged(boolean v) {
    this.configChanged = v;
  }
  
  public void endDestroy(LocalRegion region) {
    //Clean up the state if we were ready to recover this region
    if(isReadyForRecovery()) {
      ds.updateDiskRegion(this);
      this.entriesMapIncompatible = false;
      if (this.entries != null) {
        CustomEntryConcurrentHashMap<Object, Object> other = ((AbstractRegionMap)this.entries)._getMap();
        Iterator<Map.Entry<Object, Object>> it = other
            .entrySetWithReusableEntries().iterator();
        while (it.hasNext()) {
          Map.Entry<Object, Object> me = it.next();
          RegionEntry oldRe = (RegionEntry)me.getValue();
          if (oldRe != null && oldRe.isOffHeap()) {
            ((OffHeapRegionEntry) oldRe).release();
          } else {
            // no need to keep iterating; they are all either off heap or on heap.
            break;
          }
        }
      }
      this.entries = null;
      this.readyForRecovery = false;
    }
    
    if (this.aboutToDestroyDataStorage) {
      this.ds.endDestroyDataStorage(region, (DiskRegion) this);
      if(TRACE_VIEW || logger.fineEnabled()) {
        logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - endDestroyDataStorage: " + getMyPersistentID());
      }
    } else {
      this.ds.endDestroyRegion(region, (DiskRegion)this);
      if(TRACE_VIEW || logger.fineEnabled()) {
        logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - endDestroy: " + getMyPersistentID());
      }
    }
    
  }

  /**
   * Begin the destroy of everything related to this disk region.
   */
  public void beginDestroy(LocalRegion region) {
    beginDestroyRegion(region);
    if(TRACE_VIEW || logger.fineEnabled()) {
      logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - beginDestroy: " + getMyPersistentID());
    }
    if (this.myInitializedId == null) {
      endDestroy(region);
    }
  }

  /**
   * Destroy the data storage this this disk region. Destroying the
   * data storage leaves the persistent view, but removes
   * the data.
   */
  public void beginDestroyDataStorage() {
    this.ds.beginDestroyDataStorage((DiskRegion)this);
    if(TRACE_VIEW || logger.fineEnabled()) {
      logger.info(LocalizedStrings.DEBUG, "PersistentView " + getDiskStoreID().abbrev() + " - " + this.getName() + " - beginDestroyDataStorage: " + getMyPersistentID());
    }
  }
  
  public void createDataStorage() {
  }

  public boolean wasAboutToDestroy() {
    return this.aboutToDestroy;
  }
  public boolean wasAboutToDestroyDataStorage() {
    return this.aboutToDestroyDataStorage;
  }
  
  /**
   * Set to true once this DiskRegion is ready to be recovered.
   */
  private boolean readyForRecovery;
  /**
   * Total number of entries recovered by restoring from backup. Its initialized
   * right after a recovery but may be updated later as recovered entries go
   * away due to updates and destroys.
   */
  protected int recoveredEntryCount;

  protected int invalidOrTombstoneCount;

  private boolean entriesMapIncompatible;
  private boolean entriesIncompatible;
  private RegionMap entries;
  private AtomicBoolean recoveryCompleted;

  public void setEntriesMapIncompatible(boolean v) {
    this.entriesMapIncompatible = v;
  }

  public void setEntriesIncompatible(boolean v) {
    this.entriesIncompatible = v;
  }

  public RegionMap useExistingRegionMap(LocalRegion lr,
      InternalRegionArguments internalRegionArgs) {
    RegionMap result = null;
    if (!this.entriesMapIncompatible) {
      result = this.entries;
      // need to do this after region construction is complete (#42874)
      // but cannot wait until initialize since some regions may have
      // delayed initializations (e.g. in GemFireXD) so now explicit call
      // to changeOwnerForExistingRegionMap is made after constructor
      /*
      if (result != null) {
        result.changeOwner(lr, internalRegionArgs);
      }
      */
    }
    return result;
  }

  /**
   * Change the owner of the internal entries map to be the given LocalRegion.
   * This will do any initial index/schema loads as required. To be invoked
   * after LocalRegion constructor is complete (#42874).
   */
  public void changeOwnerForExistingRegionMap(LocalRegion lr,
      InternalRegionArguments internalRegionArgs) {
    if (!this.entriesMapIncompatible) {
      final RegionMap rm = this.entries;
      if (rm != null) {
        rm.changeOwner(lr, internalRegionArgs);
      }
    }
  }

  private void waitForRecoveryCompletion() {
    boolean interrupted = Thread.interrupted();
    synchronized (this.recoveryCompleted) {
      try {
        // @todo also check for shutdown of diskstore?
        while (!this.recoveryCompleted.get()) {
          try {
            this.recoveryCompleted.wait();
          } catch (InterruptedException ex) {
            interrupted = true;
          }
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  public void copyExistingRegionMap(LocalRegion lr,
      InternalRegionArguments internalRegionArgs) {
    waitForRecoveryCompletion();
    if (this.entriesMapIncompatible) {
      lr.initializeStats(this.getNumEntriesInVM(), this.getNumOverflowOnDisk(),
          this.getNumOverflowBytesOnDisk());
      lr.copyRecoveredEntries(this.entries, this.entriesIncompatible);
      this.entriesMapIncompatible = false;
    }
    else {
      this.entries.changeOwner(lr, internalRegionArgs);
      lr.initializeStats(this.getNumEntriesInVM(), this.getNumOverflowOnDisk(),
          this.getNumOverflowBytesOnDisk());
      lr.copyRecoveredEntries(null, this.entriesIncompatible);
    }
    this.entries = null;
  }

  public void setRecoveredEntryMap(RegionMap rm) {
    this.recoveryCompleted = new AtomicBoolean();
    this.entries = rm;
  }

  public RegionMap getRecoveredEntryMap() {
    return this.entries;
  }

  public void releaseRecoveryData() {
    this.readyForRecovery = false;
  }
  
  public final boolean isReadyForRecovery() {
    // better name for this method would be isRecovering
    return this.readyForRecovery;
  }

  public void prepareForRecovery() {
    this.readyForRecovery = true;
  }
  
  /**
   * gets the number of entries recovered
   * 
   * @since 3.2.1
   */
  public int getRecoveredEntryCount() {
    return this.recoveredEntryCount;
  }

  public void incRecoveredEntryCount() {
    this.recoveredEntryCount++;
  }

  public int getInvalidOrTombstoneEntryCount() {
    return this.invalidOrTombstoneCount;
  }

  public void incInvalidOrTombstoneEntryCount() {
    this.invalidOrTombstoneCount++;
  }

  /**
   * initializes the number of entries recovered
   */
  public void initRecoveredEntryCount() {
    if (this.recoveryCompleted != null) {
      synchronized (this.recoveryCompleted) {
        this.recoveryCompleted.set(true);
        this.recoveryCompleted.notifyAll();
      }
    }
  }

  protected final AtomicLong numOverflowOnDisk;

  public long getNumOverflowOnDisk() {
    return this.numOverflowOnDisk.get();
  }
  public void incNumOverflowOnDisk(long delta) {
    this.numOverflowOnDisk.addAndGet(delta);
  }
  protected final AtomicLong numOverflowBytesOnDisk;
  
  public long getNumOverflowBytesOnDisk() {
    return this.numOverflowBytesOnDisk.get();
  }
  public void incNumOverflowBytesOnDisk(long delta) {
    this.numOverflowBytesOnDisk.addAndGet(delta);
    
  }
  protected final AtomicLong numEntriesInVM;

  public long getNumEntriesInVM() {
    return this.numEntriesInVM.get();
  }
  public void incNumEntriesInVM(long delta) {
    this.numEntriesInVM.addAndGet(delta);
  }
  /**
   * Returns true if this region maintains a backup of all its keys and values
   * on disk. Returns false if only values that will not fit in memory are
   * written to disk.
   */
  public final boolean isBackup() {
    return this.backup;
  }

  @Override
  public void updateMemoryStats(Object oldValue, Object newValue) {
    // only used by BucketRegion as of now
  }

  protected final void setBackup(boolean v) {
    this.backup = v;
  }
  public void dump(PrintStream printStream) {
    String name = getName();
    if (isBucket() && !DiskStoreImpl.TRACE_RECOVERY) {
      name = getPrName();
    }
    String msg = name + ":"
      + " -lru=" + getEvictionAttributes().getAlgorithm();
    if (!getEvictionAttributes().getAlgorithm().isNone()) {
      msg += " -lruAction=" + getEvictionAttributes().getAction();
      if (!getEvictionAttributes().getAlgorithm().isLRUHeap()) {
        msg += " -lruLimit=" + getEvictionAttributes().getMaximum();
      }
    }
    msg += " -concurrencyLevel=" + getConcurrencyLevel()
      + " -initialCapacity=" + getInitialCapacity()
      + " -loadFactor=" + getLoadFactor()
      + " -compressor=" + (getCompressorClassName() == null ? "none" : getCompressorClassName())
      + " -statisticsEnabled=" + getStatisticsEnabled();
    if (DiskStoreImpl.TRACE_RECOVERY) {
      msg += " drId=" + getId()
        + " isBucket=" + isBucket()
        + " clearEntryId=" + getClearOplogEntryId()
        + " MyInitializingID=<" + getMyInitializingID() + ">"
        + " MyPersistentID=<" + getMyPersistentID() + ">"
        + " onlineMembers=" + getOnlineMembers()
        + " offlineMembers=" + getOfflineMembers()
        + " equalsMembers=" + getOfflineAndEqualMembers()
        + " flags=" + getFlags().toString();
    }
    printStream.println(msg);
  }
  
  public String dump2() {
    final String lineSeparator = System.getProperty("line.separator");
    StringBuffer sb = new StringBuffer();
    String name = getName();
    if (isBucket() && !DiskStoreImpl.TRACE_RECOVERY) {
      name = getPrName();
    }
    //String msg = name + ":"
    //  + " -lru=" + getEvictionAttributes().getAlgorithm();

    sb.append(name); sb.append(lineSeparator);
    sb.append("lru=" + getEvictionAttributes().getAlgorithm()); sb.append(lineSeparator);
    
    if (!getEvictionAttributes().getAlgorithm().isNone()) {
      sb.append("lruAction=" + getEvictionAttributes().getAction());
      sb.append(lineSeparator);
      
      if (!getEvictionAttributes().getAlgorithm().isLRUHeap()) {
        sb.append("lruAction=" + getEvictionAttributes().getAction());
        sb.append(lineSeparator);
      }
    }
    
    sb.append("-concurrencyLevel=" + getConcurrencyLevel()); sb.append(lineSeparator);
    sb.append("-initialCapacity=" + getInitialCapacity()); sb.append(lineSeparator);
    sb.append("-loadFactor=" + getLoadFactor()); sb.append(lineSeparator);
    sb.append("-compressor=" + (getCompressorClassName() == null ? "none" : getCompressorClassName())); sb.append(lineSeparator);
    sb.append("-statisticsEnabled=" + getStatisticsEnabled()); sb.append(lineSeparator);
    
    if (DiskStoreImpl.TRACE_RECOVERY) {
      sb.append("drId=" + getId()); sb.append(lineSeparator);
      sb.append("isBucket=" + isBucket()); sb.append(lineSeparator);
      sb.append("clearEntryId=" + getClearOplogEntryId()); sb.append(lineSeparator);
      sb.append("MyInitializingID=<" + getMyInitializingID() + ">"); sb.append(lineSeparator);
      sb.append("MyPersistentID=<" + getMyPersistentID() + ">"); sb.append(lineSeparator);
      sb.append("onlineMembers=" + getOnlineMembers()); sb.append(lineSeparator);
      sb.append("offlineMembers=" + getOfflineMembers()); sb.append(lineSeparator);
      sb.append("equalsMembers=" + getOfflineAndEqualMembers()); sb.append(lineSeparator);
      sb.append("flags=").append(getFlags()); sb.append(lineSeparator);
    }
    return sb.toString();
  }
  
  public void dumpMetadata() {
    String name = getName();
    //TODO - DAN - make this a flag
//    if (isBucket() && !DiskStoreImpl.TRACE_RECOVERY) {
//      name = getPrName();
//    }
    
    StringBuilder msg = new StringBuilder(name);
    
    dumpCommonAttributes(msg);
    
    dumpPersistentView(msg);

    System.out.println(msg);
  }

  /**
   * Dump the (bucket specific) persistent view to the string builder
   * @param msg
   */
  public void dumpPersistentView(StringBuilder msg) {
    msg.append("\n\tMyInitializingID=<").append(getMyInitializingID()).append(">");
    msg.append("\n\tMyPersistentID=<").append(getMyPersistentID()).append(">");
    
    msg.append("\n\tonlineMembers:");
    for (PersistentMemberID id : getOnlineMembers()) {
      msg.append("\n\t\t").append(id);
    }

    msg.append("\n\tofflineMembers:");
    for (PersistentMemberID id : getOfflineMembers()) {
      msg.append("\n\t\t").append(id);
    }

    msg.append("\n\tequalsMembers:");
    for (PersistentMemberID id : getOfflineAndEqualMembers()) {
      msg.append("\n\t\t").append(id);
    }
  }

  /**
   * Dump the attributes which are common across the PR to the string builder.
   * @param msg
   */
  public void dumpCommonAttributes(StringBuilder msg) {
    msg.append("\n\tlru=").append(getEvictionAttributes().getAlgorithm());
    if (!getEvictionAttributes().getAlgorithm().isNone()) {
      msg.append("\n\tlruAction=").append(getEvictionAttributes().getAction());
      if (!getEvictionAttributes().getAlgorithm().isLRUHeap()) {
        msg.append("\n\tlruLimit=").append(getEvictionAttributes().getMaximum());
      }
    }
    
    msg.append("\n\tconcurrencyLevel=").append(getConcurrencyLevel());
    msg.append("\n\tinitialCapacity=").append(getInitialCapacity());
    msg.append("\n\tloadFactor=").append(getLoadFactor());
    msg.append("\n\tstatisticsEnabled=").append(getStatisticsEnabled());
    
    msg.append("\n\tdrId=").append(getId());
    msg.append("\n\tisBucket=").append(isBucket());
    msg.append("\n\tclearEntryId=").append(getClearOplogEntryId());
    msg.append("\n\tflags=").append(getFlags());
  }

  /**
   * This method was added to fix bug 40192.
   * It is like getBytesAndBits except it will return Token.REMOVE_PHASE1 if
   * the htreeReference has changed (which means a clear was done).
   * @return an instance of BytesAndBits or Token.REMOVED_PHASE1
   */
  public final Object getRaw(DiskId id) {
    this.acquireReadLock();
    try {    
      return getDiskStore().getRaw(this, id);
    }
    finally {
      this.releaseReadLock();
    }
  }
  
  public RegionVersionVector getRegionVersionVector() {
    return this.versionVector;
  }

  public long getVersionForMember(VersionSource member) {
    return this.versionVector.getVersionForMember(member);
  }

  public void recordRecoveredGCVersion(VersionSource member, long gcVersion) {
    //TODO - RVV - I'm not sure about this recordGCVersion method. It seems
    //like it's not doing the right thing if the current member is the member
    //we just recovered.
    this.versionVector.recordGCVersion(member, gcVersion, null);
    
  }
  public void recordRecoveredVersonHolder(VersionSource member,
      RegionVersionHolder versionHolder, boolean latestOplog) {
    this.versionVector.initRecoveredVersion(member, versionHolder, latestOplog);
  }
  
  public void recordRecoveredVersionTag(VersionTag tag) {
    this.versionVector.recordVersion(tag.getMemberID(), tag.getRegionVersion(), null);
  }
  
  /**
   * Indicate that the current RVV for this disk region does not
   * accurately reflect what has been recorded on disk. This is true
   * while we are in the middle of a GII, because we record the new RVV
   * at the beginning of the GII. If we recover in this state, we need to
   * know that the recovered RVV is not something we can use to do a delta
   * GII.
   */
  public void setRVVTrusted(boolean trusted) {
    this.rvvTrusted = trusted;
  }
  
  public boolean getRVVTrusted() {
    return this.rvvTrusted;
  }
  
  public PersistentOplogSet getOplogSet() {
    return getDiskStore().getPersistentOplogSet(this);
  }
  
  @Override
  public String getCompressorClassName() {
    return this.compressorClassName;
  }
  
  public Compressor getCompressor() {
    return this.compressor;
  }

  @Override
  public String getColumnCompressionCodec() {
    // only expected to be invoked for BucketRegion
    return null;
  }

  @Override
  public boolean getEnableOffHeapMemory() {
    return this.enableOffHeapMemory;
  }

  public CachePerfStats getCachePerfStats() {
    return this.ds.getCache().getCachePerfStats();
  }
  
  @Override
  public void oplogRecovered(long oplogId) {
    //do nothing.  Overriden in ExportDiskRegion
  }

  @Override
  public final boolean isMetaTable() {
    return this.isMetaTable;
  }
  
  @Override
  public String toString() {
    return getClass().getSimpleName() + ":" + getName();
  }
}
