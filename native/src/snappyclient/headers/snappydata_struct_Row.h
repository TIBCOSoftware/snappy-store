/*
 * Changes for GemFireXD distributed data platform.
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
 * Portions Copyright (c) 2017-2021 TIBCO Software Inc. All rights reserved.
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

#ifndef SNAPPYDATA_STRUCT_ROW_H
#define SNAPPYDATA_STRUCT_ROW_H


#include "snappydata_struct_Decimal.h"
#include "snappydata_struct_BlobChunk.h"
#include "snappydata_struct_ClobChunk.h"
#include "snappydata_struct_ServiceMetaData.h"
#include "snappydata_struct_ServiceMetaDataArgs.h"
#include "snappydata_struct_OpenConnectionArgs.h"
#include "snappydata_struct_ConnectionProperties.h"
#include "snappydata_struct_HostAddress.h"
#include "snappydata_struct_SnappyExceptionData.h"
#include "snappydata_struct_StatementAttrs.h"
#include "snappydata_struct_ColumnValue.h"
#include "snappydata_struct_ColumnDescriptor.h"

#include "snappydata_types.h"

// removing dependency on boost for public headers
#ifndef BOOST_DYNAMIC_BITSET_FWD_HPP
#define BOOST_DYNAMIC_BITSET_FWD_HPP

#include <memory>

namespace boost {

  template <typename Block = unsigned long,
            typename Allocator = std::allocator<Block> >
  class dynamic_bitset;
}
#endif

namespace io { namespace snappydata { namespace thrift {

class Row {
protected:
  typedef boost::dynamic_bitset<unsigned long,
      std::allocator<unsigned long> > DynamicBitSet;

  std::vector<ColumnValue> m_values;
  bool m_updatable;
  // tracks the updated columns that will be applied back to SnappyData
  // server once the whole updated row is applied
  DynamicBitSet* m_changedColumns;

public:

  Row(const Row& other);
  Row& operator=(const Row& other);

  Row(): m_values(), m_updatable(false), m_changedColumns(nullptr) {
  }

  Row(const size_t initialCapacity) :
      m_values(), m_updatable(false), m_changedColumns(nullptr) {
    m_values.reserve(initialCapacity);
  }

  Row(Row&& other) : m_values(std::move(other.m_values)), m_updatable(
      other.m_updatable), m_changedColumns(other.m_changedColumns) {
    other.m_updatable = false;
    other.m_changedColumns = nullptr;
  }

  Row& operator=(Row&& other) {
    m_values.swap(other.m_values);
    std::swap(m_updatable, other.m_updatable);
    std::swap(m_changedColumns, other.m_changedColumns);
    return *this;
  }

  virtual ~Row() noexcept;

  void clearChangedColumns();

  void clear();

  void resize(const size_t newSize) {
    m_values.resize(newSize);
  }

  void swap(Row& other);

  bool operator == (const Row & rhs) const {
    return m_updatable == rhs.m_updatable && m_values == rhs.m_values;
  }

  bool operator != (const Row &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const Row & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  virtual void printTo(std::ostream& out) const;
};

void swap(Row &a, Row &b);

std::ostream& operator<<(std::ostream& out, const Row& obj);

}}} // namespace

#endif
