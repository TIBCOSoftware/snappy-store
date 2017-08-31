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

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.ENTRY_EVENT_NEW_VALUE;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongBinaryOperator;
import java.util.regex.Pattern;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.CopyHelper;
import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.DeltaSerializationException;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.admin.internal.SystemMemberCacheEventProcessor;
import com.gemstone.gemfire.cache.AttributesMutator;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheEvent;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.CacheListener;
import com.gemstone.gemfire.cache.CacheLoader;
import com.gemstone.gemfire.cache.CacheLoaderException;
import com.gemstone.gemfire.cache.CacheRuntimeException;
import com.gemstone.gemfire.cache.CacheStatistics;
import com.gemstone.gemfire.cache.CacheWriter;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.CustomExpiry;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.DiskStoreFactory;
import com.gemstone.gemfire.cache.DiskWriteAttributes;
import com.gemstone.gemfire.cache.DiskWriteAttributesFactory;
import com.gemstone.gemfire.cache.EntryDestroyedException;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.EvictionAttributes;
import com.gemstone.gemfire.cache.ExpirationAttributes;
import com.gemstone.gemfire.cache.FailedSynchronizationException;
import com.gemstone.gemfire.cache.InterestRegistrationEvent;
import com.gemstone.gemfire.cache.InterestResultPolicy;
import com.gemstone.gemfire.cache.LoaderHelper;
import com.gemstone.gemfire.cache.LockTimeoutException;
import com.gemstone.gemfire.cache.LowMemoryException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.PartitionedRegionStorageException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.RegionEvent;
import com.gemstone.gemfire.cache.RegionExistsException;
import com.gemstone.gemfire.cache.RegionMembershipListener;
import com.gemstone.gemfire.cache.RegionReinitializedException;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.StatisticsDisabledException;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.client.ServerOperationException;
import com.gemstone.gemfire.cache.client.SubscriptionNotEnabledException;
import com.gemstone.gemfire.cache.client.internal.BridgePoolImpl;
import com.gemstone.gemfire.cache.client.internal.Connection;
import com.gemstone.gemfire.cache.client.internal.Endpoint;
import com.gemstone.gemfire.cache.client.internal.PoolImpl;
import com.gemstone.gemfire.cache.client.internal.ServerRegionProxy;
import com.gemstone.gemfire.cache.control.ResourceManager;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSBucketRegionQueue;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSIntegrationUtil;
import com.gemstone.gemfire.cache.hdfs.internal.HoplogListenerForRegion;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSRegionDirector;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSRegionDirector.HdfsRegionManager;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;
import com.gemstone.gemfire.cache.query.FunctionDomainException;
import com.gemstone.gemfire.cache.query.IndexMaintenanceException;
import com.gemstone.gemfire.cache.query.IndexType;
import com.gemstone.gemfire.cache.query.NameResolutionException;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.cache.query.QueryInvocationTargetException;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.query.TypeMismatchException;
import com.gemstone.gemfire.cache.query.internal.CqService;
import com.gemstone.gemfire.cache.query.internal.DefaultQuery;
import com.gemstone.gemfire.cache.query.internal.ExecutionContext;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.cache.query.internal.index.IndexCreationData;
import com.gemstone.gemfire.cache.query.internal.index.IndexManager;
import com.gemstone.gemfire.cache.query.internal.index.IndexProtocol;
import com.gemstone.gemfire.cache.query.internal.index.IndexUtils;
import com.gemstone.gemfire.cache.util.BridgeWriterException;
import com.gemstone.gemfire.cache.util.GatewayHub;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.Profile;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ResourceEvent;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ClassLoadUtil;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalStatisticsDisabledException;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisor.CacheProfile;
import com.gemstone.gemfire.internal.cache.DiskInitFile.DiskRegionFlag;
import com.gemstone.gemfire.internal.cache.DistributedRegion.DiskEntryPage;
import com.gemstone.gemfire.internal.cache.DistributedRegion.DiskSavyIterator;
import com.gemstone.gemfire.internal.cache.FilterRoutingInfo.FilterInfo;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl.StaticSystemCallbacks;
import com.gemstone.gemfire.internal.cache.InitialImageOperation.GIIStatus;
import com.gemstone.gemfire.internal.cache.PutAllPartialResultException.PutAllPartialResult;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager.ResourceType;
import com.gemstone.gemfire.internal.cache.control.MemoryEvent;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds;
import com.gemstone.gemfire.internal.cache.control.ResourceListener;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.execute.DistributedRegionFunctionExecutor;
import com.gemstone.gemfire.internal.cache.execute.DistributedRegionFunctionResultSender;
import com.gemstone.gemfire.internal.cache.execute.LocalResultCollector;
import com.gemstone.gemfire.internal.cache.execute.RegionFunctionContextImpl;
import com.gemstone.gemfire.internal.cache.execute.ServerToClientFunctionResultSender;
import com.gemstone.gemfire.internal.cache.ha.ThreadIdentifier;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedLockObject;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy.ReadEntryUnderLock;
import com.gemstone.gemfire.internal.cache.locks.QueuedSynchronizer;
import com.gemstone.gemfire.internal.cache.lru.LRUEntry;
import com.gemstone.gemfire.internal.cache.partitioned.RedundancyAlreadyMetException;
import com.gemstone.gemfire.internal.cache.persistence.DiskExceptionHandler;
import com.gemstone.gemfire.internal.cache.persistence.DiskRecoveryStore;
import com.gemstone.gemfire.internal.cache.persistence.DiskRegionView;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.cache.persistence.query.IndexMap;
import com.gemstone.gemfire.internal.cache.persistence.query.mock.IndexMapImpl;
import com.gemstone.gemfire.internal.cache.tier.InterestType;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientNotifier;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientHealthMonitor;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientTombstoneMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientUpdateMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.HandShake;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.cache.versions.ConcurrentCacheModificationException;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionHolder;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventCallbackArgument;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventCallbackArgumentImpl;
import com.gemstone.gemfire.internal.cache.wan.serial.SerialGatewaySenderImpl;
import com.gemstone.gemfire.internal.concurrent.AB;
import com.gemstone.gemfire.internal.concurrent.CFactory;
import com.gemstone.gemfire.internal.concurrent.CM;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap;
import com.gemstone.gemfire.internal.concurrent.S;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.jta.TransactionManagerImpl;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.StoredObject;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.sequencelog.EntryLogger;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.size.ReflectionObjectSizer;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.gemstone.gemfire.internal.size.SingleObjectSizer;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.gemstone.gemfire.internal.snappy.UMMMemoryTracker;
import com.gemstone.gemfire.internal.util.concurrent.FutureResult;
import com.gemstone.gemfire.internal.util.concurrent.StoppableCountDownLatch;
import com.gemstone.gemfire.internal.util.concurrent.StoppableReentrantReadWriteLock;
import com.gemstone.gemfire.internal.util.concurrent.StoppableReentrantReadWriteLock.StoppableWriteLock;

/**
 * Implementation of a local scoped-region. Note that this class has a different
 * meaning starting with 3.0. In previous versions, a LocalRegion was the
 * representation of a region in the VM. Starting with 3.0, a LocalRegion is a
 * non-distributed region. The subclass DistributedRegion adds distribution
 * behavior.
 *
 * @author Eric Zoerner
 */
@SuppressWarnings("deprecation")
public class LocalRegion extends AbstractRegion 
  implements LoaderHelperFactory, ResourceListener<MemoryEvent>,
             DiskExceptionHandler, DiskRecoveryStore
{

  /**
   * Internal interface used to simulate failures when performing entry operations
   * @author Mitch Thomas
   * @since 5.7
   */
  public interface TestCallable {
    public void call(LocalRegion r, Operation op, RegionEntry re);
  }

  // view types for iterators
  public enum IteratorType {
    KEYS, VALUES, ENTRIES,
    /**
     * Indicates iteration over RegionEntry or TXEntryState objects -- used by
     * GemFireXD to avoid unnecessarily looking up those again since
     * indexes/scans etc. deal with raw entries directly
     */
    RAW_ENTRIES
  }

  // iniitialization level
  public static final int AFTER_INITIAL_IMAGE = 0;

  public static final int BEFORE_INITIAL_IMAGE = 1;

  public static final int ANY_INIT = 2;

  /**
   * thread local to indicate that this thread should bypass the initialization
   * Latch
   */
  private static final ThreadLocal initializationThread = new ThreadLocal();

  /* thread local to indicate its for persist data convert tool */
  protected static final ThreadLocal isConversion = new ThreadLocal();
  
  // user attributes //
  private Object regionUserAttribute;

  protected Map entryUserAttributes; // @todo darrel: shouldn't this be an

  // identity map whose key is a RegionEntry?

  private final String regionName;

  protected final LocalRegion parentRegion;
  // set to true only if isDestroyed is also true
  // and region is about to be recreated due to reinitialization by loading
  // of a snapshot, etc.
  private volatile boolean reinitialized_old = false;

  public volatile boolean isDestroyed = false;

  // In case of parallel wan, when a destroy is called on userPR, it waits for
  // parallelQueue to drain and then destroys paralleQueue. In this time if
  // operation like put happens on userPR then it will keep on building parallel
  // queue increasing time of userPR to get destroyed.this volatile boolean will
  // block such put operation by throwing RegionDestroyedException
  protected volatile boolean isDestroyedForParallelWAN = false;

  // set to true after snapshot is loaded, to help get initial image
  // make sure this is the right incarnation of this region
  private volatile boolean reinitialized_new = false;

  /** Lock used to prevent multiple concurrent destroy region operations */
  private S destroyLock;

  private volatile RegionTTLExpiryTask regionTTLExpiryTask = null;

  private volatile RegionIdleExpiryTask regionIdleExpiryTask = null;

  private final ConcurrentHashMap<RegionEntry, EntryExpiryTask> entryExpiryTasks = new ConcurrentHashMap<RegionEntry, EntryExpiryTask>();

  /**
   * Set to true after an invalidate region expiration so we don't get multiple
   * expirations
   */
  volatile boolean regionInvalid = false;

  public final RegionMap entries;
  
  private static final ThreadLocal<Boolean> isOffHeapThreadLocal =  
      new ThreadLocal<Boolean>() ;

  /**
   * Set to true if this region supports transaction else false.
   */
  private final boolean supportsTX;




  public static final ThreadLocal<String> regionPath =
      new ThreadLocal<String>() ;

  public static final ReadEntryUnderLock READ_VALUE = new ReadEntryUnderLock() {
    public final Object readEntry(final ExclusiveSharedLockObject lockObj,
        Object context, int iContext, boolean allowTombstones) {
      return ((RegionEntry)lockObj).getValue((LocalRegion)context);
    }
  };

  public static final ReadEntryUnderLock READ_VALUE_RETAIN = new ReadEntryUnderLock() {
    @Retained
    public final Object readEntry(final ExclusiveSharedLockObject lockObj,
        Object context, int iContext, boolean allowTombstones) {
      return ((RegionEntry)lockObj)._getValueRetain((LocalRegion)context,
          false);
    }
  };

  public static final ReadEntryUnderLock READ_TOKEN = new ReadEntryUnderLock() {
    public final Token readEntry(final ExclusiveSharedLockObject lockObj,
        Object context, int iContext, boolean allowTombstones) {
      return ((RegionEntry)lockObj).getValueAsToken();
    }
  };

  public static final ReadEntryUnderLock TX_READ_VALUE = new ReadEntryUnderLock() {
    public final Object readEntry(final ExclusiveSharedLockObject lockObj,
        final Object context, int iContext, boolean allowTombstones) {
      return ((LocalRegion)context)
          .getREValueForTXRead((RegionEntry)lockObj);
    }
  };

  public static final ReadEntryUnderLock GET_VALUE = new ReadEntryUnderLock() {
    public final Object readEntry(final ExclusiveSharedLockObject lockObj,
        final Object context, int iContext, boolean allowTombstones) {
      return ((LocalRegion)context).getEntryValue((RegionEntry)lockObj);
    }
  };

  public static final ReadEntryUnderLock DESER_VALUE = new ReadEntryUnderLock() {
    public final Object readEntry(final ExclusiveSharedLockObject lockObj,
        final Object context, final int iContext, boolean allowTombstones) {
      return ((LocalRegion)context).getDeserialized(
          (RegionEntry)lockObj, (iContext & DESER_UPDATE_STATS) != 0,
          (iContext & DESER_DISABLE_COPY_ON_READ) != 0,
          (iContext & DESER_PREFER_CD) != 0);
    }
  };

  /**
   * A count of entries that have been created by in-progress transactions for
   * locking. This will be incremented when TX creates the entry for lock in the
   * region and decremented when TX commits or rolls back.
   */
  final AtomicInteger txLockCreateCount = new AtomicInteger(0);

  /** tracks threadID->seqno information for this region */
  protected EventTracker eventTracker;
  
  /** tracks region-level version information for members.  See
   * https://wiki.gemstone.com/display/gfe70/Consistency+in+Replicated+Regions+and+WAN
   */
  private RegionVersionVector versionVector;

  private static final Pattern[] QUERY_PATTERNS = new Pattern[] {
      Pattern.compile("^\\(*select .*", Pattern.CASE_INSENSITIVE
          | Pattern.UNICODE_CASE | Pattern.DOTALL),
      Pattern.compile("^import .*", Pattern.CASE_INSENSITIVE
          | Pattern.UNICODE_CASE | Pattern.DOTALL) };


  public static final String EXPIRY_MS_PROPERTY = "gemfire.EXPIRY_UNITS_MS";
  
  /**
   * Used by unit tests to set expiry to milliseconds instead of the default
   * seconds. Used in ExpiryTask.
   *
   * @since 5.0
   */
  final boolean EXPIRY_UNITS_MS = Boolean.getBoolean(EXPIRY_MS_PROPERTY);

  // Indicates that the entries are in fact initialized. It turns out
  // you can't trust the assignment of a volatile (as indicated above)
  // to mean that the the thing being assigned is fully formed, only
  // those things *before* the assignment are fully formed. mthomas 10/02/2005
  private volatile boolean entriesInitialized;

  /**
   * contains Regions themselves // marked volatile to make sure it is fully
   * initialized before being // accessed; (actually should be final)
   */
  protected volatile CM subregions;

  private final Object subregionsLock = new Object();

  // Used for synchronizzing access to client Cqs
//  private final Object clientCqsSync = new Object();

  /**
   * Prevents access to this region until it is done initializing, except for
   * some special initializing operations such as replying to create region
   * messages In JDK 1.5 we will use java.util.concurrent.CountDownLatch instead
   * of com.gemstone.gemfire.internal.util.CountDownLatch.
   */
  protected final StoppableCountDownLatch initializationLatchBeforeGetInitialImage;

  protected final StoppableCountDownLatch initializationLatchAfterGetInitialImage;

  /**
   * Used to hold off cache listener events until the afterRegionCreate is
   * called
   *
   * @since 5.0
   */
  private final StoppableCountDownLatch afterRegionCreateEventLatch;

  /**
   * For test purpose to, especially for AbstractRegionMap.applyAllSuspects
   */
  public static volatile CountDownLatch applyAllSuspectTestLatch;

  public static volatile boolean applyAllSuspectTestLatchWaiting;

  public static volatile String applyAllSuspectTEST_REGION_NAME;
  
  /**
   * Set to true the first time isInitialized returns true.
   */
  private volatile boolean initialized = false; // added for bug 30223

  /** Used for accessing region data on disk */
  private final DiskRegion diskRegion;
  
  /**
   * Used by transactions to suspend entry expiration while a transaction is in
   * progress on a region. This field is only initialized if expiration is
   * configured and transactions are possible.
   */
  private volatile StoppableReentrantReadWriteLock txExpirationLock;

  final ArrayList pendingExpires = new ArrayList();

  /**
   * Used for serializing netSearch and netLoad on a per key basis.
   * CM <Object, Future>
   */
  protected final CM getFutures = CFactory.createCM();
  
  /*
   * Asif: This boolean needs to be made true if the test needs to receive a
   * synchronous callback just after clear on map is done. Its visibility is
   * default so that only tests present in com.gemstone.gemfire.internal.cache
   * will be able to see it
   */
  public static boolean ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;

  /**
   * A flag used to indicate that this Region is being used as an administrative
   * Region, holding meta-data for a PartitionedRegion
   */
  final private boolean isUsedForPartitionedRegionAdmin;

  final private boolean isUsedForPartitionedRegionBucket;
  
  private PartitionedRegion partitionedRegionForBucket = null;

  final private boolean isUsedForMetaRegion;
  
  final private boolean isMetaRegionWithTransactions;
  
  final private boolean isUsedForSerialGatewaySenderQueue;

  final private boolean isUsedForIndex;

  final private SerialGatewaySenderImpl serialGatewaySender;

  protected volatile boolean hasLocalSerialAEQorWAN;
  protected volatile boolean hasSerialAEQorWAN;

  /** parallel WAN is always colocated, so present locally if at all */
  protected volatile boolean hasLocalParallelAEQorWAN;

  /**
   * The factory used to create the LoaderHelper when a loader is invoked
   */
  protected final LoaderHelperFactory loaderHelperFactory;

  /**
   * Allow for different cacheperfstats locations... primarily for PartitionedRegions
   */
  private final CachePerfStats cachePerfStats;
  private final boolean hasOwnStats; 


  private final ImageState imageState;
  /**
   * Register interest count to track if any register interest is in progress for
   * this region. This count will be incremented when register interest starts
   * and decremented when register interest finishes.
   * @guarded.By {@link #imageState}
   */
  private int riCnt = 0; /*
                          * since always written while holding an exclusive write lock
                          * and only read while holding a read lock
                          * it does not need to be atomic or
                          * protected by any other sync.
                          */


  /**
   * Map of subregion full paths to serial numbers. These are subregions that
   * were destroyed when this region was destroyed. This map remains null until
   * this region is destroyed.
   */
  private volatile HashMap destroyedSubregionSerialNumbers;

  /**
   * This boolean is true when a member who has this region is running low on memory.
   * It is used to reject region operations.
   */
  public final AB memoryThresholdReached = CFactory.createAB(false);

  // Lock for updating PR MetaData on client side 
  public final Lock clientMetaDataLock = new ReentrantLock();

  // The int hubType would identify the order of the GfxdHub.
  // If the int is 0 implies it is not associated with any hub.
  private  int hubType= GatewayHubImpl.NO_HUB;
  public static final int GATEWAY_NONE = 0;
  public static final int GATEWAY_SENDER = 1;
  public static final int GATEWAY_ASYNCQUEUE = 2;
  private final int gatewayType = GATEWAY_NONE;

  /**
   * Global map of waiters for locks when using the waiting mode in
   * {@link ExclusiveSharedLockObject}.
   */
  private final CustomEntryConcurrentHashMap<RegionEntry, QueuedSynchronizer>
      lockWaiters = new CustomEntryConcurrentHashMap<RegionEntry,
        QueuedSynchronizer>(CustomEntryConcurrentHashMap.DEFAULT_INITIAL_CAPACITY,
            CustomEntryConcurrentHashMap.DEFAULT_LOAD_FACTOR, 8, true);

  protected HdfsRegionManager hdfsManager;
  protected HoplogListenerForRegion hoplogListener;

  /**
   * There seem to be cases where a region can be created and yet the
   * distributed system is not yet in place...
   *
   * @author jpenney
   *
   */
  protected class Stopper extends CancelCriterion {

    @Override
    public String cancelInProgress() {
      // ---
      // This grossness is necessary because there are instances where the
      // region can exist without having a cache (XML creation)
      Cache c = LocalRegion.this.getCache();
      if (c == null) {
        return LocalizedStrings.LocalRegion_THE_CACHE_IS_NOT_AVAILABLE.toLocalizedString();
      }
      // --- end of grossness
      return c.getCancelCriterion().cancelInProgress();
    }

    /* (non-Javadoc)
     * @see com.gemstone.gemfire.CancelCriterion#generateCancelledException(java.lang.Throwable)
     */
    @Override
    public RuntimeException generateCancelledException(Throwable e) {
      // ---
      // This grossness is necessary because there are instances where the
      // region can exist without having a cache (XML creation)
      Cache c = LocalRegion.this.getCache();
      if (c == null) {
        return new CacheClosedException("No cache", e);
      }
      // --- end of grossness
      return c.getCancelCriterion().generateCancelledException(e);
    }

  }

  protected final CancelCriterion stopper = createStopper();

  protected CancelCriterion createStopper() {
    return new Stopper();
  }

  private final TestCallable testCallable;

  /**
   * ThreadLocal used to set the current region being initialized.
   * 
   * Currently used by the OpLog layer to initialize the
   * {@link KeyWithRegionContext} if required.
   */
  private final static ThreadLocal<LocalRegion> initializingRegion =
    new ThreadLocal<LocalRegion>();

  /**
   * Set to true if the region contains keys implementing
   * {@link KeyWithRegionContext} that require setting up of region specific
   * context after deserialization or recovery from disk.
   */
  private boolean keyRequiresRegionContext;

  /**
   * A UUID generator that is unique in the DS for this region. Currently used
   * by GemFireXD for IDENTITY columns and for region keys in tables having no
   * primary key.
   */
  final UUIDAdvisor uuidAdvisor;

  public static final Random rand = new Random(Long.getLong(
      "gemfire.PartitionedRegionRandomSeed", System.nanoTime()));

  // below atomic vars are used by newUUID() and newShortUUID() in case
  // the region is local
  private final AtomicLong localUUID;
  private final AtomicInteger localShortUUID;

  /**
   * A cluster-wide unique ID for the region. Currently used by persistence
   * layer to determine if a region recovered is same as this one.
   */
  private final long regionUUID;

  /** combines the path with {@link #regionUUID} */
  private final String regionId;

  /**
   * Get the current initializing region as set in the ThreadLocal.
   * 
   * Note that this value is cleared after the initialization of LocalRegion is
   * done so is valid only for the duration of region creation and
   * initialization.
   */
  public static LocalRegion getInitializingRegion() {
    return initializingRegion.get();
  }

  /**
   * To clear the initializing region set in the ThreadLocal.
   *
   * NOTE: Only to be used by internal product code.
   */
  public static void clearInitializingRegion() {
    initializingRegion.set(null);
  }

  /**
   * Return true if the keys of this region implement
   * {@link KeyWithRegionContext} that require region specific context
   * initialization after deserialization or recovery from disk.
   * 
   * Currently used by GemFireXD for the optimized
   * <code>CompactCompositeRegionKey</code> that points to the raw row bytes and
   * so requires a handle to table schema for interpretation of those bytes.
   */
  public final boolean keyRequiresRegionContext() {
    return this.keyRequiresRegionContext;
  }

  /**
   * Set the {@link #keyRequiresRegionContext} flag to given value.
   */
  public final void setKeyRequiresRegionContext(boolean v) {
    this.keyRequiresRegionContext = v;
  }

  public CancelCriterion getCancelCriterion() {
    return this.stopper;
  }

  ////////////////// Public Methods ///////////////////////////////////////////

  static String calcFullPath(String regionName, LocalRegion parentRegion) {
    StringBuilder buf = null;
    if (parentRegion == null) {
      buf = new StringBuilder(regionName.length() + 1);
    }
    else {
      String parentFull = parentRegion.getFullPath();
      buf = new StringBuilder(parentFull.length() + regionName.length() + 1);
      buf.append(parentFull);
    }
    buf.append(SEPARATOR).append(regionName);
    return buf.toString();
  }

  /**
   * Creates new region
   */
  protected LocalRegion(String regionName, RegionAttributes attrs,
      LocalRegion parentRegion, GemFireCacheImpl cache,
      InternalRegionArguments internalRegionArgs) throws DiskAccessException {
    super(cache, attrs,regionName, internalRegionArgs);
    Assert.assertTrue(regionName != null, "regionName must not be null");
    this.sharedDataView = buildDataView();
    this.regionName = regionName;
    this.isInternalColumnTable = regionName.toUpperCase().endsWith(
        StoreCallbacks.SHADOW_TABLE_SUFFIX);
    this.parentRegion = parentRegion;
    this.fullPath = calcFullPath(regionName, parentRegion);
    final GemFireCacheImpl.StaticSystemCallbacks sysCb =
        GemFireCacheImpl.FactoryStatics.systemCallbacks;
    if (sysCb == null) {
      this.regionUUID = internalRegionArgs.getUUID();
    }
    else {
      this.regionUUID = sysCb.getRegionUUID(this, internalRegionArgs);
      internalRegionArgs.setUUID(this.regionUUID);
    }
    this.regionId = getIDFromPath(this.fullPath, this.regionUUID);
    this.regionLock = new ReentrantLock();


    this.initializationLatchBeforeGetInitialImage = new StoppableCountDownLatch(
        this.stopper, 1);
    this.initializationLatchAfterGetInitialImage = new StoppableCountDownLatch(
        this.stopper, 1);
    this.afterRegionCreateEventLatch = new StoppableCountDownLatch(
        this.stopper, 1);

    String myName = getFullPath();
    if (internalRegionArgs.getPartitionedRegion() != null) {
      myName = internalRegionArgs.getPartitionedRegion().getFullPath();
    }
    this.enableOffHeapMemory = attrs.getEnableOffHeapMemory() || Boolean.getBoolean(myName+":OFF_HEAP");
    if (getEnableOffHeapMemory()) {
      if (cache.getOffHeapStore() == null) {
        throw new IllegalStateException("The region " + myName + " was configured to use off heap memory but no off heap memory was configured.");
      }
      if (getCompressor() != null) {
        cache.getOffHeapStore().setCompressor(getCompressor());
      }
    }

    // set the user-attribute object upfront for GemFireXD GemFireContainer
//    cache.getLogger().fine("For region " + this.fullPath + " userAttr="
//        + internalRegionArgs.getUserAttribute() + " indexUpdater="
//        + internalRegionArgs.getIndexUpdater());
    if (internalRegionArgs.getUserAttribute() != null) {
      setUserAttribute(internalRegionArgs.getUserAttribute());
    }
    setKeyRequiresRegionContext(internalRegionArgs.keyRequiresRegionContext());
    initializingRegion.set(this);

    if (internalRegionArgs.getCachePerfStatsHolder() != null) {
      this.hasOwnStats = false;
      this.cachePerfStats = internalRegionArgs.getCachePerfStatsHolder()
          .getCachePerfStats();
    }
    else {
      if (attrs.getPartitionAttributes() != null || isInternalRegion()
          || internalRegionArgs.isUsedForMetaRegion()) {
        this.hasOwnStats = false;
        this.cachePerfStats = cache.getCachePerfStats();
      }
      else {
        this.hasOwnStats = true;
        this.cachePerfStats = new RegionPerfStats(cache, cache.getCachePerfStats(), regionName);
      }
    }

    this.hdfsManager = initHDFSManager();
    this.dsi = findDiskStore(attrs, internalRegionArgs);
    this.diskRegion = createDiskRegion(internalRegionArgs);
    this.entries = createRegionMap(internalRegionArgs);
    this.entriesInitialized = true;
    this.subregions = CFactory.createCM();
    // we only need a destroy lock if this is a root
    if (parentRegion == null) {
      initRoot();
    }
    if (internalRegionArgs.getLoaderHelperFactory() != null) {
      this.loaderHelperFactory = internalRegionArgs.getLoaderHelperFactory();
    }
    else {
      this.loaderHelperFactory = this;
    }

    this.isUsedForPartitionedRegionAdmin = internalRegionArgs
        .isUsedForPartitionedRegionAdmin();
    this.isUsedForPartitionedRegionBucket = internalRegionArgs
        .isUsedForPartitionedRegionBucket();
    this.isUsedForIndex = internalRegionArgs.isUsedForIndex();

    if(this.isUsedForPartitionedRegionBucket){
      partitionedRegionForBucket = internalRegionArgs.getPartitionedRegion();
    }
    this.isUsedForMetaRegion = internalRegionArgs
        .isUsedForMetaRegion();
    this.isMetaRegionWithTransactions = internalRegionArgs.isMetaRegionWithTransactions();
    this.isUsedForSerialGatewaySenderQueue = internalRegionArgs.isUsedForSerialGatewaySenderQueue();
    this.serialGatewaySender = internalRegionArgs.getSerialGatewaySender();

    if (!isUsedForMetaRegion && !isUsedForPartitionedRegionAdmin &&
        !isUsedForPartitionedRegionBucket) {
      this.filterProfile = new FilterProfile(this);
    }

    // initialize client to server proxy
    this.srp = ((this.getPoolName() != null)
                || isBridgeLoader(this.getCacheLoader())
                || isBridgeWriter(this.getCacheWriter()))
      ? new ServerRegionProxy(this)
      : null;
    this.imageState =
      new UnsharedImageState(this.srp != null,
                             getDataPolicy().withReplication() || getDataPolicy().isPreloaded(),
                             getScope().isLocal(),
                             getDataPolicy().withPersistence(),
                             this.stopper);

    createEventTracker();

    // prevent internal regions from participating in a TX, bug 38709
    this.supportsTX = !isSecret() && !isUsedForPartitionedRegionAdmin()
        && !isUsedForMetaRegion();

    if (getScope().isLocal()) {
      this.uuidAdvisor = null;
      this.localUUID = new AtomicLong(0);
      this.localShortUUID = new AtomicInteger(0);
    }
    else {
      if (!isUsedForPartitionedRegionBucket()) {
        this.uuidAdvisor = createUUIDAdvisor(getSystem(), internalRegionArgs);
      }
      else {
        this.uuidAdvisor = null;
      }
      this.localUUID = null;
      this.localShortUUID = null;
    }

    this.testCallable = internalRegionArgs.getTestCallable();
  }

  private HdfsRegionManager initHDFSManager() {
    HdfsRegionManager hdfsMgr = null;
    if (this.getHDFSStoreName() != null) {
      this.hoplogListener = new HoplogListenerForRegion();
      HDFSRegionDirector.getInstance().setCache(cache);
      hdfsMgr = HDFSRegionDirector.getInstance().manageRegion(this, 
          this.getHDFSStoreName(), hoplogListener);
    }
    return hdfsMgr;
  }

  private RegionMap createRegionMap(InternalRegionArguments internalRegionArgs) {
    RegionMap result = null;
    if ((internalRegionArgs.isReadWriteHDFSRegion()) && this.diskRegion != null) {
      this.diskRegion.setEntriesMapIncompatible(true);
    }
    if (this.diskRegion != null) {
      result = this.diskRegion.useExistingRegionMap(this, internalRegionArgs);
    }
    if (result == null) {
      RegionMap.Attributes ma = new RegionMap.Attributes();
      ma.statisticsEnabled = this.statisticsEnabled;
      ma.loadFactor = this.loadFactor;
      ma.initialCapacity = this.initialCapacity;
      ma.concurrencyLevel = this.concurrencyLevel;
      result = RegionMapFactory.createVM(this, ma, internalRegionArgs);
    }
    if (result.getIndexUpdater() == null && internalRegionArgs != null
        && internalRegionArgs.getIndexUpdater() != null) {
      result.setIndexUpdater(internalRegionArgs.getIndexUpdater());
    }
    return result;
  }

  protected InternalDataView buildDataView() {
    return new LocalRegionDataView();
  }

  /**
   * initialize the event tracker.  Not all region implementations want or
   * need one of these. Regions that require one should reimplement this method
   * and create one like so:
   * <code><pre>
   *     this.eventTracker = new EventTracker(this.cache);
   *     this.eventTracker.start();
   * </pre></code>
   */
  void createEventTracker() {
    // if LocalRegion is changed to have an event tracker, then the initialize()
    // method should be changed to set it to "initialized" state when the
    // region finishes initialization
  }
  
  
  /**
   * Test method for getting the event tracker.
   * 
   * this method is for testing only.  Other region classes may track events using
   * different mechanisms than EventTrackers
   */
  protected EventTracker getEventTracker() {
    return this.eventTracker;
  }
  
  /** returns the regions version-vector */
  public final RegionVersionVector getVersionVector() {
    return this.versionVector;
  }

  /** returns lock used to guard the size() operation during tombstone removal */
  public ReentrantLock getSizeGuard() {
    if (!this.concurrencyChecksEnabled) {
      return null;
    }
    else {
      return this.regionLock;
    }
  }

  /** initializes a new version vector for this region */
  protected void createVersionVector() {

    this.versionVector = RegionVersionVector.create(getVersionMember());

    final RegionVersionVector<?> diskVector;
    if (dataPolicy.withPersistence()
        && (diskVector = this.diskRegion.getRegionVersionVector()) != null) {
      // copy the versions that we have recovered from disk into
      // the version vector.
      this.versionVector.recordVersions(diskVector
          .getCloneForTransmission(), null);
    } else if (!dataPolicy.withStorage()) {
      // version vectors are currently only necessary in empty regions for
      // tracking canonical member IDs
      this.versionVector.turnOffRecordingForEmptyRegion();
    }
    if (this.srp != null) {
      this.versionVector.setIsClientVector();
    }
    this.cache.getDistributionManager().addMembershipListener(
        this.versionVector);
  }

  @Override
  protected void updateEntryExpiryPossible()
  {
    super.updateEntryExpiryPossible();
    if (isEntryExpiryPossible() || isRegionExpiryPossible()) {
      if (this.txExpirationLock == null) {
        // HACK HACK stopper can be null if we're called
        // from the superclass constructor :-(
        CancelCriterion hack = this.stopper;
        if (hack == null) {
          hack = new Stopper();
        }
        this.txExpirationLock = new StoppableReentrantReadWriteLock(true, hack);
      }
    }
    else {
      this.txExpirationLock = null;
      // since expiration is no longer possible cleanup the tasks
      cancelAllEntryExpiryTasks();
    }
  }

  public final IndexUpdater getIndexUpdater() {
    return this.entries != null ? this.entries.getIndexUpdater() : null;
  }

  public final CustomEntryConcurrentHashMap<RegionEntry, QueuedSynchronizer>
      getLockWaiters() {
    return this.lockWaiters;
  }

  /**
   * Returns null if expiration is not possible; otherwise returns a read lock
   * that will prevent entry expiration while it is held.
   */
  public StoppableReentrantReadWriteLock.StoppableReadLock
      getTxEntryExpirationReadLock() {
    final StoppableReentrantReadWriteLock lk = this.txExpirationLock;
    if (lk != null) {
      StoppableReentrantReadWriteLock.StoppableReadLock result = lk.readLock();
      result.lock();
      return result;
    }
    else {
      return null;
    }
  }

  final boolean isCacheClosing()
  {
    return this.cache.isClosed();
  }

  public final RegionEntry getRegionEntry(Object key) {
    return this.entries.getEntry(key);
  }
  
  /**
   * Test hook - returns the version stamp for an entry in the form of a
   * version tag
   * @param key
   * @return the entry version information
   */
  public VersionTag getVersionTag(Object key) {
    try{
      operationStart();
    Region.Entry entry = getEntry(key, discoverJTA(), true);
    VersionTag tag = null;
    if (entry != null && entry instanceof EntrySnapshot) {
      tag = ((EntrySnapshot)entry).getVersionTag();
    } else if (entry != null && entry instanceof NonTXEntry) {
      tag = ((NonTXEntry)entry).getRegionEntry().getVersionStamp().asVersionTag();
    }
    return tag;
    } finally {
      operationCompleted();
    }
  }
  
  /** removes any destroyed entries from the region and clear the destroyedKeys
   * assert: Caller must be holding writeLock on is
   */
  private void destroyEntriesAndClearDestroyedKeysSet() {
    ImageState is = getImageState();
    Iterator iter = is.getDestroyedEntries();
    while (iter.hasNext()) {
      Object key = iter.next();
      // destroy the entry which has value Token.DESTROYED
      // If it is Token.DESTROYED then only destroy it.
      this.entries.removeIfDestroyed(key); // fixes bug 41957
    }
  }

  /**
   * @since 5.7
   */
  protected final ServerRegionProxy srp;

  private final InternalDataView sharedDataView;

  public final ServerRegionProxy getServerProxy() {
    return this.srp;
  }
  
  public final boolean hasServerProxy() {
    return this.srp != null;
  }

  /** Returns true if the ExpiryTask is currently allowed to expire. */
  protected boolean isExpirationAllowed(ExpiryTask expiry)
  {
    return true;
  }

  public final void processPendingExpires()
  {
    while (true) {
      synchronized (this.pendingExpires) {
        if (this.pendingExpires.isEmpty()) {
          return;
        }
      }
      final StoppableReentrantReadWriteLock rwl = this.txExpirationLock;
      if (rwl != null) {
        StoppableWriteLock writeLock = rwl.writeLock();
        boolean lockAcquired = false;
        try {
          if (writeLock.tryLock()) {
            lockAcquired = true;
            basicProcessPendingExpires();
          } else {
            break;
          }
        } finally {
          if (lockAcquired) {
            writeLock.unlock();
          }
        }
      } else {
        basicProcessPendingExpires();
      }
    }
  }

  private final void basicProcessPendingExpires()
  {
    ExpiryTask[] tasks;
    while (true) {
      // note we just keep looping until no more pendingExpires exist
      synchronized (this.pendingExpires) {
        if (this.pendingExpires.isEmpty()) {
          return;
        }
        tasks = new ExpiryTask[this.pendingExpires.size()];
        this.pendingExpires.toArray(tasks);
        this.pendingExpires.clear();
      }
      try {
        if (isCacheClosing() || isClosed() || this.isDestroyed) {
          return;
        }
        final LogWriterI18n logger = getCache().getLoggerI18n();
        for (int i = 0; i < tasks.length; i++) {
          try {
            if (logger.fineEnabled()) {
              logger.fine(tasks[i].toString() + " pending expire fired at "
                  + this.cacheTimeMillis());
            }
            tasks[i].basicPerformTimeout(false);
            if (isCacheClosing() || isClosed() || isDestroyed()) {
              return;
            }
          }
          catch (EntryNotFoundException ignore) {
            // ignore and try the next expiry task
          }
        }
      }
      catch (RegionDestroyedException re) {
        // Ignore - our job is done
      }
      catch (CancelException ex) {
        // ignore
      }
      catch (VirtualMachineError err) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error.  We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      catch (Throwable ex) {
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        getCache().getLoggerI18n().severe(LocalizedStrings.LocalRegion_EXCEPTION_IN_EXPIRATION_TASK, ex);
      }
    }
  }

  /**
   * If set then an expiration can cause a tx commit to fail with a conflict.
   * The advantage of setting it is that expiration can be done by multiple
   * threads. Set -Dgemfire.EXPIRY_THREADS=x if you want multiple threads to
   * expire entries.
   */
  private final static boolean EXPIRATIONS_CAUSE_CONFLICTS = Boolean
      .getBoolean("gemfire.EXPIRATIONS_CAUSE_CONFLICTS");

  void performExpiryTimeout(ExpiryTask p_task) throws CacheException
  {
    if (EXPIRATIONS_CAUSE_CONFLICTS) {
      if (p_task != null) {
        p_task.basicPerformTimeout(false);
      }
      return;
    }
    ExpiryTask task = p_task;
    synchronized (this.pendingExpires) {
      if (!this.pendingExpires.isEmpty()) {
        // we already have pending expires so just add this one to the list
        this.pendingExpires.add(task);
        task = null;
      }
    }
    attemptPendingDrain(task);
  }

  protected final void attemptPendingDrain(ExpiryTask task) throws CacheException
  {
    final StoppableReentrantReadWriteLock lk = this.txExpirationLock;
    if (lk != null) {
      StoppableWriteLock sync = lk.writeLock();
      //try {
        if (sync.tryLock()) {
          try {
            basicProcessPendingExpires();
            if (task != null) {
              task.basicPerformTimeout(false);
            }
          }
          finally {
            sync.unlock();
          }
          {
            boolean moreToDo = false;
            synchronized (this.pendingExpires) {
              if (!this.pendingExpires.isEmpty()) {
                moreToDo = true;
              }
            }
            if (moreToDo) {
              attemptPendingDrain(null);
            }
          }
        }
        else if (task != null) {
          // We could not get the write lock so a transaction must be
          // in progress. Add a pendingExpire for the last transaction
          // to process.
          synchronized (this.pendingExpires) {
            this.pendingExpires.add(task);
          }
          attemptPendingDrain(null);
        }
    }
    else {
      // expiration must have been disabled on this region
      basicProcessPendingExpires();
      if (task != null) {
        task.basicPerformTimeout(false);
      }
    }
  }

  private void initRoot()
  {
    this.destroyLock = CFactory.createS(1);
  }

  public void handleMarker() {
    //LogWriterI18n logger = getCache().getLoggerI18n();
    
    RegionEventImpl event = new RegionEventImpl(this,
        Operation.MARKER, null, false, getMyId(),
        false /* generate EventID */);

    dispatchListenerEvent(EnumListenerEvent.AFTER_REGION_LIVE, event);
  }

  public AttributesMutator getAttributesMutator()
  {
    checkReadiness();
    return this;
  }

  public Region createSubregion(String subregionName,
      RegionAttributes regionAttributes) throws RegionExistsException,
      TimeoutException
  {
    try {
      return createSubregion(subregionName, regionAttributes,
          new InternalRegionArguments().setDestroyLockFlag(true)
              .setRecreateFlag(false));
    }
    catch (IOException e) {
      // only happens when loading a snapshot, not here
      InternalGemFireError assErr = new InternalGemFireError(LocalizedStrings.LocalRegion_UNEXPECTED_EXCEPTION.toLocalizedString());
      assErr.initCause(e);
      throw assErr;

    }
    catch (ClassNotFoundException e) {
      // only happens when loading a snapshot, not here
      InternalGemFireError assErr = new InternalGemFireError(LocalizedStrings.LocalRegion_UNEXPECTED_EXCEPTION.toLocalizedString());
      assErr.initCause(e);
      throw assErr;

    }
  }

  /**
   * Returns the member id of my distributed system
   *
   * @since 5.0
   */
  @Override
  protected final InternalDistributedMember getMyId() {
    return this.cache.getMyId();
  }

  /**
   * Get the cluster-wide unique ID for the region. Currently used by
   * persistence layer to determine if a region recovered is same as this one.
   * 
   * Currently only used by GemFireXD (#48335).
   */
  public final long getRegionUUID() {
    return this.regionUUID;
  }

  /**
   * Get a cluster-wide unique ID string for the region that combines the path
   * with {@link #getRegionUUID()}.
   * 
   * Currently only used by GemFireXD (#48335).
   */
  public final String getRegionID() {
    return this.regionId;
  }

  public static String getIDFromPath(String path, long uuid) {
    return path + ':' + uuid;
  }

  public final VersionSource getVersionMember()
  {
    if(dataPolicy.withPersistence()) {
      return getDiskStore().getDiskStoreID();
    } else {
      return this.cache.getMyId();
    }
  }

  public Region createSubregion(String subregionName,
      RegionAttributes attrs,
      InternalRegionArguments internalRegionArgs) throws RegionExistsException,
      TimeoutException, IOException, ClassNotFoundException {
    return createSubregion(subregionName, attrs, internalRegionArgs,
        false);
  }

  public Region createSubregion(String subregionName,
      RegionAttributes regionAttributes,
      InternalRegionArguments internalRegionArgs, boolean skipInitialization)
      throws RegionExistsException, TimeoutException, IOException,
      ClassNotFoundException {
    checkReadiness();
    LocalRegion newRegion = null;
    final InputStream snapshotInputStream = internalRegionArgs
        .getSnapshotInputStream();
    final boolean getDestroyLock = internalRegionArgs.getDestroyLockFlag();
    final InternalDistributedMember imageTarget = internalRegionArgs
        .getImageTarget();
    try {
      if (getDestroyLock)
        acquireDestroyLock();
      LocalRegion existing = null;
      try {
        if (isDestroyed()) {
          if (this.reinitialized_old) {
            throw new RegionReinitializedException(toString(), getFullPath());
          }
          throw new RegionDestroyedException(toString(), getFullPath());
        }
        validateRegionName(subregionName);

        validateSubregionAttributes(regionAttributes);
        String regionPath = calcFullPath(subregionName, this);

        // lock down the subregionsLock
        // to prevent other threads from adding a region to it in toRegion
        // but don't wait on initialization while synchronized (distributed
        // deadlock)
        synchronized (this.subregionsLock) {
          
          existing = (LocalRegion)this.subregions.get(subregionName);

          if (existing == null) {
            // create the async queue for HDFS if required. 
            HDFSIntegrationUtil.createAndAddAsyncQueue(regionPath,
                regionAttributes, this.cache);
            regionAttributes = cache.setEvictionAttributesForLargeRegion(
                regionAttributes);
            if (regionAttributes.getScope().isDistributed()
                && internalRegionArgs.isUsedForPartitionedRegionBucket()) {
              final PartitionedRegion pr = internalRegionArgs
                  .getPartitionedRegion();
              internalRegionArgs.setIndexUpdater(pr.getIndexUpdater());
              internalRegionArgs.setUserAttribute(pr.getUserAttribute());
              internalRegionArgs.setKeyRequiresRegionContext(pr
                  .keyRequiresRegionContext());
              if (pr.isShadowPR()) {
                if (!pr.isShadowPRForHDFS()) {
                    newRegion = new BucketRegionQueue(subregionName, regionAttributes,
                      this, this.cache, internalRegionArgs);
                }
                else {
                   newRegion = new HDFSBucketRegionQueue(subregionName, regionAttributes,
                      this, this.cache, internalRegionArgs);
                }
                
              } else {
                newRegion = new BucketRegion(subregionName, regionAttributes,
                    this, this.cache, internalRegionArgs);

              }
            }
            else if (regionAttributes.getPartitionAttributes() != null) {
              newRegion = new PartitionedRegion(subregionName,
                  regionAttributes, this, this.cache,  internalRegionArgs);
            }
            else {
              boolean local = regionAttributes.getScope().isLocal();
              newRegion = local ? new LocalRegion(subregionName,
                  regionAttributes, this, this.cache, internalRegionArgs)
                  : new DistributedRegion(subregionName, regionAttributes,
                      this, this.cache, internalRegionArgs);
            }
            Object o = this.subregions.putIfAbsent(subregionName, newRegion);

            Assert.assertTrue(o == null);

            LogWriterI18n logger = getCache().getLoggerI18n();
            Assert.assertTrue(!newRegion.isInitialized());

            //
            if (logger.fineEnabled()) {
              logger.fine("Subregion created: " + newRegion);
            }
            if (snapshotInputStream != null || imageTarget != null
                || internalRegionArgs.getRecreateFlag()) {
              this.cache.regionReinitialized(newRegion); // fix for bug 33534
            }

          } // endif: existing == null
        } // end synchronization
      }
      finally {
        if (getDestroyLock)
          releaseDestroyLock();
      }
      
      //Fix for bug 42127 - moved to outside of the destroy lock.
      if (existing != null) {
        // now outside of synchronization we must wait for appropriate
        // initialization on existing region before returning a reference to
        // it
        existing.waitOnInitialization();
        // fix for bug 32570
        throw new RegionExistsException(existing);
      }

      
      boolean success = false;
      try {
        newRegion.checkReadiness();
        this.cache.setRegionByPath(newRegion.getFullPath(), newRegion);
        if (skipInitialization) {
          success = true;
          return newRegion;
        }
        newRegion.preInitialize(internalRegionArgs);
        if (regionAttributes instanceof UserSpecifiedRegionAttributes){
          internalRegionArgs.setIndexes((
            (UserSpecifiedRegionAttributes)regionAttributes).getIndexes());  
        }
        newRegion.initialize(snapshotInputStream, imageTarget,
            internalRegionArgs); // releases initialization Latches
        //register the region with resource manager to get memory events
        if(!newRegion.isInternalRegion()){
          if (!newRegion.isDestroyed) {
            cache.getResourceManager().addResourceListener(ResourceType.MEMORY, newRegion);
            
            if (!newRegion.getEnableOffHeapMemory()) {
              newRegion.initialCriticalMembers(cache.getResourceManager().getHeapMonitor().getState().isCritical(), cache
                  .getResourceAdvisor().adviseCriticalMembers());
            } else {
              newRegion.initialCriticalMembers(cache.getResourceManager().getHeapMonitor().getState().isCritical()
                  || cache.getResourceManager().getOffHeapMonitor().getState().isCritical(), cache.getResourceAdvisor()
                  .adviseCriticalMembers());
            }

            // synchronization would be done on ManagementAdapter.regionOpLock
            // instead of destroyLock in LocalRegion? ManagementAdapter is one
            // of the Resource Event listeners            
            
            InternalDistributedSystem system = this.cache
                .getDistributedSystem();
            system.handleResourceEvent(ResourceEvent.REGION_CREATE, newRegion);
          }
        }
        success = true;
      } catch (CancelException e) {
        // don't print a call stack
        throw e;
      } catch(RedundancyAlreadyMetException e) {
        //don't log this
        throw e;
      } catch (final RuntimeException validationException) {
        this.cache.getLoggerI18n().warning(LocalizedStrings.
            LocalRegion_INITIALIZATION_FAILED_FOR_REGION_0,
            newRegion.getFullPath(), validationException);
        throw validationException;
      }
      finally {
        if (!success) {
          this.cache.setRegionByPath(newRegion.getFullPath(), null);
          initializationFailed(newRegion);
          cache.getResourceManager(false).removeResourceListener(newRegion);
        }
      }

      newRegion.postCreateRegion();
    }
    finally {
      // make sure region initialization latch is open regardless
      // before returning;
      // if the latch is not open at this point, then an exception must
      // have occurred
      if (newRegion != null && !skipInitialization
          && !newRegion.isInitialized()) {
        getCache().getLoggerI18n().fine(
            "Region initialize latch is closed, Error must have occurred");
      }
    }

    return newRegion;
  }

  /**
   * Explicitly initialize a region that was skipped during
   * {@link #createSubregion(String, RegionAttributes, InternalRegionArguments,
   *   boolean)}. Used by GemFireXD to delay initialization of a region during
   * DDL replay till all ALTER TABLE have not completed execution.
   */
  public void initialize(LocalRegion subRegion,
      InternalRegionArguments internalRegionArgs) throws RegionExistsException,
      TimeoutException, IOException, ClassNotFoundException {
    boolean success = false;
    try {
      try {
        // releases initialization Latches
        subRegion.initialize(internalRegionArgs.getSnapshotInputStream(),
            internalRegionArgs.getImageTarget(), internalRegionArgs);
        // register the region with resource manager to get heap events
        if (!subRegion.isInternalRegion()) {
          if (!subRegion.isDestroyed) {
            cache.getResourceManager().addResourceListener(ResourceType.MEMORY, subRegion);
            InternalDistributedSystem system = this.cache.getDistributedSystem();
            system.handleResourceEvent(ResourceEvent.REGION_CREATE, subRegion);
          }
        }
        success = true;
      } catch (CancelException e) {
        // don't print a call stack
        throw e;
      } catch (RedundancyAlreadyMetException e) {
        // don't log this
        throw e;
      } catch (final RuntimeException validationException) {
        this.cache.getLoggerI18n().warning(
            LocalizedStrings.LocalRegion_INITIALIZATION_FAILED_FOR_REGION_0,
            subRegion.getFullPath(), validationException);
        throw validationException;
      } catch (Error e) {
        this.cache.getLoggerI18n().warning(
            LocalizedStrings.LocalRegion_INITIALIZATION_FAILED_FOR_REGION_0,
            subRegion.getFullPath(), e);
        throw e;
      } finally {
        if (!success) {
          this.cache.setRegionByPath(subRegion.getFullPath(), null);
          initializationFailed(subRegion);
          cache.getResourceManager(false).removeResourceListener(subRegion);
        }
      }
      subRegion.postCreateRegion();
    } finally {
      // make sure region initialization latch is open regardless
      // before returning;
      // if the latch is not open at this point, then an exception must
      // have occurred
      if (subRegion != null && !subRegion.isInitialized()) {
        getCache().getLoggerI18n().fine(
            "Region initialize latch is closed, Error must have occurred");
      }
    }
  }

  public final void create(Object key, Object value, Object aCallbackArgument)
      throws TimeoutException, EntryExistsException, CacheWriterException {
    long startPut = CachePerfStats.getStatTime();
    //The OffHeap resource is freed in the validatedCreate method 
    //The freeing of OffHeapResource is being done in validatedCreate because
    //SqlFire invokes validatedCreate directly
    try {
      operationStart();
    EntryEventImpl event = newCreateEntryEvent(key, value, aCallbackArgument);
    validatedCreate(event, startPut);
    // TODO OFFHEAP: validatedCreate calls freeOffHeapResources
    } finally {
      operationCompleted();
    }
   
  }

  public final void validatedCreate(EntryEventImpl event, long startPut)
      throws TimeoutException, EntryExistsException, CacheWriterException {

    try {
      if (event.getEventId() == null && generateEventID()) {
        event.setNewEventId(cache.getDistributedSystem());
      }
      assert event.isFetchFromHDFS() : "validatedPut() should have been called";
      // Fix for 42448 - Only make create with null a local invalidate for
      // normal regions. Otherwise, it will become a distributed invalidate.
      if (getDataPolicy() == DataPolicy.NORMAL) {
        event.setLocalInvalid(true);
      }

      if (!basicPut(event, true, // ifNew
          false, // ifOld
          null, // expectedOldValue
          true // requireOldValue TODO txMerge why is oldValue required for
               // create? I think so that the EntryExistsException will have it.
      )) {
        throw new EntryExistsException(event.getKey().toString(),
            event.getOldValue());
      } else {
        if (!getDataView(event).isDeferredStats()) {
          getCachePerfStats().endPut(startPut, false);
        }
      }
    } finally {

      event.release();

    }
  }

  // split into a separate newCreateEntryEvent since GemFireXD may need to
  // manipulate event before doing the put (e.g. posDup flag)
  public final EntryEventImpl newCreateEntryEvent(Object key, Object value,
      Object aCallbackArgument) {

    validateArguments(key, value, aCallbackArgument);
    checkReadiness();
    checkForLimitedOrNoAccess();

    return EntryEventImpl.create(this, Operation.CREATE, key,
        value, aCallbackArgument, false, getMyId())
        /* to distinguish genuine create */.setCreate(true);
  }

  /**
   * The default Region implementation will generate EvenTID in the EntryEvent
   * object. This method is overridden in special Region objects like HARegion
   * or SingleWriteSingleReadRegionQueue.SingleReadWriteMetaRegion to return
   * false as the event propagation from those regions do not need EventID
   * objects
   *
   * <p>author Asif
   * @return boolean indicating whether to generate eventID or not
   */
  @Override
  public boolean generateEventID()
  {     
    return !(isUsedForPartitionedRegionAdmin()
        || isUsedForPartitionedRegionBucket() );
  }

  public final Object destroy(Object key, Object aCallbackArgument)
      throws TimeoutException, EntryNotFoundException, CacheWriterException {
    EntryEventImpl event = newDestroyEntryEvent(key, aCallbackArgument);
    //The OffHeap resource is freed in the validatedDestroy method 
    //The freeing of OffHeapResource is being done in validatedDestroy because
    //SqlFire invokes validatedDestroy directly
    try {
      operationStart();
    return validatedDestroy(key, event);
    // TODO OFFHEAP: validatedDestroy calls freeOffHeapResources
    } finally {
      operationCompleted();
    }
    
  }
  
  /**
   * Destroys entry without performing validations. Call this after validating
   * key, callback arg, and runtime state.
   */
  public Object validatedDestroy(Object key, EntryEventImpl event)
      throws TimeoutException, EntryNotFoundException, CacheWriterException
 {
    try {
      if (event.getEventId() == null && generateEventID()) {
        event.setNewEventId(cache.getDistributedSystem());
      }
      basicDestroy(event, true, // cacheWrite
          null); // expectedOldValue
      if (event.isOldValueOffHeap()) {
        return null;
      } else {
        return handleNotAvailable(event.getOldValue());
      }
    } finally {
      event.release();
    }
  }

  // split into a separate newDestroyEntryEvent since GemFireXD may need to
  // manipulate event before doing the put (e.g. posDup flag)
  public final EntryEventImpl newDestroyEntryEvent(Object key,
      Object aCallbackArgument) {
    validateKey(key);
    validateCallbackArg(aCallbackArgument);
    checkReadiness();
    checkForLimitedOrNoAccess();

    return EntryEventImpl.create(this, Operation.DESTROY, key,
        null/* newValue */, aCallbackArgument, false, getMyId());
  }

  public void destroyRegion(Object aCallbackArgument)
      throws CacheWriterException, TimeoutException {
    getDataView().checkSupportsRegionDestroy();
    checkForLimitedOrNoAccess();

    RegionEventImpl event = new RegionEventImpl(this, Operation.REGION_DESTROY,
        aCallbackArgument, false, getMyId(), generateEventID());
    basicDestroyRegion(event, true);
  }

  public final InternalDataView getDataView() {
    if (this.supportsTX) {
      final TXStateInterface tx = TXManagerImpl.getCurrentTXState();
      if (tx != null ) {
        // NORMAL/PRELOADED regions are now supported in the new TX model mixed
        // with PRs just like replicated regions
        /*
        if (this.scope.isDistributed()
            && (this.dataPolicy == DataPolicy.NORMAL
                || this.dataPolicy == DataPolicy.PRELOADED)) {
          throw new UnsupportedOperationException(LocalizedStrings
              .TX_NORMAL_PRELOADED_REGION_NOT_SUPPORTED.toLocalizedString());
        }
        */
        return tx;
      }
    }
    return this.sharedDataView;
  }

  public final InternalDataView getDataView(final TXStateInterface tx) {
    if (tx == null) {
      return this.sharedDataView;
    }
    // NORMAL/PRELOADED regions are now supported in the new TX model mixed
    // with PRs just like replicated regions
    /*
    if (this.scope.isDistributed()
        && (this.dataPolicy == DataPolicy.NORMAL
            || this.dataPolicy == DataPolicy.PRELOADED)) {
      throw new UnsupportedOperationException(LocalizedStrings
          .TX_NORMAL_PRELOADED_REGION_NOT_SUPPORTED.toLocalizedString());
    }
    */
    return tx;
  }

  public final InternalDataView getDataView(final EntryEventImpl event) {
    final TXStateInterface tx = event.getTXState(this);
    if (tx == null) {
      return this.sharedDataView;
    }
    return tx;
  }

  /**
   * Fetch the de-serialized value from non-transactional state.
   * @param key to which the value is associated
   * @param callbackArg any callback argument for the operation
   * @param updateStats true if the entry stats should be updated.
   * @param disableCopyOnRead if true then disable copy on read
   * @param preferCD true if the preferred result form is CachedDeserializable
   * @param lockState the TXState that must be used to lock the current entry
   * @param clientEvent client's event, if any (for version tag retrieval)
   * @param returnTombstones TODO
   * @return the value for the given key
   */
  public final Object getDeserializedValue(RegionEntry re, final Object key,
      final Object callbackArg, final boolean updateStats,
      final boolean disableCopyOnRead, final boolean preferCD,
      final TXStateInterface lockState, EntryEventImpl clientEvent,
      boolean returnTombstones, boolean allowReadFromHDFS) {
    if (this.diskRegion != null) {
      this.diskRegion.setClearCountReference();
    }
    try {
      if (re == null) {
        if (allowReadFromHDFS) {
          re = this.entries.getEntry(key);
        } else {
          re = this.entries.getOperationalEntryInVM(key);
        }
      }

      //skip updating the stats if the value is null
      // TODO - We need to clean up the callers of the this class so that we can
      // update the statistics here, where it would make more sense.
      if (re != null) {
        if (lockState != null) {
          // for txns, acquire read lock if required
          int context = 0;
          if (updateStats) {
            context |= ReadEntryUnderLock.DESER_UPDATE_STATS;
          }
          if (disableCopyOnRead) {
            context |= ReadEntryUnderLock.DESER_DISABLE_COPY_ON_READ;
          }
          if (preferCD) {
            context |= ReadEntryUnderLock.DESER_PREFER_CD;
          }
          return lockState.lockEntryForRead(re, key, this, context,
              returnTombstones, DESER_VALUE);
        }
        else {
          final Object value;
          if (clientEvent != null && re.getVersionStamp() != null) {
            // defer the lruUpdateCallback to prevent a deadlock (see bug 51121).
            this.entries.disableLruUpdateCallback();
            try {
              synchronized(re) { // bug #51059 value & version must be obtained atomically
                clientEvent.setVersionTag(re.getVersionStamp().asVersionTag());
                value = getDeserialized(re, updateStats, disableCopyOnRead, preferCD);
              }
            } finally {
              this.entries.enableLruUpdateCallback();
              try {
                this.entries.lruUpdateCallback();
              }catch( DiskAccessException dae) {
                this.handleDiskAccessException(dae, true /* stop bridge servers*/);
                throw dae;
              }
            }
             
          } else {
            value = getDeserialized(re, updateStats, disableCopyOnRead, preferCD);
          }

          if (getLogWriterI18n().finerEnabled() && !(this instanceof HARegion)) {
            getLogWriterI18n().finer("getDeserializedValue for " + key
                + " returning version: " + (re.getVersionStamp()==null? "null"
                  : re.getVersionStamp().asVersionTag()) + " returnTombstones: "
                + returnTombstones + " value: " + value);
          }
          return value;
        }
      }
      return null;
    } finally {
      if (this.diskRegion != null) {
        this.diskRegion.removeClearCountReference();
      }
    }
  }

  /**
   *
   * @param re
   * @param updateStats
   * @param disableCopyOnRead if true then do not make a copy on read
   * @param preferCD true if the preferred result form is CachedDeserializable
   * @return the value found, which can be
   *  <ul>
   *    <li> null if the value was removed from the region entry
   *    <li>Token.INVALID if the value of the region entry is invalid
   *    <li>Token.LOCAL_INVALID if the value of the region entry is local invalid
   *  </ul>
   */
  protected final Object getDeserialized(RegionEntry re, boolean updateStats,
      boolean disableCopyOnRead, boolean preferCD) {
    try {
      Object v = null;
      try {
         v = re.getValue(this); // OFFHEAP: incrc, deserialize, decrc TODO: optimize if preferCD but need to track down when to decrc in that case
      } catch(DiskAccessException dae) {
        this.handleDiskAccessException(dae, true/* stop bridge servers*/);
        throw dae;
      }
  
      //skip updating the stats if the value is null
      if (v == null) {
        return null;
      }
      if (preferCD) {
        if (!disableCopyOnRead && !(v instanceof CachedDeserializable)) {
          v = conditionalCopy(v);
        }
      }
      else {
        if (v instanceof CachedDeserializable) {
          if (isCopyOnRead()) {
            if (disableCopyOnRead) {
              v = ((CachedDeserializable)v).getDeserializedForReading();
            } else {
              v = ((CachedDeserializable)v).getDeserializedWritableCopy(this, re);
            }
          } else {
            v = ((CachedDeserializable)v).getDeserializedValue(this, re);
          }
        }
        else if (!disableCopyOnRead) {
          v = conditionalCopy(v);
        }
      }

      if (updateStats) {
        updateStatsForGet(re, v != null && !Token.isInvalid(v));
      }
      return v;
    } catch(IllegalArgumentException i) {
      IllegalArgumentException iae = new IllegalArgumentException(
          LocalizedStrings.DONT_RELEASE.toLocalizedString(
              "Error while deserializing value for key=" + re.getKeyCopy()));
      iae.initCause(i);
      throw iae;
    }
  }

  @Override
  public final Object get(Object key, Object aCallbackArgument,
      boolean generateCallbacks, EntryEventImpl clientEvent)
      throws TimeoutException, CacheLoaderException {
    Object result = get(key, aCallbackArgument, generateCallbacks, false, false,
        null, discoverJTA(), null, clientEvent, false, true/*allowReadFromHDFS*/);
    if (Token.isInvalid(result)) {
      result = null;
    }
    return result;
  }

  /**
   * @see BucketRegion#getSerialized(Object, Object, KeyInfo, boolean, boolean,
   *      TXStateInterface, EntryEventImpl, boolean, boolean)
   */
  public Object get(Object key, Object aCallbackArgument,
      final boolean generateCallbacks, final boolean disableCopyOnRead,
      final boolean preferCD, final ClientProxyMembershipID requestingClient,
      final TXStateInterface tx, final TXStateInterface lockState,
      EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadFromHDFS) throws TimeoutException, CacheLoaderException {

    validateKey(key);
    validateCallbackArg(aCallbackArgument);
    checkReadiness();
    checkForNoAccess();
    final CachePerfStats stats = getCachePerfStats();
    final long start = stats.startGet();
    boolean isMiss = true;
    try {
      final InternalDataView view = getDataView(tx);
      Object value = view.getDeserializedValue(key, aCallbackArgument, this,
          true, disableCopyOnRead, preferCD, lockState, clientEvent,
          returnTombstones, allowReadFromHDFS);
      final boolean isCreate = (value == null);
      isMiss = isCreate || Token.isInvalid(value)
          || (!returnTombstones && value == Token.TOMBSTONE);
      // Note: if the value was Token.DESTROYED then getDeserialized
      // returns null so we don't need the following in the above expression:
      // || (isRegInterestInProgress() && Token.isDestroyed(value))
      // because (value == null) will be true in this case.
      if (isMiss) {
        // if scope is local and there is no loader, then
        // don't go further to try and get value
        if ((getScope().isDistributed() && !isHDFSRegion())
            || hasServerProxy()
            || basicGetLoader() != null) {
          // serialize search/load threads if not in txn
          value = view.findObject(getKeyInfo(key, aCallbackArgument), this,
              isCreate, generateCallbacks, value, disableCopyOnRead, preferCD,
              requestingClient, clientEvent, returnTombstones, false/*allowReadFromHDFS*/);
          if (!returnTombstones && value == Token.TOMBSTONE) {
            value = null;
          }
        }
        else { // local scope with no loader, still might need to update stats
          if (isCreate) {
            recordMiss(tx, null, key);
          }
          value = null;
        }
      }
      return value;
    }
    finally {
      stats.endGet(start, isMiss);
    }
  }

  /**
   * Update region and potentially entry stats for the miss case 
   * @param re optional region entry, fetched if null
   * @param key the key used to fetch the region entry
   */
  final public void recordMiss(final TXStateInterface tx, final RegionEntry re,
      Object key) {
    final RegionEntry e;
    if (re == null && tx == null && !isHDFSRegion()) {
      e = basicGetEntry(key);
    } else {
      e = re;
    }
    if (e != ProxyRegionMap.markerEntry) {
      updateStatsForGet(e, false);
    }
  }

  /**
   * @return true if this region has been configured for HDFS persistence
   */
  public boolean isHDFSRegion() {
    return false;
  }

  /**
   * @return true if this region is configured to read and write data from HDFS
   */
  public boolean isHDFSReadWriteRegion() {
    return false;
  }

  /**
   * @return true if this region is configured to only write to HDFS
   */
  protected boolean isHDFSWriteOnly() {
    return false;
  }

  /**
   * FOR TESTING ONLY
   */
  public HoplogListenerForRegion getHoplogListener() {
    return hoplogListener;
  }
  
  /**
   * FOR TESTING ONLY
   */
  public HdfsRegionManager getHdfsRegionManager() {
    return hdfsManager;
  }
  
  /**
   * optimized to only allow one thread to do a search/load, other threads wait
   * on a future
   *
   * @param keyInfo
   * @param p_isCreate
   *                true if call found no entry; false if updating an existing
   *                entry
   * @param generateCallbacks
   * @param p_localValue
   *                the value retrieved from the region for this object.
   * @param disableCopyOnRead if true then do not make a copy
   * @param preferCD true if the preferred result form is CachedDeserializable
   * @param clientEvent the client event, if any
   * @param returnTombstones whether to return tombstones
   */
  @Retained
  protected Object nonTxnFindObject(KeyInfo keyInfo, boolean p_isCreate,
      boolean generateCallbacks, Object p_localValue, boolean disableCopyOnRead,
      boolean preferCD, EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadFromHDFS)
      throws TimeoutException, CacheLoaderException {
    final Object key = keyInfo.getKey();
    Object localValue = p_localValue;
    boolean isCreate = p_isCreate;
    Object[] valueAndVersion = null;
    @Retained Object result = null;
    FutureResult thisFuture = new FutureResult(this.stopper);
    Future otherFuture = (Future)this.getFutures.putIfAbsent(key, thisFuture);
    // only one thread can get their future into the map for this key at a time
    if (otherFuture != null) {
      try {
        valueAndVersion = (Object[])otherFuture.get();
        if (valueAndVersion != null) {
          result = valueAndVersion[0];
          if (clientEvent != null) {
            clientEvent.setVersionTag((VersionTag)valueAndVersion[1]);
          }
          if (!preferCD && result instanceof CachedDeserializable) {
            CachedDeserializable cd = (CachedDeserializable)result;
            // fix for bug 43023
            if (!disableCopyOnRead && isCopyOnRead()) {
              result = cd.getDeserializedWritableCopy(null, null);
            } else {
              result = cd.getDeserializedForReading();
            }
           
          } else if (!disableCopyOnRead) {
            result = conditionalCopy(result);
          }
          //For gfxd since the deserialized value is nothing but chunk
          // before returning the found value increase its use count
          if(GemFireCacheImpl.gfxdSystem() && result instanceof Chunk) {
            if(!((Chunk)result).retain()) {
              return null;
            }
          }
          // what was a miss is now a hit
          RegionEntry re = null;
          if (isCreate) {
            re = basicGetEntry(key);
            updateStatsForGet(re, true);
          }
          return result;
        }
        // if value == null, try our own search/load
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // TODO check a CancelCriterion here?
        return null;
      }
      catch (ExecutionException e) {
        // unexpected since there is no background thread
        InternalGemFireError err = new InternalGemFireError(LocalizedStrings.LocalRegion_UNEXPECTED_EXCEPTION.toLocalizedString());
        err.initCause(err);
        throw err;
      }
    }
    // didn't find a future, do one more getDeserialized to catch race
    // condition where the future was just removed by another get thread
    try {
      localValue = getDeserializedValue(null, key, keyInfo.getCallbackArg(),
          isCreate, disableCopyOnRead, preferCD, null, clientEvent, false, false/*allowReadFromHDFS*/);
      // TODO verify that this method is not used for PR or BR and hence allowReadFromHDFS does not matter
      // stats have now been updated
      if (localValue != null && !Token.isInvalid(localValue)) {
        result = localValue;
        return result;
      }
      isCreate = localValue == null;

      result = findObjectInSystem(keyInfo, isCreate, null, generateCallbacks,
          localValue, disableCopyOnRead, preferCD, null, clientEvent, returnTombstones, false/*allowReadFromHDFS*/);
      
      if (result == null && localValue != null) {
        if (localValue != Token.TOMBSTONE || returnTombstones) {
          result = localValue;
        }
      }
      // findObjectInSystem does not call conditionalCopy
    }
    finally {
      VersionTag tag = (clientEvent==null)? null : clientEvent.getVersionTag();
      thisFuture.set(new Object[]{result, tag});
      this.getFutures.remove(key);
    }
    if (!disableCopyOnRead) {
      result = conditionalCopy(result);
    }
    return result;
  }

  /**
   * Returns true if get should give a copy; false if a reference.
   *
   * @since 4.0
   */
  protected boolean isCopyOnRead()
  {
    return this.compressor == null
      && this.cache.isCopyOnRead()
      && ! this.isUsedForPartitionedRegionAdmin
      && ! this.isUsedForMetaRegion
      && ! getEnableOffHeapMemory()
      && ! isSecret();
  }

  /**
   * Makes a copy, if copy-on-get is enabled, of the specified object.
   *
   * @since 4.0
   */
  protected final Object conditionalCopy(Object o) {
    if (isCopyOnRead()) {
      return makeCopy(o);
    }
    else {
      return o;
    }
  }

  /**
   * Makes a copy, if copy-on-get is enabled, of the specified object.
   *
   * @since 4.0
   */
  protected final Object makeCopy(Object o) {
    if (!Token.isInvalid(o)) {
      return CopyHelper.copy(o);
    }
    return o;
  }

  private final String fullPath;
  private final ReentrantLock regionLock;

  public final String getFullPath() {
    return this.fullPath;
  }

  @Override
  public int hashCode() {
    return this.fullPath.hashCode();
  }

  @Override
  protected StringBuilder getStringBuilder() {
    return super.getStringBuilder().append("; indexUpdater=").append(
        getIndexUpdater());
  }

  //   public String getFullPath() {
  //     // work way up to root region, prepending
  //     // the region names to a buffer
  //     StringBuffer buf = new StringBuffer(SEPARATOR);
  //     Assert.assertTrue(this.regionName != null);
  //     buf.append(this.regionName);
  //     LocalRegion r = this;
  //     while ((r = r.parentRegion) != null) {
  //       buf.insert(0, r.regionName);
  //       buf.insert(0, SEPARATOR_CHAR);
  //     }
  //     return buf.toString();
  //   }

  public Region getParentRegion()
  {
    //checkReadiness();
    return this.parentRegion;
  }

  public Region getSubregion(String path)
  {
    checkReadiness();
    return getSubregion(path, false, false);
  }

  public void invalidateRegion(Object aCallbackArgument)
      throws TimeoutException {
    getDataView().checkSupportsRegionInvalidate();
    validateCallbackArg(aCallbackArgument);
    checkReadiness();
    checkForLimitedOrNoAccess();
    RegionEventImpl event = new RegionEventImpl(this, Operation.REGION_INVALIDATE,
      aCallbackArgument, false, getMyId(), generateEventID());

    basicInvalidateRegion(event);
  }

  public Object put(Object key, Object value, Object aCallbackArgument)
      throws TimeoutException, CacheWriterException {
    long startPut = CachePerfStats.getStatTime();
    EntryEventImpl event = newUpdateEntryEvent(key, value, aCallbackArgument);
     //Since Gemfirexd directly calls validatedPut, the freeing is done in
    // validatedPut
     return validatedPut(event, startPut);
     // TODO OFFHEAP: validatedPut calls freeOffHeapResources
    
  }

  public final Object validatedPut(EntryEventImpl event, long startPut)
      throws TimeoutException, CacheWriterException {

    try {
      operationStart();
      if (event.getEventId() == null && generateEventID()) {
        event.setNewEventId(cache.getDistributedSystem());
      }
      Object oldValue = null;
      // Gfxd changes begin
      // see #40294.

      // Rahul: this has to be an update.
      // so executing it as an update.
      boolean forceUpdateForDelta = event.hasDelta();
      // Gfxd Changes end.
      if (basicPut(event, false, // ifNew
          forceUpdateForDelta, // ifOld
          null, // expectedOldValue
          false // requireOldValue
      )) {
        if (!event.isOldValueOffHeap()) {
          // don't copy it to heap just to return from put.
          // TODO: come up with a better way to do this.
          oldValue = event.getOldValue();
        }
        if (!getDataView(event).isDeferredStats()) {
          getCachePerfStats().endPut(startPut, false);
        }
      }
      return handleNotAvailable(oldValue);
    } finally {
      operationCompleted();
      event.release();
    }
  }

  // split into a separate newUpdateEntryEvent since GemFireXD may need to
  // manipulate event before doing the put (e.g. posDup flag)
  public final EntryEventImpl newUpdateEntryEvent(Object key, Object value,
      Object aCallbackArgument) {

    validateArguments(key, value, aCallbackArgument);
    if (value == null) {
      throw new NullPointerException(LocalizedStrings
          .LocalRegion_VALUE_MUST_NOT_BE_NULL.toLocalizedString());
    }
    checkReadiness();
    checkForLimitedOrNoAccess();

    // This used to call the constructor which took the old value. It
    // was modified to call the other EntryEventImpl constructor so that
    // an id will be generated by default. Null was passed in anyway.
    //   generate EventID
    final EntryEventImpl event = EntryEventImpl.create(
        this, Operation.UPDATE, key,
        value, aCallbackArgument, false, getMyId());
    boolean eventReturned = false;
    try {
    extractDeltaIntoEvent(value, event);
    eventReturned = true;
    return event;
    } finally {
      if (!eventReturned) event.release();
    }
  }

  /**
   * Creates an EntryEventImpl that is optimized to not fetch data from HDFS.
   * This is meant to be used by PUT dml from GemFireXD.
   */
  public final EntryEventImpl newPutEntryEvent(Object key, Object value,
      Object aCallbackArgument) {
    EntryEventImpl ev = newUpdateEntryEvent(key, value, aCallbackArgument);
    ev.setFetchFromHDFS(false);
    ev.setPutDML(true);
    return ev;
  }

  /**
   * Get a new unique long UUID that is guaranteed to be unique in the
   * distributed system for this region.
   * 
   * @param throwOnOverflow
   *          if true then throw {@link IllegalStateException} when the UUIDs
   *          have been exhausted in the distributed system and the system can
   *          no longer guarantee that the UUID is unique even across VMs that
   *          may be currently offline
   * 
   * @throws IllegalStateException
   *           thrown when the UUIDs have been exhausted in the distributed
   *           system; note that it is not necessary that all possible long
   *           values would have been used by someone (e.g. a VM goes down
   *           without using its "block" of IDs)
   */
  public final long newUUID(final boolean throwOnOverflow)
      throws IllegalStateException {
    if (this.uuidAdvisor != null) {
      return this.uuidAdvisor.newUUID(throwOnOverflow);
    }
    // advisor is null for LocalRegions or BucketRegions but currently no UUIDs
    // for latter should be requested
    if (getScope().isLocal()) {
      return this.localUUID.incrementAndGet();
    }
    else {
      throw new InternalGemFireError("unexpected call to newUUID for "
          + toString());
    }
  }

  /**
   * Reset the UUID to given start value. It will return a value starting from
   * the value provided to this method and the next call to
   * {@link #newUUID(boolean)} will return a value greater than it.
   * 
   * @throws IllegalArgumentException
   *           if the given start value is negative
   * @throws IllegalStateException
   *           if no UUID beyond the given start value can be generated in the
   *           distributed system
   */
  public final long resetUUID(final long startValue)
      throws IllegalArgumentException, IllegalStateException {
    if (this.uuidAdvisor != null) {
      return this.uuidAdvisor.resetUUID(startValue);
    }
    // advisor is null for LocalRegions or BucketRegions but currently no UUIDs
    // for latter should be requested
    if (getScope().isLocal()) {
      this.localUUID.set(startValue);
      return startValue;
    }
    else {
      throw new InternalGemFireError("unexpected call to resetUUID for "
          + toString());
    }
  }

  /**
   * Get a new unique integer UUID that is unique in the distributed system for
   * this region as long as it does not overflow.
   * 
   * @throws IllegalStateException
   *           thrown when UUIDs have been exhaused in the distributed system;
   *           note that it is not necessary that all possible integer values
   *           would have been used by someone (e.g. a VM goes down without
   *           using its "block" of IDs)
   */
  public final int newShortUUID() throws IllegalStateException {
    if (this.uuidAdvisor != null) {
      return this.uuidAdvisor.newShortUUID();
    }
    // advisor is null for LocalRegions or BucketRegions but currently no UUIDs
    // for latter should be requested
    if (getScope().isLocal()) {
      return this.localShortUUID.incrementAndGet();
    }
    else {
      throw new InternalGemFireError("unexpected call to newShortUUID for "
          + toString());
    }
  }

  /**
   * Reset the short UUID to given start value. It will return a value starting
   * from the value provided to this method and the next call to
   * {@link #newShortUUID()} will return a value greater than it.
   * 
   * @throws IllegalArgumentException
   *           if the given start value is negative
   * @throws IllegalStateException
   *           if no UUID beyond the given start value can be generated in the
   *           distributed system
   */
  public final int resetShortUUID(final int startValue)
      throws IllegalArgumentException, IllegalStateException {
    if (this.uuidAdvisor != null) {
      return this.uuidAdvisor.resetShortUUID(startValue);
    }
    // advisor is null for LocalRegions or BucketRegions but currently no UUIDs
    // for latter should be requested
    if (getScope().isLocal()) {
      this.localShortUUID.set(startValue);
      return startValue;
    }
    else {
      throw new InternalGemFireError("unexpected call to resetShortUUID for "
          + toString());
    }
  }

  private void extractDeltaIntoEvent(Object value, EntryEventImpl event) {
    // 1. Check for DS-level delta property.
    // 2. Default value for operation type is UPDATE, so no need to check that here.
    // 3. Check if it has server region proxy. 
    //    We do not have a handle to event in PutOpImpl to check if we have 
    //    delta bytes calculated already. So no need to calculate it here.
    // 4. Check if value is instanceof com.gemstone.gemfire.Delta
    // 5. Check if Region in PR with redundantCopies > 0. Set extractDelta.
    // 6. Check if Region has peers. Set extractDelta.
    // 7. Check if it has any delta proxies attached to it. Set extractDelta.
    // 8. If extractDelta is set, check if it has delta.
    // 9. If delta is found, extract it and set it into the event.
    // 10. If any exception is caught while invoking the delta callbacks, throw it back.
    // 11. Wrap any checked exception in InternalGemFireException before throwing it.
    try {
      boolean extractDelta = false;
      // How costly is this if check?
      if (this.getSystem().getConfig().getDeltaPropagation()
          && value instanceof com.gemstone.gemfire.Delta) {
        if (!this.hasServerProxy()) {
          if ((this instanceof PartitionedRegion)) {
            if (((PartitionedRegion)this).getRedundantCopies() > 0) {
              extractDelta = true;
            } else {
              InternalDistributedMember ids = (InternalDistributedMember)PartitionRegionHelper
                  .getPrimaryMemberForKey(this, event.getKey());
              if (ids != null) {
                extractDelta = !this.getSystem().getMemberId().equals(
                    ids.getId());
              } else {
                extractDelta = true;
              }
            }
          } else if ((this instanceof DistributedRegion)
              && !((DistributedRegion)this).scope.isDistributedNoAck()
              && ((DistributedRegion)this).getCacheDistributionAdvisor()
                  .adviseCacheOp().size() > 0) {
            extractDelta = true;
          }
          if (!extractDelta && ClientHealthMonitor.getInstance() != null) {
            extractDelta = ClientHealthMonitor.getInstance().hasDeltaClients();
          }
        } else if (HandShake.isDeltaEnabledOnServer()) {
          // This is a client region
          extractDelta = true;
        }
        final com.gemstone.gemfire.Delta delta = (com.gemstone.gemfire.Delta)value;
        if (extractDelta && delta.hasDelta()) {
          HeapDataOutputStream hdos = new HeapDataOutputStream(Version.CURRENT);
          long start = DistributionStats.getStatTime();
          try {
            delta.toDelta(hdos);
          } catch (RuntimeException re) {
            throw re;
          } catch (Exception e) {
            throw new DeltaSerializationException(
                LocalizedStrings.DistributionManager_CAUGHT_EXCEPTION_WHILE_SENDING_DELTA
                    .toLocalizedString(), e);
          }
          event.setDeltaBytes(hdos.toByteArray());
          this.getCachePerfStats().endDeltaPrepared(start);
        }
      }
    } catch (RuntimeException re) {
      throw re;
    } catch (Exception e) {
      throw new InternalGemFireException(e);
    }
  }

  public final Region.Entry getEntry(Object key) {
    try {
      operationStart();
    return getEntry(key, discoverJTA());
    } finally {
      operationCompleted();
    }
  }

  public Region.Entry getEntry(Object key, final TXStateInterface tx) {
    validateKey(key);
    checkReadiness();
    checkForNoAccess();
    return getDataView(tx).getEntry(key, null, this, false);
  }

  /** internally we often need to get an entry whether it is a tombstone or not */
  public final Region.Entry getEntry(Object key, boolean allowTombstones) {
    return getEntry(key, discoverJTA(), allowTombstones);
  }

  /** internally we often need to get an entry whether it is a tombstone or not */
  public Region.Entry getEntry(Object key, final TXStateInterface tx,
      boolean allowTombstones) {
    try {
      operationStart();
    return getDataView(tx).getEntry(key, null, this, allowTombstones);
    } finally {
      operationCompleted();
    }
  }

  public final Region.Entry<?, ?> accessEntry(final Object key,
      boolean updateStats) {
    return accessEntry(key, getTXState(), updateStats);
  }

  /**
   * Just like getEntry but also updates the stats that get would have depending
   * on a flag. See bug 42410. Also skips discovering JTA
   * 
   * @param key
   * @return the entry if it exists; otherwise null.
   */
  public Region.Entry<?, ?> accessEntry(final Object key,
      final TXStateInterface tx, boolean updateStats) {
    validateKey(key);
    checkReadiness();
    checkForNoAccess();
    if(updateStats) {
      return getDataView(tx).accessEntry(key, null, this);
    } else {
      return getDataView(tx).getEntry(key, null, this, false);
    }
  }

  /**
   * @param keyInfo
   * @param access
   *          true if caller wants last accessed time updated
   * @return TODO
   */
  protected Region.Entry<?, ?> txGetEntry(KeyInfo keyInfo, boolean access,
      final TXStateInterface tx, boolean allowTombstones) {
    final Object key = keyInfo.getKey();
    final RegionEntry re = this.entries.getEntry(key);
    return txGetEntry(re, key, access, tx, allowTombstones);
  }

  private final Region.Entry<?, ?> txGetEntry(final RegionEntry re,
      final Object key, boolean access, final TXStateInterface lockState,
      boolean allowTombstones) {
    final boolean miss;
    Object val = null;
    if (re != null) {
      // for txns, acquire read lock if required
      if (lockState != null) {
        val = lockState.lockEntryForRead(re, key, this, 0,
            allowTombstones, READ_VALUE);
        if (Token.isRemoved(val)) {
          val = null;
        }
        miss = (val == null);
      }
      else {
        miss = re.isDestroyedOrRemoved();
      }
    }
    else {
      miss = true;
    }
    if (access) {
      updateStatsForGet(re, !miss);
    }
    if (re == null) {
      return null;
    }
    if (re.isTombstone()) {
      if (!allowTombstones) {
        return null;
      } // else return an entry (client GII / putAll results)
    } else if (miss) {
      return null;
    }

    Region.Entry ren = new NonTXEntry(re, val);
    //long start=0, end=0;
    //start = System.currentTimeMillis();
    //end = System.currentTimeMillis();
    //System.out.println("getEntry: " + (end-start));
    return ren;
  }

  protected Region.Entry<?, ?> txGetEntryForIterator(KeyInfo keyInfo,
      boolean access, final TXStateInterface tx, boolean allowTombstones) {
    final RegionEntry re = (RegionEntry)keyInfo.getKey();
    return txGetEntry(re, re.getKey(), access, tx, allowTombstones);
  }

  /**
   * Get the best iterator for iterating over the contents of this
   * region. This method will return either an iterator that uses hash
   * ordering from the entry map, or, in the case of an overflow
   * region, an iterator that iterates over the entries in disk order (except if
   * the region is an internal one which will always return hash iterator)
   */
  public Iterator<RegionEntry> getBestLocalIterator(boolean includeValues) {
    return getBestLocalIterator(includeValues, 1.0, false);
  }
  
  
  final long getDiskIteratorCacheSize(double reduceCacheFactor) {
    if (includeHDFSResults()) {
      return -1;
    }
    
    final DiskRegion dr;
    if ((dr = getDiskRegion()) != null && !isUsedForMetaRegion()
        && !isUsedForPartitionedRegionAdmin()) {
      //Wait for the disk region to recover values first.
      // Commenting it as it was causing a distributed deadlock if there is 
      // a GII happpening from remote node in case of gemfireXD
      // the value recovery waits for index recovery to happen which in turn
      // waits for DDL replay to complete. So, if two different nodes have
      // latest DISK STORES for two regions then it can cause a distributed deadlock.
      // Bug 52317
      //dr.waitForAsyncRecovery();
      if (dr.getNumOverflowOnDisk() > 0) {
        long cacheSize = DistributedRegion.MAX_PENDING_ENTRIES;

        if (reduceCacheFactor > 0.0 && reduceCacheFactor != 1.0) {
          cacheSize = (long)(cacheSize / reduceCacheFactor);
        }
        return cacheSize;
      }
    }
    return -1;
  }

  protected boolean includeHDFSResults() {
    return isUsedForPartitionedRegionBucket() 
        && isHDFSReadWriteRegion() 
        && getPartitionedRegion().includeHDFSResults();
  }

  final long adjustDiskIterCacheSize(long cacheSize, long numEntries) {
    return Math.max(Math.min(cacheSize, numEntries + 256), 8192L);
  }

  /**
   * Get the best iterator for iterating over the contents of this local region.
   * This method will return either an iterator that uses hash ordering from the
   * entry map, or, in the case of an overflow region, an iterator that iterates
   * over the entries in disk order (except if the region is an internal one
   * which will always return a hash map iterator).
   */
  public Iterator<RegionEntry> getBestLocalIterator(boolean includeValues,
      double reduceCacheFactor, boolean keepDiskMap) {
    if (includeValues && DiskEntryPage.DISK_PAGE_SIZE > 0) {
      final long cacheSize = getDiskIteratorCacheSize(reduceCacheFactor);
      if (cacheSize >= 0) {
        // if total region size is smaller then reduce the cache size
        return new DiskSavyIterator(this, adjustDiskIterCacheSize(
            cacheSize, getEstimatedLocalSize()), keepDiskMap);
      }
    }
    return this.entries.regionEntries().iterator();
  }

  /**
   * Get the best iterator for iterating over the contents of this local region.
   * This method will return either an iterator that uses hash ordering from the
   * entry map, or, in the case of an overflow region, an iterator that iterates
   * over the entries in disk order (except if the region is an internal one
   * which will always return a hash map iterator).
   */
  public Iterator<RegionEntry> getBestLocalIterator(boolean includeValues,
      final long cacheSize, boolean keepDiskMap) {
    if (includeValues && DiskEntryPage.DISK_PAGE_SIZE > 0
        && getDiskIteratorCacheSize(1.0) >= 0) {
      // if total region size is smaller then reduce the cache size
      return new DiskSavyIterator(this, cacheSize, keepDiskMap);
    }
    return this.entries.regionEntries().iterator();
  }

  /** a fast estimate of total number of entries locally in the region */
  public long getEstimatedLocalSize() {
    RegionMap rm;
    if (!this.isDestroyed) {
      long size;
      if (isHDFSReadWriteRegion() && this.initialized) {
        // this size is not used by HDFS region iterators
        // fixes bug 49239
        return 0;
      }
      // if region has not been initialized yet, then get the estimate from
      // disk region's recovery map if available
      if (!this.initialized && this.diskRegion != null
          && (rm = this.diskRegion.getRecoveredEntryMap()) != null
          && (size = rm.size()) > 0) {
        return size;
      }
      if ((rm = getRegionMap()) != null) {
        return rm.size();
      }
    }
    return 0;
  }

  /**
   * @param key
   * @param callbackArg
   * @param access
   *          true if caller wants last accessed time updated
   * @param allowTombstones whether an entry with a TOMBSTONE value can be returned
   * @return TODO
   */
  protected Region.Entry<?, ?> nonTXGetEntry(final Object key,
      final Object callbackArg, final boolean access, boolean allowTombstones) {
    final RegionEntry re = this.entries.getEntry(key);
    boolean miss = (re == null || re.isDestroyedOrRemoved());
    if (access) {
      updateStatsForGet(re, !miss);
    }
    if (re == null) {
      return null;
    }
    if (re.isTombstone()) {
      if (!allowTombstones) {
        return null;
      } // else return an entry (client GII / putAll results)
    } else if (miss) {
      return null;
    }

    Region.Entry ren = new NonTXEntry(re);
    //long start=0, end=0;
    //start = System.currentTimeMillis();
    //end = System.currentTimeMillis();
    //System.out.println("getEntry: " + (end-start));
    return ren;
  }

  /**
   * @return boolean
   */
  protected final boolean isClosed()
  {
    return this.cache.isClosed();
  }

  /**
   * Returns true if this region is or has been closed or destroyed.
   * Note that unlike {@link #isDestroyed()} this method will not
   * return true if the cache is closing but has not yet started closing
   * this region.
   */
  public boolean isThisRegionBeingClosedOrDestroyed() {
    return this.isDestroyed;
  }
  
  /** returns true if this region has been destroyed */
  public boolean isDestroyed() {
    if (!this.isDestroyed && !isClosed()) {
      return false;
    }
    LogWriterI18n log = getCache().getLoggerI18n();
    if (log.finestEnabled()) {
      log.finest("isDestroyed: true, this.isDestroyed: " + getFullPath());
    }
    return true;
    /*
    if (isClosed()) {
      return true; // for bug 42328
    }
    LogWriterI18n log = getCache().getLoggerI18n();
    boolean finestEnabled = log.finestEnabled();
    //    boolean result = false;
    if (this.isDestroyed) {
      if (finestEnabled) {
        log.finest("isDestroyed: true, this.isDestroyed: " + getFullPath());
      }
      return true;
    }
    //    if (!isInitialized()) { // don't return true if still initializing
    //      if (finestEnabled) {
    //        log.finest("isDestroyed: false, not initialized: " + getFullPath());
    //      }
    //      return false;
    //    }
    // @todo we could check parents here if we want this to be more accurate,
    // and the isDestroyed field could be made volatile as well.
    // if (this.parentRegion != null) return this.parentRegion.isDestroyed();
    if (finestEnabled) {
      log.finest("isDestroyed: false : " + getFullPath());
    }
    return false;
    */
  }

  /** a variant of subregions() that does not perform a readiness check */
  protected Set basicSubregions(boolean recursive) {
    return new SubregionsSet(recursive);
  }
  
  public Set subregions(boolean recursive) {
    checkReadiness();
    return new SubregionsSet(recursive);
  }

  public Set entries(boolean recursive) {
    checkReadiness();
    checkForNoAccess();
    return basicEntries(recursive);
  }

  /** Returns set of entries without performing validation checks. */
  public Set basicEntries(boolean recursive) {
    return new EntriesSet(this, recursive, IteratorType.ENTRIES, false);
  }

  /**
   * Flavor of keys that will not do repeatable read
   * @since 5.5
   */
  public Set testHookKeys()
  {
    checkReadiness();
    checkForNoAccess();
    return new EntriesSet(this, false, IteratorType.KEYS,
        false /* allowTombstones */);
  }

  public Set keys()
  {
    checkReadiness();
    checkForNoAccess();
    return new EntriesSet(this, false, IteratorType.KEYS, false);
  }

  /**
   * return a set of the keys in this region
   * @param allowTombstones whether destroyed entries should be included
   * @return the keys
   */
  public Set keySet(boolean allowTombstones) {
    checkReadiness();
    checkForNoAccess();
    return new EntriesSet(this, false, IteratorType.KEYS, allowTombstones);
  }

  public Collection values()
  {
    checkReadiness();
    checkForNoAccess();
    return new EntriesSet(this, false, IteratorType.VALUES, false);
  }

  public Object getUserAttribute()
  {
    return this.regionUserAttribute;
  }

  public void setUserAttribute(Object value)
  {
    checkReadiness();
    this.regionUserAttribute = value;
  }

  public final boolean containsKey(Object key) {
    try {
      operationStart();
    return containsKey(key, null);
    } finally {
      operationCompleted();
    }
  }

  public boolean containsKey(Object key, Object callbackArg) {
    checkReadiness();
    checkForNoAccess();
    return getDataView().containsKey(key, callbackArg, this);
  }

  public boolean containsTombstone(Object key)
  {
    checkReadiness();
    checkForNoAccess();
    if (!this.concurrencyChecksEnabled) {
      return false;
    } else {
      try {
        Entry entry = getDataView().getEntry(key, null, this, true);
        if (entry == null) {
          return false;
        } else {
          return (entry.getValue() == Token.TOMBSTONE);
        }
      } catch (EntryDestroyedException e) {
        return true;
      }
    }
  }

  /**
   * Check key present in region while acquiring a read lock on entry for
   * transaction. Used by non-transactional containsKey, TXState's containsKey,
   * and also by GemFireXD for transactional foreign key checks.
   */
  public boolean txContainsKey(final Object key, final Object callbackArg,
      final TXStateInterface tx, final boolean explicitReadOnlyLock) {
    checkReadiness();
    checkForNoAccess();
    if (explicitReadOnlyLock) {
      return getDataView(tx).containsKeyWithReadLock(key, callbackArg, this);
    }
    else {
      // fix for bug #40871 - concurrent RI causes containsKey for destroyed
      // entry to return true
      // [sumedh] code below also works fine for !imageState.isClient()
      // (is identical to that in RegionMap#containsKey())
      final RegionEntry re = this.entries.getEntry(key);
      if (re != null) {
        // for txns, acquire read lock if required
        if (tx != null) {
          Object val = tx.lockEntryForRead(re, key, this, 0, false, READ_TOKEN);
          return !Token.isRemoved(val);
        }
        else {
          return !re.isDestroyedOrRemoved();
        }
      }
      else {
        return false;
      }
    }
  }

  public boolean containsValueForKey(Object key) {
    try {
      operationStart();
    final TXStateInterface tx = discoverJTA();
    return getDataView(tx).containsValueForKey(key, null, this);
    } finally {
      operationCompleted();
    }
  }

  /**
   * @param key
   * @return TODO
   */
  protected boolean txContainsValueForKey(final Object key,
      final Object callbackArg, final TXStateInterface lockState) {
    checkReadiness();
    checkForNoAccess();
    if (this.diskRegion != null) {
      this.diskRegion.setClearCountReference();
    }
    try {
      final RegionEntry entry = this.entries.getEntry(key);
      if (entry != null) {
        @Retained Object val;
        if (lockState != null) {
          // for txns, acquire read lock if required
          val = lockState.lockEntryForRead(entry, key, this, 0, false,
              READ_VALUE_RETAIN);
          return containsValueInternal(val);
        }
        else {
          // no need to decompress since we only want to know
          // if we have an existing value
          SimpleMemoryAllocatorImpl.skipRefCountTracking();
          val = entry._getValueRetain(this, false);
          boolean result = containsValueInternal(val);
          SimpleMemoryAllocatorImpl.unskipRefCountTracking();
          return result;
        }
      }
      return false;
    }
    finally {
      if (this.diskRegion != null) {
        this.diskRegion.removeClearCountReference();
      }
    }
  }

  protected final boolean containsValueInternal(@Released Object val) {
    // updates received via CacheClientUpdater stores a CacheDeserializable
    // even for null values, hence do this check
    if (val instanceof StoredObject) {
      OffHeapHelper.release(val);
      return true;
    }
    // this works because INVALID and LOCAL_INVALID will never be faulted out of mem
    // If val is NOT_AVAILABLE that means we have a valid value on disk.
    return !Token.isInvalidOrRemoved(val);
  }

  /**
   * attributes for HDFS regions that hide eviction attributes.
   */
  private RegionAttributes largeRegionAttrs = null;

  public RegionAttributes getAttributes() {
    // to fix bug 35134 allow attribute access on closed regions
    //checkReadiness();
    return this;
  }

  public final String getName() {
    return this.regionName;
  }

  /**
   * Convenience method to get region name for logging/exception messages.
   * if this region is an instanceof bucket region, it returns the
   * bucket region name
   * @return name of the region or the owning partitioned region
   */
  public String getDisplayName() {
    if (this.isUsedForPartitionedRegionBucket()) {
      return this.getPartitionedRegion().getName();
    }
    return this.regionName;
  }
  
  /**
   * Returns the number of entries in this region. Note that because of the
   * concurrency properties of the {@link RegionMap}, the number of entries is
   * only an approximate. That is, other threads may change the number of
   * entries in this region while this method is being invoked.
   *
   * @see RegionMap#size
   *
   * author David Whitlock
   */
  public final int entryCount() {
    return entryCount(getTXState());
  }

  /**
   * Returns the number of entries in this region. Note that because of the
   * concurrency properties of the {@link RegionMap}, the number of entries is
   * only an approximate. That is, other threads may change the number of
   * entries in this region while this method is being invoked.
   *
   * @see RegionMap#size
   *
   * author David Whitlock
   */
  public final int entryCount(final TXStateInterface tx) {
    return entryCount(tx, null);
  }

  public int entryCount(final TXStateInterface tx, Set<Integer> buckets) {
    return entryCount(tx, buckets, false);
  }

  protected int entryCount(final TXStateInterface tx, Set<Integer> buckets, boolean estimate) {
    assert buckets == null: "unexpected buckets " + buckets + " for region "
        + toString();

    return getDataView(tx).entryCount(this);
  }

  public int entryCountEstimate(final TXStateInterface tx, Set<Integer> buckets, boolean entryCountEstimate) {
    return entryCount(tx, buckets, entryCountEstimate);
  }

  /**
   * @return size after considering imageState and TX uncommitted entries
   */
  protected int getRegionSize() {
    int result;
    final ReentrantLock regionLock = getSizeGuard();
    if (regionLock == null) {
      result = getRegionSizeNoLock();
    }
    else {
      regionLock.lock();
      try {
        result = getRegionSizeNoLock();
      } finally {
        regionLock.unlock();
      }
    }
    // decrement the uncommitted entries that have been created for TX locking
    // in case of HDFS, size operation the iterator doesn't consider
    // tx created entries anyway
    if(!includeHDFSResults()) {
      result -= this.txLockCreateCount.get();
    }
    
    if (result > 0) {
      return result;
    }
    else {
      return 0;
    }
  }

  private int getRegionSizeNoLock() {
    int result = getRegionMap().size();
    // if this is a client with no tombstones then we subtract the number
    // of entries being affected by register-interest refresh
    if (this.imageState.isClient() && !this.concurrencyChecksEnabled) {
      result -= this.imageState.getDestroyedEntriesCount();
    }
    if (includeHDFSResults()) {
      return result;
    }
    else {
      return result - this.tombstoneCount.get();
    }
  }

  /**
   * Returns the <code>DiskRegion</code> that this region uses to access data
   * on disk.
   *
   * @return <code>null</code> if disk regions are not being used
   *
   * @since 3.2
   */
  public final DiskRegion getDiskRegion() {
    return this.diskRegion;
  }

  public DiskRegionView getDiskRegionView() {
    return getDiskRegion();
  }
  
  public boolean isBackup() {
    return getDiskRegion().isBackup();
  }

  /**
   * Lets the customer do an explicit evict of a value to disk and removes the value
   * from memory. This was added for customer.
   */
  public void evictValue(Object key) {
    if (getDiskRegion() != null) {
      this.entries.evictValue(key);
    }
  }

  /**
   *
   * Initially called by EvictorThread.run
   *
   * @since 3.5.1
   */
  public void checkLRU()
  {
    if (this.entriesInitialized) {
      try {
        this.entries.lruUpdateCallback();
      }catch( DiskAccessException dae) {
        this.handleDiskAccessException(dae, true /* stop bridge servers*/);
        throw dae;
      }
    }
  }

  protected boolean isOverflowEnabled() {
    EvictionAttributes ea = getAttributes().getEvictionAttributes();
    return ea != null && ea.getAction().isOverflowToDisk();
  }
  
  public void writeToDisk()
  {
    if (this.diskRegion == null) {
      DataPolicy dp = getDataPolicy();
      if (dp == DataPolicy.EMPTY) {
        throw new IllegalStateException(LocalizedStrings.LocalRegion_CANNOT_WRITE_A_REGION_WITH_DATAPOLICY_0_TO_DISK.toLocalizedString(dp));
      }
      else if (!dp.withPersistence() && !isOverflowEnabled()) {
        throw new IllegalStateException(LocalizedStrings.LocalRegion_CANNOT_WRITE_A_REGION_THAT_IS_NOT_CONFIGURED_TO_ACCESS_DISKS.toLocalizedString());
      }
    }
    else {
      this.diskRegion.asynchForceFlush();
    }
  }

  /**
   * Used by tests to force everything out to disk.
   */
  public void forceFlush()
  {
    if (this.diskRegion != null) {
      this.diskRegion.flushForTesting();
    }
  }

  /**
   * This implementation only checks readiness and scope
   */
  public Lock getRegionDistributedLock() throws IllegalStateException
  {
    checkReadiness();
    checkForLimitedOrNoAccess();
    Scope theScope = getAttributes().getScope();
    Assert.assertTrue(theScope == Scope.LOCAL);
    throw new IllegalStateException(LocalizedStrings.LocalRegion_ONLY_SUPPORTED_FOR_GLOBAL_SCOPE_NOT_LOCAL.toLocalizedString());
  }

  /**
   * This implementation only checks readiness and scope
   */
  public Lock getDistributedLock(Object key) throws IllegalStateException
  {
    checkReadiness();
    checkForLimitedOrNoAccess();
    Scope theScope = getAttributes().getScope();
    Assert.assertTrue(theScope == Scope.LOCAL);
    throw new IllegalStateException(LocalizedStrings.LocalRegion_ONLY_SUPPORTED_FOR_GLOBAL_SCOPE_NOT_LOCAL.toLocalizedString());
  }

  public void invalidate(Object key, Object aCallbackArgument)
      throws TimeoutException, EntryNotFoundException
  {
    try{
      operationStart();
    checkReadiness();
    checkForLimitedOrNoAccess();
    validatedInvalidate(key, aCallbackArgument);
    } finally {
      operationCompleted();
    }
  }

  /**
   * Destroys entry without performing validations. Call this after validating
   * key, callback arg, and runtime state.
   */
  protected void validatedInvalidate(Object key, Object aCallbackArgument)
      throws TimeoutException, EntryNotFoundException
  {
    EntryEventImpl event = EntryEventImpl.create(
        this, Operation.INVALIDATE,
        key, null, aCallbackArgument, false, getMyId());
    try {
    if (generateEventID()) {
      event.setNewEventId(cache.getDistributedSystem());
    }
    basicInvalidate(event);
    } finally {
      event.release();
    }
  }

  public void localDestroy(Object key, Object aCallbackArgument)
      throws EntryNotFoundException
  {
    validateKey(key);
    checkReadiness();
    checkForNoAccess();
    EntryEventImpl event = EntryEventImpl.create(this, Operation.LOCAL_DESTROY,
        key, null, aCallbackArgument, false, getMyId());
    if (generateEventID()) {
      event.setNewEventId(cache.getDistributedSystem());
    }
    try {
      basicDestroy(event,
                   false,
                   null); // expectedOldValue
    }
    catch (CacheWriterException e) {
      // cache writer not called
      throw new Error(LocalizedStrings.LocalRegion_CACHE_WRITER_SHOULD_NOT_HAVE_BEEN_CALLED_FOR_LOCALDESTROY.toLocalizedString(), e);
    }
    catch (TimeoutException e) {
      // no distributed lock
      throw new Error(LocalizedStrings.LocalRegion_NO_DISTRIBUTED_LOCK_SHOULD_HAVE_BEEN_ATTEMPTED_FOR_LOCALDESTROY.toLocalizedString(), e);
    } finally {
      event.release();
    }
  }

  public void localDestroyRegion(Object aCallbackArgument)
  {
    getDataView().checkSupportsRegionDestroy();
    RegionEventImpl event = new RegionEventImpl(this,
        Operation.REGION_LOCAL_DESTROY, aCallbackArgument, false, getMyId(),
        generateEventID()/* generate EventID */);
    try {
      basicDestroyRegion(event, false);
    }
    catch (CacheWriterException e) {
      // not possible with local operation, CacheWriter not called
      throw new Error(LocalizedStrings.LocalRegion_CACHEWRITEREXCEPTION_SHOULD_NOT_BE_THROWN_IN_LOCALDESTROYREGION.toLocalizedString(), e);
    }
    catch (TimeoutException e) {
      // not possible with local operation, no distributed locks possible
      throw new Error(LocalizedStrings.LocalRegion_TIMEOUTEXCEPTION_SHOULD_NOT_BE_THROWN_IN_LOCALDESTROYREGION.toLocalizedString(), e);
    }
  }

  public final void close() {
    RegionEventImpl event = new RegionEventImpl(this, Operation.REGION_CLOSE,
        null, false, getMyId(), generateEventID()/* generate EventID */);
    close(event);
  }

  public void close(RegionEventImpl event) {
    try {
      // NOTE: the 422dynamicRegions branch added the callbackEvents argument
      // to basicDestroyRegion and inhibited events on region.close. This
      // clashed with the new SystemMemberCacheListener functionality in
      // 5.0, causing unit tests to fail
      basicDestroyRegion(event, false, true, true);
    }
    catch (CacheWriterException e) {
      // not possible with local operation, CacheWriter not called
      throw new Error(LocalizedStrings.LocalRegion_CACHEWRITEREXCEPTION_SHOULD_NOT_BE_THROWN_IN_LOCALDESTROYREGION.toLocalizedString(), e);
    }
    catch (TimeoutException e) {
      // not possible with local operation, no distributed locks possible
      throw new Error(LocalizedStrings.LocalRegion_TIMEOUTEXCEPTION_SHOULD_NOT_BE_THROWN_IN_LOCALDESTROYREGION.toLocalizedString(), e);
    }
  }

  public void localInvalidate(Object key, Object callbackArgument)
      throws EntryNotFoundException
  {
    validateKey(key);
    checkReadiness();
    checkForNoAccess();

    EntryEventImpl event = EntryEventImpl.create(
        this,
        Operation.LOCAL_INVALIDATE, key, null/* newValue */, callbackArgument,
        false, getMyId());
    try {
    operationStart();
    if (generateEventID()) {
      event.setNewEventId(cache.getDistributedSystem());
    }
    event.setLocalInvalid(true);
    basicInvalidate(event);
    } finally {
      event.release();
      operationCompleted();
    }
  }

  public void localInvalidateRegion(Object aCallbackArgument) {
    getDataView().checkSupportsRegionInvalidate();
    checkReadiness();
    checkForNoAccess();
    RegionEventImpl event = new RegionEventImpl(this,
        Operation.REGION_LOCAL_INVALIDATE, aCallbackArgument, false, getMyId());
    basicInvalidateRegion(event);
  }

  /**
   * Look up the LocalRegion with the specified full path.
   *
   * @param system
   *          the distributed system whose cache contains the root of interest
   * @return the LocalRegion or null if not found
   */
  public static LocalRegion getRegionFromPath(DistributedSystem system,
      String path)
  {
    Cache c = GemFireCacheImpl.getInstance();
    if(c==null) {
      return null;
    } else {
      return (LocalRegion)c.getRegion(path);
    }
  }

  //   public void dumpEntryMapStats(PrintStream out) {
  //     ((ConcurrentHashMap)this.entries).dumpStats(out);
  //   }

  public void preInitialize(InternalRegionArguments internalRegionArgs) {
 // if we're versioning entries we need a region-level version vector
    if (this.concurrencyChecksEnabled && this.versionVector == null) {
      createVersionVector();
    }
    
    DiskRegion dskRgn = getDiskRegion();
    
    if(dskRgn != null && dskRgn.isRecreated()) {
      getLogWriterI18n()
      .fine("DistributedRegion.getInitialImageAndRecovery: Starting Recovery");
      dskRgn.initializeOwner(this, internalRegionArgs); // do recovery
      getLogWriterI18n()
      .fine("DistributedRegion.getInitialImageAndRecovery: Finished Recovery");
    }
  }

  ////////////////// Protected Methods ////////////////////////////////////////

  /**
   * Do any extra initialization required. Region is already visible in parent's
   * subregion map. This method releases the initialization Latches, so
   * subclasses should call this super method last after performing additional
   * initialization.
   *
   * @param imageTarget
   *          ignored, used by subclass for get initial image
   * @param internalRegionArgs 
   * @see DistributedRegion#initialize(InputStream, InternalDistributedMember, InternalRegionArguments)
   */
  protected void initialize(InputStream snapshotInputStream,
      InternalDistributedMember imageTarget,
      InternalRegionArguments internalRegionArgs) throws TimeoutException,
      IOException, ClassNotFoundException {
    LogWriterI18n logger = getCache().getLoggerI18n();
    if (!isInternalRegion()) {
      // Subclasses may have already called this method, but this is
      // acceptable because addResourceListener won't add it twice
      if (!this.isDestroyed) {
        cache.getResourceManager().addResourceListener(ResourceType.MEMORY, this);
      }
    }

    // if not local, then recovery happens in InitialImageOperation
    if (this.scope.isLocal() && this.diskRegion != null) {
      try {
        this.diskRegion.finishInitializeOwner(this, GIIStatus.NO_GII);
        { // This block was added so that early recovery could figure out that
          // this data needs to be recovered from disk. Local regions used to
          // not bother assigning a memberId but that is what the early recovery
          // code uses to figure out that a region needs to be recovered.
          PersistentMemberID oldId = this.diskRegion.getMyInitializingID();
          if (oldId == null) {
            oldId = this.diskRegion.getMyPersistentID();
          }
          if (oldId == null) {
            PersistentMemberID newId = this.diskRegion.generatePersistentID();
            this.diskRegion.setInitializing(newId);
            this.diskRegion.setInitialized();
          }
        }
      }catch(DiskAccessException dae) {
        releaseAfterRegionCreateEventLatch();
        this.handleDiskAccessException(dae, 
            false /* Do stop bridge servers*/, true);
        throw dae;
      }
    }

    // make sure latches are released if they haven't been by now already
    releaseBeforeGetInitialImageLatch();
    if (snapshotInputStream != null && this.scope.isLocal()) {
      try {
        loadSnapshotDuringInitialization(snapshotInputStream);
      }catch(DiskAccessException dae) {
        releaseAfterRegionCreateEventLatch();
        this.handleDiskAccessException(dae, false /* Do not stop bridge servers*/);
        throw dae;
      }
    }
    releaseAfterGetInitialImageLatch();
    if (this.uuidAdvisor != null) {
      this.uuidAdvisor.postInitialize();
    }
    if (logger.fineEnabled()) {
      logger.fine("Calling addExpiryTasks for " + this);
    }
    // these calls can throw RegionDestroyedException if this region is
    // destroyed
    // at this point
    try {
      addIdleExpiryTask();
      addTTLExpiryTask();
      if (isEntryExpiryPossible()) {
        rescheduleEntryExpiryTasks(); // called after gii to fix bug 35214
      }
      this.accountRegionOverhead();
      initialized();
    }
    catch (RegionDestroyedException e) {
      // whether it is this region or a parent region that is destroyed,
      // then so must we be
      Assert.assertTrue(isDestroyed());
      // just proceed, a destroyed region will be returned to caller
    }
  }
  
  protected void createOQLIndexes(InternalRegionArguments internalRegionArgs) {
    
    if (internalRegionArgs == null || internalRegionArgs.getIndexes() == null){
      return;
    }
    LogWriterI18n logger = getSystem().getLogWriter().convertToLogWriterI18n();
    if (logger.fineEnabled()){
      logger.fine("LocalRegion.createOQLIndexes on region " + this.getFullPath());
    }

    List oqlIndexes = internalRegionArgs.getIndexes();

    if (this.indexManager == null) {
      this.indexManager = IndexUtils.getIndexManager(this, true);
    }
    int initLevel = 0;
    try {
      // Release the initialization latch for index creation.
      initLevel = LocalRegion.setThreadInitLevelRequirement(ANY_INIT);
      for (Object o : oqlIndexes) {
        IndexCreationData icd = (IndexCreationData)o;
        try {
          if (icd.getPartitionedIndex() != null) {
            ExecutionContext externalContext = new ExecutionContext(null, this.cache);
            if (internalRegionArgs.getPartitionedRegion() != null) {
              externalContext.setBucketRegion(internalRegionArgs.getPartitionedRegion(), (BucketRegion)this);
            }
            if (logger.fineEnabled()){
              logger.fine("IndexManager Index creation process for " + icd.getIndexName());
            }

            this.indexManager.createIndex(icd.getIndexName(), icd.getIndexType(), 
                icd.getIndexExpression(), icd.getIndexFromClause(), 
                icd.getIndexImportString(), externalContext, icd.getPartitionedIndex());           
          } else {
            if (logger.fineEnabled()){
              logger.fine("QueryService Index creation process for " + icd.getIndexName());
            }
            QueryService qs = this.getGemFireCache().getLocalQueryService();
            String fromClause = (icd.getIndexType() == IndexType.FUNCTIONAL || icd.getIndexType() == IndexType.HASH)? icd.getIndexFromClause() : this.getFullPath();
            qs.createIndex(icd.getIndexName(), icd.getIndexType(), icd.getIndexExpression(), fromClause, icd.getIndexImportString());
          }

        } catch (Exception ex) {
          
          if (logger.fineEnabled()) {
            logger.fine("Failed to create index " + icd.getIndexName() + 
             " on region " + this.getFullPath() + ex.getMessage());
          }
          // Check if the region index creation is from cache.xml, in that case throw exception.
          // Other case is when bucket regions are created dynamically, in that case ignore the exception.
          if (internalRegionArgs.getDeclarativeIndexCreation()) {
            InternalGemFireError err = new InternalGemFireError(LocalizedStrings.GemFireCache_INDEX_CREATION_EXCEPTION_1.toLocalizedString(new Object[] {icd.getIndexName(), this.getFullPath()}) );
            err.initCause(ex);
            throw err;
          }
        }
      }
    } finally {
      // Reset the initialization lock.
      LocalRegion.setThreadInitLevelRequirement(initLevel);
    }
  }

  /**
   * The region is now fully initialized, as far as LocalRegion is concerned
   */
  public void initialized() {
    // does nothing in LocalRegion at this time
  }

  protected void releaseLatches()
  {
    releaseBeforeGetInitialImageLatch();
    releaseAfterGetInitialImageLatch();
    releaseAfterRegionCreateEventLatch();
  }

  protected void releaseBeforeGetInitialImageLatch()
  {
    LogWriterI18n logger = getCache().getLoggerI18n();
    if (logger.fineEnabled()) {
      logger.fine("Releasing Initialization Latch (before initial image) for "
          + getFullPath());
    }
    releaseLatch(this.initializationLatchBeforeGetInitialImage);
  }

  protected final void releaseAfterGetInitialImageLatch() {
    LogWriterI18n logger = getCache().getLoggerI18n();
    if (logger.fineEnabled()) {
      logger.fine("Releasing Initialization Latch (after initial image) for "
          + getFullPath());
    }
    releaseLatch(this.initializationLatchAfterGetInitialImage);
  }

  /**
   * Called after we have delivered our REGION_CREATE event.
   *
   * @since 5.0
   */
  private void releaseAfterRegionCreateEventLatch()
  {
    releaseLatch(this.afterRegionCreateEventLatch);
  }

  /**
   * Used to cause cache listener events to wait until the after region create
   * event is delivered.
   *
   * @since 5.0
   */
  private void waitForRegionCreateEvent()
  {
    StoppableCountDownLatch l = this.afterRegionCreateEventLatch;
    if (l != null && l.getCount() == 0) {
      return;
    }
    waitOnInitialization(l);
  }

  private static void releaseLatch(StoppableCountDownLatch latch)
  {
    if (latch == null)
      return;
    latch.countDown();
  }

  /**
   * Removes entries and recursively destroys subregions.
   *
   * @param eventSet
   *          collects the events for all destroyed regions if null, then we're
   *          closing so don't send events to callbacks or destroy the disk
   *          region
   */
  private void recursiveDestroyRegion(Set eventSet, RegionEventImpl p_event,
      boolean cacheWrite) throws CacheWriterException, TimeoutException
  {
    RegionEventImpl event = p_event;
    final boolean isClose = event.getOperation().isClose();
    // do the cacheWriter beforeRegionDestroy first to fix bug 47736
    if (eventSet != null && cacheWrite) {
      try {
        cacheWriteBeforeRegionDestroy(event);
      }
      catch (CancelException e) {
        // I don't think this should ever happens:  bulletproofing for bug 39454
        if (!cache.forcedDisconnect()) {
          cache.getLoggerI18n().warning(
            LocalizedStrings.LocalRegion_RECURSIVEDESTROYREGION_PROBLEM_IN_CACHEWRITEBEFOREREGIONDESTROY, e);
        }
      }
    }

    if (this.eventTracker != null) {
      this.eventTracker.stop();
    }
    if (RegionVersionVector.DEBUG && getVersionVector() != null) {
      getLogWriterI18n().fine("version vector for " + getName() + " is " + getVersionVector().fullToString());
    }
    cancelTTLExpiryTask();
    cancelIdleExpiryTask();
    cancelAllEntryExpiryTasks();
    cancelEvictorService();
    if (!isInternalRegion()) {
      getCachePerfStats().incRegions(-1);
    }
    cache.getResourceManager(false).removeResourceListener(this);
    if (getMembershipAttributes().hasRequiredRoles()) {
      if (!isInternalRegion()) {
        getCachePerfStats().incReliableRegions(-1);
      }
    }

    // Note we need to do this even if we don't have a listener
    // because of the SystemMemberCacheEventProcessor. Once we have
    // a way to check for existence of SystemMemberCacheEventProcessor listeners
    // then the add only needs to be done if hasListener || hasAdminListener
    if (eventSet != null) { // && hasListener())
      eventSet.add(event);
    }

    try {
      // call recursiveDestroyRegion on each subregion and remove it
      // from this subregion map
      Collection values = this.subregions.values();
      for (Iterator itr = values.iterator(); itr.hasNext();) {
        Object element = itr.next(); // element is a LocalRegion
        LocalRegion rgn;
        try {
          LocalRegion.setThreadInitLevelRequirement(LocalRegion.BEFORE_INITIAL_IMAGE);
          try {
            rgn = toRegion(element); // converts to a LocalRegion
          } finally {
            LocalRegion.setThreadInitLevelRequirement(LocalRegion.AFTER_INITIAL_IMAGE);
          }
        }
        catch (CancelException e) {
          rgn = (LocalRegion)element; // ignore, keep going through the motions though
        }
        catch (RegionDestroyedException rde) {
          // SharedRegionData was destroyed
          continue;
        }

        // if the region is destroyed, then it is a race condition with
        // failed initialization removing it from the parent subregion map
        if (rgn.isDestroyed) {
          continue;
        }
        /** ** BEGIN operating on subregion of this region (rgn) *** */
        if (eventSet != null) {
          event = (RegionEventImpl)event.clone();
          event.region = rgn;
        }

        try {
          rgn.recursiveDestroyRegion(eventSet, event, cacheWrite);
          if (!rgn.isInternalRegion()) {
            InternalDistributedSystem system = rgn.cache.getDistributedSystem();
            system.handleResourceEvent(ResourceEvent.REGION_REMOVE, rgn);
          }
        }
        catch (CancelException e) {
          // I don't think this should ever happen:  bulletproofing for bug 39454
          if (!cache.forcedDisconnect()) {
            cache.getLoggerI18n().warning(
                LocalizedStrings.LocalRegion_RECURSIVEDESTROYREGION_RECURSION_FAILED_DUE_TO_CACHE_CLOSURE_REGION_0,
                rgn.getFullPath(), e);
          }
        }
        itr.remove(); // remove from this subregion map;
        /** ** END operating on subregion of this region *** */
      } // for

      try {
        if (this.indexManager != null) {
          try {
            this.indexManager.destroy();
          }
          catch (QueryException e) {
            throw new IndexMaintenanceException(e);
          }
        }
      }
      catch (CancelException e) {
        // I don't think this should ever happens:  bulletproofing for bug 39454
        if (!cache.forcedDisconnect()) {
          cache.getLoggerI18n().warning(
              LocalizedStrings.LocalRegion_BASICDESTROYREGION_INDEX_REMOVAL_FAILED_DUE_TO_CACHE_CLOSURE_REGION_0,
              getFullPath(), e);
        }
      }
    }
    finally {
      // mark this region as destroyed.
      if (event.isReinitializing()) {
        this.reinitialized_old = true;
      }
      this.cache.setRegionByPath(getFullPath(), null);
      
      // close the UUID advisor
      if (this.uuidAdvisor != null) {
        this.uuidAdvisor.close(p_event);
      }

      if (this.eventTracker != null) {
        this.eventTracker.stop();
      }

      if (this.diskRegion != null) {
        this.diskRegion.prepareForClose(this);
      }
      boolean giiIndexLockAcquired = this.doPreEntryDestructionCleanup(false, event, true);
      try {
        closeEntries();
      } finally {
        this.doPostEntryDestructionCleanup(false, event, true, giiIndexLockAcquired);
      }
      LogWriterI18n logger = getCache().getLoggerI18n();
      if (logger.fineEnabled()) {
        logger.fine("recursiveDestroyRegion: Region Destroyed: "
            + getFullPath());
      }

      // if eventSet is null then we need to close the listener as well
      // otherwise, the listener will be closed after the destroy event
      try {
        postDestroyRegion(!isClose, event);
      }
      catch (CancelException e) {
        cache.getLoggerI18n().warning(
            LocalizedStrings.LocalRegion_RECURSIVEDESTROYREGION_POSTDESTROYREGION_FAILED_DUE_TO_CACHE_CLOSURE_REGION_0,
            getFullPath(), e);
      }

      // Destroy cqs created aganist this Region in a server cache.
      // fix for bug #47061
      if (getServerProxy() == null) {
        closeCqs();
      }

      detachPool();
      
      if (eventSet != null) {
        closeCallbacksExceptListener();
      }
      else {
        closeAllCallbacks();
      }
      if (this.concurrencyChecksEnabled && this.dataPolicy.withReplication() && !this.cache.isClosed()) {
        this.cache.getTombstoneService().unscheduleTombstones(this);
      }
      if (this.hasOwnStats) {
        this.cachePerfStats.close();
      }
    }
  }
  
  public void closeEntries() {
    isOffHeapThreadLocal.set(Boolean.valueOf(this.enableOffHeapMemory));
    this.entries.close();
  }
  public Set<VersionSource> clearEntries(RegionVersionVector rvv) {
    isOffHeapThreadLocal.set(Boolean.valueOf(this.enableOffHeapMemory));
    return this.entries.clear(rvv);
  }

  @Override
  public void checkReadiness()
  {
    checkRegionDestroyed(true);
  }

  /**
   * This method should be called when the caller cannot locate an entry and that condition
   * is unexpected.  This will first double check the cache and region state before throwing
   * an EntryNotFoundException.  EntryNotFoundException should be a last resort exception.
   * 
   * @param entryKey the missing entry's key.
   */
  public void checkEntryNotFound(Object entryKey) {
    checkReadiness();
    // Localized string for partitioned region is generic enough for general use
    throw new EntryNotFoundException(LocalizedStrings.PartitionedRegion_ENTRY_NOT_FOUND_FOR_KEY_0.toLocalizedString(entryKey));    
  }
  
  /**

   * Search for the value in a server (if one exists),
   * then try a loader.
   * 
   * If we find a value, we put it in the cache.
   * @param preferCD return the CacheDeserializable, if that's what the value is.
   * @param requestingClient the client making the request, if any
   * @param clientEvent the client's event, if any.  If not null, we set the version tag
   * @param returnTombstones TODO
   * @return the deserialized value
   * @see DistributedRegion#findObjectInSystem(KeyInfo, boolean,
   *      TXStateInterface, boolean, Object, boolean, boolean,
   *      ClientProxyMembershipID, EntryEventImpl, boolean, boolean)
   */
  protected Object findObjectInSystem(KeyInfo keyInfo, boolean isCreate,
      final TXStateInterface lockState, boolean generateCallbacks,
      Object localValue, boolean disableCopyOnRead, boolean preferCD,
      ClientProxyMembershipID requestingClient, EntryEventImpl clientEvent,
      boolean returnTombstones, boolean allowReadFromHDFS) throws CacheLoaderException, TimeoutException {
    return findObjectInLocalSystem(keyInfo.getKey(), keyInfo.getCallbackArg(),
        isCreate, lockState, generateCallbacks, localValue, clientEvent);
  }

  protected final Object findObjectInLocalSystem(final Object key,
      final Object aCallbackArgument, boolean isCreate, TXStateInterface tx,
      boolean generateCallbacks, Object localValue, EntryEventImpl clientEvent)
      throws CacheLoaderException, TimeoutException {

    Object value = null;
    boolean fromServer = false;
    EntryEventImpl holder = null;

    /*
     * First lets try the server
     */
    {
      ServerRegionProxy mySRP = getServerProxy();
      if (mySRP != null) {
        holder = EntryEventImpl.createVersionTagHolder();
        try {
        value = mySRP.get(key, aCallbackArgument, holder);
        fromServer = value != null;
        } finally {
          holder.release();
        }
      }
    }

    /*
     * If we didn't get anything from the server, try the loader
     */
    if (!fromServer) {
      // copy into local var to prevent race condition
      CacheLoader loader = basicGetLoader();
      if (loader != null) {
        final LoaderHelper loaderHelper
          = loaderHelperFactory.createLoaderHelper(key, aCallbackArgument,
                                                   false /* netSearchAllowed */,
                                                   true  /* netloadAllowed */,
                                                   null  /* searcher */);
        CachePerfStats stats = getCachePerfStats();
        long statStart = stats.startLoad();
        try {
          value = loader.load(loaderHelper);
        }
        finally {
          stats.endLoad(statStart);
        }
      }
    }

    /*
     * If we got a value back, let's put it in the cache.
     */
    RegionEntry re = null;
    TXStateProxy txProxy = null;
    if (value != null && !isMemoryThresholdReachedForLoad()) {

      long startPut = CachePerfStats.getStatTime();
      validateKey(key);
      Operation op;
      if (isCreate) {
        op = Operation.LOCAL_LOAD_CREATE;
      }
      else {
        op = Operation.LOCAL_LOAD_UPDATE;
      }

      EntryEventImpl event
        = EntryEventImpl.create(this, op, key, value, aCallbackArgument,
                             false, getMyId(), generateCallbacks);
      try {

      // bug #47716 - do not put an invalid entry into the cache if there's
      // already one there with the same version
      if (fromServer) {
        if (alreadyInvalid(key, event)) {
          return null;
        }
        event.setFromServer(fromServer);
        event.setVersionTag(holder.getVersionTag());
        if (clientEvent != null) {
          clientEvent.setVersionTag(holder.getVersionTag());
        }
      }
      
      //set the event id so that we can progagate 
      //the value to the server
      if (!fromServer) {
        event.setNewEventId(cache.getDistributedSystem());
      }
      if (tx == null) {
        tx = getTXState();
      }
      // if we have to do a put then need to use proxy
      if (tx != null) {
        txProxy = tx.getProxy();
        event.setOriginRemote(!txProxy.isCoordinator());
      }
      event.setTXState(txProxy);
      try {
        try {
          re = basicPutEntry(event, 0L);
          if (!fromServer && clientEvent != null) {
            clientEvent.setVersionTag(event.getVersionTag());
            clientEvent.isConcurrencyConflict(event.isConcurrencyConflict());
          }
          if (fromServer && (event.getRawNewValue() == Token.TOMBSTONE)) {
            return null; // tombstones are destroyed entries
          }
        } catch (ConcurrentCacheModificationException e) {
          // this means the value attempted to overwrite a newer modification and was rejected
          if (getLogWriterI18n().fineEnabled()) {
            getLogWriterI18n().fine("caught concurrent modification attempt when applying " + event);
          }
          notifyBridgeClients(event);
        }
        if (txProxy == null) {
          getCachePerfStats().endPut(startPut, event.isOriginRemote());
        }
        else {
          event.setTXState(tx);
        }
      }
      catch (CacheWriterException cwe) {
        getCache().getLoggerI18n().fine("findObjectInSystem: writer exception putting entry "
                                    + event
                                    + " : " + cwe);
      }
      } finally {     
          event.release();        
      }
    }
    if (isCreate) {
      recordMiss(txProxy, re, key);
    }
    return value;
  }

  protected boolean isMemoryThresholdReachedForLoad() {
    return this.memoryThresholdReached.get();
  }
  
  /**
   * Returns true if the cache already has this key as an invalid entry
   * with a version >= the one in the given event.  This is used in
   * cache-miss processing to avoid overwriting the entry when it is
   * not necessary, so that we avoid invoking cache listeners.
   * 
   * @param key
   * @param event
   * @return whether the entry is already invalid
   */
  protected boolean alreadyInvalid(Object key, EntryEventImpl event) {
    @Unretained(ENTRY_EVENT_NEW_VALUE)
    Object newValue = event.getRawNewValue();
    if (newValue == null || Token.isInvalid(newValue)) {
      RegionEntry entry = this.entries.getEntry(key);
      if (entry != null) {
        synchronized(entry) {
          if (entry.isInvalid()) {
            VersionStamp stamp = entry.getVersionStamp();
            if (stamp == null || event.getVersionTag() == null) {
              return true;
            }
            if (stamp.getEntryVersion() >= event.getVersionTag().getEntryVersion()) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * @return true if cacheWrite was performed
   * @see DistributedRegion#cacheWriteBeforeDestroy(EntryEventImpl, Object)
   */
  boolean cacheWriteBeforeDestroy(EntryEventImpl event, Object expectedOldValue)
      throws CacheWriterException, EntryNotFoundException, TimeoutException
  {
    boolean result = false;
    // copy into local var to prevent race condition
    CacheWriter writer = basicGetWriter();
    if (writer != null && event.getOperation() != Operation.REMOVE && 
        !event.inhibitAllNotifications()) {
      final long start = getCachePerfStats().startCacheWriterCall();
      try {
        writer.beforeDestroy(event);
      }
      finally {
        getCachePerfStats().endCacheWriterCall(start);
      }
      result = true;
    }
    serverDestroy(event, expectedOldValue);
    return result;
  }

  /** @return true if this was a client region; false if not */
  protected boolean bridgeWriteBeforeDestroy(EntryEventImpl event, Object expectedOldValue)
      throws CacheWriterException, EntryNotFoundException, TimeoutException
  {
    if (hasServerProxy()) {
      serverDestroy(event, expectedOldValue);
      return true;
    } else {
      return false;
    }
  }

  /**
   * @since 5.7
   */
  protected void serverRegionDestroy(RegionEventImpl regionEvent) {
    if (regionEvent.getOperation().isDistributed()) {
      ServerRegionProxy mySRP = getServerProxy();
      if (mySRP != null) {
        EventID eventId = regionEvent.getEventId();
        Object callbackArg = regionEvent.getRawCallbackArgument();
        mySRP.destroyRegion(eventId, callbackArg);
      }
    }
  }

  /**
   * @since 5.7
   */
  protected void serverRegionClear(RegionEventImpl regionEvent) {
    if (regionEvent.getOperation().isDistributed()) {
      ServerRegionProxy mySRP = getServerProxy();
      if (mySRP != null) {
        EventID eventId = regionEvent.getEventId();
        Object callbackArg = regionEvent.getRawCallbackArgument();
        mySRP.clear(eventId, callbackArg);
      }
    }
  }
  /**
   * @since 5.7
   */
  protected void serverRegionInvalidate(RegionEventImpl regionEvent) {
    if (regionEvent.getOperation().isDistributed()) {
      ServerRegionProxy mySRP = getServerProxy();
      if (mySRP != null) {
        // @todo grid: add a client to server Op message for this
      }
    }
  }

  /**
   * @since 5.7
   */
  protected void serverInvalidate(EntryEventImpl event, boolean invokeCallbacks, 
      boolean forceNewEntry) {
    if (event.getOperation().isDistributed()) {
      ServerRegionProxy mySRP = getServerProxy();
      if (mySRP != null) {
        mySRP.invalidate(event);
      }
    }
  }

  /**
   * @since 5.7
   */
  protected void serverPut(EntryEventImpl event,
      boolean requireOldValue, Object expectedOldValue) {
    if (event.getOperation().isDistributed() && !event.isFromServer()) {
      ServerRegionProxy mySRP = getServerProxy();
      if (mySRP != null) {
        Operation op = event.getOperation();
        if (op == Operation.PUTALL_CREATE || op == Operation.PUTALL_UPDATE) {
          return;
        } 
        // @todo grid: is the newEntry flag needed?
        Object key = event.getKey();
        Object value = event.getRawNewValue();
        // serverPut is called by cacheWriteBeforePut so the new value will not yet be off-heap
        // TODO OFFHEAP: verify that the above assertion is true
        Object callbackArg = event.getRawCallbackArgument();
        boolean isCreate = event.isCreate(); 
        Object result = mySRP.put(key, value, event.getDeltaBytes(), event,
            op, requireOldValue, expectedOldValue,
            callbackArg, isCreate);
        // bug #42296, serverproxy returns null when cache is closing
        getCancelCriterion().checkCancelInProgress(null);
//        if (getLogWriterI18n().fineEnabled()) {
//          getLogWriterI18n().fine("serverPut: op.guaranteesOldValue()=" + op.guaranteesOldValue()
//              +"; op=" + op + "; result=" + result);
//        }
        // if concurrent map operations failed we don't want the region map
        // to apply the operation and need to throw an exception
        if (op.guaranteesOldValue()) {
          if (op != Operation.REPLACE || requireOldValue) {
            event.setConcurrentMapOldValue(result);
          }
          if (op == Operation.PUT_IF_ABSENT) {
            if (result != null) {
              throw new EntryNotFoundException("entry existed for putIfAbsent"); // customers don't see this exception
            }
          } else if (op == Operation.REPLACE) {
            if (requireOldValue && result == null) {
              throw new EntryNotFoundException("entry not found for replace");
            } else if (!requireOldValue) {
              if ( !((Boolean)result).booleanValue() ) {
                throw new EntryNotFoundException("entry found with wrong value"); // customers don't see this exception
              }
            }
          }
        }
      }
    }
  }

  /**
   * Destroy an entry on the server given its event.
   * @since 5.7
   */
  protected void serverDestroy(EntryEventImpl event, Object expectedOldValue) {
    if (event.getOperation().isDistributed()) {
      ServerRegionProxy mySRP = getServerProxy();
      if (mySRP != null) {
        // send to server
        Object key = event.getKey();
        Object callbackArg = event.getRawCallbackArgument();
        Object result = mySRP.destroy(key, expectedOldValue, event.getOperation(), event, callbackArg);
        if (result instanceof EntryNotFoundException) {
          throw (EntryNotFoundException)result;
      }
    }
  }
  }
  
  /**
   * @return true if cacheWrite was performed
   * @see DistributedRegion#cacheWriteBeforeRegionDestroy(RegionEventImpl)
   */
  boolean cacheWriteBeforeRegionDestroy(RegionEventImpl event)
      throws CacheWriterException, TimeoutException
  {
    boolean result = false;
    // copy into local var to prevent race condition
    CacheWriter writer = basicGetWriter();
    if (writer != null) {
      final long start = getCachePerfStats().startCacheWriterCall();
      try {
        writer.beforeRegionDestroy(event);
      }
      finally {
        getCachePerfStats().endCacheWriterCall(start);
      }
      result = true;
    }
    serverRegionDestroy(event);
    return result;
  }

  protected boolean cacheWriteBeforeRegionClear(RegionEventImpl event)
      throws CacheWriterException, TimeoutException
  {
    boolean result = false;
    // copy into local var to prevent race condition
    CacheWriter writer = basicGetWriter();
    if (writer != null) {
      final long start = getCachePerfStats().startCacheWriterCall();
      try {
        writer.beforeRegionClear(event);
      }
      finally {
        getCachePerfStats().endCacheWriterCall(start);
      }
      result = true;
    }
    serverRegionClear(event);
    return result;
  }

  /**
   * @since 5.7
   */
  void cacheWriteBeforeInvalidate(EntryEventImpl event, boolean invokeCallbacks, boolean forceNewEntry) {
    if (!event.getOperation().isLocal() && !event.isOriginRemote()) {
      serverInvalidate(event, invokeCallbacks, forceNewEntry);
    }
  }

  /**
   * @see DistributedRegion#cacheWriteBeforePut(EntryEventImpl, Set, CacheWriter, boolean, Object)
   * @param event
   * @param netWriteRecipients
   * @param localWriter
   * @param requireOldValue
   * @param expectedOldValue 
   * @throws CacheWriterException
   * @throws TimeoutException
   */
   void cacheWriteBeforePut(EntryEventImpl event, Set netWriteRecipients,
      CacheWriter localWriter, 
      boolean requireOldValue,
      Object expectedOldValue)
      throws CacheWriterException, TimeoutException
  {
    Assert.assertTrue(netWriteRecipients == null);
    Operation op = event.getOperation();
    if (!(op == Operation.PUT_IF_ABSENT
          || op == Operation.REPLACE) && (localWriter != null) && 
          !((EntryEventImpl)event).inhibitAllNotifications()) {
      final long start = getCachePerfStats().startCacheWriterCall();
      final boolean newEntry = event.getOperation().isCreate();
      try {
        if (!newEntry) {
          localWriter.beforeUpdate(event);
        }
        else {
          localWriter.beforeCreate(event);
        }
      }
      finally {
        getCachePerfStats().endCacheWriterCall(start);
      }
    }
    serverPut(event, requireOldValue, expectedOldValue);
  }

  protected final void validateArguments(Object key, Object value,
      Object aCallbackArgument) {
    validateKey(key);
    validateValue(value);
    validateCallbackArg(aCallbackArgument);
  }

  protected final void validateKey(Object key)
  {
    if (key == null) {
      throw new NullPointerException(LocalizedStrings.LocalRegion_KEY_CANNOT_BE_NULL.toLocalizedString());
    }

    // check validity of key against keyConstraint
    if (this.keyConstraint != null) {
      if (!this.keyConstraint.isInstance(key))
        throw new ClassCastException(LocalizedStrings.LocalRegion_KEY_0_DOES_NOT_SATISFY_KEYCONSTRAINT_1.toLocalizedString(new Object[] {key.getClass().getName(), this.keyConstraint.getName()}));
    }

    // We don't need to check that the key is Serializable. Instead,
    // we let the lower-level (data) serialization mechanism take care
    // of this for us. See bug 32394.
  }

  /**
   * Starting in 3.5, we don't check to see if the callback argument is
   * <code>Serializable</code>. We instead rely on the actual serialization
   * (which happens in-thread with the put) to tell us if there are any
   * problems.
   */
  protected final void validateCallbackArg(Object aCallbackArgument)
  {

  }

  /**
   * @since 5.0.2
   */
  private final boolean DO_EXPENSIVE_VALIDATIONS = Boolean.getBoolean("gemfire.DO_EXPENSIVE_VALIDATIONS");

  /**
   * the number of tombstone entries in the RegionMap
   */
  protected AtomicInteger tombstoneCount = new AtomicInteger();

  /** a boolean for issuing a client/server configuration mismatch message */
  private boolean concurrencyMessageIssued;

  /**
   * Starting in 3.5, we don't check to see if the value is
   * <code>Serializable</code>. We instead rely on the actual serialization
   * (which happens in-thread with the put) to tell us if there are any
   * problems.
   */
  protected final void validateValue(Object value) {
    // check validity of value against valueConstraint
    if (this.valueConstraint != null) {
      if (value != null) {
        if (value instanceof CachedDeserializable) {
          if (!DO_EXPENSIVE_VALIDATIONS) {
            return;
          }
          value = ((CachedDeserializable)value)
              .getDeserializedValue(null, null);
        }
        if (!this.valueConstraint.isInstance(value)) {
          throw new ClassCastException(LocalizedStrings
              .LocalRegion_VALUE_0_DOES_NOT_SATISFY_VALUECONSTRAINT_1
                  .toLocalizedString(new Object[] { value.getClass().getName(),
                      this.valueConstraint.getName() }));
        }
      }
    }
  }

  public CachePerfStats getCachePerfStats() {
    // return this.cache.getCachePerfStats();
    return this.cachePerfStats;
  }

  public CachePerfStats getRegionPerfStats() {
    return this.cachePerfStats;
  }

  /** regions track the number of tombstones their map holds for size calculations */
  public void incTombstoneCount(int delta) {
//    LogWriterI18n log = this.cache.getLoggerI18n();
//    log.info(LocalizedStrings.DEBUG, "incTombstoneCount invoked with " + delta, new Exception("Stack Trace"));
    this.tombstoneCount.addAndGet(delta);
    this.cachePerfStats.incTombstoneCount(delta);
    
    //Fix for 45204 - don't include the tombstones in
    //any of our entry count stats.
    this.cachePerfStats.incEntryCount(-delta);
    if(getDiskRegion() != null) {
      getDiskRegion().incNumEntriesInVM(-delta);
    }
    DiskEntry.Helper.incrementBucketStats(this, -delta/*InVM*/, 0/*OnDisk*/, 0);
  }
  
  public int getTombstoneCount() {
    return this.tombstoneCount.get();
  }
  
  public void scheduleTombstone(RegionEntry entry, VersionTag destroyedVersion) {
    Object sync = TombstoneService.DEBUG_TOMBSTONE_COUNT? TombstoneService.debugSync : new Object();
    if (destroyedVersion == null) {
      throw new NullPointerException("destroyed version tag cannot be null");
    }
    //    lastUnscheduled.set(null);
    synchronized(sync) {
      LogWriterI18n log = getLogWriterI18n();
      incTombstoneCount(1);
//      if (entry instanceof AbstractRegionEntry) {
//        AbstractRegionEntry are = (AbstractRegionEntry)entry;
//        if (are.isTombstoneScheduled()) {
//          log.severe(LocalizedStrings.DEBUG, "Scheduling a tombstone for an entry that is already a tombstone: " + entry, new Exception("stack trace"));
//          throw new IllegalStateException("Attempt to schedule a tombstone for a destroyed entry that is already scheduled for expiration");
//        }
//        are.setTombstoneScheduled(true);
//      }
      if (log.fineEnabled() || TombstoneService.VERBOSE || TombstoneService.DEBUG_TOMBSTONE_COUNT) {
        log.info(LocalizedStrings.DEBUG, "scheduling tombstone in " + getName() + " for " + entry.getKeyCopy()
            + " version=" + entry.getVersionStamp().asVersionTag()
            + " entry hash is 0x" + Integer.toHexString(System.identityHashCode(entry))
            + " count is " + this.tombstoneCount.get()
            + " entryMap size is " + this.entries.sizeInVM()/*, new Exception("stack trace")*/);
        // this can be useful for debugging tombstone count problems if there aren't a lot of concurrent threads
        if (TombstoneService.DEBUG_TOMBSTONE_COUNT && this.entries instanceof AbstractRegionMap) {
          ((AbstractRegionMap)this.entries).verifyTombstoneCount(tombstoneCount);
        }
      }
      getGemFireCache().getTombstoneService().scheduleTombstone(this, entry,
          destroyedVersion);
    }
  }

//  ThreadLocal<RegionEntry> lastUnscheduled = new ThreadLocal<RegionEntry>();
//  ThreadLocal<Exception> lastUnscheduledPlace = new ThreadLocal<Exception>();
  
  public void rescheduleTombstone(RegionEntry entry, VersionTag version) {
    LogWriterI18n log = getLogWriterI18n();
    if (log.fineEnabled() || TombstoneService.VERBOSE || TombstoneService.DEBUG_TOMBSTONE_COUNT) {
      log.info(LocalizedStrings.DEBUG, "rescheduling tombstone in " + getName() + " for " + entry.getKeyCopy());
    }
    Object sync = TombstoneService.DEBUG_TOMBSTONE_COUNT? TombstoneService.debugSync : new Object();
    synchronized(sync) {
      unscheduleTombstone(entry, false); // count is off by one, so don't allow validation to take place
      scheduleTombstone(entry, version);
    }

  }
  
  public void unscheduleTombstone(RegionEntry entry) {
    unscheduleTombstone(entry, true);
  }
  
  public void unscheduleTombstone(RegionEntry entry, boolean validateCount) {
    LogWriterI18n log = getLogWriterI18n();
//    if (lastUnscheduled.get() == entry) {
//      log.severe(LocalizedStrings.DEBUG,"unscheduling tombstone that was just unscheduled", new Exception("Second unschedule"));
//      log.severe(LocalizedStrings.DEBUG,"previous unschedule point", lastUnscheduledPlace.get());
//    }
//    lastUnscheduled.set(entry);
//    lastUnscheduledPlace.set(new Exception("first unschedule from thread '" + Thread.currentThread().getName()+"'"));
    
//    if (entry instanceof AbstractRegionEntry) {
//      AbstractRegionEntry are = (AbstractRegionEntry)entry;
//      if (!are.isTombstoneScheduled()) {
//        log.severe(LocalizedStrings.DEBUG, "Unscheduling a tombstone for an entry that is not marked as having been scheduled: " + are, new Exception("stack trace"));
//        throw new IllegalStateException("Attempt to unschedule expiration of a destroyed entry that has not been scheduled for expiration");
//      }
//      are.setTombstoneScheduled(false);
//    }
    incTombstoneCount(-1);
    if (log.fineEnabled() || TombstoneService.VERBOSE) {
      log.info(LocalizedStrings.DEBUG,
          "unscheduling tombstone in " + getName() + " for " + entry.getKeyCopy()
          + " entry hash is 0x" + Integer.toHexString(System.identityHashCode(entry))
          + " count is " + this.tombstoneCount.get()
          + " entryMap size is " + this.entries.sizeInVM()/*, new Exception("stack trace")*/);
    }
    getRegionMap().unscheduleTombstone(entry);
    if (TombstoneService.DEBUG_TOMBSTONE_COUNT && validateCount) {
      if (this.entries instanceof AbstractRegionMap) {
        ((AbstractRegionMap) this.entries).verifyTombstoneCount(this.tombstoneCount);
      }
    }
    // we don't have to remove the entry from the sweeper since the version has
    // changed.  It would be costly to iterate over the tombstone list for
    // every tombstone exhumed while holding the entry's lock
    //this.cache.getTombstoneService().unscheduleTombstone(entry);
  }
  
  /** remove any tombstones from the given member that are <= the given version 
   * @param eventID event identifier for the GC operation
   * @param clientRouting routing info (if null a routing is computed)
   */
  public void expireTombstones(Map<VersionSource, Long> regionGCVersions, EventID eventID, FilterInfo clientRouting) {
    Set<Object> keys = null;
    if (!this.concurrencyChecksEnabled) {
      return;
    }
    if (!this.versionVector.containsTombstoneGCVersions(regionGCVersions)) {
      keys = this.cache.getTombstoneService().gcTombstones(this, regionGCVersions);
      if (keys == null) {
        // deltaGII prevented tombstone GC
        return;
      }
    }
    if (eventID != null) { // bug #50683 - old members might not send an eventID
      notifyClientsOfTombstoneGC(regionGCVersions, keys, eventID, clientRouting);
    }
  }
  
  public void expireTombstoneKeys(Set<Object> tombstoneKeys) {
    if (this.concurrencyChecksEnabled) {
      this.cache.getTombstoneService().gcTombstoneKeys(this, tombstoneKeys);
    }
  }
  

  /** pass tombstone garbage-collection info to clients 
   * @param eventID the ID of the event (see bug #50683)
   * @param routing routing info (routing is computed if this is null)
   */
  protected void notifyClientsOfTombstoneGC(Map<VersionSource, Long> regionGCVersions, Set<Object>keysRemoved, EventID eventID, FilterInfo routing) {
    if (CacheClientNotifier.getInstance() != null) {
      // Only route the event to clients interested in the partitioned region.
      // We do this by constructing a region-level event and then use it to
      // have the filter profile ferret out all of the clients that have interest
      // in this region
      FilterProfile fp = getFilterProfile();
      if (fp != null || routing != null) { // null check - fix for bug #45614
        RegionEventImpl regionEvent = new RegionEventImpl(this, Operation.REGION_DESTROY, null, true, getMyId());
        regionEvent.setEventID(eventID);
        FilterInfo clientRouting = routing;
        if (clientRouting == null) {
          clientRouting = fp.getLocalFilterRouting(regionEvent);
        }
        regionEvent.setLocalFilterInfo(clientRouting); 
        ClientUpdateMessage clientMessage = ClientTombstoneMessage.gc(this, regionGCVersions,
            eventID);
        CacheClientNotifier.notifyClients(regionEvent, clientMessage); 
      }
    }
  }
  
  
  /** local regions do not perform versioning */
  protected boolean shouldGenerateVersionTag(RegionEntry entry, EntryEventImpl event) {
    // sjigyasu: Not a very clean way to make GemFireXD-specific decision. TODO:Review
    final GemFireCacheImpl.StaticSystemCallbacks sysCb =
        GemFireCacheImpl.FactoryStatics.systemCallbacks;
    if (sysCb != null) {
      return this.concurrencyChecksEnabled && entry.getVersionStamp().hasValidVersion();
    } else {
      if (this.getDataPolicy().withPersistence()) {
        return true;
      } else {
        return this.concurrencyChecksEnabled && (entry.getVersionStamp().hasValidVersion() || this.dataPolicy.withReplication());
      }
    }
  }

  protected boolean allowConcurrencyChecksOverride() {
    // Concurrency checks for GemFireXD are enabled from top-level and not
    // on-the-fly
    final GemFireCacheImpl.StaticSystemCallbacks sysCb =
        GemFireCacheImpl.FactoryStatics.systemCallbacks;
    return (sysCb == null || sysCb.allowConcurrencyChecksOverride(this));
  }

  protected void enableConcurrencyChecks() {
    if (!allowConcurrencyChecksOverride()) {
      return;
    }
    this.concurrencyChecksEnabled = true;
    if (this.dataPolicy.withStorage()) {
      RegionEntryFactory versionedEntryFactory = this.entries.getEntryFactory().makeVersioned();
      Assert.assertTrue(this.entries.sizeInVM() == 0, "RegionMap should be empty but was of size:"+this.entries.sizeInVM());
      this.entries.setEntryFactory(versionedEntryFactory);
      createVersionVector();
    }
  }

  /**
   * validate attributes of subregion being created, sent to parent
   *
   * @throws IllegalArgumentException
   *           if attrs is null
   * @throws IllegalStateException
   *           if attributes are invalid
   */
  protected void validateSubregionAttributes(RegionAttributes attrs)
  {
    if (attrs == null) {
      throw new IllegalArgumentException(LocalizedStrings.LocalRegion_REGION_ATTRIBUTES_MUST_NOT_BE_NULL.toLocalizedString());
    }
    if (this.scope == Scope.LOCAL && attrs.getScope() != Scope.LOCAL) {
      throw new IllegalStateException(LocalizedStrings.LocalRegion_A_REGION_WITH_SCOPELOCAL_CAN_ONLY_HAVE_SUBREGIONS_WITH_SCOPELOCAL.toLocalizedString());
    }
  }

  /**
   * Returns the value of the entry with the given key as it is stored in the
   * VM. This means that if the value is invalid, the invalid token will be
   * returned. If the value is a {@link CachedDeserializable}received from
   * another VM, that object will be returned. If the value does not reside in
   * the VM because it has been overflowed to disk, <code>null</code> will be
   * returned. This method is intended for testing.testing purposes only.
   *
   * @throws EntryNotFoundException
   *           No entry with <code>key</code> exists
   *
   * @see RegionMap#getEntry
   *
   * @since 3.2
   */
  public Object getValueInVM(Object key) throws EntryNotFoundException {
    return basicGetValueInVM(key, null, getTXState());
  }

  public Object getValueInVM(EntryEventImpl event)
      throws EntryNotFoundException {
    return basicGetValueInVM(event.getKey(), event.getCallbackArgument(),
        event.getTXState(this));
  }

  /**
   * @since 5.5
   */
  private Object basicGetValueInVM(final Object key, final Object callbackArg,
      final TXStateInterface tx) throws EntryNotFoundException {
    return getDataView(tx).getValueInVM(key, callbackArg, this);
  }

  /**
   * @param key
   * @param callbackArg
   * @return TODO
   */
  @Retained
  protected Object nonTXbasicGetValueInVM(Object key, Object callbackArg) {
    RegionEntry re = this.entries.getEntry(key);
    if (re == null) {
      checkEntryNotFound(key);
    }
    Object v = re.getValueInVM(this); // OFFHEAP returned to callers
    if (Token.isRemoved(v)) {
      checkEntryNotFound(key);
    }
    if (v == Token.NOT_AVAILABLE) {
      return null;
    }
    return v;
  }

  /**
   * This is a test hook method used to find out what keys the current tx has
   * read or written.
   * 
   * @return an unmodifiable set of keys that have been read or written by the
   *         transaction on this thread.
   * @throws IllegalStateException
   *           if not tx in progress
   * @since 5.5
   */
  public Set testHookTXKeys() {
    final TXStateInterface tx = getTXState();
    if (tx == null) {
      throw new IllegalStateException(
          LocalizedStrings.LocalRegion_TX_NOT_IN_PROGRESS.toLocalizedString());
    }
    TXRegionState txr = txReadRegion(tx);
    if (txr == null) {
      return Collections.EMPTY_SET;
    }
    else {
      return txr.getEntryKeys();
    }
  }

  /**
   * Returns the value of the entry with the given key as it is stored on disk.
   * While the value may be read from disk, it is <b>not </b> stored into the
   * entry in the VM. This method is intended for testing purposes only.
   * DO NOT use in product code else it will break GemFireXD that has cases
   * where routing object is not part of only the key.
   *
   * @throws EntryNotFoundException
   *           No entry with <code>key</code> exists
   * @throws IllegalStateException
   *           If this region does not write to disk
   *
   * @see RegionEntry#getValueOnDisk
   *
   * @since 3.2
   */
  public Object getValueOnDisk(Object key) throws EntryNotFoundException
  {
    // Ok for this to ignore tx state
    RegionEntry re = this.entries.getEntry(key);
    if (re == null) {
      throw new EntryNotFoundException(key.toString());
    }
    return re.getValueOnDisk(this);
  }

  /**
   * Get the serialized bytes from disk. This method only looks for the value on
   * the disk, ignoring heap data. This method is intended for testing purposes
   * only. DO NOT use in product code else it will break GemFireXD that has
   * cases where routing object is not part of only the key.
   * 
   * @param key the object whose hashCode is used to find the value
   * @return either a byte array, a CacheDeserializable with the serialized value,
   * or null if the entry exists but no value data exists.
   * @throws IllegalStateException when the region is not persistent
   * @throws EntryNotFoundException if there is no entry for the given key
   * @since gemfire5.7_hotfix
   */
  public Object getSerializedValueOnDisk(Object key) throws EntryNotFoundException
  {
    // Ok for this to ignore tx state
    RegionEntry re = this.entries.getEntry(key);
    if (re == null) {
      throw new EntryNotFoundException(key.toString());
    }
    Object result = re.getSerializedValueOnDisk(this);
    if (Token.isInvalid(result)) {
      result = null;
    } else if (Token.isRemoved(result)) {
      throw new EntryNotFoundException(key.toString());
    }
    return result;
  }

  /**
   * Returns the value of the entry with the given key as it is stored present
   * in the buffer or disk. While the value may be read from disk or buffer,
   * it is <b>not</b>
   * stored into the entry in the VM.  This is different from  getValueonDisk in that
   * it checks for a value both in asynch buffers ( subject to asynch mode
   * enabled) as well as Disk
   *
   * @throws EntryNotFoundException
   *         No entry with <code>key</code> exists
   * @throws IllegalStateException
   *         If this region does not write to disk
   *
   * @see RegionEntry#getValueOnDisk
   *
   * @since 5.1
   */
  public Object getValueOnDiskOrBuffer(Object key)
  throws EntryNotFoundException {
    // Ok for this to ignore tx state
    RegionEntry re = this.entries.getEntry(key);
    if (re == null) {
      throw new EntryNotFoundException(key.toString());
    }
    return re.getValueOnDiskOrBuffer(this);
  }
  
  /**
   * Does a get that attempts to not fault values in from disk or make the entry
   * the most recent in the LRU.
   * 
   * Originally implemented in WAN gateway code and moved here in the gemfirexd
   * "cheetah" branch.
   * @param adamant fault in and affect LRU as a last resort
   * @param allowTombstone also return Token.TOMBSTONE if the entry is deleted
   * @param serializedFormOkay if the serialized form can be returned
   */
  public Object getNoLRU(Object k, boolean adamant, boolean allowTombstone, boolean serializedFormOkay) {
    Object o = null;
    try {
      o = getValueInVM(k); // OFFHEAP deserialize
      if (o == null) {
        // must be on disk
        // fault it in w/o putting it back in the region
        o = getValueOnDiskOrBuffer(k);
        if (o == null) {
          // try memory one more time in case it was already faulted back in
          o = getValueInVM(k); // OFFHEAP deserialize
          if (o == null) {
            if (adamant) {
              o = get(k);
            }
          } else {
            if (!serializedFormOkay && (o instanceof CachedDeserializable)) {
              o = ((CachedDeserializable)o).getDeserializedValue(this,
                  getRegionEntry(k));
            }
          }
        }
      } else {
        if (!serializedFormOkay && (o instanceof CachedDeserializable)) {
          o = ((CachedDeserializable)o).getDeserializedValue(this,
              getRegionEntry(k));
        }
      }
    } catch (EntryNotFoundException ok) {
      // just return null;
    }
    if (o == Token.TOMBSTONE && !allowTombstone) {
      o = null;
    }
    return o;
  }

  /**
   * Bump this number any time an incompatible change is made to the snapshot
   * format.
   */
  private static final byte SNAPSHOT_VERSION = 1;

  private static final byte SNAPSHOT_VALUE_OBJ = 23;

  private static final byte SNAPSHOT_VALUE_INVALID = 24;

  private static final byte SNAPSHOT_VALUE_LOCAL_INVALID = 25;

  public void saveSnapshot(OutputStream outputStream) throws IOException
  {
    if (isProxy()) {
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_REGIONS_WITH_DATAPOLICY_0_DO_NOT_SUPPORT_SAVESNAPSHOT.toLocalizedString(getDataPolicy()));
    }
    checkForNoAccess();
    DataOutputStream out = new DataOutputStream(outputStream);
    try {
      out.writeByte(SNAPSHOT_VERSION);
      for (Iterator itr = entries(false).iterator(); itr.hasNext();) {
        Region.Entry entry = (Region.Entry)itr.next();
        try {
          Object key = entry.getKey();
          Object value = entry.getValue();
          if (value == Token.TOMBSTONE) {
            continue;
          }
          DataSerializer.writeObject(key, out);
          if (value == null) { // fix for bug 33311
            LocalRegion.NonTXEntry lre = (LocalRegion.NonTXEntry)entry;
            RegionEntry re = lre.getRegionEntry();
            value = re.getValue(this); // OFFHEAP: incrc, copy info heap cd for serialization, decrc
            if (value == Token.INVALID) {
              out.writeByte(SNAPSHOT_VALUE_INVALID);
            }
            else if (value == Token.LOCAL_INVALID) {
              out.writeByte(SNAPSHOT_VALUE_LOCAL_INVALID);
            }
            else {
              out.writeByte(SNAPSHOT_VALUE_OBJ);
              DataSerializer.writeObject(value, out);
            }
          }
          else {
            out.writeByte(SNAPSHOT_VALUE_OBJ);
            DataSerializer.writeObject(value, out);
          }
        }
        catch (EntryDestroyedException e) {
          // continue to next entry
        }
      }
      // write NULL terminator
      DataSerializer.writeObject(null, out);
    }
    finally {
      out.close();
    }
  }

  public void loadSnapshot4ConvertTo65(InputStream inputStream) throws CacheWriterException, TimeoutException, ClassNotFoundException, IOException {
    isConversion.set(Boolean.valueOf(true));
    try {
      loadSnapshot(inputStream);
    } finally {
      isConversion.remove();
    }
  }
  
  public void loadSnapshot(InputStream inputStream)
      throws CacheWriterException, TimeoutException, ClassNotFoundException,
      IOException
  {
    if (isProxy()) {
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_REGIONS_WITH_DATAPOLICY_0_DO_NOT_SUPPORT_LOADSNAPSHOT.toLocalizedString(getDataPolicy()));
    }
    if (inputStream == null) {
      throw new NullPointerException(LocalizedStrings.LocalRegion_INPUTSTREAM_MUST_NOT_BE_NULL.toLocalizedString());
    }
    checkReadiness();
    checkForLimitedOrNoAccess();
    RegionEventImpl event = new RegionEventImpl(this,
        Operation.REGION_LOAD_SNAPSHOT, null, false, getMyId(),
        generateEventID()/* generate EventID */);
    reinitialize(inputStream, event);
  }

//   public void createRegionOnServer() throws CacheWriterException
//   {
//     if (basicGetWriter() instanceof BridgeWriter) {
//       if (getParentRegion() != null) {
//         BridgeWriter bw = (BridgeWriter)basicGetWriter();
//         bw.createRegionOnServer(getParentRegion().getFullPath(), getName());
//       }
//       else {
//        throw new CacheWriterException(LocalizedStrings.LocalRegion_REGION_0_IS_A_ROOT_REGION_ONLY_NONROOT_REGIONS_CAN_BE_CREATED_ON_THE_SERVER.toLocalizedString(getFullPath()));
//       }
//     }
//     else {
//      throw new CacheWriterException(LocalizedStrings.LocalRegion_SERVER_REGION_CREATION_IS_ONLY_SUPPORTED_ON_CLIENT_SERVER_TOPOLOGIES_THE_CURRENT_CACHEWRITER_IS_0.toLocalizedString(this.cacheWriter));
//     }
//   }

  public void registerInterest(Object key)
  {
    registerInterest(key, false);
  }

  public void registerInterest(Object key, boolean isDurable) {
    registerInterest(key, isDurable, true);
  }
  
  public void registerInterest(Object key, boolean isDurable,
      boolean receiveValues)
  {
    registerInterest(key, InterestResultPolicy.DEFAULT, isDurable, receiveValues);
  }

  public void startRegisterInterest() {
    getImageState().writeLockRI();
    try {
      cache.registerInterestStarted();
      this.riCnt++;
    } finally {
      getImageState().writeUnlockRI();
    }
  }

  public void finishRegisterInterest() {
    if (Boolean.getBoolean("gemfire.testing.slow-interest-recovery")) {
      getLogWriterI18n().fine("slowing interest recovery...");
      try { Thread.sleep(20000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      getLogWriterI18n().fine("done slowing interest recovery");
    }
    boolean gotLock = false;
    try {
      getImageState().writeLockRI();
      gotLock = true;
      this.riCnt--;
      Assert.assertTrue(this.riCnt >= 0 , "register interest count can not be < 0 ");
      if (this.riCnt == 0) {
        // remove any destroyed entries from the region and clear the hashset
        destroyEntriesAndClearDestroyedKeysSet();
      }
    } finally {
      cache.registerInterestCompleted();
      if (gotLock) {
        getImageState().writeUnlockRI();
      }
    }
  }

  // TODO this is distressingly similar to code in the client.internal package
  private void processSingleInterest(Object key, int interestType,
      InterestResultPolicy pol, boolean isDurable,
      boolean receiveUpdatesAsInvalidates)
  {
    final ServerRegionProxy proxy = getServerProxy();
    if (proxy == null) {
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_INTEREST_REGISTRATION_REQUIRES_A_POOL.toLocalizedString());
    }
    if (isDurable && !proxy.getPool().isDurableClient()) {
      throw new IllegalStateException(LocalizedStrings.LocalRegion_DURABLE_FLAG_ONLY_APPLICABLE_FOR_DURABLE_CLIENTS.toLocalizedString());
    }
    if (!proxy.getPool().getSubscriptionEnabled()) {
      if (proxy.getPool() instanceof BridgePoolImpl) {
        String msg = "Interest registration requires establishCallbackConnection to be set to true.";
        throw new BridgeWriterException(msg);
      } else {
        String msg = "Interest registration requires a pool whose queue is enabled.";
        throw new SubscriptionNotEnabledException(msg);
      }
    }

    if (getAttributes().getDataPolicy().withReplication() // fix for bug 36185
        && !getAttributes().getScope().isLocal()) { // fix for bug 37692
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_INTEREST_REGISTRATION_NOT_SUPPORTED_ON_REPLICATED_REGIONS.toLocalizedString());
    }

    if (key == null)
      throw new IllegalArgumentException(LocalizedStrings.LocalRegion_INTEREST_KEY_MUST_NOT_BE_NULL.toLocalizedString());
    // Sequence of events, on a single entry:
    // 1. Client puts value (a).
    // 2. Server updates with value (b). Client never gets the update,
    //     because it isn't interested in that key.
    // 3. Client registers interest.
    // At this point, there is an entry in the local cache, but it is
    // inconsistent with the server.
    //
    // Because of this, we must _always_ destroy and refetch affected values
    // during registerInterest.
    startRegisterInterest();
    try {
      List serverKeys;
    
      this.clearKeysOfInterest(key, interestType, pol);
      // Checking for the Dunit test(testRegisterInterst_Destroy_Concurrent) flag
      if (PoolImpl.BEFORE_REGISTER_CALLBACK_FLAG) {
        BridgeObserver bo = BridgeObserverHolder.getInstance();
        bo.beforeInterestRegistration();
      }// Test Code Ends
      final byte regionDataPolicy = getAttributes().getDataPolicy().ordinal;
      switch (interestType) {
      case InterestType.FILTER_CLASS:
        serverKeys = proxy.registerInterest(key, interestType, pol,
              isDurable, receiveUpdatesAsInvalidates, regionDataPolicy);
        break;
      case InterestType.KEY:
        
        if (key instanceof String && key.equals("ALL_KEYS")) {
          
          serverKeys = proxy.registerInterest(".*",
                                              InterestType.REGULAR_EXPRESSION,
                                              pol,
                                              isDurable,
                                              receiveUpdatesAsInvalidates,
                                              regionDataPolicy);
        }
        else {
          if (key instanceof List) {
            serverKeys = proxy.registerInterestList((List)key, pol,
                  isDurable, receiveUpdatesAsInvalidates, regionDataPolicy);
          } else {
            serverKeys = proxy.registerInterest(key, InterestType.KEY, pol,
                  isDurable, receiveUpdatesAsInvalidates, regionDataPolicy);
          }
        }
        break;
      case InterestType.OQL_QUERY:
        serverKeys = proxy.registerInterest(key, InterestType.OQL_QUERY, pol,
              isDurable, receiveUpdatesAsInvalidates, regionDataPolicy);
        break;
      case InterestType.REGULAR_EXPRESSION: {
        String regex = (String)key;
        // compile regex throws java.util.regex.PatternSyntaxException if invalid
        // we do this before sending to the server because it's more efficient
        // and the client is not receiving exception messages properly
        Pattern.compile(regex);
        serverKeys = proxy.registerInterest(regex,
                                            InterestType.REGULAR_EXPRESSION,
                                            pol,
                                            isDurable,
                                            receiveUpdatesAsInvalidates,
                                            regionDataPolicy);
        break;
      }
      default:
        throw new InternalGemFireError(LocalizedStrings.LocalRegion_UNKNOWN_INTEREST_TYPE.toLocalizedString());
    }
    boolean finishedRefresh = false;
    try {
      refreshEntriesFromServerKeys(null, serverKeys, pol);
      
      finishedRefresh = true;      
    }
    finally {
      if (!finishedRefresh) {
        // unregister before throwing the exception caused by the refresh
        switch (interestType) {
          case InterestType.FILTER_CLASS:
            proxy.unregisterInterest(key, interestType, false, false);
            break;
          case InterestType.KEY:
            if (key instanceof String && key.equals("ALL_KEYS")) {
              proxy.unregisterInterest(".*", InterestType.REGULAR_EXPRESSION, false, false);
            }
            else if (key instanceof List) {
              proxy.unregisterInterestList((List)key, false, false);
            } else {
              proxy.unregisterInterest(key, InterestType.KEY, false, false);
            }
            break;
          case InterestType.OQL_QUERY:
            proxy.unregisterInterest(key, InterestType.OQL_QUERY, false, false);
            break;
          case InterestType.REGULAR_EXPRESSION: {
            proxy.unregisterInterest(key, InterestType.REGULAR_EXPRESSION, false, false);
            break;
          }
          default:
            throw new InternalGemFireError(LocalizedStrings.LocalRegion_UNKNOWN_INTEREST_TYPE.toLocalizedString());
          }
        }
      }
    }
    finally {
      finishRegisterInterest();
    }
  }

  public void registerInterest(Object key, InterestResultPolicy policy)
  {
    registerInterest(key, policy, false);
  }
  public void registerInterest(Object key, InterestResultPolicy policy,
      boolean isDurable) {
    registerInterest(key, policy, isDurable, true);
  }
  public void registerInterest(Object key, InterestResultPolicy policy,
      boolean isDurable, boolean receiveValues)
  {
    processSingleInterest(key, InterestType.KEY, policy, isDurable, !receiveValues);
  }

  public void registerInterestRegex(String regex)
  {
    registerInterestRegex(regex, false);
  }

  public void registerInterestRegex(String regex, boolean isDurable) {
    registerInterestRegex(regex, InterestResultPolicy.DEFAULT, isDurable, true);
  }
  public void registerInterestRegex(String regex, boolean isDurable,
      boolean receiveValues)
  {
    registerInterestRegex(regex, InterestResultPolicy.DEFAULT, isDurable, receiveValues);
  }
  public void registerInterestRegex(String regex, InterestResultPolicy policy)
  {
    registerInterestRegex(regex, policy, false);
  }

  public void registerInterestRegex(String regex, InterestResultPolicy policy,
      boolean isDurable) {
    registerInterestRegex(regex, policy, isDurable, true);
  }
  public void registerInterestRegex(String regex, InterestResultPolicy policy,
      boolean isDurable, boolean receiveValues)
  {
    processSingleInterest(regex, InterestType.REGULAR_EXPRESSION, policy,
        isDurable, !receiveValues);
  }
  public void registerInterestFilter(String className)
  {
    registerInterestFilter(className, false);
  }
  public void registerInterestFilter(String className, boolean isDurable) {
    registerInterestFilter(className, isDurable, true);
  }
  public void registerInterestFilter(String className, boolean isDurable,
      boolean receiveValues)
  {
    processSingleInterest(className, InterestType.FILTER_CLASS,
        InterestResultPolicy.DEFAULT, isDurable, !receiveValues);
  }
  public void registerInterestOQL(String query)
  {
    registerInterestOQL(query, false);
  }
  public void registerInterestOQL(String query, boolean isDurable) {
    registerInterestOQL(query, isDurable, true);
  }
  public void registerInterestOQL(String query, boolean isDurable,
      boolean receiveValues)
  {
    processSingleInterest(query, InterestType.OQL_QUERY,
        InterestResultPolicy.DEFAULT, isDurable, !receiveValues);
  }

  public void unregisterInterest(Object key)
  {
    ServerRegionProxy proxy = getServerProxy();
    if (proxy != null) {
      // Keep support for "ALL_KEYS" in 4.2.x
      if (key instanceof String && key.equals("ALL_KEYS")) {
        proxy.unregisterInterest(".*", InterestType.REGULAR_EXPRESSION, false, false);
      }
      else if (key instanceof List) {
        proxy.unregisterInterestList((List)key, false, false);
      }
      else {
        proxy.unregisterInterest(key, InterestType.KEY, false, false);
      }
    }
    else {
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_INTEREST_UNREGISTRATION_REQUIRES_A_POOL.toLocalizedString());
    }
  }

  public void unregisterInterestRegex(String regex)
  {
    ServerRegionProxy proxy = getServerProxy();
    if (proxy != null) {
      proxy.unregisterInterest(regex, InterestType.REGULAR_EXPRESSION, false, false);
    }
    else {
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_INTEREST_UNREGISTRATION_REQUIRES_A_POOL.toLocalizedString());
    }
  }

  public void unregisterInterestFilter(String className) {
    ServerRegionProxy proxy = getServerProxy();
    if (proxy != null) {
      proxy.unregisterInterest(className, InterestType.FILTER_CLASS, false, false);
    }
    else {
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_INTEREST_UNREGISTRATION_REQUIRES_A_POOL.toLocalizedString());
    }
  }

  public void unregisterInterestOQL(String query)
  {
    ServerRegionProxy proxy = getServerProxy();
    if (proxy != null) {
      proxy.unregisterInterest(query, InterestType.OQL_QUERY, false, false);
    }
    else {
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_INTEREST_UNREGISTRATION_REQUIRES_A_POOL.toLocalizedString());
    }
  }

  public List getInterestList()
  {
    ServerRegionProxy proxy = getServerProxy();
    if (proxy != null) {
      return proxy.getInterestList(InterestType.KEY);
    }
    else {
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_INTEREST_UNREGISTRATION_REQUIRES_A_POOL.toLocalizedString());
    }
  }

  /** finds the keys in this region using the given interestType and argument.  Currently only
   * InterestType.REGULAR_EXPRESSION and InterestType.KEY are supported
   *
   * @param interestType an InterestType value
   * @param interestArg the associated argument (regex string, key or key list, etc)
   * @param allowTombstones whether to return destroyed entries
   * @return a set of the keys matching the given criterion
   */
  public Set getKeysWithInterest(int interestType, Object interestArg, boolean allowTombstones)
  {
    Set ret = null;
    if (interestType == InterestType.REGULAR_EXPRESSION) {
      if (interestArg == null || ".*".equals(interestArg)) {
        ret = new HashSet(keySet(allowTombstones));
      }
      else {
        ret = new HashSet();
        // Handle the regex pattern
        if (!(interestArg instanceof String)) {
          throw new IllegalArgumentException(LocalizedStrings.AbstractRegion_REGULAR_EXPRESSION_ARGUMENT_WAS_NOT_A_STRING.toLocalizedString());
        }
        Pattern keyPattern = Pattern.compile((String)interestArg);
        for (Iterator it = this.keySet(allowTombstones).iterator(); it.hasNext();) {
          Object entryKey = it.next();
          if(!(entryKey instanceof String)) {
            //key is not a String, cannot apply regex to this entry
            continue;
          }
          if(!keyPattern.matcher((String) entryKey).matches()) {
            //key does not match the regex, this entry should not be returned.
            continue;
          }
          ret.add(entryKey);
        }
      }
    }
    else if (interestType == InterestType.KEY) {
      if (interestArg instanceof List) {
        ret = new HashSet();  // TODO optimize initial size
        List keyList = (List)interestArg;
        for (Iterator it = keyList.iterator(); it.hasNext();) {
          Object entryKey = it.next();
          if (this.containsKey(entryKey) || (allowTombstones && this.containsTombstone(entryKey))) {
            ret.add(entryKey);
          }
        }
      }
      else {
        ret = new HashSet();
        if (this.containsKey(interestArg) || (allowTombstones && this.containsTombstone(interestArg))) {
          ret.add(interestArg);
        }
      }
    }
    else if (interestType == InterestType.FILTER_CLASS) {
      throw new UnsupportedOperationException(LocalizedStrings.AbstractRegion_INTERESTTYPEFILTER_CLASS_NOT_YET_SUPPORTED.toLocalizedString());
    }
    else if (interestType == InterestType.OQL_QUERY) {
      throw new UnsupportedOperationException(LocalizedStrings.AbstractRegion_INTERESTTYPEOQL_QUERY_NOT_YET_SUPPORTED.toLocalizedString());
    }
    else {
      throw new IllegalArgumentException(LocalizedStrings.AbstractRegion_UNSUPPORTED_INTEREST_TYPE_0.toLocalizedString(Integer.valueOf(interestType)));
    }
    return ret;
  }

  public List getInterestListRegex()
  {
    ServerRegionProxy proxy = getServerProxy();
    if (proxy != null) {
      return proxy.getInterestList(InterestType.REGULAR_EXPRESSION);
    }
    else {
      throw new UnsupportedOperationException( LocalizedStrings.LocalRegion_INTEREST_LIST_RETRIEVAL_REQUIRES_A_POOL.toLocalizedString());
    }
  }

  public List getInterestListFilters()
  {
    ServerRegionProxy proxy = getServerProxy();
    if (proxy != null) {
      return proxy.getInterestList(InterestType.FILTER_CLASS);
    }
    else {
      throw new UnsupportedOperationException( LocalizedStrings.LocalRegion_INTEREST_LIST_RETRIEVAL_REQUIRES_A_POOL.toLocalizedString());
    }
  }

  public List getInterestListOQL()
  {
    ServerRegionProxy proxy = getServerProxy();
    if (proxy != null) {
      return proxy.getInterestList(InterestType.OQL_QUERY);
    }
    else {
      throw new UnsupportedOperationException( LocalizedStrings.LocalRegion_INTEREST_LIST_RETRIEVAL_REQUIRES_A_POOL.toLocalizedString());
    }
  }

  public Set keySetOnServer() {
    ServerRegionProxy proxy = getServerProxy();
    if (proxy != null) {
      return proxy.keySet();
    } else {
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_SERVER_KEYSET_REQUIRES_A_POOL.toLocalizedString());
    }
  }

  public boolean containsKeyOnServer(Object key) {
    checkReadiness();
    checkForNoAccess();
    ServerRegionProxy proxy = getServerProxy();
    if (proxy != null) {
      return proxy.containsKey(key);
    } else {
      throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_SERVER_KEYSET_REQUIRES_A_POOL.toLocalizedString());
    }
  }

  /**
   * WARNING: this method is overridden in subclasses.
   *
   * @param key
   * @see DistributedRegion#localDestroyNoCallbacks(Object)
   */
  protected void localDestroyNoCallbacks(Object key)
  {
    LogWriterI18n logger = getCache().getLoggerI18n();
    if (logger.fineEnabled()) {
      logger.fine("localDestroyNoCallbacks key="
          + key);
    }
    checkReadiness();
    validateKey(key);
    EntryEventImpl event = EntryEventImpl.create(this, Operation.LOCAL_DESTROY,
        key, false, getMyId(), false /* generateCallbacks */, true);
    try {
      basicDestroy(event,
                   false,
                   null); // expectedOldValue
    }
    catch (CacheWriterException e) {
      // cache writer not called
      throw new Error(LocalizedStrings.LocalRegion_CACHE_WRITER_SHOULD_NOT_HAVE_BEEN_CALLED_FOR_LOCALDESTROY.toLocalizedString(), e);
    }
    catch (TimeoutException e) {
      // no distributed lock
      throw new Error(LocalizedStrings.LocalRegion_NO_DISTRIBUTED_LOCK_SHOULD_HAVE_BEEN_ATTEMPTED_FOR_LOCALDESTROY.toLocalizedString(), e);
    }
    catch (EntryNotFoundException e) {
      // not a problem
    } finally {
      event.release();
    }
  }

  /**
   * Do localDestroy on a list of keys, if they exist
   *
   * @param keys
   *          the list of arrays of keys to invalidate
   * @see #registerInterest(Object)
   */
  private void clearViaList(List keys)
  {
    for (Iterator it = this.entries(false).iterator(); it.hasNext();) {
      Region.Entry entry = (Region.Entry)it.next();
      try {
        Object entryKey = entry.getKey();
        boolean match = false;
        for (Iterator it2 = keys.iterator(); it2.hasNext();) {
          Object k = it2.next();
          if (entryKey.equals(k)) {
            match = true;
            break;
          }
        } // for
        if (!match) {
          continue;
        }
        localDestroyNoCallbacks(entryKey);
      }
      catch (EntryDestroyedException ignore) {
        // ignore to fix bug 35534
      }
    }
  }

  /**
   * do a localDestroy on all matching keys
   *
   * @param key
   *          the regular expression to match on
   * @see #registerInterestRegex(String)
   */
  private void clearViaRegEx(String key)
  {
    // @todo: if (key.equals(".*)) then cmnClearRegionNoCallbacks
    Pattern keyPattern = Pattern.compile(key);
    for (Iterator it = this.entries(false).iterator(); it.hasNext();) {
      Region.Entry entry = (Region.Entry)it.next();
      try {
        Object entryKey = entry.getKey();
        if (!(entryKey instanceof String))
          continue;
        if (!keyPattern.matcher((String)entryKey).matches()) {
          //key does not match the regex, this entry should not be returned.
          continue;
        }
        localDestroyNoCallbacks(entryKey);
      }
      catch (EntryDestroyedException ignore) {
        // ignore to fix bug 35534
      }
    }
  }

  /**
   * do a localDestroy on all matching keys
   *
   * @param key
   *          the regular expression to match on
   * @see #registerInterestFilter(String)
   */
  private void clearViaFilterClass(String key)
  {
    Class filterClass;
    InterestFilter filter;
    try {
      filterClass = ClassLoadUtil.classFromName(key);
      filter = (InterestFilter)filterClass.newInstance();
    }
    catch (ClassNotFoundException cnfe) {
      throw new RuntimeException(LocalizedStrings.LocalRegion_CLASS_0_NOT_FOUND_IN_CLASSPATH.toLocalizedString(key), cnfe);
    }
    catch (Exception e) {
      throw new RuntimeException(LocalizedStrings.LocalRegion_CLASS_0_COULD_NOT_BE_INSTANTIATED.toLocalizedString(key), e);
    }

    for (Iterator it = this.entries(false).iterator(); it.hasNext();) {
      Region.Entry entry = (Region.Entry)it.next();
      try {
        Object entryKey = entry.getKey();
        if (!(entryKey instanceof String))
          continue;
        InterestEvent e = new InterestEvent(entryKey, entry.getValue(), true);
        if (!filter.notifyOnRegister(e)) {
          //the filter does not want to know about this entry, so skip it.
          continue;
        }
        localDestroyNoCallbacks(entryKey);
      }
      catch (EntryDestroyedException ignore) {
        // ignore to fix bug 35534
      }
    }
  }

  /**
   * Do a localDestroy of all matching keys
   *
   * @param query
   * @see #registerInterestOQL(String)
   */
  private void clearViaQuery(String query)
  {
    throw new InternalGemFireError(LocalizedStrings.LocalRegion_NOT_YET_SUPPORTED.toLocalizedString());
  }

  /**
   * Refresh local entries based on server's list of keys
   * @param serverKeys
   */
  public void refreshEntriesFromServerKeys(Connection con, List serverKeys,
      InterestResultPolicy pol)
  {
    ServerRegionProxy proxy = getServerProxy();
    LogWriterI18n logger = getCache().getLoggerI18n();
    if (logger.fineEnabled()) {
      logKeys(serverKeys, pol);
    }

    if (pol == InterestResultPolicy.NONE) {
      return; // done
    }

    if (logger.fineEnabled()) {
      logger.fine("refreshEntries region=" + getFullPath());
    }
    for (Iterator it = serverKeys.iterator(); it.hasNext();) {
      ArrayList keysList = (ArrayList)it.next();
      // The chunk can contain null data if there are no entries on the server
      // corresponding to the requested keys
      if (keysList == null) {
        continue;
      }
      // int numberOfResults = keysList.size();
      if(EntryLogger.isEnabled()) {
        if(con != null) {
          Endpoint endpoint = con.getEndpoint();
          if(endpoint != null) {
            EntryLogger.setSource(endpoint.getMemberId(), "RIGII");
          }
        }
      }
      try {
        ArrayList list = new ArrayList(keysList);
        for (Iterator it2 = keysList.iterator(); it2.hasNext();) {
          Object currentKey = it2.next();
          // Dont apply riResponse if the entry was destroyed when
          // ri is in progress
          if (currentKey == null || getImageState().hasDestroyedEntry(currentKey)){
            list.remove(currentKey);
          }
        }
        
        if (pol == InterestResultPolicy.KEYS) {
          // Attempt to create an invalid in without overwriting
          if (!isProxy()) {
            for (Iterator it2 = list.iterator(); it2.hasNext();) {
              Object currentKey = it2.next();
              entries.initialImagePut(currentKey, 0, Token.LOCAL_INVALID, false,
                  false, null, null, false);
              
            }
          }
          // Size statistics don't take key into account, so we don't
          // need to modify the region's size.
        }
        else if(!list.isEmpty()) {
          Assert.assertTrue(pol == InterestResultPolicy.KEYS_VALUES);
          for (Iterator it2 = list.iterator(); it2.hasNext();) {
            Object currentKey = it2.next();
            localDestroyNoCallbacks(currentKey);
          }
          VersionedObjectList values = proxy.getAllOnPrimaryForRegisterInterest(con, list);
          if (logger.fineEnabled()) {
            logger.fine("processing interest response: " + values);
          }
          VersionedObjectList.Iterator listIt = values.iterator();
          while (listIt.hasNext()) {
            VersionedObjectList.Entry entry = listIt.next();
            Object currentKey = entry.getKey();
            Object val = entry.getObject();
            boolean isBytes = entry.isBytes();
            boolean isKeyOnServer = !entry.isKeyNotOnServer();
            boolean isTombstone = this.concurrencyChecksEnabled
                                  && entry.isKeyNotOnServer()
                                  && (entry.getVersionTag() != null);
            final VersionTag tag = entry.getVersionTag();
            if (val instanceof Throwable) {
              logger
                  .warning(
                      LocalizedStrings.LocalRegion_CAUGHT_THE_FOLLOWING_EXCEPTION_FOR_KEY_0_WHILE_PERFORMING_A_REMOTE_GETALL,
                      currentKey, (Throwable)val);
              //TODO - shouldn't we skip this value and not put it in the region
              //then? I think we're putting an Exception object in the region.
            } else {
              if (logger.fineEnabled()) {
                  logger.fine("refreshEntries key=" + currentKey + " value=" + entry);
              }
            }
            
            if(val instanceof byte[] && !isBytes) {
              val = CachedDeserializableFactory.create((byte[]) val);
            }

            if (val instanceof byte[] && !isBytes) {
              if (CachedDeserializableFactory.preferObject()) {
                val = EntryEventImpl.deserialize((byte[])val);
              }
              else {
                val = CachedDeserializableFactory.create((byte[])val);
              }
            }
            
            if (isTombstone) {
              assert val == null : "server returned a value for a destroyed entry";
              val = Token.TOMBSTONE;
            }

            if (val != null || isTombstone) {
              // Sneakily drop in the value into our local cache,
              // but don't overwrite
//              if (logger.fineEnabled()) {
//                logger.fine("refreshEntries key=" + currentKey + " value=" + 
//                    (val instanceof CachedDeserializable? ((CachedDeserializable)val).getDeserializedForReading() : val));
//              }
              if (!isProxy()) {
                entries.initialImagePut(currentKey, 0, val, false, false, tag, null, false);
              }
            }
            else {
              RegionEntry re = entries.getEntry(currentKey);
              if (!isProxy() && isKeyOnServer) {
                entries.initialImagePut(currentKey, 0, Token.LOCAL_INVALID,
                      false,false, tag, null, false);
              }
              else {
                if (re != null) {
                  synchronized (re) {
                    if (re.isDestroyedOrRemovedButNotTombstone()) { 
                      entries.removeEntry(currentKey, re, false);
                    }
                  }
                }
              }
              // In this case, if we didn't overwrite, we don't have a local
              // value, so no size change needs to be recorded.
            }
          }
        }
      }
      catch (DiskAccessException dae) {
        this.handleDiskAccessException(dae, true/* stop bridge servers*/);
        throw dae;
      } finally {
        EntryLogger.clearSource();
      }
    } // for
  }

  private void logKeys(List serverKeys, InterestResultPolicy pol)
  {
    int totalKeys = 0;
    StringBuilder buffer = new StringBuilder();
    for (Iterator it = serverKeys.iterator(); it.hasNext();) {
      ArrayList keysList = (ArrayList)it.next();
      // The chunk can contain null data if there are no entries on the server
      // corresponding to the requested keys
      // TODO is this still possible?
      if (keysList == null)
        continue;
      int numThisChunk = keysList.size();
      totalKeys += numThisChunk;
      for (Iterator it2 = keysList.iterator(); it2.hasNext();) {
        Object key = it2.next();
        if (key != null) {
          buffer.append("  " + key).append("\n");
        }
      }
    } // for
    getCache().getLoggerI18n().fine(this + " refreshEntriesFromServerKeys count=" + totalKeys
        + " policy=" + pol.toString() + "\n" + buffer.toString());
  }
  /**
   * Remove values in local cache before registering interest
   *
   * @param key
   *          the interest key
   * @param interestType
   *          the interest type from {@link InterestType}
   * @param pol
   *          the policy from {@link InterestResultPolicy}
   */
  public void clearKeysOfInterest(Object key, int interestType,
      InterestResultPolicy pol)
  {
    switch (interestType) {
    case InterestType.FILTER_CLASS:
      clearViaFilterClass((String)key);
      break;
    case InterestType.KEY:
      if (key instanceof String && key.equals("ALL_KEYS"))
        clearViaRegEx(".*");
      else if (key instanceof List)
        clearViaList((List)key);
      else
        localDestroyNoCallbacks(key);
      break;
    case InterestType.OQL_QUERY:
      clearViaQuery((String)key);
      break;
    case InterestType.REGULAR_EXPRESSION:
      clearViaRegEx((String)key);
      break;
    default:
      throw new InternalGemFireError(LocalizedStrings.LocalRegion_UNKNOWN_INTEREST_TYPE.toLocalizedString());
    }
  }

  //////////////////// Package Methods ////////////////////////////////////////

  /**
   * Destroys and recreates this region. If this is triggered by loadSnapshot
   * inputStream will be supplied. If this is triggered by LossAction of
   * reinitialize then inputStream will be null, and the region will go through
   * regular GetInitalImage if it is a mirrored replicate.
   * <p>
   * Acquires and releases the DestroyLock.
   *
   * @since 5.0
   */
  void reinitialize(InputStream inputStream, RegionEventImpl event)
      throws TimeoutException, IOException, ClassNotFoundException
  {
    acquireDestroyLock();
    try {
      reinitialize_destroy(event);
      recreate(inputStream, null);
    }
    finally {
      releaseDestroyLock();
    }
  }

  /** must be holding destroy lock */
  void reinitializeFromImageTarget(InternalDistributedMember imageTarget)
      throws TimeoutException, IOException, ClassNotFoundException
  {
    Assert.assertTrue(imageTarget != null);
    recreate(null, imageTarget);
  }

  /**
   * Returns true if this region was reinitialized, e.g. a snapshot was loaded,
   * and this is the recreated region
   */
  boolean reinitialized_new()
  {
    return this.reinitialized_new;
  }

  /** must be holding destroy lock */
  void reinitialize_destroy(RegionEventImpl event) throws CacheWriterException,
      TimeoutException
  {
    final boolean cacheWrite = !event.originRemote;
    // register this region as reinitializing
    this.cache.regionReinitializing(getFullPath());
    basicDestroyRegion(event, cacheWrite, false/* lock */, true);
  }

  /** must be holding destroy lock */
  private void recreate(InputStream inputStream,
      InternalDistributedMember imageTarget) throws TimeoutException,
      IOException, ClassNotFoundException
  {
    String thePath = getFullPath();
    Region newRegion = null;
    // recreate new region with snapshot data

    try {
      LocalRegion parent = this.parentRegion;
      boolean getDestroyLock = false;
      // If specified diskDir in DEFAULT diskstore, we should not use null
      // as diskstore name any more
      if (this.dsi!=null && this.dsi.getName().equals(DiskStoreFactory.DEFAULT_DISK_STORE_NAME)
          && this.diskStoreName == null && !useDefaultDiskStore()) {
        this.diskStoreName = this.dsi.getName();
      }
      RegionAttributes attrs = this;
      InternalRegionArguments iargs = new InternalRegionArguments()
      .setDestroyLockFlag(getDestroyLock)
      .setSnapshotInputStream(inputStream)
      .setImageTarget(imageTarget)
      .setRecreateFlag(true);
      if (this instanceof BucketRegion) {
        BucketRegion me = (BucketRegion) this;
        iargs.setPartitionedRegionBucketRedundancy(me.getRedundancyLevel());
      }

      if (parent == null) {
        newRegion = this.cache.createVMRegion(this.regionName, attrs, iargs);
      }
      else {
        newRegion = parent.createSubregion(this.regionName, attrs, iargs);
      }

      // note that createVMRegion and createSubregion now call
      // regionReinitialized
    }
    catch (RegionExistsException e) {
      // shouldn't happen since we're holding the destroy lock
      InternalGemFireError error = new InternalGemFireError(LocalizedStrings.LocalRegion_GOT_REGIONEXISTSEXCEPTION_IN_REINITIALIZE_WHEN_HOLDING_DESTROY_LOCK.toLocalizedString());
      error.initCause(e);
      throw error;
    }
    finally {
      if (newRegion == null) { // failed to create region
        this.cache.unregisterReinitializingRegion(thePath);
      }
    }
  }

  void loadSnapshotDuringInitialization(InputStream inputStream)
      throws IOException, ClassNotFoundException
  {
    DataInputStream in = new DataInputStream(inputStream);
    try {
      RegionMap map = getRegionMap();
      byte snapshotVersion = in.readByte();
      if (snapshotVersion != SNAPSHOT_VERSION) {
        throw new IllegalArgumentException(LocalizedStrings.LocalRegion_UNSUPPORTED_SNAPSHOT_VERSION_0_ONLY_VERSION_1_IS_SUPPORTED.toLocalizedString(new Object[] {Byte.valueOf(snapshotVersion), Byte.valueOf(SNAPSHOT_VERSION)}));
      }
      for (;;) {
        Object key = DataSerializer.readObject(in);
        if (key == null)
          break;
        byte b = in.readByte();
        Object value;

        if (b == SNAPSHOT_VALUE_OBJ) {
          value = DataSerializer.readObject(in);
        }
        else if (b == SNAPSHOT_VALUE_INVALID) {
          // Even though it was a distributed invalidate when the
          // snapshot was created I think it is correct to turn it
          // into a local invalidate when we load the snapshot since
          // we don't do a distributed invalidate operation when loading.
          value = Token.LOCAL_INVALID;
        }
        else if (b == SNAPSHOT_VALUE_LOCAL_INVALID) {
          value = Token.LOCAL_INVALID;
        }
        else {
          throw new IllegalArgumentException(LocalizedStrings.LocalRegion_UNEXPECTED_SNAPSHOT_CODE_0_THIS_SNAPSHOT_WAS_PROBABLY_WRITTEN_BY_AN_EARLIER_INCOMPATIBLE_RELEASE.toLocalizedString(new Byte(b)));
        }
        
        //If versioning is enabled, we will give the entry a "fake"
        //version.
        VersionTag tag = null;
        if(this.concurrencyChecksEnabled) {
          tag = VersionTag.create(getVersionMember());
        }
        map.initialImagePut(key, cacheTimeMillis(), value, false,
            false, tag, null, false);
      }
    }
    finally {
      in.close();
    }
    this.reinitialized_new = true;
  }

  /** Package helper method */
  @Retained
  final Object getEntryValue(RegionEntry entry) {
    if (entry == null) {
      return null;
    }

    try {
      return entry.getValue(this);
    } catch (DiskAccessException dae) {
      this.handleDiskAccessException(dae, true/* stop bridge servers*/);
      throw dae;
    }
  }

  /**
   * Blocks until initialization is complete.
   * @param destroyedRegionOk
   *          true if it is okay to return a region that isDestroyed
   * @param returnUnInitializedRegion 
   *          true if waitOnInitialization is to be avoided.
   *
   * @see DestroyRegionOperation
   */
  final Region getSubregion(String path, boolean destroyedRegionOk, final boolean returnUnInitializedRegion)
  {
    if (destroyedRegionOk) {
      checkCacheClosed();
    }
    else if (isDestroyed()) {
      // Assume if the owner of the subregion is destroyed, so are all of its
      // subregions
      return null;
    }
    if (path == null) {
      throw new IllegalArgumentException(LocalizedStrings.LocalRegion_PATH_SHOULD_NOT_BE_NULL.toLocalizedString());
    }
    if (path.length() == 0) {
      waitOnInitialization(); // some internal methods rely on this
      return this;
    }

    if (path.charAt(0) == SEPARATOR_CHAR)
      throw new IllegalArgumentException(LocalizedStrings.LocalRegion_PATH_SHOULD_NOT_START_WITH_A_SLASH.toLocalizedString());

    int sep_idx; // the index of the next separator
    // initialize the current region as this one
    LocalRegion r = this;
    // initialize the rest of the name to be regionName
    String n = path;
    String next; // the next part of the path
    boolean last; // last: are we on the last part of the path?
    do {
      // if the rest of the name is empty, then we're done, return
      // current region
      if (n.length() == 0) {
        break; // return r
      }
      sep_idx = n.indexOf(SEPARATOR_CHAR);
      last = sep_idx < 0; // this is the last part if no separator
      // try to get next region
      next = last ? n : n.substring(0, sep_idx);
      r = r.basicGetSubregion(next);
      if (r == null) {
        // not found
        return null;
      }
      if (r.isDestroyed() && !destroyedRegionOk) {
        return null;
      }
      if (!last) // if found but still more to do, get next rest of path
        n = n.substring(sep_idx + 1);
    } while (!last);

    if (!returnUnInitializedRegion) {
      r.waitOnInitialization();
    }

    // if region has just been destroyed return null unless specified not to
    if (r.isDestroyed()) {
      if (!destroyedRegionOk) {
        return null;
      }
      return r;
    }

    return r;
  }

  /**
   * Called by a thread that is doing region initialization. Causes the
   * initialization Latch to be bypassed by this thread.
   *
   * @return oldLevel
   */
  public static int setThreadInitLevelRequirement(int level)
  {
    int oldLevel = threadInitLevelRequirement();
    if (level == AFTER_INITIAL_IMAGE) { // if setting to default, just reset
      initializationThread.set(null);
    }
    else {
      initializationThread.set(Integer.valueOf(level));
    }
    return oldLevel;
  }

  /**
   * Return the access level this thread has for regions with respect to how
   * initialized they need to be before this thread can have a reference to it.
   * AFTER_INITIAL_IMAGE: Must be fully initialized (the default)
   * BEFORE_INITIAL_IMAGE: Must have had first latch opened ANY_INIT: Thread
   * uses region as soon as possible
   */
  public static int threadInitLevelRequirement()
  {
    Integer initLevel = (Integer)initializationThread.get();
    if (initLevel == null) {
      return AFTER_INITIAL_IMAGE;
    }
    return initLevel.intValue();
  }
  
  public boolean checkForInitialization() {
    if (this.initialized) {
      return true;
    }
    switch (threadInitLevelRequirement()) {
    case AFTER_INITIAL_IMAGE:
      return checkForInitialization(this.initializationLatchAfterGetInitialImage);
    case BEFORE_INITIAL_IMAGE:
      return checkForInitialization(this.initializationLatchBeforeGetInitialImage);
    case ANY_INIT:
      return true;
    default:
      throw new InternalGemFireError(LocalizedStrings.LocalRegion_UNEXPECTED_THREADINITLEVELREQUIREMENT.toLocalizedString());
    }
  }

  private boolean checkForInitialization(
      StoppableCountDownLatch latch) {
    return latch.getCount() == 0;
  }

  /** wait on the initialization Latch based on thread requirements */
  public void waitOnInitialization()
  {
    if (this.initialized) {
      return;
    }
    switch (threadInitLevelRequirement()) {
    case AFTER_INITIAL_IMAGE:
      waitOnInitialization(this.initializationLatchAfterGetInitialImage);
      break;
    case BEFORE_INITIAL_IMAGE:
      waitOnInitialization(this.initializationLatchBeforeGetInitialImage);
      break;
    case ANY_INIT:
      return;
    default:
      throw new InternalGemFireError(LocalizedStrings.LocalRegion_UNEXPECTED_THREADINITLEVELREQUIREMENT.toLocalizedString());
    }
  }

  protected final void waitOnInitialization(StoppableCountDownLatch latch) {
    if (latch == null)
      return; // latch resource has been freed

      while (true) {
        cache.getCancelCriterion().checkCancelInProgress(null);
        boolean interrupted = Thread.interrupted();
        try {
          latch.await();
          break;
        }
        catch (InterruptedException e) {
          interrupted = true;
          cache.getCancelCriterion().checkCancelInProgress(e);
          // continue waiting
        }
        finally {
          if (interrupted) // set interrupted flag if was interrupted
            Thread.currentThread().interrupt();
        }
      } // while
  }

  /**
   * Wait until data is ready in this region
   */
  public void waitForData() {
    if (this.initialized) {
      return;
    }
    waitOnInitialization(this.initializationLatchAfterGetInitialImage);
  }

  /** return null if not found */
  @Override
  public final RegionEntry basicGetEntry(Object key) {
    // ok to ignore tx state; all callers are non-transactional
    final RegionEntry re = this.entries.getEntry(key);
    if (re != null && !re.isRemoved()) {
      return re;
    }
    return null;
  }

  /**
   * Return true if invalidation occurred; false if it did not, for example if
   * it was already invalidated
   *
   * @see DistributedRegion#basicInvalidate(EntryEventImpl)
   */
  void basicInvalidate(EntryEventImpl event) throws EntryNotFoundException
  {
    basicInvalidate(event, isInitialized()/* for bug 35214 */);
  }

  /**
   * Used by disk regions when recovering data from backup. Currently this "put"
   * is done at a very low level to keep it from generating events or pushing
   * updates to others.
   */
  public DiskEntry initializeRecoveredEntry(Object key, DiskEntry.RecoveredEntry value)
  {
    Assert.assertTrue(this.diskRegion != null);
    // region operation so it is ok to ignore tx state
    RegionEntry re = this.entries.initRecoveredEntry(key, value);
    if (re == null) {
      throw new InternalGemFireError(LocalizedStrings.LocalRegion_ENTRY_ALREADY_EXISTED_0.toLocalizedString(key));
    }
    return (DiskEntry)re;
  }
  /**
   * Used by disk regions when recovering data from backup and
   * initializedRecoveredEntry has already been called for the given key.
   * Currently this "put"
   * is done at a very low level to keep it from generating events or pushing
   * updates to others.
   */
  public DiskEntry updateRecoveredEntry(Object key, RegionEntry entry,
      DiskEntry.RecoveredEntry value) {
    Assert.assertTrue(this.diskRegion != null);
    // region operation so it is ok to ignore tx state
    RegionEntry re = this.entries.updateRecoveredEntry(key, entry, value);
    return (DiskEntry)re;
  }
  
  public void copyRecoveredEntries(RegionMap rm, boolean entriesIncompatible) {
    this.entries.copyRecoveredEntries(rm, entriesIncompatible);
  }
  
  public void recordRecoveredGCVersion(VersionSource member, long gcVersion) {
  //TODO - RVV - I'm not sure about this recordGCVersion method. It seems
    //like it's not doing the right thing if the current member is the member
    //we just recovered.
    //We need to update the RVV in memory
    this.versionVector.recordGCVersion(member, gcVersion, null);
    
    //We also need to update the RVV that represents what we have persisted on disk
    DiskRegion dr = this.getDiskRegion();
    if(dr != null) {
      dr.recordRecoveredGCVersion(member, gcVersion);
    }
  }

  public void recordRecoveredVersonHolder(VersionSource member,
      RegionVersionHolder versionHolder, boolean latestOplog) {
    //We need to update the RVV in memory
    this.versionVector.initRecoveredVersion(member, versionHolder, latestOplog);
    DiskRegion dr = this.getDiskRegion();
    //We also need to update the RVV that represents what we have persisted on disk
    if(dr != null) {
      dr.recordRecoveredVersonHolder(member, versionHolder, latestOplog);
    }
  }
  
  @Override
  public void recordRecoveredVersionTag(VersionTag tag) {
    this.versionVector.recordVersion(tag.getMemberID(), tag.getRegionVersion(), null);
    DiskRegion dr = this.getDiskRegion();
    //We also need to update the RVV that represents what we have persisted on disk
    if(dr != null) {
      dr.recordRecoveredVersionTag(tag);
    }
  }
  
  

  @Override
  public void setRVVTrusted(boolean rvvTrusted) {
    DiskRegion dr = this.getDiskRegion();
    //Update whether or not the RVV we have recovered is trusted (accurately
    //represents what we have on disk).
    if(dr != null) {
      dr.setRVVTrusted(rvvTrusted);
    }
  }
  
  /**
   * Fix up our RVV by iterating over the entries in the region
   * and making sure they are applied to the RVV.
   * 
   * If we failed to do a GII, we may have applied the RVV from a remote member.
   * That RVV may not have seen some of the events in our local RVV. Those
   * entries were supposed to be replaced with the results of the GII. However,
   * if we failed the GII, those entries may still be in the cache, but are 
   * no longer reflected in the local RVV. This method iterates over those
   * keys and makes sure their versions are applied to the local RVV.
   * 
   * TODO - this method should probably rebuild the RVV from scratch, instead
   * of starting with the existing RVV. By starting with the existing RVV, we
   * may claim to have entries that we actually don't have. Unfortunately, we
   * can't really rebuild the RVV from scratch because we will end up with
   * huge exception lists.
   * 
   * However, if we are in the state of recovering from disk with an untrusted
   * RVV, we must be newer than any other surviving members. So they shouldn't
   * have any entries in their cache that match entries that we failed to receive
   * through the GII but are reflected in our current RVV. So it should be 
   * safe to start with the current RVV.
   * 
   */
  void repairRVV() {
    RegionVersionVector rvv = this.getVersionVector();
    
    if(rvv == null) {
      //No need to do anything.
      return;
    }
    
    Iterator<RegionEntry> it = getBestLocalIterator(false);
    int count = 0;
    LogWriterI18n logger = this.getLogWriterI18n();
    VersionSource<?> myId = this.getVersionMember();
    //Iterate over the all of the entries
    while (it.hasNext()) {
      RegionEntry mapEntry = it.next();
      VersionStamp<?> stamp = mapEntry.getVersionStamp();
      VersionSource<?> id = stamp.getMemberID();
      if (id == null) {
        id = myId;
      }
      //Make sure the version is applied to the regions RVV
      rvv.recordVersion(id, stamp.getRegionVersion(), null);
      
    }
  }

  /**
   * Return true if invalidation occurred; false if it did not, for example if
   * it was already invalidated
   */
  private void basicInvalidate(final EntryEventImpl event,
      boolean invokeCallbacks) throws EntryNotFoundException
  {
    basicInvalidate(event, invokeCallbacks, false);
  }

  /**
   * Asif:Made this function protected as this is over ridden in HARegion to
   * abort expiry of Events which have key as Long , if it is not able to
   * destroy from availableIDs
   *
   * @param forceNewEntry
   *          true if we are a mirror and still in the initialization phase.
   *          Called from InvalidateOperation.InvalidateMessage

   */
  void basicInvalidate(final EntryEventImpl event, boolean invokeCallbacks,
      final boolean forceNewEntry) throws EntryNotFoundException
  {
    if (!event.isOriginRemote() && !event.isDistributed()
        && getScope().isDistributed() && getDataPolicy().withReplication()
        && invokeCallbacks /*
                            * catches case where being called by (distributed)
                            * invalidateRegion
                            */) {
      throw new IllegalStateException(LocalizedStrings.LocalRegion_CANNOT_DO_A_LOCAL_INVALIDATE_ON_A_REPLICATED_REGION.toLocalizedString());
    }

    if (hasSeenEvent(event)) {
      if (DistributionManager.VERBOSE) {
        getCache().getLoggerI18n().info(
          LocalizedStrings.DEBUG,
          "LR.basicInvalidate: this cache has already seen this event " + event);
      }
      if (this.concurrencyChecksEnabled && event.getVersionTag() != null && !event.getVersionTag().isRecorded()) {
        getVersionVector().recordVersion((InternalDistributedMember) event.getDistributedMember(), event.getVersionTag(), event);
      }
      return;
    }

    final TXStateInterface tx = discoverJTA(event);
    getDataView(tx).invalidateExistingEntry(event, invokeCallbacks,
        forceNewEntry);
  }

  void basicInvalidatePart2(RegionEntry re, EntryEventImpl event,
      boolean conflictwithClear, boolean invokeCallbacks)
  {
    updateStatsForInvalidate();

    if (invokeCallbacks) {
      try {
        re.dispatchListenerEvents(event);
      }
      catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        stopper.checkCancelInProgress(null);
        return;
      }
    }
    else {
      event.callbacksInvoked(true);
    }
  }

  /**
   * Update stats
   */
  private void updateStatsForInvalidate() {
    getCachePerfStats().incInvalidates();
  }

  void basicInvalidatePart3(RegionEntry re, EntryEventImpl event,
      boolean invokeCallbacks) {
    // No op. overriden by sub classes.
    // Dispatching listener events moved to basic*Part2.
  }

  /**
   * invoke callbacks for an invalidation
   */
  public void invokeInvalidateCallbacks(final EnumListenerEvent eventType,
      final EntryEventImpl event, final boolean callDispatchListenerEvent) {
//    getCache().getLoggerI18n().fine("invoking Invalidate callbacks for " + event);
    // Notify bridge clients (if this is a BridgeServer)
    event.setEventType(eventType);
    notifyBridgeClients(event);
    if (this.hdfsStoreName != null) {
      notifyGatewayHubs(eventType, event);
    }
    if(callDispatchListenerEvent){
      dispatchListenerEvent(eventType, event);
    }
  }

  /**
   * @param re
   *          the existing RegionEntry of the entry to put
   * @param txState
   *          the TXState of the current transaction
   * @param key
   *          the key of the entry to invalidate
   * @param newValue
   *          the new value of the entry
   * @param didDestroy
   *          true if tx destroyed this entry at some point
   * @param filterRoutingInfo 
   * @param bridgeContext 
   * @param versionTag tag generated by txCoordinator - only on far side
   * @param tailKey tail (shadow) key generated by txCoordinator for WAN - only on farside
   * @param txr the {@link TXRegionState} for the operation
   * @param cbEvent a template EntryEvent for callbacks
   */
  final void txApplyInvalidate(final RegionEntry re,
      final TXStateInterface txState, final Object key, Object newValue,
      boolean didDestroy, boolean localOp, EventID eventId,
      Object aCallbackArgument, List<EntryEventImpl> pendingCallbacks,
      FilterRoutingInfo filterRoutingInfo, ClientProxyMembershipID bridgeContext,
      VersionTag<?> versionTag, long tailKey, TXRegionState txr,
      EntryEventImpl cbEvent) {
    this.entries.txApplyInvalidate(re, txState, key, newValue, didDestroy,
        localOp, eventId, aCallbackArgument, pendingCallbacks,
        filterRoutingInfo, bridgeContext, versionTag, tailKey, txr, cbEvent);
  }

  /**
   * Called by lower levels, while still holding the write sync lock, and the
   * low level has completed its part of the basic destroy
   */
  final void txApplyInvalidatePart2(RegionEntry re, Object key,
      boolean didDestroy, boolean didInvalidate, boolean clearConflict)
  {
    if (this.testCallable != null) {
      this.testCallable.call(this, Operation.INVALIDATE, re);
    }
    if (didInvalidate) {
      updateStatsForInvalidate();
      // Bug 40842: clearing index of the old value 
      // performed in AbstractRegionMap
    }
    if (didDestroy) {
      if (this.entryUserAttributes != null) {
        this.entryUserAttributes.remove(key);
      }
    }
  }

  /**
   * Allows null as new value to accomodate create with a null value. Assumes
   * all key, value, and callback validations have been performed.
   *
   * @param event
   *          the event object for this operation, with the exception that the
   *          oldValue parameter is not yet filled in. The oldValue will be
   *          filled in by this operation.
   *
   * @param ifNew
   *          true if this operation must not overwrite an existing key
   * @param ifOld
   *          true if this operation must not create a new key
   * @param expectedOldValue
   *          only succeed if old value is equal to this value. If null,
   *          then doesn't matter what old value is. If INVALID token,
   *          must be INVALID.
   * @param requireOldValue
   *          true if the oldValue should be set in event even if ifNew
   *          and entry exists
   * @return false if ifNew is true and there is an existing key or
   *         if ifOld is true and expectedOldValue does not match the current
   *         value in the cache.  Otherwise return true.
   */
  protected final boolean basicPut(EntryEventImpl event,
      boolean ifNew,
      boolean ifOld,
      Object expectedOldValue,
      boolean requireOldValue)
  throws TimeoutException, CacheWriterException {

    final TXStateInterface tx = discoverJTA(event);
    long lastModifiedTime = cacheTimeMillis();
    return getDataView(tx).putEntry(event, ifNew, ifOld, expectedOldValue,
        requireOldValue, true, lastModifiedTime, false);
  }

  /**
   * @param putOp
   *          describes the operation that did the put
   * @param re
   *          the existing RegionEntry of the entry to put
   * @param txState
   *          the TXState of the current transaction
   * @param key
   *          the key of the entry to put
   * @param newValue
   *          the new value of the entry
   * @param didDestroy
   *          true if tx destroyed this entry at some point
   * @param aCallbackArgument
   *          argument passed in by user
   * @param filterRoutingInfo 
   * @param bridgeContext 
   * @param versionTag tag generated by txCoordinator - only on far side
   * @param tailKey tail (shadow) key generated by txCoordinator for WAN - only on farside
   * @param txr the {@link TXRegionState} for the operation
   * @param cbEvent a template EntryEvent for callbacks
   */
  final void txApplyPut(Operation putOp, final RegionEntry re,
      final TXStateInterface txState, final Object key, final Object newValue,
      boolean didDestroy, EventID eventId, Object aCallbackArgument,
      List<EntryEventImpl> pendingCallbacks, FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, VersionTag<?> versionTag,
      long tailKey, TXRegionState txr, EntryEventImpl cbEvent, Delta delta) {
    long startPut = CachePerfStats.getStatTime();
    this.entries.txApplyPut(putOp, re, txState, key, newValue, didDestroy,
        eventId, aCallbackArgument, pendingCallbacks, filterRoutingInfo,
        bridgeContext, versionTag, tailKey, txr, cbEvent, delta);
    updateStatsForPut(startPut);
  }

  /**
   * update stats
   */
  private void updateStatsForPut(long startPut) {
    getCachePerfStats().endPut(startPut, false);
    if(this.partitionedRegionForBucket != null){  
      this.partitionedRegionForBucket.getPrStats().endPut(startPut);
    }
  }

  final void txApplyPutPart2(RegionEntry re, Object key, Object newValue,
      long lastModified, boolean isCreate, boolean didDestroy, boolean clearConflict )
  {
    if (this.testCallable != null) {
      Operation op = isCreate ? Operation.CREATE : Operation.UPDATE;
      this.testCallable.call(this, op, re);
    }
    if (isCreate) {
      updateStatsForCreate();
    }
    if (!isProxy() && !clearConflict) {
      if (this.indexManager != null) {
        try {
          this.indexManager.updateIndexes(re,
                                          isCreate ? IndexManager.ADD_ENTRY :
                                                     IndexManager.UPDATE_ENTRY,
                                          isCreate ? IndexProtocol.OTHER_OP :
                                                     IndexProtocol.AFTER_UPDATE_OP);
        }
        catch (QueryException e) {
          throw new IndexMaintenanceException(e);
        }
      }
    }
    if (didDestroy) {
      if (this.entryUserAttributes != null) {
        this.entryUserAttributes.remove(key);
      }
    }
    if (this.statisticsEnabled && !clearConflict) {
      addExpiryTaskIfAbsent(re);
    }
    setLastModifiedTime(lastModified);
  }

  public boolean basicBridgeCreate(final Object key, final byte[] value,
      boolean isObject, Object p_callbackArg, final ClientProxyMembershipID client,
      boolean fromClient, EntryEventImpl clientEvent, boolean throwEntryExists) throws TimeoutException,
      EntryExistsException, CacheWriterException
  {
    EventID eventId = clientEvent.getEventId();
    Object callbackArg = p_callbackArg;
    long startPut = CachePerfStats.getStatTime();
    if (fromClient) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      if (getAttributes().getEnableGateway()) {
        callbackArg = new GatewayEventCallbackArgument(callbackArg);
      }
      if (isGatewaySenderEnabled()
          && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
        callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
      }
    }
    //Asif: Modified the call to this constructor by passing the new value obtained from remote site
    //instead of null .
    //The need for this arose, because creation of EntryEvent, makes call to PartitionResolver,
    //to get Hash. If the partitioning column is different from primary key, 
    //the resolver for GemFireXD is not able to obtain the hash object used for creation of KeyInfo  
     
    final EntryEventImpl event = EntryEventImpl.create(this, Operation.CREATE, key,
       value, callbackArg,  false /* origin remote */, client.getDistributedMember(),
        true /* generateCallbacks */,
        eventId);
    try {
    event.setContext(client);
    
    // if this is a replayed operation or WAN event we may already have a version tag
    event.setVersionTag(clientEvent.getVersionTag());
    //carry over the possibleDuplicate flag from clientEvent
    event.setPossibleDuplicate(clientEvent.isPossibleDuplicate());

    //Fix for 42448 - Only make create with null a local invalidate for
    //normal regions. Otherwise, it will become a distributed invalidate.
    if(getDataPolicy() == DataPolicy.NORMAL) {
      event.setLocalInvalid(true);
    }

    // Set the new value to the input byte[] if it isn't null
    /// For GemFireXD, if the new value happens to be an serialized object, then 
    //it needs to be converted into VMCachedDeserializable , or serializable delta 
    // as the case may be
    if (value != null) {
      // If the byte[] represents an object, then store it serialized
      // in a CachedDeserializable; otherwise store it directly as a byte[]
      if (isObject) {
        // The value represents an object
        event.setSerializedNewValue(value);
      }
      else {
        // The value does not represent an object
        event.setNewValue(value);
      }
    }

    boolean ifNew = true; // cannot overwrite an existing key
    boolean ifOld = false; // can create a new key
    long lastModified = 0L; // use now
    boolean overwriteDestroyed = false; // not okay to overwrite the DESTROYED
    // token
    boolean success = basicUpdate(event, ifNew, ifOld, lastModified,
        overwriteDestroyed, true);
    clientEvent.isConcurrencyConflict(event.isConcurrencyConflict());
    if (success) {
      clientEvent.setVersionTag(event.getVersionTag());
      getCachePerfStats().endPut(startPut, event.isOriginRemote());
    }
    else {
      this.stopper.checkCancelInProgress(null);
      if (throwEntryExists) {
        throw new EntryExistsException(""+key, event.getOldValue());
      }
    }
    return success;
    } finally {
      event.release();
    }
  }

  public boolean basicBridgePut(Object key, Object value, byte[] deltaBytes,
      boolean isObject, Object p_callbackArg, ClientProxyMembershipID memberId,
      boolean fromClient, boolean hasOldValue, EntryEventImpl clientEvent)
      throws TimeoutException, CacheWriterException {
    EventID eventID = clientEvent.getEventId();
    Object callbackArg = p_callbackArg;
    long startPut = CachePerfStats.getStatTime();
    if (fromClient) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      if (getAttributes().getEnableGateway()) {
        callbackArg = new GatewayEventCallbackArgument(callbackArg);
      }
      if (isGatewaySenderEnabled()
          && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
        callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
      }
    }
   
    final EntryEventImpl event = EntryEventImpl.create(this, Operation.UPDATE, key,
        null /* new value */, callbackArg,
        false /* origin remote */, memberId.getDistributedMember(),
        true /* generateCallbacks */,
        eventID);
    try {
    event.setContext(memberId);
    event.setDeltaBytes(deltaBytes);

    // if this is a replayed operation we may already have a version tag
    event.setVersionTag(clientEvent.getVersionTag());
    //carry over the possibleDuplicate flag from clientEvent
    event.setPossibleDuplicate(clientEvent.isPossibleDuplicate());
    
    // Set the new value to the input byte[]
    // If the byte[] represents an object, then store it
    // serialized in a CachedDeserializable; otherwise store it directly
    // as a byte[].
    if (isObject && value instanceof byte[]) {
      event.setSerializedNewValue((byte[])value);
    }
    else {
      event.setNewValue(value);
    }

    boolean ifNew = false; // can overwrite an existing key
    
    // Asif: If the system is GemFireXD, then update will always have value of
    // type SerializableDelta (i.e Delta) which requires that the old value
    // should be present
    boolean ifOld = false;
    // Keeping ifOld as false for PDX types region as it is a meta 
    // region not a GemXD user table
    if (!event.getRegion().isPdxTypesRegion()) {
      ifOld = hasOldValue; // false; // can create a new key
    }
    long lastModified = 0L; // use now
    boolean overwriteDestroyed = false; // not okay to overwrite the DESTROYED token
    boolean success = false;
    try {
      success = basicUpdate(event, ifNew, ifOld, lastModified,
       overwriteDestroyed, true);
    } catch (ConcurrentCacheModificationException ex) { // thrown by WAN conflicts
      event.isConcurrencyConflict(true);
    }
    clientEvent.isConcurrencyConflict(event.isConcurrencyConflict());
    if (success) {
      clientEvent.setVersionTag(event.getVersionTag());
      getCachePerfStats().endPut(startPut, event.isOriginRemote());
    }
    else {
      this.stopper.checkCancelInProgress(null);
    }
    return success;
    } finally {
      event.release();
    }
  }

  /**
   * issue a config message if the server and client have different
   * concurrency checking expectations
   * @param tag
   */
  private void concurrencyConfigurationCheck(VersionTag tag) {
    if (!this.concurrencyMessageIssued && ((tag != null) != this.concurrencyChecksEnabled)) {
      this.concurrencyMessageIssued = true;
      getLogWriterI18n().config(LocalizedStrings.LocalRegion_SERVER_HAS_CONCURRENCY_CHECKS_ENABLED_0_BUT_CLIENT_HAS_1_FOR_REGION_2,
          new Object[]{ !this.concurrencyChecksEnabled, this.concurrencyChecksEnabled, this});
    }
  }

  /**
   * Perform an update in a bridge client. See CacheClientUpdater.handleUpdate()
   * The op is from the bridge server and should not be distributed back to it.
   *
   * @throws CacheWriterException TODO-javadocs
   */
  public void basicBridgeClientUpdate(DistributedMember serverId, Object key,
      Object value, byte[] deltaBytes, boolean isObject,
      Object callbackArgument, boolean isCreate, boolean processedMarker,
      EntryEventImpl event, EventID eventID) throws TimeoutException,
      CacheWriterException {
    if (isCacheContentProxy()) {
      return;
    }
    concurrencyConfigurationCheck(event.getVersionTag());
    long startPut = CachePerfStats.getStatTime();
    // Generate EventID as it is possible that client is a cache server
    // in hierarchical cache
    if (generateEventID() && !this.cache.getCacheServers().isEmpty()) {
      event.setNewEventId(cache.getDistributedSystem());
    } else {
      event.setEventId(eventID);
    }
    event.setDeltaBytes(deltaBytes);

    // Set the new value to the input byte[] if it isn't null
    if (value != null) {
      // If the byte[] represents an object, then store it
      // serialized in a CachedDeserializable; otherwise store it directly
      // as a byte[].
      if (isObject && value instanceof byte[]) {
        // The value represents an object
        event.setSerializedNewValue((byte[])value);
      }
      else {
        // The value does not represent an object
        event.setNewValue(value);
      }
    }

//    final LogWriterI18n logger = getCache().getLoggerI18n();

    // If the marker has been processed, process this put event normally;
    // otherwise, this event occurred in the past and has been stored for a
    // durable client. In this case, just invoke the put callbacks.
    if (processedMarker) {
      boolean ifNew = false; // can overwrite an existing key
      boolean ifOld = false; // can create a new key
      long lastModified = 0L; // use now
      boolean overwriteDestroyed = true; //okay to overwrite the DESTROYED token
      if (basicUpdate(event, ifNew, ifOld, lastModified, overwriteDestroyed,
          true)) {
        getCachePerfStats().endPut(startPut, event.isOriginRemote());
      }
    }
    else {
      if (isInitialized()) {
        invokePutCallbacks(isCreate ? EnumListenerEvent.AFTER_CREATE
            : EnumListenerEvent.AFTER_UPDATE, event, true, true);
      }
    }
  }

  /**
   * Perform an invalidate in a bridge client.
   * The op is from the bridge server and should not be distributed back to it.
   *
   * @throws EntryNotFoundException TODO-javadocs
   */
  public void basicBridgeClientInvalidate(DistributedMember serverId, Object key,
      Object callbackArgument, boolean processedMarker, EventID eventID,
      VersionTag versionTag)
  throws EntryNotFoundException {
    if (!isCacheContentProxy()) {
      concurrencyConfigurationCheck(versionTag);

      // Create an event and put the entry
      EntryEventImpl event =
        EntryEventImpl.create(this,
                           Operation.INVALIDATE,
                           key, null /* newValue */,
                           callbackArgument /* callbackArg*/,
                           true /*originRemote*/,
                           serverId
                           );
      try {

      event.setVersionTag(versionTag);
      event.setFromServer(true);
      if (generateEventID() && !this.cache.getCacheServers().isEmpty()) {
        event.setNewEventId(cache.getDistributedSystem());
      } else {
        event.setEventId(eventID);
      }

      // If the marker has been processed, process this invalidate event
      // normally; otherwise, this event occurred in the past and has been
      // stored for a durable client. In this case, just invoke the invalidate
      // callbacks.
      if (processedMarker) {
        // [bruce] changed to force new entry creation for consistency
        final boolean forceNewEntry = this.concurrencyChecksEnabled;
        basicInvalidate(event, true, forceNewEntry);
        if (event.isConcurrencyConflict()) { // bug #45520 - we must throw this for the CacheClientUpdater
          throw new ConcurrentCacheModificationException();
        }
      } else {
        if (isInitialized()) {
          invokeInvalidateCallbacks(EnumListenerEvent.AFTER_INVALIDATE, event,
              true);
        }
      }
      } finally {
        event.release();
      }
    }
  }

  /**
   * Perform a destroy in a bridge client.
   * The op is from the bridge server and should not be distributed back to it.
   *
   * @throws EntryNotFoundException TODO-javadocs
   */
  public void basicBridgeClientDestroy(DistributedMember serverId, Object key,
      Object callbackArgument, boolean processedMarker, EventID eventID,
      VersionTag versionTag)
    throws EntryNotFoundException {
    if (!isCacheContentProxy()) {
      concurrencyConfigurationCheck(versionTag);

      // Create an event and destroy the entry
      EntryEventImpl event =
        EntryEventImpl.create(this,
                           Operation.DESTROY,
                           key, null /* newValue */,
                           callbackArgument /* callbackArg*/,
                           true /*originRemote*/,
                           serverId
                           );
      try {
      event.setFromServer(true);
      event.setVersionTag(versionTag);
      
      if (generateEventID() && !this.cache.getCacheServers().isEmpty()) {
        event.setNewEventId(cache.getDistributedSystem());
      } else {
        event.setEventId(eventID);
      }
      // If the marker has been processed, process this destroy event normally;
      // otherwise, this event occurred in the past and has been stored for a
      // durable client. In this case, just invoke the destroy callbacks.
      if (getLogWriterI18n().fineEnabled()) {
        getLogWriterI18n().fine("basicBridgeClientDestroy(processedMarker="+processedMarker+")");
      }
      if (processedMarker) {
        basicDestroy(event,
                     false, // cacheWrite
                     null); // expectedOldValue
        if (event.isConcurrencyConflict()) { // bug #45520 - we must throw an exception for CacheClientUpdater
          throw new ConcurrentCacheModificationException();
        }
      } else {
        if (isInitialized()) {
          invokeDestroyCallbacks(EnumListenerEvent.AFTER_DESTROY, event, true, true);
        }
      }
      } finally {
        event.release();
      }
    }
  }

  /**
   * Clear the region from a server request.
   * @param callbackArgument The callback argument. This is currently null
   * since {@link java.util.Map#clear} supports no parameters.
   * @param processedMarker Whether the marker has been processed (for durable
   * clients)
   */
  public void basicBridgeClientClear(Object callbackArgument, boolean processedMarker) {
    checkReadiness();
    checkForNoAccess();
    RegionEventImpl event = new RegionEventImpl(this,
        Operation.REGION_LOCAL_CLEAR, callbackArgument, true, getMyId(),
        generateEventID()/* generate EventID */);
    // If the marker has been processed, process this clear event normally;
    // otherwise, this event occurred in the past and has been stored for a
    // durable client. In this case, just invoke the clear callbacks.
    if (processedMarker) {
      basicLocalClear(event);
    } else {
      if (isInitialized()) {
        dispatchListenerEvent(EnumListenerEvent.AFTER_REGION_CLEAR, event);
      }
    }
  }
  
  
  
  public void basicBridgeDestroy(Object key, Object p_callbackArg,
      ClientProxyMembershipID memberId, boolean fromClient, EntryEventImpl clientEvent)
      throws TimeoutException, EntryNotFoundException, CacheWriterException
  {
    Object callbackArg = p_callbackArg;
    if (fromClient) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      if (getAttributes().getEnableGateway()) {
        callbackArg = new GatewayEventCallbackArgument(callbackArg);
      }
      if (isGatewaySenderEnabled()
          && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
        callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
      }
    }

    // Create an event and put the entry
    final EntryEventImpl event = EntryEventImpl.create(this, Operation.DESTROY, key,
        null /* new value */, callbackArg,
        false /* origin remote */, memberId.getDistributedMember(),
        true /* generateCallbacks */,
        clientEvent.getEventId());
    try {
    event.setContext(memberId);
    // if this is a replayed or WAN operation we may already have a version tag
    event.setVersionTag(clientEvent.getVersionTag());
    try {
      basicDestroy(event,
                 true,  // cacheWrite
                 null); // expectedOldValue
    } catch (ConcurrentCacheModificationException ex) { // thrown by WAN conflicts
      event.isConcurrencyConflict(true);
    } finally {
      clientEvent.setVersionTag(event.getVersionTag());
      clientEvent.isConcurrencyConflict(event.isConcurrencyConflict());
      clientEvent.setIsRedestroyedEntry(event.getIsRedestroyedEntry());
    }
    } finally {
      event.release();
    }
  }

  
  public void basicBridgeInvalidate(Object key, Object p_callbackArg,
      ClientProxyMembershipID memberId, boolean fromClient, EntryEventImpl clientEvent)
      throws TimeoutException, EntryNotFoundException, CacheWriterException
  {
    Object callbackArg = p_callbackArg;
    if (fromClient) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      if (getAttributes().getEnableGateway()) {
        callbackArg = new GatewayEventCallbackArgument(callbackArg);
      }
      if (isGatewaySenderEnabled()
          && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
        callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
      }
    }

    // Create an event and put the entry
    final EntryEventImpl event = EntryEventImpl.create(this, Operation.INVALIDATE, key,
        null /* new value */, callbackArg,
        false /* origin remote */, memberId.getDistributedMember(),
        true /* generateCallbacks */,
        clientEvent.getEventId());
    try {
    event.setContext(memberId);
    
    // if this is a replayed operation we may already have a version tag
    event.setVersionTag(clientEvent.getVersionTag());

    try {
      basicInvalidate(event);
    } finally {
      clientEvent.setVersionTag(event.getVersionTag());
      clientEvent.isConcurrencyConflict(event.isConcurrencyConflict());
    }
    } finally {
      event.release();
    }
  }

  public void basicBridgeUpdateVersionStamp(Object key, Object p_callbackArg,
      ClientProxyMembershipID memberId, boolean fromClient, EntryEventImpl clientEvent) {
 
    // Create an event and update version stamp of the entry
    EntryEventImpl event = EntryEventImpl.create(this, Operation.UPDATE_VERSION_STAMP, key,
        null /* new value */, null /*callbackArg*/,
        false /* origin remote */, memberId.getDistributedMember(),
        false /* generateCallbacks */,
        clientEvent.getEventId());
    event.setContext(memberId);
    
    // if this is a replayed operation we may already have a version tag
    event.setVersionTag(clientEvent.getVersionTag());

    try {
      basicUpdateEntryVersion(event);
    } finally {
      clientEvent.setVersionTag(event.getVersionTag());
      clientEvent.isConcurrencyConflict(event.isConcurrencyConflict());
      event.release();
    }
  }

  void basicUpdateEntryVersion(EntryEventImpl event) throws EntryNotFoundException {
    if (hasSeenEvent(event)) {
      if (DistributionManager.VERBOSE) {
        cache.getLoggerI18n().info(
          LocalizedStrings.DEBUG,
          "LR.basicDestroy: this cache has already seen this event "
          + event);
      }
      if (this.concurrencyChecksEnabled && event.getVersionTag() != null && !event.getVersionTag().isRecorded()) {
        getVersionVector().recordVersion((InternalDistributedMember) event.getDistributedMember(),
            event.getVersionTag(), event);
      }
      return;
    }

    getDataView().updateEntryVersion(event);
  }

  /**
   * Allows null as new value to accomodate create with a null value.
   *
   * @param event
   *          the event object for this operation, with the exception that the
   *          oldValue parameter is not yet filled in. The oldValue will be
   *          filled in by this operation.
   *
   * @param ifNew
   *          true if this operation must not overwrite an existing key
   * @param ifOld
   *          true if this operation must not create a new entry
   * @param lastModified
   *          the lastModified time to set with the value; if 0L, then the
   *          lastModified time will be set to now.
   * @param overwriteDestroyed
   *          true if okay to overwrite the DESTROYED token: when this is true
   *          has the following effect: even when ifNew is true will write over
   *          DESTROYED token when overwriteDestroyed is false and ifNew or
   *          ifOld is true then if the put doesn't occur because there is a
   *          DESTROYED token present then the entry flag blockedDestroyed is
   *          set.
   * @return false if ifNew is true and there is an existing key, or ifOld is
   *         true and there is no existing entry; otherwise return true.
   */
  boolean basicUpdate(final EntryEventImpl event,
                            final boolean ifNew,
                            final boolean ifOld,
                            final long lastModified,
                            final boolean overwriteDestroyed,
                            final boolean cacheWrite)
  throws TimeoutException,
        CacheWriterException {
    // check validity of key against keyConstraint
    if (this.keyConstraint != null) {
      if (!this.keyConstraint.isInstance(event.getKey()))
        throw new ClassCastException(LocalizedStrings.LocalRegion_KEY_0_DOES_NOT_SATISFY_KEYCONSTRAINT_1.toLocalizedString(new Object[] {event.getKey().getClass().getName(), this.keyConstraint.getName()}));
    }

    validateValue(event.basicGetNewValue());

    return getDataView(event).putEntry(event, ifNew, ifOld, null, false,
        cacheWrite, lastModified, overwriteDestroyed);
  }

  /**
   * Subclasses should reimplement if needed
   *
   * @see DistributedRegion#virtualPut(EntryEventImpl, boolean, boolean,
   *      Object, boolean, long, boolean)
   */
  boolean virtualPut(final EntryEventImpl event,
                     final boolean ifNew,
                     final boolean ifOld,
                     Object expectedOldValue,
                     boolean requireOldValue,
                     final long lastModified,
                     final boolean overwriteDestroyed)
  throws TimeoutException,
        CacheWriterException {
    if (!MemoryThresholds.isLowMemoryExceptionDisabled()) {
      checkIfAboveThreshold(event);
    }
    Operation originalOp = event.getOperation();
    RegionEntry oldEntry = null;
    
    try { 
      oldEntry = this.entries.basicPut(event,
                                 lastModified,
                                 ifNew,
                                 ifOld,
                                 expectedOldValue,
                                 requireOldValue,
                                 overwriteDestroyed);
    } catch (ConcurrentCacheModificationException e) {
      // this can happen in a client cache when another thread
      // managed to slip in its version info to the region entry before this
      // thread got around to doing so
      if (getLogWriterI18n().fineEnabled()) {
        getLogWriterI18n().fine("caught concurrent modification attempt when applying " + event);
      }
      notifyBridgeClients(event);
      return false;
    }

    // for EMPTY clients, see if a concurrent map operation had an entry on the server
    ServerRegionProxy mySRP = getServerProxy();

//     getLogWriterI18n().info(LocalizedStrings.DEBUG,
//        "LocalRegion.virtualPut oldEntry=" + oldEntry
//        +"; dataPolicy=" + this.dataPolicy
//        +"; originalOp=" + originalOp
//        +"; oldValue=" + event.getOldValue()
//        +"; event=" + event);

    if (mySRP != null && this.dataPolicy == DataPolicy.EMPTY) {
      if (originalOp == Operation.PUT_IF_ABSENT) {
        return !event.hasOldValue();
      }
      if (originalOp == Operation.REPLACE && !requireOldValue) {
        // LocalRegion.serverPut throws an EntryNotFoundException if the operation failed
        return true;
      }
    }
    
    return oldEntry != null;
  }
  
  /**
   * check to see if a LowMemoryException should be thrown for this event
   * @param evi
   * @throws LowMemoryException
   */
  public void checkIfAboveThreshold(final EntryEventImpl evi) throws LowMemoryException {
    if (evi == null) {
      checkIfAboveThreshold("UNKNOWN");
      return;
    }
    // Threshold check is performed elsewhere for putAll when there is a server proxy
    if (!(hasServerProxy() && evi.getOperation().isPutAll())
        && !evi.isOriginRemote() && memoryThresholdReached.get()) {
      throwLowMemoryException(evi.getKey());
    }
  }

  /**
   * Checks to see if the event should be rejected because of sick state either due to
   * exceeding local critical threshold or a remote member exceeding critical threshold 
   * @param key the key for the operation
   * @throws LowMemoryException if the target member for this operation is sick
   */
  private void checkIfAboveThreshold(final Object key) throws LowMemoryException{
    if (memoryThresholdReached.get()) {
      Set<DistributedMember> htrm = getMemoryThresholdReachedMembers();

      // #45603: trigger a background eviction since we're above the the critical 
      // threshold
      InternalResourceManager.getInternalResourceManager(cache).getHeapMonitor().updateStateAndSendEvent();

      Object[] prms = new Object[] {getFullPath(), key, htrm};
      throw new LowMemoryException(LocalizedStrings.ResourceManager_LOW_MEMORY_IN_0_FOR_PUT_1_MEMBER_2.toLocalizedString(prms),
          htrm);
    }
  }

  private void throwLowMemoryException(final Object key)
      throws LowMemoryException {
    Set<DistributedMember> htrm = getMemoryThresholdReachedMembers();
    Object[] prms = new Object[] { getFullPath(), key, htrm };
    throw new LowMemoryException(LocalizedStrings
        .ResourceManager_LOW_MEMORY_IN_0_FOR_PUT_1_MEMBER_2
            .toLocalizedString(prms), htrm);
  }

  /**
   * Perform a put without invoking callbacks or checking for transactions
   */
  /*public Object putNoCallbacks(Object key, Object value) {
    EntryEventImpl event = new EntryEventImpl(
        this, Operation.UPDATE, key,
        value,
        nullcallbackobj, false,
        getMyId(),
        true, true);
    event.setNewEventId(getCache().getDistributedSystem());
    boolean didPut = this.entries.basicPut(event, System.currentTimeMillis(),
        false, false, true, false) != null;
    if (didPut) {
      return event.getOldValue();
    }
    else {
      return null;
    }
  }*/

  /**
   * Allows null as new value to accomodate create with a null value.
   *
   * @param event
   *          the event object for this operation, with the exception that the
   *          oldValue parameter is not yet filled in. The oldValue will be
   *          filled in by this operation.
   * @param lastModified
   *          the lastModified time to set with the value; if 0L then the
   *          lastModified time will be set to now.
   * @return null if put not done; otherwise the put entry
   */
  protected RegionEntry basicPutEntry(final EntryEventImpl event,
      final long lastModified) throws TimeoutException, CacheWriterException {

    final TXStateInterface tx = discoverJTA(event);
    // Note we are doing a load or netsearch result so it seems like
    // we should set ifNew to true. The entry should not yet exist.
    // However since the non-tx code sets ifNew to false this code will also.
    final boolean ifNew = false;

    if (tx != null) {
      tx.txPutEntry(event, ifNew, false, false, null);
      return null;
    }
    else {
      RegionEntry oldEntry = this.entries.basicPut(event,
                                   lastModified,
                                   ifNew,
                                   false,  // ifOld
                                   null,   // expectedOldValue
                                   false,  // requireOldValue
                                   false); // overwriteDestroyed
      return oldEntry;
    }
  }

  protected long basicPutPart2(EntryEventImpl event, RegionEntry entry,
      boolean isInitialized, long lastModified,
      boolean clearConflict)
  {
    final boolean isNewKey = event.getOperation().isCreate();
    final boolean invokeCallbacks = !entry.isTombstone(); // put() is creating a tombstone

    if (isNewKey) {
      updateStatsForCreate();
    }
    final boolean lruRecentUse = event.isNetSearch() || event.isLoad(); // fix for bug 31102
    // the event may have a version timestamp that we need to use, so get the
    // event time to store in the entry
    long lastModifiedTime = event.getEventTime(lastModified);
    updateStatsForPut(entry, lastModifiedTime, lruRecentUse);
    if (!isProxy()) {
      //if (this.isUsedForPartitionedRegionBucket) {
      //  if (this.gfxdIndexManager != null) {
      //    this.gfxdIndexManager.onEvent(this, event, entry);
      //  }
      //}

      if (!clearConflict && this.indexManager != null) {
        try {
          if (!entry.isInvalid()) {
            this.indexManager.updateIndexes(entry,
                isNewKey ? IndexManager.ADD_ENTRY :
                  IndexManager.UPDATE_ENTRY,
                  isNewKey ? IndexProtocol.OTHER_OP :
                    IndexProtocol.AFTER_UPDATE_OP);
          }
        }
        catch (QueryException e) {
          throw new IndexMaintenanceException(e);
        }
      }
    }

    if (invokeCallbacks) {
      boolean doCallback = false;
      if (isInitialized) {
        // fix for #46662: skip wan notification during import
        // newwan moves notification to here from invokePutCallbacks
        if (event.isGenerateCallbacks()) {
          doCallback = true;
        }
      }
      else if (this.isUsedForPartitionedRegionBucket) {
        // invokePutCallbacks in BucketRegion will be more discriminating
        doCallback = true;
      }
      if (doCallback) {
        notifyGatewayHubs(event.getOperation().isUpdate()? EnumListenerEvent.AFTER_UPDATE
                                             : EnumListenerEvent.AFTER_CREATE, event);
        // Notify listeners
        if (event.getPutAllOperation() == null) {
          try {
            entry.dispatchListenerEvents(event);
          }
          catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            stopper.checkCancelInProgress(null);
          }
        }
      }
    }
    return lastModifiedTime;
  }

  /**
   * To lower latency, PRs generate the local filter rounting in
   * DistributedCacheOperation after message distribution and before waiting for
   * responses.
   * 
   * Warning: Even if you comment out bucket condition in following method,
   * getLocalRountingInfo() does NOT process CQs for bucket regions internally.
   * Check
   * {@link FilterProfile#getFilterRoutingInfoPart2(FilterRoutingInfo, CacheEvent)}
   * .
   * 
   * @param event
   */
  protected void generateLocalFilterRouting(InternalCacheEvent event) {
    boolean isEntryEvent = event.getOperation().isEntry();
    EntryEventImpl entryEvent = isEntryEvent? (EntryEventImpl)event : null;
    
    FilterProfile fp = this.getFilterProfile();
    FilterInfo routing = event.getLocalFilterInfo();
    boolean lockForCQ = false;
    Object re = null;
    if (fp != null && routing == null) {
      if (isEntryEvent && entryEvent.getRegionEntry() != null) {
        // bug #45520 we should either have the lock on the region entry
        // or the event was elided and CQ processing won't be done on it
        re = entryEvent.getRegionEntry();
        if (!entryEvent.isConcurrencyConflict()) {
          Assert.assertTrue(re != null);
          lockForCQ = true;
        }
      }
      if (isEntryEvent && getLogWriterI18n().fineEnabled()) {
        getLogWriterI18n().fine("getting local client routing.");
      }
      if (lockForCQ) {
        synchronized(re) {
          routing = fp.getLocalFilterRouting(event);
        }
      } else {
        routing = fp.getLocalFilterRouting(event);
      }
      event.setLocalFilterInfo(routing);
    }
    // bug #45520 - do not send CQ events to clients out of order
    if (routing != null  &&  event.getOperation().isEntry()
        &&  ((EntryEventImpl)event).isConcurrencyConflict()) {
      if (getLogWriterI18n().fineEnabled()) {
        getLogWriterI18n().fine("clearing CQ routing for event that's in conflict");
      }
      routing.clearCQRouting();
    }
  }

  /**
   * This notifies all WAN sites about updated timestamp on local site.
   * 
   * @param event
   */
  protected void notifyTimestampsToGateways(EntryEventImpl event) {
    
    // Create updateTimeStampEvent from event.
    EntryEventImpl updateTimeStampEvent = EntryEventImpl.createVersionTagHolder(event.getVersionTag());
    try {
    updateTimeStampEvent.setOperation(Operation.UPDATE_VERSION_STAMP);
    updateTimeStampEvent.setKeyInfo(event);
    updateTimeStampEvent.setGenerateCallbacks(false);
    updateTimeStampEvent.distributedMember = event.getDistributedMember();
    updateTimeStampEvent.setNewEventId(getSystem());
    
    
    if (event.getRegion() instanceof BucketRegion) {
      BucketRegion br = (BucketRegion)event.getRegion();
      PartitionedRegion pr = br.getPartitionedRegion();
      updateTimeStampEvent.setRegion(pr);
   
      // increment the tailKey for the event
      if (pr.isLocalParallelWanEnabled()) {
        br.handleWANEvent(updateTimeStampEvent);
      }
      
      if (pr.isInitialized()) {
        pr.notifyGatewayHubs(EnumListenerEvent.TIMESTAMP_UPDATE, updateTimeStampEvent);
      }
    } else {
      updateTimeStampEvent.setRegion(event.getRegion());
      notifyGatewayHubs(EnumListenerEvent.TIMESTAMP_UPDATE, updateTimeStampEvent);
    }
    } finally {
      updateTimeStampEvent.release();
    }
  }


  /**
   * Update CachePerfStats
   */
  private void updateStatsForCreate() {
    getCachePerfStats().incCreates();
  }

  public void basicPutPart3(EntryEventImpl event, RegionEntry entry,
      boolean isInitialized, long lastModified, boolean invokeCallbacks,
      boolean ifNew, boolean ifOld, Object expectedOldValue,
      boolean requireOldValue)
  {
    // We used to dispatch listener events here which is moved to part2 to be in RE lock #45520.
    if (invokeCallbacks) {
      if (event.getPutAllOperation() != null) {
          if (event.isLoadedFromHDFS()) {
            LogWriterI18n log = getCache().getLoggerI18n();
            if (log.fineEnabled()) {
              log.fine("Removing delta from the event for propagation of " +
                  "update operation as it is loaded from HDFS"
                  + event);
            }
            // removing the delta so that old value gets replaced by new 
            // value on secondary for HDFS loaded event as the row is not 
            // loaded on the secondary so delta can not be applied 
            event.removeDelta();
          }
          event.getPutAllOperation().addEntry(event);
      }
    }
  }

  public void invokePutCallbacks(final EnumListenerEvent eventType,
      final EntryEventImpl event, final boolean callDispatchListenerEvent, boolean notifyGateways) {

    // disallow callbacks on import
    if (!event.isGenerateCallbacks()) {
      return;
    }
    
    // Notify bridge clients (if this is a BridgeServer)
//    if (getLogWriterI18n().fineEnabled()) {
//      getLogWriterI18n().fine("invoking Put callbacks for " + event + " dispatchListeners=" + callDispatchListenerEvent
//        + " notifyGateways=" + notifyGateways);
//    }
    Operation op = event.getOperation();
    // The spec for ConcurrentMap support requires that operations be mapped
    // to non-CM counterparts
    if (op == Operation.PUT_IF_ABSENT) {
      event.setOperation(Operation.CREATE);
    } else if (op == Operation.REPLACE) {
      event.setOperation(Operation.UPDATE);
    }
    event.setEventType(eventType);
    notifyBridgeClients(event);
    if (notifyGateways) {
      notifyGatewayHubs(eventType, event);
    }
    if(callDispatchListenerEvent){
      dispatchListenerEvent(eventType, event);
    }
  }

  /**
   * @see DistributedRegion#postUpdate(EntryEventImpl, long)
   * @param event
   * @param lastModifiedTime
   */
  protected void postUpdate(EntryEventImpl event, long lastModifiedTime)
  {
  }
  
  /**
   * retrieve a deep copy of the Region's event state.  This is used
   * for getInitialImage.  The result is installed in the receiver of
   * the image.
   */
  public Map<? extends DataSerializable, ? extends DataSerializable> getEventState() {
    if (this.eventTracker != null) {
      return this.eventTracker.getState();
    }
    else {
      return null;
    }
  }
  
  /**
   * Record the event state encapsulated in the given Map.<p>
   * This is intended for state transfer during GII.
   * @param provider the member that provided this state
   * @param state a Map obtained from getEventState()
   */
  protected void recordEventState(InternalDistributedMember provider, Map state) {
    if (this.eventTracker != null) {
      this.eventTracker.recordState(provider, state);
    }
  }
  
  /**
   * generate version tag if it does not exist and set it into the event.
   * @param event
   * @param entry
   */
  public void generateAndSetVersionTag(InternalCacheEvent event,
      RegionEntry entry) {
    if (entry != null && event.getOperation().isEntry()) {
      generateAndSetVersionTag((EntryEventImpl)event, entry);
    }
  }

  /**
   * generate version tag if it does not exist and set it into the event.
   * @param entryEvent
   * @param entry
   */
  public final void generateAndSetVersionTag(EntryEventImpl entryEvent,
      RegionEntry entry) {
//      InternalDistributedSystem.getLoggerI18n().info(LocalizedStrings.DEBUG, "generateAndSetVersionTag entry="+entry.toString(), new Exception("stack trace"));
      if (!entryEvent.isOriginRemote() && shouldGenerateVersionTag(entry, entryEvent)) {
        boolean eventHasDelta = (getSystem().getConfig().getDeltaPropagation()
            && !this.scope.isDistributedNoAck()
            && entryEvent.getDeltaBytes() != null);
        VersionTag v = entry.generateVersionTag(null, false, eventHasDelta,
            this, entryEvent);
        if (v != null) {
          LogWriterI18n log = getLogWriterI18n();
          if (log.fineEnabled()) { log.fine("generated version tag " + v + " for " + entryEvent.getKey()); } // + " for " + entryEvent/*.getKey()*/ + " in region " + this.getName()); }
        }
      }
  }

  /**
   * record the event's sequenceId in Region's event state to prevent replay.
   * @param event
   */
  public void recordEvent(InternalCacheEvent event) {
    if (this.eventTracker != null) {
      this.eventTracker.recordEvent(event);
    }
  }
  
  /**
   * has the Region's event state seen this event?
   * @param event
   * @return true if the Region's event state has seen the event
   */
   public boolean hasSeenEvent(EntryEventImpl event) {
     boolean isDup = false;
     if (this.eventTracker != null) {
       // bug 41289 - wait for event tracker to be initialized before checkin
       // so that an operation inteded for a previous version of a bucket
       // is not prematurely applied to a new version of the bucket
       if (this.isUsedForPartitionedRegionBucket()) {
         try {
           this.eventTracker.waitOnInitialization();
         } catch (InterruptedException ie) {
           this.stopper.checkCancelInProgress(ie);
           Thread.currentThread().interrupt();
         }
       }
       isDup = this.eventTracker.hasSeenEvent(event);
       // don't clobber existing posDup flag e.g. set from GemFireXD client
       if (isDup) {
         event.setPossibleDuplicate(true);
         if (this.concurrencyChecksEnabled && event.getVersionTag() == null) {
           event.setVersionTag(findVersionTagForClientEvent(event.getEventId()));
         }
       } else {
         // bug #48205 - a retried PR operation may already have a version assigned to it
         // in another VM 
         if (event.isPossibleDuplicate()
             && event.getRegion().concurrencyChecksEnabled
             && (event.getVersionTag() == null)
             && (event.getEventId() != null)) {
           VersionTag tag = FindVersionTagOperation.findVersionTag(event.getRegion(), event.getEventId(), event.getOperation().isPutAll());
           event.setVersionTag(tag);
         }
       }
     } 
     return isDup;
   }
   
   /**
    * tries to find the version tag for a replayed client event
    * @param eventId
    * @return the version tag, if known.  Null if not
    */
   public VersionTag findVersionTagForClientEvent(EventID eventId) {
     if (this.eventTracker != null) {
       return this.eventTracker.findVersionTag(eventId);
     }
     return null;
   }
   
   /**
    * tries to find the version tag for a replayed client event
    * @param eventId
    * @return the version tag, if known.  Null if not
    */
   public VersionTag findVersionTagForClientPutAll(EventID eventId) {
     if (eventId == null) {
       return null;
     }
     if (this.eventTracker != null) {
       return this.eventTracker.findVersionTagForPutAll(eventId);
     }
     return null;
   }

   /**
    * has the Region's event state seen this event?  Most checks should use
    * the method that takes an Event, not an ID, but with transactions we
    * do not have an event at the time the check needs to be made.  Consequently,
    * this method may cause events to be recorded that would otherwise be
    * ignored.
    * @param eventID the identifier of the event
    * @return true if the Region's event state has seen the event
    */
    public boolean hasSeenEvent(EventID eventID) {
      if (eventID == null) {
        return false;
      }
      boolean isDup = false;
      if (this.eventTracker != null) {
        // bug 41289 - wait for event tracker to be initialized before checkin
        // so that an operation intended for a previous version of a bucket
        // is not prematurely applied to a new version of the bucket
        if (this.isUsedForPartitionedRegionBucket()) {
          try {
            this.eventTracker.waitOnInitialization();
          } catch (InterruptedException ie) {
            this.stopper.checkCancelInProgress(ie);
            Thread.currentThread().interrupt();
          }
        }
        isDup = this.eventTracker.hasSeenEvent(eventID, null);
      } 
      return isDup;
    }

  /**
   * A routine to provide synchronization running based on <memberShipID, threadID> 
   * of the requesting client for the region's event state
   * @param r - a Runnable to wrap the processing of putAllMsg
   * @param eventID - the base event ID of the putAllMsg
   *
   * @since 5.7
   */
  public void syncPutAll(final TXStateInterface tx, Runnable r, EventID eventID) {
    if (this.eventTracker != null && (tx == null || tx.isSnapshot())) {
      this.eventTracker.syncPutAll(r, eventID);
    }
    else {
      r.run();
    }
  }

  public void recordPutAllStart(ThreadIdentifier membershipID) {
    if (this.eventTracker != null && !isTX()) {
      this.eventTracker.recordPutAllStart(membershipID);
    }
  }
  
  final protected void notifyBridgeClients(CacheEvent event)
  {
    int numBS = getCache().getCacheServers().size();

    // #Bugfix 37518: In case of localOperations no need to notify clients.
    if (event.getOperation().isLocal() || numBS == 0) {
      return;
    }
    // Return if the inhibit all notifications flag is set
    if (event instanceof EntryEventImpl) {
      if (((EntryEventImpl)event).inhibitAllNotifications()) {
        if (this.cache.getLoggerI18n().fineEnabled())
          this.cache.getLoggerI18n().fine("Notification inhibited for key " + event.toString());
        return;
      }
    }

    if (shouldNotifyBridgeClients()) {
      
      LogWriterI18n logger = getCache().getLoggerI18n();
      if (logger.fineEnabled() && numBS > 0) {
        logger.fine(this.getName() + ": notifying " + numBS
            + " bridge servers of event: " + event);
      }
      
      Operation op = event.getOperation();
      if (event.getOperation().isEntry()) {
        EntryEventImpl e = (EntryEventImpl)event;
        if (e.getEventType() == null) {
          if (op.isCreate()) {
            e.setEventType(EnumListenerEvent.AFTER_CREATE);
          } else if (op.isUpdate()) {
            e.setEventType(EnumListenerEvent.AFTER_UPDATE);
          } else if (op.isDestroy()) {
            e.setEventType(EnumListenerEvent.AFTER_DESTROY);
          } else if (op.isInvalidate()) {
            e.setEventType(EnumListenerEvent.AFTER_INVALIDATE);
          } else {
            throw new IllegalStateException("event is missing client notification eventType: " + e);
          }
        }
      }

      InternalCacheEvent ice = (InternalCacheEvent)event;
      if (!this.isUsedForPartitionedRegionBucket()) {
        generateLocalFilterRouting(ice);
      }

      CacheClientNotifier.notifyClients(ice);
    }
  }

  protected void notifyGatewayHubs(EnumListenerEvent operation,
      EntryEventImpl event) {
    
    if (event.isConcurrencyConflict()) { // usually concurrent cache modification problem
      return;
    }
    
    // Return if the inhibit all notifications flag is set
    if (event.inhibitAllNotifications()){
      if (this.cache.getLoggerI18n().fineEnabled())
        this.cache.getLoggerI18n().fine("Notification inhibited for key " + event.toString());
        return;
    }
    
    if (!event.getOperation().isLocal()) {
      if (shouldNotifyGatewayHub()) {
        if (event.getOperation() != Operation.UPDATE_VERSION_STAMP) {
          // Distribute to each GatewayHub
          // GemFireCache gc = (GemFireCache)getCache();
          // synchronized (gc.allGatewayHubsLock) {
          // TODO do we really have to use an iterator here?
          for (GatewayHub hub:  getCache().getGatewayHubs()) {
            GatewayHubImpl hubImpl = (GatewayHubImpl)hub;
            if (shouldNotifyGatewayHub(hubImpl)) {
              hubImpl.distribute(operation, event);
            }
          } // for
        }
      }

      Set<String> allGatewaySenderIds;
      if (event.getOperation() == Operation.UPDATE_VERSION_STAMP) {
        allGatewaySenderIds = getGatewaySenderIds();
      } else {
        allGatewaySenderIds = getAllGatewaySenderIds();
      }
      List<Integer> allRemoteDSIds = getRemoteDsIds(allGatewaySenderIds);

      if (allRemoteDSIds != null) {
        for (GatewaySender sender : getCache().getAllGatewaySenders()) {
          if (!isPdxTypesRegion()) {
            if (allGatewaySenderIds.contains(sender.getId())) {
              //TODO: This is a BUG. Why return and not continue?
              if((!this.getDataPolicy().withStorage()) && sender.isParallel()){
                return;
              }
              if (getLogWriterI18n().fineEnabled()) {
                getLogWriterI18n().fine(
                    "Notifying the GatewaySender : " + sender.getId());
              }
              ((AbstractGatewaySender)sender).distribute(operation, event,
                  allRemoteDSIds);
            }
          }
        }
      }
    }
  }

  /**
   * @param cacheWrite
   *          if true, then we're just cleaning up the local cache and calling
   *          listeners,
   * @see DistributedRegion#basicDestroyRegion(RegionEventImpl, boolean,
   *      boolean, boolean)
   */
  void basicDestroyRegion(RegionEventImpl event, boolean cacheWrite)
      throws CacheWriterException, TimeoutException
  {
    basicDestroyRegion(event, cacheWrite, true, true);
  }

  void basicDestroyRegion(RegionEventImpl event, boolean cacheWrite,
      boolean lock, boolean callbackEvents) throws CacheWriterException,
      TimeoutException
  {
    HashSet eventSet = null;
    final TXStateInterface tx = this.cache.getTxManager().internalSuspend();
    try {
      boolean acquiredLock = false;
      if (lock) {
        try {
          acquireDestroyLock();
          acquiredLock = true;
        }
        catch (CancelException e) {
          // ignore
          cache.getLogger().fine(
              "basicDestroyRegion: acquireDestroyLock failed due to cache closure, region = " 
              + getFullPath());
        }
      }
      try { // maintain destroy lock and TXStateInterface
        // I moved checkRegionDestroyed up out of the following
        // try block because it does not seem correct to deliver
        // a destroy event to the clients of the region was already
        // destroyed on the server.
        checkRegionDestroyed(false);
        boolean cancelledByCacheWriterException = false; // see bug 47736
        try { // ensure that destroy events are dispatched

          if (this instanceof PartitionedRegion
              && !((PartitionedRegion)this).getParallelGatewaySenderIds()
                  .isEmpty()) {
            ((PartitionedRegion)this).destroyParallelGatewaySenderRegion(event.getOperation(),
                cacheWrite, lock, callbackEvents);
          }

          if (this.parentRegion != null) {
            // "Bubble up" the cache statistics to parent if this regions are more
            // recent
            this.parentRegion.updateStats();
          }

          getCache().removeRegionFromOldEntryMap(this.getFullPath());
        
          try {
            eventSet = callbackEvents ? new HashSet() : null;
            this.destroyedSubregionSerialNumbers = collectSubregionSerialNumbers();
            recursiveDestroyRegion(eventSet, event, cacheWrite);
          }
          catch (CancelException e) {
            // This should be properly caught and ignored; if we see this there is
            // a serious problem.
            if (!cache.forcedDisconnect()) {
              cache.getLoggerI18n().warning(
                LocalizedStrings.LocalRegion_RECURSIVEDESTROYREGION_RECURSION_FAILED_DUE_TO_CACHE_CLOSURE_REGION_0,
                getFullPath(), e);
            }
          } catch (CacheWriterException cwe) {
            cancelledByCacheWriterException = true;
            throw cwe;
          }
          
          // at this point all subregions are destroyed and this region
          // has been marked as destroyed and postDestroyRegion has been
          // called for each region. The only detail left is
          // unhooking this region from the parent subregion map, and
          // sending listener events
          Assert.assertTrue(this.isDestroyed);
          
          /**
           * Added for M&M : At this point we can safely call ResourceEvent
           * to remove the region artifacts From Management Layer
           **/
          if (!isInternalRegion()) {
            InternalDistributedSystem system = this.cache
                .getDistributedSystem();
            system.handleResourceEvent(ResourceEvent.REGION_REMOVE, this);
          }

          try {
            LocalRegion parent = this.parentRegion;
            if (parent == null) {
              this.cache.removeRoot(this);
            }
            else {
              parent.subregions.remove(this.regionName, this);
            }
          }
          catch (CancelException e) {
            // I don't think this should ever happens:  bulletproofing for bug 39454
            if (!cache.forcedDisconnect()) {
              cache.getLoggerI18n().warning(
                LocalizedStrings.LocalRegion_BASICDESTROYREGION_PARENT_REMOVAL_FAILED_DUE_TO_CACHE_CLOSURE_REGION_0, 
                getFullPath(), e);
            }
          }
        }  // ensure that destroy events are dispatched
        finally {
  //        cache.getLogger().info("basicDestroyRegion: " + getFullPath() + 
  //            ": generating events, callbackEvents = " + callbackEvents 
  //            + " events: " + eventSet);
  
          if (!cancelledByCacheWriterException) {
            // We only need to notify bridgeClients of the top level region destroy
            // which it will take and do a localRegionDestroy.
            // So we pass it event and NOT eventSet
            event.setEventType(EnumListenerEvent.AFTER_REGION_DESTROY);
            notifyBridgeClients(event);
          }
          // call sendPendingRegionDestroyEvents even if cancelledByCacheWriterException
          // since some of the destroys happened.
          if (eventSet != null && callbackEvents) {
            try {
              sendPendingRegionDestroyEvents(eventSet);
            }
            catch (CancelException e) {
              // ignore, we're mute.
            }
          }
        }
      }  // maintain destroy lock and TXStateInterface
      finally {
        if (acquiredLock) {
          try {
            releaseDestroyLock();
          }
          catch (CancelException e) {
            // ignore
          }
        }
      }
    } finally {
      this.cache.getTxManager().resume(tx);
    }
  }

  protected void distributeDestroyRegion(RegionEventImpl event, boolean notifyOfRegionDeparture) {
  }

  public static final float DEFAULT_HEAPLRU_EVICTION_HEAP_PERCENTAGE = 80.0f;
  /**
   * Called after this region has been completely created
   *
   * @since 5.0
   *
   * @see DistributedRegion#postDestroyRegion(boolean, RegionEventImpl)
   */
  protected void postCreateRegion()
  {
    if (getEvictionAttributes().getAlgorithm().isLRUHeap()) {
      final LogWriter logWriter = cache.getLogger();
      float evictionPercentage = DEFAULT_HEAPLRU_EVICTION_HEAP_PERCENTAGE;
      // This is new to 6.5. If a heap lru region is created
      // we make sure that the eviction percentage is enabled.
      InternalResourceManager rm = this.cache.getResourceManager();
      if (!getEnableOffHeapMemory()) {
        if (!rm.getHeapMonitor().hasEvictionThreshold()) {
          float criticalPercentage = rm.getCriticalHeapPercentage();
          if (criticalPercentage > 0.0f) {
            if (criticalPercentage >= 10.f) {
              evictionPercentage = criticalPercentage - 5.0f;
            } else {
              evictionPercentage = criticalPercentage;
            }
          }
          rm.setEvictionHeapPercentage(evictionPercentage);
          if (logWriter.fineEnabled()) {
            logWriter.fine("Enabled heap eviction at " + evictionPercentage + " percent for LRU region");
          }
        }
      } else {
        if (!rm.getOffHeapMonitor().hasEvictionThreshold()) {
          float criticalPercentage = rm.getCriticalOffHeapPercentage();
          if (criticalPercentage > 0.0f) {
            if (criticalPercentage >= 10.f) {
              evictionPercentage = criticalPercentage - 5.0f;
            } else {
              evictionPercentage = criticalPercentage;
            }
          }
          rm.setEvictionOffHeapPercentage(evictionPercentage);
          if (logWriter.fineEnabled()) {
            logWriter.fine("Enabled off-heap eviction at " + evictionPercentage + " percent for LRU region");
          }
        }
      }
    }
      
    if (!isInternalRegion()) {
      getCachePerfStats().incRegions(1);
      if (getMembershipAttributes().hasRequiredRoles()) {
        getCachePerfStats().incReliableRegions(1);
      }
    }

    if (hasListener()) {
      RegionEventImpl event = new RegionEventImpl(this,
          Operation.REGION_CREATE, null, false, getMyId());
      dispatchListenerEvent(EnumListenerEvent.AFTER_REGION_CREATE, event);
    }
    releaseAfterRegionCreateEventLatch();
    SystemMemberCacheEventProcessor.send(getCache(), this,
        Operation.REGION_CREATE);
    initializingRegion.set(null);
  }

  /**
   * notify region membership listeners of the initial membership
   * @param listeners an array of listeners to notify
   */
  public void notifyOfInitialMembers(CacheListener[] listeners, Set others) {
    if (listeners != null) {
      for (int i = 0; i < listeners.length; i++) {
        if (listeners[i] instanceof RegionMembershipListener) {
          RegionMembershipListener rml = (RegionMembershipListener)listeners[i];
          try {
            DistributedMember[] otherDms = new DistributedMember[others
                .size()];
            others.toArray(otherDms);
            rml.initialMembers(this, otherDms);
          }
          catch (Throwable t) {
            Error err;
            if (t instanceof Error && SystemFailure.isJVMFailureError(
                err = (Error)t)) {
              SystemFailure.initiateFailure(err);
              // If this ever returns, rethrow the error. We're poisoned
              // now, so don't let this thread continue.
              throw err;
            }
            // Whenever you catch Error or Throwable, you must also
            // check for fatal JVM error (see above).  However, there is
            // _still_ a possibility that you are dealing with a cascading
            // error condition, so you also need to check to see if the JVM
            // is still usable:
            SystemFailure.checkFailure();
            getCache().getLoggerI18n().error(
                LocalizedStrings.DistributedRegion_EXCEPTION_OCCURRED_IN_REGIONMEMBERSHIPLISTENER,
                t);
          }
        }
      }
    }
  }

  /**
   * This method is invoked after isDestroyed has been set to true
   */
  protected void postDestroyRegion(boolean destroyDiskRegion,
      RegionEventImpl event)
  {
    if (this.diskRegion != null) {
      if (destroyDiskRegion) {
        this.diskRegion.endDestroy(this);
      }
      else {
        this.diskRegion.close(this);
      }
    }
    if (this.versionVector != null) {
      try {
        this.cache.getDistributionManager().removeMembershipListener(this.versionVector);
      } catch (CancelException e) {
        // ignore: cache close will remove the membership listener
      }
    }
  }

  /**
   * @param cacheWrite
   *          true if cacheWrite should be performed or false if cacheWrite
   *          should not be performed
   * @see DistributedRegion#basicDestroy(EntryEventImpl, boolean, Object)
   */
  void basicDestroy(final EntryEventImpl event,
                       final boolean cacheWrite,
                       Object expectedOldValue)
  throws EntryNotFoundException, CacheWriterException, TimeoutException {

    if (!event.isOriginRemote()) {
      checkIfReplicatedAndLocalDestroy(event);
    }

    final TXStateInterface tx = discoverJTA(event);

    if (hasSeenEvent(event)) {
      assert tx == null;
      if (DistributionManager.VERBOSE) {
        cache.getLoggerI18n().info(
          LocalizedStrings.DEBUG,
          "LR.basicDestroy: this cache has already seen this event "
          + event);
      }
      if (this.concurrencyChecksEnabled && event.getVersionTag() != null && !event.getVersionTag().isRecorded()) {
        getVersionVector().recordVersion((InternalDistributedMember) event.getDistributedMember(),
            event.getVersionTag(), event);
      }
      // Bug 49449: When client retried and returned with hasSeenEvent for both LR and DR, the server should still 
      // notifyGatewayHubs even the event could be duplicated in gateway queues1 
      notifyGatewayHubs(EnumListenerEvent.AFTER_DESTROY, event);
      return;
    }

    getDataView(tx).destroyExistingEntry(event, cacheWrite, expectedOldValue);
  }

  /**
   * Do the expensive work of discovering an existing JTA transaction
   * Only needs to be called at Region.Entry entry points e.g. Region.put, Region.invalidate, etc.
   * @since tx
   */
  public final TXStateInterface discoverJTA() {
    if (this.supportsTX) {
      return getJTAEnlistedTX();
    }
    return null;
  }

  public final TXStateInterface discoverJTA(final EntryEventImpl event) {
    TXStateInterface tx = event.txState;
    if (tx != TXStateProxy.TX_NOT_SET) {
      return tx;
    }
    tx = discoverJTA();
    event.setTXState(tx);

    return tx;
  }

  /**
   * @return true if a transaction is in process
   * @since tx 
   */
  public final boolean isTX() {
    return getTXState() != null;
  }

  /**
   * @param expectedOldValue if this is non-null, only destroy if key exists
   *        and old value is equal to expectedOldValue
   * @return true if a the destroy was done; false if it was not needed
   */
  final boolean mapDestroy(final EntryEventImpl event,
                     final boolean cacheWrite,
                     final boolean isEviction,
                     Object expectedOldValue)
  throws CacheWriterException, EntryNotFoundException, TimeoutException {
    final boolean inGII = lockGII();
    try { // make sure unlockGII is called for bug 40001
      return mapDestroy(event, cacheWrite, isEviction, expectedOldValue, inGII, false);
    } finally {
      if (inGII) {
        unlockGII();
      }
    }
  }
  
  final boolean mapDestroy(final EntryEventImpl event,
      final boolean cacheWrite,
      final boolean isEviction,
      Object expectedOldValue,
      boolean needTokensForGII,
      boolean removeRecoveredEntry) {
    //When register interest is in progress ,
    // We should not remove the key from the
    // region and instead replace the value
    // in the map with a DESTROYED token
    final boolean inRI = !needTokensForGII
    && !event.isFromRILocalDestroy()
    && lockRIReadLock();
    // at this point riCnt is guaranteed to be correct and we know for sure
    // whether a RI is in progress and that riCnt will not change during this
    // destroy operation
    try {
      final boolean needRIDestroyToken = inRI && (this.riCnt > 0);
      final boolean inTokenMode = needTokensForGII || needRIDestroyToken;
      // the following will call basicDestroyPart2 at the correct moment
      return this.entries.destroy(event,
          inTokenMode,
          needRIDestroyToken,
          cacheWrite,
          isEviction,
          expectedOldValue,
          removeRecoveredEntry);
    } catch (ConcurrentCacheModificationException e) {
      // this can happen in a client/server cache when another thread
      // managed to slip in its version info to the region entry before this
      // thread got around to doing so
      if (getLogWriterI18n().fineEnabled()) {
        getLogWriterI18n().fine("caught concurrent modification attempt when applying " + event);
      }
      // Notify clients only if its NOT a gateway event.
      if (event.getVersionTag() != null && !event.getVersionTag().isGatewayTag()) {
        notifyBridgeClients(event);
      }
      return true; // event was elided
    } catch(DiskAccessException dae) {
      handleDiskAccessException(dae, true/* stop bridge servers*/);
      throw dae;
    }
    finally {
      if (inRI) {
        unlockRIReadLock();
      }
    }
  }

  /**
   * Return true if dae was caused by a RegionDestroyedException.
   * This was added for bug 39603.
   */
  static boolean causedByRDE(DiskAccessException dae) {
    boolean result = false;
    if (dae != null) {
      Throwable cause = dae.getCause();
      while (cause != null) {
        if (cause instanceof RegionDestroyedException) {
          result = true;
          break;
        }
        cause = cause.getCause();
      }
    }
    return result;
  }
  
  public void handleDiskAccessException(DiskAccessException dae, boolean stopBridgeServers) {
    handleDiskAccessException(dae, stopBridgeServers, false);
  }
  //Asif:To Fix bug 39079, we are locally destroying the region, the 
  //destruction takes place here & not at DiskRegion or AbstractOplogDiskRegionEntry level
  //is to eliminate any possibility of deadlocks ,as it is an entry operation thread
  //which is implictly closing the region & stopping the Servers 
   /**
    * @param dae DiskAccessException encountered by the thread
    * @param stopBridgeServers boolean which indicates that apart from destroying the
    * region should the bridge servers running, if any, should also be stopped or not.
    * So if the DiskAccessException occurs during region creation ( while recovering
    * from the  disk or GII) , then the servers are not stopped as the clients would
    * not have registered any interest. But if the Exception occurs during entry operations
    * then the bridge servers need to be stopped.
    * @param duringInitialization indicates that this exception occurred during
    * region initialization. Instead of closing the region here, we rely on the
    * region initialization to clean things up.
    * @see DistributedRegion#initialize(InputStream, InternalDistributedMember, InternalRegionArguments)
    * @see LocalRegion#initialize(InputStream, InternalDistributedMember, InternalRegionArguments)
    * @see InitialImageOperation#processChunk
    */
  public void handleDiskAccessException(final DiskAccessException dae,
      boolean stopBridgeServers, boolean duringInitialization) {
    // if the exception is from a remote node then nothing needs to be done
    // on this node (#44625)
    if (dae != null && dae.isRemote()) {
      return;
    }
    LogWriterI18n logger = getCache().getLoggerI18n();
    // Locally Destroy the region
    boolean gotRDE = false;
    if (causedByRDE(dae)) {
      gotRDE = true;
    } else {
      logger.error(LocalizedStrings.LocalRegion_A_DISKACCESSEXCEPTION_HAS_OCCURED_WHILE_WRITING_TO_THE_DISK_FOR_REGION_0_THE_REGION_WILL_BE_CLOSED, 
                   this.fullPath, dae);

      if (stopBridgeServers && !gotRDE && !isDestroyed()) {
        logger.info(LocalizedStrings
            .LocalRegion_ATTEMPTING_TO_CLOSE_THE_BRIDGESERVERS_TO_INDUCE_FAILOVER_OF_THE_CLIENTS);
        try {
          this.cache.stopServers();
          // also close GemFireXD network servers to induce failover (#45651)
          final StaticSystemCallbacks sysCb = GemFireCacheImpl.FactoryStatics.systemCallbacks;
          if (sysCb != null) {
            sysCb.stopNetworkServers();
          }
          logger
              .info(LocalizedStrings.LocalRegion_BRIDGESERVERS_STOPPED_SUCCESSFULLY);
        } catch (Exception e) {
          logger.error(LocalizedStrings
              .LocalRegion_THE_WAS_A_PROBLEM_IN_STOPPING_BRIDGESERVERS_FAILOVER_OF_CLIENTS_IS_SUSPECT, e);
        }
      }

      if (!duringInitialization) {
        try {
          RegionEventImpl event = new RegionEventImpl(this,
              Operation.REGION_CLOSE, null, false, getMyId(),
              generateEventID() /* generate EventID */);
          // set the disk exception for callbacks (#43784)
          event.setDiskException(dae);
          this.close(event); // changed to close for bug 40572
          logger.info(LocalizedStrings.LocalRegion_REGION_CLOSED_SUCCESSFULLY);
        } catch (RegionDestroyedException rde) {
          gotRDE = true;
        } catch (Exception e) {
          logger.error(LocalizedStrings
              .LocalRegion_AN_EXCEPTION_OCCURED_WHILE_DESTROYING_THE_REGION_LOCALLY, e);
        }
      }
    }
  }

  void expireDestroy(final EntryEventImpl event,
      final boolean cacheWrite) {
    basicDestroy(event, cacheWrite, null);
  }
  
  void expireInvalidate(final EntryEventImpl event) {
    basicInvalidate(event);
  }

  /**
   * Creates an event for EVICT_DESTROY operations.
   * It is intended that this method be overridden to allow for special 
   * handling of Partitioned Regions.
   * @param key - the key that this event is related to 
   * @return an event for EVICT_DESTROY
   */
  protected EntryEventImpl generateEvictDestroyEvent(final Object key) {
    EntryEventImpl event = EntryEventImpl.create(
        this, Operation.EVICT_DESTROY, key, null/* newValue */,
        null, false, getMyId());
    // Fix for bug#36963
    if (generateEventID()) {
      event.setNewEventId(cache.getDistributedSystem());
    }
    event.setFetchFromHDFS(false);
    return event;
  }
  
  protected EntryEventImpl generateCustomEvictDestroyEvent(final Object key) {
    EntryEventImpl event = EntryEventImpl.create(
        this, Operation.CUSTOM_EVICT_DESTROY, key, null/* newValue */,
        null, false, getMyId());
    // Fix for bug#36963
    if (generateEventID()) {
      event.setNewEventId(cache.getDistributedSystem());
    }
    event.setFetchFromHDFS(false);
    return event;
  }
  
  /**
   * @return true if the evict destroy was done; false if it was not needed
   */
  boolean evictDestroy(LRUEntry entry)
  {
    
    checkReadiness();
    final EntryEventImpl event = 
          generateEvictDestroyEvent(entry.getKeyCopy());
    try {
      return mapDestroy(event,
                        false, // cacheWrite
                        true,  // isEviction
                        null); // expectedOldValue
    }
    catch (CacheWriterException error) {
      throw new Error(LocalizedStrings.LocalRegion_CACHE_WRITER_SHOULD_NOT_HAVE_BEEN_CALLED_FOR_EVICTDESTROY.toLocalizedString(), error);
    }
    catch (TimeoutException anotherError) {
      throw new Error(LocalizedStrings.LocalRegion_NO_DISTRIBUTED_LOCK_SHOULD_HAVE_BEEN_ATTEMPTED_FOR_EVICTDESTROY.toLocalizedString(), anotherError);
    }
    catch (EntryNotFoundException yetAnotherError) {
      throw new Error(LocalizedStrings.LocalRegion_ENTRYNOTFOUNDEXCEPTION_SHOULD_BE_MASKED_FOR_EVICTDESTROY.toLocalizedString(), yetAnotherError);
    } finally {
      event.release();
    }
  }
  
  /**
   * Called by lower levels {@link AbstractRegionMap} while holding the entry
   * synchronization <bold>and</bold> while the entry remains in the map. Once
   * the entry is removed from the map, then other operations synchronize on a
   * new entry, allow for ordering problems between
   * {@link #create(Object, Object, Object)} and
   * {@link #destroy(Object, Object)} operations.
   *
   * @param entry the Region entry being destroyed
   * @param event
   *          the event describing the destroy operation
   * @since 5.1
   */
  protected void basicDestroyBeforeRemoval(RegionEntry entry, EntryEventImpl event)
  {
  }

  /**
   * Called by lower levels, while still holding the write sync lock, and the
   * low level has completed its part of the basic destroy
   */
  void basicDestroyPart2(RegionEntry re, EntryEventImpl event,
      boolean inTokenMode, boolean conflictWithClear, boolean duringRI, boolean invokeCallbacks)
  {
    final LogWriterI18n logger = getCache().getLoggerI18n();

    if (logger.finerEnabled() && !(this instanceof HARegion)) {
      logger.finer("basicDestroyPart2(inTokenMode="+inTokenMode+",conflictWithClear="+conflictWithClear+",duringRI="+duringRI+") event=" + event);
    }
    VersionTag v = event.getVersionTag();

    /**
     * destroys that are not part of the cleaning out of keys prior to a register-interest
     * are marked with Tombstones instead of Destroyed tokens so that they are not
     * reaped after the RI completes.  RI does not create Tombstones because it
     * would flood the TombstoneService with unnecessary work.
     */
    if (inTokenMode && !(this.concurrencyChecksEnabled || event.isFromRILocalDestroy())) {
      if (re.isDestroyed()) {
        getImageState().addDestroyedEntry(event.getKey());
        if (logger.finerEnabled() && !(this instanceof HARegion)) {
          logger.finer("basicDestroy: " + event.getKey() + "--> Token.DESTROYED");
        }
      }
    }
    else {
      if (this.concurrencyChecksEnabled && logger.fineEnabled() && !(this instanceof HARegion)) {
        logger.fine("basicDestroyPart2: " + event.getKey() + (v==null? ", no version tag" : ", version="+v));
      }
    }
    /* this is too late to do index maintenance with a CompactRangeIndex
    because we need to have the old value still intact. At this point
    the old value has already be replaced with a destroyed token.
    if (!isProxy() && !conflictWithClear) {
      if (this.indexManager != null) {
        try {
          this.indexManager.updateIndexes(re, IndexManager.REMOVE_ENTRY);
        }
        catch (QueryException e) {
          throw new IndexMaintenanceException(e);
        }
      }
    }*/

    notifyGatewayHubs(EnumListenerEvent.AFTER_DESTROY, event);
    
    // invoke callbacks if initialized and told to do so, or if this
    // is a bucket in a partitioned region
    if (invokeCallbacks) {
      if ((isInitialized() && (!inTokenMode || duringRI))
          || this.isUsedForPartitionedRegionBucket) {
        try {
          re.dispatchListenerEvents(event);
        }
        catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          stopper.checkCancelInProgress(null);
          return;
        }
      } else {
        event.callbacksInvoked(true);
      }
    }
  }

  /**
   * distribution and callback notification are done in part2 inside
   * entry lock for maintaining the order of events.
   */
  void basicDestroyPart3(RegionEntry re, EntryEventImpl event,
      boolean inTokenMode, boolean duringRI, boolean invokeCallbacks,
      Object expectedOldValue) {
    if (!inTokenMode || duringRI) {
      updateStatsForDestroy();
    }
    
    if (this.entryUserAttributes != null) {
      this.entryUserAttributes.remove(event.getKey());
    }
  }

  /**
   * Update stats
   */
  private void updateStatsForDestroy() {
    getCachePerfStats().incDestroys();
  }

  // Asif : This method will clear the tranxnl entries
  final void txClearRegion() {
    final TXStateInterface tx = discoverJTA();
    if (tx != null) {
      tx.rmRegion(this);
    }
  }

  public void invokeDestroyCallbacks(final EnumListenerEvent eventType,
      final EntryEventImpl event , final boolean callDispatchListenerEvent, boolean notifyGateways)
  {
    // The spec for ConcurrentMap support requires that operations be mapped
    // to non-CM counterparts
    if (event.getOperation() == Operation.REMOVE) {
      event.setOperation(Operation.DESTROY);
    }
    event.setEventType(eventType);
    notifyBridgeClients(event);
    if (notifyGateways) {
      notifyGatewayHubs(eventType, event);
    }
    if(callDispatchListenerEvent){
      dispatchListenerEvent(eventType, event);
    }
  }
  
  
  public void invokeTXCallbacks(final EnumListenerEvent eventType,
      final EntryEventImpl event , final boolean callDispatchListenerEvent, final boolean notifyGateway)
  {
    // The spec for ConcurrentMap support requires that operations be mapped
    // to non-CM counterparts
    
    Operation op = event.getOperation();
    
    LogWriterI18n log = getLogWriterI18n();
    if (log.fineEnabled()) {
      log.fine("invokeTXCallbacks for event " + event);
    }
    
    if (op == Operation.REMOVE) {
      event.setOperation(Operation.DESTROY);
    } else if (op == Operation.PUT_IF_ABSENT) {
      event.setOperation(Operation.CREATE);
    } else if (op == Operation.REPLACE) {
      event.setOperation(Operation.UPDATE);
    }
    event.setEventType(eventType);
    notifyBridgeClients(event);
    if(notifyGateway) {
      notifyGatewayHubs(eventType, event);
    }
    if (callDispatchListenerEvent){
      if (event.getInvokePRCallbacks() || (!(event.getRegion() instanceof PartitionedRegion) && !(event.getRegion().isUsedForPartitionedRegionBucket()))) {
        dispatchListenerEvent(eventType, event);
      }
    }
  }
  

  /**
   * @param re
   *          the existing RegionEntry of the entry to put
   * @param txState
   *          the TXState of the current transaction
   * @param key
   *          the key of the entry to destroy
   * @param needTokensForGII
   *          true if caller has determined we are in destroy token mode and
   *          will keep us in that mode while this call is executing.
   * @param filterRoutingInfo 
   * @param bridgeContext 
   * @param versionTag tag generated by txCoordinator - only on far side
   * @param tailKey tail (shadow) key generated by txCoordinator for WAN - only on farside
   * @param txr the {@link TXRegionState} for the operation
   * @param cbEvent a template EntryEvent for callbacks
   */
  final void txApplyDestroy(final RegionEntry re,
      final TXStateInterface txState, final Object key, boolean needTokensForGII,
      boolean localOp, EventID eventId, Object aCallbackArgument,
      List<EntryEventImpl> pendingCallbacks, FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, VersionTag versionTag,
      long tailKey, TXRegionState txr, EntryEventImpl cbEvent) {
    final boolean inRI = !needTokensForGII && lockRIReadLock();
    final boolean needRIDestroyToken = inRI && (this.riCnt > 0);
    final boolean inTokenMode = needTokensForGII || needRIDestroyToken;

    try {
      this.entries.txApplyDestroy(re, txState, key, inTokenMode,
          needRIDestroyToken, localOp, eventId, aCallbackArgument,
          pendingCallbacks, filterRoutingInfo, bridgeContext, versionTag,
          tailKey, txr, cbEvent);
    } finally {
      if (inRI) {
        unlockRIReadLock();
      }
    }
  }

  /**
   * Called by lower levels, while still holding the write sync lock, and the
   * low level has completed its part of the basic destroy
   */
  void txApplyDestroyPart2(RegionEntry re, Object key, boolean inTokenMode, boolean clearConflict)
  {
    if (this.testCallable != null) {
      this.testCallable.call(this, Operation.DESTROY, re);
    }
    if (inTokenMode) {
      getImageState().addDestroyedEntry(key);
    }
    else {
      updateStatsForDestroy();
    }
    if (this.entryUserAttributes != null) {
      this.entryUserAttributes.remove(key);
    }
  }

  /**
   * @see DistributedRegion#basicInvalidateRegion(RegionEventImpl)
   * @param event
   */
  void basicInvalidateRegion(RegionEventImpl event)
  {
    final TXStateInterface tx = this.cache.getTxManager().internalSuspend();
    try {
      this.regionInvalid = true;
      LogWriterI18n logger = getCache().getLoggerI18n();
      getImageState().setRegionInvalidated(true);
      invalidateAllEntries(event);
      Set allSubregions = subregions(true);
      for (Iterator itr = allSubregions.iterator(); itr.hasNext();) {
        LocalRegion rgn = (LocalRegion)itr.next();
        rgn.regionInvalid = true;
        try {
          rgn.getImageState().setRegionInvalidated(true);
          rgn.invalidateAllEntries(event);

          if (!rgn.isInitialized())
            continue; // don't invoke callbacks if not initialized yet

          if (rgn.hasListener()) {
            RegionEventImpl event2 = (RegionEventImpl)event.clone();
            event2.region = rgn;
            rgn.dispatchListenerEvent(
                EnumListenerEvent.AFTER_REGION_INVALIDATE, event2);
          }
        }
        catch (RegionDestroyedException ignore) {
          // ignore subregions that have been destroyed to fix bug 33276
        }
      }

      if (!isInitialized())
        return;

      event.setEventType(EnumListenerEvent.AFTER_REGION_INVALIDATE);
      notifyBridgeClients(event);

      boolean hasListener = hasListener();
      logger.fine("basicInvalidateRegion: hasListener = " + hasListener);
      if (hasListener) {
        dispatchListenerEvent(EnumListenerEvent.AFTER_REGION_INVALIDATE, event);
      }
    }
    finally {
      this.cache.getTxManager().resume(tx);
    }
  }

  /**
   * Determines whether the receiver is unexpired with regard to the given
   * timeToLive and idleTime attributes, which may different from this entry's
   * actual attributes. Used for validation of objects during netSearch(), which
   * must validate remote entries against local timeout attributes.
   */
  boolean isExpiredWithRegardTo(Object key, int ttl, int idleTime)
  {

    if (!getAttributes().getStatisticsEnabled())
      return false;

    long expTime;
    try {
      expTime = (new NetSearchExpirationCalculator(this, key, ttl, idleTime))
          .getExpirationTime();
    }
    catch (EntryNotFoundException ex) {
      return true;
    }
    if (expTime == 0)
      return false;
    return expTime <= cacheTimeMillis();
  }

  void dispatchListenerEvent(EnumListenerEvent op, InternalCacheEvent event)
  {
    // Return if the inhibit all notifications flag is set
    boolean isEntryEvent = event instanceof EntryEventImpl;
    if (isEntryEvent) {
      if (((EntryEventImpl)event).inhibitAllNotifications()){
        if (cache.getLoggerI18n().fineEnabled())
          cache.getLoggerI18n().fine("Notification inhibited for key " + event.toString());
        return;
      }
    }
    
    if (shouldDispatchListenerEvent()) {
      //Assert.assertTrue(event.getRegion() == this);
      LogWriterI18n logger = getCache().getLoggerI18n();
      if (logger.fineEnabled()) {
        logger.fine("dispatchListenerEvent event=" + event);
      }
      final long start = getCachePerfStats().startCacheListenerCall();
      
      boolean origOriginRemote = false;
      boolean isOriginRemoteSet = false;

      try {
        if ((isEntryEvent)) {
          if (((EntryEventImpl)event).isSingleHop()) {
            origOriginRemote = event.isOriginRemote();
            ((EntryEventImpl)event).setOriginRemote(true);
            isOriginRemoteSet = true;
          }
          RegionEntry re = ((EntryEventImpl) event).getRegionEntry();
          if (re != null) {
            ((EntryEventImpl) event).getRegionEntry()
                .setCacheListenerInvocationInProgress(true);
          }
        }
        
        if (!GemFireCacheImpl.ASYNC_EVENT_LISTENERS) {
          dispatchEvent(this, event, op);
        }
        else {
          final EventDispatcher ed = new EventDispatcher(event, op);
          try {
            this.cache.getEventThreadPool().execute(ed);
          }
          catch (RejectedExecutionException rex) {
            ed.release();
            logger.warning(LocalizedStrings.LocalRegion_0_EVENT_NOT_DISPATCHED_DUE_TO_REJECTED_EXECUTION, rex);
          }
        }
      }
      finally {
        getCachePerfStats().endCacheListenerCall(start);
        if (isOriginRemoteSet) {
          ((EntryEventImpl)event).setOriginRemote(origOriginRemote);
        }
        if (isEntryEvent) {
          RegionEntry re = ((EntryEventImpl) event).getRegionEntry();
          if (re != null) {
              re.setCacheListenerInvocationInProgress(false);
          }
        }
      }
    }
  }
  
  /** @return true if initialization is complete */
  public boolean isInitialized()
  {
    if (this.initialized) {
      return true;
    }
    else {
      long count;
      StoppableCountDownLatch latch = this.initializationLatchAfterGetInitialImage;
      if (latch == null) {
        return true;
      }
      count = latch.getCount();
      if (count == 0) {
        this.initialized = true;
        return true;
      }
      else {
        return false;
      }
    }
  }

  /**
   * Currently used by TX GII to indicate that initial profile exchange has been
   * completed (so target node can indicate any TX messages received before
   * that).
   * <p>
   * NOTE: This is now reset to false during destroy by BucketRegion, so any
   * other code relying on this flag needs to be aware that the flag can go from
   * true->false during region destroy.
   */
  public void setProfileExchanged(boolean value) {
    // no profile exchange required for local regions
  }

  /**
   * Returns true if initial profile exchange for a (non-local) region is done.
   * <p>
   * NOTE: This is now reset to false during destroy by BucketRegion, so any
   * other code relying on this flag needs to be aware that the flag can go from
   * true->false during region destroy.
   */
  public boolean isProfileExchanged() {
    // no profile exchange required for local regions
    return true;
  }

  protected void profileAdded(CacheDistributionAdvisor advisor, Profile p,
      Profile[] newProfiles) {
    if (!this.hasSerialAEQorWAN && (p instanceof CacheProfile)) {
      this.hasSerialAEQorWAN = advisor
          .profileHasSerialAEQorWAN((CacheProfile)p);
    }
  }

  protected void profileRemoved(CacheDistributionAdvisor advisor, Profile p,
      Profile[] remainingProfiles) {
    // need to update only if the removed profile has a serial AEQ or WAN
    if (this.hasSerialAEQorWAN && (p instanceof CacheProfile)
        && advisor.profileHasSerialAEQorWAN((CacheProfile)p)) {
      this.hasSerialAEQorWAN = false;
      for (Profile profile : remainingProfiles) {
        if (profile instanceof CacheProfile
            && advisor.profileHasSerialAEQorWAN((CacheProfile)profile)) {
          this.hasSerialAEQorWAN = true;
          break;
        }
      }
    }
  }

  /**
   * @return true if event state has been transfered to this region
   *         from another cache
   */
  public boolean isEventTrackerInitialized() {
    if (this.eventTracker != null) {
      return this.eventTracker.isInitialized();
    }
    return false;
  }
  
  /**
   * @return true if this region has an event tracker
   */
  public boolean hasEventTracker() {
    return (this.eventTracker != null);
  }

  void acquireDestroyLock()
  {
    boolean acquired = false;
    do {
      this.cache.getCancelCriterion().checkCancelInProgress(null);
      boolean interrupted = Thread.interrupted();
      try {
        getRoot().destroyLock.acquire();
        acquired = true;
      }
      catch (InterruptedException ie) {
        interrupted = true;
        this.cache.getCancelCriterion().checkCancelInProgress(ie);
      }
      finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    } while (!acquired);
    LogWriterI18n logger = getCache().getLoggerI18n();
    if (logger.fineEnabled()) {
      logger.fine("Acquired Destroy Lock: " + getRoot().getName());
    }
  }

  void releaseDestroyLock()
  {
    LogWriterI18n logger = getCache().getLoggerI18n();
    if (logger.fineEnabled()) {
      logger.fine("Releasing Destroy Lock: " + getRoot().getName());
    }
    getRoot().destroyLock.release();
  }

  /**
   * Cleans up any resources that may have been allocated for this region during
   * its initialization.
   */
  public void cleanupFailedInitialization()
  {
    boolean giiIndexLockAcquired = this.doPreEntryDestructionCleanup(true, null, true);
    try {
      closeEntries(); // fixes bug 41333
    } finally {
      this.doPostEntryDestructionCleanup(true, null, true, giiIndexLockAcquired);
    }
    this.cache.getResourceManager(false).removeResourceListener(this);
    this.destroyedSubregionSerialNumbers = collectSubregionSerialNumbers();
    try {
      if (this.eventTracker != null) {
        this.eventTracker.stop();
      }
      // close the UUID advisor
      if (this.uuidAdvisor != null) {
        this.uuidAdvisor.close();
      }
      if (this.diskRegion != null) {
        // This was needed to fix bug 30937
        try {
          diskRegion.cleanupFailedInitialization(this);
        }
        catch (IllegalStateException ex) {
          // just ignore this exception since whoever called us is going
          // to report the exception that caused initialization to fail.
        }
      }
    }
    finally {
      // make sure any waiters on initializing Latch are released
      this.releaseLatches();
    }
  }

  /**
   * To be invoked for clearing other related state when region map is being
   * destroyed (e.g. explicit destroy, or due to failed GII). Callers should
   * also invoke
   * {@link #doPostEntryDestructionCleanup(boolean, RegionEventImpl, boolean, boolean)}
   * in a finally block after {@link #closeEntries()} has been invoked.
   * 
   * @param initFailure
   *          if the failure is in initialization of region
   * @param event
   *          the event passed in for destroy; can be null if "initFailure" is
   *          true
   * @param setIsDestroyed
   *          if true then set the {@link #isDestroyed} flag to true
   * @return whether the indexgii write lock was taken or not
   *         (gfxd specific)
   */
  public boolean doPreEntryDestructionCleanup(boolean initFailure,
      RegionEventImpl event, boolean setIsDestroyed) {
    cleanupBeforeMarkDestroyed(initFailure, event);
    if (setIsDestroyed) {
      this.isDestroyed = true;
    }
    return cleanupAfterMarkDestroyed(initFailure, event, setIsDestroyed);
  }
  
  public static boolean getAndClearOffHeapEnabled() {
    Boolean bool = isOffHeapThreadLocal.get();
    isOffHeapThreadLocal.set(null);
    return bool != null && bool.booleanValue(); 
  }
  
  public void doPostEntryDestructionCleanup(boolean initFailure,
      RegionEventImpl event, boolean setIsDestroyed, boolean giiIndexLockAcquired) {
    // when setIsDestroyed is true, then we skip releasing the lock in
    // cleanupAfterMarkDestroyed since it should be held till closeEntries is
    // not complete
    final IndexUpdater indexUpdater;
    if (giiIndexLockAcquired && !setIsDestroyed
        && (indexUpdater = getIndexUpdater()) != null
        && (initFailure || isExplicitRegionDestroy(event))) {
      indexUpdater.releaseIndexLock(this);
    }
  }

  protected void clearTXStates() {
    // cleanup all pending TXRegionStates
    for (TXStateProxy txProxy : getCache().getTxManager()
        .getHostedTransactionsInProgress()) {
      txProxy.rmRegion(this);
    }
  }

  protected boolean clearIndexes(IndexUpdater indexUpdater, boolean lockForGII,
      boolean setIsDestroyed) {
    // by default need to clear indexes only if this is not the case of region
    // being destroyed (i.e. after GII failure) otherwise the region as well as
    // complete index is going to be blown away; bucket region will need to
    // override to clear in every case
    if (!setIsDestroyed) {
      return indexUpdater.clearIndexes(this, lockForGII, true, null, false);
    }
    return false;
  }

  protected void cleanupBeforeMarkDestroyed(boolean forInitFailure,
      RegionEventImpl event) {
    // TODO: TX: for non-persistent transactions it is okay to skip
    // clearing the TXStates when node is going down else may need to do
    // some cleanup of the persistent TX state; if we do not skip this
    // it causes hangs with GemFireCache going down and some other thread
    // holding a lock on TXRegionState when sending an operation unable
    // to get a connection (due to GemFireCache going down)
    if (forInitFailure || isExplicitRegionDestroy(event)) {
      // first mark profile exchanged as false to disallow new TXRegionStates
      // from being created
      setProfileExchanged(false);
      try {
        clearTXStates();
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error
            && SystemFailure.isJVMFailureError(err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        getCache().getCancelCriterion().checkCancelInProgress(t);
        // log the error at this point and move on
        getLogWriterI18n().severe(LocalizedStrings.DEBUG,
            "cleanupBeforeMarkDestroyed: unexpected error for "
                + toString(), t);
      }
    }
  }

  protected boolean cleanupAfterMarkDestroyed(boolean forInitFailure,
      RegionEventImpl event, boolean setIsDestroyed) {
    boolean indexGiiLockTaken = false;
    // cleanup any GII TX states still remaining
    if (forInitFailure || isExplicitRegionDestroy(event)) {
      ImageState imgState = getImageState();
      if (imgState.lockPendingTXRegionStates(true, false)) {
        try {
          // if region is not being destroyed then only reset the pending TXRS
          // list else null it out so operations can no longer go into it
          imgState.clearPendingTXRegionStates(!setIsDestroyed);
        } finally {
          imgState.unlockPendingTXRegionStates(true);
        }
      }
    }
    IndexUpdater indexUpdater = this.getIndexUpdater();
    if (indexUpdater != null) {
      if (forInitFailure || event.getDiskException() != null) {
        indexGiiLockTaken = this.clearIndexes(indexUpdater, true, setIsDestroyed);
      }
      else if (isExplicitRegionDestroy(event)) {
        // In OffHeap Gfxd race can happen between GII thread inserting an
        // entry in the map while ResourceManagerRecovery thread cleaning up
        // the failed initialization bucket, in which case index may contain
        // entry which has been released by the resource manager thread.
        // The lock below should prevent that situation.
        indexGiiLockTaken = this.clearIndexes(indexUpdater, true, setIsDestroyed);
      }
    }
    // for the case of restarting GII, reset the profile exchanged flag
    // to let new TXRS be created again
    if (!setIsDestroyed) {
      setProfileExchanged(true);
    }
    return indexGiiLockTaken;
  }

  protected boolean isExplicitRegionDestroy(RegionEventImpl event) {
    return event != null && (
        Operation.REGION_LOCAL_DESTROY.equals(event.getOperation()) ||
        Operation.REGION_DESTROY.equals(event.getOperation()) ||
        Operation.REGION_EXPIRE_LOCAL_DESTROY.equals(event.getOperation()) ||
        Operation.REGION_EXPIRE_DESTROY.equals(event.getOperation()));
  }

  //////////////////// Private Methods ////////////////////////////////////////

  LocalRegion getRoot()
  {
    LocalRegion r = this;
    while (r.parentRegion != null) {
      r = r.parentRegion;
    }
    return r;
  }

  private void initializationFailed(LocalRegion subregion)
  {
    synchronized (this.subregionsLock) { // bugfix for bug#34883 (tushar)
      this.subregions.remove(subregion.getName());
    }
    subregion.cleanupFailedInitialization();
  }

  /**
   * PRECONDITIONS: Synchronized on updateMonitor for this key in order to
   * guarantee write-through to map entry, and key must be in map
   *
   * @param p_lastModified
   *          time, may be 0 in which case uses now instead
   *
   * @return the actual lastModifiedTime used.
   */
  long updateStatsForPut(RegionEntry entry, long p_lastModified,
      boolean lruRecentUse) {
    long lastModified = p_lastModified;
    if (lruRecentUse) {
      entry.setRecentlyUsed(); // fix for bug 31102
    }
    if (lastModified == 0L) {
      lastModified = cacheTimeMillis();
    }
    entry.updateStatsForPut(lastModified);
    if (this.statisticsEnabled && !isProxy()) {
      // do not reschedule if there is already a task in the queue.
      // this prevents bloat in the TimerTask since cancelled tasks
      // do not actually get removed from the TimerQueue.
      // When the already existing task gets fired it checks to see
      // if it is premature and if so reschedules a task at that time.
      addExpiryTaskIfAbsent(entry);
    }
    // propagate to region
    setLastModifiedTime(lastModified);
    return lastModified;
  }

  /**
   * Returns a region in the subregion map first, then looks in the
   * reinitializing region registry.
   *
   * @return the region or null if not found, may be destroyed
   */
  private LocalRegion basicGetSubregion(String name)
  {
    LogWriterI18n logger = this.cache.getLoggerI18n();
    LocalRegion r = toRegion(this.subregions.get(name));
    // don't wait for reinitialization if the init_level for this thread is
    // ANY_INIT: We don't want CreateRegion messages to wait on a future
    // because it would cause a deadlock. If the region is ready for a
    // CreateRegion message, it would have been in the subregions map.
    if (r == null && threadInitLevelRequirement() != ANY_INIT) {
      // try future
      // Region p = this.parentRegion;
      String thePath = getFullPath() + SEPARATOR + name;
      if (logger.fineEnabled()) {
        logger.fine("Trying reinitializing region, fullPath=" + thePath);
      }
      r = this.cache.getReinitializingRegion(thePath, false);
      if (logger.fineEnabled()) {
        logger.fine("Reinitialized region is " + r);
      }
    }
    return r;
  }

  /**
   * Make a LocalRegion from an element in the subregion map Sent to parent
   * region.
   *
   * @return This method may return null or a destroyed region if the region was
   *         just destroyed
   */
  private LocalRegion toRegion(Object element)
  {
    LocalRegion rgn = (LocalRegion)element;
    if (rgn != null) {
      // do not return until done initializing (unless this is an initializing
      // thread)
      rgn.waitOnInitialization();
    }
    return rgn;
  }

  /**
   * Update the API statistics appropriately for returning this value from get.
   *
   * @param re
   *          the entry whose value was accessed
   */
  public final void updateStatsForGet(final RegionEntry re, final boolean hit) {
    if (!this.statisticsEnabled) {
      return;
    }

    final long now = cacheTimeMillis();
    if (re != null) {
      re.updateStatsForGet(hit, now);
      if (isEntryIdleExpiryPossible()) {
        addExpiryTaskIfAbsent(re);
      }
    }

    // update region stats
    setLastAccessedTime(now, hit);
  }

  private void sendPendingRegionDestroyEvents(HashSet set)
  {
    Iterator iterator = set.iterator();
    while (iterator.hasNext()) {
      RegionEventImpl event = (RegionEventImpl)iterator.next();
      event.region.dispatchListenerEvent(
          EnumListenerEvent.AFTER_REGION_DESTROY, event);
      if (!cache.forcedDisconnect()) {
        SystemMemberCacheEventProcessor.send(getCache(), event.getRegion(), event
            .getOperation());
      }
    }
  }

  /** The listener is not closed until after the afterRegionDestroy event */
  protected void closeCallbacksExceptListener()
  {
    closeCacheCallback(getCacheLoader());
    closeCacheCallback(getCacheWriter());
    closeCacheCallback(getEvictionController());
  }

  /** This is only done when the cache is closed. */
  private void closeAllCallbacks()
  {
    closeCallbacksExceptListener();
    CacheListener[] listeners = fetchCacheListenersField();
    if (listeners != null) {
      for (int i = 0; i < listeners.length; i++) {
        closeCacheCallback(listeners[i]);
      }
    }
  }

  /**
   * Release the client connection pool if we have one
   * @since 5.7
   */
  private void detachPool() {
    ServerRegionProxy mySRP = getServerProxy();
    if (mySRP != null) {
      GemFireCacheImpl gc = getCache();
      // @todo grid: if region has a pool check its keepAlive
      mySRP.detach(gc.keepDurableSubscriptionsAlive());
    }
  }
  
  /**
   * Closes the cqs created based on this region (Cache Client/writer/loader).
   */
  private void closeCqs() {
    CqService cqService = CqService.getRunningCqService();
    if (cqService != null) {
      try {
        cqService.closeCqs(getFullPath());
      } 
      catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        this.cache.getLoggerI18n().warning(LocalizedStrings.LocalRegion_EXCEPTION_OCCURRED_WHILE_CLOSING_CQS_ON_REGION_DESTORY, t);
      }
    }
  }

  /**
   * Called when the cache is closed. Behaves just like a Region.close except
   * the operation is CACHE_CLOSE
   */
  void handleCacheClose(Operation op)
  {
    RegionEventImpl ev = new RegionEventImpl(this, op, null, false, getMyId(),
        generateEventID());
    if (!this.isDestroyed) { // bruce: don't destroy if already destroyed
      try {
        basicDestroyRegion(ev, false, true, true);
      }
      catch (CancelException ignore) {
        // If the region was destroyed we see this because the cache is closing.
        // Since we are trying to close the cache don't get upset if
        // a region was destroyed out from under us
        if (cache.getLogger().fineEnabled()) {
          cache.getLogger().fine(
              "handleCacheClose: Encountered cache closure while closing region " + getFullPath());
        }
      }
      catch (RegionDestroyedException ignore) {
        // Since we are trying to close the cache don't get upset if
        // a region was destroyed out from under us
      }
      catch (CacheWriterException e) {
        // not possible with local operation, CacheWriter not called
        throw new Error(LocalizedStrings.LocalRegion_CACHEWRITEREXCEPTION_SHOULD_NOT_BE_THROWN_HERE.toLocalizedString(), e);
      }
      catch (TimeoutException e) {
        // not possible with local operation, no distributed locks possible
        InternalDistributedSystem ids = (this.getCache().getDistributedSystem());
        if (!ids.isDisconnecting()) {
          throw new InternalGemFireError(LocalizedStrings.LocalRegion_TIMEOUTEXCEPTION_SHOULD_NOT_BE_THROWN_HERE.toLocalizedString(), e);
        }
      }
    }
  }
  void cleanUpOnIncompleteOp(EntryEventImpl event,   RegionEntry re, 
      boolean eventRecorded, boolean updateStats, boolean isReplace) {
    //TODO:Asif: This is incorrect implementation for replicated region in case of
    //sql fabric, as gfxd index would already be  updated, if eventRecorded 
    //flag is true.So if entry is being removed , 
    //then the gfxdindex also needs to be corrected
//    LogWriterI18n log = getLogWriterI18n();
//    if (log.fineEnabled()) {
//      log.fine("removing entry from incomplete operation: " + re);
//    }
    IndexUpdater iu = this.getIndexUpdater(); // gfxd system
    if(!eventRecorded || iu ==null || isReplace) {
    //Ok to remove entry whether gemfirexd or gfe as index has not been modified yet by the operation
      this.entries.removeEntry(event.getKey(), re, updateStats) ;      
    }else {
      // a gfxd system, with event recorded as true. we need to update index.
      //Use the current event to indicate destroy.should be ok
      Operation oldOp = event.getOperation();
      event.setOperation(Operation.DESTROY);
      this.entries.removeEntry(event.getKey(), re, updateStats, event, this, iu);
      event.setOperation(oldOp);
    } 
    
  }

  static void validateRegionName(String name)
  {
    if (name == null) {
      throw new IllegalArgumentException(LocalizedStrings.LocalRegion_NAME_CANNOT_BE_NULL.toLocalizedString());
    }
    if (name.length() == 0) {
      throw new IllegalArgumentException(LocalizedStrings.LocalRegion_NAME_CANNOT_BE_EMPTY.toLocalizedString());
    }
    if (name.indexOf(SEPARATOR) >= 0) {
      throw new IllegalArgumentException(LocalizedStrings.LocalRegion_NAME_CANNOT_CONTAIN_THE_SEPARATOR_0.toLocalizedString(SEPARATOR));
    }
  }

  private void checkCacheClosed()
  {
    if (this.cache.isClosed()) {
      throw cache.getCacheClosedException(null, null);
    }
  }

  private void checkRegionDestroyed(boolean checkCancel)
  {
    if (checkCancel) {
      this.cache.getCancelCriterion().checkCancelInProgress(null);
    }
    if (this.isDestroyed) {
      RegionDestroyedException ex;
      if (this.reinitialized_old) {
        ex = new RegionReinitializedException(toString(), getFullPath());
      } else if (this.cache.isCacheAtShutdownAll()) {
        throw new CacheClosedException("Cache is being closed by ShutdownAll");
      }
      else {
        ex = new RegionDestroyedException(toString(), getFullPath());
      }
      // Race condition could cause the cache to be destroyed after the
      // cache close check above, so we need to re-check before throwing.
      if (checkCancel) {
        this.cache.getCancelCriterion().checkCancelInProgress(null);
      }
      throw ex;
    }
    
    if (this.isDestroyedForParallelWAN) {
      throw new RegionDestroyedException(
          LocalizedStrings.LocalRegion_REGION_IS_BEING_DESTROYED_WAITING_FOR_PARALLEL_QUEUE_TO_DRAIN
              .toLocalizedString(), getFullPath());
    }
  }

  /**
   * For each region entry in this region call the callback.
   * Note that this method will not work well if the callback needs the
   * value and entry has overflown to disk. For such cases user should
   * instead use {@link #getBestLocalIterator(boolean)}.
   * 
   * @since prPersistSprint2
   */
  public void foreachRegionEntry(RegionEntryCallback callback) {
    // do not iterate over entries in HDFS
    Iterator it = this.entries.regionEntriesInVM().iterator();
    while (it.hasNext()) {
      callback.handleRegionEntry((RegionEntry)it.next());
    }
  }

  /**
   * Used by {@link #foreachRegionEntry}.
   * @since prPersistSprint2
   */
  public interface RegionEntryCallback {
    public void handleRegionEntry(RegionEntry re);
  }

  protected void checkIfReplicatedAndLocalDestroy(EntryEventImpl event) {
    // Actiual: disallow local invalidation for replicated regions
    if (getScope().isDistributed() && getDataPolicy().withReplication()
        && (!event.isDistributed()) && !isUsedForSerialGatewaySenderQueue()) {
      throw new IllegalStateException(LocalizedStrings.LocalRegion_NOT_ALLOWED_TO_DO_A_LOCAL_DESTROY_ON_A_REPLICATED_REGION.toLocalizedString());
    }
  }

  /**
   * Return the number of subregions, including this region. Used for recursive
   * size calculation in SubregionsSet.size
   */
  protected int allSubregionsSize()
  {
    int sz = 1; /* 1 for this region */
    for (Iterator itr = this.subregions.values().iterator(); itr.hasNext();) {
      LocalRegion r = (LocalRegion)itr.next();
      if (r != null && r.isInitialized() && !r.isDestroyed()) {
        sz += r.allSubregionsSize();
      }
    }
    return sz;
  }

  /**
   * Return the number of entries including in subregions. Used for recursive
   * size calculation in EntriesSet.size.  This does not include tombstone
   * entries stored in the region.
   */
  protected final int allEntriesSize(final TXStateInterface tx) {
    int sz = entryCount(tx);
    for (Iterator itr = this.subregions.values().iterator(); itr.hasNext();) {
      LocalRegion r = toRegion(itr.next());
      if (r != null && !r.isDestroyed()) {
        sz += r.allEntriesSize(tx);
      }
    }
    return sz;
  }

  /**
   * @param rgnEvent
   *          the RegionEvent for region invalidation
   */
  protected void invalidateAllEntries(RegionEvent rgnEvent)
  {
    Operation op = Operation.LOCAL_INVALIDATE;
    if (rgnEvent.getOperation().isDistributed()) {
      op = Operation.INVALIDATE;
    }
   

    // if this is a local invalidation, then set local invalid flag on event
    // so LOCAL_INVALID tokens is used (even though each individual entry
    // invalidation is not distributed).
   

    // region operation so it is ok to ignore tx state
    for (Iterator itr = keySet().iterator(); itr.hasNext();) {
      try {
        //EventID will not be generated by this constructor
        EntryEventImpl event = EntryEventImpl.create(
            this, op, itr.next() /*key*/,
            null/* newValue */, null/* callbackArg */, rgnEvent.isOriginRemote(),
            rgnEvent.getDistributedMember());
        try {
        event.setLocalInvalid(!rgnEvent.getOperation().isDistributed());
        basicInvalidate(event, false);
        } finally {
          event.release();
        }
      }
      catch (EntryNotFoundException e) {
        // ignore
      }
    }
  }

  final boolean hasListener()
  {
    CacheListener[] listeners = fetchCacheListenersField();
    return listeners != null && listeners.length > 0;
  }

  private final DiskStoreImpl dsi;
  public final DiskStoreImpl getDiskStore() {
    return this.dsi;
  }

  /**
   * Return true if all disk attributes are defaults.
   * DWA.isSynchronous can be true or false.
   */
  private boolean useDefaultDiskStore() {
    assert(getDiskStoreName()== null);
    if (!Arrays.equals(getDiskDirs(), DiskStoreFactory.DEFAULT_DISK_DIRS)) {
      return false;
    }
    if (!Arrays.equals(getDiskDirSizes(), DiskStoreFactory.DEFAULT_DISK_DIR_SIZES)) {
      return false;
    }
    DiskWriteAttributesFactory dwf = new DiskWriteAttributesFactory();
    dwf.setSynchronous(false);
    if (dwf.create().equals(getDiskWriteAttributes())) {
      return true;
    }
    dwf.setSynchronous(true);
    if (dwf.create().equals(getDiskWriteAttributes())) {
      return true;
    }
    return false;
  }

  /**
   * Returns true if this region's config indicates that it will use a disk store.
   * Added for bug 42055.
   */
  protected boolean usesDiskStore(RegionAttributes ra) {
    return !isProxy()
      && (getAttributes().getDataPolicy().withPersistence()
          || isOverflowEnabled());
  }
  
  protected DiskStoreImpl findDiskStore(RegionAttributes ra, InternalRegionArguments internalRegionArgs) {
    //validate that persistent type registry is persistent
    if(getAttributes().getDataPolicy().withPersistence()) {
      getCache().getPdxRegistry().creatingPersistentRegion();
    }

    if (usesDiskStore(ra)) {
        if (getDiskStoreName() != null) {
          DiskStoreImpl diskStore = (DiskStoreImpl)getGemFireCache().findDiskStore(getDiskStoreName());
          if (diskStore == null) {
            throw new IllegalStateException(LocalizedStrings.CacheCreation_DISKSTORE_NOTFOUND_0.toLocalizedString(getDiskStoreName()));
          }
          return diskStore;
        }
        else if (useDefaultDiskStore()){
          return getGemFireCache().getOrCreateDefaultDiskStore();
        } else /* backwards compat mode */{
          DiskStoreFactory dsf = getGemFireCache().createDiskStoreFactory();
          dsf.setDiskDirsAndSizes(getDiskDirs(), getDiskDirSizes());
          DiskWriteAttributes dwa = getDiskWriteAttributes();
          //cache.getLogger().info("DEBUG region " + getName() + " created with old APIs dwa=" + dwa/*, new RuntimeException("STACK")*/);
          dsf.setAutoCompact(dwa.isRollOplogs());
          dsf.setMaxOplogSize(dwa.getMaxOplogSize());
          dsf.setTimeInterval(dwa.getTimeInterval());
          if (dwa.getBytesThreshold() > 0) {
            dsf.setQueueSize(1);
          } else {
            dsf.setQueueSize(0);
          }
          DiskStoreFactoryImpl dsfi = (DiskStoreFactoryImpl)dsf;
          return dsfi.createOwnedByRegion(getFullPath().replace('/', '_'),
              ra.getPartitionAttributes() != null, internalRegionArgs);
        }
    }
    return null;
  }

  /**
   * Creates a new <code>DiskRegion</code> for this region. We assume that the
   * attributes and the name of the region have been set.
   *
   * @return <code>null</code> is a disk region is not desired
   *
   * @since 3.2
   */
  protected DiskRegion createDiskRegion(InternalRegionArguments internalRegionArgs)
      throws DiskAccessException {
    if (internalRegionArgs.getDiskRegion() != null) {
      DiskRegion dr = internalRegionArgs.getDiskRegion();
      dr.createDataStorage();
      return dr;
    }
    // A Proxy inherently has no storage.
    if (dsi != null) {

      // for regions that were previously dropped but not seen by this JVM
      // in .if files, recreate should drop the existing persistent files
      final GemFireCacheImpl.StaticSystemCallbacks sysCb =
          GemFireCacheImpl.FactoryStatics.systemCallbacks;
      if (sysCb != null && sysCb.destroyExistingRegionInCreate(dsi, this)) {
        dsi.destroyRegion(getFullPath(), false);
      }

      DiskRegionStats stats;
      if (this instanceof BucketRegion) {
        stats = internalRegionArgs.getPartitionedRegion().getDiskRegionStats();
      } else {
        stats = new DiskRegionStats(getCache().getDistributedSystem(), getFullPath());
      }
      EnumSet<DiskRegionFlag> diskFlags = EnumSet.noneOf(DiskRegionFlag.class);
      if (deferRecovery()) {
        diskFlags.add(DiskRegionFlag.DEFER_RECOVERY);
      }
      // For GemFireXD add flag if this region has versioning enabled
      if(this.getAttributes().getConcurrencyChecksEnabled()) {
        diskFlags.add(DiskRegionFlag.IS_WITH_VERSIONING);
      }
      return DiskRegion.create(dsi, getFullPath(), false,
                               getDataPolicy().withPersistence(),
                               isOverflowEnabled(), isDiskSynchronous(),
                               stats, getCancelCriterion(), this, getAttributes(),
                               diskFlags, internalRegionArgs.getUUID(), "NO_PARTITITON", -1,
                               getCompressor(), getEnableOffHeapMemory());
    } else {
      return null;
    }
  }

  /**
   * Returns the object sizer on this region or null if it has no sizer.
   */
  public ObjectSizer getObjectSizer() {
    ObjectSizer result = null;
    EvictionAttributes ea = getEvictionAttributes();
    if (ea != null) {
      result = ea.getObjectSizer();
    }
    return result;
  }

  /** ************************ Expiration methods ******************************* */

  /**
   * Add the Region TTL expiry task to the scheduler
   */
  void addTTLExpiryTask()
  {
    synchronized (this.pendingExpires) {
      RegionTTLExpiryTask task = this.regionTTLExpiryTask;
      if (task != null)
        task.cancel();
      if (this.regionTimeToLive > 0) {
        this.regionTTLExpiryTask = (RegionTTLExpiryTask)
        this.cache.getExpirationScheduler().addExpiryTask(
            new RegionTTLExpiryTask(this));
        LogWriterI18n logger = getCache().getLoggerI18n();
        if (logger.fineEnabled()) {
          logger.fine("Initialized Region TTL Expiry Task "
              + this.regionTTLExpiryTask);
        }
      }
    }
  }

  void addTTLExpiryTask(RegionTTLExpiryTask callingTask)
  {
    synchronized (this.pendingExpires) {
      if (this.regionTTLExpiryTask != callingTask || this.regionTimeToLive <= 0) {
        return;
      }
      RegionTTLExpiryTask task = new RegionTTLExpiryTask(this);
      LogWriterI18n logger = getCache().getLoggerI18n();
      if (logger.fineEnabled()) {
        logger.fine("Scheduling Region TTL Expiry Task " + task
            + " which replaces " + this.regionTTLExpiryTask);
      }
      this.regionTTLExpiryTask = (RegionTTLExpiryTask)
          this.cache.getExpirationScheduler().addExpiryTask(task);
    }
  }

  /**
   * Add the Region Idle expiry task to the scheduler
   */
  final void addIdleExpiryTask()
  {
    synchronized (this.pendingExpires) {
      RegionIdleExpiryTask task = this.regionIdleExpiryTask;
      if (task != null)
        task.cancel();
      if (this.regionIdleTimeout > 0) {
        this.regionIdleExpiryTask = (RegionIdleExpiryTask)
            this.cache.getExpirationScheduler().addExpiryTask(
                new RegionIdleExpiryTask(this));
        LogWriterI18n logger = getCache().getLoggerI18n();
        if (logger.fineEnabled()) {
          logger.fine("Initialized Region Idle Expiry Task "
              + this.regionIdleExpiryTask);
        }
      }
    }
  }

  void addIdleExpiryTask(RegionIdleExpiryTask callingTask)
  {
    synchronized (this.pendingExpires) {
      if (this.regionIdleExpiryTask != callingTask
          || this.regionIdleTimeout <= 0) {
        return;
      }
      RegionIdleExpiryTask task = new RegionIdleExpiryTask(this);
      LogWriterI18n logger = getCache().getLoggerI18n();
      if (logger.fineEnabled()) {
        logger.fine("Scheduling Region Idle Expiry Task " + task
            + " which replaces " + this.regionIdleExpiryTask);
      }
      this.regionIdleExpiryTask = (RegionIdleExpiryTask)
          this.cache.getExpirationScheduler().addExpiryTask(task);
    }
  }

  /**
   * Added to fix bug 31204
   */
  protected boolean isEntryIdleExpiryPossible()
  {
    return this.entryIdleTimeout > 0 || this.customEntryIdleTimeout != null;
  }

  private void cancelTTLExpiryTask()
  {
    RegionTTLExpiryTask task = this.regionTTLExpiryTask;
    if (task != null) {
      task.cancel();
    }
  }

  private void cancelIdleExpiryTask()
  {
    RegionIdleExpiryTask task = this.regionIdleExpiryTask;
    if (task != null) {
      task.cancel();
    }
  }

  @Override
  protected void regionTimeToLiveChanged(ExpirationAttributes oldTimeToLive)
  {
    addTTLExpiryTask();
  }

  @Override
  protected void regionIdleTimeoutChanged(ExpirationAttributes oldIdleTimeout)
  {
    addIdleExpiryTask();
  }

  @Override
  protected void timeToLiveChanged(ExpirationAttributes oldTimeToLive)
  {
    int oldTimeout = oldTimeToLive.getTimeout();
    if (customEntryTimeToLive != null) {
      rescheduleEntryExpiryTasks();
    }
    else
    if (entryTimeToLive > 0
        && (oldTimeout == 0 || entryTimeToLive < oldTimeout)) {
      rescheduleEntryExpiryTasks();
    }
    else {
      // It's safe to let them get rescheduled lazily, as the old expiration
      // time will cause the tasks to fire sooner than the new ones.
    }
  }

  @Override
  protected void idleTimeoutChanged(ExpirationAttributes oldIdleTimeout)
  {
    int oldTimeout = oldIdleTimeout.getTimeout();
    if (customEntryIdleTimeout != null) {
      rescheduleEntryExpiryTasks();
    }
    else
    if (entryIdleTimeout > 0
        && (oldTimeout == 0 || entryIdleTimeout < oldTimeout)) {
      rescheduleEntryExpiryTasks();
    }
    else {
      // It's safe to let them get rescheduled lazily, as the old expiration
      // time will cause the tasks to fire sooner than the new ones.
    }
  }

  protected void rescheduleEntryExpiryTasks()
  {
    if (isProxy()) {
      return;
    }
    if (!isInitialized()) {
      return; // don't schedule expiration until region is initialized (bug
    }
    // OK to ignore transaction since Expiry only done non-tran
    Iterator<RegionEntry> it = this.entries.regionEntries().iterator();
    if (it.hasNext()) {
      try {
        if (isEntryExpiryPossible()) {
          ExpiryTask.setNow();
        }
        while (it.hasNext()) {
          addExpiryTask(it.next());
        }
      } finally {
        ExpiryTask.clearNow();
      }
    }
  }

  void addExpiryTaskIfAbsent(RegionEntry re)
  {
    addExpiryTask(re, true);
  }

  void addExpiryTask(RegionEntry re)
  {
    addExpiryTask(re, false);
  }
  
  /**
   * Used to create a cheap Region.Entry that can be passed to the CustomExpiry callback
   * @author dschneider
   *
   */
  private static class ExpiryRegionEntry implements Region.Entry {
    private final LocalRegion region;
    private final RegionEntry re;

    public ExpiryRegionEntry(LocalRegion lr, RegionEntry re) {
      this.region = lr;
      this.re = re;
    }

   @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((re == null) ? 0 : re.hashCode());
      result = prime * result + ((region == null) ? 0 : region.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      ExpiryRegionEntry other = (ExpiryRegionEntry) obj;
      if (re == null) {
        if (other.re != null)
          return false;
      } else if (!re.equals(other.re))
        return false;
      if (region == null) {
        if (other.region != null)
          return false;
      } else if (!region.equals(other.region))
        return false;
      return true;
    }

  @Override
    public String toString() {
      return "region=" + region.getFullPath() + ", key=" + getKey() + " value=" + getValue();
    }

    @Override
    public Region getRegion() {
      return this.region;
    }
    
    /**
     * Returns the entry's  RegionEntry if it "checks" out. The check is to
     * see if the region entry still exists.
     * @throws EntryNotFoundException if the RegionEntry has been removed.
     */
    private RegionEntry getCheckedRegionEntry() throws EntryNotFoundException {
      RegionEntry result = this.re;
      if (re.isDestroyedOrRemoved()) {
        throw new EntryNotFoundException("Entry for key " + re.getKey() + " no longer exists");
      }
      return result;
    }

    @Override
    public Object getValue() {
      Object value = this.region.getDeserialized(getCheckedRegionEntry(), false, false, false);
      if (value == null) {
        throw new EntryDestroyedException(getKey().toString());
      }
      else if(Token.isInvalid(value)) {
        return null;
      }
      return value;
    }

    @Override
    public boolean isLocal() {
      return true; // we only create expiry tasks for local entries
    }

    @Override
    public CacheStatistics getStatistics() {
      LocalRegion lr = this.region;
      if (!lr.statisticsEnabled) {
        throw new StatisticsDisabledException(LocalizedStrings.LocalRegion_STATISTICS_DISABLED_FOR_REGION_0.toLocalizedString(lr.getFullPath()));
      }
      return new CacheStatisticsImpl(getCheckedRegionEntry(), lr);
    }

    @Override
    public Object getUserAttribute() {
      Map userAttr = this.region.entryUserAttributes;
      if (userAttr == null) {
        return null;
      }
      return userAttr.get(getKey());
    }

    @Override
    public Object setUserAttribute(Object userAttribute) {
      LocalRegion lr = this.region;
      if (lr.entryUserAttributes == null) {
        lr.entryUserAttributes = new Hashtable();
      }
      return lr.entryUserAttributes.put(getKey(), userAttribute);
    }

    @Override
    public boolean isDestroyed() {
      if (this.re.isDestroyedOrRemoved()) return true;
      return false;
    }

    @Override
    public Object setValue(Object value) {
      return this.region.put(getKey(), value);
    }

    @Override
    public Object getKey() {
      return this.re.getKey();
    }
  }
  /**
   * If custom expiration returns non-null expiration attributes
   * then create a CustomEntryExpiryTask for this region and the given entry and return it.
   * Otherwise if the region is configured for expiration
   * then create an EntryExpiryTask for this region and the given entry and return it.
   * Null is returned if the expiration attributes indicate that expiration is disabled.
   */
  private EntryExpiryTask createExpiryTask(RegionEntry re) {
    if (re == null || re.isDestroyedOrRemoved()) {
      return null;
    }
    if (this.customEntryIdleTimeout != null || this.customEntryTimeToLive != null) {
      ExpiryRegionEntry ere = new ExpiryRegionEntry(this, re);
      ExpirationAttributes ttlAtts = null;
      ExpirationAttributes idleAtts = null;
      final RegionAttributes<?,?> ra = this.getAttributes();
      {
        final CustomExpiry<?,?> customTTL = ra.getCustomEntryTimeToLive();
        if (customTTL != null) {
          try {
            ttlAtts = customTTL.getExpiry(ere);
            if (ttlAtts != null) {
              this.checkEntryTimeoutAction("timeToLive", ttlAtts.getAction());
            }
          }
          catch (RegionDestroyedException rde) {
            // Ignore - #42273
          }
          catch (Exception e) {
            this.getCache().getLoggerI18n().severe(LocalizedStrings.EntryExpiryTask_ERROR_CALCULATING_EXPIRATION_0, e, null);
          }
        }
        if (ttlAtts == null) {
          ttlAtts = ra.getEntryTimeToLive();  
        }
      }
      {
        CustomExpiry<?,?> customIdle = ra.getCustomEntryIdleTimeout();
        if (customIdle != null) {
          try {
            idleAtts = customIdle.getExpiry(ere);
            if (idleAtts != null) {
              this.checkEntryTimeoutAction("idleTimeout", idleAtts.getAction());
            }
          }
          catch (RegionDestroyedException rde) {
            // Ignore - #42273
          }
          catch (Exception e) {
            this.getCache().getLoggerI18n().severe(LocalizedStrings.EntryExpiryTask_ERROR_CALCULATING_EXPIRATION_0, e, null);
          }
        }
        if (idleAtts == null) {
          idleAtts = ra.getEntryIdleTimeout();
        }
      }
      final boolean ttlDisabled = ttlAtts == null || ttlAtts.getTimeout() == 0;
      final boolean idleDisabled = idleAtts == null || idleAtts.getTimeout() == 0;
      if (ttlDisabled && idleDisabled) {
        return null;
      } else if ((ttlDisabled || ttlAtts.equals(ra.getEntryTimeToLive()))
          && (idleDisabled || idleAtts.equals(ra.getEntryIdleTimeout()))) {
        // no need for custom since we can just use the region's expiration attributes.
        return new EntryExpiryTask(this, re);
      } else {
        return new CustomEntryExpiryTask(this, re, ttlAtts, idleAtts);
      }
    } else if (isEntryExpiryPossible()) {
      return new EntryExpiryTask(this, re);
    } else {
      return null;
    }
  }

  private void addExpiryTask(RegionEntry re, boolean ifAbsent)
  {
    if (isProxy()) {
      return;
    }
    if (!isInitialized()) {
      return; // don't schedule expiration until region is initialized (bug
      // 35214)
    }
    LogWriterI18n logger = getCache().getLoggerI18n();
//    if (logger.finerEnabled()) {
//      logger.finer("addExpiryTask("+key+") isEntryExpiryPossible=" + isEntryExpiryPossible() + " ifAbsent=" + ifAbsent);
//    }
    if (isEntryExpiryPossible()) {
      EntryExpiryTask newTask = null;
      EntryExpiryTask oldTask = null;
      if (ifAbsent) {
        oldTask = (EntryExpiryTask)this.entryExpiryTasks.get(re);
        if (oldTask != null) {
          boolean keepOldTask = true;
          if (this.customEntryIdleTimeout != null || this.customEntryTimeToLive != null) {
            newTask = createExpiryTask(re);
            if (newTask == null) {
              cancelExpiryTask(re); // cancel any old task
              return;
            }
            // to fix bug 44418 see if the new tasks expiration would be earlier than
            // the scheduled task.
            long ntTime = newTask.getExpirationTime();
            if (ntTime != 0 && ntTime < oldTask.getExpirationTime()) {
              // it is so get rid of the old task and schedule the new one.
              keepOldTask = false;
            }
          }
          if (keepOldTask) {
            // if an oldTask is present leave it be
            if (logger.finerEnabled()) {
              logger.finer("Expiry Task not added because one already present. Key=" + re.getKey());
            }
            return;
          }
        }
      }
      if (newTask == null) {
        newTask = createExpiryTask(re);
        if (newTask == null) {
          cancelExpiryTask(re); // cancel any old task
          return;
        }
      }
      oldTask = this.entryExpiryTasks.put(re, newTask);
      ExpirationScheduler es = this.cache.getExpirationScheduler();
      if (oldTask != null) {
        if (oldTask.cancel()) {
          es.incCancels();
        }
      }
      if (!es.addEntryExpiryTask(newTask)) {
        this.entryExpiryTasks.remove(re);
      }
      // @todo darrel: merge question: should we catch EntryNotFoundException
      // if addExpiryTask throws it?
      //       } catch (EntryNotFoundException e) {
      //         // ignore - there are unsynchronized paths that allow an entry to
      //         // be destroyed out from under us.
      //         return;
      //       }
    }
    else {
      cancelExpiryTask(re);
      logger.finer("addExpiryTask(key) ignored");
    }
  }

  void cancelExpiryTask(RegionEntry re)
  {
    EntryExpiryTask oldTask = this.entryExpiryTasks.remove(re);
    if (oldTask != null) {
      if (oldTask.cancel()) {
        this.cache.getExpirationScheduler().incCancels();
      }
    }
  }

  public void cancelAllEntryExpiryTasks()
  {
    // This method gets called during LocalRegion construction
    // in which case the final entryExpiryTasks field can still be null
    if (this.entryExpiryTasks == null) return;
    if (this.entryExpiryTasks.isEmpty()) return;
    boolean doPurge = false;
    Iterator<EntryExpiryTask> tasksIter = this.entryExpiryTasks.values().iterator();
    while (tasksIter.hasNext()) {
      EntryExpiryTask task = tasksIter.next();
      task.cancel(); // no need to call incCancels since we will call forcePurge
      doPurge = true;
    }
    if (doPurge) {
      // do a force to not leave any refs to this region
      this.cache.getExpirationScheduler().forcePurge();
    }
  }
  
  public void cancelEvictorService() {
    if (this.evService != null) {
      this.evService.stopScheduling();
      this.evService.stop();
      try {
        this.evService.shutDown();
      }
      catch(Exception e) {
        if (getLogWriterI18n().warningEnabled()) {
          getLogWriterI18n().fine(
              "Couldn't shutdown Eviction Service cleanly.");
        }
      }
      if (getLogWriterI18n().fineEnabled()) {
        getLogWriterI18n().fine(
            "Stopped the EvictorService for region " + getFullPath());
      }
    }
  }

  /**
   * Remove the expiry task from the pending list
   */
  void cancelPendingExpiry(ExpiryTask task)
  {
    synchronized (this.pendingExpires) {
      this.pendingExpires.remove(task);
    }
  }

  /**
   * Used internally by EntryExpiryTask. Ok for it to ignore transaction.
   *
   * @return 0 if statistics not available
   */
  long getLastAccessedTime(Object key) throws EntryNotFoundException
  {
    RegionEntry entry = this.entries.getEntry(key);
    if (entry == null)
      throw new EntryNotFoundException(key.toString());
    try {
      return entry.getLastAccessed();
    }
    catch (InternalStatisticsDisabledException e) {
      return 0;
    }
  }

  /**
   * Used internally by EntryExpiryTask. Ok for it to ignore transaction.
   */
  long getLastModifiedTime(Object key) throws EntryNotFoundException
  {
    RegionEntry entry = this.entries.getEntry(key);
    if (entry == null)
      throw new EntryNotFoundException(key.toString());
    return entry.getLastModified();
  }

  /**
   * get the ImageState for this region
   */
  public final ImageState getImageState() {
    return this.imageState;
  }

  /**
   * Callers of this method should always follow the call with: if (lockGII()) {
   * try { } finally { unlockGII(); } }
   *
   * @return true if lock obtained and unlock needs to be called
   */
  boolean lockGII() {
    ImageState is = getImageState();
    if (is.isReplicate() && !isInitialized()) {
      is.lockGII();
      // recheck initialized while holding lock
      if (isInitialized()) {
        // we didn't need to lock after all so clear and return false
        is.unlockGII();
      } else {
        return true;
      }
    }
    return false;
  }

  void unlockGII() {
    ImageState is = getImageState();
    assert is.isReplicate();
    is.unlockGII();
  }

  /**
   * Callers of this method should always follow the call with: if (lockRIReadLock()) {
   * try { } finally { unlockRIReadLock(); } }
   *
   * @return true if lock obtained and unlock needs to be called
   */
  private boolean lockRIReadLock() {
    if (getImageState().isClient()) {
      getImageState().readLockRI();
      return true;
    } else {
      return false;
    }
  }

  private void unlockRIReadLock() {
    assert getImageState().isClient();
    getImageState().readUnlockRI();
  }

  /** doesn't throw RegionDestroyedException, used by CacheDistributionAdvisor */
  public LocalRegion basicGetParentRegion()
  {
    return this.parentRegion;
  }

  Object basicGetEntryUserAttribute(Object entryKey)
  {
    Map userAttr = this.entryUserAttributes;
    if (userAttr == null) {
      return null;
    }
    return userAttr.get(entryKey);
  }

  /////////////////////// Transaction Helper Methods ////////////////////

  public final boolean supportsTransaction() {
    return this.supportsTX;
  }

  public final TXStateInterface getTXState() {
    if (this.supportsTX) {
      return TXManagerImpl.getCurrentTXState();
    }
    return null;
  }

  protected final TXRegionState txReadRegion(final TXStateInterface tx) {
    if (tx != null) {
      return tx.readRegion(this);
    }
    return null;
  }

  /**
   * Fetchs a value from a region entry for a tx read.
   */
  @Retained
  public final Object getREValueForTXRead(final RegionEntry re) {
    Object vId = null;
    boolean needsLRUCleanup = false;
    try {
      if (re.isDestroyedOrRemoved()) {
        return null;
      }
      LRUEntry le = null;
      if (re instanceof LRUEntry) {
        le = (LRUEntry)re;
        if (re instanceof DiskEntry) {
          if (le.testEvicted()) {
            // Handle the case where we fault in a disk entry
            txLRUStart();
            needsLRUCleanup = true;
            
            // Fault in the value from disk
            re.getValue(this);
          }
        }
      }
      vId = re.getValue(this);
      /*
      if (le != null) {
        le.incRefCount();
      }
      */
    } catch (DiskAccessException dae) {
      handleDiskAccessException(dae, true/* stop bridge servers*/);
      needsLRUCleanup = false;
      throw dae;
    } finally {
      if (needsLRUCleanup) {
        // do this after releasing sync
        txLRUEnd(false);
      }
    }
    return vId;
  }

  /**
   * Fetchs a value from a region entry for a tx read.
   */
  protected final Object getREValueForTXRead(final RegionEntry re,
      final Object key, final TXState lockState) {
    if (lockState != null) {
      return lockState.lockEntryForRead(re, key, this, 0, false, TX_READ_VALUE);
    }
    else {
      return getREValueForTXRead(re);
    }
  }

  //protected static final TXEntryState NOOP_INVALIDATE = new TXEntryState();

  protected final TXStateInterface getJTAEnlistedTX() {
    final TXManagerImpl.TXContext context = TXManagerImpl.currentTXContext();
    if (context == null) {
      return null;
    }
    final TXStateInterface tx = context.getTXState();
    final StaticSystemCallbacks sysCb;
    if (tx != null) {
      if (!ignoreJTA && tx.getProxy().isJCA()) {
        cache.getTxManager().setTXState(null);
        tx.rollback(null);
        throw new IllegalStateException(
            LocalizedStrings.JCA_TRANSACTION_FAILURE.toLocalizedString(this
                .getName()));
      }
      return tx;
    }
    else if ((sysCb = GemFireCacheImpl.getInternalProductCallbacks()) != null) {
      return sysCb.getJTAEnlistedTX(this);
    }
    else {
      javax.transaction.Transaction jtaTx;
      javax.transaction.TransactionManager jtaTxMgr;
      try {
        if (!ignoreJTA
            && (jtaTxMgr = this.cache.getJTATransactionManager()) != null) {
          try {
            if (jtaTxMgr instanceof TransactionManagerImpl) {
              // avoid another ThreadLocal lookup for GFE transaction manager
              jtaTx = ((TransactionManagerImpl)jtaTxMgr)
                  .getTransaction(context);
            }
            else {
              jtaTx = jtaTxMgr.getTransaction();
            }
          } catch (javax.transaction.SystemException se) {
            // ignore; tm may already have been closed
            jtaTx = null;
          }
          if (jtaTx != null) {
//          getLogWriterI18n().fine("DEBUG: getJTAEnlistedTX for " + getName() + ".  no " +
//              "tx state found.  ignoreJTA="+ignoreJTA + "; mgr="
//              + this.cache.getJTATransactionManager() 
//              + "; tx="+ (this.cache.getJTATransactionManager() != null?
//                  this.cache.getJTATransactionManager().getTransaction() : "null"));
            // JTA tx Status check? prior to creating a GF TX
            TXStateProxy txProxy = this.cache.getTxManager().beginJTA();
            jtaTx.registerSynchronization(txProxy);
            return txProxy;
          }
        }
        return null;
      }
      catch (javax.transaction.SystemException se) {
        // this can be thrown when the system is shutting down (see bug #39728)
        stopper.checkCancelInProgress(se);
        jtaEnlistmentFailureCleanup(tx, se);
        return null;
      }
      catch (javax.transaction.RollbackException re) {
        jtaEnlistmentFailureCleanup(tx, re);
        return null;
      }
      catch (IllegalStateException ie) {
        jtaEnlistmentFailureCleanup(tx, ie);
        return null;
      }
    }
  }

  private final void jtaEnlistmentFailureCleanup(final TXStateInterface tx,
      Exception reason) {
    if (cache == null) {
      return;
    }
    this.cache.getCacheTransactionManager().clearTXState();
    if (tx != null) {
      tx.rollback(null);
    }
    String jtaTransName = null;
    try {
      jtaTransName = cache.getJTATransactionManager().getTransaction()
          .toString();
    }
    catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      // Whenever you catch Error or Throwable, you must also
      // check for fatal JVM error (see above).  However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      SystemFailure.checkFailure();
    }

    throw new FailedSynchronizationException(
        LocalizedStrings.LocalRegion_FAILED_ENLISTEMENT_WITH_TRANSACTION_0
            .toLocalizedString(jtaTransName), reason);
  }

  final void txLRUStart() {
    this.entries.disableLruUpdateCallback();
  }

  final void txLRUEnd(boolean doLRUUpdate) {
    this.entries.enableLruUpdateCallback();
    if (doLRUUpdate) {
      try {
        this.entries.lruUpdateCallback();
      } catch (DiskAccessException dae) {
        handleDiskAccessException(dae, true/* stop bridge servers*/);
        throw dae;
      }
    }
  }

  /*
  final void txDecLRURefCount(RegionEntry re) {
    this.entries.lruDecRefCount(re);
  }
  */

  /** ******* DEBUG Methods */
  /** Does not throw RegionDestroyedException even if destroyed */
  List debugGetSubregionNames()
  {
    List names = new ArrayList();
    for (Iterator itr = this.subregions.keySet().iterator(); itr.hasNext();)
      names.add(itr.next());
    return names;
  }

  /*****************************************************************************
   * INNER CLASSES
   ****************************************************************************/

  protected final static void dispatchEvent(LocalRegion region,
      InternalCacheEvent event, EnumListenerEvent op)
  {
    
    CacheListener[] listeners = region.fetchCacheListenersField();
    if (listeners == null || listeners.length == 0) {
      return;
    }
    if (op != EnumListenerEvent.AFTER_REGION_CREATE) {
      try {
        region.waitForRegionCreateEvent();
      }
      catch (CancelException e) {
        // ignore and keep going
        if (region.cache.getLogger().finerEnabled()) {
          region.cache.getLogger().finer(
              "Dispatching events after cache closure for region " + 
              region.getFullPath());
        }
      }
    }
    //Assert.assertTrue(event.getRegion() == region);
    if (!event.isGenerateCallbacks()) {
      return;
    }
// this check moved earlier for bug 36983
//    CacheListener[] listeners = region.fetchCacheListenersField();
//    if (listeners == null || listeners.length == 0)
//      return;
    for (int i = 0; i < listeners.length; i++) {
      CacheListener listener = listeners[i];
      if (listener != null) {
        try {
          op.dispatchEvent(event, listener);
        }
        catch (CancelException ignore) {
          // ignore for bug 37105
        }
        catch (Throwable t) {
          Error err;
          if (t instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)t)) {
            SystemFailure.initiateFailure(err);
            // If this ever returns, rethrow the error. We're poisoned
            // now, so don't let this thread continue.
            throw err;
          }
          // Whenever you catch Error or Throwable, you must also
          // check for fatal JVM error (see above).  However, there is
          // _still_ a possibility that you are dealing with a cascading
          // error condition, so you also need to check to see if the JVM
          // is still usable:
          SystemFailure.checkFailure();
          final GemFireCacheImpl.StaticSystemCallbacks sysCb = GemFireCacheImpl.FactoryStatics.systemCallbacks;
          // GemFireXD has EventCallback public interface, GemFire has CacheListener
          region.getCache().getLoggerI18n().error(
                  sysCb == null ? LocalizedStrings.LocalRegion_EXCEPTION_OCCURRED_IN_CACHELISTENER
                      : LocalizedStrings.LocalRegion_EXCEPTION_OCCURRED_IN_EVENTCALLBACK, t);
        }
      }
    }    
  }

  /** ********************* Class EventDispatcher ***************************** */

  class EventDispatcher implements Runnable
  {
    InternalCacheEvent event;

    EnumListenerEvent op;

    EventDispatcher(InternalCacheEvent event, EnumListenerEvent op) {
                
      if (LocalRegion.this.enableOffHeapMemory && event instanceof EntryEventImpl) {
        // Make a copy that has its own off-heap refcount so fix bug 48837
        event = new EntryEventImpl( (EntryEventImpl)event);   
      }
      this.event = event;
      this.op = op;
    }

    public void run()
    {
      try {
        dispatchEvent(LocalRegion.this, this.event, this.op);
      }finally {
        this.release();
      }
    }
    
    public void release() {
      if (LocalRegion.this.enableOffHeapMemory && this.event instanceof EntryEventImpl) {
        ((EntryEventImpl)this.event).release();
      }      
    }
  }
  
  

  /** ******************* Class SubregionsSet ********************************* */

  /** Set view of subregions */
  private class SubregionsSet extends AbstractSet
   {
    final boolean recursive;

    SubregionsSet(boolean recursive) {
      this.recursive = recursive;
    }

    @Override
    public Iterator iterator()
    {

      // iterates breadth-first (if recursive)
      return new Iterator() {
        Iterator currItr = LocalRegion.this.subregions.values().iterator();

        List itrQ; // FIFO queue of iterators

        Object nextElem = null;

        public void remove()
        {
          throw new UnsupportedOperationException(LocalizedStrings.LocalRegion_THIS_ITERATOR_DOES_NOT_SUPPORT_MODIFICATION.toLocalizedString());
        }

        public boolean hasNext()
        {
          if (nextElem != null) {
            return true;
          }
          else {
            Object el = next(true);
            if (el != null) {
              nextElem = el;
              return true;
            }
            else {
              return false;
            }
          }
        }

        private boolean _hasNext()
        {
          return this.currItr != null && this.currItr.hasNext();
        }
        
        public Object next() {
          return next(false);
        }
        
        /**
         * @param nullOK if true, return null instead of throwing NoSuchElementException
         * @return the next element
         */

        private Object next(boolean nullOK)
        {
          if (nextElem != null) {
            Object next = nextElem;
            nextElem = null;
            return next;
          }

          LocalRegion rgn;
          do {
            rgn = null;
            if (!_hasNext()) {
              if (itrQ == null || itrQ.isEmpty()) {
                if (nullOK) {
                  return null;
                }
                else {
                  throw new NoSuchElementException();
                }
              }
              else {
                this.currItr = (Iterator)itrQ.remove(0);
                continue;
              }
            }
            rgn = (LocalRegion)currItr.next();
          } while (rgn == null || !rgn.isInitialized() || rgn.isDestroyed());

          if (recursive) {
            Iterator nextIterator = rgn.subregions.values().iterator();
            if (nextIterator.hasNext()) {
              if (itrQ == null) {
                itrQ = new ArrayList();
              }
              itrQ.add(nextIterator);
            }
          }
          if (!_hasNext()) {
            if (itrQ == null || itrQ.isEmpty()) {
              this.currItr = null;
            }
            else {
              this.currItr = (Iterator)itrQ.remove(0);
            }
          }
          return rgn;
        }
      };
    }

    @Override
    public int size()
    {
      if (this.recursive) {
        return allSubregionsSize() - 1 /* don't count this region */;
      }
      else {
        return LocalRegion.this.subregions.size();
      }
    }

    @Override
    public Object[] toArray()
    {
      List temp = new ArrayList(this.size());
      for (Iterator iter = this.iterator(); iter.hasNext();) {
        temp.add(iter.next());
      }
      return temp.toArray();
    }

    @Override
    public Object[] toArray(Object[] array)
    {
      List temp = new ArrayList(this.size());
      for (Iterator iter = this.iterator(); iter.hasNext();) {
        temp.add(iter.next());
      }
      return temp.toArray(array);
    }
  }

  /** ******************* Class EntriesSet ************************************ */

  /**
   * Note: if changing to non-final, then change the getClass() comparison in
   * GemFireContainer.PREntriesFullIterator.extractRowLocationFromEntry
   */
  public final class NonTXEntry implements Region.Entry
  {

    private Object key;

    private Object regionVal;

    private boolean entryIsDestroyed = false;

    public boolean isLocal() {
      return true;
    }

    /**
     * Create an Entry given a key. The returned Entry may or may not be
     * destroyed
     */
    public NonTXEntry(RegionEntry regionEntry) {
      this(regionEntry, null);
    }

    /**
     * Create an Entry given a key. The returned Entry may or may not be
     * destroyed
     */
    public NonTXEntry(RegionEntry regionEntry, Object val) {
      if (regionEntry == null) {
        throw new IllegalArgumentException(LocalizedStrings.LocalRegion_REGIONENTRY_SHOULD_NOT_BE_NULL.toLocalizedString());
      }
      // for a soplog region, since the entry may not be in memory,
      // we will have to fetch it from soplog, if the entry is in
      // memory this is a quick lookup, so rather than RegionEntry
      // we keep reference to key
      this.key = regionEntry.getKey();
      this.regionVal = val;
    }

    /** Internal method for getting the underlying RegionEntry */
    public RegionEntry getRegionEntry() {
      RegionEntry re = LocalRegion.this.getRegionMap().getEntry(key);
      if (re == null) {
        throw new EntryDestroyedException(this.key.toString());
      }
      return re;
    }

    private RegionEntry basicGetEntry() {
      RegionEntry re = LocalRegion.this.basicGetEntry(key);
      if (re == null) {
        throw new EntryDestroyedException(this.key.toString());
      }
      return re;
    }

    public boolean isDestroyed()
    {
      if (this.entryIsDestroyed) {
        return true;
      }
      if (LocalRegion.this.isDestroyed) {
        this.entryIsDestroyed = true;
      } else if (LocalRegion.this.basicGetEntry(key) == null) {
        this.entryIsDestroyed = true;
      }
      return this.entryIsDestroyed;
    }

    public Object getKey() {
      RegionEntry re = this.basicGetEntry();
      return _getKey(re);
    }

    private Object _getKey(RegionEntry re) {
      if (this.key instanceof RegionEntry) {
        this.key = re.getKeyCopy();
      }
      return this.key;
    }

    public Object getValue() {
      if (this.regionVal != null) {
        return this.regionVal;
      }
      RegionEntry re = this.basicGetEntry();
      Object value = getDeserialized(re, false, false, false);
      if (value == null) {
        throw new EntryDestroyedException(_getKey(re).toString());
      }
      else if (Token.isInvalid(value)) {
        return null;
      }

      return value;
    }

    /**
     * To get the value from region in serialized form
     * @return {@link VMCachedDeserializable}
     */
    public Object getRawValue()
    {
      if (this.regionVal != null) {
        return this.regionVal;
      }
        Object value = this.basicGetEntry().getValue((LocalRegion) getRegion());
        if (value == null) {
          throw new EntryDestroyedException(this.getRegionEntry().getKeyCopy()
              .toString());
        }
        else if(Token.isInvalid(value)) {
          return null;
        }

        return value;
    }

    public Region getRegion()
    {
      this.basicGetEntry();
      return LocalRegion.this;
    }

    public CacheStatistics getStatistics()
    {
      // prefer entry destroyed exception over statistics disabled exception
      this.basicGetEntry();
      if (!LocalRegion.this.statisticsEnabled) {
        throw new StatisticsDisabledException(LocalizedStrings.LocalRegion_STATISTICS_DISABLED_FOR_REGION_0.toLocalizedString(getFullPath()));
      }
      return new CacheStatisticsImpl(this.basicGetEntry(), LocalRegion.this);
    }

    public Object getUserAttribute()
    {
      this.basicGetEntry();
      Map userAttr = LocalRegion.this.entryUserAttributes;
      if (userAttr == null) {
        return null;
      }
      return userAttr.get(this.basicGetEntry().getKey());
    }

    public Object setUserAttribute(Object value)
    {
      if (isTX()) {
        throw new UnsupportedOperationException(
            LocalizedStrings.TXEntry_UA_NOT_SUPPORTED.toLocalizedString());
      }
      if (LocalRegion.this.entryUserAttributes == null) {
        LocalRegion.this.entryUserAttributes = new Hashtable();
      }
      return LocalRegion.this.entryUserAttributes.put(
          this.basicGetEntry().getKey(), value);
    }

    @Override
    public boolean equals(Object obj)
    {
      if (!(obj instanceof LocalRegion.NonTXEntry)) {
        return false;
      }
      LocalRegion.NonTXEntry lre = (LocalRegion.NonTXEntry)obj;
      return this.basicGetEntry().equals(lre.getRegionEntry())
          && this.getRegion() == lre.getRegion();
    }

    @Override
    public int hashCode()
    {
      return this.basicGetEntry().hashCode() ^ this.getRegion().hashCode();
    }

    @Override
    public String toString() {
      return new StringBuilder("NonTXEntry@").append(
          Integer.toHexString(System.identityHashCode(this))).append(' ')
          .append(this.getRegionEntry()).toString();
    }

    ////////////////// Private Methods
    // /////////////////////////////////////////

    /*
     * throws CacheClosedException or EntryDestroyedException if this entry is
     * destroyed.
     */
    private void checkEntryDestroyed() {
      if (isDestroyed()) {
        throw new EntryDestroyedException(getKey().toString());
      }
    }

    /**
     * @since 5.0
     */
    public Object setValue(Object arg0)
    {
      return put(this.getKey(), arg0);
    }
  }

  /**
   * For internal use only.
   */
  public RegionMap getRegionMap()
  {
    // OK to ignore tx state
    return this.entries;
  }

  /**
   * Methods for java.util.Map compliance
   *
   * @since 5.0
   * @author mbid
   */

  /**
   * (description copied from entryCount() Returns the number of entries in this
   * region. Note that because of the concurrency properties of the
   * {@link RegionMap}, the number of entries is only an approximate. That is,
   * other threads may change the number of entries in this region while this
   * method is being invoked.
   *
   * @see LocalRegion#entryCount()
   */
  public int size()
  {
    checkReadiness();
    checkForNoAccess();

    final TXStateInterface tx = discoverJTA();
    boolean isClient = this.imageState.isClient();
    if (isClient) {
      lockRIReadLock(); // bug #40871 - test sees wrong size for region during RI
    }
    try {
      return entryCount(tx);
    } finally {
      if (isClient) {
        unlockRIReadLock();
      }
    }
  }

  /**
   * returns an estimate of the number of entries in this region. This method
   * should be prefered over size() for hdfs regions where an accurate size is
   * not needed. This method is not supported on a client
   * 
   * @return the estimated size of this region
   */
  public int sizeEstimate() {
    boolean isClient = this.imageState.isClient();
    if (isClient) {
      throw new UnsupportedOperationException(
          "Method not supported on a client");
    }
    return entryCount(discoverJTA(), null, true);
  }

  /**
   * This method returns true if Region is Empty.
   */
  public boolean isEmpty()
  {
    //checkForNoAccess(); // size does this check
    return this.size() > 0 ? false : true;
  }

  /**
   * Returns true if the value is present in the Map
   */
  public boolean containsValue(final Object value)
  {
    if (value == null) {
      throw new NullPointerException(LocalizedStrings.LocalRegion_VALUE_FOR_CONTAINSVALUEVALUE_CANNOT_BE_NULL.toLocalizedString());
    }
    checkReadiness();
    checkForNoAccess();
    boolean result = false;
    Iterator iterator = new EntriesSet(this, false, IteratorType.VALUES, false).iterator();
    Object val = null;
    while (iterator.hasNext()) {
      val = iterator.next();
      if (val != null) {
        if (value.equals(val)) {
          result = true;
          break;
        }
      }
    }
    return result;
  }

  /**
   * Returns a set of the entries present in the Map. This set is Not
   * Modifiable. If changes are made to this set, they will be not reflected in
   * the map
   */
  public Set entrySet()
  {
    //entries(false) takes care of open transactions
    return entries(false);
  }

  
  
  /**
   * Returns a set of the keys present in the Map. This set is Not Modifiable.
   * If changes are made to this set, they will be not reflected in the map
   */

  public Set keySet()
  {
    //keys() takes care of open transactions
    return keys();
  }

  /**
   * removes the object from the Map and returns the object removed. The object
   * is returned only if present in the localMap. If the value is present in
   * another Node, null is returned
   */
  public Object remove(Object obj)
  {
    // no validations needed here since destroy does it for us
    Object returnObject = null;
    try {
      returnObject = destroy(obj);
    }
    catch (EntryNotFoundException e) {
      // No need to log this exception; caller can test for null;
      //       LogWriterI18n writer = getCache().getLoggerI18n();
      //       if (writer.fineEnabled()) {
      //         writer.fine("Entry asked for removal not found",e);
      //       }
    }
    return returnObject;
  }

  public void basicBridgeDestroyRegion( Object p_callbackArg, final ClientProxyMembershipID client,
      boolean fromClient, EventID eventId) throws TimeoutException,
      EntryExistsException, CacheWriterException
  {
    Object callbackArg = p_callbackArg;
    //long startPut = CachePerfStats.getStatTime();
    if (fromClient) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      if (getAttributes().getEnableGateway()) {
        callbackArg = new GatewayEventCallbackArgument(callbackArg);
      }
      if (isGatewaySenderEnabled()
          && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
        callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
      }
    }

    RegionEventImpl event = new BridgeRegionEventImpl(this, Operation.REGION_DESTROY,
         callbackArg,false, client.getDistributedMember(), client/* context */, eventId);

    basicDestroyRegion(event, true);

  }




  public void basicBridgeClear( Object p_callbackArg, 
      final ClientProxyMembershipID client,
      boolean fromClient, EventID eventId) throws TimeoutException,
      EntryExistsException, CacheWriterException
  {
    Object callbackArg = p_callbackArg;
    //long startPut = CachePerfStats.getStatTime();
    if (fromClient) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      if (getAttributes().getEnableGateway()) {
        callbackArg = new GatewayEventCallbackArgument(callbackArg);
      }
      if (isGatewaySenderEnabled()
          && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
        callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
      }
    }

    RegionEventImpl event = new BridgeRegionEventImpl(this, Operation.REGION_CLEAR,
         callbackArg,false, client.getDistributedMember(), client/* context */, eventId);

    basicClear(event, true);
  }



  @Override
  void basicClear(RegionEventImpl regionEvent)
  {
    basicClear(regionEvent, true/* cacheWrite */);
  }

  void basicClear(RegionEventImpl regionEvent, boolean cacheWrite)  {
    cmnClearRegion(regionEvent, cacheWrite, true);
  }
  
  
  void cmnClearRegion(RegionEventImpl regionEvent, boolean cacheWrite, boolean useRVV) {
    RegionVersionVector rvv = null;
    if (useRVV && this.dataPolicy.withReplication() && this.concurrencyChecksEnabled) {
      rvv = this.versionVector.getCloneForTransmission();
    }
    clearRegionLocally(regionEvent, cacheWrite, rvv);
  }

  /**
   * Common code used by both clear and localClear. Asif : On the lines of
   * destroyRegion, this method will be invoked for clearing the local cache.The
   * cmnClearRegion will be overridden in the derived class DistributedRegion
   * too. For clear operation , no CacheWriter will be invoked . It will only
   * have afterClear callback. Also like destroyRegion & invalidateRegion , the
   * clear operation will not take distributedLock. The clear operation will
   * also clear the local tranxnl entries . The clear operation will have
   * immediate committed state.
   */
  void clearRegionLocally(RegionEventImpl regionEvent, boolean cacheWrite, RegionVersionVector vector)
  {
    RegionVersionVector rvv = vector;
    if (this.srp != null) {
      // clients and local regions do not maintain a full RVV.  can't use it with clear()
      rvv = null;
    }
    if (rvv != null && this.dataPolicy.withStorage()) { 
      if (getLogWriterI18n().fineEnabled() || RegionVersionVector.DEBUG) {
        getLogWriterI18n().info(LocalizedStrings.DEBUG,
            "waiting for my version vector to dominate\nmine=" + this.versionVector.fullToString()
            +"\nother=" + rvv);
      }
      boolean result = this.versionVector.waitToDominate(rvv, this);
      if (!result) {
        if (getLogWriterI18n().fineEnabled() || RegionVersionVector.DEBUG) {
          getLogWriterI18n().info(LocalizedStrings.DEBUG,
              "incrementing clearTimeouts for " + this.getName() + " rvv=" + this.versionVector.fullToString());
        }
        getCachePerfStats().incClearTimeouts();
      }
    }
    
    //final LogWriterI18n logger = this.getCache().getLoggerI18n();
    //If the initial image operation is still in progress
    // then we need will have to do the clear operation at the
    // end of the GII.For this we try to acquire the lock of GII
    // the boolean returned is true that means lock was obtained which
    // also means that GII is still in progress.
    boolean isGIIInProg = lockGII();
    if (isGIIInProg) {
      //Set a flag which will indicate that the Clear was invoked.
      // Also we should try & abort the GII
      try {
        getImageState().setClearRegionFlag(true /* Clear region */, rvv);
        //logger.fine("Set clear flag since GII is in progress");
      }
      finally {
        unlockGII();
      }
    }
    //logger.fine("starting region clear");

    if (cacheWrite && !isGIIInProg) {
      this.cacheWriteBeforeRegionClear(regionEvent);
    }
    
    RegionVersionVector myVector = getVersionVector();
    if (myVector != null) {
      LogWriterI18n log = getLogWriterI18n();
      if (log.fineEnabled()) {
        log.fine("processing version information for " + regionEvent);
      }
      if (!regionEvent.isOriginRemote() && !regionEvent.getOperation().isLocal()) {
        // generate a new version for the operation
        VersionTag tag = VersionTag.create(getVersionMember());
        tag.setVersionTimeStamp(cacheTimeMillis());
        tag.setRegionVersion(myVector.getNextVersionWhileLocked(null));
        if (log.fineEnabled() || RegionVersionVector.DEBUG) {
          log.info(LocalizedStrings.DEBUG, "recording version tag for clear: " + tag);
        }
        regionEvent.setVersionTag(tag);
      } else {
        VersionTag tag = regionEvent.getVersionTag();
        if (tag != null) {
          if (log.fineEnabled()) {
            log.fine("recording version tag for clear: " + tag);
          }
          myVector.recordVersion(tag.getMemberID(), tag, null); // clear() events always have the ID in the tag
        }
      }
    }

    //  Asif:Clear the expirational task for all the entries. It is possible that
    //after clearing it some new entries may get added befoe issuing clear
    //on the map , but that should be OK, as the expirational thread will
    //silently move ahead if the entry to be expired no longer existed
    this.cancelAllEntryExpiryTasks();
    if (this.entryUserAttributes != null) {
      this.entryUserAttributes.clear();
    }

    // if all current content has been removed then the version vector
    // does not need to retain any exceptions and the GC versions can
    // be set to the current vector versions
    if (rvv == null  &&  myVector != null) {
      myVector.removeOldVersions();
    }
    
    /*
     * Asif : First we need to clear the Tranxl state for the current region for
     * the thread. The operation will not take global lock similar to
     * regionInvalidateor regionDestroy behaviour.
     */

    // mbid : clear the disk region if present
    if (diskRegion != null) {
      // persist current rvv and rvvgc which contained version for clear() itself
      if (this.getDataPolicy().withPersistence()) {
        // null means not to change dr.rvvTrust
        if (getLogWriterI18n().fineEnabled() || RegionVersionVector.DEBUG) {
          getLogWriterI18n().info(LocalizedStrings.DEBUG, "Clear: Saved current rvv: "+diskRegion.getRegionVersionVector());
        }
        diskRegion.writeRVV(this, null);
        diskRegion.writeRVVGC(this);
      }
      
      // clear the entries in disk
      diskRegion.clear(this, rvv);
    }
    
    // this will be done in diskRegion.clear if it is not null else it has to be
    // done here
    else {
      // Now remove the tranxnl entries for this region
      this.txClearRegion();
      // Now clear the map of committed entries
      Set<VersionSource> remainingIDs = clearEntries(rvv);
      if (!this.dataPolicy.withPersistence()) { // persistent regions do not reap IDs
        if (myVector != null) {
          myVector.removeOldMembers(remainingIDs);
        }
      }
    }
    
    clearHDFSData();
    
    if (!isProxy()) {
      // Now we need to recreate all the indexes.
      //If the indexManager is null we don't have to worry
      //for any other thread creating index at that instant
      // because the region has already been cleared
      //of entries.
      //TODO Asif:Have made indexManager variable is made volatile. Is it
      // necessary?
      if (this.indexManager != null) {
        try {
          this.indexManager.rerunIndexCreationQuery();
        }
        catch (QueryException qe) {
          // Asif : Create an anonymous inner class of CacheRuntimeException so
          // that a RuntimeException is thrown
          throw new CacheRuntimeException(LocalizedStrings.LocalRegion_EXCEPTION_OCCURED_WHILE_RE_CREATING_INDEX_DATA_ON_CLEARED_REGION.toLocalizedString(), qe) {
            private static final long serialVersionUID = 0L;            
          };
        }
      }
    }
    //logger.fine(), qe));
    
    if (ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
      CacheObserverHolder.getInstance().afterRegionClear(regionEvent);
    }

    if (isGIIInProg) {
      return;
    }
    regionEvent.setEventType(EnumListenerEvent.AFTER_REGION_CLEAR);
    // notifyBridgeClients(EnumListenerEvent.AFTER_REGION_CLEAR, regionEvent);
    //Issue a callback to afterClear if the region is initialized
    boolean hasListener = hasListener();
    if (hasListener) {
      dispatchListenerEvent(EnumListenerEvent.AFTER_REGION_CLEAR, regionEvent);
    }
  }

  /**Clear HDFS data, if present */
  protected void clearHDFSData() {
    //do nothing, clear is implemented for subclasses like BucketRegion.
  }

  @Override
  void basicLocalClear(RegionEventImpl rEvent)
  {
    cmnClearRegion(rEvent, false/* cacheWrite */, false/*useRVV*/);
  }
       
  public void handleInterestEvent(InterestRegistrationEvent event) {
    throw new UnsupportedOperationException(LocalizedStrings.
        LocalRegion_REGION_INTEREST_REGISTRATION_IS_ONLY_SUPPORTED_FOR_PARTITIONEDREGIONS
        .toLocalizedString());
  }  
  
  @Override
  Map basicGetAll(Collection keys) {
    LogWriterI18n logger = getCache().getLoggerI18n();
    if (logger.fineEnabled()) {
      logger.fine("Processing getAll request for: " + keys);
    }
    TXStateInterface tx = discoverJTA();
    Map allResults = new HashMap();
    if (hasServerProxy()) {
      // Create a copy of the collection of keys
      // to keep the original collection intact
      List keysCopy = new ArrayList();
      keysCopy.addAll(keys);

      // Gather any local values
      Map localResults = new HashMap();
      for (Iterator i = keysCopy.iterator(); i.hasNext();) {
        Object key = i.next();
        Object value;
        Region.Entry entry = accessEntry(key, tx, true);
        if (entry != null && (value = entry.getValue()) != null) {
          localResults.put(key, value);
          i.remove();
        }
      }

      allResults.putAll(localResults);
      if (logger.fineEnabled()) {
        logger.fine("Added local results for getAll request: " + localResults);
      }

      // Send the rest of the keys to the server (if necessary)
      if (!keysCopy.isEmpty()) {
        VersionedObjectList remoteResults = getServerProxy().getAll(keysCopy);
        if (logger.fineEnabled()) {
          logger.fine("remote getAll results are " + remoteResults);
        }

        // Add remote results to local cache and all results if successful
        for (VersionedObjectList.Iterator it = remoteResults.iterator(); it.hasNext();) {
          VersionedObjectList.Entry entry = it.next();
          Object key = entry.getKey();
          boolean notOnServer = entry.isKeyNotOnServer();
          // in 7.5 we added transfer of tombstones with RI/getAll results for bug #40791
          boolean createTombstone = false;
          if (notOnServer) {
            createTombstone = (entry.getVersionTag() != null && this.concurrencyChecksEnabled);
            allResults.put(key, null);
            if (logger.fineEnabled()) {
              logger.fine("Added remote result for missing key: " + key);
            }
            if (!createTombstone) {
              continue;
            }
          }
          
          Object value;
          if (createTombstone) {
            // the value is null in this case, so use TOKEN_TOMBSTONE
            value = Token.TOMBSTONE;
          } else {
            value = entry.getObject();
          }
          VersionTag versionTag = entry.getVersionTag();

          if (value instanceof Throwable){
            continue;
          }
          long startPut = CachePerfStats.getStatTime();
          validateKey(key);
          Operation op = Operation.LOCAL_LOAD_CREATE;

          EntryEventImpl event = EntryEventImpl.create(
              this, op, key, value,
              null, false, getMyId(), true);
          try {
          event.setFromServer(true);
          event.setTXState(tx);
          event.setVersionTag(versionTag);

          if (!alreadyInvalid(key, event)) { // bug #47716 - don't update if it's already here & invalid
            TXManagerImpl txMgr = this.cache.getCacheTransactionManager();
            tx = txMgr.internalSuspend();
            try {
              basicPutEntry(event, 0L);
            } catch (ConcurrentCacheModificationException e) {
              logger.fine("getAll result for " + key
                  + " not stored in cache due to concurrent modification");
            } finally {
              this.cache.getTxManager().resume(tx);
            }
            getCachePerfStats().endPut(startPut, event.isOriginRemote());
          }

          if (!createTombstone) {
            allResults.put(key, value);
            if (logger.fineEnabled()) {
              logger.fine("Added remote result for getAll request: " + key + ", " + value);
            }
          }
          } finally {
            event.release();
          }
        }
      }
    } else {
      // This implementation for a P2P VM is a stop-gap to provide the
      // functionality. It needs to be rewritten more efficiently.
      for (Iterator i = keys.iterator(); i.hasNext();) {
        Object key = i.next();
        Object value;
        try {
          operationStart();
          value = get(key, null, true, false, false, null, tx, null, null,
              false, true/*allowReadFromHDFS*/);
          if (Token.isInvalid(value)) {
            value = null;
          }
          allResults.put(key, value);
        } catch (Exception e) {
          logger.warning(LocalizedStrings
              .LocalRegion_THE_FOLLOWING_EXCEPTION_OCCURRED_ATTEMPTING_TO_GET_KEY_0,
                  key, e);
        } finally {
          operationCompleted();
        }
      }
    }
    return allResults;
  }

  private void verifyPutAllMap(Map map) {
    Map.Entry mapEntry = null;
    Collection theEntries = map.entrySet();
    Iterator iterator = theEntries.iterator();
    while (iterator.hasNext()) {
      mapEntry = (Map.Entry)iterator.next();
      Object key = mapEntry.getKey();
      if (mapEntry.getValue() == null || key == null) {
        throw new NullPointerException("Any key or value in putAll should not be null");
      }
      if (!MemoryThresholds.isLowMemoryExceptionDisabled()) {
        checkIfAboveThreshold(key);
      }
      // Threshold check should not be performed again 
    }
  }

  /**
   * Called on a bridge server when it has a received a putAll command from a client.
   * @param map a map of key->value for the entries we are putting
   * @param retryVersions a map of key->version tag. If any of the entries
   * are the result of a retried client event, we need to make sure we send
   * the original version tag along with the event.
   */
  public VersionedObjectList basicBridgePutAll(Map map, Map<Object, VersionTag> retryVersions, ClientProxyMembershipID memberId,
      EventID eventId, boolean skipCallbacks) throws TimeoutException, CacheWriterException
  {
    Object callbackArg = null;
    long startPut = CachePerfStats.getStatTime();
    if (getAttributes().getEnableGateway()) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      callbackArg = new GatewayEventCallbackArgument(callbackArg);
    }
    if (isGatewaySenderEnabled()
        && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
      callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
    }

    final EntryEventImpl event = EntryEventImpl.create(this, Operation.PUTALL_CREATE, null,
        null /* new value */, callbackArg,
        false /* origin remote */, memberId.getDistributedMember(),
        !skipCallbacks /* generateCallbacks */,
        eventId);
    try {
    event.setContext(memberId);
    DistributedPutAllOperation putAllOp = new DistributedPutAllOperation(this,
        event, map.size(), true);
    try {
    VersionedObjectList result = basicPutAll(map, putAllOp, retryVersions);
    getCachePerfStats().endPutAll(startPut);
    return result;
    } finally {
      putAllOp.freeOffHeapResources();
    }
    } finally {
      event.release();
    }
  }

  public VersionedObjectList basicImportPutAll(Map map, boolean skipCallbacks) {
    long startPut = CachePerfStats.getStatTime();

    // generateCallbacks == false
    EntryEventImpl event = EntryEventImpl.create(this, Operation.PUTALL_CREATE,
        null, null, null, true, getMyId(), !skipCallbacks);
    DistributedPutAllOperation putAllOp = new DistributedPutAllOperation(this,
        event, map.size(), false);
    try {
    VersionedObjectList result = basicPutAll(map, putAllOp, null);
    getCachePerfStats().endPutAll(startPut);
    return result;
    } finally {
      putAllOp.freeOffHeapResources();
    }
  }

  public final void putAll(Map map) {
    long startPut = CachePerfStats.getStatTime();
    final DistributedPutAllOperation putAllOp = newPutAllOperation(map);
    if (putAllOp != null) {
      //Because Gemfirexd directly calls basicPutAll, the offheap resources freeing is 
      // done in the function basicPutAll     
      basicPutAll(map, putAllOp, null);
      
    }
    
    getCachePerfStats().endPutAll(startPut);
  }
  
  /**
   * Returns true if a one-hop (RemoteOperationMessage) should be used when applying the change
   * to the system.
   */
  public boolean requiresOneHopForMissingEntry(EntryEventImpl event) {
    return false;
  }

  public VersionedObjectList basicPutAll(final Map<?, ?> map,
      final DistributedPutAllOperation putAllOp, final Map<Object, VersionTag> retryVersions) {
    try {
      final long lastModifiedTime = cacheTimeMillis();
      final EntryEventImpl event = putAllOp.getBaseEvent();
      event.setEntryLastModified(lastModifiedTime);
      EventID eventId = event.getEventId();
      final LogWriterI18n log = getCache().getLoggerI18n();
      if (eventId == null && generateEventID()) {
        // Gester: We need to "reserve" the eventIds for the entries in map here
        event.reserveNewEventId(cache.getDistributedSystem(), map.size());
        eventId = event.getEventId();
      }

      final TXStateInterface tx = putAllOp.txState;
      // if there are remote recipients and operation requires further
      // remote operations, then need to flush any batched ops (#44530)
      final IndexUpdater indexUpdater;
      if (tx != null && (indexUpdater = getIndexUpdater()) != null
          && indexUpdater.hasRemoteOperations(event.op)) {
        tx.flushPendingOps(null);
      }
      RuntimeException e = null;
      verifyPutAllMap(map);
      VersionedObjectList proxyResult = null;
      boolean partialResult = false;
      if (hasServerProxy()) {
        // send message to bridge server
        /*
         * TODO: merge: what does this do for client TX? if (tx != null) {
         * TXStateProxyImpl tx = (TXStateProxyImpl)cache.getTxManager()
         * .getTXState(); tx.getRealDeal(null, this); }
         */
        try {
          proxyResult = getServerProxy().putAll(map, eventId,
              !event.isGenerateCallbacks());
          if (log.fineEnabled()) {
            log.fine("PutAll received response from server: " + proxyResult);
          }
        } catch (PutAllPartialResultException e1) {
          // adjust the map to only add succeeded entries, then apply the
          // adjustedMap
          proxyResult = e1.getSucceededKeysAndVersions();
          partialResult = true;
          if (log.fineEnabled()) {
            getCache().getLoggerI18n().fine(
                "putAll in client encountered a PutAllPartialResultException:"
                    + e1.getMessage() + "\n. Adjusted keys are: "
                    + proxyResult.getKeys());
          }
          Throwable txException = e1.getFailure();
          while (txException != null) {
            if (txException instanceof TransactionException) {
              e = (TransactionException) txException;
              break;
            }
            txException = txException.getCause();
          }
          if (e == null) {
            e = new ServerOperationException(
                LocalizedStrings.Region_PutAll_Applied_PartialKeys_At_Server_0
                    .toLocalizedString(getFullPath()),
                e1.getFailure());
          }
        }
      }

      final VersionedObjectList succeeded = new VersionedObjectList(map.size(),
          true, this.concurrencyChecksEnabled);
      // if this is a transactional putAll, we will not have version information
      // as it is only generated at commit
      // so treat transactional putAll as if the server is not versioned
      final boolean serverIsVersioned = proxyResult != null && proxyResult.regionIsVersioned()
          && !isTX() && this.dataPolicy != DataPolicy.EMPTY;
      if (!serverIsVersioned && !partialResult) {
        // we don't need server information if it isn't versioned or if the region is empty
        proxyResult = null;
      }
      lockRVVForPutAll();
      try {
        // synchronize for bug 39014
        final DistributedPutAllOperation dpao = putAllOp;
        int size = (proxyResult == null) ? map.size() : proxyResult.size();

        if (isInternalRegion()) {
          if (getLogWriterI18n().finerEnabled()) {
            getLogWriterI18n().finer(
                "size of put result is " + size + " map is " + map
                    + " proxyResult is " + proxyResult);
          }
        } else {
          if (getLogWriterI18n().fineEnabled()) {
            getLogWriterI18n().fine(
                "size of put result is " + size + " map is " + map
                    + " proxyResult is " + proxyResult);
          }
        }

        final PutAllPartialResult partialKeys = new PutAllPartialResult(size);
        final Iterator iterator;
        final boolean isVersionedResults;
        int putAllSize = 0;
        if (proxyResult != null) {
          iterator = proxyResult.iterator();
          putAllSize = proxyResult.size();
          isVersionedResults = true;
        } else {
          iterator = map.entrySet().iterator();
          putAllSize = map.size();
          isVersionedResults = false;
        }
        UMMMemoryTracker memoryTracker = null;

        Runnable r = new Runnable() {
          public void run() {
            int offset = 0;
            int callbackArgIndx = 0;
            EntryEventImpl tagHolder = EntryEventImpl
                  .createVersionTagHolder();
            while (iterator.hasNext()) {
              stopper.checkCancelInProgress(null);
              Map.Entry mapEntry = (Map.Entry) iterator.next();
              final Object key = mapEntry.getKey();
              VersionTag versionTag = null;
              tagHolder.setVersionTag(null);
              final Object value;
              boolean overwritten = false;
              if (isVersionedResults) {
                versionTag = ((VersionedObjectList.Entry) mapEntry)
                    .getVersionTag();
                value = map.get(key);
                if (log.fineEnabled()) {
                  log.fine("putAll key " + key + " -> " + value + " version="
                      + versionTag);
                  // if (versionTag == null) {
                  // log.fine(" serverIsVersioned=" + serverIsVersioned +
                  // " ccEnabled=" + concurrencyChecksEnabled);
                  // }
                }
                if (versionTag == null && serverIsVersioned
                    && concurrencyChecksEnabled && dataPolicy.withStorage()) {
                  // server was unable to determine the version for this
                  // operation.
                  // This can happen in a PR with redundancy if there is a
                  // bucket
                  // failure or migration during the operation. We destroy the
                  // entry since we don't know what its state should be (but the
                  // server should)
                  if (log.fineEnabled()) {
                    log.fine("server returned no version information for "
                        + key);
                  }
                  localDestroyNoCallbacks(key);
                  // to be consistent we need to fetch the current entry
                  get(key, null, false, null);
                  overwritten = true;
                }
              } else {
                value = mapEntry.getValue();
                if (isInternalRegion()) {
                  if (log.finerEnabled()) {
                    log.finer("putAll " + key + " -> " + value);
                  }
                } else {
                  if (log.fineEnabled()) {
                    log.fine("putAll " + key + " -> " + value);
                  }
                }

              }
              try {
                if (serverIsVersioned) {
                  if (log.fineEnabled()) {
                    log.fine("associating version tag with " + key
                        + " version=" + versionTag);
                  }
                  // If we have received a version tag from a server, add it to
                  // the event
                  tagHolder.setVersionTag(versionTag);
                  tagHolder.setFromServer(true);
                } else if (retryVersions != null
                    && retryVersions.containsKey(key)) {
                  // If this is a retried event, and we have a version tag for
                  // the retry,
                  // add it to the event.
                  tagHolder.setVersionTag(retryVersions.get(key));
                }

                if (!overwritten) {
                  try {
                    operationStart();
                    basicEntryPutAll(key, value, dpao, offset,
                        dpao.getCallbackArg(callbackArgIndx++), tagHolder,
                        lastModifiedTime);
                  } finally {
                    operationCompleted();
                    tagHolder.release();
                  }
                }
                // now we must check again since the cache may have closed
                // during
                // distribution (causing this process to not receive and queue
                // the
                // event for clients
                stopper.checkCancelInProgress(null);
                succeeded.addKeyAndVersion(key, tagHolder.getVersionTag());
              } catch (Exception ex) {
                if (log.fineEnabled() || DistributionManager.VERBOSE) {
                  log.info(LocalizedStrings.DEBUG,
                      "PutAll operation encountered exception for key " + key,
                      ex);
                }
                partialKeys.saveFailedKey(key, ex);
              }
              offset++;
            }
          }
        };
        try {
          if (callback.isSnappyStore()
                  && !this.isInternalRegion()) {
            memoryTracker = new UMMMemoryTracker(
                Thread.currentThread().getId(), putAllSize);
            putAllOp.getEvent().setBufferedMemoryTracker(memoryTracker);
          }
          this.syncPutAll(tx, r, eventId);
        } finally {
          if (memoryTracker != null) {
            long unusedMemory = memoryTracker.freeMemory();
            if (unusedMemory > 0) {
              callback.releaseStorageMemory(
                  memoryTracker.getFirstAllocationObject(), unusedMemory, false);
            }
          }
        }

        if (partialKeys.hasFailure()) {
          partialKeys.addKeysAndVersions(succeeded);
          getGemFireCache().getLoggerI18n().info(
              LocalizedStrings.Region_PutAll_Applied_PartialKeys_0_1,
              new Object[] { getFullPath(), partialKeys });
          getGemFireCache().getLoggerI18n().fine(partialKeys.detailString());
          if (e == null) {
            // if received exception from server first, ignore local exception
            if (dpao.isBridgeOperation()) {
              if (partialKeys.getFailure() instanceof CancelException) {
                e = (CancelException) partialKeys.getFailure();
              } else if (partialKeys.getFailure() instanceof LowMemoryException) {
                throw partialKeys.getFailure(); // fix for #43589
              } else {
                e = new PutAllPartialResultException(partialKeys);
              }
            } else {
              throw partialKeys.getFailure();
            }
          }
        }
      } catch (LowMemoryException lme) {
        throw lme;
      } catch (RuntimeException ex) {
        e = ex;
      } catch (Exception ex) {
        e = new RuntimeException(ex);
      } finally {
        unlockRVVForPutAll();
      }
      getDataView(tx).postPutAll(putAllOp, succeeded, this);
      if (e != null) {
        throw e;
      }
      return succeeded;
    } finally {
      putAllOp.getBaseEvent().release();
      putAllOp.freeOffHeapResources();
    }
  }
  
  /**
   *  bug #46924 - putAll can be partially applied when a clear() occurs, leaving
   *  the cache in an inconsistent state.  Set the RVV to "cache op in progress"
   *  so clear() will block until the putAll completes.  This won't work for
   *  non-replicate regions though since they uses one-hop during basicPutPart2
   *  to get a valid version tag.
   */
  private void lockRVVForPutAll() {
    if (this.versionVector != null && this.dataPolicy.withReplication()) {
      this.versionVector.lockForCacheModification(this);
    }
  }
  
  private void unlockRVVForPutAll() {
    if (this.versionVector != null && this.dataPolicy.withReplication()) {
      this.versionVector.releaseCacheModificationLock(this);
    }
  }

  // split into a separate newPutAllOperation since GemFireXD may need to
  // manipulate event before doing the put (e.g. posDup flag)
  public final DistributedPutAllOperation newPutAllOperation(Map<?, ?> map) {
    if (map == null) {
      throw new NullPointerException(LocalizedStrings
          .AbstractRegion_MAP_CANNOT_BE_NULL.toLocalizedString());
    }
    if (map.isEmpty()) {
      return null;
    }
    checkReadiness();
    checkForLimitedOrNoAccess();
    final TXStateInterface tx = discoverJTA();

    // Create a dummy event for the PutAll operation.  Always create a
    // PutAll operation, even if there is no distribution, so that individual
    // events can be tracked and handed off to callbacks in postPutAll
    final EntryEventImpl event = EntryEventImpl.create(this,
        Operation.PUTALL_CREATE, null, null, null, true, getMyId());
    event.disallowOffHeapValues();
    event.setTXState(tx);
    return new DistributedPutAllOperation(this, event, map.size(), false);
  }

  /**
   * Creates an EntryEventImpl that is optimized to not fetch data from HDFS.
   * This is meant to be used by bulk updates by PUT dml from GemFireXD.
   * @see #newPutEntryEvent(Object, Object, Object)
   */
  public final DistributedPutAllOperation newPutAllForPUTDmlOperation(Map<?, ?> map) {
    DistributedPutAllOperation dpao = newPutAllOperation(map);
    dpao.getEvent().setFetchFromHDFS(false);
    dpao.getEvent().setPutDML(true);
    return dpao;
  }

  /**
   * @param key the cache key
   * @param value the cache value
   * @param putallOp the DistributedPutAllOperation associated with the event
   * @param tagHolder holder for version tag
   * @throws TimeoutException if the operation times out
   * @throws CacheWriterException if a cache writer objects to the update
   */
  protected final void basicEntryPutAll(Object key,
      Object value,
      DistributedPutAllOperation putallOp,
      int offset, Object callbackArg, EntryEventImpl tagHolder, final long lastModifiedTime)
      throws TimeoutException, CacheWriterException {

    assert putallOp != null;
//    long startPut = CachePerfStats.getStatTime();
    checkReadiness();
    if (value == null) {
      throw new NullPointerException(LocalizedStrings.LocalRegion_VALUE_CANNOT_BE_NULL.toLocalizedString());
    }
    validateArguments(key, value, null);
    // event is marked as a PUTALL_CREATE but if the entry exists it
    // will be changed to a PUTALL_UPDATE later on.
    EntryEventImpl event = EntryEventImpl.createPutAllEvent(
        putallOp, this, Operation.PUTALL_CREATE, key, value, callbackArg);
    event.setFetchFromHDFS(putallOp.getEvent().isFetchFromHDFS());
    event.setPutDML(putallOp.getEvent().isPutDML());
    event.setBufferedMemoryTracker(putallOp.getEvent().getMemoryTracker());
    try {
    if (tagHolder != null) {
      event.setVersionTag(tagHolder.getVersionTag());
      event.setFromServer(tagHolder.isFromServer());
    }
    if (generateEventID()) {
      event.setEventId(new EventID(putallOp.getEvent().getEventId(), offset));
    }
    final TXStateInterface tx = putallOp.txState;
    event.setTXState(tx);

    /*
     * If this is tx, do putEntry, unless it is a local region? 
     */
    performPutAllEntry(event, tx, lastModifiedTime);
    if (tagHolder != null) {
      tagHolder.setVersionTag(event.getVersionTag());
      tagHolder.isConcurrencyConflict(event.isConcurrencyConflict());
    }
    } finally {
      event.release();
    }
  }

  public void performPutAllEntry(final EntryEventImpl event,
      final TXStateInterface tx, final long lastModifiedTime) {
    /*
     * If this is a putall, what do we do UGH
     */
    getDataView(tx)
        .putEntry(event, false, false, null, false, false, lastModifiedTime, false);
  }

  public void postPutAllFireEvents(DistributedPutAllOperation putallOp, VersionedObjectList successfulPuts)
  {
    if (!this.dataPolicy.withStorage() && this.concurrencyChecksEnabled
        && putallOp.getBaseEvent().isBridgeEvent()) {
      // if there is no local storage we need to transfer version information
      // to the successfulPuts list for transmission back to the client
      successfulPuts.clear();
      putallOp.fillVersionedObjectList(successfulPuts);
    }
    Set successfulKeys = new HashSet(successfulPuts.size());
    for (Object key: successfulPuts.getKeys()) {
      successfulKeys.add(key);
    }
    for (Iterator it=putallOp.eventIterator(); it.hasNext(); ) {
      EntryEventImpl event = (EntryEventImpl)it.next();
      if (successfulKeys.contains(event.getKey())) {
        EnumListenerEvent op = event.getOperation().isCreate() ? EnumListenerEvent.AFTER_CREATE
            : EnumListenerEvent.AFTER_UPDATE; 
        invokePutCallbacks(op, event, !event.callbacksInvoked() && !event.isPossibleDuplicate(),
            false /* We must notify gateways inside RegionEntry lock, NOT here, to preserve the order of events sent by gateways for same key*/);
      }
    }
  }

  public void postPutAllSend(DistributedPutAllOperation putallOp,
      TXStateProxy txProxy, VersionedObjectList successfulPuts) { 
    /* No-op for local region of course */
  }

  /**
   * DistributedRegion overrides isCurrentlyLockGrantor
   *
   * @see DistributedRegion#isCurrentlyLockGrantor()
   */
  @Override
  protected boolean isCurrentlyLockGrantor()
  {
    return false;
  }

  /**
   * Handle a local region destroy or a region close that was done on this
   * region in a remote vm. Currently the only thing needed is to have the
   * advisor
   *
   * @param sender the id of the member that did the remote operation
   * @param topSerial the remote serialNumber for the top region (maybe root)
   * @param subregionSerialNumbers map of remote subregions to serialNumbers
   * @param regionDestroyed true if the region was destroyed on the remote host (as opposed to closed)
   * @since 5.0
   */
  final void handleRemoteLocalRegionDestroyOrClose(
      InternalDistributedMember sender,
      int topSerial,
      Map subregionSerialNumbers, boolean regionDestroyed) {

    final int oldLevel = setThreadInitLevelRequirement(LocalRegion.ANY_INIT);
    // go
    // through
    // initialization
    // latches
    try {
      basicHandleRemoteLocalRegionDestroyOrClose(
            sender, topSerial, subregionSerialNumbers, false, regionDestroyed);
    }
    finally {
      setThreadInitLevelRequirement(oldLevel);
    }
  }

  /**
   * receive notification that a wan hub has been created The other members
   * hosting this region are informed that this member has a hub running for
   * this region.
   * 
   * @since SqlFabric
   */
  void hubCreated(int hubTypeVal)
  {
    if (hubTypeVal > this.hubType) {
      // tell others of the change in status
      this.hubType = hubTypeVal;
      distributeUpdatedProfileOnHubCreation();
    }
  }
  
  /**
   * @since SqlFabric
   *
   */
  void distributeUpdatedProfileOnHubCreation()
  {
    // No op
  }  
  
  /**
   * @since SqlFabric
   * @return int indicating the hub type
   */
  int getHubType() {
    return this.hubType;
  }
  /**
   * Does the core work for handleRemoteLocalRegionDestroyOrClose.
   *
   * @param sender the id of the member that did the remote operation
   * @param topSerial the remote serialNumber for the top region (maybe root)
   * @param subregionSerialNumbers remote map of subregions to serialNumbers
   * @param regionDestroyed 
   * @since 5.0
   */
  private final void basicHandleRemoteLocalRegionDestroyOrClose(
      InternalDistributedMember sender,
      int topSerial,
      Map subregionSerialNumbers,
      boolean subregion, boolean regionDestroyed) {

    // use topSerial unless this region is in subregionSerialNumbers map
    int serialForThisRegion = topSerial;

    if (subregion) {
      // OPTIMIZE: we don't know if this rgn is a subregion or the top region
      Integer serialNumber = (Integer) subregionSerialNumbers.get(getFullPath());
      if (serialNumber == null) {
        // sender didn't have this subregion
        return;
      }
      else {
        // non-null means this is a subregion under the destroyed region
        serialForThisRegion = serialNumber.intValue();
      }
    }

    // remove sender's serialForThisRegion from the advisor
    removeSenderFromAdvisor(sender, serialForThisRegion, regionDestroyed);

    // process subregions...
    for (Iterator itr = this.subregions.values().iterator(); itr.hasNext();) {
      LocalRegion r = toRegion(itr.next());
      if (r != null && !r.isDestroyed()) {
        // recursively call basicHandleRemoteLocalRegionDestroyOrClose for subregions
        r.basicHandleRemoteLocalRegionDestroyOrClose(
            sender, topSerial, subregionSerialNumbers, true, regionDestroyed);
      }
    }
  }
  
  /**
   * Remove the specified sender from this regions advisor.
   * @param regionDestroyed 
   *
   * @since 5.0
   */
  protected void removeSenderFromAdvisor(InternalDistributedMember sender, int serial, boolean regionDestroyed)
  {
    // nothing needs to be done here since LocalRegion does not have an advisor.
  }

  /**
   * @return Returns the isUsedForPartitionedRegionAdmin.
   */
  final public boolean isUsedForPartitionedRegionAdmin()
  {
    return this.isUsedForPartitionedRegionAdmin;
  }

  /**
   * This method determines whether this region should synchronize with peer replicated regions
   * when the given member has crashed.
   * @param id the crashed member
   * @return true if synchronization should be attempted
   */
  public boolean shouldSyncForCrashedMember(InternalDistributedMember id) {
    return this.concurrencyChecksEnabled
        && this.dataPolicy.withReplication()
        && !this.isUsedForPartitionedRegionAdmin
        && !this.isUsedForMetaRegion
        && !this.isUsedForSerialGatewaySenderQueue;
  }

 /**
   * forces the diskRegion to switch the oplog
   * @since 5.1
   */
  public void forceRolling() throws DiskAccessException {
    if(this.diskRegion!=null){
      diskRegion.forceRolling();
    }
  }
  
  /**
   * @deprecated as of prPersistSprint1 use forceCompaction instead
   */
  @Deprecated
  public boolean notifyToRoll() {
    return forceCompaction();
  }

  /**
   * filterProfile holds CQ and registerInterest information for clients
   * having this region
   */
  FilterProfile filterProfile;
  
  /**
   * 
   * @return int array containing the IDs of the oplogs which will potentially
   * get rolled else null if no oplogs were available at the time of signal or region
   * is not having disk persistence. Pls note that the actual number of oplogs 
   * rolled may be more than what is indicated
   * @since prPersistSprint1
   */
  @Override
  public boolean forceCompaction()
  {
    DiskRegion dr = getDiskRegion();
    if (dr != null) {
      if (dr.isCompactionPossible()) {
        return dr.forceCompaction();
      } else {
        throw new IllegalStateException("To call notifyToCompact you must configure the region with <disk-write-attributes allow-force-compaction=true/>");
      }
    }
    else {
      return false;
    }
  }

  @Override
  public File[] getDiskDirs() {
    if (getDiskStore() != null) {
      return getDiskStore().getDiskDirs();
    } else {
      return this.diskDirs;
    }
  }

  public int[] getDiskDirSizes() {
    if (getDiskStore() != null) {
      return getDiskStore().getDiskDirSizes();
    } else {
      return this.diskSizes;
    }
  }


  /**
   * @return Returns the isUsedForPartitionedRegionBucket.
   */
  public final boolean isUsedForPartitionedRegionBucket() {
    return this.isUsedForPartitionedRegionBucket;
  }
  
  public boolean isUsedForSerialGatewaySenderQueue() {
    return this.isUsedForSerialGatewaySenderQueue;
  }

  public boolean isUsedForIndex() {
    return this.isUsedForIndex;
  }

  public SerialGatewaySenderImpl getSerialGatewaySender(){
    return this.serialGatewaySender;
  }

  /**
   * Returns true if this region has a local parallel WAN or AsyncEventQueue
   * defined.
   */
  public final boolean isLocalParallelWanEnabled() {
    // return the cached value from self; only local info required
    return this.hasLocalParallelAEQorWAN;
  }

  /**
   * Returns true if this region has a serial WAN or AsyncEventQueue defined in
   * the distributed system on any datastore.
   */
  public final boolean isSerialWanEnabled() {
    // return the cached value from self and all other profiles
    return this.hasLocalSerialAEQorWAN || this.hasSerialAEQorWAN;
  }

  /**
   * A convenience method to get the PartitionedRegion for a Bucket
   * @return If this is an instance of {@link BucketRegion}, returns the
   * {@link PartitionedRegion} otherwise throws an IllegalArgumentException
   */
  public PartitionedRegion getPartitionedRegion() {
    // BucketRegion will return the correct result
    throw new IllegalArgumentException();
    /*
    if (!this.isUsedForPartitionedRegionBucket) {
      throw new IllegalArgumentException();
    }
    return ((BucketRegion)this).getPartitionedRegion();
    */
  }

  /**
   * @return Returns the isUsedForMetaRegion.
   */
  final public boolean isUsedForMetaRegion()
  {
    return this.isUsedForMetaRegion;
  }
  
  final public boolean isMetaRegionWithTransactions()
  {
    return this.isMetaRegionWithTransactions;
  }
  
  /**
   * @return true if this is not a user visible region
   */
  final public boolean isInternalRegion(){
    return isSecret() || isUsedForMetaRegion() || isUsedForPartitionedRegionAdmin()
           || isUsedForPartitionedRegionBucket();
  }

  public LoaderHelper createLoaderHelper(Object key, Object callbackArgument,
      boolean netSearchAllowed, boolean netLoadAllowed,
      SearchLoadAndWriteProcessor searcher)
  {
    return new LoaderHelperImpl(this, key, callbackArgument, netSearchAllowed,
        netLoadAllowed, searcher);
  }

  /** visitor over the CacheProfiles to check if the region has a CacheLoader */
  private static final DistributionAdvisor.ProfileVisitor<Void> netLoaderVisitor
      = new DistributionAdvisor.ProfileVisitor<Void>() {
    public boolean visit(DistributionAdvisor advisor, Profile profile,
        int profileIndex, int numProfiles, Void aggregate) {
      assert profile instanceof CacheProfile;
      final CacheProfile prof = (CacheProfile)profile;

      // if region in cache is not yet initialized, exclude
      if (prof.regionInitialized // fix for bug 41102
          && !prof.memberUnInitialized) {
        // cut the visit short if we find a CacheLoader
        return !prof.hasCacheLoader;
      }
      // continue the visit
      return true;
    }
  };

  /** visitor over the CacheProfiles to check if the region has a CacheWriter */
  private static final DistributionAdvisor.ProfileVisitor<Void> netWriterVisitor
      = new DistributionAdvisor.ProfileVisitor<Void>() {
    public boolean visit(DistributionAdvisor advisor, Profile profile,
        int profileIndex, int numProfiles, Void aggregate) {
      assert profile instanceof CacheProfile;
      final CacheProfile prof = (CacheProfile)profile;

      // if region in cache is in recovery, or member not initialized exclude
      if (!prof.inRecovery && !prof.memberUnInitialized) {
        // cut the visit short if we find a CacheWriter
        return !prof.hasCacheWriter;
      }
      // continue the visit
      return true;
    }
  };

  /**
   * Return true if some other member of the distributed system, not including
   * self, has a CacheLoader defined on the region.
   */
  public final boolean hasNetLoader(CacheDistributionAdvisor distAdvisor) {
    return !distAdvisor.accept(netLoaderVisitor, null);
  }

  /**
   * Return true if some other member of the distributed system, not including
   * self, has a CacheWriter defined on the region.
   */
  public final boolean hasNetWriter(CacheDistributionAdvisor distAdvisor) {
    return !distAdvisor.accept(netWriterVisitor, null);
  }

  /**
   * Used to indicate that this region is used for internal purposes
   */
  public boolean isSecret() {
    return false;
  }
  
  /**
   * whether concurrency checks should be disabled for this region
   */
  @Override
  public boolean supportsConcurrencyChecks() {
    final GemFireCacheImpl.StaticSystemCallbacks sysCb = GemFireCacheImpl.FactoryStatics.systemCallbacks;
    if (sysCb != null) {
      return sysCb.supportsRegionConcurrencyChecks(this);
    }
    return !isSecret() || this.dataPolicy.withPersistence();
  }
  
  /**
   * Used by GemFireXD to indicate that recovery of the values of this region from
   * disk should be deferred to the very end.
   */
  protected boolean deferRecovery() {
    return false;
  }

  /**
   * Used to prevent notification of bridge clients, typically used for internal
   * "meta" regions and if the cache doesn't have any bridge servers
   *
   * @return true only if it's cache has bridge servers and this is nt a meta region
   */
  protected boolean shouldNotifyBridgeClients()
  {
    return (this.cache.getBridgeServers().size() > 0)
        && !this.isUsedForPartitionedRegionAdmin
        && !this.isUsedForPartitionedRegionBucket
        && !this.isUsedForMetaRegion;
  }

  /**
   * Checks that if this region participates in a Gateway
   * and there is a hub
   *
   * @return true only if this region participates in a Gateway
   *         and there is a hub
   */
  protected boolean shouldNotifyGatewayHub() {
    if (!this.enableGateway) {
      return false;
    }
    return this.cache.getGatewayHubs().size() > 0;
  }
  /**
   * Checks whether this region has atleast one gateway sender attached 
   * @return true if there is atleast one gateway sender attached to 
   * this region, false otherwise.
   */
  protected boolean shouldNotifyGatewaySenders() {
    return gatewaySenderIds != null && gatewaySenderIds.size() > 0;
  }

  protected boolean shouldNotifyGatewayHub(GatewayHubImpl hub) {
    return this.gatewayHubId == null
        || this.gatewayHubId.equals("")
        || this.gatewayHubId.equals(hub.getId())
        || (this.allGatewayHubIds != null && this.allGatewayHubIds.contains(hub
            .getId()));
  }

  /**
   * Check if the region has has a Listener or not
   *
   * @return true only if this region has a Listener
   */
  protected final boolean shouldDispatchListenerEvent()
  {
    return hasListener();
  }

  /**
   * Internal method to return cache as GemFireCache to avoid unnecessary
   * typecasting.
   * 
   * @return this region's GemFireCache instance
   */
  @Override
  public final GemFireCacheImpl getGemFireCache() {
    return this.cache;
  }

  /**
   * Called by ccn when a client goes away
   * @since 5.7
   */
  void cleanupForClient(CacheClientNotifier ccn,
                        ClientProxyMembershipID client) {
    if (this.cache.isClosed()) return;
    if (this.isDestroyed) return;
    
    this.filterProfile.cleanupForClient(ccn, client);
    
    Iterator it = (new SubregionsSet(false)).iterator();
    while (it.hasNext()) {
      LocalRegion lr = (LocalRegion)it.next();
      lr.cleanupForClient(ccn, client);
    }
  }
  
  /**
   * Returns the CQ/interest profile for this region
   */
  public FilterProfile getFilterProfile() {
    return this.filterProfile;
  }
  
  /**
   * Destroys the CQ/interest profile for this region.  Use this if your
   * region does not support client interest (e.g., WAN gateway queue)
   */
  public void destroyFilterProfile() {
    this.filterProfile = null;
  }

  /**
   * Returns the log writer for this region's cache
   */
  public LogWriterI18n getLogWriterI18n() {
    return this.cache.getLoggerI18n();
  }
  
  
  /**
   * Returns a map of subregions that were destroyed when this region was
   * destroyed. Map contains subregion full paths to SerialNumbers. Return is
   * defined as HashMap because DestroyRegionOperation will provide the map to
   * DataSerializer.writeHashMap which requires HashMap. Returns
   * {@link #destroyedSubregionSerialNumbers}.
   *
   * @return HashMap of subregions to SerialNumbers
   * @throws IllegalStateException if this region has not been destroyed
   */
  protected HashMap getDestroyedSubregionSerialNumbers() {
    if (!this.isDestroyed) {
      throw new IllegalStateException(
       LocalizedStrings.LocalRegion_REGION_0_MUST_BE_DESTROYED_BEFORE_CALLING_GETDESTROYEDSUBREGIONSERIALNUMBERS
         .toLocalizedString(getFullPath()));
    }
    return this.destroyedSubregionSerialNumbers;
  }

  /**
   * Returns a map of subregion full paths to SerialNumbers. Caller must have
   * acquired the destroyLock if a stable view is desired. Key is String, value
   * is Integer.
   *
   * @return HashMap of subregions to SerialNumbers
   */
  private HashMap collectSubregionSerialNumbers() {
    HashMap map = new HashMap();
    addSubregionSerialNumbers(map);
    return map;
  }

  /**
   * Iterates over all subregions to put the full path and serial number into
   * the provided map.
   *
   * @param map the map to put the full path and serial number into for each
   * subregion
   */
  private void addSubregionSerialNumbers(Map map) {
    // iterate over all subregions to gather serialNumbers and recurse
    for (Iterator iter = this.subregions.entrySet().iterator(); iter.hasNext();) {
      Map.Entry entry = (Map.Entry)iter.next();
      LocalRegion subregion = (LocalRegion) entry.getValue();
      map.put(subregion.getFullPath(),
              Integer.valueOf(subregion.getSerialNumber()));

      // recursively call down into each subregion tree
      subregion.addSubregionSerialNumbers(map);
    }
  }

  
  public SelectResults query(String p_predicate) throws FunctionDomainException,
      TypeMismatchException, NameResolutionException,
      QueryInvocationTargetException {
    String predicate = p_predicate;
    if (predicate == null) {
      throw new IllegalArgumentException(
          "The input query predicate is null. A null predicate is not allowed.");
    }
    predicate = predicate.trim();
    SelectResults results = null;
    if (hasServerProxy()) {
      String queryString = null;

      // Trim whitespace
      predicate = predicate.trim();

      // Compare the query patterns to the 'predicate'. If one matches,
      // send it as is to the BridgeLoader
      boolean matches = false;
      for (int i=0; i<QUERY_PATTERNS.length; i++) {
        if (QUERY_PATTERNS[i].matcher(predicate).matches()) {
          matches = true;
          break;
        }
      }
      if (matches) {
        queryString = predicate;
      }
      else {
        queryString = "select * from " + getFullPath() + " this where " + predicate;
      }
      try {
        results = getServerProxy().query(queryString, null);
      }
      catch (Exception e) {
        Throwable cause = e.getCause();
        if (cause == null) {
          cause = e;
        }
        throw new QueryInvocationTargetException(e.getMessage(), cause);
      }
    }
    else {
      QueryService qs = getGemFireCache().getLocalQueryService();
      String queryStr = "select * from " + getFullPath() + " this where " + predicate;
      DefaultQuery query = (DefaultQuery)qs.newQuery(queryStr);
      results = (SelectResults)query.execute(new Object[0]);
    }
    return results;
  }

  /**
   * Execute the provided named function in all locations that contain the given
   * keys. So function can be executed on just one fabric node, executed in
   * parallel on a subset of nodes in parallel across all the nodes.
   * 
   * @param function
   * @param args
   * @param filter
   * @since 5.8Beta
   */
  public ResultCollector executeFunction(final DistributedRegionFunctionExecutor execution, final Function function, final Object args,
      final ResultCollector rc,final Set filter, final ServerToClientFunctionResultSender sender) {   

    if (function.optimizeForWrite() && memoryThresholdReached.get() &&
        !MemoryThresholds.isLowMemoryExceptionDisabled()) {
      Set<DistributedMember> htrm = getMemoryThresholdReachedMembers();
      throw new LowMemoryException(LocalizedStrings.ResourceManager_LOW_MEMORY_FOR_0_FUNCEXEC_MEMBERS_1.toLocalizedString(
          new Object[] {function.getId(), htrm}), htrm);
    }
    final LocalResultCollector<?, ?> localRC = execution
        .getLocalResultCollector(function, rc);
    final DM dm = getDistributionManager();
    execution.setExecutionNodes(Collections.singleton(getMyId()));

    final TXStateInterface tx = getTXState();
    // flush any pending TXStateProxy ops
    if (tx != null) {
      tx.flushPendingOps(dm);
    }
    final DistributedRegionFunctionResultSender resultSender = new DistributedRegionFunctionResultSender(
        dm, tx, localRC, function, sender);
    final RegionFunctionContextImpl context = new RegionFunctionContextImpl(
        function.getId(), LocalRegion.this, args, filter, null, null,
        resultSender, execution.isReExecute());
    execution.executeFunctionOnLocalNode(function, context, resultSender, dm,
        tx);
    return localRC;
  }

  /**
   * @return the set of members which are known to be critical
   */
  public Set<DistributedMember> getMemoryThresholdReachedMembers() {
    return Collections.<DistributedMember> singleton(this.cache.getMyId());
  }

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.cache.control.ResourceListener#onEvent(java.lang.Object)
   */
  public final void onEvent(MemoryEvent event) {
    if (cache.getLogger().fineEnabled()) {
      cache.getLogger().fine("Region:"+this+" received a Memory event."+ event);
    }
    setMemoryThresholdFlag(event);
  }

  protected void setMemoryThresholdFlag(MemoryEvent event) {
    assert getScope().isLocal();
    if (event.isLocal()) {
      if (event.getState().isCritical()
          && !event.getPreviousState().isCritical()
          && (event.getType() == ResourceType.HEAP_MEMORY || (event.getType() == ResourceType.OFFHEAP_MEMORY && getEnableOffHeapMemory()))) {
        // start rejecting operations
        memoryThresholdReached.set(true);
      } else if (!event.getState().isCritical()
          && event.getPreviousState().isCritical()
          && (event.getType() == ResourceType.HEAP_MEMORY || (event.getType() == ResourceType.OFFHEAP_MEMORY && getEnableOffHeapMemory()))) {
        memoryThresholdReached.set(false);
      }
    }
  }
  void updateSizeOnClearRegion(int sizeBeforeClear) {
    if(!this.reservedTable() && needAccounting()) {
      long ignoreBytes = this.isDestroyed() ? getIgnoreBytes() :
              getIgnoreBytes() + regionOverHead;
      callback.dropStorageMemory(getFullPath(), ignoreBytes);
    }
  }

  /**
   * Calculate and return the size of a value for updating the bucket size.
   * Zero is always returned for non-bucket regions.
   */
  public int calculateValueSize(Object val) {
    // Only needed by BucketRegion
    return 0;
  }
  public int calculateRegionEntryValueSize(RegionEntry re) {
    // Only needed by BucketRegion
    return 0;
  }
  void updateSizeOnPut(Object key, int oldSize, int newSize) {
    // Only needed by BucketRegion
  }

  void updateSizeOnCreate(Object key, int newSize) {
    // Only needed by BucketRegion
  }

  void updateSizeOnRemove(Object key, int oldSize) {
    // Only needed by BucketRegion
  }

  int updateSizeOnEvict(Object key, int oldSize) {
    // Only needed by BucketRegion
    return 0;
  }
  public void updateSizeOnFaultIn(Object key, int newSize, int bytesOnDisk) {
    // Only needed by BucketRegion
  }

  public void initializeStats(long numEntriesInVM, long numOverflowOnDisk,
      long numOverflowBytesOnDisk) {
    getDiskRegion().getStats().incNumEntriesInVM(numEntriesInVM); 
    getDiskRegion().getStats().incNumOverflowOnDisk(numOverflowOnDisk);
  }

  /**
   * This method is meant to be overriden by DistributedRegion
   * and PartitionedRegions to cleanup CRITICAL state
   */
  public void removeMemberFromCriticalList(DistributedMember member) {
    Assert.assertTrue(false);   //should not be called for LocalRegion
  }

  /**
   * Initialize the set of remote members whose memory state is critical.  This is
   * called when registering using {@link InternalResourceManager#addResourceListener(ResourceType, ResourceListener)}.
   * It should only be called once and very early in this region's lifetime.
   *
   * @param localMemoryIsCritical true if the local memory is in a critical state
   * @param critialMembers set of members whose memory is in a critical state
   * @see ResourceManager#setCriticalHeapPercentage(float) and ResourceManager#setCriticalOffHeapPercentage(float)
   * @since 6.0
   */
  public void initialCriticalMembers(boolean localMemoryIsCritical,
      Set<InternalDistributedMember> critialMembers) {
    assert getScope().isLocal();
    if (localMemoryIsCritical) {
      memoryThresholdReached.set(true);
    }
  }
  
  public void destroyRecoveredEntry(Object key) {
    EntryEventImpl event = EntryEventImpl.create(
        this,
        Operation.LOCAL_DESTROY, key, null, null, false, getMyId(), false);
    try {
    event.inhibitCacheListenerNotification(true);
    mapDestroy(event, true, false, null, false, true);
    } finally {
      event.release();
    }
  }
  public boolean lruLimitExceeded() {
    return this.entries.lruLimitExceeded();
  }

  public DiskEntry getDiskEntry(Object key) {
    // should return tombstone as an valid entry
    RegionEntry re = this.entries.getEntry(key);
    if (re != null && re.isRemoved() && !re.isTombstone()) {
      re = null;
    }
    return (DiskEntry)re;
  }

  /**
   * Fetch the Region which stores the given key The resulting Region will be
   * used for a read operation e.g. Region.get by transactions.
   * 
   * @param entryKey
   *          key to evaluate to determine the returned region
   * 
   * @return region that stores the key or will store the key
   */
  public LocalRegion getDataRegionForRead(KeyInfo entryKey, Operation op) {
    return this;
  }

  /**
   * Fetch the Region which stores the given key The resulting Region will be
   * used for a read operation e.g. Region.get by transactions.
   * 
   * @param key
   *          key to evaluate to determine the returned region
   * @param callbackArg
   *          any callback argument passed for the operation
   * @param bucketId
   *          the bucketId for current entry if known else
   *          {@link KeyInfo#UNKNOWN_BUCKET}
   * 
   * @return region that stores the key or will store the key
   */
  public LocalRegion getDataRegionForRead(Object key, Object callbackArg,
      int bucketId, Operation op) {
    return this;
  }

  /**
   * Fetch the Region which stores the given key. The resulting Region will be
   * used for a write operation e.g. Region.put by transactions. Note that this
   * may return a secondary BucketRegion for PRs so non-transactional operations
   * must not use this method.
   * 
   * @param entryKey
   *          key to evaluate to determine the returned region
   * 
   * @return region that stores the key or will store the key
   */
  public LocalRegion getDataRegionForWrite(KeyInfo entryKey, Operation op) {
    return this;
  }

  public final InternalDataView getSharedDataView() {
    return this.sharedDataView;
  }

  /**
   * @param key
   * @return the wrapped {@link KeyInfo}
   */
  public KeyInfo getKeyInfo(Object key) {
    return new KeyInfo(key, null, null);
  }

  /**
   * Returns the wrapped {@link KeyInfo}.
   */
  public final KeyInfo getKeyInfo(Object key, Object callbackArg) {
    return getKeyInfo(key, null, callbackArg);
  }

  /**
   * Returns the wrapped {@link KeyInfo}.
   */
  public KeyInfo getKeyInfo(Object key, Object value, Object callbackArg) {
    return new KeyInfo(key, null, callbackArg);
  }

  /**
   * Updates the {@link KeyInfo} for given arguments.
   */
  void updateKeyInfo(KeyInfo keyInfo, Object key, Object newValue) {
  }

  /**
   * Get an existing RegionEntry for locking (read or write).
   * 
   * @param dataRegion
   *          the actual region containing the RegionEntry; usually the result
   *          of {@link #getDataRegionForRead} or {@link #getDataRegionForWrite}
   * @param key
   *          the key to be looked up
   * @return the RegionEntry corresponding to the <code>keyInfo</code>
   */
  public RegionEntry basicGetEntryForLock(final LocalRegion dataRegion,
      final Object key) {
    return dataRegion.entries.getEntry(key);
  }

  /**
   * Get an existing RegionEntry for locking (read or write).
   * 
   * @param dataRegion
   *          the actual region containing the RegionEntry; usually the result
   *          of {@link #getDataRegionForRead} or {@link #getDataRegionForWrite}
   * @param key
   *          the key to be looked up
   * @return the RegionEntry corresponding to the <code>keyInfo</code>
   */
  public RegionEntry basicGetEntryForLock(final LocalRegion dataRegion,
      final Object key, final boolean allowReadFromHDFS) {
    if (allowReadFromHDFS) {
      return dataRegion.entries.getEntry(key);
    }
    else {
      return dataRegion.entries.getOperationalEntryInVM(key);
    }
  }

  /**
   * Get or create a RegionEntry for given key in the map and lock it for
   * writing using the given {@link LockingPolicy}.
   */
  public final RegionEntry lockRegionEntryForWrite(
      final LocalRegion dataRegion, final Object key,
      final LockingPolicy lockPolicy, final LockMode lockMode,
      final Object lockOwner, final int flags) throws ConflictException,
      LockTimeoutException {
    RegionEntry newRe = null;
    RegionEntry oldRe = basicGetEntryForLock(dataRegion, key);
    if (oldRe == null) {
      final RegionMap map = dataRegion.entries;
      // lock the new entry before putting in map to give the creator a
      // preference over any other concurrent writers
      newRe = createAndLockRegionEntry(dataRegion, map, key, lockPolicy,
          lockMode, lockOwner, flags);
      oldRe = map.putEntryIfAbsent(key, newRe);
      if (oldRe == null) {
        return newRe;
      }
    }
    // the entry may have been removed from the map at this point after lock
    // acquisition is done, so check for REMOVED_PHASE2 token below
    for (;;) {
      // lock the existing RegionEntry
      lockPolicy.acquireLock(oldRe, lockMode, flags, lockOwner, dataRegion,
          null);
      // if the re goes into removed2 state, it will be removed
      // from the map
      if (!oldRe.isRemovedPhase2()) {
        return oldRe;
      }
      else {
        lockPolicy.releaseLock(oldRe, lockMode, lockOwner, false, dataRegion);
      }
      final RegionMap map = dataRegion.entries;
      if (newRe == null) {
        newRe = createAndLockRegionEntry(dataRegion, map, key, lockPolicy,
            lockMode, lockOwner, flags);
      }
      oldRe = map.putEntryIfAbsent(key, newRe);
      if (oldRe == null) {
        return newRe;
      }
      dataRegion.getCachePerfStats().incRetries();
    }
  }

  private static final RegionEntry createAndLockRegionEntry(
      final LocalRegion dataRegion, final RegionMap map, final Object key,
      final LockingPolicy lockPolicy, final LockMode lockMode,
      final Object lockOwner, final int flags) throws ConflictException,
      LockTimeoutException {
    final RegionEntry newRe = map.getEntryFactory().createEntry(dataRegion,
        key, Token.REMOVED_PHASE1);
    // lock the new entry before putting in map to give the creator a
    // preference over any other concurrent writers
    lockPolicy.acquireLock(newRe, lockMode, flags, lockOwner, dataRegion, null);
    // set the lastModifiedTime to indicate that the entry has been created for
    // locking only and should not be expired etc.
    ((AbstractRegionEntry)newRe).markLockedForCreate();
    dataRegion.txLockCreateCount.incrementAndGet();
    return newRe;
  }

  @Override
  protected void gatewaySendersChanged() {
    this.hasLocalSerialAEQorWAN = false;
    this.hasLocalParallelAEQorWAN = false;
    Set<String> regionGatewaySenderIds = getAllGatewaySenderIds();
    if (!regionGatewaySenderIds.isEmpty()) {
      final GemFireCacheImpl cache = getCache();
      for (String id : regionGatewaySenderIds) {
        // serial present locally
        final GatewaySender sender = cache.getGatewaySender(id);
        if (sender == null) {
          continue;
        }
        if (sender.isParallel()) {
          this.hasLocalParallelAEQorWAN = true;
        }
        else {
          this.hasLocalSerialAEQorWAN = true;
        }
        if (this.hasLocalParallelAEQorWAN && this.hasLocalSerialAEQorWAN) {
          break;
        }
      }
    }
  }

  /** actions to be taken for a Gateway sender created on this node */
  void senderCreated() {
    distributeUpdatedProfileOnSenderChange();
  }

  /** actions to be taken for a Gateway sender removed on this node */
  void senderRemoved() {
    distributeUpdatedProfileOnSenderChange();
  }

  /** actions to be taken for a Gateway sender started on this node */
  void senderStarted() {
    // distributing the updated profile so as to update the
    // CacheProfile.hasActive* flags if required
    distributeUpdatedProfileOnSenderChange();
  }

  /** actions to be taken for a Gateway sender stopped on this node */
  void senderStopped() {
    // distributing the updated profile so as to update the
    // CacheProfile.hasActive* flags if required
    distributeUpdatedProfileOnSenderChange();
  }

  public void distributeUpdatedProfileOnSenderChange() {
    // No op for LocalRegions
  }

  static final class RegionPerfStats extends CachePerfStats {

    final CachePerfStats cachePerfStats;

    public RegionPerfStats(GemFireCacheImpl cache, CachePerfStats superStats, String regionName) {
      super(cache.getDistributedSystem(), regionName);
      this.cachePerfStats = superStats;
    }
    
     @Override
    public void incReliableQueuedOps(int inc) {
       stats.incInt(reliableQueuedOpsId, inc);
       this.cachePerfStats.incReliableQueuedOps(inc);
     }
     
     @Override
    public void incReliableQueueSize(int inc) {
       stats.incInt(reliableQueueSizeId, inc);
       this.cachePerfStats.incReliableQueueSize(inc);
     }
     @Override
    public void incReliableQueueMax(int inc) {
       stats.incInt(reliableQueueMaxId, inc);
       this.cachePerfStats.incReliableQueueMax(inc);
     }
     @Override
    public void incReliableRegions(int inc) {
       stats.incInt(reliableRegionsId, inc);
       this.cachePerfStats.incReliableRegions(inc);
     }
     @Override
    public void incReliableRegionsMissing(int inc) {
       stats.incInt(reliableRegionsMissingId, inc);
       this.cachePerfStats.incReliableRegionsMissing(inc);
     }
     @Override
    public void incReliableRegionsQueuing(int inc) {
       stats.incInt(reliableRegionsQueuingId, inc);
       this.cachePerfStats.incReliableRegionsQueuing(inc);
     }

    @Override
    public void incReliableRegionsMissingFullAccess(int inc) {
      stats.incInt(reliableRegionsMissingFullAccessId, inc);
      this.cachePerfStats.incReliableRegionsMissingFullAccess(inc);
    }
     
    @Override
    public void incReliableRegionsMissingLimitedAccess(int inc) {
      stats.incInt(reliableRegionsMissingLimitedAccessId, inc);
      this.cachePerfStats.incReliableRegionsMissingLimitedAccess(inc);
    }

    @Override
    public void incReliableRegionsMissingNoAccess(int inc) {
      stats.incInt(reliableRegionsMissingNoAccessId, inc);
      this.cachePerfStats.incReliableRegionsMissingNoAccess(inc);
    }

    @Override
    public void incQueuedEvents(int inc) {
      this.stats.incLong(eventsQueuedId, inc);
      this.cachePerfStats.incQueuedEvents(inc);
    }

    /**
     * @return the timestamp that marks the start of the operation
     */
    @Override
    public long startLoad() {
      stats.incInt(loadsInProgressId, 1);
      return this.cachePerfStats.startLoad();
      //return NanoTimer.getTime(); // don't use getStatTime so always enabled
    } 
    /**
     * @param start the timestamp taken when the operation started 
     */
    @Override
    public void endLoad(long start) {
      // note that load times are used in health checks and
      // should not be disabled by enableClockStats==false
      long ts = NanoTimer.getTime(); // don't use getStatTime so always enabled
      stats.incLong(loadTimeId, ts-start);
      stats.incInt(loadsInProgressId, -1);
      stats.incInt(loadsCompletedId, 1);
      this.cachePerfStats.endLoad(start); //need to think about timings
    }
    
    /**
     * @return the timestamp that marks the start of the operation
     */
    @Override
    public long startNetload() {
      stats.incInt(netloadsInProgressId, 1);
      this.cachePerfStats.startNetload();
      return getStatTime();
    } 
    /**
     * @param start the timestamp taken when the operation started 
     */
    @Override
    public void endNetload(long start) {
      if (enableClockStats) {
        stats.incLong(netloadTimeId, getStatTime()-start);
      }
      stats.incInt(netloadsInProgressId, -1);
      stats.incInt(netloadsCompletedId, 1);
      this.cachePerfStats.endNetload(start);
    }

    /**
     * @return the timestamp that marks the start of the operation
     */
    @Override
    public long startNetsearch() {
      stats.incInt(netsearchesInProgressId, 1);
      return this.cachePerfStats.startNetsearch();
      //return NanoTimer.getTime(); // don't use getStatTime so always enabled
    }
    /**
     * @param start the timestamp taken when the operation started 
     */
    @Override
    public void endNetsearch(long start) {
      // note that netsearch is used in health checks and timings should
      // not be disabled by enableClockStats==false
      long ts = NanoTimer.getTime(); // don't use getStatTime so always enabled
      stats.incLong(netsearchTimeId, ts-start);
      stats.incInt(netsearchesInProgressId, -1);
      stats.incInt(netsearchesCompletedId, 1);
      this.cachePerfStats.endNetsearch(start);
    }
    
    /**
     * @return the timestamp that marks the start of the operation
     */
    @Override
    public long startCacheWriterCall() {
      stats.incInt(cacheWriterCallsInProgressId, 1);
      this.cachePerfStats.startCacheWriterCall();
      return getStatTime();
    }
    /**
     * @param start the timestamp taken when the operation started 
     */
    @Override
    public void endCacheWriterCall(long start) {
      if (enableClockStats) {
        stats.incLong(cacheWriterCallTimeId, getStatTime()-start);
      }
      stats.incInt(cacheWriterCallsInProgressId, -1);
      stats.incInt(cacheWriterCallsCompletedId, 1);
      this.cachePerfStats.endCacheWriterCall(start);
    }
    
    /**
     * @return the timestamp that marks the start of the operation
     * @since 3.5
     */
    @Override
    public long startCacheListenerCall() {
      stats.incInt(cacheListenerCallsInProgressId, 1);
      this.cachePerfStats.startCacheListenerCall();
      return getStatTime();
    }
    /**
     * @param start the timestamp taken when the operation started 
     * @since 3.5
     */
    @Override
    public void endCacheListenerCall(long start) {
      if (enableClockStats) {
        stats.incLong(cacheListenerCallTimeId, getStatTime()-start);
      }
      stats.incInt(cacheListenerCallsInProgressId, -1);
      stats.incInt(cacheListenerCallsCompletedId, 1);
      this.cachePerfStats.endCacheListenerCall(start);
    }
    
    /**
     * @return the timestamp that marks the start of the operation
     */
    @Override
    public long startGetInitialImage() {
      stats.incInt(getInitialImagesInProgressId, 1);
      this.cachePerfStats.startGetInitialImage();
      return getStatTime();
    }
    /**
     * @param start the timestamp taken when the operation started 
     */
    @Override
    public void endGetInitialImage(long start) {
      if (enableClockStats) {
        stats.incLong(getInitialImageTimeId, getStatTime()-start);
      }
      stats.incInt(getInitialImagesInProgressId, -1);
      stats.incInt(getInitialImagesCompletedId, 1);
      this.cachePerfStats.endGetInitialImage(start);
    }
    
    /**
     * @param start the timestamp taken when the operation started 
     */
    @Override
    public void endNoGIIDone(long start) {
      if (enableClockStats) {
        stats.incLong(getInitialImageTimeId, getStatTime()-start);
      }
      stats.incInt(getInitialImagesInProgressId, -1);
      this.cachePerfStats.endNoGIIDone(start);
    }

    @Override
    public void incGetInitialImageKeysReceived(int inc) {
      stats.incInt(getInitialImageKeysReceivedId, inc);
      this.cachePerfStats.incGetInitialImageKeysReceived(inc);
    }

    @Override
    public void incGetInitialImageTransactionsReceived(int inc) {
      stats.incInt(getInitialImageTransactionsReceivedId, inc);
      this.cachePerfStats.incGetInitialImageTransactionsReceived(inc);
    }

    @Override
    public long startIndexUpdate() {
      stats.incInt(indexUpdateInProgressId, 1);
      this.cachePerfStats.startIndexUpdate();
      return getStatTime();
    } 
    @Override
    public void endIndexUpdate(long start) {
      long ts = getStatTime();
      stats.incLong(indexUpdateTimeId, ts-start);
      stats.incInt(indexUpdateInProgressId, -1);
      stats.incInt(indexUpdateCompletedId, 1);
      this.cachePerfStats.endIndexUpdate(start);
    }

    @Override
    public void incRegions(int inc) {
      stats.incInt(regionsId, inc);
      this.cachePerfStats.incRegions(inc);
      
    }
    @Override
    public void incPartitionedRegions(int inc) {
      stats.incInt(partitionedRegionsId, inc);
      this.cachePerfStats.incPartitionedRegions(inc);
    }
    @Override
    public void incDestroys() {
      stats.incInt(destroysId, 1);
      this.cachePerfStats.incDestroys();
    }
    @Override
    public void incCreates() {
      stats.incInt(createsId, 1);
      this.cachePerfStats.incCreates();
    }
    @Override
    public void incInvalidates() {
      stats.incInt(invalidatesId, 1);
      this.cachePerfStats.incInvalidates();
    }
    @Override
    public void incTombstoneCount(int delta) {
      stats.incInt(tombstoneCountId, delta);
      this.cachePerfStats.incTombstoneCount(delta);
    }
    @Override
    public void incTombstoneGCCount() {
      this.stats.incInt(tombstoneGCCountId, 1);
      this.cachePerfStats.incTombstoneGCCount();
    }
    @Override
    public void incClearTimeouts() {
      this.stats.incInt(clearTimeoutsId, 1);
      this.cachePerfStats.incClearTimeouts();
    }
    @Override
    public void incConflatedEventsCount() {
      this.stats.incLong(conflatedEventsId, 1);
      this.cachePerfStats.incConflatedEventsCount();
    }
    
    /**
     * @param start the timestamp taken when the operation started 
     */
    @Override
    public void endGet(long start, boolean miss) {
      if (enableClockStats) {
        stats.incLong(getTimeId, getStatTime()-start);
      }
      stats.incInt(getsId, 1);
      if (miss) {
        stats.incInt(missesId, 1);
      }
      this.cachePerfStats.endGet(start, miss);
    }
    /**
     * @param start the timestamp taken when the operation started
     * @param isUpdate true if the put was an update (origin remote)
     */
    @Override
    public long endPut(long start, boolean isUpdate) {
      long total = 0;
      if (isUpdate) {
        stats.incInt(updatesId, 1);
        if (enableClockStats) {
          total = getStatTime()-start;
          stats.incLong(updateTimeId, total);
        }
      } else {
        stats.incInt(putsId, 1);
        if (enableClockStats) {
          total = getStatTime()-start;
          stats.incLong(putTimeId, total);
        }
      }
      this.cachePerfStats.endPut(start, isUpdate);
      return total;
    }
    
    @Override
    public void endPutAll(long start) {
      stats.incInt(putallsId, 1);
      if (enableClockStats)
        stats.incLong(putallTimeId, getStatTime()-start);
      this.cachePerfStats.endPutAll(start);
    }
      
    @Override
    public void endQueryExecution(long executionTime) {
      stats.incInt(queryExecutionsId, 1);
      if (enableClockStats) {
        stats.incLong(queryExecutionTimeId, executionTime);
      }
      this.cachePerfStats.endQueryExecution(executionTime);
    }
    
    @Override
    public void endQueryResultsHashCollisionProbe(long start) {
      if (enableClockStats) {
        stats.incLong(queryResultsHashCollisionProbeTimeId, getStatTime() - start);
      }
      this.cachePerfStats.endQueryResultsHashCollisionProbe(start);
    }  
    
    @Override
    public void incQueryResultsHashCollisions() {
      stats.incInt(queryResultsHashCollisionsId, 1);
      this.cachePerfStats.incQueryResultsHashCollisions();
    }
    
    @Override
    public void incTxConflictCheckTime(long delta) {
      stats.incLong(txConflictCheckTimeId, delta);
      this.cachePerfStats.incTxConflictCheckTime(delta);
    }
    
    @Override
    public void txSuccess(long opTime, long txLifeTime, int txChanges) {
      stats.incInt(txCommitsId, 1);
      stats.incInt(txCommitChangesId, txChanges);
      stats.incLong(txCommitTimeId, opTime);
      stats.incLong(txSuccessLifeTimeId, txLifeTime);
      this.cachePerfStats.txSuccess(opTime, txLifeTime, txChanges);
    }
    @Override
    public void txFailure(long opTime, long txLifeTime, int txChanges) {
      stats.incInt(txFailuresId, 1);
      stats.incInt(txFailureChangesId, txChanges);
      stats.incLong(txFailureTimeId, opTime);
      stats.incLong(txFailedLifeTimeId, txLifeTime);
      this.cachePerfStats.txFailure(opTime, txLifeTime, txChanges);
    }
    @Override
    public void txRollback(long opTime, long txLifeTime, int txChanges) {
      stats.incInt(txRollbacksId, 1);
      stats.incInt(txRollbackChangesId, txChanges);
      stats.incLong(txRollbackTimeId, opTime);
      stats.incLong(txRollbackLifeTimeId, txLifeTime);
      this.cachePerfStats.txRollback(opTime, txLifeTime, txChanges);
    }

    @Override
    public void txRemoteSuccess(long opTime, long txLifeTime, int txChanges) {
      stats.incInt(txRemoteCommitsId, 1);
      stats.incInt(txRemoteCommitChangesId, txChanges);
      stats.incLong(txRemoteCommitTimeId, opTime);
      stats.incLong(txRemoteSuccessLifeTimeId, txLifeTime);
      this.cachePerfStats.txRemoteSuccess(opTime, txLifeTime, txChanges);
    }
    @Override
    public void txRemoteFailure(long opTime, long txLifeTime, int txChanges) {
      stats.incInt(txRemoteFailuresId, 1);
      stats.incInt(txRemoteFailureChangesId, txChanges);
      stats.incLong(txRemoteFailureTimeId, opTime);
      stats.incLong(txRemoteFailedLifeTimeId, txLifeTime);
      this.cachePerfStats.txRemoteFailure(opTime, txLifeTime, txChanges);
    }
    @Override
    public void txRemoteRollback(long opTime, long txLifeTime, int txChanges) {
      stats.incInt(txRemoteRollbacksId, 1);
      stats.incInt(txRemoteRollbackChangesId, txChanges);
      stats.incLong(txRemoteRollbackTimeId, opTime);
      stats.incLong(txRemoteRollbackLifeTimeId, txLifeTime);
      this.cachePerfStats.txRemoteRollback(opTime, txLifeTime, txChanges);
    }
    
    @Override
    public void incEventQueueSize(int items) {
      this.stats.incInt(eventQueueSizeId, items);
      this.cachePerfStats.incEventQueueSize(items);
    }

    @Override
    public void incEventQueueThrottleCount(int items) {
      this.stats.incInt(eventQueueThrottleCountId, items);
      this.cachePerfStats.incEventQueueThrottleCount(items);
    }

    @Override
    protected void incEventQueueThrottleTime(long nanos) {
      this.stats.incLong(eventQueueThrottleTimeId, nanos);
      this.cachePerfStats.incEventQueueThrottleTime(nanos);
    }

    @Override
    protected void incEventThreads(int items) {
      this.stats.incInt(eventThreadsId, items);
      this.cachePerfStats.incEventThreads(items);
    }
    
    @Override
    public void incEntryCount(int delta) {
      this.stats.incLong(entryCountId, delta);
      this.cachePerfStats.incEntryCount(delta);
    }

    @Override
    public void incRetries() {
      this.stats.incInt(retriesId, 1);
      this.cachePerfStats.incRetries();
    }

    @Override
    public void incDiskTasksWaiting() {
      this.stats.incInt(diskTasksWaitingId, 1);
      this.cachePerfStats.incDiskTasksWaiting();
    }
    @Override
    public void decDiskTasksWaiting() {
      this.stats.incInt(diskTasksWaitingId, -1);
      this.cachePerfStats.decDiskTasksWaiting();
    }
    @Override
    public void decDiskTasksWaiting(int count) {
      this.stats.incInt(diskTasksWaitingId, -count);
      this.cachePerfStats.decDiskTasksWaiting(count);
    }
    
    @Override
    public void incEvictorJobsStarted() {
      this.stats.incInt(evictorJobsStartedId, 1);
      this.cachePerfStats.incEvictorJobsStarted();
    }
    @Override
    public void incEvictorJobsCompleted() {
      this.stats.incInt(evictorJobsCompletedId, 1);
      this.cachePerfStats.incEvictorJobsCompleted();
    }
    @Override
    public void incEvictorQueueSize(int delta) {
      this.stats.incInt(evictorQueueSizeId, delta);
      this.cachePerfStats.incEvictorQueueSize(delta);
    }
    @Override
    public void incEvictWorkTime(long delta) {
      this.stats.incLong(evictWorkTimeId, delta);
      this.cachePerfStats.incEvictWorkTime(delta);
    }

    @Override
    public void incClearCount() {
      this.stats.incInt(clearsId, 1);
      this.cachePerfStats.incClearCount();
    }

    @Override
    public void incPRQueryRetries() {
      this.stats.incLong(partitionedRegionQueryRetriesId, 1);
      this.cachePerfStats.incPRQueryRetries();
    }

    @Override
    public void incNonSingleHopsCount() {
      this.stats.incLong(nonSingleHopsCountId, 1);
      this.cachePerfStats.incNonSingleHopsCount();
    }

    @Override
    public void incMetaDataRefreshCount() {
      this.stats.incLong(metaDataRefreshCountId, 1);
      this.cachePerfStats.incMetaDataRefreshCount();
    }
    
    @Override
    public void endImport(long entryCount, long start) {
      stats.incLong(importedEntriesCountId, entryCount);
      if (enableClockStats) {
        stats.incLong(importTimeId, getStatTime() - start);
      }
      cachePerfStats.endImport(entryCount, start);
    }
    
    @Override
    public void endExport(long entryCount, long start) {
      stats.incLong(exportedEntriesCountId, entryCount);
      if (enableClockStats) {
        stats.incLong(exportTimeId, getStatTime() - start);
      }
      cachePerfStats.endExport(entryCount, start);
    }

    public long startCompression() {
      stats.incLong(compressionCompressionsId, 1);
      cachePerfStats.stats.incLong(compressionCompressionsId, 1);
      return getStatTime();
    }

    public void endCompression(long startTime, long startSize, long endSize) {
      if(enableClockStats) {
        long time = getStatTime() - startTime;        
        stats.incLong(compressionCompressTimeId, time);
        cachePerfStats.stats.incLong(compressionCompressTimeId, time);
      }
      
      stats.incLong(compressionPreCompressedBytesId, startSize);
      stats.incLong(compressionPostCompressedBytesId, endSize); 

      cachePerfStats.stats.incLong(compressionPreCompressedBytesId, startSize);
      cachePerfStats.stats.incLong(compressionPostCompressedBytesId, endSize); 
    }

    public long startDecompression() {
      stats.incLong(compressionDecompressionsId, 1);     
      cachePerfStats.stats.incLong(compressionDecompressionsId, 1);  
      return getStatTime();
    }

    public void endDecompression(long startTime) {
      if(enableClockStats) {
        long time = getStatTime() - startTime;        
        stats.incLong(compressionDecompressTimeId, time);
        cachePerfStats.stats.incLong(compressionDecompressTimeId, time);
      }   
    }
  }

  /** test hook - dump the backing map for this region */
  public void dumpBackingMap() {
    Object sync = TombstoneService.DEBUG_TOMBSTONE_COUNT? TombstoneService.debugSync : new Object();
    synchronized(this.entries) {
      synchronized(sync) {
        if (this.entries instanceof AbstractRegionMap) {
          ((AbstractRegionMap)(this.entries)).verifyTombstoneCount(this.tombstoneCount);
        }
        getLogWriterI18n().info(LocalizedStrings.DEBUG, "Dumping region of size " + size() +
            " tombstones: " + getTombstoneCount() + ": " + this.toString());
        if (this.entries instanceof AbstractRegionMap) {
          ((AbstractRegionMap)this.entries).dumpMap(getLogWriterI18n());
        }
//        ((UnsharedImageState)this.imageState).dumpDestroyedEntryKeys(getLogWriterI18n());
      }
    }
  }

  /** test hook - verify tombstone count matches what is in the entry map */
  public void verifyTombstoneCount() {
    synchronized(this.entries) {
      if (this.entries instanceof AbstractRegionMap) {
//        if (!((AbstractRegionMap)(this.entries)).verifyTombstoneCount(this.tombstoneCount)) {
//          throw new RuntimeException("tombstone count is wrong in " + this);
//        }
      }
    }
  }

  //////////////////  ConcurrentMap methods //////////////////               

  private void checkIfConcurrentMapOpsAllowed() {
    // This check allows NORMAL with local scope to fix bug 44856
    if (this.srp == null && 
        ((this.dataPolicy == DataPolicy.NORMAL && this.scope.isDistributed()) || this.dataPolicy == DataPolicy.EMPTY)) {
      // the functional spec says these data policies do not support concurrent map
      // operations
      throw new UnsupportedOperationException();
    }
  }
  /**
   * If the specified key is not already associated
   * with a value, associate it with the given value.
   * This is equivalent to
   * <pre>
   *   if (!region.containsKey(key)) 
   *      return region.put(key, value);
   *   else
   *      return region.get(key);
   * </pre>
   * Except that the action is performed atomically.
   *
   * <i>Note that if this method returns null then there is no way to determine
   * definitely whether this operation succeeded and modified the region, or
   * if the entry is in an invalidated state and no modification occurred.</i>
   *
   * If this method does not modify the region then no listeners or other
   * callbacks are executed. If a modification does occur, then the behavior
   * with respect to callbacks is the same as {@link Region#create(Object, Object)}.
   *
   * @param key key with which the specified value is to be associated.
   * @param value the value for the new entry, which may be null meaning
   *              the new entry starts as if it had been locally invalidated.
   * @param callbackArgument
   * @return previous value associated with specified key, or <tt>null</tt>
   *         if there was no mapping for key.  A <tt>null</tt> return can
   *         also indicate that the entry in the region was previously in
   *         an invalidated state.
   *
   * @throws ClassCastException if key does not satisfy the keyConstraint
   * @throws IllegalArgumentException if the key or value
   *         is not serializable and this is a distributed region
   * @throws TimeoutException if timed out getting distributed lock for <code>Scope.GLOBAL</code>
   * @throws NullPointerException if key is <tt>null</tt>
   * @throws PartitionedRegionStorageException if the operation could not be completed.
   */
   public Object putIfAbsent(Object key, Object value, Object callbackArgument) {
     long startPut = CachePerfStats.getStatTime();
     operationStart();
     checkIfConcurrentMapOpsAllowed();
     validateArguments(key, value, callbackArgument);
     // TODO ConcurrentMap.putIfAbsent() treats null as an invalidation operation
     // BUT we need to return the old value, which Invalidate isn't currently doing
//     if (value == null) {
//       throw new NullPointerException(LocalizedStrings.LocalRegion_VALUE_MUST_NOT_BE_NULL.toLocalizedString());
//     }
     checkReadiness();
     checkForLimitedOrNoAccess();

     final TXStateInterface tx = discoverJTA();

     // This used to call the constructor which took the old value. It
     // was modified to call the other EntryEventImpl constructor so that
     // an id will be generated by default. Null was passed in anyway.
     //   generate EventID
     EntryEventImpl event = EntryEventImpl.create(
         this, Operation.PUT_IF_ABSENT, key,
         value, callbackArgument, false, getMyId());
     event.setTXState(tx);
     final Object oldValue = null;
     final boolean ifNew = true;
     final boolean ifOld = false;
     final boolean requireOldValue = true;
     try {
       if (generateEventID()) {
         event.setNewEventId(cache.getDistributedSystem());
       }
       if (!basicPut(event,
           ifNew,
           ifOld,
           oldValue,
           requireOldValue
       )) {
         return event.getOldValue();
       } else {
         if (!getDataView(tx).isDeferredStats()) {
           getCachePerfStats().endPut(startPut, false);
         }
         return null;
       }
     } catch (EntryNotFoundException e) {
       return event.getOldValue();
     } finally {
       operationCompleted();
       event.release();
     }
   }
          
  /* (non-Javadoc)
   * @see java.util.concurrent.ConcurrentMap#putIfAbsent(java.lang.Object, java.lang.Object)
   */
  public Object putIfAbsent(Object key, Object value) {
    return putIfAbsent(key, value, null);
  }

  /* (non-Javadoc)
   * @see java.util.concurrent.ConcurrentMap#remove(java.lang.Object, java.lang.Object)
   */
  public boolean remove(Object key, Object value) {
    return remove(key, value, null);
  }
              
  // @todo expand on this javadoc
  /**
   * Same as {@link #remove(Object, Object)} except a callback argument
   * is supplied to be passed on to <tt>CacheListener</tt>s and/or
   * <tt>CacheWriter</tt>s.
   */
  public boolean remove(Object key, Object pvalue, Object callbackArg) {
    operationStart();
    Object value = pvalue;
    checkIfConcurrentMapOpsAllowed();
    validateKey(key);
    validateCallbackArg(callbackArg);
    checkReadiness();
    checkForLimitedOrNoAccess();
    if (value == null) {
      value = Token.INVALID;
    }
    EntryEventImpl event = EntryEventImpl.create(this,
                                              Operation.REMOVE,
                                              key,
                                              null, // newValue
                                              callbackArg,
                                              false,
                                              getMyId());

    try {
      if (generateEventID() && event.getEventId() == null) {
        event.setNewEventId(this.cache.getDistributedSystem());
      }
      final TXStateInterface tx = discoverJTA();
      event.setTXState(tx);
      getDataView(tx).destroyExistingEntry(event, true, value);
    }
    catch (EntryNotFoundException enfe) {
//      getLogWriterI18n().fine("Caught in LocalRegion#remove", enfe);
      return false;
    }
    catch (RegionDestroyedException rde) {
      if (!rde.getRegionFullPath().equals(getFullPath())) {
        // Handle when a bucket is destroyed
        RegionDestroyedException rde2 =
           new RegionDestroyedException(toString(), getFullPath());
        rde2.initCause(rde);
        throw rde2;
      } else {
        throw rde;
      }
    } finally {
      operationCompleted();
      event.release();
    }
    return true;
  }

  public boolean replace(Object key, Object oldValue, Object newValue) {
    return replace(key, oldValue, newValue, null);
  }
              
  /**
   * Same as {@link #replace(Object, Object, Object)} except a callback argument
   * is supplied to be passed on to <tt>CacheListener</tt>s and/or
   * <tt>CacheWriter</tt>s.
   */
  public boolean replace(Object key,
             Object pexpectedOldValue,
             Object newValue,
             Object callbackArg) {

    operationStart();
    checkIfConcurrentMapOpsAllowed();
    if (newValue == null) {
      throw new NullPointerException();
    }
    Object expectedOldValue = pexpectedOldValue;
    long startPut = CachePerfStats.getStatTime();
    validateArguments(key, newValue, callbackArg);
    checkReadiness();
    checkForLimitedOrNoAccess();
    EntryEventImpl event = EntryEventImpl.create(this,
                                              Operation.REPLACE,
                                              key,
                                              newValue,
                                              callbackArg,
                                              false, // originRemote
                                              getMyId());

    try {
      if (generateEventID()) {
        event.setNewEventId(cache.getDistributedSystem());
      }

      final TXStateInterface tx = discoverJTA();
      event.setTXState(tx);

      // In general, expectedOldValue null is used when there is no particular
      // old value expected (it can be anything). Here, however, if null
      // is passed as expectedOldValue, then it specifically means that the
      // oldValue must actually be null (i.e. INVALID). So here we
      // change an expectedOldValue of null to the invalid token
      if (expectedOldValue == null) {
        expectedOldValue = Token.INVALID;
      }

      if (!basicPut(event,
          false, // ifNew
          true,  // ifOld
          expectedOldValue,
          false // requireOldValue
      )) {
        //this.getCache().getLogger().info("DEBUG replace returning false expectedOldValue=" + expectedOldValue);
        return false;
      }
      else {
        if (!getDataView(tx).isDeferredStats()) {
          getCachePerfStats().endPut(startPut, false);
        }
        return true;
      }
    } catch (EntryNotFoundException e) {  // put failed on server
      return false;
    } finally {
      operationCompleted();
      event.release();
    }
  }
  
  public Object replace(Object key, Object value) {
    return replaceWithCallbackArgument(key, value, null);
  }
              
  /**
   * Same as {@link #replace(Object, Object)} except a callback argument
   * is supplied to be passed on to <tt>CacheListener</tt>s and/or
   * <tt>CacheWriter</tt>s.
   */
  public Object replaceWithCallbackArgument(Object key,
                                            Object value,
                                            Object callbackArg) {
    operationStart();
    long startPut = CachePerfStats.getStatTime();

    checkIfConcurrentMapOpsAllowed();
    
    if (value == null) {
      throw new NullPointerException();
    }
    
    validateArguments(key, value, callbackArg);
    checkReadiness();
    checkForLimitedOrNoAccess();
    EntryEventImpl event = EntryEventImpl.create(this,
                                              Operation.REPLACE,
                                              key,
                                              value,
                                              callbackArg,
                                              false, // originRemote
                                              getMyId());
    try {
      if (generateEventID()) {
        event.setNewEventId(cache.getDistributedSystem());
      }

      final TXStateInterface tx = discoverJTA();

      if (!basicPut(event, false, // ifNew
          true, // ifOld
          null, // expectedOldValue
          true // requireOldValue
      )) {
        return null;
      } else {
        if (!getDataView(tx).isDeferredStats()) {
          getCachePerfStats().endPut(startPut, false);
        }
        return event.getOldValue(); // may be null if was invalid
      }
    } catch (EntryNotFoundException enf) {// put failed on server
      return null;
    } finally {
      operationCompleted();
      event.release();
    }
  }

  public Object basicBridgePutIfAbsent(final Object key, Object value,
      boolean isObject, Object p_callbackArg, final ClientProxyMembershipID client,
      boolean fromClient, EntryEventImpl clientEvent) throws TimeoutException,
      EntryExistsException, CacheWriterException
  {
    EventID eventId = clientEvent.getEventId();
    Object callbackArg = p_callbackArg;
    long startPut = CachePerfStats.getStatTime();
    if (fromClient) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      if (getAttributes().getEnableGateway()) {
        callbackArg = new GatewayEventCallbackArgument(callbackArg);
      }
      if (isGatewaySenderEnabled()
          && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
        callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
      }
    }
    final EntryEventImpl event = EntryEventImpl.create(this, Operation.PUT_IF_ABSENT, key,
        null /* new value */, callbackArg,
        false /* origin remote */, client.getDistributedMember(),
        true /* generateCallbacks */,
        eventId);
    try {
    event.setContext(client);
    
    // if this is a replayed operation we may already have a version tag
    event.setVersionTag(clientEvent.getVersionTag());

    // Set the new value to the input byte[] if it isn't null
    ///*
    if (value != null) {
      // If the byte[] represents an object, then store it serialized
      // in a CachedDeserializable; otherwise store it directly as a byte[]
      if (isObject) {
        // The value represents an object
        event.setSerializedNewValue((byte[])value);
      }
      else {
        // The value does not represent an object
        event.setNewValue(value);
      }
    }

    validateArguments(key, event.basicGetNewValue(), p_callbackArg);

    boolean ifNew = true; // cannot overwrite an existing key
    boolean ifOld = false; // can create a new key
    boolean requireOldValue = true; // need the old value if the create fails
    boolean basicPut = basicPut(event, ifNew, ifOld, null, requireOldValue);
    getCachePerfStats().endPut(startPut, false);
    this.stopper.checkCancelInProgress(null);
    // to fix bug 42968 call getRawOldValue instead of getOldValue
    Object oldValue = event.getRawOldValueAsHeapObject();
    if (oldValue == Token.NOT_AVAILABLE) {
      oldValue = AbstractRegion.handleNotAvailable(oldValue);
    }
    if (basicPut) {
      clientEvent.setVersionTag(event.getVersionTag());
      clientEvent.isConcurrencyConflict(event.isConcurrencyConflict());
    } else if (oldValue == null) {
      // fix for 42189, putIfAbsent on server can return null if the
      // operation was not performed (oldValue in cache was null).
      // We return the INVALID token instead of null to distinguish
      // this case from successful operation
      return Token.INVALID;
    }
    return oldValue;
    } finally {
      event.release();
    }
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }

  
  public boolean basicBridgeReplace(final Object key, Object expectedOldValue,
      Object value, boolean isObject, Object p_callbackArg,
      final ClientProxyMembershipID client,
      boolean fromClient, EntryEventImpl clientEvent) throws TimeoutException,
      EntryExistsException, CacheWriterException
  {
    EventID eventId = clientEvent.getEventId();
    Object callbackArg = p_callbackArg;
    long startPut = CachePerfStats.getStatTime();
    if (fromClient) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      if (getAttributes().getEnableGateway()) {
        callbackArg = new GatewayEventCallbackArgument(callbackArg);
      }
      if (isGatewaySenderEnabled()
          && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
        callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
      }
    }
    final EntryEventImpl event = EntryEventImpl.create(this, Operation.REPLACE, key,
        null /* new value */, callbackArg,
        false /* origin remote */, client.getDistributedMember(),
        true /* generateCallbacks */,
        eventId);
    try {
    event.setContext(client);

    // Set the new value to the input byte[] if it isn't null
    ///*
    if (value != null) {
      // If the byte[] represents an object, then store it serialized
      // in a CachedDeserializable; otherwise store it directly as a byte[]
      if (isObject) {
        // The value represents an object
        event.setSerializedNewValue((byte[])value);
      }
      else {
        // The value does not represent an object
        event.setNewValue(value);
      }
    }

    validateArguments(key, event.basicGetNewValue(), p_callbackArg);

    boolean ifNew = false; // can overwrite an existing key
    boolean ifOld = true; // cannot create a new key
    boolean requireOldValue = false;
    boolean success = basicPut(event, ifNew, ifOld, expectedOldValue, requireOldValue);
    clientEvent.isConcurrencyConflict(event.isConcurrencyConflict());
    if (success) {
      clientEvent.setVersionTag(event.getVersionTag());
    }
    getCachePerfStats().endPut(startPut, false);
    this.stopper.checkCancelInProgress(null);
    return success;
    } finally {
      event.release();
    }
  }

  public Object basicBridgeReplace(final Object key,
      Object value, boolean isObject, Object p_callbackArg,
      final ClientProxyMembershipID client,
      boolean fromClient, EntryEventImpl clientEvent) throws TimeoutException,
      EntryExistsException, CacheWriterException
  {
    EventID eventId = clientEvent.getEventId();
    Object callbackArg = p_callbackArg;
    long startPut = CachePerfStats.getStatTime();
    if (fromClient) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      if (getAttributes().getEnableGateway()) {
        callbackArg = new GatewayEventCallbackArgument(callbackArg);
      }
      if (isGatewaySenderEnabled()
          && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
        callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
      }
    }
    final EntryEventImpl event = EntryEventImpl.create(this, Operation.REPLACE, key,
        null /* new value */, callbackArg,
        false /* origin remote */, client.getDistributedMember(),
        true /* generateCallbacks */,
        eventId);
    try {
    event.setContext(client);

    // Set the new value to the input byte[] if it isn't null
    ///*
    if (value != null) {
      // If the byte[] represents an object, then store it serialized
      // in a CachedDeserializable; otherwise store it directly as a byte[]
      if (isObject) {
        // The value represents an object
        event.setSerializedNewValue((byte[])value);
      }
      else {
        // The value does not represent an object
        event.setNewValue(value);
      }
    }

    validateArguments(key, event.basicGetNewValue(), p_callbackArg);

    boolean ifNew = false; // can overwrite an existing key
    boolean ifOld = true; // cannot create a new key
    boolean requireOldValue = true;
    boolean succeeded = basicPut(event, ifNew, ifOld, null, requireOldValue);
    getCachePerfStats().endPut(startPut, false);
    this.stopper.checkCancelInProgress(null);
    clientEvent.isConcurrencyConflict(event.isConcurrencyConflict());
    if (succeeded) {
      clientEvent.setVersionTag(event.getVersionTag());
      // to fix bug 42968 call getRawOldValue instead of getOldValue
      Object oldValue = event.getRawOldValueAsHeapObject();
      if (oldValue == Token.NOT_AVAILABLE) {
        oldValue = AbstractRegion.handleNotAvailable(oldValue);
      }
      if (oldValue == null) {  // EntryEventImpl.setOldValue translates INVALID to null
        oldValue = Token.INVALID;
      }
      return oldValue;
    } else {
      return null;
    }
    } finally {
      event.release();
    }
  }

  public void basicBridgeRemove(Object key, Object expectedOldValue,
      Object p_callbackArg,
      ClientProxyMembershipID memberId, boolean fromClient, EntryEventImpl clientEvent)
      throws TimeoutException, EntryNotFoundException, CacheWriterException
  {
    Object callbackArg = p_callbackArg;
    if (fromClient) {
      // If this region is also wan-enabled, then wrap that callback arg in a
      // GatewayEventCallbackArgument to store the event id.
      if (getAttributes().getEnableGateway()) {
        callbackArg = new GatewayEventCallbackArgument(callbackArg);
      }
      if (isGatewaySenderEnabled()
          && !(callbackArg instanceof GatewaySenderEventCallbackArgument)) {
        callbackArg = new GatewaySenderEventCallbackArgumentImpl(callbackArg);
      }
    }

    // Create an event and put the entry
    final EntryEventImpl event = EntryEventImpl.create(this, Operation.REMOVE, key,
        null /* new value */, callbackArg,
        false /* origin remote */, memberId.getDistributedMember(),
        true /* generateCallbacks */,
        clientEvent.getEventId());
    try {
    event.setContext(memberId);
    // we rely on exceptions to tell us that the operation didn't take
    // place.  AbstractRegionMap performs the checks and throws the exception
    try {
    basicDestroy(event,
        true,  // cacheWrite
        expectedOldValue);
    } finally {
      clientEvent.setVersionTag(event.getVersionTag());
      clientEvent.setIsRedestroyedEntry(event.getIsRedestroyedEntry());
    }
    } finally {
      event.release();
    }
  }

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.DiskRecoveryStore#getVersionForMember(com.gemstone.gemfire.internal.cache.versions.VersionSource)
   */
  @Override
  public long getVersionForMember(VersionSource member) {
    throw new IllegalStateException("Operation only implemented for disk region");
  }

  /**
   * Return an IndexMap that is persisted to the disk store used
   * by this region.
   * 
   * This IndexMap should be used as the backing map for any
   * regions that are using the Soplog persistence.
   * 
   * Calling this method may create a branch new index map on disk,
   * or it may recover an index map that was previously persisted, depending
   * on whether the index previously existed.
   * 
   * 
   * 
   * @throws IllegalStateException if this region is not using
   * soplog persistence
   * 
   * @throws IllegalStateException if this index was previously
   * persisted with a different expression or from clause.
   * 
   * @param indexName the name of the index
   * @param indexedExpression the index expression
   * @param fromClause the from clause.
   *  
   * 
   * @return The index map.
   */
  public IndexMap getIndexMap(String indexName, String indexedExpression, 
      String fromClause) {
      return new IndexMapImpl();
  }
  
  /**
   * Return an IndexMap that is persisted to the disk store used
   * by this region. This method returns map that might not support
   * range queries.
   * 
   * This IndexMap should be used as the backing map for any
   * regions that are using the Soplog persistence.
   * 
   * Calling this method may create a branch new index map on disk,
   * or it may recover an index map that was previously persisted, depending
   * on whether the index previously existed.
   * 
   * @throws IllegalStateException if this region is not using
   * soplog persistence
   * 
   * @throws IllegalStateException if this index was previously
   * persisted with a different expression or from clause.
   * 
   * @param indexName the name of the index
   * @param indexedExpression the index expression
   * @param fromClause the from clause.
   *  
   * 
   * @return The index map.
   */
  public IndexMap getUnsortedIndexMap(String indexName, String indexedExpression, 
      String fromClause) {
      return new IndexMapImpl();
  }
  //////////////////  End of ConcurrentMap methods ////////////////// 

  /** visitor to check if there is atleast one initialized data store */
  private static final DistributionAdvisor.ProfileVisitor<Void> initDataStoreChecker =
      new DistributionAdvisor.ProfileVisitor<Void>() {
    public boolean visit(DistributionAdvisor advisor, Profile profile,
        int profileIndex, int numProfiles, Void aggregate) {
      assert profile instanceof CacheProfile;
      final CacheProfile cp = (CacheProfile)profile;
      return !(cp.dataPolicy.withReplication() && cp.regionInitialized &&
          !cp.memberUnInitialized);
    }
  };

  /**
   * Returns true if there are no data stores available for this region.
   */
  public boolean hasDataStores() {
    boolean hasStore = true;
    if (getScope() != Scope.LOCAL) {
      if (!getDataPolicy().withStorage()) {
        hasStore = !((DistributedRegion)this).getDistributionAdvisor()
            .accept(initDataStoreChecker, null);
      }
    }
    else {
      hasStore = getDataPolicy().withStorage();
    }
    if (!hasStore) {
      // check if VM is going down before throwing no data store found
      getCancelCriterion().checkCancelInProgress(null);
    }
    final LogWriterI18n logger = getLogWriterI18n();
    if (logger.fineEnabled()) {
      logger.fine("hasStore=" + hasStore + " for " + getFullPath());
    }
    return hasStore;
  }

  protected UUIDAdvisor createUUIDAdvisor(InternalDistributedSystem system,
      InternalRegionArguments ira) {
    UUIDAdvisor advisor = new UUIDAdvisor(system, ira);
    advisor.invokeInitialize();
    return advisor;
  }

  /**
   * This class generates UUIDs that are globally unique for the region.
   * Currently used by GemFireXD for IDENTITY columns and region keys for tables
   * having no primary keys.
   * 
   * @author swale
   * @since 7.0
   */
  final class UUIDAdvisor extends PersistentUUIDAdvisor {

    private UUIDAdvisor(InternalDistributedSystem sys,
        InternalRegionArguments ira) {
      super(sys, LocalRegion.this.getFullPath(), ira.getUUIDRecordInterval(),
          LocalRegion.this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void initVMIDLockService() {
      // check if there is a data store available, else our ID for this region
      // may not be persistable, for example, and not work
      if (!hasDataStores()) {
        throw new NoDataStoreAvailableException(LocalizedStrings
            .DistributedRegion_NO_DATA_STORE_FOUND_FOR_DISTRIBUTION
                .toLocalizedString(LocalRegion.this));
      }
      super.initVMIDLockService();
    }

    @Override
    protected boolean doPersist() {
      final GemFireCacheImpl.StaticSystemCallbacks sysCb =
          GemFireCacheImpl.FactoryStatics.systemCallbacks;
      return getDataPolicy().withPersistence() && sysCb != null
          && sysCb.createUUIDPersistentRegion(LocalRegion.this);
    }

    @Override
    protected CancelCriterion getCancelCriterion(
        final InternalDistributedSystem sys) {
      return LocalRegion.this.getCancelCriterion();
    }

    @Override
    public final String toString() {
      return "UUIDAdvisor for " + fullPath
          + (getDiskStore() != null ? " {PERSISTENT}" : "");
    }
  }

  /**
   * This returns memory overhead excluding individual entry sizes. Just additional
   * memory consumed by this data structure. 
   * 
   * @param sizer The sizer object that is to be used for estimating objects.
   * @return memory overhead by this region.
   */
  public long estimateMemoryOverhead(SingleObjectSizer sizer) {
    return sizer.sizeof(this) + getRegionMap().estimateMemoryOverhead(sizer);
  }

  /// Variables and methods for test Hook for HDFS ///////
  
  private boolean isTest = false;
  private AtomicInteger countNotFoundInLocal = null; 
  public void setIsTest() {
    isTest = true;
    countNotFoundInLocal = new AtomicInteger();
  }
  public boolean isTest() {
    return isTest;
  } 
  public void incCountNotFoundInLocal() {
    countNotFoundInLocal.incrementAndGet();
  }
  
  public Integer getCountNotFoundInLocal() {
    return countNotFoundInLocal.get();
  }
  /// End of Variables and methods for test Hook for HDFS ///////

  public void forceHDFSCompaction(boolean isMajor, Integer maxWaitTime) {
    throw new UnsupportedOperationException(
        LocalizedStrings.HOPLOG_DOES_NOT_USE_HDFSSTORE
            .toLocalizedString(getName()));
  }

  public void flushHDFSQueue(int maxWaitTime) {
    throw new UnsupportedOperationException(
        LocalizedStrings.HOPLOG_DOES_NOT_USE_HDFSSTORE
            .toLocalizedString(getName()));
  }
  
  public long lastMajorHDFSCompaction() {
    throw new UnsupportedOperationException();
  }
  
  /**
   * for testing #49506: while test latch is on, to halt
   * suspendApplyAllSuspects, we let other inserts go (and they will miss
   * insert).
   * 
   */
  public void suspendApplyAllSuspects() {
    if (applyAllSuspectTestLatch != null
        && (this.getFullPath().equals(applyAllSuspectTEST_REGION_NAME))) {
      applyAllSuspectTestLatchWaiting = true;
      try {
        applyAllSuspectTestLatch.await();
      } catch (InterruptedException e) {
        // ignore the exception. the above latch just used for testing
      }
      applyAllSuspectTestLatchWaiting = false;
    }
  }

  public VMIdAdvisor getUUIDAdvisor() {
    return uuidAdvisor;
  }

  /**
   * {@link PartitionedRegion#initialize(InputStream, InternalDistributedMember, InternalRegionArguments)}
   * doesn't calls super.initialize where UUID postInitialize gets invoked.
   */
  public void invokeUUIDPostInitialize() {
    if (uuidAdvisor != null) {
      uuidAdvisor.postInitialize();
    }
  }

  protected StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();
  protected volatile boolean regionOverHeadAccounted = false;
  protected volatile long regionOverHead = -1L;
  protected volatile long entryOverHead = -1L;
  protected volatile long diskIdOverHead = -1L;

  protected void accountRegionOverhead() {// Not throwing LowMemoryException while region creation
    if (!this.reservedTable() && !regionOverHeadAccounted && needAccounting()) {
      synchronized (this) {
        if (!regionOverHeadAccounted) {
          this.regionOverHead = ReflectionSingleObjectSizer.INSTANCE.sizeof(this);
          callback.acquireStorageMemory(getFullPath(),
                  regionOverHead, null, true, false);
          regionOverHeadAccounted = true;
        }
      }
    }
  }


  private long getEntryOverhead(RegionEntry entry) {
    long entryOverhead = ReflectionSingleObjectSizer.INSTANCE.sizeof(entry);
    Object key = entry.getRawKey();
    if (key != null) {
      entryOverhead += CachedDeserializableFactory.calcMemSize(key);
    } else {
      // first key.
      Object firstKey = this.getRegionMap().keySet().iterator().next();
      if (firstKey != null) {
        entryOverhead += CachedDeserializableFactory.calcMemSize(firstKey);
      }
    }
    if (entry instanceof DiskEntry) {
      DiskId diskId = ((DiskEntry) entry).getDiskId();
      if (diskId != null) {
        entryOverhead += ReflectionSingleObjectSizer.INSTANCE.sizeof(diskId);
      }
    }
    return entryOverhead;
  }

  protected long calculateEntryOverhead(RegionEntry entry) {
    if (entryOverHead == -1L && !this.reservedTable() && needAccounting()) {
      synchronized (this) {
        if (entryOverHead == -1L) {
          entryOverHead = getEntryOverhead(entry);
          memTrace("Entry overhead for " + getFullPath() + " = " + entryOverHead);
        }
      }
    }
    return entryOverHead;
  }

  protected long calculateDiskIdOverhead(DiskId diskId) {
    if (!this.reservedTable() && diskIdOverHead == -1L && needAccounting()) {
      diskIdOverHead = ReflectionObjectSizer.getInstance().sizeof(diskId);
      memTrace("diskIdOverHead = " + diskIdOverHead);
    }
    return diskIdOverHead;
  }

  private AtomicLong memoryBeforeAcquire = new AtomicLong(0L);

  public static long MAX_VALUE_BEFORE_ACQUIRE = 1032 * 10; //10 KB

  private LongBinaryOperator op = new LongBinaryOperator() {

    @Override
    public long applyAsLong(long prev, long delta) {
       long newVal = prev + delta;
      if (newVal >= MAX_VALUE_BEFORE_ACQUIRE) {
        return newVal - MAX_VALUE_BEFORE_ACQUIRE;
      } else {
        return newVal;
      }
    }
  };

  protected void delayedAcquirePoolMemory(long oldSize, long newSize, boolean withEntryOverHead,
                                          boolean shouldEvict) throws LowMemoryException {
    if (!this.reservedTable() && needAccounting()) {
      long size;
      if (withEntryOverHead) {
        size = (newSize - oldSize) + Math.max(0L, entryOverHead);
      } else {
        size = (newSize - oldSize);
      }

      if (MAX_VALUE_BEFORE_ACQUIRE == 1 || size > MAX_VALUE_BEFORE_ACQUIRE) {
        if (!callback.acquireStorageMemory(getFullPath(),
                (size), null, shouldEvict, false)) {
          throwLowMemoryException(size);
        }
      } else {
        long prevValue = memoryBeforeAcquire.getAndAccumulate(size, op);
        long currValue = prevValue + size;
        if (currValue >= MAX_VALUE_BEFORE_ACQUIRE) {
          if (!callback.acquireStorageMemory(getFullPath(),
                  MAX_VALUE_BEFORE_ACQUIRE, null, shouldEvict, false)) {
            throwLowMemoryException(size);
          }
        }
      }
    }
  }

  public void acquirePoolMemory(long oldSize, long newSize, boolean withEntryOverHead,
      UMMMemoryTracker buffer, boolean shouldEvict) throws LowMemoryException {
    if (!this.reservedTable() && needAccounting()) {
      long size = 0L;
      if (withEntryOverHead) {
        size = (newSize - oldSize) + Math.max(0L, entryOverHead);
      } else {
        size = (newSize - oldSize);
      }
      if (!callback.acquireStorageMemory(getFullPath(),
          size, buffer, shouldEvict, false)) {
        throwLowMemoryException(size);
      }
    }
  }

  private void throwLowMemoryException(long size) {
    Set<DistributedMember> sm = Collections.singleton(cache.getMyId());
    throw new LowMemoryException("Could not obtain memory of size " + size, sm);
  }

  public void freePoolMemory(long oldSize, boolean withEntryOverHead) {
    if (!this.reservedTable() && needAccounting()) {
      if (withEntryOverHead) {
        callback.releaseStorageMemory(getFullPath(),
            oldSize + Math.max(0L, entryOverHead), false);
      } else {
        callback.releaseStorageMemory(getFullPath(), oldSize, false);
      }
    }
  }

  public static boolean isMetaTable(String fullpath) {
    return fullpath.startsWith("/SNAPPY_HIVE_METASTORE") ||
        fullpath.startsWith("/__UUID_PERSIST") ||
        fullpath.startsWith("/_DDL_STMTS_META_REGION");
  }

  public boolean reservedTable() {
    return isSecret() || isUsedForMetaRegion() || isUsedForPartitionedRegionAdmin()
        || isMetaTable(this.getFullPath());
  }

  protected boolean needAccounting(){
    return callback.isSnappyStore();
  }

  // All put/delete should take care of this value;
  // A simple integer as write will be few while read threads will be many and frequent.
  protected int indexOverhead = 0;

  private Object overHeadLock = new Object();

  // Num bytes to be ignored by LocalRegion while region destroy
  protected LongAdder ignoreBytes = new LongAdder();

  protected int indicesOverHead() {
    if (isUsedForPartitionedRegionBucket) {
      return getPartitionedRegion().indexOverhead;
    } else {
      return indexOverhead;
    }
  }

  public void setIndexOverhead(int val) {
    synchronized (overHeadLock){
      indexOverhead = indexOverhead + val;
    }
  }

  public void incIgnoreBytes(long numBytes) {
    ignoreBytes.add(numBytes);
  }

  protected long getIgnoreBytes() {
    if (isUsedForPartitionedRegionBucket) {
      return getPartitionedRegion().ignoreBytes.sum();
    } else {
      return ignoreBytes.sum();
    }
  }

  private void memTrace(String mesage) {
    if (java.lang.Boolean.getBoolean("snappydata.umm.memtrace")) {
      LogWriterI18n log = getLogWriterI18n();
      log.fine(mesage);
    }
  }

  public boolean isInternalColumnTable() {
    return isInternalColumnTable;
  }

  private final boolean isInternalColumnTable;

  public boolean isSnapshotEnabledRegion() {
    return (getCache().snapshotEnabledForTest() && !isUsedForMetaRegion() && concurrencyChecksEnabled);
  }
}
