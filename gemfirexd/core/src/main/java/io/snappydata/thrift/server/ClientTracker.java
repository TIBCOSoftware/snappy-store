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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.server;

import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.SystemTimer;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TLongHashSet;
import com.gemstone.gnu.trove.TLongProcedure;
import com.pivotal.gemfirexd.internal.engine.Misc;
import org.apache.thrift.transport.TTransport;

/**
 * Tracks connections from a client to cleanup when client goes away.
 */
public class ClientTracker extends SystemTimer.SystemTimerTask {
  private final SnappyDataServiceImpl service;
  private final String clientHostId;
  private final TLongHashSet connectionIds;
  private final THashSet clientSockets;
  private boolean cleanupScheduled;

  public ClientTracker(SnappyDataServiceImpl service, String clientHostId) {
    this.service = service;
    this.clientHostId = clientHostId;
    this.connectionIds = new TLongHashSet(8);
    this.clientSockets = new THashSet(8);
  }

  public static String getClientHostId(String clientHost, String clientId) {
    // the clientId passed above may have connection specific information
    // appended so remove that first if required
    if (clientHost != null && clientHost.length() > 0 &&
        clientId != null && clientId.length() > 0) {
      int pipeIndex = clientId.indexOf('|');
      if (pipeIndex >= 0) {
        pipeIndex = clientId.indexOf('|', pipeIndex + 1);
        if (pipeIndex > 0) {
          return clientHost + ':' + clientId.substring(0, pipeIndex);
        }
      }
      return clientHost + ':' + clientId;
    } else {
      return null;
    }
  }

  public static ClientTracker addOrGetTracker(String clientHostId,
      SnappyDataServiceImpl service) {
    if (clientHostId != null && clientHostId.length() > 0) {
      ClientTracker tracker = new ClientTracker(service, clientHostId);
      ClientTracker currentTracker = service.clientTrackerMap.putIfAbsent(
          clientHostId, tracker);
      return currentTracker == null ? tracker : currentTracker;
    } else {
      // no tracker can be created if hostname or ID are missing
      return null;
    }
  }

  /**
   * Add entry for a new connection from this client.
   */
  public synchronized void addClientConnection(long connId) {
    this.connectionIds.add(connId);
  }

  /**
   * Add entry for a new socket from this client.
   */
  public synchronized void addClientSocket(TTransport transport,
      SnappyDataServiceImpl service) {
    this.clientSockets.add(transport);
    service.clientSocketTrackerMap.put(transport, this);
  }

  /**
   * Remove a client connection and return true if all client connections
   * have been closed.
   */
  public synchronized boolean removeClientConnection(long connId) {
    this.connectionIds.remove(connId);
    return this.connectionIds.isEmpty();
  }

  /**
   * Remove a client socket registered in a service.
   */
  public static void removeClientSocket(TTransport transport,
      SnappyDataServiceImpl service) {
    final ClientTracker tracker = service.clientSocketTrackerMap.remove(
        transport);
    if (tracker != null) {
      tracker.removeClientSocket(transport);
    }
  }

  /**
   * Remove a client socket registered for this client.
   */
  public synchronized void removeClientSocket(TTransport transport) {
    if (this.clientSockets.isEmpty()) {
      return; // already removed
    }
    this.clientSockets.remove(transport);
    if (this.clientSockets.isEmpty()) {
      if (this.connectionIds.isEmpty()) {
        // everything empty, clear map
        this.service.clientTrackerMap.remove(this.clientHostId);
      } else if (!cleanupScheduled) {
        // schedule cleanup of all remaining client connections after
        // some period of inactivity (to ensure client is really gone);
        // not using ConnectionSignaller since this is a potentially
        // blocking operation
        try {
          Misc.getGemFireCache().getCCPTimer().schedule(this, 3000L);
          cleanupScheduled = true;
        } catch (IllegalStateException e) {
          // wait for sometime before trying again
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ie) {
            Misc.checkIfCacheClosing(ie);
          }
          try {
            Misc.getGemFireCache().getCCPTimer().schedule(this, 3000L);
            cleanupScheduled = true;
          } catch (IllegalStateException ie) {
            // give up but do remove from global map so new ClientTracker
            // will be created if same client connects back
            this.service.clientTrackerMap.remove(this.clientHostId);
            cleanupScheduled = false;
          }
        }
      }
    }
  }

  // SystemTimerTask methods

  @Override
  public synchronized void run2() {
    // if sockets are still empty and dangling connections remain, then clear
    // them up and also clean up the global maps for the client
    if (this.clientSockets.isEmpty()) {
      this.service.clientTrackerMap.remove(this.clientHostId);
      if (this.connectionIds.size() > 0) {
        this.connectionIds.forEach(new TLongProcedure() {
          @Override
          public boolean execute(long connId) {
            service.forceCloseConnection(connId);
            return true;
          }
        });
      }
    } else {
      cleanupScheduled = false;
    }
  }

  @Override
  public LogWriterI18n getLoggerI18n() {
    return Misc.getI18NLogWriter();
  }
}
