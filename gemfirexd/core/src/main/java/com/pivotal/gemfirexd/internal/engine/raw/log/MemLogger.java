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

package com.pivotal.gemfirexd.internal.engine.raw.log;

import java.io.IOException;
import java.util.ArrayList;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.internal.cache.Conflatable;
import com.gemstone.gemfire.internal.cache.EventID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdOpConflationHandler;
import com.pivotal.gemfirexd.internal.engine.access.operations.MemOperation;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.LimitObjectInput;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Compensation;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Loggable;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.LogInstant;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.Logger;
import com.pivotal.gemfirexd.internal.iapi.store.raw.xact.RawTransaction;
import com.pivotal.gemfirexd.internal.iapi.store.raw.xact.TransactionId;

/**
 * This class implements a logger whose back file resides in memory. It is used
 * to store the operations on the indices of a memory base table. A stored
 * operation is a physiological operation. It means that, for a table
 * manipulating statement, such as INSERT, UPDATE, and DELETE, several
 * operations on indices are triggered and recorded in this class object. We
 * postulate that each index operation is atomic.In other words, The insertion
 * or deletion of a entry in an index will finally success if the inserting
 * operation does not cause the duplicate key exception to be thrown or the
 * index entry to be deleted really exists.
 * 
 * Each transaction has its own MemLogger object. If we guarantee that a
 * transaction is only executed by a single thread, so we do not need to
 * consider the thread safe in this implementation.
 * 
 * <Note> If a transaction has several threads, remember to modify this class to
 * make sure it is thread safe.
 * 
 * Unlike the traditional transaction implementation based on locks to isolate
 * the transactions, we do not use any locks for performance promise. In
 * addition, there are two separate lists to store the undo and redo operations.
 * The reason is that we must delay the delete operations to make sure that the
 * current state can be restored to the one just before the manipulating
 * statements executes. For example, if we delete a unique index row too early,
 * another transaction could have inserted a row with the same index key.
 * However, if the current transaction is aborted, the delete operation is not
 * undoable.
 * 
 * As records are in memory, so we do not need to implement any methods related
 * to the disk file related operations.
 * 
 * @author yjing
 */
public class MemLogger implements Logger {

  private RawTransaction xact;

  private ArrayList<MemOperation> undoList;

  private ArrayList<MemOperation> doList;

  private GfxdOpConflationHandler<MemOperation> conflationHandler;

  public void flush(LogInstant where) throws StandardException {
    throw new UnsupportedOperationException(
        "This method is not supposed to be called!");
  }

  public void flushAll() throws StandardException {
    // check the Cache just in case this node is going down
    Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(null);
    MemOperation operation = null;
    try {
      if (this.doList != null) {
        Throwable failure = null;
        for (int index = 0; index < this.doList.size(); ++index) {
          operation = this.doList.get(index);
          try {
            operation.doMe(this.xact, null, null);
          } catch (Throwable t) {
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

            // move to next operation and record the failure
            Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(t);
            failure = t;
          }
        }
        if (failure != null) {
          if (failure instanceof StandardException) {
            throw (StandardException)failure;
          } else {
            Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(failure);
            throw StandardException.newException(SQLState.LOG_DO_ME_FAIL, failure,
                operation);
          }
        }
      }
    } finally {
      clear();
    }
  }

  public LogInstant logAndDo(RawTransaction xact, Loggable operation)
      throws StandardException {
    MemOperation memOp = (MemOperation)operation;
    if (memOp.doAtCommitOrAbort()) {
      getDoList(xact).add(memOp);
    }
    else {
      try {
        memOp.doMe(xact, null, null);
      } catch (IOException ex) {
        throw StandardException.newException(SQLState.LOG_DO_ME_FAIL, ex,
            operation);
      }
    }
    // check and see if this operation can itself be conflated due to a
    // CreateContainer operation at the start (forward conflation), and
    // then check if there is an existing operation in current list that
    // can be conflated
    final String region = memOp.getRegionToConflate();
    final Object key = memOp.getKeyToConflate();
    GfxdOpConflationHandler<MemOperation> conflateHandler =
      getConflationHandler(xact);
    if (key != null) {
      // check if there is any CreateContainer operation
      // in which case no need to add to the undoList
      Conflatable regionConflatable = new RegionConflatable(region);
      MemOperation oldOp;
      if ((oldOp = conflateHandler.indexGet(regionConflatable)) != null) {
        if (GemFireXDUtils.TraceConflation) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION, toString()
              + ": skipping undo for operation [" + memOp
              + "] due to existing operation: " + oldOp);
        }
        return null;
      }
    }
    ArrayList<MemOperation> list = getUndoList(xact);
    if (!conflateHandler.doConflate(memOp, key, memOp, null, list, true, false)
        || key == null /* always add Create/Drop container to undo list */) {
      list.add(memOp);
      if (region != null) {
        conflateHandler.addToConflationIndex(memOp, memOp);
      }
    }
    else {
      if (GemFireXDUtils.TraceConflation) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION, toString()
            + ": skipping undo due to conflation for operation [" + memOp
            + ']');
      }
    }
    return null;
  }

  public LogInstant logAndUndo(RawTransaction xact, Compensation operation,
      LogInstant undoInstant, LimitObjectInput in) throws StandardException {
    throw new UnsupportedOperationException(
        "This method is not supposed to be called!");
  }

  /**
   * We do not consider the restart case because of crash and shutdown. <Note>
   * This delayed delete mechanism cannot guarantee the consistency between a
   * base table and its indices if a node crashes unexpectedly.
   */
  public void reprepare(RawTransaction t, TransactionId undoId,
      LogInstant undoStopAt, LogInstant undoStartAt) throws StandardException {
    throw new UnsupportedOperationException(
        "This method is not supposed to be called!");
  }

  /**
   * The reason of generating compensation on the fly is to reduce generating
   * too much temporary object. In addition, we think that most of insert
   * operations are successful.
   * 
   */
  public void undo(RawTransaction tran, TransactionId undoId,
      LogInstant undoStopAt, LogInstant undoStartAt) throws StandardException {
    MemOperation operation = null;
    Compensation undo = null;
    if (this.undoList != null) {
      Throwable failure = null;
      for (int index = this.undoList.size() - 1; index >= 0; --index) {
        operation = this.undoList.get(index);
        try {
          undo = operation.generateUndo(tran, null);
          if (undo != null) {
            if (SanityManager.DEBUG) {
              if (GemFireXDUtils.TraceTran) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN, toString()
                    + " undo operation {" + undo + "} for operation: "
                    + operation);
              }
            }
            undo.doMe(tran, null, null);
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
          // Whenever you catch Error or Throwable, you must also
          // check for fatal JVM error (see above).  However, there is
          // _still_ a possibility that you are dealing with a cascading
          // error condition, so you also need to check to see if the JVM
          // is still usable:
          SystemFailure.checkFailure();

          // move to next operation and record the failure
          Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(t);
          failure = t;
        }
      }
      if (failure != null) {
        if (failure instanceof StandardException) {
          throw (StandardException)failure;
        } else {
          Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(failure);
          throw StandardException.newException(SQLState.LOG_UNDO_FAILED, failure, tran,
              operation, undo);
        }
      }
    }
  }

  /**
   * The following two methods consider the case that a transaction , which only
   * contains a sequence of the deleting or inserting operations, only uses one
   * of two lists.
   * 
   * @return
   */
  private ArrayList<MemOperation> getUndoList(RawTransaction xact) {
    if (this.undoList == null) {
      this.undoList = new ArrayList<MemOperation>();
      this.xact = xact;
    }
    return this.undoList;
  }

  public ArrayList<MemOperation> getDoList(RawTransaction xact) {
    if (this.doList == null) {
      this.doList = new ArrayList<MemOperation>();
      this.xact = xact;
    }
    return this.doList;
  }

  private GfxdOpConflationHandler<MemOperation> getConflationHandler(
      RawTransaction xact) {
    if (this.conflationHandler == null) {
      this.conflationHandler = new GfxdOpConflationHandler<MemOperation>();
      this.xact = xact;
      this.conflationHandler.setLogPrefix(toString());
    }
    return this.conflationHandler;
  }

  @Override
  public String toString() {
    return "MemLogger for TX " + this.xact;
  }

  private void clear() {
    if (this.undoList != null) {
      this.undoList.clear();
    }
    if (this.doList != null) {
      this.doList.clear();
    }
    if (this.conflationHandler != null) {
      this.conflationHandler.close();
    }
  }

  @SuppressWarnings("serial")
  private final static class RegionConflatable implements Conflatable {

    private final String regionName;

    RegionConflatable(String regionName) {
      this.regionName = regionName;
    }

    public boolean shouldBeConflated() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldBeMerged() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean merge(Conflatable existing) {
      throw new AssertionError("not expected to be invoked");
    }

    public String getRegionToConflate() {
      return this.regionName;
    }

    public Object getKeyToConflate() {
      return null;
    }

    public Object getValueToConflate() {
      return null;
    }

    public EventID getEventId() {
      return null;
    }

    public void setLatestValue(Object value) {
    }
  }
}
