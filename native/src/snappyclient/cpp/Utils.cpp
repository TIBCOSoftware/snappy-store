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
 * Utils.cpp
 */

#include "Utils.h"

#include <sstream>
#include <iostream>
#ifdef __GNUC__
extern "C" {
#  include <cxxabi.h>
}
#endif

#include <boost/config.hpp>
#include <boost/lexical_cast.hpp>
#include <boost/spirit/include/karma.hpp>
#include <cmath>

#include <thrift/Thrift.h>
#include <thrift/protocol/TProtocolException.h>
#include <thrift/transport/TTransportException.h>

#include "LogWriter.h"
#include "SQLException.h"
#include "impl/InternalUtils.h"

using namespace apache::thrift;
using namespace io::snappydata;
using namespace io::snappydata::client;

// static definitions declared in Base.h
const std::string io::snappydata::client::EMPTY_STRING;
const std::map<int32_t, OutputParameter> io::snappydata::client::EMPTY_OUTPUT_PARAMS;
const std::map<int32_t, thrift::OutputParameter> io::snappydata::client::EMPTY_OUT_PARAMS;

const char* Utils::getSQLTypeName(const thrift::ColumnValue& cv) {
  switch (cv.getType()) {
    case thrift::SnappyType::ARRAY:
      return "ARRAY";
    case thrift::SnappyType::BIGINT:
      return "BIGINT";
    case thrift::SnappyType::BINARY:
      return "BINARY";
    case thrift::SnappyType::BLOB:
      return "BLOB";
    case thrift::SnappyType::BOOLEAN:
      return "BOOLEAN";
    case thrift::SnappyType::CHAR:
      return "CHAR";
    case thrift::SnappyType::CLOB:
      return "CLOB";
    case thrift::SnappyType::DATE:
      return "DATE";
    case thrift::SnappyType::DECIMAL:
      return "DECIMAL";
    case thrift::SnappyType::DOUBLE:
      return "DOUBLE";
    case thrift::SnappyType::FLOAT:
      return "REAL";
    case thrift::SnappyType::INTEGER:
      return "INTEGER";
    case thrift::SnappyType::JAVA_OBJECT:
      return "JAVA_OBJECT";
    case thrift::SnappyType::JSON:
      return "JSON";
    case thrift::SnappyType::LONGVARBINARY:
      return "LONG VARBINARY";
    case thrift::SnappyType::LONGVARCHAR:
      return "LONG VARCHAR";
    case thrift::SnappyType::MAP:
      return "MAP";
    case thrift::SnappyType::NULLTYPE:
      return "NULL";
    case thrift::SnappyType::SMALLINT:
      return "SMALLINT";
    case thrift::SnappyType::SQLXML:
      return "XML";
    case thrift::SnappyType::STRUCT:
      return "STRUCT";
    case thrift::SnappyType::TIME:
      return "TIME";
    case thrift::SnappyType::TIMESTAMP:
      return "TIMESTAMP";
    case thrift::SnappyType::TINYINT:
      return "TINYINT";
    case thrift::SnappyType::VARBINARY:
      return "VARBINARY";
    case thrift::SnappyType::VARCHAR:
      return "VARCHAR";
    default:
      return "UNKNOWN";
  }
}

void Utils::getHostAddress(const std::string& hostNameAndAddress,
    const int port, thrift::HostAddress& result) {
  const size_t slashIndex = hostNameAndAddress.find('/');

  result.port = port;
  if (slashIndex == std::string::npos) {
    result.hostName = hostNameAndAddress;
  } else if (slashIndex > 0) {
    const std::string hostName = hostNameAndAddress.substr(0, slashIndex);
    const std::string ipAddress = hostNameAndAddress.substr(slashIndex + 1);
    if (ipAddress.size() > 0 && hostName != ipAddress) {
      result.hostName = hostName;
      // need to also set the __isset var for ipAddress as it is optional
      result.__set_ipAddress(ipAddress);
      return;
    } else {
      result.hostName = hostName;
    }
  } else if (slashIndex == 0) {
    result.hostName = hostNameAndAddress.substr(1);
  }
  if (result.__isset.ipAddress) {
    result.ipAddress.clear();
    result.__isset.ipAddress = false;
  }
}

void Utils::getHostPort(const std::string& hostPort, std::string& resultHost,
    int& resultPort) {
  const size_t len = hostPort.size();
  size_t spos;
  try {
    if (hostPort[len - 1] == ']'
        && (spos = hostPort.find('[')) != std::string::npos) {
      resultPort = boost::lexical_cast<int>(
          hostPort.substr(spos + 1, len - spos - 2));
      resultHost = hostPort.substr(0, spos);
      return;
    } else if ((spos = hostPort.find(':')) != std::string::npos) {
      resultPort = boost::lexical_cast<int>(hostPort.substr(spos + 1));
      resultHost = hostPort.substr(0, spos);
      return;
    }
  } catch (const std::exception& se) {
    std::string parseError(
        "INT {failed to parse integer port in host[port] string: ");
    parseError.append(hostPort);
    throwDataFormatError(parseError.c_str(), 0, se);
  }
  std::string parseError("{failed to split given host[port] string: ");
  parseError.append(hostPort);
  throwDataFormatError(parseError.c_str(), 0, NULL);
}

const char* Utils::getServerTypeString(
    thrift::ServerType::type serverType) noexcept {
  switch (serverType) {
    case thrift::ServerType::THRIFT_SNAPPY_CP:
      return "THRIFT_SERVER_COMPACTPROTOCOL";
    case thrift::ServerType::THRIFT_SNAPPY_BP:
      return "THRIFT_SERVER_BINARYPROTOCOL";
    case thrift::ServerType::THRIFT_SNAPPY_CP_SSL:
      return "THRIFT_SERVER_SSL_COMPACTPROTOCOL";
    case thrift::ServerType::THRIFT_SNAPPY_BP_SSL:
      return "THRIFT_SERVER_SSL_BINARYPROTOCOL";
    case thrift::ServerType::THRIFT_LOCATOR_CP:
      return "THRIFT_LOCATOR_COMPACTPROTOCOL";
    case thrift::ServerType::THRIFT_LOCATOR_BP:
      return "THRIFT_LOCATOR_BINARYPROTOCOL";
    case thrift::ServerType::THRIFT_LOCATOR_CP_SSL:
      return "THRIFT_LOCATOR_SSL_COMPACTPROTOCOL";
    case thrift::ServerType::THRIFT_LOCATOR_BP_SSL:
      return "THRIFT_LOCATOR_SSL_BINARYPROTOCOL";
    case thrift::ServerType::DRDA:
      return "DRDA";
    default:
      return "THRIFT_SERVER_COMPACTPROTOCOL";
  }
}

void Utils::toHexString(const char* bytes, const size_t bytesLen,
    std::ostream& out) {
  functor::WriteStream proc = { out };
  impl::InternalUtils::toHexString(bytes, bytesLen, proc);
}

void Utils::toHexString(const char* bytes, const size_t bytesLen,
    std::string& result) {
  functor::WriteString proc = { result };
  impl::InternalUtils::toHexString(bytes, bytesLen, proc);
}

bool Utils::convertUTF8ToUTF16(const char* utf8Chars, const int utf8Len,
    std::wstring& result) {
  functor::WriteWString proc = { result };
  return convertUTF8ToUTF16(utf8Chars, utf8Len, proc);
}

void Utils::convertUTF16ToUTF8(const wchar_t* utf16Chars, const int utf16Len,
    std::string& result) {
  functor::WriteString proc = { result };
  convertUTF16ToUTF8(utf16Chars, utf16Len, proc);
}

void Utils::convertUTF16ToUTF8(const wchar_t* utf16Chars, const int utf16Len,
    std::ostream& out) {
  functor::WriteStream proc = { out };
  convertUTF16ToUTF8(utf16Chars, utf16Len, proc);
}


template<typename TNum>
class PrecisionPolicy : public boost::spirit::karma::real_policies<TNum> {
private:
  const size_t m_precision2;
  const TNum m_minFixed;

  static TNum calcMinFixed(size_t precision) {
    double n = 1.0;
    precision = (precision * 2) / 3;
    while (precision-- > 0) {
      n /= 10.0;
    }
    return static_cast<TNum>(n);
  }

public:
  PrecisionPolicy(size_t precision = DEFAULT_REAL_PRECISION) :
      m_precision2((precision * 5) / 3), m_minFixed(calcMinFixed(precision)) {
    // using increased precision to take care of insignificant
    // zeros after decimal e.g. .0001 has only one significant decimal;
    // we already stop using fractions if less than half are significant
  }

  int floatfield(TNum n) const {
    if (boost::spirit::traits::test_zero(n)) {
      return boost::spirit::karma::real_policies<TNum>::fmtflags::fixed;
    }
    const TNum absn = std::abs(n);
    if (absn >= m_minFixed && absn <= 1e9) {
      return boost::spirit::karma::real_policies<TNum>::fmtflags::fixed;
    } else {
      return boost::spirit::karma::real_policies<TNum>::fmtflags::scientific;
    }
  }

  size_t precision(TNum n) const {
    return m_precision2;
  }

  template<typename OutputIterator>
  bool fraction_part(OutputIterator& sink, const TNum n,
      const size_t precision_, const size_t precision) const {
    // The following is equivalent to:
    //    generate(sink, right_align(precision, '0')[ulong], n);
    // but it's spelled out to avoid inter-modular dependencies.
    size_t digits = 0;
    TNum n1 = n;
    if (!boost::spirit::traits::test_zero(n)) {
      // the actual precision required is less than inflated m_precision2
      const size_t actualPrecision = (m_precision2 * 3) / 5;
      TNum d = 1.0;
      while (d <= n) {
        d *= 10.0;
        digits++;
        // if digits exceed actual precision, then reduce n
        if (digits > actualPrecision) {
          n1 = static_cast<TNum>((n1 + 5.0) / 10.0);
        }
      }
    }
    while (digits < precision_) {
      if (!boost::spirit::karma::char_inserter<>::call(sink, '0')) {
        return false;
      }
      digits++;
    }
    if (precision) {
      return boost::spirit::karma::int_inserter<10>::call(sink, n1);
    } else {
      return true;
    }
  }
};

typedef boost::spirit::karma::real_generator<double, PrecisionPolicy<float> >
    PrecisionFloatType;
typedef boost::spirit::karma::real_generator<double, PrecisionPolicy<double> >
    PrecisionDoubleType;

static const PrecisionFloatType floatPrecisionDef = PrecisionFloatType(
    PrecisionPolicy<float>(DEFAULT_REAL_PRECISION));
static const PrecisionDoubleType doublePrecisionDef = PrecisionDoubleType(
    PrecisionPolicy<double>(DEFAULT_REAL_PRECISION));

void Utils::convertByteToString(const int8_t v, std::string& result) {
  char buffer[4];
  char* pbuf = buffer;
  boost::spirit::karma::generate(pbuf, boost::spirit::byte_, v);
  result.append(buffer, pbuf - &buffer[0]);
}

void Utils::convertShortToString(const int16_t v, std::string& result) {
  char buffer[10];
  char* pbuf = buffer;
  boost::spirit::karma::generate(pbuf, boost::spirit::short_, v);
  result.append(buffer, pbuf - &buffer[0]);
}

void Utils::convertIntToString(const int32_t v, std::string& result) {
  char buffer[20];
  char* pbuf = buffer;
  boost::spirit::karma::generate(pbuf, boost::spirit::int_, v);
  result.append(buffer, pbuf - &buffer[0]);
}

void Utils::convertInt64ToString(const int64_t v, std::string& result) {
  char buffer[40];
  char* pbuf = buffer;
  boost::spirit::karma::generate(pbuf, boost::spirit::long_long, v);
  result.append(buffer, pbuf - &buffer[0]);
}

void Utils::convertUInt64ToString(const uint64_t v, std::string& result) {
  char buffer[40];
  char* pbuf = buffer;
  boost::spirit::karma::generate(pbuf, boost::spirit::ulong_long, v);
  result.append(buffer, pbuf - &buffer[0]);
}

void Utils::convertFloatToString(const float v, std::string& result,
    const size_t precision) {
  if (precision < 20) {
    char buffer[64];
    char* pbuf = buffer;
    boost::spirit::karma::generate(pbuf,
        precision == DEFAULT_REAL_PRECISION ? floatPrecisionDef :
            PrecisionFloatType(PrecisionPolicy<float>(precision)), v);
    result.append(buffer, pbuf - &buffer[0]);
  } else {
    // static buffer can overflow so better just use dynamically allocated array
    char* buffer = new char[precision * 2 + 24];
    DestroyArray<char> cleanBuf(buffer);
    char* pbuf = buffer;
    boost::spirit::karma::generate(pbuf,
        precision == DEFAULT_REAL_PRECISION ? floatPrecisionDef :
            PrecisionFloatType(PrecisionPolicy<float>(precision)), v);
    result.append(buffer, pbuf - &buffer[0]);
  }
}

void Utils::convertDoubleToString(const double v, std::string& result,
    const size_t precision) {
  if (precision < 20) {
    char buffer[64];
    char* pbuf = buffer;
    boost::spirit::karma::generate(pbuf,
        precision == DEFAULT_REAL_PRECISION ? doublePrecisionDef :
            PrecisionDoubleType(PrecisionPolicy<double>(precision)), v);
    result.append(buffer, pbuf - &buffer[0]);
  } else {
    // static buffer can overflow so better just use dynamically allocated array
    char* buffer = new char[precision * 2 + 24];
    DestroyArray<char> cleanBuf(buffer);
    char* pbuf = buffer;
    boost::spirit::karma::generate(pbuf,
        precision == DEFAULT_REAL_PRECISION ? doublePrecisionDef :
            PrecisionDoubleType(PrecisionPolicy<double>(precision)), v);
    result.append(buffer, pbuf - &buffer[0]);
  }
}

std::ostream& Utils::toStream(std::ostream& out,
    const thrift::HostAddress& hostAddr) {
  thrift::ServerType::type serverType = hostAddr.serverType;
  bool addServerType = true;
  if (!hostAddr.__isset.serverType || Utils::isServerTypeDefault(serverType)) {
    addServerType = false;
  }

  out << hostAddr.hostName;
  if (hostAddr.__isset.ipAddress) {
    out << '/' << hostAddr.ipAddress;
  }
  out << '[' << hostAddr.port << ']';
  if (addServerType) {
    out << '{' << serverType << '}';
  }
  return out;
}

std::ostream& Utils::toStream(std::ostream& out, const std::exception& stde) {
  demangleTypeName(typeid(stde).name(), out);
  const char* reason = stde.what();
  if (reason != NULL) {
    out << ": " << reason;
  }
  return out;
}

std::string Utils::toString(const std::exception& stde) {
  std::string str;
  demangleTypeName(typeid(stde).name(), str);
  const char* reason = stde.what();
  if (reason != NULL) {
    str.append(": ").append(reason);
  }
  return str;
}

void Utils::throwDataFormatError(const char* target,
    const uint32_t columnIndex, const char* cause) {
  std::ostringstream reason;
  if (columnIndex > 0) {
    reason << " at column " << columnIndex;
  }
  if (cause != NULL) {
    reason << ": " << cause;
  }
  throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_FORMAT_EXCEPTION_MSG, target,
      reason.str().c_str());
}

void Utils::throwDataFormatError(const char* target,
    const thrift::ColumnValue& srcValue, const uint32_t columnIndex,
    const char* cause) {
  std::ostringstream reason;
  reason << " for value '" << srcValue << '\'';
  if (columnIndex > 0) {
    reason << " at column " << columnIndex;
  }
  if (cause != NULL) {
    reason << ": " << cause;
  }
  throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_FORMAT_EXCEPTION_MSG, target,
      reason.str().c_str());
}

void Utils::throwDataFormatError(const char* target,
    const uint32_t columnIndex, const std::exception& cause) {
  std::ostringstream reason;
  if (columnIndex > 0) {
    reason << " at column " << columnIndex;
  }
  reason << ": " << cause;
  throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_FORMAT_EXCEPTION_MSG, target,
      reason.str().c_str());
}

void Utils::throwDataOutsideRangeError(const char* target,
    const uint32_t columnIndex, const char* cause) {
  std::ostringstream reason;
  if (columnIndex > 0) {
    reason << " at column " << columnIndex;
  }
  if (cause != NULL) {
    reason << ": " << cause;
  }
  throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_OUTSIDE_RANGE_FOR_DATATYPE_MSG,
      target, reason.str().c_str());
}

#ifdef __GNUC__
char* Utils::gnuDemangledName(const char* typeName) {
  int status;
  char* demangledName = abi::__cxa_demangle(typeName, NULL, NULL, &status);
  if (status == 0 && demangledName != NULL) {
    return demangledName;
  } else {
    return NULL;
  }
}
#endif

void Utils::demangleTypeName(const char* typeName, std::string& str) {
#ifdef __GNUC__
  char* demangledName = gnuDemangledName(typeName);
  if (demangledName != NULL) {
    str.append(demangledName);
    ::free(demangledName);
    return;
  }
#endif
  str.append(typeName);
}

void Utils::demangleTypeName(const char* typeName, std::ostream& out) {
#ifdef __GNUC__
  char* demangledName = gnuDemangledName(typeName);
  if (demangledName != NULL) {
    out << demangledName;
    ::free(demangledName);
    return;
  }
#endif
  out << typeName;
}

void Utils::handleExceptionInDestructor(const char* operation,
    const SQLException& sqle) {
  // ignore transport and protocol exceptions due to other side failing
  const std::string& sqlState = sqle.getSQLState();
  if (sqlState == SQLState::SNAPPY_NODE_SHUTDOWN.getSQLState()
      || sqlState == SQLState::DATA_CONTAINER_CLOSED.getSQLState()
      || sqlState == SQLState::THRIFT_PROTOCOL_ERROR.getSQLState()) {
    return;
  }
  try {
    LogWriter::error() << "Exception in destructor of " << operation << ": "
        << stack(sqle);
  } catch (...) {
    try {
      std::cerr << "FAILURE in logging during exception in destructor of "
          << operation << ": " << stack(sqle);
    } catch (...) {
      std::cerr << "FAILURE in logging an exception in destructor of "
          << operation << std::endl;
    }
  }
}

void Utils::handleExceptionInDestructor(const char* operation,
    const std::exception& se) {
  // ignore transport and protocol exceptions due to other side failing
  if (dynamic_cast<const transport::TTransportException*>(&se) == NULL
      && dynamic_cast<const protocol::TProtocolException*>(&se) == NULL) {
    LogWriter::error() << "Exception in destructor of " << operation << ": "
        << stack(se);
  }
}

void Utils::handleExceptionInDestructor(const char* operation) {
  LogWriter::error() << "Unknown exception in destructor of " << operation;
}

std::ostream& operator <<(std::ostream& out, const wchar_t* wstr) {
  Utils::convertUTF16ToUTF8(wstr, -1, out);
  return out;
}

std::ostream& operator <<(std::ostream& out,
    const thrift::ServerType::type& serverType) {
  return out << Utils::getServerTypeString(serverType);
}

std::ostream& operator <<(std::ostream& out, const _SqleHex& hexstr) {
  Utils::toHexString(hexstr.m_str.data(), hexstr.m_str.size(), out);
  return out;
}
