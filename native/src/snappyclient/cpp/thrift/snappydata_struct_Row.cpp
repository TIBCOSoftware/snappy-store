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

#include "impl/pch.h"

#include <iosfwd>

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include "snappydata_struct_Row.h"

#include <boost/dynamic_bitset.hpp>

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {

std::ostream& operator<<(std::ostream& out, const Row& obj) {
  obj.printTo(out);
  return out;
}

uint32_t Row::read(::apache::thrift::protocol::TProtocol* iprot) {
  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_values = false;

  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->m_values.clear();
            uint32_t _size221;
            ::apache::thrift::protocol::TType _etype224;
            xfer += iprot->readListBegin(_etype224, _size221);
            this->m_values.resize(_size221);
            uint32_t _i225;
            for (_i225 = 0; _i225 < _size221; ++_i225)
            {
              xfer += this->m_values[_i225].read(iprot);
            }
            xfer += iprot->readListEnd();
          }
          isset_values = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      default:
        xfer += iprot->skip(ftype);
        break;
    }
    xfer += iprot->readFieldEnd();
  }

  xfer += iprot->readStructEnd();

  if (!isset_values)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t Row::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("Row");

  xfer += oprot->writeFieldBegin("values", ::apache::thrift::protocol::T_LIST, 1);
  {
    xfer += oprot->writeListBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->m_values.size()));
    std::vector<ColumnValue> ::const_iterator _iter226;
    std::vector<ColumnValue> ::const_iterator _iter226end = m_values.cend();
    for (_iter226 = m_values.cbegin(); _iter226 != _iter226end; ++_iter226) {
      xfer += (*_iter226).write(oprot);
    }
    xfer += oprot->writeListEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

Row::Row(const Row& other) :
    m_values(other.m_values), m_updatable(other.m_updatable) {
  if (other.m_changedColumns) {
    m_changedColumns = new DynamicBitSet(*other.m_changedColumns);
  } else {
    m_changedColumns = nullptr;
  }
}

Row& Row::operator=(const Row& other) {
  m_values = other.m_values;
  m_updatable = other.m_updatable;
  if (m_changedColumns) {
    delete m_changedColumns;
  }
  if (other.m_changedColumns) {
    m_changedColumns = new DynamicBitSet(*other.m_changedColumns);
  } else {
    m_changedColumns = nullptr;
  }
  return *this;
}

Row::~Row() noexcept {
  if (m_changedColumns) {
    delete m_changedColumns;
    m_changedColumns = nullptr;
  }
}

void Row::clearChangedColumns() {
  if (m_changedColumns) {
    delete m_changedColumns;
    m_changedColumns = nullptr;
  }
}

void Row::clear() {
  m_values.clear();
  m_updatable = false;
  if (m_changedColumns) {
    m_changedColumns->clear();
  }
}

void Row::swap(Row& other) {
  using ::std::swap;
  swap(m_values, other.m_values);
  swap(m_updatable, other.m_updatable);
  swap(m_changedColumns, other.m_changedColumns);
}

void swap(Row &a, Row &b) {
  a.swap(b);
}

void Row::printTo(std::ostream& out) const {
  if (m_updatable) {
    out << "UpdatableRow(";
  } else {
    out << "Row(";
  }
  if (m_values.size() > 0) {
    auto start = m_values.cbegin();
    auto end = m_values.cend();
    out << *start;
    while (++start != end) {
      out << ',' << *start;
    }
  }
  out << ')';
}

}}} // namespace
