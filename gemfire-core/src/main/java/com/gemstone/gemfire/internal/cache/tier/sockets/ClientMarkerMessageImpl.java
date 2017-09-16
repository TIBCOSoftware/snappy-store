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

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.Conflatable;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.tier.MessageType;
import com.gemstone.gemfire.internal.shared.Version;

import java.io.*;

/**
 * Class <code>ClientMarkerMessageImpl</code> is a marker message that is
 * placed in the <code>CacheClientProxy</code>'s queue when the client connects
 * to notify the client that all of its queued updates have been sent. This is
 * to be used mostly by the durable clients, although all clients receive it.
 *
 * @author Barry Oglesby
 *
 * @since 5.5
 */
public final class ClientMarkerMessageImpl implements ClientMessage {
  private static final long serialVersionUID = 5423895238521508743L;

  /**
   * This <code>ClientMessage</code>'s <code>LogWriterI18n</code>
   */
  private LogWriterI18n logger;

  /**
   * This <code>ClientMessage</code>'s <code>EventID</code>
   */
  private EventID eventId;

  /**
   * Constructor.
   *
   * @param eventId This <code>ClientMessage</code>'s <code>EventID</code>
   */
  public ClientMarkerMessageImpl(EventID eventId, LogWriterI18n logger) {
    this.logger = logger;
    this.eventId = eventId;
  }

  /**
   * Default constructor.
   */
  public ClientMarkerMessageImpl() {
  }

  public Message getMessage(CacheClientProxy proxy, boolean notify) throws IOException {
    Version clientVersion = proxy.getVersion();
    Message message = null;
    if (clientVersion.compareTo(Version.GFE_57) >= 0) {
      message = getGFEMessage();
    } else {
      throw new IOException(
          "Unsupported client version for server-to-client message creation: "
              + clientVersion);
    }
      
    return message;
  }
  
  protected Message getGFEMessage() throws IOException {
    Message message = new Message(1, Version.CURRENT_GFE);
    message.setMessageType(MessageType.CLIENT_MARKER);
    message.setLogger(this.logger);
    message.setTransactionId(0);
    message.addObjPart(this.eventId);
    return message;
  }

  public boolean shouldBeConflated() {
    return true;
  }

  /**
   * {@inheritDoc}
   */
  public boolean shouldBeMerged() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  public boolean merge(Conflatable existing) {
    throw new AssertionError("not expected to be invoked");
  }

  public void toData(DataOutput out) throws IOException {
    DataSerializer.writeObject(this.eventId, out);
  }

  public int getDSFID() {
    return CLIENT_MARKER_MESSAGE_IMPL;
  }

  public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {
    this.eventId = (EventID) DataSerializer.readObject(in);
    DistributedSystem ds = InternalDistributedSystem.getAnyInstance();
    if (ds != null) {
      this.logger = ds.getLogWriter().convertToLogWriterI18n();
    }
  }

  public EventID getEventId() {
    return this.eventId;
  }

  public String getRegionToConflate() {
    return "gemfire_reserved_region_name_for_durable_client_marker";
  }

  public Object getKeyToConflate() {
    // This method can be called by HARegionQueue.
    // Use this to identify the message type.
    return "marker";
  }

  public Object getValueToConflate() {
    // This method can be called by HARegionQueue
    // Use this to identify the message type.
    return "marker";
  }

  public void setLatestValue(Object value){
    return;
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }
}
