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
 * Row.cpp
 */

#include "impl/pch.h"

#include "Row.h"

#include <limits>

using namespace io::snappydata;
using namespace io::snappydata::client;

std::shared_ptr<Decimal> Row::getDecimal(const uint32_t columnNum,
    const uint32_t realPrecision) const {
  const thrift::ColumnValue& cv = getColumnValue(columnNum);

  if (cv.getType() == thrift::SnappyType::DECIMAL) {
    return std::make_shared<Decimal>(*cv.getPtr<thrift::Decimal>());
  } else {
    return convertDecimal(cv, columnNum, realPrecision);
  }
}

std::shared_ptr<std::string> Row::getBinary(
    const uint32_t columnNum) const {
  const thrift::ColumnValue& cv = getColumnValue(columnNum);

  if (cv.getType() == thrift::SnappyType::VARBINARY) {
    return cv.getPtr<thrift::Binary>();
  } else {
    return convertBinary(cv, columnNum);
  }
}

std::shared_ptr<thrift::Array> Row::getArray(
    const uint32_t columnNum) const {
  const thrift::ColumnValue& cv = getColumnValue(columnNum);

  if (cv.getType() == thrift::SnappyType::ARRAY) {
    return cv.getPtr<thrift::Array>();
  } else {
    throw GET_DATACONVERSION_ERROR(cv, "ARRAY", columnNum);
  }
}

std::shared_ptr<thrift::Map> Row::getMap(const uint32_t columnNum) const {
  const thrift::ColumnValue& cv = getColumnValue(columnNum);

  if (cv.getType() == thrift::SnappyType::MAP) {
    return cv.getPtr<thrift::Map>();
  } else {
    throw GET_DATACONVERSION_ERROR(cv, "MAP", columnNum);
  }
}

std::shared_ptr<thrift::Struct> Row::getStruct(
    const uint32_t columnNum) const {
  const thrift::ColumnValue& cv = getColumnValue(columnNum);

  if (cv.getType() == thrift::SnappyType::STRUCT) {
    return cv.getPtr<thrift::Struct>();
  } else {
    throw GET_DATACONVERSION_ERROR(cv, "STRUCT", columnNum);
  }
}

namespace {

  using namespace io::snappydata;

  template <typename NT>
  static NT decimalToExactNumeric(const thrift::ColumnValue& cv,
      const uint32_t columnNum, const char* typeName) {
    Decimal d(*cv.getPtr<thrift::Decimal>());
    int64_t result;
    if (d.toInt64(result, false)
      // cast min to avoid issues with signed/unsigned conversions
      && result >= static_cast<int64_t>(std::numeric_limits<NT>::min())
      // disable signed/unsigned comparison warning for uint64_t since the
      // comparison against min() above already ensures "result" is positive
#ifdef _WINDOWS
#pragma warning(push)
#pragma warning(disable: 4018)
#endif
      && result <= static_cast<int64_t>(std::numeric_limits<NT>::max())) {
#ifdef _WINDOWS
#pragma warning(pop)
#endif
		return static_cast<NT>(result);
    } else {
      std::string s;
      d.toString(s);
      Utils::throwDataOutsideRangeError(typeName, columnNum, s.c_str());
    }
  }

  template <typename NT>
  static NT decimalToApproxNumeric(const thrift::ColumnValue& cv,
      const uint32_t columnNum, const char* typeName) {
    Decimal d(*cv.getPtr<thrift::Decimal>());
    double result;
    if (d.toDouble(result)
        && result >= static_cast<double>(std::numeric_limits<NT>::min())
        && result <= static_cast<double>(std::numeric_limits<NT>::max())) {
      return static_cast<NT>(result);
    } else {
      std::string s;
      d.toString(s);
      Utils::throwDataOutsideRangeError(typeName, columnNum, s.c_str());
    }
  }

  // TODO: can write visitors for most of the conversion methods in this class

  // Utils uses karma::generate which is the fastest converter
  // that has been tested (beats sprintf easily)
  class ToString : public boost::static_visitor<std::shared_ptr<std::string>> {
  private:
    const thrift::ColumnValue& m_cv;
    const uint32_t m_columnNum;
    const uint32_t m_realPrecision;

  public:
    ToString(const thrift::ColumnValue& cv, const uint32_t columnNum,
        const uint32_t realPrecision) :
        m_cv(cv), m_columnNum(columnNum), m_realPrecision(realPrecision) {
    }

    std::shared_ptr<std::string> operator()(const bool& v) const {
      return std::make_shared<std::string>(v ? "true" : "false");
    }

    std::shared_ptr<std::string> operator()(const int8_t& v) const {
      auto result = std::make_shared<std::string>();
      Utils::convertByteToString(v, *result);
      return result;
    }

    std::shared_ptr<std::string> operator()(const int16_t& v) const {
      auto result = std::make_shared<std::string>();
      Utils::convertShortToString(v, *result);
      return result;
    }

    std::shared_ptr<std::string> operator()(const int32_t& v) const {
      auto result = std::make_shared<std::string>();
      Utils::convertIntToString(v, *result);
      return result;
    }

    std::shared_ptr<std::string> operator()(const int64_t& v) const {
      auto result = std::make_shared<std::string>();
      Utils::convertInt64ToString(v, *result);
      return result;
    }

    std::shared_ptr<std::string> operator()(const float& v) const {
      auto result = std::make_shared<std::string>();
      Utils::convertFloatToString(v, *result, m_realPrecision);
      return result;
    }

    std::shared_ptr<std::string> operator()(const double& v) const {
      auto result = std::make_shared<std::string>();
      Utils::convertDoubleToString(v, *result, m_realPrecision);
      return result;
    }

    std::shared_ptr<std::string> operator()(
        const std::shared_ptr<std::string>& v) const {
      return v;
    }

    std::shared_ptr<std::string> operator()(
        const std::shared_ptr<thrift::Decimal>& v) const {
      auto str = std::make_shared<std::string>();
      Decimal d(*v);
      d.toString(*str);
      return str;
    }

    std::shared_ptr<std::string> operator()(const thrift::Date& v) const {
      auto str = std::make_shared<std::string>();
      client::types::DateTime dt(v.m_elapsed);
      dt.toDate(*str);
      return str;
    }

    std::shared_ptr<std::string> operator()(const thrift::Time& v) const {
      auto str = std::make_shared<std::string>();
      client::types::DateTime dt(v.m_elapsed);
      dt.toTime(*str);
      return str;
    }

    std::shared_ptr<std::string> operator()(
        const thrift::Timestamp& v) const {
      auto str = std::make_shared<std::string>();
      Timestamp ts(v.m_elapsed);
      ts.toString(*str);
      return str;
    }

    std::shared_ptr<std::string> operator()(
        const std::shared_ptr<thrift::ClobChunk>& v) const {
      return Row::getFullClobData(v, m_cv, m_columnNum, "VARCHAR");
    }

    std::shared_ptr<std::string> operator()(
        const std::shared_ptr<thrift::Array>& v) const {
      std::string str;
      str.append("ARRAY(");
      auto iter = v->cbegin();
      auto iterEnd = v->cend();
      if (iter != iterEnd) {
        str.append(*iter->visit(*this));
        ++iter;
        while (iter != iterEnd) {
          str.append(", ");
          str.append(*iter->visit(*this));
          ++iter;
        }
      }
      str.push_back(')');
      return std::make_shared<std::string>(std::move(str));
    }

    std::shared_ptr<std::string> operator()(
        const std::shared_ptr<thrift::Map>& v) const {
      std::string str;
      str.append("MAP(");
      auto iter = v->cbegin();
      auto iterEnd = v->cend();
      bool firstCall = true;
      while (iter != iterEnd) {
        if (firstCall) {
          firstCall = false;
        } else {
          str.append(", ");
        }
        str.append(*iter->first.visit(*this));
        str.append(" = ");
        str.append(*iter->second.visit(*this));
        ++iter;
      }
      str.push_back(')');
      return std::make_shared<std::string>(std::move(str));
    }

    std::shared_ptr<std::string> operator()(
        const std::shared_ptr<thrift::Struct>& v) const {
      std::string str;
      str.append("STRUCT(");
      auto iter = v->cbegin();
      auto iterEnd = v->cend();
      if (iter != iterEnd) {
        str.append(*iter->visit(*this));
        ++iter;
        while (iter != iterEnd) {
          str.append(", ");
          str.append(*iter->visit(*this));
          ++iter;
        }
      }
      str.push_back(')');
      return std::make_shared<std::string>(std::move(str));
    }

    std::shared_ptr<std::string> operator()(const thrift::NullType& v) const {
      if (v.m_v) {
        return std::shared_ptr<std::string>();
      } else {
        return std::make_shared<std::string>();
      }
    }

    template <typename T>
    std::shared_ptr<std::string> operator()(const T& v) const {
      SKIP_UNUSED_WARNING(v);
      throw GET_DATACONVERSION_ERROR(m_cv, "VARCHAR", m_columnNum);
    }
  };
}

bool Row::convertBoolean(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  switch (cv.getType()) {
    case thrift::SnappyType::SMALLINT:
      return cv.get<int16_t>() != 0;
    case thrift::SnappyType::INTEGER:
      return cv.get<int32_t>() != 0;
    case thrift::SnappyType::BIGINT:
      return cv.get<int64_t>() != 0;
    case thrift::SnappyType::NULLTYPE:
      return !cv.isNull();
    case thrift::SnappyType::TINYINT:
      return cv.get<int8_t>() != 0;
    case thrift::SnappyType::DOUBLE:
      return cv.get<double>() != 0.0;
    case thrift::SnappyType::FLOAT:
      return cv.get<float>() != 0.0f;
    case thrift::SnappyType::DECIMAL:
      return Decimal(*cv.getPtr<thrift::Decimal>()) != Decimal::ZERO;
    case thrift::SnappyType::VARCHAR: {
      auto v = cv.getString();
      if (*v == "true" || *v == "1") {
        return true;
      } else {
        std::string s = boost::algorithm::trim_copy(*v);
        boost::algorithm::to_lower(s);
        return !(s == "0" || s == "false");
      }
    }
    default:
      throw GET_DATACONVERSION_ERROR(cv, "BOOLEAN", columnNum);
  }
}

int8_t Row::convertByte(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  try {
    switch (cv.getType()) {
      case thrift::SnappyType::SMALLINT:
        return boost::numeric_cast<int8_t>(cv.get<int16_t>());
      case thrift::SnappyType::INTEGER:
        return boost::numeric_cast<int8_t>(cv.get<int32_t>());
      case thrift::SnappyType::BIGINT:
        return boost::numeric_cast<int8_t>(cv.get<int64_t>());
      case thrift::SnappyType::NULLTYPE:
        return cv.isNull() ? 0 : 1;
      case thrift::SnappyType::BOOLEAN:
        return cv.get<bool>() ? 1 : 0;
      case thrift::SnappyType::DOUBLE:
        return boost::numeric_cast<int8_t>(cv.get<double>());
      case thrift::SnappyType::FLOAT:
        return boost::numeric_cast<int8_t>(cv.get<float>());
      case thrift::SnappyType::DECIMAL:
        return ::decimalToExactNumeric<int8_t>(cv, columnNum, "TINYINT");
      case thrift::SnappyType::VARCHAR:
        return boost::lexical_cast<int8_t>(*cv.getString());
      default:
        break;
    }
  } catch (const std::exception& ex) {
    Utils::throwDataFormatError("TINYINT", cv, columnNum, ex.what());
  }
  throw GET_DATACONVERSION_ERROR(cv, "TINYINT", columnNum);
}

uint8_t Row::convertUnsignedByte(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  try {
    switch (cv.getType()) {
      case thrift::SnappyType::SMALLINT:
        return boost::numeric_cast<uint8_t>(cv.get<int16_t>());
      case thrift::SnappyType::INTEGER:
        return boost::numeric_cast<uint8_t>(cv.get<int32_t>());
      case thrift::SnappyType::BIGINT:
        return boost::numeric_cast<uint8_t>(cv.get<int64_t>());
      case thrift::SnappyType::NULLTYPE:
        return cv.isNull() ? 0 : 1;
      case thrift::SnappyType::BOOLEAN:
        return cv.get<bool>() ? 1 : 0;
      case thrift::SnappyType::DOUBLE:
        return boost::numeric_cast<uint8_t>(cv.get<double>());
      case thrift::SnappyType::FLOAT:
        return boost::numeric_cast<uint8_t>(cv.get<float>());
      case thrift::SnappyType::DECIMAL:
        return ::decimalToExactNumeric<uint8_t>(cv, columnNum, "TINYINT");
      case thrift::SnappyType::VARCHAR:
        return boost::lexical_cast<uint8_t>(*cv.getString());
      default:
        break;
    }
  } catch (const std::exception& ex) {
    Utils::throwDataFormatError("TINYINT", cv, columnNum, ex.what());
  }
  throw GET_DATACONVERSION_ERROR(cv, "TINYINT", columnNum);
}

int16_t Row::convertShort(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  try {
    switch (cv.getType()) {
      case thrift::SnappyType::INTEGER:
        return boost::numeric_cast<int16_t>(cv.get<int32_t>());
      case thrift::SnappyType::BIGINT:
        return boost::numeric_cast<int16_t>(cv.get<int64_t>());
      case thrift::SnappyType::TINYINT:
        return cv.get<int8_t>();
      case thrift::SnappyType::NULLTYPE:
        return cv.isNull() ? 0 : 1;
      case thrift::SnappyType::BOOLEAN:
        return cv.get<bool>() ? 1 : 0;
      case thrift::SnappyType::DOUBLE:
        return boost::numeric_cast<int16_t>(cv.get<double>());
      case thrift::SnappyType::FLOAT:
        return boost::numeric_cast<int16_t>(cv.get<float>());
      case thrift::SnappyType::DECIMAL:
        return ::decimalToExactNumeric<int16_t>(cv, columnNum, "SMALLINT");
      case thrift::SnappyType::VARCHAR:
        return boost::lexical_cast<int16_t>(*cv.getString());
      default:
        break;
    }
  } catch (const std::exception& ex) {
    Utils::throwDataFormatError("SMALLINT", cv, columnNum, ex.what());
  }
  throw GET_DATACONVERSION_ERROR(cv, "SMALLINT", columnNum);
}

uint16_t Row::convertUnsignedShort(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  try {
    switch (cv.getType()) {
      case thrift::SnappyType::INTEGER:
        return boost::numeric_cast<uint16_t>(cv.get<int32_t>());
      case thrift::SnappyType::BIGINT:
        return boost::numeric_cast<uint16_t>(cv.get<int64_t>());
      case thrift::SnappyType::TINYINT:
        return boost::numeric_cast<uint16_t>(cv.get<int8_t>());
      case thrift::SnappyType::NULLTYPE:
        return cv.isNull() ? 0 : 1;
      case thrift::SnappyType::BOOLEAN:
        return cv.get<bool>() ? 1 : 0;
      case thrift::SnappyType::DOUBLE:
        return boost::numeric_cast<uint16_t>(cv.get<double>());
      case thrift::SnappyType::FLOAT:
        return boost::numeric_cast<uint16_t>(cv.get<float>());
      case thrift::SnappyType::DECIMAL:
        return ::decimalToExactNumeric<uint16_t>(cv, columnNum, "SMALLINT");
      case thrift::SnappyType::VARCHAR:
        return boost::lexical_cast<uint16_t>(*cv.getString());
      default:
        break;
    }
  } catch (const std::exception& ex) {
    Utils::throwDataFormatError("SMALLINT", cv, columnNum, ex.what());
  }
  throw GET_DATACONVERSION_ERROR(cv, "SMALLINT", columnNum);
}

int32_t Row::convertInt(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  try {
    switch (cv.getType()) {
      case thrift::SnappyType::SMALLINT:
        return cv.get<int16_t>();
      case thrift::SnappyType::BIGINT:
        return boost::numeric_cast<int32_t>(cv.get<int64_t>());
      case thrift::SnappyType::TINYINT:
        return cv.get<int8_t>();
      case thrift::SnappyType::NULLTYPE:
        return cv.isNull() ? 0 : 1;
      case thrift::SnappyType::BOOLEAN:
        return cv.get<bool>() ? 1 : 0;
      case thrift::SnappyType::DOUBLE:
        return boost::numeric_cast<int32_t>(cv.get<double>());
      case thrift::SnappyType::FLOAT:
        return boost::numeric_cast<int32_t>(cv.get<float>());
      case thrift::SnappyType::DECIMAL:
        return ::decimalToExactNumeric<int32_t>(cv, columnNum, "INTEGER");
      case thrift::SnappyType::VARCHAR:
        return boost::lexical_cast<int32_t>(*cv.getString());
      default:
        break;
    }
  } catch (const std::exception& ex) {
    Utils::throwDataFormatError("INTEGER", cv, columnNum, ex.what());
  }
  throw GET_DATACONVERSION_ERROR(cv, "INTEGER", columnNum);
}

uint32_t Row::convertUnsignedInt(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  try {
    switch (cv.getType()) {
      case thrift::SnappyType::SMALLINT:
        return boost::numeric_cast<uint32_t>(cv.get<int16_t>());
      case thrift::SnappyType::BIGINT:
        return boost::numeric_cast<uint32_t>(cv.get<int64_t>());
      case thrift::SnappyType::TINYINT:
        return boost::numeric_cast<uint32_t>(cv.get<int8_t>());
      case thrift::SnappyType::NULLTYPE:
        return cv.isNull() ? 0 : 1;
      case thrift::SnappyType::BOOLEAN:
        return cv.get<bool>() ? 1 : 0;
      case thrift::SnappyType::DOUBLE:
        return boost::numeric_cast<uint32_t>(cv.get<double>());
      case thrift::SnappyType::FLOAT:
        return boost::numeric_cast<uint32_t>(cv.get<float>());
      case thrift::SnappyType::DECIMAL:
        return ::decimalToExactNumeric<uint32_t>(cv, columnNum, "INTEGER");
      case thrift::SnappyType::VARCHAR:
        return boost::lexical_cast<uint32_t>(*cv.getString());
      default:
        break;
    }
  } catch (const std::exception& ex) {
    Utils::throwDataFormatError("INTEGER", cv, columnNum, ex.what());
  }
  throw GET_DATACONVERSION_ERROR(cv, "INTEGER", columnNum);
}

int64_t Row::convertInt64(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  try {
    switch (cv.getType()) {
      case thrift::SnappyType::SMALLINT:
        return cv.get<int16_t>();
      case thrift::SnappyType::INTEGER:
        return cv.get<int32_t>();
      case thrift::SnappyType::TINYINT:
        return cv.get<int8_t>();
      case thrift::SnappyType::NULLTYPE:
        return cv.isNull() ? 0 : 1;
      case thrift::SnappyType::BOOLEAN:
        return cv.get<bool>() ? 1 : 0;
      case thrift::SnappyType::DOUBLE:
        return boost::numeric_cast<int64_t>(cv.get<double>());
      case thrift::SnappyType::FLOAT:
        return boost::numeric_cast<int64_t>(cv.get<float>());
      case thrift::SnappyType::DECIMAL:
        return ::decimalToExactNumeric<int64_t>(cv, columnNum, "BIGINT");
      case thrift::SnappyType::VARCHAR:
        return boost::lexical_cast<int64_t>(*cv.getString());
      default:
        break;
    }
  } catch (const std::exception& ex) {
    Utils::throwDataFormatError("BIGINT", cv, columnNum, ex.what());
  }
  throw GET_DATACONVERSION_ERROR(cv, "BIGINT", columnNum);
}

uint64_t Row::convertUnsignedInt64(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  try {
    switch (cv.getType()) {
      case thrift::SnappyType::SMALLINT:
        return boost::numeric_cast<uint64_t>(cv.get<int16_t>());
      case thrift::SnappyType::INTEGER:
        return boost::numeric_cast<uint64_t>(cv.get<int32_t>());
      case thrift::SnappyType::TINYINT:
        return boost::numeric_cast<uint64_t>(cv.get<int8_t>());
      case thrift::SnappyType::NULLTYPE:
        return cv.isNull() ? 0 : 1;
      case thrift::SnappyType::BOOLEAN:
        return cv.get<bool>() ? 1 : 0;
      case thrift::SnappyType::DOUBLE:
        return boost::numeric_cast<uint64_t>(cv.get<double>());
      case thrift::SnappyType::FLOAT:
        return boost::numeric_cast<uint64_t>(cv.get<float>());
      case thrift::SnappyType::DECIMAL:
        return ::decimalToExactNumeric<uint64_t>(cv, columnNum, "BIGINT");
      case thrift::SnappyType::VARCHAR:
        return boost::lexical_cast<uint64_t>(*cv.getString());
      default:
        break;
    }
  } catch (const std::exception& ex) {
    Utils::throwDataFormatError("BIGINT", cv, columnNum, ex.what());
  }
  throw GET_DATACONVERSION_ERROR(cv, "BIGINT", columnNum);
}

float Row::convertFloat(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  try {
    switch (cv.getType()) {
      case thrift::SnappyType::SMALLINT:
        return cv.get<int16_t>();
      case thrift::SnappyType::INTEGER:
        return boost::numeric_cast<float>(cv.get<int32_t>());
      case thrift::SnappyType::BIGINT:
        return boost::numeric_cast<float>(cv.get<int64_t>());
      case thrift::SnappyType::TINYINT:
        return cv.get<int8_t>();
      case thrift::SnappyType::NULLTYPE:
        return cv.isNull() ? 0.0f : 1.0f;
      case thrift::SnappyType::BOOLEAN:
        return cv.get<bool>() ? 1.0f : 0.0f;
      case thrift::SnappyType::DOUBLE:
      case thrift::SnappyType::FLOAT:
        return boost::numeric_cast<float>(cv.get<double>());
      case thrift::SnappyType::DECIMAL:
        return ::decimalToApproxNumeric<float>(cv, columnNum, "FLOAT");
      case thrift::SnappyType::VARCHAR:
        return boost::lexical_cast<float>(*cv.getString());
      default:
        break;
    }
  } catch (const std::exception& ex) {
    Utils::throwDataFormatError("FLOAT", cv, columnNum, ex.what());
  }
  throw GET_DATACONVERSION_ERROR(cv, "FLOAT", columnNum);
}

double Row::convertDouble(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  try {
    switch (cv.getType()) {
      case thrift::SnappyType::SMALLINT:
        return cv.get<int16_t>();
      case thrift::SnappyType::INTEGER:
        return cv.get<int32_t>();
      case thrift::SnappyType::BIGINT:
        return boost::numeric_cast<double>(cv.get<int64_t>());
      case thrift::SnappyType::TINYINT:
        return cv.get<int8_t>();
      case thrift::SnappyType::NULLTYPE:
        return cv.isNull() ? 0.0 : 1.0;
      case thrift::SnappyType::BOOLEAN:
        return cv.get<bool>() ? 1.0 : 0.0;
      case thrift::SnappyType::FLOAT:
        return cv.get<float>();
      case thrift::SnappyType::DECIMAL:
        return ::decimalToApproxNumeric<double>(cv, columnNum, "DOUBLE");
      case thrift::SnappyType::VARCHAR:
        return boost::lexical_cast<double>(*cv.getString());
      default:
        break;
    }
  } catch (const std::exception& ex) {
    Utils::throwDataFormatError("DOUBLE", cv, columnNum, ex.what());
  }
  throw GET_DATACONVERSION_ERROR(cv, "DOUBLE", columnNum);
}

std::shared_ptr<std::string> Row::convertString(const thrift::ColumnValue& cv,
    const uint32_t columnNum, const uint32_t realPrecision) const {
  return cv.visit(::ToString(cv, columnNum, realPrecision));
}

std::shared_ptr<Decimal> Row::convertDecimal(const thrift::ColumnValue& cv,
    const uint32_t columnNum, const uint32_t realPrecision) const {
  switch (cv.getType()) {
    case thrift::SnappyType::SMALLINT:
      return std::make_shared<Decimal>(cv.get<int16_t>());
    case thrift::SnappyType::INTEGER:
      return std::make_shared<Decimal>(cv.get<int32_t>());
    case thrift::SnappyType::BIGINT:
      return std::make_shared<Decimal>(cv.get<int64_t>());
    case thrift::SnappyType::TINYINT:
      return std::make_shared<Decimal>(cv.get<int8_t>());
    case thrift::SnappyType::NULLTYPE:
      if (cv.isNull()) return std::shared_ptr<Decimal>();
      else return std::make_shared<Decimal>((uint32_t)1);
    case thrift::SnappyType::BOOLEAN:
      return std::make_shared<Decimal>((uint32_t)(cv.get<bool>() ? 1 : 0));
    case thrift::SnappyType::FLOAT:
      return std::make_shared<Decimal>(cv.get<float>(), realPrecision);
    case thrift::SnappyType::DOUBLE:
      return std::make_shared<Decimal>(cv.get<double>(), realPrecision);
    case thrift::SnappyType::VARCHAR:
      return std::make_shared<Decimal>(*cv.getString(), columnNum);
    default:
      break;
  }
  throw GET_DATACONVERSION_ERROR(cv, "DECIMAL", columnNum);
}

// TODO: PERF: not efficient but probably does not matter since in nearly
// all cases we would expect underlying type to also be DECIMAL
std::shared_ptr<thrift::Decimal> Row::convertTDecimal(const thrift::ColumnValue& cv,
    const uint32_t columnNum, const uint32_t realPrecision) const {
  switch (cv.getType()) {
    case thrift::SnappyType::SMALLINT: {
      Decimal dec(cv.get<int16_t>());
      auto tdec = std::make_shared<thrift::Decimal>();
      dec.copyTo(*tdec);
      return tdec;
    }
    case thrift::SnappyType::INTEGER: {
      Decimal dec(cv.get<int32_t>());
      auto tdec = std::make_shared<thrift::Decimal>();
      dec.copyTo(*tdec);
      return tdec;
    }
    case thrift::SnappyType::BIGINT: {
      Decimal dec(cv.get<int64_t>());
      auto tdec = std::make_shared<thrift::Decimal>();
      dec.copyTo(*tdec);
      return tdec;
    }
    case thrift::SnappyType::TINYINT: {
      Decimal dec(cv.get<int8_t>());
      auto tdec = std::make_shared<thrift::Decimal>();
      dec.copyTo(*tdec);
      return tdec;
    }
    case thrift::SnappyType::NULLTYPE:
      if (cv.isNull()) {
        return std::shared_ptr<thrift::Decimal>();
      } else {
        auto tdec = std::make_shared<thrift::Decimal>();
        Decimal::ONE.copyTo(*tdec);
        return tdec;
      }
    case thrift::SnappyType::BOOLEAN: {
      auto tdec = std::make_shared<thrift::Decimal>();
      if (cv.get<bool>()) {
        Decimal::ONE.copyTo(*tdec);
      } else {
        Decimal::ZERO.copyTo(*tdec);
      }
      return tdec;
    }
    case thrift::SnappyType::FLOAT: {
      Decimal dec(cv.get<float>(), realPrecision);
      auto tdec = std::make_shared<thrift::Decimal>();
      dec.copyTo(*tdec);
      return tdec;
    }
    case thrift::SnappyType::DOUBLE: {
      Decimal dec(cv.get<double>(), realPrecision);
      auto tdec = std::make_shared<thrift::Decimal>();
      dec.copyTo(*tdec);
      return tdec;
    }
    case thrift::SnappyType::VARCHAR: {
      Decimal dec(*cv.getString(), columnNum);
      auto tdec = std::make_shared<thrift::Decimal>();
      dec.copyTo(*tdec);
      return tdec;
    }
    default:
      break;
  }
  throw GET_DATACONVERSION_ERROR(cv, "DECIMAL", columnNum);
}

DateTime Row::convertDate(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  switch (cv.getType()) {
    case thrift::SnappyType::TIMESTAMP:
      return DateTime(cv.get<thrift::Timestamp>().m_elapsed / 1000000000L);
    case thrift::SnappyType::TIME:
      return DateTime(cv.get<thrift::Time>().m_elapsed);
    case thrift::SnappyType::NULLTYPE:
      return DateTime(0);
    case thrift::SnappyType::VARCHAR: {
      std::string v = boost::algorithm::trim_copy(*cv.getString());
      return DateTime::parseDate(v, columnNum);
    }
    default:
      break;
  }
  throw GET_DATACONVERSION_ERROR(cv, "DATE", columnNum);
}

DateTime Row::convertTime(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  switch (cv.getType()) {
    case thrift::SnappyType::TIMESTAMP:
      return DateTime(cv.get<thrift::Timestamp>().m_elapsed / 1000000000L);
    case thrift::SnappyType::DATE:
      return DateTime(cv.get<thrift::Date>().m_elapsed);
    case thrift::SnappyType::NULLTYPE:
      return DateTime(0);
    case thrift::SnappyType::VARCHAR: {
      std::string v = boost::algorithm::trim_copy(*cv.getString());
      return DateTime::parseTime(v, columnNum);
    }
    default:
      break;
  }
  throw GET_DATACONVERSION_ERROR(cv, "TIME", columnNum);
}

Timestamp Row::convertTimestamp(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  switch (cv.getType()) {
    case thrift::SnappyType::NULLTYPE:
      return Timestamp(0L);
    case thrift::SnappyType::DATE:
      return Timestamp(cv.get<thrift::Date>().m_elapsed, 0);
    case thrift::SnappyType::TIME:
      return Timestamp(cv.get<thrift::Time>().m_elapsed, 0);
    case thrift::SnappyType::VARCHAR: {
      std::string v = boost::algorithm::trim_copy(*cv.getString());
      return Timestamp::parseString(v, columnNum);
    }
    default:
      break;
  }
  throw GET_DATACONVERSION_ERROR(cv, "TIMESTAMP", columnNum);
}

std::shared_ptr<std::string> Row::convertBinary(const thrift::ColumnValue& cv,
    const uint32_t columnNum) const {
  switch (cv.getType()) {
    case thrift::SnappyType::BLOB:
      return getFullBlobData(cv.getPtr<thrift::BlobChunk>(), cv, columnNum,
          "VARBINARY");
    case thrift::SnappyType::CLOB:
      return getFullClobData(cv.getPtr<thrift::ClobChunk>(), cv, columnNum,
          "VARBINARY");
    case thrift::SnappyType::NULLTYPE:
      if (cv.isNull()) return std::shared_ptr<std::string>();
      else return std::make_shared<std::string>();
    case thrift::SnappyType::VARCHAR: {
      return cv.getString();
    default:
      break;
    }
  }
  throw GET_DATACONVERSION_ERROR(cv, "VARBINARY", columnNum);
}

std::shared_ptr<std::string> Row::getFullBlobData(
    const std::shared_ptr<thrift::BlobChunk>& blob,
    const thrift::ColumnValue& cv, const uint32_t columnNum,
    const char* forType) {
  // TODO: add proper BLOB/CLOB support
  if (blob) {
    if (blob->last) {
      // TODO: SW: either fix BlobChunk to have std::shared_ptr<std::string>
      // as chunk or ensure that calls use std::move semantics where possible
      return std::make_shared<std::string>(blob->chunk);
    } else {
      throw GET_DATACONVERSION_ERROR(cv, forType, columnNum);
    }
  } else {
    return std::shared_ptr<std::string>();
  }
}

std::shared_ptr<std::string> Row::getFullClobData(
    const std::shared_ptr<thrift::ClobChunk>& clob,
    const thrift::ColumnValue& cv, const uint32_t columnNum,
    const char* forType) {
  // TODO: add proper BLOB/CLOB support
  // TODO: also add support for passing in char* to fill in (e.g. from ODBC)
  // instead of having to create a copy here from multiple chunks and then
  // ODBC/... driver has to make another copy; also see if std::move can be
  // used in the API itself so that the call below can avoid the same
  if (clob) {
    if (clob->last) {
      // TODO: SW: either fix ClobChunk to have std::shared_ptr<std::string>
      // as chunk or ensure that calls use std::move semantics where possible
      return std::make_shared<std::string>(clob->chunk);
    } else {
      throw GET_DATACONVERSION_ERROR(cv, forType, columnNum);
    }
  } else {
    return std::shared_ptr<std::string>();
  }
}
