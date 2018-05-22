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

#ifndef CLIENTATTRIBUTE_H_
#define CLIENTATTRIBUTE_H_

#include <unordered_set>
#include <string>

namespace io { namespace snappydata { namespace client {

namespace impl {
  class ClientService;
}

class ClientAttribute {
private:
  // no constructors
  ClientAttribute();
  ClientAttribute(const ClientAttribute&);
  // no assignment
  ClientAttribute operator=(const ClientAttribute&);

  static std::unordered_set<std::string> s_attributes;

  static const char* addToHashSet(const char* k);

  static void staticInitialize();

  friend class impl::ClientService;

public:
  inline static const std::unordered_set<std::string>& getAllAttributes() {
    return s_attributes;
  }

  static const std::string USERNAME;
  static const std::string USERNAME_ALT;
  static const std::string PASSWORD;
  static const std::string READ_TIMEOUT;
  static const std::string KEEPALIVE_IDLE;
  static const std::string KEEPALIVE_INTVL;
  static const std::string KEEPALIVE_CNT;
  static const std::string LOAD_BALANCE;
  static const std::string SECONDARY_LOCATORS;
  static const std::string SERVER_GROUPS;
  static const std::string SINGLE_HOP_ENABLED;
  static const std::string SINGLE_HOP_MAX_CONNECTIONS;
  static const std::string DISABLE_STREAMING;
  static const std::string SKIP_LISTENERS;
  static const std::string SKIP_CONSTRAINT_CHECKS;
  static const std::string TX_SYNC_COMMITS;
  static const std::string DISABLE_THINCLIENT_CANCEL;
  static const std::string DISABLE_TX_BATCHING;
  static const std::string QUERY_HDFS;
  static const std::string LOG_FILE;
  static const std::string LOG_LEVEL;
  static const std::string SECURITY_MECHANISM;
  static const std::string SSL;
  static const std::string SSL_PROPERTIES;
  static const std::string ROUTE_QUERY;
  static const std::string THRIFT_USE_BINARY_PROTOCOL;
  static const std::string THRIFT_USE_FRAMED_TRANSPORT;

  static const int DEFAULT_LOGIN_TIMEOUT = 0;
  static const int DEFAULT_SINGLE_HOP_MAX_CONN_PER_SERVER = 5;
  static const int DEFAULT_KEEPALIVE_IDLE = 20;
  static const int DEFAULT_KEEPALIVE_INTVL = 1;
  static const int DEFAULT_KEEPALIVE_CNT = 10;
};

} } }

#endif /* CLIENTATTRIBUTE_H_ */
