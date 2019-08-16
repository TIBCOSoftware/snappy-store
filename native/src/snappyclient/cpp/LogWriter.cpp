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
 * LogWriter.cpp
 */

#include "LogWriter.h"

#include <fstream>
#include <boost/make_shared.hpp>
#include <boost/chrono/system_clocks.hpp>
#include <boost/chrono/io/time_point_io.hpp>
#include <boost/filesystem/fstream.hpp>
#include <boost/thread/thread.hpp>

#include "SQLException.h"
#include "Utils.h"
#include "impl/InternalUtils.h"
#include "impl/InternalLogger.h"
#include "types/Timestamp.h"

using namespace io::snappydata;
using namespace io::snappydata::client;

std::map<const std::string, LogLevel::type> LogLevel::s_allLogLevels;

void LogLevel::staticInitialize() {
  s_allLogLevels["all"] = LogLevel::all;
  s_allLogLevels["trace"] = LogLevel::trace;
  s_allLogLevels["debug"] = LogLevel::debug;
  s_allLogLevels["info"] = LogLevel::info;
  s_allLogLevels["warn"] = LogLevel::warn;
  s_allLogLevels["error"] = LogLevel::error;
  s_allLogLevels["fatal"] = LogLevel::fatal;
  s_allLogLevels["none"] = LogLevel::none;
}

const char* LogLevel::toString(const LogLevel::type logLevel) {
  switch (logLevel) {
    case all:
      return "all";
    case trace:
      return "trace";
    case debug:
      return "debug";
    case info:
      return "info";
    case warn:
      return "warn";
    case error:
      return "error";
    case fatal:
      return "fatal";
    case none:
    default:
      return "none";
  }
}

const LogLevel::type LogLevel::fromString(const std::string& levelString,
    const LogWriter& logger) {
  const std::map<const std::string, LogLevel::type>::const_iterator search =
      s_allLogLevels.find(levelString);
  if (search != s_allLogLevels.end()) {
    return search->second;
  } else {
    // keep unchanged
    return logger.getLogLevel();
  }
}

int TraceFlag::g_idGenerator = 0;

const TraceFlag TraceFlag::ClientHA("ClientHA", TraceFlag::getNextId());
const TraceFlag TraceFlag::ClientStatement("ClientStatement",
    TraceFlag::getNextId());
const TraceFlag TraceFlag::ClientStatementHA("ClientStatementHA",
    TraceFlag::getNextId(), &TraceFlag::ClientHA, &TraceFlag::ClientStatement);
const TraceFlag TraceFlag::ClientStatementMillis("ClientStatementMillis",
    TraceFlag::getNextId());
const TraceFlag TraceFlag::ClientConn("ClientConn", TraceFlag::getNextId());

const int TraceFlag::getNextId() noexcept {
  return g_idGenerator++;
}

TraceFlag::TraceFlag(const std::string& name, const int id,
    const TraceFlag* parent1, const TraceFlag* parent2,
    const TraceFlag* parent3, const TraceFlag* parent4) :
    m_name(name), m_id(id), m_globalSet(false) {
  addParentFlag(parent1);
  addParentFlag(parent2);
  addParentFlag(parent3);
  addParentFlag(parent4);
}

void TraceFlag::addParentFlag(const TraceFlag* parent) {
  if (parent != NULL) {
    m_parentFlags.push_back(parent);
    parent->m_childFlags.push_back(this);
  }
}

void TraceFlag::enableFlag(char* traceFlags, bool enable,
    bool isGlobalLogger) const {
  if (enable) {
    traceFlags[m_id] = 1;
    // also set the global flag for quick check by global methods
    if (isGlobalLogger) {
      m_globalSet = true;
    }
    // also enable all children
    if (!m_childFlags.empty()) {
      for (std::vector<const TraceFlag*>::const_iterator iter =
          m_childFlags.begin(); iter != m_childFlags.end(); ++iter) {
        traceFlags[(*iter)->m_id] = 1;
        // also set the global flag for quick check by global methods
        if (isGlobalLogger) {
          (*iter)->m_globalSet = true;
        }
      }
    }
  } else {
    traceFlags[m_id] = 0;
    if (isGlobalLogger) {
      m_globalSet = false;
    }
    // also disable all children whose parents have all been turned off
    if (!m_childFlags.empty()) {
      for (std::vector<const TraceFlag*>::const_iterator iter =
          m_childFlags.begin(); iter != m_childFlags.end(); ++iter) {
        const std::vector<const TraceFlag*>& parents = (*iter)->m_parentFlags;
        bool allParentsDisabled = true;
        for (std::vector<const TraceFlag*>::const_iterator piter =
            parents.begin(); iter != parents.end(); ++iter) {
          if (traceFlags[(*piter)->m_id] == 1) {
            allParentsDisabled = false;
            break;
          }
        }
        if (allParentsDisabled) {
          traceFlags[(*iter)->m_id] = 0;
          if (isGlobalLogger) {
            (*iter)->m_globalSet = false;
          }
        }
      }
    }
  }
}

const char* LogWriter::LOGGING_FLAG = "THRIFT_CLIENT";

LogWriter LogWriter::g_logger("", LogLevel::error, false);
const char* LogWriter::NEWLINE = _SNAPPY_NEWLINE_STR;

void LogWriter::staticInitialize() {
  LogLevel::staticInitialize();
  // use local timezone for formatting on std::cout
  std::cout << boost::chrono::time_fmt(boost::chrono::timezone::local);
}

LogWriter::LogWriter(const std::string& logFile, const LogLevel::type logLevel,
    bool overwrite) : m_buffer(NULL) {
  initTraceFlags();
  initialize(logFile, logLevel, overwrite);
}

LogWriter::LogWriter(std::ostream* logStream, const std::string& logFile,
    const LogLevel::type logLevel) : m_rawStream(logStream),
        m_logFile(logFile), m_logLevel(logLevel), m_buffer(NULL) {
  initTraceFlags();
  logStream->exceptions(std::ofstream::failbit | std::ofstream::badbit);
  // use local timezone for formatting
  *m_rawStream << boost::chrono::time_fmt(boost::chrono::timezone::local);
}

LogWriter::~LogWriter() {
  close();
  if (m_traceFlags != NULL) {
    delete[] m_traceFlags;
    m_traceFlags = NULL;
  }
  if (m_buffer != NULL) {
    delete[] m_buffer;
    m_buffer = NULL;
  }
}

void LogWriter::initTraceFlags() {
  const int nTotalFlags = TraceFlag::maxGlobalId();
  m_traceFlags = new char[nTotalFlags];
  for (int i = 0; i < nTotalFlags; i++) {
    m_traceFlags[i] = 0;
  }
}

void LogWriter::initialize(const std::string& logFile,
    const LogLevel::type logLevel, bool overwrite) {
  m_logFile = logFile;
  m_logLevel = logLevel;

  // empty logFile indicates logging on stdout
  if (m_logFile.size() > 0) {
    boost::filesystem::ofstream* fs = new boost::filesystem::ofstream();
    std::unique_ptr<boost::filesystem::ofstream> fsp(fs);
    fs->exceptions(std::ofstream::failbit | std::ofstream::badbit);
    try {
      boost::filesystem::path logpath(impl::InternalUtils::getPath(m_logFile));

      // for overwrite==false and existing file, roll it over to new name
      if (!overwrite) {
        int maxTries = 50;
        boost::system::error_code ec;
        if (boost::filesystem::exists(logpath, ec)) {
          std::string targetFileName = m_logFile;
          while (maxTries-- > 0) {
            bool rolledOver = false;
            const size_t dashIndex = targetFileName.find_last_of('-');

            if (dashIndex != std::string::npos) {
              char* remaining = NULL;
              int rolloverIndex = ::strtol(
                  targetFileName.data() + dashIndex + 1, &remaining, 10);
              if (remaining != NULL && (*remaining == '.' || *remaining == 0)) {
                targetFileName = targetFileName.substr(0, dashIndex + 1).append(
                    std::to_string(rolloverIndex + 1)).append(remaining);
                rolledOver = true;
              }
            }
            if (!rolledOver) {
              const size_t dotIndex = targetFileName.find_last_of('.');
              if (dotIndex != std::string::npos) {
                targetFileName = targetFileName.substr(0, dotIndex).append(
                    "-1.").append(targetFileName.substr(dotIndex + 1));
              } else {
                targetFileName.append("-1");
              }
            }
            boost::filesystem::path targetPath(
                impl::InternalUtils::getPath(targetFileName));
            if (!boost::filesystem::exists(targetPath, ec)) {
              // rename current file to targetFileName
              boost::filesystem::rename(logpath, targetPath, ec);
              if (!boost::filesystem::exists(logpath, ec)) {
                break;
              }
            }
          }
        }
      }

      // Using binary mode to write the file deliberately. We don't want
      // to deal with any conversions at our layer. We expect application
      // layer to take care of encodings as required (e.g. using UTF8
      // uniformly would be a good choice). The only thing product code
      // takes care of in its internal logging is to use platform specific
      // line-endings. Application code should use the LogWriter::LINE_SEPARATOR
      // string for logging newlines.
      fs->open(logpath, std::ios::out | std::ios::binary |
          (overwrite ? std::ios::trunc : std::ios::ate));
      // increase buffer
      if (m_buffer == NULL) {
        m_buffer = new char[DEFAULT_BUFSIZE];
      }
      fs->rdbuf()->pubsetbuf(m_buffer, DEFAULT_BUFSIZE);
    } catch (const std::exception& fail) {
      throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, Utils::toString(fail));
    }
    close();
    m_rawStream.reset(fs);
    fsp.release();
    // use local timezone for formatting
    *m_rawStream << boost::chrono::time_fmt(boost::chrono::timezone::local);
  }
}

void LogWriter::close() {
  std::ostream* oldOut = m_rawStream.get();
  if (oldOut != NULL) {
    try {
      oldOut->flush();
      std::ofstream* oldFS = dynamic_cast<std::ofstream*>(oldOut);
      if (oldFS != NULL) {
        oldFS->close();
      }
    } catch (const std::exception& se) {
      // log to stderr and move on
      if (m_logFile.size() > 0) {
        std::cerr << "Failure in closing stream for " << m_logFile << ": "
            << se << std::endl;
      } else {
        std::cerr << "Failure in closing stream (empty file name): "
            << se << std::endl;
      }
    } catch (...) {
      // log to stderr and move on
      if (m_logFile.size() > 0) {
        std::cerr << "Unknown failure in closing stream for " << m_logFile
            << std::endl;
      } else {
        std::cerr << "Unknown failure in closing stream (empty file name)"
            << std::endl;
      }
    }
    m_rawStream.reset();
  }
}

std::ostream& LogWriter::getRawStream() {
  std::ostream* stream = m_rawStream.get();
  if (stream != NULL) {
    return *stream;
  } else {
    // output to stdout by default
    return std::cout;
  }
}

void LogWriter::setTraceFlag(const TraceFlag& flag, bool enable) {
  flag.enableFlag(m_traceFlags, enable, this == &g_logger);
}

std::ostream& LogWriter::print(const LogLevel::type logLevel, const char* flag) {
  std::ostream* ostr = &getRawStream();
  while (true) {
    std::ostream& out = *ostr;
    try {
      boost::chrono::system_clock::time_point currentTime =
          boost::chrono::system_clock::now();
      // our Timestamp is both more efficient and uses abbreviated
      // timezone names, so using that instead of chrono directly
      const int64_t totalNanos = currentTime.time_since_epoch().count();
      out << '[' << LogLevel::toString(logLevel) << ' '
          << types::Timestamp(totalNanos / types::Timestamp::NANOS_MAX,
              totalNanos % types::Timestamp::NANOS_MAX) << " SNAPPY:" << flag;
      if (Utils::getCurrentThreadName(" <", out)) {
        out << '>';
      }
      out << " tid=0x" << std::hex << boost::this_thread::get_id()
          << std::dec << "] ";
      return out;
    } catch (const std::exception& se) {
      if (ostr == &std::cerr) {
        return out;
      }
      std::cerr << "ERROR: failure in logging: " << se << _SNAPPY_NEWLINE;
      std::cerr << "ERROR: original message: ";
      ostr = &std::cerr;
    } catch (...) {
      if (ostr == &std::cerr) {
        return out;
      }
      std::cerr << "ERROR: unknown failure in logging" _SNAPPY_NEWLINE_STR;
      std::cerr << "ERROR: original message: ";
      ostr = &std::cerr;
    }
  }
  return *ostr;
}

std::ostream& LogWriter::printCompact(const LogLevel::type logLevel,
    const char* flag) {
  std::ostream& out = getRawStream();
  const boost::thread::id tid = boost::this_thread::get_id();

  impl::InternalLogger::compactLogThreadName(out, tid);
  return impl::InternalLogger::printCompact_(out, logLevel, flag, tid);
}

std::ostream& LogWriter::fatal() {
  return global().print(LogLevel::fatal, LOGGING_FLAG);
}
std::ostream& LogWriter::error() {
  return global().print(LogLevel::error, LOGGING_FLAG);
}
std::ostream& LogWriter::warn() {
  return global().print(LogLevel::warn, LOGGING_FLAG);
}
std::ostream& LogWriter::info() {
  return global().print(LogLevel::info, LOGGING_FLAG);
}
std::ostream& LogWriter::debug() {
  return global().print(LogLevel::debug, LOGGING_FLAG);
}
std::ostream& LogWriter::trace() {
  return global().print(LogLevel::trace, LOGGING_FLAG);
}

std::ostream& LogWriter::trace(const TraceFlag& flag) {
  return global().print(LogLevel::info, flag.name().c_str());
}
std::ostream& LogWriter::traceCompact(const TraceFlag& flag) {
  return global().printCompact(LogLevel::info, flag.name().c_str());
}
