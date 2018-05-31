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

#ifndef SQLSTATEMESSAGE_H_
#define SQLSTATEMESSAGE_H_

#include "SQLMessage.h"

#include <exception>

namespace io {
namespace snappydata {
namespace client {

  namespace impl {
    class ClientService;
  }

  class SQLStateMessage {
  private:
    // no constructors or default operators
    SQLStateMessage() = delete;
    SQLStateMessage(const SQLStateMessage&) = delete;
    const SQLStateMessage& operator=(const SQLStateMessage&) = delete;

    static void staticInitialize();
    friend class impl::ClientService;

  public:

    /**
     * Message string for "NO_CURRENT_CONNECTION" SQLState.
     */
    static SQLMessage0 NO_CURRENT_CONNECTION_MSG1;

    /**
     * Message string for "NO_CURRENT_CONNECTION" SQLState that also includes
     * previous server and operation for which connection was closed or lost.
     */
    static SQLMessage2<const char*, const char*> NO_CURRENT_CONNECTION_MSG2;

    /**
     * Message string for the case when cursor is not positioned on a row.
     */
    static SQLMessage0 NO_CURRENT_ROW_MSG;

    /**
     * Message string for "ALREADY_CLOSED" SQLState.
     */
    static SQLMessage0 ALREADY_CLOSED_MSG;

    /**
     * Message string for "CONNECTION_IN_USE" SQLState.
     */
    static SQLMessage0 CONNECTION_IN_USE_MSG;

    /**
     * Message string for "INVALID_CURSOR_STATE" SQLState when execution
     * is done with an old open cursor.
     */
    static SQLMessage0 INVALID_CURSOR_STATE_MSG1;

    /**
     * Message string for "INVALID_CURSOR_STATE" SQLState when an attempt
     * to close a cursor is done with no open cursor.
     */
    static SQLMessage0 INVALID_CURSOR_STATE_MSG2;

    /**
     * Message string for "CURSOR_NOT_POSITIONED_ON_INSERT_ROW" SQLState
     * when cursor insert is attempted without using the insert row.
     */
    static SQLMessage0 CURSOR_NOT_POSITIONED_ON_INSERT_ROW_MSG;

    /**
     * Message string for "INVALID_CURSOR_OPERATION_AT_CURRENT_POSITION"
     * SQLState when cursor update is attempted without any row update.
     */
    static SQLMessage0 INVALID_CURSOR_UPDATE_AT_CURRENT_POSITION_MSG;

    /**
     * Message string for "INVALID_TRANSACTION_OPERATION" SQLState.
     */
    static SQLMessage0 INVALID_TRANSACTION_OPERATION_MSG2;

    /**
     * Message string for "PREPARED_STATEMENT_NOT_CURSOR" SQLState.
     */
    static SQLMessage0 PREPARED_STATEMENT_NOT_CURSOR_MSG;

    /**
     * Message string for null cursor having "NULL_HANDLE" SQLState.
     */
    static SQLMessage0 NULL_CURSOR_MSG;

    /**
     * Message string for "TRANSACTION_IN_PROGRESS" SQLState.
     */
    static SQLMessage0 TRANSACTION_IN_PROGRESS_MSG;

    /**
     * Message string for "LANG_STREAM_CLOSED" SQLState.
     */
    static SQLMessage0 LANG_STREAM_CLOSED_MSG;

    /**
     * Message string for "LANG_RESULT_SET_NOT_OPEN" SQLState.
     */
    static SQLMessage1<const char*> LANG_RESULT_SET_NOT_OPEN_MSG;

    /**
     * Message string for "UPDATABLE_RESULTSET_API_DISALLOWED" SQLState.
     */
    static SQLMessage1<const char*>
        UPDATABLE_RESULTSET_API_DISALLOWED_MSG;

    /**
     * Message string for "CURSOR_MUST_BE_SCROLLABLE" SQLState.
     */
    static SQLMessage1<const char*> CURSOR_MUST_BE_SCROLLABLE_MSG;

    /**
     * Message string for "COLUMN_NOT_FOUND" SQLState.
     */
    static SQLMessage2<int, size_t> COLUMN_NOT_FOUND_MSG1;

    /**
     * Message string for "COLUMN_NOT_FOUND" SQLState.
     */
    static SQLMessage1<const char*> COLUMN_NOT_FOUND_MSG2;

    /**
     * Message string for "LANG_OUTSIDE_RANGE_FOR_DATATYPE" SQLState.
     */
    static SQLMessage2<const char*, const char*>
        LANG_OUTSIDE_RANGE_FOR_DATATYPE_MSG;

    /**
     * Message string for numeric overflow with
     * "LANG_OUTSIDE_RANGE_FOR_DATATYPE" SQLState.
     */
    static SQLMessage2<int, size_t> LANG_OUTSIDE_RANGE_FOR_NUMERIC_MSG;

    /**
     * Message string for "LANG_DATA_TYPE_GET_MISMATCH" SQLState.
     */
    static SQLMessage3<const char*, const char*, int>
        LANG_DATA_TYPE_GET_MISMATCH_MSG;

    /**
     * Message string for "LANG_DATE_RANGE_EXCEPTION" SQLState.
     */
    static SQLMessage1<int64_t> LANG_DATE_RANGE_EXCEPTION_MSG1;

    /**
     * Message string for "LANG_DATE_RANGE_EXCEPTION" SQLState.
     */
    static SQLMessage6<int, int, int, int, int, int>
        LANG_DATE_RANGE_EXCEPTION_MSG2;

    /**
     * Message string for "LANG_DATE_RANGE_EXCEPTION" SQLState.
     */
    static SQLMessage2<int, int> LANG_DATE_RANGE_EXCEPTION_MSG3;

    /**
     * Message string for "LANG_FORMAT_EXCEPTION" SQLState.
     */
    static SQLMessage2<const char*, const char*> LANG_FORMAT_EXCEPTION_MSG;

    /**
     * Message string for "LANG_INVALID_PARAM_POSITION" SQLState.
     */
    static SQLMessage2<int, size_t> LANG_INVALID_PARAM_POSITION_MSG;

    /**
     * SQLState to denote out of memory condition.
     */
    static SQLMessage1<const char*> OUT_OF_MEMORY_MSG;

    /**
     * Message string for "NOT_IMPLEMENTED" SQLState.
     */
    static SQLMessage1<const char*> NOT_IMPLEMENTED_MSG;

    /**
     * Message string for "INVALID_BUFFER_LENGTH" SQLState.
     */
    static SQLMessage2<int, const char*> INVALID_BUFFER_LENGTH_MSG;

    /**
     * Message string for "NULL_HANDLE" SQLState.
     */
    static SQLMessage1<int> NULL_HANDLE_MSG;

    /**
     * Message string for "STRING_TRUNCATED" SQLState.
     */
    static SQLMessage2<const char*, size_t> STRING_TRUNCATED_MSG;

    /**
     * Message string for "NUMERIC_TRUNCATED" SQLState.
     */
    static SQLMessage2<const char*, size_t> NUMERIC_TRUNCATED_MSG;

    /**
     * Message string for "INVALID_CONNECTION_PROPERTY" SQLState.
     */
    static SQLMessage1<const char*> INVALID_CONNECTION_PROPERTY_MSG;

    /**
     * Message string for "INVALID_CONNECTION_PROPERTY_VALUE" SQLState.
     */
    static SQLMessage2<const char*, const char*>
        INVALID_CONNECTION_PROPERTY_VALUE_MSG;

    /**
     * Message string for "OPTION_VALUE_CHANGED" SQLState.
     */
    static SQLMessage3<const char*, size_t, int> OPTION_VALUE_CHANGED_MSG;

    /**
     * Message string for "OPTION_CANNOT_BE_SET" SQLState
     * for the case of statements.
     */
    static SQLMessage1<const char*> OPTION_CANNOT_BE_SET_FOR_STATEMENT_MSG;

    /**
     * Message string for "INVALID_DRIVER_NAME" SQLState.
     */
    static SQLMessage2<const char*, const char*> INVALID_DRIVER_NAME_MSG;

    /**
     * Message string for "INVALID_DESCRIPTOR_INDEX" SQLState.
     */
    static SQLMessage3<int, size_t, const char*> INVALID_DESCRIPTOR_INDEX_MSG;

    /**
     * Message string for "INVALID_CTYPE" SQLState.
     */
    static SQLMessage2<int, int> INVALID_CTYPE_MSG;

    /**
     * Message string for "INVALID_HANDLE_TYPE" SQLState.
     */
    static SQLMessage1<int> INVALID_HANDLE_TYPE_MSG;

    /**
     * Message string for "INVALID_PARAMETER_TYPE" SQLState.
     */
    static SQLMessage2<int, int> INVALID_PARAMETER_TYPE_MSG;

    /**
     * Message string for "InvalidHandleType" SQLState
     * when option is out of range.
     */
    static SQLMessage2<int, const char*> OPTION_TYPE_OUT_OF_RANGE;

    /**
     * Message string for "INVALID_TRANSACTION_OPERATION" SQLState.
     */
    static SQLMessage1<const char*> INVALID_TRANSACTION_OPERATION_MSG1;

    /**
     * Message string for "FUNCTION_NOT_SUPPORTED" SQLState.
     */
    static SQLMessage1<const char*> FUNCTION_NOT_SUPPORTED_MSG;

    /**
     * Message string for "FUNCTION_SEQUENCE_ERROR" SQLState.
     */
    static SQLMessage1<const char*> FUNCTION_SEQUENCE_ERROR_MSG;

    /**
     * Message string for "FUNCTION_SEQUENCE_ERROR" SQLState when
     * execution of an unprepared statement is done incorrectly
     * (e.g. without SQL string).
     */
    static SQLMessage0 STATEMENT_NOT_PREPARED_MSG;

    /**
     * Message string for "INVALID_DESCRIPTOR_FIELD_ID" SQLState.
     */
    static SQLMessage1<int> INVALID_DESCRIPTOR_FIELD_ID_MSG;

    /**
     * Message string for "FEATURE_NOT_IMPLEMENTED" SQLState.
     */
    static SQLMessage1<const char*> FEATURE_NOT_IMPLEMENTED_MSG1;

    /**
     * Message string for "FEATURE_NOT_IMPLEMENTED" SQLState
     * for the case of unsupported conversion.
     */
    static SQLMessage1<const char*> FEATURE_NOT_IMPLEMENTED_MSG2;

    /**
     * Message string for an unknown attribute being set.
     */
    static SQLMessage1<int> UNKNOWN_ATTRIBUTE_MSG;

    /**
     * Message string for "INVALID_ATTRIBUTE_VALUE" SQLState.
     */
    static SQLMessage2<size_t, const char*> INVALID_ATTRIBUTE_VALUE_MSG;

    /**
     * Message string for "INVALID_ATTRIBUTE_VALUE" SQLState
     * for string attribute values.
     */
    static SQLMessage2<const char*, const char*>
        INVALID_ATTRIBUTE_VALUE_STR_MSG;

    /**
     * Message string for "TRANSACTION_LIBRARY_LOAD_FAILED" SQLState.
     */
    static SQLMessage2<const char*, const char*>
        TRANSACTION_LIBRARY_LOAD_FAILED_MSG;

    /**
     * Message string for "ATTRIBUTE_CANNOT_BE_SET_NOW" SQLState.
     */
    static SQLMessage2<const char*, const char*>
        ATTRIBUTE_CANNOT_BE_SET_NOW_MSG;

    /**
     * Message string for "TYPE_ATTRIBUTE_VIOLATION" SQLState.
     */
    static SQLMessage2<int, int> TYPE_ATTRIBUTE_VIOLATION_MSG;

    /**
     * Message string for "SNAPPY_NODE_SHUTDOWN" SQLState.
     */
    static SQLMessage3<const char*, std::exception, const char*>
        SNAPPY_NODE_SHUTDOWN_MSG;

    /**
     * Message string for "DATA_CONTAINER_CLOSED" SQLState.
     */
    static SQLMessage3<const char*, std::exception, const char*>
        DATA_CONTAINER_CLOSED_MSG;

    /**
     * Message string for "THRIFT_PROTOCOL_ERROR" SQLState.
     */
    static SQLMessage2<const char*, const char*> THRIFT_PROTOCOL_ERROR_MSG;

    /**
     * Message string for native errors that set errno.
     *
     * arg1: operation that failed as string
     * arg2: class of operation that failed as string ({arg1} of {arg2})
     * arg3: errno integer
     */
    static SQLMessage3<const char*, const char*, int> NATIVE_ERROR;
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* SQLSTATEMESSAGE_H_ */
