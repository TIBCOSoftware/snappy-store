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

#include "common/MessageBase.h"
#include "../impl/MessageRegistry.h"

using namespace io::snappydata;

MessageBase::MessageBase() : m_messageId(), m_messageParts() {
}

void MessageBase::initialize(const char* messageId) {
  impl::MessageRegistry::instance().removeMessage(*this);
  if (messageId != NULL) {
    m_messageId = messageId;
    impl::MessageRegistry::instance().addMessage(*this);
  } else {
    m_messageId.clear();
  }
}

void MessageBase::initialize(const std::string& messageId) {
  impl::MessageRegistry::instance().removeMessage(*this);
  if (messageId.size() > 0) {
    m_messageId = messageId;
    impl::MessageRegistry::instance().addMessage(*this);
  } else {
    m_messageId.clear();
  }
}

MessageBase::~MessageBase() {
}

void MessageBase::addMessagePart(const char* messagePart) {
  m_messageParts.push_back(messagePart);
}

size_t MessageBase::getNumMessageParts() const noexcept {
  return m_messageParts.size();
}

const std::string& MessageBase::getMessagePart(int index) const {
  return m_messageParts.at(index);
}

MessageException::MessageException(const char* message) : m_message(message) {
}

MessageException::~MessageException() {
}

const char* MessageException::what() const noexcept {
  return m_message.c_str();
}
