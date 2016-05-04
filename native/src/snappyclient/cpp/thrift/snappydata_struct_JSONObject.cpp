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
#include "snappydata_struct_JSONObject.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


JSONObject::~JSONObject() throw() {
}


void JSONObject::__set_pairs(const std::map<std::string, JSONValue> & val) {
  this->pairs = val;
__isset.pairs = true;
}

void JSONObject::__set_array(const std::vector<JSONValue> & val) {
  this->array = val;
__isset.array = true;
}

uint32_t JSONObject::read(::apache::thrift::protocol::TProtocol* iprot) {

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
        if (ftype == ::apache::thrift::protocol::T_MAP) {
          {
            this->pairs.clear();
            uint32_t _size26;
            ::apache::thrift::protocol::TType _ktype27;
            ::apache::thrift::protocol::TType _vtype28;
            xfer += iprot->readMapBegin(_ktype27, _vtype28, _size26);
            uint32_t _i30;
            for (_i30 = 0; _i30 < _size26; ++_i30)
            {
              std::string _key31;
              xfer += iprot->readString(_key31);
              JSONValue& _val32 = this->pairs[_key31];
              xfer += _val32.read(iprot);
            }
            xfer += iprot->readMapEnd();
          }
          this->__isset.pairs = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->array.clear();
            uint32_t _size33;
            ::apache::thrift::protocol::TType _etype36;
            xfer += iprot->readListBegin(_etype36, _size33);
            this->array.resize(_size33);
            uint32_t _i37;
            for (_i37 = 0; _i37 < _size33; ++_i37)
            {
              xfer += this->array[_i37].read(iprot);
            }
            xfer += iprot->readListEnd();
          }
          this->__isset.array = true;
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

uint32_t JSONObject::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("JSONObject");

  if (this->__isset.pairs) {
    xfer += oprot->writeFieldBegin("pairs", ::apache::thrift::protocol::T_MAP, 1);
    {
      xfer += oprot->writeMapBegin(::apache::thrift::protocol::T_STRING, ::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->pairs.size()));
      std::map<std::string, JSONValue> ::const_iterator _iter38;
      for (_iter38 = this->pairs.begin(); _iter38 != this->pairs.end(); ++_iter38)
      {
        xfer += oprot->writeString(_iter38->first);
        xfer += _iter38->second.write(oprot);
      }
      xfer += oprot->writeMapEnd();
    }
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.array) {
    xfer += oprot->writeFieldBegin("array", ::apache::thrift::protocol::T_LIST, 2);
    {
      xfer += oprot->writeListBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->array.size()));
      std::vector<JSONValue> ::const_iterator _iter39;
      for (_iter39 = this->array.begin(); _iter39 != this->array.end(); ++_iter39)
      {
        xfer += (*_iter39).write(oprot);
      }
      xfer += oprot->writeListEnd();
    }
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(JSONObject &a, JSONObject &b) {
  using ::std::swap;
  swap(a.pairs, b.pairs);
  swap(a.array, b.array);
  swap(a.__isset, b.__isset);
}

JSONObject::JSONObject(const JSONObject& other40) {
  pairs = other40.pairs;
  array = other40.array;
  __isset = other40.__isset;
}
JSONObject::JSONObject( JSONObject&& other41) {
  pairs = std::move(other41.pairs);
  array = std::move(other41.array);
  __isset = std::move(other41.__isset);
}
JSONObject& JSONObject::operator=(const JSONObject& other42) {
  pairs = other42.pairs;
  array = other42.array;
  __isset = other42.__isset;
  return *this;
}
JSONObject& JSONObject::operator=(JSONObject&& other43) {
  pairs = std::move(other43.pairs);
  array = std::move(other43.array);
  __isset = std::move(other43.__isset);
  return *this;
}
void JSONObject::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "JSONObject(";
  out << "pairs="; (__isset.pairs ? (out << to_string(pairs)) : (out << "<null>"));
  out << ", " << "array="; (__isset.array ? (out << to_string(array)) : (out << "<null>"));
  out << ")";
}

}}} // namespace
