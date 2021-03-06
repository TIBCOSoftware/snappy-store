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

#include "impl/pch.h"

using namespace io::snappydata::client::impl;

const char InternalUtils::s_hexDigits[] = "0123456789abcdef";

boost::filesystem::path InternalUtils::getPath(const std::string& pathStr) {
  // Locale brain-dead Windows. It does not support UTF8 encodings rather
  // provides an "open" which accepts UTF16 filename. So we need to
  // convert to wchar_t here on Windows (at least if filename is not ASCII).
#ifdef _WINDOWS
  std::wstring wlogFile;
  bool result = Utils::convertUTF8ToUTF16(pathStr.c_str(),
      static_cast<int64_t>(pathStr.size()), [&](int c) {
    wlogFile.push_back((wchar_t)c);
  });
  if (result) {
    return boost::filesystem::path(wlogFile.begin(), wlogFile.end());
  }
#endif
  return boost::filesystem::path(pathStr);
}

void InternalUtils::splitCSV(const std::string& csv,
    const std::function<void(const std::string&)>& proc) {
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
    while (current > start && std::isspace(csv[current - 1], currLocale)) {
      current--;
    }
    if (current > start) {
      proc(csv.substr(start, current - start));
    }
  }
}
