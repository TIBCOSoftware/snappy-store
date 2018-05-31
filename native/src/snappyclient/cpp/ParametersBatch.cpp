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
 * ParametersBatch.cpp
 */

#include "ParametersBatch.h"
#include "PreparedStatement.h"

using namespace io::snappydata::client;

ParametersBatch::ParametersBatch() : m_batch(), m_numParams(0) {
}

ParametersBatch::ParametersBatch(const PreparedStatement& pstmt) :
    m_batch(), m_numParams(pstmt.getParameterCount()) {
}

Parameters& ParametersBatch::createParameters() {
  const size_t currentSize = m_batch.size();
  m_batch.resize(currentSize + 1);
  thrift::Row& trow = m_batch[currentSize];
  if (m_numParams > 0) {
    trow.resize(m_numParams);
  }
  return *(new (&trow) Parameters(true));
}
