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
import java.io.NotSerializableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.ToDataException;
import com.gemstone.gemfire.distributed.internal.DMStats;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.ReplySender;
import com.gemstone.gemfire.distributed.internal.LonerDistributionManager.DummyDMStats;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * A reply sender which replies back directly to a dedicated socket
 * socket.
 * @author dsmith
 *
 */
public class DirectReplySender implements ReplySender {
  private static final DMStats DUMMY_STATS = new DummyDMStats();

  private final Connection conn;
  private boolean sentReply = false;

  public DirectReplySender(Connection connection) {
    this.conn = connection;
  }

  public LogWriterI18n getLoggerI18n() {
    return conn.getLogger();
  }

  public Set putOutgoing(DistributionMessage msg) {
    Assert.assertTrue(!this.sentReply, "Trying to reply twice to a message");
    //Using an ArrayList, rather than Collections.singletonList here, because the MsgStreamer
    //mutates the list when it has exceptions.
    
    // fix for bug #42199 - cancellation check
    this.conn.owner.getDM().getCancelCriterion().checkCancelInProgress(null);
    
    if(DistributionManager.VERBOSE) {
      getLoggerI18n().info(LocalizedStrings.DEBUG, "Sending a direct reply "  + msg + " to " + conn.getRemoteAddress());
    }
    ArrayList<Connection> conns = new ArrayList<Connection>(1);
    conns.add(conn);
    BaseMsgStreamer ms = null;
    try {
      if (conn.useNIOStream()) {
        ms = MsgChannelStreamer.create(conns, msg, false, DUMMY_STATS);
      } else {
        ms = MsgStreamer.create(conns, msg, false, DUMMY_STATS);
      }
      ms.writeMessage();
      ConnectExceptions ce = ms.getConnectExceptions();
      if(ce != null && !ce.getMembers().isEmpty()) {
        Assert.assertTrue(ce.getMembers().size() == 1);
        conn.getLogger()
            .warning(
                LocalizedStrings.DirectChannel_FAILURE_SENDING_DIRECT_REPLY,
                ce.getMembers().iterator().next());
        return Collections.singleton(ce.getMembers().iterator().next());
      }
      sentReply = true;
      return Collections.emptySet();
    } 
    catch (NotSerializableException e) {
      throw new InternalGemFireException(e);
    } 
    catch (ToDataException e) {
      // exception from user code
      throw e;
    } 
    catch (IOException ex) {
      throw new InternalGemFireException(LocalizedStrings.DirectChannel_UNKNOWN_ERROR_SERIALIZING_MESSAGE.toLocalizedString(), ex);
    }
    finally {
      try {
        if (ms != null) {
          ms.close(getLoggerI18n());
        }
      }
      catch (IOException e) {
        throw new InternalGemFireException("Unknown error serializing message", e);
      }
    }

  }

}
