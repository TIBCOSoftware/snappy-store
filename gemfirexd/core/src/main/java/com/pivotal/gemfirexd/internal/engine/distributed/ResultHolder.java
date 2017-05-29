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

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.TXChanges;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.message.StatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RegionAndKey;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.compile.types.DVDSet;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.sql.execute.DistributionPlanCollector;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.CompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.CompactExecRowWithLobs;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.OffHeapCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.OffHeapCompactExecRowWithLobs;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

/**
 * This class is used for holding the results obtained from data store node & is
 * serialized to the query node.
 * 
 * @author Asif
 * 
 */
public final class ResultHolder extends GfxdDataSerializable {

  private final static byte HAS_EXCEPTION = 0x01;

  private final static byte DO_PROJECTION = 0x02;

  private final static byte HAS_OUTER_JOIN_KEYINFO = 0x04;

  private static final byte HAS_ARRAY_OF_BYTES = 0x08;

  /**
   * Return indicator for capturing stats during de-serialization.
   */
  private static final short STATS_ENABLED = 0x10;

  /**
   * If this is the last ResultHolder that also holds {@link TXChanges} from
   * remote {@link TXStateProxy} to be applied locally.
   */
  private static final byte HAS_TX_CHANGES = 0x20;

  private static final byte HAS_STREAMING = 0x40;

  private final EmbedResultSet ers;

  private transient final EmbedStatement es;

  private transient final int origLCCFlags;

  private GfxdResultCollector<?> streamingCollector;

  private GfxdResultCollectorHelper streamingHelper;

  private PreparedStatement gps;

  private byte state = 0x0;

  /**
   * If this is the first call whereby transparent failover is possible then
   * this is true, else if some results have already been consumed then app will
   * have to do the retry at its level after checking for
   * {@link SQLState#GFXD_NODE_SHUTDOWN} SQLException state.
   */
  private transient boolean firstCall = true;

  private ByteArrayDataInput dis;

  private int numEightColGrps = -1;

  private int numPartialCols = -1;

  private volatile Throwable exception = null;

  private volatile DistributedMember exceptionMember = null;

  private RowFormatter rowFormatter;
  
  public int stream_size;

  // this can be null...
  private DataTypeDescriptor distinctAggUnderlyingType = null;

  private final transient StatementStats stats;

  private final GfxdConnectionWrapper wrapper;

  private final StatementExecutorMessage<?> sourceMessage;

  private transient final GfxdHeapThresholdListener thresholdListener;

  // statistics data
  // ----------------
  private final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
      .getInstance();

  private boolean recordStats;

  public transient int rows_returned;

  public transient Timestamp construct_time = null;

  public transient long ser_deser_time;

  public transient long throttle_time;

  // data node result iteration time, query node next() time.
  public transient long process_time;
  // end of statistics data
  // -----------------------

  //    transient data for prepareSend/toData/setupResults
  // ---------------------------------------------------------------
  private transient int numEightColGroups, numPartCols, numRows;
  private transient GfxdHeapDataOutputStream hdos;
  private transient long begin = -1;
  private transient boolean hasRows, doProjection, hasLobs, isOffHeap;
  private transient RowFormatter rf;
  private transient TXChanges txChanges;
  private transient ExecRow[] candidateRowArray;
  private transient final int batchSize = GemFireXDUtils.DML_BULK_FETCH_SIZE;
  private transient int nextRowIndex;
  private transient int candidateRowArraySize;
  private transient boolean hasMoreRows;
  //  end transient data for prepareSend/toData/setupResults
  // ---------------------------------------------------------------

  /** For DataSerialization purposes only */
  public ResultHolder() {
    if (recordStats) {
      construct_time = XPLAINUtil.currentTimeStamp();
    }
    this.ers = null;
    this.es = null;
    this.origLCCFlags = 0;
    this.wrapper = null;
    this.sourceMessage = null;
    this.stats = null;
    final GemFireStore store = GemFireStore.getBootingInstance();
    if (store != null) {
      this.thresholdListener = store.thresholdListener();
    }
    else {
      // can happen during initial registration
      this.thresholdListener = null;
    }
  }

  public ResultHolder(EmbedResultSet rs, EmbedStatement es,
      GfxdConnectionWrapper wrapper, int origLCCFlags,
      StatementExecutorMessage<?> sourceMessage, boolean sendRgnAndKeyInfo) {
    if (rs == null) {
      throw new IllegalArgumentException("result set must not be null");
    }
    if(sourceMessage != null) {
      this.recordStats = sourceMessage.statsEnabled()
          || sourceMessage.timeStatsEnabled()
          || sourceMessage.explainConnectionEnabled();
    }
    if (recordStats) {
      construct_time = XPLAINUtil.currentTimeStamp();
    }
    this.ers = rs;
    this.es = es;
    this.origLCCFlags = origLCCFlags;
    this.wrapper = wrapper;
    this.sourceMessage = sourceMessage;
    this.stats = es.getStatementStats();
    if(this.recordStats) {
      this.state |= STATS_ENABLED;
    }
    if (sendRgnAndKeyInfo) {
      this.state |= HAS_OUTER_JOIN_KEYINFO;
    }
    this.thresholdListener = Misc.getMemStoreBooting().thresholdListener();
  }

  public final void setRowFormatter(final RowFormatter rf, boolean allownull) {
    assert rf != null || allownull;
    this.rowFormatter = rf;
  }

  private boolean hasOuterJoinKeyInfo() {
    return GemFireXDUtils.isSet(this.state, HAS_OUTER_JOIN_KEYINFO);
  }

  public final void setDistinctAggUnderlyingType(
      DataTypeDescriptor distinctUnderlyingType) {
    this.distinctAggUnderlyingType = distinctUnderlyingType;
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceAggreg) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AGGREG,
            "setting input type in RH as " + distinctAggUnderlyingType);
      }
    }
  }

  @Override
  public void fromData(DataInput in) throws IOException,
          ClassNotFoundException {
    super.fromData(in);
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "ResultHolder::fromData: about to read row bytes");
    }
    final byte status = in.readByte();
    if (GemFireXDUtils.isSet(status, STATS_ENABLED)) {
      recordStats = true;
      construct_time = XPLAINUtil.currentTimeStamp();
    }
    // may need to apply TXChanges even if there has been an exception
    if (GemFireXDUtils.isSet(status, HAS_TX_CHANGES)) {
      this.txChanges = TXChanges.fromData(in);
    }
    if (GemFireXDUtils.isSet(status, HAS_EXCEPTION)) {
      final Throwable t = DataSerializer.readObject(in);
      final DistributedMember m = DataSerializer.readObject(in);
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "ResultHolder::fromData: From member [" + m
                + "] received exception: " + t);
      }
      this.exception = t;
      this.exceptionMember = m;
      this.dis = null;
      return;
    }

    this.state |= status;
    final int numBytes = InternalDataSerializer.readArrayLength(in);
    Misc.checkMemoryRuntime(this.thresholdListener,
        "ResultHolder:fromData", numBytes);
    final byte[] rawData = DataSerializer.readByteArray(in, numBytes);
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "ResultHolder::fromData: Status=0x"
              + Integer.toHexString(this.state) + " Number of bytes read="
              + rawData.length);
    }
    Version v = InternalDataSerializer.getVersionForDataStreamOrNull(in);
    this.dis = new ByteArrayDataInput();
    this.dis.initialize(rawData, v);
    this.stream_size = this.dis.available();
  }

  /**
   * Prepare this ResultHolder for sending a set of results to remote caller. A
   * return value of true indicates that there are more results that need to be
   * sent while that of false indicates that last set of results have been
   * prepared.
   * 
   * Typically will be followed by a call to ResultSender.sendResult or
   * ResultSender.lastResult depending on whether this return true or false
   * respectively.
   */
  public boolean prepareSend(final Version v) throws IOException, SQLException {
    try {
      final GemFireXDQueryObserver observer = this.observer;
      final boolean hasOuterJoinKeyInfo = hasOuterJoinKeyInfo();
      this.exception = null;
      if (hdos == null) {
        if (observer != null) {
          observer.beforeResultHolderExecution(this.wrapper, this.es);
        }
        ResultSetMetaData rsmd = ers.getMetaData();
        // get the number of columns
        int numCols = rsmd.getColumnCount();

        numEightColGroups = numCols / 8 + (numCols % 8 == 0 ? 0 : 1);
        numPartCols = numCols % 8;
        if (numPartCols == 0) {
          numPartCols = 8;
        }

        this.doProjection = true;
        this.hasLobs = false;
        this.isOffHeap = false;

        final boolean collectStats = (this.stats != null);
        if (collectStats) {
          begin = this.stats.getStatTime();
        }

        if (observer != null) {
          observer.beforeResultHolderIteration(this.wrapper, this.es);
        }
        final long beginitertime = recordStats ? XPLAINUtil.recordTiming(-1)
            : 0;
        if (ers.lightWeightNext()) {

          ExecRow row = this.ers.getCurrentRow();
          final long beginsertime;
          if (beginitertime != 0) {
            beginsertime = XPLAINUtil.recordTiming(-1);
            process_time += (beginsertime - beginitertime);
          }
          else {
            beginsertime = 0;
          }
          if (observer != null) {
            observer.afterResultHolderIteration(this.wrapper, this.es);
            observer.beforeResultHolderSerialization(this.wrapper, this.es);
          }
          hasRows = true;
          final Class<?> rowClass = row.getClass();
          if (rowClass == CompactExecRow.class) {
            doProjection = false;
            hdos = new GfxdHeapDataOutputStream(this.thresholdListener,
                this.es.getSQLText(), true, v);
            final CompactExecRow crow = (CompactExecRow)row;
            rf = crow.getRowFormatter();
            isOffHeap = rf.container != null && rf.container.isOffHeap();
            final byte[] brow = crow.getRowBytes();

            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "ResultHolder::prepareSend: CompactExecRow bytes: "
                      + Arrays.toString(brow));
            }
            DataSerializer.writeByteArray(brow, hdos);
          }
          else if (rowClass == CompactExecRowWithLobs.class) {
            doProjection = false;
            hasLobs = true;
            hdos = new GfxdHeapDataOutputStream(this.thresholdListener,
                this.es.getSQLText(), true, v);
            final CompactExecRowWithLobs crow = (CompactExecRowWithLobs)row;
            rf = crow.getRowFormatter();
            isOffHeap = rf.container != null && rf.container.isOffHeap();
            final byte[][] brow = crow.getRowByteArrays();

            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "ResultHolder::prepareSend: CompactExecRowWithLobs bytes: "
                      + ArrayUtils.objectStringNonRecursive(brow));
            }
            this.state |= HAS_ARRAY_OF_BYTES;
            DataSerializer.writeArrayOfByteArrays(brow, hdos);
          }
          else if (rowClass == OffHeapCompactExecRow.class) {
            doProjection = false;
            isOffHeap = true;
            hdos = new GfxdHeapDataOutputStream(this.thresholdListener,
                this.es.getSQLText(), true, v);
            final OffHeapCompactExecRow crow = (OffHeapCompactExecRow)row;
            rf = crow.getRowFormatter();
            @Unretained final Object brow = crow.getBaseByteSource();

            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "ResultHolder::prepareSend: OffHeapCompactExecRow bytes: "
                      + Arrays.toString(crow.getRowBytes()));
            }
            if (brow != null) {
              final Class<?> rclass = brow.getClass();
              if (rclass == OffHeapRow.class) {
                ((OffHeapRow)brow).toData(hdos);
              }
              else if (rclass == byte[].class) {
                DataSerializer.writeByteArray((byte[])brow, hdos);
              }
              else {
                ((OffHeapRowWithLobs)brow).serializeGfxdBytesWithStats(0, hdos);
              }
            }
            else {
              DataSerializer.writeByteArray(null, hdos);
            }
          }
          else if (rowClass == OffHeapCompactExecRowWithLobs.class) {
            doProjection = false;
            hasLobs = true;
            isOffHeap = true;
            hdos = new GfxdHeapDataOutputStream(this.thresholdListener,
                this.es.getSQLText(), true, v);
            final OffHeapCompactExecRowWithLobs crow =
                (OffHeapCompactExecRowWithLobs)row;
            rf = crow.getRowFormatter();
            @Unretained final Object brow = crow.getBaseByteSource();

            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "ResultHolder::prepareSend: OffHeapCompactExecRowWithLobs "
                      + "bytes: " + ArrayUtils.objectStringNonRecursive(crow
                          .getRowByteArrays()));
            }
            this.state |= HAS_ARRAY_OF_BYTES;
            if (brow != null) {
              final Class<?> bclass = brow.getClass();
              if (bclass == OffHeapRowWithLobs.class) {
                ((OffHeapRowWithLobs)brow).toData(hdos);
              }
              else if (bclass == byte[][].class) {
                DataSerializer.writeArrayOfByteArrays((byte[][])brow, hdos);
              }
              else {
                // OffHeapRow for the case of ALTER TABLE
                final int numLobs = rf.numLobs();
                InternalDataSerializer.writeArrayLength(numLobs + 1, hdos);
                ((OffHeapRow)brow).toData(hdos);
                for (int i = 1; i <= numLobs; i++) {
                  DataSerializer.writeByteArray(null, hdos);
                }
              }
            }
            else {
              DataSerializer.writeArrayOfByteArrays(null, hdos);
            }
          }
          else {
            DataValueDescriptor[] dvdArr = row.getRowArray();
            // cannot wrap byte[]s for the case of UserTypes since java
            // serialization may be reusing byte[]s at its level
            boolean hasUserType = false;
            for (DataValueDescriptor dvd : dvdArr) {
              if (dvd.getTypeFormatId() == StoredFormatIds.SQL_USERTYPE_ID_V3) {
                hasUserType = true;
                break;
              }
            }
            hdos = new GfxdHeapDataOutputStream(
                numEightColGroups > 4 ? numEightColGroups * 8
                    : GfxdHeapDataOutputStream.MIN_SIZE,
                this.thresholdListener, this.es.getSQLText(), !hasUserType, v);

            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "ResultHolder::prepareSend: ExecRow: " + row);
            }
            this.state |= DO_PROJECTION;
            DVDIOUtil.writeDVDArray(dvdArr, numEightColGroups, numPartCols,
                hdos);
          }
          if (hasOuterJoinKeyInfo) {
            if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                  "ResultHolder::prepareSend: Case of outer join. "
                      + "Writing key info.");
              SanityManager.DEBUG_PRINT(
                  GfxdConstants.TRACE_QUERYDISTRIB,
                  "ResultHolder::prepareSend: keys contained: "
                      + row.getAllRegionAndKeyInfo());
            }
            this.state |= HAS_OUTER_JOIN_KEYINFO;
            DataSerializer.writeTreeSet(row.getAllRegionAndKeyInfo(), hdos);
          }
          numRows++;
          if (beginsertime != 0) {
            ser_deser_time += XPLAINUtil.recordTiming(beginsertime);
          }
          if (hdos.size() >= GemFireXDUtils.DML_MAX_CHUNK_SIZE) {
            // send this ResultHolder at higher layer
            return true;
          }
        }
      }
      else {
        hdos.clearForReuse();
      }

      if (hasRows) {
        final boolean result;
        if (doProjection) {
          if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                toString() + "::prepareSend: Serializing using DVD ExecRow");
          }
          result = this.processRows(numEightColGroups, numPartCols,
              hasOuterJoinKeyInfo, hasLobs, isOffHeap, rf, hdos);
        }
        else {
          if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
            if (!hasLobs) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                  toString() + "::prepareSend: Serializing CompactExecRows");
            }
            else {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                  toString()
                      + "::prepareSend: Serializing CompactExecRowWithLobs");
            }
          }
          result = this.processRows(0, 0, hasOuterJoinKeyInfo, hasLobs,
              isOffHeap, rf, hdos);
        }
        if (!result) {
          // indicates that max chunk size or time was exceeded so send
          // this at higher layer
          return true;
        }
      }
      // done with all serialization so invoke lastResult at higher layer

      final long beginRemainingProcessTime = recordStats ? XPLAINUtil
          .recordTiming(-1) : 0;
      if (begin != -1) {
        this.stats.incExecutionTimeDataNode(begin);
        this.stats.incNumRowsReturnedFromDataNode(numRows);
      }
      rows_returned = numRows;

      // close the ResultSet before handleTXFinish so that the read locks,
      // if any, are correctly dealt with
      closeEmbedResultSet(this.es instanceof EmbedPreparedStatement, this.ers
          .getSourceResultSet().getActivation());
      // flush any TX changes in region list
      final TXStateInterface tx;
      if (this.sourceMessage != null) {
        if ((tx = this.sourceMessage.getTXState()) != null) {
          this.txChanges = handleTXFinish(tx);
          if (this.txChanges != null) {
            this.state |= HAS_TX_CHANGES;
          }
        }
      }
      else {
        final LanguageConnectionContext lcc;
        final GemFireTransaction tran;
        if (this.es != null
            && (lcc = this.es.getEmbedConnection()
                .getLanguageConnectionContext()) != null
            && (tran = (GemFireTransaction)lcc.getTransactionExecute()) != null) {
          tx = tran.getActiveTXState();
        }
        else {
          tx = null;
        }
      }
      // flush any pending ops at this point
      if (tx != null) {
        tx.flushPendingOps(null);
      }

      if(beginRemainingProcessTime != 0) {
        process_time += XPLAINUtil.recordTiming(beginRemainingProcessTime);
      }
      
      if (observer != null) {
        observer.afterResultHolderIteration(this.wrapper, this.es);
        observer.beforeResultHolderSerialization(this.wrapper, this.es);
      }

      return false;
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      SystemFailure.checkFailure();
      // If the above returns then send back error since this may be assertion
      // or some other internal code bug.
      // check for cache close
      if (t instanceof SQLException) {
        if (SQLState.LANG_STATEMENT_NEEDS_RECOMPILE_STATE
            .equals(((SQLException)t).getSQLState())) {
          throw (SQLException)t;
        }
      }
      try {
        Misc.checkIfCacheClosing(t);
      } catch (RuntimeException re) {
        t = re;
      }
      this.state |= HAS_EXCEPTION;
      this.exception = t;
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "ResultHolder::prepareSend: got exception while sending", t);
      }
      else if (GemFireXDUtils.TraceFunctionException
          | GemFireXDUtils.TraceExecution) {
        // log full stack for unexpected runtime exceptions
        boolean printStack = false;
        Throwable fail = t;
        while (fail != null) {
          if (fail instanceof Error || ((fail instanceof RuntimeException)
              && !(fail instanceof GemFireException)
              && !(fail instanceof GemFireXDRuntimeException))) {
            printStack = true;
            break;
          }
          fail = fail.getCause();
        }
        if (printStack) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "ResultHolder::prepareSend: got exception while sending", t);
        } else {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "ResultHolder::prepareSend: got exception while sending: " + t);
        }
      }

      final TXStateInterface tx;
      try {
        if (this.sourceMessage != null
            && (tx = this.sourceMessage.getTXState()) != null) {
          this.txChanges = handleTXFinish(tx);
          if (this.txChanges != null) {
            this.state |= HAS_TX_CHANGES;
          }
        }
      } catch (Throwable ex) {
        Error e;
        if (ex instanceof Error && SystemFailure.isJVMFailureError(
            e = (Error)ex)) {
          SystemFailure.initiateFailure(e);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw e;
        }
        SystemFailure.checkFailure();
        // log and move ahead
        SanityManager.DEBUG_PRINT("severe:" + GfxdConstants.TRACE_QUERYDISTRIB,
            "ResultHolder::prepareSend: exception in TX proxy cleanup: ", ex);
        // check for cache close
        try {
          Misc.checkIfCacheClosing(ex);
        } catch (RuntimeException re) {
          t = re;
        }
      }
      this.state |= HAS_EXCEPTION;
      this.exception = t;
      return false;
    }
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    final GemFireXDQueryObserver observer = this.observer;
    // Get the byte array hidden in HeapDataOutputStream without copying the
    // byte array.
    // For the time being make use of Oplogs wrapper

    // Asif: Check if possible to use sendTo(OutputStream os) method
    // of HeapDataOutputStream. The problem is that if we use it , will we
    // be able to read data from it cleanly without creating multiple byte
    // buffers? As we will not be having the size of the bytes written
    // upfront when using sendTo method of HeapDataoutputStream.Also
    // how will we know the end of our dat? It is possible that
    // GFE appends its own bytes after writing our data & we may
    // icorrectly read those too. Can we rely on EOF/ -1 for our data
    // if so we can utilize sendTo method
    // [sumedh] now avoiding byte copying using HDOS and its size directly
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "writing hasException false and doProjection=" + doProjection
              + " status=0x" + Integer.toHexString(this.state));
    }
    final long beginsertime = recordStats ? XPLAINUtil.recordTiming(-1) : 0;
    out.writeByte(this.state);
    if (this.txChanges != null) {
      this.txChanges.toData(out);
    }
    if (this.exception == null) {
      int numBytes = 0;
      long beginWrite = -1;
      if (begin != -1) {
        beginWrite = this.stats.getStatTime();
      }

      if (hdos != null) {
        numBytes = hdos.size();
        InternalDataSerializer.writeArrayLength(numBytes, out);
        hdos.sendTo(out);
        stream_size = numBytes;
      }
      else {
        InternalDataSerializer.writeArrayLength(numBytes, out);
      }
      if (begin != -1) {
        assert beginWrite != -1;
        this.stats.incWriteResutlSetDataNode(beginWrite);
        this.stats.incNumBytesWrittenPreQuery(numBytes);
      }

      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "ResultHolder::toData: Number of rows written=" + numRows
                + " Number of bytes (may be off by 1 or 2 bytes) written="
                + (numBytes + 5));
      }
      if (GemFireXDUtils.TraceRSIter & SanityManager.isFinerEnabled) {
        if (hdos != null) {
          hdos.rewind();
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "ResultHolder::toData: bytes written: "
                  + Arrays.toString(hdos.toByteArray()));
        }
      }
    }
    else {
      DataSerializer.writeObject(this.exception, out);
      DataSerializer.writeObject(GemFireStore.getMyId(), out);
    }

    ser_deser_time += beginsertime != 0 ? XPLAINUtil.recordTiming(beginsertime)
        : 0;
    
    // in case the clock didn't ticked b/w result iterations & serialization.
    if (recordStats && ser_deser_time == 0 && process_time == 0) {
      process_time += 1;
    }
    if (observer != null) {
      observer.afterResultHolderSerialization(this.wrapper, this.es);
      observer.afterResultHolderExecution(this.wrapper, this.es,
          this.es.getSQLText());
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("ResultHolder@");
    sb.append(Integer.toHexString(System.identityHashCode(this)));
    final Throwable t = this.exception;
    if (t != null) {
      sb.append(' ').append(t.toString());
      return sb.toString();
    }
    final int numRows = this.numRows;
    if (numRows > 0) {
      sb.append(" numRows=").append(numRows);
    }
    final int ssize = this.stream_size;
    if (ssize > 0) {
      sb.append(" numBytes=").append(ssize);
    }
    final TXChanges txChanges = this.txChanges;
    if (txChanges != null) {
      sb.append(' ').append(txChanges.toString());
    }
    return sb.toString();
  }

  private TXChanges handleTXFinish(final TXStateInterface tx) {
    final TXStateProxy txProxy = tx.getProxy();
    final TXChanges txChanges = TXChanges.fromMessage(this.sourceMessage,
        txProxy);
    if (this.sourceMessage.finishTXProxyRead() && !txProxy.isSnapshot()) {
      // in case there is nothing in the TXStateProxy then get rid of it
      // so that commit/rollback will not be required for this node;
      // this is now a requirement since commit/rollback targets only the
      // nodes that host one of the affected regions only, so if nothing
      // was affected on this node then no commit/rollback may be received
      txProxy.removeSelfFromHostedIfEmpty(null);
    }
    return txChanges;
  }

  /**
   * This gets called only during local self-execution. Purpose is to provide an
   * iterator on the rows in local node.
   * 
   * @return true if there are more batches of results to be setup and false
   *         otherwise
   * 
   * @throws SQLException
   */
  public boolean setupResults(final GfxdResultCollector<?> collector,
      final Activation act) throws SQLException {
    final long beginitertime = recordStats ? XPLAINUtil.recordTiming(-1) : 0;
    this.nextRowIndex = 0;
    boolean hasNext;
    // Always turn it on here. If eagerly consumed & closed, then wrapper.process
    // will capture local query plan or otherwise #moveNextResult will turn it off
    // & then capture the local query plan.
    this.ers.setIsLocallyProcessing(true);
    // consume all results in case of select for update so that conflicts
    // are not thrown during iteration (#43937)
    if (this.ers.isForUpdate()) {      
      final ArrayList<ExecRow> allRows = new ArrayList<ExecRow>();
      ExecRow row, rowClone;
      boolean rowsCopiedOk = false;
      try {
        while (this.ers.lightWeightNext()) {
          row = this.ers.getCurrentRow();
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "ResultHolder::localResults: read row for update " + row);
          }
          rowClone = row.getNewNullRow();
          rowClone.setRowArrayClone(row, row.getAllRegionAndKeyInfo());
          // if the row has a byte source increase the use count here so that it
          // does
          // not get released
          if (rowClone instanceof AbstractCompactExecRow) {
            @Retained
            Object byteSource = ((AbstractCompactExecRow) rowClone)
                .getByteSource();
            if (byteSource instanceof OffHeapByteSource) {
              ((OffHeapByteSource) byteSource).retain();
            }
          }
          row.clearAllRegionAndKeyInfo();
          allRows.add(rowClone);
        }

        if ((this.candidateRowArraySize = allRows.size()) > 0) {
          this.candidateRowArray = new ExecRow[this.candidateRowArraySize];
          allRows.toArray(this.candidateRowArray);
        } else {
          this.candidateRowArray = null;
        }
        rowsCopiedOk = true;
      } finally {
        if (!rowsCopiedOk) {
          this.freeOffHeapForCachedRows(allRows);
        }
      }
      // no more batches of results to be setup for this case
      hasNext = false;
      // also flush all batched operations, for no conflict at commit (#44732)
      final TXStateProxy tx = this.sourceMessage.getTXProxy();
      if (tx != null) {
        tx.flushPendingOps(null);
      }
    }
    else if ((hasNext = this.ers.lightWeightNext())) {
      this.candidateRowArray = new ExecRow[this.batchSize];
      ExecRow row, rowClone;
      int index = 0;
      try {
        for (;;) {
          row = this.ers.getCurrentRow();
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "ResultHolder::localResults: read row " + row);
          }
          rowClone = row.getNewNullRow();
          rowClone.setRowArrayClone(row, row.getAllRegionAndKeyInfo());
          if (rowClone instanceof AbstractCompactExecRow) {
            Object byteSource = ((AbstractCompactExecRow) rowClone)
                .getByteSource();
            if (byteSource instanceof OffHeapByteSource) {
              ((OffHeapByteSource) byteSource).retain();
            }
          }
          row.clearAllRegionAndKeyInfo();
          this.candidateRowArray[index] = rowClone;
          if (++index < this.batchSize
              && (hasNext = this.ers.lightWeightNext())) {
            row = this.ers.getCurrentRow();
          } else {
            break;
          }
        }
      } finally {
        this.candidateRowArraySize = index;
      }
    }
    else {
      this.candidateRowArray = null;
      this.candidateRowArraySize = 0;
    }
    if (GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
          "ResultHolder::localResults: hasNext=" + hasNext
              + ", candidateRowArraySize=" + this.candidateRowArraySize);
    }
    if (hasNext) {
      if (collector != null
          && (this.streamingHelper = collector.getStreamingHelper()) != null) {
        // add a reference to the helper indicating that container locks cannot
        // be released until the local results have been fully consumed
        this.streamingHelper.addReference();
        this.streamingCollector = collector;
      }
      else {
        this.streamingHelper = null;
        this.streamingCollector = null;
      }
      this.state |= HAS_STREAMING;
      this.hasMoreRows = true;
    }
    else {
      // check for JVM going down (#44944)
      Misc.checkIfCacheClosing(null);
      close(act, this.es.getEmbedConnection());      
      this.hasMoreRows = false;
    }
    if (beginitertime != 0) {
      process_time += XPLAINUtil.recordTiming(beginitertime);
    }
    return hasNext;
  }

  private boolean moveNextResults() throws StandardException {
    if (this.hasMoreRows) {

      boolean hasNext = true;
      this.nextRowIndex = 0;
      this.candidateRowArraySize = 0;
      ExecRow row, candidateRow;
      int index = -1;
      final EmbedConnection conn = this.es.getEmbedConnection();
      synchronized (conn.getConnectionSynchronization()) {
        conn.getTR().setupContextStack();
        this.ers.pushStatementContext(conn.getLanguageConnection(), true);
        this.ers.setIsLocallyProcessing(false);
        try {
          while (++index < this.batchSize) {
            // default hasNext to false here if next() throws exception
            hasNext = false;
            if ((hasNext = this.ers.lightWeightNext())) {
              row = this.ers.getCurrentRow();
              if (GemFireXDUtils.TraceRSIter) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                    "ResultHolder::localResults: read row " + row);
              }
              candidateRow = this.candidateRowArray[index];
              // cannot use setRowArray below since for ValueRow it will assign
              // row into candidateRow by reference which is template row at
              // the lower layers; the setColumns will correctly copy the values
              // as required for ValueRow
              candidateRow.setColumns(null, row);
              if(candidateRow instanceof AbstractCompactExecRow) {
                @Retained Object byteSource = ((AbstractCompactExecRow)candidateRow).getByteSource();
                if(byteSource instanceof OffHeapByteSource) {
                  ((OffHeapByteSource)byteSource).retain();
                }        
              }
              candidateRow.setAllRegionAndKeyInfo(row.getAllRegionAndKeyInfo());
              row.clearAllRegionAndKeyInfo();
            }
            else {
              break;
            }
          }
          //this.candidateRowArraySize = index;
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "ResultHolder::localResults: hasNext=" + hasNext
                    + ", candidateRowArraySize=" + this.candidateRowArraySize);
          }
          if (hasNext) {
            return true;
          }
          else {
            // check for JVM going down (#44944)
            Misc.checkIfCacheClosing(null);
            return false;
          }
        } catch (SQLException sqle) {
          throw Misc.wrapSQLException(sqle, sqle);
        } finally {
          this.candidateRowArraySize = index;
          GfxdConnectionWrapper.restoreContextStack(this.es, this.ers);
          if (!(this.hasMoreRows = hasNext)) {
            close(null, conn);
          }
        }
      }
    }
    return false;
  }

  //TODO:ASif: This method is not truthful , I kept getting false 
  //if the last message was sent
  public final boolean isLocallyExecuted() {
    // no serialization of RH when locally executed.
    return this.sourceMessage != null && this.sourceMessage.isLocallyExecuted();
  }
  
  public final boolean isGenuinelyLocallyExecuted() {
    return this.ers != null;
  }

  public final void close(Activation act, EmbedConnection conn) {
    
    if (this.es != null) {
      // cleanup remaining stuff
      if (this.streamingCollector != null) {
        // close the containers using helper and collector
        this.streamingHelper.closeContainers(this.streamingCollector, false);
        // release the collector and helper that are holding references
        // to containers etc.
        this.streamingCollector = null;
        this.streamingHelper = null;
      }
      final boolean isPrepStmt = this.es instanceof EmbedPreparedStatement;
      if (act == null) {
        act = this.ers.getSourceResultSet().getActivation();
      }
      closeEmbedResultSet(isPrepStmt, act);
      final LanguageConnectionContext lcc = act.getLanguageConnectionContext();
      if (act != null) {
        act.setFlags(0);
        act.setFunctionContext(null);
      }
      if (lcc != null) {
        if (conn == null) {
          conn = this.es.getEmbedConnection();
        }
        lcc.setFlags(this.origLCCFlags);
        lcc.setPossibleDuplicate(false);
        if (!isPrepStmt) {
          lcc.setConstantValueSet(null, null);
        }
        // wrapper can get closed for a non-cached Connection
        if (!this.wrapper.isCached()) {
          try {
            if (isPrepStmt && !this.es.isClosed()) {
              this.es.close();
            }
          } catch (SQLException sqle) {
            // ignore any exception in statement close
          }
          this.wrapper.close(false, false);
        }
      }
    }
  }

  public final void clear() {
    this.gps = null;
  }

  private void closeEmbedResultSet(final boolean isPrepStmt,
      final Activation act) {
    this.gps = act.getPreparedStatement();

    // connection may have been closed by now if shutDown has been invoked on
    // another thread
    if (this.es.getEmbedConnection().isClosed()) {
      return;
    }

    try {
      if (GemFireXDUtils.TracePlanGeneration) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_GENERATION,
            "ResultHolder::closeERS: lightWeightClose isLocallyExecuted="
                + isLocallyExecuted());
      }
      if(isLocallyExecuted()) {
        this.ers.getSourceResultSet().markLocallyExecuted();        
      }
      this.ers.lightWeightClose();
    } catch (Exception ex) {
      if (GemFireXDUtils.TraceFunctionException) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "ResultHolder::closeERS: Unexpected exception in "
                + "ResultSet lightWeightClose", ex);
      }
      /*
      if (isPrepStmnt) {
        GfxdConnectionWrapper.restoreContextStack(this.es, this.ers);
        return false;
      }
      */
    } finally {
      if (!isPrepStmt) {
        try {
          this.es.close();
        } catch (Exception ex) {
          if (GemFireXDUtils.TraceFunctionException) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                "ResultHolder::closeERS: Unexpected exception in "
                    + "statement close", ex);
          }
        }
      }
      /*
      // also try to close a non-cached connection once we are done
      if (this.wrapper != null && !this.wrapper.isCached()) {
        try {
          this.wrapper.close();
        } catch (Exception ex) {
          if (GemFireXDUtils.TraceFunctionException) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                "ResultHolder::closeERS: Unexpected exception in non-cached "
                    + "connection close", ex);
          }
        }
      }
      */
    }
  }

  private boolean processRows(final int numEightColGroups,
      final int numPartCols, final boolean hasOuterJoinKeyInfo,
      final boolean hasLobs, final boolean isOffHeap, final RowFormatter rf,
      final GfxdHeapDataOutputStream hdos) throws SQLException, IOException,
      StandardException {
    ExecRow row;
    boolean result = true;
    int rowNumber = 1;
    long startSampleTime = 0, processTime = 0, serDeserTime = 0;
    long throttleTime = 0;
    for (;;) {

      final long beginitertime = recordStats ? XPLAINUtil.recordTiming(-1) : 0;
      final long beginsertime;

      if (!ers.lightWeightNext()) {
        break;
      }

      row = this.ers.getCurrentRow();

      if (beginitertime != 0) {
        beginsertime = XPLAINUtil.recordTiming(-1);
        processTime += (beginsertime - beginitertime);
      }
      else {
        beginsertime = 0;
      }
      if (observer != null) {
        observer.afterResultHolderIteration(this.wrapper, this.es);
        observer.beforeResultHolderSerialization(this.wrapper, this.es);
      }
      if (numPartCols == 0) { // case of compact rows

        /*
         * To read this row, the template at the source end is expected to
         * be on par with this node.
         * 
         * Table schema change will force us to write the dtd(s) or typeid
         * (@see DataType#fromData) to create the required DVDs. [sumedh]
         * should not be required since such DDLs will block the DMLs and
         * ColumnDescriptorList will be refreshed on all nodes
         */

        if (!hasLobs) {
          if (!isOffHeap) {
            final byte[] brow = row.getRowBytes(rf);

            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "ResultHolder::processRows: CompactExecRow bytes: "
                      + Arrays.toString(brow));
            }
            DataSerializer.writeByteArray(brow, hdos);
          }
          else {
            @Unretained final Object brow = row.getBaseByteSource();

            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "ResultHolder::processRows: OffHeapCompactExecRow bytes: "
                      + Arrays.toString(row.getRowBytes(rf)));
            }
            if (brow != null) {
              final Class<?> rclass = brow.getClass();
              if (rclass == OffHeapRow.class) {
                ((OffHeapRow)brow).toData(hdos);
              }
              else if (rclass == byte[].class) {
                DataSerializer.writeByteArray((byte[])brow, hdos);
              }
              else {
                ((OffHeapRowWithLobs)brow).serializeGfxdBytesWithStats(0, hdos);
              }
            }
            else {
              DataSerializer.writeByteArray(null, hdos);
            }
          }
        }
        else if (!isOffHeap) {
          final byte[][] brow = row.getRowByteArrays(rf);

          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "ResultHolder::processRows: CompactExecRowWithLobs bytes: "
                    + ArrayUtils.objectStringNonRecursive(brow));
          }
          DataSerializer.writeArrayOfByteArrays(brow, hdos);
        }
        else {
          @Unretained final Object brow = row.getBaseByteSource();

          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "ResultHolder::processRows: OffHeapCompactExecRowWithLobs "
                    + "bytes: " + ArrayUtils.objectStringNonRecursive(row
                        .getRowByteArrays(rf)));
          }
          if (brow != null) {
            final Class<?> bclass = brow.getClass();
            if (bclass == OffHeapRowWithLobs.class) {
              ((OffHeapRowWithLobs)brow).toData(hdos);
            }
            else if (bclass == byte[][].class) {
              DataSerializer.writeArrayOfByteArrays((byte[][])brow, hdos);
            }
            else {
              // OffHeapRow for the case of ALTER TABLE
              final int numLobs = rf.numLobs();
              InternalDataSerializer.writeArrayLength(numLobs + 1, hdos);
              ((OffHeapRow)brow).toData(hdos);
              for (int i = 1; i <= numLobs; i++) {
                DataSerializer.writeByteArray(null, hdos);
              }
            }
          }
          else {
            DataSerializer.writeArrayOfByteArrays(null, hdos);
          }
        }
      }
      else {
        DataValueDescriptor[] dvdArr = row.getRowArray();

        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "ResultHolder::processRows: ExecRow: " + row);
        }

        DVDIOUtil.writeDVDArray(dvdArr, numEightColGroups, numPartCols, hdos);
      }
      if (hasOuterJoinKeyInfo) {
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "ResultHolder::processRows: writing key info for outer join");
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "ResultHolder::processRows: keys contained "
                  + row.getAllRegionAndKeyInfo());
        }
        DataSerializer.writeTreeSet(row.getAllRegionAndKeyInfo(), hdos);
      }
      rowNumber++;
      if (observer != null) {
        observer.beforeResultHolderIteration(this.wrapper, this.es);
        observer.afterResultHolderSerialization(this.wrapper, this.es);
      }
      final int resultSize = hdos.size();
      if (resultSize >= GemFireXDUtils.DML_MAX_CHUNK_SIZE ) {
        // max chunk size reached so send this ResultHolder at higher layer
        result = false;
        break;
      }
      if (beginsertime != 0) {
        serDeserTime += XPLAINUtil.recordTiming(beginsertime);
      }
      if (resultSize >= GemFireXDUtils.DML_MIN_SIZE_FOR_STREAMING) {
        if (beginsertime != 0) {
          if ((serDeserTime + processTime) >=
              (1000000L * GemFireXDUtils.DML_MAX_CHUNK_MILLIS)) {
            // max time interval reached so send this ResultHolder at higher
            // layer
            result = false;
            break;
          }
        }
        else if ((rowNumber % GemFireXDUtils.DML_SAMPLE_INTERVAL) == 0) {
          final long currentTime = System.nanoTime();
          if (startSampleTime > 0) {
            if ((currentTime - startSampleTime) >=
                (1000000L * GemFireXDUtils.DML_MAX_CHUNK_MILLIS)) {
              // max time interval reached so send this ResultHolder at higher
              // layer
              result = false;
              break;
            }
          }
          else {
            startSampleTime = currentTime;
          }
        }
        // check for ResourceManager on this node and receiver
        if ((rowNumber % 10) == 0 && isCriticalUp()) {
          // throttle the processing and sends
          final long beginThrottleTime = recordStats ? XPLAINUtil
              .recordTiming(-1) : 0;
          try {
            for (int tries = 1; tries <= 5; tries++) {
              Thread.sleep(4);
              if (!isCriticalUp()) {
                break;
              }
            }
          } catch (InterruptedException ie) {
            Misc.checkIfCacheClosing(ie);
          }
          if (beginThrottleTime != 0) {
            throttleTime += XPLAINUtil.recordTiming(beginThrottleTime);
          }
        }
      }
    }
    if (recordStats) {
      process_time += processTime;
      ser_deser_time += serDeserTime;
      throttle_time += throttleTime;
    }
    numRows += (rowNumber - 1);
    return result;
  }

  private boolean isCriticalUp() {
    return this.thresholdListener.isCritical()
        || (!this.sourceMessage.isLocallyExecuted() && this.thresholdListener
            .isCriticalUp(this.sourceMessage.getSender()));
  }

  /**
   * 
   * @param projRow
   *          template for incoming row if there is projection
   * 
   * @return next ExecRow from this result holder
   */
  public ExecRow getNext(ExecRow projRow, Activation act)
      throws StandardException {

    final long beginitertime = recordStats ? XPLAINUtil.recordTiming(-1) : 0;
    // this.ers is null if being deserialized
    if (this.ers != null) {
      // WARNING: incoming projRow is always ignored here; if this assumption
      // changes then need to update "hasProjectionFromRemote" to also handle
      // local projection correctly
      if (this.nextRowIndex >= this.candidateRowArraySize && this.hasMoreRows) {
        // setup the next set of results
        final boolean hasNext = moveNextResults();
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "ResultHolder::getNext: Local read: got next batch of size "
                  + this.candidateRowArraySize + " hasNext=" + hasNext);
        }
      }

      if (this.nextRowIndex < this.candidateRowArraySize) {
        projRow = this.candidateRowArray[this.nextRowIndex++];
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "ResultHolder::getNext: Local read: returning row {" + projRow
                  + "} at index=" + (this.nextRowIndex - 1));
        }
      }
      else {
        projRow = null;
      }
      if (beginitertime != 0) {
        process_time += XPLAINUtil.recordTiming(beginitertime);
      }
      rows_returned++;
      return projRow;
    }

    return readFromStream(projRow, act);
  }

  private ExecRow readFromStream(ExecRow projRow, Activation act)
      throws StandardException {

    final GemFireXDQueryObserver observer = this.observer;
    final long beginitertime = recordStats ? XPLAINUtil.recordTiming(-1) : 0;

    if (this.dis.available() == 0) {
      throw new AssertionError(
          "readFromStream should be invoked with data available");
    }

    if (observer != null) {
      observer.beforeResultSetHolderRowRead(this.rowFormatter, act);
    }
    final byte state = this.state;
    final boolean doProjection = GemFireXDUtils.isSet(state, DO_PROJECTION);
    final boolean hasOuterJoinKeyInfo = GemFireXDUtils.isSet(state,
        HAS_OUTER_JOIN_KEYINFO);
    final boolean hasArrayOfBytes = GemFireXDUtils.isSet(state,
        HAS_ARRAY_OF_BYTES);

    if (GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
          "ResultHolder#getNext: invoked with doProjection=" + doProjection
              + " hasOuterJoinKeyInfo=" + hasOuterJoinKeyInfo
              + " hasArrayOfBytes=" + hasArrayOfBytes + " state=0x"
              + Integer.toHexString(state));
    }
    if (!doProjection) {
      try {

        final RowFormatter rf = this.rowFormatter;
        /* 
         * deserialize at the query node based on dtd at this end.
         * This can be an issue when table metadata changes and this
         * row was on the fly.
         * 
         * So, we must serialize the dtd as the first row in 
         * ResultHolder#toData() or actually serializing int(s) carrying
         * typeid should be sufficient for recreating respective
         * DVD elements & CompactExecRow can simply fill it up instead
         * of current mechanism of 'always new'. @see DataType#fromData.
         *
         * [sumedh] table schema changing is not an issue since any such
         * DDL will block all dependent DMLs and will refresh the
         * ColumnDescriptorList in the end on all nodes
         */
        assert rf != null: "ResultHolder#readFromStream: "
            + "null RowFormatter is not allowed with no projection";
        if (!hasArrayOfBytes) {
          final byte[] brow = DataSerializer.readByteArray(this.dis);
          // adjust for the case of full table row where formatter will need
          // to be explicitly gotten from the row bytes, else in other cases
          // with projection, the bytes will always be copied in the current
          // schema as provided by incoming formatter
          final boolean isFullRow = rf.isTableFormatter();
          if (projRow == null) {
            projRow = rf.container.newExecRowFromBytes(brow,
                isFullRow ? rf.container.getRowFormatter(brow) : rf);
          }
          else {
            AbstractCompactExecRow crow = (AbstractCompactExecRow)projRow;
            if (isFullRow) {
              crow.setRowArray(brow, rf.container.getRowFormatter(brow));
            }
            else {
              crow.setRowArray(brow, rf);
            }
          }
        }
        else {
          final byte[][] brow = DataSerializer.readArrayOfByteArrays(this.dis);
          // adjust for the case of full table row where formatter will need
          // to be explicitly gotten from the row bytes, else in other cases
          // with projection, the bytes will always be copied in the current
          // schema as provided by incoming formatter
          final boolean isFullRow = rf.isTableFormatter();
          if (projRow == null) {
            projRow = rf.container.newExecRowFromByteArrays(brow,
                isFullRow ? rf.container.getRowFormatter(brow) : rf);
          }
          else {
            AbstractCompactExecRow crow = (AbstractCompactExecRow)projRow;
            if (isFullRow) {
              crow.setRowArray(brow, rf.container.getRowFormatter(brow));
            }
            else {
              crow.setRowArray(brow, rf);
            }
          }
        }
        // projRow.setRowArray( crow.getRowArray() );

        TreeSet<RegionAndKey> set = null;
        if (hasOuterJoinKeyInfo) {
          set = DataSerializer.readTreeSet(this.dis);
          projRow.setAllRegionAndKeyInfo(set);
        }
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "ResultHolder::getNext:Compact Row read: returning row = "
                  + projRow);
        }
        process_time += beginitertime != 0 ? XPLAINUtil
            .recordTiming(beginitertime) : 0;
        if (observer != null) {
          observer.afterResultSetHolderRowRead(this.rowFormatter, projRow, act);
        }
        rows_returned++;
        return projRow;
      } catch (IOException e) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "unexpected IOException", e);
      } catch (ClassNotFoundException e) {
        AssertionError ae = new AssertionError(
            "unexpected ClassNotFoundException");
        ae.initCause(e);
        throw ae;
      }
    }

    assert projRow != null: "projRow must not be null";

    // TODO:Asif : Should we cache these two?
    DataValueDescriptor[] dvdArr = projRow.getRowArray();

    /*fix #41492
     * We don't want to do this for all. Unnecessary
     * looping for non-distinct aggregate / non-aggregate
     * variants.
     */
    if (this.distinctAggUnderlyingType != null) {

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceAggreg) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AGGREG,
              "ResultHolder#getNext: Have distinct aggregates in the query...");
        }
      }

      for (DataValueDescriptor dvd : dvdArr) {
        if (dvd instanceof DVDSet) {
          ((DVDSet)dvd).setResultDescriptor(this.distinctAggUnderlyingType);
          continue;
        }
      }

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceAggreg) {
          for (int i = 0; i < dvdArr.length; i++) {
            if (dvdArr[i] instanceof DVDSet) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AGGREG,
                  "ResultHolder::getNext: distinct aggregate's underlying "
                      + "type of dvdArr[" + i + "] is "
                      + ((DVDSet)dvdArr[i]).getResultDescriptor() + " for 0x"
                      + System.identityHashCode(dvdArr[i]));
            }
          }
        }
      }
    }

    int numCols = dvdArr.length;
    if (numEightColGrps < 0) {
      // Initialize some data
      numEightColGrps = numCols / 8 + (numCols % 8 == 0 ? 0 : 1);
      numPartialCols = numCols % 8;
      if (numPartialCols == 0) {
        numPartialCols = 8;
      }
    }

    try {
      DVDIOUtil.readDVDArray(dvdArr, this.dis, numEightColGrps, numPartialCols);
      if (hasOuterJoinKeyInfo) {
        TreeSet<RegionAndKey> set = DataSerializer.readTreeSet(this.dis);
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "ResultHolder::readFromStream: Outerjoin special case."
                  + "Region & Keys received for tuple=" + set);
        }
        projRow.setAllRegionAndKeyInfo(set);
      }
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "ResultHolder::getNext:Value Row read: returning row = " + projRow);
      }
      process_time += beginitertime != 0 ? XPLAINUtil
          .recordTiming(beginitertime) : 0;
      if (observer != null) {
        observer.afterResultSetHolderRowRead(this.rowFormatter, projRow, act);
      }
      rows_returned++;
      return projRow;
    } catch (ClassNotFoundException cnfe) {
      throw StandardException.newException(SQLState.SET_STREAM_FAILURE, cnfe,
          cnfe.getMessage());
    } catch (IOException ioe) {
      throw StandardException.newException(SQLState.SET_STREAM_FAILURE, ioe,
          ioe.getMessage());
    }
  }

  public void applyRemoteTXChanges(DistributedMember sender) {
    final TXChanges txChanges;
    if (this.ers == null && (txChanges = this.txChanges) != null) {
      try {
        txChanges.applyLocally(sender);
      } catch (Throwable t) {
        this.exception = t;
        this.exceptionMember = sender;
        this.dis = null;
      }
      this.txChanges = null;
    }
  }

  /**
   * Return whether projection is being applied at ResultHolder for remote
   * ResultHolders. For local execution always returns true.
   */
  public final boolean hasProjectionFromRemote() {
    if (this.ers == null) {
      return GemFireXDUtils.isSet(state, DO_PROJECTION);
    }
    // returning true for local case works fine since for local execution
    // the incoming projRow is ignored, so it doesn't matter whether
    // ValueRow or a CompactExecRow is sent to getNext() for local ResultHolder
    return true;
  }

  /**
   * @return boolean if true indicates that this resultset has been exhausted
   *         and that no more rows are left to be iterated else it returns false
   *         indicating this resultset has still got rows to serve
   * @throws StandardException
   */
  public final boolean hasNext(final Activation act) throws StandardException {

    final boolean hasMoreRows;
    if (this.ers != null) {
      if (this.nextRowIndex >= this.candidateRowArraySize && this.hasMoreRows) {
        // setup the next set of results
        final boolean hasNext = moveNextResults();
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "ResultHolder::getNext: Local read: got next batch of size "
                  + this.candidateRowArraySize + " hasNext=" + hasNext);
        }
      }
      hasMoreRows = this.nextRowIndex < this.candidateRowArraySize;
      if (GemFireXDUtils.TraceRSIter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
            "ResultHolder#hasNext: local result available=" + hasMoreRows);
      }
    }
    else {
      final Throwable t = this.exception;
      if (t != null) {
        processException(t, this.firstCall);
      }
      final ByteArrayDataInput dis = this.dis;
      hasMoreRows = (dis != null && dis.available() > 0);
      if (GemFireXDUtils.TraceRSIter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
            "ResultHolder#hasNext: remote result available=" + hasMoreRows);
      }

      if (!hasMoreRows) {
        // release the stream
        this.dis = null;
      }
    }
    this.firstCall = false;

    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      if (!hasMoreRows) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "-- end of result holder iteration --");
      }
    }

    // clock didn't tick, so at least keep 1 nano second.
    if (!hasMoreRows && recordStats && process_time == 0 && ser_deser_time == 0) {
      process_time += 1;
    }
    
    return hasMoreRows;
  }

  /**
   * Process any exception received during ResultHolder deserialization.
   * 
   * @param firstCall
   *          if this is the first call whereby transparent failover is
   *          possible, else if some results have already been consumed then app
   *          will have to do the retry at its level after checking for
   *          {@link SQLState#GFXD_NODE_SHUTDOWN} SQLException state.
   * 
   * @throws StandardException
   *           standard error policy
   */
  private void processException(final Throwable t, final boolean firstCall)
      throws StandardException {
    // For the first call throw runtime exceptions as such so retries, for
    // example, can happen transparently.
    if (firstCall) {
      GemFireXDUtils.throwIfRuntimeException(t);
    }
    // Check if remote node is going down, and in that case throw an exception
    // to indicate that the node needs to retry the query (bug #41471).
    GemFireXDUtils.processCancelException("ResultHolder::processException", t,
        this.exceptionMember);
    StandardException se = Misc.processFunctionException("getNext", t,
        this.exceptionMember, null, false);
    if (se != null) {
      throw se;
    }
    throw StandardException.newException(SQLState.NO_CURRENT_ROW, t);
  }

  public long estimateMemoryUsage() throws StandardException {
    final EmbedResultSet ers = this.ers;
    final EmbedStatement es = this.es;
    if (es != null) {
      assert ers != null;
      return Misc.estimateMemoryUsage(es.getEmbedConnection()
          .getLanguageConnection(), ers.getSourceResultSet(), es.getSQLText());
    }
    else {
      return stream_size;
    }
  }

  final Throwable getException() {
    return this.exception;
  }

  /**
   * called during query plan collection.
   */
  public void assertNoData() {
    if (this.dis != null) {
      SanityManager.THROWASSERT("Data chunk not released ... possible memory "
          + "overhead during statistics collection");
    }
  }

  public EmbedResultSet getERS() {
    if (SanityManager.ASSERT) {
      try {
        SanityManager.ASSERT(ers == null || ers.isClosed(), "ERS should be "
            + "closed at this time, otherwise timings may not be appropriate.");
      } catch (SQLException ignore) {
        SanityManager.DEBUG_PRINT("warning:" + GfxdConstants.TRACE_FUNCTION_EX,
            "Ignoring exception " + ignore, ignore);
      }
    }
    return ers;
  }

  public PreparedStatement getGPrepStmt() {
    return gps;
  }

  @Override
  public byte getGfxdID() {
    return RESULT_HOLDER;
  }
  
  public void buildResultSetString(StringBuilder builder) {
    if(this.ers != null) {
      this.ers.buildResultSetString(builder);
    }
  }

  public void freeOffHeapForCachedRowsAndCloseResultSet() {
    if (this.isGenuinelyLocallyExecuted()) {
      if (this.candidateRowArray != null && GemFireXDUtils.isOffHeapEnabled()) {
        while (nextRowIndex < this.candidateRowArraySize) {
          ExecRow row = this.candidateRowArray[nextRowIndex++];
          if (row != null) {
            row.releaseByteSource();
          }
        }
      }
      this.close(null, null);
    }
  }

  private void freeOffHeapForCachedRows(List<ExecRow> results) {
    if (GemFireXDUtils.isOffHeapEnabled()) {
      if (results != null) {
        for (ExecRow row : results) {
          if (row != null) {
            row.releaseByteSource();
          }
        }
      }
    }
  }

  /**
   * snapshot the statistics as this object is reused during streaming
   * of reply on remote node.
   * 
   * @see DistributionPlanCollector#createSendReceiveResultSet
   * @return set of result holder statistics.
   */
  public long[] snapshotStatistics() {
    return new long[] { ser_deser_time, process_time, throttle_time, numRows };
  }
}
