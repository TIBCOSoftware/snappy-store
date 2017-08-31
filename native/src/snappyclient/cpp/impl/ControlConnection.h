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

#ifndef CONTROLCONNECTION_H_
#define CONTROLCONNECTION_H_

#include "ClientService.h"

#include <boost/thread/mutex.hpp>

#include "../thrift/LocatorService.h"

namespace std {
  template<>
  struct hash<io::snappydata::thrift::HostAddress> {
    std::size_t operator()(
        const io::snappydata::thrift::HostAddress& addr) const {
      std::size_t h = 37;
      h = 37 * h + addr.port;
      h = 37 * h + std::hash<std::string>()(addr.hostName);
      h = 37 * h + std::hash<std::string>()(addr.ipAddress);
      return h;
    }
  };
}

namespace io {
namespace snappydata {
namespace client {

namespace impl {

  /**
   * Holds locator, server information to use for failover. Also provides
   * convenience methods to actually search for an appropriate host for
   * failover.
   * <p>
   * One distributed system is supposed to have one ControlConnection.
   */
  class ControlConnection {
  private:
    const thrift::ServerType m_snappyServerType;
    //const SSLSocketParameters& m_sslParams;
    const std::set<thrift::ServerType> m_snappyServerTypeSet;
    const std::vector<thrift::HostAddress>& m_locators;
    thrift::HostAddress m_controlHost;
    std::unique_ptr<thrift::LocatorServiceClient> m_controlLocator;
    const std::vector<thrift::HostAddress> m_controlHosts;
    const std::unordered_set<thrift::HostAddress> m_controlHostSet;
    const std::set<std::string>& m_serverGroups;

    const boost::mutex m_lock;

    /**
     * Since one DS is supposed to have one ControlConnection, so we expect the
     * total size of this static global list to be small.
     */
    static const std::vector<ControlConnection> s_allConnections;
    /** Global lock for {@link allConnections} */
    static const boost::mutex s_allConnsLock;

    void failoverToAvailableHost(std::set<thrift::HostAddress>& failedServers,
        const std::exception* failure);

    void refreshAllHosts(const std::vector<thrift::HostAddress>& allHosts);

    const thrift::SnappyException& unexpectedError(
        const std::exception& e, const thrift::HostAddress& host);

    void failoverExhausted(const std::exception& cause,
        thrift::SnappyException& result);

  public:
    ControlConnection(const ClientService& service);

    static const ControlConnection& getOrCreateControlConnection(
        const thrift::HostAddress& hostAddr, const ClientService& service);

    const thrift::HostAddress& getPreferredServer(
        std::set<thrift::HostAddress>& failedServers, bool forFailover);
  };

} /* namespace impl */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* CONTROLCONNECTION_H_ */
