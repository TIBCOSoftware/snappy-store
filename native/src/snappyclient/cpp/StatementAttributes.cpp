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
 * StatementAttributes.cpp
 */

#include "StatementAttributes.h"

using namespace io::snappydata::client;

StatementAttributes StatementAttributes::EMPTY;

StatementAttributes::StatementAttributes() :
    m_attrs(new thrift::StatementAttrs()) {
  // TODO: need to implement proper BLOB/CLOB/ARRAY/MAP/STRUCT/UDT support
  // get full value in one shot for now
  m_attrs->__set_lobChunkSize(0);
}

StatementAttributes::StatementAttributes(const StatementAttributes& attrs) :
    m_attrs(attrs.m_attrs) {
}

ResultSetHoldability StatementAttributes::getResultSetHoldability()
    const noexcept {
  return (m_attrs->holdCursorsOverCommit && m_attrs->__isset
      .holdCursorsOverCommit) || thrift::snappydataConstants
      ::DEFAULT_RESULTSET_HOLD_CURSORS_OVER_COMMIT
      ? ResultSetHoldability::HOLD_CURSORS_OVER_COMMIT
      : ResultSetHoldability::CLOSE_CURSORS_OVER_COMMIT;
}

bool StatementAttributes::isFetchDirectionReverse() const noexcept {
  return m_attrs->__isset.fetchReverse && m_attrs->fetchReverse;
}

uint32_t StatementAttributes::getLobChunkSize() const noexcept {
  return m_attrs->__isset.lobChunkSize ? m_attrs->lobChunkSize
      : thrift::snappydataConstants::DEFAULT_LOB_CHUNKSIZE;
}

uint32_t StatementAttributes::getMaxRows() const noexcept {
  return m_attrs->__isset.maxRows ? m_attrs->maxRows : 0;
}

uint32_t StatementAttributes::getMaxFieldSize() const noexcept {
  return m_attrs->__isset.maxFieldSize ? m_attrs->maxFieldSize : 0;
}

uint32_t StatementAttributes::getTimeout() const noexcept {
  return m_attrs->__isset.timeout ? m_attrs->timeout : 0;
}

const std::string& StatementAttributes::getCursorName() const noexcept {
  return m_attrs->__isset.cursorName ? m_attrs->cursorName : EMPTY_STRING;
}

bool StatementAttributes::isPoolable() const noexcept {
  return m_attrs->__isset.poolable && m_attrs->poolable;
}

bool StatementAttributes::hasEscapeProcessing() const noexcept {
  return m_attrs->__isset.doEscapeProcessing && m_attrs->doEscapeProcessing;
}

void StatementAttributes::setResultSetType(ResultSetType rsType) {
  m_attrs->__set_resultSetType((int8_t)rsType);
}

void StatementAttributes::setResultSetHoldability(
    ResultSetHoldability holdability) {
  m_attrs->__set_holdCursorsOverCommit(
      holdability == ResultSetHoldability::HOLD_CURSORS_OVER_COMMIT);
}

void StatementAttributes::setUpdatable(bool updatable) {
  m_attrs->__set_updatable(updatable);
}

void StatementAttributes::setBatchSize(uint32_t batchSize) {
  m_attrs->__set_batchSize(batchSize);
}

void StatementAttributes::setFetchDirectionReverse(bool fetchDirection) {
  m_attrs->__set_fetchReverse(fetchDirection);
}

void StatementAttributes::setLobChunkSize(uint32_t size) {
  m_attrs->__set_lobChunkSize(size);
}

void StatementAttributes::setMaxRows(uint32_t num) {
  m_attrs->__set_maxRows(num);
}

void StatementAttributes::setMaxFieldSize(uint32_t size) {
  m_attrs->__set_maxFieldSize(size);
}

void StatementAttributes::setTimeout(uint32_t timeout) {
  m_attrs->__set_timeout(timeout);
}

void StatementAttributes::setCursorName(const std::string& name) {
  m_attrs->__set_cursorName(name);
}

void StatementAttributes::setPoolable(bool poolable) {
  m_attrs->__set_poolable(poolable);
}

void StatementAttributes::setEscapeProcessing(bool enable) {
  m_attrs->__set_doEscapeProcessing(enable);
}
