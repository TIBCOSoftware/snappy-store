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
 * Result.h
 *
 * This class encapsulates the result of execution of a statement or prepared
 * statement.
 */

#ifndef RESULT_H_
#define RESULT_H_

#include "Types.h"
#include "PreparedStatement.h"
#include "ResultSet.h"

#include <memory>

using namespace io::snappydata::client::impl;

namespace io {
namespace snappydata {
namespace client {

  namespace impl {
    class ClientService;
  }

  class Result final {
  private:
    std::shared_ptr<ClientService> m_service;
    StatementAttributes m_attrs;
    thrift::StatementResult m_result;
    std::shared_ptr<ResultSet> m_resultSet;
    std::shared_ptr<ResultSet> m_generatedKeys;

    Result(const std::shared_ptr<ClientService>& service,
        const StatementAttributes& attrs);

    Result(const Result&) = delete; // no copy constructor
    Result operator=(const Result&) = delete; // no assignment operator

    friend class Connection;
    friend class PreparedStatement;

    static void getResultSetArgs(const StatementAttributes& attrs,
        int32_t& batchSize, bool& updatable, bool& scrollable) noexcept;

    std::shared_ptr<ResultSet> newResultSet(thrift::RowSet& rowSet);

  public:
    ~Result();

    std::shared_ptr<ResultSet> getResultSet();

    int32_t getUpdateCount() const noexcept;

    inline bool hasBatchUpdateCounts() const noexcept {
      return m_result.__isset.batchUpdateCounts
          && m_result.batchUpdateCounts.size() > 0;
    }

    const std::vector<int32_t>& getBatchUpdateCounts() const noexcept;

    const std::map<int32_t, thrift::ColumnValue>& getOutputParameters()
        const noexcept;

    std::shared_ptr<ResultSet> getGeneratedKeys();

    const StatementAttributes& getAttributes() const noexcept {
      return m_attrs;
    }

    inline bool hasWarnings() const noexcept {
      return m_result.__isset.warnings || (m_result.__isset.resultSet &&
          m_result.resultSet.__isset.warnings);
    }

    std::unique_ptr<SQLWarning> getWarnings() const;

    std::unique_ptr<PreparedStatement> getPreparedStatement() const;
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* RESULT_H_ */
