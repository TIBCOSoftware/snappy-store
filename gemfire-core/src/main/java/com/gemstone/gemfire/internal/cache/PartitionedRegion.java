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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueStats;
import com.gemstone.gemfire.cache.execute.EmptyRegionFunctionException;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.NoMemberFoundException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSEntriesSet.HDFSIterator;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.CompactionStatus;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSFlushQueueFunction;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSForceCompactionArgs;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSForceCompactionFunction;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSForceCompactionResultCollector;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSLastCompactionTimeFunction;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSRegionDirector;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HoplogOrganizer;
import com.gemstone.gemfire.cache.partition.PartitionListener;
import com.gemstone.gemfire.cache.partition.PartitionNotAvailableException;
import com.gemstone.gemfire.cache.query.FunctionDomainException;
import com.gemstone.gemfire.cache.query.Index;
import com.gemstone.gemfire.cache.query.IndexCreationException;
import com.gemstone.gemfire.cache.query.IndexExistsException;
import com.gemstone.gemfire.cache.query.IndexInvalidException;
import com.gemstone.gemfire.cache.query.IndexNameConflictException;
import com.gemstone.gemfire.cache.query.IndexType;
import com.gemstone.gemfire.cache.query.NameResolutionException;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.cache.query.QueryInvocationTargetException;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.query.TypeMismatchException;
import com.gemstone.gemfire.cache.query.internal.CompiledSelect;
import com.gemstone.gemfire.cache.query.internal.DefaultQuery;
import com.gemstone.gemfire.cache.query.internal.ExecutionContext;
import com.gemstone.gemfire.cache.query.internal.QCompiler;
import com.gemstone.gemfire.cache.query.internal.QueryExecutor;
import com.gemstone.gemfire.cache.query.internal.ResultsBag;
import com.gemstone.gemfire.cache.query.internal.ResultsCollectionWrapper;
import com.gemstone.gemfire.cache.query.internal.ResultsSet;
import com.gemstone.gemfire.cache.query.internal.index.IndexManager;
import com.gemstone.gemfire.cache.query.internal.index.IndexUtils;
import com.gemstone.gemfire.cache.query.internal.index.PartitionedIndex;
import com.gemstone.gemfire.cache.query.internal.types.ObjectTypeImpl;
import com.gemstone.gemfire.cache.query.types.ObjectType;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.distributed.DistributedLockService;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.LockServiceDestroyedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisee;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.Profile;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem.DisconnectListener;
import com.gemstone.gemfire.distributed.internal.MembershipListener;
import com.gemstone.gemfire.distributed.internal.ProfileListener;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.locks.DLockRemoteToken;
import com.gemstone.gemfire.distributed.internal.locks.DLockService;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.MemberAttributes;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.DebugLogWriter;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.SetUtils;
import com.gemstone.gemfire.internal.cache.BucketAdvisor.ServerBucketProfile;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisor.CacheProfile;
import com.gemstone.gemfire.internal.cache.DestroyPartitionedRegionMessage.DestroyPartitionedRegionResponse;
import com.gemstone.gemfire.internal.cache.DistributedRegion.DiskEntryPage;
import com.gemstone.gemfire.internal.cache.DistributedRegion.DiskSavyIterator;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl.StaticSystemCallbacks;
import com.gemstone.gemfire.internal.cache.PutAllPartialResultException.PutAllPartialResult;
import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager.ResourceType;
import com.gemstone.gemfire.internal.cache.control.MemoryEvent;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds;
import com.gemstone.gemfire.internal.cache.execute.AbstractExecution;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.cache.execute.FunctionExecutionNodePruner;
import com.gemstone.gemfire.internal.cache.execute.FunctionRemoteContext;
import com.gemstone.gemfire.internal.cache.execute.InternalFunctionInvocationTargetException;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.execute.LocalResultCollector;
import com.gemstone.gemfire.internal.cache.execute.PartitionedRegionFunctionExecutor;
import com.gemstone.gemfire.internal.cache.execute.PartitionedRegionFunctionResultSender;
import com.gemstone.gemfire.internal.cache.execute.PartitionedRegionFunctionResultWaiter;
import com.gemstone.gemfire.internal.cache.execute.RegionFunctionContextImpl;
import com.gemstone.gemfire.internal.cache.execute.ServerToClientFunctionResultSender;
import com.gemstone.gemfire.internal.cache.ha.ThreadIdentifier;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.lru.HeapEvictor;
import com.gemstone.gemfire.internal.cache.lru.LRUStatistics;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.cache.partitioned.BucketListener;
import com.gemstone.gemfire.internal.cache.partitioned.ContainsKeyValueMessage;
import com.gemstone.gemfire.internal.cache.partitioned.ContainsKeyValueMessage.ContainsKeyValueResponse;
import com.gemstone.gemfire.internal.cache.partitioned.DestroyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.DestroyMessage.DestroyResponse;
import com.gemstone.gemfire.internal.cache.partitioned.DestroyRegionOnDataStoreMessage;
import com.gemstone.gemfire.internal.cache.partitioned.DumpAllPRConfigMessage;
import com.gemstone.gemfire.internal.cache.partitioned.DumpB2NRegion;
import com.gemstone.gemfire.internal.cache.partitioned.DumpB2NRegion.DumpB2NResponse;
import com.gemstone.gemfire.internal.cache.partitioned.DumpBucketsMessage;
import com.gemstone.gemfire.internal.cache.partitioned.FetchEntriesMessage;
import com.gemstone.gemfire.internal.cache.partitioned.FetchEntriesMessage.FetchEntriesResponse;
import com.gemstone.gemfire.internal.cache.partitioned.FetchEntryMessage;
import com.gemstone.gemfire.internal.cache.partitioned.FetchEntryMessage.FetchEntryResponse;
import com.gemstone.gemfire.internal.cache.partitioned.FetchKeysMessage;
import com.gemstone.gemfire.internal.cache.partitioned.FetchKeysMessage.FetchKeysResponse;
import com.gemstone.gemfire.internal.cache.partitioned.GetMessage;
import com.gemstone.gemfire.internal.cache.partitioned.GetMessage.GetResponse;
import com.gemstone.gemfire.internal.cache.partitioned.IdentityRequestMessage;
import com.gemstone.gemfire.internal.cache.partitioned.IdentityRequestMessage.IdentityResponse;
import com.gemstone.gemfire.internal.cache.partitioned.IdentityUpdateMessage;
import com.gemstone.gemfire.internal.cache.partitioned.IdentityUpdateMessage.IdentityUpdateResponse;
import com.gemstone.gemfire.internal.cache.partitioned.IndexCreationMsg;
import com.gemstone.gemfire.internal.cache.partitioned.InterestEventMessage;
import com.gemstone.gemfire.internal.cache.partitioned.InterestEventMessage.InterestEventResponse;
import com.gemstone.gemfire.internal.cache.partitioned.InvalidateMessage;
import com.gemstone.gemfire.internal.cache.partitioned.InvalidateMessage.InvalidateResponse;
import com.gemstone.gemfire.internal.cache.partitioned.PREntriesIterator;
import com.gemstone.gemfire.internal.cache.partitioned.PRLocallyDestroyedException;
import com.gemstone.gemfire.internal.cache.partitioned.PRSanityCheckMessage;
import com.gemstone.gemfire.internal.cache.partitioned.PRUpdateEntryVersionMessage;
import com.gemstone.gemfire.internal.cache.partitioned.PRUpdateEntryVersionMessage.UpdateEntryVersionResponse;
import com.gemstone.gemfire.internal.cache.partitioned.PartitionMessage.PartitionResponse;
import com.gemstone.gemfire.internal.cache.partitioned.PartitionedRegionObserver;
import com.gemstone.gemfire.internal.cache.partitioned.PartitionedRegionObserverHolder;
import com.gemstone.gemfire.internal.cache.partitioned.PutAllPRMessage;
import com.gemstone.gemfire.internal.cache.partitioned.PutAllPRMessage.PutAllResponse;
import com.gemstone.gemfire.internal.cache.partitioned.PutMessage;
import com.gemstone.gemfire.internal.cache.partitioned.PutMessage.PutResult;
import com.gemstone.gemfire.internal.cache.partitioned.RegionAdvisor;
import com.gemstone.gemfire.internal.cache.partitioned.RegionAdvisor.BucketVisitor;
import com.gemstone.gemfire.internal.cache.partitioned.RegionAdvisor.PartitionProfile;
import com.gemstone.gemfire.internal.cache.partitioned.RemoveIndexesMessage;
import com.gemstone.gemfire.internal.cache.partitioned.SizeMessage;
import com.gemstone.gemfire.internal.cache.partitioned.SizeMessage.SizeResponse;
import com.gemstone.gemfire.internal.cache.persistence.PRPersistentConfig;
import com.gemstone.gemfire.internal.cache.persistence.query.CloseableIterator;
import com.gemstone.gemfire.internal.cache.tier.InterestType;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.cache.versions.ConcurrentCacheModificationException;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySenderEventProcessor;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderException;
import com.gemstone.gemfire.internal.cache.wan.parallel.ConcurrentParallelGatewaySenderQueue;
import com.gemstone.gemfire.internal.cache.wan.parallel.ParallelGatewaySenderImpl;
import com.gemstone.gemfire.internal.cache.wan.parallel.ParallelGatewaySenderQueue;
import com.gemstone.gemfire.internal.concurrent.AB;
import com.gemstone.gemfire.internal.concurrent.CFactory;
import com.gemstone.gemfire.internal.concurrent.CM;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.sequencelog.RegionLogger;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.gemstone.gemfire.internal.util.TransformUtils;
import com.gemstone.gemfire.internal.util.concurrent.FutureResult;
import com.gemstone.gemfire.internal.util.concurrent.StoppableCountDownLatch;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TObjectObjectProcedure;
import com.gemstone.org.jgroups.util.StringId;

/**
 * A Region whose total storage is split into chunks of data (partitions) which
 * are copied up to a configurable level (for high availability) and placed on
 * multiple VMs for improved performance and increased storage capacity.
 * 
 * @since 5.0
 * @author Rohit Reja, Tushar Apshankar, Girish Thombare, Negi Tribhuwan, Greg
 *         Passmore, Mitch Thomas, Bruce Schuchardt
 */
public class PartitionedRegion extends LocalRegion implements 
  CacheDistributionAdvisee, QueryExecutor {

  public static final Random rand = LocalRegion.rand;

  private static final AtomicInteger SERIAL_NUMBER_GENERATOR = new AtomicInteger();
  
  private final DiskRegionStats diskRegionStats;
  /**
   * Changes scope of replication to secondary bucket to SCOPE.DISTRIBUTED_NO_ACK
   */
  public static final boolean DISABLE_SECONDARY_BUCKET_ACK = Boolean.getBoolean(
      "gemfire.disablePartitionedRegionBucketAck");
  
  /**
   * A debug flag used for testing calculation of starting bucket id
   */
  public static boolean BEFORE_CALCULATE_STARTING_BUCKET_FLAG = false;
  
  /**
   * Thread specific random number
   */
  private static ThreadLocal threadRandom = new ThreadLocal() {
    @Override
    protected Object initialValue() {
      int i = rand.nextInt();
      if (i < 0) {
        i = -1 * i;
      }
      return Integer.valueOf(i);
    }
  };

  /**
   * Global Region for storing PR config ( PRName->PRConfig). This region would
   * be used to resolve PR name conflict.*
   */
  private volatile Region<String, PartitionRegionConfig> prRoot;

  /**
   * 
   * PartitionedRegionDataStore class takes care of data storage for the PR.
   * This will contain the bucket Regions to store data entries for PR*
   */
  protected PartitionedRegionDataStore dataStore;

  /**
   * The advisor that hold information about this partitioned region
   */
  private final RegionAdvisor distAdvisor;

  /** Logging mechanism for debugging */
  public final LogWriterI18n logger;

  /** cleanup flags * */
  private boolean cleanPRRegistration = false;

  /** Time to wait for for acquiring distributed lock ownership */
  final static long VM_OWNERSHIP_WAIT_TIME = PRSystemPropertyGetter
      .parseLong(
          System
              .getProperty(PartitionedRegionHelper.VM_OWNERSHIP_WAIT_TIME_PROPERTY),
          PartitionedRegionHelper.VM_OWNERSHIP_WAIT_TIME_DEFAULT);

  /**
   * default redundancy level is 0.
   */
  final int redundantCopies;

  /**
   * The miminum amount of redundancy needed for a write operation
   */
  final int minimumWriteRedundancy;

  /**
   * The miminum amount of redundancy needed for a read operation
   */
  final int minimumReadRedundancy;

  /**
   * Ratio of currently allocated memory to maxMemory that triggers rebalance
   * activity.
   */
  final static float rebalanceThreshold = 0.75f;

  /** The maximum memory allocated for this node in Mb */
  final int localMaxMemory;

  /** The maximum milliseconds for retrying operations */
  final private int retryTimeout;

  /**
   * The statistics for this PR
   */
  public final PartitionedRegionStats prStats;

  // private Random random = new Random(System.currentTimeMillis());

  /** Number of initial buckets */
  private final int totalNumberOfBuckets;

  /**
   * To check if local cache is enabled.
   */
  private static final boolean localCacheEnabled = false;

  // private static final boolean throwIfNoNodesLeft = true;

  public static final int DEFAULT_RETRY_ITERATIONS = 3;

  /**
   * Flag to indicate if a cache loader is present
   */
  private volatile boolean haveCacheLoader;

  /**
   * Region identifier used for DLocks (Bucket and Region)
   */
  private final String regionIdentifier;

  /**
   * Maps each PR to a prId. This prId will uniquely identify the PR.
   */
  static final PRIdMap prIdToPR = new PRIdMap();

  /**
   * Flag to indicate whether region is closed
   * 
   */
  public volatile boolean isClosed = false;

  /**
   * a flag indicating that the PR is destroyed in this VM
   */
  public volatile boolean isLocallyDestroyed = false;
  
  /**
   * the thread locally destroying this pr.  not volatile,
   * so always check isLocallyDestroyed before checking locallyDestroyingThread
   * 
   * @guarded.By {@link #isLocallyDestroyed}
   */
  public Thread locallyDestroyingThread;

  // TODO someone please add a javadoc for this
  private volatile boolean hasPartitionedIndex = false;

  /**
   * regionMembershipListener notification requires this to be plugged into
   * a PR's RegionAdvisor
   */
  private final AdvisorListener advisorListener = new AdvisorListener();

  private BucketListener bucketListener;

  /*
   * Map containing <IndexTask, FutureTask<IndexTask> or Index>.
   * IndexTask represents an index thats completely created or
   * one thats in create phase. This is done in order to avoid
   * synchronization on the indexes.
   */
  private final CM indexes = CFactory.createCM();

  private volatile boolean recoveredFromDisk;

  public static final int RUNNING_MODE = -1;
  public static final int PRIMARY_BUCKETS_LOCKED = 1;
  public static final int DISK_STORE_FLUSHED = 2;
  public static final int OFFLINE_EQUAL_PERSISTED = 3;

  private volatile int shutDownAllStatus = RUNNING_MODE;

  /** Maximum size in bytes for ColumnBatches. */
  private int columnBatchSize = -1;

  /** Maximum rows to keep in the delta buffer. */
  private int columnMaxDeltaRows = -1;

  /** Minimum size for ColumnBatches. */
  private int columnMinDeltaRows = 200;

  /** default compression used by the column store */
  private String columnCompressionCodec;

  public void setColumnBatchSizes(int size, int maxDeltaRows,
      int minDeltaRows) {
    columnBatchSize = size;
    columnMaxDeltaRows = maxDeltaRows;
    columnMinDeltaRows = minDeltaRows;
  }

  private void initFromHiveMetaData() {
    final GemFireCacheImpl.StaticSystemCallbacks sysCb = GemFireCacheImpl
        .getInternalProductCallbacks();
    if (sysCb != null) {
      ExternalTableMetaData metadata = sysCb.fetchSnappyTablesHiveMetaData(this);
      if (this.columnBatchSize == -1) {
        this.columnBatchSize = metadata.columnBatchSize;
      }
      if (this.columnMaxDeltaRows == -1) {
        this.columnMaxDeltaRows = metadata.columnMaxDeltaRows;
      }
      if (this.columnCompressionCodec == null) {
        this.columnCompressionCodec = metadata.compressionCodec;
      }
    }
  }

  public int getColumnBatchSize() {
    int columnBatchSize = this.columnBatchSize;
    if (columnBatchSize == -1) {
      initFromHiveMetaData();
      return this.columnBatchSize;
    }
    return columnBatchSize;
  }

  public int getColumnMaxDeltaRows() {
    int columnMaxDeltaRows = this.columnMaxDeltaRows;
    if (columnMaxDeltaRows == -1) {
      initFromHiveMetaData();
      return this.columnMaxDeltaRows;
    }
    return columnMaxDeltaRows;
  }

  public int getColumnMinDeltaRows() {
    return columnMinDeltaRows;
  }

  public String getColumnCompressionCodec() {
    String codec = this.columnCompressionCodec;
    if (codec == null && !cache.isUnInitializedMember(cache.getMyId())) {
      initFromHiveMetaData();
      return this.columnCompressionCodec;
    }
    return codec;
  }

  private final long birthTime = System.currentTimeMillis();

  public void setShutDownAllStatus(int newStatus) {
    this.shutDownAllStatus = newStatus;
  }

  private PartitionedRegion colocatedWithRegion;

  private List<BucketRegion> sortedBuckets; 

  private ScheduledExecutorService bucketSorter;

  private final ConcurrentMap<String, Integer[]> partitionsMap =
    new ConcurrentHashMap<String, Integer[]>();

  public ConcurrentMap<String, Integer[]> getPartitionsMap() {
    return this.partitionsMap;
  }

  /**
  * for wan shadowPR
  */
  private boolean enableConflation;

  /**
   * Byte 0 = no NWHOP Byte 1 = NWHOP to servers in same server-grp Byte 2 =
   * NWHOP tp servers in other server-grp
   */
  private final ThreadLocal<Byte> isNetworkHop = new ThreadLocal<Byte>() {
    @Override
    protected Byte initialValue() {
      return Byte.valueOf((byte)0);
    }
  };

  public void setIsNetworkHop(Byte value) {
    this.isNetworkHop.set(value);
  }

  public Byte isNetworkHop() {
    return this.isNetworkHop.get();
  }
  
  private final ThreadLocal<Byte> metadataVersion = new ThreadLocal<Byte>() {
    @Override
    protected Byte initialValue() {
      return 0;
    }
  };

  public void setMetadataVersion(Byte value) {
     this.metadataVersion.set(value);
  }

  public Byte getMetadataVersion() {
    return this.metadataVersion.get();
  }
      

  /**
   * Returns the LRUStatistics for this PR.
   * This is needed to find the single instance of LRUStatistics
   * created early for a PR when it is recovered from disk.
   * This fixes bug 41938
   */
  public LRUStatistics getPRLRUStatsDuringInitialization() {
    LRUStatistics result = null;
    if (getDiskStore() != null) {
      result = getDiskStore().getPRLRUStats(this);
    }
    return result;
  }
               
  
  //////////////////  ConcurrentMap methods //////////////////               
          
  @Override
   public boolean remove(Object key, Object value, Object callbackArg) {
     final long startTime = PartitionedRegionStats.startTime();
     try {
       return super.remove(key, value, callbackArg);
     }
     finally {
       this.prStats.endDestroy(startTime);
     }
   }
   
   
               
   //////////////////  End of ConcurrentMap methods ////////////////// 
               

  public PartitionListener[] getPartitionListeners() {
    return this.partitionListeners;
  }

  public void setBucketListener(BucketListener listener) {
    this.bucketListener = listener;
  }

  public BucketListener getBucketListener() {
    return this.bucketListener;
  }

  /**
   * Return canonical representation for a bucket (for logging)
   * 
   * @param bucketId
   *                the bucket
   * @return a String representing this PR and the bucket
   */
  public String bucketStringForLogs(int bucketId) {
    return getPRId() + BUCKET_ID_SEPARATOR + bucketId;
  }

  /** Separator between PRId and bucketId for creating bucketString */
  public static final String BUCKET_ID_SEPARATOR = ":";

  /**
   * Clear the prIdMap, typically used when disconnecting from the distributed
   * system or clearing the cache
   */
  public static void clearPRIdMap() {
    synchronized (prIdToPR) {
      prIdToPR.clear();
    }
  }

  private static DisconnectListener dsPRIdCleanUpListener = new DisconnectListener() {
    @Override
    public String toString() {
      return LocalizedStrings.PartitionedRegion_SHUTDOWN_LISTENER_FOR_PARTITIONEDREGION.toLocalizedString();
    }

    public void onDisconnect(InternalDistributedSystem sys) {
      clearPRIdMap();
    }
  };


  public static class PRIdMap extends HashMap {
    private static final long serialVersionUID = 3667357372967498179L;
    public final static String DESTROYED = "Partitioned Region Destroyed";

    final static String LOCALLY_DESTROYED = "Partitioned Region Is Locally Destroyed";

    final static String FAILED_REGISTRATION = "Partitioned Region's Registration Failed";

    public final static String NO_PATH_FOUND = "NoPathFound";

    private volatile boolean cleared = true;

    @Override
    public Object get(Object key) {
      throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_PRIDMAPGET_NOT_SUPPORTED_USE_GETREGION_INSTEAD.toLocalizedString());
    }

    public Object getRegion(Object key) throws PRLocallyDestroyedException {
      if (cleared) {
        Cache c = GemFireCacheImpl.getInstance();
        if (c == null) {
          throw new CacheClosedException();
        }
        else {
          c.getCancelCriterion().checkCancelInProgress(null);
        }
      }
      Assert.assertTrue(key instanceof Integer);

      Object o = super.get(key);
      if (o == DESTROYED) {
        throw new RegionDestroyedException(LocalizedStrings.PartitionedRegion_REGION_FOR_PRID_0_IS_DESTROYED.toLocalizedString(key), NO_PATH_FOUND);
      }
      if (o == LOCALLY_DESTROYED) {
//        if (logger.fineEnabled()) {
//          logger.fine("Got LOCALLY_DESTROYED for prId = " + key);
//        }
        throw new PRLocallyDestroyedException(LocalizedStrings.PartitionedRegion_REGION_WITH_PRID_0_IS_LOCALLY_DESTROYED_ON_THIS_NODE.toLocalizedString(key));
      }
      if (o == FAILED_REGISTRATION) {
//        if (logger.fineEnabled()) {
//          logger.fine("Got FAILED_REGISTRATION for prId = " + key);
//        }
        throw new PRLocallyDestroyedException(LocalizedStrings.PartitionedRegion_REGION_WITH_PRID_0_FAILED_INITIALIZATION_ON_THIS_NODE.toLocalizedString(key));
      }
      return o;
    }

    @Override
    public Object remove(final Object key) {
      return put(key, DESTROYED, true);
    }

    @Override
    public Object put(final Object key, final Object value) {
      return put(key, value, true);
    }

    public Object put(final Object key, final Object value,
        boolean sendIdentityRequestMessage) {
//      if (logger.fineEnabled()) {
//        logger.fine("Put of key=" + key + " val=" + value);
//      }
      if (cleared) {
        cleared = false;
      }

      if (key == null) {
        throw new NullPointerException(LocalizedStrings.PartitionedRegion_NULL_KEY_NOT_ALLOWED_FOR_PRIDTOPR_MAP.toLocalizedString());
      }
      if (value == null) {
        throw new NullPointerException(LocalizedStrings.PartitionedRegion_NULL_VALUE_NOT_ALLOWED_FOR_PRIDTOPR_MAP.toLocalizedString());
      }
      Assert.assertTrue(key instanceof Integer);
      if (sendIdentityRequestMessage)
        IdentityRequestMessage.setLatestId(((Integer)key).intValue());
      if ((super.get(key) == DESTROYED) && (value instanceof PartitionedRegion)) {
        PartitionedRegionException pre = new PartitionedRegionException(LocalizedStrings.PartitionedRegion_CAN_NOT_REUSE_OLD_PARTITIONED_REGION_ID_0.toLocalizedString(key));
        throw pre;
      }
      return super.put(key, value);
    }

    @Override
    public void clear() {
      // if (logger != null) {
      // logger.info("Clearing the prIdMap, maxPRId="
      // + IdentityRequestMessage.getLatestId());
      // }
      this.cleared = true;
      super.clear();
    }

    public synchronized String dump() {
      StringBuffer b = new StringBuffer("prIdToPR Map@");
      b.append(System.identityHashCode(prIdToPR)).append(":\n");
      Map.Entry me;
      for (Iterator i = prIdToPR.entrySet().iterator(); i.hasNext();) {
        me = (Map.Entry)i.next();
        b.append(me.getKey()).append("=>").append(me.getValue());
        if (i.hasNext()) {
          b.append("\n");
        }
      }
      return b.toString();
    }
  }

  private int partitionedRegionId = -3;

  // final private Scope userScope;

  /** Node description */
  final private Node node;

  /** Helper Object for redundancy Management of PartitionedRegion */
  private final PRHARedundancyProvider redundancyProvider;

  /**
   * flag saying whether this VM needs cache operation notifications from other
   * members
   */
  private boolean requiresNotification;

  /**
   * Latch that signals when the Bucket meta-data is ready to receive updates
   */
  private final StoppableCountDownLatch initializationLatchAfterBucketIntialization;

  /**
   * Fast path to check for {@link #initializationLatchAfterBucketIntialization}
   */
  private boolean initializedBuckets;

  /** indicates whether initial profile exchange has completed */
  private volatile boolean profileExchanged;

  /**
   * Constructor for a PartitionedRegion. This has an accessor (Region API)
   * functionality and contains a datastore for actual storage. An accessor can
   * act as a local cache by having a local storage enabled. A PartitionedRegion
   * can be created by a factory method of RegionFactory.java and also by
   * invoking Cache.createRegion(). (Cache.xml etc to be added)
   * 
   */

  static public final String RETRY_TIMEOUT_PROPERTY = 
      "gemfire.partitionedRegionRetryTimeout";

  private final PartitionRegionConfigValidator validator;

  List<FixedPartitionAttributesImpl> fixedPAttrs;

  private byte fixedPASet;

  public final List<PartitionedRegion> colocatedByList =
      new CopyOnWriteArrayList<PartitionedRegion>();

  private final PartitionListener[] partitionListeners;

  private boolean isShadowPR = false;
  private boolean isShadowPRForHDFS = false;
  
  private ParallelGatewaySenderImpl parallelGatewaySender = null;
  
  private final ThreadLocal<Boolean> queryHDFS = new ThreadLocal<Boolean>() {
    @Override
    protected Boolean initialValue() {
      return false;
    }
  };
  
  public PartitionedRegion(String regionname, RegionAttributes ra,
      LocalRegion parentRegion, GemFireCacheImpl cache,
      InternalRegionArguments internalRegionArgs) {
    super(regionname, ra, parentRegion, cache, internalRegionArgs);
    if (Boolean.getBoolean(getClass().getName() + "-logging")) {
      this.logger = new DebugLogWriter((LogWriterImpl)getCache().getLogger(),
          getClass());
    }
    else {
      this.logger = getCache().getLoggerI18n();
    }

    this.node = initializeNode();
    this.prStats = new PartitionedRegionStats(cache.getDistributedSystem(), getFullPath());
    this.regionIdentifier = getFullPath().replace('/', '#');

    if (this.logger.fineEnabled()) {
      this.logger.fine("Constructing Partitioned Region " + regionname);
    }

    // By adding this disconnect listener we ensure that the pridmap is cleaned
    // up upon
    // distributed system disconnect even this (or other) PRs are destroyed
    // (which prevents pridmap cleanup).
    cache.getDistributedSystem().addDisconnectListener(dsPRIdCleanUpListener);
    
    // add an async queue for the region if the store name is not null. 
    if (this.getHDFSStoreName() != null) {
      String eventQueueName = getHDFSEventQueueName();
      this.addAsyncEventQueueId(eventQueueName);
    }

    // this.userScope = ra.getScope();
    this.partitionAttributes = ra.getPartitionAttributes();
    this.localMaxMemory = this.partitionAttributes.getLocalMaxMemory();
    this.retryTimeout = Integer.getInteger(RETRY_TIMEOUT_PROPERTY,
        PartitionedRegionHelper.DEFAULT_TOTAL_WAIT_RETRY_ITERATION).intValue();
    this.totalNumberOfBuckets = this.partitionAttributes.getTotalNumBuckets();
    this.prStats.incTotalNumBuckets(this.totalNumberOfBuckets);
    this.distAdvisor = RegionAdvisor.createRegionAdvisor(this); // Warning: potential early escape of instance
    this.redundancyProvider = new PRHARedundancyProvider(this); // Warning:
                                                                // potential
                                                                // early escape
                                                                // instance

    // localCacheEnabled = ra.getPartitionAttributes().isLocalCacheEnabled();
    // This is to make sure that local-cache get and put works properly.
    // getScope is overridden to return the correct scope.
    // this.scope = Scope.LOCAL;
    this.redundantCopies = ra.getPartitionAttributes().getRedundantCopies();
    this.prStats.setConfiguredRedundantCopies(ra.getPartitionAttributes().getRedundantCopies());
    
    // No redundancy required for writes
    this.minimumWriteRedundancy = Integer.getInteger(
        "gemfire.mimimumPartitionedRegionWriteRedundancy", 0).intValue();
    // No redundancy required for reads
    this.minimumReadRedundancy = Integer.getInteger(
        "gemfire.mimimumPartitionedRegionReadRedundancy", 0).intValue();

    this.haveCacheLoader = ra.getCacheLoader() != null;

    this.initializationLatchAfterBucketIntialization = new StoppableCountDownLatch(
        this.getCancelCriterion(), 1);
    this.initializedBuckets = false;
    
    this.validator = new PartitionRegionConfigValidator(this);
    this.partitionListeners = this.partitionAttributes.getPartitionListeners(); 

    if (cache.getLogger().fineEnabled()) {
      cache.getLogger().fine(
          "Partitioned Region " + regionname + " constructed "
              + (this.haveCacheLoader ? "with a cache loader" : ""));
    }
    if (this.getEvictionAttributes() != null
        && this.getEvictionAttributes().getAlgorithm().isLRUHeap()) {
      this.sortedBuckets = new ArrayList<BucketRegion>();
      final ThreadGroup grp = LogWriterImpl.createThreadGroup("BucketSorterThread",
          cache.getLoggerI18n());
      ThreadFactory tf = new ThreadFactory() {
        public Thread newThread(Runnable r) {
          Thread t = new Thread(grp, r, "BucketSorterThread");
          t.setDaemon(true);
          return t;
        }
      };
      this.bucketSorter = Executors.newScheduledThreadPool(1, tf);
    }
    // If eviction is on, Create an instance of PartitionedRegionLRUStatistics
    if ((this.getEvictionAttributes() != null
        && !this.getEvictionAttributes().getAlgorithm().isNone()
        && this.getEvictionAttributes().getAction().isOverflowToDisk())
        || this.getDataPolicy().withPersistence()) {
      StatisticsFactory sf = this.getCache().getDistributedSystem();
      this.diskRegionStats = new DiskRegionStats(sf, getFullPath());
    } else {
      this.diskRegionStats = null;
    }
    if (internalRegionArgs.isUsedForParallelGatewaySenderQueue()) {
      this.isShadowPR = true;
      this.parallelGatewaySender = internalRegionArgs.getParallelGatewaySender();
      if (internalRegionArgs.isUsedForHDFSParallelGatewaySenderQueue())
        this.isShadowPRForHDFS = true;
    }
    
    
    /*
     * Start persistent profile logging if we are a persistent region.
     */
    if(dataPolicy.withPersistence()) {
      startPersistenceProfileLogging();      
    }
  }

  /**
   * Monitors when other members that participate in this persistent region are removed and creates
   * a log entry marking the event.
   */
  private void startPersistenceProfileLogging() {
    this.distAdvisor.addProfileChangeListener(new ProfileListener() {
      @Override
      public void profileCreated(Profile profile) {
      }

      @Override
      public void profileUpdated(Profile profile) {
      }
      
      @Override
      public void profileRemoved(Profile profile, boolean destroyed) {
        /*
         * Don't bother logging membership activity if our region isn't ready.
         */
        if(isInitialized()) {
          CacheProfile cacheProfile = ((profile instanceof CacheProfile) ? (CacheProfile) profile : null);
          Set<String> onlineMembers = new HashSet<String>();

          TransformUtils.transform(PartitionedRegion.this.distAdvisor.advisePersistentMembers().values(),onlineMembers,TransformUtils.persistentMemberIdToLogEntryTransformer);

          PartitionedRegion.this.logger.info(LocalizedStrings.PersistenceAdvisorImpl_PERSISTENT_VIEW,
              new Object[] {PartitionedRegion.this.getName(),TransformUtils.persistentMemberIdToLogEntryTransformer.transform(cacheProfile.persistentID),onlineMembers});                          
        }
      }      
    });
  }

  @Override
  public final boolean isHDFSRegion() {
    return this.getHDFSStoreName() != null;
  }

  @Override
  public final boolean isHDFSReadWriteRegion() {
    return isHDFSRegion() && !getHDFSWriteOnly();
  }

  @Override
  protected final boolean isHDFSWriteOnly() {
    return isHDFSRegion() && getHDFSWriteOnly();
  }

  public final void setQueryHDFS(boolean includeHDFS) {
    queryHDFS.set(includeHDFS);
  }

  @Override
  public final boolean includeHDFSResults() {
    return queryHDFS.get();
  }

  public final boolean isShadowPR() {
    return isShadowPR;
  }

  public final boolean isShadowPRForHDFS() {
    return isShadowPRForHDFS;
  }
  
  public ParallelGatewaySenderImpl getParallelGatewaySender() {
    return parallelGatewaySender;
  }
  
  public Set<String> getParallelGatewaySenderIds() {
    Set<String> regionGatewaySenderIds = this.getAllGatewaySenderIds();
    if (regionGatewaySenderIds.isEmpty()) {
      return Collections.EMPTY_SET;
    }
    Set<GatewaySender> cacheGatewaySenders = getCache().getAllGatewaySenders();
    Set<String> parallelGatewaySenderIds = new HashSet<String>();
    for (GatewaySender sender : cacheGatewaySenders) {
      if (regionGatewaySenderIds.contains(sender.getId())
          && sender.isParallel()) {
        parallelGatewaySenderIds.add(sender.getId());
      }
    }
    return parallelGatewaySenderIds;
  }
  
  List<PartitionedRegion> getColocatedByList() {
	return this.colocatedByList;
  }

  public boolean isColocatedBy() {
    return !this.colocatedByList.isEmpty();
  } 

  private void createAndValidatePersistentConfig() {
    DiskStoreImpl dsi = this.getDiskStore();
    if (this.dataPolicy.withPersistence() && !this.concurrencyChecksEnabled
        && supportsConcurrencyChecks()) {
      if (allowConcurrencyChecksOverride()) {
        logger.info(
                LocalizedStrings.PartitionedRegion_ENABLING_CONCURRENCY_CHECKS_FOR_PERSISTENT_PR,
                this.getFullPath());
        this.concurrencyChecksEnabled = true;
      }
    }
    final PartitionedRegion colocatedWithRegion = this.colocatedWithRegion;
    if (dsi != null && this.getDataPolicy().withPersistence()) {
      String colocatedWith = colocatedWithRegion == null 
          ? "" : colocatedWithRegion.getFullPath(); 
      PRPersistentConfig config = dsi.getPersistentPRConfig(this.getFullPath());
      if(config != null) {
        if (config.getTotalNumBuckets() != this.getTotalNumberOfBuckets()) {
          Object[] prms = new Object[] { this.getFullPath(), this.getTotalNumberOfBuckets(),
              config.getTotalNumBuckets() };
          IllegalStateException ise = new IllegalStateException(
              LocalizedStrings.PartitionedRegion_FOR_REGION_0_TotalBucketNum_1_SHOULD_NOT_BE_CHANGED_Previous_Configured_2.toString(prms));
          throw ise;
        }
        //Make sure we don't change to be colocated with a different region
        //We also can't change from colocated to not colocated without writing
        //a record to disk, so we won't allow that right now either.
        if (!colocatedWith.equals(config.getColocatedWith())) {
          Object[] prms = new Object[] { this.getFullPath(), colocatedWith,
              config.getColocatedWith() };
          DiskAccessException dae = new DiskAccessException(LocalizedStrings.LocalRegion_A_DISKACCESSEXCEPTION_HAS_OCCURED_WHILE_WRITING_TO_THE_DISK_FOR_REGION_0_THE_REGION_WILL_BE_CLOSED.toLocalizedString(this.getFullPath()), null, dsi);
          dsi.handleDiskAccessException(dae, false);
          IllegalStateException ise = new IllegalStateException(
              LocalizedStrings.PartitionedRegion_FOR_REGION_0_ColocatedWith_1_SHOULD_NOT_BE_CHANGED_Previous_Configured_2.toString(prms));
          throw ise;
        }
      } else {
        config= new PRPersistentConfig(this.getTotalNumberOfBuckets(), 
            colocatedWith);
        dsi.addPersistentPR(this.getFullPath(), config);
        //Fix for support issue 7870 - the parent region needs to be able
        //to discover that there is a persistent colocated child region. So
        //if this is a child region, persist its config to the parent disk store
        //as well.
        if(colocatedWithRegion != null 
            && colocatedWithRegion.getDiskStore() != null
            && colocatedWithRegion.getDiskStore() != dsi) {
          colocatedWithRegion.getDiskStore().addPersistentPR(this.getFullPath(), config);
        }
      }
    }
  }

  void setColocatedWithRegion(final PartitionedRegion colocatedWithRegion) {
    this.colocatedWithRegion = colocatedWithRegion;
    if (colocatedWithRegion != null) {
      // In colocation chain, child region inherita the fixed partitin
      // attributes from parent region.
      this.fixedPAttrs = colocatedWithRegion.getFixedPartitionAttributesImpl();
      this.fixedPASet = colocatedWithRegion.fixedPASet;
      synchronized (colocatedWithRegion.colocatedByList) {
        colocatedWithRegion.colocatedByList.add(this);
      }
    }
    else {
      this.fixedPAttrs = this.partitionAttributes.getFixedPartitionAttributes();
      this.fixedPASet = 0;
    }
  }

  /**
   * Initializes the PartitionedRegion meta data, adding this Node and starting
   * the service on this node (if not already started).
   * Made this synchronized for bug 41982
   * @return true if initialize was done; false if not because it was destroyed
   */
  private synchronized boolean initPRInternals(InternalRegionArguments internalRegionArgs) {
    
    if (this.isLocallyDestroyed) {
      // don't initialize if we are already destroyed for bug 41982
      return false;
    }
    /* Initialize the PartitionRegion */
    if (cache.isCacheAtShutdownAll()) {
      throw new CacheClosedException("Cache is shutting down");
    }

    this.colocatedWithRegion = null;
    setColocatedWithRegion(ColocationHelper.getColocatedRegion(this));

    validator.validateColocation();
    
    //Do this after the validation, to avoid creating a persistent config
    //for an invalid PR.
    createAndValidatePersistentConfig();
    initializePartitionedRegion();
    
    /* set the total number of buckets */
    // setTotalNumOfBuckets();
    // If localMaxMemory is set to 0, do not initialize Data Store.
    final boolean storesData = this.localMaxMemory != 0;
    if (storesData) {
      initializeDataStore(this.getAttributes());
    }

    // register this PartitionedRegion, Create a PartitionRegionConfig and bind
    // it into the allPartitionedRegion system wide Region.
    // IMPORTANT: do this before advising peers that we have this region
    registerPartitionedRegion(storesData);
    
    getRegionAdvisor().initializeRegionAdvisor(); // must be BEFORE initializeRegion call
    getRegionAdvisor().addMembershipListener(this.advisorListener); // fix for bug 38719

    // 3rd part of eviction attributes validation, after eviction attributes
    // have potentially been published (by the first VM) but before buckets are created
    validator.validateEvictionAttributesAgainstLocalMaxMemory();
    validator.validateFixedPartitionAttributes();

    // Register with the other Nodes that have this region defined, this
    // allows for an Advisor profile exchange, also notifies the Admin
    // callbacks that this Region is created.
    try {
      new CreateRegionProcessor(this).initializeRegion();
    } catch (IllegalStateException e) {
      // If this is a PARTITION_PROXY then retry region creation
      // after toggling the concurrencyChecksEnabled flag. This is
      // required because for persistent regions, we enforce concurrencyChecks
      if (!this.isDataStore() && supportsConcurrencyChecks()
          && allowConcurrencyChecksOverride()) {
        this.concurrencyChecksEnabled = !this.concurrencyChecksEnabled;
        new CreateRegionProcessor(this).initializeRegion();
      } else {
        throw e;
      }
    }

    if (!this.isDestroyed && !this.isLocallyDestroyed) {
      // Register at this point so that other members are known
      this.cache.getResourceManager().addResourceListener(ResourceType.MEMORY, this);
    }
    
    // Create OQL indexes before starting GII.
    createOQLIndexes(internalRegionArgs);
    
    // if any other services are dependent on notifications from this region,
    // then we need to make sure that in-process ops are distributed before
    // releasing the GII latches
    if (this.isAllEvents() ||
        (this.enableGateway && (this.cache.getGatewayHubs().size() > 0))) {
      StateFlushOperation sfo = new StateFlushOperation(getDistributionManager());
      try {
        sfo.flush(this.distAdvisor.adviseAllPRNodes(),
          getDistributionManager().getId(),
          DistributionManager.HIGH_PRIORITY_EXECUTOR, false);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        getCancelCriterion().checkCancelInProgress(ie);
      }
    }

    releaseBeforeGetInitialImageLatch(); // moved to this spot for bug 36671

    // requires prid assignment mthomas 4/3/2007
    getRegionAdvisor().processProfilesQueuedDuringInitialization(); 

    releaseAfterBucketMetadataSetupLatch();
        
    try {
      if(storesData) {
        if(this.redundancyProvider.recoverPersistentBuckets()) {
          //Mark members as recovered from disk recursively, starting
          //with the leader region.
          PartitionedRegion leaderRegion = ColocationHelper.getLeaderRegion(this);
          markRecoveredRecursively(leaderRegion);
        }
      }
    }
    catch (RegionDestroyedException rde) {
      // Do nothing.
      if (logger.fineEnabled()) {
        logger.fine("initPRInternals: failed due to exception", rde);
      }
    }

    releaseAfterGetInitialImageLatch();

    try {
      if(storesData) {
        this.redundancyProvider.scheduleCreateMissingBuckets();

        if (this.redundantCopies > 0) {
          this.redundancyProvider.startRedundancyRecovery();
        }
      }
    }
    catch (RegionDestroyedException rde) {
      // Do nothing.
      if (logger.fineEnabled()) {
        logger.fine("initPRInternals: failed due to exception", rde);
      }
    }

    return true;
  }
  
  private void markRecoveredRecursively(PartitionedRegion region) {
    region.setRecoveredFromDisk();
    for(PartitionedRegion colocatedRegion : ColocationHelper.getColocatedChildRegions(region)) {
      markRecoveredRecursively(colocatedRegion);
    }
  }

  @Override
  protected void postCreateRegion() {
    super.postCreateRegion();
    
    CacheListener[] listeners = fetchCacheListenersField();
    if (listeners != null && listeners.length > 0) {
      Set others = getRegionAdvisor().adviseGeneric();
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
    
    PartitionListener[] partitionListeners = this.getPartitionListeners();
    if (partitionListeners != null && partitionListeners.length != 0) {
      for (int i = 0; i < partitionListeners.length; i++) {
        PartitionListener listener = partitionListeners[i];
        if (listener != null) {
          listener.afterRegionCreate(this);
        }
      }     
    }

    Set<String> allGatewaySenderIds = getAllGatewaySenderIds();
    if (!allGatewaySenderIds.isEmpty()) {
      for (GatewaySender sender : cache.getAllGatewaySenders()) {
        if (sender.isParallel()
            && allGatewaySenderIds.contains(sender.getId())) {
          /**
           * get the ParallelGatewaySender to create the colocated partitioned
           * region for this region.
           */
          if (sender.isRunning() ) {
            ParallelGatewaySenderImpl senderImpl = (ParallelGatewaySenderImpl)sender;
            ((ConcurrentParallelGatewaySenderQueue)senderImpl.getQueues().toArray(new RegionQueue[1])[0])
                .addShadowPartitionedRegionForUserPR(this);
          }
        }
      }
    }   
  }
  
  

  @Override
  public void preInitialize(InternalRegionArguments internalRegionArgs) {
    //Do nothing. PartitionedRegion doesn't manage
    //a version vector or a diskRegion.
  }

  /*
   * Initializes the PartitionedRegion. OVERRIDES
   */
  @Override
  protected void initialize(InputStream snapshotInputStream,
      InternalDistributedMember imageTarget,
      InternalRegionArguments internalRegionArgs) throws TimeoutException,
      ClassNotFoundException {
    LogWriterI18n logger = this.cache.getLoggerI18n();
    if (logger.fineEnabled()) {
      logger.fine("PartitionedRegion#initialize " + getName());
    }
    RegionLogger.logCreate(getName(), getDistributionManager().getDistributionManagerId());
    
    this.requiresNotification = this.cache.requiresNotificationFromPR(this);
    initPRInternals(internalRegionArgs);
    if (logger.fineEnabled()) {
      logger.fine(
        "PartitionRegion#initialize: finished with " + toString());
    }
    initilializeCustomEvictor();
    this.cache.addPartitionedRegion(this);
  }

  /**
   * Initializes the Node for this Map.
   */
  private Node initializeNode() {
    return new Node(getDistributionManager().getId(),
        SERIAL_NUMBER_GENERATOR.getAndIncrement());
  }

  /**
   * receive notification that a bridge server or wan gateway has been created
   * that requires notification of cache events from this region
   */
  public void cacheRequiresNotification() {
    if (!this.requiresNotification
        && !(this.isClosed || this.isLocallyDestroyed)) {
      // tell others of the change in status
      this.requiresNotification = true;
      new UpdateAttributesProcessor(this).distribute(false);
    }    
  }

  @Override
  void distributeUpdatedProfileOnHubCreation()
  {
    if (!(this.isClosed || this.isLocallyDestroyed)) {
      // tell others of the change in status
      this.requiresNotification = true;
      new UpdateAttributesProcessor(this).distribute(false);      
    }
  }
  
  @Override
  public void distributeUpdatedProfileOnSenderChange() {
    if (!(this.isClosed || this.isLocallyDestroyed)) {
      // tell others of the change in status
      this.requiresNotification = true;
      new UpdateAttributesProcessor(this).distribute(false);
    }
  }

  /**
   * Initializes the PartitionedRegion - create the Global regions for storing
   * the PartitiotnedRegion configs.
   */
  private void initializePartitionedRegion() {
    this.prRoot = PartitionedRegionHelper.getPRRoot(getCache());
  }

  @Override
  public void remoteRegionInitialized(CacheProfile profile) {
    if (isInitialized() && hasListener()) {
      Object callback = DistributedRegion.TEST_HOOK_ADD_PROFILE? profile : null;
      RegionEventImpl event = new RegionEventImpl(PartitionedRegion.this,
          Operation.REGION_CREATE, callback, true, profile.peerMemberId);
      dispatchListenerEvent(EnumListenerEvent.AFTER_REMOTE_REGION_CREATE,
            event);
    }
  }

  /**
   * This method initializes the partitionedRegionDataStore for this PR.
   * 
   * @param ra
   *                Region attributes
   */
  private void initializeDataStore(RegionAttributes ra) {

    this.dataStore = PartitionedRegionDataStore.createDataStore(cache, this, ra
        .getPartitionAttributes());
  }

  protected DistributedLockService getPartitionedRegionLockService() {
    return getGemFireCache().getPartitionedRegionLockService();
  }
  
  /**
   * Register this PartitionedRegion by: 1) Create a PartitionRegionConfig and
   * 2) Bind it into the allPartitionedRegion system wide Region.
   * 
   * @param storesData
   *                which indicates whether the instance in this cache stores
   *                data, effecting the Nodes PRType
   * 
   * @see Node#setPRType(int)
   */
  private void registerPartitionedRegion(boolean storesData) {
    // Register this ParitionedRegion. First check if the ParitionedRegion
    // entry already exists globally.
    PartitionRegionConfig prConfig = null;
    PartitionAttributes prAttribs = getAttributes().getPartitionAttributes();
    if (storesData) {
      if (this.fixedPAttrs != null) {
        this.node.setPRType(Node.FIXED_PR_DATASTORE);
      } else {
        this.node.setPRType(Node.ACCESSOR_DATASTORE);
      }
      this.node.setPersistence(getAttributes().getDataPolicy() == DataPolicy.PERSISTENT_PARTITION);
      byte loaderByte = (byte)(getAttributes().getCacheLoader() != null ? 0x01 : 0x00);
      byte writerByte = (byte)(getAttributes().getCacheWriter() != null ? 0x02 : 0x00);
      this.node.setLoaderWriterByte((byte)(loaderByte + writerByte));
    }
    else {
      if (this.fixedPAttrs != null) {
        this.node.setPRType(Node.FIXED_PR_ACCESSOR);
      } else {
        this.node.setPRType(Node.ACCESSOR);
      }
    }
    final RegionLock rl = getRegionLock();
    try {
      // if (!rl.lock()) {
      if (logger.fineEnabled()) {
        this.logger.fine("registerPartitionedRegion: obtaining lock");
      }
      rl.lock();
      checkReadiness();
      
      prConfig = this.prRoot.get(getRegionIdentifier());
      
      if (prConfig == null) {
        //validateParalleGatewaySenderIds();
        this.partitionedRegionId = generatePRId(getSystem());
        prConfig = new PartitionRegionConfig(this.partitionedRegionId,
            this.getFullPath(), prAttribs, this.getScope(),
            getAttributes().getEvictionAttributes(), 
            getAttributes().getRegionIdleTimeout(), 
            getAttributes().getRegionTimeToLive(), 
            getAttributes().getEntryIdleTimeout(),
            getAttributes().getEntryTimeToLive(),
            this.getAllGatewaySenderIds());
        if (logger.infoEnabled()) {
          logger.info(
              LocalizedStrings.PartitionedRegion_PARTITIONED_REGION_0_IS_BORN_WITH_PRID_1_IDENT_2,
              new Object[] { getFullPath(), Integer.valueOf(this.partitionedRegionId), getRegionIdentifier()});
        }

        PRSanityCheckMessage.schedule(this);
      }
      else {
        validator.validatePartitionAttrsFromPRConfig(prConfig);
        if (storesData) {
          validator.validatePersistentMatchBetweenDataStores(prConfig);
          validator.validateCacheLoaderWriterBetweenDataStores(prConfig);
          validator.validateFixedPABetweenDataStores(prConfig);
        }
        
        this.partitionedRegionId = prConfig.getPRId();
        if (logger.infoEnabled()) {
          logger.info(
              LocalizedStrings.PartitionedRegion_PARTITIONED_REGION_0_IS_CREATED_WITH_PRID_1,
              new Object[] { getFullPath(), Integer.valueOf(this.partitionedRegionId)});
        }
      }

      synchronized (prIdToPR) {
        prIdToPR.put(Integer.valueOf(this.partitionedRegionId), this); // last
      }
      prConfig.addNode(this.node);
      if (this.getFixedPartitionAttributesImpl() != null) {
        calculateStartingBucketIDs(prConfig);
      }
      updatePRConfig(prConfig, false);
      /*
       * try { if (this.redundantCopies > 0) { if (storesData) {
       * this.dataStore.grabBackupBuckets(false); } } } catch
       * (RegionDestroyedException rde) { if (!this.isClosed) throw rde; }
       */
      this.cleanPRRegistration = true;
    }
    catch (LockServiceDestroyedException lsde) {
      if (logger.fineEnabled()) {
        logger.fine("registerPartitionedRegion: unable to obtain lock for "
            + this);
      }
      cleanupFailedInitialization();
      throw new PartitionedRegionException(
          LocalizedStrings.PartitionedRegion_CAN_NOT_CREATE_PARTITIONEDREGION_FAILED_TO_ACQUIRE_REGIONLOCK
              .toLocalizedString(), lsde);
    }
    catch (IllegalStateException ill) {
      cleanupFailedInitialization();
      throw ill;
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
      String registerErrMsg = 
        LocalizedStrings.PartitionedRegion_AN_EXCEPTION_WAS_CAUGHT_WHILE_REGISTERING_PARTITIONEDREGION_0_DUMPPRID_1
        .toLocalizedString(new Object[] {getFullPath(), prIdToPR.dump()});
      try {
        synchronized (prIdToPR) {
          if (prIdToPR.containsKey(Integer.valueOf(this.partitionedRegionId))) {
            prIdToPR.put(Integer.valueOf(this.partitionedRegionId),
                PRIdMap.FAILED_REGISTRATION, false);
            if (logger.infoEnabled()) {
              logger.info(
                  LocalizedStrings.PartitionedRegion_FAILED_REGISTRATION_PRID_0_NAMED_1,
                  new Object[] {Integer.valueOf(this.partitionedRegionId), this.getName()});
            }
          }
        }
      }
      catch (Throwable ex) {
        if (ex instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)ex)) {
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
        if (logger.fineEnabled()) {
          logger.fine("Partitioned Region creation, could not clean up after "
              + "caught exception", ex);
        }
      }
      throw new PartitionedRegionException(registerErrMsg, t);
    }
    finally {
      try {
        rl.unlock();
        if (logger.fineEnabled()) {
          this.logger.fine("registerPartitionedRegion: released lock");
        }
      }
      catch (Exception es) {
        logger.warning(es);
      }
    }
  }

  /*
   * Gets the log writer.
   */
  @Override
  public LogWriterI18n getLogWriterI18n() {
    return this.logger;
  }

  /**
   * @return whether this region requires event notification for all cache
   *         content changes from other nodes
   */
  public boolean getRequiresNotification() {
    return this.requiresNotification;
  }

  /**
   * Get the Partitioned Region identifier used for DLocks (Bucket and Region)
   */
  final public String getRegionIdentifier() {
    return this.regionIdentifier;
  }
  
  void setRecoveredFromDisk() {
    this.recoveredFromDisk = true;
    new UpdateAttributesProcessor(this).distribute(false);
  }

  public final void updatePRConfig(PartitionRegionConfig prConfig,
      boolean putOnlyIfUpdated) {
    final Set<Node> nodes = prConfig.getNodes();
    final PartitionedRegion colocatedRegion = ColocationHelper
        .getColocatedRegion(this);
    RegionLock colocatedLock = null;
    boolean colocatedLockAcquired = false;
    try {
      boolean colocationComplete = false;
      if (colocatedRegion != null && !prConfig.isColocationComplete() &&
        // if the current node is marked uninitialized (GFXD DDL replay in
        // progress) then colocation will definitely not be marked complete so
        // avoid taking the expensive region lock
          !getCache().isUnInitializedMember(getDistributionManager().getId())) {
        colocatedLock = colocatedRegion.getRegionLock();
        colocatedLock.lock();
        colocatedLockAcquired = true;
        final PartitionRegionConfig parentConf = this.prRoot
            .get(colocatedRegion.getRegionIdentifier());
        if (parentConf.isColocationComplete()
            && parentConf.hasSameDataStoreMembers(prConfig)) {
          colocationComplete = true;
          // check if all the nodes have been initialized (GFXD bug #42089)
          for (Node node : nodes) {
            if (getCache().isUnInitializedMember(node.getMemberId())) {
              colocationComplete = false;
              break;
            }
          }
          if (colocationComplete) {
            prConfig.setColocationComplete();
          }
        }
      }

      if(isDataStore() && !prConfig.isFirstDataStoreCreated()) {
        prConfig.setDatastoreCreated(getEvictionAttributes());
      }
      // N.B.: this put could fail with a CacheClosedException:
      if (!putOnlyIfUpdated || colocationComplete) {
        this.prRoot.put(getRegionIdentifier(), prConfig);
      }
    } finally {
      if (colocatedLockAcquired) {
        colocatedLock.unlock();
      }
    }
  }

  private void updatePRConfigForWriterLoaderChange() {
    final Region<String, PartitionRegionConfig> prRoot = this.prRoot;
    if (prRoot == null) {
      return;
    }
    byte loaderByte = (byte)(getAttributes().getCacheLoader() != null ? 0x01
        : 0x00);
    byte writerByte = (byte)(getAttributes().getCacheWriter() != null ? 0x02
        : 0x00);
    byte value = (byte)(loaderByte + writerByte);
    synchronized (this.node) {
      if (value != this.node.cacheLoaderWriterByte) {
        final RegionLock rl = getRegionLock();
        if (logger.fineEnabled()) {
          this.logger
            .fine("updatePRConfigForWriterLoaderChange: obtaining lock");
        }
        rl.lock();
        try {
          checkReadiness();

          PartitionRegionConfig prConfig = prRoot.get(getRegionIdentifier());
          this.node.setLoaderWriterByte(value);
          if (prConfig != null && prConfig.removeNode(this.node)) {
            prConfig.addNode(this.node);
            updatePRConfig(prConfig, false);
          }
        } finally {
          rl.unlock();
        }
      }
    }
  }

  /**
   * 
   * @param keyInfo
   * @param access
   *          true if caller wants last accessed time updated
   * @param allowTombstones - whether a tombstone can be returned
   * @return TODO
   */
  @Override
  protected final Region.Entry<?, ?> txGetEntry(KeyInfo keyInfo,
      boolean access, final TXStateInterface tx, boolean allowTombstones) {
    final long startTime = PartitionedRegionStats.startTime();
    final Object key = keyInfo.getKey();
    try {
      int bucketId = keyInfo.getBucketId();
      if (bucketId == KeyInfo.UNKNOWN_BUCKET) {
        bucketId = PartitionedRegionHelper.getHashKey(this,
            Operation.GET_ENTRY, key, keyInfo.getValue(),
            keyInfo.getCallbackArg());
        keyInfo.setBucketId(bucketId);
      }
      InternalDistributedMember targetNode = getOrCreateNodeForBucketRead(bucketId);
      return getEntryInBucket(targetNode, bucketId, key, access, tx,
          allowTombstones);
    }
    finally {
      this.prStats.endGetEntry(startTime);
    }
  }

  @Override
  protected final Region.Entry<?, ?> txGetEntryForIterator(
      final KeyInfo keyInfo, final boolean access, final TXStateInterface tx,
      boolean allowTombstones) {
    return txGetEntry(keyInfo, access, tx, allowTombstones);
  }

  @Override
  protected final Region.Entry<?, ?> nonTXGetEntry(final Object key,
      final Object callbackArg, final boolean access, boolean allowTombstones) {
    final long startTime = PartitionedRegionStats.startTime();
    try {
      final int bucketId = PartitionedRegionHelper.getHashKey(this,
          Operation.GET_ENTRY, key, null, callbackArg);
      final InternalDistributedMember targetNode =
        getOrCreateNodeForBucketRead(bucketId);
      return getEntryInBucket(targetNode, bucketId, key, access, null,
          allowTombstones);
    } finally {
      this.prStats.endGetEntry(startTime);
    }
  }

  protected EntrySnapshot getEntryInBucket(final DistributedMember targetNode,
      final int bucketId, final Object key, boolean access,
      final TXStateInterface lockState, final boolean allowTombstones) {
    final int retryAttempts = calcRetry();
    if (logger.finerEnabled()) {
      logger.finer("getEntryInBucket: " + "Key key=" + key + " ("
          + key.hashCode() + ") from: " + targetNode + " bucketId="
          + bucketStringForLogs(bucketId));
    }
    Integer bucketIdInt = Integer.valueOf(bucketId);
    EntrySnapshot ret = null;
    int count = 0;
    RetryTimeKeeper retryTime = null;
    InternalDistributedMember retryNode = (InternalDistributedMember)targetNode;
    while (count <= retryAttempts) {
      // Every continuation should check for DM cancellation
      if (retryNode == null) {
        checkReadiness();
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        if (retryTime.overMaximum()) {
          break;
        }
        retryNode = getOrCreateNodeForBucketRead(bucketId);

        // No storage found for bucket, early out preventing hot loop, bug 36819
        if (retryNode == null) {
          checkShutdown();
          return null;
        }
        continue;
      }
      try {
        final boolean loc = (this.localMaxMemory != 0) && retryNode.equals(getMyId());
        if (loc) {
          ret = this.dataStore.getEntryLocally(bucketId, key, access,
              lockState, allowTombstones);
        }
        else {
          final TXStateInterface tx = lockState != null ? lockState
              : getTXState();
          ret = getEntryRemotely(retryNode, tx, bucketIdInt, key, access,
              allowTombstones);
          // TODO:Suranjan&Yogesh : there should be better way than this one
          String name = Thread.currentThread().getName();
          if (name.startsWith("ServerConnection")
              && !getMyId().equals(targetNode)) {
            setNetworkHop(bucketIdInt, (InternalDistributedMember)targetNode);
          }
        }

        return ret;
      }
      catch (PRLocallyDestroyedException pde) {
        if (logger.fineEnabled()) {
          logger
              .fine("getEntryInBucket: Encountered PRLocallyDestroyedException ");
        }
        checkReadiness();
      }
      catch (EntryNotFoundException enfe) {
        return null;
      }
      catch (ForceReattemptException prce) {
        prce.checkKey(key);
        if (logger.fineEnabled()) {
          logger.fine("getEntryInBucket: retrying, attempts so far:" + count 
              /* + " retryAttempts */, logger.finerEnabled() ? prce : null);
        }
        checkReadiness();
        InternalDistributedMember lastNode = retryNode;
        retryNode = getOrCreateNodeForBucketRead(bucketIdInt.intValue());
        if (lastNode.equals(retryNode)) {
          if (retryTime == null) {
            retryTime = new RetryTimeKeeper(this.retryTimeout);
          }
          if (retryTime.overMaximum()) {
            break;
          }
          retryTime.waitToRetryNode();
        }
      }
      catch (PrimaryBucketException notPrimary) {
        if (logger.fineEnabled()) {
          logger.fine("Bucket " + notPrimary.getLocalizedMessage()
              + " on Node " + retryNode + " not primary ");
        }
        getRegionAdvisor().notPrimary(bucketIdInt.intValue(), retryNode);
        retryNode = getOrCreateNodeForBucketRead(bucketIdInt.intValue());
      }

      // It's possible this is a GemFire thread e.g. ServerConnection
      // which got to this point because of a distributed system shutdown or
      // region closure which uses interrupt to break any sleep() or wait()
      // calls
      // e.g. waitForPrimary
      checkShutdown();

      count++;
      if (count == 1) {
        this.prStats.incContainsKeyValueOpsRetried();
      }
      this.prStats.incContainsKeyValueRetries();

    }

    PartitionedRegionDistributionException e = null; // Fix for bug 36014
    if (logger.fineEnabled()) {
      e = new PartitionedRegionDistributionException(LocalizedStrings.PartitionRegion_NO_VM_AVAILABLE_FOR_GETENTRY_IN_0_ATTEMPTS.toLocalizedString(Integer.valueOf(count)));
    }
    logger.warning(LocalizedStrings.PartitionRegion_NO_VM_AVAILABLE_FOR_GETENTRY_IN_0_ATTEMPTS, Integer.valueOf(count), e);
    return null;
  }

  /**
   * Check for region closure, region destruction, cache closure as well as
   * distributed system disconnect. As of 6/21/2007, there were at least four
   * volatile variables reads and one synchonrization performed upon completion
   * of this method.
   */
  private void checkShutdown() {
    checkReadiness();
    this.cache.getCancelCriterion().checkCancelInProgress(null);
  }

  /**
   * Checks if a key is contained remotely.
   * 
   * @param targetNode
   *          the node where bucket region for the key exists.
   * @param tx
   *                the TX state for current operation
   * @param bucketId
   *          the bucket id for the key.
   * @param key
   *          the key, whose value needs to be checks
   * @param access
   *          true if caller wants last access time updated
   * @param allowTombstones whether tombstones should be returned
   * @throws EntryNotFoundException
   *           if the entry doesn't exist
   * @throws ForceReattemptException
   *           if the peer is no longer available
   * @throws PrimaryBucketException
   * @return true if the passed key is contained remotely.
   */
  EntrySnapshot getEntryRemotely(InternalDistributedMember targetNode,
      TXStateInterface tx, Integer bucketId, Object key, boolean access,
      boolean allowTombstones) throws EntryNotFoundException,
      PrimaryBucketException, ForceReattemptException {
    FetchEntryResponse r = FetchEntryMessage
        .send(targetNode, this, tx, key, access);
    this.prStats.incPartitionMessagesSent();
    EntrySnapshot entry = r.waitForResponse();
    if (entry != null && entry.getRawValue() == Token.TOMBSTONE){
      if (!allowTombstones) {
        return null;
      }
    }
    return entry;
  }

  // /////////////////////////////////////////////////////////////////
  // Following methods would throw, operation Not Supported Exception
  // /////////////////////////////////////////////////////////////////

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  @Override
  public void becomeLockGrantor() {
    throw new UnsupportedOperationException();
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  @Override
  final public Region createSubregion(String subregionName,
      RegionAttributes regionAttributes) throws RegionExistsException,
      TimeoutException {
    throw new UnsupportedOperationException();
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  @Override
  public Lock getDistributedLock(Object key) throws IllegalStateException {
    throw new UnsupportedOperationException();
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  @Override
  public CacheStatistics getStatistics() {
    throw new UnsupportedOperationException();
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  public Region getSubregion() {
    throw new UnsupportedOperationException();
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  @Override
  public Lock getRegionDistributedLock() throws IllegalStateException {

    throw new UnsupportedOperationException();
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  @Override
  public void loadSnapshot(InputStream inputStream) throws IOException,
      ClassNotFoundException, CacheWriterException, TimeoutException {
    throw new UnsupportedOperationException();
  }

  /**
   * Should it destroy entry from local accessor????? 
   * OVERRIDES
   */
  @Override
  public void localDestroy(Object key, Object aCallbackArgument)
      throws EntryNotFoundException {

    throw new UnsupportedOperationException();
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  @Override
  public void localInvalidate(Object key, Object aCallbackArgument)
      throws EntryNotFoundException {

    throw new UnsupportedOperationException();
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  @Override
  public void localInvalidateRegion(Object aCallbackArgument) {
    getDataView().checkSupportsRegionInvalidate();
    throw new UnsupportedOperationException();
  }

  /**
   * Executes a query on this PartitionedRegion. The restrictions have already
   * been checked. The query is a SELECT expression, and the only region it
   * refers to is this region.
   * 
   * @see DefaultQuery#execute()
   * 
   * @since 5.1
   */
  public Object executeQuery(DefaultQuery query, Object[] parameters,
      Set buckets) throws FunctionDomainException, TypeMismatchException,
      NameResolutionException, QueryInvocationTargetException {
    for (;;) {
      try {
        return doExecuteQuery(query, parameters, buckets);
      } catch (ForceReattemptException fre) {
        // fall through and loop
      }
    }
  }
  /**
   * If ForceReattemptException is thrown then the caller must loop and call us again.
   * @throws ForceReattemptException if one of the buckets moved out from under us
   */
  private Object doExecuteQuery(DefaultQuery query, Object[] parameters,
      Set buckets)
  throws FunctionDomainException, TypeMismatchException,
  NameResolutionException, QueryInvocationTargetException,
  ForceReattemptException
  {
    if (this.logger.fineEnabled()) {
      this.logger.fine("Executing query :" + query);
    }

    HashSet<Integer> allBuckets = new HashSet<Integer>();
    
    if (buckets==null) { // remote buckets
      final Iterator remoteIter = getRegionAdvisor().getBucketSet().iterator();
      try {
        while (remoteIter.hasNext()) {
          allBuckets.add((Integer)remoteIter.next());
        }
      }
      catch (NoSuchElementException stop) {
      }
    }
    else { // local buckets
      Iterator localIter = null;
      if (this.dataStore != null) {
        localIter = buckets.iterator();
      }
      else {
        localIter = Collections.EMPTY_SET.iterator();
      }
      try {
        while (localIter.hasNext()) {
          allBuckets.add((Integer)localIter.next());        
        }
      }
      catch (NoSuchElementException stop) {
      }
    }

    if (allBuckets.size() == 0) {
      if (logger.fineEnabled()) {
        getLogWriterI18n().fine(
            "No bucket storage allocated. PR has no data yet.");
      }
      ResultsSet resSet = new ResultsSet();
      resSet.setElementType(new ObjectTypeImpl(
          this.getValueConstraint() == null ? Object.class : this
              .getValueConstraint()));
      return resSet;
    }

    CompiledSelect selectExpr = query.getSimpleSelect();
    if (selectExpr == null) {
      throw new IllegalArgumentException(
        LocalizedStrings.
          PartitionedRegion_QUERY_MUST_BE_A_SELECT_EXPRESSION_ONLY
            .toLocalizedString());
    }    

    // this can return a BAG even if it's a DISTINCT select expression,
    // since the expectation is that the duplicates will be removed at the end
    SelectResults results = selectExpr
        .getEmptyResultSet(parameters, getCache());

    PartitionedRegionQueryEvaluator prqe = new PartitionedRegionQueryEvaluator(this.getSystem(), this, query,
        parameters, results, allBuckets);
    for (;;) {
      this.getCancelCriterion().checkCancelInProgress(null);
      boolean interrupted = Thread.interrupted();
      try {
        prqe.queryBuckets(null);
        break;
      }
      catch (InterruptedException e) {
        interrupted = true;
      }
      catch (FunctionDomainException e) {
	throw e;
      }
      catch (TypeMismatchException e) {
	throw e;
      }
      catch (NameResolutionException e) {
	throw e;
      }
      catch (QueryInvocationTargetException e) {
	throw e;
      }
      catch (QueryException qe) {
        throw new QueryInvocationTargetException(LocalizedStrings.PartitionedRegion_UNEXPECTED_QUERY_EXCEPTION_OCCURED_DURING_QUERY_EXECUTION_0.toLocalizedString(qe.getMessage()), qe);
      }
      finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    } // for

    // Drop Duplicates if this is a DISTINCT query
    boolean allowsDuplicates = results.getCollectionType().allowsDuplicates();
    //Asif: No need to apply the limit to the SelectResults. 
    // We know that even if we do not apply the limit,
    //the results will satisfy the limit
    // as it has been evaluated in the iteration of List to 
    // populate the SelectsResuts     
    //So if the results is instance of ResultsBag or is a StructSet or 
    // a ResultsSet, if the limit exists, the data set size will 
    // be exactly matching the limit
    if (selectExpr.isDistinct()) {
      // don't just convert to a ResultsSet (or StructSet), since
      // the bags can convert themselves to a Set more efficiently
      ObjectType elementType = results.getCollectionType().getElementType();
      if (selectExpr.getOrderByAttrs() != null) {
        // Set limit also, its not applied while building the final result set as order by is involved.
        results = new ResultsCollectionWrapper(elementType, results.asSet(), query.getLimit(parameters));
      } else if (allowsDuplicates) {
        results = new ResultsCollectionWrapper(elementType, results.asSet());
      }
      if (selectExpr.isCount() && (results.isEmpty() || selectExpr.isDistinct())) {
        SelectResults resultCount = new ResultsBag(getCachePerfStats());//Constructor with elementType not visible.
        resultCount.setElementType(new ObjectTypeImpl(Integer.class));
        ((ResultsBag)resultCount).addAndGetOccurence(results.size());
        return resultCount;
      }
    }
    return results;
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  @Override
  public void saveSnapshot(OutputStream outputStream) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
  @Override
  public void writeToDisk() {
    throw new UnsupportedOperationException();
  }

  /**
   * @since 5.0
   * @throws UnsupportedOperationException
   * OVERRIDES
   */
 @Override
 public void clear() {
    throw new UnsupportedOperationException();
  }

  @Override
  void basicClear(RegionEventImpl regionEvent, boolean cacheWrite) {
    throw new UnsupportedOperationException();
  }

  @Override
  void basicLocalClear(RegionEventImpl event) {
    throw new UnsupportedOperationException();
  }

  // /////////////////////////////////////////////////////////////////////
  // ////////////// Operation Supported for this release
  // //////////////////////////////
  // /////////////////////////////////////////////////////////////////////

  @Override
  boolean virtualPut(EntryEventImpl event,
                     boolean ifNew,
                     boolean ifOld,
                     Object expectedOldValue,
                     boolean requireOldValue,
                     long lastModified,
                     boolean overwriteDestroyed)
  throws TimeoutException, CacheWriterException {
    final long startTime = PartitionedRegionStats.startTime();
    boolean result = false;
    final DistributedPutAllOperation putAllOp_save = event.setPutAllOperation(null);
    
    if (event.getEventId() == null) {
      event.setNewEventId(this.cache.getDistributedSystem());
    }
    boolean bucketStorageAssigned = true;
    try {
      final Integer bucketId = event.getBucketId();
      assert bucketId != KeyInfo.UNKNOWN_BUCKET;
      // check in bucket2Node region
      InternalDistributedMember targetNode = getNodeForBucketWrite(bucketId
          .intValue(), null);
      // force all values to be serialized early to make size computation cheap
      // and to optimize distribution.
      if (logger.fineEnabled() && event.getPutAllOperation() == null) {
        logger.fine("PR.virtualPut putting event=" + event);
      }

      if (targetNode == null) {
        try {
          bucketStorageAssigned=false;
          // if this is a Delta update, then throw exception since the key doesn't
          // exist if there is no bucket for it yet
          // For HDFS region, we will recover key, so allow bucket creation
          if (!this.dataPolicy.withHDFS() && event.hasDeltaPut()) {
            throw new EntryNotFoundException(LocalizedStrings.
              PartitionedRegion_CANNOT_APPLY_A_DELTA_WITHOUT_EXISTING_ENTRY
                .toLocalizedString());
          }
          targetNode = createBucket(bucketId.intValue(), event.getNewValSizeForPR(),
              null);
        }
        catch (PartitionedRegionStorageException e) {
          // try not to throw a PRSE if the cache is closing or this region was
          // destroyed during createBucket() (bug 36574)
          this.checkReadiness();
          if (this.cache.isClosed()) {
            throw new RegionDestroyedException(toString(), getFullPath());
          }
          throw e;
        }
      }

      if (event.isBridgeEvent() && bucketStorageAssigned) {
        setNetworkHop(bucketId, targetNode);
      }
      // logger.warning("virtualPut targetNode = " + targetNode
      // + "; ifNew = " + ifNew + "; ifOld = " + ifOld + "; bucketId = "
      // + bucketString(bucketId));
      if (putAllOp_save == null) {
        result = putInBucket(targetNode,
                           bucketId,
                           event,
                           ifNew,
                           ifOld,
                           expectedOldValue,
                           requireOldValue,
                           (ifNew ? 0L : lastModified));
        if (logger.fineEnabled()) {
          logger.fine("PR.virtualPut event=" + event + " ifNew=" + ifNew
              + " ifOld=" + ifOld + " result=" + result);
        }
      } else {
        checkIfAboveThreshold(event);   // fix for 40502
        // putAll: save the bucket id into DPAO, then wait for postPutAll to send msg
    	// at this time, DPAO's PutAllEntryData should be empty, we should add entry here with bucket id
        // the message will be packed in postPutAll, include the one to local bucket, because the buckets
        // could be changed at that time
    	putAllOp_save.addEntry(event, bucketId);
        if (logger.fineEnabled()) {
          logger.fine("PR.virtualPut PutAll added event=" + event + " into bucket " + bucketId);
        }
    	result = true;
      }
    }
    catch (RegionDestroyedException rde) {
      if (!rde.getRegionFullPath().equals(getFullPath())) {
        RegionDestroyedException rde2 = new RegionDestroyedException(toString(), getFullPath());
        rde2.initCause(rde);
        throw rde2;
      }
    }
    // catch (CacheWriterException cwe) {
    // logger.warning("cache writer exception escaping from virtualPut", cwe);
    // throw cwe;
    // }
    // catch (TimeoutException te) {
    // logger.warning("timeout exception escaping from virtualPut", te);
    // throw te;
    // }
    // catch (RuntimeException re) {
    // logger.warning("runtime exception escaping from virtualPut", re);
    // throw re;
    // }
    finally {
//      event.setPutAllOperation(putAllOp_save); // Gester: temporary fix
      if (putAllOp_save == null) {
        // only for normal put
        if (ifNew) {
          this.prStats.endCreate(startTime);
        }
        else {
          this.prStats.endPut(startTime);
        }
      }
    }
    if (!result) {
      checkReadiness();
      if (!ifNew && !ifOld && !this.concurrencyChecksEnabled) { // may fail due to concurrency conflict
        // failed for unknown reason
        //throw new PartitionedRegionStorageException("unable to execute operation");
        logger.warning(
            LocalizedStrings.PartitionedRegion_PRVIRTUALPUT_RETURNING_FALSE_WHEN_IFNEW_AND_IFOLD_ARE_BOTH_FALSE,
            new Exception(LocalizedStrings.PartitionedRegion_STACK_TRACE.toLocalizedString()));
      }
    }
    return result;
  }

  @Override
  public void performPutAllEntry(final EntryEventImpl event,
      final TXStateInterface tx, final long lastModifiedTime) {
    /*
     * force shared data view so that we just do the virtual op, accruing things
     * in the put all operation for later
     */
    getSharedDataView().putEntry(event, false, false, null, false, false, lastModifiedTime,
        false);
  }

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.LocalRegion#checkIfAboveThreshold(com.gemstone.gemfire.internal.cache.EntryEventImpl)
   */
  @Override
  public void checkIfAboveThreshold(EntryEventImpl evi)
      throws LowMemoryException {
    getRegionAdvisor().checkIfBucketSick(evi.getBucketId(), evi.getKey());
  }

  public boolean isFixedPartitionedRegion() {
    if (this.fixedPAttrs != null || this.fixedPASet == 1) {
      // We are sure that its a FixedFPA
      return true;
    }
    // We know that it's a normal PR
    if (this.fixedPASet == 2) {
      return false;
    }
    // Now is the case for accessor with fixedPAttrs null
    // and we don't know if it is a FPR
    // We will find out once and return that value whenever we have check again.
    this.fixedPASet = hasRemoteFPAttrs();
    return this.fixedPASet == 1;
  }

  private byte hasRemoteFPAttrs() {
    List<FixedPartitionAttributesImpl> fpaList = this.getRegionAdvisor()
        .adviseAllFixedPartitionAttributes();
    Set<InternalDistributedMember> remoteDataStores = this.getRegionAdvisor()
        .adviseDataStore();
    if (!fpaList.isEmpty() 
        || (this.fixedPAttrs != null && !this.fixedPAttrs.isEmpty()) ) {
      return 1;
    }
    if (isDataStore() || !remoteDataStores.isEmpty()) {
      return 2;
    }
    
    // This is an accessor and we need to wait for a datastore to see if there
    //are any fixed PRs.
    return 0;
  }
  
  
  @Override
  public void postPutAllFireEvents(DistributedPutAllOperation putallOp, VersionedObjectList successfulPuts) {
    /*
     * No op on pr, will happen in the buckets etc.
     */
  }

  
  /**
   * Create PutAllPRMsgs for each bucket, and send them. 
   * @param putallO
   *                DistributedPutAllOperation object.  
   * @param successfulPuts
   *                not used in PartitionedRegion. 
   */
  @Override
  public void postPutAllSend(final DistributedPutAllOperation putallO,
      final TXStateProxy txProxy, VersionedObjectList successfulPuts) {
    if (cache.isCacheAtShutdownAll()) {
      throw new CacheClosedException("Cache is shutting down");
    }

    final ArrayList<PutAllPRMessage.PutAllResponse> responses =
      new ArrayList<PutAllPRMessage.PutAllResponse>();
    try {
    final long startTime = PartitionedRegionStats.startTime();
    // build all the msgs by bucketid
    THashMap prMsgMap = putallO.createPRMessages();

    final PutAllPartialResult partialKeys = new PutAllPartialResult(
        putallO.putAllDataSize);

    // clear the successfulPuts list since we're actually doing the puts here
    // and the basicPutAll work was just a way to build the DPAO object
    THashMap keyToVersionMap = new THashMap(successfulPuts.size());
    successfulPuts.clearVersions();
    long then = -1;
    if (this.logger.fineEnabled()) {
      then = System.currentTimeMillis();
    }
    prMsgMap.forEachEntry(new TObjectObjectProcedure() {
      @Override
      public boolean execute(Object bid, Object msg) {
        PutAllPRMessage.PutAllResponse response = null;
        Integer bucketId = (Integer)bid;
        PutAllPRMessage prMsg = (PutAllPRMessage)msg;
        try {
          checkReadiness();
          response = sendMsgByBucket(bucketId, prMsg, txProxy);
          responses.add(response);
        } catch (RegionDestroyedException rde) {
          if (logger.fineEnabled()) {
            logger.fine("prMsg.send: caught RegionDestroyedException", rde);
          }
          throw new RegionDestroyedException(PartitionedRegion.this.toString(),
              getFullPath());
        } catch (Exception ex) {
          handleSendOrWaitException(ex, putallO, partialKeys, prMsg, txProxy);
        }
        return true;
      }
    });

    PutAllPRMessage prMsg = null;
    for (PutAllPRMessage.PutAllResponse resp : responses) {
      this.prStats.incPartitionMessagesSent();
      prMsg = resp.getPRMessage();
      try {
        VersionedObjectList versions = null;
        if (!resp.isLocal()) {
          PutAllPRMessage.PRMsgResponseContext ctx = resp.getContextObject();
          resp = sendOrWaitForRespWithRetry(ctx.getCurrTarget(),
              ctx.getCurrTargets(), ctx.getBucketId(), prMsg, txProxy, ctx.getEvent(),
              resp);
          versions = resp.getResult(this);
        }
        else {
          versions = resp.getPRMessage().getVersions();
        }

        if (versions.size() > 0) {
          partialKeys.addKeysAndVersions(versions);
          versions.saveVersions(keyToVersionMap);
        }
        // no keys returned if not versioned
        else if (txProxy != null || !this.concurrencyChecksEnabled) {
          Collection<Object> keys = prMsg.getKeys();
          partialKeys.addKeys(keys);
        }
        PutAllResponse remoteResponse = prMsg.getRemoteResponse();
        // This will be possible in the new transaction model. Just wait for
        // the result and move forward
        if (remoteResponse != null) {
          remoteResponse.waitForResult();
        }
      } catch (Exception ex) {
        handleSendOrWaitException(ex, putallO, partialKeys, prMsg, txProxy);
      }
    }

    if (this.logger.fineEnabled()) {
      long now = System.currentTimeMillis();
      if ((now - then) >= 10000) {
        logger.fine("PR.sendMsgByBucket took " + (now - then) + " ms");
      }
    }

    this.prStats.endPutAll(startTime);
    if (!keyToVersionMap.isEmpty()) {
      for (Iterator it=successfulPuts.getKeys().iterator(); it.hasNext(); ) {
        successfulPuts.addVersion((VersionTag)keyToVersionMap.get(it.next()));
      }
      keyToVersionMap.clear();
    }

    if (partialKeys.hasFailure()) {
      logger.info(LocalizedStrings.Region_PutAll_Applied_PartialKeys_0_1,
          new Object[] { getFullPath(), partialKeys });
      if (putallO.isBridgeOperation() || GemFireCacheImpl.gfxdSystem()) {
        if (partialKeys.getFailure() instanceof CancelException) {
          throw (CancelException)partialKeys.getFailure();
        }
        else {
          throw new PutAllPartialResultException(partialKeys);
        }
      }
      else {
        if (partialKeys.getFailure() instanceof RuntimeException) {
          throw (RuntimeException)partialKeys.getFailure();
        }
        else {
          throw new RuntimeException(partialKeys.getFailure());
        }
      }
    }

    } finally {
      for (PutAllPRMessage.PutAllResponse resp : responses) {
        PutAllPRMessage.PRMsgResponseContext ctx = resp.getContextObject();
        if (ctx != null) {
          EntryEventImpl e = ctx.getEvent();
          if (e != null) {
            e.release();
          }
        }
      }
    }
  }


  private volatile Boolean columnBatching;
  private volatile Boolean columnStoreTable;
  public boolean needsBatching() {
    final Boolean columnBatching = this.columnBatching;
    if (columnBatching != null) {
      return columnBatching;
    }
    // Find all the child region and see if they anyone of them has name ending
    // with _SHADOW_
    if (this.getName().toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX)) {
      this.columnBatching = false;
      return false;
    } else {
      boolean needsBatching = false;
      List<PartitionedRegion> childRegions = ColocationHelper.getColocatedChildRegions(this);
      for (PartitionedRegion pr : childRegions) {
        needsBatching |= pr.getName().toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX);
      }
      this.columnBatching = needsBatching;
      return needsBatching;
    }
  }

  public boolean columnTable() {
    final Boolean columnTable = this.columnStoreTable;
    if (columnTable != null) {
      return columnTable;
    }
    // Find all the child region and see if they anyone of them has name ending
    // with _SHADOW_
    if (this.getName().toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX)) {
      this.columnStoreTable = true;
      return true;
    } else {
      this.columnStoreTable = false;
    }
    return false;
  }

  private void handleSendOrWaitException(Exception ex,
      DistributedPutAllOperation putallO, PutAllPartialResult partialKeys,
      PutAllPRMessage prMsg, TXStateProxy txProxy) {
    if (txProxy != null) {
      txProxy.markTXInconsistent(ex);
      if (ex instanceof TransactionException) {
        throw (TransactionException)ex;
      }
      throw new TransactionException("exception in postPutAllSend for op: "
          + putallO, ex);
    }
    EntryEventImpl firstEvent = prMsg.getFirstEvent(this);
    try {
      if (ex instanceof PutAllPartialResultException) {
        PutAllPartialResultException pre = (PutAllPartialResultException)ex;
        // sendMsgByBucket applied partial keys
        if (this.logger.fineEnabled()) {
          logger.fine("PR.postPutAll encountered "
              + "PutAllPartialResultException", pre);
        }
        partialKeys.consolidate(pre.getResult());
      }
      else {
        // If failed at other exception
        if (this.logger.fineEnabled()) {
          logger.fine("PR.postPutAll encountered exception at "
              + "sendMsgByBucket", ex);
        }
        partialKeys.saveFailedKey(firstEvent.getKey(), ex);
      }
    } finally {
      firstEvent.release();
    }
  }

//  public void postPutAllSend_old(DistributedPutAllOperation putallO,
//      TXStateProxy txProxy, VersionedObjectList successfulPuts) {
//    if (cache.isCacheAtShutdownAll()) {
//      throw new CacheClosedException("Cache is shutting down");
//    }
//
//    final long startTime = PartitionedRegionStats.startTime();
//    // build all the msgs by bucketid
//    HashMap prMsgMap = putallO.createPRMessages();
//    PutAllPartialResult partialKeys = new PutAllPartialResult(putallO.putAllDataSize);
//    
//    // clear the successfulPuts list since we're actually doing the puts here
//    // and the basicPutAll work was just a way to build the DPAO object
//    successfulPuts.clear();
//    Iterator itor = prMsgMap.entrySet().iterator();
//    while (itor.hasNext()) {
//      Map.Entry mapEntry = (Map.Entry)itor.next();
//      Integer bucketId = (Integer)mapEntry.getKey();
//      PutAllPRMessage prMsg =(PutAllPRMessage)mapEntry.getValue();
//      checkReadiness();
//      long then = -1;
//      if (this.logger.fineEnabled()) {
//        then = System.currentTimeMillis();
//      }
//      try {
//        PutAllPRMessage.PutAllResponse response = sendMsgByBucket(bucketId, prMsg, txProxy);
//        if (versions.size() > 0) {
//          partialKeys.addKeysAndVersions(versions);
//          successfulPuts.addAll(versions); // fill in the version info
//        } else if (!this.concurrencyChecksEnabled) { // no keys returned if not versioned
//          Set keys = prMsg.getKeys();
//          partialKeys.addKeys(keys);
//          successfulPuts.addAllKeys(keys);
//        }
//      } catch (PutAllPartialResultException pre) {
//        if (txProxy != null) {
//          txProxy.markTXInconsistent(pre);
//          throw new TransactionException("exception in postPutAllSend for op: "
//              + putallO, pre);
//        }
//        // sendMsgByBucket applied partial keys
//        if (this.logger.fineEnabled()) {
//          logger.fine("PR.postPutAll encountered "
//              + "PutAllPartialResultException", pre);
//        }
//        partialKeys.consolidate(pre.getResult());
//      } catch (Exception ex) {
//        if (txProxy != null) {
//          txProxy.markTXInconsistent(ex);
//          if (ex instanceof TransactionException) {
//            throw (TransactionException)ex;
//          }
//          throw new TransactionException("exception in postPutAllSend for op: "
//              + putallO, ex);
//        }
//        // If failed at other exception
//        if (this.logger.fineEnabled()) {
//          logger.fine("PR.postPutAll encountered exception at "
//              + "sendMsgByBucket", ex);
//        }
//        partialKeys.saveFailedKey(prMsg.getFirstEvent(this).getKey(), ex);
//      }
//      if (this.logger.fineEnabled()) {
//        long now = System.currentTimeMillis();
//        if ((now - then) >= 10000) {
//          logger.fine("PR.sendMsgByBucket took " + (now - then) + " ms");
//        }
//      }
//    }
//    this.prStats.endPutAll(startTime);
//
//    if (partialKeys.hasFailure()) {
//      logger.info(LocalizedStrings.Region_PutAll_Applied_PartialKeys_0_1,
//          new Object[] {getFullPath(), partialKeys});
//      if (putallO.isBridgeOperation()) {
//        if (partialKeys.getFailure() instanceof CancelException) {
//          throw (CancelException)partialKeys.getFailure(); 
//        } else {
//          throw new PutAllPartialResultException(partialKeys);
//        }
//      } else {
//        if (partialKeys.getFailure() instanceof RuntimeException) {
//          throw (RuntimeException)partialKeys.getFailure();
//        } else {
//          throw new RuntimeException(partialKeys.getFailure());
//        }
//      }
//    } 
//  } 
  
  @SuppressWarnings("unchecked")
  private Set<InternalDistributedMember> getAllBucketOwners(int bucketId,
      TXStateProxy txProxy, RetryTimeKeeper retryTime, EntryEventImpl event) {
    if (txProxy != null) {
      final RegionAdvisor ra = getRegionAdvisor();
      ProxyBucketRegion pbr = ra.getProxyBucketArray()[bucketId];
      PartitionedRegionHelper.initializeBucket(this, pbr, true);
      txProxy.addAffectedRegion(pbr);
      Set<InternalDistributedMember> others = pbr.getBucketAdvisor()
          .adviseCacheOp();
      if (pbr.getCreatedBucketRegion() != null) {
        if (!others.isEmpty()) {
          others.add(getDistributionManager().getId());
        }
        else {
          THashSet singleSet = new THashSet(3);
          singleSet.add(getDistributionManager().getId());
          return singleSet;
        }
      }
      return others;
    }
    InternalDistributedMember currentTarget = null;
    if (retryTime == null) {
      currentTarget = getNodeForBucketWrite(bucketId, null);
      THashSet set = new THashSet();
      set.add(currentTarget);
      return set;
    }
    else {
      if (event != null) {
        currentTarget = waitForNodeOrCreateBucket(retryTime, event, bucketId);
      }
      else {
        currentTarget = getNodeForBucketWrite(bucketId, retryTime);
      }
    }
    THashSet set = new THashSet();
    set.add(currentTarget);
    return set;
  }
  
  /* If failed after retries, it will throw PartitionedRegionStorageException, no need for return value */
  private PutAllPRMessage.PutAllResponse sendMsgByBucket(final Integer bucketId,
      PutAllPRMessage prMsg, TXStateProxy txProxy) {

    // retry the put remotely until it finds the right node managing the bucket
    EntryEventImpl event = prMsg.getFirstEvent(this);
    //InternalDistributedMember currentTarget = getNodeForBucketWrite(bucketId.intValue(), null);
    InternalDistributedMember currentTarget = null;
    Set<InternalDistributedMember> currentTargets = null;
    if(txProxy == null) {
      currentTarget = getNodeForBucketWrite(bucketId.intValue(), null);
      addAffectedRegionForSnapshotIsolation(prMsg, bucketId);
    }
    else {
      currentTargets = getAllBucketOwners(bucketId, txProxy, null, null);
    }
    if (logger.fineEnabled()) {
      if (txProxy == null) {
        logger.fine("PR.sendMsgByBucket:bucket "+ bucketId + "'s currentTarget is "+
                currentTarget);
      } else {
        logger.fine("PR.sendMsgByBucket:bucket "+bucketId+"'s currentTargets are " +
                currentTargets);
      }
    }

    return sendOrWaitForRespWithRetry(currentTarget, currentTargets, bucketId,
        prMsg, txProxy, event, null);
    // event.freeOffHeapResources called by sendOrWaitForRespWithRetry.
  }

  private void addAffectedRegionForSnapshotIsolation(PutAllPRMessage prMsg, int bucketId) {
    if (prMsg.getTXState() != null && prMsg.getLockingPolicy() == LockingPolicy.SNAPSHOT) {
      final RegionAdvisor ra = getRegionAdvisor();
      ProxyBucketRegion pbr = ra.getProxyBucketArray()[bucketId];
      prMsg.getTXState().getProxy().addAffectedRegion(pbr);
    }
  }

  private PutAllPRMessage.PutAllResponse sendOrWaitForRespWithRetry(
      InternalDistributedMember currentTarget,
      Set<InternalDistributedMember> currentTargets, final Integer bucketId,
      PutAllPRMessage prMsg, TXStateProxy txProxy, EntryEventImpl event,
      PutAllPRMessage.PutAllResponse response) {
    try {
    final boolean isResponseCall = response != null;
    RetryTimeKeeper retryTime = null;

    long timeOut = 0;
    int count = 0;
    for (;;) {
      switch (count) {
        case 0:
          // Note we don't check for DM cancellation in common case.
          // First time.  Assume success, keep going.
          break;
        case 1:
          this.cache.getCancelCriterion().checkCancelInProgress(null);
          // Second time (first failure).  Calculate timeout and keep going.
          timeOut = System.currentTimeMillis() + this.retryTimeout;
          break;
        default:
          this.cache.getCancelCriterion().checkCancelInProgress(null);
          // test for timeout
          long timeLeft = timeOut - System.currentTimeMillis();
          if (timeLeft < 0) {
            PRHARedundancyProvider.timedOut(this, null, null, "update an entry", this.retryTimeout);
            // NOTREACHED
          }

          // Didn't time out.  Sleep a bit and then continue
          boolean interrupted = Thread.interrupted();
          try {
            Thread.sleep(PartitionedRegionHelper.DEFAULT_WAIT_PER_RETRY_ITERATION);
          }
          catch (InterruptedException e) {
            interrupted = true;
          }
          finally {
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
          }
          break;
      } // switch
      count ++;

      boolean doFindTargets = false;
      if (txProxy == null) {
        if (currentTarget == null) {
          doFindTargets = true;
        }
      }
      else {
        if(currentTargets == null
          || currentTargets.isEmpty()) {
          doFindTargets = true;
        }
      }
      if (doFindTargets) { // pick targets
        checkReadiness();
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }

        if (txProxy == null) {
          currentTarget = waitForNodeOrCreateBucket(retryTime, event, bucketId);
          addAffectedRegionForSnapshotIsolation(prMsg, bucketId);
        }
        else {
          currentTargets = getAllBucketOwners(bucketId, txProxy, retryTime, event);
        }
        if (logger.fineEnabled()) {
          if (txProxy == null) {
            logger.fine("PR.sendOrWaitForRespWithRetry: event size is "
                + getEntrySize(event) + ", " + "new currentTarget is "
                + currentTarget);
          }
          else {
            logger.fine("PR.sendOrWaitForRespWithRetry: event size is "
                + getEntrySize(event) + ", " + "new currentTargets are "
                + currentTargets);
          }
        }

        // It's possible this is a GemFire thread e.g. ServerConnection 
        // which got to this point because of a distributed system shutdown or 
        // region closure which uses interrupt to break any sleep() or wait() calls
        // e.g. waitForPrimary or waitForBucketRecovery in which case throw exception
        checkShutdown();
        continue;
      } // pick target

      if (count > 1 && isResponseCall) {
        prMsg = response.getPRMessage();
      }
      
      PutAllPRMessage.PutAllResponse responseOfTry = null;
      try {
        if (count > 1 || !isResponseCall) { // need to send PR message
          if (txProxy == null) {
            responseOfTry = tryToSendOnePutAllMessage(prMsg,
                Collections.singleton(currentTarget), txProxy);
          }
          else {
            responseOfTry = tryToSendOnePutAllMessage(prMsg, currentTargets,
                txProxy);
          }
          if (!isResponseCall) {
            responseOfTry
            .setContextObject(new PutAllPRMessage.PRMsgResponseContext(
                currentTarget, currentTargets, bucketId, event));
            event = null; // event now belongs to responseOfTry context.
            return responseOfTry;
          }
        }

        if (isResponseCall) { // waiting for response
          if (count > 1) {
            response = responseOfTry;
            // nothing more to do for local execution
            if (response.isLocal()) {
              return response;
            }
          }
          response.waitForResult();
          // No need to set context here as wait for result has ended without
          // any exception so this context object will never be used. But setting
          // it so that if some logging is done later based on this object then
          // we have the right information.
          response.setContextObject(new PutAllPRMessage.PRMsgResponseContext(
              currentTarget, currentTargets, bucketId, null));
          return response;
        }
      } catch (RegionDestroyedException rde) {
        if (logger.fineEnabled()) {
          logger.fine("prMsg.send: caught RegionDestroyedException", rde);
        }
        throw new RegionDestroyedException(toString(), getFullPath());
      } catch (CacheException ce) {
        if (ce instanceof TransactionException) {
          throw (TransactionException)ce;
        }
        // Fix for bug 36014
        throw new PartitionedRegionDistributionException("prMsg.send failed", ce);
      }
      catch (ForceReattemptException prce) {
        checkForTransactionAndBreak(prce, bucketId, txProxy);
        checkReadiness();

        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }

        Object lastTarget, currTarget;
        if (txProxy == null) {
          lastTarget = currentTarget;
          currentTarget = getNodeForBucketWrite(bucketId.intValue(), retryTime);
          currTarget = currentTarget;
          addAffectedRegionForSnapshotIsolation(prMsg, bucketId);
        }
        else {
          lastTarget = currentTargets;
          currentTargets = getAllBucketOwners(bucketId, txProxy, retryTime,
              null);
          currTarget = currentTargets;
        }

        logger.finer("PR.sendOrWaitForRespWithRetry: Old target was " + lastTarget
            + ", Retrying " + currTarget);
        if (currTarget != null && currTarget.equals(lastTarget)) {
          logger.fine("PR.sendOrWaitForRespWithRetry: Retrying at the same node:"
              + currTarget + " " + "due to " + prce.getMessage());
          if (retryTime.overMaximum()) {
            PRHARedundancyProvider.timedOut(this, null, null,
                "update an entry", this.retryTimeout);
            // NOTREACHED
          }
          retryTime.waitToRetryNode();
        }
        event.setPossibleDuplicate(true);
        if (prMsg != null) {
          prMsg.setPossibleDuplicate(true);
        }
      }
      catch (PrimaryBucketException notPrimary) {
        checkForTransactionAndBreak(notPrimary, bucketId, txProxy);
        if (logger.fineEnabled()) {
          logger.fine("Bucket " + notPrimary.getLocalizedMessage() 
              + " on Node " + currentTarget + " not primary ");
        }
        getRegionAdvisor().notPrimary(bucketId.intValue(), currentTarget);
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        
        if (txProxy == null) {
          currentTarget = getNodeForBucketWrite(bucketId.intValue(), retryTime);
          addAffectedRegionForSnapshotIsolation(prMsg, bucketId);
        }
        else {
          currentTargets = getAllBucketOwners(bucketId, txProxy, retryTime,
              null);
        }
      }
      catch (DataLocationException dle) {
        checkForTransactionAndBreak(dle, bucketId, txProxy);
        if (logger.fineEnabled()) {
          logger.fine("DataLocationException processing putAll",dle);
        }
        throw new TransactionException(dle);
      }

      // It's possible this is a GemFire thread e.g. ServerConnection
      // which got to this point because of a distributed system shutdown or
      // region closure which uses interrupt to break any sleep() or wait()
      // calls
      // e.g. waitForPrimary or waitForBucketRecovery in which case throw
      // exception
      checkShutdown();
      
      // If we get here, the attempt failed...
      if (count == 1) {
        this.prStats.incPutAllMsgsRetried();
      }
      this.prStats.incPutAllRetries();
    } // for
    } finally {
      if (event != null) {
        event.release();
      }
    }
    // NOTREACHED
  }

  private boolean checkForTransactionAndBreak(Exception ex, int bucketId,
      TXStateProxy txProxy) {
    if (txProxy != null) {
      InternalDistributedMember mem = getNodeForBucketRead(bucketId);
      if (mem != null) {
        // in the new txnal implementation as long as a single copy is there
        // we are fine. So ignore the exception
        return true;
      }
      else {
        throw new TransactionDataNodeHasDepartedException(
            "no copy for bucket id: " + bucketId + " in pr: " + this + " left");
      }
    }
    return false;
  }

  private PutAllPRMessage.PutAllResponse tryToSendOnePutAllMessage(
      PutAllPRMessage prMsg, final Set currentTargets,
      final TXStateProxy txProxy) throws DataLocationException {
    PutAllPRMessage.PutAllResponse response = null;
    prMsg.setRemoteResponse(null);
    final InternalDistributedMember self = getMyId();
    final boolean isLocalAlso = (this.localMaxMemory != 0)
        && currentTargets.contains(self);
    final boolean hasRemote = !isLocalAlso || currentTargets.size() > 1;

    // first do remote sends if required
    if (hasRemote) {
      if (isLocalAlso) {
        currentTargets.remove(self);
      }
      response = (PutAllPRMessage.PutAllResponse)prMsg.send(currentTargets,
          this);
    }
    if (isLocalAlso) { // local
      // It might throw retry exception when one key failed
      // InternalDS has to be set for each msg
      prMsg.initMessage(this, null, false, null);
      prMsg.doLocalPutAll(this, self, 0L);
      if (response != null) {
        prMsg.setRemoteResponse(response);
      }
      response = new PutAllPRMessage.PutAllResponseFromLocal(prMsg);
    }

    return response;
  }

  /*
  private VersionedObjectList tryToSendOnePutAllMessage_old(PutAllPRMessage prMsg,
      final Set currentTargets, final TXStateProxy txProxy)
      throws DataLocationException {

    boolean putResult = false;
    VersionedObjectList versions = null;
    final InternalDistributedMember self = getMyId();
    final boolean isLocalAlso = (this.localMaxMemory > 0)
      && currentTargets.contains(self);
    if (isLocalAlso) { // local
      // It might throw retry exception when one key failed
      // InternalDS has to be set for each msg
      prMsg.initMessage(this, null, false, null);
      putResult = prMsg.doLocalPutAll(this, self, 0L);
      versions = prMsg.getVersions();
    }

    if (putResult || !isLocalAlso) {
      if (currentTargets.size() > 1
          || (!isLocalAlso && currentTargets.size() > 0)) {
        if (isLocalAlso) {
          currentTargets.remove(self);
        }
        PutAllPRMessage.PutAllResponse response =
          (PutAllPRMessage.PutAllResponse)prMsg.send(currentTargets, this);
        PutAllPRMessage.PutAllResult pr = null;
        if (response != null) {
          this.prStats.incPartitionMessagesSent();
          try {
            response.waitForResult();
            pr = response.getResult();
            putResult = pr.returnValue;
            versions = pr.versions;
          } catch (RegionDestroyedException rde) {
            if (logger.fineEnabled()) {
              logger.fine("prMsg.send: caught RegionDestroyedException", rde);
            }
            throw new RegionDestroyedException(toString(), getFullPath());
          } catch (CacheException ce) {
            if (ce instanceof TransactionException) {
              throw (TransactionException)ce;
            }
            // Fix for bug 36014
            throw new PartitionedRegionDistributionException("prMsg.send on "
                + currentTargets + " failed", ce);
          }
        }
        else {
          putResult = true; // follow the same behavior of putRemotely()
        }
      }
    }

    if (!putResult) {
      // retry exception when msg failed in waitForResult()  
      ForceReattemptException fre = new ForceReattemptException(
          "false result in PutAllMessage.send - retrying");
      fre.setHash(0);
      throw fre;
    }
    return versions;
  }
  */

/**
   * Performs the {@link Operation#UPDATE put} operation in the bucket on the
   * remote VM and invokes "put" callbacks
   * 
   * @param targetNode
   *          the VM in whose {@link PartitionedRegionDataStore} contains the
   *          bucket
   * @param bucketId
   *                the identity of the bucket
   * @param event
   *                the event that contains the key and value
   * @param lastModified
   *                the timestamp that the entry was modified
   * @param ifNew
   *                true=can create a new key false=can't create a new key
   * @param ifOld
   *                true=can update existing entry false=can't update existing
   *                entry
   * @param expectedOldValue
   *          only succeed if old value is equal to this value. If null,
   *          then doesn't matter what old value is. If INVALID token,
   *          must be INVALID.
   * @see LocalRegion#virtualPut(EntryEventImpl, boolean, boolean, Object, boolean, long,
   *      boolean)
   * @return false if ifNew is true and there is an existing key, or ifOld is
   *         true and there is no existing entry; otherwise return true.
   * 
   */
  private boolean putInBucket(final InternalDistributedMember targetNode, 
                              final Integer bucketId,
                              final EntryEventImpl event,
                              final boolean ifNew,
                              boolean ifOld,
                              Object expectedOldValue,
                              boolean requireOldValue,
                              final long lastModified) {
    if (logger.fineEnabled()) {
      logger.fine("putInBucket: " + event.getKey() + " ("
          + event.getKey().hashCode() + ") to " + targetNode + " to bucketId="
          + bucketStringForLogs(bucketId.intValue()) 
          + " retry=" + retryTimeout + " ms");
    }
    // retry the put remotely until it finds the right node managing the bucket

    RetryTimeKeeper retryTime = null;
    boolean result = false;
    InternalDistributedMember currentTarget = targetNode;
    long timeOut = 0;
    int count = 0;
    int rdeEx = 0;
    for (;;) {
      switch (count) {
      case 0:
        // Note we don't check for DM cancellation in common case.
        // First time.  Assume success, keep going.
        break;
      case 1:
        this.cache.getCancelCriterion().checkCancelInProgress(null);
        // Second time (first failure).  Calculate timeout and keep going.
        timeOut = System.currentTimeMillis() + this.retryTimeout;
        break;
      default:
        this.cache.getCancelCriterion().checkCancelInProgress(null);
        // test for timeout
        long timeLeft = timeOut - System.currentTimeMillis();
        if (timeLeft < 0) {
          PRHARedundancyProvider.timedOut(this, null, null, "update an entry", this.retryTimeout);
          // NOTREACHED
        }

        // Didn't time out.  Sleep a bit and then continue
        boolean interrupted = Thread.interrupted();
        try {
          Thread.sleep(PartitionedRegionHelper.DEFAULT_WAIT_PER_RETRY_ITERATION);
        }
        catch (InterruptedException e) {
          interrupted = true;
        }
        finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
        break;
      } // switch
      count ++;
      
      if (currentTarget == null) { // pick target
        checkReadiness();
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        currentTarget = waitForNodeOrCreateBucket(retryTime, event, bucketId);

        // It's possible this is a GemFire thread e.g. ServerConnection 
        // which got to this point because of a distributed system shutdown or 
        // region closure which uses interrupt to break any sleep() or wait() calls
        // e.g. waitForPrimary or waitForBucketRecovery in which case throw exception
        checkShutdown();
        continue;
      } // pick target
      
      try {
        final boolean isLocal = (this.localMaxMemory != 0) && currentTarget.equals(getMyId());
        if (logger.fineEnabled()) {
          logger.fine("putInBucket: currentTarget = " + currentTarget 
              + "; ifNew = " + ifNew + "; ifOld = " + ifOld 
              + "; isLocal = " + isLocal);
        }
        
        checkIfAboveThreshold(event);
        if (isLocal) {
//          final boolean cacheWrite = !event.isOriginRemote()
//              && !event.isNetSearch();
//          if (cacheWrite) {
//            doCacheWriteBeforePut(event, ifNew);
//          }
          event.setInvokePRCallbacks(true);
          long start = this.prStats.startPutLocal();
          try {   
            final BucketRegion br = this.dataStore.getInitializedBucketForId(event.getKey(), bucketId);
            // Local updates should insert a serialized (aka CacheDeserializable) object
            // given that most manipulation of values is remote (requiring serialization to send).
            // But... function execution always implies local manipulation of
            // values so keeping locally updated values in Object form should be more efficient.
            if (! DistributionManager.isFunctionExecutionThread.get().booleanValue()) {
              // TODO: this condition may not help since BucketRegion.virtualPut calls forceSerialized
              br.forceSerialized(event);
            }
          if (ifNew) {
//            logger.fine("putInBucket: calling this.dataStore.createLocally for event " +  event);
            result = this.dataStore.createLocally(br,
                                                  event,
                                                  ifNew,
                                                  ifOld,
                                                  requireOldValue,
                                                  lastModified);
          }
          else {
//            logger.fine("putInBucket: calling this.dataStore.putLocally for event " + event);
            result = this.dataStore.putLocally(br,
                                               event,
                                               ifNew, 
                                               ifOld,
                                               expectedOldValue,
                                               requireOldValue,
                                               lastModified);
          }
          } finally {
            this.prStats.endPutLocal(start);
          }
        } // local
        else { // remote
          // no need to perform early serialization (and create an un-necessary byte array)
          // sending the message performs that work.
          long start = this.prStats.startPutRemote();
          try {
          if (ifNew) {
//            logger.fine("putInBucket: calling createRemotely for event " + event);
            result = createRemotely(currentTarget,
                                    bucketId,
                                    event,
                                    requireOldValue);
          }
          else {
//            logger.fine("putInBucket: calling putRemotely for event " + event);
            result = putRemotely(currentTarget,
                                 event,
                                 ifNew,
                                 ifOld,
                                 expectedOldValue,
                                 requireOldValue,
                                 false);
            if (!requireOldValue) {
              // make sure old value is set to NOT_AVAILABLE token
              event.oldValueNotAvailable();
            }
          }
          } finally {
            this.prStats.endPutRemote(start);
          }
        } // remote

        if (!result && !ifOld && !ifNew) {
          Assert.assertTrue(!isLocal);
          ForceReattemptException fre = new ForceReattemptException(LocalizedStrings.PartitionedRegion_FALSE_RESULT_WHEN_IFNEW_AND_IFOLD_IS_UNACCEPTABLE_RETRYING.toLocalizedString());
          fre.setHash(event.getKey().hashCode());
          throw fre;
        }

        return result;
      } catch (ConcurrentCacheModificationException e) {
        if (logger.fineEnabled()) {
          logger.fine("putInBucket: caught concurrent cache modification exception", e);
        }
        event.isConcurrencyConflict(true);

        if (logger.finerEnabled()) {
          logger
              .finer("ConcurrentCacheModificationException received for putInBucket for bucketId: "
                  + bucketStringForLogs(bucketId.intValue())
                  + " for event: "
                  + event + " No reattampt is done, returning from here");
        }
        return result;
      }
      catch (ForceReattemptException prce) {
        prce.checkKey(event.getKey());
        if (logger.fineEnabled()) {
          logger.fine("putInBucket: " + "Got ForceReattemptException for "
              + this + " on VM " + this.getMyId() + " for node "
              + currentTarget + " for bucket = "
              + bucketStringForLogs(bucketId.intValue()), prce);
        }

//        logger.warning(LocalizedStrings.DEBUG, "putInBucket: " + "Got ForceReattemptException for "
//            + this + " on VM " + this.getMyId() + " for node "
//            + currentTarget + " for bucket = "
//            + bucketStringForLogs(bucketId.intValue()), prce);
//        
//        logger.warning(LocalizedStrings.DEBUG, "putInBucket: count=" + count);
//        if (logger.fineEnabled()) {
//          logger.fine("putInBucket: count=" + count);
//        }
        RegionDestroyedException rde = null;
        if (( rde = isCauseRegionDestroyedException(prce)) != null) {
          rdeEx++;
          if (rdeEx > 200) {
            throw rde;
          }
        }
        checkReadiness();
        InternalDistributedMember lastTarget = currentTarget;
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        currentTarget = getNodeForBucketWrite(bucketId.intValue(), retryTime);
        if (lastTarget.equals(currentTarget)) {
//          if (logger.fineEnabled()) {
//            logger.fine("putInBucket: getNodeForBucketWrite returned same node.");
//          }
          if (retryTime.overMaximum()) {
            PRHARedundancyProvider.timedOut(this, null, null, "update an entry", this.retryTimeout);
            // NOTREACHED
          }
          retryTime.waitToRetryNode();
        }
        event.setPossibleDuplicate(true);
      }
      catch (PrimaryBucketException notPrimary) {
        if (logger.fineEnabled()) {
          logger.fine("Bucket " + notPrimary.getLocalizedMessage() 
              + " on Node " + currentTarget + " not primary ");
        }
        getRegionAdvisor().notPrimary(bucketId.intValue(), currentTarget);
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        currentTarget = getNodeForBucketWrite(bucketId.intValue(), retryTime);
      }

      // It's possible this is a GemFire thread e.g. ServerConnection
      // which got to this point because of a distributed system shutdown or
      // region closure which uses interrupt to break any sleep() or wait()
      // calls
      // e.g. waitForPrimary or waitForBucketRecovery in which case throw
      // exception
      checkShutdown();

      // If we get here, the attempt failed...
      if (count == 1) {
        if (ifNew) {
          this.prStats.incCreateOpsRetried();
        }
        else {
          this.prStats.incPutOpsRetried();
        }
      }
      if (event.getOperation().isCreate()) {
        this.prStats.incCreateRetries();
      }
      else {
        this.prStats.incPutRetries();
      }

      if (logger.fineEnabled()) {
        logger.fine("putInBucket for bucketId = " 
            + bucketStringForLogs(bucketId.intValue())
            + " failed (attempt # " + count
            + " (" + (timeOut - System.currentTimeMillis())  
            + " ms left), retrying with node " + currentTarget);
      }
    } // for

    // NOTREACHED
  }


  private RegionDestroyedException isCauseRegionDestroyedException(
      ForceReattemptException prce) {
    Throwable t = prce.getCause();
    while (t != null) {
      if (t instanceof RegionDestroyedException) {
        return (RegionDestroyedException)t;
      }
      t = t.getCause();
    }
    return null;
  }

  /**
   * Find an existing live Node that owns the bucket, or create the bucket and
   * return one of its owners.
   * 
   * @param retryTime
   *                the RetryTimeKeeper to track retry times
   * @param event
   *                the event used to get the entry size in the event a new
   *                bucket should be created
   * @param bucketId
   *                the identity of the bucket should it be created
   * @return a Node which contains the bucket, potentially null
   */
  private InternalDistributedMember waitForNodeOrCreateBucket(
      RetryTimeKeeper retryTime, EntryEventImpl event, Integer bucketId)
  {
    InternalDistributedMember newNode;
    if (retryTime.overMaximum()) {
      PRHARedundancyProvider.timedOut(this, null, null, LocalizedStrings
          .PRHARRedundancyProvider_ALLOCATE_ENOUGH_MEMBERS_TO_HOST_BUCKET
          .toLocalizedString(), retryTime.getRetryTime());
      // NOTREACHED
    }

    retryTime.waitForBucketsRecovery();
    newNode = getNodeForBucketWrite(bucketId.intValue(), retryTime);
    if (newNode == null) {
      newNode = createBucket(bucketId.intValue(), getEntrySize(event),
          retryTime);
    }

    return newNode;
  }

  /**
   * Serialize the key and value early (prior to creating the message) to gather
   * the size of the entry Assumes the new value from the
   * <code>EntryEventImpl</code> is not serialized
   * 
   * @return sum of bytes as reported by
   *         {@link CachedDeserializable#getSizeInBytes()}
   */
  // private int serializeValue(EntryEventImpl event)
  // {
  // TODO serialize the key as well
  // this code used to make the following call:
  // Object val = event.getNewValue();
  // which deserializes the value and we don't want to do that.
  // int numBytes = 0;
  // Object val = event.getNewValue();
  // if (val == null) {
  // // event.setSerializedNewValue(new byte[] {DataSerializer.NULL});
  // return 0;
  // }
  // if (val instanceof byte[]) {
  // byte[] v = (byte[]) val;
  // numBytes = v.length;
  // } else {
  // if (event.getSerializedNewValue() == null) {
  // event.setSerializedNewValue(EntryEventImpl.serialize(event.getNewValue()));
  // }
  // numBytes = getEntrySize(event);
  // }
  // return numBytes;
  // }
  /**
   * Get the serialized size of an <code>EntryEventImpl</code>
   * 
   * @param eei
   *                the entry from whcih to fetch the size
   * @return the size of the serialized entry
   */
  private static int getEntrySize(EntryEventImpl eei) {
    @Unretained final Object v = eei.getRawNewValue();
    if (v instanceof CachedDeserializable) {
      return ((CachedDeserializable)v).getSizeInBytes();
    }
    return 0;
  }

  // /**
  // * Gets the Node that is managing a specific bucketId. This does consider
  // the
  // * failed nodes.
  // *
  // * @param bucketId
  // * identifier for bucket
  // * @param failedNodeList
  // * of all the failedNodes to avoid these failed nodes to be picked in
  // * the next node selection.
  // * @return the Node managing the bucket
  // */
  // private Node getNodeForBucketExcludeFailedNode(final Long bucketId,
  // final List failedNodeList) {
  // throw new IllegalStateException("bucket2node should not be used");
  // }

  public InternalDistributedMember getOrCreateNodeForBucketWrite(int bucketId, final RetryTimeKeeper snoozer) {
    InternalDistributedMember targetNode = getNodeForBucketWrite(bucketId, snoozer);
    if(targetNode != null) {
      return targetNode;
    }
    
    try {
      return createBucket(bucketId, 0, null);
    }
    catch (PartitionedRegionStorageException e) {
      // try not to throw a PRSE if the cache is closing or this region was
      // destroyed during createBucket() (bug 36574)
      this.checkReadiness();
      if (this.cache.isClosed()) {
        throw new RegionDestroyedException(toString(), getFullPath());
      }
      throw e;
    }
  }
  /**
   * Fetch the primary Node returning if the redundancy for the Node is satisfied
   * @param bucketId the identity of the bucket
   * @param snoozer a RetryTimeKeeper to track how long we may wait until the bucket is ready
   * @return the primary member's id or null if there is no storage
   */
  public InternalDistributedMember getNodeForBucketWrite(int bucketId, 
      final RetryTimeKeeper snoozer) {
//    InternalDistributedSystem ids = (InternalDistributedSystem)this.cache.getDistributedSystem();
    RetryTimeKeeper localSnoozer = snoozer;
    // Prevent early access to buckets that are not completely created/formed
    // and
    // prevent writing to a bucket whose redundancy is sub par
    while (minimumWriteRedundancy > 0 && getRegionAdvisor().getBucketRedundancy(bucketId) < this.minimumWriteRedundancy) {
      this.cache.getCancelCriterion().checkCancelInProgress(null);

      // First check to see if there is any storage assigned TODO: redundant check to while condition
      if ( ! getRegionAdvisor().isStorageAssignedForBucket(bucketId, this.minimumWriteRedundancy, false)) {
        if (logger.fineEnabled()) {
          logger.fine("No storage assigned for bucket ("
              + bucketStringForLogs(bucketId) + ") write");
        }
        return null;  // No bucket for this key
      }

      if (localSnoozer == null) {
        localSnoozer = new RetryTimeKeeper(this.retryTimeout);
      }

      if (!localSnoozer.overMaximum()) {
        localSnoozer.waitForBucketsRecovery();
      }
      else {
        int red = getRegionAdvisor().getBucketRedundancy(bucketId);
        final TimeoutException noTime = new TimeoutException(LocalizedStrings.PartitionedRegion_ATTEMPT_TO_ACQUIRE_PRIMARY_NODE_FOR_WRITE_ON_BUCKET_0_TIMED_OUT_IN_1_MS_CURRENT_REDUNDANCY_2_DOES_NOT_SATISFY_MINIMUM_3.toLocalizedString(new Object[] {bucketStringForLogs(bucketId), Integer.valueOf(localSnoozer.getRetryTime()), Integer.valueOf(red), Integer.valueOf(this.minimumWriteRedundancy)}));
        checkReadiness();
        throw noTime;
      }
    }
    // Possible race with loss of redundancy at this point.
    // This loop can possibly create a soft hang if no primary is ever selected.
    // This is preferable to returning null since it will prevent obtaining the
    // bucket lock for bucket creation.
    return waitForNoStorageOrPrimary(bucketId, "write");
  }

  
  
  /**
   * wait until there is a primary or there is no storage
   * @param bucketId the id of the target bucket
   * @param readOrWrite a string used in log messages
   * @return the primary member's id or null if there is no storage
   */
   private InternalDistributedMember waitForNoStorageOrPrimary(int bucketId,
      String readOrWrite) {
     boolean isInterrupted = false;
     try {
       for (;;) {
         isInterrupted = Thread.interrupted() || isInterrupted;
         InternalDistributedMember d = getBucketPrimary(bucketId);
         if (d != null) {
           return d; // success!
         } 
         else {
           // go around the loop again
           if (logger.fineEnabled()) {
             logger.fine("No primary node found for bucket ("
                 + bucketStringForLogs(bucketId) + ") " + readOrWrite);
           }
         }
         if (!getRegionAdvisor().isStorageAssignedForBucket(bucketId)) {
           if (logger.fineEnabled()) {
            logger.fine("No storage while waiting for primary for bucket ("
                + bucketStringForLogs(bucketId) + ") " + readOrWrite);
          }
           return null; // No bucket for this key
         }
         checkShutdown();
       }
     }
     finally {
       if (isInterrupted) {
         Thread.currentThread().interrupt();
       }
     }
  }

  /**
   * override the one in LocalRegion since we don't need to do getDeserialized.
   */
  @Override
  protected final Object nonTxnFindObject(KeyInfo keyInfo, boolean isCreate,
      boolean generateCallbacks, Object localValue, boolean disableCopyOnRead,
      boolean preferCD, EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadFromHDFS)
      throws TimeoutException, CacheLoaderException {
    Object result = null;
    FutureResult thisFuture = new FutureResult(getCancelCriterion());
    Future otherFuture = (Future)this.getFutures.putIfAbsent(keyInfo.getKey(), thisFuture);
    // only one thread can get their future into the map for this key at a time
    if (otherFuture != null) {
      try {
        result = otherFuture.get();
        if (result != null) {
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
         /* if(GemFireCacheImpl.gfxdSystem() && result instanceof Chunk) {
            if(!((Chunk)result).use()) {
              return null;
            }
          }*/
           // what was a miss is now a hit
          RegionEntry re = null;
          if (isCreate) {
            re = basicGetEntry(keyInfo.getKey());
            updateStatsForGet(re, true);
          }
          return result;
        }
        // if value == null, try our own search/load
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        getCache().getCancelCriterion().checkCancelInProgress(e);
        return null;
      }
      catch (ExecutionException e) {
        // unexpected since there is no background thread
        AssertionError err = new AssertionError("unexpected exception");
        err.initCause(err);
        throw err;
      }
    }
    try {
      result = getSharedDataView().findObject(keyInfo, this, true/*isCreate*/, generateCallbacks,
          localValue, disableCopyOnRead, preferCD, null, null, false, allowReadFromHDFS);
    }
    finally {
      if (result instanceof Chunk) {
        thisFuture.set(null);
      } else {
        thisFuture.set(result);
      }
      this.getFutures.remove(keyInfo.getKey());
    }
    return result;
  }

  /**
   * override the one in LocalRegion since we don't need to do getDeserialized.
   */
  @Override
  public Object get(Object key, Object aCallbackArgument,
      boolean generateCallbacks, boolean disableCopyOnRead, boolean preferCD,
      ClientProxyMembershipID requestingClient, final TXStateInterface tx,
      final TXStateInterface lockState, final EntryEventImpl clientEvent,
      boolean returnTombstones, boolean allowReadFromHDFS) throws TimeoutException, CacheLoaderException {
    validateKey(key);
    validateCallbackArg(aCallbackArgument);
    checkReadiness();
    checkForNoAccess();
    CachePerfStats stats = getCachePerfStats();
    long start = stats.startGet();
    boolean miss = true;
    try {
      // if scope is local and there is no loader, then
      // don't go further to try and get value
      final Object value = getDataView(tx).findObject(
          getKeyInfo(key, aCallbackArgument), this, true/*isCreate*/,
          generateCallbacks, null /*no local value*/, disableCopyOnRead,
          preferCD, requestingClient, clientEvent, returnTombstones, allowReadFromHDFS);
      if (value != null && !Token.isInvalid(value)) {
        miss = false;
      }
      return value;
    }
    finally {
      stats.endGet(start, miss);
    }
  }

   public InternalDistributedMember getOrCreateNodeForBucketRead(int bucketId) {
     InternalDistributedMember targetNode = getNodeForBucketRead(bucketId);
     if(targetNode != null) {
       return targetNode;
     }
     try {
       return createBucket(bucketId, 0, null);
     }
     catch (PartitionedRegionStorageException e) {
       // try not to throw a PRSE if the cache is closing or this region was
       // destroyed during createBucket() (bug 36574)
       this.checkReadiness();
       if (this.cache.isClosed()) {
         throw new RegionDestroyedException(toString(), getFullPath());
       }
       throw e;
     }
   }

  public final InternalDistributedMember getOrCreateNodeForInitializedBucketRead(
      final int bucketId, final boolean skipUnitializedMembers) {
    InternalDistributedMember targetNode = getNodeForInitializedBucketRead(
        bucketId, skipUnitializedMembers);
    if (targetNode != null) {
      return targetNode;
    }
    try {
      InternalDistributedMember writeNode = createBucket(bucketId, 0, null);
      targetNode = getNodeForInitializedBucketRead(bucketId,
          skipUnitializedMembers);
      return targetNode != null ? targetNode : writeNode;
    } catch (PartitionedRegionStorageException e) {
      // try not to throw a PRSE if the cache is closing or this region was
      // destroyed during createBucket() (bug 36574)
      this.checkReadiness();
      if (this.cache.isClosed()) {
        throw new RegionDestroyedException(toString(), getFullPath());
      }
      throw e;
    }
  }

  /**
   * Gets the Node for reading a specific bucketId. This method gives
   * priority to local node to speed up operation and avoid remote calls.
   * 
   * @param bucketId
   *                identifier for bucket
   * 
   * @return the primary member's id or null if there is no storage
   */
  public InternalDistributedMember getNodeForBucketRead(int bucketId) {
    // Wait until there is storage
    InternalDistributedMember primary = waitForNoStorageOrPrimary(bucketId, 
        "read");
    if (primary == null) {
      return null;
    }

    if (this.hdfsStoreName != null) {
      return getNodeForBucketWrite(bucketId, null);
    }

    InternalDistributedMember result =  getRegionAdvisor().getPreferredNode(bucketId);
    return result;
  }

  protected final InternalDistributedMember getOrCreateNodeForBucketRead(
      final KeyInfo keyInfo, final boolean noCreateNode) {
    final Object key = keyInfo.getKey();
    int bucketId = keyInfo.getBucketId();
    if (bucketId == KeyInfo.UNKNOWN_BUCKET) {
      bucketId = PartitionedRegionHelper.getHashKey(this, Operation.GET_ENTRY,
          key, keyInfo.getValue(), keyInfo.getCallbackArg());
      keyInfo.setBucketId(bucketId);
    }
    if (noCreateNode) {
      return getNodeForBucketRead(bucketId);
    }
    return getOrCreateNodeForBucketRead(bucketId);
  }

  public final InternalDistributedMember getNodeForInitializedBucketRead(
      final int bucketId, final boolean skipUnitializedMembers) {
    // Wait until there is storage
    if (waitForNoStorageOrPrimary(bucketId, "read") != null) {
      final InternalDistributedMember target = getRegionAdvisor()
          .getPreferredInitializedNode(bucketId, skipUnitializedMembers);
      if (target != null) {
        return target;
      }
      // if no node found then fallback to getNodeForBucketWrite()
      return getNodeForBucketWrite(bucketId, null);
    }
    return null;
  }

  protected final InternalDistributedMember getOrCreateNodeForInitializedBucketRead(
      final KeyInfo keyInfo, final boolean noCreateNode) {
    final Object key = keyInfo.getKey();
    int bucketId = keyInfo.getBucketId();
    if (bucketId == KeyInfo.UNKNOWN_BUCKET) {
      bucketId = PartitionedRegionHelper.getHashKey(this, Operation.GET_ENTRY,
          key, keyInfo.getValue(), keyInfo.getCallbackArg());
      keyInfo.setBucketId(bucketId);
    }
    if (noCreateNode) {
      return getNodeForInitializedBucketRead(bucketId, false);
    }
    return getOrCreateNodeForInitializedBucketRead(bucketId, false);
  }

  protected final InternalDistributedMember getOrCreateNodeForBucketRead(
      final Object key, final Object callbackArg, final boolean noCreateNode) {
    final int bucketId = PartitionedRegionHelper.getHashKey(this,
        Operation.GET_ENTRY, key, null, callbackArg);
    if (noCreateNode) {
      return getNodeForBucketRead(bucketId);
    }
    return getOrCreateNodeForBucketRead(bucketId);
  }

  /**
   * Gets the Node for reading or performing a load from a specific bucketId.
   * @return the member from which to read or load
   * @since 5.7
   */
  private InternalDistributedMember getNodeForBucketReadOrLoad(int bucketId) {
    InternalDistributedMember targetNode;
    if (!this.haveCacheLoader && (this.hdfsStoreName == null)) {
      targetNode = getNodeForBucketRead(bucketId);
    }
    else {
      targetNode = getNodeForBucketWrite(bucketId, null /* retryTimeKeeper */);
    }
    if (targetNode == null) {
      this.checkShutdown(); // Fix for bug#37207
      targetNode = createBucket(bucketId, 0 /* size */, null /* retryTimeKeeper */);
    }
    return targetNode;
  }

  /**
   * Puts the key/value pair into the remote target that is managing the key's
   * bucket.
   * 
   * @param recipient
   *                the member to receive the message
   * @param event
   *                the event prompting this action
   * @param ifNew
   *                the mysterious ifNew parameter
   * @param ifOld
   *                the mysterious ifOld parameter
   * @return whether the operation succeeded
   * @throws PrimaryBucketException
   *                 if the remote bucket was not the primary
   * @throws ForceReattemptException
   *                 if the peer is no longer available
   */
  public boolean putRemotely(final InternalDistributedMember recipient,
                              final EntryEventImpl event,
                              boolean ifNew,
                              boolean ifOld,
                              Object expectedOldValue,
                              boolean requireOldValue,
                              boolean ignoreRecipient)  
  throws PrimaryBucketException, ForceReattemptException {
    // boolean forceAck = basicGetWriter() != null
    // || getDistributionAdvisor().adviseNetWrite().size() > 0;
    long eventTime = event.getEventTime(0L);
    final PutMessage.PutResponse response = sendPutRemotely(recipient, event,
        eventTime, ifNew, ifOld, expectedOldValue, requireOldValue, true);
    if (response != null) {
      return waitForRemotePut(response, recipient, event, requireOldValue);
    }
    return true;// ???:ezoerner:20080728 why return true if response was null?
  }

  protected final PutMessage.PutResponse sendPutRemotely(
      final InternalDistributedMember recipient, final EntryEventImpl event,
      long eventTime, boolean ifNew, boolean ifOld,
      final Object expectedOldValue, boolean requireOldValue,
      final boolean cacheWrite) throws ForceReattemptException {
    final PutMessage.PutResponse response = PutMessage.send(recipient, this,
        event, eventTime, ifNew, ifOld, expectedOldValue, requireOldValue,
        cacheWrite);
    if (response != null) {
      this.prStats.incPartitionMessagesSent();
      return response;
    }
    return null;
  }

  protected final PutMessage.PutResponse sendPutRemotely(final Set recipients,
      final EntryEventImpl event, long eventTime, boolean ifNew, boolean ifOld,
      final Object expectedOldValue, boolean requireOldValue,
      final boolean cacheWrite) throws ForceReattemptException {
    PutMessage.PutResponse response = (PutMessage.PutResponse)PutMessage.send(
        recipients, this, event, eventTime, ifNew, ifOld, expectedOldValue,
        requireOldValue, cacheWrite);
    if (response != null) {
      this.prStats.incPartitionMessagesSent();
      return response;
    }
    return null;
  }

  protected final boolean waitForRemotePut(
      final PutMessage.PutResponse response, final Object recipients,
      final EntryEventImpl event, boolean requireOldValue)
      throws PrimaryBucketException, ForceReattemptException {
    try {
      final PutResult pr = response.waitForResult();
      // if (logger.fineEnabled()) {
      // logger.fine("return operation for put is " + pr.op);
      // }
      event.setOperation(pr.op);
      event.setVersionTag(pr.versionTag);
      if (requireOldValue) {
        event.setOldValue(pr.oldValue, true);
      }
      return pr.returnValue;
    } catch (RegionDestroyedException rde) {
      if (logger.fineEnabled()) {
        logger.fine("putRemotely: caught RegionDestroyedException", rde);
      }
      throw new RegionDestroyedException(toString(), getFullPath());
    } catch (TransactionException te) {
      throw te;
    } catch (CacheException ce) {
      // Fix for bug 36014
      throw new PartitionedRegionDistributionException(
          LocalizedStrings.PartitionedRegion_PUTTING_ENTRY_ON_0_FAILED
              .toLocalizedString(recipients),
          ce);
    }
  }

  /**
   * Create a bucket for the provided bucket identifier in an atomic fashion.
   * 
   * @param bucketId
   *                the bucket identifier for the bucket that needs creation
   * @param snoozer
   *                tracking object used to determine length of time t for
   *                bucket creation
   * @return a selected Node on which to perform bucket operations
   */
  public InternalDistributedMember createBucket(int bucketId, int size,
      final RetryTimeKeeper snoozer) {

    InternalDistributedMember ret = getNodeForBucketWrite(bucketId, snoozer);
    if (ret != null) {
      return ret;
    }
    // In the current co-location scheme, we have to create the bucket for the
    // colocatedWith region, before we create bucket for this region
    final PartitionedRegion colocatedWith = ColocationHelper
        .getColocatedRegion(this);
    if (colocatedWith != null) {
      colocatedWith.createBucket(bucketId, size, snoozer);
    }
    
    // THis is for FPR.if the given bucket id is not starting bucket id then
    // create bucket for starting bucket id
    String partitionName = null;
    if (this.isFixedPartitionedRegion()) {
      FixedPartitionAttributesImpl fpa = PartitionedRegionHelper
          .getFixedPartitionAttributesForBucket(this, bucketId);
      partitionName = fpa.getPartitionName();
      int startBucketId = fpa.getStartingBucketID();
      if(startBucketId == -1){
        throw new PartitionNotAvailableException(
            LocalizedStrings.FOR_FIXED_PARTITION_REGION_0_PARTITION_1_IS_NOT_YET_INITIALIZED_ON_DATASTORE
                .toString(new Object[]{getName(),partitionName}));
      }
      if (startBucketId != bucketId) {
        createBucket(startBucketId, size, snoozer);
      }
    }
    // Potentially no storage assigned, start bucket creation, be careful of race
    // conditions
    final long startTime = PartitionedRegionStats.startTime();
    if(isDataStore()) {
      ret = this.redundancyProvider.createBucketAtomically(bucketId, size,
          startTime, false, partitionName);
    } else {
      ret = this.redundancyProvider.createBucketOnDataStore(bucketId, size, startTime, snoozer);
    }
    return ret;
  }

  @Override
  final protected Object findObjectInSystem(KeyInfo keyInfo, boolean isCreate,
      final TXStateInterface lockState, boolean generateCallbacks,
      Object localValue, boolean disableCopyOnRead, boolean preferCD,
      ClientProxyMembershipID requestingClient, EntryEventImpl clientEvent,
      boolean returnTombstones, boolean allowReadFromHDFS) throws CacheLoaderException, TimeoutException {
    Object obj = null;
    final Object key = keyInfo.getKey();
    final Object aCallbackArgument = keyInfo.getCallbackArg();
    final long startTime = PartitionedRegionStats.startTime();
    try {
      int bucketId = keyInfo.getBucketId();
      if (bucketId == KeyInfo.UNKNOWN_BUCKET) {
        bucketId = PartitionedRegionHelper.getHashKey(this,
            isCreate ? Operation.CREATE : null, key, keyInfo.getValue(),
            aCallbackArgument);
        keyInfo.setBucketId(bucketId);
      }
      InternalDistributedMember targetNode = getNodeForBucketReadOrLoad(bucketId);
      if (targetNode == null) {
        if (logger.fineEnabled()) {
          logger
            .fine("No need to create buckets on get(), no CacheLoader configured.");
        }
        return null;
      }
      obj = getFromBucket(targetNode, bucketId, key, aCallbackArgument,
          disableCopyOnRead, preferCD, requestingClient, lockState, lockState,
          clientEvent, returnTombstones, allowReadFromHDFS);
    }
    finally {
      this.prStats.endGet(startTime);
    }
    return obj;
  }

  /**
   * Execute the provided named function in all locations that contain the given
   * keys. So function can be executed on just one fabric node, executed in
   * parallel on a subset of nodes in parallel across all the nodes.
   * 
   * @param function
   * @param execution
   * @param rc
   * @since 6.0
   */
  public ResultCollector executeFunction(final Function function,
      final PartitionedRegionFunctionExecutor execution, ResultCollector rc,
      boolean executeOnBucketSet) {
    final TXStateInterface tx = getTXState();
    if (tx != null) {
      // flush any pending TXStateProxy ops
      tx.flushPendingOps(getDistributionManager());
      // for transactions we need to wait even after exceptions else commit or
      // rollback can get executed before previous message has even reached
      execution.setWaitOnExceptionFlag(true);
    }
    if (execution.isPrSingleHop()) {
      if (!executeOnBucketSet) {
        switch (execution.getFilter().size()) {
          case 1:
            if (logger.fineEnabled()) {
              logger.fine("Executing Function: (Single Hop) "
                  + function.getId() + " on single node.");
            }
            // no waitOnException required (or should be set) for single node
            execution.setWaitOnExceptionFlag(false);
            return executeOnSingleNode(function, execution, rc, true, tx);
          default:
            if (logger.fineEnabled()) {
              logger.fine("Executing Function: (Single Hop) "
                  + function.getId() + " on multiple nodes.");
            }
            return executeOnMultipleNodes(function, execution, rc, true, tx);
        }
      }
      else {
        if (logger.fineEnabled()) {
          logger.fine("Executing Function: (Single Hop) " + function.getId()
              + " on a set of buckets nodes.");
        }
        return executeOnBucketSet(function, execution, rc, execution
            .getFilter(), tx);
      }
    }
    else {
      switch (execution.getFilter().size()) {
        case 0:
          if (logger.fineEnabled()) {
            logger.fine("Executing Function: " + function.getId()
                + " withArgs=" + execution.getArguments() + " on all buckets.");
          }
          return executeOnAllBuckets(function, execution, rc, false, tx);
        case 1:
          if (logger.fineEnabled()) {
            logger.fine("Executing Function: " + function.getId()
                + " withArgs=" + execution.getArguments() + " on single node.");
          }
          // no waitOnException required (or should be set) for single node
          execution.setWaitOnExceptionFlag(false);
          return executeOnSingleNode(function, execution, rc, false, tx);
        default:
          if (logger.fineEnabled()) {
            logger.fine("Executing Function: " + function.getId()
                + " withArgs=" + execution.getArguments()
                + " on multiple nodes.");
          }
          return executeOnMultipleNodes(function, execution, rc, false, tx);
      }
    }
  }

  /**
   * Executes function on multiple nodes
   * 
   * @param function
   * @param execution
   */
  private ResultCollector executeOnMultipleNodes(final Function function,
      final PartitionedRegionFunctionExecutor execution, ResultCollector rc,
      boolean isPRSingleHop, final TXStateInterface tx) {
    final Set routingKeys = execution.getFilter();
    final boolean primaryMembersNeeded = function.optimizeForWrite();
    final boolean hasRoutingObjects = execution.hasRoutingObjects();
    HashMap<Integer, HashSet> bucketToKeysMap = FunctionExecutionNodePruner
        .groupByBucket(this, routingKeys, primaryMembersNeeded,
            hasRoutingObjects);
    HashMap<InternalDistributedMember, HashSet> memberToKeysMap = new HashMap<InternalDistributedMember, HashSet>();
    HashMap<InternalDistributedMember, HashSet<Integer>> memberToBuckets = FunctionExecutionNodePruner
    .groupByMemberToBuckets(this, bucketToKeysMap.keySet(), primaryMembersNeeded);    
    
    if (isPRSingleHop && (memberToBuckets.size() > 1)) {
      // memberToBuckets.remove(getMyId()); // don't remove
      for (InternalDistributedMember targetNode : memberToBuckets.keySet()) {
        if (!targetNode.equals(getMyId())) {
          for (Integer bucketId : memberToBuckets.get(targetNode)) {
            Set<ServerBucketProfile> profiles = this.getRegionAdvisor()
                .getClientBucketProfiles(bucketId);
            if (profiles != null) {
              for (ServerBucketProfile profile : profiles) {
                if (profile.getDistributedMember().equals(targetNode)) {
                  if (logger.fineEnabled()) {
                    logger
                        .fine("FunctionServiceSingleHop: Found multiple nodes."
                            + getMyId());
                  }
                  throw new InternalFunctionInvocationTargetException(
                      LocalizedStrings.PartitionedRegion_MULTIPLE_TARGET_NODE_FOUND_FOR
                          .toLocalizedString());
                }
              }
            }
          }
        }
      }
    }
    
    while(!execution.getFailedNodes().isEmpty()) {
      Set memberKeySet = memberToBuckets.keySet();
      RetryTimeKeeper retryTime = new RetryTimeKeeper(this.retryTimeout);
      Iterator iterator = memberKeySet.iterator();
      
      boolean hasRemovedNode = false;
      
      while (iterator.hasNext()){
        if(execution.getFailedNodes().contains(((InternalDistributedMember)iterator.next()).getId())){
          hasRemovedNode = true;
        }
      }
      
      if(hasRemovedNode){
        if (retryTime.overMaximum()) {
          PRHARedundancyProvider.timedOut(this, null, null, "doing function execution", this.retryTimeout);
          // NOTREACHED
        }
        retryTime.waitToRetryNode();
        memberToBuckets = FunctionExecutionNodePruner.groupByMemberToBuckets(
            this, bucketToKeysMap.keySet(), primaryMembersNeeded);     
        
      }else{
        execution.clearFailedNodes();
      }    
    }
    
    for (Map.Entry entry : memberToBuckets.entrySet()) {
      InternalDistributedMember member = (InternalDistributedMember)entry
          .getKey();
      HashSet<Integer> buckets = (HashSet)entry.getValue();
      for (Integer bucket : buckets) {
        HashSet keys = memberToKeysMap.get(member);
        if (keys == null) {
          keys = new HashSet();
        }
        keys.addAll(bucketToKeysMap.get(bucket));
        memberToKeysMap.put(member, keys);
      }
    }
    //memberToKeysMap.keySet().retainAll(memberToBuckets.keySet());
    if (memberToKeysMap.isEmpty()) {
      throw new NoMemberFoundException(
          LocalizedStrings.PartitionedRegion_NO_TARGET_NODE_FOUND_FOR_KEY_0
              .toLocalizedString(routingKeys));
    }
    Set<InternalDistributedMember> dest = memberToKeysMap.keySet();
    execution.validateExecution(function, dest);
    //added for the data aware procedure.
    execution.setExecutionNodes(dest);
    //end

    final HashSet localKeys = memberToKeysMap.remove(getMyId());
    HashSet<Integer> localBucketSet = null;
    boolean remoteOnly = false;
    if (localKeys == null) {
      remoteOnly = true;
    }
    else {
      localBucketSet = FunctionExecutionNodePruner
      .getBucketSet(PartitionedRegion.this, localKeys,
                    hasRoutingObjects);
      
      remoteOnly = false;
    }
    final LocalResultCollector<?, ?> localResultCollector = execution
        .getLocalResultCollector(function, rc);
    final DM dm = getDistributionManager();
    final PartitionedRegionFunctionResultSender resultSender = new PartitionedRegionFunctionResultSender(
        dm, tx, this, 0L, localResultCollector, execution
            .getServerResultSender(), memberToKeysMap.isEmpty(), remoteOnly,
        execution.isForwardExceptions(), function, localBucketSet);

    if (localKeys != null) {
      final RegionFunctionContextImpl prContext = new RegionFunctionContextImpl(
          function.getId(),
          PartitionedRegion.this,
          execution.getArgumentsForMember(getMyId().getId()),
                                          localKeys,
              ColocationHelper.constructAndGetAllColocatedLocalDataSet(
                               PartitionedRegion.this, localBucketSet, tx),
                                          localBucketSet,
                                          resultSender,
                                          execution.isReExecute());
      execution.executeFunctionOnLocalPRNode(function, prContext, resultSender,
          dm, tx);
    }

    if (!memberToKeysMap.isEmpty()) {    
      HashMap<InternalDistributedMember, FunctionRemoteContext> recipMap = 
        new HashMap<InternalDistributedMember, FunctionRemoteContext>();
      for (Map.Entry me : memberToKeysMap.entrySet()) {
        InternalDistributedMember recip = (InternalDistributedMember)me
            .getKey();
        HashSet memKeys = (HashSet)me.getValue();
        FunctionRemoteContext context = new FunctionRemoteContext(function,
            execution.getArgumentsForMember(recip.getId()), memKeys,
            FunctionExecutionNodePruner.getBucketSet(this, memKeys,
                hasRoutingObjects), execution.isReExecute(),
                execution.isFnSerializationReqd(), tx);
        recipMap.put(recip, context);
      }

      PartitionedRegionFunctionResultWaiter resultReciever = new PartitionedRegionFunctionResultWaiter(
          getSystem(), this.getPRId(), localResultCollector, function,
          resultSender);
      return resultReciever.getPartitionedDataFrom(recipMap, this, execution);
    }
    return localResultCollector;

  }
  
  /**
   * Single key execution on single node
   * 
   * @param function
   * @param execution
   * @since 6.0
   */
  private ResultCollector executeOnSingleNode(final Function function,
      final PartitionedRegionFunctionExecutor execution, ResultCollector rc,
      boolean isPRSingleHop, final TXStateInterface tx) {
    final Set routingKeys = execution.getFilter();
    final Object key = routingKeys.iterator().next();
    final Integer bucketId;
    if (execution.hasRoutingObjects()) {
      bucketId = Integer.valueOf(PartitionedRegionHelper.getHashKey(this, key));
    }
    else {
      //bucketId = Integer.valueOf(PartitionedRegionHelper.getHashKey(this,
      //    Operation.FUNCTION_EXECUTION, key, null));
      bucketId = Integer.valueOf(PartitionedRegionHelper.getHashKey(this,
          Operation.FUNCTION_EXECUTION, key, null, null));
    }
    InternalDistributedMember targetNode = null;
    if (function.optimizeForWrite()) {
      targetNode = createBucket(bucketId.intValue(), 0, null /* retryTimeKeeper */);
      HeapMemoryMonitor hmm = ((InternalResourceManager) cache.getResourceManager()).getHeapMonitor();
      if (hmm.isMemberHeapCritical(targetNode)
          && !MemoryThresholds.isLowMemoryExceptionDisabled()) {
        Set<DistributedMember> sm = Collections.singleton((DistributedMember) targetNode);
        throw new LowMemoryException(LocalizedStrings.ResourceManager_LOW_MEMORY_FOR_0_FUNCEXEC_MEMBERS_1.toLocalizedString(
                new Object[] {function.getId(), sm}), sm);
      }
    }
    else {
      targetNode = getOrCreateNodeForBucketRead(bucketId.intValue());
    }
    final DistributedMember localVm = getMyId(); 
    if (targetNode!= null && isPRSingleHop && !localVm.equals(targetNode)) {
      Set<ServerBucketProfile> profiles = this.getRegionAdvisor()
          .getClientBucketProfiles(bucketId);
      if (profiles != null) {
        for (ServerBucketProfile profile : profiles) {
          if (profile.getDistributedMember().equals(targetNode)) {
            if (logger.fineEnabled()) {
              logger.fine("FunctionServiceSingleHop: Found remote node."
                  + localVm);
            }
            throw new InternalFunctionInvocationTargetException(
                LocalizedStrings.PartitionedRegion_MULTIPLE_TARGET_NODE_FOUND_FOR
                    .toLocalizedString());
          }
        }
      }
    }

    if (targetNode == null) {
      throw new NoMemberFoundException(
          LocalizedStrings.PartitionedRegion_NO_TARGET_NODE_FOUND_FOR_KEY_0
              .toLocalizedString(key));
    }
    
    if (logger.fineEnabled()) {
      logger.fine("executing " + function.getId() + " withArgs="
          + execution.getArguments() + " on " + targetNode);
    }
    while (!execution.getFailedNodes().isEmpty()) {
      RetryTimeKeeper retryTime = new RetryTimeKeeper(this.retryTimeout);
      if (execution.getFailedNodes().contains(targetNode.getId())) {
        /*if (retryTime.overMaximum()) {
          PRHARedundancyProvider.timedOut(this, null, null,
              "doing function execution", this.retryTimeout);
          // NOTREACHED
        }*/
	//Asif: Fix for Bug # 40083
        targetNode = null;
        while (targetNode == null) {
          if (retryTime.overMaximum()) {
            PRHARedundancyProvider.timedOut(this, null, null,
                "doing function execution", this.retryTimeout);
            // NOTREACHED
          }
          retryTime.waitToRetryNode();          
          if (function.optimizeForWrite()) {
            targetNode = getOrCreateNodeForBucketWrite(bucketId.intValue(), retryTime);
          }
          else {
            targetNode = getOrCreateNodeForBucketRead(bucketId.intValue());
          }
        }
        if (targetNode == null) {
          throw new FunctionException(
              LocalizedStrings.PartitionedRegion_NO_TARGET_NODE_FOUND_FOR_KEY_0
                  .toLocalizedString(key));
        }
      }
      else {
        execution.clearFailedNodes();
      }
    }

    final HashSet<Integer> buckets = new HashSet<Integer>(); 
    buckets.add(bucketId);
    final Set<InternalDistributedMember> singleMember = Collections
        .singleton(targetNode);
    execution.validateExecution(function, singleMember);
    execution.setExecutionNodes(singleMember);
    if (targetNode.equals(localVm)) {
      final LocalResultCollector<?, ?> localRC = execution
          .getLocalResultCollector(function, rc);
      final DM dm = getDistributionManager();
      PartitionedRegionFunctionResultSender resultSender = new PartitionedRegionFunctionResultSender(
          dm, tx, PartitionedRegion.this, 0, localRC,
          execution.getServerResultSender(), true, false,
          execution.isForwardExceptions(), function, buckets);
      final FunctionContext context = new RegionFunctionContextImpl(function
          .getId(), PartitionedRegion.this, execution
          .getArgumentsForMember(localVm.getId()), routingKeys,
          ColocationHelper.constructAndGetAllColocatedLocalDataSet(
              PartitionedRegion.this, buckets, tx), buckets, resultSender,
              execution.isReExecute());
      execution.executeFunctionOnLocalPRNode(function, context, resultSender,
          dm, tx);
      return localRC;      
    }
    else {
      return executeFunctionOnRemoteNode(targetNode, function,
          execution.getArgumentsForMember(targetNode.getId()), routingKeys, rc,
          buckets, execution.getServerResultSender(), execution, tx);
    }
  }
  
  public ResultCollector executeOnBucketSet(final Function function,
      PartitionedRegionFunctionExecutor execution, ResultCollector rc,
      Set<Integer> bucketSet, final TXStateInterface tx) {
    Set<Integer> actualBucketSet = this.getRegionAdvisor().getBucketSet();
    try {
      bucketSet.retainAll(actualBucketSet);
    }
    catch (NoSuchElementException done) {
    }
    HashMap<InternalDistributedMember, HashSet<Integer>> memberToBuckets = FunctionExecutionNodePruner
        .groupByMemberToBuckets(this, bucketSet, function.optimizeForWrite());

    if (memberToBuckets.isEmpty()) {
      if (logger.fineEnabled()) {
        logger.fine("Executing on bucketset : " + bucketSet
            + " executeOnBucketSet Member to buckets map is : "
            + memberToBuckets + " bucketSet is empty");
      }
      throw new EmptyRegionFunctionException(
          LocalizedStrings.PartitionedRegion_FUNCTION_NOT_EXECUTED_AS_REGION_IS_EMPTY
              .toLocalizedString());
    }
    else {
      if(logger.fineEnabled()) {
        logger.fine("Executing on bucketset : " + bucketSet
            + " executeOnBucketSet Member to buckets map is : "
            + memberToBuckets);
      }
    }
    
    if (memberToBuckets.size() > 1) {
      for (InternalDistributedMember targetNode : memberToBuckets.keySet()) {
        if (!targetNode.equals(getMyId())) {
          for (Integer bucketId : memberToBuckets.get(targetNode)) {
            Set<ServerBucketProfile> profiles = this.getRegionAdvisor()
                .getClientBucketProfiles(bucketId);
            if (profiles != null) {
              for (ServerBucketProfile profile : profiles) {
                if (profile.getDistributedMember().equals(targetNode)) {
                  if (logger.fineEnabled()) {
                    logger
                        .fine("FunctionServiceSingleHop: Found multiple nodes for executing on bucket set."
                            + getMyId());
                  }
                  throw new InternalFunctionInvocationTargetException(
                      LocalizedStrings.PartitionedRegion_MULTIPLE_TARGET_NODE_FOUND_FOR
                          .toLocalizedString());
                }
              }
            }
          }
        }
      }
    }
    
    execution = (PartitionedRegionFunctionExecutor)execution.withFilter(new HashSet());
    while (!execution.getFailedNodes().isEmpty()) {
      Set memberKeySet = memberToBuckets.keySet();
      RetryTimeKeeper retryTime = new RetryTimeKeeper(this.retryTimeout);
      Iterator iterator = memberKeySet.iterator();
      boolean hasRemovedNode = false;

      while (iterator.hasNext()) {
        if (execution.getFailedNodes().contains(
            ((InternalDistributedMember)iterator.next()).getId())) {
          hasRemovedNode = true;
        }
      }

      if (hasRemovedNode) {
        if (retryTime.overMaximum()) {
          PRHARedundancyProvider.timedOut(this, null, null,
              "doing function execution", this.retryTimeout);
          // NOTREACHED
        }
        retryTime.waitToRetryNode();
        memberToBuckets = FunctionExecutionNodePruner.groupByMemberToBuckets(
            this, bucketSet, function.optimizeForWrite());
      }
      else {
        execution.clearFailedNodes();
      }
    }
    
    Set<InternalDistributedMember> dest = memberToBuckets.keySet();
    if (function.optimizeForWrite() && cache.getResourceManager().getHeapMonitor().
        containsHeapCriticalMembers(dest) &&
        !MemoryThresholds.isLowMemoryExceptionDisabled()) {
      Set<InternalDistributedMember> hcm  = cache.getResourceAdvisor().adviseCriticalMembers();
      Set<DistributedMember> sm = SetUtils.intersection(hcm, dest);
      throw new LowMemoryException(LocalizedStrings.ResourceManager_LOW_MEMORY_FOR_0_FUNCEXEC_MEMBERS_1.toLocalizedString(
          new Object[] {function.getId(), sm}), sm);
    }

    boolean isSelf = false;
    execution.setExecutionNodes(dest);
    final Set localBucketSet = memberToBuckets.remove(getMyId());
    if (localBucketSet != null) {
      isSelf = true;
    }
    final HashMap<InternalDistributedMember, FunctionRemoteContext> recipMap = new HashMap<InternalDistributedMember, FunctionRemoteContext>();
    for (InternalDistributedMember recip : dest) {
      FunctionRemoteContext context = new FunctionRemoteContext(function,
          execution.getArgumentsForMember(recip.getId()), null,
          memberToBuckets.get(recip), execution.isReExecute(),
          execution.isFnSerializationReqd(), tx);
      recipMap.put(recip, context);
    }
    //final LocalResultCollector localResultCollector = new LocalResultCollector(function, rc, execution);
    final LocalResultCollector<?, ?> localRC = execution
    .getLocalResultCollector(function, rc);
    
    final DM dm = getDistributionManager();
    final PartitionedRegionFunctionResultSender resultSender = new PartitionedRegionFunctionResultSender(
        dm, tx, this, 0L, localRC, execution
            .getServerResultSender(), recipMap.isEmpty(), !isSelf, execution.isForwardExceptions(), function, localBucketSet);

    // execute locally and collect the result
    if (isSelf && this.dataStore != null) {
      final RegionFunctionContextImpl prContext = new RegionFunctionContextImpl(function.getId(), PartitionedRegion.this,
          execution.getArgumentsForMember(getMyId().getId()), null,
          ColocationHelper.constructAndGetAllColocatedLocalDataSet(
              PartitionedRegion.this, localBucketSet, tx), localBucketSet,
          resultSender, execution.isReExecute());
//      final RegionFunctionContextImpl prContext = new RegionFunctionContextImpl(
//          function.getId(), PartitionedRegion.this, execution
//              .getArgumentsForMember(getMyId().getId()), null, ColocationHelper
//              .constructAndGetAllColocatedLocalDataSet(PartitionedRegion.this,
//                  localBucketSet), resultSender, execution.isReExecute());
      execution.executeFunctionOnLocalNode(function, prContext, resultSender,
          dm, tx);
    }
    PartitionedRegionFunctionResultWaiter resultReciever = new PartitionedRegionFunctionResultWaiter(
        getSystem(), this.getPRId(), localRC, function, resultSender);

    ResultCollector reply = resultReciever.getPartitionedDataFrom(recipMap,
        this, execution);

    return reply;
  
  }
  
  /**
   * Executes function on all bucket nodes
   * 
   * @param function
   * @param execution
   * @return ResultCollector
   * @since 6.0
   */
  private ResultCollector executeOnAllBuckets(final Function function,
      final PartitionedRegionFunctionExecutor execution, ResultCollector rc,
      boolean isPRSingleHop, final TXStateInterface tx) {
    Set<Integer> bucketSet = new HashSet<Integer>();
    Iterator<Integer> itr = this.getRegionAdvisor().getBucketSet().iterator();
    while (itr.hasNext()) {
      try {
        bucketSet.add(itr.next());
      }
      catch (NoSuchElementException ex) {
      }
    }
    HashMap<InternalDistributedMember, HashSet<Integer>> memberToBuckets = FunctionExecutionNodePruner
        .groupByMemberToBuckets(this, bucketSet, function.optimizeForWrite());

    if (memberToBuckets.isEmpty()) {
      throw new EmptyRegionFunctionException(LocalizedStrings.PartitionedRegion_FUNCTION_NOT_EXECUTED_AS_REGION_IS_EMPTY.toLocalizedString()
          );
    }
    
    while(!execution.getFailedNodes().isEmpty()){
      Set memberKeySet = memberToBuckets.keySet();
      RetryTimeKeeper retryTime = new RetryTimeKeeper(this.retryTimeout);
      
     Iterator iterator = memberKeySet.iterator();
      
      boolean hasRemovedNode = false;
      
      while (iterator.hasNext()){
        if(execution.getFailedNodes().contains(((InternalDistributedMember)iterator.next()).getId())){
          hasRemovedNode = true;
        }
      }
      
      if(hasRemovedNode){
        if (retryTime.overMaximum()) {
          PRHARedundancyProvider.timedOut(this, null, null, "doing function execution", this.retryTimeout);
          // NOTREACHED
        }
        retryTime.waitToRetryNode();
        memberToBuckets = FunctionExecutionNodePruner
        .groupByMemberToBuckets(this, bucketSet, function.optimizeForWrite());
      }else{
        execution.clearFailedNodes();
      }    
    }

    Set<InternalDistributedMember> dest = memberToBuckets.keySet();
    execution.validateExecution(function, dest);
    execution.setExecutionNodes(dest);

    boolean isSelf = false;
    final Set<Integer> localBucketSet = memberToBuckets.remove(getMyId());
    if (localBucketSet != null) {
      isSelf = true;
    }
    final HashMap<InternalDistributedMember, FunctionRemoteContext> recipMap = new HashMap<InternalDistributedMember, FunctionRemoteContext>();
    for (InternalDistributedMember recip : memberToBuckets.keySet()) {
      FunctionRemoteContext context = new FunctionRemoteContext(function,
          execution.getArgumentsForMember(recip.getId()), null,
          memberToBuckets.get(recip), execution.isReExecute(),
          execution.isFnSerializationReqd(), tx);
      recipMap.put(recip, context);
    }
    final LocalResultCollector<?, ?> localResultCollector = execution
        .getLocalResultCollector(function, rc);
    final DM dm = getDistributionManager();
    final PartitionedRegionFunctionResultSender resultSender = new PartitionedRegionFunctionResultSender(
        dm, tx, this, 0L, localResultCollector, execution
            .getServerResultSender(), recipMap.isEmpty(), !isSelf, execution.isForwardExceptions(), function, localBucketSet);

    // execute locally and collect the result
    if (isSelf && this.dataStore != null) {
      final RegionFunctionContextImpl prContext = new RegionFunctionContextImpl(
          function.getId(), PartitionedRegion.this, execution
              .getArgumentsForMember(getMyId().getId()), null,
          ColocationHelper.constructAndGetAllColocatedLocalDataSet(
              PartitionedRegion.this, localBucketSet, tx), localBucketSet,
              resultSender, execution.isReExecute());
      execution.executeFunctionOnLocalPRNode(function, prContext, resultSender,
          dm, tx);
    }
    PartitionedRegionFunctionResultWaiter resultReciever = new PartitionedRegionFunctionResultWaiter(
        getSystem(), this.getPRId(), localResultCollector, function, resultSender);

    ResultCollector reply = resultReciever.getPartitionedDataFrom(recipMap,
        this, execution);

    return reply;
  }

  /**
   * no docs
   * @param preferCD 
   * @param requestingClient the client requesting the object, or null if not from a client
   * @param clientEvent TODO
   * @param returnTombstones TODO
   */
  private Object getFromBucket(final InternalDistributedMember targetNode,
      int bucketId, final Object key, final Object aCallbackArgument,
      boolean disableCopyOnRead, boolean preferCD,
      ClientProxyMembershipID requestingClient, final TXStateInterface tx,
      final TXStateInterface lockState, final EntryEventImpl clientEvent,
      boolean returnTombstones, boolean allowReadFromHDFS) {
    final int retryAttempts = calcRetry();
    Object obj;
    // retry the get remotely until it finds the right node managing the bucket
    int count = 0;
    RetryTimeKeeper retryTime = null;
    InternalDistributedMember retryNode = targetNode;
    while (count <= retryAttempts) {
      // Every continuation should check for DM cancellation
      if (retryNode == null) {
        checkReadiness();
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        retryNode = getNodeForBucketReadOrLoad(bucketId);

        // No storage found for bucket, early out preventing hot loop, bug 36819
        if (retryNode == null) {
          checkShutdown();
          return null;
        }
        continue;
      }
      final boolean isLocal = this.localMaxMemory != 0 && retryNode.equals(getMyId());
      
      try {
        if (isLocal) {
          obj = this.dataStore.getLocally(bucketId, key, aCallbackArgument,
              disableCopyOnRead, preferCD, requestingClient, tx, lockState,
              clientEvent, returnTombstones, allowReadFromHDFS);
        }
        else {
          try {
            if (localCacheEnabled && null != (obj = localCacheGet(key))) { // OFFHEAP: copy into heap cd; TODO optimize for preferCD case
              if (logger.finerEnabled()) {
                logger.finer("getFromBucket: Getting key " + key + " ("
                    + key.hashCode() + ") from local cache");
              }
              return obj;
            }
            else if (this.haveCacheLoader || this.hdfsStoreName != null) {
              // If the region has a cache loader or if the region has a HDFS store, 
              // the target node is the primary server of the bucket. But, if the 
              // value can be found in a local bucket, we should first try there.
              // TODO HDFS re-enable this later
              /*if (null != (obj = getFromLocalBucket(bucketId, key,
                  aCallbackArgument, disableCopyOnRead, preferCD,
                  requestingClient, tx, lockState, clientEvent,
                  returnTombstones))) {
                return obj;
              }*/
            }
          }
          catch (Exception e) {
            if (logger.fineEnabled()) {
              logger.fine("getFromBucket: Can not get value for key = " + key
                  + " from local cache.", e);
            }
          }

          //  Test hook
          if (((LocalRegion)this).isTest())
            ((LocalRegion)this).incCountNotFoundInLocal();

          obj = getRemotely(retryNode, tx, bucketId, key, aCallbackArgument,
              preferCD, requestingClient, clientEvent, returnTombstones, allowReadFromHDFS);

          // TODO:Suranjan&Yogesh : there should be better way than this one
          String name = Thread.currentThread().getName();
          if (name.startsWith("ServerConnection")
              && !getMyId().equals(retryNode)) {
            setNetworkHop(bucketId, retryNode);
          }
        }
        return obj;
      }
      catch (PRLocallyDestroyedException pde) {
        if (logger.fineEnabled()) {
          logger.fine("getFromBucket Encountered PRLocallyDestroyedException",
              pde);
        }
        checkReadiness();
        retryNode = getNodeForBucketReadOrLoad(bucketId);
      }
      catch (ForceReattemptException prce) {
        prce.checkKey(key);
        if (logger.fineEnabled()) {
          logger.fine("getFromBucket: retry attempt: " + count + " of "
              + retryAttempts, logger.finerEnabled() ? prce : null);
        }
        checkReadiness();

        InternalDistributedMember lastNode = retryNode;
        retryNode = getNodeForBucketReadOrLoad(bucketId);
        if (lastNode.equals(retryNode)) {
          if (retryTime == null) {
            retryTime = new RetryTimeKeeper(this.retryTimeout);
          }
          if (retryTime.overMaximum()) {
            break;
          }
          if (logger.fineEnabled()) {
            logger.fine("waiting to retry node " + retryNode);
          }
          retryTime.waitToRetryNode();
        }
      }
      catch (PrimaryBucketException notPrimary) {
        if (logger.fineEnabled()) {
          logger.fine("getFromBucket: " + notPrimary.getLocalizedMessage()
              + " on Node " + retryNode + " not primary ");
        }
        getRegionAdvisor().notPrimary(bucketId, retryNode);
        retryNode = getNodeForBucketReadOrLoad(bucketId);

      }

      // It's possible this is a GemFire thread e.g. ServerConnection
      // which got to this point because of a distributed system shutdown or
      // region closure which uses interrupt to break any sleep() or wait()
      // calls
      // e.g. waitForPrimary
      checkShutdown();

      count++;
      if (count == 1) {
        this.prStats.incGetOpsRetried();
      }
      this.prStats.incGetRetries();
      if (logger.fineEnabled()) {
        logger.fine("getFromBucket: Attempting to resend get to node "
            + retryNode + " after " + count + " failed attempts");
      }
    } // While
    
    PartitionedRegionDistributionException e = null; // Fix for bug 36014
    if (logger.fineEnabled()) {
      e = new PartitionedRegionDistributionException(LocalizedStrings.PartitionRegion_NO_VM_AVAILABLE_FOR_GET_IN_0_ATTEMPTS.toLocalizedString(Integer.valueOf(count)));
    }
    logger.warning(LocalizedStrings.PartitionRegion_NO_VM_AVAILABLE_FOR_GET_IN_0_ATTEMPTS, Integer.valueOf(count), e);
    return null;
  }

  /**
   * This invokes a cache writer before a destroy operation. Although it has the
   * same method signature as the method in LocalRegion, it is invoked in a
   * different code path. LocalRegion invokes this method via its "entries"
   * member, while PartitionedRegion invokes this method in its region operation
   * methods and messages.
   * 
   * @see LocalRegion#cacheWriteBeforeRegionDestroy(RegionEventImpl)
   */
  @Override
  boolean cacheWriteBeforeRegionDestroy(RegionEventImpl event)
      throws CacheWriterException, TimeoutException {

    if (event.getOperation().isDistributed()) {
      serverRegionDestroy(event);
      CacheWriter localWriter = basicGetWriter();
      Set netWriteRecipients = localWriter == null ? this.distAdvisor
          .adviseNetWrite() : null;

      if (localWriter == null
          && (netWriteRecipients == null || netWriteRecipients.isEmpty())) {
        // getSystem().getLogWriter().finer("regionDestroy empty set returned by
        // advisor.adviseNetWrite in netWrite");
        return false;
      }

      final long start = getCachePerfStats().startCacheWriterCall();
      try {
        SearchLoadAndWriteProcessor processor = SearchLoadAndWriteProcessor
            .getProcessor();
        processor.initialize(this, "preDestroyRegion", null);
        processor.doNetWrite(event, netWriteRecipients, localWriter,
            SearchLoadAndWriteProcessor.BEFOREREGIONDESTROY);
        processor.release();
      }
      finally {
        getCachePerfStats().endCacheWriterCall(start);
      }
      return true;
    }
    return false;
  }

  /**
   * Test Method: Get the DistributedMember identifier for the vm containing a
   * key
   * 
   * @param key
   *                the key to look for
   * @return The ID of the DistributedMember holding the key, or null if there
   *         is no current mapping for the key
   */
  public DistributedMember getMemberOwning(Object key) {
    int bucketId = PartitionedRegionHelper.getHashKey(this, null, key, null, null);
    InternalDistributedMember targetNode = getNodeForBucketRead(bucketId);
    return targetNode;
  }

  /**
   * Test Method: Investigate the local cache to determine if it contains a the
   * key
   * 
   * @param key
   *                The key
   * @return true if the key exists
   * @see LocalRegion#containsKey(Object)
   */
  public boolean localCacheContainsKey(Object key) {
    return getRegionMap().containsKey(key);
  }

  /**
   * Test Method: Fetch a value from the local cache
   * 
   * @param key
   *                The kye
   * @return the value associated with that key
   * @see LocalRegion#get(Object, Object, boolean, EntryEventImpl)
   */
  public Object localCacheGet(Object key) {
    RegionEntry re = getRegionMap().getEntry(key);
    if (re == null || re.isDestroyedOrRemoved()) {
    // TODO:KIRK:OK if (re == null || Token.isRemoved(re.getValueInVM(this))) {
      return null;
    } else {
      return re.getValue(this); // OFFHEAP: spin until we can copy into a heap cd?
    }
  }

  /**
   * Test Method: Fetch the local cache's key set
   * 
   * @return A set of keys
   * @see LocalRegion#keys()
   */
  public Set localCacheKeySet() {
    return super.keys();
  }

  /**
   * Test Method: Get a random set of keys from a randomly selected bucket using
   * the provided <code>Random</code> number generator.
   * 
   * @param rnd
   * @return A set of keys from a randomly chosen bucket or
   *         {@link Collections#EMPTY_SET}
   * @throws IOException
   * @throws ClassNotFoundException
   */
  public Set getSomeKeys(Random rnd) throws IOException,
  ClassNotFoundException {
    InternalDistributedMember nod = null;
    Integer buck = null;
    Set buks = getRegionAdvisor().getBucketSet();

    if (buks != null && !buks.isEmpty()) {
      Object[] buksA = buks.toArray();
      Set ret = null;
      // Randomly pick a node to get some data from
      for (int i = 0; i < buksA.length; i++) {
        try {
          if (logger.fineEnabled()) {
            logger.fine("getSomeKeys: iteration: " + i);
          }
          int ind = rnd.nextInt(buksA.length);
          if (ind >= buksA.length) {
            // The GSRandom.nextInt(int) may return a value that includes the
            // maximum.
            ind = buksA.length - 1;
          }
          buck = (Integer)buksA[ind];

          nod = getNodeForBucketRead(buck.intValue());
          if (nod != null) {
            if (logger.fineEnabled()) {
              logger.fine("getSomeKeys: iteration: " + i + " got node: " + nod);
            }
            if (nod.equals(getMyId())) {
              ret = dataStore.handleRemoteGetKeys(buck,
                  InterestType.REGULAR_EXPRESSION, ".*", false);
            }
            else {
              final TXStateInterface tx = getTXState();
              FetchKeysResponse r = FetchKeysMessage.send(nod, this, tx,
                  buck, false);
              ret = r.waitForKeys();
            }

            if (ret != null && !ret.isEmpty()) {
              return ret;
            }
          }
        } catch (ForceReattemptException movinOn) {
          checkReadiness();
          if (logger.fineEnabled()) {
            this.logger.fine(
                "Test hook getSomeKeys caught a ForceReattemptException for bucketId="
                    + bucketStringForLogs(buck.intValue())
                    + ". Moving on to another bucket", movinOn);
          }
          continue;
        } catch (PRLocallyDestroyedException pde) {
          if (logger.fineEnabled()) {
            logger
            .fine("getSomeKeys: Encountered PRLocallyDestroyedException");
          }
          checkReadiness();
          continue;
        }

      } // nod != null
    } // for
    if (logger.fineEnabled()) {
      logger.fine("getSomeKeys: no keys found returning empty set");
    }
    return Collections.EMPTY_SET;
  }

  /**
   * Test Method: Get all entries for all copies of a bucket
   * 
   * This method will not work correctly if membership in the distributed
   * system changes while the result is being calculated.
   * 
   * @return a List of HashMaps, each map being a copy of the entries in a
   *         bucket
   */
  public List<BucketDump> getAllBucketEntries(final int bucketId)
      throws ForceReattemptException {
//    PartitionedRegion.getLogWriter().info(
//        "TESTHOOK:getAllBucketEntries ownersI.size()="+owners.size());
    if (bucketId >= getTotalNumberOfBuckets()) {
      return Collections.EMPTY_LIST;
    }
    ArrayList<BucketDump> ret = new ArrayList<BucketDump>();
    HashSet<InternalDistributedMember> collected = new HashSet<InternalDistributedMember>();
    for (;;) {
      // Collect all the candidates by re-examining the advisor...
      Set<InternalDistributedMember> owners = getRegionAdvisor().getBucketOwners(bucketId);
      // Remove ones we've already polled...
      owners.removeAll(collected);
      
      // Terminate if no more entries
      if (owners.isEmpty()) {
        break;
      }
      // Get first entry
      Iterator<InternalDistributedMember> ownersI = owners.iterator();
      InternalDistributedMember owner = ownersI.next();
      // Remove it from our list
      collected.add(owner);
      
      // If it is ourself, answer directly
      if (owner.equals(getMyId())) {
        BucketRegion br = this.dataStore.handleRemoteGetEntries(bucketId);
        Map<Object, Object> m = new HashMap<Object, Object>() {
          private static final long serialVersionUID = 0L;

          @Override
          public String toString() {
            return "Bucket id = " + bucketId + " from local member = "
                + getDistributionManager().getDistributionManagerId() + ": "
                + super.toString();
          }
        };
        
        Map<Object, VersionTag> versions = new HashMap<Object, VersionTag>();

        for (Iterator<Map.Entry> it=br.entrySet().iterator(); it.hasNext(); ) {
          LocalRegion.NonTXEntry entry = (LocalRegion.NonTXEntry)it.next();
          RegionEntry re = entry.getRegionEntry();
          Object value = re.getValue(br); // OFFHEAP: incrc, deserialize, decrc
          VersionStamp versionStamp = re.getVersionStamp();
          VersionTag versionTag = versionStamp != null ? versionStamp.asVersionTag() : null;
          if(versionTag != null) {
            versionTag.replaceNullIDs(br.getVersionMember());
          }
          if (Token.isRemoved(value)) {
            continue;
          }
          else if (Token.isInvalid(value)) {
            value = null;
          }
          else if (value instanceof CachedDeserializable) { 
            value = ((CachedDeserializable)value).getDeserializedForReading();
          }
          final Object key = re.getKeyCopy();
          m.put(key, value);
          versions.put(key, versionTag);
        }
        RegionVersionVector rvv = br.getVersionVector();
        rvv = rvv != null ? rvv.getCloneForTransmission() : null;
        ret.add(new BucketDump(bucketId, owner, rvv, m, versions));
        continue;
      }
      
      // Send a message
      try {
        final FetchEntriesResponse r;
        r = FetchEntriesMessage.send(owner,
            this, null, bucketId);
        ret.add(r.waitForEntries());
      }
      catch (ForceReattemptException e) {
        // node has departed?  Ignore.
      }
    } // for
    
    return ret;
  }

  /**
   * Fetch the keys for the given bucket identifier, if the bucket is local or
   * remote.
   * 
   * @param bucketNum
   * @return A set of keys from bucketNum or {@link Collections#EMPTY_SET}if no
   *         keys can be found.
   */
  public Set getBucketKeys(int bucketNum) {
    return getBucketKeys(bucketNum, false);
  }

  /**
   * Fetch the keys for the given bucket identifier, if the bucket is local or
   * remote.  This version of the method allows you to retrieve Tombstone entries
   * as well as undestroyed entries.
   * 
   * @param bucketNum
   * @param allowTombstones whether to include destroyed entries in the result
   * @return A set of keys from bucketNum or {@link Collections#EMPTY_SET}if no
   *         keys can be found.
   */
  public Set getBucketKeys(int bucketNum, boolean allowTombstones) {
    Integer buck = Integer.valueOf(bucketNum);
    final int retryAttempts = calcRetry();
    Set ret = null;
    int count = 0;
    InternalDistributedMember nod = getOrCreateNodeForBucketRead(bucketNum);
    RetryTimeKeeper snoozer = null;
    while (count <= retryAttempts) {
      // It's possible this is a GemFire thread e.g. ServerConnection
      // which got to this point because of a distributed system shutdown or
      // region closure which uses interrupt to break any sleep() or wait()
      // calls
      // e.g. waitForPrimary or waitForBucketRecovery
      checkShutdown();

      if (nod == null) {
        if (snoozer == null) {
          snoozer = new RetryTimeKeeper(this.retryTimeout);
        }
        nod = getOrCreateNodeForBucketRead(bucketNum);

        // No storage found for bucket, early out preventing hot loop, bug 36819
        if (nod == null) {
          checkShutdown();
          break;
        }
        count++;
        continue;
      }

      try {
        if (nod.equals(getMyId())) {
          ret = this.dataStore.getKeysLocally(buck, allowTombstones);
        }
        else {
          final TXStateInterface tx = getTXState();
          FetchKeysResponse r = FetchKeysMessage.send(nod, this, tx,
              buck, allowTombstones);
          ret = r.waitForKeys();
        }
        if (ret != null) {
          return ret;
        }
      }
      catch (PRLocallyDestroyedException pde) {
        if (logger.fineEnabled()) {
          logger.fine("getBucketKeys: Encountered PRLocallyDestroyedException");
        }
        checkReadiness();
      }
      catch (ForceReattemptException prce) {
        if (logger.fineEnabled()) {
          logger.fine("getBucketKeys: attempt:" + (count + 1), logger.finerEnabled() ? prce : null);
        }
        checkReadiness();
        if (snoozer == null) {
          snoozer = new RetryTimeKeeper(this.retryTimeout);
        }
        InternalDistributedMember oldNode = nod;
        nod = getNodeForBucketRead(buck.intValue());
        if (nod != null && nod.equals(oldNode)) {
          if (snoozer.overMaximum()) {
            checkReadiness();
            throw new TimeoutException(LocalizedStrings.PartitionedRegion_ATTEMPT_TO_ACQUIRE_PRIMARY_NODE_FOR_READ_ON_BUCKET_0_TIMED_OUT_IN_1_MS.toLocalizedString(new Object[] {getBucketName(buck.intValue()), Integer.valueOf(snoozer.getRetryTime())}));
          }
          snoozer.waitToRetryNode();
        }

      }
      count++;
    }
    if (logger.fineEnabled()) {
      logger.fine("getBucketKeys: no keys found returning empty set");
    }
    return Collections.EMPTY_SET;
  }

  // /**
  // * Fetch all {@link InternalDistributedMember}s hosting a bucket using the
  // * bucket2Node region
  // *
  // * @return the HashSet of unique Members hosting buckets
  // */
  // private HashSet getAllBucketDistributedMembers()
  // {
  // return getAllBucketNodes(true);
  // }

  /**
   * Test Method: Get all {@link InternalDistributedMember}s known by this
   * instance of the PartitionedRegion. Note: A member is recognized as such
   * when it partiticpates as a "data store".
   * 
   * @return a <code>HashSet</code> of {@link InternalDistributedMember}s or
   *         an empty <code>HashSet</code>
   */
  public Set<InternalDistributedMember> getAllNodes() {
    Set<InternalDistributedMember> result = getRegionAdvisor().adviseDataStore(true);
    if(this.isDataStore()) {
      result.add(getDistributionManager().getId());
    }
    return result;
  }

  /**
   * Test Method: Get the number of entries in the local data store.
   */
  public long getLocalSize() {
    if (this.dataStore == null) {
      return 0L;
    }
    long ret = 0L;
    Integer i;
    for (Iterator si = this.dataStore.getSizeLocally().values().iterator(); si
        .hasNext();) {
     i = (Integer)si.next(); 
     ret += i.intValue();
    }
    return ret;
  }

  /**
   * Gets the remote object with the given key.
   * 
   * @param targetNode
   *                the Node hosting the key
   * @param tx
   *                the TX state for current operation
   * @param bucketId
   *                the id of the bucket the key hashed into
   * @param key
   *                the key
   * @param requestingClient the client that made this request
   * @param clientEvent client event for carrying version information.  Null if not a client operation
   * @param returnTombstones TODO
   * @param allowReadFromHDFS 
   * @return the value
   * @throws PrimaryBucketException
   *                 if the peer is no longer the primary
   * @throws ForceReattemptException
   *                 if the peer is no longer available
   */
  public Object getRemotely(InternalDistributedMember targetNode,
      final TXStateInterface tx, int bucketId, final Object key,
      final Object aCallbackArgument, boolean preferCD,
      ClientProxyMembershipID requestingClient, EntryEventImpl clientEvent,
      boolean returnTombstones, boolean allowReadFromHDFS) throws PrimaryBucketException,
      ForceReattemptException {
    Object value;
    if (logger.fineEnabled()) {
      logger.fine("PartitionedRegion#getRemotely: getting value from bucketId="
          + bucketStringForLogs(bucketId) + " for key " + key);
    }

    GetResponse response = GetMessage.send(targetNode, this, tx, key,
        aCallbackArgument, requestingClient, returnTombstones, allowReadFromHDFS);
    this.prStats.incPartitionMessagesSent();
    value = response.waitForResponse(preferCD);
    if (clientEvent != null) {
      clientEvent.setVersionTag(response.getVersionTag());
    }
    if (logger.fineEnabled()) {
      logger.fine("getRemotely: got value " + value + " for key " + key);
    }

    // Here even if we can not cache the value, it should return value to
    // user.
    try {
      if (localCacheEnabled && value != null) {
        super.put(key, value);
      }
    }
    catch (Exception e) {
      if (logger.fineEnabled()) {
        logger.fine("getRemotely: Can not cache value = " + value
            + " for key = " + key + " in local cache", e);
      }
    }
    return value;
  }
  
  private ResultCollector executeFunctionOnRemoteNode(
      InternalDistributedMember targetNode, final Function function,
      final Object object, final Set routingKeys, ResultCollector rc,
      Set bucketSet, ServerToClientFunctionResultSender sender,
      AbstractExecution execution, TXStateInterface tx) {
    PartitionedRegionFunctionResultSender resultSender = new PartitionedRegionFunctionResultSender(
        null, tx, this, 0, rc, sender, false, true,
        execution.isForwardExceptions(), function, bucketSet);

    PartitionedRegionFunctionResultWaiter resultReciever = new PartitionedRegionFunctionResultWaiter(
        getSystem(), this.getPRId(), rc, function, resultSender);

    FunctionRemoteContext context = new FunctionRemoteContext(function, object,
        routingKeys, bucketSet, execution.isReExecute(),
        execution.isFnSerializationReqd(), tx);

    HashMap<InternalDistributedMember, FunctionRemoteContext> recipMap = 
      new HashMap<InternalDistributedMember, FunctionRemoteContext>();

    recipMap.put(targetNode, context);
    ResultCollector reply = resultReciever.getPartitionedDataFrom(recipMap,
        this, execution);
    
    return reply;
  }
  
  /**
   * This method returns Partitioned Region data store associated with this
   * Partitioned Region
   * 
   * @return PartitionedRegionDataStore
   */
  public final PartitionedRegionDataStore getDataStore() {
    return this.dataStore;
  }

  /**
   * Grab the PartitionedRegionID Lock, this MUST be done in a try block since
   * it may throw an exception
   * 
   * @return true if the lock was acquired
   */
  private static boolean grabPRIDLock(final DistributedLockService lockService,
                                      LogWriterI18n logger) {
    boolean ownership = false;
    int n = 0;
    while (!ownership) {
      if (logger.fineEnabled()) {
        logger.fine("grabPRIDLock: "
            + "Trying to get the dlock in allPartitionedRegions for "
            + PartitionedRegionHelper.MAX_PARTITIONED_REGION_ID + ": "
            + (n + 1));
      }
//      try {
        ownership = lockService.lock(
            PartitionedRegionHelper.MAX_PARTITIONED_REGION_ID,
            VM_OWNERSHIP_WAIT_TIME, -1);
//      }
//      catch (InterruptedException ie) {
//        Thread.currentThread().interrupt();
//        if (ownership) {
//          lockService.unlock(PartitionedRegionHelper.MAX_PARTITIONED_REGION_ID);
//        }
//        logger
//            .fine(
//                "InterruptedException encountered getting the MAX_PARTITIONED_REGION_ID",
//                ie);
//        throw new PartitionedRegionException(LocalizedStrings.PartitionedRegion_INTERRUPTEDEXCEPTION_ENCOUNTERED_GETTING_THE_MAX_PARTITIONED_REGION_ID.toLocalizedString(), ie);
//      }
    }
    return ownership;
  }

  private static void releasePRIDLock(final DistributedLockService lockService,
                                      LogWriterI18n logger) {
    try {
      lockService.unlock(PartitionedRegionHelper.MAX_PARTITIONED_REGION_ID);
      if (logger.fineEnabled()) {
        logger
            .fine("releasePRIDLock: Released the dlock in allPartitionedRegions for "
                + PartitionedRegionHelper.MAX_PARTITIONED_REGION_ID);
      }
    }
    catch (Exception es) {
      logger.warning(
          LocalizedStrings.PartitionedRegion_RELEASEPRIDLOCK_UNLOCKING_0_CAUGHT_AN_EXCEPTION,
         Integer.valueOf(PartitionedRegionHelper.MAX_PARTITIONED_REGION_ID), es);
    }
  }

  /**
   * generates new partitioned region ID globally.
   */
  // !!!:ezoerner:20080321 made this function public and static.
  // @todo should be moved to the Distributed System level as a general service
  // for getting a unique id, with different "domains" for different
  // contexts
  // :soubhik:pr_func merge20914:21056: overloaded static and non-static version of generatePRId.
  //   static version is used mainly with gfxd & non-static in gfe.
  public static int generatePRId(final GemFireCacheImpl cache) {
    if (cache != null) {
      return _generatePRId(cache.getDistributedSystem(),
          cache.getPartitionedRegionLockService());
    }
    return 0;
  }

  public int generatePRId(InternalDistributedSystem sys) {
    final DistributedLockService lockService = getPartitionedRegionLockService();
    return _generatePRId(sys, lockService);
  }
  
  private static int _generatePRId(InternalDistributedSystem sys, DistributedLockService lockService) {
    boolean ownership = false;
    LogWriterI18n logger = sys.getLogWriterI18n();
    int prid = 0;

    try {
      ownership = grabPRIDLock(lockService, logger);
      if (ownership) {
        if (logger.fineEnabled()) {
          logger.fine("generatePRId: Got the dlock in allPartitionedRegions for "
                  + PartitionedRegionHelper.MAX_PARTITIONED_REGION_ID);
        }

        Set parMembers = sys.getDistributionManager()
            .getOtherDistributionManagerIds();

        Integer currentPRID;

        IdentityResponse pir = IdentityRequestMessage.send(parMembers, sys);
        currentPRID = pir.waitForId();

        if (currentPRID == null) {
          currentPRID = Integer.valueOf(0);
        }
        prid = currentPRID.intValue() + 1;
        currentPRID = Integer.valueOf(prid);

        try {
          IdentityUpdateResponse pr =
            IdentityUpdateMessage.send(parMembers,
                                       sys,
                                       currentPRID.intValue());
          pr.waitForRepliesUninterruptibly();
        }
        catch (ReplyException ignore) {
          if (logger.fineEnabled()) {
            logger.fine("generatePRId: Ignoring exception", ignore);
          }
        }
      }
    }
    finally {
      if (ownership) {
        releasePRIDLock(lockService, logger);
      }
    }
    return prid;
  }

  public final DistributionAdvisor getDistributionAdvisor() {
    return this.distAdvisor;
  }

  public final CacheDistributionAdvisor getCacheDistributionAdvisor()
  {
    return this.distAdvisor;
  }

  public final RegionAdvisor getRegionAdvisor() {
    return this.distAdvisor;
  }

  /** Returns the distribution profile; lazily creates one if needed */
  public Profile getProfile() {
    return this.distAdvisor.createProfile();
  }

  public void fillInProfile(Profile p) {
    CacheProfile profile = (CacheProfile)p;
    // set fields on CacheProfile...
    profile.isPartitioned = true;
    profile.isPersistent = dataPolicy.withPersistence();
    profile.dataPolicy = getDataPolicy();
    profile.hasCacheLoader = basicGetLoader() != null;
    profile.hasCacheWriter = basicGetWriter() != null;
    profile.hasCacheListener = hasListener();
    Assert.assertTrue(getScope().isDistributed());
    profile.scope = getScope();
    profile.setSubscriptionAttributes(getSubscriptionAttributes());
    profile.isGatewayEnabled = this.enableGateway;
    // fillInProfile MUST set serialNumber
    profile.serialNumber = getSerialNumber();
    
    //TODO - prpersist - this is a bit of a hack, but we're 
    //reusing this boolean to indicate that this member has finished disk recovery.
    profile.regionInitialized = recoveredFromDisk;
    
    profile.hasCacheServer = (this.cache.getCacheServers().size() > 0);
    profile.filterProfile = getFilterProfile();
    profile.gatewaySenderIds = getGatewaySenderIds();
    profile.asyncEventQueueIds = getAsyncEventQueueIds();
    profile.activeSerialGatewaySenderIds = getActiveSerialGatewaySenderIds();
    profile.activeSerialAsyncQueueIds = getActiveSerialAsyncQueueIds();

    if (dataPolicy.withPersistence()) {
      profile.persistentID = getDiskStore().generatePersistentID(null);
    }

    fillInProfile((PartitionProfile) profile);
    
    profile.isOffHeap = getEnableOffHeapMemory();
    profile.hasDBSynchOrAsyncEventListener = hasDBSynchOrAsyncListener();
  }

  /** set fields that are only in PartitionProfile... */
  public void fillInProfile(PartitionProfile profile) {
    // both isDataStore and numBuckets are not required for sending purposes,
    // but nice to have for toString debugging
    profile.isDataStore = getLocalMaxMemory() > 0;
    if (this.dataStore != null) {
      profile.numBuckets = this.dataStore.getBucketsManaged();
    }

    profile.requiresNotification = this.requiresNotification;
    profile.localMaxMemory = getLocalMaxMemory();
    profile.fixedPAttrs = this.fixedPAttrs;
    // shutdownAll
    profile.shutDownAllStatus = shutDownAllStatus;
  }

  @Override
  public void initialized() {
    // PartitionedRegions do not send out a profile at the end of
    // initialization.  It is not currently needed by other members
    // since no GII is done on a PartitionedRegion
  }

  @Override
  public void setProfileExchanged(boolean value) {
    this.profileExchanged = value;
  }

  @Override
  public boolean isProfileExchanged() {
    return this.profileExchanged;
  }

  @Override
  protected void cacheListenersChanged(boolean nowHasListener) {
    if (nowHasListener) {
      this.advisorListener.initRMLWrappers();
    }
    new UpdateAttributesProcessor(this).distribute();
  }

  // propagate the new writer to the data store
  @Override
  protected void cacheWriterChanged(CacheWriter p_oldWriter) {
    CacheWriter oldWriter = p_oldWriter;
    super.cacheWriterChanged(oldWriter);
    if (isBridgeWriter(oldWriter)) {
      oldWriter = null;
    }
    if (oldWriter == null ^ basicGetWriter() == null) {
      new UpdateAttributesProcessor(this).distribute();
    }
    updatePRConfigForWriterLoaderChange();
  }

  // propagate the new loader to the data store
  @Override
  protected void cacheLoaderChanged(CacheLoader oldLoader) {
    CacheLoader myOldLoader = oldLoader;
    if (isBridgeLoader(oldLoader)) {
      myOldLoader = null;
    }
    PartitionedRegionDataStore ds = this.dataStore;
    if (ds != null) {
      ds.cacheLoaderChanged(basicGetLoader(), myOldLoader);
    }
    super.cacheLoaderChanged(oldLoader);
    if (myOldLoader == null ^ basicGetLoader() == null) {
      new UpdateAttributesProcessor(this).distribute();
    }
    updatePRConfigForWriterLoaderChange();
  }

  /**
   * This method returns PartitionedRegion associated with a PartitionedRegion
   * ID from prIdToPR map.
   * 
   * @param prid
   *                Partitioned Region ID
   * @return PartitionedRegion
   */
  public static PartitionedRegion getPRFromId(int prid)
      throws PRLocallyDestroyedException {
    final Object o;
    synchronized (prIdToPR) {
      o = prIdToPR.getRegion(Integer.valueOf(prid));
    }
    return (PartitionedRegion)o;
  }

  /**
   * Verify that the given prId is correct for the given region name in this vm
   * 
   * @param sender
   *                the member requesting validation
   * @param prId
   *                the ID being used for the pr by the sender
   * @param regionId
   *                the regionIdentifier used for prId by the sender
   * @param log
   *                a logwriter used for logging validation warnings
   */
  public static void validatePRID(InternalDistributedMember sender, int prId,
      String regionId, LogWriterI18n log) {
    try {
      PartitionedRegion pr = null;
      synchronized (prIdToPR) {
        // first do a quick probe
        pr = (PartitionedRegion)prIdToPR.getRegion(Integer.valueOf(prId));
      }
      if (pr != null && !pr.isLocallyDestroyed && 
          pr.getRegionIdentifier().equals(regionId)) {
        return;
      }
    }
    catch (RegionDestroyedException e) {
      // ignore and do full pass over prid map
    }
    catch (PartitionedRegionException e) {
      // ditto
    }
    catch (PRLocallyDestroyedException e) {
      // ignore and do full check
    }
    synchronized(prIdToPR) {
      for (Iterator it = prIdToPR.values().iterator(); it.hasNext();) {
        Object o = it.next();
        if (o instanceof String) {
          continue;
        }
        PartitionedRegion pr = (PartitionedRegion)o;
        if (pr.getPRId() == prId) {
          if (!pr.getRegionIdentifier().equals(regionId)) {
            log.warning(
                LocalizedStrings.PartitionedRegion_0_IS_USING_PRID_1_FOR_2_BUT_THIS_PROCESS_MAPS_THAT_PRID_TO_3,
                new Object[] {sender.toString(), Integer.valueOf(prId), pr.getRegionIdentifier()});
          }
        }
        else if (pr.getRegionIdentifier().equals(regionId)) {
          log.warning(
              LocalizedStrings.PartitionedRegion_0_IS_USING_PRID_1_FOR_2_BUT_THIS_PROCESS_IS_USING_PRID_3,
              new Object[] {sender, Integer.valueOf(prId), pr.getRegionIdentifier(), Integer.valueOf(pr.getPRId())});
        }
      }
    }
    
  }

  public static String dumpPRId() {
    return prIdToPR.dump();
  }

  public String dumpAllPartitionedRegions() {
    StringBuffer b = new StringBuffer(this.prRoot.getFullPath());
    b.append("\n");
    Object key = null;
    for (Iterator i = this.prRoot.keySet().iterator(); i.hasNext();) {
      key = i.next();
      b.append(key).append("=>").append(this.prRoot.get(key));
      if (i.hasNext()) {
        b.append("\n");
      }
    }
    return b.toString();
  }

  /**
   * This method returns prId
   * 
   * @return partitionedRegionId
   */
  public final int getPRId() {
    return this.partitionedRegionId;
  }

  /**
   * Updates local cache with a new value.
   * 
   * @param key
   *                the key
   * @param value
   *                the value
   * @param newVersion
   *                the new version of the key
   */
  void updateLocalCache(Object key, Object value, long newVersion) {

  }

  /**
   * This method returns total number of buckets for this PR
   * 
   * @return totalNumberOfBuckets
   */
  public final int getTotalNumberOfBuckets() {
    return this.totalNumberOfBuckets;
  }

  // /////////////////////////////////////////////////////////////////////
  // ////////////////////////// destroy method changes //
  // ///////////////////////////
  // /////////////////////////////////////////////////////////////////////

  @Override
  void basicDestroy(final EntryEventImpl event,
                       final boolean cacheWrite,
                       final Object expectedOldValue)
  throws TimeoutException, EntryNotFoundException, CacheWriterException {
    
    final long startTime = PartitionedRegionStats.startTime();
    try {
      if (event.getEventId() == null) {
        event.setNewEventId(this.cache.getDistributedSystem());
      }
      final TXStateInterface tx = discoverJTA(event);
      getDataView(tx).destroyExistingEntry(event, cacheWrite, expectedOldValue);
    }
    catch (RegionDestroyedException rde) {
      if (!rde.getRegionFullPath().equals(getFullPath())) {
        // Handle when a bucket is destroyed
        RegionDestroyedException rde2 = new RegionDestroyedException(toString(), getFullPath());
        rde2.initCause(rde);
        throw rde2;
      }
    }
    finally {
      this.prStats.endDestroy(startTime);
    }
    return;
  }

  /**
   * 
   * @param event
   * @param expectedOldValue only succeed if current value is equal to expectedOldValue
   * @throws EntryNotFoundException if entry not found or if expectedOldValue
   *         not null and current value was not equal to expectedOldValue
   * @throws CacheWriterException
   */
  public void destroyInBucket(final EntryEventImpl event, Object expectedOldValue)
      throws EntryNotFoundException, CacheWriterException {
    // Get the bucket id for the key
    final Integer bucketId = event.getBucketId();
    assert bucketId != KeyInfo.UNKNOWN_BUCKET;
    // check in bucket2Node region
    final InternalDistributedMember targetNode = getOrCreateNodeForBucketWrite(
        bucketId, null);

    if (logger.fineEnabled()) {
      logger.fine("destroyInBucket: key=" + event.getKey() + " ("
          + event.getKey().hashCode() + ") in node " + targetNode 
          + " to bucketId=" + bucketStringForLogs(bucketId.intValue()) 
          + " retry=" + retryTimeout + " ms");
    }

    // retry the put remotely until it finds the right node managing the bucket
    RetryTimeKeeper retryTime = null;
    InternalDistributedMember currentTarget = targetNode;
    long timeOut = 0;
    int count = 0;
    for (;;) {
      switch (count) {
      case 0:
        // Note we don't check for DM cancellation in common case.
        // First time, keep going
        break;
      case 1:
        // First failure
        this.cache.getCancelCriterion().checkCancelInProgress(null);
        timeOut = System.currentTimeMillis() + this.retryTimeout;
        break;
      default:
        this.cache.getCancelCriterion().checkCancelInProgress(null);
        // test for timeout
        long timeLeft = timeOut - System.currentTimeMillis();
        if (timeLeft < 0) {
          PRHARedundancyProvider.timedOut(this, null, null, "destroy an entry", this.retryTimeout);
          // NOTREACHED
        }
        
        // Didn't time out.  Sleep a bit and then continue
        boolean interrupted = Thread.interrupted();
        try {
          Thread.sleep(PartitionedRegionHelper.DEFAULT_WAIT_PER_RETRY_ITERATION);
        }
        catch (InterruptedException e) {
          interrupted = true;
        }
        finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      break;
      }
      count ++;
      
      if (currentTarget == null) { // pick target
        checkReadiness();

        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        if (retryTime.overMaximum()) {
          // if (this.getNodeList(bucketId) == null
          // || this.getNodeList(bucketId).size() == 0) {
          // throw new EntryNotFoundException("Entry not found for key "
          // + event.getKey());
          // }
          if (getRegionAdvisor().getBucket(bucketId.intValue())
              .getBucketAdvisor().basicGetPrimaryMember() == null) {
            throw new EntryNotFoundException(LocalizedStrings.PartitionedRegion_ENTRY_NOT_FOUND_FOR_KEY_0.toLocalizedString(event.getKey()));
          }
          TimeoutException e = new TimeoutException(LocalizedStrings.PartitionedRegion_TIME_OUT_LOOKING_FOR_TARGET_NODE_FOR_DESTROY_WAITED_0_MS.toLocalizedString(
                  Integer.valueOf(retryTime.getRetryTime())));
          if (logger.fineEnabled()) {
            logger.warning(e);
          }
          checkReadiness();
          throw e;
        }

        currentTarget = getOrCreateNodeForBucketWrite(bucketId.intValue(), retryTime);

        // No storage found for bucket, early out preventing hot loop, bug 36819
        if (currentTarget == null) {
          checkEntryNotFound(event.getKey());
        }
        continue;
      } // pick target

      final boolean isLocal = (this.localMaxMemory > 0) && currentTarget.equals(getMyId());
      try {
        
        if (isLocal) {
//          doCacheWriteBeforeDestroy(event);
          event.setInvokePRCallbacks(true);
          this.dataStore.destroyLocally(bucketId,
              event, expectedOldValue);
        }
        else {
          if (event.isBridgeEvent()) {
            setNetworkHop(bucketId, currentTarget);
          }
          destroyRemotely(currentTarget,
                          event,
                          expectedOldValue);
          if (localCacheEnabled) {
            try {
              // only destroy in local cache if successfully destroyed remotely
              final boolean cacheWrite = true;
              super.basicDestroy(event,
                                 cacheWrite,
                                 null);  // pass null as expectedOldValue,
                                         // since if successfully destroyed
                                         // remotely we always want to succeed
                                         // locally
            }
            catch (EntryNotFoundException enf) {
              if (logger.fineEnabled()) {
                logger.fine("destroyInBucket: "
                    + "Failed to invalidate from local cache because of "
                    + "EntryNotFoundException.", enf);
              }
            }
          }
        }
        return;
        
        // NOTREACHED (success)
      }
      catch (ConcurrentCacheModificationException e) {
        if (logger.fineEnabled()) {
          logger.fine("destroyInBucket: caught concurrent cache modification exception", e);
        }
        event.isConcurrencyConflict(true);

        if (logger.finerEnabled()) {
          logger
              .finer("ConcurrentCacheModificationException received for destroyInBucket for bucketId: "
                  + bucketStringForLogs(bucketId.intValue())
                  + " for event: "
                  + event + " No reattampt is done, returning from here");
        }
        return;
      }
      catch (ForceReattemptException e) {
        e.checkKey(event.getKey());
        // We don't know if the destroy took place or not at this point.
        // Assume that if the next destroy throws EntryDestroyedException, the
        // previous destroy attempt was a success
        checkReadiness();
        InternalDistributedMember lastNode = currentTarget;
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        currentTarget = getOrCreateNodeForBucketWrite(bucketId.intValue(), retryTime);
        event.setPossibleDuplicate(true);
        if (lastNode.equals(currentTarget)) {
          if (retryTime.overMaximum()) {
            PRHARedundancyProvider.timedOut(this, null, null, "destroy an entry", retryTime.getRetryTime());
          }
          retryTime.waitToRetryNode();
        }
      }
      catch (PrimaryBucketException notPrimary) {
        if (logger.fineEnabled()) {
          logger.fine("destroyInBucket: " + notPrimary.getLocalizedMessage() 
              + " on Node " + currentTarget + " not primary ");
        }
        getRegionAdvisor().notPrimary(bucketId.intValue(), currentTarget);
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        currentTarget = getOrCreateNodeForBucketWrite(bucketId.intValue(), retryTime);
      }

      // If we get here, the attempt failed.
      if (count == 1) {
        this.prStats.incDestroyOpsRetried();
      }
      this.prStats.incDestroyRetries();

      if (logger.fineEnabled()) {
        logger.fine("destroyInBucket: "
            + " Attempting to resend destroy to node " + currentTarget + " after "
            + count + " failed attempts");
      }
    } // for
  }

  /**
   * TODO txMerge verify
   * 
   * @param bucketId
   * @param targetNode
   */

  private void setNetworkHop(final Integer bucketId,
      final InternalDistributedMember targetNode) {

    if (this.isDataStore() && !getMyId().equals(targetNode)) {
      Set<ServerBucketProfile> profiles = this.getRegionAdvisor()
          .getClientBucketProfiles(bucketId);
      
      if (profiles != null) {
        for (ServerBucketProfile profile : profiles) {
          if (profile.getDistributedMember().equals(targetNode)) {

            if (isProfileFromSameGroup(profile)) {
              if (logger.fineEnabled() && this.isNetworkHop() != 1) {
                logger
                    .fine("one-hop: cache op meta data staleness observed.  Message is in same server group (byte 1)");
              }
              this.setIsNetworkHop((byte)1);
            } else {
              if (logger.fineEnabled() && this.isNetworkHop() != 2) {
                logger
                    .fine("one-hop: cache op meta data staleness observed.  Message is to different server group (byte 2)");
              }
              this.setIsNetworkHop((byte)2);
            }
            this.setMetadataVersion((byte)profile.getVersion());
            break;
          }
        }
      }
    }
  }

  public boolean isProfileFromSameGroup(ServerBucketProfile profile) {
    Set<String> localServerGroups = getLocalServerGroups();
    if (localServerGroups.isEmpty()) {
      return true;
    }

    Set<BucketServerLocation66> locations = profile.getBucketServerLocations();

    for (BucketServerLocation66 sl : locations) {
      String[] groups = sl.getServerGroups();
      if (groups.length == 0) {
        return true;
      } else {
        for (String s : groups) {
          if (localServerGroups.contains(s))
            return true;
        }
      }
    }
    return false;
  }

  public Set<String> getLocalServerGroups() {
    Set<String> localServerGroups = new HashSet();
    GemFireCacheImpl c = getCache();
    List servers = null;

    servers = c.getCacheServers();

    Collections.addAll(localServerGroups, MemberAttributes.parseGroups(null, c.getSystem().getConfig().getGroups()));
    
    for (Object object : servers) {
      BridgeServerImpl server = (BridgeServerImpl)object;
      if (server.isRunning() && (server.getExternalAddress() != null)) {
        Collections.addAll(localServerGroups, server.getGroups());
      }
    }
    return localServerGroups;
  }
  /**
   * Destroy the entry on the remote node.
   * 
   * @param recipient
   *                the member id of the message
   * @param event
   *                the event prompting this request
   * @param expectedOldValue
   *        if not null, then destroy only if entry exists and current value
   *        is equal to expectedOldValue
   * @throws EntryNotFoundException if entry not found OR if expectedOldValue
   *         is non-null and doesn't equal the current value
   * @throws PrimaryBucketException
   *                 if the bucket on that node is not the primary copy
   * @throws ForceReattemptException
   *                 if the peer is no longer available
   */
  protected final void destroyRemotely(final InternalDistributedMember recipient,
                                       final EntryEventImpl event,
                                       final Object expectedOldValue)
      throws EntryNotFoundException,
             PrimaryBucketException,
             ForceReattemptException {
    final DestroyResponse response = sendDestroyRemotely(recipient, event,
        expectedOldValue, true);
    if (response != null) {
      waitForRemoteDestroy(response, recipient, event);
    }
  }

  protected final DestroyResponse sendDestroyRemotely(
      final InternalDistributedMember recipient, final EntryEventImpl event,
      Object expectedOldValue, boolean cacheWrite)
      throws ForceReattemptException {
    final DestroyResponse response = DestroyMessage.send(recipient, this,
        event, expectedOldValue, cacheWrite);
    if (response != null) {
      this.prStats.incPartitionMessagesSent();
      return response;
    }
    return null;
  }

  protected final PartitionResponse sendDestroyRemotely(final Set recipients,
      EntryEventImpl event, Object expectedOldValue, final boolean cacheWrite)
      throws ForceReattemptException {
    final PartitionResponse response = DestroyMessage.send(recipients, this,
        event, expectedOldValue, cacheWrite);
    if (response != null) {
      this.prStats.incPartitionMessagesSent();
      return response;
    }
    return null;
  }

  protected final void waitForRemoteDestroy(final DestroyResponse response,
      final Object recipients, final EntryEventImpl event) throws
      EntryNotFoundException, PrimaryBucketException, ForceReattemptException {
    try {
      response.waitForCacheException();
      event.setVersionTag(response.getVersionTag());
    } catch (EntryNotFoundException enfe) {
      throw enfe;
    } catch (TransactionException te) {
      throw te;
    } catch (CacheException ce) {
      throw new PartitionedRegionException(
          LocalizedStrings.PartitionedRegion_DESTROY_OF_ENTRY_ON_0_FAILED
              .toLocalizedString(recipients), ce);
    } catch (RegionDestroyedException rde) {
      throw new RegionDestroyedException(toString(), getFullPath());
    }
  }

  /**
   * This is getter method for local max memory.
   * 
   * @return local max memory for this PartitionedRegion
   */
  public int getLocalMaxMemory() {
    return this.localMaxMemory;
  }

  /**
   * This is getter method for redundancy.
   * 
   * @return redundancy for this PartitionedRegion
   */
  final public int getRedundantCopies() {
    return this.redundantCopies;
  }

  @Override
  void createEventTracker() {
    // PR buckets maintain their own trackers.  None is needed at this level
  }

  @Override
  public VersionTag findVersionTagForClientEvent(EventID eventId) {
    if (this.dataStore != null) {
      Set<Map.Entry<Integer, BucketRegion>> bucketMap = this.dataStore.getAllLocalBuckets();
      for (Map.Entry<Integer, BucketRegion> entry: bucketMap) {
        VersionTag result = entry.getValue().findVersionTagForClientEvent(eventId);
        if (result != null) {
          return result;
        }
      }
    }
    return null;
  }
  
  @Override
  public VersionTag findVersionTagForClientPutAll(EventID eventId) {
    new HashMap<ThreadIdentifier, VersionTag>();
    if (this.dataStore != null) {
      Set<Map.Entry<Integer, BucketRegion>> bucketMap = this.dataStore.getAllLocalBuckets();
      for (Map.Entry<Integer, BucketRegion> entry: bucketMap) {
        VersionTag bucketResult = entry.getValue().findVersionTagForClientPutAll(eventId);
        if (bucketResult != null) {
          return bucketResult;
        }
      }
    }
    return null;
  }
  
  /*
   * This method cleans the Partioned region structures if the the creation of
   * Partition region fails
   * OVERRIDES
   */
  @Override
  public void cleanupFailedInitialization() {
    super.cleanupFailedInitialization();
    //Fix for 44551 - make sure persistent buckets
    //are done recoverying from disk before sending the 
    //destroy region message.
    this.redundancyProvider.waitForPersistentBucketRecovery();
    this.cache.removePartitionedRegion(this);
    this.cache.getResourceManager(false).removeResourceListener(this);
    this.redundancyProvider.shutdown(); // see bug 41094
    if (this.bucketSorter != null) {
      this.bucketSorter.shutdownNow();
    }
    int serials[] = getRegionAdvisor().getBucketSerials();
    RegionEventImpl event = new RegionEventImpl(this, Operation.REGION_CLOSE,
        null, false, getMyId(), generateEventID()/* generate EventID */);
    try {
      sendDestroyRegionMessage(event, serials);
    }
    catch (Exception ex) {
      logger.warning(
          LocalizedStrings.PartitionedRegion_PARTITIONEDREGION_CLEANUPFAILEDINITIALIZATION_FAILED_TO_CLEAN_THE_PARTIONREGION_DATA_STORE,
          ex);
    }
    if (null != this.dataStore) {
      try {
        this.dataStore.cleanUp(true, false);
      }
      catch (Exception ex) {
        logger.warning(
            LocalizedStrings.PartitionedRegion_PARTITIONEDREGION_CLEANUPFAILEDINITIALIZATION_FAILED_TO_CLEAN_THE_PARTIONREGION_DATA_STORE,
            ex);
      }
    }
    
    if (this.cleanPRRegistration) {
      try {
        synchronized (prIdToPR) {
          if (prIdToPR.containsKey(Integer.valueOf(this.partitionedRegionId))) {
            prIdToPR.put(Integer.valueOf(this.partitionedRegionId),
                PRIdMap.FAILED_REGISTRATION, false);
            if (logger.fineEnabled()) {
              logger.fine("cleanupFailedInitialization: set failed for prId="
                  + this.partitionedRegionId + " named " + this.getName());
            }
          }
        }
        
        PartitionedRegionHelper.removeGlobalMetadataForFailedNode(this.node,
            this.getRegionIdentifier(), this.getGemFireCache(), true);
      }
      catch (Exception ex) {
        logger.warning(
            LocalizedStrings.PartitionedRegion_PARTITIONEDREGION_CLEANUPFAILEDINITIALIZATION_FAILED_TO_CLEAN_THE_PARTIONREGION_ALLPARTITIONEDREGIONS,
            ex);
      }
    }
    this.distAdvisor.close();
    getPrStats().close();
    if(getDiskStore() != null && getDiskStore().getOwnedByRegion()) {
      getDiskStore().close();
    }
    if (logger.fineEnabled()) {
      logger.fine("cleanupFailedInitialization: end of " + getName());
    }
  }

  /**
   * Perform cleanup when the Cache closes OVERRIDES
   */
  // void handleCacheClose()
  // {
  // logger.fine("Handling cache closure for Partitioned Region " + getName());
  // super.handleCacheClose();
  // basicClose();
  // }
  /**
   * Do what needs to be done to partitioned regions state when closing.
   */
  // private void basicClose()
  // {
  // getPrStats().close();
  // // isClosed = true;
  // }
  /**
   * Called after the cache close has closed all regions. This clean up static
   * pr resources.
   * 
   * @since 5.0
   */
  static void afterRegionsClosedByCacheClose(GemFireCacheImpl cache) {
    PRQueryProcessor.shutdown();
    clearPRIdMap();
  }

  static void destroyLockService() {
    PartitionedRegionHelper.destroyLockService();
  }

  @Override
  void basicInvalidateRegion(RegionEventImpl event) {
    if (!event.isOriginRemote()) {
      sendInvalidateRegionMessage(event);
    }
    for (BucketRegion br : getDataStore().getAllLocalPrimaryBucketRegions()) {
      logger.fine("Invalidating bucket "+br);
      br.basicInvalidateRegion(event);
    }
    super.basicInvalidateRegion(event);
  }

  @Override
  protected void invalidateAllEntries(RegionEvent rgnEvent) {
  }
  
  private void sendInvalidateRegionMessage(RegionEventImpl event) {
    final int retryAttempts = calcRetry();
    Throwable thr = null;
    int count = 0;
    while (count <= retryAttempts) {
      try {
        count++;
        Set recipients = getRegionAdvisor().adviseDataStore();
        ReplyProcessor21 response = InvalidatePartitionedRegionMessage.send(
            recipients, this, event);
        response.waitForReplies();
        thr = null;
        break;
      } catch (ReplyException e) {
        thr = e;
        if (!this.isClosed && !this.isDestroyed) {
          logger.fine(
                  LocalizedStrings.PartitionedRegion_INVALIDATING_REGION_CAUGHT_EXCEPTION.toLocalizedString(), e);
        }
      } catch (InterruptedException e) {
        thr = e;
        if (this.cache.getCancelCriterion().cancelInProgress() == null) {
          logger.fine(
                  LocalizedStrings.PartitionedRegion_INVALIDATING_REGION_CAUGHT_EXCEPTION.toLocalizedString(), e);
        }
      }
    }
    if (thr != null) {
      PartitionedRegionDistributionException e = new PartitionedRegionDistributionException(
          LocalizedStrings.PartitionedRegion_INVALIDATING_REGION_CAUGHT_EXCEPTION
              .toLocalizedString(Integer.valueOf(count)));
      logger.fine(e);
      throw e;
    }
  }

  @Override
  void basicInvalidate(EntryEventImpl event) throws EntryNotFoundException {
    final long startTime = PartitionedRegionStats.startTime();
    try {
      if (event.getEventId() == null) {
        event.setNewEventId(this.cache.getDistributedSystem());
      }
      final TXStateInterface tx = discoverJTA(event);
      getDataView(tx).invalidateExistingEntry(event, isInitialized(), false);
    }
    catch (RegionDestroyedException rde) {
      if (!rde.getRegionFullPath().equals(getFullPath())) {
        // Handle when a bucket is destroyed
        RegionDestroyedException rde2 = new RegionDestroyedException(toString(), getFullPath());
        rde2.initCause(rde);
        throw rde2;
      }
    }
    finally {
      this.prStats.endInvalidate(startTime);
    }
    return;
  }

  /** visitor to check if there is atleast one initialized data store */
  private static final DistributionAdvisor.ProfileVisitor<Void> initDataStoreChecker =
      new DistributionAdvisor.ProfileVisitor<Void>() {
    public boolean visit(DistributionAdvisor advisor, Profile profile,
        int profileIndex, int numProfiles, Void aggregate) {
      PartitionProfile p = (PartitionProfile)profile;
      return !(p.isDataStore && !p.memberUnInitialized);
    }
  };

  /*
   * We yet don't have any stats for this operation.
   * (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.LocalRegion#basicUpdateEntryVersion(com.gemstone.gemfire.internal.cache.EntryEventImpl)
   */
  @Override
  void basicUpdateEntryVersion(EntryEventImpl event)
      throws EntryNotFoundException {

    try {
      if (event.getEventId() == null) {
        event.setNewEventId(this.cache.getDistributedSystem());
      }
      getDataView().updateEntryVersion(event);
    }
    catch (RegionDestroyedException rde) {
      if (!rde.getRegionFullPath().equals(getFullPath())) {
        // Handle when a bucket is destroyed
        RegionDestroyedException rde2 = new RegionDestroyedException(toString(), getFullPath());
        rde2.initCause(rde);
        throw rde2;
      }
    }
    return;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean hasDataStores() {
    boolean hasStore = true;
    PartitionAttributes<?, ?> pa = getPartitionAttributes();
    if (pa.getLocalMaxMemory() == 0) {
      hasStore = !this.getRegionAdvisor().accept(initDataStoreChecker, null);
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

  /**
   * Invalidate the entry in the bucket identified by the key
   * @param event
   * @throws EntryNotFoundException
   */
  void invalidateInBucket(final EntryEventImpl event)
      throws EntryNotFoundException {
    final Integer bucketId = event.getBucketId();
    assert bucketId != KeyInfo.UNKNOWN_BUCKET;
    final InternalDistributedMember targetNode = getOrCreateNodeForBucketWrite(
        bucketId, null);

    final int retryAttempts = calcRetry();
    int count = 0;
    RetryTimeKeeper retryTime = null;
    InternalDistributedMember retryNode = targetNode;
    while (count <= retryAttempts) {
      // It's possible this is a GemFire thread e.g. ServerConnection
      // which got to this point because of a distributed system shutdown or
      // region closure which uses interrupt to break any sleep() or wait()
      // calls
      // e.g. waitForPrimary or waitForBucketRecovery
      checkShutdown();

      if (retryNode == null) {
        checkReadiness();
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        try {
          retryNode = getOrCreateNodeForBucketWrite(bucketId.intValue(), retryTime);
        }
        catch (TimeoutException te) {
          if (getRegionAdvisor()
              .isStorageAssignedForBucket(bucketId.intValue())) { // bucket no
                                                                  // longer
                                                                  // exists
            throw new EntryNotFoundException(LocalizedStrings.PartitionedRegion_ENTRY_NOT_FOUND_FOR_KEY_0.toLocalizedString(event.getKey()));
          }
          break; // fall out to failed exception
        }

        if (retryNode == null) {
          checkEntryNotFound(event.getKey());
        }
        continue;
      }
      final boolean isLocal = (this.localMaxMemory != 0) && retryNode.equals(getMyId());
      try {
        if (isLocal) {
          event.setInvokePRCallbacks(true);
          this.dataStore.invalidateLocally(bucketId, event);
        }
        else {
          invalidateRemotely(retryNode, event);
          if (localCacheEnabled) {
            try {
              super.basicInvalidate(event);
            }
            catch (EntryNotFoundException enf) {
              if (logger.fineEnabled()) {
                logger.fine("invalidateInBucket: "
                    + "Failed to invalidate from local cache because of "
                    + "EntryNotFoundException.", enf);
              }
            }
          }
        }
        return;
      } catch (ConcurrentCacheModificationException e) {
        if (logger.fineEnabled()) {
          logger.fine("invalidateInBucket: caught concurrent cache modification exception", e);
        }
        event.isConcurrencyConflict(true);

        if (logger.finerEnabled()) {
          logger
              .finer("ConcurrentCacheModificationException received for invalidateInBucket for bucketId: "
                  + bucketStringForLogs(bucketId.intValue())
                  + " for event: "
                  + event + " No reattampt is done, returning from here");
        }
        return;
      }
      catch (ForceReattemptException prce) {
        prce.checkKey(event.getKey());
        if (logger.fineEnabled()) {
          logger.fine("invalidateInBucket: retry attempt:" + count + " of "
              + retryAttempts, prce);
        }
        checkReadiness();

        InternalDistributedMember lastNode = retryNode;
        retryNode = getOrCreateNodeForBucketWrite(bucketId.intValue(), retryTime);
        if (lastNode.equals(retryNode)) {
          if (retryTime == null) {
            retryTime = new RetryTimeKeeper(this.retryTimeout);
          }
          if (retryTime.overMaximum()) {
            break;
          }
          retryTime.waitToRetryNode();
        }
        event.setPossibleDuplicate(true);
      }
      catch (PrimaryBucketException notPrimary) {
        if (logger.fineEnabled()) {
          logger.fine("invalidateInBucket " + notPrimary.getLocalizedMessage()
              + " on Node " + retryNode + " not primary ");
        }
        getRegionAdvisor().notPrimary(bucketId.intValue(), retryNode);
        retryNode = getOrCreateNodeForBucketWrite(bucketId.intValue(), retryTime);
      }

      count++;
      if (count == 1) {
        this.prStats.incInvalidateOpsRetried();
      }
      this.prStats.incInvalidateRetries();
      if (logger.fineEnabled()) {
        logger.fine("invalidateInBucket: "
            + " Attempting to resend invalidate to node " + retryNode
            + " after " + count + " failed attempts");
      }
    } // while

    // No target was found
    PartitionedRegionDistributionException e 
      = new PartitionedRegionDistributionException(LocalizedStrings.PartitionedRegion_NO_VM_AVAILABLE_FOR_INVALIDATE_IN_0_ATTEMPTS
          .toLocalizedString(Integer.valueOf(count)));  // Fix for bug 36014
    if (!logger.fineEnabled()) {
      logger.warning(
          LocalizedStrings.PartitionedRegion_NO_VM_AVAILABLE_FOR_INVALIDATE_IN_0_ATTEMPTS, 
          Integer.valueOf(count));
    }
    else {
      logger.warning(e);
    }
    throw e;
  }

  /**
   * invalidates the remote object with the given key.
   * 
   * @param recipient
   *                the member id of the recipient of the operation
   * @throws EntryNotFoundException
   *                 if the entry does not exist in this region
   * @throws PrimaryBucketException
   *                 if the bucket on that node is not the primary copy
   * @throws ForceReattemptException
   *                 if the peer is no longer available
   */
  protected void invalidateRemotely(final InternalDistributedMember recipient,
      final EntryEventImpl event) throws EntryNotFoundException,
      PrimaryBucketException, ForceReattemptException {
    final InvalidateResponse response = sendInvalidateRemotely(recipient, event);
    if (response != null) {
      waitForRemoteInvalidate(response, recipient, event);
    }
  }

  protected final InvalidateResponse sendInvalidateRemotely(
      final InternalDistributedMember recipient, final EntryEventImpl event)
      throws ForceReattemptException {
    final InvalidateResponse response = InvalidateMessage.send(recipient, this,
        event);
    if (response != null) {
      this.prStats.incPartitionMessagesSent();
      return response;
    }
    return null;
  }

  protected final InvalidateResponse sendInvalidateRemotely(
      final Set recipients, final EntryEventImpl event)
      throws ForceReattemptException {
    final InvalidateResponse response = InvalidateMessage.send(recipients,
        this, event);
    if (response != null) {
      this.prStats.incPartitionMessagesSent();
      return response;
    }
    return null;
  }

  protected final void waitForRemoteInvalidate(
      final InvalidateResponse response, final Object recipients,
      final EntryEventImpl event) throws EntryNotFoundException,
      PrimaryBucketException, ForceReattemptException {
    try {
      response.waitForResult();
      event.setVersionTag(response.versionTag);
    } catch (EntryNotFoundException enfe) {
      throw enfe;
    } catch (TransactionException te) {
      throw te;
    } catch (CacheException ce) {
      throw new PartitionedRegionException(
          LocalizedStrings.PartitionedRegion_INVALIDATION_OF_ENTRY_ON_0_FAILED
              .toLocalizedString(recipients), ce);
    }
  }

  /**
   * Calculate the number of times we attempt to commumnicate with a data store.
   * Beware that this method is called very frequently so it absolutely must
   * perform well.
   * 
   * @return the number of times to attempt to communicate with a data store
   */
  private int calcRetry() {
    return (this.retryTimeout / 
        PartitionedRegionHelper.DEFAULT_WAIT_PER_RETRY_ITERATION) + 1;
  }

  /**
   * Creates the key/value pair into the remote target that is managing the
   * key's bucket.
   * 
   * @param recipient
   *                member id of the recipient of the operation
   * @param bucketId
   *                the id of the bucket that the key hashed to
   * @param event
   *                the event prompting this request
   * @throws PrimaryBucketException
   *                 if the bucket on that node is not the primary copy
   * @throws ForceReattemptException
   *                 if the peer is no longer available
   * @throws EntryExistsException
   */
  private boolean createRemotely(InternalDistributedMember recipient,
                                 Integer bucketId,
                                 EntryEventImpl event,
                                 boolean requireOldValue)
      throws PrimaryBucketException, ForceReattemptException {
    boolean ret = false;
    long eventTime = event.getEventTime(0L);
    PutMessage.PutResponse reply = PutMessage.send(recipient,
                                                this,
                                                event,
                                                eventTime,
                                                true,
                                                false,
                                                null, // expectedOldValue
                                                requireOldValue,
                                                true);
    PutResult pr = null;
    if (reply != null) {
      this.prStats.incPartitionMessagesSent();
      try {
        pr = reply.waitForResult();
        // if (logger.fineEnabled()) {
        // logger.fine("return operation for put is " + pr.op);
        // }
        event.setOperation(pr.op);
        event.setVersionTag(pr.versionTag);
        if (requireOldValue) {
          event.setOldValue(pr.oldValue, true);
        }
        ret = pr.returnValue;
        // if (!pr.returnValue) {
        // throw new EntryExistsException("An entry already exists for key " +
        // event.getKey() + " on region " + getFullPath());
        // }
      }
      catch (EntryExistsException eee) {
        // This might not be necessary and is here for safety sake
        ret = false;
      } catch (TransactionException te) {
        throw te;
      } catch (CacheException ce) {
        throw new PartitionedRegionException(
            LocalizedStrings.PartitionedRegion_CREATE_OF_ENTRY_ON_0_FAILED
                .toLocalizedString(recipient), ce);
      }
      catch (RegionDestroyedException rde) {
        if (logger.fineEnabled()) {
          logger.fine("createRemotely: caught exception", rde);
        }
        throw new RegionDestroyedException(toString(), getFullPath());
      }
    }
    return ret;
  }

  // ////////////////////////////////
  // ////// Set Operations /////////
  // ///////////////////////////////

  /**
   * This method returns set of all the entries of this
   * PartitionedRegion(locally or remotely). Currently, it throws
   * UnsupportedOperationException
   * 
   * @param recursive
   *                boolean flag to indicate whether subregions should be
   *                considered or not.
   * @return set of all the entries of this PartitionedRegion
   * 
   * OVERRIDES
   */
  @Override
  public Set entries(boolean recursive) {
    checkReadiness();
    return Collections.unmodifiableSet(new PREntriesSet());
  }

  /**
   * Currently used by GemFireXD to get a non-wrapped iterator for all entries
   * for index consistency check.
   */
  public Set allEntries() {
    return new PREntriesSet();
  }

  /**
   * Get an iterator on local entries possibly filtered by given
   * FunctionContext.
   * 
   * @param context
   *          FunctionContext, if any, from the execution of a function on
   *          region
   * @param primaryOnly
   *          if true then return only the primary bucket entries when there is
   *          no FunctionContext provided or the context has no filter
   * @param forUpdate
   *          if true then the entry has to be fetched for update so will be
   *          locked if required in a transactional context
   * @param includeValues
   *          if true then iterator needs the values else only keys (e.g. to
   *          avoid disk reads)
   */
  public final Iterator<?> localEntriesIterator(
      final InternalRegionFunctionContext context, final boolean primaryOnly,
      final boolean forUpdate, final boolean includeValues) {
    final TXStateInterface tx = getTXState();
    TXState txState = null;
    if (tx != null) {
      txState = tx.getLocalTXState();
    }
    return localEntriesIterator(context, primaryOnly, forUpdate, includeValues,
        txState);
  }

  /**
   * Get an iterator on local entries possibly filtered by given
   * FunctionContext.
   * 
   * @param context
   *          FunctionContext, if any, from the execution of a function on
   *          region
   * @param primaryOnly
   *          if true then return only the primary bucket entries when there is
   *          no FunctionContext provided or the context has no filter
   * @param forUpdate
   *          if true then the entry has to be fetched for update so will be
   *          locked if required in a transactional context
   * @param includeValues
   *          if true then iterator needs the values else only keys (e.g. to
   *          avoid disk reads)
   * @param txState
   *          the current TXState
   */
  public final Iterator<?> localEntriesIterator(
      final InternalRegionFunctionContext context, final boolean primaryOnly,
      final boolean forUpdate, boolean includeValues, final TXState txState) {
    final Set<Integer> bucketSet;
    if (context != null
        && (bucketSet = context.getLocalBucketSet(this)) != null) {
      return new PRLocalScanIterator(bucketSet, txState, forUpdate,
          includeValues, true);
    }
    return new PRLocalScanIterator(primaryOnly, txState, forUpdate,
        includeValues);
  }

  public final Iterator<?> localEntriesIterator(
      Set bucketSet, final boolean primaryOnly,
      final boolean forUpdate, boolean includeValues, final TXState txState) {
    if (bucketSet  != null) {
      return new PRLocalScanIterator(bucketSet, txState, forUpdate,
          includeValues, true);
    }
    return new PRLocalScanIterator(primaryOnly, txState, forUpdate,
        includeValues);
  }

  /**
   * Set view of entries. This currently extends the keySet iterator and
   * performs individual getEntry() operations using the keys
   * 
   * @since 5.1
   * @author bruce
   */
  protected class PREntriesSet extends KeysSet {

    boolean allowTombstones;
    
    private class EntriesSetIterator extends KeysSetIterator {

      /** reusable KeyInfo */
      private final KeyInfo key = new KeyInfo(null, null, null);

      public EntriesSetIterator(Set bucketSet, boolean allowTombstones) {
        super(bucketSet, allowTombstones);
        PREntriesSet.this.allowTombstones = allowTombstones;
      }

      @Override
      public Object next() {
        this.key.setKey(super.next());
        this.key.setBucketId(this.currentBucketId);
        Object entry = view.getEntryForIterator(this.key, PartitionedRegion.this,
            allowTombstones);
        return entry != null ? entry : new DestroyedEntry(key.getKey().toString());
      }
    }

    public PREntriesSet() {
      super();
    }
    public PREntriesSet(Set<Integer> bucketSet, final TXStateInterface tx) {
      super(bucketSet, tx);
    }

    @Override
    public Iterator iterator() {
      checkTX();
      return new EntriesSetIterator(this.bucketSet, allowTombstones);
    }
  }


  /**
   * This method returns set of all the keys of this PartitionedRegion(locally
   * or remotely).
   * 
   * @return set of all the keys of this PartitionedRegion
   * 
   * OVERRIDES
   */
  @Override
  public Set keys() {
    checkReadiness();
    return Collections.unmodifiableSet(new KeysSet());
  }
  
  @Override
  public Set keySet(boolean allowTombstones) {
    checkReadiness();
    return Collections.unmodifiableSet(new KeysSet(allowTombstones,
        getTXState()));
  }

  public Set keysWithoutCreatesForTests() {
    checkReadiness();
    Set<Integer> availableBuckets = new HashSet<Integer>();
    for(int i =0; i < getTotalNumberOfBuckets(); i++) {
      if(distAdvisor.isStorageAssignedForBucket(i)) {
        availableBuckets.add(Integer.valueOf(i));
      }
    }
    return Collections.unmodifiableSet(new KeysSet(availableBuckets,
        getTXState()));
  }

  /** Set view of entries */
  protected class KeysSet extends EntriesSet {
    class KeysSetIterator implements PREntriesIterator<Object> {
      final Iterator<Integer> bucketSetI;
      volatile Iterator<?> currentBucketI = null;
      int currentBucketId = KeyInfo.UNKNOWN_BUCKET;
      volatile Object currentKey = null;
      boolean allowTombstones;

      public KeysSetIterator(Set<Integer> bucketSet, boolean allowTombstones) {
        PartitionedRegion.this.checkReadiness();
        this.bucketSetI = createBucketSetI(bucketSet);
        this.allowTombstones = allowTombstones;
        this.currentBucketI = getNextBucketIter(false /* no throw */);
      }

      protected final Iterator<Integer> createBucketSetI(
          final Set<Integer> bucketSet) {
        if (bucketSet != null) {
          return bucketSet.iterator();
        }
        return getRegionAdvisor().getBucketSet().iterator();
      }

      public boolean hasNext() {
        PartitionedRegion.this.checkReadiness();
        if (this.currentBucketI.hasNext()) {
          return true;
        }
        else {
          while (!this.currentBucketI.hasNext() && this.bucketSetI.hasNext()) {
            PartitionedRegion.this.checkReadiness();
            this.currentBucketI = getNextBucketIter(false);
          }
          return this.currentBucketI.hasNext();
        }
      }

      public Object next() {
        if (myTX != null && !skipTxCheckInIteration) {
          checkTX();
        }
        PartitionedRegion.this.checkReadiness();
        if (this.currentBucketI.hasNext()) {
          this.currentKey = this.currentBucketI.next();
        }
        else {
          this.currentKey = null;
          while (!this.currentBucketI.hasNext() && this.bucketSetI.hasNext()) {
            PartitionedRegion.this.checkReadiness();
            this.currentBucketI = getNextBucketIter(true);
          }
          // Next line may throw NoSuchElementException... this is expected.
          this.currentKey = this.currentBucketI.next();
        }
        return this.currentKey;
      }

      protected Iterator getNextBucketIter(boolean canThrow) {
        try {
          this.currentBucketId = this.bucketSetI.next().intValue();
          // @todo: optimize this code by implementing getBucketKeysIterator.
          // Instead of creating a Set to return it can just create an ArrayList
          // and return an iterator on it. This would cut down on garbage and
          // cpu usage.
          return view.getBucketKeys(PartitionedRegion.this,
              this.currentBucketId, this.allowTombstones).iterator();
        }
        catch (NoSuchElementException endOfTheLine) {
          if (canThrow) {
            throw endOfTheLine;
          }
          else {
            // Logically pass the NoSuchElementException to the caller
            // Can't throw here because it is called in the contructor context
            return Collections.EMPTY_SET.iterator();
          }
        }
      }

      public void remove() {
        if (this.currentKey == null) {
          throw new IllegalStateException();
        }
        try {
          PartitionedRegion.this.destroy(this.currentKey);
        }
        catch (EntryNotFoundException ignore) {
          if (logger.fineEnabled()) {
            logger.fine("Caught exception during KeySetIterator remove", ignore);
          }
        }
        finally {
          this.currentKey = null;
        }
      }

      public final PartitionedRegion getPartitionedRegion() {
        return PartitionedRegion.this;
      }

      public final int getBucketId() {
        return this.currentBucketId;
      }

      public final Bucket getBucket() {
        return getRegionAdvisor().getBucket(this.currentBucketId);
      }

      public final BucketRegion getHostedBucketRegion() {
        return getBucket().getHostedBucketRegion();
      }
    }

    final protected Set<Integer> bucketSet;

    public KeysSet() {
      super(PartitionedRegion.this, false, IteratorType.KEYS, getTXState(),
          false, false);
      this.bucketSet = null;
    }

    public KeysSet(boolean allowTombstones, final TXStateInterface tx) {
      super(PartitionedRegion.this, false, IteratorType.KEYS, tx, false,
          allowTombstones);
      this.bucketSet = null;
    }

    public KeysSet(Set<Integer> bucketSet, final TXStateInterface tx) {
      super(PartitionedRegion.this, false, IteratorType.KEYS, tx, false, false);
      this.bucketSet = bucketSet;
    }

    @Override
    public int size() {
      checkTX();
     return PartitionedRegion.this.entryCount(this.myTX, this.bucketSet);
    }

    @Override
    public Object[] toArray() {
      return toArray(null);
    }

    @Override
    public Object[] toArray(Object[] array) {
      List temp = new ArrayList(this.size());
      for (Iterator iter = this.iterator(); iter.hasNext();) {
        temp.add(iter.next());
      }
      if (array == null) {
        return temp.toArray();
      }
      else {
        return temp.toArray(array);
      }
    }

    @Override
    public Iterator iterator() {
      checkTX();
      return new KeysSetIterator(this.bucketSet, this.allowTombstones);
    }
  }

  /**
   * This method returns collection of all the values of this
   * PartitionedRegion(locally or remotely).
   * 
   * @return collection of all the values of this PartitionedRegion
   */
  @Override
  public Collection values() {
    checkReadiness();
    return Collections.unmodifiableSet(new ValuesSet());
  }

  /**
   * Set view of values. This currently extends the keySet iterator and performs
   * individual get() operations using the keys
   * 
   * @since 5.1
   * @author bruce
   */
  protected class ValuesSet extends KeysSet  {

    private class ValuesSetIterator extends KeysSetIterator {

      Object nextValue = null;

      /** reusable KeyInfo */
      private final KeyInfo key = new KeyInfo(null, null, null);

      public ValuesSetIterator(Set bucketSet) {
        super(bucketSet, false);
      }

      @Override
      public boolean hasNext() {
        if (nextValue != null) {
          return true;
        }
        while (nextValue == null || Token.isInvalid(nextValue)) {
          if (!super.hasNext()) {
            nextValue = null;
            return false;
          }
          this.key.setKey(super.next());
          this.key.setBucketId(this.currentBucketId);
          nextValue = getDataView(myTX).getValueForIterator(key,
              PartitionedRegion.this, false /* updateStats */, keepSerialized,
              null, allowTombstones);
        }
        return true;
      }

      @Override
      public Object next() {
        if (!this.hasNext()) {
          throw new NoSuchElementException();
        }
        Assert.assertTrue(nextValue != null, "nextValue found to be null");
        Object result = nextValue;
        nextValue = null;
        return result;
      }
    
      @Override
      public void remove() {
        super.remove();
        nextValue = null;
      }
    }

    public ValuesSet() {
      super();
    }

    public ValuesSet(Set<Integer> bucketSet, final TXStateInterface tx) {
      super(bucketSet, tx);
    }

    @Override
    public Iterator iterator() {
      checkTX();
      return new ValuesSetIterator(this.bucketSet);
    }
  }

  public final class PRLocalScanIterator implements PREntriesIterator<Object>,
      CloseableIterator<Object> {

    private final Iterator<Integer> bucketIdsIter;

    private final TXState txState;

    private final boolean includeHDFS;

    private final boolean forUpdate;

    private final boolean includeValues;

    private Iterator<RegionEntry> bucketEntriesIter;
    private boolean remoteEntryFetched;

    private Object currentEntry;

    private int currentBucketId = KeyInfo.UNKNOWN_BUCKET;

    private BucketRegion currentBucketRegion;// could be null if the entry is fetched from remote

    private boolean moveNext = true;

    private final long numEntries;
    
    private boolean diskIteratorInitialized;

    private final StaticSystemCallbacks cb;

    private final boolean fetchRemoteEntries;

    private boolean commitOnClose;

    public PRLocalScanIterator(final boolean primaryOnly, final TXState tx,
        final boolean forUpdate, final boolean includeValues) {
      this.includeHDFS = includeHDFSResults();
      Iterator<Integer> iter = null;
      long numEntries = -1;
      PartitionedRegionDataStore ds = dataStore;
      if (ds == null) {
        // region may not have storage or region may not have been initialized
        // (e.g. delayed initialization in GemFireXD while CREATE INDEX may open
        // an iterator on the region)
        if (getLocalMaxMemory() == 0 || !isInitialized()) {
          iter = Collections.<Integer> emptySet().iterator();
        }
        // check dataStore again due to small window after isInitialized()
        else if ((ds = dataStore) == null) {
          checkReadiness();
          throw new AssertionError("This process should have storage"
              + " for local scan on PR: " + PartitionedRegion.this.toString());
        }
      }
      if (iter == null) {
        // make sure an hdfs region has all buckets created
        ensureBucketsForHDFS();

        if (primaryOnly) {
          iter = ds.getAllLocalPrimaryBucketIds().iterator();
          if (needsDiskIteration(includeValues)) {
            numEntries = ds.getEstimatedLocalBucketSize(true);
          }
        }
        else {
          iter = ds.getAllLocalBucketIds().iterator();
          if (needsDiskIteration(includeValues)) {
            numEntries = ds.getEstimatedLocalBucketSize(false);
          }
        }
      }
      this.fetchRemoteEntries = false;
      this.remoteEntryFetched = false;
      this.bucketIdsIter = iter;
      this.numEntries = numEntries;
      this.txState = tx;
      this.forUpdate = forUpdate;
      this.includeValues = includeValues;
      this.diskIteratorInitialized = false;
      this.cb = GemFireCacheImpl.getInternalProductCallbacks();

    }

    public PRLocalScanIterator(final Set<Integer> bucketIds, final TXState tx,
        final boolean forUpdate, final boolean includeValues, final boolean fetchRemote) {
      this.includeHDFS = includeHDFSResults();
      Iterator<Integer> iter = null;
      long numEntries = -1;
      PartitionedRegionDataStore ds = dataStore;

      if (ds == null) {
        // region may not have storage or region may not have been initialized
        // (e.g. delayed initialization in GemFireXD while CREATE INDEX may open
        // an iterator on the region)
        if (getLocalMaxMemory() == 0 || !isInitialized()) {
          iter = Collections.<Integer> emptySet().iterator();
        }
        else if ((ds = dataStore) == null) {
          checkReadiness();
          throw new AssertionError("This process should have storage"
              + " for local scan on PR: " + PartitionedRegion.this.toString());
        }
      }
      if (iter == null) {
        // make sure an hdfs region has all buckets created
        ensureBucketsForHDFS();

        iter = bucketIds.iterator();
        if (needsDiskIteration(includeValues)) {
          numEntries = ds.getEstimatedLocalBucketSize(bucketIds);
        }
      }
      this.fetchRemoteEntries = fetchRemote;
      this.remoteEntryFetched = false;
      this.bucketIdsIter = iter;
      this.txState = tx;
      this.forUpdate = forUpdate;
      this.includeValues = includeValues;
      this.numEntries = numEntries;
      this.diskIteratorInitialized = false;
      this.cb = GemFireCacheImpl.getInternalProductCallbacks();
    }

    private void ensureBucketsForHDFS() {
      if (isHDFSReadWriteRegion() && includeHDFS) {
        getRegionAdvisor().accept(new BucketVisitor<Object>() {
          @Override
          public boolean visit(RegionAdvisor advisor, ProxyBucketRegion pbr, Object obj) {
            // ensure that the bucket has been created
            pbr.getPartitionedRegion().getOrCreateNodeForBucketWrite(pbr.getBucketId(), null);
            return true;
          }
        }, null);
      }
    }
    
    private boolean needsDiskIteration(boolean includeValues) {
      return includeValues && DiskEntryPage.DISK_PAGE_SIZE > 0
          && getDiskStore() != null && !isUsedForMetaRegion()
          && !isUsedForPartitionedRegionAdmin();
    }

    public void close() {
      if (bucketEntriesIter instanceof HDFSIterator) {
        ((HDFSIterator) bucketEntriesIter).close();
      }
    }

    // For snapshot isolation, Get the iterator on the txRegionstate maps entry iterator too
    public boolean hasNext() {
      if (this.moveNext) {
        for (;;) {
          final Iterator<RegionEntry> bucketEntriesIter = this.bucketEntriesIter;
          if (bucketEntriesIter != null
              && bucketEntriesIter.hasNext()) {
            final RegionEntry val = bucketEntriesIter.next();
            if (val != null) {
              if (val.isMarkedForEviction() && !includeHDFS && !forUpdate) {
                // entry has been faulted in from HDFS, skip
                continue;
              }
              // KN: If DiskIterator has been initialized then we need to change
              // the current bucket id and the current bucket region accordingly
              // as otherwise there will be a mismatch.
              // The keepDiskMap flag aggregates overflowed entries across
              // BucketRegions and so this diskIterator is a single one across
              // entire PR (local buckets) so this needs to be set explicitly.
              if (this.diskIteratorInitialized
                  && !(val instanceof NonLocalRegionEntry)) {
                setCurrRegionAndBucketId(val);
              }
            }
            //TODO: Suranjan How will it work for tx? There will be no BucketRegion for NonLocalRegionEntry
            // Ideally for tx there shouldn't be a case of iterator fetching remote entry
            // For snapshot make sure that it returns either the old entry or new entry depending on the
            // version in snapshot
            if (this.txState != null && !remoteEntryFetched) {
              this.currentEntry = this.txState.getLocalEntry(
                  PartitionedRegion.this, this.currentBucketRegion,
                  -1 /* not used */, (AbstractRegionEntry)val, this.forUpdate);
            }
            else {
              this.currentEntry = val;
            }
            // For snapshot Isolation do the check here.
            // get the snapshot information from TxState.TxRegionState.
            if (this.currentEntry != null /*&& !checkIfCorrectVersion(this.currentEntry)*/) {
              this.moveNext = false;
              return true;
            }
            continue;
          }
          // visiting the first bucket or reaching the end of the entries set
          for (;;) {
            if (!this.bucketIdsIter.hasNext()) {
              // check for an open disk iterator
              if (bucketEntriesIter instanceof DiskSavyIterator) {
                if (((DiskSavyIterator)bucketEntriesIter)
                    .initDiskIterator()) {
                  this.diskIteratorInitialized = true;
                  break;
                }
              }
              // no more buckets need to be visited
              this.bucketEntriesIter = null;
              this.moveNext = false;
              if (commitOnClose) {
                getCache().getCacheTransactionManager().masqueradeAs(this.txState);
                getCache().getCacheTransactionManager().commit();
                commitOnClose = false;
              }
              return false;
            }
            final int bucketId = this.bucketIdsIter.next().intValue();
            BucketRegion br = null;
            try {
              br = dataStore.getInitializedBucketForId(null, bucketId);
            } catch (ForceReattemptException fre) {

              //TODO: Suranjan in case of exception get all the entries
              // from remote and return the iterator
              if (logger.fineEnabled()) {
                logger.fine("PRLocalScanIterator#hasNext: bucket not "
                    + "available for ID " + bucketId + ", PR: "
                    + PartitionedRegion.this.toString());
              }
              if (!fetchRemoteEntries) {
                checkReadiness();
                throw new InternalFunctionInvocationTargetException(
                    fre.getLocalizedMessage(), fre);
              }
            }
            if (br != null) {
              if (logger.fineEnabled()) {
                logger.fine("PRLocalScanIterator#hasNext: bucket "
                    + "available for ID " + bucketId + ", PR: "
                    + PartitionedRegion.this.toString());
              }
              setLocalBucketEntryIterator(br, bucketId);
              this.remoteEntryFetched = false;
            } else {
              if (logger.fineEnabled()) {
                logger.fine("PRLocalScanIterator#hasNext: bucket not "
                    + "available for ID " + bucketId + ", PR: "
                    + PartitionedRegion.this.toString() + ". Fetching from remote node");
              }
              setRemoteBucketEntriesIterator(bucketId);
              this.remoteEntryFetched = true;
            }
            break;
          }
        }
      }
      if (this.currentEntry == null && commitOnClose) {
        getCache().getCacheTransactionManager().masqueradeAs(this.txState);
        getCache().getCacheTransactionManager().commit();
        commitOnClose = false;
      }
      return (this.currentEntry != null);
    }

    private void setLocalBucketEntryIterator(BucketRegion br, int bucketId) {
      this.currentBucketId = bucketId;
      this.currentBucketRegion = br;
      if (this.bucketEntriesIter == null) {
        long cacheSize = this.numEntries >= 0 ? adjustDiskIterCacheSize(
            DistributedRegion.MAX_PENDING_ENTRIES, this.numEntries) : 0;
        this.bucketEntriesIter = br.getBestLocalIterator(
            this.includeValues, cacheSize, true);
        if (this.bucketEntriesIter instanceof HDFSIterator) {
          if (this.forUpdate) {
            ((HDFSIterator)bucketEntriesIter).setForUpdate();
          }
          if (this.txState != null) {
            ((HDFSIterator)bucketEntriesIter).setTXState(this.txState);
          }
        }
      } else if (!(this.bucketEntriesIter instanceof DiskSavyIterator)) {
        this.bucketEntriesIter = br.entries.regionEntries().iterator();
        if (this.bucketEntriesIter instanceof HDFSIterator) {
          if (this.forUpdate) {
            ((HDFSIterator)bucketEntriesIter).setForUpdate();
          }
          if (this.txState != null) {
            ((HDFSIterator)bucketEntriesIter).setTXState(this.txState);
          }
        }
      } else {
        // wait for region recovery etc.
        br.getDiskIteratorCacheSize(1.0);
        ((DiskSavyIterator)this.bucketEntriesIter).setRegion(br);
      }
    }

    private void setRemoteBucketEntriesIterator(int bucketId) {
      this.currentBucketId = bucketId;
      this.currentBucketRegion = null;
      Set<RegionEntry> entries = getBucketEntries(bucketId);
      if (entries != null) {
        this.bucketEntriesIter = entries.iterator();
      }
      // if null, then iterator already set
    }

    private Set<RegionEntry> getBucketEntries(final int bucketId) {
      final int retryAttempts = calcRetry();
      Set<RegionEntry> entries = null;
      int count = 0;
      InternalDistributedMember nod = getOrCreateNodeForBucketRead(bucketId);
      RetryTimeKeeper snoozer = null;
      while (count <= retryAttempts) {
        // It's possible this is a GemFire thread e.g. ServerConnection
        // which got to this point because of a distributed system shutdown or
        // region closure which uses interrupt to break any sleep() or wait()
        // calls
        // e.g. waitForPrimary or waitForBucketRecovery
        checkShutdown();

        if (nod == null) {
          if (snoozer == null) {
            snoozer = new RetryTimeKeeper(retryTimeout);
          }
          nod = getOrCreateNodeForBucketRead(bucketId);

          // No storage found for bucket, early out preventing hot loop, bug 36819
          if (nod == null) {
            checkShutdown();
            break;
          }
          count++;
          continue;
        }

        try {
          if (nod.equals(getMyId())) {
            final BucketRegion r = dataStore.getInitializedBucketForId(null, bucketId);
            setLocalBucketEntryIterator(r, bucketId);
            return null;
          } else {
            final TXStateInterface tx = getTXState();
            final FetchEntriesResponse r;
            r = FetchEntriesMessage.send(nod,
                dataStore.getPartitionedRegion(), tx, bucketId);
            entries = r.waitForEntriesSet();
          }
          if (entries != null) {
            return entries;
          }
        } catch (ForceReattemptException prce) {
          if (logger.fineEnabled()) {
            logger.fine("getBucketEntries: attempt:" + (count + 1), logger.finerEnabled() ? prce : null);
          }
          checkReadiness();
          if (snoozer == null) {
            snoozer = new RetryTimeKeeper(retryTimeout);
          }
          InternalDistributedMember oldNode = nod;
          nod = getNodeForBucketRead(bucketId);
          if (nod != null && nod.equals(oldNode)) {
            if (snoozer.overMaximum()) {
              checkReadiness();
              throw new TimeoutException(LocalizedStrings
                  .PartitionedRegion_ATTEMPT_TO_ACQUIRE_PRIMARY_NODE_FOR_READ_ON_BUCKET_0_TIMED_OUT_IN_1_MS
                  .toLocalizedString(new Object[]{getBucketName(bucketId),
                      snoozer.getRetryTime()}));
            }
            snoozer.waitToRetryNode();
          }

        }
        count++;
      }
      if (logger.fineEnabled()) {
        logger.fine("getBucketEntries: no entries found returning empty set");
      }
      return Collections.EMPTY_SET;
    }

    private void setCurrRegionAndBucketId(RegionEntry val) {
      if (this.cb != null) {
        int bucketId = this.cb.getBucketIdFromRegionEntry(val);
        this.currentBucketId = bucketId;
        this.currentBucketRegion = dataStore.localBucket2RegionMap.get(bucketId);
      }
    }

    public Object next() {
      final Object entry = this.currentEntry;
      if (entry != null) {
        this.moveNext = true;
        return entry;
      }
      throw new NoSuchElementException();
    }

    public void remove() {
      throw new UnsupportedOperationException(
          "PRLocalScanIterator::remove: unexpected call");
    }

    public final int getBucketId() {
      return this.currentBucketId;
    }

    public final BucketRegion getBucket() {
      return this.currentBucketRegion;
    }

    public final BucketRegion getHostedBucketRegion() {
      return this.currentBucketRegion;
    }

    public PartitionedRegion getPartitionedRegion() {
      return PartitionedRegion.this;
    }
  }

  /**
   * @since 6.6
   */
  @Override
  public boolean containsValue(final Object value) {
    if (value == null) {
      throw new NullPointerException(LocalizedStrings.LocalRegion_VALUE_FOR_CONTAINSVALUEVALUE_CANNOT_BE_NULL.toLocalizedString());
    }
    checkReadiness();
    // First check the values is present locally.
    if (this.getDataStore() != null) {
      ValuesSet vSet = new ValuesSet(this.getDataStore()
          .getAllLocalPrimaryBucketIds(), getTXState());
      Iterator itr = vSet.iterator();
      while (itr.hasNext()) {
        Object v = itr.next();
        if (v.equals(value)) {
          return true;
        }
      }
    }
    
    ResultCollector rc = null;
    try {
      rc = FunctionService.onRegion(this).withArgs(value)
          .execute(PRContainsValueFunction.class.getName());
      List<Boolean> results = ((List<Boolean>)rc.getResult());
      for(Boolean r: results){
        if(r){
          return true;
        }
      }
    }
    catch (FunctionException fe) {
      checkShutdown();
      this.logger.warning(LocalizedStrings.PR_CONTAINSVALUE_WARNING,fe.getCause());  
    }
    return false;
  }

  @Override
  public final boolean containsKey(Object key, Object callbackArg) {
    checkReadiness();
    validateKey(key);
    return getDataView().containsKey(key, callbackArg, this);
  }

  @Override
  public final boolean txContainsKey(final Object key,
      final Object callbackArg, final TXStateInterface tx,
      final boolean explicitReadOnlyLock) {
    if (explicitReadOnlyLock) {
      return super.txContainsKey(key, callbackArg, tx, true);
    }
    final long startTime = PartitionedRegionStats.startTime();
    boolean contains = false;
    try {
      final int bucketId = PartitionedRegionHelper.getHashKey(this,
          Operation.CONTAINS_KEY, key, null, callbackArg);
      final Integer bucketIdInt = Integer.valueOf(bucketId);
      InternalDistributedMember targetNode = getOrCreateNodeForBucketRead(bucketId);
      // targetNode null means that this key is not in the system.
      if (targetNode != null) {
        contains = containsKeyInBucket(targetNode, bucketIdInt, key, false);
      }
    }
    finally {
      this.prStats.endContainsKey(startTime);
    }
    return contains;
  }

  final boolean containsKeyInBucket(final InternalDistributedMember targetNode,
      final Integer bucketIdInt, final Object key, boolean valueCheck) {
    final int retryAttempts = calcRetry();
    if (logger.finerEnabled()) {
      logger.finer("containsKeyInBucket: "
          + (valueCheck ? "ValueForKey key=" : "Key key=") + key + " ("
          + key.hashCode() + ") from: " + targetNode + " bucketId="
          + bucketStringForLogs(bucketIdInt.intValue()));
    }
    boolean ret;
    int count = 0;
    RetryTimeKeeper retryTime = null;
    InternalDistributedMember retryNode = targetNode;
    while (count <= retryAttempts) {
      // Every continuation should check for DM cancellation
      if (retryNode == null) {
        checkReadiness();
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        if (retryTime.overMaximum()) {
          break;
        }
        retryNode = getOrCreateNodeForBucketRead(bucketIdInt.intValue());
        // No storage found for bucket, early out preventing hot loop, bug 36819
        if (retryNode == null) {
          checkShutdown(); // Prefer closed style exceptions over empty result
          return false;
        }

        continue;
      } // retryNode != null
      try {
        final boolean loc = retryNode.equals(getMyId());
        if (loc) {
          if (valueCheck) {
            ret = this.dataStore.containsValueForKeyLocally(bucketIdInt, key);
          }
          else {
            ret = this.dataStore.containsKeyLocally(bucketIdInt, key);
          }
        }
        else {
          if (valueCheck) {
            ret = containsValueForKeyRemotely(retryNode,
                null /* no TX callers */, bucketIdInt, key);
          }
          else {
            ret = containsKeyRemotely(retryNode, null /* no TX callers */,
                bucketIdInt, key);
          }
        }
        return ret;
      }
      catch (PRLocallyDestroyedException pde) {
        if (logger.fineEnabled()) {
          logger
              .fine("containsKeyInBucket: Encountered PRLocallyDestroyedException"
                  + pde);
        }
        checkReadiness();
      }
      catch (ForceReattemptException prce) {
        prce.checkKey(key);
        if (logger.fineEnabled()) {
          logger.fine("containsKeyInBucket: retry attempt:" + count + " of "
              + retryAttempts, logger.finerEnabled() ? prce : null);
        }
        checkReadiness();

        InternalDistributedMember lastNode = retryNode;
        retryNode = getOrCreateNodeForBucketRead(bucketIdInt.intValue());
        if (lastNode.equals(retryNode)) {
          if (retryTime == null) {
            retryTime = new RetryTimeKeeper(this.retryTimeout);
          }
          if (retryTime.overMaximum()) {
            break;
          }
          retryTime.waitToRetryNode();
        }
      }
      catch (PrimaryBucketException notPrimary) {
        if (logger.fineEnabled()) {
          logger.fine("containsKeyInBucket " + notPrimary.getLocalizedMessage()
              + " on Node " + retryNode + " not primary ");
        }
        getRegionAdvisor().notPrimary(bucketIdInt.intValue(), retryNode);
        retryNode = getOrCreateNodeForBucketRead(bucketIdInt.intValue());
      }
      catch (RegionDestroyedException rde) {
        if (!rde.getRegionFullPath().equals(getFullPath())) {
          RegionDestroyedException rde2 = new RegionDestroyedException(toString(), getFullPath());
          rde2.initCause(rde);
          throw rde2;
        }
      }

      // It's possible this is a GemFire thread e.g. ServerConnection
      // which got to this point because of a distributed system shutdown or
      // region closure which uses interrupt to break any sleep() or wait()
      // calls
      // e.g. waitForPrimary
      checkShutdown();

      count++;
      if (count == 1) {
        this.prStats.incContainsKeyValueOpsRetried();
      }
      this.prStats.incContainsKeyValueRetries();

    }

    StringId msg = null;
    if (valueCheck) {
      msg = LocalizedStrings.PartitionedRegion_NO_VM_AVAILABLE_FOR_CONTAINS_VALUE_FOR_KEY_IN_1_ATTEMPTS;
    } else {
      msg = LocalizedStrings.PartitionedRegion_NO_VM_AVAILABLE_FOR_CONTAINS_KEY_IN_1_ATTEMPTS;
    }
    
    Integer countInteger = Integer.valueOf(count);
    PartitionedRegionDistributionException e = null; // Fix for bug 36014
    if (logger.fineEnabled()) {
      e = new PartitionedRegionDistributionException(msg.toLocalizedString(countInteger));
    }
    logger.warning(msg, countInteger, e);
    return false;
  }

  /**
   * Checks if a key is contained remotely.
   * 
   * @param targetNode
   *                the node where bucket region for the key exists.
   * @param tx
   *                the TX state for current operation
   * @param bucketId
   *                the bucket id for the key.
   * @param key
   *                the key, whose value needs to be checks
   * @return true if the passed key is contained remotely.
   * @throws PrimaryBucketException
   *                 if the remote bucket was not the primary
   * @throws ForceReattemptException
   *                 if the peer is no longer available
   */
  public boolean containsKeyRemotely(InternalDistributedMember targetNode,
      TXStateInterface tx, Integer bucketId, Object key)
      throws PrimaryBucketException, ForceReattemptException {
    ContainsKeyValueResponse r = ContainsKeyValueMessage.send(targetNode, this,
        tx, key, bucketId, false);
    this.prStats.incPartitionMessagesSent();
    return r.waitForContainsResult();
  }

  /**
   * Returns whether there is a valid (non-null) value present for the specified
   * key, locally or remotely. This method is equivalent to:
   * 
   * <pre>
   * Entry e = getEntry(key);
   * return e != null &amp;&amp; e.getValue() != null;
   * </pre>
   * 
   * This method does not consult localCache, even if enabled.
   * 
   * @param key
   *                the key to check for a valid value
   * @return true if there is an entry in this region for the specified key and
   *         it has a valid value 
   * OVERRIDES
   */
  @Override
  public boolean containsValueForKey(Object key) {
    // checkClosed();
    checkReadiness();
    validateKey(key);
    final long startTime = PartitionedRegionStats.startTime();
    boolean containsValueForKey = false;
    try {
      containsValueForKey = getDataView().containsValueForKey(key, null, this);
    }
    finally {
      this.prStats.endContainsValueForKey(startTime);
    }
    return containsValueForKey;
  }

  @Override
  protected final boolean txContainsValueForKey(Object key,
      Object callbackArg, TXStateInterface tx) {
    boolean containsValueForKey = false;
    final int bucketId = PartitionedRegionHelper.getHashKey(this,
        Operation.CONTAINS_VALUE_FOR_KEY, key, null, callbackArg);
    InternalDistributedMember targetNode = getOrCreateNodeForBucketRead(bucketId);
    // targetNode null means that this key is not in the system.
    if (targetNode != null) {
      containsValueForKey = containsKeyInBucket(targetNode, bucketId, key, true);
    }
    return containsValueForKey;
  }
  
  /**
   * Checks if this instance contains a value for the key remotely.
   * 
   * @param targetNode
   *                the node where bucket region for the key exists.
   * @param tx
   *                the TX state for current operation
   * @param bucketId
   *                the bucket id for the key.
   * @param key
   *                the key, whose value needs to be checks
   * @return true if there is non-null value for the given key
   * @throws PrimaryBucketException
   *                 if the remote bucket was not the primary
   * @throws ForceReattemptException
   *                 if the peer is no longer available
   */
  boolean containsValueForKeyRemotely(InternalDistributedMember targetNode,
      TXStateInterface tx, Integer bucketId, Object key)
      throws PrimaryBucketException, ForceReattemptException {
    if (logger.fineEnabled()) {
      logger.fine("containsValueForKeyRemotely: key=" + key);
    }
    ContainsKeyValueResponse r = ContainsKeyValueMessage.send(targetNode, this,
        tx, key, bucketId, true);
    this.prStats.incPartitionMessagesSent();
    return r.waitForContainsResult();
  }

  /**
   * Get the VSD statistics type
   * 
   * @return the statistics instance specific to this Partitioned Region
   */
  public PartitionedRegionStats getPrStats() {
    return this.prStats;
  }

  // fix for bug #42945 - PR.size() does not pay attention to transaction state
//  @Override
//  public int entryCount() {
//    return entryCount(null);
//  }
  /* non-transactional size calculation */
  @Override
  public int getRegionSize() {
    return entryCount(getTXState(), null);
  }

  public int entryCount(boolean localOnly) {
    if (localOnly) {
      if (this.isDataStore()) {
        return entryCount(getTXState(),
            new THashSet(this.dataStore.getAllLocalBucketIds()));
      }
      else {
        return 0;
      }
    }
    else {
      return entryCount(null);
    }
  }

  @Override
  protected int entryCount(TXStateInterface tx, Set<Integer> buckets,
      boolean estimate) {
    Map<Integer, SizeEntry> bucketSizes = null;
    if (isHDFSReadWriteRegion() && (includeHDFSResults() || estimate)) {
      bucketSizes = getSizeForHDFS(tx, buckets, estimate);
      
    } else {
      if (buckets != null) {
        if (this.dataStore != null) {
          bucketSizes = this.dataStore.getSizeLocallyForBuckets(buckets);
        }
      }
      else {
        if (this.dataStore != null) {
          bucketSizes = this.dataStore.getSizeForLocalBuckets();
        }
        Set<InternalDistributedMember> recips = getRegionAdvisor()
            .adviseDataStore(true);
        recips.remove(getMyId());
        if (!recips.isEmpty()) {
          Map<Integer, SizeEntry> remoteSizes = new HashMap<Integer, PartitionedRegion.SizeEntry>();
          try {
            remoteSizes = getSizeRemotely(recips, tx, false);
          } catch (ReplyException e) {
            // Remote member will never throw ForceReattemptException or
            // PrimaryBucketException, so any exception on the remote member
            // should be re-thrown
            e.handleAsUnexpected();
          }
          if (logger.fineEnabled()) {
            logger.fine("entryCount: " + this + " remoteSizes=" + remoteSizes);
          }
          if (bucketSizes != null && !bucketSizes.isEmpty()) {
            for (Map.Entry<Integer, SizeEntry> me : remoteSizes.entrySet()) {
              Integer k = me.getKey();
              if (!bucketSizes.containsKey(k) || !bucketSizes.get(k).isPrimary()) {
                bucketSizes.put(k, me.getValue());
              }
            }
          }
          else {
            bucketSizes = remoteSizes;
          }
        }
      }
    }

    int size = 0;
    if (bucketSizes != null) {
      for(SizeEntry entry : bucketSizes.values()) {
        size += entry.getSize();
      }
    }
    return size;
  }

  @Override
  public int entryCount(final TXStateInterface tx, final Set<Integer> buckets) {
    return entryCount(tx, buckets, false);
  }

  @Override
  public long getEstimatedLocalSize() {
    final PartitionedRegionDataStore ds = this.dataStore;
    if (ds != null) {
      return ds.getEstimatedLocalBucketSize(false);
    }
    else {
      return 0;
    }
  }

  private Map<Integer, SizeEntry> getSizeForHDFS(final TXStateInterface tx, final Set<Integer> buckets, boolean estimate) {
    // figure out which buckets to include
    Map<Integer, SizeEntry> bucketSizes = new HashMap<Integer, SizeEntry>();
    getRegionAdvisor().accept(new BucketVisitor<Map<Integer, SizeEntry>>() {
      @Override
      public boolean visit(RegionAdvisor advisor, ProxyBucketRegion pbr,
          Map<Integer, SizeEntry> map) {
        if (buckets == null || buckets.contains(pbr.getBucketId())) {
          map.put(pbr.getBucketId(), null);
          // ensure that the bucket has been created
          pbr.getPartitionedRegion().getOrCreateNodeForBucketWrite(pbr.getBucketId(), null);
        }
        return true;
      }
    }, bucketSizes);

    RetryTimeKeeper retry = new RetryTimeKeeper(retryTimeout);

    while (true) {
      // get the size from local buckets
      if (dataStore != null) {
        Map<Integer, SizeEntry> localSizes;
        if (estimate) {
          localSizes = dataStore.getSizeEstimateForLocalPrimaryBuckets();
        } else {
          localSizes = dataStore.getSizeForLocalPrimaryBuckets();
        }
        for (Map.Entry<Integer, SizeEntry> me : localSizes.entrySet()) {
          if (bucketSizes.containsKey(me.getKey())) {
            bucketSizes.put(me.getKey(), me.getValue());
          }
        }
      }
      // all done
      int count = 0;
      Iterator it = bucketSizes.values().iterator();
      while (it.hasNext()) {
        if (it.next() != null) count++;
      }
      if (bucketSizes.size() == count) {
        return bucketSizes;
      }
      
      Set<InternalDistributedMember> remotes = getRegionAdvisor().adviseDataStore(true);
      remotes.remove(getMyId());
      
      // collect remote sizes
      if (!remotes.isEmpty()) {
        Map<Integer, SizeEntry> remoteSizes = new HashMap<Integer, PartitionedRegion.SizeEntry>();
        try {
          remoteSizes = getSizeRemotely(remotes, tx, estimate);
        } catch (ReplyException e) {
          // Remote member will never throw ForceReattemptException or
          // PrimaryBucketException, so any exception on the remote member
          // should be re-thrown
          e.handleAsUnexpected();
        }
        for (Map.Entry<Integer, SizeEntry> me : remoteSizes.entrySet()) {
          Integer k = me.getKey();
          if (bucketSizes.containsKey(k) && me.getValue().isPrimary()) {
            bucketSizes.put(k, me.getValue());
          }
        }
      }
      
      if (retry.overMaximum()) {
        checkReadiness();
        PRHARedundancyProvider.timedOut(this, null, null, "calculate size", retry.getRetryTime());
      }
      
      // throttle subsequent attempts
      retry.waitForBucketsRecovery();
    }
  }
  
  /**
   * This method gets a PartitionServerSocketConnection to targetNode and sends
   * size request to the node. It returns size of all the buckets "primarily"
   * hosted on that node. Here "primarily" means that for a given bucketID that
   * node comes first in the node list. This selective counting ensures that
   * redundant bucket size is added only once.
   * 
   * @param targetNodes
   *                the target set of nodes to get the bucket sizes
   * @param tx
   *                the TX state for current operation
   * @return the size of all the buckets hosted on the target node.
   */
  private Map<Integer, SizeEntry> getSizeRemotely(Set targetNodes,
      TXStateInterface tx, boolean estimate) {
    SizeResponse r = SizeMessage.send(targetNodes, this, tx, null, estimate);
    this.prStats.incPartitionMessagesSent();
    Map retVal = null;
    try {
      retVal = r.waitBucketSizes();
    } catch (CacheException e) {
      checkReadiness();
      throw e;
    }
    return retVal;
  }

  static int getRandom(int max) {
    if (max <= 0) {
      return 0;
    }
    int ti = ((Integer)PartitionedRegion.threadRandom.get()).intValue();
    return ti % max;
  }

  /**
   * Returns the lockname used by Distributed Lock service to clean the
   * <code> allPartitionedRegions<code>
   *
   * @return String
   */
  private String getLockNameForBucket2NodeModification(int bucketID) {
    return (getRegionIdentifier() + ":" + bucketID);
  }

  /**
   * A simple container class that holds the lock name and the service that
   * created the lock, typically used for locking buckets, but not restricted to
   * that usage.
   * 
   * @author mthomas
   * @since 5.0
   */
  static class BucketLock {

    protected final DLockService lockService;

    protected final String lockName;

    private boolean lockOwned = false;

    private final GemFireCacheImpl cache;
    
    private final boolean enableAlerts;

    protected BucketLock(String lockName, GemFireCacheImpl cache, boolean enableAlerts) {
      this.lockService = (DLockService)
          cache.getPartitionedRegionLockService();
      this.cache = cache;
      this.lockName = lockName;
      this.enableAlerts = enableAlerts;
    }

    /**
     * Locks the given name (provided during construction) uninterruptibly or
     * throws an exception.
     * 
     * @throws LockServiceDestroyedException
     */
    public void lock() {
      try {
        basicLock();
      }
      catch (LockServiceDestroyedException e) {
        cache.getCancelCriterion().checkCancelInProgress(null);
        throw e;
      }
    }

    /**
     * Attempts to lock the given name (provided during construction)
     * uninterruptibly
     * 
     * @return true if the lock was acquired, otherwise false.
     * @throws LockServiceDestroyedException
     */
    public boolean tryLock() {
      try {
        cache.getCancelCriterion().checkCancelInProgress(null);
        basicTryLock(
            PartitionedRegionHelper.DEFAULT_WAIT_PER_RETRY_ITERATION);
      }
      catch (LockServiceDestroyedException e) {
        cache.getCancelCriterion().checkCancelInProgress(null);
        throw e;
      }
      return this.lockOwned;
    }
    
    

    private void basicLock() {
      if(enableAlerts) {
        ReplyProcessor21.forceSevereAlertProcessing();
      }
      try {
        DM dm = cache.getDistributedSystem().getDistributionManager();
  
        long ackWaitThreshold = 0; 
        long ackSAThreshold = dm.getConfig().getAckSevereAlertThreshold() * 1000;
        boolean suspected = false;
        boolean severeAlertIssued = false;
        DistributedMember lockHolder = null;
        
        long waitInterval;
        long startTime;
  
        if(!enableAlerts) {
          //Make sure we only attempt the lock long enough not to
          //get a 15 second warning from the reply processor.
          ackWaitThreshold = dm.getConfig().getAckWaitThreshold() * 1000;
          waitInterval = ackWaitThreshold - 1;
          startTime = System.currentTimeMillis();
        }
        else if (ackSAThreshold > 0) {
          ackWaitThreshold = dm.getConfig().getAckWaitThreshold() * 1000;
          waitInterval = ackWaitThreshold;
          startTime = System.currentTimeMillis();
        }
        else {
          waitInterval = PartitionedRegion.VM_OWNERSHIP_WAIT_TIME;
          startTime = 0;
        }
  
        while (!this.lockOwned) {
          cache.getCancelCriterion().checkCancelInProgress(null);
          this.lockOwned = this.lockService.lock(this.lockName,
              waitInterval, -1);
          if (!this.lockOwned && ackSAThreshold > 0 && enableAlerts) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > ackWaitThreshold && enableAlerts) {
              if (!suspected) {
                suspected = true;
                severeAlertIssued = false;
                waitInterval = ackSAThreshold;
                DLockRemoteToken remoteToken = this.lockService.queryLock(this.lockName);
                lockHolder = remoteToken.getLessee();
                if (lockHolder != null) {
                  dm.getMembershipManager()
                    .suspectMember(lockHolder,
                      "Has not released a partitioned region lock in over "
                      + ackWaitThreshold / 1000 + " sec");
                }
              }
              else if (elapsed > ackSAThreshold  && enableAlerts) {
                DLockRemoteToken remoteToken = this.lockService.queryLock(this.lockName);
                if (lockHolder != null && remoteToken.getLessee() != null
                    && lockHolder.equals(remoteToken.getLessee())) {
                  if (!severeAlertIssued) {
                    severeAlertIssued = true;
                    cache.getLoggerI18n().severe(
                      LocalizedStrings.PartitionedRegion_0_SECONDS_HAVE_ELAPSED_WAITING_FOR_THE_PARTITIONED_REGION_LOCK_HELD_BY_1,
                      new Object[] {Long.valueOf((ackWaitThreshold+ackSAThreshold)/1000), lockHolder});     
                  }
                }
                else {
                  // either no lock holder now, or the lock holder has changed
                  // since the ackWaitThreshold last elapsed
                  suspected = false;
                  waitInterval = ackWaitThreshold;
                  lockHolder = null;
                }
              }
            }
          }
        }
      }
      finally {
        if(enableAlerts) {
          ReplyProcessor21.unforceSevereAlertProcessing();
        }
      }
    }
    
    
    
    private void basicTryLock(long time)
    {
      
      final Object key = this.lockName;

      final DM dm = cache.getDistributedSystem().getDistributionManager();

      long start = System.currentTimeMillis();
      long end;
      long timeoutMS = time;
      if (timeoutMS < 0) {
        timeoutMS = Long.MAX_VALUE;
        end = Long.MAX_VALUE;
      }
      else {
        end = start + timeoutMS;
      }

      long ackSAThreshold = cache.getDistributedSystem().getConfig().getAckSevereAlertThreshold() * 1000;
      boolean suspected = false;
      boolean severeAlertIssued = false;
      DistributedMember lockHolder = null;

      long waitInterval;
      long ackWaitThreshold;

      if (ackSAThreshold > 0) {
        ackWaitThreshold = cache.getDistributedSystem().getConfig().getAckWaitThreshold() * 1000;
        waitInterval = ackWaitThreshold;
        start = System.currentTimeMillis();
      }
      else {
        waitInterval = timeoutMS;
        ackWaitThreshold = 0;
        start = 0;
      }

      do {
        try {
          waitInterval = Math.min(end-System.currentTimeMillis(), waitInterval);
          ReplyProcessor21.forceSevereAlertProcessing();
          this.lockOwned = this.lockService.lock(key,
                waitInterval, -1, true, false);
          if (this.lockOwned) {
            return;
          }
          if (ackSAThreshold > 0) {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > ackWaitThreshold) {
              if (!suspected) {
                // start suspect processing on the holder of the lock
                suspected = true;
                severeAlertIssued = false; // in case this is a new lock holder
                waitInterval = ackSAThreshold;
                DLockRemoteToken remoteToken =
                  this.lockService.queryLock(key);
                lockHolder = remoteToken.getLessee();
                if (lockHolder != null) {
                  dm.getMembershipManager()
                  .suspectMember(lockHolder,
                      "Has not released a global region entry lock in over "
                      + ackWaitThreshold / 1000 + " sec");
                }
              }
              else if (elapsed > ackSAThreshold) {
                DLockRemoteToken remoteToken =
                  this.lockService.queryLock(key);
                if (lockHolder != null && remoteToken.getLessee() != null
                    && lockHolder.equals(remoteToken.getLessee())) {
                  if (!severeAlertIssued) {
                    severeAlertIssued = true;
                    cache.getLoggerI18n().severe(
                        LocalizedStrings.PartitionedRegion_0_SECONDS_HAVE_ELAPSED_WAITING_FOR_GLOBAL_REGION_ENTRY_LOCK_HELD_BY_1,
                        new Object[] {Long.valueOf(ackWaitThreshold+ackSAThreshold)/1000 /* fix for bug 44757*/, lockHolder});
                  }
                }
                else {
                  // the lock holder has changed
                  suspected = false;
                  waitInterval = ackWaitThreshold;
                  lockHolder = null;
                }
              }
            }
          } // ackSAThreshold processing
        }
        catch (IllegalStateException ex) {
          cache.getCancelCriterion().checkCancelInProgress(null);
          throw ex;
        }
        finally {
          ReplyProcessor21.unforceSevereAlertProcessing();
        }
      } while (System.currentTimeMillis() < end);
    }

    /**
     * Ask the grantor who has the lock
     * @return the ID of the member holding the lock
     */
    public DistributedMember queryLock() {
      try {
        DLockRemoteToken remoteToken = this.lockService.queryLock(this.lockName);
        return remoteToken.getLessee();
      }
      catch (LockServiceDestroyedException e) {
        cache.getCancelCriterion().checkCancelInProgress(null);
        throw e;
      }
    }

    public void unlock() {
      if (this.lockOwned) {
        try {
          this.lockService.unlock(this.lockName);
        }
        catch (LockServiceDestroyedException ignore) {
          // cache was probably closed which destroyed this lock service
          // note: destroyed lock services release all held locks
          cache.getCancelCriterion().checkCancelInProgress(null);
          if (cache.getLoggerI18n().fineEnabled()) {
            cache.getLoggerI18n().fine("BucketLock#unlock: " + "Lock service "
                + this.lockService + " was destroyed", ignore);
          }
        }
        finally {
          this.lockOwned = false;
        }
      }
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }
      if (!(obj instanceof BucketLock)) {
        return false;
      }

      BucketLock other = (BucketLock)obj;
      if (!this.lockName.equals(other.lockName))
        return false;

      DLockService ls1 = lockService;
      DLockService ls2 = other.lockService;
      if (ls1 == null || ls2 == null) {
        if (ls1 != ls2)
          return false;
      }
      return ls1.equals(ls2);
    }

    @Override
    public int hashCode() {
      return this.lockName.hashCode();
    }

    @Override
    public String toString() {
      return "BucketLock@" + System.identityHashCode(this) + "lockName="
          + this.lockName + " lockService=" + this.lockService;
    }
  } // end class BucketLock

  public BucketLock getBucketLock(int bucketID) {
    String lockName = getLockNameForBucket2NodeModification(bucketID);
    return new BucketLock(lockName, getCache(), false);
  }

  public final static class RegionLock extends BucketLock {
    protected RegionLock(String regionIdentifier, GemFireCacheImpl cache) {
      super(regionIdentifier, cache, true);
    }

    @Override
    public String toString() {
      return "RegionLock@" + System.identityHashCode(this) + "lockName="
          + super.lockName + " lockService=" + super.lockService;
    }
  }

  public final class RecoveryLock extends BucketLock {
    protected RecoveryLock() {
      super(PartitionedRegion.this.getRegionIdentifier() + "-RecoveryLock", getCache(), false);
    }

    @Override
    public String toString() {
      return "RecoveryLock@" + System.identityHashCode(this) + "lockName="
          + super.lockName + " lockService=" + super.lockService;
    }
  }

  @Override
  public final int hashCode() {
    return RegionInfoShip.getHashCode(getPRId(), -1);
  }

  @Override
  protected StringBuilder getStringBuilder() {
    return super.getStringBuilder()
      .append("; prId=").append(this.partitionedRegionId)
      .append("; isDestroyed=").append(this.isDestroyed) 
      .append("; isClosed=").append(this.isClosed)
      .append("; retryTimeout=").append(this.retryTimeout)
      .append("; serialNumber=").append(getSerialNumber())
      .append("; hdfsStoreName=").append(getHDFSStoreName())
      .append("; hdfsWriteOnly=").append(getHDFSWriteOnly())
      .append("; partition attributes=").append(getPartitionAttributes())
      .append("; on VM ").append(getMyId());
  }
  
  public RegionLock getRegionLock() {
    return getRegionLock(getRegionIdentifier(), getGemFireCache());
  }

  public static RegionLock getRegionLock(String regionIdentifier, GemFireCacheImpl cache) {
    return new RegionLock(regionIdentifier, cache);
  }
  
  public RecoveryLock getRecoveryLock() {
    return new RecoveryLock();
  }

  public Node getNode() {
    return this.node;
  }

  @Override
  public LoaderHelper createLoaderHelper(Object key, Object callbackArgument,
      boolean netSearchAllowed, boolean netLoadAllowed,
      SearchLoadAndWriteProcessor searcher) {
    return new LoaderHelperImpl(this, key, callbackArgument, netSearchAllowed,
        netLoadAllowed, searcher);
  }

  public PRHARedundancyProvider getRedundancyProvider() {
    return this.redundancyProvider;
  }

  /**
   * This method returns true if region is closed.
   * 
   */
  public void checkClosed() {
    if (this.isClosed) {
      throw new RegionDestroyedException(LocalizedStrings.PartitionedRegion_PR_0_IS_LOCALLY_CLOSED.toLocalizedString(this), getFullPath());
    }
  }

  public final void setHaveCacheLoader() {
    if (!this.haveCacheLoader) {
      this.haveCacheLoader = true;
    }
  }

  /**
   * This method closes the partitioned region locally. It is invoked from the
   * postDestroyRegion method of LocalRegion {@link LocalRegion}, which is
   * overridden in PartitionedRegion This method <br>
   * Updates this prId in prIDMap <br>
   * Cleanups the data store (Removes the bucket mapping from b2n region,
   * locally destroys b2n and destroys the bucket regions. <br>
   * sends destroyPartitionedRegionMessage to other VMs so that they update
   * their RegionAdvisor <br>
   * Sends BackupBucketMessage to other nodes
   * 
   * @param event
   *                the region event
   */
  private void closePartitionedRegion(RegionEventImpl event) {
    final boolean isClose = event.getOperation().isClose();
    if (isClose) {
      this.isClosed = true;
    }
    final RegionLock rl = getRegionLock();
    try {
      rl.lock();
      if (logger.infoEnabled()) {
        logger.info(
            LocalizedStrings.PartitionedRegion_PARTITIONED_REGION_0_WITH_PRID_1_CLOSING,
            new Object[] {getFullPath(), Integer.valueOf(getPRId())});
      }
      if(!checkIfAlreadyDestroyedOrOldReference()) {
        PartitionedRegionHelper.removeGlobalMetadataForFailedNode(getNode(),
            this.getRegionIdentifier(), getGemFireCache(), false);
      }
      int serials[] = getRegionAdvisor().getBucketSerials();

      if(!event.getOperation().isClose() && getDiskStore() != null && getDataStore() != null) {
        for(BucketRegion bucketRegion : getDataStore().getAllLocalBucketRegions()) {
          bucketRegion.isDestroyingDiskRegion = true;
          bucketRegion.getDiskRegion().beginDestroy(bucketRegion);
        }
      }

      sendDestroyRegionMessage(event, serials); // Notify other members that this VM
      // no longer has the region

      // Must clean up pridToPR map so that messages do not get access to buckets 
      // which should be destroyed
      synchronized (prIdToPR) { 
        prIdToPR.put(Integer.valueOf(getPRId()), PRIdMap.LOCALLY_DESTROYED,
            false);
      }

      redundancyProvider.shutdown();

      if (this.bucketSorter != null) {
        this.bucketSorter.shutdownNow();
      }   
      
      if (this.dataStore != null) {
        this.dataStore.cleanUp(true, !isClose);
      }
    }
    finally {
      // Make extra sure that the static is cleared in the event
      // a new cache is created
      synchronized (prIdToPR) {
        prIdToPR
        .put(Integer.valueOf(getPRId()), PRIdMap.LOCALLY_DESTROYED, false);
      }

      rl.unlock();
    }

    if (logger.infoEnabled()) {
      logger.info(
          LocalizedStrings.PartitionedRegion_PARTITIONED_REGION_0_WITH_PRID_1_CLOSED,
          new Object[] {getFullPath(), Integer.valueOf(getPRId())});
    }
  }

  public void checkForColocatedChildren(boolean skipShadowPRs) {
    List<PartitionedRegion> listOfChildRegions = ColocationHelper
        .getColocatedChildRegions(this);
    if (listOfChildRegions.size() != 0) {
      List<String> childRegionList = new ArrayList<String>();
      for (PartitionedRegion childRegion : listOfChildRegions) {
        if (skipShadowPRs && childRegion.isShadowPR) {
          continue;
        }
        if (!childRegion.getName().contains(
            ParallelGatewaySenderQueue.QSTRING)) {
          childRegionList.add(childRegion.getFullPath());
        }
      }
      if (!childRegionList.isEmpty()) {
        throw new IllegalStateException(String.format(
            "The parent region [%s] in colocation chain cannot "
                + "be destroyed, unless all its children [%s] are destroyed",
            this.getFullPath(), childRegionList));
      }
    }
  }

  @Override
  public void destroyRegion(Object aCallbackArgument)
      throws CacheWriterException, TimeoutException {
    
    //For HDFS regions, we need a data store
    //to do the global destroy so that it can delete
    //the data from HDFS as well.
    if(!isDataStore()) {
      if(destroyOnDataStore(aCallbackArgument)) {
        //If we were able to find a data store to do the destroy,
        //stop here.
        //otherwise go ahead and destroy the region from this member
        return;
      }
    }

    checkForColocatedChildren(false);
    getDataView().checkSupportsRegionDestroy();
    checkForLimitedOrNoAccess();

    RegionEventImpl event = new RegionEventImpl(this, Operation.REGION_DESTROY,
        aCallbackArgument, false, getMyId(), generateEventID());
    basicDestroyRegion(event, true);
  }

  /**Globally destroy the partitioned region by sending a message
   * to a data store to do the destroy.
   * @return true if the region was destroyed successfully
   */
  private boolean destroyOnDataStore(Object aCallbackArgument) {
    RegionAdvisor advisor = getRegionAdvisor();
    Set<InternalDistributedMember> attempted = new HashSet<InternalDistributedMember>();
    
    checkReadiness();
    while(!isDestroyed()) {
      Set<InternalDistributedMember> available = advisor.adviseInitializedDataStore();
      available.removeAll(attempted);
      if(available.isEmpty()) {
        return false;
      }
      InternalDistributedMember next = available.iterator().next();
      try {
        DestroyRegionOnDataStoreMessage.send(next, this, aCallbackArgument);
        return true;
      } catch(ReplyException e) {
        //try the next member
        if(logger.fineEnabled()) {
          logger.fine("Error destroying " + this + " on " + next, e);
        }
      }
    }
    
    return true;
  }

  public void destroyParallelGatewaySenderRegion(Operation op, boolean cacheWrite,
      boolean lock, boolean callbackEvents) {

    if (getLogWriterI18n().fineEnabled()) {
      getLogWriterI18n().fine(
          "Destoying parallel queue region for senders: "
              + this.getParallelGatewaySenderIds());
    }

    boolean keepWaiting = true;

    AsyncEventQueueImpl hdfsQueue = getHDFSEventQueue();
    while(true) {
      List<String> pausedSenders = new ArrayList<String>();
      List<ConcurrentParallelGatewaySenderQueue> parallelQueues = new ArrayList<ConcurrentParallelGatewaySenderQueue>();
      isDestroyedForParallelWAN = true;
      int countOfQueueRegionsToBeDestroyed = 0;
      for (String senderId : this.getParallelGatewaySenderIds()) {
        ParallelGatewaySenderImpl sender = (ParallelGatewaySenderImpl)this.cache
            .getGatewaySender(senderId);
        if (sender == null || sender.getEventProcessor() == null) {
          continue;
        }

        if (cacheWrite) { // in case of destroy operation senders should be
                          // resumed
          if (sender.isPaused()) {
            pausedSenders.add(senderId);
            continue;
          }
        }

        if (pausedSenders.isEmpty()) { // if there are puase sender then only
                                       // check for other pause senders instead
                                       // of creating list of shadowPR
          AbstractGatewaySenderEventProcessor ep = sender.getEventProcessor();
          if (ep == null) continue;
          ConcurrentParallelGatewaySenderQueue parallelQueue = (ConcurrentParallelGatewaySenderQueue)ep.getQueue();
          PartitionedRegion parallelQueueRegion = parallelQueue.getRegion(this
              .getFullPath());
          
          // this may be removed in previous iteration
          if (parallelQueueRegion == null || parallelQueueRegion.isDestroyed
              || parallelQueueRegion.isClosed) {
            continue;
          }
          
          parallelQueues.add(parallelQueue);
          countOfQueueRegionsToBeDestroyed++;
        }
      }

      if (!pausedSenders.isEmpty()) {
        String exception = null;
        if (pausedSenders.size() == 1) {
          exception = LocalizedStrings.PartitionedRegion_GATEWAYSENDER_0_IS_PAUSED_RESUME_IT_BEFORE_DESTROYING_USER_REGION_1
              .toLocalizedString(new Object[] { pausedSenders, this.getName() });
        }
        else {
          exception = LocalizedStrings.PartitionedRegion_GATEWAYSENDERS_0_ARE_PAUSED_RESUME_THEM_BEFORE_DESTROYING_USER_REGION_1
              .toLocalizedString(new Object[] { pausedSenders, this.getName() });
        }
        isDestroyedForParallelWAN = false;
        throw new GatewaySenderException(exception);
      }

      if (countOfQueueRegionsToBeDestroyed == 0) {
        break;
      }
      
      for (ConcurrentParallelGatewaySenderQueue parallelQueue : parallelQueues) {
        PartitionedRegion parallelQueueRegion = parallelQueue.getRegion(this
            .getFullPath());
        // CacheWrite true == distributedDestoy. So in case of false, dont wait
        // for queue to drain
        // parallelQueueRegion.size() = With DistributedDestroym wait for queue
        // to drain
        // keepWaiting : comes from the MAXIMUM_SHUTDOWN_WAIT_TIME case handled
        if (cacheWrite && parallelQueueRegion.size() != 0 && keepWaiting) {
          continue;
        }
        else {// In any case, destroy shadow PR locally. distributed destroy of
              // userPR will take care of detsroying shadowPR locally on other
              // nodes.
          RegionEventImpl event = null;
          if (op.isClose()) { // In case of cache close operation, we want SPR's basic destroy to go through CACHE_CLOSE condition of postDestroyRegion not closePartitionedRegion code
            event = new RegionEventImpl(parallelQueueRegion,
                op, null, false, getMyId(),
                generateEventID());
          }
          else {
            event = new RegionEventImpl(parallelQueueRegion,
                Operation.REGION_LOCAL_DESTROY, null, false, getMyId(),
                generateEventID());
          }
          parallelQueueRegion.basicDestroyRegion(event, false, lock,
              callbackEvents);
          parallelQueue.removeShadowPR(this.getFullPath());
          
          countOfQueueRegionsToBeDestroyed--;
          continue;
        }
      }

      if(countOfQueueRegionsToBeDestroyed == 0){
        break;
      }

      if (cacheWrite) {
        if (AbstractGatewaySender.MAXIMUM_SHUTDOWN_WAIT_TIME == -1) {
          keepWaiting = true;
          try {
            Thread.sleep(5000);
          }
          catch (InterruptedException e) {
            // interrupted
          }
        }
        else {
          try {
            Thread
                .sleep(AbstractGatewaySender.MAXIMUM_SHUTDOWN_WAIT_TIME * 1000);
          }
          catch (InterruptedException e) {/* ignore */
            // interrupted
          }
          keepWaiting = false;
        }
      }
    }
    
    if(hdfsQueue != null) {
      hdfsQueue.destroy();
      cache.removeAsyncEventQueue(hdfsQueue);
    }
  }
        
  @Override
  public void localDestroyRegion(Object aCallbackArgument) {
    localDestroyRegion(aCallbackArgument, false);
  }

  /**
   * Locally destroy a region.
   * 
   * GemFireXD change: The parameter "ignoreParent" has been added to allow
   * skipping the check for parent colocated region. This is because GemFireXD
   * DDLs are distributed in any case and are guaranteed to be atomic (i.e. no
   * concurrent DMLs on that table). Without this it is quite ugly to implement
   * "TRUNCATE TABLE" which first drops the table and recreates it.
   */
  public void localDestroyRegion(Object aCallbackArgument, boolean ignoreParent)
  {
    getDataView().checkSupportsRegionDestroy();
    String prName = this.getColocatedWith();
    List<PartitionedRegion> listOfChildRegions = ColocationHelper
        .getColocatedChildRegions(this);

    List<String> childRegionsWithoutSendersList = new ArrayList<String>();
    if (listOfChildRegions.size() != 0) {
      for (PartitionedRegion childRegion : listOfChildRegions) {
        if (!childRegion.getName().contains(ParallelGatewaySenderQueue.QSTRING)) {
          childRegionsWithoutSendersList.add(childRegion.getFullPath());
        }
      }
    }

    if ((!ignoreParent && prName != null)
        || (!childRegionsWithoutSendersList.isEmpty())) {
      // only throw exception if there is a non-parallel WAN colocated region
      String colocatedChild = null;
      boolean isUnsupported = true;
      if (ignoreParent && listOfChildRegions.size() != 0) {
        isUnsupported = false;
        for (PartitionedRegion child : listOfChildRegions) {
          if (child.isShadowPR) {
            continue;
          }
          isUnsupported = true;
          colocatedChild = child.getFullPath();
          break;
        }
      }
      if (isUnsupported) {
        throw new UnsupportedOperationException(
            "Any Region in colocation chain cannot be destroyed locally"
                + (colocatedChild != null ? " (colocated child: "
                    + colocatedChild + ")." : "."));
      }
    }

    RegionEventImpl event = new RegionEventImpl(this,
        Operation.REGION_LOCAL_DESTROY, aCallbackArgument, false, getMyId(),
        generateEventID()/* generate EventID */);
    try {
      basicDestroyRegion(event, false);
    }
    catch (CacheWriterException e) {
      // not possible with local operation, CacheWriter not called
      throw new Error(
          "CacheWriterException should not be thrown in localDestroyRegion", e);
    }
    catch (TimeoutException e) {
      // not possible with local operation, no distributed locks possible
      throw new Error(
          "TimeoutException should not be thrown in localDestroyRegion", e);
    }
  }

  /**
   * This method actually destroys the local accessor and data store. This
   * method is invoked from the postDestroyRegion method of LocalRegion
   * {@link LocalRegion}, which is overridden in PartitionedRegion This method
   * is invoked from postDestroyRegion method. If origin is local: <br>
   * Takes region lock <br>
   * Removes prId from PRIDMap <br>
   * Does data store cleanup (removal of bucket regions) <br>
   * Sends destroyRegionMessage to other nodes <br>
   * Removes it from allPartitionedRegions <br>
   * Destroys bucket2node region
   * 
   * @param event
   *                the RegionEvent that triggered this operation
   * 
   * @see #destroyPartitionedRegionLocally(boolean)
   * @see #destroyPartitionedRegionGlobally(RegionEventImpl)
   * @see #destroyCleanUp(RegionEventImpl, int[])
   * @see DestroyPartitionedRegionMessage
   */
  private void destroyPartitionedRegion(RegionEventImpl event) {
    final RegionLock rl = getRegionLock();
    boolean isLocked = false;
    
    boolean removeFromDisk = !event.getOperation().isClose();
    try {
      if (logger.infoEnabled()) {
        logger.info(
            LocalizedStrings.PartitionedRegion_PARTITIONED_REGION_0_WITH_PRID_1_IS_BEING_DESTROYED,
            new Object[] {getFullPath(), Integer.valueOf(getPRId())});
      }
      if (!event.isOriginRemote()) {
        try {
          rl.lock();
          isLocked = true;
        }
        catch (CancelException e) {
          // ignore
        }
        if (!destroyPartitionedRegionGlobally(event)) {
          destroyPartitionedRegionLocally(removeFromDisk);
        }
      }
      else {
        PartitionRegionConfig prConfig = null;
        try {
          prConfig = this.prRoot
            .get(getRegionIdentifier());
        }
        catch (CancelException e) {
          // ignore; metadata not accessible
        }
        // fix for bug 35306 by Tushar
        if (!checkIfAlreadyDestroyedOrOldReference() && null != prConfig && !prConfig.getIsDestroying()) {
          try {
            rl.lock();
            isLocked = true;
          }
          catch (CancelException e) {
            // ignore
          }
          if (!destroyPartitionedRegionGlobally(event)) {
            destroyPartitionedRegionLocally(removeFromDisk);
          }
        }
        else {
          destroyPartitionedRegionLocally(removeFromDisk);
        }
      }
      if (logger.infoEnabled()) {
        logger.info(
          LocalizedStrings.PartitionedRegion_PARTITIONED_REGION_0_WITH_PRID_1_IS_DESTROYED,
          new Object[] {getFullPath(), Integer.valueOf(getPRId())});
      }
    }
    finally {
      try {
        if (isLocked) {
          rl.unlock();
        }
      }
      catch (Exception es) {
        if (logger.infoEnabled()) {
          logger.info(
            LocalizedStrings.PartitionedRegion_CAUGHT_EXCEPTION_WHILE_TRYING_TO_UNLOCK_DURING_REGION_DESTRUCTION,
            es);
        }
      }
    }
  }

  /**
   * This method destroys the PartitionedRegion globally. It sends destroyRegion
   * message to other nodes and handles cleaning up of the data stores and
   * meta-data.
   * 
   * @param event
   *                the RegionEvent which triggered this global destroy
   *                operation
   * @return true if the region was found globally.
   */
  private boolean destroyPartitionedRegionGlobally(RegionEventImpl event) {
    if (checkIfAlreadyDestroyedOrOldReference()) {
      return false;
    }
    PartitionRegionConfig prConfig;
    try {
      prConfig = prRoot.get(
          this.getRegionIdentifier());
    }
    catch (CancelException e) {
      return false; // global data not accessible, don't try to finish global destroy.
    }
    
    if (prConfig == null || prConfig.getIsDestroying()) {
      return false;
    }
    prConfig.setIsDestroying();
    try {
      this.prRoot.put(this.getRegionIdentifier(), prConfig);
    }
    catch (CancelException e) {
      // ignore; metadata not accessible
    }
    int serials[] = getRegionAdvisor().getBucketSerials();
    final boolean isClose = event.getOperation().isClose();
    destroyPartitionedRegionLocally(!isClose);
    destroyCleanUp(event, serials);
    
    if(!isClose) {
      destroyHDFSData();
    }
    return true;
  }

  /**
   * This method: <br>
   * Sends DestroyRegionMessage to other nodes <br>
   * Removes this PartitionedRegion from allPartitionedRegions <br>
   * Destroys bucket2node region <br>
   * 
   * @param event
   *                the RegionEvent that triggered the region clean up
   * 
   * @see DestroyPartitionedRegionMessage
   */
  private void destroyCleanUp(RegionEventImpl event, int serials[])
  {
    String rId = getRegionIdentifier();
    try {
      if (logger.fineEnabled()) {
        logger.fine("PartitionedRegion#destroyCleanUp: Destroying region: "
            + getFullPath());
      }
      sendDestroyRegionMessage(event, serials);
      try {
        // if this event is global destruction of the region everywhere, remove
        // it from the pr root configuration
        if (null != this.prRoot) {
          this.prRoot.destroy(rId);
        }
      }
      catch (EntryNotFoundException ex) {
        if (logger.fineEnabled()) {
          logger.fine("PartitionedRegion#destroyCleanup: caught exception", ex);
        }
      }
      catch (CancelException e) {
        // ignore; metadata not accessible
      }
    }
    finally {
      if (logger.fineEnabled()) {
        logger.fine("PartitionedRegion#destroyCleanUp: " + "Destroyed region: "
            + getFullPath());
      }
    }
  }

  /**
   * Sends the partitioned region specific destroy region message and waits for
   * any responses. This message is also sent in close/localDestroyRegion
   * operation. When it is sent in Cache close/Region close/localDestroyRegion
   * operations, it results in updating of RegionAdvisor on recipient nodes.
   * 
   * @param event
   *                the destruction event
   * @param serials the bucket serials hosted locally
   * @see Region#destroyRegion()
   * @see Region#close()
   * @see Region#localDestroyRegion()
   * @see GemFireCacheImpl#close()
   */
  private void sendDestroyRegionMessage(RegionEventImpl event, int serials[])
  {
    if (this.prRoot == null) {
      logger.info(LocalizedStrings.DEBUG, "Partition region "+this+" failed to initialize. Remove its profile from remote members.");
      new UpdateAttributesProcessor(this, true).distribute(false);
      return;
    }
    final HashSet configRecipients = new HashSet(getRegionAdvisor()
        .adviseAllPRNodes());
    
    // It's possile this instance has not been initialized
    // or hasn't gotten through initialize() far enough to have
    // sent a CreateRegionProcessor message, bug 36048
    try {
      final PartitionRegionConfig prConfig = this.prRoot.get(getRegionIdentifier());

      if (prConfig != null) {
        // Fix for bug#34621 by Tushar
        Iterator itr = prConfig.getNodes().iterator();
        while (itr.hasNext()) {
          InternalDistributedMember idm = ((Node)itr.next()).getMemberId();
          if (!idm.equals(getMyId())) {
            configRecipients.add(idm);
          }
        }
      }
    }
    catch (CancelException e) {
      // ignore
    }

    try {
      DestroyPartitionedRegionResponse resp = DestroyPartitionedRegionMessage
          .send(configRecipients, this, event, serials);
      resp.waitForRepliesUninterruptibly();
    }
    catch (ReplyException ignore) {
      logger.warning(
          LocalizedStrings.PartitionedRegion_PARTITIONEDREGION_SENDDESTROYREGIONMESSAGE_CAUGHT_EXCEPTION_DURING_DESTROYREGIONMESSAGE_SEND_AND_WAITING_FOR_RESPONSE,
          ignore);
    }
  }

  /**
   * This method is used to destroy this partitioned region data and its
   * associated store and removal of its PartitionedRegionID from local prIdToPR
   * map. This method is called from destroyPartitionedRegion Method and removes
   * the entry from prIdMap and cleans the data store buckets. It first checks
   * whether this PartitionedRegion is already locally destroyed. If it is, this
   * call returns, else if process with the removal from prIdMap and dataStore
   * cleanup.
   * 
   * @see #destroyPartitionedRegion(RegionEventImpl)
   */
  void destroyPartitionedRegionLocally(boolean removeFromDisk) {
    synchronized (this) {
      if (this.isLocallyDestroyed) {
        return;
      }
      this.locallyDestroyingThread = Thread.currentThread();
      this.isLocallyDestroyed = true;
    }
    if (logger.fineEnabled()) {
      logger.fine("destroyPartitionedRegionLocally: Starting destroy for PR = "
          + this);
    }
    try {
      synchronized (prIdToPR) {
        prIdToPR.remove(Integer.valueOf(getPRId()));
      }
      this.redundancyProvider.shutdown(); // see bug 41094
      if (this.bucketSorter != null) {
        this.bucketSorter.shutdownNow();
      }
      if (this.dataStore != null) {
        this.dataStore.cleanUp(false, removeFromDisk);
      }
    }
    finally {
      this.getRegionAdvisor().close();
      getPrStats().close();
      this.cache.getResourceManager(false).removeResourceListener(this);
      this.locallyDestroyingThread = null;
      if (logger.fineEnabled()) {
        logger.fine("destroyPartitionedRegionLocally: Ending destroy for PR = "
            + this);
      }
    }

  }

  /**
   * This method is invoked from recursiveDestroyRegion method of LocalRegion.
   * This method checks the region type and invokes the relevant method.
   * 
   * @param destroyDiskRegion - true if the contents on disk should be destroyed
   * @param event
   *                the RegionEvent <br>
   * OVERRIDES
   */
  @Override
  protected void postDestroyRegion(boolean destroyDiskRegion, RegionEventImpl event) {
    if (logger.fineEnabled()) {
      logger.fine("PartitionedRegion#postDestroyRegion: " + this);
    }
    Assert.assertTrue(this.isDestroyed || this.isClosed);
    // bruce disabled the dumping of entries to keep the size of dunit log files
    // from growing unreasonably large
    // if (this.dataStore != null && logger.fineEnabled()) {
    // this.dataStore.dumpEntries(false);
    // }

    //Fixes 44551 - wait for persistent buckets to finish
    //recovering before sending the destroy region message
    //any GII or wait for persistent recoveery will be aborted by the destroy
    //flag being set to true, so this shouldn't take long.
    this.redundancyProvider.waitForPersistentBucketRecovery();
    // fix #39196 OOME caused by leak in GemFireCache.partitionedRegions
    this.cache.removePartitionedRegion(this);
    this.cache.getResourceManager(false).removeResourceListener(this);
    
    final Operation op = event.getOperation();
    if (op.isClose() || Operation.REGION_LOCAL_DESTROY.equals(op)) {
      try {
        if (Operation.CACHE_CLOSE.equals(op) || 
            Operation.FORCED_DISCONNECT.equals(op)) {
          int serials[] = getRegionAdvisor().getBucketSerials();

          try {
            redundancyProvider.shutdown();
            if (this.bucketSorter != null) {
              this.bucketSorter.shutdownNow();
            }  
            getRegionAdvisor().closeBucketAdvisors();
            // BUGFIX for bug#34672 by Tushar Apshankar. It would update the
            // advisors on other nodes about cache closing of this PartitionedRegion
            sendDestroyRegionMessage(event, serials);
            
            //Because this code path never cleans up the real buckets, we need
            //to log the fact that those buckets are destroyed here
            if(RegionLogger.isEnabled()) {
              PartitionedRegionDataStore store = getDataStore();
              if(store != null) {
                for(BucketRegion bucket: store.getAllLocalBucketRegions()) {
                  RegionLogger.logDestroy(bucket.getFullPath(), getMyId(), bucket.getPersistentID(), true);
                }
              }
            }
          }
          catch (CancelException e) {
            // Don't throw this; we're just trying to remove the region.
            if (logger.fineEnabled()) {
              logger
                  .fine("postDestroyRegion: failed sending DestroyRegionMessage due to cache closure");
            }
          } finally {
            // Since we are not calling closePartitionedRegion
            // we need to cleanup any diskStore we own here.
            // Why don't we call closePartitionedRegion?
            //Instead of closing it, we need to register it to be closed later
            //Otherwise, when the cache close tries to close all of the bucket regions,
            //they'll fail because their disk store is already closed.
            DiskStoreImpl dsi = getDiskStore();
            if (dsi != null && dsi.getOwnedByRegion()) {
              cache.addDiskStore(dsi);
            }
          }

          // Majority of cache close operations handled by
          // afterRegionsClosedByCacheClose(GemFireCache
          // cache) or GemFireCache.close()
        }
        else {
          if (logger.fineEnabled()) {
            logger.fine("Making closePartitionedRegion call for " + this
                + " with origin = " + event.isOriginRemote() + " op= " + op);
          }
          try {
            closePartitionedRegion(event);
          } finally {
            if (Operation.REGION_LOCAL_DESTROY.equals(op)) {
              DiskStoreImpl dsi = getDiskStore();
              if (dsi != null && dsi.getOwnedByRegion()) {
                dsi.destroy();
              }
            }
          }
        }
      } 
      finally {
        // tell other members to recover redundancy for any buckets
        this.getRegionAdvisor().close();
        getPrStats().close();
      }
    }
    else if (Operation.REGION_DESTROY.equals(op) || Operation.REGION_EXPIRE_DESTROY.equals(op)) {
      if (logger.fineEnabled()) {
        logger.fine("PartitionedRegion#postDestroyRegion: "
            + "Making destroyPartitionedRegion call for " + this
            + " with originRemote = " + event.isOriginRemote());
      }
      destroyPartitionedRegion(event);
    }
    else {
      Assert.assertTrue(false, "Unknown op" + op); 
    }
    
    // set client routing information into the event
    // The destroy operation in case of PR is distributed differently
    // hence the FilterRoutingInfo is set here instead of
    // DistributedCacheOperation.distribute().
    if (!isUsedForMetaRegion() && !isUsedForPartitionedRegionAdmin()
        && !isUsedForPartitionedRegionBucket()) {
      FilterRoutingInfo localCqFrInfo = getFilterProfile().getFilterRoutingInfoPart1(event, FilterProfile.NO_PROFILES, Collections.EMPTY_SET);
      FilterRoutingInfo localCqInterestFrInfo = getFilterProfile().getFilterRoutingInfoPart2(localCqFrInfo, event);
      if (localCqInterestFrInfo != null){
        event.setLocalFilterInfo(localCqInterestFrInfo.getLocalFilterInfo());
      }
    }

    if(destroyDiskRegion) {
      DiskStoreImpl dsi = getDiskStore();
      if(dsi != null && getDataPolicy().withPersistence()) {
        dsi.removePersistentPR(getFullPath());
        //Fix for support issue 7870 - remove this regions
        //config from the parent disk store, if we are removing the region.
        final PartitionedRegion colocatedWithRegion = this.colocatedWithRegion;
        if (colocatedWithRegion != null
            && colocatedWithRegion.getDiskStore() != null
            && colocatedWithRegion.getDiskStore() != dsi) {
          colocatedWithRegion.getDiskStore().removePersistentPR(getFullPath());
          
        }
      }
    }
    
    HDFSRegionDirector.getInstance().clear(getFullPath());
    
    RegionLogger.logDestroy(getName(), cache.getMyId(), null, op.isClose());
  }

  /**
   * This method checks whether this PartitionedRegion is eligible for the
   * destruction or not. It first gets the prConfig for this region, and if it
   * NULL, it sends a destroyPartitionedRegionLocally call as a pure
   * precautionary measure. If it is not null, we check if this call is intended
   * for this region only and there is no new PartitionedRegion creation with
   * the same name. This check fixes, bug # 34621.
   * 
   * @return true, if it is eligible for the region destroy
   */
  private boolean checkIfAlreadyDestroyedOrOldReference() {
    boolean isAlreadyDestroyedOrOldReference = false;
    PartitionRegionConfig prConfig = null;
    try {
      prConfig = prRoot.get(this
          .getRegionIdentifier());
    }
    catch (CancelException e) {
      // ignore, metadata not accessible
    }
    if (null == prConfig) {
      isAlreadyDestroyedOrOldReference = true;
    }
    else {
      // If this reference is a destroyed reference and a new PR is created
      // after destruction of the older one is complete, bail out.
      if (prConfig.getPRId() != this.partitionedRegionId) {
        isAlreadyDestroyedOrOldReference = true;
      }
    }
    return isAlreadyDestroyedOrOldReference;
  }

  @Override
  void dispatchListenerEvent(EnumListenerEvent op, InternalCacheEvent event) {
    if (this.bucketListener != null
        && op.getEventCode() == EnumListenerEvent.AFTER_REGION_DESTROY
            .getEventCode()) {
      this.bucketListener.regionClosed(event);
    }
    // don't dispatch the event if the interest policy forbids it
    if (hasListener()) {
      if (event.getOperation().isEntry()) {
        EntryEventImpl ev = (EntryEventImpl)event;
        if (!ev.getInvokePRCallbacks()) {
          if (this.getSubscriptionAttributes().getInterestPolicy() == 
              InterestPolicy.CACHE_CONTENT) {
            /*
            int bucketId = PartitionedRegionHelper.getHashKey(
                this, ev.getOperation(), ev.getKey(), ev.getCallbackArgument());
            InternalDistributedMember primary = getBucketPrimary(bucketId);
            if (primary.equals(this.cache.getDistributedSystem().getDistributedMember())) {
              logger.error("Found invokePRCallbacks=false in primary owner for event " + ev, new Exception("Stack Trace"));
            }
            else
            */
            if (DistributionManager.VERBOSE || BridgeServerImpl.VERBOSE) {
              logger.info(LocalizedStrings.DEBUG, "not dispatching PR event in this member as there is no interest in it");
            }
            return;
          }
        }
      }
      super.dispatchListenerEvent(op, event);
    }
  }

  
  @Override
  protected void generateLocalFilterRouting(InternalCacheEvent event) {
    if (event.getLocalFilterInfo() == null) {
      super.generateLocalFilterRouting(event);
    }
  }


  /**
   * Invoke the cache writer before a put is performed. Each
   * BucketRegion delegates to the CacheWriter on the PartitionedRegion
   * meaning that CacheWriters on a BucketRegion should only be used for internal
   * purposes.
   *
   * @see BucketRegion#cacheWriteBeforePut(EntryEventImpl, Set, CacheWriter, boolean, Object)
   */
  @Override
  protected void cacheWriteBeforePut(EntryEventImpl event, Set netWriteRecipients,
      CacheWriter localWriter, 
      boolean requireOldValue,
      Object expectedOldValue)
      throws CacheWriterException, TimeoutException {
    
    // return if notifications are inhibited
    if (event.inhibitAllNotifications()) {
      if (cache.getLoggerI18n().fineEnabled())
        cache.getLoggerI18n().fine("Notification inhibited for key " + event.getKey());
      
      return;
    }

    final boolean isNewKey = event.getOperation().isCreate();
    serverPut(event, requireOldValue, expectedOldValue);
    if (localWriter == null
        && (netWriteRecipients == null || netWriteRecipients.isEmpty())) {
      if (logger.fineEnabled()) {
        logger.fine("cacheWriteBeforePut: beforePut empty set returned by "
            + "advisor.adviseNetWrite in netWrite");
      }
      return;
    }

    final long start = getCachePerfStats().startCacheWriterCall();

    try {
      SearchLoadAndWriteProcessor processor = SearchLoadAndWriteProcessor
          .getProcessor();
      processor.initialize(this, "preUpdate", null);
      try {
        if (!isNewKey) {
          if (logger.fineEnabled()) {
            logger.fine("cacheWriteBeforePut: doNetWrite(BEFOREUPDATE)");
          }
          processor.doNetWrite(event, netWriteRecipients, localWriter,
              SearchLoadAndWriteProcessor.BEFOREUPDATE);
        }
        else {
          if (logger.fineEnabled()) {
            logger.fine("cacheWriteBeforePut: doNetWrite(BEFORECREATE)");
          }
          // sometimes the op will erroneously be UPDATE
          processor.doNetWrite(event, netWriteRecipients, localWriter,
              SearchLoadAndWriteProcessor.BEFORECREATE);
        }
      }
      finally {
        processor.release();
      }
    }
    finally {
      getCachePerfStats().endCacheWriterCall(start);
    }
  }

  /**
   * Invoke the CacheWriter before the detroy operation occurs.  Each
   * BucketRegion delegates to the CacheWriter on the PartitionedRegion
   * meaning that CacheWriters on a BucketRegion should only be used for internal
   * purposes.
   * @see BucketRegion#cacheWriteBeforeDestroy(EntryEventImpl, Object)
   */
  @Override
  boolean cacheWriteBeforeDestroy(EntryEventImpl event, Object expectedOldValue)
      throws CacheWriterException, EntryNotFoundException, TimeoutException {
    // return if notifications are inhibited
    if (event.inhibitAllNotifications()) {
      if (cache.getLoggerI18n().fineEnabled())
        cache.getLoggerI18n().fine("Notification inhibited for key " + event.getKey());
      return false;
    }

    // logger.info("PR.cachWriteBeforeDestroy(event="+event);
    if (event.isDistributed()) {
      serverDestroy(event, expectedOldValue);
      CacheWriter localWriter = basicGetWriter();
      Set netWriteRecipients = localWriter == null ? this.distAdvisor
          .adviseNetWrite() : null;

      if (localWriter == null
          && (netWriteRecipients == null || netWriteRecipients.isEmpty())) {
        // getSystem().getLogWriter().finer("beforeDestroy empty set returned by
        // advisor.adviseNetWrite in netWrite");
        return false;
      }

      final long start = getCachePerfStats().startCacheWriterCall();
      try {
        event.setOldValueFromRegion();
        SearchLoadAndWriteProcessor processor = SearchLoadAndWriteProcessor
            .getProcessor();
        processor.initialize(this, event.getKey(), null);
        processor.doNetWrite(event, netWriteRecipients, localWriter,
            SearchLoadAndWriteProcessor.BEFOREDESTROY);
        processor.release();
      }
      finally {
        getCachePerfStats().endCacheWriterCall(start);
      }
      return true;
    }
    return false;
  }

  /**
   * Send a message to all PartitionedRegion participants, telling each member
   * of the PartitionedRegion with a datastore to dump the contents of the
   * buckets to the system.log and validate that the meta-data for buckets
   * agrees with the data store's perspective
   * 
   * @param distribute true will distributed a DumpBucketsMessage to PR nodes
   * @throws ReplyException
   * @see #validateAllBuckets()
   */
  public void dumpAllBuckets(boolean distribute, LogWriterI18n log) throws ReplyException {
    LogWriterI18n logWriter = log;
    if (logWriter == null) {
      logWriter = logger;
    }
    logWriter.info(LocalizedStrings.DEBUG, "[dumpAllBuckets] distribute=" + distribute + " " + this);
    getRegionAdvisor().dumpProfiles(logWriter, "dumpAllBuckets");
    if (distribute) {
      PartitionResponse response = DumpBucketsMessage.send(getRegionAdvisor()
          .adviseAllPRNodes(), this, false /* only validate */, false);
      response.waitForRepliesUninterruptibly();
    }
    if (this.dataStore != null) {
      this.dataStore.dumpEntries(false /* onlyValidate */, logWriter);
    }
  }
  
  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.LocalRegion#dumpBackingMap()
   */
  @Override
  public void dumpBackingMap() {
    dumpAllBuckets(true, this.getLogWriterI18n());
  }

  /**
   * Send a message to all PartitionedRegion participants, telling each member
   * of the PartitionedRegion with a datastore to dump just the bucket names to
   * the system.log
   * 
   * @throws ReplyException
   * @see #validateAllBuckets()
   */
  public void dumpJustBuckets() throws ReplyException {
    PartitionResponse response = DumpBucketsMessage.send(getRegionAdvisor()
        .adviseDataStore(), this, false /* only validate */, true);
    response.waitForRepliesUninterruptibly();
    if (this.dataStore != null) {
      this.dataStore.dumpBuckets();
    }
  }

  /**
   * 
   * Send a message to all PartitionedRegion participants, telling each member
   * of the PartitionedRegion with a datastore to validate that the meta-data
   * for buckets agrees with the data store's perspective
   * 
   * @throws ReplyException
   * @see #dumpAllBuckets(boolean, LogWriterI18n)
   */
  public void validateAllBuckets() throws ReplyException {
    PartitionResponse response = DumpBucketsMessage.send(getRegionAdvisor()
        .adviseAllPRNodes(), this, true /* only validate */, false);
    response.waitForRepliesUninterruptibly();
    if (this.dataStore != null) {
      this.dataStore.dumpEntries(true /* onlyValidate */, null);
    }
  }

  /**
   * Sends a message to all the <code>PartitionedRegion</code> participants,
   * telling each member of the PartitionedRegion to dump the nodelist in
   * bucket2node metadata for specified bucketId.
   * 
   * @param bucketId
   */
  public void sendDumpB2NRegionForBucket(int bucketId) {
    getRegionAdvisor().dumpProfiles(logger, "dumpB2NForBucket");
    try {
      PartitionResponse response = DumpB2NRegion.send(this.getRegionAdvisor()
          .adviseAllPRNodes(), this, bucketId, false);
      response.waitForRepliesUninterruptibly();
      this.dumpB2NForBucket(bucketId);
    }
    catch (ReplyException re) {
      if (logger.fineEnabled()) {
        logger.fine("sendDumpB2NRegionForBucket got ReplyException", re);
      }
    }
    catch (CancelException e) {
      if (logger.fineEnabled()) {
        logger.fine("sendDumpB2NRegionForBucket got CacheClosedException", e);
      }
    }
    catch (RegionDestroyedException e) {
      if (logger.fineEnabled()) {
        logger
            .fine("sendDumpB2RegionForBucket got RegionDestroyedException", e);
      }
    }
  }

  /**
   * Logs the b2n nodelist for specified bucketId.
   * 
   * @param bId
   */
  public void dumpB2NForBucket(int bId) {
    //StringBuffer b = new StringBuffer();
    // DEBUG
    // b.append("\n").append("Dumping allPR.." +
    // dumpAllPartitionedRegions()).append("\n");

    getRegionAdvisor().getBucket(bId).getBucketAdvisor().dumpProfiles(
        logger,
        "Dumping advisor bucket meta-data for bId=" + bucketStringForLogs(bId)
            + " aka " + getBucketName(bId));

    //logger.fine(b.toString());
  }

  /**
   * Send a message to all PartitionedRegion participants, telling each member
   * of the PartitionedRegion with a datastore to dump the contents of the
   * allPartitionedRegions for this PartitionedRegion.
   * 
   * @throws ReplyException
   */
  public void sendDumpAllPartitionedRegions() throws ReplyException {
    getRegionAdvisor().dumpProfiles(logger, "dumpAllPartitionedRegions");
    PartitionResponse response = DumpAllPRConfigMessage.send(getRegionAdvisor()
        .adviseAllPRNodes(), this);
    response.waitForRepliesUninterruptibly();
    dumpSelfEntryFromAllPartitionedRegions();
  }

  /**
   * This method prints the content of the allPartitionedRegion's contents for
   * this PartitionedRegion.
   */
  public void dumpSelfEntryFromAllPartitionedRegions() {
    StringBuffer b = new StringBuffer(this.prRoot.getFullPath());
    b.append("Dumping allPartitionedRegions for ");
    b.append(this);
    b.append("\n");
    b.append(this.prRoot.get(getRegionIdentifier()));
    logger.info(LocalizedStrings.ONE_ARG, b.toString());
  }
  
  /**
   * A test method to get the list of all the bucket ids for the partitioned
   * region in the data Store.
   * 
   */
  public List getLocalBucketsListTestOnly() {
    List localBucketList = null;
    if (this.dataStore != null) {
      localBucketList = this.dataStore.getLocalBucketsListTestOnly();
    }
    return localBucketList;
  }
  
  /**
   * A test method to get the list of all the primary bucket ids for the partitioned
   * region in the data Store.
   * 
   */
  public List getLocalPrimaryBucketsListTestOnly() {
    List localPrimaryList = null;
    if (this.dataStore != null) {
      localPrimaryList = this.dataStore.getLocalPrimaryBucketsListTestOnly();
    }
    return localPrimaryList;
  }

  // /**
  // * Gets the nodeList for a bucketId from B2N Region removing the nodes that
  // * are not found in both membershipSet and prConfig meta-data region.
  // *
  // * @param bucketId
  // * @return list of nodes for bucketId
  // */
  // ArrayList getNodeList(Integer bucketId)
  // {
  // ArrayList nList = null;
  // VersionedArrayList val = (VersionedArrayList)this.getBucket2Node().get(
  // bucketId);
  // if (val != null) {
  // nList = this.getRedundancyProvider().verifyBucketNodes(val.getListCopy());
  // if (nList.size() == 0) {
  // PartitionedRegionHelper.logForDataLoss(this, bucketId.intValue(),
  // "getNodeList");
  // }
  // }
  // return nList;
  // }

  /** doesn't throw RegionDestroyedException, used by CacheDistributionAdvisor */
  public DistributionAdvisee getParentAdvisee() {
    return (DistributionAdvisee) basicGetParentRegion();
  }

  /**
   * A Simple class used to track retry time for Region operations Does not
   * provide any synchronization or concurrent safety
   * 
   * @author Mitch Thomas
   */
  public final static class RetryTimeKeeper {
    private int totalTimeInRetry;

    private final int maxTimeInRetry;

    public RetryTimeKeeper(int maxTime) {
      this.maxTimeInRetry = maxTime;
    }

    /**
     * wait for {@link PartitionedRegionHelper#DEFAULT_WAIT_PER_RETRY_ITERATION},
     * updating the total wait time. Use this method when the same node has been
     * selected for consecutive attempts with an operation.
     */
    public void waitToRetryNode() {
      this.waitForBucketsRecovery();
    }

    /**
     * Wait for {@link PartitionedRegionHelper#DEFAULT_WAIT_PER_RETRY_ITERATION}
     * time and update the total wait time.
     */
    public void waitForBucketsRecovery() {
      /*
       * Unfortunately, due to interrupts plus the vagaries of thread
       * scheduling, we can't assume that our sleep is for exactly the amount of
       * time that we specify. Thus, we need to measure the before/after times
       * and increment the counter accordingly.
       */
      long start = System.currentTimeMillis();
      boolean interrupted = Thread.interrupted();
      try {
        Thread.sleep(PartitionedRegionHelper.DEFAULT_WAIT_PER_RETRY_ITERATION);
      }
      catch (InterruptedException intEx) {
        interrupted = true;
      }
      finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      long delta = System.currentTimeMillis() - start;
      if (delta < 1) {
        // I don't think this can happen, but I want to guarantee that
        // this thing will eventually time out.
        delta = 1;
      }
      this.totalTimeInRetry += delta;
    }

    public boolean overMaximum() {
      return this.totalTimeInRetry > this.maxTimeInRetry;
    }

    public int getRetryTime() {
      return this.totalTimeInRetry;
    }
  }

  public final String getBucketName(int bucketId) {
    return PartitionedRegionHelper.getBucketName(getFullPath(), bucketId);
  }

  public static final String BUCKET_NAME_SEPARATOR = "_";

  /**
   * Test to determine if the data store is managing the bucket appropriate for
   * the given key
   * 
   * @param key
   *                the cache key
   * @return true if the bucket that should host this key is in the data store
   */
  public final boolean isManagingBucket(Object key) {
    if (this.dataStore != null) {
      int bucketId = PartitionedRegionHelper.getHashKey(this, null, key, null, null);
      return this.dataStore.isManagingBucket(bucketId);
    }
    return false;
  }
  
  public boolean isDataStore() {
    return localMaxMemory != 0;
  }

  @Override
  protected void enableConcurrencyChecks() {
    if (!allowConcurrencyChecksOverride()) {
      return;
    }
    this.concurrencyChecksEnabled = true;
    assert !isDataStore();
  }

  /**
   * finds all the keys matching the given regular expression (or all keys if
   * regex is ".*")
   * 
   * @param regex
   *                the regular expression
   * @param allowTombstones whether to include destroyed entries
   * @param collector
   *                object that will receive the keys as they arrive
   * @throws IOException
   */
  public void getKeysWithRegEx(String regex, boolean allowTombstones, SetCollector collector)
      throws IOException {
    _getKeysWithInterest(InterestType.REGULAR_EXPRESSION, regex, allowTombstones, collector);
  }

  /**
   * finds all the keys in the given list that are present in the region
   * 
   * @param keyList the key list
   * @param         allowTombstones whether to return destroyed entries
   * @param collector  object that will receive the keys as they arrive
   * @throws IOException
   */
  public void getKeysWithList(List keyList, boolean allowTombstones, SetCollector collector)
      throws IOException {
    _getKeysWithInterest(InterestType.KEY, keyList, allowTombstones, collector);
  }

  /**
   * finds all the keys matching the given interest type and passes them to the
   * given collector
   * 
   * @param interestType
   * @param interestArg
   * @param allowTombstones whether to return destroyed entries
   * @param collector
   * @throws IOException
   */
  private void _getKeysWithInterest(int interestType, Object interestArg,
      boolean allowTombstones, SetCollector collector) throws IOException {
    // this could be parallelized by building up a list of buckets for each
    // vm and sending out the requests for keys in parallel. That might dump
    // more onto this vm in one swoop than it could handle, though, so we're
    // keeping it simple for now
    int totalBuckets = getTotalNumberOfBuckets();
    int retryAttempts = calcRetry();
    for (int bucket = 0; bucket < totalBuckets; bucket++) {
      Set bucketSet = null;
      Integer lbucket = Integer.valueOf(bucket);
      final RetryTimeKeeper retryTime = new RetryTimeKeeper(Integer.MAX_VALUE);
      InternalDistributedMember bucketNode = getOrCreateNodeForBucketRead(lbucket
          .intValue());
      for (int count = 0; count <= retryAttempts; count++) {
        if (logger.fineEnabled()) {
          logger.fine("_getKeysWithInterest bucketId=" + bucket + " attempt="
              + (count + 1));
        }
        try {
          if (bucketNode != null) {
            if (bucketNode.equals(getMyId())) {
              bucketSet = this.dataStore.handleRemoteGetKeys(lbucket,
                  interestType, interestArg, allowTombstones);
            }
            else {
              final TXStateInterface tx = getTXState();
              FetchKeysResponse r = FetchKeysMessage.sendInterestQuery(
                  bucketNode, this, tx, lbucket, interestType,
                  interestArg, allowTombstones);
              bucketSet = r.waitForKeys();
            }
          }
          break;
        }
        catch (PRLocallyDestroyedException pde) {
          if (logger.fineEnabled()) {
            logger
                .fine("_getKeysWithInterest: Encountered PRLocallyDestroyedException");
          }
          checkReadiness();
        }
        catch (ForceReattemptException prce) {
          // no checkKey possible
          if (logger.fineEnabled()) {
            logger.fine("_getKeysWithInterest: retry attempt:" + count, prce);
          }
          checkReadiness();

          InternalDistributedMember lastTarget = bucketNode;
          bucketNode = getOrCreateNodeForBucketRead(lbucket.intValue());
          if (lastTarget != null && lastTarget.equals(bucketNode)) {
            if (retryTime.overMaximum()) {
              break;
            }
            retryTime.waitToRetryNode();
          }
        }
      } // for(count)
      if (bucketSet != null) {
        collector.receiveSet(bucketSet);
      }
    } // for(bucket)
  }

  /**
   * EventID will not be generated for PartitionedRegion.
   * 
   */
  // TODO:ASIF: Check if this is correct assumption
  @Override
  public boolean generateEventID() {
    return true;
  }

  @Override
  protected boolean shouldNotifyBridgeClients() {
    return true;
  }

  /**
   * SetCollector is implemented by classes that want to receive chunked results
   * from queries like getKeysWithRegEx. The implementor creates a method,
   * receiveSet, that consumes the chunks.
   * 
   * @author bruce
   * @since 5.1
   */
  public static interface SetCollector {
    public void receiveSet(Set theSet) throws IOException;
  }
  
  /**
   * Returns the index flag
   * 
   * @return true if the partitioned region is indexed else false
   */
  public boolean isIndexed() {
    return this.hasPartitionedIndex;
  }

  /**
   * Returns the map containing all the indexes on this partitioned region.
   * 
   * @return Map of all the indexes created.
   */
  public Map getIndex() {
    Hashtable availableIndexes = new Hashtable();
    Iterator iter = this.indexes.values().iterator();
    while (iter.hasNext()){
      Object ind = iter.next();
      // Check if the returned value is instance of Index (this means
      // the index is not in create phase, its created successfully).
      if (ind instanceof Index){
        availableIndexes.put(((Index)ind).getName(), ind);
      }
    }
    return availableIndexes;
  }

  /**
   * Returns the a PartitionedIndex on this partitioned region.
   * 
   * @return Index
   */
  public PartitionedIndex getIndex(String indexName) {
    Iterator iter = this.indexes.values().iterator();
    while (iter.hasNext()){
      Object ind = iter.next();
      // Check if the returned value is instance of Index (this means
      // the index is not in create phase, its created successfully).
      if (ind instanceof PartitionedIndex && ((Index) ind).getName().equals(indexName)){
        return (PartitionedIndex)ind;
      }
    }
    return null;
  }

  /**
   * Gets a collecion of all the indexes created on this pr.
   * 
   * @return collection of all the indexes
   */
  public Collection getIndexes() {
    if (this.indexes.isEmpty()) {
      return Collections.EMPTY_LIST;
    }

    ArrayList idxs = new ArrayList();
    Iterator it = this.indexes.values().iterator();
    while (it.hasNext()) {
      Object ind = it.next();
      // Check if the returned value is instance of Index (this means
      // the index is not in create phase, its created successfully).
      if (ind instanceof Index){
        idxs.add(ind);
      }
    }
    return idxs;
  }
  /**
   * Creates the actual index on this partitioned regions.
   * 
   * @param remotelyOriginated
   *                true if the index is created because of a remote index
   *                creation call
   * @param indexType
   *                the type of index created.
   * @param indexName
   *                the name for the index to be created
   * @param indexedExpression
   *                expression for index creation.
   * @param fromClause
   *                the from clause for index creation
   * @param imports
   *                class to be imported for fromClause.
   * 
   * @return Index an index created on this region.
   * @throws ForceReattemptException
   *                 indicating the operation failed to create a remote index
   * @throws IndexCreationException
   *                 if the index is not created properly
   * @throws IndexNameConflictException
   *                 if an index exists with this name on this region
   * @throws IndexExistsException
   *                 if and index already exists with the same properties as the
   *                 one created
   */
  public Index createIndex(boolean remotelyOriginated, IndexType indexType,
      String indexName, String indexedExpression, String fromClause, 
      String imports) throws ForceReattemptException, IndexCreationException,
      IndexNameConflictException, IndexExistsException {
    // Check if its remote request and this vm is an accessor.
    if (remotelyOriginated && dataStore == null) {
      // This check makes sure that for some region this vm cannot create
      // data store where as it should have.
      if (getLocalMaxMemory() != 0) {
        throw new IndexCreationException(LocalizedStrings.
            PartitionedRegion_DATA_STORE_ON_THIS_VM_IS_NULL_AND_THE_LOCAL_MAX_MEMORY_IS_NOT_ZERO_THE_DATA_POLICY_IS_0_AND_THE_LOCALMAXMEMEORY_IS_1.
            toLocalizedString(new Object[] {getDataPolicy(), Long.valueOf(getLocalMaxMemory())}));
      }
      // Not have to do anything since the region is just an Accessor and
      // does not store any data.
      logger.info(LocalizedStrings.PartitionedRegion_THIS_IS_AN_ACCESSOR_VM_AND_DOESNT_CONTAIN_DATA);
      return null;
    }

    // Create indexManager.
    if (this.indexManager == null) {
      this.indexManager = IndexUtils.getIndexManager(this, true); 
    }
    
    if (logger.fineEnabled()){
      logger.fine("Started creating index with Index Name :" + indexName + " On PartitionedRegion " +
          this.getFullPath() +", Indexfrom caluse=" +fromClause + ", Remote Request: " + remotelyOriginated);
    }
    IndexTask indexTask = new IndexTask(remotelyOriginated, indexType, indexName,
        indexedExpression, fromClause,  imports);

    FutureTask<Index> indexFutureTask = new FutureTask<Index>(indexTask);

    // This will return either the Index FutureTask or Index itself, based
    // on whether the index creation is in process or completed.
    Object ind = this.indexes.putIfAbsent(indexTask, indexFutureTask);

    // Check if its instance of Index, in that the case throw index exists exception.
    if (ind instanceof Index){
      if (remotelyOriginated) {
        return (Index)ind;
      }

      throw new IndexNameConflictException(LocalizedStrings.IndexManager_INDEX_NAMED_0_ALREADY_EXISTS
          .toLocalizedString(indexName));
    }


    FutureTask<Index> oldIndexFutureTask = (FutureTask<Index>)ind;
    Index index = null;
    boolean interrupted = false;
        
    try {
      if (oldIndexFutureTask == null) {
        // Index doesn't exist, create index.
        indexFutureTask.run();
        index = indexFutureTask.get();
        if (index != null){
          this.indexes.put(indexTask, index);
          PartitionedIndex prIndex = (PartitionedIndex)index;      
          indexManager.addIndex(indexName, index);
          
          // Locally originated create index request.
          // Send create request to other PR nodes.
          if (!remotelyOriginated){
            logger.info(LocalizedStrings.
                PartitionedRegion_CREATED_INDEX_LOCALLY_SENDING_INDEX_CREATION_MESSAGE_TO_ALL_MEMBERS_AND_WILL_BE_WAITING_FOR_RESPONSE_0,
                prIndex);
            IndexCreationMsg.IndexCreationResponse response =
              (IndexCreationMsg.IndexCreationResponse)IndexCreationMsg.send(null, PartitionedRegion.this, prIndex);            

            if (response != null) {
              IndexCreationMsg.IndexCreationResult result = response.waitForResult();
              prIndex.setRemoteBucketesIndexed(result.getNumBucketsIndexed());
            }
          }
        }
      } else {
        // Some other thread is trying to create the same index.
        // Wait for index to be initialized from other thread.
        index = oldIndexFutureTask.get();
        // The Index is successfully created, throw appropriate error message from this thread.
        if (remotelyOriginated) {
          return index;
        }
        
        throw new IndexNameConflictException(LocalizedStrings.IndexManager_INDEX_NAMED_0_ALREADY_EXISTS.toLocalizedString(indexName));
      }
    } catch (InterruptedException ie) {
      interrupted = true;
    } catch (ExecutionException ee) {
      if(!remotelyOriginated) {
        Throwable c = ee.getCause();
        if (c instanceof IndexNameConflictException) {
          throw (IndexNameConflictException)c;
        } else if (c instanceof IndexExistsException){
          throw (IndexExistsException)c;
        }
        throw new IndexInvalidException(ee);
      }
    } finally {
      //If the index is not successfully created, remove IndexTask from the map.
      if (index == null){
        ind = this.indexes.get(indexTask);
        if (index != null && !(index instanceof Index)){
          this.indexes.remove(indexTask);
        }
      }

      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    if (logger.fineEnabled()){
      logger.fine("Completed creating index with Index Name :" + indexName + " On PartitionedRegion " +
          this.getFullPath() + ", Remote Request: " + remotelyOriginated);
    }
    return index;
  }

  /**
   * Explicitly sends an index creation message to a newly added node to the
   * system on prs.
   * 
   * @param idM
   *                id on the newly added node.
   */
  public void sendIndexCreationMsg(InternalDistributedMember idM) {

    if (!this.isIndexed())
      return;

    RegionAdvisor advisor = (RegionAdvisor)(this.getCacheDistributionAdvisor());
    final Set recipients  = advisor.adviseDataStore();
    if(!recipients.contains(idM)){
      logger.info(
        LocalizedStrings.PartitionedRegion_NEWLY_ADDED_MEMBER_TO_THE_PR_IS_AN_ACCESSOR_AND_WILL_NOT_RECEIVE_INDEX_INFORMATION_0,  
        idM);
      return;
    }
    // this should add the member to a synchornized set and then sent this member
    // and index creation msg latter after its completed creating the partitioned region.
    IndexCreationMsg.IndexCreationResponse response = null;
    IndexCreationMsg.IndexCreationResult result = null;

    if (this.indexes.isEmpty()) {
      return;
    }
    
    Iterator it = this.indexes.values().iterator();
    while (it.hasNext()) {
      Object ind = it.next();
      // Check if the returned value is instance of Index (this means
      // the index is not in create phase, its created successfully).
      if (!(ind instanceof Index)){
        continue;
      }

      PartitionedIndex prIndex = (PartitionedIndex)ind;
      response = (IndexCreationMsg.IndexCreationResponse)IndexCreationMsg.send(
          idM, this, prIndex);

      if (logger.fineEnabled()) {
        logger.fine("Sending explictly index creation message to : " + idM);
      }

      if (response != null) {
        try {
          result = response.waitForResult();
          if (logger.fineEnabled()) {
            logger.fine("Explicit remote buckets indexed : "
                + result.getNumBucketsIndexed());
          }
          prIndex.setRemoteBucketesIndexed(result.getNumBucketsIndexed());
        }
        catch (ForceReattemptException ignor) {
          logger.info(LocalizedStrings.PartitionedRegion_FORCEREATTEMPT_EXCEPTION___0, ignor);
        }
      }
      
    }
  }

  /**
   * Removes all the indexes on this partitioned regions instance and send
   * remove index message
   * 
   * @throws ForceReattemptException
   * @throws CacheException
   */
  public int removeIndexes(boolean remotelyOriginated) throws CacheException,
      ForceReattemptException {
    LogWriterI18n log = getLogWriterI18n();
    int numBuckets = 0;
    
    if (!this.hasPartitionedIndex || this.indexes.isEmpty()) {
      if (log.fineEnabled()) {
        log.fine("This partitioned regions does not have any index : " + this);
      }
      return numBuckets;
    }

    this.hasPartitionedIndex = false;

    if (log.fineEnabled()){
      log.info(LocalizedStrings.PartitionedRegion_REMOVING_ALL_THE_INDEXES_ON_THIS_PARITITION_REGION__0, this);
    }

    try {
      Iterator bucketIterator = dataStore.getAllLocalBuckets().iterator();
      while (bucketIterator.hasNext()) {
        LocalRegion bucket = null;
        Map.Entry bucketEntry = (Map.Entry)bucketIterator.next();
        bucket = (LocalRegion)bucketEntry.getValue();
        if (bucket != null) {
          bucket.waitForData();
          IndexManager indexMang = IndexUtils.getIndexManager(bucket, false);
          if (indexMang != null) {
            indexMang.removeIndexes();
            numBuckets++;
            if (log.fineEnabled()){
              log.fine("Removed all the indexes on bucket  " + bucket);
            }
          }
        }
      } // ends while
      if (log.fineEnabled()){
        log.fine("Removed this many indexes on the buckets : " + numBuckets);
      }
      RemoveIndexesMessage.RemoveIndexesResponse response = null;

      if (!remotelyOriginated) {
        log.info(LocalizedStrings.PartitionedRegion_SENDING_REMOVEINDEX_MESSAGE_TO_ALL_THE_PARTICIPATING_PRS);

        response = (RemoveIndexesMessage.RemoveIndexesResponse)
            RemoveIndexesMessage.send(this, null, true);
        
        if (null != response) {
          response.waitForResults();
          log.info(LocalizedStrings.PartitionedRegion_DONE_WATING_FOR_REMOVE_INDEX);
          if (log.fineEnabled()){
            log.fine("Total number of buckets which removed indexes , locally : "
                + numBuckets + " and remotely removed : "
                + response.getRemoteRemovedIndexes()
                + " and the total number of remote buckets : "
                + response.getTotalRemoteBuckets());
          }
        }
      }
      this.indexManager.removeIndexes();
      return numBuckets;

    } // outer try block
    finally {
      //this.indexes = null;
      this.indexes.clear();
    }
  }

  /**
   * Removes a particular index on this partitioned regions instance.
   * 
   * @param ind Index to be removed.
   * 
   */
  public int removeIndex(Index ind, boolean remotelyOrignated)
      throws CacheException, ForceReattemptException {
    
    int numBuckets = 0;
    IndexTask indexTask = null;
    Object prIndex = null;

    if (ind != null) {
      indexTask = new IndexTask(ind.getName());
      prIndex = this.indexes.get(indexTask);
    }

    // Check if the returned value is instance of Index (this means the index is
    // not in create phase, its created successfully).
    if (prIndex == null || !(prIndex instanceof Index)) {
      getLogWriterI18n().info(LocalizedStrings.PartitionedRegion_THIS_INDEX__0_IS_NOT_ON_THIS_PARTITONED_REGION___1,
          new Object[] {ind, this});
      return numBuckets;
    }

    LogWriterI18n log = getLogWriterI18n();
    if (log.fineEnabled()) {
      log.fine("Remove index called, IndexName: " + ind.getName() +
          " Index : " + ind + " Will be removing all the bucket indexes.");
    }

    Index i = this.indexManager.getIndex(ind.getName());
    if (i != null) {
      this.indexManager.removeIndex(i);
    }

    // After removing from region wait for removing from index manager and
    // marking the index invalid.
    if (prIndex != null) {
      PartitionedIndex index = (PartitionedIndex) prIndex;
      index.acquireLockForRemoveIndex();
    }

    this.indexes.remove(indexTask);

    // For releasing the write lock after removal.
    try {  
      synchronized (prIndex) {
        List allBucketIndex = ((PartitionedIndex)prIndex).getBucketIndexes();
        Iterator it = allBucketIndex.iterator();
          
        if (log.fineEnabled()) {
          log.fine("Will be removing indexes on : " + allBucketIndex.size()
              + " buckets ");
        }
          
        while (it.hasNext()) {
          Index in = (Index)it.next();
          LocalRegion region = ((LocalRegion)in.getRegion());
          region.waitForData();
          IndexManager indMng = region.getIndexManager();
          indMng.removeIndex(in);
          
          if (log.fineEnabled()){
            log.fine("Removed index : " + in + " on bucket " + region);
          }
          numBuckets++;
          ((PartitionedIndex)prIndex).removeFromBucketIndexes(in);
        } // while
      }
    } finally {
      ((PartitionedIndex)prIndex).releaseLockForRemoveIndex();
    }

    if (!remotelyOrignated) {
      // send remove index message.
      RemoveIndexesMessage.RemoveIndexesResponse response = null;
      log.info(LocalizedStrings.PartitionedRegion_SENDING_REMOVEINDEX_MESSAGE_TO_ALL_THE_PARTICIPATING_PRS);
      response = (RemoveIndexesMessage.RemoveIndexesResponse)RemoveIndexesMessage.send(this, ind, false);

      if (response != null) {
        response.waitForResults();
        log.info(LocalizedStrings.PartitionedRegion_DONE_WATING_FOR_REMOVE_INDEX);
        if (log.fineEnabled()) {
          log.fine("Total number of buckets which removed indexs , locally : "
              + numBuckets + " and remotely removed : "
              + response.getRemoteRemovedIndexes()
              + " and the total number of remote buckets : "
              + response.getTotalRemoteBuckets());
        }
      }
    }
    return numBuckets;
  }

  /**
   * Gets and removes index by name.
   * 
   * @param indexName
   *                name of the index to be removed.
   */
  public int removeIndex(String indexName) throws CacheException,
  ForceReattemptException {
    int numbuckets = 0;
    // remotely orignated removeindex
    //IndexTask indexTask = new IndexTask(indexName);
    Object ind = this.indexes.get(indexName);

    // Check if the returned value is instance of Index (this means the index is
    // not in create phase, its created successfully).
    if (ind instanceof Index) {
      numbuckets = removeIndex((Index)this.indexes.get(indexName), true);
    }
    return numbuckets;
  }

  /*
   * @OVERRIDES
   */
  @Override
  public Object getValueInVM(Object key) throws EntryNotFoundException {
    if (this.dataStore == null) {
      throw new EntryNotFoundException(key.toString());
    }
    final int bucketId = PartitionedRegionHelper.getHashKey(this, null, key, null, null);
    return this.dataStore.getLocalValueInVM(key, bucketId);
  }

  @Override
  public Object getValueInVM(EntryEventImpl event)
      throws EntryNotFoundException {
    final Object key = event.getKey();
    if (this.dataStore == null) {
      throw new EntryNotFoundException(key.toString());
    }
    final int bucketId = PartitionedRegionHelper.getHashKey(event, this, key,
        event.getCallbackArgument());
    return this.dataStore.getLocalValueInVM(key, bucketId);
  }

  /**
   * This method is intended for testing purposes only.
   * DO NOT use in product code else it will break GemFireXD that has cases
   * where routing object is not part of only the key.
   */
  @Override
  public Object getValueOnDisk(Object key) throws EntryNotFoundException {
    final int bucketId = PartitionedRegionHelper.getHashKey(this, null, key, null, null);
    if (this.dataStore == null) {
      throw new EntryNotFoundException(key.toString());
    }
    return this.dataStore.getLocalValueOnDisk(key, bucketId);
  }
  
  /**
   * This method is intended for testing purposes only.
   * DO NOT use in product code else it will break GemFireXD that has cases
   * where routing object is not part of only the key.
   */
  @Override
  public Object getValueOnDiskOrBuffer(Object key) throws EntryNotFoundException {
    final int bucketId = PartitionedRegionHelper.getHashKey(this, null, key, null, null);
    if (this.dataStore == null) {
      throw new EntryNotFoundException(key.toString());
    }
    return this.dataStore.getLocalValueOnDiskOrBuffer(key, bucketId);
  }

  /**
   * Test Method: Fetch the given bucket's meta-data from each member hosting
   * buckets
   * 
   * @param bucketId
   *                the identity of the bucket
   * @return list of arrays, each array element containing a
   *         {@link DistributedMember} and a {@link Boolean} the boolean denotes
   *         if the member is hosting the bucket and believes it is the primary
   * @throws ForceReattemptException
   *                 if the caller should reattempt this request
   */
  public List getBucketOwnersForValidation(int bucketId)
      throws ForceReattemptException {
    // bucketid 1 => "vm A", false | "vm B", false | "vm C", true | "vm D",
    // false
    // bucketid 2 => List< Tuple(MemberId mem, Boolean isPrimary) >

    // remotely fetch each VM's bucket meta-data (versus looking at the bucket
    // advisor's data
    DumpB2NResponse response = DumpB2NRegion.send(getRegionAdvisor()
        .adviseDataStore(), this, bucketId, true);
    List remoteInfos = new LinkedList(response.waitForPrimaryInfos());

    // Include current VM in the status...
    // remoteInfos.add(new Object[] {
    // getDistributionManager().getId(),
    // new Boolean(
    // getRegionAdvisor().getBucket(bucketId).isHosting())});
    if (getRegionAdvisor().getBucket(bucketId).isHosting()) {
      if (getRegionAdvisor().isPrimaryForBucket(bucketId)) {
        remoteInfos.add(new Object[] { getDistributionManager().getId(),
            Boolean.TRUE, "" });
      }
      else {
        remoteInfos.add(new Object[] { getDistributionManager().getId(),
            Boolean.FALSE, "" });
      }
    }
    return remoteInfos;
  }

  /**
   * Return the primary for the local bucket. Returns null if no primary
   * can be found within {@link com.gemstone.gemfire.distributed.internal.DistributionConfig#getMemberTimeout}.
   * @param bucketId
   * @return the primary bucket member
   */
  public InternalDistributedMember getBucketPrimary(int bucketId) {
    return getRegionAdvisor().getPrimaryMemberForBucket(bucketId);
  }

  /**
   * Test Method: Used to debug the system when an operation fails by throwing
   * an exception
   * 
   * @param key
   * @return empty string??
   */
  public String getAbortedOperationReason(Object key) {
    // Find the primary bucket info (time of creation, primary location, primary
    // meta-data on all nodes, bucket size)
    // using the given key.
    return "";
  }

  /**
   * Wait until the bucket meta-data has been built and is ready to receive
   * messages and/or updates
   */
  public final void waitOnBucketMetadataInitialization() {
    if (this.initializedBuckets) {
      return;
    }
    waitOnInitialization(this.initializationLatchAfterBucketIntialization);
    // at this point initialization must be complete
    this.initializedBuckets = true;
  }

  private void releaseAfterBucketMetadataSetupLatch() {
    this.initializationLatchAfterBucketIntialization.countDown();
    this.initializedBuckets = true;
  }
  
  /**
   * Test to see if this region has finished bucket metadata setup.
   */
  public boolean isAfterBucketMetadataSetup() {
    return this.initializationLatchAfterBucketIntialization.getCount() == 0;
  }

  @Override
  protected void releaseLatches() {
    super.releaseLatches();
    releaseAfterBucketMetadataSetupLatch();
  }

  /**
   * get the total retry interval, in milliseconds, for operations concerning
   * this partitioned region
   * 
   * @return millisecond retry timeout interval
   */
  public int getRetryTimeout() {
    return this.retryTimeout;
  }
  
  public long getBirthTime() { 
    return birthTime;
  }

  public PartitionResolver getPartitionResolver() {
    // [GemFireXD] use PartitionAttributes to get the the resolver
    // since it may change after ALTER TABLE
    return this.partitionAttributes.getPartitionResolver();
  }

  public String getColocatedWith() {
    // [GemFireXD] use PartitionAttributes to get colocated region
    // since it may change after ALTER TABLE
    return this.partitionAttributes.getColocatedWith();
  }

  // For GemFireXD ALTER TABLE. Need to set the colocated region using
  // PartitionAttributesImpl and also reset the parentAdvisor for
  // BucketAdvisors.
  /**
   * Set the colocated with region path and adjust the BucketAdvisor's. This
   * should *only* be invoked when region is just newly created and has no data
   * or existing buckets else will have undefined behaviour.
   * 
   * @since 6.5
   */
  public void setColocatedWith(String colocatedRegionFullPath) {
    ((PartitionAttributesImpl)this.partitionAttributes)
        .setColocatedWith(colocatedRegionFullPath);
    this.colocatedWithRegion = null;
    setColocatedWithRegion(ColocationHelper.getColocatedRegion(this));
    if (isInitialized()) {
      this.getRegionAdvisor().resetBucketAdvisorParents();
    }
  }

  /**
   * Used to get membership events from our advisor to implement
   * RegionMembershipListener invocations. This is copied almost in whole from
   * DistributedRegion
   * 
   * @since 5.7
   */
  protected class AdvisorListener implements MembershipListener
  {
    protected synchronized void initRMLWrappers() {
      if (PartitionedRegion.this.isInitialized() && hasListener()) {
        initPostCreateRegionMembershipListeners(getRegionAdvisor().adviseAllPRNodes());
      }
    }

    public synchronized void memberJoined(InternalDistributedMember id) {
      // bug #44684 - this notification has been moved to a point AFTER the
      // other member has finished initializing its region
//      if (PartitionedRegion.this.isInitialized() && hasListener()) {
//        RegionEventImpl event = new RegionEventImpl(PartitionedRegion.this,
//            Operation.REGION_CREATE, null, true, id);
//        dispatchListenerEvent(EnumListenerEvent.AFTER_REMOTE_REGION_CREATE,
//            event);
//      }
      // required-roles functionality is not implemented for partitioned regions,
      // or it would be done here
    }

    public void quorumLost(Set<InternalDistributedMember> failures, List<InternalDistributedMember> remaining) {
    }

    public void memberSuspect(InternalDistributedMember id,
        InternalDistributedMember whoSuspected) {
    }
    
    public synchronized void memberDeparted(InternalDistributedMember id,
        boolean crashed) {
      if (PartitionedRegion.this.isInitialized() && hasListener()) {
        RegionEventImpl event = new RegionEventImpl(PartitionedRegion.this,
            Operation.REGION_CLOSE, null, true, id);
        if (crashed) {
          dispatchListenerEvent(EnumListenerEvent.AFTER_REMOTE_REGION_CRASH,
              event);
        }
        else {
          // @todo darrel: it would be nice to know if what actual op was done
          //               could be close, local destroy, or destroy (or load snap?)
          if (DestroyRegionOperation.isRegionDepartureNotificationOk()) {
            dispatchListenerEvent(EnumListenerEvent.AFTER_REMOTE_REGION_DEPARTURE, event);
          }
        }
      }
      // required-roles functionality is not implemented for partitioned regions,
      // or it would be done here
    }
  }
  
  /*
   * This is an internal API for GemFireXD only <br>
   * This is usefull to execute a function on set of nodes irrelevant of the
   * routinKeys <br>
   * notes : This API uses DefaultResultCollector. If you want your Custome
   * Result collector, let me know
   * 
   * @param functionName
   * @param args
   * @param nodes
   *                Set of DistributedMembers on which this function will be
   *                executed
   * @throws Exception
   *//*
  public ResultCollector executeFunctionOnNodes(String functionName,
      Serializable args, Set nodes) throws Exception {
    Assert.assertTrue(functionName != null, "Error: functionName is null");
    Assert.assertTrue(nodes != null, "Error: nodes set is null");
    Assert.assertTrue(nodes.size() != 0, "Error: empty nodes Set");
    ResultCollector rc = new DefaultResultCollector();
    boolean isSelf = nodes.remove(getMyId());
    PartitionedRegionFunctionResponse response = null;
    //TODO Yogesh: this API is broken after Resultsender implementation
    //response = new PartitionedRegionFunctionResponse(this.getSystem(), nodes,
    //    rc);
    Iterator i = nodes.iterator();
    while (i.hasNext()) {
      InternalDistributedMember recip = (InternalDistributedMember)i.next();
      PartitionedRegionFunctionMessage.send(recip, this, functionName, args,
          null routingKeys , response, null);
    }
    if (isSelf) {
      // execute locally and collect the result
      if (this.dataStore != null) {
        this.dataStore.executeOnDataStore(
            null routingKeys , functionName, args, 0,null,rc,null);
      }
    }
    return response;
  }*/


  /*
   * This is an internal API for GemFireXD only <br>
   * API for invoking a function using primitive ints as the routing objects
   * (i.e. passing the hashcodes of the routing objects directly). <br>
   * notes : This API uses DefaultResultCollector. If you want to pass your
   * Custom Result collector, let me know
   * 
   * @param functionName
   * @param args
   * @param hashcodes
   *          hashcodes of the routing objects
   * @throws Exception
   *//*
  public ResultCollector executeFunctionUsingHashCodes(String functionName,
      Serializable args, int hashcodes[]) throws Exception {
    Assert.assertTrue(functionName != null, "Error: functionName is null");
    Assert.assertTrue(hashcodes != null, "Error: hashcodes array is null");
    Assert.assertTrue(hashcodes.length != 0, "Error: empty hashcodes array");
    Set nodes = new HashSet();
    for (int i = 0; i < hashcodes.length; i++) {
      int bucketId = hashcodes[i] % getTotalNumberOfBuckets();
      InternalDistributedMember n = getNodeForBucketRead(bucketId);
      nodes.add(n);
    }
    return executeFunctionOnNodes(functionName, args, nodes);
  }*/

  /**
   * This is an internal API for GemFireXD only <br>
   * Given a array of routing objects, returns a set of members on which the (owner of each
   * buckets)
   * 
   * @param routingObjects array of routing objects passed 
   * @return Set of  InternalDistributedMembers
   */
  public Set getMembersFromRoutingObjects(Object[] routingObjects) {
    Assert.assertTrue(routingObjects != null, "Error: null routingObjects ");
    Assert.assertTrue(routingObjects.length != 0, "Error: empty routingObjects ");
    Set nodeSet = new HashSet();
    int bucketId;
    for (int i = 0; i < routingObjects.length; i++) {
      bucketId = PartitionedRegionHelper.getHashKey(routingObjects[i],
                                                    getTotalNumberOfBuckets());
      InternalDistributedMember lnode = getOrCreateNodeForBucketRead(bucketId);
      if (lnode != null) {
        nodeSet.add(lnode);
      }
    }
    return nodeSet;
  }

  /**
   * @see LocalRegion#basicGetEntryForLock(LocalRegion, Object)
   */
  @Override
  public final RegionEntry basicGetEntryForLock(final LocalRegion dataRegion,
      final Object key) {
    return basicGetEntryForLock(dataRegion, key, true);
  }

  /**
   * @see LocalRegion#basicGetEntryForLock(LocalRegion, Object, boolean)
   */
  @Override
  public final RegionEntry basicGetEntryForLock(final LocalRegion dataRegion,
      final Object key, final boolean allowReadFromHDFS) {
    try {
      return this.dataStore.getRegionEntryLocally((BucketRegion)dataRegion,
          key, false, false, allowReadFromHDFS);
    } catch (EntryNotFoundException e) {
      // deliberate
      return null;
    } catch (PrimaryBucketException e) {
      RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
          .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
              .toLocalizedString(key, dataRegion.getFullPath()), getFullPath());
      re.initCause(e);
      throw re;
    } catch (ForceReattemptException e) {
      RuntimeException re = new TransactionDataNodeHasDepartedException(
          LocalizedStrings
            .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
              .toLocalizedString(e.getOrigin()));
      re.initCause(e);
      throw re;
    } catch (PRLocallyDestroyedException e) {
      RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
          .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
              .toLocalizedString(key, dataRegion.getFullPath()), getFullPath());
      re.initCause(e);
      throw re;
    }
  }

  @Override
  protected boolean usesDiskStore(RegionAttributes ra) {
    if (ra.getPartitionAttributes().getLocalMaxMemory() == 0) return false; // see bug 42055
    return super.usesDiskStore(ra);
  }

  @Override
  protected DiskStoreImpl findDiskStore(RegionAttributes ra,
                                        InternalRegionArguments internalRegionArgs) {
    DiskStoreImpl store = super.findDiskStore(ra, internalRegionArgs);
    if(store != null && store.getOwnedByRegion()) {
      store.initializeIfNeeded(false);
    }
    return store;
  }

  @Override
  protected DiskRegion createDiskRegion(InternalRegionArguments internalRegionArgs) throws DiskAccessException {
    if (internalRegionArgs.getDiskRegion() != null) {
      return internalRegionArgs.getDiskRegion();
    } else {
      final DiskStoreImpl dsi = getDiskStore();
      if (dsi != null) {
        // for regions that were previously dropped but not seen by this JVM
        // in .if files, recreate should drop the existing persistent files
        final GemFireCacheImpl.StaticSystemCallbacks sysCb =
            GemFireCacheImpl.FactoryStatics.systemCallbacks;
        if (sysCb != null && sysCb.destroyExistingRegionInCreate(dsi, this)) {
          dsi.destroyRegion(getFullPath(), false);
        }
      }
      return null;
    }
  }

  @Override
  public void handleInterestEvent(InterestRegistrationEvent event) {
    if (logger.fineEnabled()) {
      logger.fine("PartitionedRegion " + getFullPath() + " handling " + event);
    }

    // Process event in remote data stores by sending message
    Set allRemoteStores = getRegionAdvisor().adviseDataStore(true);
    if (logger.fineEnabled()) {
      logger.fine("PartitionedRegion " + getFullPath()
          + " sending InterestEvent message to: " + allRemoteStores);
    }
    InterestEventResponse response = null;
    if (!allRemoteStores.isEmpty()) {
      try {
        response = InterestEventMessage.send(allRemoteStores, this, event);
      }
      catch (ForceReattemptException e) {
        logger.warning(LocalizedStrings.ONE_ARG, "PartitionedRegion " + getFullPath() + " caught " + e);
      }
    }

    // Process event in the local data store if necessary
    if (this.dataStore != null) {
      // Handle the interest event in the local data store
      this.dataStore.handleInterestEvent(event);
    }

    // Wait for replies
    if (response != null) {
      try {
        if (logger.fineEnabled()) {
          logger.fine("PartitionedRegion " + getFullPath()
              + " waiting for response from " + allRemoteStores);
        }
        response.waitForResponse();
      }
      catch (ForceReattemptException e) {
        logger.warning(LocalizedStrings.ONE_ARG, 
            "PartitionedRegion " + getFullPath() + " caught " + e);
      }
    }
  }
  
  @Override
  public AttributesMutator getAttributesMutator() {
    checkReadiness();
    return this;
  }

  /**
   * Changes the timeToLive expiration attributes for the partitioned region as
   * a whole
   * 
   * @param timeToLive
   *                the expiration attributes for the region timeToLive
   * @return the previous value of region timeToLive
   * @throws IllegalArgumentException
   *                 if timeToLive is null or if the ExpirationAction is
   *                 LOCAL_INVALIDATE and the region is
   *                 {@link DataPolicy#withReplication replicated}
   * @throws IllegalStateException
   *                 if statistics are disabled for this region.
   */
  @Override
  public ExpirationAttributes setRegionTimeToLive(
      ExpirationAttributes timeToLive) {
    ExpirationAttributes attr = super.setRegionTimeToLive(timeToLive);
    // Set to Bucket regions as well
    if (this.getDataStore() != null) { // not for accessors
      Iterator iter = this.getDataStore().getAllLocalBuckets().iterator();
      while (iter.hasNext()) {
        Map.Entry entry = (Map.Entry)iter.next();
        Region bucketRegion = (BucketRegion)entry.getValue();
        bucketRegion.getAttributesMutator().setRegionTimeToLive(timeToLive);
      }
    }
    return attr;
  }

  /**
   * Changes the idleTimeout expiration attributes for the region as a whole.
   * Resets the {@link CacheStatistics#getLastAccessedTime} for the region.
   * 
   * @param idleTimeout
   *                the ExpirationAttributes for this region idleTimeout
   * @return the previous value of region idleTimeout
   * @throws IllegalArgumentException
   *                 if idleTimeout is null or if the ExpirationAction is
   *                 LOCAL_INVALIDATE and the region is
   *                 {@link DataPolicy#withReplication replicated}
   * @throws IllegalStateException
   *                 if statistics are disabled for this region.
   */
  @Override
  public ExpirationAttributes setRegionIdleTimeout(
      ExpirationAttributes idleTimeout) {
    ExpirationAttributes attr = super.setRegionIdleTimeout(idleTimeout);
    // Set to Bucket regions as well
    if (this.getDataStore() != null) { // not for accessors
      Iterator iter = this.getDataStore().getAllLocalBuckets().iterator();
      while (iter.hasNext()) {
        Map.Entry entry = (Map.Entry)iter.next();
        Region bucketRegion = (BucketRegion)entry.getValue();
        bucketRegion.getAttributesMutator().setRegionIdleTimeout(idleTimeout);
      }
    }
    return attr;
  }

  /**
   * Changes the timeToLive expiration attributes for values in this region.
   * 
   * @param timeToLive
   *                the timeToLive expiration attributes for entries
   * @return the previous value of entry timeToLive
   * @throws IllegalArgumentException
   *                 if timeToLive is null or if the ExpirationAction is
   *                 LOCAL_DESTROY and the region is
   *                 {@link DataPolicy#withReplication replicated} or if the
   *                 ExpirationAction is LOCAL_INVALIDATE and the region is
   *                 {@link DataPolicy#withReplication replicated}
   * @throws IllegalStateException
   *                 if statistics are disabled for this region.
   */
  @Override
  public ExpirationAttributes setEntryTimeToLive(ExpirationAttributes timeToLive) {
    ExpirationAttributes attr = super.setEntryTimeToLive(timeToLive);
    // Set to Bucket regions as well
    if (this.getDataStore() != null) { // not for accessors
      Iterator iter = this.getDataStore().getAllLocalBuckets().iterator();
      while (iter.hasNext()) {
        Map.Entry entry = (Map.Entry)iter.next();
        Region bucketRegion = (BucketRegion)entry.getValue();
        bucketRegion.getAttributesMutator().setEntryTimeToLive(timeToLive);
      }
    }
    return attr;
  }

  /**
   * Changes the custom timeToLive for values in this region
   * 
   * @param custom
   *                the new CustomExpiry
   * @return the old CustomExpiry
   */
  @Override
  public CustomExpiry setCustomEntryTimeToLive(CustomExpiry custom) {
    CustomExpiry expiry = super.setCustomEntryTimeToLive(custom);
    // Set to Bucket regions as well
    if (this.getDataStore() != null) { // not for accessors
      Iterator iter = this.getDataStore().getAllLocalBuckets().iterator();
      while (iter.hasNext()) {
        Map.Entry entry = (Map.Entry)iter.next();
        Region bucketRegion = (BucketRegion)entry.getValue();
        bucketRegion.getAttributesMutator().setCustomEntryTimeToLive(custom);
      }
    }
    return expiry;
  }

  /**
   * Changes the idleTimeout expiration attributes for values in the region.
   * 
   * @param idleTimeout
   *                the idleTimeout expiration attributes for entries
   * @return the previous value of entry idleTimeout
   * @throws IllegalArgumentException
   *                 if idleTimeout is null or if the ExpirationAction is
   *                 LOCAL_DESTROY and the region is
   *                 {@link DataPolicy#withReplication replicated} or if the the
   *                 ExpirationAction is LOCAL_INVALIDATE and the region is
   *                 {@link DataPolicy#withReplication replicated}
   * @see AttributesFactory#setStatisticsEnabled
   * @throws IllegalStateException
   *                 if statistics are disabled for this region.
   */
  @Override
  public ExpirationAttributes setEntryIdleTimeout(
      ExpirationAttributes idleTimeout) {
    ExpirationAttributes attr = super.setEntryIdleTimeout(idleTimeout);
    // Set to Bucket regions as well
    if (this.getDataStore() != null) { // not for accessors
      Iterator iter = this.getDataStore().getAllLocalBuckets().iterator();
      while (iter.hasNext()) {
        Map.Entry entry = (Map.Entry)iter.next();
        Region bucketRegion = (BucketRegion)entry.getValue();
        bucketRegion.getAttributesMutator().setEntryIdleTimeout(idleTimeout);
      }
    }
    return attr;
  }

  /**
   * Changes the CustomExpiry for idleTimeout for values in the region
   * 
   * @param custom
   *                the new CustomExpiry
   * @return the old CustomExpiry
   */
  @Override
  public CustomExpiry setCustomEntryIdleTimeout(CustomExpiry custom) {
    CustomExpiry expiry = super.setCustomEntryIdleTimeout(custom);
    // Set to Bucket regions as well
    if (this.getDataStore() != null) { // not for accessors
      Iterator iter = this.getDataStore().getAllLocalBuckets().iterator();
      while (iter.hasNext()) {
        Map.Entry entry = (Map.Entry)iter.next();
        Region bucketRegion = (BucketRegion)entry.getValue();
        bucketRegion.getAttributesMutator().setCustomEntryIdleTimeout(custom);
      }
    }
    return expiry;
  }

  @Override
  protected void setMemoryThresholdFlag(MemoryEvent event) {
    if (event.getState().isCritical()
        && !event.getPreviousState().isCritical()
        && (event.getType() == ResourceType.HEAP_MEMORY || (event.getType() == ResourceType.OFFHEAP_MEMORY && getEnableOffHeapMemory()))) {
      // update proxy bucket, so that we can reject operations on those buckets.
      getRegionAdvisor().markBucketsOnMember(event.getMember(), true/*sick*/);
    } else if (!event.getState().isCritical()
        && event.getPreviousState().isCritical()
        && (event.getType() == ResourceType.HEAP_MEMORY || (event.getType() == ResourceType.OFFHEAP_MEMORY && getEnableOffHeapMemory()))) {
      getRegionAdvisor().markBucketsOnMember(event.getMember(), false/*not sick*/);
    }
  }

  @Override
  public void initialCriticalMembers(boolean localMemoryIsCritical,
      Set<InternalDistributedMember> critialMembers) {
    for (InternalDistributedMember idm: critialMembers) {
      getRegionAdvisor().markBucketsOnMember(idm, true/*sick*/);
    }
  }

  public DiskRegionStats getDiskRegionStats() {
    return diskRegionStats;
  }

  @Override
  public void removeMemberFromCriticalList(DistributedMember member) {
    if (cache.getLoggerI18n().fineEnabled()) {
      cache.getLoggerI18n().fine("PR: removing member "+member+" from " +
      		"critical member list");
    }
    getRegionAdvisor().markBucketsOnMember(member, false/*sick*/);
  }

  public final PartitionedRegion getColocatedWithRegion() { 
    return this.colocatedWithRegion;
  }

  private final AB bucketSorterStarted = CFactory.createAB(false);
  private final AB bucketSortedOnce = CFactory.createAB(false);

  private final Object monitor = new Object();

  public List<BucketRegion> getSortedBuckets() {
    if (!bucketSorterStarted.get()) {
      bucketSorterStarted.set(true);
      this.bucketSorter.scheduleAtFixedRate(new BucketSorterThread(), 0,
          HeapEvictor.BUCKET_SORTING_INTERVAL, TimeUnit.MILLISECONDS);
      if (cache.getLoggerI18n().fineEnabled()) {
        cache
            .getLoggerI18n()
            .fine(
                "Started BucketSorter to sort the buckets according to numver of entries in each bucket for every "
                    + HeapEvictor.BUCKET_SORTING_INTERVAL + " milliseconds");
      }      
    }
    List<BucketRegion> bucketList = new ArrayList<BucketRegion>();
    if(!bucketSortedOnce.get()){
      while(bucketSortedOnce.get() == false);
    }
    bucketList.addAll(this.sortedBuckets);
    return bucketList;
  }

  class BucketSorterThread implements Runnable {
    public void run() {
      try {
        List<BucketRegion> bucketList = new ArrayList<BucketRegion>();
        Set<BucketRegion> buckets = dataStore.getAllLocalBucketRegions();
        for (BucketRegion br : buckets) {
          if (HeapEvictor.MINIMUM_ENTRIES_PER_BUCKET < br.getSizeForEviction()) {
            bucketList.add(br);
          }
        }
        if (!bucketList.isEmpty()) {
          Collections.sort(bucketList, new Comparator<BucketRegion>() {
            public int compare(BucketRegion buk1, BucketRegion buk2) {
              long buk1NumEntries = buk1.getSizeForEviction();
              long buk2NumEntries = buk2.getSizeForEviction();
              if (buk1NumEntries > buk2NumEntries) {
                return -1;
              }
              else if (buk1NumEntries < buk2NumEntries) {
                return 1;
              }
              return 0;
            }
          });
        }
        sortedBuckets = bucketList;
        if(!bucketSortedOnce.get()){
          bucketSortedOnce.set(true);
        }
      }
      catch (Exception e) {
        if (cache.getLoggerI18n().finerEnabled()) {
          getCache().getLoggerI18n().finer(
              "BucketSorterThread : encountered Exception ", e);
        }
      }
    }
  }

  /**
   * @see LocalRegion#getDataRegionForRead(KeyInfo, Operation)
   */
  @Override
  public final BucketRegion getDataRegionForRead(final KeyInfo keyInfo,
      final Operation op) {
    final Object entryKey = keyInfo.getKey();
    BucketRegion br;
    int bucketId = KeyInfo.UNKNOWN_BUCKET;
    try {
      PartitionedRegionDataStore ds = getDataStore();
      if (ds == null) {
        throw new TransactionException(
            LocalizedStrings.PartitionedRegion_TX_ON_DATASTORE
                .toLocalizedString());
      }
      bucketId = keyInfo.getBucketId();
      if (bucketId == KeyInfo.UNKNOWN_BUCKET) {
        bucketId = PartitionedRegionHelper.getHashKey(this, op, entryKey,
            keyInfo.getValue(), keyInfo.getCallbackArg());
        keyInfo.setBucketId(bucketId);
      }
      br = ds.getInitializedBucketWithKnownPrimaryForId(null, bucketId);
    } catch (RegionDestroyedException rde) {
      RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
          .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
              .toLocalizedString(entryKey, (getFullPath() + ':' + bucketId)),
              getFullPath());
      re.initCause(rde);
      throw re;
    } catch (ForceReattemptException e) {
      RuntimeException re = new TransactionDataNodeHasDepartedException(
          LocalizedStrings
            .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
              .toLocalizedString(e.getOrigin()));
      re.initCause(e);
      throw re;
    }
    return br;
  }

  /**
   * @see LocalRegion#getDataRegionForRead(Object, Object, int, Operation)
   */
  @Override
  public final BucketRegion getDataRegionForRead(final Object key,
      final Object callbackArg, int bucketId, final Operation op) {
    BucketRegion br;
    try {
      PartitionedRegionDataStore ds = getDataStore();
      if (ds == null) {
        throw new TransactionException(
            LocalizedStrings.PartitionedRegion_TX_ON_DATASTORE
                .toLocalizedString());
      }
      if (bucketId == KeyInfo.UNKNOWN_BUCKET) {
        bucketId = PartitionedRegionHelper.getHashKey(this, op, key, null,
            callbackArg);
      }
      br = ds.getInitializedBucketWithKnownPrimaryForId(null, bucketId);
    } catch (RegionDestroyedException rde) {
      RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
          .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
              .toLocalizedString(key, (getFullPath() + ':' + bucketId)),
              getFullPath());
      re.initCause(rde);
      throw re;
    } catch (ForceReattemptException e) {
      RuntimeException re = new TransactionDataNodeHasDepartedException(
          LocalizedStrings
            .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
              .toLocalizedString(e.getOrigin()));
      re.initCause(e);
      throw re;
    }
    return br;
  }

  /**
   * @see LocalRegion#getDataRegionForWrite(KeyInfo, Operation)
   */
  @Override
  public final BucketRegion getDataRegionForWrite(final KeyInfo keyInfo,
      final Operation op) {
    BucketRegion br = null;
    final Object entryKey = keyInfo.getKey();
    //try {
      int count = 0;
      final int retryAttempts = calcRetry();
      int bucketId = keyInfo.getBucketId();
      if (bucketId == KeyInfo.UNKNOWN_BUCKET) {
        bucketId = PartitionedRegionHelper.getHashKey(this, op, entryKey,
            keyInfo.getValue(), keyInfo.getCallbackArg());
        keyInfo.setBucketId(bucketId);
      }
      while (count <= retryAttempts) {
        try {
          PartitionedRegionDataStore ds = getDataStore();
          if (ds == null) {
            throw new TransactionException(
                LocalizedStrings.PartitionedRegion_TX_ON_DATASTORE
                    .toLocalizedString());
          }
          //br = ds.getInitializedBucketWithKnownPrimaryForId(entryKey, bucketId);
          br = ds.getCreatedBucketForId(entryKey, bucketId);
          // wait until the primary is initialized
          br.getBucketAdvisor().getPrimary();
          break;
        } catch (ForceReattemptException e) {
          // create a new bucket
          /*
          Throwable cause = null;
          InternalDistributedMember member;
          */
          try {
            createBucket(bucketId, 0, null);
            // we are okay for txns even if this node is a secondary
            /* member = */ getNodeForInitializedBucketRead(bucketId, true);
          } catch (PartitionedRegionStorageException prse) {
            // try not to throw a PRSE if the cache is closing or this region
            // was destroyed during createBucket() (bug 36574)
            this.checkReadiness();
            if (this.cache.isClosed()) {
              throw new RegionDestroyedException(toString(), getFullPath());
            }
            /*
            cause = prse;
            member = null;
            */
          }
          /*
          if (!getMyId().equals(member)) {
            // The keys are not CoLocated
            RuntimeException re = new TransactionDataNotColocatedException(
                LocalizedStrings.PartitionedRegion_KEY_0_NOT_COLOCATED_WITH_TRANSACTION
                    .toLocalizedString(entryKey));
            if (cause != null) {
              re.initCause(cause);
            }
            else if (e != null) {
              re.initCause(e.getCause());
            }
            throw re;
          }
          */
          count++;
        }
      }
      if (br == null) {
        throw new BucketMovedException(LocalizedStrings
            .PartitionedRegionDataStore_BUCKET_ID_0_NOT_FOUND_ON_VM_1
                .toLocalizedString(new Object[] { bucketStringForLogs(bucketId),
                    getMyId() }), bucketId, getFullPath());
      }
      // br.checkForPrimary();
    /*
    } catch (PrimaryBucketException pbe) {
      RuntimeException re = new TransactionDataNotColocatedException(
          LocalizedStrings.PartitionedRegion_KEY_0_NOT_COLOCATED_WITH_TRANSACTION
              .toLocalizedString(entryKey));
      re.initCause(pbe);
      throw re;
    } catch (RegionDestroyedException rde) {
      RuntimeException re = new TransactionDataNotColocatedException(
          LocalizedStrings.PartitionedRegion_KEY_0_NOT_COLOCATED_WITH_TRANSACTION
              .toLocalizedString(entryKey));
      re.initCause(rde);
      throw re;
    }
    */
    return br;
  }

  @Override
  protected InternalDataView buildDataView() {
    return new PartitionedRegionDataView();
  }

  /**
   * Now only used for tests. TODO: method should be removed.
   */
  public DistributedMember getOwnerForKey(KeyInfo keyInfo) {
    if (keyInfo == null) {
      return getMyId();
    }
    int bucketId = keyInfo.getBucketId();
    if (bucketId == KeyInfo.UNKNOWN_BUCKET) {
      bucketId = PartitionedRegionHelper.getHashKey(this, null,
          keyInfo.getKey(), keyInfo.getValue(), keyInfo.getCallbackArg());
      keyInfo.setBucketId(bucketId);
    }
    return createBucket(bucketId, 0, null);
  }

  @Override
  public final KeyInfo getKeyInfo(Object key) {
    return getKeyInfo(key, null, null);
  }

  @Override
  public final KeyInfo getKeyInfo(Object key, Object value, Object callbackArg) {
    final int bucketId;
    if (key == null) {
      // key is null for putAll
      bucketId = KeyInfo.UNKNOWN_BUCKET;
    }
    else {
      bucketId = PartitionedRegionHelper.getHashKey(this, null, key, value,
          callbackArg);
    }
    return new KeyInfo(key, callbackArg, bucketId);
  }

  @Override
  void updateKeyInfo(final KeyInfo keyInfo, final Object key,
      final Object newValue) {
    final int bucketId;
    if (key == null) {
      // key is null for putAll
      bucketId = KeyInfo.UNKNOWN_BUCKET;
    }
    else {
      if (keyInfo instanceof EntryEventImpl) {
        final EntryEventImpl event = (EntryEventImpl)keyInfo;
        bucketId = PartitionedRegionHelper.getHashKey(event, this, key,
            event.getCallbackArgument());
      }
      else {
        bucketId = PartitionedRegionHelper.getHashKey(this, null, key,
            newValue, keyInfo.callbackArg);
      }
    }
    keyInfo.setBucketId(bucketId);
  }

  public static class SizeEntry implements Serializable {
    private final int size;
    private final boolean isPrimary;
    
    public SizeEntry(int size, boolean isPrimary) {
      this.size = size;
      this.isPrimary = isPrimary;
    }

    public int getSize() {
      return size;
    }

    public boolean isPrimary() {
      return isPrimary;
    }
    
    @Override
    public String toString() {
      return "SizeEntry("+size+", primary="+isPrimary+")";
    }
    
    
  }
  
  /**
   * Index Task used to create the index. This is used along with the
   * FutureTask to take care of, same index creation request from multiple
   * threads. At any time only one thread succeeds and other threads waits
   * for the completion of the index creation. This avoids usage of
   * synchronization which could block any index creation.
   */
  public class IndexTask implements Callable<Index> {

    public String indexName;

    public boolean remotelyOriginated;

    private IndexType indexType;

    private String indexedExpression;

    private String fromClause;

    //public List p_list;

    public String imports;

    IndexTask (boolean remotelyOriginated, IndexType indexType, String indexName
        ,
        String indexedExpression, String fromClaus,  String imports
    ){
      this.indexName = indexName;
      this.remotelyOriginated = remotelyOriginated;
      this.indexType = indexType;
      this.indexName = indexName;
      this.indexedExpression = indexedExpression;
      this.fromClause = fromClaus;
     //this.p_list = p_list;
      this.imports = imports;
    }
    IndexTask (String indexName) {
      this.indexName = indexName;
    }

    @Override
    public boolean equals (Object other){
      if (other == null) {
        return false;
      }
      IndexTask otherIndexTask = (IndexTask) other;
      if (this.indexName.equals(otherIndexTask.indexName)){
        return true;
      }
      return false;
    }

    @Override
    public int hashCode(){
      return this.indexName.hashCode();
    }

    /**
     * This starts creating the index.
     */
    public PartitionedIndex call() throws IndexCreationException, IndexNameConflictException,
    IndexExistsException, ForceReattemptException {
     // List list = p_list;
      PartitionedIndex prIndex = null;

      if (dataStore != null){
        prIndex = createIndexOnPRBuckets();
      } else {
        if (getLocalMaxMemory() != 0 ) {
          throw new IndexCreationException(LocalizedStrings.
              PartitionedRegion_DATA_STORE_ON_THIS_VM_IS_NULL_AND_THE_LOCAL_MAX_MEMORY_IS_NOT_ZERO_0.toLocalizedString(
                  Long.valueOf(getLocalMaxMemory())));
        }
        logger.info(LocalizedStrings.PartitionedRegion_THIS_IS_AN_ACCESSOR_VM_AND_DOESNT_CONTAIN_DATA);
         
        prIndex = new PartitionedIndex(indexType, indexName, PartitionedRegion.this,
            indexedExpression, fromClause, imports);
      }

      hasPartitionedIndex = true;
      return prIndex;
    }

    /**
     * This creates indexes on PR buckets.
     */
    private PartitionedIndex createIndexOnPRBuckets() throws IndexNameConflictException, IndexExistsException, IndexCreationException {
      // List list = p_list;

      Set localBuckets = getDataStore().getAllLocalBuckets();
      Iterator it = localBuckets.iterator();
      QCompiler compiler = new QCompiler(cache.getLoggerI18n());
      if (imports != null) {
        compiler.compileImports(imports);
      }

      //list = compiler.compileFromClause(fromClause);

      PartitionedIndex parIndex = new PartitionedIndex(indexType, indexName, PartitionedRegion.this,
          indexedExpression, fromClause,  imports); // imports can be null
      while (it.hasNext()) {
        Map.Entry entry = (Map.Entry) it.next();
        Region bucket = (Region) entry.getValue();
        
        if (bucket == null) {
          continue;
        }

        ExecutionContext externalContext = new ExecutionContext(null, cache);
        externalContext.setBucketRegion(PartitionedRegion.this,
            (BucketRegion) bucket);
        IndexManager indMng = IndexUtils.getIndexManager(bucket, true);
        try {
          indMng
              .createIndex(indexName, indexType, indexedExpression, fromClause,
                  imports, externalContext, parIndex);
          
          //parIndex.addToBucketIndexes(bucketIndex);
        } catch (IndexNameConflictException ince) {
          if (!remotelyOriginated) {
            throw ince;
          }
        } catch (IndexExistsException iee) {
          if (!remotelyOriginated) {
            throw iee;
          }
        }
      }// End of bucket list
      return parIndex;
    }

  }
  
  public List<FixedPartitionAttributesImpl> getFixedPartitionAttributesImpl() {
    return fixedPAttrs;
  }
  
  public List<FixedPartitionAttributesImpl> getPrimaryFixedPartitionAttributes_TestsOnly() {
    List<FixedPartitionAttributesImpl> primaryFixedPAttrs = new LinkedList<FixedPartitionAttributesImpl>();
    if (this.fixedPAttrs != null) {
      for (FixedPartitionAttributesImpl fpa : this.fixedPAttrs) {
        if (fpa.isPrimary()) {
          primaryFixedPAttrs.add(fpa);
        }
      }
    }
    return primaryFixedPAttrs;
  }

  public List<FixedPartitionAttributesImpl> getSecondaryFixedPartitionAttributes_TestsOnly() {
    List<FixedPartitionAttributesImpl> secondaryFixedPAttrs = new LinkedList<FixedPartitionAttributesImpl>();
    if (this.fixedPAttrs != null) {
      for (FixedPartitionAttributesImpl fpa : this.fixedPAttrs) {
        if (!fpa.isPrimary()) {
          secondaryFixedPAttrs.add(fpa);
        }
      }
    }
    return secondaryFixedPAttrs;
  }
  
  /**
   * For the very first member, FPR's first partition has starting bucket id as
   * 0 and other partitions have starting bucket id as the sum of previous
   * partition's starting bucket id and previous partition's num-buckets.
   * 
   * For other members, all partitions defined previously with assigned starting
   * bucket ids are fetched from the metadata PartitionRegionConfig. Now for
   * each partition defined for this member, if this partition is already
   * available in the list of the previously defined partitions then starting
   * bucket id is directly assigned from the same previously defined partition.
   * 
   * And if the partition on this member is not available in the previously
   * defined partitions then new starting bucket id is calculated as the sum of
   * the largest starting bucket id from previously defined partitions and
   * corresponding num-buckets of the partition.
   * 
   * This data of the partitions (FixedPartitionAttributes with starting bucket
   * id for the Fixed Partitioned Region) is stored in metadata for each member.
   */
  
  private void calculateStartingBucketIDs(PartitionRegionConfig prConfig) {
    if (BEFORE_CALCULATE_STARTING_BUCKET_FLAG) {
      PartitionedRegionObserver pro = PartitionedRegionObserverHolder.getInstance();
      pro.beforeCalculatingStartingBucketId();
    }
    int startingBucketID = 0;
    List<FixedPartitionAttributesImpl> fpaList = getFixedPartitionAttributesImpl();
    
    if (this.getColocatedWith() == null) {
      Set<FixedPartitionAttributesImpl> elderFPAs = prConfig
          .getElderFPAs();
      if (elderFPAs != null && !elderFPAs.isEmpty()) {
        int largestStartBucId = -1;
        for (FixedPartitionAttributesImpl fpa : elderFPAs) {
          if (fpa.getStartingBucketID() > largestStartBucId) {
            largestStartBucId = fpa.getStartingBucketID();
            startingBucketID = largestStartBucId + fpa.getNumBuckets();
          }
        }
      }
      for (FixedPartitionAttributesImpl fpaImpl : fpaList) {
        if (elderFPAs != null && elderFPAs.contains(fpaImpl)) {
          for (FixedPartitionAttributesImpl remotefpa : elderFPAs) {
            if (remotefpa.equals(fpaImpl)) {
              fpaImpl.setStartingBucketID(remotefpa.getStartingBucketID());
            }
          }
        }
        else {
          fpaImpl.setStartingBucketID(startingBucketID);
          startingBucketID += fpaImpl.getNumBuckets();
        }
      }
    }
    prConfig.addFPAs(fpaList);
    for (FixedPartitionAttributesImpl fxPrAttr : fpaList) {
      this.partitionsMap.put(
          fxPrAttr.getPartitionName(),
          new Integer[] { fxPrAttr.getStartingBucketID(),
              fxPrAttr.getNumBuckets() });
    }
  }
  
  /**
   * Returns the local BucketRegion given the key.
   * Returns null if no BucketRegion exists.
   */
  public BucketRegion getBucketRegion(Object key) {
    if (this.dataStore == null)
      return null;
    Integer bucketId = Integer.valueOf(PartitionedRegionHelper.getHashKey(this,
        null, key, null, null));
    return this.dataStore.getLocalBucketById(bucketId);
  }

  /**
   * Returns the local BucketRegion given the key and value. Particularly useful
   * for GemFireXD where the routing object may be part of value and determining
   * from key alone will require an expensive global index lookup.
   * Returns null if no BucketRegion exists.
   */
  public BucketRegion getBucketRegion(Object key, Object value) {
    if (this.dataStore == null) {
      return null;
    }
    final Integer bucketId = Integer.valueOf(PartitionedRegionHelper
        .getHashKey(this, null, key, value, null));
    return this.dataStore.getLocalBucketById(bucketId);
  }

  /**
   * Test hook to return the per entry overhead for a bucket region.
   * Returns -1 if no buckets exist in this vm. 
   */
  public int getPerEntryLRUOverhead() {
    if (this.dataStore == null) { // this is an accessor
      return -1;
    }
    try {
      return this.dataStore.getPerEntryLRUOverhead();
    } catch (NoSuchElementException e) { // no buckets available
      return -1;
    }
  }

  @Override
  protected boolean isEntryIdleExpiryPossible() {
    // false always as this is a partitionedRegion,
    // its the BucketRegion that does the expiry
    return false;
  }

  @Override
  public boolean hasSeenEvent(EntryEventImpl ev) {
    // [bruce] PRs don't track events - their buckets do that
    if (this.dataStore == null) {
      return false;
    }
    return this.dataStore.hasSeenEvent(ev);
  }

  public void enableConflation(boolean conflation) {
    this.enableConflation = conflation;
  }

  public boolean isConflationEnabled() {
    return this.enableConflation;
  }

  public PRLocalScanIterator getAppropriateLocalEntriesIterator(Set<Integer> bucketSet,
      boolean primaryOnly, boolean forUpdate, boolean includeValues,
      LocalRegion currRegion, boolean fetchRemote) {
    if (bucketSet != null && !bucketSet.isEmpty()) {
      return new PRLocalScanIterator(bucketSet, null, forUpdate, includeValues, fetchRemote);
    }
    return new PRLocalScanIterator(primaryOnly, null, forUpdate, includeValues);
  }

  @Override
  public CachePerfStats getRegionPerfStats() {
    PartitionedRegionDataStore ds = getDataStore();
    CachePerfStats result = null;
    if (ds != null) {
      // If we don't have a data store (we are an accessor)
      // then we do not have per region stats.
      // This is not good. We should still have stats even for accessors.
      result = ds.getCachePerfStats(); // fixes 46692
    }
    return result;
  }

  public void updateEntryVersionInBucket(EntryEventImpl event) {

    final Integer bucketId = event.getBucketId();
    assert bucketId != KeyInfo.UNKNOWN_BUCKET;
    final InternalDistributedMember targetNode = getOrCreateNodeForBucketWrite(
        bucketId, null);

    final int retryAttempts = calcRetry();
    int count = 0;
    RetryTimeKeeper retryTime = null;
    InternalDistributedMember retryNode = targetNode;
    while (count <= retryAttempts) {
      // It's possible this is a GemFire thread e.g. ServerConnection
      // which got to this point because of a distributed system shutdown or
      // region closure which uses interrupt to break any sleep() or wait()
      // calls
      // e.g. waitForPrimary or waitForBucketRecovery
      checkShutdown();

      if (retryNode == null) {
        checkReadiness();
        if (retryTime == null) {
          retryTime = new RetryTimeKeeper(this.retryTimeout);
        }
        try {
          retryNode = getOrCreateNodeForBucketWrite(bucketId.intValue(), retryTime);
        }
        catch (TimeoutException te) {
          if (getRegionAdvisor()
              .isStorageAssignedForBucket(bucketId.intValue())) { // bucket no
                                                                  // longer
                                                                  // exists
            throw new EntryNotFoundException(LocalizedStrings.PartitionedRegion_ENTRY_NOT_FOUND_FOR_KEY_0.toLocalizedString(event.getKey()));
          }
          break; // fall out to failed exception
        }

        if (retryNode == null) {
          checkEntryNotFound(event.getKey());
        }
        continue;
      }
      final boolean isLocal = (this.localMaxMemory != 0) && retryNode.equals(getMyId());
      try {
        if (isLocal) {
          this.dataStore.updateEntryVersionLocally(bucketId, event);
        }
        else {
          updateEntryVersionRemotely(retryNode, bucketId, event);
          if (localCacheEnabled) {
            try {
              super.basicUpdateEntryVersion(event);
            }
            catch (EntryNotFoundException enf) {
              if (logger.fineEnabled()) {
                logger.fine("updateEntryVersionInBucket: "
                    + "Failed to update entry version timestamp from local cache because of "
                    + "EntryNotFoundException.", enf);
              }
            }
          }
        }
        return;
      } catch (ConcurrentCacheModificationException e) {
        if (logger.fineEnabled()) {
          logger.fine("updateEntryVersionInBucket: caught concurrent cache modification exception", e);
        }
        event.isConcurrencyConflict(true);

        if (logger.finerEnabled()) {
          logger
              .finer("ConcurrentCacheModificationException received for updateEntryVersionInBucket for bucketId: "
                  + bucketStringForLogs(bucketId.intValue())
                  + " for event: "
                  + event + " No reattampt is done, returning from here");
        }
        return;
      }
      catch (ForceReattemptException prce) {
        prce.checkKey(event.getKey());
        if (logger.fineEnabled()) {
          logger.fine("updateEntryVersionInBucket: retry attempt:" + count + " of "
              + retryAttempts, prce);
        }
        checkReadiness();

        InternalDistributedMember lastNode = retryNode;
        retryNode = getOrCreateNodeForBucketWrite(bucketId.intValue(), retryTime);
        if (lastNode.equals(retryNode)) {
          if (retryTime == null) {
            retryTime = new RetryTimeKeeper(this.retryTimeout);
          }
          if (retryTime.overMaximum()) {
            break;
          }
          retryTime.waitToRetryNode();
        }
      }
      catch (PrimaryBucketException notPrimary) {
        if (logger.fineEnabled()) {
          logger.fine("updateEntryVersionInBucket " + notPrimary.getLocalizedMessage()
              + " on Node " + retryNode + " not primary ");
        }
        getRegionAdvisor().notPrimary(bucketId.intValue(), retryNode);
        retryNode = getOrCreateNodeForBucketWrite(bucketId.intValue(), retryTime);
      }

      count++;
      if (count == 1) {
        //this.prStats.incUpdateEntryVersionOpsRetried();
      }
      //this.prStats.incUpdateEntryVersionRetries();
      if (logger.fineEnabled()) {
        logger.fine("updateEntryVersionInBucket: "
            + " Attempting to resend update version to node " + retryNode
            + " after " + count + " failed attempts");
      }
    } // while

    // No target was found
    PartitionedRegionDistributionException e 
      = new PartitionedRegionDistributionException(LocalizedStrings.PartitionedRegion_NO_VM_AVAILABLE_FOR_UPDATE_ENTRY_VERSION_IN_0_ATTEMPTS
          .toLocalizedString(Integer.valueOf(count)));  // Fix for bug 36014
    if (!logger.fineEnabled()) {
      logger.warning(
          LocalizedStrings.PartitionedRegion_NO_VM_AVAILABLE_FOR_UPDATE_ENTRY_VERSION_IN_0_ATTEMPTS, 
          Integer.valueOf(count));
    }
    else {
      logger.warning(e);
    }
    throw e;
  }

  /**
   * Updates the entry version timestamp of the remote object with the given key.
   * 
   * @param recipient
   *                the member id of the recipient of the operation
   * @param bucketId
   *                the id of the bucket the key hashed into
   * @throws EntryNotFoundException
   *                 if the entry does not exist in this region
   * @throws PrimaryBucketException
   *                 if the bucket on that node is not the primary copy
   * @throws ForceReattemptException
   *                 if the peer is no longer available
   */
  private void updateEntryVersionRemotely(InternalDistributedMember recipient,
      Integer bucketId, EntryEventImpl event) throws EntryNotFoundException,
      PrimaryBucketException, ForceReattemptException {

    UpdateEntryVersionResponse response = PRUpdateEntryVersionMessage.send(recipient, this, event);
    if (response != null) {
      this.prStats.incPartitionMessagesSent();
      try {
        response.waitForResult();
        return;
      }
      catch (EntryNotFoundException ex) {
        throw ex;
      } catch (TransactionDataRebalancedException e) {
        throw e;
      } catch (TransactionException te) {
        throw te;
      } catch (CacheException ce) {
        throw new PartitionedRegionException(LocalizedStrings.PartitionedRegion_UPDATE_VERSION_OF_ENTRY_ON_0_FAILED.toLocalizedString(recipient), ce);
      }
    }  
  }

  /**
   * Clear local primary buckets.
   * This is currently only used by gemfirexd truncate table
   * to clear the partitioned region.
   */
  public void clearLocalPrimaries() {
 // rest of it should be done only if this is a store while RecoveryLock
    // above still required even if this is an accessor
    if (getLocalMaxMemory() > 0) {
      // acquire the primary bucket locks
      // do this in a loop to handle the corner cases where a primary
      // bucket region ceases to be so when we actually take the lock
      // (probably not required to do this in loop after the recovery lock)
      // [sumedh] do we need both recovery lock and bucket locks?
      boolean done = false;
      Set<BucketRegion> lockedRegions = null;
      while (!done) {
        lockedRegions = getDataStore().getAllLocalPrimaryBucketRegions();
        done = true;
        for (BucketRegion br : lockedRegions) {
          try {
            br.doLockForPrimary(false);
          } catch (RegionDestroyedException rde) {
            done = false;
            break;
          } catch (PrimaryBucketException pbe) {
            done = false;
            break;
          } catch (Exception e) {
            // ignore any other exception
            getLogWriterI18n().fine(
                "GemFireContainer#clear: ignoring exception "
                    + "in bucket lock acquire", e);
          }
        }
      }
      
      //hoplogs - pause HDFS dispatcher while we 
      //clear the buckets to avoid missing some files
      //during the clear
      pauseHDFSDispatcher();

      try {
        // now clear the bucket regions; we go through the primary bucket
        // regions so there is distribution for every bucket but that
        // should be performant enough
        for (BucketRegion br : lockedRegions) {
          try {
            br.clear();
          } catch (Exception e) {
            // ignore any other exception
            getLogWriterI18n().fine(
                "GemFireContainer#clear: ignoring exception "
                    + "in bucket clear", e);
          }
        }
      } finally {
        resumeHDFSDispatcher();
        // release the bucket locks
        for (BucketRegion br : lockedRegions) {
          try {
            br.doUnlockForPrimary();
          } catch (Exception e) {
            // ignore all exceptions at this stage
            getLogWriterI18n().fine(
                "GemFireContainer#clear: ignoring exception "
                    + "in bucket lock release", e);
          }
        }
      }
    }
    
  }
  
  /**Destroy all data in HDFS, if this region is using HDFS persistence.*/
  private void destroyHDFSData() {
    if(getHDFSStoreName() == null) {
      return;
    }
    
    try {
      hdfsManager.destroyData();
    } catch (IOException e) {
      getLogWriterI18n().warning(LocalizedStrings.HOPLOG_UNABLE_TO_DELETE_HDFS_DATA, e);
    }
  }

  private void pauseHDFSDispatcher() {
    if(!isHDFSRegion()) {
      return;
    }
    AbstractGatewaySenderEventProcessor eventProcessor = getHDFSEventProcessor();
    if (eventProcessor == null) return;
    eventProcessor.pauseDispatching();
    eventProcessor.waitForDispatcherToPause();
  }
  
  /**
   * Get the statistics for the HDFS event queue associated with this region,
   * if any
   */
  public AsyncEventQueueStats getHDFSEventQueueStats() {
    AsyncEventQueueImpl asyncQ = getHDFSEventQueue();
    if(asyncQ == null) {
      return null;
    }
    return asyncQ.getStatistics();
  }
  
  protected AbstractGatewaySenderEventProcessor getHDFSEventProcessor() {
    final AsyncEventQueueImpl asyncQ = getHDFSEventQueue();
    final ParallelGatewaySenderImpl gatewaySender = (ParallelGatewaySenderImpl)asyncQ.getSender();
    AbstractGatewaySenderEventProcessor eventProcessor = gatewaySender.getEventProcessor();
    return eventProcessor;
  }

  public AsyncEventQueueImpl getHDFSEventQueue() {
    String asyncQId = getHDFSEventQueueName();
    if(asyncQId == null) {
      return null;
    }
    final AsyncEventQueueImpl asyncQ =  (AsyncEventQueueImpl)this.getCache().getAsyncEventQueue(asyncQId);
    return asyncQ;
  }
  
  private void resumeHDFSDispatcher() {
    if(!isHDFSRegion()) {
      return;
    }
    AbstractGatewaySenderEventProcessor eventProcessor = getHDFSEventProcessor();
    if (eventProcessor == null) return;
    eventProcessor.resumeDispatching();
  }

  protected String getHDFSEventQueueName() {
    if (!this.getDataPolicy().withHDFS()) return null;
    String colocatedWith = this.getPartitionAttributes().getColocatedWith();
    String eventQueueName;
    if (colocatedWith != null) {
      PartitionedRegion leader = ColocationHelper.getLeaderRegionName(this);
      eventQueueName = HDFSStoreFactoryImpl.getEventQueueName(leader
          .getFullPath());
    }
    else {
      eventQueueName = HDFSStoreFactoryImpl.getEventQueueName(getFullPath());
    }
    return eventQueueName;
  }

  /**
   * schedules compaction on all members where this region is hosted.
   * 
   * @param isMajor
   *          true for major compaction
   * @param maxWaitTime
   *          time to wait for the operation to complete, 0 will wait forever
   */
  @Override
  public void forceHDFSCompaction(boolean isMajor, Integer maxWaitTime) {
    if (!this.isHDFSReadWriteRegion()) {
      if (this.isHDFSRegion()) {
        throw new UnsupportedOperationException(
            LocalizedStrings.HOPLOG_CONFIGURED_AS_WRITEONLY
                .toLocalizedString(getName()));
      }
      throw new UnsupportedOperationException(
          LocalizedStrings.HOPLOG_DOES_NOT_USE_HDFSSTORE
              .toLocalizedString(getName()));
    }
    // send request to remote data stores
    long start = System.currentTimeMillis();
    int waitTime = maxWaitTime * 1000;
    HDFSForceCompactionArgs args = new HDFSForceCompactionArgs(getRegionAdvisor().getBucketSet(), isMajor, waitTime);
    HDFSForceCompactionResultCollector rc = new HDFSForceCompactionResultCollector();
    AbstractExecution execution = (AbstractExecution) FunctionService.onRegion(this).withArgs(args).withCollector(rc);
    execution.setForwardExceptions(true); // get all exceptions
    if (getLogWriterI18n().fineEnabled()) {
      getLogWriterI18n().fine("HDFS: ForceCompat invoking function with arguments "+args);
    }
    execution.execute(HDFSForceCompactionFunction.ID);
    List<CompactionStatus> result = rc.getResult();
    Set<Integer> successfulBuckets = rc.getSuccessfulBucketIds();
    if (rc.shouldRetry()) {
      int retries = 0;
      while (retries < HDFSForceCompactionFunction.FORCE_COMPACTION_MAX_RETRIES) {
        waitTime -= System.currentTimeMillis() - start;
        if (maxWaitTime > 0 && waitTime < 0) {
          break;
        }
        start = System.currentTimeMillis();
        retries++;
        Set<Integer> retryBuckets = new HashSet<Integer>(getRegionAdvisor().getBucketSet());
        retryBuckets.removeAll(successfulBuckets);
        
        for (int bucketId : retryBuckets) {
          getNodeForBucketWrite(bucketId, new PartitionedRegion.RetryTimeKeeper(waitTime));
          long now = System.currentTimeMillis();
          waitTime -= now - start;
          start = now;
        }
        
        args = new HDFSForceCompactionArgs(retryBuckets, isMajor, waitTime);
        rc = new HDFSForceCompactionResultCollector();
        execution = (AbstractExecution) FunctionService.onRegion(this).withArgs(args).withCollector(rc);
        execution.setWaitOnExceptionFlag(true); // wait for all exceptions
        if (getLogWriterI18n().fineEnabled()) {
          getLogWriterI18n().fine("HDFS: ForceCompat re-invoking function with arguments "+args+" filter:"+retryBuckets);
        }
        execution.execute(HDFSForceCompactionFunction.ID);
        result = rc.getResult();
        successfulBuckets.addAll(rc.getSuccessfulBucketIds());
      }
    }
    if (successfulBuckets.size() != getRegionAdvisor().getBucketSet().size()) {
      checkReadiness();
      Set<Integer> uncessfulBuckets = new HashSet<Integer>(getRegionAdvisor().getBucketSet());
      uncessfulBuckets.removeAll(successfulBuckets);
      throw new FunctionException("Could not run compaction on following buckets:"+uncessfulBuckets);
    }
  }

  /**
   * Schedules compaction on local buckets
   * @param buckets the set of buckets to compact
   * @param isMajor true for major compaction
   * @param time TODO use this
   * @return a list of futures for the scheduled compaction tasks
   */
  public List<Future<CompactionStatus>> forceLocalHDFSCompaction(Set<Integer> buckets, boolean isMajor, long time) {
    List<Future<CompactionStatus>> futures = new ArrayList<Future<CompactionStatus>>();
    if (!isDataStore() || hdfsManager == null || buckets == null || buckets.isEmpty()) {
      if (getLogWriterI18n().fineEnabled()) {
        getLogWriterI18n().fine(
            "HDFS: did not schedule local " + (isMajor ? "Major" : "Minor") + " compaction");
      }
      // nothing to do
      return futures;
    }
    if (getLogWriterI18n().fineEnabled()) {
      getLogWriterI18n().fine(
          "HDFS: scheduling local " + (isMajor ? "Major" : "Minor") + " compaction for buckets:"+buckets);
    }
    Collection<HoplogOrganizer> organizers = hdfsManager.getBucketOrganizers(buckets);
    
    for (HoplogOrganizer hoplogOrganizer : organizers) {
      Future<CompactionStatus> f = hoplogOrganizer.forceCompaction(isMajor);
      futures.add(f);
    }
    return futures;
  }
  
  @Override
  public void flushHDFSQueue(int maxWaitTime) {
    if (!this.isHDFSRegion()) {
      throw new UnsupportedOperationException(
          LocalizedStrings.HOPLOG_DOES_NOT_USE_HDFSSTORE
              .toLocalizedString(getName()));
    }
    HDFSFlushQueueFunction.flushQueue(this, maxWaitTime);
  }
  
  @Override
  public long lastMajorHDFSCompaction() {
    if (!this.isHDFSReadWriteRegion()) {
      if (this.isHDFSRegion()) {
        throw new UnsupportedOperationException(
            LocalizedStrings.HOPLOG_CONFIGURED_AS_WRITEONLY
                .toLocalizedString(getName()));
      }
      throw new UnsupportedOperationException(
          LocalizedStrings.HOPLOG_DOES_NOT_USE_HDFSSTORE
              .toLocalizedString(getName()));
    }
    List<Long> result = (List<Long>) FunctionService.onRegion(this)
        .execute(HDFSLastCompactionTimeFunction.ID)
        .getResult();
    if (logger.fineEnabled()) {
      logger.fine("HDFS: Result of LastCompactionTimeFunction "+result);
    }
    long min = Long.MAX_VALUE;
    for (long ts : result) {
      if (ts !=0 && ts < min) {
        min = ts;
      }
    }
    min = min == Long.MAX_VALUE ? 0 : min;
    return min;
  }

  public long lastLocalMajorHDFSCompaction() {
    if (!isDataStore() || hdfsManager == null) {
      // nothing to do
      return 0;
    }
    if (getLogWriterI18n().fineEnabled()) {
      getLogWriterI18n().fine(
          "HDFS: getting local Major compaction time");
    }
    Collection<HoplogOrganizer> organizers = hdfsManager.getBucketOrganizers();
    long minTS = Long.MAX_VALUE;
    for (HoplogOrganizer hoplogOrganizer : organizers) {
      long ts = hoplogOrganizer.getLastMajorCompactionTimestamp();
      if (ts !=0 && ts < minTS) {
        minTS = ts;
      }
    }
    minTS = minTS == Long.MAX_VALUE ? 0 : minTS;
    if (getLogWriterI18n().fineEnabled()) {
      getLogWriterI18n().fine(
          "HDFS: local Major compaction time: "+minTS);
    }
    return minTS;
  }

  public void shadowPRWaitForBucketRecovery() {
    assert this.isShadowPR();
    PartitionedRegion userPR = ColocationHelper.getLeaderRegion(this);
    boolean isAccessor = (userPR.getLocalMaxMemory() == 0);
    if (isAccessor)
      return; // return from here if accessor node
    
    // Before going ahead, make sure all the buckets of shadowPR are
    // loaded
    // and primary nodes have been decided.
    // This is required in case of persistent PR and sender.
    Set<Integer> allBuckets = userPR.getDataStore()
        .getAllLocalBucketIds();
    Set<Integer> allBucketsClone = new HashSet<Integer>();
    allBucketsClone.addAll(allBuckets);

    while (!(allBucketsClone.size() == 0)) {
      if (logger.fineEnabled()) {
        logger.fine("Need to wait until partitionedRegionQueue <<"
            + this.getName()
            + ">> is loaded with all the buckets");
      }
      Iterator<Integer> itr = allBucketsClone.iterator();
      while (itr.hasNext()) {
        InternalDistributedMember node = this
            .getNodeForBucketWrite(itr.next(), null);
        if (node != null) {
          itr.remove();
        }
      }
      // after the iteration is over, sleep for sometime before trying
      // again
      try {
        Thread.sleep(ParallelGatewaySenderQueue.WAIT_CYCLE_SHADOW_BUCKET_LOAD);
      } catch (InterruptedException e) {
        logger.error(e);
      }
    }
  }
}
