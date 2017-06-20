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
package com.pivotal.gemfirexd.internal.engine.ddl.wan;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.EventErrorHandler;
import com.gemstone.gemfire.internal.cache.GatewayEventImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.WrappedCallbackArgument;
import com.gemstone.gemfire.internal.cache.tier.sockets.BaseCommand;
import com.gemstone.gemfire.internal.cache.tier.sockets.Message;
import com.gemstone.gemfire.internal.cache.tier.sockets.Part;
import com.gemstone.gemfire.internal.cache.tier.sockets.ServerConnection;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.callbacks.AsyncEventHelper;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.GfxdCBArgForSynchPrms;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireResultSet;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.execute.TriggerEvent;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * 
 * @author asif, ymahajan
 * 
 */
public final class GfxdBulkDMLCommand extends BaseCommand {

  private static final AsyncEventHelper helper = AsyncEventHelper.newInstance();

  public static void dummy() {
  }

  public GfxdBulkDMLCommand() {
  }

  @Override
  public void processBulkDML(Part statementPart, Part callbackPart) {
    GfxdCBArgForSynchPrms stmtEvent = null;
    LanguageConnectionContext lcc = null;
    Statement stmt = null;
    try {
      stmtEvent = (GfxdCBArgForSynchPrms)callbackPart.getObject();
      if (SanityManager.isFineEnabled) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
            "GfxdBulkDMLOp: CallbackPart = " + callbackPart + ";"
                + " CallbackArg = " + stmtEvent);
      }

      String dmlString = stmtEvent.getDMLString();
      String schema = stmtEvent.getSchemaName();
      if (GemFireXDUtils.TraceDBSynchronizer) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
            "GfxdBulkDMLCommand::execute: Statement event obtained = "
                + stmtEvent);
      }
      final EmbedConnection conn = GemFireXDUtils.getTSSConnection(false,
          false, false);
      lcc = conn.getLanguageConnectionContext();
      lcc.setGatewaySenderEventCallbackArg(stmtEvent);
      lcc.setPossibleDuplicate(stmtEvent.isPossibleDuplicate());
      // Set fresh connection's isolation level as none
      conn.setTransactionIsolation(java.sql.Connection.TRANSACTION_NONE);
      conn.setAutoCommit(false);
      conn.setDefaultSchema(schema);
      int numRows;
      if (stmtEvent.hasParameters()) {
        EmbedPreparedStatement ps = (EmbedPreparedStatement)conn
            .prepareStatement(dmlString);
        stmt = ps;
        // if this is coming from WAN then ignore the original prepared
        // statement and instead do raw puts (#45652)
        if (stmtEvent.isBulkInsert()) {
          final GemFireContainer container = stmtEvent.getContainer();
          final ArrayList<Object> bulkInsertRows = stmtEvent
              .getRawBulkInsertRows();
          container.insertMultipleRows(bulkInsertRows, null, lcc, false,
              stmtEvent.isPutDML());
          // also publish to the queue further if required (#45674)
          if (container.getRegion().isSerialWanEnabled()) {
            ps.getActivation().distributeBulkOpToDBSynchronizer(
                container.getRegion(), true,
                (GemFireTransaction)lcc.getTransactionExecute(),
                lcc.isSkipListeners(), bulkInsertRows);
          }
          // if above is successful then all rows have been inserted
          numRows = bulkInsertRows.size();
        }
        else {
          if (helper.setParamsInBulkPreparedStatement(stmtEvent,
              stmtEvent.getType(), ps, null, null)) {
            numRows = 0;
            for (int n : ps.executeBatch()) {
              numRows += n;
            }
          }
          else {
            numRows = ps.executeUpdate();
          }
        }
      }
      else {
        stmt = conn.createStatement();
        numRows = stmt.executeUpdate(dmlString);
      }
      if (GemFireXDUtils.TraceDBSynchronizer) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
            "GfxdBulkDMLCommand::execute: Successfully executed. "
                + "Num rows affected = " + numRows);
      }
    } catch (Throwable t) {
      Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(t);
      LogWriterI18n logger = Misc.getI18NLogWriter();
      if (logger != null && logger.warningEnabled()) {
          logger.warning(LocalizedStrings.Gfxd_GATEWAY_SYNCHRONIZER__1,
              stmtEvent, t);
      }
    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (Throwable t) {
          Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(t);
          LogWriterI18n logger = Misc.getI18NLogWriter();
          if (logger != null && logger.warningEnabled()) {
            logger.warning(LocalizedStrings.Gfxd_GATEWAY_SYNCHRONIZER__1,
                stmtEvent, t);
          }
        }
      }
      // explicitly release all locks here since ConnectionWrapper may
      // have set the skipLocks flag to true via StatementQueryExecutor
      final TransactionController tc;
      if (lcc != null) {
        lcc.setGatewaySenderEventCallbackArg(null);
        lcc.setPossibleDuplicate(false);
        if ((tc = lcc.getTransactionExecute()) != null) {
          lcc.setSkipLocks(false);
          lcc.setIsConnectionForRemote(false);
          try {
            if (!tc.isTransactional()) {
              // this TC is for this thread-local connection so force release
              // all locks
              tc.releaseAllLocks(true, true);
            }
          } catch (Throwable t) {
            Misc.getGemFireCache().getCancelCriterion()
                .checkCancelInProgress(t);
            LogWriterI18n logger = Misc.getI18NLogWriter();
            if (logger != null && logger.warningEnabled()) {
              logger.warning(LocalizedStrings.Gfxd_GATEWAY_SYNCHRONIZER__1,
                  stmtEvent, t);
            }
          }
        }
      }
    }
  }

  @Override
  public void cmdExecute(Message msg, ServerConnection servConn, long start) {
    // No Op
  }

  @Override
  public Object regenerateKeyConditionally(Object oldKey, LocalRegion region) {
    GemFireContainer gfc = (GemFireContainer)region.getUserAttribute();
    if (gfc.isPrimaryKeyBased()) {
      return oldKey;
    }
    else {
      return gfc.newUUIDForRegionKey();
    }
  }

  @Override
  public Object regenerateCallbackConditionally(final Object key,
      final Object oldCallbackArg, final LocalRegion region) {
    GemFireContainer gfc = (GemFireContainer)region.getUserAttribute();
    GfxdPartitionResolver resolver = GemFireXDUtils.getResolver(region);
    boolean isPartitioningKeyThePrimaryKey = false;
    if (resolver != null) {
      isPartitioningKeyThePrimaryKey = resolver
          .isPartitioningKeyThePrimaryKey();
    }
    if (gfc.isPrimaryKeyBased() || !isPartitioningKeyThePrimaryKey) {
      return oldCallbackArg;
    }
    Object callbackArg = oldCallbackArg;
    WrappedCallbackArgument wca = null;
    while (callbackArg instanceof WrappedCallbackArgument) {
      wca = (WrappedCallbackArgument)callbackArg;
      callbackArg = wca.getOriginalCallbackArg();
    }
    if (callbackArg instanceof GfxdCallbackArgument) {
      GfxdCallbackArgument sca = (GfxdCallbackArgument)callbackArg;
      if (!sca.isFixedInstance()) {
        sca.setRoutingObject(GfxdPartitionByExpressionResolver
            .getGeneratedKeyRoutingObject(key));
      }
      return oldCallbackArg;
    }
    else if (callbackArg instanceof Integer) {
      if (wca != null) {
        wca.setOriginalCallbackArgument(GfxdPartitionByExpressionResolver
            .getGeneratedKeyRoutingObject(key));
        return oldCallbackArg;
      }
      else {
        return GfxdPartitionByExpressionResolver
            .getGeneratedKeyRoutingObject(key);
      }
    }
    return oldCallbackArg;
  }

  @Override
  public void beforeOperationCallback(LocalRegion rgn, int actionType) {
    EmbedConnection conn = null;
    boolean contextSet = false;
    if (GemFireXDUtils.TraceDBSynchronizer) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
          "GfxdBulkDMLCommand::beforeOperationCallback:entering ..");
    }

    try {
      // Refer Bug 42810.In case of WAN, a PK based insert is converted into
      // region.put since it bypasses GemFireXD layer, the LCC can be null.
      conn = GemFireXDUtils.getTSSConnection(false, false, false);
      conn.getTR().setupContextStack();
      contextSet = true;
      final LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
      GemFireTransaction tran = (GemFireTransaction)lcc.getTransactionExecute();
      GemFireContainer container = (GemFireContainer)rgn.getUserAttribute();
      container.open(tran, ContainerHandle.MODE_READONLY);
      AbstractGemFireResultSet.openOrCloseFKContainers(container, tran, false,
          false);

      final GfxdIndexManager sqim = (GfxdIndexManager)rgn.getIndexUpdater();
      if (sqim != null) {
        switch (actionType) {
          case GatewayEventImpl.CREATE_ACTION:
            sqim.fireStatementTriggers(TriggerEvent.BEFORE_INSERT, lcc, tran,
                null);
            break;
          case GatewayEventImpl.UPDATE_ACTION:
            sqim.fireStatementTriggers(TriggerEvent.BEFORE_UPDATE, lcc, tran,
                null);
            break;
          case GatewayEventImpl.DESTROY_ACTION:
            sqim.fireStatementTriggers(TriggerEvent.BEFORE_DELETE, lcc, tran,
                null);
            break;
        }
      }
    } catch (StandardException se) {
      throw new GemFireXDRuntimeException(se);
    } finally {
      if (contextSet) {
        conn.getTR().restoreContextStack();
      }
    }
  }

  @Override
  public void afterOperationCallback(LocalRegion rgn, int actionType) {
    EmbedConnection conn = null;
    GemFireTransaction tran = null;
    boolean contextSet = false;
    if (GemFireXDUtils.TraceDBSynchronizer) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
          "GfxdBulkDMLCommand::afterOperationCallback:entering ..");
    }
    GemFireContainer container = (GemFireContainer)rgn.getUserAttribute();
    try {
      conn = GemFireXDUtils.getTSSConnection(false, false, false);
      conn.getTR().setupContextStack();
      contextSet = true;
      final LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
      tran = (GemFireTransaction)lcc.getTransactionExecute();

      final GfxdIndexManager sqim = (GfxdIndexManager)rgn.getIndexUpdater();
      if (sqim != null) {
        switch (actionType) {
          case GatewayEventImpl.CREATE_ACTION:
            sqim.fireStatementTriggers(TriggerEvent.AFTER_INSERT, lcc, tran,
                null);
            break;
          case GatewayEventImpl.UPDATE_ACTION:
            sqim.fireStatementTriggers(TriggerEvent.AFTER_UPDATE, lcc, tran,
                null);
            break;
          case GatewayEventImpl.DESTROY_ACTION:
            sqim.fireStatementTriggers(TriggerEvent.AFTER_DELETE, lcc, tran,
                null);
            break;
        }
      }
    } catch (StandardException se) {
      throw new GemFireXDRuntimeException(se);
    } finally {     
      try {
        if (tran != null) {
          if (GemFireXDUtils.TraceDBSynchronizer) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
                "GfxdBulkDMLCommand::afterOperationCallback:finally block. "
                    + "skip locks=" + tran.skipLocks());
          }
          AbstractGemFireResultSet.openOrCloseFKContainers(container, tran,
              true, false);
          container.closeForEndTransaction(tran, false);
        }
        if (contextSet) {
          conn.getTR().restoreContextStack();
        }
      } catch (StandardException se) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
            "GfxdBulkDMLCommand::afterOperationCallback: " +
                "exception in finally block: " + se, se);
      }
    }
  }

  /**
   * Log warnings for constraint violations while rethrow back other unexpected
   * exceptions. This is similar to the treatment for events in DBSychronizer.
   */
  @Override
  public void processRuntimeException(RuntimeException e, String servConnName,
      String regionName, Object key, Object valuePart, Object callbackArg,
      EntryEventImpl eventForErrorLog) {
    // just log constraint violation errors and move on
    final Throwable cause = e.getCause();
    boolean logWarning = false;
    if (cause != null) {
      if (cause instanceof StandardException) {
        if (((StandardException)cause).getSQLState().startsWith(
            SQLState.INTEGRITY_VIOLATION_PREFIX)) {
          logWarning = true;
        }
      }
      else if (cause instanceof SQLException) {
        if (((SQLException)cause).getSQLState().startsWith(
            SQLState.INTEGRITY_VIOLATION_PREFIX)) {
          logWarning = true;
        }
      }
    }
    if (logWarning) {
      // [sjigyasu] Log the constraint violations to the error log file. 
      // Other exceptions are precipitated and logged at the higher level
      LogWriterI18n logger = Misc.getI18NLogWriter();
      
      EventErrorHandler handler = GemFireCacheImpl.getExisting()
          .getEventErrorHandler();
      if (handler != null) {
        handler.onError(eventForErrorLog, e);
      }
      
      if (logger != null && logger.warningEnabled()) {
        logger.warning(LocalizedStrings.Gfxd_GATEWAY_SYNCHRONIZER__1,
            String.format("{ServerConnection=%s, table=%s, key=%s, "
                + "valuePart=%s, callbackArg=%s}", servConnName,
                Misc.getFullTableNameFromRegionPath(regionName),
                String.valueOf(key), String.valueOf(valuePart),
                String.valueOf(callbackArg)), e);
      }
    }
    else {
      if (GemFireXDUtils.TraceDBSynchronizer) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
            String.format("Exception in processing gateway event "
                + "{ServerConnection=%s, table=%s, key=%s, valuePart=%s, "
                + "callbackArg=%s}", servConnName,
                Misc.getFullTableNameFromRegionPath(regionName),
                String.valueOf(key), String.valueOf(valuePart),
                String.valueOf(callbackArg)), e);
      }
      throw e;
    }
  }
}
