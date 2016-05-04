/**
 * Autogenerated by Thrift Compiler (1.0.0-dev)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#ifndef SNAPPYDATA_STRUCT_JSONVALUE_H
#define SNAPPYDATA_STRUCT_JSONVALUE_H


#include "snappydata_struct_Decimal.h"
#include "snappydata_struct_Timestamp.h"

#include "snappydata_types.h"

namespace io { namespace snappydata { namespace thrift {

typedef struct _JSONValue__isset {
  _JSONValue__isset() : string_val(false), bool_val(false), i32_val(false), i64_val(false), double_val(false), object_val(false), array_val(false), null_val(false) {}
  bool string_val :1;
  bool bool_val :1;
  bool i32_val :1;
  bool i64_val :1;
  bool double_val :1;
  bool object_val :1;
  bool array_val :1;
  bool null_val :1;
} _JSONValue__isset;

class JSONValue {
 public:

  JSONValue(const JSONValue&);
  JSONValue(JSONValue&&);
  JSONValue& operator=(const JSONValue&);
  JSONValue& operator=(JSONValue&&);
  JSONValue() : string_val(), bool_val(0), i32_val(0), i64_val(0), double_val(0), null_val(0) {
  }

  virtual ~JSONValue() throw();
  std::string string_val;
  bool bool_val;
  int32_t i32_val;
  int64_t i64_val;
  double double_val;
  std::map<std::string, JSONValue>  object_val;
  std::vector<JSONValue>  array_val;
  bool null_val;

  _JSONValue__isset __isset;

  void __set_string_val(const std::string& val);

  void __set_bool_val(const bool val);

  void __set_i32_val(const int32_t val);

  void __set_i64_val(const int64_t val);

  void __set_double_val(const double val);

  void __set_object_val(const std::map<std::string, JSONValue> & val);

  void __set_array_val(const std::vector<JSONValue> & val);

  void __set_null_val(const bool val);

  bool operator == (const JSONValue & rhs) const
  {
    if (__isset.string_val != rhs.__isset.string_val)
      return false;
    else if (__isset.string_val && !(string_val == rhs.string_val))
      return false;
    if (__isset.bool_val != rhs.__isset.bool_val)
      return false;
    else if (__isset.bool_val && !(bool_val == rhs.bool_val))
      return false;
    if (__isset.i32_val != rhs.__isset.i32_val)
      return false;
    else if (__isset.i32_val && !(i32_val == rhs.i32_val))
      return false;
    if (__isset.i64_val != rhs.__isset.i64_val)
      return false;
    else if (__isset.i64_val && !(i64_val == rhs.i64_val))
      return false;
    if (__isset.double_val != rhs.__isset.double_val)
      return false;
    else if (__isset.double_val && !(double_val == rhs.double_val))
      return false;
    if (__isset.object_val != rhs.__isset.object_val)
      return false;
    else if (__isset.object_val && !(object_val == rhs.object_val))
      return false;
    if (__isset.array_val != rhs.__isset.array_val)
      return false;
    else if (__isset.array_val && !(array_val == rhs.array_val))
      return false;
    if (__isset.null_val != rhs.__isset.null_val)
      return false;
    else if (__isset.null_val && !(null_val == rhs.null_val))
      return false;
    return true;
  }
  bool operator != (const JSONValue &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const JSONValue & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  virtual void printTo(std::ostream& out) const;
};

void swap(JSONValue &a, JSONValue &b);

inline std::ostream& operator<<(std::ostream& out, const JSONValue& obj)
{
  obj.printTo(out);
  return out;
}

}}} // namespace

#endif
