/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#ifndef SNAPPYDATA_STRUCT_SNAPPYEXCEPTIONDATA_H
#define SNAPPYDATA_STRUCT_SNAPPYEXCEPTIONDATA_H


#include "snappydata_struct_Decimal.h"
#include "snappydata_struct_BlobChunk.h"
#include "snappydata_struct_ClobChunk.h"
#include "snappydata_struct_TransactionXid.h"
#include "snappydata_struct_ServiceMetaData.h"
#include "snappydata_struct_ServiceMetaDataArgs.h"
#include "snappydata_struct_OpenConnectionArgs.h"
#include "snappydata_struct_ConnectionProperties.h"
#include "snappydata_struct_HostAddress.h"

#include "snappydata_types.h"

namespace io { namespace snappydata { namespace thrift {

typedef struct _SnappyExceptionData__isset {
  _SnappyExceptionData__isset() : sqlState(false), updateCounts(false) {}
  bool sqlState :1;
  bool updateCounts :1;
} _SnappyExceptionData__isset;

class SnappyExceptionData {
 public:

  SnappyExceptionData(const SnappyExceptionData&);
  SnappyExceptionData(SnappyExceptionData&&) noexcept;
  SnappyExceptionData& operator=(const SnappyExceptionData&);
  SnappyExceptionData& operator=(SnappyExceptionData&&) noexcept;
  SnappyExceptionData() : reason(), errorCode(0), sqlState() {
  }

  virtual ~SnappyExceptionData() noexcept;
  std::string reason;
  int32_t errorCode;
  std::string sqlState;
  std::vector<int32_t>  updateCounts;

  _SnappyExceptionData__isset __isset;

  void __set_reason(const std::string& val);

  void __set_errorCode(const int32_t val);

  void __set_sqlState(const std::string& val);

  void __set_updateCounts(const std::vector<int32_t> & val);

  bool operator == (const SnappyExceptionData & rhs) const
  {
    if (!(reason == rhs.reason))
      return false;
    if (!(errorCode == rhs.errorCode))
      return false;
    if (__isset.sqlState != rhs.__isset.sqlState)
      return false;
    else if (__isset.sqlState && !(sqlState == rhs.sqlState))
      return false;
    if (__isset.updateCounts != rhs.__isset.updateCounts)
      return false;
    else if (__isset.updateCounts && !(updateCounts == rhs.updateCounts))
      return false;
    return true;
  }
  bool operator != (const SnappyExceptionData &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const SnappyExceptionData & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  virtual void printTo(std::ostream& out) const;
};

void swap(SnappyExceptionData &a, SnappyExceptionData &b);

inline std::ostream& operator<<(std::ostream& out, const SnappyExceptionData& obj)
{
  obj.printTo(out);
  return out;
}

}}} // namespace

#endif
