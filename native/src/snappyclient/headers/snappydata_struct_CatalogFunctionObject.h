/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#ifndef SNAPPYDATA_STRUCT_CATALOGFUNCTIONOBJECT_H
#define SNAPPYDATA_STRUCT_CATALOGFUNCTIONOBJECT_H


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
#include "snappydata_struct_RowSet.h"
#include "snappydata_struct_PrepareResult.h"
#include "snappydata_struct_UpdateResult.h"
#include "snappydata_struct_StatementResult.h"
#include "snappydata_struct_BucketOwners.h"
#include "snappydata_struct_CatalogStorage.h"
#include "snappydata_struct_CatalogSchemaObject.h"
#include "snappydata_struct_CatalogStats.h"
#include "snappydata_struct_CatalogTableObject.h"

#include "snappydata_types.h"

namespace io { namespace snappydata { namespace thrift {

typedef struct _CatalogFunctionObject__isset {
  _CatalogFunctionObject__isset() : schemaName(false) {}
  bool schemaName :1;
} _CatalogFunctionObject__isset;

class CatalogFunctionObject {
 public:

  CatalogFunctionObject(const CatalogFunctionObject&);
  CatalogFunctionObject(CatalogFunctionObject&&) noexcept;
  CatalogFunctionObject& operator=(const CatalogFunctionObject&);
  CatalogFunctionObject& operator=(CatalogFunctionObject&&) noexcept;
  CatalogFunctionObject() : functionName(), schemaName(), className() {
  }

  virtual ~CatalogFunctionObject() noexcept;
  std::string functionName;
  std::string schemaName;
  std::string className;
  std::vector<std::string>  resources;

  _CatalogFunctionObject__isset __isset;

  void __set_functionName(const std::string& val);

  void __set_schemaName(const std::string& val);

  void __set_className(const std::string& val);

  void __set_resources(const std::vector<std::string> & val);

  bool operator == (const CatalogFunctionObject & rhs) const
  {
    if (!(functionName == rhs.functionName))
      return false;
    if (__isset.schemaName != rhs.__isset.schemaName)
      return false;
    else if (__isset.schemaName && !(schemaName == rhs.schemaName))
      return false;
    if (!(className == rhs.className))
      return false;
    if (!(resources == rhs.resources))
      return false;
    return true;
  }
  bool operator != (const CatalogFunctionObject &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const CatalogFunctionObject & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  virtual void printTo(std::ostream& out) const;
};

void swap(CatalogFunctionObject &a, CatalogFunctionObject &b);

inline std::ostream& operator<<(std::ostream& out, const CatalogFunctionObject& obj)
{
  obj.printTo(out);
  return out;
}

}}} // namespace

#endif
