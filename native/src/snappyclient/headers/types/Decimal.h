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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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
 * Decimal.h
 */

#ifndef DECIMAL_H_
#define DECIMAL_H_

#include "SQLException.h"

#ifdef _WINDOWS
#include <mpir.h>
#else
#include <gmp.h>
#endif

namespace io {
namespace snappydata {
namespace client {
namespace types {

  class Decimal {
  private:
    void initializeBigInteger(const int8_t signum,
        const int8_t* magnitude, const size_t maglen, const bool bigEndian);

    /** discard fractional part */
    const mpz_t* getBigInteger(mpz_t* copy) const noexcept;

    static uint32_t TEN_POWERS_TABLE[];

    mpz_t m_bigInt;
    size_t m_scale;
    mutable size_t m_precision;

    void parseString(const std::string& str, const uint32_t columnIndex);

  public:
    Decimal(const thrift::Decimal& dec);

    Decimal(const int8_t signum, const size_t scale,
        const int8_t* magnitude, const size_t maglen, const bool bigEndian);

    Decimal(const int32_t v);

    Decimal(const uint32_t v);

    Decimal(const int64_t v);

    Decimal(const uint64_t v);

    Decimal(const float v, const size_t precision = DEFAULT_REAL_PRECISION);

    Decimal(const double v, const size_t precision = DEFAULT_REAL_PRECISION);

    Decimal(const std::string& str, const uint32_t columnIndex = -1);

    Decimal(const Decimal& other) noexcept;
    Decimal(Decimal&& other) noexcept;

    Decimal& operator=(const Decimal& other) noexcept;
    Decimal& operator=(Decimal&& other) noexcept;

    static Decimal ZERO;
    static Decimal ONE;

    bool operator==(const Decimal& other) const;

    bool operator!=(const Decimal& other) const;

    size_t precision() const noexcept {
      if (m_precision == 0) {
        m_precision = mpz_sizeinbase(m_bigInt, 10);
      }
      return m_precision;
    }

    size_t scale() const noexcept {
      return m_scale;
    }

    int32_t signum() const noexcept {
      return mpz_sgn(m_bigInt);
    }

    bool toUnsignedInt64(uint64_t& result,
        const bool allowOverflow = false) const;

    bool toInt64(int64_t& result, const bool allowOverflow = false) const;

    bool toDouble(double& result) const;

    size_t toByteArray(std::string& str) const;

    bool wholeDigits(uint8_t* bytes, const size_t maxLen,
        size_t& actualLen) const noexcept;

    void copyTo(thrift::Decimal& target) const;

    size_t toString(std::string& str) const;

    ~Decimal();
  };

} /* namespace types */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* DECIMAL_H_ */
