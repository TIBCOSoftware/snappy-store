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
 * Timestamp.h
 */

#ifndef TIMESTAMP_H_
#define TIMESTAMP_H_

#include "SQLException.h"
#include "DateTime.h"

namespace io {
namespace snappydata {
namespace client {
namespace types {

  class Timestamp: public DateTime {
  private:
    int32_t m_nanos;

    Timestamp(); // no default constructor

  public:
    static const int32_t NANOS_MAX = 1000000000;

    /**
     * Total nanoseconds since Epoch 1970-01-01 00:00:00 +0000 (UTC).
     */
    Timestamp(const int64_t totalNanos) :
        DateTime(totalNanos / NANOS_MAX),
        m_nanos(static_cast<int32_t>(totalNanos % NANOS_MAX)) {
    }

    /**
     * Seconds since Epoch 1970-01-01 00:00:00 +0000 (UTC)
     * and nanoseconds component of this timestamp.
     */
    Timestamp(const int64_t secsSinceEpoch, const int32_t nanos) :
        DateTime(secsSinceEpoch) {
      // setter invoked to check for valid nanoseconds
      setNanos(nanos);
    }

    /**
     * Local time as year, month, day, hour, minutes, seconds.
     *
     * @param year  number of years e.g. 1998
     * @param month number of months since January, in the range 1 to 12
     * @param day   day of the month, in the range 1 to 31
     * @param hour  number of hours past midnight, in the range 0 to 23
     * @param min   number of minutes after the hour,
     *              in the range 0 to 59
     * @param sec   number of seconds after the minute,
     *              normally in the range 0 to 59, but can be up to 60
     *              to allow for leap seconds
     * @param nanos the nanoseconds component of this timestamp
     */
    Timestamp(const uint16_t year, const uint16_t month,
        const uint16_t day, const uint16_t hour, const uint16_t min,
        const uint16_t sec, int32_t nanos, const bool utc = false) :
        DateTime(year, month, day, hour, min, sec, utc) {
      setNanos(nanos);
    }

    /** Get the nanoseconds component of this timestamp */
    inline int32_t getNanos() const noexcept {
      return m_nanos;
    }

    /** Get the total nanoseconds since Epoch . */
    inline int64_t getTotalNanos() const noexcept {
      return getEpochTime() * NANOS_MAX + m_nanos;
    }

    /** Set the new nanoseconds component of this timestamp */
    void setNanos(int32_t nanos);

    /**
     * Create a Timestamp instance parsing the timestamp
     * in string form hh:mm:ss hh:mm:ss[.NNNNNNNNN]
     */
    static Timestamp parseString(const std::string& str,
        const uint32_t columnIndex = 0, const bool utc = false);

    /**
     * Get the UTC date time representation
     * yyyy-mm-dd hh:mm:ss[.NNNNNNNNN]. The last portion is the
     * nanoseconds component that is printed only if present
     * and trailing zeros may be removed.
     */
    std::string& toString(std::string& str,
        const bool utc = false) const;
  };

  struct time_fmt_manip {
  private:
    const bool m_utc;

  public:

    time_fmt_manip(const bool utc) : m_utc(utc) {
    }

    void operator()(std::ostream& out) const;
  };

  inline time_fmt_manip time_fmt(const bool utc) {
    return time_fmt_manip(utc);
  }

  std::ostream& operator<<(std::ostream& out, const time_fmt_manip op);

} /* namespace types */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

std::ostream& operator <<(std::ostream& stream,
    io::snappydata::client::types::Timestamp ts);

#endif /* TIMESTAMP_H_ */
