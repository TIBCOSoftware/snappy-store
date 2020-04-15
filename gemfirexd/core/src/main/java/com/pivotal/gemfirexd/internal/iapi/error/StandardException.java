/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.error.StandardException

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

package com.pivotal.gemfirexd.internal.iapi.error;

import java.sql.SQLException;
import java.sql.SQLWarning;

import com.gemstone.gemfire.GemFireCheckedException;
import com.gemstone.gemfire.GemFireException;
// GemStone changes BEGIN
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.membership.
    InternalDistributedMember;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
// GemStone changes END
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

/**
	StandardException is the root of all exceptions that are handled
	in a standard fashion by the database code, mainly in the language code.
	<P>
    This class is abstract to ensure that an implementation only throws
	a specific exception (e.g. TransactionException) which is a sub-class
	<P>
	A method in an iterface in a protocol under com.ibm.db2j.protocol.Database must
	only throw a StandardException (if it needs to throw an exception).
	This indicates that the method can throw an exception and therefore its
	caller must ensure that any resources it allocates will be cleaned up
	in the event of an exception in the StandardException hierarchy.
	<P>
	Implementations of methods that throw StandardException can have throws
	clause that are more specific than StandardException.
*/

public class StandardException extends Exception 
{
	public static final int REPORT_DEFAULT = 0;
	public static final int REPORT_NEVER = 1;
	public static final int REPORT_ALWAYS = 2;

	/*
	 * Exception State
	 */
// GemStone changes BEGIN
	private static final long serialVersionUID = -5141884744265493120L;

	// removed transient for proper serialization
	private Object[] arguments;

	private DistributedMember origin;

	public static Object[] changeArgumentsForTransport(Object[] args) {
	  // convert each of the arguments to string and store; this
	  // avoids any problems during serialization
	  if (args != null) {
	    final Object[] arguments = new Object[args.length];
	    for (int index = 0; index < args.length; ++index) {
	      arguments[index] = args[index] != null
	                         ? args[index].toString() : null;
	    }
	    return arguments;
	  }
	  return null;
	}

	public final void changeArgumentsForTransport() {
	  this.arguments = changeArgumentsForTransport(this.arguments);
	}

	public static InternalDistributedMember getMyId() {
	  return GemFireStore.getMyId();
	}

	public static DistributedMember getSenderFromException(Throwable t) {
	  DistributedMember member = null;
	  // try to find remote member from some wrapped exception
	  while (t != null && member == null) {
	    if (t instanceof ReplyException) {
	      member = ((ReplyException)t).getSender();
	    }
	    else if (t instanceof GemFireXDRuntimeException) {
	      member = ((GemFireXDRuntimeException)t).getOrigin();
	    }
	    else if (t instanceof StandardException) {
	      member = ((StandardException)t).getOrigin();
	    }
	    else if (t instanceof DerbySQLException) {
	      member = ((DerbySQLException)t).getOrigin();
	    }
	    else if (t instanceof GemFireException) {
	      member = ((GemFireException)t).getOrigin();
	    }
	    else if (t instanceof GemFireCheckedException) {
	      member = ((GemFireCheckedException)t).getOrigin();
	    }
	    t = t.getCause();
	  }
	  return member;
	}

        public static DistributedMember getSenderFromExceptionOrSelf(
            Throwable t) {
          DistributedMember member = getSenderFromException(t);
          if (member != null) {
            return member;
          }
          else {
            return getMyId();
          }
        }

	public static DistributedMember fixUpRemoteException(
	    final Throwable remoteEx, DistributedMember member) {
	  // try to find remote member from some wrapped exception
	  if (member == null) {
	    member = getSenderFromException(remoteEx);
	  }
	  if (member != null) {
	    ReplyException.fixUpRemoteEx(remoteEx, member);
	  }
	  return member;
	}

	/**
	 * If the given exception is a StandardException having the passed
	 * SQLState ("sqlState") which is {@link SQLState#JAVA_EXCEPTION} then
	 * get the underlying java exception else return null.
	 */
	public static Throwable getJavaException(Throwable t, String sqlState) {
	  final String javaExState = getSQLStateFromIdentifier(
	      SQLState.JAVA_EXCEPTION);
	  if (javaExState.equals(sqlState)) {
	    while ((t instanceof SQLException)
	        || (t instanceof StandardException)) {
	      t = t.getCause();
	    }
	    return t;
	  }
	  return null;
	}

	public static StandardException newJavaException(Throwable t,
	    String msg) {
	  if (t instanceof StandardException) {
	    return (StandardException)t;
	  }
	  else if (t instanceof SQLException) {
	    SQLException sqle = (SQLException)t;
	    return Misc.wrapSQLException(sqle, sqle);
	  }
	  if (msg == null) {
	    msg = t.getMessage();
	  }
	  if (msg == null) {
	    msg = "";
	  }
	  return newException(SQLState.JAVA_EXCEPTION, t,
	      t.getClass().getName(), msg);
        }

	public final DistributedMember getOrigin() {
	  return this.origin;
	}

	public final boolean isRemote() {
	  return this.origin != GemFireStore.getMyId();
	}

	public static StandardException newException(String messageID,
	    Throwable t, Object[] args) {
	  return new StandardException(messageID, t, args);
	}
	/* (original code)
	 private transient Object[] arguments;
	 */
// GemStone changes END
	private int severity;
	private String textMessage;
	private String sqlState;
	private transient int report;

	/*
	** End of constructors
	*/
	
	protected StandardException(String messageID)
	{
		this(messageID, (Throwable) null, (Object[]) null);

	}

	protected StandardException(String messageID, Object[] args)
	{
		this(messageID, (Throwable) null, args);
	}

	protected StandardException(String messageID, Throwable t, Object[] args)
	{
		super(messageID);

		this.severity = getSeverityFromIdentifier(messageID);
		this.sqlState = getSQLStateFromIdentifier(messageID);
		this.arguments = args;
		if (t != null) {
			initCause(t);
		}
// GemStone changes BEGIN
		if (this.origin == null) {
		  this.origin = getSenderFromExceptionOrSelf(t);
		}
// GemStone changes END

		if (SanityManager.DEBUG)
		{
                    SanityManager.ASSERT(messageID != null,
                                         "StandardException with no messageID");
		}
	}

	/**
	 * This constructor is used when we already have the
	 * message text.
	 * 
	 * @param sqlState the sql state of the message
	 * @param text the text of the message
	 */
	private StandardException(String sqlState, String text)
	{
		this(sqlState);
		textMessage = text;
	}

	/*
	** End of constructors
	*/
	/**
	 * Sets the arguments for this exception.
	 */
// GemStone changes BEGIN
	public final void setArguments(Object[] arguments) {
	  this.arguments = arguments;
	  // refresh the message
	  if (this.textMessage != null) {
	    this.textMessage = null;
	  }
	/* (original code)
	private final void setArguments(Object[] arguments)
	{
		this.arguments = arguments;
	*/
// GemStone changes END
	}

	/**
	 * Returns the arguments for this exception,
	 * if there are any.
	 */
	public final Object[] getArguments()
	{
		return arguments;
	}

	/**
		Yes, report me. Errors that need this method to return
		false are in the minority.
	*/
	public final int report() {
		return report;
	}

	/**
		Set my report type.
	*/
	public final void setReport(int report) {
		this.report = report;
	}

	public final void setSeverity(int severity) {
		this.severity = severity;
	}


	public final int getSeverity() {
		return severity;
	}

	public final int getErrorCode() {
		return severity;
	}

	/**
		Return the 5 character SQL State.
		If you need the identifier that was used to create the
		message, then use getMessageId(). getMessageId() will return the
		string that corresponds to the field in com.pivotal.gemfirexd.internal.iapi.reference.SQLState.
	*/
	public final String getSQLState()
	{
		return sqlState;
	}

	/**
		Convert a message identifer from com.pivotal.gemfirexd.internal.iapi.reference.SQLState to
		a SQLState five character string.
	 *	@param messageID - the sql state id of the message from Derby
	 *	@return String 	 - the 5 character code of the SQLState ID to returned to the user 
	*/
	public static String getSQLStateFromIdentifier(String messageID) {

		if (messageID.length() == 5)
			return messageID;
		return messageID.substring(0, 5);
	}

	/**
		Get the severity given a message identifier from com.pivotal.gemfirexd.internal.iapi.reference.SQLState.
	*/
	public static int getSeverityFromIdentifier(String messageID) {

		int lseverity = ExceptionSeverity.NO_APPLICABLE_SEVERITY;

		if (messageID.length() == 5) {
			switch (messageID.charAt(0)) {
			case '0':
				switch (messageID.charAt(1)) {
				case '1':
					lseverity = ExceptionSeverity.WARNING_SEVERITY;
					break;
				case 'A':
				case '7':
					lseverity = ExceptionSeverity.STATEMENT_SEVERITY;
					break;
				case '8':
					lseverity = ExceptionSeverity.SESSION_SEVERITY;
					break;
				}
				break;
			case '2':
			case '3':
				lseverity = ExceptionSeverity.STATEMENT_SEVERITY;
				break;
			case '4':
				switch (messageID.charAt(1)) {
				case '0':
					lseverity = ExceptionSeverity.TRANSACTION_SEVERITY;
					break;
				case '2':
					lseverity = ExceptionSeverity.STATEMENT_SEVERITY;
					break;
				}
				break;
			}
		}
		if (messageID.length() >= 7) {
			switch (messageID.charAt(6)) {
			case 'M':
				lseverity = ExceptionSeverity.SYSTEM_SEVERITY;
				break;
			case 'D':
				lseverity = ExceptionSeverity.DATABASE_SEVERITY;
				break;
			case 'C':
				lseverity = ExceptionSeverity.SESSION_SEVERITY;
				break;
			case 'T':
				lseverity = ExceptionSeverity.TRANSACTION_SEVERITY;
				break;
			case 'S':
				lseverity = ExceptionSeverity.STATEMENT_SEVERITY;
				break;
			case 'U':
				lseverity = ExceptionSeverity.NO_APPLICABLE_SEVERITY;
				break;
			}
		}

		return lseverity;
	}

	/*
	** Set of static methods to obtain exceptions.
	**
	** Possible parameters:
	** String sqlState - SQL State
	** int severity - Severity of message
	** Throwable t - exception to wrap
	** Object aN - argument to error message
	**
	** Calls that can be made after the exception has been created.
	**
	** setExceptionCategory()
	** setReport()
	*/

	/* specific exceptions */

	public	static	StandardException	normalClose()
	{
		StandardException	se = newException( SQLState.NORMAL_CLOSE );
		se.report = REPORT_NEVER;
		return se;
	}

	/* 0 arguments */

	public static StandardException newException(String messageID) {
		return new StandardException(messageID);
	}
	public static StandardException newException(String messageID, Throwable t) {
		return new StandardException(messageID, t, (Object[]) null);
	}

	/* 1 argument */

	public static StandardException newException(String messageID, Object a1) {
		Object[] oa = new Object[] {a1};
		return new StandardException(messageID, oa);
	}

	public static StandardException newException(String messageID,
												 Object[] a1) {
		return new StandardException(messageID, a1);
	}

	public static StandardException newException(String messageID, Throwable t, Object a1) {
		Object[] oa = new Object[] {a1};
		return new StandardException(messageID, t, oa);
	}

	/* 2 arguments */

	public static StandardException newException(String messageID, Object a1, Object a2) {
		Object[] oa = new Object[] {a1, a2};
		return new StandardException(messageID, oa);
	}

    /**
     * Dummy exception to catch incorrect use of
     * StandardException.newException(), at compile-time. If you get a
     * compilation error because this exception isn't caught, it means
     * that you are using StandardException.newException(...)
     * incorrectly. The nested exception should always be the second
     * argument.
     * @see StandardException#newException(String, Object, Throwable)
     * @see StandardException#newException(String, Object, Object, Throwable)
     */
    public static class BadMessageArgumentException extends Throwable {}

    /**
     * Dummy overload which should never be called. Only used to
     * detect incorrect usage, at compile time.
     * @param messageID - the sql state id of the message
     * @param a1 - Message arg
     * @param t - Incorrectly placed exception to be nested
     * @return nothing - always throws
     * @throws BadMessageArgumentException - always (dummy)
     */
    public static StandardException newException(String messageID, 
                                                 Object a1, 
                                                 Throwable t) 
        throws BadMessageArgumentException {
        throw new BadMessageArgumentException();
    }

	public static StandardException newException(String messageID, Throwable t, Object a1, Object a2) {
		Object[] oa = new Object[] {a1, a2};
		return new StandardException(messageID, t, oa);
	}

	/* 3 arguments */

	public static StandardException newException(String messageID, Object a1, Object a2, Object a3) {
		Object[] oa = new Object[] {a1, a2, a3};
		return new StandardException(messageID, oa);
	}
    
    /**
     * Dummy overload which should never be called. Only used to
     * detect incorrect usage, at compile time.
     * @param messageID - the sql state id of the message
     * @param a1 - First message arg
     * @param a2 - Second message arg
     * @param t - Incorrectly placed exception to be nested
     * @return nothing - always throws
     * @throws BadMessageArgumentException - always (dummy)
     */
    public static StandardException newException(String messageID, 
                                                 Object a1, 
                                                 Object a2,
                                                 Throwable t) 
        throws BadMessageArgumentException {
        throw new BadMessageArgumentException(); 
    }

	public static StandardException newException(String messageID, Throwable t, Object a1, Object a2, Object a3) {
		Object[] oa = new Object[] {a1, a2, a3};
		return new StandardException(messageID, t, oa);
	}

	/* 4 arguments */

	public static StandardException newException(String messageID, Object a1, Object a2, Object a3, Object a4) {
		Object[] oa = new Object[] {a1, a2, a3, a4};
		return new StandardException(messageID, oa);
	}
	public static StandardException newException(String messageID, Throwable t, Object a1, Object a2, Object a3, Object a4) {
		Object[] oa = new Object[] {a1, a2, a3, a4};
		return new StandardException(messageID, t, oa);
	}
 
	/* 5 arguments */
	public static StandardException newException(String messageID, Object a1, Object a2, Object a3, Object a4, Object a5) {
		Object[] oa = new Object[] {a1, a2, a3, a4, a5};
		return new StandardException(messageID, oa);
	}
	public static StandardException newException(String messageID, Throwable t, Object a1, Object a2, Object a3, Object a4, Object a5) {
		Object[] oa = new Object[] {a1, a2, a3, a4, a5};
		return new StandardException(messageID, t, oa);
	}

	/* 6 arguments */
	public static StandardException newException(String messageID, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
		Object[] oa = new Object[] {a1, a2, a3, a4, a5, a6};
		return new StandardException(messageID, oa);
	}
	public static StandardException newException(String messageID, Throwable t, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
		Object[] oa = new Object[] {a1, a2, a3, a4, a5, a6};
		return new StandardException(messageID, t, oa);
	}

	/* 7 arguments */
	public static StandardException newException(String messageID, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
		Object[] oa = new Object[] {a1, a2, a3, a4, a5, a6, a7};
		return new StandardException(messageID, oa);
	}
	public static StandardException newException(String messageID, Throwable t, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
		Object[] oa = new Object[] {a1, a2, a3, a4, a5, a6, a7};
		return new StandardException(messageID, t, oa);
	}

	/* 8 arguments */
	public static StandardException newException(String messageID, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8) {
		Object[] oa = new Object[] {a1, a2, a3, a4, a5, a6, a7, a8};
		return new StandardException(messageID, oa);
	}
	public static StandardException newException(String messageID, Throwable t, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8) {
		Object[] oa = new Object[] {a1, a2, a3, a4, a5, a6, a7, a8};
		return new StandardException(messageID, t, oa);
	}

    /**
     * Creates a new StandardException using message text that has already been localized.
     *
     * @param MessageID The SQLState and severity are derived from the ID. However the text message is not.
     * @param t The Throwable that caused this exception, null if this exception was not caused by another Throwable.
     * @param localizedMessage The message associated with this exception.
     *        <b>It is the caller's responsibility to ensure that this message is properly localized.</b>
     *
     * See com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource
     */
    public static StandardException newPreLocalizedException( String MessageID,
                                                              Throwable t,
                                                              String localizedMessage)
    {
// GemStone changes BEGIN
        StandardException se;
        if (MessageID != null) {
          se = new StandardException(MessageID, localizedMessage);
        }
        else {
          if (t == null) {
            t = new RuntimeException(localizedMessage);
          }
          se = newException(SQLState.JAVA_EXCEPTION, t.getClass().getName(),
              localizedMessage != null ? localizedMessage : t.getMessage());
        }
        /* (original code)
        StandardException se = new StandardException( MessageID, localizedMessage);
        */
// GemStone changes END
        if( t != null)
            se.initCause(t);
        return se;
    }
    
    
	/**
	 * Unpack the exception, looking for an DerbySQLException, which carries
	 * the Derby messageID and arguments. 
	 * @see com.pivotal.gemfirexd.internal.impl.jdbc.SQLExceptionFactory
	 * @see com.pivotal.gemfirexd.internal.impl.jdbc.SQLExceptionFactory40
	 * @see com.pivotal.gemfirexd.internal.impl.jdbc.Util
	 */
	public static SQLException	getArgumentFerry(SQLException se)
	{
		if (se instanceof DerbySQLException) {
			/*
			 * Cater for pre-JDBC4 scenario.
			 */
			return se;
		}
		/*
		 * See DERBY-1178 for background information.
		 * In JDBC4, the DerbySQLException may be wrapped by a SQLException.
		 */
		Throwable	cause = se.getCause();

		if ( (cause == null) || !(cause instanceof DerbySQLException ))	{ return se; }
		else	{ return (SQLException) cause; }
	}


	public static StandardException unexpectedUserException(Throwable t)
	{
        // If the exception is an SQLException generated by Derby, it has an
        // argument ferry which is an DerbySQLException. Use this to check
        // whether the exception was generated by Derby.
        DerbySQLException ferry = null;
        if (t instanceof SQLException) {
            SQLException sqle =
                getArgumentFerry((SQLException) t);
            if (sqle instanceof DerbySQLException) {
                ferry = (DerbySQLException) sqle;
            }
        }
        
		/*
		** If we have a SQLException that isn't an EmbedSQLException
		** (i.e. it didn't come from Derby), then we check
		** to see if it is a valid user defined exception range 
		** (38001-38XXX).  If so, then we convert it into a 
		** StandardException without further ado.
		*/ 
		if ((t instanceof SQLException) && (ferry == null))
		{
			SQLException sqlex  = (SQLException)t;
			String state = sqlex.getSQLState();
			if ((state != null) && 
				(state.length() == 5) &&
				state.startsWith("38") &&
				!state.equals("38000"))
			{
				StandardException se = new StandardException(state, sqlex.getMessage());
				if (sqlex.getNextException() != null)		
				{	
					se.initCause(sqlex.getNextException());
				}
				return se;
			}
		}

		// Look for simple wrappers for 3.0.1 - will be cleaned up in main
		if (ferry != null) {
			if (ferry.isSimpleWrapper()) {
				Throwable wrapped = ((SQLException)ferry).getCause();
				if (wrapped instanceof StandardException)
					return (StandardException) wrapped;
			}
		}


		// no need to wrap a StandardException
		if (t instanceof StandardException) 
		{
			return (StandardException) t;
		}
		else
		{
			/*
			** 
			** The exception at this point could be a:
			**
			**    standard java exception, e.g. NullPointerException
			**    SQL Exception - from some server-side JDBC
			**    3rd party exception - from some application
			**    some Derby exception that is not a standard exception.
			**    
			**    
			** We don't want to call t.toString() here, because the JVM is
			** inconsistent about whether it includes a detail message
			** with some exceptions (esp. NullPointerException).  In those
			** cases where there is a detail message, t.toString() puts in
			** a colon character, even when the detail message is blank.
			** So, we do our own string formatting here, including the colon
			** only when there is a non-blank message.
			**
			** The above is because our test canons contain the text of
			** error messages.
			** 
			** In the past we didn't want to place the class name in
			** an exception because Cloudscape builds were
			** obfuscated, so the class name would change from build
                        ** to build. This is no longer true for Derby, but for
			** exceptions that are Derby's, i.e. EmbedSQLException,
			** we use toString(). If this returns an empty or null
			** then we use the class name to make tracking the 
                        ** problem down easier, though the lack of a message 
			** should be seen as a bug.
			*/
			String	detailMessage;
			boolean derbyException = false;

			if (ferry != null) {
				detailMessage = ferry.toString();
				derbyException = true;
			}
			else {
				detailMessage = t.getMessage();
			}

			if (detailMessage == null)
			{
				detailMessage = "";
			} else {
				detailMessage = detailMessage.trim();
			}

			// if no message, use the class name
			if (detailMessage.length() == 0) {
				detailMessage = t.getClass().getName();
			}
			else {

				if (!derbyException) {
					detailMessage = t.getClass().getName() + ": " + detailMessage;
				}
			}

			StandardException se =
				newException(SQLState.LANG_UNEXPECTED_USER_EXCEPTION, t, detailMessage);
			return se;
		}
	}

	/**
		Similar to unexpectedUserException but makes no assumtion about
		when the execption is being called. The error is wrapped as simply
		as possible.
	*/

	public static StandardException plainWrapException(Throwable t) {

		if (t instanceof StandardException)
			return (StandardException) t;

		if (t instanceof SQLException) {

			SQLException sqle = (SQLException) t;

			String sqlState = sqle.getSQLState();
			if (sqlState != null) {

				StandardException se = new StandardException(sqlState, "(" + sqle.getErrorCode()  + ") " + sqle.getMessage());
				sqle = sqle.getNextException();
				if (sqle != null)
					se.initCause(plainWrapException(sqle));
				return se;
			}
		}

		String	detailMessage = t.getMessage();

		if (detailMessage == null)
		{
			detailMessage = "";
		} else {
			detailMessage = detailMessage.trim();
		}
		
		StandardException se =
				newException(SQLState.JAVA_EXCEPTION, t, detailMessage, t.getClass().getName());
		return se;
	}

	/**
	** A special exception to close a session.
	*/
	public static StandardException closeException() {
		StandardException se = newException(SQLState.CLOSE_REQUEST);
		se.setReport(REPORT_NEVER);
		return se;
	}
	/*
	** Message handling
	*/

	/**
		The message stored in the super class Throwable must be set
		up object creation. At this time we cannot get any information
		about the object itself (ie. this) in order to determine the
		natural language message. Ie. we need to class of the objec in
		order to look up its message, but we can't get the class of the
		exception before calling the super class message.
		<P>
		Thus the message stored by Throwable and obtained by the
		getMessage() of Throwable (ie. super.getMessage() in this
		class) is the message identifier. The actual text message
		is stored in this class at the first request.

	*/

	public String getMessage() {
		if (textMessage == null)
			textMessage = MessageService.getCompleteMessage(getMessageId(), getArguments());

		return textMessage;
	}

	/**
		Return the message identifier that is used to look up the
		error message text in the messages.properties file.
	*/
	public final String getMessageId() {
		return super.getMessage();
	}


	/**
		Get the error code for an error given a type. The value of
		the property messageId.type will be returned, e.g.
		deadlock.sqlstate.
	*/
	public String getErrorProperty(String type) {
		return getErrorProperty(getMessageId(), type);
	}

	/**
		Don't print the class name in the toString() method.
	*/
	public String toString() {
		String msg = getMessage();

		return "ERROR " + getSQLState() + ": " + msg;
	}

	/*
	** Static methods
	*/

	private static String getErrorProperty(String messageId, String type) {
		return MessageService.getProperty(messageId, type);
	}

	public static StandardException interrupt(InterruptedException ie) {
		StandardException se = StandardException.newException(SQLState.CONN_INTERRUPT, ie);
		return se;
	}
	/*
	** SQL warnings
	*/

	public static SQLWarning newWarning(String messageId) {

		return newWarningCommon( messageId, (Object[]) null );

	}

	public static SQLWarning newWarning(String messageId, Object a1) {

		Object[] oa = new Object[] {a1};

		return newWarningCommon( messageId, oa );
	}

	public static SQLWarning newWarning(String messageId, Object a1, Object a2) {

		Object[] oa = new Object[] {a1, a2};

		return newWarningCommon( messageId, oa );
	}

// GemStone changes BEGIN
	public static SQLWarning newWarning(String messageId, Object[] args,
	    Throwable cause) {
	  final SQLWarning warning = newWarningCommon(messageId, args);
	  if (cause != null) {
	    warning.initCause(cause);
	  }
	  return warning;
	}

// GemStone changes END
	static final ThreadLocal<SQLWarning> noRowFound  =
	    new ThreadLocal<SQLWarning>();

	public static SQLWarning newNoRowFoundWarning() {
	  // this thread-local stuff is to avoid the brain-dead big sync
	  // in DriverManager.println call from SQLWarning constructor
	  SQLWarning sqlw = noRowFound.get();
	  if (sqlw == null) {
	    sqlw = newWarning(SQLState.LANG_NO_ROW_FOUND);
	    noRowFound.set(sqlw);
	  }
	  else {
	    sqlw.setNextWarning(null);
	  }
	  return sqlw;
	}

	private	static	SQLWarning	newWarningCommon( String messageId, Object[] oa )
	{
		String		message = MessageService.getCompleteMessage(messageId, oa);
		String		state = StandardException.getSQLStateFromIdentifier(messageId);
		SQLWarning	sqlw = new SQLWarning(message, state, ExceptionSeverity.WARNING_SEVERITY);
// GemStone changes BEGIN
		// log at warning level in GemFire logger
		// don't log for NO_ROW_FOUND as it is normally not a problem
		if (!SQLState.LANG_NO_ROW_FOUND.equals(state)) {
		  if (SanityManager.isFineEnabled) {
		    SanityManager.DEBUG_PRINT("warning:SQLWarning", message, sqlw);
		  }
		  else {
		    SanityManager.DEBUG_PRINT("warning:SQLWarning", sqlw.toString());
		  }
		}
// GemStone changes END

		return sqlw;
	}
}
