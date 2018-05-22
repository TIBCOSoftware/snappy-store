/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement

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

package com.pivotal.gemfirexd.internal.impl.sql;

// GemStone changes BEGIN
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.internal.cache.AbstractRegion;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;

import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireUpdateActivation;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.management.GfxdResourceEvent;
// GemStone changes END

import com.pivotal.gemfirexd.internal.catalog.Dependable;
import com.pivotal.gemfirexd.internal.catalog.DependableFinder;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.sql.execute.SnappyActivation;
import com.pivotal.gemfirexd.internal.engine.sql.execute.SnappySelectResultSet;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.Cacheable;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedClass;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.services.stream.HeaderPrintWriter;
import com.pivotal.gemfirexd.internal.iapi.services.uuid.UUIDFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Statement;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.TypeCompiler;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.Provider;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.ProviderList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SPSDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecCursorTableReference;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.util.ByteArray;
import com.pivotal.gemfirexd.internal.impl.sql.compile.CursorNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.StatementNode;
import com.pivotal.gemfirexd.internal.impl.sql.conn.GenericLanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.SingleHopInformation;

import java.io.Serializable;
import java.sql.Timestamp;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Basic implementation of prepared statement.
 * relies on implementation of ResultDescription and Statement that
 * are also in this package.
 * <p>
 * These are both dependents (of the schema objects and prepared statements
 * they depend on) and providers.  Prepared statements that are providers
 * are cursors that end up being used in positioned delete and update
 * statements (at present).
 * <p>
 * This is impl with the regular prepared statements; they will never
 * have the cursor info fields set.
 * <p>
 * Stored prepared statements extend this implementation
 *
 */
public class GenericPreparedStatement
	implements ExecPreparedStatement, Serializable
{
	///////////////////////////////////////////////
	//
	// WARNING: when adding members to this class, be
	// sure to do the right thing in getClone(): if
	// it is PreparedStatement specific like finished,
	// then it shouldn't be copied, but stuff like parameters
	// must be copied.
	//
	////////////////////////////////////////////////

	////////////////////////////////////////////////
	// STATE that is copied by getClone()
	////////////////////////////////////////////////
	public Statement statement;
	protected volatile /* GemStoneAddition*/ GeneratedClass activationClass; // satisfies Activation
	protected ResultDescription resultDesc;
	protected DataTypeDescriptor[] paramTypeDescriptors;

	private String			spsName;
	private SQLWarning		warnings;
// GemStone changes BEGIN
	protected List<TypeCompiler> origTypeCompilers;
        private int statementType ;
        private LocalRegion localRegion = null;
        private PartitionedRegion partitionedRegionForSingleHop = null;
        private boolean isSubqueryPrepStmnt;
        private ProviderList dependencyProviders = null;
        private boolean isInsertAsSubselect;
        private GenericPreparedStatement parentPS = null;
        private ArrayList<GenericPreparedStatement> childPS = null;
        private boolean useOnlyPrimaryBuckets;
        private boolean queryHDFS = false;
        private boolean hasQueryHDFS = false;
        private boolean isCallableStatement;
        private ArrayList<com.pivotal.gemfirexd.internal.impl.sql.compile.Token> dynamicTokenList;
// GemStone changes END

	//If the query node for this statement references SESSION schema tables, mark it so in the boolean below
	//This information will be used by EXECUTE STATEMENT if it is executing a statement that was created with NOCOMPILE. Because
	//of NOCOMPILE, we could not catch SESSION schema table reference by the statement at CREATE STATEMENT time. Need to catch
	//such statements at EXECUTE STATEMENT time when the query is getting compiled.
	//This information will also be used to decide if the statement should be cached or not. Any statement referencing SESSION
	//schema tables will not be cached.
	private boolean		referencesSessionSchema;

	// fields used for cursors
	protected ExecCursorTableReference	targetTable;
	protected ResultColumnDescriptor[]	targetColumns;
	protected String[] 					updateColumns;
	protected int 						updateMode;

	protected ConstantAction	executionConstants;
	protected Object[]	savedObjects;
	protected List requiredPermissionsList;

	// fields for dependency tracking
	protected String UUIDString;
	protected UUID   UUIDValue;

	private boolean needsSavepoint;

	private String execStmtName;
	private String execSchemaName;
	protected boolean isAtomic;
	protected String sourceTxt;

// GemStone changes BEGIN
	private final AtomicInteger inUseCount = new AtomicInteger(0);
	/* (original code)
	private int inUseCount;

	// true if the statement is being compiled.
	public volatile boolean compilingStatement;
	*/
// GemStone changes END


	////////////////////////////////////////////////
	// STATE that is not copied by getClone()
	////////////////////////////////////////////////
	// fields for run time stats
	protected long parseTime;
	protected long bindTime;
	protected long optimizeTime;
	// GemStone changes BEGIN
	protected long routingInfoTime;
	// GemStone changes END
	protected long generateTime;
	protected long compileTime;
	protected Timestamp beginCompileTimestamp;
	protected Timestamp endCompileTimestamp;
// GemStone changes BEGIN
	protected int numPrepared;
// GemStone changes END

	//private boolean finished;
// GemStone changes BEGIN
	// reducing the visibility
	// using an atomic integer to store the flags instead to avoid
	// syncpoints for readers
	private static final int IS_INVALID = 0x1;
	private static final int IS_COMPILING = 0x2;
	private static final int IS_ACTIVATION_CLASS_NULL = 0x4;
	private final AtomicInteger isInvalid = new AtomicInteger(
	    IS_INVALID | IS_ACTIVATION_CLASS_NULL);
	//private boolean isValid;
	private transient StatementStats stats;
	private Set routingInfoObjects;
	private volatile QueryInfo qinfo;
	private boolean isPutDML = false;
// GemStone changes END
	protected boolean spsAction;

	// state for caching.
	/**
		If non-null then this object is the cacheable
		that holds us in the cache.
	*/
	private volatile /* GemStoneAddition */ Cacheable cacheHolder;

	/**
	 * Incremented for each (re)compile.
	 */
	private long versionCounter;

	//
	// constructors
	//
	private GenericPreparedStatement(boolean isSubqueryPrepStmnt) {
		/* Get the UUID for this prepared statement */
		UUIDFactory uuidFactory =
			Monitor.getMonitor().getUUIDFactory();

		UUIDValue = uuidFactory.createUUID();
		UUIDString = UUIDValue.toString();
		spsAction = false;
		this.isSubqueryPrepStmnt = isSubqueryPrepStmnt;
	}

	GenericPreparedStatement() {
          this(false /* is not a subquery prep stmnt*/);
        }

	/**
	 */
	public GenericPreparedStatement(Statement st)
	{
          this(false /* is not a subquery prep stmnt*/);

          statement = st;
	}

	public GenericPreparedStatement(Statement st, boolean isSubqueryPrepStmt)
        {
          this(isSubqueryPrepStmt);
          statement = st;
        }

	//
	// PreparedStatement interface
	//
// GemStone changes BEGIN
	public final boolean upToDate() {
	  return this.isInvalid.get() == 0;
	}
	/* (original code)
	public synchronized boolean	upToDate()
		throws StandardException
	{
		return  isValid && (activationClass != null) && !compilingStatement;
	}
	*/
// GemStone changes END

	// old activation(i.e. the one before rePrepare) is passed in order to 
	// flush the batch in case of batched updates (#48263)
	public void rePrepare(LanguageConnectionContext lcc, Activation oldAct) 
		throws StandardException {
		if (!upToDate()) {
// GemStone changes BEGIN
			if (this.isSubqueryPrepStmnt) {
				GenericPreparedStatement pps = this.getParentPS();
				pps.makeInvalid(DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
				throw StandardException.newException(SQLState.LANG_STATEMENT_NEEDS_RECOMPILE_PARENT);
			}

                        if (GemFireXDUtils.TraceStatementMatching) {
                          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_STATEMENT_MATCHING,
                              GemFireXDUtils.addressOf(this) + " starting to reprepare [" + statement
                                  + " ]", new Throwable());
                        }
      // #48263: if a batch update statement is to be re-prepared flush
      // values accumulated so far
      Activation a = null;
      if (oldAct != null) {
        if (oldAct instanceof GenericActivationHolder) {
          a = ((GenericActivationHolder)oldAct).getActivation();
        } else if (oldAct instanceof Activation) {
          a = oldAct;
        }
      }
      if ((a != null) && (a instanceof GemFireUpdateActivation)) {
        if (GemFireXDUtils.TraceConglomUpdate) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
              "GPS#rePrepare flushing the current batch for GemFireUpdateActivation: "
                  + a);
        }
        ((GemFireUpdateActivation) a).flushBatch();
      }
      FunctionContext fc = oldAct != null ? oldAct.getFunctionContext() : null;
      boolean allTablesReplicated = false;
      if (fc != null && fc instanceof RegionExecutorMessage) {
        allTablesReplicated = ((RegionExecutorMessage<?>)fc)
        .allTablesAreReplicatedOnRemote();
      }
      ((GenericStatement)statement).setReplicatedFlag(allTablesReplicated);
      
			PreparedStatement ps = statement.prepare(lcc,false);
// GemStone changes END

			if (SanityManager.DEBUG)
				SanityManager.ASSERT(ps == this, "ps != this");
		}
	}

	/**
	 * Get a new activation instance.
	 *
	 * @exception StandardException thrown if finished.
	 */
	public Activation getActivation(LanguageConnectionContext lcc,
									boolean scrollable, String statementText)
		throws StandardException
	{
// GemStone changes BEGIN
	  return getActivation(lcc, scrollable, statementText, true);
	}

	public Activation getActivation(LanguageConnectionContext lcc,
	    boolean scrollable, String statementText, boolean addToLCC) throws StandardException {
		Activation ac;
		GeneratedClass gc = getActivationClass();
		if (gc == null) {
		  // Acquire the DD read lock first since otherwise the locking
		  // order will be PreparedStatement->DD-read, while a DDL
		  // invalidating the same PreparedStatement will have
		  // DD-write->PreparedStatement (in GenericPreparedStatement#
		  //   makeInvalid) -- see bug #40926.
		  DataDictionary dd = lcc.getDataDictionary();
		  int readMode = dd.startReading(lcc);
		  try {
		    synchronized (this) {
		      rePrepare(lcc, null);
		      gc = getActivationClass();
		    }
		  } finally {
		    dd.doneReading(readMode, lcc);
		  }
		}
		ac = new GenericActivationHolder(lcc, gc, this,
		    scrollable, statementText, addToLCC);
		this.inUseCount.incrementAndGet();
	        /* (original Derby code)
	        Activation ac;
		synchronized (this) {
			GeneratedClass gc = getActivationClass();

			if (gc == null) {
				rePrepare(lcc);
				gc = getActivationClass();
			}
			ac = new GenericActivationHolder(lcc, gc, this, scrollable);
			inUseCount++;
		}
		*/
		if (this.useOnlyPrimaryBuckets) {
		  ac.setUseOnlyPrimaryBuckets(true);
		}

		ac.setQueryHDFS(this.queryHDFS);
		ac.setHasQueryHDFS(this.hasQueryHDFS);
		
// GemStone changes END
		// DERBY-2689. Close unused activations-- this method should be called
		// when I'm not holding a lock on a prepared statement to avoid
		// deadlock.
		lcc.closeUnusedActivations();

		ac.setCallActivation(lcc.getCaller());

		return ac;
	}

    public ResultSet execute(LanguageConnectionContext lcc,
                             boolean rollbackParentContext,
                             long timeoutMillis)
		throws StandardException
	{
		Activation a = getActivation(lcc, false, null);
		a.setSingleExecution();
// GemStone changes BEGIN
		return execute(a, rollbackParentContext, timeoutMillis,
		    true /* pop statement context */, true);
// GemStone changes END
	}

	/**
	  *	The guts of execution.
	  *
	  *	@param	activation					the activation to run.
	 * @param rollbackParentContext True if 1) the statement context is
      *  NOT a top-level context, AND 2) in the event of a statement-level
      *	 exception, the parent context needs to be rolled back, too.
	 * @param timeoutMillis timeout value in milliseconds.
	 * @param popStmntCntxt boolean to indicate whether the StatementContext should be
      * popped out after execution
	 * @return	the result set to be pawed through
	  *
	  *	@exception	StandardException thrown on error
	  */
//  GemStone changes BEGIN
    public ResultSet execute(final Activation activation,
                             final boolean rollbackParentContext,
                             final long timeoutMillis, final boolean popStmntCntxt,
                             final boolean accountInProgress) throws StandardException {

                // the inprogress count will either decrement when an exception occurs within this function,
                // and on success, com.pivotal.gemfirexd.internal.iapi.sql.ResultSet topResultSet is closed.
                final boolean createQI = statement.createQueryInfo();
                final boolean statsEnabled = accountInProgress && activation.getLanguageConnectionContext().statsEnabled();
                if (statsEnabled) {
                    stats.incStat(StatementStats.numExecutionsInProgressId, createQI, 1);
                }
//    GemStone changes END
		boolean				needToClearSavePoint = false;


		if (activation == null || activation.getPreparedStatement() != this)
		{
			throw StandardException.newException(SQLState.LANG_WRONG_ACTIVATION, "execute");
		}

recompileOutOfDatePlan:
		while (true) {
			// verify the activation is for me--somehow.  NOTE: This is
			// different from the above check for whether the activation is
			// associated with the right PreparedStatement - it's conceivable
			// that someone could construct an activation of the wrong type
			// that points to the right PreparedStatement.
			//
			//SanityManager.ASSERT(activation instanceof activationClass, "executing wrong activation");

			/* This is where we set and clear savepoints around each individual
			 * statement which needs one.  We don't set savepoints for cursors because
			 * they're not needed and they wouldn't work in a read only database.
			 * We can't set savepoints for commit/rollback because they'll get
			 * blown away before we try to clear them.
			 */

			LanguageConnectionContext lccToUse = activation.getLanguageConnectionContext();

 			if (lccToUse.getLogStatementText())
			{
				HeaderPrintWriter istream = Monitor.getStream();
				String xactId = lccToUse.getTransactionExecute().getActiveStateTxIdString();
				String pvsString = "";
				ParameterValueSet pvs = activation.getParameterValueSet();
				if (pvs != null && pvs.getParameterCount() > 0)
				{
					pvsString = " with " + pvs.getParameterCount() +
							" parameters " + pvs.toString();
				}
				istream.printlnWithHeader(LanguageConnectionContext.xidStr +
										  xactId +
										  "), " +
										  LanguageConnectionContext.lccStr +
										  lccToUse.getInstanceNumber() +
										  "), " +
										  LanguageConnectionContext.dbnameStr +
										  lccToUse.getDbname() +
										  "), " +
										  LanguageConnectionContext.drdaStr +
										  lccToUse.getDrdaID() +
										  "), Executing prepared statement: " +
										  getSource() +
										  " :End prepared statement" +
										  pvsString);
			}

			ParameterValueSet pvs = activation.getParameterValueSet();

			/* put it in try block to unlock the PS in any case
			 */
			if (!spsAction) {
			// only re-prepare if this isn't an SPS for a trigger-action;
			// if it _is_ an SPS for a trigger action, then we can't just
			// do a regular prepare because the statement might contain
			// internal SQL that isn't allowed in other statements (such as a
			// static method call to get the trigger context for retrieval
			// of "new row" or "old row" values).  So in that case we
			// skip the call to 'rePrepare' and if the statement is out
			// of date, we'll get a NEEDS_COMPILE exception when we try
			// to execute.  That exception will be caught by the executeSPS()
			// method of the GenericTriggerExecutor class, and at that time
			// the SPS action will be recompiled correctly.
        final GemFireXDQueryObserver observer =
            GemFireXDQueryObserverHolder.getInstance();
        if (observer != null) {
          observer.beforeQueryReprepare(this, lccToUse);
        }
				rePrepare(lccToUse, activation);
			}

			final StatementContext statementContext = lccToUse.pushStatementContext(
				isAtomic, updateMode==CursorNode.READ_ONLY, getSource(), pvs, rollbackParentContext, timeoutMillis);


			if (needsSavepoint())
			{
				/* Mark this position in the log so that a statement
				* rollback will undo any changes.
				*/
				statementContext.setSavePoint();
				needToClearSavePoint = true;
			}

			if (executionConstants != null)
			{
				lccToUse.validateStmtExecution(executionConstants);
			}

			ResultSet resultSet = null;
// GemStone changes BEGIN
			final long beginExecute = lccToUse.getStatisticsTiming() ? XPLAINUtil.recordTiming(-1) : 0;
			final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();

			try {
			  if (SanityManager.DEBUG) {
			    if (observer != null) {
			      observer.beforeQueryExecution(this, lccToUse);
			    }
			  }
			  if (GemFireXDUtils.TraceActivation) {
			      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
			          "GenericPreparedStatement::execute::" +
			          "Using activation = " + activation);
			  }
			  resultSet = activation.execute();
			  if (SanityManager.DEBUG) {
			    if (observer != null) {
			      observer.afterQueryExecution(this, activation);
			    }
			  }
// GemStone changes END
			  resultSet.open();
// GemStone changes BEGIN
			  if (SanityManager.DEBUG) {
			    if (observer != null) {
			      observer.afterResultSetOpen(this, lccToUse, resultSet);
			    }
			  }
// GemStone changes END
			} catch (StandardException se) {

			// GemStone changes BEGIN
			  /* Cann't handle recompiling SPS action recompile here */
			  if (!se.getMessageId().equals(SQLState.LANG_STATEMENT_NEEDS_RECOMPILE)
						 || spsAction) {
			    GemFireTransaction gft = (GemFireTransaction) lccToUse.
			        getTransactionExecute();
			    if(gft != null) {
			      gft.release();
			    }
			    throw se;
			  }
					
				
			  statementContext.cleanupOnError(se);
        
//GemStone changes BEGIN
				// release all locks for GemFireXD
				TransactionController tc = lccToUse
				    .getTransactionExecute();
				if (tc != null) {
				  tc.releaseAllLocks(false, false);
				}
				//lccToUse.internalCleanup();
// GemStone changes END
				continue recompileOutOfDatePlan;

			}
// GemStone changes BEGIN
			finally {

                          // Instead of taking this flag all the way to iapi.ResultSet and
                          // decrementing in its close, we are optimistic here. This anyway
                          // happens for internal queries created via prepareInternalStatement
                          // direct call. One possibility is BaseActivation carrying this context.
                          if (statsEnabled) {
                            stats.incStat(StatementStats.numExecutionsInProgressId, createQI, -1);
                            stats.incStat(StatementStats.numExecutionsId, createQI, 1);
                          }
                          if (beginExecute != 0 && stats != null) {
                            stats.incStat(StatementStats.executeTimeId, createQI,
                                XPLAINUtil.recordTiming(beginExecute));
                          }
			  if (observer != null) {
			    observer.statementStatsBeforeExecutingStatement(stats);
			  }
			}
// GemStone changes END

			if (needToClearSavePoint)
			{
				/* We're done with our updates */
				statementContext.clearSavePoint();
			}

// GemStone changes BEGIN
			if (popStmntCntxt) {
				lccToUse.popStatementContext(statementContext, null);
			}
// GemStone changes END

			if (activation.isSingleExecution() && resultSet.isClosed())
			{
				// if the result set is 'done', i.e. not openable,
				// then we can also release the activation.
				// Note that a result set with output parameters
				// or rows to return is explicitly finished
				// by the user.
				activation.close();
			}

			return resultSet;

		}
	}

	public ResultDescription	getResultDescription()	{
		return resultDesc;
	}

	public void	setResultDescription(ResultDescription rd)	{
		resultDesc = rd;
	}

	public DataTypeDescriptor[]	getParameterTypes()	{
		return paramTypeDescriptors;
	}

  public void setParameterTypes(DataTypeDescriptor[] paramTypes)	{
    paramTypeDescriptors = paramTypes;
  }

	public String getSource() {
		return (sourceTxt != null) ?
			sourceTxt :
			(statement == null) ?
				"null" :
				statement.getSource();
	}

	public String getUserQueryString(LanguageConnectionContext lcc) {
          return
                  (statement == null) ?
                      (sourceTxt != null) ?
                          sourceTxt  :  "null":
                          lcc != null ?statement.getQueryStringForParse(lcc):statement.getSource();
         }

	public void setSource(String text)
	{
		sourceTxt = text;
	}
// GemStone changes BEGIN

	public List<TypeCompiler>    getOriginalParameterTypeCompilers()     {
          return this.origTypeCompilers;
        }

  public void setStatementType(int stmntType) {
    this.statementType = stmntType;
  }

  public int getStatementType() {
    return this.statementType;
  }

  public final Statement getStatement() {
    return this.statement;
  }

  public void setFlags(boolean atomic, boolean readOnly) {
    this.isAtomic = atomic;
    if (readOnly) {
      this.updateMode = CursorNode.READ_ONLY;
    }
  }
// GemStone changes END

	public final void setSPSName(String name) {
		spsName = name;
	}

	public String getSPSName() {
		return spsName;
	}


	/**
	 * Get the total compile time for the associated query in milliseconds.
	 * Compile time can be divided into parse, bind, optimize and generate times.
	 *
	 * @return long		The total compile time for the associated query in milliseconds.
	 */
	public long getCompileTimeInMillis()
	{
		return compileTime;
	}

	/**
	 * Get the parse time for the associated query in milliseconds.
	 *
	 * @return long		The parse time for the associated query in milliseconds.
	 */
	public long getParseTimeInMillis()
	{
		return parseTime;
	}

	/**
	 * Get the bind time for the associated query in milliseconds.
	 *
	 * @return long		The bind time for the associated query in milliseconds.
	 */
	public long getBindTimeInMillis()
	{
		return bindTime;
	}

	/**
	 * Get the optimize time for the associated query in milliseconds.
	 *
	 * @return long		The optimize time for the associated query in milliseconds.
	 */
	public long getOptimizeTimeInMillis()
	{
		return optimizeTime;
	}

	// GemStone changes BEGIN
        /**
         * Get the routing info time for the associated query in milliseconds.
         *
         * @return long         The query info time for the associated query in milliseconds.
         */
	public long getRoutingInfoTimeInMillis() {
	  return routingInfoTime;
	}
	// GemStone changes END

	/**
	 * Get the generate time for the associated query in milliseconds.
	 *
	 * @return long		The generate time for the associated query in milliseconds.
	 */
	public long getGenerateTimeInMillis()
	{
		return generateTime;
	}

	/**
	 * Get the timestamp for the beginning of compilation
	 *
	 * @return Timestamp	The timestamp for the beginning of compilation.
	 */
	public Timestamp getBeginCompileTimestamp()
	{
		return beginCompileTimestamp;
	}

	/**
	 * Get the timestamp for the end of compilation
	 *
	 * @return Timestamp	The timestamp for the end of compilation.
	 */
	public Timestamp getEndCompileTimestamp()
	{
		return endCompileTimestamp;
	}

	void setCompileTimeWarnings(SQLWarning warnings) {
		this.warnings = warnings;
	}

	public final SQLWarning getCompileTimeWarnings() {
		return warnings;
	}

	/**
	 * Set the compile time for this prepared statement.
	 * @param routingInfoTime TODO
	 * @param compileTime	The compile time
	 */
	public void setCompileTimeMillis(long parseTime, long bindTime,
										long optimizeTime,
										long routingInfoTime,
										long generateTime,
										long compileTime,
										Timestamp beginCompileTimestamp, Timestamp endCompileTimestamp)
	{
		this.parseTime = parseTime;
		this.bindTime = bindTime;
		this.optimizeTime = optimizeTime;
		this.generateTime = generateTime;
		// GemStone changes BEGIN
		this.routingInfoTime = routingInfoTime;
		// GemStone changes END
		this.compileTime = compileTime;
		this.beginCompileTimestamp = beginCompileTimestamp;
		this.endCompileTimestamp = endCompileTimestamp;
		this.numPrepared++;

                if (stats != null) {
                  final boolean isQN = statement.createQueryInfo();
                  stats.incStat(StatementStats.parseTimeId, isQN, parseTime);
                  if (bindTime > 0) {
                    stats.incStat(StatementStats.bindTimeId, isQN, bindTime);
                    stats.incStat(StatementStats.optimizeTimeId, isQN, optimizeTime);
                    stats.incStat(StatementStats.routingInfoTimeId, isQN, routingInfoTime);
                    stats.incStat(StatementStats.generateTimeId, isQN, generateTime);
                  }
                  stats.incStat(StatementStats.totCompilationTimeId, isQN, compileTime);
                  stats.incStat(StatementStats.numCompiledId, isQN, 1);
                }
	}


	/**
		Finish marks a statement as totally unusable.
	 */
	public void finish(LanguageConnectionContext lcc) {

// GemStone changes BEGIN
		final int count = this.inUseCount.decrementAndGet();
		if (this.cacheHolder != null) {
		  // Rahul : should make this statement invalid and call close.
		  return;
		}
		if (count != 0) {
		  return;
		}
		// for subqueries, the statement will be invalidated when parent 
		// statement is invalidated. 
		if (this.isSubqueryPrepStmnt) {
		  return;
		}
		/* (original code)
		synchronized (this) {
			inUseCount--;

			if (cacheHolder != null) {
			  // Rahul : should make this statement invalid and call close.
			  return;
			}

			if (inUseCount != 0) {
				//if (SanityManager.DEBUG) {
				//	if (inUseCount < 0)
				//		SanityManager.THROWASSERT("inUseCount is negative " + inUseCount + " for " + this);
				//}
				return;
			}
		}
		*/
// GemStone changes END

		// invalidate any prepared statements that
		// depended on this statement (including this one)
		// prepareToInvalidate(this, DependencyManager.PREPARED_STATEMENT_INVALID);
		try
		{
			/* NOTE: Since we are non-persistent, we "know" that no exception
			 * will be thrown under us.
			 */
			makeInvalid(DependencyManager.PREPARED_STATEMENT_RELEASE, lcc);
		}
		catch (StandardException se)
		{
		  if (SanityManager.DEBUG)
			{
				SanityManager.THROWASSERT("Unexpected exception", se);
			}
		}
	}

	/**
	 *	Set the Execution constants. This routine is called as we Prepare the
	 *	statement.
	 *
	 *	@param constantAction The big structure enclosing the Execution constants.
	 */
	final void	setConstantAction( ConstantAction constantAction )
	{
		executionConstants = constantAction;
	}


	/**
	 *	Get the Execution constants. This routine is called at Execution time.
	 *
	 *	@return	ConstantAction	The big structure enclosing the Execution constants.
	 */
	public	final ConstantAction	getConstantAction()
	{
		return	executionConstants;
	}

	/**
	 *	Set the saved objects. Called when compilation completes.
	 *
	 *	@param	objects	The objects to save from compilation
	 */
// GemStone changes BEGIN
// changed to public
	public final void	setSavedObjects( Object[] objects )
// GemStone changes END
	{
		savedObjects = objects;
	}

	/**
	 *	Get the specified saved object.
	 *
	 *	@param	objectNum	The object to get.
	 *	@return	the requested saved object.
	 *
	public final Object	getSavedObject(int objectNum)
	{
		if (SanityManager.DEBUG) {
			if (!(objectNum>=0 && objectNum<savedObjects.length))
			SanityManager.THROWASSERT(
				"request for savedObject entry "+objectNum+" invalid; "+
				"savedObjects has "+savedObjects.length+" entries");
		}
		return	savedObjects[objectNum];
	}*/

	/**
	 *	Get the saved objects.
	 *
	 *	@return all the saved objects
	 */
	public	final Object[]	getSavedObjects()
	{
		return	savedObjects;
	}

	//
	// Dependent interface
	//
	/**
		Check that all of the dependent's dependencies are valid.

		@return true if the dependent is currently valid
	 */
	public final boolean isValid() {
// GemStone changes BEGIN
		return (this.isInvalid.get() & IS_INVALID) == 0;
	}

	public final boolean compilingStatement() {
	  return (this.isInvalid.get() & IS_COMPILING) != 0;
	}

	public final void setCompilingStatement(boolean isCompiling) {
	  for (;;) {
	    final int invalidFlags = this.isInvalid.get();
	    if ((invalidFlags & IS_COMPILING) != 0) {
	      if (isCompiling) {
	        return;
	      }
	      else if (this.isInvalid.compareAndSet(invalidFlags,
	          invalidFlags & (~IS_COMPILING))) {
	        return;
	      }
	    }
	    else if (isCompiling) {
	      if (this.isInvalid.compareAndSet(invalidFlags,
	          invalidFlags | IS_COMPILING)) {
	        return;
	      }
	    }
	    else {
	      return;
	    }
	  }
	}
		/* (original code)
		return isValid;
	}
	*/
// GemStone changes END

	/**
	 * set this prepared statement to be valid, currently used by
	 * GenericTriggerExecutor.
	 */
	public void setValid()
	{
// GemStone changes BEGIN
		for (;;) {
		  final int isInvalidFlags = this.isInvalid.get();
		  if ((isInvalidFlags & IS_INVALID) != 0) {
		    if (this.isInvalid.compareAndSet(isInvalidFlags,
		        isInvalidFlags & (~IS_INVALID))) {
		      return;
		    }
		  }
		  else {
		    return;
		  }
		}
		/* (original code)
		isValid = true;
		*/
// GemStone changes END
	}

	/**
	 * Indicate this prepared statement is an SPS action, currently used
	 * by GenericTriggerExecutor.
	 */
	public void setSPSAction()
	{
		spsAction = true;
	}

	/**
		Prepare to mark the dependent as invalid (due to at least one of
		its dependencies being invalid).

		@param	action	The action causing the invalidation
		@param	p		the provider

		@exception StandardException thrown if unable to make it invalid
	 */
	public void prepareToInvalidate(Provider p, int action,
									LanguageConnectionContext lcc)
		throws StandardException {

		/*
			this statement can have other open result sets
			if another one is closing without any problems.

			It is not a problem to create an index when there is an open
			result set, since it doesn't invalidate the access path that was
			chosen for the result set.
		*/
		switch (action) {
		case DependencyManager.CHANGED_CURSOR:
		case DependencyManager.CREATE_INDEX:
			return;
		}

		/* Verify that there are no activations with open result sets
		 * on this prepared statement.
		 */
		lcc.verifyNoOpenResultSets(this, p, action);
	}


	/**
		Mark the dependent as invalid (due to at least one of
		its dependencies being invalid).

		@param	action	The action causing the invalidation

	 	@exception StandardException Standard Derby error policy.
	 */
	public void makeInvalid(int action, LanguageConnectionContext lcc)
		 throws StandardException
	{

// Gemstone changes BEGIN
                if (SanityManager.TraceSingleHop) {
                  SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                      "GenericPreparedStatement::makeInvalid called for GPS: " + this
                          + ", compilingStatement: " + compilingStatement() + " ac: "
                          + getActivationClass() /* + ", ac name is: " + this.getActivationClass().getName() + " statement is: " + this.statement*/);
                }
                synchronized (this) {
                  // first get and set the flags
                  for (;;) {
                    final int invalidFlags = this.isInvalid.get();
                    if ((invalidFlags & IS_COMPILING) == 0) {
                      // make ourselves invalid
                      // block compiles while we are invalidating
                      if (this.isInvalid.compareAndSet(invalidFlags,
                          invalidFlags | (IS_INVALID | IS_COMPILING))) {
                        break;
                      }
                    }
                    else {
                      return;
                    }
                  }
                  this.qinfo = null;
                  this.routingInfoObjects = null;
                  this.activationClass = null;
                }
                /* (original code)
		boolean alreadyInvalid;

		synchronized (this) {

			if (compilingStatement)
				return;

			alreadyInvalid = !isValid;

			// make ourseleves invalid
			isValid = false;

			// block compiles while we are invalidating
			compilingStatement = true;
		}
		*/
// Gemstone changes END

		try {

			DependencyManager dm = lcc.getDataDictionary().getDependencyManager();

			/* Clear out the old dependencies on this statement as we
			 * will build the new set during the reprepare in makeValid().
			 */
			dm.clearDependencies(lcc, this);

			/*
			** If we are invalidating an EXECUTE STATEMENT because of a stale
			** plan, we also need to invalidate the stored prepared statement.
			*/
			if (execStmtName != null) {
				switch (action) {
				case DependencyManager.INTERNAL_RECOMPILE_REQUEST:
				case DependencyManager.CHANGED_CURSOR:
				{
					/*
					** Get the DataDictionary, so we can get the descriptor for
					** the SPP to invalidate it.
					*/
					DataDictionary dd = lcc.getDataDictionary();

					SchemaDescriptor sd = dd.getSchemaDescriptor(execSchemaName, lcc.getTransactionCompile(), true);
					SPSDescriptor spsd = dd.getSPSDescriptor(execStmtName, sd);
					spsd.makeInvalid(action, lcc);
					break;
				}
				}
			}

		} finally {
			synchronized (this) {
// GemStone changes BEGIN
				setCompilingStatement(false);
				/* (original code)
				compilingStatement = false;
				*/
// GemStone changes END
				notifyAll();
			}
		}
	}

	/**
	 * Is this dependent persistent?  A stored dependency will be required
	 * if both the dependent and provider are persistent.
	 *
	 * @return boolean		Whether or not this dependent is persistent.
	 */
	public boolean isDescriptorPersistent()
	{
		/* Non-stored prepared statements are not persistent */
		return false;
	}

	//
	// Dependable interface
	//

	/**
		@return the stored form of this Dependable

		@see Dependable#getDependableFinder
	 */
	public DependableFinder getDependableFinder()
	{
	    return null;
	}

	/**
	 * Return the name of this Dependable.  (Useful for errors.)
	 *
	 * @return String	The name of this Dependable..
	 */
	public String getObjectName()
	{
		return UUIDString;
	}

	/**
	 * Get the Dependable's UUID String.
	 *
	 * @return String	The Dependable's UUID String.
	 */
	public UUID getObjectID()
	{
		return UUIDValue;
	}

	/**
	 * Get the Dependable's class type.
	 *
	 * @return String		Classname that this Dependable belongs to.
	 */
	public String getClassType()
	{
		return Dependable.PREPARED_STATEMENT;
	}

	/**
	 * Return true if the query node for this statement references SESSION schema
	 * tables/views.
	 * This method gets called at the very beginning of the compile phase of any statement.
	 * If the statement which needs to be compiled is already found in cache, then there is
	 * no need to compile it again except the case when the statement is referencing SESSION
	 * schema objects. There is a small window where such a statement might get cached
	 * temporarily (a statement referencing SESSION schema object will be removed from the
	 * cache after the bind phase is over because that is when we know for sure that the
	 * statement is referencing SESSION schema objects.)
	 *
	 * @return	true if references SESSION schema tables, else false
	 */
	public boolean referencesSessionSchema()
	{
		return referencesSessionSchema;
	}

	/**
	 * Return true if the QueryTreeNode references SESSION schema tables/views.
	 * The return value is also saved in the local field because it will be
	 * used by referencesSessionSchema() method.
	 * This method gets called when the statement is not found in cache and
	 * hence it is getting compiled.
	 * At the beginning of compilation for any statement, first we check if
	 * the statement's plan already exist in the cache. If not, then we add
	 * the statement to the cache and continue with the parsing and binding.
	 * At the end of the binding, this method gets called to see if the
	 * QueryTreeNode references a SESSION schema object. If it does, then
	 * we want to remove it from the cache, since any statements referencing
	 * SESSION schema objects should never get cached.
	 *
	 * @return	true if references SESSION schema tables/views, else false
	 */
	public boolean referencesSessionSchema(StatementNode qt)
	throws StandardException {
		//If the query references a SESSION schema table (temporary or permanent), then
		// mark so in this statement
		referencesSessionSchema = qt.referencesSessionSchema();
		return(referencesSessionSchema);
	}

	//
	// class interface
	//

	/**
		Makes the prepared statement valid, assigning
		values for its query tree, generated class,
		and associated information.

		@param qt the query tree for this statement

		@exception StandardException thrown on failure.
	 */
	void completeCompile(StatementNode qt)
						throws StandardException {
		//if (finished)
		//	throw StandardException.newException(SQLState.LANG_STATEMENT_CLOSED, "completeCompile()");

		paramTypeDescriptors = qt.getParameterTypes();
		//GemStone changes BEGIN
		this.origTypeCompilers= qt.getOriginalParameterTypeCompilers();
		//GemStone changes END
		// erase cursor info in case statement text changed
		if (targetTable!=null) {
			targetTable = null;
			updateMode = 0;
			updateColumns = null;
			targetColumns = null;
		}

		// get the result description (null for non-cursor statements)
		// would we want to reuse an old resultDesc?
		// or do we need to always replace in case this was select *?
		resultDesc = qt.makeResultDescription();

		// would look at resultDesc.getStatementType() but it
		// doesn't call out cursors as such, so we check
		// the root node type instead.

		if (resultDesc != null)
		{
			/*
				For cursors, we carry around some extra information.
			 */
			CursorInfo cursorInfo = (CursorInfo)qt.getCursorInfo();
			if (cursorInfo != null)
			{
				targetTable = cursorInfo.targetTable;
				targetColumns = cursorInfo.targetColumns;
				updateColumns = cursorInfo.updateColumns;
				updateMode = cursorInfo.updateMode;
			}
		}
// GemStone changes BEGIN
		makeValid();
		/* (original code)
		isValid = true;

		return;
		*/
// GemStone changes END
	}
// GemStone changes BEGIN

  void makeValid() {
    for (;;) {
      final int invalidFlags = this.isInvalid.get();
      if ((invalidFlags & IS_INVALID) != 0) {
        if (this.isInvalid.compareAndSet(invalidFlags,
            invalidFlags & (~IS_INVALID))) {
          return;
        }
      }
      else {
        return;
      }
    }
  }
// GemStone changes END

	public GeneratedClass getActivationClass()
		throws StandardException
	{
		return activationClass;
	}

	void setActivationClass(GeneratedClass ac)
	{
		activationClass = ac;
// GemStone changes BEGIN
		// set the flag for upToDate()
		for (;;) {
		  final int invalidFlags = this.isInvalid.get();
		  if ((invalidFlags & IS_ACTIVATION_CLASS_NULL) != 0) {
		    if (ac != null) {
		      if (this.isInvalid.compareAndSet(invalidFlags,
		          invalidFlags & (~IS_ACTIVATION_CLASS_NULL))) {
		        break;
		      }
		    }
		    else {
		      break;
		    }
		  }
		  else if (ac != null) {
		    break;
		  }
		  else {
		    if (this.isInvalid.compareAndSet(invalidFlags,
		        invalidFlags | IS_ACTIVATION_CLASS_NULL)) {
		      break;
		    }
		  }
		}
// GemStone changes END
	}

	//
	// ExecPreparedStatement
	//

	/**
	 * the update mode of the cursor
	 *
	 * @return	The update mode of the cursor
	 */
	public int	getUpdateMode() {
		return updateMode;
	}

	/**
	 * the target table of the cursor
	 *
	 * @return	target table of the cursor
	 */
	public ExecCursorTableReference getTargetTable()
	{
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(targetTable!=null, "Not a cursor, no target table");
		}
		return targetTable;
	}

	/**
	 * the target columns of the cursor as a result column list
	 *
	 * @return	target columns of the cursor as a result column list
	 */
	public ResultColumnDescriptor[]	getTargetColumns() {
		return targetColumns;
	}

	/**
	 * the update columns of the cursor as a update column list
	 *
	 * @return	update columns of the cursor as a array of strings
	 */
	public String[]	getUpdateColumns()
	{
		return updateColumns;
	}

	/**
	 * Return the cursor info in a single chunk.  Used
	 * by StrorablePreparedStatement
	 */
	public Object getCursorInfo()
	{
		return new CursorInfo(
			updateMode,
			targetTable,
			targetColumns,
			updateColumns);
	}

	void setCursorInfo(CursorInfo cursorInfo)
	{
		if (cursorInfo != null)
		{
			updateMode = cursorInfo.updateMode;
			targetTable = cursorInfo.targetTable;
			targetColumns = cursorInfo.targetColumns;
			updateColumns = cursorInfo.updateColumns;
		}
	}


	//
	// class implementation
	//

	/**
	 * Get the byte code saver for this statement.
	 * Overridden for StorablePreparedStatement.  We
	 * don't want to save anything
	 *
	 * @return a byte code saver (null for us)
	 */
	ByteArray getByteCodeSaver()
	{
		return null;
	}

	/**
	 * Does this statement need a savepoint?
	 *
	 * @return true if this statement needs a savepoint.
	 */
	public boolean needsSavepoint()
	{
		return needsSavepoint;
	}

	/**
	 * Set the stmts 'needsSavepoint' state.  Used
	 * by an SPS to convey whether the underlying stmt
	 * needs a savepoint or not.
	 *
	 * @param needsSavepoint true if this statement needs a savepoint.
	 */
	void setNeedsSavepoint(boolean needsSavepoint)
	{
	 	this.needsSavepoint = needsSavepoint;
	}

	/**
	 * Set the stmts 'isAtomic' state.
	 *
	 * @param isAtomic true if this statement must be atomic
	 * (i.e. it is not ok to do a commit/rollback in the middle)
	 */
	void setIsAtomic(boolean isAtomic)
	{
	 	this.isAtomic = isAtomic;
	}

	/**
	 * Returns whether or not this Statement requires should
	 * behave atomically -- i.e. whether a user is permitted
	 * to do a commit/rollback during the execution of this
	 * statement.
	 *
	 * @return boolean	Whether or not this Statement is atomic
	 */
	public boolean isAtomic()
	{
		return isAtomic;
	}

	/**
	 * Set the name of the statement and schema for an "execute statement"
	 * command.
	 */
	void setExecuteStatementNameAndSchema(String execStmtName,
												 String execSchemaName)
	{
		this.execStmtName = execStmtName;
		this.execSchemaName = execSchemaName;
	}

	/**
	 * Get a new prepared statement that is a shallow copy
	 * of the current one.
	 *
	 * @return a new prepared statement
	 *
	 * @exception StandardException on error
	 */
	public ExecPreparedStatement getClone() throws StandardException
	{

		GenericPreparedStatement clone = new GenericPreparedStatement(statement);

		clone.activationClass = getActivationClass();
		clone.resultDesc = resultDesc;
		clone.paramTypeDescriptors = paramTypeDescriptors;
		clone.dynamicTokenList = dynamicTokenList;

		clone.executionConstants = executionConstants;
		clone.UUIDString = UUIDString;
		clone.UUIDValue = UUIDValue;
		clone.savedObjects = savedObjects;
		clone.execStmtName = execStmtName;
		clone.execSchemaName = execSchemaName;
		clone.isAtomic = isAtomic;
		clone.sourceTxt = sourceTxt;
		clone.statementType = statementType;
		clone.targetTable = targetTable;
		clone.targetColumns = targetColumns;
		clone.updateColumns = updateColumns;
		clone.updateMode = updateMode;
		clone.needsSavepoint = needsSavepoint;
		clone.useOnlyPrimaryBuckets = useOnlyPrimaryBuckets;
// GemStone changes BEGIN
		clone.queryHDFS = this.queryHDFS;
		clone.isCallableStatement = this.isCallableStatement;
		clone.origTypeCompilers = this.origTypeCompilers;
		clone.stats = stats;
		clone.qinfo = qinfo;
		if (clone.activationClass == null) {
		  clone.isInvalid.set(IS_ACTIVATION_CLASS_NULL);
		}
// GemStone changes END

		return clone;
	}

	// cache holder stuff.
	public void setCacheHolder(Cacheable cacheHolder) {

		this.cacheHolder = cacheHolder;

		if (cacheHolder == null) {

			// need to invalidate the statement
// GemStone changes BEGIN
			if (!isValid() || this.inUseCount.get() != 0)
			/* (original code)
			if (!isValid || (inUseCount != 0))
			*/
// GemStone changes END
				return;

			ContextManager cm = ContextService.getFactory().getCurrentContextManager();
			LanguageConnectionContext lcc =
				(LanguageConnectionContext)
				(cm.getContext(LanguageConnectionContext.CONTEXT_ID));

			// invalidate any prepared statements that
			// depended on this statement (including this one)
			// prepareToInvalidate(this, DependencyManager.PREPARED_STATEMENT_INVALID);
			try
			{
				/* NOTE: Since we are non-persistent, we "know" that no exception
				 * will be thrown under us.
				 */
				makeInvalid(DependencyManager.PREPARED_STATEMENT_RELEASE, lcc);
				// GemStone changes BEGIN
				GfxdManagementService.handleEvent(GfxdResourceEvent.STATEMENT__INVALIDATE, this.statement);
				// GemStone changes END
			}
			catch (StandardException se)
			{
				if (SanityManager.DEBUG)
				{
					SanityManager.THROWASSERT("Unexpected exception", se);
				}
			}
		}
	}

	public String toString() {
		return getObjectName();
	}

	public boolean isStorable() {
		return false;
	}

	public final void setRequiredPermissionsList( List requiredPermissionsList)
	{
		this.requiredPermissionsList = requiredPermissionsList;
	}

	public final List getRequiredPermissionsList()
	{
		return requiredPermissionsList;
	}

	public final long getVersionCounter() {
	    return versionCounter;
	}
	public final void incrementVersionCounter() {
	    ++versionCounter;
	}

// GemStone changes BEGIN
        public final  boolean createQueryInfo() {
          return ((GenericStatement)this.statement).createQueryInfo();
        }

        public final void setLocalRegion(LocalRegion rgn) {
          this.localRegion = rgn;
        }

        public final LocalRegion getLocalRegion() {
          return this.localRegion;
        }

        public final PartitionedRegion getPartitionedRegion() {
          if (this.partitionedRegionForSingleHop == null) {
            this.partitionedRegionForSingleHop = (PartitionedRegion)((this.qinfo != null && this.qinfo
                .getRegion() instanceof PartitionedRegion) ? this.qinfo.getRegion()
                : null);
          }
          return this.partitionedRegionForSingleHop;
        }

        public final boolean isSubqueryPrepStatement() {
          return this.isSubqueryPrepStmnt;
        }

        public final boolean isInsertAsSubselect() {
          return this.isInsertAsSubselect;
        }

        public final void setInsertAsSubselect(boolean flag) {
          this.isInsertAsSubselect = flag;
        }



        final void setProviderList(ProviderList apl) {
          this.dependencyProviders = apl;
        }

        @Override
        public final StatementStats getStatementStats() {
          return stats;
        }

        @Override
        public final void setStatementStats(StatementStats st) {
          if (GemFireXDUtils.TraceStatsGeneration) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_STATS_GENERATION,
                "GenericPreparedStatement#setStatementStats setting statistics object to "
                    + st);
          }
          this.stats = st;
        }

        public SingleHopInformation fillAndGetSingleHopInformation() {
          String table = null;
          GfxdPartitionResolver resolver = null;
          AbstractRegion region = null;
          if (this.routingInfoObjects != null) {
            String schemaName = this.qinfo.getSchemaName();
            String tableName = this.qinfo.getTableName();
            if (schemaName == null || tableName == null) {
              SingleHopInformation shifn = new SingleHopInformation("");
              shifn.setResolverType(SingleHopInformation.NO_SINGLE_HOP_CASE);
              return shifn;
            }
            table = schemaName + "." + tableName;
            region = this.qinfo.getRegion();
            if (region != null) {
              if (region.getDataPolicy().withPartitioning()) {
                resolver = (GfxdPartitionResolver)((PartitionedRegion)this.qinfo
                    .getRegion()).getPartitionResolver();
              }
            }
          }
          SingleHopInformation shifn = new SingleHopInformation(table);
          if (this.routingInfoObjects == null) {
            shifn.setResolverType(SingleHopInformation.NO_SINGLE_HOP_CASE);
            return shifn;
          }

          if (this.qinfo.isQuery(QueryInfo.HAS_DISTINCT, QueryInfo.HAS_DISTINCT_SCAN,
              QueryInfo.HAS_GROUPBY, QueryInfo.HAS_ORDERBY,
              QueryInfo.EMBED_GFE_RESULTSET)
              || this.qinfo.isOuterJoinSpecialCase()) {
            shifn.setHasAggregate();
          }

          if (region != null && this.qinfo != null && this.qinfo.isDelete()) {
            final GfxdIndexManager sqlIM = (GfxdIndexManager)((LocalRegion)region)
                .getIndexUpdater();
            // fks can be null probably even if sqlim is refreshing its fks list
            // as there is no lock on the table at this point of time. so confirm this.
            // [sumedh] fks is set to either older value or the newer one,
            // never null if it does not need to be, so lock is not required
            // just for that
            if (sqlIM.getFKS() != null) {
              shifn.setResolverType(SingleHopInformation.NO_SINGLE_HOP_CASE);
              return shifn;
            }
          }
          this.partitionedRegionForSingleHop = (PartitionedRegion)region;
          if (resolver != null) {
            try {
              resolver.setResolverInfoInSingleHopInfoObject(shifn);
              if (this.partitionedRegionForSingleHop.isHDFSReadWriteRegion()) {
                shifn.setHdfsRegionCase();
              }
            } catch (StandardException e) {
              // TODO KN do proper exception handling
              throw new GemFireXDRuntimeException(e);
            }
          }
          else {
            shifn.setResolverType(SingleHopInformation.NO_SINGLE_HOP_CASE);
            return shifn;
          }
          shifn.setRoutingObjectInfoSet(this.routingInfoObjects);
          return shifn;
        }

        public void setRoutingObjects(
            Set robjsRoutingInfoObjects) {
          this.routingInfoObjects = robjsRoutingInfoObjects;

        }

        public QueryInfo getQueryInfo() {
          return this.qinfo;
        }

        public void setQueryInfoObject(QueryInfo qi) {
          this.qinfo = qi;
        }

        public void setPartitionedRegion(String regionPath) {
          this.partitionedRegionForSingleHop = (PartitionedRegion)Misc
              .getRegionForTable(regionPath, false);
        }

        public void incSingleHopStats(LanguageConnectionContext lcc) {
          if (lcc != null) {
            if (lcc.statsEnabled()) {
              stats.incStat(StatementStats.dn_numSingleHopExecutions, false, 1);
            }
          }
        }

        public void setParentPS(GenericPreparedStatement gps) {
          this.parentPS = gps;
          if (gps != null) {
            if (gps.childPS == null) {
              gps.childPS = new ArrayList<GenericPreparedStatement>();
            }
            gps.childPS.add(this);
          }
        }

        public GenericPreparedStatement getParentPS() {
          return this.parentPS;
        }

        public void setUseOnlyPrimaryBuckets(boolean b) {
          this.useOnlyPrimaryBuckets = true;
        }

        public void setQueryHDFS(boolean val) {
          this.queryHDFS = val;
        }

        public void setHasQueryHDFS(boolean val) {
          this.hasQueryHDFS = val;
        }        
        
        public void setPutDML(boolean val) {
          this.isPutDML = val;
        }

        public boolean isPutDML() {
          return this.isPutDML;
        }
        
        public boolean hasQueryHDFS() {
          return hasQueryHDFS;
        }

        public boolean getQueryHDFS() {
          return queryHDFS;
        }

        public void setIsCallableStatement(boolean v) {
          this.isCallableStatement = v;
        }

        public boolean isCallableStatement() {
          return this.isCallableStatement;
        }

        public void setDynamicTokenList(ArrayList<com.pivotal.gemfirexd.internal.impl.sql.compile.Token> tokenList) {
          this.dynamicTokenList = tokenList;
        }

        public ArrayList<com.pivotal.gemfirexd.internal.impl.sql.compile.Token> getDynamicTokenList() {
          return this.dynamicTokenList;
        }
// GemStone changes END

}
