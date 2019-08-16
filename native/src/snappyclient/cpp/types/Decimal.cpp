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
 * Decimal.cpp
 */

#include "Types.h"

#include "../impl/InternalUtils.h"
#include <boost/lexical_cast.hpp>
#include <boost/numeric/conversion/cast.hpp>

using namespace io::snappydata;
using namespace io::snappydata::client::types;

Decimal Decimal::ZERO((uint32_t)0);
Decimal Decimal::ONE((uint32_t)1);

uint32_t Decimal::TEN_POWERS_TABLE[] = {
    10,                    // 1 / 10^1
    100,                   // 2 / 10^2
    1000,                  // 3 / 10^3
    10000,                 // 4 / 10^4
    100000,                // 5 / 10^5
    1000000,               // 6 / 10^6
    10000000,              // 7 / 10^7
    100000000,             // 8 / 10^8
    1000000000,            // 9 / 10^9
};

Decimal::Decimal(const thrift::Decimal& dec) :
    m_scale(dec.scale), m_precision(0) {
  initializeBigInteger(dec.signum, (const int8_t*)dec.magnitude.c_str(),
      dec.magnitude.size(), true);
}

Decimal::Decimal(const int8_t signum, const size_t scale,
    const int8_t* magnitude, const size_t maglen, const bool bigEndian) :
    m_scale(scale), m_precision(0) {
  initializeBigInteger(signum, magnitude, maglen, bigEndian);
}

void Decimal::initializeBigInteger(const int8_t signum, const int8_t* magnitude,
    const size_t maglen, const bool bigEndian) {
  if (signum != 0) {
    const int endianness = (bigEndian ? 1 : -1);

    mpz_init2(m_bigInt, maglen * sizeof(int8_t) * 8);
    mpz_import(m_bigInt, maglen, endianness,
        sizeof(int8_t) /* size of each element i.e. int8_t in int8_t[] */,
        endianness, 0, magnitude);
    if (signum < 0) {
      mpz_neg(m_bigInt, m_bigInt);
    }
  } else {
    mpz_init(m_bigInt);
  }
}

Decimal::Decimal(const int32_t v) :
    m_scale(0), m_precision(0) {
  mpz_init_set_si(m_bigInt, v);
}

Decimal::Decimal(const uint32_t v) :
    m_scale(0), m_precision(0) {
  mpz_init_set_ui(m_bigInt, v);
}

Decimal::Decimal(const int64_t v) :
    m_scale(0), m_precision(0) {
  // fallback to slow conversion from string for 32-bit mpz_init_set_si
  if (sizeof(signed long int) < sizeof(int64_t)) {
    std::string vstr;
    Utils::convertInt64ToString(v, vstr);
    mpz_init_set_str(m_bigInt, vstr.c_str(), 10);
  } else {
    // disable warning about possible data loss that is taken care of above
#ifdef _WINDOWS
#pragma warning(push)
#pragma warning(disable: 4244)
#endif
    mpz_init_set_si(m_bigInt, v);
#ifdef _WINDOWS
#pragma warning(pop)
#endif
  }
}

Decimal::Decimal(const uint64_t v) :
    m_scale(0), m_precision(0) {
  // fallback to slow conversion from string for 32-bit mpz_init_set_ui
  if (sizeof(unsigned long int) < sizeof(uint64_t)) {
    std::string vstr;
    Utils::convertUInt64ToString(v, vstr);
    mpz_init_set_str(m_bigInt, vstr.c_str(), 10);
  } else {
    // disable warning about possible data loss that is taken care of above
#ifdef _WINDOWS
#pragma warning(push)
#pragma warning(disable: 4244)
#endif
    mpz_init_set_ui(m_bigInt, v);
#ifdef _WINDOWS
#pragma warning(pop)
#endif
  }
}

/* TODO: need to implement conversion from double to BigDecimal
 * (e.g. see Android's code)
*/

Decimal::Decimal(const float v, const size_t precision) :
    m_precision(0) {
  // TODO: make this efficient using code like in JDK's BigDecimal
  std::string str;
  Utils::convertFloatToString(v, str, precision);
  parseString(str, -1);
}

Decimal::Decimal(const double v, const size_t precision) :
    m_precision(0) {
  // TODO: make this efficient using code like in JDK's BigDecimal
  std::string str;
  Utils::convertDoubleToString(v, str, precision);
  parseString(str, -1);
}

void Decimal::parseString(const std::string& str, const uint32_t columnIndex) {
  int result;
  // search for the '.' to determine the scale
  // also check if this is the case of leading '.' in which case
  // we can optimize by directly converting the string after '.'
  // to integer
  const char *start, *p;
  start = p = str.c_str();
  bool hasDot = false;
  char c;
  const bool neg = ((c = *p) == '-');
  bool onlyZerosOrDots = neg || (c == '0');
  while (c != 0) {
    if (c == '.') {
      hasDot = true;
      break;
    }
    if (onlyZerosOrDots && c != '0') {
      onlyZerosOrDots = false;
    }
    p++;
    c = *p;
  }
  if (hasDot) {
    size_t sz = str.length();
    size_t wholeDigitsWithSign = p - start;
    m_scale = sz - wholeDigitsWithSign - 1;
    // convert directly if there are only zeros/sign and dot at start
    if (onlyZerosOrDots) {
      result = mpz_init_set_str(m_bigInt, p + 1, 10);
      if (neg && result == 0) {
        mpz_neg(m_bigInt, m_bigInt);
      }
    } else {
      std::string unscaledValue;
      unscaledValue.reserve(sz);
      unscaledValue.append(start, wholeDigitsWithSign).append(p + 1, m_scale);
      result = mpz_init_set_str(m_bigInt, unscaledValue.c_str(), 10);
    }
  } else {
    // just a plain integer
    result = mpz_init_set_str(m_bigInt, start, 10);
    m_scale = 0;
  }
  if (result != 0) {
    // fail with exception
    mpz_clear(m_bigInt);
    Utils::throwDataFormatError("DECIMAL", columnIndex, str.c_str());
  }
}

Decimal::Decimal(const std::string& str, const uint32_t columnIndex) :
    m_precision(0) {
  parseString(str, columnIndex);
}

Decimal::Decimal(const Decimal& other) noexcept :
    m_scale(other.m_scale), m_precision(other.m_precision) {
  mpz_init_set(m_bigInt, other.m_bigInt);
}

Decimal::Decimal(Decimal&& other) noexcept :
    m_scale(other.m_scale), m_precision(other.m_precision) {
  m_bigInt[0] = other.m_bigInt[0];
  mpz_init(other.m_bigInt);
  other.m_scale = 0;
  other.m_precision = 0;
}

Decimal& Decimal::operator=(const Decimal& other) noexcept {
  mpz_clear(m_bigInt);
  mpz_init_set(m_bigInt, other.m_bigInt);
  m_scale = other.m_scale;
  m_precision = other.m_precision;
  return *this;
}

Decimal& Decimal::operator=(Decimal&& other) noexcept {
  auto tmp = m_bigInt[0];
  m_bigInt[0] = other.m_bigInt[0];
  other.m_bigInt[0] = tmp;
  std::swap(m_scale, other.m_scale);
  std::swap(m_precision, other.m_precision);
  return *this;
}

bool Decimal::operator==(const Decimal& other) const {
  return m_scale == other.m_scale && mpz_cmp(m_bigInt, other.m_bigInt) == 0;
}

bool Decimal::operator!=(const Decimal& other) const {
  return m_scale != other.m_scale || mpz_cmp(m_bigInt, other.m_bigInt) != 0;
}

const mpz_t* Decimal::getBigInteger(mpz_t* copy) const noexcept {
  if (m_scale != 0) {
    mpz_init_set(*copy, m_bigInt);
    uint32_t nPowersOf10 = sizeof(TEN_POWERS_TABLE)
        / sizeof(TEN_POWERS_TABLE[0]);
    size_t scale = m_scale;
    size_t ndivs = scale / nPowersOf10;
    while (--ndivs >= 0) {
      mpz_fdiv_q_ui(*copy, *copy, TEN_POWERS_TABLE[nPowersOf10 - 1]);
      scale -= nPowersOf10;
    }
    if (scale > 0) {
      mpz_fdiv_q_ui(*copy, *copy, TEN_POWERS_TABLE[scale - 1]);
    }
    return copy;
  } else {
    return &m_bigInt;
  }
}

bool Decimal::toUnsignedInt64(uint64_t& result, const bool allowOverflow) const {
  mpz_t temp;
  const mpz_t* intVal = getBigInteger(&temp);
  if (allowOverflow) {
    result = mpz_get_ui(*intVal);
    return true;
  } else {
    if (mpz_fits_ulong_p(*intVal)) {
      result = mpz_get_ui(*intVal);
      return true;
    } else {
      return false;
    }
  }
}

bool Decimal::toInt64(int64_t& result, const bool allowOverflow) const {
  mpz_t temp;
  const mpz_t* intVal = getBigInteger(&temp);
  if (allowOverflow) {
    result = mpz_get_si(*intVal);
    return true;
  } else {
    if (mpz_fits_slong_p(*intVal)) {
      result = mpz_get_si(*intVal);
      return true;
    } else {
      return false;
    }
  }
}

bool Decimal::toDouble(double& result) const {
  // TODO: SW: make this efficient
  std::string str;
  toString(str);
  try {
    result = boost::lexical_cast<double>(str);
    return true;
  } catch (const std::exception&) {
    return false;
  }
}

size_t Decimal::toByteArray(std::string& str) const {
  size_t len = str.length();
  // calculate the required length
  size_t nbytes = (mpz_sizeinbase(m_bigInt, 2) + 7) / 8;
  // below manipulates internal char* of std::string which should
  // work will all implementations though is undefined as per standard
  if (nbytes > 0) {
    str.resize(len + nbytes);
    char* resultChars = const_cast<char*>(str.data()) + len;
    mpz_export(resultChars, NULL, 1,
        1 /* size of each element i.e. byte in byte[] */, 1 /* big endian */, 0,
        m_bigInt);
  }
  return nbytes;
}

bool Decimal::wholeDigits(uint8_t* bytes, const size_t maxLen,
    size_t& actualLen) const noexcept {
  mpz_t temp;
  const mpz_t* intVal = getBigInteger(&temp);
  // calculate the required length
  actualLen = (mpz_sizeinbase(*intVal, 2) + 7) / 8;
  if (actualLen <= maxLen) {
    mpz_export(bytes, NULL, 1, 1 /* size of each element i.e. byte in byte[] */,
        1 /* big endian */, 0, *intVal);
    return true;
  } else {
    return false;
  }
}

void Decimal::copyTo(thrift::Decimal& target) const {
  toByteArray(target.magnitude);
  target.signum = signum();
  target.scale = boost::numeric_cast<int32_t>(m_scale);
}

size_t Decimal::toString(std::string& str) const {
  // convert the integer to string first
  char buf[thrift::snappydataConstants::DECIMAL_MAX_PRECISION + 4];
  char* bufp = buf;
  io::snappydata::client::impl::FreePointer freep(0);
  const size_t ndigits = mpz_sizeinbase(m_bigInt, 10);
  if (ndigits > 128) {
    bufp = new char[ndigits + 2];
    freep.reset(bufp);
  }
  mpz_get_str(bufp, 10, m_bigInt);

  // now the three cases of '.' inside, before and not at all
  const bool neg = (*bufp == '-');
  if (m_scale == 0) {
    str.append(bufp, ndigits + neg);
    return (ndigits + neg);
  }
  // check for sign (using signed version of size_t)
  ptrdiff_t wholeDigits = (ndigits - m_scale);
  if (wholeDigits > 0) {
    size_t wholeDigitsWithSign = static_cast<size_t>(wholeDigits + neg);
    str.append(bufp, wholeDigitsWithSign);
    str += '.';
    str.append(bufp + wholeDigitsWithSign, m_scale);
    return (ndigits + neg + 1);
  } else {
    // '.' goes at the start with possible additional zeros
    if (neg) {
      str += '-';
      bufp++;
    }
    str.append("0.");
    // add additional zero between '.' and integer
    if (wholeDigits < 0) {
      str.append(static_cast<size_t>(-wholeDigits), '0');
    }
    str.append(bufp, ndigits);
    return (ndigits + neg + 2);
  }
}

Decimal::~Decimal() {
  mpz_clear(m_bigInt);
}
