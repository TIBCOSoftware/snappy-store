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
 * Utils.cpp
 */

#include "impl/pch.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <sstream>
#include <typeinfo>

#ifdef __GNUC__
extern "C" {
#  include <cxxabi.h>
}
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-copy"
#pragma GCC diagnostic ignored "-Wdouble-promotion"
#pragma GCC diagnostic ignored "-Wshadow"
#pragma GCC diagnostic ignored "-Wsign-conversion"
#endif

#ifndef _WINDOWS
#include <boost/process.hpp>
#endif
#include <boost/spirit/include/karma.hpp>

#ifdef __GNUC__
#pragma GCC diagnostic pop
#endif

#ifdef _WINDOWS
extern "C" {
#  include <windows.h>
#  include <wincred.h>
}
#endif

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
  throwDataFormatError(parseError.c_str(), 0, nullptr);
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

std::string Utils::readPasswordFromManager(const std::string &user,
    const std::string &passwordKey) {
#ifdef _WINDOWS
  PCREDENTIAL pcred;
  if (::CredRead(passwordKey.c_str(), CRED_TYPE_GENERIC, 0, &pcred)) {
    std::unique_ptr<CREDENTIAL, decltype(&::CredFree)> p(pcred, ::CredFree);
    std::string passwd;
    if (pcred->CredentialBlobSize > 0) {
      convertUTF16ToUTF8((wchar_t*)pcred->CredentialBlob,
          pcred->CredentialBlobSize / sizeof(wchar_t), [&](char c) {
        passwd.push_back(c);
      });
    }
    return passwd;
  } else {
    std::string errMsg;
    DWORD err = ::GetLastError();
    switch (err) {
    case ERROR_NOT_FOUND:
      errMsg = "Missing credentials for ";
      errMsg.append(user);
      break;
    case ERROR_NO_SUCH_LOGON_SESSION:
      errMsg = "Login session not found to read credentials for ";
      errMsg.append(user);
      break;
    default:
      errMsg = "Credentials read failed for ";
      errMsg.append(user).append(" with code = ").append(std::to_string(err));
      break;
    }
    errMsg.append(" in Windows credential manager using the password key '")
      .append(passwordKey).append("'");
    throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, errMsg);
  }
#elif defined(__APPLE__)
  try {
    boost::process::ipstream out, err;
    std::stringstream outVal, errVal;

    const char *user = ::getenv("USER");
    if (!user) {
      user = ::getenv("LOGNAME");
    }
    if (!user) {
      throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION,
          "Keychain lookup failed to get the user with "
          "USER or LOGNAME environment variables");
    }
    boost::process::child c(boost::process::search_path("security"),
        "find-generic-password", "-w", "-a", user, "-s", passwordKey,
        boost::process::std_out > out, boost::process::std_err > err);

    while (out >> outVal.rdbuf()) {
    }
    while (err >> errVal.rdbuf()) {
    }

    c.wait();
    int exitCode = c.exit_code();
    if (exitCode != 0) {
      throw std::runtime_error(
          std::string("exit with error code ") + std::to_string(exitCode));
    }
    if (errVal.rdbuf()->in_avail() > 0) {
      throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, errVal.str());
    } else {
      return outVal.str();
    }
  } catch (SQLException &sqle) {
    throw sqle;
  } catch (std::exception &ex) {
    std::string err("Password lookup failure in 'security' tool for ");
    err.append(user).append(" using the password key '").append(
        passwordKey).append("' : ");
    throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, err.append(ex.what()));
  }
#else
  std::string attribute, value;
  size_t colonPos = passwordKey.find(':');
  if (colonPos == std::string::npos) {
    attribute = passwordKey;
  } else {
    attribute = passwordKey.substr(0, colonPos);
    value = passwordKey.substr(colonPos + 1);
  }
  try {
    boost::process::ipstream out, err;
    std::stringstream outVal, errVal;

    boost::process::child c(boost::process::search_path("secret-tool"),
        "lookup", attribute.c_str(), value.c_str(),
        boost::process::std_out > out, boost::process::std_err > err);

    while (out >> outVal.rdbuf()) {
    }
    while (err >> errVal.rdbuf()) {
    }

    c.wait();
    int exitCode = c.exit_code();
    if (exitCode != 0) {
      throw std::runtime_error(
          std::string("exit with error code ") + std::to_string(exitCode));
    }
    if (errVal.rdbuf()->in_avail() > 0) {
      throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, errVal.str());
    } else {
      return outVal.str();
    }
  } catch (SQLException &sqle) {
    throw sqle;
  } catch (std::exception &ex) {
    std::string err("Password lookup failure in 'secret-tool' for ");
    err.append(user).append(" using the attribute:value '").append(attribute)
      .append("':'").append(value).append("' : ");
    throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, err.append(ex.what()));
  }
#endif
}

bool Utils::convertUTF8ToUTF16(const char *utf8Chars,
    const int64_t utf8Len, std::function<void(int)> process) {
  const char *endChars = (utf8Len < 0) ? nullptr : (utf8Chars + utf8Len);
  bool nonASCII = false;
  int ch;
  while ((!endChars || utf8Chars < endChars)
      && (ch = (*utf8Chars++ & 0xFF)) != 0) {
    // get next byte unsigned
    const int k = (ch >> 5);
    // classify based on the high order 3 bits
    switch (k) {
      case 6: {
        // two byte encoding
        // 110yyyyy 10xxxxxx
        // use low order 6 bits
        const int y = ch & 0x1F;
        // use low order 6 bits of the next byte
        // It should have high order bits 10, which we don't check.
        const int x = *utf8Chars++ & 0x3F;
        // 00000yyy yyxxxxxx
        process(y << 6 | x);
        nonASCII = true;
        break;
      }
      case 7: {
        // three byte encoding
        // 1110zzzz 10yyyyyy 10xxxxxx
        //assert ( b & 0x10 )
        //     == 0 : "UTF8Decoder does not handle 32-bit characters";
        // use low order 4 bits
        const int z = ch & 0x0F;
        // use low order 6 bits of the next byte
        // It should have high order bits 10, which we don't check.
        const int y = *utf8Chars++ & 0x3F;
        // use low order 6 bits of the next byte
        // It should have high order bits 10, which we don't check.
        const int x = *utf8Chars++ & 0x3F;
        // zzzzyyyy yyxxxxxx
        process(z << 12 | y << 6 | x);
        nonASCII = true;
        break;
      }
      default:
        // one byte encoding
        // 0xxxxxxx
        // use just low order 7 bits
        // 00000000 0xxxxxxx
        process(ch & 0x7F);
        break;
    }
  }
  return nonASCII;
}


template<typename TNum>
class PrecisionPolicy final : public boost::spirit::karma::real_policies<TNum> {
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
    if (absn >= m_minFixed && absn <= static_cast<TNum>(1e9)) {
      return boost::spirit::karma::real_policies<TNum>::fmtflags::fixed;
    } else {
      return boost::spirit::karma::real_policies<TNum>::fmtflags::scientific;
    }
  }

  size_t precision(TNum n) const {
    SKIP_UNUSED_WARNING(n);
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
        d *= static_cast<TNum>(10.0);
        digits++;
        // if digits exceed actual precision, then reduce n
        if (digits > actualPrecision) {
          n1 = static_cast<TNum>((static_cast<double>(n1) + 5.0) / 10.0);
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
  result.append(buffer, static_cast<size_t>(pbuf - &buffer[0]));
}

void Utils::convertShortToString(const int16_t v, std::string& result) {
  char buffer[10];
  char* pbuf = buffer;
  boost::spirit::karma::generate(pbuf, boost::spirit::short_, v);
  result.append(buffer, static_cast<size_t>(pbuf - &buffer[0]));
}

void Utils::convertIntToString(const int32_t v, std::string& result) {
  char buffer[20];
  char* pbuf = buffer;
  boost::spirit::karma::generate(pbuf, boost::spirit::int_, v);
  result.append(buffer, static_cast<size_t>(pbuf - &buffer[0]));
}

void Utils::convertInt64ToString(const int64_t v, std::string& result) {
  char buffer[40];
  char* pbuf = buffer;
  boost::spirit::karma::generate(pbuf, boost::spirit::long_long, v);
  result.append(buffer, static_cast<size_t>(pbuf - &buffer[0]));
}

void Utils::convertUInt64ToString(const uint64_t v, std::string& result) {
  char buffer[40];
  char* pbuf = buffer;
  boost::spirit::karma::generate(pbuf, boost::spirit::ulong_long, v);
  result.append(buffer, static_cast<size_t>(pbuf - &buffer[0]));
}

void Utils::convertFloatToString(const float v, std::string& result,
    const size_t precision) {
  if (precision < 20) {
    char buffer[64];
    char* pbuf = buffer;
    boost::spirit::karma::generate(pbuf,
        precision == DEFAULT_REAL_PRECISION ? floatPrecisionDef :
            PrecisionFloatType(PrecisionPolicy<float>(precision)), v);
    result.append(buffer, static_cast<size_t>(pbuf - &buffer[0]));
  } else {
    // static buffer can overflow so better just use dynamically allocated array
    char* buffer = new char[precision * 2 + 24];
    std::unique_ptr<char[]> cleanBuf(buffer);
    char* pbuf = buffer;
    boost::spirit::karma::generate(pbuf,
        precision == DEFAULT_REAL_PRECISION ? floatPrecisionDef :
            PrecisionFloatType(PrecisionPolicy<float>(precision)), v);
    result.append(buffer, static_cast<size_t>(pbuf - &buffer[0]));
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
    result.append(buffer, static_cast<size_t>(pbuf - &buffer[0]));
  } else {
    // static buffer can overflow so better just use dynamically allocated array
    char* buffer = new char[precision * 2 + 24];
    std::unique_ptr<char[]> cleanBuf(buffer);
    char* pbuf = buffer;
    boost::spirit::karma::generate(pbuf,
        precision == DEFAULT_REAL_PRECISION ? doublePrecisionDef :
            PrecisionDoubleType(PrecisionPolicy<double>(precision)), v);
    result.append(buffer, static_cast<size_t>(pbuf - &buffer[0]));
  }
}

std::string Utils::toString(const std::exception& stde) {
  std::string str;
  demangleTypeName(typeid(stde).name(), str);
  const char* reason = stde.what();
  if (reason) {
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
  if (cause) {
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
  if (cause) {
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
  if (cause) {
    reason << ": " << cause;
  }
  throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_OUTSIDE_RANGE_FOR_DATATYPE_MSG,
      target, reason.str().c_str());
}

#ifdef __GNUC__
char* Utils::gnuDemangledName(const char* typeName) {
  int status;
  char *demangledName = abi::__cxa_demangle(typeName, nullptr, nullptr,
      &status);
  if (status == 0 && demangledName) {
    return demangledName;
  } else {
    return nullptr;
  }
}
#endif

void Utils::demangleTypeName(const char* typeName, std::string& str) {
#ifdef __GNUC__
  char* demangledName = gnuDemangledName(typeName);
  if (demangledName) {
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
  if (demangledName) {
    out << demangledName;
    ::free(demangledName);
    return;
  }
#endif
  out << typeName;
}

void Utils::handleExceptionsInDestructor(const char *operation,
    std::function<void()> body) noexcept {
  try {
    body();
  } catch (const SQLException &sqle) {
    // ignore transport and protocol exceptions due to other side failing
    const std::string &sqlState = sqle.getSQLState();
    if (sqlState == SQLState::SNAPPY_NODE_SHUTDOWN.getSQLState()
        || sqlState == SQLState::DATA_CONTAINER_CLOSED.getSQLState()
        || sqlState == SQLState::THRIFT_PROTOCOL_ERROR.getSQLState()) {
      return;
    }
    try {
      LogWriter::error() << "Exception in destructor of " << operation << ": "
          << stack(sqle);
    } catch (...) {
      std::cerr << "FAILURE in logging SQLException in destructor of "
          << operation << std::endl;
    }
  } catch (const std::exception &se) {
    // ignore transport and protocol exceptions due to other side failing
    if (!dynamic_cast<const transport::TTransportException*>(&se)
        && !dynamic_cast<const protocol::TProtocolException*>(&se)) {
      try {
        LogWriter::error() << "Exception in destructor of " << operation << ": "
            << stack(se);
      } catch (...) {
        std::cerr << "FAILURE in logging std::exception in destructor of "
            << operation << std::endl;
      }
    }
  } catch (...) {
    try {
      LogWriter::error() << "Unknown exception in destructor of " << operation;
    } catch (...) {
      std::cerr << "FAILURE in logging unknown exception in destructor of "
          << operation << std::endl;
    }
  }
}

std::ostream& operator <<(std::ostream& out, const wchar_t* wstr) {
  functor::WriteStream proc = { out };
  Utils::convertUTF16ToUTF8(wstr, -1, proc);
  return out;
}

std::ostream& operator <<(std::ostream& out,
    const thrift::ServerType::type& serverType) {
  return thrift::operator <<(out, serverType);
}

std::ostream& operator <<(std::ostream& out, const _SqleHex& hexstr) {
  Utils::toHexString(hexstr.m_str.data(), hexstr.m_str.size(), out);
  return out;
}
