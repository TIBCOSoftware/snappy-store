/*
 * Copyright (c) 2017-2021 TIBCO Software Inc. All rights reserved.
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

#include "impl/TimeUtils.h"

using namespace io::snappydata::client::impl;

boost::posix_time::ptime TimeUtils::s_epoch;
boost::local_time::time_zone_ptr TimeUtils::s_localTimeZone;
std::string TimeUtils::s_localTimeZoneStr;
bool TimeUtils::s_initialized = TimeUtils::staticInitialize();

std::string initializeSnappyDataTime() {
  // initialization of statics already done by call to staticInitialize above
  boost::posix_time::ptime currentTime =
      boost::posix_time::microsec_clock::universal_time();
  return boost::posix_time::to_simple_string(currentTime);
}

bool TimeUtils::staticInitialize() {
  s_epoch = boost::posix_time::ptime(boost::gregorian::date(1970, 1, 1));

  // get the local timezone
  time_t ts = 0;
  struct tm t;
  char buf[16], bufA[16];
  std::string bufStr;
#ifdef _WINDOWS
  ::localtime_s(&t, &ts);
#else
  ::localtime_r(&ts, &t);
#endif

  bool addColon = false;
  size_t buflen = ::strftime(buf, sizeof(buf), "%z", &t);
  if (buflen == 5 && (buf[3] != ':' && buf[3] != '-')) {
    addColon = true;
  }
  buflen = ::strftime(bufA, sizeof(bufA), "%Z", &t);
  bufStr.assign(bufA, buflen);
  bufStr.append(buf);
  if (addColon) {
    bufStr.insert(bufStr.length() - 2, ":");
  }

  s_localTimeZone = boost::local_time::time_zone_ptr(
      new boost::local_time::posix_time_zone(bufStr));
  if (s_localTimeZone->std_zone_abbrev().length() > 0) {
    s_localTimeZoneStr = s_localTimeZone->std_zone_abbrev();
  } else if (s_localTimeZone->std_zone_name().length() > 0) {
    s_localTimeZoneStr = s_localTimeZone->std_zone_name();
  } else {
    s_localTimeZoneStr = s_localTimeZone->to_posix_string();
  }
  return true;
}
