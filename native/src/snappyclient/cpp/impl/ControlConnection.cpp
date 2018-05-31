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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

#include "ControlConnection.h"

#include <boost/assign/list_of.hpp>

using namespace apache::thrift;
using namespace io::snappydata::client;

/*
ControlConnection::ControlConnection() {
  // TODO Auto-generated constructor stub

}

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
      SanityManager.DEBUG_PRINT("SW:", "SW: random host " + hostAddr
          + " serverType=" + hostAddr.getServerType() + ", searchType="
          + this.searchServerType + ", failed servers = " + failedServers
          + ", isFailed=" + failedServers.contains(hostAddr));
      if (hostAddr.getServerType() == this.searchServerType
          && !this.failedServers.contains(hostAddr)) {
        this.foundServer = hostAddr;
      }
      return true;
    }
  }

const std::vector<ControlConnection> ControlConnection::s_allConnections(2);
const boost::mutex ControlConnection::s_allConnsLock;

ControlConnection::ControlConnection(const ClientService& service) :
    m_snappyServerType(service.m_reqdServerType), m_snappyServerTypeSet(
        boost::assign::list_of(service.m_reqdServerType)), m_locators(
            service.m_locators), m_controlHost(), m_controlLocator(),
        m_controlHosts(service.m_connHosts), m_controlHostSet(
            service.m_connHosts), m_serverGroups(service.m_serverGroups) {
}

const ControlConnection& ControlConnection::getOrCreateControlConnection(
    const thrift::HostAddress& hostAddr, const ClientService& service) {
  // loop through all ControlConnections since size of this global list is
  // expected to be in single digit (total number of distributed systems)
  boost::lock_guard<boost::mutex> globalGuard(s_allConnsLock);

  size_t index = s_allConnections.size();
  while (--index >= 0) {
    std::unique_ptr<ControlConnection>& controlService = s_allConnections[index];

    boost::lock_guard<boost::mutex> serviceGuard(
        controlService->m_lock);
    if (controlService->m_controlHostSet.find(hostAddr)
        != controlService->m_controlHostSet.end()) {
      return controlService;
    }
  }
  // if we reached here, then need to create a new ControlConnection
  std::unique_ptr<ControlConnection> controlService = new ControlConnection(
      service);
  std::set<thrift::HostAddress> emptyServers;
  controlService->getPreferredServer(emptyServers, true);
  s_allConnections.push_back(controlService);
  return controlService;
}

const thrift::HostAddress& ControlConnection::getPreferredServer(
    std::set<thrift::HostAddress>& failedServers,
    bool forFailover)
{
    if (m_controlLocator.get() == NULL) {
      failoverToAvailableHost(failedServers, NULL);
      forFailover = true;
    }

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
                  this.serverGroups, failedServers);
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
        }
        else {
          if (SanityManager.TraceClientHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "getPreferredServer() trying using host " + this.controlHost);
          }
          if (SanityManager.TraceClientStatement) {
            final long ns = System.nanoTime();
            SanityManager.DEBUG_PRINT_COMPACT("getPreferredServer_S", null,
                0, ns, true, null);
          }

          preferredServer = controlLocator.getPreferredServer(
              this.snappyServerTypeSet, this.serverGroups, failedServers);

          if (SanityManager.TraceClientStatement) {
            final long ns = System.nanoTime();
            SanityManager.DEBUG_PRINT_COMPACT("getPreferredServer_E", null,
                0, ns, false, null);
          }
        }

        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "Got preferred server " + preferredServer
                  + " using control connection to " + this.controlHost
                  + (preferredServer.getPort() <= 0 ? "" : " (trying random "
                      + "server since no preferred server received)"));
        }
        if (preferredServer.getPort() <= 0) {
          // for this case we don't have a locator so choose some server
          // randomly as the "preferredServer"
          SearchRandomServer search = new SearchRandomServer(this.snappyServerType,
              this.controlHostSet.size(), failedServers);
          this.controlHostSet.forEach(search);
          if ((preferredServer = search.getRandomServer()) == null) {
            throw failoverExhausted(null);
          }
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
              .getSeverity(), se)).isNone()) {
            throw se;
          }
          else if (status == NetConnection.FailoverStatus.RETRY) {
            forFailover = true;
            continue;
          }
        }
        // search for a new host for locator query
        if (failedServers == null) {
          Set<HostAddress> servers = new THashSet(2);
          failedServers = servers;
        }
        failedServers.add(controlHost);
        controlLocator.getOutputProtocol().getTransport().close();
        failedServers = failoverToAvailableHost(failedServers, te);
      } catch (Throwable t) {
        throw unexpectedError(t, controlHost);
      }
      forFailover = true;
    }
  }

void ControlConnection::failoverToAvailableHost(
    std::set<thrift::HostAddress>& failedServers,
    const std::exception* failure)
{
  NEXT_SERVER: for (std::vector<thrift::HostAddress>::const_iterator iter =
      m_controlHosts.begin(); iter != m_controlHosts.end(); ++iter) {
    const thrift::HostAddress& controlAddr = *iter;
    if (!failedServers.empty() && failedServers.find(controlAddr) != failedServers.end()) {
        continue;
      }
    m_controlHost.hostName.clear();
    m_controlLocator.reset(NULL);

    boost::shared_ptr<transport::TSocket> transport;
    boost::shared_ptr<protocol::TProtocol> protocol;
      while (true) {
        try {
          if (transport.get() != NULL) {
            transport->close();
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

          // using socket with TCompactProtocol to match the server
          // settings; this could become configurable in future
          if (m_snappyServerType.isThriftSSL()) {
            transport = new SnappyTSSLSocket(controlAddr, this.sslParams);
          }
          else {
            transport = new SnappyTSocket(controlAddr);
          }
          if (this.snappyServerType.isThriftBinaryProtocol()) {
            protocol = new TBinaryProtocol(transport);
          }
          else {
            protocol = new TCompactProtocol(transport);
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
          if (transport != null) {
            transport.close();
          }
          continue NEXT_SERVER;
        } catch (Throwable t) {
          throw unexpectedError(t, controlAddr);
        }
      }
      this.controlHost = controlAddr;
      this.controlLocator = new LocatorService.Client(protocol);

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
    throw failoverExhausted(failure);
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
    return ClientExceptionUtil.newSnappyException(SQLState.JAVA_EXCEPTION, t,
        t.getClass(), t.getMessage() + " (Server=" + host + ')');
  }

  private SnappyException failoverExhausted(Throwable cause) {
    return ClientExceptionUtil.newSnappyException(
        SQLState.DATA_CONTAINER_CLOSED, cause, this.locators.get(0),
            " {failed after trying all available servers: " + controlHostSet
            + (this.locators.size() > 1 ? ", secondary-locators="
                + this.locators.subList(1, this.locators.size()) : "")
                + (cause instanceof TException ? " with: " + ThriftExceptionUtil
                    .getExceptionString(cause) + '}' : "}"));
  }
}
*/
