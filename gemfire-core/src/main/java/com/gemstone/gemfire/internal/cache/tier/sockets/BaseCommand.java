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
package com.gemstone.gemfire.internal.cache.tier.sockets;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.CopyException;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.SerializationException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheLoaderException;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.InterestResultPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.TransactionDataNodeHasDepartedException;
import com.gemstone.gemfire.cache.TransactionDataRebalancedException;
import com.gemstone.gemfire.cache.operations.QueryOperationContext;
import com.gemstone.gemfire.cache.persistence.PartitionOfflineException;
import com.gemstone.gemfire.cache.query.*;
import com.gemstone.gemfire.cache.query.internal.CqEntry;
import com.gemstone.gemfire.cache.query.internal.CqQueryImpl;
import com.gemstone.gemfire.cache.query.internal.DefaultQuery;
import com.gemstone.gemfire.cache.query.internal.types.CollectionTypeImpl;
import com.gemstone.gemfire.cache.query.internal.types.StructTypeImpl;
import com.gemstone.gemfire.cache.query.types.CollectionType;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.CachedDeserializable;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.FindVersionTagOperation;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.tier.CachedRegionHelper;
import com.gemstone.gemfire.internal.cache.tier.Command;
import com.gemstone.gemfire.internal.cache.tier.InterestType;
import com.gemstone.gemfire.internal.cache.tier.MessageType;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.security.AuthorizeRequestPP;
import com.gemstone.gemfire.internal.sequencelog.EntryLogger;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.security.GemFireSecurityException;

/**
 * @author ashahid
 *
 */
public abstract class BaseCommand implements Command {
  protected LogWriterI18n logger; // findbugs is WRONG this can't be package protected.

  /**
   * Whether zipped values are being passed to/from the client. Can be modified
   * using the system property Message.ZIP_VALUES ? This does not appear to
   * happen anywhere
   */
  protected static final boolean zipValues = false;

  protected static final boolean APPLY_RETRIES = Boolean
      .getBoolean("gemfire.gateway.ApplyRetries");

  public static final byte[] OK_BYTES = new byte[]{0};  

  protected static final int maximumChunkSize = Integer.getInteger(
      "BridgeServer.MAXIMUM_CHUNK_SIZE", 100).intValue();

  /** Maximum number of entries in each chunked response chunk */

  /** Whether to suppress logging of IOExceptions */
  private static boolean suppressIOExceptionLogging = Boolean
      .getBoolean("gemfire.bridge.suppressIOExceptionLogging");

  /**
   * Maximum number of concurrent incoming client message bytes that a bridge
   * server will allow. Once a server is working on this number additional
   * incoming client messages will wait until one of them completes or fails.
   * The bytes are computed based in the size sent in the incoming msg header.
   */
  private static final int MAX_INCOMING_DATA = Integer.getInteger(
      "BridgeServer.MAX_INCOMING_DATA", -1).intValue();

  /**
   * Maximum number of concurrent incoming client messages that a bridge server
   * will allow. Once a server is working on this number additional incoming
   * client messages will wait until one of them completes or fails.
   */
  private static final int MAX_INCOMING_MSGS = Integer.getInteger(
      "BridgeServer.MAX_INCOMING_MSGS", -1).intValue();

  private static final Semaphore incomingDataLimiter;

  private static final Semaphore incomingMsgLimiter;
  static {
    Semaphore tmp;
    if (MAX_INCOMING_DATA > 0) {
      // backport requires that this is fair since we inc by values > 1
      tmp = new Semaphore(MAX_INCOMING_DATA, true);
    }
    else {
      tmp = null;
    }
    incomingDataLimiter = tmp;
    if (MAX_INCOMING_MSGS > 0) {
      tmp = new Semaphore(MAX_INCOMING_MSGS, false); // unfair for best
      // performance
    }
    else {
      tmp = null;
    }
    incomingMsgLimiter = tmp;

  }

  final public void execute(Message msg, ServerConnection servConn) {
    logger = servConn.getLogger();
    // Read the request and update the statistics
    long start = DistributionStats.getStatTime();
    //servConn.resetTransientData();
    if(EntryLogger.isEnabled() && servConn  != null) {
      EntryLogger.setSource(servConn.getMembershipID(), "c2s");
    }
    boolean shouldMasquerade = shouldMasqueradeForTx(msg, servConn);
    try {
      if (shouldMasquerade) {
        GemFireCacheImpl  cache = (GemFireCacheImpl)servConn.getCache();
        InternalDistributedMember member = (InternalDistributedMember)servConn.getProxyID().getDistributedMember();
        TXManagerImpl txMgr = cache.getTxManager();
        TXManagerImpl.TXContext txContext = null;
        try {
          txContext = txMgr.masqueradeAs(msg, member, true);
          cmdExecute(msg, servConn, start);
        } finally {
          txMgr.unmasquerade(txContext, true);
        }
      } else {
        cmdExecute(msg, servConn, start);
      }
      
    }   
    catch (EOFException eof) {
      BaseCommand.handleEOFException(msg, servConn, eof);
      // TODO:Asif: Check if there is any need for explicitly returning
      return;
    }
    catch (InterruptedIOException e) { // Solaris only
      BaseCommand.handleInterruptedIOException(msg, servConn, e);
      return;
    }
    catch (IOException e) {
      BaseCommand.handleIOException(msg, servConn, e);
      return;
    }
    catch (DistributedSystemDisconnectedException e) {
      BaseCommand.handleShutdownException(msg, servConn, e);
      return;
    }
    catch (PartitionOfflineException e) { // fix for bug #42225
      handleExceptionNoDisconnect(msg, servConn, e);
    }
    catch (GemFireSecurityException e) {
      handleExceptionNoDisconnect(msg, servConn, e);
    }
    catch (CacheLoaderException e) {
      handleExceptionNoDisconnect(msg, servConn, e);
    }
    catch (CacheWriterException e) {
      handleExceptionNoDisconnect(msg, servConn, e);
    } catch (SerializationException e) {
      handleExceptionNoDisconnect(msg, servConn, e);
    } catch (CopyException e) {
      handleExceptionNoDisconnect(msg, servConn, e);
    }
    catch (Throwable e) {
      Error err;
      if (e instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)e)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      BaseCommand.handleThrowable(msg, servConn, e);
    } finally {
      EntryLogger.clearSource();
    }
    /*
     * finally { // Keep track of the fact that a message is no longer being //
     * processed. servConn.setNotProcessingMessage();
     * servConn.clearRequestMsg(); }
     */
  }

  /**
   * checks to see if this thread needs to masquerade as a transactional thread.
   * clients after GFE_66 should be able to start a transaction.
   * @param msg
   * @param servConn
   * @return true if thread should masquerade as a transactional thread.
   */
  protected boolean shouldMasqueradeForTx(Message msg, ServerConnection servConn) {
    if (servConn.getClientVersion().compareTo(Version.GFE_66) >= 0
        && msg.getTransactionId() != TXManagerImpl.NO_TX) {
      return true;
    }
    return false;
  }
  
  /**
   * If an operation is retried then some server may have seen it already.
   * We cannot apply this operation to the cache without knowing whether a
   * version tag has already been created for it.  Otherwise caches that have
   * seen the event already will reject it but others will not, but will have
   * no version tag with which to perform concurrency checks.
   * <p>The client event should have the event identifier from the client and
   * the region affected by the operation.
   * @param clientEvent
   */
  public boolean recoverVersionTagForRetriedOperation(EntryEventImpl clientEvent) {
    LocalRegion r = clientEvent.getRegion();    
//    if (logger.fineEnabled()) {
//      logger.fine("DEBUG: attempting to recover version tag for " + clientEvent.getEventId());
//    }
    VersionTag tag = r.findVersionTagForClientEvent(clientEvent.getEventId());
    if (tag == null) {
      if (r instanceof DistributedRegion || r instanceof PartitionedRegion) {
        // TODO this could be optimized for partitioned regions by sending the key
        // so that the PR could look at an individual bucket for the event
        tag = FindVersionTagOperation.findVersionTag(r, clientEvent.getEventId(), false);
      }
    }
    if (tag != null) {
      if (logger != null && logger.fineEnabled()) {
        logger.fine("recovered version tag " + tag + " for replayed operation " + clientEvent.getEventId());
      }
      clientEvent.setVersionTag(tag);
    }
    return (tag != null);
  }
  
  /**
   * If an operation is retried then some server may have seen it already.
   * We cannot apply this operation to the cache without knowing whether a
   * version tag has already been created for it.  Otherwise caches that have
   * seen the event already will reject it but others will not, but will have
   * no version tag with which to perform concurrency checks.
   * <p>The client event should have the event identifier from the client and
   * the region affected by the operation.
   */
  protected VersionTag findVersionTagsForRetriedPutAll(LocalRegion r, EventID eventID) {
//    if (logger.fineEnabled()) {
//      logger.fine("DEBUG: attempting to recover version tag for " + clientEvent.getEventId());
//    }
    VersionTag tag = r.findVersionTagForClientPutAll(eventID);
    if(tag != null) {
      if (logger.fineEnabled()) {
        logger.fine("recovered version tag " + tag + " for replayed put all operation " + eventID);
      }
      return tag;
    }
    if (r instanceof DistributedRegion || r instanceof PartitionedRegion) {
      // TODO this could be optimized for partitioned regions by sending the key
      // so that the PR could look at an individual bucket for the event
      tag = FindVersionTagOperation.findVersionTag(r, eventID, true);
    }
    if (tag != null) {
      if (logger.fineEnabled()) {
        logger.fine("recovered version tag " + tag + " for replayed put all operation " + eventID);
      }
    }
    return tag;
  }

  abstract public void cmdExecute(Message msg, ServerConnection servConn,
      long start) throws IOException, ClassNotFoundException, InterruptedException;

  // Used by GemFireXD to process bulk dml
  public void processBulkDML(Part statementPart, Part callbackPart) {
    //No Op
  }
  //Used by GemFireXD to generate Region Key locally, in case the tables do not have explicit PK 
  // defined. This is used to avoid key clash  , as the same key may point to different data
  // across wan sites
  
  public Object regenerateKeyConditionally(Object oldKey, LocalRegion region) {
    return oldKey;
  }

  // Used to correct the routing object in callbackArg when regenerating
  // the key using regenerateKeyConditionally
  public Object regenerateCallbackConditionally(Object key,
      Object oldCallbackArg, LocalRegion region) {
    return oldCallbackArg;
  }

  public void beforeOperationCallback(LocalRegion rgn, int actionType) {
  }

  public void afterOperationCallback(LocalRegion rgn, int actionType) {
  }

  public void processRuntimeException(RuntimeException e, String servConnName,
      String regionName, Object key, Object valuePart, Object callbackArg,
      EntryEventImpl eventForErrorLog) {
    throw e;
  }

  protected void writeReply(Message origMsg, ServerConnection servConn)
      throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    Message replyMsg = servConn.getReplyMessage();
    servConn.getCache().getCancelCriterion().checkCancelInProgress(null);
    replyMsg.setMessageType(MessageType.REPLY);
    //TODO:hitesh need to chk this, why it is setting one ??
    replyMsg.setNumberOfParts(1);
    replyMsg.setTransactionId(origMsg.getTransactionId());
    replyMsg.addBytesPart(OK_BYTES);  
    replyMsg.send(servConn);
    if (logger.finerEnabled()) {
      logger.finer(servConn.getName() + ": rpl tx: "
          + origMsg.getTransactionId());
    }
  }
  protected void writeReplyWithRefreshMetadata(Message origMsg,
      ServerConnection servConn, PartitionedRegion pr, byte nwHop) throws IOException {
    Message replyMsg = servConn.getReplyMessage();
    servConn.getCache().getCancelCriterion().checkCancelInProgress(null);
    replyMsg.setMessageType(MessageType.REPLY);
    replyMsg.setNumberOfParts(1);
    replyMsg.setTransactionId(origMsg.getTransactionId());
    replyMsg.addBytesPart(new byte[]{pr.getMetadataVersion().byteValue(), nwHop});
    replyMsg.send(servConn);
    pr.getPrStats().incPRMetaDataSentCount();
    if (logger.finerEnabled()) {
      logger.finer(servConn.getName() + ": rpl with REFRESH_METADAT tx: "
          + origMsg.getTransactionId());
    }
  }

  private static void handleEOFException(Message msg,
      ServerConnection servConn, Exception eof) {
    LogWriterI18n logger = servConn.getLogger();
    CachedRegionHelper crHelper = servConn.getCachedRegionHelper();
    CacheServerStats stats = servConn.getCacheServerStats();
    boolean potentialModification = servConn.getPotentialModification();
    if (!crHelper.isShutdown()) {
      if (potentialModification) {
        stats.incAbandonedWriteRequests();
      }
      else {
        stats.incAbandonedReadRequests();
      }
      if (!suppressIOExceptionLogging) {
        if (potentialModification) {
          int transId = (msg != null) ? msg.getTransactionId()
              : Integer.MIN_VALUE;
          if (logger.warningEnabled()) {
            logger.warning(
              LocalizedStrings.BaseCommand_0_EOFEXCEPTION_DURING_A_WRITE_OPERATION_ON_REGION__1_KEY_2_MESSAGEID_3,
              new Object[] {servConn.getName(), servConn.getModRegion(), servConn.getModKey(), Integer.valueOf(transId)});
          }
        }
        else {
          if (logger.infoEnabled()) {
            logger.info(
              LocalizedStrings.BaseCommand_0_CONNECTION_DISCONNECT_DETECTED_BY_EOF,
              servConn.getName());
          }
        }
      }
    }
    servConn.setFlagProcessMessagesAsFalse();
  }

  private static void handleInterruptedIOException(Message msg,
      ServerConnection servConn, Exception e) {
    LogWriterI18n logger = servConn.getLogger();
    CachedRegionHelper crHelper = servConn.getCachedRegionHelper();
    if (!crHelper.isShutdown() && servConn.isOpen()) {
      if (!suppressIOExceptionLogging) {
        if (logger.fineEnabled())
          logger.fine("Aborted message due to interrupt: " + e);
      }
    }
    servConn.setFlagProcessMessagesAsFalse();
  }

  private static void handleIOException(Message msg, ServerConnection servConn,
      Exception e) {
    LogWriterI18n logger = servConn.getLogger();
    CachedRegionHelper crHelper = servConn.getCachedRegionHelper();
    boolean potentialModification = servConn.getPotentialModification();

    if (!crHelper.isShutdown() && servConn.isOpen()) {
      if (!suppressIOExceptionLogging) {
        if (potentialModification) {
          int transId = (msg != null) ? msg.getTransactionId()
              : Integer.MIN_VALUE;
          if (logger.warningEnabled()) {
            logger.warning(
              LocalizedStrings.BaseCommand_0_UNEXPECTED_IOEXCEPTION_DURING_OPERATION_FOR_REGION_1_KEY_2_MESSID_3,
              new Object[] {servConn.getName(), servConn.getModRegion(), servConn.getModKey(), Integer.valueOf(transId)}, e);
          }
        }
        else {
          if (logger.warningEnabled()) {
            logger.warning(
              LocalizedStrings.BaseCommand_0_UNEXPECTED_IOEXCEPTION,
              servConn.getName(), e);
          }
        }
      }
    }
    servConn.setFlagProcessMessagesAsFalse();
  }

  private static void handleShutdownException(Message msg,
      ServerConnection servConn, Exception e) {
    LogWriterI18n logger = servConn.getLogger();
    CachedRegionHelper crHelper = servConn.getCachedRegionHelper();
    boolean potentialModification = servConn.getPotentialModification();

    if (!crHelper.isShutdown()) {
      if (potentialModification) {
        int transId = (msg != null) ? msg.getTransactionId()
            : Integer.MIN_VALUE;
        if (logger.warningEnabled()) {
          logger.warning(
            LocalizedStrings.BaseCommand_0_UNEXPECTED_SHUTDOWNEXCEPTION_DURING_OPERATION_ON_REGION_1_KEY_2_MESSAGEID_3,
            new Object[] {servConn.getName(), servConn.getModRegion(), servConn.getModKey(), Integer.valueOf(transId)}, e);
        }
      }
      else {
        if (logger.warningEnabled()) {
          logger.warning(
            LocalizedStrings.BaseCommand_0_UNEXPECTED_SHUTDOWNEXCEPTION,
            servConn.getName(),e);
        }
      }
    }
    servConn.setFlagProcessMessagesAsFalse();
  }

  // Handle GemfireSecurityExceptions separately since the connection should not
  // be terminated (by setting processMessages to false) unlike in
  // handleThrowable. Fixes bugs #38384 and #39392.
//  private static void handleGemfireSecurityException(Message msg,
//      ServerConnection servConn, GemFireSecurityException e) {
//
//    boolean requiresResponse = servConn.getTransientFlag(REQUIRES_RESPONSE);
//    boolean responded = servConn.getTransientFlag(RESPONDED);
//    boolean requiresChunkedResponse = servConn
//        .getTransientFlag(REQUIRES_CHUNKED_RESPONSE);
//    boolean potentialModification = servConn.getPotentialModification();
//
//    try {
//      try {
//        if (requiresResponse && !responded) {
//          if (requiresChunkedResponse) {
//            writeChunkedException(msg, e, false, servConn);
//          }
//          else {
//            writeException(msg, e, false, servConn);
//          }
//          servConn.setAsTrue(RESPONDED);
//        }
//      }
//      finally { // inner try-finally to ensure proper ordering of logging
//        if (potentialModification) {
//          int transId = (msg != null) ? msg.getTransactionId()
//              : Integer.MIN_VALUE;
//          if (logger.fineEnabled()) {
//            logger.fine(servConn.getName()
//                + ": Security Exception during operation on region: "
//                + servConn.getModRegion() + " key: " + servConn.getModKey()
//                + " messageId: " + transId, e);
//          }
//        }
//        else {
//          if (logger.fineEnabled()) {
//            logger.fine(servConn.getName() + ": Security Exception", e);
//          }
//        }
//      }
//    }
//    catch (IOException ioe) {
//      if (logger.fineEnabled()) {
//        logger.fine(servConn.getName()
//            + ": Unexpected IOException writing security exception: ", ioe);
//      }
//    }
//  }

  private static void handleExceptionNoDisconnect(Message msg,
      ServerConnection servConn, Exception e) {
    LogWriterI18n logger = servConn.getLogger();
    boolean requiresResponse = servConn.getTransientFlag(REQUIRES_RESPONSE);
    boolean responded = servConn.getTransientFlag(RESPONDED);
    boolean requiresChunkedResponse = servConn
        .getTransientFlag(REQUIRES_CHUNKED_RESPONSE);
    boolean potentialModification = servConn.getPotentialModification();
    boolean wroteExceptionResponse = false;

    try {
      try {
        if (requiresResponse && !responded) {
          if (requiresChunkedResponse) {
            writeChunkedException(msg, e, false, servConn);
          }
          else {
            writeException(msg, e, false, servConn);
          }
          wroteExceptionResponse = true;
          servConn.setAsTrue(RESPONDED);
        }
      }
      finally { // inner try-finally to ensure proper ordering of logging
        if (potentialModification) {
          int transId = (msg != null) ? msg.getTransactionId()
              : Integer.MIN_VALUE;
          if (!wroteExceptionResponse) {
            if (logger.warningEnabled()) {
              logger.warning(
                  LocalizedStrings.BaseCommand_0_UNEXPECTED_EXCEPTION_DURING_OPERATION_ON_REGION_1_KEY_2_MESSAGEID_3,
                  new Object[] {servConn.getName(),servConn.getModRegion(), servConn.getModKey(), Integer.valueOf(transId)}, e);
            }
          } else {
            if (logger.fineEnabled()) {
              logger.fine(servConn.getName()
                + ": Exception during operation on region: "
                + servConn.getModRegion() + " key: " + servConn.getModKey()
                + " messageId: " + transId, e);
            }
          }
        }
        else {
          if (!wroteExceptionResponse) {
            if (logger.warningEnabled()) {
              logger.warning(
                  LocalizedStrings.BaseCommand_0_UNEXPECTED_EXCEPTION,
                  servConn.getName(), e);
            }
          } else {
            if (logger.fineEnabled()) {
              logger.fine(servConn.getName() + ": Exception", e);
            }
          }
        }
      }
    }
    catch (IOException ioe) {
      if (logger.fineEnabled()) {
        logger.fine(servConn.getName()
            + ": Unexpected IOException writing exception: ", ioe);
      }
    }
  }

  private static void handleThrowable(Message msg, ServerConnection servConn,
      Throwable th) {
    LogWriterI18n logger = servConn.getLogger();
    boolean requiresResponse = servConn.getTransientFlag(REQUIRES_RESPONSE);
    boolean responded = servConn.getTransientFlag(RESPONDED);
    boolean requiresChunkedResponse = servConn
        .getTransientFlag(REQUIRES_CHUNKED_RESPONSE);
    boolean potentialModification = servConn.getPotentialModification();

    try {
      try {
        if (th instanceof Error) {
          if (logger.severeEnabled()) {
            logger.severe( LocalizedStrings.BaseCommand_0_UNEXPECTED_ERROR_ON_SERVER,
                servConn.getName(), th);
          }
        }
        if (requiresResponse && !responded) {
          if (requiresChunkedResponse) {
            writeChunkedException(msg, th, false, servConn);
          }
          else {
            writeException(msg, th, false, servConn);
          }
          servConn.setAsTrue(RESPONDED);
        }
      }
      finally { // inner try-finally to ensure proper ordering of logging
        if (th instanceof Error) {
          // log nothing
        } else if (th instanceof CancelException) {
          // log nothing
        } else if (th instanceof TransactionDataRebalancedException) {
          // log nothing
        } else if (th instanceof TransactionDataNodeHasDepartedException) {
          // log nothing
        } else {
          if (potentialModification) {
            int transId = (msg != null) ? msg.getTransactionId()
                : Integer.MIN_VALUE;
            if (logger.warningEnabled()) {
              logger.warning(
                LocalizedStrings.BaseCommand_0_UNEXPECTED_EXCEPTION_DURING_OPERATION_ON_REGION_1_KEY_2_MESSAGEID_3,
                new Object[] {servConn.getName(),servConn.getModRegion(), servConn.getModKey(), Integer.valueOf(transId)}, th);
            }
          }
          else {
            if (logger.warningEnabled()) {
              logger.warning(
                LocalizedStrings.BaseCommand_0_UNEXPECTED_EXCEPTION,
                servConn.getName(), th);
            }
          }
        }
      }
    } catch (IOException ioe) {
      if (logger.fineEnabled()) {
        logger.fine(servConn.getName()
            + ": Unexpected IOException writing exception: ", ioe);
      }
    } finally {
      servConn.setFlagProcessMessagesAsFalse();
    }
  }
  
  protected static void writeChunkedException(Message origMsg, Throwable e,
      boolean isSevere, ServerConnection servConn) throws IOException {
    writeChunkedException(origMsg, e, isSevere, servConn, servConn.getChunkedResponseMessage());
  }

  protected static void writeChunkedException(Message origMsg, Throwable e,
      boolean isSevere, ServerConnection servConn, ChunkedMessage originalReponse) throws IOException {
    writeChunkedException(origMsg, e, isSevere, servConn, originalReponse, 2);
  }

  protected static void writeChunkedException(Message origMsg, Throwable e,
      boolean isSevere, ServerConnection servConn, ChunkedMessage originalReponse, int numOfParts) throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    ChunkedMessage chunkedResponseMsg = servConn.getChunkedResponseMessage();
    chunkedResponseMsg.setServerConnection(servConn);
    if (originalReponse.headerHasBeenSent()) {
      // fix for bug 35442
      chunkedResponseMsg.setNumberOfParts(numOfParts);
      chunkedResponseMsg.setLastChunkAndNumParts(true, numOfParts);
      chunkedResponseMsg.addObjPart(e); 
      if (numOfParts == 2) {
        chunkedResponseMsg.addStringPart(getExceptionTrace(e));
      }
      if (logger.fineEnabled()) {
        logger.fine(servConn.getName()
            + ": Sending exception chunk while reply in progress: ", e);
      }
    }
    else {
      chunkedResponseMsg.setMessageType(MessageType.EXCEPTION);
      chunkedResponseMsg.setNumberOfParts(numOfParts);
      chunkedResponseMsg.setLastChunkAndNumParts(true, numOfParts);
      chunkedResponseMsg.setTransactionId(origMsg.getTransactionId());
      chunkedResponseMsg.sendHeader();
      chunkedResponseMsg.addObjPart(e);
      if (numOfParts == 2) {
        chunkedResponseMsg.addStringPart(getExceptionTrace(e));
      }
      if (logger.fineEnabled()) {
        logger.fine(servConn.getName() + ": Sending exception chunk: ", e);
      }
    }
    chunkedResponseMsg.sendChunk(servConn);
  }

  // Get the exception stacktrace for native clients
  public static String getExceptionTrace(Throwable ex) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    ex.printStackTrace(pw);
    pw.close();
    return sw.toString();
  }

  protected static void writeException(Message origMsg, Throwable e,
      boolean isSevere, ServerConnection servConn) throws IOException {
    writeException(origMsg, MessageType.EXCEPTION, e, isSevere, servConn);
  }

  protected static void writeException(Message origMsg, int msgType, Throwable e,
      boolean isSevere, ServerConnection servConn) throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    Message errorMsg = servConn.getErrorResponseMessage();
    errorMsg.setMessageType(msgType);
    errorMsg.setNumberOfParts(2);
    errorMsg.setTransactionId(origMsg.getTransactionId());
    if (isSevere) {
      String msg = e.getMessage();
      if (msg == null) {
        msg = e.toString();
      }
      if (logger.severeEnabled()) {
        logger.severe(LocalizedStrings.BaseCommand_SEVERE_CACHE_EXCEPTION_0, msg);
      }
    }
    errorMsg.addObjPart(e);
    errorMsg.addStringPart(getExceptionTrace(e));
    errorMsg.send(servConn);
    if (logger.fineEnabled()) {
      logger.fine(servConn.getName() + ": Wrote exception: ", e);
    }
  }

  protected static void writeErrorResponse(Message origMsg, int messageType,
      ServerConnection servConn) throws IOException {
    Message errorMsg = servConn.getErrorResponseMessage();
    errorMsg.setMessageType(messageType);
    errorMsg.setNumberOfParts(1);
    errorMsg.setTransactionId(origMsg.getTransactionId());
    errorMsg
        .addStringPart(LocalizedStrings.BaseCommand_INVALID_DATA_RECEIVED_PLEASE_SEE_THE_CACHE_SERVER_LOG_FILE_FOR_ADDITIONAL_DETAILS.toLocalizedString());
    errorMsg.send(servConn);
  }

  protected static void writeErrorResponse(Message origMsg, int messageType,
      String msg, ServerConnection servConn) throws IOException {
    Message errorMsg = servConn.getErrorResponseMessage();
    errorMsg.setMessageType(messageType);
    errorMsg.setNumberOfParts(1);
    errorMsg.setTransactionId(origMsg.getTransactionId());
    errorMsg.addStringPart(msg);
    errorMsg.send(servConn);
  }

  protected static void writeRegionDestroyedEx(Message msg, String regionName,
      String title, ServerConnection servConn) throws IOException {
    String reason = servConn.getName() + ": Region named " + regionName + title;
    RegionDestroyedException ex = new RegionDestroyedException(reason,
        regionName);
    if (servConn.getTransientFlag(REQUIRES_CHUNKED_RESPONSE)) {
      writeChunkedException(msg, ex, false, servConn);
    }
    else {
      writeException(msg, ex, false, servConn);
    }
  }

  protected static void writeResponse(Object data, Object callbackArg,
      Message origMsg, boolean isObject, ServerConnection servConn)
      throws IOException {
    Message responseMsg = servConn.getResponseMessage();
    responseMsg.setMessageType(MessageType.RESPONSE);
    responseMsg.setTransactionId(origMsg.getTransactionId());

    
    if (callbackArg == null) {
      responseMsg.setNumberOfParts(1);
    }
    else {
      responseMsg.setNumberOfParts(2);
    }
    if (data instanceof byte[]) {
      responseMsg.addRawPart((byte[])data, isObject);
    }
    else {
      Assert.assertTrue(isObject,
          "isObject should be true when value is not a byte[]");
      responseMsg.addObjPart(data, zipValues);
    }
    if (callbackArg != null) {
      responseMsg.addObjPart(callbackArg);
    }
    servConn.getCache().getCancelCriterion().checkCancelInProgress(null);
    responseMsg.send(servConn);
    origMsg.flush();
  }
  
  protected static void writeResponseWithRefreshMetadata(Object data,
      Object callbackArg, Message origMsg, boolean isObject,
      ServerConnection servConn, PartitionedRegion pr, byte nwHop) throws IOException {
    Message responseMsg = servConn.getResponseMessage();
    responseMsg.setMessageType(MessageType.RESPONSE);
    responseMsg.setTransactionId(origMsg.getTransactionId());

    if (callbackArg == null) {
      responseMsg.setNumberOfParts(2);
    }
    else {
      responseMsg.setNumberOfParts(3);
    }

    if (data instanceof byte[]) {
      responseMsg.addRawPart((byte[])data, isObject);
    }
    else {
      Assert.assertTrue(isObject,
          "isObject should be true when value is not a byte[]");
      responseMsg.addObjPart(data, zipValues);
    }
    if (callbackArg != null) {
      responseMsg.addObjPart(callbackArg);
    }
    responseMsg.addBytesPart(new byte[]{pr.getMetadataVersion().byteValue(),nwHop});
    servConn.getCache().getCancelCriterion().checkCancelInProgress(null);
    responseMsg.send(servConn);
    origMsg.flush();
  }

  protected static void writeResponseWithFunctionAttribute(byte[] data,
      Message origMsg, ServerConnection servConn) throws IOException {
    Message responseMsg = servConn.getResponseMessage();
    responseMsg.setMessageType(MessageType.RESPONSE);
    responseMsg.setTransactionId(origMsg.getTransactionId());
    responseMsg.setNumberOfParts(1);
    responseMsg.addBytesPart(data);
    servConn.getCache().getCancelCriterion().checkCancelInProgress(null);
    responseMsg.send(servConn);
    origMsg.flush();
  }
  
  static protected void checkForInterrupt(ServerConnection servConn, Exception e) 
      throws InterruptedException, InterruptedIOException {
    servConn.getCachedRegionHelper().checkCancelInProgress(e);
    if (e instanceof InterruptedException) {
      throw (InterruptedException)e;
    }
    if (e instanceof InterruptedIOException) {
      throw (InterruptedIOException)e;
    }
  }

  protected static void writeQueryResponseChunk(Object queryResponseChunk,
      CollectionType collectionType, boolean lastChunk,
      ServerConnection servConn) throws IOException {
    ChunkedMessage queryResponseMsg = servConn.getQueryResponseMessage();
    queryResponseMsg.setNumberOfParts(2);
    queryResponseMsg.setLastChunk(lastChunk);
    queryResponseMsg.addObjPart(collectionType, zipValues);
    queryResponseMsg.addObjPart(queryResponseChunk, zipValues);
    queryResponseMsg.sendChunk(servConn);
  }

  protected static void writeQueryResponseException(Message origMsg,
      Throwable e, boolean isSevere, ServerConnection servConn)
      throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    ChunkedMessage queryResponseMsg = servConn.getQueryResponseMessage();
    ChunkedMessage chunkedResponseMsg = servConn.getChunkedResponseMessage();
    if (queryResponseMsg.headerHasBeenSent()) {
      // fix for bug 35442
      // This client is expecting 2 parts in this message so send 2 parts
      queryResponseMsg.setServerConnection(servConn);
      queryResponseMsg.setNumberOfParts(2);
      queryResponseMsg.setLastChunkAndNumParts(true, 2);
      queryResponseMsg.addObjPart(e);
      queryResponseMsg.addStringPart(getExceptionTrace(e));
      if (logger.fineEnabled()) {
        logger.fine(servConn.getName()
            + ": Sending exception chunk while reply in progress: ", e);
      }
      queryResponseMsg.sendChunk(servConn);
    }
    else {
      chunkedResponseMsg.setServerConnection(servConn);
      chunkedResponseMsg.setMessageType(MessageType.EXCEPTION);
      chunkedResponseMsg.setNumberOfParts(2);
      chunkedResponseMsg.setLastChunkAndNumParts(true, 2);
      chunkedResponseMsg.setTransactionId(origMsg.getTransactionId());
      chunkedResponseMsg.sendHeader();
      chunkedResponseMsg.addObjPart(e);
      chunkedResponseMsg.addStringPart(getExceptionTrace(e));
      if (logger.fineEnabled()) {
        logger.fine(servConn.getName() + ": Sending exception chunk: ", e);
      }
      chunkedResponseMsg.sendChunk(servConn);
    }
  }

  protected static void writeChunkedErrorResponse(Message origMsg,
      int messageType, String message, ServerConnection servConn)
      throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    // Send chunked response header identifying error message
    ChunkedMessage chunkedResponseMsg = servConn.getChunkedResponseMessage();
    if (logger.fineEnabled()) {
      logger.fine(servConn.getName() + ": Sending error message header type: "
          + messageType + " transaction: " + origMsg.getTransactionId());
    }
    chunkedResponseMsg.setMessageType(messageType);
    chunkedResponseMsg.setTransactionId(origMsg.getTransactionId());
    chunkedResponseMsg.sendHeader();

    // Send actual error
    if (logger.fineEnabled()) {
      logger.fine(servConn.getName() + ": Sending error message chunk: "
          + message);
    }
    chunkedResponseMsg.setNumberOfParts(1);
    chunkedResponseMsg.setLastChunk(true);
    chunkedResponseMsg.addStringPart(message);
    chunkedResponseMsg.sendChunk(servConn);
  }
  
  protected static void writeFunctionResponseException(Message origMsg,
      int messageType, String message, ServerConnection servConn, Throwable e)
      throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    ChunkedMessage functionResponseMsg = servConn.getFunctionResponseMessage();
    ChunkedMessage chunkedResponseMsg = servConn.getChunkedResponseMessage();
    if (functionResponseMsg.headerHasBeenSent()) {
      functionResponseMsg.setServerConnection(servConn);
      functionResponseMsg.setNumberOfParts(2);
      functionResponseMsg.setLastChunkAndNumParts(true,2);
      functionResponseMsg.addObjPart(e);
      functionResponseMsg.addStringPart(getExceptionTrace(e));
      if (logger.fineEnabled()) {
        logger.fine(servConn.getName()
            + ": Sending exception chunk while reply in progress: ", e);
      }
      functionResponseMsg.sendChunk(servConn);
    }
    else {
      chunkedResponseMsg.setServerConnection(servConn);
      chunkedResponseMsg.setMessageType(messageType);
      chunkedResponseMsg.setNumberOfParts(2);
      chunkedResponseMsg.setLastChunkAndNumParts(true,2);
      chunkedResponseMsg.setTransactionId(origMsg.getTransactionId());
      chunkedResponseMsg.sendHeader();
      chunkedResponseMsg.addObjPart(e);
      chunkedResponseMsg.addStringPart(getExceptionTrace(e));
      if (logger.fineEnabled()) {
        logger.fine(servConn.getName() + ": Sending exception chunk: ", e);
      }
      chunkedResponseMsg.sendChunk(servConn);
    }
  }
  
  protected static void writeFunctionResponseError(Message origMsg,
      int messageType, String message, ServerConnection servConn)
      throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    ChunkedMessage functionResponseMsg = servConn.getFunctionResponseMessage();
    ChunkedMessage chunkedResponseMsg = servConn.getChunkedResponseMessage();
    if (functionResponseMsg.headerHasBeenSent()) {
      functionResponseMsg.setNumberOfParts(1);
      functionResponseMsg.setLastChunk(true);
      functionResponseMsg.addStringPart(message);
      if (logger.fineEnabled()) {
        logger.fine(servConn.getName()
            + ": Sending Error chunk while reply in progress: " + message);
      }
      functionResponseMsg.sendChunk(servConn);
    }
    else {
      chunkedResponseMsg.setMessageType(messageType);
      chunkedResponseMsg.setNumberOfParts(1);
      chunkedResponseMsg.setLastChunk(true);
      chunkedResponseMsg.setTransactionId(origMsg.getTransactionId());
      chunkedResponseMsg.sendHeader();
      chunkedResponseMsg.addStringPart(message);
      if (logger.fineEnabled()) {
        logger.fine(servConn.getName() + ": Sending Error chunk: " + message);
      }
      chunkedResponseMsg.sendChunk(servConn);
    }
  }

  protected static void writeKeySetErrorResponse(Message origMsg,
      int messageType, String message, ServerConnection servConn)
      throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    // Send chunked response header identifying error message
    ChunkedMessage chunkedResponseMsg = servConn.getKeySetResponseMessage();
    if (logger.fineEnabled()) {
      logger.fine(servConn.getName() + ": Sending error message header type: "
          + messageType + " transaction: " + origMsg.getTransactionId());
    }
    chunkedResponseMsg.setMessageType(messageType);
    chunkedResponseMsg.setTransactionId(origMsg.getTransactionId());
    chunkedResponseMsg.sendHeader();
    // Send actual error
    if (logger.fineEnabled()) {
      logger.fine(servConn.getName() + ": Sending error message chunk: "
          + message);
    }
    chunkedResponseMsg.setNumberOfParts(1);
    chunkedResponseMsg.setLastChunk(true);
    chunkedResponseMsg.addStringPart(message);
    chunkedResponseMsg.sendChunk(servConn);
  }
  
  static Message readRequest(ServerConnection servConn) {
    servConn.getLogger();
    Message requestMsg = null;
    servConn.getLogger();
    try {
      requestMsg = servConn.getRequestMessage();
      requestMsg.recv(servConn, MAX_INCOMING_DATA, incomingDataLimiter,
          MAX_INCOMING_MSGS, incomingMsgLimiter);
      return requestMsg;
    }
    catch (EOFException eof) {
      handleEOFException(null, servConn, eof);
      // TODO:Asif: Check if there is any need for explicitly returning

    }
    catch (InterruptedIOException e) { // Solaris only
      handleInterruptedIOException(null, servConn, e);

    }
    catch (IOException e) {
      handleIOException(null, servConn, e);

    }
    catch (DistributedSystemDisconnectedException e) {
      handleShutdownException(null, servConn, e);

    }
    catch (VirtualMachineError err) {
      SystemFailure.initiateFailure(err);
      // If this ever returns, rethrow the error.  We're poisoned
      // now, so don't let this thread continue.
      throw err;
    }
    catch (Throwable e) {
      SystemFailure.checkFailure();
      handleThrowable(null, servConn, e);
    }
    return requestMsg;
  }

  protected static void fillAndSendRegisterInterestResponseChunks(
      LocalRegion region, Object riKey, int interestType,
      InterestResultPolicy policy, ServerConnection servConn)
      throws IOException {
    // Client is not interested.
    if (policy.isNone()) {
      sendRegisterInterestResponseChunk(region, riKey, new ArrayList(), true,
          servConn);
      return;
    }
    if (riKey instanceof List) {
      handleList(region, (List)riKey, policy, servConn);
      return;
    }
    if (!(riKey instanceof String)) {
      handleSingleton(region, riKey, policy, servConn);
      return;
    }

    switch (interestType) {
    case InterestType.OQL_QUERY:
      // Not supported yet
      throw new InternalGemFireError(LocalizedStrings.BaseCommand_NOT_YET_SUPPORTED.toLocalizedString());
    case InterestType.FILTER_CLASS:
      throw new InternalGemFireError(LocalizedStrings.BaseCommand_NOT_YET_SUPPORTED.toLocalizedString());
      // handleFilter(region, (String)riKey, policy);
      // break;
    case InterestType.REGULAR_EXPRESSION: {
      String regEx = (String)riKey;
      if (regEx.equals(".*")) {
        handleAllKeys(region, policy, servConn);
      }
      else {
        handleRegEx(region, regEx, policy, servConn);
      }
    }
      break;
    case InterestType.KEY:
      if (riKey.equals("ALL_KEYS")) {
        handleAllKeys(region, policy, servConn);
      }
      else {
        handleSingleton(region, riKey, policy, servConn);
      }
      break;
    default:
      throw new InternalGemFireError(LocalizedStrings.BaseCommand_UNKNOWN_INTEREST_TYPE.toLocalizedString());
    }
  }

  /**
   * @param list
   *                is a List of entry keys
   */
  protected static void sendRegisterInterestResponseChunk(Region region,
      Object riKey, ArrayList list, boolean lastChunk, ServerConnection servConn)
      throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    ChunkedMessage chunkedResponseMsg = servConn.getRegisterInterestResponseMessage();
    chunkedResponseMsg.setNumberOfParts(1);
    chunkedResponseMsg.setLastChunk(lastChunk);
    chunkedResponseMsg.addObjPart(list, zipValues);
    String regionName = (region == null) ? " null " : region.getFullPath();
    if (logger.fineEnabled()) {
      String str = servConn.getName() + ": Sending"
          + (lastChunk ? " last " : " ")
          + "register interest response chunk for region: " + regionName
          + " for keys: " + riKey + " chunk=<" + chunkedResponseMsg + ">";
      logger.fine(str);
    }

    chunkedResponseMsg.sendChunk(servConn);
  }
  
  /**
   * Determines whether keys for destroyed entries (tombstones) should be sent
   * to clients in register-interest results.
   * 
   * @param servConn
   * @param policy
   * @return true if tombstones should be sent to the client
   */
  private static boolean sendTombstonesInRIResults(ServerConnection servConn, InterestResultPolicy policy) {
    return (policy == InterestResultPolicy.KEYS_VALUES)
         && (Version.GFE_75.compareTo(servConn.getClientVersion()) <= 0);
  }

  /**
   * Process an interest request involving a list of keys
   *
   * @param region
   *                the region
   * @param keyList
   *                the list of keys
   * @param policy
   *                the policy
   * @throws IOException
   */
  private static void handleList(LocalRegion region, List keyList,
      InterestResultPolicy policy, ServerConnection servConn)
      throws IOException {
    if (region instanceof PartitionedRegion) {
      // too bad java doesn't provide another way to do this...
      handleListPR((PartitionedRegion)region, keyList, policy, servConn);
      return;
    }
    ArrayList newKeyList = new ArrayList(maximumChunkSize);
    // Handle list of keys
    if (region != null) {
      for (Iterator it = keyList.iterator(); it.hasNext();) {
        Object entryKey = it.next();
        if (region.containsKey(entryKey)
            || (sendTombstonesInRIResults(servConn, policy) && region.containsTombstone(entryKey))) {
          
          appendInterestResponseKey(region, keyList, entryKey, newKeyList,
              "list", servConn);
        }
      }
    }
    // Send the last chunk (the only chunk for individual and list keys)
    // always send it back, even if the list is of zero size.
    sendRegisterInterestResponseChunk(region, keyList, newKeyList, true,
        servConn);
  }

  /**
   * Process an interest request consisting of a single key
   *
   * @param region
   *                the region
   * @param entryKey
   *                the key
   * @param policy
   *                the policy
   * @throws IOException
   */
  private static void handleSingleton(LocalRegion region, Object entryKey,
      InterestResultPolicy policy, ServerConnection servConn)
      throws IOException {
    ArrayList keyList = new ArrayList(1);
    if (region != null) {
      if (region.containsKey(entryKey) ||
          (sendTombstonesInRIResults(servConn, policy) && region.containsTombstone(entryKey))) {
        appendInterestResponseKey(region, entryKey, entryKey, keyList,
            "individual", servConn);
      }
    }
    // Send the last chunk (the only chunk for individual and list keys)
    // always send it back, even if the list is of zero size.
    sendRegisterInterestResponseChunk(region, entryKey, keyList, true, servConn);
  }

  /**
   * Process an interest request of type ALL_KEYS
   *
   * @param region
   *                the region
   * @param policy
   *                the policy
   * @throws IOException
   */
  private static void handleAllKeys(LocalRegion region,
      InterestResultPolicy policy, ServerConnection servConn)
      throws IOException {
    ArrayList keyList = new ArrayList(maximumChunkSize);
    if (region != null) {
      for (Iterator it = region.keySet(sendTombstonesInRIResults(servConn, policy)).iterator(); it.hasNext();) {
        appendInterestResponseKey(region, "ALL_KEYS", it.next(), keyList,
            "ALL_KEYS", servConn);
      }
    }
    // Send the last chunk (the only chunk for individual and list keys)
    // always send it back, even if the list is of zero size.
    sendRegisterInterestResponseChunk(region, "ALL_KEYS", keyList, true,
        servConn);
  }

  /**
   * Process an interest request of type {@link InterestType#REGULAR_EXPRESSION}
   *
   * @param region
   *                the region
   * @param regex
   *                the regex
   * @param policy
   *                the policy
   * @throws IOException
   */
  private static void handleRegEx(LocalRegion region, String regex,
      InterestResultPolicy policy, ServerConnection servConn)
      throws IOException {
    if (region instanceof PartitionedRegion) {
      // too bad java doesn't provide another way to do this...
      handleRegExPR((PartitionedRegion)region, regex, policy, servConn);
      return;
    }
    ArrayList keyList = new ArrayList(maximumChunkSize);
    // Handle the regex pattern
    Pattern keyPattern = Pattern.compile(regex);
    if (region != null) {
      for (Iterator it = region.keySet(sendTombstonesInRIResults(servConn, policy)).iterator(); it.hasNext();) {
        Object entryKey = it.next();
        if (!(entryKey instanceof String)) {
          // key is not a String, cannot apply regex to this entry
          continue;
        }
        if (!keyPattern.matcher((String)entryKey).matches()) {
          // key does not match the regex, this entry should not be returned.
          continue;
        }

        appendInterestResponseKey(region, regex, entryKey, keyList, "regex",
            servConn);
      }
    }
    // Send the last chunk (the only chunk for individual and list keys)
    // always send it back, even if the list is of zero size.
    sendRegisterInterestResponseChunk(region, regex, keyList, true, servConn);
  }

  /**
   * Process an interest request of type {@link InterestType#REGULAR_EXPRESSION}
   *
   * @param region
   *                the region
   * @param regex
   *                the regex
   * @param policy
   *                the policy
   * @throws IOException
   */
  private static void handleRegExPR(final PartitionedRegion region,
      final String regex, final InterestResultPolicy policy,
      final ServerConnection servConn) throws IOException {
    final ArrayList keyList = new ArrayList(maximumChunkSize);
    region.getKeysWithRegEx(regex, sendTombstonesInRIResults(servConn, policy), new PartitionedRegion.SetCollector() {
      public void receiveSet(Set theSet) throws IOException {
        appendInterestResponseKeys(region, regex, theSet, keyList, "regex",
            servConn);
      }
    });
    // Send the last chunk (the only chunk for individual and list keys)
    // always send it back, even if the list is of zero size.
    sendRegisterInterestResponseChunk(region, regex, keyList, true, servConn);
  }

  /**
   * Process an interest request involving a list of keys
   *
   * @param region
   *                the region
   * @param keyList
   *                the list of keys
   * @param policy
   *                the policy
   * @throws IOException
   */
  private static void handleListPR(final PartitionedRegion region,
      final List keyList, final InterestResultPolicy policy,
      final ServerConnection servConn) throws IOException {
    final ArrayList newKeyList = new ArrayList(maximumChunkSize);
    region.getKeysWithList(keyList, sendTombstonesInRIResults(servConn, policy), new PartitionedRegion.SetCollector() {
      public void receiveSet(Set theSet) throws IOException {
        appendInterestResponseKeys(region, keyList, theSet, newKeyList, "list",
            servConn);
      }
    });
    // Send the last chunk (the only chunk for individual and list keys)
    // always send it back, even if the list is of zero size.
    sendRegisterInterestResponseChunk(region, keyList, newKeyList, true,
        servConn);
  }

  /**
   * Append an interest response
   *
   * @param region
   *                the region (for debugging)
   * @param riKey
   *                the registerInterest "key" (what the client is interested
   *                in)
   * @param entryKey
   *                key we're responding to
   * @param list
   *                list to append to
   * @param kind
   *                for debugging
   */
  private static void appendInterestResponseKey(LocalRegion region,
      Object riKey, Object entryKey, ArrayList list, String kind,
      ServerConnection servConn) throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    list.add(entryKey);
    if (logger.fineEnabled()) {
      logger.fine(servConn.getName() + ": appendInterestResponseKey <"
          + entryKey + ">; list size was " + list.size() + "; region: "
          + region.getFullPath());
    }
    if (list.size() == maximumChunkSize) {
      // Send the chunk and clear the list
      sendRegisterInterestResponseChunk(region, riKey, list, false, servConn);
      list.clear();
    }
  }

  protected static void appendInterestResponseKeys(LocalRegion region,
      Object riKey, Collection entryKeys, ArrayList collector, String riDescr,
      ServerConnection servConn) throws IOException {
    for (Iterator it = entryKeys.iterator(); it.hasNext();) {
      appendInterestResponseKey(region, riKey, it.next(), collector, riDescr,
          servConn);
    }
  }

  /**
   * Process the give query and sends the resulset back to the client.
   *
   * @param msg
   * @param query
   * @param queryString
   * @param regionNames
   * @param start
   * @param cqQuery
   * @param queryContext
   * @param servConn
   * @return true if successful execution
   *         false in case of failure.
   * @throws IOException
   */
  protected static boolean processQuery(Message msg, Query query,
      String queryString, Set regionNames, long start, CqQueryImpl cqQuery,
      QueryOperationContext queryContext, ServerConnection servConn, 
      boolean sendResults)
      throws IOException, InterruptedException {
    return processQueryUsingParams(msg, query, queryString,
        regionNames, start, cqQuery, queryContext, servConn, sendResults, null);
  }
  
  /**
   * Process the give query and sends the resulset back to the client.
   *
   * @param msg
   * @param query
   * @param queryString
   * @param regionNames
   * @param start
   * @param cqQuery
   * @param queryContext
   * @param servConn
   * @return true if successful execution
   *         false in case of failure.
   * @throws IOException
   */
  protected static boolean processQueryUsingParams(Message msg, Query query,
      String queryString, Set regionNames, long start, CqQueryImpl cqQuery,
      QueryOperationContext queryContext, ServerConnection servConn, 
      boolean sendResults, Object[] params)
      throws IOException, InterruptedException {
    LogWriterI18n logger = servConn.getLogger();
    ChunkedMessage queryResponseMsg = servConn.getQueryResponseMessage();
    CacheServerStats stats = servConn.getCacheServerStats();
    CachedRegionHelper crHelper = servConn.getCachedRegionHelper();
    
    {
      long oldStart = start;
      start = DistributionStats.getStatTime();
      stats.incReadQueryRequestTime(start - oldStart);
    }

    // In case of client query, use the PDX types in serialized form.
    DefaultQuery.setPdxReadSerialized(servConn.getCache(), true);
    // from 7.0, set flag to indicate a remote query irrespective of the
    // object type
    if (servConn.getClientVersion().compareTo(Version.GFE_70) >= 0) {
      ((DefaultQuery) query).setRemoteQuery(true);
    }
    // Process the query request
    try {

      // Execute query
      // startTime = GenericStats.getTime();
      // startTime = System.currentTimeMillis();

      // For now we assume the results are a SelectResults
      // which is the only possibility now, but this may change
      // in the future if we support arbitrary queries
      Object result = null;
      
      if (params != null) {
        result = query.execute(params);
      } else {
        result = query.execute();
      }

      //Asif : Before conditioning the results check if any
      //of the regions involved in the query have been destroyed
      //or not. If yes, throw an Exception.
      //This is a workaround/fix for Bug 36969
      Iterator itr = regionNames.iterator();
      while(itr.hasNext()) {
        String regionName = (String)itr.next();
        if(crHelper.getRegion(regionName) == null) {
          throw new RegionDestroyedException(
              LocalizedStrings.BaseCommand_REGION_DESTROYED_DURING_THE_EXECUTION_OF_THE_QUERY.toLocalizedString(), regionName);
        }
      }
      AuthorizeRequestPP postAuthzRequest = servConn.getPostAuthzRequest();
      if (postAuthzRequest != null) {
        if (cqQuery == null) {
          queryContext = postAuthzRequest.queryAuthorize(queryString,
              regionNames, result, queryContext);
        }
        else {
          queryContext = postAuthzRequest.executeCQAuthorize(cqQuery.getName(),
              queryString, regionNames, result, queryContext);
        }
        result = queryContext.getQueryResult();
      }

      // endTime = System.currentTimeMillis();
      // System.out.println("Query executed in: " + (endTime-startTime) + "ms");
      // GenericStats.endTime0(startTime);


      if (result instanceof SelectResults) {
        SelectResults selectResults = (SelectResults)result;
        if (logger.fineEnabled()) {
          logger.fine("Query Result size for :" + query.getQueryString() + 
            " is :" + selectResults.size());
        }
        
        CollectionType collectionType = null;
        boolean sendCqResultsWithKey = true;
        boolean isStructs = false;
     
        // check if resultset has serialized objects, so that they could be sent
        // as ObjectPartList
        boolean hasSerializedObjects = ((DefaultQuery) query)
            .isKeepSerialized();
        if (logger.fineEnabled()) {
          logger.fine("Query Result for :" + query.getQueryString()
              + " has serialized objects :" + hasSerializedObjects);
        }
        // Don't convert to a Set, there might be duplicates now
        // The results in a StructSet are stored in Object[]s
        // Get them as Object[]s for the objs[] in order to avoid duplicating
        // the StructTypes
        
        // Object[] objs = new Object[selectResults.size()];
        // Get the collection type (which includes the element type)
        // (used to generate the appropriate instance on the client)

        // Get the collection type (which includes the element type)
        // (used to generate the appropriate instance on the client)
        collectionType = selectResults.getCollectionType();
        isStructs = collectionType.getElementType().isStructType();

        // Check if the Query is from CQ execution.
        if (cqQuery != null){
          // Check if the key can be sent to the client based on its version.
          sendCqResultsWithKey = sendCqResultsWithKey(servConn);
          
          if (sendCqResultsWithKey){
            // Update the collection type to include key info.
            collectionType = new CollectionTypeImpl(Collection.class, 
                new StructTypeImpl(new String[]{"key", "value"}));
            isStructs = collectionType.getElementType().isStructType();              
          }
        }
 
        int numberOfChunks = (int)Math.ceil(selectResults.size() * 1.0
            / maximumChunkSize);

        if (logger.finerEnabled()) {
          logger.finer(servConn.getName() + ": Query results size: " + selectResults.size() + 
              ": Entries in chunk: " + maximumChunkSize + ": Number of chunks: " + numberOfChunks);
        }
        
        long oldStart = start;
        start = DistributionStats.getStatTime();
        stats.incProcessQueryTime(start - oldStart);

        if(sendResults){
          queryResponseMsg.setMessageType(MessageType.RESPONSE);
          queryResponseMsg.setTransactionId(msg.getTransactionId());
          queryResponseMsg.sendHeader();
        }

        if (sendResults && numberOfChunks == 0) {
          // Send 1 empty chunk
          if (logger.finerEnabled()) {
            logger.finer(servConn.getName() + ": Creating chunk: 0");
          }
          writeQueryResponseChunk(new Object[0], collectionType, true, servConn);
          if (logger.fineEnabled()) {
            logger.fine(servConn.getName()
                + ": Sent chunk (1 of 1) of query response for query "
                + queryString);
          }
        }
        else {
          // Send response to client.
          // from 7.0, if the object is in the form of serialized byte array,
          // send it as a part of ObjectPartList
          if (hasSerializedObjects) {
            sendResultsAsObjectPartList(numberOfChunks, servConn,
                selectResults.asList(), isStructs, collectionType,
                queryString, cqQuery, sendCqResultsWithKey, sendResults);
          } else {
            sendResultsAsObjectArray(selectResults, numberOfChunks, servConn,
                isStructs, collectionType, queryString, cqQuery, sendCqResultsWithKey, sendResults);
          }
        }
        
        if(cqQuery != null){
          // Set the CQ query result cache initialized flag.
          cqQuery.setCqResultsCacheInitialized();
        }
        
      }
      else if (result instanceof Integer) {
        if (sendResults) {
          queryResponseMsg.setMessageType(MessageType.RESPONSE);
          queryResponseMsg.setTransactionId(msg.getTransactionId());
          queryResponseMsg.sendHeader();
          writeQueryResponseChunk(result, null, true, servConn);
        }
      }
      else {
        throw new QueryInvalidException(LocalizedStrings.BaseCommand_UNKNOWN_RESULT_TYPE_0.toLocalizedString(result.getClass()));
      }
      msg.flush();
    }
    catch (QueryInvalidException e) {
      // Handle this exception differently since it can contain
      // non-serializable objects.
      // java.io.NotSerializableException: antlr.CommonToken
      // Log a warning to show stack trace and create a new
      // QueryInvalidEsception on the original one's message (not cause).
      if (logger.warningEnabled()) {
        logger.warning(
          LocalizedStrings.BaseCommand_UNEXPECTED_QUERYINVALIDEXCEPTION_WHILE_PROCESSING_QUERY_0, queryString, e);
      }
      QueryInvalidException qie = new QueryInvalidException(LocalizedStrings.BaseCommand_0_QUERYSTRING_IS_1
          .toLocalizedString(new Object[] {e.getLocalizedMessage(), queryString}));
      writeQueryResponseException(msg, qie, false, servConn);
      return false;
    }
    catch (DistributedSystemDisconnectedException se) {
      if (logger != null && msg != null && logger.fineEnabled()) {
        logger.fine(servConn.getName() + " ignoring message of type "
            + MessageType.getString(msg.getMessageType()) + " from client "
            + servConn.getProxyID()
            + " because shutdown occurred during message processing.");
      }
      servConn.setFlagProcessMessagesAsFalse();
      return false;
    }
    catch (Exception e) {
      // If an interrupted exception is thrown , rethrow it
      checkForInterrupt(servConn, e);
      // Otherwise, write a query response and continue
      // Check if query got canceled from QueryMonitor.
      DefaultQuery defaultQuery = (DefaultQuery)query;
      if ((defaultQuery).isCanceled()){
        e = new QueryException(defaultQuery.getQueryCanceledException().getMessage(), e.getCause());
      }
      writeQueryResponseException(msg, e, false, servConn);
      return false;
    }  finally {
      DefaultQuery.setPdxReadSerialized(servConn.getCache(), false);
      ((DefaultQuery)query).setRemoteQuery(false);
    }

    if (logger.fineEnabled()) {
      logger.fine(servConn.getName() + ": Sent query response for query "
          + queryString);
    }

    stats.incWriteQueryResponseTime(DistributionStats.getStatTime() - start);
    return true;
  }
  
  private static boolean sendCqResultsWithKey(ServerConnection servConn) {
    Version clientVersion = servConn.getClientVersion();
    if (clientVersion.compareTo(Version.GFE_65) >= 0) {
      return true;
    }
    return false;
  }

  protected static void sendCqResponse(int msgType, String msgStr, int txId,
      Throwable e, ServerConnection servConn) throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    ChunkedMessage cqMsg = servConn.getChunkedResponseMessage();
    if (logger.fineEnabled()) {
      logger.fine("CQ Response message :" + msgStr);
    }

    switch (msgType) {
    case MessageType.REPLY:
      cqMsg.setNumberOfParts(1);
      break;

    case MessageType.CQDATAERROR_MSG_TYPE:
      if (logger.warningEnabled()) {
        logger.warning(LocalizedStrings.ONE_ARG, msgStr);
      }
      cqMsg.setNumberOfParts(1);
      break;

    case MessageType.CQ_EXCEPTION_TYPE:
      String exMsg = "";
      if (e != null) {
        exMsg = e.getLocalizedMessage();
      }

      if (logger.fineEnabled()) {
        logger.fine(msgStr + exMsg, e);
      }
      else {
        logger.info(LocalizedStrings.TWO_ARG, new Object[] {msgStr, exMsg}, e);
      }

      msgStr += exMsg; // fixes bug 42309

      cqMsg.setNumberOfParts(1);
      break;

    default:
      msgType = MessageType.CQ_EXCEPTION_TYPE;
      cqMsg.setNumberOfParts(1);
      msgStr += LocalizedStrings.BaseCommand_UNKNOWN_QUERY_EXCEPTION.toLocalizedString();
      break;
    }

    cqMsg.setMessageType(msgType);
    cqMsg.setTransactionId(txId);
    cqMsg.sendHeader();
    cqMsg.addStringPart(msgStr);
    cqMsg.setLastChunk(true);
    cqMsg.sendChunk(servConn);
    cqMsg.setLastChunk(true);

    if (logger.fineEnabled()) {
      logger.fine("CQ Response sent successfully");
    }
  }
  
  private static void sendResultsAsObjectArray(SelectResults selectResults,
      int numberOfChunks, ServerConnection servConn, 
      boolean isStructs, CollectionType collectionType, String queryString, CqQueryImpl cqQuery, boolean sendCqResultsWithKey, boolean sendResults)
      throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    int resultIndex = 0;
    // For CQ only as we dont want CQEntries which have null values.
    int cqResultIndex = 0;
    Object[] objs = selectResults.toArray();
    for (int j = 0; j < numberOfChunks; j++) {
      boolean incompleteArray = false;
      if (logger.finerEnabled()) {
        logger.finer(servConn.getName() + ": Creating chunk: " + j);
      }
      Object[] results = new Object[maximumChunkSize];
      for (int i = 0; i < maximumChunkSize; i++) {
        if ((resultIndex) == selectResults.size()) {
          incompleteArray = true;
          break;
        }
        if (logger.finerEnabled()) {
            logger.finer(servConn.getName() + ": Adding entry [" + resultIndex
                + "] to query results: " + objs[resultIndex]);
        }
        if (cqQuery != null){
          CqEntry e = (CqEntry)objs[resultIndex];
          // The value may have become null because of entry invalidation.
          if (e.getValue() == null) {
            resultIndex++;
            // i will get incremented anyway so we need to decrement it back so
            // that results[i] is not null.
            i--;
            continue;
          }    
          // Add the key into CQ results cache.
          // For PR the Result caching is not yet supported.
          // cqQuery.cqResultsCacheInitialized is added to take care
          // of CQ execute requests that are re-sent. In that case no
          // need to update the Results cache.
          if (!cqQuery.isPR) {
            cqQuery.addToCqResultKeys(e.getKey());
          }
  
          // Add to the Results object array.
          if (sendCqResultsWithKey) {
            results[i] = e.getKeyValuePair();
          } else {
            results[i] = e.getValue();
          }      
        } else {
          // instance check added to fix bug 40516.
          if (isStructs && (objs[resultIndex] instanceof Struct)) {
            results[i] = ((Struct) objs[resultIndex]).getFieldValues();
          } else {
            results[i] = objs[resultIndex];
          }
        }
        resultIndex++;
        cqResultIndex++;
      }
      // Shrink array if necessary. This will occur if the number
      // of entries in the chunk does not divide evenly into the
      // number of entries in the result set.
      if (incompleteArray) {
        Object[] newResults;
        if (cqQuery != null) {
          newResults = new Object[cqResultIndex % maximumChunkSize];
        } else {
          newResults = new Object[resultIndex % maximumChunkSize];
        }
        for (int i = 0; i < newResults.length; i++) {
          newResults[i] = results[i];
        }
        results = newResults;
      }

      if (sendResults) {
        writeQueryResponseChunk(results, collectionType,
            (resultIndex == selectResults.size()), servConn);
        
        if (logger.fineEnabled()) {
          logger.fine(servConn.getName() + ": Sent chunk (" + (j + 1) + " of "
              + numberOfChunks + ") of query response for query " + queryString);
        }
      }
      // If we have reached the last element of SelectResults then we should
      // break out of loop here only.
      if (resultIndex == selectResults.size()) {
        break;
      }
    }
  }

  private static void sendResultsAsObjectPartList(int numberOfChunks,
      ServerConnection servConn, List objs, boolean isStructs,
      CollectionType collectionType, String queryString, CqQueryImpl cqQuery, boolean sendCqResultsWithKey, boolean sendResults)
      throws IOException {
    LogWriterI18n logger = servConn.getLogger();
    int resultIndex = 0;
    Object result = null;
    for (int j = 0; j < numberOfChunks; j++) {
      if (logger.finerEnabled()) {
        logger.finer(servConn.getName() + ": Creating chunk: " + j);
      }
      ObjectPartList serializedObjs = new ObjectPartList(maximumChunkSize,
          false);
      for (int i = 0; i < maximumChunkSize; i++) {
        if ((resultIndex) == objs.size()) {
          break;
        }
        if (logger.finerEnabled()) {
            logger.finer(servConn.getName() + ": Adding entry [" + resultIndex
                + "] to query results: " + objs.get(resultIndex));
        }
        if (cqQuery != null){
          CqEntry e = (CqEntry)objs.get(resultIndex);
          // The value may have become null because of entry invalidation.
          if (e.getValue() == null) {
            resultIndex++;
            continue;
          }    
          // Add the key into CQ results cache.
          // For PR the Result caching is not yet supported.
          // cqQuery.cqResultsCacheInitialized is added to take care
          // of CQ execute requests that are re-sent. In that case no
          // need to update the Results cache.
          if (!cqQuery.isPR) {
            cqQuery.addToCqResultKeys(e.getKey());
          }
  
          // Add to the Results object array.
          if (sendCqResultsWithKey) {
            result = e.getKeyValuePair();
          } else {
            result = e.getValue();
          }      
        }
        else {
          result = objs.get(resultIndex);
        }
        if (sendResults) {
          addToObjectPartList(serializedObjs, result, collectionType, false,
              servConn, isStructs);
        }
        resultIndex++;
      }
      
      if (sendResults) {
        writeQueryResponseChunk(serializedObjs, collectionType,
            ((j + 1) == numberOfChunks), servConn);

        if (logger.fineEnabled()) {
          logger
              .fine(servConn.getName() + ": Sent chunk (" + (j + 1) + " of "
                  + numberOfChunks + ") of query response for query "
                  + queryString);
        }
      }
   }
  }

  private static void addToObjectPartList(ObjectPartList serializedObjs,
      Object res, CollectionType collectionType, boolean lastChunk,
      ServerConnection servConn, boolean isStructs) throws IOException {

    if (isStructs && (res instanceof Struct)) {
      Object[] values = ((Struct) res).getFieldValues();
      // create another ObjectPartList for the struct
      ObjectPartList serializedValueObjs = new ObjectPartList(values.length,
          false);
      for (Object value : values) {
        if (value instanceof CachedDeserializable) {
          serializedValueObjs.addPart(null,
              ((CachedDeserializable) value).getSerializedValue(),
              ObjectPartList.OBJECT, null);
        } else {
          addDeSerializedObjectToObjectPartList(serializedValueObjs, value);
        }
      }
      serializedObjs.addPart(null, serializedValueObjs, ObjectPartList.OBJECT,
          null);
    } else if (res instanceof Object[]) {// for CQ key-value pairs
      Object[] values = ((Object[]) res);
      // create another ObjectPartList for the Object[]
      ObjectPartList serializedValueObjs = new ObjectPartList(values.length,
          false);
      for (Object value : values) {
        if (value instanceof CachedDeserializable) {
          serializedValueObjs.addPart(null,
              ((CachedDeserializable) value).getSerializedValue(),
              ObjectPartList.OBJECT, null);
        } else {
          addDeSerializedObjectToObjectPartList(serializedValueObjs, value);
        }
      }
      serializedObjs.addPart(null, serializedValueObjs, ObjectPartList.OBJECT,
          null);
    } else if (res instanceof CachedDeserializable) {
      serializedObjs.addPart(null,
          ((CachedDeserializable) res).getSerializedValue(),
          ObjectPartList.OBJECT, null);
    } else { // for deserialized objects
      addDeSerializedObjectToObjectPartList(serializedObjs, res);
    }
  }
  
  private static void addDeSerializedObjectToObjectPartList(
      ObjectPartList objPartList, Object obj) {
    if (obj instanceof byte[]) {
      objPartList.addPart(null, obj, ObjectPartList.BYTES, null);
    } else {
      objPartList.addPart(null, obj, ObjectPartList.OBJECT, null);
    }
  }
}
