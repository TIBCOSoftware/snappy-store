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
 * ColumnDescriptor.h
 */

#ifndef COLUMNDESCRIPTOR_H_
#define COLUMNDESCRIPTOR_H_

#include "ColumnDescriptorBase.h"

namespace io {
namespace snappydata {
namespace client {

  // TODO: SW: add move constructors for all C++ classes
  class ColumnDescriptor: public ColumnDescriptorBase {
  private:
    ColumnDescriptor(thrift::ColumnDescriptor& descriptor,
        const uint32_t columnIndex) :
        ColumnDescriptorBase(descriptor, columnIndex) {
    }

    friend class ResultSet;

  public:
    ~ColumnDescriptor() {
    }

    ColumnUpdatable getUpdatable() const noexcept {
      if (m_descriptor.updatable) {
        return ColumnUpdatable::UPDATABLE;
      } else if (m_descriptor.definitelyUpdatable) {
        return ColumnUpdatable::DEFINITELY_UPDATABLE;
      } else {
        return ColumnUpdatable::READ_ONLY;
      }
    }

    bool isAutoIncrement() const noexcept {
      return m_descriptor.autoIncrement;
    }

    bool isCaseSensitive() const noexcept;

    bool isSearchable() const noexcept {
      // we have no restrictions yet, so this is always true
      return true;
    }

    bool isCurrency() const noexcept;

    uint32_t getDisplaySize() const noexcept;

    std::string getLabel() const noexcept {
      if (m_descriptor.__isset.name) {
        return m_descriptor.name;
      } else {
        char buf[32];
        ::snprintf(buf, sizeof(buf), "Column%d", m_columnIndex);
        return buf;
      }
    }
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* COLUMNDESCRIPTOR_H_ */
