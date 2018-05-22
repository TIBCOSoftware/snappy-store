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

#ifndef DNSCACHESERVICE_H_
#define DNSCACHESERVICE_H_

#include "snappydata_struct_HostAddress.h"

namespace io {
namespace snappydata {

  /**
   * A simple DNS caching service purged as per TTL or fixed interval
   * if no DNS TTL is found in the record. Also allows setting an upper
   * limit on the size of the DNS cache.
   */
  class DNSCacheService {
  private:
    DNSCacheService();

    static DNSCacheService g_instance;

  public:

    static DNSCacheService& instance() {
      return g_instance;
    }

    void resolve(thrift::HostAddress& hostAddr) const;
  };

} /* namespace snappydata */
} /* namespace io */

#endif /* DNSCACHESERVICE_H_ */
