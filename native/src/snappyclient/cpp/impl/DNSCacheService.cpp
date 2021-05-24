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

// #include <boost/asio/io_service.hpp>
// #include <boost/asio/ip/tcp.hpp>

using namespace io::snappydata;

DNSCacheService DNSCacheService::g_instance;

DNSCacheService::DNSCacheService() {
}

void DNSCacheService::resolve(thrift::HostAddress& addr) {
  if (!addr.__isset.ipAddress) {
    if (addr.hostName.empty()) {
      throw GET_SQLEXCEPTION2(client::SQLStateMessage::CONNECTION_FAILED_MSG,
          addr.toString().c_str(),
          std::runtime_error("no name or IP address available for given host"),
          "DSNCacheService");
    }
    // check if hostname already contains IP address (use inet_pton or getaddrinfo)

    // lookup cache else resolve the host name and cache the results

    // TODO: this can return multiple results and client should ideally
    // try all of them in order that thrift sockets should already be doing
    // but here all of them need to be stored and thrift socket calls
    // need to be made for all of them

    /*
    // [lookup cache]
    boost::asio::io_context context;
    boost::system::error_code ec;
    boost::asio::ip::tcp::resolver resolver(context);
    std::string portStr;
    client::Utils::convertIntToString(addr.port, portStr);
    boost::asio::ip::tcp::resolver::iterator iter = resolver.resolve(
        addr.hostName, portStr,
        boost::asio::ip::tcp::resolver::query::numeric_service, ec);
    if (ec != 0) {
      std::vector<std::string> addresses;
      boost::asio::ip::tcp::resolver::iterator end;
      while (iter != end) {
        std::string ipAddr = iter->address().to_string(ec);
        if (ec != 0) addresses.emplace_back(ipAddr);
        ++iter;
      }
      // [add addresses to cache]
    }
    */
  }
}
