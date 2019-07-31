/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection

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

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Struct;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

// GemStone changes BEGIN
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.TransactionFlag;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.Checkpoint;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTLongObjectHashMap;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.FinalizeHolder;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.TLongArrayList;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.distributed.DistributedConnectionCloseExecutorFunction;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.StatementQueryExecutor;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.sql.conn.ConnectionSignaller;
import com.pivotal.gemfirexd.internal.engine.sql.conn.ConnectionState;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
// GemStone changes END
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.db.Database;
import com.pivotal.gemfirexd.internal.iapi.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.iapi.error.SQLWarningFactory;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.AuthenticationService;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineConnection;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineLOB;
import com.pivotal.gemfirexd.internal.iapi.reference.Attribute;
import com.pivotal.gemfirexd.internal.iapi.reference.MessageId;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.memory.LowMemory;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.access.XATransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.replication.master.MasterFactory;
import com.pivotal.gemfirexd.internal.iapi.store.replication.slave.SlaveFactory;
import com.pivotal.gemfirexd.internal.impl.db.SlaveDatabase;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.NoneAuthenticationServiceImpl;
import com.pivotal.gemfirexd.internal.impl.sql.GenericStatement;
import com.pivotal.gemfirexd.internal.impl.sql.conn.GenericLanguageConnectionContext;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;
/**
 * Local implementation of Connection for a JDBC driver in 
 * the same process as the database.
 * <p> 
 * There is always a single root (parent) connection.  The
 * initial JDBC connection is the root connection. A
 * call to <I>getCurrentConnection()</I> or with the URL 
 * <I>jdbc:default:connection</I> yields a nested connection that shares
 * the same root connection as the parent.  A nested connection
 * is implemented using this class.  The nested connection copies the 
 * state of the parent connection and shares some of the same 
 * objects (e.g. ContextManager) that are shared across all
 * nesting levels.  The proxy also maintains its own
 * state that is distinct from its parent connection (e.g.
 * autocommit or warnings).
 * <p>
 * <B>SYNCHRONIZATION</B>: Just about all JDBC actions are
 * synchronized across all connections stemming from the
 * same root connection.  The synchronization is upon
 * the a synchronized object return by the rootConnection.
   <P><B>Supports</B>
   <UL>
  <LI> JDBC 2.0
   </UL>
 * 
 *
 * @see TransactionResourceImpl
 *
 */
public abstract class EmbedConnection implements EngineConnection
{

	private static final StandardException exceptionClose = StandardException.closeException();
    
    /**
     * Static exception to be thrown when a Connection request can not
     * be fulfilled due to lack of memory. A static exception as the lack
     * of memory would most likely cause another OutOfMemoryException and
     * if there is not enough memory to create the OOME exception then something
     * like the VM dying could occur. Simpler just to throw a static.
     */
    public static final SQLException NO_MEM =
        Util.generateCsSQLException(SQLState.LOGIN_FAILED, "java.lang.OutOfMemoryError");
    
    /**
     * Low memory state object for connection requests.
     */
    public static final LowMemory memoryState = new LowMemory();

	//////////////////////////////////////////////////////////
	// OBJECTS SHARED ACROSS CONNECTION NESTING
	//////////////////////////////////////////////////////////
	DatabaseMetaData dbMetadata;

	TransactionResourceImpl tr; // always access tr thru getTR()

	private ConcurrentTLongObjectHashMap<Object> lobHashMap = null;
	private int lobHMKey = 0;

    /**
     * Map to keep track of all the lobs associated with this
     * connection. These lobs will be cleared after the transaction
     * is no longer valid or when connection is closed
     */
    private WeakHashMap lobReferences = null;

	//////////////////////////////////////////////////////////
	// STATE (copied to new nested connections, but nesting
	// specific)
	//////////////////////////////////////////////////////////
// GemStone changes BEGIN
	private final FinalizeObject.State active;
	private FinalizeEmbedConnection finalizer;
	// default auto-commit is false for GemFireXD
	boolean autoCommit = GfxdConstants.GFXD_AUTOCOMMIT_DEFAULT;
	/* (original code)
	private boolean active;
	boolean	autoCommit = true;
	*/
	protected long beginTime;
// GemStone changes END
	boolean	needCommit;

	// Set to true if NONE authentication is being used
	private boolean usingNoneAuth;

	/*
     following is a new feature in JDBC3.0 where you can specify the holdability
     of a resultset at the end of the transaction. This gets set by the
	 new method setHoldability(int) in JDBC3.0
     * 
	 */
	private int	connectionHoldAbility = ResultSet.HOLD_CURSORS_OVER_COMMIT;


	//////////////////////////////////////////////////////////
	// NESTING SPECIFIC OBJECTS
	//////////////////////////////////////////////////////////
	/*
	** The root connection is the base connection upon
	** which all actions are synchronized.  By default,
	** we are the root connection unless we are created
	** by copying the state from another connection.
	*/
	final EmbedConnection rootConnection;
	private SQLWarning 		topWarning;
	/**	
		Factory for JDBC objects to be created.
	*/
	private InternalDriver factory;

	/**
		The Connection object the application is using when accessing the
		database through this connection. In most cases this will be equal
		to this. When Connection pooling is being used, then it will
		be set to the Connection object handed to the application.
		It is used for the getConnection() methods of various JDBC objects.
	*/
	private java.sql.Connection applicationConnection;

	/**
		An increasing counter to assign to a ResultSet on its creation.
		Used for ordering ResultSets returned from a procedure, always
		returned in order of their creation. Is maintained at the root connection.
	*/
	private int resultSetId;
    
    /** Cached string representation of the connection id */
    private String connString;

// GemStone changes BEGIN
    //Added by Asif
    //This keeps the information unique ID assosciated with the connection
    final private long  connID;
    final private long  incomingID;
    final boolean isRemoteConnection;
    public static final int UNINITIALIZED = -1;
    public static final int CHILD_NOT_CACHEABLE = -2;

    /**
     * For root connection, this will always be zero as of now. For
     * nestedConnection this will capture last value of
     * {@link java.sql.Statement#setQueryTimeout(int)}, so that any
     * statement/preparedStatement created henceforth using the nested connection
     * inherits QueryTimeOut of the original statement's timeout value.<br><br>
     * 
     * In other words, for stored procedures, this stores the timeout value for
     * the statement being executed from the parent connection (i.e. outer
     * CallableStatement)
     * 
     * @see GenericLanguageConnectionContext#lastStatementQueryTimeOutMillis
     */
    private final long defaultNestedConnQueryTimeOutMillis;
    
// GemStone changes END

	//////////////////////////////////////////////////////////
	// CONSTRUCTORS
	//////////////////////////////////////////////////////////

	// create a new Local Connection, using a new context manager
	//
//  GemStone changes BEGIN
	public EmbedConnection(InternalDriver driver, String url, Properties info,
	    long id, long incomingId, final boolean isRemote)
// GemStone changes END
		 throws SQLException
	{
		// Create a root connection.
		applicationConnection = rootConnection = this;
		factory = driver;
		tr = new TransactionResourceImpl(driver, url, info);

// GemStone changes BEGIN
		this.isRemoteConnection = isRemote;
		this.nestedConn = false;
		this.defaultNestedConnQueryTimeOutMillis = 0;
		this.clientConn = (info != null && "true".equalsIgnoreCase(
		    info.getProperty(com.pivotal.gemfirexd.internal.iapi.reference.Property.DRDA_CLIENT_CONNECTION)));
		this.active = new FinalizeObject.State((byte)1);
		/* (original code)
		active = true;
		*/
// GemStone changes END
		// register this thread and its context manager with
		// the global context service
		setupContextStack(true);

		try {

			// stick my context into the context manager
			EmbedConnectionContext context = pushConnectionContext(tr.getContextManager());

			// if we are shutting down don't attempt to boot or create the database
			boolean shutdown = Boolean.valueOf(info.getProperty(com.pivotal.gemfirexd.Attribute.SHUTDOWN_ATTR)).booleanValue();

			// see if database is already booted
			Database database = (Database) Monitor.findService(Property.DATABASE_MODULE, tr.getDBName());


			// See if user wants to create a new database.
			boolean	createBoot = createBoot(info);	

			// DERBY-2264: keeps track of whether we do a plain boot before an
			// (re)encryption or hard upgrade boot to (possibly) authenticate
			// first. We can not authenticate before we have booted, so in
			// order to enforce data base owner powers over encryption or
			// upgrade, we need a plain boot, then authenticate, then, if all
			// is well, boot with (re)encryption or upgrade.  Encryption at
			// create time is not checked.
			boolean isTwoPhaseEncryptionBoot = (!createBoot &&
												isEncryptionBoot(info));
			boolean isTwoPhaseUpgradeBoot = (!createBoot &&
											 isHardUpgradeBoot(info));
			boolean isStartSlaveBoot = isStartReplicationSlaveBoot(info);
            // Set to true if startSlave command is attempted on an
            // already booted database. Will raise an exception when
            // credentials have been verified
            boolean slaveDBAlreadyBooted = false;

            boolean isFailoverMasterBoot = false;
            boolean isFailoverSlaveBoot = false;

            // check that a replication operation is not combined with
            // other operations
            String replicationOp = getReplicationOperation(info);
            if (replicationOp!= null) {
                if (createBoot ||
                    shutdown ||
                    isTwoPhaseEncryptionBoot ||
                    isTwoPhaseUpgradeBoot) {
                    throw StandardException.
                        newException(SQLState.
                                     REPLICATION_CONFLICTING_ATTRIBUTES,
                                     replicationOp);
                }
            }

            if (isReplicationFailover(info)) {
                // Check that the database has been booted - otherwise throw 
                // exception.
                checkDatabaseBooted(database, 
                                    Attribute.REPLICATION_FAILOVER, 
                                    tr.getDBName());
                // The failover command is the same for master and slave 
                // databases. If the db is not in slave replication mode, we
                // assume that it is in master mode. If not in any replication 
                // mode, the connection attempt will fail with an exception
                if (database.isInSlaveMode()) {
                    isFailoverSlaveBoot = true;
                } else {
                    isFailoverMasterBoot = true;
                }
            }

			// Save original properties if we modified them for
			// two phase encryption or upgrade boot.
			Properties savedInfo = null;

            if (isStartSlaveBoot) {
                if (database != null) {
                    // If the slave database has already been booted,
                    // the command should fail. Setting
                    // slaveDBAlreadyBooted to true will cause an
                    // exception to be thrown, but not until after
                    // credentials have been verified so that db boot
                    // information is not exposed to unauthorized
                    // users
                    slaveDBAlreadyBooted = true;
                } else {
                    // We need to boot the slave database two times. The
                    // first boot will check authentication and
                    // authorization. The second boot will put the
                    // database in replication slave mode. SLAVE_PRE_MODE
                    // ensures that log records are not written to disk
                    // during the first boot. This is necessary because
                    // the second boot needs a log that is exactly equal
                    // to the log at the master.
                    info.setProperty(SlaveFactory.REPLICATION_MODE,
                                     SlaveFactory.SLAVE_PRE_MODE);
                }
            }

            if (isStopReplicationSlaveBoot(info)) {
                // DERBY-3383: stopSlave must be performed before
                // bootDatabase so that we don't accidentally boot the db
                // if stopSlave is requested on an unbooted db.
                // An exception is always thrown from this method. If
                // stopSlave is requested, we never get past this point
                handleStopReplicationSlave(database, info);
            } else if (isInternalShutdownSlaveDatabase(info)) {
                internalStopReplicationSlave(database, info);
                return;
            } else if (isFailoverSlaveBoot) {
                // For slave side failover, we perform failover before 
                // connecting to the db (tr.startTransaction further down sets
                // up the connection). If a connection had been
                // established first, the connection attempt would throw an 
                // exception saying that a database in slave mode cannot be 
                // connected to
                handleFailoverSlave(database);
                // db is no longer in slave mode - proceed with normal 
                // connection attempt
            }

			if (database != null)
			{
				// database already booted by someone else
				tr.setDatabase(database);
				isTwoPhaseEncryptionBoot = false;
				isTwoPhaseUpgradeBoot = false;
			}
			else if (!shutdown)
			{
				if (isTwoPhaseEncryptionBoot || isTwoPhaseUpgradeBoot) {
					savedInfo = info;
					info = removePhaseTwoProps((Properties)info.clone());
				}

				// Return false iff the monitor cannot handle a service of the
				// type indicated by the proptocol within the name.  If that's
				// the case then we are the wrong driver.

				if (!bootDatabase(info, isTwoPhaseUpgradeBoot))
				{
					tr.clearContextInError();
					setInactive();
					return;
				}
			}

			if (createBoot && !shutdown)
			{
				// if we are shutting down don't attempt to boot or create the
				// database

				if (tr.getDatabase() != null) {
					addWarning(SQLWarningFactory.newSQLWarning(SQLState.DATABASE_EXISTS, getDBName()));
				} else {

					// check for user's credential and authenticate the user
					// with system level authentication service.
					// FIXME: We should also check for CREATE DATABASE operation
					//		  authorization for the user if authorization was
					//		  set at the system level.
					//		  Right now, the authorization service does not
					//		  restrict/account for Create database op.
					checkUserCredentials(null, info);
					
					// Process with database creation
					database = createDatabase(tr.getDBName(), info);
					tr.setDatabase(database);
				}
			}


			if (tr.getDatabase() == null) {
				handleDBNotFound();
			}
// GemStone changes BEGIN
			// At this point the GemFireStore is booted. So it is
			// OK to get the Connection ID  from GFE system now.
			if (id == UNINITIALIZED) {
			  id = GemFireXDUtils.newUUID();
			}
// GemStone changes END

			// Check User's credentials and if it is a valid user of
			// the database
			//
            try {
                checkUserCredentials(tr.getDBName(), info);
            } catch (SQLException sqle) {
                if (isStartSlaveBoot && !slaveDBAlreadyBooted) {
                    // Failing credentials check on a previously
                    // unbooted db should not leave the db booted
                    // for startSlave command.

                    // tr.startTransaction is needed to get the
                    // Database context. Without this context,
                    // handleException will not shutdown the
                    // database
                    tr.startTransaction(id, isRemote);
                    handleException(tr.shutdownDatabaseException());
                }
                throw sqle;
            }

			// Make a real connection into the database, setup lcc, tc and all
			// the rest.
			tr.startTransaction(id, isRemote);

            if (isStartReplicationMasterBoot(info) ||
                isStopReplicationMasterBoot(info) ||
                isFailoverMasterBoot) {

                if (!usingNoneAuth &&
                    getLanguageConnection().usesSqlAuthorization()) {
                    // a failure here leaves database booted, but no
                    // operation has taken place and the connection is
                    // rejected.
                    checkIsDBOwner(OP_REPLICATION);
                }

                if (isStartReplicationMasterBoot(info)) {
                    handleStartReplicationMaster(tr, info);
                } else if (isStopReplicationMasterBoot(info)) {
                    handleStopReplicationMaster(tr, info);
                } else if (isFailoverMasterBoot) {
                    handleFailoverMaster(tr);
                }
            }

			if (isTwoPhaseEncryptionBoot ||
				isTwoPhaseUpgradeBoot ||
				isStartSlaveBoot) {

				// shutdown and boot again with encryption, upgrade or
				// start replication slave attributes active. This is
				// restricted to the database owner if authentication
				// and sqlAuthorization is on.
				if (!usingNoneAuth &&
						getLanguageConnection().usesSqlAuthorization()) {
					int operation;
					if (isTwoPhaseEncryptionBoot) {
						operation = OP_ENCRYPT;
					} else if (isTwoPhaseUpgradeBoot) {
						operation = OP_HARD_UPGRADE;
					} else {
						operation = OP_REPLICATION;
					}
					try {
						// a failure here leaves database booted, but no
						// (re)encryption has taken place and the connection is
						// rejected.
						checkIsDBOwner(operation);
					} catch (SQLException sqle) {
						if (isStartSlaveBoot) {
							// If authorization fails for the start
							// slave command, we want to shutdown the
							// database which is currently in the
							// SLAVE_PRE_MODE.
							handleException(tr.shutdownDatabaseException());
						}
						throw sqle;
					}
				}

				if (isStartSlaveBoot) {
					// Throw an exception if the database had been
					// booted before this startSlave connection attempt.
					if (slaveDBAlreadyBooted) {
						throw StandardException.newException(
						SQLState.CANNOT_START_SLAVE_ALREADY_BOOTED,
						getTR().getDBName());
					}

					// Let the next boot of the database be
					// replication slave mode
					info.setProperty(SlaveFactory.REPLICATION_MODE,
									 SlaveFactory.SLAVE_MODE);
					info.setProperty(SlaveFactory.SLAVE_DB,
									 getTR().getDBName());
				} else {
					// reboot using saved properties which
					// include the (re)encyption or upgrade attribute(s)
					info = savedInfo;
				}

                // Authentication and authorization done - shutdown
                // the database
				handleException(tr.shutdownDatabaseException());
				restoreContextStack();
				tr = new TransactionResourceImpl(driver, url, info);
// GemStone changes BEGIN
				this.active.state = 1;
				setupContextStack(false);
				/* (original code)
				active = true;
				setupContextStack();
				*/
// GemStone changes END
				context = pushConnectionContext(tr.getContextManager());

                // Reboot the database in the correct
                // encrypt/upgrade/slave replication mode
				if (!bootDatabase(info, false))
				{
					if (SanityManager.DEBUG) {
						SanityManager.THROWASSERT(
							"bootDatabase failed after initial plain boot " +
							"for (re)encryption or upgrade");
					}
					tr.clearContextInError();
					setInactive();
					return;
				}

				if (isStartSlaveBoot) {
					// We don't return a connection to the client who
					// called startSlave. Rather, we throw an
					// exception stating that replication slave mode
					// has been successfully started for the database
					throw StandardException.newException(
						SQLState.REPLICATION_SLAVE_STARTED_OK,
						getTR().getDBName());
				} else {
					// don't need to check user credentials again, did
					// that on first plain boot, so just start
					tr.startTransaction(id, isRemote);
				}
			}

			// now we have the database connection, we can shut down
			if (shutdown) {
				if (!usingNoneAuth &&
						getLanguageConnection().usesSqlAuthorization()) {
					// DERBY-2264: Only allow database owner to shut down if
					// authentication and sqlAuthorization is on.
					checkIsDBOwner(OP_SHUTDOWN);
				}
				throw tr.shutdownDatabaseException();
			}

			// Raise a warning in sqlAuthorization mode if authentication is not ON
			if (usingNoneAuth && getLanguageConnection().usesSqlAuthorization())
				addWarning(SQLWarningFactory.newSQLWarning(SQLState.SQL_AUTHORIZATION_WITH_NO_AUTHENTICATION));
// GemStone changes BEGIN
			if ((createBoot || database == null) && !shutdown) {
			  tr.getDatabase().postCreate(this, info);
			}
			
			if(!shutdown) {
			  if (tr.defaultSchema != null &&
			      !tr.defaultSchema.equals(tr.lcc.getCurrentSchemaName())) {
			    FabricDatabase.setupDefaultSchema(tr.lcc.getDataDictionary(), tr.lcc,
			        tr.lcc.getTransactionExecute(), tr.defaultSchema, true);
			  }
                            getLanguageConnection().setRunTimeStatisticsMode(
                                tr.getDatabase().getRuntimeStatistics(), false);
			}
                        this.beginTime = ConnectionStats.getStatTime();
			this.finalizer = new FinalizeEmbedConnection(this,
			    this.tr.cm, id, isRemote, this.active,
			    this.nestedConn, this.clientConn, this.beginTime);

// GemStone changes END
		}
        catch (OutOfMemoryError noMemory)
		{
			//System.out.println("freeA");
			restoreContextStack();
			tr.lcc = null;
// GemStone changes BEGIN
			if (tr.cm != null) {
			  ContextService.removeContextManager(tr.cm);
			  tr.cm = null;
			}
			/* (original code)
			tr.cm = null;
			*/
// GemStone changes END
			
			//System.out.println("free");
			//System.out.println(Runtime.getRuntime().freeMemory());
            memoryState.setLowMemory();
			
			//noMemory.printStackTrace();
			// throw Util.generateCsSQLException(SQLState.LOGIN_FAILED, noMemory.getMessage(), noMemory);
			throw NO_MEM;
		}
		catch (Throwable t) {
            if (t instanceof StandardException)
            {
                StandardException se = (StandardException) t;
                if (se.getSeverity() < ExceptionSeverity.SESSION_SEVERITY)
                    se.setSeverity(ExceptionSeverity.SESSION_SEVERITY);
            }
			tr.cleanupOnError(t);
			throw handleException(t);
		} finally {
                  this.connID = id;
                  this.incomingID = incomingId;
                  this.tr.restoreContextStack();
		}
	}

    /**
     * Check that a database has already been booted. Throws an exception 
     * otherwise
     *
     * @param database The database that should have been booted
     * @param operation The operation that requires that the database has 
     * already been booted, used in the exception message if not booted
     * @param dbname The name of the database that should have been booted, 
     * used in the exception message if not booted
     * @throws java.sql.SQLException thrown if database is not booted
     */
    private void checkDatabaseBooted(Database database,
                                     String operation, 
                                     String dbname) throws SQLException {
        if (database == null) {
            // Do not clear the TransactionResource context. It will
            // be restored as part of the finally clause of the constructor.
            this.setInactive();
            throw newSQLException(SQLState.REPLICATION_DB_NOT_BOOTED, 
                                  operation, dbname);
        }
    }

	/**
	  Examine the attributes set provided for illegal boot
	  combinations and determine if this is a create boot.

	  @return true iff the attribute <em>create=true</em> is provided. This
	  means create a standard database.  In other cases, returns
	  false.

	  @param p the attribute set.

	  @exception SQLException Throw if more than one of
	  <em>create</em>, <em>createFrom</em>, <em>restoreFrom</em> and
	  <em>rollForwardRecoveryFrom</em> is used simultaneously. <br>

	  Also, throw if (re)encryption is attempted with one of
	  <em>createFrom</em>, <em>restoreFrom</em> and
	  <em>rollForwardRecoveryFrom</em>.

	*/
	private boolean createBoot(Properties p) throws SQLException
	{
		int createCount = 0;

		if (Boolean.valueOf(p.getProperty(com.pivotal.gemfirexd.Attribute.CREATE_ATTR)).booleanValue())
			createCount++;

		int restoreCount=0;
		//check if the user has specified any /create/restore/recover from backup attributes.
		if (p.getProperty(Attribute.CREATE_FROM) != null)
			restoreCount++;
		if (p.getProperty(Attribute.RESTORE_FROM) != null)
			restoreCount++;
		if (p.getProperty(Attribute.ROLL_FORWARD_RECOVERY_FROM)!=null)
			restoreCount++;
		if(restoreCount > 1)
			throw newSQLException(SQLState.CONFLICTING_RESTORE_ATTRIBUTES);
	
        // check if user has specified re-encryption attributes in
        // combination with createFrom/restoreFrom/rollForwardRecoveryFrom
        // attributes.  Re-encryption is not
        // allowed when restoring from backup.
        if (restoreCount != 0 && isEncryptionBoot(p)) {
			throw newSQLException(SQLState.CONFLICTING_RESTORE_ATTRIBUTES);
        }


		//add the restore count to create count to make sure 
		//user has not specified and restore together by mistake.
		createCount = createCount + restoreCount ;

		//
		if (createCount > 1) throw newSQLException(SQLState.CONFLICTING_CREATE_ATTRIBUTES);
		
		//retuns true only for the  create flag not for restore flags
		return (createCount - restoreCount) == 1;
	}

    private void handleDBNotFound() throws SQLException {
        String dbname = tr.getDBName();
        // do not clear the TransactionResource context. It will be restored
        // as part of the finally clause of the object creator. 
        this.setInactive();
        throw newSQLException(SQLState.DATABASE_NOT_FOUND, dbname);
    }

	/**
	 * Examine boot properties and determine if a boot with the given
	 * attributes would entail an encryption operation.
	 *
	 * @param p the attribute set
	 * @return true if a boot will encrypt or re-encrypt the database
	 */
	private boolean isEncryptionBoot(Properties p)
	{
		return ((Boolean.valueOf(
					 p.getProperty(Attribute.DATA_ENCRYPTION)).booleanValue()) ||
				(p.getProperty(Attribute.NEW_BOOT_PASSWORD) != null)           ||
				(p.getProperty(Attribute.NEW_CRYPTO_EXTERNAL_KEY) != null));
	}

	/**
	 * Examine boot properties and determine if a boot with the given
	 * attributes would entail a hard upgrade.
	 *
	 * @param p the attribute set
	 * @return true if a boot will hard upgrade the database
	 */
	private boolean isHardUpgradeBoot(Properties p)
	{
		return Boolean.valueOf(
			p.getProperty(com.pivotal.gemfirexd.Attribute.UPGRADE_ATTR)).booleanValue();
	}

    private boolean isStartReplicationSlaveBoot(Properties p) {
        return ((Boolean.valueOf(
                 p.getProperty(Attribute.REPLICATION_START_SLAVE)).
                 booleanValue()));
    }

    private boolean isStartReplicationMasterBoot(Properties p) {
        return ((Boolean.valueOf(
                 p.getProperty(Attribute.REPLICATION_START_MASTER)).
                 booleanValue()));
    }
    
    /**
     * used to verify if the failover attribute has been set.
     * 
     * @param p The attribute set.
     * @return true if the failover attribute has been set.
     *         false otherwise.
     */
    private boolean isReplicationFailover(Properties p) {
        return ((Boolean.valueOf(
                 p.getProperty(Attribute.REPLICATION_FAILOVER)).
                 booleanValue()));
    }

    private boolean isStopReplicationMasterBoot(Properties p) {
        return ((Boolean.valueOf(
                 p.getProperty(Attribute.REPLICATION_STOP_MASTER)).
                 booleanValue()));
    }
    
    /**
     * Examine the boot properties and determine if a boot with the
     * given attributes should stop slave replication mode.
     * 
     * @param p The attribute set.
     * @return true if the stopSlave attribute has been set, false
     * otherwise.
     */
    private boolean isStopReplicationSlaveBoot(Properties p) {
        return Boolean.valueOf(
               p.getProperty(Attribute.REPLICATION_STOP_SLAVE)).
               booleanValue();
    }

    /**
     * Examine the boot properties and determine if a boot with the
     * given attributes should stop slave replication mode. A
     * connection with this property should only be made from
     * SlaveDatabase. Make sure to call
     * SlaveDatabase.verifyShutdownSlave() to verify that this
     * connection is not made from a client.
     * 
     * @param p The attribute set.
     * @return true if the shutdownslave attribute has been set, false
     * otherwise.
     */
    private boolean isInternalShutdownSlaveDatabase(Properties p) {
        return Boolean.valueOf(
               p.getProperty(Attribute.REPLICATION_INTERNAL_SHUTDOWN_SLAVE)).
               booleanValue();
    }

    private String getReplicationOperation(Properties p) 
        throws StandardException {

        String operation = null;
        int opcount = 0;
        if (isStartReplicationSlaveBoot(p)) {
            operation = Attribute.REPLICATION_START_SLAVE;
            opcount++;
        } 
        if (isStartReplicationMasterBoot(p)) {
            operation = Attribute.REPLICATION_START_MASTER;
            opcount++;
        }
        if (isStopReplicationSlaveBoot(p)) {
            operation = Attribute.REPLICATION_STOP_SLAVE;
            opcount++;
        }
        if (isInternalShutdownSlaveDatabase(p)) {
            operation = Attribute.REPLICATION_INTERNAL_SHUTDOWN_SLAVE;
            opcount++;
        }
        if (isStopReplicationMasterBoot(p)) {
            operation = Attribute.REPLICATION_STOP_MASTER;
            opcount++;
        } 
        if (isReplicationFailover(p)) {
            operation = Attribute.REPLICATION_FAILOVER;
            opcount++;
        }

        if (opcount > 1) {
            throw StandardException.
                newException(SQLState.REPLICATION_CONFLICTING_ATTRIBUTES,
                             operation);
        }
        return operation;
    }

    private void handleStartReplicationMaster(TransactionResourceImpl tr,
                                              Properties p)
        throws SQLException {

        // If authorization is turned on, we need to check if this
        // user is database owner.
        if (!usingNoneAuth &&
            getLanguageConnection().usesSqlAuthorization()) {
            checkIsDBOwner(OP_REPLICATION);
        }
        // TODO: If system privileges is turned on, we need to check
        // that the user has the replication privilege. Waiting for
        // Derby-2109

        // At this point, the user is properly authenticated,
        // authorized and has the correct system privilege to start
        // replication - depending on the security mechanisms
        // Derby is running under.

        String slavehost =
            p.getProperty(Attribute.REPLICATION_SLAVE_HOST);

        if (slavehost == null) {
            // slavehost is required attribute.
            SQLException wrappedExc =
                newSQLException(SQLState.PROPERTY_MISSING,
                                Attribute.REPLICATION_SLAVE_HOST);
            throw newSQLException(SQLState.LOGIN_FAILED, wrappedExc);
        }

        String portString =
            p.getProperty(Attribute.REPLICATION_SLAVE_PORT);
        int slaveport = -1; // slaveport < 0 will use the default port
        if (portString != null) {
            slaveport = Integer.valueOf(portString).intValue();
        }

        tr.getDatabase().startReplicationMaster(getTR().getDBName(),
                                                slavehost,
                                                slaveport,
                                                MasterFactory.
                                                ASYNCHRONOUS_MODE);
    }

    private void handleStopReplicationMaster(TransactionResourceImpl tr,
                                             Properties p)
        throws SQLException {

        // If authorization is turned on, we need to check if this
        // user is database owner.
        if (!usingNoneAuth &&
            getLanguageConnection().usesSqlAuthorization()) {
            checkIsDBOwner(OP_REPLICATION);
        }
        // TODO: If system privileges is turned on, we need to check
        // that the user has the replication privilege. Waiting for
        // Derby-2109

        // At this point, the user is properly authenticated,
        // authorized and has the correct system privilege to start
        // replication - depending on the security mechanisms
        // Derby is running under.

        tr.getDatabase().stopReplicationMaster();
    }

    /**
     * Stop replication slave when called from a client. Stops
     * replication slave mode, provided that the database is in
     * replication slave mode and has lost connection with the master
     * database. If the connection with the master is up, the call to
     * this method will be refused by raising an exception. The reason
     * for refusing the stop command if the slave is connected with
     * the master is that we cannot authenticate the user on the slave
     * side (because the slave database has not been fully booted)
     * whereas authentication is not a problem on the master side. If
     * not refused, this operation will cause SlaveDatabase to call
     * internalStopReplicationSlave
     *
     * @param database The database the stop slave operation will be
     * performed on
     * @param p The Attribute set.
     * @exception StandardException Thrown on error, if not in replication 
     * slave mode or if the network connection with the master is not down
     * @exception SQLException Thrown if the database has not been
     * booted or if stopSlave is performed successfully
     */
    private void handleStopReplicationSlave(Database database, Properties p)
        throws StandardException, SQLException {

        // We cannot check authentication and authorization for
        // databases in slave mode since the AuthenticationService has
        // not been booted for the database

        // Cannot get the database by using getTR().getDatabase()
        // because getTR().setDatabase() has not been called in the
        // constructor at this point.

        // Check that the database has been booted - otherwise throw exception
        checkDatabaseBooted(database, Attribute.REPLICATION_STOP_SLAVE, 
                            tr.getDBName());

        database.stopReplicationSlave();
        // throw an exception to the client
        throw newSQLException(SQLState.REPLICATION_SLAVE_SHUTDOWN_OK,
                              getTR().getDBName());
    }

    /**
     * Stop replication slave when called from SlaveDatabase. Called
     * when slave replication mode has been stopped, and all that
     * remains is to shutdown the database. This happens if
     * handleStopReplicationSlave has successfully requested the slave
     * to stop, if the replication master has requested the slave to
     * stop using the replication network, or if a fatal exception has
     * occurred in the database.
     *    
     * @param database The database the internal stop slave operation
     * will be performed on
     * @param p The Attribute set.
     * @exception StandardException Thrown on error or if not in replication 
     * slave mode
     * @exception SQLException Thrown if the database has not been
     * booted or if this connection was not made internally from
     * SlaveDatabase
     */
    private void internalStopReplicationSlave(Database database, Properties p)
        throws StandardException, SQLException {

        // We cannot check authentication and authorization for
        // databases in slave mode since the AuthenticationService has
        // not been booted for the database

        // Cannot get the database by using getTR().getDatabase()
        // because getTR().setDatabase() has not been called in the
        // constructor at this point.

        // Check that the database has been booted - otherwise throw exception.
        checkDatabaseBooted(database, 
                            Attribute.REPLICATION_INTERNAL_SHUTDOWN_SLAVE,
                            tr.getDBName());

        // We should only get here if the connection is made from
        // inside SlaveDatabase. To verify, we ask SlaveDatabase
        // if it requested this shutdown. If it didn't,
        // verifyShutdownSlave will throw an exception
        if (! (database instanceof SlaveDatabase)) {
            throw newSQLException(SQLState.REPLICATION_NOT_IN_SLAVE_MODE);
        }
        ((SlaveDatabase)database).verifyShutdownSlave();

        // Will shutdown the database without writing to the log
        // since the SQLException with state
        // REPLICATION_SLAVE_SHUTDOWN_OK will be reported anyway
        handleException(tr.shutdownDatabaseException());
    }
    
    /**
     * Used to authorize and verify the privileges of the user and
     * initiate failover.
     * 
     * @param tr an instance of TransactionResourceImpl Links the connection 
     *           to the database.
     * @throws StandardException 1) If the failover succeeds, an exception is
     *                              thrown to indicate that the master database
     *                              was shutdown after a successful failover
     *                           2) If a failure occurs during network
     *                              communication with slave.
     * @throws SQLException      1) Thrown upon a authorization failure.
     */
    private void handleFailoverMaster(TransactionResourceImpl tr)
        throws SQLException, StandardException {

        // If authorization is turned on, we need to check if this
        // user is database owner.
        if (!usingNoneAuth &&
            getLanguageConnection().usesSqlAuthorization()) {
            checkIsDBOwner(OP_REPLICATION);
        }
        // TODO: If system privileges is turned on, we need to check
        // that the user has the replication privilege. Waiting for
        // Derby-2109

        // At this point, the user is properly authenticated,
        // authorized and has the correct system privilege to initiate 
        // failover - depending on the security mechanisms
        // Derby is running under.

        tr.getDatabase().failover(tr.getDBName());
    }

    /**
     * Used to perform failover on a database in slave replication
     * mode. Performs failover, provided that the database is in
     * replication slave mode and has lost connection with the master
     * database. If the connection with the master is up, the call to
     * this method will be refused by raising an exception. The reason
     * for refusing the failover command if the slave is connected
     * with the master is that we cannot authenticate the user on the
     * slave side (because the slave database has not been fully
     * booted) whereas authentication is not a problem on the master
     * side. If not refused, this method will apply all operations
     * received from the master and complete the booting of the
     * database so that it can be connected to.
     * 
     * @param database The database the failover operation will be
     * performed on
     * @exception SQLException Thrown on error, if not in replication 
     * slave mode or if the network connection with the master is not down
     */
    private void handleFailoverSlave(Database database)
        throws SQLException {

        // We cannot check authentication and authorization for
        // databases in slave mode since the AuthenticationService has
        // not been booted for the database

        try {
            database.failover(getTR().getDBName());
        } catch (StandardException se) {
            throw Util.generateCsSQLException(se);
        }
    }
	/**
	 * Remove any encryption or upgarde properties from the given properties
	 *
	 * @param p the attribute set
	 * @return clone sans encryption properties
	 */
	private Properties removePhaseTwoProps(Properties p)
	{
		p.remove(Attribute.DATA_ENCRYPTION);
		p.remove(Attribute.NEW_BOOT_PASSWORD);
		p.remove(Attribute.NEW_CRYPTO_EXTERNAL_KEY);
		p.remove(com.pivotal.gemfirexd.Attribute.UPGRADE_ATTR);
		return p;
	}


	/**
	 * Create a new connection based off of the 
	 * connection passed in.  Initializes state
	 * based on input connection, and copies 
	 * appropriate object pointers. This is only used
	   for nested connections.
	 *
	 * @param inputConnection the input connection
	 */
	public EmbedConnection(EmbedConnection inputConnection) 
	{
// GemStone changes BEGIN
	  //TODO:Asif: Verify the correctness of it?
	  this.connID = inputConnection.connID;
          this.incomingID = inputConnection.incomingID;
	  this.isRemoteConnection = inputConnection.isRemoteConnection;
	  this.nestedConn = true;
	  this.clientConn = false;
// GemStone changes END
		if (SanityManager.DEBUG)
		{
// GemStone changes BEGIN
		  SanityManager.ASSERT(inputConnection.active.state == 1,
		  /* (original code)
			SanityManager.ASSERT(inputConnection.active,
		  */
// GemStone changes END
			"trying to create a proxy for an inactive conneciton");
		}

		// Proxy connections are always autocommit false
		// thus needCommit is irrelavent.
		autoCommit = false;


		/*
		** Nesting specific state we are copying from 
		** the inputConnection
		*/

		/*
		** Objects we are sharing across nestings
		*/
		// set it to null to allow it to be final.
		tr = null;			// a proxy connection has no direct
								// pointer to the tr.  Every call has to go
								// thru the rootConnection's tr.
		this.active = new FinalizeObject.State((byte)1);
		this.rootConnection = inputConnection.rootConnection;
		this.applicationConnection = this;
		this.factory = inputConnection.factory;

		//if no holdability specified for the resultset, use the holability
		//defined for the connection
		this.connectionHoldAbility = inputConnection.connectionHoldAbility;

		//RESOLVE: although it looks like the right
		// thing to share the metadata object, if
		// we do we'll get the wrong behavior on
		// getCurrentConnection().getMetaData().isReadOnly()
		// so don't try to be smart and uncomment the
		// following.  Ultimately, the metadata should
		// be shared by all connections anyway.
		//dbMetadata = inputConnection.dbMetadata;

		this.defaultNestedConnQueryTimeOutMillis = this.rootConnection
            .getLanguageConnectionContext().getQueryTimeOutForLastUsedStatement();
	}

	//
	// Check passed-in user's credentials.
	//
	private void checkUserCredentials(String dbname,
									  Properties userInfo)
	  throws SQLException
	{
		if (SanityManager.DEBUG_ASSERT)
			SanityManager.ASSERT(!isClosed(), "connection is closed");

		// If a database name was passed-in then check user's credential
		// in that database using the database's authentication service,
		// otherwise check if it is a valid user in the JBMS system.
		//
		// NOTE: We always expect an authentication service per database
		// and one at the system level.
		//
		AuthenticationService authenticationService = null;

        try {
            // Retrieve appropriate authentication service handle
            if (dbname == null)
                authenticationService =
                    getLocalDriver().getAuthenticationService();
            else
                authenticationService =
                    getTR().getDatabase().getAuthenticationService();

        } catch (StandardException se) {
            throw Util.generateCsSQLException(se);
        }

		// check that we do have a authentication service
		// it is _always_ expected.
		if (authenticationService == null)
		{
			String failedString = MessageService.getTextMessage(
				(dbname == null) ? MessageId.AUTH_NO_SERVICE_FOR_SYSTEM : MessageId.AUTH_NO_SERVICE_FOR_DB);

			throw newSQLException(SQLState.LOGIN_FAILED, failedString);
		}
		
		// Let's authenticate now
			
		String failure;
		if ((failure = authenticationService.authenticate(
											   dbname,
											   userInfo
											   )) != null) {

			throw newSQLException(SQLState.NET_CONNECT_AUTH_FAILED,
                     MessageService.getTextMessage(MessageId.AUTH_INVALID, failure));

		}

		// If authentication is not on, we have to raise a warning if sqlAuthorization is ON
		// Since NoneAuthenticationService is the default for Derby, it should be ok to refer
		// to its implementation here, since it will always be present.
		if (authenticationService instanceof NoneAuthenticationServiceImpl)
			usingNoneAuth = true;
	}

	/* Enumerate operations controlled by database owner powers */
	private static final int OP_ENCRYPT = 0;
	private static final int OP_SHUTDOWN = 1;
	private static final int OP_HARD_UPGRADE = 2;
	private static final int OP_REPLICATION = 3;
	/**
	 * Check if actual authenticationId is equal to the database owner's.
	 *
	 * @param operation attempted operation which needs database owner powers
	 * @throws SQLException if actual authenticationId is different
	 * from authenticationId of database owner.
	 */
	private void checkIsDBOwner(int operation) throws SQLException
	{
		final LanguageConnectionContext lcc = getLanguageConnection();
		final String actualId = lcc.getAuthorizationId();
		final String dbOwnerId = lcc.getDataDictionary().
			getAuthorizationDatabaseOwner();
		if (!actualId.equals(dbOwnerId)) {
			switch (operation) {
			case OP_ENCRYPT:
				throw newSQLException(SQLState.AUTH_ENCRYPT_NOT_DB_OWNER,
									  actualId, tr.getDBName());
			case OP_SHUTDOWN:
				throw newSQLException(SQLState.AUTH_SHUTDOWN_NOT_DB_OWNER,
									  actualId, tr.getDBName());
			case OP_HARD_UPGRADE:
				throw newSQLException(SQLState.AUTH_HARD_UPGRADE_NOT_DB_OWNER,
									  actualId, tr.getDBName());
			case OP_REPLICATION:
				throw newSQLException(SQLState.AUTH_REPLICATION_NOT_DB_OWNER,
									  actualId, tr.getDBName());
			default:
				if (SanityManager.DEBUG) {
					SanityManager.THROWASSERT(
						"illegal checkIsDBOwner operation");
				}
				throw newSQLException(
					SQLState.AUTH_DATABASE_CONNECTION_REFUSED);
			}
		}
	}

    /**
     * Gets the EngineType of the connected database.
     *
     * @return 0 if there is no database, the engine type otherwise. @see com.pivotal.gemfirexd.internal.iapi.reference.EngineType
     */
    public int getEngineType()
    {
        Database db = getDatabase();

        if( null == db)
            return 0;
        return db.getEngineType();
    }
    
	/*
	** Methods from java.sql.Connection
	*/

    /**
	 * SQL statements without parameters are normally
     * executed using Statement objects. If the same SQL statement 
     * is executed many times, it is more efficient to use a 
     * PreparedStatement
     *
     * JDBC 2.0
     *
     * Result sets created using the returned Statement will have
     * forward-only type, and read-only concurrency, by default.
     *
     * @return a new Statement object 
     * @exception SQLException if a database-access error occurs.
     */
	public final Statement createStatement() throws SQLException 
	{
// GemStone changes BEGIN
                long id  = GemFireXDUtils.newUUID();
		return createStatement(ResultSet.TYPE_FORWARD_ONLY,
							   ResultSet.CONCUR_READ_ONLY,
							   connectionHoldAbility, id);
// GemStone changes END
	}
    /**
     * JDBC 2.0
     *
     * Same as createStatement() above, but allows the default result set
     * type and result set concurrency type to be overridden.
     *
     * @param resultSetType a result set type, see ResultSet.TYPE_XXX
     * @param resultSetConcurrency a concurrency type, see ResultSet.CONCUR_XXX
     * @return a new Statement object 
      * @exception SQLException if a database-access error occurs.
    */
    public final Statement createStatement(int resultSetType,
    								 int resultSetConcurrency) 
						throws SQLException
	{
//    GemStone changes BEGIN
                long id  = GemFireXDUtils.newUUID();
		return createStatement(resultSetType, resultSetConcurrency,
			    connectionHoldAbility,id);
//     GemStone changes END
	}
//  GemStone changes BEGIN
    private boolean internalConn;
    private boolean skipCloseStats;
    private final boolean clientConn;
    private final boolean nestedConn;

    public final void setInternalConnection() {
      this.internalConn = true;
      FinalizeEmbedConnection finalizer = this.finalizer;
      if (finalizer != null) {
        finalizer.internalConn = true;
      }
    }

    public final boolean isInternalConnection() {
      return this.internalConn;
    }

    public final boolean isClientConnection() {
      return this.clientConn;
    }

    public final boolean isNestedConnection() {
      return this.nestedConn;
    }

    public final void setSkipCloseStats() {
      this.skipCloseStats = true;
      FinalizeEmbedConnection finalizer = this.finalizer;
      if (finalizer != null) {
        finalizer.skipCloseStats = true;
      }
    }

    /**
     * JDBC 3.0
     *
     * Same as createStatement() above, but allows the default result set
     * type, result set concurrency type and result set holdability type to
     * be overridden.
     *
     * @param resultSetType a result set type, see ResultSet.TYPE_XXX
     * @param resultSetConcurrency a concurrency type, see ResultSet.CONCUR_XXX
     * @param resultSetHoldability a holdability type,
     *  ResultSet.HOLD_CURSORS_OVER_COMMIT or ResultSet.CLOSE_CURSORS_AT_COMMIT
     * @return a new Statement object
     * @exception SQLException if a database-access error occurs.
     */
    public final Statement createStatement(int resultSetType,
                                                                 int resultSetConcurrency,
                                                                 int resultSetHoldability)
                                                throws SQLException
        {
            long id = GemFireXDUtils.newUUID();
           return this.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability, id);
      
        }
//  GemStone changes END

    /**
     * JDBC 3.0
     *
     * Same as createStatement() above, but allows the default result set
     * type, result set concurrency type and result set holdability type to
     * be overridden.
     *
     * @param resultSetType a result set type, see ResultSet.TYPE_XXX
     * @param resultSetConcurrency a concurrency type, see ResultSet.CONCUR_XXX
     * @param resultSetHoldability a holdability type,
     *  ResultSet.HOLD_CURSORS_OVER_COMMIT or ResultSet.CLOSE_CURSORS_AT_COMMIT
     * @return a new Statement object
     * @exception SQLException if a database-access error occurs.
     */
    public final Statement createStatement(int resultSetType,
    								 int resultSetConcurrency,
// GemStone changes BEGIN
    								 int resultSetHoldability,long id)
// GemStone changes END
						throws SQLException
	{
		checkIfClosed();
//              GemStone changes BEGIN
             

		return factory.newEmbedStatement(this, false,
			setResultSetType(resultSetType), resultSetConcurrency,
			resultSetHoldability, id);
//              GemStone changes END
	}

    /**
     * A SQL statement with or without IN parameters can be
     * pre-compiled and stored in a PreparedStatement object. This
     * object can then be used to efficiently execute this statement
     * multiple times.
     *
     * <P><B>Note:</B> This method is optimized for handling
     * parametric SQL statements that benefit from precompilation. If
     * the driver supports precompilation, prepareStatement will send
     * the statement to the database for precompilation. Some drivers
     * may not support precompilation. In this case, the statement may
     * not be sent to the database until the PreparedStatement is
     * executed.  This has no direct affect on users; however, it does
     * affect which method throws certain SQLExceptions.
     *
     * JDBC 2.0
     *
     * Result sets created using the returned PreparedStatement will have
     * forward-only type, and read-only concurrency, by default.
     *
     * @param sql a SQL statement that may contain one or more '?' IN
     * parameter placeholders
     * @return a new PreparedStatement object containing the
     * pre-compiled statement 
     * @exception SQLException if a database-access error occurs.
     * 
     * Set rootID = 0 and statementLevel = 0
     */
    public final PreparedStatement prepareStatement(String sql)
	    throws SQLException
	{
//    GemStone changes BEGIN
      long id = GemFireXDUtils.newUUID();
		return prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,
			ResultSet.CONCUR_READ_ONLY,
			connectionHoldAbility,
			Statement.NO_GENERATED_KEYS,
			null,
			null,true /* is Query (Parse) Node */,id, false/*needGfxdSubactivation*/, true/* flattenSubquery*/,
			false, null, 0, 0);
//    GemStone changes END
	}
    
      // GemStone changes BEGIN
      /**
       * JDBC 2.0
       * 
       * Same as prepareStatement() above, but allows change in query node
       * parameter.
       * 
       * @return a new PreparedStatement object containing the pre-compiled SQL
       *         statement
       * @exception SQLException
       *              if a database-access error occurs.
       * 
       * Set rootID = 0 and statementLevel = 0
       */
      public final PreparedStatement prepareStatement(String sql,
          boolean isQueryNode) throws SQLException {
        long id = GemFireXDUtils.newUUID();
        return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY, connectionHoldAbility,
            Statement.NO_GENERATED_KEYS, null, null, isQueryNode, id,
            false/*needGfxdSubactivation*/, true/* flattenSubquery*/, false,
            null, 0, 0);
      }
      // GemStone changes END
    
    /**
     * JDBC 2.0
     *
     * Same as prepareStatement() above, but allows the default result set
     * type and result set concurrency type to be overridden.
     *
     * @param resultSetType a result set type, see ResultSet.TYPE_XXX
     * @param resultSetConcurrency a concurrency type, see ResultSet.CONCUR_XXX
     * @return a new PreparedStatement object containing the
     * pre-compiled SQL statement
     * @exception SQLException if a database-access error occurs.
     * 
     * Set rootID = 0 and statementLevel = 0
     */
    public final PreparedStatement prepareStatement(String sql, int resultSetType,
					int resultSetConcurrency)
	    throws SQLException
	{
//    GemStone changes BEGIN
      long id = GemFireXDUtils.newUUID();
		return prepareStatement(sql,
			resultSetType,
			resultSetConcurrency,
			connectionHoldAbility,
			Statement.NO_GENERATED_KEYS,
			null,
			null,true /* is Query (Parse) Node */,id,false /*needGfxdSubactivation*/,true /*flattenSubquery*/,
			false, null, 0, 0);
//              GemStone changes END
	}

    /**
     * JDBC 3.0
     *
     * Same as prepareStatement() above, but allows the default result set
     * type, result set concurrency type and result set holdability
     * to be overridden.
     *
     * @param resultSetType a result set type, see ResultSet.TYPE_XXX
     * @param resultSetConcurrency a concurrency type, see ResultSet.CONCUR_XXX
     * @param resultSetHoldability - one of the following ResultSet constants:
     *  ResultSet.HOLD_CURSORS_OVER_COMMIT or ResultSet.CLOSE_CURSORS_AT_COMMIT
     * @return a new PreparedStatement object containing the
     *  pre-compiled SQL statement
     * @exception SQLException if a database-access error occurs.
     * 
     * Set rootID = 0 and statementLevel = 0
     */
    public final PreparedStatement prepareStatement(String sql, int resultSetType,
					int resultSetConcurrency, int resultSetHoldability)
	    throws SQLException
	{
//    GemStone changes BEGIN
      long id = GemFireXDUtils.newUUID();
		return prepareStatement(sql,
			resultSetType,
			resultSetConcurrency,
			resultSetHoldability,
			Statement.NO_GENERATED_KEYS,
			null,
			null,true /* is Query (Parse) Node */,id, false /*needGfxdSubactivation*/, true/*flattenSubquery*/,
			false, null, 0, 0);
//              GemStone changes END
	}


	/**
	 * Creates a default PreparedStatement object capable of returning
	 * the auto-generated keys designated by the given array. This array contains
	 * the indexes of the columns in the target table that contain the auto-generated
	 * keys that should be made available. This array is ignored if the SQL statement
	 * is not an INSERT statement

		JDBC 3.0
	 *
	 *
	 * @param sql  An SQL statement that may contain one or more ? IN parameter placeholders
	 * @param columnIndexes  An array of column indexes indicating the columns
	 *  that should be returned from the inserted row or rows
	 *
	 * @return  A new PreparedStatement object, containing the pre-compiled
	 *  SQL statement, that will have the capability of returning auto-generated keys
	 *  designated by the given array of column indexes
	 *
	 * @exception SQLException  Thrown on error.
	 * 
	 * Set rootID = 0 and statementLevel = 0
	 */
	public final PreparedStatement prepareStatement(
			String sql,
			int[] columnIndexes)
    throws SQLException
	{
//      GemStone changes BEGIN
          long id = GemFireXDUtils.newUUID();
  		return prepareStatement(sql,
			ResultSet.TYPE_FORWARD_ONLY,
			ResultSet.CONCUR_READ_ONLY,
			connectionHoldAbility,
			(columnIndexes == null || columnIndexes.length == 0)
				? Statement.NO_GENERATED_KEYS
				: Statement.RETURN_GENERATED_KEYS,
			columnIndexes,
			null,true /* is Query (Parse) Node */,id, false /*needGfxdSubactivation*/,true/*flattenSubquery*/,
			false, null, 0, 0);
//      GemStone changes END
	}

	/**
	 * Creates a default PreparedStatement object capable of returning
	 * the auto-generated keys designated by the given array. This array contains
	 * the names of the columns in the target table that contain the auto-generated
	 * keys that should be returned. This array is ignored if the SQL statement
	 * is not an INSERT statement
	 *
		JDBC 3.0
	 *
	 * @param sql  An SQL statement that may contain one or more ? IN parameter placeholders
	 * @param columnNames  An array of column names indicating the columns
	 *  that should be returned from the inserted row or rows
	 *
	 * @return  A new PreparedStatement object, containing the pre-compiled
	 *  SQL statement, that will have the capability of returning auto-generated keys
	 *  designated by the given array of column names
	 *
	 * @exception SQLException Thrown on error.
	 * 
	 * Set rootID = 0 and statementLevel = 0
	 */
	public final PreparedStatement prepareStatement(
			String sql,
			String[] columnNames)
    throws SQLException
	{
// GemStone changes BEGIN
          long id = GemFireXDUtils.newUUID();
  		return prepareStatement(sql,
			ResultSet.TYPE_FORWARD_ONLY,
			ResultSet.CONCUR_READ_ONLY,
			connectionHoldAbility,
			(columnNames == null || columnNames.length == 0)
				? Statement.NO_GENERATED_KEYS
				: Statement.RETURN_GENERATED_KEYS,
			null,
			columnNames,true /* is Query (Parse) Node */,id, false/*needGfxdSubactivation*/,true /*flattenSubquery*/,
			false, null, 0, 0);
//     GemStone changes END
	}

	/**
	 * Creates a default PreparedStatement object that has the capability to
	 * retieve auto-generated keys. The given constant tells the driver
	 * whether it should make auto-generated keys available for retrieval.
	 * This parameter is ignored if the SQL statement is not an INSERT statement.
	 * JDBC 3.0
	 *
	 * @param sql  A SQL statement that may contain one or more ? IN parameter placeholders
	 * @param autoGeneratedKeys  A flag indicating whether auto-generated keys
	 *  should be returned
	 *
	 * @return  A new PreparedStatement object, containing the pre-compiled
	 *  SQL statement, that will have the capability of returning auto-generated keys
	 *
	 * @exception SQLException  Feature not implemented for now.
	 * 
	 * Set rootID = 0 and statementLevel = 0
	 */
	public final PreparedStatement prepareStatement(
			String sql,
			int autoGeneratedKeys)
    throws SQLException
	{
// GemStone changes BEGIN
          long id = GemFireXDUtils.newUUID();
		return prepareStatement(sql,
			ResultSet.TYPE_FORWARD_ONLY,
			ResultSet.CONCUR_READ_ONLY,
			connectionHoldAbility,
			autoGeneratedKeys,
			null,
			null,true /* is Query (Parse) Node */,id,false /*needGfxdSubactivation*/,true/*flattenSubquery*/,
			false, null, 0, 0);
	}

	@Override
	public final PreparedStatement prepareStatement(
	    String sql, int resultSetType,
	    int resultSetConcurrency, int resultSetHoldability,
	    int autoGeneratedKeys) throws SQLException {
	  long id = GemFireXDUtils.newUUID();
	  return prepareStatement(sql,
	      resultSetType,
	      resultSetConcurrency,
	      resultSetHoldability,
	      autoGeneratedKeys,
	      null, null,
	      true /* is Query (Parse) Node */, id,
	      false /*needGfxdSubactivation*/, true/*flattenSubquery*/,
	      false, null, 0, 0);
	}

	@Override
	public final PreparedStatement prepareStatement(
	    String sql, int resultSetType,
	    int resultSetConcurrency, int resultSetHoldability,
	    int[] columnIndexes) throws SQLException {
	  long id = GemFireXDUtils.newUUID();
	  return prepareStatement(sql,
	      resultSetType,
	      resultSetConcurrency,
	      resultSetHoldability,
	      (columnIndexes != null && columnIndexes.length > 0
	          ? Statement.RETURN_GENERATED_KEYS
	          : Statement.NO_GENERATED_KEYS),
	      columnIndexes, null,
	      true /* is Query (Parse) Node */, id,
	      false /*needGfxdSubactivation*/, true/*flattenSubquery*/,
	      false, null, 0, 0);
	}

	@Override
	public final PreparedStatement prepareStatement(
	    String sql, int resultSetType,
	    int resultSetConcurrency, int resultSetHoldability,
	    String[] columnNames) throws SQLException {
	  long id = GemFireXDUtils.newUUID();
	  return prepareStatement(sql,
	      resultSetType,
	      resultSetConcurrency,
	      resultSetHoldability,
	      (columnNames != null && columnNames.length > 0
	          ? Statement.RETURN_GENERATED_KEYS
	          : Statement.NO_GENERATED_KEYS),
	      null, columnNames,
	      true /* is Query (Parse) Node */, id,
	      false /*needGfxdSubactivation*/, true/*flattenSubquery*/,
	      false, null, 0, 0);
	}

// GemStone changes END

	private PreparedStatement prepareStatement(String sql, int resultSetType,
					int resultSetConcurrency, int resultSetHoldability,
// GemStone changes BEGIN
					int autoGeneratedKeys, int[] columnIndexes, String[] columnNames, boolean isQueryNode, long id, 
                                        boolean needGfxdSubactivation, boolean flattenSubquery, 
                                        boolean allReplicated, THashMap ncjMetaData,
                                             long rootID, int stmtLevel)
// GemStone changes END
       throws SQLException
	 {
//            GemStone changes BEGIN
                short execFlags = 0x0000;
                if (SanityManager.TraceSingleHop) {
                  SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                      "EmbedConnection::prepareStatement isQueryNode: " + isQueryNode
                          + " and bucket ids in lcc: "
                          + getLanguageConnectionContext().getBucketIdsForLocalExecution());
                }
                
                if(isQueryNode && (getLanguageConnectionContext().getBucketIdsForLocalExecution() == null)) {
                  execFlags = GemFireXDUtils.set(execFlags, GenericStatement.CREATE_QUERY_INFO);
                }
                
                if(!flattenSubquery) {
                  execFlags = GemFireXDUtils.set(execFlags, GenericStatement.DISALLOW_SUBQUERY_FLATTENING);
                }
                
                if(needGfxdSubactivation) {
                  execFlags = GemFireXDUtils.set(execFlags, GenericStatement.GFXD_SUBACTIVATION_NEEDED);
                }
                
                if (allReplicated) {
                  if (SanityManager.DEBUG) {
                    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                      SanityManager
                          .DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                              "EmbedConnection.prepareStatement - " +
                              "Setting ALL_TABLES_ARE_REPLICATED_ON_REMOTE to TRUE");
                    }
                  }
                  execFlags = GemFireXDUtils.set(execFlags, GenericStatement.ALL_TABLES_ARE_REPLICATED_ON_REMOTE);
                }
                else {
                  if (SanityManager.DEBUG) {
                    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                      SanityManager
                          .DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                              "EmbedConnection.prepareStatement - " +
                              "Flag ALL_TABLES_ARE_REPLICATED_ON_REMOTE is FALSE");
                    }
                  }
                }

		synchronized (getConnectionSynchronization()) {
                        setupContextStack(true);
			try {
			    return factory.newEmbedPreparedStatement(this, sql, getTR().forMetadataPrepare(),
											   setResultSetType(resultSetType),
											   resultSetConcurrency,
											   resultSetHoldability,
											   autoGeneratedKeys,
											   columnIndexes,
                                                                                           columnNames, id, execFlags, ncjMetaData, rootID, stmtLevel);
			} catch (SQLException sqle) { // GemStoneAddition
			  final GemFireXDQueryObserver observer =
			      GemFireXDQueryObserverHolder.getInstance();
			  if (observer != null) {
			    PreparedStatement ps = observer.afterQueryPrepareFailure(
			        this, sql, resultSetType, resultSetConcurrency,
			        resultSetHoldability, autoGeneratedKeys,
			        columnIndexes, columnNames, sqle);
			    if (ps != null) {
			      return ps;
			    }
			  }
			  throw sqle;
			} finally {
			    restoreContextStack();
			}
		}
//            GemStone changes END
     }

    /**
     * A SQL stored procedure call statement is handled by creating a
     * CallableStatement for it. The CallableStatement provides
     * methods for setting up its IN and OUT parameters, and
     * methods for executing it.
     *
     * <P><B>Note:</B> This method is optimized for handling stored
     * procedure call statements. Some drivers may send the call
     * statement to the database when the prepareCall is done; others
     * may wait until the CallableStatement is executed. This has no
     * direct affect on users; however, it does affect which method
     * throws certain SQLExceptions.
     *
     * JDBC 2.0
     *
     * Result sets created using the returned CallableStatement will have
     * forward-only type, and read-only concurrency, by default.
     *
     * @param sql a SQL statement that may contain one or more '?'
     * parameter placeholders. Typically this  statement is a JDBC
     * function call escape string.
     * @return a new CallableStatement object containing the
     * pre-compiled SQL statement 
     * @exception SQLException if a database-access error occurs.
     */
	public final CallableStatement prepareCall(String sql) 
		throws SQLException 
	{
		return prepareCall(sql, ResultSet.TYPE_FORWARD_ONLY,
						   ResultSet.CONCUR_READ_ONLY,
						   connectionHoldAbility);
	}
	
	/**
	 * Same as {@link EmbedConnection#prepareCall(String)} except that
	 * stmtId is passed instead as an arg  
	 */
	public final CallableStatement prepareCall(String sql, long stmtId)
	        throws SQLException 
	{
	  return prepareCall(sql, stmtId, ResultSet.TYPE_FORWARD_ONLY,
              ResultSet.CONCUR_READ_ONLY,
              connectionHoldAbility);
	}

    /**
     * JDBC 2.0
     *
     * Same as prepareCall() above, but allows the default result set
     * type and result set concurrency type to be overridden.
     *
     * @param resultSetType a result set type, see ResultSet.TYPE_XXX
     * @param resultSetConcurrency a concurrency type, see ResultSet.CONCUR_XXX
     * @return a new CallableStatement object containing the
     * pre-compiled SQL statement 
     * @exception SQLException if a database-access error occurs.
     */
    public final CallableStatement prepareCall(String sql, int resultSetType,
				 int resultSetConcurrency)
		throws SQLException 
	{
		return prepareCall(sql, resultSetType, resultSetConcurrency,
						   connectionHoldAbility);
	}
    
//    GemStone changes BEGIN
    /**
     * JDBC 3.0
     *
     * Same as prepareCall() above, but allows the default result set
     * type, result set concurrency type and result set holdability
     * to be overridden.
     *
     * @param resultSetType a result set type, see ResultSet.TYPE_XXX
     * @param resultSetConcurrency a concurrency type, see ResultSet.CONCUR_XXX
     * @param resultSetHoldability - one of the following ResultSet constants:
     *  ResultSet.HOLD_CURSORS_OVER_COMMIT or ResultSet.CLOSE_CURSORS_AT_COMMIT
     * @return a new CallableStatement object containing the
     * pre-compiled SQL statement 
     * @exception SQLException if a database-access error occurs.
     */
    public final CallableStatement prepareCall(String sql, int resultSetType, 
				 int resultSetConcurrency, int resultSetHoldability)
		throws SQLException 
	{
		checkIfClosed();
                 long id = GemFireXDUtils.newUUID();
                 short execFlags = 0x00;
                 execFlags = GemFireXDUtils.set(execFlags, GenericStatement.CREATE_QUERY_INFO);
		synchronized (getConnectionSynchronization())
		{
            setupContextStack(true);
			try 
			{
			    return factory.newEmbedCallableStatement(this, sql,
											   setResultSetType(resultSetType),
											   resultSetConcurrency,
											   resultSetHoldability, id,execFlags);
			} catch (SQLException sqle) { // GemStoneAddition
			  final GemFireXDQueryObserver observer =
			      GemFireXDQueryObserverHolder.getInstance();
			  if (observer != null) {
			    CallableStatement ps = (CallableStatement)observer.afterQueryPrepareFailure(
			        this, sql, resultSetType, resultSetConcurrency,
			        resultSetHoldability, Statement.NO_GENERATED_KEYS,
			        null, null, sqle);
			    if (ps != null) {
			      return ps;
			    }
			  }
			  throw sqle;
			} finally
			{
			    restoreContextStack();
			}
		}
	}
    
  /**
   * Same as that of {@link EmbedConnection#prepareCall(String, int, int, int)}
   * except that statment id instead of generating inside is passed as an argument.
   */
  public final CallableStatement prepareCall(String sql, long stmtId, int resultSetType,
      int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    checkIfClosed();
    short execFlags = 0x00;
    execFlags = GemFireXDUtils.set(execFlags, GenericStatement.CREATE_QUERY_INFO);
    synchronized (getConnectionSynchronization()) {
      setupContextStack(true);
      try {
        return factory.newEmbedCallableStatement(this, sql,
            setResultSetType(resultSetType), resultSetConcurrency,
            resultSetHoldability, stmtId, execFlags);
      } finally {
        restoreContextStack();
      }
    }
  }

//    GemStone changes End
    /**
     * A driver may convert the JDBC sql grammar into its system's
     * native SQL grammar prior to sending it; nativeSQL returns the
     * native form of the statement that the driver would have sent.
     *
     * @param sql a SQL statement that may contain one or more '?'
     * parameter placeholders
     * @return the native form of this statement
     */
    public String nativeSQL(String sql) throws SQLException {
        checkIfClosed();
		// we don't massage the strings at all, so this is easy:
		return sql;
	}

    /**
     * If a connection is in auto-commit mode, then all its SQL
     * statements will be executed and committed as individual
     * transactions.  Otherwise, its SQL statements are grouped into
     * transactions that are terminated by either commit() or
     * rollback().  By default, new connections are in auto-commit
     * mode.
     *
     * The commit occurs when the statement completes or the next
     * execute occurs, whichever comes first. In the case of
     * statements returning a ResultSet, the statement completes when
     * the last row of the ResultSet has been retrieved or the
     * ResultSet has been closed. In advanced cases, a single
     * statement may return multiple results as well as output
     * parameter values. Here the commit occurs when all results and
     * output param values have been retrieved.
     *
     * @param autoCommit true enables auto-commit; false disables
     * auto-commit.  
     * @exception SQLException if a database-access error occurs.
     */
	public void setAutoCommit(boolean autoCommit) throws SQLException {
		checkIfClosed();

		// Is this a nested connection
		if (rootConnection != this) {
			if (autoCommit)
				throw newSQLException(SQLState.NO_AUTO_COMMIT_ON);
		}

		LanguageConnectionContext lcc = getLanguageConnection();
		if (lcc.isAllowExplicitCommitTrue()) {
			// always set autoCommit to true if "allow-explicit-commit"
			// connection property is true
			autoCommit = true;
		}

		if (this.autoCommit != autoCommit)
			commit();

		this.autoCommit = autoCommit;
		lcc.setAutoCommit(this.autoCommit);
	}
	
	public void setAutoCommit(boolean autoCommit, boolean isInit) throws SQLException {
	  if (isInit) {
	    this.autoCommit = autoCommit;
	    getLanguageConnection().setAutoCommit(autoCommit);
	  } else {
	    setAutoCommit(autoCommit);
	  }
	}

    /**
     * Get the current auto-commit state.
     *
     * @return Current state of auto-commit mode.
     * @see #setAutoCommit 
     */
    public boolean getAutoCommit() throws SQLException {
        checkIfClosed();
		return autoCommit;
	}

    /**
     * Commit makes all changes made since the previous
     * commit/rollback permanent and releases any database locks
     * currently held by the Connection. This method should only be
     * used when auto commit has been disabled.
     *
     * @exception SQLException if a database-access error occurs.
     * @see #setAutoCommit 
     */
    public void commit() throws SQLException {
		synchronized (getConnectionSynchronization())
		{
			/*
			** Note that the context stack is
			** needed even for rollback & commit
			*/
            setupContextStack(false);

			try
			{
		    	getTR().commit(this /* GemStoneAddition */);
		    	clearLOBMapping();
			}
            catch (Throwable t)
			{
				throw handleException(t);
			}
			finally 
			{
				restoreContextStack();
			}

			needCommit = false;
		}
	}

    /**
     * Rollback drops all changes made since the previous
     * commit/rollback and releases any database locks currently held
     * by the Connection. This method should only be used when auto
     * commit has been disabled.
     *
     * @exception SQLException if a database-access error occurs.
     * @see #setAutoCommit 
     */
    public void rollback() throws SQLException {

		synchronized (getConnectionSynchronization())
		{
			/*
			** Note that the context stack is
			** needed even for rollback & commit
			*/
            setupContextStack(false);
			try
			{
		    	getTR().rollback();
		    	clearLOBMapping();
			} catch (Throwable t) {
				throw handleException(t);
			}
			finally 
			{
				restoreContextStack();
			}
			needCommit = false;
		} 
// GemStone changes BEGIN
		final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
		    .getInstance();
		if (observer != null) {
		  observer.afterRollback(this);
		}
//GemStone changes END
	}

    /**
     * In some cases, it is desirable to immediately release a
     * Connection's database and JDBC resources instead of waiting for
     * them to be automatically released; the close method provides this
     * immediate release. 
     *
     * <P><B>Note:</B> A Connection is automatically closed when it is
     * garbage collected. Certain fatal errors also result in a closed
     * Connection.
     *
     * @exception SQLException if a database-access error occurs.
     */
// GemStone changes BEGIN
  public final void close() throws SQLException {
    this.close(true /* distribute it */);
  }

  /** close from a network connection */
  public final void internalClose() throws SQLException {
    close(exceptionClose, true /* distribute */, true /* internal */);
  }

  /**
   * If a connection is in auto-commit mode, then all its SQL statements will be
   * executed and committed as individual transactions. Otherwise, its SQL
   * statements are grouped into transactions that are terminated by either
   * commit() or rollback(). By default, new connections are in auto-commit
   * mode.
   * 
   * The commit occurs when the statement completes or the next execute occurs,
   * whichever comes first. In the case of statements returning a ResultSet, the
   * statement completes when the last row of the ResultSet has been retrieved
   * or the ResultSet has been closed. In advanced cases, a single statement may
   * return multiple results as well as output parameter values. Here the commit
   * occurs when all results and output param values have been retrieved.
   * 
   * This setting is for nested DDL execution so will not check if pending
   * operations are remaining in parent connection.
   * 
   * @param autoCommit
   *          true enables auto-commit; false disables auto-commit.
   * @exception SQLException
   *              if a database-access error occurs.
   */
  public final void internalSetAutoCommit(boolean autoCommit)
      throws SQLException {
    checkIfClosed();

    // Is this a nested connection
    if (rootConnection != this) {
      if (autoCommit)
        throw newSQLException(SQLState.NO_AUTO_COMMIT_ON);
    }

    if (this.autoCommit != autoCommit) {
      internalCommit();
    }

    this.autoCommit = autoCommit;
  }

  /**
   * Commit makes all changes made since the previous commit/rollback permanent
   * and releases any database locks currently held by the Connection. This
   * method should only be used when auto commit has been disabled.
   * 
   * This is invoked by internal methods so will ignore check for pending
   * operations on the conneciton.
   * 
   * @exception SQLException
   *              if a database-access error occurs.
   * @see #setAutoCommit
   */
  public final void internalCommit() throws SQLException {
    final LanguageConnectionContext lcc = getLanguageConnection();
    if (lcc != null) {
      synchronized (getConnectionSynchronization()) {
        /*
        ** Note that the context stack is
        ** needed even for rollback & commit
        */
        setupContextStack(false);

        try {
          lcc.internalCommit(true);
          clearLOBMapping();
        } catch (Throwable t) {
          throw handleException(t);
        } finally {
          restoreContextStack();
        }

        needCommit = false;
      }
    }
  }

  /**
   * Rollback drops all changes made since the previous commit/rollback and
   * releases any database locks currently held by the Connection. This method
   * should only be used when auto commit has been disabled.
   * 
   * This is invoked by internal methods (e.g. network server session close of a
   * thread) so will ignore check for pending operations on the conneciton.
   * 
   * @exception SQLException
   *              if a database-access error occurs.
   * @see #setAutoCommit
   */
  @Override
  public final void internalRollback() throws SQLException {
    final LanguageConnectionContext lcc = getLanguageConnection();
    if (lcc != null) {
      synchronized (getConnectionSynchronization()) {
        /*
        ** Note that the context stack is
        ** needed even for rollback & commit
        */
        setupContextStack(false);
        try {
          lcc.internalRollback();
          clearLOBMapping();
        } catch (Throwable t) {
          throw handleException(t);
        } finally {
          restoreContextStack();
        }
        this.needCommit = false;
      }
    }
  }

  /** basic cleanup required to avoid memory leaks etc. */
  @Override
  public final void forceClose() {
    final TransactionResourceImpl tr = getTR();
    if (tr != null) {
      // try to abort any active transaction first
      final LanguageConnectionContext lcc = tr.getLcc();
      if (lcc != null) {
        TransactionController tc = lcc.getTransactionExecute();
        if (tc != null) {
          try {
            tc.abort();
          } catch (StandardException ignored) {
          }
          tc.releaseAllLocks(true, true);
        }
      }

      if (rootConnection == this) {
        // cleanup the CM from ContextService
        final ContextManager cm = this.tr.cm;
        if (cm != null) {
          ContextService.removeContextManager(cm);
        }
      }
      this.active.state = 0;
    }
  }

  @Override
  public final FinalizeObject getAndClearFinalizer() {
    final FinalizeEmbedConnection finalizer = this.finalizer;
    if (finalizer != null) {
      this.finalizer = null;
    }
    return finalizer;
  }

  public final void close(boolean distribute) throws SQLException {
    if (!isClosed()) {
      // do not check for transaction IDLE with TX level NONE since that
      // does not require commit/abort regardless of autocommit level
      final LanguageConnectionContext lcc = getTR().getLcc();
      boolean autoCommit = this.autoCommit;
      if (lcc != null) {
        final int isolationLevel = ExecutionContext
            .CS_TO_JDBC_ISOLATION_LEVEL_MAP[lcc.getCurrentIsolationLevel()];
        if (isolationLevel == TRANSACTION_NONE) {
          autoCommit = true;
        }
        // if outermost root connection is getting closed, otherwise ignore
        if (rootConnection == this && (lcc.statsEnabled() ||
              lcc.explainConnection())) {
          lcc.setStatsEnabled(false, false, false);
        }
      }
      if ((rootConnection == this) && (!autoCommit && !transactionIsIdle())) {
        throw newSQLException(SQLState.CANNOT_CLOSE_ACTIVE_CONNECTION);
      }
    }
    close(exceptionClose, distribute, false);
      /* (original code)
        checkForTransactionInProgress();
		close(exceptionClose);
      */
// GemStone changes END
	}

    /**
     * Check if the transaction is active so that we cannot close down the
     * connection. If auto-commit is on, the transaction is committed when the
     * connection is closed, so it is always OK to close the connection in that
     * case. Otherwise, throw an exception if a transaction is in progress.
     *
     * @throws SQLException if this transaction is active and the connection
     * cannot be closed
     */
    public void checkForTransactionInProgress() throws SQLException {
// GemStone changes BEGIN
        // do not check for transaction IDLE with TX level NONE since that
        // does not require commit/abort regardless of autocommit level
        final LanguageConnectionContext lcc = getTR().getLcc();
        if (!isClosed() && (rootConnection == this) && (lcc == null ||
              lcc.getCurrentIsolationLevel() != ExecutionContext
                  .UNSPECIFIED_ISOLATION_LEVEL) &&
        /* (original code)
        if (!isClosed() && (rootConnection == this) &&
        */
// GemStone changes END
                !autoCommit && !transactionIsIdle()) {
            throw newSQLException(SQLState.CANNOT_CLOSE_ACTIVE_CONNECTION);
        }
    }

	// This inner close takes the exception and calls 
	// the context manager to make the connection close.
	// The exception must be a session severity exception.
	//
	// NOTE: This method is not part of JDBC specs.
	//
    private void close(StandardException e,
// GemStone changes BEGIN
        boolean distribute, boolean internal) throws SQLException {

         boolean wasActive = false;
         // lose the finalizer reference, so that itself can now be GCed
         final FinalizeEmbedConnection finalizer = this.finalizer;
         if (finalizer != null) {
           finalizer.clearAll();
           this.finalizer = null;
         }
// GemStone changes END
		synchronized(getConnectionSynchronization())
		{
			if (rootConnection == this)
			{
				/*
				 * If it isn't active, it's already been closed.
				 */
// GemStone changes BEGIN
				if (this.active.state == 1) {
				  tr.getLcc().setRunTimeStatisticsMode(
				      false, false);
				/* (original code)
				if (active) {
				*/
// GemStone changes END
					if (tr.isActive()) {
						setupContextStack(false);
// GemStone changes BEGIN
						wasActive = true;
// GemStone changes END
						try {
// GemStone changes BEGIN
							// ignore exceptions in rollback for internal case
							if (internal) {
							  try {
							    this.tr.rollback();
							  } catch (StandardException se) {
							    if (SanityManager.isFinerEnabled) {
							      SanityManager.DEBUG_PRINT(
							          GfxdConstants.TRACE_TRAN,
							          "Got exception in internal connection rollback.", se);
							    }
							  }
							}
							else {
							  this.tr.rollback();
							}
							/* (original code)
							tr.rollback();
							*/
// GemStone changes END
							
							// Let go of lcc reference so it can be GC'ed after
							// cleanupOnError, the tr will stay around until the
							// rootConnection itself is GC'ed, which is dependent
							// on how long the client program wants to hold on to
							// the Connection object.
							tr.clearLcc(); 
							tr.cleanupOnError(e);
							
						} catch (Throwable t) {
							throw handleException(t);
						} finally {
							restoreContextStack();
						}
					} else {
						// DERBY-1947: If another connection has closed down
						// the database, the transaction is not active, but
						// the cleanup has not been done yet.
						tr.clearLcc(); 
						tr.cleanupOnError(e);
					}
				}
// GemStone changes BEGIN
				// cleanup the CM from ContextService
				final ContextManager cm = this.tr.cm;
				if (cm != null) {
				  ContextService.removeContextManager(cm);
				}
// GemStone changes END
			}

			if (!isClosed())
				setInactive();
		}
// GemStone changes BEGIN
		if (wasActive && distribute && this.rootConnection == this
		    && this.connID != UNINITIALIZED
		    && this.connID != CHILD_NOT_CACHEABLE) {
		  // Add to the closed connection IDs List
		  ConnectionSignaller.getInstance().add(
		      new ConnectionCloseState(this.connID));
		}
		final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
		    .getInstance();
		if (observer != null) {
		  observer.afterConnectionClose(this);
		}
		incrementCloseStats();
// GemStone changes END
	}

// GemStone changes BEGIN
    public final void closingNestedConnection() {
      incrementCloseStats();
    }

    private final void incrementCloseStats() {
      if (!this.skipCloseStats) {
        incrementCloseStats(nestedConn, internalConn,
            clientConn, beginTime);
      }
    }

    private static void incrementCloseStats(boolean nestedConn,
        boolean internalConn, boolean clientConn, long beginTime) {
      InternalDriver driver = InternalDriver.activeDriver();
      ConnectionStats stats;
      if (driver != null && (stats = driver.getConnectionStats()) != null) {
        if (nestedConn) {
          stats.incNestedConnectionsClosed();
          stats.decNestedConnectionsOpen();
        }
        else {
          if (internalConn) {
            stats.incInternalConnectionsClosed();
            stats.decInternalConnectionsOpen();
          }
          else {
            // check for client connection
            if (!clientConn) {
              stats.incPeerConnectionsClosed();
              stats.incPeerConnectionLifeTime(beginTime);
              stats.decPeerConnectionsOpen();
            }
          }
        }
      }
    }

    /**
     * Return true if the connection is still active and false otherwise. Unlike
     * {@link #isClosed()} it avoids checking for database/monitor etc. for
     * validity and so is a much faster alternative where checking for latter is
     * not strictly required.
     */
    public final boolean isActive() {
      return (this.active.state == 1);
    }
// GemStone changes END

    /**
     * Tests to see if a Connection is closed.
     *
     * @return true if the connection is closed; false if it's still open
     */
    public final boolean isClosed() {
// GemStone changes BEGIN
		if (this.active.state == 1) {
		/* (original code)
		if (active) {
		*/
// GemStone changes END

			// I am attached, check the database state
			if (getTR().isActive()) {
				return false;
			}
		}
		return true;
	}

    /**
     * A Connection's database is able to provide information
     * describing its tables, its supported SQL grammar, its stored
     * procedures, the capabilities of this connection, etc. This
     * information is made available through a DatabaseMetaData
     * object.
     *
     * @return a DatabaseMetaData object for this Connection 
     * @exception SQLException if a database-access error occurs.
     */
    public DatabaseMetaData getMetaData() throws SQLException {
        checkIfClosed();

		if (dbMetadata == null) {

 			// There is a case where dbname can be null.
			// Replication client of this method does not have a
			// JDBC connection; therefore dbname is null and this
			// is expected.
			//
			dbMetadata = factory.newEmbedDatabaseMetaData(this, getTR().getUrl());
		}
		return dbMetadata;
	}

	/**
		JDBC 3.0
	 * Retrieves the current holdability of ResultSet objects created using this
	 * Connection object.
	 *
	 *
	 * @return  The holdability, one of ResultSet.HOLD_CURSORS_OVER_COMMIT
	 * or ResultSet.CLOSE_CURSORS_AT_COMMIT
	 *
	 */
	public final int getHoldability() throws SQLException {
		checkIfClosed();
		return connectionHoldAbility;
	}

	/**
		JDBC 3.0
	 * Changes the holdability of ResultSet objects created using this
	 * Connection object to the given holdability.
	 *
	 *
	 * @param holdability  A ResultSet holdability constant, one of ResultSet.HOLD_CURSORS_OVER_COMMIT
	 * or ResultSet.CLOSE_CURSORS_AT_COMMIT
	 *
	 */
	public final void setHoldability(int holdability) throws SQLException {
		checkIfClosed();
		connectionHoldAbility = holdability;
	}

    /**
     * You can put a connection in read-only mode as a hint to enable 
     * database optimizations.
     *
     * <P><B>Note:</B> setReadOnly cannot be called while in the
     * middle of a transaction.
     *
     * @param readOnly true enables read-only mode; false disables
     * read-only mode.  
     * @exception SQLException if a database-access error occurs.
     */
    public final void setReadOnly(boolean readOnly) throws SQLException
	{
		synchronized(getConnectionSynchronization())
		{
                        setupContextStack(false);
			try {
				getLanguageConnection().setReadOnly(readOnly);
			} catch (StandardException e) {
				throw handleException(e);
			} finally {
				restoreContextStack();
			}
		}
	}

    /**
     * Tests to see if the connection is in read-only mode.
     *
     * @return true if connection is read-only
     * @exception SQLException if a database-access error occurs.
     */
    public final boolean isReadOnly() throws SQLException
	{
		checkIfClosed();
		return getLanguageConnection().isReadOnly();
	}

    /**
     * A sub-space of this Connection's database may be selected by setting a
     * catalog name. If the driver does not support catalogs it will
     * silently ignore this request.
     *
     * @exception SQLException if a database-access error occurs.
     */
    public void setCatalog(String catalog) throws SQLException {
        checkIfClosed();
		// silently ignoring this request like the javadoc said.
		return;
	}

    /**
     * Return the Connection's current catalog name.
     *
     * @return the current catalog name or null
     * @exception SQLException if a database-access error occurs.
     */
	public String getCatalog() throws SQLException {
		checkIfClosed();
		// we do not have support for Catalog, just return null as
		// the JDBC specs mentions then.
		return null;
	}

    /**
     * You can call this method to try to change the transaction
     * isolation level using one of the TRANSACTION_* values.
     *
     * <P><B>Note:</B> setTransactionIsolation causes the current
     * transaction to commit if the isolation level is changed. Otherwise, if
     * the requested isolation level is the same as the current isolation
     * level, this method is a no-op.
     *
     * @param level one of the TRANSACTION_* isolation values with the
     * exception of TRANSACTION_NONE; some databases may not support
     * other values
     * @exception SQLException if a database-access error occurs.
     * @see DatabaseMetaData#supportsTransactionIsolationLevel 
     */
    public void setTransactionIsolation(int level) throws SQLException {
// GemStone changes BEGIN
      setTransactionIsolation(level, null);
    }

    @Override
    public EnumSet<TransactionFlag> getTransactionFlags() {
      return getTR().getTXFlags();
    }

    @Override
    public void setTransactionIsolation(int level,
        EnumSet<TransactionFlag> txFlags) throws SQLException {
      //SanityManager.showTrace(new Throwable(GfxdConstants.TRACE_TRAN));
      final LanguageConnectionContext lcc = getLanguageConnection();
      if (lcc == null) {
        throw Util.noCurrentConnection();
      }
      int currentLevel = ExecutionContext
          .CS_TO_JDBC_ISOLATION_LEVEL_MAP[lcc.getCurrentIsolationLevel()];
      if (GemFireXDUtils.TraceTran) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN, "connID="
            + this.connID + "; current isolation level "
            + currentLevel + "; isolation being assigned " + level);
      }
		if (level == currentLevel
		    && ArrayUtils.objectEquals(txFlags, lcc.getTXFlags()))
// GemStone changes END
			return;

		// Convert the isolation level to the internal one
		int iLevel;
		switch (level)
		{
		case java.sql.Connection.TRANSACTION_READ_UNCOMMITTED:
// GemStone changes BEGIN
		  // add a log message for upgrading the isolation level.
		  SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_TRAN,
		      "Upgrading transaction level from READ_UNCOMMITTED to " +
		      "READ_COMMITTED for " + getLanguageConnectionContext()
		          .getTransactionExecute());
		  //iLevel = ExecutionContext.READ_UNCOMMITTED_ISOLATION_LEVEL;
		  //break;
// GemStone changes END

		case java.sql.Connection.TRANSACTION_READ_COMMITTED:
			iLevel = ExecutionContext.READ_COMMITTED_ISOLATION_LEVEL;			
			break;

		case java.sql.Connection.TRANSACTION_REPEATABLE_READ:
			iLevel = ExecutionContext.REPEATABLE_READ_ISOLATION_LEVEL;		
			break;

		case java.sql.Connection.TRANSACTION_SERIALIZABLE:
// GemStone changes BEGIN
			GemFireStore store = Misc.getMemStoreBootingNoThrow();
			if (store != null && store.isSnappyStore()) {
				iLevel = ExecutionContext.REPEATABLE_READ_ISOLATION_LEVEL;
				setAutoCommit(true);
				break;
			}

		  throw newSQLException(SQLState.UNIMPLEMENTED_ISOLATION_LEVEL, Integer.valueOf(level));
		  //iLevel = ExecutionContext.SERIALIZABLE_ISOLATION_LEVEL;
		  //break;
		  // allow setting TX level NONE
		case java.sql.Connection.TRANSACTION_NONE:
		  iLevel = ExecutionContext.UNSPECIFIED_ISOLATION_LEVEL;
		  break;
		case IsolationLevel.NO_JDBC_LEVEL:
			iLevel = ExecutionContext.UNSPECIFIED_ISOLATION_LEVEL;
			break;

// GemStone changes END
		default:
			throw newSQLException(SQLState.UNIMPLEMENTED_ISOLATION_LEVEL,
			    Integer.valueOf(level));
		}

		synchronized(getConnectionSynchronization())
		{
            setupContextStack(false);
			try {
// GemStone changes BEGIN
                          if (txFlags != null) {
                            lcc.setTXFlags(txFlags);
                          }
                          else if (lcc.getTXFlags() == null) {
                            // inherit from TR
                            txFlags = getTR().getTXFlags();
                            lcc.setTXFlags(txFlags);
                          }
			  lcc.setIsolationLevel(iLevel);
			  /* (original code)
				getLanguageConnection().setIsolationLevel(iLevel);
			  */
// GemStone changes END
			} catch (StandardException e) {
				throw handleException(e);
			} finally {
				restoreContextStack();
				if (GemFireXDUtils.TraceTran) {
				        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN, "conn ID = "+ this.connID
				            + "; isolation level after asignment="+this.getTransactionIsolation()
				            );
				      }
			}
		}
	}


    /**
     * Get this Connection's current transaction isolation mode.
     *
     * @return the current TRANSACTION_* mode value
     * @exception SQLException if a database-access error occurs.
     */
    public final int getTransactionIsolation() throws SQLException {
        checkIfClosed();
// GemStone changes BEGIN
        final LanguageConnectionContext lcc = getLanguageConnectionContext();
        if (lcc != null) {
          return ExecutionContext
              .CS_TO_JDBC_ISOLATION_LEVEL_MAP[lcc.getCurrentIsolationLevel()];
        }
        else {
          throw Util.noCurrentConnection();
        }
        /* (original code)
		return ExecutionContext.CS_TO_JDBC_ISOLATION_LEVEL_MAP[getLanguageConnection().getCurrentIsolationLevel()];
	*/
// Gemstone changes END
	}

    /**
     * The first warning reported by calls on this Connection is
     * returned.  
     *
     * <P><B>Note:</B> Subsequent warnings will be chained to this
     * SQLWarning.
     *
     * @return the first SQLWarning or null 
     *
	 * Synchronization note: Warnings are synchronized 
	 * on nesting level
     */
	public final synchronized SQLWarning getWarnings() throws SQLException {
		checkIfClosed();
   		return topWarning;
	}

    /**
     * After this call, getWarnings returns null until a new warning is
     * reported for this Connection.  
     *
	 * Synchronization node: Warnings are synchonized 
	 * on nesting level
     */
    public final synchronized void clearWarnings() throws SQLException {
        checkIfClosed();
		topWarning = null;
	}

 	/////////////////////////////////////////////////////////////////////////
	//
	//	JDBC 2.0	-	New public methods
	//
	/////////////////////////////////////////////////////////////////////////

    /**
     *
	 * Get the type-map object associated with this connection.
	 * By default, the map returned is empty.
	 * JDBC 2.0 - java.util.Map requires JDK 1
     *
     */
  // GemStone changes BEGIN
  // changed to use generics for JDBC 4.0
    public java.util.Map<String, Class<?>> getTypeMap() throws SQLException {
        checkIfClosed();
		// just return an immuntable empty map
      return java.util.Collections.EMPTY_MAP;
    }
  // GemStone changes END

    /** 
	 * Install a type-map object as the default type-map for
	 * this connection.
	 * JDBC 2.0 - java.util.Map requires JDK 1
     *
     * @exception SQLException Feature not implemented for now.
	 */
    public final void setTypeMap(java.util.Map map) throws SQLException {
        checkIfClosed();
        throw Util.notImplemented();
    }

	/////////////////////////////////////////////////////////////////////////
	//
	//	Implementation specific methods	
	//
	/////////////////////////////////////////////////////////////////////////

	/**
		Add a warning to the current list of warnings, to follow
		this note from Connection.getWarnings.
		Note: Subsequent warnings will be chained to this SQLWarning. 

		@see java.sql.Connection#getWarnings
	*/
	 public final synchronized void addWarning(SQLWarning newWarning) {
		if (topWarning == null) {
			topWarning = newWarning;
			return;
		}

		topWarning.setNextWarning(newWarning);
	}

	/**
	 * Return the dbname for this connection.
	 *
	 * @return String	The dbname for this connection.
	 */
	public String getDBName()
	{
		if (SanityManager.DEBUG_ASSERT)
			SanityManager.ASSERT(!isClosed(), "connection is closed");

		return getTR().getDBName();
	}

	public final LanguageConnectionContext getLanguageConnection() {

		if (SanityManager.DEBUG_ASSERT)
			SanityManager.ASSERT(isActive(), "connection is closed");

		return getTR().getLcc();
	}

    /**
     * Raises an exception if the connection is closed.
     *
     * @exception SQLException if the connection is closed
     */
    protected final void checkIfClosed() throws SQLException {
        if (!isActive()) {
            throw Util.noCurrentConnection();
        }
    }

	//EmbedConnection30 overrides this method so it can release the savepoints array if
	//the exception severity is transaction level
	SQLException handleException(Throwable thrownException)
			throws SQLException
	{
		//assume in case of SQLException cleanup is 
		//done already. This assumption is inline with
		//TR's assumption. In case no rollback was 
		//called lob objects will remain valid.
		if (thrownException instanceof StandardException) {
// GemStone changes BEGIN
		  // for constraint violations within TX, need to abort the
		  // TX due to #43170, so set severity appropriately
		  StandardException se = (StandardException)thrownException;
		  abortForConstraintViolationInTX(se,
		      getLanguageConnectionContext());
		  if (se.getSeverity()
	          /* (original code)
			if (((StandardException) thrownException)
				.getSeverity() 
		  */				
// GemStone changes END
				>= ExceptionSeverity.TRANSACTION_SEVERITY) {
				clearLOBMapping();
			}
		}

		/*
		** By default, rollback the connection on if autocommit
	 	** is on.
		*/
		return getTR().handleException(thrownException, 
								  autoCommit,
								  true // Rollback xact on auto commit
								  );
	}

	/**
		Handle any type of Exception.
		<UL>
		<LI> Inform the contexts of the error
		<LI> Throw an Util based upon the thrown exception.
		</UL>

		REMIND: now that we know all the exceptions from our driver
		are Utils, would it make sense to shut down the system
		for unknown SQLExceptions? At present, we do not.

		Because this is the last stop for exceptions,
		it will catch anything that occurs in it and try
		to cleanup before re-throwing them.
	
		@param thrownException the exception
		@param rollbackOnAutoCommit rollback the xact on if autocommit is
				on, otherwise rollback stmt but leave xact open (and
				continue to hold on to locks).  Most of the time, this
				will be true, excepting operations on result sets, like
				getInt().
	*/
	final SQLException handleException(Throwable thrownException, 
									   boolean rollbackOnAutoCommit) 
			throws SQLException 
	{
		//assume in case of SQLException cleanup is 
		//done already. This assumption is inline with
		//TR's assumption. In case no rollback was 
		//called lob objects will remain valid.
		if (thrownException instanceof StandardException) {
// GemStone changes BEGIN
		  // for constraint violations within TX, need to abort the
		  // TX due to #43170, so set severity appropriately
		  StandardException se = (StandardException)thrownException;
		  abortForConstraintViolationInTX(se,
		      getLanguageConnectionContext());
		  if (se.getSeverity()
                  /* (original code)
			if (((StandardException) thrownException)
				.getSeverity() 
		  */
// GemStone changes END
				>= ExceptionSeverity.TRANSACTION_SEVERITY) {
				clearLOBMapping();
			}
		}
		return getTR().handleException(thrownException, autoCommit,
								  rollbackOnAutoCommit); 

	}

	/*
	   This is called from the EmbedConnectionContext to
	   close on errors.  We assume all handling of the connectin
	   is dealt with via the context stack, and our only role
	   is to mark ourself as closed.
	 */

	/**
		Close the connection when processing errors, or when
	 	closing a nested connection.
		<p>
		This only marks it as closed and frees up its resources;
		any closing of the underlying connection or commit work
		is assumed to be done elsewhere.

		Called from EmbedConnectionContext's cleanup routine,	
		and by proxy.close().
	 */

	public final void setInactive() {

// GemStone changes BEGIN
		if (this.active.state == 0)
		/* (original code)
		if (active == false)
		*/
// GemStone changes END
			return;
		// active = false
		// tr = null !-> active = false

		synchronized (getConnectionSynchronization()) {
// GemStone changes BEGIN
			this.active.state = 0;
			/* (original code)
			active = false;
			*/
// GemStone changes END
			// tr = null; cleanupOnerror sets inactive but still needs tr to
			// restore context later
			dbMetadata = null;
		}
	}

	/**
		@exception Throwable	standard error policy
	 */
// GemStone changes BEGIN
	// now using FinalizeEmbedConnection in ReferenceQueue instead
	// of finalizer
	/* (original code)
	protected void finalize() throws Throwable 
	{
		try {
			// Only close root connections, since for nested
			// connections, it is not strictly necessary and close()
			// synchronizes on the root connection which can cause
			// deadlock with the call to runFinalization from
			// GenericPreparedStatement#prepareToInvalidate (see
			// DERBY-1947) on SUN VMs.
			if (rootConnection == this) {
			  close(exceptionClose);
			}
		}
		finally {
			super.finalize();
		}
	}
	*/
// GemStone changes END

	/**
	 * if auto commit is on, remember that we need to commit
	 * the current statement.
	 */
    protected void needCommit() {
		if (!needCommit) needCommit = true;
	}

	/**
	 * if a commit is needed, perform it.
     *
     * Must have connection synchonization and context set up already.
     *
	 * @exception SQLException if commit returns error
	 */
	protected final void commitIfNeeded() throws SQLException 
    {
		if ((autoCommit && needCommit) ||
				((GemFireTransaction)getTR().getLcc().getTransactionExecute()).getImplcitSnapshotTxStarted())
        {
            try
            {
                getTR().commit(this /* GemStoneAddition */);
                clearLOBMapping();
            } 
            catch (Throwable t)
            {
                throw handleException(t);
            }
            needCommit = false;
		}
	}

	/**
	 * If in autocommit, then commit.
     * 
     * Used to force a commit after a result set closes in autocommit mode.
     * The needCommit mechanism does not work correctly as there are times
     * with cursors (like a commit, followed by a next, followed by a close)
     * where the system does not think it needs a commit but we need to 
     * force the commit on close.  It seemed safer to just force a commit
     * on close rather than count on keeping the needCommit flag correct for
     * all cursor cases.
     *
     * Must have connection synchonization and context set up already.
     *
	 * @exception SQLException if commit returns error
	 */
	protected final void commitIfAutoCommit() throws SQLException 
    {
		if (autoCommit ||
				((GemFireTransaction)getLanguageConnection().getTransactionExecute()).getImplcitSnapshotTxStarted())
        {
            try
            {
                getTR().commit(this /* GemStoneAddition */);
                clearLOBMapping();
            } 
            catch (Throwable t)
            {
                throw handleException(t);
            }
            needCommit = false;
		}
	}


// GemStone changes BEGIN
	@Override
	final public Object getConnectionSynchronization()
	/* (original code) final protected Object getConnectionSynchronization() */
// GemStone changes END
  {
		return rootConnection;
  }

// GemStone added the boolean argument below
	/**
		Install the context manager for this thread.  Check connection status here.
		@param isOperation true if this is a top-level operation that needs proper
		transactional context, else false (e.g. for connection close) when no new
		transaction needs to be started after a previous commit or rollback
	 	@exception SQLException if fails
	 */
	public final void setupContextStack(
	    final boolean isOperation) throws SQLException {

		/*
			Track this entry, then throw an exception
			rather than doing the quiet return.  Need the
			track before the throw because the backtrack
			is in a finally block.
		 */

		checkIfClosed();

// GemStone changes BEGIN
		try {
		  if (this.isRemoteConnection) {
		    getTR().setupContextStack();
		  }
		  else {
		    getTR().setupContextStackAndReattach(isOperation);
		  }
		} catch (SQLException sqle) {
		  throw sqle;
		} catch (Throwable t) {
		  throw handleException(t);
		}
		/* (original code)
		getTR().setupContextStack();
		*/
// GemStone changes END

	}

	public final void restoreContextStack() throws SQLException {

// GemStone changes BEGIN
		if (SanityManager.DEBUG_ASSERT) {
		  Util.ASSERT(this, this.active.state == 1 || getTR()
		      .getCsf() != null, "No context service to do restore");
		}
		/* (original code)
		Util.ASSERT(this, (active) || getTR().getCsf() !=null, "No context service to do restore");
		*/
// GemStone changes END

		TransactionResourceImpl tr = getTR();

		//REMIND: someone is leaving an incorrect manager on when they
		// are exiting the system in the nested case.
		if (SanityManager.DEBUG_ASSERT)
		{
			if (tr.getCsf() != null) {
				ContextManager cm1 = tr.getCsf().getCurrentContextManager();
				ContextManager cm2 = tr.getContextManager();
				// If the system has been shut down, cm1 can be null.
				// Otherwise, cm1 and cm2 should be identical.
// GemStone changes BEGIN
				// create string only on mismatch
				if (cm1 != cm2 && cm1 != null) {
				  Util.THROWASSERT(this, "Current Context "
				      + "Manager not the one expected: "
				      + cm1 + " " + cm2);
				}
				/* (original code)
				Util.ASSERT(this, (cm1 == cm2 || cm1 == null),
					"Current Context Manager not the one was expected: " + cm1 + " " + cm2 
				    );
				*/
// GemStone changes END
			}
		}

		tr.restoreContextStack();
	}

	/*
	** Create database methods.
	*/

	/**
		Create a new database.
		@param dbname the database name
		@param info the properties

		@return	Database The newly created database or null.

	 	@exception SQLException if fails to create database
	*/

	private Database createDatabase(String dbname, Properties info)
		throws SQLException {

		info = filterProperties(info);

		try {
			if (Monitor.createPersistentService(Property.DATABASE_MODULE, dbname, info) == null) 
			{
				// service already exists, create a warning
				addWarning(SQLWarningFactory.newSQLWarning(SQLState.DATABASE_EXISTS, dbname));
			}
		} catch (StandardException mse) {
// GemStone changes BEGIN
		  if (mse.getSQLState().startsWith("XB")
		      || mse.getSQLState().startsWith("XC")) {
		    throw Util.generateCsSQLException(mse);
		  }
		  throw Util.generateCsSQLException(SQLState
		      .CREATE_DATABASE_FAILED, dbname,
		      getBootDatabaseFailureCause(mse));
		  /* (original code)
            throw Util.seeNextException(SQLState.CREATE_DATABASE_FAILED,
                                        new Object[] { dbname },
                                        handleException(mse));
                  */
// GemStone changes END
		}

		// clear these values as some modules hang onto
		// the properties set corresponding to service.properties
		// and they shouldn't be interested in these JDBC attributes.
		info.clear();

		return (Database) Monitor.findService(Property.DATABASE_MODULE, dbname);
	}


	/**
	 * Boot database.
	 *
	 * @param info boot properties
	 *
	 * @param softAuthenticationBoot If true, don't fail soft upgrade due
	 * to missing features (phase one of two phased hard upgrade boot).
	 *
	 * @return false iff the monitor cannot handle a service
	 * of the type indicated by the protocol within the name.
	 * If that's the case then we are the wrong driver.
	 *
	 * @throws Throwable if anything else is wrong.
	 */

	private boolean bootDatabase(Properties info,
								 boolean softAuthenticationBoot
								 ) throws Throwable
	{
		String dbname = tr.getDBName();

		// boot database now
		try {

	                //GemStone changes BEGIN
	                //This indicates whether we are booting through FabricService API
	                //or on First connection.
                        if ( info.getProperty(GfxdConstants.PROPERTY_BOOT_INDICATOR) == null) {
                          
                          if (GemFireXDUtils.TraceFabricServiceBoot) {
                            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
                                "Setting first connection as boot indicator");
                          }
                          
                          info.setProperty(GfxdConstants.PROPERTY_BOOT_INDICATOR,
                              GfxdConstants.BT_INDIC.FIRSTCONNECTION.toString());
                        }
	                //GemStone changes END
			info = filterProperties(info);

			if (softAuthenticationBoot) {
				info.setProperty(Attribute.SOFT_UPGRADE_NO_FEATURE_CHECK,
								 "true");
			} else {
				info.remove(Attribute.SOFT_UPGRADE_NO_FEATURE_CHECK);
			}
			
			// Try to start the service if it doesn't already exist.
			// Skip boot if internal connection property is set.
			boolean booted = Boolean.parseBoolean(info.getProperty(
					com.pivotal.gemfirexd.Attribute.INTERNAL_CONNECTION));
			GemFireStore store = booted ? Misc.getMemStoreBooting()
			    : Misc.getMemStoreBootingNoThrow();
			if (store == null && !Monitor.startPersistentService(dbname, info)) {
				// a false indicates the monitor cannot handle a service
				// of the type indicated by the protocol within the name.
				// If that's the case then we are the wrong driver
				// so just return null.
				return false;
			}
			// clear these values as some modules hang onto
			// the properties set corresponding to service.properties
			// and they shouldn't be interested in these JDBC attributes.
			info.clear();

			Database database = (Database) Monitor.findService(Property.DATABASE_MODULE, dbname);
			tr.setDatabase(database);
			if (store == null) {
				store = Misc.getMemStoreBootingNoThrow();
				if (store != null) {
					store.setDBName(dbname);
				}
			}

		} catch (StandardException mse) {

			Throwable ne = mse.getCause();
			SQLException nse;

			/*
			  If there is a next exception, assume
			  that the first one is just a redundant "see the
			  next exception" message.
			  if it is a BEI, treat it as a database exception.
			  If there isn't a BEI, treat it as a java exception.

			  In general we probably want to walk the chain
			  and return all of them, but empirically, this
			  is all we need to do for now.
			  */
// GemStone changes BEGIN
			if (GemFireXDUtils.TraceFabricServiceBoot) {
			  SanityManager.DEBUG_PRINT(GfxdConstants
			      .TRACE_FABRIC_SERVICE_BOOT,
			      "Failed to boot database", mse);
			}
			if (mse.getSQLState().startsWith("XB")
			    || mse.getSQLState().startsWith("XC")) {
			  throw Util.generateCsSQLException(mse);
			}
			throw Util.generateCsSQLException(SQLState
			    .BOOT_DATABASE_FAILED, dbname,
			    getBootDatabaseFailureCause(mse));
			/* (original code)
			if (ne instanceof StandardException)
				nse = Util.generateCsSQLException((StandardException)ne);
			else if (ne != null)
				nse = Util.javaException(ne);
			else
				nse = Util.generateCsSQLException(mse);

            throw Util.seeNextException(SQLState.BOOT_DATABASE_FAILED,
                                        new Object[] { dbname }, nse);
                        */
// GemStone changes END
		}

		// If database exists, getDatabase() will return the database object.
		// If any error occured while booting an existing database, an
		// exception would have been thrown already.
		return true;

	}

	/*
	 * Class interface methods used by database metadata to ensure
	 * good relations with autocommit.
	 */

// GemStone changes BEGIN
    public
// GemStone changes END
    PreparedStatement prepareMetaDataStatement(String sql)
	    throws SQLException {
		synchronized (getConnectionSynchronization()) {
                        setupContextStack(true);
			PreparedStatement s = null;
			try {
//                            GemStone changes BEGIN
                          //TODO:Asif: Rectify this!
			  byte execFlags = 0x00;
			    s = factory.newEmbedPreparedStatement(this, sql, true,
											  ResultSet.TYPE_FORWARD_ONLY,
											  ResultSet.CONCUR_READ_ONLY,
											  connectionHoldAbility,
											  Statement.NO_GENERATED_KEYS,
											  null,
								  null,  -1 /* pass the ID as -1 for now */,
								  execFlags, null, 0 /* pass the root ID as 0 for now */,
								  0 /* pass the stmt level as 0 for now */);
//                          GemStone changes END
                        } finally {
			    restoreContextStack();
			}
			return s;
		}
	}

	public final InternalDriver getLocalDriver()
	{
		if (SanityManager.DEBUG_ASSERT)
			SanityManager.ASSERT(!isClosed(), "connection is closed");

		return getTR().getDriver();
	}

	/**
		Return the context manager for this connection.
	*/
	public final ContextManager getContextManager() {

		if (SanityManager.DEBUG_ASSERT)
			SanityManager.ASSERT(!isClosed(), "connection is closed");

		return getTR().getContextManager();
	}

	/**
	 * Filter out properties from the passed in set of JDBC attributes
	 * to remove any gemfirexd.* properties. This is to ensure that setting
	 * gemfirexd.* properties does not work this way, it's not a defined way
	 * to set such properties and could be a secuirty hole in allowing
	 * remote connections to override system, application or database settings.
	 * 
	 * @return a new Properties set copied from the parameter but with no
	 * gemfirexd.* properties.
	 */
	private Properties filterProperties(Properties inputSet) {
		Properties limited = new Properties();

		// filter out any gemfirexd.* properties, only
		// JDBC attributes can be set this way
		for (java.util.Enumeration e = inputSet.propertyNames(); e.hasMoreElements(); ) {

			String key = (String) e.nextElement();

			// we don't allow properties to be set this way
			if (key.startsWith("derby."))
				continue;
			String val = inputSet.getProperty(key);
			if (val != null) {
			  limited.put(key, val);
			}
			else {
			  limited.put(key, inputSet.get(key));
			}
		}
		return limited;
	}

	/*
	** methods to be overridden by subimplementations wishing to insert
	** their classes into the mix.
	*/

	protected Database getDatabase() 
	{
		if (SanityManager.DEBUG_ASSERT)
			SanityManager.ASSERT(!isClosed(), "connection is closed");

		return getTR().getDatabase();
	}
        // Gemstone changes BEGIN
	// changed method from protected to public
	final public TransactionResourceImpl getTR()
	// Gemstone changes END
	{
		return rootConnection.tr;
	}
// GemStone changes BEGIN
	public TransactionResourceImpl getTRResource() throws SQLException {
	    return getTR();
	  }
// GemStone changes END

	private EmbedConnectionContext pushConnectionContext(ContextManager cm) {
		return new EmbedConnectionContext(cm, this);
	}

	public final void setApplicationConnection(java.sql.Connection applicationConnection) {
		this.applicationConnection = applicationConnection;
	}

	public final java.sql.Connection getApplicationConnection() {
		return applicationConnection;
	}

	public void setDrdaID(String drdaID) {
		getLanguageConnection().setDrdaID(drdaID);
	}

	/**
		Reset the connection before it is returned from a PooledConnection
		to a new application request (wrapped by a BrokeredConnection).
		Examples of reset covered here is dropping session temporary tables
		and reseting IDENTITY_VAL_LOCAL.
		Most JDBC level reset is handled by calling standard java.sql.Connection
		methods from EmbedPooledConnection.
	 */
	public void resetFromPool() throws SQLException {
		synchronized (getConnectionSynchronization())
		{
			setupContextStack(false);
			try {
				getLanguageConnection().resetFromPool();
			} catch (StandardException t) {
				throw handleException(t);
			}
			finally
			{
				restoreContextStack();
			}
		}
	}

	/*
	** methods to be overridden by subimplementations wishing to insert
	** their classes into the mix.
	** The reason we need to override them is because we want to create a
	** Local20/LocalStatment object (etc) rather than a Local/LocalStatment
	** object (etc).
	*/


	/*
	** XA support
	*/

    /**
     * Do not use this method directly use XATransactionState.xa_prepare
     * instead because it also maintains/cancels the timout task which is
     * scheduled to cancel/rollback the global transaction.
     */
	public final int xa_prepare() throws SQLException {

		synchronized (getConnectionSynchronization())
		{
            setupContextStack(true);
			try
			{
				XATransactionController tc = 
					(XATransactionController) getLanguageConnection().getTransactionExecute();

				int ret = tc.xa_prepare();

				if (ret == XATransactionController.XA_RDONLY)
				{
					// On a prepare call, xa allows an optimization that if the
					// transaction is read only, the RM can just go ahead and
					// commit it.  So if store returns this read only status -
					// meaning store has taken the liberty to commit already - we
					// needs to turn around and call internalCommit (without
					// committing the store again) to make sure the state is
					// consistent.  Since the transaction is read only, there is
					// probably not much that needs to be done.

					getLanguageConnection().internalCommit(false /* don't commitStore again */);
				}
				return ret;
			} catch (StandardException t)
			{
				throw handleException(t);
			}
			finally
			{
				restoreContextStack();
			}
		}
	}


    /**
     * Do not use this method directly use XATransactionState.xa_commit
     * instead because it also maintains/cancels the timout task which is
     * scheduled to cancel/rollback the global transaction.
     */
	public final void xa_commit(boolean onePhase) throws SQLException {

		synchronized (getConnectionSynchronization())
		{
            setupContextStack(true);
			try
			{
		    	getLanguageConnection().xaCommit(onePhase);
			} catch (StandardException t)
			{
				throw handleException(t);
			}
			finally 
			{
				restoreContextStack();
			}
		}
	}


    /**
     * Do not use this method directly use XATransactionState.xa_rollback
     * instead because it also maintains/cancels the timout task which is
     * scheduled to cancel/rollback the global transaction.
     */
	public final void xa_rollback() throws SQLException {
		synchronized (getConnectionSynchronization())
		{
            setupContextStack(true);
			try
			{
		    	getLanguageConnection().xaRollback();
			} catch (StandardException t)
			{
				throw handleException(t);
			}
			finally 
			{
				restoreContextStack();
			}
		}
	}


	/**
	 * returns false if there is an underlying transaction and that transaction
	 * has done work.  True if there is no underlying transaction or that
	 * underlying transaction is idle
	 */
	public final boolean transactionIsIdle()
	{
		return getTR().isIdle();
	}

	private int setResultSetType(int resultSetType) {
		/* Add warning if scroll sensitive cursor
		 * and downgrade to scroll insensitive cursor.
		 */
		if (resultSetType == ResultSet.TYPE_SCROLL_SENSITIVE)
		{
			addWarning(SQLWarningFactory.newSQLWarning(SQLState.NO_SCROLL_SENSITIVE_CURSORS));
			resultSetType = ResultSet.TYPE_SCROLL_INSENSITIVE;
		}
		return resultSetType;
	}
	

	/** 
	 * Set the transaction isolation level that will be used for the 
	 * next prepare.  Used by network server to implement DB2 style 
	 * isolation levels.
	 * @param level Isolation level to change to.  level is the DB2 level
	 *               specified in the package names which happen to correspond
	 *               to our internal levels. If 
	 *               level == ExecutionContext.UNSPECIFIED_ISOLATION,
	 *               the statement won't be prepared with an isolation level.
	 * 
	 * 
	 */
	public void setPrepareIsolation(int level) throws SQLException
	{
		if (level == getPrepareIsolation())
			return;

		switch (level)
		{
			case ExecutionContext.READ_UNCOMMITTED_ISOLATION_LEVEL:
			case ExecutionContext.REPEATABLE_READ_ISOLATION_LEVEL:
			case ExecutionContext.READ_COMMITTED_ISOLATION_LEVEL:
			case ExecutionContext.SERIALIZABLE_ISOLATION_LEVEL:
			case ExecutionContext.UNSPECIFIED_ISOLATION_LEVEL:
				break;
			default:
				throw Util.generateCsSQLException(
															   SQLState.UNIMPLEMENTED_ISOLATION_LEVEL, new Integer(level));
		}
		
		synchronized(getConnectionSynchronization())
		{
			getLanguageConnection().setPrepareIsolationLevel(level);
		}
	}

	/**
	 * Return prepare isolation 
	 */
	public int getPrepareIsolation()
	{
		return getLanguageConnection().getPrepareIsolationLevel();
	}

	/**
		Return a unique order number for a result set.
		A unique value is only needed if the result set is
		being created within procedure and thus must be using
		a nested connection.
	*/
	final int getResultSetOrderId() {

		if (this == rootConnection) {
			return 0;
		} else {
			return rootConnection.resultSetId++;
		}
	}

	protected SQLException newSQLException(String messageId) {
		return Util.generateCsSQLException(messageId);
	}
	protected SQLException newSQLException(String messageId, Object arg1) {
		return Util.generateCsSQLException(messageId, arg1);
	}
	protected SQLException newSQLException(String messageId, Object arg1, Object arg2) {
		return Util.generateCsSQLException(messageId, arg1, arg2);
	}

	/////////////////////////////////////////////////////////////////////////
	//
	//	OBJECT OVERLOADS
	//
	/////////////////////////////////////////////////////////////////////////

    /**
     * Get a String representation that uniquely identifies
     * this connection.  Include the same information that is
     * printed in the log for various trace and error messages.
     *
     * In Derby the "physical" connection is a LanguageConnectionContext, 
     * or LCC.
     * The JDBC Connection is an JDBC-specific layer on top of this.  Rather
     * than create a new id here, we simply use the id of the underlying LCC.
     * Note that this is a big aid in debugging, because much of the
     * engine trace and log code prints the LCC id. 
     *
     * @return a string representation for this connection
     */
    public String toString()
    {
        if ( connString == null )
        {
            
            LanguageConnectionContext lcc = getLanguageConnection();

// GemStone changes BEGIN
            StringBuilder sb = new StringBuilder();
            sb.append("EmbedConnection@").append(this.hashCode()).append(' ')
              .append(" (CONNID = ").append(this.connID).append(") ");
            if (this.connID != this.incomingID) {
              sb.append(" (INCOMINGID = ").append(this.incomingID);
            }
            sb.append(" (REMOTE = ").append(isRemoteConnection).append(") ");
            if (lcc != null) {
              sb.append(lcc.xidStr).append(lcc.getTransactionExecute() != null
                  ? lcc.getTransactionExecute().getTransactionIdString()
                  : "NULL").append("), ").append(lcc.lccStr).append(Integer
                  .toString(lcc.getInstanceNumber())).append("), ")
                .append(lcc.dbnameStr).append(lcc.getDbname()).append("), ")
                .append(lcc.drdaStr).append(lcc.getDrdaID()).append(") ");
              connString = sb.toString();
            }
            else {
              return sb.toString();
            }
// GemStone changes END
        }       
        
        return connString;
    }


	/**
	*
	* Constructs an object that implements the <code>Clob</code> interface. The object
	* returned initially contains no data.  The <code>setAsciiStream</code>,
	* <code>setCharacterStream</code> and <code>setString</code> methods of
	* the <code>Clob</code> interface may be used to add data to the <code>Clob</code>.
	*
	* @return An object that implements the <code>Clob</code> interface
	* @throws SQLException if an object that implements the
	* <code>Clob</code> interface can not be constructed, this method is
	* called on a closed connection or a database access error occurs.
	*
	*/
	public EmbedClob createClob() throws SQLException {
		checkIfClosed();
		return new EmbedClob(this);
	}

	/**
	*
	* Constructs an object that implements the <code>Blob</code> interface. The object
	* returned initially contains no data.  The <code>setBinaryStream</code> and
	* <code>setBytes</code> methods of the <code>Blob</code> interface may be used to add data to
	* the <code>Blob</code>.
	*
	* @return  An object that implements the <code>Blob</code> interface
	* @throws SQLException if an object that implements the
	* <code>Blob</code> interface can not be constructed, this method is
	* called on a closed connection or a database access error occurs.
	*
	*/
	public EmbedBlob createBlob() throws SQLException {
		checkIfClosed();
		return new EmbedBlob(new byte[0], this);
	}

	/**
	* Add the locator and the corresponding LOB object into the
	* HashMap
	*
	* @param LOBReference The object which contains the LOB object that
	*                     that is added to the HashMap.
	* @return an integer that represents the locator that has been
	*         allocated to this LOB.
	*/
	public int addLOBMapping(Object LOBReference) {
		int loc = getIncLOBKey();
// GemStone changes BEGIN
		getlobHMObj().putPrimitive(loc, LOBReference);
		/* (original code)
		getlobHMObj().put(new Integer(loc), LOBReference);
		*/
// GemStone changes END
		return loc;
	}

	/**
	* Remove the key(LOCATOR) from the hash table.
	* @param key an integer that represents the locator that needs to be
	*            removed from the table.
	*/
// GemStone changes BEGIN
	@Override
	public void removeLOBMapping(long key) {
		// changed to Integer.valueOf()
		getlobHMObj().removePrimitive(key);
		/* (original code)
		getlobHMObj().remove(new Integer(key));
		*/
// GemStone changes END
	}

	/**
	* Get the LOB reference corresponding to the locator.
	* @param key the integer that represents the LOB locator value.
	* @return the LOB Object corresponding to this locator.
	*/
// GemStone changes BEGIN
	public Object getLOBMapping(long key) {
		return getlobHMObj().getPrimitive(key);
		// changed to use Integer.valueOf()
		/* (original code)
		return getlobHMObj().get(new Integer(key));
		 */
	}

	@Override
	public boolean hasLOBs() {
	  final ConcurrentTLongObjectHashMap<Object> m = rootConnection.lobHashMap;
	  return m != null && m.size() > 0;
	}
// GemStone changes END

	/**
	* Clear the HashMap of all entries.
	* Called when a commit or rollback of the transaction
	* happens.
	*/
	public void clearLOBMapping() throws SQLException {

		//free all the lob resources in the HashMap
		//initialize the locator value to 0 and
		//the hash table object to null.
		Map map = rootConnection.lobReferences;
		if (map != null) {
            Iterator it = map.keySet ().iterator ();
            while (it.hasNext()) {
                ((EngineLOB)it.next()).free();
			}
			map.clear();
		}
        if (rootConnection.lobHashMap != null) {
            rootConnection.lobHashMap.clear ();
        }
	}

	/**
	* Return the current locator value/
        * 0x800x values are not  valid values as they are used to indicate the BLOB 
        * is being sent by value, so we skip those values (DERBY-3243)
        * 
	* @return an integer that represents the most recent locator value.
	*/
	private int getIncLOBKey() {
	    synchronized (getConnectionSynchronization()) {
                int newKey = ++rootConnection.lobHMKey;
                // Skip 0x8000, 0x8002, 0x8004, 0x8006, for DERBY-3243
                // Earlier versions of the Derby Network Server (<10.3) didn't
                // support locators and would send an extended length field
                // with one of the above mentioned values instead of a
                // locator, even when locators were requested. To enable the
                // client driver to detect that locators aren't supported,
                // we don't use any of them as locator values.
                if (newKey == 0x8000 || newKey == 0x8002 ||  newKey == 0x8004 ||
                     newKey == 0x8006 || newKey == 0x8008)
                    newKey = ++rootConnection.lobHMKey;
                // Also roll over when the high bit of four byte locator is set.
                // This will prevent us from sending a negative locator to the
                // client. Don't allow zero since it is not a valid locator for the
                // client.
                if (newKey == 0x80000000 || newKey == 0)
                    newKey = rootConnection.lobHMKey = 1;
                return newKey;
	    }
	}

    /**
     * Adds an entry of the lob in WeakHashMap. These entries are used
     * for cleanup during commit/rollback or close.
     * @param lobReference LOB Object
     */
    void addLOBReference (Object lobReference) {
        if (rootConnection.lobReferences == null) {
            rootConnection.lobReferences = new WeakHashMap ();
        }
        rootConnection.lobReferences.put (lobReference, null);
    }

	/**
	* Return the Hash Map in the root connection
	* @return the HashMap that contains the locator to LOB object mapping
	*/
	public final ConcurrentTLongObjectHashMap<Object> getlobHMObj() {
		if (rootConnection.lobHashMap == null) {
			rootConnection.lobHashMap = new ConcurrentTLongObjectHashMap<Object>();
		}
		return rootConnection.lobHashMap;
	}

    /** Cancels the current running statement. */
    public void cancelRunningStatement() {
        getLanguageConnection().getStatementContext().cancel();
    }

    /**
     * Obtain the name of the current schema. Not part of the
     * java.sql.Connection interface, but is accessible through the
     * EngineConnection interface, so that the NetworkServer can get at the
     * current schema for piggy-backing
     * @return the current schema name
     */
    public String getCurrentSchemaName() {
        return getLanguageConnection().getCurrentSchemaName();
    }
// GemStone changes BEGIN

  Throwable getBootDatabaseFailureCause(StandardException mse) {
    Throwable ne = mse.getCause();
    // avoid too much wrapping (#42595)
    if (ne == null) {
      ne = mse;
    }
    else {
      // unwrap useless wrappings in XJ040/XJ041
      while (ne instanceof StandardException
          && ("XJ040".equals((mse = (StandardException)ne).getSQLState()) || "XJ041"
              .equals(mse.getSQLState()))) {
        ne = mse.getCause();
      }
    }
    if (ne instanceof StandardException) {
      // change to SQLException
      ne = Util.generateCsSQLException((StandardException)ne);
    }
    return ne;
  }

  // Asif:This is an internal use method. Should we change its name?
  /**
   * @param id
   *          long uniquely identifying the connection. This method will get
   *          invoked only through StatementQueryExecutirFunction
   */
  public final Statement createStatement(long id) throws SQLException {
    return createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY, connectionHoldAbility, id);
  }

  // Invoked only from PreparedStatementQueryExecutor
  /**
   * This method is only invoked on the remote VM through the
   * PrepStatementQueryExecutor function. The flag createQueryInfo is passed as
   * false from this function so the queryInfo object is not generated. It uses
   * the id which is passed from the query node. The other public API methods
   * generate their own unqiue ID. The public APIs are expected to be invoked
   * only on the query node.
   * 
   * @param stmtID
   *          long unqiue identifier of the PreparedStatement in the distributed
   *          system
   * @param sql
   *          String representing the query for the PreparedStatement
   * @return PreparedStatement
   * @see StatementQueryExecutor#executePrepStatement
   */
  public final PreparedStatement prepareStatementByPassQueryInfo(long stmtID,
      String sql, boolean needGfxdSubactivation, boolean flattenSubquery,
      boolean allReplicated, THashMap ncjMetaData, long rootID, int stmtLevel)
      throws SQLException {
    return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY, connectionHoldAbility,
        Statement.NO_GENERATED_KEYS, null, null,
        false /* is Query (Parse) Node */, stmtID, needGfxdSubactivation,
        flattenSubquery, allReplicated, ncjMetaData, rootID, stmtLevel);
  }

  /**
   * Force a commit even if not in autocommit.
   * 
   * Used to force a commit after a result set closes in autocommit mode or for
   * GemFireXD isolation level NONE. The needCommit mechanism does not work
   * correctly as there are times with cursors (like a commit, followed by a
   * next, followed by a close) where the system does not think it needs a
   * commit but we need to force the commit on close. It seemed safer to just
   * force a commit on close rather than count on keeping the needCommit flag
   * correct for all cursor cases.
   * 
   * Must have connection synchonization and context set up already.
   * 
   * @exception SQLException
   *              if commit returns error
   */
  protected void forceCommit() throws SQLException {
    try {
      getTR().commit(this);
      clearLOBMapping();
    } catch (Throwable t) {
      throw handleException(t);
    }
    needCommit = false;
  }

  public final long getConnectionID() {
    return this.connID;
  }

  public final boolean isRemoteConnection() {
    return this.isRemoteConnection;
  }

  /**
   * Implementation of {@link ConnectionState} to distribute connection close
   * message in batches at some periodic interval to remote nodes.
   * 
   * @author Asif
   * @author swale
   */
  static final class ConnectionCloseState implements ConnectionState {

    private final long closeConnectionId;

    private TLongArrayList closeConnectionIds;

    ConnectionCloseState(long connId) {
      this.closeConnectionId = connId;
      this.closeConnectionIds = null;

      // close on this VM first
      final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
          .getInstance();
      if (observer == null) {
        DistributedConnectionCloseExecutorFunction.closeConnection(connId,
            GfxdConnectionHolder.getHolder());
      }
      else {
        final long[] connIds = new long[] { connId };
        observer.beforeConnectionCloseByExecutorFunction(connIds);
        DistributedConnectionCloseExecutorFunction.closeConnection(connId,
            GfxdConnectionHolder.getHolder());
        observer.afterConnectionCloseByExecutorFunction(connIds);
      }
    }

    @Override
    public boolean accumulate(ConnectionState other) {
      if (other instanceof ConnectionCloseState) {
        ConnectionCloseState otherState = (ConnectionCloseState)other;
        if (this.closeConnectionIds == null) {
          this.closeConnectionIds = new TLongArrayList();
          this.closeConnectionIds.add(this.closeConnectionId);
        }
        if (otherState.closeConnectionIds == null) {
          this.closeConnectionIds.add(otherState.closeConnectionId);
        }
        else {
          this.closeConnectionIds.add(otherState.closeConnectionIds);
        }
        return true;
      }
      return false;
    }

    /**
     * Distribute the close of connectionIDs accumulated so far.
     */
    @Override
    public void distribute() {
      try {
        long[] connIDs;
        if (this.closeConnectionIds != null) {
          connIDs = this.closeConnectionIds.toNativeArray();
        }
        else {
          connIDs = new long[] { this.closeConnectionId };
        }
        // send to other members
        Set<DistributedMember> otherMembers = GfxdMessage.getOtherServers();
        if (otherMembers.size() > 0) {
          FunctionService.onMembers(Misc.getDistributedSystem(), otherMembers)
              .withArgs(connIDs).execute(
                  DistributedConnectionCloseExecutorFunction.ID);
        }
      } catch (com.gemstone.gemfire.CancelException ex) {
        // let this be handled at a higher level signalling exit
        throw ex;
      } catch (Throwable t) {
        final LogWriterI18n logger = Misc.getI18NLogWriter();
        if (logger != null && logger.warningEnabled()) {
          logger.warning(
              LocalizedStrings.CONNECTION_CHANGE_PROCESS_FAILED, t);
        }
      }
    }

    @Override
    public int numChanges() {
      if (this.closeConnectionIds != null) {
        return this.closeConnectionIds.size();
      }
      return 1;
    }

    @Override
    public int minBatchSize() {
      return 5;
    }

    @Override
    public long waitMillis() {
      return 500L;
    }

    @Override
    public String toString() {
      return this.getClass().getName() + ": "
          + (this.closeConnectionIds != null ? this.closeConnectionIds
              .toString() : this.closeConnectionId);
    }
  }

  // GemStone changes BEGIN
  public long getDefaultNestedConnQueryTimeOut() {
    return this.defaultNestedConnQueryTimeOutMillis;
  }
  
  // JDBC 4.0 Dummy methods, so will compile in JDK 1.6
  public Array createArrayOf(String typeName, Object[] elements)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public NClob createNClob() throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public SQLXML createSQLXML() throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public Struct createStruct(String typeName, Object[] attributes)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public boolean isValid(int timeout) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setClientInfo(String name, String value)
  throws SQLClientInfoException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setClientInfo(Properties properties)
  throws SQLClientInfoException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public String getClientInfo(String name)
  throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public Properties getClientInfo()
  throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }

  public boolean isWrapperFor(Class<?> interfaces) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }

  public <T> T unwrap(java.lang.Class<T> interfaces) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }

  // for jdbc 4.1 from jdk 1.7
  public void setSchema(String schema) throws SQLException {
    setDefaultSchema(schema);
  }

  public String getSchema() throws SQLException {
    return getCurrentSchemaName();
  }

  public void abort(Executor executor) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }

  public void setNetworkTimeout(Executor executor, int milliseconds)
      throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }

  public int getNetworkTimeout() throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }

  @Override
  public final void setPossibleDuplicate(boolean isDup) {
    final LanguageConnectionContext lcc = getTR().getLcc();
    if (lcc != null) {
      lcc.setPossibleDuplicate(isDup);
    }
  }

  @Override
  public final void setEnableStreaming(boolean enable) {
    final LanguageConnectionContext lcc = getTR().getLcc();
    if (lcc != null) {
      lcc.setEnableStreaming(enable);
    }
  }

  @Override
  public final LanguageConnectionContext getLanguageConnectionContext() {
    return getTR().getLcc();
  }

  public final void setTXIdForFinalizer(final TXId txId) {
    final FinalizeEmbedConnection finalizer = this.finalizer;
    if (finalizer != null) {
      finalizer.txId = txId;
    }
  }

  /**
   * Internal method for setting up current schema.
   * 
   * @param schemaName
   * @throws SQLException
   */
  public void setDefaultSchema(String schemaName) throws SQLException {

    if (schemaName == null) {
      return;
    }

    final LanguageConnectionContext lcc;
    if ((lcc = getTR().getLcc()) == null
        || lcc.getDefaultSchema().getSchemaName().equals(schemaName)) {
      return;
    }

    synchronized (getConnectionSynchronization()) {
      if (isClosed()) {
        return;
      }
      setupContextStack(true);
      try {
        FabricDatabase.setupDefaultSchema(lcc.getDataDictionary(), lcc, lcc
            .getTransactionExecute(), schemaName, true);

      } catch (StandardException sqle) {
        throw new SQLException(sqle);

      } finally {
        restoreContextStack();
      }
    }
  }

  /**
   * The returned object should <b>NEVER</b> be held as a hard reference other
   * than local variable, else it will be a BIG time resource leak. Use & see
   * EmbedConnectionContext#getNestedConnection instead wherever possible.
   * <p>
   * This is primarily a hack for nested connection to execute DDLs by flexibly
   * switching on autoCommit at
   * {@link EmbedStatement#executeStatement}.
   * 
   * @return most root connection i.e. original user created connection.
   * @author soubhikc
   */
  public EmbedConnection getRootConnection() {
    EmbedConnection currentConn = this;

    while (currentConn.rootConnection != currentConn) {
      currentConn = currentConn.rootConnection;
    }

    return currentConn;
  }

  /**
   * Bump the severity of an exception to TRANSACTION level to abort the
   * transaction for cases where there may have been some affects to the system
   * which we cannot selectively cleanup due to lack of savepoints.
   */
  public static void abortForConstraintViolationInTX(final StandardException se,
      final LanguageConnectionContext lcc) {
    // for constraint violations within TX, need to abort the
    // TX due to #43170, so set severity appropriately
    if (abortForConstraintViolationInTX(se.getSQLState(), se.getSeverity(), lcc)) {
      se.setSeverity(ExceptionSeverity.TRANSACTION_SEVERITY);
    }
  }

  /**
   * Return true to bump the severity of an exception to TRANSACTION level to
   * abort the transaction for cases where there may have been some affects to
   * the system which we cannot selectively cleanup due to lack of savepoints.
   */
  public static boolean abortForConstraintViolationInTX(final String sqlState,
      final int severity, final LanguageConnectionContext lcc) {
    // conservatively return true if no LCC in current context for some reason
    if (lcc == null) {
      return true;
    }
    // for constraint violations within TX, need to abort the
    // TX due to #43170, so set severity appropriately
    if (severity < ExceptionSeverity.TRANSACTION_SEVERITY
        && (sqlState.startsWith(SQLState.INTEGRITY_VIOLATION_PREFIX)
            || sqlState.startsWith(SQLState.SQL_DATA_PREFIX)
            || sqlState.startsWith("XS") // other data corruption exceptions
            || sqlState.startsWith("38000") // unexpected user exception
            || sqlState.startsWith("XJ001") // java exceptions
            || sqlState.startsWith("XCL52"))) {
      final int isolationLevel = ExecutionContext
          .CS_TO_JDBC_ISOLATION_LEVEL_MAP[lcc.getCurrentIsolationLevel()];
      return isolationLevel != TRANSACTION_NONE;
    }
    // also check if current transaction has already been rolled back to due to
    // some nested execution (e.g. inside trigger bodies where user might have
    // wrapped or ignore the actual exception #51448)
    GemFireTransaction tc = (GemFireTransaction)lcc.getTransactionExecute();
    TXStateProxy tx;
    if (tc != null && (tx = tc.getCurrentTXStateProxy()) != null) {
      return !tx.isInProgress() || tx.getTXInconsistent() != null;
    }
    return false;
  }

  @SuppressWarnings("serial")
  static final class FinalizeEmbedConnection extends FinalizeObject {

    private ContextManager cm;

    private long connId;

    private final boolean forRemote;

    private final State active;

    private final boolean nestedConn;
    private final boolean clientConn;
    boolean internalConn;
    boolean skipCloseStats;
    private final long beginTime;

    private TXId txId;

    public FinalizeEmbedConnection(final EmbedConnection conn,
        final ContextManager cm, final long connId, final boolean forRemote,
        final State active, boolean nestConn, boolean clientConn,
        long beginTime) {
      super(conn, true);
      this.cm = cm;
      this.connId = connId;
      this.forRemote = forRemote;
      this.active = active;
      this.nestedConn = nestConn;
      this.clientConn = clientConn;
      this.beginTime = beginTime;
    }

    @Override
    public final FinalizeHolder getHolder() {
      return getServerHolder();
    }

    @Override
    protected void clearThis() {
      this.cm = null;
      this.connId = UNINITIALIZED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected final boolean doFinalize() throws SQLException {
      if (this.active.state == 1) {
        // cleanup the CM from ContextService
        final ContextManager cm = this.cm;
        if (cm != null) {
          ContextService.removeContextManager(cm);
          this.cm = null;
        }
      }
      if (!this.forRemote && this.connId != UNINITIALIZED
          && this.connId != CHILD_NOT_CACHEABLE) {
        // try to rollback GFE transaction, if any; at this stage we don't
        // have enough of information to successfully rollback from GFXD layer
        // but just rolling back GFE transaction will be enough
        if (this.txId != null && this.connId >= 0) {
          final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
          if (cache != null) {
            final TXManagerImpl txMgr = cache.getTxManager();
            final TXStateProxy txProxy = txMgr.getHostedTXState(
                this.txId);
            if (txProxy != null && txProxy.isInProgress()) {
              TXManagerImpl.TXContext context = null;
              TXStateInterface previousTX = null;
              try {
                context = TXManagerImpl.getOrCreateTXContext();
                previousTX = context.getTXState();
                txMgr.setTXState(txProxy, context);
                txProxy.rollback(this.connId);
              } catch (Throwable t) {
                Error err;
                if (t instanceof Error && SystemFailure.isJVMFailureError(
                    err = (Error)t)) {
                  SystemFailure.initiateFailure(err);
                  // If this ever returns, rethrow the error. We're poisoned
                  // now, so don't let this thread continue.
                  throw err;
                }
                SanityManager.DEBUG_PRINT("warning:FinalizeEmbedConnection",
                    "unexpected exception while invoking finalizer", t);
              } finally {
                if (context != null) {
                  txMgr.setTXState(previousTX, context);
                }
              }
            }
          }
        }
        // Add to the closed connection IDs List
        ConnectionSignaller.getInstance().add(
            new ConnectionCloseState(this.connId));
      }
      this.active.state = 0;
      if (!this.skipCloseStats) {
        incrementCloseStats(nestedConn, internalConn, clientConn, beginTime);
      }
      return true;
    }
  }

  public void setExecutionSequence(int execSeq) {
    LanguageConnectionContext lcc = this.getLanguageConnection();
    assert lcc != null : "expected non null lcc object";
    GemFireTransaction gft = (GemFireTransaction)lcc.getTransactionExecute();
    gft.setExecutionSeqInTXState(execSeq);
  }

  public Checkpoint masqueradeAsTxn(TXId txid, int isolationLevel)
      throws SQLException {
    LanguageConnectionContext lcc = this.getLanguageConnection();
    assert lcc != null: "expected non null lcc object";
    GemFireTransaction gft = (GemFireTransaction)lcc.getTransactionExecute();
    return gft.masqueradeAsTxn(txid, isolationLevel);
  }

  public void updateAffectedRegion(Bucket b) {
    LanguageConnectionContext lcc = this.getLanguageConnection();
    assert lcc != null : "expected non null lcc object";
    GemFireTransaction gft = (GemFireTransaction)lcc.getTransactionExecute();
    gft.addAffectedRegion(b);
  }
// GemStone changes END
}
