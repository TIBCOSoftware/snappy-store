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
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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
#include "common/AutoPtr.h"
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
      ALL = INT_MIN,
      /**
       * If the writer's level is <code>FINEST</code> then
       * finest, finer, fine, config, info, warning, error, and
       * severe messages will be logged.
       */
      FINEST = 300,
      /**
       * If the writer's level is <code>FINER</code> then
       * finer, fine, config, info, warning, error, and severe messages
       * will be logged.
       */
      FINER = 400,
      /**
       * If the writer's level is <code>FINE</code> then
       * fine, config, info, warning, error, and severe messages
       * will be logged.
       */
      FINE = 500,
      /**
       * If the writer's level is <code>CONFIG</code> then
       * config, info, warning, error, and severe messages will be logged.
       */
      CONFIG = 700,
      /**
       * If the writer's level is <code>INFO</code> then
       * info, warning, error, and severe messages will be logged.
       */
      INFO = 800,
      /**
       * If the writer's level is <code>WARNING</code> then
       * warning, error, and severe messages will be logged.
       */
      WARNING = 900,
      /**
       * If the writer's level is <code>SEVERE</code> then
       * only severe messages will be logged.
       */
      SEVERE = 1000,
      /**
       * If the writer's level is <code>ERROR</code> then
       * error and severe messages will be logged.
       */
      ERROR = ((int)WARNING + (int)SEVERE) / 2,
      /**
       * If the writer's level is <code>NONE</code> then
       * no messages will be logged.
       */
      NONE = INT_MAX,
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
    TraceFlag(const LogLevel&); // no copy constructor
    TraceFlag& operator=(const LogLevel&); // no assignment

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

    static const int getNextId() throw ();

    TraceFlag(const std::string& name, const int id,
        const TraceFlag* parent1 = NULL, const TraceFlag* parent2 = NULL,
        const TraceFlag* parent3 = NULL, const TraceFlag* parent4 = NULL);

    void addParentFlag(const TraceFlag* parent);

  public:
    inline const std::string& name() const throw () {
      return m_name;
    }

    inline int id() const throw () {
      return m_id;
    }

    inline bool global() const throw () {
      return m_globalSet;
    }

    inline static bool maxGlobalId() throw () {
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
    LogWriter(); // no default constructor
    LogWriter(const LogWriter&); // no copy constructor
    LogWriter& operator=(const LogWriter&); // no assignment operator

    AutoPtr<std::ostream> m_rawStream;
    /**
     * The log-file being used for logging.
     * This is a copy-on-write field so embedded in AutoPtr.
     */
    AutoPtr<std::string> m_logFile;
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

    ~LogWriter() throw ();

    static const int DEFAULT_BUFSIZE = 16 * 1024;

    static const char* NEWLINE;

    inline static LogWriter& global() throw () {
      return g_logger;
    }

    void initialize(const std::string& logFile,
        const LogLevel::type logLevel, bool overwrite = false);

    void close();

    std::ostream& getRawStream();

    AutoPtr<const std::string> getLogFile() const throw () {
      return AutoPtr<const std::string>(m_logFile.get(), false);
    }

    LogLevel::type getLogLevel() const throw () {
      return m_logLevel;
    }

    inline bool isLogged(const LogLevel::type logLevel) const {
      return ((int)logLevel >= (int)m_logLevel);
    }

    inline bool isTraceEnabled(const TraceFlag& flag) const {
      return m_traceFlags[flag.id()] == 1;
    }

    void setTraceFlag(const TraceFlag& flag, bool enable);

    std::ostream& print(const LogLevel::type logLevel, const char* flag);

    std::ostream& printCompact(const LogLevel::type logLevel, const char* flag);

    std::ostream& log(const LogLevel::type logLevel);

    inline static bool SEVERE_ENABLED() {
      return ((int)LogLevel::SEVERE >= (int)g_logger.m_logLevel);
    }
    inline static bool ERROR_ENABLED() {
      return ((int)LogLevel::ERROR >= (int)g_logger.m_logLevel);
    }
    inline static bool WARNING_ENABLED() {
      return ((int)LogLevel::WARNING >= (int)g_logger.m_logLevel);
    }
    inline static bool CONFIG_ENABLED() {
      return ((int)LogLevel::CONFIG >= (int)g_logger.m_logLevel);
    }
    inline static bool INFO_ENABLED() {
      return ((int)LogLevel::INFO >= (int)g_logger.m_logLevel);
    }
    inline static bool FINE_ENABLED() {
      return ((int)LogLevel::FINE >= (int)g_logger.m_logLevel);
    }
    inline static bool FINER_ENABLED() {
      return ((int)LogLevel::FINER >= (int)g_logger.m_logLevel);
    }
    inline static bool FINEST_ENABLED() {
      return ((int)LogLevel::FINEST >= (int)g_logger.m_logLevel);
    }
    inline static bool TRACE_ENABLED(const TraceFlag& flag) {
      return flag.global();
    }

    static std::ostream& SEVERE();
    static std::ostream& ERROR();
    static std::ostream& WARNING();
    static std::ostream& CONFIG();
    static std::ostream& INFO();
    static std::ostream& FINE();
    static std::ostream& FINER();
    static std::ostream& FINEST();

    static std::ostream& TRACE(const TraceFlag& flag);
    static std::ostream& TRACE_COMPACT(const TraceFlag& flag);
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* CONNECTION_H_ */
