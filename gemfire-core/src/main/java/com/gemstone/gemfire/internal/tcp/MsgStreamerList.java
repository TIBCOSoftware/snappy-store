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
import java.util.Collections;
import java.util.List;

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * Encapsulates a set of {@link MsgStreamer}s and {@link VersionedMsgStreamer}s
 * requiring possibly different serializations for different versions of
 * product.
 * 
 * @author swale
 * @since 7.1
 */
public final class MsgStreamerList implements BaseMsgStreamer {

  /**
   * List of {@link BaseMsgStreamer}s encapsulated by this MsgStreamerList.
   */
  private final List<BaseMsgStreamer> streamers;

  MsgStreamerList(List<BaseMsgStreamer> streamers) {
    this.streamers = streamers;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void reserveConnections(long startTime, long ackTimeout,
      long ackSDTimeout) {
    for (BaseMsgStreamer streamer : this.streamers) {
      streamer.reserveConnections(startTime, ackTimeout, ackSDTimeout);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int writeMessage() throws IOException {
    int result = 0;
    RuntimeException ex = null;
    IOException ioe = null;
    for (BaseMsgStreamer streamer : this.streamers) {
      if (ex != null) {
        streamer.release();
      }
      try {
        result += streamer.writeMessage();
        // if there is an exception we need to finish the
        // loop and release the other streamer's buffers
      } catch (RuntimeException e) {
        ex = e;
      } catch (IOException e) {
        ioe = e;
      }
    }
    if (ex != null) {
      throw ex;
    }
    if (ioe != null) {
      throw ioe;
    }
    return result;
  }

  /**
   * {@inheritDoc}
   */
  @SuppressWarnings("unchecked")
  @Override
  public List<?> getSentConnections() {
    List<Object> sentCons = Collections.emptyList();
    for (BaseMsgStreamer streamer : this.streamers) {
      if (sentCons.size() == 0) {
        sentCons = (List<Object>)streamer.getSentConnections();
      }
      else {
        sentCons.addAll(streamer.getSentConnections());
      }
    }
    return sentCons;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ConnectExceptions getConnectExceptions() {
    ConnectExceptions ce = null;
    for (BaseMsgStreamer streamer : this.streamers) {
      if (ce == null) {
        ce = streamer.getConnectExceptions();
      }
      else {
        // loop through all failures and add to base ConnectionException
        ConnectExceptions e = streamer.getConnectExceptions();
        if (e != null) {
          List<?> members = e.getMembers();
          List<?> exs = e.getCauses();
          for (int i = 0; i < exs.size(); i++) {
            ce.addFailure((InternalDistributedMember)members.get(i),
                (Throwable)exs.get(i));
          }
        }
      }
    }
    return ce;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close(LogWriterI18n logger) throws IOException {
    // only throw the first exception and try to close all
    IOException ex = null;
    for (BaseMsgStreamer m : this.streamers) {
      try {
        m.close(logger);
      } catch (IOException e) {
        if (ex == null) {
          ex = e;
        }
        else {
          // log the exception and move on to close others
          logger.severe(LocalizedStrings.ONE_ARG,
              "Unknown error closing streamer", e);
        }
      }
    }
    if (ex != null) {
      throw ex;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    for (BaseMsgStreamer m : this.streamers) {
      m.release();
    }
  }
}
