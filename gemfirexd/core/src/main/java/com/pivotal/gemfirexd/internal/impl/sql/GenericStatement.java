/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.GenericStatement

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


import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.InsertQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfoContext;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SubQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.management.GfxdResourceEvent;
import com.pivotal.gemfirexd.internal.engine.reflect.GemFireActivationClass;
import com.pivotal.gemfirexd.internal.engine.reflect.SnappyActivationClass;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.sql.execute.SnappyActivation;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedClass;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.services.stream.HeaderPrintWriter;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.Statement;
import com.pivotal.gemfirexd.internal.iapi.sql.StatementType;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.C_NodeTypes;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Parser;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.ConnectionUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.ProviderList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.*;
import com.pivotal.gemfirexd.internal.impl.sql.conn.GenericLanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.rules.ExecutionEngineArbiter;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.AssertFailure;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.pivotal.gemfirexd.internal.impl.sql.rules.ExecutionEngineRule.ExecutionEngine;

//GemStone changes BEGIN


public class GenericStatement
	implements Statement {

	// these fields define the identity of the statement
	protected SchemaDescriptor compilationSchema;
	protected String			statementText;
        //protected final boolean isForReadOnly;
	protected int                      prepareIsolationLevel;
	protected GenericPreparedStatement preparedStmt;
// GemStone changes BEGIN
       // protected boolean        createQueryInfo;
        //final private boolean  needGfxdSubactivation;
        //protected final boolean isPreparedStatement;
        protected int hash;
        protected int stmtHash;
        protected short execFlags;
        public final static short IS_READ_ONLY = 0x01;
        public final static short CREATE_QUERY_INFO = 0x02;
        public final static short GFXD_SUBACTIVATION_NEEDED = 0x04;
        public final static short DISALLOW_SUBQUERY_FLATTENING = 0x08;
        public final static short IS_PREP_STMT = 0x10;
        public final static short DISALLOW_OR_LIST_OPTIMIZATION = 0x20;
        public final static short ALL_TABLES_ARE_REPLICATED_ON_REMOTE = 0x40;
        public final static short IS_OPTIMIZED_STMT = 0x80;
        public final static short QUERY_HDFS = 0x100;
        public final static short IS_CALLABLE_STATEMENT = 0x200;
        public final static short ROUTE_QUERY = 0x400;

        private static final Pattern SKIP_CANCEL_STMTS = Pattern.compile(
            "^\\s*\\{?\\s*(((DROP|TRUNCATE)\\s+(TABLE|INDEX))|(DELETE\\s+FROM))\\s+",
            Pattern.CASE_INSENSITIVE);
        private static final Pattern ignoreStmts = Pattern.compile(
            ("\\s.*(\"SYSSTAT\"|SYS.\")"), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        //private ProcedureProxy procProxy;
        private final GfxdHeapThresholdListener thresholdListener;
        private THashMap ncjMetaData = null;
        private static final String MISC_DDL_PREFIX =
            "(STREAMING|DEPLOY|UNDEPLOY|CACHE|UNCACHE|REFRESH|RESET)";
        private static final String TABLE_DML_SELECT_PATTERN =
            "((((INSERT|PUT)\\s+INTO)|(DELETE\\s+FROM))\\s+(TABLE)?.*\\s+SELECT)";
	      private static final String CREATE_OR_DROP_PATTERN = "(FUNCTION|POLICY)";
	      private static final String ALTER_TABLE_PREFIX = "ALTER\\s+(TABLE)?\\s+.*\\s+";
	      private static final String ALTER_TABLE_COMMANDS = "(ADD|DROP|ENABLE|DISABLE)";

	      // final patterns combining the above patterns and also adding EXPLAIN
	      private static final String ROUTED_QUERY_BASE_PATTERN =
            MISC_DDL_PREFIX + '|' + TABLE_DML_SELECT_PATTERN +
                "|((CREATE|DROP)\\s+" + CREATE_OR_DROP_PATTERN +
                ")|(" + ALTER_TABLE_PREFIX + ALTER_TABLE_COMMANDS + ')';
	      private static final String ROUTED_QUERY_PREFIX = "^\\s*(EXPLAIN\\s+)?";
	      private static final Pattern ROUTED_QUERY_PATTERN =
            Pattern.compile(ROUTED_QUERY_PREFIX + '(' + ROUTED_QUERY_BASE_PATTERN + ")\\s+",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	      // the pattern below is to be excluded from routing
	      private static final Pattern ALTER_TABLE_CONSTRAINTS =
            Pattern.compile(ROUTED_QUERY_PREFIX + ALTER_TABLE_PREFIX +
                "(ADD|DROP)\\s+CONSTRAINT\\s+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern DML_TABLE_PATTERN =
            Pattern.compile("^\\s*(INSERT|UPDATE|DELETE|PUT)\\s+",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern NON_ROUTED_QUERY =
            Pattern.compile(ROUTED_QUERY_PREFIX + "\\{?\\s*(CALL|EXECUTE)\\s+",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	      private static ExecutionEngineArbiter engineArbiter = new ExecutionEngineArbiter();
// GemStone changes END
	/**
	 * Constructor for a Statement given the text of the statement in a String
	 * @param compilationSchema schema
	 * @param statementText	The text of the statement
	 */
	public GenericStatement(SchemaDescriptor compilationSchema, String statementText,
	    short execFlags, THashMap ncjMetaData)
	{
		this.compilationSchema = compilationSchema;
		this.statementText = statementText;
		//this.isForReadOnly = isForReadOnly;
// GemStone changes BEGIN
                this.execFlags = execFlags;
                //this.needGfxdSubactivation = needGfxdSubactivation;
                //this.isPreparedStatement = isPreparedStatement;
                int h = getHashCode(0, this.statementText.length(),  0);
                this.stmtHash = h;
                h = ResolverUtils.addIntToHash(createQueryInfo() ? 1231 : 1237, h);
                h = ResolverUtils.addIntToHash(isPreparedStatement()
                    || !isOptimizedStatement()? 19531 : 20161, h);
                h = ResolverUtils.addIntToHash(getRouteQuery() ? 22409 : 22433, h);
                h = ResolverUtils.addIntToHash(getQueryHDFS() ? 999599 : 999983, h);
                this.hash = h;
                final GemFireStore store = GemFireStore.getBootingInstance();
                if (store != null) {
                  this.thresholdListener = store.thresholdListener();
                }
                else {
                  // can happen during initial registration
                  this.thresholdListener = null;
                }
                this.ncjMetaData = ncjMetaData;
// GemStone changes END
	}

	/*
	 * Statement interface
	 */


	/* RESOLVE: may need error checking, debugging code here */
	@Override
  public PreparedStatement prepare(LanguageConnectionContext lcc) throws StandardException
	{
		/*
		** Note: don't reset state since this might be
		** a recompilation of an already prepared statement.
		*/
		return prepMinion(lcc, true, (Object[]) null, (SchemaDescriptor) null, false);
	}
	@Override
  public final PreparedStatement prepare(final LanguageConnectionContext lcc, final boolean forMetaData) throws StandardException
	{
		/*
		** Note: don't reset state since this might be
		** a recompilation of an already prepared statement.
		*/
		return prepMinion(lcc, true, (Object[]) null, (SchemaDescriptor) null, forMetaData);
	}

	// GemStone changes BEGIN
	private GenericPreparedStatement getPreparedStatementForSnappy(boolean commitNestedTransaction,
			StatementContext statementContext, LanguageConnectionContext lcc, boolean isDDL,
			boolean checkCancellation, boolean isUpdateOrDelete) throws StandardException {
      GenericPreparedStatement gps = preparedStmt;
      GeneratedClass ac = new SnappyActivationClass(lcc, !isDDL, isPreparedStatement() && !isDDL,
          isUpdateOrDelete);
      gps.setActivationClass(ac);
      gps.incrementVersionCounter();
      gps.makeValid();
      if (commitNestedTransaction) {
        lcc.commitNestedTransaction();
      }
      if (statementContext != null) {
        lcc.popStatementContext(statementContext, null);
      }

     if (GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "GenericStatement.getPreparedStatementForSnappy: Created SnappyActivation for sql: " +
              this.getSource() + " ,isDDL=" + isDDL + " ,isUpdateOrDelete=" + isUpdateOrDelete);
	 }
     if (checkCancellation) {
       Misc.checkMemory(thresholdListener, statementText, -1);
     }
     return gps;
  }

	// GemStone changes END
	@SuppressFBWarnings(value="ML_SYNC_ON_FIELD_TO_GUARD_CHANGING_THAT_FIELD")
        private final PreparedStatement prepMinion(
            final LanguageConnectionContext lcc, final boolean cacheMe,
            final Object[] paramDefaults, final SchemaDescriptor spsSchema,
            final boolean internalSQL)
            throws StandardException {

		long				beginTime = 0;
		long				parseTime = 0;
		long				bindTime = 0;
		long				optimizeTime = 0;
                long                            routingInfoTime = 0;
		long				generateTime = 0;
		Timestamp			beginTimestamp = null;
		Timestamp			endTimestamp = null;
		StatementContext	statementContext = null;
// GemStone changes BEGIN
    final boolean routeQuery = getRouteQuery();

		GeneratedClass ac = null;
                QueryInfo qinfo = null;
                boolean createGFEPrepStmt = false;
                final GemFireXDQueryObserver observer =
                  GemFireXDQueryObserverHolder.getInstance();
                ProviderList prevAPL = null;

                boolean foundStats = false;
                if (GemFireXDUtils.TraceExecution) {
                  String sql = GemFireXDUtils.maskCreateUserPasswordFromSQLString(
                      this.statementText);
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                      "GS#prepMinion: preparing "
                          + (sql != null ? sql : this.statementText));
                }
// GemStone changes END
		// verify it isn't already prepared...
		// if it is, and is valid, simply return that tree.
		// if it is invalid, we will recompile now.
		if (preparedStmt != null) {
// GemStone changes BEGIN

                  if (preparedStmt.upToDate()) {
                    foundStats = fetchStatementStats(lcc, null);
                    assert foundStats == true : "prepMinon: expected the stats to be present from other prep statement";
                    return preparedStmt;
                  }
		}
		// Clear the optimizer trace from the last statement
		if (lcc.getOptimizerTrace() || lcc.explainConnection())
                /* (original code)
                      if (preparedStmt.upToDate())
                        return preparedStmt;
		}

		// Clear the optimizer trace from the last statement
		if (lcc.getOptimizerTrace())
		*/
// GemStone changes END
			lcc.setOptimizerTraceOutput(getSource() + "\n");

		beginTime = getCurrentTimeMillis(lcc);
		/* beginTimestamp only meaningful if beginTime is meaningful.
		 * beginTime is meaningful if STATISTICS TIMING is ON.
		 */
		if (beginTime != 0)
		{
			beginTimestamp = new Timestamp(beginTime);
		}

		/** set the prepare Isolaton from the LanguageConnectionContext now as
		 * we need to consider it in caching decisions
		 */
		prepareIsolationLevel = lcc.getPrepareIsolationLevel();

		/* a note on statement caching:
		 *
		 * A GenericPreparedStatement (GPS) is only added it to the cache if the
		 * parameter cacheMe is set to TRUE when the GPS is created.
		 *
		 * Earlier only CacheStatement (CS) looked in the statement cache for a
		 * prepared statement when prepare was called. Now the functionality
		 * of CS has been folded into GenericStatement (GS). So we search the
		 * cache for an existing PreparedStatement only when cacheMe is TRUE.
		 * i.e if the user calls prepare with cacheMe set to TRUE:
		 * then we
		 *         a) look for the prepared statement in the cache.
		 *         b) add the prepared statement to the cache.
		 *
		 * In cases where the statement cache has been disabled (by setting the
		 * relevant Derby property) then the value of cacheMe is irrelevant.
		 */
		boolean foundInCache = false;
		final boolean isSnappyStore = Misc.getMemStore().isSnappyStore();
		if (preparedStmt == null)
		{
                        boolean isRemoteDDLAndSnappyStore =  isSnappyStore && lcc.isConnectionForRemoteDDL() && !routeQuery;
                        if (cacheMe && !isRemoteDDLAndSnappyStore) {
                                preparedStmt = (GenericPreparedStatement)((GenericLanguageConnectionContext)lcc).lookupStatement(this);
                        }
      
			if (preparedStmt == null)
			{
				preparedStmt = new GenericPreparedStatement(this);
			}
			else
			{
				foundInCache = true;
			}
		}
//                  cache1stLookUpTime = getCurrentTimeMillis(lcc);

// GemStone changes BEGIN
    // Acquire the read lock on DD before trying to wait for prepared statement
    // compilation. The reason is that write lock on DD may already have been
    // acquired on the query node executing a DDL and one of the execution may
    // be due to DDL distribution. So one thread may do:
    // DD write -> preparedStmt wait; while other thread may do (earlier code):
    // DD read -> preparedStmt notifyAll. Now preparedStmt.compilingStatement
    // will never be true for the former case.
    DataDictionary dataDictionary = lcc.getDataDictionary();

    int ddMode = 0;
    boolean ddLockAcquired = false;
    int additionalDDLocks = 0;
    // don't cancel DROP/TRUNCATE TABLE/INDEX /DELETE statements
    // before querytree is available set the flag based on pattern matching
    // later when qt is available set it using nodetype
    // also don't cancel meta-data queries (if OOME happens node will stop)
    boolean checkCancellation = !(Misc.SNAPPY_HIVE_METASTORE.equals(
        lcc.getCurrentSchemaName()) || SKIP_CANCEL_STMTS.matcher(statementText).find());
    try {
     outer: while(true) {
      ddMode = (dataDictionary != null ? dataDictionary.startReading(lcc) : 0);
      ddLockAcquired = true;

// GemStone changes END
		// if anyone else also has this prepared statement,
		// we don't want them trying to compile with it while
		// we are.  So, we synchronize on it and re-check
		// its validity first.
		// this is a no-op if and until there is a central
		// cache of prepared statement objects...
		synchronized (preparedStmt)
		{

			for (;;) {

				if (foundInCache) {
					if (preparedStmt.referencesSessionSchema()) {
						// cannot use this state since it is private to a connection.
						// switch to a new statement.
						foundInCache = false;
						preparedStmt = new GenericPreparedStatement(this);
						break;
					}
				}

				// did it get updated while we waited for the lock on it?
				if (preparedStmt.upToDate()) {
				  // GemStone changes BEGIN
                                   foundStats = fetchStatementStats(lcc, null);
                                   assert foundStats: "prepMinion: expected " +
				       "the stats to be present after waiting for compilation.";
                                  // GemStone changes END
				     return preparedStmt;
				}

				if (!preparedStmt.compilingStatement()) {
					break;
				}

				try {

// GemStone changes BEGIN
				       //Fix for 42569. If one of the thread is going for reprepare,
				       //other threads would be waiting here. If these threads go into
				       //wait without releasing the read lock on data dictionary, the
				       // repreparing thread would not proceed
				       //Release the data dictionary read lock before going into wait
				        if (dataDictionary != null && ddLockAcquired) {
				          dataDictionary.doneReading(ddMode, lcc);
				          ddLockAcquired = false;
					  // check for any more remaining DD locks
					  TransactionController tc = lcc.getTransactionExecute();
					  while (dataDictionary.unlockAfterReading(tc)) {
					    additionalDDLocks++;
					  }
				        }
// GemStone changes END
					preparedStmt.wait();
// GemStone changes BEGIN
					while (additionalDDLocks > 0) {
					  dataDictionary.lockForReading(
					      lcc.getTransactionExecute());
					  additionalDDLocks--;
					}
// GemStone changes END
					continue outer;
				} catch (final InterruptedException ie) {
					throw StandardException.interrupt(ie);
				}
			}

			preparedStmt.setCompilingStatement(true);
			preparedStmt.setActivationClass(null);
                        // GemStone changes BEGIN

			break;
                        // GemStone changes END
		}
      }


		try {
			final HeaderPrintWriter istream = lcc.getLogStatementText() ? Monitor.getStream() : null;

			/*
			** For stored prepared statements, we want all
			** errors, etc in the context of the underlying
			** EXECUTE STATEMENT statement, so don't push/pop
			** another statement context unless we don't have
			** one.  We won't have one if it is an internal
			** SPS (e.g. jdbcmetadata).
			*/
			if (!preparedStmt.isStorable() || lcc.getStatementDepth() == 0)
			{
				// since this is for compilation only, set atomic
				// param to true and timeout param to 0
				statementContext = lcc.pushStatementContext(true, isForReadOnly(), getSource(),
                                                            null, false, 0L);
			}



			/*
			** RESOLVE: we may ultimately wish to pass in
			** whether we are a jdbc metadata query or not to
			** get the CompilerContext to make the createDependency()
			** call a noop.
			*/
			final CompilerContext cc = lcc.pushCompilerContext(compilationSchema, true);
// GemStone changes BEGIN
                       //reset queryHDFS to default value: false
			cc.setHasQueryHDFS(false);
			cc.setQueryHDFS(false);
			cc.setOriginalExecFlags(this.execFlags);
			if (!createQueryInfo()) {
			  cc.disableQueryInfoCreation();
			}
                        if (GemFireXDUtils.TraceActivation) {
                          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
                              "GenericStatement::prepMinion: "
                                  + "subquery flattening allowed = "
                                  + subqueryFlatteningAllowed()
                                  + ", GfxdSubActivation needed = "
                                  + this.needGfxdSubactivation() + " for query" + " = "
                                  + this.statementText);
                        }
			cc.setSubqueryFlatteningFlag(subqueryFlatteningAllowed());
	                cc.setAllTablesAreReplicatedOnRemote(allTablesAreReplicatedOnRemote());
	                cc.setNCJoinOnRemote(this.ncjMetaData);
                        if (SanityManager.DEBUG) {
                          if (GemFireXDUtils.TraceNCJ) {
                            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
                                "prepMinion - " + "Setting ncjMetaData on remote to "
                                    + this.ncjMetaData);
                          }
                        }
// GemStone changes END
			if (prepareIsolationLevel !=
				ExecutionContext.UNSPECIFIED_ISOLATION_LEVEL)
			{
				cc.setScanIsolationLevel(prepareIsolationLevel);
			}


			// Look for stored statements that are in a system schema
			// and with a match compilation schema. If so, allow them
			// to compile using internal SQL constructs.

			if (internalSQL ||
				(spsSchema != null) && (spsSchema.isSystemSchema()) &&
					(spsSchema.equals(compilationSchema))) {
						cc.setReliability(CompilerContext.INTERNAL_SQL_LEGAL);
			}

			try
			{
				// Statement logging if lcc.getLogStatementText() is true
				if (istream != null)
				{
					final String xactId = lcc.getTransactionExecute().getActiveStateTxIdString();
					istream.printlnWithHeader(LanguageConnectionContext.xidStr +
											  xactId +
											  "), " +
											  LanguageConnectionContext.lccStr +
												  lcc.getInstanceNumber() +
											  "), " +
											  LanguageConnectionContext.dbnameStr +
												  lcc.getDbname() +
											  "), " +
											  LanguageConnectionContext.drdaStr +
												  lcc.getDrdaID() +
											  "), Begin compiling prepared statement: " +
											  getSource() +
											  " :End prepared statement");
				}
				final Parser p = cc.getParser();

                                p.setGfxdSubactivationFlag(this.needGfxdSubactivation());
                                //GemStone changes BEGIN
                                if(this.isPreparedStatement()) {
                                  //default value false means its a statement
                                  cc.setPreparedStatement();
                                }
                                if(isOptimizedStatement()) {
                                  cc.allowOptimizeLiteral(true);
                                }

                                prevAPL = cc.getCurrentAuxiliaryProviderList();
                                final ProviderList apl = new ProviderList();
                                cc.setCurrentAuxiliaryProviderList(apl);
                                //GemStone changes END
				cc.setCurrentDependent(preparedStmt);

				// GemStone changes BEGIN
				cc.setParentPS(preparedStmt);
				// check memory before parsing.
				if (checkCancellation) {
				  Misc.checkMemory(thresholdListener, statementText, -1);
				}
                                // GemStone changes END
				//Only top level statements go through here, nested statement
				//will invoke this method from other places
				//GemStone changes BEGIN
				//StatementNode qt = p.parseStatement(statementText, paramDefaults);
				StatementNode qt;
				final String source = getSource();

				try {
					//Route all "insert/put into tab select .. " queries to spark

					if (routeQuery && prepareIsolationLevel == Connection.TRANSACTION_NONE && (
						ROUTED_QUERY_PATTERN.matcher(source).find() &&
						!ALTER_TABLE_CONSTRAINTS.matcher(source).find())) {
							cc.markAsDDLForSnappyUse(true);
							return getPreparedStatementForSnappy(false, statementContext, lcc,
								cc.isMarkedAsDDLForSnappyUse(), checkCancellation,
								DML_TABLE_PATTERN.matcher(source).find());
					}
					qt = p.parseStatement(getQueryStringForParse(lcc), paramDefaults);
				}
				catch (StandardException | AssertFailure ex) {
          //wait till the query hint is examined before throwing exceptions or
          if (routeQuery && prepareIsolationLevel == Connection.TRANSACTION_NONE &&
              !NON_ROUTED_QUERY.matcher(source).find()) {
            return getPreparedStatementForSnappy(false, statementContext, lcc,
                cc.isMarkedAsDDLForSnappyUse(), checkCancellation,
                DML_TABLE_PATTERN.matcher(source).find());
          }
          throw ex;
				}
				checkCancellation = checkCancellation && !shouldSkipMemoryChecks(qt);
				// DDL Route, even if no exception
				if (routeQuery && cc.isForcedDDLrouting() && !NON_ROUTED_QUERY.matcher(source).find())
				{
					//SanityManager.DEBUG_PRINT("DEBUG","Parse: force routing sql=" + this.getSource());
				    if (observer != null) {
				      observer.testExecutionEngineDecision(qinfo, ExecutionEngine.SPARK, this.statementText);
				    }
				    return getPreparedStatementForSnappy(false, statementContext, lcc, true,
				        checkCancellation, DML_TABLE_PATTERN.matcher(source).find());
				}
				//GemStone changes END
				parseTime = getCurrentTimeMillis(lcc);

				if (SanityManager.DEBUG)
				{
				  	if (GemFireXDUtils.TraceParseTree)
					{
						qt.treePrint();
					}

					if (GemFireXDUtils.TraceStopAfterParse)
					{
						throw StandardException.newException(SQLState.LANG_STOP_AFTER_PARSING);
					}
				}
// GemStone changes BEGIN

				/*
				** Tell the data dictionary that we are about to do
				** a bunch of "get" operations that must be consistent with
				** each other.
				*/
				/*
				DataDictionary dataDictionary = lcc.getDataDictionary();

				int ddMode = dataDictionary == null ? 0 : dataDictionary.startReading(lcc);
				*/
				  StringBuilder queryTextForStats = null;
				  boolean continueLoop = true;
			    int i = 0;
			    // allow checking for get/getAll convertibles even with bucketIds
			    final boolean isLocalScan = lcc.getBucketIdsForLocalExecution() != null;
			    boolean forceSkipQueryInfoCreation = false;
			    while (continueLoop) {
			      i++;
			      continueLoop = false;
			      if (observer != null) {
			        observer.afterQueryParsing(this.statementText,
			            qt, lcc);
			        observer.beforeOptimizedParsedTree(
			            this.statementText, qt, lcc);
			      }
// GemStone changes END
				try
				{
					// start a nested transaction -- all locks acquired by bind
					// and optimize will be released when we end the nested
					// transaction.
				        if( i == 1) {
					  lcc.beginNestedTransaction(true);
				        }

// GemStone changes BEGIN
				        // set the qt before binding to get hold
				        // of it in exceptions etc.
				        lcc.setTransientStatementNode(qt);
	                                // check memory before binding.
				        if (checkCancellation) {
	                                  Misc.checkMemory(thresholdListener, statementText, -1);
				        }
// GemStone changes END
					try {
						qt.bindStatement();
					}
					catch(StandardException | AssertFailure ex) {
				      boolean routeToSnappy = (ex instanceof StandardException && ((StandardException)ex).
									getMessageId().equals(SQLState.ROW_LEVEL_SECURITY_ENABLED)) ||
									(routeQuery && !NON_ROUTED_QUERY.matcher(source).find());

					  if (routeToSnappy) {
					    if (observer != null) {
					      observer.testExecutionEngineDecision(qinfo, ExecutionEngine.SPARK, this.statementText);
					    }
							return getPreparedStatementForSnappy(true, statementContext, lcc, false,
							  checkCancellation, DML_TABLE_PATTERN.matcher(source).find());
					  }
					  throw ex;
					}

					bindTime += getCurrentTimeMillis(lcc);

					if (SanityManager.DEBUG)
					{
						if (SanityManager.DEBUG_ON("DumpBindTree"))
						{
							qt.treePrint();
						}

						if (SanityManager.DEBUG_ON("StopAfterBinding")) {
							throw StandardException.newException(SQLState.LANG_STOP_AFTER_BINDING);
						}
					}
					
					// generate query string text for statement stats
					if (lcc.statsEnabled()) {
					  queryTextForStats = qt.makeShortDescription(new StringBuilder());
					}

					//Derby424 - In order to avoid caching select statements referencing
					// any SESSION schema objects (including statements referencing views
					// in SESSION schema), we need to do the SESSION schema object check
					// here.
					//a specific eg for statement referencing a view in SESSION schema
					//CREATE TABLE t28A (c28 int)
					//INSERT INTO t28A VALUES (280),(281)
					//CREATE VIEW SESSION.t28v1 as select * from t28A
					//SELECT * from SESSION.t28v1 should show contents of view and we
					// should not cache this statement because a user can later define
					// a global temporary table with the same name as the view name.
					//Following demonstrates that
					//DECLARE GLOBAL TEMPORARY TABLE SESSION.t28v1(c21 int, c22 int) not
					//     logged
					//INSERT INTO SESSION.t28v1 VALUES (280,1),(281,2)
					//SELECT * from SESSION.t28v1 should show contents of global temporary
					//table and not the view.  Since this select statement was not cached
					// earlier, it will be compiled again and will go to global temporary
					// table to fetch data. This plan will not be cached either because
					// select statement is using SESSION schema object.
					//
					//Following if statement makes sure that if the statement is
					// referencing SESSION schema objects, then we do not want to cache it.
					// We will remove the entry that was made into the cache for
					//this statement at the beginning of the compile phase.
					//The reason we do this check here rather than later in the compile
					// phase is because for a view, later on, we loose the information that
					// it was referencing SESSION schema because the reference
					//view gets replaced with the actual view definition. Right after
					// binding, we still have the information on the view and that is why
					// we do the check here.
					if (preparedStmt.referencesSessionSchema(qt)) {
						if (foundInCache)
							((GenericLanguageConnectionContext)lcc).removeStatement(this);
					}
					try {
						qt.optimizeStatement();

					}
					catch(StandardException | AssertFailure ex) {
						if (routeQuery && !NON_ROUTED_QUERY.matcher(source).find()) {
							if (observer != null) {
								observer.testExecutionEngineDecision(qinfo, ExecutionEngine.SPARK, this.statementText);
							}
							return getPreparedStatementForSnappy(true, statementContext, lcc, false,
								checkCancellation, DML_TABLE_PATTERN.matcher(source).find());
						}
						throw ex;
					}

 					optimizeTime += getCurrentTimeMillis(lcc);
// GemStone changes BEGIN
 	                                // check memory after optimization and before queryInfo computation.
 					if (checkCancellation) {
 	                                  Misc.checkMemory(thresholdListener, statementText, -1);
 					}
 					if (lcc.getOptimizerTrace()) {
                                            if (GemFireXDUtils.IS_TEST_MODE) {
                                              SanityManager.DEBUG_PRINT("info", "<IgnoreGrepLogsStart/>");
                                            }
                                            SanityManager.DEBUG_PRINT("gemfirexd.optimizer.trace",
                                                lcc.getOptimizerTraceOutput());
                                            if (GemFireXDUtils.IS_TEST_MODE) {
                                              SanityManager.DEBUG_PRINT("info", "<IgnoreGrepLogsStop/>");
                                            }
 					}
				        if (observer != null) {
				          observer.afterOptimizedParsedTree(
				              this.statementText, qt, lcc);
				        }

                                        if (routeQuery && qt instanceof ExplainNode &&
                                            ((ExplainNode)qt).isRouted()) {
                                          if (observer != null) {
                                            observer.testExecutionEngineDecision(qinfo,
                                                ExecutionEngine.SPARK, this.statementText);
                                          }
                                          return getPreparedStatementForSnappy(true,
                                              statementContext, lcc, false, checkCancellation, false);
                                        }
                                        if(this.createQueryInfo() && !forceSkipQueryInfoCreation) {
                                          final DataTypeDescriptor paramDTDS[] = qt.getParameterTypes();
                                          final QueryInfoContext qic = new QueryInfoContext(
                                              this.createQueryInfo(),  paramDTDS != null ? paramDTDS.length : 0, isPreparedStatement());
                                          qinfo = qt.computeQueryInfo(qic);
                                          // only allow get/getAll convertibles for local scan
                                          if (isLocalScan &&
                                              !(qinfo.isSelect() && qinfo.isPrimaryKeyBased())) {
                                            qinfo = null;
                                            forceSkipQueryInfoCreation = true;
                                            continue;
                                          }
                                          // Only rerouting selects to lead node. Inserts will be handled separately.
                                          // The below should be connection specific.
                                          if ((routeQuery && qinfo != null && qinfo.isDML()
                                              && cc.getExecutionEngine() != ExecutionEngine.STORE)) {
                                            // order is important. cost should be last criteria
                                            if (cc.getExecutionEngine() == ExecutionEngine.SPARK
                                                || engineArbiter.getExecutionEngine((DMLQueryInfo)qinfo) == ExecutionEngine.SPARK
                                                /*|| engineArbiter.getExecutionEngine(qt, this, routeQuery) == ExecutionEngine.SPARK*/) {
                                              boolean isUpdateOrDelete = qinfo.isUpdate() || qinfo.isDelete();
                                              if (qinfo.isSelect() || isUpdateOrDelete) {
                                                if (observer != null) {
                                                  observer.testExecutionEngineDecision(qinfo, ExecutionEngine.SPARK, this.statementText);
                                                }
                                                return getPreparedStatementForSnappy(true,
                                                    statementContext, lcc, false,
                                                    checkCancellation, isUpdateOrDelete);
                                              }
                                            }
                                          }

                                          if (observer != null && qinfo != null && qinfo.isSelect()) {
                                            observer.testExecutionEngineDecision(qinfo, ExecutionEngine.STORE, this.statementText);
                                          }

                                          if (qinfo != null && qinfo.isInsert()) {
                                            qinfo = handleInsertAndInsertSubSelect(qinfo, qt);
                                          }

                                          if (isSnappyStore && !isLocalScan &&
                                              qinfo != null && qinfo.isDML() &&
                                              invalidQueryOnColumnTable(lcc, (DMLQueryInfo)qinfo)) {
                                            throw StandardException.newException(SQLState.SNAPPY_OP_DISALLOWED_ON_COLUMN_TABLES);
                                          }

                                          //Even if  i == 1, but if the top query contains replicated table
                                          //&  sub query contains PR , we cannot allow flattening as it will cause
                                          // Bug # 42428. causing repetition of data.
                                          if( i == 2 || !cc.subqueryFlatteningAllowed()) {
                                            // we are here because disallowing subquery flattening
                                            // made it successful
                                            ((DMLQueryInfo)qinfo).disallowSubqueryFlattening();
                                          }
                                          if (observer != null) {
                                            observer.queryInfoObjectFromOptmizedParsedTree(qinfo, preparedStmt, lcc);
                                            if(qinfo != null && qinfo.isSelect()) {
                                              final List<SubQueryInfo> sqis = ((DMLQueryInfo)qinfo).getSubqueryInfoList();
                                              if(!sqis.isEmpty() )
                                                observer.subQueryInfoObjectFromOptmizedParsedTree(sqis, preparedStmt, lcc);
                                              }
                                            }

                                          //Bug # 42416
                                          //If gfxd subactivation is needed and if the outer query is on replicated region,
                                          // we though have to create derby activation for outer query, inner should go as
                                          // distributed query, so we need to recompile the tree with right flags set.
                                          // the set up needed would be
                                          if (qinfo != null && !qinfo.createGFEActivation() && (qinfo.isDML())
                                              && ((DMLQueryInfo)qinfo).isRemoteGfxdSubActivationNeeded()) {
                                            this.execFlags = GemFireXDUtils.set(this.execFlags,
                                            GFXD_SUBACTIVATION_NEEDED);
                                            if (!cc.subqueryFlatteningAllowed()) {
                                              this.execFlags = GemFireXDUtils.set(this.execFlags,
                                                  DISALLOW_SUBQUERY_FLATTENING);
                                            }
                                            cc.disableQueryInfoCreation();
                                            cc.setHasOrList(false);
                                            p.setGfxdSubactivationFlag(true);

                                            qt = p.parseStatement(this.getQueryStringForParse(lcc), paramDefaults);
                                            continueLoop = true;
                                            forceSkipQueryInfoCreation = true;
                                            continue;
                                          }
                                        }
                                        
                                        routingInfoTime += getCurrentTimeMillis(lcc);
// GemStone changes END

					// Statement logging if lcc.getLogStatementText() is true
					if (istream != null)
					{
						final String xactId = lcc.getTransactionExecute().getActiveStateTxIdString();
						istream.printlnWithHeader(LanguageConnectionContext.xidStr +
												  xactId +
												  "), " +
												  LanguageConnectionContext.lccStr +
												  lcc.getInstanceNumber() +
												  "), " +
												  LanguageConnectionContext.dbnameStr +
												  lcc.getDbname() +
												  "), " +
												  LanguageConnectionContext.drdaStr +
												  lcc.getDrdaID() +
												  "), End compiling prepared statement: " +
												  getSource() +
												  " :End prepared statement");
					}
				}

				catch (final StandardException se)
				{
// GemStone changes BEGIN
				  String messgId = se.getMessageId();
				  if (cc.containsNoncorrelatedSubquery() && i == 1 &&
				      (messgId.equals(SQLState.NOT_COLOCATED_WITH)
				          || messgId.equals(SQLState.COLOCATION_CRITERIA_UNSATISFIED) ||
				          messgId.equals(SQLState.REPLICATED_PR_CORRELATED_UNSUPPORTED))) {
				    continueLoop = true;
				    cc.setSubqueryFlatteningFlag(false /* do not allow flattening*/);
				    cc.setHasOrList(false);
				    cc.resetNumTables();
				    qt = p.parseStatement(this.getQueryStringForParse(lcc), paramDefaults);
				    continue;
				  }
				  else if (SQLState.INTERNAL_SKIP_ORLIST_OPTIMIZATION.equals(messgId)) {
				    continueLoop = true;

				    cc.setOrListOptimizationFlag(false);
				    cc.setHasOrList(false);
				    qt = p.parseStatement(getQueryStringForParse(lcc), paramDefaults);
				    continue;
          } else if (routeQuery && !NON_ROUTED_QUERY.matcher(source).find() &&
            (messgId.equals(SQLState.NOT_COLOCATED_WITH) ||
                messgId.equals(SQLState.COLOCATION_CRITERIA_UNSATISFIED) ||
                messgId.equals(SQLState.REPLICATED_PR_CORRELATED_UNSUPPORTED) ||
                messgId.equals(SQLState.SUBQUERY_MORE_THAN_1_NESTING_NOT_SUPPORTED) ||
                messgId.equals(SQLState.NOT_IMPLEMENTED))) {
            if (observer != null) {
              observer.testExecutionEngineDecision(qinfo, ExecutionEngine.SPARK, this.statementText);
            }
            return getPreparedStatementForSnappy(true, statementContext, lcc, false,
                checkCancellation, DML_TABLE_PATTERN.matcher(source).find());
          }
// GemStone changes END
					lcc.commitNestedTransaction();

					// Statement logging if lcc.getLogStatementText() is true
					if (istream != null)
					{
						final String xactId = lcc.getTransactionExecute().getActiveStateTxIdString();
						istream.printlnWithHeader(LanguageConnectionContext.xidStr +
												  xactId +
												  "), " +
												  LanguageConnectionContext.lccStr +
												  lcc.getInstanceNumber() +
												  "), " +
												  LanguageConnectionContext.dbnameStr +
												  lcc.getDbname() +
												  "), " +
												  LanguageConnectionContext.drdaStr +
												  lcc.getDrdaID() +
												  "), Error compiling prepared statement: " +
												  getSource() +
												  " :End prepared statement");
					}
					throw se;
				}

// GemStone changes BEGIN
          // removed doneReading call from here since the
          // qt.makeConstantAction() actually reads the DD; only do it
          // for ExecSPSNode since it requires DD write lock
          finally {
            /* Tell the data dictionary that we are done reading */
            if (!continueLoop && dataDictionary != null
                && ddLockAcquired && qt instanceof ExecSPSNode) {
              dataDictionary.doneReading(ddMode, lcc);
              ddLockAcquired = false;
            }
          }

			}
				/* (original derby code)
				finally
				{
					/* Tell the data dictionary that we are done reading *
					if (dataDictionary != null)
					dataDictionary.doneReading(ddMode, lcc);
				}
				*/
// GemStone changes END

				/* we need to move the commit of nested sub-transaction
				 * after we mark PS valid, during compilation, we might need
				 * to get some lock to synchronize with another thread's DDL
				 * execution, in particular, the compilation of insert/update/
				 * delete vs. create index/constraint (see Beetle 3976).  We
				 * can't release such lock until after we mark the PS valid.
				 * Otherwise we would just erase the DDL's invalidation when
				 * we mark it valid.
				 */
				try		// put in try block, commit sub-transaction if bad
				{
					if (SanityManager.DEBUG)
					{
						if (SanityManager.DEBUG_ON("DumpOptimizedTree"))
						{
							qt.treePrint();
						}

						if (SanityManager.DEBUG_ON("StopAfterOptimizing"))
						{
							throw StandardException.newException(SQLState.LANG_STOP_AFTER_OPTIMIZING);
						}
					}
//                                      GemStone changes BEGIN

	                               // check memory before query plan class generation.
				       if (checkCancellation) {
				         Misc.checkMemory(thresholdListener, statementText, -1);
				       }
                                       createGFEPrepStmt = false;
                                       final int statementType = qt.getStatementType();
                                       if (qinfo != null) {
                                         createGFEPrepStmt = qinfo.createGFEActivation();
                                         if (statementType == StatementType.DELETE
                                            || statementType == StatementType.UPDATE) {
                                            // skip below for VTI update
                                            if (!(statementType == StatementType.UPDATE
                                                && qinfo.isTableVTI())) {
                                              this.preparedStmt.setLocalRegion(
                                                  (LocalRegion)qinfo.getRegion());
                                            }
                                          }
                                        }
                                          
                                        // populate statistics object before class generation, so that subquery capture it in sub-activation.
                                        fetchOrCreateStatementStats(lcc, qt, cc, queryTextForStats);

                                        if (!createGFEPrepStmt || !cc.isGlobalScope()) {
                                          ac = qt.generate(preparedStmt.getByteCodeSaver());
                                          //Neeraj: If local execution due to cc.isGlobalScope returning false then force all the queries to run
                                          // only on primary buckets. Ideal would be to either select primaries only or secondaries only,
                                          //decide on the co-ordinator and use that so that only primaries doesn't get over loaded.
                                          if (!cc.isGlobalScope()) {
                                            preparedStmt.setUseOnlyPrimaryBuckets(true);
                                          }
                                          if (SanityManager.DEBUG) {
                                            if (GemFireXDUtils.TraceActivation) {
                                              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
                                                  "GenericStatement::prepMinion Generated plan for " + statementText
                                                    + " parseNode " + createQueryInfo() + ' ' + ac.getName());
                                            }
                                          }

                                        } else {
                                          if (SanityManager.DEBUG) {
                                            if (GemFireXDUtils.TraceActivation) {
                                              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
                                                  "GenericStatement::prepMinion Creating GemFireActivationClass");
                                            }
                                          }

                                          ac = new GemFireActivationClass(lcc, qinfo);
                                        }
//                                      GemStone changes END


					generateTime = getCurrentTimeMillis(lcc);
					/* endTimestamp only meaningful if generateTime is meaningful.
					 * generateTime is meaningful if STATISTICS TIMING is ON.
					 */
					if (generateTime != 0)
					{
						endTimestamp = new Timestamp(generateTime);
					}

					if (SanityManager.DEBUG)
					{
						if (SanityManager.DEBUG_ON("StopAfterGenerating"))
						{
							throw StandardException.newException(SQLState.LANG_STOP_AFTER_GENERATING);
						}
					}

					/*
						copy over the compile-time created objects
						to the prepared statement.  This always happens
						at the end of a compile, so there is no need
						to erase the previous entries on a re-compile --
						this erases as it replaces.  Set the activation
						class in case it came from a StorablePreparedStatement
					*/
					preparedStmt.setConstantAction( qt.makeConstantAction() );
					preparedStmt.setSavedObjects( cc.getSavedObjects() );
					preparedStmt.setRequiredPermissionsList(cc.getRequiredPermissionsList());
					preparedStmt.incrementVersionCounter();
					preparedStmt.setActivationClass(ac);
					preparedStmt.setNeedsSavepoint(qt.needsSavepoint());
					preparedStmt.setCursorInfo((CursorInfo)cc.getCursorInfo());
					preparedStmt.setIsAtomic(qt.isAtomic());
					preparedStmt.setExecuteStatementNameAndSchema(
												qt.executeStatementName(),
												qt.executeSchemaName()
												);
					preparedStmt.setSPSName(qt.getSPSName());
					preparedStmt.completeCompile(qt);
					preparedStmt.setCompileTimeWarnings(cc.getWarnings());
// GemStone changes BEGIN
          preparedStmt.setDynamicTokenList(cc.getDynamicTokenList());
					preparedStmt.setQueryHDFS(cc.getQueryHDFS());
					preparedStmt.setHasQueryHDFS(cc.getHasQueryHDFS());
					preparedStmt.setIsCallableStatement(
					    (cc.getOriginalExecFlags() & IS_CALLABLE_STATEMENT) != 0);

					if (SanityManager.TraceSingleHop) {
		                            SanityManager.DEBUG_PRINT(
		                                SanityManager.TRACE_SINGLE_HOP,
		                                "GenericStatement::prepminion qinfo is: " + qinfo
		                                    + " and lcc.getSendSingleHopInformation(): "
		                                    + lcc.getSendSingleHopInformation());
		                        }
					if (qinfo != null && isPreparedStatement()
		                              && ((qinfo instanceof DMLQueryInfo)
		                                  && (((DMLQueryInfo)qinfo).getSubqueryInfoList() == null
		                                      || ((DMLQueryInfo)qinfo).getSubqueryInfoList().isEmpty()))
		                              && !qinfo.isInsertAsSubSelect()
		                              && (qinfo.isSelect() || qinfo.isUpdate() || qinfo.isDelete())) {
		                            Set<Object> robjs = new THashSet();
		                            robjs.add(ResolverUtils.TOK_ALL_NODES);
		                            Activation a = (Activation)ac.newInstance(
		                                lcc, false, preparedStmt);
		                            try {
		                              qinfo.computeNodes(robjs, a, true);
		                            } finally {
		                              // cleanup
		                              a.close();
		                            }
		                            if (SanityManager.TraceSingleHop) {
		                              SanityManager.DEBUG_PRINT(
		                                  SanityManager.TRACE_SINGLE_HOP,
		                                  "GenericStatement::prepminion qinfo is: " + qinfo
		                                      + " and after compute nodes routingobjectset: "
		                                      + robjs);
		                            }
		                            if (robjs != null && robjs.size() > 0 &&
		                                !robjs.contains(ResolverUtils.TOK_ALL_NODES)) {
		                              preparedStmt.setRoutingObjects(robjs);
		                            }
					}
					lcc.setTransientStatementNode(null);
					preparedStmt.setQueryInfoObject(qinfo);
					//ac.setUpdatableInfo(qt);
                                        preparedStmt.setStatementType(statementType);
                                        preparedStmt.setInsertAsSubselect(qinfo != null
                                            && qinfo.hasSubSelect());
                                        InsertNode in = qt instanceof InsertNode ? ((InsertNode)qt) : null;
                                        TableDescriptor ttd = in != null ? in.targetTableDescriptor : null;
                                        boolean isAppTable = false;
                                        LocalRegion lr = null;
                                        boolean isSessionSchema = preparedStmt.referencesSessionSchema();
                                        if (ttd != null && !isSessionSchema) {
                                          lr = (LocalRegion)Misc.getRegion(ttd, lcc, true, false);
                                          if (lr != null && lr instanceof PartitionedRegion) {
                                            GemFireContainer gfc = (GemFireContainer)lr.getUserAttribute();
                                            if (gfc != null) {
                                              isAppTable = gfc.isApplicationTable();
                                            }
                                          }
                                          else {
                                            lr = null;
                                          }
                                        }
                                        if (in != null) {
                                          preparedStmt.setPutDML(in.isPutDML());
                                        }
                                        if (isAppTable && in != null && qinfo != null) {
                                          Activation a = (Activation)ac.newInstance(
                                              lcc, false, preparedStmt);
                                          if (isPreparedStatement()) {
                                            GfxdPartitionResolver resolver = GemFireXDUtils.getResolver(lr);
                                            Set<Object> robjs = ((InsertNode)qt).getSingleHopInformation(
                                                (GenericParameterValueSet)a.getParameterValueSet(),
                                                preparedStmt, resolver, ttd);
                                            if (robjs != null && robjs.size() > 0 &&
                                                !robjs.contains(ResolverUtils.TOK_ALL_NODES)) {
                                              preparedStmt.setRoutingObjects(robjs);
                                            }
                                            String schema = ttd.getSchemaName();
                                            String table = ttd.getName();
                                            ((InsertQueryInfo)qinfo).setSchemaTableAndRegion(schema, table, lr);
                                          }
                                        }
                                          if(apl.size() > 0) {
                                            preparedStmt.setProviderList(apl);
                                          }

                                          if (observer != null) {
                                          observer.queryInfoObjectAfterPreparedStatementCompletion(qinfo,
                                              preparedStmt, lcc);
                                        }
// GemStone changes END
				}
				catch (final StandardException e) 	// hold it, throw it
				{
					lcc.commitNestedTransaction();
					throw e;
				}

				if (lcc.getRunTimeStatisticsMode())
				{
					preparedStmt.setCompileTimeMillis(
						parseTime - beginTime, //parse time
						bindTime - parseTime, //bind time
						optimizeTime - bindTime, //optimize time
						routingInfoTime - optimizeTime, // queryInfo time
						generateTime - routingInfoTime, //generate time
						getElapsedTimeMillis(beginTime),
						beginTimestamp,
						endTimestamp);
				}

			}
			finally // for block introduced by pushCompilerContext()
			{
			       lcc.popCompilerContext( cc );
                               //GemStone changes BEGIN
                               cc.setCurrentAuxiliaryProviderList(prevAPL);
				//GemStone changes END
			}
		}
		catch (final StandardException se)
		{
			if (foundInCache)
				((GenericLanguageConnectionContext)lcc).removeStatement(this);
			throw se;
		}
		finally
		{

			synchronized (preparedStmt) {
				preparedStmt.setCompilingStatement(false);
				preparedStmt.notifyAll();
			}
		}

// GemStone changes BEGIN
      // check memory again before returning the query plan.
      if (checkCancellation) {
        Misc.checkMemory(thresholdListener, statementText, -1);
      }
    } catch (final RuntimeException e) {
      lcc.commitNestedTransaction();
      throw e;
    } catch (final Error e) {
      lcc.commitNestedTransaction();
      throw e;
    } finally {
      /* Tell the data dictionary that we are done reading */
      if (dataDictionary != null && ddLockAcquired ) {
        dataDictionary.doneReading(ddMode, lcc);
        ddLockAcquired = false;
      }

    }
// GemStone changes END
		lcc.commitNestedTransaction();

		if (statementContext != null)
			lcc.popStatementContext(statementContext, null);


		return preparedStmt;
	}

	public boolean invalidQueryOnColumnTable(LanguageConnectionContext _lcc,
			DMLQueryInfo qi) {
		boolean isColumnTable = SnappyActivation.isColumnTable(qi);
		// check subqueries
		List<SubQueryInfo> subQinfoList = qi.getSubqueryInfoList();
		if (!isColumnTable && subQinfoList.size() > 0) {
			for (SubQueryInfo sq : subQinfoList) {
				isColumnTable = SnappyActivation.isColumnTable(sq);
				if(isColumnTable)
					break;
			}
		}
		// additional step for insertAsSubSelect
		if (!isColumnTable && qi.isInsertAsSubSelect()) {
			isColumnTable = SnappyActivation.isColumnTable(((InsertQueryInfo)qi).getSubSelectQueryInfo());
		}
		return isColumnTable && !Misc.routeQuery(_lcc) && !_lcc.isSnappyInternalConnection();
	}

	private boolean shouldSkipMemoryChecks(StatementNode qt) {
		final int nodeType = qt.getNodeType();
		return nodeType == C_NodeTypes.DELETE_NODE
        || nodeType == C_NodeTypes.DROP_TABLE_NODE
        || nodeType == C_NodeTypes.DROP_INDEX_NODE
        || (nodeType == C_NodeTypes.ALTER_TABLE_NODE &&
				     ((AlterTableNode)qt).isTruncateTable());
	}

	/**
	 * Generates an execution plan given a set of named parameters.
	 * Does so for a storable prepared statement.
	 *
	 * @param	paramDefaults		Parameter defaults
	 *
	 * @return A PreparedStatement that allows execution of the execution
	 *	   plan.
	 * @exception StandardException	Thrown if this is an
	 *	   execution-only version of the module (the prepare() method
	 *	   relies on compilation).
	 */
	@Override
  public	PreparedStatement prepareStorable(
				LanguageConnectionContext lcc,
				PreparedStatement ps,
				Object[]			paramDefaults,
				SchemaDescriptor	spsSchema,
				boolean internalSQL)
		throws StandardException
	{
		if (ps == null)
			ps = new GenericStorablePreparedStatement(this);
		else
			((GenericPreparedStatement) ps).statement = this;

		this.preparedStmt = (GenericPreparedStatement) ps;
		return prepMinion(lcc, false, paramDefaults, spsSchema, internalSQL);
	}

	@Override
  public String getSource() {
		return statementText;
	}

	public String getCompilationSchema() {
		return compilationSchema.getDescriptorName();
	}

	private static long getCurrentTimeMillis(LanguageConnectionContext lcc)
	{
		if (lcc.getStatisticsTiming())
		{
			return System.currentTimeMillis();
		}
		else
		{
			return 0;
		}
	}

	private static long getElapsedTimeMillis(long beginTime)
	{
		if (beginTime != 0)
		{
			return System.currentTimeMillis() - beginTime;
		}
		else
		{
			return 0;
		}
	}

    /**
     * Return the {@link PreparedStatement} currently associated with this
     * statement.
     *
     * @return the prepared statement that is associated with this statement
     */
    public PreparedStatement getPreparedStatement() {
        return preparedStmt;
    }

    public boolean isSystemSchema() {
      return this.compilationSchema.isSystemSchema();
    }

    @Override
    public GenericPreparedStatement prepareStatement(LanguageConnectionContext lcc,
        QueryInfo qinfo, DataTypeDescriptor[] paramDTDs, CursorNode cn,
        CompilerContext cc) throws StandardException
        {
      if (GemFireXDUtils.TraceActivation) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
            "GenericStatement:: prepareStatement for subquery activation creation");
      }


      boolean createGFEPrepStmt = false;

      if (preparedStmt != null) {
        if (preparedStmt.upToDate())
          return preparedStmt;
      }



      prepareIsolationLevel = lcc.getPrepareIsolationLevel();
      this.preparedStmt = new GenericPreparedStatement(this, true /* is sub query prep stmnt*/);
      this.preparedStmt.setSource(getSource());

      HeaderPrintWriter istream = lcc.getLogStatementText() ? Monitor
          .getStream() : null;
          if (istream != null) {
            String xactId = lcc.getTransactionExecute().getActiveStateTxIdString();
            istream.printlnWithHeader(LanguageConnectionContext.xidStr + xactId
                + "), " + LanguageConnectionContext.lccStr
                + lcc.getInstanceNumber() + "), "
                + LanguageConnectionContext.dbnameStr + lcc.getDbname() + "), "
                + LanguageConnectionContext.drdaStr + lcc.getDrdaID()
                + "), End compiling prepared statement: " + getSource()
                + " :End prepared statement");
          }

          GeneratedClass ac = null;
          createGFEPrepStmt = qinfo.createGFEActivation();

          if (createGFEPrepStmt) {

            if (GemFireXDUtils.TraceActivation) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
                  "GenericStatement::prepareStatement for subquery activation:" +
                  " Creating GemFireActivationClass");
            }


            ac = new GemFireActivationClass(lcc, qinfo);

          }
          else {
            if (GemFireXDUtils.TraceActivation) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
                  "GenericStatement::prepareStatement for subquery activation:" +
                  " Creating Derby Activation Class");
            }
            ac = cn.generate(preparedStmt.getByteCodeSaver());
          }
          // GemStone changes END

          preparedStmt.setConstantAction(null);
          Object[] savedObjects = cc.getSavedObjects();
          preparedStmt.setSavedObjects(savedObjects);
          //reset the saved objects back in CC so that parent PreparedStatement
          // will also get it
          cc.setSavedObjects(savedObjects);
          preparedStmt.setRequiredPermissionsList(Collections.EMPTY_LIST);
          preparedStmt.setActivationClass(ac);
          preparedStmt.setNeedsSavepoint(false);
          preparedStmt.setCursorInfo(null);
          preparedStmt.setIsAtomic(true);
          preparedStmt.setStatementType(StatementType.UNKNOWN);
          preparedStmt.setValid();
          preparedStmt.paramTypeDescriptors = paramDTDs;
          return preparedStmt;

        }

    //Overriden in GeneralizedStatement
    @Override
    public String getQueryStringForParse(LanguageConnectionContext lcc) {
      return this.getSource();
    }
    //      GemStone changes END
	/*
	** Identity
	*/

	@Override
  public boolean equals(Object other) {

		if (other instanceof GenericStatement) {

			GenericStatement os = (GenericStatement) other;

			return statementText.equals(os.statementText) && isForReadOnly()==os.isForReadOnly()
				&& compilationSchema.equals(os.compilationSchema) &&
//                               GemStone changes BEGIN
                                (createQueryInfo() == os.createQueryInfo()) &&
                                (getQueryHDFS() == os.getQueryHDFS()) &&
//                              GemStone changes END
				(prepareIsolationLevel == os.prepareIsolationLevel);
		}

		return false;
	}

	@Override
  public final int hashCode() {

//        GemStone changes BEGIN
	  /*originally return statementText.hashCode() */
	  return hash;
//        GemStone changes END
	}


  // GemStone changes BEGIN

        /*
         * Handle Insert And InsertSubSelect
         * @return QueryInfo
         */
        private QueryInfo handleInsertAndInsertSubSelect(QueryInfo qinfo,
            final StatementNode qt) throws StandardException
        {
          if (qinfo.hasSubSelect() && !DMLQueryInfo.isSingleVMCase()) {
            assert qinfo.isInsert();
            if (!qinfo.isTableVTI()) {
              InsertQueryInfo.checkSupportedInsertSubSelect(((InsertQueryInfo)qinfo)
                  .getSubSelectQueryInfo());
              String targetTable = ((InsertNode)qt).getNonVTITargetTableName();
              if (targetTable == null) {
                throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
                    "inserts as sub selects for VTIs");
              }
              qinfo.setInsertAsSubSelect(true, targetTable);
              return qinfo;
            }
            else {
              // HandleInsert Select case, where select table is a file i.e. VTI
              // Want local execution of same, using derby, so make qinfo as null
              // return null
            }
          }
          return qinfo;
        }

        protected final int getHashCode(int beginOffset, final int endOffset, int hash) {

          while (beginOffset < endOffset) {
            final char c = statementText.charAt(beginOffset);
            final int val = Character.isHighSurrogate(c) ? Character.toCodePoint(c,
                statementText.charAt(++beginOffset)) : c;
            hash = ResolverUtils.addIntToHash(val, hash);
            beginOffset++;
          }
          if (this.compilationSchema != null) {
            final String schema = this.compilationSchema.getSchemaName();
            final int len = schema.length();
            beginOffset = 0;
            while (beginOffset < len) {
              final char c = schema.charAt(beginOffset);
              final int val = Character.isHighSurrogate(c) ? Character.toCodePoint(c,
                  schema.charAt(++beginOffset)) : c;
              hash = ResolverUtils.addIntToHash(val, hash);
              beginOffset++;
            }
          }
          return hash;
        }

        @Override
        public boolean createQueryInfo() {
         return  GemFireXDUtils.isSet(this.execFlags, CREATE_QUERY_INFO);
        }

        protected boolean isPreparedStatement() {
          return  GemFireXDUtils.isSet(this.execFlags, IS_PREP_STMT);
         }

        protected boolean isOptimizedStatement() {
          return  GemFireXDUtils.isSet(this.execFlags, IS_OPTIMIZED_STMT);
         }

        protected boolean isForReadOnly() {
          return  GemFireXDUtils.isSet(this.execFlags, IS_READ_ONLY);
        }

        protected boolean needGfxdSubactivation() {
          return  GemFireXDUtils.isSet(this.execFlags, GFXD_SUBACTIVATION_NEEDED);
        }

        protected boolean subqueryFlatteningAllowed() {
          return  !GemFireXDUtils.isSet(this.execFlags, DISALLOW_SUBQUERY_FLATTENING);
        }

        protected boolean orListOptimizationAllowed() {
          return !GemFireXDUtils.isSet(this.execFlags, DISALLOW_OR_LIST_OPTIMIZATION);
        }

        protected boolean allTablesAreReplicatedOnRemote() {
          return  GemFireXDUtils.isSet(this.execFlags, ALL_TABLES_ARE_REPLICATED_ON_REMOTE);
        }
        
        protected boolean getQueryHDFS() {
          return  GemFireXDUtils.isSet(this.execFlags, QUERY_HDFS);
        }

        public boolean getRouteQuery() {
        	return  GemFireXDUtils.isSet(this.execFlags, ROUTE_QUERY);
        }

        private final boolean fetchStatementStats(LanguageConnectionContext lcc,
            CompilerContext cc) {
          // if stats is enabled, only then create the stats.
          if(!lcc.statsEnabled()) {
            return true; //pseudo success
          }

          if(preparedStmt == null) {
            return false;
          }

          if(ignoreStmts.matcher(statementText).find()) {
            return true; //pseudo success
          }

          // handling reprepare where we won't create new statistics object for the
          // same query on recompilation.
          if(preparedStmt.getStatementStats() != null) {
              if(cc != null) {
                // This is specially to handle case where during
                // subquery reprepare, child has lost it stats
                cc.setStatementStats(preparedStmt.getStatementStats());
              }
              return true; //success
          }

          StatementStats  s = null;
          //try to get the query node stats object if self execution is happening.
          if (!createQueryInfo()) {
            s = getQueryNodeStatementStats();
          }

          if (s != null) {
            preparedStmt.setStatementStats(s);
            if(cc != null) {
              cc.setStatementStats(s);
            }
            return true; //success
          }

          return false;
        }

        private final void fetchOrCreateStatementStats(
            final LanguageConnectionContext lcc, final StatementNode qt,
            final CompilerContext cc, StringBuilder queryTextForStats) {

          // in case of reprepare, fetch should succeed.
          if (fetchStatementStats(lcc, cc)) {
            return;
          }

          assert cc != null;
          String stmtDesc;
          if(cc.getStatementAlias() == null ) {
            if ((queryTextForStats != null) && (queryTextForStats.length() > 0)) {
              if (queryTextForStats.length() > GfxdConstants.MAX_STATS_DESCRIPTION_LENGTH) {
                stmtDesc = queryTextForStats.substring(0, GfxdConstants.MAX_STATS_DESCRIPTION_LENGTH);
              }
              else {
                stmtDesc = queryTextForStats.toString();
              }
              stmtDesc = stmtDesc + "/" +  getUniqueIdFromStatementText(lcc); //getSource().hashCode();
              SanityManager.DEBUG_PRINT("info:statementAlias:",
                  "created statement descriptor [" + stmtDesc + "] for statement ["
                      + getQueryStringForParse(lcc) + "] schema="
                      + this.compilationSchema);
            }
            else {
              String stmtText = getQueryStringForParse(lcc);//getSource();
              if (stmtText.length() > 255) {
                stmtDesc = stmtText.substring(0, 250);
              }
              else {
                stmtDesc = stmtText;
              }
            }
          }
          else {
            stmtDesc = cc.getStatementAlias();
          }

          stmtDesc = stmtDesc.replaceAll("\\s+", "_");

          int quoteIndex = stmtDesc.indexOf("\"");
          quoteIndex = stmtDesc.startsWith("\"") ? 0 : quoteIndex;
          if(quoteIndex > -1) {
            // we have quotes in the descriptor, address #44661 by escaping quotes &
            // wrapping it with quotes.
            stmtDesc = stmtDesc.replaceAll("\"", "\\\\\"");

            if(!stmtDesc.startsWith("\"")) {
              stmtDesc = "\"" + stmtDesc;
            }

            // check already double quoted or not except last double quote is not
            // escaped, if desc ending with escaped double quote then we need to add
            // another double quote.
            if(! (stmtDesc.endsWith("\"") && !stmtDesc.endsWith("\\\"")) ) {
              stmtDesc = stmtDesc + "\"";
            }
          }

          // log the descriptor that is being used.
          SanityManager.DEBUG_PRINT("info:statementAlias:", "creating statistics with stmtDesc=" + stmtDesc);
          final StatementStats st = StatementStats.newInstance(stmtDesc,
              createQueryInfo(), getUniqueIdFromStatementText(lcc));
          assert preparedStmt != null: " prepMinion: At this time prepared statement cannot be null ";
          preparedStmt.setStatementStats(st);
          cc.setStatementStats(st);

          // Create and register Statement MBean if management is enabled
          if (!st.isCreatedWithExistingStats()) {
            GfxdManagementService.handleEvent(GfxdResourceEvent.STATEMENT__CREATE, this);
          }
        }

        private final StatementStats getQueryNodeStatementStats() {
          GenericPreparedStatement gps = null;
          try {
            GenericLanguageConnectionContext lcc = (GenericLanguageConnectionContext)ConnectionUtil.getCurrentLCC();
            short qnExecFlags = this.execFlags;
            qnExecFlags = GemFireXDUtils.set(qnExecFlags, CREATE_QUERY_INFO);
            final GenericStatement g = new GenericStatement(this.compilationSchema,
                statementText, qnExecFlags, null);

            gps = lcc.seekGenericPreparedStatement(g);
            if (gps != null) {
              return gps.getStatementStats();
            }
          } catch (SQLException e) {
            ; // ignore exception and return null to create a new stat object ;
            gps = null;
          } catch (StandardException e) {
            ; // ignore exception and return null to create a new stat object ;
            gps = null;
          }
          return null;
        }

        protected int getUniqueIdFromStatementText(LanguageConnectionContext lcc) {
          return this.stmtHash;
        }
	 // moved createGFEActivation inside DMLQueryInfo.
        
        public void setReplicatedFlag(boolean allTablesReplicated) {
          if (allTablesReplicated) {
            if (SanityManager.DEBUG) {
              if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                    "GenericStatement::setReplicatedFlag - "
                        + "Setting ALL_TABLES_ARE_REPLICATED_ON_REMOTE to TRUE");
              }
            }
            execFlags = GemFireXDUtils.set(execFlags,
                GenericStatement.ALL_TABLES_ARE_REPLICATED_ON_REMOTE);
          }
          else if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                  "GenericStatement::setReplicatedFlag - "
                      + "Flag ALL_TABLES_ARE_REPLICATED_ON_REMOTE is being cleared");
            }
            execFlags = GemFireXDUtils.clear(execFlags,
                GenericStatement.ALL_TABLES_ARE_REPLICATED_ON_REMOTE);
          }
        }
        // GemStone changes END
}
