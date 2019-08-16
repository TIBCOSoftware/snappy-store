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
 * PreparedStatement.cpp
 */

#include "PreparedStatement.h"

#include "Parameters.h"
#include "ParametersBatch.h"
#include "ResultSet.h"
#include "Result.h"

#include "impl/ClientService.h"
#include "impl/InternalUtils.h"

using namespace io::snappydata;
using namespace io::snappydata::client;

PreparedStatement::PreparedStatement(
    const std::shared_ptr<ClientService>& service,
    const StatementAttributes& attrs) :
    m_service(service), m_attrs(attrs), m_prepResult(), m_warnings(),
    m_cursorId(thrift::snappydataConstants::INVALID_ID) {
}

PreparedStatement::PreparedStatement(
    const std::shared_ptr<ClientService>& service,
    const StatementAttributes& attrs, const thrift::PrepareResult& prepResult) :
    m_service(service), m_attrs(attrs), m_prepResult(prepResult), m_warnings(),
    m_cursorId(thrift::snappydataConstants::INVALID_ID) {
}

void PreparedStatement::registerOutParameter(const int32_t parameterIndex,
    const SQLType type) {
  if (m_outParams == NULL) {
    m_outParams.reset(new std::map<int32_t, thrift::OutputParameter>());
  }
  thrift::OutputParameter outParam;
  outParam.__set_type(static_cast<thrift::SnappyType::type>(type));
  m_outParams->operator[](parameterIndex) = outParam;
}

void PreparedStatement::registerOutParameter(const int32_t parameterIndex,
    const SQLType type, const int32_t scale) {
  if (m_outParams == NULL) {
    m_outParams.reset(new std::map<int32_t, thrift::OutputParameter>());
  }
  thrift::OutputParameter outParam;
  outParam.__set_type(static_cast<thrift::SnappyType::type>(type));
  outParam.__set_scale(scale);
  m_outParams->operator[](parameterIndex) = outParam;
}

bool PreparedStatement::unregisterOutParameter(const int32_t parameterIndex) {
  if (m_outParams != NULL) {
    return (m_outParams->erase(parameterIndex) > 0);
  } else {
    return false;
  }
}

std::unique_ptr<Result> PreparedStatement::execute(const Parameters& params) {
  std::unique_ptr<Result> result(new Result(m_service, m_attrs));

  m_service->executePrepared(result->m_result, m_prepResult, params,
      m_outParams == NULL ? EMPTY_OUT_PARAMS : *m_outParams,
      m_attrs.getAttrs());
  if (result->m_result.__isset.resultSet) {
    m_cursorId = result->m_result.resultSet.cursorId;
  } else {
    m_cursorId = thrift::snappydataConstants::INVALID_ID;
  }
  if (result->hasWarnings()) {
    // set back in PreparedStatement
    m_warnings = result->getWarnings();
  } else {
    m_warnings.reset();
  }
  return result;
}

int32_t PreparedStatement::executeUpdate(const Parameters& params) {
  thrift::UpdateResult result;
  m_service->executePreparedUpdate(result, m_prepResult, params,
      m_attrs.getAttrs());
  m_cursorId = thrift::snappydataConstants::INVALID_ID;
  if (result.__isset.warnings) {
    // set back in PreparedStatement
    m_warnings.reset(new GET_SQLWARNING(result.warnings));
  } else {
    m_warnings.reset();
  }
  if (result.__isset.updateCount) {
    return result.updateCount;
  } else {
    return -1;
  }
}

std::unique_ptr<ResultSet> PreparedStatement::executeQuery(
    const Parameters& params) {
  int32_t batchSize;
  bool updatable, scrollable;
  Result::getResultSetArgs(m_attrs, batchSize, updatable, scrollable);

  thrift::RowSet* rs = new thrift::RowSet();
  // resize the vectors to some reasonable values
  rs->rows.reserve(4);
  rs->metadata.reserve(10);
  std::unique_ptr<ResultSet> resultSet(
      new ResultSet(rs, m_service, m_attrs, batchSize, updatable,
          scrollable));
  m_service->executePreparedQuery(*rs, m_prepResult, params,
      m_attrs.getAttrs());
  m_cursorId = rs->cursorId;
  if (resultSet->hasWarnings()) {
    // set back in PreparedStatement
    m_warnings = resultSet->getWarnings();
  } else {
    m_warnings.reset();
  }
  return resultSet;
}

std::vector<int32_t> PreparedStatement::executeBatch(
    const ParametersBatch& paramsBatch) {
  thrift::UpdateResult result;
  m_service->executePreparedBatch(result, m_prepResult, paramsBatch.m_batch,
      m_attrs.getAttrs());
  m_cursorId = thrift::snappydataConstants::INVALID_ID;
  if (result.__isset.warnings) {
    // set back in PreparedStatement
    m_warnings.reset(new GET_SQLWARNING(result.warnings));
  } else {
    m_warnings.reset();
  }
  return std::move(result.batchUpdateCounts);
}

std::unique_ptr<ResultSet> PreparedStatement::getNextResults(
    const NextResultSetBehaviour behaviour) {
  if (m_cursorId != thrift::snappydataConstants::INVALID_ID) {
    int32_t batchSize;
    bool updatable, scrollable;
    Result::getResultSetArgs(m_attrs, batchSize, updatable, scrollable);

    thrift::RowSet* rs = new thrift::RowSet();
    std::unique_ptr<ResultSet> resultSet(
        new ResultSet(rs, m_service, m_attrs, batchSize, updatable,
            scrollable));

    m_service->getNextResultSet(*rs, m_cursorId,
        static_cast<int8_t>(behaviour));
    m_cursorId = rs->cursorId;
    // check for empty ResultSet
    if (rs->metadata.empty()) {
      return std::unique_ptr<ResultSet>(nullptr);
    } else {
      if (resultSet->hasWarnings()) {
        // set back in PreparedStatement
        m_warnings = resultSet->getWarnings();
      } else {
        m_warnings.reset();
      }
      return resultSet;
    }
  } else {
    return std::unique_ptr<ResultSet>(nullptr);
  }
}

std::unique_ptr<SQLWarning> PreparedStatement::getWarnings() {
  if (m_warnings != NULL) {
    return std::unique_ptr<SQLWarning>(new SQLWarning(*m_warnings));
  } else if (m_prepResult.__isset.warnings) {
    return std::unique_ptr<SQLWarning>(new GET_SQLWARNING(
        m_prepResult.warnings));
  } else {
    return std::unique_ptr<SQLWarning>();
  }
}

ParameterDescriptor PreparedStatement::getParameterDescriptor(
    const uint32_t parameterIndex) {
  // Check that parameterIndex is in range.
  if (parameterIndex >= 1
      && parameterIndex <= m_prepResult.parameterMetaData.size()) {
    return ParameterDescriptor(
        m_prepResult.parameterMetaData[parameterIndex - 1], parameterIndex);
  } else {
    throw GET_SQLEXCEPTION2(SQLStateMessage::INVALID_DESCRIPTOR_INDEX_MSG,
        parameterIndex, m_prepResult.parameterMetaData.size(),
        "parameter number in prepared statement");
  }
}

ColumnDescriptor PreparedStatement::getColumnDescriptor(
    const uint32_t columnIndex) {
  return ResultSet::getColumnDescriptor(m_prepResult.resultSetMetaData,
      columnIndex, "column number in prepared statement");
}

bool PreparedStatement::cancel() {
  const int64_t statementId = m_prepResult.statementId;
  if (statementId != thrift::snappydataConstants::INVALID_ID) {
    m_service->cancelStatement(statementId);
    return true;
  }
  return false;
}

void PreparedStatement::close() {
  if (m_prepResult.statementId != thrift::snappydataConstants::INVALID_ID) {
    m_service->closeStatement(m_prepResult.statementId);
  }
  m_prepResult.statementId = thrift::snappydataConstants::INVALID_ID;
  m_cursorId = thrift::snappydataConstants::INVALID_ID;
}

PreparedStatement::~PreparedStatement() {
  // destructor should *never* throw an exception
  // TODO: close from destructor should use bulkClose if valid handle
  try {
    close();
  } catch (const SQLException& sqle) {
    Utils::handleExceptionInDestructor("prepared statement", sqle);
  } catch (const std::exception& stde) {
    Utils::handleExceptionInDestructor("prepared statement", stde);
  } catch (...) {
    Utils::handleExceptionInDestructor("prepared statement");
  }
}
