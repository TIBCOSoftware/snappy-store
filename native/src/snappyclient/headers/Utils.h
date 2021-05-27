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
 * Utils.h
 */

#ifndef UTILS_H_
#define UTILS_H_

#include "ClientBase.h"

extern "C" {
#ifdef _LINUX
#include <sys/prctl.h>
#endif
}

#include <exception>
#include <functional>

namespace io {
namespace snappydata {

namespace functor {
  struct WriteStream final {
    std::ostream& m_out;

    inline void operator()(char c) {
      m_out.put(c);
    }
    inline void operator()(const char* buf, const size_t bufLen) {
      m_out.write(buf, static_cast<std::streamsize>(bufLen));
    }
  };

  struct WriteString final {
    std::string& m_str;

    inline void operator()(char c) {
      m_str.push_back(c);
    }
    inline void operator()(const char* buf, const size_t bufLen) {
      m_str.append(buf, bufLen);
    }
  };
}

namespace client {

  union float2int_ final {
    float m_f;
    int32_t m_i32;
  };

  /** For I/O manipulator to get hex string. */
  struct _SqleHex final {
    const std::string& m_str;
  };

  /** Parameterized I/O manipulator to get hex string. */
  inline _SqleHex hexstr(const std::string& str) {
    _SqleHex h = { str };
    return h;
  }

  class Utils final {
  public:
    static const char* getSQLTypeName(const thrift::ColumnValue& cv);

    inline static float int32ToFloat(int32_t i) noexcept {
      float2int_ u;
      u.m_i32 = i;
      return u.m_f;
    }

    inline static int32_t float2Int32(float f) noexcept {
      float2int_ u;
      u.m_f = f;
      return u.m_i32;
    }

    template <typename T>
    inline static std::vector<T> singleVector(const T& elem) {
      return std::vector<T>(1, elem);
    }

    inline static bool supportsThreadNames() {
#ifdef _LINUX
      return true;
#else
      return false;
#endif
    }

    inline static bool getCurrentThreadName(const char* header,
        std::string& result) {
#ifdef _LINUX
      char threadName[32];
      if (::prctl(PR_GET_NAME, threadName) == 0) {
        if (header) {
          result.append(header);
        }
        result.append(threadName);
        return true;
      }
      // TODO: there is a way to do it on Windows too; also check for
      // other platforms where pthread_get_name_np is available (config.h)
      // Also change supportsThreadNames() if support for others is added
#endif
      SKIP_UNUSED_WARNING(header);
      SKIP_UNUSED_WARNING(result);
      return false;
    }

    inline static bool getCurrentThreadName(const char* header,
        std::ostream& out) {
#ifdef _LINUX
      char threadName[32];
      if (::prctl(PR_GET_NAME, threadName) == 0) {
        if (header) {
          out << header;
        }
        out << threadName;
        return true;
      }
      // TODO: there is a way to do it on Windows too; also check for
      // other platforms where pthread_get_name_np is available (config.h)
      // Also change supportsThreadNames() if support for others is added
#endif
      SKIP_UNUSED_WARNING(header);
      SKIP_UNUSED_WARNING(out);
      return false;
    }

    inline static std::ostream& threadName(std::ostream& out) {
      getCurrentThreadName(nullptr, out);
      return out;
    }

    static void getHostAddress(const std::string& hostNameAndAddress,
        const int port, thrift::HostAddress& result);

    /**
     * Split a given host[port] or host:port string into host and port.
     */
    static void getHostPort(const std::string& hostPort,
        std::string& resultHost, int& resultPort);

    static bool isServerTypeDefault(
        const thrift::ServerType::type serverType) noexcept {
      return (serverType == thrift::ServerType::THRIFT_SNAPPY_CP)
          | (serverType == thrift::ServerType::THRIFT_LOCATOR_CP);
    }

    static void toHexString(const char* bytes, const size_t bytesLen,
        std::ostream& out);

    static void toHexString(const char* bytes, const size_t bytesLen,
        std::string& result);

    static std::string readPasswordFromManager(const std::string& user,
        const std::string& passwordKey);

    static bool convertUTF8ToUTF16(const char* utf8Chars, const int64_t utf8Len,
        std::function<void(int)> process);

    template<typename TWCHAR>
    static void convertUTF16ToUTF8(const TWCHAR* utf16Chars,
        const int64_t utf16Len, std::function<void(char)> process);

    static void convertByteToString(const int8_t v, std::string& result);
    static void convertShortToString(const int16_t v, std::string& result);
    static void convertIntToString(const int32_t v, std::string& result);
    static void convertInt64ToString(const int64_t v, std::string& result);
    static void convertUInt64ToString(const uint64_t v, std::string& result);
    static void convertFloatToString(const float v, std::string& result,
        const size_t precision = DEFAULT_REAL_PRECISION);
    static void convertDoubleToString(const double v, std::string& result,
        const size_t precision = DEFAULT_REAL_PRECISION);

    static std::string toString(const std::exception& stde);

    BOOST_NORETURN static void throwDataFormatError(const char* target,
        const uint32_t columnIndex, const char* cause);

    BOOST_NORETURN static void throwDataFormatError(const char* target,
        const thrift::ColumnValue& srcValue, const uint32_t columnIndex,
        const char* cause);

    BOOST_NORETURN static void throwDataFormatError(const char* target,
        const uint32_t columnIndex, const std::exception& cause);

    BOOST_NORETURN static void throwDataOutsideRangeError(const char* target,
        const uint32_t columnIndex, const char* cause);

#ifdef __GNUC__
    static char* gnuDemangledName(const char* typeIdName);
#endif
    static void demangleTypeName(const char* typeIdName,
        std::string& str);

    static void demangleTypeName(const char* typeIdName,
        std::ostream& out);

    static void handleExceptionsInDestructor(const char *operation,
        std::function<void()> body) noexcept;

  private:
    Utils() = delete; // no instances
    Utils(const Utils&) = delete;
    Utils operator=(const Utils&) = delete;
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

// TODO: SW: check all the UTF8 conversions as to how they conform with
// C standard way for surrogate chars like in Java

template<typename TWCHAR>
void io::snappydata::client::Utils::convertUTF16ToUTF8(const TWCHAR* utf16Chars,
    const int64_t utf16Len, std::function<void(char)> process) {
  const TWCHAR* endChars = utf16Len < 0 ? nullptr : (utf16Chars + utf16Len);
  TWCHAR wch;
  while ((!endChars || utf16Chars < endChars) && (wch = *utf16Chars++) != 0) {
    if (wch > 0 && wch <= 0x7F) {
      process((char)wch);
    } else if (wch <= 0x7FF) {
      process(static_cast<char>(0xC0 + ((wch >> 6) & 0x1F)));
      process(static_cast<char>(0x80 + (wch & 0x3F)));
    } else {
      process(static_cast<char>(0xE0 + ((wch >> 12) & 0xF)));
      process(static_cast<char>(0x80 + ((wch >> 6) & 0x3F)));
      process(static_cast<char>(0x80 + (wch & 0x3F)));
    }
  }
}

std::ostream& operator <<(std::ostream& out, const wchar_t* wstr);

std::ostream& operator <<(std::ostream& out,
  const io::snappydata::thrift::ServerType::type& serverType);

std::ostream& operator<<(std::ostream& out,
  const io::snappydata::client::_SqleHex& hexstr);

#endif /* UTILS_H_ */
