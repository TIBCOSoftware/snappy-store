/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.jdbc.ConnectionChild

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

import java.sql.SQLException;

import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;

/**
	Any class in the embedded JDBC driver (ie this package) that needs to
	refer back to the EmbedConnection object extends this class.
*/

abstract class ConnectionChild {

	/*
	** Local connection is the current EmbedConnection
	** object that we use for all our work.
	*/
	EmbedConnection localConn;

	/**	
		Factory for JDBC objects to be created.
	*/
	final InternalDriver factory;

	/**
		Calendar for data operations.
	*/
	private java.util.Calendar cal;


	ConnectionChild(EmbedConnection conn) {
		super();
		localConn = conn;
// GemStone changes BEGIN
		factory = conn != null ? conn.getLocalDriver()
		    : InternalDriver.activeDriver();
		/* (original code)
		factory = conn.getLocalDriver();
		*/
// GemStone changes END
	}

	/**
		Return a reference to the EmbedConnection
	*/
// GemStone changes BEGIN
        public final EmbedConnection getEmbedConnection() {
	/* final EmbedConnection getEmbedConnection() { */
// GemStone changes END
		return localConn;
	}

	/**
	 * Return an object to be used for connection
	 * synchronization.
	 */
// GemStone changes BEGIN
	public final Object getConnectionSynchronization()
	{
// GemStone changes END
		return localConn.getConnectionSynchronization();
	}

	/**
		Handle any exception.
		@see EmbedConnection#handleException
		@exception SQLException thrown if can't handle
	*/
	final SQLException handleException(Throwable t)
			throws SQLException {
		return localConn.handleException(t);
	}

	/**
		If Autocommit is on, note that a commit is needed.
		@see EmbedConnection#needCommit
	 */
	final void needCommit() {
		localConn.needCommit();
	}

	/**
		Perform a commit if one is needed.
		@see EmbedConnection#commitIfNeeded
		@exception SQLException thrown on failure
	 */
	final void commitIfNeeded() throws SQLException {
		//System.out.println(this + " <> " + localConn.getClass());
		//new Throwable("cin").printStackTrace(System.out);
		localConn.commitIfNeeded();
	}

	/**
		Perform a commit if autocommit is enabled.
		@see EmbedConnection#commitIfNeeded
		@exception SQLException thrown on failure
	 */
	final void commitIfAutoCommit() throws SQLException {
		//System.out.println(this + " <> " + localConn.getClass());
		//new Throwable("cin").printStackTrace(System.out);
		localConn.commitIfAutoCommit();
	}

// GemStone changes BEGIN
  /**
   * Force a commit even if autocommit is not enabled.
   * 
   * @see EmbedConnection#commitIfAutoCommit
   * @see EmbedConnection#commitIfNeeded
   * 
   * @exception SQLException
   *              thrown on failure
   */
  final void forceCommit() throws SQLException {
    localConn.forceCommit();
  }
// GemStone changes END

// GemStone added the boolean argument below
  /**
		Setup the context stack (a.k.a. context manager)
		for this connection.
		@param isOperation true if this is a top-level operation that needs proper
		transactional context, else false (e.g. for connection close) when no new
		transaction needs to be started after a previous commit or rollback
		@see EmbedConnection#setupContextStack
		@exception SQLException thrown on failure
	 */
	public final void setupContextStack(
	    final boolean isOperation) throws SQLException {
		localConn.setupContextStack(isOperation);
	}

	/**
		Setup the context stack (a.k.a. context manager)
		for this connection.
		@see EmbedConnection#restoreContextStack
		@exception SQLException thrown on failure
	 */
	public final void restoreContextStack() throws SQLException {
		localConn.restoreContextStack();
	}
        

	/**
		Get and save a unique calendar object for this JDBC object.
		No need to synchronize because multiple threads should not
		be using a single JDBC object. Even if they do there is only
		a small window where each would get its own Calendar for a
		single call.
	*/
	java.util.Calendar getCal() {
		if (cal == null)
			cal = ClientSharedData.getDefaultCalendar();
		cal.clear();
		return cal;
	}

	SQLException newSQLException(String messageId) {
		return localConn.newSQLException(messageId);
	}
	SQLException newSQLException(String messageId, Object arg1) {
		return localConn.newSQLException(messageId, arg1);
	}
	SQLException newSQLException(String messageId, Object arg1, Object arg2) {
		return localConn.newSQLException(messageId, arg1, arg2);
	}
	SQLException newSQLException(String messageId, Object arg1,
	    Object arg2, Object arg3) {
	  return Util.generateCsSQLException(messageId, arg1, arg2, arg3);
	}
}


