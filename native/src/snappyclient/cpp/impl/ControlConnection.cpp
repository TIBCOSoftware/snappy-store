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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

#include "impl/pch.h"

#include "impl/ControlConnection.h"
#include "impl/ClientService.h"

using namespace io::snappydata;
using namespace io::snappydata::client;
using namespace io::snappydata::client::impl;
using namespace io::snappydata::thrift;

static GlobalConnectionHolder& getAllConnections() {
  static GlobalConnectionHolder s_allConnections;
  return s_allConnections;
}

GlobalConnectionHolder::~GlobalConnectionHolder() {
  // this destructor will only be invoked when the app/library is shutting down,
  // so cleanup OpenSSL that will also skip any further SSL_shutdown attempts

  // Note: below is now handled directly in thrift build where SSL connection
  // close checks if OpenSSL library is initialized or not, hence this flag
  // is obsolete and has been removed from the builds
  // TSSLSocketFactory::setInExit(true);
  apache::thrift::transport::cleanupOpenSSL();
}

ControlConnection::ControlConnection(ClientService* service) :
    m_snappyServerType(service->getServerType()),
        m_locators(service->getLocators()),
        m_controlHost(service->getCurrentHostAddress()),
        m_serverGroups(service->getServerGroups()),
        m_framedTransport(service->isFrameTransport()) {
  m_snappyServerTypeSet.insert(service->getServerType());
  m_controlHostSet.insert(m_locators.begin(), m_locators.end());
}

void ControlConnection::staticInitialize() {
  // initialize TSSLSocketFactory before GlobalConnectionHolder's static
  // instance so that it's statics are destroyed after the GlobalConnectionHolder

  // Note: below is now handled directly in thrift build where SSL connection
  // close checks if OpenSSL library is initialized or not, hence this flag
  // is obsolete and has been removed from the builds
  // TSSLSocketFactory::setInExit(false);
  if (!getAllConnections().m_map.empty()) {
    throw std::runtime_error("INITIALIZATION ERROR: "
        "unexpected non-empty global control connection map");
  }
}

ControlConnection& ControlConnection::getOrCreateControlConnection(
    const std::vector<thrift::HostAddress>& hostAddrs, ClientService* service,
    SQLException& failure) {

  // loop through all ControlConnections since size of this global list is
  // expected to be in single digit (total number of distributed systems)

  std::lock_guard<std::mutex> globalGuard(getAllConnections().m_lock);

  auto& allConnections = getAllConnections().m_map;
  const auto existing = allConnections.find(service);
  if (existing != allConnections.end()) {
    return *existing->second;
  }

  for (const auto& kv : allConnections) {
    const std::shared_ptr<ControlConnection>& controlConn = kv.second;

    std::lock_guard<std::recursive_mutex> lockGuard(controlConn->m_lock);
    const std::vector<thrift::HostAddress>& locators = controlConn->m_locators;
    for (const auto& hostAddr : hostAddrs) {
      auto result = std::find(locators.begin(), locators.end(), hostAddr);

      if (result == locators.end()) {
        continue;
      } else {
        auto serviceServerType = service->getServerType();
        auto contrConnServerType = controlConn->m_snappyServerType;
        if (contrConnServerType == serviceServerType) {
          allConnections.emplace(service, controlConn);
          return *controlConn;
        } else {
          thrift::SnappyException ex;
          std::string msg = hostAddr.toString()
              + " as registered but having different type "
              + thrift::to_string(contrConnServerType) + " than connection "
              + thrift::to_string(serviceServerType);
          SnappyExceptionData snappyExData;
          snappyExData.__set_sqlState("08006");
          snappyExData.__set_reason(msg);
          // errorCode matches product ExceptionSeverity.SESSION_SEVERITY
          snappyExData.__set_errorCode(40000);
          ex.__set_exceptionData(snappyExData);
          ex.__set_serverInfo(hostAddr.toString());
          throw ex;
        }
      }
    }
  }

  // if we reached here, then need to create a new ControlConnection
  std::shared_ptr<ControlConnection> newControlConn(
      new ControlConnection(service));
  {
    std::set<thrift::HostAddress> failedServers;
    auto preferredServer = newControlConn->getPreferredServer(failure,
        failedServers, service, true);
    // check again if new control host already exists
    for (const auto& kv : allConnections) {
      const std::shared_ptr<ControlConnection>& controlConn = kv.second;
      std::lock_guard<std::recursive_mutex> lockGuard(controlConn->m_lock);
      const std::vector<thrift::HostAddress>& locators = controlConn->m_locators;
      auto result = std::find(locators.begin(), locators.end(),
          preferredServer);
      if (result != locators.end()) {
        allConnections.emplace(service, controlConn);
        return *controlConn;
      }
    }
  }
  allConnections.emplace(service, newControlConn);
  return *allConnections[service];
}

thrift::HostAddress ControlConnection::getPreferredServer(SQLException& failure,
    std::set<thrift::HostAddress>& failedServers, ClientService* service,
    bool updateHostList) {
  thrift::HostAddress preferredServer;
  if (!m_controlLocator) {
    failoverToAvailableHost(failedServers, false, failure, service);
    updateHostList = true;
  }
  std::lock_guard<std::recursive_mutex> localGuard(m_lock);
  bool firstCall = true;
  while (true) {
    try {
      if (updateHostList) {
        // refresh the full host list
        std::vector<HostAddress> prefServerAndAllHosts;
        m_controlLocator->getAllServersWithPreferredServer(
            prefServerAndAllHosts, m_snappyServerTypeSet,
            service->getServerGroups(), failedServers);
        if (!prefServerAndAllHosts.empty()) {
          preferredServer = prefServerAndAllHosts[0];
          std::vector<HostAddress> allHosts(
              std::make_move_iterator(prefServerAndAllHosts.begin() + 1),
              std::make_move_iterator(prefServerAndAllHosts.end()));
          refreshAllHosts(std::move(allHosts));
        }
      } else {
        m_controlLocator->getPreferredServer(preferredServer,
            m_snappyServerTypeSet, service->getServerGroups(), failedServers);
      }
      if (preferredServer.port <= 0) {
        /*
         * For this case we don't have a locator or locator able to
         * determine a preferred server, so choose some server randomly
         * as the "preferredServer". In case all servers have failed then
         * the search below will also fail.
         * Remove controlHost from failedServers since it is known to be
         * working at this point (e.g after a reconnect)
         */
        auto skipServers = failedServers;
        if (!skipServers.empty()) {
          // don't change the original failure list since that is proper
          // for the current operation but change for random server search
          skipServers.erase(m_controlHost);
        }
        preferredServer = searchRandomServer(skipServers, failure, service);
      }
      return preferredServer;
    } catch (thrift::SnappyException& snEx) {
      FailoverStatus status = NetConnection::getFailoverStatus(
          snEx.exceptionData.sqlState, snEx);
      if (status == FailoverStatus::NONE) {
        throw snEx;
      } else if (status == FailoverStatus::RETRY) {
        updateHostList = true;
        continue;
      } else {
        failure = std::move(GET_SQLEXCEPTION(snEx));
      }
    } catch (const TException& tex) {
      // Search for a new host for locator query.
      // For the first call do not mark m_controlHost as failed but retry
      // (e.g. for a reconnect case).
      if (firstCall) {
        firstCall = false;
      } else {
        failedServers.insert(m_controlHost);
      }
      close();
      failure = std::move(
          GET_SQLEXCEPTION2(SQLStateMessage::CONNECTION_FAILED_MSG,
              m_controlHost.toString().c_str(), tex, "getPreferredServer"));
      failoverToAvailableHost(failedServers, true, failure, service);
    } catch (std::exception& ex) {
      throw unexpectedError(ex, m_controlHost);
    }
    updateHostList = true;
  }
}

thrift::HostAddress ControlConnection::searchRandomServer(
    const std::set<thrift::HostAddress>& skipServers, SQLException& failure,
    ClientService* service) {

  std::vector<thrift::HostAddress> searchServers;
  // Note: use vector and not unordered_set for proper random_shuffle behaviour
  searchServers.insert(searchServers.end(), m_controlHostSet.begin(),
      m_controlHostSet.end());
  if (searchServers.size() > 2) {
    // need random iterator here hence the vector above for searchServers
    std::random_shuffle(searchServers.begin(), searchServers.end());
  }
  for (thrift::HostAddress host : searchServers) {
    if (host.serverType == m_snappyServerType
        && (skipServers.empty() || skipServers.find(host) == skipServers.end())) {
      return host;
    }
  }
  failoverExhausted(skipServers, failure, service);
}

void ControlConnection::failoverToAvailableHost(
    std::set<thrift::HostAddress>& failedServers, bool checkFailedControlHosts,
    SQLException& failure, ClientService* service) {
  std::lock_guard<std::recursive_mutex> lockGuard(m_lock);
  for (auto& controlAddr : m_controlHostSet) {
    if (checkFailedControlHosts && !failedServers.empty()
        && (failedServers.find(controlAddr) != failedServers.end())) {
      continue;
    }
    m_controlLocator = nullptr;

    std::shared_ptr<TTransport> inTransport = nullptr;
    std::shared_ptr<TTransport> outTransport = nullptr;
    std::shared_ptr<TProtocol> inProtocol = nullptr;
    std::shared_ptr<TProtocol> outProtocol = nullptr;

    // resolve the controlAddr using DNSCacheService to minimize DNS lookup
    // from hostname (when hostnames are being used)
    // it is also required in case hostname lookups are not working from
    // client-side and only IP addresses provided by servers are supposed
    // to work

    // not really modifying the key but use DNSCacheService to fill up IP address
    // DNSCacheService::instance().resolve(controlAddr);

    try {
      while (true) {
        if (outTransport) {
          outTransport->close();
        }
        std::shared_ptr<TTransport> tTransport = nullptr;
        if (m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_BP_SSL
            || m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_CP_SSL
            || m_snappyServerType == thrift::ServerType::THRIFT_SNAPPY_BP_SSL
            || m_snappyServerType == thrift::ServerType::THRIFT_SNAPPY_CP_SSL) {
          if (!m_sslFactory) { // m_lock has already been locked before
            m_sslFactory.reset(new SSLSocketFactory(*service->m_sslFactory));
          }
          tTransport = service->createSSLSocket(controlAddr.hostName,
              controlAddr.port, *m_sslFactory);
        } else if (m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_BP
            || m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_CP
            || m_snappyServerType == thrift::ServerType::THRIFT_SNAPPY_BP
            || m_snappyServerType == thrift::ServerType::THRIFT_SNAPPY_CP) {
          tTransport = std::make_shared<TSocket>(
              controlAddr.ipAddressOrHostName(), controlAddr.port);
        }
        tTransport->open();
        TTransportFactory* transportFactory = nullptr;
        if (m_framedTransport) {
          transportFactory = new TFramedTransportFactory();
        } else {
          transportFactory = new TTransportFactory();
        }
        inTransport = transportFactory->getTransport(tTransport);
        outTransport = transportFactory->getTransport(tTransport);
        delete transportFactory;
        transportFactory = 0;

        TProtocolFactory* protocolFactory = nullptr;
        if (m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_BP
            || m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_BP_SSL
            || m_snappyServerType == thrift::ServerType::THRIFT_SNAPPY_BP
            || m_snappyServerType == thrift::ServerType::THRIFT_SNAPPY_BP_SSL) {
          protocolFactory = new TBinaryProtocolFactory();

        } else {
          protocolFactory = new TCompactProtocolFactory();
        }
        inProtocol = protocolFactory->getProtocol(inTransport);
        outProtocol = protocolFactory->getProtocol(outTransport);

        delete protocolFactory;
        protocolFactory = 0;
        break;
      }
    } catch (const TException& tex) {
      failedServers.insert(controlAddr);
      if (outTransport) {
        outTransport->close();
      }
      failure = std::move(
          GET_SQLEXCEPTION2(SQLStateMessage::CONNECTION_FAILED_MSG,
              controlAddr.toString().c_str(), tex, "failover"));
      continue;
    } catch (std::exception& ex) {
      throw unexpectedError(ex, controlAddr);
    }
    m_controlHost = controlAddr;
    m_controlLocator.reset(
        new thrift::LocatorServiceClient(inProtocol, outProtocol));
    return;
  }
  failoverExhausted(failedServers, failure, service);
}

const thrift::SnappyException ControlConnection::unexpectedError(
    const std::exception& ex, const thrift::HostAddress& host) {
  close();
  thrift::SnappyException snappyEx;
  SnappyExceptionData snappyExData;
  snappyExData.__set_sqlState(
      std::string(SQLState::UNKNOWN_EXCEPTION.getSQLState()));
  snappyExData.__set_reason(ex.what());
  snappyEx.__set_exceptionData(snappyExData);
  snappyEx.__set_serverInfo(host.toString());
  return snappyEx;
}

void ControlConnection::refreshAllHosts(
    std::vector<thrift::HostAddress>&& allHosts) {
  // refresh the locator list first(keep old but push current to front)
  std::vector<thrift::HostAddress>& locators = m_locators;
  std::vector<thrift::HostAddress> newLocators;
  newLocators.reserve(locators.size());

  for (HostAddress& host : allHosts) {
    thrift::ServerType::type sType = host.serverType;
    if (sType == ServerType::THRIFT_LOCATOR_BP
        || sType == ServerType::THRIFT_LOCATOR_BP_SSL
        || sType == ServerType::THRIFT_LOCATOR_CP
        || sType == ServerType::THRIFT_LOCATOR_CP_SSL
        || (std::find(locators.begin(), locators.end(), host) != locators.end())) {
      newLocators.push_back(host); // no move here since it is used later
    }
  }
  for (HostAddress& host : locators) {
    if (!(std::find(newLocators.begin(), newLocators.end(), host)
        != newLocators.end())) {
      newLocators.push_back(std::move(host));
    }
  }

  m_locators = newLocators;
  // refresh the new server list

  // we remove all from the set and re-populate since we would like
  // to prefer the ones coming as "allServers" with "isServer" flag
  // correctly set rather than the ones in "secondary-locators"
  m_controlHostSet.clear();
  // allHosts is inserted first since it is the more "authoritative" list
  // having proper serverTypes etc
  m_controlHostSet.insert(std::make_move_iterator(allHosts.begin()),
      std::make_move_iterator(allHosts.end()));
  m_controlHostSet.insert(std::make_move_iterator(newLocators.begin()),
      std::make_move_iterator(newLocators.end()));
}

void ControlConnection::failoverExhausted(
    const std::set<thrift::HostAddress>& failedServers, SQLException& failure,
    ClientService* service) {
  std::string reason;
  if (failure.getReason().empty()) {
    if (service && service->m_isolationLevel != IsolationLevel::NONE) {
      failure.setSQLState(SQLState::DATA_CONTAINER_CLOSED);
    } else {
      failure.setSQLState(SQLState::CONNECTION_FAILED);
    }
  } else {
    // append to original failure reason if it is not a null message
    reason = failure.getReason();
    reason.append(LogWriter::NEWLINE).push_back('\t');
  }
  reason.append("Tried available servers: {");
  int index = 0;
  for (const auto& host : failedServers) {
    if (index > 0) reason.push_back(',');
    reason.append(host.toString());
    index++;
  }
  reason.push_back('}');
  failure.setReason(reason);
  throw failure;
}

thrift::HostAddress ControlConnection::getConnectedHost(
    const thrift::HostAddress& hostAddr, bool allowCurrent) {
  std::lock_guard<std::recursive_mutex> lockGuard(m_lock);

  auto result = m_controlHostSet.find(hostAddr);
  if (result != m_controlHostSet.end()
      && static_cast<int>(result->serverType) != 0) {
    return *result;
  }
  // below is for cases where user-supplied a hostName in hostAddr but the
  // server returned IP addresses in the hostName field, so just pick up
  // the server marked as "current" with minimal port equality checking
  if (allowCurrent) {
    for (const auto& host : m_controlHostSet) {
      if (host.__isset.isCurrent && host.isCurrent && hostAddr.__isset.isCurrent
          && hostAddr.isCurrent && static_cast<int>(host.serverType) != 0
          && host.port == hostAddr.port) {
        return host;
      }
    }
  }
  return hostAddr;
}

void ControlConnection::close() {
  std::lock_guard<std::recursive_mutex> lockGuard(m_lock);

  m_controlHost = thrift::HostAddress();
  if (m_controlLocator) {
    m_controlLocator->getOutputProtocol()->getTransport()->close();
    m_controlLocator = nullptr;
  }
}

ControlConnection::~ControlConnection() {
  // destructor should *never* throw an exception
  Utils::handleExceptionsInDestructor("control connection", [&]() {
    close();
  });
}

bool ControlConnection::removeService(ClientService* service) {
  std::lock_guard<std::mutex> globalGuard(getAllConnections().m_lock);
  auto& allConnections = getAllConnections().m_map;
  return !allConnections.empty() && allConnections.erase(service) != 0;
}
