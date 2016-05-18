/**
 * Autogenerated by Thrift Compiler (1.0.0-dev)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

/*
 * Changes for GemFireXD distributed data platform.
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
/*
 * Changes for SnappyData product.
 *
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

#include <iosfwd>

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include <thrift/cxxfunctional.h>
#include "snappydata_struct_ColumnValue.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>
#include "Utils.h"

using namespace io::snappydata::thrift;

namespace {
  class ValueEquality : public boost::static_visitor<bool> {
  public:
    template<typename T, typename U>
    bool operator()(const T& lhs, const U& rhs) const {
      // cannot compare different types
      return false;
    }

    template<typename T>
    bool operator()(const std::shared_ptr<T>& lhs,
        const std::shared_ptr<T>& rhs) const {
      // compare the objects inside shared_ptr's and not pointer equality
      return *lhs == *rhs;
    }

    template<typename T>
    bool operator()(const T& lhs, const T& rhs) const {
      return lhs == rhs;
    }
  };

  class Serializer : public boost::static_visitor<uint32_t> {
  private:
    ::apache::thrift::protocol::TProtocol*const m_oprot;

  public:
    Serializer(::apache::thrift::protocol::TProtocol* oprot) :
        m_oprot(oprot) {
    }

    uint32_t operator()(const bool& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("bool_val",
          ::apache::thrift::protocol::T_BOOL, 1);
      xfer += m_oprot->writeBool(v);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const int8_t& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("byte_val",
          ::apache::thrift::protocol::T_BYTE, 2);
      xfer += m_oprot->writeByte(v);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const int16_t& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("i16_val",
          ::apache::thrift::protocol::T_I16, 3);
      xfer += m_oprot->writeI16(v);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const int32_t& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("i32_val",
          ::apache::thrift::protocol::T_I32, 4);
      xfer += m_oprot->writeI32(v);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const int64_t& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("i64_val",
          ::apache::thrift::protocol::T_I64, 5);
      xfer += m_oprot->writeI64(v);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const float& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("float_val",
          ::apache::thrift::protocol::T_I32, 6);
      xfer += m_oprot->writeI32(io::snappydata::client::Utils::float2Int32(v));
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const double& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("double_val",
          ::apache::thrift::protocol::T_DOUBLE, 7);
      xfer += m_oprot->writeDouble(v);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const std::shared_ptr<std::string>& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("string_val",
          ::apache::thrift::protocol::T_STRING, 8);
      xfer += m_oprot->writeString(*v);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const std::shared_ptr<Decimal>& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("decimal_val",
          ::apache::thrift::protocol::T_STRUCT, 9);
      xfer += v->write(m_oprot);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const Date& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("date_val",
          ::apache::thrift::protocol::T_STRUCT, 10);
      xfer += v.write(m_oprot);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const Time& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("time_val",
          ::apache::thrift::protocol::T_STRUCT, 11);
      xfer += v.write(m_oprot);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const Timestamp& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("timestamp_val",
          ::apache::thrift::protocol::T_STRUCT, 12);
      xfer += v.write(m_oprot);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const std::shared_ptr<Binary>& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("binary_val",
          ::apache::thrift::protocol::T_STRING, 13);
      xfer += m_oprot->writeBinary(*v);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const std::shared_ptr<BlobChunk>& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("blob_val",
          ::apache::thrift::protocol::T_STRUCT, 14);
      xfer += v->write(m_oprot);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const std::shared_ptr<ClobChunk>& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("clob_val",
          ::apache::thrift::protocol::T_STRUCT, 15);
      xfer += v->write(m_oprot);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const std::shared_ptr<Array>& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("array_val",
          ::apache::thrift::protocol::T_LIST, 16);
      xfer += m_oprot->writeListBegin(::apache::thrift::protocol::T_STRUCT,
          static_cast<uint32_t>(v->size()));
      auto _iter229 = v->cbegin();
      auto _iter229_end = v->cend();
      while (_iter229 != _iter229_end) {
        xfer += (*_iter229).write(m_oprot);
        ++_iter229;
      }
      xfer += m_oprot->writeListEnd();
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const std::shared_ptr<Map>& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("map_val",
          ::apache::thrift::protocol::T_MAP, 17);
      xfer += m_oprot->writeMapBegin(::apache::thrift::protocol::T_STRUCT,
          ::apache::thrift::protocol::T_STRUCT,
           static_cast<uint32_t>(v->size()));
      auto _iter230 = v->cbegin();
      auto _iter230_end = v->cend();
      while (_iter230 != _iter230_end) {
        xfer += _iter230->first.write(m_oprot);
        xfer += _iter230->second.write(m_oprot);
        ++_iter230;
      }
      xfer += m_oprot->writeMapEnd();
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const std::shared_ptr<Struct>& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("struct_val",
          ::apache::thrift::protocol::T_LIST, 18);
      xfer += m_oprot->writeListBegin(::apache::thrift::protocol::T_STRUCT,
          static_cast<uint32_t>(v->size()));
      auto _iter231 = v->cbegin();
      auto _iter231_end = v->cend();
      while (_iter231 != _iter231_end) {
        xfer += (*_iter231).write(m_oprot);
        ++_iter231;
      }
      xfer += m_oprot->writeListEnd();
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const NullType& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("null_val",
          ::apache::thrift::protocol::T_BOOL, 16);
      xfer += m_oprot->writeBool(v.m_v);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const std::shared_ptr<JSONObject>& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("json_val",
          ::apache::thrift::protocol::T_STRUCT, 18);
      xfer += v->write(m_oprot);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }

    uint32_t operator()(const std::shared_ptr<JavaObject>& v) const {
      uint32_t xfer = 0;
      xfer += m_oprot->writeFieldBegin("java_val",
          ::apache::thrift::protocol::T_STRING, 19);
      xfer += m_oprot->writeBinary(*v);
      xfer += m_oprot->writeFieldEnd();
      return xfer;
    }
  };

  class PrintToStream : public boost::static_visitor<> {
  private:
    std::ostream& m_out;
    const int m_which;

  public:
    PrintToStream(std::ostream& out, const int which) :
        m_out(out), m_which(which) {
    }

    /** special override for BinaryType (which is a string) to print as hex */
    void operator()(const std::shared_ptr<Binary>& v) const {
      // print the bytes in hex form and not as string
      m_out << "binary_val=0x";
      io::snappydata::client::Utils::toHexString(v->data(), v->size(), m_out);
    }

    void operator()(const std::shared_ptr<Array>& v) const {
      // print the object inside shared_ptr, not the pointer
      // container types don't define << overloads, so use thrift to_string
      m_out << "array_val=(";
      auto iter = v->cbegin();
      auto iterEnd = v->cend();
      if (iter != iterEnd) {
        iter->visit(*this);
        ++iter;
        while (iter != iterEnd) {
          m_out << ", ";
          iter->visit(*this);
          ++iter;
        }
      }
      m_out << ')';
    }

    void operator()(const std::shared_ptr<Map>& v) const {
      // print the object inside shared_ptr, not the pointer
      // container types don't define << overloads, so use thrift to_string
      m_out << "map_val=";
      auto iter = v->cbegin();
      auto iterEnd = v->cend();
      bool firstCall = true;
      while (iter != iterEnd) {
        if (firstCall) {
          firstCall = false;
        } else {
          m_out << ", ";
        }
        iter->first.visit(*this);
        m_out << " = ";
        iter->second.visit(*this);
        ++iter;
      }
      m_out << ')';
    }

    template<typename T>
    void operator()(const std::shared_ptr<T>& v) const {
      // print the object inside shared_ptr, not the pointer
      // container types don't define << overloads, so use thrift to_string
      m_out << ColumnValue::getThriftFieldName(m_which) << '='
          << apache::thrift::to_string(*v);
    }

    template<typename T>
    void operator()(const T& v) const {
      m_out << ColumnValue::getThriftFieldName(m_which) << '=' << v;
    }
  };
}

ColumnValue& ColumnValue::operator=(const ColumnValue& other) noexcept {
  m_val = other.m_val;
  return *this;
}
ColumnValue& ColumnValue::operator=(ColumnValue&& other) noexcept {
  m_val = std::move(other.m_val);
  return *this;
}

bool ColumnValue::operator ==(const ColumnValue & rhs) const {
  return boost::apply_visitor(::ValueEquality(), m_val, rhs.m_val);
}

const char* ColumnValue::getThriftFieldName(int index) noexcept {
  return s_typeMap[index].second;
}

void ColumnValue::setBinary(const std::string& val) {
  m_val = std::move(std::shared_ptr<Binary>(new Binary(val)));
}

void ColumnValue::setBinary(std::string&& val) {
  m_val = std::move(std::shared_ptr<Binary>(new Binary(std::move(val))));
}

void ColumnValue::setBlob(const BlobChunk& val) {
  m_val = std::move(std::shared_ptr<BlobChunk>(new BlobChunk(val)));
}

void ColumnValue::setBlob(BlobChunk&& val) {
  m_val = std::move(
      std::shared_ptr<BlobChunk>(new BlobChunk(std::move(val))));
}

void ColumnValue::setClob(const ClobChunk& val) {
  m_val = std::move(std::shared_ptr<ClobChunk>(new ClobChunk(val)));
}

void ColumnValue::setClob(ClobChunk&& val) {
  m_val = std::move(
      std::shared_ptr<ClobChunk>(new ClobChunk(std::move(val))));
}

void ColumnValue::setArray(const Array& val) {
  m_val = std::move(std::shared_ptr<Array>(new Array(val)));
}

void ColumnValue::setArray(Array&& val) {
  m_val = std::move(std::shared_ptr<Array>(new Array(std::move(val))));
}

void ColumnValue::setMap(const Map& val) {
  m_val = std::move(std::shared_ptr<Map>(new Map(val)));
}

void ColumnValue::setMap(Map&& val) {
  m_val = std::move(std::shared_ptr<Map>(new Map(std::move(val))));
}

void ColumnValue::setStruct(const Struct& val) {
  m_val = std::move(std::shared_ptr<Struct>(new Struct(val)));
}

void ColumnValue::setStruct(Struct&& val) {
  m_val = std::move(std::shared_ptr<Struct>(new Struct(std::move(val))));
}

void ColumnValue::setJavaVal(const std::string& val) {
  m_val = std::move(std::shared_ptr<JavaObject>(new JavaObject(val)));
}

void ColumnValue::setJavaVal(std::string&& val) {
  m_val = std::move(
      std::shared_ptr<JavaObject>(new JavaObject(std::move(val))));
}

uint32_t ColumnValue::read(::apache::thrift::protocol::TProtocol* iprot) {
  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  while (true) {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid) {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_BOOL) {
          bool v;
          xfer += iprot->readBool(v);
          m_val = v;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_BYTE) {
          int8_t v;
          xfer += iprot->readByte(v);
          m_val = v;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_I16) {
          int16_t v;
          xfer += iprot->readI16(v);
          m_val = v;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          int32_t v;
          xfer += iprot->readI32(v);
          m_val = v;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 5:
        if (ftype == ::apache::thrift::protocol::T_I64) {
          int64_t v;
          xfer += iprot->readI64(v);
          m_val = v;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 6:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          int32_t v;
          xfer += iprot->readI32(v);
          m_val = io::snappydata::client::Utils::int32ToFloat(v);
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 7:
        if (ftype == ::apache::thrift::protocol::T_DOUBLE) {
          double v;
          xfer += iprot->readDouble(v);
          m_val = v;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 8:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          auto v = std::shared_ptr<std::string>(new std::string());
          xfer += iprot->readString(*v);
          m_val = std::move(v);
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 9:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          auto v = std::shared_ptr<Decimal>(new Decimal());
          xfer += v->read(iprot);
          m_val = std::move(v);
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 10:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          Date v;
          xfer += v.read(iprot);
          m_val = v;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 11:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          Time v;
          xfer += v.read(iprot);
          m_val = v;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 12:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          Timestamp v;
          xfer += v.read(iprot);
          m_val = v;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 13:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          auto v = std::shared_ptr<Binary>(new Binary());
          xfer += iprot->readBinary(*v);
          m_val = std::move(v);
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 14:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          auto v = std::shared_ptr<BlobChunk>(new BlobChunk());
          xfer += v->read(iprot);
          m_val = std::move(v);
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 15:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          auto v = std::shared_ptr<ClobChunk>(new ClobChunk());
          xfer += v->read(iprot);
          m_val = std::move(v);
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 16:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          uint32_t _size212;
          ::apache::thrift::protocol::TType _etype215;
          xfer += iprot->readListBegin(_etype215, _size212);
          auto v = std::shared_ptr<Array>(new Array(_size212));
          uint32_t _i216;
          for (_i216 = 0; _i216 < _size212; ++_i216) {
            ColumnValue c;
            xfer += c.read(iprot);
            v->push_back(std::move(c));
          }
          xfer += iprot->readListEnd();
          m_val = std::move(v);
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 17:
        if (ftype == ::apache::thrift::protocol::T_MAP) {
          uint32_t _size217;
          ::apache::thrift::protocol::TType _ktype218;
          ::apache::thrift::protocol::TType _vtype219;
          xfer += iprot->readMapBegin(_ktype218, _vtype219, _size217);
          auto v = std::shared_ptr<Map>(new Map());
          uint32_t _i221;
          for (_i221 = 0; _i221 < _size217; ++_i221) {
            ColumnValue _key222, _val223;
            xfer += _key222.read(iprot);
            xfer += _val223.read(iprot);
            // create the pair in-place
            v->emplace(_key222, _val223);
          }
          xfer += iprot->readMapEnd();
          m_val = std::move(v);
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 18:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          uint32_t _size224;
          ::apache::thrift::protocol::TType _etype227;
          xfer += iprot->readListBegin(_etype227, _size224);
          auto v = std::shared_ptr<Struct>(new Struct(_size224));
          uint32_t _i228;
          for (_i228 = 0; _i228 < _size224; ++_i228) {
            ColumnValue c;
            xfer += c.read(iprot);
            v->push_back(std::move(c));
          }
          xfer += iprot->readListEnd();
          m_val = std::move(v);
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 19:
        if (ftype == ::apache::thrift::protocol::T_BOOL) {
          bool v;
          xfer += iprot->readBool(v);
          m_val = v;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 20:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          auto v = std::shared_ptr<JSONObject>(new JSONObject());
          xfer += v->read(iprot);
          m_val = std::move(v);
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 21:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          auto v = std::shared_ptr<JavaObject>(new JavaObject());
          xfer += iprot->readBinary(*v);
          m_val = std::move(v);
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

uint32_t ColumnValue::write(
    ::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("ColumnValue");

  xfer += boost::apply_visitor(::Serializer(oprot), m_val);

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void ColumnValue::swap(ColumnValue& other) noexcept {
  std::swap(m_val, other.m_val);
}

void swap(ColumnValue &a, ColumnValue &b) noexcept {
  a.swap(b);
}

void ColumnValue::printTo(std::ostream& out) const {
  visit(::PrintToStream(out, m_val.which()));
}
