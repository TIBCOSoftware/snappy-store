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
 * Row.h
 */

#ifndef ROW_H_
#define ROW_H_

#include "Types.h"

#include <sstream>

#define GET_DATACONVERSION_ERROR(cv, target, columnIndex) \
     GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATA_TYPE_GET_MISMATCH_MSG, \
         target, Utils::getSQLTypeName(cv), columnIndex)

namespace {
  class ToString;
}

namespace io {
namespace snappydata {
namespace client {

  class Row : public thrift::Row {
  private:
    // IMPORTANT NOTE: DO NOT ADD ANY ADDITIONAL FIELDS IN THIS CLASS.
    // If need be then add to thrift::Row since higher layers use
    // placement new to freely up-convert thrift::Row to this type
    inline void checkColumnBounds(const uint32_t columnZeroIndex) const {
      if (columnZeroIndex < m_values.size()) {
        return;
      } else if (m_values.size() > 0) {
        throw GET_SQLEXCEPTION2(SQLStateMessage::COLUMN_NOT_FOUND_MSG1,
            columnZeroIndex + 1, static_cast<int>(m_values.size() + 1));
      } else {
        throw GET_SQLEXCEPTION2(SQLStateMessage::NO_CURRENT_ROW_MSG);
      }
    }

    // no copy constructor or assignment
    Row(const Row& other);
    Row& operator=(const Row& other);

    friend class ResultSet;
    friend class Result;

  protected:
    // for placement new skip initialization of m_values
    Row(bool updatable) : thrift::Row(updatable) {
    }

    inline const thrift::ColumnValue& getColumnValue(
        uint32_t columnIndex) const {
      columnIndex--;
      checkColumnBounds(columnIndex);

      return m_values[columnIndex];
    }

    inline thrift::ColumnValue* getColumnValue_(uint32_t columnIndex) {
      columnIndex--;
      checkColumnBounds(columnIndex);

      return &m_values[columnIndex];
    }

    bool convertBoolean(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    int8_t convertByte(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    uint8_t convertUnsignedByte(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    int16_t convertShort(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    uint16_t convertUnsignedShort(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    int32_t convertInt(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    uint32_t convertUnsignedInt(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    int64_t convertInt64(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    uint64_t convertUnsignedInt64(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    float convertFloat(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    double convertDouble(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    std::shared_ptr<std::string> convertString(
        const thrift::ColumnValue& cv, const uint32_t columnIndex,
        const uint32_t realPrecision) const;

    std::shared_ptr<Decimal> convertDecimal(const thrift::ColumnValue& cv,
        const uint32_t columnIndex, const uint32_t realPrecision) const;

    std::shared_ptr<thrift::Decimal> convertTDecimal(
        const thrift::ColumnValue& cv, const uint32_t columnIndex,
        const uint32_t realPrecision) const;

    DateTime convertDate(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    DateTime convertTime(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    Timestamp convertTimestamp(const thrift::ColumnValue& cv,
        const uint32_t columnIndex) const;

    std::shared_ptr<std::string> convertBinary(
        const thrift::ColumnValue& cv, const uint32_t columnIndex) const;

    // TODO: need to add chunking for BLOBs/CLOBs

    static std::shared_ptr<std::string> getFullBlobData(
        const std::shared_ptr<thrift::BlobChunk>& blob,
        const thrift::ColumnValue& cv, const uint32_t columnIndex,
        const char* forType);

    static std::shared_ptr<std::string> getFullClobData(
        const std::shared_ptr<thrift::ClobChunk>& clob,
        const thrift::ColumnValue& cv, const uint32_t columnIndex,
        const char* forType);

    friend class ::ToString;

  public:
    Row() : thrift::Row() {
    }

    Row(const size_t initialCapacity) : thrift::Row(initialCapacity) {
    }

    Row(Row&& other) : thrift::Row(std::move(other)) {
    }

    Row& operator=(Row&& other) {
      thrift::Row::operator =(std::move(other));
      return *this;
    }

    SQLType::type getType(const uint32_t columnIndex) const {
      return getColumnValue(columnIndex).getType();
    }

    bool getBoolean(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<bool>()) {
        return *v;
      } else {
        return convertBoolean(cv, columnIndex);
      }
    }

    int8_t getByte(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<int8_t>()) {
        return *v;
      } else {
        return convertByte(cv, columnIndex);
      }
    }

    uint8_t getUnsignedByte(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<int8_t>()) {
        return (uint8_t)*v;
      } else {
        return convertUnsignedByte(cv, columnIndex);
      }
    }

    int16_t getShort(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<int16_t>()) {
        return *v;
      } else {
        return convertShort(cv, columnIndex);
      }
    }

    uint16_t getUnsignedShort(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<int16_t>()) {
        return (uint16_t)*v;
      } else {
        return convertUnsignedShort(cv, columnIndex);
      }
    }

    int32_t getInt(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<int32_t>()) {
        return *v;
      } else {
        return convertInt(cv, columnIndex);
      }
    }

    uint32_t getUnsignedInt(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<int32_t>()) {
        return (uint32_t)*v;
      } else {
        return convertUnsignedInt(cv, columnIndex);
      }
    }

    int64_t getInt64(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<int64_t>()) {
        return *v;
      } else {
        return convertInt64(cv, columnIndex);
      }
    }

    uint64_t getUnsignedInt64(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<int64_t>()) {
        return (uint64_t)*v;
      } else {
        return convertUnsignedInt64(cv, columnIndex);
      }
    }

    float getFloat(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<float>()) {
        return *v;
      } else {
        return convertFloat(cv, columnIndex);
      }
    }

    double getDouble(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<double>()) {
        return *v;
      } else {
        return convertDouble(cv, columnIndex);
      }
    }

    std::shared_ptr<std::string> getString(const uint32_t columnIndex,
        const uint32_t realPrecision = DEFAULT_REAL_PRECISION) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (cv.getType() == thrift::SnappyType::VARCHAR) {
        return cv.getPtr<std::string>();
      } else {
        return convertString(cv, columnIndex, realPrecision);
      }
    }

    int32_t getString(const uint32_t columnIndex, char* outStr,
        const int32_t outMaxLen, const bool truncate = true,
        const uint32_t realPrecision = DEFAULT_REAL_PRECISION) const;

    std::shared_ptr<Decimal> getDecimal(const uint32_t columnIndex,
        const uint32_t realPrecision = DEFAULT_REAL_PRECISION) const;

    std::shared_ptr<thrift::Decimal> getTDecimal(const uint32_t columnIndex,
        const uint32_t realPrecision = DEFAULT_REAL_PRECISION) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (cv.getType() == thrift::SnappyType::DECIMAL) {
        return cv.getPtr<thrift::Decimal>();
      } else {
        return convertTDecimal(cv, columnIndex, realPrecision);
      }
    }

    DateTime getDate(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<thrift::Date>()) {
        return DateTime(v->secsSinceEpoch);
      } else {
        return convertDate(cv, columnIndex);
      }
    }

    DateTime getTime(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<thrift::Time>()) {
        return DateTime(v->secsSinceEpoch);
      } else {
        return convertTime(cv, columnIndex);
      }
    }

    Timestamp getTimestamp(const uint32_t columnIndex) const {
      const thrift::ColumnValue& cv = getColumnValue(columnIndex);

      if (auto v = cv.getOrNull<thrift::Timestamp>()) {
        return Timestamp(v->secsSinceEpoch, v->nanos);
      } else {
        return convertTimestamp(cv, columnIndex);
      }
    }

    std::shared_ptr<std::string> getBinary(const uint32_t columnIndex) const;

    std::shared_ptr<thrift::Array> getArray(const uint32_t columnIndex) const;

    std::shared_ptr<thrift::Map> getMap(const uint32_t columnIndex) const;

    std::shared_ptr<thrift::Struct> getStruct(
        const uint32_t columnIndex) const;

    JSON getJSON(const uint32_t columnIndex) const;

    inline bool isNull(const uint32_t columnIndex) const {
      return getColumnValue(columnIndex).isNull();
    }

    inline int32_t numColumns() const {
      return m_values.size();
    }

    virtual ~Row() {
    }
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* ROW_H_ */
