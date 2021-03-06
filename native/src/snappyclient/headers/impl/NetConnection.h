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

/*
 * NetConnection.h
 *
 *  Created on: 15-Jul-2019
 *      Author: pbisen
 */

#ifndef IMPL_NETCONNECTION_H_
#define IMPL_NETCONNECTION_H_

#include <thrift/Thrift.h>

using namespace apache::thrift;

namespace io {
namespace snappydata {
namespace client {
namespace impl {

  enum class FailoverStatus : unsigned char {
    NONE,         /** no failover to be done */
    NEW_SERVER,   /** failover to a new server */
    RETRY         /** retry to the same server */
  };
  class NetConnection {
  private:
    /** set of SQLState strings that denote failover should be done */
    static std::unordered_set<std::string> failoverSQLStateSet;
  public:
    static FailoverStatus getFailoverStatus(const std::string& sqlState,
        const std::exception& snappyEx);
  };

} /* namespace impl */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* IMPL_NETCONNECTION_H_ */
