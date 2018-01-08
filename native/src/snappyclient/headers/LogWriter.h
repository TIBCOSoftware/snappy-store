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

#ifndef LOGGING_H_
#define LOGGING_H_

#include "common/Base.h"
#include "SQLException.h"

extern "C" {
#include <limits.h>
}


namespace io {
namespace snappydata {
namespace client {

  namespace impl {
    class ClientService;
  }

  class LogWriter;

  struct LogLevel {
  private:
    LogLevel(); // no instance
    LogLevel(const LogLevel&); // no constructor
    LogLevel& operator=(const LogLevel&); // no assignment

  public:
    enum type {
      /**
       * If the writer's level is <code>ALL</code> then all messages
       * will be logged.
       */
      all = INT_MIN,
      /**
       * If the writer's level is <code>TRACE</code> then
       * trace, debug, info, warn, error, and fatal messages will be logged.
       */
      trace = 100,
      /**
       * If the writer's level is <code>DEBUG</code> then
       * debug, info, warn, error, and fatal messages will be logged.
       */
      debug = 200,
      /**
       * If the writer's level is <code>INFO</code> then
       * info, warn, error, and fatal messages will be logged.
       */
      info = 300,
      /**
       * If the writer's level is <code>WARN</code> then
       * warn, error, and fatal messages will be logged.
       */
      warn = 400,
      /**
       * If the writer's level is <code>ERROR</code> then
       * error and fatal messages will be logged.
       */
      error = 500,
      /**
       * If the writer's level is <code>FATAL</code> then
       * only fatal level messages will be logged.
       */
      fatal = 600,
      /**
       * If the writer's level is <code>NONE</code> then
       * no messages will be logged.
       */
      none = INT_MAX,
    };

  private:
    static std::map<const std::string, LogLevel::type> s_allLogLevels;
    static void staticInitialize();
    friend class LogWriter;

  public:
    static const char* toString(const LogLevel::type logLevel);
    static const LogLevel::type fromString(const std::string& levelString,
        const LogWriter& logger);
  };

  struct TraceFlag {
  private:
    TraceFlag(const LogLevel&) = delete; // no copy constructor
    TraceFlag& operator=(const LogLevel&) = delete; // no assignment

    const std::string m_name;
    const int m_id;
    mutable bool m_globalSet;

    /**
     * set of parent flags on which this depends (i.e. will turn on
     * if any of parent is on, and off when all are off)
     */
    std::vector<const TraceFlag*> m_parentFlags;
    /** set of child flags that depend on this (i.e. will be turned on
     * if this is on, and off when all the child's parents are off)
     */
    mutable std::vector<const TraceFlag*> m_childFlags;

    static int g_idGenerator;

    static const int getNextId() noexcept;

    TraceFlag(const std::string& name, const int id,
        const TraceFlag* parent1 = NULL, const TraceFlag* parent2 = NULL,
        const TraceFlag* parent3 = NULL, const TraceFlag* parent4 = NULL);

    void addParentFlag(const TraceFlag* parent);

  public:
    const std::string& name() const noexcept {
      return m_name;
    }

    const int id() const noexcept {
      return m_id;
    }

    bool global() const noexcept {
      return m_globalSet;
    }

    static int maxGlobalId() noexcept {
      return g_idGenerator;
    }

    void enableFlag(char* traceFlags, bool enable, bool isGlobalLogger) const;

    static const TraceFlag ClientHA;
    static const TraceFlag ClientStatement;
    static const TraceFlag ClientStatementHA;
    static const TraceFlag ClientStatementMillis;
    static const TraceFlag ClientConn;
  };

  // TODO: SW: need a thread-safe version of ostream for LogWriter
  // (e.g. using thread-local buffers)
  /**
   * A utility class to write text log-files for debugging, tracing,
   * etc using {@link LogLevel}s to control the output.
   * <p>
   * A convience global instance is provided (globalInstance) that
   * can be used by convenience SEVERE, WARNING and other such static
   * methods in the class. The generic log(LogLevel) instance method
   * of the class can be used for other non-global instances.
   * <p>
   * All the calls in the code of INFO(), WARNING(), SEVERE etc should
   * be preceeded by check for corresponding INFO_ENABLED() etc. The
   * static INFO(), WARNING() and such methods return a reference to
   * std::ostream which can then be chained using "<<" operator calls
   * as usual. An end of line should be indicated using
   * "<< LogWriter::NEWLINE" in the code.
   */
  class LogWriter {
  private:
    LogWriter(const LogWriter&) = delete; // no copy constructor
    LogWriter& operator=(const LogWriter&) = delete; // no assignment operator

    std::unique_ptr<std::ostream> m_rawStream;
    /**
     * The log-file being used for logging.
     */
    std::string m_logFile;
    /** The LogLevel for the current LogWriter. */
    LogLevel::type m_logLevel;
    /**
     * The set of enabled trace flags. We don't expect a very large
     * number of trace flags in total, so using an efficient array
     * instead of a regular set (or hash based set).
     */
    char* m_traceFlags;

    /** The default larger buffer used by LogWriter */
    char* m_buffer;

    static const char* LOGGING_FLAG;

    void initTraceFlags();

    static LogWriter g_logger;
    static void staticInitialize();
    friend class impl::ClientService;

  public:
    LogWriter(const std::string& logFile, const LogLevel::type logLevel,
        bool overwrite = false);

    LogWriter(std::ostream* logStream, const std::string& logFile,
        const LogLevel::type logLevel);

    ~LogWriter();

    static const int DEFAULT_BUFSIZE = 16 * 1024;

    static const char* NEWLINE;

    inline static void setGlobalLoggingFlag(const char* flag) {
      LOGGING_FLAG = flag;
    }

    inline static LogWriter& global() noexcept {
      return g_logger;
    }

    void initialize(const std::string& logFile,
        const LogLevel::type logLevel, bool overwrite = false);

    void close();

    std::ostream& getRawStream();

    const std::string& getLogFile() const noexcept {
      return m_logFile;
    }

    LogLevel::type getLogLevel() const noexcept {
      return m_logLevel;
    }

    inline bool isLogged(const LogLevel::type logLevel) const noexcept {
      return ((int)logLevel >= (int)m_logLevel);
    }

    inline bool isTraceEnabled(const TraceFlag& flag) const noexcept {
      return m_traceFlags[flag.id()] == 1;
    }

    void setTraceFlag(const TraceFlag& flag, bool enable);

    std::ostream& print(const LogLevel::type logLevel, const char* flag);

    std::ostream& printCompact(const LogLevel::type logLevel, const char* flag);

    std::ostream& log(const LogLevel::type logLevel);

    inline static bool fatalEnabled() noexcept {
      return ((int)LogLevel::fatal >= (int)g_logger.m_logLevel);
    }
    inline static bool errorEnabled() noexcept {
      return ((int)LogLevel::error >= (int)g_logger.m_logLevel);
    }
    inline static bool warnEnabled() noexcept {
      return ((int)LogLevel::warn >= (int)g_logger.m_logLevel);
    }
    inline static bool infoEnabled() noexcept {
      return ((int)LogLevel::info >= (int)g_logger.m_logLevel);
    }
    inline static bool debugEnabled() noexcept {
      return ((int)LogLevel::debug >= (int)g_logger.m_logLevel);
    }
    inline static bool traceEnabled() noexcept {
      return ((int)LogLevel::trace >= (int)g_logger.m_logLevel);
    }
    inline static bool traceEnabled(const TraceFlag& flag) noexcept {
      return flag.global();
    }

    static std::ostream& fatal();
    static std::ostream& error();
    static std::ostream& warn();
    static std::ostream& info();
    static std::ostream& debug();
    static std::ostream& trace();

    static std::ostream& trace(const TraceFlag& flag);
    static std::ostream& traceCompact(const TraceFlag& flag);
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* LOGGING_H_ */
