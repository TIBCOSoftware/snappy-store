/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017-2021 TIBCO Software Inc. All rights reserved.
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

#ifndef SNAPPYDATA_STRUCT_HOSTADDRESS_H
#define SNAPPYDATA_STRUCT_HOSTADDRESS_H

#include <functional>

#include "snappydata_struct_Decimal.h"
#include "snappydata_struct_BlobChunk.h"
#include "snappydata_struct_ClobChunk.h"
#include "snappydata_struct_TransactionXid.h"
#include "snappydata_struct_ServiceMetaData.h"
#include "snappydata_struct_ServiceMetaDataArgs.h"
#include "snappydata_struct_OpenConnectionArgs.h"
#include "snappydata_struct_ConnectionProperties.h"

#include "snappydata_types.h"

namespace io { namespace snappydata { namespace thrift {

typedef struct _HostAddress__isset {
  _HostAddress__isset() : ipAddress(false), serverType(false), isCurrent(false) {}
  bool ipAddress :1;
  bool serverType :1;
  bool isCurrent :1;
} _HostAddress__isset;

class HostAddress {
 public:

  HostAddress(const HostAddress&);
  HostAddress(HostAddress&&);
  HostAddress& operator=(const HostAddress&);
  HostAddress& operator=(HostAddress&&);
  HostAddress() : hostName(), port(0), ipAddress(), serverType((ServerType::type)0), isCurrent(0) {
  }

  virtual ~HostAddress() noexcept;
  std::string hostName;
  int32_t port;
  std::string ipAddress;
  ServerType::type serverType;
  bool isCurrent;

  _HostAddress__isset __isset;

  void __set_hostName(const std::string& val);

  void __set_port(const int32_t val);

  void __set_ipAddress(const std::string& val);

  void __set_serverType(const ServerType::type val);

  void __set_isCurrent(const bool val);

  bool operator == (const HostAddress & rhs) const {
    if (port != rhs.port) return false;
    if (hostName == rhs.hostName) {
      return !__isset.ipAddress || !rhs.__isset.ipAddress
        || ipAddress == rhs.ipAddress;
    }
    // hostname of one may be IP address of other
    else if (__isset.ipAddress) {
      return ipAddress == rhs.hostName;
    }
    else if (rhs.__isset.ipAddress) {
      return hostName == rhs.ipAddress;
    }
    return false;
  }
  bool operator != (const HostAddress &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const HostAddress &) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  virtual void printTo(std::ostream &out) const;

  /**
   * Return ipAddress of the host if set, else return the hostName field.
   */
  const std::string& ipAddressOrHostName() const noexcept {
    return __isset.ipAddress ? ipAddress : hostName;
  }

  virtual std::string toString() const;
};

void swap(HostAddress &a, HostAddress &b);

std::ostream& operator<<(std::ostream& out, const HostAddress& obj);

}}} // namespace

namespace std {

template<>
struct hash<io::snappydata::thrift::HostAddress> {
  std::size_t operator()(
      const io::snappydata::thrift::HostAddress& addr) const {
    std::size_t h = 37;
    h = 37 * h + static_cast<size_t>(addr.port);
    // equality of two HostAddresses can involve comparing ipAddress against
    // hostName of other, so just use the port for the hash code
    return h;
  }
};

}

#endif
