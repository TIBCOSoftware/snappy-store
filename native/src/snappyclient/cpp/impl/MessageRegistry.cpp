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

#include "MessageRegistry.h"
#include <sstream>

using namespace io::snappydata;
using namespace io::snappydata::impl;

MessageRegistry MessageRegistry::s_instance;

MessageRegistry::MessageRegistry() : m_allMessages() {
}

void MessageRegistry::addMessage(MessageBase& msg) {
  const std::string& messageId = msg.getMessageId();
  if (messageId.empty()) {
    return;
  }
  // push into the global map
  if (!m_allMessages.putIfAbsent(messageId, &msg)) {
    std::ostringstream errorMessage;
    MessageBase* old;
    errorMessage << "Internal error in MessageBase: message with id "
        << messageId << " already exists";
    if (m_allMessages.get(messageId, &old)) {
      errorMessage << " (with parts:";
      for (std::vector<std::string>::const_iterator iter =
          old->m_messageParts.begin(); iter != old->m_messageParts.end();
          ++iter) {
        errorMessage << " [" << (*iter) << ']';
      }
      errorMessage << ')';
    }
    throw MessageException(errorMessage.str().c_str());
  }
}

void MessageRegistry::removeMessage(const MessageBase& msg) {
  const std::string& messageId = msg.getMessageId();
  if (messageId.empty()) {
    return;
  }
  m_allMessages.remove(messageId);
}

MessageBase* MessageRegistry::lookup(const std::string& messageId) const {
  MessageBase* result = NULL;
  m_allMessages.get(messageId, &result);
  return result;
}
