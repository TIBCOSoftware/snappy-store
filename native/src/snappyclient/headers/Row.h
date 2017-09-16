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
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

#define GET_DATACONVERSION_ERROR(cv, target, columnNum) \
     GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATA_TYPE_GET_MISMATCH_MSG, \
         target, Utils::getSQLTypeName(cv), columnNum)

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
            columnZeroIndex + 1, m_values.size());
      } else {
        throw GET_SQLEXCEPTION2(SQLStateMessage::NO_CURRENT_ROW_MSG);
      }
    }

    // no copy constructor or assignment
    Row(const Row& other) = delete;
    Row& operator=(const Row& other) = delete;

    friend class ResultSet;
    friend class Result;

  protected:
    // for placement new skip initialization of m_values
    Row(bool updatable) : thrift::Row(updatable) {
    }

    inline const thrift::ColumnValue& getColumnValue(
        uint32_t columnNum) const {
      columnNum--;
      checkColumnBounds(columnNum);

      return m_values[columnNum];
    }

    inline thrift::ColumnValue* getColumnValue_(uint32_t columnNum) {
      columnNum--;
      checkColumnBounds(columnNum);

      return &m_values[columnNum];
    }

    bool convertBoolean(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    int8_t convertByte(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    uint8_t convertUnsignedByte(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    int16_t convertShort(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    uint16_t convertUnsignedShort(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    int32_t convertInt(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    uint32_t convertUnsignedInt(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    int64_t convertInt64(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    uint64_t convertUnsignedInt64(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    float convertFloat(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    double convertDouble(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    std::shared_ptr<std::string> convertString(
        const thrift::ColumnValue& cv, const uint32_t columnNum,
        const uint32_t realPrecision) const;

    std::shared_ptr<Decimal> convertDecimal(const thrift::ColumnValue& cv,
        const uint32_t columnNum, const uint32_t realPrecision) const;

    std::shared_ptr<thrift::Decimal> convertTDecimal(
        const thrift::ColumnValue& cv, const uint32_t columnNum,
        const uint32_t realPrecision) const;

    DateTime convertDate(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    DateTime convertTime(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    Timestamp convertTimestamp(const thrift::ColumnValue& cv,
        const uint32_t columnNum) const;

    std::shared_ptr<std::string> convertBinary(
        const thrift::ColumnValue& cv, const uint32_t columnNum) const;

    // TODO: need to add chunking for BLOBs/CLOBs

    static std::shared_ptr<std::string> getFullBlobData(
        const std::shared_ptr<thrift::BlobChunk>& blob,
        const thrift::ColumnValue& cv, const uint32_t columnNum,
        const char* forType);

    static std::shared_ptr<std::string> getFullClobData(
        const std::shared_ptr<thrift::ClobChunk>& clob,
        const thrift::ColumnValue& cv, const uint32_t columnNum,
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

    void addColumn(const thrift::ColumnValue& v) {
      m_values.push_back(v);
    }

    void addColumn(thrift::ColumnValue&& v) {
      m_values.push_back(std::move(v));
    }

    SQLType getType(const uint32_t columnNum) const {
      return static_cast<SQLType>(getColumnValue(columnNum).getType());
    }

    bool getBoolean(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<bool>()) {
        return *v;
      } else {
        return convertBoolean(cv, columnNum);
      }
    }

    int8_t getByte(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<int8_t>()) {
        return *v;
      } else {
        return convertByte(cv, columnNum);
      }
    }

    uint8_t getUnsignedByte(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<int8_t>()) {
        return (uint8_t)*v;
      } else {
        return convertUnsignedByte(cv, columnNum);
      }
    }

    int16_t getShort(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<int16_t>()) {
        return *v;
      } else {
        return convertShort(cv, columnNum);
      }
    }

    uint16_t getUnsignedShort(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<int16_t>()) {
        return (uint16_t)*v;
      } else {
        return convertUnsignedShort(cv, columnNum);
      }
    }

    int32_t getInt(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<int32_t>()) {
        return *v;
      } else {
        return convertInt(cv, columnNum);
      }
    }

    uint32_t getUnsignedInt(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<int32_t>()) {
        return (uint32_t)*v;
      } else {
        return convertUnsignedInt(cv, columnNum);
      }
    }

    int64_t getInt64(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<int64_t>()) {
        return *v;
      } else {
        return convertInt64(cv, columnNum);
      }
    }

    uint64_t getUnsignedInt64(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<int64_t>()) {
        return (uint64_t)*v;
      } else {
        return convertUnsignedInt64(cv, columnNum);
      }
    }

    float getFloat(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<float>()) {
        return *v;
      } else {
        return convertFloat(cv, columnNum);
      }
    }

    double getDouble(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<double>()) {
        return *v;
      } else {
        return convertDouble(cv, columnNum);
      }
    }

    std::shared_ptr<std::string> getString(const uint32_t columnNum,
        const uint32_t realPrecision = DEFAULT_REAL_PRECISION) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (cv.getType() == thrift::SnappyType::VARCHAR) {
        return cv.getPtr<std::string>();
      } else {
        return convertString(cv, columnNum, realPrecision);
      }
    }

    std::shared_ptr<Decimal> getDecimal(const uint32_t columnNum,
        const uint32_t realPrecision = DEFAULT_REAL_PRECISION) const;

    std::shared_ptr<thrift::Decimal> getTDecimal(const uint32_t columnNum,
        const uint32_t realPrecision = DEFAULT_REAL_PRECISION) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (cv.getType() == thrift::SnappyType::DECIMAL) {
        return cv.getPtr<thrift::Decimal>();
      } else {
        return convertTDecimal(cv, columnNum, realPrecision);
      }
    }

    DateTime getDate(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<thrift::Date>()) {
        return DateTime(v->m_elapsed);
      } else {
        return convertDate(cv, columnNum);
      }
    }

    DateTime getTime(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<thrift::Time>()) {
        return DateTime(v->m_elapsed);
      } else {
        return convertTime(cv, columnNum);
      }
    }

    Timestamp getTimestamp(const uint32_t columnNum) const {
      const thrift::ColumnValue& cv = getColumnValue(columnNum);

      if (auto v = cv.getOrNull<thrift::Timestamp>()) {
        return Timestamp(v->m_elapsed);
      } else {
        return convertTimestamp(cv, columnNum);
      }
    }

    std::shared_ptr<std::string> getBinary(const uint32_t columnNum) const;

    std::shared_ptr<thrift::Array> getArray(const uint32_t columnNum) const;

    std::shared_ptr<thrift::Map> getMap(const uint32_t columnNum) const;

    std::shared_ptr<thrift::Struct> getStruct(
        const uint32_t columnNum) const;

    inline bool isNull(const uint32_t columnNum) const {
      return getColumnValue(columnNum).isNull();
    }

    size_t numColumns() const {
      return m_values.size();
    }

    virtual ~Row() {
    }
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* ROW_H_ */
