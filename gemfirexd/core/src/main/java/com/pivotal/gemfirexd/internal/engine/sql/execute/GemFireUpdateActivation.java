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

package com.pivotal.gemfirexd.internal.engine.sql.execute;


import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.cache.EntryDestroyedException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.*;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.reflect.GemFireActivationClass;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer.SerializableDelta;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;


/**
 * Activation object used to execute update as region.put
 * @author Asif
 *
 */
public class GemFireUpdateActivation extends AbstractGemFireActivation
{
  //TODO :Should we cache it?
  private final GemFireContainer container;

  private MultipleKeyValueHolder mkvh;

  private long batchSize;
  
  public GemFireUpdateActivation(ExecPreparedStatement st,
      LanguageConnectionContext _lcc, DMLQueryInfo qi, GemFireActivationClass gc)
      throws StandardException {
    super(st, _lcc, qi);
    this.container = (GemFireContainer)this.qInfo.getRegion()
        .getUserAttribute();
  }

  @Override
  protected void executeWithResultSet(AbstractGemFireResultSet rs)
      throws StandardException {
    final LanguageConnectionContext lcc = getLanguageConnectionContext();
    final GemFireTransaction tran = (GemFireTransaction)lcc
        .getTransactionExecute();
    final TXStateInterface tx = this.container.getActiveTXState(tran);
    final boolean isTransactional = tx != null;
    // Asif: Obtain the Primary Key. If it is dynamic then we need to compute
    // now If it is static use it as it is
    Object pk = this.qInfo.getPrimaryKey();
    Object[][] otherKeys = (Object[][])this.qInfo.getOtherKeys();

    String whereStatement = ((UpdateQueryInfo)this.qInfo).statementSQLText;
    int whereIndex = whereStatement.indexOf("where");
    if (whereIndex == 0) {
      whereIndex = whereStatement.indexOf("WHERE");
    }
    String predicate = whereStatement.substring(whereIndex + 6,
        whereStatement.length());

    Object[] gfKeys = null;
    // TODO:Asif: We need to find out a cleaner
    // way to detect bulk get / bulk put conditions
    // TODO [sumedh] Making a clone for TX case since that key can go
    // into TXRegionState map. Can avoid clone if this node is not a
    // datastore.
    if (pk instanceof Object[]) {
      if (qInfo.isWhereClauseDynamic()) {
        Object[] pks = (Object[])pk;
        int len = pks.length;
        gfKeys = new Object[len];
        for (int i = 0; i < len; ++i) {
          if (pks[i] instanceof DynamicKey) {
            DynamicKey dk = (DynamicKey)pks[i];
            gfKeys[i] = dk.getEvaluatedPrimaryKey(this, this.container,
                isTransactional);
          }
        }
      }
      else {
        gfKeys = (Object[])pk;
      }
    }
    else {
      gfKeys = new Object[1];
      if (qInfo.isWhereClauseDynamic()) {
        DynamicKey dk = (DynamicKey)pk;
        pk = dk.getEvaluatedPrimaryKey(this, this.container, isTransactional);
      }
      gfKeys[0] = pk;
    }

    if (observer != null) {
      observer.beforeGemFireResultSetExecuteOnActivation(this);
    }



    DataValueDescriptor[] otherKeyValues = null;
    if (otherKeys != null && predicate != null && !isTransactional) {

      int primaryKeyLength =
          this.container.getExtraTableInfo().getPrimaryKeyColumnNames().length;
      if(primaryKeyLength == otherKeys.length){// Pure PK based update
        predicate = null;
      }

      QueryInfo[][] updatedCols = ((UpdateQueryInfo)this.qInfo).getUpdatedCols();
      int startIndexOfWhereParams = 0;
      for(int i = 0; i< updatedCols.length ; i++){
        QueryInfo[] qif = updatedCols[i];
        ColumnQueryInfo cqi = (ColumnQueryInfo)qif[0];
        if (cqi != null) {
          ValueQueryInfo vqi = (ValueQueryInfo)qif[1];
          if (vqi instanceof ParameterQueryInfo) {
            startIndexOfWhereParams++;
          }
        }
      }

      int otherKeyValLength = 0;

      for (int i = 0; i < otherKeys.length; i++) {
        QueryInfo[] qif = (QueryInfo[])otherKeys[i];
        ColumnQueryInfo cqi = (ColumnQueryInfo)qif[0];
        if (cqi != null) {
          for (int j = 1; j < qif.length; j++) {
            ValueQueryInfo vqi = (ValueQueryInfo)qif[j];
            if (vqi instanceof ParameterQueryInfo) {
              otherKeyValLength++;
            }
          }
        }
      }

      otherKeyValues = new DataValueDescriptor[otherKeyValLength];
      for (int i = 0; i < otherKeys.length; i++) {
        QueryInfo[] qif = (QueryInfo[])otherKeys[i];
        ColumnQueryInfo cqi = (ColumnQueryInfo)qif[0];
        int index = 0;
        if (cqi != null) {
          for (int j = 1; j < qif.length; j++) {
            ValueQueryInfo vqi = (ValueQueryInfo)qif[j];
            if (vqi instanceof ParameterQueryInfo) {
              index = ((ParameterQueryInfo)vqi).getParamIndex() - startIndexOfWhereParams;
              otherKeyValues[index] = vqi
                  .evaluateToGetDataValueDescriptor(this);
            }
          }
        }
      }
    }
    //TODO:Asif : what happens if the update is only partially successful ?
    int numRowsModified = 0;
    Object[] tmpResult = new Object[2];
    for (int i = 0; i < gfKeys.length; ++i) {
      ((UpdateQueryInfo)this.qInfo).getChangedRowAndFormatableBitSet(this,
          gfKeys[i], tmpResult);
      final DataValueDescriptor[] dvds = (DataValueDescriptor[])tmpResult[0];
      // clone the DVDs if we could be doing a local put;
      // this is because the DVDs could be coming from a ParameterValueSet that
      // can be reused and TX will not apply the delta immediately;
      // don't need to clone for non-TX since delta will be applied immediately
      if ((isTransactional || this.isPreparedBatch)) {
        for (int index = 0; index < dvds.length; index++) {
          if (dvds[index] != null) {
            dvds[index] = dvds[index].getClone();
          }
        }
      }
      try {
        boolean flush = false;
        if (this.isPreparedBatch) {
          this.batchSize += ValueRow.estimateDVDArraySize(dvds);
          if (this.mkvh == null) {
            this.mkvh = new MultipleKeyValueHolder();
          }
          if (this.batchSize > GemFireXDUtils.DML_MAX_CHUNK_SIZE) {
            flush = true;
            this.batchSize = 0;
          }
        }

        this.container.replacePartialRow(gfKeys[i],
            (FormatableBitSet)tmpResult[1], dvds, null, tran, tx, lcc,
            this.mkvh, flush, predicate, otherKeyValues);
        if (flush) {
          this.mkvh = null;
        }
        LocalRegion rgn;
        if (isTransactional
            && (rgn = this.container.getRegion()).isSerialWanEnabled()) {
          this.distributeBulkOpToDBSynchronizer(rgn, this.qInfo.isDynamic(),
              tran, this.lcc.isSkipListeners());
        }
        ++numRowsModified;
      } catch (EntryNotFoundException e) {
        GemFireXDUtils.checkForInsufficientDataStore(this.container.getRegion());
      } catch (EntryDestroyedException e) {
      } catch (GemFireException gfeex) {
        throw Misc.processGemFireException(gfeex, gfeex, "execution of "
            + this.preStmt.getSource(), true);
      }
    }
    if (observer != null) {
      observer.afterGemFireResultSetExecuteOnActivation(this);
    }
    rs.setNumRowsModified(numRowsModified);
  }

  public final void flushBatch() throws StandardException {
    int numRowsModified = 0;
    try {
      if (this.mkvh != null && this.mkvh.toBeInsertedDeltas != null) {
        VersionedObjectList result = this.container.doPutAllOfAllDeltas(
            this.mkvh, lcc);
        if (result != null) {
          numRowsModified = result.size();
        }
      }
    } catch (EntryNotFoundException e) {
      GemFireXDUtils.checkForInsufficientDataStore(this.container.getRegion());
    } catch (EntryDestroyedException e) {
      // ok to ignore ...
    } finally {
      // null the holder after success or failure
      this.mkvh = null;
    }
    if (this.resultSet instanceof AbstractGemFireResultSet) {
      ((AbstractGemFireResultSet)this.resultSet)
          .setNumRowsModified(numRowsModified);
    }
  }

  @Override
  protected AbstractGemFireResultSet createResultSet(int resultsetNum) throws StandardException {
    return new GemFireUpdateResultSet(this);
  }

  @Override
  public void accept(ActivationStatisticsVisitor visitor) {
    visitor.visit(this);
  }

  /**
   * This class holds the multiple key+delta+callback args in a batch update
   * that is being converted to putAll.
   */
  public static final class MultipleKeyValueHolder {

    private LinkedHashMap<Object, Object> toBeInsertedDeltas;
    private ArrayList<Object> callbackArgs;

    public void addKeyValueAndCallbackArg(Object key, SerializableDelta delta,
        Object sca) {
      if (this.toBeInsertedDeltas == null) {
        this.toBeInsertedDeltas = new LinkedHashMap<Object, Object>();
        this.callbackArgs = new ArrayList<Object>();
      }
      // #48245: if toBeInsertedDeltas already contains the key, then the value
      // will be replaced by the new value as in the case of updates on the 
      // same row in a batch, only the latest value is needed
      this.toBeInsertedDeltas.put(key, delta);
      this.callbackArgs.add(sca);
    }

    public LinkedHashMap<Object, Object> getToBeInsertedDeltas() {
      return this.toBeInsertedDeltas;
    }

    public ArrayList<Object> getCallbackArgs() {
      return this.callbackArgs;
    }
  }
}
