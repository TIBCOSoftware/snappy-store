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

#include "snappydata_struct_PrepareResult.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


PrepareResult::~PrepareResult() noexcept {
}


void PrepareResult::__set_statementId(const int64_t val) {
  this->statementId = val;
}

void PrepareResult::__set_statementType(const int8_t val) {
  this->statementType = val;
}

void PrepareResult::__set_parameterMetaData(const std::vector<ColumnDescriptor> & val) {
  this->parameterMetaData = val;
}

void PrepareResult::__set_resultSetMetaData(const std::vector<ColumnDescriptor> & val) {
  this->resultSetMetaData = val;
__isset.resultSetMetaData = true;
}

void PrepareResult::__set_warnings(const SnappyExceptionData& val) {
  this->warnings = val;
__isset.warnings = true;
}
std::ostream& operator<<(std::ostream& out, const PrepareResult& obj)
{
  obj.printTo(out);
  return out;
}


uint32_t PrepareResult::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_statementId = false;
  bool isset_statementType = false;
  bool isset_parameterMetaData = false;

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
          xfer += iprot->readI64(this->statementId);
          isset_statementId = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_BYTE) {
          xfer += iprot->readByte(this->statementType);
          isset_statementType = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->parameterMetaData.clear();
            uint32_t _size258;
            ::apache::thrift::protocol::TType _etype261;
            xfer += iprot->readListBegin(_etype261, _size258);
            this->parameterMetaData.resize(_size258);
            uint32_t _i262;
            for (_i262 = 0; _i262 < _size258; ++_i262)
            {
              xfer += this->parameterMetaData[_i262].read(iprot);
            }
            xfer += iprot->readListEnd();
          }
          isset_parameterMetaData = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->resultSetMetaData.clear();
            uint32_t _size263;
            ::apache::thrift::protocol::TType _etype266;
            xfer += iprot->readListBegin(_etype266, _size263);
            this->resultSetMetaData.resize(_size263);
            uint32_t _i267;
            for (_i267 = 0; _i267 < _size263; ++_i267)
            {
              xfer += this->resultSetMetaData[_i267].read(iprot);
            }
            xfer += iprot->readListEnd();
          }
          this->__isset.resultSetMetaData = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 5:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->warnings.read(iprot);
          this->__isset.warnings = true;
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

  if (!isset_statementId)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_statementType)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_parameterMetaData)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t PrepareResult::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("PrepareResult");

  xfer += oprot->writeFieldBegin("statementId", ::apache::thrift::protocol::T_I64, 1);
  xfer += oprot->writeI64(this->statementId);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("statementType", ::apache::thrift::protocol::T_BYTE, 2);
  xfer += oprot->writeByte(this->statementType);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("parameterMetaData", ::apache::thrift::protocol::T_LIST, 3);
  {
    xfer += oprot->writeListBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->parameterMetaData.size()));
    std::vector<ColumnDescriptor> ::const_iterator _iter268;
    for (_iter268 = this->parameterMetaData.begin(); _iter268 != this->parameterMetaData.end(); ++_iter268)
    {
      xfer += (*_iter268).write(oprot);
    }
    xfer += oprot->writeListEnd();
  }
  xfer += oprot->writeFieldEnd();

  if (this->__isset.resultSetMetaData) {
    xfer += oprot->writeFieldBegin("resultSetMetaData", ::apache::thrift::protocol::T_LIST, 4);
    {
      xfer += oprot->writeListBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->resultSetMetaData.size()));
      std::vector<ColumnDescriptor> ::const_iterator _iter269;
      for (_iter269 = this->resultSetMetaData.begin(); _iter269 != this->resultSetMetaData.end(); ++_iter269)
      {
        xfer += (*_iter269).write(oprot);
      }
      xfer += oprot->writeListEnd();
    }
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.warnings) {
    xfer += oprot->writeFieldBegin("warnings", ::apache::thrift::protocol::T_STRUCT, 5);
    xfer += this->warnings.write(oprot);
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(PrepareResult &a, PrepareResult &b) {
  using ::std::swap;
  swap(a.statementId, b.statementId);
  swap(a.statementType, b.statementType);
  swap(a.parameterMetaData, b.parameterMetaData);
  swap(a.resultSetMetaData, b.resultSetMetaData);
  swap(a.warnings, b.warnings);
  swap(a.__isset, b.__isset);
}

PrepareResult::PrepareResult(const PrepareResult& other270) {
  statementId = other270.statementId;
  statementType = other270.statementType;
  parameterMetaData = other270.parameterMetaData;
  resultSetMetaData = other270.resultSetMetaData;
  warnings = other270.warnings;
  __isset = other270.__isset;
}
PrepareResult::PrepareResult( PrepareResult&& other271) {
  statementId = std::move(other271.statementId);
  statementType = std::move(other271.statementType);
  parameterMetaData = std::move(other271.parameterMetaData);
  resultSetMetaData = std::move(other271.resultSetMetaData);
  warnings = std::move(other271.warnings);
  __isset = std::move(other271.__isset);
}
PrepareResult& PrepareResult::operator=(const PrepareResult& other272) {
  statementId = other272.statementId;
  statementType = other272.statementType;
  parameterMetaData = other272.parameterMetaData;
  resultSetMetaData = other272.resultSetMetaData;
  warnings = other272.warnings;
  __isset = other272.__isset;
  return *this;
}
PrepareResult& PrepareResult::operator=(PrepareResult&& other273) {
  statementId = std::move(other273.statementId);
  statementType = std::move(other273.statementType);
  parameterMetaData = std::move(other273.parameterMetaData);
  resultSetMetaData = std::move(other273.resultSetMetaData);
  warnings = std::move(other273.warnings);
  __isset = std::move(other273.__isset);
  return *this;
}
void PrepareResult::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "PrepareResult(";
  out << "statementId=" << to_string(statementId);
  out << ", " << "statementType=" << to_string(statementType);
  out << ", " << "parameterMetaData=" << to_string(parameterMetaData);
  out << ", " << "resultSetMetaData="; (__isset.resultSetMetaData ? (out << to_string(resultSetMetaData)) : (out << "<null>"));
  out << ", " << "warnings="; (__isset.warnings ? (out << to_string(warnings)) : (out << "<null>"));
  out << ")";
}

}}} // namespace
