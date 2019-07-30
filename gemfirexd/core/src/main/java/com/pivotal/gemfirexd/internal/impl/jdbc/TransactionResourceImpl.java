/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl

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

import java.util.EnumSet;
import java.util.Properties;
import java.sql.SQLException;

// GemStone changes BEGIN
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.TransactionDataNodeHasDepartedException;
import com.gemstone.gemfire.cache.TransactionDataRebalancedException;
import com.gemstone.gemfire.cache.TransactionFlag;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
// GemStone changes END
import com.pivotal.gemfirexd.internal.iapi.db.Database;
import com.pivotal.gemfirexd.internal.iapi.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.io.DerbyIOException;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;

import static com.pivotal.gemfirexd.internal.engine.GfxdConstants.ALLOW_EXPLICIT_COMMIT;

/** 
 *	An instance of a TransactionResourceImpl is a bundle of things that
 *	connects a connection to the database - it is the transaction "context" in
 *	a generic sense.  It is also the object of synchronization used by the
 *	connection object to make sure only one thread is accessing the underlying
 *	transaction and context.
 *
 *  <P>TransactionResourceImpl not only serves as a transaction "context", it
 *	also takes care of: <OL>
 *	<LI>context management: the pushing and popping of the context manager in
 *		and out of the global context service</LI>
 *	<LI>transaction demarcation: all calls to commit/abort/prepare/close a
 *		transaction must route thru the transaction resource.
 *	<LI>error handling</LI>
 *	</OL>
 *
 *  <P>The only connection that have access to the TransactionResource is the
 *  root connection, all other nested connections (called proxyConnection)
 *  accesses the TransactionResource via the root connection.  The root
 *  connection may be a plain EmbedConnection, or a DetachableConnection (in
 *  case of a XATransaction).  A nested connection must be a ProxyConnection.
 *  A proxyConnection is not detachable and can itself be a XA connection -
 *  although an XATransaction may start nested local (proxy) connections.
 *
 *	<P> this is an example of how all the objects in this package relate to each
 *		other.  In this example, the connection is nested 3 deep.  
 *		DetachableConnection.  
 *	<P><PRE>
 *
 *      lcc  cm   database  jdbcDriver
 *       ^    ^    ^         ^ 
 *       |    |    |         |
 *      |======================|
 *      | TransactionResource  |
 *      |======================|
 *             ^  |
 *             |  |
 *             |  |      |---------------rootConnection----------|
 *             |  |      |                                       |
 *             |  |      |- rootConnection-|                     |
 *             |  |      |                 |                     |
 *             |  V      V                 |                     |
 *|========================|      |=================|      |=================|
 *|    EmbedConnection     |      | EmbedConnection |      | EmbedConnection |
 *|                        |<-----|                 |<-----|                 |
 *| (DetachableConnection) |      | ProxyConnection |      | ProxyConnection |
 *|========================|      |=================|      |=================|
 *   ^                 | ^             ^                        ^
 *   |                 | |             |                        |
 *   ---rootConnection-- |             |                        |
 *                       |             |                        |
 *                       |             |                        |
 * |======================|  |======================|  |======================|
 * | ConnectionChild |  | ConnectionChild |  | ConnectionChild |
 * |                      |  |                      |  |                      |
 * |  (EmbedStatement)    |  |  (EmbedResultSet)    |  |  (...)               |
 * |======================|  |======================|  |======================|
 *
 * </PRE>
 * <P>A plain local connection <B>must</B> be attached (doubly linked with) to a
 * TransactionResource at all times.  A detachable connection can be without a
 * TransactionResource, and a TransactionResource for an XATransaction
 * (called  XATransactionResource) can be without a connection.
 *
 *
 */
public final class TransactionResourceImpl
{
	/*
	** instance variables set up in the constructor.
	*/
	// conn is only present if TR is attached to a connection
	protected ContextManager cm;
	protected ContextService csf;
	protected String username;

	private String dbname;
	private InternalDriver driver;
	private String url;
	private String drdaID;
	private String authToken;

	// set these up after constructor, called by EmbedConnection
	protected Database database;
	protected LanguageConnectionContext lcc;
	
	

	/**
	 * create a brand new connection for a brand new transaction
	 */
	TransactionResourceImpl(
							InternalDriver driver, 
							String url,
							Properties info) throws SQLException 
	{
		this.driver = driver;
		csf = driver.getContextServiceFactory();
		dbname = InternalDriver.getDatabaseName(url, info);
		this.url = url;

		// the driver manager will push a user name
		// into the properties if its getConnection(url, string, string)
		// interface is used.  Thus, we look there first.
		// Default to APP.
		username = IdUtil.getUserNameFromURLProps(info);
		this.authToken = info.getProperty(Attribute.PASSWORD_ATTR, "");

		drdaID = info.getProperty(Attribute.DRDAID_ATTR, null);		
		

		// make a new context manager for this TransactionResource

		// note that the Database API requires that the 
		// getCurrentContextManager call return the context manager
		// associated with this session.  The JDBC driver assumes
		// responsibility (for now) for tracking and installing
		// this context manager for the thread, each time a database
		// call is made.
		cm = csf.newContextManager();
// GemStone changes BEGIN
		// check for skip-locks
		// only allowed for admin user by LCC.setSkipLocksForConnection
		this.skipLocks = getPropertyValue(
                    Attribute.SKIP_LOCKS, null, info, false);
		this.defaultSchema = PropertyUtil.findAndGetProperty(info,
		    Attribute.DEFAULT_SCHEMA, null);
		// default is streaming enabled
		this.disableStreaming = getPropertyValue(
		    Attribute.DISABLE_STREAMING,
		    GfxdConstants.GFXD_DISABLE_STREAMING, info,
		    !GfxdConstants.GFXD_STREAMING_DEFAULT);
		this.skipListeners = getPropertyValue(
		    Attribute.SKIP_LISTENERS, null, info, false);
		EnumSet<TransactionFlag> flags = null;
		if (getPropertyValue(
		    Attribute.ENABLE_TX_WAIT_MODE,
		    com.pivotal.gemfirexd.internal.iapi.reference.Property.GFXD_ENABLE_TX_WAIT_MODE,
		    info, GfxdConstants.GFXD_TX_WAIT_MODE_DEFAULT)) {
		  flags = GemFireXDUtils.addTXFlag(TransactionFlag.WAITING_MODE,
		      TXManagerImpl.WAITING_MODE, flags);
		}
		if (getPropertyValue(
		    Attribute.DISABLE_TX_BATCHING,
		    com.pivotal.gemfirexd.internal.iapi.reference.Property.GFXD_DISABLE_TX_BATCHING,
		    info, !GfxdConstants.GFXD_TX_BATCHING_DEFAULT)) {
		  flags = GemFireXDUtils.addTXFlag(
		      TransactionFlag.DISABLE_BATCHING,
		      TXManagerImpl.DISABLE_BATCHING, flags);
		}
                //SQLF:BC
                if (getPropertyValue(
                    Attribute.DISABLE_TX_BATCHING,
                    com.pivotal.gemfirexd.internal.iapi.reference.Property.SQLF_DISABLE_TX_BATCHING,
                    info, false)) {
                  flags = GemFireXDUtils.addTXFlag(
                      TransactionFlag.DISABLE_BATCHING,
                      TXManagerImpl.DISABLE_BATCHING, flags);
                }
		if (getPropertyValue(
		    Attribute.TX_SYNC_COMMITS,
		    com.pivotal.gemfirexd.internal.iapi.reference.Property.GFXD_TX_SYNC_COMMITS,
		    info, GfxdConstants.GFXD_TX_SYNC_COMMITS_DEFAULT)) {
		  flags = GemFireXDUtils.addTXFlag(TransactionFlag.SYNC_COMMITS,
		      TXManagerImpl.SYNC_COMMITS, flags);
		}
		//SQLF:BC
                if (getPropertyValue(
                    Attribute.TX_SYNC_COMMITS,
                    com.pivotal.gemfirexd.internal.iapi.reference.Property.SQLF_TX_SYNC_COMMITS,
                    info, false)) {
                  flags = GemFireXDUtils.addTXFlag(TransactionFlag.SYNC_COMMITS,
                      TXManagerImpl.SYNC_COMMITS, flags);
                }
		this.txFlags = flags;
		this.enableStats = getPropertyValue(
		    Attribute.ENABLE_STATS,
		    GfxdConstants.GFXD_ENABLE_STATS, info, false);
		if (this.enableStats) {
		  this.enableTimeStats = getPropertyValue(
		      Attribute.ENABLE_TIMESTATS,
		      GfxdConstants.GFXD_ENABLE_TIMESTATS, info, false);
		}
		else {
		  this.enableTimeStats = false;
		}
		this.queryHDFS = getPropertyValue(
		    Attribute.QUERY_HDFS,
		    GfxdConstants.GFXD_QUERY_HDFS, info, false);
		this.routeQuery = getPropertyValue(
		    Attribute.ROUTE_QUERY,
		    GfxdConstants.GFXD_ROUTE_QUERY, info, false);
		this.snappyInternalConnection = getPropertyValue(Attribute.INTERNAL_CONNECTION,
				GfxdConstants.INTERNAL_CONNECTION, info, false);
		this.allowExplicitCommit = getPropertyValue(Attribute.ALLOW_EXPLICIT_COMMIT,
				GfxdConstants.ALLOW_EXPLICIT_COMMIT, info, false);
		this.defaultPersistent = getPropertyValue(
		    Attribute.DEFAULT_PERSISTENT, GfxdConstants.GFXD_PREFIX
			+ Attribute.DEFAULT_PERSISTENT, info, false);
		this.enableBulkFkChecks = PropertyUtil.getBooleanProperty(
		    Attribute.ENABLE_BULK_FK_CHECKS,
		    GfxdConstants.GFXD_ENABLE_BULK_FK_CHECKS, info, true, null);
		this.skipConstraintChecks = getPropertyValue(
		    Attribute.SKIP_CONSTRAINT_CHECKS, null, info, false);
                this.enableMetadataPrepare = PropertyUtil.getBooleanProperty(
                    Attribute.ENABLE_METADATA_PREPARE,
                    GfxdConstants.GFXD_ENABLE_METADATA_PREPARE, info, false, null);

                String qt = PropertyUtil.findAndGetProperty(info,
                    GfxdConstants.GFXD_QUERY_TIMEOUT, Attribute.QUERY_TIMEOUT);
                if (qt == null) {
                  // SQLF:BC
                  qt = PropertyUtil.findAndGetProperty(info,
		      com.pivotal.gemfirexd.internal.iapi.reference.Property.SQLF_QUERY_TIMEOUT,
		      Attribute.QUERY_TIMEOUT);
                }
                if (qt != null) {
                  this.queryTimeOut = Integer.parseInt(qt);
                }
                else {
                  this.queryTimeOut = 0;
                }
                
                {// NCJ Batch Size - Default 0 i.e. no batching
                  String ncjBatch = PropertyUtil.findAndGetProperty(info,
                      GfxdConstants.GFXD_NCJ_BATCH_SIZE, Attribute.NCJ_BATCH_SIZE);
                  if (ncjBatch != null) {
                    this.ncjBatchSize = Integer.parseInt(ncjBatch);
                  }
                  else {
                    this.ncjBatchSize = 0;
                  }
                }
            
                {// NCJ Cache Size - Default 0 i.e. no caching
                  String ncjCache = PropertyUtil.findAndGetProperty(info,
                      GfxdConstants.GFXD_NCJ_CACHE_SIZE, Attribute.NCJ_CACHE_SIZE);
                  if (ncjCache != null) {
                    this.ncjCacheSize = Integer.parseInt(ncjCache);
                  }
                  else {
                    this.ncjCacheSize = 0;
                  }
                }
	}

	private final boolean getPropertyValue(String propName,
	    String sysPropName, Properties info, boolean def) {
	  return PropertyUtil.getBooleanProperty(propName, sysPropName,
	      info, def, null);
	}

	private final boolean disableStreaming;

	private final boolean skipListeners;

	private final EnumSet<TransactionFlag> txFlags;

	private final boolean enableStats;

	private final boolean enableTimeStats;
	
	private final boolean queryHDFS;

	private final boolean routeQuery;

	private final boolean snappyInternalConnection;

	private final boolean allowExplicitCommit;

	private final boolean defaultPersistent;

	private final boolean skipConstraintChecks;
	
	private final boolean enableBulkFkChecks;
	
	private final int queryTimeOut;
	
	private final int ncjBatchSize;
	
	private final int ncjCacheSize;

	private final boolean skipLocks;

	final String defaultSchema;

  private final boolean enableMetadataPrepare;
        
	public final EnumSet<TransactionFlag> getTXFlags() {
	  return this.txFlags;
	}

	/* } */
// GemStone changes END

	/**
	 * Called only in EmbedConnection construtor.
	 * The Local Connection sets up the database in its constructor and sets it
	 * here.
	 */
	void setDatabase(Database db)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT(database == null, 
				"setting database when it is not null"); 

		database = db;
	}
// GemStone changes BEGIN
	/*
	 * Called only in EmbedConnection constructor.  Create a new transaction
	 * by creating a lcc.
	 *
	 * The arguments are not used by this object, it is used by
	 * XATransactionResoruceImpl.  Put them here so that there is only one
	 * routine to start a local connection.
	 */
	void startTransaction(long connectionID, boolean isRemote)
	throws StandardException, SQLException {
		// setting up local connection
		lcc = database.setupConnection(cm, username, this.authToken, drdaID, dbname,
		    connectionID, isRemote);

		// no streaming for remote connections
		this.lcc.setEnableStreaming(!isRemote && !this.disableStreaming);
                this.lcc.setStatsEnabled(this.enableStats, this.enableTimeStats,
                    this.lcc.explainConnection());
		if (this.skipListeners) {
		  this.lcc.setSkipListeners();
		}
		this.lcc.setTXFlags(this.txFlags);
		this.lcc.setQueryHDFS(this.queryHDFS);
		this.lcc.setQueryRoutingFlag(this.routeQuery);
		this.lcc.setSnappyInternalConnection(this.snappyInternalConnection);
		this.lcc.setAllowExplicitCommit(this.allowExplicitCommit);
		this.lcc.setDefaultPersistent(this.defaultPersistent);
		this.lcc.setEnableBulkFkChecks(this.enableBulkFkChecks);
		this.lcc.setSkipConstraintChecks(this.skipConstraintChecks);
		this.lcc.setDefaultQueryTimeOut(this.queryTimeOut);
		this.lcc.setSkipLocksForConnection(this.skipLocks);
		this.lcc.setNcjBatchSize(this.ncjBatchSize);
	        this.lcc.setNcjCacheSize(this.ncjCacheSize);
// GemStone changes END
	}

	/**
	 * Return instance variables to EmbedConnection.  RESOLVE: given time, we
	 * should perhaps stop giving out reference to these things but instead use
	 * the transaction resource itself.
	 */
	InternalDriver getDriver() {
		return driver;
	}
	ContextService getCsf() {
		return  csf;
	}

	/**
	 * need to be public because it is in the XATransactionResource interface
	 */
	ContextManager getContextManager() {
		return  cm;
	}

	final LanguageConnectionContext getLcc() {
		return  lcc;
	}
	String getDBName() {
		return  dbname;
	}
	String getUrl() {
		return  url;
	}
	public Database getDatabase() {
		return  database;
	}

	StandardException shutdownDatabaseException() {
		StandardException se = StandardException.newException(SQLState.SHUTDOWN_DATABASE, getDBName());
		se.setReport(StandardException.REPORT_NEVER);
		return se;
	}

	/**
	 * local transaction demarcation - note that global or xa transaction
	 * cannot commit thru the connection, they can only commit thru the
	 * XAResource, which uses the xa_commit or xa_rollback interface as a 
	 * safeguard. 
	 */
// Gemstone changes BEGIN
	// Increased the visibility from package level to public
	public void commit(final EmbedConnection conn) throws StandardException {
	/* (original code)
	void commit() throws StandardException
	{
		lcc.userCommit();
	*/
	  lcc.userCommit();
	  final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
	      .getInstance();
	  if (observer != null) {
	    observer.afterCommit(conn);
	  }
// Gemstone changes END
	}		

	void rollback() throws StandardException
	{
		// lcc may be null if this is called in close.
		if (lcc != null)
			lcc.userRollback();
	}

	/*
	 * context management
	 */

	/**
	 * An error happens in the constructor, pop the context.
	 */
	void clearContextInError()
	{
// GemStone changes BEGIN
		final ContextManager cm = this.cm;
		if (cm != null) {
		  csf.resetCurrentContextManager(cm);
		  csf.removeContext(cm);
		  this.cm = null;
		}
		/* (original code)
		csf.resetCurrentContextManager(cm);
		cm = null;
		*/
// GemStone changes END
	}

	/**
	 * Resolve: probably superfluous
	 */
	void clearLcc()
	{
		lcc = null;
	}

// GemStone changes BEGIN
	// made public
	public
// GemStone changes END
	final void setupContextStack()
	{
		if (SanityManager.DEBUG) {
			SanityManager.ASSERT(cm != null, "setting up null context manager stack");
		}

			csf.setCurrentContextManager(cm);
	}

// GemStone changes BEGIN
	final void setupContextStackAndReattach(
	    final boolean isOperation) throws SQLException {
	  if (SanityManager.DEBUG) {
	    SanityManager.ASSERT(this.cm != null,
	        "setting up null context manager stack");
	  }
	  if (this.csf.setCurrentContextManager(this.cm)) {
	    // GFE transaction may need to be reset in thread-local
	    // because user may have switched connections
	    GemFireTransaction.reattachTransaction(this.lcc, isOperation);
	  }
	  else if (isOperation) {
	    // GFE transaction may need to be started since this may be the
	    // first operation after a previous commit/abort; now not starting
	    // TX immediately after commit/abort to avoid leaks of the new
	    // TXStateProxy in case the commit/abort is the last op by a thread;
	    // don't do the below for statement/connection close ...
	    GemFireTransaction.setActiveStateForTransaction(this.lcc);
	  }
	}

	// made public
	public
// GemStone changes END
	final void restoreContextStack() {

		if ((csf == null) || (cm == null))
			return;
		csf.resetCurrentContextManager(cm);
	}

	/*
	 * exception handling
	 */

	/**
	 * clean up the error and wrap the real exception in some SQLException.
	 */
	final SQLException handleException(Throwable thrownException,
									   boolean autoCommit,	
									   boolean rollbackOnAutoCommit)
			throws SQLException 
	{
		try {
			if (SanityManager.DEBUG)
				SanityManager.ASSERT(thrownException != null);

			/*
				just pass SQL exceptions right back. We assume that JDBC driver
				code has cleaned up sufficiently. Not passing them through would mean
				that all cleanupOnError methods would require knowledge of Utils.
			 */
			if (thrownException instanceof SQLException) {
			  if(this.lcc != null) {
			    ((GemFireTransaction)this.lcc.getTransactionExecute()).release();
			  }
				return (SQLException) thrownException;

			} 

			boolean checkForShutdown = false;
			if (thrownException instanceof StandardException)
			{
				StandardException se = (StandardException) thrownException;
				int severity = se.getSeverity();
				if (severity <= ExceptionSeverity.STATEMENT_SEVERITY)
				{
					/*
					** If autocommit is on, then do a rollback
					** to release locks if requested.  We did a stmt 
					** rollback in the cleanupOnError above, but we still
					** may hold locks from the stmt.
					*/

					if (autoCommit && rollbackOnAutoCommit || ((GemFireTransaction)lcc.getTransactionExecute()).getImplcitSnapshotTxStarted())
					{
						se.setSeverity(ExceptionSeverity.TRANSACTION_SEVERITY);
					}
				} else if (SQLState.CONN_INTERRUPT.equals(se.getMessageId())) {
					// an interrupt closed the connection.
					checkForShutdown = true;
				}
			}
// GemStone changes BEGIN
			final StandardException txEx;
			if ((txEx = wrapsTransactionException(
			        thrownException)) != null) {
			  if (thrownException instanceof StandardException) {
			    final StandardException thrownSe =
			      (StandardException)thrownException;
			    if (!txEx.getMessageId().equals(
			        thrownSe.getMessageId())) {
			      if (txEx.getMessageId().startsWith(
			          thrownSe.getSQLState())) {
			        thrownSe.setSeverity(
			            ExceptionSeverity.TRANSACTION_SEVERITY);
			      }
			      else {
			        thrownException = txEx;
			      }
			    }
			  }
			  else {
			    final StandardException se = txEx;
			    se.setSeverity(ExceptionSeverity.TRANSACTION_SEVERITY);
			    thrownException = se;
			  }
		        }
// GemStone changes END
			// if cm is null, we don't have a connection context left,
			// it was already removed.  all that's left to cleanup is
			// JDBC objects.
			if (cm!=null) {
			        boolean isShutdown = thrownException instanceof CacheClosedException ;
			        if( !isShutdown) {
				        isShutdown = cleanupOnError(thrownException);
			        }else {
			          if(this.lcc != null) {
			            ((GemFireTransaction)this.lcc.getTransactionExecute()).release();
			          }
			        }
				if (checkForShutdown && isShutdown) {
					// Change the error message to be a known shutdown.
					thrownException = shutdownDatabaseException();
				}
			}



			return wrapInSQLException(thrownException);

		} catch (Throwable t) {

			if (cm!=null) { // something to let us cleanup?
				cm.cleanupOnError(t);
			}
			/*
			   We'd rather throw the Throwable,
			   but then javac complains...
			   We assume if we are in this degenerate
			   case that it is actually a java exception
			 */
			throw wrapInSQLException(t);
			//throw t;
		}

	}

    /**
     * Wrap a <code>Throwable</code> in an <code>SQLException</code>.
     *
     * @param thrownException a <code>Throwable</code>
     * @return <code>thrownException</code>, if it is an
     * <code>SQLException</code>; otherwise, an <code>SQLException</code> which
     * wraps <code>thrownException</code>
     */
	public static SQLException wrapInSQLException(Throwable thrownException) {

		if (thrownException == null)
			return null;

// GemStone changes BEGIN
		// check for JVM errors
		Error err;
		if (thrownException instanceof Error && SystemFailure
		    .isJVMFailureError(err = (Error)thrownException)) {
		  SystemFailure.initiateFailure(err);
		  // If the above ever returns, rethrow the error. We're
		  // poisoned now, so don't let this thread continue.
		  throw err;
		}
		SystemFailure.checkFailure();
		// also check for proper shutdown
		GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
		FabricService service = FabricServiceManager
		    .currentFabricServiceInstance();
		if (cache == null || cache.getCancelCriterion()
		    .cancelInProgress() != null || service == null
		    || service.status() == FabricService.State.STOPPING
		    || service.status() == FabricService.State.STOPPED
		    || Monitor.inShutdown()) {
		  final StandardException se;
		  if (thrownException instanceof SQLException
		      && ((SQLException)thrownException).getErrorCode() >=
		           ExceptionSeverity.SESSION_SEVERITY) {
		    return (SQLException)thrownException;
		  }
		  else if (thrownException instanceof StandardException
		      && ((StandardException)thrownException).getSeverity() >=
		           ExceptionSeverity.SESSION_SEVERITY) {
		    se = (StandardException)thrownException;
		  }
		  else {
		    se = StandardException.newException(
		        SQLState.SHUTDOWN_DATABASE, thrownException,
		        Attribute.GFXD_DBNAME);
		    se.setReport(StandardException.REPORT_NEVER);
		  }
		  // simple wrapper so that it can be unwrapped easily
		  return EmbedSQLException.wrapStandardException(se.getMessage(),
		      se.getMessageId(), se.getSeverity(), se);
		}
		// unwrap SQLException/StandardException wrapped
		// inside a GemFireXDRuntimeException, if any
		if (thrownException instanceof GemFireXDRuntimeException
		    && thrownException.getCause() != null) {
		  thrownException = thrownException.getCause();
		}
		Throwable cause = thrownException.getCause();
		if (cause != null &&
		    ((cause instanceof SQLException) &&
		    ((SQLException)cause).getSQLState() != null) ||
		    ((cause instanceof StandardException) &&
		    ((StandardException)cause).getSQLState() != null)) {
		  thrownException = cause;
		}
		DerbyIOException dioe;
		if (thrownException instanceof DerbyIOException && (dioe =
		    (DerbyIOException)thrownException).getSQLState() != null) {
		  return Util.getExceptionFactory().getSQLException(
		      dioe.getMessage(), dioe.getSQLState(), null,
		      StandardException.getSeverityFromIdentifier(
		          dioe.getSQLState()), dioe, null);
		}
//GemStone changes END
		if (thrownException instanceof SQLException) {
            // Server side JDBC can end up with a SQLException in the nested
            // stack. Return the exception with no wrapper.
            return (SQLException) thrownException;
		}

        if (thrownException instanceof StandardException) {

			StandardException se = (StandardException) thrownException;

            if (se.getCause() == null) {
                // se is a single, unchained exception. Just convert it to an
                // SQLException.
                return Util.generateCsSQLException(se);
            }

            // se contains a non-empty exception chain. We want to put all of
            // the exceptions (including Java exceptions) in the next-exception
            // chain. Therefore, call wrapInSQLException() recursively to
            // convert the cause chain into a chain of SQLExceptions.
// GemStone changes BEGIN
            // if this is a CancelException that got wrapped in unexpected
            // user exception, then ignore so it will be converted to a
            // shutdown exception later below
            if (!SQLState.LANG_UNEXPECTED_USER_EXCEPTION.equals(se.getMessageId())
                || !wrapsCancelException(se.getCause(), true)) {
              // original code loses any preLocalizedMessage and again expects
              // arguments; instead let StandardException format itself and set
              // the nextExceptions as in original code
              // avoid too much of wrapping (#42595)
              //return Util.seeNextException(se, wrapInSQLException(se.getCause()));
              return Util.generateCsSQLException(se);
            }
        }

        // convert a GemFire CancelException to an SQL shutdown exception
        if (wrapsCancelException(thrownException, false)) {
          SQLException sqle = checkTransactionNodeFailureException(
              thrownException);
          if (sqle != null) {
            return sqle;
          }
          final StandardException se = StandardException.newException(
              SQLState.SHUTDOWN_DATABASE, thrownException, Attribute.GFXD_DBNAME);
          se.setReport(StandardException.REPORT_NEVER);
          // simple wrapper so that it can be unwrapped easily
          return EmbedSQLException.wrapStandardException(se.getMessage(), se
              .getMessageId(), se.getSeverity(), se);
        }
        else if (GemFireXDUtils.retryToBeDone(thrownException)) {
          SQLException sqle = checkTransactionNodeFailureException(
              thrownException);
          if (sqle != null) {
            return sqle;
          }
          final StandardException se = StandardException
              .newException(SQLState.GFXD_NODE_SHUTDOWN, thrownException, null,
                  "handleException");
          // simple wrapper so that it can be unwrapped easily
          return EmbedSQLException.wrapStandardException(se.getMessage(), se
              .getMessageId(), se.getSeverity(), se);
        }
        else if (thrownException instanceof GemFireException) {
          // check for any wrapped SQLException
          Throwable t = thrownException;
          while ((t = t.getCause()) != null) {
            if (t instanceof SQLException) {
              return (SQLException)t;
            }
          }
          StandardException se = Misc.processKnownGemFireException(
              (GemFireException)thrownException, thrownException,
              "handleException", false);
          if (se != null) {
            return Util.generateCsSQLException(se);
          }
        }
        // thrownException is a Java exception
        return Util.javaException(thrownException);
	}

	private static boolean wrapsCancelException(Throwable thrownException
	    , boolean checkRemote) {
	  return GemFireXDUtils.nodeFailureException(thrownException,
	      checkRemote);
	}

	private static StandardException wrapsTransactionException(
	    Throwable thrownException) {
	  Throwable cause = thrownException;
	  do {
	    if (cause instanceof com.gemstone.gemfire.cache.ConflictException) {
	      return StandardException.newException(SQLState
	          .GFXD_OPERATION_CONFLICT, thrownException, cause.getMessage());
	    }
	    if (cause instanceof com.gemstone.gemfire.cache.LockTimeoutException) {
	      return StandardException.newException(SQLState.LOCK_TIMEOUT,
	          thrownException);
	    }
	    if (cause instanceof com.gemstone.gemfire.cache.TransactionInDoubtException) {
	      return StandardException.newException(SQLState
	          .GFXD_TRANSACTION_INDOUBT, thrownException);
	    }
	    if (cause instanceof TransactionDataRebalancedException) {
	      TransactionDataRebalancedException tdre =
	          (TransactionDataRebalancedException)cause;
	      return StandardException.newException(SQLState
	          .DATA_CONTAINER_VANISHED, thrownException,
	          Misc.getFullTableNameFromRegionPath(tdre.getRegionPath()));
	    }
	    if (cause instanceof TransactionDataNodeHasDepartedException) {
              return StandardException.newException(SQLState
                  .DATA_CONTAINER_CLOSED, thrownException, StandardException
                      .getSenderFromExceptionOrSelf(thrownException), "");
	    }
	    if (cause instanceof com.gemstone.gemfire.cache.IllegalTransactionStateException) {
	      return StandardException.newException(SQLState
	          .GFXD_TRANSACTION_ILLEGAL, thrownException,
	          cause.getLocalizedMessage());
	    }
	    if (cause instanceof com.gemstone.gemfire.cache.TransactionStateReadOnlyException) {
	      return StandardException.newException(SQLState
	          .GFXD_TRANSACTION_READ_ONLY, thrownException,
	          cause.getLocalizedMessage());
	    }
	  } while ((cause = cause.getCause()) != null);
	  return null;
	}

	private static SQLException checkTransactionNodeFailureException(
	    Throwable thrownException) {
          if (TXManagerImpl.getCurrentTXState() != null) {
            return Util.generateCsSQLException(StandardException.newException(
                SQLState.DATA_CONTAINER_CLOSED, thrownException,
                StandardException.getSenderFromExceptionOrSelf(
                    thrownException), ""));
          }
          else {
            return null;
          }
	}
        /* (original code)
            return Util.seeNextException(se.getMessageId(),
                        se.getArguments(), wrapInSQLException(se.getCause()));
        }
        // thrownException is a Java exception
        return Util.javaException(thrownException);
	}
	*/
// GemStone changes END

	/*
	 * TransactionResource methods
	 */

	String getUserName() {
		return  username;
	}

	boolean cleanupOnError(Throwable e)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT(cm != null, "cannot cleanup on error with null context manager");

// GemStone changes BEGIN
		try {
		  return cm.cleanupOnError(e);
                } finally {
                  // for GemFireXD TX level NONE explicitly release locks even
                  // when auto-commit is not true
                  final LanguageConnectionContext lcc = getLcc();
                  if (lcc != null && !lcc.isConnectionForRemote()) {
                    TransactionController tc = lcc.getTransactionExecute();
                    if (!tc.isTransactional()) {
                      tc.releaseAllLocks(false, false);
                    }
                  }
                  // if database is not fully up.
                  if (getDatabase() != null && !getDatabase().isActive()) {
                    getDatabase().cleanupOnError(e);
                  }
                }
		/* (original code) return cm.cleanupOnError(e); */
// GemStone changes END
	}

	boolean isIdle()
	{
		// If no lcc, there is no transaction.
		return (lcc == null || lcc.getTransactionExecute().isIdle());
	}


	/*
	 * class specific methods
	 */


	/* 
	 * is the underlaying database still active?
	 */
	boolean isActive()
	{
		// database is null at connection open time
		return (driver.isActive() && ((database == null) || database.isActive()));
	}
	
	public final boolean forMetadataPrepare() {
	  return enableMetadataPrepare;
	}

}


