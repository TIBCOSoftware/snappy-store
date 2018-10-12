/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement

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

// GemStone changes BEGIN
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.hdfs.HDFSIOException;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.shared.FinalizeHolder;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gemfire.internal.util.BlobHelper;
import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.execute.CallbackStatement;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLFinishMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLQueueEntry;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegionQueue;
import com.pivotal.gemfirexd.internal.engine.distributed.QueryCancelFunction;
import com.pivotal.gemfirexd.internal.engine.distributed.QueryCancelFunction.QueryCancelFunctionArgs;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdWaitingReplyProcessor;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.execute.ConstantValueSet;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.NonUpdatableRowsResultSet;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.impl.sql.compile.DDLStatementNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// GemStone changes END






import com.pivotal.gemfirexd.internal.iapi.error.DerbySQLException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineStatement;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericResultDescription;
import com.pivotal.gemfirexd.internal.impl.sql.GenericStatement;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;
import com.pivotal.gemfirexd.internal.impl.sql.execute.CreateSchemaConstantAction;
import com.pivotal.gemfirexd.internal.impl.sql.execute.DDLConstantAction;
import com.pivotal.gemfirexd.internal.impl.sql.execute.IndexConstantAction;
import com.pivotal.gemfirexd.internal.impl.sql.execute.LockTableConstantAction;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 *
 * EmbedStatement is a local JDBC statement.
 *
   <P><B>Supports</B>
   <UL>
   <LI> JSR169 - no subsetting for java.sql.Statement
   <LI> JDBC 2.0
   <LI> JDBC 3.0 - no new dependencies on new JDBC 3.0 or JDK 1.4 classes,
        new methods can safely be added into implementation.
   </UL>

 */
public class EmbedStatement extends ConnectionChild
    implements EngineStatement, CallbackStatement /* GemStoneAddition */ {

	private final java.sql.Connection applicationConnection;
    
    /**
     * Statement reference the application is using to execute
     * this Statement. Normally set to this, but if this was
     * created by a Connection from an XAConnection then this
     * will be a reference to the BrokeredStatement.
     *
     * Making it protected to allow access from EmbedPreparedStatement40
     * to be used for StatementEvents
     *
     */
    protected EngineStatement applicationStatement;
    
    protected Activation activation;

	int updateCount = -1;
// GemStone changes BEGIN
	/* (original code)
	EmbedResultSet results;
	*/
	java.sql.ResultSet results;
	//for jdbc3.0 feature, where you can get a resultset of rows inserted
	//for auto generated columns after an insert
        // holding onto the iapi.ResultSet for non-select statements for stats
        // collection.
	ResultSet iapiResultSet = null;
	// holding onto the iapi.PreparedStatement for non-selects.
	PreparedStatement gps = null;

	private static final String[] ignoreStatesForClientDDLRetry =
	  new String[] { "42X05", "42X65", "42Y07", "X0X05", "X0X99",
	    "01504", "X0Y32", "X0Y68"
	  };

	protected static final HashSet<String> ignoreStateSetForClientDDLRetry;

	static {
	  ignoreStateSetForClientDDLRetry =
	    new HashSet<String>(ignoreStatesForClientDDLRetry.length);
	  for (String s : ignoreStatesForClientDDLRetry) {
	    ignoreStateSetForClientDDLRetry.add(s);
	  }
	}

	public static final SQLWarning ignoreDDLForClientRetry(
	    final LanguageConnectionContext lcc, final Throwable t) {
	  if (lcc != null && lcc.isPossibleDuplicate()) {
	    String messageId = null;
	    Object[] args = null;
	    String sqlState = null;
	    if (t.getClass() == StandardException.class) {
	      final StandardException se = (StandardException)t;
	      messageId = se.getMessageId();
	      args = se.getArguments();
	      sqlState = se.getSQLState();
	    }
	    else if (t instanceof DerbySQLException) {
	      final DerbySQLException de = (DerbySQLException)t;
	      messageId = de.getMessageId();
	      args = de.getArguments();
	      sqlState = StandardException.getSQLStateFromIdentifier(messageId);
	    }
	    if (sqlState != null
	        && ignoreStateSetForClientDDLRetry.contains(sqlState)) {
	      // log a warning and return with success
	      return StandardException.newWarning(messageId, args, t);
	    }
	  }
	  return null;
	}

	public static void fillInColumnName(final StandardException se,
	    String columnName, final Activation a) {
	  if (columnName == null && a == null) {
	    return;
	  }
	  final String msgId = se.getMessageId();
	  if (SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE.equals(msgId)
	      || SQLState.LANG_FORMAT_EXCEPTION.equals(msgId)) {
	    // add column name to the exception message
	    final Object[] args = se.getArguments();
	    if (args != null && (args.length == 1 ||
	        (args.length > 1 && args[1] == null))) {
	      final Object[] newArgs = new Object[2];
	      String colName = (columnName == null
	          ? a.getCurrentColumnName() : columnName);
	      if (colName == null) {
	        colName = "unknown";
	      }
	      newArgs[0] = args[0];
	      newArgs[1] = colName;
	      se.setArguments(newArgs);
	    }
	  }
	  else if (SQLState.LANG_DATA_TYPE_GET_MISMATCH.equals(msgId)
	      || SQLState.LANG_DATA_TYPE_SET_MISMATCH.equals(msgId)
	      || SQLState.UNSUPPORTED_ENCODING.equals(msgId)) {
	    // add column name to the exception message
	    final Object[] args = se.getArguments();
	    if (args != null && (args.length == 2 ||
	        (args.length > 2 && args[2] == null))) {
	      final Object[] newArgs = new Object[3];
	      String colName = (columnName == null
	          ? a.getCurrentColumnName() : columnName);
	      if (colName == null) {
	        colName = "unknown";
	      }
	      newArgs[0] = args[0];
	      newArgs[1] = args[1];
	      newArgs[2] = colName;
	      se.setArguments(newArgs);
	    }
	  }
	  else if (SQLState.LANG_DATE_RANGE_EXCEPTION.equals(msgId)
	      || SQLState.LANG_DATE_SYNTAX_EXCEPTION.equals(msgId)) {
	    // add column name to the exception message
	    final Object[] args = se.getArguments();
	    if (args == null || (args.length == 0 ||
	        (args.length > 0 && args[0] == null))) {
	      final Object[] newArgs = new Object[1];
	      String colName = (columnName == null
	          ? a.getCurrentColumnName() : columnName);
	      if (colName == null) {
	        colName = "unknown";
	      }
	      newArgs[0] = colName;
	      se.setArguments(newArgs);
	    }
	  }
	}

	protected final void setResults(final EmbedResultSet ers) {
	  this.results = ers;
	  final FinalizeStatement finalizer = this.finalizer;
	  if (finalizer != null) {
	    if (ers != null) {
	      finalizer.addSingleUseActivation(ers.singleUseActivation);
	    }
	    else {
	      finalizer.clearAllSingleUseActivations();
	    }
	  }
	}

	@Override
	public final void setResultSet(final java.sql.ResultSet rs) {
	  if (rs instanceof EmbedResultSet) {
	    setResults((EmbedResultSet)rs);
	  }
	  else {
	    this.results = rs;
	  }
	}

	@Override
	public final void setUpdateCount(int count) {
	  this.updateCount = count;
	}

	protected
	/* (original code) private  java.sql.ResultSet */ EmbedResultSet autoGeneratedKeysResultSet;
// GemStone changes END
	private String cursorName;

	private final boolean forMetaData;
	int resultSetType;
	int resultSetConcurrency;
	private int resultSetHoldability;
	final LanguageConnectionContext lcc;

	private SQLWarning warnings;
	String SQLText;

    private int fetchSize = 1;
    private int fetchDirection = java.sql.ResultSet.FETCH_FORWARD;
    int maxFieldSize;
	/**
	 * Query timeout in milliseconds. By default, no statements time
	 * out. Timeout is set explicitly with setQueryTimeout().
	 */
    long timeoutMillis;

	//the state of this statement, set to false when close() is called
	private boolean active = true;

    //in case of batch update, save the individual statements in the batch in this vector
 	//this is only used by JDBC 2.0
// GemStone changes BEGIN
	ArrayList<Object> batchStatements;
	int batchStatementCurrentIndex = 0;
	int executeBatchInProgress = 0;

	/* (original code)
 	Vector batchStatements;
 	*/
// GemStone changes END
	
	// The maximum # of rows to return per result set.
	// (0 means no limit.)
	int maxRows;

	protected ParameterValueSet pvs;

	// An EmbedStatement is NOT poolable by default. The constructor for
	// PreparedStatement overrides this.
	protected boolean isPoolable = false;
// GemStone changes BEGIN
	final long statementID;
	int executionID;
	long rootID;
	int statementLevel;
	protected StatementStats stats;

	protected FinalizeStatement finalizer;

	protected void postBatchExecution() throws StandardException,
	    SQLException {
	  // no batching optimization for statements yet
	  // when this is added then also optimize doCommitIfNeeded to avoid
	  // a commit after every batch element execution
	}

	protected FinalizeStatement newFinalizer(final EmbedStatement stmt) {
	  return new FinalizeStatement(stmt);
	}

	public final long getID() {
	  return this.statementID;
	}
	
        public final long getRootID() {
          return this.rootID;
        }
      
        public final long setRootID(long rootID) {
          return this.rootID = rootID;
        }
        
        public final long getStatementLevel() {
          return this.statementLevel;
        }
      
        public final long setStatementLevel(int val) {
          return this.statementLevel = val;
        }

	// dummy methods to get this to compile with JDK 1.6 compiler
	public boolean isWrapperFor(Class<?> interfaces) throws SQLException {
	  throw new AssertionError("unexpected execution: JDBC 4.0 only method");
	}

	public <T> T unwrap(java.lang.Class<T> interfaces) throws SQLException {
	  throw new AssertionError("unexpected execution: JDBC 4.0 only method");
	}
// GemStone changes END
      
  //
	// constructor
	//
	public EmbedStatement (EmbedConnection connection, boolean forMetaData,
// GemStone changes BEGIN
	    int resultSetType, int resultSetConcurrency,
	    int resultSetHoldability, long stmtID, long rootID, int stmtLevel) throws SQLException {
	  super(connection);
	  this.forMetaData = forMetaData;
	  this.statementID = stmtID;
	  this.executionID = 0;
          this.rootID = rootID;
          this.statementLevel = stmtLevel;
	  checkAttributes(resultSetType, resultSetConcurrency,
	      resultSetHoldability);
// GemStone changes END
		this.resultSetType = resultSetType;
		this.resultSetConcurrency = resultSetConcurrency;
		this.resultSetHoldability = resultSetHoldability;

		lcc = getEmbedConnection().getLanguageConnection();
// GemStone changes BEGIN
		lcc.setStatementId(this.statementID);
		
		// if user has configured query timeout property (thru properties file, 
		// or system property) set it as the timeout.
		if (!getEmbedConnection().isNestedConnection() &&
		    lcc.getDeafultQueryTimeOut() > 0) {
		  setQueryTimeout(lcc.getDeafultQueryTimeOut());
		} else {
		  // for nested connections, inherit the parent's time out
		  this.timeoutMillis =
		      getEmbedConnection().getDefaultNestedConnQueryTimeOut();
		}
		
                final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
                    .getInstance();
                if (observer != null) {
                  observer.getStatementIDs(this.statementID, this.rootID,
                      this.statementLevel);
                }
// GemStone changes END
		applicationConnection = getEmbedConnection().getApplicationConnection();
        applicationStatement = this;
// GemStone changes BEGIN
        this.finalizer = newFinalizer(this);
// GemStone changes END
	}

	//
	// java.sql.Statement interface
	// the comments are those from the JDBC interface,
	// so we know what we're supposed to to.

	/**
     * Execute a SQL statement that returns a single ResultSet.
     *
     * @param sql					typically this is a static SQL SELECT statement
	 * @return a ResultSet that contains the data produced by the
     * query; never null
	 * @exception SQLException thrown on failure.
     */
	public java.sql.ResultSet executeQuery(String sql)
		throws SQLException
	{
		execute(sql, true, false, Statement.NO_GENERATED_KEYS, null, null);

		if (SanityManager.DEBUG) {
			if (results == null)
				SanityManager.THROWASSERT("no results returned on executeQuery()");
		}

		return results;
	}
// GemStone changes BEGIN

	public int executeUpdateByPassQueryInfo(String sql,
	    boolean needGfxdSubactivation, boolean flattenSubquery,
	    int activationFlags, FunctionContext fnContext)
	throws SQLException {
	  execute(sql, false, true, Statement.NO_GENERATED_KEYS, null, null,
	      false /*Is a data store node  so do not create query info*/,
	      false /* no skip context restore for updates */,
	      needGfxdSubactivation, flattenSubquery, activationFlags,
	      fnContext);

	  return updateCount;
	}
// GemStone changes END

    /**
     * Execute a SQL INSERT, UPDATE or DELETE statement. In addition,
     * SQL statements that return nothing such as SQL DDL statements
     * can be executed.
     *
     * @param sql a SQL INSERT, UPDATE or DELETE statement or a SQL
     * statement that returns nothing
     * @return either the row count for INSERT, UPDATE or DELETE; or 0
     * for SQL statements that return nothing
	 * @exception SQLException thrown on failure.
     */
	public int executeUpdate(String sql) throws SQLException
	{
		execute(sql, false, true, Statement.NO_GENERATED_KEYS, null, null);
		return updateCount;
	}

    /**
     * JDBC 3.0
     *
     * Execute the given SQL statement and signals the driver with the given flag
     * about whether the auto-generated keys produced by this Statement object
     * should be made available for retrieval.
     *
     * @param sql a SQL INSERT, UPDATE or DELETE statement or a SQL
     * statement that returns nothing
     * @param autoGeneratedKeys - a flag indicating whether auto-generated keys
     * should be made available for retrieval; one of the following constants:
     * Statement.RETURN_GENERATED_KEYS Statement.NO_GENERATED_KEYS
     * @return either the row count for INSERT, UPDATE or DELETE; or 0
     * for SQL statements that return nothing
     * @exception SQLException if a database access error occurs
     */
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException
	{
		execute(sql, false, true, autoGeneratedKeys, null, null);
		return updateCount;
	}

    /**
     * JDBC 3.0
     *
     * Executes the given SQL statement and signals the driver that the
     * auto-generated keys indicated in the given array should be made
     * available for retrieval. The driver will ignore the array if the SQL
     * statement is not an INSERT statement
     *
     * @param sql a SQL INSERT, UPDATE or DELETE statement or a SQL
     * statement that returns nothing
     * @param columnIndexes - an array of column indexes indicating the
     * columns that should be returned from the inserted row
     * @return either the row count for INSERT, UPDATE or DELETE; or 0
     * for SQL statements that return nothing
     * @exception SQLException if a database access error occurs
     */
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException
	{
		execute(sql, false, true,
			((columnIndexes == null) || (columnIndexes.length == 0))
				? Statement.NO_GENERATED_KEYS
				: Statement.RETURN_GENERATED_KEYS,
			columnIndexes,
			null);
		return updateCount;
	}

    /**
     * JDBC 3.0
     *
     * Executes the given SQL statement and signals the driver that the
     * auto-generated keys indicated in the given array should be made
     * available for retrieval. The driver will ignore the array if the SQL
     * statement is not an INSERT statement
     *
     * @param sql a SQL INSERT, UPDATE or DELETE statement or a SQL
     * statement that returns nothing
     * @param columnNames - an array of the names of the columns
     * that should be returned from the inserted row
     * @return either the row count for INSERT, UPDATE or DELETE; or 0
     * for SQL statements that return nothing
     * @exception SQLException if a database access error occurs
     */
	public int executeUpdate(String sql, String[] columnNames) throws SQLException
	{
		execute(sql, false, true,
			((columnNames == null) || (columnNames.length == 0))
				? Statement.NO_GENERATED_KEYS
				: Statement.RETURN_GENERATED_KEYS,
			null,
			columnNames);
		return updateCount;
	}

	final void checkIfInMiddleOfBatch() throws SQLException {
		/* If batchStatements is not null then we are in the middle
		 * of a batch. That's an invalid state. We need to finish the
		 * batch either by clearing the batch or executing the batch.
		 * executeUpdate is not allowed inside the batch.
		 */
		if (batchStatements != null && batchStatementCurrentIndex > 0)
  		throw newSQLException(SQLState.MIDDLE_OF_BATCH);
	}

    /**
     * Tell whether this statment has been closed or not.
     *
     * @return <code>true</code> is closed, <code>false</code> otherwise.
     * @exception SQLException if a database access error occurs.
     */
    public boolean isClosed() throws SQLException {
        // If active, verify state by consulting parent connection.
        if (active) {
            try {
                checkExecStatus();
            } catch (SQLException sqle) {
            }
        }
        return !active;
    }
    
    
    /**
     * In many cases, it is desirable to immediately release a
     * Statements's database and JDBC resources instead of waiting for
     * this to happen when it is automatically closed; the close
     * method provides this immediate release.
     *
     * <P><B>Note:</B> A Statement is automatically closed when it is
     * garbage collected. When a Statement is closed its current
     * ResultSet, if one exists, is also closed.
	 * @exception SQLException thrown on failure.
     */
	public final void close() throws SQLException {
	  
// GemStone changes BEGIN
          synchronized (getConnectionSynchronization()) {
	    
            /* The close() method is the only method
                 * that is allowed to be called on a closed
                 * Statement, as per Jon Ellis.
                 **/
                if (!active)
                {
                        return;
                }
                // will get closed either via EmbedResultSet or
                // right after collecting updateCount. 
                // here we just need to release the pointer. 
                iapiResultSet = null; 

                // lose the finalizer so it can be GCed
                clearFinalizer();
// GemStone changes END
		  closeActions();
		 
		  //we first set the status
		  active = false;

		  //first, clear the resutl set
		  clearResultSets();
		  
		  //next, release other resource
// GemStone changes BEGIN
		  this.stats = null;
// GemStone changes END
		  cursorName = null;
		  warnings = null;
		  SQLText = null;
		  batchStatements = null;
      batchStatementCurrentIndex = 0;
	    executeBatchInProgress = 0;

	  }
		
	  
	}
// GemStone changes BEGIN
	public final boolean isActive() {
	  return this.active;
	}

	@Override
	public final void forceClearBatch() {
	  synchronized (getConnectionSynchronization()) {
	    this.batchStatements = null;
	  }
	}

	@Override
	public final void clearFinalizer() {
	  final FinalizeStatement finalizer = this.finalizer;
	  if (finalizer != null) {
	    finalizer.clearAll();
	    this.finalizer = null;
	  }
	}

	public void clearParameters() throws SQLException {
	}

    // now using FinalizeStatement to perform the finalization using
    // ReferenceQueue rather than the expensive finalizers
    /* (original code)
    /**
     * Mark the statement and its single-use activation as unused. This method
     * should be called from <code>EmbedPreparedStatement</code>'s finalizer as
     * well, even though prepared statements reuse activations, since
     * <code>getGeneratedKeys()</code> uses a single-use activation regardless
     * of statement type.
     * <BR>
     * Dynamic result sets (those in dynamicResults array) need not
     * be handled here as they will be handled by the statement object
     * that created them. In some cases results will point to a
     * ResultSet in dynamicResults but all that will happen is that
     * the activation will get marked as unused twice.
     *
    @Override
    protected void finalize() throws Throwable {
        super.finalize();

        // We mark the activation as not being used and
        // that is it.  We rely on the connection to sweep
        // through the activations to find the ones that
        // aren't in use, and to close them.  We cannot
        // do a activation.close() here because there are
        // synchronized methods under close that cannot
        // be called during finalization.
        if (results != null && results.singleUseActivation != null) {
            results.singleUseActivation.markUnused();
        }
    }
    */
// GemStone changes END

	// allow sub-classes to execute additional close
	// logic while holding the synchronization.
	void closeActions() throws SQLException {
	}

    //----------------------------------------------------------------------

    /**
     * The maxFieldSize limit (in bytes) is the maximum amount of data
     * returned for any column value; it only applies to BINARY,
     * VARBINARY, LONGVARBINARY, CHAR, VARCHAR, and LONGVARCHAR
     * columns.  If the limit is exceeded, the excess data is silently
     * discarded.
     *
     * @return the current max column size limit; zero means unlimited
	 * @exception SQLException thrown on failure.
     */
	public int getMaxFieldSize() throws SQLException {
		checkStatus();

        return maxFieldSize;
	}

    /**
     * The maxFieldSize limit (in bytes) is set to limit the size of
     * data that can be returned for any column value; it only applies
     * to BINARY, VARBINARY, LONGVARBINARY, CHAR, VARCHAR, and
     * LONGVARCHAR fields.  If the limit is exceeded, the excess data
     * is silently discarded.
     *
     * @param max the new max column size limit; zero means unlimited
	 * @exception SQLException thrown on failure.
     */
	public void setMaxFieldSize(int max) throws SQLException {
		checkStatus();

		if (max < 0)
		{
			throw newSQLException(SQLState.INVALID_MAXFIELD_SIZE, new Integer(max));
		}
        this.maxFieldSize = max;
	}

    /**
     * The maxRows limit is the maximum number of rows that a
     * ResultSet can contain.  If the limit is exceeded, the excess
     * rows are silently dropped.
     *
     * @return the current max row limit; zero means unlimited
	 * @exception SQLException thrown on failure.
     */
	public int getMaxRows() throws SQLException 
	{
		checkStatus();
		return maxRows;
	}

    /**
     * The maxRows limit is set to limit the number of rows that any
     * ResultSet can contain.  If the limit is exceeded, the excess
     * rows are silently dropped.
     *
     * @param max the new max rows limit; zero means unlimited
	 * @exception SQLException thrown on failure.
     */
	public void setMaxRows(int max) throws SQLException	
	{
		checkStatus();
		if (max < 0)
		{
			throw newSQLException(SQLState.INVALID_MAX_ROWS_VALUE, new Integer(max));
		}
		this.maxRows = max;
	}

    /**
     * If escape scanning is on (the default) the driver will do
     * escape substitution before sending the SQL to the database.
     *
     * @param enable true to enable; false to disable
	 * @exception SQLException thrown on failure.
     */
	public void setEscapeProcessing(boolean enable) throws SQLException	{
		checkStatus();
        // Nothing to do in our server , just ignore it.

	}

    /**
     * The queryTimeout limit is the number of seconds the driver will
     * wait for a Statement to execute. If the limit is exceeded a
     * SQLException is thrown.
     *
     * @return the current query timeout limit in seconds; zero means unlimited
	 * @exception SQLException thrown on failure.
     */
	public final int getQueryTimeout() throws SQLException {
        checkStatus();
        return (int) (timeoutMillis / 1000);
	}

    /**
     * The queryTimeout limit is the number of seconds the driver will
     * wait for a Statement to execute. If the limit is exceeded a
     * SQLException is thrown.
     *
     * @param seconds the new query timeout limit in seconds; zero means unlimited
	 * @exception SQLException thrown on failure.
     */
	public final void setQueryTimeout(int seconds) throws SQLException {
		checkStatus();
        if (seconds < 0) {
            throw newSQLException(SQLState.INVALID_QUERYTIMEOUT_VALUE,
                                  new Integer(seconds));
        }
        
        if (GemFireXDUtils.TraceExecution) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
              "EmbedStatement#setQueryTimeout timeout=" + seconds + " seconds");
        }
        
        final EmbedConnection conn = getEmbedConnection();
        // in case of nested connections (such as statements in a stored procedure),
        // timeout cannot be more than parent(outer callable statement) statement's timeout 
        int timeOutForOuterStmt = (int) (conn.getDefaultNestedConnQueryTimeOut() 
            / 1000);
        if (timeOutForOuterStmt > 0 && seconds > timeOutForOuterStmt) {
          this.addWarning(StandardException.newWarning(
              SQLState.LANG_INNER_QUERY_TIMEOUT_MORE_THAN_PARENT_TIMEOUT,
              new Object[] { seconds, timeOutForOuterStmt, 
                  timeOutForOuterStmt }, null));
          timeoutMillis = conn.getDefaultNestedConnQueryTimeOut();
        } else {
          timeoutMillis = (long) seconds * 1000;
        }

        if (!conn.isNestedConnection()) {
          getEmbedConnection().getLanguageConnectionContext()
              .setLastStatementQueryTimeOut(timeoutMillis);
        }
	}

	/**
	 * Cancel can be used by one thread to cancel a statement that
	 * is being executed by another thread.
	 *  * @exception SQLException thrown on failure.
	 */
	public void cancel() throws SQLException {
	  if (GemFireXDUtils.TraceExecution) {
	    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
	        "EmbedStatement#cancel statementId=" + this.statementID 
	        + " activation=" + this.activation);
	  }
	  // cancel the query on query node (i.e. the current node)
	  if (this.activation != null && !this.activation.isClosed()) {
	    this.activation.cancelOnUserRequest();
	  }
	  // send a message to cancel the query on all other data nodes
	  QueryCancelFunctionArgs args = QueryCancelFunction
	      .newQueryCancelFunctionArgs(this.statementID, lcc.getConnectionId());
	  Set<DistributedMember> dataStores = GemFireXDUtils.getGfxdAdvisor().adviseDataStores(null);
	  final DistributedMember myId = GemFireStore.getMyId();
	  // add self too for the wrapper connection
	  dataStores.add(myId);
	  if (dataStores.size() > 0) {
	    FunctionService.onMembers(dataStores).withArgs(args).execute(
	        QueryCancelFunction.ID);
	  }
	}

    /**
     * The first warning reported by calls on this Statement is
     * returned.  A Statment's execute methods clear its SQLWarning
     * chain. Subsequent Statement warnings will be chained to this
     * SQLWarning.
     *
     * <p>The warning chain is automatically cleared each time
     * a statement is (re)executed.
     *
     * <P><B>Note:</B> If you are processing a ResultSet then any
     * warnings associated with ResultSet reads will be chained on the
     * ResultSet object.
     *
     * @return the first SQLWarning or null
	 * @exception SQLException thrown on failure.
     */
	public SQLWarning getWarnings() throws SQLException	{
		checkStatus();
		return warnings;
	}

    /**
     * After this call getWarnings returns null until a new warning is
     * reported for this Statement.
	 * @exception SQLException thrown on failure.
     */
	public void clearWarnings() throws SQLException	{
		checkStatus();
		warnings = null;
	}

    /**
     * setCursorName defines the SQL cursor name that will be used by
     * subsequent Statement execute methods. This name can then be
     * used in SQL positioned update/delete statements to identify the
     * current row in the ResultSet generated by this statement.  If
     * the database doesn't support positioned update/delete, this
     * method is a noop.
     *
     * <P><B>Note:</B> By definition, positioned update/delete
     * execution must be done by a different Statement than the one
     * which generated the ResultSet being used for positioning. Also,
     * cursor names must be unique within a Connection.
     *
     * @param name the new cursor name.
     */
	public void setCursorName(String name) throws SQLException {
		checkStatus();
		cursorName = name;
	}

    //----------------------- Multiple Results --------------------------

    /**
     * Execute a SQL statement that may return multiple results.
     * Under some (uncommon) situations a single SQL statement may return
     * multiple result sets and/or update counts.  Normally you can ignore
     * this, unless you're executing a stored procedure that you know may
     * return multiple results, or unless you're dynamically executing an
     * unknown SQL string.  The "execute", "getMoreResults", "getResultSet"
     * and "getUpdateCount" methods let you navigate through multiple results.
     *
     * The "execute" method executes a SQL statement and indicates the
     * form of the first result.  You can then use getResultSet or
     * getUpdateCount to retrieve the result, and getMoreResults to
     * move to any subsequent result(s).
     *
     * @param sql					any SQL statement
	 *
     * @return true if the first result is a ResultSet; false if it is an integer
     * @see #getResultSet
     * @see #getUpdateCount
     * @see #getMoreResults
	 * @exception SQLException thrown on failure
     */
	public boolean execute(String sql)
		throws SQLException
	{
		return execute(sql, false, false, Statement.NO_GENERATED_KEYS, null, null);
	}
	
    /**
     * Execute a SQL statement that may return multiple results.
     * Under some (uncommon) situations a single SQL statement may return
     * multiple result sets and/or update counts.  Normally you can ignore
     * this, unless you're executing a stored procedure that you know may
     * return multiple results, or unless you're dynamically executing an
     * unknown SQL string.  The "execute", "getMoreResults", "getResultSet"
     * and "getUpdateCount" methods let you navigate through multiple results.
     *
     * The "execute" method executes a SQL statement and indicates the
     * form of the first result.  You can then use getResultSet or
     * getUpdateCount to retrieve the result, and getMoreResults to
     * move to any subsequent result(s).
     *
     * @param sql					any SQL statement
	 * @param executeQuery			caller is executeQuery()
	 * @param executeUpdate			caller is executeUpdate()
     * @param autoGeneratedKeys
     * @param columnIndexes
     * @param columnNames
	 *
     * @return true if the first result is a ResultSet; false if it is an integer
     * @see #getResultSet
     * @see #getUpdateCount
     * @see #getMoreResults
	 * @exception SQLException thrown on failure
     */
	private boolean execute(String sql, boolean executeQuery, boolean executeUpdate,
		int autoGeneratedKeys, int[] columnIndexes, String[] columnNames) throws SQLException
	{
//            GemStone changes BEGIN
	  return this.execute(sql, executeQuery, executeUpdate, autoGeneratedKeys, 
               columnIndexes, columnNames, true /* create query info*/,
               false /* skip context restore */,
               false /* sub query node need not be analyzed*/,
               true /* flattenSubquery */, 0 /* activationFlags */,
               null /* function context */);
//            GemStone changes END
	}
        
        
//      GemStone changes BEGIN

  public java.sql.ResultSet executeQueryByPassQueryInfo(String sql,
      boolean needGfxdSubactivation, boolean flattenSubquery,
      int activationFlags, FunctionContext fnContext) throws SQLException {
    this.execute(sql, true, false, Statement.NO_GENERATED_KEYS, null, null,
        false /* do not create query info */, true /* skip context restore */,
        needGfxdSubactivation, flattenSubquery, activationFlags, fnContext);

    if (SanityManager.DEBUG) {
      if (results == null)
        SanityManager.THROWASSERT("no results returned on executeQuery()");
    }
    return this.results;

  }

  /**
   * Execute a SQL statement that may return multiple results. Under some
   * (uncommon) situations a single SQL statement may return multiple result
   * sets and/or update counts. Normally you can ignore this, unless you're
   * executing a stored procedure that you know may return multiple results, or
   * unless you're dynamically executing an unknown SQL string. The "execute",
   * "getMoreResults", "getResultSet" and "getUpdateCount" methods let you
   * navigate through multiple results.
   * 
   * The "execute" method executes a SQL statement and indicates the form of the
   * first result. You can then use getResultSet or getUpdateCount to retrieve
   * the result, and getMoreResults to move to any subsequent result(s).
   * 
   * @param sql
   *          any SQL statement
   * @param executeQuery
   *          caller is executeQuery()
   * @param executeUpdate
   *          caller is executeUpdate()
   * @param autoGeneratedKeys
   * @param columnIndexes
   * @param columnNames
   * @param createQueryInfo
   *          boolean which indicates whether the QueryInfo creation is to be
   *          avoided or not. It will be false only when invoked from
   *          com.gemstone
   *          .gemfirexd.internal.engine.distributed.StatementQueryExecutor
   * @param needGfxdSubactivation
   * @param flattenSubquery
   * @param activationFlags
   * @param fnContext
   * 
   * @return true if the first result is a ResultSet; false if it is an integer
   * @see #getResultSet
   * @see #getUpdateCount
   * @see #getMoreResults
   * @see com.pivotal.gemfirexd.internal.engine.distributed.StatementQueryExecutor
   * @exception SQLException
   *              thrown on failure
   */
  private boolean execute(String sql, boolean executeQuery,
      boolean executeUpdate, int autoGeneratedKeys, int[] columnIndexes,
      String[] columnNames, boolean createQueryInfo,
      boolean skipContextRestore, boolean needGfxdSubactivation,
      boolean flattenSubquery, int activationFlags, FunctionContext fnContext)
      throws SQLException {
     synchronized (getConnectionSynchronization()) {

      checkExecStatus();
      if (sql == null) {
        throw newSQLException(SQLState.NULL_SQL_TEXT);
      }
			// GemStone changes BEGIN
      checkIfInMiddleOfBatch();
      clearResultSets(); // release the last statement executed, if any.
      
      setupContextStack(true); // make sure there's context
     
      // try to remember the SQL statement in case anybody asks for it
      SQLText = sql;
//    GemStone changes BEGIN
      boolean stmtSuccess = false;
      boolean isStatementOptimizationDisabled = getEmbedConnection().getTR().getDatabase()
              .disableStatementOptimizationToGenericPlan();
      CompilerContext cc = null;
//      Activation activation = null;
      try {
        try {
          short execFlags = 0x0000;
          THashMap ncjMetaData = null;
          if (createQueryInfo) {
            execFlags = GemFireXDUtils.set(execFlags,
                GenericStatement.CREATE_QUERY_INFO);
          }

          if (needGfxdSubactivation) {
            execFlags = GemFireXDUtils.set(execFlags,
                GenericStatement.GFXD_SUBACTIVATION_NEEDED);
          }

          if (!flattenSubquery) {
            execFlags = GemFireXDUtils.set(execFlags,
                GenericStatement.DISALLOW_SUBQUERY_FLATTENING);
          }
          if (resultSetConcurrency == java.sql.ResultSet.CONCUR_READ_ONLY) {
            execFlags = GemFireXDUtils.set(execFlags,
                GenericStatement.IS_READ_ONLY);
          }

          if ((RegionExecutorMessage<?>)fnContext == null) {
            if (SanityManager.DEBUG) {
              if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                SanityManager
                    .DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                        "EmbedStatement::execute - " +
                        "Function Context is Null. " +
                        "Flag ALL_TABLES_ARE_REPLICATED_ON_REMOTE is FALSE");
              }
            }
          }
          else {
            if (((RegionExecutorMessage<?>)fnContext)
                .allTablesAreReplicatedOnRemote()) {
              if (SanityManager.DEBUG) {
                if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                  SanityManager
                      .DEBUG_PRINT(
                          GfxdConstants.TRACE_QUERYDISTRIB,
                          "EmbedStatement::execute - "
                              + "Setting ALL_TABLES_ARE_REPLICATED_ON_REMOTE to TRUE");
                }
              }
              execFlags = GemFireXDUtils.set(execFlags,
                  GenericStatement.ALL_TABLES_ARE_REPLICATED_ON_REMOTE);
            }
            else if (SanityManager.DEBUG) {
              if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                SanityManager
                    .DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                        "EmbedStatement::execute - " + 
                        "Flag ALL_TABLES_ARE_REPLICATED_ON_REMOTE is FALSE");
              }
            }

            ncjMetaData = ((RegionExecutorMessage<?>)fnContext)
                .getNCJMetaDataOnRemote();

            if (SanityManager.DEBUG) {
              if (GemFireXDUtils.TraceNCJ) {
                SanityManager.DEBUG_PRINT(
                    GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
                    "EmbedStatement::execute. ncjMetaData=" + ncjMetaData);
              }
            }
          }
          
          if (!isStatementOptimizationDisabled) {
            execFlags = (short)GemFireXDUtils.set(execFlags,
                GenericStatement.IS_OPTIMIZED_STMT, true);
            cc = lcc.lookupGeneralizedStatement(lcc.getDefaultSchema(), sql,
                execFlags);
          }

          PreparedStatement preparedStatement = cc != null ? cc
              .getPreparedStatement() : null;
          
          if (cc == null || preparedStatement == null  /* || !cc.isPreparedStatementUpToDate()*/) {
            /*
            String compileQueryString = cc != null ? cc
                .replaceQueryString(null) : null;
            if (compileQueryString == null) {
              compileQueryString = SQLText;
            }*/
            if (cc != null) {
              if (SanityManager.DEBUG) {
                if (GemFireXDUtils.TraceStatementMatching
                    && !isStatementOptimizationDisabled) {
                  SanityManager.DEBUG_PRINT(
                      GfxdConstants.TRACE_STATEMENT_MATCHING, "Cache Miss "
                          + cc.getGeneralizedQueryString() + " compiling ");
                }
              }
            }

            if (cc == null || isStatementOptimizationDisabled) {
              execFlags = (short)GemFireXDUtils.set(execFlags,
                  GenericStatement.IS_OPTIMIZED_STMT, false);
            }

            if (lcc.getQueryHDFS()) {
              execFlags = (short) GemFireXDUtils.set(execFlags,
                  GenericStatement.QUERY_HDFS, true);
            }

            if (routeQueryEnabled(cc)) {
              execFlags = GemFireXDUtils.set(execFlags, GenericStatement.ROUTE_QUERY, true);
            }

            preparedStatement = lcc.prepareInternalStatement(lcc
                .getDefaultSchema(), cc != null? cc.getGeneralizedQueryString():SQLText,                
                false /* for meta data*/, execFlags, cc, ncjMetaData);
          }
          ConstantValueSet cvs = (ConstantValueSet)lcc.getConstantValueSet(null); 
         
          this.stats = lcc.statsEnabled() ? preparedStatement
              .getStatementStats() : null;
          
         
          activation = preparedStatement.getActivation(lcc,
              resultSetType == java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE,
              SQLText);
          if( cvs != null ) {
            cvs.initialize(((GenericPreparedStatement)preparedStatement).getParameterTypes());
          }
          if(createQueryInfo && !isStatementOptimizationDisabled) {
            validateParameterizedData(preparedStatement);
            
          }
          
          // this is for a Statement execution
          activation.setSingleExecution();
          activation.setStatementID(this.statementID);
          activation.setRootID(this.rootID);
          activation.setStatementLevel(this.statementLevel);
          checkRequiresCallableStatement(activation);
          activation.setFlags(activationFlags);
          activation.setFunctionContext(fnContext);
          /* (original code)
          activation = preparedStatement.getActivation(lcc,
              resultSetType == java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE, SQLText);
          checkRequiresCallableStatement(activation);
          throw handleException(t);
          */
        } catch (Throwable t) {
          // ignore "already exists" or "does not exist" kind of exceptions in
          // DDL executions from client during retries and log a warning (#42795)
          final SQLWarning ddlWarning;
          if (lcc.getTransientStatementNode() instanceof DDLStatementNode
              && (ddlWarning = ignoreDDLForClientRetry(lcc, t)) != null) {
            // invoke handleException in any case for context cleanup
            handleException(t);
            // log a warning and return with success
            addWarning(ddlWarning);
            stmtSuccess = true;
            return false /* no ResultSets for DDLs */;
          }
          final SQLException sqle = handleException(t);
          final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
              .getInstance();
          if (observer != null) {
            if (observer.afterQueryExecution(this, sqle)) {
              // return value of true means that don't throw exception
              // rather the result has been filled in the statement using
              // either setResultSet or setUpdateCount by the observer
              return this.results != null;
            }
          }
          this.clearResultSets();
          throw sqle;
        }

        /* (original code)
        // this is for a Statement execution
        activation.setSingleExecution();
        activation.setStatementID(this.statementID);
        */
// GemStone changes END

        // bug 4838 - save the auto-generated key information in activation.
        // keeping this
        // information in lcc will not work work it can be tampered by a nested
        // trasaction
        if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS)
          activation.setAutoGeneratedKeysResultsetInfo(columnIndexes,
              columnNames);
// GemStone changes BEGIN
        boolean result = executeStatement(activation, executeQuery,
            executeUpdate, createQueryInfo, true /* is context set */,
            skipContextRestore, false /* is prepared batch */);
        stmtSuccess = true;
        return result;
// GemStone changes END
      } finally {
// GemStone changes BEGIN
        //Asif : If on data store node then do not skip context
        if (cc != null) {
          lcc.popCompilerContext(cc);
        }
        lcc.unlinkConstantValueSet();
        activation = null;
        // if execution of statement failed for some reason then cleanup
        // context stack in any case; higher layer remote statement execution
        // will cleanup only in the case when execute is success
        if (!skipContextRestore || !stmtSuccess) {
          restoreContextStack();
        }
// GemStone changes END
      }
    }
   }

  protected static final Pattern EXECUTION_ENGINE_STORE_HINT =
    Pattern.compile(".*\\bEXECUTIONENGINE(\\s+)?+=(\\s+)?+STORE\\s*\\b.*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  protected boolean routeQueryEnabled(CompilerContext cc) {
    String stmt = cc != null? cc.getGeneralizedQueryString() : SQLText;
    return Misc.routeQuery(lcc)
      && (!EXECUTION_ENGINE_STORE_HINT.matcher(stmt).matches());
  }

	private void validateParameterizedData(PreparedStatement preparedStatement)
      throws StandardException {
    ConstantValueSet  cvs = (ConstantValueSet)this.lcc.getConstantValueSet(null);
    GenericPreparedStatement gps = (GenericPreparedStatement)preparedStatement; 
    if( cvs != null) {
      int validateResult = cvs.validateParameterizedData();
      if(validateResult != -1) {
        QueryInfo qInfo = gps.getQueryInfo();
        if(qInfo != null) {
          qInfo.throwExceptionForInvalidParameterizedData(validateResult);
        }else {
          // it is for sure an insert
           throw StandardException.newException(SQLState.LANG_NOT_STORABLE,
               cvs.getParamTypes()[validateResult].getTypeId().getSQLTypeName(),
               cvs.getOrigTypeCompilers().get(validateResult)
                 .getCorrespondingPrimitiveTypeName(),
               "non-null column " + Integer.toString(validateResult + 1));
        }
      }
    }
  }
       
        
//      GemStone changes END


    /**
     * JDBC 3.0
     *
     * Executes the given SQL statement, which may return multiple
     * results, and signals the driver that any auto-generated keys
     * should be made available for retrieval. The driver will ignore
     * this signal if the SQL statement is not an INSERT statement.
     *
     * @param sql any SQL statement
     * @param autoGeneratedKeys - a constant indicating whether
     * auto-generated keys should be made available for retrieval using
     * the method getGeneratedKeys; one of the following constants:
     * Statement.RETURN_GENERATED_KEYS or Statement.NO_GENERATED_KEYS
     * @return rue if the first result is a ResultSet object; false if
     * it is an update count or there are no results
     * @exception SQLException if a database access error occurs
     */
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException
	{
		return execute(sql, false, false, autoGeneratedKeys, null, null);
	}

    /**
     * JDBC 3.0
     *
     * Executes the given SQL statement, which may return multiple
     * results, and signals the driver that the auto-generated keys
     * indicated in the given array should be made available for retrieval.
     * This array contains the indexes of the columns in the target table
     * that contain the auto-generated keys that should be made available.
     * The driver will ignore the array if the given SQL statement is not an
     * INSERT statement.
     *
     * @param sql any SQL statement
     * @param columnIndexes - an array of the indexes of the columns in the
     * inserted row that should be made available for retrieval by a call to
     * the method getGeneratedKeys
     * @return rue if the first result is a ResultSet object; false if
     * it is an update count or there are no results
     * @exception SQLException if a database access error occurs
     */
	public boolean execute(String sql, int[] columnIndexes) throws SQLException
	{
		return execute(sql, false, true,
			((columnIndexes == null) || (columnIndexes.length == 0))
				? Statement.NO_GENERATED_KEYS
				: Statement.RETURN_GENERATED_KEYS,
			columnIndexes,
			null);
	}

    /**
     * JDBC 3.0
     *
     * Executes the given SQL statement, which may return multiple
     * results, and signals the driver that the auto-generated keys
     * indicated in the given array should be made available for retrieval.
     * This array contains the names of the columns in the target table
     * that contain the auto-generated keys that should be made available.
     * The driver will ignore the array if the given SQL statement is not an
     * INSERT statement.
     *
     * @param sql any SQL statement
     * @param columnNames - an array of the names of the columns in the
     * inserted row that should be made available for retrieval by a call to
     * the method getGeneratedKeys
     * @return rue if the first result is a ResultSet object; false if
     * it is an update count or there are no results
     * @exception SQLException if a database access error occurs
     */
	public boolean execute(String sql, String[] columnNames) throws SQLException
	{
		return execute(sql, false, true,
			((columnNames == null) || (columnNames.length == 0))
				? Statement.NO_GENERATED_KEYS
				: Statement.RETURN_GENERATED_KEYS,
			null,
			columnNames);
	}

    /**
     *  getResultSet returns the current result as a ResultSet.  It
     *  should only be called once per result.
     *
     * @return the current result as a ResultSet; null if the result
     * is an update count or there are no more results or the statement
	 * was closed.
     * @see #execute
     */
	public final java.sql.ResultSet getResultSet() throws SQLException  {
		checkStatus();

		return results;
	}

    /**
     *  getUpdateCount returns the current result as an update count;
     *  if the result is a ResultSet or there are no more results -1
     *  is returned.  It should only be called once per result.
     *
     * <P>The only way to tell for sure that the result is an update
     *  count is to first test to see if it is a ResultSet. If it is
     *  not a ResultSet it is either an update count or there are no
     *  more results.
     *
     * @return the current result as an update count; -1 if it is a
     * ResultSet or there are no more results
     * @see #execute
     */
	public final int getUpdateCount()	throws SQLException  {
		checkStatus();
		return updateCount;
	}

    /**
     * getMoreResults moves to a Statement's next result.  It returns true if
     * this result is a ResultSet.  getMoreResults also implicitly
     * closes any current ResultSet obtained with getResultSet.
     *
     * There are no more results when (!getMoreResults() &&
     * (getUpdateCount() == -1)
     *
     * @return true if the next result is a ResultSet; false if it is
     * an update count or there are no more results
     * @see #execute
	 * @exception SQLException thrown on failure.
     */
	public final boolean getMoreResults() throws SQLException	{
		return getMoreResults(Statement.CLOSE_ALL_RESULTS);
	}

	/////////////////////////////////////////////////////////////////////////
	//
	//	JDBC 2.0 methods that are implemented here because EmbedPreparedStatement
	//  and EmbedCallableStatement in Local20 need access to them, and those
	//	classes extend their peer classes in Local, instead of EmbedStatement
	//	in Local20
	//
	//  We do the same of JDBC 3.0 methods.
	/////////////////////////////////////////////////////////////////////////

    /**
     * JDBC 2.0
     *
     * Determine the result set type.
     *
     * @exception SQLException Feature not implemented for now.
     */
    public final int getResultSetType()
		throws SQLException 
	{
		checkStatus();
		return resultSetType;
	}


    /**
     * JDBC 2.0
     *
     * Give a hint as to the direction in which the rows in a result set
     * will be processed. The hint applies only to result sets created
     * using this Statement object.  The default value is 
     * ResultSet.FETCH_FORWARD.
     *
     * @param direction the initial direction for processing rows
     * @exception SQLException if a database-access error occurs or direction
     * is not one of ResultSet.FETCH_FORWARD, ResultSet.FETCH_REVERSE, or
     * ResultSet.FETCH_UNKNOWN
     */
    public void setFetchDirection(int direction) throws SQLException {
		
		checkStatus();
                /* fetch direction is meaningless to us. we just save
                 * it off if it is valid  and return the current value if asked.
                 */
                if (direction == java.sql.ResultSet.FETCH_FORWARD || 
                    direction == java.sql.ResultSet.FETCH_REVERSE ||
                    direction == java.sql.ResultSet.FETCH_UNKNOWN )
                {
                    fetchDirection = direction;
                }else
                    throw newSQLException(SQLState.INVALID_FETCH_DIRECTION, 
                                   new Integer(direction));
	}

    /**
     * JDBC 2.0
     *
     * Determine the fetch direction.
     *
     * @return the default fetch direction
     * @exception SQLException if a database-access error occurs
     */
    public int getFetchDirection() throws SQLException {
		checkStatus();
		return fetchDirection;
	}


    /**
     * JDBC 2.0
     *
     * Give the JDBC driver a hint as to the number of rows that should
     * be fetched from the database when more rows are needed.  The number 
     * of rows specified only affects result sets created using this 
     * statement. If the value specified is zero, then the hint is ignored.
     * The default value is zero.
     *
     * @param rows the number of rows to fetch
     * @exception SQLException if a database-access error occurs, or the
     * condition 0 <= rows <= this.getMaxRows() is not satisfied.
     */
    public void setFetchSize(int rows) throws SQLException {
		checkStatus();
        if (rows < 0  || (this.getMaxRows() != 0 && 
                             rows > this.getMaxRows()))
        {
	        throw newSQLException(SQLState.INVALID_ST_FETCH_SIZE, new Integer(rows));
        }else if ( rows > 0 ) // ignore the call if the value is zero
            fetchSize = rows;
	}
  
    /**
     * JDBC 2.0
     *
     * Determine the default fetch size.
     * @exception SQLException if a database-access error occurs
     *
     */
    public int getFetchSize() throws SQLException {
		checkStatus();
		return fetchSize;
	}

    /**
     * JDBC 2.0
     *
     * Determine the result set concurrency.
     *
     * @exception SQLException Feature not implemented for now.
     */
    public int getResultSetConcurrency() throws SQLException {
		checkStatus();
		return resultSetConcurrency;
	}

    /**
     * JDBC 3.0
     *
     * Retrieves the result set holdability for ResultSet objects
     * generated by this Statement object.
     *
     * @return either ResultSet.HOLD_CURSORS_OVER_COMMIT or
     * ResultSet.CLOSE_CURSORS_AT_COMMIT
     * @exception SQLException Feature not implemented for now.
     */
    public final int getResultSetHoldability() throws SQLException {
		checkStatus();
		return resultSetHoldability;
	}

    /**
     * JDBC 2.0
     *
     * Adds a SQL command to the current batch of commmands for the statement.
     * This method is optional.
     *
     * @param sql typically this is a static SQL INSERT or UPDATE statement
     * @exception SQLException if a database-access error occurs, or the
     * driver does not support batch statements
     */
    public void addBatch( String sql ) throws SQLException {
		checkStatus();
  	  synchronized (getConnectionSynchronization()) {
		  if (batchStatements == null)
// GemStone changes BEGIN
		  {
		    batchStatements = new ArrayList<Object>();
		  }
		  batchStatements.add(sql);
		  batchStatementCurrentIndex++;

		    /* (original code)
			  batchStatements = new Vector();
        batchStatements.addElement(sql);
		    */
// GemStone changes END
  		}
	}

    /**
     * JDBC 2.0
     *
     * Make the set of commands in the current batch empty.
     * This method is optional.
     *
     * @exception SQLException if a database-access error occurs, or the
     * driver does not support batch statements
     */
    public final void clearBatch() throws SQLException {
		checkStatus();
  	  synchronized (getConnectionSynchronization()) {
        batchStatements = null;
        batchStatementCurrentIndex = 0;
          }
	}

	public final void resetBatch() throws SQLException {
		checkStatus();
		synchronized (getConnectionSynchronization()) {
			//batchStatements = null;
			batchStatementCurrentIndex = 0;
			if (batchStatements != null) {
				for (int i = batchStatements.size() - 1; i >= 0; i--) {
					((ParameterValueSet)batchStatements.get(i)).clearParameters();
				}
			}
		}
	}


	/**
     * JDBC 2.0
     * 
     * Submit a batch of commands to the database for execution.
     * This method is optional.
	 *
	 * Moving jdbc2.0 batch related code in this class because
	 * callableStatement in jdbc 20 needs this code too and it doesn't derive
	 * from prepared statement in jdbc 20 in our implementation. 
	 * BatchUpdateException is the only new class from jdbc 20 which is being
	 * referenced here and in order to avoid any jdk11x problems, using
	 * reflection code to make an instance of that class. 
     *
     * @return an array of update counts containing one element for each
     * command in the batch.  The array is ordered according
     * to the order in which commands were inserted into the batch
     * @exception SQLException if a database-access error occurs, or the
     * driver does not support batch statements
     */
    public int[] executeBatch() throws SQLException {
		checkExecStatus();
		synchronized (getConnectionSynchronization()) 
		{
			executeBatchInProgress++;
                        setupContextStack(true);
			int i = 0;
			// As per the jdbc 2.0 specs, close the statement object's current resultset
			// if one is open.
			// Are there results?
			// outside of the lower try/finally since results will
			// setup and restore themselves.
			clearResultSets();

// GemStone changes BEGIN
			final ArrayList<Object> stmts = this.batchStatements;
			/* (original code)
			Vector stmts = batchStatements;
			*/
// GemStone changes END
			if (!isPrepared()){
				batchStatements = null;
			}

			int size;
			if (stmts == null)
				size = 0;
			else
				size = batchStatementCurrentIndex;//stmts.size();
			// take the index in case if last batch
			int[] returnUpdateCountForBatch = new int[size];

			SQLException sqle;
// GemStone changes BEGIN
			final GemFireXDQueryObserver observer =
			    GemFireXDQueryObserverHolder.getInstance();
			try {
			  if (observer != null) {
			    observer.beforeBatchQueryExecution(this,
			        stmts != null ? stmts.size() : 0);
			  }
	                /* (original code)
	                try {
	                */
// GemStone changes END
				for (; i< size; i++) 
				{
// GemStone changes BEGIN
					if (executeBatchElement(stmts.get(i)))
					/* (original code)
					if (executeBatchElement(stmts.elementAt(i)))
					*/
// GemStone changes END
						throw newSQLException(SQLState.RESULTSET_RETURN_NOT_ALLOWED);
					returnUpdateCountForBatch[i] = getUpdateCount();
				}
// GemStone changes BEGIN
				postBatchExecution();
				commitIfAutoCommit();
				if (this.lcc != null) {
				  GemFireTransaction tran = (GemFireTransaction)
				      this.lcc.getTransactionExecute();
				  if (!tran.isTransactional()) {
				    tran.releaseAllLocks(false, false);
				  }
				}
				if (observer != null) {
				  observer.afterBatchQueryExecution(this, size);
				}
// GemStone changes END
				return returnUpdateCountForBatch;
			}
			catch (StandardException se) {

				sqle = handleException(se);
			}
			catch (SQLException sqle2) 
			{
				sqle = sqle2;
			}
			finally 
			{
				restoreContextStack();
			}

			int successfulUpdateCount[] = new int[i];
			for (int j=0; j<i; j++)
			{
				successfulUpdateCount[j] = returnUpdateCountForBatch[j];
			}

			SQLException batch =
			new java.sql.BatchUpdateException(sqle.getMessage(), sqle.getSQLState(),
									sqle.getErrorCode(), successfulUpdateCount);

			batch.setNextException(sqle);
			batch.initCause(sqle);
			throw batch;
      }
	}

	/**
		Execute a single element of the batch. Overridden by EmbedPreparedStatement
	*/
	boolean executeBatchElement(Object batchElement) throws SQLException, StandardException {
		return execute((String)batchElement, false, true, Statement.NO_GENERATED_KEYS, null, null);
	}

    /**
     * JDBC 2.0
     *
     * Return the Connection that produced the Statement.
     *
     * @exception SQLException Exception if it cannot find the connection
     * associated to this statement.
     */
    public final java.sql.Connection getConnection()  throws SQLException {
		checkStatus();

    	java.sql.Connection appConn = getEmbedConnection().getApplicationConnection();
		if ((appConn != applicationConnection) || (appConn == null)) {

			throw Util.noCurrentConnection();
        }
		return appConn;
    }

    /**
     * JDBC 3.0
     *
     * Moves to this Statement obect's next result, deals with any current ResultSet
     * object(s) according to the instructions specified by the given flag, and
     * returns true if the next result is a ResultSet object
     *
     * @param current - one of the following Statement constants indicating what
     * should happen to current ResultSet objects obtained using the method
     * getResultSetCLOSE_CURRENT_RESULT, KEEP_CURRENT_RESULT, or CLOSE_ALL_RESULTS
     * @return true if the next result is a ResultSet; false if it is
     * an update count or there are no more results
     * @see #execute
     * @exception SQLException thrown on failure.
     */
	public final boolean getMoreResults(int current) throws SQLException	{
		checkExecStatus();

		synchronized (getConnectionSynchronization()) {
			if (dynamicResults == null) {
				// we only have the one resultset, so this is
				// simply a close for us.
				clearResultSets();
				return false;
			}

			int startingClose;
			switch (current) {
			default:
			case Statement.CLOSE_ALL_RESULTS:
				startingClose = 0;
				break;
			case Statement.CLOSE_CURRENT_RESULT:
				// just close the current result set.
				startingClose = currentDynamicResultSet;
				break;
			case Statement.KEEP_CURRENT_RESULT:
				// make the close loop a no-op.
				startingClose = dynamicResults.length;
				break;
			}

			// Close loop.
			SQLException se = null;
			for (int i = startingClose; i <= currentDynamicResultSet && i < dynamicResults.length; i++) {
				java.sql.ResultSet lrs = dynamicResults[i];
				if (lrs == null)
					continue;


				try {
					lrs.close();
				} catch (SQLException sqle) {
					if (se == null)
						se = sqle;
					else
						se.setNextException(sqle);
				} finally {
					dynamicResults[i] = null;
				}
			}

			if (se != null) {
				// leave positioned on the current result set (?)
				throw se;
			}

			updateCount = -1;

			while (++currentDynamicResultSet < dynamicResults.length) {

				java.sql.ResultSet lrs = dynamicResults[currentDynamicResultSet];
				if (lrs != null) {
				  if (lrs instanceof EmbedResultSet) {
					if (((EmbedResultSet)lrs).isClosed) {
						dynamicResults[currentDynamicResultSet] = null;
						continue;
					}
				  }
				  else if (lrs.isClosed()) {
				    dynamicResults[currentDynamicResultSet] = null;
				    continue;
				  }

// GemStone changes BEGIN
					setResultSet(lrs);
					/* (original code)
					results = lrs;
					*/
// GemStone changes END

					return true;
				}
			}

// GemStone changes BEGIN
			setResults(null);
			/* (original code)
			results = null;
			*/
// GemStone changes END
			return false;
		}
	}

    /**
     * JDBC 3.0
     *
     * Retrieves any auto-generated keys created as a result of executing this
     * Statement object. If this Statement is a non-insert statement,
     * a null ResultSet object is returned.
     *
     * @return a ResultSet object containing the auto-generated key(s) generated by
     * the execution of this Statement object
     * @exception SQLException if a database access error occurs
     */
	public final java.sql.ResultSet getGeneratedKeys() throws SQLException	{
		checkStatus();
		if (autoGeneratedKeysResultSet == null)
			return null;
		else {
// GemStone changes BEGIN
		  // get the resultset already held
		  return this.autoGeneratedKeysResultSet;
		  /* (original code)
		  execute("VALUES IDENTITY_VAL_LOCAL()", true, false, Statement.NO_GENERATED_KEYS, null, null);
		  return results;
		  */
// GemStone changes END
		}
	}

	/////////////////////////////////////////////////////////////////////////
	//
	//	Implementation specific methods	
	//
	/////////////////////////////////////////////////////////////////////////

	/**
		Execute the current statement.
	    @exception SQLException thrown on failure.
	*/
// GemStone changes BEGIN
	boolean executeStatement(Activation a,
	            boolean executeQuery, boolean executeUpdate,
	            boolean createQueryInfo, boolean isContextSet,
	            boolean skipContextRestore, boolean isPreparedBatch)
	    throws SQLException {

	  // the inprogress count will either decrement when an exception
	  // occurs within this function, and on success,
	  // EmbedResultSet.close is going to decrement it.
	  if (stats != null) {
	    stats.incStat(StatementStats.numExecutionsInProgressId,
	        createQueryInfo, 1);
	  }
	  SQLException throwEx = null;
// GemStone changes END
		// we don't differentiate the update from the resultset case.
		// so, there could be a result set.

		// note: the statement interface will paste together
		// an activation and make sure the prepared statement
		// is still valid, so it is preferrable, for now,
		// to creating our own activation and stuffing it in
		// the prepared statement.

		synchronized (getConnectionSynchronization()) {
// GemStone changes BEGIN
		  final GemFireXDQueryObserver observer =
		    GemFireXDQueryObserverHolder.getInstance();
		  if (observer != null) {
		    observer.beforeQueryExecution(this, a);
		  }
		  clearResultSets(isPreparedBatch);
		  // flags below added by GemStone
		  boolean distribute = false;
		  DDLConstantAction ddlAction = null;
		  boolean ddlNestedTransaction = false;
		  boolean stmtSuccess = false;
		  final boolean origAutoCommit = this.localConn.getAutoCommit();

//            if (SanityManager.DEBUG)
//            {
//                // Ensure that clearResultSets has been called
//                // to fulfill [JDBC4: section 15.2.5 ]
//                // A ResultSet object is implicitly closed when:
//                // The associated Statement object is re-executed
//                
//                SanityManager.ASSERT(results == null);
//                SanityManager.ASSERT(dynamicResults == null);
//                SanityManager.ASSERT(autoGeneratedKeysResultSet == null);
//           }
		  if (!isContextSet) {
		    setupContextStack(true); // make sure there's context
		  }
// GemStone changes END
			boolean retval;

			pvs = a.getParameterValueSet();

			try {

				clearWarnings();

// GemStone changes BEGIN
				doCommitIfNeeded(isPreparedBatch, true);
				/* (original code)
				if (! forMetaData) {
					commitIfNeeded(); // commit the last statement if needed
					needCommit();
				} else {


		        	if (lcc.getActivationCount() > 1) {
		     		  // we do not want to commit here as there seems to be other
					  // statements/resultSets currently opened for this connection.
					} else {
						commitIfNeeded(); // we can legitimately commit
						needCommit();
					}
				}
				*/
// GemStone changes END

				// if this was a prepared statement, this just
				// gets it for us, it won't recompile unless it is invalid.
				PreparedStatement ps = a.getPreparedStatement();
				ps.rePrepare(lcc, a);
				addWarning(ps.getCompileTimeWarnings());

// GemStone changes BEGIN
        // Check if command is not from a remote node
        // and constant action is DDLConstantAction
        ConstantAction act = ((GenericPreparedStatement)ps).getConstantAction();
        InternalDistributedSystem sys = null;
        GfxdWaitingReplyProcessor processor = null;
        boolean disableLogging = false;
        GemFireTransaction tran;
        final boolean connForRemote;
        GfxdDDLRegionQueue ddlQ = null;
        Long ddlId = Long.valueOf(0);
        if (this.lcc != null) {
          connForRemote = this.lcc.isConnectionForRemote();
          tran = (GemFireTransaction)this.lcc.getTransactionExecute();
        }
        else {
          connForRemote = false;
          tran = null;
        }
        if (act != null) {
          // wait for stats sampler initialization
          Misc.waitForSamplerInitialization();
        }
        // set autocommit to true temporarily for DDLs in the nested transaction
        if (act != null && act instanceof DDLConstantAction) {
          if (!connForRemote) {
            ddlAction = (DDLConstantAction)act;
            distribute = ddlAction.isReplayable();
            if (distribute) {
              ddlQ = Misc.getMemStore().getDDLStmtQueue();
              // DDL ID is deliberately different from statement ID itself since
              // it has to be unique even across restarts so has to be part of
              // a persistent region (DDL meta-region) whereas latter is
              // required to be unique only for a reasonable amount of time
              // and can be recycled at a later point of time
              ddlId = Long.valueOf(ddlQ.newUUID());
            }
            sys = Misc.getDistributedSystem();
            // Yogesh: Do not allow DDL to execute if no servers are available
            DistributionDescriptor.checkAvailableDataStore(lcc, null, "DDL "
                + this.SQLText);
            if (GemFireXDUtils.TraceDDLReplay) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
                  "EmbedStatement: Starting execution of DDL statement "
                      + "with remoting: " + this.SQLText);
            }
            // if required start a new nested transaction which shall be
            // committed
            // at the end
            this.lcc.beginNestedTransactionExecute(false,
                false /* commit existing GemFire transaction */,
                "start of DDL execution for: " + this.SQLText);
            // set auto-commit to true temporarily to enable commit/rollback
            // for this statement
            if (!origAutoCommit) {
              this.localConn.autoCommit = true;
            }
            ddlNestedTransaction = true;
            // Transaction would have changed due to nested execution begin
            tran = (GemFireTransaction)this.lcc.getTransactionExecute();
            // set the DDL ID for this transaction
            tran.setDDLId(ddlId.longValue());
          }
          if (tran != null) {
            // turn on logging to enable clean rollback in case of failure on
            // this or a remote node
            tran.enableLogging();
            disableLogging = true;
          }
        }
        DDLConflatable ddl = null;
        String defaultSchema = null;
        Set<DistributedMember> otherMembers = null;
        Set<DistributedMember> memberThatPersistOnHDFS = null;
        boolean hdfsPersistenceSuccess = false; 
        if (GemFireXDUtils.TraceExecution) {
          ParameterValueSet pvs;
          final String pvsStr;
          // mask passwords from being logged
          String sql = GemFireXDUtils.maskCreateUserPasswordFromSQLString(
              this.SQLText);
          if (isPrepared() && (pvs = getParameterValueSet()) != null
              && pvs.getParameterCount() > 0) {
            if (sql == null && pvs.getParameterCount() == 2) {
              // need to mask the second argument
              pvs = pvs.getClone();
              // param position is 0 based
              pvs.getParameter(1).setValue("***");
            }
            pvsStr = ", args: " + pvs;
          }
          else {
            pvsStr = "";
          }
          if (sql == null) {
            sql = this.SQLText;
          }
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
              "EmbedStatement#executeStatement: executing " + sql + pvsStr
                  + " ;statementId:" + this.getStatementUUID()
                  + " ;statementLevel:" + this.statementLevel + " ;rootID:"
                  + this.rootID);
        }
        try {
// GemStone changes END
				/*
				** WARNING WARNING
				**
				** Any state set in the activation before execution *must* be copied
				** to the new activation in GenericActivationHolder.execute() when
				** the statement has been recompiled. State such as
				** singleExecution, cursorName, holdability, maxRows.
				*/

				if (cursorName != null)
				{
					a.setCursorName(cursorName);
				}
                
                boolean executeHoldable = getExecuteHoldable();
 
				a.setResultSetHoldability(executeHoldable);

				//reset the activation to clear warnings
				//and clear existing result sets in case this has been cached
				a.reset(false);
				a.setMaxRows(maxRows);
// GemStone changes BEGIN
				a.setPreparedBatch(isPreparedBatch);
				a.setExecutionID(++executionID); //increment the execution count.
				a.setTimeOutMillis(timeoutMillis);
				ResultSet resultsToWrap = ps.execute(a, false,
				    timeoutMillis, !skipContextRestore, false);
//  GemStone changes END
				addWarning(a.getWarnings());


				if (resultsToWrap.returnsRows()) {

                    // The statement returns rows, so calling it with
                    // executeUpdate() is not allowed.
                    if (executeUpdate) {
                        throw StandardException.newException(
                                SQLState.LANG_INVALID_CALL_TO_EXECUTE_UPDATE);
                    }

					EmbedResultSet lresults = factory.newEmbedResultSet(getEmbedConnection(), resultsToWrap, forMetaData, this, ps.isAtomic());
// GemStone changes BEGIN
					/* (original code)
					results = lresults;
					*/
// GemStone changes END

					// Set up the finalization of the ResultSet to
					// mark the activation as unused. It will be
					// closed sometime later by the connection
					// outside of finalization.
					if (a.isSingleExecution())
						lresults.singleUseActivation = a;
// GemStone changes BEGIN
					setResults(lresults);
// GemStone changes END

					updateCount = -1;
					retval = true;
				}
				else {

					// Only applipable for an insert statement, which does not return rows.
					//the auto-generated keys resultset will be null if used for non-insert statement
// GemStone changes BEGIN
				  if (!isPreparedBatch
				      && a.getAutoGeneratedKeysResultsetMode()
				      && resultsToWrap.hasAutoGeneratedKeysResultSet())
				    /* (original code)
					if (a.getAutoGeneratedKeysResultsetMode() && (resultsToWrap.getAutoGeneratedKeysResultset() != null))
				    */
// GemStone changes END
					{
						resultsToWrap.getAutoGeneratedKeysResultset().open();
						autoGeneratedKeysResultSet = factory.newEmbedResultSet(getEmbedConnection(),
							resultsToWrap.getAutoGeneratedKeysResultset(), false, this, ps.isAtomic());
						
// GemStone changes BEGIN
						//The stock derby code leaves this autoGeneratedKeysResultSet 
						//with incorrect metadata, and then uses a query to 
						//satisfy calls to getGeneratedKeys. For GemFireXD, we want to
						//correct the metadata for this result set.
						
						//The autogenerated column ids were set on the activation in GemfireInsertResultSet.openCore
						int[] autoGeneratedColumns = resultsToWrap.getActivation().getAutoGeneratedKeysColumnIndexes();
						ResultDescription oldDescription = resultsToWrap.getActivation().getResultDescription();
						ResultColumnDescriptor[] savedDescriptors = new ResultColumnDescriptor[autoGeneratedColumns.length];
						for(int i =0; i < autoGeneratedColumns.length; i++) {
						  for(ResultColumnDescriptor rcd : oldDescription.getColumnInfo()) {
						    if(rcd.getColumnPosition() == autoGeneratedColumns[i]) {
						      savedDescriptors[i] = rcd;
						    }
						  }
						}
						
						autoGeneratedKeysResultSet.setResultDescription(
						    new GenericResultDescription(savedDescriptors, "INSERT"));
// GemStone changes END
					}

					updateCount = resultsToWrap.modifiedRowCount();
// GemStone changes BEGIN
					if (lcc.getRunTimeStatisticsMode()) {
					  iapiResultSet = resultsToWrap;
					  gps = ps;
					}
					setResults(null);
					/* (original code)
					results = null; // note that we have none.
					*/
// GemStone changes END

                    int dynamicResultCount = 0;
					if (a.getDynamicResults() != null) {
                        dynamicResultCount =
                            processDynamicResults(a.getDynamicResults(),
                                                  a.getMaxDynamicResults());
					}
// GemStone changes BEGIN
            if (distribute) {
              // refresh DDLConstantAction since it may have changed
              // due to reprepare etc.
              ddlAction = (DDLConstantAction)((GenericPreparedStatement)ps)
                  .getConstantAction();
              // don't distribute/enqueue DDLs that are local/session specific
              distribute = ddlAction.isReplayable();
              if (ddlAction instanceof IndexConstantAction) {
                // distribute only if non-fatal warnings
                SQLWarning warn = a.getWarnings();
                distribute = (warn == null || !SQLState.LANG_INDEX_DUPLICATE
                    .equals(warn.getSQLState()));
              }
            }
// GemStone changes END
                    resultsToWrap.close(false); // Don't need the result set any more

                    // executeQuery() is not allowed if the statement
                    // doesn't return exactly one ResultSet.
                    if (executeQuery && dynamicResultCount != 1) {
                        throw StandardException.newException(
                                SQLState.LANG_INVALID_CALL_TO_EXECUTE_QUERY);
                    }

                    // executeUpdate() is not allowed if the statement
                    // returns ResultSets.
                    if (executeUpdate && dynamicResultCount > 0) {
                        throw StandardException.newException(
                                SQLState.LANG_INVALID_CALL_TO_EXECUTE_UPDATE);
                    }
					
                    if (dynamicResultCount == 0) {
						if (a.isSingleExecution()) {
							a.close();
						}

// GemStone changes BEGIN
						else if (updateCount >= 0) {
						  a.reset(false);
						}
						if (!distribute)
						  if (!origAutoCommit && !tran.getImplcitSnapshotTxStarted()) {
						    if (!tran.isTransactional() && !isPreparedBatch && (act == null || !(act instanceof LockTableConstantAction))) {
						      tran.releaseAllLocks(
						          false, false);
						    }
						  }
                                                  else {
                                                    doCommitIfNeeded(isPreparedBatch, false);
                                                  }
						/* (original code)
						if (!forMetaData)
							commitIfNeeded();
						else {

							if (lcc.getActivationCount() > 1) {
							  // we do not want to commit here as there seems to be other
							  // statements/resultSets currently opened for this connection.
							} else {
								commitIfNeeded(); // we can legitimately commit
							}
						}
						*/
// GemStone changes END
					}

                    retval = (dynamicResultCount > 0);
                                        // GemStone changes BEGIN
                                        //if not returning rows, lets adjust the counters here.
                                        if (stats != null) {
                                          stats.incStat(StatementStats.numExecutionsInProgressId,
                                              createQueryInfo, -1);
                                          stats.incStat(StatementStats.numExecutionsId, createQueryInfo, 1);
                                        }
                                        // GemStone changes END
				}
// GemStone changes BEGIN
          if (distribute) {
            final GemFireStore memStore = Misc.getMemStore();
            // Send the message to all booted members in the DistributedSystem
            //otherMembers = (Set)ddlQ.getRegion().getCacheDistributionAdvisor()
            //    .adviseReplicates();
            //if (otherMembers.size() > 0) {
            //  otherMembers.remove(sys.getDistributedMember());
            //}
            otherMembers = GfxdMessage.getOtherMembers();
            // If no others members in DS currently then don't distribute
            // using GfxdDDLMessage or wait for GfxdDDLFinishMessage on the
            // receiving side, but do put in the GfxdDDLRegionQueue
            if (otherMembers.size() == 0) {
              distribute = false;
            }
            // Put the DDL in hidden region to enable execution by new VMs
            // later, and then distribute the DDL to all members. Note the order
            // of execution since we want to ensure that nothing is missed by
            // new upcoming members. The DDL put in the hidden queue will be
            // with wait flag set so that any new VMs will wait on it till
            // finish message is not received.
            // authorizationId = IdUtil.getUserAuthorizationId(this.localConn
            // .getTR().getUserName());
            defaultSchema = this.localConn.getLanguageConnection()
                .getDefaultSchema().getSchemaName();
            final Object additionalArgs = this.lcc.getContextObject();
            // clear the context object after retrieval
            this.lcc.setContextObject(null);
            // if any schema was created implicitly, then add a CREATE SCHEMA to
            // the queue so that if there is any DROP SCHEMA done later it works
            // correctly (else a dangling DROP SCHEMA may remain once
            // CREATE/DROP TABLE has been conflated, for example)
            DDLConflatable schemaDDL = null;
            final boolean queueInitialized = ddlQ.isInitialized()
                && (memStore.initialDDLReplayInProgress() || memStore
                    .initialDDLReplayDone());
            final CreateSchemaConstantAction csca;
            if ((csca = ddlAction.getImplicitSchemaCreated()) != null) {
              long schemaDDLId = ddlQ.newUUID();
              schemaDDL = new DDLConflatable(csca.toString(), defaultSchema,
                  csca, null, null, schemaDDLId, queueInitialized, lcc);
            }
            ddl = new DDLConflatable(this.SQLText, defaultSchema, ddlAction,
                additionalArgs, schemaDDL, ddlId.longValue(), queueInitialized, lcc);
            if (distribute) {
              if (schemaDDL != null) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
                    "EmbedStatement: Implicit schema creation of "
                        + schemaDDL.getRegionToConflate() + " being enqueued");
              }
              if (GemFireXDUtils.getMyVMKind().isAccessor() && ddl != null &&
                  ddl.isHDFSPersistent()) {
                Set<DistributedMember> dataStores = GfxdMessage.getDataStores();
                DistributedMember selectedmember = null;
                while (dataStores.size() > 0) {
                  for (DistributedMember member : dataStores) {
                    if (!Misc.getGemFireCache().isUnInitializedMember((InternalDistributedMember)member)){
                      selectedmember = member;
                      break;
                    }
                  }
                  if (selectedmember == null) {
                    throw StandardException.newException(SQLState.NO_DATASTORE_FOUND,
                        "execution of DDL " + this.SQLText);
                  }
                  memberThatPersistOnHDFS = new HashSet<DistributedMember>();
                  memberThatPersistOnHDFS.add(selectedmember);
                  processor = GfxdDDLMessage.getReplyProcessor(sys, memberThatPersistOnHDFS,
                      true);
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
                      "EmbedStatement: Sending DDL statement " + ddl
                          + " [" + ddlId + "] to other VMs in the "
                          + "distributed system for execution: " + selectedmember + ". This VM " +
                          		"is responsible for persisting the statement on HDFS. ");
                  GfxdDDLMessage.send(sys, processor, memberThatPersistOnHDFS, ddl,
                      localConn.getConnectionID(), ddlId.longValue(), this.lcc, true);
                  if (processor != null && processor.hasGrantedMembers()) {
                    hdfsPersistenceSuccess = true;
                    otherMembers.remove(selectedmember);
                    break;
                  }
                  dataStores.remove(selectedmember);
                }
                if (processor == null || !processor.hasGrantedMembers()){
                  throw StandardException.newException(SQLState.NO_DATASTORE_FOUND,
                      "execution of DDL " + this.SQLText);
                }
              }
              
              if (otherMembers.size() > 0) {
                processor = GfxdDDLMessage.getReplyProcessor(sys, otherMembers,
                    true);
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
                    "EmbedStatement: Sending DDL statement " + ddl
                        + " [" + ddlId + "] to other VMs in the "
                        + "distributed system for execution: " + otherMembers);
                GfxdDDLMessage.send(sys, processor, otherMembers, ddl,
                    localConn.getConnectionID(), ddlId.longValue(), this.lcc);
              }
            }
            if (GemFireXDUtils.getMyVMKind().isAccessor()
                && (processor == null || !processor.hasGrantedMembers())) {
              throw StandardException.newException(SQLState.NO_DATASTORE_FOUND,
                  "execution of DDL " + this.SQLText);
            }
          }
          else if (!connForRemote && tran != null) {
            // flush any batched operations at this point (especially required
            //   for replicated regions that do not create GFE activation and
            //   hence do not go through function message route)
            final TXStateProxy txProxy = tran.getCurrentTXStateProxy();
            if (txProxy != null && !txProxy.batchingEnabled()) {
              txProxy.flushPendingOps(null);
            }
          } 

          if (GemFireXDUtils.getMyVMKind().isStore() && ddl != null 
              && ddl.isHDFSPersistent() &&  !connForRemote) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
                "EmbedStatement: Persisting statement on HDFS " + this.SQLText
                    + " this : " + GemFireStore.getMyId());
            // Find out all the HDFS stores where this ddl belongs
            ArrayList<HDFSStoreImpl> destinationhdfsStores = getDestinationHDFSStoreForDDL(ddl, lcc);
            if (destinationhdfsStores != null && destinationhdfsStores.size() != 0){
              persistOnHDFS(ddl, lcc, destinationhdfsStores);
            }
          }
          stmtSuccess = true;
        } catch (Throwable t) {
          // log the base exception and throw it back
          if (GemFireXDUtils.TraceFunctionException) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX,
                "EmbedStatement: exception for SQL: " + this.SQLText, t);
          }
          throw t;
        } finally {
          if (disableLogging) {
            tran.disableLogging();
          }
          // reset DDL ID
          if (tran != null) {
            tran.setDDLId(0);
          }
          // Put the statement in the DDL queue.
          long sequenceId = -1;
          if (ddl != null) {
            if (stmtSuccess) {
              if (GemFireXDUtils.TraceDDLReplay) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
                    "EmbedStatement: Putting DDL statement in hidden region "
                        + "for remoting: " + ddl);
              }
              // do the region put only locally since distributed one is done
              // by finish message
              // first put the implicit schema DDL, if any
              DDLConflatable schemaDDL;
              if ((schemaDDL = ddl.getImplicitSchema()) != null) {
                ddl.setImplicitSchemaSequenceId(ddlQ.put(
                    Long.valueOf(schemaDDL.getId()), -1, schemaDDL,
                    true, true /* localPut */));
              }
              sequenceId = ddlQ.put(ddlId, -1, ddl, true, true /* localPut */);
            }
            // this will either commit the DDL or roll it back on other nodes
            // if DDL execution fails on any of the nodes due to SQLException
            // then we need to roll it back on other nodes
            // send the finish message to all nodes including locators since
            // the DDL needs to go into the DDL region in any case
            //Set<DistributedMember> members = processor.getGrantedMembers();
            if (distribute) {
              if (memberThatPersistOnHDFS != null && !hdfsPersistenceSuccess) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
                    "EmbedStatement: Sending GfxdDDLFinishMessage for " + this.SQLText
                        + '[' + ddlId.longValue() + "] to VM in the "
                        + "distributed system for execution: " + memberThatPersistOnHDFS.iterator().next() + ". This VM was " +
                            "responsible for persisting the statement on HDFS. ");
                GfxdDDLFinishMessage.send(sys, memberThatPersistOnHDFS, ddl,
                    this.localConn.getConnectionID(), ddlId.longValue(),
                    sequenceId, stmtSuccess); 
              }
              else {
                if (memberThatPersistOnHDFS != null)
                  otherMembers.add(memberThatPersistOnHDFS.iterator().next());
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
                    "EmbedStatement: Sending GfxdDDLFinishMessage for DDL statement " + this.SQLText
                        + '[' + ddlId.longValue() + "] to other VMs in the "
                        + "distributed system for execution: " + otherMembers + ". This VM " +
                            "is responsible for persisting the statement on HDFS. ");
                GfxdDDLFinishMessage.send(sys, otherMembers, ddl,
                    this.localConn.getConnectionID(), ddlId.longValue(),
                    sequenceId, stmtSuccess);
              }
            }
          }
          if (ddlNestedTransaction && stmtSuccess) {
            this.lcc.commitNestedTransactionExecute();
          }
        }
      } catch (Throwable t) {
        SQLException abortEx = null;
        if (ddlNestedTransaction) {
          // abort the nested DDL TX first
          try {
            this.lcc.abortNestedTransactionExecute();
          } catch (Throwable ae) {
            abortEx = TransactionResourceImpl.wrapInSQLException(ae);
          }
          // reset auto-commit flag before proceeding else it will also
          // abort the parent transaction
          if (!origAutoCommit && !this.localConn.isClosed()) {
            this.localConn.autoCommit = false;
          }
        }
        if (a.isSingleExecution()) {
          try {
            a.close();
          } catch (Throwable tt) {
            // ignored
          }
        }
        // ignore "already exists" or "does not exist" kind of exceptions in
        // DDL executions from client during retries and log a warning (#42795)
        final SQLWarning ddlWarning;
        if (t.getClass() == StandardException.class) {
          fillInColumnName((StandardException)t, null, a);
        }
        else if (ddlAction != null && (ddlWarning = ignoreDDLForClientRetry(
            this.lcc, t)) != null) {
          // log a warning and return with success
          addWarning(ddlWarning);
          return false; /* no ResultSets for DDLs */
        }
        throwEx = handleException(t);
        setSQLExceptionCause(throwEx, abortEx);
        // now cleanup the nested DDL transaction
        if (ddlNestedTransaction) {
          try {
            this.lcc.cleanupNestedTransactionExecute();
          } catch (Throwable cleanEx) {
            SQLException ce = TransactionResourceImpl
                .wrapInSQLException(cleanEx);
            setSQLExceptionCause(throwEx, ce);
          }
        }
        if (stats != null) {
          stats.incStat(StatementStats.numExecutionsInProgressId,
              createQueryInfo, -1);
        }
        if (observer != null) {
          // allow observer to override the result with another one in case
          // of exceptions or otherwise
          if (observer.afterQueryExecution(this, throwEx)) {
            // return value of true means that don't throw exception
            // rather the result has been filled in the statement using
            // either setResultSet or setUpdateCount by the observer
            return this.results != null;
          }
        }
        this.clearResultSets();
        throw throwEx;
      } finally {
        // if execution of statement failed for some reason then cleanup
        // context stack in any case; higher layer remote statement execution
        // will cleanup only in the case when execute is success;
        // isSysSchemaBased check is here is since queryInfo is not created
        // for those and the check createQueryInfo would be invalid then
        if ((!skipContextRestore || !stmtSuccess) && !isContextSet) {
          restoreContextStack();
        }
        if (ddlNestedTransaction && !origAutoCommit
            && !this.localConn.isClosed()) {
          this.localConn.autoCommit = false;
        }
        if (observer != null && throwEx == null) {
          observer.afterQueryExecution(this, throwEx);
        }
        /* (original code)
	        } catch (Throwable t) {
				if (a.isSingleExecution()) {
					try { a.close(); } catch (Throwable tt) {;}
				}
		        throw handleException(t);
			} finally {
	*/
// GemStone changes END
			}
			return retval;
		}
	}

	final void doCommitIfNeeded(final boolean isPreparedBatch,
	    final boolean markNeedCommit) throws SQLException {
	  if (!forMetaData) {
	    if (!isPreparedBatch) {
	      commitIfNeeded(); // commit the last statement if needed
	      if (markNeedCommit) {
	        needCommit();
	      }
	    }
	  }
	  else {
	    if (lcc.getActivationCount() > 1) {
	      // we do not want to commit here as there seems to be other
	      // statements/resultSets currently opened for this connection.
	    }
	    else {
	      commitIfNeeded(); // we can legitimately commit
	      if (markNeedCommit) {
	        needCommit();
	      }
	    }
	  }
	}

	private static void setSQLExceptionCause(SQLException sqle,
	    SQLException next) {
	  if (sqle != null && next != null) {
	    sqle.setNextException(next);
	    // also try to set as cause at the end of chain if possible
	    Throwable nextEx = sqle;
	    while (nextEx.getCause() != null) {
	      nextEx = nextEx.getCause();
	    }
	    try {
	      nextEx.initCause(next);
	    } catch (IllegalStateException ise) {
	      // ignore if we failed to set as cause
	    }
	  }
	}

    /**
     * Add a SQLWarning to this Statement object.
     * If the Statement already has a SQLWarning then it
     * is added to the end of the chain.
     * 
     * @see #getWarnings()
     */
	final void addWarning(SQLWarning sw)
	{
		if (sw != null) {
			if (warnings == null)
				warnings = sw;
			else
				warnings.setNextException(sw);
		}
	}


	/* package */
	public String getSQLText()
	{
		// no need to synchronize - accessing a reference is atomic
		// synchronized (getConnectionSynchronization()) 
		return SQLText;
	}

	public ParameterValueSet getParameterValueSet()
	{
		return pvs;
	}
	
	public Activation getActivation(){
	  return this.activation;
	}
	
	public int getExecutionID() {
	  return this.executionID;
	}
	
	/**
	 * Used in Sessions VTI
	 */
        public String getStatementUUID() {
          return getEmbedConnection().getConnectionID() + "-" + this.statementID
              + "-" + this.executionID;
        }
	
	 /**
         * Used in Sessions VTI
	 * @throws StandardException 
         */
        public long getEstimatedMemoryUsage() throws StandardException {
          if (this.activation == null) {
            return 0;
          } else {
            return this.activation.estimateMemoryUsage();
          }
        }

	/**
     * Throw an exception if this Statement has been closed explictly
     * or it has noticed it has been closed implicitly.
     * JDBC specifications require nearly all methods throw a SQLException
     * if the Statement has been closed, thus most methods call this
     * method or checkExecStatus first.
     * 
     * @exception SQLException Thrown if the statement is marked as closed.
     * 
     * @see #checkExecStatus()
	 */
    final void checkStatus() throws SQLException {
		if (!active) {
            // 
            // Check the status of the connection first
            //
            java.sql.Connection appConn = getEmbedConnection().getApplicationConnection();
            if (appConn == null || appConn.isClosed()) {
                throw Util.noCurrentConnection();
            }

            throw newSQLException(SQLState.ALREADY_CLOSED, "Statement");
        }
	}

	/**
		A heavier weight version of checkStatus() that ensures the application's Connection
		object is still open. This is to stop errors or unexpected behaviour when a [Prepared]Statement
		object is used after the application has been closed. In particular to ensure that
		a Statement obtained from a PooledConnection cannot be used after the application has closed
		its connection (as the underlying Connection is still active).
		To avoid this heavier weight check on every method of [Prepared]Statement it is only used
		on those methods that would end up using the database's connection to read or modify data.
		E.g. execute*(), but not setXXX, etc.
        <BR>
        If this Statement's Connection is closed an exception will
        be thrown and the active field will be set to false,
        completely marking the Statement as closed.
        <BR>
        If the Statement is not currently connected to an active
        transaction, i.e. a suspended global transaction, then
        this method will throw a SQLException but the Statement
        will remain open. The Statement is open but unable to
        process any new requests until its global transaction
        is resumed.
        <BR>
        Upon return from the method, with or without a SQLException
        the field active will correctly represent the open state of
        the Statement.
        
        @exception SQLException Thrown if the statement is marked as closed
        or the Statement's transaction is suspended.
        
        @see #checkStatus()
	*/
	final void checkExecStatus() throws SQLException {
		// getConnection() checks if the Statement is closed
          checkStatus();

          final EmbedConnection conn = this.localConn;
          java.sql.Connection appConn = conn.getApplicationConnection();
          if (conn == appConn) {
            if (conn.isActive()) {
              return;
            }
            else {
              throw Util.noCurrentConnection();
            }
          }
          if (appConn == applicationConnection && appConn != null && !appConn.isClosed()) {
            return;
          }

        // Now this connection is closed for allh
        // future use.
        active = false;
        	
		throw Util.noCurrentConnection();
	}

	/**
		Close and clear all result sets associated with this statement
		from the last execution.
	*/
// GemStone changes BEGIN
	void clearResultSets() throws SQLException {
	  clearResultSets(false);
	}

	@Override
	public void resetForReuse() throws SQLException {
	  // will get closed either via EmbedResultSet or
	  // right after collecting updateCount.
	  // here we just need to release the pointer.
	  iapiResultSet = null;
	  // first, clear the result set
	  clearResultSets();
	  // next, release other resource
	  cursorName = null;
	  warnings = null;
	  SQLText = null;
	  batchStatements = null;
	  batchStatementCurrentIndex = 0;
	  clearParameters();
	}

	@Override
	public boolean hasDynamicResults() {
	  return this.dynamicResults != null;
	}

	void clearResultSets(boolean isPreparedBatch) throws SQLException {
// GemStone changes END

		SQLException sqle = null;

		try {
			// Are there results?
			// outside of the lower try/finally since results will
			// setup and restore themselves.
// GemStone changes BEGIN
			final java.sql.ResultSet results = this.results;
			if (results != null) {
			  results.close();
			  setResults(null);
			
			/* (original code)
			if (results != null) {
				results.close();
				results = null;
			*/
// GemStone changes END
			}
		} catch (SQLException s1) {
			sqle = s1;
		}

		try {
// GemStone changes BEGIN
		  if (!isPreparedBatch)
// GemStone changes END
			if (autoGeneratedKeysResultSet != null) {
				autoGeneratedKeysResultSet.close();
				autoGeneratedKeysResultSet = null;
			}
		} catch (SQLException sauto) {
			if (sqle == null)
				sqle = sauto;
			else
				sqle.setNextException(sauto);
		}

		// close all the dynamic result sets.
		if (dynamicResults != null) {
			for (int i = 0; i < dynamicResults.length; i++) {
				java.sql.ResultSet lrs = dynamicResults[i];
				if (lrs == null)
					continue;

				try {
					lrs.close();
				} catch (SQLException sdynamic) {
					if (sqle == null)
						sqle = sdynamic;
					else
						sqle.setNextException(sdynamic);
				}
			}
			dynamicResults = null;
		}

		/*
			  We don't reset statement to null because PreparedStatement
			  relies on it being there for subsequent (post-close) execution
			  requests.  There is no close method on database statement objects.
		*/

		updateCount = -1; // reset field

		if (sqle != null)
			throw sqle;
	 }  
	
	/**
		Check to see if a statement requires to be executed via a callable statement.
	*/
	void checkRequiresCallableStatement(Activation activation) throws SQLException {

		ParameterValueSet pvs = activation.getParameterValueSet();

		if (pvs == null)
			return;

		if (pvs.checkNoDeclaredOutputParameters()) {
			try {
				activation.close();
			} catch (StandardException se) {
			}
			throw newSQLException(SQLState.REQUIRES_CALLABLE_STATEMENT, SQLText);
		}
	}

	/**
		Transfer my batch of Statements to a newly created Statement.
	*/
	public void transferBatch(EmbedStatement other) throws SQLException {
		
		synchronized (getConnectionSynchronization()) {
			other.batchStatements = batchStatements;
			batchStatements = null;
		}
	}
    
    /**
     * Set the application statement for this Statement.
    */
    public final void setApplicationStatement(EngineStatement s) {
        this.applicationStatement = s;
    }

	private java.sql.ResultSet[] dynamicResults;
	private int currentDynamicResultSet;

    /**
     * Go through a holder of dynamic result sets, remove those that
     * should not be returned, and sort the result sets according to
     * their creation.
     *
     * @param holder a holder of dynamic result sets
     * @param maxDynamicResultSets the maximum number of result sets
     * to be returned
     * @return the actual number of result sets
     * @exception SQLException if an error occurs
     */
    private int processDynamicResults(java.sql.ResultSet[][] holder,
                                      int maxDynamicResultSets)
        throws SQLException
    {

		java.sql.ResultSet[] sorted = new java.sql.ResultSet[holder.length];

		int actualCount = 0;
		for (int i = 0; i < holder.length; i++) {

			java.sql.ResultSet[] param = holder[i];

			java.sql.ResultSet rs = param[0];

            // Clear the JDBC dynamic ResultSet from the language
            // ResultSet for the CALL statement. This stops the
            // CALL statement closing the ResultSet when its language
            // ResultSet is closed, which will happen just after the
            // call to the processDynamicResults() method.
			param[0] = null;
            
            // ignore non-Derby result sets or results sets from another connection
            // and closed result sets.
            java.sql.ResultSet lrs = EmbedStatement.processDynamicResult(
                    getEmbedConnection(), rs, this);
            
            if (lrs == null)
            {
                continue;
            }
//Gemstone changes Begin
            if (lrs instanceof EmbedResultSet) {
               ((EmbedResultSet)lrs).setResultsetIndex(i);
            }
//Gemstone changes End
                sorted[actualCount++] = lrs;
		}

		if (actualCount != 0) {

			// results are defined to be ordered according to their creation
// GemStone changes BEGIN
		  // base the order on result set number and not the order;
		  // when using OutgoingResultSet the order is zero since it
		  // is on a rootConnection (see EmbedConnection.getResultSetOrderId)
		  // and so it always comes as first regardless of its number;
		  // in any case the user will expect to receive in the order of
		  // its number and not creation time
		  /* (original code)
			if (actualCount != 1) {
				java.util.Arrays.sort(sorted, 0, actualCount);
			}
		  */
// GemStone changes END

			dynamicResults = sorted;

			if (actualCount > maxDynamicResultSets) {
				addWarning(StandardException.newWarning(SQLState.LANG_TOO_MANY_DYNAMIC_RESULTS_RETURNED));

				for (int i = maxDynamicResultSets; i < actualCount; i++) {
					sorted[i].close();
					sorted[i] = null;
				}

				actualCount = maxDynamicResultSets;
			}


			updateCount = -1;
// GemStone changes BEGIN
			setResultSet(sorted[0]);
			/* (original code)
			results = sorted[0];
			*/
// GemStone changes END
			currentDynamicResultSet = 0;

			// 0100C is not returned for procedures written in Java, from the SQL2003 spec.
			// getWarnings(StandardException.newWarning(SQLState.LANG_DYNAMIC_RESULTS_RETURNED));
		}


		return actualCount;
	}
    
    /**
     * Process a ResultSet created in a Java procedure as a dynamic result.
     * To be a valid dynamic result the ResultSet must be:
     * <UL>
     * <LI> From a Derby system
     * <LI> From a nested connection of connection passed in
     * or from the connection itself.
     * <LI> Open
     * </UL>
     * Any invalid ResultSet is ignored.
     * 
     * 
     * @param conn Connection ResultSet needs to belong to
     * @param resultSet ResultSet to be tested
     * @param callStatement Statement that executed the CALL, null if 
     * @return The result set cast down to EmbedResultSet, null if not a valid
     * dynamic result.
     */
    //Gemstone changes Begin
  public   static java.sql.ResultSet processDynamicResult(EmbedConnection conn,
            java.sql.ResultSet resultSet,
            EmbedStatement callStatement)
   //Gemstone changes End 
    {
        if (resultSet == null)
            return null;

// GemStone changes BEGIN
        if (resultSet instanceof NonUpdatableRowsResultSet) {
          return resultSet;
        }
// GemStone changes END
        // ignore non-Derby result sets or results sets from another connection
        if (!(resultSet instanceof EmbedResultSet))
            return null;

        EmbedResultSet lrs = (EmbedResultSet) resultSet;

        if (lrs.getEmbedConnection().rootConnection != conn.rootConnection)
            return null;

        // ignore closed result sets.
        try {
        	//following will check if the JDBC ResultSet or the language
        	//ResultSet is closed. If yes, then it will throw an exception.
        	//So, the exception indicates that the ResultSet is closed and
        	//hence we should ignore it. 
        	lrs.checkIfClosed("");
        } catch (SQLException ex) {
            return null;        	
        }
        
        lrs.setDynamicResultSet(callStatement);

        return lrs;
    }

	/**
		Callback on the statement when one of its result sets is closed.
		This allows the statement to control when it completes and hence
		when it commits in auto commit mode.

        Must have connection synchronization and setupContextStack(), this
        is required for the call to commitIfNeeded().
	*/
	void resultSetClosing(EmbedResultSet closingLRS) throws SQLException {

		// If the Connection is not in auto commit then this statement completion
		// cannot cause a commit.
		if (!getEmbedConnection().autoCommit)
			return;

		// If we have dynamic results, see if there is another result set open.
		// If so, then no commit. The last result set to close will close the statement.
		if (dynamicResults != null) {
			for (int i = 0; i < dynamicResults.length; i++) {
				java.sql.ResultSet rs = dynamicResults[i];
				if (rs == null) {
				  continue;
				}
				if (!(rs instanceof EmbedResultSet)) {
				  if (rs == null || rs.isClosed()) {
				    continue;
				  }
				  return;
				}
				EmbedResultSet lrs = (EmbedResultSet)rs;
				if (lrs.isClosed)
					continue;
				if (lrs == closingLRS)
					continue;

				// at least one still open so no commit now.
				return;
			}
		}

		// new Throwable("COMMIT ON " + SQLText).printStackTrace(System.out);

        // beetle 5383.  Force a commit in autocommit always.  Before this
        // change if client in autocommit opened a result set, did a commit,
        // then next then close a commit would not be forced on the close.
		commitIfAutoCommit();
	}
    
    /**
     * Get the execute time holdability for the Statement.
     * When in a global transaction holdabilty defaults to false.
     * @throws SQLException Error from getResultSetHoldability.
     */
    private boolean getExecuteHoldable() throws SQLException
    {
        if (resultSetHoldability  == java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT)
            return false;
        
        // Simple non-XA case
        if (applicationStatement == this)
            return true;
        
        return applicationStatement.getResultSetHoldability() ==
            java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

	/**
	 * Returns the value of the EmbedStatement's poolable hint,
	 * indicating whether pooling is requested.
	 *
	 * @return The value of the poolable hint.
	 * @throws SQLException if the Statement has been closed.
	 */

	public boolean isPoolable() throws SQLException {
		// Assert the statement is still active (not closed)
		checkStatus();

		return isPoolable;
	}                

	/**
	 * Requests that an EmbedStatement be pooled or not.
	 *
	 * @param poolable requests that the EmbedStatement be pooled if true
	 * and not be pooled if false.
	 * @throws SQLException if the EmbedStatement has been closed.
	 */
     
	public void setPoolable(boolean poolable) throws SQLException {
		// Assert the statement is still active (not closed)
		checkStatus();

		isPoolable = poolable;
	}


// GemStone changes BEGIN
  @Override
  public boolean isPrepared() {
    return false;
  }

  // JDBC 4.1 methods so will compile in JDK 1.7
  public void closeOnCompletion() throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }

  public boolean isCloseOnCompletion() throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }
  
  public StatementStats getStatementStats() {
    return stats;
  }

  private void checkAttributes(int type, int concurrency, int holdability)
      throws SQLException {
    if (type == java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE
        && concurrency == java.sql.ResultSet.CONCUR_UPDATABLE) {
      // if this is supported, disable exception throw in
      // com.pivotal.gemfirexd.internal.client.am.Statement#initStatement as
      // well.
      throw newSQLException(SQLState.UPDATABLE_SCROLL_INSENSITIVE_NOT_SUPPORTED);
    }
  }

  @Override
  public void reset(int newType, int newConcurrency, int newHoldability)
      throws SQLException {
    checkAttributes(newType, newConcurrency, newHoldability);
    this.resultSetType = newType;
    this.resultSetConcurrency = newConcurrency;
    this.resultSetHoldability = newHoldability;
    // reset some other attributes
    this.timeoutMillis = 0;
    this.maxRows = 0;
    this.maxFieldSize = 0;
    this.cursorName = null;
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName() + '@'
        + Integer.toHexString(System.identityHashCode(this)) + ", "
        + this.SQLText;
  }

  public ResultSet getResultsToWrap() {
    return iapiResultSet;
  }

  public PreparedStatement getGPrepStmt() {
    return gps;
  }
  

  /**
   * Find out all the HDFS stores where this ddl belongs. Then find out all the DDLs that belongs to those 
   * HDFS stores. Once done, persist the ddls to their respective HDFS stores
   */
  public static void persistOnHDFS(DDLConflatable ddl, LanguageConnectionContext lcc, 
      ArrayList<HDFSStoreImpl> destinationhdfsStores)
      throws StandardException {
    ArrayList<String> storeNames = new ArrayList<String>();
    for (HDFSStoreImpl hdfsstore : destinationhdfsStores) {
      storeNames.add(hdfsstore.getName());
    }

    // find out all the DDLs that belongs to the given list of HDFS stores
    HashMap<String, ArrayList<DDLConflatable>> storeToDDLs =
        getListOfDDLsForHDFSStoreNames(storeNames, lcc);
    // persist the ddls to their respective HDFS stores
    for (HDFSStoreImpl hdfsstore : destinationhdfsStores) {
      ArrayList<DDLConflatable> ddlListTobePersisted = storeToDDLs
          .get(hdfsstore.getName());

      // add the new ddl to the list of ddls
      ddlListTobePersisted.add(ddl);
      ArrayList<byte[]> keyList = new ArrayList<byte[]>();
      ArrayList<byte[]> valueList = new ArrayList<byte[]>();
      try {
        for (DDLConflatable ddlstmt : ddlListTobePersisted) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
              "EmbedStatement: Persisting all statements on HDFS " + ddlstmt.getValueToConflate());
          byte[] valueBytes = BlobHelper.serializeToBlob(ddlstmt);
          byte[] keyBytes = BlobHelper.serializeToBlob(ddlstmt.getId());
          keyList.add(keyBytes);
          valueList.add(valueBytes);
        }
      } catch(IOException e) {
        throw new InternalGemFireError("Could not serialize DDL statement", e);
      }
      try {
      hdfsstore.getDDLHoplogOrganizer().flush(keyList.iterator(),
          valueList.iterator());
      } catch (HDFSIOException e) {
        throw StandardException.newException(SQLState.HDFS_ERROR, e, e.getMessage());
      }
    }
  }

  /**
   * find out all the DDLs that belongs to the given list of HDFS stores
   */
  private static HashMap<String, ArrayList<DDLConflatable>> getListOfDDLsForHDFSStoreNames(
      ArrayList<String> listOfHDFSStores, LanguageConnectionContext lcc) throws 
      StandardException {
    
    HashMap<String, ArrayList<DDLConflatable>> storeToDDLs = 
        new HashMap<String, ArrayList<DDLConflatable>>();
    
    // initialize the hash maps with empty arrays
    for (String storeName : listOfHDFSStores) {
      storeToDDLs.put(storeName, new ArrayList<DDLConflatable>());
    }
    
    // get the list of DDLs in the queue 
    List<GfxdDDLQueueEntry> allDDLs = getAllDDLs();
    
    if (allDDLs == null)
      return storeToDDLs;
    
    // loop through the ddls to find out the ones that need to go to the HDFS store. 
    for (GfxdDDLQueueEntry queueEntry : allDDLs) {
      final Object qVal = queueEntry.getValue();
      if (qVal instanceof DDLConflatable) {
        // find out the list of HDFS stores where this DDL should belong
        ArrayList<HDFSStoreImpl> storesForDDL = getDestinationHDFSStoreForDDL((DDLConflatable)qVal, lcc);
        if (storesForDDL == null)
          continue;
        // add the DDL to the HDFS stores where it belongs
        for (HDFSStoreImpl store : storesForDDL) {
          if (storeToDDLs.containsKey(store.getName())){
            storeToDDLs.get(store.getName()).add((DDLConflatable)qVal);
          }
        }
      }
    }
    return storeToDDLs;
  }

  /**
   * Creates a queue wrapper over the DDL region. Peeks and removes the ddl 
   * statements from it and returns them. This does not take a lock on the ddl 
   * queue and the caller of this function should take a lock before calling 
   * this function. 
   *     
   */
  private static List<GfxdDDLQueueEntry> getAllDDLs() throws StandardException {
    List<GfxdDDLQueueEntry> ddlIter = null;
    final GemFireStore memStore = Misc.getMemStore();
    final GfxdDataDictionary dd = memStore.getDatabase().getDataDictionary();

    if (dd == null) {
      throw StandardException.newException(SQLState.SHUTDOWN_DATABASE,
          Attribute.GFXD_DBNAME);
    }
    // create a wrapper GfxdDDLRegionQueue to get the DDLs in proper order
    final GfxdDDLRegionQueue ddlQ = new GfxdDDLRegionQueue(memStore
        .getDDLStmtQueue().getRegion());
  
    ddlQ.initializeQueue(dd, false);
    
    try {
      ddlIter = ddlQ.peekAndRemoveFromQueue(-1, -1);
    } catch (CacheException e) {
      throw Misc.processGemFireException(e, e, "reading of ddl queue", true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // check for JVM going down
      Misc.checkIfCacheClosing(e);
    }
  

    return ddlIter;
  }

  /**
   * Find out all the HDFS stores where a ddl belongs
   */
  public static ArrayList<HDFSStoreImpl> getDestinationHDFSStoreForDDL(
      DDLConflatable ddl, LanguageConnectionContext lcc) {

    ArrayList<HDFSStoreImpl> hdfsStores = Misc.getGemFireCache().getAllHDFSStores();
    
    ArrayList<HDFSStoreImpl> destinationhdfsStores = null;
    if (hdfsStores.size() <= 0 ||  !ddl.isHDFSPersistent()) {
      // NO HDFS stores yet. No need to persist DDL commands. 
      return destinationhdfsStores;
    }
    
    // if ddl has associated hdfs store, return it .
    if (ddl.getHDFSStoreName() != null){
      // This is a CREATE HDFSSTORE DDL or alter hdfsstore ddl
      HDFSStoreImpl hdfsStore = Misc.getGemFireCache().findHDFSStore(ddl.getHDFSStoreName());
      if (hdfsStore != null) {
        destinationhdfsStores = new ArrayList<HDFSStoreImpl>();
        destinationhdfsStores.add(hdfsStore);
      }
    } 
    // if the ddl has an schema and table name, get associated hdfs stores 
    else if (ddl.isAlterTable() || ddl.isCreateTable()) {
      String regionPath = Misc.getRegionPath( ddl.getSchemaForTable(),  ddl.getTableName(), lcc);
      destinationhdfsStores = getHDFSStore(regionPath);
    }
    else {
      // return all hdfs stores. 
      destinationhdfsStores = hdfsStores;
    }
      
    return destinationhdfsStores;
  }

  /**
   * Given a region path, get the associated hdfs store. 
   */
  private static ArrayList<HDFSStoreImpl> getHDFSStore(String regionPath) {
    
    ArrayList<HDFSStoreImpl> destinationhdfsStores = new ArrayList<HDFSStoreImpl>();
    
    LocalRegion region = Misc.getGemFireCache().getRegionByPath(regionPath, false);
    
    if (region == null) {// no region associated 
      return destinationhdfsStores;
    }
    
    if (region.getHDFSStoreName() == null)
      return destinationhdfsStores;
    HDFSStoreImpl hdfsStore = Misc.getGemFireCache().findHDFSStore(region.getHDFSStoreName());
    if (hdfsStore != null)
      destinationhdfsStores.add(hdfsStore);
    return destinationhdfsStores;
  }

  @SuppressWarnings("serial")
  protected static class FinalizeStatement extends FinalizeObject {

    private Activation singleUseActivation;
    private ArrayList<Activation> singleUseActivations;

    public FinalizeStatement(final EmbedStatement stmt) {
      super(stmt, true);
    }

    @Override
    public final FinalizeHolder getHolder() {
      return getServerHolder();
    }

    @Override
    protected void clearThis() {
      clearAllSingleUseActivations();
    }

    protected final void clearAllSingleUseActivations() {
      this.singleUseActivation = null;
      this.singleUseActivations = null;
    }

    public final void addSingleUseActivation(final Activation a) {
      final Activation act = this.singleUseActivation;
      if (act == null) {
        this.singleUseActivation = a;
      }
      else if (act != a) {
        ArrayList<Activation> acts = this.singleUseActivations;
        if (acts == null) {
          this.singleUseActivations = acts = new ArrayList<Activation>(4);
        }
        synchronized (acts) {
          acts.add(a);
        }
      }
    }

    public final void clearSingleUseActivation(final Activation a) {
      final ArrayList<Activation> acts;
      if (this.singleUseActivation == a) {
        this.singleUseActivation = null;
      }
      else if ((acts = this.singleUseActivations) != null) {
        synchronized (acts) {
          if (acts.remove(a) && acts.size() == 0) {
            this.singleUseActivations = null;
          }
        }
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean doFinalize() {
      final Activation act;
      final ArrayList<Activation> acts;

      // We mark the activation as not being used and
      // that is it.  We rely on the connection to sweep
      // through the activations to find the ones that
      // aren't in use, and to close them.  We cannot
      // do a activation.close() here because there are
      // synchronized methods under close that cannot
      // be called during finalization.
      if ((act = this.singleUseActivation) != null) {
        act.markUnused();
        this.singleUseActivation = null;
      }
      if ((acts = this.singleUseActivations) != null) {
        synchronized (acts) {
          for (final Activation a : acts) {
            a.markUnused();
          }
          this.singleUseActivations = null;
        }
      }
      return true;
    }
  }
// GemStone changes END
}

