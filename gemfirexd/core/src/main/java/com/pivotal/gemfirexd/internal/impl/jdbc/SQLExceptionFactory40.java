/*
 
   Derby - Class com.pivotal.gemfirexd.internal.impl.jdbc.SQLExceptionFactory40
 
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at
 
      http://www.apache.org/licenses/LICENSE-2.0
 
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 
 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
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

package com.pivotal.gemfirexd.internal.impl.jdbc;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLFeatureNotSupportedException;
// GemStone changes BEGIN


import com.gemstone.gemfire.distributed.DistributedMember;
import com.pivotal.gemfirexd.internal.iapi.error.DerbySQLException;
// GemStone changes END
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.shared.common.error.DefaultExceptionFactory40;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

/**
 * SQLExceptionFactory40 overwrites getSQLException method
 * to return SQLException or one of its sub class
 */

public class SQLExceptionFactory40 extends SQLExceptionFactory {
    
    /**
     * overwrites super class method to create JDBC4 exceptions      
     * SQLSTATE CLASS (prefix)     Exception
     * 0A                          java.sql.SQLFeatureNotSupportedException
     * 08                          java.sql.SQLNonTransientConnectionException
     * 22                          java.sql.SQLDataException
     * 28                          java.sql.SQLInvalidAuthorizationSpecException
     * 40                          java.sql.SQLTransactionRollbackException
     * 42                          java.sql.SQLSyntaxErrorException
     * 
     * Note the following divergence from JDBC3 behavior: When running
     * a JDBC3 client, we return EmbedSQLException. That exception class
     * overrides Throwable.toString() and strips off the Throwable's class name.
     * In contrast, the following JDBC4 implementation returns
     * subclasses of java.sql.Exception. These subclasses inherit the behavior 
     * of Throwable.toString(). That is, their toString() output includes
     * their class name. This will break code which relies on the
     * stripping behavior of EmbedSQLSxception.toString(). 
     */
    
    public SQLException getSQLException(String message, String messageId,
            SQLException next, int severity, Throwable t, Object[] args) {
        String sqlState = StandardException.getSQLStateFromIdentifier(messageId);

// GemStone changes BEGIN
    if (sqlState.startsWith(SQLState.CONNECTIVITY_PREFIX)) {
      // none of the sqlstate supported by derby belongs to
      // TransientConnectionException DERBY-3074
      return new EmbedSQLNonTransientConnectionException(message, messageId,
          next, severity, t, args);
    }
    else if (sqlState.startsWith(SQLState.SQL_DATA_PREFIX)) {
      return new EmbedSQLDataException(message, messageId, next, severity, t,
          args);
    }
    else if (sqlState.startsWith(SQLState.INTEGRITY_VIOLATION_PREFIX)) {
      return new EmbedSQLIntegrityConstraintViolationException(message,
          messageId, next, severity, t, args);
    }
    else if (sqlState.startsWith(SQLState.AUTHORIZATION_SPEC_PREFIX)
        || sqlState.startsWith(SQLState.AUTH_FAILURE_PREFIX)) {
      return new EmbedSQLInvalidAuthorizationSpecException(message, messageId,
          next, severity, t, args);
    }
    else if (DefaultExceptionFactory40.isTransactionException(sqlState)) {
      return new EmbedSQLTransactionRollbackException(message, messageId, next,
          severity, t, args);
    }
    else if (sqlState.startsWith(SQLState.LSE_COMPILATION_PREFIX)) {
      return new EmbedSQLSyntaxErrorException(message, messageId, next,
          severity, t, args);
    }
    else if (sqlState.startsWith(SQLState.UNSUPPORTED_PREFIX)) {
      return new EmbedSQLFeatureNotSupportedException(message, messageId, next,
          severity, t, args);
    }
    else {
      return new EmbedSQLException(message, messageId, next, severity, t, args);
    }
        /* (original code)
		//
		// Create dummy exception which ferries arguments needed to serialize
		// SQLExceptions across the DRDA network layer.
		//
		t = wrapArgsForTransportAcrossDRDA( message, messageId, next, severity, t, args );

        final SQLException ex;
        if (sqlState.startsWith(SQLState.CONNECTIVITY_PREFIX)) {
            //none of the sqlstate supported by derby belongs to
            //TransientConnectionException DERBY-3074
            ex = new SQLNonTransientConnectionException(message, sqlState,
                    severity, t);
        } else if (sqlState.startsWith(SQLState.SQL_DATA_PREFIX)) {
            ex = new SQLDataException(message, sqlState, severity, t);
        } else if (sqlState.startsWith(SQLState.INTEGRITY_VIOLATION_PREFIX)) {
            ex = new SQLIntegrityConstraintViolationException(message, sqlState,
                    severity, t);
        } else if (sqlState.startsWith(SQLState.AUTHORIZATION_SPEC_PREFIX)) {
            ex = new SQLInvalidAuthorizationSpecException(message, sqlState,
                    severity, t);
        }        
        else if (sqlState.startsWith(SQLState.TRANSACTION_PREFIX)) {
            ex = new SQLTransactionRollbackException(message, sqlState,
                    severity, t);
        } else if (sqlState.startsWith(SQLState.LSE_COMPILATION_PREFIX)) {
            ex = new SQLSyntaxErrorException(message, sqlState, severity, t);
        } else if (sqlState.startsWith(SQLState.UNSUPPORTED_PREFIX)) {
            ex = new SQLFeatureNotSupportedException(message, sqlState, severity, t);
        } else {
            ex = new SQLException(message, sqlState, severity, t);
        }
        
        if (next != null) {
            ex.setNextException(next);
        }
        return ex;
        */
// GemStone changes END
    }        

	/**
	 * <p>
	 * The following method helps handle DERBY-1178. The problem is that we may
	 * need to serialize our final SQLException across the DRDA network layer.
	 * That serialization involves some clever encoding of the Derby messageID and
	 * arguments. Unfortunately, once we create one of the
	 * JDBC4-specific subclasses of SQLException, we lose the messageID and
	 * args. This method creates a dummy EmbedSQLException which preserves that
	 * information. We return the dummy exception.
	 * </p>
	 */
	private	SQLException	wrapArgsForTransportAcrossDRDA
	( String message, String messageId, SQLException next, int severity, Throwable t, Object[] args )
	{
        // Generate an EmbedSQLException
        SQLException e =
            super.getSQLException(message, messageId,
                (next == null ? null : getArgumentFerry(next)),
                severity, t, args);
        return e;
	}

// GemStone changes BEGIN
  // Extensions to JDBC4 SQLException classes that also implement
  // DerbySQLException.
  // This is primarily to avoid too many wrappings (#42595).

  public static class EmbedSQLNonTransientConnectionException extends
      SQLNonTransientConnectionException implements DerbySQLException {

    private static final long serialVersionUID = -8832940935707798936L;

    private Object[] arguments;

    private String messageId;

    private DistributedMember origin;

    /** for serialization */
    public EmbedSQLNonTransientConnectionException() {
    }

    /**
     * Because SQLException does not have settable fields, the caller of the
     * constructor must do message lookup, and pass the appropriate values here
     * for message, messageId, and next exception.
     */
    EmbedSQLNonTransientConnectionException(String message, String messageId,
        SQLException nextException, int severity, Throwable t, Object[] args) {

      super(message, StandardException.getSQLStateFromIdentifier(messageId),
          severity);
      this.messageId = messageId;
      // convert each of the arguments to string and store; this
      // avoids any problems during serialization
      if (args != null) {
        this.arguments = new Object[args.length];
        for (int index = 0; index < args.length; ++index) {
          this.arguments[index] = args[index] != null ? args[index].toString()
              : null;
        }
      }
      else {
        this.arguments = null;
      }
      if (this.origin == null) {
        this.origin = StandardException.getSenderFromException(t);
        if (this.origin == null) {
          this.origin = StandardException.getMyId();
        }
      }
      if (nextException != null) {
        this.setNextException(nextException);
      }

      // if no cause has been specified, let nextException be the cause (this
      // improves error reporting since the cause is included in the output
      // from printStackTrace())
      if (t == null) {
        t = nextException;
      }

      if (t != null) {
        initCause(t);
      }
    }

    public String getMessageId() {
      return messageId;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public boolean isSimpleWrapper() {
      return false;
    }

    public DistributedMember getOrigin() {
      return this.origin;
    }

    /**
     * Override Throwables toString() to avoid the class name appearing in the
     * message.
     */
    public String toString() {
      // We use java.sql.SQLException rather than the default toString(),
      // which returns
      // com.pivotal.gemfirexd.internal.impl.jdbc.EmbedSQLException, so
      // that (a) we're not exposing an internal class name and (b) so
      // this is consistent with the network client, where SQLExceptions
      // are vanilla java.sql classes and not our own subclass
      return "java.sql.SQLNonTransientConnectionException(" + getSQLState()
          + "): " + getMessage();
    }
  }

  public static class EmbedSQLDataException extends SQLDataException implements
      DerbySQLException {

    private static final long serialVersionUID = 3824010447574121870L;

    private Object[] arguments;

    private String messageId;

    private DistributedMember origin;

    /** for serialization */
    public EmbedSQLDataException() {
    }

    /**
     * Because SQLException does not have settable fields, the caller of the
     * constructor must do message lookup, and pass the appropriate values here
     * for message, messageId, and next exception.
     */
    EmbedSQLDataException(String message, String messageId,
        SQLException nextException, int severity, Throwable t, Object[] args) {

      super(message, StandardException.getSQLStateFromIdentifier(messageId),
          severity);
      this.messageId = messageId;
      // convert each of the arguments to string and store; this
      // avoids any problems during serialization
      if (args != null) {
        this.arguments = new Object[args.length];
        for (int index = 0; index < args.length; ++index) {
          this.arguments[index] = args[index] != null ? args[index].toString()
              : null;
        }
      }
      else {
        this.arguments = null;
      }
      if (this.origin == null) {
        this.origin = StandardException.getSenderFromException(t);
        if (this.origin == null) {
          this.origin = StandardException.getMyId();
        }
      }
      if (nextException != null) {
        this.setNextException(nextException);
      }

      // if no cause has been specified, let nextException be the cause (this
      // improves error reporting since the cause is included in the output
      // from printStackTrace())
      if (t == null) {
        t = nextException;
      }

      if (t != null) {
        initCause(t);
      }
    }

    public String getMessageId() {
      return messageId;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public boolean isSimpleWrapper() {
      return false;
    }

    public DistributedMember getOrigin() {
      return this.origin;
    }

    /**
     * Override Throwables toString() to avoid the class name appearing in the
     * message.
     */
    public String toString() {
      // We use java.sql.SQLException rather than the default toString(),
      // which returns
      // com.pivotal.gemfirexd.internal.impl.jdbc.EmbedSQLException, so
      // that (a) we're not exposing an internal class name and (b) so
      // this is consistent with the network client, where SQLExceptions
      // are vanilla java.sql classes and not our own subclass
      return "java.sql.SQLDataException(" + getSQLState() + "): "
          + getMessage();
    }
  }

  public static class EmbedSQLIntegrityConstraintViolationException extends
      SQLIntegrityConstraintViolationException implements DerbySQLException {

    private static final long serialVersionUID = -6619591273592269731L;

    private Object[] arguments;

    private String messageId;

    private DistributedMember origin;

    /** for serialization */
    public EmbedSQLIntegrityConstraintViolationException() {
    }

    /**
     * Because SQLException does not have settable fields, the caller of the
     * constructor must do message lookup, and pass the appropriate values here
     * for message, messageId, and next exception.
     */
    EmbedSQLIntegrityConstraintViolationException(String message,
        String messageId, SQLException nextException, int severity,
        Throwable t, Object[] args) {

      super(message, StandardException.getSQLStateFromIdentifier(messageId),
          severity);
      this.messageId = messageId;
      // convert each of the arguments to string and store; this
      // avoids any problems during serialization
      if (args != null) {
        this.arguments = new Object[args.length];
        for (int index = 0; index < args.length; ++index) {
          this.arguments[index] = args[index] != null ? args[index].toString()
              : null;
        }
      }
      else {
        this.arguments = null;
      }
      if (this.origin == null) {
        this.origin = StandardException.getSenderFromException(t);
        if (this.origin == null) {
          this.origin = StandardException.getMyId();
        }
      }
      if (nextException != null) {
        this.setNextException(nextException);
      }

      // if no cause has been specified, let nextException be the cause (this
      // improves error reporting since the cause is included in the output
      // from printStackTrace())
      if (t == null) {
        t = nextException;
      }

      if (t != null) {
        initCause(t);
      }
    }

    public String getMessageId() {
      return messageId;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public boolean isSimpleWrapper() {
      return false;
    }

    public DistributedMember getOrigin() {
      return this.origin;
    }

    /**
     * Override Throwables toString() to avoid the class name appearing in the
     * message.
     */
    public String toString() {
      // We use java.sql.SQLException rather than the default toString(),
      // which returns
      // com.pivotal.gemfirexd.internal.impl.jdbc.EmbedSQLException, so
      // that (a) we're not exposing an internal class name and (b) so
      // this is consistent with the network client, where SQLExceptions
      // are vanilla java.sql classes and not our own subclass
      return "java.sql.SQLIntegrityConstraintViolationException("
          + getSQLState() + "): " + getMessage();
    }
  }

  public static class EmbedSQLInvalidAuthorizationSpecException extends
      SQLInvalidAuthorizationSpecException implements DerbySQLException {

    private static final long serialVersionUID = 239272541885450068L;

    private Object[] arguments;

    private String messageId;

    private DistributedMember origin;

    /** for serialization */
    public EmbedSQLInvalidAuthorizationSpecException() {
    }

    /**
     * Because SQLException does not have settable fields, the caller of the
     * constructor must do message lookup, and pass the appropriate values here
     * for message, messageId, and next exception.
     */
    EmbedSQLInvalidAuthorizationSpecException(String message, String messageId,
        SQLException nextException, int severity, Throwable t, Object[] args) {

      super(message, StandardException.getSQLStateFromIdentifier(messageId),
          severity);
      this.messageId = messageId;
      // convert each of the arguments to string and store; this
      // avoids any problems during serialization
      if (args != null) {
        this.arguments = new Object[args.length];
        for (int index = 0; index < args.length; ++index) {
          this.arguments[index] = args[index] != null ? args[index].toString()
              : null;
        }
      }
      else {
        this.arguments = null;
      }
      if (this.origin == null) {
        this.origin = StandardException.getSenderFromException(t);
        if (this.origin == null) {
          this.origin = StandardException.getMyId();
        }
      }
      if (nextException != null) {
        this.setNextException(nextException);
      }

      // if no cause has been specified, let nextException be the cause (this
      // improves error reporting since the cause is included in the output
      // from printStackTrace())
      if (t == null) {
        t = nextException;
      }

      if (t != null) {
        initCause(t);
      }
    }

    public String getMessageId() {
      return messageId;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public boolean isSimpleWrapper() {
      return false;
    }

    public DistributedMember getOrigin() {
      return this.origin;
    }

    /**
     * Override Throwables toString() to avoid the class name appearing in the
     * message.
     */
    public String toString() {
      // We use java.sql.SQLException rather than the default toString(),
      // which returns
      // com.pivotal.gemfirexd.internal.impl.jdbc.EmbedSQLException, so
      // that (a) we're not exposing an internal class name and (b) so
      // this is consistent with the network client, where SQLExceptions
      // are vanilla java.sql classes and not our own subclass
      return "java.sql.SQLInvalidAuthorizationSpecException(" + getSQLState()
          + "): " + getMessage();
    }
  }

  public static class EmbedSQLTransactionRollbackException extends
      SQLTransactionRollbackException implements DerbySQLException {

    private static final long serialVersionUID = -1880056564903119733L;

    private Object[] arguments;

    private String messageId;

    private DistributedMember origin;

    /** for serialization */
    public EmbedSQLTransactionRollbackException() {
    }

    /**
     * Because SQLException does not have settable fields, the caller of the
     * constructor must do message lookup, and pass the appropriate values here
     * for message, messageId, and next exception.
     */
    EmbedSQLTransactionRollbackException(String message, String messageId,
        SQLException nextException, int severity, Throwable t, Object[] args) {

      super(message, StandardException.getSQLStateFromIdentifier(messageId),
          severity);
      this.messageId = messageId;
      // convert each of the arguments to string and store; this
      // avoids any problems during serialization
      if (args != null) {
        this.arguments = new Object[args.length];
        for (int index = 0; index < args.length; ++index) {
          this.arguments[index] = args[index] != null ? args[index].toString()
              : null;
        }
      }
      else {
        this.arguments = null;
      }
      if (this.origin == null) {
        this.origin = StandardException.getSenderFromException(t);
        if (this.origin == null) {
          this.origin = StandardException.getMyId();
        }
      }
      if (nextException != null) {
        this.setNextException(nextException);
      }

      // if no cause has been specified, let nextException be the cause (this
      // improves error reporting since the cause is included in the output
      // from printStackTrace())
      if (t == null) {
        t = nextException;
      }

      if (t != null) {
        initCause(t);
      }
    }

    public String getMessageId() {
      return messageId;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public boolean isSimpleWrapper() {
      return false;
    }

    public DistributedMember getOrigin() {
      return this.origin;
    }

    /**
     * Override Throwables toString() to avoid the class name appearing in the
     * message.
     */
    public String toString() {
      // We use java.sql.SQLException rather than the default toString(),
      // which returns
      // com.pivotal.gemfirexd.internal.impl.jdbc.EmbedSQLException, so
      // that (a) we're not exposing an internal class name and (b) so
      // this is consistent with the network client, where SQLExceptions
      // are vanilla java.sql classes and not our own subclass
      return "java.sql.SQLTransactionRollbackException(" + getSQLState()
          + "): " + getMessage();
    }
  }

  public static class EmbedSQLSyntaxErrorException extends SQLSyntaxErrorException
      implements DerbySQLException {

    private static final long serialVersionUID = 5978011241555870084L;

    private Object[] arguments;

    private String messageId;

    private DistributedMember origin;

    /** for serialization */
    public EmbedSQLSyntaxErrorException() {
    }

    /**
     * Because SQLException does not have settable fields, the caller of the
     * constructor must do message lookup, and pass the appropriate values here
     * for message, messageId, and next exception.
     */
    EmbedSQLSyntaxErrorException(String message, String messageId,
        SQLException nextException, int severity, Throwable t, Object[] args) {

      super(message, StandardException.getSQLStateFromIdentifier(messageId),
          severity);
      this.messageId = messageId;
      // convert each of the arguments to string and store; this
      // avoids any problems during serialization
      if (args != null) {
        this.arguments = new Object[args.length];
        for (int index = 0; index < args.length; ++index) {
          this.arguments[index] = args[index] != null ? args[index].toString()
              : null;
        }
      }
      else {
        this.arguments = null;
      }
      if (this.origin == null) {
        this.origin = StandardException.getSenderFromException(t);
        if (this.origin == null) {
          this.origin = StandardException.getMyId();
        }
      }
      if (nextException != null) {
        this.setNextException(nextException);
      }

      // if no cause has been specified, let nextException be the cause (this
      // improves error reporting since the cause is included in the output
      // from printStackTrace())
      if (t == null) {
        t = nextException;
      }

      if (t != null) {
        initCause(t);
      }
    }

    public String getMessageId() {
      return messageId;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public boolean isSimpleWrapper() {
      return false;
    }

    public DistributedMember getOrigin() {
      return this.origin;
    }

    /**
     * Override Throwables toString() to avoid the class name appearing in the
     * message.
     */
    public String toString() {
      // We use java.sql.SQLException rather than the default toString(),
      // which returns
      // com.pivotal.gemfirexd.internal.impl.jdbc.EmbedSQLException, so
      // that (a) we're not exposing an internal class name and (b) so
      // this is consistent with the network client, where SQLExceptions
      // are vanilla java.sql classes and not our own subclass
      return "java.sql.SQLSyntaxErrorException(" + getSQLState() + "): "
          + getMessage();
    }
  }

  public static class EmbedSQLFeatureNotSupportedException extends
      SQLFeatureNotSupportedException implements DerbySQLException {

    private static final long serialVersionUID = 879447537063698632L;

    private Object[] arguments;

    private String messageId;

    private DistributedMember origin;

    /** for serialization */
    public EmbedSQLFeatureNotSupportedException() {
    }

    /**
     * Because SQLException does not have settable fields, the caller of the
     * constructor must do message lookup, and pass the appropriate values here
     * for message, messageId, and next exception.
     */
    EmbedSQLFeatureNotSupportedException(String message, String messageId,
        SQLException nextException, int severity, Throwable t, Object[] args) {

      super(message, StandardException.getSQLStateFromIdentifier(messageId),
          severity);
      this.messageId = messageId;
      // convert each of the arguments to string and store; this
      // avoids any problems during serialization
      if (args != null) {
        this.arguments = new Object[args.length];
        for (int index = 0; index < args.length; ++index) {
          this.arguments[index] = args[index] != null ? args[index].toString()
              : null;
        }
      }
      else {
        this.arguments = null;
      }
      if (this.origin == null) {
        this.origin = StandardException.getSenderFromException(t);
        if (this.origin == null) {
          this.origin = StandardException.getMyId();
        }
      }
      if (nextException != null) {
        this.setNextException(nextException);
      }

      // if no cause has been specified, let nextException be the cause (this
      // improves error reporting since the cause is included in the output
      // from printStackTrace())
      if (t == null) {
        t = nextException;
      }

      if (t != null) {
        initCause(t);
      }
    }

    public String getMessageId() {
      return messageId;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public boolean isSimpleWrapper() {
      return false;
    }

    public DistributedMember getOrigin() {
      return this.origin;
    }

    /**
     * Override Throwables toString() to avoid the class name appearing in the
     * message.
     */
    public String toString() {
      // We use java.sql.SQLException rather than the default toString(),
      // which returns
      // com.pivotal.gemfirexd.internal.impl.jdbc.EmbedSQLException, so
      // that (a) we're not exposing an internal class name and (b) so
      // this is consistent with the network client, where SQLExceptions
      // are vanilla java.sql classes and not our own subclass
      return "java.sql.SQLFeatureNotSupportedException(" + getSQLState()
          + "): " + getMessage();
    }
  }
// GemStone changes END
}
