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

package com.gemstone.gemfire.internal.tcp;

import java.io.IOException;
import java.util.List;

import com.gemstone.gemfire.i18n.LogWriterI18n;

/**
 * Base interface for {@link MsgStreamer} and {@link MsgStreamerList} to send a
 * message over a list of connections to one or more peers.
 * 
 * @author swale
 * @since 7.1
 */
public interface BaseMsgStreamer {

  public void reserveConnections(long startTime, long ackTimeout,
      long ackSDTimeout);

  /**
   * Returns a list of the Connections that the message was sent to. Call this
   * after {@link #writeMessage}.
   */
  public List<?> getSentConnections();

  /**
   * Returns an exception the describes which cons the message was not sent to.
   * Call this after {@link #writeMessage}.
   */
  public ConnectExceptions getConnectExceptions();

  /**
   * Writes the message to the connected streams and returns the number of bytes
   * written.
   * 
   * @throws IOException
   *           if serialization failure
   */
  public int writeMessage() throws IOException;

  /**
   * Close this streamer.
   * 
   * @throws IOException
   *           on exception
   */
  public void close(LogWriterI18n logger) throws IOException;

  /**
   * Release any resources associated with this streamer.
   * In most cases it must have been already closed.
   */
  public void release();
}
