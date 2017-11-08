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

package com.pivotal.gemfirexd.internal.shared.common.error;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransactionRollbackException;

import com.pivotal.gemfirexd.internal.shared.common.i18n.SQLMessageFormat;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

/**
 * Default SQLException factory implementation used for client/server.
 * 
 * @author swale
 * @since gfxd 1.1
 */
public class DefaultExceptionFactory40 extends DefaultExceptionFactory30 {

  // Important DRDA SQL States, from DRDA v3 spec, Section 8.2
  // We have to consider these as well as the standard SQLState classes
  // when choosing the right exception subclass
  private static final String DRDA_CONVERSATION_TERMINATED = "58009";
  private static final String DRDA_COMMAND_NOT_SUPPORTED = "58014";
  private static final String DRDA_OBJECT_NOT_SUPPORTED = "58015";
  private static final String DRDA_PARAM_NOT_SUPPORTED = "58016";
  private static final String DRDA_VALUE_NOT_SUPPORTED = "58017";
  private static final String DRDA_SQLTYPE_NOT_SUPPORTED = "56084";
  private static final String DRDA_CONVERSION_NOT_SUPPORTED = "57017";
  private static final String DRDA_REPLY_MSG_NOT_SUPPORTED = "58018";

  public DefaultExceptionFactory40(SQLMessageFormat formatter) {
    super(formatter);
  }

  /**
   * Returns true if the given messageId/SQLState corresponds to one of those
   * thrown indicating that transaction has failed.
   */
  public static boolean isTransactionException(String messageId) {
    return messageId.startsWith(SQLState.GFXD_OPERATION_CONFLICT_PREFIX)
        || messageId.startsWith(SQLState.GFXD_TRANSACTION_ILLEGAL_PREFIX)
        || messageId.startsWith(SQLState.GFXD_TRANSACTION_INDOUBT_PREFIX)
        || messageId.startsWith(SQLState.GFXD_TRANSACTION_READ_ONLY_PREFIX)
        || messageId.startsWith(SQLState.GFXD_NODE_SHUTDOWN_PREFIX)
        || messageId.startsWith(SQLState.TRANSACTION_PREFIX);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SQLException getSQLException(String message, String sqlState,
      int errCode, SQLException next, Throwable t) {
    SQLException sqle;
    if (sqlState == null) {
      sqle = new SQLException(getMessage(message, "XJ001", errCode), "XJ001",
          errCode, t);
    }
    else if (sqlState.startsWith(SQLState.CONNECTIVITY_PREFIX)
        || sqlState.equals(DRDA_CONVERSATION_TERMINATED)
        || errCode >= ExceptionSeverity.SESSION_SEVERITY) {
      // none of the sqlstate supported by derby belongs to
      // TransientConnectionException. DERBY-3075
      sqle = new SQLNonTransientConnectionException(getMessage(message,
          sqlState, errCode), sqlState, errCode, t);
    }
    else if (sqlState.startsWith(SQLState.SQL_DATA_PREFIX)) {
      sqle = new SQLDataException(getMessage(message, sqlState, errCode),
          sqlState, errCode, t);
    }
    else if (sqlState.startsWith(SQLState.INTEGRITY_VIOLATION_PREFIX)) {
      sqle = new SQLIntegrityConstraintViolationException(getMessage(message,
          sqlState, errCode), sqlState, errCode, t);
    }
    else if (sqlState.startsWith(SQLState.AUTHORIZATION_SPEC_PREFIX)
        || sqlState.startsWith(SQLState.AUTH_FAILURE_PREFIX)) {
      sqle = new SQLInvalidAuthorizationSpecException(getMessage(message,
          sqlState, errCode), sqlState, errCode, t);
    }
    else if (isTransactionException(sqlState)
        || errCode >= ExceptionSeverity.TRANSACTION_SEVERITY) {
      sqle = new SQLTransactionRollbackException(getMessage(message, sqlState,
          errCode), sqlState, errCode, t);
    }
    else if (sqlState.startsWith(SQLState.LSE_COMPILATION_PREFIX)) {
      sqle = new SQLSyntaxErrorException(
          getMessage(message, sqlState, errCode), sqlState, errCode, t);
    }
    else if (sqlState.startsWith(SQLState.UNSUPPORTED_PREFIX)
        || sqlState.equals(DRDA_COMMAND_NOT_SUPPORTED)
        || sqlState.equals(DRDA_OBJECT_NOT_SUPPORTED)
        || sqlState.equals(DRDA_PARAM_NOT_SUPPORTED)
        || sqlState.equals(DRDA_VALUE_NOT_SUPPORTED)
        || sqlState.equals(DRDA_SQLTYPE_NOT_SUPPORTED)
        || sqlState.equals(DRDA_CONVERSION_NOT_SUPPORTED)
        || sqlState.equals(DRDA_REPLY_MSG_NOT_SUPPORTED)) {
      sqle = new SQLFeatureNotSupportedException(getMessage(message, sqlState,
          errCode), sqlState, errCode, t);
    }
    else {
      sqle = new SQLException(getMessage(message, sqlState, errCode), sqlState,
          errCode, t);
    }

    if (next != null) {
      sqle.setNextException(next);
    }
    return sqle;
  }
}
