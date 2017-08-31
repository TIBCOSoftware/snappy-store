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
 * StatementAttributes.h
 *
 * Specifies various attributes for the statement that can alter the behaviour
 * of a statement execution.
 */

#ifndef STATEMENTATTRIBUTES_H_
#define STATEMENTATTRIBUTES_H_

#include "Types.h"

namespace io {
namespace snappydata {
namespace client {

  class StatementAttributes {
  public:
    StatementAttributes();
    StatementAttributes(const StatementAttributes& attrs);
    ~StatementAttributes() {
    }

    static StatementAttributes EMPTY;

    ResultSetType getResultSetType() const noexcept {
      return static_cast<ResultSetType>(
          m_attrs->__isset.resultSetType ? m_attrs->resultSetType :
              thrift::snappydataConstants::DEFAULT_RESULTSET_TYPE);
    }

    bool isUpdatable() const noexcept {
      return m_attrs->__isset.updatable && m_attrs->updatable;
    }

    uint32_t getBatchSize() const noexcept {
      return m_attrs->__isset.batchSize ? m_attrs->batchSize
          : thrift::snappydataConstants::DEFAULT_RESULTSET_BATCHSIZE;
    }

    const thrift::StatementAttrs& getAttrs() const {
      return *m_attrs;
    }

    ResultSetHoldability getResultSetHoldability() const noexcept;
    bool isFetchDirectionReverse() const noexcept;
    uint32_t getLobChunkSize() const noexcept;
    uint32_t getMaxRows() const noexcept;
    uint32_t getMaxFieldSize() const noexcept;
    uint32_t getTimeout() const noexcept;
    const std::string& getCursorName() const noexcept;
    bool isPoolable() const noexcept;
    bool hasEscapeProcessing() const noexcept;

    void setResultSetType(ResultSetType rsType);
    void setResultSetHoldability(ResultSetHoldability holdability);
    void setUpdatable(bool updatable);
    void setBatchSize(uint32_t batchSize);
    void setFetchDirectionReverse(bool fetchDirection);
    void setLobChunkSize(uint32_t size);
    void setMaxRows(uint32_t num);
    void setMaxFieldSize(uint32_t size);
    void setTimeout(uint32_t timeout);
    void setCursorName(const std::string& name);
    void setPoolable(bool poolable);
    void setEscapeProcessing(bool enable);

  private:
    std::shared_ptr<thrift::StatementAttrs> m_attrs;
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* STATEMENTATTRIBUTES_H_ */
