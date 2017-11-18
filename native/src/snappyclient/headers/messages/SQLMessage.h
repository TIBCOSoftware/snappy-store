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

#ifndef SQLMESSAGE_H_
#define SQLMESSAGE_H_

#include "common/MessageBase.h"

#include <sstream>

namespace io {
namespace snappydata {
namespace client {

  class SQLState;
  class SQLStateMessage;

  class SQLMessageBase : public MessageBase {
  private:
    const SQLState* m_sqlState;

  protected:
    SQLMessageBase();
    virtual ~SQLMessageBase();

    void initialize(const char* messageId);
    void initialize(const SQLState& sqlState, const int idPerSQLState);

  public:
    const SQLState* getSQLState() const noexcept;
  };

  class SQLMessage0 : public SQLMessageBase {
  private:
    SQLMessage0() : SQLMessageBase() {
    }
    void initialize(const char* messageId, const char* message) {
      SQLMessageBase::initialize(messageId);
      addMessagePart(message);
    }
    void initialize(const SQLState& sqlState, const int idPerSQLState,
        const char* message) {
      SQLMessageBase::initialize(sqlState, idPerSQLState);
      addMessagePart(message);
    }

    friend class SQLStateMessage;

  public:
    const std::string& format() const {
      return getMessagePart(0);
    }
  };

  template<typename T1>
  class SQLMessage1 : public SQLMessageBase {
  private:
    SQLMessage1() : SQLMessageBase() {
    }
    void initialize(const char* messageId, const char* message,
        const char* messagePart1, const char* messagePart2) {
      SQLMessageBase::initialize(messageId);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
    }
    void initialize(const SQLState& sqlState, const int idPerSQLState,
        const char* messagePart1, const char* messagePart2) {
      SQLMessageBase::initialize(sqlState, idPerSQLState);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
    }

    friend class SQLStateMessage;

  public:
    std::string format(const T1& arg1) const {
      std::ostringstream sstr;
      sstr << getMessagePart(0) << arg1 << getMessagePart(1);
      return sstr.str();
    }
  };

  template<typename T1, typename T2>
  class SQLMessage2 : public SQLMessageBase {
  private:
    SQLMessage2() : SQLMessageBase() {
    }

    void initialize(const char* messageId, const char* message,
        const char* messagePart1, const char* messagePart2,
        const char* messagePart3) {
      SQLMessageBase::initialize(messageId);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
      addMessagePart(messagePart3);
    }
    void initialize(const SQLState& sqlState, const int idPerSQLState,
        const char* messagePart1, const char* messagePart2,
        const char* messagePart3) {
      SQLMessageBase::initialize(sqlState, idPerSQLState);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
      addMessagePart(messagePart3);
    }

    friend class SQLStateMessage;

  public:
    std::string format(const T1& arg1, const T2& arg2) const {
      std::ostringstream sstr;
      sstr << getMessagePart(0) << arg1 << getMessagePart(1) << arg2
          << getMessagePart(2);
      return sstr.str();
    }
  };

  template<typename T1, typename T2, typename T3>
  class SQLMessage3 : public SQLMessageBase {
  private:
    SQLMessage3() : SQLMessageBase() {
    }

    void initialize(const char* messageId, const char* messagePart1,
        const char* messagePart2, const char* messagePart3,
        const char* messagePart4) {
      SQLMessageBase::initialize(messageId);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
      addMessagePart(messagePart3);
      addMessagePart(messagePart4);
    }
    void initialize(const SQLState& sqlState, const int idPerSQLState,
        const char* messagePart1, const char* messagePart2,
        const char* messagePart3, const char* messagePart4) {
      SQLMessageBase::initialize(sqlState, idPerSQLState);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
      addMessagePart(messagePart3);
      addMessagePart(messagePart4);
    }

    friend class SQLStateMessage;

  public:
    std::string format(const T1& arg1, const T2& arg2, const T3& arg3) const {
      std::ostringstream sstr;
      sstr << getMessagePart(0) << arg1 << getMessagePart(1) << arg2
          << getMessagePart(2) << arg3 << getMessagePart(3);
      return sstr.str();
    }
  };

  template<typename T1, typename T2, typename T3, typename T4>
  class SQLMessage4 : public SQLMessageBase {
  private:
    SQLMessage4() : SQLMessageBase() {
    }

    void initialize(const char* messageId, const char* messagePart1,
        const char* messagePart2, const char* messagePart3,
        const char* messagePart4, const char* messagePart5) {
      SQLMessageBase::initialize(messageId);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
      addMessagePart(messagePart3);
      addMessagePart(messagePart4);
      addMessagePart(messagePart5);
    }
    void initialize(const SQLState& sqlState, const int idPerSQLState,
        const char* messagePart1, const char* messagePart2,
        const char* messagePart3, const char* messagePart4,
        const char* messagePart5) {
      SQLMessageBase::initialize(sqlState, idPerSQLState);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
      addMessagePart(messagePart3);
      addMessagePart(messagePart4);
      addMessagePart(messagePart5);
    }

    friend class SQLStateMessage;

  public:
    std::string format(const T1& arg1, const T2& arg2, const T3& arg3,
        const T4& arg4) const {
      std::ostringstream sstr;
      sstr << getMessagePart(0) << arg1 << getMessagePart(1) << arg2
          << getMessagePart(2) << arg3 << getMessagePart(3) << arg4
          << getMessagePart(4);
      return sstr.str();
    }
  };

  template<typename T1, typename T2, typename T3, typename T4, typename T5>
  class SQLMessage5 : public SQLMessageBase {
  private:
    SQLMessage5() : SQLMessageBase() {
    }

    void initialize(const char* messageId, const char* messagePart1,
        const char* messagePart2, const char* messagePart3,
        const char* messagePart4, const char* messagePart5,
        const char* messagePart6) {
      SQLMessageBase::initialize(messageId);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
      addMessagePart(messagePart3);
      addMessagePart(messagePart4);
      addMessagePart(messagePart5);
      addMessagePart(messagePart6);
    }
    void initialize(const SQLState& sqlState, const int idPerSQLState,
        const char* messagePart1, const char* messagePart2,
        const char* messagePart3, const char* messagePart4,
        const char* messagePart5, const char* messagePart6) {
      SQLMessageBase::initialize(sqlState, idPerSQLState);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
      addMessagePart(messagePart3);
      addMessagePart(messagePart4);
      addMessagePart(messagePart5);
      addMessagePart(messagePart6);
    }

    friend class SQLStateMessage;

  public:
    std::string format(const T1& arg1, const T2& arg2, const T3& arg3,
        const T4& arg4, const T5& arg5) const {
      std::ostringstream sstr;
      sstr << getMessagePart(0) << arg1 << getMessagePart(1) << arg2
          << getMessagePart(2) << arg3 << getMessagePart(3) << arg4
          << getMessagePart(4) << arg5 << getMessagePart(5);
      return sstr.str();
    }
  };

  template<typename T1, typename T2, typename T3, typename T4,
      typename T5, typename T6>
  class SQLMessage6 : public SQLMessageBase {
  private:
    SQLMessage6() : SQLMessageBase() {
    }

    void initialize(const char* messageId, const char* messagePart1,
        const char* messagePart2, const char* messagePart3,
        const char* messagePart4, const char* messagePart5,
        const char* messagePart6, const char* messagePart7) {
      SQLMessageBase::initialize(messageId);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
      addMessagePart(messagePart3);
      addMessagePart(messagePart4);
      addMessagePart(messagePart5);
      addMessagePart(messagePart6);
      addMessagePart(messagePart7);
    }
    void initialize(const SQLState& sqlState, const int idPerSQLState,
        const char* messagePart1, const char* messagePart2,
        const char* messagePart3, const char* messagePart4,
        const char* messagePart5, const char* messagePart6,
        const char* messagePart7) {
      SQLMessageBase::initialize(sqlState, idPerSQLState);
      addMessagePart(messagePart1);
      addMessagePart(messagePart2);
      addMessagePart(messagePart3);
      addMessagePart(messagePart4);
      addMessagePart(messagePart5);
      addMessagePart(messagePart6);
      addMessagePart(messagePart7);
    }

    friend class SQLStateMessage;

  public:
    std::string format(const T1& arg1, const T2& arg2, const T3& arg3,
        const T4& arg4, const T5& arg5, const T6& arg6) const {
      std::ostringstream sstr;
      sstr << getMessagePart(0) << arg1 << getMessagePart(1) << arg2
          << getMessagePart(2) << arg3 << getMessagePart(3) << arg4
          << getMessagePart(4) << arg5 << getMessagePart(5) << arg6
          << getMessagePart(6);
      return sstr.str();
    }
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* SQLMESSAGE_H_ */
