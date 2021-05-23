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

//--------Headers----------

#include "impl/pch.h"

#include <cstring>

using namespace io::snappydata;
using namespace io::snappydata::client;
using namespace io::snappydata::client::impl;
using namespace io::snappydata::thrift;

static GlobalConnectionHolder& getAllConnections() {
  static GlobalConnectionHolder s_allConnections;
  return s_allConnections;
}

GlobalConnectionHolder::~GlobalConnectionHolder() {
  // this destructor will only be invoked when the app/library is shutting
  // down, so set the TSSLSocketFactory's setInExit flag
  TSSLSocketFactory::setInExit(true);
}

ControlConnection::ControlConnection(ClientService *service) :
    m_snappyServerType(service->getServerType()),
    m_locators(service->getLocators()),
    m_controlHost(service->getCurrentHostAddress()),
    m_serverGroups(service->getServerGrps()),
    m_framedTransport(service->isFrameTransport()) {
  m_snappyServerTypeSet.insert(service->getServerType());
  m_controlHostSet.insert(m_locators.begin(), m_locators.end());
}

void ControlConnection::staticInitialize() {
  // initialize TSSLSocketFactory before GlobalConnectionHolder's static
  // instance so that it's statics are destroyed after the GlobalConnectionHolder
  TSSLSocketFactory::setInExit(false);
  if (!getAllConnections().m_map.empty()) {
    throw std::runtime_error("INITIALIZATION ERROR: "
        "unexpected non-empty global control connection map");
  }
}

ControlConnection& ControlConnection::getOrCreateControlConnection(
    const std::vector<thrift::HostAddress> &hostAddrs, ClientService *service,
    SQLException &failure) {

  // loop through all ControlConnections since size of this global list is
  // expected to be in single digit (total number of distributed systems)

  std::lock_guard<std::mutex> globalGuard(getAllConnections().m_lock);

  auto &allConnections = getAllConnections().m_map;
  const auto existing = allConnections.find(service);
  if (existing != allConnections.end()) {
    return *existing->second;
  }

  for (const auto &kv : allConnections) {
    const std::shared_ptr<ControlConnection> &controlConn = kv.second;

    std::lock_guard<std::recursive_mutex> lockGuard(controlConn->m_lock);
    const std::vector<thrift::HostAddress> &locators = controlConn->m_locators;
    for (const auto &hostAddr : hostAddrs) {
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
              + thrift::to_string(contrConnServerType)
              + " than connection "
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
    thrift::HostAddress preferredServer;
    newControlConn->getPreferredServer(preferredServer, failure, service, true);
    // check again if new control host already exists
    for (const auto &kv : allConnections) {
      const std::shared_ptr<ControlConnection> &controlConn = kv.second;
      std::lock_guard<std::recursive_mutex> lockGuard(controlConn->m_lock);
      const std::vector<thrift::HostAddress> &locators = controlConn->m_locators;
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

void ControlConnection::getLocatorPreferredServer(
    thrift::HostAddress& prefHostAddr,
    std::set<thrift::HostAddress>& failedServers,
    std::set<std::string> serverGroups) {
  m_controlLocator->getPreferredServer(prefHostAddr, m_snappyServerTypeSet,
      serverGroups, failedServers);
}

void ControlConnection::getPreferredServer(thrift::HostAddress &preferredServer,
    SQLException &failure, ClientService *service, bool forFailover) {
  std::set<thrift::HostAddress> failedServers;
  std::set<std::string> serverGroups;
  return getPreferredServer(preferredServer, failure, failedServers,
      serverGroups, service, forFailover);
}

void ControlConnection::getPreferredServer(thrift::HostAddress &preferredServer,
    SQLException &failure, std::set<thrift::HostAddress> &failedServers,
    std::set<std::string> &serverGroups, ClientService *service,
    bool forFailover) {
  if (!m_controlLocator) {
    failoverToAvailableHost(failedServers, false, failure, service);
    forFailover = true;
  }
  std::lock_guard<std::recursive_mutex> localGuard(m_lock);
  bool firstCall = true;
  while (true) {
    try {
      if (forFailover) {
        //refresh the full host list
        std::vector<HostAddress> prefServerAndAllHosts;
        m_controlLocator->getAllServersWithPreferredServer(
            prefServerAndAllHosts, m_snappyServerTypeSet, serverGroups,
            failedServers);
        if (!prefServerAndAllHosts.empty()) {
          preferredServer = prefServerAndAllHosts[0];
          std::vector<HostAddress> allHosts(
              std::make_move_iterator(prefServerAndAllHosts.begin() + 1),
              std::make_move_iterator(prefServerAndAllHosts.end()));
          refreshAllHosts(std::move(allHosts));
        }
      } else {
        getLocatorPreferredServer(preferredServer, failedServers,
            serverGroups);
      }
      if (preferredServer.port <= 0) {
        /*For this case we don't have a locator or locator unable to
         * determine a preferred server, so choose some server randomly
         * as the "preferredServer". In case all servers have failed then
         * the search below will also fail.
         * Remove controlHost from failedServers since it is known to be
         * working at this point (e.g after a reconnect)
         * */
        std::set<thrift::HostAddress> skipServers = failedServers;
        if (!failedServers.empty()
            && std::find(failedServers.begin(), failedServers.end(),
                m_controlHost) != failedServers.end()) {
          //don't change the original failure list since that is proper
          // for the current operation but change for random server search
          skipServers.erase(m_controlHost);
        }
        searchRandomServer(skipServers, failure, preferredServer);
      }
      return;
    } catch (thrift::SnappyException &snEx) {
      FailoverStatus status = NetConnection::getFailoverStatus(
          snEx.exceptionData.sqlState, snEx);
      if (status == FailoverStatus::NONE) {
        throw snEx;
      } else if (status == FailoverStatus::RETRY) {
        forFailover = true;
        continue;
      } else {
        failure = std::move(GET_SQLEXCEPTION(snEx));
      }
    } catch (const TException &tex) {
      // Search for a new host for locator query.
      // For the first call do not mark m_controlHost as failed but retry
      // (e.g. for a reconnect case).
      if (firstCall) {
        firstCall = false;
      } else {
        failedServers.insert(m_controlHost);
      }
      close();
      failure = std::move(GET_SQLEXCEPTION2(SQLStateMessage::CONNECTION_FAILED_MSG,
          m_controlHost.toString().c_str(), tex, "getPreferredServer"));
      failoverToAvailableHost(failedServers, true, failure, service);
    } catch (std::exception &ex) {
      throw unexpectedError(ex, m_controlHost);
    }
    forFailover = true;
  }
}

void ControlConnection::searchRandomServer(
    const std::set<thrift::HostAddress> &skipServers, SQLException &failure,
    thrift::HostAddress &hostAddress) {

  std::vector<thrift::HostAddress> searchServers;
  // Note: Do not use unordered_set -- reason is http://www.cplusplus.com/forum/general/198319/
  searchServers.insert(searchServers.end(), m_controlHostSet.begin(),
      m_controlHostSet.end());
  if (searchServers.size() > 2) {
    // need random iterator here hence the vector above for searchServers
    std::random_shuffle(searchServers.begin(), searchServers.end());
  }
  bool findIt = false;
  for (thrift::HostAddress host : searchServers) {
    if (host.serverType == m_snappyServerType
        && !(!skipServers.empty()
            && std::find(skipServers.begin(), skipServers.end(), host)
                != skipServers.end())) {
      hostAddress = host;
      findIt = true;
      break;
    }
  }
  if (findIt) return;
  failoverExhausted(skipServers, failure);
}

void ControlConnection::failoverToAvailableHost(
    std::set<thrift::HostAddress> &failedServers, bool checkFailedControlHosts,
    SQLException &failure, ClientService *service) {
  std::lock_guard<std::recursive_mutex> lockGuard(m_lock);
  for (auto &controlAddr : m_controlHostSet) {
    if (checkFailedControlHosts && !failedServers.empty()
        && (failedServers.find(controlAddr) != failedServers.end())) {
      continue;
    }
    m_controlLocator = nullptr;

    std::shared_ptr<TTransport> inTransport = nullptr;
    std::shared_ptr<TTransport> outTransport = nullptr;
    std::shared_ptr<TProtocol> inProtocol = nullptr;
    std::shared_ptr<TProtocol> outProtocol = nullptr;

    try {
      while (true) {
        if (outTransport) {
          outTransport->close();
        }
        std::shared_ptr<TTransport> tTransport = nullptr;
        if (m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_BP_SSL
            || m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_CP_SSL
            || m_snappyServerType == thrift::ServerType::THRIFT_SNAPPY_BP_SSL
            || m_snappyServerType
                == thrift::ServerType::THRIFT_SNAPPY_CP_SSL) {
          if (!m_sslFactory) { // m_lock has already been locked before
            m_sslFactory.reset(new SSLSocketFactory(*service->m_sslFactory));
          }
          tTransport = service->createSSLSocket(
              controlAddr.ipAddressOrHostName(), controlAddr.port,
              *m_sslFactory);
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
            || m_snappyServerType
                == thrift::ServerType::THRIFT_SNAPPY_BP_SSL) {
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
    } catch (const TException &tex) {
      failedServers.insert(controlAddr);
      if (outTransport) {
        outTransport->close();
      }
      failure = std::move(GET_SQLEXCEPTION2(SQLStateMessage::CONNECTION_FAILED_MSG,
          controlAddr.toString().c_str(), tex, "failoverToAvailableHost"));
      continue;
    } catch (std::exception &ex) {
      throw unexpectedError(ex, controlAddr);
    }
    m_controlHost = controlAddr;
    m_controlLocator.reset(
        new thrift::LocatorServiceClient(inProtocol, outProtocol));
    return;
  }
  failoverExhausted(failedServers, failure);
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
    std::vector<thrift::HostAddress> &&allHosts) {
  // refresh the locator list first(keep old but push current to front)
  std::vector<thrift::HostAddress>& locators = m_locators;
  std::vector<thrift::HostAddress> newLocators;
  newLocators.reserve(locators.size());

  for (HostAddress &host : allHosts) {
    thrift::ServerType::type sType = host.serverType;
    if (sType == ServerType::THRIFT_LOCATOR_BP
        || sType == ServerType::THRIFT_LOCATOR_BP_SSL
        || sType == ServerType::THRIFT_LOCATOR_CP
        || sType == ServerType::THRIFT_LOCATOR_CP_SSL
        || (std::find(locators.begin(), locators.end(), host)
            != locators.end())) {
      newLocators.push_back(host); // no move here since it is used later
    }
  }
  for (HostAddress &host : locators) {
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
    const std::set<thrift::HostAddress> &failedServers,
    SQLException &failure) {

  std::string failedServerString;
  for (thrift::HostAddress host : failedServers) {
    if (!failedServerString.empty()) failedServerString.append(",");
    failedServerString.append(host.toString());
  }
  thrift::SnappyException snappyEx;
  SnappyExceptionData snappyExData;
  snappyExData.__set_sqlState(failure.getSQLState());
  snappyExData.__set_errorCode(failure.getSeverity());
  std::string reason = "Failed after trying all available servers: {";
  reason.append(failedServerString).append("}");
  // add to own message if original failure is not a null message
  if (!failure.getReason().empty()
      && failure.getReason() != "Unknown exception") {
    reason.append(" Cause: [").append(_SNAPPY_NEWLINE_STR);
    reason.push_back('\t');
    reason.append(failure.getReason()).append(_SNAPPY_NEWLINE_STR);
    reason.push_back(']');
  }
  snappyExData.__set_reason(reason);
  snappyEx.__set_exceptionData(snappyExData);
  snappyEx.__set_serverInfo(failedServerString);
  throw snappyEx;
}

thrift::HostAddress ControlConnection::getConnectedHost(
    const thrift::HostAddress &hostAddr) {
  std::lock_guard<std::recursive_mutex> lockGuard(m_lock);

  auto it = std::find(m_controlHostSet.begin(), m_controlHostSet.end(),
      hostAddr);
  if (it != m_controlHostSet.end()) {
    return *it;
  }

  for (auto &host : m_controlHostSet) {
    if (host.__isset.isCurrent && host.isCurrent) {
      return host;
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

bool ControlConnection::removeService(ClientService *service) {
  std::lock_guard<std::mutex> globalGuard(getAllConnections().m_lock);
  auto &allConnections = getAllConnections().m_map;
  return !allConnections.empty() && allConnections.erase(service) != 0;
}
