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
 * SQLException.cpp
 */

#include "impl/pch.h"

extern "C" {
#  ifndef _WINDOWS
#    include <limits.h>
#  endif
#  ifdef __GNUC__
#    include <execinfo.h>
#  endif
}

using namespace io::snappydata;
using namespace io::snappydata::client;

std::string SQLException::s_processBaseAddress;
std::string SQLException::s_libraryBaseAddress;

SQLException::SQLException(const char* file, int line, const SQLState& state,
    const std::string& reason, SQLException* next) :
    m_reason(reason), m_state(state.getSQLState()),
    m_severity(static_cast<int32_t>(state.getSeverity())), m_next(next),
    m_file(file), m_line(line) {
  init();
}

SQLException::SQLException(const char* file, int line,
	const thrift::SnappyException& se) :
    m_reason(se.exceptionData.reason),
    m_state(se.exceptionData.sqlState),
    m_severity(se.exceptionData.errorCode), m_next(nullptr),
    m_file(file), m_line(line) {
  initNextException(se.nextExceptions);
  init();
}

SQLException::SQLException(const char* file, int line) :
    m_reason(), m_state(SQLState::UNKNOWN_EXCEPTION.getSQLState()),
    m_severity(static_cast<int32_t>(ExceptionSeverity::SESSION_SEVERITY)),
    m_next(nullptr), m_file(file), m_line(line) {
#ifdef __GNUC__
  m_stackSize = 0;
#endif
}

SQLException::SQLException(const char* file, int line,
    const std::exception& stde) :
    m_reason(stde.what()), m_state(SQLState::UNKNOWN_EXCEPTION.getSQLState()),
    m_severity(static_cast<int32_t>(ExceptionSeverity::SESSION_SEVERITY)),
    m_next(nullptr), m_file(file), m_line(line) {
  init();
}

SQLException::SQLException(const SQLException& other) :
    m_reason(other.m_reason), m_state(other.m_state),
    m_severity(other.m_severity), m_next(nullptr),
    m_file(other.m_file), m_line(other.m_line) {
#ifdef __GNUC__
  copyStack(other.m_stack, other.m_stackSize);
#endif
  initNextException(other);
}

SQLException::SQLException(SQLException&& other) :
    m_reason(std::move(other.m_reason)), m_state(std::move(other.m_state)),
    m_severity(other.m_severity), m_next(other.m_next),
    m_file(other.m_file), m_line(other.m_line) {
  other.m_next = nullptr;
#ifdef __GNUC__
  copyStack(other.m_stack, other.m_stackSize);
  other.m_stackSize = 0;
#endif
}

SQLException& SQLException::operator=(const SQLException &other) {
  m_reason = other.m_reason;
  m_state = other.m_state;
  m_severity = other.m_severity;
  deleteNextException();
  initNextException(other);
  m_file = other.m_file;
  m_line = other.m_line;
#ifdef __GNUC__
  copyStack(other.m_stack, other.m_stackSize);
#endif
  return *this;
}

SQLException& SQLException::operator=(SQLException &&other) {
  m_reason = std::move(other.m_reason);
  m_state = std::move(other.m_state);
  m_severity = other.m_severity;
  deleteNextException();
  m_next = other.m_next;
  other.m_next = nullptr;
  m_file = other.m_file;
  m_line = other.m_line;
#ifdef __GNUC__
  copyStack(other.m_stack, other.m_stackSize);
  other.m_stackSize = 0;
#endif
  return *this;
}

void SQLException::staticInitialize() {
#ifndef _WINDOWS
  // initialize the process base address
  char buffer[PATH_MAX];
  int len = ::readlink("/proc/self/exe", buffer, PATH_MAX - 1);
  if (len > 0) {
    buffer[len] = '\0';
    // search for the first instance of executable in /proc/self/maps
    std::ifstream maps("/proc/self/maps");
    if (maps.is_open()) {
      std::string line;
      while (std::getline(maps, line)) {
        if (line.find(buffer) != std::string::npos) {
          size_t addrEnd = line.find('-', 0);
          if (addrEnd != std::string::npos) {
            s_processBaseAddress.append(" 0x");
            s_processBaseAddress.append(line.begin(),
                line.begin() + static_cast<ptrdiff_t>(addrEnd));
          }
        } else if (line.find("snappyclient") != std::string::npos
            || line.find("snappyodbc") != std::string::npos) {
          size_t addrEnd = line.find('-', 0);
          if (addrEnd != std::string::npos) {
            s_processBaseAddress.append(" 0x");
            s_libraryBaseAddress.append(line.begin(),
                line.begin() + static_cast<ptrdiff_t>(addrEnd));
          }
        }
      }
    }
  }
#endif
}

SQLException* SQLException::clone() const {
  return new SQLException(*this);
}

#ifdef __GNUC__
void SQLException::copyStack(void* const * stack, size_t stackSize) {
  if (stack && stackSize > 0) {
    for (size_t i = 0; i < stackSize; i++) {
      m_stack[i] = stack[i];
    }
  }
  m_stackSize = stackSize;
}
#endif

void SQLException::init() {
  // TODO: Add stack handling for Windows
#ifdef __GNUC__
  // get the stack trace; only get the frames here for efficiency
  // while the names will be gotten in getStackTrace()
  m_stackSize = static_cast<size_t>(::backtrace(m_stack, STACK_MAX_SIZE));
#endif
}

void SQLException::initNextException(
    const std::vector<thrift::SnappyExceptionData>& nextExceptions) {
  if (nextExceptions.size() > 0) {
    SQLException* next = nullptr;
    SQLException* current;
    // create from the end prepending to the list at the front
    for (auto iter = nextExceptions.rbegin(); iter != nextExceptions.rend();
        ++iter) {
      current = createNextException(*iter);
      current->m_next = next;
      next = current;
    }
    m_next = next;
  } else {
    m_next = nullptr;
  }
}

void SQLException::initNextException(const SQLException& other) {
  // re-create the next chain
  const SQLException* onext = &other;
  SQLException* next = this;
  while ((onext = onext->m_next)) {
    next->m_next = createNextException(onext->m_file, onext->m_line,
        onext->m_reason, onext->m_state.c_str(), onext->m_severity
#ifdef __GNUC__
        , onext->m_stack, onext->m_stackSize
#endif
        );
    next = next->m_next;
  }
}

std::ostream& SQLException::printStackTrace(std::ostream& out, int level) const {
  toString(out);
  out << _SNAPPY_NEWLINE;
#ifdef __GNUC__
  char** stackStrings;
  const size_t skip = skipFrames();
  if (m_stack && m_stackSize > skip) {
    if ((stackStrings = ::backtrace_symbols(m_stack, m_stackSize))) {
      std::unique_ptr<char*, decltype(std::free)*> freeStrings(stackStrings,
          std::free);
      std::string stackStr, function;
      size_t begin, end;
      char* demangledName;
      for (size_t i = skip; i < m_stackSize; i++) {
        demangledName = nullptr;
        stackStr.assign(stackStrings[i]);

        // locate the mangled name after the parentheses and address
        begin = stackStr.find('(');
        if (begin != std::string::npos) {
          // search for the end
          if ((end = stackStr.find('+', begin + 2)) != std::string::npos) {
            function.assign(stackStr.substr(begin + 1, end - begin - 1));
          } else {
            function.assign(stackStr.substr(begin + 1));
          }
          // demangle the name
          demangledName = Utils::gnuDemangledName(function.c_str());
        }
        if (demangledName) {
          std::unique_ptr<char, decltype(std::free)*> freeName(demangledName,
              std::free);
          out << "\tat " << stackStr.substr(0, begin + 1) << demangledName;
          if (end != std::string::npos) {
            out << stackStr.substr(end);
          }
        } else {
          out << "\tat " << stackStr;
        }
        out << _SNAPPY_NEWLINE;
      }
    }
  }
  if (m_next) {
    out << "Caused by:" << _SNAPPY_NEWLINE;
    m_next->printStackTrace(out, level + 1);
  }
  if (level == 0 && stackStrings) {
    bool hasBaseAddresses = false;
    if (!s_processBaseAddress.empty()) {
      out << "Process base addresses:" << s_processBaseAddress
          << _SNAPPY_NEWLINE;
      hasBaseAddresses = true;
    }
    if (!s_libraryBaseAddress.empty()) {
      out << "ODBC library base addresses:" << s_libraryBaseAddress
          << _SNAPPY_NEWLINE;
      hasBaseAddresses = true;
    }
    if (hasBaseAddresses) {
      out << "(Note: use addr2line/gdb to translate addresses shown in "
          "square brackets to code file and line numbers after taking the "
          "hex diff with the first base address of process or library)"
          << _SNAPPY_NEWLINE;
    }
  }
#else
  if (m_next) {
    out << "Caused by:" << _SNAPPY_NEWLINE;
    m_next->printStackTrace(out, level + 1);
  }
#endif
  return out;
}

void SQLException::toString(std::ostream& out) const {
  out << "SQLSTATE=" << m_state << " SEVERITY=" << m_severity
      << ": " << m_reason << _SNAPPY_NEWLINE_STR "\tat " << m_file << ':'
      << m_line;
}

void SQLException::deleteNextException() noexcept {
  SQLException* next = m_next;
  SQLException* pnext;
  while (next) {
    // iteratively go at the next and start deleting/clearing
    pnext = next->m_next;
    next->m_next = nullptr;
    delete next;
    next = pnext;
  }
  m_next = nullptr;
}

SQLException::~SQLException() noexcept {
  deleteNextException();
}

SQLWarning::SQLWarning(const char* file, int line, const SQLState& state,
    const std::string& reason, SQLWarning* next) :
    SQLException(file, line, state, reason, next) {
}

const SQLWarning* SQLWarning::getNextWarning() const {
  const SQLException* next = getNextException();
  return next ? dynamic_cast<const SQLWarning*>(next) : nullptr;
}

void SQLWarning::setNextWarning(SQLWarning* next) {
  m_next = next;
}

SQLWarning::SQLWarning(const SQLWarning& other) :
    SQLException(other.m_file, other.m_line, other.m_reason,
        other.m_state.c_str(), other.m_severity
#ifdef __GNUC__
        , other.m_stack, other.m_stackSize
#endif
        ) {
  // virtual call from inside here is fine since this is after
  // base class object construction has been completed
  initNextException(other);
}

SQLWarning::SQLWarning(SQLWarning&& other) :
    SQLException(std::move(other)) {
}

SQLException* SQLWarning::clone() const {
  return new SQLWarning(*this);
}

std::ostream& operator<<(std::ostream& out, const SQLException& sqle) {
  sqle.toString(out);
  return out;
}

std::ostream& operator<<(std::ostream& out, _SqleStack s) {
  return s.m_sqle.printStackTrace(out);
}

std::ostream& operator<<(std::ostream& out, const std::exception& stde) {
  return out << Utils::toString(stde);
}

std::ostream& operator<<(std::ostream& out, _StdeStack s) {
  return GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION,
      Utils::toString(s.m_stde)).printStackTrace(out);
}
