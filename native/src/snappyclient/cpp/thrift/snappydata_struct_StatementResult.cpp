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

#include "snappydata_struct_StatementResult.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


StatementResult::~StatementResult() noexcept {
}


void StatementResult::__set_resultSet(const RowSet& val) {
  this->resultSet = val;
__isset.resultSet = true;
}

void StatementResult::__set_updateCount(const int32_t val) {
  this->updateCount = val;
__isset.updateCount = true;
}

void StatementResult::__set_batchUpdateCounts(const std::vector<int32_t> & val) {
  this->batchUpdateCounts = val;
__isset.batchUpdateCounts = true;
}

void StatementResult::__set_procedureOutParams(const std::map<int32_t, ColumnValue> & val) {
  this->procedureOutParams = val;
__isset.procedureOutParams = true;
}

void StatementResult::__set_generatedKeys(const RowSet& val) {
  this->generatedKeys = val;
__isset.generatedKeys = true;
}

void StatementResult::__set_newDefaultSchema(const std::string& val) {
  this->newDefaultSchema = val;
__isset.newDefaultSchema = true;
}

void StatementResult::__set_warnings(const SnappyExceptionData& val) {
  this->warnings = val;
__isset.warnings = true;
}

void StatementResult::__set_preparedResult(const PrepareResult& val) {
  this->preparedResult = val;
__isset.preparedResult = true;
}
std::ostream& operator<<(std::ostream& out, const StatementResult& obj)
{
  obj.printTo(out);
  return out;
}


uint32_t StatementResult::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;


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
          xfer += this->resultSet.read(iprot);
          this->__isset.resultSet = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          xfer += iprot->readI32(this->updateCount);
          this->__isset.updateCount = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->batchUpdateCounts.clear();
            uint32_t _size284;
            ::apache::thrift::protocol::TType _etype287;
            xfer += iprot->readListBegin(_etype287, _size284);
            this->batchUpdateCounts.resize(_size284);
            uint32_t _i288;
            for (_i288 = 0; _i288 < _size284; ++_i288)
            {
              xfer += iprot->readI32(this->batchUpdateCounts[_i288]);
            }
            xfer += iprot->readListEnd();
          }
          this->__isset.batchUpdateCounts = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_MAP) {
          {
            this->procedureOutParams.clear();
            uint32_t _size289;
            ::apache::thrift::protocol::TType _ktype290;
            ::apache::thrift::protocol::TType _vtype291;
            xfer += iprot->readMapBegin(_ktype290, _vtype291, _size289);
            uint32_t _i293;
            for (_i293 = 0; _i293 < _size289; ++_i293)
            {
              int32_t _key294;
              xfer += iprot->readI32(_key294);
              ColumnValue& _val295 = this->procedureOutParams[_key294];
              xfer += _val295.read(iprot);
            }
            xfer += iprot->readMapEnd();
          }
          this->__isset.procedureOutParams = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 5:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->generatedKeys.read(iprot);
          this->__isset.generatedKeys = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 6:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->newDefaultSchema);
          this->__isset.newDefaultSchema = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 7:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->warnings.read(iprot);
          this->__isset.warnings = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 8:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->preparedResult.read(iprot);
          this->__isset.preparedResult = true;
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

  return xfer;
}

uint32_t StatementResult::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("StatementResult");

  if (this->__isset.resultSet) {
    xfer += oprot->writeFieldBegin("resultSet", ::apache::thrift::protocol::T_STRUCT, 1);
    xfer += this->resultSet.write(oprot);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.updateCount) {
    xfer += oprot->writeFieldBegin("updateCount", ::apache::thrift::protocol::T_I32, 2);
    xfer += oprot->writeI32(this->updateCount);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.batchUpdateCounts) {
    xfer += oprot->writeFieldBegin("batchUpdateCounts", ::apache::thrift::protocol::T_LIST, 3);
    {
      xfer += oprot->writeListBegin(::apache::thrift::protocol::T_I32, static_cast<uint32_t>(this->batchUpdateCounts.size()));
      std::vector<int32_t> ::const_iterator _iter296;
      for (_iter296 = this->batchUpdateCounts.begin(); _iter296 != this->batchUpdateCounts.end(); ++_iter296)
      {
        xfer += oprot->writeI32((*_iter296));
      }
      xfer += oprot->writeListEnd();
    }
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.procedureOutParams) {
    xfer += oprot->writeFieldBegin("procedureOutParams", ::apache::thrift::protocol::T_MAP, 4);
    {
      xfer += oprot->writeMapBegin(::apache::thrift::protocol::T_I32, ::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->procedureOutParams.size()));
      std::map<int32_t, ColumnValue> ::const_iterator _iter297;
      for (_iter297 = this->procedureOutParams.begin(); _iter297 != this->procedureOutParams.end(); ++_iter297)
      {
        xfer += oprot->writeI32(_iter297->first);
        xfer += _iter297->second.write(oprot);
      }
      xfer += oprot->writeMapEnd();
    }
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.generatedKeys) {
    xfer += oprot->writeFieldBegin("generatedKeys", ::apache::thrift::protocol::T_STRUCT, 5);
    xfer += this->generatedKeys.write(oprot);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.newDefaultSchema) {
    xfer += oprot->writeFieldBegin("newDefaultSchema", ::apache::thrift::protocol::T_STRING, 6);
    xfer += oprot->writeString(this->newDefaultSchema);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.warnings) {
    xfer += oprot->writeFieldBegin("warnings", ::apache::thrift::protocol::T_STRUCT, 7);
    xfer += this->warnings.write(oprot);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.preparedResult) {
    xfer += oprot->writeFieldBegin("preparedResult", ::apache::thrift::protocol::T_STRUCT, 8);
    xfer += this->preparedResult.write(oprot);
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(StatementResult &a, StatementResult &b) {
  using ::std::swap;
  swap(a.resultSet, b.resultSet);
  swap(a.updateCount, b.updateCount);
  swap(a.batchUpdateCounts, b.batchUpdateCounts);
  swap(a.procedureOutParams, b.procedureOutParams);
  swap(a.generatedKeys, b.generatedKeys);
  swap(a.newDefaultSchema, b.newDefaultSchema);
  swap(a.warnings, b.warnings);
  swap(a.preparedResult, b.preparedResult);
  swap(a.__isset, b.__isset);
}

StatementResult::StatementResult(const StatementResult& other298) {
  resultSet = other298.resultSet;
  updateCount = other298.updateCount;
  batchUpdateCounts = other298.batchUpdateCounts;
  procedureOutParams = other298.procedureOutParams;
  generatedKeys = other298.generatedKeys;
  newDefaultSchema = other298.newDefaultSchema;
  warnings = other298.warnings;
  preparedResult = other298.preparedResult;
  __isset = other298.__isset;
}
StatementResult::StatementResult( StatementResult&& other299) {
  resultSet = std::move(other299.resultSet);
  updateCount = std::move(other299.updateCount);
  batchUpdateCounts = std::move(other299.batchUpdateCounts);
  procedureOutParams = std::move(other299.procedureOutParams);
  generatedKeys = std::move(other299.generatedKeys);
  newDefaultSchema = std::move(other299.newDefaultSchema);
  warnings = std::move(other299.warnings);
  preparedResult = std::move(other299.preparedResult);
  __isset = std::move(other299.__isset);
}
StatementResult& StatementResult::operator=(const StatementResult& other300) {
  resultSet = other300.resultSet;
  updateCount = other300.updateCount;
  batchUpdateCounts = other300.batchUpdateCounts;
  procedureOutParams = other300.procedureOutParams;
  generatedKeys = other300.generatedKeys;
  newDefaultSchema = other300.newDefaultSchema;
  warnings = other300.warnings;
  preparedResult = other300.preparedResult;
  __isset = other300.__isset;
  return *this;
}
StatementResult& StatementResult::operator=(StatementResult&& other301) {
  resultSet = std::move(other301.resultSet);
  updateCount = std::move(other301.updateCount);
  batchUpdateCounts = std::move(other301.batchUpdateCounts);
  procedureOutParams = std::move(other301.procedureOutParams);
  generatedKeys = std::move(other301.generatedKeys);
  newDefaultSchema = std::move(other301.newDefaultSchema);
  warnings = std::move(other301.warnings);
  preparedResult = std::move(other301.preparedResult);
  __isset = std::move(other301.__isset);
  return *this;
}
void StatementResult::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "StatementResult(";
  out << "resultSet="; (__isset.resultSet ? (out << to_string(resultSet)) : (out << "<null>"));
  out << ", " << "updateCount="; (__isset.updateCount ? (out << to_string(updateCount)) : (out << "<null>"));
  out << ", " << "batchUpdateCounts="; (__isset.batchUpdateCounts ? (out << to_string(batchUpdateCounts)) : (out << "<null>"));
  out << ", " << "procedureOutParams="; (__isset.procedureOutParams ? (out << to_string(procedureOutParams)) : (out << "<null>"));
  out << ", " << "generatedKeys="; (__isset.generatedKeys ? (out << to_string(generatedKeys)) : (out << "<null>"));
  out << ", " << "newDefaultSchema="; (__isset.newDefaultSchema ? (out << to_string(newDefaultSchema)) : (out << "<null>"));
  out << ", " << "warnings="; (__isset.warnings ? (out << to_string(warnings)) : (out << "<null>"));
  out << ", " << "preparedResult="; (__isset.preparedResult ? (out << to_string(preparedResult)) : (out << "<null>"));
  out << ")";
}

}}} // namespace
