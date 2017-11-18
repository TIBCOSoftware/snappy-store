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
 * DateTime.cpp
 */

#include "Types.h"
#include "../impl/InternalUtils.h"

#include <boost/date_time/time_duration.hpp>
#include <boost/date_time/posix_time/posix_time.hpp>
#include <boost/date_time/local_time/local_time.hpp>

#include <boost/spirit/include/karma.hpp>

using namespace io::snappydata::client::impl;
using namespace io::snappydata::client::types;

void DateTime::init(const uint16_t year, const uint16_t month,
    const uint16_t day, const uint16_t hour, const uint16_t min,
    const uint16_t sec, const bool utc) {
  try {
    if (utc) {
      boost::posix_time::ptime dateTime(
          boost::gregorian::date(year, month, day),
          boost::posix_time::time_duration(hour, min, sec));
      boost::posix_time::time_duration sinceEpoch = dateTime
          - InternalUtils::s_epoch;
      setEpochTime(sinceEpoch.total_seconds());
    } else {
      boost::local_time::local_date_time localTime(
          boost::gregorian::date(year, month, day),
          boost::posix_time::time_duration(hour, min, sec),
          InternalUtils::s_localTimeZone,
          boost::local_time::local_date_time::EXCEPTION_ON_ERROR);
      boost::posix_time::time_duration sinceEpoch = localTime.utc_time()
          - InternalUtils::s_epoch;
      setEpochTime(sinceEpoch.total_seconds());
    }
  } catch (const std::exception&) {
    throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG2,
      year, month, day, hour, min, sec);
  }
}

void DateTime::setEpochTime(const int64_t epochTime) {
  m_secsSinceEpoch = epochTime;
}

time_t DateTime::getTime() const {
  const int64_t secs = m_secsSinceEpoch;
  if (sizeof(time_t) >= sizeof(secs)) {
    return secs;
  } else if (sizeof(time_t) >= sizeof(int32_t) &&
      secs >= INT32_MIN && secs <= INT32_MAX) {
    return (time_t)secs;
  } else {
    throw GET_SQLEXCEPTION2(
        SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG1, secs);
  }
}

DateTime DateTime::parseDate(const std::string& str,
    const uint32_t columnIndex, const bool utc) {
  try {
    boost::gregorian::date d = boost::gregorian::from_simple_string(str);
    boost::gregorian::date::ymd_type ymd = d.year_month_day();
    return DateTime(ymd.year, ymd.month, ymd.day, utc);
  } catch (const std::exception& e) {
    std::string err(str);
    err.append(": ").append(e.what());
    Utils::throwDataFormatError("DATE", columnIndex, err.c_str());
    // never reached
    return DateTime(0);
  }
}

DateTime DateTime::parseTime(const std::string& str,
    const uint32_t columnIndex, const bool utc) {
  try {
    // use today's date
    boost::gregorian::date today = boost::gregorian::day_clock::local_day();
    boost::posix_time::time_duration td =
        boost::posix_time::duration_from_string(str);
    boost::gregorian::date::ymd_type ymd = today.year_month_day();
    return DateTime(ymd.year, ymd.month, ymd.day, td.hours(), td.minutes(),
        td.seconds(), utc);
  } catch (const std::exception& e) {
    std::string err(str);
    err.append(": ").append(e.what());
    Utils::throwDataFormatError("TIME", columnIndex, err.c_str());
    // never reached
    return DateTime(0);
  }
}

// TODO: allow for optional timezone in the string apart from the UTC flag
DateTime DateTime::parseDateTime(const std::string& str,
    const uint32_t columnIndex, const bool utc) {
  try {
    // split date/time on a unique delimiter char such as ' '
    std::string dateStr, timeStr;
    if (!boost::date_time::split(str, ' ', dateStr, timeStr)) {
      Utils::throwDataFormatError("DATETIME", columnIndex, str.c_str());
    }

    // call parse_date with first string
    boost::gregorian::date d = boost::gregorian::from_simple_string(dateStr);
    boost::gregorian::date::ymd_type ymd = d.year_month_day();

    // call parse_time_duration with remaining string
    boost::posix_time::time_duration td =
        boost::posix_time::duration_from_string(timeStr);
    return DateTime(ymd.year, ymd.month, ymd.day, td.hours(), td.minutes(),
        td.seconds(), utc);
  } catch (const std::exception& e) {
    std::string err(str);
    err.append(": ").append(e.what());
    Utils::throwDataFormatError("DATETIME", columnIndex, err.c_str());
    // never reached
    return DateTime(0);
  }
}

inline void twoDigitsZeroPadded(char*& bufp, const uint16_t v) {
  if (v >= 10) {
    boost::spirit::karma::generate(bufp, boost::spirit::short_, v);
  } else {
    *bufp++ = '0';
    *bufp++ = v + '0';
  }
}

inline void nDigitsZeroPadded(char*& bufp, const uint32_t v, int n) {
  unsigned int vp = v;
  while ((vp /= 10) > 0) {
    n--;
  }
  while (--n > 0) {
    *bufp++ = '0';
  }
  boost::spirit::karma::generate(bufp, boost::spirit::int_, v);
}

inline char* toDateString(const uint16_t year, const uint16_t month,
    const uint16_t day, char* bufp) {
  boost::spirit::karma::generate(bufp, boost::spirit::short_, year);
  *bufp++ = '-';
  twoDigitsZeroPadded(bufp, month);
  *bufp++ = '-';
  twoDigitsZeroPadded(bufp, day);
  return bufp;
}

inline char* toTimeString(const uint16_t hour, const uint16_t min,
    const uint16_t sec, char* bufp) {
  twoDigitsZeroPadded(bufp, hour);
  *bufp++ = ':';
  twoDigitsZeroPadded(bufp, min);
  *bufp++ = ':';
  twoDigitsZeroPadded(bufp, sec);
  return bufp;
}

std::string& DateTime::toString(const uint16_t year, const uint16_t month,
    const uint16_t day, const uint16_t hour, const uint16_t min,
    const uint16_t sec, const uint32_t nanos, std::string& str) {
  char buf[64];
  char* bufp = buf;
  bufp = toDateString(year, month, day, bufp);
  *bufp++ = ' ';
  bufp = toTimeString(hour, min, sec, bufp);

  if (nanos != 0) {
    *bufp++ = '.';
    // is precision only in millis?
    if ((nanos % 1000000) == 0) {
      uint32_t millis = nanos / 1000000;
      nDigitsZeroPadded(bufp, millis, 3);
    } else if ((nanos % 1000) == 0) { // micros
      uint32_t micros = nanos / 1000;
      nDigitsZeroPadded(bufp, micros, 6);
    } else {
      nDigitsZeroPadded(bufp, nanos, 9);
    }
  }
  str.append(buf, (bufp - &buf[0]));
  return str;
}

std::ostream& DateTime::toString(const uint16_t year, const uint16_t month,
    const uint16_t day, const uint16_t hour, const uint16_t min,
    const uint16_t sec, const uint32_t nanos, std::ostream& stream) {
  char buf[64];
  char* bufp = buf;
  bufp = toDateString(year, month, day, bufp);
  *bufp++ = ' ';
  bufp = toTimeString(hour, min, sec, bufp);

  if (nanos != 0) {
    *bufp++ = '.';
    // is precision only in millis?
    if ((nanos % 1000000) == 0) {
      uint32_t millis = nanos / 1000000;
      nDigitsZeroPadded(bufp, millis, 3);
    } else if ((nanos % 1000) == 0) { // or micros
      uint32_t micros = nanos / 1000;
      nDigitsZeroPadded(bufp, micros, 6);
    } else {
      nDigitsZeroPadded(bufp, nanos, 9);
    }
  }
  *bufp = '\0';
  stream << buf;
  //str.append(buf, (bufp - &buf[0]));
  return stream;
}

void DateTime::toDate(std::string& str, const bool utc) const {
  try {
    char buf[32];
    char* bufp = buf;
    if (utc) {
      boost::posix_time::ptime dateTime =
          InternalUtils::convertEpochSecsToPosixTime(m_secsSinceEpoch);
      const auto ymd = dateTime.date().year_month_day();

      bufp = toDateString(uint16_t(ymd.year), ymd.month.as_number(),
          ymd.day.as_number(), bufp);
      str.append(buf, bufp - &buf[0]);
    } else {
      boost::local_time::local_date_time localTime(
          InternalUtils::convertEpochSecsToPosixTime(m_secsSinceEpoch),
          InternalUtils::s_localTimeZone);
      const auto ymd = localTime.date().year_month_day();

      bufp = toDateString(uint16_t(ymd.year), ymd.month.as_number(),
          ymd.day.as_number(), bufp);
      str.append(buf, bufp - &buf[0]);
    }
  } catch (const std::exception&) {
    throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG1,
        m_secsSinceEpoch);
  }
}

void DateTime::toTime(std::string& str, const bool utc) const {
  try {
    char buf[32];
    char* bufp = buf;
    if (utc) {
      boost::posix_time::ptime dateTime =
          InternalUtils::convertEpochSecsToPosixTime(m_secsSinceEpoch);
      const auto td = dateTime.time_of_day();

      bufp = toTimeString(td.hours(), td.minutes(), td.seconds(), bufp);
      str.append(buf, bufp - &buf[0]);
    } else {
      boost::local_time::local_date_time localTime(
          InternalUtils::convertEpochSecsToPosixTime(m_secsSinceEpoch),
          InternalUtils::s_localTimeZone);
      const auto td = localTime.time_of_day();

      bufp = toTimeString(td.hours(), td.minutes(), td.seconds(), bufp);
      str.append(buf, bufp - &buf[0]);
    }
  } catch (const std::exception&) {
    throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG1,
        m_secsSinceEpoch);
  }
}

void DateTime::toDateTime(std::string& str, const bool utc) const {
  try {
    if (utc) {
      boost::posix_time::ptime dateTime =
          InternalUtils::convertEpochSecsToPosixTime(m_secsSinceEpoch);
      auto ymd = dateTime.date().year_month_day();
      auto td = dateTime.time_of_day();

      toString(uint16_t(ymd.year), ymd.month.as_number(), ymd.day.as_number(),
          td.hours(), td.minutes(), td.seconds(), 0, str);
    } else {
      boost::local_time::local_date_time localTime(
          InternalUtils::convertEpochSecsToPosixTime(m_secsSinceEpoch),
          InternalUtils::s_localTimeZone);
      auto ymd = localTime.date().year_month_day();
      auto td = localTime.time_of_day();

      toString(uint16_t(ymd.year), ymd.month.as_number(), ymd.day.as_number(),
          td.hours(), td.minutes(), td.seconds(), 0, str);
    }
  } catch (const std::exception&) {
    throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG1,
        m_secsSinceEpoch);
  }
}

/** Get the date+time fields as C "struct tm" */
struct tm DateTime::toDateTime(const bool utc) const {
  try {
    if (utc) {
      boost::posix_time::ptime dateTime =
          InternalUtils::convertEpochSecsToPosixTime(m_secsSinceEpoch);
      return boost::posix_time::to_tm(dateTime);
    } else {
      boost::local_time::local_date_time localTime(
          InternalUtils::convertEpochSecsToPosixTime(m_secsSinceEpoch),
          InternalUtils::s_localTimeZone);
      return boost::local_time::to_tm(localTime);
    }
  } catch (const std::exception&) {
    throw GET_SQLEXCEPTION2(SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG1,
        m_secsSinceEpoch);
  }
}
