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
 * Parameters.cpp
 */

#include "Parameters.h"
#include "PreparedStatement.h"

using namespace io::snappydata;
using namespace io::snappydata::client;

Parameters::Parameters(const PreparedStatement& pstmt) :
    Row(static_cast<size_t>(pstmt.getParameterCount())) {
  m_values.resize(pstmt.getParameterCount());
}

Parameters& Parameters::setString(uint32_t paramNum, const std::string& v) {
  checkBounds(--paramNum);
  m_values[paramNum].setString(v);
  return *this;
}

Parameters& Parameters::setString(uint32_t paramNum, const char* v) {
  checkBounds(--paramNum);
  m_values[paramNum].setString(v);
  return *this;
}

Parameters& Parameters::setString(uint32_t paramNum, const char* v,
    const size_t len) {
  checkBounds(--paramNum);
  m_values[paramNum].setString(v, len);
  return *this;
}

Parameters& Parameters::setDecimal(uint32_t paramNum, const Decimal& v) {
  checkBounds(--paramNum);
  auto dec = std::shared_ptr<thrift::Decimal>(new thrift::Decimal());
  v.copyTo(*dec);
  m_values[paramNum].set(dec);
  return *this;
}

Parameters& Parameters::setDecimal(uint32_t paramNum, const int8_t signum,
    const int32_t scale, const int8_t* magnitude, const size_t maglen,
    const bool bigEndian) {
  checkBounds(--paramNum);
  auto dec = std::shared_ptr<thrift::Decimal>(new thrift::Decimal());

  dec->signum = signum;
  dec->scale = scale;
  if (bigEndian) {
    dec->magnitude.assign((const char*)magnitude, maglen);
  } else {
    // need to inverse the bytes
    if (maglen > 0) {
      dec->magnitude.resize(maglen);
      const int8_t* magp = magnitude + maglen - 1;
      for (uint32_t index = 0; index < maglen; index++, magp--) {
        dec->magnitude[index] = *magp;
      }
    } else {
      dec->magnitude.clear();
    }
  }
  m_values[paramNum].set(dec);
  return *this;
}

Parameters& Parameters::setDate(uint32_t paramNum, const DateTime v) {
  checkBounds(--paramNum);
  m_values[paramNum].set(thrift::Date(v.m_secsSinceEpoch));
  return *this;
}

Parameters& Parameters::setTime(uint32_t paramNum, const DateTime v) {
  checkBounds(--paramNum);
  m_values[paramNum].set(thrift::Time(v.m_secsSinceEpoch));
  return *this;
}

Parameters& Parameters::setTimestamp(uint32_t paramNum, const Timestamp& v) {
  checkBounds(--paramNum);
  m_values[paramNum].set(thrift::Timestamp(v.getTotalNanos()));
  return *this;
}

Parameters& Parameters::setBinary(uint32_t paramNum, const std::string& v) {
  checkBounds(--paramNum);
  m_values[paramNum].setBinary(v);
  return *this;
}

Parameters& Parameters::setBinary(uint32_t paramNum, std::string&& v) {
  checkBounds(--paramNum);
  m_values[paramNum].setBinary(std::move(v));
  return *this;
}

Parameters& Parameters::setBinary(uint32_t paramNum, const int8_t* v,
    const size_t len) {
  checkBounds(--paramNum);
  m_values[paramNum].setBinary(std::move(std::string((const char*)v, len)));
  return *this;
}

Parameters& Parameters::setArray(uint32_t paramNum, const thrift::Array& v) {
  checkBounds(--paramNum);
  m_values[paramNum].setArray(v);
  return *this;
}

Parameters& Parameters::setArray(uint32_t paramNum, thrift::Array&& v) {
  checkBounds(--paramNum);
  m_values[paramNum].setArray(std::move(v));
  return *this;
}

Parameters& Parameters::setMap(uint32_t paramNum, const thrift::Map& v) {
  checkBounds(--paramNum);
  m_values[paramNum].setMap(v);
  return *this;
}

Parameters& Parameters::setMap(uint32_t paramNum, thrift::Map&& v) {
  checkBounds(--paramNum);
  m_values[paramNum].setMap(std::move(v));
  return *this;
}

Parameters& Parameters::setStruct(uint32_t paramNum, const thrift::Struct& v) {
  checkBounds(--paramNum);
  m_values[paramNum].setStruct(v);
  return *this;
}

Parameters& Parameters::setStruct(uint32_t paramNum, thrift::Struct&& v) {
  checkBounds(--paramNum);
  m_values[paramNum].setStruct(std::move(v));
  return *this;
}

Parameters& Parameters::setNull(uint32_t paramNum, const bool v) {
  checkBounds(--paramNum);
  m_values[paramNum].setIsNull(v);
  return *this;
}
