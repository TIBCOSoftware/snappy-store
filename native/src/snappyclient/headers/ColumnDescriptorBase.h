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

/**
 * ColumnDescriptorBase.h
 */

#ifndef COLUMNDESCRIPTORBASE_H_
#define COLUMNDESCRIPTORBASE_H_

#include "Types.h"

namespace io {
namespace snappydata {
namespace client {

  namespace ColumnNullability {
    enum type {
      NONULLS = 0,
      NULLABLE = 1,
      UNKNOWN = 2
    };
  }

  namespace ColumnUpdatable {
    enum type {
      READ_ONLY = 0,
      UPDATABLE = 1,
      DEFINITELY_UPDATABLE = 2
    };
  }

  class ColumnDescriptorBase {
  protected:
    thrift::ColumnDescriptor& m_descriptor;
    const uint32_t m_columnIndex;

    ColumnDescriptorBase(thrift::ColumnDescriptor& descriptor,
        const uint32_t columnIndex) :
        m_descriptor(descriptor), m_columnIndex(columnIndex) {
    }

  public:
    ~ColumnDescriptorBase() {
    }

    /**
     * Get the 1-based index of the column/parameter that this
     * descriptor represents.
     */
    uint32_t getIndex() const noexcept {
      return m_columnIndex;
    }

    SQLType::type getSQLType() const noexcept {
      return m_descriptor.type;
    }

    const std::string& getName() const noexcept {
      return m_descriptor.name;
    }

    std::string getSchema() const noexcept {
      if (m_descriptor.__isset.fullTableName) {
        const std::string& tableName = m_descriptor.fullTableName;
        size_t dotPos;
        if ((dotPos = tableName.find('.')) != std::string::npos) {
          return tableName.substr(0, dotPos);
        }
      }
      return "";
    }

    std::string getTable() const noexcept {
      if (m_descriptor.__isset.fullTableName) {
        const std::string& tableName = m_descriptor.fullTableName;
        size_t dotPos;
        if ((dotPos = tableName.find('.')) != std::string::npos) {
          return tableName.substr(dotPos + 1);
        } else {
          return tableName;
        }
      }
      return "";
    }

    const std::string& getFullTableName() const noexcept {
      return m_descriptor.fullTableName;
    }

    ColumnNullability::type getNullability() const noexcept {
      if (m_descriptor.nullable) {
        return ColumnNullability::NULLABLE;
      } else if (m_descriptor.__isset.nullable) {
        return ColumnNullability::NONULLS;
      } else {
        return ColumnNullability::UNKNOWN;
      }
    }

    bool isSigned() const noexcept {
      switch (m_descriptor.type) {
        case SQLType::TINYINT:
        case SQLType::SMALLINT:
        case SQLType::INTEGER:
        case SQLType::BIGINT:
        case SQLType::FLOAT:
        case SQLType::REAL:
        case SQLType::DOUBLE:
        case SQLType::DECIMAL:
          return true;
        default:
          return false;
      }
    }

    int16_t getPrecision() const noexcept {
      return m_descriptor.precision;
    }

    int16_t getScale() const noexcept {
      if (m_descriptor.__isset.scale) {
        return m_descriptor.scale;
      } else {
        switch (m_descriptor.type) {
          case SQLType::BOOLEAN:
          case SQLType::TINYINT:
          case SQLType::SMALLINT:
          case SQLType::INTEGER:
          case SQLType::BIGINT:
          case SQLType::FLOAT:
          case SQLType::REAL:
          case SQLType::DOUBLE:
          case SQLType::DATE:
          case SQLType::TIME:
            return 0;
          case SQLType::TIMESTAMP:
            return 6;
          default:
            return thrift::snappydataConstants::COLUMN_SCALE_UNKNOWN;
        }
      }
    }

    /**
     * Returns the database type name for this column, or "UNKNOWN"
     * if the type cannot be determined.
     */
    std::string getTypeName() const noexcept {
      if (m_descriptor.__isset.udtTypeAndClassName) {
        const std::string& typeAndClass = m_descriptor.udtTypeAndClassName;
        size_t colonIndex;
        if ((colonIndex = typeAndClass.find(':')) != std::string::npos) {
          return typeAndClass.substr(0, colonIndex);
        } else {
          return typeAndClass;
        }
      } else {
        switch (m_descriptor.type) {
          case SQLType::TINYINT:
            return "TINYINT";
          case SQLType::SMALLINT:
            return "SMALLINT";
          case SQLType::INTEGER:
            return "INTEGER";
          case SQLType::BIGINT:
            return "BIGINT";
          case SQLType::FLOAT:
            return "FLOAT";
          case SQLType::REAL:
            return "REAL";
          case SQLType::DOUBLE:
            return "DOUBLE";
          case SQLType::DECIMAL:
            return "DECIMAL";
          case SQLType::CHAR:
            return "CHAR";
          case SQLType::VARCHAR:
            return "VARCHAR";
          case SQLType::LONGVARCHAR:
            return "LONG VARCHAR";
          case SQLType::DATE:
            return "DATE";
          case SQLType::TIME:
            return "TIME";
          case SQLType::TIMESTAMP:
            return "TIMESTAMP";
          case SQLType::BINARY:
            return "CHAR FOR BIT DATA";
          case SQLType::VARBINARY:
            return "VARCHAR FOR BIT DATA";
          case SQLType::LONGVARBINARY:
            return "LONG VARCHAR FOR BIT DATA";
          case SQLType::JAVA_OBJECT:
            return "JAVA";
          case SQLType::BLOB:
            return "BLOB";
          case SQLType::CLOB:
            return "CLOB";
          case SQLType::BOOLEAN:
            return "BOOLEAN";
          case SQLType::SQLXML:
            return "XML";
          case SQLType::ARRAY:
            return "ARRAY";
          case SQLType::MAP:
            return "MAP";
          case SQLType::STRUCT:
            return "STRUCT";
          case SQLType::JSON:
            return "JSON";
          default:
            return "UNKNOWN";
        }
      }
    }

    /**
     * For a Java user-defined type, return the java class name
     * of the type, else returns empty string ("").
     */
    std::string getUDTClassName() const noexcept {
      if (m_descriptor.__isset.udtTypeAndClassName) {
        const std::string& typeAndClass = m_descriptor.udtTypeAndClassName;
        size_t colonIndex;
        if ((colonIndex = typeAndClass.find(':')) != std::string::npos) {
          return typeAndClass.substr(colonIndex);
        }
      }
      return "";
    }
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* COLUMNDESCRIPTORBASE_H_ */
