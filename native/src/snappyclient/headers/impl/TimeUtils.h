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

#ifndef IMPL_TIMEUTILS_H_
#define IMPL_TIMEUTILS_H_

#ifdef __GNUC__
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wshadow"
#pragma GCC diagnostic ignored "-Wsign-conversion"
#endif
#include <boost/date_time/posix_time/posix_time.hpp>
#include <boost/date_time/local_time/local_time.hpp>
#ifdef __GNUC__
#pragma GCC diagnostic pop
#endif

/**
 * Initialize time related statics like time zone and return the current time.
 */
std::string initializeSnappyDataTime();

namespace io {
namespace snappydata {
namespace client {
namespace impl {

class TimeUtils final {
public:
  /** posix_time since Epoch 1970-01-01 00:00:00 +0000 UTC */
  static boost::posix_time::ptime s_epoch;

  /** local timezeone */
  static boost::local_time::time_zone_ptr s_localTimeZone;
  static std::string s_localTimeZoneStr;

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
  TimeUtils() = delete; // no instances
  TimeUtils(const TimeUtils&) = delete; // no instances
  TimeUtils operator=(const TimeUtils&) = delete; // no instances

  static bool s_initialized;
  static bool staticInitialize();

  friend class ClientService;
};

} /* namespace impl */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* IMPL_TIMEUTILS_H_ */
