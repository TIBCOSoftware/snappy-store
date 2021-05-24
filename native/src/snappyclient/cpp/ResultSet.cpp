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
 * ResultSet.cpp
 */

#include "impl/pch.h"

#include "ResultSet.h"

#include "StatementAttributes.h"
#include "impl/ClientService.h"

using namespace io::snappydata;
using namespace io::snappydata::client;

const ResultSet::const_iterator ResultSet::ITR_END_CONST;
const ResultSet::iterator ResultSet::ITR_END;

ResultSet::ResultSet(thrift::RowSet* rows,
    const std::shared_ptr<ClientService>& service,
    const StatementAttributes& attrs, const int32_t batchSize, bool updatable,
    bool scrollable, bool isOwner) :
    m_rows(rows), m_service(service), m_attrs(attrs), m_batchSize(batchSize),
    m_updatable(updatable), m_scrollable(scrollable), m_isOwner(isOwner),
    m_descriptors(nullptr), m_columnPositionMap(nullptr) {
  initRowData(false);
}

void ResultSet::initRowData(bool clearData) {
  if (clearData) m_rowData.clear();

  auto &rows = m_rows->rows;
  auto numRows = rows.size();
  if (numRows > 0) {
    m_rowData.reserve(numRows);
    for (thrift::Row& row : rows) {
      m_rowData.push_back(std::move(row));
    }
  }
}

bool ResultSet::moveToNextRowSet(int32_t offset) {
  checkOpen("moveToNextRowSet");

  // copy descriptors prior to move since descriptors may not be
  // set by server in subsequent calls
  copyDescriptors();

  m_service->scrollCursor(*m_rows, m_rows->cursorId, offset, false, false,
      m_batchSize);
  initRowData(true);
  return !m_rowData.empty();
}

bool ResultSet::moveToRowSet(int32_t offset, int32_t batchSize,
    bool offsetIsAbsolute) {
  checkOpen("moveToRowSet");
  checkScrollable("moveToRowSet");

  // copy descriptors prior to move since descriptors may not be
  // set by server in subsequent calls
  copyDescriptors();

  m_service->scrollCursor(*m_rows, m_rows->cursorId, offset, offsetIsAbsolute,
      false, batchSize);
  initRowData(true);
  return !m_rowData.empty();
}

struct ClearUpdates final {
private:
  UpdatableRow* m_row;

public:
  ClearUpdates(UpdatableRow* row) : m_row(row) {
  }
  ~ClearUpdates() {
    m_row->clearChangedColumns();
  }
};

void ResultSet::insertRow(UpdatableRow* row, int32_t rowIndex) {
  checkOpen("insertRow");
  if (row && row->getChangedColumns()) {
    ClearUpdates clearRow(row);
    std::vector<int32_t> changedColumns(
        std::move(row->getChangedColumnsAsVector()));
    if (!changedColumns.empty()) {
      m_service->executeCursorUpdate(m_rows->cursorId,
          thrift::CursorUpdateOperation::INSERT_OP, *row, changedColumns,
          rowIndex);
      return;
    }
  }
  throw GET_SQLEXCEPTION2(
      SQLStateMessage::CURSOR_NOT_POSITIONED_ON_INSERT_ROW_MSG);
}

void ResultSet::updateRow(UpdatableRow* row, int32_t rowIndex) {
  checkOpen("updateRow");
  if (row && row->getChangedColumns()) {
    ClearUpdates clearRow(row);
    std::vector<int32_t> changedColumns(
        std::move(row->getChangedColumnsAsVector()));
    if (!changedColumns.empty()) {
      m_service->executeCursorUpdate(m_rows->cursorId,
          thrift::CursorUpdateOperation::UPDATE_OP, *row, changedColumns,
          rowIndex);
      return;
    }
  }
  throw GET_SQLEXCEPTION2(
      SQLStateMessage::INVALID_CURSOR_UPDATE_AT_CURRENT_POSITION_MSG);
}

void ResultSet::deleteRow(UpdatableRow* row, int32_t rowIndex) {
  checkOpen("deleteRow");
  ClearUpdates clearRow(row);
  m_service->executeBatchCursorUpdate(m_rows->cursorId,
      Utils::singleVector(thrift::CursorUpdateOperation::DELETE_OP),
      std::vector<thrift::Row>(), std::vector<std::vector<int32_t> >(),
      Utils::singleVector(rowIndex));
}

ResultSet::const_iterator ResultSet::cbegin(int32_t offset) const {
  checkOpen("cbegin");
  if (offset != 0) {
    checkScrollable("cbegin");
  }

  return const_iterator(const_cast<ResultSet*>(this), offset);
}

ResultSet::iterator ResultSet::begin(int32_t offset) {
  checkOpen("begin");
  if (offset != 0) {
    checkScrollable("begin");
  }

  return iterator(this, offset);
}

uint32_t ResultSet::getColumnPosition(const std::string& name) const {
  checkOpen("getColumnPosition");
  if (!m_columnPositionMap) {
    // populate the map on first call
    const std::vector<thrift::ColumnDescriptor>* descriptors =
        m_descriptors ? &m_rows->metadata : m_descriptors;
    m_columnPositionMap = new std::map<std::string, uint32_t>();
    uint32_t index = 1;
    for (std::vector<thrift::ColumnDescriptor>::const_iterator iter =
        descriptors->begin(); iter != descriptors->end(); ++iter) {
      const thrift::ColumnDescriptor& cd = *iter;
      if (cd.__isset.name) {
        m_columnPositionMap->operator [](cd.name) = index;
        // also push back the fully qualified name
        if (cd.__isset.fullTableName) {
          m_columnPositionMap->operator [](cd.fullTableName + "." + cd.name) =
              index;
        }
      }
      index++;
    }
  }
  std::map<std::string, uint32_t>::const_iterator findColumn =
      m_columnPositionMap->find(name);
  if (findColumn != m_columnPositionMap->end()) {
    return findColumn->second;
  } else {
    throw GET_SQLEXCEPTION2(SQLStateMessage::COLUMN_NOT_FOUND_MSG2,
        name.c_str());
  }
}

ColumnDescriptor ResultSet::getColumnDescriptor(
    std::vector<thrift::ColumnDescriptor>& descriptors,
    const uint32_t columnIndex, const char* operation) {
  // Check that columnIndex is in range.
  if (columnIndex >= 1 && columnIndex <= descriptors.size()) {
    // check if fullTableName, typeAndClassName are missing
    // which may be optimized out for consecutive same values
    thrift::ColumnDescriptor& cd = descriptors[columnIndex - 1];
    if (!cd.__isset.fullTableName) {
      // search for the table
      for (uint32_t i = columnIndex - 1; i > 0; i--) {
        thrift::ColumnDescriptor& cd2 = descriptors[i - 1];
        if (cd2.__isset.fullTableName) {
          cd.__set_fullTableName(cd2.fullTableName);
          break;
        }
      }
    }
    if (cd.type == thrift::SnappyType::JAVA_OBJECT) {
      if (!cd.__isset.udtTypeAndClassName) {
        // search for the UDT typeAndClassName
        for (uint32_t i = columnIndex - 1; i > 0; i--) {
          thrift::ColumnDescriptor& cd2 = descriptors[i - 1];
          if (cd2.__isset.udtTypeAndClassName) {
            cd.__set_udtTypeAndClassName(cd2.udtTypeAndClassName);
            break;
          }
        }
      }
    }
    return ColumnDescriptor(cd, columnIndex);
  } else {
    throw GET_SQLEXCEPTION2(SQLStateMessage::INVALID_DESCRIPTOR_INDEX_MSG,
        static_cast<int>(columnIndex), descriptors.size(), operation);
  }
}

ColumnDescriptor ResultSet::getColumnDescriptor(const uint32_t columnIndex) {
  checkOpen("getColumnDescriptor");
  return getColumnDescriptor(!m_descriptors ? m_rows->metadata : *m_descriptors,
      columnIndex, "column number in result set");
}

int32_t ResultSet::getRow() const {
  return m_rows ? m_rows->offset : 0;
}

std::string ResultSet::getCursorName() const {
  checkOpen("getCursorName");
  const thrift::RowSet* rs = m_rows;
  return rs && rs->__isset.cursorName ? rs->cursorName : "";
}

std::unique_ptr<ResultSet> ResultSet::getNextResults(
    const NextResultSetBehaviour behaviour) {
  checkOpen("getNextResults");

  if (m_rows->cursorId != thrift::snappydataConstants::INVALID_ID) {
    std::unique_ptr<thrift::RowSet> rs(new thrift::RowSet());
    m_service->getNextResultSet(*rs, m_rows->cursorId,
        static_cast<int8_t>(behaviour));
    std::unique_ptr<ResultSet> resultSet(
        new ResultSet(rs.get(), m_service, m_attrs, m_batchSize, m_updatable,
            m_scrollable));
    rs.release();
    // check for empty ResultSet
    if (resultSet->getColumnCount() == 0) {
      return std::unique_ptr<ResultSet>(nullptr);
    } else {
      return resultSet;
    }
  } else {
    // single forward-only result set that has been consumed
    return std::unique_ptr<ResultSet>(nullptr);
  }
}

std::unique_ptr<SQLWarning> ResultSet::getWarnings() const {
  checkOpen("getWarnings");

  if (m_rows->__isset.warnings) {
    return std::unique_ptr<SQLWarning>(new GET_SQLWARNING(m_rows->warnings));
  } else {
    return std::unique_ptr<SQLWarning>();
  }
}

std::unique_ptr<ResultSet> ResultSet::clone() const {
  checkOpen("clone");
  if (m_rows) {
    /* clone the contained object */
    std::unique_ptr<thrift::RowSet> rs(new thrift::RowSet(*m_rows));
    std::unique_ptr<ResultSet> resultSet(
        new ResultSet(rs.get(), m_service, m_attrs, m_batchSize, m_updatable,
            m_scrollable, true /* isOwner */));
    rs.release();
    if (m_descriptors) {
      resultSet->m_descriptors = new std::vector<thrift::ColumnDescriptor>(
          *m_descriptors);
    }
    return resultSet;
  } else {
    return std::unique_ptr<ResultSet>();
  }
}

void ResultSet::cleanupRS() {
  if (m_descriptors) {
    delete m_descriptors;
    m_descriptors = nullptr;
  }
  if (m_columnPositionMap) {
    delete m_columnPositionMap;
    m_columnPositionMap = nullptr;
  }
}

bool ResultSet::cancelStatement() {
  if (m_rows) {
    const auto statementId = m_rows->statementId;
    if (statementId != thrift::snappydataConstants::INVALID_ID) {
      m_service->cancelStatement(statementId);
      return true;
    }
  }
  return false;
}

void ResultSet::close(bool closeStatement) {
  if (m_rows && m_rows->cursorId != thrift::snappydataConstants::INVALID_ID) {
    // need to make the server call only if this is not the last batch
    // or a scrollable cursor with multiple batches, otherwise server
    // would have already closed the ResultSet
    if ((m_rows->flags &
        thrift::snappydataConstants::ROWSET_LAST_BATCH) == 0 || m_scrollable) {
      m_service->closeResultSet(m_rows->cursorId);
    }
    if (closeStatement) {
      const auto statementId = m_rows->statementId;
      if (statementId != thrift::snappydataConstants::INVALID_ID) {
        m_service->closeStatement(statementId);
      }
    }
  }
  if (m_isOwner && m_rows) {
    delete m_rows;
  }
  m_rows = nullptr;
  cleanupRS();
}

ResultSet::~ResultSet() {
  // destructor should *never* throw an exception
  // TODO: close from destructor should use bulkClose if valid handle
  Utils::handleExceptionsInDestructor("result set", [&]() {
    close(false);
  });
}
