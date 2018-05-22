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

#include "messages/SQLMessage.h"
#include "SQLState.h"

#include "Utils.h"

using namespace io::snappydata::client;

SQLMessageBase::SQLMessageBase() : MessageBase(), m_sqlState(NULL) {
}

void SQLMessageBase::initialize(const char* messageId) {
  MessageBase::initialize(messageId);
  m_sqlState = NULL;
}

void SQLMessageBase::initialize(const SQLState& sqlState,
    const int idPerSQLState) {
  std::string id(sqlState.getSQLState());
  id.append(1, '.');
  Utils::convertIntToString(idPerSQLState, id);
  MessageBase::initialize(id);
  m_sqlState = &sqlState;
}

SQLMessageBase::~SQLMessageBase() {
}

const SQLState* SQLMessageBase::getSQLState() const noexcept {
  return m_sqlState;
}
