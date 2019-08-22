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
#include "snappydata_struct_CatalogFunctionObject.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


CatalogFunctionObject::~CatalogFunctionObject() noexcept {
}


void CatalogFunctionObject::__set_functionName(const std::string& val) {
  this->functionName = val;
}

void CatalogFunctionObject::__set_schemaName(const std::string& val) {
  this->schemaName = val;
__isset.schemaName = true;
}

void CatalogFunctionObject::__set_className(const std::string& val) {
  this->className = val;
}

void CatalogFunctionObject::__set_resources(const std::vector<std::string> & val) {
  this->resources = val;
}

uint32_t CatalogFunctionObject::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_functionName = false;
  bool isset_className = false;
  bool isset_resources = false;

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
          xfer += iprot->readString(this->functionName);
          isset_functionName = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->schemaName);
          this->__isset.schemaName = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->className);
          isset_className = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->resources.clear();
            uint32_t _size416;
            ::apache::thrift::protocol::TType _etype419;
            xfer += iprot->readListBegin(_etype419, _size416);
            this->resources.resize(_size416);
            uint32_t _i420;
            for (_i420 = 0; _i420 < _size416; ++_i420)
            {
              xfer += iprot->readString(this->resources[_i420]);
            }
            xfer += iprot->readListEnd();
          }
          isset_resources = true;
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

  if (!isset_functionName)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_className)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_resources)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t CatalogFunctionObject::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("CatalogFunctionObject");

  xfer += oprot->writeFieldBegin("functionName", ::apache::thrift::protocol::T_STRING, 1);
  xfer += oprot->writeString(this->functionName);
  xfer += oprot->writeFieldEnd();

  if (this->__isset.schemaName) {
    xfer += oprot->writeFieldBegin("schemaName", ::apache::thrift::protocol::T_STRING, 2);
    xfer += oprot->writeString(this->schemaName);
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldBegin("className", ::apache::thrift::protocol::T_STRING, 3);
  xfer += oprot->writeString(this->className);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("resources", ::apache::thrift::protocol::T_LIST, 4);
  {
    xfer += oprot->writeListBegin(::apache::thrift::protocol::T_STRING, static_cast<uint32_t>(this->resources.size()));
    std::vector<std::string> ::const_iterator _iter421;
    for (_iter421 = this->resources.begin(); _iter421 != this->resources.end(); ++_iter421)
    {
      xfer += oprot->writeString((*_iter421));
    }
    xfer += oprot->writeListEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(CatalogFunctionObject &a, CatalogFunctionObject &b) {
  using ::std::swap;
  swap(a.functionName, b.functionName);
  swap(a.schemaName, b.schemaName);
  swap(a.className, b.className);
  swap(a.resources, b.resources);
  swap(a.__isset, b.__isset);
}

CatalogFunctionObject::CatalogFunctionObject(const CatalogFunctionObject& other422) {
  functionName = other422.functionName;
  schemaName = other422.schemaName;
  className = other422.className;
  resources = other422.resources;
  __isset = other422.__isset;
}
CatalogFunctionObject::CatalogFunctionObject( CatalogFunctionObject&& other423) noexcept {
  functionName = std::move(other423.functionName);
  schemaName = std::move(other423.schemaName);
  className = std::move(other423.className);
  resources = std::move(other423.resources);
  __isset = std::move(other423.__isset);
}
CatalogFunctionObject& CatalogFunctionObject::operator=(const CatalogFunctionObject& other424) {
  functionName = other424.functionName;
  schemaName = other424.schemaName;
  className = other424.className;
  resources = other424.resources;
  __isset = other424.__isset;
  return *this;
}
CatalogFunctionObject& CatalogFunctionObject::operator=(CatalogFunctionObject&& other425) noexcept {
  functionName = std::move(other425.functionName);
  schemaName = std::move(other425.schemaName);
  className = std::move(other425.className);
  resources = std::move(other425.resources);
  __isset = std::move(other425.__isset);
  return *this;
}
void CatalogFunctionObject::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "CatalogFunctionObject(";
  out << "functionName=" << to_string(functionName);
  out << ", " << "schemaName="; (__isset.schemaName ? (out << to_string(schemaName)) : (out << "<null>"));
  out << ", " << "className=" << to_string(className);
  out << ", " << "resources=" << to_string(resources);
  out << ")";
}

}}} // namespace
