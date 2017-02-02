/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#ifndef SNAPPYDATA_STRUCT_ROWSET_H
#define SNAPPYDATA_STRUCT_ROWSET_H


#include "snappydata_struct_Decimal.h"
#include "snappydata_struct_BlobChunk.h"
#include "snappydata_struct_ClobChunk.h"
#include "snappydata_struct_TransactionXid.h"
#include "snappydata_struct_ServiceMetaData.h"
#include "snappydata_struct_ServiceMetaDataArgs.h"
#include "snappydata_struct_OpenConnectionArgs.h"
#include "snappydata_struct_ConnectionProperties.h"
#include "snappydata_struct_HostAddress.h"
#include "snappydata_struct_SnappyExceptionData.h"
#include "snappydata_struct_StatementAttrs.h"
#include "snappydata_struct_ColumnValue.h"
#include "snappydata_struct_ColumnDescriptor.h"
#include "snappydata_struct_Row.h"
#include "snappydata_struct_OutputParameter.h"

#include "snappydata_types.h"

namespace io { namespace snappydata { namespace thrift {

typedef struct _RowSet__isset {
  _RowSet__isset() : token(false), source(false), offset(false), metadata(false), cursorName(false), warnings(false), rowIdsForUpdateOrDelete(false) {}
  bool token :1;
  bool source :1;
  bool offset :1;
  bool metadata :1;
  bool cursorName :1;
  bool warnings :1;
  bool rowIdsForUpdateOrDelete :1;
} _RowSet__isset;

class RowSet {
 public:

  RowSet(const RowSet&);
  RowSet(RowSet&&) noexcept;
  RowSet& operator=(const RowSet&);
  RowSet& operator=(RowSet&&) noexcept;
  RowSet() : flags(0), cursorId(0), statementId(0), connId(0), token(), offset(0), cursorName() {
  }

  virtual ~RowSet() noexcept;
  std::vector<Row>  rows;
  int8_t flags;
  int64_t cursorId;
  int64_t statementId;
  int64_t connId;
  std::string token;
  HostAddress source;
  int32_t offset;
  std::vector<ColumnDescriptor>  metadata;
  std::string cursorName;
  SnappyExceptionData warnings;
  std::vector<int64_t>  rowIdsForUpdateOrDelete;

  _RowSet__isset __isset;

  void __set_rows(const std::vector<Row> & val);

  void __set_flags(const int8_t val);

  void __set_cursorId(const int64_t val);

  void __set_statementId(const int64_t val);

  void __set_connId(const int64_t val);

  void __set_token(const std::string& val);

  void __set_source(const HostAddress& val);

  void __set_offset(const int32_t val);

  void __set_metadata(const std::vector<ColumnDescriptor> & val);

  void __set_cursorName(const std::string& val);

  void __set_warnings(const SnappyExceptionData& val);

  void __set_rowIdsForUpdateOrDelete(const std::vector<int64_t> & val);

  bool operator == (const RowSet & rhs) const
  {
    if (!(rows == rhs.rows))
      return false;
    if (!(flags == rhs.flags))
      return false;
    if (!(cursorId == rhs.cursorId))
      return false;
    if (!(statementId == rhs.statementId))
      return false;
    if (!(connId == rhs.connId))
      return false;
    if (__isset.token != rhs.__isset.token)
      return false;
    else if (__isset.token && !(token == rhs.token))
      return false;
    if (__isset.source != rhs.__isset.source)
      return false;
    else if (__isset.source && !(source == rhs.source))
      return false;
    if (__isset.offset != rhs.__isset.offset)
      return false;
    else if (__isset.offset && !(offset == rhs.offset))
      return false;
    if (__isset.metadata != rhs.__isset.metadata)
      return false;
    else if (__isset.metadata && !(metadata == rhs.metadata))
      return false;
    if (__isset.cursorName != rhs.__isset.cursorName)
      return false;
    else if (__isset.cursorName && !(cursorName == rhs.cursorName))
      return false;
    if (__isset.warnings != rhs.__isset.warnings)
      return false;
    else if (__isset.warnings && !(warnings == rhs.warnings))
      return false;
    if (__isset.rowIdsForUpdateOrDelete != rhs.__isset.rowIdsForUpdateOrDelete)
      return false;
    else if (__isset.rowIdsForUpdateOrDelete && !(rowIdsForUpdateOrDelete == rhs.rowIdsForUpdateOrDelete))
      return false;
    return true;
  }
  bool operator != (const RowSet &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const RowSet & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  virtual void printTo(std::ostream& out) const;
};

void swap(RowSet &a, RowSet &b) noexcept;

inline std::ostream& operator<<(std::ostream& out, const RowSet& obj)
{
  obj.printTo(out);
  return out;
}

}}} // namespace

#endif
