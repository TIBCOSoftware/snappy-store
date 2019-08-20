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

#include "ClientService.h"
#include <boost/thread/mutex.hpp>
#include <boost/optional.hpp>
#include "../thrift/LocatorService.h"
//-----------namespaces-----

using namespace apache::thrift;
using namespace apache::thrift::transport;
using namespace apache::thrift::protocol;
using namespace io::snappydata;

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
          /**********Data members********/
          thrift::ServerType::type m_snappyServerType;
          //const SSLSocketParameters& m_sslParams;
          std::set<thrift::ServerType::type> m_snappyServerTypeSet;
          std::vector<thrift::HostAddress> m_locators;
          thrift::HostAddress m_controlHost;
          std::unique_ptr<thrift::LocatorServiceClient> m_controlLocator;
          std::unordered_set<thrift::HostAddress> m_controlHostSet;
          const std::set<std::string>& m_serverGroups;

          boost::mutex m_lock;
          bool m_framedTransport;
          /**
           * Since one DS is supposed to have one ControlConnection, so we expect the
           * total size of this static global list to be small.
           */
          static std::vector<std::unique_ptr<ControlConnection> > s_allConnections;
          /** Global lock for {@link allConnections} */
          static boost::mutex s_allConnsLock;
          /*********Member functions**************/
          ControlConnection() :
              m_serverGroups(std::set<std::string>()) {
          }
          ;
          ControlConnection(ClientService * const &service);

          void failoverToAvailableHost(
              std::set<thrift::HostAddress>& failedServers,
              bool checkFailedControlHosts, const std::exception& failure);

          void refreshAllHosts(
              const std::vector<thrift::HostAddress>& allHosts);

          const thrift::SnappyException unexpectedError(
              const std::exception& e, const thrift::HostAddress& host);

          void failoverExhausted(
              const std::set<thrift::HostAddress>& failedServers,
              const std::exception& failure);

          void getLocatorPreferredServer(thrift::HostAddress& prefHostAddr,
              std::set<thrift::HostAddress>& failedServers,
              std::set<std::string> serverGroups);

          void getPreferredServer(thrift::HostAddress& preferredServer,
              const std::exception& failure, bool forFailover = false);

        public:

          static const boost::optional<ControlConnection&> getOrCreateControlConnection(
              const std::vector<thrift::HostAddress>& hostAddrs,
              ClientService * const &service, const std::exception& failure);

          void getPreferredServer(thrift::HostAddress& preferredServer,
              const std::exception& failure,
              std::set<thrift::HostAddress>& failedServers,
              std::set<std::string>& serverGroups, bool forFailover = false);

          void searchRandomServer(
              const std::set<thrift::HostAddress>& skipServers,
              const std::exception& failure,
              thrift::HostAddress& hostAddress);

          void getConnectedHost(thrift::HostAddress& hostAddr,
              thrift::HostAddress& connectedHost);

          void close(bool clearGlobal);
        };

      } /* namespace impl */
    } /* namespace client */
  } /* namespace snappydata */
} /* namespace io */

#endif /* CONTROLCONNECTION_H_ */
