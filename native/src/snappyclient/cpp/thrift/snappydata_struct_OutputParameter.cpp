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
#include "snappydata_struct_OutputParameter.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


OutputParameter::~OutputParameter() noexcept {
}


void OutputParameter::__set_type(const SnappyType::type val) {
  this->type = val;
}

void OutputParameter::__set_scale(const int32_t val) {
  this->scale = val;
__isset.scale = true;
}

void OutputParameter::__set_typeName(const std::string& val) {
  this->typeName = val;
__isset.typeName = true;
}

uint32_t OutputParameter::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_type = false;

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
          int32_t ecast231;
          xfer += iprot->readI32(ecast231);
          this->type = (SnappyType::type)ecast231;
          isset_type = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          xfer += iprot->readI32(this->scale);
          this->__isset.scale = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->typeName);
          this->__isset.typeName = true;
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

  if (!isset_type)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t OutputParameter::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("OutputParameter");

  xfer += oprot->writeFieldBegin("type", ::apache::thrift::protocol::T_I32, 1);
  xfer += oprot->writeI32((int32_t)this->type);
  xfer += oprot->writeFieldEnd();

  if (this->__isset.scale) {
    xfer += oprot->writeFieldBegin("scale", ::apache::thrift::protocol::T_I32, 2);
    xfer += oprot->writeI32(this->scale);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.typeName) {
    xfer += oprot->writeFieldBegin("typeName", ::apache::thrift::protocol::T_STRING, 3);
    xfer += oprot->writeString(this->typeName);
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(OutputParameter &a, OutputParameter &b) {
  using ::std::swap;
  swap(a.type, b.type);
  swap(a.scale, b.scale);
  swap(a.typeName, b.typeName);
  swap(a.__isset, b.__isset);
}

OutputParameter::OutputParameter(const OutputParameter& other232) {
  type = other232.type;
  scale = other232.scale;
  typeName = other232.typeName;
  __isset = other232.__isset;
}
OutputParameter::OutputParameter( OutputParameter&& other233) noexcept {
  type = std::move(other233.type);
  scale = std::move(other233.scale);
  typeName = std::move(other233.typeName);
  __isset = std::move(other233.__isset);
}
OutputParameter& OutputParameter::operator=(const OutputParameter& other234) {
  type = other234.type;
  scale = other234.scale;
  typeName = other234.typeName;
  __isset = other234.__isset;
  return *this;
}
OutputParameter& OutputParameter::operator=(OutputParameter&& other235) noexcept {
  type = std::move(other235.type);
  scale = std::move(other235.scale);
  typeName = std::move(other235.typeName);
  __isset = std::move(other235.__isset);
  return *this;
}
void OutputParameter::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "OutputParameter(";
  out << "type=" << to_string(type);
  out << ", " << "scale="; (__isset.scale ? (out << to_string(scale)) : (out << "<null>"));
  out << ", " << "typeName="; (__isset.typeName ? (out << to_string(typeName)) : (out << "<null>"));
  out << ")";
}

}}} // namespace
