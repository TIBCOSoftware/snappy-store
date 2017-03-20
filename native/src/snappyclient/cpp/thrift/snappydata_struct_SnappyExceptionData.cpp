/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#include <iosfwd>

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include <thrift/cxxfunctional.h>
#include "snappydata_struct_SnappyExceptionData.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


SnappyExceptionData::~SnappyExceptionData() noexcept {
}


void SnappyExceptionData::__set_reason(const std::string& val) {
  this->reason = val;
}

void SnappyExceptionData::__set_errorCode(const int32_t val) {
  this->errorCode = val;
}

void SnappyExceptionData::__set_sqlState(const std::string& val) {
  this->sqlState = val;
__isset.sqlState = true;
}

void SnappyExceptionData::__set_updateCounts(const std::vector<int32_t> & val) {
  this->updateCounts = val;
__isset.updateCounts = true;
}

uint32_t SnappyExceptionData::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_reason = false;
  bool isset_errorCode = false;

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
          xfer += iprot->readString(this->reason);
          isset_reason = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          xfer += iprot->readI32(this->errorCode);
          isset_errorCode = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->sqlState);
          this->__isset.sqlState = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->updateCounts.clear();
            uint32_t _size133;
            ::apache::thrift::protocol::TType _etype136;
            xfer += iprot->readListBegin(_etype136, _size133);
            this->updateCounts.resize(_size133);
            uint32_t _i137;
            for (_i137 = 0; _i137 < _size133; ++_i137)
            {
              xfer += iprot->readI32(this->updateCounts[_i137]);
            }
            xfer += iprot->readListEnd();
          }
          this->__isset.updateCounts = true;
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

  if (!isset_reason)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_errorCode)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t SnappyExceptionData::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("SnappyExceptionData");

  xfer += oprot->writeFieldBegin("reason", ::apache::thrift::protocol::T_STRING, 1);
  xfer += oprot->writeString(this->reason);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("errorCode", ::apache::thrift::protocol::T_I32, 2);
  xfer += oprot->writeI32(this->errorCode);
  xfer += oprot->writeFieldEnd();

  if (this->__isset.sqlState) {
    xfer += oprot->writeFieldBegin("sqlState", ::apache::thrift::protocol::T_STRING, 3);
    xfer += oprot->writeString(this->sqlState);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.updateCounts) {
    xfer += oprot->writeFieldBegin("updateCounts", ::apache::thrift::protocol::T_LIST, 4);
    {
      xfer += oprot->writeListBegin(::apache::thrift::protocol::T_I32, static_cast<uint32_t>(this->updateCounts.size()));
      std::vector<int32_t> ::const_iterator _iter138;
      for (_iter138 = this->updateCounts.begin(); _iter138 != this->updateCounts.end(); ++_iter138)
      {
        xfer += oprot->writeI32((*_iter138));
      }
      xfer += oprot->writeListEnd();
    }
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(SnappyExceptionData &a, SnappyExceptionData &b) noexcept {
  using ::std::swap;
  static_assert(noexcept(swap(a, b)), "throwing swap");
  swap(a.reason, b.reason);
  swap(a.errorCode, b.errorCode);
  swap(a.sqlState, b.sqlState);
  swap(a.updateCounts, b.updateCounts);
  swap(a.__isset, b.__isset);
}

SnappyExceptionData::SnappyExceptionData(const SnappyExceptionData& other139) {
  reason = other139.reason;
  errorCode = other139.errorCode;
  sqlState = other139.sqlState;
  updateCounts = other139.updateCounts;
  __isset = other139.__isset;
}
SnappyExceptionData::SnappyExceptionData( SnappyExceptionData&& other140) noexcept {
  reason = std::move(other140.reason);
  errorCode = std::move(other140.errorCode);
  sqlState = std::move(other140.sqlState);
  updateCounts = std::move(other140.updateCounts);
  __isset = std::move(other140.__isset);
}
SnappyExceptionData& SnappyExceptionData::operator=(const SnappyExceptionData& other141) {
  reason = other141.reason;
  errorCode = other141.errorCode;
  sqlState = other141.sqlState;
  updateCounts = other141.updateCounts;
  __isset = other141.__isset;
  return *this;
}
SnappyExceptionData& SnappyExceptionData::operator=(SnappyExceptionData&& other142) noexcept {
  reason = std::move(other142.reason);
  errorCode = std::move(other142.errorCode);
  sqlState = std::move(other142.sqlState);
  updateCounts = std::move(other142.updateCounts);
  __isset = std::move(other142.__isset);
  return *this;
}
void SnappyExceptionData::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "SnappyExceptionData(";
  out << "reason=" << to_string(reason);
  out << ", " << "errorCode=" << to_string(errorCode);
  out << ", " << "sqlState="; (__isset.sqlState ? (out << to_string(sqlState)) : (out << "<null>"));
  out << ", " << "updateCounts="; (__isset.updateCounts ? (out << to_string(updateCounts)) : (out << "<null>"));
  out << ")";
}

}}} // namespace
