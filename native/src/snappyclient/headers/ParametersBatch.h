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
 * ParametersBatch.h
 */

#ifndef PARAMETERSBATCH_H_
#define PARAMETERSBATCH_H_

#include <vector>

#include "Types.h"
#include "Parameters.h"

namespace io {
namespace snappydata {
namespace client {

  class ParametersBatch final {
  private:
    std::vector<thrift::Row> m_batch;
    const size_t m_numParams;

    friend class Connection;
    friend class PreparedStatement;

  public:
    ParametersBatch();
    ParametersBatch(const PreparedStatement& pstmt);

    /**
     * Get the number of parameters in each row of the batch
     * (i.e. number of columns).
     */
    inline size_t numParams() const noexcept {
      return m_numParams;
    }

    /**
     * Like vector.reserve, increase the capacity of this batch to a value
     * that's greater or equal to newCapacity.
     */
    void reserve(size_t newCapacity);

    /**
     * Move the given Parameters appending to the end of this batch.
     * Like the name suggests, the passed parameters are henceforth "owned"
     * by this ParametersBatch and are no longer usable from outside.
     */
    void moveParameters(Parameters& params);

    /**
     * Get the number of rows in this batch.
     */
    inline size_t batchSize() const noexcept {
      return m_batch.size();
    }

    /**
     * Get the parameters at the given index of the batch. If the index is out
     * of range, then it throws a std::out_of_range exception like std::vector.
     *
     * The returned set of parameters is created on the fly, so callers can
     * use std::move explicitly on the result to avoid a copy if required.
     */
    Parameters parametersAt(size_t index);

    ~ParametersBatch() {
    }

  private:
    ParametersBatch(const ParametersBatch&) = delete; // no copy constructor
    ParametersBatch operator=(const ParametersBatch&) = delete; // no assignment
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* PARAMETERSBATCH_H_ */
