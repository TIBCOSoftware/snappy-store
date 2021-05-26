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

#ifndef CONTROLCONNECTION_H_
#define CONTROLCONNECTION_H_

#include <exception>
#include <mutex>
#include <unordered_map>
#include <unordered_set>

#include "impl/ClientService.h"
#include "impl/LocatorService.h"

//-----------namespaces-----

using namespace apache::thrift;
using namespace apache::thrift::transport;
using namespace apache::thrift::protocol;

namespace io {
namespace snappydata {
namespace client {
namespace impl {

class ControlConnection;

/**
 * Holds a global map of ClientService to ControlConnection mappings.
 * Added to allow its (global) destructor call TSSLSocket::setInExit().
 */
class GlobalConnectionHolder final {
private:
  /**
   * Map of each ClientService to its corresponding ControlConnection.
   * This uses a reference-counted shared_ptr, so when all references to
   * a ControlConnection are removed, then the ControlConnection will
   * automatically get destroyed.
   */
  std::unordered_map<ClientService*, std::shared_ptr<ControlConnection> > m_map;
  /**
   * Lock used to access/update the map.
   */
  std::mutex m_lock;

  friend class ControlConnection;

public:
  ~GlobalConnectionHolder();
};

/**
 * Holds locator, server information to use for failover. Also provides
 * convenience methods to actually search for an appropriate host for
 * failover.
 * <p>
 * One distributed system is supposed to have one ControlConnection.
 */
class ControlConnection final {
private:
  /**********Data members********/
  thrift::ServerType::type m_snappyServerType;
  std::set<thrift::ServerType::type> m_snappyServerTypeSet;
  std::vector<thrift::HostAddress> m_locators;
  thrift::HostAddress m_controlHost;
  std::unique_ptr<thrift::LocatorServiceClient> m_controlLocator;
  // THRIFT-4164: The factory MUST outlive any sockets it creates
  // hence a reference is maintained here
  std::unique_ptr<SSLSocketFactory> m_sslFactory;
  std::unordered_set<thrift::HostAddress> m_controlHostSet;
  std::set<std::string> m_serverGroups;
  std::recursive_mutex m_lock;
  bool m_framedTransport;

  /*********Member functions**************/
  ControlConnection(ClientService* service);

  void failoverToAvailableHost(std::set<thrift::HostAddress>& failedServers,
      bool checkFailedControlHosts, SQLException& failure,
      ClientService* service);

  void refreshAllHosts(std::vector<thrift::HostAddress>&& allHosts);

  const thrift::SnappyException unexpectedError(const std::exception& e,
      const thrift::HostAddress& host);

  void failoverExhausted(const std::set<thrift::HostAddress>& failedServers,
      SQLException& failure);

  void getLocatorPreferredServer(thrift::HostAddress& prefHostAddr,
      std::set<thrift::HostAddress>& failedServers,
      std::set<std::string> serverGroups);

  void getPreferredServer(thrift::HostAddress& preferredServer,
      SQLException& failure, ClientService* service, bool forFailover = false);

public:
  static void staticInitialize();

  static ControlConnection& getOrCreateControlConnection(
      const std::vector<thrift::HostAddress>& hostAddrs, ClientService* service,
      SQLException& failure);

  void getPreferredServer(thrift::HostAddress& preferredServer,
      SQLException& failure, std::set<thrift::HostAddress>& failedServers,
      std::set<std::string>& serverGroups, ClientService* service,
      bool forFailover = false);

  void searchRandomServer(const std::set<thrift::HostAddress>& skipServers,
      SQLException& failure, thrift::HostAddress& hostAddress);

  /**
   * Get the server-returned HostAddress for the given connected
   * user-supplied HostAddress. The optional parameter "allowCurrent"
   * will allow returning the HostAddress marked as "current" if none
   * of the server-returned hosts match (e.g. because user-supplied
   * hostName while server returned IP addresses or vice-versa).
   *
   * CAUTION: the parameter "allowCurrent" should be passed as true
   * only for the ControlConnection explicitly created up for the
   * user-supplied HostAddress using getOrCreateControlConnection.
   */
  thrift::HostAddress getConnectedHost(const thrift::HostAddress& hostAddr,
      bool allowCurrent);

  void close();

  ~ControlConnection();

  /**
   * Remove ClientService from global map and destroy the corresponding
   * ControlConnection if no other service uses it.
   */
  static bool removeService(ClientService* service);
};

} /* namespace impl */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* CONTROLCONNECTION_H_ */
