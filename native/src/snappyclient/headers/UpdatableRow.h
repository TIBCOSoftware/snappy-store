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
 * UpdatableRow.h
 */

#ifndef UPDATABLEROW_H_
#define UPDATABLEROW_H_

#include "Row.h"

namespace io {
namespace snappydata {
namespace client {

  class UpdatableRow : public Row {
  private:
    UpdatableRow(bool updatable) : Row(updatable) {
    }

    // IMPORTANT NOTE: DO NOT ADD ANY ADDITIONAL FIELDS IN THIS CLASS.
    // If need be then add to thrift::Row since higher layers use
    // placement new to freely up-convert thrift::Row to this type
    thrift::ColumnValue* getColumnValueForUpdate(const uint32_t columnIndex);

    // no copy constructor or assignment
    UpdatableRow(const UpdatableRow& other) = delete;
    UpdatableRow& operator=(const UpdatableRow& other) = delete;

    friend class ResultSet;

  public:
    UpdatableRow() : Row() {
    }

    UpdatableRow(UpdatableRow&& other) : Row(std::move(other)) {
    }

    UpdatableRow& operator=(UpdatableRow&& other) {
      Row::operator =(std::move(other));
      return *this;
    }

    inline DynamicBitSet* getChangedColumns() {
      return m_changedColumns;
    }

    std::vector<int32_t> getChangedColumnsAsVector();

    void setBoolean(const uint32_t columnIndex, const bool v);

    void setByte(const uint32_t columnIndex, const int8_t v);

    void setUnsignedByte(const uint32_t columnIndex, const uint8_t v) {
      // thrift API has no unsigned so need to convert to signed
      setByte(columnIndex, (const int8_t)v);
    }

    void setShort(const uint32_t columnIndex, const int16_t v);

    void setUnsignedShort(const uint32_t columnIndex, const uint16_t v) {
      // thrift API has no unsigned so need to convert to signed
      setShort(columnIndex, (const int16_t)v);
    }

    void setInt(const uint32_t columnIndex, const int32_t v);

    void setUnsignedInt(const uint32_t columnIndex, const uint32_t v) {
      // thrift API has no unsigned so need to convert to signed
      setInt(columnIndex, (const int32_t)v);
    }

    void setInt64(const uint32_t columnIndex, const int64_t v);

    void setUnsignedInt64(const uint32_t columnIndex, const uint64_t v) {
      // thrift API has no unsigned so need to convert to signed
      setInt64(columnIndex, (const int64_t)v);
    }

    void setFloat(const uint32_t columnIndex, const float v);

    void setDouble(const uint32_t columnIndex, const double v);

    void setString(const uint32_t columnIndex, const std::string& v);

    void setString(const uint32_t columnIndex, std::string&& v);

    void setDecimal(const uint32_t columnIndex, const Decimal& v);

    void setDate(const uint32_t columnIndex, const DateTime v);

    void setTime(const uint32_t columnIndex, const DateTime v);

    void setTimestamp(const uint32_t columnIndex, const Timestamp& v);

    void setBinary(const uint32_t columnIndex, const std::string& v);

    void setBinary(const uint32_t columnIndex, std::string&& v);

    void setArray(const uint32_t columnIndex, const thrift::Array& v);

    void setArray(const uint32_t columnIndex, thrift::Array&& v);

    void setMap(const uint32_t columnIndex, const thrift::Map& v);

    void setMap(const uint32_t columnIndex, thrift::Map&& v);

    void setStruct(const uint32_t columnIndex, const thrift::Struct& v);

    void setStruct(const uint32_t columnIndex, thrift::Struct&& v);

    void setNull(const uint32_t columnIndex, const bool v);
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* UPDATABLEROW_H_ */
