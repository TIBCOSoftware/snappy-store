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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

  enum class ColumnNullability {
    NONULLS = 0,
    NULLABLE = 1,
    UNKNOWN = 2
  };

  enum class ColumnUpdatable {
    READ_ONLY = 0,
    UPDATABLE = 1,
    DEFINITELY_UPDATABLE = 2
  };

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

    SQLType getSQLType() const noexcept {
      return static_cast<SQLType>(m_descriptor.type);
    }

    const std::string& getName() const noexcept {
      return m_descriptor.name;
    }

    std::string getSchema() const {
      if (m_descriptor.__isset.fullTableName) {
        const std::string& tableName = m_descriptor.fullTableName;
        size_t dotPos;
        if ((dotPos = tableName.find('.')) != std::string::npos) {
          return tableName.substr(0, dotPos);
        }
      }
      return "";
    }

    std::string getTable() const {
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

    ColumnNullability getNullability() const noexcept {
      if (m_descriptor.nullable) {
        return ColumnNullability::NULLABLE;
      } else if (m_descriptor.__isset.nullable) {
        return ColumnNullability::NONULLS;
      } else {
        return ColumnNullability::UNKNOWN;
      }
    }

    bool isSigned() const noexcept;

    int16_t getPrecision() const noexcept {
      return m_descriptor.precision;
    }

    int16_t getScale() const noexcept;

    /**
     * Returns the database type name for this column, or "UNKNOWN"
     * if the type cannot be determined.
     */
    std::string getTypeName() const noexcept;

    /**
     * For a Java user-defined type, return the java class name
     * of the type, else returns empty string ("").
     */
    std::string getUDTClassName() const noexcept;
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* COLUMNDESCRIPTORBASE_H_ */
