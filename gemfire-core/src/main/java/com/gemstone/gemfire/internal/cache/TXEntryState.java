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

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.TX_ENTRY_STATE;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.CacheWriter;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.EntryDestroyedException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.locks.NonReentrantLock;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.Releasable;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.util.ArrayUtils;

/**
 * TXEntryState is the entity that tracks transactional changes.
 * 
 * @author Darrel Schneider
 * @author swale
 * 
 * @since 4.0
 *  
 */
public class TXEntryState implements TXEntryId, Releasable {

  @Retained(TX_ENTRY_STATE)
  protected Object originalVersionId;

  /*
   * Used to remember the event id to use on the farSide for this entry. See bug
   * 39434.
   * 
   * @since 5.7
   */
  //private int farSideEventOffset = -1;

  /**
   * Used to remember the event id to use on the nearSide for this entry. See
   * bug 39434.
   * 
   * @since 5.7
   */
  private int nearSideEventOffset = -1;

  // Asif: In case of GemFireXD, the pending value may be a SerializableDelta
  // object which may be containing base value ( in case of Tx create) along
  // with bunch of incremental deltas, so for correct behaviour this field
  // should be accessed only by its getter. Do not use it directly.
  // [sumedh] Now the GemFireXD delta is stored in pendingDelta field.
  protected Object pendingValue;
  protected Delta pendingDelta;

  /**
   * Remember the callback argument for listener invocation
   */
  private Object callBackArgument;

  protected byte op;

  /**
   * destroy field remembers the strongest destroy op perfomed on this entry
   */
  protected byte destroy; // DESTROY_NONE, DESTROY_LOCAL, DESTROY_DISTRIBUTED

  /**
   * Indicates whether this entry has pending changes that still need to be
   * flushed.
   */
  private transient byte pendingStateForBatch;

  /*
  /**
   * System property to be set when read conflicts should be detected.
   * Benefits of read conflict detection are at:
   * https://wiki.gemstone.com/display/PR/Read+conflict+detection
   *
  private static final boolean DETECT_READ_CONFLICTS =
    Boolean.getBoolean("gemfire.detectReadConflicts");
  */

  /**
   * The region key for this operation.
   */
  protected final Object regionKey;

  // @todo darrel/mitch: optimize footprint by having this field on a subclass
  //      that is only created by TXRegionState when it knows its region is an
  //      LRU.
  /**
   * A reference to the LRUEntry, in committed state, that this tx entry has
   * referenced. Note: this field is only needed if the commited region is an
   * LRU.
   * 
   * [sumedh] In the new TX model this now keeps the RegionEntry in all cases
   * obtained during initial locking
   */
  //private final LRUEntry cmtLRUEntry;
  protected RegionEntry regionEntry;

  /**
   * Set to true if this operation is result of a bulk op. We use this
   * boolean to determine op type rather than extending the operation algebra.
   */
  protected boolean bulkOp;

  /**
   * Flag to indicate that this entry was created by TX GII (which can be a
   * duplicate since the operation can also be received directly and needs to be
   * treated as duplicate). This should always be read or written under
   * TXRegionState lock so not needed to be volatile.
   */
  protected transient boolean fromGII;

  /**
   * Reference to the previous element in the circular doubly linked list. This
   * is used to order by the modification so that events are applied to cache in
   * same order.
   */
  protected TXEntryState previous;

  /**
   * Reference to the next element in the circular doubly linked list. This is
   * used to order by the modification so that events are applied to cache in
   * same order.
   */
  protected TXEntryState next;

  /**
   * The {@link TXRegionState} containing this TX entry.
   */
  protected TXRegionState txRegionState;

  // Static byte values for the "destroy"
  // ORDER of the following is important to the implementation!
  protected static final byte DESTROY_NONE = 0;

  protected static final byte DESTROY_LOCAL = 1;

  protected static final byte DESTROY_DISTRIBUTED = 2;

  // State byte value for "isPendingForBatch"
  /**
   * Indicates that a normal delta flush has to be done when available.
   */
  protected static final byte PENDING_DELTA_FLUSH = 1;

  /**
   * Indicates that the full value has to be flushed even if delta is available
   * since the entry has been created in this TX that has not been flushed yet.
   */
  protected static final byte PENDING_FULL_VALUE_FLUSH = 2;

  // Static byte values for the "op"
  // ORDER of the following is important to the implementation!

  protected static final byte OP_FLAG_EOF = -4;
  protected static final byte OP_FLAG_FOR_READ = -3;
  protected static final byte OP_LOCK_FOR_UPDATE = -2;
  protected static final byte OP_READ_ONLY = -1;
  protected static final byte OP_NULL = 0;
  protected static final byte OP_L_DESTROY = 1;
  protected static final byte OP_CREATE_LD = 2;
  protected static final byte OP_LLOAD_CREATE_LD = 3;
  protected static final byte OP_NLOAD_CREATE_LD = 4;
  protected static final byte OP_PUT_LD = 5;
  protected static final byte OP_LLOAD_PUT_LD = 6;
  protected static final byte OP_NLOAD_PUT_LD = 7;
  protected static final byte OP_D_INVALIDATE_LD = 8;
  protected static final byte OP_D_DESTROY = 9;
  protected static final byte OP_L_INVALIDATE = 10;
  protected static final byte OP_PUT_LI = 11;
  protected static final byte OP_LLOAD_PUT_LI = 12;
  protected static final byte OP_NLOAD_PUT_LI = 13;
  protected static final byte OP_D_INVALIDATE = 14;
  protected static final byte OP_CREATE_LI = 15;
  protected static final byte OP_LLOAD_CREATE_LI = 16;
  protected static final byte OP_NLOAD_CREATE_LI = 17;
  protected static final byte OP_CREATE = 18;
  protected static final byte OP_SEARCH_CREATE = 19;
  protected static final byte OP_LLOAD_CREATE = 20;
  protected static final byte OP_NLOAD_CREATE = 21;
  protected static final byte OP_LOCAL_CREATE = 22;
  protected static final byte OP_PUT = 23;
  protected static final byte OP_SEARCH_PUT = 24;
  protected static final byte OP_LLOAD_PUT = 25;
  protected static final byte OP_NLOAD_PUT = 26;

  static {
    Assert.assertTrue(OP_SEARCH_PUT - OP_PUT == OP_SEARCH_CREATE - OP_CREATE,
        "search offset inconsistent");
    Assert.assertTrue(OP_LLOAD_PUT - OP_PUT == OP_LLOAD_CREATE - OP_CREATE,
        "lload offset inconsistent");
    Assert.assertTrue(OP_NLOAD_PUT - OP_PUT == OP_NLOAD_CREATE - OP_CREATE,
        "nload offset inconsistent");
  }

  public static byte getLockForUpdateOp() {
    return OP_LOCK_FOR_UPDATE;
  }

  public static byte getReadOnlyOp() {
    return OP_READ_ONLY;
  }

  // TODO: KN a list per txentry seems too much but whats the option.
  // May be a fixed size array of 3 or whatever which if required can
  // be recreated of a bigger size.
  protected final ArrayList<TxOpChange> changesInOrder;
  /**
   * This constructor is used when creating an entry.
   */
  protected TXEntryState(final Object key, final RegionEntry re, Object val,
      final boolean doFullValueFlush, final TXRegionState txrs) {
    this.pendingStateForBatch = -PENDING_DELTA_FLUSH;
    // check lastModified time to see if this entry was created for
    // locking only
    // TODO: TX: see if this can cause any problem with expiry/evict
    // Will likely need rework for expiry/evict
    if (re != null && re.isLockedForCreate()) {
      // REMOVED_PHASE2 indicates that entry was created for locking
      val = Token.REMOVED_PHASE2;
      if (doFullValueFlush) {
        // value will be created in this TX, so flush full pending value
        this.pendingStateForBatch = -PENDING_FULL_VALUE_FLUSH;
      }
    }
    else if (val == null) {
      // REMOVED_PHASE1 indicates that entry was present but destroyed in region
      val = Token.REMOVED_PHASE1;
      if (doFullValueFlush) {
        // value will be created in this TX, so flush full pending value
        this.pendingStateForBatch = -PENDING_FULL_VALUE_FLUSH;
      }
    }
    this.op = OP_NULL;
    this.originalVersionId = val;
    this.regionKey = key;
    this.regionEntry = re;
    this.txRegionState = txrs;
    this.changesInOrder = new ArrayList<TxOpChange>();
  }

  public final Object getPendingValue() {
    if (this.pendingValue == null && this.pendingDelta != null) {
      this.pendingValue = this.pendingDelta.apply(getBaseRegion(),
          this.regionKey, this.originalVersionId, false);
    }
    return this.pendingValue;
  }

  public final boolean hasPendingValue() {
    return this.pendingDelta != null
        || (this.pendingValue != null && !Token
            .isInvalidOrRemoved(this.pendingValue));
  }

  public final RegionEntry getUnderlyingRegionEntry() {
    return this.regionEntry;
  }

  public final Object getCallbackArgument() {
    return this.callBackArgument;
  }

  public final LocalRegion getDataRegion() {
    return this.txRegionState.region;
  }

  public final LocalRegion getBaseRegion() {
    return getBaseRegion(this.txRegionState.region);
  }

  private final LocalRegion getBaseRegion(final LocalRegion dataRegion) {
    if (dataRegion.isUsedForPartitionedRegionBucket()) {
      return dataRegion.getPartitionedRegion();
    }
    return dataRegion;
  }

  /**
   * Gets the pending value for near side operations. Special cases local
   * destroy and local invalidate to fix bug 34387.
   */
  @Unretained
  public final Object getNearSidePendingValue() {
    if (isOpDestroy()) {
      return null;
    }
    if (isOpLocalInvalidate()) {
      return Token.LOCAL_INVALID;
    }
    return getPendingValue();
  }

  protected final void setPendingValue(final Object pv, final Delta pendingDelta,
      LocalRegion baseRegion, final byte op) {
//    if (pv instanceof StoredObject) {
//      baseRegion.getLogWriterI18n().info(LocalizedStrings.DEBUG, "unexpected instanceof StoredObject", new RuntimeException("STACK"));
//      throw new IllegalStateException("unexpected instanceof StoredObject");
//    }
    final LogWriterI18n logger = baseRegion.getLogWriterI18n();
    final boolean logFiner = logger.finerEnabled() || TXStateProxy.LOG_FINEST;
    if (pendingDelta != null) {
      if (this.pendingDelta == null) {
        // case of delta i.e. TX update
        this.pendingDelta = pendingDelta;
        if (pv != null) {
          if (logFiner) {
            logger.finer("setPendingValue: setting full value with already "
                + "applied delta " + pv + " for entry " + shortToString());
          }
          this.pendingValue = pv;
        }
        else if (this.pendingValue != null) {
          if (logFiner) {
            logger.finer("setPendingValue: applying delta " + pendingDelta
                + " over full value for entry " + shortToString());
          }
          // case of delta over existing pendingValue i.e. TX create + update
          if (baseRegion.isUsedForPartitionedRegionBucket()) {
            baseRegion = baseRegion.getPartitionedRegion();
          }
          this.pendingValue = pendingDelta.apply(baseRegion, this.regionKey,
              this.pendingValue, false);
        }
        else {
          if (logFiner) {
            logger.finer("setPendingValue: setting delta " + pendingDelta
                + " for entry " + shortToString());
          }
        }
      }
      else {
        // case of two deltas consecutively i.e. TX update + update
        if (baseRegion.isUsedForPartitionedRegionBucket()) {
          baseRegion = baseRegion.getPartitionedRegion();
        }
        this.pendingDelta = this.pendingDelta.merge(baseRegion, pendingDelta);
        // apply over any existing value too
        if (this.pendingValue != null) {
          this.pendingValue = pendingDelta.apply(baseRegion, this.regionKey,
              this.pendingValue, false);
        }
        if (logFiner) {
          logger.finer("setPendingValue: merging delta " + pendingDelta
              + " over existing delta for entry " + shortToString());
        }
      }
    }
    else if (pv != null) {
      if (logFiner) {
        logger.finer("setPendingValue: setting full value " + pv
            + " for entry " + shortToString());
      }
      this.pendingValue = pv;
      this.pendingDelta = null;
    }
    else {
      // both value and delta null means this must be destroy
      if (!isOpDestroy(op)) {
        Assert.fail("unexpected value and delta both null in "
            + "TXEntryState#setPendingValue with op " + op + ": " + toString());
      }
      if (logFiner) {
        logger.finer("setPendingValue: clearing pending value and delta "
            + "for destroy of entry " + shortToString());
      }
      this.pendingValue = null;
      this.pendingDelta = null;
    }
  }

  protected void setCallbackArgument(Object callbackArgument) {
    this.callBackArgument = callbackArgument;
  }

  /**
   * Place the entry at the end of queue to order the update events.
   * 
   * @since 7.0
   */
  protected final void updateForCommit(final TXState txState) {
    // mark TX as dirty
    txState.getProxy().markDirty();
    final TXEntryState head = txState.head;
    // assuming TXRegionState is locked, the access to next for this entry need
    // not be synchronized
    TXEntryState next = this.next;
    // return immediately if this is already the last entry
    if (next == head) {
      return;
    }
    final NonReentrantLock headLock = txState.headLock;
    headLock.lock();
    try {
      updateForCommitNoLock(txState, false);
    } finally {
      headLock.unlock();
    }
  }

  /**
   * Place the entry at the end of queue to order the update events without any
   * locking. Always invoke under the {@link TXState#headLock} lock.
   * 
   * @since 7.0
   */
  final void updateForCommitNoLock(final TXState txState,
      final boolean markPending) {
    assert txState.headLock.isLocked();

    final TXEntryState head = txState.head;
    // return immediately if this is already the last entry
    TXEntryState next = this.next;
    if (next == head) {
      if (markPending) {
        markPendingForBatch(txState);
      }
      return;
    }
    // if this is an update then remove from current position first
    if (next != null) {
      this.previous.next = next;
      next.previous = this.previous;
    }
    this.previous = head.previous;
    this.next = head;
    head.previous.next = this;
    head.previous = this;
    if (markPending) {
      markPendingForBatch(txState);
    }
  }

  /**
   * Returns true if this entry has been written; false if only read
   * 
   * @since 5.1
   */
  public final boolean isDirty() {
    return this.op > OP_NULL;
  }

  private final void markPendingForBatch(final TXState txState) {
    if (this.pendingStateForBatch < 0) {
      this.pendingStateForBatch = (byte)-this.pendingStateForBatch;
      txState.addPendingOp(this, getDataRegion());
    }
  }

  protected final void clearPendingForBatch() {
    // don't try to change the values here since the TXRegionState is not locked
    /*
    // try to flush only the new deltas if this entry was created in this TX
    // if entry was not created in this TX don't increase the overhead by
    // creating a new byte[]
    if (this.pendingDelta != null && wasCreatedByTX()) {
      this.pendingValue = this.pendingDelta.apply(getBaseRegion(),
          this.regionKey, this.pendingValue);
      this.pendingDelta = null;
    }
    */
    if (isPendingForBatch()) {
      // if we have already flushed full value created in TX then no need to do
      // it any further, so always revert back to delta flush when possible
      this.pendingStateForBatch = -PENDING_DELTA_FLUSH;
    }
  }

  /**
   * Returns true if this entry has been marked as pending for flush to copies
   * on other nodes (for replicated region or bucket region).
   * 
   * @see TXStateProxy#flushPendingOps
   */
  protected final boolean isPendingForBatch() {
    return this.pendingStateForBatch > 0;
  }

  /**
   * Returns true if this entry is pending for flush to other copies with full
   * value (rather than delta) created in this TX itself.
   * 
   * @see #isPendingForBatch()
   */
  protected final boolean isPendingFullValueForBatch() {
    return this.pendingStateForBatch == PENDING_FULL_VALUE_FLUSH
        || this.pendingDelta == null;
  }

  /**
   * Returns true if this entry is pending for flush to other copies with delta
   * value (rather than full value) since the entry either already existed in
   * the region or the full value created in this TX has been flushed before.
   * 
   * @see #isPendingForBatch()
   */
  protected final boolean isPendingDeltaForBatch() {
    return this.pendingStateForBatch == PENDING_DELTA_FLUSH
        && this.pendingDelta != null;
  }

  /**
   * Returns true if the transaction state has this entry existing "locally".
   * Returns false if the transaction is going to remove this entry.
   */
  public final boolean existsLocally() {
    if (this.op > OP_D_DESTROY) {
      return true;
    }
    if (isDirty()) {
      return false;
    }
    return !Token.isRemoved(this.originalVersionId);
  }

  /**
   * Returns true if the transaction state has this entry existing "locally".
   * Returns false if the transaction is going to remove this entry.
   * 
   * @param checkAndClearGIIEntry
   *          if true then also check if this entry is received from GII and
   *          clear the GII flag
   */
  public final boolean existsLocally(boolean checkAndClearGIIEntry) {
    if (this.op > OP_D_DESTROY) {
      return true;
    }
    if (isDirty()) {
      if (!checkAndClearGIIEntry || !this.fromGII) {
        return false;
      }
    }
    return !Token.isRemoved(this.originalVersionId);
  }

  public final boolean checkAndClearGIIEntry() {
    if (this.fromGII) {
      this.fromGII = false;
      return true;
    }
    else {
      return false;
    }
  }

  protected final boolean isOpReadOnly() {
    return (this.op == OP_READ_ONLY);
  }

  protected final boolean isOpNull() {
    return (this.op == OP_NULL);
  }

  protected final boolean isOpFlaggedForRead() {
    return (this.op == OP_FLAG_FOR_READ);
  }

  protected final void setOpFlagForRead() {
    this.op = OP_FLAG_FOR_READ;
  }

  private final boolean isOpLocalDestroy() {
    return this.op >= OP_L_DESTROY && this.op <= OP_D_INVALIDATE_LD;
  }

  private final boolean isOpLocalInvalidate() {
    return this.op >= OP_L_INVALIDATE && this.op <= OP_NLOAD_CREATE_LI
        && this.op != OP_D_INVALIDATE;
  }

  /**
   * Return true if this transaction has completely invalidated or destroyed the
   * value of this entry in the entire distributed system. Return false if a
   * netsearch should be done.
   */
  public final boolean noValueInSystem() {
    if (this.op == OP_D_DESTROY || this.op == OP_D_INVALIDATE_LD
        || this.op == OP_D_INVALIDATE) {
      return true;
    }
    else if (getNearSidePendingValue() == Token.INVALID) {
      // Note that we are not interested in LOCAL_INVALID
      return (this.op >= OP_CREATE_LD && this.op != OP_L_INVALIDATE
          && this.op != OP_SEARCH_CREATE && this.op != OP_LOCAL_CREATE
          && this.op != OP_SEARCH_PUT);
    }
    return false;
  }

  /**
   * Returns true if the transaction state has this entry existing locally and
   * has a valid local value. Returns false if the transaction is going to
   * remove this entry or its local value is invalid.
   */
  public final boolean isLocallyValid(boolean isProxy) {
    if (this.op <= OP_NULL) {
      if (isProxy) {
        // If it is a proxy that consider it locally valid
        // since we don't have any local committed state
        return true;
      }
      else {
        return !Token.isInvalidOrRemoved(this.originalVersionId);
      }
    }
    else {
      return this.op >= OP_CREATE
          && !Token.isInvalid(getNearSidePendingValue());
    }
  }

  @Retained
  public final Object getValueInVM(Object key)
      throws EntryNotFoundException {
    if (!existsLocally()) {
      throw new EntryNotFoundException(String.valueOf(key));
    }
    return getRetainedValueInTXOrRegion();
  }

  /**
   * @return the value, or null if the value does not exist in the cache,
   *         Token.INVALID or Token.LOCAL_INVALID if the value is invalid
   */
  public final Object getValue(Region<?, ?> r) {
    if (!existsLocally()) {
      return null;
    }
    Object v = getNearSidePendingValue();
    if (v != null && v instanceof CachedDeserializable) {
      // The only reason we would is if we do a read
      // in a transaction and the initial op that created the TXEntryState
      // did not call getDeserializedValue.
      // If a write op is done then the pendingValue does not belong to
      // the cache yet so we do not need the RegionEntry.
      // If we do need the RegionEntry then TXEntryState
      // will need to be changed to remember the RegionEntry it is created with.
      // [sumedh] now we do remember the RegionEntry since it is obtained at
      // the initial lock
      if (r == null) {
        r = this.txRegionState.region;
      }
      v = ((CachedDeserializable)v).getDeserializedValue(r, this.regionEntry);
    }
    return v;
  }

  final Object getDeserializedValue(LocalRegion dataRegion, final Object v,
      final boolean preferCD) {
    if (!preferCD && (v instanceof CachedDeserializable)) {
      // The only reason we would is if we do a read
      // in a transaction and the initial op that created the TXEntryState
      // did not call getDeserializedValue.
      // If a write op is done then the pendingValue does not belong to
      // the cache yet so we do not need the RegionEntry.
      // If we do need the RegionEntry then TXEntryState
      // will need to be changed to remember the RegionEntry it is created with.
      // [sumedh] now we do remember the RegionEntry since it is obtained at
      // the initial lock
      if (dataRegion == null) {
        dataRegion = this.txRegionState.region;
      }
      return ((CachedDeserializable)v)
          .getDeserializedValue(dataRegion, this.regionEntry);
    }
    return v;
  }

  protected final boolean isOpCreate() {
    return this.op >= OP_CREATE_LI && this.op <= OP_LOCAL_CREATE;
  }

  protected final boolean isOpPut() {
    return this.op >= OP_PUT;
  }

  /*
  private final boolean isOpInvalidate() {
    // Note that OP_CREATE_LI, OP_LLOAD_CREATE_LI, and OP_NLOAD_CREATE_LI
    // do not return true here because they are actually creates
    // with a value of LOCAL_INVALID locally and some other value remotely.
    return this.op <= OP_D_INVALIDATE && this.op >= OP_L_INVALIDATE;
  }
  */

  protected final boolean isOpDestroy() {
    return this.op <= OP_D_DESTROY && this.op >= OP_L_DESTROY;
  }

  protected static final boolean isOpDestroy(final byte op) {
    return op <= OP_D_DESTROY && op >= OP_L_DESTROY;
  }

  protected final boolean isOpDestroyEvent(LocalRegion r) {
    // Note that if the region is a proxy then we go ahead and distribute
    // the destroy because we can't eliminate it based on committed state
    return isOpDestroy()
        && (r.isProxy() || !Token.isRemoved(this.originalVersionId));
  }

  /**
   * Returns true if this operation has an event for the tx listener
   * 
   * @since 5.0
   */
  protected final boolean isOpAnyEvent() {
    //return isOpPutEvent() || isOpCreateEvent() || isOpInvalidateEvent()
    //    || isOpDestroyEvent(this.txRegionState.region);
    // return if any operation but check if entry created in TX itself got
    // destroyed (except if it is an empty region since then we have to
    // distribute events in any case)
    return this.op > OP_NULL
        && (!isOpDestroy() || !wasCreatedByTX() || getBaseRegion().isProxy());
  }

  protected final String opToString() {
    return opToString(this.op);
  }

  private final String opToString(byte opCode) {
    switch (opCode) {
    case OP_NULL:
      return "OP_NULL";
    case OP_LOCK_FOR_UPDATE:
      return "OP_LOCK_FOR_UPDATE";
    case OP_READ_ONLY:
      return "OP_READ_ONLY";
    case OP_FLAG_FOR_READ:
      return "OP_FLAG_FOR_READ";
    case OP_L_DESTROY:
      return "OP_L_DESTROY";
    case OP_CREATE_LD:
      return "OP_CREATE_LD";
    case OP_LLOAD_CREATE_LD:
      return "OP_LLOAD_CREATE_LD";
    case OP_NLOAD_CREATE_LD:
      return "OP_NLOAD_CREATE_LD";
    case OP_PUT_LD:
      return "OP_PUT_LD";
    case OP_LLOAD_PUT_LD:
      return "OP_LLOAD_PUT_LD";
    case OP_NLOAD_PUT_LD:
      return "OP_NLOAD_PUT_LD";
    case OP_D_INVALIDATE_LD:
      return "OP_D_INVALIDATE_LD";
    case OP_D_DESTROY:
      return "OP_D_DESTROY";
    case OP_L_INVALIDATE:
      return "OP_L_INVALIDATE";
    case OP_PUT_LI:
      return "OP_PUT_LI";
    case OP_LLOAD_PUT_LI:
      return "OP_LLOAD_PUT_LI";
    case OP_NLOAD_PUT_LI:
      return "OP_NLOAD_PUT_LI";
    case OP_D_INVALIDATE:
      return "OP_D_INVALIDATE";
    case OP_CREATE_LI:
      return "OP_CREATE_LI";
    case OP_LLOAD_CREATE_LI:
      return "OP_LLOAD_CREATE_LI";
    case OP_NLOAD_CREATE_LI:
      return "OP_NLOAD_CREATE_LI";
    case OP_CREATE:
      return "OP_CREATE";
    case OP_SEARCH_CREATE:
      return "OP_SEARCH_CREATE";
    case OP_LLOAD_CREATE:
      return "OP_LLOAD_CREATE";
    case OP_NLOAD_CREATE:
      return "OP_NLOAD_CREATE";
    case OP_LOCAL_CREATE:
      return "OP_LOCAL_CREATE";
    case OP_PUT:
      return "OP_PUT";
    case OP_SEARCH_PUT:
      return "OP_SEARCH_PUT";
    case OP_LLOAD_PUT:
      return "OP_LLOAD_PUT";
    case OP_NLOAD_PUT:
      return "OP_NLOAD_PUT";
    default:
      return "<unhandled op " + opCode + " >";
    }
  }

  /**
   * Returns an Operation instance that matches what the transactional operation
   * done on this entry in the cache the the transaction was performed in.
   */
  protected final Operation getNearSideOperation() {
    switch (this.op) {
    case OP_NULL:
    case OP_READ_ONLY:
    case OP_LOCK_FOR_UPDATE:
    case OP_FLAG_FOR_READ:
      return null;
    case OP_L_DESTROY:
      return Operation.LOCAL_DESTROY;
    case OP_CREATE_LD:
      return Operation.LOCAL_DESTROY;
    case OP_LLOAD_CREATE_LD:
      return Operation.LOCAL_DESTROY;
    case OP_NLOAD_CREATE_LD:
      return Operation.LOCAL_DESTROY;
    case OP_PUT_LD:
      return Operation.LOCAL_DESTROY;
    case OP_LLOAD_PUT_LD:
      return Operation.LOCAL_DESTROY;
    case OP_NLOAD_PUT_LD:
      return Operation.LOCAL_DESTROY;
    case OP_D_INVALIDATE_LD:
      return Operation.LOCAL_DESTROY;
    case OP_D_DESTROY:
      return Operation.DESTROY;
    case OP_L_INVALIDATE:
      return Operation.LOCAL_INVALIDATE;
    case OP_PUT_LI:
      return Operation.LOCAL_INVALIDATE;
    case OP_LLOAD_PUT_LI:
      return Operation.LOCAL_INVALIDATE;
    case OP_NLOAD_PUT_LI:
      return Operation.LOCAL_INVALIDATE;
    case OP_D_INVALIDATE:
      return Operation.INVALIDATE;
    case OP_CREATE_LI:
      return getCreateOperation();
    case OP_LLOAD_CREATE_LI:
      return getCreateOperation();
    case OP_NLOAD_CREATE_LI:
      return getCreateOperation();
    case OP_CREATE:
      return getCreateOperation();
    case OP_SEARCH_CREATE:
      return Operation.SEARCH_CREATE;
    case OP_LLOAD_CREATE:
      return Operation.LOCAL_LOAD_CREATE;
    case OP_NLOAD_CREATE:
      return Operation.NET_LOAD_CREATE;
    case OP_LOCAL_CREATE:
      return getCreateOperation();
    case OP_PUT:
      return getUpdateOperation();
    case OP_SEARCH_PUT:
      return Operation.SEARCH_UPDATE;
    case OP_LLOAD_PUT:
      return Operation.LOCAL_LOAD_UPDATE;
    case OP_NLOAD_PUT:
      return Operation.NET_LOAD_CREATE;
    default:
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_UNHANDLED_OP_0.toLocalizedString(this.op));
    }
  }

  private void generateNearSideEventOffset(TXState txState) {
    assert this.nearSideEventOffset == -1: "expected an uninitialized "
        + "event offset for " + this;

    this.nearSideEventOffset = txState.generateEventOffset();
  }

  /*
  private int getFarSideEventOffset() {
    assert this.nearSideEventOffset != -1;
    return this.nearSideEventOffset;
  }
  */

  private static EventID createEventID(TXState txState, int offset) {
    return new EventID(txState.getBaseMembershipId(),
                       txState.getBaseThreadId(),
                       txState.getBaseSequenceId() + offset);
  }
  /**
   * Calculate (if farside has not already done so) and return then eventID
   * to use for near side op applications.
   * @since 5.7
   */
  private EventID getNearSideEventId(TXState txState) {
    assert this.nearSideEventOffset != -1: "expected initialized "
        + "event offset for " + this;
    return createEventID(txState, this.nearSideEventOffset);
  }

  /**
   * Calculate and return the event offset for this entry's farSide operation.
   * @since 5.7
   */
  protected final void generateEventOffsets(final TXState txState) {
    if (TXStateProxy.LOG_FINE) {
      getDataRegion().getLogWriterI18n().info(LocalizedStrings.DEBUG,
          "generating event offsets for "
              + (TXStateProxy.LOG_FINEST ? toString() : shortToString(false)));
    }
    generateNearSideEventOffset(txState);
    /*
    switch (this.op) {
    case OP_NULL:
    case OP_READ_ONLY:
    case OP_LOCK_FOR_UPDATE:
    case OP_FLAG_FOR_READ:
      // no eventIds needed
      break;
    case OP_L_DESTROY:
      generateNearSideOnlyEventOffset(txState);
      break;
    case OP_CREATE_LD:
      generateBothEventOffsets(txState);
      break;
    case OP_LLOAD_CREATE_LD:
      generateBothEventOffsets(txState);
      break;
    case OP_NLOAD_CREATE_LD:
      generateBothEventOffsets(txState);
      break;
    case OP_PUT_LD:
      generateBothEventOffsets(txState);
      break;
    case OP_LLOAD_PUT_LD:
      generateBothEventOffsets(txState);
      break;
    case OP_NLOAD_PUT_LD:
      generateBothEventOffsets(txState);
      break;
    case OP_D_INVALIDATE_LD:
      generateBothEventOffsets(txState);
      break;
    case OP_D_DESTROY:
      generateSharedEventOffset(txState);
      break;
    case OP_L_INVALIDATE:
      generateNearSideOnlyEventOffset(txState);
      break;
    case OP_PUT_LI:
      generateBothEventOffsets(txState);
      break;
    case OP_LLOAD_PUT_LI:
      generateBothEventOffsets(txState);
      break;
    case OP_NLOAD_PUT_LI:
      generateBothEventOffsets(txState);
      break;
    case OP_D_INVALIDATE:
      generateSharedEventOffset(txState);
      break;
    case OP_CREATE_LI:
      generateBothEventOffsets(txState);
      break;
    case OP_LLOAD_CREATE_LI:
      generateBothEventOffsets(txState);
      break;
    case OP_NLOAD_CREATE_LI:
      generateBothEventOffsets(txState);
      break;
    case OP_CREATE:
      generateSharedEventOffset(txState);
      break;
    case OP_SEARCH_CREATE:
      generateNearSideOnlyEventOffset(txState);
      break;
    case OP_LLOAD_CREATE:
      generateSharedEventOffset(txState);
      break;
    case OP_NLOAD_CREATE:
      generateSharedEventOffset(txState);
      break;
    case OP_LOCAL_CREATE:
      generateNearSideOnlyEventOffset(txState);
      break;
    case OP_PUT:
      generateSharedEventOffset(txState);
      break;
    case OP_SEARCH_PUT:
      generateNearSideOnlyEventOffset(txState);
      break;
    case OP_LLOAD_PUT:
      generateSharedEventOffset(txState);
      break;
    case OP_NLOAD_PUT:
      generateSharedEventOffset(txState);
      break;
    default:
      throw new IllegalStateException("<unhandled op " + this.op + " >");
    }
    */
  }

  /*
  /**
   * Gets the operation code for the operation done on this entry in caches
   * remote from the originator of the tx (i.e. the "far side").
   * 
   * @return null if no far side operation
   *
  private final Operation getFarSideOperation() {
    switch (this.op) {
    case OP_NULL:
    case OP_READ_ONLY:
      return null;
    case OP_L_DESTROY:
      return null;
    case OP_CREATE_LD:
      return getCreateOperation();
    case OP_LLOAD_CREATE_LD:
      return Operation.LOCAL_LOAD_CREATE;
    case OP_NLOAD_CREATE_LD:
      return Operation.NET_LOAD_CREATE;
    case OP_PUT_LD:
      return getUpdateOperation();
    case OP_LLOAD_PUT_LD:
      return Operation.LOCAL_LOAD_UPDATE;
    case OP_NLOAD_PUT_LD:
      return Operation.NET_LOAD_UPDATE;
    case OP_D_INVALIDATE_LD:
      return Operation.INVALIDATE;
    case OP_D_DESTROY:
      return Operation.DESTROY;
    case OP_L_INVALIDATE:
      return null;
    case OP_PUT_LI:
      return getUpdateOperation();
    case OP_LLOAD_PUT_LI:
      return Operation.LOCAL_LOAD_UPDATE;
    case OP_NLOAD_PUT_LI:
      return Operation.NET_LOAD_UPDATE;
    case OP_D_INVALIDATE:
      return Operation.INVALIDATE;
    case OP_CREATE_LI:
      return getCreateOperation();
    case OP_LLOAD_CREATE_LI:
      return Operation.LOCAL_LOAD_CREATE;
    case OP_NLOAD_CREATE_LI:
      return Operation.NET_LOAD_CREATE;
    case OP_CREATE:
      return getCreateOperation();
    case OP_SEARCH_CREATE:
      return null;
    case OP_LLOAD_CREATE:
      return Operation.LOCAL_LOAD_CREATE;
    case OP_NLOAD_CREATE:
      return Operation.NET_LOAD_CREATE;
    case OP_LOCAL_CREATE:
      return null;
    case OP_PUT:
      return getUpdateOperation();
    case OP_SEARCH_PUT:
      return null;
    case OP_LLOAD_PUT:
      return Operation.LOCAL_LOAD_UPDATE;
    case OP_NLOAD_PUT:
      return Operation.NET_LOAD_UPDATE;
    default:
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_UNHANDLED_OP_0.toLocalizedString(this.op));
    }
  }
  */

  //  private void dumpOp() {
  //    System.out.println("DEBUG: op=" + opToString()
  //                       + " destroy=" + this.destroy
  //                       + " isDis=" + isLocalEventDistributed());
  //    System.out.flush();
  //  }

  protected final EntryEventImpl getEvent(final TXState txs,
      final boolean isCohort, final InternalDistributedMember myId) {
    // dumpOp();
    //TODO:ASIF : Should we generate EventID ? At this point not generating
    final LocalRegion eventRegion = getBaseRegion();
    final EntryEventImpl result = EntryEventImpl.create(eventRegion,
        getNearSideOperation(), this.regionKey, getNearSidePendingValue(),
        getCallbackArgument(), false, myId);
    // OFFHEAP: freeOffHeapResources on this event is called from TXEvent.freeOffHeapResources.
    if (this.destroy == DESTROY_NONE || isOpDestroy()) {
      result.setOldValue(this.originalVersionId);
    }
    // Use coordinator to determine if the originRemote flag should be set.
    // The event can come to coordinator from itself in nested function
    // execution so this flag is more reliable -- see bug #41498.
    result.setOriginRemote(isCohort);
    result.setTXState(txs);
    return result;
  }

  /**
   * @return true if invalidate was done
   */
  public final boolean invalidate(EntryEventImpl event, Operation op)
      throws EntryNotFoundException {
    LocalRegion lr = event.getRegion();
    boolean isProxy = lr.isProxy();
    if (!isLocallyValid(isProxy)) {
      return false;
    }

    performOp(adviseInvalidateOp(event, op), event);
    return true;
  }

  protected final byte adviseInvalidateOp(EntryEventImpl event, Operation op) {
    if (event.isLocalInvalid() || op.isLocal()) {
      return adviseOp(OP_L_INVALIDATE);
    }
    else {
      return adviseOp(OP_D_INVALIDATE);
    }
  }

  @SuppressWarnings("unchecked")
  protected final void destroyPart1(final EntryEventImpl event,
      final boolean cacheWrite) throws CacheWriterException,
      EntryNotFoundException, TimeoutException {
    if (cacheWrite) {
      final LocalRegion lr = event.getRegion();
      @SuppressWarnings("rawtypes")
      final CacheWriter cWriter = lr.basicGetWriter();
      if (cWriter != null) {
        // release the TXRegionState lock else we can end up in deadlocks
        final TXRegionState txrs = this.txRegionState;
        txrs.unlock();
        // @todo mitch Ask Bruce if we should perform net writes
        try {
          cWriter.beforeDestroy(event);
        } catch (CacheWriterException cwe) {
          throw new TXCacheWriterException(cwe, this.op, this.destroy,
              this.bulkOp, this.pendingValue, this.pendingDelta);
        } finally {
          // lock in finally block to avoid unlocking twice at higher level in
          // case of exceptions
          txrs.lock();
        }
      }
    }
  }

  protected final void destroyPart2(final EntryEventImpl event)
      throws EntryNotFoundException, TimeoutException {
    final byte advisedOp = adviseDestroyOp(event.getOperation());
    if (advisedOp > OP_NULL) {
      performOp(advisedOp, event);
    }
    else {
      // set the callbackArg neverthless (e.g. if this is being created for
      // locking) since it may contain GemFireXD routing object for example
      // (e.g. #44074)
      event.putTXEntryCallbackArg(this);
    }
  }

  protected final byte adviseDestroyOp(Operation op) {
    byte destroyOp;
    if (op.isDistributed()) {
      destroyOp = OP_D_DESTROY;
      this.destroy = DESTROY_DISTRIBUTED;
    }
    else {
      destroyOp = OP_L_DESTROY;
      if (this.destroy != DESTROY_DISTRIBUTED) {
        this.destroy = DESTROY_LOCAL;
      }
    }
    destroyOp = adviseOp(destroyOp);
    if (destroyOp <= OP_NULL) {
      this.destroy = DESTROY_NONE;
    }
    return destroyOp;
  }

  protected void destroyPart3(final LocalRegion dataRegion,
      final EntryEventImpl event) throws EntryNotFoundException,
      TimeoutException {
  }

  /**
   * @param event
   *          the event object for this operation, with the exception that the
   *          oldValue parameter is not yet filled in. The oldValue will be
   *          filled in by this operation.
   * 
   * @param ifNew
   *          true if this operation must not overwrite an existing key
   */
  @SuppressWarnings("unchecked")
  protected final byte basicPutPart1(final EntryEventImpl event,
      final boolean ifNew, final boolean cacheWrite)
      throws CacheWriterException, TimeoutException {
    final Operation op = event.getOperation();
    byte putOp;
    if (ifNew) {
      putOp = OP_CREATE;
      if (op.isUpdate()) {
        event.makeCreate();
      }
    }
    else {
      putOp = OP_PUT;
      if (op.isCreate()) {
        event.makeUpdate();
      }
    }

    if (cacheWrite && !op.isNetSearch()) {
      final LocalRegion lr = event.getRegion();
      @SuppressWarnings("rawtypes")
      final CacheWriter cWriter = lr.basicGetWriter();
      // @todo mitch Ask Bruce if we should perform net writes
      if (cWriter != null) {
        // release the TXRegionState lock else we can end up in deadlocks
        final TXRegionState txrs = this.txRegionState;
        txrs.unlock();
        setEventOldValue(event);
        try {
          if (ifNew) {
            cWriter.beforeCreate(event);
          }
          else {
            cWriter.beforeUpdate(event);
          }
        } catch (CacheWriterException cwe) {
          throw new TXCacheWriterException(cwe, this.op, this.destroy,
              this.bulkOp, this.pendingValue, this.pendingDelta);
        } finally {
          // lock in finally block to avoid unlocking twice at higher level in
          // case of exceptions
          txrs.lock();
        }
      }
    }
    return putOp;
  }

  protected final void basicPutPart2(final EntryEventImpl event, byte putOp)
      throws TimeoutException {
    // oldValue has already been set during the operation
    performOp(advisePutOp(putOp, event.getOperation()), event);
  }

  protected final byte advisePutOp(byte putOp, Operation op) {
    if (op.isNetSearch()) {
      putOp += (byte)(OP_SEARCH_PUT - OP_PUT);
    }
    else if (op.isLocalLoad()) {
      putOp += (byte)(OP_LLOAD_PUT - OP_PUT);
    }
    else if (op.isNetLoad()) {
      putOp += (byte)(OP_NLOAD_PUT - OP_PUT);
    }
    this.bulkOp = op.isPutAll();
    return adviseOp(putOp);
  }

  protected void basicPutPart3(final LocalRegion dataRegion,
      final EntryEventImpl event, final boolean ifNew) throws TimeoutException {
  }

  private final void setEventOldValue(final EntryEventImpl event) {
    if (event != null && !event.hasOldValue()) { // Set event old value
      event.setTXOldValue(getValueInTXOrRegion());
    }
  }

  /**
   * Get the value in TX if dirty else get the value in region. Returns null for
   * destroyed entry and relevant Token object for invalidated entry.
   */
  @Unretained  
  protected final Object getValueInTXOrRegion() {
    if (isDirty()) {
      return getNearSidePendingValue();
    }
    else {
      @Unretained final Object oldVal = this.originalVersionId;
      if (!Token.isRemoved(oldVal)) {        
        return oldVal;
      }
      return null;
    }
  }

  @Retained
  public final Object getRetainedValueInTXOrRegion() {
    @Unretained Object val = this.getValueInTXOrRegion();
    if (val instanceof Chunk) {
      if (!((Chunk) val).retain()) {
        throw new IllegalStateException("Could not retain OffHeap value=" + val);
      }
    }
    return val;
  }

  /**
   * Perform operation algebra
   * 
   * @return false if operation was not done
   */
  private final byte adviseOp(final byte requestedOpCode) {
    byte advisedOpCode = OP_NULL;
    // Determine new operation based on
    // the requested operation 'requestedOpCode' and
    // the previous operation 'this.op'
    switch (requestedOpCode) {
    case OP_L_DESTROY:
      switch (this.op) {
      case OP_NULL:
      case OP_READ_ONLY:
      case OP_LOCK_FOR_UPDATE:
      case OP_FLAG_FOR_READ:
        advisedOpCode = requestedOpCode;
        break;
      case OP_L_DESTROY:
      case OP_CREATE_LD:
      case OP_LLOAD_CREATE_LD:
      case OP_NLOAD_CREATE_LD:
      case OP_PUT_LD:
      case OP_LLOAD_PUT_LD:
      case OP_NLOAD_PUT_LD:
      case OP_D_INVALIDATE_LD:
      case OP_D_DESTROY:
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_UNEXPECTED_CURRENT_OP_0_FOR_REQUESTED_OP_1
                .toLocalizedString(new Object[] { opToString(),
                    opToString(requestedOpCode) }));
      case OP_L_INVALIDATE:
        advisedOpCode = requestedOpCode;
        break;
      case OP_PUT_LI:
        advisedOpCode = OP_PUT_LD;
        break;
      case OP_LLOAD_PUT_LI:
        advisedOpCode = OP_LLOAD_PUT_LD;
        break;
      case OP_NLOAD_PUT_LI:
        advisedOpCode = OP_NLOAD_PUT_LD;
        break;
      case OP_D_INVALIDATE:
        advisedOpCode = OP_D_INVALIDATE_LD;
        break;
      case OP_CREATE_LI:
        advisedOpCode = OP_CREATE_LD;
        break;
      case OP_LLOAD_CREATE_LI:
        advisedOpCode = OP_LLOAD_CREATE_LD;
        break;
      case OP_NLOAD_CREATE_LI:
        advisedOpCode = OP_NLOAD_CREATE_LD;
        break;
      case OP_CREATE:
        advisedOpCode = OP_CREATE_LD;
        break;
      case OP_SEARCH_CREATE:
        advisedOpCode = requestedOpCode;
        break;
      case OP_LLOAD_CREATE:
        advisedOpCode = OP_LLOAD_CREATE_LD;
        break;
      case OP_NLOAD_CREATE:
        advisedOpCode = OP_NLOAD_CREATE_LD;
        break;
      case OP_LOCAL_CREATE:
        advisedOpCode = requestedOpCode;
        break;
      case OP_PUT:
        advisedOpCode = OP_PUT_LD;
        break;
      case OP_SEARCH_PUT:
        advisedOpCode = requestedOpCode;
        break;
      case OP_LLOAD_PUT:
        advisedOpCode = OP_LLOAD_PUT_LD;
        break;
      case OP_NLOAD_PUT:
        advisedOpCode = OP_NLOAD_PUT_LD;
        break;
      default:
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_UNHANDLED_0.toLocalizedString(opToString()));
      }
      break;
    case OP_D_DESTROY:
      if (isOpDestroy() && !this.fromGII) {
        Assert.fail("Transactional destroy assertion op=" + this.op);
      }
      advisedOpCode = requestedOpCode;
      break;
    case OP_L_INVALIDATE:
      switch (this.op) {
      case OP_NULL:
      case OP_READ_ONLY:
        advisedOpCode = requestedOpCode;
        break;
      case OP_L_DESTROY:
      case OP_CREATE_LD:
      case OP_LLOAD_CREATE_LD:
      case OP_NLOAD_CREATE_LD:
      case OP_PUT_LD:
      case OP_LLOAD_PUT_LD:
      case OP_NLOAD_PUT_LD:
      case OP_D_INVALIDATE_LD:
      case OP_D_DESTROY:
      case OP_L_INVALIDATE:
      case OP_PUT_LI:
      case OP_LLOAD_PUT_LI:
      case OP_NLOAD_PUT_LI:
      case OP_D_INVALIDATE:
      case OP_CREATE_LI:
      case OP_LLOAD_CREATE_LI:
      case OP_NLOAD_CREATE_LI:
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_UNEXPECTED_CURRENT_OP_0_FOR_REQUESTED_OP_1
                .toLocalizedString(new Object[] { opToString(),
                    opToString(requestedOpCode) }));
      case OP_CREATE:
        advisedOpCode = OP_CREATE_LI;
        break;
      case OP_SEARCH_CREATE:
        advisedOpCode = OP_LOCAL_CREATE;
        // pendingValue will be set to LOCAL_INVALID
        break;
      case OP_LLOAD_CREATE:
        advisedOpCode = OP_LLOAD_CREATE_LI;
        break;
      case OP_NLOAD_CREATE:
        advisedOpCode = OP_NLOAD_CREATE_LI;
        break;
      case OP_LOCAL_CREATE:
        advisedOpCode = OP_LOCAL_CREATE;
        break;
      case OP_PUT:
        advisedOpCode = OP_PUT_LI;
        break;
      case OP_SEARCH_PUT:
        advisedOpCode = requestedOpCode;
        break;
      case OP_LLOAD_PUT:
        advisedOpCode = OP_LLOAD_PUT_LI;
        break;
      case OP_NLOAD_PUT:
        advisedOpCode = OP_NLOAD_PUT_LI;
        break;
      default:
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_UNHANDLED_0.toLocalizedString(opToString()));
      }
      break;
    case OP_D_INVALIDATE:
      switch (this.op) {
      case OP_NULL:
      case OP_READ_ONLY:
        advisedOpCode = requestedOpCode;
        break;
      case OP_L_DESTROY:
      case OP_CREATE_LD:
      case OP_LLOAD_CREATE_LD:
      case OP_NLOAD_CREATE_LD:
      case OP_PUT_LD:
      case OP_LLOAD_PUT_LD:
      case OP_NLOAD_PUT_LD:
      case OP_D_INVALIDATE_LD:
      case OP_D_DESTROY:
      case OP_L_INVALIDATE:
      case OP_PUT_LI:
      case OP_LLOAD_PUT_LI:
      case OP_NLOAD_PUT_LI:
      case OP_D_INVALIDATE:
      case OP_CREATE_LI:
      case OP_LLOAD_CREATE_LI:
      case OP_NLOAD_CREATE_LI:
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_UNEXPECTED_CURRENT_OP_0_FOR_REQUESTED_OP_1
                .toLocalizedString(new Object[] { opToString(),
                    opToString(requestedOpCode) }));
      case OP_CREATE:
        advisedOpCode = OP_CREATE;
        // pendingValue will be set to INVALID turning it into create invalid
        break;
      case OP_SEARCH_CREATE:
        advisedOpCode = OP_LOCAL_CREATE;
        // pendingValue will be set to INVALID to indicate dinvalidate
        break;
      case OP_LLOAD_CREATE:
        advisedOpCode = OP_CREATE;
        // pendingValue will be set to INVALID turning it into create invalid
        break;
      case OP_NLOAD_CREATE:
        advisedOpCode = OP_CREATE;
        // pendingValue will be set to INVALID turning it into create invalid
        break;
      case OP_LOCAL_CREATE:
        advisedOpCode = OP_LOCAL_CREATE;
        // pendingValue will be set to INVALID to indicate dinvalidate
        break;
      case OP_PUT:
      case OP_SEARCH_PUT:
      case OP_LLOAD_PUT:
      case OP_NLOAD_PUT:
        advisedOpCode = requestedOpCode;
        break;
      default:
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_UNHANDLED_0.toLocalizedString(opToString()));
      }
      break;
    case OP_CREATE:
    case OP_SEARCH_CREATE:
    case OP_LLOAD_CREATE:
    case OP_NLOAD_CREATE:
      advisedOpCode = requestedOpCode;
      break;
    case OP_PUT:
      switch (this.op) {
      case OP_CREATE:
      case OP_SEARCH_CREATE:
      case OP_LLOAD_CREATE:
      case OP_NLOAD_CREATE:
      case OP_LOCAL_CREATE:
      case OP_CREATE_LI:
      case OP_LLOAD_CREATE_LI:
      case OP_NLOAD_CREATE_LI:
      case OP_CREATE_LD:
      case OP_LLOAD_CREATE_LD:
      case OP_NLOAD_CREATE_LD:
      case OP_PUT_LD:
      case OP_LLOAD_PUT_LD:
      case OP_NLOAD_PUT_LD:
      case OP_D_INVALIDATE_LD:
      case OP_L_DESTROY:
      case OP_D_DESTROY:
        advisedOpCode = OP_CREATE;
        break;
      default:
        advisedOpCode = requestedOpCode;
        break;
      }
      break;
    case OP_SEARCH_PUT:
      switch (this.op) {
      case OP_NULL:
      case OP_READ_ONLY:
        advisedOpCode = requestedOpCode;
        break;
      case OP_L_INVALIDATE:
        advisedOpCode = requestedOpCode;
        break;
      // The incoming search put value should match
      // the pendingValue from the previous tx operation.
      // So it is ok to simply drop the _LI from the op
      case OP_PUT_LI:
        advisedOpCode = OP_PUT;
        break;
      case OP_LLOAD_PUT_LI:
        advisedOpCode = OP_LLOAD_PUT;
        break;
      case OP_NLOAD_PUT_LI:
        advisedOpCode = OP_NLOAD_PUT;
        break;
      case OP_CREATE_LI:
        advisedOpCode = OP_CREATE;
        break;
      case OP_LLOAD_CREATE_LI:
        advisedOpCode = OP_LLOAD_CREATE;
        break;
      case OP_NLOAD_CREATE_LI:
        advisedOpCode = OP_NLOAD_CREATE;
        break;
      default:
        // Note that OP_LOCAL_CREATE and OP_CREATE with invalid values
        // are not possible because they would cause the netsearch to
        // fail and we would do a load or a total miss.
        // Note that OP_D_INVALIDATE followed by OP_SEARCH_PUT is not
        // possible since the netsearch will alwsys "miss" in this case.
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_PREVIOUS_OP_0_UNEXPECTED_FOR_REQUESTED_OP_1
                .toLocalizedString(new Object[] { opToString(),
                    opToString(requestedOpCode) }));
      }
      break;
    case OP_LLOAD_PUT:
    case OP_NLOAD_PUT:
      switch (this.op) {
      case OP_NULL:
      case OP_READ_ONLY:
      case OP_L_INVALIDATE:
      case OP_PUT_LI:
      case OP_LLOAD_PUT_LI:
      case OP_NLOAD_PUT_LI:
      case OP_D_INVALIDATE:
        advisedOpCode = requestedOpCode;
        break;
      case OP_CREATE:
      case OP_LOCAL_CREATE:
      case OP_CREATE_LI:
      case OP_LLOAD_CREATE_LI:
      case OP_NLOAD_CREATE_LI:
        if (requestedOpCode == OP_LLOAD_PUT) {
          advisedOpCode = OP_LLOAD_CREATE;
        }
        else {
          advisedOpCode = OP_NLOAD_CREATE;
        }
        break;
      default:
        // note that other invalid states are covered by this default
        // case because they should have caused a OP_SEARCH_PUT
        // to be requested.
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_PREVIOUS_OP_0_UNEXPECTED_FOR_REQUESTED_OP_1
                .toLocalizedString(new Object[] { opToString(),
                    opToString(requestedOpCode) }));
      }
      break;
    default:
      throw new IllegalStateException(LocalizedStrings
          .TXEntryState_OPCODE_0_SHOULD_NEVER_BE_REQUESTED
              .toLocalizedString(opToString(requestedOpCode)));
    }
    return advisedOpCode;
  }

  private final void performOp(byte advisedOpCode, EntryEventImpl event) {
    if (TXStateProxy.LOG_FINE) {
      getDataRegion().getLogWriterI18n().info(LocalizedStrings.DEBUG,
          "performOp: advisedOpCode=" + advisedOpCode + ", event="
              + (TXStateProxy.LOG_FINEST ? event.toString()
                  : event.shortToString()) + ", pendingDelta="
              + this.pendingDelta + ", currentEntry="
              + (TXStateProxy.LOG_FINEST && this.regionEntry != null
                  ? this.regionEntry.toString()
                  : ArrayUtils.objectRefString(this.regionEntry)));
    }
    if (advisedOpCode > OP_NULL) {
      event.putValueTXEntry(this, advisedOpCode);
    }
    else if (event != null) {
      // set the callbackArg neverthless (e.g. if this is being created for
      //  locking) since it may contain GemFireXD routing object for example
      // (e.g. #44074)
      event.putTXEntryCallbackArg(this);
    }
    this.op = advisedOpCode;
    if (TXStateProxy.LOG_FINE) {
      getDataRegion().getLogWriterI18n().info(LocalizedStrings.DEBUG,
          "performOp: after op: " + shortToString());
    }
  }

  protected final void performOpForRegionEntry(final TXState txState,
      final EntryEventImpl eventTemplate, final int lockFlags,
      final boolean checkInitialized, final Boolean checkForTXFinish,
      final AbstractOperationMessage msg) {
    final TXRegionState txrs = this.txRegionState;
    final LocalRegion dataRegion = txrs.region;
    if (checkInitialized && !txrs.isInitializedAndLockGII()) {
      txrs.addPendingTXOpAndUnlockGII(this, lockFlags);
      return;
    }
    final LocalRegion region = getBaseRegion(dataRegion);
    final boolean checkValid = checkForTXFinish.booleanValue();
    txrs.lock();
    try {
      final Object currentEntry = txrs.readEntry(this.regionKey, checkValid);
      if (TXStateProxy.LOG_FINE) {
        if (TXStateProxy.LOG_FINEST) {
          region.getLogWriterI18n().info(LocalizedStrings.DEBUG,
              "performOpForRegionEntry: op=" + this.op + ", key="
                  + this.regionKey + ", pendingValue=" + this.pendingValue
                  + ", pendingDelta=" + this.pendingDelta + ", currentEntry="
                  + currentEntry);
        }
        else {
          region.getLogWriterI18n().info(LocalizedStrings.DEBUG,
              "performOpForRegionEntry: op=" + this.op + ", key="
                  + this.regionKey + ", pendingValue="
                  + ArrayUtils.objectRefString(this.pendingValue)
                  + ", pendingDelta=" + this.pendingDelta + ", currentEntry="
                  + ArrayUtils.objectRefString(currentEntry));
        }
      }
      boolean lockedForRead = false;
      if (currentEntry == null
          || (lockedForRead = (currentEntry instanceof AbstractRegionEntry))) {
        final LockingPolicy lockPolicy = txState.getLockingPolicy();
        final RegionEntry entry;
        if (isDirty() || this.op == OP_LOCK_FOR_UPDATE) {
          entry = region.lockRegionEntryForWrite(dataRegion, this.regionKey,
              lockPolicy, lockPolicy.getWriteLockMode(), txState.txId,
              lockFlags);
        }
        else {
          if (!isOpReadOnly()) {
            throw new IllegalStateException(
                LocalizedStrings.TXEntryState_OPCODE_0_SHOULD_NEVER_BE_REQUESTED
                    .toLocalizedString(opToString(this.op)));
          }
          entry = txState.basicGetEntryForLock(region, dataRegion,
              this.regionKey, "TXEntryState#performOpForRegionEntry", this.op);
          lockPolicy.lockForRead(entry, lockPolicy.getReadOnlyLockMode(),
              txState.txId, dataRegion, lockFlags, msg, false,
              LockingPolicy.NULL_READER);
        }
        Object val = dataRegion
            .getREValueForTXRead(entry, this.regionKey, null);
        if (val == null) {
          // check lastModified time to see if this entry was created for
          // locking only
          if (entry.isLockedForCreate()) {
            val = Token.REMOVED_PHASE2;
          }
          else {
            val = Token.REMOVED_PHASE1;
          }
        }
        //Release previous contained value
        release();
        this.originalVersionId = val;
        this.regionEntry = entry;
        applyBatchOperationOnNewEntry(txState, lockPolicy, txrs, region,
            dataRegion, val, entry, lockedForRead, checkValid, eventTemplate);
      }
      else {
        applyBatchOperationOnExistingEntry(txState, txrs, region, dataRegion,
            (TXEntryState)currentEntry, eventTemplate, lockFlags, msg);
      }
    } finally {
      txrs.unlock();
    }
  }

  protected void applyBatchOperationOnNewEntry(final TXState txState,
      final LockingPolicy lockPolicy, final TXRegionState txrs,
      final LocalRegion region, final LocalRegion dataRegion,
      final Object regionValue, final RegionEntry entry,
      final boolean lockedForRead, final boolean checkValid,
      final EntryEventImpl eventTemplate) {
    // this EntryState should go into the TXState list and TXRegionState mods
    if (lockedForRead) {
      // release the read lock since write lock has been acquired
      lockPolicy.releaseLock(entry, lockPolicy.getReadLockMode(), txState.txId,
          false, dataRegion);
    }
    final THashMapWithCreate entryMap = checkValid ? txrs.getEntryMap() : txrs
        .getInternalEntryMap();
    entryMap.put(this.regionKey, this);
    if (isDirty()) {
      updateForCommit(txState);
    }
  }

  protected void applyBatchOperationOnExistingEntry(final TXState txState,
      final TXRegionState txrs, final LocalRegion region,
      final LocalRegion dataRegion, final TXEntryState currentTXEntry,
      final EntryEventImpl eventTemplate, final int lockFlags,
      final AbstractOperationMessage msg) {
    // update the existing TXEntryState
    currentTXEntry.destroy = this.destroy;
    currentTXEntry.bulkOp = this.bulkOp;
    currentTXEntry.fromGII = this.fromGII;
    if (isDirty() || this.op == OP_LOCK_FOR_UPDATE) {
      if (currentTXEntry.isOpReadOnly()) {
        final LockingPolicy lockPolicy = txState.getLockingPolicy();
        final RegionEntry entry = currentTXEntry.regionEntry;
        // We cannot upgrade the lock from READ_ONLY to WRITE mode atomically
        // in all cases because others may also have the lock. However, if
        // there is only on READ_ONLY lock then this TX itself is the owner of
        // that and so allow the lock to be acquired in that case to have
        // atomic upgrade from READ_ONLY to WRITE mode.
        lockPolicy.acquireLock(entry, lockPolicy.getWriteLockMode(),
            AbstractRegionEntry.ALLOW_ONE_READ_ONLY_WITH_EX_SH | lockFlags,
            txState.txId, dataRegion, msg);
        // no need to check if entry has been removed after acquiring the
        // lock since READ_ONLY lock was already acquired that ensures that
        // no other TX can remove the entry
        currentTXEntry.op = this.op;
        // READ_ONLY lock has been already released by above acquireLock call
        //lockPolicy.releaseLock(entry, lockPolicy.getReadOnlyLockMode(),
        //    txState.txId, false, dataRegion);
        if (this.op == OP_LOCK_FOR_UPDATE) {
          // nothing more to be done for only locking
          return;
        }
      }
      else if (this.op == OP_LOCK_FOR_UPDATE) {
        // nothing more to be done for only locking
        return;
      }
      else {
        currentTXEntry.op = currentTXEntry.adviseOp(this.op);
      }
      currentTXEntry.setPendingValue(this.pendingValue, this.pendingDelta,
          region, currentTXEntry.op);
      // update entry in the list
      currentTXEntry.updateForCommit(txState);
    }
    else {
      // this should be the case of READ_ONLY; no change required to the
      // currentTXEntry op since it will be already at least READ_ONLY
      if (!isOpReadOnly()) {
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_OPCODE_0_SHOULD_NEVER_BE_REQUESTED
                .toLocalizedString(opToString(this.op)));
      }
    }
  }

  protected final void revert(final byte originalOp, final byte destroy,
      final boolean bulkOp, final Object originalValue,
      final Delta originalDelta) {
    this.op = originalOp;
    this.destroy = destroy;
    this.bulkOp = bulkOp;
    this.pendingValue = originalValue;
    this.pendingDelta = originalDelta;   
  }

  protected final void setReadOnlyOp() {
    this.op = OP_READ_ONLY;
  }

  protected final void setOpType(byte opType, Object callbackArg) {
    this.op = opType;
    if (callbackArg != null) {
      setCallbackArgument(callbackArg);
    }
  }

  protected final boolean entryCreatedForLock() {
    return this.originalVersionId == Token.REMOVED_PHASE2;
  }

  private final boolean didDestroy() {
    return this.destroy != DESTROY_NONE;
  }

  /*
  private final boolean didDistributedDestroy() {
    return this.destroy == DESTROY_DISTRIBUTED;
  }
  */

  /**
   * Returns the total number of modifications made by this transaction to this
   * entry. The result will be +1 for a create 1, a 0 for destroy of entry
   * created in TX itself and -1 for any other destroy.
   */
  protected final int entryCountMod() {
    switch (this.op) {
    case OP_L_DESTROY:
    case OP_CREATE_LD:
    case OP_LLOAD_CREATE_LD:
    case OP_NLOAD_CREATE_LD:
    case OP_PUT_LD:
    case OP_LLOAD_PUT_LD:
    case OP_NLOAD_PUT_LD:
    case OP_D_INVALIDATE_LD:
    case OP_D_DESTROY:
      if (entryCreatedForLock()) {
        return 0;
      }
      else {
        return -1;
      }
    case OP_CREATE_LI:
    case OP_LLOAD_CREATE_LI:
    case OP_NLOAD_CREATE_LI:
    case OP_CREATE:
    case OP_SEARCH_CREATE:
    case OP_LLOAD_CREATE:
    case OP_NLOAD_CREATE:
    case OP_LOCAL_CREATE:
      if (entryCreatedForLock()) {
        return 1;
      }
      else {
        return 0;
      }
    case OP_NULL:
    case OP_LOCK_FOR_UPDATE:
    case OP_READ_ONLY:
    case OP_FLAG_FOR_READ:
    case OP_L_INVALIDATE:
    case OP_PUT_LI:
    case OP_LLOAD_PUT_LI:
    case OP_NLOAD_PUT_LI:
    case OP_D_INVALIDATE:
    case OP_PUT:
    case OP_SEARCH_PUT:
    case OP_LLOAD_PUT:
    case OP_NLOAD_PUT:
    default:
      return 0;
    }
  }

  /**
   * Returns true if this entry may have been created by this transaction.
   */
  protected final boolean wasCreatedByTX() {
    return Token.isRemoved(this.originalVersionId);
  }

  private final void txApplyDestroyLocally(LocalRegion r, Object key,
      TXState txState, TXRegionState txr, EntryEventImpl cbEvent) {
    if (isOpDestroyEvent(r)) {
      try {
        r.txApplyDestroy(this.regionEntry, txState, key,
            false /*inTokenMode*/, isOpLocalDestroy(),
            getNearSideEventId(txState), getCallbackArgument(),
            txState.getPendingCallbacks(), null, null, null,
            cbEvent.getTailKey(), txr, cbEvent);
      } catch (RegionDestroyedException ignore) {
      } catch (EntryDestroyedException ignore) {
      }
    }
    // if !isOpDestroyEvent then
    // this destroy, to local committed state, becomes a noop
    // since nothing needed to be done locally.
    // We don't want to actually do the destroy since we told the
    // transaction listener that no destroy was done.
  }

  private final void txApplyInvalidateLocally(LocalRegion r, Object key,
      Object newValue, boolean didDestroy, TXState txState, TXRegionState txr,
      EntryEventImpl cbEvent) {
    try {
      r.txApplyInvalidate(this.regionEntry, txState, key, newValue, didDestroy,
          isOpLocalInvalidate() ? true : false, getNearSideEventId(txState),
          getCallbackArgument(), txState.getPendingCallbacks(), null, null,
          null, -1, txr, cbEvent);
    } catch (RegionDestroyedException ignore) {
    } catch (EntryDestroyedException ignore) {
    }
  }

  private final void txApplyPutLocally(LocalRegion r, Operation putOp,
      Object key, Object newValue, boolean didDestroy, TXState txState,
      TXRegionState txr, EntryEventImpl cbEvent) {
    try {
      r.txApplyPut(putOp, this.regionEntry, txState, key, newValue, didDestroy,
          getNearSideEventId(txState), getCallbackArgument(),
          txState.getPendingCallbacks(), null, null, null,
          cbEvent.getTailKey(), txr, cbEvent, this.pendingDelta);
    } catch (RegionDestroyedException ignore) {
    } catch (EntryDestroyedException ignore) {
    }
  }

  protected final void applyChanges(final TXState txState,
      EntryEventImpl cbEvent, final LocalRegion r) {

    if (!isDirty()) {
      // all we did was read so just return
      return;
    }

    final Object key = this.regionKey;

    switch (this.op) {
    case OP_NULL:
    case OP_READ_ONLY:
    case OP_LOCK_FOR_UPDATE:
    case OP_FLAG_FOR_READ:
      // do nothing
      break;
    case OP_L_DESTROY:
      txApplyDestroyLocally(r, key, txState, txRegionState, cbEvent);
      break;
    case OP_CREATE_LD:
      txApplyDestroyLocally(r, key, txState, txRegionState, cbEvent);
      break;
    case OP_LLOAD_CREATE_LD:
      txApplyDestroyLocally(r, key, txState, txRegionState, cbEvent);
      break;
    case OP_NLOAD_CREATE_LD:
      txApplyDestroyLocally(r, key, txState, txRegionState, cbEvent);
      break;
    case OP_PUT_LD:
      txApplyDestroyLocally(r, key, txState, txRegionState, cbEvent);
      break;
    case OP_LLOAD_PUT_LD:
      txApplyDestroyLocally(r, key, txState, txRegionState, cbEvent);
      break;
    case OP_NLOAD_PUT_LD:
      txApplyDestroyLocally(r, key, txState, txRegionState, cbEvent);
      break;
    case OP_D_INVALIDATE_LD:
      txApplyDestroyLocally(r, key, txState, txRegionState, cbEvent);
      break;
    case OP_D_DESTROY:
      txApplyDestroyLocally(r, key, txState, txRegionState, cbEvent);
      break;
    case OP_L_INVALIDATE:
      txApplyInvalidateLocally(r, key, Token.LOCAL_INVALID, didDestroy(),
          txState, txRegionState, cbEvent);
      break;
    case OP_PUT_LI:
      txApplyPutLocally(r, getUpdateOperation(), key, Token.LOCAL_INVALID,
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_LLOAD_PUT_LI:
      txApplyPutLocally(r, getUpdateOperation(), key, Token.LOCAL_INVALID,
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_NLOAD_PUT_LI:
      txApplyPutLocally(r, getUpdateOperation(), key, Token.LOCAL_INVALID,
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_D_INVALIDATE:
      txApplyInvalidateLocally(r, key, Token.INVALID, didDestroy(), txState,
          txRegionState, cbEvent);
      break;
    case OP_CREATE_LI:
      txApplyPutLocally(r, getCreateOperation(), key, Token.LOCAL_INVALID,
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_LLOAD_CREATE_LI:
      txApplyPutLocally(r, getCreateOperation(), key, Token.LOCAL_INVALID,
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_NLOAD_CREATE_LI:
      txApplyPutLocally(r, getCreateOperation(), key, Token.LOCAL_INVALID,
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_CREATE:
      txApplyPutLocally(r, getCreateOperation(), key, getPendingValue(),
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_SEARCH_CREATE:
      txApplyPutLocally(r, Operation.SEARCH_CREATE, key, getPendingValue(),
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_LLOAD_CREATE:
      txApplyPutLocally(r, Operation.LOCAL_LOAD_CREATE, key, getPendingValue(),
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_NLOAD_CREATE:
      txApplyPutLocally(r, Operation.NET_LOAD_CREATE, key, getPendingValue(),
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_LOCAL_CREATE:
      txApplyPutLocally(r, getCreateOperation(), key, getPendingValue(),
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_PUT:
      txApplyPutLocally(r, getUpdateOperation(), key, getPendingValue(),
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_SEARCH_PUT:
      txApplyPutLocally(r, Operation.SEARCH_UPDATE, key, getPendingValue(),
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_LLOAD_PUT:
      txApplyPutLocally(r, Operation.LOCAL_LOAD_UPDATE, key, getPendingValue(),
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    case OP_NLOAD_PUT:
      txApplyPutLocally(r, Operation.NET_LOAD_UPDATE, key, getPendingValue(),
          didDestroy(), txState, txRegionState, cbEvent);
      break;
    default:
        throw new IllegalStateException(LocalizedStrings
            .TXEntryState_UNHANDLED_OP_0.toLocalizedString(opToString()));
    }
  }

  /**
   * @return returns {@link Operation#PUTALL_CREATE} if the operation is a
   *         result of bulk op, {@link Operation#CREATE} otherwise
   */
  private Operation getCreateOperation() {
    return this.bulkOp ? Operation.PUTALL_CREATE : Operation.CREATE;
  }

  /**
   * @return returns {@link Operation#PUTALL_UPDATE} if the operation is a
   *         result of bulk op, {@link Operation#UPDATE} otherwise
   */
  private Operation getUpdateOperation() {
    return this.bulkOp ? Operation.PUTALL_UPDATE : Operation.UPDATE;
  }

  protected void cleanup(final TXState txState, final LocalRegion r,
      final LockingPolicy lockPolicy, final LockMode writeMode,
      final boolean removeFromList, final boolean removeFromMap,
      final Boolean forCommit) {
    try {
    final RegionEntry re = this.regionEntry;
    if (re != null) {
      //r.txDecLRURefCount(re);
      // release all the locks acquired on the entry
      if (isDirty() && re != null) {
        lockPolicy.releaseLock(re, writeMode,
            null /* no need of lockOwner here */, true, r);
        // decrement the locked for create entry count in region
        decrementLockedCountForRegion(r);
        // also unmark the IS_UPDATING flag on RegionEntry to signal
        // < REPEATABLE_READ isolation reads that do not acquire any locks
        re.setUpdateInProgress(false);
      }
      else if (this.isOpReadOnly()) {
        // release the read-only lock
        lockPolicy.releaseLock(re, lockPolicy.getReadOnlyLockMode(),
            null /* no need of lockOwner here */, false, r);
      }
      else if (this.op == OP_LOCK_FOR_UPDATE) {
        lockPolicy.releaseLock(re, lockPolicy.getWriteLockMode(),
            null /* no need of lockOwner here */, true, r);
        // decrement the locked for create entry count in region
        decrementLockedCountForRegion(r);
      }
    }
    // remove from linked list if required
    if (removeFromList) {
      removeFromList(txState);
    }
    // also remove from TXRegionState entryMods map if required
    if (removeFromMap) {
      final TXRegionState regionState = this.txRegionState;

      // in this case we expect that write lock on region is already held
      assert regionState.isHeldByCurrentThread();

      regionState.getInternalEntryMap().remove(this.regionKey);
    }
    }finally {
    release();
    }
  }

  @Override
  @Released(TX_ENTRY_STATE)
  public final void release() {
    Object tmp = this.originalVersionId;
    if (OffHeapHelper.release(tmp)) {
      this.originalVersionId = null; // fix for bug 47900
    }
  }

  protected final void removeFromList(final TXState txState) {
    final NonReentrantLock headLock = txState.headLock;
    headLock.lock();
    try {
      if (this.next != null) {
        this.previous.next = this.next;
        this.next.previous = this.previous;
      }
      if (isPendingForBatch()) {
        // remove from pending ops list
        txState.removePendingOp(this);
      }
      clearPendingForBatch();
    } finally {
      headLock.unlock();
    }
  }

  protected final void decrementLockedCountForRegion(final LocalRegion region) {
    if (entryCreatedForLock()) {
      final AtomicInteger count = region.txLockCreateCount;
      for (;;) {
        final int current = count.get();
        if (current >= 1) {
          if (count.compareAndSet(current, current - 1)) {
            break;
          }
        }
        else {
          // never make -ve
          break;
        }
      }
    }
  }

  public final TXId getTXId() {
    final TXRegionState txrs = this.txRegionState;
    return txrs != null ? txrs.txState.txId : null;
  }

  @Override
  public final int hashCode() {
    return this.regionKey.hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    return this == other;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    ArrayUtils.objectRefString(this, sb);
    sb.append(": op=").append(this.op);
    sb.append(", destroy=").append(this.destroy);
    sb.append(", bulkOp=").append(this.bulkOp);
    sb.append(", key=").append(this.regionKey);
    if (this.pendingDelta != null) {
      sb.append(", pendingDelta=").append(this.pendingDelta);
    }
    if (this.pendingValue != null) {
      sb.append(", pendingValue=");
      ArrayUtils.objectStringNonRecursive(this.pendingValue, sb);
    }
    if (isPendingForBatch()) {
      sb.append(", pendingFullValueForBatch=").append(
          isPendingFullValueForBatch());
      sb.append(", pendingDeltaForBatch=").append(isPendingDeltaForBatch());
    }
    sb.append(", originalValue=");
    ArrayUtils.objectStringNonRecursive(this.originalVersionId, sb);
    sb.append(", callbackArg=").append(getCallbackArgument());
    sb.append(", txId=").append(getTXId());
    sb.append(", regionEntry=").append(this.regionEntry);
    if (this.regionEntry != null) {
      sb.append(", isMarkedForEviction=").append(this.regionEntry.isMarkedForEviction());
    }
    return sb.toString();
  }

  public String shortToString() {
    return shortToString(false);
  }

  public String shortToString(boolean skipValues) {
    final StringBuilder sb = new StringBuilder();
    ArrayUtils.objectRefString(this, sb);
    sb.append(": op=").append(this.op);
    sb.append(", destroy=").append(this.destroy);
    sb.append(", bulkOp=").append(this.bulkOp);
    sb.append(", key=").append(this.regionKey);
    if (this.pendingDelta != null) {
      sb.append(", pendingDelta=").append(this.pendingDelta);
    }
    if (skipValues) {
      return sb.toString();
    }
    if (this.pendingValue != null) {
      sb.append(", pendingValue=");
      ArrayUtils.objectRefString(this.pendingValue, sb);
    }
    if (isPendingForBatch()) {
      sb.append(", pendingFullValueForBatch=").append(
          isPendingFullValueForBatch());
      sb.append(", pendingDeltaForBatch=").append(isPendingDeltaForBatch());
    }
    sb.append(", originalValue=");
    ArrayUtils.objectRefString(this.originalVersionId, sb);
    sb.append(", callbackArg=").append(getCallbackArgument());
    sb.append(", ").append(getTXId().shortToString());
    sb.append(", regionEntry=");
    ArrayUtils.objectRefString(this.regionEntry, sb);
    return sb.toString();
  }

  // Asif:Add for sql fabric as it has to plug in its own TXEntry object
  private final static TXEntryStateFactory factory = new TXEntryStateFactory() {

    public final TXEntryState createEntry(final Object key,
        final RegionEntry re, final Object val,
        final boolean doFullValueFlush, final TXRegionState txrs) {
      return new TXEntryState(key, re, val, doFullValueFlush, txrs);
    }
  };

  public static final TXEntryStateFactory getFactory() {
    return factory;
  }
  
  public Object getOriginalValue() {
    return this.originalVersionId;
  }
}
