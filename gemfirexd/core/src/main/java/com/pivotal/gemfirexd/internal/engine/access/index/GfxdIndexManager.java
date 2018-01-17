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

package com.pivotal.gemfirexd.internal.engine.access.index;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.GemFireCheckedException;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.execute.EmptyRegionFunctionException;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.distributed.LockNotHeldException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.MembershipManager;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.Oplog.DiskRegionInfo;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventCallbackArgument;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TLongHashSet;
import com.gemstone.gnu.trove.TObjectProcedure;
import com.pivotal.gemfirexd.internal.catalog.DependableFinder;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.heap.MemHeap;
import com.pivotal.gemfirexd.internal.engine.access.operations.GlobalHashIndexDeleteOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.GlobalHashIndexInsertOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexDeleteOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexInsertOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexRefreshIndexKeyOperation;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionSingleKeyExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLocalLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockable;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdReadWriteLock;
import com.pivotal.gemfirexd.internal.engine.locks.impl.GfxdReentrantReadWriteLock;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.CompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.ExtractingIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.engine.store.entry.GfxdTXEntryState;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapDelta;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapDeltas;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.db.Database;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.Dependent;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.Provider;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ForeignKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.IndexRowGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ReferencedKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TriggerDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeUtilities;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.WrapperRowLocationForTxn;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.impl.sql.compile.CreateTriggerNode.GfxdIMParamInfo;
import com.pivotal.gemfirexd.internal.impl.sql.execute.RowUtil;
import com.pivotal.gemfirexd.internal.impl.sql.execute.TriggerEvent;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Index maintenance class. An instance of this class is installed in
 * PartitionedRegion for index maintenance upon any region operation (create,
 * update and destroy).
 * 
 * TODO: PERF: get rid of ExecRow and getColumn/DVD creation calls throughout
 * this class and instead use the underlying byte[]s
 * 
 * @author Rahul Dubey.
 * @author yjing
 * @author swale
 * @author kneeraj
 */
public final class GfxdIndexManager implements Dependent, IndexUpdater,
    GfxdLockable {

  private final GfxdReentrantReadWriteLock lockForContainerGII;

  private final GfxdReentrantReadWriteLock lockForIndexGII;

  private final GfxdDataDictionary dd;

  private TableDescriptor td;

  private volatile List<ConglomerateDescriptor> indexDescs;

  private volatile List<GemFireContainer> indexContainers;

  private UUID oid;

  private final GemFireContainer container;

  private volatile boolean hasFkConstriant;

  private boolean hasUniqConstraint;
  
  private volatile boolean hasGlobalIndexOrCallbacks;

  /** the descriptors for foreign keys referred by this table */
  private volatile ForeignKeyInformation[] fks;

  private boolean isPartitionedRegion;

  private boolean isEmptyRegion;

  private final String name;

  private final boolean traceLock;

  private final boolean logFineEnabled;
  private final boolean logFineOrQuery;

  private volatile ArrayList<TriggerInfoStruct>[] triggerInfo;

  private final AtomicInteger numTriggers = new AtomicInteger(0);

  private InternalDistributedMember replicatedTableTriggerFiringNode;

  private InternalDistributedMember thisNodeMemberId;

  private MembershipManager membershipManager;


  public enum Index{
    LOCAL,
    GLOBAL,
    BOTH
  }
  
  protected GfxdIndexManager(DataDictionary dd, TableDescriptor td,
      Database db, GemFireContainer gfc, boolean hasFk) {
    this.dd = (GfxdDataDictionary)dd;
    this.td = td;
    this.container = gfc;
    this.indexDescs = null;
    this.indexContainers = null;
    this.hasFkConstriant = hasFk;
    this.fks = null;
    this.name = gfc.getQualifiedTableName() + "_GFXDINDEXMANAGER";
    this.lockForContainerGII = new GfxdReentrantReadWriteLock(this.name, true);
    this.lockForIndexGII = new GfxdReentrantReadWriteLock(this.name
        + ".INDEXGII", true);
    this.logFineEnabled = SanityManager.isFineEnabled | GemFireXDUtils.TraceIndex;
    this.logFineOrQuery = this.logFineEnabled | GemFireXDUtils.TraceQuery;
    this.traceLock = this.logFineEnabled || gfc.traceLock();
  }

  /**
   * Create a new GemFireXD index manager.
   */
  public static GfxdIndexManager newIndexManager(DataDictionary dd,
      TableDescriptor td, GemFireContainer gfc, Database db, boolean hasFk) {
    return new GfxdIndexManager(dd, td, db, gfc, hasFk);
  }

  /**
   * Initialize this GemFireXD index manager. This should always be invoked
   * before the index manager gets used.
   */
  public void initialize(GemFireTransaction tran, boolean isPartitioned,
      boolean isEmpty) throws StandardException {
    this.isPartitionedRegion = isPartitioned;
    this.isEmptyRegion = isEmpty;
    this.oid = this.dd.getUUIDFactory().createUUID();
    refreshIndexListAndConstriantDesc(true, true, tran);
    this.dd.getDependencyManager().addDependency(this, this.td,
        tran.getContextManager());
    final DM dm = Misc.getDistributedSystem().getDistributionManager();
    this.membershipManager = dm.getMembershipManager();
    this.thisNodeMemberId = dm.getDistributionManagerId();
  }

  private ExecRow getExecRow(TableDescriptor td, @Unretained Object value,
      DataValueDescriptor[] row, int rowLen, boolean returnNullForDelta,
      ExtraTableInfo tableInfo) throws StandardException {
    final Class<?> cls = value.getClass();
    if (cls == byte[].class) {
      final byte[] vbytes = (byte[])value;
      final RowFormatter rf = this.container.getRowFormatter(vbytes, tableInfo);
      if (row != null) {
        return this.container
            .newExecRowFromBytes(vbytes, rf, row, rowLen, true);
      }
      else {
        return this.container.newExecRowFromBytes(vbytes, rf);
      }
    }
    else if (cls == byte[][].class) {
      final byte[][] vbyteArrays = (byte[][])value;
      final RowFormatter rf = this.container.getRowFormatter(vbyteArrays[0],
          tableInfo);
      if (row != null) {
        return this.container.newExecRowFromByteArrays(vbyteArrays, rf, row,
            rowLen, true);
      }
      else {
        return this.container.newExecRowFromByteArrays(vbyteArrays, rf);
      }
    }
    else if (cls == OffHeapRow.class) {
      @Unretained
      final OffHeapRow vbytes = (OffHeapRow)value;
      final RowFormatter rf = this.container.getRowFormatter(vbytes, tableInfo);
      if (row != null) {
        return this.container.newExecRowFromByteSource(vbytes, rf, row, rowLen,
            true);
      }
      else {
        return this.container.newExecRowFromByteSource(vbytes, rf);
      }
    }
    else if (cls == OffHeapRowWithLobs.class) {
      @Unretained
      final OffHeapRowWithLobs vbytes = (OffHeapRowWithLobs)value;
      final RowFormatter rf = this.container.getRowFormatter(vbytes, tableInfo);
      if (row != null) {
        return this.container.newExecRowFromByteSource(vbytes, rf, row, rowLen,
            true);
      }
      else {
        return this.container.newExecRowFromByteSource(vbytes, rf);
      }
    }
    else if (cls == DataValueDescriptor[].class) {
      return new ValueRow((DataValueDescriptor[])value);
    }
    else if (cls == OffHeapDelta.class || cls == OffHeapDeltas.class
        || value instanceof Delta) {
      if (returnNullForDelta) {
        // this is the case of ListOfDeltas which can happen during destroy
        // (see bug #41529)
        return null;
      }
    }
    throw new GemFireXDRuntimeException("Unsupported storage format: "
        + value.getClass().getName());
  }

  @Override
  public void onEvent(final LocalRegion owner, final EntryEventImpl event,
      final RegionEntry entry) {

    final List<GemFireContainer> indexes = this.indexContainers;
    final ArrayList<TriggerInfoStruct>[] triggers = this.triggerInfo;
    //if (GemFireXDUtils.TraceIndex || (this.container != null && this.container.isApplicationTable())) {
    if (GemFireXDUtils.TraceIndex) {
      traceIndex("GfxdIndexManager#onEvent: invoked for container %s with "
          + "index descriptors [%s] with entry %s, for event: %s",
          this.container, getObjectString(indexes), entry, event);
    }

    // null the context just in case next calls throw exceptions
    long lastModifiedFromOrigin = event.getEntryLastModified();
    event.setEntryLastModified(-1);
    boolean isPutDML = event.isPutDML();
    //boolean loadedFromHDFS = event.isLoadedFromHDFS();
    //boolean isCustomEviction = event.isCustomEviction();
    boolean skipConstraintChecks = false;
    final Operation op = event.getOperation();
    @Unretained Object oldValue = event.getOldValueAsOffHeapDeserializedOrRaw();
    final boolean diskRecovery = op.isDiskRecovery();
    // ignore for TOMBSTONE in disk recovery (#48570)
    if (diskRecovery && event.newValueIsDestroyedToken()
        && (oldValue == null || Token.isRemoved(oldValue))) {
      return;
    }
    if(event.getTXState() != null && event.isCustomEviction()) {
      return;
    }
    if (op.isDestroy() && oldValue == null) {
      return;
    }

    Object cbArg = event.getRawCallbackArgument();
    final boolean isGIIEvent = Boolean.TRUE.equals(cbArg);
    if (cbArg != null) {
      cbArg = event.getCallbackArgument(cbArg);
    }
    // for the case of update and thru GIIEvent, oldValue is passed 
    // thru context object from initialImagePut
    Object tmpOldValue = null;
    if (op.isUpdate() && isGIIEvent) {
      tmpOldValue = event.getContextObject();
      event.setContextObject(null);
    }

    assert (op.isCreate() || op.isUpdate() || op.isDestroy()
        || diskRecovery);

    final GemFireStore memStore = Misc.getMemStoreBooting();
    LanguageConnectionContext lcc = null;
    GemFireTransaction tc = null;
    boolean lockedForGII = false;
    boolean contextSet = false;
    boolean origSkipLocks = false;
    boolean origIsRemote = false;
    boolean restoreFlags = false;
    boolean lccPosDupSet = false;
    try {
      // invoke any observer present
      final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
          .getInstance();
      if (observer != null) {
        observer.beforeIndexUpdatesAtRegionLevel(owner, event, entry);
      }

      final boolean posDup = event.isPossibleDuplicate();
      // check for creates from empty regions (bug #41174)
      if (!this.isEmptyRegion && !event.hasDelta()
          && (!this.isPartitionedRegion || op.isPutAll())
          && !op.isDestroy() && !Token.isRemoved(oldValue)
          && (oldValue != null || (event.getHasOldRegionEntry()
              && !entry.isDestroyedOrRemoved()))
          // skip EntryExistsException for PUTs and for skip-constraint-checks
          && !isPutDML && !(cbArg instanceof GfxdCallbackArgument
              && ((GfxdCallbackArgument)cbArg).isSkipConstraintChecks())) {

        // check for region destroyed
        if (owner.isDestroyed) {
          throw new RegionDestroyedException(owner.toString(),
              owner.getFullPath());
        }

        boolean isRecovered = false;
        if (!owner.isInitialized()) {
          DiskRegion dr = owner.getDiskRegion();
          if (dr != null) {
            isRecovered = dr.testIsRecovered(entry, false);
          }
        }
        boolean throwEEE = false;
        if (!posDup && (owner.isInitialized() || (!this.isPartitionedRegion && !owner.isInitialized()
            && !isRecovered))) {
          if (lastModifiedFromOrigin == -1
              || lastModifiedFromOrigin != entry.getLastModified()) {
            throwEEE = true;
          }
          else {
            //last modified from origin == lastModified of entry
            //check if values are same
            @Released Object retainedOldValue = oldValue;
            if (retainedOldValue == null) {
              retainedOldValue = entry.getValueOffHeapOrDiskWithoutFaultIn(owner);
            }
            try {
            @Unretained Object newVal = event.getNewValueAsOffHeapDeserializedOrRaw();
            final Class<?> oldValClass;
            final Class<?> newValClass = newVal != null ? newVal.getClass()
                : null;
            if (retainedOldValue == null) {
              throwEEE = false;
            }
            else if ((oldValClass = retainedOldValue.getClass()) == byte[].class
                && newValClass == byte[].class) {
              throwEEE = ((byte[])retainedOldValue).length != ((byte[])newVal).length
                  || !Arrays.equals((byte[])retainedOldValue, (byte[])newVal);
            }
            else if (oldValClass == byte[][].class
                && newValClass == byte[][].class) {
              if (((byte[][])retainedOldValue).length == ((byte[][])newVal).length) {
                int len = ((byte[][])retainedOldValue).length;
                for (int i = 0; i < len; ++i) {
                  throwEEE = !Arrays.equals(((byte[][])retainedOldValue)[i],
                      ((byte[][])newVal)[i]);
                  if (throwEEE) {
                    break;
                  }
                }
              }
              else {
                throwEEE = true;
              }
            } else if (oldValClass == OffHeapRow.class && newValClass == OffHeapRow.class) {
              OffHeapRow oldBS = (OffHeapRow)retainedOldValue;
              OffHeapRow newBS = (OffHeapRow)newVal;
              int oldBSLength = oldBS.getLength();
              int newBSLength = newBS.getLength();
              if (oldBSLength != newBSLength) {
                throwEEE = true;
              } else {
                // lengths are equal
                long oldBSAddr = oldBS.getUnsafeAddress(0, oldBSLength);
                long newBSAddr = newBS.getUnsafeAddress(0, newBSLength);
                if (!UnsafeMemoryChunk.compareUnsafeBytes(oldBSAddr, newBSAddr, oldBSLength)) {
                  throwEEE = true;
                }
              }
            } else if (oldValClass == OffHeapRowWithLobs.class && newValClass == OffHeapRowWithLobs.class) {
              OffHeapRowWithLobs oldBS = (OffHeapRowWithLobs)retainedOldValue;
              OffHeapRowWithLobs newBS = (OffHeapRowWithLobs)newVal;
              throwEEE = !checkByteSourceEquivalence(oldBS, newBS);
            }
            else {
              throwEEE = true;
            }
            } finally {
              if (retainedOldValue != oldValue) {
                OffHeapHelper.release(retainedOldValue);
              }
            }
          }
          if (throwEEE) {
            if (GemFireXDUtils.TraceIndex) {
              traceIndex("GfxdIndexManager#onEvent: throwing "
                  + "EntryExistsException for event=%s, owner=%s, oldValue=%s",
                  event, owner, oldValue);
            }
            throw new EntryExistsException(event.getKey().toString(), OffHeapHelper.getHeapForm(oldValue));
          }
        } else {
          // for the case of posDup or dup during GII just ignore and return
          if (this.logFineEnabled) {
            traceIndex("GfxdIndexManager#onEvent: ignored "
                + "duplicate for existing key=%s new value=%s into %s, " +
                "old value = %s",
                event.getKey(), event.getNewValueAsOffHeapDeserializedOrRaw(), 
                this.container, tmpOldValue);
          }
          //Refresh the index keys before returning
          if (indexes != null) {
            @Released Object retainedOldValue = null;
            if (tmpOldValue == null) {
              tmpOldValue = oldValue != null ? oldValue
                  : (retainedOldValue = entry
                      .getValueOffHeapOrDiskWithoutFaultIn(owner));
            }

            try {
              ExecRow execRow = getExecRow(this.td, tmpOldValue, null, 0, false, null);
              // here lcc or conn is not yet initialized, so null is passed as of now.
              // tran is not used within this method & refreshIndexKey.
              this.refreshIndexKeyForContainers(indexes, null,
                  event, entry, execRow, null, true);
            }
            finally {
              if (retainedOldValue != null) {
                OffHeapHelper.release(retainedOldValue);
              }
            }
          }
          return;
        }
      }

      // return at this point if no indexes to update or triggers to fire
      if (indexes == null && triggers == null) {
        return;
      }
      
      EmbedConnection conn = null;
      Object routingObject = null;
      // check for connection in current context and if found then this is
      // for local execution so use that
      final ContextManager currentCM = ContextService.getFactory()
          .getCurrentContextManager();
      if (currentCM != null && (conn = EmbedConnectionContext
          .getEmbedConnection(currentCM)) != null && conn.isActive()) {
        lcc = conn.getLanguageConnectionContext();
        if (lcc.isSkipConstraintChecks()) {
          skipConstraintChecks = true;
        }
        
      }
      else if (cbArg instanceof GfxdCallbackArgument) {
        final GfxdCallbackArgument eca = (GfxdCallbackArgument)cbArg;
        final long connId = eca.getConnectionID();
        routingObject = eca.getRoutingObject();
        if (connId >= 0) {
          // setup the wrapper and use to track transactional context etc.
          // properly
          final GfxdConnectionWrapper wrapper = GfxdConnectionHolder
              .getOrCreateWrapper(null, connId, false, null);
          // note the flag below should be false since we do not intend
          // to sync on the connection object itself so the syncVersion
          // should not be incremented
          conn = wrapper.getConnectionForSynchronization(false);
          // acquire the sync lock here to allow any in-progress streaming
          // queries to complete first; however, don't need to hold on to
          // this lock since the pk based queries themselves are never
          // streaming
          // [sumedh] cannot sync on connection due to lock inversion against
          // the RegionEntry and other locks held by higher layers, so not
          // using signalling for this to wait for any ongoing operations on
          // this connection not initiated by this thread itself
          if (eca.isPkBased()) {
            wrapper.waitFor(conn.getConnectionSynchronization());
          }
          //if (eca.isPkBased()) {
          //  synchronized (conn.getConnectionSynchronization()) {
          //    lcc = conn.getLanguageConnectionContext();
          //  }
          //}
          //else {
          lcc = conn.getLanguageConnectionContext();          
          //}
          // set the skip-constraint-checks flag from the callback arg
          skipConstraintChecks = eca.isSkipConstraintChecks();
          lcc.setSkipConstraintChecks(skipConstraintChecks);
        }
      }
      // if everything fails (e.g. invocation in a background thread on remote
      // WAN site receiver), then use the thread-local connection
      if (lcc == null) {
        conn = GemFireXDUtils.getTSSConnection();
        lcc = conn.getLanguageConnectionContext();
        restoreFlags = true;
        origSkipLocks = lcc.skipLocks();
        origIsRemote = lcc.isConnectionForRemote();
        lcc.setPossibleDuplicate(posDup);
        lcc.setSkipLocks(true);
        lcc.setIsConnectionForRemote(true);
        lcc.setIsConnectionForRemoteDDL(false);
        tc = (GemFireTransaction)lcc.getTransactionExecute();
      }
      else {
        tc = (GemFireTransaction)lcc.getTransactionExecute();
      }
      
      if (routingObject == null && cbArg instanceof GfxdCallbackArgument) {
        final GfxdCallbackArgument eca = (GfxdCallbackArgument)cbArg;
        routingObject = eca.getRoutingObject();
      }
      // expect event's posDup to be set correctly whenever LCC's is set
      assert !lcc.isPossibleDuplicate() || posDup;

      // set the posDup flag from event into LCC in case this event originated
      // from a remote node that actually received the client event with posDup
      if (posDup && !lcc.isPossibleDuplicate()) {
        lcc.setPossibleDuplicate(true);
        lccPosDupSet = true;
      }

      // The index GII lock is now taken in basicPut()/destroy() before
      // RegionEntry lock to avoid deadlocks.

      assert tc != null;
      if(tc!=null) {
        if(entry instanceof TXEntryState) {
          if(((TXEntryState)entry).getUnderlyingRegionEntry().isMarkedForEviction())
            event.setLoadedFromHDFS(true);
        }
      }
      // take the GII read lock to avoid missing results in case initial DDL
      // replay is in progress -- this is released in postEvent to ensure
      // that this entry is definitely in the region map and will be picked
      // up by CreateIndexConstantAction#loadIndexConglomerate
      final boolean lockForGII = memStore.initialDDLReplayInProgress()
          || !owner.isInitialized();
      if (lockForGII) {
        // the container GII lock is to synchronize with any index list changes
        // in refreshIndexListAndConstraintDesc()
        lockForGII(false, tc);
        lockedForGII = true;
      }

      // check for region destroyed
      if (owner.isDestroyed) {
        throw new RegionDestroyedException(owner.toString(),
            owner.getFullPath());
      }

      // No constraint checking for destroy since that is done by
      // ReferencedKeyCheckerMessage if required.
      // Also check if foreign key constraint check is really required.
      TXStateInterface tx = event.getTXState(owner);
      if (tx != null && tx.isSnapshot()) {
        tx = null;
      }

      final RowLocation rl = entry instanceof RowLocation ? (RowLocation)entry
          : null;
      final boolean skipDistribution;
      final int bucketId;
      if (this.isPartitionedRegion) {
        // Cannot just type cast in BucketEntry because entry could also be
        // GfxdTXEntryState
        final BucketAdvisor ba = ((BucketRegion)owner).getBucketAdvisor();
        if (ba != null) {
          // first check event flags
          if (event.getSkipDistributionOps()) {
            skipDistribution = true;
          }
          else if (event.getDistributeIndexOps()) {
            skipDistribution = false;
          }
          else {
            skipDistribution = isGIIEvent || !ba.isPrimary()
                || diskRecovery;
          }
          bucketId = ba.getProxyBucketRegion().getId();
        }
        else {
          skipDistribution = true;
          bucketId = rl.getBucketID();
        }
        if (GemFireXDUtils.TraceIndex) {
          traceIndex("GfxdIndexManager#onEvent: set skipDistribution=%s for "
              + "bucketId=%s with isGIIEvent=%s, BucketAdvisor: %s, "
              + "existingPrimary: %s", skipDistribution, bucketId, isGIIEvent,
              ba, (ba != null?ba.basicGetPrimaryMember():null));
        }
      }
      else {
        bucketId = KeyInfo.UNKNOWN_BUCKET;
        // first check event flags
        if (event.getSkipDistributionOps()) {
          skipDistribution = true;
        }
        else if (event.getDistributeIndexOps()) {
          skipDistribution = false;
        }
        else {
          skipDistribution = isGIIEvent || event.isOriginRemote()
              || diskRecovery || event.isNetSearch();
        }
        if (GemFireXDUtils.TraceIndex) {
          traceIndex("GfxdIndexManager#onEvent: set skipDistribution=%s with "
              + "isGIIEvent=%s, originRemote=%s, netSearch=%s",
              skipDistribution, isGIIEvent, event.isOriginRemote(),
              event.isNetSearch());
        }
      }
      // inserts for create and update are done here

      FormatableBitSet changedColumns;
      ExecRow execRow, newRow, triggerExecRow = null, triggerNewRow = null;
      final ExtraTableInfo tableInfo = getTableInfo(rl);
      // setup the context object for postEvent commit or rollback
      if (event.isGFXDCreate(true) || diskRecovery) {
        execRow = getExecRow(this.td,
            event.getNewValueAsOffHeapDeserializedOrRaw(), null, 0, false,
            tableInfo);
        newRow = null;
        changedColumns = null;
        if (skipConstraintChecks) {
          // convert INSERTs to PUTs when skip-constraint-checks=true
          isPutDML = true;
        }
      }
      else if (op.isUpdate()) {
        if (event.hasDelta()) {
          final GemFireContainer.SerializableDelta delta =
            (GemFireContainer.SerializableDelta)event.getDeltaNewValue();
          if (!isEmptyRegion) {
            // oldValue's RowFormatter may be different so pass null tableInfo
            execRow = getExecRow(this.td, oldValue, null, 0, false, null);
          }
          else {
            execRow = null;
          }
          final DataValueDescriptor[] changedRow = delta.getChangedRow();
          final int changedRowLen = changedRow.length;
          changedColumns = delta.getChangedColumns();
          final int numChangedColumns;
          if (changedColumns == null) {
            changedColumns = new FormatableBitSet(changedRowLen);
            for (int index = 0; index < changedRowLen; ++index) {
              changedColumns.set(index);
            }
            numChangedColumns = changedRowLen;
          }
          else {
            numChangedColumns = changedColumns.getNumBitsSet();
          } 
          if (!isEmptyRegion) {
            newRow = getExecRow(this.td, event.getNewValueAsOffHeapDeserializedOrRaw(), changedRow,
                numChangedColumns, false, tableInfo);
          }
          else {
            newRow = new ValueRow(changedRow);
          }
          isPutDML = false;
          // For events loaded from HDFS maintain some extra info for trigger.
          if (event.isLoadedFromHDFS()) {
            triggerExecRow = execRow; 
            triggerNewRow = newRow;
            execRow = getExecRow(this.td, event.getNewValueAsOffHeapDeserializedOrRaw(), null, 0, false,
                tableInfo);
            newRow = null;
            if (GemFireXDUtils.TraceIndex) {
              traceIndex("GfxdIndexManager#onEvent: handling update of "
                  + "faulted in entry oldValue faulted in =%s, newValue =%s",
                  triggerExecRow, triggerNewRow);
            }
          } else {
            triggerExecRow = execRow; 
            triggerNewRow = newRow;
          }
        }
        else if(op.equals(Operation.LOCAL_LOAD_UPDATE)) {
            execRow = getExecRow(this.td, oldValue, null, 0, false, null);         
            newRow = getExecRow(this.td, event.getNewValueAsOffHeapDeserializedOrRaw(), null,
                0, false, tableInfo);
          int numCols = newRow.nColumns();
          changedColumns = new FormatableBitSet(numCols);
          for(int i =0 ; i < numCols; ++i) {
            changedColumns.set(i);
          }
          isPutDML = false;
        }
        else if (isGIIEvent) {
          if (GemFireXDUtils.TraceIndex) {
            traceIndex("GfxdIndexManager#onEvent: handling gii event where "
                + "value obtained from gii=%s, is different from one obtained" +
                " from disc or its previous value=%s",
                event.getNewValueAsOffHeapDeserializedOrRaw(), tmpOldValue);
          }
  
          if (indexes == null) {
            return;
          }
          // insert into index entries related to new updated value through
          // gii and then do delete related to the old value
          execRow = getExecRow(this.td, tmpOldValue, null, 0, false, null);
          try {
            deleteFromIndexes(null, null, owner, event, skipDistribution,
                entry, indexes, execRow, null, true, isPutDML, true);
          } finally {
            newRow = getExecRow(this.td,
                event.getNewValueAsOffHeapDeserializedOrRaw(), null, 0, false,
                null);

            insertIntoIndexes(tc, tx, owner, event, diskRecovery,
                skipDistribution, rl, newRow, null, null, bucketId, indexes,
                null, isPutDML, Index.BOTH, isGIIEvent);
          }
          return;
        }
        else if (isPutDML) {
          execRow = getExecRow(this.td, event.getNewValueAsOffHeapDeserializedOrRaw(), null, 0, false,
              tableInfo);
          newRow = null;
          changedColumns = null;
        }
        else {
          // this can happen if the same insert comes through both GII and
          // normal P2P update channel; just return so context will be null
          // and postEvent() will also get skipped
          // Neeraj: This can also happen in putall
          if (owner.isInitialized() && op.isPutAll()) {
            if (GemFireXDUtils.TraceIndex) {
              traceIndex("GfxdIndexManager#onEvent: throwing "
                  + "EntryExistsException for event=%s, owner=%s, oldValue=%s",
                  event, owner, oldValue);
            }
            throw new EntryExistsException(event.getKey().toString(), OffHeapHelper.getHeapForm(oldValue));
          }
          //if this is GII event, we need to refresh the index key here because
          // the context object containing old value has already been set as null,
          // so in post event the refresh will not work.
        //Refresh the index keys before returning
          if (indexes != null) {
            execRow = getExecRow(this.td, event.getNewValueAsOffHeapDeserializedOrRaw(), null, 0, false, null);          
            this.refreshIndexKeyForContainers(indexes, tc, event, entry,
                execRow, null, true);
          }
          return;
        }
      }
      else { // op.isDestroy()
        // oldValue's RowFormatter may be different so pass null tableInfo
        if (oldValue == null) {
          if (GemFireXDUtils.TraceIndex) {
            traceIndex("GfxdIndexManager#onEvent: oldValue is null for "
                + "assumed destroy op and event is: %s" , event);
          }
          // case of TOMBSTONE or when there is no underlying row (#49020)
          return;
        }
        execRow = getExecRow(this.td, oldValue, null, 0, true, null);
        newRow = null;
        changedColumns = null;
      }
      // fire any before triggers
      if (triggers != null && !isGIIEvent && !isPutDML) {
        // check for the case when this is invoked twice from two paths for
        // removeEntry
        if (!op.isDestroy()
            || owner.getRegionMap().getEntry(event.getKey()) != null) {
          if (op.isUpdate() && event.isLoadedFromHDFS()) {
            fireTriggers(conn, lcc, tc, tx, owner, event, triggerExecRow,
                triggerNewRow, true, skipDistribution, triggers);
          }
          else {
            fireTriggers(conn, lcc, tc, tx, owner, event, execRow, newRow,
                true, skipDistribution, triggers);
          }
        }
      }
      // in case of exceptions we revert the inserts done so far using the
      // undoList
      final ArrayList<GemFireContainer> undoList;
      final EntryEventContext eventContext;
      if (indexes != null) {
        undoList = new ArrayList<GemFireContainer>(indexes.size());
        eventContext = new EntryEventContext(conn, lcc, tc,
            skipDistribution, execRow, newRow, changedColumns, undoList,
            lockedForGII, bucketId, origSkipLocks, origIsRemote, restoreFlags,
            lccPosDupSet, isGIIEvent, isPutDML, routingObject);
        event.setContextObject(eventContext);
        contextSet = true;
        if (op.isUpdate() && event.isLoadedFromHDFS()) {
          eventContext.triggerExecRow = triggerExecRow;
          eventContext.triggerNewExecRow = triggerNewRow;
        }
      }
      else {
        // need to set the context for triggers in postEvent
        eventContext = new EntryEventContext(conn, lcc, tc,
            skipDistribution, execRow, newRow, changedColumns, null,
            lockedForGII, bucketId, origSkipLocks, origIsRemote, restoreFlags,
            lccPosDupSet, isGIIEvent, isPutDML, routingObject);
        event.setContextObject(eventContext);
        contextSet = true;
        if (op.isUpdate() && event.isLoadedFromHDFS()) {
          eventContext.triggerExecRow = triggerExecRow;
          eventContext.triggerNewExecRow = triggerNewRow;
        }
        return;
      }
      if (event.isGFXDCreate(true) || diskRecovery || isPutDML ) {
        if(!op.isDestroy()) {
          doInsert(tc, tx, owner, event, diskRecovery, skipDistribution, rl,
            execRow, bucketId, indexes, undoList, isPutDML,
            skipConstraintChecks, isGIIEvent);
        }
        if (isPutDML) {
          if (oldValue != null) {
            eventContext.execRow = getExecRow(this.td, oldValue, null, 0,
                false, tableInfo);
          }
        }
      } else if (op.isUpdate()) {
        if(event.isLoadedFromHDFS()) {
          // we have to insert into local index
          // whereas we have to update the global index
          doUpdateForOperationOnHDFSFaultedInRow(tc, tx, owner, event, diskRecovery, skipDistribution, rl,
              execRow, newRow, triggerExecRow, triggerNewRow, changedColumns, bucketId, indexes, undoList,
              isPutDML, skipConstraintChecks);
        }
        else {
          doUpdate(tc, tx, owner, event, diskRecovery, skipDistribution, rl,
              execRow, newRow, changedColumns, bucketId, indexes, undoList,
              isPutDML, skipConstraintChecks, isGIIEvent);
        }
            
      }
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      // Asif: If the exception is thrown due to cache close, we should throw it
      // as is as other wise
      // PartitionMessage$PartitionResponse.waitForCacheException will not do
      // the retry (#43359).
      handleException(t, "Exception maintaining index", lcc, owner, event);
    } finally {
      if (!contextSet) {
        if (lcc != null) {
          if (restoreFlags) {
            lcc.setSkipLocks(origSkipLocks);
            lcc.setIsConnectionForRemote(origIsRemote);
          }
          if (lccPosDupSet) {
            lcc.setPossibleDuplicate(false);
          }
        }
        if (lockedForGII) {
          unlockForGII(false, tc);
        }
        if (GemFireXDUtils.TraceIndex) {
          traceIndex("GfxdIndexManager#onEvent: FAILED before EventContext "
              + "setting for container %s, set context: %s", this.container,
              event.getContextObject());
        }
      }
      else if (GemFireXDUtils.TraceIndex) {
        traceIndex("GfxdIndexManager#onEvent: for container %s, "
            + "set context: %s", this.container, event.getContextObject());
      }
    }
  }

  private boolean checkByteSourceEquivalence(@Unretained OffHeapRowWithLobs oldWithLobs,
      @Unretained OffHeapRowWithLobs newWithLobs) {
    int numLobs = oldWithLobs.readNumLobsColumns(true);
    int totalRows = numLobs + 1;
    int oldZerothRowLen = oldWithLobs.getLength();
    int newZerothRowLen = newWithLobs.getLength();
    if(oldZerothRowLen == newZerothRowLen 
        && numLobs == newWithLobs.readNumLobsColumns(true)) {
      long oldBSAddr = oldWithLobs.getUnsafeAddress(0, oldZerothRowLen);
      long newBSAddr = newWithLobs.getUnsafeAddress(0, newZerothRowLen);
      if (!UnsafeMemoryChunk.compareUnsafeBytes(oldBSAddr, newBSAddr,
          oldZerothRowLen)) {
        return false;
      }
      for(int i =1 ; i < totalRows ; ++i) {
        Object oldRow = oldWithLobs.getGfxdByteSource(i);
        Object newRow = newWithLobs.getGfxdByteSource(i);
        if(oldRow == null && newRow == null) {
          return true;
        } else if (oldRow != null && newRow != null) {
          Class<?> oldRowClass = oldRow.getClass();
          if (oldRowClass == newRow.getClass()) {
            if (oldRowClass == OffHeapRow.class) {
              OffHeapRow oldRowLob = (OffHeapRow)oldRow;
              OffHeapRow newRowLob = (OffHeapRow)newRow;
              int oldRowLobLength = oldRowLob.getLength();
              int newRowLobLength = newRowLob.getLength();
              if (oldRowLobLength != newRowLobLength) {
                return false;
              } else {
                // lengths are equal
                long oldRowLobAddr = oldRowLob.getUnsafeAddress(0, oldRowLobLength);
                long newRowLobAddr = newRowLob.getUnsafeAddress(0, newRowLobLength);
                if (!UnsafeMemoryChunk.compareUnsafeBytes(oldRowLobAddr, newRowLobAddr, oldRowLobLength)) {
                  return false;
                }
              }
            } else {
              // it must be byte[]
              if (!(((byte[]) oldRow).length == ((byte[]) newRow).length && Arrays
                  .equals((byte[]) oldRow, (byte[]) newRow))) {
                return false;
              }
            }
          } else {
            return false;
          }
        }else {
          return false;
        }
      }
      return true;
    } else {
      return false;
    }
  }

  @Override
  public void postEvent(LocalRegion owner, EntryEventImpl event,
      final RegionEntry entry, final boolean success) {
    final List<GemFireContainer> indexes = this.indexContainers;
    //if (GemFireXDUtils.TraceIndex || (this.container != null && this.container.isApplicationTable())) {
    if (GemFireXDUtils.TraceIndex) {
      traceIndex("GfxdIndexManager#postEvent: invoking for container %s with "
          + "index descriptors [%s] for entry %s, success=%s, for event: %s",
          this.container, getObjectString(indexes), entry, success,
          event);
    }
    if (event.getTXState() != null && event.isCustomEviction()) {
      return;
    }
    final Operation op = event.getOperation();
    //try {
    TXStateInterface tx = event.getTXState(owner);
    if (tx != null && tx.isSnapshot()) {
      tx = null;
    }
    if (success && tx == null /* txnal ops will update this at commit */) {
      if (!(op.isUpdate() && event.hasDelta())) {
        this.container.updateNumRows(op.isDestroy());
      }
    }

    assert (op.isCreate() || op.isUpdate() || op.isDestroy()
        || op.isDiskRecovery());

    if (EntryEventImpl.SUSPECT_TOKEN == event.getContextObject()) {
      event.setContextObject(null);
      try {
        @Unretained Object valBytes =  event.getOldValueAsOffHeapDeserializedOrRaw();
        //If for some reason old value bytes are null, use the new value bytes to create
        // exec row
        if(valBytes == null) {
          valBytes = event.getNewValueAsOffHeapDeserializedOrRaw();
        }
        ExecRow execRow = getExecRow(this.td, valBytes, null, 0, false, 
            null);
        List<GemFireContainer> unaffectedContainers = indexes;
        if (unaffectedContainers != null && !unaffectedContainers.isEmpty()) {
          this.refreshIndexKeyForContainers(unaffectedContainers, null, event,
              entry, execRow, tx,success);
        }
      } catch (Exception e) {
        handleException(e, "Exception maintaining index", null, null, null);
      }
      return;

    }
    
    final EntryEventContext ctx = (EntryEventContext)event.getContextObject();    
    if (ctx == null) {
      // Can happen if there are no indexes to update and triggers to fire.
      // Also possible in case of an unexpected exception in onEvent().
      // Tt can also happen if a create comes in both through GII and through
      // normal P2P update channel in which case second one gets converted to
      // update and is ignored since it is a non-delta update.
      // Exclude UPDATE op converted from CREATE op, see #49306
      if (op.isUpdate() ) {
        try {
          @Unretained Object valBytes =  event.getOldValueAsOffHeapDeserializedOrRaw();
          //If for some reason old value bytes are null, use the new value bytes to create
          // exec row
          if(valBytes == null) {
            valBytes = event.getNewValueAsOffHeapDeserializedOrRaw();
          }
          ExecRow execRow = getExecRow(this.td, valBytes, null, 0,
              false, null);
          List<GemFireContainer> unaffectedContainers = indexes;
          if (unaffectedContainers != null && !unaffectedContainers.isEmpty()) {
            this.refreshIndexKeyForContainers(unaffectedContainers, null,
                event, entry, execRow, tx, success);
          }
        } catch (Exception e) {
          handleException(e, "Exception maintaining index", null, null, null );          
        }
      }
      return;
    }

    final ArrayList<TriggerInfoStruct>[] triggers = this.triggerInfo;
    if (GemFireXDUtils.TraceIndex) {
      if (!success && ctx.undoList != null && ctx.undoList.size() > 0) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
            "Undoing index changes with undoList: " + ctx.undoList);
      }
    }

    final EmbedConnection conn = ctx.conn;
    final LanguageConnectionContext lcc = ctx.lcc;
    final GemFireTransaction tc = ctx.tc;
    final boolean isPutDML = ctx.isPutDML;
    final boolean skipDistribution = ctx.skipDistribution
        // recheck flag in event; set for TX abort explicitly to skip ops
        || event.getSkipDistributionOps();
    try {
      final ExecRow execRow;
      final ExecRow newRow;
      // no index needed to be updated
      if (indexes == null
          // currently no index updates need to be done for these cases
          || (success && event.isGFXDCreate(true) && !isPutDML)
          || (!success && op.isDestroy())) {
        if (!isPutDML) {
          if (success && triggers != null && !ctx.isGIIEvent) {
            if (op.isUpdate() && event.isLoadedFromHDFS()) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
                  "Firing triggers with execRow " + ctx.triggerExecRow
                      + " newRow : " + ctx.triggerNewExecRow);
              fireTriggers(conn, lcc, tc, tx, owner, event, ctx.triggerExecRow,
                  ctx.triggerNewExecRow, false, skipDistribution, triggers);
            }
            else {
              fireTriggers(conn, lcc, tc, tx, owner, event, ctx.execRow,
                  ctx.newExecRow, false, skipDistribution, triggers);
            }
          }
        }
        if (success && indexes != null && !op.isCreate() && !ctx.isGIIEvent) {
          // refresh all the indexes
          List<GemFireContainer> unaffectedContainers = indexes;
          if (unaffectedContainers != null && !unaffectedContainers.isEmpty()) {
            this.refreshIndexKeyForContainers(unaffectedContainers, tc, event,
                entry, ctx.execRow, tx, success);
          }
        }
        //TODO:Asif: Though the below a logical , but it creates problem for 
        //say a create which failed & was to be destroyed . In this case say an
        //insert which failed , but the LRU callback meant it was to be destroyed
        // but here there is nothing to be destroyed & so we get an exception
        /*else if(!success && op.isDestroy() ) {
          if(ctx.execRow != null) {
            execRow = ctx.execRow;
          }else {          
            execRow = getExecRow(this.td, event.getOldValueAsOffHeapDeserializedOrRaw(), null, 0, false,
                getTableInfo(entry));
          }
          deleteFromIndexes(tc, tx, owner, event, skipDistribution, entry,
              indexes, execRow, null, ctx.bucketId, false, isPutDML);
        }*/
        return;
      }

      final ArrayList<GemFireContainer> undoList;

      if (success) {
        execRow = ctx.execRow;
        if (isPutDML) {
          if (execRow != null ) {
            if(op.isUpdate()) { 
            if ((undoList = ctx.undoList) != null && !undoList.isEmpty()) {
              deleteFromIndexes(tc, tx, owner, event, skipDistribution, entry,
                  ctx.undoList, execRow, null, true, isPutDML, success);
            }
            else {
              if(ctx.changedColumns != null) {
                deleteFromIndexes(tc, tx, owner, event, skipDistribution, entry,
                    indexes, execRow, ctx.changedColumns, true, isPutDML, success);
              }else {
                this.refreshIndexKeyForContainers(indexes, null,
                  event, entry, execRow, null, success);
              }
            }
            }else if(op.isDestroy()) {
              deleteFromIndexes(tc, tx, owner, event, skipDistribution, entry,
                  indexes, execRow, null, false, isPutDML, success);
            }
          }
        }
        //In case of GII initialImagePut , the old value which may be offheap 
        // is stored in context object
        else if (op.isUpdate() && (event.hasDelta() || event.hasOffHeapValue())) {
          if (event.isLoadedFromHDFS()) {
            // 1. For local index : do nothing
            // 2. For global index: delete the previous row
            final FormatableBitSet changedColumns = ctx.changedColumns;          
            if (changedColumns != null) {
              //TODO: Asif Why triggerExecRow?
              // if we have the undoList then it already contains the correct
              // indexes and we do not need to pass the changedColumns
              if ((undoList = ctx.undoList) != null && !undoList.isEmpty()) {
                deleteFromIndexes(tc, tx, owner, event, skipDistribution, entry,
                    undoList, ctx.triggerExecRow, null, true, isPutDML, success);
              }
              else {
                deleteFromIndexes(tc, tx, owner, event, skipDistribution, entry,
                    indexes, ctx.triggerExecRow, changedColumns, true, isPutDML, success);
              }
              if (SanityManager.DEBUG) {
                checkIndexListChange(indexes, "update");
              }
            }else {
              List<GemFireContainer> unaffectedContainers = indexes;
              if (unaffectedContainers != null && !unaffectedContainers.isEmpty()) {
                this.refreshIndexKeyForContainers(unaffectedContainers, tc, event, entry,
                    execRow, tx, success);            
              }
            }
          } else {
            final FormatableBitSet changedColumns = ctx.changedColumns;          
            if (changedColumns != null) {
              // if we have the undoList then it already contains the correct
              // indexes and we do not need to pass the changedColumns
              if ((undoList = ctx.undoList) != null && !undoList.isEmpty()) {
                deleteFromIndexes(tc, tx, owner, event, skipDistribution, entry,
                    undoList, execRow, null, true, isPutDML, success);
              }
              else {
                deleteFromIndexes(tc, tx, owner, event, skipDistribution, entry,
                    indexes, execRow, changedColumns, true, isPutDML, success);
              }
              if (SanityManager.DEBUG) {
                checkIndexListChange(indexes, "update");
              }
            }else {
              List<GemFireContainer> unaffectedContainers = indexes;
              if (unaffectedContainers != null && !unaffectedContainers.isEmpty()) {
                this.refreshIndexKeyForContainers(unaffectedContainers, tc, event, entry,
                    execRow, tx, success);            
              }
            }  
          }
          
        }
        else if (op.isDestroy()) {
          // the execRow can be null in case destroy comes in while base value
          // is a ListOfDeltas in which case ignore the destroy (bug #41529)
          if (execRow != null) {
            doDelete(tc, tx, owner, event, skipDistribution, entry, execRow,
                indexes, isPutDML, success);
          }
        }

        if (triggers != null && !ctx.isGIIEvent) {
          if (op.isUpdate() && event.isLoadedFromHDFS()) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
                "Firing triggers with execRow " + ctx.triggerExecRow
                    + " newRow : " + ctx.triggerNewExecRow);
            fireTriggers(conn, lcc, tc, tx, owner, event, ctx.triggerExecRow,
                ctx.triggerNewExecRow, false, skipDistribution, triggers);
          }
          else {
            fireTriggers(conn, lcc, tc, tx, owner, event, execRow,
                ctx.newExecRow, false, skipDistribution, triggers);
          }
        }
      }
      else if ((undoList = ctx.undoList) != null) {
        if (event.isGFXDCreate(true) || isPutDML) {
          execRow = ctx.execRow;
          deleteFromIndexes(tc, tx, owner, event, skipDistribution, entry,
              undoList, execRow, null, false, isPutDML, success /* case false*/);
        }
        else if (op.isUpdate()) {
          newRow = getExecRow(this.td,
              event.getNewValueAsOffHeapDeserializedOrRaw(), null, 0, false,
              getTableInfo(entry));
          deleteFromIndexes(tc, tx, owner, event, skipDistribution, entry,
              undoList, newRow, null, false, isPutDML, success /* case false*/);
        }
      }
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      // Asif: If the exception is thrown due to cache close, we should throw it
      // as is as other wise
      // PartitionMessage$PartitionResponse.waitForCacheException will not do
      // the retry (#43359).
      handleException(t, "Exception maintaining index", lcc,
          null, null /* no failed event registration in postEvent */);
    } finally {
      
      if (ctx.routingObject != null) {
        GfxdCallbackArgument cbarg = (GfxdCallbackArgument)event.getCallbackArgument();
        cbarg.setRoutingObject((Integer)ctx.routingObject);
      }
      
      event.setContextObject(null);
      if (conn != null) {
        if (lcc != null) {
          if (ctx.restoreFlags) {
            lcc.setSkipLocks(ctx.origSkipLocks);
            lcc.setIsConnectionForRemote(ctx.origIsRemote);
          }
          if (ctx.lccPosDupSet) {
            lcc.setPossibleDuplicate(false);
          }
        }
      }
      // release the GII read lock taken in onEvent to avoid missing results in
      // case initial DDL replay is in progress -- this ensures that this entry
      // is definitely in the region map and will be picked up by
      // CreateIndexConstantAction#loadIndexConglomerate
      if (ctx.isGIILocked) {
        unlockForGII(false, tc);
      }
    }
    
  }

  @Override
  public void postEventCleanup(final EntryEventImpl event) {
    final Object ctx = event.getContextObject();
    if (ctx != null) {
      event.setContextObject(null);
    }
  }

  @Override
  public boolean needsRecovery() {
    return needsRecovery(this.indexContainers, null);
  }

  public static boolean needsRecovery(final Collection<?> indexes,
      final List<GemFireContainer> fetchLocalIndexes) {
    boolean hasLocalIndexes = false;
    if (indexes != null && indexes.size() > 0) {
      for (Object index : indexes) {
        GemFireContainer indexContainer = (GemFireContainer)index;
        if (indexContainer.isLocalIndex()) {
          if (fetchLocalIndexes != null) {
            fetchLocalIndexes.add(indexContainer);
          }
          else {
            return true;
          }
          hasLocalIndexes = true;
        }
      }
    }
    return hasLocalIndexes;
  }

  @Override
  public void onOverflowToDisk(RegionEntry entry) {
    List<GemFireContainer> localIndexContainers = this.indexContainers;
    if (localIndexContainers == null || localIndexContainers.size() == 0) {
      return;
    }

    //Get a reference to the byte[] value that we are faulting out.
    final Object faultedOutBytes;
    final Object faultedOutValue = entry._getValue();
    if (faultedOutValue != null) {
      faultedOutBytes = SortedMap2IndexDeleteOperation
          .getRowByteSource(faultedOutValue);
      if (faultedOutBytes == null) {
        return;
      }
      /*
      final Class<?> cls = faultedOutValue.getClass();
      if (cls == byte[].class) {
        faultedOutBytes = (byte[])faultedOutValue;
      }
      else if (cls == byte[][].class) {
        faultedOutBytes = ((byte[][])faultedOutValue)[0];
      }
      else if (OffHeapByteSource.isOffHeapBytesClass(cls)) {
        faultedOutBytes = faultedOutValue;
      }
      else {
        return;
      }
      */
    }
    else {
      return;
    }

    List<ConglomerateDescriptor> localIndexDescList = this.indexDescs;
    try {
      //loop through all indexes, updating the index key if it has reference
      //to the value for this region entry.
      for (int index = 0; index < localIndexDescList.size(); ++index) {
        final GemFireContainer indexContainer = localIndexContainers.get(index);

        final boolean isIndexTypeGlobal = !indexContainer.isLocalIndex();
        final ConcurrentSkipListMap<Object, Object> skipList = indexContainer
            .getSkipListMap();
        if (isIndexTypeGlobal || skipList == null) {
          continue;
        }

        final ExtractingIndexKey extractingKey = indexContainer
            .getExtractingKey(faultedOutBytes);
       // ((RowLocation) entry).markDeleteFromIndexInProgress();
       // try {
          // This get is used to extract the actual key instance held in the map
          final Object indexValue = skipList.get(extractingKey);
          final CompactCompositeIndexKey foundKey = extractingKey.getFoundKey();

          // if the key in the skipList is pointing at these value bytes.
          // TODO:Asif: This needs to be handled once OffHeap comes into
          // picture.
          // Object valueBytesInIndexKey = foundKey.getValueByteSource();
          // !=
          // null?RowFormatter.getBytesFromSourceOrArray(foundKey.getValueByteSource()):null;
          if (foundKey != null) {
            Object vbs = foundKey.getValueByteSource();
            try {
              if (SortedMap2IndexRefreshIndexKeyOperation.bytesSameAsCCIKBytes(
                  faultedOutBytes, vbs)) {
                boolean updatedValue = false;
                // If the index holds a RowLocation array, see if there
                // is another field that has a value the key could point at.
                Class<?> vClass;
                if (indexValue == null) {
                  // can this happen?
                  updatedValue = false;
                } else if ((vClass = indexValue.getClass()) == RowLocation[].class) {
                  RowLocation[] values = (RowLocation[]) indexValue;
                  for (RowLocation value : values) {
                    if (value != entry && value != null) {
                      if (value.useRowLocationForIndexKey()) {
                        try {
                          @Retained @Released Object replacementBytes = SortedMap2IndexDeleteOperation
                              .getRowLocationByteSource(value);

                          if (replacementBytes != null) {
                            try {
                              if (!SortedMap2IndexRefreshIndexKeyOperation
                                  .bytesSameAsCCIKBytes(replacementBytes,
                                      faultedOutBytes)
                                  // If the valueBytes we've extracted from the
                                  // value
                                  // don't match this region key, then some
                                  // other
                                  // thread must have already changed the value
                                  // for
                                  // that entry. Don't use those value bytes.
                                  && foundKey
                                      .equalsValueBytes(replacementBytes)) {
                                foundKey.update(replacementBytes,
                                    faultedOutBytes);
                                // if the update above fails then some other
                                // update
                                // has succeeded so don't snapshot key from
                                // value in
                                // any case
                                updatedValue = true;
                                break;
                              }
                            } finally {
                              foundKey.releaseValueByteSource(replacementBytes);
                            }
                          }
                        } finally {
                          value.endIndexKeyUpdate();
                        }
                      }
                    }
                  }
                } else if (vClass == ConcurrentTHashSet.class) {
                  ConcurrentTHashSet<?> set = (ConcurrentTHashSet<?>) indexValue;
                  for (Object value : set) {
                    if (value != entry && value != null) {
                      RowLocation rl = (RowLocation) value;
                      if (rl.useRowLocationForIndexKey()) {
                        try {
                          Object replacementBytes = SortedMap2IndexDeleteOperation
                              .getRowLocationByteSource(rl);
                          if (replacementBytes != null) {
                            try {
                              if (!SortedMap2IndexRefreshIndexKeyOperation
                                  .bytesSameAsCCIKBytes(replacementBytes,
                                      faultedOutBytes)
                                  // If the valueBytes we've extracted from the
                                  // value
                                  // don't match this region key, then some
                                  // other
                                  // thread must have already changed the value
                                  // for
                                  // that entry. Don't use those value bytes.
                                  && foundKey
                                      .equalsValueBytes(replacementBytes)) {
                                foundKey.update(replacementBytes,
                                    faultedOutBytes);
                                // if the update above fails then some other
                                // update
                                // has succeeded so don't snapshot key from
                                // value in
                                // any case
                                updatedValue = true;
                                break;
                              }
                            } finally {
                              foundKey.releaseValueByteSource(replacementBytes);
                            }
                          }
                        } finally {
                          rl.endIndexKeyUpdate();
                        }
                      }
                    }
                  }
                }
                // For single value entries, concurrent hash maps, or
                // RowLocation[]
                // entries in the index with no remaining in memory values
                // snapshot
                // the value in the key.
                if (!updatedValue) {
                  byte[] value = foundKey.snapshotKeyFromValue();
                  if(value != null){
                    indexContainer.accountSnapshotEntry(value.length);
                  }


                }
              }
            } finally {
              foundKey.releaseValueByteSource(vbs);
            }
          }
        /*} finally {
          ((RowLocation) entry).unmarkDeleteFromIndexInProgress();
        }*/
      }
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      // Asif: If the exception is thrown due to cache close, we should throw it
      // as is as other wise
      // PartitionMessage$PartitionResponse.waitForCacheException will not do
      // the retry (#43359).
      handleException(t, "Exception maintaining index", null, null, null);
    }
  }

  @Override
  public void onFaultInFromDisk(RegionEntry entry) {
    List<GemFireContainer> localIndexContainers = this.indexContainers;

    if (localIndexContainers == null || localIndexContainers.size() == 0) {
      return;
    }

    //Get a reference to the byte[] value that we are faulting in.
    final byte[] faultedInBytes;
    final Object faultedOutValue = entry._getValue();
    if (faultedOutValue != null) {
      final Class<?> fClass = faultedOutValue.getClass();
      if (fClass == byte[].class) {
        faultedInBytes = (byte[])faultedOutValue;
      }
      else if (fClass == byte[][].class) {
        faultedInBytes = ((byte[][])faultedOutValue)[0];
      }
      else {
        return;
      }
    }
    else {
      return;
    }

    try {
      final int numContainers = localIndexContainers.size();
      for (int index = 0; index < numContainers; index++) {
        final GemFireContainer indexContainer = localIndexContainers.get(index);
        final ConcurrentSkipListMap<Object, Object> skipList = indexContainer
            .getSkipListMap();
        if (skipList == null) {
          continue;
        }

        final ExtractingIndexKey extractingKey = indexContainer
            .getExtractingKey(faultedInBytes);

        //This get is used to extract the actual key instance held in the map
        if (skipList.containsKey(extractingKey)) {
        //don't bother to update the keys of CHMs.
        //if (!(indexValue instanceof CustomEntryConcurrentHashMap<?, ?>)) {
          final CompactCompositeIndexKey foundKey = extractingKey.getFoundKey();
          //if the key is using a snapshot of the bytes, set the key
          //to use these bytes instead.
          if (foundKey != null && foundKey.isValueNull()) {
            // try to replace value bytes only if it remains null
            foundKey.update(faultedInBytes, null);
          }
        }
      }
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      // Asif: If the exception is thrown due to cache close, we should throw it
      // as is as other wise
      // PartitionMessage$PartitionResponse.waitForCacheException will not do
      // the retry (#43359).
      handleException(t, "Exception maintaining index", null, null, null);
    }
  }

  private final ExtraTableInfo getTableInfo(final RegionEntry entry) {
    return this.isEmptyRegion ? this.container.getExtraTableInfo()
        : (ExtraTableInfo)entry.getContainerInfo();
  }

  private final ExtraTableInfo getTableInfo(final RowLocation rl) {
    return this.isEmptyRegion ? this.container.getExtraTableInfo()
        : rl.getTableInfo(this.container);
  }

  private String getObjectString(Object obj) {
    return (obj != null ? (obj + getRefString(obj)) : "(null)");
  }

  private String getRefString(Object obj) {
    return ("@" + Integer.toHexString(System.identityHashCode(obj)));
  }

  /**
   * Refresh the index list from DataDictionary for the table.
   * 
   * @param lockGII
   *          true if lock for container GII has to be acquired
   * @param onlyGlobalIndexes
   *          true if only global indexes have to be refreshed else all indexes
   *          are refreshed
   */
  final void refreshIndexListAndConstriantDesc(boolean lockGII,
      final boolean onlyGlobalIndexes, final GemFireTransaction tran)
      throws StandardException {
    if (lockGII) {
      lockGII = lockForGII(true, tran);
    }
    try {
      // null checks required for the case of drop table
      if (this.td == null || (this.td =
          this.dd.getTableDescriptor(this.td.getObjectID())) == null) {
        this.indexDescs = null;
        this.indexContainers = null;
        this.hasFkConstriant = false;
        this.hasGlobalIndexOrCallbacks = false;
        this.fks = null;
        return;
      }
      // nothing to refresh for indexes if an accessor region
      final LocalRegion region = this.container.getRegion();
//      if (region != null && region.getDataPolicy().withPartitioning()
//          && region.getPartitionAttributes().getLocalMaxMemory() == 0) {
//        return;
//      }

      // refresh constraint Desc list.
      // do it always since a FK constraint may be getting added
      this.td.emptyConstraintDescriptorList();
      this.dd.getConstraintDescriptors(this.td);

      this.hasGlobalIndexOrCallbacks = false;
      final ConglomerateDescriptor[] cds = td.getConglomerateDescriptors();
      TLongHashSet cdlUniq = new TLongHashSet();
      ArrayList<ConglomerateDescriptor> cdl =
        new ArrayList<ConglomerateDescriptor>();
      ArrayList<GemFireContainer> containerList =
        new ArrayList<GemFireContainer>();
      for (ConglomerateDescriptor cd : cds) {
        long cdNum = cd.getConglomerateNumber();
        IndexRowGenerator irg;
        // not an index
        if (!cd.isIndex() || (irg = cd.getIndexDescriptor()).indexType()
              .equals(GfxdConstants.LOCAL_HASH1_INDEX_TYPE)
            // for empty regions no local index updates need to be done; only FK
            // checks and global index maintenance
            || ((this.isEmptyRegion || onlyGlobalIndexes) && irg.indexType()
                .equals(GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE))) {
          continue;
        }
        else if (cdlUniq.contains(cdNum)) {
          continue;
        }

        final GemFireContainer container = Misc.getMemStore().getContainer(
            ContainerKey.valueOf(ContainerHandle.TABLE_SEGMENT, cd
                .getConglomerateNumber()));
        if (container == null) {
          GemFireXDUtils.throwAssert("unexpected null container for index: "
              + cd + "; for container " + this.container + " with descriptors "
              + getObjectString(this.indexDescs));
        }
        if (irg.isUnique()) {
          cdl.add(0, cd); // put the unique index at the start of the array
          containerList.add(0, container);
        }
        else {
          cdl.add(cd);
          containerList.add(container);
        }
        if (!this.hasGlobalIndexOrCallbacks && !container.isLocalIndex()) {
          this.hasGlobalIndexOrCallbacks = true;
        }
        cdlUniq.add(cdNum);
      }

      // check for any async event listeners
      if (!this.hasGlobalIndexOrCallbacks && region != null) {
        // assume that if there is a gateway sender then it is likely to be
        // enabled and running on requisite store nodes
        this.hasGlobalIndexOrCallbacks = region.isGatewaySenderEnabled();
      }

      // refresh foreign key information
      this.hasFkConstriant = false;
      ArrayList<ForeignKeyConstraintDescriptor> fkcdList =
        new ArrayList<ForeignKeyConstraintDescriptor>();
      ConstraintDescriptorList cdList = this.td.getConstraintDescriptorList();
      for (int index = 0; index < cdList.size(); ++index) {
        ConstraintDescriptor cd = cdList.elementAt(index);
        if (cd.getConstraintType() == DataDictionary.FOREIGNKEY_CONSTRAINT) {
          this.hasFkConstriant = true;
          fkcdList.add((ForeignKeyConstraintDescriptor)cd);
        }
        else if (cd.getConstraintType() == DataDictionary.UNIQUE_CONSTRAINT) {
          this.hasUniqConstraint = true;
        }
      }
      if (this.hasFkConstriant) {
        final ForeignKeyInformation[] fks = new ForeignKeyInformation[fkcdList
            .size()];
        ForeignKeyConstraintDescriptor fkcd;
        ReferencedKeyConstraintDescriptor rkcd;
        GemFireContainer refContainer;
        GemFireContainer refIndexContainer;
        ConglomerateDescriptor refConglom;
        int[] refColsPartColsMap;
        LocalRegion refRegion;
        GfxdPartitionResolver refResolver;
        for (int index = 0; index < fkcdList.size(); index++) {
          fkcd = fkcdList.get(index);
          rkcd = fkcd.getReferencedConstraint();
          refContainer = ((MemHeap)tran.findExistingConglomerate(rkcd
              .getTableDescriptor().getHeapConglomerateId()))
              .getGemFireContainer();
          refConglom = rkcd.getIndexConglomerateDescriptor(this.dd);
          refIndexContainer = ((MemIndex)tran
              .findExistingConglomerate(refConglom.getConglomerateNumber()))
              .getGemFireContainer();
          int[] refColPositions = rkcd.getReferencedColumns();
          refColsPartColsMap = null;
          // refIndexContainer will be null for hash primary key index
          // self-referencing FK has null region as has not yet been created
          //  (but does not require reshuffle)
          if (refColPositions.length > 1
        		  && (!fkcd.isSelfReferencingFK())   
              && (refRegion = refContainer.getRegion())
                  .getPartitionAttributes() != null
              && (refIndexContainer == null
                  || refIndexContainer.isLocalIndex())) {
            refResolver = (GfxdPartitionResolver)refRegion
                .getPartitionAttributes().getPartitionResolver();
            String[] refPartCols = refResolver.getColumnNames();
            String[] refCols = rkcd.getColumnDescriptors().getColumnNames();
            refColsPartColsMap = new int[refPartCols.length];
            boolean requiresShuffle = (refPartCols.length != refCols.length);
            for (int cIndex = 0; cIndex < refPartCols.length; cIndex++) {
              // find partitioning column in the FK reference
              int refIndex = 0;
              String refPartCol = refPartCols[cIndex];
              for (;;) {
                if (refPartCol.equals(refCols[refIndex])) {
                  break;
                }
                if (++refIndex >= refCols.length) {
                  Assert.fail("failed to get referenced column for "
                      + "partitioning column " + refPartCol + " in table "
                      + rkcd.getTableDescriptor());
                }
              }
              refColsPartColsMap[cIndex] = refIndex;
              if (!requiresShuffle) {
                requiresShuffle = (cIndex != refIndex);
              }
            }
            if (!requiresShuffle) {
              // indicate that no shuffle of columns is required
              refColsPartColsMap = null;
            }
          }
          fks[index] = new ForeignKeyInformation(fkcd, refContainer,
              refIndexContainer, refColsPartColsMap);
        }
        this.fks = fks;
      }
      else {
        this.fks = null;
      }
      // initialize the cached index descriptor and container lists
      int size = cdl.size();
      if (size == 0 && !this.hasFkConstriant) {
        this.indexDescs = null;
        this.indexContainers = null;
      }
      else {
        this.indexDescs = Collections.unmodifiableList(cdl);
        this.indexContainers = Collections.unmodifiableList(containerList);
      }
    } finally {
      if (this.logFineOrQuery) {
        traceIndex("GfxdIndexManager#refreshIndexListAndConstriantDesc:"
            + " for container %s descriptor %s new index descriptors: %s",
            this.container, getRefString(this.td), getObjectString(indexDescs));
      }
      if (lockGII) {
        unlockForGII(true, tran);
      }
    }
  }

  private AbstractRowLocation createGlobalRowLocation(RowLocation rl,
      ExecRow newRow, EntryEventImpl event, boolean isPrimaryKeyConstraint)
      throws StandardException {
    return GlobalRowLocation.getRowLocation(rl, newRow, this.container, event,
        isPrimaryKeyConstraint);
  }

  /**
   * Update the indexes.
   */
  private void doInsert(GemFireTransaction tc, final TXStateInterface tx,
      LocalRegion owner, EntryEventImpl event, boolean diskRecovery,
      boolean skipDistribution, RowLocation rl, ExecRow execRow, int bucketId,
      List<GemFireContainer> localIndexContainers,
      ArrayList<GemFireContainer> undoList, boolean isPutDML,
      boolean skipConstraintChecks, boolean isGIIEvent) throws StandardException {
    if (GemFireXDUtils.TraceIndex) {
      logKeyValue("doInsert", rl, event.getRawNewValue());
    }
//    if(event.isLoadedFromHDFS()) {
//      return;
//    }
    // Skip FK checks for gateway events if the flag is set on the cache.
    boolean skipFKChecks = false;
    if (skipConstraintChecks) {
      skipFKChecks = true;
    }
    else {
      Object callbackArg = event.getRawCallbackArgument();
      if (GemFireCacheImpl.getExisting().skipFKChecksForGatewayEvents()
          && callbackArg != null
          && callbackArg instanceof GatewaySenderEventCallbackArgument) {
        skipFKChecks = true;
        if (GemFireXDUtils.TraceIndex) {
          traceIndex("GfxdIndexManager#doInsert: "
              + "skipping FK checks for gateway event %s", event);
        }
      }
    }
    // check for foreign key constraints before insertion
    if (!skipDistribution && this.hasFkConstriant && !skipFKChecks) {
      checkForeignKeyConstraint(event, tc, tx, execRow, null, rl);
    }
    insertIntoIndexes(tc, tx, owner, event, diskRecovery, skipDistribution, rl,
        execRow, null, null, bucketId, localIndexContainers, undoList,
        isPutDML, Index.BOTH, isGIIEvent);
  }

  private void logKeyValue(String op, Object key, @Unretained Object value) {
    byte[] rawBytes = null;
    if (value != null) {
      final Class<?> cls = value.getClass();
      if (cls == byte[].class) {
        rawBytes = (byte[])value;
      }
      else if (cls == byte[][].class) {
        rawBytes = ((byte[][])value)[0];
      }
    }
    traceIndex("GfxdIndexManager#%s: invoked for container %s for "
        + "entry/key %s, raw new value: %s", op, this.container, key,
        (rawBytes != null ? Arrays.toString(rawBytes) : value));
  }

  private void insertIntoIndexes(GemFireTransaction tran,
      final TXStateInterface tx, LocalRegion owner, EntryEventImpl event,
      boolean diskRecovery, boolean skipDistribution, RowLocation rl,
      ExecRow newRow, ExecRow oldRow, FormatableBitSet changedColumns,
      int bucketId, List<GemFireContainer> localIndexContainers,
      ArrayList<GemFireContainer> undoList, boolean isPutDML,
      Index indexToUpdate, boolean isGIIEvent) throws StandardException {
    final int numContainers = localIndexContainers.size();
    for (int index = 0; index < numContainers; index++) {
      if (SanityManager.DEBUG) {
        checkIndexListChange(localIndexContainers, "insert");
      }
      insertIntoIndex(tran, tx, owner, event, diskRecovery, skipDistribution,
          rl, newRow, oldRow, changedColumns, bucketId,
          localIndexContainers.get(index), undoList, isPutDML, indexToUpdate,
          isGIIEvent);
    }
  }

  public final void insertIntoIndex(GemFireTransaction tran,
      final TXStateInterface tx, LocalRegion owner, EntryEventImpl event,
      boolean diskRecovery, boolean skipDistribution, RowLocation rl,
      ExecRow newRow, ExecRow oldRow, FormatableBitSet changedColumns,
      int bucketId, final GemFireContainer indexContainer,
      ArrayList<GemFireContainer> undoList, boolean isPutDML, 
      Index indexToUpdate, boolean isGIIEvent)
      throws StandardException {

    if (GemFireXDUtils.TraceIndex) {
      traceIndex("GfxdIndexManager#insertIntoIndexes: Value bytes source" +
      		" to be inserted is = %s" , newRow.getByteSource());
          
    }
    final boolean indexInitialized = indexContainer.isInitialized();
    boolean isIndexTypeGlobal = !indexContainer.isLocalIndex();
    if (skipDistribution && isIndexTypeGlobal) {
      return;
    }
    //do not insert into index if the owner is marked for destroy
    if(!isIndexTypeGlobal && owner != null && owner.isDestroyed()) {
      return ;
    }
    // 1. Local index should not be updated for entries loaded from HDFS
    // 2. Global index should be updated but evicted immediately
//    if(!isIndexTypeGlobal && event.isLoadedFromHDFS()) {
//      return;
//    }
    // for an update determine if the index key value has changed.
    if (changedColumns != null
        && unchangedIndexKey(indexContainer.getBaseColumnPositions(),
            changedColumns)) {
      return;
    }

    Object insertedIndexKey = null;
    RowLocation rowLocation = null;
    boolean result = false;
    // insert new entry in an index
    try {
      if (isIndexTypeGlobal && (indexToUpdate==Index.GLOBAL || indexToUpdate==Index.BOTH)) {
        insertedIndexKey = indexContainer.getGlobalIndexKey(newRow);
        rowLocation = createGlobalRowLocation(rl, newRow, event,
            indexContainer.isGlobalIndexForPrimaryKey());
        //#48894: in case that PUT DML is changing partitioning column, it should throw EEE
        //Set wasPutDML only when global index is for primary key, see #51463
        boolean wasPutDML = false;
        if (isPutDML && event.getOperation().isCreate() && indexContainer.isGlobalIndexForPrimaryKey()) {
          isPutDML = false;
          wasPutDML = true;
        }
        GlobalHashIndexInsertOperation.doMe(tran, tx, indexContainer,
            insertedIndexKey, rowLocation, isPutDML, wasPutDML);
        
        final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
            .getInstance();
        if (observer != null) {
          observer.afterGlobalIndexInsert(true);
        }
        result = true;
        if (tx != null && indexContainer.getRegion().isHDFSReadWriteRegion()) {
          ((GfxdTXEntryState)rl).updateIndexInfos(indexContainer,
              insertedIndexKey);
        }
      }
      else if(!isIndexTypeGlobal && (indexToUpdate==Index.LOCAL || indexToUpdate==Index.BOTH)){
        // snapshot key bytes for DISK_RECOVERY
        insertedIndexKey = indexContainer.getLocalIndexKey(newRow, rl,
            !diskRecovery, false);
        rowLocation = rl;

        boolean unique = indexContainer.isUniqueIndex();
        if (this.logFineOrQuery) {
          traceIndex("SortedMap2IndexInsertOp: for indexContainer=%s "
              + "unique=%s and isGIIEvent=%s", indexContainer, unique,
              isGIIEvent);
        }
        if (isGIIEvent && unique) {
          if (InitialImageOperation.TRACE_GII_FINER) {
            traceIndex(
                "GfxdIndexManager#insertIntoIndexes: for index %s "
                    + "passing isUnique as false as this is a GII event"
                    + "is unitialized", indexContainer);
          }
          unique = false;
        }
        
        if (tx == null && unique) {
          if (this.isPartitionedRegion && (owner != null && !owner.isInitialized())) {
            final BucketAdvisor ba = ((BucketRegion)owner).getBucketAdvisor();
            if (ba != null && !ba.isPrimary()) {
              if (InitialImageOperation.TRACE_GII_FINER) {
                traceIndex(
                    "GfxdIndexManager#insertIntoIndexes: for index %s "
                        + "passing isUnique as false as this is an update to a secondary copy"
                        , indexContainer);
              }
              unique = false;
            }
          }
        }
        
        result = SortedMap2IndexInsertOperation.doMe(tran, tx, indexContainer,
            insertedIndexKey, rowLocation, unique,
            null, isPutDML);

        if (tx != null) {
          ((GfxdTXEntryState)rowLocation).updateIndexInfos(indexContainer,
              insertedIndexKey);
        }
      }
      // if insert successful then add to undo list
      if (undoList != null && result) {
        undoList.add(indexContainer);
        if (GemFireXDUtils.TraceIndex) {
          traceIndex("GfxdIndexManager#insertIntoIndexes: undoList %s for "
              + "container (%s) for key[%s] with RowLocation[%s] added "
              + "index: %s", getRefString(undoList), this.container,
              insertedIndexKey, rowLocation, indexContainer);
        }
      }
    } catch (StandardException se) {
      // in case of unique constraint violation check if the affected columns
      // were changed to the same value by comparing the GemFireKeys
      if (SQLState.LANG_DUPLICATE_KEY_CONSTRAINT.equals(se.getSQLState())) {
        final Throwable t = se.getCause();
        boolean isOldValueEqual = true;
        if (t instanceof EntryExistsException) {
          EntryExistsException eee = (EntryExistsException)t;
          final Object oldRowLocObj = eee.getOldValue();
          if (oldRowLocObj instanceof RowLocation) {
            final RowLocation oldRowLoc = (RowLocation)oldRowLocObj;
            if (changedColumns != null) {
              // compare the old and new rows to get the columns that have
              // really changed
              changedColumns = getChangedColumns(oldRow, newRow);
              // update the changedColumns in EntryEvent's context object
              EntryEventContext ctx = (EntryEventContext)event
                  .getContextObject();
              if (ctx != null) {
                ctx.changedColumns = changedColumns;
              }
              if (changedColumns == null) {
                // Even if the index column has not changed still the refresh of
                // index keys need to be done
                return;
              }
              else if (unchangedIndexKey(
                  indexContainer.getBaseColumnPositions(), changedColumns)) {
                final boolean isUnique = indexContainer.isUniqueIndex();
                // below code puts a wrapped GfxdTXEntryState so that any
                // other txns scanning/updating this index will get an
                // appropriate conflict (or lock will wait for timeout)
                if (tx != null && isUnique && !isIndexTypeGlobal
                    && !tx.getTransactionId().equals(oldRowLoc.getTXId())) {
                  GfxdTXEntryState stxe = (GfxdTXEntryState)rowLocation;
                  WrapperRowLocationForTxn wrl = new WrapperRowLocationForTxn(
                      stxe, insertedIndexKey, !event.getOperation().isDestroy());
                  SortedMap2IndexInsertOperation.doMe(tran, tx, indexContainer,
                      insertedIndexKey, rowLocation, isUnique, wrl, isPutDML);
                  if (GemFireXDUtils.TraceIndex) {
                    traceIndex("GfxdIndexManager#insertIntoIndexes: wrapper "
                        + "%s for container (%s) with indexes %s for key[%s] "
                        + "with RowLocation[%s] replacing in index: %s", wrl,
                        this.container, getRefString(this.indexDescs),
                        insertedIndexKey, rowLocation, indexContainer);
                  }
                }
                return;
              }
            }
            // for retries check if the original hash in GlobalRowLocation
            // matches that for the new one; ignore if a match is found else
            // throw the exception (see comments towards the end of #41177)
            isOldValueEqual = oldRowLoc.equals(rowLocation);
          }
          // change RowLocation to its toString() to avoid serializing it
          // over the wire
          Object[] args = se.getArguments();
          if (args != null && args.length > 0
              && "duplicate entry".equals(args[0])) {
            // create new exception with full args
            eee = new EntryExistsException(eee.getMessage() + " for tuple "
                + rowLocation + ", key=" + insertedIndexKey + "; myID: "
                + GemFireStore.getMyId(),
                ArrayUtils.objectStringNonRecursive(oldRowLocObj));
            se = StandardException.newException(
                SQLState.LANG_DUPLICATE_KEY_CONSTRAINT, eee, args);
          }
          else {
            eee.setOldValue(ArrayUtils.objectStringNonRecursive(oldRowLocObj));
          }
        }
        final boolean ownerInitialized = owner == null || owner.isInitialized();
        if (this.logFineEnabled) {
          BucketAdvisor ba = null;
          if (owner instanceof BucketRegion) {
            ba = ((BucketRegion)owner).getBucketAdvisor();
          }
          traceIndex("GfxdIndexManager.insertIntoIndexes duplicate exception"
              + " region=%s(primary=%s), skipDistribution=%s, container=%s, "
              + "isInitialized=%s, owner initialized=%s, indexInitialized=%s,"
              + "isDiskRecovery=%s", owner != null ? owner.getFullPath() : "",
              ba != null ? ba.isPrimary() : "NA", skipDistribution,
              this.container, this.container.isInitialized(), ownerInitialized,
              indexInitialized, diskRecovery);
        }
        // can happen during bucket GII (due to EntryOperation as CREATE in
        // initialImagePut) or duplicate event in HA retry
        if (isOldValueEqual
            && (!indexInitialized || !ownerInitialized || event
                .isPossibleDuplicate())) {
          if (this.logFineEnabled) {
            traceIndex("GfxdIndexManager#insertIntoIndexes: ignored "
                + "duplicate exception key=%s value=%s into %s",
                insertedIndexKey, rowLocation, indexContainer);
          }          
        }
        else {
          // If the operation was create, in case of GFE value never gets
          // inserted , but in gfxd, the value gets created so off heap contains
          // a value, need to release it
          /*Operation op = event.getOperation();
          if(op.isCreate() || diskRecovery || op.isUpdate()) {
           newRow.releaseByteSource();
            
          }*/
          throw se;
        }
      }
      else {
        throw se;
      }
    }
  }

  private FormatableBitSet getChangedColumns(ExecRow oldRow, ExecRow newRow)
      throws StandardException {
    int columns = oldRow.nColumns();
    FormatableBitSet changedColumns = new FormatableBitSet(columns + 1);
    for (int i = 1; i <= columns; i++) {
      if (!oldRow.getColumn(i).equals(newRow.getColumn(i))) {
        changedColumns.set(i - 1);
      }
    }
    return (changedColumns.getNumBitsSet() > 0 ? changedColumns : null);
  }

  private void doUpdateForOperationOnHDFSFaultedInRow(GemFireTransaction tc, final TXStateInterface tx,
      LocalRegion owner, EntryEventImpl event, boolean diskRecovery,
      boolean skipDistribution, RowLocation rl, ExecRow oldRow, ExecRow newRow, ExecRow triggerExecRow, ExecRow triggerNewRow,
      FormatableBitSet changedColumns, int bucketId,
      List<GemFireContainer> localIndexContainers,
      ArrayList<GemFireContainer> undoList, boolean isPutDML,
      boolean skipConstraintChecks) throws StandardException {
    
    // no column changes, return
    if (changedColumns != null) {
      if (GemFireXDUtils.TraceIndex) {
        traceIndex("GfxdIndexManager#doUpdate: invoked for container %s for "
            + "entry %s with changed columns %s, raw new value: %s",
            this.container, rl, changedColumns, event.getRawNewValue());
      }
      
      // Skip FK checks for gateway events if the flag is set on the cache.
      boolean skipFKChecks = false;
      if (skipConstraintChecks) {
        skipFKChecks = true;
      }
      else {
        Object callbackArg = event.getRawCallbackArgument();
        if (GemFireCacheImpl.getExisting().skipFKChecksForGatewayEvents()
            && callbackArg != null
            && callbackArg instanceof GatewaySenderEventCallbackArgument) {
          skipFKChecks = true;
          if (GemFireXDUtils.TraceIndex) {
            traceIndex("GfxdIndexManager#doUpdate: "
                + "skipping FK checks for gateway event %s", event);
          }
        }
      }
      if (!skipDistribution && this.hasFkConstriant && !skipFKChecks) {
        checkForeignKeyConstraint(event, tc, tx, triggerNewRow, changedColumns, rl);
      }
      // Note that the order should be insert and then delete (in doPostUpdate).
      // There is no problem of spurious clash since the insert/delete is only
      // required if the index key is being changed, so in that case the insert
      // is required to not clash with an existing entry.
      // This is update operation on faulted-in entry 
      // as local index would have been deleted earlier we just insert the newRow
      // Local insert
      insertIntoIndexes(tc, tx, owner, event, diskRecovery, skipDistribution,
          rl, oldRow, null, null, bucketId, localIndexContainers,
          undoList, isPutDML, Index.LOCAL, false);
      
      //Global update
      // We update the global index as global index gets evicted and not deleted
      // when a row is evicted.
      insertIntoIndexes(tc, tx, owner, event, diskRecovery, skipDistribution,
          rl, triggerNewRow, triggerExecRow, changedColumns, bucketId, localIndexContainers,
          undoList, isPutDML, Index.GLOBAL, false);

    }
  }
  
  
  private void doUpdate(GemFireTransaction tc, final TXStateInterface tx,
      LocalRegion owner, EntryEventImpl event, boolean diskRecovery,
      boolean skipDistribution, RowLocation rl, ExecRow oldRow, ExecRow newRow,
      FormatableBitSet changedColumns, int bucketId,
      List<GemFireContainer> localIndexContainers,
      ArrayList<GemFireContainer> undoList, boolean isPutDML,
      boolean skipConstraintChecks, boolean isGIIEvent) throws StandardException {
    // no column changes, return
    if (changedColumns != null) {
      if (GemFireXDUtils.TraceIndex) {
        traceIndex("GfxdIndexManager#doUpdate: invoked for container %s for "
            + "entry %s with changed columns %s, raw new value: %s",
            this.container, rl, changedColumns, event.getRawNewValue());
      }
      
      // Skip FK checks for gateway events if the flag is set on the cache.
      boolean skipFKChecks = false;
      if (skipConstraintChecks) {
        skipFKChecks = true;
      }
      else {
        Object callbackArg = event.getRawCallbackArgument();
        if (GemFireCacheImpl.getExisting().skipFKChecksForGatewayEvents()
            && callbackArg != null
            && callbackArg instanceof GatewaySenderEventCallbackArgument) {
          skipFKChecks = true;
          if (GemFireXDUtils.TraceIndex) {
            traceIndex("GfxdIndexManager#doUpdate: "
                + "skipping FK checks for gateway event %s", event);
          }
        }
      }
      if (!skipDistribution && this.hasFkConstriant && !skipFKChecks) {
        checkForeignKeyConstraint(event, tc, tx, newRow, changedColumns, rl);
      }
      // Note that the order should be insert and then delete (in doPostUpdate).
      // There is no problem of spurious clash since the insert/delete is only
      // required if the index key is being changed, so in that case the insert
      // is required to not clash with an existing entry.
      insertIntoIndexes(tc, tx, owner, event, diskRecovery, skipDistribution,
          rl, newRow, oldRow, changedColumns, bucketId, localIndexContainers,
          undoList, isPutDML, Index.BOTH, isGIIEvent);
    }
  }

  private void deleteFromIndexes(GemFireTransaction tran,
      final TXStateInterface tx, LocalRegion owner, EntryEventImpl event,
      boolean skipDistribution, RegionEntry entry,
      List<GemFireContainer> containers, ExecRow execRow,
      FormatableBitSet changedColumns, boolean refreshIndexKey,
      boolean isPutDML, boolean isSuccess) throws StandardException {
//    if (event.isLoadedFromHDFS()) {
//      if (GemFireXDUtils.TraceIndex) {
//        traceIndex("GfxdIndexManager#deleteFromIndexes: "
//            + "skipping the delete as the entry was faulted in from hdfs %s", event);
//      }
//      return;
//    }
    Object cbArg = event.getRawCallbackArgument();
    LogWriter logger = Misc.getCacheLogWriterNoThrow();
    final boolean isGIIEvent = Boolean.TRUE.equals(cbArg);
    @SuppressWarnings("unchecked")
    final Set<GemFireContainer> unaffectedContainers = (changedColumns == null
        && refreshIndexKey) ? new THashSet(this.indexContainers) : null;
    Throwable firstThrowable  = null, exceptionToThrow = null;  
    int len = containers.size();
    for (int i = 0 ; i < len; ++i) {
      final GemFireContainer indexContainer  = containers.get(i);
      boolean isIndexTypeGlobal = !indexContainer.isLocalIndex();
      if (skipDistribution  && isIndexTypeGlobal) {
        continue;
      }
      // For local index, while the row was evicted entry was deleted from the index
      // also when rows is faulted in we do not update index
      // so no need to delete it again
      //If the operation is unsucessful, the row inserted into the index
      //needs to be removed.
      if(!isIndexTypeGlobal && event.isLoadedFromHDFS() && isSuccess) {
        continue;
      }
      // for an update determine if the index key value has changed.
      if (changedColumns != null
          && unchangedIndexKey(indexContainer.getBaseColumnPositions(),
              changedColumns)) {
        if (refreshIndexKey && !isIndexTypeGlobal) {
          ExtractingIndexKey indexKey = (ExtractingIndexKey) indexContainer
              .getLocalIndexKey(execRow, entry, false, true);
          if (tx == null) {
            refreshIndexKey(tran, event, entry, execRow, indexContainer,
                indexKey, isSuccess);
          } else {
            ((GfxdTXEntryState) entry).updateUnaffectedIndexInfos(
                indexContainer, indexKey);
          }
        }
        continue;
      }
      
      if(unaffectedContainers != null) {
        unaffectedContainers.remove(indexContainer);
      }
      
      final Object indexKey;
      boolean deleted = false;
     
      RowLocation rowLocation;
      if (isIndexTypeGlobal) {
        indexKey = indexContainer.getGlobalIndexKey(execRow);
        rowLocation = null;
        if (!indexContainer.isGlobalIndexForPrimaryKey() || !isPutDML) {
          try {
            final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
                .getInstance();
            if (observer != null) {
              observer.beforeGlobalIndexDelete();
            }
            deleted = GlobalHashIndexDeleteOperation.doMe(tran, tx,
                indexContainer, indexKey, event.isCustomEviction());
          }catch(Throwable th) { 
            Error err;
            if (th instanceof Error && SystemFailure.isJVMFailureError(
                err = (Error)th)) {
              SystemFailure.initiateFailure(err);
              // If this ever returns, rethrow the error. We're poisoned
              // now, so don't let this thread continue.
              throw err;
            }
            SystemFailure.checkFailure();
            if(firstThrowable == null) {
              firstThrowable = th;
              if (logger != null) {
                logger.error("GfxdIndexManager::deleteFromIndexes: Encountered primary" +
                    " exception while doing index maintenance. Storing the exception" +
                    " & continuing index maintenance", th);
              }
            } else {
              if (logger != null) {
                logger.error("GfxdIndexManager::deleteFromIndexes: Encountered secondary" +
                    " exception while doing index maintenance. Ignoring the exception" +
                    " & continuing index maintenance", th);
              }
            }          
            //continue
          }
        }
        else {
          //treat it as deleted, although we don't need delete the index
          deleted = true;
        }
      }
      else {
        indexKey = indexContainer.getLocalIndexKey(execRow, entry);
        // this.container.newRowLocation(entry, bucketId);
        rowLocation = (RowLocation)entry;
        WrapperRowLocationForTxn wrapper = null;
        GfxdTXEntryState txEntry = null;
        if (rowLocation.getTXId() != null) {
          txEntry = (GfxdTXEntryState)rowLocation;
          // for rollback, just remove the inserted GfxdTXEntryState (#51553)
          if (isSuccess) {
            wrapper = txEntry.wrapperForRollback(indexContainer, indexKey);
          }
        }
        try {
        if (wrapper != null) {
          rowLocation = (RowLocation)rowLocation.getUnderlyingRegionEntry();
          if (GemFireXDUtils.TraceIndex | GemFireXDUtils.TraceQuery) {
            Object deletedValue = isGIIEvent ? execRow.getBaseByteSource()
                : event.getOldValueAsOffHeapDeserializedOrRaw();
            GfxdIndexManager.traceIndex("SortedMap2Index: "
                + "Going to insert deleted wrapper for key=%s value=(%s) into %s," +
                "deleted value byte source = %s",
                indexKey, GemFireXDUtils.TraceIndex ? wrapper : ArrayUtils
                    .objectRefString(wrapper), indexContainer, deletedValue);
          }
          
          deleted = SortedMap2IndexInsertOperation.replaceInSkipListMap(
              indexContainer, indexKey, rowLocation, wrapper,
              indexContainer.isUniqueIndex(), null /* we do not want 
              tx wrapper rowlocation to cause change in index key*/,
              false /* isPutDML */);
          if (this.logFineOrQuery) {
            Object deletedValue = isGIIEvent ? execRow.getBaseByteSource()
                : event.getOldValueAsOffHeapDeserializedOrRaw();
            GfxdIndexManager.traceIndex("SortedMap2Index: "
                + "inserted deleted wrapper for key=%s value=(%s) into %s," +
                "deleted value byte source = %s",
                indexKey, GemFireXDUtils.TraceIndex ? wrapper : ArrayUtils
                    .objectRefString(wrapper), indexContainer, deletedValue);
          }
        }
        else {
          Object deletedValue = isGIIEvent ? execRow.getBaseByteSource()
              : event.getOldValueAsOffHeapDeserializedOrRaw();
          if (this.logFineOrQuery) {
            GfxdIndexManager.traceIndex("SortedMap2Index: "
                + "Attempting to delete value bytes from index for key=%s into %s," +
                "deleted value byte source = %s",
                indexKey,  indexContainer, deletedValue);
          }
          deleted = SortedMap2IndexDeleteOperation.doMe(tran, indexContainer,
              indexKey, rowLocation, indexContainer.isUniqueIndex(),
              deletedValue);
          if (!isSuccess && txEntry != null && deleted) {
            // cleanup transactional entry from index tx map
            txEntry.clearIndexInfos(indexContainer);
          }
        }
        }catch(Throwable th) {
          Error err;
          if (th instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)th)) {
            SystemFailure.initiateFailure(err);
            // If this ever returns, rethrow the error. We're poisoned
            // now, so don't let this thread continue.
            throw err;
          }
          SystemFailure.checkFailure();
          if(firstThrowable == null) {
            if (logger != null) {
              logger.error("GfxdIndexManager::deleteFromIndexes: Encountered primary" +
                  " exception while doing index maintenance. Storing the exception" +
                  " & continuing index maintenance", th);
            }
            firstThrowable = th;
          } else {
            if (logger != null) {
              logger.error("GfxdIndexManager::deleteFromIndexes: Encountered secondary" +
                  " exception while doing index maintenance. Ignoring the exception" +
                  " & continuing index maintenance", th);
            }
          }
        }
      }      
      
     
      if (!deleted &&  exceptionToThrow == null) {
        try {
        handleNotDeleted(event.isPossibleDuplicate(), owner, indexContainer,
            rowLocation, entry, indexKey, firstThrowable);  
        firstThrowable = null;
        }catch(Exception e) {
          //Fix for 49131. If the handleNotDeleted throws an exception,
          //than for insert & update ,we still continue because the operation 
          //from GFE perspective is alreday successful. But for destroy operation
          // the operation is successful only of postEvent does not throw exception.
          // since we are here & operation is destroy , the operation will not be
          //successful. So we will have to undo the deletes done on indexes so far.
          exceptionToThrow = e;          
          if (event.getOperation().isDestroy()) {
            if(tx == null) {
              this.undoDeleteIndexesForUnsuccessfulDestroy(entry, owner, event,
                isPutDML, containers, i - 1, tran, execRow);
            }
            break;
          }
        }
      }
    }
    
    if (unaffectedContainers != null && !unaffectedContainers.isEmpty()) {
     refreshIndexKeyForContainers(unaffectedContainers, tran, event, entry,
         execRow,  tx, isSuccess);
    }
    if(exceptionToThrow != null) {
      if(exceptionToThrow instanceof Error) {
        throw (Error) exceptionToThrow;
      }else if(exceptionToThrow instanceof RuntimeException) {
        throw (RuntimeException) exceptionToThrow;
      }else if(exceptionToThrow instanceof StandardException) {
        throw (StandardException) exceptionToThrow;
      }else {
        throw GemFireXDRuntimeException.
        newRuntimeException("Exception in index mainetance", exceptionToThrow);
      }
    }
  }
  
  private void undoDeleteIndexesForUnsuccessfulDestroy(RegionEntry entry,
      LocalRegion owner, EntryEventImpl event, boolean isPutDML,
      List<GemFireContainer> containers, final int undoIndex,
      GemFireTransaction tran, final ExecRow rowToInsert) {
    final RowLocation rl = entry instanceof RowLocation ? (RowLocation) entry
        : null;
    int bucketId;
    //final ExtraTableInfo tableInfo = getTableInfo(rl);
    try {      
      if (this.isPartitionedRegion) {
        // Cannot just type cast in BucketEntry because entry could also be
        // GfxdTXEntryState
        final BucketAdvisor ba = ((BucketRegion) owner).getBucketAdvisor();
        if (ba != null) {
          bucketId = ba.getProxyBucketRegion().getId();
        } else {
          bucketId = rl.getBucketID();
        }

      } else {
        bucketId = KeyInfo.UNKNOWN_BUCKET;
      }
      // re- insert in the previous indexes from which entry has been removed
      for (int j = undoIndex; j >= 0; --j) {
        GemFireContainer containerToInsert = containers.get(j);
        try {
          this.insertIntoIndex(tran, null, owner, event, false, false, rl,
              rowToInsert, null, null, bucketId, containerToInsert, null,
              isPutDML, Index.BOTH, false);
        } catch (Exception e) {
          LogWriter logger = Misc.getCacheLogWriterNoThrow();
          if(logger != null) {
            logger.error(
              "insert into index for unsucessful destroy could not be done for "
                  + "index =" + containerToInsert, e);
          }
        }
      }
    } catch (Exception e) {
      LogWriter logger = Misc.getCacheLogWriterNoThrow();
      if(logger != null) {
        logger.error("undo operation for unsucessful destroy could not be done",
          e);
      }
    }
  }
  
  private void refreshIndexKeyForContainers(Collection<GemFireContainer> containers,
      GemFireTransaction tran, EntryEventImpl event, RegionEntry entry, 
      ExecRow execRow, TXStateInterface tx, boolean isSucess)
 throws StandardException {

    Iterator<GemFireContainer> itr = containers.iterator();
    while (itr.hasNext()) {
      GemFireContainer indexContainer = itr.next();
      boolean isIndexTypeGlobal = !indexContainer.isLocalIndex();
      if (isIndexTypeGlobal) {
        continue;
      }
      // This index has not changed, now check if this index's IndexKey needs to
      // be refreshed
      // get an extracting index key
      ExtractingIndexKey indexKey = (ExtractingIndexKey) indexContainer
          .getLocalIndexKey(execRow, entry, false, true);
      if (tx == null) {
        refreshIndexKey(tran, event, entry, execRow, indexContainer, indexKey, isSucess);
      } else {
        ((GfxdTXEntryState) entry).updateUnaffectedIndexInfos(indexContainer,
            indexKey);
      }
    }

  }

  private void refreshIndexKey(GemFireTransaction tran, EntryEventImpl event,
      RegionEntry entry, ExecRow execRow, final GemFireContainer indexContainer,
      ExtractingIndexKey indexKey, boolean isSuccess)
      throws StandardException {
    
    Object cbArg = event.getRawCallbackArgument();
    final boolean isGIIEvent = Boolean.TRUE.equals(cbArg);
    Object oldValue = isGIIEvent ? execRow.getBaseByteSource():
      event.getOldValueAsOffHeapDeserializedOrRaw();
    if (GemFireXDUtils.TraceIndex) {
      GfxdIndexManager.traceIndex("SortedMap2Index::refreshIndexKey "
          + "Refreshing index key for  key=%s Old row=(%s) , " +
          "for index container=(%s), old value = %s",
          indexKey,execRow, indexContainer, oldValue);
    }
    SortedMap2IndexRefreshIndexKeyOperation.doMe(tran, indexContainer,
        indexKey, (RowLocation) entry, oldValue,event.isLoadedFromHDFS(), isSuccess);
  }

  public static void handleNotDeleted(final boolean posDup,
      final LocalRegion owner, final GemFireContainer indexContainer,
      final RowLocation rowLocation, final RegionEntry entry,
      final Object indexKey, Throwable cause) throws StandardException {
    // can happen during bucket GII (due to cleanup of tokens) or duplicate
    // event in HA retry
    final String dumpFlag = "Delete did not happen";
    // Can't take chances for offheap,
    //snap shot the key bytes of index key's 
    if (indexContainer.isLocalIndex() && indexKey != null) {
      @Retained
      @Released
      Object valueBytes = ((CompactCompositeIndexKey) indexKey)
          .getValueByteSource();
      ExtractingIndexKey eik = null;
      boolean keepRetained = false;
      if (valueBytes != null) {
        try {

          eik = (ExtractingIndexKey) indexContainer.getLocalIndexKey(
              valueBytes, entry, false, true);
          keepRetained = !SortedMap2IndexRefreshIndexKeyOperation
              .forceSnapShotIndexKey(indexContainer, eik, rowLocation,
                  valueBytes);
        } finally {
          if (!keepRetained) {
            if (eik != null) {
              eik.releaseValueByteSource(valueBytes);
            }
          } else {
            SanityManager.DEBUG_PRINT(dumpFlag, "Snapshot of index key also "
                + "failed. Retaining the value bytes = " + valueBytes
                + "; This will produce an orphan");
          }
        }
      }
    }
   
    if (indexContainer.isInitialized() && !posDup && owner.isInitialized()) {
      if (GemFireXDUtils.TracePersistIndex) {
        final String lineSep = SanityManager.lineSeparator;
        final StringBuilder sb = new StringBuilder().append(lineSep);
        final BucketRegion breg;
        final PartitionedRegion preg;
        final boolean isPrimary;
        if (owner instanceof BucketRegion) {
          breg = (BucketRegion)owner;
          preg = breg.getPartitionedRegion();
          isPrimary = breg.getBucketAdvisor().isPrimary();
        }
        else {
          breg = null;
          preg = null;
          isPrimary = false;
        }
        final GemFireContainer container = indexContainer.getBaseContainer();
        final RowFormatter rf = container.getCurrentRowFormatter();
        final String[] columnNames;
        if (rf != null) {
          columnNames = new String[rf.getNumColumns()];
          for (int i = 0; i < columnNames.length; ++i) {
            columnNames[i] = rf.getColumnDescriptor(i).getColumnName();
          }
        }
        else {
          columnNames = null;
        }

        // Asif: Added assertion error as a Delete for now should
        // always have an Entry in the index.
        final MemIndex conglom = (MemIndex)indexContainer.getConglomerate();
        conglom.dumpIndex(dumpFlag);

        // also dump the region contents
        if (breg != null) {
          sb.append("======== Bucket ID=")
              .append(preg.bucketStringForLogs(breg.getId())).append(" region=")
              .append(breg.getName()).append(" primary=").append(isPrimary)
              .append(" contents: ========");
        }
        else {
          sb.append("======= Region=").append(owner.getFullPath())
              .append(" contents: ========");
        }
        Iterator<?> iter = owner.getBestLocalIterator(true, 4.0, false);
        while (iter.hasNext()) {
          RegionEntry re = (RegionEntry)iter.next();
          @Retained
          @Released
          final ExecRow row = RegionEntryUtils.getRowWithoutFaultIn(container,
              owner, re, (ExtraTableInfo)re.getContainerInfo());
          try {
            sb.append(lineSep).append('\t');
            if (row != null) {
              final DataValueDescriptor[] dvds = row.getRowArray();
              for (int i = 0; i < dvds.length; ++i) {
                if (i > 0) {
                  sb.append(',');
                }
                sb.append((columnNames != null ? columnNames[i] : "col" + i))
                    .append(':').append(dvds[i]);
              }
            }
            else {
              sb.append("NULL");
            }
            sb.append('\t').append(re);
            if (sb.length() > (4 * 1024 * 1024)) {
              SanityManager.DEBUG_PRINT(dumpFlag, sb.toString());
              sb.setLength(0);
            }
          } finally {
            if (row != null) {
              row.releaseByteSource();
            }
          }
        }
        SanityManager.DEBUG_PRINT(dumpFlag, sb.toString());
      }
      String message = "Delete did not happen on "
          + "index: " + indexContainer.getQualifiedTableName()
          + " for indexKey: " + ArrayUtils.objectString(indexKey)
          + "; with RowLocation: " + rowLocation + "; indexedColumns: "
          + Arrays.toString(indexContainer.getBaseColumnPositions())
          + "; unique=" + indexContainer.isUniqueIndex();
      if(cause == null) {
      GemFireXDUtils.throwAssert(message);
      }else if(cause instanceof StandardException) {
        throw (StandardException) cause;
      }else if(cause instanceof RuntimeException){
        throw (RuntimeException)cause;
      }else {
        throw GemFireXDRuntimeException.newRuntimeException(message, cause);
      }
    }
    else {
      if (SanityManager.isFineEnabled | GemFireXDUtils.TraceIndex) {
        traceIndex("GfxdIndexManager#deleteFromIndexes: ignored delete "
            + "failure in HA or GII key=%s value=(%s) from %s", indexKey,
            rowLocation, indexContainer);
      }
    }
  }

  /**
   * Given an array of indexed columns and a {@link FormatableBitSet} figure out
   * if the index is changed or not.
   * 
   * @return true if indexed columns have not changed else false.
   */
  public boolean unchangedIndexKey(int[] indexedColumns,
      FormatableBitSet changedColumns) {
    final int numSetBits = changedColumns.getLength();
    for (int indexedColumn : indexedColumns) {
      if (indexedColumn <= numSetBits
          && changedColumns.isSet(indexedColumn - 1)) {
        return false;
      }
    }
    return true;
  }

  private void doDelete(GemFireTransaction tran, final TXStateInterface tx,
      LocalRegion owner, EntryEventImpl event, boolean skipDistribution,
      RegionEntry entry, ExecRow execRow,
      List<GemFireContainer> localIndexContainers, boolean isPutDML,
      boolean success)
      throws StandardException {
    if (GemFireXDUtils.TraceIndex) {
      traceIndex("GfxdIndexManager#doDelete: invoked for container %s for "
          + "entry %s", this.container, entry);
    }
    deleteFromIndexes(tran, tx, owner, event, skipDistribution, entry,
        localIndexContainers, execRow, null, false, isPutDML, success);
    if (SanityManager.DEBUG) {
      checkIndexListChange(localIndexContainers, "delete");
    }
  }

  private void checkIndexListChange(
      final List<GemFireContainer> localIndexContainers, String operation) {
    final List<GemFireContainer> currentIndexContainers =
        this.indexContainers;
    if (currentIndexContainers != localIndexContainers) {
      // now really check the index list since it may happen that index list is
      // refreshed due to invalidation as part of cleanup handling of some
      // exceptions etc. but there has been no add/drop of indexes as such
      // (see bug #41651)
      boolean comparisonResult = true;
      if (currentIndexContainers.size() != localIndexContainers.size()) {
        comparisonResult = false;
      }
      else {
        final int numContainers = currentIndexContainers.size();
        for (int index = 0; index < numContainers; index++) {
          GemFireContainer currentContainer = currentIndexContainers.get(index);
          boolean foundIndex = false;
          final int numContainers2 = localIndexContainers.size();
          for (int index2 = 0; index2 < numContainers2; index2++) {
            GemFireContainer container = localIndexContainers.get(index2);
            if (currentContainer.getId().equals(container.getId())) {
              foundIndex = true;
              break;
            }
          }
          if (!foundIndex) {
            comparisonResult = false;
            break;
          }
        }
      }
      if (!comparisonResult) {
        GemFireXDUtils.throwAssert("unexpected change in indexes during "
            + operation + "; original list: "
            + getObjectString(localIndexContainers) + "; new list: "
            + getObjectString(currentIndexContainers));
      }
    }
  }

  /** Methods of the Dependent interface */

  @Override
  public boolean isValid() {
    return true;
  }

  @Override
  public void makeInvalid(int action, LanguageConnectionContext lcc)
      throws StandardException {

    switch (action) {
      case DependencyManager.CREATE_INDEX:
      case DependencyManager.DROP_INDEX:
      case DependencyManager.ALTER_TABLE:
        // also locking here for DDL executions during replay
        invalidateFor(-1, lcc);
        break;
      default:
        return;
    }
  }

  @Override
  public void prepareToInvalidate(Provider p, int action,
      LanguageConnectionContext lcc) throws StandardException {
  }

  /**
   * Invalidate the cached conglomerate descriptors in the GfxdIndexManager of
   * given {@link #td}. This is invoked during drop index, since that does not
   * invalidate the dependencies of the table rather only of that index.
   */
  public void invalidateFor(LanguageConnectionContext lcc)
      throws StandardException {
    invalidateFor(-1, lcc);
  }

  /**
   * Invalidate the cached conglomerate descriptors in the GfxdIndexManager of
   * given {@link #td}. This is invoked during drop index, since that does not
   * invalidate the dependencies of the table rather only of that index.
   */
  public void invalidateFor(int dropColumnPos, LanguageConnectionContext lcc)
      throws StandardException {
    final GemFireTransaction tc = (GemFireTransaction)lcc
        .getTransactionExecute();
    // if index refresh has already been added then do nothing except
    // for setting column position for drop if required
    List<?> commitOps = tc.getLogAndDoListForCommit();
    if (commitOps != null) {
      for (Object op : commitOps) {
        if (op instanceof MemIndexRefresh) {
          if (dropColumnPos > 0) {
            ((MemIndexRefresh)op).setDropColumnPosition(dropColumnPos);
          }
          return;
        }
      }
    }

    // first lock for index GII so incoming updates will be blocked
    // while the indexes are initialized
    final MemIndexRefresh refreshOp = new MemIndexRefresh(this, true, false);
    if (dropColumnPos > 0) {
      refreshOp.setDropColumnPosition(dropColumnPos);
    }
    refreshOp.lockGII(tc);
    lockForIndexGII(true, tc);
    tc.logAndDo(refreshOp);
    // take the write lock to block any GIIs and release it at commit time
    // when the index descriptor list has been modified
  }

  public static void setIndexInitialized(ConglomerateDescriptor cd)
      throws StandardException {
    final GemFireStore memStore = Misc.getMemStore();
    final GemFireContainer indexContainer = memStore.getContainer(ContainerKey
        .valueOf(ContainerHandle.TABLE_SEGMENT, cd.getConglomerateNumber()));
    if (GemFireXDUtils.TraceConglomUpdate) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
          "GfxdIndexManager#setIndexInitialized with indexContainer: "
              + indexContainer);
    }
    if (indexContainer != null) {
      indexContainer.setIndexInitialized();
    }
  }

  public static GfxdIndexManager getGfxdIndexManager(TableDescriptor td,
      LanguageConnectionContext lcc) {
    // get the region for the table descriptor
    final LocalRegion region = (LocalRegion)Misc.getRegionForTableByPath(Misc
        .getFullTableName(td, lcc), false);
    if (region != null) {
      return (GfxdIndexManager)region.getIndexUpdater();
    }
    return null;
  }

  final TransactionController getLockOwner() {
    // use current GemFireTransaction, if any, as the owner
    LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    if (lcc != null) {
      return lcc.getTransactionExecute();
    }
    return null;
  }

  /**
   * @see IndexUpdater#lockForGII()
   */
  @Override
  public void lockForGII() throws TimeoutException {
    lockForGII(false, getLockOwner());
  }

  public final boolean lockForGII(boolean forWrite,
      final TransactionController tc) throws TimeoutException {
    // for bucket recovery etc. keep a large lock timeout
    long timeout = GfxdLockSet.MAX_LOCKWAIT_VAL;
    if (timeout < GfxdConstants.MAX_LOCKWAIT_DEFAULT) {
      timeout = GfxdConstants.MAX_LOCKWAIT_DEFAULT;
    }
    final GfxdLocalLockService lockService = Misc.getMemStoreBooting()
        .getDDLLockService().getLocalLockService();
    final boolean success;
    if (forWrite) {
      success = lockService.writeLock(this, tc, timeout, -1);
    }
    else {
      success = lockService.readLock(this, tc, timeout);
    }
    if (success) {
      return true;
    }
    else {
      throw lockService.getLockTimeoutRuntimeException(this, tc, true);
    }
  }

  /**
   * @see IndexUpdater#unlockForGII()
   */
  @Override
  public void unlockForGII() throws LockNotHeldException {
    unlockForGII(false, getLockOwner());
  }

  /**
   * @see IndexUpdater#unlockForGII(boolean)
   */
  @Override
  public void unlockForGII(boolean forWrite) throws LockNotHeldException {
    unlockForGII(forWrite, getLockOwner());
  }
  
  public final void unlockForGII(boolean forWrite,
      final TransactionController tc) throws LockNotHeldException {
    final GfxdLocalLockService lockService = Misc.getMemStoreBooting()
        .getDDLLockService().getLocalLockService();
    if (forWrite) {
      lockService.writeUnlock(this, tc);
    }
    else {
      lockService.readUnlock(this);
    }
  }

  /**
   * @see IndexUpdater#lockForIndexGII()
   */
  @Override
  public boolean lockForIndexGII() throws TimeoutException {
    final boolean takeIndexGIILock = Misc.initialDDLReplayInProgress();
    if (takeIndexGIILock) {
      final TransactionController tc = getLockOwner();
      lockForGII(false, tc);
      lockForIndexGII(false, tc);
      return true;
    }
    else {
      return false;
    }
  }

  /**
   * @see IndexUpdater#unlockForIndexGII()
   */
  @Override
  public void unlockForIndexGII() throws LockNotHeldException {
    final TransactionController tc = getLockOwner();
    unlockForIndexGII(false, tc);
    unlockForGII(false, tc);
  }

  public void lockForIndexGII(boolean forWrite, Object owner) {
    if (forWrite) {
      this.lockForIndexGII.attemptWriteLock(-1, owner);
    }
    else {
      this.lockForIndexGII.attemptReadLock(-1, owner);
    }
  }

  public final void unlockForIndexGII(boolean forWrite, Object owner) {
    if (forWrite) {
      this.lockForIndexGII.releaseWriteLock(owner);
    }
    else {
      this.lockForIndexGII.releaseReadLock();
    }
  }

  public final boolean hasRemoteOperations(final Operation op) {
    // if we need to do global index lookup or maintenance, then don't use the
    // serial executor since the P2P reader thread will be blocked for
    // significant amount of time otherwise
    if (op.isCreate() || op.isUpdate()) {
      return this.hasFkConstriant || this.hasGlobalIndexOrCallbacks
          || this.numTriggers.get() > 0;
    }
    else if (op.isDestroy()) {
      return this.hasGlobalIndexOrCallbacks || this.numTriggers.get() > 0;
    }
    return false;
  }

  public final boolean avoidSerialExecutor(final Operation op) {
    if (op != null) {
      // for putAll always avoid SERIAL_EXECUTOR
      if (op.isPutAll()) {
        return true;
      }
      return hasRemoteOperations(op);
    }
    return false;
  }

  @Override
  public String getClassType() {
    return null;
  }

  @Override
  public DependableFinder getDependableFinder() {
    return null;
  }

  @Override
  public UUID getObjectID() {
    return this.oid;
  }

  @Override
  public String getObjectName() {
    return null;
  }

  public GemFireContainer getContainer() {
    return this.container;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof GfxdIndexManager) {
      return this.name.equals(((GfxdIndexManager)other).name);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return this.name.hashCode();
  }

  @Override
  public boolean isDescriptorPersistent() {
    return false;
  }

  /**
   * Check foreign key constraints for user defined tables.
   * 
   * @param event
   *          from underlying region.
   * @param rl
   *          from underlying region doing the create.
   */
  private void checkForeignKeyConstraint(EntryEventImpl event,
      GemFireTransaction tc, final TXStateInterface tx, ExecRow row,
      FormatableBitSet changedCols, final RowLocation rl)
      throws StandardException {

    final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();
    if (observer != null) {
      observer.beforeForeignKeyConstraintCheckAtRegionLevel();
    }

    final ForeignKeyInformation[] localFks = this.fks;
    if (localFks == null || localFks.length == 0) {
      return;
    }
    TXManagerImpl txMgr = null;
    TXStateInterface proxy = null;
    try {
      if (tx != null) {
        proxy = tx.getProxy();
        if (proxy != tx) {
          if (GemFireXDUtils.TraceTran) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
                "GfxdIndexManager#checkForeignKeyConstraint: "
                    + " setting proxy transaction: " + proxy
                    + " in current thread replacing " + tx);
          }
          txMgr = tc.getTransactionManager();
          txMgr.masqueradeAs(proxy);
          tc.setActiveTXState(proxy, false);
        }
      }
      ForeignKeyInformation fk;
      ForeignKeyConstraintDescriptor fkcd;
      final Object callbackArg = event.getCallbackArgument();
      for (int index = 0; index < localFks.length; index++) {
        fk = localFks[index];
        fkcd = fk.fkcd;
        if (changedCols != null) {
          // this means this is an update operation.
          // for all insert we have to check foreign key.
          if (unchangedIndexKey(fkcd.getReferencedColumns(), changedCols)) {
            continue;
          }
        }

        if (this.logFineEnabled) {
          traceIndex("Found the following foreign key constraint to be "
              + "evaluated: %s, and the changed columns: %s", fkcd, changedCols);
        }
        try {
          if (fkcd.getReferencedConstraint().getConstraintType() ==
              DataDictionary.UNIQUE_CONSTRAINT) {
            checkFkOnUniqueKeyColumns(event, fk.refContainer, fk.refIndex,
                fkcd, fk.refColsPartColsMap, row, tc, proxy, callbackArg);
          }
          else {
            checkFkOnPrimaryKeyColumns(event, fk.refContainer, fkcd, row,
                rl, proxy, tc, callbackArg);
          }
        } catch (Throwable t) {
          Error err;
          if (t instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)t)) {
            SystemFailure.initiateFailure(err);
            // If this ever returns, rethrow the error. We're poisoned
            // now, so don't let this thread continue.
            throw err;
          }
          // Asif: If the exception is thrown due to cache close, we should throw it
          // as is as other wise
          // PartitionMessage$PartitionResponse.waitForCacheException will not do
          // the retry (#43359).
         /* Operation op = event.getOperation();
         if( /op.isCreate() || op.isDiskRecovery() || op.isUpdate()) {
            row.releaseByteSource();
             
           }*/
          handleException(t, "Foreign key constraint violation",
              GemFireTransaction.getLanguageConnectionContext(tc), null, null);
         
         
        }
      }
    } finally {
      if (txMgr != null) {
        if (GemFireXDUtils.TraceTran) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
              "GfxdIndexManager#checkForeignKeyConstraint:: "
                  + " switching back to: " + tx
                  + " in current thread instead of: " + proxy);
        }
        txMgr.masqueradeAs(tx);
        tc.setActiveTXState(tx, false);
      }
    }
  }

  /**
   * Checks constraint for given FK on unique key Constraint Descriptor.
   */
  private void checkFkOnUniqueKeyColumns(EntryEventImpl event,
      GemFireContainer refContainer, GemFireContainer refIndex,
      ForeignKeyConstraintDescriptor fkCD, int[] refColsPartColsMap,
      ExecRow row, GemFireTransaction tc, final TXStateInterface tx,
      final Object callbackArg) throws StandardException {
    boolean localIndexLookup = false;
    final LocalRegion refRegion = refContainer.getRegion();
    final boolean isOnPR = refRegion.getDataPolicy().withPartitioning();
    
    boolean bulkFkChecksDone = false;
    if ((callbackArg != null) && callbackArg instanceof 
        GfxdCallbackArgument) {
      bulkFkChecksDone = ((GfxdCallbackArgument) callbackArg)
          .isBulkFkChecksEnabled();
      // if bulk fk checks are enabled these checks are already done
      // GemFireContainer.insertMultipleRrows
      if (isOnPR && bulkFkChecksDone) {
        if (this.logFineEnabled) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
              "GfxdIndexManager#checkFkOnUniqueKeyColumns: skip "
                  + "FK check as bulk checks are done");
        }
        return;
      }
    }

    GfxdFunctionMessage<Object> msg = null;
    Throwable cause = null;
    boolean containsKey;
    GfxdPartitionResolver refResolver = null;
    try {
      final ReferencedKeyConstraintDescriptor refCD = fkCD
          .getReferencedConstraint();
      int[] colPositionsInRefTable = refCD.getReferencedColumns();
      int[] colPositionsInThisTable = fkCD.getKeyColumns();
      if (isOnPR) {
        refResolver = (GfxdPartitionResolver)refRegion
            .getPartitionAttributes().getPartitionResolver();
        localIndexLookup = refIndex.isLocalIndex();
      }
      else {
        localIndexLookup = true;
      }
      
      Object indexKey = null;
      DataValueDescriptor[] keys = null;
      DataValueDescriptor dvd = null;

      final int len = colPositionsInThisTable.length;
      if (len > 1) {
        keys = new DataValueDescriptor[len];
        for (int i = 0; i < len; i++) {
          dvd = row.getColumn(colPositionsInThisTable[i]);
          // skip FK check like other DBs if any column is null (#41168)
          if (dvd == null || dvd.isNull()) {
            if (this.logFineEnabled) {
              traceIndex("GfxdIndexManager#checkFkOnUniqueKeyColumns: skip "
                  + "FK check for null column %s being inserted into %s, "
                  + "in row %s", colPositionsInThisTable[i], this.container,
                  row);
            }
            return;
          }
          keys[i] = dvd;
        }
        indexKey = keys;
      }
      else {
        dvd = row.getColumn(colPositionsInThisTable[0]);
        // skip FK check like other DBs if any column is null (#41168)
        if (dvd == null || dvd.isNull()) {
          if (this.logFineEnabled) {
            traceIndex("GfxdIndexManager#checkFkOnUniqueKeyColumns: skip "
                + "FK check for null column %s being inserted into %s, "
                + "in row %s", colPositionsInThisTable[0], this.container, row);
          }
          return;
        }
        indexKey = dvd;
      }

      // check for the case of self-referential constraints when the row being
      // inserted itself satisfies the constraint (#43159)
      if (refRegion == this.container.getRegion()) {
        if (len > 1) {
          boolean selfSatisfied = true;
          for (int i = 0; i < len; i++) {
            if (compareKeyToBytes(row, keys[i], colPositionsInRefTable[i],
                colPositionsInThisTable[i])) {
              selfSatisfied = false;
              break;
            }
          }
          if (selfSatisfied) {
            return;
          }
        }
        else {
          if (dvd.equals(row.getColumn(colPositionsInRefTable[0]))) {
            return;
          }
        }
      }

      // Neeraj: Now there are three cases
      // 1. if the ref table is replicate then go to any one data store
      //    for that table and do an index look-up
      // 2. if the ref table is a partition table and localIndexLookup is true
      //    then go to all the hosting nodes and do local index look-up
      // 3. if the ref table is a partition table and local index look-up is false
      //    then it means we need to look into the global index region
      //    and we can use the contains key message.
      if (!isOnPR) {
        msg = new ContainsUniqueKeyExecutorMessage(refRegion,
            colPositionsInRefTable, indexKey, null, tx, tc
                .getLanguageConnectionContext());
        Object result = msg.executeFunction();
        assert result instanceof Boolean: "unexpected result "
            + result + (result != null ? " with type "
                + result.getClass().getName() : "");
        containsKey = (Boolean)result;
      }
      else if (localIndexLookup) {
        Object robj = null;
        if (len == 1) {
          robj = refResolver.getRoutingKeyForColumn(dvd);
        }
        else if (refColsPartColsMap == null) {
          robj = refResolver.getRoutingObjectsForPartitioningColumns(keys);
        }
        else {
          // need to create a separate key for getting routing object
          final int numPartCols = refResolver.getPartitioningColumnsCount();
          DataValueDescriptor[] rkeys = new DataValueDescriptor[numPartCols];
          for (int index = 0; index < numPartCols; index++) {
            rkeys[index] = keys[refColsPartColsMap[index]];
          }
          robj = refResolver
              .getRoutingObjectsForPartitioningColumns(rkeys);
        }
        msg = new ContainsUniqueKeyExecutorMessage(refRegion,
            colPositionsInRefTable, indexKey, robj, tx, tc
                .getLanguageConnectionContext());
        Object result = msg.executeFunction();
        assert result instanceof Boolean: "unexpected type of result: "
            + (result != null ? result.getClass() : "(null)");
        containsKey = ((Boolean)result).booleanValue();
      }
      else {
        LocalRegion globalIndexRegion = refIndex.getRegion();

        if (globalIndexRegion == null) {
          Assert.fail("unexpected null global index region for unique index "
              + "on columns " + refCD.getColumnDescriptors()
              + " for parent table " + refCD.getTableDescriptor());
        }
        if (indexKey instanceof DataValueDescriptor[]) {
          indexKey = new CompositeRegionKey((DataValueDescriptor[])indexKey);
        }
        Object routingObject = Integer.valueOf(PartitionedRegionHelper
            .getHashKey((PartitionedRegion)globalIndexRegion, indexKey));
        msg = new ContainsKeyExecutorMessage(globalIndexRegion, indexKey, null,
            routingObject, tx, tc.getLanguageConnectionContext());
        final Object result = msg.executeFunction(false,
            event.isPossibleDuplicate(), null, false);
        assert result != null: "Expected one result for targeted function "
            + "execution of GfxdIndexManager#ContainsKeyFunction";
        containsKey = ((Boolean)result).booleanValue();
      }
    } catch (SQLException sqle) {
      throw Misc.wrapRemoteSQLException(sqle, sqle, null);
    } catch (RuntimeException re) {
      containsKey = false;
      cause = handleFKException(re);
    }
    if (!containsKey) {
      throw StandardException.newException(SQLState.LANG_FK_VIOLATION,
          cause, fkCD.getConstraintName(), td.getName(), "INSERT",
          row == null ? "(currentRow is null)" : RowUtil.toString(row,
              fkCD.getKeyColumns()));
    }
  }

  /**
   * Checks constraint for given FK on primary key Constraint Descriptor.
   */
  private void checkFkOnPrimaryKeyColumns(EntryEventImpl event,
      final GemFireContainer refContainer, ForeignKeyConstraintDescriptor fkCd,
      ExecRow row, final RowLocation rl, final TXStateInterface tx,
      final GemFireTransaction tc, final Object callbackArg)
      throws StandardException {
    LocalRegion refRegion = refContainer.getRegion();
    final PartitionAttributes<?, ?> pattrs = refRegion
        .getPartitionAttributes();
    final DataPolicy refDP = refRegion.getDataPolicy();
    final int[] refCols = fkCd.getReferencedColumns();
    Object gfKey = null;
    final boolean isOnReplicate = refDP.withReplication();

    final GfxdPartitionResolver refResolver;
    final boolean requiresGlobalIndex;
    if (pattrs != null) {
      refResolver = (GfxdPartitionResolver)pattrs
          .getPartitionResolver();
      requiresGlobalIndex = refResolver.requiresGlobalIndex();
    }
    else {
      refResolver = null;
      requiresGlobalIndex = false;
    }

    boolean bulkFkChecksDone = false;
    if ((callbackArg != null)
        && callbackArg instanceof GfxdCallbackArgument) {
      bulkFkChecksDone = ((GfxdCallbackArgument) callbackArg)
          .isBulkFkChecksEnabled();
      
      // if bulk fk checks are enabled these checks are already done
      // GemFireContainer.insertMultipleRrows
      if (refResolver != null && !isOnReplicate && bulkFkChecksDone) {
        if (this.logFineEnabled) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
              "GfxdIndexManager#checkFkOnPrimaryKeyColumns: skip "
                  + "FK check as bulk checks are done");
        }
        return;
      }
    }

    // check for the case of self-referential constraints when the row being
    // inserted itself satisfies the constraint (#43159)
    if (refRegion == this.container.getRegion()) {
      final ExtraTableInfo tableInfo = getTableInfo(rl);
      gfKey = getRefRegionKey(refContainer, refCols, fkCd, row, tableInfo);
      if (gfKey != null) {
        if (refDP.withStorage()
            && (pattrs == null || pattrs.getLocalMaxMemory() != 0)) {
          if (rl.getKey().equals(gfKey)) {
            return;
          }
        }
        else {
          final Object refKey = GemFireXDUtils.convertIntoGemFireRegionKey(row,
              this.container, tableInfo, null);
          if (refKey != null && refKey.equals(gfKey)) {
            return;
          }
        }
      }
      else {
        // indicates that no reference key check is required
        return;
      }
    }

    final int size = refCols.length;
    if (size == 0) {
      GemFireXDUtils.throwAssert("foreign key constraint should not have "
          + "zero columns: " + fkCd + "; for container: " + this.container);
    }
    // for the special case of lookup in global index (for PK != partitioning)
    // in transactional context, use containsKey on the global index region
    // itself so it will take the appropriate locks too;
    // for non TX case, the routing object calculation itself will perform a
    // global index lookup (we check to see that routing object non-null is
    //   enough for this case)
    if (requiresGlobalIndex && tx != null) {
      if (size == 1) {
        final DataValueDescriptor key = row.getColumn(refCols[0]);
        // skip FK check like other DBs if any column is null (#41168)
        if (key == null || key.isNull()) {
          if (this.logFineEnabled) {
            traceIndex("GfxdIndexManager#checkFkOnPrimaryKeyColumns: skip "
                + "FK check for null column %s being inserted into %s, "
                + "in row %s", refCols[0], this.container, row);
          }
          return;
        }
        gfKey = key;
      }
      else {
        final DataValueDescriptor[] primaryKey = new DataValueDescriptor[size];
        DataValueDescriptor dvd;
        for (int index = 0; index < size; index++) {
          dvd = row.getColumn(refCols[index]);
          // skip FK check like other DBs if any column is null (#41168)
          if (dvd == null || dvd.isNull()) {
            if (this.logFineEnabled) {
              traceIndex("GfxdIndexManager#checkFkOnPrimaryKeyColumns: skip "
                  + "FK check for null column %s being inserted into %s, "
                  + "in row %s", refCols[index], this.container, row);
            }
            return;
          }
          primaryKey[index] = dvd;
        }
        gfKey = new CompositeRegionKey(primaryKey);
      }
    }
    else if (gfKey == null) {
      gfKey = getRefRegionKey(refContainer, refCols, fkCd, row,
          getTableInfo(rl));
      if (gfKey == null) {
        // indicates that no reference key check is required
        return;
      }
    }

    boolean containsKey;
    Throwable cause = null;
    ContainsKeyExecutorMessage msg = null;
    // [sumedh] using function execution instead of containsKey() call;
    // this approach has three advantages:
    // 1. reduces chances of distributed deadlock since the function
    // execution will not block region threads (also see bug #40208)
    // 2. avoid double global index lookup for cases where PK != partitioning
    // columns; this is because a containsKey() will invoke resolver both
    // here and on the target node and there is no way to explicitly set
    // the callback argument to routing object either for containsKey()
    // 3. is likely more efficient for the EMPTY region case which has to
    // invoke get() due to non-availability of distributed containsKey()
    // for EMPTY regions as of now
    try {
      if (isOnReplicate) {
        if (tx != null) {
          containsKey = refRegion.txContainsKey(gfKey, null, tx, true);
        }
        else {
          containsKey = refRegion.containsKey(gfKey);
        }
      }
      else {
        Object routingObject = null;
        if (refResolver != null) {
          if (tx == null) {
            routingObject = refResolver.getRoutingObject(gfKey, null,
                refRegion);
            if (requiresGlobalIndex) {
              if (routingObject != null) {
                // successful global index lookup means key is present; no
                // need for a further containsKey() on the base table
                return;
              }
              else {
                throw new EntryNotFoundException(
                    "entry not found in local index for key: " + gfKey);
              }
            }
          }
          else {
            if (requiresGlobalIndex) {
              // use ContainsKey message on global index region itself
              refRegion = refResolver.getGlobalIndexContainer()
                  .getRegion();
              routingObject = Integer.valueOf(PartitionedRegionHelper
                  .getHashKey((PartitionedRegion)refRegion, gfKey));
            }
            else {
              routingObject = refResolver.getRoutingObject(gfKey,
                  null, refRegion);
            }
          }
        }
        if (this.logFineEnabled) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
              "GfxdIndexmanager#checkFk: refregion is: " + refRegion
                .getFullPath() + " key=" + gfKey + ", routingObject="
                + routingObject + ", partitionresolver is: " + (refRegion
                    .getPartitionAttributes() != null ? refRegion
                    .getPartitionAttributes().getPartitionResolver() : null));
        }
        msg = new ContainsKeyExecutorMessage(refRegion, gfKey, null,
            routingObject, tx, tc.getLanguageConnectionContext());
        final Object result = msg.executeFunction(false,
            event.isPossibleDuplicate(), null, false);
        assert result != null: "Expected one result for targeted function "
            + "execution of GfxdIndexManager#ContainsKeyFunction";
        containsKey = ((Boolean)result).booleanValue();
      }
    } catch (SQLException sqle) {
      throw Misc.wrapRemoteSQLException(sqle, sqle,
          msg != null ? msg.getTarget() : null);
    } catch (RuntimeException re) {
      containsKey = false;
      cause = handleFKException(re);
    }
    if (!containsKey) {
      throw StandardException.newException(
          SQLState.LANG_FK_VIOLATION, cause, fkCd.getConstraintName(), td
              .getName(), "INSERT", row == null ? "(currentRow is null)"
              : RowUtil.toString(row, refCols));
    }
  }

  public boolean compareKeyToBytes(ExecRow row, DataValueDescriptor lhsDVD,
      int lhsColPos, int rhsColPos) throws StandardException {
    if (this.container.isByteArrayStore()
        && row instanceof AbstractCompactExecRow) {
      final AbstractCompactExecRow cRow = (AbstractCompactExecRow)row;
      final RowFormatter rf = cRow.getRowFormatter();
      if (!this.container.isOffHeap()) {
        final byte[] rhsBytes = cRow.getRowBytes(rhsColPos);
        if (lhsDVD == null) {
          final byte[] lhsBytes = cRow.getRowBytes(lhsColPos);
          return DataTypeUtilities.compare(lhsBytes, rhsBytes,
              rf.getOffsetAndWidth(lhsColPos, lhsBytes),
              rf.getOffsetAndWidth(rhsColPos, rhsBytes), false, true,
              rf.getColumnDescriptor(rhsColPos - 1)) == 0;
        }
        else {
          return DataTypeUtilities.compare(lhsDVD, rhsBytes,
              rf.getOffsetAndWidth(rhsColPos, rhsBytes), false, true,
              rf.getColumnDescriptor(rhsColPos - 1)) == 0;
        }
      }
      else {
        final Object rhs = cRow.getByteSource(rhsColPos);
        final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
        if (lhsDVD == null) {
          final Object lhs = cRow.getByteSource(lhsColPos);
          byte[] lhsBytes = null, rhsBytes = null;
          OffHeapByteSource lhsBS = null, rhsBS = null;
          long lhsAddr = 0, rhsAddr = 0;
          final long lhsOffsetAndWidth, rhsOffsetAndWidth;
          if (lhs == null || lhs.getClass() == byte[].class) {
            lhsBytes = (byte[])lhs;
            lhsOffsetAndWidth = rf.getOffsetAndWidth(lhsColPos, lhsBytes);
          }
          else {
            lhsBS = (OffHeapByteSource)lhs;
            final int bytesLen = lhsBS.getLength();
            lhsAddr = lhsBS.getUnsafeAddress(0, bytesLen);
            lhsOffsetAndWidth = rf.getOffsetAndWidth(lhsColPos, unsafe,
                lhsAddr, bytesLen);
          }
          if (rhs == null || rhs.getClass() == byte[].class) {
            rhsBytes = (byte[])rhs;
            rhsOffsetAndWidth = rf.getOffsetAndWidth(rhsColPos, rhsBytes);
          }
          else {
            rhsBS = (OffHeapByteSource)rhs;
            final int bytesLen = rhsBS.getLength();
            rhsAddr = rhsBS.getUnsafeAddress(0, bytesLen);
            rhsOffsetAndWidth = rf.getOffsetAndWidth(rhsColPos, unsafe,
                rhsAddr, bytesLen);
          }
          return DataTypeUtilities.compare(unsafe, lhsBytes, lhsAddr, lhsBS,
              rhsBytes, rhsAddr, rhsBS, lhsOffsetAndWidth, rhsOffsetAndWidth,
              false, true, rf.getColumnDescriptor(rhsColPos - 1)) == 0;
        }
        else {
          byte[] rhsBytes = null;
          OffHeapByteSource rhsBS = null;
          long rhsAddr = 0;
          final long rhsOffsetAndWidth;
          if (rhs == null || rhs.getClass() ==  byte[].class) {
            rhsBytes = (byte[])rhs;
            rhsOffsetAndWidth = rf.getOffsetAndWidth(rhsColPos, rhsBytes);
          }
          else {
            rhsBS = (OffHeapByteSource)rhs;
            final int bytesLen = rhsBS.getLength();
            rhsAddr = rhsBS.getUnsafeAddress(0, bytesLen);
            rhsOffsetAndWidth = rf.getOffsetAndWidth(rhsColPos, unsafe,
                rhsAddr, bytesLen);
          }
          return DataTypeUtilities.compare(unsafe, lhsDVD, rhsBytes, rhsAddr,
              rhsBS, rhsOffsetAndWidth, false, true,
              rf.getColumnDescriptor(rhsColPos - 1)) == 0;
        }
      }
    }
    else {
      return lhsDVD.equals(row.getColumn(rhsColPos));
    }
  }

  public Object getRefRegionKey(final GemFireContainer parentContainer,
      final int[] refCols, final ForeignKeyConstraintDescriptor fkCd,
      final ExecRow row, final ExtraTableInfo tableInfo)
      throws StandardException {

    Object gfKey = null;
    
    Object keyRowBytes = null;
    if(row instanceof AbstractCompactExecRow) {
      keyRowBytes = ((AbstractCompactExecRow)row).getByteSource();
    }
    else {
      final Object rowValue  = row.getRawRowValue(false);
      final int size = refCols.length;
      final DataValueDescriptor[] dvds = (DataValueDescriptor[])rowValue;
      DataValueDescriptor dvd;
      if (size == 1) {
        dvd = dvds[refCols[0] - 1];
        // skip FK check like other DBs if any column is null (#41168)
        if (dvd == null || dvd.isNull()) {
          if (this.logFineEnabled) {
            traceIndex("GfxdIndexManager#checkFk: skipping "
                + "FK check for null column %s being inserted into %s, "
                + "in row %s", refCols[0], this.container, row);
          }
          return null;
        }
        gfKey = GemFireXDUtils.convertIntoGemfireRegionKey(dvd, parentContainer,
            false);
      }
      else {
        final DataValueDescriptor[] primaryKey = new DataValueDescriptor[size];
        for (int index = 0; index < size; index++) {
          dvd = dvds[refCols[index] - 1];
          // skip FK check like other DBs if any column is null (#41168)
          if (dvd == null || dvd.isNull()) {
            if (this.logFineEnabled) {
              traceIndex("GfxdIndexManager#checkFk: skipping "
                  + "FK check for null column %s being inserted into %s, "
                  + "in row %s", refCols[index], this.container, row);
            }
            return null;
          }
          primaryKey[index] = dvd;
        }
        gfKey = GemFireXDUtils.convertIntoGemfireRegionKey(primaryKey,
            parentContainer, false);
      }
    }
    if (keyRowBytes != null) {
      final ExtraTableInfo parentTableInfo = parentContainer
          .getExtraTableInfo();
      final int[] parentTableRefCols = fkCd.getReferencedConstraint()
          .getReferencedColumns();
      final byte[] keyBytes;
      if (keyRowBytes.getClass() == byte[].class) {
        final byte[] bytes = (byte[])keyRowBytes;
        keyBytes = tableInfo.getRowFormatter(bytes)
            .extractReferencedKeysForPKOrNullIfNull(bytes, refCols,
                parentTableInfo.getPrimaryKeyFormatter(), parentTableRefCols,
                parentTableInfo.getRowFormatter(),
                parentTableInfo.getPrimaryKeyColumns());
      }
      else {
        final OffHeapByteSource ohbytes = (OffHeapByteSource)keyRowBytes;
        keyBytes = tableInfo.getRowFormatter(ohbytes)
            .extractReferencedKeysForPKOrNullIfNull(ohbytes, refCols,
                parentTableInfo.getPrimaryKeyFormatter(), parentTableRefCols,
                parentTableInfo.getRowFormatter(),
                parentTableInfo.getPrimaryKeyColumns());
      }
      // bug fix #41168 moved inside extractPrimaryKeyOrNullIfNull method.
      if (keyBytes == null) {
        if (this.logFineEnabled) {
          traceIndex("GfxdIndexManager#checkFk: skipping "
              + "FK check for null column being inserted into %s, "
              + "in row %s", this.container, row);
        }
        return null;
      }
      gfKey = new CompactCompositeRegionKey(parentTableInfo, keyBytes);
    }
    return gfKey;
  }

  public Exception handleFKException(final RuntimeException re)
      throws StandardException {
    final GemFireXDUtils.TXAbortState ret = GemFireXDUtils.isTXAbort(re);
    if (ret == GemFireXDUtils.TXAbortState.CONFLICT) {
      throw StandardException
          .newException(SQLState.GFXD_OPERATION_CONFLICT, re, re.getMessage());
    }
    else if (ret == GemFireXDUtils.TXAbortState.TIMEOUT) {
      throw StandardException.newException(SQLState.LOCK_TIMEOUT, re);
    }
    else if (ret == GemFireXDUtils.TXAbortState.OTHER) {
      throw StandardException.newException(SQLState.GFXD_TRANSACTION_READ_ONLY,
          re, re.getLocalizedMessage());
    }
    else if (re instanceof EntryNotFoundException
        || re instanceof EmptyRegionFunctionException) {
      return re;
    }
    else if (re instanceof GemFireException) {
      return Misc.processGemFireException((GemFireException)re, re,
          "lookup of foreign key reference", true);
    }
    else {
      throw re;
    }
  }

  private void handleException(final Throwable t, final String message,
      LanguageConnectionContext lcc, LocalRegion region, EntryEventImpl event) {
    SystemFailure.checkFailure();
    if (lcc != null && lcc.isConnectionForRemote()) {
      // unwrap GemFireXDRuntimeExceptions
      Throwable failure = t;
      while (failure instanceof GemFireXDRuntimeException) {
        failure = failure.getCause();
      }
      lcc.getContextManager().cleanupOnError(failure);
    }
    // check for VM going down before throwing exception
    Misc.checkIfCacheClosing(t);
    if (GemFireXDUtils.retryToBeDone(t)) {
      // wrap in CacheClosedException else use since ForceReattemptException is
      // not a RuntimeException else could have used it for PRs(#47462)
      //throw new InternalFunctionInvocationTargetException(
      //    new ForceReattemptException(t.getMessage(), t));
      throw new OperationReattemptException(t.getMessage(), t);
    }
    // register failed event for any GII sources
    if (event != null && region instanceof DistributedRegion) {
      ((DistributedRegion)region).registerFailedEvent(event.getEventId());
    }
    if (t instanceof RuntimeException) {
      throw (RuntimeException)t;
    }
    if (t instanceof Error) {
      throw (Error)t;
    }
    throw GemFireXDRuntimeException.newRuntimeException(message, t);
  }

  public void dumpAllIndexes() {
    if (this.indexContainers != null) {
      for (GemFireContainer index : this.indexContainers) {
        ((MemIndex)index.getConglomerate()).dumpIndex("Dumping all indexes");
      }
    }
  }

  public List<GemFireContainer> getAllIndexes() {
    final List<GemFireContainer> containers = this.indexContainers;
    return (containers != null ? containers : Collections
        .<GemFireContainer> emptyList());
  }

  public static void traceIndex(String format, @Unretained Object... params) {
    if (params != null) {
      for (int index = 0; index < params.length; ++index) {
        params[index] = ArrayUtils.objectStringNonRecursive(params[index]);
      }
    }
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
        String.format(format, params));
  }

  @SuppressWarnings("unchecked")
  public void addTriggerExecutor(TriggerDescriptor trgD,
      String triggerActionStr, List<GfxdIMParamInfo> gfxdIMparaminfovec) {
    if (this.triggerInfo == null) {
      this.triggerInfo = new ArrayList[TriggerEvent.MAX_EVENTS];
      for (int i = 0; i < TriggerEvent.MAX_EVENTS; i++) {
        this.triggerInfo[i] = new ArrayList<TriggerInfoStruct>();
      }
    }
    int type = getEventTypeFromMask(trgD);
    this.triggerInfo[type].add(new TriggerInfoStruct(trgD, triggerActionStr,
        gfxdIMparaminfovec));
    this.numTriggers.incrementAndGet();
  }

  public void removeTriggerExecutor(TriggerDescriptor triggerd,
      LanguageConnectionContext lcc) {
    triggerd.getUUID();
    TriggerInfoStruct tif = new TriggerInfoStruct(triggerd);
    for (int i = 0; i < TriggerEvent.MAX_EVENTS; i++) {
      ArrayList<TriggerInfoStruct> list = this.triggerInfo[i];
      if (list.remove(tif)) {
        this.numTriggers.decrementAndGet();
        break;
      }
    }
  }

  private int getEventTypeFromMask(TriggerDescriptor trgDescp) {
    int type = -1;
    final int mask = trgDescp.getTriggerEventMask();
    if (trgDescp.isBeforeTrigger()) {
      if (GemFireXDUtils.isSet(mask, TriggerDescriptor.TRIGGER_EVENT_INSERT)) {
        type = TriggerEvent.BEFORE_INSERT;
      }
      else if (GemFireXDUtils.isSet(mask,
          TriggerDescriptor.TRIGGER_EVENT_UPDATE)) {
        type = TriggerEvent.BEFORE_UPDATE;
      }
      else if (GemFireXDUtils.isSet(mask,
          TriggerDescriptor.TRIGGER_EVENT_DELETE)) {
        type = TriggerEvent.BEFORE_DELETE;
      }
    }
    else {
      if (GemFireXDUtils.isSet(mask, TriggerDescriptor.TRIGGER_EVENT_INSERT)) {
        type = TriggerEvent.AFTER_INSERT;
      }
      else if (GemFireXDUtils.isSet(mask,
          TriggerDescriptor.TRIGGER_EVENT_UPDATE)) {
        type = TriggerEvent.AFTER_UPDATE;
      }
      else if (GemFireXDUtils.isSet(mask,
          TriggerDescriptor.TRIGGER_EVENT_DELETE)) {
        type = TriggerEvent.AFTER_DELETE;
      }
    }
    return type;
  }

  public static class TriggerInfoStruct {

    private final TriggerDescriptor trigD;

    private final String actionStmnt;

    private final List<GfxdIMParamInfo> paramInfo;

    private DataTypeDescriptor[] types;

    private final boolean isRowTrigger;

    public TriggerInfoStruct(TriggerDescriptor trgDesc, String gfxdActionStmnt,
        List<GfxdIMParamInfo> paramInfo) {
      this.trigD = trgDesc;
      this.actionStmnt = gfxdActionStmnt;
      this.paramInfo = paramInfo;
      this.isRowTrigger = trgDesc.isRowTrigger();
      initializeTypeField();
    }

    public TriggerInfoStruct(TriggerDescriptor td) {
      this.trigD = td;
      this.actionStmnt = null;
      this.paramInfo = null;
      this.isRowTrigger = false;
    }

    private void initializeTypeField() {
      if (this.paramInfo != null) {
        this.types = new DataTypeDescriptor[this.paramInfo.size()];
        int cnt = 0;
        for (GfxdIMParamInfo info : this.paramInfo) {
          this.types[cnt++] = info.getDTDType();
        }
      }
    }

    @Override
    public String toString() {
      return "TriggerInfoStruct: " + trigD.toString();
    }

    @Override
    public boolean equals(Object obj) {
      TriggerInfoStruct other = (TriggerInfoStruct)obj;
      return this.trigD.getUUID().equals(other.trigD.getUUID());
    }

    @Override
    public int hashCode() {
      // findbugs doesn't like the equals() method w/o a hashCode() method
      throw new UnsupportedOperationException(
          "this class does not support hashing");
    }

  }

  private boolean needToFireTrigger(LocalRegion owner,
      final TXStateInterface tx, boolean skipDistribution)
      throws StandardException {
    // for transactions just use the skipDistribution flag
    if (tx != null) {
      return !skipDistribution;
    }
    if (this.thisNodeMemberId.getVmKind() == DistributionManager.LONER_DM_TYPE) {
      return true;
    }
    boolean ret = false;
    boolean needToRefreshInfo = true;
    if (this.isPartitionedRegion) {
      if (((BucketRegion)owner).getBucketAdvisor().isPrimary()) {
        return true;
      }
      else {
        return false;
      }
    }
    // It is a replicated table case
    if (this.replicatedTableTriggerFiringNode != null) {
      if (this.thisNodeMemberId == this.replicatedTableTriggerFiringNode) {
        ret = true;
        needToRefreshInfo = false;
      }
      else {
        if (this.membershipManager == null || this.membershipManager
            .memberExists(this.replicatedTableTriggerFiringNode)) {
          needToRefreshInfo = false;
        }
        else {
          needToRefreshInfo = true;
        }
      }
    }

    if (needToRefreshInfo) {
      refreshTriggerFiringNodeInfo();
      ret = needToFireTrigger(owner, tx, skipDistribution);
    }
    return ret;
  }

  static final class GetElderMember implements TObjectProcedure {

    final Set<String> serverGroups;

    InternalDistributedMember elder;

    GetElderMember(final Set<String> serverGroups) {
      this.serverGroups = serverGroups;
    }

    /**
     * @see TObjectProcedure#execute(Object)
     */
    @Override
    public final boolean execute(final Object m) {
      final InternalDistributedMember member = (InternalDistributedMember)m;
      final int managerType = member.getVmKind();
      if (GemFireXDUtils.TraceTrigger) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRIGGER,
            "GfxdIndexManager$GetElderMember: checking node for replicated "
                + "datastore [" + member + "] in groups: " + this.serverGroups);
      }
      if (managerType != DistributionManager.ADMIN_ONLY_DM_TYPE
          && ServerGroupUtils.isGroupMember(member, this.serverGroups, true)) {
        this.elder = member;
        return false;
      }
      return true;
    }
  }

  private InternalDistributedMember getElderMember(
      final Set<String> serverGroups) {
    final GetElderMember doGet = new GetElderMember(serverGroups);
    this.membershipManager.forEachViewMember(doGet, true);
    return doGet.elder;
  }

  private synchronized void refreshTriggerFiringNodeInfo()
      throws StandardException {
    if (this.replicatedTableTriggerFiringNode != null) {
      if (this.thisNodeMemberId == this.replicatedTableTriggerFiringNode) {
        return;
      }
      else {
        if (this.membershipManager
            .memberExists(this.replicatedTableTriggerFiringNode)) {
          return;
        }
      }
    }
    final Set<String> serverGroups = this.container.getTableDescriptor()
        .getDistributionDescriptor().getServerGroups();
    this.replicatedTableTriggerFiringNode = getElderMember(serverGroups);
    // optimization to allow using reference-equality check for self
    if (this.thisNodeMemberId.equals(this.replicatedTableTriggerFiringNode)) {
      this.replicatedTableTriggerFiringNode = this.thisNodeMemberId;
    }
    if (GemFireXDUtils.TraceTrigger) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRIGGER,
          "GfxdIndexManager#refreshTriggerFiringNodeInfo: using replicated "
              + "firing node [" + this.replicatedTableTriggerFiringNode
              + "] in groups: " + serverGroups);
    }
  }

  private void fireTriggers(final EmbedConnection conn,
      LanguageConnectionContext lcc, final GemFireTransaction tran,
      final TXStateInterface tx, LocalRegion owner, EntryEventImpl event,
      ExecRow execRow, ExecRow newRow, boolean isBefore,
      boolean skipDistribution, final ArrayList<TriggerInfoStruct>[] triggers) {
    // no triggers in disk recovery
    if (event.getOperation().isDiskRecovery() || event.isCustomEviction()) {
      return;
    }
    TriggerInfoStruct tis = null;
    try {
      if (needToFireTrigger(owner, tx, skipDistribution)) {
        synchronized (conn.getConnectionSynchronization()) {
          conn.getTR().setupContextStack();
          try {
            // use proxy to enable distribution, if required (bug #43089)
            final TXStateProxy txProxy;
            //final GemFireXDQueryObserver observer =
            //    GemFireXDQueryObserverHolder.getInstance();
            if (tx != null && tx != (txProxy = tx.getProxy())) {
              final TXManagerImpl txMgr = tran.getTransactionManager();
              txMgr.masqueradeAs(txProxy);
              tran.setActiveTXState(txProxy, false);
              try {
                tis = invokeRowTriggers(lcc, owner, event, execRow, newRow,
                    isBefore, triggers);
                // flush all batched ops due to triggers
                txProxy.flushPendingOps(null);
              } finally {
                txMgr.masqueradeAs(tx);
                tran.setActiveTXState(tx, false);
              }
            }
            else {
              tis = invokeRowTriggers(lcc, owner, event, execRow, newRow,
                  isBefore, triggers);
            }
          } finally {
            conn.getTR().restoreContextStack();
          }
        }
      }
      
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      handleException(t, "Exception in executing trigger: " + tis, lcc, null,
          null);
    }
  }

  public void fireStatementTriggers(int triggerEvent,
      final LanguageConnectionContext lcc, final GemFireTransaction tran,
      final TXStateInterface tx) throws StandardException {
    // use proxy to enable distribution, if required (bug #43089)
    final TXStateProxy txProxy;
    if (tx != null && tx != (txProxy = tx.getProxy())) {
      final TXManagerImpl txMgr = tran.getTransactionManager();
      txMgr.masqueradeAs(txProxy);
      tran.setActiveTXState(txProxy, false);
      try {
        invokeStatementTriggers(triggerEvent, lcc);
        // flush all batched ops due to triggers
        txProxy.flushPendingOps(null);
      } finally {
        txMgr.masqueradeAs(tx);
        tran.setActiveTXState(tx, false);
      }
    }
    else {
      invokeStatementTriggers(triggerEvent, lcc);
    }
  }

  private TriggerInfoStruct invokeRowTriggers(LanguageConnectionContext lcc,
      LocalRegion owner, EntryEventImpl event, ExecRow execRow, ExecRow newRow,
      boolean isBefore, final ArrayList<TriggerInfoStruct>[] triggers)
      throws StandardException {
    TriggerInfoStruct tis = null;
    final int trigEv = determineTriggerEventType(event, isBefore);
    // -1 indicates ignore trigger invocation
    if (trigEv == -1) {
      return null;
    }
    final ArrayList<TriggerInfoStruct> trigList = triggers[trigEv];
    if (GemFireXDUtils.TraceTrigger) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRIGGER,
          "GfxdIndexManager#fireTriggers: inside row triggers for "
              + "container [" + this.container + "], event=" + trigEv );
    }
    for (int index = 0; index < trigList.size(); ++index) {
      tis = trigList.get(index);
      if (!tis.isRowTrigger) {
        continue;
      }
      if (GemFireXDUtils.TraceTrigger) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRIGGER,
            "GfxdIndexManager#fireTriggers: firing row trigger [" + tis
                + "] for container [" + this.container + "], event=" + trigEv);
      }
      PreparedStatement ps = lcc.getTriggerActionPrepStmnt(tis.trigD,
          tis.actionStmnt);
      boolean oldTrigger = lcc.isTriggerBody();
      try {
        Activation childActivation = ps.getActivation(lcc, false, null);
        if (childActivation.getMaxRows() == -1) {
          childActivation.setMaxRows(0);
        }
        childActivation.setIsPrepStmntQuery(true /*
                                                  * The Activation object refers
                                                  * to a PreparedStatement
                                                  */);

        childActivation.setStatementID(lcc.getStatementId());
        lcc.setTriggerBody(true);
        GenericParameterValueSet gpvs = (GenericParameterValueSet)childActivation
            .getParameterValueSet();
        if (gpvs != null && gpvs.getParameterCount() > 0) {
          childActivation.setParameters(gpvs, ps.getParameterTypes());
        }
        ((GenericPreparedStatement)ps).setFlags(true, true);
        if (tis.paramInfo != null) {
          for (int i = 0; i < tis.paramInfo.size(); ++i) {
            Object value = getValueFromParamInfo(event, execRow, newRow,
                tis.paramInfo.get(i));
            gpvs.setParameterAsObject(i, value, false);
          }
        }
        if (GemFireXDUtils.TraceTrigger) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRIGGER,
              "GfxdIndexManager#fireTriggers: firing row trigger action "
                  + "statement: " + tis.actionStmnt + " with gpvs: " + gpvs
                  + " ps: " + ps + " source is: " + ps.getSource());
        }
        final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
        if (observer != null) {
          observer.beforeRowTrigger(lcc, execRow, newRow);
        }
        final ResultSet r = ps.execute(childActivation, true, 0L, true, true);
        if (observer != null) {
          observer.afterRowTrigger(tis.trigD, gpvs);
        }
        r.close(false);
        childActivation.close();
      }
      finally {
        lcc.setTriggerBody(oldTrigger);
      }
    }
    return tis;
  }

  private void invokeStatementTriggers(int triggerEvent,
      final LanguageConnectionContext lcc) throws StandardException {
    final ArrayList<TriggerInfoStruct>[] triggers = this.triggerInfo;
    if (triggers != null) {
      final ArrayList<TriggerInfoStruct> trigList = triggers[triggerEvent];
      if (GemFireXDUtils.TraceTrigger) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRIGGER,
            "GfxdIndexManager#fireTriggers: inside statement triggers for "
                + "container [" + this.container + "], event=" + triggerEvent);
      }
      for (TriggerInfoStruct tis : trigList) {
        if (tis.isRowTrigger) {
          continue;
        }
        if (GemFireXDUtils.TraceTrigger) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRIGGER,
              "GfxdIndexManager#fireTriggers: firing statement trigger [" + tis
                  + "] for container [" + this.container + "], event="
                  + triggerEvent);
        }
        assert tis.paramInfo == null;
        PreparedStatement ps = lcc.getTriggerActionPrepStmnt(tis.trigD,
            tis.actionStmnt);
        Activation ac = ps.getActivation(lcc, false, null);
        if (ac.getMaxRows() == -1) {
          ac.setMaxRows(0);
        }
        ps.execute(ac, true, 0L, true, true);
        ac.close();
      }
    }
  }

  private Object getValueFromParamInfo(final EntryEventImpl event,
      final ExecRow erow, final ExecRow newRow, final GfxdIMParamInfo paramInfo)
      throws StandardException {
    final ExecRow row;
    if (paramInfo.isFromOld()) {
      row = erow;
    }
    else {
      if (newRow == null && event.isGFXDCreate(true)) {
        row = erow;
      }
      else {
        row = newRow;
      }
    }
    return row.getColumn(paramInfo.getPosition()).getObject();
  }

  private int determineTriggerEventType(EntryEventImpl event, boolean isBefore) {
    int type = -1;
    boolean throwException = false;
    Operation op = event.getOperation();
    if (isBefore) {
      if (event.hasDelta()) {
        type = TriggerEvent.BEFORE_UPDATE;
      }
      else if (event.isGFXDCreate(true)) {
        type = TriggerEvent.BEFORE_INSERT;
      }
      else if (op.isDestroy()) {
        type = TriggerEvent.BEFORE_DELETE;
      }
      // op.isUpdate() can happen for concurrent loader invocations (#47608)
      else if (!op.isUpdate()) {
        throwException = true;
      }
    }
    else {
      if (event.hasDelta()) {
        type = TriggerEvent.AFTER_UPDATE;
      }
      else if (event.isGFXDCreate(true)) {
        type = TriggerEvent.AFTER_INSERT;
      }
      else if (op.isDestroy()) {
        type = TriggerEvent.AFTER_DELETE;
      }
      // op.isUpdate() can happen for concurrent loader invocations (#47608)
      else if (!op.isUpdate()) {
        throwException = true;
      }
    }
    if (throwException) {
      throw new GemFireXDRuntimeException(
          "unknown operation type for trigger operations: " + op);
    }
    return type;
  }

  @Override
  public boolean clearIndexes(LocalRegion region, DiskRegion dr, boolean lockForGII,
      boolean holdIndexLock, Iterator<?> bucketEntriesIter, int bucketId) {
    EmbedConnection conn = null;
    GemFireContainer gfc = null;
    LanguageConnectionContext lcc = null;
    GemFireTransaction tc = null;
    boolean tableLockAcquired = false;
    boolean giiLockAcquired = false;
    LogWriter logger = Misc.getCacheLogWriterNoThrow();
    try {
      final List<GemFireContainer> indexes = this.indexContainers;
      conn = GemFireXDUtils.getTSSConnection(false, true, false);
      lcc = conn.getLanguageConnectionContext();
      tc = (GemFireTransaction)lcc.getTransactionExecute();
      gfc = (GemFireContainer)region.getUserAttribute();
      // need to take the read lock on container before reading indexes
      gfc.open(tc, ContainerHandle.MODE_READONLY);
      tableLockAcquired = true;
      // acquire the GII lock after container lock
      // Neeraj: fix for #50207
      // The same thread has taken a read lock and tries to grab a write lock
      // here and throws timout exception. So in case of replicated region this
      // is a problem but is not required to take lock here if region creation
      // has failed as anyways no index creation will happen.
      // Also tracking whether this actually took a write lock and returned
      // this information as the top level should not try to unlock if write lock was
      // not taken here. Fixes scenarios where gii failed in between due to some
      // reason and clearIndexes got called.
      boolean needToAcquireWriteLockOnGIILock = true;
      if (holdIndexLock) {
        // write lock is required and since this would have already taken
        // a read lock so this will hang. Need to avoid this and this hack does
        // that. For PR this is not happening.
        boolean isReplicated = !this.isPartitionedRegion;
        if (isReplicated && Misc.initialDDLReplayInProgress()) {
          if (!this.container.getRegion().isInitialized()) {
            needToAcquireWriteLockOnGIILock = false;
          }
        }
      }
      if (needToAcquireWriteLockOnGIILock) {
        if (lockForGII) {
          giiLockAcquired = lockForGII(holdIndexLock, tc);
        }
      }

      // for the case of replicated region, simply blow away the entire
      // local indexes
      if (!region.isUsedForPartitionedRegionBucket()
          && bucketId == KeyInfo.UNKNOWN_BUCKET) {
        if (indexes != null) {
          for (GemFireContainer index : indexes) {
            index.clear(lcc, tc);
          }
        }
        return giiLockAcquired;
      }

      RegionEntry entry;
      boolean isOffHeapEnabled = region.getEnableOffHeapMemory();
      if (isOffHeapEnabled || indexes != null) {
        int totalExceptionCount = 0;
        while (bucketEntriesIter.hasNext()) {
          entry = (RegionEntry) bucketEntriesIter.next();
          try {
            if (indexes != null) {
              basicClearEntry(region, dr, tc, indexes, entry, bucketId);
            }
          } catch (Throwable th) {
            // in case of not destroyOffline, no need to log.
            // However, after the index fix we shouldn't reach here.
            totalExceptionCount++;
            if (logger != null && (totalExceptionCount == 1)) {
              logger.error("Exception in removing the entry from index. "
                  + "Ignoring & continuing the loop ", th);
            }
          } finally {
            if (logger != null && totalExceptionCount > 1) {
              logger.error("Exception in removing the entry from index. "
                  + "Total exception count : " + totalExceptionCount);
            }
            if (isOffHeapEnabled) {
              ((AbstractRegionEntry) entry).release();
            }
          }
        }
      }
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      handleException(t, "GfxdIndexManager#clearIndexes: unexpected exception",
          lcc, null, null);
    } finally {
      if (!holdIndexLock) {
        unlockForGII(false, tc);
      }
      if (tableLockAcquired) {
        gfc.closeForEndTransaction(tc, false);
      }
    }
    return giiLockAcquired;
  }

  void basicClearEntry(LocalRegion region, DiskRegion dr, GemFireTransaction tc,
      final List<GemFireContainer> indexes, RegionEntry entry, int bucketId)
      throws StandardException, InternalGemFireError, Error {
    ExecRow oldRow;
    synchronized (entry) {
      if (!entry.isDestroyedOrRemoved()) {
        @Retained @Released
        Object val = ((AbstractRegionEntry)entry).getValueOffHeapOrDiskWithoutFaultIn(region, dr);
        if (val == null || val instanceof Token) {
          return;
        }
        try {
          oldRow = getExecRow(this.td, val, null, 0, true,
              getTableInfo(entry));
          // the execRow can be null in case bucket is cleared while base
          // value
          // is a ListOfDeltas in which case ignore the destroy (bug #41529)
          if (oldRow != null) {
            // bucketId is passed when bucket region is not created
            // and instead a PR is passed as region. Avoid passing region tp
            // EntryEventImpl.create if bucketId is valid as it
            // will try to find routing object if region is passed
            EntryEventImpl event = EntryEventImpl.create(
                bucketId == KeyInfo.UNKNOWN_BUCKET ? region : null,
                Operation.DESTROY, entry.getKey(), null, null, false, null);
            if (bucketId != KeyInfo.UNKNOWN_BUCKET) {
              event.setRegion(region);
              event.setBucketId(bucketId);
            }
            event.setOldValue(val, true);
            event.setPossibleDuplicate(true);
            //mark the vent with set duplicate as true as it is possible that 
            // because of destroy flag set on the region, the entry never went into the 
            //index, which will unnecessary cause an exception to be thrown
            try {
              doDelete(tc, null, region, event, true /*
                                                          * do not touch
                                                          * global indexes
                                                          */, entry,
                  oldRow, indexes, false, true);
            } catch (Throwable t) {
              Error err;
              if (t instanceof Error && SystemFailure.isJVMFailureError(
                  err = (Error)t)) {
                SystemFailure.initiateFailure(err);
                // If this ever returns, rethrow the error. We're poisoned
                // now, so don't let this thread continue.
                throw err;
              }
              Misc.checkIfCacheClosing(t);
              // log the error at this level and move on
              // we don't want region destroy to fail due to this otherwise
              // many other things can go wrong
              SanityManager.DEBUG_PRINT("severe:"
                  + GfxdConstants.TRACE_INDEX, "GfxdIndexManager#"
                  + "clearIndexes: unexpected exception for " + entry, t);
            } finally {
              event.release();
            }
            this.container.updateNumRows(true);
          }
        } finally {
          OffHeapHelper.release(val);
        }
      }
    }
  }

  @Override
  public void releaseIndexLock(LocalRegion region) {
    EmbedConnection conn;
    LanguageConnectionContext lcc = null;
    GemFireTransaction tc;
    try {
      conn = GemFireXDUtils.getTSSConnection(true, true, false);
      lcc = conn.getLanguageConnectionContext();
      tc = (GemFireTransaction)lcc.getTransactionExecute();
      unlockForGII(true, tc);
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      handleException(t,
          "GfxdIndexManager#releaseIndexLock: unexpected exception", lcc,
          null, null);
    }
  }

  /** invoke when dropping the table to clear out dependencies */
  public void drop(final GemFireTransaction tran) throws StandardException {
    // unregister from dependecy manager
    final UUID oid = this.oid;
    if (oid != null) {
      dd.getDependencyManager().clearDependencies(
          tran.getLanguageConnectionContext(), this, tran);
    }
  }

  public final List<ConglomerateDescriptor> getIndexConglomerateDescriptors() {
    return this.indexDescs;
  }

  public List<GemFireContainer> getIndexContainers() {
    return this.indexContainers;
  }

  @Override
  public Object getName() {
    return this.name;
  }

  @Override
  public GfxdReentrantReadWriteLock getReadWriteLock() {
    return this.lockForContainerGII;
  }

  @Override
  public void setReadWriteLock(GfxdReadWriteLock rwLock) {
    // ignored
  }

  @Override
  public boolean traceLock() {
    return this.traceLock;
  }

  /**
   * Holds various fields for foreign key reference on the table.
   * 
   * @author swale
   * @since 7.0
   */
  public static final class ForeignKeyInformation {

    /** the descriptor for foreign key referred by the table */
    final ForeignKeyConstraintDescriptor fkcd;

    /**
     * the GemFireContainer for the table referred by the foreign key
     * {@link #fkcd}
     */
    final GemFireContainer refContainer;

    /**
     * the GemFireContainer for the index on the columns of the table referred
     * by the foreign key {@link #fkcd}
     */
    final GemFireContainer refIndex;

    /**
     * for an FK {@link #fkcd} on unique columns, if the unique columns are
     * superset of partitioning columns, then this stores the mapping (i.e.
     * refColsPartColsMapping[desc][index] gives the index into the referenced
     * column list of the matching column
     */
    final int[] refColsPartColsMap;
    
    public GemFireContainer getRefContainer() {
      return this.refContainer;
    }

    public GemFireContainer refIndex() {
      return this.refIndex;
    }

    public ForeignKeyConstraintDescriptor getFkcd() {
      return this.fkcd;
    }
    public int[] getRefColsPartColsMap() {
      return this.refColsPartColsMap;
    }

    ForeignKeyInformation(ForeignKeyConstraintDescriptor fkcd,
        GemFireContainer refContainer, GemFireContainer refIndex,
        int[] refColsPartColsMap) {
      this.fkcd = fkcd;
      this.refContainer = refContainer;
      this.refIndex = refIndex;
      this.refColsPartColsMap = refColsPartColsMap;
    }
  }

  /**
   * Execution function message to perform containsKey() for foreign key
   * on pk columns constraint check. This is used instead of region methods to avoid
   * distributed deadlocks for cases during inserts (see bugs #40208 and #40296)
   * 
   * @author swale
   * @since 6.0
   */
  public static final class ContainsKeyExecutorMessage extends
      RegionSingleKeyExecutorMessage {

    private final static String ID = "ContainsKeyExecutorMessage";

    /** for deserialization */
    public ContainsKeyExecutorMessage() {
      super(true);
    }

    public ContainsKeyExecutorMessage(final LocalRegion region,
        final Object key, final Object callbackArg, final Object routingObject,
        final TXStateInterface tx, LanguageConnectionContext lcc) {
      // acquire lock on all copies for REPEATABLE_READ
      super(region, key, callbackArg, routingObject, tx != null
          && tx.getLockingPolicy().readOnlyCanStartTX(), tx,
          getTimeStatsSettings(lcc));
    }

    /** copy constructor for {@link #clone()} */
    private ContainsKeyExecutorMessage(final ContainsKeyExecutorMessage other) {
      super(other);
    }
    
        
    public static boolean existsKey(LocalRegion lr, boolean isPR, int bucketId,
        Object key, TXStateInterface tx, Object callbackArg)
        throws GemFireCheckedException {
      final boolean doLog = DistributionManager.VERBOSE
          | GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceIndex
          | SanityManager.isFineEnabled;

      final boolean containsKey;
      PartitionedRegion pr = null;
      if (isPR) {
        pr = (PartitionedRegion) lr;
      }
      
      if (pr != null) {
        if (doLog) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, ID
              + ": execute for PR " + pr.getFullPath() + " on key "
              + key);
        }
        containsKey = pr.getDataStore().txContainsKeyLocally(
            bucketId, key, tx);
      }
      else {
        if (doLog) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, ID
              + ": execute for DR " + lr.getFullPath() + " on key " + key);
        }
        containsKey = lr.txContainsKey(key, callbackArg, tx,
            true);
      }
      
      return containsKey;
    }


    /**
     * @see GfxdFunctionMessage#execute()
     */
    @Override
    protected void execute() throws GemFireCheckedException {
      final TXStateInterface tx = getTXState();
      final boolean doLog = DistributionManager.VERBOSE
          | GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceIndex
          | SanityManager.isFineEnabled;

      LocalRegion lr = null;
      if (this.pr != null) {
        lr = this.pr;
      } else {
        lr = this.region;
      }

      final boolean containsKey = existsKey(lr, this.pr != null, this.bucketId, 
                    this.key, tx, this.callbackArg);

      if (doLog) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, ID
            + ": sending result=" + containsKey
            + " back via ReplyMessage with processorId=" + getProcessorId()
            + ", isSecondaryCopy=" + this.isSecondaryCopy);
      }
      if (this.isSecondaryCopy) {
        // DUMMY_RESULT indicates dummy result and only for locking
        lastResult(DUMMY_RESULT, false, false, true);
      }
      else {
        lastResult(Boolean.valueOf(containsKey), false, true, true);
      }
    }

    /**
     * @see GfxdFunctionMessage#isHA()
     */
    @Override
    public final boolean isHA() {
      return true;
    }

    /**
     * @see GfxdFunctionMessage#optimizeForWrite()
     */
    @Override
    public final boolean optimizeForWrite() {
      return false;
    }

    /**
     * @see RegionSingleKeyExecutorMessage#clone()
     */
    @Override
    protected ContainsKeyExecutorMessage clone() {
      return new ContainsKeyExecutorMessage(this);
    }

    /**
     * @see TransactionMessage#canStartRemoteTransaction()
     */
    @Override
    public final boolean canStartRemoteTransaction() {
      return getLockingPolicy().readOnlyCanStartTX();
    }

    @Override
    public final int getMessageProcessorType() {
      // Make this serial so that it will be processed in the p2p msg reader
      // which gives it better performance.
      // don't use SERIAL_EXECUTOR if we may have to wait for a pending TX
      // else if may deadlock as the p2p msg reader thread will be blocked
      // don't use SERIAL_EXECUTOR for RepeatableRead isolation level else
      // it can block P2P reader thread thus blocking any possible commits
      // which could have released the SH lock this thread is waiting on
      return this.pendingTXId == null
          && getLockingPolicy() == LockingPolicy.NONE
          ? DistributionManager.SERIAL_EXECUTOR
          : DistributionManager.PARTITIONED_REGION_EXECUTOR;
    }

    @Override
    public byte getGfxdID() {
      return CONTAINSKEY_EXECUTOR_MSG;
    }

    @Override
    protected String getID() {
      return ID;
    }
  }

  /**
   * Class to carry around some contextual information in the
   * {@link EntryEventImpl} object.
   * 
   * @author swale
   */
  private static final class EntryEventContext {

    final EmbedConnection conn;

    final LanguageConnectionContext lcc;

    final GemFireTransaction tc;

    ExecRow execRow;

    final ExecRow newExecRow;
    
    ExecRow triggerExecRow;

    ExecRow triggerNewExecRow;
    
    FormatableBitSet changedColumns;

    final ArrayList<GemFireContainer> undoList;

    final boolean skipDistribution;

    final boolean isGIILocked;

    final boolean isGIIEvent;

    final boolean isPutDML;

    final int bucketId;

    final boolean origSkipLocks;

    final boolean origIsRemote;

    final boolean restoreFlags;

    final boolean lccPosDupSet;
    
    final Object routingObject;

    EntryEventContext(EmbedConnection conn, LanguageConnectionContext lcc,
        GemFireTransaction tc, boolean skipDistribution, ExecRow execRow,
        ExecRow newRow, FormatableBitSet changedColumns,
        ArrayList<GemFireContainer> undoList, boolean isGIILocked,
        int bucketId, boolean skipLocks, boolean isRemote,
        boolean restoreFlags, boolean lccPosDupSet, boolean isGIIEvent,
        boolean isPutDML, Object routingObject) {
      this.conn = conn;
      this.lcc = lcc;
      this.tc = tc;
      this.execRow = execRow;
      this.newExecRow = newRow;
      this.changedColumns = changedColumns;
      this.undoList = undoList;
      this.skipDistribution = skipDistribution;
      this.isGIILocked = isGIILocked;
      this.bucketId = bucketId;
      this.origSkipLocks = skipLocks;
      this.origIsRemote = isRemote;
      this.restoreFlags = restoreFlags;
      this.lccPosDupSet = lccPosDupSet;
      this.isGIIEvent = isGIIEvent;
      this.isPutDML = isPutDML;
      this.routingObject = routingObject;
    }

    @Override
    public String toString() {
      return "EntryEventContext: execRow=" + this.execRow + ", newExecRow="
          + this.newExecRow + ", changedColumns=" + this.changedColumns
          + ", bucketId=" + this.bucketId + ", skipDistribution="
          + this.skipDistribution + ", isGIILocked=" + this.isGIILocked
          + ", isGIIEvent=" + this.isGIIEvent + ", isPutDML=" + this.isPutDML
          + ", skipLocks=" + this.origSkipLocks + ", isRemote=" + origIsRemote
          + ", restoreFlags=" + this.restoreFlags + ", lccPosDupSet="
          + this.lccPosDupSet + ", undoList=" + this.undoList + ", lcc="
          + this.lcc + ", tc=" + this.tc + ", conn=" + this.conn;
    }
  }

  public Object getFKS() {
    return this.fks;
  }

  private Set<String> triggerTargetTableNames;
  public boolean hasThisTableAsTarget(String qualifiedName) {
    if (this.triggerTargetTableNames == null) {
      return false;
    }
    return this.triggerTargetTableNames.contains(qualifiedName);
  }

  @SuppressWarnings("unchecked")
  public void addTriggerTargetTableName(String targetTableName) {
    if (this.triggerTargetTableNames == null) {
      this.triggerTargetTableNames = new THashSet();
    }
    this.triggerTargetTableNames.add(targetTableName);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean handleSuspectEvents() {
    return !this.isPartitionedRegion
        && (this.hasFkConstriant | this.hasUniqConstraint);
  }

  /**
   * Append index records for a newly created index to existing old oplogs. For
   * the current oplog, the new index records will get written automatically
   * whenever the next rolling happens.
   */
  public long writeNewIndexRecords(GemFireContainer indexContainer,
      DiskStoreImpl dsImpl) throws StandardException {
    final SortedIndexRecoveryJob indexRecoveryJob = new SortedIndexRecoveryJob(
        dsImpl.getCache(), dsImpl, dsImpl.getCancelCriterion(), indexContainer);
    final Map<SortedIndexContainer, SortedIndexRecoveryJob> singleIndex =
        Collections.singletonMap((SortedIndexContainer)indexContainer,
            indexRecoveryJob);
    try {
      long numEntries = 0;
      dsImpl.forceFlush();
      // we take the DD read lock in Oplog.createKrfAsync which will prevent
      // any new createKrf from creating idxkrf files that might be missing
      // this index, so from we know that the current oplogs that get rolled
      // over will have the new index data for sure
      for (Oplog oplog : dsImpl.getPersistentOplogSet(null).getAllOplogs()) {
        if (oplog.needsKrf()) {
          // don't dump where KRF is still to be created since that will happen
          // in its createKrfAsync invocation when KRF is created; however need
          // to load the index neverthless
          numEntries += oplog.recoverIndexes(singleIndex);
        }
        else if (oplog.getIndexFileIfValid(false) != null) {
          Collection<DiskRegionInfo> regions = oplog
              .getTargetRegionsForIndexes(singleIndex.keySet());
          numEntries += oplog.writeIRF(oplog.getSortedLiveEntries(regions),
              null, singleIndex.keySet(), singleIndex);
        }
        else {
          // create fresh irf file having all indexes but load only this index
          Set<SortedIndexContainer> allIndexes = GemFireCacheImpl
              .getInternalProductCallbacks().getAllLocalIndexes(dsImpl);
          Collection<DiskRegionInfo> regions = oplog
              .getTargetRegionsForIndexes(allIndexes);
          numEntries += oplog.writeIRF(oplog.getSortedLiveEntries(regions),
              null, allIndexes, singleIndex);
        }
        // check for node shutdown
        Misc.checkIfCacheClosing(null);
      }
      indexRecoveryJob.endJobs();
      indexContainer.accountMemoryForIndex(numEntries, true);
      return numEntries;
    } catch (IOException ioe) {
      // check for node shutdown
      Misc.checkIfCacheClosing(ioe);
      throw StandardException.newException(SQLState.DATA_UNEXPECTED_EXCEPTION,
          ioe);
    } catch (DiskAccessException dae) {
      // check for node shutdown
      Misc.checkIfCacheClosing(dae);
      throw StandardException.newException(SQLState.DATA_UNEXPECTED_EXCEPTION,
          dae);
    }
  }

  /**
   * Load index records reading from in-memory table data.
   */
  public long loadLocalIndexRecords(GemFireContainer indexContainer)
      throws StandardException {
    final LocalRegion region = this.container.getRegion();
    // Compute the size of index step wise , with power of two

    final SortedIndexRecoveryJob indexRecoveryJob = new SortedIndexRecoveryJob(
        region.getCache(), null, region.getCancelCriterion(), indexContainer);
    try {
      long numEntries = 0;
      final boolean hasOffHeap = region.getEnableOffHeapMemory();
      // includeValues below does not really matter since it has an affect only
      // for persistent regions which are handled otherwise.
      // Else includeValues should have been true if index key was part of value
      // and false if it was completely a subset of primary key alone.
      Iterator<?> entryIterator = this.container.getEntrySetIterator(null,
          false, 0, true);
      while (entryIterator.hasNext()) {
        indexContainer.accountMemoryForIndex(numEntries, false);
        RegionEntry entry = (RegionEntry)entryIterator.next();
        @Released
        final Object val = ((RowLocation)entry)
            .getValueWithoutFaultIn(this.container);
        if (val == null || Token.isInvalidOrRemoved(val)) {
          if (GemFireXDUtils.TraceIndex) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
                "IndexManager#loadLocalIndexRecords: row null for entry: "
                    + entry + "; continuing to next.");
          }
          continue;
        }
        CompactCompositeIndexKey ikey = indexContainer.getLocalIndexKey(val,
            entry, false, false);
        if (!hasOffHeap || !(val instanceof Chunk)) {
          indexRecoveryJob.addJob(ikey, entry);
        }
        else {
          try {
            indexRecoveryJob.addJob(ikey, entry);
          } finally {
            ((Chunk)val).release();
          }
        }
        numEntries++;
      }
      indexRecoveryJob.endJobs();
      indexContainer.accountMemoryForIndex(numEntries, true);
      List<GemFireContainer> indexes = getAllIndexes();
      return numEntries;
    } catch (CancelException ce) {
      throw ce;
    } catch (GemFireException ge) {
      // check for node shutdown
      Misc.checkIfCacheClosing(ge);
      throw StandardException.newException(SQLState.DATA_UNEXPECTED_EXCEPTION,
          ge);
    }
  }
}
