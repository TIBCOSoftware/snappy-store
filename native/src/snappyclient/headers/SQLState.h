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

#ifndef SQLSTATE_H_
#define SQLSTATE_H_

#include <string>

namespace io {
namespace snappydata {
namespace client {

  enum class ExceptionSeverity {
    /*
     * Use NO_APPLICABLE_SEVERITY for internal errors and unit
     * tests that don't need to report or worry about severities.
     */
    /**
     * NO_APPLICABLE_SEVERITY occurs only when the system was
     * unable to determine the severity.
     */
    NO_APPLICABLE_SEVERITY = 0,

    /**
     * WARNING_SEVERITY is associated with SQLWarnings.
     */
    WARNING_SEVERITY = 10000,

    /**
     * STATEMENT_SEVERITY is associated with errors which
     * cause only the current statement to be aborted.
     */
    STATEMENT_SEVERITY = 20000,

    /**
     * TRANSACTION_SEVERITY is associated with those errors which
     * cause the current transaction to be aborted.
     */
    TRANSACTION_SEVERITY = 30000,

    /**
     * SESSION_SEVERITY is associated with errors which
     * cause the current connection to be closed.
     */
    SESSION_SEVERITY = 40000,

    /**
     * DATABASE_SEVERITY is associated with errors which
     * cause the current database to be closed.
     */
    DATABASE_SEVERITY = 45000,

    /**
     * SYSTEM_SEVERITY is associated with internal errors which
     * cause the system to shut down.
     */
    SYSTEM_SEVERITY = 50000
  };

  namespace impl {
    class ClientService;
  }

  class SQLState {
  private:
    const char* m_state;
    const ExceptionSeverity m_severity;

    SQLState(const char* state, const ExceptionSeverity severity);

    static void staticInitialize();
    friend class impl::ClientService;

  public:
    const char* getSQLState() const noexcept {
      return m_state;
    }

    ExceptionSeverity getSeverity() const noexcept {
      return m_severity;
    }

    /**
     * SQLState to denote that current connection has been closed
     * or is not available otherwise for some reason.
     */
    static const SQLState NO_CURRENT_CONNECTION;

    /**
     * SQLState to denote that current statement has been closed.
     */
    static const SQLState ALREADY_CLOSED;

    /**
     * SQLState to denote that current result set has been closed.
     */
    static const SQLState LANG_RESULT_SET_NOT_OPEN;

    /**
     * SQLState to denote that an update operation was attempted
     * on a row of result set which is not updatable (e.g. because
     * statement attrs did not indicate so, or no "SELECT FOR UPDATE")
     */
    static const SQLState UPDATABLE_RESULTSET_API_DISALLOWED;

    /**
     * SQLState to denote that a scrollable cursor operation was
     * attempted on a result set which is not scrollable.
     */
    static const SQLState CURSOR_MUST_BE_SCROLLABLE;

    /**
     * SQLState to denote that a column with given name or index
     * could not be found in the current result set.
     */
    static const SQLState COLUMN_NOT_FOUND;

    /**
     * SQLState to denote that data value is outside of range allowed
     * by target data type in a type conversion.
     */
    static const SQLState LANG_OUTSIDE_RANGE_FOR_DATATYPE;

    /**
     * SQLState to denote that data value cannot be convered to requested
     * target data type.
     */
    static const SQLState LANG_DATA_TYPE_GET_MISMATCH;

    /**
     * SQLState to denote that a date/time/timestamp value could not
     * be converted to local time format and was outside range.
     */
    static const SQLState LANG_DATE_RANGE_EXCEPTION;

    /**
     * SQLState to denote that data value has incorrect representation
     * to be interpreted as a value of requested target type.
     */
    static const SQLState LANG_FORMAT_EXCEPTION;

    /**
     * SQLState to denote that given parameter position is invalid.
     */
    static const SQLState LANG_INVALID_PARAM_POSITION;

    /**
     * SQLState for an unknown std::exception caught in SQL layer.
     * In some cases this can also be thrown by server due to an
     * unexpected runtime exception.
     */
    static const SQLState UNKNOWN_EXCEPTION;

    /**
     * SQLState to denote out of memory condition.
     */
    static const SQLState OUT_OF_MEMORY;

    /**
     * SQLState to denote that a function/API has not been implemented.
     */
    static const SQLState NOT_IMPLEMENTED;

    /**
     * Denotes that input length for string/buffer was invalid.
     */
    static const SQLState INVALID_BUFFER_LENGTH;

    /**
     * SQLState to denote a null handle.
     */
    static const SQLState NULL_HANDLE;

    /**
     * Denotes that connection was attempted on an active connection.
     */
    static const SQLState CONNECTION_IN_USE;

    /**
     * Denotes that return string was truncated.
     */
    static const SQLState STRING_TRUNCATED;

    /**
     * Denotes that return numeric type was truncated.
     */
    static const SQLState NUMERIC_TRUNCATED;

    /**
     * Denotes that an unknown property was passed in the connection string
     * or specified for the data source in the ini file.
     */
    static const SQLState INVALID_CONNECTION_PROPERTY;

    /**
     * Denotes that an unexpected property value was passed in the
     * connection string or specified for the data source in the ini file.
     */
    static const SQLState INVALID_CONNECTION_PROPERTY_VALUE;

    /**
     * Denotes that the value for an option was changed to another value
     * having compatible behaviour because the underlying data store does
     * not support the specified value.
     */
    static const SQLState OPTION_VALUE_CHANGED;

    /**
     * Denotes that an option cannot be set at this point (e.g. some
     *   statement options after it has been prepared).
     *
     * Also used when the attribute cannot be set e.g connection is open
     * and trying to set the connection attribute which can't be set
     * after connecting.
     */
    static const SQLState OPTION_CANNOT_BE_SET;

    /**
     * Denotes that DRIVER property was not the expected one for SnappyData.
     */
    static const SQLState INVALID_DRIVER_NAME;

    /**
     * SQLState of the exception thrown when an invalid index (e.g. < 1)
     * was provided for parameter index for example.
     */
    static const SQLState INVALID_DESCRIPTOR_INDEX;

    /**
     * SQLState of the exception thrown when an invalid C type is specified
     * for buffer during parameter bind.
     */
    static const SQLState INVALID_CTYPE;

    /**
     * SQLState of the exception returned when an invalid handle type is
     * passed to a method.
     */
    static const SQLState INVALID_HANDLE_TYPE;

    /**
     * SQLState of the exception thrown when an invalid type for a parameter
     * is specified for buffer during parameter bind.
     */
    static const SQLState INVALID_PARAMETER_TYPE;

    /**
     * SQLState to denote that cursor no longer points to a valid
     * row in a result set or when an execution is done with an old
     * open cursor, or if cursor is tried to close with no open cursor.
     */
    static const SQLState INVALID_CURSOR_STATE;

    /**
     * SQLState of the exception thrown when a cursor is not positioned
     * on the insert row for a cursor insertion operation.
     */
    static const SQLState CURSOR_NOT_POSITIONED_ON_INSERT_ROW;

    /**
     * SQLState of the exception thrown when a cursor update operation
     * is attempted without any updates to the row.
     */
    static const SQLState INVALID_CURSOR_OPERATION_AT_CURRENT_POSITION;

    /**
     * SQLState of the exception thrown when an invalid transaction
     * operation is requested.
     */
    static const SQLState INVALID_TRANSACTION_OPERATION;

    /**
     * SQLState of the exception thrown when a function is not supported.
     */
    static const SQLState FUNCTION_NOT_SUPPORTED;

    /**
     * SQLState of the exception thrown when a prerequisite for a method
     * has not been met e.g. execute is fired on a statement without
     * first preparing it.
     */
    static const SQLState FUNCTION_SEQUENCE_ERROR;

    /**
     * SQLState of the exception thrown when an invalid descriptor field
     * identifier is passed to methods like SQLColAttribute.
     */
    static const SQLState INVALID_DESCRIPTOR_FIELD_ID;

    /**
     * SQLState of the exception thrown when a prepared statement
     * is specified where a cursor was expected.
     */
    static const SQLState PREPARED_STATEMENT_NOT_CURSOR;

    /**
     * SQLState of the exception thrown when an ODBC feature/API
     * has not been implemented.
     */
    static const SQLState FEATURE_NOT_IMPLEMENTED;

    /**
     * SQLState of the exception thrown when an invalid attribute
     * is specified.
     */
    static const SQLState INVALID_ATTRIBUTE_VALUE;

    /**
     * SQLState of the exception thrown when the translation library
     * failed to load.
     */
    static const SQLState TRANSLATION_LIBRARY_LOAD_FAILED;

    /**
     * SQLState of the exception thrown when data types given for
     * value and parameter have conversion error.
     */
    static const SQLState TYPE_ATTRIBUTE_VIOLATION;

    /**
     * SQLState of the exception thrown when transaction in progress.
     */
    static const SQLState TRANSACTION_IN_PROGRESS;

    /**
     * SQLState of the exception thrown when a LOB or some file stream
     * has been closed.
     */
    static const SQLState LANG_STREAM_CLOSED;

    /**
     * SQLState of the exception thrown when the current server has
     * failed during execution of a non-transactional operation.
     */
    static const SQLState SNAPPY_NODE_SHUTDOWN;

    /**
     * SQLState of the exception thrown when current server has
     * failed during execution of a transactional operation.
     */
    static const SQLState DATA_CONTAINER_CLOSED;

    /**
     * SQLState of the exception thrown when an error in the thrift
     * protocol is detected (can be due to server failure).
     */
    static const SQLState THRIFT_PROTOCOL_ERROR;
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* SQLSTATE_H_ */
