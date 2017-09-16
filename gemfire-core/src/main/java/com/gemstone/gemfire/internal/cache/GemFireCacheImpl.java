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
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.naming.Context;

import com.gemstone.gemfire.*;
import com.gemstone.gemfire.admin.internal.SystemMemberCacheEventProcessor;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueueFactory;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueFactoryImpl;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientRegionFactory;
import com.gemstone.gemfire.cache.client.ClientRegionShortcut;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.client.PoolFactory;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.client.internal.ClientMetadataService;
import com.gemstone.gemfire.cache.client.internal.ClientRegionFactoryImpl;
import com.gemstone.gemfire.cache.client.internal.PoolImpl;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.hdfs.HDFSStoreFactory;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSIntegrationUtil;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreCreation;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSFlushQueueFunction;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSForceCompactionFunction;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSLastCompactionTimeFunction;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSRegionDirector;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSStoreDirector;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.cache.query.internal.CqService;
import com.gemstone.gemfire.cache.query.internal.DefaultQuery;
import com.gemstone.gemfire.cache.query.internal.DefaultQueryService;
import com.gemstone.gemfire.cache.query.internal.QueryMonitor;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.cache.snapshot.CacheSnapshotService;
import com.gemstone.gemfire.cache.util.BridgeServer;
import com.gemstone.gemfire.cache.util.GatewayConflictResolver;
import com.gemstone.gemfire.cache.util.GatewayHub;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.cache.wan.GatewayReceiver;
import com.gemstone.gemfire.cache.wan.GatewayReceiverFactory;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.cache.wan.GatewaySenderFactory;
import com.gemstone.gemfire.distributed.DistributedLockService;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.*;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.Profile;
import com.gemstone.gemfire.distributed.internal.locks.DLockService;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.*;
import com.gemstone.gemfire.internal.HostStatSampler.StatsSamplerCallback;
import com.gemstone.gemfire.internal.cache.BucketRegion.RawValueFactory;
import com.gemstone.gemfire.internal.cache.DiskInitFile.DiskRegionFlag;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager.ResourceType;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholdListener;
import com.gemstone.gemfire.internal.cache.control.ResourceAdvisor;
import com.gemstone.gemfire.internal.cache.ha.HARegionQueue;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.lru.HeapEvictor;
import com.gemstone.gemfire.internal.cache.lru.OffHeapEvictor;
import com.gemstone.gemfire.internal.cache.partitioned.RedundancyAlreadyMetException;
import com.gemstone.gemfire.internal.cache.persistence.BackupManager;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberManager;
import com.gemstone.gemfire.internal.cache.persistence.query.TemporaryResultSetFactory;
import com.gemstone.gemfire.internal.cache.snapshot.CacheSnapshotServiceImpl;
import com.gemstone.gemfire.internal.cache.store.SerializedDiskBuffer;
import com.gemstone.gemfire.internal.cache.tier.sockets.AcceptorImpl;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientNotifier;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientProxy;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientHealthMonitor;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionHolder;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.gemstone.gemfire.internal.cache.wan.GatewayReceiverFactoryImpl;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderAdvisor;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderAttributes;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderFactoryImpl;
import com.gemstone.gemfire.internal.cache.wan.parallel.ParallelGatewaySenderQueue;
import com.gemstone.gemfire.internal.cache.xmlcache.CacheXmlParser;
import com.gemstone.gemfire.internal.cache.xmlcache.CacheXmlPropertyResolver;
import com.gemstone.gemfire.internal.cache.xmlcache.PropertyResolver;
import com.gemstone.gemfire.internal.concurrent.AI;
import com.gemstone.gemfire.internal.concurrent.CFactory;
import com.gemstone.gemfire.internal.concurrent.CM;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.jndi.JNDIInvoker;
import com.gemstone.gemfire.internal.jta.TransactionManagerImpl;
import com.gemstone.gemfire.internal.offheap.MemoryAllocator;
import com.gemstone.gemfire.internal.offheap.OffHeapStorage;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.ChunkType;
import com.gemstone.gemfire.internal.shared.BufferAllocator;
import com.gemstone.gemfire.internal.shared.HeapBufferAllocator;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.shared.unsafe.DirectBufferAllocator;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.gemstone.gemfire.internal.snappy.memory.MemoryManagerStats;
import com.gemstone.gemfire.internal.tcp.ConnectionTable;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.internal.util.concurrent.FutureResult;
import com.gemstone.gemfire.lang.Identifiable;
import com.gemstone.gemfire.management.cli.CommandService;
import com.gemstone.gemfire.management.internal.JmxManagerAdvisee;
import com.gemstone.gemfire.management.internal.JmxManagerAdvisor;
import com.gemstone.gemfire.management.internal.beans.ManagementListener;
import com.gemstone.gemfire.memcached.GemFireMemcachedServer;
import com.gemstone.gemfire.memcached.GemFireMemcachedServer.Protocol;
import com.gemstone.gemfire.pdx.PdxInstance;
import com.gemstone.gemfire.pdx.PdxInstanceFactory;
import com.gemstone.gemfire.pdx.PdxSerializer;
import com.gemstone.gemfire.pdx.ReflectionBasedAutoSerializer;
import com.gemstone.gemfire.pdx.internal.AutoSerializableManager;
import com.gemstone.gemfire.pdx.internal.PdxInstanceFactoryImpl;
import com.gemstone.gemfire.pdx.internal.PdxInstanceImpl;
import com.gemstone.gemfire.pdx.internal.TypeRegistry;
import com.gemstone.gnu.trove.THashSet;

// @todo somebody Come up with more reasonable values for {@link #DEFAULT_LOCK_TIMEOUT}, etc.
/**
 * GemFire's implementation of a distributed {@link com.gemstone.gemfire.cache.Cache}.
 *
 * @author Darrel Schneider
 */
public class GemFireCacheImpl implements InternalCache, ClientCache, HasCachePerfStats, DistributionAdvisee {

  // moved *SERIAL_NUMBER stuff to DistributionAdvisor

  /** The default number of seconds to wait for a distributed lock */
  public static final int DEFAULT_LOCK_TIMEOUT = Integer.getInteger("gemfire.Cache.defaultLockTimeout", 60).intValue();

  /**
   * The default duration (in seconds) of a lease on a distributed lock
   */
  public static final int DEFAULT_LOCK_LEASE = Integer.getInteger("gemfire.Cache.defaultLockLease", 120).intValue();

  /** The default "copy on read" attribute value */
  public static final boolean DEFAULT_COPY_ON_READ = false;

  /** the last instance of GemFireCache created */
  private static volatile GemFireCacheImpl instance = null;
  /**
   * Just like instance but is valid for a bit longer so that pdx can still find the cache during a close.
   */
  private static volatile GemFireCacheImpl pdxInstance = null;

  /**
   * The default amount of time to wait for a <code>netSearch</code> to complete
   */
  public static final int DEFAULT_SEARCH_TIMEOUT = Integer.getInteger("gemfire.Cache.defaultSearchTimeout", 300).intValue();

  /**
   * The <code>CacheLifecycleListener</code> s that have been registered in this VM
   */
  private static final Set cacheLifecycleListeners = new HashSet();

  /**
   * Define LocalRegion.ASYNC_EVENT_LISTENERS=true to invoke event listeners in the background
   */
  public static final boolean ASYNC_EVENT_LISTENERS = Boolean.getBoolean("gemfire.Cache.ASYNC_EVENT_LISTENERS");

  /**
   * If true then when a delta is applied the size of the entry value will be recalculated. If false (the default) then
   * the size of the entry value is unchanged by a delta application. Not a final so that tests can change this value.
   */
  public static boolean DELTAS_RECALCULATE_SIZE = Boolean.getBoolean("gemfire.DELTAS_RECALCULATE_SIZE");

  public static final int EVENT_QUEUE_LIMIT = Integer.getInteger("gemfire.Cache.EVENT_QUEUE_LIMIT", 4096).intValue();

  /**
   * System property to limit the max query-execution time. By default its turned off (-1), the time is set in MiliSecs.
   */
  public static final int MAX_QUERY_EXECUTION_TIME = Integer.getInteger("gemfire.Cache.MAX_QUERY_EXECUTION_TIME", -1).intValue();

  /**
   * System property to disable query monitor even if resource manager is in use
   */
  public final boolean QUERY_MONITOR_DISABLED_FOR_LOW_MEM = Boolean.getBoolean("gemfire.Cache.DISABLE_QUERY_MONITOR_FOR_LOW_MEMORY");

  /**
   * System property to disable default snapshot
   */
  public boolean DEFAULT_SNAPSHOT_ENABLED = SystemProperties.getServerInstance().getBoolean(
      "cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION", false);

  private final boolean DEFAULT_SNAPSHOT_ENABLED_TEST = SystemProperties.getServerInstance().getBoolean(
      "cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST", false);

  /**
   * Property set to true if resource manager heap percentage is set and query monitor is required
   */
  public static Boolean QUERY_MONITOR_REQUIRED_FOR_RESOURCE_MANAGER = Boolean.FALSE;

  /**
   * True if the user is allowed lock when memory resources appear to be overcommitted. 
   */
  public static final boolean ALLOW_MEMORY_LOCK_WHEN_OVERCOMMITTED = Boolean.getBoolean("gemfire.Cache.ALLOW_MEMORY_OVERCOMMIT");

  
  //time in ms
  private static final int FIVE_HOURS = 5 * 60 * 60 * 1000;
  /** To test MAX_QUERY_EXECUTION_TIME option. */
  public int TEST_MAX_QUERY_EXECUTION_TIME = -1;
  public boolean TEST_MAX_QUERY_EXECUTION_TIME_OVERRIDE_EXCEPTION = false;

  // ///////////////////// Instance Fields ///////////////////////

  private final InternalDistributedSystem system;

  private final DM dm;

  private final InternalDistributedMember myId;

  // This is a HashMap because I know that clear() on it does
  // not allocate objects.
  private final HashMap rootRegions;

  /**
   * True if this cache is being created by a ClientCacheFactory.
   */
  private final boolean isClient;
  protected PoolFactory clientpf;
  /**
   * It is not final to allow cache.xml parsing to set it.
   */
  private Pool defaultPool;

  private final CM pathToRegion = CFactory.createCM();

  protected volatile boolean isClosing = false;
  protected volatile boolean closingGatewayHubsByShutdownAll = false;
  protected volatile boolean closingGatewaySendersByShutdownAll = false;
  protected volatile boolean closingGatewayReceiversByShutdownAll = false;

  /** Amount of time (in seconds) to wait for a distributed lock */
  private int lockTimeout = DEFAULT_LOCK_TIMEOUT;

  /** Amount of time a lease of a distributed lock lasts */
  private int lockLease = DEFAULT_LOCK_LEASE;

  /** Amount of time to wait for a <code>netSearch</code> to complete */
  private int searchTimeout = DEFAULT_SEARCH_TIMEOUT;

  private final CachePerfStats cachePerfStats;

  /** Date on which this instances was created */
  private final Date creationDate;

  /** thread pool for event dispatching */
  private final ThreadPoolExecutor eventThreadPool;

  /**
   * GemFireXD's static distribution advisee.
   */
  private volatile DistributionAdvisee gfxdAdvisee;

  /**
   * the list of all bridge servers. CopyOnWriteArrayList is used to allow concurrent add, remove and retrieval
   * operations. It is assumed that the traversal operations on bridge servers list vastly outnumber the mutative
   * operations such as add, remove.
   */
  private volatile List allBridgeServers = new CopyOnWriteArrayList();

  /**
   * Controls updates to the list of all gateway senders
   *
   * @see #allGatewaySenders
   */
  public final Object allGatewaySendersLock = new Object();

  /**
   * the set of all gateway senders. It may be fetched safely (for enumeration), but updates must by synchronized via
   * {@link #allGatewaySendersLock}
   */
  private volatile Set<GatewaySender> allGatewaySenders = Collections.emptySet();

  /**
   * The list of all async event queues added to the cache. 
   * CopyOnWriteArrayList is used to allow concurrent add, remove and retrieval operations.
   */
  private volatile Set<AsyncEventQueue> allAsyncEventQueues = new CopyOnWriteArraySet<AsyncEventQueue>();
  
  /**
   * Controls updates to the list of all gateway receivers
   *
   * @see #allGatewayReceivers
   */
  public final Object allGatewayReceiversLock = new Object();

  /**
   * the list of all gateway Receivers. It may be fetched safely (for enumeration), but updates must by synchronized via
   * {@link #allGatewayReceiversLock}
   */
  private volatile Set<GatewayReceiver> allGatewayReceivers = Collections.emptySet();

  /** PartitionedRegion instances (for required-events notification */
  // This is a HashSet because I know that clear() on it does not
  // allocate any objects.
  private final HashSet<PartitionedRegion> partitionedRegions =
    new HashSet<PartitionedRegion>();

  /**
   * Fix for 42051 This is a map of regions that are in the process of being destroyed. We could potentially leave the
   * regions in the pathToRegion map, but that would entail too many changes at this point in the release. We need to
   * know which regions are being destroyed so that a profile exchange can get the persistent id of the destroying
   * region and know not to persist that ID if it receives it as part of the persistent view.
   */
  private final ConcurrentMap<String, DistributedRegion> regionsInDestroy =
    new ConcurrentHashMap<String, DistributedRegion>();

  /**
   * The <code>GatewayHub</code>s registered on this <code>Cache</code>
   *
   * @guarded.By {@link #allGatewayHubsLock}
   */
  private volatile GatewayHubImpl allGatewayHubs[] = new GatewayHubImpl[0];

  /**
   * Controls updates to {@link #allGatewayHubs}
   */
  public final Object allGatewayHubsLock = new Object();
  
  /**
   * conflict resolver for WAN, if any
   * @guarded.By {@link #allGatewayHubsLock}
   */
  private GatewayConflictResolver gatewayConflictResolver;

  /** Is this is "server" cache? */
  private boolean isServer = false;

  /** transaction manager for this cache */
  private final TXManagerImpl txMgr;

  /** persistent TXId generator */
  //private final PersistentUUIDAdvisor txIdAdvisor;

  /** Copy on Read feature for all read operations e.g. get */
  private volatile boolean copyOnRead = DEFAULT_COPY_ON_READ;
  
  /** The named region attributes registered with this cache. */
  private final Map namedRegionAttributes = Collections.synchronizedMap(new HashMap());

  /**
   * if this cache was forced to close due to a forced-disconnect, we retain a ForcedDisconnectException that can be
   * used as the cause
   */
  private boolean forcedDisconnect;

  /**
   * if this cache was forced to close due to a forced-disconnect or system failure, this keeps track of the reason
   */
  protected volatile Throwable disconnectCause = null;

  /** context where this cache was created -- for debugging, really... */
  public Exception creationStack = null;

  /**
   * a system timer task for cleaning up old bridge thread event entries
   */
  private EventTracker.ExpiryTask recordedEventSweeper;

  private TombstoneService tombstoneService;

  private Map<Region,RegionVersionVector> snapshotRVV = new ConcurrentHashMap<Region,RegionVersionVector>();

  private final ReentrantReadWriteLock snapshotLock = new ReentrantReadWriteLock();
  private final ReentrantReadWriteLock lockForSnapshotRvv = new ReentrantReadWriteLock();

  private volatile RvvSnapshotTestHook testHook;
  private volatile RowScanTestHook rowScanTestHook;
  /**
   * DistributedLockService for PartitionedRegions. Remains null until the first PartitionedRegion is created. Destroyed
   * by GemFireCache when closing the cache. Protected by synchronization on this GemFireCache.
   *
   * @guarded.By prLockServiceLock
   */
  private DistributedLockService prLockService;

  /**
   * lock used to access prLockService
   */
  private final Object prLockServiceLock = new Object();

  private volatile DistributedRegion cachedPRRoot;

  private final InternalResourceManager resourceManager;

  private final AtomicReference<BackupManager> backupManager =
    new AtomicReference<BackupManager>();

  private HeapEvictor heapEvictor = null;
  
  private OffHeapEvictor offHeapEvictor = null;

  private final Object heapEvictorLock = new Object();
  
  private final Object offHeapEvictorLock = new Object();

  private final BufferAllocator bufferAllocator;

  private ResourceEventsListener listener;

  /**
   * Enabled when CacheExistsException issues arise in debugging
   *
   * @see #creationStack
   */
  private static final boolean DEBUG_CREATION_STACK = false;

  private volatile QueryMonitor queryMonitor;

  private final Object queryMonitorLock = new Object();

  private final PersistentMemberManager persistentMemberManager;

  private ClientMetadataService clientMetadatService = null;

  private final Object clientMetaDatServiceLock = new Object();

  private volatile boolean isShutDownAll = false;

  private transient final ReentrantReadWriteLock rvvSnapshotLock = new ReentrantReadWriteLock();
  /**
   * Set of members that are not yet ready. Currently used by GemFireXD during
   * initial DDL replay to indicate that the member should not be chosen for
   * primary buckets.
   */
  private final HashSet<InternalDistributedMember> unInitializedMembers =
      new HashSet<InternalDistributedMember>();

  /**
   * Set of {@link BucketAdvisor}s for this node that are pending for volunteer
   * for primary due to uninitialized node (GemFireXD DDL replay in progress).
   */
  private final LinkedHashSet<BucketAdvisor> deferredVolunteerForPrimary =
      new LinkedHashSet<BucketAdvisor>();

  private final ResourceAdvisor resourceAdvisor;
  private final JmxManagerAdvisor jmxAdvisor;

  private final int serialNumber;

  /** indicates whether this is a GemFireXD system */
  private static boolean gfxdSystem;

  private final CacheConfig cacheConfig;
  
  // Stores the properties used to initialize declarables.
  private final Map<Declarable, Properties> declarablePropertiesMap = new ConcurrentHashMap<Declarable, Properties>();
  
  // Indicates whether foreign key checks for events received on WAN gateways should be skipped when applying them 
  private boolean skipFKChecksForGatewayEvents = false;

  /** {@link PropertyResolver} to resolve ${} type property strings */
  protected static PropertyResolver resolver;

  protected static boolean xmlParameterizationEnabled =
      !Boolean.getBoolean("gemfire.xml.parameterization.disabled");

  /**
   * the memcachedServer instance that is started when {@link DistributionConfig#getMemcachedPort()}
   * is specified
   */
  private GemFireMemcachedServer memcachedServer;

  private String vmIdRegionPath;

  //TODO:Suranjan This has to be replcaed with better approach. guava cache or WeakHashMap.
  private final Map<String, Map<Object, BlockingQueue<RegionEntry>
    /*RegionEntry*/>>  oldEntryMap;
  
  private ScheduledExecutorService oldEntryMapCleanerService;

  /**
   * Time interval after which oldentries cleaner thread run
   */
  public static long OLD_ENTRIES_CLEANER_TIME_INTERVAL = Long.getLong("gemfire" +
      ".snapshot-oldentries-cleaner-time-interval", 20000);


  /**
   * Test only method
   *
   * @param oldEntriesCleanerTimeInterval
   */
  public void setOldEntriesCleanerTimeIntervalAndRestart(long
      oldEntriesCleanerTimeInterval) {
    OLD_ENTRIES_CLEANER_TIME_INTERVAL = oldEntriesCleanerTimeInterval;
    if (oldEntryMapCleanerService != null) {
      oldEntryMapCleanerService.shutdownNow();
      oldEntryMapCleanerService = Executors.newScheduledThreadPool(1);
      oldEntryMapCleanerService.scheduleAtFixedRate(new OldEntriesCleanerThread(), 0,
          OLD_ENTRIES_CLEANER_TIME_INTERVAL,
          TimeUnit.MILLISECONDS);
    }
  }

  // For each entry this should be in sync
  public void removeRegionFromOldEntryMap(String regionPath) {
    synchronized (this.oldEntryMap) {
      Map<Object, BlockingQueue<RegionEntry>> map = oldEntryMap.remove(regionPath);
      if (GemFireCacheImpl.hasNewOffHeap() && map != null) {
        for (BlockingQueue<RegionEntry> values : map.values()) {
          if (values != null) {
            for (RegionEntry re : values) {
              Object value = re._getValue();
              if (value instanceof SerializedDiskBuffer) {
                ((SerializedDiskBuffer)value).release();
              }
            }
          }
        }
      }
    }
  }

  public long getOldEntryRemovalPeriod() {
    return OLD_ENTRIES_CLEANER_TIME_INTERVAL;
  }
  // For each entry this should be in sync

  public void addOldEntry(NonLocalRegionEntry oldRe, RegionEntry newEntry,
      LocalRegion region) {
    if (!snapshotEnabled()) {
      return;
    }

    // Insert specific case
    // just add the newEntry in TXState for rollback.
    if (region.getTXState() != null) {
      TXState txState = region.getTXState().getLocalTXState();
      if (txState != null) {
        txState.addCommittedRegionEntryReference(oldRe == null ? Token.TOMBSTONE : oldRe, newEntry, region);
      }
    }

    if (oldRe == null) {
      return;
    }

    final String regionPath = region.getFullPath();
    // ask for pool memory before continuing
    if (!region.reservedTable() && region.needAccounting()) {
      region.calculateEntryOverhead(oldRe);
      LocalRegion.regionPath.set(region.getFullPath());
      region.acquirePoolMemory(0, oldRe.getValueSize(), oldRe.isForDelete(), null, true);
    }

    if(getLoggerI18n().fineEnabled()) {
      getLoggerI18n().fine("For region  " + regionPath + " adding " +
          oldRe + " to oldEntrMap");
    }

    Map<Object, BlockingQueue<RegionEntry>> snapshot = this.oldEntryMap.get(regionPath);
    if (snapshot != null) {
      enqueueOldEntry(oldRe, snapshot);
    } else {
      synchronized (this.oldEntryMap) {
        snapshot = this.oldEntryMap.get(regionPath);
        if (snapshot == null) {
          BlockingQueue<RegionEntry> oldEntryqueue = new LinkedBlockingDeque<RegionEntry>();
          snapshot = new ConcurrentHashMap<Object, BlockingQueue<RegionEntry>>();
          oldEntryqueue.add(oldRe);
          snapshot.put(oldRe.getKeyCopy(), oldEntryqueue);
          this.oldEntryMap.put(regionPath, snapshot);
        } else {
          enqueueOldEntry(oldRe, snapshot);
        }
      }
    }

    if (getLoggerI18n().fineEnabled()) {
      getLoggerI18n().fine("For key  " + oldRe.getKeyCopy() + " " +
          "the entries are " + snapshot.get(oldRe.getKeyCopy()));
    }
  }

  // for one entry it will always be called in a lock so assuming no sync
  private void enqueueOldEntry(RegionEntry oldRe, Map<Object, BlockingQueue<RegionEntry>> snapshot) {
    BlockingQueue<RegionEntry> oldEntryqueue = snapshot.get(oldRe.getKeyCopy());
    if (oldEntryqueue == null) {
      oldEntryqueue = new LinkedBlockingDeque<RegionEntry>();
      oldEntryqueue.add(oldRe);
      snapshot.put(oldRe.getKeyCopy(), oldEntryqueue);
    } else {
      oldEntryqueue.add(oldRe);
    }
  }

  final Object readOldEntry(Region region, final Object entryKey,
      final Map<String, Map<VersionSource, RegionVersionHolder>> snapshot, final boolean
      checkValid, RegionEntry re, TXState txState) {
    String regionPath = region.getFullPath();
    if (re.getVersionStamp().getEntryVersion() <= 1) {
      RegionEntry oldRegionEntry = NonLocalRegionEntry.newEntry(re.getKeyCopy(), Token.TOMBSTONE,
          (LocalRegion)region, re.getVersionStamp().asVersionTag());
      if (getLoggerI18n().fineEnabled()) {
        getLoggerI18n().fine("Returning TOMBSTONE");
      }
      return oldRegionEntry;
    } else {
      List<RegionEntry> oldEntries = new ArrayList<>();
      Map<Object, BlockingQueue<RegionEntry>> regionMap = oldEntryMap.get(regionPath);
      if (regionMap == null) {
        if (getLoggerI18n().fineEnabled()) {
          getLoggerI18n().fine("For region  " + region + " the snapshot doesn't have any snapshot yet but there " +
              "are entries present in the region" +
              " the RVV " + ((LocalRegion)region).getVersionVector().fullToString() + " and snapshot RVV " +
              ((LocalRegion)region).getVersionVector().getSnapShotOfMemberVersion() + "against the key " + entryKey +
              " the entry in region is " + re + " with version " + re.getVersionStamp().asVersionTag());
        }
        return null;
      }

      BlockingQueue<RegionEntry> entries = regionMap.get(entryKey);
      if (entries == null) {
        if (getLoggerI18n().fineEnabled()) {
        getLoggerI18n().fine("For region  " + region + " the snapshot doesn't have any snapshot yet but there " +
            "are entries present in the region" +
            " the RVV " + ((LocalRegion)region).getVersionVector().fullToString() + " and snapshot RVV " +
            ((LocalRegion)region).getVersionVector().getSnapShotOfMemberVersion() + " the entries are " + entries + " against the key " + entryKey +
        " the entry in region is " + re + " with version " + re.getVersionStamp().asVersionTag());
        }
        return null;
      }
      for (RegionEntry value : entries) {
        if (TXState.checkEntryInSnapshot(txState, region, value)) {
          oldEntries.add(value);
        }
      }

      RegionEntry max = NonLocalRegionEntry.newEntry(re.getKeyCopy(), Token.TOMBSTONE,
          (LocalRegion)region, null);
      for (RegionEntry entry : oldEntries) {
        if (null == max) {
          max = entry;
        } else if (max.getVersionStamp().getEntryVersion() <= entry.getVersionStamp()
            .getEntryVersion()) {
          max = entry;
        }
      }
      if (getLoggerI18n().fineEnabled()) {
        getLoggerI18n().fine("For region  " + region +
            " the RVV " + ((LocalRegion)region).getVersionVector().fullToString() + " and snapshot RVV " +
            ((LocalRegion)region).getVersionVector().getSnapShotOfMemberVersion() + " the entries are " + entries +
            "against the key " + entryKey +
            " the entry in region is " + re + " with version " + re.getVersionStamp().asVersionTag() +
            " the oldEntries are " + oldEntries + " returning : " + max);
      }
      return max;
    }
  }

  public Map getOldEntriesForRegion(String regionName) {
    return oldEntryMap.get(regionName);
  }

  public void startOldEntryCleanerService() {
    getLoggerI18n().info(LocalizedStrings.DEBUG,
        "Snapshot is enabled " + snapshotEnabled());

    if (oldEntryMapCleanerService == null) {
      final LogWriterImpl.LoggingThreadGroup threadGroup = LogWriterImpl.createThreadGroup("OldEntry GC Thread Group",
          this.system.getLogWriterI18n());
      ThreadFactory oldEntryGCtf = new ThreadFactory() {
        public Thread newThread(Runnable command) {
          Thread thread = new Thread(threadGroup, command,
              "OldEntry GC Thread");
          thread.setDaemon(true);
          return thread;
        }
      };

      getLoggerI18n().info(LocalizedStrings.DEBUG,
          "Snapshot is enabled, starting the cleaner thread.");
      oldEntryMapCleanerService = Executors.newScheduledThreadPool(1, oldEntryGCtf);
      oldEntryMapCleanerService.scheduleAtFixedRate(new OldEntriesCleanerThread(), 0, OLD_ENTRIES_CLEANER_TIME_INTERVAL,
          TimeUnit.MILLISECONDS);
    }
  }

  public void runOldEntriesCleanerThread(){
    new OldEntriesCleanerThread().run();
  }

  class OldEntriesCleanerThread implements Runnable {
    // Keep each entry alive for at least 20 secs.
    public void run() {
      try {
        if (!oldEntryMap.isEmpty()) {
          for (Entry<String,Map<Object, BlockingQueue<RegionEntry>>> entry : oldEntryMap.entrySet()) {
            Map<Object, BlockingQueue<RegionEntry>> regionEntryMap = entry.getValue();
            LocalRegion region = (LocalRegion)getRegion(entry.getKey());
            if (region == null) continue;
            for (BlockingQueue<RegionEntry> oldEntriesQueue : regionEntryMap.values()) {
              for (RegionEntry re : oldEntriesQueue) {
                boolean entryFoundInTxState = false;
                for (TXStateProxy txProxy : getTxManager().getHostedTransactionsInProgress()) {
                  TXState txState = txProxy.getLocalTXState();
                  if (re.isUpdateInProgress() || (txState != null && !txState.isCommitted() && TXState.checkEntryInSnapshot
                      (txState, region, re))) {
                    entryFoundInTxState = true;
                    break;
                  }
                }
                if (!entryFoundInTxState) {
                  if (getLoggerI18n().fineEnabled()) {
                    getLoggerI18n().fine(
                        "OldEntriesCleanerThread : Removing the entry " + re + " entry update in progress : " +
                            re.isUpdateInProgress());
                  }
                  // continue if some explicit call removed the entry
                  if (!oldEntriesQueue.remove(re)) continue;
                  if (GemFireCacheImpl.hasNewOffHeap()) {
                    // also remove reference to region buffer, if any
                    Object value = re._getValue();
                    if (value instanceof SerializedDiskBuffer) {
                      ((SerializedDiskBuffer)value).release();
                    }
                  }
                  // free the allocated memory
                  if (!region.reservedTable() && region.needAccounting()) {
                    NonLocalRegionEntry nre = (NonLocalRegionEntry)re;
                    region.freePoolMemory(nre.getValueSize(), nre.isForDelete());
                  }
                }
              }
            }
          }
        }

       synchronized (oldEntryMap) {
        for (Map<Object, BlockingQueue<RegionEntry>> regionEntryMap : oldEntryMap.values()) {
          for (Entry<Object, BlockingQueue<RegionEntry>> entry : regionEntryMap.entrySet()) {
            if (entry.getValue().size() == 0) {
              regionEntryMap.remove(entry.getKey());
              if (getLoggerI18n().fineEnabled()) {
                getLoggerI18n().fine(
                    "OldEntriesCleanerThread : Removing the map against the key " + entry.getKey());
              }
            }
          }
        }
       }
      }
      catch (Exception e) {
        if (getLoggerI18n().warningEnabled()) {
          getLoggerI18n().warning(LocalizedStrings.DEBUG,
              "OldEntriesCleanerThread : Error occured while cleaning the oldentries map.Actual " +
                  "Exception:", e);
        }
      }
    }
  }

  private long memorySize;
  /**
   * disables automatic eviction configuration for HDFS regions
   */
  private final static Boolean DISABLE_AUTO_EVICTION = Boolean.getBoolean("gemfire.disableAutoEviction");

  static {
    // this works around jdk bug 6427854, reported in ticket #44434
    String propertyName = "sun.nio.ch.bugLevel";
    String value = System.getProperty(propertyName);
    if (value == null) {
      System.setProperty(propertyName, "");
    }
  }

  public static void lockMemory() {
    NativeCalls.getInstance().lockCurrentMemory();
  }

  /**
   * This is for debugging cache-open issues (esp. {@link com.gemstone.gemfire.cache.CacheExistsException})
   */
  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("GemFireCache[");
    sb.append("id = " + System.identityHashCode(this));
    sb.append("; isClosing = " + this.isClosing);
    sb.append("; isShutDownAll = " + this.isShutDownAll);
    sb.append("; closingGatewayHubsByShutdownAll = " + this.closingGatewayHubsByShutdownAll);
    sb.append("; created = " + this.creationDate);
    sb.append("; server = " + this.isServer);
    sb.append("; copyOnRead = " + this.copyOnRead);
    sb.append("; lockLease = " + this.lockLease);
    sb.append("; lockTimeout = " + this.lockTimeout);
    // sb.append("; rootRegions = (" + this.rootRegions + ")");
    // sb.append("; bridgeServers = (" + this.bridgeServers + ")");
    // sb.append("; regionAttributes = (" + this.listRegionAttributes());
    // sb.append("; gatewayHub = " + gatewayHub);
    if (this.creationStack != null) {
      sb.append("\nCreation context:\n");
      OutputStream os = new OutputStream() {
        @Override
        public void write(int i) {
          sb.append((char) i);
        }
      };
      PrintStream ps = new PrintStream(os);
      this.creationStack.printStackTrace(ps);
    }
    sb.append("]");
    return sb.toString();
  }

  // ////////////////////// Constructors /////////////////////////

  /** Map of Futures used to track Regions that are being reinitialized */
  private final CM reinitializingRegions = CFactory.createCM();

  /** Returns the last created instance of GemFireCache */
  public static GemFireCacheImpl getInstance() {
    return instance;
  }

  /**
   * Returns an existing instance. If a cache does not exist
   * throws a cache closed exception.
   * 
   * @return the existing cache
   * @throws CacheClosedException
   *           if an existing cache can not be found.
   */
  public static final GemFireCacheImpl getExisting() {
    final GemFireCacheImpl result = instance;
    if (result != null && !result.isClosing) {
      return result;
    }
    if (result != null) {
      throw result.getCacheClosedException(LocalizedStrings
        .CacheFactory_THE_CACHE_HAS_BEEN_CLOSED.toLocalizedString(), null);
    }
    throw new CacheClosedException(LocalizedStrings
        .CacheFactory_A_CACHE_HAS_NOT_YET_BEEN_CREATED.toLocalizedString());
  }

  /**
   * Returns an existing instance. If a cache does not exist throws an exception.
   * 
   * @param reason
   *          the reason an existing cache is being requested.
   * @return the existing cache
   * @throws CacheClosedException
   *           if an existing cache can not be found.
   */
  public static GemFireCacheImpl getExisting(String reason) {
    final GemFireCacheImpl result = getInstance();
    if (result != null && !result.isClosing) {
      return result;
    }
    throw new CacheClosedException(reason);
  }

  /**
   * Pdx is allowed to obtain the cache even while it is being closed
   */
  public static GemFireCacheImpl getForPdx(String reason) {
    final GemFireCacheImpl result = pdxInstance;
    if (result != null) {
      return result;
    }
    throw new CacheClosedException(reason);
  }

  // /**
  // * @deprecated remove when Lise allows a Hydra VM to
  // * be re-created
  // */
  // public static void clearInstance() {
  // System.err.println("DEBUG: do not commit GemFireCache#clearInstance");
  // instance = null;
  // }

  public static GemFireCacheImpl create(boolean isClient, PoolFactory pf, DistributedSystem system, CacheConfig cacheConfig) {
    return new GemFireCacheImpl(true, pf, system, cacheConfig).init();
  }

  public static GemFireCacheImpl create(DistributedSystem system, CacheConfig cacheConfig) {
    return new GemFireCacheImpl(false, null, system, cacheConfig).init();
  }
  public static Cache create(DistributedSystem system, boolean existingOk, CacheConfig cacheConfig)
  throws CacheExistsException, TimeoutException, CacheWriterException,
  GatewayException,
  RegionExistsException 
  {
    GemFireCacheImpl instance = getInstance();

    if (instance != null && !instance.isClosed()) {
      if (existingOk) {
        // Check if cache configuration matches.
        cacheConfig.validateCacheConfig(instance);

        return instance;
      } else {
        // instance.creationStack argument is for debugging...
        throw new CacheExistsException(instance, LocalizedStrings.CacheFactory_0_AN_OPEN_CACHE_ALREADY_EXISTS.toLocalizedString(instance), instance.creationStack);
      }
    }
    return create(system, cacheConfig);
  }
  
  /**
   * Creates a new instance of GemFireCache and populates it according to the <code>cache.xml</code>, if appropriate.
   */
  protected GemFireCacheImpl(boolean isClient, PoolFactory pf, DistributedSystem system, CacheConfig cacheConfig) {
    this.isClient = isClient;
    this.clientpf = pf;
    this.cacheConfig = cacheConfig; // do early for bug 43213

    // initialize advisor for normal DMs immediately
    InternalDistributedSystem ids = (InternalDistributedSystem)system;
    StaticSystemCallbacks sysCallbacks = getInternalProductCallbacks();
    boolean startVMIdAdvisor = false;
    if (sysCallbacks != null) {
      startVMIdAdvisor = sysCallbacks.isOperationNode();
    }
    else {
      startVMIdAdvisor = (ids.getDistributedMember()
          .getVmKind() == DistributionManager.NORMAL_DM_TYPE);
    }
    if (startVMIdAdvisor) {
      ids.getVMIdAdvisor().handshake();
    }

    // Synchronized to prevent a new cache from being created
    // before an old one has finished closing
    synchronized (GemFireCacheImpl.class) {
      ExclusiveSharedSynchronizer.initProperties();
      
      // start JTA transaction manager within this synchronized block
      // to prevent race with cache close. fixes bug 43987
      JNDIInvoker.mapTransactions();
      this.system = ids;
      this.dm = this.system.getDistributionManager();

      if (!isHadoopGfxdLonerMode() && !this.isClient
          && PoolManager.getAll().isEmpty()) {
        // We only support management on members of a distributed system
        // Should do this:     if (!getSystem().isLoner()) {
        // but it causes quickstart.CqClientTest to hang
        this.listener = new ManagementListener();
        this.system.addResourceListener(listener);
      } else {
        this.listener = null;
      }

      // dummy call to FactoryStatics class for initialization
      FactoryStatics.init();

      // Don't let admin-only VMs create Cache's just yet.
      DM dm = this.system.getDistributionManager();
      this.myId = this.system.getDistributedMember();
      if (dm instanceof DistributionManager) {
        if (((DistributionManager) dm).getDMType() == DistributionManager.ADMIN_ONLY_DM_TYPE) {
          throw new IllegalStateException(LocalizedStrings.GemFireCache_CANNOT_CREATE_A_CACHE_IN_AN_ADMINONLY_VM
              .toLocalizedString());
        }
      }

      this.rootRegions = new HashMap();

      initReliableMessageQueueFactory();

      // Create the CacheStatistics
      this.cachePerfStats = new CachePerfStats(system);
      CachePerfStats.enableClockStats = this.system.getConfig().getEnableTimeStatistics();

      this.txMgr = new TXManagerImpl(this.cachePerfStats,
          this.system.getLogWriterI18n(), this);
      dm.addMembershipListener(this.txMgr);

      // clear any old TXState
      this.txMgr.clearTXState();

      //this.oldEntryMap = new CustomEntryConcurrentHashMap<>();
      this.oldEntryMap = new ConcurrentHashMap<String, Map<Object, BlockingQueue<RegionEntry>>>();

      if (snapshotEnabled()) {
        startOldEntryCleanerService();
      }

      this.creationDate = new Date();

      this.persistentMemberManager = new PersistentMemberManager(this.system.getLogWriterI18n());

      if (ASYNC_EVENT_LISTENERS) {
        final ThreadGroup group = LogWriterImpl.createThreadGroup("Message Event Threads", this.system.getLogWriterI18n());
        ThreadFactory tf = new ThreadFactory() {
          public Thread newThread(final Runnable command) {
            final Runnable r = new Runnable() {
              public void run() {
                ConnectionTable.threadWantsSharedResources();
                command.run();
              }
            };
            Thread thread = new Thread(group, r, "Message Event Thread");
            thread.setDaemon(true);
            return thread;
          }
        };
        // @todo darrel: add stats
        // this.cachePerfStats.getEventQueueHelper());
        ArrayBlockingQueue q = new ArrayBlockingQueue(EVENT_QUEUE_LIMIT);
        this.eventThreadPool = new PooledExecutorWithDMStats(q, 16, this.cachePerfStats.getEventPoolHelper(), tf, 1000,
            new CallerRunsPolicy());
      } else {
        this.eventThreadPool = null;
      }

      // Initialize the advisor here, but wait to exchange profiles until cache is fully built
      this.resourceAdvisor = ResourceAdvisor.createResourceAdvisor(this);
      // Initialize the advisor here, but wait to exchange profiles until cache is fully built
      this.jmxAdvisor = JmxManagerAdvisor.createJmxManagerAdvisor(new JmxManagerAdvisee(this));
      
      resourceManager = InternalResourceManager.createResourceManager(this);
      this.serialNumber = DistributionAdvisor.createSerialNumber();

      getResourceManager().addResourceListener(ResourceType.HEAP_MEMORY, getHeapEvictor());
      
      /*
       * Only bother creating an off-heap evictor if we have off-heap memory enabled.
       */
      if(null != getOffHeapStore()) {
        getResourceManager().addResourceListener(ResourceType.OFFHEAP_MEMORY, getOffHeapEvictor());
      }

      recordedEventSweeper = EventTracker.startTrackerServices(this);
      tombstoneService = TombstoneService.initialize(this);

      for (Iterator iter = cacheLifecycleListeners.iterator(); iter.hasNext();) {
        CacheLifecycleListener listener = (CacheLifecycleListener) iter.next();
        listener.cacheCreated(this);
      }
      if (GemFireCacheImpl.instance != null) {
        Assert.assertTrue(GemFireCacheImpl.instance == null, "Cache instance already in place: " + instance);
      }
      GemFireCacheImpl.instance = this;
      GemFireCacheImpl.pdxInstance = this;

      // set the buffer allocator for the cache (off-heap or heap)
      long memorySize = OffHeapStorage.parseOffHeapMemorySize(
          getSystem().getConfig().getMemorySize());
      if (memorySize == 0) {
        // check in callbacks
        StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();
        memorySize = callbacks.getExecutionPoolSize(true) +
            callbacks.getStoragePoolSize(true);
      }
      this.memorySize = memorySize;
      if (memorySize > 0) {
        if (!GemFireVersion.isEnterpriseEdition()) {
          throw new IllegalArgumentException("The off-heap column store (enabled by property " +
              "memory-size) is not supported in SnappyData OSS version.");
        }
        try {
          Class clazz = Class.forName("com.gemstone.gemfire.internal.cache.store.ManagedDirectBufferAllocator");
          Method method = clazz.getDeclaredMethod("instance");
          this.bufferAllocator = (DirectBufferAllocator)method.invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
            InvocationTargetException e) {
          throw new IllegalStateException("Could not configure managed buffer allocator.", e);
        }
      } else {
        // the allocation sizes will be initialized from the heap size
        this.bufferAllocator = HeapBufferAllocator.instance();
      }

      TypeRegistry.init();
      basicSetPdxSerializer(this.cacheConfig.getPdxSerializer());
      TypeRegistry.open();

      /*
      // start the persistent TXId advisor
      this.txIdAdvisor = PersistentUUIDAdvisor.createPersistentUUIDAdvisor(
          this.system, "/__TXID_ADVISOR",
          InternalRegionArguments.DEFAULT_UUID_RECORD_INTERVAL, null);
      this.txIdAdvisor.postInitialize();
      */

      if (!isClient()) {
        // Initialize the QRM thread freqeuncy to default (1 second )to prevent spill
        // over from previous Cache , as the interval is stored in a static
        // volatile field.
        HARegionQueue.setMessageSyncInterval(HARegionQueue.DEFAULT_MESSAGE_SYNC_INTERVAL);
      }
      FunctionService.registerFunction(new PRContainsValueFunction());
      FunctionService.registerFunction(new HDFSLastCompactionTimeFunction());
      FunctionService.registerFunction(new HDFSForceCompactionFunction());
      FunctionService.registerFunction(new HDFSFlushQueueFunction());
      this.expirationScheduler = new ExpirationScheduler(this.system);

      // uncomment following line when debugging CacheExistsException
      if (DEBUG_CREATION_STACK) {
        this.creationStack = new Exception(LocalizedStrings.GemFireCache_CREATED_GEMFIRECACHE_0.toLocalizedString(toString()));
      }

      if (xmlParameterizationEnabled) {
        /** If gemfire prperties file is available replace properties from there */
        Properties userProps = this.system.getConfig().getUserDefinedProps();
        if (userProps != null && !userProps.isEmpty()) {
          resolver = new CacheXmlPropertyResolver(false,
              PropertyResolver.NO_SYSTEM_PROPERTIES_OVERRIDE, userProps);
        } else {
          resolver = new CacheXmlPropertyResolver(false,
              PropertyResolver.NO_SYSTEM_PROPERTIES_OVERRIDE, null);
        }
      }
    } // synchronized
  }

  final RawValueFactory getRawValueFactory() {
    return FactoryStatics.rawValueFactory;
  }

  /**
   * Used by unit tests to force cache creation to use a test generated cache.xml
   */
  public static File testCacheXml = null;

  /**
   * @return true if cache is created using a ClientCacheFactory
   * @see #hasPool()
   */
  public final boolean isClient() {
    return this.isClient;
  }

  /**
   * Method to check for GemFire client. In addition to checking for ClientCacheFactory, this method checks for any
   * defined pools.
   *
   * @return true if the cache has pools declared
   */
  public boolean hasPool() {
    return this.isClient || !getAllPools().isEmpty();
  }

  private Collection<Pool> getAllPools() {
    Collection<Pool> pools = PoolManagerImpl.getPMI().getMap().values();
    for (Iterator<Pool> itr = pools.iterator(); itr.hasNext();) {
      PoolImpl pool = (PoolImpl) itr.next();
      if (pool.isUsedByGateway()) {
        itr.remove();
      }
    }
    return pools;
  }

  /**
   * May return null (even on a client).
   */
  public Pool getDefaultPool() {
    return this.defaultPool;
  }

  private void setDefaultPool(Pool v) {
    this.defaultPool = v;
  }

  /**
   * Perform initialization, solve the early escaped reference problem by putting publishing references to this instance
   * in this method (vs. the constructor).
   *
   * @return the initialized instance of the cache
   */
  protected GemFireCacheImpl init() {
    ClassPathLoader.setLatestToDefault();
        
    SystemMemberCacheEventProcessor.send(this, Operation.CACHE_CREATE);
    this.resourceAdvisor.initializationGate();

    // moved this after initializeDeclarativeCache because in the future
    // distributed system creation will not happen until we have read
    // cache.xml file.
    // For now this needs to happen before cache.xml otherwise
    // we will not be ready for all the events that cache.xml
    // processing can deliver (region creation, etc.).
    // This call may need to be moved inside initializeDeclarativeCache.
    /** Entry to GemFire Management service **/
    this.jmxAdvisor.initializationGate();
    system.handleResourceEvent(ResourceEvent.CACHE_CREATE, this);
    boolean completedCacheXml = false;
    try {
      initializeDeclarativeCache();
      completedCacheXml = true;
    } finally {
      if (!completedCacheXml) {
        // so initializeDeclarativeCache threw an exception
        try {
          close(); // fix for bug 34041
        } catch (Throwable ignore) {
          // I don't want init to throw an exception that came from the close.
          // I want it to throw the original exception that came from initializeDeclarativeCache.
        }
      }
    }

    this.clientpf = null;
    
    if (FactoryStatics.systemCallbacks != null) {
      if (!FactoryStatics.systemCallbacks.isAdmin()) {
        new JarDeployer(getLogger(), this.system.getConfig()
            .getDeployWorkingDir()).loadPreviouslyDeployedJars();
      }
    }
    else {
      new JarDeployer(getLogger(), this.system.getConfig()
          .getDeployWorkingDir()).loadPreviouslyDeployedJars();
    }

    startColocatedJmxManagerLocator();

    startMemcachedServer();
    addMemoryManagerStats();

    return this;
  }

  private void addMemoryManagerStats() {
    MemoryManagerStats stats = new MemoryManagerStats(this.getDistributedSystem(), "MemoryManagerStats");
    CallbackFactoryProvider.getStoreCallbacks().initMemoryStats(stats);
  }

  private void startMemcachedServer() {
    int port = system.getConfig().getMemcachedPort();
    if (port != 0) {
      String protocol = system.getConfig().getMemcachedProtocol();
      assert protocol != null;
      getLoggerI18n().info(LocalizedStrings
          .GemFireCacheImpl_STARTING_GEMFIRE_MEMCACHED_SERVER_ON_PORT_0_FOR_1_PROTOCOL,
              new Object[] { port, protocol });
      this.memcachedServer = new GemFireMemcachedServer(port,
          Protocol.valueOf(protocol.toUpperCase()));
      this.memcachedServer.start();
    }
  }

  public URL getCacheXmlURL() {
    if (this.getMyId().getVmKind() == DistributionManager.LOCATOR_DM_TYPE) {
      return null;
    }
    File xmlFile = testCacheXml;
    if (xmlFile == null) {
      xmlFile = this.system.getConfig().getCacheXmlFile();
    }
    if ("".equals(xmlFile.getName())) {
      return null;
    }

    URL url = null;
    if (!xmlFile.exists() || !xmlFile.isFile()) {
      // do a resource search
      String resource = xmlFile.getPath();
      resource = resource.replaceAll("\\\\", "/");
      if (resource.length() > 1 && resource.startsWith("/")) {
        resource = resource.substring(1);
      }
      url = ClassPathLoader.getLatest().getResource(getClass(), resource);
    } else {
      try {
        url = xmlFile.toURL();
      } catch (IOException ex) {
        throw new CacheXmlException(
            LocalizedStrings.GemFireCache_COULD_NOT_CONVERT_XML_FILE_0_TO_AN_URL.toLocalizedString(xmlFile), ex);
      }
    }
    if (url == null) {
      File defaultFile = DistributionConfig.DEFAULT_CACHE_XML_FILE;
      if (!xmlFile.equals(defaultFile)) {
        if (!xmlFile.exists()) {
          throw new CacheXmlException(LocalizedStrings.GemFireCache_DECLARATIVE_CACHE_XML_FILERESOURCE_0_DOES_NOT_EXIST
              .toLocalizedString(xmlFile));
        } else /* if (!xmlFile.isFile()) */{
          throw new CacheXmlException(LocalizedStrings.GemFireCache_DECLARATIVE_XML_FILE_0_IS_NOT_A_FILE.toLocalizedString(xmlFile));
        }
      }
    }

    return url;
  }

  /**
   * Initializes the contents of this <code>Cache</code> according to the declarative caching XML file specified by the
   * given <code>DistributedSystem</code>. Note that this operation cannot be performed in the constructor because
   * creating regions in the cache, etc. uses the cache itself (which isn't initialized until the constructor returns).
   *
   * @throws CacheXmlException
   *           If something goes wrong while parsing the declarative caching XML file.
   * @throws TimeoutException
   *           If a {@link com.gemstone.gemfire.cache.Region#put(Object, Object)}times out while initializing the cache.
   * @throws CacheWriterException
   *           If a <code>CacheWriterException</code> is thrown while initializing the cache.
   * @throws RegionExistsException
   *           If the declarative caching XML file desribes a region that already exists (including the root region).
   * @throws GatewayException
   *           If a <code>GatewayException</code> is thrown while initializing the cache.
   *           
   * @see #loadCacheXml
   */
  private void initializeDeclarativeCache() throws TimeoutException, CacheWriterException, GatewayException, RegionExistsException {
    URL url = getCacheXmlURL();
    String cacheXmlDescription = this.cacheConfig.getCacheXMLDescription();
    if (url == null && cacheXmlDescription == null) {
      if (isClient()) {
        determineDefaultPool();
        initializeClientRegionShortcuts(this);
      } else {
        initializeRegionShortcuts(this);
      }
      initializePdxRegistry();
      readyDynamicRegionFactory();
      return; // nothing needs to be done
    }

    try {
      InputStream stream = null;
      if (cacheXmlDescription != null) {
        if (getLoggerI18n().finerEnabled()) {
          getLoggerI18n().finer("initializing cache with generated XML: " + cacheXmlDescription);
        }
        stream = new StringBufferInputStream(cacheXmlDescription);
      } else {
        stream = url.openStream();
      }
      loadCacheXml(stream);
      try {
        stream.close();
      } catch (IOException ignore) {
      }
      if (cacheXmlDescription == null) {
        StringBuilder sb = new StringBuilder();
        try {
          final String EOLN = System.getProperty("line.separator");
          BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
          String l = br.readLine();
          while (l != null) {
            if (!l.isEmpty()) {
              sb.append(EOLN).append(l);
            }
            l = br.readLine();
          }
          br.close();
        } catch (IOException ignore) {
        }
        getLoggerI18n().config(LocalizedStrings.GemFireCache_CACHE_INITIALIZED_USING__0__1, new Object[] {url.toString(), sb.toString()});
      } else {
        getLoggerI18n().config(LocalizedStrings.GemFireCache_CACHE_INITIALIZED_USING__0__1, new Object[] {"generated description from old cache", cacheXmlDescription});
      }
    } catch (IOException ex) {
      throw new CacheXmlException(LocalizedStrings.GemFireCache_WHILE_OPENING_CACHE_XML_0_THE_FOLLOWING_ERROR_OCCURRED_1
          .toLocalizedString(new Object[] { url.toString(), ex }));

    } catch (CacheXmlException ex) {
      CacheXmlException newEx = new CacheXmlException(LocalizedStrings.GemFireCache_WHILE_READING_CACHE_XML_0_1
          .toLocalizedString(new Object[] { url, ex.getMessage() }));
      newEx.setStackTrace(ex.getStackTrace());
      newEx.initCause(ex.getCause());
      throw newEx;
    }
  }

  public synchronized void initializePdxRegistry() {
    if (this.pdxRegistry == null) {
      // Check to see if this member is allowed to have a pdx registry
      if (this.getMyId().getVmKind() == DistributionManager.LOCATOR_DM_TYPE) {
        // locators can not have a pdx registry.
        // If this changes in the future then dunit needs to change
        // to do a clear on the pdx registry in the locator.
        // Otherwise the existence of the type in the locators pdxRegistry
        // cause other members on startup to have non-persistent registries.
        return;
      }
      this.pdxRegistry = new TypeRegistry(this);
      this.pdxRegistry.initialize();
    }
  }

  /**
   * Call to make this vm's dynamic region factory ready. Public so it can be called from CacheCreation during xml
   * processing
   */
  public void readyDynamicRegionFactory() {
    try {
      ((DynamicRegionFactoryImpl) DynamicRegionFactory.get()).internalInit(this);
    } catch (CacheException ce) {
      throw new GemFireCacheException(LocalizedStrings.GemFireCache_DYNAMIC_REGION_INITIALIZATION_FAILED.toLocalizedString(), ce);
    }
  }

  /**
   * create diskstore factory with default attributes
   *
   * @since prPersistSprint2
   */
  public DiskStoreFactory createDiskStoreFactory() {
    return new DiskStoreFactoryImpl(this);
  }

  /**
   * create diskstore factory with predefined attributes
   *
   * @since prPersistSprint2
   */
  public DiskStoreFactory createDiskStoreFactory(DiskStoreAttributes attrs) {
    return new DiskStoreFactoryImpl(this, attrs);
  }

  // this snapshot is different from snapshot for export.
  // however this can be used for that purpose.
  public boolean snapshotEnabled() {
    // if rowstore return false
    // if snappy return true
    return snapshotEnabledForTest() || DEFAULT_SNAPSHOT_ENABLED;
  }

  public boolean snapshotEnabledForTest() {
    // snapshot should be enabled and if LockingPolicy is RC/RR then it should not be disabled
    return DEFAULT_SNAPSHOT_ENABLED_TEST;
  }

  // currently it will wait for a long time
  // we can have differnt ds or read write locks to avoid waiting of read operations.
  //TODO: As an optimizations we can change the ds and maintain it at cache level and punish writes.
  //return snapshotRVV;
  public Map getSnapshotRVV() {
    try {
      // Wait for all the regions to get initialized before taking snapshot.
      lockForSnapshotRvv.readLock().lock();
      Map<String, Map> snapshot = new HashMap();
      for (LocalRegion region : getApplicationRegions()) {
        if (region.getPartitionAttributes() != null && ((PartitionedRegion)region).isDataStore()
            && ((PartitionedRegion)region).concurrencyChecksEnabled) {
          region.waitForData();
          for (BucketRegion br : ((PartitionedRegion)region).getDataStore().getAllLocalBucketRegions()) {
            // if null then create the rvv for that bucket.!
            // For Initialization case, so that we have all the data before snapshot.
            br.waitForData();
            snapshot.put(br.getFullPath(), br.getVersionVector().getSnapShotOfMemberVersion());
          }
        } else if (region.getVersionVector() != null) {
          // if null then create the rvv for that region.!
          // For Initialization case, so that we have all the data before snapshot.
          region.waitForData();
          snapshot.put(region.getFullPath(), region.getVersionVector().getSnapShotOfMemberVersion());
        }
      }
      return snapshot;
    } finally {
      lockForSnapshotRvv.readLock().unlock();

    }
  }

  public void acquireWriteLockOnSnapshotRvv() {
    lockForSnapshotRvv.writeLock().lock();
  }

  public interface RvvSnapshotTestHook {

    public abstract void notifyTestLock();
    public abstract void notifyOperationLock();
    public abstract void waitOnTestLock();
    public abstract void waitOnOperationLock();
  }


  public  RvvSnapshotTestHook getRvvSnapshotTestHook() {
    return this.testHook;
  }

  public void setRvvSnapshotTestHook(RvvSnapshotTestHook hook) {
    this.testHook = hook;
  }

  public void notifyRvvTestHook() {
    if(null !=this.testHook) {
      this.testHook.notifyTestLock();
    }
  }

  public void notifyRvvSnapshotTestHook() {
    if (null != this.testHook) {
      this.testHook.notifyOperationLock();
    }
  }

  public void waitOnRvvTestHook() {
    if (null != this.testHook) {
      this.testHook.waitOnTestLock();
    }
  }

  public void waitOnRvvSnapshotTestHook() {
    if (null != this.testHook) {
      this.testHook.waitOnOperationLock();
    }
  }




  public interface RowScanTestHook {

    public abstract void notifyTestLock();
    public abstract void notifyOperationLock();
    public abstract void waitOnTestLock();
    public abstract void waitOnOperationLock();
  }


  public  RowScanTestHook getRowScanTestHook() {
    return this.rowScanTestHook;
  }

  public void setRowScanTestHook(RowScanTestHook hook) {
    this.rowScanTestHook = hook;
  }

  public void notifyScanTestHook() {
    if(null !=this.rowScanTestHook) {
      this.rowScanTestHook.notifyTestLock();
    }
  }

  public void notifyRowScanTestHook() {
    if (null != this.rowScanTestHook) {
      this.rowScanTestHook.notifyOperationLock();
    }
  }

  public void waitOnScanTestHook() {
    if (null != this.rowScanTestHook) {
      this.rowScanTestHook.waitOnTestLock();
    }
  }

  public void waitOnRowScanTestHook() {
    if (null != this.rowScanTestHook) {
      this.rowScanTestHook.waitOnOperationLock();
    }
  }

  public void releaseWriteLockOnSnapshotRvv() {
    lockForSnapshotRvv.writeLock().unlock();
  }

  public void lockForSnapshot() {
    this.snapshotLock.writeLock().lock();
  }

  public void releaseSnapshotLocks() {
    this.snapshotLock.writeLock().unlock();
  }

  protected final class Stopper extends CancelCriterion {

    /*
     * (non-Javadoc)
     *
     * @see com.gemstone.gemfire.CancelCriterion#cancelInProgress()
     */
    @Override
    public String cancelInProgress() {
      //String reason = GemFireCacheImpl.this.getDistributedSystem()
      //    .getCancelCriterion().cancelInProgress();
      final DM dm = GemFireCacheImpl.this.system.getDM();
      if (dm != null) {
        String reason;
        if ((reason = dm.getCancelCriterion().cancelInProgress()) != null) {
          return reason;
        }
      }
      else {
        return "No dm";
      }
      if (!GemFireCacheImpl.this.isClosing) {
        return null;
      }
      final Throwable disconnectCause = GemFireCacheImpl.this.disconnectCause;
      if (disconnectCause != null) {
        return disconnectCause.getMessage();
      }
      else {
        return "The cache is closed."; // this + ": closed";
      }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.gemstone.gemfire.CancelCriterion#generateCancelledException(java.lang.Throwable)
     */
    @Override
    public RuntimeException generateCancelledException(Throwable e) {
      String reason = cancelInProgress();
      if (reason == null) {
        return null;
      }
      RuntimeException result = getDistributedSystem().getCancelCriterion().generateCancelledException(e);
      if (result != null) {
        return result;
      }
      if (GemFireCacheImpl.this.disconnectCause == null) {
        // No root cause, specify the one given and be done with it.
        return new CacheClosedException(reason, e);
      }

      if (e == null) {
        // Caller did not specify any root cause, so just use our own.
        return new CacheClosedException(reason, GemFireCacheImpl.this.disconnectCause);
      }

      // Attempt to stick rootCause at tail end of the exception chain.
      Throwable nt = e;
      while (nt.getCause() != null) {
        nt = nt.getCause();
      }
      try {
        nt.initCause(GemFireCacheImpl.this.disconnectCause);
        return new CacheClosedException(reason, e);
      } catch (IllegalStateException e2) {
        // Bug 39496 (Jrockit related) Give up. The following
        // error is not entirely sane but gives the correct general picture.
        return new CacheClosedException(reason, GemFireCacheImpl.this.disconnectCause);
      }
    }
  }

  private final Stopper stopper = new Stopper();

  public final CancelCriterion getCancelCriterion() {
    return stopper;
  }

  /** return true if the cache was closed due to being shunned by other members */
  public boolean forcedDisconnect() {
    return this.forcedDisconnect || this.system.forcedDisconnect();
  }

  /** return a CacheClosedException with the given reason */
  public CacheClosedException getCacheClosedException(String reason, Throwable cause) {
    CacheClosedException result;
    if (cause != null) {
      result = new CacheClosedException(reason, cause);
    } else if (this.disconnectCause != null) {
      result = new CacheClosedException(reason, this.disconnectCause);
    } else {
      result = new CacheClosedException(reason);
    }
    return result;
  }

  /** if the cache was forcibly closed this exception will reflect the cause */
  public Throwable getDisconnectCause() {
    return this.disconnectCause;
  }

  /**
   * Set to true during a cache close if user requested durable subscriptions to be kept.
   *
   * @since 5.7
   */
  private boolean keepAlive;

  /**
   * Returns true if durable subscriptions (registrations and queries) should be preserved.
   *
   * @since 5.7
   */
  public boolean keepDurableSubscriptionsAlive() {
    return this.keepAlive;
  }

  /**
   * break any potential circularity in {@link #loadEmergencyClasses()}
   */
  private static volatile boolean emergencyClassesLoaded = false;

  /**
   * Ensure that all the necessary classes for closing the cache are loaded
   *
   * @see SystemFailure#loadEmergencyClasses()
   */
  static public void loadEmergencyClasses() {
    if (emergencyClassesLoaded)
      return;
    emergencyClassesLoaded = true;
    InternalDistributedSystem.loadEmergencyClasses();
    AcceptorImpl.loadEmergencyClasses();
    GatewayHubImpl.loadEmergencyClasses();
    PoolManagerImpl.loadEmergencyClasses();
  }

  /**
   * Close the distributed system, bridge servers, and gateways. Clears the rootRegions and partitionedRegions map.
   * Marks the cache as closed.
   *
   * @see SystemFailure#emergencyClose()
   */
  static public void emergencyClose() {
    final boolean DEBUG = SystemFailure.TRACE_CLOSE;

    GemFireCacheImpl inst = GemFireCacheImpl.instance;
    if (inst == null) {
      if (DEBUG) {
        System.err.println("GemFireCache#emergencyClose: no instance");
      }
      return;
    }
    // Catenation, don't attempt this log message
    // inst.getLogger().warning("Emergency close of " + inst);

    // any cleanup actions for GemFireXD
    StaticSystemCallbacks sysCb = FactoryStatics.systemCallbacks;
    if (sysCb != null) {
      sysCb.emergencyClose();
    }

    GemFireCacheImpl.instance = null;
    GemFireCacheImpl.pdxInstance = null;
    // leave the PdxSerializer set if we have one to prevent 43412
    // TypeRegistry.setPdxSerializer(null);

    // Shut down messaging first
    InternalDistributedSystem ids = inst.system;
    if (ids != null) {
      if (DEBUG) {
        System.err.println("DEBUG: emergencyClose InternalDistributedSystem");
      }
      ids.emergencyClose();
    }

    inst.isClosing = true;
    inst.disconnectCause = SystemFailure.getFailure();

    // Clear bridge servers
    if (DEBUG) {
      System.err.println("DEBUG: Close bridge servers");
    }
    {
      Iterator allBridgeServersItr = inst.allBridgeServers.iterator();
      while (allBridgeServersItr.hasNext()) {
        BridgeServerImpl bs = (BridgeServerImpl) allBridgeServersItr.next();
        AcceptorImpl ai = bs.getAcceptor();
        if (ai != null) {
          ai.emergencyClose();
        }
      }
    }

    if (DEBUG) {
      System.err.println("DEBUG: closing client resources");
    }
    PoolManagerImpl.emergencyClose();

    if (DEBUG) {
      System.err.println("DEBUG: closing gateway hubs");
    }
    // synchronized (inst.allGatewayHubsLock)
    {
      GatewayHubImpl snap[] = inst.allGatewayHubs;
      for (int i = 0; i < snap.length; i++) {
        snap[i].emergencyClose();
      }
    }

    // These are synchronized sets -- avoid potential deadlocks
    // instance.pathToRegion.clear(); // garbage collection
    // instance.gatewayHubs.clear();

    // rootRegions is intentionally *not* synchronized. The
    // implementation of clear() does not currently allocate objects.
    inst.rootRegions.clear();
    // partitionedRegions is intentionally *not* synchronized, The
    // implementation of clear() does not currently allocate objects.
    inst.partitionedRegions.clear();
    if (DEBUG) {
      System.err.println("DEBUG: done with cache emergency close");
    }
  }

  public final boolean isCacheAtShutdownAll() {
    return isShutDownAll;
  }

  /**
   * Number of threads used to close PRs in shutdownAll. By default is the number of PRs in the cache
   */
  private static final int shutdownAllPoolSize = Integer.getInteger("gemfire.SHUTDOWN_ALL_POOL_SIZE", -1);

  void shutdownSubTreeGracefully(Map<String, PartitionedRegion> prSubMap) {
    for (final PartitionedRegion pr : prSubMap.values()) {
      shutDownOnePRGracefully(pr);
    }
  }

  public synchronized void shutDownAll() {
    boolean testIGE = Boolean.getBoolean("TestInternalGemFireError");

    if (testIGE) {
      InternalGemFireError assErr = new InternalGemFireError(LocalizedStrings.GemFireCache_UNEXPECTED_EXCEPTION.toLocalizedString());
      throw assErr;
    }
    if (isCacheAtShutdownAll()) {
      // it's already doing shutdown by another thread
      return;
    }
    this.isShutDownAll = true;
    LogWriterI18n logger = getLoggerI18n();

    // medco issue (bug 44031) requires multithread shutdownall should be grouped
    // by root region. However, shutDownAllDuringRecovery.conf test revealed that
    // we have to close colocated child regions first.
    // Now check all the PR, if anyone has colocate-with attribute, sort all the
    // PRs by colocation relationship and close them sequentially, otherwise still
    // group them by root region.
    TreeMap<String, Map<String, PartitionedRegion>> prTrees = getPRTrees();
    if (prTrees.size() > 1 && shutdownAllPoolSize != 1) {
      ExecutorService es = getShutdownAllExecutorService(logger, prTrees.size());
      for (final Map<String, PartitionedRegion> prSubMap : prTrees.values()) {
        es.execute(new Runnable() {
          public void run() {
            ConnectionTable.threadWantsSharedResources();
            shutdownSubTreeGracefully(prSubMap);
          }
        });
      } // for each root
      es.shutdown();
      try {
        es.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        logger.info(LocalizedStrings.DEBUG, "shutdown all interrupted while waiting for PRs to be shutdown gracefully.");
      }

    } else {
      for (final Map<String, PartitionedRegion> prSubMap : prTrees.values()) {
        shutdownSubTreeGracefully(prSubMap);
      }
    }

    close("Shut down all members", null, false, true);
  }

  private ExecutorService getShutdownAllExecutorService(LogWriterI18n logger, int size) {
    final ThreadGroup thrGrp = LogWriterImpl.createThreadGroup("ShutdownAllGroup", logger);
    ThreadFactory thrFactory = new ThreadFactory() {
      private final AtomicInteger threadCount = new AtomicInteger(1);

      public Thread newThread(Runnable r) {
        Thread t = new Thread(thrGrp, r, "ShutdownAll-" + threadCount.getAndIncrement());
        t.setDaemon(true);
        return t;
      }
    };
    ExecutorService es = Executors.newFixedThreadPool(shutdownAllPoolSize == -1 ? size : shutdownAllPoolSize, thrFactory);
    return es;
  }

  private void shutDownOnePRGracefully(PartitionedRegion pr) {
    LogWriterI18n logger = getLoggerI18n();
    boolean acquiredLock = false;
    try {
      pr.acquireDestroyLock();
      acquiredLock = true;

      synchronized(pr.getRedundancyProvider()) {
      if (pr.isDataStore() && pr.getDataStore() != null && pr.getDataPolicy().withPersistence()) {
        int numBuckets = pr.getTotalNumberOfBuckets();
        Map<InternalDistributedMember, PersistentMemberID> bucketMaps[] = new Map[numBuckets];
        PartitionedRegionDataStore prds = pr.getDataStore();

        // lock all the primary buckets
        Set<Entry<Integer, BucketRegion>> bucketEntries = prds.getAllLocalBuckets();
        for (Map.Entry e : bucketEntries) {
          BucketRegion br = (BucketRegion) e.getValue();
          if (br == null || br.isDestroyed) {
            // bucket region could be destroyed in race condition
            continue;
          }
          br.getBucketAdvisor().tryLockIfPrimary();

          // get map <InternalDistriutedMemeber, persistentID> for this bucket's
          // remote members
          bucketMaps[br.getId()] = br.getBucketAdvisor().adviseInitializedPersistentMembers();
          if (logger.fineEnabled()) {
            logger.fine("shutDownAll: PR " + pr.getName() + ": initialized persistent members for  " + br.getId() + ":"
                + bucketMaps[br.getId()]);
          }
        }
        if (logger.fineEnabled()) {
          logger.fine("shutDownAll: All buckets for PR " + pr.getName() + " are locked.");
        }

        // send lock profile update to other members
        pr.setShutDownAllStatus(PartitionedRegion.PRIMARY_BUCKETS_LOCKED);
        new UpdateAttributesProcessor(pr).distribute(false);
        pr.getRegionAdvisor().waitForProfileStatus(PartitionedRegion.PRIMARY_BUCKETS_LOCKED);
        if (logger.fineEnabled()) {
          logger.fine("shutDownAll: PR " + pr.getName() + ": all bucketlock profiles received.");
        }

        // if async write, do flush
        if (!pr.getAttributes().isDiskSynchronous()) {
          // several PRs might share the same diskstore, we will only flush once
          // even flush is called several times.
          pr.getDiskStore().forceFlush();
          // send flush profile update to other members
          pr.setShutDownAllStatus(PartitionedRegion.DISK_STORE_FLUSHED);
          new UpdateAttributesProcessor(pr).distribute(false);
          pr.getRegionAdvisor().waitForProfileStatus(PartitionedRegion.DISK_STORE_FLUSHED);
          if (logger.fineEnabled()) {
            logger.fine("shutDownAll: PR " + pr.getName() + ": all flush profiles received.");
          }
        } // async write

        // persist other members to OFFLINE_EQUAL for each bucket region
        // iterate through all the bucketMaps and exclude the items whose
        // idm is no longer online
        Set<InternalDistributedMember> membersToPersistOfflineEqual = pr.getRegionAdvisor().adviseDataStore();
        for (Map.Entry e : bucketEntries) {
          BucketRegion br = (BucketRegion) e.getValue();
          if (br == null || br.isDestroyed) {
            // bucket region could be destroyed in race condition
            continue;
          }
          Map<InternalDistributedMember, PersistentMemberID> persistMap = getSubMapForLiveMembers(pr, membersToPersistOfflineEqual,
              bucketMaps[br.getId()]);
          if (persistMap != null) {
            br.getPersistenceAdvisor().persistMembersOfflineAndEqual(persistMap);
            if (logger.fineEnabled()) {
              logger.fine("shutDownAll: PR " + pr.getName() + ": persisting bucket " + br.getId() + ":" + persistMap);
            }
          }
        }

        // send persited profile update to other members, let all members to persist
        // before close the region
        pr.setShutDownAllStatus(PartitionedRegion.OFFLINE_EQUAL_PERSISTED);
        new UpdateAttributesProcessor(pr).distribute(false);
        pr.getRegionAdvisor().waitForProfileStatus(PartitionedRegion.OFFLINE_EQUAL_PERSISTED);
        if (logger.fineEnabled()) {
          logger.fine("shutDownAll: PR " + pr.getName() + ": all offline_equal profiles received.");
        }
      } // datastore

      // after done all steps for buckets, close pr
      // close accessor directly
      RegionEventImpl event = new RegionEventImpl(pr, Operation.REGION_CLOSE,
          null, false, getMyId(), true);
      try {
        // not to acquire lock
        pr.basicDestroyRegion(event, false, false, true);
      } catch (CacheWriterException e) {
        // not possible with local operation, CacheWriter not called
        throw new Error(LocalizedStrings.LocalRegion_CACHEWRITEREXCEPTION_SHOULD_NOT_BE_THROWN_IN_LOCALDESTROYREGION
            .toLocalizedString(), e);
      } catch (TimeoutException e) {
        // not possible with local operation, no distributed locks possible
        throw new Error(LocalizedStrings.LocalRegion_TIMEOUTEXCEPTION_SHOULD_NOT_BE_THROWN_IN_LOCALDESTROYREGION
            .toLocalizedString(), e);
      }
      // pr.close();
      } // synchronized
    } catch (CacheClosedException cce) {
      logger.fine("Encounter CacheClosedException when shutDownAll is closing PR: "
          + pr.getFullPath() + ":" + cce.getMessage());
    } catch (CancelException ce) {
      logger.fine("Encounter CancelException when shutDownAll is closing PR: "
          + pr.getFullPath() + ":" + ce.getMessage());
    } catch (RegionDestroyedException rde) {
      logger.fine("Encounter CacheDestroyedException when shutDownAll is closing PR: "
          + pr.getFullPath() + ":" + rde.getMessage());
    } finally {
      if (acquiredLock) {
        pr.releaseDestroyLock();
      }
    }
  }

  private Map<InternalDistributedMember, PersistentMemberID> getSubMapForLiveMembers(PartitionedRegion pr,
      Set<InternalDistributedMember> membersToPersistOfflineEqual, Map<InternalDistributedMember, PersistentMemberID> bucketMap) {
    if (bucketMap == null) {
      return null;
    }
    Map<InternalDistributedMember, PersistentMemberID> persistMap = new HashMap();
    Iterator itor = membersToPersistOfflineEqual.iterator();
    while (itor.hasNext()) {
      InternalDistributedMember idm = (InternalDistributedMember) itor.next();
      if (bucketMap.containsKey(idm)) {
        persistMap.put(idm, bucketMap.get(idm));
      }
    }
    return persistMap;
  }

  public void close() {
    close(false);
  }

  public void close(boolean keepalive) {
    close("Normal disconnect", null, keepalive, false);
  }

  public void close(String reason, Throwable optionalCause) {
    close(reason, optionalCause, false, false);
  }

  /**
   * Gets or lazily creates the PartitionedRegion distributed lock service. This call will synchronize on this
   * GemFireCache.
   *
   * @return the PartitionedRegion distributed lock service
   */
  protected final DistributedLockService getPartitionedRegionLockService() {
    synchronized (this.prLockServiceLock) {
      stopper.checkCancelInProgress(null);
      if (this.prLockService == null) {
        try {
          this.prLockService = DLockService.create(PartitionedRegionHelper.PARTITION_LOCK_SERVICE_NAME, getDistributedSystem(),
              true /* distributed */, true /* destroyOnDisconnect */, true /* automateFreeResources */);
        } catch (IllegalArgumentException e) {
          this.prLockService = DistributedLockService.getServiceNamed(PartitionedRegionHelper.PARTITION_LOCK_SERVICE_NAME);
          if (this.prLockService == null) {
            throw e; // PARTITION_LOCK_SERVICE_NAME must be illegal!
          }
        }
      }
      return this.prLockService;
    }
  }

  /**
   * Destroys the PartitionedRegion distributed lock service when closing the cache. Caller must be synchronized on this
   * GemFireCache.
   */
  private void destroyPartitionedRegionLockService() {
    try {
      DistributedLockService.destroy(PartitionedRegionHelper.PARTITION_LOCK_SERVICE_NAME);
    } catch (IllegalArgumentException e) {
      // DistributedSystem.disconnect may have already destroyed the DLS
    }
  }

  public HeapEvictor getHeapEvictor() {
    synchronized (this.heapEvictorLock) {
      stopper.checkCancelInProgress(null);
      if (this.heapEvictor == null) {
        this.heapEvictor = new HeapEvictor(this);
      }
      return this.heapEvictor;
    }
  }

  public OffHeapEvictor getOffHeapEvictor() {
    synchronized (this.offHeapEvictorLock) {
      stopper.checkCancelInProgress(null);
      if (this.offHeapEvictor == null) {
        this.offHeapEvictor = new OffHeapEvictor(this);
      }
      return this.offHeapEvictor;
    }    
  }
  
  public final PersistentMemberManager getPersistentMemberManager() {
    return persistentMemberManager;
  }

  public ClientMetadataService getClientMetadataService() {
    synchronized (this.clientMetaDatServiceLock) {
      stopper.checkCancelInProgress(null);
      if (this.clientMetadatService == null) {
        this.clientMetadatService = new ClientMetadataService(this);
      }
      return this.clientMetadatService;
    }
  }

  private final boolean DISABLE_DISCONNECT_DS_ON_CACHE_CLOSE = Boolean.getBoolean("gemfire.DISABLE_DISCONNECT_DS_ON_CACHE_CLOSE");

  /**
   * close the cache
   *
   * @param reason
   *          the reason the cache is being closed
   * @param systemFailureCause
   *          whether this member was ejected from the distributed system
   * @param keepalive
   *          whoever added this should javadoc it
   */
  public void close(String reason, Throwable systemFailureCause, boolean keepalive) {
    close(reason, systemFailureCause, keepalive, false);
  }

  public void close(String reason, Throwable systemFailureCause, boolean keepalive, boolean keepDS) {
    if (isClosed()) {
      return;
    }
    synchronized (GemFireCacheImpl.class) {
      // bugfix for bug 36512 "GemFireCache.close is not thread safe"
      // ALL CODE FOR CLOSE SHOULD NOW BE UNDER STATIC SYNCHRONIZATION
      // OF synchronized (GemFireCache.class) {
      // static synchronization is necessary due to static resources
      if (isClosed()) {
        return;
      }

      if (oldEntryMapCleanerService != null) {
        oldEntryMapCleanerService.shutdownNow();
      }

      /**
       * First close the ManagementService as it uses a lot of infra which will be closed by cache.close()
       **/
      system.handleResourceEvent(ResourceEvent.CACHE_REMOVE, this);
      if (this.listener != null) {
        this.system.removeResourceListener(listener);
        this.listener = null;
      }

      isClosing = true;

      if (systemFailureCause != null) {
        this.forcedDisconnect = systemFailureCause instanceof ForcedDisconnectException;
        if (this.forcedDisconnect) {
          this.disconnectCause = new ForcedDisconnectException(reason);
        } else {
          this.disconnectCause = systemFailureCause;
        }
      }

      this.getLoggerI18n().info(LocalizedStrings.GemFireCache_0_NOW_CLOSING, this);

      // Before anything else...make sure that this instance is not
      // available to anyone "fishing" for a cache...
      if (GemFireCacheImpl.instance == this) {
        GemFireCacheImpl.instance = null;
      }

      // we don't clear the prID map if there is a system failure. Other
      // threads may be hung trying to communicate with the map locked
      if (systemFailureCause == null) {
        PartitionedRegion.clearPRIdMap();
      }
      TXStateInterface tx = null;
      try {
        this.keepAlive = keepalive;
        PoolManagerImpl.setKeepAlive(keepalive);

        if (this.txMgr != null) {
          //this.txIdAdvisor.close();
          tx = this.txMgr.internalSuspend();
        }

        // do this before closing regions
        resourceManager.close();

        try {
          this.resourceAdvisor.close();
        } catch (CancelException e) {
          // ignore
        } 
        try {
          this.jmxAdvisor.close();
        } catch (CancelException e) {
          // ignore
        }

        try {
          GatewaySenderAdvisor advisor = null;
          for (GatewaySender sender : this.getAllGatewaySenders()) {
            ((AbstractGatewaySender) sender).stop();
            advisor = ((AbstractGatewaySender) sender).getSenderAdvisor();
            if (advisor != null) {
              if (getLogger().fineEnabled()) {
                getLogger().fine("Stopping the GatewaySender advisor");
              }
              advisor.close();
            }
          }
        } catch (CancelException ce) {

        }
        if (ASYNC_EVENT_LISTENERS) {
          this.getLogger().fine(this.toString() + ": stopping event thread pool...");
          this.eventThreadPool.shutdown();
        }

        /*
         * IMPORTANT: any operation during shut down that can time out (create a CancelException) must be inside of this
         * try block. If all else fails, we *must* ensure that the cache gets closed!
         */
        try {
          this.stopServers();

          stopMemcachedServer();

          // no need to track PR instances since we won't create any more
          // bridgeServers or gatewayHubs
          if (this.partitionedRegions != null) {
            this.getLogger().fine(this.toString() + ": clearing partitioned regions...");
            synchronized (this.partitionedRegions) {
              int prSize = -this.partitionedRegions.size();
              this.partitionedRegions.clear();
              getCachePerfStats().incPartitionedRegions(prSize);
            }
          }

          prepareDiskStoresForClose();
          if (GemFireCacheImpl.pdxInstance == this) {
            GemFireCacheImpl.pdxInstance = null;
          }

          List rootRegionValues = null;
          synchronized (this.rootRegions) {
            rootRegionValues = new ArrayList(this.rootRegions.values());
          }
          {
            final Operation op;
            if (this.forcedDisconnect) {
              op = Operation.FORCED_DISCONNECT;
            } else if (isReconnecting()) {
              op = Operation.CACHE_RECONNECT;
            } else {
              op = Operation.CACHE_CLOSE;
            }

            LocalRegion prRoot = null;
            
            for (Iterator itr = rootRegionValues.iterator(); itr.hasNext();) {
              LocalRegion lr = (LocalRegion) itr.next();
              this.getLogger().fine(this.toString() + ": processing region " + lr.getFullPath());
              if (PartitionedRegionHelper.PR_ROOT_REGION_NAME.equals(lr.getName())) {
                prRoot = lr;
              } else {
                if(lr.getName().contains(ParallelGatewaySenderQueue.QSTRING)){
                  continue; //this region will be closed internally by parent region
                }
                this.getLogger().fine(this.toString() + ": closing region " + lr.getFullPath() + "...");
                try {
                  lr.handleCacheClose(op);
                } catch (Exception e) {
                  if (this.getLogger().fineEnabled() || !forcedDisconnect) {
                    this.getLoggerI18n().warning(LocalizedStrings.GemFireCache_0_ERROR_CLOSING_REGION_1,
                        new Object[] { this, lr.getFullPath() }, e);
                  }
                }
              }
            } // for

            try {
              this.getLogger().fine(this.toString() + ": finishing partitioned region close...");
              PartitionedRegion.afterRegionsClosedByCacheClose(this);
              if (prRoot != null) {
                // do the PR meta root region last
                prRoot.handleCacheClose(op);
              }
            } catch (CancelException e) {
              this.getLoggerI18n().warning(LocalizedStrings.GemFireCache_0_ERROR_IN_LAST_STAGE_OF_PARTITIONEDREGION_CACHE_CLOSE,
                  this, e);
            }
            this.cachedPRRoot = null;

            destroyPartitionedRegionLockService();
          }

          closeDiskStores();
          
          closeHDFSStores();
          
          // Close the CqService Handle.
          try {
            this.getLogger().fine(this.toString() + ": closing CQ service...");
            CqService.closeCqService();
          } catch (Exception ex) {
            getLoggerI18n().info(LocalizedStrings.GemFireCache_FAILED_TO_GET_THE_CQSERVICE_TO_CLOSE_DURING_CACHE_CLOSE_1);
          }

          PoolManager.close(keepalive);

          this.getLogger().fine(this.toString() + ": closing reliable message queue...");
          try {
            getReliableMessageQueueFactory().close(true);
          } catch (CancelException e) {
            if (this.getLogger().fineEnabled()) {
              this.getLogger().fine("Ignored cancellation while closing reliable message queue", e);
            }
          }

          this.getLogger().fine(this.toString() + ": notifying admins of close...");
          try {
            SystemMemberCacheEventProcessor.send(this, Operation.CACHE_CLOSE);
          } catch (CancelException e) {
            if (this.getLogger().fineEnabled()) {
              this.getLogger().fine("Ignored cancellation while notifying admins");
            }
          }

          this.getLogger().fine(this.toString() + ": stopping destroyed entries processor...");
          this.tombstoneService.stop();

          // NOTICE: the CloseCache message is the *last* message you can send!
          DM dm = null;
          try {
            dm = system.getDistributionManager();
            dm.removeMembershipListener(this.txMgr);
          } catch (CancelException e) {
            // dm = null;
          }

          if (dm != null) { // Send CacheClosedMessage (and NOTHING ELSE) here
            this.getLogger().fine(this.toString() + ": sending CloseCache to peers...");
            Set otherMembers = dm.getOtherDistributionManagerIds();
            ReplyProcessor21 processor = new ReplyProcessor21(system, otherMembers);
            CloseCacheMessage msg = new CloseCacheMessage();
            // [bruce] if multicast is available, use it to send the message to
            // avoid race conditions with cache content operations that might
            // also be multicast
            msg.setMulticast(system.getConfig().getMcastPort() != 0);
            msg.setRecipients(otherMembers);
            msg.setProcessorId(processor.getProcessorId());
            dm.putOutgoing(msg);
            try {
              processor.waitForReplies();
            } catch (InterruptedException ex) {
              // Thread.currentThread().interrupt(); // TODO ??? should we reset this bit later?
              // Keep going, make best effort to shut down.
            } catch (ReplyException ex) {
              // keep going
            }
            // set closed state after telling others and getting responses
            // to avoid complications with others still in the process of
            // sending messages
          }
          // NO MORE Distributed Messaging AFTER THIS POINT!!!!

          {
            ClientMetadataService cms = this.clientMetadatService;
            if (cms != null) {
              cms.close();
            }
            HeapEvictor he = this.heapEvictor;
            if (he != null) {
              he.close();
            }
          }
        } catch (CancelException e) {
          // make sure the disk stores get closed
          closeDiskStores();
          closeHDFSStores();
          // NO DISTRIBUTED MESSAGING CAN BE DONE HERE!

          // okay, we're taking too long to do this stuff, so let's
          // be mean to other processes and skip the rest of the messaging
          // phase
          // [bruce] the following code is unnecessary since someone put the
          // same actions in a finally block
          // getLogger().fine("GemFire shutdown is being accelerated");
          // if (!this.closed) {
          // this.closed = true;
          // this.txMgr.close();
          // if (GemFireCache.instance == this) {
          // GemFireCache.instance = null;
          // }
          // ((DynamicRegionFactoryImpl)DynamicRegionFactory.get()).close();
          // }
        }

        // Close the CqService Handle.
        try {
          CqService.closeCqService();
        } catch (Exception ex) {
          getLoggerI18n().info(LocalizedStrings.GemFireCache_FAILED_TO_GET_THE_CQSERVICE_TO_CLOSE_DURING_CACHE_CLOSE_2);
        }

        this.cachePerfStats.close();

        EventTracker.stopTrackerServices(this);

        synchronized (ccpTimerMutex) {
          if (this.ccpTimer != null) {
            this.ccpTimer.cancel();
          }
        }

        this.expirationScheduler.cancel();

        // Stop QueryMonitor if running.
        if (this.queryMonitor != null) {
          this.queryMonitor.stopMonitoring();
        }   

      } finally {
        try {
          // NO DISTRIBUTED MESSAGING CAN BE DONE HERE!
          if (this.txMgr != null) {
            this.txMgr.close();
          }
          ((DynamicRegionFactoryImpl) DynamicRegionFactory.get()).close();
          if (this.txMgr != null) {
            this.txMgr.resume(tx);
          }
        } 
        finally {
          //See #50072
          if (this.txMgr != null) {
            this.txMgr.release();
          }
        }
      }
      // Added to close the TransactionManager's cleanup thread
      TransactionManagerImpl.refresh();

      if (!keepDS) {
        // keepDS is used by ShutdownAll. It will override DISABLE_DISCONNECT_DS_ON_CACHE_CLOSE
        if (!DISABLE_DISCONNECT_DS_ON_CACHE_CLOSE) {
          this.system.disconnect();
        }
      }
      TypeRegistry.close();
      // do this late to prevent 43412
      TypeRegistry.setPdxSerializer(null);

      this.bufferAllocator.close();

      // Added to reset the memory manager to handle cases where only cache is closed.
      // Right now mostly in DUNITs
      CallbackFactoryProvider.getStoreCallbacks().resetMemoryManager();

      for (Iterator iter = cacheLifecycleListeners.iterator(); iter.hasNext();) {
        CacheLifecycleListener listener = (CacheLifecycleListener) iter.next();
        listener.cacheClosed(this);
      }
    } // static synchronization on GemFireCache.class
  }
  
  // see Cache.isReconnecting()
  public boolean isReconnecting() {
    return this.system.isReconnecting();
  }

  // see Cache.waitUntilReconnected(long, TimeUnit)
  public boolean waitUntilReconnected(long time, TimeUnit units) throws InterruptedException {
    return this.system.waitUntilReconnected(time,  units);
  }
  
  // see Cache.stopReconnecting()
  public void stopReconnecting() {
    this.system.stopReconnecting();
  }
  
  // see Cache.getReconnectedCache()
  public Cache getReconnectedCache() {
    Cache c = GemFireCacheImpl.getInstance();
    if (c == this) {
      c = null;
    }
    return c;
  }

  private void stopMemcachedServer() {
    if (this.memcachedServer != null) {
      getLoggerI18n().info(LocalizedStrings.GemFireCacheImpl_MEMCACHED_SERVER_ON_PORT_0_IS_SHUTTING_DOWN,
          new Object[] { this.system.getConfig().getMemcachedPort() });
      this.memcachedServer.shutdown();
    }
  }

  private void prepareDiskStoresForClose() {
    String pdxDSName = TypeRegistry.getPdxDiskStoreName(this);
    DiskStoreImpl pdxdsi = null;
    for (DiskStoreImpl dsi : this.diskStores.values()) {
      if (dsi.getName().equals(pdxDSName)) {
        pdxdsi = dsi;
      } else {
        dsi.prepareForClose();
      }
    }
    if (pdxdsi != null) {
      pdxdsi.prepareForClose();
    }
  }

  private final ConcurrentMap<String, DiskStoreImpl> diskStores = new ConcurrentHashMap<String, DiskStoreImpl>();
  private final ConcurrentMap<String, DiskStoreImpl> regionOwnedDiskStores = new ConcurrentHashMap<String, DiskStoreImpl>();
  
  public void addDiskStore(DiskStoreImpl dsi) {
    this.diskStores.put(dsi.getName(), dsi);
  }

  public void removeDiskStore(DiskStoreImpl dsi) {
    this.diskStores.remove(dsi.getName());
    this.regionOwnedDiskStores.remove(dsi.getName());
    /** Added for M&M **/
    if(!dsi.getOwnedByRegion())
    system.handleResourceEvent(ResourceEvent.DISKSTORE_REMOVE, dsi);
  }

  public void addRegionOwnedDiskStore(DiskStoreImpl dsi) {
    this.regionOwnedDiskStores.put(dsi.getName(), dsi);
  }

  public void closeDiskStores() {
    Iterator<DiskStoreImpl> it = this.diskStores.values().iterator();
    while (it.hasNext()) {
      try {
        DiskStoreImpl dsi = it.next();
        getLogger().info("closing " + dsi);
        dsi.close();
        /** Added for M&M **/
        system.handleResourceEvent(ResourceEvent.DISKSTORE_REMOVE, dsi);
      } catch (Exception e) {
        getLoggerI18n().severe(LocalizedStrings.Disk_Store_Exception_During_Cache_Close, e);
      }
      it.remove();
    }
  }

  public void setVMIDRegionPath(String regionPath) {
    this.vmIdRegionPath = regionPath;
  }

  public String getVMIDRegionPath() {
    return this.vmIdRegionPath;
  }

  /**
   * Used by GemFireXD and unit tests to allow them to change the default disk
   * store name.
   */
  public static void setDefaultDiskStoreName(String dsName) {
    DEFAULT_DS_NAME = dsName;
  }

  /**
   * Used by unit tests to undo a change to the default disk store name.
   */
  public static void unsetDefaultDiskStoreName() {
    DEFAULT_DS_NAME = DiskStoreFactory.DEFAULT_DISK_STORE_NAME;
  }

  public static String getDefaultDiskStoreName() {
    return DEFAULT_DS_NAME;
  }

  public static String DEFAULT_DS_NAME = DiskStoreFactory.DEFAULT_DISK_STORE_NAME;

  public final DiskStoreImpl getOrCreateDefaultDiskStore() {
    DiskStoreImpl result = (DiskStoreImpl) findDiskStore(null);
    if (result == null) {
      synchronized (this) {
        result = (DiskStoreImpl) findDiskStore(null);
        if (result == null) {
          result = (DiskStoreImpl) createDiskStoreFactory().create(DEFAULT_DS_NAME);
        }
      }
    }
    return result;
  }

  /**
   * Returns the DiskStore by name
   *
   * @since prPersistSprint2
   */
  public final DiskStoreImpl findDiskStore(String name) {
    if (name == null) {
      name = DEFAULT_DS_NAME;
    }
    return this.diskStores.get(name);
  }

  /**
   * Returns the DiskStore list
   *
   * @since prPersistSprint2
   */
  public Collection<DiskStoreImpl> listDiskStores() {
    return Collections.unmodifiableCollection(this.diskStores.values());
  }

  public Collection<DiskStoreImpl> listDiskStoresIncludingDefault() {
    return Collections.unmodifiableCollection(listDiskStores());
  }

  public Collection<DiskStoreImpl> listDiskStoresIncludingRegionOwned() {
    HashSet<DiskStoreImpl> allDiskStores = new HashSet<DiskStoreImpl>();
    allDiskStores.addAll(this.diskStores.values());
    allDiskStores.addAll(this.regionOwnedDiskStores.values());
    return allDiskStores;
  }

 /* private static class DiskStoreFuture extends FutureTask {
    private final DiskStoreTask task;

    public DiskStoreFuture(DiskStoreTask r) {
      super(r, null);
      this.task = r;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      boolean result = super.cancel(mayInterruptIfRunning);
      if (result) {
        task.taskCancelled();
      }
      return result;
    }

  }*/

  public int stopGatewayHubs(boolean byShutdownAll) {
    int cnt = 0;
    closingGatewayHubsByShutdownAll = byShutdownAll;
    synchronized (allGatewayHubsLock) {
      for (int i = 0; i < allGatewayHubs.length; i++) {
        GatewayHubImpl hub = allGatewayHubs[i];
        this.getLogger().fine(this.toString() + ": stopping gateway hub " + hub);
        try {
          hub.stop();
          cnt++;
        } catch (CancelException e) {
          if (this.getLogger().fineEnabled()) {
            this.getLogger().fine("Ignored cache closure while closing hub " + hub, e);
          }
        }
      }
    } // synchronized

    this.getLogger().fine(this.toString() + ": finished stopping " + cnt + " gateway hub(s), total is " + allGatewayHubs.length);
    return cnt;
  }
  
  public int stopGatewaySenders(boolean byShutdownAll) {
    int cnt = 0;
    closingGatewaySendersByShutdownAll = byShutdownAll;
    synchronized (allGatewaySendersLock) {
      GatewaySenderAdvisor advisor = null;
      Iterator<GatewaySender> itr = allGatewaySenders.iterator();
      while (itr.hasNext()) {
        GatewaySender sender = itr.next();
        this.getLogger().fine(
            this.toString() + ": stopping gateway sender " + sender);
        try {
          sender.stop();
          advisor = ((AbstractGatewaySender)sender).getSenderAdvisor();
          if (advisor != null) {
            if (getLogger().fineEnabled()) {
              getLogger().fine("Stopping the GatewaySender advisor");
            }
            advisor.close();
          }
          cnt++;
        }
        catch (CancelException e) {
          if (this.getLogger().fineEnabled()) {
            this.getLogger().fine(
                "Ignored cache closure while closing sender " + sender, e);
          }
        }
      }
    } // synchronized

    this.getLogger().fine(
        this.toString() + ": finished stopping " + cnt
            + " gateway sender(s), total is " + allGatewaySenders.size());
    return cnt;
  }
  
  public int stopGatewayReceivers(boolean byShutdownAll) {
    int cnt = 0;
    closingGatewayReceiversByShutdownAll = byShutdownAll;
    synchronized (allGatewayReceiversLock) {
      Iterator<GatewayReceiver> itr = allGatewayReceivers.iterator();
      while (itr.hasNext()) {
        GatewayReceiver receiver = itr.next();
        this.getLogger().fine(
            this.toString() + ": stopping gateway receiver " + receiver);
        try {
          receiver.stop();
          cnt++;
        }
        catch (CancelException e) {
          if (this.getLogger().fineEnabled()) {
            this.getLogger().fine(
                "Ignored cache closure while closing receiver " + receiver, e);
          }
        }
      }
    } // synchronized

    this.getLogger().fine(
        this.toString() + ": finished stopping " + cnt
            + " gateway receiver(s), total is " + allGatewayReceivers.size());
    return cnt;
  }

  void stopServers() {

    this.getLogger().fine(this.toString() + ": stopping bridge servers...");
    Iterator allBridgeServersIterator = this.allBridgeServers.iterator();
    while (allBridgeServersIterator.hasNext()) {
      BridgeServerImpl bridge = (BridgeServerImpl) allBridgeServersIterator.next();
      this.getLogger().fine("stopping bridge " + bridge);
      try {
        bridge.stop();
      } catch (CancelException e) {
        if (this.getLogger().fineEnabled()) {
          this.getLogger().fine("Ignored cache closure while closing bridge " + bridge, e);
        }
      }
      allBridgeServers.remove(bridge);
    }

    stopGatewayHubs(false);

    // stop HA services if they had been started
    this.getLogger().fine(this.toString() + ": stopping HA services...");
    try {
      HARegionQueue.stopHAServices();
    } catch (CancelException e) {
      if (this.getLogger().fineEnabled()) {
        this.getLogger().fine("Ignored cache closure while closing HA services", e);
      }
    }

    this.getLogger().fine(this.toString() + ": stopping client health monitor...");
    try {
      ClientHealthMonitor.shutdownInstance();
    } catch (CancelException e) {
      if (this.getLogger().fineEnabled()) {
        this.getLogger().fine("Ignored cache closure while closing client health monitor", e);
      }
    }

    // Reset the unique id counter for durable clients.
    // If a durable client stops/starts its cache, it needs
    // to maintain the same unique id.
    ClientProxyMembershipID.resetUniqueIdCounter();

  }

  public final InternalDistributedSystem getDistributedSystem() {
    return this.system;
  }

  /**
   * Returns the member id of my distributed system
   *
   * @since 5.0
   */
  public final InternalDistributedMember getMyId() {
    return this.myId;
  }

  /*
   * (non-Javadoc)
   *
   * @see com.gemstone.gemfire.cache.Cache#getMembers()
   */
  public Set<DistributedMember> getMembers() {
    return Collections.unmodifiableSet(this.dm.getOtherNormalDistributionManagerIds());
  }

  /*
   * (non-Javadoc)
   *
   * @see com.gemstone.gemfire.cache.Cache#getAdminMembers()
   */
  public Set<DistributedMember> getAdminMembers() {
    return this.dm.getAdminMemberSet();
  }

  /*
   * (non-Javadoc)
   *
   * @see com.gemstone.gemfire.cache.Cache#getMembers(com.gemstone.gemfire.cache.Region)
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public Set<DistributedMember> getMembers(Region r) {
    if (r instanceof DistributedRegion) {
      DistributedRegion d = (DistributedRegion) r;
      return (Set)d.getDistributionAdvisor().adviseCacheOp();
    } else if (r instanceof PartitionedRegion) {
      PartitionedRegion p = (PartitionedRegion) r;
      return p.getRegionAdvisor().adviseAllPRNodes();
    } else {
      return Collections.emptySet();
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see com.gemstone.gemfire.cache.client.ClientCache#getCurrentServers()
   */
  public Set<InetSocketAddress> getCurrentServers() {
    Map<String, Pool> pools = PoolManager.getAll();
    Set result = null;
    for (Pool p : pools.values()) {
      PoolImpl pi = (PoolImpl) p;
      for (Object o : pi.getCurrentServers()) {
        ServerLocation sl = (ServerLocation) o;
        if (result == null) {
          result = new HashSet<DistributedMember>();
        }
        result.add(new InetSocketAddress(sl.getHostName(), sl.getPort()));
      }
    }
    if (result == null) {
      return Collections.EMPTY_SET;
    } else {
      return result;
    }
  }

  public final LogWriter getLogger() {
    return this.system.getLogWriter();
  }

  public final LogWriter getSecurityLogger() {
    return this.system.getSecurityLogWriter();
  }

  public LogWriterI18n getLoggerI18n() {
    return this.system.getLogWriterI18n();
  }
  
  public LogWriterI18n getSecurityLoggerI18n() {
    return this.system.getSecurityLogWriter().convertToLogWriterI18n();
  }

  /**
   * get the threadid/sequenceid sweeper task for this cache
   *
   * @return the sweeper task
   */
  protected final EventTracker.ExpiryTask getEventTrackerTask() {
    return this.recordedEventSweeper;
  }

  public final CachePerfStats getCachePerfStats() {
    return this.cachePerfStats;
  }

  public final String getName() {
    return this.system.getName();
  }

  /**
   * Get the list of all instances of properties for Declarables with the given class name.
   * 
   * @param className Class name of the declarable
   * @return List of all instances of properties found for the given declarable
   */
  public List<Properties> getDeclarableProperties(final String className) {
    List<Properties> propertiesList = new ArrayList<Properties>();
    synchronized (this.declarablePropertiesMap) {
      for (Map.Entry<Declarable, Properties> entry : this.declarablePropertiesMap.entrySet()) {
        if (entry.getKey().getClass().getName().equals(className)) {
          propertiesList.add(entry.getValue());
        }
      }
    }
    return propertiesList;
  }
  
  /**
   * Get the properties for the given declarable.
   * 
   * @param declarable The declarable
   * @return Properties found for the given declarable
   */
  public Properties getDeclarableProperties(final Declarable declarable) {
    return this.declarablePropertiesMap.get(declarable);
  }
  
  /**
   * Returns the date and time that this cache was created.
   *
   * @since 3.5
   */
  public Date getCreationDate() {
    return this.creationDate;
  }

  /**
   * Returns the number of seconds that have elapsed since the Cache was created.
   *
   * @since 3.5
   */
  public int getUpTime() {
    return (int) ((System.currentTimeMillis() - this.creationDate.getTime()) / 1000);
  }

  /**
   * All entry and region operations should be using this time rather than
   * System.currentTimeMillis(). Specially all version stamps/tags must be
   * populated with this timestamp.
   * 
   * @return distributed cache time.
   */
  public final long cacheTimeMillis() {
    if (dm != null) {
      return dm.cacheTimeMillis();
    } else {
      return System.currentTimeMillis();
    }
  }

  public Region createVMRegion(String name, RegionAttributes attrs) throws RegionExistsException, TimeoutException {
    return createRegion(name, attrs);
  }

  private PoolFactory createDefaultPF() {
    PoolFactory defpf = PoolManager.createFactory();
    try {
      String localHostName = SocketCreator.getHostName(SocketCreator.getLocalHost());
      defpf.addServer(localHostName, CacheServer.DEFAULT_PORT);
    } catch (UnknownHostException ex) {
      throw new IllegalStateException("Could not determine local host name");
    }
    return defpf;
  }

  protected void checkValidityForPool() {
    if (!isClient()) {
      throw new UnsupportedOperationException();
    }
  }
  /**
   * Used to set the default pool on a new GemFireCache.
   */
  public void determineDefaultPool() {
    this.checkValidityForPool();
    Pool pool = null;
    // create the pool if it does not already exist
    if (this.clientpf == null) {
      Map<String, Pool> pools = PoolManager.getAll();
      if (pools.isEmpty()) {
        this.clientpf = createDefaultPF();
      } else if (pools.size() == 1) {
        // otherwise use a singleton.
        pool = pools.values().iterator().next();
      } else {
        if (pool == null) {
          // act as if the default pool was configured
          // and see if we can find an existing one that is compatible
          PoolFactoryImpl pfi = (PoolFactoryImpl) createDefaultPF();
          for (Pool p : pools.values()) {
            if (((PoolImpl) p).isCompatible(pfi.getPoolAttributes())) {
              pool = p;
              break;
            }
          }
          if (pool == null) {
            // if pool is still null then we will not have a default pool for this ClientCache
            setDefaultPool(null);
            return;
          }
        }
      }
    } else {
      PoolFactoryImpl pfi = (PoolFactoryImpl) this.clientpf;
      if (pfi.getPoolAttributes().locators.isEmpty() && pfi.getPoolAttributes().servers.isEmpty()) {
        try {
          String localHostName = SocketCreator.getHostName(SocketCreator.getLocalHost());
          pfi.addServer(localHostName, CacheServer.DEFAULT_PORT);
        } catch (UnknownHostException ex) {
          throw new IllegalStateException("Could not determine local host name");
        }
      }
      // look for a pool that already exists that is compatible with
      // our PoolFactory.
      // If we don't find one we will create a new one that meets our needs.
      Map<String, Pool> pools = PoolManager.getAll();
      for (Pool p : pools.values()) {
        if (((PoolImpl) p).isCompatible(pfi.getPoolAttributes())) {
          pool = p;
          break;
        }
      }
    }
    if (pool == null) {
      // create our pool with a unique name
      String poolName = "DEFAULT";
      int count = 1;
      Map<String, Pool> pools = PoolManager.getAll();
      while (pools.containsKey(poolName)) {
        poolName = "DEFAULT" + count;
        count++;
      }
      pool = this.clientpf.create(poolName);
    }
    setDefaultPool(pool);
  }

  /**
   * Used to see if a existing cache's pool is compatible with us.
   *
   * @return the default pool that is right for us
   */
  public Pool determineDefaultPool(PoolFactory pf) {
    Pool pool = null;
    // create the pool if it does not already exist
    if (pf == null) {
      Map<String, Pool> pools = PoolManager.getAll();
      if (pools.isEmpty()) {
        throw new IllegalStateException("Since a cache already existed a pool should also exist.");
      } else if (pools.size() == 1) {
        // otherwise use a singleton.
        pool = pools.values().iterator().next();
        if (getDefaultPool() != pool) {
          throw new IllegalStateException("Existing cache's default pool was not the same as the only existing pool");
        }
      } else {
        // just use the current default pool if one exists
        pool = getDefaultPool();
        if (pool == null) {
          // act as if the default pool was configured
          // and see if we can find an existing one that is compatible
          PoolFactoryImpl pfi = (PoolFactoryImpl) createDefaultPF();
          for (Pool p : pools.values()) {
            if (((PoolImpl) p).isCompatible(pfi.getPoolAttributes())) {
              pool = p;
              break;
            }
          }
          if (pool == null) {
            // if pool is still null then we will not have a default pool for this ClientCache
            return null;
          }
        }
      }
    } else {
      PoolFactoryImpl pfi = (PoolFactoryImpl) pf;
      if (pfi.getPoolAttributes().locators.isEmpty() && pfi.getPoolAttributes().servers.isEmpty()) {
        try {
          String localHostName = SocketCreator.getHostName(SocketCreator.getLocalHost());
          pfi.addServer(localHostName, CacheServer.DEFAULT_PORT);
        } catch (UnknownHostException ex) {
          throw new IllegalStateException("Could not determine local host name");
        }
      }
      PoolImpl defPool = (PoolImpl) getDefaultPool();
      if (defPool != null && defPool.isCompatible(pfi.getPoolAttributes())) {
        pool = defPool;
      } else {
        throw new IllegalStateException("Existing cache's default pool was not compatible");
      }
    }
    return pool;
  }

  public Region createRegion(String name, RegionAttributes attrs) throws RegionExistsException, TimeoutException {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }
    return basicCreateRegion(name, attrs);
  }

  public Region basicCreateRegion(String name, RegionAttributes attrs) throws RegionExistsException, TimeoutException {
    try {
      InternalRegionArguments ira = new InternalRegionArguments().setDestroyLockFlag(true).setRecreateFlag(false)
          .setSnapshotInputStream(null).setImageTarget(null);

      if (attrs instanceof UserSpecifiedRegionAttributes) {
        ira.setIndexes(((UserSpecifiedRegionAttributes) attrs).getIndexes());
      }
      return createVMRegion(name, attrs, ira);
    } catch (IOException e) {
      // only if loading snapshot, not here
      InternalGemFireError assErr = new InternalGemFireError(LocalizedStrings.GemFireCache_UNEXPECTED_EXCEPTION.toLocalizedString());
      assErr.initCause(e);
      throw assErr;
    } catch (ClassNotFoundException e) {
      // only if loading snapshot, not here
      InternalGemFireError assErr = new InternalGemFireError(LocalizedStrings.GemFireCache_UNEXPECTED_EXCEPTION.toLocalizedString());
      assErr.initCause(e);
      throw assErr;
    }
  }

  public <K, V> Region<K, V> createVMRegion(String name, RegionAttributes<K, V> p_attrs, InternalRegionArguments internalRegionArgs)
      throws RegionExistsException, TimeoutException, IOException, ClassNotFoundException {
    if (getMyId().getVmKind() == DistributionManager.LOCATOR_DM_TYPE) {
      if (!internalRegionArgs.isUsedForMetaRegion() && internalRegionArgs.getInternalMetaRegion() == null) {
        throw new IllegalStateException("Regions can not be created in a locator.");
      }
    }
    stopper.checkCancelInProgress(null);
    LocalRegion.validateRegionName(name);
    RegionAttributes<K, V> attrs = p_attrs;
    if (attrs == null) {
      throw new IllegalArgumentException(LocalizedStrings.GemFireCache_ATTRIBUTES_MUST_NOT_BE_NULL.toLocalizedString());
    }
    
    LocalRegion rgn = null;
    // final boolean getDestroyLock = attrs.getDestroyLockFlag();
    final InputStream snapshotInputStream = internalRegionArgs.getSnapshotInputStream();
    InternalDistributedMember imageTarget = internalRegionArgs.getImageTarget();
    final boolean recreate = internalRegionArgs.getRecreateFlag();

    final boolean isPartitionedRegion = attrs.getPartitionAttributes() != null;
    final boolean isReinitCreate = snapshotInputStream != null
        || imageTarget != null || recreate;

    final String regionPath = LocalRegion.calcFullPath(name, null);

    try {
      for (;;) {
        getCancelCriterion().checkCancelInProgress(null);

        Future future = null;
        synchronized (this.rootRegions) {
          rgn = (LocalRegion) this.rootRegions.get(name);
          if (rgn != null) {
            throw new RegionExistsException(rgn);
          }
          // check for case where a root region is being reinitialized and we
          // didn't
          // find a region, i.e. the new region is about to be created

          if (!isReinitCreate) { // fix bug 33523
            String fullPath = Region.SEPARATOR + name;
            future = (Future) this.reinitializingRegions.get(fullPath);
          }
          if (future == null) {
            HDFSIntegrationUtil.createAndAddAsyncQueue(regionPath, attrs, this);
            attrs = setEvictionAttributesForLargeRegion(attrs);
            if (internalRegionArgs.getInternalMetaRegion() != null) {
              rgn = internalRegionArgs.getInternalMetaRegion();
            } else if (isPartitionedRegion) {
              rgn = new PartitionedRegion(name, attrs, null, this, internalRegionArgs);
            } else {
              /*for (String senderId : attrs.getGatewaySenderIds()) {
                if (getGatewaySender(senderId) != null
                    && getGatewaySender(senderId).isParallel()) {
                  throw new IllegalStateException(
                      LocalizedStrings.AttributesFactory_PARALLELGATEWAYSENDER_0_IS_INCOMPATIBLE_WITH_DISTRIBUTED_REPLICATION
                          .toLocalizedString(senderId));
                }
              }*/
              if (attrs.getScope().isLocal()) {
                rgn = new LocalRegion(name, attrs, null, this, internalRegionArgs);
              } else {
                rgn = new DistributedRegion(name, attrs, null, this, internalRegionArgs);
              }
            }

            this.rootRegions.put(name, rgn);
            if (isReinitCreate) {
              regionReinitialized(rgn);
            }
            break;
          }
        } // synchronized

        boolean interrupted = Thread.interrupted();
        try { // future != null
          LocalRegion region = (LocalRegion) future.get(); // wait on Future
          throw new RegionExistsException(region);
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (ExecutionException e) {
          throw new Error(LocalizedStrings.GemFireCache_UNEXPECTED_EXCEPTION.toLocalizedString(), e);
        } catch (CancellationException e) {
          // future was cancelled
        } finally {
          if (interrupted)
            Thread.currentThread().interrupt();
        }
      } // for
      
      boolean success = false;
      try {
        setRegionByPath(rgn.getFullPath(), rgn);
        rgn.preInitialize(internalRegionArgs);
        rgn.initialize(snapshotInputStream, imageTarget, internalRegionArgs);
        success = true;
      } catch (CancelException e) {
        // don't print a call stack
        throw e;
      } catch (RedundancyAlreadyMetException e) {
        // don't log this
        throw e;
      } catch (final RuntimeException validationException) {
        getLoggerI18n().warning(LocalizedStrings.GemFireCache_INITIALIZATION_FAILED_FOR_REGION_0, rgn.getFullPath(),
            validationException);
        throw validationException;
      } finally {
        if (!success) {
          try {
            // do this before removing the region from
            // the root set to fix bug 41982.
            rgn.cleanupFailedInitialization();
          } catch (VirtualMachineError e) {
            SystemFailure.initiateFailure(e);
            throw e;
          } catch (Throwable t) {
            SystemFailure.checkFailure();
            stopper.checkCancelInProgress(t);
            
            // bug #44672 - log the failure but don't override the original exception
            getLoggerI18n().warning(
                LocalizedStrings.GemFireCache_INIT_CLEANUP_FAILED_FOR_REGION_0, 
                rgn.getFullPath(), t);

          } finally {
            // clean up if initialize fails for any reason
            setRegionByPath(rgn.getFullPath(), null);
            synchronized (this.rootRegions) {
              Region r = (Region) this.rootRegions.get(name);
              if (r == rgn) {
                this.rootRegions.remove(name);
              }
            } // synchronized
          }
        } // success
      }

      
      
      rgn.postCreateRegion();
    } catch (RegionExistsException ex) {
      // outside of sync make sure region is initialized to fix bug 37563
      LocalRegion r = (LocalRegion) ex.getRegion();
      r.waitOnInitialization(); // don't give out ref until initialized
      throw ex;
    }

    /**
     * Added for M&M . Putting the callback here to avoid creating RegionMBean in case of Exception
     **/
    if (!rgn.isInternalRegion()) {
      system.handleResourceEvent(ResourceEvent.REGION_CREATE, rgn);
    }

    return rgn;
  }

  /**
   * turn on eviction by default for HDFS regions
   */
  @SuppressWarnings("deprecation")
  public <K, V> RegionAttributes<K, V> setEvictionAttributesForLargeRegion(
      RegionAttributes<K, V> attrs) {
    RegionAttributes<K, V> ra = attrs;
    if (DISABLE_AUTO_EVICTION) {
      return ra;
    }
    if (attrs.getDataPolicy().withHDFS()
        || attrs.getHDFSStoreName() != null) {
      // make the region overflow by default
      EvictionAttributes evictionAttributes = attrs.getEvictionAttributes();
      boolean hasNoEvictionAttrs = evictionAttributes == null
          || evictionAttributes.getAlgorithm().isNone();
      AttributesFactory<K, V> af = new AttributesFactory<K, V>(attrs);
      String diskStoreName = attrs.getDiskStoreName();
      // set the local persistent directory to be the same as that for
      // HDFS store
      if (attrs.getHDFSStoreName() != null) {
        HDFSStoreImpl hdfsStore = findHDFSStore(attrs.getHDFSStoreName());
        if (attrs.getPartitionAttributes().getLocalMaxMemory() != 0 && hdfsStore == null) {
          // HDFS store expected to be found at this point
          throw new IllegalStateException(
              LocalizedStrings.HOPLOG_HDFS_STORE_NOT_FOUND
                  .toLocalizedString(attrs.getHDFSStoreName()));
        }
        // if there is no disk store, use the one configured for hdfs queue
        if (attrs.getPartitionAttributes().getLocalMaxMemory() != 0 && diskStoreName == null) {
          diskStoreName = hdfsStore.getHDFSEventQueueAttributes().getDiskStoreName();
        }
      }
      // set LRU heap eviction with overflow to disk for HDFS stores with
      // local Oplog persistence
      // set eviction attributes only if not set
      if (hasNoEvictionAttrs && !isHadoopGfxdLonerMode()) {
        if (diskStoreName != null) {
          af.setDiskStoreName(diskStoreName);
        }
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(
            ObjectSizer.DEFAULT, EvictionAction.OVERFLOW_TO_DISK));
      }
      ra = af.create();
    }
    return ra;
  }

  public final Region getRegion(String path) {
    return getRegion(path, false);
  }
  
  public final Region getRegionThoughUnInitialized(String path) {
    return getRegion(path, false, true);
  }

  /**
   * returns a set of all current regions in the cache, including buckets
   *
   * @since 6.0
   */
  public final Set<LocalRegion> getAllRegions() {
    Set<LocalRegion> result = new HashSet();
    synchronized (this.rootRegions) {
      for (Object r : this.rootRegions.values()) {
        if (r instanceof PartitionedRegion) {
          PartitionedRegion p = (PartitionedRegion) r;
          PartitionedRegionDataStore prds = p.getDataStore();
          if (prds != null) {
            Set<Entry<Integer, BucketRegion>> bucketEntries = p.getDataStore().getAllLocalBuckets();
            for (Map.Entry e : bucketEntries) {
              result.add((LocalRegion) e.getValue());
            }
          }
        } else if (r instanceof LocalRegion) {
          LocalRegion l = (LocalRegion) r;
          result.add(l);
          result.addAll(l.basicSubregions(true));
        }
      }
    }
    return result;
  }

  public final Set<LocalRegion> getApplicationRegions() {
    Set<LocalRegion> result = new HashSet<LocalRegion>();
    synchronized (this.rootRegions) {
      for (Object r : this.rootRegions.values()) {
        LocalRegion rgn = (LocalRegion) r;
        if (rgn.isSecret() || rgn.isUsedForMetaRegion() || rgn instanceof HARegion || rgn.isUsedForPartitionedRegionAdmin()
            || rgn.isInternalRegion()/* rgn.isUsedForPartitionedRegionBucket() */) {
          continue; // Skip administrative PartitionedRegions
        }
        result.add(rgn);
        result.addAll(rgn.basicSubregions(true));
      }
    }
    return result;
  }

  void setRegionByPath(String path, LocalRegion r) {
    if (r == null) {
      this.pathToRegion.remove(path);
    } else {
      this.pathToRegion.put(path, r);
    }
  }

  /**
   * @throws IllegalArgumentException
   *           if path is not valid
   */
  private static void validatePath(String path) {
    if (path == null) {
      throw new IllegalArgumentException(LocalizedStrings.GemFireCache_PATH_CANNOT_BE_NULL.toLocalizedString());
    }
    if (path.length() == 0) {
      throw new IllegalArgumentException(LocalizedStrings.GemFireCache_PATH_CANNOT_BE_EMPTY.toLocalizedString());
    }
    if (path.equals(Region.SEPARATOR)) {
      throw new IllegalArgumentException(LocalizedStrings.GemFireCache_PATH_CANNOT_BE_0.toLocalizedString(Region.SEPARATOR));
    }
  }

  public final LocalRegion getRegionByPath(String path, final boolean returnUnInitializedRegion) {
    validatePath(path); // fix for bug 34892

    { // do this before checking the pathToRegion map
      LocalRegion result = getReinitializingRegion(path, returnUnInitializedRegion);
      if (result != null) {
        return result;
      }
    }
    return (LocalRegion) this.pathToRegion.get(path);
  }
  public final LocalRegion getRegionByPathForProcessing(String path) {
    LocalRegion result = getRegionByPath(path, false);
    if (result == null) {
      stopper.checkCancelInProgress(null);
      int oldLevel = LocalRegion.setThreadInitLevelRequirement(LocalRegion.ANY_INIT); // go through
      // initialization
      // latches
      try {
        String[] pathParts = parsePath(path);
        LocalRegion root;
        synchronized (this.rootRegions) {
          root = (LocalRegion) this.rootRegions.get(pathParts[0]);
          if (root == null)
            return null;
        }
        LogWriterI18n logger = getLoggerI18n();
        if (logger.fineEnabled()) {
          logger.fine("GemFireCache.getRegion, calling getSubregion on root(" + pathParts[0] + "): " + pathParts[1]);
        }
        result = (LocalRegion) root.getSubregion(pathParts[1], true, false);
      } finally {
        LocalRegion.setThreadInitLevelRequirement(oldLevel);
      }
    }
    return result;
  }

  public final DistributedRegion getPRRootRegion(boolean returnDestroyedRegion) {
    final DistributedRegion prRoot = this.cachedPRRoot;
    if (prRoot != null) {
      prRoot.waitOnInitialization();
      if (returnDestroyedRegion || !prRoot.isDestroyed()) {
        return prRoot;
      }
      else {
        stopper.checkCancelInProgress(null);
        return null;
      }
    }
    return (this.cachedPRRoot = (DistributedRegion)getRegion(
        PartitionedRegionHelper.PR_ROOT_REGION_NAME, returnDestroyedRegion));
  }

  /**
   * @param returnDestroyedRegion
   *          if true, okay to return a destroyed region
   * @param returnUnInitializedRegion
   *          if true, okay to return yet to be initialized region. Avoids
   *          waitOnInitialization.
   */
  public final Region getRegion(String path, boolean returnDestroyedRegion, final boolean returnUnInitializedRegion) {
    stopper.checkCancelInProgress(null);
    {
      LocalRegion result = getRegionByPath(path, returnUnInitializedRegion);
      // Do not waitOnInitialization() for PR
      // if (result != null && !(result instanceof PartitionedRegion)) {
      if (result != null) {
        if (!returnUnInitializedRegion) {
          result.waitOnInitialization();
        }
        if (!returnDestroyedRegion && result.isDestroyed()) {
          stopper.checkCancelInProgress(null);
          return null;
        } else {
          return result;
        }
      }
    }

    String[] pathParts = parsePath(path);
    LocalRegion root;
    LogWriterI18n logger = getLoggerI18n();
    synchronized (this.rootRegions) {
      root = (LocalRegion) this.rootRegions.get(pathParts[0]);
      if (root == null) {
        if (logger.fineEnabled()) {
          logger.fine("GemFireCache.getRegion, no region found for " + pathParts[0]);
        }
        stopper.checkCancelInProgress(null);
        return null;
      }
      if (!returnDestroyedRegion && root.isDestroyed()) {
        stopper.checkCancelInProgress(null);
        return null;
      }
    }
    if (logger.fineEnabled()) {
      logger.fine("GemFireCache.getRegion, calling getSubregion on root(" + pathParts[0] + "): " + pathParts[1]);
    }
    return root.getSubregion(pathParts[1], returnDestroyedRegion, returnUnInitializedRegion);
  }
  
  /**
   * @param returnDestroyedRegion
   *          if true, okay to return a destroyed region
   */
  public Region getRegion(String path, boolean returnDestroyedRegion) {
    return getRegion(path, returnDestroyedRegion, false);
  }

  /**
   * @param returnDestroyedRegion
   *          if true, okay to return a destroyed partitioned region
   */
  public final Region getPartitionedRegion(String path, boolean returnDestroyedRegion) {
    stopper.checkCancelInProgress(null);
    {
      LocalRegion result = getRegionByPath(path, false);
      // Do not waitOnInitialization() for PR
      if (result != null) {
        if (!(result instanceof PartitionedRegion)) {
          return null;
        } else {
          return result;
        }
      }
    }
 
    String[] pathParts = parsePath(path);
    LocalRegion root;
    LogWriterI18n logger = getLoggerI18n();
    synchronized (this.rootRegions) {
      root = (LocalRegion) this.rootRegions.get(pathParts[0]);
      if (root == null) {
        if (logger.fineEnabled()) {
          logger.fine("GemFireCache.getRegion, no region found for " + pathParts[0]);
        }
        stopper.checkCancelInProgress(null);
        return null;
      }
      if (!returnDestroyedRegion && root.isDestroyed()) {
        stopper.checkCancelInProgress(null);
        return null;
      }
    }
    if (logger.fineEnabled()) {
      logger.fine("GemFireCache.getPartitionedRegion, calling getSubregion on root(" + pathParts[0] + "): " + pathParts[1]);
    }
    Region result = root.getSubregion(pathParts[1], returnDestroyedRegion, false);
    if (result != null && !(result instanceof PartitionedRegion)) {
      return null;
    } else {
      return result;
    }
  }

  /** Return true if this region is initializing */
  final boolean isGlobalRegionInitializing(String fullPath) {
    stopper.checkCancelInProgress(null);
    int oldLevel = LocalRegion.setThreadInitLevelRequirement(LocalRegion.ANY_INIT); // go through
    // initialization
    // latches
    try {
      return isGlobalRegionInitializing((LocalRegion) getRegion(fullPath));
    } finally {
      LocalRegion.setThreadInitLevelRequirement(oldLevel);
    }
  }

  /** Return true if this region is initializing */
  final boolean isGlobalRegionInitializing(LocalRegion region) {
    boolean result = region != null && region.scope.isGlobal() && !region.isInitialized();
    if (result) {
      LogWriterI18n logger = getLoggerI18n();
      if (logger.fineEnabled()) {
        logger.fine("GemFireCache.isGlobalRegionInitializing (" + region.getFullPath() + ")");
      }
    }
    return result;
  }

  public Set rootRegions() {
    return rootRegions(false);
  }

  public final Set rootRegions(boolean includePRAdminRegions) {
    return rootRegions(includePRAdminRegions, true);
  }

  private final Set rootRegions(boolean includePRAdminRegions, boolean waitForInit) {
    stopper.checkCancelInProgress(null);
    Set regions = new HashSet();
    synchronized (this.rootRegions) {
      for (Iterator itr = this.rootRegions.values().iterator(); itr.hasNext();) {
        LocalRegion r = (LocalRegion) itr.next();
        // If this is an internal meta-region, don't return it to end user
        if (r.isSecret() || r.isUsedForMetaRegion() || r instanceof HARegion || !includePRAdminRegions
            && (r.isUsedForPartitionedRegionAdmin() || r.isUsedForPartitionedRegionBucket())) {
          continue; // Skip administrative PartitionedRegions
        }
        regions.add(r);
      }
    }
    if (waitForInit) {
      for (Iterator r = regions.iterator(); r.hasNext();) {
        LocalRegion lr = (LocalRegion) r.next();
        // lr.waitOnInitialization();
        if (!lr.checkForInitialization()) {
          r.remove();
        }
      }
    }
    return Collections.unmodifiableSet(regions);
  }

  /**
   * Called by ccn when a client goes away
   *
   * @since 5.7
   */
  public void cleanupForClient(CacheClientNotifier ccn, ClientProxyMembershipID client) {
    try {
      if (isClosed())
        return;
      Iterator it = rootRegions(false, false).iterator();
      while (it.hasNext()) {
        LocalRegion lr = (LocalRegion) it.next();
        lr.cleanupForClient(ccn, client);
      }
    } catch (DistributedSystemDisconnectedException ignore) {
    }
  }

  public final boolean isClosed() {
    return this.isClosing;
  }

  public int getLockTimeout() {
    return this.lockTimeout;
  }

  public void setLockTimeout(int seconds) {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }
    stopper.checkCancelInProgress(null);
    this.lockTimeout = seconds;
  }

  public int getLockLease() {
    return this.lockLease;
  }

  public void setLockLease(int seconds) {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }
    stopper.checkCancelInProgress(null);
    this.lockLease = seconds;
  }

  public int getSearchTimeout() {
    return this.searchTimeout;
  }

  public void setSearchTimeout(int seconds) {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }
    stopper.checkCancelInProgress(null);
    this.searchTimeout = seconds;
  }

  public int getMessageSyncInterval() {
    return HARegionQueue.getMessageSyncInterval();
  }

  public void setMessageSyncInterval(int seconds) {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }
    stopper.checkCancelInProgress(null);
    if (seconds < 0) {
      throw new IllegalArgumentException(
          LocalizedStrings.GemFireCache_THE_MESSAGESYNCINTERVAL_PROPERTY_FOR_CACHE_CANNOT_BE_NEGATIVE.toLocalizedString());
    }
    HARegionQueue.setMessageSyncInterval(seconds);
  }

  /**
   * Get a reference to a Region that is reinitializing, or null if that Region is not reinitializing or this thread is
   * interrupted. If a reinitializing region is found, then this method blocks until reinitialization is complete and
   * then returns the region.
   */
  LocalRegion getReinitializingRegion(String fullPath, final boolean returnUnInitializedRegion) {
    LogWriterI18n logger = getLoggerI18n();
    Future future = (Future) this.reinitializingRegions.get(fullPath);
    if (future == null) {
      // if (logger.fineEnabled()) {
      // logger.fine("getReinitializingRegion: No initialization future for: "
      // + fullPath);
      // }
      return null;
    }
    try {
      LocalRegion region = (LocalRegion) future.get();
      if (!returnUnInitializedRegion) {
        region.waitOnInitialization();
      }
      if (logger.fineEnabled()) {
        logger
            .fine("Returning manifested future for: "
                + fullPath
                + (returnUnInitializedRegion ? " without waiting for initialization."
                    : ""));
      }
      return region;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // logger.fine("thread interrupted, returning null");
      return null;
    } catch (ExecutionException e) {
      throw new Error(LocalizedStrings.GemFireCache_UNEXPECTED_EXCEPTION.toLocalizedString(), e);
    } catch (CancellationException e) {
      // future was cancelled
      logger.fine("future cancelled, returning null");
      return null;
    }
  }

  /**
   * Register the specified region name as reinitializing, creating and adding a Future for it to the map.
   *
   * @throws IllegalStateException
   *           if there is already a region by that name registered.
   */
  void regionReinitializing(String fullPath) {
    Object old = this.reinitializingRegions.putIfAbsent(fullPath, new FutureResult(this.stopper));
    if (old != null) {
      throw new IllegalStateException(LocalizedStrings.GemFireCache_FOUND_AN_EXISTING_REINITALIZING_REGION_NAMED_0
          .toLocalizedString(fullPath));
    }
  }

  /**
   * Set the reinitialized region and unregister it as reinitializing.
   *
   * @throws IllegalStateException
   *           if there is no region by that name registered as reinitializing.
   */
  void regionReinitialized(Region region) {
    String regionName = region.getFullPath();
    FutureResult future = (FutureResult) this.reinitializingRegions.get(regionName);
    if (future == null) {
      throw new IllegalStateException(LocalizedStrings.GemFireCache_COULD_NOT_FIND_A_REINITIALIZING_REGION_NAMED_0
          .toLocalizedString(regionName));
    }
    future.set(region);
    unregisterReinitializingRegion(regionName);
  }

  /**
   * Clear a reinitializing region, e.g. reinitialization failed.
   *
   * @throws IllegalStateException
   *           if cannot find reinitializing region registered by that name.
   */
  void unregisterReinitializingRegion(String fullPath) {
    /* Object previous = */this.reinitializingRegions.remove(fullPath);
    // if (previous == null) {
    // throw new IllegalStateException("Could not find a reinitializing region
    // named " +
    // fullPath);
    // }
  }

  // /////////////////////////////////////////////////////////////

  /**
   * Returns true if get should give a copy; false if a reference.
   *
   * @since 4.0
   */
  final boolean isCopyOnRead() {
    return this.copyOnRead;
  }

  /**
   * Implementation of {@link com.gemstone.gemfire.cache.Cache#setCopyOnRead}
   *
   * @since 4.0
   */
  public void setCopyOnRead(boolean copyOnRead) {
    this.copyOnRead = copyOnRead;
  }

  /**
   * Implementation of {@link com.gemstone.gemfire.cache.Cache#getCopyOnRead}
   *
   * @since 4.0
   */
  final public boolean getCopyOnRead() {
    return this.copyOnRead;
  }

  /**
   * Remove the specified root region
   *
   * @param rootRgn
   *          the region to be removed
   * @return true if root region was removed, false if not found
   */
  boolean removeRoot(LocalRegion rootRgn) {
    synchronized (this.rootRegions) {
      String rgnName = rootRgn.getName();
      LocalRegion found = (LocalRegion) this.rootRegions.get(rgnName);
      if (found == rootRgn) {
        LocalRegion previous = (LocalRegion) this.rootRegions.remove(rgnName);
        Assert.assertTrue(previous == rootRgn);
        return true;
      } else
        return false;
    }
  }

  /**
   * @return array of two Strings, the root name and the relative path from root If there is no relative path from root,
   *         then String[1] will be an empty string
   */
  static String[] parsePath(String p_path) {
    String path = p_path;
    validatePath(path);
    String[] result = new String[2];
    result[1] = "";
    // strip off root name from path
    int slashIndex = path.indexOf(Region.SEPARATOR_CHAR);
    if (slashIndex == 0) {
      path = path.substring(1);
      slashIndex = path.indexOf(Region.SEPARATOR_CHAR);
    }
    result[0] = path;
    if (slashIndex > 0) {
      result[0] = path.substring(0, slashIndex);
      result[1] = path.substring(slashIndex + 1);
    }
    return result;
  }

  /**
   * Makes note of a <code>CacheLifecycleListener</code>
   */
  public static void addCacheLifecycleListener(CacheLifecycleListener l) {
    synchronized (GemFireCacheImpl.class) {
      cacheLifecycleListeners.add(l);
    }
  }

  /**
   * Removes a <code>CacheLifecycleListener</code>
   *
   * @return Whether or not the listener was removed
   */
  public static boolean removeCacheLifecycleListener(CacheLifecycleListener l) {
    synchronized (GemFireCacheImpl.class) {
      return cacheLifecycleListeners.remove(l);
    }
  }

  /**
   * Creates the single instance of the Transation Manager for this cache. Returns the existing one upon request.
   *
   * @return the CacheTransactionManager instance.
   *
   * @since 4.0
   */
  public final TXManagerImpl getCacheTransactionManager() {
    return this.txMgr;
  }

  /**
   * @see CacheClientProxy
   * @guarded.By {@link #ccpTimerMutex}
   */
  private SystemTimer ccpTimer;

  /**
   * @see #ccpTimer
   */
  private final Object ccpTimerMutex = new Object();

  /**
   * Get cache-wide CacheClientProxy SystemTimer
   *
   * @return the timer, lazily created
   */
  public SystemTimer getCCPTimer() {
    synchronized (ccpTimerMutex) {
      if (ccpTimer != null) {
        return ccpTimer;
      }
      ccpTimer = new SystemTimer(getDistributedSystem(), true, getLoggerI18n());
      if (this.isClosing) {
        ccpTimer.cancel(); // poison it, don't throw.
      }
      return ccpTimer;
    }
  }

  /**
   * @see LocalRegion
   */
  private final ExpirationScheduler expirationScheduler;

  /**
   * Get cache-wide ExpirationScheduler
   *
   * @return the scheduler, lazily created
   */
  public final ExpirationScheduler getExpirationScheduler() {
    return this.expirationScheduler;
  }

  /**
   * Returns the <code>Executor</code> (thread pool) that is used to execute cache event listeners.
   *
   * @since 3.5
   */
  Executor getEventThreadPool() {
    Assert.assertTrue(this.eventThreadPool != null);
    return this.eventThreadPool;
  }

  public BridgeServer addBridgeServer() {
    return (BridgeServer) addCacheServer();
  }

  public CacheServer addCacheServer() {
    return addCacheServer(false);
  }

  public CacheServer addCacheServer(boolean isGatewayReceiver) {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }
    stopper.checkCancelInProgress(null);

    BridgeServerImpl bridge = new BridgeServerImpl(this, isGatewayReceiver);
    allBridgeServers.add(bridge);

    sendAddCacheServerProfileMessage();
    return bridge;
  }

  public GatewayHub setGatewayHub(String id, int port) {
    return addGatewayHub(id, port);
  }

  private int findGatewayHub(GatewayHubImpl list[], GatewayHubImpl hub) {
    for (int i = 0; i < list.length; i++) {
      if (list[i].equals(hub)) {
        return i;
      }
    }
    return -1;
  }

  public GatewayHub addGatewayHub(String id, int port,
      boolean isCapableOfBecomingPrimary) {
    if (isClient()) {
      throw new UnsupportedOperationException(
          "operation is not supported on a client cache");
    }
    stopper.checkCancelInProgress(null);

    GatewayHubImpl hub;
    synchronized (this.allGatewayHubsLock) {
      // If a gateway hub with the id is already defined, throw an exception
      if (alreadyDefinesGatewayHubId(id)) {
        throw new GatewayException(
            LocalizedStrings.GemFireCache_A_GATEWAYHUB_WITH_ID_0_IS_ALREADY_DEFINED_IN_THIS_CACHE
                .toLocalizedString(id));
      }
      // If a gateway hub with the port is already defined, throw an exception
      if (alreadyDefinesGatewayHubPort(port)) {
        throw new GatewayException(
            LocalizedStrings.GemFireCache_A_GATEWAYHUB_WITH_PORT_0_IS_ALREADY_DEFINED_IN_THIS_CACHE
                .toLocalizedString(Integer.valueOf(port)));
      }

      hub = new GatewayHubImpl(this, id, port, isCapableOfBecomingPrimary);
      GatewayHubImpl snap[] = allGatewayHubs; // volatile fetch
      int pos = findGatewayHub(snap, hub);
      Assert.assertTrue(pos == -1);

      allGatewayHubs = (GatewayHubImpl[])ArrayUtils.insert(snap, snap.length,
          hub);

      // getLogger().fine("Added gateway hub (now there are "
      // + allGatewayHubs.length + ") :" + hub);
    } // synchronized
    
    synchronized (this.rootRegions) {
      Set<LocalRegion> appRegions = getApplicationRegions();
      for (LocalRegion r : appRegions) {
        RegionAttributes ra = r.getAttributes();
        String hubID = ra.getGatewayHubId();
        Set<String> allGatewayHubIdsForRegion = r.getAllGatewayHubIds();
        if (ra.getEnableGateway()
            && (hubID.equals("")
                || hubID.equals(id)
                || (allGatewayHubIdsForRegion != null && allGatewayHubIdsForRegion.contains(id)))) {
          r.hubCreated(GatewayHubImpl.NON_GFXD_HUB);
        }
      }
    }
    
    return hub;
  }

  public GatewayHub addGatewayHub(String id, int port) {
    return this
        .addGatewayHub(id, port, true /* by default capable of becoming primary*/);
  }

  public void addGatewaySender(GatewaySender sender) {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }

    stopper.checkCancelInProgress(null);

    synchronized (allGatewaySendersLock) {
      if (!allGatewaySenders.contains(sender)) {
        new UpdateAttributesProcessor((AbstractGatewaySender) sender).distribute(true);
        Set<GatewaySender> tmp = new HashSet<GatewaySender>(allGatewaySenders.size() + 1);
        if (!allGatewaySenders.isEmpty()) {
          tmp.addAll(allGatewaySenders);
        }
        tmp.add(sender);
        this.allGatewaySenders = Collections.unmodifiableSet(tmp);
      } else {
        throw new IllegalStateException(LocalizedStrings.GemFireCache_A_GATEWAYSENDER_WITH_ID_0_IS_ALREADY_DEFINED_IN_THIS_CACHE
            .toLocalizedString(sender.getId()));
      }
    }

    synchronized (this.rootRegions) {
      Set<LocalRegion> appRegions = getApplicationRegions();
      for (LocalRegion r : appRegions) {
        Set<String> senders = r.getAllGatewaySenderIds();
        if (senders.contains(sender.getId())) {
          if (!sender.isParallel()) {
            r.senderCreated();
          }
          r.gatewaySendersChanged();
        }
      }
    }

     if(!sender.isParallel()) {
       Region dynamicMetaRegion = getRegion(DynamicRegionFactory.dynamicRegionListName);
       if(dynamicMetaRegion == null) {
         if(getLoggerI18n().fineEnabled()) {
           getLoggerI18n().fine(" The dynamic region is null. ");
         }
      } else {
        dynamicMetaRegion.getAttributesMutator().addGatewaySenderId(sender.getId());
      }
    }
    if (!(sender.getRemoteDSId() < 0)) {
      system.handleResourceEvent(ResourceEvent.GATEWAYSENDER_CREATE, sender);
    }
  }

  public void removeGatewaySender(GatewaySender sender) {
    if (isClient()) {
      throw new UnsupportedOperationException(
          "operation is not supported on a client cache");
    }

    stopper.checkCancelInProgress(null);

    synchronized (allGatewaySendersLock) {
      if (allGatewaySenders.contains(sender)) {
        new UpdateAttributesProcessor((AbstractGatewaySender)sender, true)
            .distribute(true);
        Set<GatewaySender> tmp = new HashSet<GatewaySender>(
            allGatewaySenders.size());
        if (!allGatewaySenders.isEmpty()) {
          tmp.addAll(allGatewaySenders);
        }
        tmp.remove(sender);
        this.allGatewaySenders = Collections.unmodifiableSet(tmp);
      }
    }

    synchronized (this.rootRegions) {
      Set<LocalRegion> appRegions = getApplicationRegions();
      for (LocalRegion r : appRegions) {
        Set<String> senders = r.getAllGatewaySenderIds();
        if (senders.contains(sender.getId())) {
          if (!sender.isParallel()) {
            r.senderRemoved();
          }
          r.gatewaySendersChanged();
        }
      }
    }
  }

  public void addGatewayReceiver(GatewayReceiver recv) {
    if (isClient()) {
      throw new UnsupportedOperationException(
          "operation is not supported on a client cache");
    }
    stopper.checkCancelInProgress(null);
    synchronized (allGatewayReceiversLock) {
      Set<GatewayReceiver> tmp = new HashSet<GatewayReceiver>(
          allGatewayReceivers.size() + 1);
      if (!allGatewayReceivers.isEmpty()) {
        tmp.addAll(allGatewayReceivers);
      }
      tmp.add(recv);
      this.allGatewayReceivers = Collections.unmodifiableSet(tmp);
    }
  }

  public void addAsyncEventQueue(AsyncEventQueue asyncQueue) {
    if (isClient()) {
      throw new UnsupportedOperationException(
          "operation is not supported on a client cache");
    }

    stopper.checkCancelInProgress(null);

    // using gateway senders lock since async queue uses a gateway sender
    synchronized (allGatewaySendersLock) {
      this.allAsyncEventQueues.add(asyncQueue);
    }
    system
        .handleResourceEvent(ResourceEvent.ASYNCEVENTQUEUE_CREATE, asyncQueue);
  }
  
  /**
   * Returns List of GatewaySender (excluding the senders for internal use)
   * 
   * @return  List    List of GatewaySender objects
   */
  public final Set<GatewaySender> getGatewaySenders() {
    THashSet tempSet = new THashSet();
    for (GatewaySender sender : allGatewaySenders) {
      if (!((AbstractGatewaySender)sender).isForInternalUse()) {
        tempSet.add(sender);
      }
    }
    return tempSet;
  }

  /**
   * Returns List of all GatewaySenders (including the senders for internal use)
   * 
   * @return  List    List of GatewaySender objects
   */
  public final Set<GatewaySender> getAllGatewaySenders() {
    return this.allGatewaySenders;
  }

  public final GatewaySender getGatewaySender(String Id) {
    final Set<GatewaySender> allSenders = this.allGatewaySenders;
    for (GatewaySender sender : allSenders) {
      if (sender.getId().equals(Id)) {
        return sender;
      }
    }
    return null;
  }

  public void gatewaySenderStarted(GatewaySender sender) {
    synchronized (this.rootRegions) {
      Set<LocalRegion> appRegions = getApplicationRegions();
      for (LocalRegion r : appRegions) {
        Set<String> senders = r.getAllGatewaySenderIds();
        if (senders.contains(sender.getId()) && !sender.isParallel()) {
          r.senderStarted();
        }
      }
    }
  }

  public void gatewaySenderStopped(GatewaySender sender) {
    synchronized (this.rootRegions) {
      Set<LocalRegion> appRegions = getApplicationRegions();
      for (LocalRegion r : appRegions) {
        Set<String> senders = r.getAllGatewaySenderIds();
        if (senders.contains(sender.getId()) && !sender.isParallel()) {
          r.senderStopped();
        }
      }
    }
  }

  public final Set<GatewayReceiver> getGatewayReceivers() {
    return this.allGatewayReceivers;
  }

  public final GatewayReceiver getGatewayReceiver(String Id) {
    final Set<GatewayReceiver> allReceivers = this.allGatewayReceivers;
    for (GatewayReceiver rcvr : allReceivers) {
      if (rcvr.getId().equals(Id)) {
        return rcvr;
      }
    }
    return null;
  }

  public void removeGatewayReceiver(GatewayReceiver receiver) {
    if (isClient()) {
      throw new UnsupportedOperationException(
          "operation is not supported on a client cache");
    }
    synchronized (allGatewayReceiversLock) {
      if (allGatewayReceivers.contains(receiver)) {
        Set<GatewayReceiver> tmp = new HashSet<GatewayReceiver>(
            allGatewayReceivers.size() - 1);
        tmp.addAll(allGatewayReceivers);
        tmp.remove(receiver);
        this.allGatewayReceivers = Collections.unmodifiableSet(tmp);
      }
    }
  }

  public final Set<AsyncEventQueue> getAsyncEventQueues() {
    return this.allAsyncEventQueues;
  }

  public final AsyncEventQueue getAsyncEventQueue(String id) {
    for (AsyncEventQueue asyncEventQueue : this.allAsyncEventQueues) {
      if (asyncEventQueue.getId().equals(id)) {
        return asyncEventQueue;
      }
    }
    return null;
  }

  public void removeAsyncEventQueue(AsyncEventQueue asyncQueue) {
    if (isClient()) {
      throw new UnsupportedOperationException(
          "operation is not supported on a client cache");
    }
    // first remove the gateway sender of the queue
    if (asyncQueue instanceof AsyncEventQueueImpl) {
      removeGatewaySender(((AsyncEventQueueImpl)asyncQueue).getSender());
    }
    // using gateway senders lock since async queue uses a gateway sender
    synchronized (allGatewaySendersLock) {
      this.allAsyncEventQueues.remove(asyncQueue);
    }
  }

  public GatewayHub getGatewayHub() {
    GatewayHubImpl snap[] = allGatewayHubs;
    if (snap.length == 0) {
      return null;
    } else {
      return snap[0];
    }
  }
  
  public boolean hasGatewayHub() {
    return allGatewayHubs.length > 0;
  }

  public List<GatewayHub> getGatewayHubs() {
    GatewayHubImpl snap[] = allGatewayHubs;
    ArrayList<GatewayHub> result = new ArrayList<GatewayHub>();
    for (int i = 0; i < snap.length; i++) {
      result.add(snap[i]);
    }
    return result;
  }

  public GatewayHub getGatewayHub(String id) {
    GatewayHubImpl result = null;
    for (int i = 0; i < allGatewayHubs.length; i++) {
      GatewayHubImpl hub = allGatewayHubs[i];
      if (hub.getId().equals(id)) {
        result = hub;
        break;
      }
    }
    return result;
  }
  
  /* Cache API - get the conflict resolver for WAN */
  public final GatewayConflictResolver getGatewayConflictResolver() {
    synchronized (this.allGatewayHubsLock) {
      return this.gatewayConflictResolver;
    }
  }
  
  /* Cache API - set the conflict resolver for WAN */
  public void setGatewayConflictResolver(GatewayConflictResolver resolver) {
    synchronized (this.allGatewayHubsLock) {
      this.gatewayConflictResolver = resolver;
      LogWriterI18n logger = this.getLoggerI18n();
      if(logger.fineEnabled()){
        if (resolver == null) {
          logger.fine("Removed Gateway Conflict Resolver.");
        } else {
          logger.fine("Gateway Conflict Resolver installed:" + resolver.toString() );
        }
      }
    }
  }

  /**
   * Returns whether a <code>GatewayHub</code> with id is already defined by this <code>Cache</code>.
   *
   * @param id
   *          The id to verify
   * @return whether a <code>GatewayHub</code> with id is already defined by this <code>Cache</code>
   * @guarded.By {@link #allGatewayHubsLock}
   */
  protected boolean alreadyDefinesGatewayHubId(String id) {
    boolean alreadyDefined = false;
    for (int i = 0; i < allGatewayHubs.length; i++) {
      GatewayHubImpl hub = allGatewayHubs[i];
      if (hub.getId().equals(id)) {
        alreadyDefined = true;
        break;
      }
    }
    return alreadyDefined;
  }

  /**
   * Returns whether a <code>GatewayHub</code> with port is already defined by this <code>Cache</code>.
   *
   * @param port
   *          The port to verify
   * @return whether a <code>GatewayHub</code> with port is already defined by this <code>Cache</code>
   * @guarded.By {@link #allGatewayHubsLock}
   */
  protected boolean alreadyDefinesGatewayHubPort(int port) {
    boolean alreadyDefined = false;
    if (port != GatewayHub.DEFAULT_PORT) {
      for (int i = 0; i < allGatewayHubs.length; i++) {
        GatewayHubImpl hub = allGatewayHubs[i];
        if (hub.getPort() == port) {
          alreadyDefined = true;
          break;
        }
      }
    }
    return alreadyDefined;
  }

  public List getBridgeServers() {
    return getCacheServers();
  }

  public List getCacheServers() {
    List bridgeServersWithoutReceiver = null;
    if (!allBridgeServers.isEmpty()) {
    Iterator allBridgeServersIterator = allBridgeServers.iterator();
    while (allBridgeServersIterator.hasNext()) {
      BridgeServerImpl bridgeServer = (BridgeServerImpl) allBridgeServersIterator.next();
      // If BridgeServer is a GatewayReceiver, don't return as part of CacheServers
      if (!bridgeServer.isGatewayReceiver()) {
        if (bridgeServersWithoutReceiver == null) {
          bridgeServersWithoutReceiver = new ArrayList();
        }
        bridgeServersWithoutReceiver.add(bridgeServer);
      }
    }
    }
    if (bridgeServersWithoutReceiver == null) {
      bridgeServersWithoutReceiver = Collections.emptyList();
    }
    return bridgeServersWithoutReceiver;
  }

  public List getBridgeServersAndGatewayReceiver() {
    return allBridgeServers;
  }

  /**
   * notify partitioned regions that this cache requires all of their events
   */
  public void requiresPREvents() {
    synchronized (this.partitionedRegions) {
      for (Iterator it = this.partitionedRegions.iterator(); it.hasNext();) {
        ((PartitionedRegion) it.next()).cacheRequiresNotification();
      }
    }
  }

  /**
   * add a partitioned region to the set of tracked partitioned regions. This is used to notify the regions when this
   * cache requires, or does not require notification of all region/entry events.
   */
  public void addPartitionedRegion(PartitionedRegion r) {
    synchronized (GemFireCacheImpl.class) {
      synchronized (this.partitionedRegions) {
        if (r.isDestroyed()) {
          if (getLogger().fineEnabled()) {
            getLogger().fine("GemFireCache#addPartitionedRegion did not add destroyed " + r);
          }
          return;
        }
        if (this.partitionedRegions.add(r)) {
          getCachePerfStats().incPartitionedRegions(1);
        }
      }
    }
  }

  /**
   * Returns a set of all current partitioned regions for test hook.
   */
  public Set<PartitionedRegion> getPartitionedRegions() {
    synchronized (this.partitionedRegions) {
      return new HashSet<PartitionedRegion>(this.partitionedRegions);
    }
  }

  private TreeMap<String, Map<String, PartitionedRegion>> getPRTrees() {
    // prTree will save a sublist of PRs who are under the same root
    TreeMap<String, Map<String, PartitionedRegion>> prTrees = new TreeMap();
    TreeMap<String, PartitionedRegion> prMap = getPartitionedRegionMap();
    boolean hasColocatedRegion = false;
    for (PartitionedRegion pr : prMap.values()) {
      List<PartitionedRegion> childlist = ColocationHelper.getColocatedChildRegions(pr);
      if (childlist != null && childlist.size() > 0) {
        hasColocatedRegion = true;
        break;
      }
    }

    if (hasColocatedRegion) {
      LinkedHashMap<String, PartitionedRegion> orderedPrMap = orderByColocation(prMap);
      prTrees.put("ROOT", orderedPrMap);
    } else {
      for (PartitionedRegion pr : prMap.values()) {
        String rootName = pr.getRoot().getName();
        TreeMap<String, PartitionedRegion> prSubMap = (TreeMap<String, PartitionedRegion>) prTrees.get(rootName);
        if (prSubMap == null) {
          prSubMap = new TreeMap();
          prTrees.put(rootName, prSubMap);
        }
        prSubMap.put(pr.getFullPath(), pr);
      }
    }

    return prTrees;
  }

  private TreeMap<String, PartitionedRegion> getPartitionedRegionMap() {
    TreeMap<String, PartitionedRegion> prMap = new TreeMap();
    for (Map.Entry<String, Region> entry : ((Map<String,Region>)pathToRegion).entrySet()) {
      String regionName = (String) entry.getKey();
      Region region = entry.getValue();
      
      //Don't wait for non partitioned regions
      if(!(region instanceof PartitionedRegion)) {
        continue;
      }
      // Do a getRegion to ensure that we wait for the partitioned region
      //to finish initialization
      try {
        Region pr = getRegion(regionName);
        if (pr instanceof PartitionedRegion) {
          prMap.put(regionName, (PartitionedRegion) pr);
        }
      } catch (CancelException ce) {
        // if some region throws cancel exception during initialization,
        // then no need to shutdownall them gracefully
      }
    }

    return prMap;
  }

  private LinkedHashMap<String, PartitionedRegion> orderByColocation(TreeMap<String, PartitionedRegion> prMap) {
    LinkedHashMap<String, PartitionedRegion> orderedPrMap = new LinkedHashMap();
    for (PartitionedRegion pr : prMap.values()) {
      addColocatedChildRecursively(orderedPrMap, pr);
    }
    return orderedPrMap;
  }

  private void addColocatedChildRecursively(LinkedHashMap<String, PartitionedRegion> prMap, PartitionedRegion pr) {
    for (PartitionedRegion colocatedRegion : ColocationHelper.getColocatedChildRegions(pr)) {
      addColocatedChildRecursively(prMap, colocatedRegion);
    }
    prMap.put(pr.getFullPath(), pr);
  }

  /**
   * check to see if any cache components require notification from a partitioned region. Notification adds to the
   * messaging a PR must do on each put/destroy/invalidate operation and should be kept to a minimum
   *
   * @param r
   *          the partitioned region
   * @return true if the region should deliver all of its events to this cache
   */
  protected boolean requiresNotificationFromPR(PartitionedRegion r) {
    synchronized (GemFireCacheImpl.class) {
      boolean hasHubs = this.allGatewayHubs.length > 0;
      boolean hasSerialSenders = hasSerialSenders(r);
      boolean result = (hasHubs && r.getEnableGateway()) || hasSerialSenders;
      if (!result) {
        Iterator allBridgeServersIterator = allBridgeServers.iterator();
        while (allBridgeServersIterator.hasNext()) {
          BridgeServerImpl server = (BridgeServerImpl) allBridgeServersIterator.next();
          if (!server.getNotifyBySubscription()) {
            result = true;
            break;
          }
        }

      }
      return result;
    }
  }

  private boolean hasSerialSenders(PartitionedRegion r) {
    boolean hasSenders = false;
    Set<String> senders = r.getAllGatewaySenderIds();
    for (String sender : senders) {
      GatewaySender gs = this.getGatewaySender(sender);
      if (gs != null && !gs.isParallel()) {
        hasSenders = true;
        break;
      }
    }
    return hasSenders;
  }

  /**
   * remove a partitioned region from the set of tracked instances.
   *
   * @see #addPartitionedRegion(PartitionedRegion)
   */
  public void removePartitionedRegion(PartitionedRegion r) {
    synchronized (this.partitionedRegions) {
      if (this.partitionedRegions.remove(r)) {
        getCachePerfStats().incPartitionedRegions(-1);
      }
    }
  }

  public void setIsServer(boolean isServer) {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }
    stopper.checkCancelInProgress(null);

    this.isServer = isServer;
  }

  public final boolean isServer() {
    if (isClient()) {
      return false;
    }
    stopper.checkCancelInProgress(null);

    if (!this.isServer) {
      return (this.allBridgeServers.size() > 0);
    } else {
      return true;
    }
  }

  public final QueryService getQueryService() {
    if (isClient()) {
      Pool p = getDefaultPool();
      if (p == null) {
        throw new IllegalStateException("Client cache does not have a default pool. Use getQueryService(String poolName) instead.");
      } else {
        return p.getQueryService();
      }
    } else {
      return new DefaultQueryService(this);
    }
  }

  public final QueryService getLocalQueryService() {
    return new DefaultQueryService(this);
  }

  /**
   * @return Context jndi context associated with the Cache.
   * @since 4.0
   */
  public final Context getJNDIContext() {
    // if (isClient()) {
    // throw new UnsupportedOperationException("operation is not supported on a client cache");
    // }
    return JNDIInvoker.getJNDIContext();
  }

  /**
   * @return JTA TransactionManager associated with the Cache.
   * @since 4.0
   */
  public final javax.transaction.TransactionManager getJTATransactionManager() {
    // if (isClient()) {
    // throw new UnsupportedOperationException("operation is not supported on a client cache");
    // }
    return JNDIInvoker.getTransactionManager();
  }

  /**
   * return the cq/interest information for a given region name, creating one if it doesn't exist
   */
  public FilterProfile getFilterProfile(String regionName) {
    LocalRegion r = (LocalRegion) getRegion(regionName, true);
    if (r != null) {
      return r.getFilterProfile();
    }
    return null;
  }

  public RegionAttributes getRegionAttributes(String id) {
    return (RegionAttributes) this.namedRegionAttributes.get(id);
  }

  public void setRegionAttributes(String id, RegionAttributes attrs) {
    if (attrs == null) {
      this.namedRegionAttributes.remove(id);
    } else {
      this.namedRegionAttributes.put(id, attrs);
    }
  }

  public Map listRegionAttributes() {
    return Collections.unmodifiableMap(this.namedRegionAttributes);
  }

  private static final ThreadLocal xmlCache = new ThreadLocal();

  /**
   * Returns the cache currently being xml initialized by the thread that calls this method. The result will be null if
   * the thread is not initializing a cache.
   */
  public static GemFireCacheImpl getXmlCache() {
    return (GemFireCacheImpl) xmlCache.get();
  }

  public void loadCacheXml(InputStream stream) throws TimeoutException, CacheWriterException, GatewayException,
      RegionExistsException {
    // make this cache available to callbacks being initialized during xml create
    final Object oldValue = xmlCache.get();
    xmlCache.set(this);
    try {
      CacheXmlParser xml;

      if (xmlParameterizationEnabled) {
        char[] buffer = new char[1024];
        Reader reader = new BufferedReader(new InputStreamReader(stream,
              "ISO-8859-1"));
        Writer stringWriter = new StringWriter();

        int n = -1;
        while ((n = reader.read(buffer)) != -1) {
          stringWriter.write(buffer, 0, n);
        }

        /** Now replace all replaceable system properties here using <code>PropertyResolver</code> */
        String replacedXmlString = resolver.processUnresolvableString(stringWriter.toString());

        /*
         * Turn the string back into the default encoding so that the XML
         * parser can work correctly in the presence of
         * an "encoding" attribute in the XML prolog.
         */
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(baos, "ISO-8859-1");
        writer.write(replacedXmlString);
        writer.flush();

        xml = CacheXmlParser.parse(new ByteArrayInputStream(baos.toByteArray()));
      } else {
        xml = CacheXmlParser.parse(stream);
      }
      xml.create(this);
    } catch (IOException e) {
      throw new CacheXmlException("Input Stream could not be read for system property substitutions.");
    } finally {
      xmlCache.set(oldValue);
    }
  }

  public void readyForEvents() {
    PoolManagerImpl.readyForEvents(this.system, false);
  }

  /**
   * This cache's reliable message queue factory. Should always have an instance of it.
   */
  private ReliableMessageQueueFactory rmqFactory;

  private List<File> backupFiles = Collections.emptyList();

  /**
   * Initializes the reliable message queue. Needs to be called at cache creation
   *
   * @throws IllegalStateException
   *           if the factory is in use
   */
  private void initReliableMessageQueueFactory() {
    synchronized (GemFireCacheImpl.class) {
      if (this.rmqFactory != null) {
        this.rmqFactory.close(false);
      }
      this.rmqFactory = new ReliableMessageQueueFactoryImpl();
    }
  }

  /**
   * Returns this cache's ReliableMessageQueueFactory.
   *
   * @since 5.0
   */
  public ReliableMessageQueueFactory getReliableMessageQueueFactory() {
    return this.rmqFactory;
  }

  public final InternalResourceManager getResourceManager() {
    return getResourceManager(true);
  }

  public final InternalResourceManager getResourceManager(
      boolean checkCancellationInProgress) {
    if (checkCancellationInProgress) {
      stopper.checkCancelInProgress(null);
    }
    return this.resourceManager;
  }

  public void setBackupFiles(List<File> backups) {
    this.backupFiles = backups;
  }

  public List<File> getBackupFiles() {
    return Collections.unmodifiableList(this.backupFiles);
  }

  public BackupManager startBackup(InternalDistributedMember sender) 
  throws IOException {
    BackupManager manager = new BackupManager(sender, this);
    if (!this.backupManager.compareAndSet(null, manager)) {
      // TODO prpersist internationalize this
      throw new IOException("Backup already in progress");
    }
    manager.start();
    return manager;
  }

  public void clearBackupManager() {
    this.backupManager.set(null);
  }

  public BackupManager getBackupManager() {
    return this.backupManager.get();
  }

  // //////////////////// Inner Classes //////////////////////

  // TODO make this a simple int guarded by riWaiters and get rid of the double-check
  private final AI registerInterestsInProgress = CFactory.createAI();

  private final ArrayList<SimpleWaiter> riWaiters = new ArrayList<SimpleWaiter>();

  private TypeRegistry pdxRegistry; // never changes but is currently not
                                    // initialized in constructor

  /**
   * update stats for completion of a registerInterest operation
   */
  public void registerInterestCompleted() {
    // Don't do a cancellation check, it's just a moot point, that's all
    // GemFireCache.this.getCancelCriterion().checkCancelInProgress(null);
    if (GemFireCacheImpl.this.isClosing) {
      return; // just get out, all of the SimpleWaiters will die of their own accord
    }
    LogWriterI18n l = GemFireCacheImpl.this.getLoggerI18n();
    int cv = registerInterestsInProgress.decrementAndGet();
    if (l.fineEnabled()) {
      l.fine("registerInterestCompleted: new value = " + cv);
    }
    if (cv == 0) {
      synchronized (riWaiters) {
        // TODO double-check
        cv = registerInterestsInProgress.get();
        if (cv == 0) { // all clear
          if (l.fineEnabled()) {
            l.fine("registerInterestCompleted: Signalling end of register-interest");
          }
          Iterator it = riWaiters.iterator();
          while (it.hasNext()) {
            SimpleWaiter sw = (SimpleWaiter) it.next();
            sw.doNotify();
          }
          riWaiters.clear();
        } // all clear
      } // synchronized
    }
  }

  public void registerInterestStarted() {
    // Don't do a cancellation check, it's just a moot point, that's all
    // GemFireCache.this.getCancelCriterion().checkCancelInProgress(null);
    int newVal = registerInterestsInProgress.incrementAndGet();
    LogWriterI18n l = GemFireCacheImpl.this.getLoggerI18n();
    if (l.fineEnabled()) {
      l.fine("registerInterestsStarted: new count = " + newVal);
    }
  }

  /**
   * update stats for initiation of a registerInterest operation
   */
  /**
   * Blocks until no register interests are in progress.
   */
  public void waitForRegisterInterestsInProgress() {
    // In *this* particular context, let the caller know that
    // his cache has been cancelled. doWait below would do that as
    // well, so this is just an early out.
    GemFireCacheImpl.this.getCancelCriterion().checkCancelInProgress(null);
    final LogWriterI18n l = GemFireCacheImpl.this.getLoggerI18n();

    int count = registerInterestsInProgress.get();
    SimpleWaiter sw = null;
    if (count > 0) {
      synchronized (riWaiters) {
        // TODO double-check
        count = registerInterestsInProgress.get();
        if (count > 0) {
          if (l.fineEnabled()) {
            l.fine("waitForRegisterInterestsInProgress: count =" + count + ")");
          }
          sw = new SimpleWaiter();
          riWaiters.add(sw);
        }
      } // synchronized
      if (sw != null) {
        sw.doWait();
      }
    }
  }

  /**
   * Wait for given sender queue to flush for given timeout.
   * 
   * @param id
   *          ID of GatewaySender or AsyncEventQueue
   * @param isAsyncListener
   *          true if this is for an AsyncEventQueue and false if for a
   *          GatewaySender
   * @param maxWaitTime
   *          maximum time to wait in seconds; zero or -ve means infinite wait
   * 
   * @return zero if maxWaitTime was not breached, -1 if queue could not be
   *         found or is closed, and elapsed time if timeout was breached
   */
  public int waitForSenderQueueFlush(String id, boolean isAsyncListener,
      int maxWaitTime) {
    getCancelCriterion().checkCancelInProgress(null);
    AbstractGatewaySender gatewaySender = null;
    if (isAsyncListener) {
      AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)
          getAsyncEventQueue(id);
      if (asyncQueue != null) {
        gatewaySender = asyncQueue.getSender();
      }
    }
    else {
      gatewaySender = (AbstractGatewaySender)getGatewaySender(id);
    }
    RegionQueue rq;
    final long startTime = System.currentTimeMillis();
    long elapsedTime;
    if (maxWaitTime <= 0) {
      maxWaitTime = Integer.MAX_VALUE;
    }
    while (gatewaySender != null && gatewaySender.isRunning()
        && (rq = gatewaySender.getQueue()) != null) {
      if (rq.size() == 0) {
        // return zero since it was not a timeout
        return 0;
      }
      try {
        Thread.sleep(500);
        getCancelCriterion().checkCancelInProgress(null);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        getCancelCriterion().checkCancelInProgress(ie);
      }
      // clear interrupted flag before retry
      Thread.interrupted();
      elapsedTime = System.currentTimeMillis() - startTime;
      if (elapsedTime >= (maxWaitTime * 1000L)) {
        // return elapsed time
        return (int)(elapsedTime / 1000L);
      }
    }
    return -1;
  }

  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
  public void setQueryMonitorRequiredForResourceManager(boolean required) {
    QUERY_MONITOR_REQUIRED_FOR_RESOURCE_MANAGER = required;
  }
  
  public boolean isQueryMonitorDisabledForLowMemory() {
    return QUERY_MONITOR_DISABLED_FOR_LOW_MEM;
  }
  
  /**
   * Returns the QueryMonitor instance based on system property MAX_QUERY_EXECUTION_TIME.
   * @since 6.0
   */
  public QueryMonitor getQueryMonitor() {
    //Check to see if monitor is required if ResourceManager critical heap percentage is set
    //@see com.gemstone.gemfire.cache.control.ResourceManager#setCriticalHeapPercentage(int)
    //or whether we override it with the system variable;
    boolean monitorRequired = !QUERY_MONITOR_DISABLED_FOR_LOW_MEM && QUERY_MONITOR_REQUIRED_FOR_RESOURCE_MANAGER;
    // Added for DUnit test purpose, which turns-on and off the this.TEST_MAX_QUERY_EXECUTION_TIME.
    if (!(this.MAX_QUERY_EXECUTION_TIME > 0 || this.TEST_MAX_QUERY_EXECUTION_TIME > 0 || monitorRequired)) {
      // if this.TEST_MAX_QUERY_EXECUTION_TIME is set, send the QueryMonitor.
      // Else send null, so that the QueryMonitor is turned-off.
      return null;
    }

    // Return the QueryMonitor service if MAX_QUERY_EXECUTION_TIME is set or it is required by the ResourceManager and not overriden by system property.
    if ((this.MAX_QUERY_EXECUTION_TIME > 0 || this.TEST_MAX_QUERY_EXECUTION_TIME > 0 || monitorRequired) && this.queryMonitor == null) {
      synchronized (queryMonitorLock) {
        if (this.queryMonitor == null) {
          int maxTime = MAX_QUERY_EXECUTION_TIME > TEST_MAX_QUERY_EXECUTION_TIME ? MAX_QUERY_EXECUTION_TIME
              : TEST_MAX_QUERY_EXECUTION_TIME;
          
          if (monitorRequired && maxTime < 0) {
            //this means that the resource manager is being used and we need to monitor query memory usage
            //If no max execution time has been set, then we will default to five hours
            maxTime = FIVE_HOURS;
          }

         
          this.queryMonitor = new QueryMonitor(getLoggerI18n(), maxTime);
          final LogWriterImpl.LoggingThreadGroup group = LogWriterImpl.createThreadGroup("QueryMonitor Thread Group",
              getLoggerI18n());
          Thread qmThread = new Thread(group, this.queryMonitor, "QueryMonitor Thread");
          qmThread.setDaemon(true);
          qmThread.start();
          if (this.getLogger().fineEnabled()) {
            this.getLogger().fine("QueryMonitor thread started.");
          }
        }
      }
    }
    return this.queryMonitor;
  }

  /**
   * Simple class to allow waiters for register interest. Has at most one thread that ever calls wait.
   *
   * @since 5.7
   */
  private class SimpleWaiter {
    private boolean notified = false;

    SimpleWaiter() {
    }

    public void doWait() {
      synchronized (this) {
        while (!this.notified) {
          GemFireCacheImpl.this.getCancelCriterion().checkCancelInProgress(null);
          boolean interrupted = Thread.interrupted();
          try {
            this.wait(1000);
          } catch (InterruptedException ex) {
            interrupted = true;
          } finally {
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
          }
        }
      }
    }

    public void doNotify() {
      synchronized (this) {
        this.notified = true;
        this.notifyAll();
      }
    }
  }

  private void sendAddCacheServerProfileMessage() {
    DM dm = this.getDistributedSystem().getDistributionManager();
    Set otherMembers = dm.getOtherDistributionManagerIds();
    AddCacheServerProfileMessage msg = new AddCacheServerProfileMessage();
    msg.operateOnLocalCache(this);
    if (!otherMembers.isEmpty()) {
      final LogWriterI18n l = GemFireCacheImpl.this.getLoggerI18n();
      if (l.fineEnabled()) {
        l.fine("Sending add cache server profile message to other members.");
      }
      ReplyProcessor21 rp = new ReplyProcessor21(dm, otherMembers);
      msg.setRecipients(otherMembers);
      msg.processorId = rp.getProcessorId();
      dm.putOutgoing(msg);

      // Wait for replies.
      try {
        rp.waitForReplies();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public final TXManagerImpl getTxManager() {
    return this.txMgr;
  }

  /*
  public final PersistentUUIDAdvisor getTXIdAdvisor() {
    return this.txIdAdvisor;
  }
  */

  /**
   * @since 6.5
   */
  public <K, V> RegionFactory<K, V> createRegionFactory(RegionShortcut atts) {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    } else {
      return new RegionFactoryImpl<K, V>(this, atts);
    }
  }

  /**
   * @since 6.5
   */
  public <K, V> RegionFactory<K, V> createRegionFactory() {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }
    return new RegionFactoryImpl<K, V>(this);
  }

  /**
   * @since 6.5
   */
  public <K, V> RegionFactory<K, V> createRegionFactory(String regionAttributesId) {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }
    return new RegionFactoryImpl<K, V>(this, regionAttributesId);
  }

  /**
   * @since 6.5
   */
  public <K, V> RegionFactory<K, V> createRegionFactory(RegionAttributes<K, V> regionAttributes) {
    if (isClient()) {
      throw new UnsupportedOperationException("operation is not supported on a client cache");
    }
    return new RegionFactoryImpl<K, V>(this, regionAttributes);
  }

  /**
   * @since 6.5
   */
  public <K, V> ClientRegionFactory<K, V> createClientRegionFactory(ClientRegionShortcut atts) {
    return new ClientRegionFactoryImpl<K, V>(this, atts);
  }

  public <K, V> ClientRegionFactory<K, V> createClientRegionFactory(String refid) {
    return new ClientRegionFactoryImpl<K, V>(this, refid);
  }

  /**
   * @since 6.5
   */
  public final QueryService getQueryService(String poolName) {
    Pool p = PoolManager.find(poolName);
    if (p == null) {
      throw new IllegalStateException("Could not find a pool named " + poolName);
    } else {
      return p.getQueryService();
    }
  }

  public RegionService createAuthenticatedView(Properties properties) {
    Pool pool = getDefaultPool();
    if (pool == null) {
      throw new IllegalStateException("This cache does not have a default pool");
    }
    return createAuthenticatedCacheView(pool, properties);
  }

  public RegionService createAuthenticatedView(Properties properties, String poolName) {
    Pool pool = PoolManager.find(poolName);
    if (pool == null) {
      throw new IllegalStateException("Pool " + poolName + " does not exist");
    }
    return createAuthenticatedCacheView(pool, properties);
  }

  public RegionService createAuthenticatedCacheView(Pool pool, Properties properties) {
    if (pool.getMultiuserAuthentication()) {
      return ((PoolImpl) pool).createAuthenticatedCacheView(properties);
    } else {
      throw new IllegalStateException("The pool " + pool.getName() + " did not have multiuser-authentication set to true");
    }
  }

  public static void initializeRegionShortcuts(Cache c) {
    // no shortcuts for GemFireXD since these are not used and some combinations
    // are not supported
    if (gfxdSystem()) {
      return;
    }
    for (RegionShortcut pra : RegionShortcut.values()) {
      switch (pra) {
      case PARTITION: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        af.setPartitionAttributes(paf.create());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_REDUNDANT: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(1);
        af.setPartitionAttributes(paf.create());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_PERSISTENT: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        af.setPartitionAttributes(paf.create());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_REDUNDANT_PERSISTENT: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(1);
        af.setPartitionAttributes(paf.create());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        af.setPartitionAttributes(paf.create());
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_REDUNDANT_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(1);
        af.setPartitionAttributes(paf.create());
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_PERSISTENT_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        af.setPartitionAttributes(paf.create());
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_REDUNDANT_PERSISTENT_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(1);
        af.setPartitionAttributes(paf.create());
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_HEAP_LRU: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        af.setPartitionAttributes(paf.create());
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_REDUNDANT_HEAP_LRU: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(1);
        af.setPartitionAttributes(paf.create());
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case REPLICATE: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.REPLICATE);
        af.setScope(Scope.DISTRIBUTED_ACK);
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case REPLICATE_PERSISTENT: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);
        af.setScope(Scope.DISTRIBUTED_ACK);
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case REPLICATE_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.REPLICATE);
        af.setScope(Scope.DISTRIBUTED_ACK);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case REPLICATE_PERSISTENT_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);
        af.setScope(Scope.DISTRIBUTED_ACK);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case REPLICATE_HEAP_LRU: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.REPLICATE);
        af.setScope(Scope.DISTRIBUTED_ACK);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case LOCAL: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.NORMAL);
        af.setScope(Scope.LOCAL);
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case LOCAL_PERSISTENT: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);
        af.setScope(Scope.LOCAL);
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case LOCAL_HEAP_LRU: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.NORMAL);
        af.setScope(Scope.LOCAL);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case LOCAL_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.NORMAL);
        af.setScope(Scope.LOCAL);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case LOCAL_PERSISTENT_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);
        af.setScope(Scope.LOCAL);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_PROXY: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setLocalMaxMemory(0);
        af.setPartitionAttributes(paf.create());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_PROXY_REDUNDANT: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PARTITION);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setLocalMaxMemory(0);
        paf.setRedundantCopies(1);
        af.setPartitionAttributes(paf.create());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case REPLICATE_PROXY: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.EMPTY);
        af.setScope(Scope.DISTRIBUTED_ACK);
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PARTITION_HDFS: {
    	  AttributesFactory af = new AttributesFactory();
          af.setDataPolicy(DataPolicy.HDFS_PARTITION);
          PartitionAttributesFactory paf = new PartitionAttributesFactory();
          af.setPartitionAttributes(paf.create());
          af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
          af.setHDFSWriteOnly(false);
          c.setRegionAttributes(pra.toString(), af.create());
          break;
        }
      case PARTITION_REDUNDANT_HDFS: {
    	  AttributesFactory af = new AttributesFactory();
          af.setDataPolicy(DataPolicy.HDFS_PARTITION);
          PartitionAttributesFactory paf = new PartitionAttributesFactory();
          paf.setRedundantCopies(1);
          af.setPartitionAttributes(paf.create());
          af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
          af.setHDFSWriteOnly(false);
          c.setRegionAttributes(pra.toString(), af.create());
          break;
        }
      case PARTITION_WRITEONLY_HDFS_STORE: {
        AttributesFactory af = new AttributesFactory();
          af.setDataPolicy(DataPolicy.HDFS_PARTITION);
          PartitionAttributesFactory paf = new PartitionAttributesFactory();
          af.setPartitionAttributes(paf.create());
          af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
          af.setHDFSWriteOnly(true);
          c.setRegionAttributes(pra.toString(), af.create());
          break;
        }
      case PARTITION_REDUNDANT_WRITEONLY_HDFS_STORE: {
        AttributesFactory af = new AttributesFactory();
          af.setDataPolicy(DataPolicy.HDFS_PARTITION);
          PartitionAttributesFactory paf = new PartitionAttributesFactory();
          paf.setRedundantCopies(1);
          af.setPartitionAttributes(paf.create());
          af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
          af.setHDFSWriteOnly(true);
          c.setRegionAttributes(pra.toString(), af.create());
          break;
        }
      default:
        throw new IllegalStateException("unhandled enum " + pra);
      }
    }
  }

  public static void initializeClientRegionShortcuts(Cache c) {
    for (ClientRegionShortcut pra : ClientRegionShortcut.values()) {
      switch (pra) {
      case LOCAL: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.NORMAL);
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case LOCAL_PERSISTENT: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case LOCAL_HEAP_LRU: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.NORMAL);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes());
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case LOCAL_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.NORMAL);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case LOCAL_PERSISTENT_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        c.setRegionAttributes(pra.toString(), af.create());
        break;
      }
      case PROXY: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.EMPTY);
        UserSpecifiedRegionAttributes ra = (UserSpecifiedRegionAttributes) af.create();
        ra.requiresPoolName = true;
        c.setRegionAttributes(pra.toString(), ra);
        break;
      }
      case CACHING_PROXY: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.NORMAL);
        UserSpecifiedRegionAttributes ra = (UserSpecifiedRegionAttributes) af.create();
        ra.requiresPoolName = true;
        c.setRegionAttributes(pra.toString(), ra);
        break;
      }
      case CACHING_PROXY_HEAP_LRU: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.NORMAL);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes());
        UserSpecifiedRegionAttributes ra = (UserSpecifiedRegionAttributes) af.create();
        ra.requiresPoolName = true;
        c.setRegionAttributes(pra.toString(), ra);
        break;
      }
      case CACHING_PROXY_OVERFLOW: {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.NORMAL);
        af.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
        UserSpecifiedRegionAttributes ra = (UserSpecifiedRegionAttributes) af.create();
        ra.requiresPoolName = true;
        c.setRegionAttributes(pra.toString(), ra);
        break;
      }
      default:
        throw new IllegalStateException("unhandled enum " + pra);
      }
    }
  }

  public void beginDestroy(String path, DistributedRegion region) {
    this.regionsInDestroy.putIfAbsent(path, region);
  }

  public void endDestroy(String path, DistributedRegion region) {
    this.regionsInDestroy.remove(path, region);
  }

  public DistributedRegion getRegionInDestroy(String path) {
    return this.regionsInDestroy.get(path);
  }

  public final DistributionAdvisee getGfxdAdvisee() {
    return this.gfxdAdvisee;
  }

  public void setGfxdAdvisee(DistributionAdvisee advisee) {
    this.gfxdAdvisee = advisee;
  }

  /**
   * Mark a node as initialized or not initialized. Used by GemFireXD to avoid
   * creation of buckets or routing of operations/functions on a node that is
   * still in the DDL replay phase.
   */
  public boolean updateNodeStatus(InternalDistributedMember member,
      boolean initialized) {
    HashSet<BucketAdvisor> advisors = null;
    synchronized (this.unInitializedMembers) {
      if (initialized) {
        if (this.unInitializedMembers.remove(member)) {
          if (member.equals(getMyId())) {
            // don't invoke volunteerForPrimary() inside the lock since
            // BucketAdvisor will also require the lock after locking itself
            advisors = new HashSet<BucketAdvisor>(
                this.deferredVolunteerForPrimary);
            this.deferredVolunteerForPrimary.clear();
          }
        } else {
          return false;
        }
      } else {
        return this.unInitializedMembers.add(member);
      }
    }
    if (advisors != null) {
      final LogWriterI18n logger = getLoggerI18n();
      for (BucketAdvisor advisor : advisors) {
        if (this.gfxdSystem) {
          if (isPRForGlobalIndex(advisor)) {
            continue;
          }
        }
        if (logger.fineEnabled()) {
          logger.fine("Invoking volunteer for primary for deferred bucket "
              + "post GemFireXD DDL replay for BucketAdvisor: " + advisor);
        }
        advisor.volunteerForPrimary();
      }
    }
    return true;
  }

  /**
   * Return true if this node is still not initialized else false.
   */
  public boolean isUnInitializedMember(InternalDistributedMember member) {
    synchronized (this.unInitializedMembers) {
      return this.unInitializedMembers.contains(member);
    }
  }

  /**
   * Return false for volunteer primary if this node is not currently initialized. Also adds the {@link BucketAdvisor}
   * to a list that will be replayed once this node is initialized.
   */
  public boolean doVolunteerForPrimary(BucketAdvisor advisor) {
    synchronized (this.unInitializedMembers) {
      if (!this.unInitializedMembers.contains(getMyId())) {
        return true;
      }
      if (this.gfxdSystem) {
        // Check if this is a global index region. If yes then don't defer the
        // volunteering for primary. Fixes #48383
        if (isPRForGlobalIndex(advisor)) {
          return true;
        }
      }
      final LogWriterI18n logger = getLoggerI18n();
      if (logger.fineEnabled()) {
        logger.fine("Deferring volunteer for primary due to uninitialized "
            + "node (GemFireXD DDL replay) for BucketAdvisor: " + advisor);
      }
      this.deferredVolunteerForPrimary.add(advisor);
      return false;
    }
  }

  private boolean isPRForGlobalIndex(BucketAdvisor advisor) {
    PartitionedRegion pr = advisor.getPartitionedRegion();
    final StaticSystemCallbacks sysCb = GemFireCacheImpl.getInternalProductCallbacks();
    if (sysCb != null) {
      if (sysCb.isPRForGlobalIndex(pr)) {
        return true;
      }
    }
    return false;
  }
  /**
   * Remove all the uninitialized members from the given collection.
   */
  public final void removeUnInitializedMembers(Collection<InternalDistributedMember> members) {
    synchronized (this.unInitializedMembers) {
      for (final InternalDistributedMember m : this.unInitializedMembers) {
        members.remove(m);
      }
    }
  }

  public final boolean isGFXDSystem() {
    return gfxdSystem;
  }

  public static boolean gfxdSystem() {
    /*
    final GemFireCacheImpl inst = instance;
    if (inst != null) {
      return inst.isGfxdSystem();
    }
    else {
      return Boolean.getBoolean(GEMFIREXD_PRODUCT_PROP);
    }
    */
    return gfxdSystem;
  }

  public static void setGFXDSystem(final boolean v) {
    // check the stack to see if this is really from a GemFireXD system
    gfxdSystem = v ? SystemProperties.isUsingGemFireXDEntryPoint() : false;
  }

  /**
   * Only for tests.
   */
  public static void setGFXDSystemForTests() {
    gfxdSystem = true;
  }

  public final TombstoneService getTombstoneService() {
    return this.tombstoneService;
  }

  public final TypeRegistry getPdxRegistry() {
    return this.pdxRegistry;
  }

  public final boolean getPdxReadSerialized() {
    return this.cacheConfig.pdxReadSerialized;
  }

  public final PdxSerializer getPdxSerializer() {
    return this.cacheConfig.pdxSerializer;
  }

  public final String getPdxDiskStore() {
    return this.cacheConfig.pdxDiskStore;
  }

  public final boolean getPdxPersistent() {
    return this.cacheConfig.pdxPersistent;
  }

  public final boolean getPdxIgnoreUnreadFields() {
    return this.cacheConfig.pdxIgnoreUnreadFields;
  }

  public static final StaticSystemCallbacks getInternalProductCallbacks() {
    return FactoryStatics.systemCallbacks;
  }

  /**
   * Some common system functionality overridden by GemFireXD. Ideally everything
   * in the code that uses {@link GemFireCacheImpl#gfxdSystem()} should use this
   * instead.
   * 
   * @author swale
   * @since 7.0
   */
  public static interface StaticSystemCallbacks extends
      SystemProperties.Callbacks {
    
    /**
     * Log in-memory until the buffer is full, instead of file io
     * synchronization. This is mainly used in the GemFire code that invokes
     * GemFireXD's SanityManager logging.<br>
     * {@link ArrayUtils#objectStringNonRecursive(Object)} is used for string conversion
     * on objects which is delayed and is called by another thread. so for
     * thread shared objects toString() or ArrayUtils.objectStringXXX should be
     * invoked as part of object[] by the caller. <br>
     * <br>
     * <code>
     * <b>Example:</b>
     * GemFireCacheImpl.getInternalProductCallbacks().logAsync(new Object[] { null, null,
     *       "SampleLogging", this.toString(), "delay object toString", this});
     * </code> <br>
     * <br>
     * 
     * @param line
     *          log line with first 2 elements always set to null for timestamp
     *          & threadID.
     */
    public void logAsync(Object []line);

    /**
     * Any initializations required for transactions or otherwise before
     * starting disk recovery of regions and TXStates that are marked as
     * deferred ( {@link DiskRegionFlag#DEFER_RECOVERY}).
     * 
     * @param timeout
     *          the maximum number of milliseconds to wait; a negative value
     *          indicates infinite wait
     * 
     * @return true if the initialization completed within the given timeout and
     *         false if timeout was exhausted without completion of
     *         initialization
     */
    public boolean initializeForDeferredRegionsRecovery(long timeout);

    /** If this node is booted an an ADMIN node */
    public boolean isAdmin();

    /** If this node is booted as a SnappyStore node */
    public boolean isSnappyStore();

    /** If this node is booted as a Accessor node */
    public boolean isAccessor();

    /**
     * If this node has been booted as one that can perform operations as an
     * accessor or datastore (i.e. non-admin-only, non-agent-only,
     * non-locator-only node).
     */
    public boolean isOperationNode();

    /**
     * Callback to wait for any basic initializations to complete before
     * compaction or other async disk tasks (e.g. KRF creation) can proceed.
     * Currently this waits for DDL region to complete basic initialization so
     * that DDL meta-data can be kept in sync with others especially when
     * recovering from old product versions. It also acquires the DataDictionary
     * read lock which should be released at the end in a finally block by
     * invoking {@link #endAsyncDiskTask(DiskStoreImpl)}.
     * 
     * @param timeout
     *          the maximum number of milliseconds to wait; a negative value
     *          indicates infinite wait
     * @param ds
     *          the disk store for which the compaction is waiting
     * 
     * @return true if the initialization completed within the given timeout
     *         else false
     */
    public boolean waitBeforeAsyncDiskTask(long timeout, DiskStoreImpl ds);

    /**
     * Should be invoked in a finally block at the end of an async disk task
     * when {@link #waitBeforeAsyncDiskTask(long, DiskStoreImpl)} returns true.
     */
    public void endAsyncDiskTask(DiskStoreImpl ds);

    /**
     * Return true if indexes should be persisted for given DiskStore.
     */
    public boolean persistIndexes(DiskStoreImpl ds);

    /**
     * Wait for async index recovery task to first let all indexes that require
     * recovery be created.
     */
    public void waitForAsyncIndexRecovery(DiskStoreImpl ds);

    /**
     * Get the list of all valid indexes at this point. Intended to be invoked
     * only after {@link #waitForAsyncIndexRecovery} during recovery, or with
     * DataDictionary lock so that a stable set of indexes can be fetched. The
     * value is an index container object on which various methods of
     * {@link SortedIndexContainer} can be invoked.
     */
    public Set<SortedIndexContainer> getAllLocalIndexes(DiskStoreImpl ds);

    /**
     * Get a global {@link MemoryThresholdListener} for the JVM.
     */
    public MemoryThresholdListener getMemoryThresholdListener();

    /**
     * GemFireXD may need to do more initialization in case of Offline tasks like
     * validate disk store, offline compaction or disk store upgrade which does
     * not initialize the GFXD engine.
     */
    public void initializeForOffline();

    /**
     * Whether "concurrency-checks" are supported at all for given region.
     */
    public boolean supportsRegionConcurrencyChecks(LocalRegion region);

    /**
     * Whether to allow changing "concurrency-checks-enabled" flag
     * @param region
     * @return true if changing "concurrency-checks-enabled" flag is allowed
     */
    public boolean allowConcurrencyChecksOverride(LocalRegion region);

    /**
     * Determines whether delete should win in case of delete-update and delete-insert conflicts 
     */
    
    public boolean shouldDeleteWinOnConflict();
    /**
     * The default for "concurrency-checks-enabled" for regions.
     */
    public boolean defaultRegionConcurrencyChecksEnabled();
    
    /**
     * Determines whether the exception of the type constarint violation
     * @param ex
     * @return true if exception is for constraint violation
     */
    public boolean isConstraintViolation(Exception ex);

    /**
     * Return true if need to add entry into the global UUID persistent region
     * for this given persistent region.
     */
    public boolean createUUIDPersistentRegion(LocalRegion region);

    /**
     * For GemFireXD refresh the key when the value changes for the case when key
     * points to the value part itself.
     * 
     * @throws IllegalAccessException
     *           if both key and value are null and caller needs to retry
     *           reading the two
     */
    public Object entryRefreshKey(Object key, Object oldValue, Object newValue,
        Object containerInfo) throws IllegalAccessException;

    /**
     * Get the key object to be used in the RegionMap for this entry. For
     * GemFireXD this can be the RegionEntry itself.
     * 
     * @return the key to be used for the entry, or null if both key and value
     *         are null and caller needs to retry reading the two
     */
    public Object entryGetKey(Object key, AbstractRegionEntry entry);

    /**
     * Used as the result of {@link RegionEntry#getKeyCopy()} in GemFireXD.
     * 
     * @return the key to be used for external callers (a copy if required), or
     *         null if both key and value are null and caller needs to retry
     *         reading the two
     */
    public Object entryGetKeyCopy(Object key, AbstractRegionEntry entry);

    /**
     * Check the value for being a valid type as expected by the underlying
     * system.
     */
    public void entryCheckValue(Object val);

    /**
     * Used for GemFireXD to return ExtraTableInfo if key is part of the value row
     * itself.
     */
    public Object entryGetContainerInfoForKey(AbstractRegionEntry entry);

    /**
     * Get the region from the GemFireXD table schema object.
     * 
     * @param containerInfo
     *          the result of {@link RegionEntry#getContainerInfo()} which is an
     *          ExtraTableInfo
     */
    public LocalRegion getRegionFromContainerInfo(Object containerInfo);

    /**
     * Calculate the hashcode to be used for an entry given its key and value.
     * For GemFireXD this can calculate the hash directly from the value bytes in
     * case key is just a pointer to the value portion of the entry.
     * 
     * @throws IllegalAccessException
     *           if both key and value are null and caller needs to retry
     *           reading the two
     */
    public int entryHashCode(Object key, AbstractRegionEntry entry)
        throws IllegalAccessException;

    /**
     * Compare a RegionEntry against the given object for value equality.
     * 
     * @throws IllegalAccessException
     *           if both key and value are null and caller needs to retry
     *           reading the two
     */
    public boolean entryEquals(final Object key, Object value,
        AbstractRegionEntry entry, Object other) throws IllegalAccessException;

    /**
     * Get size of key object in bytes. A negative return value indicates both
     * serialized key and value bytes were null and caller should retry the
     * operation (can happen if update and read happen concurrently).
     * 
     * @return a positive entry size in bytes of the key object, or a negative
     *         value if both key and value are null and caller needs to retry
     *         reading the two
     */
    public int entryKeySizeInBytes(Object key, AbstractRegionEntry entry);

    /**
     * Callback invoked when product version change is detected for given value
     * (or raw key bytes) recovered from disk or received from a remote node.
     * This is mostly useful for raw pre-serialized objects like byte[]s that
     * GemFireXD uses that cannot otherwise handle this in their fromData calls.
     */
    public Object fromVersion(byte[] bytes, int bytesLen, boolean serialized,
        Version version, ByteArrayDataInput in);

    /**
     * Callback invoked when product version change is detected for given value
     * (or raw key bytes) recovered from disk or received from a remote node.
     * This is mostly useful for raw pre-serialized objects like byte[]s that
     * GemFireXD uses that cannot otherwise handle this in their fromData calls.
     *
     * Return value is required to be serialized byte[] for serialized objects
     * and raw byte[] for non-serialized ones.
     */
    public byte[] fromVersionToBytes(byte[] bytes, int bytesLen,
        boolean serialized, Version version, ByteArrayDataInput in,
        HeapDataOutputStream hdos);

    /**
     * In case a RegionEntry is unable to find valid key and value objects even
     * after repeatedly trying to read them, then this method will be invoked
     * checking for cache close case before returning an appropriate error.
     */
    public RuntimeException checkCacheForNullKeyValue(String method);

    /**
     * Create an instance of {@link NonLocalRegionEntry} for GemFireXD.
     */
    public NonLocalRegionEntry newNonLocalRegionEntry();

    /**
     * Create an instance of {@link NonLocalRegionEntry} for GemFireXD.
     */
    public NonLocalRegionEntry newNonLocalRegionEntry(RegionEntry re,
        LocalRegion region, boolean allowTombstones);

    public NonLocalRegionEntry newNonLocalRegionEntry(RegionEntry re,
        LocalRegion region, boolean allowTombstones, boolean faultInValue);
    /**
     * Create an instance of {@link NonLocalRegionEntryWithStats} for GemFireXD.
     */
    public NonLocalRegionEntryWithStats newNonLocalRegionEntryWithStats();

    /**
     * Create an instance of {@link NonLocalRegionEntryWithStats} for GemFireXD.
     */
    public NonLocalRegionEntryWithStats newNonLocalRegionEntryWithStats(
        RegionEntry re, LocalRegion region, boolean allowTombstones);

    /**
     * Create an instance of {@link NonLocalRegionEntry} for GemFireXD.
     */
    public NonLocalRegionEntry newNonLocalRegionEntry(Object key, Object value,
        LocalRegion region, VersionTag<?> versionTag);

    /**
     * Invoked if this JVM is waiting for another member to initialize for disk
     * region GII.
     */
    public void waitingForDataSync(String regionPath,
        Set<PersistentMemberID> membersToWaitFor, Set<Integer> missingBuckets,
        PersistentMemberID myId, String message);

    /**
     * Invoked when a previous {@link #waitingForDataSync} has ended and disk
     * region GII has commenced.
     */
    public void endWaitingForDataSync(String regionPath,
        PersistentMemberID myId);

    /**
     * Stop all the network servers.
     */
    public void stopNetworkServers();

    /**
     * Any GemFireXD cleanup actions to be done before close.
     */
    public void emergencyClose();

    /**
     * Returns true if this subsystem supports {@link CommandService} and false
     * otherwise.
     */
    public boolean supportsCommandService();

    /**
     * Whether an existing region should be destroyed in persistent files first.
     * This will be true when table is being created by an explicit DDL
     * currently in GemFireXD (and is not during initial DDL replay).
     * 
     * Now this is a fail-safe only for the case of recovering from older
     * persistent files. For other cases this is now handled for GemFireXD by
     * GemFire persistence layer itself by storing the UUID for the DDL in .if
     * file, and comparing with current table's UUID.
     */
    public boolean destroyExistingRegionInCreate(DiskStoreImpl ds,
        LocalRegion region);

    /**
     * Get a cluster-wide unique ID for given region.
     */
    public long getRegionUUID(LocalRegion region,
        InternalRegionArguments ira);

    /**
     * Get a cluster-wide unique ID for given GatewaySender. The sender can be a
     * WAN sender or for an AsyncEventQueue.
     */
    public long getGatewayUUID(AbstractGatewaySender sender,
        GatewaySenderAttributes attrs);

    /**
     * Print stack traces of all threads to given PrintWriter. For GemFireXD this
     * also dumps locks, transaction states etc.
     */
    public void printStacks(PrintWriter pw);

    /**
     * @see LocalRegion#getJTAEnlistedTX()
     */
    public TXStateProxy getJTAEnlistedTX(LocalRegion region);
    
    public void beforeReturningOffHeapMemoryToAllocator(long address, 
        ChunkType chunkType, int dataSizeDelta);

    /**
     * Returns event error handler
     */
    public EventErrorHandler getEventErrorHandler();

    public boolean tracePersistFinestON();

    public String getDDLStatementRegionName();

    /**
     * Call this when gemfire logger isn't initialized but GemFireXD layer
     * is already logging.
     */
    public void log(String traceFlag, String logline, Throwable t);

    /**
     * Returns true if this PR is being used for a global index.
     */
    public boolean isPRForGlobalIndex(PartitionedRegion pr);

    /*
     * Callback to print CompactCompositeRegionKey from Gfe
     * for debugging purpose
     */
    public String dumpCompactCompositeRegionKey(Object key, String regionPath);

    /**
     * Returns a set of all members in Gfxd system which can host data
     */
    public Set<DistributedMember> getDataStores();

    /**
     * Returns bucket id from the region entry
     */
    public int getBucketIdFromRegionEntry(RegionEntry val);

    /**
     * Returns authentication properties required during reconnect.
     */
    public Properties getSecurityPropertiesForReconnect();

    /**
     * Fetches hive meta data for Snappy tables.
     */
    public ExternalTableMetaData fetchSnappyTablesHiveMetaData(PartitionedRegion region);
  }

  /**
   * Lazily initialized statics to ensure GemFireCache.getInstance() is invoked
   * after cache has booted.
   * 
   * @author swale
   * @since 7.0
   */
  public static class FactoryStatics {

    static StaticSystemCallbacks systemCallbacks;
    static TXStateProxyFactory txStateProxyFactory;
    static TXEntryStateFactory txEntryStateFactory;
    static RawValueFactory rawValueFactory;
    public static StatsSamplerCallback statSamplerCallback;

    static {
      init();
    }

    public static synchronized void init() {
      // set custom entry factories for GemFireXD
      if (gfxdSystem || SystemProperties.isUsingGemFireXDEntryPoint()) {
        String provider = SystemProperties.GFXD_FACTORY_PROVIDER;
        try {
          Class<?> factoryProvider = ClassPathLoader.getLatest().forName(
              provider);
          Method method;

          systemCallbacks = initGFXDCallbacks(false);

          method = factoryProvider.getDeclaredMethod("getTXStateProxyFactory");
          txStateProxyFactory = (TXStateProxyFactory)method.invoke(null);

          method = factoryProvider.getDeclaredMethod("getTXEntryStateFactory");
          txEntryStateFactory = (TXEntryStateFactory)method.invoke(null);

          method = factoryProvider.getDeclaredMethod("getRawValueFactory");
          rawValueFactory = (RawValueFactory)method.invoke(null);

          method = factoryProvider
              .getDeclaredMethod("getStatsSamplerCallbackImpl");
          statSamplerCallback = (StatsSamplerCallback)method.invoke(null);

          // allow persistent transactions in GemFireXD by default
          TXManagerImpl.ALLOW_PERSISTENT_TRANSACTIONS = true;
        } catch (Exception e) {
          throw new IllegalStateException("Exception in obtaining GemFireXD "
              + "Objects Factory provider class", e);
        }
      }
      else {
        systemCallbacks = null;
        txStateProxyFactory = TXStateProxy.getFactory();
        txEntryStateFactory = TXEntryState.getFactory();
        rawValueFactory = RawValueFactory.getFactory();
        statSamplerCallback = null;
      }
    }

    public static StaticSystemCallbacks initGFXDCallbacks(boolean check) {
      final SystemProperties sysProps = SystemProperties.getServerInstance();
      SystemProperties.Callbacks cb = sysProps.getCallbacks();
      if (cb instanceof StaticSystemCallbacks) {
        return (StaticSystemCallbacks)cb;
      }
      else if (check) {
        throw new GemFireConfigException(
            "Expected GemFireXD system callbacks to be set but got " + cb
                + ". Likely cause: product was started as a GemFire node "
                + "using pure GemFire APIs.");
      }
      else {
        StaticSystemCallbacks sysCb = (StaticSystemCallbacks)SystemProperties
            .getGFXDServerCallbacks();
        sysProps.setCallbacks(sysCb);
        return sysCb;
      }
    }
  }

  /**
   * Returns true if any of the GemFire services prefers PdxInstance.
   * And application has not requested getObject() on the PdxInstance.
   *
   */
  public final boolean getPdxReadSerializedByAnyGemFireServices() {
    if ((getPdxReadSerialized() || DefaultQuery.getPdxReadSerialized())
        && PdxInstanceImpl.getPdxReadSerialized()) {
      return true;
    }
    return false;
  }

  public final CacheConfig getCacheConfig() {
    return this.cacheConfig;
  }

  public final DM getDistributionManager() {
    return this.dm;
  }

  public GatewaySenderFactory createGatewaySenderFactory(){
    return new GatewaySenderFactoryImpl(this);
  }

  public AsyncEventQueueFactory createAsyncEventQueueFactory() {
    return new AsyncEventQueueFactoryImpl(this);
  }

  public GatewayReceiverFactory createGatewayReceiverFactory() {
    return new GatewayReceiverFactoryImpl(this);
  }

  public final DistributionAdvisor getDistributionAdvisor() {
    return getResourceAdvisor();
  }

  public final ResourceAdvisor getResourceAdvisor() {
    return resourceAdvisor;
  }

  public final Profile getProfile() {
    return resourceAdvisor.createProfile();
  }

  public final DistributionAdvisee getParentAdvisee() {
    return null;
  }

  public final InternalDistributedSystem getSystem() {
    return this.system;
  }

  public String getFullPath() {
    return "ResourceManager";
  }

  public void fillInProfile(Profile profile) {
    resourceManager.fillInProfile(profile);
  }

  public int getSerialNumber() {
    return this.serialNumber;
  }

  // test hook
  public void setPdxSerializer(PdxSerializer v) {
    this.cacheConfig.setPdxSerializer(v);
    basicSetPdxSerializer(v);
  }

  private void basicSetPdxSerializer(PdxSerializer v) {
    TypeRegistry.setPdxSerializer(v);
    if (v instanceof ReflectionBasedAutoSerializer) {
      AutoSerializableManager asm = AutoSerializableManager.getInstance((ReflectionBasedAutoSerializer) v);
      if (asm != null) {
        asm.setRegionService(this);
      }
    }
  }

  // test hook
  public void setReadSerialized(boolean v) {
    this.cacheConfig.setPdxReadSerialized(v);
  }

  public void setDeclarativeCacheConfig(CacheConfig cacheConfig) {
    this.cacheConfig.setDeclarativeConfig(cacheConfig);
    basicSetPdxSerializer(this.cacheConfig.getPdxSerializer());
  }

  /**
   * Add to the map of declarable properties.  Any properties that exactly match existing
   * properties for a class in the list will be discarded (no duplicate Properties allowed).
   * 
   * @param mapOfNewDeclarableProps Map of the declarable properties to add
   */
  public void addDeclarableProperties(final Map<Declarable, Properties> mapOfNewDeclarableProps) {
    synchronized (this.declarablePropertiesMap) {
      for (Map.Entry<Declarable, Properties> newEntry : mapOfNewDeclarableProps.entrySet()) {
        // Find and remove a Declarable from the map if an "equal" version is already stored
        Class clazz = newEntry.getKey().getClass();

        Object matchingDeclarable = null;
        for (Map.Entry<Declarable, Properties> oldEntry : this.declarablePropertiesMap.entrySet()) {
          if (clazz.getName().equals(oldEntry.getKey().getClass().getName()) && (newEntry.getValue().equals(oldEntry.getValue()) ||
              ((newEntry.getKey() instanceof Identifiable) && (((Identifiable) oldEntry.getKey()).getId().equals(((Identifiable) newEntry.getKey()).getId()))))) {
            matchingDeclarable = oldEntry.getKey();
            break;
          }
        }
        if (matchingDeclarable != null) {
          this.declarablePropertiesMap.remove(matchingDeclarable);
        }

        // Now add the new/replacement properties to the map
        this.declarablePropertiesMap.put(newEntry.getKey(), newEntry.getValue());
      }
    }
  }

  public static boolean isXmlParameterizationEnabled() {
    return xmlParameterizationEnabled;
  }

  public static void setXmlParameterizationEnabled(boolean isXmlParameterizationEnabled) {
    xmlParameterizationEnabled = isXmlParameterizationEnabled;
  }
    
  private Declarable initializer;
  private Properties initializerProps;

  /**
   * A factory for temporary result sets than can overflow to disk.
   */
  private TemporaryResultSetFactory resultSetFactory;

  public Declarable getInitializer() {
    return this.initializer;
  }

  public Properties getInitializerProps() {
    return this.initializerProps;
  }

  public void setInitializer(Declarable initializer, Properties initializerProps) {
    this.initializer = initializer;
    this.initializerProps = initializerProps;
  }

  public PdxInstanceFactory createPdxInstanceFactory(String className) {
    return PdxInstanceFactoryImpl.newCreator(className, true);
  }

  public PdxInstanceFactory createPdxInstanceFactory(String className, boolean b) {
    return PdxInstanceFactoryImpl.newCreator(className, b);
  }

  public PdxInstance createPdxEnum(String className, String enumName, int enumOrdinal) {
    return PdxInstanceFactoryImpl.createPdxEnum(className, enumName, enumOrdinal, this);
  }
  
  public JmxManagerAdvisor getJmxManagerAdvisor() {
    return this.jmxAdvisor;
  }
  
  public CacheSnapshotService getSnapshotService() {
    return new CacheSnapshotServiceImpl(this);
  }
  
  private void startColocatedJmxManagerLocator() {
    InternalLocator loc = InternalLocator.getLocator();
    if (loc != null) {
      loc.startJmxManagerLocationService(this);
    }
  }
  
  @Override
  public HDFSStoreFactory createHDFSStoreFactory() {
    // TODO Auto-generated method stub
    return new HDFSStoreFactoryImpl(this);
  }
  
  public HDFSStoreFactory createHDFSStoreFactory(HDFSStoreCreation creation) {
    return new HDFSStoreFactoryImpl(this, creation);
  }
  public void addHDFSStore(HDFSStoreImpl hsi) {
    HDFSStoreDirector.getInstance().addHDFSStore(hsi);
    //TODO:HDFS Add a resource event for hdfs store creation as well 
    // like the following disk store event
    //system.handleResourceEvent(ResourceEvent.DISKSTORE_CREATE, dsi);
  }

  public void removeHDFSStore(HDFSStoreImpl hsi) {
    hsi.destroy();
    HDFSStoreDirector.getInstance().removeHDFSStore(hsi.getName());
    //TODO:HDFS Add a resource event for hdfs store as well 
    // like the following disk store event
    //system.handleResourceEvent(ResourceEvent.DISKSTORE_REMOVE, dsi);
  }

  public void closeHDFSStores() {
    HDFSRegionDirector.reset();
    HDFSStoreDirector.getInstance().closeHDFSStores();
  }

  
  public HDFSStoreImpl findHDFSStore(String name) {
    return HDFSStoreDirector.getInstance().getHDFSStore(name);
  }
  
  public ArrayList<HDFSStoreImpl> getAllHDFSStores() {
    return HDFSStoreDirector.getInstance().getAllHDFSStores();
  }
  
  
  public TemporaryResultSetFactory getResultSetFactory() {
    return this.resultSetFactory;
  }

  public final MemoryAllocator getOffHeapStore() {
    return this.getSystem().getOffHeapStore();
  }

  /**
   * All ByteBuffer allocations, particularly for off-heap, must use this
   * or {@link #getCurrentBufferAllocator()}.
   */
  public final BufferAllocator getBufferAllocator() {
    return this.bufferAllocator;
  }

  /**
   * All ByteBuffer allocations, particularly for off-heap, must use this
   * or {@link #getBufferAllocator()}.
   */
  public static BufferAllocator getCurrentBufferAllocator() {
    final GemFireCacheImpl instance = getInstance();
    if (instance != null) {
      return instance.bufferAllocator;
    } else {
      // use the allocator as per the setting in StoreCallbacks
      return CallbackFactoryProvider.getStoreCallbacks().hasOffHeap()
          ? DirectBufferAllocator.instance() : HeapBufferAllocator.instance();
    }
  }

  public void setSkipFKChecksForGatewayEvents(boolean flag) {
    this.skipFKChecksForGatewayEvents = flag;
  }
  
  public boolean skipFKChecksForGatewayEvents() {
    return skipFKChecksForGatewayEvents;
  }

  public final boolean isHadoopGfxdLonerMode() {
    return this.system.isHadoopGfxdLonerMode();
  }

  public final EventErrorHandler getEventErrorHandler() {
    StaticSystemCallbacks callbacks = GemFireCacheImpl.getInternalProductCallbacks();
    EventErrorHandler handler = null;
    if (callbacks != null) {
      handler = callbacks.getEventErrorHandler();
    }
    return handler;
  }

  public boolean hasNonWanDispatcher(Set<String> regionGatewaySenderIds) {
    final Set<GatewaySender> allSenders = this.allGatewaySenders;
    for (GatewaySender sender : allSenders) {
      if (regionGatewaySenderIds.contains(sender.getId())
          && sender.isNonWanDispatcher()) {
        return true;
      }
    }
    return false;
  }

  public long getMemorySize(){
    return this.memorySize;
  }

  public static boolean hasNewOffHeap() {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    return cache != null && cache.memorySize > 0L;
  }
}
