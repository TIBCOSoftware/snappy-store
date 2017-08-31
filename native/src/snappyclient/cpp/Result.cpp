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
 * Result.cpp
 */

#include "Result.h"

#include "ResultSet.h"
#include "Row.h"
#include "PreparedStatement.h"
#include "StatementAttributes.h"

#include "impl/ClientService.h"

using namespace io::snappydata;
using namespace io::snappydata::client;

Result::Result(const std::shared_ptr<ClientService>& service,
    const StatementAttributes& attrs) :
    m_service(service), m_attrs(attrs), m_result() {
}

Result::~Result() {
}

void Result::getResultSetArgs(const StatementAttributes& attrs,
    int32_t& batchSize, bool& updatable, bool& scrollable) noexcept {
  batchSize = attrs.getBatchSize();
  updatable = attrs.isUpdatable();
  scrollable = (attrs.getResultSetType() != ResultSetType::FORWARD_ONLY);
}

ResultSet* Result::newResultSet(thrift::RowSet& rowSet) {
  int32_t batchSize;
  bool updatable, scrollable;
  getResultSetArgs(m_attrs, batchSize, updatable, scrollable);
  // the RowSet data will continue to be owned by this object
  return new ResultSet(&rowSet, m_service, m_attrs, batchSize, updatable,
      scrollable, false /* isOwner */);
}

std::unique_ptr<ResultSet> Result::getResultSet() {
  if (m_result.__isset.resultSet) {
    return std::unique_ptr<ResultSet>(newResultSet(m_result.resultSet));
  } else {
    return std::unique_ptr<ResultSet>();
  }
}

int32_t Result::getUpdateCount() const noexcept {
  return (m_result.__isset.updateCount ? m_result.updateCount : -1);
}

const std::vector<int32_t>& Result::getBatchUpdateCounts() const noexcept {
  return m_result.batchUpdateCounts;
}

const std::map<int32_t, thrift::ColumnValue>& Result::getOutputParameters()
    const noexcept {
  return m_result.procedureOutParams;
}

std::unique_ptr<ResultSet> Result::getGeneratedKeys() {
  if (m_result.__isset.generatedKeys) {
    return std::unique_ptr<ResultSet>(newResultSet(m_result.generatedKeys));
  } else {
    return std::unique_ptr<ResultSet>();
  }
}

std::unique_ptr<SQLWarning> Result::getWarnings() const {
  if (m_result.__isset.warnings) {
    return std::unique_ptr<SQLWarning>(new GET_SQLWARNING(m_result.warnings));
  } else if (m_result.__isset.resultSet
      && m_result.resultSet.__isset.warnings) {
    return std::unique_ptr<SQLWarning>(
        new GET_SQLWARNING(m_result.resultSet.warnings));
  } else {
    return std::unique_ptr<SQLWarning>();
  }
}

std::unique_ptr<PreparedStatement> Result::getPreparedStatement() const {
  if (m_result.__isset.preparedResult) {
    // deliberately making a copy of preparedResult to transfer full
    // ownership to that
    return std::unique_ptr<PreparedStatement>(
        new PreparedStatement(m_service, m_attrs, m_result.preparedResult));
  } else {
    return std::unique_ptr<PreparedStatement>();
  }
}
