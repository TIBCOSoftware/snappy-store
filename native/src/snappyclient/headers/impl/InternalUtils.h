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

#ifndef INTERNALUTILS_H_
#define INTERNALUTILS_H_

#include <functional>

#include <boost/filesystem.hpp>

extern "C" {
#include <stdlib.h>
}

#define CHRONO_NANO_CLOCK boost::chrono::high_resolution_clock

namespace io {
namespace snappydata {
namespace client {
namespace impl {

  class InternalUtils final {
  public:
    /** array to convert bytes to hex */
    static const char s_hexDigits[];

    static boost::filesystem::path getPath(const std::string& pathStr);

    /**
     * Invoke a given functor for each string in a comma separated
     * list of strings.
     */
    static void splitCSV(const std::string& csv,
        const std::function<void(const std::string&)>& proc);

    template<typename TPROC>
    static void toHexString(const char* bytes, const size_t bytesLen,
        TPROC& proc);

  private:
    InternalUtils() = delete; // no instances
    InternalUtils(const InternalUtils&) = delete; // no instances
    InternalUtils operator=(const InternalUtils&) = delete; // no instances
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
    std::unique_ptr<char[]> del(buffer);

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

#endif /* INTERNALUTILS_H_ */
