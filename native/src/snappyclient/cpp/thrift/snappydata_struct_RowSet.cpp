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
#include "snappydata_struct_RowSet.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


RowSet::~RowSet() noexcept {
}


void RowSet::__set_rows(const std::vector<Row> & val) {
  this->rows = val;
}

void RowSet::__set_flags(const int8_t val) {
  this->flags = val;
}

void RowSet::__set_cursorId(const int64_t val) {
  this->cursorId = val;
}

void RowSet::__set_statementId(const int64_t val) {
  this->statementId = val;
}

void RowSet::__set_connId(const int64_t val) {
  this->connId = val;
}

void RowSet::__set_token(const std::string& val) {
  this->token = val;
__isset.token = true;
}

void RowSet::__set_source(const HostAddress& val) {
  this->source = val;
__isset.source = true;
}

void RowSet::__set_offset(const int32_t val) {
  this->offset = val;
__isset.offset = true;
}

void RowSet::__set_metadata(const std::vector<ColumnDescriptor> & val) {
  this->metadata = val;
__isset.metadata = true;
}

void RowSet::__set_cursorName(const std::string& val) {
  this->cursorName = val;
__isset.cursorName = true;
}

void RowSet::__set_warnings(const SnappyExceptionData& val) {
  this->warnings = val;
__isset.warnings = true;
}

void RowSet::__set_rowIdsForUpdateOrDelete(const std::vector<int64_t> & val) {
  this->rowIdsForUpdateOrDelete = val;
__isset.rowIdsForUpdateOrDelete = true;
}
std::ostream& operator<<(std::ostream& out, const RowSet& obj)
{
  obj.printTo(out);
  return out;
}


uint32_t RowSet::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_rows = false;
  bool isset_flags = false;
  bool isset_cursorId = false;
  bool isset_statementId = false;
  bool isset_connId = false;

  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->rows.clear();
            uint32_t _size236;
            ::apache::thrift::protocol::TType _etype239;
            xfer += iprot->readListBegin(_etype239, _size236);
            this->rows.resize(_size236);
            uint32_t _i240;
            for (_i240 = 0; _i240 < _size236; ++_i240)
            {
              xfer += this->rows[_i240].read(iprot);
            }
            xfer += iprot->readListEnd();
          }
          isset_rows = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_BYTE) {
          xfer += iprot->readByte(this->flags);
          isset_flags = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_I64) {
          xfer += iprot->readI64(this->cursorId);
          isset_cursorId = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_I64) {
          xfer += iprot->readI64(this->statementId);
          isset_statementId = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 5:
        if (ftype == ::apache::thrift::protocol::T_I64) {
          xfer += iprot->readI64(this->connId);
          isset_connId = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 6:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readBinary(this->token);
          this->__isset.token = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 7:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->source.read(iprot);
          this->__isset.source = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 8:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          xfer += iprot->readI32(this->offset);
          this->__isset.offset = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 9:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->metadata.clear();
            uint32_t _size241;
            ::apache::thrift::protocol::TType _etype244;
            xfer += iprot->readListBegin(_etype244, _size241);
            this->metadata.resize(_size241);
            uint32_t _i245;
            for (_i245 = 0; _i245 < _size241; ++_i245)
            {
              xfer += this->metadata[_i245].read(iprot);
            }
            xfer += iprot->readListEnd();
          }
          this->__isset.metadata = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 10:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->cursorName);
          this->__isset.cursorName = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 11:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->warnings.read(iprot);
          this->__isset.warnings = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 12:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->rowIdsForUpdateOrDelete.clear();
            uint32_t _size246;
            ::apache::thrift::protocol::TType _etype249;
            xfer += iprot->readListBegin(_etype249, _size246);
            this->rowIdsForUpdateOrDelete.resize(_size246);
            uint32_t _i250;
            for (_i250 = 0; _i250 < _size246; ++_i250)
            {
              xfer += iprot->readI64(this->rowIdsForUpdateOrDelete[_i250]);
            }
            xfer += iprot->readListEnd();
          }
          this->__isset.rowIdsForUpdateOrDelete = true;
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

  if (!isset_rows)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_flags)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_cursorId)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_statementId)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_connId)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t RowSet::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("RowSet");

  xfer += oprot->writeFieldBegin("rows", ::apache::thrift::protocol::T_LIST, 1);
  {
    xfer += oprot->writeListBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->rows.size()));
    std::vector<Row> ::const_iterator _iter251;
    for (_iter251 = this->rows.begin(); _iter251 != this->rows.end(); ++_iter251)
    {
      xfer += (*_iter251).write(oprot);
    }
    xfer += oprot->writeListEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("flags", ::apache::thrift::protocol::T_BYTE, 2);
  xfer += oprot->writeByte(this->flags);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("cursorId", ::apache::thrift::protocol::T_I64, 3);
  xfer += oprot->writeI64(this->cursorId);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("statementId", ::apache::thrift::protocol::T_I64, 4);
  xfer += oprot->writeI64(this->statementId);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("connId", ::apache::thrift::protocol::T_I64, 5);
  xfer += oprot->writeI64(this->connId);
  xfer += oprot->writeFieldEnd();

  if (this->__isset.token) {
    xfer += oprot->writeFieldBegin("token", ::apache::thrift::protocol::T_STRING, 6);
    xfer += oprot->writeBinary(this->token);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.source) {
    xfer += oprot->writeFieldBegin("source", ::apache::thrift::protocol::T_STRUCT, 7);
    xfer += this->source.write(oprot);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.offset) {
    xfer += oprot->writeFieldBegin("offset", ::apache::thrift::protocol::T_I32, 8);
    xfer += oprot->writeI32(this->offset);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.metadata) {
    xfer += oprot->writeFieldBegin("metadata", ::apache::thrift::protocol::T_LIST, 9);
    {
      xfer += oprot->writeListBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->metadata.size()));
      std::vector<ColumnDescriptor> ::const_iterator _iter252;
      for (_iter252 = this->metadata.begin(); _iter252 != this->metadata.end(); ++_iter252)
      {
        xfer += (*_iter252).write(oprot);
      }
      xfer += oprot->writeListEnd();
    }
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.cursorName) {
    xfer += oprot->writeFieldBegin("cursorName", ::apache::thrift::protocol::T_STRING, 10);
    xfer += oprot->writeString(this->cursorName);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.warnings) {
    xfer += oprot->writeFieldBegin("warnings", ::apache::thrift::protocol::T_STRUCT, 11);
    xfer += this->warnings.write(oprot);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.rowIdsForUpdateOrDelete) {
    xfer += oprot->writeFieldBegin("rowIdsForUpdateOrDelete", ::apache::thrift::protocol::T_LIST, 12);
    {
      xfer += oprot->writeListBegin(::apache::thrift::protocol::T_I64, static_cast<uint32_t>(this->rowIdsForUpdateOrDelete.size()));
      std::vector<int64_t> ::const_iterator _iter253;
      for (_iter253 = this->rowIdsForUpdateOrDelete.begin(); _iter253 != this->rowIdsForUpdateOrDelete.end(); ++_iter253)
      {
        xfer += oprot->writeI64((*_iter253));
      }
      xfer += oprot->writeListEnd();
    }
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(RowSet &a, RowSet &b) {
  using ::std::swap;
  swap(a.rows, b.rows);
  swap(a.flags, b.flags);
  swap(a.cursorId, b.cursorId);
  swap(a.statementId, b.statementId);
  swap(a.connId, b.connId);
  swap(a.token, b.token);
  swap(a.source, b.source);
  swap(a.offset, b.offset);
  swap(a.metadata, b.metadata);
  swap(a.cursorName, b.cursorName);
  swap(a.warnings, b.warnings);
  swap(a.rowIdsForUpdateOrDelete, b.rowIdsForUpdateOrDelete);
  swap(a.__isset, b.__isset);
}

RowSet::RowSet(const RowSet& other254) {
  rows = other254.rows;
  flags = other254.flags;
  cursorId = other254.cursorId;
  statementId = other254.statementId;
  connId = other254.connId;
  token = other254.token;
  source = other254.source;
  offset = other254.offset;
  metadata = other254.metadata;
  cursorName = other254.cursorName;
  warnings = other254.warnings;
  rowIdsForUpdateOrDelete = other254.rowIdsForUpdateOrDelete;
  __isset = other254.__isset;
}
RowSet::RowSet( RowSet&& other255) noexcept {
  rows = std::move(other255.rows);
  flags = std::move(other255.flags);
  cursorId = std::move(other255.cursorId);
  statementId = std::move(other255.statementId);
  connId = std::move(other255.connId);
  token = std::move(other255.token);
  source = std::move(other255.source);
  offset = std::move(other255.offset);
  metadata = std::move(other255.metadata);
  cursorName = std::move(other255.cursorName);
  warnings = std::move(other255.warnings);
  rowIdsForUpdateOrDelete = std::move(other255.rowIdsForUpdateOrDelete);
  __isset = std::move(other255.__isset);
}
RowSet& RowSet::operator=(const RowSet& other256) {
  rows = other256.rows;
  flags = other256.flags;
  cursorId = other256.cursorId;
  statementId = other256.statementId;
  connId = other256.connId;
  token = other256.token;
  source = other256.source;
  offset = other256.offset;
  metadata = other256.metadata;
  cursorName = other256.cursorName;
  warnings = other256.warnings;
  rowIdsForUpdateOrDelete = other256.rowIdsForUpdateOrDelete;
  __isset = other256.__isset;
  return *this;
}
RowSet& RowSet::operator=(RowSet&& other257) noexcept {
  rows = std::move(other257.rows);
  flags = std::move(other257.flags);
  cursorId = std::move(other257.cursorId);
  statementId = std::move(other257.statementId);
  connId = std::move(other257.connId);
  token = std::move(other257.token);
  source = std::move(other257.source);
  offset = std::move(other257.offset);
  metadata = std::move(other257.metadata);
  cursorName = std::move(other257.cursorName);
  warnings = std::move(other257.warnings);
  rowIdsForUpdateOrDelete = std::move(other257.rowIdsForUpdateOrDelete);
  __isset = std::move(other257.__isset);
  return *this;
}
void RowSet::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "RowSet(";
  out << "rows=" << to_string(rows);
  out << ", " << "flags=" << to_string(flags);
  out << ", " << "cursorId=" << to_string(cursorId);
  out << ", " << "statementId=" << to_string(statementId);
  out << ", " << "connId=" << to_string(connId);
  out << ", " << "token="; (__isset.token ? (out << to_string(token)) : (out << "<null>"));
  out << ", " << "source="; (__isset.source ? (out << to_string(source)) : (out << "<null>"));
  out << ", " << "offset="; (__isset.offset ? (out << to_string(offset)) : (out << "<null>"));
  out << ", " << "metadata="; (__isset.metadata ? (out << to_string(metadata)) : (out << "<null>"));
  out << ", " << "cursorName="; (__isset.cursorName ? (out << to_string(cursorName)) : (out << "<null>"));
  out << ", " << "warnings="; (__isset.warnings ? (out << to_string(warnings)) : (out << "<null>"));
  out << ", " << "rowIdsForUpdateOrDelete="; (__isset.rowIdsForUpdateOrDelete ? (out << to_string(rowIdsForUpdateOrDelete)) : (out << "<null>"));
  out << ")";
}

}}} // namespace
