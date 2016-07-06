/**
 * Autogenerated by Thrift Compiler (1.0.0-dev)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#ifndef SNAPPYDATA_STRUCT_DATETIME_H
#define SNAPPYDATA_STRUCT_DATETIME_H


#include "snappydata_struct_Decimal.h"
#include "snappydata_struct_Timestamp.h"
#include "snappydata_struct_JSONValue.h"
#include "snappydata_struct_JSONObject.h"
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

namespace io { namespace snappydata { namespace thrift {


class DateTime {
 public:

  DateTime(const DateTime&);
  DateTime(DateTime&&) noexcept;
  DateTime& operator=(const DateTime&);
  DateTime& operator=(DateTime&&) noexcept;
  DateTime() : secsSinceEpoch(0) {
  }
  DateTime(int64_t secs) : secsSinceEpoch(secs) {
  }

  virtual ~DateTime();
  int64_t secsSinceEpoch;

  void __set_secsSinceEpoch(const int64_t val);

  bool operator == (const DateTime & rhs) const
  {
    if (!(secsSinceEpoch == rhs.secsSinceEpoch))
      return false;
    return true;
  }
  bool operator != (const DateTime &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const DateTime & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  virtual void printTo(std::ostream& out) const;
};

void swap(DateTime &a, DateTime &b) noexcept;

inline std::ostream& operator<<(std::ostream& out, const DateTime& obj) {
  obj.printTo(out);
  return out;
}

}}} // namespace

#endif
