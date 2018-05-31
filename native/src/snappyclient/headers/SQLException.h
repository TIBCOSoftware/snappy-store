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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

#ifndef SQLEXCEPTION_H_
#define SQLEXCEPTION_H_

#include "ClientBase.h"
#include "SQLState.h"

#include <sstream>
#include <vector>

#define GET_SQLEXCEPTION(...) \
    io::snappydata::client::SQLException(__FILE__, __LINE__, __VA_ARGS__)

#define GET_SQLEXCEPTION2(m, ...) \
    io::snappydata::client::SQLException(__FILE__, __LINE__, \
        *m.getSQLState(), m.format(__VA_ARGS__))

#define GET_SQLWARNING(...) \
    io::snappydata::client::SQLWarning(__FILE__, __LINE__, __VA_ARGS__)

#define GET_SQLWARNING2(m, ...) \
    io::snappydata::client::SQLWarning(__FILE__, __LINE__, \
        *m.getSQLState(), m.format(__VA_ARGS__))

#define STACK_MAX_SIZE 50

namespace io {
namespace snappydata {

namespace thrift {
  class SnappyException;
}

namespace client {

  class SQLException;

  class SQLException : std::exception {
  public:
    SQLException(const char* file, int line, const SQLState& state,
        const std::string& reason, SQLException* next = NULL);

    SQLException(const char* file, int line,
        const thrift::SnappyException& se);

    SQLException(const char* file, int line, const std::exception& ex);

    // copy constructor
    SQLException(const SQLException& other);

    // move constructor
    SQLException(SQLException&& other);

    virtual SQLException* clone() const;

    const std::string& getReason() const noexcept {
      return m_reason;
    }

    virtual const char* what() const noexcept {
      return m_reason.c_str();
    }

    const std::string& getSQLState() const noexcept {
      return m_state;
    }

    int32_t getSeverity() const noexcept {
      return m_severity;
    }

    const SQLException* getNextException() const noexcept {
      return m_next;
    }

    void setNextException(SQLException* next) noexcept {
      m_next = next;
    }

    const char* getFileName() const noexcept {
      return m_file;
    }

    int getLineNumber() const noexcept {
      return m_line;
    }

    /** Print the stack trace to given output stream. */
    virtual std::ostream& printStackTrace(std::ostream& out) const;

    inline std::string getStackTrace() const {
      std::ostringstream str;
      printStackTrace(str);
      return str.str();
    }

    inline std::string toString() const {
      std::ostringstream str;
      toString(str);
      return str.str();
    }

    virtual void toString(std::ostream& out) const;

    virtual ~SQLException();

  protected:
    std::string m_reason;
    std::string m_state;
    int32_t m_severity;
    SQLException* m_next;

    const char* m_file;
    const int m_line;

#ifdef __GNUC__
    void* m_stack[STACK_MAX_SIZE]; // exception stack
    size_t m_stackSize; // number of entries in the exception stack

    void copyStack(void* const * stack, size_t stackSize);
#endif

    void init();

    SQLException(const char* file, int line,
        const thrift::SnappyExceptionData& snappyExceptionData) :
        m_reason(snappyExceptionData.reason),
        m_state(snappyExceptionData.sqlState),
        m_severity(snappyExceptionData.errorCode), m_next(NULL),
        m_file(file), m_line(line) {
      init();
    }

    SQLException(const char* file, int line, const std::string& reason,
        const char* state, const int32_t severity
#ifdef __GNUC__
        , void* const * stack, size_t stackSize
#endif
        ) :
        m_reason(reason), m_state(state), m_severity(severity),
        m_next(NULL), m_file(file), m_line(line) {
#ifdef __GNUC__
      copyStack(stack, stackSize);
#endif
    }

    virtual SQLException* createNextException(
        const thrift::SnappyExceptionData& snappyExceptionData) {
      return new SQLException(m_file, m_line, snappyExceptionData);
    }

    virtual SQLException* createNextException(const char* file, int line,
        const std::string& reason, const char* state, int32_t severity
#ifdef __GNUC__
        , void* const * stack, size_t stackSize
#endif
        ) {
      return new SQLException(file, line, reason, state, severity
#ifdef __GNUC__
          , stack, stackSize
#endif
          );
    }

    void initNextException(
        const std::vector<thrift::SnappyExceptionData>& nextExceptions);

    void initNextException(const SQLException& other);

    /**
     * Get the name of this exception class. Child classes must always
     * override this to return their own class name.
     */
    virtual const char* getName() const noexcept {
      return "SQLException";
    }

    /**
     * Number of stack frames to skip when constructing the stack trace
     */
    virtual size_t skipFrames() const noexcept {
#ifdef __GNUC__
      // skip the top-most constructor frame
      return 1;
#else
      return 0;
#endif
    }
  };

  class SQLWarning : public SQLException {
  public:
    SQLWarning(const char* file, int line, const SQLState& state,
        const std::string& reason, SQLWarning* next = NULL);

    SQLWarning(const char* file, int line,
        const thrift::SnappyExceptionData& snappyExceptionData) :
        SQLException(file, line, snappyExceptionData) {
    }

    // copy constructor
    SQLWarning(const SQLWarning& other);

    // move constructor
    SQLWarning(SQLWarning&& other);

    virtual SQLException* clone() const;

    const SQLWarning* getNextWarning() const;

    void setNextWarning(SQLWarning* next);

  protected:
    SQLWarning(const char* file, int line, const std::string& reason,
        const char* state, const int32_t severity
#ifdef __GNUC__
        , void* const * stack, size_t stackSize
#endif
        ) :
        SQLException(file, line, reason, state, severity
#ifdef __GNUC__
            , stack, stackSize
#endif
            ) {
    }

    virtual SQLException* createNextException(
        const thrift::SnappyExceptionData& snappyExceptionData) {
      return new SQLWarning(m_file, m_line, snappyExceptionData);
    }

    virtual SQLException* createNextException(const char* file, int line,
        const std::string& reason, const char* state, int32_t severity
#ifdef __GNUC__
        , void* const * stack, size_t stackSize
#endif
        ) {
      return new SQLWarning(file, line, reason, state, severity
#ifdef __GNUC__
          , stack, stackSize
#endif
          );
    }

    virtual const char* getName() const noexcept {
      return "SQLWarning";
    }

    virtual size_t skipFrames() const noexcept {
      return SQLException::skipFrames() + 1;
    }
  };

  /** For I/O manipulator to get the stack trace. */
  struct _SqleStack {
    const SQLException& m_sqle;
  };
  struct _StdeStack {
    const std::exception& m_stde;
  };

  /** Parameterized I/O manipulator to get the stack trace. */
  inline _SqleStack stack(const SQLException& sqle) {
    _SqleStack s = { sqle };
    return s;
  }

  /** Parameterized I/O manipulator to get the stack trace. */
  inline _StdeStack stack(const std::exception& se) {
    _StdeStack s = { se };
    return s;
  }

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

std::ostream& operator<<(std::ostream& out,
    const io::snappydata::client::SQLException& sqle);

std::ostream& operator<<(std::ostream& out,
    io::snappydata::client::_SqleStack s);

std::ostream& operator<<(std::ostream& out, const std::exception& stde);

std::ostream& operator<<(std::ostream& out,
    io::snappydata::client::_StdeStack s);

#endif /* SQLEXCEPTION_H_ */
