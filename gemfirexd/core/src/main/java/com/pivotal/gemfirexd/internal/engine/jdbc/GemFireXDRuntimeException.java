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

package com.pivotal.gemfirexd.internal.engine.jdbc;

import java.sql.SQLException;

import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.shared.CommonRunTimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;

/**
 * Generic exception, indicating GemFireXD runtime exceptions.
 * 
 * @author soubhikc
 * @author swale
 * @since GemFireXD
 */
public final class GemFireXDRuntimeException extends RuntimeException implements
    CommonRunTimeException {

  // //////////////////// Constructors //////////////////////

  private static final long serialVersionUID = -4374956473222420893L;

  /**
   * The distributed member that caused this exception, if any.
   */
  private DistributedMember origin;

  /**
   * Creates a new <code>GemFireXDRuntimeException</code> with no detailed
   * message.
   */
  public GemFireXDRuntimeException() {
    super();
  }

  /**
   * Creates a new <code>GemFireXDRuntimeException</code> with the given detail
   * message.
   */
  public GemFireXDRuntimeException(String message) {
    super(appendMyOrigin(message));
  }

  /**
   * Creates a new <code>GemFireXDRuntimeException</code> with the given detail
   * message and cause.
   */
  private GemFireXDRuntimeException(String message, Throwable cause) {
    super(appendMyOrigin(message), cause);
  }

  /**
   * Creates a new <code>GemFireXDRuntimeException</code> with the given cause
   * and no detail message
   */
  public GemFireXDRuntimeException(Throwable cause) {
    this(null, cause);
  }

  private static String appendMyOrigin(String message) {
    try {
      final DistributedMember myId = StandardException.getMyId();
      return (message != null ? (message + ", myID: " + myId)
          : ("myID: " + myId));
    } catch (Exception ex) {
      return message;
    }
  }

  /**
   * If the given exception is a RuntimeException or Error then return as such
   * else wrap the exception in {@link GemFireXDRuntimeException} and throw.
   */
  public static RuntimeException newRuntimeException(String message,
      Throwable t) {
    try {
      return getOrThrowSQLException(t, false, message);
    } catch (StandardException | SQLException ex) {
      // will never happen
    }
    // will never be reached
    throw new GemFireXDRuntimeException(message, t);
  }

  /**
   * If the given exception is a SQLException, StandardException,
   * RuntimeException or Error then throw as such else wrap the exception in
   * {@link GemFireXDRuntimeException} and throw.
   */
  public static void throwSQLOrRuntimeException(String message, Throwable t)
      throws StandardException, SQLException {
    throw getOrThrowSQLException(t, true, message);
  }

  private static GemFireXDRuntimeException createRuntimeException(
      String message, Throwable t) {
    if (message != null) {
      return new GemFireXDRuntimeException(message, t);
    }
    else {
      return new GemFireXDRuntimeException(t);
    }
  }

  protected static RuntimeException getOrThrowSQLException(Throwable ex,
      boolean throwSQLEx, String wrapMsg) throws StandardException,
      SQLException {
    Throwable t = ex;
    while (t != null) {
      if (t instanceof SQLException) {
        final SQLException sqlEx = (SQLException)t;
        t = StandardException.getJavaException(sqlEx, sqlEx.getSQLState());
        if (t == null) {
          if (throwSQLEx) {
            throw sqlEx;
          }
          else {
            return createRuntimeException(wrapMsg, sqlEx);
          }
        }
      }
      else if (t instanceof StandardException) {
        final StandardException sqlEx = (StandardException)t;
        t = StandardException.getJavaException(sqlEx, sqlEx.getSQLState());
        if (t == null) {
          if (throwSQLEx) {
            throw sqlEx;
          }
          else {
            return createRuntimeException(wrapMsg, sqlEx);
          }
        }
      }
      t = t.getCause();
    }
    if (ex instanceof RuntimeException) {
      return (RuntimeException)ex;
    }
    else if (ex instanceof Error) {
      throw (Error)ex;
    }
    return createRuntimeException(wrapMsg, ex);
  }

  //////////////////// Instance Methods ////////////////////

  public final void setOrigin(DistributedMember member) {
    this.origin = member;
  }

  public final DistributedMember getOrigin() {
    return this.origin;
  }

  /**
   * Returns the root cause of this <code>GemFireXDRuntimeException</code> or
   * <code>null</code> if the cause is nonexistent or unknown.
   */
  public Throwable getRootCause() {
    if (this.getCause() == null) {
      return null;
    }
    Throwable root = this.getCause();
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root;
  }

  @Override
  public String toString() {
    String result = super.toString();
    Throwable cause = getCause();
    String originStr = "";
    if (this.origin != null) {
      originStr = ", on member " + this.origin.toString();
    }
    if (cause != null) {
      String causeStr = cause.toString();
      final String glue = ", caused by ";
      final StringBuilder sb = new StringBuilder(result.length()
          + originStr.length() + causeStr.length() + glue.length());
      sb.append(result).append(originStr).append(glue).append(causeStr);
      result = sb.toString();
    }
    return result;
  }

  @Override
  public RuntimeException newRunTimeException(String msg, Throwable cause) {
    return newRuntimeException(msg, cause);
  }
}
