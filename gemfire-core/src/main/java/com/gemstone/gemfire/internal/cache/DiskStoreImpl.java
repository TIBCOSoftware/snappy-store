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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.GemFireIOException;
import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.DiskStore;
import com.gemstone.gemfire.cache.DiskStoreFactory;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.persistence.PersistentID;
import com.gemstone.gemfire.cache.query.IndexMaintenanceException;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.FileUtil;
import com.gemstone.gemfire.internal.InsufficientDiskSpaceException;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl.StaticSystemCallbacks;
import com.gemstone.gemfire.internal.cache.Oplog.DiskRegionInfo;
import com.gemstone.gemfire.internal.cache.Oplog.KRFEntry;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager.ResourceType;
import com.gemstone.gemfire.internal.cache.control.MemoryEvent;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds.MemoryState;
import com.gemstone.gemfire.internal.cache.control.ResourceListener;
import com.gemstone.gemfire.internal.cache.lru.LRUAlgorithm;
import com.gemstone.gemfire.internal.cache.lru.LRUStatistics;
import com.gemstone.gemfire.internal.cache.persistence.*;
import com.gemstone.gemfire.internal.cache.snapshot.GFSnapshot;
import com.gemstone.gemfire.internal.cache.snapshot.GFSnapshot.SnapshotWriter;
import com.gemstone.gemfire.internal.cache.snapshot.SnapshotPacket.SnapshotRecord;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.concurrent.ConcurrentHashSet;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.org.jgroups.oswego.concurrent.ReadWriteLock;
import com.gemstone.org.jgroups.oswego.concurrent.WriterPreferenceReadWriteLock;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import static com.gemstone.gemfire.internal.cache.GemFireCacheImpl.sysProps;

/**
 * Represents a (disk-based) persistent store for region data. Used for both
 * persistent recoverable regions and overflow-only regions.
 * 
 * @author David Whitlock
 * @author Darrel Schneider
 * @author Mitul Bid
 * @author Asif
 * 
 * @since 3.2
 */
@SuppressWarnings("synthetic-access")
public class DiskStoreImpl implements DiskStore, ResourceListener<MemoryEvent> {

  private static final String BACKUP_DIR_PREFIX = "dir";

  public static final boolean TRACE_RECOVERY = sysProps.getBoolean(
      "disk.TRACE_RECOVERY", false);
  public static final boolean TRACE_READS = sysProps.getBoolean(
      "disk.TRACE_READS", false);
  public static final boolean TRACE_WRITES = sysProps.getBoolean(
      "disk.TRACE_WRITES", false);
  public static final boolean KRF_DEBUG = sysProps.getBoolean(
      "disk.KRF_DEBUG", false);

  public static final int MAX_OPEN_INACTIVE_OPLOGS = sysProps.getInteger(
      "MAX_OPEN_INACTIVE_OPLOGS", 7);
  /* 
   * If less than 20MB (default - configurable through this property) of the
   * available space is left for logging and other misc stuff then it 
   * is better to bail out.
   */
  public static final int MIN_DISK_SPACE_FOR_LOGS = sysProps.getInteger(
      "MIN_DISK_SPACE_FOR_LOGS", 20);

  public static boolean INDEX_LOAD_DEBUG_FINER = sysProps.getBoolean(
      "IndexLoadDebugFiner", false);
  public static boolean INDEX_LOAD_DEBUG = INDEX_LOAD_DEBUG_FINER
      || sysProps.getBoolean("IndexLoadDebug", false);

  public static boolean INDEX_LOAD_PERF_DEBUG = INDEX_LOAD_DEBUG
      || sysProps.getBoolean("IndexLoadPerfDebug", false);

  /** Represents an invalid id of a key/value on disk */
  public static final long INVALID_ID = 0L; // must be zero

  private static final String COMPLETE_COMPACTION_BEFORE_TERMINATION_PROPERTY_BASE_NAME =
      "disk.completeCompactionBeforeTermination";
  public static final String COMPLETE_COMPACTION_BEFORE_TERMINATION_PROPERTY_NAME =
      sysProps.getSystemPropertyNamePrefix()
      + COMPLETE_COMPACTION_BEFORE_TERMINATION_PROPERTY_BASE_NAME;

  static final int MINIMUM_DIR_SIZE = 1024;

  /**
   * The static field delays the joining of the close/clear/destroy & forceFlush
   * operation, with the compactor thread. This joining occurs after the
   * compactor thread is notified to exit. This was added to reproduce deadlock
   * caused by concurrent destroy & clear operation where clear operation is
   * restarting the compactor thread ( a new thread object different from the
   * one for which destroy operation issued notification for release). The delay
   * occurs iff the flag used for enabling callbacks to CacheObserver is enabled
   * true
   */
  static volatile long DEBUG_DELAY_JOINING_WITH_COMPACTOR = 500;

  /**
   * Kept for backwards compat. Should use allowForceCompaction api/dtd instead.
   */
  private final static boolean ENABLE_NOTIFY_TO_ROLL = sysProps.getBoolean(
      "ENABLE_NOTIFY_TO_ROLL", false);

  private static final String RECOVER_VALUE_PROPERTY_BASE_NAME =
      "disk.recoverValues";
  public static final String RECOVER_VALUE_PROPERTY_NAME = sysProps
      .getSystemPropertyNamePrefix() + RECOVER_VALUE_PROPERTY_BASE_NAME;
  private static final String RECOVER_VALUES_SYNC_PROPERTY_BASE_NAME =
      "disk.recoverValuesSync";
  public static final String RECOVER_VALUES_SYNC_PROPERTY_NAME = sysProps
      .getSystemPropertyNamePrefix() + RECOVER_VALUES_SYNC_PROPERTY_BASE_NAME;

  /***
   * Flag to determine if KRF recovery is to be done during data extraction. 
   */
  protected boolean dataExtractionKrfRecovery = false;

  /*****
   * Flag to determine if the offline disk-store is used for data extraction
   */
  protected boolean dataExtraction = false;

  boolean RECOVER_VALUES = sysProps.getBoolean(
      RECOVER_VALUE_PROPERTY_BASE_NAME, true);
  boolean RECOVER_VALUES_SYNC = sysProps.getBoolean(
      RECOVER_VALUES_SYNC_PROPERTY_BASE_NAME, false);
  boolean FORCE_KRF_RECOVERY = sysProps.getBoolean(
      "disk.FORCE_KRF_RECOVERY", false);

  public static final int MAX_SOPLOGS_PER_LEVEL = sysProps.getInteger(
      "disk.MAX_SOPLOGS_PER_LEVEL", 4);

  //TODO soplogs - need to be able to default this to an unlimited number of levels
  //The SizeTieredCompactor currently creates all levels up front
  public static final int MAX_SOPLOG_LEVELS = sysProps.getInteger(
      "disk.MAX_SOPLOG_LEVELS", 10);

  public static final long MIN_RESERVED_DRID = 1;
  public static final long MAX_RESERVED_DRID = 8;
  static final long MIN_DRID = MAX_RESERVED_DRID + 1;

  /**
   * Estimated number of bytes written to disk for each new disk id.
   */
  static final int BYTES_PER_ID = 8;
  
  /**
   * Maximum number of oplogs to compact per compaction operations. Defaults to
   * 1 to allows oplogs to be deleted quickly, to reduce amount of memory used
   * during a compaction and to be fair to other regions waiting for a compactor
   * thread from the pool. Ignored if set to <= 0. Made non static so tests can
   * set it.
   */
  private final int MAX_OPLOGS_PER_COMPACTION = sysProps.getInteger(
      "MAX_OPLOGS_PER_COMPACTION",
      sysProps.getInteger("MAX_OPLOGS_PER_ROLL", 1));

  /**
   * This system property indicates that IF should also be preallocated. This property 
   * will be used in conjunction with the PREALLOCATE_OPLOGS property. If PREALLOCATE_OPLOGS
   * is ON the below will by default be ON but in order to switch it off you need to explicitly
   */
  static final boolean PREALLOCATE_IF = sysProps.getBoolean(
      "preAllocateIF", true);
  /**
   * This system property indicates that Oplogs should be preallocated till the
   * maxOplogSize as specified for the disk store.
   */
  static final boolean PREALLOCATE_OPLOGS = sysProps.getBoolean(
      "preAllocateDisk", true);

  /** For some testing purposes we would not consider top property if this flag is set to true **/
  public static boolean SET_IGNORE_PREALLOCATE = false;
  
  /**
   * This system property turns on synchronous writes just the the init file.
   */
  static final boolean SYNC_IF_WRITES = sysProps.getBoolean(
      "syncMetaDataWrites", false);

  /**
   * Property to disable fsync behavior to speed up precheckin runs.
   */
  public static boolean DISABLE_SYNC_WRITES_FOR_TESTS = sysProps
      .getBoolean("DISABLE_SYNC_WRITES_FOR_TESTS", false);

  // /** delay for slowing down recovery, for testing purposes only */
  // public static volatile int recoverDelay = 0;

  // extra reserved space to be freed when standby oplog is in use,
  // so that renaming of standby oplog, & creation of krf idxkrf etc is successful
  // This is to be specified in MB & default is 1 GB , i.e 1024 MB
  private static int extraReservedSpace = Integer.getInteger("gemfire.EXTRA_RESERVED_SPACE", 1024);

  // //////////////////// Instance Fields ///////////////////////

  private final GemFireCacheImpl cache;

  /** The stats for this store */
  private final DiskStoreStats stats;

  final LogWriterI18n logger;

  /**
   * Asif:Added as stop gap arrangement to fix bug 39380. It is not a clean fix
   * as keeping track of the threads acquiring read lock, etc is not a good idea
   * to solve the issue
   */
  private final AtomicInteger entryOpsCount = new AtomicInteger(0);

  /**
   * Do not want to take chance with any object like DiskRegion etc as lock
   */
  private final Object closeRegionGuard = new Object();

  /** Number of dirs* */
  final int dirLength;

  /** Disk directory holders* */
  DirectoryHolder[] directories;

  /** max of all the dir sizes given stored in bytes* */
  private final long maxDirSize;

  /** disk dir to be used by info file * */
  private int infoFileDirIndex;

  private final int compactionThreshold;

  /**
   * The limit of how many items can be in the async queue before async starts
   * blocking and a flush is forced. If this value is 0 then no limit.
   */
  private final int maxAsyncItems;
  private final AtomicInteger forceFlushCount;
  private final Object asyncMonitor;

  // complex vars
  /** Compactor task which does the compaction. Null if compaction not possible. */
  private final OplogCompactor oplogCompactor;

  private DiskInitFile initFile = null;

  private volatile DiskStoreBackup diskStoreBackup = null;

  private final ReentrantReadWriteLock compactorLock = new ReentrantReadWriteLock();
  private final WriteLock compactorWriteLock = compactorLock.writeLock();
  private final ReadLock compactorReadLock = compactorLock.readLock();

  /**
   * Set if we have encountered a disk exception causing us to shutdown this
   * disk store. This is currently used only to prevent trying to shutdown the
   * disk store from multiple threads, but I think at some point we should use
   * this to prevent any other ops from completing during the close operation.
   */
  // private final AtomicReference<DiskAccessException> diskException = new AtomicReference<DiskAccessException>();

  private boolean isForInternalUse;

  PersistentOplogSet persistentOplogs = new PersistentOplogSet(this);
  OverflowOplogSet overflowOplogs = new OverflowOplogSet(this);

  /** For testing purpose **/
  public THashMap TEST_INDEX_ACCOUNTING_MAP;
  public static boolean TEST_NEW_CONTAINER = false;
  public List<Object> TEST_NEW_CONTAINER_LIST;

  // index recovery related flags
  private static final int INDEXRECOVERY_UNINIT = 1;
  private static final int INDEXRECOVERY_INIT = 2;
  private static final int INDEXRECOVERY_DONE = 3;
  private final int[] indexRecoveryState;
  private final AtomicReference<Throwable> indexRecoveryFailure;
  private final ReadWriteLock diskStoreLock = new WriterPreferenceReadWriteLock();

  // private boolean isThreadWaitingForSpace = false;

  /**
   * Get the next available dir
   */

  // /**
  // * Max timed wait for disk space to become available for an entry operation
  // ,
  // * in milliseconds. This will be the maximum time for which a
  // * create/modify/remove operation will wait so as to allow switch over & get
  // a
  // * new Oplog for writing. If no space is available in that time,
  // * DiskAccessException will be thrown. The default wait will be for 120
  // * seconds
  // */
  // private static final long MAX_WAIT_FOR_SPACE = SystemProperties.getInteger(
  // "MAX_WAIT_FOR_SPACE", 20) * 1000;


  private final AtomicLong regionIdCtr = new AtomicLong(MIN_DRID);
  /**
   * Only contains backup DiskRegions. The Value could be a RecoveredDiskRegion
   * or a DiskRegion
   */
  private final ConcurrentMap<Long, DiskRegion> drMap = new ConcurrentHashMap<Long, DiskRegion>();
  /**
   * A set of overflow only regions that are using this disk store.
   */
  private final Set<DiskRegion> overflowMap = new ConcurrentHashSet<DiskRegion>();
  /**
   * Contains all of the disk recovery stores for which we are recovering values
   * asnynchronously.
   */
  private final Map<Long, DiskRecoveryStore> currentAsyncValueRecoveryMap = new HashMap<Long, DiskRecoveryStore>();

  private final Object asyncValueRecoveryLock = new Object();

  /**
   * The unique id for this disk store.
   * 
   * Either set during recovery of an existing disk store when the
   * IFREC_DISKSTORE_ID record is read or when a new init file is created.
   * 
   */
  private DiskStoreID diskStoreID;

  private volatile Future lastDelayedWrite;

  /**
   * Currently active disk block sorter used by region iterators for
   * cross iterator sorting in case multiple concurrent iterators are open.
   * The iterators will grab hold of currently active sorter and submit their
   * disk blocks to be sorted and at some point one of those iterators will
   * open the sorter for reading at which point no new blocks can be added
   * and a new current sorter will be created.
   */
  private final DiskBlockSortManager sortManager;

  final long standbyCrfSize;
  final long standbyDrfSize;
  final Oplog.OplogFile standByCrf;
  final Oplog.OplogFile standByDrf;
  final Oplog.OplogFile extraSpaceReservedFile;
  final static String standByFileName = "StandByOplog";
  final static String extraReservedSpaceFileName = "standByReserve";

  // ///////////////////// Constructors /////////////////////////

  private static int calcCompactionThreshold(int ct) {
    if (ct == DiskStoreFactory.DEFAULT_COMPACTION_THRESHOLD) {
      // allow the old sys prop for backwards compat.
      if (sysProps.getString("OVERFLOW_ROLL_PERCENTAGE", null) != null) {
        ct = (int) (Double.parseDouble(sysProps.getString(
            "gemfire.OVERFLOW_ROLL_PERCENTAGE", "0.50")) * 100.0);
      }
    }
    return ct;
  }

  /**
   * Creates a new <code>DiskRegion</code> that access disk on behalf of the
   * given region.
   */
  DiskStoreImpl(Cache cache, DiskStoreAttributes props) {
    this(cache, props, false, null);
  }

  DiskStoreImpl(Cache cache, DiskStoreAttributes props, boolean ownedByRegion,
      InternalRegionArguments internalRegionArgs) {
    this(cache, props.getName(), props, ownedByRegion, internalRegionArgs,
        false, false/* upgradeVersionOnly */, false, false, true);
  }
  DiskStoreImpl(Cache cache, String name, DiskStoreAttributes props,
      boolean ownedByRegion, InternalRegionArguments internalRegionArgs,
      boolean offline, boolean upgradeVersionOnly, boolean offlineValidating,
      boolean offlineCompacting, boolean needsOplogs) {
    this(cache, name, props, ownedByRegion, internalRegionArgs, offline, upgradeVersionOnly, offlineValidating, offlineCompacting, needsOplogs, false);
  }
  
  DiskStoreImpl(Cache cache, String name, DiskStoreAttributes props,
      boolean ownedByRegion, InternalRegionArguments internalRegionArgs,
      boolean offline, boolean upgradeVersionOnly, boolean offlineValidating,
      boolean offlineCompacting, boolean needsOplogs, boolean dataExtraction) {
    
    this.dataExtraction = dataExtraction;
    this.offline = offline;
    this.upgradeVersionOnly = upgradeVersionOnly;
    this.validating = offlineValidating;
    this.offlineCompacting = offlineCompacting;

    assert internalRegionArgs == null || ownedByRegion : "internalRegionArgs "
        + "should be non-null only if the DiskStore is owned by region";
    this.ownedByRegion = ownedByRegion;
    this.internalRegionArgs = internalRegionArgs;

    // validate properties before reading from it
    props.validateAndAdjust();

    this.name = name;
    this.autoCompact = props.getAutoCompact();
    this.allowForceCompaction = props.getAllowForceCompaction();
    this.compactionThreshold = calcCompactionThreshold(props
        .getCompactionThreshold());
    this.maxOplogSizeInBytes = props.getMaxOplogSizeInBytes();
    this.timeInterval = props.getTimeInterval();
    this.queueSize = props.getQueueSize();
    this.writeBufferSize = props.getWriteBufferSize();
    this.diskDirs = props.getDiskDirs();
    this.diskDirSizes = props.getDiskDirSizes();
    this.syncWrites = props.getSyncWrites() && !DISABLE_SYNC_WRITES_FOR_TESTS;
    this.cache = (GemFireCacheImpl) cache;
    logger = cache.getLoggerI18n();
    StatisticsFactory factory = cache.getDistributedSystem();
    this.stats = new DiskStoreStats(factory, getName());

    // start simple init

    this.isCompactionPossible = isOfflineCompacting()
        || (!isOffline() && (getAutoCompact() || getAllowForceCompaction() || ENABLE_NOTIFY_TO_ROLL));
    this.maxAsyncItems = getQueueSize();
    this.forceFlushCount = new AtomicInteger();
    this.asyncMonitor = new Object();
    // always use LinkedBlockingQueue to work around bug 41470
    // if (this.maxAsyncItems > 0 && this.maxAsyncItems < 1000000) {
    // // we compare to 1,000,000 so that very large maxItems will
    // // not cause us to consume too much memory in our queue.
    // // Removed the +13 since it made the queue bigger than was configured.
    // // The +13 is to give us a bit of headroom during the drain.
    // this.asyncQueue = new
    // ArrayBlockingQueue<Object>(this.maxAsyncItems/*+13*/);
    // } else {
    if (this.maxAsyncItems > 0) {
      this.asyncQueue = new ForceableLinkedBlockingQueue<Object>(
          this.maxAsyncItems); // fix for bug 41310
    } else {
      this.asyncQueue = new ForceableLinkedBlockingQueue<Object>();
    }
    if (!isValidating() && !isOfflineCompacting()) {
      startAsyncFlusher();
    }

    File[] dirs = getDiskDirs();
    // first delete any existing stand by oplog file in any of the disk dirs.
    for (File dir: dirs) {
      if (dir.exists()) {
        File []  standByOplogFiles = dir.listFiles((File parent, String fileName) ->
          fileName.equals(standByFileName + ".crf")
            || fileName.equals(standByFileName + ".drf")
            || fileName.equals(extraReservedSpaceFileName + ".oplog"));
        for ( File f: standByOplogFiles) {
          f.delete();
        }
      }
    }

    int crfPct = Integer.getInteger("gemfire.CRF_MAX_PERCENTAGE", 90);
    if (crfPct > 100 || crfPct < 0) {
      crfPct = 90;
    }

    this.standbyCrfSize = (long)(maxOplogSizeInBytes * (crfPct / 100.0)) ;
    this.standbyDrfSize = maxOplogSizeInBytes - this.standbyCrfSize;
    // now create the stand by oplog file space in the 0th directory
    this.extraSpaceReservedFile = new Oplog.OplogFile();
    this.standByCrf = new Oplog.OplogFile();
    this.standByDrf = new Oplog.OplogFile();
    File crfFile = new File(dirs[0], standByFileName +".crf");
    File drfFile = new File(dirs[0], standByFileName +".drf");
    File extraSpaceFile = new File(dirs[0], extraReservedSpaceFileName +".oplog");

    // preblow the file
    this.standByCrf.f = crfFile;
    this.standByDrf.f = drfFile;
    this.extraSpaceReservedFile.f = extraSpaceFile;
    int[] dirSizes = getDiskDirSizes();
    int length = dirs.length;
    this.directories = new DirectoryHolder[length];
    long tempMaxDirSize = 0;
    for (int i = 0; i < length; i++) {
      directories[i] = new DirectoryHolder(getName() + "_DIR#" + i, factory,
          dirs[i], dirSizes[i], i);
      // logger.info(LocalizedStrings.DEBUG, "DEBUG ds=" + name + " dir#" + i +
      // "=" + directories[i]);

      if (tempMaxDirSize < dirSizes[i]) {
        tempMaxDirSize = dirSizes[i];
      }
    }
    try {
      this.preblow(this.standByCrf, this.standbyCrfSize, directories[0]);
      this.preblow(this.standByDrf, this.standbyDrfSize, directories[0]);
      this.preblow(this.extraSpaceReservedFile, extraReservedSpace * 1024 * 1024, directories[0]);
    } catch (IOException ioe) {
      throw new IllegalStateException("Unable to reserve space for stand by oplogs");
    }

    // stored in bytes
    this.maxDirSize = tempMaxDirSize * 1024 * 1024;
    this.infoFileDirIndex = 0;
    // Now that we no longer have db files, use all directories for oplogs
    /**
     * The infoFileDir contains the lock file and the init file. It will be
     * directories[0] on a brand new disk store. On an existing disk store it
     * will be the directory the init file is found in.
     */
    this.dirLength = length;

    loadFiles(needsOplogs);

    // Store all the ddl ids which this vm has already seen
    final GemFireCacheImpl.StaticSystemCallbacks sysCb = GemFireCacheImpl
        .getInternalProductCallbacks();
    if (sysCb != null) {
      this.persistIndexes = sysCb.persistIndexes(this);
    }
    else {
      this.persistIndexes = false;
    }
    this.recoveredIndexIds = new HashSet<String>(getDiskInitFile()
        .getCreatedIndexIds());
    Set<String> deletedIndexIds = getDiskInitFile().getDeletedIndexIds();
    if (!deletedIndexIds.isEmpty()) {
      this.recoveredIndexIds.removeAll(deletedIndexIds);
    }
    this.indexRecoveryState = new int[] { INDEXRECOVERY_UNINIT };
    this.indexRecoveryFailure = new AtomicReference<Throwable>(null);

    // setFirstChild(getSortedOplogs());

    this.sortManager = new DiskBlockSortManager();

    // complex init
    if (isCompactionPossible() && !isOfflineCompacting()) {
      this.oplogCompactor = new OplogCompactor();
      this.oplogCompactor.startCompactor();
    } else {
      this.oplogCompactor = null;
    }

    // register with ResourceManager to adjust async queue size
    InternalResourceManager irm = this.cache.getResourceManager();
    if (irm != null) {
      irm.addResourceListener(ResourceType.HEAP_MEMORY, this);
    }
  }

  ////////////////////// Instance Methods //////////////////////

  void preblow(Oplog.OplogFile olf, long maxSize, DirectoryHolder dirHolder) throws IOException {
    GemFireCacheImpl.StaticSystemCallbacks ssc = GemFireCacheImpl.getInternalProductCallbacks();
    if (!this.isOfflineCompacting() && ssc != null && ssc.isSnappyStore() && ssc.isAccessor()
      && this.getName().equals(GemFireCacheImpl.getDefaultDiskStoreName())) {
      logger.warning(LocalizedStrings.SHOULDNT_INVOKE, "Pre blow is invoked on Accessor Node.");
      return;
    }

//     logger.info(LocalizedStrings.DEBUG, "DEBUG preblow(" + maxSize + ")  dirAvailSpace=" + this.dirHolder.getAvailableSpace());
    long availableSpace = dirHolder.getAvailableSpace();
    if (availableSpace >= maxSize) {
      try {
        NativeCalls.getInstance().preBlow(olf.f.getAbsolutePath(), maxSize,
          (DiskStoreImpl.PREALLOCATE_OPLOGS && !DiskStoreImpl.SET_IGNORE_PREALLOCATE));
      }
      catch (IOException ioe) {
        logger.warning(LocalizedStrings.DEBUG, "Could not pregrow oplog to " + maxSize + " because: " + ioe);
//         if (this.logger.warningEnabled()) {
//           this.logger.warning(
//               LocalizedStrings.Oplog_OPLOGCREATEOPLOGEXCEPTION_IN_PREBLOWING_THE_FILE_A_NEW_RAF_OBJECT_FOR_THE_OPLOG_FILE_WILL_BE_CREATED_WILL_NOT_BE_PREBLOWNEXCEPTION_STRING_IS_0,
//               ioe, null);
//         }
        // I don't think I need any of this. If setLength throws then
        // the file is still ok.
        // I need this on windows. I'm seeing this in testPreblowErrorCondition:
// Caused by: java.io.IOException: The parameter is incorrect
// 	at sun.nio.ch.FileDispatcher.write0(Native Method)
// 	at sun.nio.ch.FileDispatcher.write(FileDispatcher.java:44)
// 	at sun.nio.ch.IOUtil.writeFromNativeBuffer(IOUtil.java:104)
// 	at sun.nio.ch.IOUtil.write(IOUtil.java:60)
// 	at sun.nio.ch.FileChannelImpl.write(FileChannelImpl.java:206)
// 	at com.gemstone.gemfire.internal.cache.Oplog.flush(Oplog.java:3377)
// 	at com.gemstone.gemfire.internal.cache.Oplog.flushAll(Oplog.java:3419)
        /*
        {
          String os = System.getProperty("os.name");
          if (os != null) {
	    if (os.indexOf("Windows") != -1) {
              olf.raf.close();
              olf.RAFClosed = true;
              if (!olf.f.delete() && olf.f.exists()) {
                throw new DiskAccessException(LocalizedStrings.Oplog_COULD_NOT_DELETE__0_.toLocalizedString(olf.f.getAbsolutePath()), getParent());
              }
              if (logger.fineEnabled()) {
                logger.fine("recreating operation log file " + olf.f);
              }
              olf.raf = new RandomAccessFile(olf.f, SYNC_WRITES ? "rwd" : "rw");
              olf.RAFClosed = false;
            }
          }
        }
        */
        closeAndDeleteAfterEx(ioe, olf);
        throw new InsufficientDiskSpaceException(
          LocalizedStrings.Oplog_PreAllocate_Failure.toLocalizedString(
            olf.f.getAbsolutePath(), maxSize), ioe, this);
      }
    }
    // TODO: Perhaps the test flag is not requierd here. Will re-visit.
    else if (DiskStoreImpl.PREALLOCATE_OPLOGS && !DiskStoreImpl.SET_IGNORE_PREALLOCATE) {
      throw new InsufficientDiskSpaceException(
        LocalizedStrings.Oplog_PreAllocate_Failure.toLocalizedString(
          olf.f.getAbsolutePath(), maxSize), new IOException(
        "not enough space left to pre-blow, available=" + availableSpace
          + ", required=" + maxSize), this);
    }
  }

  void closeAndDeleteAfterEx(IOException ex, Oplog.OplogFile olf) {
    if (olf == null) {
      return;
    }

    if (olf.raf != null) {
      try {
        olf.raf.close();
      } catch (IOException e) {
        logger.warning(LocalizedStrings.Oplog_Close_Failed, olf.f.getAbsolutePath(), e);
      }
    }
    olf.RAFClosed = true;
    if (!olf.f.delete() && olf.f.exists()) {
      throw new DiskAccessException(
        LocalizedStrings.Oplog_COULD_NOT_DELETE__0_.toLocalizedString(olf.f
          .getAbsolutePath()), ex, this);
    }
  }

  public void setUsedForInternalUse() {
    this.isForInternalUse = true;
  }

  public boolean isUsedForInternalUse() {
    return this.isForInternalUse;
  }

  public final boolean isPersistIndexes() {
    return this.persistIndexes;
  }

  /**
   * set the async queue capacity to the current size if it has not been
   * explicitly specified
   */
  public final void setAsyncQueueCapacityToCurrent() {
    this.asyncQueue.setCurrentSizeAsCapacity();
  }

  /**
   * revert back the capacity to original capacity after a call to
   * {@link #setAsyncQueueCapacityToCurrent()}
   */
  public final void resetAsyncQueueCapacity() {
    this.asyncQueue.resetCapacity();
  }

  public boolean sameAs(DiskStoreAttributes props) {
    if (getAllowForceCompaction() != props.getAllowForceCompaction()) {
      this.logger.info(LocalizedStrings.DEBUG, "DEBUG allowForceCompaction "
          + getAllowForceCompaction() + "!=" + props.getAllowForceCompaction());
    }
    if (getAutoCompact() != props.getAutoCompact()) {
      this.logger.info(LocalizedStrings.DEBUG, "DEBUG AutoCompact "
          + getAutoCompact() + "!=" + props.getAutoCompact());
    }
    if (getCompactionThreshold() != props.getCompactionThreshold()) {
      this.logger.info(LocalizedStrings.DEBUG, "DEBUG CompactionThreshold "
          + getCompactionThreshold() + "!=" + props.getCompactionThreshold());
    }
    if (getMaxOplogSizeInBytes() != props.getMaxOplogSizeInBytes()) {
      this.logger.info(LocalizedStrings.DEBUG, "DEBUG MaxOplogSizeInBytes "
          + getMaxOplogSizeInBytes() + "!=" + props.getMaxOplogSizeInBytes());
    }
    if (!getName().equals(props.getName())) {
      this.logger.info(LocalizedStrings.DEBUG, "DEBUG Name " + getName() + "!="
          + props.getName());
    }
    if (getQueueSize() != props.getQueueSize()) {
      this.logger.info(LocalizedStrings.DEBUG, "DEBUG QueueSize "
          + getQueueSize() + "!=" + props.getQueueSize());
    }
    if (getTimeInterval() != props.getTimeInterval()) {
      this.logger.info(LocalizedStrings.DEBUG, "DEBUG TimeInterval "
          + getTimeInterval() + "!=" + props.getTimeInterval());
    }
    if (getWriteBufferSize() != props.getWriteBufferSize()) {
      this.logger.info(LocalizedStrings.DEBUG, "DEBUG WriteBufferSize "
          + getWriteBufferSize() + "!=" + props.getWriteBufferSize());
    }
    if (!Arrays.equals(getDiskDirs(), props.getDiskDirs())) {
      this.logger.info(
          LocalizedStrings.DEBUG,
          "DEBUG DiskDirs " + Arrays.toString(getDiskDirs()) + "!="
              + Arrays.toString(props.getDiskDirs()));
    }
    if (!Arrays.equals(getDiskDirSizes(), props.getDiskDirSizes())) {
      this.logger.info(LocalizedStrings.DEBUG,
          "DEBUG DiskDirSizes " + Arrays.toString(getDiskDirSizes()) + "!="
              + Arrays.toString(props.getDiskDirSizes()));
    }

    return getAllowForceCompaction() == props.getAllowForceCompaction()
        && getAutoCompact() == props.getAutoCompact()
        && getCompactionThreshold() == props.getCompactionThreshold()
        && getMaxOplogSizeInBytes() == props.getMaxOplogSizeInBytes()
        && getName().equals(props.getName())
        && getQueueSize() == props.getQueueSize()
        && getTimeInterval() == props.getTimeInterval()
        && getWriteBufferSize() == props.getWriteBufferSize()
        && Arrays.equals(getDiskDirs(), props.getDiskDirs())
        && Arrays.equals(getDiskDirSizes(), props.getDiskDirSizes());
  }

  /**
   * Returns the <code>DiskStoreStats</code> for this store
   */
  public DiskStoreStats getStats() {
    return this.stats;
  }

  public final DiskBlockSortManager getSortManager() {
    return this.sortManager;
  }

  public Map<Long, AbstractDiskRegion> getAllDiskRegions() {
    Map<Long, AbstractDiskRegion> results = new HashMap<Long, AbstractDiskRegion>();
    results.putAll(drMap);
    results.putAll(initFile.getDRMap());
    return results;
  }

  void scheduleForRecovery(DiskRecoveryStore drs) {
    DiskRegionView dr = drs.getDiskRegionView();
    PersistentOplogSet oplogSet = getPersistentOplogSet(dr);
    oplogSet.scheduleForRecovery(drs);
  }

  /**
   * Initializes the contents of any regions on this DiskStore that have been
   * registered but are not yet initialized.
   */
  final void initializeOwner(LocalRegion lr,
      InternalRegionArguments internalRegionArgs) {
    DiskRegion dr = lr.getDiskRegion();
    //We don't need to do recovery for overflow regions.
    if(!lr.getDataPolicy().withPersistence() || !dr.isRecreated()) {
      return;
    }
    
    DiskRegionView drv = lr.getDiskRegionView();
    synchronized (currentAsyncValueRecoveryMap) {
      dr.changeOwnerForExistingRegionMap(lr, internalRegionArgs);
      
      if (drv.getRecoveredEntryMap() != null) {
        PersistentOplogSet oplogSet = getPersistentOplogSet(drv);
        // prevent async recovery from recovering a value
        // while we are copying the entry map.
        drv.copyExistingRegionMap(lr, internalRegionArgs);
        getStats().incUncreatedRecoveredRegions(-1);
        for (Oplog oplog : oplogSet.getAllOplogs()) {
          if (oplog != null) {
            oplog.updateDiskRegion(lr.getDiskRegionView());
          }
        }
        if (currentAsyncValueRecoveryMap.containsKey(drv.getId())) {
          currentAsyncValueRecoveryMap.put(drv.getId(), lr);
        }
        return;
      }
    }

    scheduleForRecovery(lr);

    // boolean gotLock = false;

    try {
      // acquireReadLock(dr);
      // gotLock = true;
      recoverRegionsThatAreReady(false);
    } catch (DiskAccessException dae) {
      // Asif:Just rethrow t
      throw dae;
    } catch (RuntimeException re) {
      // @todo: if re is caused by a RegionDestroyedException
      // (or CacheClosed...) then don't we want to throw that instead
      // of a DiskAccessException?
      // Asif :wrap it in DiskAccessException
      // IOException is alerady wrappped by DiskRegion correctly.
      // Howvever EntryEventImpl .deserialize is converting IOException
      // into IllegalArgumentExcepption, so handle only run time exception
      // here
      throw new DiskAccessException(
          "RuntimeException in initializing the disk store from the disk", re,
          this);
    }
    // finally {
    // if(gotLock) {
    // releaseReadLock(dr);
    // }
    // }
  }

  public final OplogSet getOplogSet(DiskRegionView drv) {
    if (drv.isBackup()) {
      return persistentOplogs;
    }
    else {
      return overflowOplogs;
    }
  }

  public final PersistentOplogSet getPersistentOplogSet(DiskRegionView drv) {
    if (drv != null) {
      assert drv.isBackup();
    }
    return persistentOplogs;
  }

  /**
   * Stores a key/value pair from a region entry on disk. Updates all of the
   * necessary {@linkplain DiskRegionStats statistics}and invokes
   * {@link Oplog#create}or {@link Oplog#modify}.
   * 
   * @param entry
   *          The entry which is going to be written to disk
   * @param value
   *          The <code>ValueWrapper</code> for the byte data
   * @throws RegionClearedException
   *           If a clear operation completed before the put operation completed
   *           successfully, resulting in the put operation to abort.
   * @throws IllegalArgumentException
   *           If <code>id</code> is less than zero
   */
  final void put(LocalRegion region, DiskEntry entry, DiskEntry.Helper.ValueWrapper value,
      boolean async) throws RegionClearedException {
    DiskRegion dr = region.getDiskRegion();
    DiskId id = entry.getDiskId();
    if (dr.isBackup() && id.getKeyId() < 0) {
      throw new IllegalArgumentException(
          LocalizedStrings.DiskRegion_CANT_PUT_A_KEYVALUE_PAIR_WITH_ID_0
              .toLocalizedString(id));
    }
    long start = async ? this.stats.startFlush() : this.stats.startWrite();
    if (!async) {
      dr.getStats().startWrite();
    }
    try {
      if (!async) {
        acquireReadLock(dr);
      }
      try {
        if (dr.isRegionClosed()) {
          region.getCancelCriterion().checkCancelInProgress(null);
          throw new RegionDestroyedException(
              LocalizedStrings.DiskRegion_THE_DISKREGION_HAS_BEEN_CLOSED_OR_DESTROYED
                  .toLocalizedString(), dr.getName());
        }

        // Asif TODO: Should the htree reference in
        // DiskRegion/DiskRegion be made
        // volatile.Will theacquireReadLock ensure variable update?
        boolean doingCreate = false;
        if (dr.isBackup() && id.getKeyId() == INVALID_ID) {
          doingCreate = true;
          // the call to newOplogEntryId moved down into Oplog.basicCreate
        }
        boolean goahead = true;
        if (dr.didClearCountChange()) {
          // mbid: if the reference has changed (by a clear)
          // after a put has been made in the region
          // then we need to confirm if this key still exists in the region
          // before writing to disk
          goahead = region.basicGetEntry(entry.getKey()) == entry;
        }
        if (goahead) {
          // in overflow only mode, no need to write the key and the
          // extra data, hence if it is overflow only mode then use
          // modify and not create
          OplogSet oplogSet = getOplogSet(dr);
          if (doingCreate) {
            oplogSet.create(region, entry, value, async);
          } else {
            oplogSet.modify(region, entry, value, async);
          }
        } else {
          throw new RegionClearedException(
              LocalizedStrings.DiskRegion_CLEAR_OPERATION_ABORTING_THE_ONGOING_ENTRY_0_OPERATION_FOR_ENTRY_WITH_DISKID_1
                  .toLocalizedString(new Object[] {
                      ((doingCreate) ? "creation" : "modification"), id }));
        }
      } finally {
        if (!async) {
          releaseReadLock(dr);
        }
      }
    } finally {
      if (async) {
        this.stats.endFlush(start);
      } else {
        dr.getStats().endWrite(start, this.stats.endWrite(start));
        dr.getStats().incWrittenBytes(id.getValueLength());
      }
    }
  }

  final void putVersionTagOnly(LocalRegion region, VersionTag tag, boolean async) {
    DiskRegion dr = region.getDiskRegion();
    // this method will only be called by backup oplog
    assert dr.isBackup();

    if (!async) {
      acquireReadLock(dr);
    }
    try {
      if (dr.isRegionClosed()) {
        region.getCancelCriterion().checkCancelInProgress(null);
        throw new RegionDestroyedException(
            LocalizedStrings.DiskRegion_THE_DISKREGION_HAS_BEEN_CLOSED_OR_DESTROYED
                .toLocalizedString(), dr.getName());
      }

      if (dr.getRegionVersionVector().contains(tag.getMemberID(),
          tag.getRegionVersion())) {
        // No need to write the conflicting tag to disk if the disk RVV already
        // contains this tag.
        return;
      }

      PersistentOplogSet oplogSet = getPersistentOplogSet(dr);

      oplogSet.getChild().saveConflictVersionTag(region, tag, async);
    } finally {
      if (!async) {
        releaseReadLock(dr);
      }
    }
  }

  /**
   * Returns the value of the key/value pair with the given diskId. Updates all
   * of the necessary {@linkplain DiskRegionStats statistics}
   * 
   */
  final Object get(DiskRegion dr, DiskId id) {
    acquireReadLock(dr);
    try {
      int count = 0;
      RuntimeException ex = null;
      while (count < 3) {
        // retry at most 3 times
        BytesAndBits bb = null;
        try {
          bb = getBytesAndBitsWithoutLock(dr, id, true/* fault -in */, false /*
                                                                              * Get
                                                                              * only
                                                                              * the
                                                                              * userbit
                                                                              */);
          if (bb == CLEAR_BB) {
            return Token.REMOVED_PHASE1;
          }
          return convertBytesAndBitsIntoObject(bb);
        } catch (IllegalArgumentException e) {
          count++;
          this.logger.info(LocalizedStrings.DEBUG, "DiskRegion: Tried " + count
                  + ", getBytesAndBitsWithoutLock returns wrong byte array: "
                  + bb);
          ex = e;
        }
      } // while
      this.logger
          .info(
              LocalizedStrings.DEBUG,
              "Retried 3 times, getting entry from DiskRegion still failed. It must be Oplog file corruption due to HA");
      throw ex;
    } finally {
      releaseReadLock(dr);
    }
  }

  // private static String baToString(byte[] ba) {
  // StringBuffer sb = new StringBuffer();
  // for (int i=0; i < ba.length; i++) {
  // sb.append(ba[i]).append(", ");
  // }
  // return sb.toString();
  // }

  /**
   * This method was added to fix bug 40192. It is like getBytesAndBits except
   * it will return Token.REMOVE_PHASE1 if the htreeReference has changed (which
   * means a clear was done).
   * 
   * @return an instance of BytesAndBits or Token.REMOVED_PHASE1
   */
  final Object getRaw(DiskRegionView dr, DiskId id) {
    BytesAndBits bb = dr.getDiskStore().getBytesAndBitsWithoutLock(dr, id,
        true/* fault -in */, false /* Get only the userbit */);
    if (bb == CLEAR_BB) {
      return Token.REMOVED_PHASE1;
    }
    return bb;
  }

  /**
   * Given a BytesAndBits object either convert it to the relevant Object
   * (deserialize if necessary) or return the serialized blob.
   */
  private static Object convertBytesAndBits(BytesAndBits bb, boolean asObject) {
    Object value;
    if (EntryBits.isInvalid(bb.getBits())) {
      value = Token.INVALID;
    } else if (EntryBits.isSerialized(bb.getBits())) {
      value = DiskEntry.Helper.readSerializedValue(bb, asObject);
    } else if (EntryBits.isLocalInvalid(bb.getBits())) {
      value = Token.LOCAL_INVALID;
    } else if (EntryBits.isTombstone(bb.getBits())) {
      value = Token.TOMBSTONE;
    } else {
      value = DiskEntry.Helper.readRawValue(bb);
    }
    // buffer will no longer be used so clean it up eagerly
    bb.release();
    return value;
  }

  /**
   * Given a BytesAndBits object convert it to the relevant Object (deserialize
   * if necessary) and return the object
   *
   * @param bb
   * @return the converted object
   */
  static Object convertBytesAndBitsIntoObject(BytesAndBits bb) {
    return convertBytesAndBits(bb, true);
  }

  /**
   * Given a BytesAndBits object get the serialized blob
   * 
   * @param bb
   * @return the converted object
   */
  static Object convertBytesAndBitsToSerializedForm(BytesAndBits bb) {
    return convertBytesAndBits(bb, false);
  }

  // CLEAR_BB was added in reaction to bug 41306
  static final BytesAndBits CLEAR_BB = new BytesAndBits(
      DiskEntry.Helper.NULL_BUFFER, (byte) 0);

  /**
   * Gets the Object from the OpLog . It can be invoked from OpLog , if by the
   * time a get operation reaches the OpLog, the entry gets compacted or if we
   * allow concurrent put & get operations. It will also minimize the synch lock
   * on DiskId
   * 
   * @param id
   *          DiskId object for the entry
   * @return value of the entry or CLEAR_BB if it is detected that the entry was
   *         removed by a concurrent region clear.
   */
  final BytesAndBits getBytesAndBitsWithoutLock(DiskRegionView dr, DiskId id,
      boolean faultIn, boolean bitOnly) {
    if (dr.isRegionClosed()) {
      throw new RegionDestroyedException(
          LocalizedStrings.DiskRegion_THE_DISKREGION_HAS_BEEN_CLOSED_OR_DESTROYED
              .toLocalizedString(), dr.getName());
    }
    if (dr.didClearCountChange()) {
      return CLEAR_BB;
    }
    long oplogId = id.getOplogId();
    OplogSet oplogSet = getOplogSet(dr);
    CompactableOplog oplog = oplogSet.getChild(oplogId);
    if (oplog == null) {
      if (dr.didClearCountChange()) {
        return CLEAR_BB;
      }
      throw new DiskAccessException(
          LocalizedStrings.DiskRegion_DATA_FOR_DISKENTRY_HAVING_DISKID_AS_0_COULD_NOT_BE_OBTAINED_FROM_DISK_A_CLEAR_OPERATION_MAY_HAVE_DELETED_THE_OPLOGS
              .toLocalizedString(id), dr.getName());
    }
    return oplog.getBytesAndBits(dr, id, faultIn, bitOnly);
  }

  final BytesAndBits getBytesAndBits(DiskRegion dr, DiskId id,
      boolean faultingIn) {
    acquireReadLock(dr);
    try {
      BytesAndBits bb = getBytesAndBitsWithoutLock(dr, id, faultingIn, false /*
                                                                              * Get
                                                                              * only
                                                                              * user
                                                                              * bit
                                                                              */);
      if (bb == CLEAR_BB) {
        throw new DiskAccessException(
            LocalizedStrings.DiskRegion_ENTRY_HAS_BEEN_CLEARED_AND_IS_NOT_PRESENT_ON_DISK
                .toLocalizedString(), dr.getName());
      }
      return bb;
    } finally {
      releaseReadLock(dr);
    }

  }

  /**
   * @since 3.2.1
   */
  final byte getBits(DiskRegion dr, DiskId id) {
    acquireReadLock(dr);
    try {
      // TODO:Asif : Fault In?
      BytesAndBits bb = getBytesAndBitsWithoutLock(dr, id, true, true /*
                                                                       * Get
                                                                       * only
                                                                       * user
                                                                       * bit
                                                                       */);
      if (bb == CLEAR_BB) {
        return EntryBits.setInvalid((byte) 0, true);
      }
      return bb.getBits();
    } finally {
      releaseReadLock(dr);
    }

  }

  /**
   * Asif: THIS SHOULD ONLY BE USED FOR TESTING PURPOSES AS IT IS NOT THREAD
   * SAFE
   * 
   * Returns the object stored on disk with the given id. This method is used
   * for testing purposes only. As such, it bypasses the buffer and goes
   * directly to the disk. This is not a thread safe function , in the sense, it
   * is possible that by the time the OpLog is queried , data might move HTree
   * with the oplog being destroyed
   * 
   * @return null if entry has nothing stored on disk (id == INVALID_ID)
   * @throws IllegalArgumentException
   *           If <code>id</code> is less than zero, no action is taken.
   */
  public final Object getNoBuffer(DiskRegion dr, DiskId id) {
    BytesAndBits bb = null;
    acquireReadLock(dr);
    try {
      long opId = id.getOplogId();
      if (opId != -1) {
        OplogSet oplogSet = getOplogSet(dr);
        bb = oplogSet.getChild(opId).getNoBuffer(dr, id);
        return convertBytesAndBitsIntoObject(bb);
      } else {
        return null;
      }
    } finally {
      releaseReadLock(dr);
    }
  }

  void testHookCloseAllOverflowChannels() {
    overflowOplogs.testHookCloseAllOverflowChannels();
  }

  ArrayList<OverflowOplog> testHookGetAllOverflowOplogs() {
    return overflowOplogs.testHookGetAllOverflowOplogs();
  }

  void testHookCloseAllOverflowOplogs() {
    overflowOplogs.testHookCloseAllOverflowOplogs();
  }

  /**
   * Removes the key/value pair with the given id on disk.
   * 
   * @param async
   *          true if called by the async flusher thread
   * 
   * @throws RegionClearedException
   *           If a clear operation completed before the put operation completed
   *           successfully, resulting in the put operation to abort.
   * @throws IllegalArgumentException
   *           If <code>id</code> is {@linkplain #INVALID_ID invalid}or is less
   *           than zero, no action is taken.
   */
  final void remove(LocalRegion region, DiskEntry entry, boolean async,
      boolean isClear) throws RegionClearedException {
    DiskRegion dr = region.getDiskRegion();
    if (!async) {
      acquireReadLock(dr);
    }
    try {
      if (dr.isRegionClosed()) {
        throw new RegionDestroyedException(
            LocalizedStrings.DiskRegion_THE_DISKREGION_HAS_BEEN_CLOSED_OR_DESTROYED
                .toLocalizedString(), dr.getName());
      }

      // mbid: if reference has changed (only clear
      // can change the reference) then we should not try to remove again.
      // Entry will not be found in diskRegion.
      // So if reference has changed, do nothing.
      if (!dr.didClearCountChange()) {
        long start = this.stats.startRemove();
        OplogSet oplogSet = getOplogSet(dr);
        oplogSet.remove(region, entry, async, isClear);
        dr.getStats().endRemove(start, this.stats.endRemove(start));
      } else {
        throw new RegionClearedException(
            LocalizedStrings.DiskRegion_CLEAR_OPERATION_ABORTING_THE_ONGOING_ENTRY_DESTRUCTION_OPERATION_FOR_ENTRY_WITH_DISKID_0
                .toLocalizedString(entry.getDiskId()));
      }
    } finally {
      if (!async) {
        releaseReadLock(dr);
      }
    }
  }

  private FlushPauser fp = null;

  /**
   * After tests call this method they must call flushForTesting.
   */
  public void pauseFlusherForTesting() {
    assert this.fp == null;
    this.fp = new FlushPauser();
    try {
      addAsyncItem(this.fp, true);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("unexpected interrupt in test code", ex);
    }
  }

  public void flushForTesting() {
    if (this.fp != null) {
      this.fp.unpause();
      this.fp = null;
    }
    forceFlush();
  }

  // //////////////////// Implementation Methods //////////////////////
  

  /**
   * This function is having a default visiblity as it is used in the
   * OplogJUnitTest for a bug verification of Bug # 35012
   * 
   * All callers must have {@link #releaseWriteLock(DiskRegion)} in a matching
   * finally block.
   * 
   * Note that this is no longer implemented by getting a write lock but instead
   * locks the same lock that acquireReadLock does.
   * 
   * @since 5.1
   */
  private void acquireWriteLock(DiskRegion dr) {
    // @todo darrel: this is no longer a write lock need to change method name
    dr.acquireWriteLock();
  }

  /**
   * 
   * This function is having a default visiblity as it is used in the
   * OplogJUnitTest for a bug verification of Bug # 35012
   * 
   * @since 5.1
   */

  private void releaseWriteLock(DiskRegion dr) {
    // @todo darrel: this is no longer a write lock need to change method name
    dr.releaseWriteLock();
  }

  /**
   * All callers must have {@link #releaseReadLock(DiskRegion)} in a matching
   * finally block. Note that this is no longer implemented by getting a read
   * lock but instead locks the same lock that acquireWriteLock does.
   * 
   * @since 5.1
   */
  void acquireReadLock(DiskRegion dr) {
    dr.basicAcquireReadLock();
    synchronized (this.closeRegionGuard) {
      entryOpsCount.incrementAndGet();
      if (dr.isRegionClosed()) {
        dr.releaseReadLock();
        throw new RegionDestroyedException(
            "The DiskRegion has been closed or destroyed", dr.getName());
      }
    }
  }

  /**
   * @since 5.1
   */

  void releaseReadLock(DiskRegion dr) {
    dr.basicReleaseReadLock();
    int currentOpsInProgress = entryOpsCount.decrementAndGet();
    // Potential candiate for notifying in case of disconnect
    if (currentOpsInProgress == 0) {
      synchronized (this.closeRegionGuard) {
        if (dr.isRegionClosed() && entryOpsCount.get() == 0) {
          this.closeRegionGuard.notify();
        }
      }
    }
  }

  public void forceRoll() {
    persistentOplogs.forceRoll(null);
  }

  public void forceRoll(boolean blocking) {
    Oplog child = persistentOplogs.getChild();
    if (child != null) {
      child.forceRolling(null, blocking);
    }
  }

  /**
   * @since 5.1
   */
  public void forceRolling(DiskRegion dr) {
    if (!dr.isBackup())
      return;
    if (!dr.isSync() && this.maxAsyncItems == 0 && getTimeInterval() == 0) {
      forceFlush();
    }
    acquireReadLock(dr);
    try {
      PersistentOplogSet oplogSet = getPersistentOplogSet(dr);
      oplogSet.forceRoll(dr);
    } finally {
      releaseReadLock(dr);
    }
  }

  public boolean forceCompaction() {
    return basicForceCompaction(null);
  }

  public boolean forceCompaction(DiskRegion dr) {
    if (!dr.isBackup())
      return false;
    acquireReadLock(dr);
    try {
      return basicForceCompaction(dr);
    } finally {
      releaseReadLock(dr);
    }
  }

  /**
   * Get serialized form of data off the disk
   * 
   * @param id
   * @since gemfire5.7_hotfix
   */
  public Object getSerializedData(DiskRegion dr, DiskId id) {
    return convertBytesAndBitsToSerializedForm(getBytesAndBits(dr, id, true));
  }

  public Object getSerializedDataWithoutLock(DiskRegionView dr, DiskId id,
      boolean faultIn) {
    return convertBytesAndBitsToSerializedForm(getBytesAndBitsWithoutLock(dr,
        id, faultIn, false));
  }

  private void checkForFlusherThreadTermination() {
    if (this.flusherThreadTerminated) {
      String message = "Could not schedule asynchronous write because the flusher thread had been terminated.";
      if(this.isClosing()) {
     // for bug 41305
        throw this.cache
            .getCacheClosedException(message, null);
      } else {
        throw new DiskAccessException(message, this);
      }
      
    }
  }

  private void handleFullAsyncQueue(Object o) {
    AsyncDiskEntry ade = (AsyncDiskEntry) o;
    LocalRegion region = ade.region;
    try {
      VersionTag tag = ade.tag;
      if (ade.versionOnly) {
        if (tag != null) {
          DiskEntry.Helper.doAsyncFlush(tag, region);
        }
      } else {
        DiskEntry entry = ade.de;
        DiskEntry.Helper.handleFullAsyncQueue(entry, region, tag);
      }
    } catch (RegionDestroyedException ex) {
      // Normally we flush before closing or destroying a region
      // but in some cases it is closed w/o flushing.
      // So just ignore it; see bug 41305.
    }
  }

  public void addDiskRegionToQueue(LocalRegion lr) {
    try {
      addAsyncItem(lr, true);
    } catch (InterruptedException ignore) {
      // If it fail, that means the RVVTrusted is not written. It will 
      // automatically do full-GII
    }
  }
  
  private void addAsyncItem(Object item, boolean forceAsync)
      throws InterruptedException {
    synchronized (this.lock) { // fix for bug 41390
      // 43312: since this thread has gained dsi.lock, dsi.clear() should have
      // finished. We check if clear() has happened after ARM.putEntryIfAbsent()
      if (item instanceof AsyncDiskEntry) {
        AsyncDiskEntry ade = (AsyncDiskEntry) item;
        DiskRegion dr = ade.region.getDiskRegion();
        if (dr.didClearCountChange() && !ade.versionOnly) {
          return;
        }
        if (ade.region.isDestroyed) {
          throw new RegionDestroyedException(ade.region.toString(), ade.region.getFullPath());
        }
      }
      checkForFlusherThreadTermination();
      if (forceAsync) {
        this.asyncQueue.forcePut(item);
      } else {
        if (!this.asyncQueue.offer(item)) {
          // queue is full so do a sync write to prevent deadlock
          handleFullAsyncQueue(item);
          // return early since we didn't add it to the queue
          return;
        }
      }
      this.stats.incQueueSize(1);
    }
    // this.logger.info(LocalizedStrings.DEBUG, "DEBUG addAsyncItem=" + item);
    if (this.maxAsyncItems > 0) {
      if (checkAsyncItemLimit()) {
        synchronized (this.asyncMonitor) {
          this.asyncMonitor.notifyAll();
        }
      }
    }
  }

  private void rmAsyncItem(Object item) {
    if (this.asyncQueue.remove(item)) {
      this.stats.incQueueSize(-1);
    }
  }

  private long startAsyncWrite(DiskRegion dr) {
    if (this.stoppingFlusher) {
      if (isClosed()) {
        throw (new Stopper()).generateCancelledException(null); // fix for bug
                                                                // 41141
      } else {
        throw new DiskAccessException(
            "The disk store is still open, but flusher is stopped, probably no space left on device",
            this);
      }
    } else {
      this.pendingAsyncEnqueue.incrementAndGet();
    }
    // logger.info(LocalizedStrings.DEBUG, "DEBUG startAsyncWrite");
    dr.getStats().startWrite();
    return this.stats.startWrite();
  }

  private void endAsyncWrite(AsyncDiskEntry ade, DiskRegion dr, long start) {
    // logger.info(LocalizedStrings.DEBUG, "DEBUG endAsyncWrite");
    this.pendingAsyncEnqueue.decrementAndGet();
    dr.getStats().endWrite(start, this.stats.endWrite(start));
    
    if (!ade.versionOnly) { // for versionOnly = true ade.de will be null
      long bytesWritten = ade.de.getDiskId().getValueLength();
      dr.getStats().incWrittenBytes(bytesWritten);
    }

  }

  /**
   * @since prPersistSprint1
   */
  public void scheduleAsyncWrite(AsyncDiskEntry ade) {
    DiskRegion dr = ade.region.getDiskRegion();
    long start = startAsyncWrite(dr);
    try {
      try {
        addAsyncItem(ade, false);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        ade.region.getCancelCriterion().checkCancelInProgress(ie);
        // @todo: I'm not sure we need an error here
        if (!ade.versionOnly)
          ade.de.getDiskId().setPendingAsync(false);
      }
    } finally {
      endAsyncWrite(ade, dr, start);
    }
  }

  /**
   * @since prPersistSprint1
   */
  public void unscheduleAsyncWrite(DiskId did) {
    if (did != null) {
      did.setPendingAsync(false);
      // we could remove it from the async buffer but currently
      // we just wait for the flusher to discover it and drop it.
    }
  }

  /**
   * This queue can continue DiskEntry of FlushNotifier.
   */
  private final ForceableLinkedBlockingQueue<Object> asyncQueue;
  private final Object drainSync = new Object();
  private ArrayList drainList = null;

  private int fillDrainList() {
    synchronized (this.drainSync) {
      this.drainList = new ArrayList(asyncQueue.size());
      int drainCount = asyncQueue.drainTo(this.drainList);
      return drainCount;
    }
  }

  private ArrayList getDrainList() {
    return this.drainList;
  }

  /**
   * To fix bug 41770 clear the list in a way that will not break a concurrent
   * iterator that is not synced on drainSync. Only clear from it entries on the
   * given region. Currently we do this by clearing the isPendingAsync bit on
   * each entry in this list.
   * 
   * @param rvv
   */
  void clearDrainList(LocalRegion r, RegionVersionVector rvv) {
    synchronized (this.drainSync) {
      if (this.drainList == null)
        return;
      Iterator it = this.drainList.iterator();
      while (it.hasNext()) {
        Object o = it.next();
        if (o instanceof AsyncDiskEntry) {
          AsyncDiskEntry ade = (AsyncDiskEntry) o;
          if (shouldClear(r, rvv, ade) && ade.de != null) {
            unsetPendingAsync(ade);
          }
        }
      }
    }
  }

  private boolean shouldClear(LocalRegion r, RegionVersionVector rvv,
      AsyncDiskEntry ade) {
    if (ade.region != r) {
      return false;
    }

    // If no RVV, remove all of the async items for this region.
    if (rvv == null) {
      return true;
    }

    // If we are clearing based on an RVV, only remove
    // entries contained in the RVV
    if (ade.versionOnly) {
      return rvv.contains(ade.tag.getMemberID(), ade.tag.getRegionVersion());
    } else {
      VersionStamp stamp = ade.de.getVersionStamp();
      VersionSource member = stamp.getMemberID();
      if (member == null) {
        // For overflow only regions, the version member may be null
        // because that represents the local internal distributed member
        member = r.getVersionMember();
      }
      return rvv.contains(member, stamp.getRegionVersion());
    }

  }

  /**
   * Clear the pending async bit on a disk entry.
   */
  private void unsetPendingAsync(AsyncDiskEntry ade) {
    DiskId did = ade.de.getDiskId();
    if (did != null && did.isPendingAsync()) {
      synchronized (did) {
        did.setPendingAsync(false);
      }
    }
  }

  private Thread flusherThread;
  /**
   * How many threads are waiting to do a put on asyncQueue?
   */
  private final AtomicInteger pendingAsyncEnqueue = new AtomicInteger();
  private volatile boolean stoppingFlusher;
  private volatile boolean stopFlusher;
  private volatile boolean flusherThreadTerminated;

  private void startAsyncFlusher() {
    final String thName = LocalizedStrings.DiskRegion_ASYNCHRONOUS_DISK_WRITER_0
        .toLocalizedString(new Object[] { getName() });
    this.flusherThread = new Thread(LogWriterImpl.createThreadGroup(
        LocalizedStrings.DiskRegion_DISK_WRITERS.toLocalizedString(),
        getCache().getDistributedSystem().getLogWriter()
            .convertToLogWriterI18n()), new FlusherThread(), thName);
    this.flusherThread.setDaemon(true);
    this.flusherThread.start();
  }

  protected void stopAsyncFlusher() {
    // logger.info(LocalizedStrings.DEBUG, "DEBUG stopAsyncFlusher immediately="
    // + immediately);
    this.stoppingFlusher = true;
    do {
      // Need to keep looping as long as we have more threads
      // that are already pending a put on the asyncQueue.
      // New threads will fail because stoppingFlusher has been set.
      // See bug 41141.
      forceFlush();
    } while (this.pendingAsyncEnqueue.get() > 0);
    // logger.info(LocalizedStrings.DEBUG, "DEBUG "
    // + this.owner.getFullPath()
    // + " stopAsyncFlusher immediately=" + immediately);
    synchronized (asyncMonitor) {
      this.stopFlusher = true;
      this.asyncMonitor.notifyAll();
    }
    while (!this.flusherThreadTerminated) {
      try {
        // check if cache is going down and in that case also terminate the
        // flusher thread.
        getCache().getCancelCriterion().checkCancelInProgress(null);
        this.flusherThread.join(100);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        getCache().getCancelCriterion().checkCancelInProgress(ie);
      }
    }
  }

  public boolean testWaitForAsyncFlusherThread(int waitMs) {
    try {
      this.flusherThread.join(waitMs);
      return true;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    return false;
  }

  /**
   * force a flush but do it async (don't wait for the flush to complete).
   */
  public void asynchForceFlush() {
    try {
      flushFlusher(true);
    } catch (InterruptedException ignore) {
    }
  }

  public GemFireCacheImpl getCache() {
    return this.cache;
  }

  public void flush() {
    forceFlush();
  }

  /**
   * Flush all async queue data, and fsync all oplogs to disk.
   */
  public final void flushAndSync() {
    forceFlush();
    acquireCompactorWriteLock();
    try {
      for (Oplog oplog : getPersistentOplogSet(null).getAllOplogs()) {
        oplog.flushAllAndSync();
      }
    } finally {
      releaseCompactorWriteLock();
    }
  }

  /**
   * 
   */
  public final void flushAndSync(boolean noCompactorLock) {
    forceFlush();
    for (Oplog oplog : getPersistentOplogSet(null).getAllOplogs()) {
      oplog.flushAllAndSync(true);
    }
  }
  
  public void forceFlush() {
    try {
      flushFlusher(false);
    } catch (InterruptedException ie) {
      // logger.info(LocalizedStrings.DEBUG, "DEBUG forceFlush interrupted");
      Thread.currentThread().interrupt();
      getCache().getCancelCriterion().checkCancelInProgress(ie);
    }
  }

  private boolean isFlusherTerminated() {
    return this.stopFlusher || this.flusherThreadTerminated
        || this.flusherThread == null || !this.flusherThread.isAlive();
  }

  private void flushFlusher(boolean async) throws InterruptedException {
    if (!isFlusherTerminated()) {
      FlushNotifier fn = new FlushNotifier();
      addAsyncItem(fn, true);
      if (isFlusherTerminated()) {
        rmAsyncItem(fn);
        // logger.info(LocalizedStrings.DEBUG, "DEBUG flusher terminated #1");
      } else {
        incForceFlush();
        if (!async) {
          // logger.info(LocalizedStrings.DEBUG, "DEBUG flushFlusher waiting");
          fn.waitForFlush();
          // logger.info(LocalizedStrings.DEBUG,
          // "DEBUG flushFlusher done waiting");
        }
      }
      // } else {
      // logger.info(LocalizedStrings.DEBUG, "DEBUG flusher terminated #2");
    }
  }

  private void incForceFlush() {
    synchronized (this.asyncMonitor) {
      this.forceFlushCount.incrementAndGet(); // moved inside sync to fix bug
                                              // 41654
      this.asyncMonitor.notifyAll();
    }
  }

  /**
   * Return true if a non-zero value is found and the decrement was done.
   */
  private boolean checkAndClearForceFlush() {
    if (stopFlusher) {
      return true;
    }
    boolean done = false;
    boolean result;
    do {
      int v = this.forceFlushCount.get();
      result = v > 0;
      if (result) {
        done = this.forceFlushCount.compareAndSet(v, 0);
      }
    } while (result && !done);
    return result;
  }

  private class FlushPauser extends FlushNotifier {
    @Override
    public synchronized void doFlush() {
      // logger.info(LocalizedStrings.DEBUG, "DEBUG: doFlush");
      // this is called by flusher thread so have it wait
      try {
        super.waitForFlush();
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
    }

    public synchronized void unpause() {
      super.doFlush();
    }

    @Override
    protected boolean isStoppingFlusher() {
      return stoppingFlusher;
    }
  }

  private class FlushNotifier {
    private boolean flushed;

    protected boolean isStoppingFlusher() {
      return false;
    }

    public synchronized void waitForFlush() throws InterruptedException {
      while (!flushed && !isFlusherTerminated() && !isStoppingFlusher()) {
        wait(333);
      }
    }

    public synchronized void doFlush() {
      this.flushed = true;
      notifyAll();
    }
  }

  /**
   * Return true if we have enough async items to do a flush
   */
  private boolean checkAsyncItemLimit() {
    return this.asyncQueue.size() >= this.maxAsyncItems;
  }

  private class FlusherThread implements Runnable {
    private boolean waitUntilFlushIsReady() throws InterruptedException {
      if (maxAsyncItems > 0) {
        final long time = getTimeInterval();
        synchronized (asyncMonitor) {
          if (time > 0) {
            long nanosRemaining = TimeUnit.MILLISECONDS.toNanos(time);
            final long endTime = System.nanoTime() + nanosRemaining;
            boolean done = checkAndClearForceFlush() || checkAsyncItemLimit();
            while (!done && nanosRemaining > 0) {
              TimeUnit.NANOSECONDS.timedWait(asyncMonitor, nanosRemaining);
              done = checkAndClearForceFlush() || checkAsyncItemLimit();
              if (!done) {
                nanosRemaining = endTime - System.nanoTime();
              }
            }
          } else {
            boolean done = checkAndClearForceFlush() || checkAsyncItemLimit();
            while (!done) {
              asyncMonitor.wait();
              done = checkAndClearForceFlush() || checkAsyncItemLimit();
            }
          }
        }
      } else {
        long time = getTimeInterval();
        if (time > 0) {
          long nanosRemaining = TimeUnit.MILLISECONDS.toNanos(time);
          final long endTime = System.nanoTime() + nanosRemaining;
          synchronized (asyncMonitor) {
            boolean done = checkAndClearForceFlush();
            while (!done && nanosRemaining > 0) {
              TimeUnit.NANOSECONDS.timedWait(asyncMonitor, nanosRemaining);
              done = checkAndClearForceFlush();
              if (!done) {
                nanosRemaining = endTime - System.nanoTime();
              }
            }
          }
        } else {
          // wait for a forceFlush
          synchronized (asyncMonitor) {
            boolean done = checkAndClearForceFlush();
            while (!done) {
              asyncMonitor.wait();
              done = checkAndClearForceFlush();
            }
          }
        }
      }
      return !stopFlusher;
    }

    private void flushChild() {
      persistentOplogs.flushChild();
    }

    public void run() {

      // logger.info(LocalizedStrings.DEBUG, "DEBUG maxAsyncItems=" +
      // maxAsyncItems
      // + " asyncTime=" + getTimeInterval());
      if (logger.fineEnabled()) {
        logger.fine("Async writer thread started");
      }
      boolean doingFlush = false;
      boolean terminateFlusherThread = true;
      try {
        while (waitUntilFlushIsReady()) {
          int drainCount = fillDrainList();
          if (drainCount > 0) {
            stats.incQueueSize(-drainCount);
            Iterator it = getDrainList().iterator();
            while (it.hasNext()) {
              Object o = it.next();
              // logger.info(LocalizedStrings.DEBUG, "DEBUG: asyncDequeue=" +
              // o);
              if (o instanceof FlushNotifier) {
                flushChild();
                if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
                  if (!it.hasNext()) {
                    doingFlush = false;
                    CacheObserverHolder.getInstance().afterWritingBytes();
                  }
                }
                // logger.info(LocalizedStrings.DEBUG,
                // "DEBUG: about to flush");
                ((FlushNotifier) o).doFlush();
                // logger.info(LocalizedStrings.DEBUG, "DEBUG: after flush");
              } else {
                try {
                  if (o!=null && o instanceof LocalRegion) {
                    LocalRegion lr = (LocalRegion)o;
                    lr.getDiskRegion().writeRVV(null, true);
                    lr.getDiskRegion().writeRVVGC(lr);
                  } else {
                    AsyncDiskEntry ade = (AsyncDiskEntry) o;
                    LocalRegion region = ade.region;
                    VersionTag tag = ade.tag;
                    if (ade.versionOnly) {
                      DiskEntry.Helper.doAsyncFlush(tag, region);
                    } else {
                      DiskEntry entry = ade.de;
                      // We check isPendingAsync
                      if (entry.getDiskId().isPendingAsync()) {
                        if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
                          if (!doingFlush) {
                            doingFlush = true;
                            CacheObserverHolder.getInstance().goingToFlush();
                          }
                        }
                        DiskEntry.Helper.doAsyncFlush(entry, region, tag);
                      } else {
                        // If it is no longer pending someone called
                        // unscheduleAsyncWrite
                        // so we don't need to write the entry, but
                        // if we have a version tag we need to record the
                        // operation
                        // to update the RVV
                        if (tag != null) {
                          DiskEntry.Helper.doAsyncFlush(tag, region);
                        }
                      }
                    }
                  } // else
                } catch (RegionDestroyedException ex) {
                  // Normally we flush before closing or destroying a region
                  // but in some cases it is closed w/o flushing.
                  // So just ignore it; see bug 41305.
                }
              }
            }
            flushChild();
            if (doingFlush) {
              doingFlush = false;
              if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
                CacheObserverHolder.getInstance().afterWritingBytes();
              }
            }
          }
        }
      } catch (InterruptedException ie) {
        // logger.info(LocalizedStrings.DEBUG, "DEBUG: interrupted");
        flushChild();
        Thread.currentThread().interrupt();
        getCache().getCancelCriterion().checkCancelInProgress(ie);
        throw new IllegalStateException(
            "Async writer thread stopping due to unexpected interrupt");
      } catch (DiskAccessException dae) {
        // logger.info(LocalizedStrings.DEBUG, "DEBUG: dae", dae);
        boolean okToIgnore = dae.getCause() instanceof ClosedByInterruptException;

      } catch (CancelException ignore) {
        // logger.info(LocalizedStrings.DEBUG, "DEBUG", ignore);
        // the above checkCancelInProgress will throw a CancelException
        // when we are being shutdown
      } catch (Throwable t) {
        getCache().getCancelCriterion().checkCancelInProgress(t);
        if (!(t instanceof IOException)) {
          terminateFlusherThread = false;
          throw new GemFireIOException("Exception encountered in flusher thread: " + t.getMessage(), t);
        }
        logger.severe(LocalizedStrings.DiskStoreImpl_FATAL_ERROR_ON_FLUSH, t);

      } finally {
        // logger.info(LocalizedStrings.DEBUG,
        // "DEBUG: Async writer thread stopped stopFlusher=" + stopFlusher);
        if (logger.fineEnabled()) {
          logger.fine("Async writer thread stopped. Pending opcount="
              + asyncQueue.size());
        }
        if (terminateFlusherThread) {
          flusherThreadTerminated = true;
          stopFlusher = true; // set this before calling handleDiskAccessException
          // or it will hang

        }
      }
    }
  }

  // simple code
  /** Extension of the oplog lock file * */
  private static final String LOCK_FILE_EXT = ".lk";
  private FileLock fl;
  private File lockFile;

  private void createLockFile(String name) throws DiskAccessException {
    File f = new File(getInfoFileDir().getDir(), "DRLK_IF" + name
        + LOCK_FILE_EXT);
    if (logger.fineEnabled()) {
      logger.fine("Creating lock file " + f.getAbsolutePath()/*, new RuntimeException("STACK")*/);
    }
    FileOutputStream fs = null;
    // 41734: A known NFS issue on Redhat. The thread created the directory,
    // but when it try to lock, it will fail with permission denied or
    // input/output
    // error. To workarround it, introduce 5 times retries.
    int cnt = 0;
    DiskAccessException dae = null;
    do {
      try {
        fs = new FileOutputStream(f);
        this.lockFile = f;
        this.fl = fs.getChannel().tryLock();
        if (fl == null) {
          try {
            fs.close();
          } catch (IOException ignore) {
          }
          throw new IOException(
              LocalizedStrings.Oplog_THE_FILE_0_IS_BEING_USED_BY_ANOTHER_PROCESS
                  .toLocalizedString(f));
        }
        f.deleteOnExit();
        dae = null;
        break;
      } catch (IOException ex) {
        if (fs != null) {
          try {
            fs.close();
          } catch (IOException ignore) {
          }
        }
        dae = new DiskAccessException(
            LocalizedStrings.Oplog_COULD_NOT_LOCK_0.toLocalizedString(f
                .getPath()), ex, this);
      } catch (IllegalStateException ex2) {
        // OverlappingFileLockExtension needs to be caught here see bug 41290
        if (fs != null) {
          try {
            fs.close();
          } catch (IOException ignore) {
          }
        }
        dae = new DiskAccessException(
            LocalizedStrings.Oplog_COULD_NOT_LOCK_0.toLocalizedString(f
                .getPath()), ex2, this);
      }
      cnt++;
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    } while (cnt < 100);
    if (dae != null) {
      throw dae;
    }
    logger.info(LocalizedStrings.DEBUG, "Locked disk store " + name
        + " for exclusive access in directory: " + getInfoFileDir().getDir()); // added
                                                                               // to
                                                                               // help
                                                                               // debug
                                                                               // 41734

  }

  void closeLockFile() {
    FileLock myfl = this.fl;
    if (myfl != null) {
      try {
        FileChannel fc = myfl.channel();
        if (myfl.isValid()) {
          myfl.release();
        }
        fc.close();
      } catch (IOException ignore) {
      }
      this.fl = null;
    }
    File f = this.lockFile;
    if (f != null) {
      if (f.delete()) {
        if (logger.fineEnabled()) {
          logger.fine("Deleted lock file " + f);
        }
      } else if (f.exists()) {
        if (logger.fineEnabled()) {
          logger.fine("Could not delete lock file " + f);
        }
      }
    }
    logger.info(LocalizedStrings.DEBUG, "Unlocked disk store " + name); // added
                                                                        // to
                                                                        // help
                                                                        // debug
                                                                        // 41734
  }

  private String getRecoveredGFVersionName() {
    String currentVersionStr = "GFE pre-7.0";
    Version version = getRecoveredGFVersion();
    if (version != null) {
      currentVersionStr = version.toString();
    }
    return currentVersionStr;
  }

  /**
   * Searches the given disk dirs for the files and creates the Oplog objects
   * wrapping those files
   */
  private void loadFiles(boolean needsOplogs) {
    String partialFileName = getName();
    boolean foundIfFile = false;
    {
      // Figure out what directory the init file is in (if we even have one).
      // Also detect multiple if files and fail (see bug 41883).
      int ifDirIdx = 0;
      int idx = 0;
      String ifName = "BACKUP" + name + DiskInitFile.IF_FILE_EXT;
      for (DirectoryHolder dh : this.directories) {
        File f = new File(dh.getDir(), ifName);
        if (f.exists()) {
          if (foundIfFile) {
            throw new IllegalStateException(
                "Detected multiple disk store initialization files named \""
                    + ifName
                    + "\". This disk store directories must only contain one initialization file.");
          } else {
            foundIfFile = true;
            ifDirIdx = idx;
          }
        }
        idx++;
      }
      this.infoFileDirIndex = ifDirIdx;
    }
    // get a high level lock file first; if we can't get this then
    // this disk store is already open by someone else
    createLockFile(partialFileName);
    boolean finished = false;
    try {
      Map<File, DirectoryHolder> persistentBackupFiles = persistentOplogs
          .findFiles(partialFileName);
      {

        boolean backupFilesExist = !persistentBackupFiles.isEmpty();
        boolean ifRequired = backupFilesExist || isOffline();
        
        //If this offline disk-store is used by data extractor tool , we still need the cache around to process other diskstores
        //We wouldn't want to close the cache due to an IllegalStateException which can be caused to corruption of IF file. 
        
        this.initFile = new DiskInitFile(partialFileName, this, ifRequired,
            persistentBackupFiles.keySet());
        if (this.upgradeVersionOnly) {
          if (Version.CURRENT.compareTo(getRecoveredGFVersion()) <= 0 && !dataExtraction) {
            if (getCache() != null) {
              getCache().close();
            }
            throw new IllegalStateException(
                LocalizedStrings.DiskStoreAlreadyInVersion_0
                    .toLocalizedString(getRecoveredGFVersionName()));
            
          }
        } else {
          if (Version.GFE_70.compareTo(getRecoveredGFVersion()) > 0) {
            // TODO: In each new version, need to modify the highest version
            // that needs converstion.
            if (getCache() != null && !dataExtraction) {
              getCache().close();
            }
            throw new IllegalStateException(
                LocalizedStrings.DiskStoreStillAtVersion_0
                    .toLocalizedString(getRecoveredGFVersionName()));
          }
        }
      }

      {
        FilenameFilter overflowFileFilter = new DiskStoreFilter(OplogType.OVERFLOW, true,
            partialFileName);
        for (DirectoryHolder dh : this.directories) {
          File dir = dh.getDir();
          // delete all overflow files
          File[] files = FileUtil.listFiles(dir, overflowFileFilter);
          for (File file : files) {
            boolean deleted = file.delete();
            if (!deleted && file.exists()) {
              if (logger.fineEnabled()) {
                logger.fine("Could not delete file " + file);
              }
            }
          }
        }
      }

      persistentOplogs.createOplogs(needsOplogs, persistentBackupFiles);
      
      finished = true;

      // Log a message with the disk store id, indicating whether we recovered
      // or created thi disk store.
      if (foundIfFile) {
        logger.info(
            LocalizedStrings.DiskStoreImpl_RecoveredDiskStore_0_With_Id_1,
            new Object[] { getName(), getDiskStoreID() });
      } else {
        logger.info(
            LocalizedStrings.DiskStoreImpl_CreatedDiskStore_0_With_Id_1,
            new Object[] { getName(), getDiskStoreID() });
      }

    } finally {
      if (!finished) {
        closeLockFile();
        if (getDiskInitFile() != null) {
          getDiskInitFile().close();
        }
      }
    }
  }

  /**
   * The diskStats are at PR level.Hence if the region is a bucket region, the
   * stats should not be closed, but the figures of entriesInVM and
   * overflowToDisk contributed by that bucket need to be removed from the stats
   * .
   */
  private void statsClose() {
    this.stats.close();
    if (this.directories != null) {
      for (int i = 0; i < this.directories.length; i++) {
        this.directories[i].close();
      }
    }
  }

  void initializeIfNeeded(boolean initialRecovery) {
    if (!persistentOplogs.alreadyRecoveredOnce.get()) {
      recoverRegionsThatAreReady(initialRecovery);
    }
  }

  void doInitialRecovery() {
    initializeIfNeeded(true);
  }

  /**
   * Reads the oplogs files and loads them into regions that are ready to be
   * recovered.
   */
  public final void recoverRegionsThatAreReady(boolean initialRecovery) {
    persistentOplogs.recoverRegionsThatAreReady(initialRecovery);
  }

  public void scheduleIndexRecovery(Set<Oplog> allOplogs, boolean recreateIndexes) {
    // schedule index recovery atmost once
    if (markIndexRecoveryScheduled()) {
      IndexRecoveryTask task = new IndexRecoveryTask(allOplogs, recreateIndexes);
      // other disk store threads wait for this task, so use a different
      // thread pool for execution if possible (not in loner VM)
      ThreadPoolExecutor executor = getCache()
          .getWaitingThreadPoolOrDiskWritePool();
      executeDiskStoreTask(task, executor, true);
    }
  }

  void scheduleValueRecovery(Set<Oplog> oplogsNeedingValueRecovery,
      Map<Long, DiskRecoveryStore> recoveredStores) {
    ValueRecoveryTask task = new ValueRecoveryTask(oplogsNeedingValueRecovery,
        recoveredStores);
    synchronized (currentAsyncValueRecoveryMap) {
      DiskStoreImpl.this.currentAsyncValueRecoveryMap.putAll(recoveredStores);
    }
    executeDiskStoreTask(task);
  }

  /**
   * get the directory which has the info file
   * 
   * @return directory holder which has the info file
   */
  DirectoryHolder getInfoFileDir() {
    return this.directories[this.infoFileDirIndex];
  }

  /** For Testing * */
  // void addToOplogSet(int oplogID, File opFile, DirectoryHolder dirHolder) {
  // Oplog oplog = new Oplog(oplogID, this);
  // oplog.addRecoveredFile(opFile, dirHolder);
  // // @todo check callers to see if they need drf support
  // this.oplogSet.add(oplog);
  // }

  /** For Testing * */
  /**
   * returns the size of the biggest directory available to the region
   * 
   */
  public long getMaxDirSize() {
    return maxDirSize;
  }

  /**
   * 
   * @return boolean indicating whether the disk region compaction is on or not
   */
  boolean isCompactionEnabled() {
    return getAutoCompact();
  }

  public int getCompactionThreshold() {
    return this.compactionThreshold;
  }

  private final boolean isCompactionPossible;

  final boolean isCompactionPossible() {
    return this.isCompactionPossible;
  }

  void scheduleCompaction() {
    if (isCompactionEnabled() && !isOfflineCompacting()) {
      this.oplogCompactor.scheduleIfNeeded(getOplogToBeCompacted());
    }
  }

  /**
   * All the oplogs except the current one are destroyed.
   * 
   * @param rvv
   *          if not null, clear the region using a version vector Clearing with
   *          a version vector only removes entries less than the version
   *          vector, which allows for a consistent clear across members.
   */
  private void basicClear(LocalRegion region, DiskRegion dr,
      RegionVersionVector rvv) {
    if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
      CacheObserverHolder.getInstance().beforeDiskClear();
    }
    if (region != null) {
      clearAsyncQueue(region, false, rvv);
      // to fix bug 41770 need to wait for async flusher thread to finish
      // any work it is currently doing since it might be doing an operation on
      // this region.
      // If I call forceFlush here I might wait forever since I hold the
      // writelock
      // this preventing the async flush from finishing.
      // Can I set some state that will cause the flusher to ignore records
      // it currently has in it's hand for region?
      // Bug 41770 is caused by us doing a regionMap.clear at the end of this
      // method.
      // That causes any entry mod for this region that the async flusher has a
      // ref to
      // to end up being written as a create. We then end up writing another
      // create
      // since the first create is not in the actual region map.
      clearDrainList(region, rvv);
    }

    if (rvv == null) {
      // if we have an RVV, the stats are updated by AbstractRegionMap.clear
      // removing each entry.
      dr.statsClear(region);
    }

    if (dr.isBackup()) {
      PersistentOplogSet oplogSet = getPersistentOplogSet(dr);
      oplogSet.clear(dr, rvv);
    } else if (rvv == null) {
      // For an RVV based clear on an overflow region, freeing entries is
      // handled in
      // AbstractRegionMap.clear
      dr.freeAllEntriesOnDisk(region);
    }
  }

  

  /**
   * Removes anything found in the async queue for the given region
   * 
   * @param rvv
   */
  private void clearAsyncQueue(LocalRegion region, boolean needsWriteLock,
      RegionVersionVector rvv) {
    DiskRegion dr = region.getDiskRegion();
    if (needsWriteLock) {
      acquireWriteLock(dr);
    }
    try {
      // Now while holding the write lock remove any elements from the queue
      // for this region.
      Iterator<Object> it = this.asyncQueue.iterator();
      while (it.hasNext()) {
        Object o = it.next();
        if (o instanceof AsyncDiskEntry) {
          AsyncDiskEntry ade = (AsyncDiskEntry) o;
          if (shouldClear(region, rvv, ade)) {
            rmAsyncItem(o);
          }
        }
      }
    } finally {
      if (needsWriteLock) {
        releaseWriteLock(dr);
      }
    }
  }

  /**
   * Obtained and held by clear/destroyRegion/close. Also obtained when adding
   * to async queue.
   */
  private final Object lock = new Object();

  /**
   * It invokes appropriate methods of super & current class to clear the
   * Oplogs.
   * 
   * @param rvv
   *          if not null, clear the region using the version vector
   */
  void clear(LocalRegion region, DiskRegion dr, RegionVersionVector rvv) {
    acquireCompactorWriteLock();
    // get lock on sizeGuard first to avoid deadlock that occurred in bug #46133
    final ReentrantLock regionLock = region != null ? region.getSizeGuard()
        : null;
    if (regionLock != null) {
      regionLock.lock();
    }
    try {
        synchronized (this.lock) {
          // if (this.oplogCompactor != null) {
          // this.oplogCompactor.stopCompactor();
          // }
          acquireWriteLock(dr);
          try {
            if (dr.isRegionClosed()) {
              throw new RegionDestroyedException(
                  LocalizedStrings.DiskRegion_THE_DISKREGION_HAS_BEEN_CLOSED_OR_DESTROYED
                      .toLocalizedString(), dr.getName());
            }
            basicClear(region, dr, rvv);
            if (rvv == null && region != null) {
              // If we have no RVV, clear the region under lock
              region.txClearRegion();
              region.clearEntries(null);
              dr.incClearCount();
            }
          } finally {
            releaseWriteLock(dr);
          }
          // if (this.oplogCompactor != null) {
          // this.oplogCompactor.startCompactor();
          // scheduleCompaction();
          // }
        }
    } finally {
      if (regionLock != null) {
        regionLock.unlock();
      }
      releaseCompactorWriteLock();
    }

    if (rvv != null && region != null) {
      // If we have an RVV, we need to clear the region
      // without holding a lock.
      region.txClearRegion();
      region.clearEntries(rvv);
      // Note, do not increment the clear count in this case.
    }
  }

  private void releaseCompactorWriteLock() {
    compactorWriteLock.unlock();
  }

  private void acquireCompactorWriteLock() {
    compactorWriteLock.lock();
  }

  public void releaseCompactorReadLock() {
    compactorReadLock.unlock();
  }

  public void acquireCompactorReadLock() {
    compactorReadLock.lock();
  }

  private boolean closing = false;
  private boolean closed = false;

  boolean isClosing() {
    return this.closing;
  }

  boolean isClosed() {
    return this.closed;
  }
  
  void close() {
    close(false);
  }

  void close(boolean destroy) {
    this.closing = true;
    RuntimeException rte = null;
    try {
      // logger.info(LocalizedStrings.DEBUG, "DEBUG DiskStore close");
      // at this point all regions should already be closed
      try {
        closeCompactor(false);
      } catch (RuntimeException e) {
        rte = e;
      }
      if (!isOffline()) {
        try {
          // do this before write lock
          stopAsyncFlusher();
        } catch (RuntimeException e) {
          if (rte != null) {
            rte = e;
          }
        }
      }

      // Wakeup any threads waiting for the asnyc disk store recovery.
      synchronized (currentAsyncValueRecoveryMap) {
        currentAsyncValueRecoveryMap.notifyAll();
      }

      // don't block the shutdown hook
      if (Thread.currentThread() != InternalDistributedSystem.shutdownHook) {
        waitForBackgroundTasks();
      }
      try {
        overflowOplogs.closeOverflow();
      } catch (RuntimeException e) {
        if (rte != null) {
          rte = e;
        }
      }

      if ((!destroy && getDiskInitFile().hasLiveRegions()) || isValidating()) {
        RuntimeException exception = persistentOplogs.close();
        if(exception != null && rte != null) {
          rte = exception;
        }
        getDiskInitFile().close();
      } else {
        try {
          destroyAllOplogs();
        } catch (RuntimeException e) {
          if (rte != null) {
            rte = e;
          }
        }
        getDiskInitFile().close();
      }
      try {
        statsClose();
      } catch (RuntimeException e) {
        if (rte != null) {
          rte = e;
        }
      }
      
      closeLockFile();
      if (rte != null) {
        throw rte;
      }
    } finally {
      this.closed = true;
    }
  }

  boolean allowKrfCreation() {
    // Compactor might be stopped by cache-close. In that case, we should not create krf
    return this.oplogCompactor == null || this.oplogCompactor.keepCompactorRunning();
  }
  
  void closeCompactor(boolean isPrepare) {
    if (this.oplogCompactor == null) {
      return;
    }
    if (isPrepare) {
      acquireCompactorWriteLock();
    }
    try {
      synchronized (this.lock) {
        // final boolean orig =
        // this.oplogCompactor.compactionCompletionRequired;
        try {
          // to fix bug 40473 don't wait for the compactor to complete.
          // this.oplogCompactor.compactionCompletionRequired = true;
          this.oplogCompactor.stopCompactor();
        } catch (CancelException ignore) {
          // Asif:To fix Bug 39380 , ignore the cache closed exception here.
          // allow it to call super .close so that it would be able to close
          // the
          // oplogs
          // Though I do not think this exception will be thrown by
          // the stopCompactor. Still not taking chance and ignoring it

        } catch (RuntimeException e) {
          if (logger.warningEnabled()) {
            logger
            .warning(
                LocalizedStrings.DiskRegion_COMPLEXDISKREGION_CLOSE_EXCEPTION_IN_STOPPING_COMPACTOR,
                e);
          }
          throw e;
          // } finally {
            // this.oplogCompactor.compactionCompletionRequired = orig;
        }
      }
    } finally {
      if (isPrepare) {
        releaseCompactorWriteLock();
      }
    }
  }

  private void basicClose(LocalRegion region, DiskRegion dr, boolean closeDataOnly) {
    if (dr.isBackup()) {
      if (region != null) {
        region.closeEntries();
      }
      // logger.info(LocalizedStrings.DEBUG, "DEBUG basicClose dr=" +
      // dr.getName() + " id=" + dr.getId());
      if(!closeDataOnly) {
        getDiskInitFile().closeRegion(dr);
      }
      // call close(dr) on each oplog
      PersistentOplogSet oplogSet = getPersistentOplogSet(dr);
      oplogSet.basicClose(dr);
    } else {
      if (region != null) {
        // OVERFLOW ONLY
        clearAsyncQueue(region, true, null); // no need to try to write these to
                                             // disk any longer
        dr.freeAllEntriesOnDisk(region);
        region.closeEntries();
        this.overflowMap.remove(dr);
      }
    }
  }

  /**
   * Called before LocalRegion clears the contents of its entries map
   */
  void prepareForClose(LocalRegion region, DiskRegion dr) {
    // logger.info(LocalizedStrings.DEBUG, "DEBUG prepareForClose dr=" +
    // dr.getName());
    if (dr.isBackup()) {
      // logger.info(LocalizedStrings.DEBUG, "DEBUG prepareForClose dr=" +
      // dr.getName());
      // Need to flush any async ops done on dr.
      // The easiest way to do this is to flush the entire async queue.
      forceFlush();
    }
  }

  public void prepareForClose() {
    forceFlush();
    persistentOplogs.prepareForClose();
    closeCompactor(true);
  }

  private void onClose() {
    InternalResourceManager irm = this.cache.getResourceManager(false);
    if (irm != null) {
      irm.removeResourceListener(ResourceType.HEAP_MEMORY, this);
    }
    this.cache.removeDiskStore(this);
  }

  void close(LocalRegion region, DiskRegion dr, boolean closeDataOnly) {
    // CancelCriterion stopper = dr.getOwner().getCancelCriterion();

    if (logger.fineEnabled()) {
      logger
          .fine("DiskRegion::close:Attempting to close DiskRegion. Region name ="
              + dr.getName());
    }

    boolean closeDiskStore = false;
    acquireCompactorWriteLock();
    // Fix for 46284 - we must obtain the size guard lock before getting the
    // disk store lock
    final ReentrantLock regionLock = region != null ? region.getSizeGuard()
        : null;
    if (regionLock != null) {
      regionLock.lock();
    }
    try {
        synchronized (this.lock) {
          // Fix 45104, wait here for addAsyncItem to finish adding into queue
          // prepareForClose() should be out of synchronized (this.lock) to avoid deadlock
          if (dr.isRegionClosed()) {
            return;
          }
        }
        prepareForClose(region, dr);
        synchronized (this.lock) {
          boolean gotLock = false;
          try {
            acquireWriteLock(dr);
            if(!closeDataOnly) {
              dr.setRegionClosed(true);
            }
            gotLock = true;
          } catch (CancelException e) {
            synchronized (this.closeRegionGuard) {
              if (!dr.isRegionClosed()) {
                if(!closeDataOnly) {
                  dr.setRegionClosed(true);
                }
                // Asif: I am quite sure that it should also be Ok if instead
                // while it is a If Check below. Because if acquireReadLock
                // thread
                // has acquired thelock, it is bound to see the isRegionClose as
                // true
                // and so will realse teh lock causing decrement to zeo , before
                // releasing the closeRegionGuard. But still...not to take any
                // chance

                while (this.entryOpsCount.get() > 0) {
                  try {
                    this.closeRegionGuard.wait(20000);
                  } catch (InterruptedException ie) {
                    // Exit without closing the region, do not know what else
                    // can be done
                    Thread.currentThread().interrupt();
                    dr.setRegionClosed(false);
                    return;
                  }
                }

              } else {
                return;
              }
            }

          }

          try {
            if (logger.fineEnabled()) {
              logger
                  .fine("DiskRegion::close:Before invoking basic Close. Region name ="
                      + dr.getName());
            }
            basicClose(region, dr, closeDataOnly);
          } finally {
            if (gotLock) {
              releaseWriteLock(dr);
            }
          }
        }

      if (getOwnedByRegion() && !closeDataOnly) {
        // logger.info(LocalizedStrings.DEBUG, "DEBUG: ds=" + getName()
        // + "close ownCount=" + getOwnCount(), new RuntimeException("STACK"));
        if (this.ownCount.decrementAndGet() <= 0) {
          closeDiskStore = true;
        }
      }
    } finally {
      if (regionLock != null) {
        regionLock.unlock();
      }
      releaseCompactorWriteLock();
    }

    // Fix for 44538 - close the disk store without holding
    // the compactor write lock.
    if (closeDiskStore) {
      onClose();
      close();
    }
  }

  /**
   * stops the compactor outside the write lock. Once stopped then it proceeds
   * to destroy the current & old oplogs
   * 
   * @param dr
   */
  void beginDestroyRegion(LocalRegion region, DiskRegion dr) {
    // logger.info(LocalizedStrings.DEBUG, "DEBUG beginDestroyRegion dr=" +
    // dr.getName());
    if (dr.isBackup()) {
      getDiskInitFile().beginDestroyRegion(dr);
    }
  }

  private final AtomicInteger backgroundTasks = new AtomicInteger();

  int incBackgroundTasks() {
    getCache().getCachePerfStats().incDiskTasksWaiting();
    int v = this.backgroundTasks.incrementAndGet();
    // logger.info(LocalizedStrings.DEBUG, "DEBUG: incBackgroundTasks " + v, new
    // Exception());
    return v;
  }

  void decBackgroundTasks() {
    int v = this.backgroundTasks.decrementAndGet();
    // logger.info(LocalizedStrings.DEBUG, "DEBUG: decBackgroundTasks " + v, new
    // Exception());
    if (v == 0) {
      synchronized (this.backgroundTasks) {
        this.backgroundTasks.notifyAll();
      }
    }
    getCache().getCachePerfStats().decDiskTasksWaiting();
  }

  public void waitForBackgroundTasks() {
    if (isBackgroundTaskThread()) {
      return; // fixes bug 42775
    }
    if (this.backgroundTasks.get() > 0) {
      boolean interrupted = Thread.interrupted();
      try {
        synchronized (this.backgroundTasks) {
          while (this.backgroundTasks.get() > 0) {
            try {
              this.backgroundTasks.wait(500L);
            } catch (InterruptedException ex) {
              interrupted = true;
            }
          }
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  boolean basicForceCompaction(DiskRegion dr) {
    PersistentOplogSet oplogSet = persistentOplogs;
    // see if the current active oplog is compactable; if so
    {
      Oplog active = oplogSet.getChild();
      if (active != null) {
        if (active.hadLiveEntries() && active.needsCompaction()) {
          active.forceRolling(dr, false);
        }
      }
    }

    //Compact the oplogs
    CompactableOplog[] oplogs = getOplogsToBeCompacted(true/* fixes 41143 */);
    // schedule a compaction if at this point there are oplogs to be compacted
    if (oplogs != null) {
      if (this.oplogCompactor != null) {
        if (this.oplogCompactor.scheduleIfNeeded(oplogs)) {
          this.oplogCompactor.waitForRunToComplete();
        } else {
          oplogs = null;
          // logger.info(LocalizedStrings.DEBUG, "DEBUG:  todo ");
          // @todo darrel: still need to schedule oplogs and wait for them to
          // compact.
        }
      }
    }
    return oplogs != null;
  }

  /**
   * Destroy the given region
   */
  private void basicDestroy(LocalRegion region, DiskRegion dr) {
    if (dr.isBackup()) {
      if (region != null) {
        region.closeEntries();
      }
      PersistentOplogSet oplogSet = getPersistentOplogSet(dr);
      oplogSet.basicDestroy(dr);
    } else {
      dr.freeAllEntriesOnDisk(region);
      if (region != null) {
        region.closeEntries();
      }
    }
  }

  /**
   * Destroy all the oplogs
   * 
   */
  private void destroyAllOplogs() {
    persistentOplogs.destroyAllOplogs();
    
    // Need to also remove all oplogs that logically belong to this DiskStore
    // even if we were not using them.
    { // delete all overflow oplog files
      FilenameFilter overflowFileFilter = new DiskStoreFilter(OplogType.OVERFLOW, true,
          getName());
      deleteFiles(overflowFileFilter);
    }
    { // delete all backup oplog files
      FilenameFilter backupFileFilter = new DiskStoreFilter(OplogType.BACKUP, true,
          getName());
      deleteFiles(backupFileFilter);
    }
  }

  private void deleteFiles(FilenameFilter overflowFileFilter) {
    for (int i = 0; i < this.directories.length; i++) {
      File dir = this.directories[i].getDir();
      File[] files = FileUtil.listFiles(dir, overflowFileFilter);
      for (File file : files) {
        boolean deleted = file.delete();
        if (!deleted && file.exists()) {
          if (logger.fineEnabled()) {
            logger.fine("Could not delete file " + file);
          }
        }
      }
    }
  }

  public void destroy() {
    Set<String> liveRegions = new TreeSet<String>();
    for(AbstractDiskRegion dr : getDiskRegions()) {
      liveRegions.add(dr.getName());
    }
    for(AbstractDiskRegion dr : overflowMap) {
      liveRegions.add(dr.getName());
    }
    if(!liveRegions.isEmpty()) {
      throw new IllegalStateException("Disk store is currently in use by these regions " + liveRegions);
    }
    close(true);
    getDiskInitFile().destroy();
    onClose();
  }

  /**
   * gets the available oplogs to be compacted from the LinkedHashMap
   * 
   * @return Oplog[] returns the array of oplogs to be compacted if present else
   *         returns null
   */
  CompactableOplog[] getOplogToBeCompacted() {
    return getOplogsToBeCompacted(false);
  }

  /**
   * Test hook to see how many oplogs are available for compaction
   */
  public int numCompactableOplogs() {
    CompactableOplog[] oplogs = getOplogsToBeCompacted(true);
    if (oplogs == null) {
      return 0;
    } else {
      return oplogs.length;
    }

  }

  private CompactableOplog[] getOplogsToBeCompacted(boolean all) {
    ArrayList<CompactableOplog> l = new ArrayList<CompactableOplog>();
      
    // logger.info(LocalizedStrings.DEBUG, "DEBUG getOplogToBeCompacted=" +
    // this.oplogIdToOplog);
    int max = Integer.MAX_VALUE;
    // logger.info(LocalizedStrings.DEBUG, "DEBUG:  num=" + num);
    if (!all && max > MAX_OPLOGS_PER_COMPACTION
        && MAX_OPLOGS_PER_COMPACTION > 0) {
      max = MAX_OPLOGS_PER_COMPACTION;
    }
    persistentOplogs.getCompactableOplogs(l, max);

    // Note this always puts overflow oplogs on the end of the list.
    // They may get starved.
    overflowOplogs.getCompactableOplogs(l, max);
    
    if(l.isEmpty()) {
      return null;
    }
      
    return l.toArray(new CompactableOplog[0]);
  }

  /**
   * Returns the dir name used to back up this DiskStore's directories under.
   * The name is a concatenation of the disk store name and id.
   */
  public String getBackupDirName() {
    String name = getName();
    
    if(name == null) {
      name = GemFireCacheImpl.DEFAULT_DS_NAME;
    }
    
    return (name + "_" + getDiskStoreID().toString());
  }
  
  /**
   * Filters and returns the current set of oplogs that aren't already in the
   * baseline for incremental backup
   * 
   * @param baselineInspector
   *          the inspector for the previous backup.
   * @param baselineCopyMap
   *          this will be populated with baseline oplogs Files that will be
   *          used in the restore script.
   * @return an map of Oplogs to be copied for an incremental backup. The map is from
   * the oplog to the set of files that still need to be backed up for that oplog
   * @throws IOException
   */
  private Map<Oplog, Set<File>> filterBaselineOplogs(BackupInspector baselineInspector,
      Map<File, File> baselineCopyMap) throws IOException {
    File baselineDir = new File(baselineInspector.getBackupDir(),
        BackupManager.DATA_STORES);
    baselineDir = new File(baselineDir, getBackupDirName());

    // Find all of the member's diskstore oplogs in the member's baseline
    // diskstore directory structure (*.crf,*.krf,*.drf)
    List<File> baselineOplogFiles = FileUtil.findAll(baselineDir,
        ".*\\.(idx)?[kdc]rf$");

    // Our list of oplogs to copy (those not already in the baseline)
    Map<Oplog, Set<File>> oplogList = new LinkedHashMap<Oplog, Set<File>>();

    // Total list of member oplogs
    Map<Oplog, Set<File>> allOplogs = getAllOplogsForBackup();

    /*
     * Loop through operation logs and see if they are already part of the
     * baseline backup.
     */
    for (Map.Entry<Oplog, Set<File>> entry: allOplogs.entrySet()) {
      Oplog log = entry.getKey();
      Set<File> filesNeedingBackup = entry.getValue();
      // See if they are backed up in the current baseline
      Map<File, File> oplogMap = log.mapBaseline(baselineOplogFiles, filesNeedingBackup);

      // No? Then see if they were backed up in previous baselines
      if (!filesNeedingBackup.isEmpty() && baselineInspector.isIncremental()) {
        Set<String> matchingOplogs = log
            .gatherMatchingOplogFiles(baselineInspector
                .getIncrementalOplogFileNames(), filesNeedingBackup);
        if (!matchingOplogs.isEmpty()) {
          for (String matchingOplog : matchingOplogs) {
            oplogMap.put(
                new File(baselineInspector
                    .getCopyFromForOplogFile(matchingOplog)), new File(
                    baselineInspector.getCopyToForOplogFile(matchingOplog)));
          }
        }
      }

      if (!filesNeedingBackup.isEmpty()) {
        /*
         * These are fresh operation log files so lets back them up.
         */
        oplogList.put(log, filesNeedingBackup);
      }
      /*
       * These have been backed up before so lets just add their entries from
       * the previous backup or restore script into the current one.
       */
      baselineCopyMap.putAll(oplogMap);
    }

    return oplogList;
  }

  /**
   * Get all of the oplogs
   */
  private Map<Oplog, Set<File>> getAllOplogsForBackup() {
    Oplog[] oplogs = persistentOplogs.getAllOplogs();
    Map<Oplog, Set<File>> results = new LinkedHashMap<Oplog, Set<File>>();
    for(Oplog oplog: oplogs) {
      results.put(oplog, oplog.getAllFiles());
    }
    
    return results;
  }

  // @todo perhaps a better thing for the tests would be to give them a listener
  //       hook that notifies them every time an oplog is created.
  /**
   * Wait before executing an async disk task (e.g. compaction, krf creation).
   * Returns true if {@link #endAsyncDiskTask()} should be invoked and false
   * otherwise.
   * Used by tests to confirm stat size.
   * 
   */
  final boolean waitBeforeAsyncDiskTask() {
    if (isOffline()) {
      return false;
    }
    final GemFireCacheImpl.StaticSystemCallbacks sysCb = GemFireCacheImpl
        .getInternalProductCallbacks();
    if (sysCb != null) {
      final long waitMillis = 500L;
      while (!sysCb.waitBeforeAsyncDiskTask(waitMillis, this)) {
        if (DiskStoreImpl.this.isClosing()) {
          // break early if disk store is closing
          return false;
        }
      }
      if (logger != null && logger.fineEnabled()) {
        logger.fine("Proceeding after waiting for basic "
            + "system initialization to complete");
      }
      return true;
    }
    return false;
  }

  final void endAsyncDiskTask() {
    if (isOffline()) {
      return;
    }
    final GemFireCacheImpl.StaticSystemCallbacks sysCb = GemFireCacheImpl
        .getInternalProductCallbacks();
    if (sysCb != null) {
      sysCb.endAsyncDiskTask(this);
    }
  }

  // @todo perhaps a better thing for the tests would be to give them a listener
  // hook that notifies them every time an oplog is created.
  /**
   * Used by tests to confirm stat size.
   */
  final AtomicLong undeletedOplogSize = new AtomicLong();

  /**
   * Compacts oplogs
   * 
   * @author Mitul Bid
   * @author Asif
   * @since 5.1
   * 
   */
  class OplogCompactor implements Runnable {
    /** boolean for the thread to continue compaction* */
    private volatile boolean compactorEnabled;
    private volatile boolean scheduled;
    private CompactableOplog[] scheduledOplogs;
    /**
     * used to keep track of the Thread currently invoking run on this compactor
     */
    private volatile Thread me;

    // private LogWriterI18n logger = null;
    // Boolean which decides if the compactor can terminate early i.e midway
    // between compaction.
    // If this boolean is true ,( default is false), then the compactor thread
    // if entered the
    // compaction phase will exit only after it has compacted the oplogs & also
    // deleted the compacted
    // oplogs

    private final boolean compactionCompletionRequired;

    OplogCompactor() {
      this.compactionCompletionRequired = sysProps.getBoolean(
	  COMPLETE_COMPACTION_BEFORE_TERMINATION_PROPERTY_BASE_NAME, false);
    }

    /**
     * Creates a new compactor and starts a new thread
     * 
     * private OplogCompactor() { logger =
     * DiskRegion.this.owner.getCache().getLogger(); }
     */

    /** Creates a new thread and starts the thread* */
    private void startCompactor() {
      this.compactorEnabled = true;
    }

    /**
     * Stops the thread from compaction and the compactor thread joins with the
     * calling thread
     */
    private void stopCompactor() {
      synchronized(this) {
        if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
          CacheObserverHolder.getInstance().beforeStoppingCompactor();
        }
        this.compactorEnabled = false;
        if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
          CacheObserverHolder.getInstance().afterSignallingCompactor();
        }
      }
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance().afterStoppingCompactor();
      }
    }

    /**
     * @return true if compaction done; false if it was not
     */
    private synchronized boolean scheduleIfNeeded(CompactableOplog[] opLogs) {
      if (!this.scheduled) {
        return schedule(opLogs);
      } else {
        return false;
      }
    }

    /**
     * @return true if compaction done; false if it was not
     */
    private synchronized boolean schedule(CompactableOplog[] opLogs) {
      assert !this.scheduled;
      if (!this.compactorEnabled)
        return false;
      if (opLogs != null) {
        for (int i = 0; i < opLogs.length; i++) {
          // logger.info(LocalizedStrings.DEBUG,
          // "schedule oplog#" + opLogs[i].getOplogId(),
          // new RuntimeException("STACK"));
          opLogs[i].prepareForCompact();
        }
        this.scheduled = true;
        this.scheduledOplogs = opLogs;
        boolean result = executeDiskStoreTask(this);
        if (!result) {
          reschedule(false, new CompactableOplog[0]);
          return false;
        } else {
          return true;
        }
      } else {
        return false;
      }
    }

    /**
     * A non-backup just needs values that are written to one of the oplogs
     * being compacted that are still alive (have not been deleted or modified
     * in a future oplog) to be copied forward to the current active oplog
     */
    private boolean compact() {
      int totalCount = 0;
      long compactionStart = 0;
      long start = 0;
      // wait for basic GemFireXD initialization to complete first
      boolean signalEnd = waitBeforeAsyncDiskTask();
      try {
        // return if diskstore is closing
        if (DiskStoreImpl.this.isClosing()) {
          // pretend success; higher level will check for this anyway
          // and end at some point
          return true;
        }
        CompactableOplog[] oplogs = this.scheduledOplogs;
        // continue if nothing to be compacted
        if (oplogs.length == 0) {
          return true;
        }
        compactionStart = getStats().startCompaction();
        start = NanoTimer.getTime();
        // logger.info(LocalizedStrings.DEBUG, "DEBUG keepCompactorRunning="
        // + keepCompactorRunning());
        for (int i = 0; i < oplogs.length && keepCompactorRunning() /*
                                                                     * @todo &&
                                                                     * !owner.
                                                                     * isDestroyed
                                                                     */; i++) {
          int compacted = oplogs[i].compact(this);
          totalCount += compacted;
          if (DiskStoreImpl.this.testoplogcompact != null) {
            if (compacted > 0 && (oplogs[i] == DiskStoreImpl.this.testoplogcompact)) {
              DiskStoreImpl.this.testOplogCompacted = true;
            }
          }
        }

        // TODO:Asif : DiskRegion: How do we tackle
      } finally {
        if (compactionStart != 0) {
          getStats().endCompaction(compactionStart);
        }
        if (signalEnd) {
          endAsyncDiskTask();
        }
      }
      long endTime = NanoTimer.getTime();
      logger.info(LocalizedStrings.DiskRegion_COMPACTION_SUMMARY, new Object[] {
          totalCount, ((endTime - start) / 1000000) });
      return true /* @todo !owner.isDestroyed */;
    }
    
    private boolean isClosing() {
      if (getCache().isClosed()) {
        return true;
      }
      CancelCriterion stopper = getCache().getCancelCriterion();
      if (stopper.cancelInProgress() != null) {
        return true;
      }
      return false;
    }

    /**
     * Just do compaction and then check to see if another needs to be done and
     * if so schedule it. Asif:The compactor thread checks for an oplog in the
     * LinkedHasMap in a synchronization on the oplogIdToOplog object. This will
     * ensure that an addition of an Oplog to the Map does not get missed.
     * Notifications need not be sent if the thread is already compaction
     */
    public void run() {
      getCache().getCachePerfStats().decDiskTasksWaiting();
      if (!this.scheduled)
        return;
      boolean compactedSuccessfully = false;
      try {
        SystemFailure.checkFailure();
        if (isClosing()) {
          return;
        }
        if (!this.compactorEnabled)
          return;
        final CompactableOplog[] oplogs = this.scheduledOplogs;
        this.me = Thread.currentThread();
        try {
          // set our thread's name
          String tName = "OplogCompactor " + getName() + " for oplog "
              + oplogs[0].toString();
          Thread.currentThread().setName(tName);

          StringBuilder buffer = new StringBuilder();
          for (int j = 0; j < oplogs.length; ++j) {
            buffer.append(oplogs[j].toString());
            if (j + 1 < oplogs.length) {
              buffer.append(", ");
            }
          }
          String ids = buffer.toString();
          logger.info(LocalizedStrings.DiskRegion_COMPACTION_OPLOGIDS,
              new Object[] { getName(), ids });
          if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
            CacheObserverHolder.getInstance().beforeGoingToCompact();
          }
          compactedSuccessfully = compact();
          if (compactedSuccessfully) {
            // logger.info(LocalizedStrings.DiskRegion_COMPACTION_SUCCESS,
            // new Object[] {getName(), ids});
            if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
              CacheObserverHolder.getInstance().afterHavingCompacted();
            }
          } else {
            logger.warning(LocalizedStrings.DiskRegion_COMPACTION_FAILURE,
                new Object[] { getName(), ids });
          }
        } catch (DiskAccessException dae) {
          throw dae;
        } catch (KillCompactorException ex) {
          if (logger.fineEnabled()) {
            logger.fine("compactor thread terminated by test");
          }
          throw ex;
        } finally {
          if (compactedSuccessfully) {
            this.me.setName("Idle OplogCompactor");
          }
          this.me = null;
        }
      } catch (CancelException ignore) {
        // if cache is closed, just about the compaction
      }
      finally {
        reschedule(compactedSuccessfully, scheduledOplogs);
      }
    }

    synchronized void waitForRunToComplete() {
      if (this.me == Thread.currentThread()) {
        // no need to wait since we are the compactor to fix bug 40630
        return;
      }
      while (this.scheduled) {
        try {
          wait();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private synchronized void reschedule(boolean success, CompactableOplog[] previousList) {
      this.scheduled = false;
      this.scheduledOplogs = null;
      notifyAll();
      if (!success)
        return;
      if (!this.compactorEnabled)
        return;
      if (isClosing())
        return;
      SystemFailure.checkFailure();
      //TODO griddb - is sync this necessary? For what?
      //synchronized (DiskStoreImpl.this.oplogIdToOplog) {
        if (this.compactorEnabled) {
          if (isCompactionEnabled()) {
            CompactableOplog[] newList = getOplogToBeCompacted();
            if(Arrays.equals(newList, previousList)) {
              //If the list of oplogs to be compacted didn't change,
              //don't loop and compact again.
              logger.warning(LocalizedStrings.DiskStoreImpl_PREVENTING_COMPACTION_LOOP, Arrays.asList(newList));
              return;
            }
            schedule(newList);
          }
        }
      //}
    }

    boolean keepCompactorRunning() {
      return this.compactorEnabled || this.compactionCompletionRequired;
    }
  }
  
  // for test. test needs to wait after successful compaction happened
  // for the given oplog set via this setter method below.
  private Oplog testoplogcompact = null;
  public void TEST_oplogCompact(Oplog oplog) {
   this.testoplogcompact = oplog; 
  }

  private boolean testOplogCompacted;
  public boolean isTestOplogCompacted() {
    return this.testOplogCompacted;
  }
  /**
   * Used by unit tests to kill the compactor operation.
   */
  public static class KillCompactorException extends RuntimeException {
  }

  public DiskInitFile getDiskInitFile() {
    return this.initFile;
  }

  public void memberOffline(DiskRegionView dr, PersistentMemberID persistentID) {
    if (this.initFile != null) {
      this.initFile.addOfflinePMID(dr, persistentID);
    }
  }

  public void memberOfflineAndEqual(DiskRegionView dr,
      PersistentMemberID persistentID) {
    if (this.initFile != null) {
      this.initFile.addOfflineAndEqualPMID(dr, persistentID);
    }
  }

  public void memberOnline(DiskRegionView dr, PersistentMemberID persistentID) {
    if (this.initFile != null) {
      this.initFile.addOnlinePMID(dr, persistentID);
    }
  }

  public void memberRemoved(DiskRegionView dr, PersistentMemberID persistentID) {
    if (this.initFile != null) {
      this.initFile.rmPMID(dr, persistentID);
    }
  }

  public void memberRevoked(PersistentMemberPattern revokedPattern) {
    if (this.initFile != null) {
      this.initFile.revokeMember(revokedPattern);
    }
  }

  public void setInitializing(DiskRegionView dr, PersistentMemberID newId) {
    if (this.initFile != null) {
      this.initFile.addMyInitializingPMID(dr, newId);
    }
  }

  public void setInitialized(DiskRegionView dr) {
    if (this.initFile != null) {
      this.initFile.markInitialized(dr);
    }
  }

  public Set<PersistentMemberPattern> getRevokedMembers() {
    if (this.initFile != null) {
      return this.initFile.getRevokedIDs();
    }
    return Collections.emptySet();
  }

  public void endDestroyRegion(LocalRegion region, DiskRegion dr) {
    // logger.info(LocalizedStrings.DEBUG, "DEBUG endDestroyRegion dr=" +
    // dr.getName());
    // CancelCriterion stopper = dr.getOwner().getCancelCriterion();
    // Fix for 46284 - we must obtain the size guard lock before getting the
    // disk store lock
    final ReentrantLock regionLock = region != null ? region.getSizeGuard()
        : null;
    if (regionLock != null) {
      regionLock.lock();
    }
    try {
      synchronized (this.lock) {
        if (dr.isRegionClosed()) {
          return;
        }
        // // Stop the compactor if running, without taking lock.
        // if (this.oplogCompactor != null) {
        // try {
        // this.oplogCompactor.stopCompactor();
        // }
        // catch (CancelException ignore) {
        // // Asif:To fix Bug 39380 , ignore the cache closed exception here.
        // // allow it to call super .close so that it would be able to close
        // the
        // // oplogs
        // // Though I do not think this exception will be thrown by
        // // the stopCompactor. Still not taking chance and ignoring it

        // }
        // }
        // // if (!isSync()) {
        // stopAsyncFlusher(true); // do this before writeLock
        // // }
        boolean gotLock = false;
        try {
          try {
            acquireWriteLock(dr);
            gotLock = true;
          } catch (CancelException e) {
            // see workaround below.
          }

          if (!gotLock) { // workaround for bug39380
            // Allow only one thread to proceed
            synchronized (this.closeRegionGuard) {
              if (dr.isRegionClosed()) {
                return;
              }

              dr.setRegionClosed(true);
              // Asif: I am quite sure that it should also be Ok if instead
              // while it is a If Check below. Because if acquireReadLock thread
              // has acquired the lock, it is bound to see the isRegionClose as
              // true
              // and so will release the lock causing decrement to zeo , before
              // releasing the closeRegionGuard. But still...not to take any
              // chance
              final int loopCount = 10;
              for (int i = 0; i < loopCount; i++) {
                if (this.entryOpsCount.get() == 0) {
                  break;
                }
                boolean interrupted = Thread.interrupted();
                try {
                  this.closeRegionGuard.wait(1000);
                } catch (InterruptedException ie) {
                  interrupted = true;
                } finally {
                  if (interrupted) {
                    Thread.currentThread().interrupt();
                  }
                }
              } // for
              if (this.entryOpsCount.get() > 0) {
                logger
                    .warning(
                        LocalizedStrings.DisKRegion_OUTSTANDING_OPS_REMAIN_AFTER_0_SECONDS_FOR_DISK_REGION_1,
                        new Object[] { Integer.valueOf(loopCount), dr.getName() });

                for (;;) {
                  if (this.entryOpsCount.get() == 0) {
                    break;
                  }
                  boolean interrupted = Thread.interrupted();
                  try {
                    this.closeRegionGuard.wait(1000);
                  } catch (InterruptedException ie) {
                    interrupted = true;
                  } finally {
                    if (interrupted) {
                      Thread.currentThread().interrupt();
                    }
                  }
                } // for
                logger
                    .info(
                        LocalizedStrings.DisKRegion_OUTSTANDING_OPS_CLEARED_FOR_DISK_REGION_0,
                        dr.getName());
              }
            } // synchronized
          }

          dr.setRegionClosed(true);
          basicDestroy(region, dr);
        } finally {
          if (gotLock) {
            releaseWriteLock(dr);
          }
        }
      }
    } finally {
      if (regionLock != null) {
        regionLock.unlock();
      }
    }
    if (this.initFile != null && dr.isBackup()) {
      this.initFile.endDestroyRegion(dr);
    } else {
      rmById(dr.getId());
      this.overflowMap.remove(dr);
    }
    if (getOwnedByRegion()) {
      // logger.info(LocalizedStrings.DEBUG, "DEBUG: ds=" + getName()
      // + "destroy ownCount=" + getOwnCount());
      if (this.ownCount.decrementAndGet() <= 0) {
        destroy();
      }
    }
  }

  public void beginDestroyDataStorage(DiskRegion dr) {
    if (this.initFile != null && dr.isBackup()/* fixes bug 41389 */) {
      this.initFile.beginDestroyDataStorage(dr);
    }
  }

  public void endDestroyDataStorage(LocalRegion region, DiskRegion dr) {
    // logger.info(LocalizedStrings.DEBUG, "DEBUG endPartialDestroyRegion dr=" +
    // dr.getName());
    try {
      clear(region, dr, null);
      dr.resetRVV();
      dr.setRVVTrusted(false);
      dr.writeRVV(null, null); // just persist the empty rvv with trust=false
    } catch (RegionDestroyedException rde) {
      // ignore a RegionDestroyedException at this stage
    }
    if (this.initFile != null && dr.isBackup()) {
      this.initFile.endDestroyDataStorage(dr);
    }
  }

  public PersistentMemberID generatePersistentID(DiskRegionView dr) {
    File firstDir = getInfoFileDir().getDir();
    InternalDistributedSystem ids = getCache().getDistributedSystem();
    InternalDistributedMember memberId = ids.getDistributionManager()
        .getDistributionManagerId();
    
    //NOTE - do NOT use DM.cacheTimeMillis here. See bug #49920
    long timestamp = System.currentTimeMillis();
    PersistentMemberID id = new PersistentMemberID(getDiskStoreID(), memberId.getIpAddress(),
        firstDir.getAbsolutePath(), memberId.getName(),
        timestamp, (short) 0);
    return id;
  }

  public PersistentID getPersistentID() {
    InetAddress host = cache.getDistributedSystem().getDistributedMember()
        .getIpAddress();
    String dir = getDiskDirs()[0].getAbsolutePath();
    return new PersistentMemberPattern(host, dir, this.diskStoreID.toUUID(), 0);
  }

  // test hook
  public void forceIFCompaction() {
    if (this.initFile != null) {
      this.initFile.forceCompaction();
    }
  }

  // @todo DiskStore it
  /**
   * Need a stopper that only triggers if this DiskRegion has been closed. If we
   * use the LocalRegion's Stopper then our async writer will not be able to
   * finish flushing on a cache close.
   */
  private class Stopper extends CancelCriterion {
    @Override
    public String cancelInProgress() {
      if (isClosed()) {
        return "The disk store is closed.";
      } else {
        return null;
      }
    }

    @Override
    public RuntimeException generateCancelledException(Throwable e) {
      if (isClosed()) {
        return new CacheClosedException("The disk store is closed", e);
      } else {
        return null;
      }
    }

  }

  private final CancelCriterion stopper = new Stopper();

  public CancelCriterion getCancelCriterion() {
    return this.stopper;
  }

  /**
   * Called when we are doing recovery and we find a new id.
   */
  void recoverRegionId(long drId) {
    long newVal = drId + 1;
    if (this.regionIdCtr.get() < newVal) { // fixes bug 41421
      this.regionIdCtr.set(newVal);
    }
  }

  /**
   * Called when creating a new disk region (not a recovered one).
   */
  long generateRegionId() {
    long result;
    do {
      result = this.regionIdCtr.getAndIncrement();
    } while (result <= MAX_RESERVED_DRID && result >= MIN_RESERVED_DRID);
    return result;
  }

  /**
   * Returns a set of the disk regions that are using this disk store. Note that
   * this set is read only and live (its contents may change if the regions
   * using this disk store changes).
   */
  public Collection<DiskRegion> getDiskRegions() {
    return Collections.unmodifiableCollection(this.drMap.values());
  }

  /**
   * This method is slow and should be optimized if used for anything important.
   * At this time it was added to do some internal assertions that have since
   * been removed.
   */
  public DiskRegion getByName(String name) {
    for (DiskRegion dr : getDiskRegions()) {
      if (dr.getName().equals(name)) {
        return dr;
      }
    }
    return null;
  }

  void addDiskRegion(DiskRegion dr) {
    if (dr.isBackup()) {
      PersistentOplogSet oplogSet = getPersistentOplogSet(dr);
      if(!isOffline()) {
        oplogSet.initChild();
      }
      
      DiskRegion old = this.drMap.putIfAbsent(dr.getId(), dr);
      if (old != null) {
        throw new IllegalStateException("DiskRegion already exists with id "
            + dr.getId() + " and name " + old.getName());
      }
      getDiskInitFile().createRegion(dr);
    } else {
      this.overflowMap.add(dr);
    }
    if (getOwnedByRegion()) {
      this.ownCount.incrementAndGet();
      // logger.info(LocalizedStrings.DEBUG, "DEBUG: ds=" + getName()
      // + "addDiskRegion ownCount=" + getOwnCount(), new
      // RuntimeException("STACK"));
    }
  }

  void addPersistentPR(String name, PRPersistentConfig config) {
    getDiskInitFile().createPersistentPR(name, config);
  }

  void removePersistentPR(String name) {
    if(isClosed() && getOwnedByRegion()) {
      //A region owned disk store will destroy
      //itself when all buckets are removed, resulting
      //in an exception when this method is called.
      //Do nothing if the disk store is already
      //closed
      return;
    }
    getDiskInitFile().destroyPersistentPR(name);
  }

  PRPersistentConfig getPersistentPRConfig(String name) {
    return getDiskInitFile().getPersistentPR(name);
  }

  Map<String, PRPersistentConfig> getAllPRs() {
    return getDiskInitFile().getAllPRs();
  }

  DiskRegion getById(Long regionId) {
    return this.drMap.get(regionId);
  }

  void rmById(Long regionId) {
    this.drMap.remove(regionId);
  }



  private final String name;
  private final boolean autoCompact;
  private final boolean allowForceCompaction;
  private final long maxOplogSizeInBytes;
  private final long timeInterval;
  private final int queueSize;
  private final int writeBufferSize;
  private final File[] diskDirs;
  private final int[] diskDirSizes;
  private final boolean syncWrites;

  // DiskStore interface methods
  public String getName() {
    return this.name;
  }

  public boolean getAutoCompact() {
    return this.autoCompact;
  }

  public boolean getAllowForceCompaction() {
    return this.allowForceCompaction;
  }

  public long getMaxOplogSize() {
    return this.maxOplogSizeInBytes / (1024 * 1024);
  }

  public long getMaxOplogSizeInBytes() {
    return this.maxOplogSizeInBytes;
  }

  public long getTimeInterval() {
    return this.timeInterval;
  }

  public int getQueueSize() {
    return this.queueSize;
  }

  public int getWriteBufferSize() {
    return this.writeBufferSize;
  }

  public File[] getDiskDirs() {
    return this.diskDirs;
  }

  public int[] getDiskDirSizes() {
    return this.diskDirSizes;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean getSyncWrites() {
    return this.syncWrites;
  }

  // public String toString() {
  // StringBuffer sb = new StringBuffer();
  // sb.append("<");
  // sb.append(getName());
  // if (getOwnedByRegion()) {
  // sb.append(" OWNED_BY_REGION");
  // }
  // sb.append(">");
  // return sb.toString();
  // }

  public static class AsyncDiskEntry {
    public final LocalRegion region;
    public final DiskEntry de;
    public final boolean versionOnly;
    public final VersionTag tag;

    public AsyncDiskEntry(LocalRegion region, DiskEntry de, VersionTag tag) {
      this.region = region;
      this.de = de;
      this.tag = tag;
      this.versionOnly = false;
    }

    public AsyncDiskEntry(LocalRegion region, VersionTag tag) {
      this.region = region;
      this.de = null;
      this.tag = tag;
      this.versionOnly = true;
      // if versionOnly, only de.getDiskId() is used for synchronize
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("dr=").append(region.getDiskRegion().getId());
      sb.append(" versionOnly=" + this.versionOnly);
      if (this.versionOnly) {
        sb.append(" versionTag=" + this.tag);
      }
      if (de != null) {
        sb.append(" key=" + de.getKeyCopy());
      } else {
        sb.append(" <END CLEAR>");
      }
      return sb.toString();
    }
  }

  /**
   * Set of OplogEntryIds (longs). Memory is optimized by using an int[] for ids
   * in the unsigned int range.
   */
  public static class OplogEntryIdSet {
    private final IntHashSet ints = new IntHashSet(8);
    private final LongHashSet longs = new LongHashSet(8);

    public void add(long id) {
      if (id >= 0 && id <= 0x00000000FFFFFFFFL) {
        this.ints.add((int) id);
      } else {
        this.longs.add(id);
      }
    }

    public boolean contains(long id) {
      if (id >= 0 && id <= 0x00000000FFFFFFFFL) {
        return this.ints.contains((int) id);
      } else {
        return this.longs.contains(id);
      }
    }

    public int size() {
      return this.ints.size() + this.longs.size();
    }
    
    public void addAll(OplogEntryIdSet toAdd) {
      this.ints.addAll(toAdd.ints.toArray());
      this.longs.addAll(toAdd.longs.toArray());
    }
  }

  /**
   * Set to true if this diskStore is owned by a single region. This only
   * happens in backwardsCompat mode.
   */
  private final boolean ownedByRegion;

  /**
   * Set to the region's {@link InternalRegionArguments} when the diskStore is
   * owned by a single region in backwardsCompat mode ({@link #ownedByRegion}
   * must be true).
   */
  private final InternalRegionArguments internalRegionArgs;

  /**
   * Number of current owners. Only valid if ownedByRegion is true.
   */
  private final AtomicInteger ownCount = new AtomicInteger();

  public boolean getOwnedByRegion() {
    return this.ownedByRegion;
  }

  public InternalRegionArguments getInternalRegionArguments() {
    return this.internalRegionArgs;
  }

  public int getOwnCount() {
    return this.ownCount.get();
  }

  private final boolean validating;

  boolean isValidating() {
    return this.validating;
  }

  private final boolean offline;

  public boolean isOffline() {
    return this.offline;
  }

  public final boolean upgradeVersionOnly;

  boolean isUpgradeVersionOnly() {
    return this.upgradeVersionOnly
        && Version.GFE_70.compareTo(this.getRecoveredGFVersion()) > 0;
  }

  boolean isUpgradeVersionOnly(DiskInitFile initFile) {
    return this.upgradeVersionOnly
        && Version.GFE_70.compareTo(this.getRecoveredGFVersion(initFile)) > 0;
  }

  private final boolean offlineCompacting;

  boolean isOfflineCompacting() {
    return this.offlineCompacting;
  }

  /**
   * Destroy a region which has not been created.
   * 
   * @param regName
   *          the name of the region to destroy
   * @param throwIfNotExists throw an {@link IllegalArgumentException} if the
   *                         given region does not exist, else return false
   */
  public boolean destroyRegion(String regName, boolean throwIfNotExists) {
    DiskRegionView drv = getDiskInitFile().getDiskRegionByName(regName);
    if (drv == null) {
      drv = getDiskInitFile().getDiskRegionByPrName(regName);
      if (drv == null) {
        if (throwIfNotExists) {
          throw new IllegalArgumentException(
              "The disk store does not contain a region named: " + regName);
        }
        else {
          return false;
        }
      } else {
        getDiskInitFile().destroyPRRegion(regName);
      }
    } else {
      getDiskInitFile().endDestroyRegion(drv);
    }
    return true;
  }

  public String modifyRegion(String regName, String lruOption,
      String lruActionOption, String lruLimitOption,
      String concurrencyLevelOption, String initialCapacityOption,
      String loadFactorOption, String compressorClassNameOption,
      String statisticsEnabledOption, boolean printToConsole) {

    assert isOffline();
    DiskRegionView drv = getDiskInitFile().getDiskRegionByName(regName);
    if (drv == null) {
      drv = getDiskInitFile().getDiskRegionByPrName(regName);
      if (drv == null) {
        throw new IllegalArgumentException(
            "The disk store does not contain a region named: " + regName);
      } else {
        return getDiskInitFile().modifyPRRegion(regName, lruOption,
            lruActionOption, lruLimitOption, concurrencyLevelOption,
            initialCapacityOption, loadFactorOption, compressorClassNameOption,
            statisticsEnabledOption, printToConsole);
      }
    } else {
      return getDiskInitFile().modifyRegion(drv, lruOption, lruActionOption,
          lruLimitOption, concurrencyLevelOption, initialCapacityOption,
          loadFactorOption, compressorClassNameOption,
          statisticsEnabledOption, printToConsole);
    }
  }

  private void dumpInfo(PrintStream printStream, String regName) {
    assert isOffline();
    getDiskInitFile().dumpRegionInfo(printStream, regName);
  }

  private void dumpMetadata(boolean showBuckets) {
    assert isOffline();
    getDiskInitFile().dumpRegionMetadata(showBuckets);
  }

  private void exportSnapshot(String name, File out) throws IOException {
    // Since we are recovering a disk store, the cast from DiskRegionView -->
    // PlaceHolderDiskRegion
    // and from RegionEntry --> DiskEntry should be ok.

    // In offline mode, we need to schedule the regions to be recovered
    // explicitly.
    for (DiskRegionView drv : getKnown()) {
      scheduleForRecovery((PlaceHolderDiskRegion) drv);
    }
    recoverRegionsThatAreReady(false);

    // coelesce disk regions so that partitioned buckets from a member end up in
    // the same file
    Map<String, List<PlaceHolderDiskRegion>> regions = new HashMap<String, List<PlaceHolderDiskRegion>>();
    for (DiskRegionView drv : getKnown()) {
      PlaceHolderDiskRegion ph = (PlaceHolderDiskRegion) drv;
      String regionName = (drv.isBucket() ? ph.getPrName() : drv.getName());
      List<PlaceHolderDiskRegion> views = regions.get(regionName);
      if (views == null) {
        views = new ArrayList<PlaceHolderDiskRegion>();
        regions.put(regionName, views);
      }
      views.add(ph);
    }

    for (Map.Entry<String, List<PlaceHolderDiskRegion>> entry : regions
        .entrySet()) {
      String fname = entry.getKey().substring(1).replace('/', '-');
      File f = new File(out, "snapshot-" + name + "-" + fname);
      SnapshotWriter writer = GFSnapshot.create(f, entry.getKey());
      try {
        for (DiskRegionView drv : entry.getValue()) {
          // skip regions that have no entries
          if (drv.getRecoveredEntryCount() == 0) {
            continue;
          }

          // TODO: [sumedh] for best efficiency this should use equivalent of
          // DiskSavyIterator or Oplog.getSortedLiveEntries for recovered
          // entry map else random reads will kill performance
          Collection<RegionEntry> entries = drv.getRecoveredEntryMap()
              .regionEntries();
          for (RegionEntry re : entries) {
            Object key = re.getKeyCopy();
            // TODO:KIRK:OK Rusty's code was value = de.getValueWithContext(drv);
            @Retained @Released Object value = re._getValueRetain(drv, true); // OFFHEAP: passed to SnapshotRecord who copies into byte[]; so for now copy to heap CD
            if (Token.isRemoved(value)) {
              continue;
            }

            // some entries may have overflowed to disk
            if (value == null && re instanceof DiskEntry) {
              DiskEntry de = (DiskEntry) re;
              DiskEntry.Helper.recoverValue(de, de.getDiskId().getOplogId(),
                  ((DiskRecoveryStore) drv));
              // TODO:KIRK:OK Rusty's code was value = de.getValueWithContext(drv);
              value = de._getValueRetain(drv, true); // OFFHEAP: passed to SnapshotRecord who copies into byte[]; so for now copy to heap CD
            }
            try {
              writer.snapshotEntry(new SnapshotRecord(key, value));
            } finally {
              OffHeapHelper.release(value);
            }
          }
        }
      } finally {
        writer.snapshotComplete();
      }
    }
  }

  private void validate() {
    assert isValidating();
    this.RECOVER_VALUES = false; // save memory @todo should Oplog make sure
                                 // value is deserializable?
    this.liveEntryCount = 0;
    this.deadRecordCount = 0;
    for (DiskRegionView drv : getKnown()) {
      scheduleForRecovery(ValidatingDiskRegion.create(this, drv));
    }
    recoverRegionsThatAreReady(false);
    if (getDeadRecordCount() > 0) {
      System.out.println("Disk store contains " + getDeadRecordCount()
          + " compactable records.");
    }
    System.out.println("Total number of region entries in this disk store is: "
        + getLiveEntryCount());
    String r = getDiskInitFile().getInconsistencyReport();
    if (r != null) {
      System.out.print(r);
    }
    String cbinfo = getDiskInitFile().getColumnBufferInfo();
    if (cbinfo != null) {
      System.out.print(cbinfo);
    }
  }

  private int liveEntryCount;

  void incLiveEntryCount(int count) {
    this.liveEntryCount += count;
  }

  public int getLiveEntryCount() {
    return this.liveEntryCount;
  }

  private int deadRecordCount;

  void incDeadRecordCount(int count) {
    this.deadRecordCount += count;
  }

  public int getDeadRecordCount() {
    return this.deadRecordCount;
  }

  private void offlineCompact() {
    assert isOfflineCompacting();
    this.RECOVER_VALUES = false;
    this.deadRecordCount = 0;
    for (DiskRegionView drv : getKnown()) {
      scheduleForRecovery(OfflineCompactionDiskRegion.create(this, drv));
    }

    persistentOplogs.recoverRegionsThatAreReady(false);
    persistentOplogs.offlineCompact();
    
    getDiskInitFile().forceCompaction();
    if (this.upgradeVersionOnly) {
      System.out.println("Upgrade disk store " + this.name + " to version "
          + getRecoveredGFVersionName() + " finished.");
    } else {
      if (getDeadRecordCount() == 0) {
        System.out
            .println("Offline compaction did not find anything to compact.");
      } else {
        System.out.println("Offline compaction removed " + getDeadRecordCount()
            + " records.");
      }
      // If we have more than one oplog then the liveEntryCount may not be the
      // total
      // number of live entries in the disk store. So do not log the live entry
      // count
    }
  }

  private final HashMap<String, LRUStatistics> prlruStatMap = new HashMap<String, LRUStatistics>();

  LRUStatistics getOrCreatePRLRUStats(PlaceHolderDiskRegion dr) {
    String prName = dr.getPrName();
    LRUStatistics result = null;
    synchronized (this.prlruStatMap) {
      result = this.prlruStatMap.get(prName);
      if (result == null) {
        EvictionAttributesImpl ea = dr.getEvictionAttributes();
        LRUAlgorithm ec = ea.createEvictionController(null, dr.getEnableOffHeapMemory());
        StatisticsFactory sf = cache.getDistributedSystem();
        result = ec.getLRUHelper().initStats(dr, sf);
        this.prlruStatMap.put(prName, result);
      }
    }
    return result;
  }

  /**
   * If we have recovered a bucket earlier for the given pr then we will have an
   * LRUStatistics to return for it. Otherwise return null.
   */
  LRUStatistics getPRLRUStats(PartitionedRegion pr) {
    String prName = pr.getFullPath();
    LRUStatistics result = null;
    synchronized (this.prlruStatMap) {
      result = this.prlruStatMap.get(prName);
    }
    return result;
  }

  /**
   * Lock the disk store to prevent updates. This is the first step of the
   * backup process. Once all disk stores on all members are locked, we still
   * move on to startBackup.
   */
  public void lockStoreBeforeBackup() {
    // This will prevent any region level operations like
    // create/destroy region, and region view changes.
    // We might want to consider preventing any entry level
    // operations as well. We should at least prevent transactions
    // when we support persistent transactions.
    //
    // When we do start caring about blocking entry
    // level operations, we will need to be careful
    // to block them *before* they are put in the async
    // queue
    getDiskInitFile().lockForBackup();
  }

  /**
   * Release the lock that is preventing operations on this disk store during
   * the backup process.
   */
  public void releaseBackupLock() {
    getDiskInitFile().unlockForBackup();
  }

  /**
   * Start the backup process. This is the second step of the backup process. In
   * this method, we define the data we're backing up by copying the init file
   * and rolling to the next file. After this method returns operations can
   * proceed as normal, except that we don't remove oplogs.
   * 
   * @param targetDir
   * @param baselineInspector
   * @param restoreScript
   * @throws IOException
   */
  public void startBackup(File targetDir, BackupInspector baselineInspector,
      RestoreScript restoreScript) throws IOException {
    getDiskInitFile().setBackupThread(Thread.currentThread());
    boolean done = false;
    try {
      for (;;) {
        Oplog childOplog = persistentOplogs.getChild();
        if (childOplog == null) {
          this.diskStoreBackup = new DiskStoreBackup(Collections.EMPTY_MAP, targetDir);
          break;
        }
        
        //Get an appropriate lock object for each set of oplogs.
        Object childLock = childOplog == null ? new Object() : childOplog.lock;;
        
        // TODO - We really should move this lock into the disk store, but
        // until then we need to do this magic to make sure we're actually
        // locking the latest child for both types of oplogs
        
        //This ensures that all writing to disk is blocked while we are
        //creating the snapshot
        synchronized (childLock) {
          if (persistentOplogs.getChild() != childOplog) {
            continue;
          }

          logger.fine("snapshotting oplogs for disk store " + getName());

          // Create the directories for this disk store
          for (int i = 0; i < directories.length; i++) {
            File dir = getBackupDir(targetDir, i);
            if (!FileUtil.mkdirs(dir)) {
              throw new IOException("Could not create directory " + dir);
            }
            restoreScript.addFile(directories[i].getDir(), dir);
          }

          restoreScript.addExistenceTest(this.initFile.getIFFile());

          // Contains all oplogs that will backed up
          Map<Oplog, Set<File>> allOplogs = null;
          
          // Incremental backup so filter out oplogs that have already been
          // backed up
          if (null != baselineInspector) {
            Map<File, File> baselineCopyMap = new LinkedHashMap<File, File>();
            allOplogs = filterBaselineOplogs(baselineInspector, baselineCopyMap);
            restoreScript.addBaselineFiles(baselineCopyMap);
          } else {
            allOplogs = getAllOplogsForBackup();
          }

          // mark all oplogs as being backed up. This will
          // prevent the oplogs from being deleted
          this.diskStoreBackup = new DiskStoreBackup(allOplogs, targetDir);

          // copy the init file
          File firstDir = getBackupDir(targetDir, infoFileDirIndex);
          initFile.copyTo(firstDir);
          persistentOplogs.forceRoll(null);

          logger.fine("done snaphotting for disk store" + getName());
          break;
        }
      }
      done = true;
    } finally {
      if (!done) {
        clearBackup();
      }
    }
  }

  private File getBackupDir(File targetDir, int index) {
    return new File(targetDir, BACKUP_DIR_PREFIX + index);
  }

  /**
   * Copy the oplogs to the backup directory. This is the final step of the
   * backup process. The oplogs we copy are defined in the startBackup method.
   * 
   * @param backupManager
   * @throws IOException
   */
  public void finishBackup(BackupManager backupManager) throws IOException {
    if (diskStoreBackup == null) {
      return;
    }
    try {
      //Wait for oplogs to be unpreblown before backing them up.
      waitForDelayedWrites();
      
      //Backup all of the oplogs
      for (Map.Entry<Oplog, Set<File>> entry: this.diskStoreBackup.getPendingBackup().entrySet()) {
        if (backupManager.isCancelled()) {
          break;
        }
        Oplog oplog = entry.getKey();
        Set<File> filesToBackup = entry.getValue();
        // Copy theoplog to the destination directory
        int index = oplog.getDirectoryHolder().getArrayIndex();
        File backupDir = getBackupDir(this.diskStoreBackup.getTargetDir(),
            index);

        //Backup just the set of files we previously captured
        for(File file : filesToBackup) {
          FileUtil.copy(file, backupDir);
        }

        // Allow the oplog to be deleted, and process any pending delete
        this.diskStoreBackup.backupFinished(oplog);
      }
    } finally {
      clearBackup();
    }
  }

  private int getArrayIndexOfDirectory(File searchDir) {
    for(DirectoryHolder holder : directories) {
      if(holder.getDir().equals(searchDir)) {
        return holder.getArrayIndex();
      }
    }
    return 0;
  }
  
  public DirectoryHolder[] getDirectoryHolders(){
    return this.directories;
  }

  private void clearBackup() {
    DiskStoreBackup backup = this.diskStoreBackup;
    if (backup != null) {
      this.diskStoreBackup = null;
      backup.cleanup();
    }
  }

  public DiskStoreBackup getInProgressBackup() {
    return diskStoreBackup;
  }

  private void createRegionsForValidation(String name) {
    // first create any parent regions as non-persistent with local scope.
    // then create the last region itself as PERSISTENT_REPLICATE with local
    // scope
  }

  protected Collection<DiskRegionView> getKnown() {
    return this.initFile.getKnown();
  }

  private static DiskStoreImpl createForOffline(String dsName, File[] dsDirs)
      throws Exception {
    return createForOffline(dsName, dsDirs, false, false,
        false/* upgradeVersionOnly */, 0, true);
  }

  private static DiskStoreImpl createForOffline(String dsName, File[] dsDirs,
      boolean needsOplogs) throws Exception {
    return createForOffline(dsName, dsDirs, false, false,
        false/* upgradeVersionOnly */, 0, needsOplogs);
  }

  private static DiskStoreImpl createForOfflineValidate(String dsName,
      File[] dsDirs) throws Exception {
    return createForOffline(dsName, dsDirs, false, true,
        false/* upgradeVersionOnly */, 0, true);
  }

  protected static Cache offlineCache = null;
  protected static DistributedSystem offlineDS = null;

  private final boolean persistIndexes;

  private final HashSet<String> recoveredIndexIds;

  private static void cleanupOffline() {
    if (offlineCache != null) {
      offlineCache.close();
      offlineCache = null;
    }
    if (offlineDS != null) {
      offlineDS.disconnect();
      offlineDS = null;
    }
  }

  private static DiskStoreImpl createForOffline(String dsName, File[] dsDirs,
      boolean offlineCompacting, boolean offlineValidate,
      boolean upgradeVersionOnly, long maxOplogSize, boolean needsOplogs)
      throws Exception {
    if (dsDirs == null) {
      dsDirs = new File[] { new File("") };
    }
    // need a cache so create a loner ds
    Properties props = new Properties();
    props.setProperty("locators", "");
    props.setProperty("mcast-port", "0");
    props.setProperty("cache-xml-file", "");
    if (!TRACE_RECOVERY) {
      props.setProperty("log-level", "warning");
    }
    DistributedSystem ds = DistributedSystem.connect(props);
    offlineDS = ds;
    Cache c = com.gemstone.gemfire.cache.CacheFactory.create(ds);
    offlineCache = c;
    com.gemstone.gemfire.cache.DiskStoreFactory dsf = c
        .createDiskStoreFactory();
    dsf.setDiskDirs(dsDirs);
    if (offlineCompacting && maxOplogSize != -1L) {
      dsf.setMaxOplogSize(maxOplogSize);
    }
    DiskStoreImpl dsi = new DiskStoreImpl(c, dsName,
        ((DiskStoreFactoryImpl) dsf).getDiskStoreAttributes(), false, null,
        true, upgradeVersionOnly, offlineValidate, offlineCompacting,
        needsOplogs);
    ((GemFireCacheImpl) c).addDiskStore(dsi);
    return dsi;
  }

  /**
   * Use this method to destroy a region in an offline disk store.
   * 
   * @param dsName
   *          the name of the disk store
   * @param dsDirs
   *          the directories that that the disk store wrote files to
   * @param regName
   *          the name of the region to destroy
   */
  public static void destroyRegion(String dsName, File[] dsDirs, String regName)
      throws Exception {
    DiskStoreImpl dsi = createForOffline(dsName, dsDirs);
    try {
      dsi.destroyRegion(regName, true);
    } finally {
      cleanupOffline();
    }
  }

  public static String modifyRegion(String dsName, File[] dsDirs,
      String regName, String lruOption, String lruActionOption,
      String lruLimitOption, String concurrencyLevelOption,
      String initialCapacityOption, String loadFactorOption,
      String compressorClassNameOption, String statisticsEnabledOption,
      boolean printToConsole) throws Exception {
    DiskStoreImpl dsi = createForOffline(dsName, dsDirs);
    try {
      return dsi.modifyRegion(regName, lruOption, lruActionOption,
          lruLimitOption, concurrencyLevelOption, initialCapacityOption,
          loadFactorOption, compressorClassNameOption,
          statisticsEnabledOption, printToConsole);
    } finally {
      cleanupOffline();
    }
  }

  public static void dumpInfo(PrintStream printStream, String dsName,
      File[] dsDirs, String regName) throws Exception {
    DiskStoreImpl dsi = createForOffline(dsName, dsDirs, false);
    try {
      dsi.dumpInfo(printStream, regName);
    } finally {
      cleanupOffline();
    }
  }

  public static void dumpMetadata(String dsName, File[] dsDirs,
      boolean showBuckets) throws Exception {
    DiskStoreImpl dsi = createForOffline(dsName, dsDirs, false);
    try {
      dsi.dumpMetadata(showBuckets);
    } finally {
      cleanupOffline();
    }
  }

  public static void exportOfflineSnapshot(String dsName, File[] dsDirs,
      File out) throws Exception {
    DiskStoreImpl dsi = createForOffline(dsName, dsDirs);
    try {
      dsi.exportSnapshot(dsName, out);
    } finally {
      cleanupOffline();
    }
  }

  public static void validate(String name, File[] dirs) throws Exception {
    DiskStoreImpl dsi = createForOfflineValidate(name, dirs);
    try {
      dsi.validate();
    } finally {
      cleanupOffline();
    }
  }

  public static DiskStoreImpl offlineCompact(String name, File[] dirs,
      boolean upgradeVersionOnly, long maxOplogSize) throws Exception {
    try {
      GemFireCacheImpl.StaticSystemCallbacks sysCb =
          GemFireCacheImpl.FactoryStatics.systemCallbacks;
      if (sysCb != null) {
        sysCb.initializeForOffline();
      }
      DiskStoreImpl dsi = createForOffline(name, dirs, true, false,
          upgradeVersionOnly, maxOplogSize, true);
      dsi.offlineCompact();
      dsi.close();
      return dsi;
    } finally {
      cleanupOffline();
    }
  }

  public static void main(String args[]) throws Exception {
    if (args.length == 0) {
      System.out.println("Usage: diskStoreName [dirs]");
    } else {
      String dsName = args[0];
      File[] dirs = null;
      if (args.length > 1) {
        dirs = new File[args.length - 1];
        for (int i = 1; i < args.length; i++) {
          dirs[i - 1] = new File(args[i]);
        }
      }
      offlineCompact(dsName, dirs, false, 1024);
    }
  }

  public boolean hasPersistedData() {
    return persistentOplogs.getChild() != null;
  }

  public UUID getDiskStoreUUID() {
    return this.diskStoreID.toUUID();
  }

  public DiskStoreID getDiskStoreID() {
    return this.diskStoreID;
  }

  void setDiskStoreID(DiskStoreID diskStoreID) {
    this.diskStoreID = diskStoreID;
  }

  File getInitFile() {
    return getDiskInitFile().getIFFile();
  }

  public boolean needsLinkedList() {
    return isCompactionPossible() || couldHaveKrf();
  }

  /**
   * 
   * @return true if KRF files are used on this disk store's oplogs
   */
  boolean couldHaveKrf() {
    return !isOffline();
  }

  @Override
  public String toString() {
    return "DiskStore[" + name + "]";
  }

  private class IndexRecoveryTask implements Runnable {

    private final Set<Oplog> allOplogs;

    public IndexRecoveryTask(Set<Oplog> allOplogs, boolean recreateIndexFile) {
      this.allOplogs = allOplogs;
      this.recreateIndexFile = recreateIndexFile;
    }

    private final boolean recreateIndexFile;

    @Override
    public void run() {
      indexRecoveryFailure.set(null);
      final GemFireCacheImpl.StaticSystemCallbacks cb = GemFireCacheImpl
          .getInternalProductCallbacks();
      // wait for async recovery if required
      Set<SortedIndexContainer> indexes = null;
      final DiskStoreImpl dsi = DiskStoreImpl.this;
      if (cb != null) {
        cb.waitForAsyncIndexRecovery(dsi);
        indexes = cb.getAllLocalIndexes(dsi);
      }

      // need to recover indexes if index recovery map is non-null
      if (indexes != null && !indexes.isEmpty()) {
        try {
          // if there are newly created indexes then do populate all indexes
          // from full values since we have to read full values in any case
          @SuppressWarnings("unchecked")
          final Map<SortedIndexContainer, SortedIndexRecoveryJob> allIndexes =
              new THashMap();
          @SuppressWarnings("unchecked")
          final Set<SortedIndexContainer> newIndexes = new THashSet(4);
          for (SortedIndexContainer index : indexes) {
            if (!recoveredIndexIds.contains(index.getUUID())) {
              if (TEST_NEW_CONTAINER) {
                if (TEST_NEW_CONTAINER_LIST == null) {
                  TEST_NEW_CONTAINER_LIST = new ArrayList<Object>();
                }
                TEST_NEW_CONTAINER_LIST.add(index);
              }
              newIndexes.add(index);
            }
            allIndexes.put(index, new SortedIndexRecoveryJob(dsi.getCache(),
                dsi, dsi.getCancelCriterion(), index));
          }
          for (Oplog oplog : this.allOplogs) {
            // check if we have started closing
            getCancelCriterion().checkCancelInProgress(null);
            // recover for indexes if there was no krf (hence value
            // recovery already done inline)
            // fallback to full recovery if failed to recover from *irf
            File indexFile = oplog.getIndexFileIfValid(this.recreateIndexFile);
            boolean hasKrf = !oplog.needsKrf();
            if (!hasKrf || !newIndexes.isEmpty() || indexFile == null) {
              // for missing krf case, the irf will be created in createKrf
              if (persistIndexes && hasKrf) {
                Collection<DiskRegionInfo> targetRegions = oplog
                    .getTargetRegionsForIndexes(indexes);
                List<KRFEntry> sortedLiveEntries = oplog
                    .getSortedLiveEntries(targetRegions);
                if (indexFile == null) {
                  // create the full irf files
                  oplog.writeIRF(sortedLiveEntries, null, indexes, allIndexes);
                  // check if we have started closing
                  getCancelCriterion().checkCancelInProgress(null);
                  getDiskInitFile().irfCreate(oplog.oplogId);
                }
                else {
                  // append to IRF for new indexes only but load all indexes
                  oplog.writeIRF(sortedLiveEntries, null, newIndexes,
                      allIndexes);
                }
              }
              else {
                oplog.recoverIndexes(allIndexes);
              }
            }
            else {
              oplog.getOplogIndex().recoverIndexes(allIndexes);
            }
          }
          // submit last jobs for all in parallel and then wait for all
          Collection<SortedIndexRecoveryJob> allJobs = allIndexes.values();
          for (SortedIndexRecoveryJob indexRecoveryJob : allJobs) {
            indexRecoveryJob.submitLastJob();
          }
          for (SortedIndexRecoveryJob indexRecoveryJob : allJobs) {
            indexRecoveryJob.waitForJobs(0);
            //Approximating the index size after complete index recovery. Assumption is that sufficient memory is there.
            indexRecoveryJob.getIndexContainer().accountMemoryForIndex(0,true);
          }
          if (!newIndexes.isEmpty()) {
            for (SortedIndexContainer index : newIndexes) {
              writeIndexCreate(index.getUUID());
            }
          }
        } catch (IOException ioe) {
          indexRecoveryFailure
              .compareAndSet(null, new DiskAccessException(ioe));
        } catch (RuntimeException re) {
          indexRecoveryFailure.compareAndSet(null, re);
        } catch (Error err) {
          if (SystemFailure.isJVMFailureError(err)) {
            SystemFailure.initiateFailure(err);
            // If this ever returns, rethrow the error. We're poisoned
            // now, so don't let this thread continue.
            throw err;
          }
          // Whenever you catch Error or Throwable, you must also
          // check for fatal JVM error (see above). However, there is
          // _still_ a possibility that you are dealing with a cascading
          // error condition, so you also need to check to see if the JVM
          // is still usable:
          SystemFailure.checkFailure();
          indexRecoveryFailure.compareAndSet(null, err);
        } finally {
          for (Oplog oplog : this.allOplogs) {
            oplog.clearInitRecoveryMap();
          }
          markIndexRecoveryDone();
        }
      }
      else {
        for (Oplog oplog : this.allOplogs) {
          oplog.clearInitRecoveryMap();
        }
        markIndexRecoveryDone();
      }
    }
  }

  private class ValueRecoveryTask implements Runnable {
    private final Set<Oplog> oplogSet;
    private final Map<Long, DiskRecoveryStore> recoveredStores;

    public ValueRecoveryTask(Set<Oplog> oplogSet,
        Map<Long, DiskRecoveryStore> recoveredStores) {
      this.oplogSet = oplogSet;
      this.recoveredStores = new HashMap<Long, DiskRecoveryStore>(
          recoveredStores);
    }

    public void run() {
      // store any regions whose initializations have to be deferred
      final HashMap<Long, DiskRecoveryStore> deferredRegions =
          new HashMap<Long, DiskRecoveryStore>();
      synchronized (asyncValueRecoveryLock) {
        try {
          // wait for index recovery to complete first to avoid interference
          waitForIndexRecoveryEnd(-1);

          DiskStoreObserver.startAsyncValueRecovery(DiskStoreImpl.this);
          // defer regions marked in first pass
          for (Oplog oplog : oplogSet) {
            // If returns false further recovery is not possible due to UMM limit.
            if (!oplog.recoverValuesIfNeeded(currentAsyncValueRecoveryMap,
                deferredRegions, currentAsyncValueRecoveryMap)){
              break;
            }
          }
        } catch (CancelException ignore) {
          // do nothing
        } finally {
          synchronized (currentAsyncValueRecoveryMap) {
            currentAsyncValueRecoveryMap.keySet().removeAll(
                recoveredStores.keySet());
            if (deferredRegions.size() > 0) {
              currentAsyncValueRecoveryMap.putAll(deferredRegions);
              if (logger.fineEnabled()) {
                logger.fine("DiskStoreImpl: deferred recovery stores: "
                    + currentAsyncValueRecoveryMap.values());
              }
            }
            else {
              DiskStoreObserver.endAsyncValueRecovery(DiskStoreImpl.this);
            }
            currentAsyncValueRecoveryMap.notifyAll();
          }
        }
        if (deferredRegions.size() > 0) {
          // second round to recover the deferred regions/TXStates, but first
          // do any initialization (used by GemFireXD to wait for DDL replay);
          // break the wait if new tasks have been added to the recovery
          // list for recovery to avoid blocking them (#43048)
          try {
            final GemFireCacheImpl.StaticSystemCallbacks cb = GemFireCacheImpl
                .getInternalProductCallbacks();
            final long waitMillis = 100L;
            if (cb != null) {
              while (!cb.initializeForDeferredRegionsRecovery(waitMillis)) {
                synchronized (currentAsyncValueRecoveryMap) {
                  if (currentAsyncValueRecoveryMap.size() > deferredRegions
                      .size()) {
                    DiskStoreObserver.endAsyncValueRecovery(DiskStoreImpl.this);
                    return;
                  }
                }
              }
            }
            for (Oplog oplog : oplogSet) {
              // If returns false further recovery is not possible due to UMM limit.
              if (!oplog.recoverValuesIfNeeded(deferredRegions, null,
                  currentAsyncValueRecoveryMap)){
                break;
              }
            }
          } catch (CancelException ignore) {
            // do nothing
          } finally {
            synchronized (currentAsyncValueRecoveryMap) {
              currentAsyncValueRecoveryMap.keySet().removeAll(
                  recoveredStores.keySet());
              currentAsyncValueRecoveryMap.notifyAll();
            }
            DiskStoreObserver.endAsyncValueRecovery(DiskStoreImpl.this);
          }
        }
      }
    }
  }

  public void waitForAsyncRecovery(DiskRegion diskRegion) {
    synchronized (currentAsyncValueRecoveryMap) {
      boolean interrupted = false;
      while (!isClosing()
          && currentAsyncValueRecoveryMap.containsKey(diskRegion.getId())) {
        try {
          currentAsyncValueRecoveryMap.wait(500);
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  private static final ThreadLocal<Boolean> backgroundTaskThread = new ThreadLocal<Boolean>();

  private static boolean isBackgroundTaskThread() {
    boolean result = false;
    Boolean tmp = backgroundTaskThread.get();
    if (tmp != null) {
      result = tmp.booleanValue();
    }
    return result;
  }

  private static void markBackgroundTaskThread() {
    backgroundTaskThread.set(Boolean.TRUE);
  }

  /**
   * Execute a task which must be performed asnychronously, but has no requirement
   * for timely execution. This task pool is used for compactions, creating KRFS, etc.
   * So some of the queued tasks may take a while.
   */
  public boolean executeDiskStoreTask(final Runnable runnable) {
    return executeDiskStoreTask(runnable,
        getCache().getDiskStoreTaskPool(), true) != null;
  }

  /**
   * Execute a task asynchronously, or in the calling thread if the bound
   * is reached. This pool is used for write operations which can be delayed,
   * but we have a limit on how many write operations we delay so that
   * we don't run out of disk space. Used for deletes, unpreblow, RAF close, etc.
   */
  public boolean executeDelayedExpensiveWrite(Runnable task) {
    Future<?> f = (Future<?>)executeDiskStoreTask(task,
        getCache().getDiskDelayedWritePool(), false);
    lastDelayedWrite = f;
    return f != null;
  }

  /**
   * Wait for any current operations in the delayed write pool. Completion
   * of this method ensures that the writes have completed or the pool was shutdown
   */
  protected void waitForDelayedWrites() {
    Future<?> lastWriteTask = lastDelayedWrite;
    if(lastWriteTask != null) {
      try {
        lastWriteTask.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        //do nothing, an exception from the write task was already logged.
      }
    }
  }

  private Object executeDiskStoreTask(final Runnable runnable,
      ThreadPoolExecutor executor, boolean async) {
    // schedule another thread to do it
    incBackgroundTasks();
    Object result = executeDiskStoreTask(new DiskStoreTask() {
      public void run() {
        try {
          markBackgroundTaskThread(); // for bug 42775
          //getCache().getCachePerfStats().decDiskTasksWaiting();
          runnable.run();
        } finally {
          decBackgroundTasks();
        }
      }

      public void taskCancelled() {
        decBackgroundTasks();
      }
    }, executor, async);

    if (result == null) {
      decBackgroundTasks();
    }

    return result;
  }

  private Object executeDiskStoreTask(DiskStoreTask r,
      ThreadPoolExecutor executor, boolean async) {
    try {
      if (async) {
        executor.execute(r);
        return Boolean.TRUE;
      } else {
        return executor.submit(r);
      }
    } catch (RejectedExecutionException ex) {
      if (this.logger.fineEnabled()) {
        this.logger.fine("Ignored compact schedule during shutdown", ex);
      }
    }
    return null;
  }

  public void writeRVVGC(DiskRegion dr, LocalRegion region) {
    if (region != null && !region.getConcurrencyChecksEnabled()) {
      return;
    }
    acquireReadLock(dr);
    try {
      if (dr.isRegionClosed()) {
        dr.getCancelCriterion().checkCancelInProgress(null);
        throw new RegionDestroyedException(
            LocalizedStrings.DiskRegion_THE_DISKREGION_HAS_BEEN_CLOSED_OR_DESTROYED
                .toLocalizedString(), dr.getName());
      }

      // Update on the on disk region version vector.
      // TODO - RVV - For async regions, it's possible that
      // the on disk RVV is actually less than the GC RVV we're trying record
      // it might make sense to push the RVV through the async queue?
      // What we're doing here is only recording the GC RVV if it is dominated
      // by the RVV of what we have persisted.
      RegionVersionVector inMemoryRVV = region.getVersionVector();
      RegionVersionVector diskRVV = dr.getRegionVersionVector();

      // Update the GC version for each member in our on disk version map
      updateDiskGCRVV(diskRVV, inMemoryRVV, diskRVV.getOwnerId());
      for (VersionSource member : (Collection<VersionSource>) inMemoryRVV
          .getMemberToGCVersion().keySet()) {
        updateDiskGCRVV(diskRVV, inMemoryRVV, member);
      }

      // Remove any exceptions from the disk RVV that are are dominated
      // by the GC RVV.
      diskRVV.pruneOldExceptions();

      PersistentOplogSet oplogSet = getPersistentOplogSet(dr);
      // persist the new GC RVV information for this region to the DRF
      oplogSet.getChild().writeGCRVV(dr);
    } finally {
      releaseReadLock(dr);
    }
  }

  public void writeRVV(DiskRegion dr, LocalRegion region, Boolean isRVVTrusted) {
    if (region != null && !region.getConcurrencyChecksEnabled()) {
      return;
    }
    acquireReadLock(dr);
    try {
      if (dr.isRegionClosed()) {
        dr.getCancelCriterion().checkCancelInProgress(null);
        throw new RegionDestroyedException(
            LocalizedStrings.DiskRegion_THE_DISKREGION_HAS_BEEN_CLOSED_OR_DESTROYED
                .toLocalizedString(), dr.getName());
      }

      RegionVersionVector inMemoryRVV = (region==null)?null:region.getVersionVector();
      // persist the new GC RVV information for this region to the CRF
      PersistentOplogSet oplogSet = getPersistentOplogSet(dr);
      // use current dr.rvvTrust
      oplogSet.getChild().writeRVV(dr, inMemoryRVV, isRVVTrusted);
    } finally {
      releaseReadLock(dr);
    }
  }

  /**
   * Update the on disk GC version for the given member, only if the disk has
   * actually recorded all of the updates including that member.
   * 
   * @param diskRVV
   *          the RVV for what has been persisted
   * @param inMemoryRVV
   *          the RVV of what is in memory
   * @param member
   *          The member we're trying to update
   */
  private void updateDiskGCRVV(RegionVersionVector diskRVV,
      RegionVersionVector inMemoryRVV, VersionSource member) {
    long diskVersion = diskRVV.getVersionForMember(member);
    long memoryGCVersion = inMemoryRVV.getGCVersion(member);

    // If the GC version is less than what we have on disk, go ahead
    // and record it.
    if (memoryGCVersion <= diskVersion) {
      diskRVV.recordGCVersion(member, memoryGCVersion, null);
    }

  }

  public final Version getRecoveredGFVersion() {
    return getRecoveredGFVersion(this.initFile);
  }

  final Version getRecoveredGFVersion(DiskInitFile initFile) {
    return initFile.currentRecoveredGFVersion();
  }

  public DirectoryHolder[] getDirectories() {
    return this.directories;
  }
  
  public void updateDiskRegion(AbstractDiskRegion dr) {
    PersistentOplogSet oplogSet = getPersistentOplogSet(dr);
    oplogSet.updateDiskRegion(dr);
  }

  public void writeIndexCreate(String indexId) {
    this.initFile.indexCreate(indexId);
  }

  public void writeIndexDelete(String indexId) {
    this.initFile.indexDelete(indexId);
  }

  public boolean markIndexRecoveryScheduled() {
    synchronized (this.indexRecoveryState) {
      if (this.indexRecoveryState[0] == INDEXRECOVERY_UNINIT) {
        markIndexRecovery(INDEXRECOVERY_INIT);
        return true;
      }
      else {
        return false;
      }
    }
  }

  public void resetIndexRecoveryState() {
    synchronized (this.indexRecoveryState) {
      this.indexRecoveryState[0] = INDEXRECOVERY_UNINIT;
    }
  }

  public void markIndexRecoveryDone() {
    synchronized (this.indexRecoveryState) {
      markIndexRecovery(INDEXRECOVERY_DONE);
    }
  }

  /** should be invoked under synchronized (this.indexRecoveryState) */
  private void markIndexRecovery(int state) {
    assert Thread.holdsLock(this.indexRecoveryState);

    if (logger.fineEnabled()) {
      logger
          .fine("DSI: marking indexRecovery=" + state + " for: " + toString());
    }
    this.indexRecoveryState[0] = state;
    this.indexRecoveryState.notifyAll();
  }

  public boolean waitForIndexRecoveryEnd(long waitMillis) {
    return waitForIndexRecovery(INDEXRECOVERY_DONE, waitMillis);
  }

  private boolean waitForIndexRecovery(int expected, long waitMillis) {
    long endMillis;
    if (waitMillis < 0) {
      endMillis = waitMillis = Long.MAX_VALUE;
    }
    else {
      long currentTime = System.currentTimeMillis();
      endMillis = currentTime + waitMillis;
      if (endMillis < currentTime) {
        endMillis = Long.MAX_VALUE;
      }
    }
    final long loopMillis = Math.min(200L, waitMillis);
    synchronized (this.indexRecoveryState) {
      while (this.indexRecoveryState[0] < expected && !isClosing()) {
        Throwable t = null;
        try {
          if (logger.fineEnabled()) {
            logger.fine("DSI: waiting for indexRecovery=" + expected + " for: "
                + toString());
          }
          this.indexRecoveryState.wait(loopMillis);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          t = ie;
        }
        getCancelCriterion().checkCancelInProgress(t);
        checkIndexRecoveryFailure();
        if (System.currentTimeMillis() >= endMillis) {
          return (this.indexRecoveryState[0] >= expected || isClosing());
        }
      }
    }
    checkIndexRecoveryFailure();
    return true;
  }

  private void checkIndexRecoveryFailure() {
    final Throwable t = this.indexRecoveryFailure.get();
    if (t != null) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException)t;
      }
      else if (t instanceof Error) {
        throw (Error)t;
      }
      else {
        throw new IndexMaintenanceException(t);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onEvent(MemoryEvent event) {
    // stop growth of async queue on EVICTION_UP or CRITICAL_UP and restart on
    // EVICTION_DOWN
    final MemoryState memoryState = event.getState();
    if (this.logger.fineEnabled()) {
      this.logger.fine("DiskStoreImpl " + getName()
          + ": received memory event " + event + " with queueSize="
          + this.asyncQueue.size());
    }

    if (memoryState.isCritical()) {
      if (!this.cache.isClosing) {
        setAsyncQueueCapacityToCurrent();
      }
    }
    else if (memoryState.isEviction()) {
      setAsyncQueueCapacityToCurrent();
    }
    else {
      resetAsyncQueueCapacity();
    }
  }

  public void acquireDiskStoreReadLock() {
    try {
      this.diskStoreLock.readLock().acquire();
    } catch(InterruptedException ie) {
      this.stopper.checkCancelInProgress(ie);
      Thread.currentThread().interrupt();
      throw new InternalGemFireException(ie);
    }
  }

  public void releaseDiskStoreReadLock() {
    this.diskStoreLock.readLock().release();
  }

  public void acquireDiskStoreWriteLock() {
    try {
      this.diskStoreLock.writeLock().acquire();
    } catch (InterruptedException ie) {
      this.stopper.checkCancelInProgress(ie);
      Thread.currentThread().interrupt();
      throw new InternalGemFireException(ie);
    }
  }

  public void releaseDiskStoreWriteLock() {
    this.diskStoreLock.writeLock().release();
  }

  void shutdownDiskStoreAndAffiliatedRegions(DiskAccessException dae) {
    final ThreadGroup exceptionHandlingGroup = LogWriterImpl.createThreadGroup(
      "Disk Store Exception Handling Group", cache.getLoggerI18n());

    // Shutdown the regions and bridge servers in another thread, to make sure
    // that we don't cause a deadlock because this thread is holding some lock
    Thread thread = new Thread(exceptionHandlingGroup,
      "Disk store exception handler") {
      @Override
      public void run() {

        /**
         * Each DiskStore at the time of creation , also creates files to be used as standby oplog.
         * Once the standby oplog is switched by any thread , an asynch thread is started in the diskstore whose
         * task is to shut down the diskstore & the regions using diskstore, after taking a write lock.
         * This will ensure that any non tx thread is done using disk store & once the write lock is taken by
         * the closing asynch thread, no new non tx threads can come.
         * The asynch thread code, waits for 5 minutes, to allow any transactions to get over before shutting down
         * the diskstore. The ideal way would have been for tx thread to take diskstore read lock at the start of
         * transaction & release it on commit. But I don't think that is possible as gfxd transactions
         * are distributed , with different threads doing transactions.
         * Also the asynch thread keeps acquiring & releasing the write lock for a while because
         * it was seen that even tx thread , when generating UUID was doing it out without tx state set,
         * as result that operation is deemed as non tx & it tries to acquire readlock in AbstractRegionMap.
         * So if the asynch thread does not release the write lock periodically,
         * the tx thread gets indirectly blocked at the UUID generation path,
         * causing shutdown to happen after 5 minutes, with transaction uncompleted.
         */

        // wait in a loop for Transactions to be empty
        TXManagerImpl txManager = DiskStoreImpl.this.cache.getTxManager();
        // wait for 5 minutes for tx to be empty , then just close?
        int count = 0;
        DiskStoreImpl.this.acquireDiskStoreWriteLock();
        while(txManager.hasHostedTransactions()) {
          DiskStoreImpl.this.releaseDiskStoreWriteLock();
          try {
            Thread.sleep(20000);
            ++count;
            if (count == 15) {
              // just proceed to close!!?
              DiskStoreImpl.this.acquireDiskStoreWriteLock();
              break;
            }
          } catch (InterruptedException ie) {
            DiskStoreImpl.this.acquireDiskStoreWriteLock();
            // just proceed to close!!?
            break;
          }
          DiskStoreImpl.this.acquireDiskStoreWriteLock();
        }
        try {
        // first ask each region to handle the exception.
        for (DiskRegion dr : DiskStoreImpl.this.drMap.values()) {
          DiskExceptionHandler lr = dr.getExceptionHandler();
          lr.handleDiskAccessException(dae, false);
        }

        // then stop the bridge server if needed

          LogWriterI18n logger = getCache().getLoggerI18n();
          logger
            .info(LocalizedStrings.LocalRegion_ATTEMPTING_TO_CLOSE_THE_BRIDGESERVERS_TO_INDUCE_FAILOVER_OF_THE_CLIENTS);
          try {
            getCache().stopServers();
            // also close GemFireXD network servers to induce failover (#45651)
            final StaticSystemCallbacks sysCb =
              GemFireCacheImpl.FactoryStatics.systemCallbacks;
            if (sysCb != null) {
              sysCb.stopNetworkServers();
            }
            logger.info(LocalizedStrings
              .LocalRegion_BRIDGESERVERS_STOPPED_SUCCESSFULLY);
          } catch (Exception e) {
            logger.error(LocalizedStrings
              .LocalRegion_THE_WAS_A_PROBLEM_IN_STOPPING_BRIDGESERVERS_FAILOVER_OF_CLIENTS_IS_SUSPECT, e);
          }


        logger.error(LocalizedStrings
            .LocalRegion_A_DISKACCESSEXCEPTION_HAS_OCCURED_WHILE_WRITING_TO_THE_DISK_FOR_DISKSTORE_0_THE_DISKSTORE_WILL_BE_CLOSED,
          DiskStoreImpl.this.getName(), dae);

        // then close this disk store
        onClose();
        close();
      } finally {
          DiskStoreImpl.this.releaseDiskStoreWriteLock();
        }
     }
    };
    thread.start();

  }

}
