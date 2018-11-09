/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
 * ColumnDescriptor.cpp
 */

#include "ColumnDescriptor.h"

using namespace io::snappydata::client;

bool ColumnDescriptorBase::isSigned() const noexcept {
  switch (m_descriptor.type) {
    case thrift::SnappyType::TINYINT:
    case thrift::SnappyType::SMALLINT:
    case thrift::SnappyType::INTEGER:
    case thrift::SnappyType::BIGINT:
    case thrift::SnappyType::FLOAT:
    case thrift::SnappyType::DOUBLE:
    case thrift::SnappyType::DECIMAL:
      return true;
    default:
      return false;
  }
}

int16_t ColumnDescriptorBase::getScale() const noexcept {
  if (m_descriptor.__isset.scale) {
    return m_descriptor.scale;
  } else {
    switch (m_descriptor.type) {
      case thrift::SnappyType::BOOLEAN:
      case thrift::SnappyType::TINYINT:
      case thrift::SnappyType::SMALLINT:
      case thrift::SnappyType::INTEGER:
      case thrift::SnappyType::BIGINT:
      case thrift::SnappyType::FLOAT:
      case thrift::SnappyType::DOUBLE:
      case thrift::SnappyType::DATE:
      case thrift::SnappyType::TIME:
        return 0;
      case thrift::SnappyType::TIMESTAMP:
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
std::string ColumnDescriptorBase::getTypeName() const noexcept {
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
      case thrift::SnappyType::TINYINT:
        return "TINYINT";
      case thrift::SnappyType::SMALLINT:
        return "SMALLINT";
      case thrift::SnappyType::INTEGER:
        return "INTEGER";
      case thrift::SnappyType::BIGINT:
        return "BIGINT";
      case thrift::SnappyType::FLOAT:
        return "REAL";
      case thrift::SnappyType::DOUBLE:
        return "DOUBLE";
      case thrift::SnappyType::DECIMAL:
        return "DECIMAL";
      case thrift::SnappyType::CHAR:
        return "CHAR";
      case thrift::SnappyType::VARCHAR:
        return "VARCHAR";
      case thrift::SnappyType::LONGVARCHAR:
        return "LONG VARCHAR";
      case thrift::SnappyType::DATE:
        return "DATE";
      case thrift::SnappyType::TIME:
        return "TIME";
      case thrift::SnappyType::TIMESTAMP:
        return "TIMESTAMP";
      case thrift::SnappyType::BINARY:
        return "CHAR FOR BIT DATA";
      case thrift::SnappyType::VARBINARY:
        return "VARCHAR FOR BIT DATA";
      case thrift::SnappyType::LONGVARBINARY:
        return "LONG VARCHAR FOR BIT DATA";
      case thrift::SnappyType::JAVA_OBJECT:
        return "JAVA";
      case thrift::SnappyType::BLOB:
        return "BLOB";
      case thrift::SnappyType::CLOB:
        return "CLOB";
      case thrift::SnappyType::BOOLEAN:
        return "BOOLEAN";
      case thrift::SnappyType::SQLXML:
        return "XML";
      case thrift::SnappyType::ARRAY:
        return "ARRAY";
      case thrift::SnappyType::MAP:
        return "MAP";
      case thrift::SnappyType::STRUCT:
        return "STRUCT";
      case thrift::SnappyType::JSON:
        return "JSON";
      default:
        return "UNKNOWN";
    }
  }
}

std::string ColumnDescriptorBase::getUDTClassName() const noexcept {
  if (m_descriptor.__isset.udtTypeAndClassName) {
    const std::string& typeAndClass = m_descriptor.udtTypeAndClassName;
    size_t colonIndex;
    if ((colonIndex = typeAndClass.find(':')) != std::string::npos) {
      return typeAndClass.substr(colonIndex);
    }
  }
  return "";
}

bool ColumnDescriptor::isCaseSensitive() const noexcept {
  switch (m_descriptor.type) {
    case thrift::SnappyType::CHAR:
    case thrift::SnappyType::VARCHAR:
    case thrift::SnappyType::CLOB:
    case thrift::SnappyType::LONGVARCHAR:
    case thrift::SnappyType::SQLXML:
      return true;
    default:
      return false;
  }
}

bool ColumnDescriptor::isCurrency() const noexcept {
  return (m_descriptor.type == thrift::SnappyType::DECIMAL);
}

uint32_t ColumnDescriptor::getDisplaySize() const noexcept {
  uint32_t size;
  switch (m_descriptor.type) {
    case thrift::SnappyType::TIMESTAMP:
      return 26;
    case thrift::SnappyType::DATE:
      return 10;
    case thrift::SnappyType::TIME:
      return 8;
    case thrift::SnappyType::INTEGER:
      return 11;
    case thrift::SnappyType::SMALLINT:
      return 6;
    case thrift::SnappyType::FLOAT:
      return 13;
    case thrift::SnappyType::DOUBLE:
      return 22;
    case thrift::SnappyType::TINYINT:
      return 4;
    case thrift::SnappyType::BIGINT:
      return 20;
    case thrift::SnappyType::BOOLEAN:
      return 5; // for "false"
    case thrift::SnappyType::BINARY:
    case thrift::SnappyType::VARBINARY:
    case thrift::SnappyType::LONGVARBINARY:
    case thrift::SnappyType::BLOB:
      size = (2 * getPrecision());
      return (size > 0 ? size : 30);
    default:
      size = getPrecision();
      return (size > 0 ? size : 15);
  }
}
