/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation

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

package com.pivotal.gemfirexd.internal.impl.sql.execute;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Vector;


// GemStone changes BEGIN
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventCallbackArgument;
import com.gemstone.gemfire.internal.shared.ClientSharedData;
// GemStone changes END
import com.pivotal.gemfirexd.callbacks.AsyncEventHelper;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.BulkDBSynchronizerMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.CacheLoadedDBSynchronizerMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.GfxdCBArgForSynchPrms;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.procedure.cohort.ProcedureSender;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.sql.execute.ActivationStatisticsVisitor;
import com.pivotal.gemfirexd.internal.engine.sql.execute.ConstantValueSet;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireInsertResultSet;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GfxdSubqueryResultSet;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.SQLWarningFactory;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.ConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedByteCode;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedClass;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedMethod;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Optimizer;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.CursorActivation;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.CursorResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ResultSetFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TemporaryRowHolder;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.store.access.Qualifier;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.types.NumberDataValue;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.util.ReuseFactory;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;

/**
 * BaseActivation
 * provides the fundamental support we expect all activations to have.
 * Doesn't actually implement any of the activation interface,
 * expects the subclasses to do that.
 */
public abstract class BaseActivation implements CursorActivation, GeneratedByteCode

{
	protected LanguageConnectionContext	lcc;
	protected ContextManager			cm;

	protected ExecPreparedStatement preStmt;
	protected ResultSet resultSet;
	protected ResultDescription resultDescription;
	protected boolean closed;
	private String cursorName;
	
	protected int numSubqueries;

	private boolean singleExecution;

	// This flag is declared volatile to ensure it is 
	// visible when it has been modified by the finalizer thread.
	private volatile boolean inUse;

	private java.sql.ResultSet targetVTI;
	//GemStone changes BEGIN
	//private SQLWarning warnings;
	private SQLWarning resultsetWarnings, warnings;
	
	//GemStone changes END
	private GeneratedClass gc;	// my Generated class object.

	private boolean checkRowCounts;
	private final HashSet rowCountsCheckedThisExecution = new HashSet(4, 0.9f);

	private static final long MAX_SQRT = (long) Math.sqrt(Long.MAX_VALUE);

	// When the row count exceeds this number, we should recompile if
	// the difference in row counts is greater than 10%.  If it's less
	// than this number, we use an entirely different technique to check
	// for recompilation.  See comments below, in informOfRowCount()
	private static final int TEN_PERCENT_THRESHOLD = 400;

	/* Performance optimization for update/delete - only
	 * open heap ConglomerateController once when doing
	 * index row to base row on search
	 */
	private ConglomerateController  updateHeapCC;
	private ScanController			indexSC;
	private long					indexConglomerateNumber = -1;

	private TableDescriptor ddlTableDescriptor;

	private int maxRows = -1;
	private boolean			forCreateTable;

	private boolean			scrollable;

  	private boolean resultSetHoldability;

	//beetle 3865: updateable cursor using index.  A way of communication
	//between cursor activation and update activation.
	private CursorResultSet forUpdateIndexScan;

	//Following three are used for JDBC3.0 auto-generated keys feature.
	//autoGeneratedKeysResultSetMode will be set true if at the time of statement execution,
	//either Statement.RETURN_GENERATED_KEYS was passed or an array of (column positions or
	//column names) was passed
	private boolean autoGeneratedKeysResultSetMode;
	private int[] autoGeneratedKeysColumnIndexes ;
	private String[] autoGeneratedKeysColumnNames ;

	// Authorization stack frame, cf. SQL 2003 4.31.1.1 and 4.27.3 is
	// implemented as follows: Statements at root connection level
	// (not executed within a stored procedure), maintain the current
	// role in the lcc.  In this case, 'callActivation' is null.  If
	// we are executing SQL inside a stored procedure (nested
	// connection), then 'callActivation' will be non-null, and we
	// maintain the current role in the activation of the calling
	// statement, see 'setNestedCurrentRole'. The current role of a call
	// context is kept in the field 'nestedCurrentRole'.
	//
	// 'callActivation' is set when activation is created (see
	// GenericPreparedStatement#getActivation based on the top of the
	// dynamic call stack of activation, see
	// GenericLanguageConnectionContext#getCaller.
	//
	// Corner case: When a dynamic result set references current role,
	// the value retrieved will always be that of the current role
	// when the statement is executed (inside), not the current value
	// when the result set is accessed outside the stored procedure.
	//
	// Consequence of this implementation: If more than one nested
	// connection is used inside a shared procedure, they will share
	// the current role setting. Since the same dynamic call context
	// is involved, this seems correct.
	//
	private Activation callActivation;
	private String nestedCurrentRole;

	//Following is the position of the session table names list in savedObjects in compiler context
	//This is updated to be the correct value at cursor generate time if the cursor references any session table names.
	//If the cursor does not reference any session table names, this will stay negative
	protected int indexOfSessionTableNamesInSavedObjects = -1;
	
        //GemStone changes BEGIN
        /**
         * <code>queryCancellationFlag</code> is used to cancel a query on timeout or
         * low memory or in case of user initiated cancel. In case of query timeout,
         * Derby starts counting on each invocation of any method on
         * connection/resultset. in other words, .execute() timeout applies afresh
         * when .next gets called.
         */
        private volatile short queryCancellationFlag = 0x0;
        
        public final static short CANCELLED_LOW_MEMORY = 0x1;
        public final static short CANCELLED_TIMED_OUT = 0x2;
        public final static short CANCELLED_USER_REQUESTED = 0x3;
        
        long timeoutMillis = 0L;
        
        protected long connectionID;

        protected long statementID;
        
        protected boolean isPrepStmntQuery;
        
        protected int executionID;
        
        protected long rootID;
        
        protected int statementLevel;
        
        private boolean isPutDML = false;
        //GemStone changes END

	// WARNING: these fields are accessed by code generated in the 
	// ExpressionClassBuilder: don't change them unless you 
	// make the appropriate changes there.
	protected ExecRow[] row;
	protected ParameterValueSet pvs;

// GemStone changes BEGIN
  public boolean addToLCC;

  private DataValueDescriptor expressionDVD = null;

  private DataValueDescriptor[] expressionDVDs = null;

  private String objectName = null;  
  private Object[] savedObjects = null;

  /**
   * WARNING: this is used by generate method of ResultColumn, so if changing
   * the name of this field then need to change it there too.
   */
  protected String currentColumnName;

  /**
   * This will be non-null is case of statement query execution. For prepstatement & others,
   * this is expected to be null, but then pvs can never be instanceof ConstantValueSet in 
   * those cases.
   */
  private String statementText = null;

  public String getObjectName() {
    return this.objectName;
  }

  public void setObjectName(String name) {
    this.objectName = name;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addNullEliminatedWarning() {
    if ((this.flags & HAS_NULL_ELIMINATED_WARNING) == 0) {
      addResultsetWarning(SQLWarningFactory
          .newSQLWarning(SQLState.LANG_NULL_ELIMINATED_IN_SET_FUNCTION));
      this.flags |= HAS_NULL_ELIMINATED_WARNING;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getCurrentColumnName() {
    return this.currentColumnName;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setCurrentColumnName(String name) {
    this.currentColumnName = name;
  }

  public String getStatementText() {
    return statementText;
  }

  public void setExpressionDVDs(DataValueDescriptor dvd,
      DataValueDescriptor[] dvds) {
    this.expressionDVD = dvd;
    this.expressionDVDs = dvds;
  }

  protected BaseActivation(LanguageConnectionContext lcc) {
    super();
    this.lcc = lcc;
  }

// GemStone changes END
	//
	// constructors
	//

	protected BaseActivation()
	{
		super();
	}

// GemStone changes BEGIN
	public final void initFromContext(final LanguageConnectionContext context,
	    final boolean addToLCC, final ExecPreparedStatement eps)
	          throws StandardException {

		if (SanityManager.DEBUG)
		{
		  if (context == null) {
		    SanityManager.THROWASSERT("NULL context passed to BaseActivation.initFromContext");
		    // never reached
		    return;
		  }
		}
		this.cm = context.getContextManager();

		this.lcc = context;
		/*
		lcc = (LanguageConnectionContext) cm.getContext(LanguageConnectionContext.CONTEXT_ID);
		*/

		if (SanityManager.DEBUG) {
			if (lcc == null)
				SanityManager.THROWASSERT("lcc is null in activation type " + getClass());
		}

		// mark in use
		inUse = true;
		this.preStmt = eps;
		//To Fix Bug 49118. Store the saved objects from GPS to activation instance
		//Since in reprepare the GPS will get a new saved objects array, the 
		// resultset object created from old activation may not get right mapping
		if(eps instanceof GenericPreparedStatement) {
		  this.savedObjects = ((GenericPreparedStatement)eps).getSavedObjects();
		}

		// add this activation to the pool for the connection.
		if ((this.addToLCC = addToLCC)) {
		  lcc.addActivation(this);
		}
	}
// GemStone changes END

	//
	// Activation interface
	//

	public final ExecPreparedStatement getPreparedStatement() {
		return preStmt;
	}

	public ConstantAction getConstantAction() {
		return preStmt.getConstantAction();
	}

	public  void checkStatementValidity() throws StandardException {

		if (preStmt == null)
			return;

// GemStone changes BEGIN
		if (preStmt.upToDate() && gc == preStmt.getActivationClass()) {
		  return;
		}
		/* (original code)
		synchronized (preStmt) {

			if ((gc == preStmt.getActivationClass()) && preStmt.upToDate())
				return;
		}
		*/
// GemStone changes END

		StandardException se = StandardException.newException(SQLState.LANG_STATEMENT_NEEDS_RECOMPILE);
		se.setReport(StandardException.REPORT_NEVER);
		throw se;
	}

	/**
		Link this activation with its PreparedStatement.
		It can be called with null to break the link with the
		PreparedStatement.
	 * @param statementText original user submitted query string in case statement.

	*/
	public void setupActivation(ExecPreparedStatement ps, boolean scrollable, String stmt_text) 
	throws StandardException {
		preStmt = ps;
		statementText = stmt_text;
		
		if (ps != null) {
			// get the result set description
   			resultDescription = ps.getResultDescription();
			this.scrollable = scrollable;
			
			// Initialize the parameter set to have allocated
			// DataValueDescriptor objects for each parameter.
			if (pvs != null && pvs.getParameterCount() != 0) {
				pvs.initialize(ps.getParameterTypes());
// GemStone changes BEGIN
                        }
                        else {
                          // [sb] lets stuff in the constant parameters as well. All copies of the
                          // Activation can share the same underlying constant values.
                          setConstantParameters(lcc.getConstantValueSet(this));
                        }
			setInsertAsSubselect(ps.isInsertAsSubselect());
			setPutDML(ps.isPutDML());
// GemStone changes END
	                                
			

		} else {
			resultDescription = null;
			this.scrollable = false;
		}
	}

	public ResultSet getResultSet() {
		return resultSet;
	}

	/**
		Get the saved RowLocation.

		@param itemNumber	The saved item number.

		@return	A RowLocation template for the conglomerate
	 */
	public RowLocation getRowLocationTemplate(int itemNumber)
	{
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(itemNumber >= 0,
				"itemNumber expected to be >= 0");
			if (! (getSavedObject(itemNumber) instanceof RowLocation))
			{
				SanityManager.THROWASSERT(
					"getPreparedStatement().getSavedObject(itemNumber) expected to be " +
					"instance of RowLocation, not " +
					getSavedObject(itemNumber).getClass().getName() +
					", query is " + getPreparedStatement().getUserQueryString(getLanguageConnectionContext()));
			}
			RowLocation rl = (RowLocation) getSavedObject(itemNumber);
			if (! (rl.cloneObject() instanceof RowLocation))
			{
				SanityManager.THROWASSERT(
					"rl.cloneObject() expected to be " +
					"instance of RowLocation, not " +
					rl.getClass().getName() +
					", query is " + getPreparedStatement().getSource());
			}
		}
		/* We have to return a clone of the saved RowLocation due
		 * to the shared cache of SPSs.
		 */
		return (RowLocation)
			((RowLocation)(getSavedObject(itemNumber))).cloneObject();
	}

	/*
	 */
	public ResultDescription getResultDescription() {
		if (SanityManager.DEBUG)
	    	SanityManager.ASSERT(resultDescription != null, "Must have a result description");
	   	    return resultDescription;
	}

	/**
		This is a partial implementation of reset.
		Subclasses will want to reset information
		they are aware of, such as parameters.
		<p>
		All subclasses must call super.reset() and
		then do their cleanup.
		<p>
		The execute call must set the resultSet field
		to be the resultSet that it has returned.

		@exception StandardException on error
	 */
	public void reset(final boolean cleanupOnError) throws StandardException
	{
// GemStone changes BEGIN
		final ResultSet rs = this.resultSet;
		if (rs != null) {
		  // null the resultSet to avoid recursive call back into
		  // BaseActivation.close
		  this.resultSet = null;
		  rs.close(cleanupOnError);
		  // restore resultSet as in original code
		  this.resultSet = rs;
		}
		/* (original code)
		if (resultSet != null) 
			resultSet.close();
		*/
// GemStone changes END
		
		updateHeapCC = null;
		// REMIND: do we need to get them to stop input as well?

		//GemStone changes BEGIN
		queryCancellationFlag = 0;
		timeoutMillis = 0L;
		//GemStone changes END
		if (!isSingleExecution())
			clearWarnings();
	}

	/**
		Closing an activation marks it as unusable. Any other
		requests made on it will fail.  An activation should be
		marked closed when it is expected to not be used any longer,
		i.e. when the connection for it is closed, or it has suffered some
		sort of severe error.

		This should also remove it from the language connection context.

		@exception StandardException on error
	 */
	public  void close() throws StandardException 
	{
	  if (! closed) {	
			// markUnused();

// GemStone changes BEGIN
			final ResultSet rs = this.resultSet;
// GemStone changes END
			// we call reset so that if the actual type of "this"
			// is a subclass of BaseActivation, its cleanup will
			// also happen -- reset in the actual type is called,
			// not reset in BaseActivation.  Subclass reset's
			// are supposed to call super.reset() as well.
			reset(false); // get everything related to executing released

// GemStone changes BEGIN
			if (rs != null) {
			  // null the resultSet first to avoid recursive call
			  // back into BaseActivation.close
			  this.resultSet = null;
			  // Finish the resultSet, it will never be used again.
			  rs.finish();
			/* (original code)
			if (resultSet != null)
			{
				// Finish the resultSet, it will never be used again.
				resultSet.finish();
				resultSet = null;
			*/
// GemStone changes END
			}
			// GemStone changes BEGIN
			// there is now a circular reference in GfxdSubqueryResultSet
			// resulting a memory leak of SubqueryParameterValueSetWrapper
			// objects.
                        if (pvs != null && pvs.canReleaseOnClose()) {
                          if (SanityManager.DEBUG) {
                            if (GemFireXDUtils.TraceStatementMatching) {
                              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_STATEMENT_MATCHING,
                                  "nullifying " + GemFireXDUtils.addressOf(pvs) + " of "
                                      + GemFireXDUtils.addressOf(this));
                            }
                          }
                          pvs = null;
                        }
                        // GemStone changes END

			closed = true;

			LanguageConnectionContext lcc = getLanguageConnectionContext();

// GemStone changes BEGIN
		      if (this.addToLCC) {
// GemStone changes END
			lcc.removeActivation(this);
			if (preStmt != null) {
				preStmt.finish(lcc);
				preStmt = null;
			}
// GemStone changes BEGIN
		      }
		      else if (preStmt != null) {
		        preStmt = null;
		      }
		      this.fnContext = null;
		      this.sender = null;
// GemStone changes END

			try {
				closeActivationAction();
			} catch (Throwable e) {
				throw StandardException.plainWrapException(e);
			}

		}
		
	}

	/**
		A generated class can create its own closeActivationAction
		method to invoke special logic when the activation is closed.
	*/
	protected void closeActivationAction() throws Exception {
		// no code to be added here as generated code
		// will not call super.closeActivationAction()
	}

	/**
		Find out if the activation closed or not.
		@return true if the prepared statement has been closed.
	 */
	public boolean isClosed() {
		return closed;
	}

	/**
		Set this Activation for a single execution.

		@see Activation#setSingleExecution
	*/
	public void setSingleExecution() {
		singleExecution = true;
	}
        
// GemStone changes BEGIN
        public long getConnectionID() {
          return connectionID;
        }
        
        public long getStatementID() {
          return this.statementID;
        }
        
        public long getRootID() {
          return this.rootID;
        }
        
        public int getStatementLevel() {
          return this.statementLevel;
        }
        
        public boolean getIsPrepStmntQuery() {
          return this.isPrepStmntQuery;
        }
        
	public void setConnectionID(long id) {
	    this.connectionID = id;
	}

	public void setStatementID(long id) {
	    this.statementID = id;
	}
	
        public void setRootID(long id) {
          this.rootID = id;
        }
        
        public void setStatementLevel(int val) {
          this.statementLevel = val;
        }

        public void setExecutionID(int id) {
          this.executionID = id;
        }
	
        public int getExecutionID() {
          return executionID;
        }
	
        public void setIsPrepStmntQuery(boolean flag) {
          /**
           * @see defect #48646 'GetAll on Index' queries that can switch from
           *      BaseActivation (GemfireSelectActivation) to
           *      AbstractGemFireDistributionActivation or vice-versa can change
           *      activation and its type itself. Thus this flag needed.
           */
          this.isPrepStmntQuery = flag;
        }
        
        public void distributeClose() {
          //NO Op
        }

// GemStone changes END

	/**
		Returns true if this Activation is only going to be used for
		one execution.

		@see Activation#isSingleExecution
	*/
	public boolean isSingleExecution() {
		return singleExecution;
	}

	/**
		Get the number of subqueries in the entire query.
		@return int	 The number of subqueries in the entire query.
	 */
	public int getNumSubqueries() {
		return numSubqueries;
	}

	/**
	 * @see Activation#isCursorActivation
	 */
	public boolean isCursorActivation()
	{
		return false;
	}

	//
	// GeneratedByteCode interface
	//

	public final void setGC(GeneratedClass gc) {
		this.gc = gc;
	}

	public final GeneratedClass getGC() {

		if (SanityManager.DEBUG) {
			if (gc == null)
				SanityManager.THROWASSERT("move code requiring GC to postConstructor() method!!");
		}
		return gc;
	}

	public final GeneratedMethod getMethod(String methodName) throws StandardException {

		return getGC().getMethod(methodName);
	}
	public Object e0() throws StandardException { return null; } 
	public Object e1() throws StandardException { return null; }
	public Object e2() throws StandardException { return null; }
	public Object e3() throws StandardException { return null; }
	public Object e4() throws StandardException { return null; } 
	public Object e5() throws StandardException { return null; }
	public Object e6() throws StandardException { return null; }
	public Object e7() throws StandardException { return null; }
	public Object e8() throws StandardException { return null; } 
	public Object e9() throws StandardException { return null; }

	//
	// class interface
	//

	/**
	 * Temporary tables can be declared with ON COMMIT DELETE ROWS. But if the table has a held curosr open at
	 * commit time, data should not be deleted from the table. This method, (gets called at commit time) checks if this
	 * activation held cursor and if so, does that cursor reference the passed temp table name.
	 *
	 * @return	true if this activation has held cursor and if it references the passed temp table name
	 */
	public boolean checkIfThisActivationHasHoldCursor(String tableName)
	{
		if (!inUse)
			return false;

		if (resultSetHoldability == false) //if this activation is not held over commit, do not need to worry about it
			return false;

		if (indexOfSessionTableNamesInSavedObjects == -1) //if this activation does not refer to session schema tables, do not need to worry about it
			return false;

		/* is there an open result set? */
		if ((resultSet != null) && !resultSet.isClosed() && resultSet.returnsRows())
		{
			//If we came here, it means this activation is held over commit and it reference session table names
			//Now let's check if it referneces the passed temporary table name which has ON COMMIT DELETE ROWS defined on it.
			return ((ArrayList)getSavedObject(indexOfSessionTableNamesInSavedObjects)).contains(tableName);
		}

		return false;
	}

	/**
	   remember the cursor name
	 */

	public void	setCursorName(String cursorName)
	{
		if (isCursorActivation())
			this.cursorName = cursorName;
	}


	/**
	  get the cursor name.  For something that isn't
	  a cursor, this is used as a string name of the
	  result set for messages from things like the
	  dependency manager.
	  <p>
	  Activations that do support cursors will override
	  this.	
	*/
	public String getCursorName() {

		return isCursorActivation() ? cursorName : null;
	}

	public void setResultSetHoldability(boolean resultSetHoldability)
	{
		this.resultSetHoldability = resultSetHoldability;
	}

	public boolean getResultSetHoldability()
	{
		return resultSetHoldability;
	}

	/** @see Activation#setAutoGeneratedKeysResultsetInfo */
	public void setAutoGeneratedKeysResultsetInfo(int[] columnIndexes, String[] columnNames)
	{
		autoGeneratedKeysResultSetMode = true;
		autoGeneratedKeysColumnIndexes = columnIndexes;
		autoGeneratedKeysColumnNames = columnNames;
	}

	/** @see Activation#getAutoGeneratedKeysResultsetMode */
	public boolean getAutoGeneratedKeysResultsetMode()
	{
		return autoGeneratedKeysResultSetMode;
	}

	/** @see Activation#getAutoGeneratedKeysColumnIndexes */
	public int[] getAutoGeneratedKeysColumnIndexes()
	{
		return autoGeneratedKeysColumnIndexes;
	}

	/** @see Activation#getAutoGeneratedKeysColumnNames */
	public String[] getAutoGeneratedKeysColumnNames()
	{
		return autoGeneratedKeysColumnNames;
	}

	//
	// class implementation
	//


	/**
		Used in the execute method of activations for
		generating the result sets that they concatenate together.
	 */
	public final ResultSetFactory getResultSetFactory() {
		return getExecutionFactory().getResultSetFactory();
	}

	/**
		Used in activations for generating rows.
	 */
	public final ExecutionFactory getExecutionFactory() {
		return getLanguageConnectionContext().
            getLanguageConnectionFactory().getExecutionFactory();
	}


	/**
		Used in CurrentOfResultSet to get to the target result set
		for a cursor. Overridden by activations generated for
		updatable cursors.  Those activations capture the target
		result set in a field in their execute() method, and then
		return the value of that field in their version of this method.

		@return null.
	 */
	public CursorResultSet getTargetResultSet() {
		if (SanityManager.DEBUG)
			SanityManager.THROWASSERT("Must be overridden to be used.");
		return null;
	}

	/**
	 * Called by generated code to compute the next autoincrement value.
	 * 
	 * @return The next autoincrement value which should be inserted.
	 * returns the correct number datatype.
	 */
	protected DataValueDescriptor 
		getSetAutoincrementValue(int columnPosition, long increment, boolean isGeneratedByDefault)
	       throws StandardException
	{
// GemStone changes BEGIN
	  DataValueDescriptor l = null;
	  if(isGeneratedByDefault){
            l = ((GemFireInsertResultSet)resultSet).getMaxIdentityValue(columnPosition);
	  }else {
            l = ((GemFireInsertResultSet)resultSet).getNextUUIDValue(columnPosition);
	  }
	  /* (original code)*/
//		DataValueDescriptor l =
//			((InsertResultSet)resultSet).getSetAutoincrementValue(columnPosition, increment);
// GemStone changes END
		return l;

	}

	/**
		Used in CurrentOfResultSet to get to the cursor result set
		for a cursor.  Overridden by activations generated for
		updatable cursors.  Those activations capture the cursor
		result set in a field in their execute() method, and then
		return the value of that field in their version of this method.

		@return null
	 */
	public CursorResultSet getCursorResultSet() {
		if (SanityManager.DEBUG)
			SanityManager.THROWASSERT("Must be overridden to be used.");
		return null;
	}

	/**
		Various activation methods need to disallow their
		invocation if the activation is closed. This lets them
		check and throw without generating alot of code.
		<p>
		The code to write to generate the call to this is approximately:
		<verbatim>
			// jf is a JavaFactory
			CallableExpression ce = jf.newMethodCall(
				jf.thisExpression(),
				BaseActivation.CLASS_NAME,
				"throwIfClosed",
				"void",
				acb.exprArray(jf.newStringLiteral(...some literal here...)));

			//mb is a MethodBuilder
			mb.addStatement(jf.newStatement(ce));
		</verbatim>
		The java code to write to call this is:
		<verbatim>
			this.throwIfClosed(...some literal here...);
		</verbatim>
		In both cases, "...some literal here..." gets replaced with
		an expression of type String that evaluates to the name
		of the operation that is being checked, like "execute" or
		"reset".

		@exception StandardException thrown if closed
	 */
	public void throwIfClosed(String op) throws StandardException {
		if (closed)
			throw StandardException.newException(SQLState.LANG_ACTIVATION_CLOSED, op);
	}

	/**
	 * Set a column position in an array of column positions.
	 *
	 * @param columnPositions	The array of column positions
	 * @param positionToSet		The place to put the column position
	 * @param column			The column position
	 */
	public static void setColumnPosition(
							int[] columnPositions,
							int positionToSet,
							int column)
	{
		columnPositions[positionToSet] = column;
	}

	/**
	 * Allocate an array of qualifiers and initialize in Qualifier[][]
	 *
	 * @param qualifiers	The array of Qualifier arrays.
	 * @param position		The position in the array to set
	 * @param length		The array length of the qualifier array to allocate.
	 */
	public static void allocateQualArray(
    Qualifier[][]   qualifiers,
    int             position,
    int             length)
	{
        qualifiers[position] = new Qualifier[length];
	}


	/**
	 * Set a Qualifier in a 2 dimensional array of Qualifiers.
     *
     * Set a single Qualifier into one slot of a 2 dimensional array of 
     * Qualifiers.  @see Qualifier for detailed description of layout of
     * the 2-d array.
	 *
	 * @param qualifiers	The array of Qualifiers
	 * @param qualifier		The Qualifier
	 * @param position_1    The Nth array index into qualifiers[N][M]
	 * @param position_2    The Nth array index into qualifiers[N][M]
	 */
	public static void setQualifier(
    Qualifier[][]   qualifiers,
    Qualifier	    qualifier,
    int			    position_1,
    int             position_2)
	{
		qualifiers[position_1][position_2] = qualifier;
	}

	/**
	 * Reinitialize all Qualifiers in an array of Qualifiers.
	 *
	 * @param qualifiers	The array of Qualifiers
	 */
	public static void reinitializeQualifiers(Qualifier[][] qualifiers)
	{
		if (qualifiers != null)
		{
            for (int term = 0; term < qualifiers.length; term++)
            {
                for (int i = 0; i < qualifiers[term].length; i++)
                {
                    qualifiers[term][i].reinitialize();
                }
            }
		}
	}

	/**
	 * Mark the activation as unused.  
	 */
	public final void markUnused()
	{
		if(isInUse()) {
// GemStone changes BEGIN
		  // lose the function context to help GC
		  this.fnContext = null;
		  this.sender = null;
// GemStone changes END
			inUse = false;
			lcc.notifyUnusedActivation();
		}
	}

	/**
	 * Is the activation in use?
	 *
	 * @return true/false
	 */
	public final boolean isInUse()
	{
		return inUse;
	}

	/**
	  @see com.pivotal.gemfirexd.internal.iapi.sql.Activation#addWarning
	  */
	public void addResultsetWarning(SQLWarning w)
	{
             if (this.resultsetWarnings == null)
                 this.resultsetWarnings = w;
             else
                 this.resultsetWarnings.setNextWarning(w);
   
	}
      
        public void addWarning(SQLWarning w) {
          if (this.warnings == null)
            this.warnings = w;
          else
            this.warnings.setNextWarning(w);
      
        }

	/**
	  @see com.pivotal.gemfirexd.internal.iapi.sql.Activation#getWarnings
	  */
	public SQLWarning getResultsetWarnings()
	{
	    return this.resultsetWarnings;
	}
	
	public SQLWarning getWarnings()
        {               
                return this.warnings;
        }

	/**
	  @see com.pivotal.gemfirexd.internal.iapi.sql.Activation#clearWarnings
	  */
	public void clearWarnings()
	{
		warnings = null;
	}

	//GemStone changes BEGIN
	public void clearResultsetWarnings() {
	  this.resultsetWarnings = null;
	}
	
	
	//GemStone changes END
	
	/**
	 * @exception StandardException on error
	 */
	protected static void nullToPrimitiveTest(DataValueDescriptor dvd, String primitiveType)
		throws StandardException
	{
		if (dvd.isNull())
		{
			throw StandardException.newException(SQLState.LANG_NULL_TO_PRIMITIVE_PARAMETER, primitiveType);
		}
	}

	/**
		@see Activation#informOfRowCount
		@exception StandardException	Thrown on error
	 */
	public void informOfRowCount(NoPutResultSet resultSet, long currentRowCount)
					throws StandardException
	{

		/* Do we want to check the row counts during this execution? */
		if (checkRowCounts)
		{
			boolean significantChange = false;

			int resultSetNumber = resultSet.resultSetNumber();
			Integer rsn = ReuseFactory.getInteger(resultSetNumber);

			/* Check each result set only once per execution */
			if (rowCountsCheckedThisExecution.add(rsn))
			{
				synchronized (getPreparedStatement())
				{
					Vector rowCountCheckVector = getRowCountCheckVector();

					if (rowCountCheckVector == null) {
						rowCountCheckVector = new Vector();
						setRowCountCheckVector(rowCountCheckVector);
					}

					Long firstRowCount = null;

					/*
					** Check whether this resultSet has been seen yet.
					*/
					if (resultSetNumber < rowCountCheckVector.size())
					{
						firstRowCount =
							(Long) rowCountCheckVector.elementAt(resultSetNumber);
					}
					else
					{
						rowCountCheckVector.setSize(resultSetNumber + 1);
					}

					if (firstRowCount != null)
					{
						/*
						** This ResultSet has been seen - has the row count
						** changed significantly?
						*/
						long n1 = firstRowCount.longValue();

						if (currentRowCount != n1)
						{
							if (n1 >= TEN_PERCENT_THRESHOLD)
							{
								/*
								** For tables with more than
								** TEN_PERCENT_THRESHOLD rows, the
								** threshold is 10% of the size of the table.
								*/
								long changeFactor = n1 / (currentRowCount - n1);
								if (Math.abs(changeFactor) <= 10)
									significantChange = true;
							}
							else
							{
								/*
								** For tables with less than
								** TEN_PERCENT_THRESHOLD rows, the threshold
								** is non-linear.  This is because we want
								** recompilation to happen sooner for small
								** tables that change size.  This formula
								** is for a second-order equation (a parabola).
								** The derivation is:
								**
								**   c * n1 = (difference in row counts) ** 2
								**				- or - 
								**   c * n1 = (currentRowCount - n1) ** 2
								**
								** Solving this for currentRowCount, we get:
								**
								**   currentRowCount = n1 + sqrt(c * n1)
								**
								**				- or -
								**
								**   difference in row counts = sqrt(c * n1)
								**
								**				- or -
								**
								**   (difference in row counts) ** 2 =
								**					c * n1
								**
								** Which means that we should recompile when
								** the current row count exceeds n1 (the first
								** row count) by sqrt(c * n1), or when the
								** square of the difference exceeds c * n1.
								** A good value for c seems to be 4.
								**
								** We don't use this formula when c is greater
								** than TEN_PERCENT_THRESHOLD because we never
								** want to recompile unless the number of rows
								** changes by more than 10%, and this formula
								** is more sensitive than that for values of
								** n1 greater than TEN_PERCENT_THRESHOLD.
								*/
								long changediff = currentRowCount - n1;

								/*
								** Square changediff rather than take the square
								** root of (4 * n1), because multiplying is
								** faster than taking a square root.  Also,
								** check to be sure that squaring changediff
								** will not cause an overflow by comparing it
								** with the square root of the maximum value
								** for a long (this square root is taken only
								** once, when the class is loaded, or during
								** compilation if the compiler is smart enough).
								*/
								if (Math.abs(changediff) <= MAX_SQRT)
								{
									if ((changediff * changediff) >
															Math.abs(4 * n1))
									{
										significantChange = true;
									}
								}
							}
						}
					}
					else
					{
// GemStone changes BEGIN
						// changed to use valueOf()
						firstRowCount = Long.valueOf(currentRowCount);
						/* (original code)
						firstRowCount = new Long(currentRowCount);
						*/
// GemStone changes END
						rowCountCheckVector.setElementAt(
														firstRowCount,
														resultSetNumber
														);

					}
				}
			}

			/* Invalidate outside of the critical section */
			if (significantChange)
			{
				preStmt.makeInvalid(DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
			}
		}

	}

	/**
	 * The subclass calls this method when it begins an execution.
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void startExecution() throws StandardException
	{
		// determine if we should check row counts during this execution
		shouldWeCheckRowCounts();

		// If we are to check row counts, clear the hash table of row counts
		// we have checked.
		if (checkRowCounts)
			rowCountsCheckedThisExecution.clear();
	}

	/**
	 * @see Activation#getHeapConglomerateController
	 */
	public ConglomerateController getHeapConglomerateController()
	{
		return updateHeapCC;
	}


	/**
	 * @see Activation#setHeapConglomerateController
	 */
	public void setHeapConglomerateController(ConglomerateController updateHeapCC)
	{
		this.updateHeapCC = updateHeapCC;
	}

	/**
	 * @see Activation#clearHeapConglomerateController
	 */
	public void clearHeapConglomerateController()
	{
		updateHeapCC = null;
	}

	/**
	 * @see Activation#getIndexScanController
	 */
	public ScanController getIndexScanController()
	{
		return indexSC;
	}

	/**
	 * @see Activation#setIndexScanController
	 */
	public void setIndexScanController(ScanController indexSC)
	{
		this.indexSC = indexSC;
	}

	/**
	 * @see Activation#getIndexConglomerateNumber
	 */
	public long getIndexConglomerateNumber()
	{
		return indexConglomerateNumber;
	}

	/**
	 * @see Activation#setIndexConglomerateNumber
	 */
	public void setIndexConglomerateNumber(long indexConglomerateNumber)
	{
		this.indexConglomerateNumber = indexConglomerateNumber;
	}

	/**
	 * @see Activation#clearIndexScanInfo
	 */
	public void clearIndexScanInfo()
	{
		indexSC = null;
		indexConglomerateNumber = -1;
	}

	/**
	 * @see Activation#setForCreateTable()
	 */
	public void setForCreateTable()
	{
		forCreateTable = true;
	}

	/**
	 * @see Activation#getForCreateTable()
	 */
	public boolean getForCreateTable()
	{
		return forCreateTable;
	}

	/**
	 * @see Activation#setDDLTableDescriptor
	 */
	public void setDDLTableDescriptor(TableDescriptor td)
	{
		ddlTableDescriptor = td;
	}

	/**
	 * @see Activation#getDDLTableDescriptor
	 */
	public TableDescriptor getDDLTableDescriptor()
	{
		return ddlTableDescriptor;
	}

	/**
	 * @see Activation#setMaxRows
	 */
	public void setMaxRows(int maxRows)
	{
		this.maxRows = maxRows;
	}

	/**
	 * @see Activation#getMaxRows
	 */
	public int getMaxRows()
	{
		return maxRows;
	}

	public void setTargetVTI(java.sql.ResultSet targetVTI)
	{
		this.targetVTI = targetVTI;
	}

	public java.sql.ResultSet getTargetVTI()
	{
		return targetVTI;
	}

	private void shouldWeCheckRowCounts() throws StandardException
	{
		/*
		** Check the row count only every N executions.  OK to check this
		** without synchronization, since the value of this number is not
		** critical.  The value of N is determined by the property
		** gemfirexd.language.stalePlanCheckInterval.
		*/
		int executionCount = getExecutionCount() + 1;

		/*
		** Always check row counts the first time, to establish the
		** row counts for each result set.  After that, don't check
		** if the execution count is below the minimum row count check
		** interval.  This saves us from checking a database property
		** when we don't have to (checking involves querying the store,
		** which can be expensive).
		*/

		if (executionCount == 1)
		{
			checkRowCounts = true;
		}
		else if (executionCount <
								Property.MIN_LANGUAGE_STALE_PLAN_CHECK_INTERVAL)
		{
			checkRowCounts = false;
		}
		else
		{
			int stalePlanCheckInterval = getStalePlanCheckInterval();

			/*
			** Only query the database property once.  We can tell because
			** the minimum value of the property is greater than zero.
			*/
			if (stalePlanCheckInterval == 0)
			{
				TransactionController tc = getTransactionController();

				stalePlanCheckInterval =
						PropertyUtil.getServiceInt(
							tc,
							Property.LANGUAGE_STALE_PLAN_CHECK_INTERVAL,
							Property.MIN_LANGUAGE_STALE_PLAN_CHECK_INTERVAL,
							Integer.MAX_VALUE,
							Property.DEFAULT_LANGUAGE_STALE_PLAN_CHECK_INTERVAL
							);
				setStalePlanCheckInterval(stalePlanCheckInterval);
			}

			checkRowCounts = (executionCount % stalePlanCheckInterval) == 1;


		}

		setExecutionCount(executionCount);
	}

	/*
	** These accessor methods are provided by the sub-class to help figure
	** out whether to check row counts during this execution.
	*/
	abstract protected int getExecutionCount();

	abstract protected void setExecutionCount(int newValue); 

	/*
	** These accessor methods are provided by the sub-class to help figure
	** out whether the row count for a particular result set has changed
	** enough to force recompilation.
	*/
	abstract protected Vector getRowCountCheckVector();

	abstract protected void setRowCountCheckVector(Vector newValue);

	/*
	** These accessor methods are provided by the sub-class to remember the
	** value of the stale plan check interval property, so that we only
	** have to query the database properties once (there is heavyweight
	** synchronization around the database properties).
	*/
	abstract protected int getStalePlanCheckInterval();

	abstract protected void setStalePlanCheckInterval(int newValue);

	public final boolean getScrollable() {
		return scrollable;
	}
	// GemStone changes BEGIN
	protected final void setParameterValueSet(int paramCount, boolean hasReturnParam) {
                if (this.preStmt == null || (!this.preStmt.isSubqueryPrepStatement()) ) {
		   pvs = lcc.getLanguageFactory().newParameterValueSet(
			lcc.getLanguageConnectionFactory().getClassFactory().getClassInspector(),
			paramCount, hasReturnParam);
		} else {
		  // Asif :In case of Subquery Prep Stmnt do not set the PVS. It will be set in GfxdSubqueryResultSet
		}
	}
	// GemStone changes END
	/**
	 * This method can help reduce the amount of generated code by changing
	 * instances of this.pvs.getParameter(position) to this.getParameter(position) 
	 * @param position
	 * @throws StandardException
	 */
	// GemStone changes BEGIN
	// increase visibility
	public final DataValueDescriptor getParameter(int position) throws StandardException { 
        // GemStone changes END
		return pvs.getParameter(position); 
		} 
	
	/**
	 return the parameters.
	 */
	public final ParameterValueSet	getParameterValueSet() 
	{ 
		if (pvs == null) 
			setParameterValueSet(0, false); 
		
		return pvs; 
	}

	// how do we do/do we want any sanity checking for
	// the number of parameters expected?
	public void	setParameters(ParameterValueSet parameterValues, DataTypeDescriptor[] parameterTypes) throws StandardException
	{
		if (!isClosed())
		{
			if (this.pvs == null || parameterTypes == null || pvs.isListOfConstants()) {
			  
				pvs = parameterValues;
				return;

			}

			DataTypeDescriptor[]	newParamTypes = preStmt.getParameterTypes();

			/*
			** If there are old parameters but not new ones,
			** they aren't compatible.
			*/
			boolean match = false;
			if (newParamTypes != null) {

				if (newParamTypes.length == parameterTypes.length) {

					/* Check each parameter */
					match = true;
					for (int i = 0; i < parameterTypes.length; i++)
					{
						DataTypeDescriptor	oldType = parameterTypes[i];
						DataTypeDescriptor	newType	= newParamTypes[i];

						if (!oldType.isExactTypeAndLengthMatch(newType)) {
							match = false;
							break;
						}
						/*
						** We could probably get away without checking nullability,
						** since parameters are always nullable.
						*/
						if (oldType.isNullable() != newType.isNullable()) {
							match = false;
							break;
						}
					}
				}

			}

			if (!match)
				throw StandardException.newException(SQLState.LANG_OBSOLETE_PARAMETERS);


			parameterValues.transferDataValues(pvs);

		}
		else if (SanityManager.DEBUG)
		{
			SanityManager.THROWASSERT("isClosed() is expected to return false");
		}
	}

	/**
	 	Throw an exception if any parameters are uninitialized.

		@exception StandardException	Thrown if any parameters
												are unitialized
	 */

	public void throwIfMissingParms() throws StandardException
	{
// GemStone changes BEGIN
	  final int notSetPos;
	  if (pvs != null && (notSetPos = pvs.allAreSet()) > 0) {
	    throw StandardException.newException(SQLState.LANG_MISSING_PARMS,
	        notSetPos, this.statementText != null ? this.statementText
	            : (preStmt != null ? preStmt.getSource() : "(null)"));
	    /* (original code)
		if (pvs != null && !pvs.allAreSet())
		{
			throw StandardException.newException(SQLState.LANG_MISSING_PARMS);
	    */
// GemStone changes END
		}
	}

	/**
	 * Remember the row for the specified ResultSet.
	 */
	public void setCurrentRow(ExecRow currentRow, int resultSetNumber)
	{ 
		if (SanityManager.DEBUG_ASSERT) 
		{
			SanityManager.ASSERT(!isClosed(), "closed");
			if (row != null)
			{
				if (!(resultSetNumber >=0 && resultSetNumber < row.length))
				{
					SanityManager.THROWASSERT("resultSetNumber = " + resultSetNumber +
								 ", expected to be between 0 and " + row.length);
				}
			}
		}
		if (row != null)
		{
			row[resultSetNumber] = currentRow;
		}
	}

	/**
	 * Clear the current row for the specified ResultSet.
	 */
	public void clearCurrentRow(int resultSetNumber)
	{
		if (SanityManager.DEBUG_ASSERT)
		{
			if (row != null)
			{
				if (!(resultSetNumber >=0 && resultSetNumber < row.length))
				{
					SanityManager.THROWASSERT("resultSetNumber = " + resultSetNumber +
								 ", expected to be between 0 and " + row.length);
				}
			}
		}
		if (row != null)
		{
			row[resultSetNumber] = null;
		}
	}

	/**
	 * Set the current role name of the dynamic call context stemming
	 * from this activation (which must be a stored
	 * procedure/function call).
	 *
	 * @param role The name of the current role
	 */
	public void setNestedCurrentRole(String role) {
		nestedCurrentRole = role;
	}

	/**
	 * Get the current role name of the dynamic call context stemming
	 * from this activation (which must be a stored
	 * procedure/function call).
	 *
	 * @return The name of the current role
	 */
	public String getNestedCurrentRole() {
		return nestedCurrentRole;
	}

	/**
	 * This activation is created in a dynamic call context, remember
	 * its caller's activation.
	 *
	 * @param a The caller's activation
	 */
	public void setCallActivation(Activation a) {
		callActivation = a;
	}

	/**
	 * This activation is created in a dynamic call context, get its
	 * caller's activation.
	 *
	 * @return The caller's activation
	 */
	public Activation getCallActivation() {
		return callActivation;
	}


	protected final DataValueDescriptor getColumnFromRow(int rsNumber, int colId)
		throws StandardException {

// GemStone changes BEGIN
	  final DataValueDescriptor dvd;
	  if (this.expressionDVD != null) {
	    dvd = this.expressionDVD;
	    if (colId != 1) {
	      GemFireXDUtils.throwAssert("unexpected colId=" + colId);
	    }
	    if (GemFireXDUtils.TraceConglomRead) {
	      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_READ,
	          toString() + ": returning single column for colId=" + colId
	          + ": " + dvd + "(type: " + dvd.getTypeName() + ", id="
	          + dvd.getTypeFormatId() + ')');
	    }
	    return dvd;
	  }
	  else if (this.expressionDVDs != null) {
	    dvd = this.expressionDVDs[colId - 1];
	    if (GemFireXDUtils.TraceConglomRead) {
	      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_READ,
	          toString() + ": returning column for colId=" + colId
	          + ": " + dvd + "(type: " + dvd.getTypeName() + ", id="
	          + dvd.getTypeFormatId() + ')');
	    }
	    return dvd;
	  }
	  final ExecRow row = this.row[rsNumber];
	  if (row != null) {
	    return row.getColumn(colId);
	  }
	  else {
	    return null;
	  }
	  /* (original code)
        if( row[rsNumber] == null)
        {
            /* This actually happens. NoPutResultSetImpl.clearOrderableCache attempts to prefetch invariant values
             * into a cache. This fails in some deeply nested joins. See Beetle 4736 and 4880.
             *
            return null;
        }
		return row[rsNumber].getColumn(colId);
	*/
// GemStone changes END
	}

    /**
     * Check that a positioned statement is executing against a cursor
     * from the same PreparedStatement (plan) that the positioned
     * statement was original compiled against.
     * 
     * Only called from generated code for positioned UPDATE and DELETE
     * statements. See CurrentOfNode.
     * 
     * @param cursorName Name of the cursor
     * @param psName Object name of the PreparedStatement.
     * @throws StandardException
     */
	protected void checkPositionedStatement(String cursorName, String psName)
		throws StandardException {

		ExecPreparedStatement ps = getPreparedStatement();
		if (ps == null)
			return;
			
		LanguageConnectionContext lcc = getLanguageConnectionContext();

		CursorActivation cursorActivation = lcc.lookupCursorActivation(cursorName);

		if (cursorActivation != null)
		{
			// check we are compiled against the correct cursor
			if (!psName.equals(cursorActivation.getPreparedStatement().getObjectName())) {

				// our prepared statement is now invalid since there
				// exists another cursor with the same name but a different
				// statement.
				ps.makeInvalid(DependencyManager.CHANGED_CURSOR, lcc);
			}
		}
	}

	/* This method is used to materialize a resultset if can actually fit in the memory
	 * specified by "maxMemoryPerTable" system property.  It converts the result set into
	 * union(union(union...(union(row, row), row), ...row), row).  It returns this
	 * in-memory converted resultset, or the original result set if not converted.
	 * See beetle 4373 for details.
	 *
	 * Optimization implemented as part of Beetle: 4373 can cause severe stack overflow
	 * problems. See JIRA entry DERBY-634. With default MAX_MEMORY_PER_TABLE of 1MG, it is
	 * possible that this optimization could attempt to cache upto 250K rows as nested
	 * union results. At runtime, this would cause stack overflow.
	 *
	 * As Jeff mentioned in DERBY-634, right way to optimize original problem would have been
	 * to address subquery materialization during optimization phase, through hash joins.
	 * Recent Army's optimizer work through DEBRY-781 and related work introduced a way to
	 * materialize subquery results correctly and needs to be extended to cover this case.
	 * While his optimization needs to be made more generic and stable, I propose to avoid
	 * this regression by limiting size of the materialized resultset created here to be
	 * less than MAX_MEMORY_PER_TABLE and MAX_DYNAMIC_MATERIALIZED_ROWS.
	 *
	 *	@param	rs	input result set
	 *	@return	materialized resultset, or original rs if it can't be materialized
	 */
	public NoPutResultSet materializeResultSetIfPossible(NoPutResultSet rs)
		throws StandardException
	{
	        boolean isGfxdSubquery = rs instanceof GfxdSubqueryResultSet;
	     // GemStone changes begin
                if(isGfxdSubquery) {
                     rs.openCore();
                      return rs; 
	    // GemStone changes end
                }else {
		rs.openCore();
		Vector rowCache = new Vector();
		ExecRow aRow;
		int cacheSize = 0;
		FormatableBitSet toClone = null;

		int maxMemoryPerTable = getLanguageConnectionContext().getOptimizerFactory().getMaxMemoryPerTable();
    boolean isOffHeapEnabled = GemFireXDUtils.isOffHeapEnabled();
        
		aRow = rs.getNextRowCore();
		if (aRow != null)
		{
			toClone = new FormatableBitSet(aRow.nColumns() + 1);
			toClone.set(1);
		}
		boolean anyByteSourceRetained = false;
		while (aRow != null)
		{
			cacheSize += aRow.getColumn(1).getLength();
			if (cacheSize > maxMemoryPerTable ||
				rowCache.size() > Optimizer.MAX_DYNAMIC_MATERIALIZED_ROWS 
				)
				break;
			ExecRow clonedRow = aRow.getClone(toClone); 
			rowCache.addElement(clonedRow);
			
      if (isOffHeapEnabled) {
        Object byteSource = clonedRow.getByteSource();
        if (byteSource != null) {
          Class<?> byteSourceClass = byteSource.getClass();
          if (!(byteSourceClass == byte[].class || byteSourceClass == byte[][].class)) {
            // safe to cast it to OffHeapByteSource
            // retain the byte source
            ((OffHeapByteSource) byteSource).retain();
            anyByteSourceRetained = true;
          }
        }
        rs.releasePreviousByteSource();
      }
			aRow = rs.getNextRowCore();
		}
		boolean releaseCachedByteSource = anyByteSourceRetained;
		try {
		rs.close(false);
		if (aRow == null)
		{
		  releaseCachedByteSource = false;
			int rsNum = rs.resultSetNumber();

			int numRows = rowCache.size();
			if (numRows == 0)
			{
				return new RowResultSet(
										this,
										(ExecRow) null,
										true,
										rsNum,
										0,
										0);
			}
			RowResultSet[] rrs = new RowResultSet[numRows];
			UnionResultSet[] urs = new UnionResultSet[numRows - 1];

			for (int i = 0; i < numRows; i++)
			{
				rrs[i] = new RowResultSet(
										this,
										(ExecRow) rowCache.elementAt(i),
										true,
										rsNum,
										1,
										0);
				if (i > 0)
				{
					urs[i - 1] = new UnionResultSet (
										(i > 1) ? (NoPutResultSet)urs[i - 2] : (NoPutResultSet)rrs[0],
										rrs[i],
										this,
										rsNum,
										i + 1,
										0);
				}
			}

			rs.finish();

			if (numRows == 1)
				return rrs[0];
			else
				return urs[urs.length - 1];
		}
		return rs;
		}finally {
		  if(releaseCachedByteSource) {
		    int size = rowCache.size();
		    for(int i =0; i < size;++i) {
		      ExecRow row = (ExecRow)rowCache.get(i);
		      row.releaseByteSource();
		    }
		  }
		  
		}
                }
		
                
	}


	//WARNING : this field name is referred in the DeleteNode generate routines.
	protected CursorResultSet[] raParentResultSets;


	// maintain hash table of parent result set vector
	// a table can have more than one parent source.
	protected Hashtable parentResultSets;
	public void setParentResultSet(TemporaryRowHolder rs, String resultSetId)
	{
		Vector  rsVector;
		if(parentResultSets == null)
			parentResultSets = new Hashtable();
		rsVector = (Vector) parentResultSets.get(resultSetId);
		if(rsVector == null)
		{
			rsVector = new Vector();
			rsVector.addElement(rs);
		}else
		{
			rsVector.addElement(rs);
		}
		parentResultSets.put(resultSetId , rsVector);
	}

	/**
	 * get the reference to parent table ResultSets, that will be needed by the 
	 * referential action dependent table scans.
	 */
	public Vector getParentResultSet(String resultSetId)
	{
		return (Vector) parentResultSets.get(resultSetId);
	}

	public Hashtable getParentResultSets()
	{
		return parentResultSets;
	}

	/**
	 ** prepared statement use the same activation for
	 ** multiple execution. For each excution we create new
	 ** set of temporary resultsets, we should clear this hash table.
	 ** otherwise we will refer to the released resources.
	 */
	public void clearParentResultSets()
	{
		if(parentResultSets != null)
			parentResultSets.clear();
	}

	/**
	 * beetle 3865: updateable cursor using index.  A way of communication
	 * between cursor activation and update activation.
	 */
	public void setForUpdateIndexScan(CursorResultSet forUpdateIndexScan)
	{
		this.forUpdateIndexScan = forUpdateIndexScan;
	}

	public CursorResultSet getForUpdateIndexScan()
	{
		return forUpdateIndexScan;
	}

	private java.util.Calendar cal;
	/**
		Return a calendar for use by this activation.
		Calendar objects are not thread safe, the one returned
		is purely for use by this activation and it is assumed
		that is it single threded through the single active
		thread in a connection model.
	*/
	protected java.util.Calendar getCalendar() {
		if (cal == null)
			cal = ClientSharedData.getDefaultCalendar();
		cal.clear();
		return cal;

	}


	/*
	** Code originally in the parent class BaseExpressionActivation
	*/
	/**
	    Get the language connection factory associated with this connection
	  */
	public final LanguageConnectionContext	getLanguageConnectionContext()
	{
		return	lcc;
	}

	public final TransactionController getTransactionController()
	{
		return lcc.getTransactionExecute();
	}
			
	/**
	 * Get the Current ContextManager.
	 *
	 * @return Current ContextManager
	 */
	public ContextManager getContextManager()
	{
		return cm;
	}

	/**
		Used by activations to generate data values.  Most DML statements
		will use this method.  Possibly some DDL statements will, as well.
	 */
	public DataValueFactory getDataValueFactory()
	{
		return getLanguageConnectionContext().getDataValueFactory();
	}

	/**
	 * Used to get a proxy for the current connection.
	 *
	 * @exception SQLException		Thrown on failure to get connection
	 */
	public Connection getCurrentConnection() throws SQLException {

		ConnectionContext cc = 
			(ConnectionContext) getContextManager().getContext(ConnectionContext.CONTEXT_ID);

		return cc.getNestedConnection(true);
	}	

	/**
		Real implementations of this method are provided by a generated class.
	*/
	public java.sql.ResultSet[][] getDynamicResults() {
		return null;
	}
	/**
		Real implementations of this method are provided by a generated class.
	*/
	public int getMaxDynamicResults() {
		return 0;
	}

    /**
     * Compute the DB2 compatible length of a value.
     *
     * @param value
     * @param constantLength The length, if it is a constant modulo null/not null. -1 if the length is not constant
     * @param reUse If non-null then re-use this as a container for the length
     *
     * @return the DB2 compatible length, set to null if value is null.
     */
    public NumberDataValue getDB2Length( DataValueDescriptor value,
                                         int constantLength,
                                         NumberDataValue reUse)
        throws StandardException
    {
        if( reUse == null)
            reUse = getDataValueFactory().getNullInteger( null);
        if( value.isNull())
            reUse.setToNull();
        else
        {
            if( constantLength >= 0)
                reUse.setValue( constantLength);
            else
            {
                reUse.setValue(value.getLength());
            }
        }
        return reUse;
    } // end of getDB2Length

// GemStone changes BEGIN
  // this method is added for the java.sql.ResultSet creation in the data-aware
  // procedure.
  public ResultDescription switchResultDescription(ResultDescription rd) {
    ResultDescription old = this.resultDescription;
    this.resultDescription = rd;
    return old;

  }

  public void setProxyParameterValueSet(ParameterValueSet pvs) {
    this.pvs = pvs;
  }

  private boolean[] updatedColumns = null;

  private int[] projectMapping = null;

  private FunctionContext fnContext;

  private ProcedureSender sender;

  private int flags;

  /**
   * {@inheritDoc}
   */
  public final FunctionContext getFunctionContext() {
    return this.fnContext;
  }

  /**
   * {@inheritDoc}
   */
  public final void setFunctionContext(final FunctionContext fc) {
    this.fnContext = fc;
  }

  /**
   * {@inheritDoc}
   */
  public final void setFlags(int flags) {
    // keep immutable bits
    final int immutableFlags = (this.flags & FLAGS_IMMUTABLE);
    this.flags = (flags & (~FLAGS_IMMUTABLE));
    this.flags |= immutableFlags;
  }

  public final void copyFlags(BaseActivation target) {
    target.flags = this.flags;
  }

  /**
   * {@inheritDoc}
   */
  public final boolean isSpecialCaseOuterJoin() {
    return (this.flags & SPECIAL_OUTER_JOIN) != 0;
  }

  /**
   * {@inheritDoc}
   */
  public final void setSpecialCaseOuterJoin(boolean flag) {
    this.flags = GemFireXDUtils.set(this.flags, SPECIAL_OUTER_JOIN, flag);
  }

  /**
   * {@inheritDoc}
   */
  public final boolean needKeysForSelectForUpdate() {
    return (this.flags & NEED_KEY_FOR_SELECT_FOR_UPDATE) != 0;
  }

  /**
   * {@inheritDoc}
   */
  public final void setNeedKeysForSelectForUpdate(boolean flag) {
    this.flags = GemFireXDUtils.set(this.flags, NEED_KEY_FOR_SELECT_FOR_UPDATE,
        flag);
  }

  /**
   * {@inheritDoc}
   */
  public final boolean isPreparedBatch() {
    return (this.flags & IS_PREPARED_BATCH) != 0;
  }

  /**
   * {@inheritDoc}
   */
  public final void setPreparedBatch(boolean flag) {
    this.flags = GemFireXDUtils.set(this.flags, IS_PREPARED_BATCH, flag);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean getInsertAsSubselect() {
    return (this.flags & INSERT_AS_SUBSELECT) != 0;
  }

   public final boolean isPutDML() {
    return this.isPutDML;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setInsertAsSubselect(boolean flag) {
    this.flags = GemFireXDUtils.set(this.flags, INSERT_AS_SUBSELECT, flag);
  }
  
  public final void setPutDML(boolean val) {
    this.isPutDML = val;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ProcedureSender getProcedureSender() {
    return this.sender;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setProcedureSender(ProcedureSender sender) {
    this.sender = sender;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isPossibleDuplicate() {
    return (this.flags & IS_POSSIBLE_DUPLICATE) != 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setPossibleDuplicate(boolean flag) {
    this.flags = GemFireXDUtils.set(this.flags, IS_POSSIBLE_DUPLICATE, flag);
  }

  public void setUpdatedColumns(boolean[] updatedColumns) {
    this.updatedColumns = updatedColumns;
  }

  public boolean[] getUpdatedColumns() {
    return updatedColumns;
  }

  public void setProjectMapping(int[] projectMapping) {
    if (this.projectMapping != null) {
      // mapping on mapping
      int[] newMapping = projectMapping.clone();
      for (int index = 0; index < this.projectMapping.length; ++index) {
        newMapping[index] = projectMapping[this.projectMapping[index] - 1];
      }
      this.projectMapping = newMapping;
    }
    else {
      this.projectMapping = projectMapping;
    }
  }

  public int[] getProjectMapping() {
    return projectMapping;
  }
  
  public final void checkCancellationFlag() throws StandardException {
    if (isQueryCancelled()) {
      switch (queryCancellationFlag) {
      case BaseActivation.CANCELLED_LOW_MEMORY:
        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceHeapThresh || GemFireXDUtils.TraceExecution) {
            SanityManager
                .DEBUG_PRINT(
                    GfxdConstants.TRACE_HEAPTHRESH,
                    "BaseActivation: statement "
                        + (preStmt != null ? preStmt
                            .getUserQueryString(this.lcc) : "null statement")
                        + " is cancelled due to low memory");
          }
        }
        // stats updated in GfxdHeapThresholdListener.cancelTopMemoryConsumingQuery()
        throw Misc.generateLowMemoryException(getPreparedStatement()
            .getUserQueryString(this.lcc));
        
      case BaseActivation.CANCELLED_TIMED_OUT:
        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceExecution) {
            SanityManager.DEBUG_PRINT(
                GfxdConstants.TRACE_EXECUTION,
                "BaseActivation: statement "
                    + (preStmt != null ? preStmt.getUserQueryString(this.lcc)
                        : "null statement") + " is cancelled due to timeout");
          }
        }
        //update the stats
        Misc.getMemStoreBooting().getStoreStatistics().collectQueryTimeOutStats();
        throw StandardException
            .newException(SQLState.LANG_STATEMENT_CANCELLED_ON_TIME_OUT);
        
      case BaseActivation.CANCELLED_USER_REQUESTED:
        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceExecution) {
            SanityManager.DEBUG_PRINT(
                GfxdConstants.TRACE_EXECUTION,
                "BaseActivation: statement "
                    + (preStmt != null ? preStmt.getUserQueryString(this.lcc)
                        : "null statement") + " is cancelled due to a user request");
          }
        }
        throw StandardException
            .newException(SQLState.LANG_STATEMENT_CANCELLED_ON_USER_REQUEST);
        
      default:
        break;
      }
    }
  }

  /**
   * This method should be modified with caution as it can 
   * increase computation overhead drastically because called
   * by getNextRowCore() of every ResultSet.
   */
  public final boolean isQueryCancelled() {
    return (queryCancellationFlag != 0) ;
  }

  public final void cancelOnLowMemory() {
    queryCancellationFlag = BaseActivation.CANCELLED_LOW_MEMORY;
    LogWriter logger = Misc.getCacheLogWriterNoThrow();
    if (logger != null && logger.warningEnabled()) {
      logger.warning("BaseActivation: cancelling statement due to low memory "
          + (preStmt != null ? preStmt.getUserQueryString(this.getLanguageConnectionContext()) : " NULL "));
    }
  }
  
  public final void cancelOnTimeOut() {
    queryCancellationFlag = BaseActivation.CANCELLED_TIMED_OUT;
    LogWriter logger = Misc.getCacheLogWriterNoThrow();
    if (logger != null && logger.warningEnabled()) {
      logger.warning("BaseActivation: cancelling statement due to query timeout "
          + (preStmt != null ? preStmt.getUserQueryString(this.getLanguageConnectionContext()) : " NULL "));
    }
  }
  
  public final void cancelOnUserRequest() {
    queryCancellationFlag = BaseActivation.CANCELLED_USER_REQUESTED;
    LogWriter logger = Misc.getCacheLogWriterNoThrow();
    if (logger != null && logger.warningEnabled()) {
      logger.warning("BaseActivation: cancelling statement due to a user request "
          + (preStmt != null ? preStmt.getUserQueryString(this.getLanguageConnectionContext()) : " NULL "));
    }
  }
  
  public void setTimeOutMillis(long timeOutMillis) {
    this.timeoutMillis = timeOutMillis;
  }
  
  public long getTimeOutMillis() {
    return this.timeoutMillis;
  }
  
  public long estimateMemoryUsage() throws StandardException {
    return Misc.estimateMemoryUsage(lcc, resultSet, (preStmt != null ? preStmt
        .getUserQueryString(getLanguageConnectionContext()) : null));
  }

  public void distributeTxCacheLoaded(LocalRegion rgn, GemFireTransaction gft,
      Object key, Object value) throws StandardException {
    if (rgn.isGatewaySenderEnabled()) {
      // Assume that all the DML strings passed to the DBSynchronizer
      // will be stored in the synchronizer as PrepStatement only, unless there
      // is a case to distinguish statements from prep statements we will not
      // pass the information of statement type.

      assert gft != null && gft.isTransactional();

      CacheLoadedDBSynchronizerMessage dbm = new CacheLoadedDBSynchronizerMessage(
          rgn, key, value, gft.isTransactional());
      if (GemFireXDUtils.TraceDBSynchronizer) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
            "BaseActivation: distributeTxCacheLoaded " + "Key =" + key
                + ";value = " + value + ";Transaction = " + gft);
      }

      gft.addDBSynchronizerOperation(dbm);
    }
  }

  public final void distributeBulkOpToDBSynchronizer(LocalRegion rgn,
      boolean isDynamic, GemFireTransaction tran, boolean skipListeners)
      throws StandardException {
    distributeBulkOpToDBSynchronizer(rgn, isDynamic, tran, skipListeners, null);
  }

  public final void distributeBulkOpToDBSynchronizer(LocalRegion rgn,
      boolean isDynamic, GemFireTransaction tran, boolean skipListeners,
      ArrayList<Object> bulkInsertRows) throws StandardException {
    // only distribute if there is a gateway or async listener
    if (rgn.isGatewaySenderEnabled()
        && (!skipListeners || rgn.getGatewaySenderIds().size() > 0)) {
     // System.out.println("YOGS INSDIE " + bulkInsertRows);
      // Assume that all the DML strings passed to the DBSynchronizer
      // will be stored in the synchronizer as PrepStatement only, unless there
      // is a case to distinguish statements from prep statements we will not
      // pass the information of statement type.
      LanguageConnectionContext lcc = getLanguageConnectionContext();
      String source = this.preStmt.getUserQueryString(lcc);
      if (GemFireXDUtils.TraceDBSynchronizer) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
            "BaseActivation: distributing bulk "
            + (isDynamic ? " dynamic " : " static ") + "DML operation"
            + " with dml string =" + source + ". " + "Transaction = " + tran);
      }
      final boolean isTransactional = (tran != null && tran.isTransactional());
      GatewaySenderEventCallbackArgument senderArg = this
          .getLanguageConnectionContext().getGatewaySenderEventCallbackArg();
      GemFireContainer gfc = (GemFireContainer)rgn.getUserAttribute();
      final BulkDBSynchronizerMessage dbm;
      final GfxdCBArgForSynchPrms cbArg;

      final ParameterValueSet params = this.getParameterValueSet();
      if (params instanceof ConstantValueSet) {
        if (SanityManager.DEBUG) {
          if (!isSingleExecution()) {
            SanityManager.THROWASSERT("This activation must be for single use "
                + "i.e. statement query activation & shouldn't be cached.");
          }
        }
        source = statementText;
        assert source != null;
        isDynamic = false;
      }
      if (bulkInsertRows != null) {
        /*
        // for the case of insert as subselect, need to draw up the prepared
        // statement string separately
        if (getInsertAsSubselect()) {
          source = DBSynchronizer.getInsertString(gfc, gfc.getRowFormatter());
        }
        else if (!isDynamic || params == null || params.getParameterCount() == 0) {
          bulkInsertRows = null;
        }
        */
        final ExtraTableInfo tableInfo = gfc.getExtraTableInfo();
        try {
          source = AsyncEventHelper.getInsertString(
              gfc.getQualifiedTableName(), tableInfo.getRowFormatter()
                  .getMetaData(), tableInfo.hasAutoGeneratedColumns());
        } catch (SQLException sqle) {
          throw Misc.wrapSQLException(sqle, sqle);
        }
        cbArg = new GfxdCBArgForSynchPrms(source, bulkInsertRows, gfc,
            isTransactional, senderArg, this.isPutDML);
      }
      else if (isDynamic) {
        GenericParameterValueSet pvs = (GenericParameterValueSet)params;
        if (isTransactional) {
          // We will have to clone it, so that if next operation is also on same
          // prep statement data is not lost
          pvs = (GenericParameterValueSet)pvs.getClone();
        }
        cbArg = new GfxdCBArgForSynchPrms(source, pvs,
            this.preStmt.getParameterTypes(), gfc.getSchemaName(),
            gfc.getTableName(), isTransactional, senderArg, this.isPutDML);
      }
      else {
        cbArg = new GfxdCBArgForSynchPrms(source, gfc.getSchemaName(),
            gfc.getTableName(), isTransactional, senderArg, this.isPutDML);
      }
      dbm = new BulkDBSynchronizerMessage(rgn, source, skipListeners, cbArg);
      if (isTransactional) {
        tran.addDBSynchronizerOperation(dbm);
      }
      else {
        dbm.applyOperation();
      }
    }
  }

  private final void setConstantParameters(ParameterValueSet parameterValueSet)
      throws StandardException {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceStatementMatching) {
        ExecPreparedStatement ps = getPreparedStatement();
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_STATEMENT_MATCHING,
            GemFireXDUtils.addressOf(this) + ":setConstantParameters: ("
                + (ps != null ? ps.getStatement() : " NULL ")
                + (parameterValueSet != null ? ") setting with constantValues "
                    + parameterValueSet : ") setting  with constantValues [ NULL ] ")
                + " of parameter types "
                + (ps != null ? ps.getParameterTypes() : " NULL "));
      }
    }

    // when called within the trigger, ConstantValueSet might be non-null for
    // parent statment invocation, but child statement should have pvs of its
    // own. see GfxdIndexManager#fireStatementTriggers.
    if (parameterValueSet != null
        && (preStmt == null || preStmt.getParameterTypes() != null)) {
      pvs = parameterValueSet;
    }
  }

  // do nothing ... as we create GFResultSets sometimes with derby activation
  // class.
  public void accept(ActivationStatisticsVisitor visitor) {
  }
  
  public ExecRow getCurrentRow(int resultSetNumber)
  {
    if (SanityManager.DEBUG_ASSERT) 
    {
            SanityManager.ASSERT(!isClosed(), "closed");
            if (row != null)
            {
                    if (!(resultSetNumber >=0 && resultSetNumber < row.length))
                    {
                            SanityManager.THROWASSERT("resultSetNumber = " + resultSetNumber +
                                                     ", expected to be between 0 and " + row.length);
                    }
            }
    }
    
    if (row != null)
    {
            return row[resultSetNumber];
    }
    
    return null;
  }
  
  private boolean useOnlyPrimaryBuckets;
  
  public void setUseOnlyPrimaryBuckets(boolean b) {
    this.useOnlyPrimaryBuckets = b;  
  }
  
  public boolean getUseOnlyPrimaryBuckets() {
    return this.useOnlyPrimaryBuckets;
  }
  
  private boolean queryHDFS = false;
  
  public void setQueryHDFS(boolean val) {
    this.queryHDFS = val;
  }
  
  public boolean getQueryHDFS() {
    return this.queryHDFS;
  }
    
  private boolean hasQueryHDFS = false;
  
  public boolean getHasQueryHDFS() {
    return hasQueryHDFS;
  }

  public void setHasQueryHDFS(boolean hasQueryHDFS) {
    this.hasQueryHDFS = hasQueryHDFS;
  }
  
  /**
   *  Get the specified saved object.
   *
   *  @param  objectNum The object to get.
   *  @return the requested saved object.
   */
  @Override
  public final Object getSavedObject(int objectNum)
  {
    if (SanityManager.DEBUG) {
      if (!(objectNum>=0 && objectNum<savedObjects.length))
      SanityManager.THROWASSERT(
        "request for savedObject entry "+objectNum+" invalid; "+
        "savedObjects has "+savedObjects.length+" entries");
    }
    return  savedObjects[objectNum];
  }
  
  @Override
  public final Object[] getSavedObjects() {
    return this.savedObjects;
  }
  
  //GemStone changes END
}
