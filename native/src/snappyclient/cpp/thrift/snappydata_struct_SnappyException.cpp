/**
 * Autogenerated by Thrift Compiler (1.0.0-dev)
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
#include "snappydata_struct_SnappyException.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


SnappyException::~SnappyException() throw() {
}


void SnappyException::__set_exceptionData(const SnappyExceptionData& val) {
  this->exceptionData = val;
}

void SnappyException::__set_serverInfo(const std::string& val) {
  this->serverInfo = val;
}

void SnappyException::__set_nextExceptions(const std::vector<SnappyExceptionData> & val) {
  this->nextExceptions = val;
__isset.nextExceptions = true;
}

uint32_t SnappyException::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_exceptionData = false;
  bool isset_serverInfo = false;

  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->exceptionData.read(iprot);
          isset_exceptionData = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->serverInfo);
          isset_serverInfo = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->nextExceptions.clear();
            uint32_t _size173;
            ::apache::thrift::protocol::TType _etype176;
            xfer += iprot->readListBegin(_etype176, _size173);
            this->nextExceptions.resize(_size173);
            uint32_t _i177;
            for (_i177 = 0; _i177 < _size173; ++_i177)
            {
              xfer += this->nextExceptions[_i177].read(iprot);
            }
            xfer += iprot->readListEnd();
          }
          this->__isset.nextExceptions = true;
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

  if (!isset_exceptionData)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_serverInfo)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t SnappyException::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("SnappyException");

  xfer += oprot->writeFieldBegin("exceptionData", ::apache::thrift::protocol::T_STRUCT, 1);
  xfer += this->exceptionData.write(oprot);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("serverInfo", ::apache::thrift::protocol::T_STRING, 2);
  xfer += oprot->writeString(this->serverInfo);
  xfer += oprot->writeFieldEnd();

  if (this->__isset.nextExceptions) {
    xfer += oprot->writeFieldBegin("nextExceptions", ::apache::thrift::protocol::T_LIST, 3);
    {
      xfer += oprot->writeListBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->nextExceptions.size()));
      std::vector<SnappyExceptionData> ::const_iterator _iter178;
      for (_iter178 = this->nextExceptions.begin(); _iter178 != this->nextExceptions.end(); ++_iter178)
      {
        xfer += (*_iter178).write(oprot);
      }
      xfer += oprot->writeListEnd();
    }
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(SnappyException &a, SnappyException &b) {
  using ::std::swap;
  swap(a.exceptionData, b.exceptionData);
  swap(a.serverInfo, b.serverInfo);
  swap(a.nextExceptions, b.nextExceptions);
  swap(a.__isset, b.__isset);
}

SnappyException::SnappyException(const SnappyException& other179) : TException() {
  exceptionData = other179.exceptionData;
  serverInfo = other179.serverInfo;
  nextExceptions = other179.nextExceptions;
  __isset = other179.__isset;
}
SnappyException::SnappyException( SnappyException&& other180) : TException() {
  exceptionData = std::move(other180.exceptionData);
  serverInfo = std::move(other180.serverInfo);
  nextExceptions = std::move(other180.nextExceptions);
  __isset = std::move(other180.__isset);
}
SnappyException& SnappyException::operator=(const SnappyException& other181) {
  exceptionData = other181.exceptionData;
  serverInfo = other181.serverInfo;
  nextExceptions = other181.nextExceptions;
  __isset = other181.__isset;
  return *this;
}
SnappyException& SnappyException::operator=(SnappyException&& other182) {
  exceptionData = std::move(other182.exceptionData);
  serverInfo = std::move(other182.serverInfo);
  nextExceptions = std::move(other182.nextExceptions);
  __isset = std::move(other182.__isset);
  return *this;
}
void SnappyException::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "SnappyException(";
  out << "exceptionData=" << to_string(exceptionData);
  out << ", " << "serverInfo=" << to_string(serverInfo);
  out << ", " << "nextExceptions="; (__isset.nextExceptions ? (out << to_string(nextExceptions)) : (out << "<null>"));
  out << ")";
}

const char* SnappyException::what() const throw() {
  try {
    std::stringstream ss;
    ss << "TException - service has thrown: " << *this;
    this->thriftTExceptionMessageHolder_ = ss.str();
    return this->thriftTExceptionMessageHolder_.c_str();
  } catch (const std::exception&) {
    return "TException - service has thrown: SnappyException";
  }
}

}}} // namespace
