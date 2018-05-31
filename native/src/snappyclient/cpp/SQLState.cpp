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

#include "SQLState.h"

using namespace io::snappydata::client;

SQLState::SQLState(const char* state, const ExceptionSeverity severity) :
    m_state(state), m_severity(severity) {
}

const SQLState SQLState::NO_CURRENT_CONNECTION("08003",
    ExceptionSeverity::SESSION_SEVERITY);
const SQLState SQLState::ALREADY_CLOSED("XJ012",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::LANG_RESULT_SET_NOT_OPEN("XCL16",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::UPDATABLE_RESULTSET_API_DISALLOWED("XJ083",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::CURSOR_MUST_BE_SCROLLABLE("XJ125",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::COLUMN_NOT_FOUND("S0022",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::LANG_OUTSIDE_RANGE_FOR_DATATYPE("22003",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::LANG_DATA_TYPE_GET_MISMATCH("22005",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::LANG_DATE_RANGE_EXCEPTION("22007",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::LANG_FORMAT_EXCEPTION("22018",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::LANG_INVALID_PARAM_POSITION("XCL13",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::UNKNOWN_EXCEPTION("XJ001",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::OUT_OF_MEMORY("HY001",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::NOT_IMPLEMENTED("0A000",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::INVALID_BUFFER_LENGTH("HY090",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::NULL_HANDLE("HY009",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::CONNECTION_IN_USE("08002",
    ExceptionSeverity::SESSION_SEVERITY);
const SQLState SQLState::STRING_TRUNCATED("01004",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::NUMERIC_TRUNCATED("01S07",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::INVALID_CONNECTION_PROPERTY("01S00",
    ExceptionSeverity::SESSION_SEVERITY);
const SQLState SQLState::INVALID_CONNECTION_PROPERTY_VALUE("XJ028",
    ExceptionSeverity::SESSION_SEVERITY);
const SQLState SQLState::OPTION_VALUE_CHANGED("01S02",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::OPTION_CANNOT_BE_SET("HY011",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::INVALID_DRIVER_NAME("IM003",
    ExceptionSeverity::SESSION_SEVERITY);
const SQLState SQLState::INVALID_DESCRIPTOR_INDEX("07009",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::INVALID_CTYPE("HY003",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::INVALID_HANDLE_TYPE("HY092",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::INVALID_PARAMETER_TYPE("HY004",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::INVALID_CURSOR_STATE("24000",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::CURSOR_NOT_POSITIONED_ON_INSERT_ROW("XJ086",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::INVALID_CURSOR_OPERATION_AT_CURRENT_POSITION("XJ121",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::INVALID_TRANSACTION_OPERATION("HY012",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::FUNCTION_NOT_SUPPORTED("IM001",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::FUNCTION_SEQUENCE_ERROR("HY010",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::INVALID_DESCRIPTOR_FIELD_ID("HY091",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::PREPARED_STATEMENT_NOT_CURSOR("07005",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::FEATURE_NOT_IMPLEMENTED("HYC00",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::INVALID_ATTRIBUTE_VALUE("HY024",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::TRANSLATION_LIBRARY_LOAD_FAILED("IM009",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::TYPE_ATTRIBUTE_VIOLATION("07006",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::TRANSACTION_IN_PROGRESS("25S02",
    ExceptionSeverity::STATEMENT_SEVERITY);
const SQLState SQLState::LANG_STREAM_CLOSED("XCL53",
    ExceptionSeverity::NO_APPLICABLE_SEVERITY);
const SQLState SQLState::SNAPPY_NODE_SHUTDOWN("X0Z01",
    ExceptionSeverity::TRANSACTION_SEVERITY);
const SQLState SQLState::DATA_CONTAINER_CLOSED("40XD0",
    ExceptionSeverity::TRANSACTION_SEVERITY);
const SQLState SQLState::THRIFT_PROTOCOL_ERROR("58015",
    ExceptionSeverity::SESSION_SEVERITY);

void SQLState::staticInitialize() {
}
