/**
 * Autogenerated by Thrift Compiler (0.12.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#include <iosfwd>

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include <thrift/stdcxx.h>
#include "snappydata_struct_ConnectionProperties.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


ConnectionProperties::~ConnectionProperties() noexcept {
}


void ConnectionProperties::__set_connId(const int64_t val) {
  this->connId = val;
}

void ConnectionProperties::__set_clientHostName(const std::string& val) {
  this->clientHostName = val;
}

void ConnectionProperties::__set_clientID(const std::string& val) {
  this->clientID = val;
}

void ConnectionProperties::__set_userName(const std::string& val) {
  this->userName = val;
__isset.userName = true;
}

void ConnectionProperties::__set_token(const std::string& val) {
  this->token = val;
__isset.token = true;
}

void ConnectionProperties::__set_defaultSchema(const std::string& val) {
  this->defaultSchema = val;
__isset.defaultSchema = true;
}
std::ostream& operator<<(std::ostream& out, const ConnectionProperties& obj)
{
  obj.printTo(out);
  return out;
}


uint32_t ConnectionProperties::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_connId = false;
  bool isset_clientHostName = false;
  bool isset_clientID = false;

  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_I64) {
          xfer += iprot->readI64(this->connId);
          isset_connId = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->clientHostName);
          isset_clientHostName = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->clientID);
          isset_clientID = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->userName);
          this->__isset.userName = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 5:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readBinary(this->token);
          this->__isset.token = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 6:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->defaultSchema);
          this->__isset.defaultSchema = true;
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

  if (!isset_connId)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_clientHostName)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_clientID)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t ConnectionProperties::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("ConnectionProperties");

  xfer += oprot->writeFieldBegin("connId", ::apache::thrift::protocol::T_I64, 1);
  xfer += oprot->writeI64(this->connId);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("clientHostName", ::apache::thrift::protocol::T_STRING, 2);
  xfer += oprot->writeString(this->clientHostName);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("clientID", ::apache::thrift::protocol::T_STRING, 3);
  xfer += oprot->writeString(this->clientID);
  xfer += oprot->writeFieldEnd();

  if (this->__isset.userName) {
    xfer += oprot->writeFieldBegin("userName", ::apache::thrift::protocol::T_STRING, 4);
    xfer += oprot->writeString(this->userName);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.token) {
    xfer += oprot->writeFieldBegin("token", ::apache::thrift::protocol::T_STRING, 5);
    xfer += oprot->writeBinary(this->token);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.defaultSchema) {
    xfer += oprot->writeFieldBegin("defaultSchema", ::apache::thrift::protocol::T_STRING, 6);
    xfer += oprot->writeString(this->defaultSchema);
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(ConnectionProperties &a, ConnectionProperties &b) {
  using ::std::swap;
  swap(a.connId, b.connId);
  swap(a.clientHostName, b.clientHostName);
  swap(a.clientID, b.clientID);
  swap(a.userName, b.userName);
  swap(a.token, b.token);
  swap(a.defaultSchema, b.defaultSchema);
  swap(a.__isset, b.__isset);
}

ConnectionProperties::ConnectionProperties(const ConnectionProperties& other124) {
  connId = other124.connId;
  clientHostName = other124.clientHostName;
  clientID = other124.clientID;
  userName = other124.userName;
  token = other124.token;
  defaultSchema = other124.defaultSchema;
  __isset = other124.__isset;
}
ConnectionProperties::ConnectionProperties( ConnectionProperties&& other125) noexcept {
  connId = std::move(other125.connId);
  clientHostName = std::move(other125.clientHostName);
  clientID = std::move(other125.clientID);
  userName = std::move(other125.userName);
  token = std::move(other125.token);
  defaultSchema = std::move(other125.defaultSchema);
  __isset = std::move(other125.__isset);
}
ConnectionProperties& ConnectionProperties::operator=(const ConnectionProperties& other126) {
  connId = other126.connId;
  clientHostName = other126.clientHostName;
  clientID = other126.clientID;
  userName = other126.userName;
  token = other126.token;
  defaultSchema = other126.defaultSchema;
  __isset = other126.__isset;
  return *this;
}
ConnectionProperties& ConnectionProperties::operator=(ConnectionProperties&& other127) noexcept {
  connId = std::move(other127.connId);
  clientHostName = std::move(other127.clientHostName);
  clientID = std::move(other127.clientID);
  userName = std::move(other127.userName);
  token = std::move(other127.token);
  defaultSchema = std::move(other127.defaultSchema);
  __isset = std::move(other127.__isset);
  return *this;
}
void ConnectionProperties::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "ConnectionProperties(";
  out << "connId=" << to_string(connId);
  out << ", " << "clientHostName=" << to_string(clientHostName);
  out << ", " << "clientID=" << to_string(clientID);
  out << ", " << "userName="; (__isset.userName ? (out << to_string(userName)) : (out << "<null>"));
  out << ", " << "token="; (__isset.token ? (out << to_string(token)) : (out << "<null>"));
  out << ", " << "defaultSchema="; (__isset.defaultSchema ? (out << to_string(defaultSchema)) : (out << "<null>"));
  out << ")";
}

}}} // namespace
