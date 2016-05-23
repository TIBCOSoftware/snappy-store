/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

#ifndef CLIENTBASE_H_
#define CLIENTBASE_H_

#include "common/Base.h"
#include "common/SystemProperties.h"
#include "messages/SQLStateMessage.h"

#include "snappydata_constants.h"
#include "snappydata_types.h"
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
#include "snappydata_struct_DateTime.h"
#include "snappydata_struct_ColumnValue.h"
#include "snappydata_struct_ColumnDescriptor.h"
#include "snappydata_struct_Row.h"
#include "snappydata_struct_OutputParameter.h"
#include "snappydata_struct_RowSet.h"
#include "snappydata_struct_PrepareResult.h"
#include "snappydata_struct_UpdateResult.h"
#include "snappydata_struct_StatementResult.h"

#include <map>
#include <set>
#include <unordered_map>
#include <unordered_set>

namespace io {
namespace snappydata {
namespace client {

  static const uint32_t DEFAULT_REAL_PRECISION = 8;

  namespace impl {
    class ClientService;
  }

  class ColumnDescriptor;
  class Connection;
  class DatabaseMetaData;
  class DatabaseMetaDataArgs;
  class ParameterDescriptor;
  class Parameters;
  class ParametersBatch;
  class PreparedStatement;
  class Result;
  class ResultSet;
  class Row;
  class SQLException;
  class SQLWarning;
  class StatementAttributes;
  class UpdatableRow;

  struct SQLType : public thrift::SnappyType {
  };

  struct DatabaseMetaDataCall : public thrift::ServiceMetaDataCall {
  };

  struct DatabaseFeature : public thrift::ServiceFeature {
  };

  struct DriverType {
    enum type : int8_t {
      JDBC = thrift::snappydataConstants::DRIVER_JDBC,
      ODBC = thrift::snappydataConstants::DRIVER_ODBC
    };
  };

  struct ResultSetType {
    enum type : int8_t {
      FORWARD_ONLY = thrift::snappydataConstants::RESULTSET_TYPE_FORWARD_ONLY,
      INSENSITIVE = thrift::snappydataConstants::RESULTSET_TYPE_INSENSITIVE,
      SENSITIVE = thrift::snappydataConstants::RESULTSET_TYPE_SENSITIVE,
      UNKNOWN = thrift::snappydataConstants::RESULTSET_TYPE_UNKNOWN
    };
  };

  struct ResultSetHoldability {
    enum type : int8_t {
      NONE = 0,
      CLOSE_CURSORS_OVER_COMMIT = 1,
      HOLD_CURSORS_OVER_COMMIT = 2
    };
  };

  /**
   * Keeping the values below the same as the Thrift IDL. Unfortunately
   * the C++ genertor does not have these as constants rather as statics.
   */
  struct IsolationLevel {
    enum type : int8_t {
      NONE = thrift::snappydataConstants::TRANSACTION_NONE,
      READ_UNCOMMITTED = thrift::snappydataConstants::TRANSACTION_READ_UNCOMMITTED,
      READ_COMMITTED = thrift::snappydataConstants::TRANSACTION_READ_COMMITTED,
      REPEATABLE_READ = thrift::snappydataConstants::TRANSACTION_REPEATABLE_READ,
      SERIALIZABLE = thrift::snappydataConstants::TRANSACTION_SERIALIZABLE,
      NO_CHANGE = thrift::snappydataConstants::TRANSACTION_NO_CHANGE
    };
  };

  struct NextResultSetBehaviour {
    enum type : int8_t {
      CLOSE_ALL = thrift::snappydataConstants::NEXTRS_CLOSE_ALL_RESULTS,
      CLOSE_CURRENT = thrift::snappydataConstants::NEXTRS_CLOSE_CURRENT_RESULT,
      KEEP_CURRENT = thrift::snappydataConstants::NEXTRS_KEEP_CURRENT_RESULT
    };
  };

  struct TransactionAttribute : public thrift::TransactionAttribute {
  };

  class OutputParameter {
  private:
    thrift::OutputParameter m_outParam;

  public:
    OutputParameter() : m_outParam() {
    }

    OutputParameter(SQLType::type type) : m_outParam() {
      m_outParam.__set_type(type);
    }

    const thrift::OutputParameter& getThriftOutputParameter() const noexcept {
      return m_outParam;
    }

    void setType(SQLType::type type) noexcept {
      m_outParam.__set_type(type);
    }

    SQLType::type getType() const noexcept {
      return m_outParam.type;
    }

    void setScale(const int32_t scale) noexcept {
      m_outParam.__set_scale(scale);
    }

    int32_t getScale() const noexcept {
      return m_outParam.scale;
    }

    bool isSetScale() const noexcept {
      return m_outParam.__isset.scale;
    }

    void setTypeName(const std::string& typeName) noexcept {
      m_outParam.__set_typeName(typeName);
    }

    const std::string& getTypeName() const noexcept {
      return m_outParam.typeName;
    }

    bool isSetTypeName() const noexcept {
      return m_outParam.__isset.typeName;
    }
  };

  // some statics used to avoid creating objects for empty parameters

  extern const std::string EMPTY_STRING;
  extern const std::set<thrift::TransactionAttribute::type> EMPTY_TX_FLAGS;
  extern const std::map<int32_t, OutputParameter> EMPTY_OUTPUT_PARAMS;
  extern const std::map<int32_t, thrift::OutputParameter> EMPTY_OUT_PARAMS;

  namespace types {
    class Blob;
    class Clob;
    class DateTime;
    class Decimal;
    class JSON;
    class Timestamp;
  } /* namespace types */

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* CLIENTBASE_H_ */
