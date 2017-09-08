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

#ifndef RESULTSET_H_
#define RESULTSET_H_

#include "Types.h"
#include "ColumnDescriptor.h"
#include "UpdatableRow.h"
#include "StatementAttributes.h"

#include <memory>

using namespace io::snappydata::client::impl;

namespace io {
namespace snappydata {
namespace client {

  class ResultSet {
  private:
    thrift::RowSet* m_rows;
    std::shared_ptr<ClientService> m_service;
    const StatementAttributes m_attrs;
    const int32_t m_batchSize;
    const bool m_updatable;
    const bool m_scrollable;
    const bool m_isOwner;
    std::vector<thrift::ColumnDescriptor>* m_descriptors;
    mutable std::map<std::string, uint32_t>* m_columnPositionMap;

    ResultSet(thrift::RowSet* rows,
        const std::shared_ptr<ClientService>& service,
        const StatementAttributes& attrs = StatementAttributes::EMPTY,
        const int32_t batchSize = -1, bool updatable = false,
        bool scrollable = false, bool isOwner = true);

    // no assignment operator or copy constructor
    ResultSet(const ResultSet&);
    ResultSet operator=(const ResultSet&);

    friend class Connection;
    friend class PreparedStatement;
    friend class Result;

    inline void checkOpen(const char* operation) const {
      if (m_rows) {
        return;
      } else {
        throw GET_SQLEXCEPTION2(
            SQLStateMessage::LANG_RESULT_SET_NOT_OPEN_MSG, operation);
      }
    }

    inline void checkScrollable(const char* operation) const {
      if (m_scrollable) {
        return;
      } else {
        throw GET_SQLEXCEPTION2(
            SQLStateMessage::CURSOR_MUST_BE_SCROLLABLE_MSG, operation);
      }
    }

    inline void copyDescriptors() {
      if (m_descriptors == NULL && m_rows->metadata.size() > 0) {
        m_descriptors = new std::vector<thrift::ColumnDescriptor>(
            m_rows->metadata);
      }
    }

    static ColumnDescriptor getColumnDescriptor(
        std::vector<thrift::ColumnDescriptor>& descriptors,
        const uint32_t columnIndex, const char* operation);

    bool moveToNextRowSet(int32_t offset);
    bool moveToRowSet(int32_t offset, int32_t batchSize,
        bool offsetIsAbsolute);

    void insertRow(UpdatableRow* row, int32_t rowIndex);
    void updateRow(UpdatableRow* row, int32_t rowIndex);
    void deleteRow(UpdatableRow* row, int32_t rowIndex);

    void cleanupRS();

    template<typename TRow, typename TRowP, bool updatable>
    class Itr {
    private:
      ResultSet* m_resultSet;
      thrift::RowSet* m_rows;
      // using STL iterator is bit hard (and slightly expensive) here
      // due to random access nature of scrollable Itr that can move it past
      // the current vector where we have to get new set of rows
      TRow* m_currentRow;
      TRow* m_endBatch;
      TRow* m_insertRow;
      thrift::CursorUpdateOperation::type m_operation;

      static constexpr thrift::CursorUpdateOperation::type NO_OP =
          static_cast<thrift::CursorUpdateOperation::type>(0);

      void resetPositions() {
        size_t sz = m_rows->rows.size();
        if (sz > 0) {
          m_currentRow = new (&m_rows->rows[0]) TRow(updatable);
          m_endBatch = m_currentRow + sz;
        } else {
          m_currentRow = m_endBatch = NULL;
        }
      }

      void init(int32_t offset) {
        m_operation = NO_OP;
        const int32_t batchOffset = m_rows->offset;
        if (offset == 0 && batchOffset == 0) { // first handle the common case
          resetPositions();
        } else if (offset >= 0) {
          // offset from start of the resultset; check if in current batch
          size_t sz = m_rows->rows.size();
          if (offset >= batchOffset &&
              offset < static_cast<int32_t>(batchOffset + sz)) {
            m_currentRow = new (&m_rows->rows[0]) TRow(updatable);
            m_endBatch = m_currentRow + sz;
            m_currentRow += (offset - batchOffset);
          } else {
            if (m_resultSet->moveToRowSet(offset, m_resultSet->m_batchSize, true)
                && (sz = m_rows->rows.size()) > 0) {
              // check for cursor placed after last row
              if ((m_rows->flags &
                  thrift::snappydataConstants::ROWSET_AFTER_LAST) == 0) {
                m_currentRow = new (&m_rows->rows[0]) TRow(updatable);
                m_endBatch = m_currentRow + sz;
              } else { // after last
                m_currentRow = m_endBatch = NULL;
              }
            } else {
              // indicate end of iteration
              m_currentRow = m_endBatch = NULL;
            }
          }
        } else {
          // offset from end of the resultset; check if in current batch
          size_t sz = m_rows->rows.size();
          if ((m_rows->flags & thrift::snappydataConstants::
              ROWSET_LAST_BATCH) != 0 && (sz + offset) >= 0) {
            m_currentRow = new (&m_rows->rows[0]) TRow(updatable);
            m_endBatch = m_currentRow + sz;
            m_currentRow += (sz + offset);
          } else {
            if (m_resultSet->moveToRowSet(offset, m_resultSet->m_batchSize, true)
                && (sz = m_rows->rows.size()) > 0) {
              m_currentRow = new (&m_rows->rows[0]) TRow(updatable);
              m_endBatch = m_currentRow + sz;
              // check for cursor placed before first row
              if ((m_rows->flags &
                  thrift::snappydataConstants::ROWSET_BEFORE_FIRST) != 0) {
                m_currentRow = NULL;
              }
            } else {
              // indicate end of iteration
              m_currentRow = m_endBatch = NULL;
            }
          }
        }
      }

      template<typename T>
      void setFields(const T& other) {
        m_resultSet = other.m_resultSet;
        m_rows = other.m_rows;
        m_currentRow = other.m_currentRow;
        m_endBatch = other.m_endBatch;
        m_operation = other.m_operation;
      }

      inline SQLException noCurrentRow(const char* operation) const {
        if (m_rows == NULL) {
          return GET_SQLEXCEPTION2(
              SQLStateMessage::LANG_RESULT_SET_NOT_OPEN_MSG, operation);
        } else {
          return GET_SQLEXCEPTION2(SQLStateMessage::NO_CURRENT_ROW_MSG);
        }
      }

      inline void checkOnRow(const char* operation) const {
        if (m_currentRow != NULL) {
          return;
        } else {
          throw noCurrentRow(operation);
        }
      }

      inline void checkUpdatable(const char* operation) const {
        if (updatable) {
          checkOnRow(operation);
        } else {
          throw GET_SQLEXCEPTION2(
              SQLStateMessage::UPDATABLE_RESULTSET_API_DISALLOWED_MSG,
              operation);
        }
      }

    public:
      Itr() : m_resultSet(0), m_rows(0), m_currentRow(0), m_endBatch(0),
          m_insertRow(0), m_operation(NO_OP) {
      }

      Itr(ResultSet* rs, int32_t offset) : m_resultSet(rs), m_rows(rs->m_rows),
          m_insertRow(0) {
        // m_operation is initialized in init()
        init(offset);
      }

      Itr(const Itr& other) :
          m_resultSet(other.m_resultSet), m_rows(other.m_rows),
          m_currentRow(other.m_currentRow), m_endBatch(other.m_endBatch),
          m_operation(other.m_operation) {
        if (other.m_insertRow != NULL) {
          m_insertRow = new TRow(other.m_insertRow);
        } else {
          m_insertRow = NULL;
        }
      }

      Itr(Itr&& other) :
          m_resultSet(other.m_resultSet), m_rows(other.m_rows),
          m_currentRow(other.m_currentRow), m_endBatch(other.m_endBatch),
          m_insertRow(other.m_insertRow), m_operation(other.m_operation) {
        other.m_insertRow = NULL;
        other.clear();
      }

      ~Itr() {
        clearInsertRow();
      }

      Itr& operator=(const Itr& other) {
        setFields(other);
        return *this;
      }

      void initialize(ResultSet& resultSet, bool beforeFirst) {
        m_resultSet = &resultSet;
        m_rows = resultSet.m_rows;
        m_operation = NO_OP;
        resetPositions();
        if (beforeFirst) {
          m_currentRow = NULL;
        }
      }

      void clear() {
        m_resultSet = NULL;
        m_rows = NULL;
        m_currentRow = NULL;
        m_endBatch = NULL;
        m_operation = NO_OP;
        clearInsertRow();
      }

      inline bool isOnRow() const noexcept {
        return m_currentRow != NULL;
      }

      inline bool isBeforeFirst() const noexcept {
        return m_currentRow == NULL && m_endBatch != NULL;
      }

      /** Dereference the iterator. No NULL check like STL iterators. */
      TRow& operator*() const noexcept {
        return *m_currentRow;
      }

      TRowP operator->() const noexcept {
        return m_currentRow;
      }

      TRowP get() const noexcept {
        return m_currentRow;
      }

      bool operator==(const Itr& other) const noexcept {
        return m_currentRow == other.m_currentRow;
      }

      bool operator!=(const Itr& other) const noexcept {
        return m_currentRow != other.m_currentRow;
      }

      bool next() {
        m_operation = NO_OP;
        if (m_currentRow != NULL) {
          ++m_currentRow;
          // check if we have reached end
          if (m_currentRow >= m_endBatch) {
            if ((m_rows->flags &
                thrift::snappydataConstants::ROWSET_LAST_BATCH) == 0) {
              // go on to next set of rows
              size_t sz;
              if (m_resultSet->moveToNextRowSet(0)
                  && (sz = m_rows->rows.size()) > 0) {
                m_currentRow = new (&m_rows->rows[0]) TRow(updatable);
                m_endBatch = m_currentRow + sz;
              } else {
                // indicate end of iteration
                m_currentRow = m_endBatch = NULL;
                return false;
              }
            } else {
              // indicate end of iteration
              m_currentRow = m_endBatch = NULL;
              return false;
            }
          }
          return true;
        } else if (m_endBatch != NULL) {
          // before first, so move to the first row
          resetPositions();
          checkOnRow("next");
          return true;
        } else {
          throw noCurrentRow("next");
        }
      }

      Itr& operator++() {
        next();
        return *this;
      }

      bool previous() {
        size_t sz;
        m_operation = NO_OP;
        // check if cursor is placed after last
        if (m_currentRow == NULL) {
          if (m_endBatch == NULL) {
            const int32_t batchSize = m_resultSet->m_batchSize;
            // check if there is already a fetched RowSet which is last one
            if (((m_rows != NULL && (m_rows->flags &
                thrift::snappydataConstants::ROWSET_LAST_BATCH) != 0) ||
                // position to the (end - batchSize) since we only fetch in
                // forward direction from server
                m_resultSet->moveToRowSet(-batchSize, batchSize, true)) &&
                (sz = m_rows->rows.size()) > 0) {
              m_endBatch = new (&m_rows->rows[0] + sz) TRow(updatable);
              m_currentRow = m_endBatch - 1;
              return true;
            }
          } else {
            // placed at the start
            checkOnRow("previous");
          }
          return false;
        }
        // check if we have reached start
        if (m_currentRow <= &m_rows->rows[0]) {
          if (m_rows->offset > 0) {
            // go on to previous set of rows
            const int32_t batchSize = m_resultSet->m_batchSize;
            if (m_resultSet->moveToRowSet(-batchSize, batchSize, false)
                && (sz = m_rows->rows.size()) > 0) {
              m_currentRow = m_endBatch = new (
                  &m_rows->rows[0] + sz) TRow(updatable);
            } else {
              // indicate that reached before first
              m_currentRow = NULL;
              return false;
            }
          } else {
            // indicate that reached before first
            m_currentRow = NULL;
            return false;
          }
        }
        --m_currentRow;
        return true;
      }

      Itr& operator--() {
        previous();
        return *this;
      }

      Itr& operator+=(uint32_t n) {
        m_operation = NO_OP;
        if (m_currentRow == NULL) {
          if (m_endBatch == NULL || n == 0) {
            throw noCurrentRow("nextN");
          } else {
            // before first, so move to first and calculate from there
            resetPositions();
            checkOnRow("nextN");
            n -= 1;
          }
        }
        thrift::Row* row = (m_currentRow + n);
        // check if we have reached end
        if (row >= m_endBatch) {
          if ((m_rows->flags
              & thrift::snappydataConstants::ROWSET_LAST_BATCH) == 0) {
            // jump to an appropriate relative offset so that +n is at start
            // of the new batch fetched from server
            int32_t offset = static_cast<int32_t>(
                n - (m_endBatch - m_currentRow));
            // move to the required batch offset
            size_t sz;
            if (m_resultSet->moveToRowSet(offset, m_resultSet->m_batchSize,
                false) && (sz = m_rows->rows.size()) > 0) {
              m_currentRow = new (&m_rows->rows[0]) TRow(updatable);
              m_endBatch = m_currentRow + sz;
            } else {
              // indicate end of iteration
              m_currentRow = m_endBatch = NULL;
              return *this;
            }
          } else {
            // indicate end of iteration
            m_currentRow = m_endBatch = NULL;
            return *this;
          }
        } else {
          m_currentRow = new (row) TRow(updatable);
        }
        return *this;
      }

      Itr& operator-=(const uint32_t n) {
        size_t sz;
        m_operation = NO_OP;
        // check if cursor is placed at the end
        if (m_currentRow == NULL) {
          if (m_endBatch == NULL) {
            const int32_t batchSize = m_resultSet->m_batchSize;
            // position to the (end - (batchSize + n)) since we only fetch in
            // forward direction from server
            if (m_resultSet->moveToRowSet(-batchSize - n, batchSize, true)
                && (sz = m_rows->rows.size()) > 0) {
              m_currentRow = m_endBatch = new (
                  &m_rows->rows[0] + sz) TRow(updatable);
            }
          } else {
            // placed at the start
            checkOnRow("previousN");
          }
          return *this;
        }
        // check if we can find the position in current batch
        thrift::Row* start = &m_rows->rows[0];
        TRow* row = (m_currentRow - n);
        if (row < start) {
          int32_t offset = m_rows->offset;
          int32_t requiredOffset = (start - row); // minimum required offset
          if (offset >= requiredOffset) {
            int32_t batchSize = m_resultSet->m_batchSize;
            // jump to an appropriate relative offset so that -n is at end
            // of the new batch fetched from server
            if ((requiredOffset += batchSize) > offset) {
              // batchSize lies beyond the start, so reduce to max possible
              // (batchSize added to minRequiredOffset above, hence -= below)
              batchSize -= (requiredOffset - offset - 1);
              requiredOffset = batchSize;
            }
            // move to the required batch offset
            if (m_resultSet->moveToRowSet(-requiredOffset, batchSize, false)
                && (sz = m_rows->rows.size()) > 0) {
              row = &m_rows->rows[0];
              m_endBatch = (row + sz);
            } else {
              // indicate that reached before first
              m_currentRow = NULL;
              return *this;
            }
          } else {
            // indicate that reached before first
            m_currentRow = NULL;
            return *this;
          }
        }
        m_currentRow = row;
        return *this;
      }

      TRow* getInsertRow() {
        checkUpdatable("getInsertRow");

        if (m_insertRow != NULL) {
          return m_insertRow;
        } else {
          return (m_insertRow = new TRow());
        }
      }

      void clearInsertRow() {
        if (m_insertRow != NULL) {
          delete m_insertRow;
          m_insertRow = NULL;
        }
      }

      void insertRow() {
        checkUpdatable("insertRow");

        // TODO: handle SCROLL_SENSITIVE for insertRow/updateRow/deleteRow
        // but first need to add that to server-side

        const int32_t position = static_cast<int32_t>(
            (thrift::Row*)m_currentRow - &m_rows->rows[0]);
        m_resultSet->insertRow(m_insertRow, position);
        m_operation = thrift::CursorUpdateOperation::INSERT_OP;
      }

      void updateRow() {
        checkUpdatable("updateRow");

        const int32_t position = static_cast<int32_t>(
            (thrift::Row*)m_currentRow - &m_rows->rows[0]);
        m_resultSet->updateRow(m_currentRow, position);
        m_operation = thrift::CursorUpdateOperation::UPDATE_OP;
      }

      void deleteRow() {
        checkUpdatable("deleteRow");

        const int32_t position = static_cast<int32_t>(
            (thrift::Row*)m_currentRow - &m_rows->rows[0]);
        m_resultSet->deleteRow(m_currentRow, position);
        m_operation = thrift::CursorUpdateOperation::DELETE_OP;
      }

      bool rowInserted() const {
        return m_operation == thrift::CursorUpdateOperation::INSERT_OP;
      }
      bool rowUpdated() const {
        return m_operation == thrift::CursorUpdateOperation::UPDATE_OP;
      }
      bool rowDeleted() const {
        return m_operation == thrift::CursorUpdateOperation::DELETE_OP;
      }
    };

  public:
    typedef Itr<Row, const Row*, false> const_iterator;
    typedef Itr<UpdatableRow, UpdatableRow*, true> iterator;

    const_iterator cbegin(int32_t offset = 0) const;

    inline const_iterator begin(int32_t offset = 0) const {
      return cbegin(offset);
    }

    iterator begin(int32_t offset = 0);

    const_iterator crbegin() const {
      return cbegin(-1);
    }

    inline const_iterator rbegin() const {
      return cbegin(-1);
    }

    iterator rbegin() {
      return begin(-1);
    }


    inline const const_iterator& cend() const {
      return ITR_END_CONST;
    }

    inline const const_iterator& end() const {
      return ITR_END_CONST;
    }

    inline const iterator& end() {
      return ITR_END;
    }

    inline const const_iterator& crend() const {
      return ITR_END_CONST;
    }

    inline const const_iterator& rend() const {
      return ITR_END_CONST;
    }

    inline const iterator& rend() {
      return ITR_END;
    }

    inline bool isOpen() const noexcept {
      return m_rows != NULL;
    }

    inline bool isUpdatable() const noexcept {
      return m_updatable;
    }

    inline bool isScrollable() const noexcept {
      return m_scrollable;
    }

    uint32_t getColumnCount() const {
      checkOpen("getColumnCount");
      return static_cast<uint32_t>(m_descriptors == NULL
          ? m_rows->metadata.size() : m_descriptors->size());
    }

    int32_t getBatchSize() const noexcept {
      return m_batchSize;
    }

    size_t getCurrentBatchSize() const {
      checkOpen("getCurrentBatchSize");
      return m_rows->rows.size();
    }

    /**
     * Get the column position given its name.
     */
    uint32_t getColumnPosition(const std::string& name) const;

    ColumnDescriptor getColumnDescriptor(const uint32_t columnIndex);

    int32_t getRow() const;

    std::string getCursorName() const;

    const StatementAttributes& getAttributes() const noexcept {
      return m_attrs;
    }

    std::unique_ptr<ResultSet> getNextResults(
        const NextResultSetBehaviour behaviour =
            NextResultSetBehaviour::CLOSE_ALL);

    inline bool hasWarnings() const noexcept {
      const thrift::RowSet* rs = m_rows;
      return rs != NULL && rs->__isset.warnings;
    }

    std::unique_ptr<SQLWarning> getWarnings() const;

    std::unique_ptr<ResultSet> clone() const;

    bool cancelStatement();

    void close(bool closeStatement);

    virtual ~ResultSet();

  private:
    static const const_iterator ITR_END_CONST;
    static const iterator ITR_END;
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* RESULTSET_H_ */
