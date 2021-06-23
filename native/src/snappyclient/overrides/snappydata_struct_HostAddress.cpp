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

#include "impl/pch.h"

#include <iosfwd>

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include "snappydata_struct_HostAddress.h"
#include "Utils.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


HostAddress::~HostAddress() noexcept {
}


void HostAddress::__set_hostName(const std::string& val) {
  this->hostName = val;
}

void HostAddress::__set_port(const int32_t val) {
  this->port = val;
}

void HostAddress::__set_ipAddress(const std::string& val) {
  this->ipAddress = val;
__isset.ipAddress = true;
}

void HostAddress::__set_serverType(const ServerType::type val) {
  this->serverType = val;
__isset.serverType = true;
}

void HostAddress::__set_isCurrent(const bool val) {
  this->isCurrent = val;
__isset.isCurrent = true;
}
std::ostream& operator<<(std::ostream& out, const HostAddress& obj)
{
  obj.printTo(out);
  return out;
}


uint32_t HostAddress::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_hostName = false;
  bool isset_port = false;

  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->hostName);
          isset_hostName = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          xfer += iprot->readI32(this->port);
          isset_port = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->ipAddress);
          this->__isset.ipAddress = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          int32_t ecast128;
          xfer += iprot->readI32(ecast128);
          this->serverType = (ServerType::type)ecast128;
          this->__isset.serverType = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 5:
        if (ftype == ::apache::thrift::protocol::T_BOOL) {
          xfer += iprot->readBool(this->isCurrent);
          this->__isset.isCurrent = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      default:
        xfer += iprot->skip(ftype);
        break;
    }
    xfer += iprot->readFieldEnd();
  }

  xfer += iprot->readStructEnd();

  if (!isset_hostName)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_port)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t HostAddress::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("HostAddress");

  xfer += oprot->writeFieldBegin("hostName", ::apache::thrift::protocol::T_STRING, 1);
  xfer += oprot->writeString(this->hostName);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("port", ::apache::thrift::protocol::T_I32, 2);
  xfer += oprot->writeI32(this->port);
  xfer += oprot->writeFieldEnd();

  if (this->__isset.ipAddress) {
    xfer += oprot->writeFieldBegin("ipAddress", ::apache::thrift::protocol::T_STRING, 3);
    xfer += oprot->writeString(this->ipAddress);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.serverType) {
    xfer += oprot->writeFieldBegin("serverType", ::apache::thrift::protocol::T_I32, 4);
    xfer += oprot->writeI32((int32_t)this->serverType);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.isCurrent) {
    xfer += oprot->writeFieldBegin("isCurrent", ::apache::thrift::protocol::T_BOOL, 5);
    xfer += oprot->writeBool(this->isCurrent);
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(HostAddress &a, HostAddress &b) {
  using ::std::swap;
  swap(a.hostName, b.hostName);
  swap(a.port, b.port);
  swap(a.ipAddress, b.ipAddress);
  swap(a.serverType, b.serverType);
  swap(a.isCurrent, b.isCurrent);
  swap(a.__isset, b.__isset);
}

HostAddress::HostAddress(const HostAddress& other129) {
  hostName = other129.hostName;
  port = other129.port;
  ipAddress = other129.ipAddress;
  serverType = other129.serverType;
  isCurrent = other129.isCurrent;
  __isset = other129.__isset;
}
HostAddress::HostAddress( HostAddress&& other130) {
  hostName = std::move(other130.hostName);
  port = std::move(other130.port);
  ipAddress = std::move(other130.ipAddress);
  serverType = std::move(other130.serverType);
  isCurrent = std::move(other130.isCurrent);
  __isset = std::move(other130.__isset);
}
HostAddress& HostAddress::operator=(const HostAddress& other131) {
  hostName = other131.hostName;
  port = other131.port;
  ipAddress = other131.ipAddress;
  serverType = other131.serverType;
  isCurrent = other131.isCurrent;
  __isset = other131.__isset;
  return *this;
}
HostAddress& HostAddress::operator=(HostAddress&& other132) {
  hostName = std::move(other132.hostName);
  port = std::move(other132.port);
  ipAddress = std::move(other132.ipAddress);
  serverType = std::move(other132.serverType);
  isCurrent = std::move(other132.isCurrent);
  __isset = std::move(other132.__isset);
  return *this;
}
void HostAddress::printTo(std::ostream &out) const {
  bool addServerType = true;
  if (!__isset.serverType || client::Utils::isServerTypeDefault(serverType)) {
    addServerType = false;
  }
  out << hostName;
  if (__isset.ipAddress) {
    out << '/' << ipAddress;
  }
  out << '[' << port << ']';
  if (addServerType) {
    out << '{' << serverType << '}';
  }
}
std::string HostAddress::toString() const {
  std::ostringstream out;
  printTo(out);
  return out.str();
}

}}} // namespace
