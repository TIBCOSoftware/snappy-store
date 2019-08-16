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
 * PreparedStatement.h
 */

#ifndef PREPAREDSTATEMENT_H_
#define PREPAREDSTATEMENT_H_

#include "Types.h"
#include "ParameterDescriptor.h"
#include "StatementAttributes.h"
#include "ResultSet.h"

#include <memory>

using namespace io::snappydata::client::impl;

namespace io {
namespace snappydata {
namespace client {

  class PreparedStatement {
  private:
    std::shared_ptr<ClientService> m_service;
    StatementAttributes m_attrs;
    thrift::PrepareResult m_prepResult;
    std::unique_ptr<SQLWarning> m_warnings;
    std::unique_ptr<std::map<int32_t, thrift::OutputParameter> > m_outParams;
    // cursorId of last execution
    int64_t m_cursorId;

    friend class Connection;
    friend class Result;

    PreparedStatement(const std::shared_ptr<ClientService>& service,
        const StatementAttributes& attrs);

    PreparedStatement(const std::shared_ptr<ClientService>& service,
        const StatementAttributes& attrs,
        const thrift::PrepareResult& prepResult);

    // no copy constructor or assignment operator
    PreparedStatement(const PreparedStatement&) = delete;
    PreparedStatement operator=(const PreparedStatement&) = delete;

    inline ClientService& checkAndGetService() const {
      if (m_service != NULL) {
        return *m_service;
      } else {
        throw GET_SQLEXCEPTION2(SQLStateMessage::ALREADY_CLOSED_MSG);
      }
    }

  public:

    void registerOutParameter(const int32_t parameterIndex,
        const SQLType type);

    void registerOutParameter(const int32_t parameterIndex,
        const SQLType type, const int32_t scale);

    bool unregisterOutParameter(const int32_t parameterIndex);

    std::unique_ptr<Result> execute(const Parameters& params);

    int32_t executeUpdate(const Parameters& params);

    std::unique_ptr<ResultSet> executeQuery(const Parameters& params);

    std::vector<int32_t> executeBatch(const ParametersBatch& paramsBatch);

    std::unique_ptr<ResultSet> getNextResults(
        const NextResultSetBehaviour behaviour =
            NextResultSetBehaviour::CLOSE_ALL);

    int8_t getStatementType() const noexcept {
      return m_prepResult.statementType;
    }

    inline bool hasWarnings() const noexcept {
      return m_warnings != NULL || m_prepResult.__isset.warnings;
    }

    std::unique_ptr<SQLWarning> getWarnings();

    uint32_t getParameterCount() const noexcept {
      return static_cast<uint32_t>(m_prepResult.parameterMetaData.size());
    }

    ParameterDescriptor getParameterDescriptor(
        const uint32_t parameterIndex);

    uint32_t getColumnCount() const noexcept {
      return static_cast<uint32_t>(m_prepResult.resultSetMetaData.size());
    }

    ColumnDescriptor getColumnDescriptor(const uint32_t columnIndex);

    bool cancel();

    void close();

    ~PreparedStatement();
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* PREPAREDSTATEMENT_H_ */
