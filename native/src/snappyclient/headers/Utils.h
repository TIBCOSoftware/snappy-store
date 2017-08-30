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
#include <typeinfo>
#include <boost/config.hpp>

namespace io {
namespace snappydata {

namespace functor {
  struct WriteStream {
    std::ostream& m_out;

    inline void operator()(char c) {
      m_out.put(c);
    }
    inline void operator()(const char* buf, const size_t bufLen) {
      m_out.write(buf, bufLen);
    }
  };

  struct WriteString {
    std::string& m_str;

    inline void operator()(char c) {
      m_str.append(1, c);
    }
    inline void operator()(const char* buf, const size_t bufLen) {
      m_str.append(buf, bufLen);
    }
  };

  struct WriteWString {
    std::wstring& m_wstr;

    inline void operator()(int c) {
      m_wstr.append(1, (wchar_t)c);
    }
  };
}

/** used to destroy arrays on end of a block */
template<typename ARR_TYPE>
class DestroyArray {
private:
  const ARR_TYPE* m_arr;

public:
  DestroyArray(const ARR_TYPE* arr) : m_arr(arr) {
  }
  ~DestroyArray() {
    delete[] m_arr;
  }
};

namespace client {

  union float2int_ {
    float m_f;
    int32_t m_i32;
  };

  /** For I/O manipulator to get hex string. */
  struct _SqleHex {
    const std::string& m_str;
  };

  /** Parameterized I/O manipulator to get hex string. */
  inline _SqleHex hexstr(const std::string& str) {
    _SqleHex h = { str };
    return h;
  }

  class Utils {
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
    static std::vector<T> singleVector(const T& elem) {
      std::vector<T> vec(1);
      vec.push_back(elem);
      return vec;
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
        if (header != NULL) {
          result.append(header);
        }
        result.append(threadName);
        return true;
      }
      // TODO: there is a way to do it on Windows too; also check for
      // other platforms where pthread_get_name_np is available (config.h)
      // Also change supportsThreadNames() if support for others is added
#endif
      return false;
    }

    inline static bool getCurrentThreadName(const char* header,
        std::ostream& out) {
#ifdef _LINUX
      char threadName[32];
      if (::prctl(PR_GET_NAME, threadName) == 0) {
        if (header != NULL) {
          out << header;
        }
        out << threadName;
        return true;
      }
      // TODO: there is a way to do it on Windows too; also check for
      // other platforms where pthread_get_name_np is available (config.h)
      // Also change supportsThreadNames() if support for others is added
#endif
      return false;
    }

    inline static std::ostream& threadName(std::ostream& out) {
      getCurrentThreadName(NULL, out);
      return out;
    }

    static void getHostAddress(const std::string& hostNameAndAddress,
        const int port, thrift::HostAddress& result);

    /**
     * Split a given host[port] or host:port string into host and port.
     */
    static void getHostPort(const std::string& hostPort,
        std::string& resultHost, int& resultPort);

    static const char* getServerTypeString(
        thrift::ServerType::type serverType) noexcept;

    static bool isServerTypeDefault(
        const thrift::ServerType::type serverType) noexcept {
      return (serverType == thrift::ServerType::THRIFT_SNAPPY_CP)
          | (serverType == thrift::ServerType::THRIFT_LOCATOR_CP);
    }

    static void toHexString(const char* bytes, const size_t bytesLen,
        std::ostream& out);

    static void toHexString(const char* bytes, const size_t bytesLen,
        std::string& result);

    template<typename TPROC>
    static bool convertUTF8ToUTF16(const char* utf8Chars,
        const int utf8Len, TPROC& process);

    static bool convertUTF8ToUTF16(const char* utf8Chars,
        const int utf8Len, std::wstring& result);

    template<typename TWCHAR, typename TPROC>
    static void convertUTF16ToUTF8(const TWCHAR* utf16Chars,
        const int utf16Len, TPROC& process);

    static void convertUTF16ToUTF8(const wchar_t* utf16Chars,
        const int utf16Len, std::string& result);

    static void convertUTF16ToUTF8(const wchar_t* utf16Chars,
        const int utf16Len, std::ostream& out);

    static void convertByteToString(const int8_t v, std::string& result);
    static void convertShortToString(const int16_t v, std::string& result);
    static void convertIntToString(const int32_t v, std::string& result);
    static void convertInt64ToString(const int64_t v, std::string& result);
    static void convertUInt64ToString(const uint64_t v, std::string& result);
    static void convertFloatToString(const float v, std::string& result,
        const size_t precision = DEFAULT_REAL_PRECISION);
    static void convertDoubleToString(const double v, std::string& result,
        const size_t precision = DEFAULT_REAL_PRECISION);

    static std::ostream& toStream(std::ostream& out,
        const thrift::HostAddress& hostAddr);

    static std::ostream& toStream(std::ostream& out,
        const std::exception& stde);

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

    static void handleExceptionInDestructor(const char* operation,
        const SQLException& sqle);
    static void handleExceptionInDestructor(const char* operation,
        const std::exception& stde);
    static void handleExceptionInDestructor(const char* operation);

  private:
    Utils(); // no instances
    ~Utils(); // no instances
    Utils(const Utils&);
    Utils operator=(const Utils&);
  };

  /**
   * @brief Thrown for an incorrect typecast.
   */
  class CastException: public std::bad_cast {
  private:
    std::string m_msg;

  public:
    CastException(const std::string& msg) : m_msg(msg) {
    }

    virtual ~CastException() {
    }

    virtual const char* what() const noexcept {
      return m_msg.c_str();
    }
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

template<typename TPROC>
bool io::snappydata::client::Utils::convertUTF8ToUTF16(
  const char* utf8Chars, const int utf8Len, TPROC& process) {
  const char* endChars = (utf8Len < 0) ? NULL : (utf8Chars + utf8Len);
  bool nonASCII = false;
  int ch;
  while ((endChars == NULL || utf8Chars < endChars)
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

template<typename TWCHAR, typename TPROC>
void io::snappydata::client::Utils::convertUTF16ToUTF8(
  const TWCHAR* utf16Chars, const int utf16Len, TPROC& process) {
  TWCHAR wch;
  if (utf16Len < 0) {
    while ((wch = *utf16Chars++) != 0) {
      if (wch > 0 && wch <= 0x7F) {
        process((char)wch);
      } else if (wch <= 0x7FF) {
        process((char)(0xC0 + ((wch >> 6) & 0x1F)));
        process((char)(0x80 + (wch & 0x3F)));
      } else {
        process((char)(0xE0 + ((wch >> 12) & 0xF)));
        process((char)(0x80 + ((wch >> 6) & 0x3F)));
        process((char)(0x80 + (wch & 0x3F)));
      }
    }
  } else {
    const TWCHAR* endChars = (utf16Chars + utf16Len);
    while (utf16Chars < endChars && (wch = *utf16Chars++) != 0) {
      if (wch > 0 && wch <= 0x7F) {
        process((char)wch);
      } else if (wch <= 0x7FF) {
        process((char)(0xC0 + ((wch >> 6) & 0x1F)));
        process((char)(0x80 + (wch & 0x3F)));
      } else {
        process((char)(0xE0 + ((wch >> 12) & 0xF)));
        process((char)(0x80 + ((wch >> 6) & 0x3F)));
        process((char)(0x80 + (wch & 0x3F)));
      }
    }
  }
}

std::ostream& operator <<(std::ostream& out, const wchar_t* wstr);

std::ostream& operator <<(std::ostream& out,
  const io::snappydata::thrift::ServerType::type& serverType);

std::ostream& operator<<(std::ostream& out,
  const io::snappydata::client::_SqleHex& hexstr);

#endif /* UTILS_H_ */
