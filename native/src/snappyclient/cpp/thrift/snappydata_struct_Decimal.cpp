/**
 * Autogenerated by Thrift Compiler (0.14.1)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#include <iosfwd>

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include <functional>
#include <memory>

#include "snappydata_struct_Decimal.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


Decimal::~Decimal() noexcept {
}


void Decimal::__set_signum(const int8_t val) {
  this->signum = val;
}

void Decimal::__set_scale(const int32_t val) {
  this->scale = val;
}

void Decimal::__set_magnitude(const std::string& val) {
  this->magnitude = val;
}
std::ostream& operator<<(std::ostream& out, const Decimal& obj)
{
  obj.printTo(out);
  return out;
}


uint32_t Decimal::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_signum = false;
  bool isset_scale = false;
  bool isset_magnitude = false;

  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_BYTE) {
          xfer += iprot->readByte(this->signum);
          isset_signum = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          xfer += iprot->readI32(this->scale);
          isset_scale = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readBinary(this->magnitude);
          isset_magnitude = true;
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

  if (!isset_signum)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_scale)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_magnitude)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t Decimal::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("Decimal");

  xfer += oprot->writeFieldBegin("signum", ::apache::thrift::protocol::T_BYTE, 1);
  xfer += oprot->writeByte(this->signum);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("scale", ::apache::thrift::protocol::T_I32, 2);
  xfer += oprot->writeI32(this->scale);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("magnitude", ::apache::thrift::protocol::T_STRING, 3);
  xfer += oprot->writeBinary(this->magnitude);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(Decimal &a, Decimal &b) {
  using ::std::swap;
  swap(a.signum, b.signum);
  swap(a.scale, b.scale);
  swap(a.magnitude, b.magnitude);
}

Decimal::Decimal(const Decimal& other0) {
  signum = other0.signum;
  scale = other0.scale;
  magnitude = other0.magnitude;
}
Decimal::Decimal( Decimal&& other1) {
  signum = std::move(other1.signum);
  scale = std::move(other1.scale);
  magnitude = std::move(other1.magnitude);
}
Decimal& Decimal::operator=(const Decimal& other2) {
  signum = other2.signum;
  scale = other2.scale;
  magnitude = other2.magnitude;
  return *this;
}
Decimal& Decimal::operator=(Decimal&& other3) {
  signum = std::move(other3.signum);
  scale = std::move(other3.scale);
  magnitude = std::move(other3.magnitude);
  return *this;
}
void Decimal::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "Decimal(";
  out << "signum=" << to_string(signum);
  out << ", " << "scale=" << to_string(scale);
  out << ", " << "magnitude=" << to_string(magnitude);
  out << ")";
}

}}} // namespace
