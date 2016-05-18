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
    Parameters(const Parameters&);
    Parameters operator=(const Parameters&);

    // for placement new skip initialization of m_values
    Parameters(bool skipInitialize) :
        Row(false) {
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

    Parameters& setBoolean(const uint32_t index, const bool v);

    Parameters& setByte(const uint32_t index, const int8_t v);

    Parameters& setUnsignedByte(const uint32_t index, const uint8_t v) {
      // thrift API has no unsigned so need to convert to signed
      return setByte(index, (const int8_t)v);
    }

    Parameters& setShort(const uint32_t index, const int16_t v);

    Parameters& setUnsignedShort(const uint32_t index, const uint16_t v) {
      // thrift API has no unsigned so need to convert to signed
      return setShort(index, (const int16_t)v);
    }

    Parameters& setInt(const uint32_t index, const int32_t v);

    Parameters& setUnsignedInt(const uint32_t index, const uint32_t v) {
      // thrift API has no unsigned so need to convert to signed
      return setInt(index, (const int32_t)v);
    }

    Parameters& setInt64(const uint32_t index, const int64_t v);

    Parameters& setUnsignedInt64(const uint32_t index, const uint64_t v) {
      // thrift API has no unsigned so need to convert to signed
      return setInt64(index, (const int64_t)v);
    }

    Parameters& setFloat(const uint32_t index, const float v);

    Parameters& setDouble(const uint32_t index, const double v);

    Parameters& setString(const uint32_t index, const std::string& v);

    Parameters& setString(const uint32_t index, std::string&& v);

    Parameters& setString(const uint32_t index, const char* v);

    Parameters& setString(const uint32_t index, const char* v,
        const int32_t len);

    // TODO: somehow have an efficient move version; right now a full
    // transformation copy happens from public Decimal to thrift's Decimal

    Parameters& setDecimal(const uint32_t index, const Decimal& v);

    Parameters& setDecimal(const uint32_t index, const int8_t signum,
        const int32_t scale, const int8_t* magnitude, const size_t maglen,
        const bool bigEndian);

    Parameters& setDate(const uint32_t index, const DateTime v);

    Parameters& setTime(const uint32_t index, const DateTime v);

    Parameters& setTimestamp(const uint32_t index, const Timestamp& v);

    Parameters& setBinary(const uint32_t index, const std::string& v);

    Parameters& setBinary(const uint32_t index, std::string&& v);

    Parameters& setBinary(const uint32_t index, const int8_t* v,
        const size_t len);

    Parameters& setArray(const uint32_t index, const thrift::Array& v);

    Parameters& setArray(const uint32_t index, thrift::Array&& v);

    Parameters& setMap(const uint32_t index, const thrift::Map& v);

    Parameters& setMap(const uint32_t index, thrift::Map&& v);

    Parameters& setStruct(const uint32_t index, const thrift::Struct& v);

    Parameters& setStruct(const uint32_t index, thrift::Struct&& v);

    Parameters& setNull(const uint32_t index, const bool v);

    Parameters& setJSON(const uint32_t index, const JSON& v);
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* PARAMETERS_H_ */
