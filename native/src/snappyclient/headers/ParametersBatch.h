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

  class ParametersBatch {
  private:
    std::vector<thrift::Row> m_batch;
    const size_t m_numParams;

    friend class Connection;
    friend class PreparedStatement;

  public:
    ParametersBatch();
    ParametersBatch(const PreparedStatement& pstmt);

    /**
     * Allocates a new set of parameters, adds to end of this batch
     * and returns it. Caller must use the returned Parameters object
     * to add the required parameters.
     */
    Parameters& createParameters();

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
