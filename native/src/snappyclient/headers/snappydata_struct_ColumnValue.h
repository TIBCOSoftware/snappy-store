/**
 * Autogenerated by Thrift Compiler (0.10.0)
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
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

#ifndef SNAPPYDATA_STRUCT_COLUMNVALUE_H
#define SNAPPYDATA_STRUCT_COLUMNVALUE_H

#include "common/Base.h"

#include "snappydata_struct_Decimal.h"
#include "snappydata_struct_BlobChunk.h"
#include "snappydata_struct_ClobChunk.h"
#include "snappydata_struct_ServiceMetaData.h"
#include "snappydata_struct_ServiceMetaDataArgs.h"
#include "snappydata_struct_OpenConnectionArgs.h"
#include "snappydata_struct_ConnectionProperties.h"
#include "snappydata_struct_HostAddress.h"
#include "snappydata_struct_SnappyExceptionData.h"
#include "snappydata_struct_StatementAttrs.h"

#include "snappydata_types.h"

#include <boost/functional/hash.hpp>
#include <boost/variant.hpp>

#include <deque>
#include <unordered_map>
#include <vector>

namespace io { namespace snappydata {

template<typename ... T>
struct VariadicSize {
  static constexpr int size = sizeof...(T);
};

template<typename ... T>
struct VariadicSize<boost::variant<T...> > {
  static constexpr int size = sizeof...(T);
};

namespace thrift {
  class ColumnValue;
}

}} // namespace

namespace std {
  template<>
  struct hash<io::snappydata::thrift::ColumnValue> {
    std::size_t operator()(const io::snappydata::thrift::ColumnValue& v) const;
  };
}

namespace io { namespace snappydata { namespace thrift {

// boost::variant does not allow composition of multiple fields with the same
// types hence need to define separate types for storage of repeated types

class DateTime {
public:
  int64_t m_elapsed;

  DateTime(int64_t elapsed): m_elapsed(elapsed) {
  }

  bool operator==(const DateTime& other) const {
    return m_elapsed == other.m_elapsed;
  }
  bool operator!=(const DateTime& other) const {
    return m_elapsed != other.m_elapsed;
  }
};

class Date : public DateTime {
public:
  Date(int64_t secs) : DateTime(secs) {
  }
};

class Time : public DateTime {
public:
  Time(int64_t secs) : DateTime(secs) {
  }
};

class Timestamp : public DateTime {
public:
  Timestamp(int64_t totalNanos) : DateTime(totalNanos) {
  }
};

class Binary : public std::string {
public:
  Binary() {
  }
  Binary(const std::string& v) : std::string(v) {
  }
  Binary(const int8_t* bytes, int len) :
      std::string((const char*)bytes, len) {
  }
  Binary(Binary&& other) : std::string(std::move(other)) {
  }
  Binary(std::string&& other) : std::string(std::move(other)) {
  }
  Binary& operator=(Binary&& other) {
    std::string::operator=(std::move(other));
    return *this;
  }
  Binary& operator=(std::string&& other) {
    std::string::operator=(std::move(other));
    return *this;
  }
};

// TODO: Types of Array and Struct have been kept different below to allow
// using in boost::variant without having to define a class.
// The Array class can change into a proper class that enforces same type
// for all elements.

typedef std::deque<ColumnValue> Array;

typedef std::unordered_map<ColumnValue, ColumnValue> Map;

typedef std::vector<ColumnValue> Struct;

struct NullType {
  bool m_v;

  bool operator==(const NullType& other) const {
    return m_v == other.m_v;
  }
  bool operator!=(const NullType& other) const {
    return m_v != other.m_v;
  }
};

class JavaObject : public std::string {
public:
  JavaObject() {
  }
  JavaObject(const std::string& v) : std::string(v) {
  }
  JavaObject(const int8_t* bytes, int len) :
      std::string((const char*)bytes, len) {
  }
  JavaObject(JavaObject&& other) : std::string(std::move(other)) {
  }
  JavaObject(std::string&& other) : std::string(std::move(other)) {
  }
  JavaObject& operator=(JavaObject&& other) {
    std::string::operator=(std::move(other));
    return *this;
  }
  JavaObject& operator=(std::string&& other) {
    std::string::operator=(std::move(other));
    return *this;
  }
};

class ColumnValue {
private:
  // Types below should exactly match the types and be in the same order as in
  // snappydata.thrift for ColumnValue
  // Any changes to snappydata.thrift ColumnValue should be reflected below
  // as well as added to the s_typeMap below
  typedef boost::variant<
    bool, // BOOLEAN
    int8_t, // TINYINT
    int16_t, // SMALLINT
    int32_t, // INTEGER
    int64_t, // BIGINT
    float, // FLOAT
    double, // DOUBLE
    std::shared_ptr<std::string>, // VARCHAR
    std::shared_ptr<Decimal>, // DECIMAL
    Date, // DATE (epoch value)
    Time, // TIME (epoch value)
    Timestamp, // TIMESTAMP
    std::shared_ptr<Binary>, // VARBINARY
    std::shared_ptr<BlobChunk>, // BLOB
    std::shared_ptr<ClobChunk>, // CLOB
    std::shared_ptr<Array>, // ARRAY
    std::shared_ptr<Map>, // MAP
    std::shared_ptr<Struct>, // STRUCT
    NullType, // NULLTYPE
    std::shared_ptr<JavaObject> // JAVA_OBJECT
  > UnionType;
  UnionType m_val;

  static const std::pair<SnappyType::type, const char*>
  s_typeMap[VariadicSize<UnionType>::size];

  friend struct std::hash<io::snappydata::thrift::ColumnValue>;

public:

  ColumnValue() noexcept : m_val() {
  }

  ColumnValue(const ColumnValue& other) noexcept : m_val(other.m_val) {
  }

  ColumnValue(ColumnValue&& other) noexcept : m_val(std::move(other.m_val)) {
  }

  ColumnValue& operator=(const ColumnValue& other) noexcept;
  ColumnValue& operator=(ColumnValue&& other) noexcept;

  ~ColumnValue() {
  }

  bool operator == (const ColumnValue & rhs) const;
  bool operator != (const ColumnValue &rhs) const {
    return !(*this == rhs);
  }

  inline SnappyType::type getType() const noexcept {
    return s_typeMap[m_val.which()].first;
  }

  static const char* getThriftFieldName(int index) noexcept;

  template<typename Visitor>
  inline BOOST_VARIANT_AUX_GENERIC_RESULT_TYPE(typename Visitor::result_type)
  visit(const Visitor& visitor) const {
    return boost::apply_visitor(visitor, m_val);
  }

  template <typename T>
  inline T get() const {
    return boost::get<T>(m_val);
  }

  template <typename T>
  inline const std::shared_ptr<T>& getPtr() const {
    return boost::get<std::shared_ptr<T> >(m_val);
  }

  inline const std::shared_ptr<std::string> getString() const {
    return boost::get<std::shared_ptr<std::string> >(m_val);
  }

  template <typename T>
  inline const T* getOrNull() const BOOST_NOEXCEPT {
    return boost::get<T>(&m_val);
  }

  template <typename T>
  inline void set(const T& v) {
    m_val = v;
  }

  inline void setString(const std::string& val) {
    m_val = std::move(std::shared_ptr<std::string>(new std::string(val)));
  }

  inline void setString(std::string&& val) {
    m_val = std::move(std::shared_ptr<std::string>(
        new std::string(std::move(val))));
  }

  inline void setString(const char* val) {
    m_val = std::move(std::shared_ptr<std::string>(new std::string(val)));
  }

  // TODO: PERF: we could change thrift impl to carry through char* instead
  // of copying to std::string for cases where execute is going to be fired
  // immediately after setting the parameters. Same for setBinary.
  // Similarly when getting column values as result, we could send a char*
  // buffer to read the result into instead of creating a new std::string
  // (though it is dangerous and should avoid unless perf benefit is proven)
  inline void setString(const char* val, const size_t len) {
    if (len < 0) {
      m_val = std::move(std::shared_ptr<std::string>(new std::string(val)));
    } else {
      m_val = std::move(std::shared_ptr<std::string>(new std::string(val,
          len)));
    }
  }

  inline void setDecimal(const Decimal& val) {
    m_val = std::move(std::shared_ptr<Decimal>(new Decimal(val)));
  }

  inline void setDecimal(Decimal&& val) {
    m_val = std::move(std::shared_ptr<Decimal>(new Decimal(std::move(val))));
  }

  inline bool isNull() const BOOST_NOEXCEPT {
    auto v = getOrNull<NullType>();
    return v != NULL && v->m_v;
  }

  inline void setIsNull(bool val) {
    NullType v = { val };
    m_val = v;
  }

  void setBinary(const std::string& val);
  void setBinary(std::string&& val);

  void setBlob(const BlobChunk& val);
  void setBlob(BlobChunk&& val);

  void setClob(const ClobChunk& val);
  void setClob(ClobChunk&& val);

  void setArray(const Array& val);
  void setArray(Array&& val);

  void setMap(const Map& val);
  void setMap(Map&& val);

  void setStruct(const Struct& val);
  void setStruct(Struct&& val);

  void setJavaVal(const std::string& val);
  void setJavaVal(std::string&& val);

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  void swap(ColumnValue& other);
  void printTo(std::ostream& out) const;
};

void swap(ColumnValue &a, ColumnValue &b);

inline std::ostream& operator<<(std::ostream& out, const ColumnValue& obj) {
  obj.printTo(out);
  return out;
}

inline std::ostream& operator<<(std::ostream& out, const DateTime& v) {
  return out << v.m_elapsed;
}
inline std::ostream& operator<<(std::ostream& out, const NullType& v) {
  return out << v.m_v;
}

inline size_t hash_value(const io::snappydata::thrift::DateTime& v) {
  return boost::hash_value(v.m_elapsed);
}
inline size_t hash_value(const io::snappydata::thrift::NullType& v) {
  return boost::hash_value(v.m_v);
}

}}} // namespace

inline std::size_t std::hash<io::snappydata::thrift::ColumnValue>::operator()(
    const io::snappydata::thrift::ColumnValue& v) const {
  return boost::hash_value(v.m_val);
}

#endif
