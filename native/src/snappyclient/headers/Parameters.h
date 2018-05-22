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
 * Parameters.h
 */

#ifndef PARAMETERS_H_
#define PARAMETERS_H_

#include "Row.h"

namespace io {
namespace snappydata {
namespace client {

  class Parameters : public Row {
  private:
    // IMPORTANT NOTE: DO NOT ADD ANY ADDITIONAL FIELDS IN THIS CLASS.
    // If need be then add to thrift::Row since higher layers use
    // placement new to freely up-convert thrift::Row to this type
    /**
     * No copy constructor or assignment operator because its expensive
     * and implicit shallow copy can have unexpected behaviour for the
     * users. Use clone()/shallowClone() as required.
     */
    Parameters(const Parameters&) = delete;
    Parameters operator=(const Parameters&) = delete;

    // for placement new skip initialization of m_values
    Parameters(bool skipInitialize) :
        Row(false) {
    }

    inline void checkBounds(uint32_t paramIndex) const {
      if (paramIndex >= m_values.size()) {
        throw GET_SQLEXCEPTION2(
            SQLStateMessage::LANG_INVALID_PARAM_POSITION_MSG, paramIndex,
            m_values.size());
      }
    }

    template<typename T>
    inline Parameters& set(uint32_t paramNum, const T v) {
      paramNum--;
      if (paramNum < m_values.size()) {
        m_values[paramNum].set(v);
        return *this;
      } else {
        throw GET_SQLEXCEPTION2(
            SQLStateMessage::LANG_INVALID_PARAM_POSITION_MSG, paramNum,
            m_values.size());
      }
    }

    friend class ParametersBatch;

  public:
    Parameters() : Row() { }

    Parameters(Parameters&& other) :
        Row(std::move(other)) {
    }

    Parameters& operator=(Parameters&& other) {
      Row::operator =(std::move(other));
      return *this;
    }

    Parameters(const PreparedStatement& pstmt);

    Parameters& setBoolean(uint32_t paramNum, const bool v) {
      return set(paramNum, v);
    }

    Parameters& setByte(uint32_t paramNum, const int8_t v) {
      return set(paramNum, v);
    }

    Parameters& setUnsignedByte(uint32_t paramNum, const uint8_t v) {
      // thrift API has no unsigned so need to convert to signed
      return set(paramNum, (const int8_t)v);
    }

    Parameters& setShort(uint32_t paramNum, const int16_t v) {
      return set(paramNum, v);
    }

    Parameters& setUnsignedShort(uint32_t paramNum, const uint16_t v) {
      // thrift API has no unsigned so need to convert to signed
      return set(paramNum, (const int16_t)v);
    }

    Parameters& setInt(uint32_t paramNum, const int32_t v) {
      return set(paramNum, v);
    }

    Parameters& setUnsignedInt(uint32_t paramNum, const uint32_t v) {
      // thrift API has no unsigned so need to convert to signed
      return set(paramNum, (const int32_t)v);
    }

    Parameters& setInt64(uint32_t paramNum, const int64_t v) {
      return set(paramNum, v);
    }

    Parameters& setUnsignedInt64(uint32_t paramNum, const uint64_t v) {
      // thrift API has no unsigned so need to convert to signed
      return set(paramNum, (const int64_t)v);
    }

    Parameters& setFloat(uint32_t paramNum, const float v) {
      return set(paramNum, v);
    }

    Parameters& setDouble(uint32_t paramNum, const double v) {
      return set(paramNum, v);
    }

    Parameters& setString(uint32_t paramNum, const std::string& v);

    Parameters& setString(uint32_t paramNum, std::string&& v) {
      checkBounds(--paramNum);
      m_values[paramNum].setString(std::move(v));
      return *this;
    }

    Parameters& setString(uint32_t paramNum, const char* v);

    Parameters& setString(uint32_t paramNum, const char* v, const size_t len);

    // TODO: somehow have an efficient move version; right now a full
    // transformation copy happens from public Decimal to thrift's Decimal

    Parameters& setDecimal(uint32_t paramNum, const Decimal& v);

    Parameters& setDecimal(uint32_t paramNum, const int8_t signum,
        const int32_t scale, const int8_t* magnitude, const size_t maglen,
        const bool bigEndian);

    Parameters& setDate(uint32_t paramNum, const DateTime v);

    Parameters& setTime(uint32_t paramNum, const DateTime v);

    Parameters& setTimestamp(uint32_t paramNum, const Timestamp& v);

    Parameters& setBinary(uint32_t paramNum, const std::string& v);

    Parameters& setBinary(uint32_t paramNum, std::string&& v);

    Parameters& setBinary(uint32_t paramNum, const int8_t* v,
        const size_t len);

    Parameters& setArray(uint32_t paramNum, const thrift::Array& v);

    Parameters& setArray(uint32_t paramNum, thrift::Array&& v);

    Parameters& setMap(uint32_t paramNum, const thrift::Map& v);

    Parameters& setMap(uint32_t paramNum, thrift::Map&& v);

    Parameters& setStruct(uint32_t paramNum, const thrift::Struct& v);

    Parameters& setStruct(uint32_t paramNum, thrift::Struct&& v);

    Parameters& setNull(uint32_t paramNum, const bool v);
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* PARAMETERS_H_ */
