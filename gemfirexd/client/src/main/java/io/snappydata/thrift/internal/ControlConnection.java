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

package io.snappydata.thrift.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TObjectProcedure;
import com.pivotal.gemfirexd.internal.client.net.NetConnection;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import io.snappydata.thrift.HostAddress;
import io.snappydata.thrift.LocatorService;
import io.snappydata.thrift.ServerType;
import io.snappydata.thrift.SnappyException;
import io.snappydata.thrift.common.SnappyTSSLSocket;
import io.snappydata.thrift.common.SnappyTSocket;
import io.snappydata.thrift.common.SocketParameters;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.common.ThriftUtils;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TTransport;

/**
 * Holds locator, server information to use for failover. Also provides
 * convenience methods to actually search for an appropriate host for failover.
 * <p>
 * One distributed system is supposed to have one ControlConnection.
 */
final class ControlConnection {
  private final SocketParameters socketParams;
  private final boolean framedTransport;
  private final Set<ServerType> snappyServerTypeSet;
  private final List<HostAddress> locators;
  private HostAddress controlHost;
  private LocatorService.Client controlLocator;
  private final ArrayList<HostAddress> controlHosts;
  private final THashSet controlHostSet;

  public static final class SearchRandomServer implements TObjectProcedure {

    private final ServerType searchServerType;
    private final Set<HostAddress> failedServers;
    private HostAddress foundServer;
    private int searchIndex;

    public static final Random rand = new Random();

    SearchRandomServer(ServerType searchServerType, int setSize,
        Set<HostAddress> failedServers) {
      this.searchServerType = searchServerType;
      this.failedServers = failedServers != null ? failedServers : Collections
          .<HostAddress> emptySet();
      this.searchIndex = rand.nextInt(setSize);
    }

    public HostAddress getRandomServer() {
      return this.foundServer;
    }

    @Override
    public boolean execute(Object o) {
      if (this.searchIndex > 0) {
        this.searchIndex--;
      }
      else if (this.foundServer != null) {
        return false;
      }

      HostAddress hostAddr = (HostAddress)o;
      if (hostAddr.getServerType() == this.searchServerType
          && !this.failedServers.contains(hostAddr)) {
        this.foundServer = hostAddr;
      }
      return true;
    }
  }

  /**
   * Since one DS is supposed to have one ControlConnection, so we expect the
   * total size of this static global list to be small.
   */
  private static final ArrayList<ControlConnection> allControlConnections =
      new ArrayList<>(2);

  ControlConnection(ClientService service) {
    this.socketParams = service.socketParams;
    this.framedTransport = service.framedTransport;
    this.snappyServerTypeSet = Collections.singleton(getServerType());
    this.locators = service.connHosts;
    this.controlHosts = new ArrayList<>(service.connHosts);
    this.controlHostSet = new THashSet(service.connHosts);
  }

  final ServerType getServerType() {
    return this.socketParams.getServerType();
  }

  static ControlConnection getOrCreateControlConnection(HostAddress hostAddr,
      ClientService service, Throwable failure) throws SnappyException {
    // loop through all ControlConnections since size of this global list is
    // expected to be in single digit (total number of distributed systems)
    synchronized (allControlConnections) {
      int index = allControlConnections.size();
      while (--index >= 0) {
        ControlConnection controlService = allControlConnections.get(index);
        synchronized (controlService) {
          if (controlService.controlHostSet.contains(hostAddr)) {
            // we expect the serverType to match
            if (controlService.getServerType() == service.getServerType()) {
              return controlService;
            }
            else {
              throw ThriftExceptionUtil.newSnappyException(
                  SQLState.DRDA_CONNECTION_TERMINATED, null,
                  hostAddr != null ? hostAddr.toString() : null,
                  "found server " + hostAddr
                      + " as registered but having different type "
                      + controlService.getServerType() + " than connection "
                      + service.getServerType());
            }
          }
        }
      }
      // if we reached here, then need to create a new ControlConnection
      ControlConnection controlService = new ControlConnection(service);
      controlService.getPreferredServer(null, null, true, failure);
      allControlConnections.add(controlService);
      return controlService;
    }
  }

  private HostAddress getLocatorPreferredServer(
      Set<HostAddress> failedServers, Set<String> serverGroups)
      throws TException {
    if (SanityManager.TraceClientHA) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
          "getPreferredServer() trying using host " + this.controlHost);
    }
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getPreferredServer_S", null, 0, ns,
          true, null);
    }

    HostAddress preferredServer = controlLocator.getPreferredServer(
        this.snappyServerTypeSet, serverGroups, failedServers);

    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getPreferredServer_E", null, 0, ns,
          false, null);
    }
    return preferredServer;
  }

  synchronized HostAddress getPreferredServer(Set<HostAddress> failedServers,
      Set<String> serverGroups, boolean forFailover, Throwable failure)
      throws SnappyException {

    if (controlLocator == null) {
      failedServers = failoverToAvailableHost(failedServers, false, failure);
      forFailover = true;
    }

    boolean firstCall = true;
    while (true) {
      try {
        HostAddress preferredServer;
        if (forFailover) {
          if (SanityManager.TraceClientHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "getAllServersWithPreferredServer() trying using host "
                    + this.controlHost);
          }
          if (SanityManager.TraceClientStatement) {
            final long ns = System.nanoTime();
            SanityManager.DEBUG_PRINT_COMPACT(
                "getAllServersWithPreferredServer_S", null, 0, ns, true, null);
          }

          // refresh the full host list
          List<HostAddress> prefServerAndAllHosts = controlLocator
              .getAllServersWithPreferredServer(this.snappyServerTypeSet,
                  serverGroups, failedServers);
          // refresh the new server list
          List<HostAddress> allHosts = prefServerAndAllHosts.subList(1,
              prefServerAndAllHosts.size());
          refreshAllHosts(allHosts);
          preferredServer = prefServerAndAllHosts.get(0);

          if (SanityManager.TraceClientHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "Returning all hosts " + allHosts
                    + " using control connection to " + this.controlHost);
          }
          if (SanityManager.TraceClientStatement) {
            final long ns = System.nanoTime();
            SanityManager.DEBUG_PRINT_COMPACT(
                "getAllServersWithPreferredServer_E", null, 0, ns, false,
                null);
          }
        } else {
          preferredServer = getLocatorPreferredServer(failedServers,
              serverGroups);
        }

        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "Got preferred server " + preferredServer
                  + " using control connection to " + this.controlHost
                  + (preferredServer.getPort() > 0 ? "" : " (trying random "
                  + "server since no preferred server received)"));
        }
        if (preferredServer.getPort() <= 0) {
          // For this case we don't have a locator or locator unable to
          // determine a preferred server, so choose some server randomly
          // as the "preferredServer". In case all servers have failed
          // then the search below will also fail.
          // Remove controlHost from failedServers since its known to be
          // working at this point (e.g. after a reconnect).
          Set<HostAddress> skipServers = failedServers;
          if (failedServers != null && !failedServers.isEmpty() &&
              failedServers.contains(this.controlHost)) {
            // don't change the original failure list since that is proper
            // for the current operation but change for random server search
            skipServers = new HashSet<>(failedServers);
            skipServers.remove(this.controlHost);
          }
          preferredServer = searchRandomServer(skipServers, failure);
        }
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "Returning preferred server " + preferredServer
                  + " with current control connection to " + this.controlHost);
        }
        return preferredServer;
      } catch (TException te) {
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "getPreferredServer() received exception from control host "
                  + this.controlHost, te);
        }
        if (te instanceof SnappyException) {
          SnappyException se = (SnappyException)te;
          NetConnection.FailoverStatus status;
          if ((status = NetConnection.getFailoverStatus(se
              .getExceptionData().getSqlState(), se.getExceptionData()
              .getErrorCode(), se)).isNone()) {
            throw se;
          } else if (status == NetConnection.FailoverStatus.RETRY) {
            forFailover = true;
            continue;
          }
        }
        // search for a new host for locator query
        if (failedServers == null) {
          @SuppressWarnings("unchecked")
          Set<HostAddress> servers = new THashSet(2);
          failedServers = servers;
        }
        // for the first call do not mark controlHost as failed but retry
        // (e.g. for a reconnect case)
        if (firstCall) {
          firstCall = false;
        } else {
          failedServers.add(controlHost);
        }
        controlLocator.getOutputProtocol().getTransport().close();
        failedServers = failoverToAvailableHost(failedServers, true, te);
        if (failure == null) {
          failure = te;
        }
      } catch (Throwable t) {
        throw unexpectedError(t, controlHost);
      }
      forFailover = true;
    }
  }

  HostAddress searchRandomServer(Set<HostAddress> failedServers,
      Throwable failure) throws SnappyException {
    HostAddress preferredServer;
    SearchRandomServer search = new SearchRandomServer(getServerType(),
        this.controlHostSet.size(), failedServers);
    this.controlHostSet.forEach(search);
    if ((preferredServer = search.getRandomServer()) != null) {
      return preferredServer;
    } else {
      throw failoverExhausted(failedServers, failure);
    }
  }

  private synchronized Set<HostAddress> failoverToAvailableHost(
      Set<HostAddress> failedServers, boolean checkFailedControlHosts,
      Throwable failure) throws SnappyException {

    NEXT_SERVER: for (HostAddress controlAddr : this.controlHosts) {
      if (checkFailedControlHosts && failedServers != null &&
          failedServers.contains(controlAddr)) {
        continue;
      }
      this.controlHost = null;
      this.controlLocator = null;

      TTransport inTransport;
      TTransport outTransport = null;
      TProtocol inProtocol;
      TProtocol outProtocol;
      while (true) {
        try {
          if (outTransport != null) {
            outTransport.close();
          }

          if (SanityManager.TraceClientHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "Trying control connection to host " + controlAddr);
          }
          if (SanityManager.TraceClientStatement
              | SanityManager.TraceClientConn) {
            final long ns = System.nanoTime();
            SanityManager.DEBUG_PRINT_COMPACT(
                "failoverToAvailableHost_S", null, 0, ns, true,
                SanityManager.TraceClientConn ? new Throwable() : null);
          }

          final TTransport transport;
          if (getServerType().isThriftSSL()) {
            transport = new SnappyTSSLSocket(controlAddr, this.socketParams);
          } else {
            transport = new SnappyTSocket(controlAddr, null, false, true,
                ThriftUtils.isThriftSelectorServer(), this.socketParams);
          }
          if (this.framedTransport) {
            inTransport = outTransport = new TFramedTransport(transport);
          } else {
            inTransport = outTransport = transport;
          }
          if (getServerType().isThriftBinaryProtocol()) {
            inProtocol = new TBinaryProtocol(inTransport);
            outProtocol = new TBinaryProtocol(outTransport);
          } else {
            inProtocol = new TCompactProtocol(inTransport);
            outProtocol = new TCompactProtocol(outTransport);
          }
          break;
        } catch (TException te) {
          if (SanityManager.TraceClientHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "Received exception in control connection to host "
                    + controlAddr, te);
          }
          failure = te;
          // search for a new host for locator query
          if (failedServers == null) {
            @SuppressWarnings("unchecked")
            Set<HostAddress> servers = new THashSet(2);
            failedServers = servers;
          }
          failedServers.add(controlAddr);
          if (outTransport != null) {
            outTransport.close();
          }
          continue NEXT_SERVER;
        } catch (Throwable t) {
          throw unexpectedError(t, controlAddr);
        }
      }
      this.controlHost = controlAddr;
      this.controlLocator = new LocatorService.Client(inProtocol, outProtocol);

      if (SanityManager.TraceClientHA) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
            "Established control connection to host " + controlAddr);
      }
      if (SanityManager.TraceClientStatement
          | SanityManager.TraceClientConn) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT(
            "failoverToAvailableHost_E", null, 0, ns, false,
            SanityManager.TraceClientConn ? new Throwable() : null);
      }
      return failedServers;
    }
    throw failoverExhausted(failedServers, failure);
  }

  private void refreshAllHosts(List<HostAddress> allHosts) {
    // refresh the new server list

    // we remove all from the set and re-populate since we would like
    // to prefer the ones coming as "allServers" with "isServer" flag
    // correctly set rather than the ones in "secondary-locators"
    this.controlHostSet.clear();
    this.controlHosts.subList(this.locators.size(), this.controlHosts.size())
        .clear();

    this.controlHostSet.addAll(allHosts);
    this.controlHostSet.addAll(this.controlHosts);

    this.controlHosts.addAll(allHosts);
  }

  private SnappyException unexpectedError(Throwable t, HostAddress host) {
    this.controlHost = null;
    if (this.controlLocator != null) {
      this.controlLocator.getOutputProtocol().getTransport().close();
      this.controlLocator = null;
    }
    return ThriftExceptionUtil.newSnappyException(SQLState.JAVA_EXCEPTION, t,
        host != null ? host.toString() : null, t.getClass(), t.getMessage());
  }

  private SnappyException failoverExhausted(Set<HostAddress> failedServers,
      Throwable cause) {
    return ThriftExceptionUtil.newSnappyException(
        SQLState.DATA_CONTAINER_CLOSED, cause,
        failedServers != null ? failedServers.toString() : null,
        this.locators.get(0), " {failed after trying all available servers: "
            + controlHostSet + (this.locators.size() > 1
            ? ", secondary-locators=" + this.locators.subList(
            1, this.locators.size()) : "")
            + (cause instanceof TException ? " with: "
            + ThriftExceptionUtil.getExceptionString(cause) + '}' : "}"));
  }
}
