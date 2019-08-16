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

#include "messages/SQLStateMessage.h"
#include "SQLState.h"

#include "snappydata_types.h"

using namespace io::snappydata;
using namespace io::snappydata::client;

template class SQLMessage1<const char*> ;

SQLMessage0 SQLStateMessage::NO_CURRENT_CONNECTION_MSG1;
SQLMessage2<const char*, const char*> SQLStateMessage::NO_CURRENT_CONNECTION_MSG2;
SQLMessage0 SQLStateMessage::NO_CURRENT_ROW_MSG;
SQLMessage0 SQLStateMessage::ALREADY_CLOSED_MSG;
SQLMessage0 SQLStateMessage::CONNECTION_IN_USE_MSG;
SQLMessage0 SQLStateMessage::INVALID_CURSOR_STATE_MSG1;
SQLMessage0 SQLStateMessage::INVALID_CURSOR_STATE_MSG2;
SQLMessage0 SQLStateMessage::CURSOR_NOT_POSITIONED_ON_INSERT_ROW_MSG;
SQLMessage0 SQLStateMessage::INVALID_CURSOR_UPDATE_AT_CURRENT_POSITION_MSG;
SQLMessage0 SQLStateMessage::INVALID_TRANSACTION_OPERATION_MSG2;
SQLMessage0 SQLStateMessage::PREPARED_STATEMENT_NOT_CURSOR_MSG;
SQLMessage0 SQLStateMessage::NULL_CURSOR_MSG;
SQLMessage0 SQLStateMessage::TRANSACTION_IN_PROGRESS_MSG;
SQLMessage0 SQLStateMessage::LANG_STREAM_CLOSED_MSG;

SQLMessage1<const char*> SQLStateMessage::LANG_RESULT_SET_NOT_OPEN_MSG;
SQLMessage1<const char*> SQLStateMessage::UPDATABLE_RESULTSET_API_DISALLOWED_MSG;
SQLMessage1<const char*> SQLStateMessage::CURSOR_MUST_BE_SCROLLABLE_MSG;
SQLMessage2<int, size_t> SQLStateMessage::COLUMN_NOT_FOUND_MSG1;
SQLMessage1<const char*> SQLStateMessage::COLUMN_NOT_FOUND_MSG2;
SQLMessage2<const char*, const char*> SQLStateMessage::LANG_OUTSIDE_RANGE_FOR_DATATYPE_MSG;
SQLMessage2<int, size_t> SQLStateMessage::LANG_OUTSIDE_RANGE_FOR_NUMERIC_MSG;
SQLMessage3<const char*, const char*, int> SQLStateMessage::LANG_DATA_TYPE_GET_MISMATCH_MSG;
SQLMessage1<int64_t> SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG1;
SQLMessage6<int, int, int, int, int, int> SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG2;
SQLMessage2<int, int> SQLStateMessage::LANG_DATE_RANGE_EXCEPTION_MSG3;
SQLMessage2<const char*, const char*> SQLStateMessage::LANG_FORMAT_EXCEPTION_MSG;
SQLMessage2<int, size_t> SQLStateMessage::LANG_INVALID_PARAM_POSITION_MSG;
SQLMessage1<const char*> SQLStateMessage::OUT_OF_MEMORY_MSG;
SQLMessage1<const char*> SQLStateMessage::NOT_IMPLEMENTED_MSG;
SQLMessage2<int, const char*> SQLStateMessage::INVALID_BUFFER_LENGTH_MSG;
SQLMessage1<int> SQLStateMessage::NULL_HANDLE_MSG;
SQLMessage2<const char*, size_t> SQLStateMessage::STRING_TRUNCATED_MSG;
SQLMessage2<const char*, size_t> SQLStateMessage::NUMERIC_TRUNCATED_MSG;
SQLMessage1<const char*> SQLStateMessage::INVALID_CONNECTION_PROPERTY_MSG;
SQLMessage2<const char*, const char*> SQLStateMessage::INVALID_CONNECTION_PROPERTY_VALUE_MSG;
SQLMessage3<const char*, size_t, int> SQLStateMessage::OPTION_VALUE_CHANGED_MSG;
SQLMessage1<const char*> SQLStateMessage::OPTION_CANNOT_BE_SET_FOR_STATEMENT_MSG;
SQLMessage2<const char*, const char*> SQLStateMessage::INVALID_DRIVER_NAME_MSG;
SQLMessage3<int, size_t, const char*> SQLStateMessage::INVALID_DESCRIPTOR_INDEX_MSG;
SQLMessage2<int, int> SQLStateMessage::INVALID_CTYPE_MSG;
SQLMessage1<int> SQLStateMessage::INVALID_HANDLE_TYPE_MSG;
SQLMessage2<int, int> SQLStateMessage::INVALID_PARAMETER_TYPE_MSG;
SQLMessage2<int, const char*> SQLStateMessage::OPTION_TYPE_OUT_OF_RANGE;
SQLMessage1<const char*> SQLStateMessage::INVALID_TRANSACTION_OPERATION_MSG1;
SQLMessage1<const char*> SQLStateMessage::FUNCTION_NOT_SUPPORTED_MSG;
SQLMessage1<const char*> SQLStateMessage::FUNCTION_SEQUENCE_ERROR_MSG;
SQLMessage0 SQLStateMessage::STATEMENT_NOT_PREPARED_MSG;
SQLMessage1<int> SQLStateMessage::INVALID_DESCRIPTOR_FIELD_ID_MSG;
SQLMessage1<const char*> SQLStateMessage::FEATURE_NOT_IMPLEMENTED_MSG1;
SQLMessage1<const char*> SQLStateMessage::FEATURE_NOT_IMPLEMENTED_MSG2;
SQLMessage1<int> SQLStateMessage::UNKNOWN_ATTRIBUTE_MSG;
SQLMessage2<size_t, const char*> SQLStateMessage::INVALID_ATTRIBUTE_VALUE_MSG;
SQLMessage2<const char*, const char*> SQLStateMessage::INVALID_ATTRIBUTE_VALUE_STR_MSG;
SQLMessage2<const char*, const char*> SQLStateMessage::TRANSACTION_LIBRARY_LOAD_FAILED_MSG;
SQLMessage2<const char*, const char*> SQLStateMessage::ATTRIBUTE_CANNOT_BE_SET_NOW_MSG;
SQLMessage2<int, int> SQLStateMessage::TYPE_ATTRIBUTE_VIOLATION_MSG;

SQLMessage3<const char*, std::exception, const char*>
    SQLStateMessage::SNAPPY_NODE_SHUTDOWN_MSG;
SQLMessage3<const char*, std::exception, const char*>
    SQLStateMessage::DATA_CONTAINER_CLOSED_MSG;
SQLMessage2<const char*, const char*> SQLStateMessage::THRIFT_PROTOCOL_ERROR_MSG;

SQLMessage3<const char*, const char*, int> SQLStateMessage::NATIVE_ERROR;

void SQLStateMessage::staticInitialize() {
  NO_CURRENT_CONNECTION_MSG1.initialize(SQLState::NO_CURRENT_CONNECTION, 1,
      "The underlying physical connection was never opened or is closed");
  NO_CURRENT_CONNECTION_MSG2.initialize(SQLState::NO_CURRENT_CONNECTION, 2,
      "The underlying physical connection to server '",
      "' was never opened or is closed (operation=", ")");
  NO_CURRENT_ROW_MSG.initialize(SQLState::INVALID_CURSOR_STATE, 1,
      "No current row.");
  ALREADY_CLOSED_MSG.initialize(SQLState::ALREADY_CLOSED, 1,
      "PreparedStatement already closed");
  CONNECTION_IN_USE_MSG.initialize(SQLState::CONNECTION_IN_USE, 1,
      "The specified handle had already been "
          "used to establish a connection with a data source, and the "
          "connection was still open or the user was browsing for a connection");
  INVALID_CURSOR_STATE_MSG1.initialize(SQLState::INVALID_CURSOR_STATE, 2,
      "Invalid cursor state: an open cursor "
          "exists for this statement (forgot to invoke SQLCloseCursor?)");
  INVALID_CURSOR_STATE_MSG2.initialize(SQLState::INVALID_CURSOR_STATE, 3,
      "Invalid cursor state: no open cursor exists for this statement");
  CURSOR_NOT_POSITIONED_ON_INSERT_ROW_MSG.initialize(
      SQLState::CURSOR_NOT_POSITIONED_ON_INSERT_ROW, 1,
      "Invalid cursor insert operation while the cursor is not on the insert "
      "row or if the concurrency of this ResultSet object is CONCUR_READ_ONLY.");
  INVALID_CURSOR_UPDATE_AT_CURRENT_POSITION_MSG.initialize(
      SQLState::INVALID_CURSOR_OPERATION_AT_CURRENT_POSITION, 1,
      "Invalid update operation at current cursor position.");
  INVALID_TRANSACTION_OPERATION_MSG2.initialize(
      SQLState::INVALID_TRANSACTION_OPERATION, 2,
      "Cannot commit or rollback in a shared environment");
  PREPARED_STATEMENT_NOT_CURSOR_MSG.initialize(
      SQLState::PREPARED_STATEMENT_NOT_CURSOR, 1,
      "Prepared statement not a cursor-specification");
  NULL_CURSOR_MSG.initialize(SQLState::INVALID_CURSOR_STATE, 4,
      "The cursor name passed was NULL");
  TRANSACTION_IN_PROGRESS_MSG.initialize(SQLState::TRANSACTION_IN_PROGRESS, 1,
      "Transaction is still active. Commit or rollback the transaction.");
  LANG_STREAM_CLOSED_MSG.initialize(SQLState::LANG_STREAM_CLOSED, 1,
      "Stream is closed");

  LANG_RESULT_SET_NOT_OPEN_MSG.initialize(SQLState::LANG_RESULT_SET_NOT_OPEN, 1,
      "ResultSet not open for operation '", "'");
  UPDATABLE_RESULTSET_API_DISALLOWED_MSG.initialize(
      SQLState::UPDATABLE_RESULTSET_API_DISALLOWED, 1,
      "ResultSet not updatable for operation '", "'");
  CURSOR_MUST_BE_SCROLLABLE_MSG.initialize(SQLState::CURSOR_MUST_BE_SCROLLABLE,
      1, "ResultSet not scrollable [operation=", "]");
  COLUMN_NOT_FOUND_MSG1.initialize(SQLState::COLUMN_NOT_FOUND, 1, "Column ",
      " not found (max = ", ")");
  COLUMN_NOT_FOUND_MSG2.initialize(SQLState::COLUMN_NOT_FOUND, 2, "Column '",
      "' not found");
  LANG_OUTSIDE_RANGE_FOR_DATATYPE_MSG.initialize(
      SQLState::LANG_OUTSIDE_RANGE_FOR_DATATYPE, 1,
      "The resulting value is outside the range for data type '", "'", "");
  LANG_OUTSIDE_RANGE_FOR_NUMERIC_MSG.initialize(
      SQLState::LANG_OUTSIDE_RANGE_FOR_DATATYPE, 2,
      "The resulting value is outside the range for NUMERIC type: "
          "output buffer size = ", " ; actual size = ", "");
  LANG_DATA_TYPE_GET_MISMATCH_MSG.initialize(
      SQLState::LANG_DATA_TYPE_GET_MISMATCH, 1,
      "An attempt was made to get a data value of type '", "' from a "
          "data value of type '", "' for column ", "");
  LANG_DATE_RANGE_EXCEPTION_MSG1.initialize(SQLState::LANG_DATE_RANGE_EXCEPTION,
      1, "Cannot convert seconds since Epoch ", " to a valid time");
  LANG_DATE_RANGE_EXCEPTION_MSG2.initialize(SQLState::LANG_DATE_RANGE_EXCEPTION,
      2, "Invalid date time, year=", " month=", " day=", " hour=", " min=",
      " sec=", "");
  LANG_DATE_RANGE_EXCEPTION_MSG3.initialize(SQLState::LANG_DATE_RANGE_EXCEPTION,
      3, "Invalid nanos ", ": expected 0 to ", " (both inclusive)");
  LANG_FORMAT_EXCEPTION_MSG.initialize(SQLState::LANG_FORMAT_EXCEPTION, 1,
      "Invalid character string format for type '", "'", "");
  LANG_INVALID_PARAM_POSITION_MSG.initialize(
      SQLState::LANG_INVALID_PARAM_POSITION, 1, "The parameter position '",
      "' is out of range. The number of parameters for this "
          "prepared  statement is '", "'");
  OUT_OF_MEMORY_MSG.initialize(SQLState::OUT_OF_MEMORY, 1,
      "The driver was unable to allocate memory required to support "
          "execution or completion of the function: ", "");
  NOT_IMPLEMENTED_MSG.initialize(SQLState::NOT_IMPLEMENTED, 1,
      "Driver method '", "' has not been implemented yet");
  INVALID_BUFFER_LENGTH_MSG.initialize(SQLState::INVALID_BUFFER_LENGTH, 1,
      "Invalid string or buffer length ", " for ", "");
  NULL_HANDLE_MSG.initialize(SQLState::NULL_HANDLE, 1,
      "The handle argument was NULL for type=", "");
  STRING_TRUNCATED_MSG.initialize(SQLState::STRING_TRUNCATED, 1,
      "The buffer was not large enough to return the ", " and was "
          "truncated to ", " chars");
  NUMERIC_TRUNCATED_MSG.initialize(SQLState::NUMERIC_TRUNCATED, 1,
      "The output data type was not large enough to return the ",
      " and the fraction having ", " digits was truncated");
  INVALID_CONNECTION_PROPERTY_MSG.initialize(
      SQLState::INVALID_CONNECTION_PROPERTY, 1,
      "Unknown connection attribute '", "' provided");
  INVALID_CONNECTION_PROPERTY_VALUE_MSG.initialize(
      SQLState::INVALID_CONNECTION_PROPERTY_VALUE, 1,
      "Unexpected connection attribute value '", "' provided for '", "'");
  OPTION_VALUE_CHANGED_MSG.initialize(SQLState::OPTION_VALUE_CHANGED, 1,
      "Option value for ", " changed from ", " to ", "");
  OPTION_CANNOT_BE_SET_FOR_STATEMENT_MSG.initialize(
      SQLState::OPTION_CANNOT_BE_SET, 1, "Attribute value for ",
      " cannot be set now after statement prepare");
  INVALID_DRIVER_NAME_MSG.initialize(SQLState::INVALID_DRIVER_NAME, 1,
      "Unexpected driver name '", "', expected '", "'");
  INVALID_DESCRIPTOR_INDEX_MSG.initialize(SQLState::INVALID_DESCRIPTOR_INDEX, 1,
      "Invalid descriptor index ", " (max=", ") for ", "");
  INVALID_CTYPE_MSG.initialize(SQLState::INVALID_CTYPE, 1,
      "Invalid C data type ", " specified for parameter ", "");
  INVALID_HANDLE_TYPE_MSG.initialize(SQLState::INVALID_HANDLE_TYPE, 1,
      "Invalid handle type = ", "");
  INVALID_PARAMETER_TYPE_MSG.initialize(SQLState::INVALID_PARAMETER_TYPE, 1,
      "Invalid parameter type ", " specified for parameter ", "");
  OPTION_TYPE_OUT_OF_RANGE.initialize(SQLState::INVALID_HANDLE_TYPE, 2,
      "The value ", " specified for the option ", " was not valid");
  INVALID_TRANSACTION_OPERATION_MSG1.initialize(
      SQLState::INVALID_TRANSACTION_OPERATION, 1, "The argument ",
      " was neither SQL_COMMIT nor SQL_ROLLBACK");
  FUNCTION_NOT_SUPPORTED_MSG.initialize(SQLState::FUNCTION_NOT_SUPPORTED, 1,
      "Driver does not support function ", "");
  FUNCTION_SEQUENCE_ERROR_MSG.initialize(SQLState::FUNCTION_SEQUENCE_ERROR, 1,
      "Function sequence error: ", "");
  STATEMENT_NOT_PREPARED_MSG.initialize(SQLState::FUNCTION_SEQUENCE_ERROR, 2,
      "Cannot execute the statement associated with the handle "
          "as it was not prepared");
  INVALID_DESCRIPTOR_FIELD_ID_MSG.initialize(
      SQLState::INVALID_DESCRIPTOR_FIELD_ID, 1,
      "Invalid descriptor field identifier ", "");
  FEATURE_NOT_IMPLEMENTED_MSG1.initialize(SQLState::FEATURE_NOT_IMPLEMENTED, 1,
      "The requested optional feature '", "' is not yet implemented");
  FEATURE_NOT_IMPLEMENTED_MSG2.initialize(SQLState::FEATURE_NOT_IMPLEMENTED, 2,
      "The driver or data source does not support the conversion "
          "specified by the combination of the value specified for the "
          "argument ValueType and the driver-specific value specified "
          "for the argument ParameterType for '", "'.");
  UNKNOWN_ATTRIBUTE_MSG.initialize(SQLState::INVALID_HANDLE_TYPE, 3,
      "Unknown attribute ", "");
  INVALID_ATTRIBUTE_VALUE_MSG.initialize(SQLState::INVALID_ATTRIBUTE_VALUE, 1,
      "Invalid attribute value ", " for attribute ", "");
  INVALID_ATTRIBUTE_VALUE_STR_MSG.initialize(SQLState::INVALID_ATTRIBUTE_VALUE,
      2, "Invalid attribute value ", " for attribute ", "");
  TRANSACTION_LIBRARY_LOAD_FAILED_MSG.initialize(
      SQLState::TRANSLATION_LIBRARY_LOAD_FAILED, 1,
      "Unable to load translation library ", ": ", "");
  ATTRIBUTE_CANNOT_BE_SET_NOW_MSG.initialize(SQLState::OPTION_CANNOT_BE_SET, 2,
      "Attribute ", " cannot be set now. Reason: ", "");
  TYPE_ATTRIBUTE_VIOLATION_MSG.initialize(SQLState::TYPE_ATTRIBUTE_VIOLATION, 1,
      "Data type identified by value type argument '",
      "' cannot be converted to data type identified by parameter type '", "'");

  SNAPPY_NODE_SHUTDOWN_MSG.initialize(SQLState::SNAPPY_NODE_SHUTDOWN, 1,
      "Node on ", " failed with ", " (operation=", ")");
  DATA_CONTAINER_CLOSED_MSG.initialize(SQLState::DATA_CONTAINER_CLOSED, 1,
      "Data container on node ", " failed with ", " (transactional operation=",
      ")");
  THRIFT_PROTOCOL_ERROR_MSG.initialize(SQLState::THRIFT_PROTOCOL_ERROR, 1,
      "Error in client-server protocol: ", " (operation=", ")");

  NATIVE_ERROR.initialize(SQLState::UNKNOWN_EXCEPTION, 1, "", " of ",
      " failed due to errno ", "");
}
