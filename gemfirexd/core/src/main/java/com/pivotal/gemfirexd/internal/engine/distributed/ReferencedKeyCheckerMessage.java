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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.GemFireIOException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.execute.EmptyRegionFunctionException;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.NoDataStoreAvailableException;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.cache.partitioned.RegionAdvisor;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TIntHashSet;
import com.gemstone.gnu.trove.TIntIntHashMap;
import com.gemstone.gnu.trove.TIntIterator;
import com.gemstone.gnu.trove.TObjectObjectProcedure;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.message.BitSetSet;
import com.pivotal.gemfirexd.internal.engine.distributed.message.MemberExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.types.DataType;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import io.snappydata.collection.IntObjectHashMap;

/**
 * This function is used to check the referenced key constraint after we
 * obtained all rows to be deleted from a parent table. Note: this function is
 * only used in the case of the parent table and its dependent tables are not
 * colocated.
 * 
 * The function parameters includes the schema and table names of a parent table
 * and a set of row values to be deleted.
 * 
 * @author yjing
 * @author swale
 */
public final class ReferencedKeyCheckerMessage extends
    MemberExecutorMessage<ArrayList<List<Object>>> {

  private String schemaName;

  private String tableName;

  private ArrayList<DataValueDescriptor[]> keyColumnDVDs;
  private int numColumns;
  private ArrayList<byte[]> keyRows;

  private long connectionId;

  private final transient ArrayList<RowLocation> keyColumnLocations;

  private transient GemFireContainer container;

  private final transient GemFireContainer[] refContainers;

  private final transient THashMap hostedBuckets;

  private boolean forUpdate;
  private int[] refImpactedCols;
  private transient FormatableBitSet refColsUpdtdBits;
  private transient int [] refCol2DVDPosMapping;
  private final transient IntObjectHashMap<TIntHashSet> refColUpdtd2DependentCols;
  private final transient TIntIntHashMap refCol2IndexMap;
  private transient IntObjectHashMap<byte[]> refColSameAfterModBitsMapping;

  private transient GfxdConnectionWrapper wrapperForMarkUnused;

  private static final TObjectObjectProcedure postExecutionProc =
      new TObjectObjectProcedure() {
    @Override
    public final boolean execute(final Object key, final Object value) {
      GemFireContainer gfc = (GemFireContainer)key;
      // For this container find out the members hosting primary and buckets
      // hosted by each member
      RegionExecutorMessage.HashMapOrSet beforeExecutionState =
          (RegionExecutorMessage.HashMapOrSet)value;
      final PartitionedRegion pr = (PartitionedRegion)gfc.getRegion();
      final RegionAdvisor advisor = pr.getRegionAdvisor();
      final RegionExecutorMessage.HashMapOrSet afterExecutionState =
          new RegionExecutorMessage.HashMapOrSet(true, pr);
      advisor.accept(RegionExecutorMessage.collectPrimaries,
          afterExecutionState);
      if (afterExecutionState.size() != beforeExecutionState.size()) {
        for (Object m : beforeExecutionState.keySet()) {
          if (afterExecutionState.remove(m) == null) {
            BitSetSet movedBuckets = (BitSetSet)beforeExecutionState.get(m);
            throw new BucketMovedException(
                LocalizedStrings.FunctionService_BUCKET_MIGRATED_TO_ANOTHER_NODE
                    .toLocalizedString(), movedBuckets.nextSetBit(0, 0), pr
                    .getFullPath());
          }
        }
        if (afterExecutionState.size() > 0) {
          BitSetSet movedBuckets = (BitSetSet)afterExecutionState.values()
              .iterator().next();
          throw new BucketMovedException(
              LocalizedStrings.FunctionService_BUCKET_MIGRATED_TO_ANOTHER_NODE
                  .toLocalizedString(),
              movedBuckets.nextSetBit(0, 0), pr.getFullPath());
        }
      }
      beforeExecutionState.forEachEntry(new TObjectObjectProcedure() {
        @Override
        public final boolean execute(final Object key, final Object value) {
          DistributedMember member = (DistributedMember)key;
          BitSetSet beforeBitSetSet = (BitSetSet)value;
          BitSetSet afterBitSetSet = (BitSetSet)afterExecutionState
              .get(member);
          if (afterBitSetSet == null
              || beforeBitSetSet.size() != afterBitSetSet.size()) {
            if (afterBitSetSet != null) {
              beforeBitSetSet.removeAll(afterBitSetSet);
            }
            throw new BucketMovedException(
                LocalizedStrings.FunctionService_BUCKET_MIGRATED_TO_ANOTHER_NODE
                    .toLocalizedString(), beforeBitSetSet.nextSetBit(0, 0), pr
                    .getFullPath());
          }
          for (int valBefore = beforeBitSetSet.nextSetBit(0, 0),
              valAfter = afterBitSetSet.nextSetBit(0, 0); valBefore != -1;
              valBefore = beforeBitSetSet.nextSetBit(valBefore + 1),
                  valAfter = afterBitSetSet.nextSetBit(valAfter + 1)) {
            if (valBefore != valAfter) {
              throw new BucketMovedException(LocalizedStrings
                  .FunctionService_BUCKET_MIGRATED_TO_ANOTHER_NODE
                      .toLocalizedString(), valBefore, pr.getFullPath());
            }
          }
          return true;
        }
      });
      return true;
    }
  };

  /** Default constructor for deserialization. Not to be invoked directly. */
  public ReferencedKeyCheckerMessage() {
    super(true);
    this.keyColumnLocations = null;
    this.refContainers = null;
    this.hostedBuckets = null;
    this.refColUpdtd2DependentCols= null; 
    this.refCol2IndexMap = null;
  }

  public ReferencedKeyCheckerMessage(final ReferencedKeyResultCollector rc,
      final TXStateInterface tx, final LanguageConnectionContext lcc,
      final GemFireContainer container,
      final ArrayList<DataValueDescriptor[]> keyColumnValues,
      final ArrayList<RowLocation> keyColumnLocations,
      final GemFireContainer[] refContainers, boolean forUpdate, int [] refImpactedCols,
      FormatableBitSet refColsUpdtdBits,
      IntObjectHashMap<TIntHashSet> refColUpdtd2DependentCols,
      TIntIntHashMap refCol2IndexMap, int[] colToDVDPosMapping ) {
    super(rc, tx, getTimeStatsSettings(lcc), true);
    this.container = container;
    this.schemaName = container.getSchemaName();
    if (this.schemaName == null) {
      this.schemaName = Misc.getDefaultSchemaName(lcc);
    }
    this.tableName = container.getTableName();
    this.keyColumnDVDs = keyColumnValues;
    this.keyColumnLocations = keyColumnLocations;
    this.connectionId = lcc.getConnectionId();
    this.refContainers = refContainers;
    this.hostedBuckets = new THashMap();
    this.forUpdate = forUpdate;
    this.refImpactedCols = refImpactedCols;
    this.refColsUpdtdBits = refColsUpdtdBits;
    this.refCol2DVDPosMapping = colToDVDPosMapping;
    this.refColUpdtd2DependentCols = refColUpdtd2DependentCols;
    this.refCol2IndexMap = refCol2IndexMap;
  }

  protected ReferencedKeyCheckerMessage(
      final ReferencedKeyCheckerMessage other) {
    super(other);
    this.container = other.container;
    this.schemaName = other.schemaName;
    this.tableName = other.tableName;
    this.keyColumnDVDs = other.keyColumnDVDs;
    this.keyColumnLocations = other.keyColumnLocations;
    this.connectionId = other.connectionId;
    this.refContainers = other.refContainers;
    this.hostedBuckets = new THashMap();
    this.forUpdate = other.forUpdate;
    this.refImpactedCols = other.refImpactedCols;
    this.refColsUpdtdBits = other.refColsUpdtdBits;
    this.refCol2DVDPosMapping = other.refCol2DVDPosMapping;
    this.refColUpdtd2DependentCols = other.refColUpdtd2DependentCols;
    this.refCol2IndexMap = other.refCol2IndexMap;
  }

  @Override
  protected void execute() throws SQLException, StandardException {

    this.container = (GemFireContainer)Misc.getRegionByPath(
        Misc.getRegionPath(this.schemaName, this.tableName, null), true)
        .getUserAttribute();

    if (this.keyColumnDVDs == null && this.keyRows != null) {
      final int columns = this.numColumns;
      final int numRows = this.keyRows.size();
      this.keyColumnDVDs = new ArrayList<>(numRows);
      final ExtraTableInfo tableInfo = this.container.getExtraTableInfo();
      final RowFormatter rf;
      // if(forUpdate) {
      // rf = tableInfo.getReferencedKeyRowFormatter()
      // .getSubSetReferenceKeyFormatter(this.refImpactedCols);
      // }else {
      // rf = tableInfo.getReferencedKeyRowFormatter();
      // }
      rf = tableInfo.getReferencedKeyRowFormatter();

      // final int[] keyPositions =
      // this.forUpdate?this.refImpactedCols:tableInfo.getReferencedKeyColumns();
      final int[] keyPositions = tableInfo.getReferencedKeyColumns();
      assert columns == keyPositions.length : "numKeys=" + keyPositions.length
          + ", read=" + columns;

      byte[] rowBytes;

      DataValueDescriptor[] row;
      for (int index = 0; index < numRows; index++) {
        rowBytes = this.keyRows.get(index);
        if (rowBytes != null) {
          row = new DataValueDescriptor[columns];
          for (int col = 1; col <= columns; col++) {
            row[col - 1] = rf.getColumn(col, rowBytes);
          }
          this.keyColumnDVDs.add(row);
        }
      }
      // free the keyRows
      this.keyRows = null;
    }

    final DataDictionary dd;
    LanguageConnectionContext lcc = null;
    GemFireTransaction tc = null;
    final GemFireContainer container = this.container;
    final ExtraTableInfo tableInfo;
    final HashMap<int[], long[]> keyColumns2IndexNumbers;
    final int[] columnPositions;
    final TIntIntHashMap columnPositions2Index;
    boolean setRowsInLCC = false;

    boolean contextSet = false;
    this.wrapperForMarkUnused = null;
    final GfxdConnectionWrapper wrapper = GfxdConnectionHolder
        .getOrCreateWrapper(null, this.connectionId, false, null);
    final EmbedConnection conn = wrapper.getConnectionForSynchronization();
    int syncVersion = -1;
    // acquire the lock on connection so that subsequent requests on the same
    // connection get sequenced out
    synchronized (conn.getConnectionSynchronization()) {
      boolean oldPosDup = false;
      try {
        syncVersion = wrapper.convertToHardReference(conn);
        conn.getTR().setupContextStack();
        contextSet = true;
        lcc = wrapper.getLanguageConnectionContext();
        oldPosDup = lcc.isPossibleDuplicate();
        lcc.setPossibleDuplicate(isPossibleDuplicate());
        FormatableBitSet colsToWritePerRowLocal = null;
        // for the case of self-reference key checks, skip the rows being
        // deleted themselves from reference key check (#43159)
        if (isLocallyExecuted()) {
          if(this.forUpdate) {
            colsToWritePerRowLocal = new FormatableBitSet(
                this.refImpactedCols.length);
          }
          for (GemFireContainer refContainer : this.refContainers) {
            if (container == refContainer) {
              @SuppressWarnings("unchecked")
              final Collection<RowLocation> rows = new THashSet(
                  this.keyColumnLocations);
              lcc.setReferencedKeyCheckRows(rows);
              setRowsInLCC = true;
              break;
            }
          }
        }
        // get data dictionary
        dd = lcc.getDataDictionary();
        tc = (GemFireTransaction)lcc.getTransactionExecute();
        GfxdConnectionWrapper.checkForTransaction(conn, tc, getTXState());

        tableInfo = container.getExtraTableInfo();
        columnPositions = this.forUpdate ? this.refImpactedCols : tableInfo
            .getReferencedKeyColumns();
        int rowCount = this.keyColumnDVDs != null ? this.keyColumnDVDs.size()
            : this.keyColumnLocations.size();
        keyColumns2IndexNumbers = tableInfo
            .getReferencedKeyColumns2IndexNumbers();
        columnPositions2Index = buildColumnPosition2ArrayIndex(  columnPositions );
        for (int rowNumber = 0; rowNumber < rowCount; rowNumber++) {
          DataValueDescriptor[] dvdRow = getKeyColumnValues(rowNumber);
          if (dvdRow == null) {
            continue;
          }
          if(isLocallyExecuted() && this.forUpdate) {
            //nullify the unmodified or modified to same value, cols which are not to be checked
            colsToWritePerRowLocal.clear();
            this.identifyUpdatedColsUnchangedAndColsToWrite(dvdRow, colsToWritePerRowLocal, rowNumber);
           
          }
          final GemFireXDUtils.Pair<Long, RowLocation> failPosition =
              existingKeyInDependentTables(tc, columnPositions2Index,
                  dvdRow, keyColumns2IndexNumbers, rowNumber, colsToWritePerRowLocal);
          if (failPosition != null) {
            final long indexNumber = failPosition.getKey().longValue();
            final ConglomerateDescriptor indexCD = dd
                .getConglomerateDescriptor(indexNumber);
            final String constraintName = indexCD.getConglomerateName();
            final ArrayList<Object> failure = new ArrayList<Object>(4);
            failure.add(constraintName);
            failure.add(RowUtil.toString(dvdRow));
            // before sending back an exception, check for node going down
            Misc.getGemFireCache().getCancelCriterion()
                .checkCancelInProgress(null);
            lastResult(failure, false, true, true);
            return;
          }
        }
      } finally {
        if (lcc != null) {
          lcc.setPossibleDuplicate(oldPosDup);
          if (setRowsInLCC) {
            lcc.setReferencedKeyCheckRows(null);
          }
        }
        // reset cached TXState
        if (tc != null) {
          tc.resetTXState();
        }
        // check if cache is closing in which case our result may be incorrect
        // cc.checkCancelInProgress(null);
        if (contextSet) {
          conn.getTR().restoreContextStack();
        }
        // wrapper can get closed for a non-cached Connection
        if (!wrapper.isClosed()) {
          if (wrapper.convertToSoftReference(syncVersion)) {
            this.wrapperForMarkUnused = wrapper;
          }
        }
        
        //#49353: set this.wrapperForMarkUnused, even if wrapper is closed.
        this.wrapperForMarkUnused = wrapper;
      }
    }
    // this should be *outside* of sync(conn) block and always the last thing
    // to be done related to connection set/reset
    endMessage();
    lastResult(Boolean.TRUE, false, true, true);
  }
  
  @Override
  protected void endMessage() {
    if (this.wrapperForMarkUnused != null) {
      this.wrapperForMarkUnused.markUnused();
      this.wrapperForMarkUnused = null;
    }
  }

  private int getIndexInLocalDVDRow(int colNum) {
      
      int posInExecRow = this.refCol2DVDPosMapping != null
          ? this.refCol2DVDPosMapping[colNum - 1] : colNum - 1;
    return posInExecRow;

  }

  GemFireXDUtils.Pair<Long, RowLocation> existingKeyInDependentTables(
      GemFireTransaction tc, final TIntIntHashMap columnPositions2Index,
      DataValueDescriptor[] columnValues,
      HashMap<int[], long[]> keyColumns2IndexNumbers, int rowNum,
      FormatableBitSet colsToWritePerRowLocal) throws StandardException {

    
    ScanController sc;
    long indexNumber;
    Iterator<int[]> keyIt = keyColumns2IndexNumbers.keySet().iterator();
    
    outer: while (keyIt.hasNext()) {

      int[] keyColumnPositions = keyIt.next();
      long[] indexNumbers = keyColumns2IndexNumbers.get(keyColumnPositions);

      // generate search condition;

      int keyLength = keyColumnPositions.length;
      DataValueDescriptor[] startOperand = new DataValueDescriptor[keyLength];
      boolean foundChangedKey = false;
      for (int i = 0; i < keyLength; i++) {
        int arrayIndex = columnPositions2Index.get(keyColumnPositions[i]);
        if (arrayIndex == 0
            && !columnPositions2Index.containsKey(keyColumnPositions[i])) {
          continue outer;
        }
        if (colsToWritePerRowLocal != null) {
          boolean shouldWriteValue = colsToWritePerRowLocal.get(arrayIndex);
          if (shouldWriteValue) {
            int posInRow = isLocallyExecuted() && this.forUpdate ? this
                .getIndexInLocalDVDRow(keyColumnPositions[i]) : arrayIndex;
            startOperand[i] = columnValues[posInRow];
          }
          else {
            startOperand[i] = null;
          }
        }
        else {
          int posInRow = isLocallyExecuted() && this.forUpdate ? this
              .getIndexInLocalDVDRow(keyColumnPositions[i]) : arrayIndex;
          startOperand[i] = columnValues[posInRow];
        }
        if (this.forUpdate && (startOperand[i] == null || startOperand[i].isNull())) {
          // If for update the col value is null, implies that the
          // check needs to be skipped
          assert !foundChangedKey;
          continue outer;
        }

        if (this.forUpdate) {
          // for the array index check if the column is updated or not

          if (!foundChangedKey && this.refColsUpdtdBits.isSet(arrayIndex)) {
            // check if it is not unset by the specific row flag
            byte[] refColsSameAfterUpdtBit = refColSameAfterModBitsMapping != null
                ? (byte[])this.refColSameAfterModBitsMapping.get(rowNum) : null;
            if (refColsSameAfterUpdtBit == null) {
              foundChangedKey = true;
            }
            else {
              // check if the ref col indicated at array pos is
              // unmodified by the row specific data
              // identify the set bit index
              int pos = this.refColsUpdtdBits.anySetBit();
              int j = -1;
              while (pos != -1) {
                ++j;
                if (pos == arrayIndex) {
                  break;
                }
                pos = this.refColsUpdtdBits.anySetBit(pos);
              }
              // check at j th index if the bit is switched off or
              // not
              final int byteIndex = FormatableBitSet.udiv8(j);
              final byte bitIndex = FormatableBitSet.umod8(j);
              boolean isSet = (refColsSameAfterUpdtBit[byteIndex] & (0x80 >> bitIndex)) != 0;
              if (!isSet) {
                foundChangedKey = true;
              }
            }
          }
        }
      }
      if (this.forUpdate && !foundChangedKey) {
        continue;
      }
      for (int j = 0; j < indexNumbers.length; j++) {
        indexNumber = indexNumbers[j];

        sc = tc.openScan(indexNumber, false,
            GfxdConstants.SCAN_OPENMODE_FOR_READONLY_LOCK,
            TransactionController.MODE_TABLE,
            TransactionController.ISOLATION_NOLOCK, null, startOperand,
            ScanController.GE, null, startOperand, ScanController.GT, null);

        if (sc.next()) {
          final RowLocation rl = sc.fetchLocation(null);
          sc.close();
          return new GemFireXDUtils.Pair<Long, RowLocation>(
              Long.valueOf(indexNumber), rl);
        }
        else {
          sc.close();
        }
      }
    }
    return null;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  public boolean isHA() {
    return true;
  }

  @Override
  public void reset() {
    super.reset();
    if (this.hostedBuckets != null) {
      this.hostedBuckets.clear();
    }
  }

  private  TIntIntHashMap buildColumnPosition2ArrayIndex(
      final int[] columns) {
    final TIntIntHashMap columnPositions2Index = new TIntIntHashMap();
    for (int index = 0; index < columns.length; index++) {
        columnPositions2Index.put(columns[index], index);      
    }
    return columnPositions2Index;
  }

  public static void referencedKeyCheck(final GemFireContainer container,
      final LanguageConnectionContext lcc,
      final ArrayList<DataValueDescriptor[]> keyColumnValues,
      final ArrayList<RowLocation> keyColumnLocations, boolean flushTXPendingOps,
      boolean forUpdate, int[] referencedImpactedCols,FormatableBitSet refColsUpdtdBits, 
      IntObjectHashMap<TIntHashSet> refColUpdtd2DependentCols,
      TIntIntHashMap refCol2IndexMap, int[] colNumToDVDMapping)
      throws StandardException {
    final GemFireContainer[] refContainers = container.getExtraTableInfo()
        .getReferencedContainers();
    ReferencedKeyResultCollector rkrc = new ReferencedKeyResultCollector();
    final TXStateInterface tx = getCurrentTXState(lcc);
    final ReferencedKeyCheckerMessage checkMsg = new ReferencedKeyCheckerMessage(
        rkrc, tx, lcc, container, keyColumnValues, keyColumnLocations,
        refContainers, forUpdate, referencedImpactedCols, refColsUpdtdBits,
        refColUpdtd2DependentCols, refCol2IndexMap, colNumToDVDMapping);
    Collection<?> result = null;
    try {
      if (tx != null) {
        if (tx != tx.getProxy()) {
          GemFireXDUtils.throwAssert("ReferencedKeyCheckerMessage#"
              + "referencedKeyCheck: expected same proxies but got: " + tx
              + " and " + tx.getProxy());
        }
      }
      result = checkMsg.executeFunction(false, lcc.isPossibleDuplicate(), null,
          false);
    } catch (StandardException se) {
      // Neeraj: deliberately ignore the exception because initially
      // this code was running even if result was null. Now with the fix
      // for bug #41633 we get exception if there are no nodes to run an
      // internal function. So we catch this particular exception and ignore.
      // [sumedh] also see #44100
      if (!SQLState.LANG_INVALID_MEMBER_REFERENCE.equals(se.getMessageId())) {
        throw Misc.processFunctionException(
            "ReferencedKeyCheckerMessage#referencedKeyCheck", se, null,
            container.getRegion());
      }
    } catch (EmptyRegionFunctionException ignored) {
      // ignored
    } catch (GemFireException gfeex) {
      throw Misc.processGemFireException(gfeex, gfeex,
          "reference key check on parent " + container.getQualifiedTableName(),
          true);
    } catch (Exception e) {
      throw Misc.processFunctionException(
          "ReferencedKeyCheckerMessage#referencedKeyCheck", e, null,
          container.getRegion());
    }
    if (result != null && result.size() > 0) {
      assert rkrc.size() == result.size();
      StandardException topEx = null;
      StandardException currSe = null;
      // [yogesh] : fix for #45008. Looks like it is possible that the
      // the results are getting added in ReferencedKeyResultCollector during
      // following iteration. This causes ConcurrentModifcationException.
      // Ideally, this should not happen, as the result should be ready before
      // iteration. Making a copy of the result list to avoid
      // ConcurrentModifcationException. Or ReferencedKeyResultCollector
      // can extend CopyOnWriteArrayList
      List<List<Object>> temp = new ArrayList<List<Object>>();
      temp.addAll(rkrc);
      for (List<Object> failure : temp) {
        final StandardException se = StandardException.newException(
            SQLState.LANG_FK_VIOLATION, failure.get(0),
            container.getQualifiedTableName(), forUpdate ? "UPDATE" : "DELETE",
            failure.get(1) + " on member " + failure.get(2));
        if (topEx == null) {
          topEx = se;
          currSe = topEx;
        }
        else {
          currSe.initCause(se);
          currSe = se;
        }
      }
      throw topEx;
    }
  }

  public String getSchemaName() {
    return schemaName;
  }

  public String getTableName() {
    return tableName;
  }

  public long getConnectionId() {
    return this.connectionId;
  }

  public DataValueDescriptor[] getKeyColumnValues(int listPosition)
      throws StandardException {
    if (this.keyColumnDVDs != null) {
      return this.keyColumnDVDs.get(listPosition);
    }
    DataValueDescriptor[] keys = null;
    final int[] keyPositions = this.forUpdate ? this.refImpactedCols
        : this.container.getExtraTableInfo().getReferencedKeyColumns();
    // byte[] rowBytes;
    RowLocation rowLoc;
    @Retained @Released Object rowValue;
    ExtraTableInfo tableInfo;
    RowFormatter rf;
    rowLoc = this.keyColumnLocations.get(listPosition);
    SimpleMemoryAllocatorImpl.skipRefCountTracking();
    rowValue = RegionEntryUtils.getByteSource(rowLoc, this.container, false,
        false);
    SimpleMemoryAllocatorImpl.unskipRefCountTracking();
    if (rowValue != null) {
      keys = new DataValueDescriptor[keyPositions.length];
      final Class<?> cls = rowValue.getClass();
      if (cls == byte[].class) {
        final byte[] rowBytes = (byte[])rowValue;
        tableInfo = rowLoc.getTableInfo(this.container);
        rf = tableInfo.getRowFormatter(rowBytes);
        rf.getColumns(rowBytes, keys, keyPositions);
      }
      else if (cls == byte[][].class) {
        final byte[][] rowBytes = (byte[][])rowValue;
        tableInfo = rowLoc.getTableInfo(this.container);
        rf = tableInfo.getRowFormatter(rowBytes[0]);
        rf.getColumns(rowBytes, keys, keyPositions);
      }
      else {
        OffHeapByteSource bs = null;
        try {
          if (cls == OffHeapRow.class) {
            final OffHeapRow rowBS = (OffHeapRow)rowValue;
            bs = rowBS;
            tableInfo = rowLoc.getTableInfo(this.container);
            rf = tableInfo.getRowFormatter(rowBS);
            rf.getColumns(rowBS, keys, keyPositions);
          }
          else {
            final OffHeapRowWithLobs rowBS = (OffHeapRowWithLobs)rowValue;
            bs = rowBS;
            tableInfo = rowLoc.getTableInfo(this.container);
            rf = tableInfo.getRowFormatter(rowBS);
            rf.getColumns(rowBS, keys, keyPositions);
          }
        } finally {
          if (bs != null) {
            SimpleMemoryAllocatorImpl.skipRefCountTracking();
            bs.release();
            SimpleMemoryAllocatorImpl.unskipRefCountTracking();
          }
        }
      }
    }
    return keys;
  }

  @Override
  public boolean canStartRemoteTransaction() {
    // referenced key check needs to be able to start transactions else
    // it may never conflict (#44731)
    return true;
  }

  @Override
  protected boolean requiresTXFlushBeforeExecution() {
    return false;
  }

  @Override
  protected boolean requiresTXFlushAfterExecution() {
    // TODO: PERF: somehow flush only on new nodes not already covered by the
    // distributed delete, or somehow only flush after the current operation's
    // "checkpoint" -- same holds for all other messages that do force the flush
    return getLockingPolicy().readOnlyCanStartTX();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Set<DistributedMember> getMembers() {
    // [sumedh]: need to execute on all referenced regions and not
    // this region, since the referenced regions may be on different nodes
    // due to server groups (see bug #40240); when we support the scenario
    // of members going up and down with bucket rebalancing then this may have
    // a small window of missing a new member
    // TODO: Below can be further optimized to select such a replicate that is
    // present in other replicate sets that will come in the loop later
    this.hostedBuckets.clear();
    Set<DistributedMember> allRecips = new HashSet<DistributedMember>();
    final InternalDistributedMember myId = Misc.getGemFireCache().getMyId();
    for (GemFireContainer container : this.refContainers) {
      LocalRegion refRegion = container.getRegion();
      if (refRegion.getPartitionAttributes() != null) {
        // using primaries for now; a more optimal way will be to select
        // minimal set of nodes having primaries+secondaries;
        // "onRegions" will likely handle that
        final PartitionedRegion pr = (PartitionedRegion)refRegion;
        RegionAdvisor advisor = pr.getRegionAdvisor();
        final RegionExecutorMessage.HashMapOrSet map =
            new RegionExecutorMessage.HashMapOrSet(true, pr);
        advisor.accept(RegionExecutorMessage.collectPrimaries, map);
        this.hostedBuckets.put(container, map);
        allRecips.addAll(map.keySet());
      }
      else if (!refRegion.getScope().isLocal()) {
        Set<InternalDistributedMember> recips = ((DistributedRegion)refRegion)
            .getCacheDistributionAdvisor().adviseInitializedReplicates();
        // add self to the set if required
        if (refRegion.getDataPolicy().withReplication()) {
          if (recips.size() == 0) {
            recips = new HashSet<InternalDistributedMember>(2);
          }
          recips.add(myId);
        }
        // if one of the existing members is already selected then no need
        // to add a new one
        boolean foundMember = false;
        for (Object recip : recips) {
          if (allRecips.contains(recip)) {
            foundMember = true;
            break;
          }
        }
        if (!foundMember) {
          if (refRegion.getDataPolicy().withReplication()) {
            allRecips.add(myId);
          }
          else {
            Iterator<InternalDistributedMember> iter = recips.iterator();
            if (iter.hasNext()) {
              allRecips.add(iter.next());
            }
            else {
              // no member found; check whether this is due to offline
              // persistent region or no data; for latter ignore the check
              if (GemFireXDUtils.isPersistent(refRegion)) {
                throw new NoDataStoreAvailableException(LocalizedStrings
                    .DistributedRegion_NO_DATA_STORE_FOUND_FOR_DISTRIBUTION
                        .toLocalizedString(refRegion));
              }
            }
          }
        }
      }
    }
    return allRecips;
  }

  @Override
  public void postExecutionCallback() {
    // Check if the state of hosted buckets before execution is same as after
    // execution
    this.hostedBuckets.forEachEntry(postExecutionProc);
  }

  @Override
  protected ReferencedKeyCheckerMessage clone() {
    return new ReferencedKeyCheckerMessage(this);
  }

  @Override
  public byte getGfxdID() {
    return REFERENCED_KEY_CHECK_MSG;
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.schemaName = DataSerializer.readString(in);
    this.tableName = DataSerializer.readString(in);
    final int columns = InternalDataSerializer.readArrayLength(in);
    this.numColumns = columns;
    final boolean hasDVDs = in.readBoolean();

    final int rows;
    if (hasDVDs) {
      this.forUpdate = in.readBoolean();
      if (this.forUpdate) {
        this.refImpactedCols = new int[columns];
        for (int i = 0; i < columns; ++i) {
          this.refImpactedCols[i] = (int)InternalDataSerializer
              .readUnsignedVL(in);
        }
        byte[] refColsUpdtd = new byte[FormatableBitSet
            .numBytesFromBits(this.refImpactedCols.length)];
        for (int i = 0; i < refColsUpdtd.length; ++i) {
          refColsUpdtd[i] = in.readByte();
        }
        this.refColsUpdtdBits = new FormatableBitSet(refColsUpdtd);

      }
      rows = InternalDataSerializer.readArrayLength(in);
      this.keyColumnDVDs = new ArrayList<DataValueDescriptor[]>(rows);

      for (int i = 0; i < rows; i++) {
        final DataValueDescriptor[] row = new DataValueDescriptor[columns];
        for (int j = 0; j < columns; j++) {
          row[j] = DataType.readDVD(in);
        }
        this.keyColumnDVDs.add(row);
      }
      if (this.forUpdate) {
        boolean refColSameAfterModFlag = in.readBoolean();
        if (refColSameAfterModFlag) {
          int numElements = in.readInt();
          this.refColSameAfterModBitsMapping = IntObjectHashMap.withExpectedSize(
              numElements);
          int byteArraySize = FormatableBitSet
              .numBytesFromBits(this.refColsUpdtdBits.getNumBitsSet());
          for (int i = 0; i < numElements; ++i) {
           
            int rowNum = in.readInt();
            byte[] bytes = new byte[byteArraySize];
            for (int j = 0; j < byteArraySize; ++j) {
              bytes[j] = in.readByte();
            }
            this.refColSameAfterModBitsMapping.put(rowNum,bytes);
            
          }
        }
      }
    }
    else {

      // if(this.forUpdate) {
      // this.refImpactedCols = new int[columns];
      // for(int i =0; i < columns;++i) {
      // this.refImpactedCols[i] =
      // InternalDataSerializer.readUnsignedVL(in);
      // }
      // }
      rows = InternalDataSerializer.readArrayLength(in);
      this.keyRows = new ArrayList<>(rows);
      this.keyColumnDVDs = null;
      for (int index = 0; index < rows; index++) {
        this.keyRows.add(DataSerializer.readByteArray(in));
      }
    }
    this.connectionId = GemFireXDUtils.readCompressedHighLow(in);
    if (timeStatsEnabled) {
      this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
    }
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    //TODO:Asif: Check if distributing to oether nodes this map can be reused.
    //Temporarily rcreating it for every toData
    this.refColSameAfterModBitsMapping = null;
    final long begintime = this.timeStatsEnabled ? XPLAINUtil
        .recordTiming(ser_deser_time == 0 ? ser_deser_time = -1 /* record */
        : -2/* ignore nested call */) : 0;
    DataSerializer.writeString(this.schemaName, out);
    DataSerializer.writeString(this.tableName, out);
    ExtraTableInfo tableInfo = this.container.getExtraTableInfo();
    final int[] keyPositions = this.forUpdate ? this.refImpactedCols
        : tableInfo.getReferencedKeyColumns();
    InternalDataSerializer.writeArrayLength(keyPositions.length, out);
    if (this.keyColumnDVDs != null) {
      out.writeBoolean(true);
      out.writeBoolean(this.forUpdate);
      if (this.forUpdate) {
        for (int refCol : this.refImpactedCols) {
          InternalDataSerializer.writeUnsignedVL(refCol, out);
        }
        for (byte bits : this.refColsUpdtdBits.getByteArray()) {
          out.writeByte(bits);
        }
      }
      InternalDataSerializer.writeArrayLength(this.keyColumnDVDs.size(), out);
      if (this.forUpdate) {
        // FormatableBitSet refColsSameAfterUpdtPerRow = new
        // FormatableBitSet(this.refColUpdtd2DependentCols.size());
        FormatableBitSet colsToWritePerRow = new FormatableBitSet(
            this.refImpactedCols.length);
        for (int index = 0; index < this.keyColumnDVDs.size(); index++) {
          // refColsSameAfterUpdtPerRow.clear();
          colsToWritePerRow.clear();
          DataValueDescriptor[] dvdArray = this.keyColumnDVDs.get(index);
          identifyUpdatedColsUnchangedAndColsToWrite(dvdArray,
              colsToWritePerRow, index);
          // // at this point we know the ref updated cols which
          // remain unchanged
          // // all the cols which need to send their value
          // //write the bits fo ref updated col which remain
          // unchanged
          // for(byte bits: refColsSameAfterUpdtPerRow.getByteArray())
          // {
          // DataSerializer.writePrimitiveByte(bits, out);
          // }
          // now write the columns themselves
          //int j = 0;
          for (int i = 0; i < this.refImpactedCols.length; i++) {
            int colNum = this.refImpactedCols[i];
            boolean shouldWriteValue = colsToWritePerRow.get(i);
            int posInExecRow = getIndexInLocalDVDRow(colNum);
            DataValueDescriptor old = dvdArray[posInExecRow];
            if (shouldWriteValue) {
              old.toData(out);
            }
            else {
              old.writeNullDVD(out);
            }
          }
        }
        if (this.refColSameAfterModBitsMapping != null) {
          out.writeBoolean(true);
          out.writeInt(this.refColSameAfterModBitsMapping.size());
          this.refColSameAfterModBitsMapping.forEachWhile((rowNum, refColSameAfterUpdt) -> {
            try {
              out.writeInt(rowNum);
              for (byte b : refColSameAfterUpdt) {
                out.writeByte(b);
              }
              return true;
            } catch (IOException ioe) {
              throw new GemFireIOException(ioe.getMessage(), ioe);
            }
          });
        }
        else {
          out.writeBoolean(false);
        }

      }
      else {
        for (int i = 0; i < this.keyColumnDVDs.size(); i++) {
          for (DataValueDescriptor dvd : this.keyColumnDVDs.get(i)) {
            dvd.toData(out);
          }
        }
      }
    }
    else {
      out.writeBoolean(false);
      assert !this.forUpdate;
      // out.writeBoolean(this.forUpdate);
      // if (this.forUpdate) {
      // for (int refCol : this.refImpactedCols) {
      // InternalDataSerializer.writeUnsignedVL(refCol, out);
      // }
      // }
      final RowFormatter refKeyFormatter;
      // if (forUpdate) {
      // refKeyFormatter = tableInfo.getReferencedKeyRowFormatter()
      // .getSubSetReferenceKeyFormatter(this.refImpactedCols);
      // } else {
      // refKeyFormatter = tableInfo.getReferencedKeyRowFormatter();
      // }
      refKeyFormatter = tableInfo.getReferencedKeyRowFormatter();

      int[] fixedPositions = tableInfo.getReferencedKeyFixedColumns();
      int[] varPositions = tableInfo.getReferencedKeyVarColumns();
      // final int[] fixedPositions, varPositions;
      //
      // if (this.forUpdate) {
      // fixedPositions = filterModifiedRefCols(fixedPositionsAll);
      // varPositions = filterModifiedRefCols(varPositionsAll);
      //
      // } else {
      // fixedPositions = fixedPositionsAll;
      // varPositions = varPositionsAll;
      // }
      final int rows = this.keyColumnLocations.size();
      InternalDataSerializer.writeArrayLength(rows, out);
      //byte[] rowBytes;
      RowLocation rowLoc;
      @Retained @Released Object rowValue;
      RowFormatter rf;
      // try {
      for (int index = 0; index < rows; index++) {
        rowLoc = this.keyColumnLocations.get(index);
        rowValue = RegionEntryUtils
            .getByteSource(rowLoc, this.container, false, false);
        OffHeapByteSource rowBS = null;
        try {
          if (rowValue != null
              && (tableInfo = rowLoc.getTableInfo(this.container)) != null) {
            final Class<?> cls = rowValue.getClass();
            if (cls == byte[].class) {
              final byte[] rowBytes = (byte[])rowValue;
              rf = tableInfo.getRowFormatter(rowBytes);
              rf.serializeColumns(rowBytes, out, fixedPositions, varPositions,
                  refKeyFormatter.getNumOffsetBytes(),
                  refKeyFormatter.getOffsetDefaultToken(), refKeyFormatter);
            }
            else if (cls == byte[][].class) {
              final byte[] rowBytes = ((byte[][])rowValue)[0];
              rf = tableInfo.getRowFormatter(rowBytes);
              rf.serializeColumns(rowBytes, out, fixedPositions, varPositions,
                  refKeyFormatter.getNumOffsetBytes(),
                  refKeyFormatter.getOffsetDefaultToken(), refKeyFormatter);
            }
            else {
              rowBS = (OffHeapByteSource)rowValue;
              rf = tableInfo.getRowFormatter(rowBS);
              rf.serializeColumns(rowBS, out, fixedPositions, varPositions,
                  refKeyFormatter.getNumOffsetBytes(),
                  refKeyFormatter.getOffsetDefaultToken(), refKeyFormatter);
            }
          }
          else {
            DataSerializer.writeByteArray(null, out);
          }
        } finally {
          if (rowBS != null) {
            rowBS.release();
          }
        }
      }
      /*
       * } catch (StandardException se) { throw new IOException(
       * "ConstraintCheckArgs#toData: unexpected exception", se); }
       */
    }
    GemFireXDUtils.writeCompressedHighLow(out, this.connectionId);
    if (begintime != 0) {
      this.ser_deser_time = XPLAINUtil.recordTiming(begintime);
    }
  }

  private void identifyUpdatedColsUnchangedAndColsToWrite(
      DataValueDescriptor[] dvdArray, FormatableBitSet colsToWritePerRow,
      int rowNum) {
    int pos = this.refColsUpdtdBits.anySetBit();
    int j = 0;
    byte[] refColSameAfterUpdt = null;
    while (pos != -1) {
      // get the column number of the updated col
      int colNum = this.refImpactedCols[pos];
      // get the old & new dvd for that col num
      int posInExecRow = this.refCol2DVDPosMapping != null
          ? this.refCol2DVDPosMapping[colNum - 1] : colNum - 1;

      DataValueDescriptor old = dvdArray[posInExecRow];
      DataValueDescriptor modified = dvdArray[(dvdArray.length - 1) / 2
          + posInExecRow];
      if (old.equals(modified)) {
        // set bit on to indicate no change
        if (this.refColSameAfterModBitsMapping == null) {
          this.refColSameAfterModBitsMapping =
              IntObjectHashMap.withExpectedSize(8);
        }
        if (refColSameAfterUpdt == null) {
          int numCols = this.refColUpdtd2DependentCols.size();
          refColSameAfterUpdt = new byte[FormatableBitSet
              .numBytesFromBits(numCols)];
          this.refColSameAfterModBitsMapping.put(rowNum, refColSameAfterUpdt);
        }
        // based on j identify the right byte from array & its pos
        // TODO:Asif clean up.
        int byteIndex = FormatableBitSet.udiv8(j);
        byte bitIndex = FormatableBitSet.umod8(j);
        refColSameAfterUpdt[byteIndex] |= (0x80 >> bitIndex);

      }
      else {
        // find all the dependent cols & set their bit on so that
        // data can be written for those cols
        TIntHashSet dependentCols = this.refColUpdtd2DependentCols.get(colNum);
        TIntIterator ti = dependentCols.iterator();
        while (ti.hasNext()) {

          int colToWrite = ti.next();
          colsToWritePerRow.set(this.refCol2IndexMap.get(colToWrite));
        }
        // set self
        colsToWritePerRow.set(this.refCol2IndexMap.get(colNum));
      }
      j++;
      pos = this.refColsUpdtdBits.anySetBit(pos);
    }
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";table=").append(this.schemaName).append('.')
        .append(this.tableName);
    sb.append(";connectionId=").append(this.connectionId);
    sb.append(";forUpdate=").append(this.forUpdate);
    if (forUpdate) {
      sb.append(";Reference updates cols positions=");
      for (int col : this.refImpactedCols) {
        sb.append("" + col + ",");
      }
      sb.deleteCharAt(sb.length() - 1);

      sb.append("RefColsUpdtd flag = " + this.refColsUpdtdBits);
    }
    sb.append(";keys=");

    if (this.keyColumnDVDs != null) {
      final int numKeys = this.keyColumnDVDs.size();

      if (numKeys <= 10) {
        for (int index = 0; index < numKeys; index++) {
          if (index > 0) {
            sb.append(':');
          }

          if (this.forUpdate) {
            DataValueDescriptor[] dvdArray = this.keyColumnDVDs.get(index);

            String str = refCol2DVDPosMapping != null ? generateForUpdateString(
                dvdArray) : RowUtil.toString(dvdArray);
            sb.append(str);
          }
          else {
            sb.append(RowUtil.toString(this.keyColumnDVDs.get(index)));
          }
        }
      }
      else {
        sb.append(numKeys).append(" keys");
      }
    } else if (this.keyRows != null) {
      final int numKeys = this.keyRows.size();
      if (numKeys <= 10) {
        for (int index = 0; index < numKeys; index++) {
          if (index > 0) {
            sb.append("::");
          }
          byte[] keyRow = this.keyRows.get(index);
          if (keyRow != null) {
            sb.append(ClientSharedUtils.toHexString(keyRow, 0, keyRow.length));
          } else {
            sb.append("null");
          }
        }
      }
      else {
        sb.append(numKeys).append(" keys");
      }
    } else {
      final int numKeys = this.keyColumnLocations.size();
      if (numKeys <= 10) {
        final int[] keyPositions = this.forUpdate ? this.refImpactedCols
            : this.container.getExtraTableInfo().getReferencedKeyColumns();
        final DataValueDescriptor[] keys = new DataValueDescriptor[keyPositions.length];
        //byte[] rowBytes;
        RowLocation rowLoc;
        @Retained @Released Object rowValue;
        ExtraTableInfo tableInfo;
        RowFormatter rf;
        for (int index = 0; index < this.keyColumnLocations.size(); index++) {
          if (index > 0) {
            sb.append(':');
          }
          rowLoc = this.keyColumnLocations.get(index);
          try {
            SimpleMemoryAllocatorImpl.skipRefCountTracking();
            rowValue = RegionEntryUtils.getByteSource(rowLoc, this.container,
                false, false);
            SimpleMemoryAllocatorImpl.unskipRefCountTracking();
            if (rowValue != null) {
              final Class<?> cls = rowValue.getClass();
              if (cls == byte[].class) {
                final byte[] rowBytes = (byte[])rowValue;
                tableInfo = rowLoc.getTableInfo(this.container);
                rf = tableInfo.getRowFormatter(rowBytes);
                rf.getColumns(rowBytes, keys, keyPositions);
              }
              else if (cls == byte[][].class) {
                final byte[][] rowBytes = (byte[][])rowValue;
                tableInfo = rowLoc.getTableInfo(this.container);
                rf = tableInfo.getRowFormatter(rowBytes[0]);
                rf.getColumns(rowBytes, keys, keyPositions);
              }
              else {
                OffHeapByteSource bs = null;
                try {
                  if (cls == OffHeapRow.class) {
                    final OffHeapRow rowBS = (OffHeapRow)rowValue;
                    bs = rowBS;
                    tableInfo = rowLoc.getTableInfo(this.container);
                    rf = tableInfo.getRowFormatter(rowBS);
                    rf.getColumns(rowBS, keys, keyPositions);
                  }
                  else {
                    final OffHeapRowWithLobs rowBS = (OffHeapRowWithLobs)rowValue;
                    bs = rowBS;
                    tableInfo = rowLoc.getTableInfo(this.container);
                    rf = tableInfo.getRowFormatter(rowBS);
                    rf.getColumns(rowBS, keys, keyPositions);
                  }
                  sb.append(RowUtil.toString(keys));
                } finally {
                  if (bs != null) {
                    SimpleMemoryAllocatorImpl.skipRefCountTracking();
                    bs.release();
                    SimpleMemoryAllocatorImpl.unskipRefCountTracking();
                  }
                }
              }
            }
            else {
              sb.append("(null)");
            }
          } catch (StandardException se) {
            sb.append("(null)");
          }
        }
      }
      else {
        sb.append(numKeys).append(" keys");
      }
    }
  }

  private String generateForUpdateString(DataValueDescriptor[] dvdArray) {
    DataValueDescriptor[] out = new DataValueDescriptor[refImpactedCols.length];
    for (int i = 0; i < this.refImpactedCols.length; i++) {
      int colNum = this.refImpactedCols[i];
      int posInExecRow = this.refCol2DVDPosMapping[colNum - 1];
      out[i] = dvdArray[posInExecRow];
    }
    return RowUtil.toString(out);
  }

  public static void openReferencedContainersForRead(
      final GemFireTransaction tran, final GemFireContainer[] refContainers)
      throws StandardException {
    // open the containers to acquire the read locks
    for (int index = 0; index < refContainers.length; index++) {
      refContainers[index].open(tran, ContainerHandle.MODE_READONLY);
    }
  }

  public static void closeReferencedContainersAfterRead(
      final GemFireTransaction tran,
      final GemFireContainer[] refContainers) throws StandardException {
    // close the containers to release the read locks
    for (int index = 0; index < refContainers.length; index++) {
      refContainers[index].closeForEndTransaction(tran, false);
    }
  }

  private static class ReferencedKeyResultCollector
      extends ArrayList<List<Object>>
      implements ResultCollector<Object, ArrayList<List<Object>>> {

    private static final long serialVersionUID = -1479391066460153172L;

    @SuppressWarnings("unchecked")
    public void addResult(DistributedMember memberId,
        Object resultOfSingleExecution) {
      if (resultOfSingleExecution instanceof List<?>) {
        final List<Object> failure = (List<Object>)resultOfSingleExecution;
        failure.add(memberId.toString());
        add(failure);
      }
    }

    public ArrayList<List<Object>> getResult() throws FunctionException {
      return this;
    }

    public ArrayList<List<Object>> getResult(long timeout, TimeUnit unit)
        throws FunctionException, InterruptedException {
      throw new AssertionError(
          "getResult with timeout not expected to be invoked for GemFireXD");
    }

    public void clearResults() {
      clear();
    }

    public void endResults() {
    }
  }

  /**
   * Simple reply processor for a store node executing delete waiting for
   * reference key check result from origin that processes a
   * {@link ReferencedKeyReplyMessage}.
   * 
   * @author swale
   * @since 7.0
   */
  public static final class ReferencedKeyReplyProcessor extends
      ReplyProcessor21 {

    private volatile boolean success;

    public ReferencedKeyReplyProcessor(final DM dm,
        final InternalDistributedMember origin) {
      super(dm, origin);
    }

    @Override
    public void process(final DistributionMessage msg) {
      if (msg instanceof ReferencedKeyReplyMessage) {
        this.success = ((ReferencedKeyReplyMessage)msg).getResponseCode()
            .isGrant();
      }
      super.process(msg);
    }

    /** processor can wait for reply from sender. */
    @Override
    protected boolean allowReplyFromSender() {
      return true;
    }

    public boolean waitForResult() throws StandardException {
      Throwable thr = null;
      try {
        super.waitForReplies();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        // check for VM going down
        Misc.checkIfCacheClosing(ie);
        thr = ie;
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
        thr = t;
      } finally {
        if (thr != null) {
          final DM dm = getDistributionManager();
          dm.getCancelCriterion().checkCancelInProgress(thr);
          dm.getLoggerI18n().info(LocalizedStrings.DEBUG,
              "ReferencedKeyReplyProcessor: unexpected exception while waiting "
                  + "for reply from " + membersToString(), thr);
          return false;
        }
      }
      return this.success;
    }
  }

  /**
   * Simple message from origin to store node waiting for result of reference
   * key check in delete on other nodes to inform whether the reference key
   * check was successful.
   * 
   * @author swale
   * @since 7.0
   */
  public static final class ReferencedKeyReplyMessage extends GfxdReplyMessage {

    private boolean success;

    /** for deserialization */
    public ReferencedKeyReplyMessage() {
    }

    public ReferencedKeyReplyMessage(final boolean success) {
      this.success = success;
    }

    public static void send(final InternalDistributedMember recipient,
        final int processorId, final DM dm, final boolean success) {
      Assert.assertTrue(recipient != null, "Sending a ReplyMessage to ALL");
      final ReferencedKeyReplyMessage m = new ReferencedKeyReplyMessage(success);
      m.processorId = processorId;
      m.setRecipient(recipient);
      // process for self in the same thread
      final InternalDistributedMember self = Misc.getMyId();
      if (self.equals(recipient)) {
        m.setSender(self);
        final ReplyProcessor21 processor = ReplyProcessor21
            .getProcessor(processorId);
        if (processor != null) {
          processor.process(m);
        }
        else {
          SanityManager.THROWASSERT("unexpected null reply processor "
              + "for processorId=" + processorId);
        }
      }
      else {
        dm.putOutgoing(m);
      }
    }

    /**
     * @see GfxdReplyMessage#getGfxdID()
     */
    @Override
    public byte getGfxdID() {
      return REFERENCED_KEY_REPLY_MSG;
    }

    /**
     * @see GfxdReplyMessage#getResponseCode()
     */
    @Override
    public GfxdResponseCode getResponseCode() {
      return this.success ? GfxdResponseCode.GRANT(1)
          : GfxdResponseCode.EXCEPTION;
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      super.toData(out);
      out.writeBoolean(this.success);
    }

    @Override
    public void fromData(DataInput in) throws IOException,
            ClassNotFoundException {
      super.fromData(in);
      this.success = in.readBoolean();
    }
  }
}
