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
 * Timestamp.cpp
 */

#include "Types.h"
#include "../impl/InternalUtils.h"

#include <boost/date_time/time_duration.hpp>
#include <boost/date_time/posix_time/posix_time.hpp>

#include <boost/chrono/io/time_point_io.hpp>

#include <boost/spirit/include/qi.hpp>

using namespace io::snappydata::client;
using namespace io::snappydata::client::impl;
using namespace io::snappydata::client::types;

namespace _snappy_impl {
  class nano_time_duration : public boost::date_time::time_duration<
      nano_time_duration, boost::date_time::nano_res> {
  public:
    typedef boost::date_time::nano_res::hour_type hour_type;
    typedef boost::date_time::nano_res::min_type min_type;
    typedef boost::date_time::nano_res::sec_type sec_type;
    typedef boost::date_time::nano_res::fractional_seconds_type
        fractional_seconds_type;
    typedef boost::date_time::nano_res::impl_type impl_type;

    nano_time_duration(hour_type hour, min_type min, sec_type sec,
        fractional_seconds_type fs = 0) :
        boost::date_time::time_duration<nano_time_duration,
            boost::date_time::nano_res>(hour, min, sec, fs) {
    }
    nano_time_duration() : boost::date_time::time_duration<nano_time_duration,
        boost::date_time::nano_res>(0, 0, 0) {
    }
    // Construct from special_values
    nano_time_duration(boost::date_time::special_values sv) :
        boost::date_time::time_duration<nano_time_duration,
            boost::date_time::nano_res>(sv) {
    }
    // Give duration access to ticks constructor -- hide from users
    friend class boost::date_time::time_duration<nano_time_duration,
        boost::date_time::nano_res>;

  protected:
    explicit nano_time_duration(impl_type tick_count) :
        boost::date_time::time_duration<nano_time_duration,
            boost::date_time::nano_res>(tick_count) {
    }
  };
}

void Timestamp::setNanos(int32_t nanos) {
  if (nanos >= 0 && nanos < NANOS_MAX) {
    m_nanos = nanos;
  } else {
    throw GET_SQLEXCEPTION2(
        SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG3, nanos,
        NANOS_MAX - 1);
  }
}

Timestamp Timestamp::parseString(const std::string& str,
    const uint32_t columnIndex, const bool utc) {
  // TODO: PERF: can be made much more efficient by using tight parser
  // of boost::spirit::qi; sscanf is faster but we are sticking to this for now
  try {
    // split date/time on a unique delimiter char such as ' '
    std::string dt, timeStr;
    if (!boost::date_time::split(str, ' ', dt, timeStr)) {
      Utils::throwDataFormatError("TIMESTAMP", columnIndex, str.c_str());
    }

    // call parse_date with first string
    boost::gregorian::date d = boost::gregorian::from_simple_string(dt);
    boost::gregorian::date::ymd_type ymd = d.year_month_day();

    // call parse_time_duration with remaining string
    _snappy_impl::nano_time_duration td =
        boost::date_time::parse_delimited_time_duration<
            _snappy_impl::nano_time_duration>(timeStr);
	// nanoseconds will lie within int32 limits
	return Timestamp(ymd.year, ymd.month, ymd.day, td.hours(), td.minutes(),
        td.seconds(), static_cast<int32_t>(td.fractional_seconds()), utc);
  } catch (const std::exception& e) {
    std::string err(str);
    err.append(": ").append(e.what());
    Utils::throwDataFormatError("TIMESTAMP", columnIndex, err.c_str());
    // never reached
    return Timestamp(0L);
  }
}

/**
 * Get the UTC or local date-time representation as string
 * yyyy-mm-dd hh:mm:ss[.NNNNNNNNN].
 */
std::string& Timestamp::toString(std::string& str, const bool utc) const {
  if (utc) {
    const int64_t secsSinceEpoch = getEpochTime();
    try {
      boost::posix_time::ptime dateTime =
          InternalUtils::convertEpochSecsToPosixTime(secsSinceEpoch);
      boost::gregorian::date::ymd_type ymd = dateTime.date().year_month_day();
      boost::posix_time::time_duration td = dateTime.time_of_day();

      return DateTime::toString(uint16_t(ymd.year), ymd.month.as_number(),
          ymd.day.as_number(), td.hours(), td.minutes(), td.seconds(), m_nanos,
          str);
    } catch (const std::exception&) {
      throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG1,
          secsSinceEpoch);
    }
  } else {
    const time_t tv = getTime();
    struct tm t;
    ::memset(&t, 0, sizeof(t));
#ifdef _WINDOWS
    if (::localtime_s(&t, &tv) == 0) {
#else
    if (::localtime_r(&tv, &t) != NULL) {
#endif
      return DateTime::toString(uint16_t(t.tm_year + 1900), t.tm_mon + 1,
          t.tm_mday, t.tm_hour, t.tm_min, t.tm_sec, m_nanos, str);
    } else {
      throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG1,
          tv);
    }
  }
}

/** Change the timezone ios state. */
void time_fmt_manip::operator()(std::ostream& out) const {
  boost::chrono::set_timezone(out,
      m_utc ? boost::chrono::timezone::utc : boost::chrono::timezone::local);
}

std::ostream& types::operator<<(std::ostream& out, const time_fmt_manip op) {
  if (out.good()) {
    op(out);
  }
  return out;
}

std::ostream& operator <<(std::ostream& stream, Timestamp ts) {
  if (boost::chrono::get_timezone(stream) == boost::chrono::timezone::utc) {
    const int64_t secsSinceEpoch = ts.getEpochTime();
    try {
      boost::posix_time::ptime dateTime =
          InternalUtils::convertEpochSecsToPosixTime(secsSinceEpoch);
      boost::gregorian::date::ymd_type ymd = dateTime.date().year_month_day();
      boost::posix_time::time_duration td = dateTime.time_of_day();

      return DateTime::toString(uint16_t(ymd.year), ymd.month.as_number(),
          ymd.day.as_number(), td.hours(), td.minutes(), td.seconds(),
          ts.getNanos(), stream);
    } catch (const std::exception&) {
      throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG1,
          secsSinceEpoch);
    }
  } else {
    const time_t tv = ts.getTime();
    struct tm t;
    ::memset(&t, 0, sizeof(t));
#ifdef _WINDOWS
    if (::localtime_s(&t, &tv) == 0) {
#else
    if (::localtime_r(&tv, &t) != NULL) {
#endif
      DateTime::toString(uint16_t(t.tm_year + 1900), t.tm_mon + 1, t.tm_mday,
          t.tm_hour, t.tm_min, t.tm_sec, ts.getNanos(), stream);
      // also append POSIX timezone string
      return stream << ' ' << InternalUtils::s_localTimeZoneStr;
    } else {
      throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG1,
          tv);
    }
  }
}
