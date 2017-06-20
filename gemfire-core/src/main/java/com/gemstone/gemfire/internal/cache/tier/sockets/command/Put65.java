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
/**
 * 
 */
package com.gemstone.gemfire.internal.cache.tier.sockets.command;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import com.gemstone.gemfire.InvalidDeltaException;
import com.gemstone.gemfire.cache.DynamicRegionFactory;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.ResourceException;
import com.gemstone.gemfire.cache.operations.PutOperationContext;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.CachedDeserializable;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.tier.CachedRegionHelper;
import com.gemstone.gemfire.internal.cache.tier.Command;
import com.gemstone.gemfire.internal.cache.tier.MessageType;
import com.gemstone.gemfire.internal.cache.tier.sockets.BaseCommand;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheServerStats;
import com.gemstone.gemfire.internal.cache.tier.sockets.Message;
import com.gemstone.gemfire.internal.cache.tier.sockets.Part;
import com.gemstone.gemfire.internal.cache.tier.sockets.ServerConnection;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.security.AuthorizeRequest;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.Breadcrumbs;
import com.gemstone.gemfire.security.GemFireSecurityException;

/**
 * @since 6.5 
 */
public class Put65 extends BaseCommand {

  private final static Put65 singleton = new Put65();

  public static Command getCommand() {
    return singleton;
  }

  protected Put65() {
  }

  @Override
  public void cmdExecute(Message msg, ServerConnection servConn, long p_start)
      throws IOException, InterruptedException {
    long start = p_start;
    Part regionNamePart = null, keyPart = null, valuePart = null, callbackArgPart = null;
    String regionName = null;
    Object callbackArg = null, key = null;
    Part eventPart = null;
    boolean isDelta = false;
    CachedRegionHelper crHelper = servConn.getCachedRegionHelper();
    CacheServerStats stats = servConn.getCacheServerStats();
    if (crHelper.emulateSlowServer() > 0) {
      boolean interrupted = Thread.interrupted();
      try {
        Thread.sleep(crHelper.emulateSlowServer());
      }
      catch (InterruptedException ugh) {
        interrupted = true;
      }
      finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    // requiresResponse = true;
    servConn.setAsTrue(REQUIRES_RESPONSE);
    {
      long oldStart = start;
      start = DistributionStats.getStatTime();
      stats.incReadPutRequestTime(start - oldStart);
    }
    // Retrieve the data from the message parts
    int idx = 0;
    regionNamePart = msg.getPart(idx++);
    Operation operation;
    try {
      operation = (Operation)msg.getPart(idx++).getObject();
      if (operation == null) { // native clients send a null since the op is java-serialized
        operation = Operation.UPDATE;
      }
    } catch (ClassNotFoundException e) {
      writeException(msg, e, false, servConn);
      servConn.setAsTrue(RESPONDED);
      return;
    }
    int flags = msg.getPart(idx++).getInt();
    boolean requireOldValue = ((flags & 0x01) == 0x01);
    boolean haveExpectedOldValue = ((flags & 0x02) == 0x02);
    Object expectedOldValue = null;
    if (haveExpectedOldValue) {
      try {
        expectedOldValue = msg.getPart(idx++).getObject();
      } catch (ClassNotFoundException e) {
        writeException(msg, e, false, servConn);
        servConn.setAsTrue(RESPONDED);
        return;
      }
    }
    keyPart = msg.getPart(idx++);
    try {
      isDelta = ((Boolean)msg.getPart(idx).getObject()).booleanValue();
      idx += 1;
    }
    catch (Exception e) {
      writeException(msg, MessageType.PUT_DELTA_ERROR, e, false, servConn);
      servConn.setAsTrue(RESPONDED);
      // CachePerfStats not available here.
      return;
    }
    valuePart = msg.getPart(idx++);
    eventPart = msg.getPart(idx++);
    if (msg.getNumberOfParts() > idx) {
      callbackArgPart = msg.getPart(idx++);
      try {
        callbackArg = callbackArgPart.getObject();
      }
      catch (Exception e) {
        writeException(msg, e, false, servConn);
        servConn.setAsTrue(RESPONDED);
        return;
      }
    }
    regionName = regionNamePart.getString();
    
    try {
      key = keyPart.getStringOrObject();
    }
    catch (Exception e) {
      writeException(msg, e, false, servConn);
      servConn.setAsTrue(RESPONDED);
      return;
    }

    if (logger.fineEnabled()) {
      logger.fine(servConn.getName() + ": Received "
          + (isDelta ? " delta " : " ") + "put request ("
          + msg.getPayloadLength() + " bytes) from "
          + servConn.getSocketString() + " for region " + regionName + " key "
          + key + " txId " + msg.getTransactionId() + " posdup: " + msg.isRetry());
    }

    // Process the put request
    if (key == null || regionName == null) {
      StringBuilder errMessage = new StringBuilder();
      if (key == null) {
        String putMsg = " The input key for the put request is null";
        if (logger.warningEnabled())
          logger.fine(servConn.getName() + ":" + putMsg);
        errMessage.append(putMsg);
      }
      if (regionName == null) {
        String putMsg = " The input region name for the put request is null";
        if (logger.warningEnabled())
          logger.fine(servConn.getName() + ":" + putMsg);
        errMessage.append(putMsg);
      }
      writeErrorResponse(msg, MessageType.PUT_DATA_ERROR,
          errMessage.toString(), servConn);
      servConn.setAsTrue(RESPONDED);
    }
    else {
      LocalRegion region = (LocalRegion)crHelper.getRegion(regionName);
      if (region == null) {
        String reason = " was not found during put request";
        writeRegionDestroyedEx(msg, regionName, reason, servConn);
        servConn.setAsTrue(RESPONDED);
      }
      else if (valuePart.isNull() && operation != Operation.PUT_IF_ABSENT && region.containsKey(key)) {
        // Invalid to 'put' a null value in an existing key
        String putMsg = " Attempted to put a null value for existing key "
            + key;
        if (logger.infoEnabled())
          logger.fine(servConn.getName() + ":" + putMsg);
        StringBuilder errMessage = new StringBuilder();
        errMessage.append(putMsg);
        writeErrorResponse(msg, MessageType.PUT_DATA_ERROR, errMessage
            .toString(), servConn);
        servConn.setAsTrue(RESPONDED);
      }
      else {
        // try {
        // this.eventId = (EventID)eventPart.getObject();
        ByteBuffer eventIdPartsBuffer = ByteBuffer.wrap(eventPart
            .getSerializedForm());
        long threadId = EventID
            .readEventIdPartsFromOptmizedByteArray(eventIdPartsBuffer);
        long sequenceId = EventID
            .readEventIdPartsFromOptmizedByteArray(eventIdPartsBuffer);

        EntryEventImpl clientEvent = new EntryEventImpl(
            new EventID(servConn.getEventMemberIDByteArray(),
              threadId, sequenceId));
        
        Breadcrumbs.setEventId(clientEvent.getEventId());

        // msg.isRetry might be set by v7.0 and later clients
        if (msg.isRetry()) {
//          if (logger.fineEnabled()) {
//            logger.fine("DEBUG: encountered isRetry in Put65");
//          }
          clientEvent.setPossibleDuplicate(true);
          if (region.getAttributes().getConcurrencyChecksEnabled()) {
            // recover the version tag from other servers
            clientEvent.setRegion(region);
            if (!recoverVersionTagForRetriedOperation(clientEvent)) {
              clientEvent.setPossibleDuplicate(false); // no-one has seen this event
            }
          }
        }
        
        boolean result = false;
        boolean sendOldValue = false;
        boolean oldValueIsObject = true;
        Object oldValue = null;

        try {
          Object value = null;
          if (!isDelta) {
            value = valuePart.getSerializedForm();
          }
          boolean isObject = valuePart.isObject();
          boolean isMetaRegion = region.isUsedForMetaRegion();
          msg.setMetaRegion(isMetaRegion);
          AuthorizeRequest authzRequest = null;
          if (!isMetaRegion) {
            authzRequest = servConn.getAuthzRequest();
          }
          if (authzRequest != null) {
            // TODO SW: This is to handle DynamicRegionFactory create
            // calls. Rework this when the semantics of DynamicRegionFactory are
            // cleaned up.
            if (DynamicRegionFactory.regionIsDynamicRegionList(regionName)) {
              authzRequest.createRegionAuthorize((String)key);
            }
            // Allow PUT operations on meta regions (bug #38961)
            else {
              PutOperationContext putContext = authzRequest.putAuthorize(
                  regionName, key, value, isObject, callbackArg);
              value = putContext.getValue();
              isObject = putContext.isObject();
              callbackArg = putContext.getCallbackArg();
            }
          }
          if (logger.fineEnabled()) {
            logger.fine("processing put65 with operation=" + operation);
          }
          // If the value is 1 byte and the byte represents null,
          // attempt to create the entry. This test needs to be
          // moved to DataSerializer or DataSerializer.NULL needs
          // to be publicly accessible.
          if (operation == Operation.PUT_IF_ABSENT) {
//            try {
            if (msg.isRetry() && clientEvent.getVersionTag() != null) {
              // bug #46590 the operation was successful the last time since it
              // was applied to the cache, so return success and the recovered
              // version tag
              if (logger.fineEnabled()) {
                logger.fine("putIfAbsent operation was successful last time with version " 
                    + clientEvent.getVersionTag());
              }
              oldValue = null;
            } else {
              oldValue = region.basicBridgePutIfAbsent(key, value, isObject,
                callbackArg, servConn.getProxyID(), true, clientEvent);
            }
            sendOldValue = true;
            oldValueIsObject = true;
            Version clientVersion = servConn.getClientVersion();
            if (oldValue instanceof CachedDeserializable) {
              oldValue = ((CachedDeserializable)oldValue).getSerializedValue();
            } else if (oldValue instanceof byte[]) {
              oldValueIsObject = false;
            } else if ((oldValue instanceof Token)
                && clientVersion.compareTo(Version.GFE_651) <= 0) {
              // older clients don't know that Token is now a DSFID class, so we
              // put the token in a serialized form they can consume
              HeapDataOutputStream str = new HeapDataOutputStream(Version.CURRENT_GFE);
              DataOutput dstr = new DataOutputStream(str);
              InternalDataSerializer.writeSerializableObject(oldValue, dstr);
              oldValue = str.toByteArray();
            }
            result = true;
//            } catch (Exception e) {
//              writeException(msg, e, false, servConn);
//              servConn.setAsTrue(RESPONDED);
//              return;
//            }
            
          } else if (operation == Operation.REPLACE) {
//            try {
              if (requireOldValue) { // <V> replace(<K>, <V>)
                oldValue = region.basicBridgeReplace(key, value, isObject,
                  callbackArg, servConn.getProxyID(), true, clientEvent);
                if (msg.isRetry() && clientEvent.isConcurrencyConflict()
                    && clientEvent.getVersionTag() != null) {
                  if (logger.fineEnabled()) {
                    logger.fine("replace(k,v) operation was successful last time with version " 
                        + clientEvent.getVersionTag());
                  }
                }
                sendOldValue = !clientEvent.isConcurrencyConflict();
                oldValueIsObject = true;
                Version clientVersion = servConn.getClientVersion();
                if (oldValue instanceof CachedDeserializable) {
                  oldValue = ((CachedDeserializable)oldValue).getSerializedValue();
                } else if (oldValue instanceof byte[]) {
                  oldValueIsObject = false;
                } else if ((oldValue instanceof Token)
                    && clientVersion.compareTo(Version.GFE_651) <= 0) {
                  // older clients don't know that Token is now a DSFID class, so we
                  // put the token in a serialized form they can consume
                  HeapDataOutputStream str = new HeapDataOutputStream(Version.CURRENT_GFE);
                  DataOutput dstr = new DataOutputStream(str);
                  InternalDataSerializer.writeSerializableObject(oldValue, dstr);
                  oldValue = str.toByteArray();
                }
                if (logger.fineEnabled()) {
                  logger.fine("returning " + oldValue + " from replace(K,V)");
                }
                result = true;
              } else { // boolean replace(<K>, <V>, <V>) {
                boolean didPut;
                didPut = region.basicBridgeReplace(key, expectedOldValue,
                    value, isObject, callbackArg, servConn.getProxyID(), true, clientEvent);
                if (msg.isRetry() && clientEvent.getVersionTag() != null) {
                  if (logger.fineEnabled()) {
                    logger.fine("replace(k,v,v) operation was successful last time with version " 
                        + clientEvent.getVersionTag());
                  }
                  didPut = true;
                }
                sendOldValue = true;
                oldValueIsObject = true;
                oldValue = didPut? Boolean.TRUE : Boolean.FALSE;
                if (logger.fineEnabled()) {
                  logger.fine("returning " + oldValue + " from replace(K,V,V)");
                }
                result = true;
              }
//            } catch (Exception e) {
//              writeException(msg, e, false, servConn);
//              servConn.setAsTrue(RESPONDED);
//              return;
//            }
            
          } else if (value == null && !isDelta) {
            // Create the null entry. Since the value is null, the value of the
            // isObject
            // the true after null doesn't matter and is not used.
            result = region.basicBridgeCreate(key, null, true, callbackArg,
                servConn.getProxyID(), true, clientEvent, false);
            if (msg.isRetry() && clientEvent.isConcurrencyConflict()
                && clientEvent.getVersionTag() != null) {
              result = true;
              if (logger.fineEnabled()) {
                logger.fine("create(k,null) operation was successful last time with version " 
                    + clientEvent.getVersionTag());
              }
            }
          }
          else {
            // Put the entry
            byte[] delta = null;
            if (isDelta) {
              delta = valuePart.getSerializedForm();              
            }
            TXManagerImpl txMgr = (TXManagerImpl)servConn.getCache().getCacheTransactionManager();
            // bug 43068 - use create() if in a transaction and op is CREATE
            if (txMgr.getTXState() != null && operation.isCreate()) {
              result = region.basicBridgeCreate(key, (byte[])value, isObject, callbackArg,
                  servConn.getProxyID(), true, clientEvent, true);
            } else {
              result = region.basicBridgePut(key, value, delta, isObject,
                callbackArg, servConn.getProxyID(), true, servConn
                    .isGemFireXDSystem(), clientEvent);
            }
            if (msg.isRetry() && clientEvent.isConcurrencyConflict()
                && clientEvent.getVersionTag() != null) {
              if (logger.fineEnabled()) {
                logger.fine("put(k,v) operation was successful last time with version " 
                    + clientEvent.getVersionTag());
                result = true;
              }
            }
          }
          if (result) {
            servConn.setModificationInfo(true, regionName, key);
          }
          else {
            String message = servConn.getName()
                + ": Failed to put entry for region " + regionName
                + " key " + key + " value " + valuePart;
            if (logger.infoEnabled())
              logger.fine(message);
            throw new Exception(message);
          }
        }
        catch (RegionDestroyedException rde) {
          writeException(msg, rde, false, servConn);
          servConn.setAsTrue(RESPONDED);
          return;
        }
        catch (ResourceException re) {
          writeException(msg, re, false, servConn);
          servConn.setAsTrue(RESPONDED);
          return;
        }
        catch (InvalidDeltaException ide) {
          if (logger.infoEnabled()) {
            logger
                .info(
                    LocalizedStrings.UpdateOperation_ERROR_APPLYING_DELTA_FOR_KEY_0_OF_REGION_1,
                    new Object[] { key, regionName });
          }
          writeException(msg, MessageType.PUT_DELTA_ERROR, ide, false, servConn);
          servConn.setAsTrue(RESPONDED);
          region.getCachePerfStats().incDeltaFullValuesRequested();
          return;
        }
        catch (Exception ce) {
          // If an interrupted exception is thrown , rethrow it
          checkForInterrupt(servConn, ce);

          // If an exception occurs during the put, preserve the connection
          writeException(msg, ce, false, servConn);
          servConn.setAsTrue(RESPONDED);
          if (ce instanceof GemFireSecurityException) {
            // Fine logging for security exceptions since these are already
            // logged by the security logger
            if (logger.fineEnabled()) {
              logger.fine(servConn.getName()
                  + ": Unexpected Security exception", ce);
            }
          }
          else if (logger.fineEnabled()) {
            logger.fine(servConn.getName() + ": Unexpected Exception", ce);
          }
          return;
        }
        finally {
          long oldStart = start;
          start = DistributionStats.getStatTime();
          stats.incProcessPutTime(start - oldStart);
        }

        // Increment statistics and write the reply
        if (region instanceof PartitionedRegion) {
          PartitionedRegion pr = (PartitionedRegion)region;
          if (pr.isNetworkHop().byteValue() != (byte)0) {
            writeReplyWithRefreshMetadata(msg, servConn, pr, sendOldValue, oldValueIsObject, 
                oldValue, pr.isNetworkHop().byteValue(), clientEvent.getVersionTag());
            pr.setIsNetworkHop((byte)0);
            pr.setMetadataVersion(Byte.valueOf((byte)0));
          }
          else {
            writeReply(msg, servConn, sendOldValue, oldValueIsObject, oldValue, clientEvent.getVersionTag());
          }
        }
        else {
          writeReply(msg, servConn, sendOldValue, oldValueIsObject, oldValue, clientEvent.getVersionTag());
        }
        servConn.setAsTrue(RESPONDED);
        if (logger.fineEnabled()) {
          logger.fine(servConn.getName() + ": Sent put response back to "
              + servConn.getSocketString() + " for region " + regionName
              + " key " + key + " value " + valuePart);
        }
        stats.incWritePutResponseTime(DistributionStats.getStatTime() - start);
      }
    }

  }
  protected void writeReply(Message origMsg, ServerConnection servConn,
      boolean sendOldValue, boolean oldValueIsObject, Object oldValue,
      VersionTag tag)
  throws IOException {
    Message replyMsg = servConn.getReplyMessage();
    servConn.getCache().getCancelCriterion().checkCancelInProgress(null);
    replyMsg.setMessageType(MessageType.REPLY);
    replyMsg.setNumberOfParts(sendOldValue? 3 : 1);
    replyMsg.setTransactionId(origMsg.getTransactionId());
    replyMsg.addBytesPart(OK_BYTES);
    if (sendOldValue) {
      replyMsg.addIntPart(oldValueIsObject?1:0);
      replyMsg.addObjPart(oldValue);
    }
    replyMsg.send(servConn);
    if (logger.finerEnabled()) {
      logger.finer(servConn.getName() + ": rpl tx: "
          + origMsg.getTransactionId() + " parts=" + replyMsg.getNumberOfParts());
    }
  }
  protected void writeReplyWithRefreshMetadata(Message origMsg,
      ServerConnection servConn, PartitionedRegion pr,
      boolean sendOldValue, boolean oldValueIsObject, Object oldValue, byte nwHopType,
      VersionTag tag)
  throws IOException {
    Message replyMsg = servConn.getReplyMessage();
    servConn.getCache().getCancelCriterion().checkCancelInProgress(null);
    replyMsg.setMessageType(MessageType.REPLY);
    replyMsg.setNumberOfParts(sendOldValue? 3 : 1);
    replyMsg.setTransactionId(origMsg.getTransactionId());
    replyMsg.addBytesPart(new byte[]{pr.getMetadataVersion().byteValue(), nwHopType});
    if (sendOldValue) {
      replyMsg.addIntPart(oldValueIsObject?1:0);
      replyMsg.addObjPart(oldValue);
    }
    replyMsg.send(servConn);
    pr.getPrStats().incPRMetaDataSentCount();
    if (logger.finerEnabled()) {
      logger.finer(servConn.getName() + ": rpl with REFRESH_METADAT tx: "
          + origMsg.getTransactionId()+ " parts=" + replyMsg.getNumberOfParts());
    }
  }

}
