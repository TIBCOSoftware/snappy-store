/**
 * Autogenerated by Thrift Compiler (1.0.0-dev)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#ifndef SNAPPYDATA_STRUCT_TIMESTAMP_H
#define SNAPPYDATA_STRUCT_TIMESTAMP_H


#include "snappydata_struct_Decimal.h"

#include "snappydata_types.h"

namespace io { namespace snappydata { namespace thrift {

typedef struct _Timestamp__isset {
  _Timestamp__isset() : nanos(false) {}
  bool nanos :1;
} _Timestamp__isset;

class Timestamp {
 public:

  Timestamp(const Timestamp&);
  Timestamp(Timestamp&&) noexcept;
  Timestamp& operator=(const Timestamp&);
  Timestamp& operator=(Timestamp&&) noexcept;
  Timestamp() : secsSinceEpoch(0), nanos(0) {
  }
  Timestamp(int64_t secs, int32_t ns) : secsSinceEpoch(secs), nanos(ns) {
  }

  virtual ~Timestamp();
  int64_t secsSinceEpoch;
  int32_t nanos;

  _Timestamp__isset __isset;

  void __set_secsSinceEpoch(const int64_t val);

  void __set_nanos(const int32_t val);

  bool operator == (const Timestamp & rhs) const
  {
    if (!(secsSinceEpoch == rhs.secsSinceEpoch))
      return false;
    if (__isset.nanos != rhs.__isset.nanos)
      return false;
    else if (__isset.nanos && !(nanos == rhs.nanos))
      return false;
    return true;
  }
  bool operator != (const Timestamp &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const Timestamp & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  virtual void printTo(std::ostream& out) const;
};

void swap(Timestamp &a, Timestamp &b) noexcept;

inline std::ostream& operator<<(std::ostream& out, const Timestamp& obj)
{
  obj.printTo(out);
  return out;
}

}}} // namespace

#endif
