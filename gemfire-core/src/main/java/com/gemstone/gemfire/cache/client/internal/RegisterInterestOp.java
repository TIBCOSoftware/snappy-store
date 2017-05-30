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
package com.gemstone.gemfire.cache.client.internal;

import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.tier.MessageType;
import com.gemstone.gemfire.internal.cache.tier.sockets.Message;
import com.gemstone.gemfire.internal.cache.tier.sockets.ChunkedMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.Part;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.internal.cache.tier.InterestType;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.cache.InterestResultPolicy;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.client.ServerOperationException;
import com.gemstone.gemfire.distributed.internal.ServerLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Does a region registerInterest on a server
 * @author darrel
 * @since 5.7
 */
public class RegisterInterestOp {
  /**
   * Does a region registerInterest on a server using connections from the given pool
   * to communicate with the server.
   * @param pool the pool to use to communicate with the server.
   * @param region the name of the region to do the registerInterest on
   * @param key describes what we are interested in
   * @param interestType the {@link InterestType} for this registration
   * @param policy the interest result policy for this registration
   * @param isDurable true if this registration is durable
   * @param regionDataPolicy the data policy ordinal of the region
   * @return list of keys
   */
  public static List execute(ExecutablePool pool,
                             String region,
                             Object key,
                             int interestType,
                             InterestResultPolicy policy,
                             boolean isDurable,
                             boolean receiveUpdatesAsInvalidates,
                             byte regionDataPolicy)
  {
    AbstractOp op = new RegisterInterestOpImpl(pool.getLoggerI18n(), region,
        key, interestType, policy, isDurable, receiveUpdatesAsInvalidates, regionDataPolicy);
    return  (List) pool.executeOnQueuesAndReturnPrimaryResult(op);
  }
                                                               
  /**
   * Does a region registerInterest on a server using connections from the given pool
   * to communicate with the given server location.
   * @param sl the server to do the register interest on.
   * @param pool the pool to use to communicate with the server.
   * @param region the name of the region to do the registerInterest on
   * @param key describes what we are interested in
   * @param interestType the {@link InterestType} for this registration
   * @param policy the interest result policy for this registration
   * @param isDurable true if this registration is durable
   * @param regionDataPolicy the data policy ordinal of the region
   * @return list of keys
   */
  public static List executeOn(ServerLocation sl,
                               ExecutablePool pool,
                               String region,
                               Object key,
                               int interestType,
                               InterestResultPolicy policy,
                               boolean isDurable,
                               boolean receiveUpdatesAsInvalidates,
                               byte regionDataPolicy)
  {
    AbstractOp op = new RegisterInterestOpImpl(pool.getLoggerI18n(), region,
        key, interestType, policy, isDurable, receiveUpdatesAsInvalidates, regionDataPolicy);
    return  (List) pool.executeOn(sl, op);
  }

  
  /**
   * Does a region registerInterest on a server using connections from the given pool
   * to communicate with the given server location.
   * @param conn the connection to do the register interest on.
   * @param pool the pool to use to communicate with the server.
   * @param region the name of the region to do the registerInterest on
   * @param key describes what we are interested in
   * @param interestType the {@link InterestType} for this registration
   * @param policy the interest result policy for this registration
   * @param isDurable true if this registration is durable
   * @param regionDataPolicy the data policy ordinal of the region
   * @return list of keys
   */
  public static List executeOn(Connection conn,
                               ExecutablePool pool,
                               String region,
                               Object key,
                               int interestType,
                               InterestResultPolicy policy,
                               boolean isDurable,
                               boolean receiveUpdatesAsInvalidates,
                               byte regionDataPolicy)
  {
    AbstractOp op = new RegisterInterestOpImpl(pool.getLoggerI18n(), region,
        key, interestType, policy, isDurable, receiveUpdatesAsInvalidates, regionDataPolicy);
    return  (List) pool.executeOn(conn, op);
  }

  
  private RegisterInterestOp() {
    // no instances allowed
  }

  protected static class RegisterInterestOpImpl extends AbstractOp {
    /**
     * @throws com.gemstone.gemfire.SerializationException if serialization fails
     */
    public RegisterInterestOpImpl(LogWriterI18n lw,
                                  String region,
                                  Object key,
                                  int interestType,
                                  InterestResultPolicy policy,
                                  boolean isDurable,
                                  boolean receiveUpdatesAsInvalidates,
                                  byte regionDataPolicy) {
      super(lw, MessageType.REGISTER_INTEREST, 7);
      getMessage().addStringPart(region);
      getMessage().addIntPart(interestType);
      getMessage().addObjPart(policy);
      {
        byte durableByte = (byte)(isDurable ? 0x01 : 0x00);
        getMessage().addBytesPart(new byte[] {durableByte});
      }
      getMessage().addStringOrObjPart(key);
      byte notifyByte = (byte)(receiveUpdatesAsInvalidates ? 0x01 : 0x00);
      getMessage().addBytesPart(new byte[] {notifyByte});

      getMessage().addBytesPart(new byte[] {regionDataPolicy});
    }
    /**
     * This constructor is used by our subclass CreateCQWithIROpImpl
     * @throws com.gemstone.gemfire.SerializationException if serialization fails
     */
    protected RegisterInterestOpImpl(LogWriterI18n lw, int msgType, int numParts) {
      super(lw, msgType, numParts);
    }

    @Override  
    protected Message createResponseMessage() {
      return new ChunkedMessage(1, Version.CURRENT_GFE);
    }
    
    @Override  
    protected Object processResponse(Message m) throws Exception {
      ChunkedMessage msg = (ChunkedMessage)m;
      msg.readHeader();
      switch (msg.getMessageType()) {
      case MessageType.RESPONSE_FROM_PRIMARY: {
        ArrayList serverKeys = new ArrayList();
        // Process the chunks
        do {
          // Read the chunk
          msg.receiveChunk();

          // Deserialize the result
          Part part = msg.getPart(0);

          Object partObj = part.getObject();
          if (partObj instanceof Throwable) {
            String s = "While performing a remote " + getOpName();
            throw new ServerOperationException(s, (Throwable)partObj);
            // Get the exception toString part.
            // This was added for c++ thin client and not used in java
            //Part exceptionToStringPart = msg.getPart(1);
          }
          else {
            // Add the result to the list of results
            serverKeys.add(partObj);
          }

        } while (!msg.isLastChunk());
        return serverKeys;
      }
      case MessageType.RESPONSE_FROM_SECONDARY:
        // Read the chunk
        msg.receiveChunk();
        return null;
      case MessageType.EXCEPTION:
        // Read the chunk
        msg.receiveChunk();
        // Deserialize the result
        Part part = msg.getPart(0);
        // Get the exception toString part.
        // This was added for c++ thin client and not used in java
        //Part exceptionToStringPart = msg.getPart(1);
        Object obj = part.getObject();
        {
          String s = this + ": While performing a remote " + getOpName();
          throw new ServerOperationException(s, (Throwable) obj);
        }
      case MessageType.REGISTER_INTEREST_DATA_ERROR:
        // Read the chunk
        msg.receiveChunk();

        // Deserialize the result
        String errorMessage = msg.getPart(0).getString();
        String s = this + ": While performing a remote " + getOpName() + ": ";
        throw new ServerOperationException(s + errorMessage);
      default:
        throw new InternalGemFireError("Unknown message type "
                                       + msg.getMessageType());
      }
    }
    protected String getOpName() {
      return "registerInterest";
    }
    @Override  
    protected boolean isErrorResponse(int msgType) {
      return msgType == MessageType.REGISTER_INTEREST_DATA_ERROR;
    }
    @Override  
    protected long startAttempt(ConnectionStats stats) {
      return stats.startRegisterInterest();
    }
    @Override  
    protected void endSendAttempt(ConnectionStats stats, long start) {
      stats.endRegisterInterestSend(start, hasFailed());
    }
    @Override  
    protected void endAttempt(ConnectionStats stats, long start) {
      stats.endRegisterInterest(start, hasTimedOut(), hasFailed());
    }
  }
}
