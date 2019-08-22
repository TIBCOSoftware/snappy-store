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
#include "snappydata_struct_TransactionXid.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


TransactionXid::~TransactionXid() noexcept {
}


void TransactionXid::__set_formatId(const int32_t val) {
  this->formatId = val;
}

void TransactionXid::__set_globalId(const std::string& val) {
  this->globalId = val;
}

void TransactionXid::__set_branchQualifier(const std::string& val) {
  this->branchQualifier = val;
}

uint32_t TransactionXid::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_formatId = false;
  bool isset_globalId = false;
  bool isset_branchQualifier = false;

  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          xfer += iprot->readI32(this->formatId);
          isset_formatId = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readBinary(this->globalId);
          isset_globalId = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readBinary(this->branchQualifier);
          isset_branchQualifier = true;
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

  if (!isset_formatId)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_globalId)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_branchQualifier)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t TransactionXid::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("TransactionXid");

  xfer += oprot->writeFieldBegin("formatId", ::apache::thrift::protocol::T_I32, 1);
  xfer += oprot->writeI32(this->formatId);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("globalId", ::apache::thrift::protocol::T_STRING, 2);
  xfer += oprot->writeBinary(this->globalId);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("branchQualifier", ::apache::thrift::protocol::T_STRING, 3);
  xfer += oprot->writeBinary(this->branchQualifier);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(TransactionXid &a, TransactionXid &b) {
  using ::std::swap;
  swap(a.formatId, b.formatId);
  swap(a.globalId, b.globalId);
  swap(a.branchQualifier, b.branchQualifier);
}

TransactionXid::TransactionXid(const TransactionXid& other12) {
  formatId = other12.formatId;
  globalId = other12.globalId;
  branchQualifier = other12.branchQualifier;
}
TransactionXid::TransactionXid( TransactionXid&& other13) noexcept {
  formatId = std::move(other13.formatId);
  globalId = std::move(other13.globalId);
  branchQualifier = std::move(other13.branchQualifier);
}
TransactionXid& TransactionXid::operator=(const TransactionXid& other14) {
  formatId = other14.formatId;
  globalId = other14.globalId;
  branchQualifier = other14.branchQualifier;
  return *this;
}
TransactionXid& TransactionXid::operator=(TransactionXid&& other15) noexcept {
  formatId = std::move(other15.formatId);
  globalId = std::move(other15.globalId);
  branchQualifier = std::move(other15.branchQualifier);
  return *this;
}
void TransactionXid::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "TransactionXid(";
  out << "formatId=" << to_string(formatId);
  out << ", " << "globalId=" << to_string(globalId);
  out << ", " << "branchQualifier=" << to_string(branchQualifier);
  out << ")";
}

}}} // namespace
