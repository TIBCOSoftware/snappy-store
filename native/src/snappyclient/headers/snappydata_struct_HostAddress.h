/**
 * Autogenerated by Thrift Compiler (0.12.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#ifndef SNAPPYDATA_STRUCT_HOSTADDRESS_H
#define SNAPPYDATA_STRUCT_HOSTADDRESS_H


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
  HostAddress(HostAddress&&) noexcept;
  HostAddress& operator=(const HostAddress&);
  HostAddress& operator=(HostAddress&&) noexcept;
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

  bool operator == (const HostAddress & rhs) const
  {
    if (!(hostName == rhs.hostName))
      return false;
    if (!(port == rhs.port))
      return false;
    if (__isset.ipAddress != rhs.__isset.ipAddress)
      return false;
    else if (__isset.ipAddress && !(ipAddress == rhs.ipAddress))
      return false;
    if (__isset.serverType != rhs.__isset.serverType)
      return false;
    else if (__isset.serverType && !(serverType == rhs.serverType))
      return false;
    if (__isset.isCurrent != rhs.__isset.isCurrent)
      return false;
    else if (__isset.isCurrent && !(isCurrent == rhs.isCurrent))
      return false;
    return true;
  }
  bool operator != (const HostAddress &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const HostAddress & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  virtual void printTo(std::ostream& out) const;
};

void swap(HostAddress &a, HostAddress &b);

std::ostream& operator<<(std::ostream& out, const HostAddress& obj);

}}} // namespace

#endif
