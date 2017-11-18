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
 * DateTime.h
 */

#ifndef DATETIME_H_
#define DATETIME_H_

#include "SQLException.h"
extern "C" {
# include <time.h>
}

namespace io {
namespace snappydata {
namespace client {
namespace types {

  class DateTime {
  private:
    int64_t m_secsSinceEpoch;

    friend class io::snappydata::client::Row;
    friend class io::snappydata::client::Parameters;
    friend class io::snappydata::client::UpdatableRow;

    DateTime(); // no default constructor

  protected:
    /**
     * Get the date time representation
     * yyyy-mm-dd hh:mm:ss[.NNNNNNNNN]. The last portion is the
     * nano seconds component that is printed only if present
     * and trailing zeros may be removed.
     */
    static std::string& toString(const uint16_t year, const uint16_t month,
        const uint16_t day, const uint16_t hour, const uint16_t min,
        const uint16_t sec, const uint32_t nanos, std::string& str);

  public:
    /**
     * Initialize with seconds since Epoch 1970-01-01 00:00:00 (UTC).
     */
    DateTime(const int64_t secsSinceEpoch) :
        m_secsSinceEpoch(secsSinceEpoch) {
    }

    /**
     * Initialize with date provided as year, month, day
     *
     * @param year  number of years e.g. 1998
     * @param month number of months since January, in the range 1 to 12
     * @param day   day of the month, in the range 1 to 31
     * @param utc   if true then the date is expressed in UTC
     *              else in local timezone
     */
    DateTime(const uint16_t year, const uint16_t month,
        const uint16_t day, const bool utc = false) {
      init(year, month, day, 0, 0, 0, utc);
    }

    /**
     * Initialize with date-time provided as year, month, day,
     * hour, minutes, seconds.
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
     * @param utc   if true then the date-time is expressed in UTC
     *              else in local timezone
     */
    DateTime(const uint16_t year, const uint16_t month,
        const uint16_t day, const uint16_t hour, const uint16_t min,
        const uint16_t sec, const bool utc = false) {
      init(year, month, day, hour, min, sec, utc);
    }

    /**
     * Initialize this DateTime with local time as year, month, day,
     * hour, minutes, seconds.
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
     * @param utc   if true then the date-time is expressed in UTC
     *              else in local timezone
     */
    void init(const uint16_t year, const uint16_t month,
        const uint16_t day, const uint16_t hour, const uint16_t min,
        const uint16_t sec, const bool utc);

    /** Get the seconds since Epoch 1970-01-01 00:00:00 +0000 (UTC) */
    inline int64_t getEpochTime() const noexcept {
      return m_secsSinceEpoch;
    }

    /** Set the seconds since Epoch 1970-01-01 00:00:00 +0000 (UTC) */
    void setEpochTime(const int64_t epochTime);

    /**
     * Convert to time_t value if possible, else throw SQLException
     * (SQLState::SQLState::LANG_DATE_RANGE_EXCEPTION) for overflow.
     */
    time_t getTime() const;

    /**
     * Create a DateTime instance parsing the date
     * in string form yyyy-mm-dd
     *
     * @param str   the string to be parsed
     * @param columnIndex if this is associated with some column number
     *                    which is used in any exception message
     * @param utc   if true then the date is expressed in UTC
     *              else in local timezone
     */
    static DateTime parseDate(const std::string& str,
        const uint32_t columnIndex = 0, const bool utc = false);

    /**
     * Create a DateTime instance parsing the time
     * in string form hh:mm:ss
     *
     * @param str   the string to be parsed
     * @param columnIndex if this is associated with some column number
     *                    which is used in any exception message
     * @param utc   if true then the date is expressed in UTC
     *              else in local timezone
     */
    static DateTime parseTime(const std::string& str,
        const uint32_t columnIndex = 0, const bool utc = false);

    /**
     * Create a DateTime instance parsing the date and time
     * in string form yyyy-mm-dd hh:mm:ss
     *
     * @param str   the string to be parsed
     * @param columnIndex if this is associated with some column number
     *                    which is used in any exception message
     * @param utc   if true then the date is expressed in UTC
     *              else in local timezone
     */
    static DateTime parseDateTime(const std::string& str,
        const uint32_t columnIndex = 0, const bool utc = false);

    /**
     * Get the date time representation
     * yyyy-mm-dd hh:mm:ss[.NNNNNNNNN]. The last portion is the
     * nano seconds component that is printed only if present
     * and trailing zeros may be removed.
     */
    static std::ostream& toString(const uint16_t year,
        const uint16_t month, const uint16_t day, const uint16_t hour,
        const uint16_t min, const uint16_t sec, const uint32_t nanos,
        std::ostream& stream);

    /**
     * Get the UTC or local date representation as string in format
     * yyyy-mm-dd
     *
     * @param str   input string to which the date is appended
     * @param utc   if true then the date is expressed in UTC
     *              else in local timezone
     */
    void toDate(std::string& str, const bool utc = false) const;

    /**
     * Get the UTC or local time representation as string in format
     * hh:mm:ss
     *
     * @param str   input string to which the time is appended
     * @param utc   if true then the time is expressed in UTC
     *              else in local timezone
     */
    void toTime(std::string& str, const bool utc = false) const;

    /**
     * Get the UTC or local date-time representation as string in format
     * yyyy-mm-dd hh:mm:ss
     *
     * @param str   input string to which the date-time is appended
     * @param utc   if true then the date-time is expressed in UTC
     *              else in local timezone
     */
    void toDateTime(std::string& str, const bool utc = false) const;

    /**
     * Get the date+time fields as C "struct tm"
     *
     * @param utc   if true then the date-time is expressed in UTC
     *              else in local timezone
     */
    struct tm toDateTime(const bool utc = false) const;
  };

} /* namespace types */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* DATETIME_H_ */
