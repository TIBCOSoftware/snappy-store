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

#ifndef INTERNALUTILS_H_
#define INTERNALUTILS_H_

#include "Types.h"
#include "ClientService.h"

#include <boost/chrono/system_clocks.hpp>
#include <boost/chrono/thread_clock.hpp>
#include <boost/date_time/posix_time/posix_time.hpp>
#include <boost/date_time/local_time/local_time.hpp>
#include <boost/filesystem.hpp>

extern "C" {
#include <stdlib.h>
}

namespace io {
namespace snappydata {
namespace client {
namespace impl {

  typedef boost::chrono::high_resolution_clock NanoClock;
  typedef boost::chrono::high_resolution_clock::time_point NanoTime;
  typedef boost::chrono::high_resolution_clock::duration NanoDuration;
#ifdef BOOST_CHRONO_HAS_THREAD_CLOCK
  typedef boost::chrono::thread_clock NanoClockThread;
  typedef boost::chrono::thread_clock::time_point NanoTimeThread;
  typedef boost::chrono::thread_clock::duration NanoDurationThread;
#else
  typedef boost::chrono::high_resolution_clock NanoClockThread;
  typedef boost::chrono::high_resolution_clock::time_point NanoTimeThread;
  typedef boost::chrono::high_resolution_clock::duration NanoDurationThread;
#endif

  class InternalUtils {
  public:
    /** array to convert bytes to hex */
    static const char s_hexDigits[];

    /** posix_time since Epoch 1970-01-01 00:00:00 +0000 UTC */
    static boost::posix_time::ptime s_epoch;

    /** local timezeone */
    static boost::local_time::time_zone_ptr s_localTimeZone;
    static std::string s_localTimeZoneStr;

    inline static NanoTime nanoTime() {
      return NanoClock::now();
    }

    inline static NanoTimeThread nanoTimeThread() {
      return NanoClockThread::now();
    }

    static boost::filesystem::path getPath(const std::string& pathStr);

    /**
     * Generic functor to add the split strings as per splitCSV
     * call into a collection.
     */
    template<typename TCOLL>
    struct CollectStrings {
      TCOLL& m_strings;

      CollectStrings(TCOLL& strings) : m_strings(strings) {
      }

      void operator()(const std::string& str) {
        m_strings.insert(m_strings.end(), str);
      }
    };

    /**
     * Invoke a given functor for each string in a comma separated
     * list of strings.
     */
    template<typename TPROC>
    static void splitCSV(const std::string& csv, TPROC& proc);

    template<typename TPROC>
    static void toHexString(const char* bytes, const size_t bytesLen,
        TPROC& proc);

    static boost::posix_time::ptime convertEpochSecsToPosixTime(
        const int64_t secsSinceEpoch) {
      // using milliseconds instead of seconds as it is 64-bit
      return (s_epoch + boost::posix_time::milliseconds(secsSinceEpoch * 1000));
    }

    static int64_t convertPosixTimeToEpochSecs(
        const boost::posix_time::ptime dateTime) {
      boost::posix_time::time_duration sinceEpoch = dateTime - s_epoch;
      return (sinceEpoch.ticks() / sinceEpoch.ticks_per_second());
    }

  private:
    InternalUtils() = delete; // no instances
    ~InternalUtils() = delete; // no instances
    InternalUtils(const InternalUtils&) = delete; // no instances
    InternalUtils operator=(const InternalUtils&) = delete; // no instances

    static bool s_initialized;
    static bool staticInitialize();
    friend class ClientService;
  };

  class FreePointer {
  private:
    void* m_p;

    FreePointer(const FreePointer&) = delete; // disable copy constructor
    FreePointer& operator=(const FreePointer&) = delete; // disable assignment

  public:
    FreePointer(void* p) noexcept : m_p(p) {
    }

    void reset(void* p) noexcept {
      m_p = p;
    }

    ~FreePointer() {
      if (m_p != 0) {
        ::free(m_p);
      }
    }
  };

} /* namespace impl */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

template<typename TPROC>
void io::snappydata::client::impl::InternalUtils::toHexString(
    const char* bytes, const size_t bytesLen, TPROC& proc) {
  // for small sizes, write to out directly but for others create
  // temporary buffer to avoid repeated stream range/capacity checks
  if (bytesLen > 32) {
    char* buffer = new char[bytesLen << 1];
    io::snappydata::DestroyArray<char> del(buffer);

    size_t bufIndex = 0;
    for (size_t index = 0; index < bytesLen; index++) {
      buffer[bufIndex++] = (s_hexDigits[(bytes[index] >> 4) & 0x0f]);
      buffer[bufIndex++] = (s_hexDigits[bytes[index] & 0x0f]);
    }
    proc(buffer, bytesLen << 1);
  } else {
    for (size_t index = 0; index < bytesLen; index++) {
      proc(s_hexDigits[(bytes[index] >> 4) & 0x0f]);
      proc(s_hexDigits[bytes[index] & 0x0f]);
    }
  }
}

template<typename TPROC>
void io::snappydata::client::impl::InternalUtils::splitCSV(
    const std::string& csv, TPROC& proc) {
  const size_t csvLen = csv.size();
  if (csvLen > 0) {
    uint32_t start = 0;
    std::locale currLocale;
    // skip leading spaces, if any
    while (start < csvLen && std::isspace(csv[start], currLocale)) {
      start++;
    }
    uint32_t current = start;
    while (current < csvLen) {
      if (csv[current] != ',') {
        current++;
      } else {
        proc(csv.substr(start, current - start));
        start = ++current;
      }
    }
    // skip trailing spaces, if any
    while (current > start && std::isspace(csv[current], currLocale)) {
      current--;
    }
    if (current > start) {
      proc(csv.substr(start, current - start));
    }
  }
}

#endif /* INTERNALUTILS_H_ */
