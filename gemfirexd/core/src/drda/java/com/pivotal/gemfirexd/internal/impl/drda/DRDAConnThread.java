/*
    Derby - Class com.pivotal.gemfirexd.internal.impl.drda.DRDAConnThread

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
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

package com.pivotal.gemfirexd.internal.impl.drda;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicLong;


// GemStone changes BEGIN
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.Checkpoint;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.cache.partitioned.PRLocallyDestroyedException;
import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.shared.UnsupportedGFXDVersionException;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.tcp.ConnectionTable;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.sql.execute.SnappySelectResultSet;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
import com.pivotal.gemfirexd.internal.iapi.services.io.ApplicationObjectInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory;
// now using base DerbySQLException
//import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedSQLException;
// GemStone changes END
import com.pivotal.gemfirexd.internal.iapi.error.DerbySQLException;
import com.pivotal.gemfirexd.internal.iapi.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.AuthenticationService;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineConnection;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineLOB;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EnginePreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.jdbc.WrapperEngineBLOB;
import com.pivotal.gemfirexd.internal.iapi.jdbc.WrapperEngineCLOB;
import com.pivotal.gemfirexd.internal.iapi.reference.DRDAConstants;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC30Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.info.JVMInfo;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.services.stream.HeaderPrintWriter;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.iapi.types.HarmonySerialClob;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.SingleHopInformation;
import com.pivotal.gemfirexd.internal.shared.common.io.DynamicByteArrayOutputStream;

/**
 * This class translates DRDA protocol from an application requester to JDBC
 * for Derby and then translates the results from Derby to DRDA
 * for return to the application requester.
 */
class DRDAConnThread extends Thread {

// GemStone changes BEGIN
  // Adding extra whitespace so that log message doesn't show up as suspect string.
  // Avoids complexity of regular expressions in any search (for clients too)
  private static final String leftBrace = "{  ";
  private static final String rightBrace = "  }";

  private static final byte PRPSQLSTT_ERROR = -1;

  private static final String SET_SCHEMA_STATEMENT =
      "SET CONNECTION_SCHEMA ";
  private String pendingSetSchema;
  private volatile long beginWaitMillis;
  private final AtomicLong numTimesWaited = new AtomicLong(0);
  private final AtomicLong waitTime = new AtomicLong(0);
  private final AtomicLong processTime = new AtomicLong(0);
  private final AtomicLong numCmdsProcessed = new AtomicLong(0);
//  private String sqlstmnt;
// GemStone changes END
	private static final byte NULL_VALUE = (byte)0xff;
	private static final String SYNTAX_ERR = "42X01";

	// Manager Level 3 constant.
	private static final int MGRLVL_3 = 0x03;

	// Manager Level 4 constant.
	private static final int MGRLVL_4 = 0x04;

	// Manager Level 5 constant.
	private static final int MGRLVL_5 = 0x05;

	// Manager level 6 constant.
	private static final int MGRLVL_6 = 0x06;

	// Manager Level 7 constant.
	private static final int MGRLVL_7 = 0x07;


	// Commit or rollback UOWDSP values
	private static final int COMMIT = 1;
	private static final int ROLLBACK = 2;


	protected CcsidManager ccsidManager = new EbcdicCcsidManager();
	protected final boolean trace;
	private int correlationID;
	private InputStream sockis;
	private OutputStream sockos;
	private DDMReader reader;
	private DDMWriter writer;
	private DRDAXAProtocol xaProto;

	private static int [] ACCRDB_REQUIRED = {CodePoint.RDBACCCL, 
											 CodePoint.CRRTKN,
											 CodePoint.PRDID,
											 CodePoint.TYPDEFNAM,
											 CodePoint.TYPDEFOVR};

	private static int MAX_REQUIRED_LEN = 5;

	private int currentRequiredLength = 0;
	private int [] required = new int[MAX_REQUIRED_LEN];


	private NetworkServerControlImpl server;			// server who created me
	private Session	session;	// information about the session
	private long timeSlice;				// time slice for this thread
	private Object timeSliceSync = new Object(); // sync object for updating time slice 
	private boolean logConnections;		// log connections to databases

	private boolean	sendWarningsOnCNTQRY = false;	// Send Warnings for SELECT if true
	private Object logConnectionsSync = new Object(); // sync object for log connect
	private boolean close;				// end this thread
	private Object closeSync = new Object();	// sync object for parent to close us down
	private static HeaderPrintWriter logStream;
	private AppRequester appRequester;	// pointer to the application requester
										// for the session being serviced
	private Database database; 	// pointer to the current database
	private int sqlamLevel;		// SQLAM Level - determines protocol

	// DRDA diagnostic level, DIAGLVL0 by default 
	private byte diagnosticLevel = (byte)0xF0; 

	// manager processing
	private Vector unknownManagers;
	private Vector knownManagers;
	private Vector errorManagers;
	private Vector errorManagersLevel;

	// database accessed failed
	private SQLException databaseAccessException;

	// these fields are needed to feed back to jcc about a statement/procedure's PKGNAMCSN
	/** The value returned by the previous call to
	 * <code>parsePKGNAMCSN()</code>. */
	private Pkgnamcsn prevPkgnamcsn = null;
	/** Current RDB Package Name. */
	private DRDAString rdbnam = new DRDAString(ccsidManager);
	/** Current RDB Collection Identifier. */
	private DRDAString rdbcolid = new DRDAString(ccsidManager);
	/** Current RDB Package Identifier. */
	private DRDAString pkgid = new DRDAString(ccsidManager);
	/** Current RDB Package Consistency Token. */
	private DRDAString pkgcnstkn = new DRDAString(ccsidManager);
	/** Current RDB Package Section Number. */
	private int pkgsn;

    private final static String TIMEOUT_STATEMENT = "SET STATEMENT_TIMEOUT ";

    private int pendingStatementTimeout; // < 0 means no pending timeout to set

	// this flag is for an execute statement/procedure which actually returns a result set;
	// do not commit the statement, otherwise result set is closed

	// for decryption
	private static DecryptionManager decryptionManager;

	// public key generated by Deffie-Hellman algorithm, to be passed to the encrypter,
	// as well as used to initialize the cipher
	private byte[] myPublicKey;

    // generated target seed to be used to generate the password substitute
    // as part of SECMEC_USRSSBPWD security mechanism
    private byte[] myTargetSeed;

    // Some byte[] constants that are frequently written into messages. It is more efficient to 
    // use these constants than to convert from a String each time 
    // (This replaces the qryscraft_ and notQryscraft_ static exception objects.)
    private static final byte[] eod00000 = { '0', '0', '0', '0', '0' };
    private static final byte[] eod02000 = { '0', '2', '0', '0', '0' };
    private static final byte[] nullSQLState = { ' ', ' ', ' ', ' ', ' ' };
    private static final byte[] errD4_D6 = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }; // 12x0 
    private static final byte[] warn0_warnA = { ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ' };  // 11x ' '

    private final static String AUTHENTICATION_PROVIDER_BUILTIN_CLASS =
    "com.pivotal.gemfirexd.internal.impl.jdbc.authentication.BasicAuthenticationServiceImpl";

    private final static String AUTHENTICATION_PROVIDER_NONE_CLASS =
    "com.pivotal.gemfirexd.internal.impl.jdbc.authentication.NoneAuthenticationServiceImpl";

    // Work around a classloader bug involving interrupt handling during
    // class loading. If the first request to load the
    // DRDAProtocolExceptionInfo class occurs during shutdown, the
    // loading of the class may be aborted when the Network Server calls
    // Thread.interrupt() on the DRDAConnThread. By including a static
    // reference to the DRDAProtocolExceptionInfo class here, we ensure
    // that it is loaded as soon as the DRDAConnThread class is loaded,
    // and therefore we know we won't be trying to load the class during
    // shutdown. See DERBY-1338 for more background, including pointers
    // to the apparent classloader bug in the JVM.
	private static final DRDAProtocolExceptionInfo dummy =
		new DRDAProtocolExceptionInfo(0,0,0,false);
    /**
     * Tells if the reset / connect request is a deferred request.
     * This information is used to work around a bug (DERBY-3596) in a
     * compatible manner, which also avoids any changes in the client driver.
     * <p>
     * The bug manifests itself when a connection pool data source is used and
     * logical connections are obtained from the physical connection associated
     * with the data source. Each new logical connection causes a new physical
     * connection on the server, including a new transaction. These connections
     * and transactions are not closed / cleaned up.
     */
    private boolean deferredReset = false;

// GemStone changes BEGIN
    private Version gfxdClientVersion;
    private TXId currTxIdForSHOPExecution;
// GemStone changes END
    /**
     * Flag to indicate that this is servicing a GFXD client and not some other
     * DRDA client so that internal failover context etc. can be read
     */
	// constructor
	/**
	 * Create a new Thread for processing session requests
	 *
	 * @param session Session requesting processing
	 * @param server  Server starting thread
	 * @param timeSlice timeSlice for thread
	 * @param logConnections
	 **/

	DRDAConnThread(Session session, NetworkServerControlImpl server, 
						  long timeSlice,
						  boolean logConnections) {
	
   	super();

		// Create a more meaningful name for this thread (but preserve its
		// thread id from the default name).
		NetworkServerControlImpl.setUniqueThreadName(this, "DRDAConnThread");

		this.session = session;
		this.server = server;
		this.trace = SanityManager.DEBUG && server.debugOutput;
		this.timeSlice = timeSlice;
		this.logConnections = logConnections;
        this.pendingStatementTimeout = -1;
		initialize();
    }

	/**
	 * Main routine for thread, loops until the thread is closed
	 * Gets a session, does work for the session
	 */
    public void run() {
		if (this.trace)
			trace("Starting new connection thread");

		Session prevSession;
		while(!closed())
		{

			// get a new session
			prevSession = session;
			session = server.getNextSession(session);
			if (session == null)
				close();

			if (closed())
				break;
			if (session != prevSession)
			{
				initializeForSession();
			}
			try {
				long timeStart = System.currentTimeMillis();

				switch (session.state)
				{
					case Session.INIT:
						sessionInitialState();
						if (session == null)
							break;
                        // else fallthrough
					case Session.ATTEXC:
					case Session.SECACC:
					case Session.CHKSEC:
						long currentTimeSlice;

						do {
                            try {
                                processCommands();
                            } catch (DRDASocketTimeoutException ste) {
                                // Just ignore the exception. This was
                                // a timeout on the read call in
                                // DDMReader.fill(), which will happen
                                // only when timeSlice is set.
                            }
							currentTimeSlice = getTimeSlice();
						} while ((currentTimeSlice <= 0)  || 
							(System.currentTimeMillis() - timeStart < currentTimeSlice));

						break;
					default:
						// this is an error
						agentError("Session in invalid state:" + session.state);
				}
			} catch (Exception e) {
// GemStone changes BEGIN
			  com.gemstone.gemfire.internal.cache.GemFireCacheImpl cache;
			  final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
			        .getInstance();
			  boolean cacheCloseFortesting = false;
			  if (observer != null) {
			    cacheCloseFortesting = observer.isCacheClosedForTesting();
			  }
// GemStone changes END
				if (e instanceof DRDAProtocolException && 
						((DRDAProtocolException)e).isDisconnectException())
				{
				 	// client went away - this is O.K. here
					closeSession();
				}
// GemStone changes BEGIN
				// ignore if shutting down
				else if (this.server.getShutdown()
				    || (cache = com.pivotal.gemfirexd.internal
				        .engine.Misc.getGemFireCacheNoThrow()) == null
				    || cache.getCancelCriterion().cancelInProgress() != null
				    || cacheCloseFortesting) {
				  // try to cleanup
				  if (SanityManager.TraceSingleHop || SanityManager.TraceClientHA) {
			            SanityManager.DEBUG_PRINT(
			                SanityManager.TRACE_SINGLE_HOP,
			                "DRDAConnThread::run got exception when cache shutting down: " + e, e);
			          }
				  closeSession();
				  close();
				}
// GemStone changes END
				else
				{
					handleException(e);
				}
			}
		}
		ConnectionTable.releaseThreadsSockets();
		if (this.trace)
			trace("Ending connection thread");
		server.removeThread(this);

	}
	/**
	 * Get input stream
	 *
	 * @return input stream
	 */
	protected InputStream getInputStream()
	{
		return sockis;
	}

	/**
	 * Get output stream
	 *
	 * @return output stream
	 */
	protected OutputStream getOutputStream()
	{
		return sockos;
	}

	/**
	 *  get DDMReader
	 * @return DDMReader for this thread
	 */
	protected DDMReader getReader()
	{
		return reader;
	}
	
	/** 
	 * get  DDMWriter 
	 * @return DDMWriter for this thread
	 */
	protected DDMWriter getWriter()
	{
		return writer;
	}

	/**
	 * Get correlation id
	 *
	 * @return correlation id
	 */
	protected int getCorrelationID ()
	{
		return correlationID;
	}

	/**
	 * Get session we are working on
	 *
	 * @return session
	 */
	protected Session getSession()
	{
		return session;
	}

	/**
	 * Get Database we are working on
	 *
	 * @return database
	 */
	protected Database getDatabase()
	{
		return database;
	}
	/**
	 * Get server
	 *
	 * @return server
	 */
	protected NetworkServerControlImpl getServer()
	{
		return server;
	}
	/**
	 * Get correlation token
	 *
	 * @return crrtkn
	 */
	protected byte[] getCrrtkn()
	{
		if (database != null)
			return database.crrtkn;
		return null;
	}
	/**
	 * Get database name
	 *
	 * @return database name
	 */
	protected String getDbName()
	{
		if (database != null)
			return database.dbName;
		return null;
	}
	/**
	 * Close DRDA  connection thread
	 */
	protected void close()
	{
		synchronized (closeSync)
		{
			close = true;
		}
	}

	/**
	 * Set logging of connections
	 * 
	 * @param value value to set for logging connections
	 */
	protected void setLogConnections(boolean value)
	{
		synchronized(logConnectionsSync) {
			logConnections = value;
		}
	}
	/**
	 * Set time slice value
	 *
	 * @param value new value for time slice
	 */
	protected void setTimeSlice(long value)
	{
		synchronized(timeSliceSync) {
			timeSlice = value;
		}
	}
	/**
	 * Indicate a communications failure
	 * 
	 * @param arg1 - info about the communications failure
	 * @param arg2 - info about the communications failure
	 * @param arg3 - info about the communications failure
	 * @param arg4 - info about the communications failure
	 *
	 * @exception DRDAProtocolException  disconnect exception always thrown
	 */
	protected void markCommunicationsFailure(String arg1, String arg2, String arg3,
		String arg4) throws DRDAProtocolException
	{
	    markCommunicationsFailure(null,arg1,arg2,arg3, arg4);

	}
        
        
        /**
         * Indicate a communications failure. Log to gemfirexd.log
         * 
         * @param e  - Source exception that was thrown
         * @param arg1 - info about the communications failure
         * @param arg2 - info about the communications failure
         * @param arg3 - info about the communications failure
         * @param arg4 - info about the communications failure
         *
         * @exception DRDAProtocolException  disconnect exception always thrown
         */
        protected void markCommunicationsFailure(Exception e, String arg1, String arg2, String arg3,
                String arg4) throws DRDAProtocolException
        {
            String dbname = null;
   
            if (database != null)
            {
                dbname = database.dbName;
            }
            if (e != null) {
                println2Log(dbname,session.drdaID, e.getMessage());
                server.consoleExceptionPrintTrace(e);
            }
        
            Object[] oa = {arg1,arg2,arg3,arg4};
            throw DRDAProtocolException.newDisconnectException(this,oa);
        }

	/**
	 * Syntax error
	 *
	 * @param errcd		Error code
	 * @param cpArg  code point value
	 * @exception DRDAProtocolException
	 */

	protected  void throwSyntaxrm(int errcd, int cpArg)
		throws DRDAProtocolException
	{
		throw new
			DRDAProtocolException(DRDAProtocolException.DRDA_Proto_SYNTAXRM,
								  this,
								  cpArg,
								  errcd);
	}
	/**
	 * Agent error - something very bad happened
	 *
	 * @param msg	Message describing error
	 *
	 * @exception DRDAProtocolException  newAgentError always thrown
	 */
	protected void agentError(String msg) throws DRDAProtocolException
	{

		String dbname = null;
		if (database != null)
			dbname = database.dbName;
		throw DRDAProtocolException.newAgentError(this, CodePoint.SVRCOD_PRMDMG, 
			dbname, msg);
	}
	/**
	 * Missing code point
	 *
	 * @param codePoint  code point value
	 * @exception DRDAProtocolException
	 */
	protected void missingCodePoint(int codePoint) throws DRDAProtocolException
	{
		throwSyntaxrm(CodePoint.SYNERRCD_REQ_OBJ_NOT_FOUND, codePoint);
	}
	/**
	 * Print a line to the DB2j log
	 *
	 * @param dbname  database name
	 * @param drdaID	DRDA identifier
	 * @param msg	message
	 */
	protected static void println2Log(String dbname, String drdaID, String msg)
	{
		if (logStream == null)
			logStream = Monitor.getStream();

		if (dbname != null)
		{
			int endOfName = dbname.indexOf(';');
			if (endOfName != -1)
				dbname = dbname.substring(0, endOfName);
		}
		logStream.printlnWithHeader("(DATABASE = " + dbname + "), (DRDAID = " + drdaID + "), " + msg);
	}
	/**
	 * Write RDBNAM
	 *
	 * @param rdbnam 	database name
	 * @exception DRDAProtocolException
	 */
	protected void writeRDBNAM(String rdbnam)
		throws DRDAProtocolException
	{
		int len = rdbnam.length();
		if (len < CodePoint.RDBNAM_LEN)
			len = CodePoint.RDBNAM_LEN;
		writer.writeScalarHeader(CodePoint.RDBNAM, len);
		try {
			writer.writeScalarPaddedBytes(rdbnam.getBytes(server.DEFAULT_ENCODING),
				len, server.SPACE_CHAR);
		}
		catch (UnsupportedEncodingException e)
		{
			agentError("Unsupported coding exception for server encoding "
				+ server.DEFAULT_ENCODING);
		}
	}
	/***************************************************************************
	 *                   Private methods
	 ***************************************************************************/

	/**
	 * Initialize class
	 */
	private void initialize()
	{
		// set input and output sockets
		// this needs to be done before creating reader
		sockis = session.sessionInput;
		sockos = session.sessionOutput;

		reader = new DDMReader(this, session.dssTrace);
		writer = new DDMWriter(ccsidManager, this, session.dssTrace);
	}

	/**
	 * Initialize for a new session
	 */
	private void initializeForSession()
	{
		// set input and output sockets
		sockis = session.sessionInput;
		sockos = session.sessionOutput;

		// intialize reader and writer
		reader.initialize(this, session.dssTrace);
		writer.reset(session.dssTrace);

		// initialize local pointers to session info
		database = session.database;
		appRequester = session.appRequester;

		// set sqlamLevel
		if (session.state == Session.ATTEXC)
			sqlamLevel = appRequester.getManagerLevel(CodePoint.SQLAM);

	}
	/**      
	 * In initial state for a session, 
	 * determine whether this is a command
	 * session or a DRDA protocol session.  A command session is for changing
	 * the configuration of the Net server, e.g., turning tracing on
	 * If it is a command session, process the command and close the session.
	 * If it is a DRDA session, exchange server attributes and change session
	 * state.
	 */
	private void sessionInitialState()
		throws Exception
	{
		// process NetworkServerControl commands - if it is not either valid protocol  let the 
		// DRDA error handling handle it
		if (reader.isCmd())
		{
			try {
				server.processCommands(reader, writer, session);
				// reset reader and writer
				reader.initialize(this, null);
				writer.reset(null);
				closeSession();
			} catch (Throwable t) {
				if (t instanceof InterruptedException)
					throw (InterruptedException)t;
				else
				{
					server.consoleExceptionPrintTrace(t);
				}
			}

		}
		else
		{
			// exchange attributes with application requester
			exchangeServerAttributes();
		}
	}

    /**
     * Cleans up and closes a result set if an exception is thrown
     * when collecting QRYDTA in response to OPNQRY or CNTQRY.
     *
     * @param stmt the DRDA statement to clean up
     * @param sqle the exception that was thrown
     * @param writerMark start index for the first DSS to clear from
     * the output buffer
     * @exception DRDAProtocolException if a DRDA protocol error is
     * detected
     */
    private void cleanUpAndCloseResultSet(DRDAStatement stmt,
                                          SQLException sqle,
                                          int writerMark)
        throws DRDAProtocolException
    {
        if (stmt != null) {
            writer.clearDSSesBackToMark(writerMark);
            if (!stmt.rsIsClosed()) {
                try {
                    stmt.rsClose();
                } catch (SQLException ec) {
                    if (this.trace) {
                        trace("Warning: Error closing result set");
                    }
                }
                writeABNUOWRM();
                writeSQLCARD(sqle, CodePoint.SVRCOD_ERROR, 0, 0);
            }
        } else {
            writeSQLCARDs(sqle, 0);
        }
        errorInChain(sqle);
    }

	/**
	 * Process DRDA commands we can receive once server attributes have been
	 * exchanged.
	 *
	 * @exception DRDAProtocolException
	 */
	private void processCommands() throws DRDAProtocolException
	{
		DRDAStatement stmt = null;
		int updateCount = 0;
		boolean PRPSQLSTTfailed = false;
		boolean checkSecurityCodepoint = session.requiresSecurityCodepoint();
		do
		{
		        // GemStone changes BEGIN
                        long beginWaitNanos = 0;
                        if (ConnectionStats.clockStatsEnabled()) {
                          beginWaitNanos = NanoTimer.getTime();
                          beginWaitMillis = System.currentTimeMillis();
                        }
		        // GemStone changes END
			correlationID = reader.readDssHeader();
			// GemStone changes BEGIN
			long beginProcessTime = 0;
			if (ConnectionStats.clockStatsEnabled()) {
			  synchronized(this) {
			    final long endWait = NanoTimer.getTime();
			    final long currentWait = (endWait - beginWaitNanos);
                            // if we are waiting beyond 5 milli second, then we account 
                            // for wait time and number of such occurances.
			    if (currentWait > 0) {
			      waitTime.addAndGet(currentWait);
			    }
                            beginWaitMillis = 0;
                            if (currentWait >= 5000000L) {
                              numTimesWaited.incrementAndGet();
                            }
			  }
			  beginProcessTime = NanoTimer.getTime();
			}
			 
			// GemStone changes END
			int codePoint = reader.readLengthAndCodePoint( false );
			int writerMark = writer.markDSSClearPoint();
// GemStone changes BEGIN
			boolean finalizeChain = true;
// GemStone changes END
			
			if (checkSecurityCodepoint)
				verifyInOrderACCSEC_SECCHK(codePoint,session.getRequiredSecurityCodepoint());

			switch(codePoint)
			{
				case CodePoint.CNTQRY:
					try{
						stmt = parseCNTQRY();
						if (stmt != null)
						{
						        stmt.setStatus("SENDING CNTQRY RESULTS");
						        writeQRYDTA(stmt);
							// GemStone changes BEGIN
                                                        stmt.checkBucketsStillHosted();
                                                        // GemStone changes END
							if (stmt.rsIsClosed())
							{
								writeENDQRYRM(CodePoint.SVRCOD_WARNING);
								writeNullSQLCARDobject();
							}
							// Send any warnings if JCC can handle them
							checkWarning(null, null, stmt.getResultSet(), 0, false, sendWarningsOnCNTQRY);
                            writePBSD();
						}
					}
					catch(SQLException e)
					{
						// if we got a SQLException we need to clean up and
						// close the result set Beetle 4758
						cleanUpAndCloseResultSet(stmt, e, writerMark);
					}
					break;
				case CodePoint.EXCSQLIMM:
					try {
						updateCount = parseEXCSQLIMM();
						// RESOLVE: checking updateCount is not sufficient
						// since it will be 0 for creates, we need to know when
						// any logged changes are made to the database
						// Not getting this right for JCC is probably O.K., this
						// will probably be a problem for ODBC and XA
						// The problem is that JDBC doesn't provide this information
						// so we would have to expand the JDBC API or call a
						// builtin method to check(expensive)
						// For now we will assume that every execute immediate
						// does an update (that is the most conservative thing)
						if (database.RDBUPDRM_sent == false)
						{
							writeRDBUPDRM();
						}

						// we need to set update count in SQLCARD
						checkWarning(null, database.getDefaultStatement().getStatement(),
							null, updateCount, true, true);
                        writePBSD();
					} catch (SQLException e)
					{
						writer.clearDSSesBackToMark(writerMark);
						writeSQLCARDs(e, 0);
						errorInChain(e);
					}
					break;

				case CodePoint.EXCSQLSET:
					try {
						if (parseEXCSQLSET())
						// all went well.
							writeSQLCARDs(null,0);
					}
					catch (SQLWarning w)
					{
						writeSQLCARD(w, CodePoint.SVRCOD_WARNING, 0, 0);
					}
					catch (SQLException e)
					{
						writer.clearDSSesBackToMark(writerMark);
						writeSQLCARDs(e, 0);
						errorInChain(e);
					}
					break;
					
				case CodePoint.PRPSQLSTT:
					int sqldaType;
					PRPSQLSTTfailed = false;
					try {
						database.getConnection().clearWarnings();
						sqldaType = parsePRPSQLSTT();
						database.getCurrentStatement().sqldaType = sqldaType;
	                                        
						if (sqldaType > 0)		// do write SQLDARD
// GemStone changes BEGIN
						{
	                                         DRDAStatement currStmt =
                                                            database.getCurrentStatement();
						  currStmt.sqldaType = sqldaType;
						  
                                                  // defer writing the meta-data until
                                                  // after execution for unprepared queries
						  if (currStmt.isPrepared()) {
						    // Gemstone changes BEGIN
	                                            // send the statement ID to client
						    // for un-prepared statement this done in executeStatement
						    if (currStmt.needStatementUUID()) {
						      try {
						        writeSQLSTMTID(currStmt.getStatementUUID());
						      } finally {
						        currStmt.setSendStatementUUID(false);
						      }
						    }
	                                                   
                                                    if (SanityManager.TraceSingleHop) {
                                                      SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                                                          "DRDAConnThread::processCommand currStmt is: " + currStmt
                                                              + " sqldaType: " + sqldaType);
                                                    }
						    /* original code
						    writeSQLDARD(currStmt,
						        (sqldaType ==  CodePoint.TYPSQLDA_LIGHT_OUTPUT),
						        database.getConnection().getWarnings());
						        */
                                                     
						    writeSQLDARD(currStmt,
                                                        (sqldaType ==  CodePoint.TYPSQLDA_LIGHT_OUTPUT),
                                                        database.getConnection().getWarnings(), true,false);
						 // Gemstone changes END
						  }
						  else {
						    currStmt.pstmtParseWriterMark = writerMark;
						  }
						}
						  /* (original code)
							writeSQLDARD(database.getCurrentStatement(),
										 (sqldaType ==  CodePoint.TYPSQLDA_LIGHT_OUTPUT),
										 database.getConnection().getWarnings());
						  */
						else
							checkWarning(database.getConnection(), null, null, 0, true, true);

					} catch (SQLException e)
					{
						writer.clearDSSesBackToMark(writerMark);
						writeSQLCARDs(e, 0, true);
						PRPSQLSTTfailed = true;
						errorInChain(e);
					}
					break;
				case CodePoint.OPNQRY:
// GemStone changes BEGIN
					Statement ps = null;
					/* (original code)
					PreparedStatement ps = null;
					*/
// GemStone changes END
					try {
						if (PRPSQLSTTfailed) {
							// read the command objects
							// for ps with parameter
							// Skip objects/parameters
							skipRemainder(true);

							// If we failed to prepare, then we fail
							// to open, which  means OPNQFLRM.
							writeOPNQFLRM(null);
							break;
						}
						Pkgnamcsn pkgnamcsn = parseOPNQRY();
						if (pkgnamcsn != null)
						{
							stmt = database.getDRDAStatement(pkgnamcsn);
// GemStone changes BEGIN
							ps = stmt.getUnderlyingStatement();
							/* (original code)
							ps = stmt.getPreparedStatement();
							*/
// GemStone changes END
							ps.clearWarnings();
                            if (pendingStatementTimeout >= 0) {
                                ps.setQueryTimeout(pendingStatementTimeout);
                                pendingStatementTimeout = -1;
                            }
// GemStone changes BEGIN
                            else if (this.pendingSetSchema != null) {
                              stmt.setSchema(this.pendingSetSchema);
                              this.pendingSetSchema = null;
                            }
						        // write the meta-data now for unprepared queries
					               if (executeStatement(stmt, true) ==
							      PRPSQLSTT_ERROR) {
							    PRPSQLSTTfailed = true;
							    break;
							}
							/* (original code)
							stmt.execute();
							*/
// GemStone changes END
							writeOPNQRYRM(false, stmt);
							checkWarning(null, ps, null, 0, false, true);

							if (stmt.ps != null)
							{
							  long sentVersion = stmt.versionCounter;
// GemStone changes BEGIN
							  if (stmt.ps instanceof EnginePreparedStatement) {
// GemStone changes END
							  long currentVersion =
							       ((EnginePreparedStatement)stmt.ps).
							          getVersionCounter();

							  final ResultSet rs = stmt.ps.getResultSet();
							  final boolean sendMetadata = (rs != null) && rs instanceof EmbedResultSet
							  && ((EmbedResultSet)rs).getSourceResultSet() instanceof SnappySelectResultSet;
							  if (sendMetadata || (stmt.sqldaType ==
							      CodePoint.TYPSQLDA_LIGHT_OUTPUT &&
							      currentVersion != sentVersion)) {
							  // DERBY-5459. The prepared statement has a
							  // result set and has changed on the server
							  // since we last informed the client about its
							  // shape, so re-send metadata.

							  // also send metadata after the execution of query for
							  // PreparedStatement that is routed to lead node
							  // as we did not have metadata for at prepare time

							  // NOTE: This is an extension of the standard
							  // DRDA protocol since we send the SQLDARD
							  // even if it isn't requested in this case.
							  // This is OK because there is already code on the
							  // client to handle an unrequested SQLDARD at
							  // this point in the protocol.
							  // Note : not sending single-hop info here, may need to if single-hop enabled
							  //   ... and asking for metadata from result set,
							  //     not prepared statement's cached metadata
							  writeSQLDARD(stmt, true, null,false,true);
							  }
							}
// GemStone changes BEGIN
							}
// GemStone changes END
							writeQRYDSC(stmt, false);

							stmt.rsSuspend();

							if (stmt.getQryprctyp() == CodePoint.LMTBLKPRC &&
									stmt.getQryrowset() != 0) {
								// The DRDA spec allows us to send
								// QRYDTA here if there are no LOB
								// columns.
								DRDAResultSet drdars =
									stmt.getCurrentDrdaResultSet();
								try {
									if (drdars != null &&
										!drdars.hasLobColumns()) {
									        stmt.setStatus("SENDING OPNQRY RESULTS");
										writeQRYDTA(stmt);
										// GemStone changes BEGIN
										stmt.checkBucketsStillHosted();
										// GemStone changes END
									}
								} catch (SQLException sqle) {
									cleanUpAndCloseResultSet(stmt, sqle,
															 writerMark);
								}
							}
						}
                        writePBSD();
					}
					catch (SQLException e)
					{
						writer.clearDSSesBackToMark(writerMark);
						// The fix for DERBY-1196 removed code 
						// here to close the prepared statement 
						// if OPNQRY failed.
							writeOPNQFLRM(e);
					}
					break;
				case CodePoint.RDBCMM:
				case CodePoint.RDBCMMMOD: // GemStone change
					try
					{
						if (this.trace)
							trace("Received commit");
// GemStone changes BEGIN
						// check for optional RDBNAM
						if (reader.moreDdmData()) {
						  int cp = reader.readLengthAndCodePoint(false);
						  if (cp == CodePoint.RDBNAM) {
						    setDatabase(CodePoint.RDBCMM);
						  }
						  else {
						    codePointNotSupported(cp);
						  }
						}
						// Read commit piggy backed data if there
						if (codePoint == CodePoint.RDBCMMMOD) {
						  getRegionsAndInformTX();
						}
						// flush any pending batch execution
						DRDAStatement batchStmt =
						    database.getCurrentStatement();
						if (batchStmt != null &&
						    batchStmt.batchOutput &&
						    batchStmt.numBatchResults > 0) {
						  boolean sendResult;
						  try {
						    executeBatch(batchStmt, false);
						    sendResult = true;
						  } catch (SQLException e) {
						    sendBatchException(
						        batchStmt, e, true);
						    batchStmt.setStatus("BATCH EXCEPTION "
						          + e.getMessage());
						    sendResult = false;
						  }
						  if (sendResult) {
						    sendBatchResult(batchStmt);
						  }
						}
// GemStone changes END
						if (!database.getConnection().getAutoCommit())
						{
							database.getConnection().clearWarnings();
							database.commit();
							writeENDUOWRM(COMMIT);
							checkWarning(database.getConnection(), null, null, 0, true, true);
						}
						// we only want to write one of these per transaction
						// so set to false in preparation for next command
						database.RDBUPDRM_sent = false;
					}
					catch (SQLException e)
					{
						writer.clearDSSesBackToMark(writerMark);
						// Even in case of error, we have to write the ENDUOWRM.
						writeENDUOWRM(COMMIT);
						writeSQLCARDs(e, 0);
						errorInChain(e);
					}
					break;
				case CodePoint.RDBRLLBCK:
					try
					{
						if (this.trace)
							trace("Received rollback");
// GemStone changes BEGIN
						// check for optional RDBNAM
						if (reader.moreDdmData()) {
						  int cp = reader.readLengthAndCodePoint(false);
						  if (cp == CodePoint.RDBNAM) {
						    setDatabase(CodePoint.RDBCMM);
						  }
						  else {
						    codePointNotSupported(cp);
						  }
						}
// GemStone changes END
						database.getConnection().clearWarnings();
						database.rollback();
						writeENDUOWRM(ROLLBACK);
						checkWarning(database.getConnection(), null, null, 0, true, true);
						// we only want to write one of these per transaction
						// so set to false in preparation for next command
						database.RDBUPDRM_sent = false;
					}
					catch (SQLException e)
					{
						writer.clearDSSesBackToMark(writerMark);
						// Even in case of error, we have to write the ENDUOWRM.
						writeENDUOWRM(ROLLBACK);
						writeSQLCARDs(e, 0);
						errorInChain(e);
					}
					break;
				case CodePoint.CLSQRY:
					try{
						stmt = parseCLSQRY();
						stmt.rsClose();
						writeSQLCARDs(null, 0);
					}
					catch (SQLException e)
					{
						writer.clearDSSesBackToMark(writerMark);
						writeSQLCARDs(e, 0);
						errorInChain(e);
					}
					break;
				case CodePoint.EXCSAT:
					parseEXCSAT();
					writeEXCSATRD();
					break;
				case CodePoint.ACCSEC:
					int securityCheckCode = parseACCSEC();
					writeACCSECRD(securityCheckCode); 
					checkSecurityCodepoint = true;
					break;
				case CodePoint.SECCHK:
				    try {
					if(parseDRDAConnection())
						// security all checked and connection ok
						checkSecurityCodepoint = false;
				    } catch (SQLException e) {
				      writer.clearDSSesBackToMark(writerMark);
				      writeSQLCARDs(e, 0);
				      errorInChain(e);
				    }
				    break;
				/* since we don't support sqlj, we won't get bind commands from jcc, we
				 * might get it from ccc; just skip them.
				 */
				case CodePoint.BGNBND:
					reader.skipBytes();
					writeSQLCARDs(null, 0);
					break;
				case CodePoint.BNDSQLSTT:
					reader.skipBytes();
					parseSQLSTTDss();
					writeSQLCARDs(null, 0);
					break;
				case CodePoint.SQLSTTVRB:
					// optional
					reader.skipBytes();
					break;
				case CodePoint.ENDBND:
					reader.skipBytes();
					writeSQLCARDs(null, 0);
					break;
				case CodePoint.DSCSQLSTT:
					if (PRPSQLSTTfailed) {
						reader.skipBytes();
						writeSQLCARDs(null, 0);
						break;
					}
					try {
						boolean rtnOutput = parseDSCSQLSTT();
						// GemStone changes BEGIN
						writeSQLDARD(database.getCurrentStatement(), rtnOutput,
                                                    null, false,false);
						/* original code
						writeSQLDARD(database.getCurrentStatement(), rtnOutput,
									 null);
									 */
						// GemStone changes END
						
					} catch (SQLException e)
					{
						writer.clearDSSesBackToMark(writerMark);
						server.consoleExceptionPrint(e);
						try {
						  // GemStone changes BEGIN
						  /* orginal code
							writeSQLDARD(database.getCurrentStatement(), true, e);
							*/
							writeSQLDARD(database.getCurrentStatement(), true, e, false,false);
						  // GemStone changes END
						} catch (SQLException e2) {	// should not get here since doing nothing with ps
// GemStone changes BEGIN
						  // log the error to see what is
						  // happenning (bug #41719 is due
						  // to this)
						  SanityManager.DEBUG_PRINT("AGENT ERROR",
						      "Got another SQLException while writing one: "
						      + SanityManager.getStackTrace(e2));
// GemStone changes END
							agentError("Why am I getting another SQLException?");
						}
						errorInChain(e);
					}
					break;
				case CodePoint.EXCSQLSTT:
					if (PRPSQLSTTfailed) {
						// Skip parameters too if they are chained Beetle 4867
						skipRemainder(true);
						writeSQLCARDs(null, 0);
						break;
					}
					try {
// GemStone changes BEGIN
						final byte result = parseEXCSQLSTT();
						if (result == PRPSQLSTT_ERROR) {
						  PRPSQLSTTfailed = true;
						  break;
						}
						if ((finalizeChain = (result == CodePoint.TRUE))) {
						  DRDAStatement currStmt =
						      database.getCurrentStatement();
						  if (currStmt != null) {
						    currStmt.rsSuspend();
						  }
						  writePBSD();
						}
						/* (original code)
						parseEXCSQLSTT();

						DRDAStatement curStmt = database.getCurrentStatement();
						if (curStmt != null)
							curStmt.rsSuspend();
                        writePBSD();
						*/
// GemStone changes END
					} catch (SQLException e)
					{
						skipRemainder(true);
						writer.clearDSSesBackToMark(writerMark);
						if (this.trace) 
						{
							server.consoleExceptionPrint(e);
						}
// GemStone changes BEGIN
						// client expects reply for each element
						// in the batch even if it is an exception,
						// so write batchSize exceptions in a loop
						DRDAStatement currStmt = database
						    .getCurrentStatement();
						if (currStmt != null
						    && currStmt.batchOutput) {
						  sendBatchException(currStmt, e, false);
						}
// GemStone changes END
						writeSQLCARDs(e, 0);
						errorInChain(e);

					}
					break;
				case CodePoint.SYNCCTL:
					if (xaProto == null)
						xaProto = new DRDAXAProtocol(this);
					xaProto.parseSYNCCTL();
 					try {
 						writePBSD();
 					} catch (SQLException se) {
						server.consoleExceptionPrint(se);
 						errorInChain(se);
 					}
					break;
				default:
					codePointNotSupported(codePoint);
			}

            if (this.trace) {
                String cpStr = new CodePointNameTable().lookup(codePoint);
                try {
                    PiggyBackedSessionData pbsd =
                            database.getPiggyBackedSessionData(false);
                    // DERBY-3596
                    // Don't perform this assert if a deferred reset is
                    // happening or has recently taken place, because the
                    // connection state has been changed under the feet of the
                    // piggy-backing mechanism.
                    if (!this.deferredReset && pbsd != null) {
                        // Session data has already been piggy-backed. Refresh
                        // the data from the connection, to make sure it has
                        // not changed behind our back.
                        pbsd.refresh();
                        SanityManager.ASSERT(!pbsd.isModified(),
                              "Unexpected PBSD modification: " + pbsd +
                              " after codePoint " + cpStr);
                    }
                    // Not having a pbsd here is ok. No data has been
                    // piggy-backed and the client has no cached values.
                    // If needed it will send an explicit request to get
                    // session data
                } catch (SQLException sqle) {
                    server.consoleExceptionPrint(sqle);
                    SanityManager.THROWASSERT("Unexpected exception after " +
                            "codePoint "+cpStr, sqle);
                }
            }

			// Set the correct chaining bits for whatever
			// reply DSS(es) we just wrote.  If we've reached
			// the end of the chain, this method will send
			// the DSS(es) across.
// GemStone changes BEGIN
			if (finalizeChain) {
			  finalizeChain();
			}
			/* (original code)
			finalizeChain();
			*/
			if (ConnectionStats.clockStatsEnabled()) {
			  long currProcessTime = NanoTimer.getTime() - beginProcessTime;
			  if (currProcessTime > 0) {
			    processTime.addAndGet(currProcessTime);
			  }
			}
			if (ConnectionStats.isSamplingEnabled()) {
			  numCmdsProcessed.incrementAndGet();
			}
// GemStone changes END

		}
		while (reader.isChainedWithSameID() || reader.isChainedWithDiffID());
	}

  // GemStone changes BEGIN
  private void getRegionsAndInformTX() throws DRDAProtocolException {
    int prs = reader.readNetworkShort();
    EngineConnection conn = database.getConnection();
    StringBuilder sb = null;
    for (int i = 0; i < prs; i++) {
      if (sb == null && SanityManager.TraceSingleHop) {
        sb = new StringBuilder();
      }
      int prid = reader.readNetworkShort();
      int numBuckets = reader.readNetworkShort();
      for (int j = 0; j < numBuckets; j++) {
        int bid = reader.readNetworkShort();
        try {
          PartitionedRegion pr = PartitionedRegion.getPRFromId(prid);
          Bucket b = pr.getRegionAdvisor().getBucket(bid);
          conn.updateAffectedRegion(b);
          if (sb != null) {
            sb.append(prid);
            sb.append(':');
            sb.append(bid);
            sb.append(" -- ");
            sb.append(b.getName());
            sb.append(" ");
          }
        } catch (PRLocallyDestroyedException e) {
          // KN: TODO -- probably implicit rollback and fail the commit op??
          // For now log and move on to read the next as it will fail at the
          // next level
          LogWriter logger = Misc.getCacheLogWriter();
          if (logger != null) {
            logger.warning(
                "DrdaConnThread::getRegionsAndInformTX bucket region with prid="
                    + prid + ", and bucket id" + bid + " is locally destroyed",
                e);
          }
        }
      }
    }
    if (SanityManager.TraceClientHA | SanityManager.TraceSingleHop) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
          "DrdaConnThread::getRegionsAndInformTX affected regions from client = "
              + sb.toString());
    }
  }

  // GemStone changes END

	/**
	 * If there's a severe error in the DDM chain, and if the header indicates
	 * "terminate chain on error", we stop processing further commands in the chain
	 * nor do we send any reply for them.  In accordance to this, a SQLERRRM message 
	 * indicating the severe error must have been sent! (otherwise application requestor,
	 * such as JCC, would not terminate the receiving of chain replies.)
	 *
	 * Each DRDA command is processed independently. DRDA defines no interdependencies
	 * across chained commands. A command is processed the same when received within
	 * a set of chained commands or received separately.  The chaining was originally
	 * defined as a way to save network costs.
	 *
 	 * @param e		the SQLException raised
	 * @exception	DRDAProtocolException
	 */
	private void errorInChain(SQLException e) throws DRDAProtocolException
	{
		if (reader.terminateChainOnErr() && (getExceptionSeverity(e) > CodePoint.SVRCOD_ERROR))
		{
			if (this.trace)  trace("terminating the chain on error...");
			skipRemainder(false);
		}
	}

	/**
	 * Exchange server attributes with application requester
	 *
	 * @exception DRDAProtocolException
	 */
	private void exchangeServerAttributes()
		throws  DRDAProtocolException
	{
		int codePoint;
		correlationID = reader.readDssHeader();
		if (this.trace) {
		  if (correlationID == 0)
		  {
		    SanityManager.THROWASSERT(
					      "Unexpected value for correlationId = " + correlationID);
		  }
		}

		codePoint = reader.readLengthAndCodePoint( false );

		// The first code point in the exchange of attributes must be EXCSAT
		if (codePoint != CodePoint.EXCSAT)
		{
			//Throw PRCCNVRM 
			throw
			    new DRDAProtocolException(DRDAProtocolException.DRDA_Proto_PRCCNVRM,
										  this, codePoint,
										  CodePoint.PRCCNVCD_EXCSAT_FIRST_AFTER_CONN);
		}

		parseEXCSAT();
		writeEXCSATRD();
		finalizeChain();
		session.setState(session.ATTEXC);
	}
	

	private boolean parseDRDAConnection() throws DRDAProtocolException, SQLException
	{
		int codePoint;
		boolean sessionOK = true;


		int securityCheckCode = parseSECCHK();
		if (this.trace)
			trace("*** SECCHKRM securityCheckCode is: "+securityCheckCode);
		writeSECCHKRM(securityCheckCode);
		//at this point if the security check failed, we're done, the session failed
		if (securityCheckCode != 0)
		{
			return false;
		}

		correlationID = reader.readDssHeader();
		codePoint = reader.readLengthAndCodePoint( false );
		verifyRequiredObject(codePoint,CodePoint.ACCRDB);
		int svrcod = parseACCRDB();

// GemStone changes BEGIN
		try {
		  database.checkAndSetPossibleDuplicate();
		} catch (SQLException sqle) {
		  this.databaseAccessException = sqle;
		}
// GemStone changes END
		//If network server gets a null connection form InternalDriver, reply with
		//RDBAFLRM and SQLCARD with null SQLException 
		if(database.getConnection() == null && databaseAccessException == null){
			writeRDBfailure(CodePoint.RDBAFLRM);
			return false;
		}		
		
		//if earlier we couldn't access the database
		if (databaseAccessException != null)
		{

			//if the Database was not found we will try DS
			int failureType = getRdbAccessErrorCodePoint();
			if (failureType == CodePoint.RDBNFNRM 
				|| failureType == CodePoint.RDBATHRM)
			{
				writeRDBfailure(failureType);
			}
			else
			{
				writeRDBfailure(CodePoint.RDBAFLRM);
			}
			return false;
		}
		else if (database.accessCount > 1 )	// already in conversation with database
		{
			writeRDBfailure(CodePoint.RDBACCRM);
			return false;
		}
		else // everything is fine 
			writeACCRDBRM(svrcod);

		// compare this application requester with previously stored
		// application requesters and if we have already seen this one
		// use stored application requester 
		session.appRequester = server.getAppRequester(appRequester);
		return sessionOK;
	}

	/**
	 * Write RDB Failure
	 *
	 * Instance Variables
	 * 	SVRCOD - Severity Code - required
	 *	RDBNAM - Relational Database name - required
	 *  SRVDGN - Server Diagnostics - optional (not sent for now)
 	 *
	 * @param	codePoint	codepoint of failure
	 */
	private void writeRDBfailure(int codePoint) throws DRDAProtocolException
	{
		writer.createDssReply();
		writer.startDdm(codePoint);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, CodePoint.SVRCOD_ERROR);
		writeRDBNAM(database.dbName);
    	writer.endDdmAndDss();
    	
    	switch(codePoint){
    		case CodePoint.RDBAFLRM:
    			//RDBAFLRM requires TYPDEFNAM and TYPDEFOVR
    			writer.createDssObject();
    			writer.writeScalarString(CodePoint.TYPDEFNAM,
    									 CodePoint.TYPDEFNAM_QTDSQLASC);
    			writeTYPDEFOVR();
    			writer.endDss();
    		case CodePoint.RDBNFNRM:
    		case CodePoint.RDBATHRM:
    			writeSQLCARD(databaseAccessException,CodePoint.SVRCOD_ERROR,0,0);
    		case CodePoint.RDBACCRM:
    			//Ignore anything that was chained to the ACCRDB.
    			skipRemainder(false);

    			// Finalize chain state for whatever we wrote in
    			// response to ACCRDB.
    			finalizeChain();
    			break;
    	}
    	
	}

	/* Check the database access exception and return the appropriate
	   error codepoint.
	   RDBNFNRM - Database not found
	   RDBATHRM - Not Authorized
	   RDBAFLRM - Access failure
	   @return RDB Access codepoint 
	           
	*/

	private int getRdbAccessErrorCodePoint()
	{
		String sqlState = databaseAccessException.getSQLState();
		// These tests are ok since DATABASE_NOT_FOUND,
		// NO_SUCH_DATABASE and AUTH_INVALID_USER_NAME are not
		// ambigious error codes (on the first five characters) in
		// SQLState. If they were, we would have to perform a similar
		// check as done in method isAuthenticationException
		if (sqlState.regionMatches(0,SQLState.DATABASE_NOT_FOUND,0,5) ||
			sqlState.regionMatches(0,SQLState.NO_SUCH_DATABASE,0,5)) {
			// RDB not found codepoint
			return CodePoint.RDBNFNRM;
		} else {
			if (isAuthenticationException(databaseAccessException) ||
				sqlState.regionMatches(0,SQLState.AUTH_INVALID_USER_NAME,0,5)) {
				// Not Authorized To RDB reply message codepoint
				return CodePoint.RDBATHRM;
			} else {
				// RDB Access Failed Reply Message codepoint
				return CodePoint.RDBAFLRM;
            }
        }
	}

    /**
     * There are multiple reasons for not getting a connection, and
     * all these should throw SQLExceptions with SQL state 08004
     * according to the SQL standard. Since only one of these SQL
     * states indicate that an authentication error has occurred, it
     * is not enough to check that the SQL state is 08004 and conclude
     * that authentication caused the exception to be thrown.
     *
     * This method tries to cast the exception to an EmbedSQLException
     * and use getMessageId on that object to check for authentication
     * error instead of the SQL state we get from
     * SQLExceptions#getSQLState. getMessageId returns the entire id
     * as defined in SQLState (e.g. 08004.C.1), while getSQLState only
     * return the 5 first characters (i.e. 08004 instead of 08004.C.1)
     *
     * If the cast to EmbedSQLException is not successful, the
     * assumption that SQL State 08004 is caused by an authentication
     * failure is followed even though this is not correct. This was
     * the pre DERBY-3060 way of solving the issue.
     *
     * @param sqlException The exception that is checked to see if
     * this is really caused by an authentication failure
     * @return true if sqlException is (or has to be assumed to be)
     * caused by an authentication failure, false otherwise.
     * @see SQLState
     */
    private boolean isAuthenticationException (SQLException sqlException) {
        boolean authFail = false;

        // get exception which carries Derby messageID and args
        SQLException se = Util.getExceptionFactory().
            getArgumentFerry(sqlException);

// GemStone changes BEGIN
        if (se instanceof DerbySQLException) {
            // DERBY-3060: if this is an EmbedSQLException, we can
            // check the messageId to find out what caused the
            // exception.

            String msgId = ((DerbySQLException)se).getMessageId();
        /* (original code)
        if (se instanceof EmbedSQLException) {
            // DERBY-3060: if this is an EmbedSQLException, we can
            // check the messageId to find out what caused the
            // exception.

            String msgId = ((EmbedSQLException)se).getMessageId();
        */
// GemStone changes END

            // Of the 08004.C.x messages, only
            // SQLState.NET_CONNECT_AUTH_FAILED is an authentication
            // exception
            if (msgId.equals(SQLState.NET_CONNECT_AUTH_FAILED)) {
                authFail = true;
            }
        } else {
            String sqlState = se.getSQLState();
            if (sqlState.regionMatches(0,SQLState.LOGIN_FAILED,0,5)) {
                // Unchanged by DERBY-3060: This is not an
                // EmbedSQLException, so we cannot check the
                // messageId. As before DERBY-3060, we assume that all
                // 08004 error codes are due to an authentication
                // failure, even though this ambigious
                authFail = true;
            }
        }
        return authFail;
    }

	/**
	 * Verify userId and password
	 *
	 * Username and password is verified by making a connection to the
	 * database
	 *
	 * @return security check code, 0 is O.K.
	 * @exception DRDAProtocolException
	 */
	private int verifyUserIdPassword() throws DRDAProtocolException
	{
		databaseAccessException = null;
		int retSecChkCode = 0;

		String realName = database.dbName; //first strip off properties
		int endOfName = realName.indexOf(';');
		if (endOfName != -1)
			realName = realName.substring(0, endOfName);
		retSecChkCode = getConnFromDatabaseName();
		return retSecChkCode;
	}

	/**
	 * Get connection from a database name
	 *
	 * Username and password is verified by making a connection to the
	 * database
	 *
	 * @return security check code, 0 is O.K.
	 * @exception DRDAProtocolException
	 */
	private int getConnFromDatabaseName() throws DRDAProtocolException
	{
		Properties p = new Properties();
		databaseAccessException = null;
		//if we haven't got the correlation token yet, use session number for drdaID
		if (session.drdaID == null)
			session.drdaID = leftBrace + session.connNum + rightBrace;
		p.put(Attribute.DRDAID_ATTR, session.drdaID);
	

        // We pass extra property information for the authentication provider
        // to successfully re-compute the substitute (hashed) password and
        // compare it with what we've got from the requester (source).
        //
        // If a password attribute appears as part of the connection URL
        // attributes, we then don't use the substitute hashed password
        // to authenticate with the engine _as_ the one (if any) as part
        // of the connection URL attributes, will be used to authenticate
        // against Derby's BUILT-IN authentication provider - As a reminder,
        // Derby allows password to be mentioned as part of the connection
        // URL attributes, as this extra capability could be useful to pass
        // passwords to external authentication providers for Derby; hence
        // a password defined as part of the connection URL attributes cannot
        // be substituted (single-hashed) as it is not recoverable.
        if ((database.securityMechanism == CodePoint.SECMEC_USRSSBPWD) &&
            (database.dbName.indexOf(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR) == -1))
        {
            p.put(Attribute.DRDA_SECMEC,
                  String.valueOf(database.securityMechanism));
            p.put(Attribute.DRDA_SECTKN_IN,
                  DecryptionManager.toHexString(database.secTokenIn, 0,
                                                database.secTokenIn.length));
            p.put(Attribute.DRDA_SECTKN_OUT,
                  DecryptionManager.toHexString(database.secTokenOut, 0,
                                                database.secTokenOut.length));
        }
            
	 	try {
			database.makeConnection(p);
	  	} catch (SQLException se) {
                        database.incClientConnectionsFailed();
                        
			String sqlState = se.getSQLState();

			databaseAccessException = se;
			for (; se != null; se = se.getNextException())
			{
				if (this.trace)
					trace(se.getMessage());
	 			println2Log(database.dbName, session.drdaID, se.getMessage());
			}

			if (isAuthenticationException(databaseAccessException)) {
				// need to set the security check code based on the
				// reason the connection was denied, Derby doesn't say
				// whether the userid or password caused the problem,
				// so we will just return userid invalid
				return CodePoint.SECCHKCD_USERIDINVALID;
			} else {
				return 0;
			}
		}
		catch (Exception e)
		{
                        database.incClientConnectionsFailed();
			// If Derby has shut down for some reason,
			// we will send  an agent error and then try to 
			// get the driver loaded again.  We have to get
			// rid of the client first in case they are holding
			// the DriverManager lock.
			println2Log(database.dbName, session.drdaID, 
						"Driver not loaded"
						+ e.getMessage());
				try {
					agentError("Driver not loaded");
				}
				catch (DRDAProtocolException dpe)
				{
					// Retry starting the server before rethrowing 
					// the protocol exception.  Then hopfully all
					// will be well when they try again.
					try {
						server.startNetworkServer();
					} catch (Exception re) {
						println2Log(database.dbName, session.drdaID, "Failed attempt to reload driver " +re.getMessage()  );
					}
					throw dpe;
				}
		}
		
	
		// Everything worked so log connection to the database.
		if (getLogConnections())
	 		println2Log(database.dbName, session.drdaID,
				"Pivotal GemFireXD Server connected to database " +
						database.dbName);
		return 0;
	}


	/**
	 * Parses EXCSAT (Exchange Server Attributes)
	 * Instance variables
	 *	EXTNAM(External Name)	- optional
	 *  MGRLVLLS(Manager Levels) - optional
	 *	SPVNAM(Supervisor Name) - optional
	 *  SRVCLSNM(Server Class Name) - optional
	 *  SRVNAM(Server Name) - optional, ignorable
	 *  SRVRLSLV(Server Product Release Level) - optional, ignorable
	 *
	 * @exception DRDAProtocolException
	 */
	private void parseEXCSAT() throws DRDAProtocolException
	{
		int codePoint;
		String strVal;

		// There are three kinds of EXCSAT's we might get.
		// 1) Initial Exchange attributes.
		//    For this we need to initialize the apprequester.
		//    Session state is set to ATTEXC and then the AR must 
		//    follow up with ACCSEC and SECCHK to get the connection.
		//  2) Send of EXCSAT as ping or mangager level adjustment. 
		//     (see parseEXCSAT2())
		//     For this we just ignore the EXCSAT objects that
		//     are already set.
		//  3) Send of EXCSAT for connection reset. (see parseEXCSAT2())
		//     This is treated just like ping and will be followed up 
		//     by an ACCSEC request if in fact it is a connection reset.

		// If we have already exchanged attributes once just 
		// process any new manager levels and return (case 2 and 3 above)
        this.deferredReset = false; // Always reset, only set to true below.
		if (appRequester != null)
		{
            // DERBY-3596
            // Don't mess with XA requests, as the logic for these are handled
            // by the server side (embedded) objects. Note that XA requests
            // results in a different database object implementation, and it
            // does not have the bug we are working around.
            if (!appRequester.isXARequester()) {
                this.deferredReset = true; // Non-XA deferred reset detected.
            }
			parseEXCSAT2();
			return;
		}

		// set up a new Application Requester to store information about the
		// application requester for this session

		appRequester = new AppRequester();

		reader.markCollection();

		codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch (codePoint)
			{
				// optional
				case CodePoint.EXTNAM:
					appRequester.extnam = reader.readString();
					if (this.trace)
						trace("extName = " + appRequester.extnam);
					if (appRequester.extnam.length() > CodePoint.MAX_NAME)
						tooBig(CodePoint.EXTNAM);
					break;
				// optional
				case CodePoint.MGRLVLLS:
					parseMGRLVLLS(1);
					break;
				// optional 
				case CodePoint.SPVNAM:
					appRequester.spvnam = reader.readString();
					// This is specified as a null parameter so length should
					// be zero
					if (appRequester.spvnam != null)
						badObjectLength(CodePoint.SPVNAM);
					break;
				// optional
				case CodePoint.SRVNAM:
					appRequester.srvnam = reader.readString();
					if (this.trace)
						trace("serverName = " +  appRequester.srvnam);
					if (appRequester.srvnam.length() > CodePoint.MAX_NAME)
						tooBig(CodePoint.SRVNAM);
					break;
				// optional
				case CodePoint.SRVRLSLV:
					appRequester.srvrlslv = reader.readString();
					if (this.trace)
						trace("serverlslv = " + appRequester.srvrlslv);
					if (appRequester.srvrlslv.length() > CodePoint.MAX_NAME)
						tooBig(CodePoint.SRVRLSLV);
					break;
				// optional
				case CodePoint.SRVCLSNM:
					appRequester.srvclsnm = reader.readString();
					if (this.trace)
						trace("serverClassName = " + appRequester.srvclsnm);
					if (appRequester.srvclsnm.length() > CodePoint.MAX_NAME)
						tooBig(CodePoint.SRVCLSNM);
					break;
				default:
					invalidCodePoint(codePoint);
			}
			codePoint = reader.getCodePoint();
		}
	}

	/**
	 * Parses EXCSAT2 (Exchange Server Attributes)
	 * Instance variables
	 *	EXTNAM(External Name)	- optional
	 *  MGRLVLLS(Manager Levels) - optional
	 *	SPVNAM(Supervisor Name) - optional
	 *  SRVCLSNM(Server Class Name) - optional
	 *  SRVNAM(Server Name) - optional, ignorable
	 *  SRVRLSLV(Server Product Release Level) - optional, ignorable
	 *
	 * @exception DRDAProtocolException
	 * 
	 * This parses a second occurrence of an EXCSAT command
	 * The target must ignore the values for extnam, srvclsnm, srvnam and srvrlslv.
	 * I am also going to ignore spvnam since it should be null anyway.
	 * Only new managers can be added.
	 */
	private void parseEXCSAT2() throws DRDAProtocolException
	{
		int codePoint;
		reader.markCollection();

		codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch (codePoint)
			{
				// optional
				case CodePoint.EXTNAM:
				case CodePoint.SRVNAM:
				case CodePoint.SRVRLSLV:
				case CodePoint.SRVCLSNM:
				case CodePoint.SPVNAM:
					reader.skipBytes();
					break;
				// optional
				case CodePoint.MGRLVLLS:
					parseMGRLVLLS(2);
					break;
				default:
					invalidCodePoint(codePoint);
			}
			codePoint = reader.getCodePoint();
		}
	}

	/**
	 *	Parse manager levels
	 *  Instance variables
	 *		MGRLVL - repeatable, required
	 *		  CODEPOINT
	 *			CCSIDMGR - CCSID Manager
	 *			CMNAPPC - LU 6.2 Conversational Communications Manager 
	 *			CMNSYNCPT - SNA LU 6.2 SyncPoint Conversational Communications Manager
	 *			CMNTCPIP - TCP/IP Communication Manager 
	 *			DICTIONARY - Dictionary
	 *			RDB - Relational Database 
	 *			RSYNCMGR - Resynchronization Manager 
	 *			SECMGR - Security Manager
	 *			SQLAM - SQL Application Manager
	 *			SUPERVISOR - Supervisor
	 *			SYNCPTMGR - Sync Point Manager 
	 *		  VALUE
	 *
	 *	On the second appearance of this codepoint, it can only add managers
	 *
	 * @param time	1 for first time this is seen, 2 for subsequent ones
	 * @exception DRDAProtocolException
	 * 
	 */
	private void parseMGRLVLLS(int time) throws DRDAProtocolException
	{
		int manager, managerLevel;
		int currentLevel;
		// set up vectors to keep track of manager information
		unknownManagers = new Vector();
		knownManagers = new Vector();
		errorManagers = new Vector();
		errorManagersLevel = new Vector();
		if (this.trace)
			trace("Manager Levels");

		while (reader.moreDdmData())
		{
			manager = reader.readNetworkShort();
			managerLevel = reader.readNetworkShort();
			if (CodePoint.isKnownManager(manager))
			{
// GemStone changes BEGIN
				// changed to use Integer.valueOf()
				knownManagers.addElement(Integer.valueOf(manager));
				/* (original code)
				knownManagers.addElement(new Integer(manager));
				*/
// GemStone changes END
				//if the manager level hasn't been set, set it
				currentLevel = appRequester.getManagerLevel(manager);
				if (currentLevel == appRequester.MGR_LEVEL_UNKNOWN)
			    	appRequester.setManagerLevel(manager, managerLevel);
				else
				{
					//if the level is still the same we'll ignore it
					if (currentLevel != managerLevel)
					{
						//keep a list of conflicting managers
// GemStone changes BEGIN
						// changed to use Integer.valueOf()
						errorManagers.addElement(Integer.valueOf(manager));
						errorManagersLevel.addElement(Integer.valueOf(managerLevel));
						/* (original code)
						errorManagers.addElement(new Integer(manager));
						errorManagersLevel.addElement(new Integer (managerLevel));
						*/
// GemStone changes END
					}
				}

			}
			else
// GemStone changes BEGIN
			{
				// changed to use Integer.valueOf()
				unknownManagers.addElement(Integer.valueOf(manager));
			}
				/* (original code)
				unknownManagers.addElement(new Integer(manager));
				*/
// GemStone changes END
			if (this.trace)
			   trace("Manager = " + java.lang.Integer.toHexString(manager) + 
					  " ManagerLevel " + managerLevel);
		}
		sqlamLevel = appRequester.getManagerLevel(CodePoint.SQLAM);
		// did we have any errors
		if (errorManagers.size() > 0)
		{
			Object [] oa = new Object[errorManagers.size()*2];
			int j = 0;
			for (int i = 0; i < errorManagers.size(); i++)
			{
				oa[j++] = errorManagers.elementAt(i);
				oa[j++] = errorManagersLevel.elementAt(i);
			}
			throw new DRDAProtocolException(DRDAProtocolException.DRDA_Proto_MGRLVLRM,
										  this, 0,
										  0, oa);
		}
	}
	/**
	 * Write reply to EXCSAT command
	 * Instance Variables
	 *	EXTNAM - External Name (optional)
	 *  MGRLVLLS - Manager Level List (optional)
	 *  SRVCLSNM - Server Class Name (optional) - used by JCC
	 *  SRVNAM - Server Name (optional)
	 *  SRVRLSLV - Server Product Release Level (optional)
	 *
	 * @exception DRDAProtocolException
	 */
	private void writeEXCSATRD() throws DRDAProtocolException
	{
// GemStone changes BEGIN
		// z/OS DB2 driver expects createDssObject
		writer.createDssObject();
		/* (original code)
		writer.createDssReply();
		*/
// GemStone changes END
		writer.startDdm(CodePoint.EXCSATRD);
		writer.writeScalarString(CodePoint.EXTNAM, server.att_extnam);
		//only reply with manager levels if we got sent some
		if (knownManagers != null && knownManagers.size() > 0)
			writeMGRLEVELS();
		writer.writeScalarString(CodePoint.SRVCLSNM, server.att_srvclsnm);
		writer.writeScalarString(CodePoint.SRVNAM, server.ATT_SRVNAM);
		writer.writeScalarString(CodePoint.SRVRLSLV, server.att_srvrlslv);
    	writer.endDdmAndDss();
	}
	/**
	 * Write manager levels
	 * The target server must not provide information for any target
	 * managers unless the source explicitly requests it.
	 * For each manager class, if the target server's support level
     * is greater than or equal to the source server's level, then the source
     * server's level is returned for that class if the target server can operate
     * at the source's level; otherwise a level 0 is returned.  If the target
     * server's support level is less than the source server's level, the
     * target server's level is returned for that class.  If the target server
     * does not recognize the code point of a manager class or does not support
     * that class, it returns a level of 0.  The target server then waits
     * for the next command or for the source server to terminate communications.
     * When the source server receives EXCSATRD, it must compare each of the entries
     * in the mgrlvlls parameter it received to the corresponding entries in the mgrlvlls
     * parameter it sent.  If any level mismatches, the source server must decide
     * whether it can use or adjust to the lower level of target support for that manager
     * class.  There are no architectural criteria for making this decision.
     * The source server can terminate communications or continue at the target
     * servers level of support.  It can also attempt to use whatever
     * commands its user requests while receiving error reply messages for real
     * functional mismatches.
     * The manager levels the source server specifies or the target server
     * returns must be compatible with the manager-level dependencies of the specified
     * manangers.  Incompatible manager levels cannot be specified.
	 *  Instance variables
	 *		MGRLVL - repeatable, required
	 *		  CODEPOINT
	 *			CCSIDMGR - CCSID Manager
	 *			CMNAPPC - LU 6.2 Conversational Communications Manager 
	 *			CMNSYNCPT - SNA LU 6.2 SyncPoint Conversational Communications Manager
	 *			CMNTCPIP - TCP/IP Communication Manager   
	 *			DICTIONARY - Dictionary 
	 *			RDB - Relational Database 
	 *			RSYNCMGR - Resynchronization Manager   
	 *			SECMGR - Security Manager
	 *			SQLAM - SQL Application Manager 
	 *			SUPERVISOR - Supervisor 
	 *			SYNCPTMGR - Sync Point Manager 
	 *			XAMGR - XA manager 
	 *		  VALUE
	 */
	private void writeMGRLEVELS() throws DRDAProtocolException
	{
		int manager;
		int appLevel;
		int serverLevel;
		writer.startDdm(CodePoint.MGRLVLLS);
		for (int i = 0; i < knownManagers.size(); i++)
		{
			manager = ((Integer)knownManagers.elementAt(i)).intValue();
			appLevel = appRequester.getManagerLevel(manager);
			serverLevel = server.getManagerLevel(manager);
			if (serverLevel >= appLevel)
			{
				//Note appLevel has already been set to 0 if we can't support
				//the original app Level
				writer.writeCodePoint4Bytes(manager, appLevel);
			}
			else
			{
				writer.writeCodePoint4Bytes(manager, serverLevel);
				// reset application manager level to server level
				appRequester.setManagerLevel(manager, serverLevel);
			}
		}
		// write 0 for all unknown managers
		for (int i = 0; i < unknownManagers.size(); i++)
		{
			manager = ((Integer)unknownManagers.elementAt(i)).intValue();
			writer.writeCodePoint4Bytes(manager, 0);
		}
		writer.endDdm();
	}
	/**
	 *  Parse Access Security
	 *
	 *	If the target server supports the SECMEC requested by the application requester
	 *	then a single value is returned and it is identical to the SECMEC value
	 *	in the ACCSEC command. If the target server does not support the SECMEC
	 *	requested, then one or more values are returned and the application requester
	 *  must choose one of these values for the security mechanism.
	 *  We currently support
	 *		- user id and password (default for JCC)
	 *		- encrypted user id and password
     *      - strong password substitute (USRSSBPWD w/
     *                                    Derby network client only)
	 *
     *  Instance variables
	 *    SECMGRNM  - security manager name - optional
	 *	  SECMEC 	- security mechanism - required
	 *	  RDBNAM	- relational database name - optional
	 * 	  SECTKN	- security token - optional, (required if sec mech. needs it)
     *
	 *  @return security check code - 0 if everything O.K.
	 */
	private int parseACCSEC() throws  DRDAProtocolException
	{
		int securityCheckCode = 0;
		int securityMechanism = 0;
		byte [] secTokenIn = null;

		reader.markCollection();
		int codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch(codePoint)
			{
				//optional
				case CodePoint.SECMGRNM:
					// this is defined to be 0 length
					if (reader.getDdmLength() != 0)
						badObjectLength(CodePoint.SECMGRNM);
					break;
				//required
				case CodePoint.SECMEC:
					checkLength(CodePoint.SECMEC, 2);
					securityMechanism = reader.readNetworkShort();
					if (this.trace)
						trace("parseACCSEC - Security mechanism = " + securityMechanism);
                    
                    // if Property.DRDA_PROP_SECURITYMECHANISM has been set, then
                    // network server only accepts connections which use that
                    // security mechanism. No other types of connections 
                    // are accepted.
                    // Make check to see if this property has been set.
                    // if set, and if the client requested security mechanism 
                    // is not the same, then return a security check code 
                    // that the server does not support/allow this security 
                    // mechanism
                    if ( (server.getSecurityMechanism() != 
                        NetworkServerControlImpl.INVALID_OR_NOTSET_SECURITYMECHANISM)
                            && securityMechanism != server.getSecurityMechanism())
                    {
                        securityCheckCode = CodePoint.SECCHKCD_NOTSUPPORTED;
                        if (this.trace) {
                            trace("parseACCSEC - SECCHKCD_NOTSUPPORTED [1] - " +
                                  securityMechanism + " <> " +
                                  server.getSecurityMechanism() + "\n");
                        }
                    }
                    else
                    {
                        // for plain text userid,password USRIDPWD, and USRIDONL
                        // no need of decryptionManager
                        if (securityMechanism != CodePoint.SECMEC_USRIDPWD &&
                                securityMechanism != CodePoint.SECMEC_USRIDONL)
                        {
                            // These are the only other mechanisms we understand
                            if (((securityMechanism != CodePoint.SECMEC_EUSRIDPWD) ||
                                 (securityMechanism == CodePoint.SECMEC_EUSRIDPWD && 
                                   !server.supportsEUSRIDPWD())
                                 ) &&
                                (securityMechanism !=
                                        CodePoint.SECMEC_USRSSBPWD))
                                //securityCheckCode = CodePoint.SECCHKCD_NOTSUPPORTED;
                    {
                        securityCheckCode = CodePoint.SECCHKCD_NOTSUPPORTED;
                        if (this.trace) {
                            trace("parseACCSEC - SECCHKCD_NOTSUPPORTED [2]\n");
                        }
                    }
                            else
                            {
                                // We delay the initialization and required
                                // processing for SECMEC_USRSSBPWD as we need
                                // to ensure the database is booted so that
                                // we can verify that the current auth scheme
                                // is set to BUILT-IN or NONE. For this we need
                                // to have the RDBNAM codepoint available.
                                //
                                // See validateSecMecUSRSSBPWD() call below
                                if (securityMechanism ==
                                        CodePoint.SECMEC_USRSSBPWD)
                                    break;

                                // SECMEC_EUSRIDPWD initialization
                                try {
                                    if (decryptionManager == null)
                                        decryptionManager = new DecryptionManager();
                                    myPublicKey = decryptionManager.obtainPublicKey();
                                } catch (SQLException e) {
                                    println2Log(null, session.drdaID, e.getMessage());
                                    // Local security service non-retryable error.
                                    securityCheckCode = CodePoint.SECCHKCD_0A;
                                }
                            }
                        }
                    }
					break;
                //optional (currently required for Derby - needed for
                //          DERBY-528 as well)
				case CodePoint.RDBNAM:
					String dbname = parseRDBNAM();
// GemStone changes BEGIN
					setRdbName(dbname);
                    /* (original code)
					Database d = session.getDatabase(dbname);
					if (d == null)
						addDatabase(dbname);
					else
                    {
                        // reset database for connection re-use 
                        // DERBY-3596
                        // If we are reusing resources for a new physical
                        // connection, reset the database object. If the client
                        // is in the process of creating a new logical
                        // connection only, don't reset the database object.
                        if (!deferredReset) {
                            d.reset();
                        }
                        database = d;
                    }
                    */
// GemStone changes END
					break;
				//optional - depending on security Mechanism 
				case CodePoint.SECTKN:
					secTokenIn = reader.readBytes();
					break;
				default:
					invalidCodePoint(codePoint);
			}
			codePoint = reader.getCodePoint();
		}

		// check for required CodePoint's
		if (securityMechanism == 0)
			missingCodePoint(CodePoint.SECMEC);


		// RESOLVE - when we look further into security we might want to
		// handle this part of the protocol at the session level without
		// requiring a database for when authentication is used but there
		// is no database level security
		if (database == null)
// GemStone changes BEGIN
			// z/OS DB2 driver does not send RDBNAM at this point,
			// so assume "gemfirexd"
			setRdbName(Attribute.GFXD_DBNAME);
			/* (original code)
			missingCodePoint(CodePoint.RDBNAM);
			*/
// GemStone changes END

		database.securityMechanism = securityMechanism;
		database.secTokenIn = secTokenIn;

        // If security mechanism is SECMEC_USRSSBPWD, then ensure it can be
        // used for the database or system based on the client's connection
        // URL and its identity.
        if (securityCheckCode == 0  &&
            (database.securityMechanism == CodePoint.SECMEC_USRSSBPWD))
        {
            if (this.trace)
		        SanityManager.ASSERT((securityCheckCode == 0),
						"SECMEC_USRSSBPWD: securityCheckCode should not " +
                        "already be set, found it initialized with " +
                        "a value of '" + securityCheckCode + "'.");
            securityCheckCode = validateSecMecUSRSSBPWD();
        }

		// need security token
		if (securityCheckCode == 0  && 
			(database.securityMechanism == CodePoint.SECMEC_EUSRIDPWD ||
            database.securityMechanism == CodePoint.SECMEC_USRSSBPWD) &&
			database.secTokenIn == null)
			securityCheckCode = CodePoint.SECCHKCD_SECTKNMISSING_OR_INVALID;

		// shouldn't have security token
		if (securityCheckCode == 0 &&
			(database.securityMechanism == CodePoint.SECMEC_USRIDPWD ||
			database.securityMechanism == CodePoint.SECMEC_USRIDONL)  &&
			database.secTokenIn != null)
			securityCheckCode = CodePoint.SECCHKCD_SECTKNMISSING_OR_INVALID;

		if (this.trace)
			trace("** ACCSECRD securityCheckCode is: " + securityCheckCode);
		
		// If the security check was successful set the session state to
		// security accesseed.  Otherwise go back to attributes exchanged so we
		// require another ACCSEC
		if (securityCheckCode == 0)
			session.setState(session.SECACC);
		else
			session.setState(session.ATTEXC);

		return securityCheckCode;
	}

// GemStone changes BEGIN
	private void setRdbName(String dbname) {
	  Database d = session.getDatabase(dbname);
	  if (d == null) {
	    addDatabase(dbname);
	  }
	  else {
	    // reset database for connection re-use
	    // DERBY-3596
	    // If we are reusing resources for a new physical
	    // connection, reset the database object. If the client
	    // is in the process of creating a new logical
	    // connection only, don't reset the database object.
	    if (!deferredReset) {
	      d.reset();
	    }
	    database = d;
	  }
	}

// GemStone changes END
	/**
	 * Parse OPNQRY
	 * Instance Variables
	 *  RDBNAM - relational database name - optional
	 *  PKGNAMCSN - RDB Package Name, Consistency Token and Section Number - required
	 *  QRYBLKSZ - Query Block Size - required
	 *  QRYBLKCTL - Query Block Protocol Control - optional 
	 *  MAXBLKEXT - Maximum Number of Extra Blocks - optional - default value 0
	 *  OUTOVROPT - Output Override Option
	 *  QRYROWSET - Query Rowset Size - optional - level 7
	 *  MONITOR - Monitor events - optional.
	 *
	 * @return RDB Package Name, Consistency Token, and Section Number
	 * @exception DRDAProtocolException
	 */
	private Pkgnamcsn parseOPNQRY() throws DRDAProtocolException, SQLException
	{
		Pkgnamcsn pkgnamcsn = null;
		boolean gotQryblksz = false;
		int blksize = 0;
		// GemStone changes BEGIN
                boolean localExecDueToSHOP = false;
                int isolationlevel = 0;
                Set<Integer> bucketSet = null;
                boolean dbSync = false;
                // GemStone changes END
		int qryblkctl = CodePoint.QRYBLKCTL_DEFAULT;
		int maxblkext = CodePoint.MAXBLKEXT_DEFAULT;
		int qryrowset = CodePoint.QRYROWSET_DEFAULT;
		int qryclsimp = DRDAResultSet.QRYCLSIMP_DEFAULT;
		int outovropt = CodePoint.OUTOVRFRS;
		reader.markCollection();
		int codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch(codePoint)
			{
				//optional
				case CodePoint.RDBNAM:
					setDatabase(CodePoint.OPNQRY);
					break;
				//required
			        // Gemstone changes BEGIN
				/* original code
				case CodePoint.PKGNAMCSN:
					pkgnamcsn = parsePKGNAMCSN();
					break;
				*/
				case CodePoint.PKGNAMCSN:
				case CodePoint.PKGNAMCSN_SHOP:
				case CodePoint.PKGNAMCSN_SHOP_WITH_STR:
                                  pkgnamcsn = parsePKGNAMCSN();
                                  if (codePoint != CodePoint.PKGNAMCSN) {
                                    checkPre1302ClientVersionForSingleHop("OPNQRY");
                                    bucketSet = readBucketSet();
                                    if (bucketSet != null) {
                                      if (bucketSet.contains(ResolverUtils.TOKEN_FOR_DB_SYNC)) {
                                        dbSync = true;
                                        bucketSet.remove(ResolverUtils.TOKEN_FOR_DB_SYNC);
                                      }
                                      if (Version.GFXD_20.compareTo(this.gfxdClientVersion) <= 0) {
                                        byte txID = reader.readByte();
                                        if (txID == ClientSharedData.CLIENT_TXID_WRITTEN) {
                                          assert this.currTxIdForSHOPExecution == null : "txid not expected as the client has already sent it";
                                          isolationlevel = reader.readInt(getByteOrder());
                                          long memberId = reader.readLong(getByteOrder());
                                          long uniqueId = reader.readLong(getByteOrder());
                                          this.currTxIdForSHOPExecution = TXId.valueOf(memberId, (int)uniqueId);
                                        }
                                      }
                                    }
                                    localExecDueToSHOP = true; 
                                  }
                                  if (SanityManager.TraceSingleHop) {
                                    SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                                        "DRDAConnThread::parseOPNQRY pkgnamcsn: " + pkgnamcsn);
                                  }
                                  break;
				// Gemstone changes END
				//required
			  	case CodePoint.QRYBLKSZ:
					blksize = parseQRYBLKSZ();
					gotQryblksz = true;
					break;
				//optional
			  	case CodePoint.QRYBLKCTL:
					qryblkctl = reader.readNetworkShort();
					//The only type of query block control we can specify here
					//is forced fixed row
					if (qryblkctl != CodePoint.FRCFIXROW)
						invalidCodePoint(qryblkctl);
					if (this.trace)
						trace("!!qryblkctl = "+Integer.toHexString(qryblkctl));
					gotQryblksz = true;
					break;
				//optional
			  	case CodePoint.MAXBLKEXT:
					maxblkext = reader.readSignedNetworkShort();
					if (this.trace)
						trace("maxblkext = "+maxblkext);
					break;
				// optional
				case CodePoint.OUTOVROPT:
					outovropt = parseOUTOVROPT();
					break;
				//optional
				case CodePoint.QRYROWSET:
					//Note minimum for OPNQRY is 0
					qryrowset = parseQRYROWSET(0);
					break;
				case CodePoint.QRYCLSIMP:
					// Implicitly close non-scrollable cursor
					qryclsimp = parseQRYCLSIMP();
					break;
				case CodePoint.QRYCLSRLS:
					// Ignore release of read locks.  Nothing we can do here
					parseQRYCLSRLS();
					break;
				// optional
				case CodePoint.MONITOR:
					parseMONITOR();
					break;
			  	default:
					invalidCodePoint(codePoint);
			}
			codePoint = reader.getCodePoint();
		}
		// check for required variables
		if (pkgnamcsn == null) {
			missingCodePoint(CodePoint.PKGNAMCSN);
			// never reached
			return null;
		}
		if (!gotQryblksz)
			missingCodePoint(CodePoint.QRYBLKSZ);

		// get the statement we are opening
		DRDAStatement stmt = database.getDRDAStatement(pkgnamcsn);
		// Gemstone changes BEGIN
                
                if (stmt != null && localExecDueToSHOP) {
                  EngineConnection conn = database.getConnection();
                  Checkpoint cp = null;
                  if (this.currTxIdForSHOPExecution != null) {
                    cp = conn.masqueradeAsTxn(this.currTxIdForSHOPExecution, isolationlevel);
                  }
                  LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
                  if (SanityManager.TraceSingleHop) {
                    boolean origSkipLocks = lcc.skipLocks();
                    SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                        "DRDAConnThread::parseOPNQRY conn: stmt == null for pkgnamcsn: "
                            + pkgnamcsn + ", bucketset: " + bucketSet
                            + ", local exec due to shop: " + true + ", origSkipLocks: "
                            + origSkipLocks + " and lcc: " + lcc + " dbSync: " + dbSync
                            + ", currTXIDForSHOPExecution " + this.currTxIdForSHOPExecution);
                  }
                  stmt.incSingleHopStats();
                  lcc.setExecuteLocally(bucketSet, stmt.getRegion(), dbSync, cp);
                  
                }

		if (stmt == null)
		{
			//XXX should really throw a SQL Exception here
                  // in single hop case this stmt can be null
                  // so check that
                  invalidValue(CodePoint.PKGNAMCSN);
                  // never reached
                  return pkgnamcsn;
                  /*original code
			invalidValue(CodePoint.PKGNAMCSN);
			*/
                  // Gemstone changes END
		}

		// check that this statement is not already open
		// commenting this check out for now
		// it turns out that JCC doesn't send a close if executeQuery is
		// done again without closing the previous result set
		// this check can't be done since the second executeQuery should work
		//if (stmt.state != DRDAStatement.NOT_OPENED)
		//{
		//	writeQRYPOPRM();
		//	pkgnamcsn = null;
		//}
		//else
		//{
		stmt.setOPNQRYOptions(blksize,qryblkctl,maxblkext,outovropt,
							  qryrowset, qryclsimp);
		//}
		
		// read the command objects
		// for ps with parameter
		if (reader.isChainedWithSameID())
		{
			if (this.trace)
				trace("&&&&&& parsing SQLDTA");
			parseOPNQRYobjects(stmt);
		}
		return pkgnamcsn;
	}
	
	private Set<Integer> readBucketSet() throws DRDAProtocolException {
	  int sz = reader.readNetworkShort();
	  Set<Integer> set = new HashSet<Integer>();
 	  for(int i=0; i<sz; i++) {
	    int bid = reader.readNetworkShort();
	    set.add(bid);
	  }
 	  return set;
	}

  /**
	 * Parse OPNQRY objects
	 * Objects
	 *  TYPDEFNAM - Data type definition name - optional
	 *  TYPDEFOVR - Type defintion overrides - optional
	 *  SQLDTA- SQL Program Variable Data - optional
	 *
	 * If TYPDEFNAM and TYPDEFOVR are supplied, they apply to the objects
	 * sent with the statement.  Once the statement is over, the default values
	 * sent in the ACCRDB are once again in effect.  If no values are supplied,
	 * the values sent in the ACCRDB are used.
	 * Objects may follow in one DSS or in several DSS chained together.
	 * 
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void parseOPNQRYobjects(DRDAStatement stmt) 
		throws DRDAProtocolException, SQLException
	{
		int codePoint;
		do
		{
			correlationID = reader.readDssHeader();
			while (reader.moreDssData())
			{
				codePoint = reader.readLengthAndCodePoint( false );
				switch(codePoint)
				{
					// optional
					case CodePoint.TYPDEFNAM:
						setStmtOrDbByteOrder(false, stmt, parseTYPDEFNAM());
						break;
					// optional
					case CodePoint.TYPDEFOVR:
						parseTYPDEFOVR(stmt);
						break;
					// optional 
					case CodePoint.SQLDTA:
						parseSQLDTA(stmt);
						break;
					// optional
					case CodePoint.EXTDTA:	
						readAndSetAllExtParams(stmt, false);
						break;
					default:
						invalidCodePoint(codePoint);
				}
			}
		} while (reader.isChainedWithSameID());

	}
	/**
	 * Parse OUTOVROPT - this indicates whether output description can be
	 * overridden on just the first CNTQRY or on any CNTQRY
	 *
	 * @return output override option
	 * @exception DRDAProtocolException
	 */
	private int parseOUTOVROPT() throws DRDAProtocolException
	{
		checkLength(CodePoint.OUTOVROPT, 1);
		int outovropt = reader.readUnsignedByte();
		if (this.trace)
			trace("output override option: "+outovropt);
		if (outovropt != CodePoint.OUTOVRFRS && outovropt != CodePoint.OUTOVRANY)
			invalidValue(CodePoint.OUTOVROPT);
		return outovropt;
	}

	/**
	 * Parse QRYBLSZ - this gives the maximum size of the query blocks that
	 * can be returned to the requester
	 *
	 * @return query block size
	 * @exception DRDAProtocolException
	 */
	private int parseQRYBLKSZ() throws DRDAProtocolException
	{
		checkLength(CodePoint.QRYBLKSZ, 4);
		int blksize = reader.readNetworkInt();
		if (this.trace)
			trace("qryblksz = "+blksize);
		if (blksize < CodePoint.QRYBLKSZ_MIN || blksize > CodePoint.QRYBLKSZ_MAX)
			invalidValue(CodePoint.QRYBLKSZ);
		return blksize;
	}
	
	/**
         * Parse NCJBATCHSIZE
         *
         * @exception DRDAProtocolException
         */
        private int parseNCJBATCHSIZE() throws DRDAProtocolException
        {
                checkLength(CodePoint.NCJBATCHSIZE, 4);
                int batchsize = reader.readNetworkInt();
                if (this.trace)
                        trace("ncjbatchsize = "+batchsize);
                if (batchsize < 0)
                        invalidValue(CodePoint.NCJBATCHSIZE);
                return batchsize;
        }
        
        /**
         * Parse NCJCACHESIZE
         *
         * @exception DRDAProtocolException
         */
        private int parseNCJCACHESIZE() throws DRDAProtocolException
        {
                checkLength(CodePoint.QRYROWSET, 4);
                int cachesize = reader.readNetworkInt();
                if (this.trace)
                  trace("ncjcachesize = "+cachesize);
                if (cachesize < 0)
                  invalidValue(CodePoint.NCJCACHESIZE);
                return cachesize;
        }
	
	/**
 	 * Parse QRYROWSET - this is the number of rows to return
	 *
	 * @param minVal - minimum value
	 * @return query row set size
	 * @exception DRDAProtocolException
	 */
	private int parseQRYROWSET(int minVal) throws DRDAProtocolException
	{
		checkLength(CodePoint.QRYROWSET, 4);
		int qryrowset = reader.readNetworkInt();
		if (this.trace)
			trace("qryrowset = " + qryrowset);
		if (qryrowset < minVal || qryrowset > CodePoint.QRYROWSET_MAX)
			invalidValue(CodePoint.QRYROWSET);
		return qryrowset;
	}

	/** Parse a QRYCLSIMP - Implicitly close non-scrollable cursor 
	 * after end of data.
	 * @return  true to close on end of data 
	 */
	private int  parseQRYCLSIMP() throws DRDAProtocolException
	{
	   
		checkLength(CodePoint.QRYCLSIMP, 1);
		int qryclsimp = reader.readUnsignedByte();
		if (this.trace)
			trace ("qryclsimp = " + qryclsimp);
		if (qryclsimp != CodePoint.QRYCLSIMP_SERVER_CHOICE &&
			qryclsimp != CodePoint.QRYCLSIMP_YES &&
			qryclsimp != CodePoint.QRYCLSIMP_NO )
			invalidValue(CodePoint.QRYCLSIMP);
		return qryclsimp;
	}


	private int parseQRYCLSRLS() throws DRDAProtocolException
	{
		reader.skipBytes();
		return 0;
	}

	/**
	 * Write a QRYPOPRM - Query Previously opened
	 * Instance Variables
	 *  SVRCOD - Severity Code - required - 8 ERROR
	 *  RDBNAM - Relational Database Name - required
	 *  PKGNAMCSN - RDB Package Name, Consistency Token, and Section Number - required
	 * 
	 * @exception DRDAProtocolException
	 */
	private void writeQRYPOPRM() throws DRDAProtocolException
	{
		writer.createDssReply();
		writer.startDdm(CodePoint.QRYPOPRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, CodePoint.SVRCOD_ERROR);
		writeRDBNAM(database.dbName);
		writePKGNAMCSN();
		writer.endDdmAndDss();
	}
	/**
	 * Write a QRYNOPRM - Query Not Opened
	 * Instance Variables
	 *  SVRCOD - Severity Code - required -  4 Warning 8 ERROR
	 *  RDBNAM - Relational Database Name - required
	 *  PKGNAMCSN - RDB Package Name, Consistency Token, and Section Number - required
	 * 
	 * @param svrCod	Severity Code
	 * @exception DRDAProtocolException
	 */
	private void writeQRYNOPRM(int svrCod) throws DRDAProtocolException
	{
		writer.createDssReply();
		writer.startDdm(CodePoint.QRYNOPRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, svrCod);
		writeRDBNAM(database.dbName);
		writePKGNAMCSN();
		writer.endDdmAndDss();
	}
	/**
	 * Write a OPNQFLRM - Open Query Failure
	 * Instance Variables
	 *  SVRCOD - Severity Code - required - 8 ERROR
	 *  RDBNAM - Relational Database Name - required
	 *
	 * @param	e	Exception describing failure
	 *
	 * @exception DRDAProtocolException
	 */
	private void writeOPNQFLRM(SQLException e) throws DRDAProtocolException
	{
		writer.createDssReply();
		writer.startDdm(CodePoint.OPNQFLRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, CodePoint.SVRCOD_ERROR);
		writeRDBNAM(database.dbName);
		writer.endDdm();
		writer.startDdm(CodePoint.SQLCARD);
		writeSQLCAGRP(e, getSqlCode(getExceptionSeverity(e)), 0, 0);
		writer.endDdmAndDss();
	}
	/**
	 * Write PKGNAMCSN
	 * Instance Variables
	 *   NAMESYMDR - database name - not validated
	 *   RDBCOLID - RDB Collection Identifier
	 *   PKGID - RDB Package Identifier
	 *   PKGCNSTKN - RDB Package Consistency Token
	 *   PKGSN - RDB Package Section Number
	 *
	 * There are two possible formats, fixed and extended which includes length
	 * information for the strings
	 *
	 * @throws DRDAProtocolException
	 */
	private void writePKGNAMCSN(byte[] pkgcnstkn) throws DRDAProtocolException
	{
		writer.startDdm(CodePoint.PKGNAMCSN);
		if (rdbnam.length() <= CodePoint.RDBNAM_LEN &&
			rdbcolid.length() <= CodePoint.RDBCOLID_LEN &&
			pkgid.length() <= CodePoint.PKGID_LEN)
		{	// if none of RDBNAM, RDBCOLID and PKGID have a length of
			// more than 18, use fixed format
			writer.writeScalarPaddedString(rdbnam, CodePoint.RDBNAM_LEN);
			writer.writeScalarPaddedString(rdbcolid, CodePoint.RDBCOLID_LEN);
			writer.writeScalarPaddedString(pkgid, CodePoint.PKGID_LEN);
			writer.writeScalarPaddedBytes(pkgcnstkn,
										  CodePoint.PKGCNSTKN_LEN, (byte) 0);
			writer.writeShort(pkgsn);
		}
		else	// extended format
		{
			int len = Math.max(CodePoint.RDBNAM_LEN, rdbnam.length());
			writer.writeShort(len);
			writer.writeScalarPaddedString(rdbnam, len);
			len = Math.max(CodePoint.RDBCOLID_LEN, rdbcolid.length());
			writer.writeShort(len);
			writer.writeScalarPaddedString(rdbcolid, len);
			len = Math.max(CodePoint.PKGID_LEN, pkgid.length());
			writer.writeShort(len);
			writer.writeScalarPaddedString(pkgid, len);
			writer.writeScalarPaddedBytes(pkgcnstkn,
										  CodePoint.PKGCNSTKN_LEN, (byte) 0);
			writer.writeShort(pkgsn);
		}
		writer.endDdm();
	}

	private void writePKGNAMCSN() throws DRDAProtocolException
	{
		writePKGNAMCSN(pkgcnstkn.getBytes());
	}

	/**
	 * Parse CNTQRY - Continue Query
	 * Instance Variables
	 *   RDBNAM - Relational Database Name - optional
	 *   PKGNAMCSN - RDB Package Name, Consistency Token, and Section Number - required
	 *   QRYBLKSZ - Query Block Size - required
	 *   QRYRELSCR - Query Relative Scrolling Action - optional
	 *   QRYSCRORN - Query Scroll Orientation - optional - level 7
	 *   QRYROWNBR - Query Row Number - optional
	 *   QRYROWSNS - Query Row Sensitivity - optional - level 7
	 *   QRYBLKRST - Query Block Reset - optional - level 7
	 *   QRYRTNDTA - Query Returns Data - optional - level 7
	 *   QRYROWSET - Query Rowset Size - optional - level 7
	 *   QRYRFRTBL - Query Refresh Answer Set Table - optional
	 *   NBRROW - Number of Fetch or Insert Rows - optional
	 *   MAXBLKEXT - Maximum number of extra blocks - optional
	 *   RTNEXTDTA - Return of EXTDTA Option - optional
	 *   MONITOR - Monitor events - optional.
	 *
	 * @return DRDAStatement we are continuing
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private DRDAStatement parseCNTQRY() throws DRDAProtocolException, SQLException
	{
		byte val;
		Pkgnamcsn pkgnamcsn = null;
		boolean gotQryblksz = false;
		boolean qryrelscr = true;
		long qryrownbr = 1;
		boolean qryrfrtbl = false;
		int nbrrow = 1;
		int blksize = 0;
		int maxblkext = -1;
		long qryinsid;
		boolean gotQryinsid = false;
		int qryscrorn = CodePoint.QRYSCRREL;
		boolean qryrowsns = false;
		boolean gotQryrowsns = false;
		boolean qryblkrst = false;
		boolean qryrtndta = true;
		int qryrowset = CodePoint.QRYROWSET_DEFAULT;
		int rtnextdta = CodePoint.RTNEXTROW;
		reader.markCollection();
		int codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch(codePoint)
			{
				//optional
				case CodePoint.RDBNAM:
					setDatabase(CodePoint.CNTQRY);
					break;
				//required
				case CodePoint.PKGNAMCSN:
					pkgnamcsn = parsePKGNAMCSN();
					break;
				//required
				case CodePoint.QRYBLKSZ:
					blksize = parseQRYBLKSZ();
					gotQryblksz = true;
					break;
				//optional
				case CodePoint.QRYRELSCR:
					qryrelscr = readBoolean(CodePoint.QRYRELSCR);
					if (this.trace)
						trace("qryrelscr = "+qryrelscr);
					break;
				//optional
				case CodePoint.QRYSCRORN:
					checkLength(CodePoint.QRYSCRORN, 1);
					qryscrorn = reader.readUnsignedByte();
					if (this.trace)
						trace("qryscrorn = "+qryscrorn);
					switch (qryscrorn)
					{
						case CodePoint.QRYSCRREL:
						case CodePoint.QRYSCRABS:
						case CodePoint.QRYSCRAFT:
						case CodePoint.QRYSCRBEF:
							break;
						default:
							invalidValue(CodePoint.QRYSCRORN);
					}
					break;
				//optional
				case CodePoint.QRYROWNBR:
					checkLength(CodePoint.QRYROWNBR, 8);
					qryrownbr = reader.readNetworkLong();
					if (this.trace)
						trace("qryrownbr = "+qryrownbr);
					break;
				//optional
				case CodePoint.QRYROWSNS:
					checkLength(CodePoint.QRYROWSNS, 1);
					qryrowsns = readBoolean(CodePoint.QRYROWSNS);
					if (this.trace)
						trace("qryrowsns = "+qryrowsns);
					gotQryrowsns = true;
					break;
				//optional
				case CodePoint.QRYBLKRST:
					checkLength(CodePoint.QRYBLKRST, 1);
					qryblkrst = readBoolean(CodePoint.QRYBLKRST);
					if (this.trace)
						trace("qryblkrst = "+qryblkrst);
					break;
				//optional
				case CodePoint.QRYRTNDTA:
					qryrtndta = readBoolean(CodePoint.QRYRTNDTA);
					if (this.trace)
						trace("qryrtndta = "+qryrtndta);
					break;
				//optional
				case CodePoint.QRYROWSET:
					//Note minimum for CNTQRY is 1
					qryrowset = parseQRYROWSET(1);
					if (this.trace)
						trace("qryrowset = "+qryrowset);
					break;
				//optional
				case CodePoint.QRYRFRTBL:
					qryrfrtbl = readBoolean(CodePoint.QRYRFRTBL);
					if (this.trace)
						trace("qryrfrtbl = "+qryrfrtbl);
					break;
				//optional
				case CodePoint.NBRROW:
					checkLength(CodePoint.NBRROW, 4);
					nbrrow = reader.readNetworkInt();
					if (this.trace)
						trace("nbrrow = "+nbrrow);
					break;
				//optional
				case CodePoint.MAXBLKEXT:
					checkLength(CodePoint.MAXBLKEXT, 2);
					maxblkext = reader.readSignedNetworkShort();
					if (this.trace)
						trace("maxblkext = "+maxblkext);
					break;
				//optional
				case CodePoint.RTNEXTDTA:
					checkLength(CodePoint.RTNEXTDTA, 1);
					rtnextdta = reader.readUnsignedByte();
					if (rtnextdta != CodePoint.RTNEXTROW && 
							rtnextdta != CodePoint.RTNEXTALL)
						invalidValue(CodePoint.RTNEXTDTA);
					if (this.trace)
						trace("rtnextdta = "+rtnextdta);
					break;
				// required for SQLAM >= 7
				case CodePoint.QRYINSID:
					checkLength(CodePoint.QRYINSID, 8);
					qryinsid = reader.readNetworkLong();
					gotQryinsid = true;
					if (this.trace)
						trace("qryinsid = "+qryinsid);
					break;
				// optional
				case CodePoint.MONITOR:
					parseMONITOR();
					break;
				default:
					invalidCodePoint(codePoint);
			}
			codePoint = reader.getCodePoint();
		}
		// check for required variables
		if (pkgnamcsn == null) {
			missingCodePoint(CodePoint.PKGNAMCSN);
			// never reached
			return null;
		}
 		if (!gotQryblksz)
			missingCodePoint(CodePoint.QRYBLKSZ);
		if (sqlamLevel >= MGRLVL_7 && !gotQryinsid)
			missingCodePoint(CodePoint.QRYINSID);

		// get the statement we are continuing
		DRDAStatement stmt = database.getDRDAStatement(pkgnamcsn);
		if (stmt == null)
		{
			//XXX should really throw a SQL Exception here
			invalidValue(CodePoint.CNTQRY);
			// never reached
			return null;
		}

		if (stmt.rsIsClosed())
		{
			writeQRYNOPRM(CodePoint.SVRCOD_ERROR);
			skipRemainder(true);
			return null;
		}
		stmt.setQueryOptions(blksize,qryrelscr,qryrownbr,qryrfrtbl,nbrrow,maxblkext,
						 qryscrorn,qryrowsns,qryblkrst,qryrtndta,qryrowset,
						 rtnextdta);

		if (reader.isChainedWithSameID())
			parseCNTQRYobjects(stmt);
		return stmt;
	}
	/**
	 * Skip remainder of current DSS and all chained DSS'es
	 *
	 * @param onlySkipSameIds True if we _only_ want to skip DSS'es
	 *   that are chained with the SAME id as the current DSS.
	 *   False means skip ALL chained DSSes, whether they're
	 *   chained with same or different ids.
	 * @exception DRDAProtocolException
	 */
	private void skipRemainder(boolean onlySkipSameIds) throws DRDAProtocolException
	{
		reader.skipDss();
		while (reader.isChainedWithSameID() ||
			(!onlySkipSameIds && reader.isChainedWithDiffID()))
		{
			reader.readDssHeader();
			reader.skipDss();
		}
	}
	/**
	 * Parse CNTQRY objects
	 * Instance Variables
	 *   OUTOVR - Output Override Descriptor - optional
	 *
	 * @param stmt DRDA statement we are working on
	 * @exception DRDAProtocolException
	 */
	private void parseCNTQRYobjects(DRDAStatement stmt) throws DRDAProtocolException, SQLException
	{
		int codePoint;
		do
		{
			correlationID = reader.readDssHeader();
			while (reader.moreDssData())
			{
				codePoint = reader.readLengthAndCodePoint( false );
				switch(codePoint)
				{
					// optional
					case CodePoint.OUTOVR:
						parseOUTOVR(stmt);
						break;
					default:
						invalidCodePoint(codePoint);
				}
			}
		} while (reader.isChainedWithSameID());

	}
	/**
	 * Parse OUTOVR - Output Override Descriptor
	 * This specifies the output format for data to be returned as output to a SQL
	 * statement or as output from a query.
	 *
	 * @param stmt	DRDA statement this applies to
	 * @exception DRDAProtocolException
	 */
	private void parseOUTOVR(DRDAStatement stmt) throws DRDAProtocolException, SQLException
	{
		boolean first = true;
		int numVars;
		int dtaGrpLen;
		int tripType;
		int tripId;
		int precision;
		int start = 0;
		while (true)
		{
			dtaGrpLen = reader.readUnsignedByte();
			tripType = reader.readUnsignedByte();
			tripId = reader.readUnsignedByte();
			// check if we have reached the end of the data
			if (tripType == FdocaConstants.RLO_TRIPLET_TYPE)
			{
				//read last part of footer
				reader.skipBytes();
				break;
			}
			numVars = (dtaGrpLen - 3) / 3;
			if (this.trace)
				trace("num of vars is: "+numVars);
			int[] outovr_drdaType = null;
			if (first)
			{
				outovr_drdaType = new int[numVars];
				first = false;
			}
			else
			{
				int[] oldoutovr_drdaType = stmt.getOutovr_drdaType();
				int oldlen = oldoutovr_drdaType.length;
				// create new array and copy over already read stuff
				outovr_drdaType = new int[oldlen + numVars];
				System.arraycopy(oldoutovr_drdaType, 0,
								 outovr_drdaType,0,
								 oldlen);
				start = oldlen;
			}
			for (int i = start; i < numVars + start; i++)
			{
                int drdaType = reader.readUnsignedByte();
                if (!database.supportsLocator()) { 
                    // ignore requests for locator when it is not supported
                    if ((drdaType >= DRDAConstants.DRDA_TYPE_LOBLOC)
                        && (drdaType <= DRDAConstants.DRDA_TYPE_NCLOBLOC)) {
                        if (this.trace) {
                            trace("ignoring drdaType: " + drdaType);
                        }
                        reader.readNetworkShort(); // Skip rest
                        continue;
                    }
                }
                outovr_drdaType[i] = drdaType;
				if (this.trace)
					trace("drdaType is: "+ outovr_drdaType[i]);
				precision = reader.readNetworkShort();
				if (this.trace)
					trace("drdaLength is: "+precision);
				outovr_drdaType[i] |= (precision << 8);
			}
			stmt.setOutovr_drdaType(outovr_drdaType);
		}
	}

    /**
     * Piggy-back any modified session attributes on the current message. Writes
     * a PBSD conataining one or both of PBSD_ISO and PBSD_SCHEMA. PBSD_ISO is
     * followed by the jdbc isolation level as an unsigned byte. PBSD_SCHEMA is
     * followed by the name of the current schema as an UTF-8 String.
     * @throws java.sql.SQLException
     * @throws com.pivotal.gemfirexd.internal.impl.drda.DRDAProtocolException
     */
    private void writePBSD() throws SQLException, DRDAProtocolException
    {
        final Database database = this.database;
        if (database == null || !appRequester.supportsSessionDataCaching()) {
            return;
        }
        PiggyBackedSessionData pbsd = database.getPiggyBackedSessionData(true);
        if (this.trace) {
            SanityManager.ASSERT(pbsd != null, "pbsd is not expected to be null");
        }
        if(pbsd == null) {
          return;
        }
        // DERBY-3596
        // Reset the flag. In sane builds it is used to avoid an assert, but
        // we want to reset it as soon as possible to avoid masking real bugs.
        // We have to do this because we are changing the connection state
        // at an unexpected time (deferred reset, see parseSECCHK). This was
        // done to avoid having to change the client code.
        this.deferredReset = false;
        pbsd.refresh();
        Checkpoint cp = null;
        EngineConnection econn = database.getConnection();
        if (econn != null) {
          LanguageConnectionContext lcc = econn.getLanguageConnectionContext();
          if (lcc != null) {
            cp = lcc.getCheckpoint();
          }
        }
        boolean endDdmDdsToBeWritten = false;
        boolean endDdmDdsWritten = false;
        boolean modified = pbsd.isModified();
        // KN: TODO When the default isolation level becomes RC then even if the 
        // pbsd is not modified we need to send the TXID
        if (modified) {
            writer.createDssReply();
            writer.startDdm(CodePoint.PBSD);

            if (pbsd.isIsoModified()) {
                writer.writeScalar1Byte(CodePoint.PBSD_ISO, pbsd.getIso());
            }

            if (pbsd.isSchemaModified()) {
                writer.startDdm(CodePoint.PBSD_SCHEMA);
                writer.writeString(pbsd.getSchema());
                writer.endDdm();
            }
// GemStone changes BEGIN
            if (pbsd.isIsoModified() && this.gfxdClientVersion != null) {
              // Piggyback the TXID generated in the reply.
              // Whether or not to send this extra data will depend on
              // whether the client is our drda client.
              // KN: TODO Code for this will be added in the client-server
              // exchange attribute code and a flag will be accordingly set in the
              TXId newTxId = pbsd.getTXId();
              byte nonNullTxIdFlag = 0;
              long memberId = 0;
              long uniqId = 0;
              if (newTxId == null) {
                // write a single byte 0 to indicate that TXId is null
                // I know redundant but just to be clear. DRDA message packing !!!
                nonNullTxIdFlag = 0;
              }
              else {
                // write a single byte 1 to indicate that TXId is non null
                // Then write the two
                nonNullTxIdFlag = 1;
                memberId = newTxId.getMemberId();
                uniqId = newTxId.getUniqId();
              }
              writer.writeScalar1Byte(CodePoint.PBSD_TXID, nonNullTxIdFlag);
              
              // KN: TODO compress and write please. Will do that in the next pass.
              if (nonNullTxIdFlag == 1) {
                writer.writeLong(memberId);
                writer.writeLong(uniqId);
              }
              
            }
            if (pbsd.isModified() && !pbsd.serverVersionSent() && this.gfxdClientVersion != null) {
              Version v = Version.CURRENT;
              writer.writeScalar2Bytes(CodePoint.PBSD_SVRVER, v.ordinal());
            }

            endDdmDdsToBeWritten = true;
            if (cp == null) {
              endDdmDdsWritten = true;
// GemStone changes END
              writer.endDdmAndDss();
            }
        }
// GemStone changes BEGIN
        if (!modified && cp != null && this.gfxdClientVersion != null) {
          if (SanityManager.TraceSingleHop) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "writing codepoint pbsd: " + CodePoint.PBSD);
          }
          writer.createDssReply();
          writer.startDdm(CodePoint.PBSD);
          endDdmDdsToBeWritten = true;
        }
        boolean logRegionsSent = SanityManager.TraceSingleHop;
        StringBuilder sb = logRegionsSent ? new StringBuilder() : null;
        if (cp != null) {
          // write changes
          Object[] brs = getBuckets(cp);
          int numChanged = (brs == null || brs.length == 0) ? 0 : brs.length;
          writer.writeScalar2Bytes(CodePoint.PBSD_REGIONS, numChanged);
          for (int i = 0; i < numChanged; i++) {
            Bucket b = (Bucket)brs[i];
            int prid = b.getPartitionedRegion().getPRId();
            int bid = b.getId();
            writer.writeShort(prid);
            writer.writeShort(bid);
            if (sb != null) {
              sb.append(prid);
              sb.append(':');
              sb.append(bid);
              sb.append(" --  ");
              sb.append(b.getName());
              sb.append(" ");
            }
          }
        }
        if (sb != null) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "affected regions being sent: " + sb.toString());
        }
        if (endDdmDdsToBeWritten && !endDdmDdsWritten) {
          writer.endDdmAndDss();
        }
// GemStone changes END
        pbsd.setUnmodified();
        if (this.trace) {
            PiggyBackedSessionData pbsdNew =
                database.getPiggyBackedSessionData(true);
            SanityManager.ASSERT(pbsdNew == pbsd,
                                 "pbsdNew and pbsd are expected to reference " +
                                 "the same object");
            pbsd.refresh();
            SanityManager.ASSERT
                (!pbsd.isModified(),
                 "pbsd=("+pbsd+") is not expected to be modified");
        }
    }
// GemStone changes BEGIN
  private Object[] getBuckets(Checkpoint cp) {
    int numChanged = cp.numChanged();
    if (numChanged == 0) {
      return null;
    }
    ArrayList<Bucket> buckets = null;
    for (int i = 0; i < numChanged; i++) {
      Object ob = cp.elementAt(i);
      if (ob instanceof Bucket) {
        if (buckets == null) {
          buckets = new ArrayList<Bucket>();
        }
        buckets.add((Bucket)ob);
      }
    }
    return buckets.toArray();
  }
// GemStone changes END
	/**
	 * Write OPNQRYRM - Open Query Complete
	 * Instance Variables
	 *   SVRCOD - Severity Code - required
	 *   QRYPRCTYP - Query Protocol Type - required
	 *   SQLCSRHLD - Hold Cursor Position - optional
	 *   QRYATTSCR - Query Attribute for Scrollability - optional - level 7
	 *   QRYATTSNS - Query Attribute for Sensitivity - optional - level 7
	 *   QRYATTUPD - Query Attribute for Updatability -optional - level 7
	 *   QRYINSID - Query Instance Identifier - required - level 7
	 *   SRVDGN - Server Diagnostic Information - optional
	 *
	 * @param isDssObject - return as a DSS object (part of a reply) 
	 * @param stmt - DRDA statement we are processing
	 *
	 * @exception DRDAProtocolException
	 */
	private void writeOPNQRYRM(boolean isDssObject, DRDAStatement stmt) 
		throws DRDAProtocolException, SQLException
	{
		if (this.trace)
			trace("WriteOPNQRYRM");

		if (isDssObject)
			writer.createDssObject();
		else
			writer.createDssReply();
		writer.startDdm(CodePoint.OPNQRYRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD,CodePoint.SVRCOD_INFO);

		// There is currently a problem specifying LMTBLKPRC for LOBs with JCC
		// JCC will throw an ArrayOutOfBounds exception.  Once this is fixed, we
		// don't need to pass the two arguments for getQryprctyp.
		int prcType = stmt.getQryprctyp();
		if (this.trace)
			trace("sending QRYPRCTYP: " + prcType);
		writer.writeScalar2Bytes(CodePoint.QRYPRCTYP, prcType);

		//pass the SQLCSRHLD codepoint only if statement producing the ResultSet has 
		//hold cursors over commit set. In case of stored procedures which use server-side
		//JDBC, the holdability of the ResultSet will be the holdability of the statement 
		//in the stored procedure, not the holdability of the calling statement.
		if (stmt.getCurrentDrdaResultSet().withHoldCursor == ResultSet.HOLD_CURSORS_OVER_COMMIT)
			writer.writeScalar1Byte(CodePoint.SQLCSRHLD, CodePoint.TRUE);
		if (sqlamLevel >= MGRLVL_7)
		{
			writer.writeScalarHeader(CodePoint.QRYINSID, 8);
			//This is implementer defined.  DB2 uses this for the nesting level
			//of the query.  A query from an application would be nesting level 0,
			//from a stored procedure, nesting level 1, from a recursive call of
			//a stored procedure, nesting level 2, etc.
			writer.writeInt(0);     
			//This is a unique sequence number per session
			writer.writeInt(session.qryinsid++);
			//Write the scroll attributes if they are set
			if (stmt.isScrollable())
			{
				writer.writeScalar1Byte(CodePoint.QRYATTSCR, CodePoint.TRUE);
				if ((stmt.getConcurType() == ResultSet.CONCUR_UPDATABLE) &&
						(stmt.getResultSet().getType() == 
						 ResultSet.TYPE_SCROLL_INSENSITIVE)) {
					writer.writeScalar1Byte(CodePoint.QRYATTSNS, 
											CodePoint.QRYSNSSTC);
				} else {
					writer.writeScalar1Byte(CodePoint.QRYATTSNS, 
											CodePoint.QRYINS);
				}
			}
			if (stmt.getConcurType() == ResultSet.CONCUR_UPDATABLE) {
				if (stmt.getResultSet() != null) { 
					// Resultset concurrency can be less than statement
					// concurreny if the underlying language resultset
					// is not updatable.
					if (stmt.getResultSet().getConcurrency() == 
						ResultSet.CONCUR_UPDATABLE) {
						writer.writeScalar1Byte(CodePoint.QRYATTUPD, 
												CodePoint.QRYUPD);
					} else {
						writer.writeScalar1Byte(CodePoint.QRYATTUPD, 
												CodePoint.QRYRDO);
					}
				} else {
					writer.writeScalar1Byte(CodePoint.QRYATTUPD, 
											CodePoint.QRYUPD);
				}
			} else {
				writer.writeScalar1Byte(CodePoint.QRYATTUPD, CodePoint.QRYRDO);
			}
		}
		writer.endDdmAndDss ();
	}
	/**
	 * Write ENDQRYRM - query process has terminated in such a manner that the
	 *	query or result set is now closed.  It cannot be resumed with the CNTQRY
	 *  command or closed with the CLSQRY command
	 * @param svrCod  Severity code - WARNING or ERROR
	 * @exception DRDAProtocolException
	 */
	private void writeENDQRYRM(int svrCod) throws DRDAProtocolException
	{
		writer.createDssReply();
		writer.startDdm(CodePoint.ENDQRYRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD,svrCod);
		writer.endDdmAndDss();
	}
/**
	 * Write ABNUOWRM - query process has terminated in an error condition
	 * such as deadlock or lock timeout.
	 * Severity code is always error
	 * 	 * @exception DRDAProtocolException
	 */
	private void writeABNUOWRM() throws DRDAProtocolException
	{
		writer.createDssReply();
		writer.startDdm(CodePoint.ABNUOWRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD,CodePoint.SVRCOD_ERROR);
		writeRDBNAM(database.dbName);
		writer.endDdmAndDss();
	}
	/**
	 * Parse database name
	 *
	 * @return database name
	 *
	 * @exception DRDAProtocolException
	 */
	private String parseRDBNAM() throws DRDAProtocolException
	{
		String name;
		byte [] rdbName = reader.readBytes();
		if (rdbName.length == 0)
		{
			// throw RDBNFNRM
			rdbNotFound(null);
		}
		//SQLAM level 7 allows db name up to 255, level 6 fixed len 18
		if (rdbName.length < CodePoint.RDBNAM_LEN || rdbName.length > CodePoint.MAX_NAME)
			badObjectLength(CodePoint.RDBNAM);
		name = reader.convertBytes(rdbName);
		// trim trailing blanks from the database name
		name = name.trim();
		if (this.trace)
// GemStone changes BEGIN
			// always use "gemfirexd" as DB name in GemFireXD
			trace("RdbName = " + name + "; actual = "
			    + Attribute.GFXD_DBNAME);
		return Attribute.GFXD_DBNAME;
		/* (original code)
			trace("RdbName " + name);
		return name;
		*/
// GemStone changes END
	}

	/**
	 * Write ACCSECRD
	 * If the security mechanism is known, we just send it back along with
	 * the security token if encryption is going to be used.
	 * If the security mechanism is not known, we send a list of the ones
	 * we know.
	 * Instance Variables
	 * 	SECMEC - security mechanism - required
	 *	SECTKN - security token	- optional (required if security mechanism 
	 *						uses encryption)
	 *  SECCHKCD - security check code - error occurred in processing ACCSEC
	 *
	 * @param securityCheckCode
	 * 
	 * @exception DRDAProtocolException
	 */
	private void writeACCSECRD(int securityCheckCode)
		throws DRDAProtocolException
	{
// GemStone changes BEGIN
		// z/OS DB2 driver expects createDssObject
		writer.createDssObject();
		/* (original code)
		writer.createDssReply();
		*/
// GemStone changes END
		writer.startDdm(CodePoint.ACCSECRD);

		if (securityCheckCode != CodePoint.SECCHKCD_NOTSUPPORTED)
			writer.writeScalar2Bytes(CodePoint.SECMEC, database.securityMechanism);
		else
		{ 
            // if server doesnt recognize or allow the client requested security mechanism,
            // then need to return the list of security mechanisms supported/allowed by the server
            
            // check if server is set to accept connections from client at a certain 
            // security mechanism, if so send only the security mechanism  that the 
            // server will accept, to the client
            if ( server.getSecurityMechanism() != NetworkServerControlImpl.INVALID_OR_NOTSET_SECURITYMECHANISM )
                writer.writeScalar2Bytes(CodePoint.SECMEC, server.getSecurityMechanism());
            else
            {
                // note: per the DDM manual , ACCSECRD response is of 
                // form SECMEC (value{value..})  
                // Need to fix the below to send a list of supported security 
                // mechanisms for value of one SECMEC codepoint (JIRA 926)
                // these are the ones we know about
                writer.writeScalar2Bytes(CodePoint.SECMEC, CodePoint.SECMEC_USRIDPWD);
                // include EUSRIDPWD in the list of supported secmec only if 
                // server can truely support it in the jvm that is running in
                if ( server.supportsEUSRIDPWD())
                    writer.writeScalar2Bytes(CodePoint.SECMEC, CodePoint.SECMEC_EUSRIDPWD);
                writer.writeScalar2Bytes(CodePoint.SECMEC, CodePoint.SECMEC_USRIDONL);
                writer.writeScalar2Bytes(CodePoint.SECMEC, CodePoint.SECMEC_USRSSBPWD);
            }
		}

		if (securityCheckCode != 0)
		{
			writer.writeScalar1Byte(CodePoint.SECCHKCD, securityCheckCode);
		}
		else
		{
			// we need to send back the key if encryption is being used
			if (database.securityMechanism == CodePoint.SECMEC_EUSRIDPWD)
				writer.writeScalarBytes(CodePoint.SECTKN, myPublicKey);
            else if (database.securityMechanism == CodePoint.SECMEC_USRSSBPWD)
				writer.writeScalarBytes(CodePoint.SECTKN, myTargetSeed);
		}
    	writer.endDdmAndDss ();

		if (securityCheckCode != 0) {
		// then we have an error and so can ignore the rest of the
		// DSS request chain.
			skipRemainder(false);
		}

		finalizeChain();
	}

	/**
	 * Parse security check
	 * Instance Variables
	 *	SECMGRNM - security manager name - optional, ignorable
	 *  SECMEC	- security mechanism - required
	 *	SECTKN	- security token - optional, (required if encryption used)
	 *	PASSWORD - password - optional, (required if security mechanism uses it)
	 *	NEWPASSWORD - new password - optional, (required if sec mech. uses it)
	 *	USRID	- user id - optional, (required if sec mec. uses it)
	 *	RDBNAM	- database name - optional (required if databases can have own sec.)
	 *
	 * 
	 * @return security check code
	 * @exception DRDAProtocolException
	 */
	private int parseSECCHK() throws DRDAProtocolException
	{
		int codePoint, securityCheckCode = 0;
		int securityMechanism = 0;
		databaseAccessException = null;
		reader.markCollection();
		codePoint = reader.getCodePoint();
        if (this.deferredReset) {
            // Skip the SECCHK, but assure a minimal degree of correctness.
            while (codePoint != -1) {
                switch (codePoint) {
                    // Note the fall-through.
                    // Minimal level of checking to detect protocol errors.
                    // NOTE: SECMGR level 8 code points are not handled.
                    case CodePoint.SECMGRNM:
                    case CodePoint.SECMEC:
                    case CodePoint.SECTKN:
                    case CodePoint.PASSWORD:
                    case CodePoint.NEWPASSWORD:
                    case CodePoint.USRID:
                    case CodePoint.RDBNAM:
                        reader.skipBytes();
                        break;
                    default:
                        invalidCodePoint(codePoint);
                }
                codePoint = reader.getCodePoint();
            }
        } else {
		while (codePoint != -1)
		{
			switch (codePoint)
			{
				//optional, ignorable
				case CodePoint.SECMGRNM:
					reader.skipBytes();
					break;
				//required
				case CodePoint.SECMEC:
					checkLength(CodePoint.SECMEC, 2);
					securityMechanism = reader.readNetworkShort();
					if (this.trace) 
						trace("parseSECCHK - Security mechanism = " + securityMechanism);
					//RESOLVE - spec is not clear on what should happen
					//in this case
					if (securityMechanism != database.securityMechanism)
						invalidValue(CodePoint.SECMEC);
					break;
				//optional - depending on security Mechanism 
				case CodePoint.SECTKN:
					if ((database.securityMechanism !=
                                        CodePoint.SECMEC_EUSRIDPWD) &&
                        (database.securityMechanism !=
                                        CodePoint.SECMEC_USRSSBPWD))
					{
						securityCheckCode = CodePoint.SECCHKCD_SECTKNMISSING_OR_INVALID;
						reader.skipBytes();
					}
                    else if (database.securityMechanism ==
                                                CodePoint.SECMEC_EUSRIDPWD)
                    {
                        if (database.decryptedUserId == null)
                        {
						    try {
							    database.decryptedUserId =
                                    reader.readEncryptedString(
                                                decryptionManager,
												database.securityMechanism,
                                                myPublicKey,
                                                database.secTokenIn);
						    } catch (SQLException se) {
							    println2Log(database.dbName, session.drdaID,
                                            se.getMessage());
							    if (securityCheckCode == 0)
                                    //userid invalid
								    securityCheckCode = CodePoint.SECCHKCD_13;
						    }
						    database.userId = database.decryptedUserId;
						    if (this.trace)
                                trace("**decrypted userid is: "+database.userId);
					    }
					    else if (database.decryptedPassword == null)
                        {
						    try {
							    database.decryptedPassword =
                                    reader.readEncryptedString(
                                            decryptionManager,
										    database.securityMechanism,
                                            myPublicKey,
                                            database.secTokenIn);
						    } catch (SQLException se) {	
                                println2Log(database.dbName, session.drdaID,
                                            se.getMessage());
                                if (securityCheckCode == 0)
                                    //password invalid
                                    securityCheckCode = CodePoint.SECCHKCD_0F;
                            }
                            database.password = database.decryptedPassword;
                            if (this.trace)
                                trace("**decrypted password is: " +
                                      database.password);
                        }
                    }
                    else if (database.securityMechanism ==
                                                CodePoint.SECMEC_USRSSBPWD)
                    {
                        if (database.passwordSubstitute == null)
                        {
                            database.passwordSubstitute = reader.readBytes();
					        if (this.trace)
                                trace("** Substitute Password is:" +
                                      DecryptionManager.toHexString(
                                        database.passwordSubstitute, 0,
                                        database.passwordSubstitute.length));
                            database.password =
                                DecryptionManager.toHexString(
                                    database.passwordSubstitute, 0,
                                    database.passwordSubstitute.length);
                        }
                    }
					else
					{
						tooMany(CodePoint.SECTKN);
					}
					break;
				//optional - depending on security Mechanism
				case CodePoint.PASSWORD:
					database.password = reader.readString();
					if (this.trace) trace("PASSWORD " + database.password);
					break;
				//optional - depending on security Mechanism
				//we are not supporting this method so we'll skip bytes
				case CodePoint.NEWPASSWORD:
					reader.skipBytes();
					break;
				//optional - depending on security Mechanism
				case CodePoint.USRID:
					database.userId = reader.readString();
					if (this.trace) trace("USERID " + database.userId);
					break;
				//optional - depending on security Mechanism
				case CodePoint.RDBNAM:
					String dbname = parseRDBNAM();
					if (database != null) 
					{
						if (!database.dbName.equals(dbname))
							rdbnamMismatch(CodePoint.SECCHK);
					}
					else
					{
						// we should already have added the database in ACCSEC
						// added code here in case we make the SECMEC session rather
						// than database wide
						addDatabase(dbname);
					}
					break;
				default:
					invalidCodePoint(codePoint);

			}
			codePoint = reader.getCodePoint();
		}
		// check for SECMEC which is required
		if (securityMechanism == 0)
			missingCodePoint(CodePoint.SECMEC);

		//check if we have a userid and password when we need it
		if (securityCheckCode == 0 && 
		   (database.securityMechanism == CodePoint.SECMEC_USRIDPWD||
		    database.securityMechanism == CodePoint.SECMEC_USRIDONL ))
		{
			if (database.userId == null)
				securityCheckCode = CodePoint.SECCHKCD_USERIDMISSING;
			else if (database.securityMechanism == CodePoint.SECMEC_USRIDPWD)
			{
			    if (database.password == null)
				securityCheckCode = CodePoint.SECCHKCD_PASSWORDMISSING;
			}
			//Note, we'll ignore encryptedUserId and encryptedPassword if they
			//are also set
		}

		if (securityCheckCode == 0 && 
				database.securityMechanism == CodePoint.SECMEC_USRSSBPWD)
		{
			if (database.userId == null)
				securityCheckCode = CodePoint.SECCHKCD_USERIDMISSING;
			else if (database.passwordSubstitute == null)
				securityCheckCode = CodePoint.SECCHKCD_PASSWORDMISSING;
		}

		if (securityCheckCode == 0 && 
				database.securityMechanism == CodePoint.SECMEC_EUSRIDPWD)
		{
			if (database.decryptedUserId == null)
				securityCheckCode = CodePoint.SECCHKCD_USERIDMISSING;
			else if (database.decryptedPassword == null)
				securityCheckCode = CodePoint.SECCHKCD_PASSWORDMISSING;
		}
		// RESOLVE - when we do security we need to decrypt encrypted userid & password
		// before proceeding
        } // End "if (deferredReset) ... else ..." block

		// verify userid and password, if we haven't had any errors thus far.
		if ((securityCheckCode == 0) && (databaseAccessException == null))
        {
            // DERBY-3596: Reset server side (embedded) physical connection for
            //     use with a new logical connection on the client.
            if (this.deferredReset) {
                // Reset the existing connection here.
                try {
                    database.getConnection().resetFromPool();
                    database.getConnection().setHoldability(
                            ResultSet.HOLD_CURSORS_OVER_COMMIT);
                    // Reset isolation level to default, as the client is in
                    // the process of creating a new logical connection.
// GemStone changes BEGIN
                    // GemFireXD defaults to NONE by default; no need to reset
                    // the isolation level anyways
                    /* (original code)
                    database.getConnection().setTransactionIsolation(
                            Connection.TRANSACTION_READ_COMMITTED);
                    */
// GemStone changes END
                } catch (SQLException sqle) {
                    handleException(sqle);
                }
            } else {
                securityCheckCode = verifyUserIdPassword();
            }
		}

		// Security all checked 
		if (securityCheckCode == 0)
			session.setState(session.CHKSEC);
		
		return securityCheckCode;

	}

	/**
	 * Write security check reply
	 * Instance variables
	 * 	SVRCOD - serverity code - required
	 *	SECCHKCD	- security check code  - required
	 *	SECTKN - security token - optional, ignorable
	 *	SVCERRNO	- security service error number
	 *	SRVDGN	- Server Diagnostic Information
	 *
	 * @exception DRDAProtocolException
	 */
	private void writeSECCHKRM(int securityCheckCode) throws DRDAProtocolException
	{
		writer.createDssReply();
		writer.startDdm(CodePoint.SECCHKRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, svrcodFromSecchkcd(securityCheckCode));
		writer.writeScalar1Byte(CodePoint.SECCHKCD, securityCheckCode);
    	writer.endDdmAndDss ();

		if (securityCheckCode != 0) {
		// then we have an error and are going to end up ignoring the rest
		// of the DSS request chain.
			skipRemainder(false);
		}

		finalizeChain();

	}
	/**
	 * Calculate SVRCOD value from SECCHKCD
	 *
	 * @param securityCheckCode
	 * @return SVRCOD value
	 */
	private int svrcodFromSecchkcd(int securityCheckCode)
	{
		if (securityCheckCode == 0 || securityCheckCode == 2 ||
			securityCheckCode == 5 || securityCheckCode == 8)
			return CodePoint.SVRCOD_INFO;
		else
			return CodePoint.SVRCOD_ERROR;
	}
	/**
	 * Parse access RDB
	 * Instance variables
	 *	RDBACCCL - RDB Access Manager Class - required must be SQLAM
	 *  CRRTKN - Correlation Token - required
	 *  RDBNAM - Relational database name -required
	 *  PRDID - Product specific identifier - required
	 *  TYPDEFNAM	- Data Type Definition Name -required
	 *  TYPDEFOVR	- Type definition overrides -required
	 *  RDBALWUPD -  RDB Allow Updates optional
	 *  PRDDTA - Product Specific Data - optional - ignorable
	 *  STTDECDEL - Statement Decimal Delimiter - optional
	 *  STTSTRDEL - Statement String Delimiter - optional
	 *  TRGDFTRT - Target Default Value Return - optional
	 *
	 * @return severity code
	 *
	 * @exception DRDAProtocolException
	 */
	private int parseACCRDB() throws  DRDAProtocolException, SQLException
	{
		int codePoint;
		int svrcod = 0;
		copyToRequired(ACCRDB_REQUIRED);
		reader.markCollection();
		codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch (codePoint)
			{
				//required
				case CodePoint.RDBACCCL:
					checkLength(CodePoint.RDBACCCL, 2);
					int sqlam = reader.readNetworkShort();
					if (this.trace) 
						trace("RDBACCCL = " + sqlam);
					// required to be SQLAM 

					if (sqlam != CodePoint.SQLAM)
						invalidValue(CodePoint.RDBACCCL);
					removeFromRequired(CodePoint.RDBACCCL);
					break;
				//required
				case CodePoint.CRRTKN:
					database.crrtkn = reader.readBytes();
					if (this.trace) 
						trace("crrtkn " + convertToHexString(database.crrtkn));
					removeFromRequired(CodePoint.CRRTKN);
					int l = database.crrtkn.length;
					if (l > CodePoint.MAX_NAME)
						tooBig(CodePoint.CRRTKN);
					// the format of the CRRTKN is defined in the DRDA reference
					// x.yz where x is 1 to 8 bytes (variable)
					// 		y is 1 to 8 bytes (variable)
					//		x is 6 bytes fixed
					//		size is variable between 9 and 23
					if (l < 9 || l > 23)
						invalidValue(CodePoint.CRRTKN);
					byte[] part1 = new byte[l - 6];
					for (int i = 0; i < part1.length; i++)
						part1[i] = database.crrtkn[i];
					long time = SignedBinary.getLong(database.crrtkn, 
							l-8, SignedBinary.BIG_ENDIAN); // as "long" as unique
					session.drdaID = reader.convertBytes(part1) + 
									time + leftBrace + session.connNum + rightBrace;
					if (this.trace) 
						trace("******************************************drdaID is: " + session.drdaID);
					database.setDrdaID(session.drdaID);
	
					break;
				//required
				case CodePoint.RDBNAM:
					String dbname = parseRDBNAM();
					if (database != null)
					{ 
						if (!database.dbName.equals(dbname))
							rdbnamMismatch(CodePoint.ACCRDB);
					}
					else
					{
						//first time we have seen a database name
						Database d = session.getDatabase(dbname);
						if (d == null)
							addDatabase(dbname);
						else
						{
							database = d;
							database.accessCount++;
						}
					}
					removeFromRequired(CodePoint.RDBNAM);
					break;
				//required
				case CodePoint.PRDID:
					appRequester.setClientVersion(reader.readString());
					if (this.trace) 
						trace("prdId " + appRequester.prdid);
					if (appRequester.prdid.length() > CodePoint.PRDID_MAX)
						tooBig(CodePoint.PRDID);

					/* If JCC version is 1.5 or later, send SQLWarning on CNTQRY */
					if (((appRequester.getClientType() == appRequester.JCC_CLIENT) &&
						(appRequester.greaterThanOrEqualTo(1, 5, 0))) ||
					   (appRequester.getClientType() == appRequester.DNC_CLIENT))
					{
						sendWarningsOnCNTQRY = true;
					}
					else sendWarningsOnCNTQRY = false;

					// The client can not request DIAGLVL because when run with
					// an older server it will cause an exception. Older version
					// of the server do not recognize requests for DIAGLVL.
					if ((appRequester.getClientType() == appRequester.DNC_CLIENT) &&
							appRequester.greaterThanOrEqualTo(10, 2, 0)) {
						diagnosticLevel = CodePoint.DIAGLVL1;
					}

					removeFromRequired(CodePoint.PRDID);
					break;
				//required
				case CodePoint.TYPDEFNAM:
					setStmtOrDbByteOrder(true, null, parseTYPDEFNAM());
					removeFromRequired(CodePoint.TYPDEFNAM);
					break;
				//required
				case CodePoint.TYPDEFOVR:
					parseTYPDEFOVR(null);
					removeFromRequired(CodePoint.TYPDEFOVR);
					break;
				//optional 
				case CodePoint.RDBALWUPD:
					checkLength(CodePoint.RDBALWUPD, 1);
// GemStone changes BEGIN
					// we now use the "allow update" byte
					// to indicate whether this is a failover
					// retry request
					int allowUpd = reader.readByte() & 0xff;
					database.resetFlags();

					if ((allowUpd & CodePoint.DISABLE_STREAMING)
					    == CodePoint.DISABLE_STREAMING) {
					  database.disableStreaming = true;
					  allowUpd &= CodePoint.CLEAR_DISABLE_STREAMING;
					}
					if ((allowUpd & CodePoint.SKIP_LISTENERS)
					    == CodePoint.SKIP_LISTENERS) {
					  database.skipListeners = true;
					  allowUpd &= CodePoint.CLEAR_SKIP_LISTENER;
					}					                                      
					if ((allowUpd & CodePoint.SKIP_CONSTRAINTS)
					    == CodePoint.SKIP_CONSTRAINTS) {
					  database.skipConstraintChecks = true;
					  allowUpd &= CodePoint.CLEAR_SKIP_CONSTRAINTS;
					}

					// now check the higher nibble
					final int allowUpdHN =
					    (allowUpd & CodePoint.FALSE);
					if ((allowUpdHN & ~CodePoint.FAILOVER)
					    == allowUpdHN) {
					  database.failover = true;
					}
					if ((allowUpdHN & ~CodePoint.SYNC_COMMITS)
					    == allowUpdHN) {
					  database.syncCommits = true;
					}
					if ((allowUpdHN & ~CodePoint
					    .DISABLE_TX_BATCHING) == allowUpdHN) {
					  database.disableTXBatching = true;
					}
          if ((allowUpdHN & CodePoint.QUERY_HDFS)
             == allowUpdHN) {
             // Since queryHDFS wont be used in snappydata so
             // using this bit in the byte for query routing information
             if (!Misc.getMemStore().isSnappyStore()) {
               database.queryHDFS = true;
             }
             else {
               database.routeQuery = true;
             }
          }
					database.rdbAllowUpdates = (allowUpd
					    & 0x0f) == (CodePoint.TRUE & 0x0f);
					// KN: TODO implement these two properly so that this knows
					// whether to read the failover context or not.
					int goodImplicitSavePoint = 0;
					long memberId = 0;
					long uniqueId = 0;
					int isolationlevel = 0;
					// Non null check is enough for older version thin client
                                        if (database.failover && this.gfxdClientVersion != null) {
                                          byte txnFailoverCtxWritten = reader.readByte();
                                          if (txnFailoverCtxWritten == ClientSharedData.CLIENT_FAILOVER_CONTEXT_WRITTEN) {
                                            goodImplicitSavePoint = reader.readInt(getByteOrder());
                                            isolationlevel = reader.readInt(getByteOrder());
                                            memberId = reader.readLong(getByteOrder());
                                            uniqueId = reader.readLong(getByteOrder());
                                            EngineConnection conn = database.getConnection();
                                            if (conn != null && ROLLBACK_IMPLEMENTED_FULLY) {
                                              GemFireTransaction gft = (GemFireTransaction)conn
                                                  .getLanguageConnectionContext().getTransactionExecute();
                                              gft.rollbackToPreviousState(isolationlevel,
                                                  goodImplicitSavePoint, memberId, (int)uniqueId);
                                            }
                                          }
                                        }
					/* (original code)
					database.rdbAllowUpdates = readBoolean(CodePoint.RDBALWUPD);
					*/
// GemStone changes END
					if (this.trace) 
						trace("rdbAllowUpdates = "+database.rdbAllowUpdates);
					break;
				//optional, ignorable
				case CodePoint.PRDDTA:
					// check that it fits in maximum but otherwise ignore for now
					if (reader.getDdmLength() > CodePoint.MAX_NAME)
						tooBig(CodePoint.PRDDTA);
// GemStone changes BEGIN
					/* original code -- skipped everything reader.skipBytes();*/
					final int prdlen = (int)reader.getDdmLength();
					// 0th byte is length excluding itself
					reader.readByte();
					// check for additional connection properties
					int bytesRead = checkPRDDTAForGFXDClientMagic(prdlen);
					this.gfxdClientVersion = null;
					if (bytesRead == ClientSharedData.BYTES_PREFIX_CLIENT_VERSION_LENGTH) {
					  // always BIG_ENDIAN here
					  final int byteOrder = SignedBinary.BIG_ENDIAN;
					  // read the client version
					  short versionOrdinal = reader.readShort(byteOrder);
                                          if (versionOrdinal > 0) {
					    try {
					      this.gfxdClientVersion = Version.fromOrdinal(
					          versionOrdinal, false);
					      // we don't use version for pre 2.0 clients
					      if (Version.GFXD_20.compareTo(
					          this.gfxdClientVersion) > 0) {
					        this.gfxdClientVersion = null;
					      }
					    } catch (UnsupportedGFXDVersionException ue) {
					      // ignore and assume older clients
					    }
                                          }
					  // read additional connection properties
					  // encoded them as a single integer for now
					  // this can be expanded in future using
					  // a special flag in this to send a separate
					  // Properties bag for example
					  int connProps = reader.readInt(byteOrder);
					  if ((connProps & CodePoint.SKIP_LOCKS) != 0) {
					    database.skipLocks = true;
					  }
					  // skip remaining bytes
					  bytesRead += (2 + 4);
					}
					// skip remaining bytes
					reader.skipBytes(prdlen - 1 - bytesRead);
// GemStone changes END
					break;
				case CodePoint.TRGDFTRT:
					byte b = reader.readByte();
// GemStone changes BEGIN
					//if (b  == 0xF1)
					if ((b & 0xff)  == 0xF1)
// GemStone changes END
						database.sendTRGDFTRT = true;
					break;
				//optional - not used in JCC so skip for now
// GemStone changes BEGIN
				// Decimal and string delimiter are sent by
				// z/OS DB2 driver. Should GemFireXD add support
				// for this? Looks a bit hard due to these
				// delimiters being hardcoded to . and '
				// in many places and not many use-cases for
				// having something else are known, so for now
				// this will be a limitation in GemFireXD.
				// Just check that these should be same as
				// expected by GemFireXD.
				case CodePoint.STTDECDEL:
					int decDelim = reader.readNetworkShort();
					if (this.trace) {
					  trace("decimal delimiter = "
					      + Integer.toHexString(decDelim));
					}
					// check package default should also be
					// period when implemented
					if (decDelim != CodePoint.DECDELPRD &&
					    decDelim != CodePoint.DFTPKG) {
					  throw new DRDAProtocolException(
					      DRDAProtocolException.DRDA_Proto_VALNSPRM,
					      this, codePoint,
					      DRDAProtocolException.NO_ASSOC_ERRCD,
					      new Object[] { "unsupported decimal delimiter code "
					          + Integer.toHexString(decDelim) });
					}
					break;
				case CodePoint.STTSTRDEL:
					int strDelim = reader.readNetworkShort();
					if (this.trace) {
					  trace("string delimiter = "
					      + Integer.toHexString(strDelim));
					}
					// check package default should also be
					// apostrophe when implemented
					if (strDelim != CodePoint.STRDELAP &&
					    strDelim != CodePoint.DFTPKG) {
					  throw new DRDAProtocolException(
					      DRDAProtocolException.DRDA_Proto_VALNSPRM,
					      this, codePoint,
					      DRDAProtocolException.NO_ASSOC_ERRCD,
					      new Object[] { "unsupported string delimiter code "
					          + Integer.toHexString(strDelim) });
					}
					break;
				case CodePoint.DIAGLVL:
					byte lvl = reader.readByte();
					if (lvl == CodePoint.DIAGLVL1 ||
					    lvl == CodePoint.DIAGLVL2) {
					  diagnosticLevel = lvl;
					}
					else {
					  // default to LVL0
					  diagnosticLevel = CodePoint.DIAGLVL0;
					}
				/* (original code)
				case CodePoint.STTDECDEL:
				case CodePoint.STTSTRDEL:
					codePointNotSupported(codePoint);
				*/
	                                break;
				case CodePoint.NCJCACHESIZE:
	                                database.ncjCacheSize = parseNCJCACHESIZE();
	                                database.checkAndSetPossibleNCJCacheSizeDuplicate();
	                                break;
	                        case CodePoint.NCJBATCHSIZE:
	                                database.ncjBatchSize = parseNCJBATCHSIZE();
	                                database.checkAndSetPossibleNCJBatchSizeDuplicate();
// GemStone changes END
					break;
				default:
					invalidCodePoint(codePoint);
			}
			codePoint = reader.getCodePoint();
		}
		checkRequired(CodePoint.ACCRDB);
		// check that we can support the double-byte and mixed-byte CCSIDS
		// set svrcod to warning if they are not supported
		if ((database.ccsidDBC != 0 && !server.supportsCCSID(database.ccsidDBC)) ||
				(database.ccsidMBC != 0 && !server.supportsCCSID(database.ccsidMBC))) 
			svrcod = CodePoint.SVRCOD_WARNING;
		return svrcod;
	}

	/**
	 * Parse TYPDEFNAM
	 *
	 * @return typdefnam
	 * @exception DRDAProtocolException
	 */
	private String parseTYPDEFNAM() throws DRDAProtocolException
	{
		String typDefNam = reader.readString();
		if (this.trace) trace("typeDefName " + typDefNam);
		if (typDefNam.length() > CodePoint.MAX_NAME)
			tooBig(CodePoint.TYPDEFNAM);
		checkValidTypDefNam(typDefNam);
		// check if the typedef is one we support
		if (!typDefNam.equals(CodePoint.TYPDEFNAM_QTDSQLASC)  &&
			!typDefNam.equals(CodePoint.TYPDEFNAM_QTDSQLJVM) &&
// GemStone changes BEGIN
			!typDefNam.equals(CodePoint.TYPDEFNAM_QTDSQL370) &&
			!typDefNam.equals(CodePoint.TYPDEFNAM_QTDSQL400) &&
// GemStone changes END
			!typDefNam.equals(CodePoint.TYPDEFNAM_QTDSQLX86))
			valueNotSupported(CodePoint.TYPDEFNAM);
		return typDefNam;
	}

	/**
	 * Set a statement or the database' byte order, depending on the arguments
	 *
	 * @param setDatabase   if true, set database' byte order, otherwise set statement's
	 * @param stmt          DRDAStatement, used when setDatabase is false
	 * @param typDefNam     TYPDEFNAM value
	 */
	private void setStmtOrDbByteOrder(boolean setDatabase, DRDAStatement stmt, String typDefNam)
	{
		int byteOrder = (typDefNam.equals(CodePoint.TYPDEFNAM_QTDSQLX86) ?
							SignedBinary.LITTLE_ENDIAN : SignedBinary.BIG_ENDIAN);
		if (setDatabase)
		{
			database.typDefNam = typDefNam;
			database.byteOrder = byteOrder;
		}
		else
		{
			stmt.typDefNam = typDefNam;
			stmt.byteOrder = byteOrder;
		}
	}

	/**
	 * Write Access to RDB Completed
	 * Instance Variables
	 *  SVRCOD - severity code - 0 info, 4 warning -required
	 *  PRDID - product specific identifier -required
	 *  TYPDEFNAM - type definition name -required
	 *  TYPDEFOVR - type definition overrides - required
	 *  RDBINTTKN - token which can be used to interrupt DDM commands - optional
	 *  CRRTKN	- correlation token - only returned if we didn't get one from requester
	 *  SRVDGN - server diagnostic information - optional
	 *  PKGDFTCST - package default character subtype - optional
	 *  USRID - User ID at the target system - optional
	 *  SRVLST - Server List
	 * 
	 * @exception DRDAProtocolException
	 */
	private void writeACCRDBRM(int svrcod) throws DRDAProtocolException
	{
		writer.createDssReply();
		writer.startDdm(CodePoint.ACCRDBRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, svrcod);
		writer.writeScalarString(CodePoint.PRDID, server.prdId);
		//TYPDEFNAM -required - JCC doesn't support QTDSQLJVM so for now we
		// just use ASCII, though we should eventually be able to use QTDSQLJVM
		// at level 7
		writer.writeScalarString(CodePoint.TYPDEFNAM,
								 CodePoint.TYPDEFNAM_QTDSQLASC);
		writeTYPDEFOVR();
		writer.endDdmAndDss ();

         // Write the initial piggy-backed data, currently the isolation level
         // and the schema name. Only write it if the client supports session
         // data caching.
         // Sending the session data on connection initialization was introduced
         // in Derby 10.7.
// GemStone changes BEGIN
/* original code
         if ((appRequester.getClientType() == appRequester.DNC_CLIENT) &&
                 appRequester.greaterThanOrEqualTo(10, 7, 0)) {
*/
	 // if gfxd client version is there then send the PBSD data as well
         if ((appRequester.getClientType() == appRequester.DNC_CLIENT) &&
                 appRequester.greaterThanOrEqualTo(10, 7, 0) || this.gfxdClientVersion != null) {
             try {
                 writePBSD();
             } catch (SQLException se) {
                 server.consoleExceptionPrint(se);
                 errorInChain(se);
             }
         }
		finalizeChain();
	}
	
	private void writeTYPDEFOVR() throws DRDAProtocolException
	{
		//TYPDEFOVR - required - only single byte and mixed byte are specified
		writer.startDdm(CodePoint.TYPDEFOVR);
		writer.writeScalar2Bytes(CodePoint.CCSIDSBC, server.CCSIDSBC);
		writer.writeScalar2Bytes(CodePoint.CCSIDMBC, server.CCSIDMBC);
		// PKGDFTCST - Send character subtype and userid if requested
		if (database.sendTRGDFTRT)
		{
			// default to multibyte character
			writer.startDdm(CodePoint.PKGDFTCST);
			writer.writeShort(CodePoint.CSTMBCS);
			writer.endDdm();
			// userid
			writer.startDdm(CodePoint.USRID);
			writer.writeString(database.userId);
			writer.endDdm();
		}
		writer.endDdm();

	}
	
	/**
	 * Parse Type Defintion Overrides
	 * 	TYPDEF Overrides specifies the Coded Character SET Identifiers (CCSIDs)
	 *  that are in a named TYPDEF.
	 * Instance Variables
	 *  CCSIDSBC - CCSID for Single-Byte - optional
	 *  CCSIDDBC - CCSID for Double-Byte - optional
	 *  CCSIDMBC - CCSID for Mixed-byte characters -optional
	 *
 	 * @param st	Statement this TYPDEFOVR applies to
	 *
	 * @exception DRDAProtocolException
	 */
	private void parseTYPDEFOVR(DRDAStatement st) throws  DRDAProtocolException
	{
		int codePoint;
		int ccsidSBC = 0;
		int ccsidDBC = 0;
		int ccsidMBC = 0;
		String ccsidSBCEncoding = null;
		String ccsidDBCEncoding = null;
		String ccsidMBCEncoding = null;

		reader.markCollection();

		codePoint = reader.getCodePoint();
		// at least one of the following instance variable is required
		// if the TYPDEFOVR is specified in a command object
		if (codePoint == -1 && st != null)
			missingCodePoint(CodePoint.CCSIDSBC);

		while (codePoint != -1)
		{
			switch (codePoint)
			{
				case CodePoint.CCSIDSBC:
					checkLength(CodePoint.CCSIDSBC, 2);
					ccsidSBC = reader.readNetworkShort();
					try {
						ccsidSBCEncoding = 
							CharacterEncodings.getJavaEncoding(ccsidSBC);
					} catch (Exception e) {
						valueNotSupported(CodePoint.CCSIDSBC);
					}
					if (this.trace) 
						trace("ccsidsbc = " + ccsidSBC + " encoding = " + ccsidSBCEncoding);
					break;
				case CodePoint.CCSIDDBC:
					checkLength(CodePoint.CCSIDDBC, 2);
					ccsidDBC = reader.readNetworkShort();
					try {
						ccsidDBCEncoding = 
							CharacterEncodings.getJavaEncoding(ccsidDBC);
					} catch (Exception e) {
						// we write a warning later for this so no error
						// unless for a statement
						ccsidDBCEncoding = null;
						if (st != null)
							valueNotSupported(CodePoint.CCSIDSBC);
					}
					if (this.trace) 
						trace("ccsiddbc = " + ccsidDBC + " encoding = " + ccsidDBCEncoding);
					break;
				case CodePoint.CCSIDMBC:
					checkLength(CodePoint.CCSIDMBC, 2);
					ccsidMBC = reader.readNetworkShort();
					try {
						ccsidMBCEncoding = 
							CharacterEncodings.getJavaEncoding(ccsidMBC);
					} catch (Exception e) {
						// we write a warning later for this so no error
						ccsidMBCEncoding = null;
						if (st != null)
							valueNotSupported(CodePoint.CCSIDMBC);
					}
					if (this.trace) 
						trace("ccsidmbc = " + ccsidMBC + " encoding = " + ccsidMBCEncoding);
					break;
				default:
					invalidCodePoint(codePoint);

			}
			codePoint = reader.getCodePoint();
		}
		if (st == null)
		{
			if (ccsidSBC != 0)
			{
				database.ccsidSBC = ccsidSBC;
				database.ccsidSBCEncoding = ccsidSBCEncoding;
			}
			if (ccsidDBC != 0)
			{
				database.ccsidDBC = ccsidDBC;
				database.ccsidDBCEncoding = ccsidDBCEncoding;
			}
			if (ccsidMBC != 0)
			{
				database.ccsidMBC = ccsidMBC;
				database.ccsidMBCEncoding = ccsidMBCEncoding;
			}
		}
		else
		{
			if (ccsidSBC != 0)
			{
				st.ccsidSBC = ccsidSBC;
				st.ccsidSBCEncoding = ccsidSBCEncoding;
			}
			if (ccsidDBC != 0)
			{
				st.ccsidDBC = ccsidDBC;
				st.ccsidDBCEncoding = ccsidDBCEncoding;
			}
			if (ccsidMBC != 0)
			{
				st.ccsidMBC = ccsidMBC;
				st.ccsidMBCEncoding = ccsidMBCEncoding;
			}
		}
	}
	/**
	 * Parse PRPSQLSTT - Prepare SQL Statement
	 * Instance Variables
	 *   RDBNAM - Relational Database Name - optional
	 *   PKGNAMCSN - RDB Package Name, Consistency Token, and Section Number - required
	 *   RTNSQLDA - Return SQL Descriptor Area - optional
	 *   MONITOR - Monitor events - optional.
	 *   
	 * @return return 0 - don't return sqlda, 1 - return input sqlda, 
	 * 		2 - return output sqlda
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private int parsePRPSQLSTT() throws DRDAProtocolException,SQLException
	{
		int codePoint;
		boolean rtnsqlda = false;
		boolean rtnOutput = true; 	// Return output SQLDA is default
// GemStone changes BEGIN
		boolean isUnprepared = false;
                boolean sendSingleHopInfo = false;
                boolean createQueryInfo = true;
                String regionName = null;
                boolean sendStatementUUID = false;
// GemStone changes END
		String typdefnam;
		Pkgnamcsn pkgnamcsn = null;

		DRDAStatement stmt = null;  
		Database databaseToSet = null;

		reader.markCollection();

		codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch (codePoint)
			{
				// optional
				case CodePoint.RDBNAM:
					setDatabase(CodePoint.PRPSQLSTT);
					databaseToSet = database;
					break;
				// required
				case CodePoint.PKGNAMCSN:
					pkgnamcsn = parsePKGNAMCSN(); 
					break;
// GemStone changes BEGIN
				case CodePoint.PKGNAMCSN_SHOP_WITH_STR:
				        checkPre1302ClientVersionForSingleHop("PRPSQLSTT");
				        pkgnamcsn = parsePKGNAMCSN();
				        regionName = parseNOCMorNOCS();
				        if (regionName != null &&
				            regionName.length() > 0) {
				          createQueryInfo = false;
				        }
                                        break;
// GemStone changes END
				//optional
				case CodePoint.RTNSQLDA:
				// Return SQLDA with description of statement
// GemStone changes BEGIN
					checkLength(CodePoint.RTNSQLDA, 1);
					byte val = reader.readByte();
					if ((isUnprepared = (val & CodePoint
					    .RTNSQLDA_UNPREPARED) == CodePoint
					    .RTNSQLDA_UNPREPARED)) {
					  val &= CodePoint.CLEAR_RTNSQLDA_UNPREPARED;
					  isUnprepared = true;
					}
					else {
					  isUnprepared = false;
					}
					if (val == CodePoint.TRUE) { 
					  rtnsqlda = true;
					}
					else if (val == CodePoint.FALSE) {
					  rtnsqlda = false;
					}
					else {
					  invalidCodePoint(codePoint);
					}
					/* (original code)
					rtnsqlda = readBoolean(CodePoint.RTNSQLDA);
					*/
// GemStone changes END
					break;
				//optional
				case CodePoint.TYPSQLDA:
// GemStone changes BEGIN
				  checkLength(CodePoint.TYPSQLDA, 1);
				  byte sqldaType = reader.readByte();
				  if (sqldaType == CodePoint.TYPSQLDA_X_OUTPUT_AND_SINGLEHOP_INFO) {
				    checkPre1302ClientVersionForSingleHop("PRPSQLSTT");
				    sqldaType = CodePoint.TYPSQLDA_X_OUTPUT;
				    sendSingleHopInfo = true;
				    if (SanityManager.TraceSingleHop) {
				      SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
				          "DRDAConnThread::parsePRPSQLSTT requires single "
				              + "hop information as sqldaType is: " + sqldaType);
				    }
				  }
// GemStone changes END
					rtnOutput = parseTYPSQLDA(sqldaType);
					break;
				//optional
				case CodePoint.MONITOR:
					parseMONITOR();
					break;
				case CodePoint.GETSTMTID:
				        reader.skipBytes();
				        sendStatementUUID = true;
				        break;
				default:
					invalidCodePoint(codePoint);

			}
			codePoint = reader.getCodePoint();
		}

		stmt = database.newDRDAStatement(pkgnamcsn);
// GemStone changes BEGIN
		// set the statement as the current statement
		stmt.setStatus("PARSING");
		database.setCurrentStatement(stmt);
		if (sendSingleHopInfo) {
		  stmt.setSendSingleHopInfo(true);
		}
// GemStone changes END
		String sqlStmt = parsePRPSQLSTTobjects(stmt);
		if (databaseToSet != null)
			stmt.setDatabase(database);
// GemStone changes BEGIN
		if (this.pendingSetSchema != null) {
		  stmt.setSchema(this.pendingSetSchema);
		  this.pendingSetSchema = null;
		}
		if (isUnprepared) {
		  stmt.implicitPrepare(sqlStmt);
		}
		else {
		  stmt.explicitPrepare(sqlStmt, createQueryInfo);
		  if (regionName != null) {
		    stmt.setRegionName(regionName);
		  }
		}
	        if(sendStatementUUID) {
	          stmt.setSendStatementUUID(true);
	        }
                stmt.setStatus("COMPILED");
		/* (original code)
		stmt.explicitPrepare(sqlStmt);
		// set the statement as the current statement
		database.setCurrentStatement(stmt);
		*/
// GemStone changes END

		if (!rtnsqlda)
			return 0;
		else if (rtnOutput)   
			return 2;
		else
			return 1;
	}
	/**
	 * Parse PRPSQLSTT objects
	 * Objects
	 *  TYPDEFNAM - Data type definition name - optional
	 *  TYPDEFOVR - Type defintion overrides - optional
	 *  SQLSTT - SQL Statement required
	 *  SQLATTR - Cursor attributes on prepare - optional - level 7
	 *
	 * If TYPDEFNAM and TYPDEFOVR are supplied, they apply to the objects
	 * sent with the statement.  Once the statement is over, the default values
	 * sent in the ACCRDB are once again in effect.  If no values are supplied,
	 * the values sent in the ACCRDB are used.
	 * Objects may follow in one DSS or in several DSS chained together.
	 * 
	 * @return SQL statement
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private String parsePRPSQLSTTobjects(DRDAStatement stmt) 
		throws DRDAProtocolException, SQLException
	{
		String sqlStmt = null;
		int codePoint;
		do
		{
			correlationID = reader.readDssHeader();
			while (reader.moreDssData())
			{
				codePoint = reader.readLengthAndCodePoint( false );
				switch(codePoint)
				{
					// required
					case CodePoint.SQLSTT:
						sqlStmt = parseEncodedString();
// GemStone changes BEGIN
						// treat empty sqlStmt as empty query string
						// instead of throwing protocol exception
						if (sqlStmt == null) {
						  sqlStmt = "";
						}
// GemStone changes END
						if (this.trace) 
							trace("sqlStmt = " + sqlStmt);
						break;
					// optional
					case CodePoint.TYPDEFNAM:
						setStmtOrDbByteOrder(false, stmt, parseTYPDEFNAM());
						break;
					// optional
					case CodePoint.TYPDEFOVR:
						parseTYPDEFOVR(stmt);
						break;
					// optional
					case CodePoint.SQLATTR:
						parseSQLATTR(stmt);
						break;
					default:
						invalidCodePoint(codePoint);
				}
			}
		} while (reader.isChainedWithSameID());
		if (sqlStmt == null)
			missingCodePoint(CodePoint.SQLSTT);

		return sqlStmt;
	}

	/**
	 * Parse TYPSQLDA - Type of the SQL Descriptor Area
	 *
	 * @return true if for output; false otherwise
	 * @exception DRDAProtocolException
	 */
	// Gemstone changes BEGIN
	private boolean parseTYPSQLDA(byte sqldaType) throws DRDAProtocolException
	{
//		checkLength(CodePoint.TYPSQLDA, 1);
//		byte sqldaType = reader.readByte();
	// Gemstone changes END
		if (this.trace) 
			trace("typSQLDa " + sqldaType);
		if (sqldaType == CodePoint.TYPSQLDA_STD_OUTPUT ||
				sqldaType == CodePoint.TYPSQLDA_LIGHT_OUTPUT ||
				sqldaType == CodePoint.TYPSQLDA_X_OUTPUT)
			return true;
		else if (sqldaType == CodePoint.TYPSQLDA_STD_INPUT ||
					 sqldaType == CodePoint.TYPSQLDA_LIGHT_INPUT ||
					 sqldaType == CodePoint.TYPSQLDA_X_INPUT)
				return false;
		else
			invalidValue(CodePoint.TYPSQLDA);

		// shouldn't get here but have to shut up compiler
		return false;
	}
	/**
	 * Parse SQLATTR - Cursor attributes on prepare
	 *   This is an encoded string. Can have combination of following, eg INSENSITIVE SCROLL WITH HOLD
	 * Possible strings are
	 *  SENSITIVE DYNAMIC SCROLL [FOR UPDATE]
	 *  SENSITIVE STATIC SCROLL [FOR UPDATE]
	 *  INSENSITIVE SCROLL
	 *  FOR UPDATE
	 *  WITH HOLD
	 *
	 * @param stmt DRDAStatement
	 * @exception DRDAProtocolException
	 */
	protected void parseSQLATTR(DRDAStatement stmt) throws DRDAProtocolException
	{
		String attrs = parseEncodedString();
		if (this.trace)
			trace("sqlattr = '" + attrs+"'");
		//let Derby handle any errors in the types it doesn't support
		//just set the attributes

		boolean validAttribute = false;
		if (attrs.indexOf("INSENSITIVE SCROLL") != -1 || attrs.indexOf("SCROLL INSENSITIVE") != -1) //CLI
		{
			stmt.scrollType = ResultSet.TYPE_SCROLL_INSENSITIVE;
			stmt.concurType = ResultSet.CONCUR_READ_ONLY;
			validAttribute = true;
		}
		if ((attrs.indexOf("SENSITIVE DYNAMIC SCROLL") != -1) || (attrs.indexOf("SENSITIVE STATIC SCROLL") != -1))
		{
			stmt.scrollType = ResultSet.TYPE_SCROLL_SENSITIVE;
			validAttribute = true;
		}

		if ((attrs.indexOf("FOR UPDATE") != -1))
		{
			validAttribute = true;
			stmt.concurType = ResultSet.CONCUR_UPDATABLE;
		}

		if (attrs.indexOf("WITH HOLD") != -1)
		{
			stmt.withHoldCursor = ResultSet.HOLD_CURSORS_OVER_COMMIT;
			validAttribute = true;
		}

		if (!validAttribute)
		{
			invalidValue(CodePoint.SQLATTR);
		}
	}

	/**
	 * Parse DSCSQLSTT - Describe SQL Statement previously prepared
	 * Instance Variables
	 *	TYPSQLDA - sqlda type expected (output or input)
	 *  RDBNAM - relational database name - optional
	 *  PKGNAMCSN - RDB Package Name, Consistency Token and Section Number - required
	 *  MONITOR - Monitor events - optional.
	 *
	 * @return expect "output sqlda" or not
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private boolean parseDSCSQLSTT() throws DRDAProtocolException,SQLException
	{
		int codePoint;
		boolean rtnOutput = true;	// default
		Pkgnamcsn pkgnamcsn = null;
		reader.markCollection();

		codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch (codePoint)
			{
				// optional
				case CodePoint.TYPSQLDA:
				        // Gemstone changes BEGIN
				        checkLength(CodePoint.TYPSQLDA, 1);
			                byte sqldaType = reader.readByte();
			                if (sqldaType == CodePoint.TYPSQLDA_X_OUTPUT_AND_SINGLEHOP_INFO) {
			                  sqldaType = CodePoint.TYPSQLDA_X_OUTPUT;
			                }
			                // Gemstone changes END
					rtnOutput = parseTYPSQLDA(sqldaType);
					break;
				// optional
				case CodePoint.RDBNAM:
					setDatabase(CodePoint.DSCSQLSTT);
					break;
				// required
				case CodePoint.PKGNAMCSN:
					pkgnamcsn = parsePKGNAMCSN();
					DRDAStatement stmt = database.getDRDAStatement(pkgnamcsn);     
					if (stmt == null)
					{
						invalidValue(CodePoint.PKGNAMCSN);
					}
					break;
				//optional
				case CodePoint.MONITOR:
					parseMONITOR();
					break;
				default:
					invalidCodePoint(codePoint);
			}
			codePoint = reader.getCodePoint();
		}
		if (pkgnamcsn == null)
			missingCodePoint(CodePoint.PKGNAMCSN);
		return rtnOutput;
	}

	/**
	 * Parse EXCSQLSTT - Execute non-cursor SQL Statement previously prepared
	 * Instance Variables
	 *  RDBNAM - relational database name - optional
	 *  PKGNAMCSN - RDB Package Name, Consistency Token and Section Number - required
	 *  OUTEXP - Output expected
	 *  NBRROW - Number of rows to be inserted if it's an insert
	 *  PRCNAM - procedure name if specified by host variable, not needed for Derby
	 *  QRYBLKSZ - query block size
	 *  MAXRSLCNT - max resultset count
	 *  MAXBLKEXT - Max number of extra blocks
	 *  RSLSETFLG - resultset flag
	 *  RDBCMTOK - RDB Commit Allowed - optional
	 *  OUTOVROPT - output override option
	 *  QRYROWSET - Query Rowset Size - Level 7
	 *  MONITOR - Monitor events - optional.
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
// GemStone changes BEGIN
	private byte parseEXCSQLSTT() throws DRDAProtocolException, SQLException
	/* (original code
	private void parseEXCSQLSTT() throws DRDAProtocolException,SQLException
	*/
// GemStone changes END
	{
		int codePoint;
		String strVal;
		reader.markCollection();

		codePoint = reader.getCodePoint();
		boolean outputExpected = false;
// GemStone changes BEGIN
		boolean batchOutput = false;
		boolean localExecDueToSHOP = false;
		int isolationlevel = 0;
		Set<Integer> bucketSet = null;
		boolean dbSync = false;
		boolean rtnsqlda = false;
		boolean rtnOutput = true;
// GemStone changes END
		Pkgnamcsn pkgnamcsn = null;
		int numRows = 1;	// default value
		int blkSize =  0;
 		int maxrslcnt = 0; 	// default value
		int maxblkext = CodePoint.MAXBLKEXT_DEFAULT;
		int qryrowset = CodePoint.QRYROWSET_DEFAULT;
		int outovropt = CodePoint.OUTOVRFRS;
		byte [] rslsetflg = null;
		String procName = null;

		while (codePoint != -1)
		{
			switch (codePoint)
			{
				// optional
				case CodePoint.RDBNAM:
					setDatabase(CodePoint.EXCSQLSTT);
					break;
				// required
					// Gemstone changes BEGIN
					/* original code
				//  case CodePoint.PKGNAMCSN:
				//  pkgnamcsn = parsePKGNAMCSN();
				 * */
				 
				case CodePoint.PKGNAMCSN:
                                case CodePoint.PKGNAMCSN_SHOP:
                                case CodePoint.PKGNAMCSN_SHOP_WITH_STR:
                                  pkgnamcsn = parsePKGNAMCSN();
                                  if (codePoint != CodePoint.PKGNAMCSN) {
                                    checkPre1302ClientVersionForSingleHop("EXCSQLSTT");
                                    bucketSet = readBucketSet();
                                    if (bucketSet != null) {
                                      if (bucketSet.contains(ResolverUtils.TOKEN_FOR_DB_SYNC)) {
                                        dbSync = true;
                                        bucketSet.remove(ResolverUtils.TOKEN_FOR_DB_SYNC);
                                      }
                                      if (Version.GFXD_20.compareTo(this.gfxdClientVersion) <= 0) {
                                        byte txID = reader.readByte();
                                        if (txID == ClientSharedData.CLIENT_TXID_WRITTEN) {
                                          assert this.currTxIdForSHOPExecution == null : "txid = " + this.currTxIdForSHOPExecution + "not expected as the client has already sent it";
                                          isolationlevel = reader.readInt(getByteOrder());
                                          long memberId = reader.readLong(getByteOrder());
                                          long uniqueId = reader.readLong(getByteOrder());
                                          this.currTxIdForSHOPExecution = TXId.valueOf(memberId, (int)uniqueId);
                                        }
                                      }
                                    }
                                    localExecDueToSHOP = true;
                                  }
                                  if (SanityManager.TraceSingleHop) {
                                    SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                                        "DRDAConnThread::parseEXCSQLSTT pkgnamcsn: " + pkgnamcsn);
                                  }
                               // Gemstone changes END
					
					break;
				// optional
				case CodePoint.OUTEXP:
// GemStone changes BEGIN
				  // augmented OUTEXP with flag to indicate
				  // batch statement execution
				  checkLength(CodePoint.OUTEXP, 1);
				  batchOutput = false;
				  byte outv = reader.readByte();
				  if ((outv & CodePoint.OUTEXP_BATCH_OUTPUT)
				      == CodePoint.OUTEXP_BATCH_OUTPUT) {
				    batchOutput = true;
				    outv &= CodePoint.CLEAR_OUTEXP_BATCH_OUTPUT;
				  }
				  else {
				    batchOutput = false;
				  }
				  if (outv == CodePoint.TRUE) {
				    outputExpected = true;
				  }
				  else if (outv == CodePoint.FALSE) {
				    outputExpected = false;
				  }
				  else {
				    invalidCodePoint(CodePoint.OUTEXP);
				  }
				  if (this.trace) {
				    trace("outexp = " + outputExpected
				        + " batchOutput = " + batchOutput);
				  }
				  /* (original code)
					outputExpected = readBoolean(CodePoint.OUTEXP);
					if (this.trace) 
						trace("outexp = "+ outputExpected);
				  */
// GemStone changes END
					break;
				// optional
				case CodePoint.NBRROW:
					checkLength(CodePoint.NBRROW, 4);
					numRows = reader.readNetworkInt();
					if (this.trace) 
						trace("# of rows: "+numRows);
					break;
				// optional
				case CodePoint.PRCNAM:
					procName = reader.readString();
					if (this.trace) 
						trace("Procedure Name = " + procName);
					break;
				// optional
				case CodePoint.QRYBLKSZ:
					blkSize = parseQRYBLKSZ();
					break;
				// optional
				case CodePoint.MAXRSLCNT:
					// this is the maximum result set count
					// values are 0 - requester is not capabable of receiving result
					// sets as reply data in the response to EXCSQLSTT
					// -1 - requester is able to receive all result sets
					checkLength(CodePoint.MAXRSLCNT, 2);
					maxrslcnt = reader.readNetworkShort();
					if (this.trace) 
						trace("max rs count: "+maxrslcnt);
					break;
				// optional
				case CodePoint.MAXBLKEXT:
					// number of extra qury blocks of answer set data per result set
					// 0 - no extra query blocks
					// -1 - can receive entire result set
					checkLength(CodePoint.MAXBLKEXT, 2);
					maxblkext = reader.readNetworkShort();
					if (this.trace) 
						trace("max extra blocks: "+maxblkext);
					break;
				// optional
				case CodePoint.RSLSETFLG:
					//Result set flags
					rslsetflg = reader.readBytes();
					for (int i=0;i<rslsetflg.length;i++)
						if (this.trace) 
							trace("rslsetflg: "+rslsetflg[i]);
					break;
				// optional
				case CodePoint.RDBCMTOK:
					parseRDBCMTOK();
					break;
				// optional
				case CodePoint.OUTOVROPT:
					outovropt = parseOUTOVROPT();
					break;
				// optional
				case CodePoint.QRYROWSET:
					//Note minimum for OPNQRY is 0, we'll assume it is the same
					//for EXCSQLSTT though the standard doesn't say
					qryrowset = parseQRYROWSET(0);
					break;
				//optional
				case CodePoint.MONITOR:
					parseMONITOR();
					break;
// GemStone changes BEGIN
				case CodePoint.RTNSQLDA:
					rtnsqlda = readBoolean(CodePoint.RTNSQLDA);
					break;
				case CodePoint.TYPSQLDA:
					checkLength(CodePoint.TYPSQLDA, 1);
					byte sqldaType = reader.readByte();
					rtnOutput = parseTYPSQLDA(sqldaType);
					break;
// GemStone changes END

				default:
					invalidCodePoint(codePoint);
			}
			codePoint = reader.getCodePoint();
		}

		if (pkgnamcsn == null) {
			missingCodePoint(CodePoint.PKGNAMCSN);
			// never reached
			return CodePoint.FALSE;
		}

		DRDAStatement stmt;
		boolean needPrepareCall = false;

		stmt  = database.getDRDAStatement(pkgnamcsn);
		if (SanityManager.TraceSingleHop) {
                  SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                      "DRDAConnThread::parseEXCSQLSTT statement obtained from database: " + stmt.toDebugString(" "));
                }
		boolean isProcedure = (procName !=null || 
							   (stmt != null && 
								stmt.wasExplicitlyPrepared() &&
								stmt.isCall));

		if (isProcedure)		// stored procedure call
		{
			if ( stmt == null  || !(stmt.wasExplicitlyPrepared()))
			{ 				
				stmt  = database.newDRDAStatement(pkgnamcsn);
				stmt.setQryprctyp(CodePoint.QRYBLKCTL_DEFAULT);				
				needPrepareCall = true;
			}
				
			stmt.procName = procName;
			stmt.outputExpected = outputExpected;
		}
		else
		{
		  if (stmt != null && localExecDueToSHOP) {
	                  EngineConnection conn = database.getConnection();
	                  Checkpoint cp = null;
	                  if (this.currTxIdForSHOPExecution != null) {
	                    cp = conn.masqueradeAsTxn(this.currTxIdForSHOPExecution, isolationlevel);
	                  }
	                  LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
                          if (SanityManager.TraceSingleHop) {
                            boolean origSkipLocks = lcc.skipLocks();
                            SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                                "DRDAConnThread::parseEXCSQLSTT conn: stmt == null for pkgnamcsn: "
                                    + pkgnamcsn + ", bucketset: " + bucketSet
                                    + ", local exec due to shop: " + true + ", skipLocks: "
                                    + origSkipLocks + " and lcc: " + lcc + " dbSync: " + dbSync);
                          }
	                  stmt.incSingleHopStats();
	                  lcc.setExecuteLocally(bucketSet, stmt.getRegion(), dbSync, cp);
	                }
			// we can't find the statement
		  if (stmt == null)
	                {
	                        //XXX should really throw a SQL Exception here
	                  // in single hop case this stmt can be null
	                  // so check that
	                  // Gemstone changes BEGIN
	                  invalidValue(CodePoint.PKGNAMCSN);
	                  // never reached
	                  return CodePoint.FALSE;

	                  /*original code
	                        invalidValue(CodePoint.PKGNAMCSN);
	                        */
	                  // Gemstone changes END
	                }
//			if (stmt == null)
//			{
//				invalidValue(CodePoint.PKGNAMCSN);
//			}
			stmt.setQryprctyp(CodePoint.QRYBLKCTL_DEFAULT);
		}

 		stmt.nbrrow = numRows;
 		stmt.qryrowset = qryrowset;
 		stmt.blksize = blkSize;
 		stmt.maxblkext = maxblkext;
 		stmt.maxrslcnt = maxrslcnt;
 		stmt.outovropt = outovropt;
 		stmt.rslsetflg = rslsetflg;
// GemStone changes BEGIN
 		stmt.batchOutput = batchOutput;
 		if (this.pendingSetSchema != null) {
 		  stmt.setSchema(this.pendingSetSchema);
 		  this.pendingSetSchema = null;
 		}
        if (pendingStatementTimeout >= 0) {
            stmt.getUnderlyingStatement().setQueryTimeout(
                pendingStatementTimeout);
            /* (original code)
        if (pendingStatementTimeout >= 0) {
            stmt.getPreparedStatement().setQueryTimeout(pendingStatementTimeout);
            */
// GemStone changes BEGIN
            pendingStatementTimeout = -1;
        }
 
	
		// set the statement as the current statement
		database.setCurrentStatement(stmt);
		
		boolean hasResultSet;
// GemStone changes BEGIN
		boolean batchAdd = false;
		boolean batchExec = false;
// GemStone changes END
		if (reader.isChainedWithSameID()) 
		{
// GemStone changes BEGIN
			byte result = parseEXCSQLSTTobjects(stmt);
			if (result == PRPSQLSTT_ERROR) {
			  return PRPSQLSTT_ERROR;
			}
			else if (result == CodePoint.OUTEXP_BATCH_OUTPUT) {
			  batchAdd = true;
			}
			else if ((result & CodePoint.OUTEXP_BATCH_OUTPUT)
			    == CodePoint.OUTEXP_BATCH_OUTPUT) {
			  batchExec = true;
			  result &= CodePoint.CLEAR_OUTEXP_BATCH_OUTPUT;
			}
			hasResultSet = (result == CodePoint.TRUE);
			/* (original code)
			hasResultSet = parseEXCSQLSTTobjects(stmt);
			*/
		} else 
		{
			if (isProcedure  && (needPrepareCall))
			{
				// if we had parameters the callable statement would
				// be prepared with parseEXCQLSTTobjects, otherwise we
				// have to do it here
				String prepareString = "call " + stmt.procName +"()";
				if (this.trace) 
					trace ("$$$prepareCall is: "+prepareString);
				database.getConnection().clearWarnings();
				CallableStatement cs = (CallableStatement) stmt.prepare(prepareString);
			}
			stmt.getUnderlyingStatement().clearWarnings();
			byte result = executeStatement(stmt, false);
			if (result == PRPSQLSTT_ERROR) {
			  return PRPSQLSTT_ERROR;
			}
			else {
			  hasResultSet = (result == CodePoint.TRUE);
			}
			/* (original code)
			stmt.ps.clearWarnings();
			hasResultSet = stmt.execute();
			*/
// GemStone changes END
		}
		
		
		ResultSet rs = null;
		if (hasResultSet)
		{
			rs = stmt.getResultSet();
		}
		// temp until ps.execute() return value fixed
		hasResultSet = (rs != null);
		int numResults = 0;
		if (hasResultSet)
		{
			numResults = stmt.getNumResultSets();
			writeRSLSETRM(stmt);
		}

		// First of all, we send if there really are output params. Otherwise
		// CLI (.Net driver) fails. DRDA spec (page 151,152) says send SQLDTARD
		// if server has output param data to send.
		boolean sendSQLDTARD = stmt.hasOutputParams() && outputExpected;
		if (isProcedure)
		{
			if (sendSQLDTARD) {
				writer.createDssObject();
				writer.startDdm(CodePoint.SQLDTARD);
				writer.startDdm(CodePoint.FDODSC);
				writeQRYDSC(stmt, true);
				writer.endDdm();
				writer.startDdm(CodePoint.FDODTA);
				writeFDODTA(stmt);
                writer.endDdm();
                writer.endDdmAndDss();

                if (stmt.getExtDtaObjects() != null)
                {
                    // writeScalarStream() ends the dss
                    writeEXTDTA(stmt);
                }
			}
			else if (hasResultSet)
			// DRDA spec says that we MUST return either an
			// SQLDTARD or an SQLCARD--the former when we have
			// output parameters, the latter when we don't.
			// If we have a result set, then we have to write
			// the SQLCARD _now_, since it is expected before
			// we send the result set info below; if we don't
			// have a result set and we don't send SQLDTARD,
			// then we can wait until we reach the call to
			// checkWarning() below, which will write an
			// SQLCARD for us.
				writeNullSQLCARDobject();
		}

		//We need to marke that params are finished so that we know we 
		// are ready to send resultset info.
		stmt.finishParams();
			
// GemStone changes BEGIN
		Statement ps = stmt.getUnderlyingStatement();
		/* (original code)
		PreparedStatement ps = stmt.getPreparedStatement();
		*/
// GemStone changes END
		int rsNum = 0;
		do {
		if (hasResultSet)
		{
			stmt.setCurrentDrdaResultSet(rsNum);
			//indicate that we are going to return data
			stmt.setQryrtndta(true);
			if (! isProcedure)
				checkWarning(null, ps, null, -1, true, true);
			if (rsNum == 0)
				writeSQLRSLRD(stmt);
			writeOPNQRYRM(true, stmt);
			writeSQLCINRD(stmt);
			writeQRYDSC(stmt, false);
			stmt.rsSuspend();

			/* Currently, if LMTBLKPRC is used, a pre-condition is that no lob columns.
			 * But in the future, when we do support LOB in LMTBLKPRC, the drda spec still
			 * does not allow LOB to be sent with OPNQRYRM.  So this "if" here will have
			 * to add "no lob columns".
			 */
			if (stmt.getQryprctyp() == CodePoint.LMTBLKPRC) {
			     stmt.setStatus("SENDING EXCSQLSTT RESULTS " + rsNum);
			     writeQRYDTA(stmt);
			}
		}
		else  if (! sendSQLDTARD)
		{
// GemStone changes BEGIN
			if (batchAdd) {
			  stmt.numBatchResults++;
			}
			else {
			  if (batchExec) {
			    sendBatchResult(stmt);
			  }
			  else {
			    int updateCount = ps.getUpdateCount();
			    checkWarning(database.getConnection(),
			        stmt.getUnderlyingStatement(), null,
			        updateCount, null, true, true);
			    return CodePoint.TRUE;
			  }
			}
			return CodePoint.FALSE;
			/* (original code)
			int updateCount = ps.getUpdateCount();
			if (false && (database.RDBUPDRM_sent == false) &&
				! isProcedure)
			{
				writeRDBUPDRM();
			}

			checkWarning(database.getConnection(), stmt.ps, null, updateCount, true, true);
			*/
// GemStone changes END
		}

		} while(hasResultSet && (++rsNum < numResults));
		
		return CodePoint.TRUE /* GemStone addition */;			// we are done
	}


	/**
	 * Parse RDBCMTOK - tells the database whether to allow commits or rollbacks
	 * to be executed as part of the command
	 * Since we don't have a SQL commit or rollback command, we will just ignore
	 * this for now
	 *
	 * @exception DRDAProtocolException
	 */
	private void parseRDBCMTOK() throws DRDAProtocolException
	{
		boolean rdbcmtok = readBoolean(CodePoint.RDBCMTOK);
		if (this.trace) 
			trace("rdbcmtok = " + rdbcmtok);
	}

	/**
	 * Parse EXCSQLSTT command objects
	 * Command Objects
	 *  TYPDEFNAM - Data Type Definition Name - optional
	 *  TYPDEFOVR - TYPDEF Overrides -optional
	 *	SQLDTA - optional, variable data, specified if prpared statement has input parameters
	 *	EXTDTA - optional, externalized FD:OCA data
	 *	OUTOVR - output override descriptor, not allowed for stored procedure calls
	 *
	 * If TYPDEFNAM and TYPDEFOVR are supplied, they apply to the objects
	 * sent with the statement.  Once the statement is over, the default values
	 * sent in the ACCRDB are once again in effect.  If no values are supplied,
	 * the values sent in the ACCRDB are used.
	 * Objects may follow in one DSS or in several DSS chained together.
	 * 
	 * @param stmt	the DRDAStatement to execute
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
// GemStone changes BEGIN
	// now also returns flag to indicate batch execution
	private byte parseEXCSQLSTTobjects(DRDAStatement stmt)
	    throws DRDAProtocolException, SQLException
	/* (original code)
	private boolean parseEXCSQLSTTobjects(DRDAStatement stmt) throws DRDAProtocolException, SQLException
	*/
// GemStone changes END
	{
		int codePoint;
		boolean gotSQLDTA = false, gotEXTDTA = false;
// GemStone changes BEGIN
		byte result = CodePoint.FALSE;
		/* (original code)
		boolean result = false;
		*/
// GemStone changes END
		do
		{
			correlationID = reader.readDssHeader();
			while (reader.moreDssData())
			{
				codePoint = reader.readLengthAndCodePoint( true );
				switch(codePoint)
				{
					// optional
					case CodePoint.TYPDEFNAM:
						setStmtOrDbByteOrder(false, stmt, parseTYPDEFNAM());
						stmt.setTypDefValues();
						break;
					// optional
					case CodePoint.TYPDEFOVR:
						parseTYPDEFOVR(stmt);
						stmt.setTypDefValues();
						break;
					// required
					case CodePoint.SQLDTA:
						parseSQLDTA(stmt);
						gotSQLDTA = true;
						break;
					// optional
					case CodePoint.EXTDTA:	
						readAndSetAllExtParams(stmt, true);
// GemStone changes BEGIN
						stmt.getUnderlyingStatement()
						    .clearWarnings();
						if (stmt.batchOutput) {
						  result = executeBatch(stmt, true);
						}
						else {
						  result = executeStatement(stmt,
						      false);
						}
						/* (original code)
						stmt.ps.clearWarnings();
						result = stmt.execute();
						*/
// GemStone changes END
						gotEXTDTA = true;
						break;
					// optional
					case CodePoint.OUTOVR:
						parseOUTOVR(stmt);
						break;
					default:
						invalidCodePoint(codePoint);
				}
			}
		} while (reader.isChainedWithSameID());

		// SQLDTA is required
		if (! gotSQLDTA)
			missingCodePoint(CodePoint.SQLDTA);
		
		if (! gotEXTDTA) {
// GemStone changes BEGIN
			stmt.getUnderlyingStatement().clearWarnings();
			if (stmt.batchOutput) {
			  return executeBatch(stmt, true);
			}
			else {
			  return executeStatement(stmt, false);
			}
		}
		return result;
	}

        private byte executeBatch(final DRDAStatement stmt, final boolean doAdd)
            throws SQLException {
          if (doAdd) {
            stmt.addBatch();
          }
          if (reader.getCurrChainState() != DssConstants.DSS_NOCHAIN) {
            return CodePoint.OUTEXP_BATCH_OUTPUT;
          }
          else {
            // end of batch
            final boolean result = stmt.execute(true);
            return (byte)(CodePoint.OUTEXP_BATCH_OUTPUT
                | (result ? CodePoint.TRUE : CodePoint.FALSE));
          }
        }

        private void sendBatchResult(final DRDAStatement stmt)
            throws DRDAProtocolException, SQLException {
          checkWarning(database.getConnection(), stmt.ps, null, -3,
              stmt.batchResult, true, true);
          stmt.resetBatch();
        }

        private void sendBatchException(final DRDAStatement stmt,
            SQLException e, boolean lastFinalize) throws DRDAProtocolException {
          BatchUpdateException batchEx = null;
          // int[] successCounts = null;
          int index = 0;
          if (e instanceof BatchUpdateException) {
            batchEx = (BatchUpdateException)e;
            // successCounts = batchEx.getUpdateCounts();
            if (batchEx.getNextException() != null) {
              e = batchEx.getNextException();
            }
          }
          final OutputStream os = getOutputStream();
          // for any successful updates send the
          // counts separately; we don't exactly know
          // which counts correspond to which element
          // in the batch but that's okay since the
          // exception will be recreated on the client
          // where any such information is lost anyways
          /* below is unreliable so skipping it completely
          if (successCounts != null
              && successCounts.length > 0) {
            for (index = 0; index < successCounts
                 .length; index++) {
              writeSQLCARDs(null, successCounts[index]);
              writer.finalizeChain(
                  (byte)DssConstants.DSSCHAIN, os);
            }
          }
          */
          for (; index < stmt.numBatchResults; index++) {
            writeSQLCARDs(e, 0);
            errorInChain(e);
            if (lastFinalize && index == (stmt.numBatchResults - 1)) {
              writer.finalizeChain(reader.getCurrChainState(), os);
            }
            else {
              writer.finalizeChain((byte)DssConstants.DSSCHAIN, os);
            }
            if (e.getNextException() != null) {
              e = e.getNextException();
            }
          }
          stmt.resetBatch();
        }

        private byte executeStatement(final DRDAStatement stmt,
	    final boolean isOpenQry)
	        throws DRDAProtocolException, SQLException {
	  if (stmt.isPrepared()) {
	    return stmt.execute(false) ? CodePoint.TRUE : CodePoint.FALSE;
	  }
	  else {
	    try {
	   // GemStone changes BEGIN
	      // send the statement ID to client
	      // this is done just once per statement
	      if (stmt.needStatementUUID()) {
	        try {
	          writeSQLSTMTID(stmt.getStatementUUID());
	          finalizeChain();
	        } finally {
	          stmt.setSendStatementUUID(false);
	        }
	      }
	   // GemStone changes END
	          
	      final boolean result = stmt.execute(false);
	      // write the meta-data now for unprepared queries
	      writeSQLDARD(stmt,
                  (stmt.sqldaType ==  CodePoint.TYPSQLDA_LIGHT_OUTPUT),
                  database.getConnection().getWarnings(), false,false);
	      finalizeChain();
	      return result ? CodePoint.TRUE : CodePoint.FALSE;
	    } catch (SQLException sqle) {
	      // treat like an exception during prepare
	      writer.clearDSSesBackToMark(stmt.pstmtParseWriterMark);
	      writeSQLCARDs(sqle, 0, true);
	      errorInChain(sqle);

	      // chain has to be always finalized
	      finalizeChain();

	      // if the chain is broken no need to send the result for error
	      // in query execution
	      if (reader.isChainedWithSameID()
	          || reader.isChainedWithDiffID()) {
	        // read the command objects
	        // for ps with parameter
	        // Skip objects/parameters
	        skipRemainder(true);

	        if (isOpenQry) {
	          // If we failed to prepare, then we fail
	          // to open, which  means OPNQFLRM.
	          writeOPNQFLRM(null);
	        }
	        else {
	          writeSQLCARDs(null, 0);
	        }
	      }

	      // indicates skip writing anything else for the command
	      return PRPSQLSTT_ERROR;
	    }
	  }
	}
		/* (original code)
			stmt.ps.clearWarnings();
			result = stmt.execute();
		}
		
		return result;
	}
                */

        /**
         * Check if server supports single-hop for given client version.
         * Since GFXD 1.3.0.2, the hashing and thus routing object has changed
         * for integer columns, so older clients can no longer use single-hop.
         */
        private void checkPre1302ClientVersionForSingleHop(String command)
            throws SQLException {
          if (ResolverUtils.isUsingGFXD1302Hashing()
              && (appRequester.getClientType() != AppRequester.DNC_CLIENT
                  || !appRequester.greaterThanOrEqualTo(10, 4, 1))) {
            throw Util.generateCsSQLException(SQLState.DRDA_COMMAND_NOT_IMPLEMENTED,
                "{ " + command + " with pre GemFireXD 1.3.0.2 clients against "
                    + "GemFireXD 1.3.0.2 or greater servers }");
          }
        }
// GemStone changes END

	/**
	 * Write SQLCINRD - result set column information
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void writeSQLCINRD(DRDAStatement stmt) throws DRDAProtocolException,SQLException
	{		
		ResultSet rs = stmt.getResultSet();

		writer.createDssObject();
		writer.startDdm(CodePoint.SQLCINRD);
		if (sqlamLevel >= MGRLVL_7)
// GemStone changes BEGIN
		  writeSQLDHROW(rs.getHoldability());
		  /* (original code)
			writeSQLDHROW(((EngineResultSet) rs).getHoldability());
		  */
// GemStone changes END

		ResultSetMetaData rsmeta = rs.getMetaData();
		int ncols = rsmeta.getColumnCount();
		writer.writeShort(ncols);	// num of columns
		if (sqlamLevel >= MGRLVL_7)
		{
			for (int i = 0; i < ncols; i++)
				writeSQLDAGRP (rsmeta, null, i, true);
		}
		else
		{
			for (int i = 0; i < ncols; i++)
			{
				writeVCMorVCS(rsmeta.getColumnName(i+1));
				writeVCMorVCS(rsmeta.getColumnLabel(i+1));
				writeVCMorVCS(null);
			}
		}
		writer.endDdmAndDss();
	}

	/**
	 * Write SQLRSLRD - result set reply data
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void writeSQLRSLRD(DRDAStatement stmt) throws DRDAProtocolException,SQLException
	{
		int numResults = stmt.getNumResultSets();

		writer.createDssObject();
		writer.startDdm(CodePoint.SQLRSLRD);
		writer.writeShort(numResults); // num of result sets

		for (int i = 0; i < numResults; i ++)
			{
				writer.writeInt(i);	// rsLocator
				writeVCMorVCS(stmt.getResultSetCursorName(i));
				writer.writeInt(1);	// num of rows XXX resolve, it doesn't matter for now

			}
		writer.endDdmAndDss();
	}

	/**
	 * Write RSLSETRM
	 * Instance variables
	 *  SVRCOD - Severity code - Information only - required
	 *  PKGSNLST - list of PKGNAMCSN -required
	 *  SRVDGN - Server Diagnostic Information -optional
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void writeRSLSETRM(DRDAStatement stmt) throws DRDAProtocolException,SQLException
	{
		int numResults = stmt.getNumResultSets();
		writer.createDssReply();
		writer.startDdm(CodePoint.RSLSETRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, 0);
		writer.startDdm(CodePoint.PKGSNLST);
		
		for (int i = 0; i < numResults; i++)
			writePKGNAMCSN(stmt.getResultSetPkgcnstkn(i).getBytes());
		writer.endDdm();
		writer.endDdmAndDss();
	}

	
	/**
	 * Parse SQLDTA - SQL program variable data 
	 * and handle exception.
	 * @see #parseSQLDTA_work
	 */

	private void parseSQLDTA(DRDAStatement stmt) throws DRDAProtocolException,SQLException
	{
		try {
			parseSQLDTA_work(stmt);
		} 
		catch (SQLException se)
		{
			skipRemainder(true);
			throw se;
		}
	}
	
	/**
	 * Parse SQLDTA - SQL program variable data
	 * Instance Variables
	 *  FDODSC - FD:OCA data descriptor - required
	 *  FDODTA - FD:OCA data - optional
	 *    
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void parseSQLDTA_work(DRDAStatement stmt) throws DRDAProtocolException,SQLException
	{
		String strVal;
		PreparedStatement ps = stmt.getPreparedStatement();
		int codePoint;
		ParameterMetaData pmeta = null;

		// Clear params without releasing storage
		stmt.clearDrdaParams();

		int numVars = 0;
		boolean rtnParam = false;

		reader.markCollection();		
		codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
				switch (codePoint)
				{
					// required
					case CodePoint.FDODSC:
						while (reader.getDdmLength() > 6) //we get parameter info til last 6 byte
					{
						int dtaGrpLen = reader.readUnsignedByte();
						int numVarsInGrp = (dtaGrpLen - 3) / 3;
						if (this.trace) 
							trace("num of vars in this group is: "+numVarsInGrp);
						reader.readByte();		// tripletType
						reader.readByte();		// id
						for (int j = 0; j < numVarsInGrp; j++)
						{
							final byte t = reader.readByte();
							if (this.trace) 
								trace("drdaType is: "+ "0x" +
       								  Integer.toHexString(t));
							int drdaLength = reader.readNetworkShort();
							if (this.trace) 
								trace("drdaLength is: "+drdaLength);
							stmt.addDrdaParam(t, drdaLength);
						}
					}
					numVars = stmt.getDrdaParamCount();
					if (this.trace)
						trace("numVars = " + numVars);
					if (ps == null)		// it is a CallableStatement under construction
					{
						String marks = "(?";	// construct parameter marks
						for (int i = 1; i < numVars; i++)
							marks += ", ?";
						String prepareString = "call " + stmt.procName + marks + ")";
						if (this.trace) 
							trace ("$$ prepareCall is: "+prepareString);
						CallableStatement cs = null;
						try {
							cs = (CallableStatement)
								stmt.prepare(prepareString);			
							stmt.registerAllOutParams();
						} catch (SQLException se) {
							if (! stmt.outputExpected || 
								(!se.getSQLState().equals(SQLState.LANG_NO_METHOD_FOUND)))
								throw se;
							if (this.trace) 
								trace("****** second try with return parameter...");
							// Save first SQLException most likely suspect
							if (numVars == 1)
								prepareString = "? = call " + stmt.procName +"()";
							else
								prepareString = "? = call " + stmt.procName +"("+marks.substring(3) + ")";
							if (this.trace)
								trace ("$$ prepareCall is: "+prepareString);
							try {
								cs = (CallableStatement) stmt.prepare(prepareString);
							} catch (SQLException se2)
							{
								// The first exception is the most likely suspect
								throw se;
							}
							rtnParam = true;
						}
						ps = cs;
						stmt.ps = ps;
					}

					pmeta = stmt.getParameterMetaData();

					reader.readBytes(6);	// descriptor footer
					break;
				// optional
				case CodePoint.FDODTA:
					reader.readByte();	// row indicator
					for (int i = 0; i < numVars; i++)
					{
					
						if ((stmt.getParamDRDAType(i+1) & 0x1) == 0x1)	// nullable
						{
							int nullData = reader.readUnsignedByte();
							if ((nullData & 0xFF) == FdocaConstants.NULL_DATA)
							{
								if (this.trace)
									trace("******param null");
								if (pmeta.getParameterMode(i + 1) 
									!= JDBC30Translation.PARAMETER_MODE_OUT )
										ps.setNull(i+1, pmeta.getParameterType(i+1));
								if (stmt.isOutputParam(i+1))
									stmt.registerOutParam(i+1);
								continue;
							}
						}

						// not null, read and set it
						readAndSetParams(i, stmt, pmeta);
					}
					break;
				case CodePoint.EXTDTA:
					readAndSetAllExtParams(stmt, false);
					break;
				default:
					invalidCodePoint(codePoint);

			}
				codePoint = reader.getCodePoint();
		}


	}

	private int getByteOrder()
	{
		DRDAStatement stmt = database.getCurrentStatement();
		return ((stmt != null && stmt.typDefNam != null) ? stmt.byteOrder : database.byteOrder);
	}

	/**
	 * Read different types of input parameters and set them in
	 * PreparedStatement
	 * @param i			index of the parameter
	 * @param stmt      drda statement
	 * @param pmeta		parameter meta data
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void readAndSetParams(int i,
								  DRDAStatement stmt,
								  ParameterMetaData pmeta)
				throws DRDAProtocolException, SQLException
	{
		PreparedStatement ps = stmt.getPreparedStatement();

		// mask out null indicator
		final int drdaType = ((stmt.getParamDRDAType(i+1) | 0x01) & 0xff);
		final int paramLenNumBytes = stmt.getParamLen(i+1);

		if (ps instanceof CallableStatement)
		{
			if (stmt.isOutputParam(i+1))
			{
				CallableStatement cs = (CallableStatement) ps;
				cs.registerOutParameter(i+1, stmt.getOutputParamType(i+1));
			}
		}

		switch (drdaType)
		{
			case DRDAConstants.DRDA_TYPE_NSMALL:
			{
				short paramVal = (short) reader.readShort(getByteOrder());
				if (this.trace)
					trace("short parameter value is: "+paramVal);
 				// DB2 does not have a BOOLEAN java.sql.bit type, it's sent as small
				if (pmeta.getParameterType(i+1) == Types.BOOLEAN)
					ps.setBoolean(i+1, (paramVal == 1));
				else
					ps.setShort(i+1, paramVal);
				break;
			}
			case  DRDAConstants.DRDA_TYPE_NINTEGER:
			{
				int paramVal = reader.readInt(getByteOrder());
				if (this.trace)
					trace("integer parameter value is: "+paramVal);
				ps.setInt(i+1, paramVal);
				break;
			}
			case DRDAConstants.DRDA_TYPE_NINTEGER8:
			{
				long paramVal = reader.readLong(getByteOrder());
				if (this.trace)
					trace("parameter value is: "+paramVal);
				ps.setLong(i+1, paramVal);
				break;
			}
			case DRDAConstants.DRDA_TYPE_NFLOAT4:
			{
				float paramVal = reader.readFloat(getByteOrder());
				if (this.trace) 
					trace("parameter value is: "+paramVal);
				ps.setFloat(i+1, paramVal);
				break;
			}
			case DRDAConstants.DRDA_TYPE_NFLOAT8:
			{
				double paramVal = reader.readDouble(getByteOrder());
				if (this.trace) 
					trace("nfloat8 parameter value is: "+paramVal);
				ps.setDouble(i+1, paramVal);
				break;
			}
			case DRDAConstants.DRDA_TYPE_NDECIMAL:
			{
				int precision = (paramLenNumBytes >> 8) & 0xff;
				int scale = paramLenNumBytes & 0xff;
				BigDecimal paramVal = reader.readBigDecimal(precision, scale);
				if (this.trace)
					trace("ndecimal parameter value is: "+paramVal);
				ps.setBigDecimal(i+1, paramVal);
				break;
			}
			case DRDAConstants.DRDA_TYPE_NDATE:
			{
				String paramVal = reader.readStringData(10).trim();  //parameter may be char value
				if (this.trace) 
					trace("ndate parameter value is: \""+paramVal+"\"");
				try {
					ps.setDate(i+1, java.sql.Date.valueOf(paramVal));
				} catch (java.lang.IllegalArgumentException e) {
					// Just use SQLSTATE as message since, if user wants to
					// retrieve it, the message will be looked up by the
					// sqlcamessage() proc, which will get the localized
					// message based on SQLSTATE, and will ignore the
					// the message we use here...
					throw new SQLException(SQLState.LANG_DATE_SYNTAX_EXCEPTION,
						SQLState.LANG_DATE_SYNTAX_EXCEPTION.substring(0,5));
				}
				break;
			}
			case DRDAConstants.DRDA_TYPE_NTIME:
			{
				String paramVal = reader.readStringData(8).trim();  //parameter may be char value
				if (this.trace) 
					trace("ntime parameter value is: "+paramVal);
				try {
					ps.setTime(i+1, java.sql.Time.valueOf(paramVal));
				} catch (java.lang.IllegalArgumentException e) {
					throw new SQLException(SQLState.LANG_DATE_SYNTAX_EXCEPTION,
						SQLState.LANG_DATE_SYNTAX_EXCEPTION.substring(0,5));
				}
				break;
			}
			case DRDAConstants.DRDA_TYPE_NTIMESTAMP:
			{
				// JCC represents ts in a slightly different format than Java standard, so
				// we do the conversion to Java standard here.
				String paramVal = reader.readStringData(26).trim();  //parameter may be char value
				if (this.trace)
					trace("ntimestamp parameter value is: "+paramVal);
				try {
					String tsString = paramVal.substring(0,10) + " " +
						paramVal.substring(11,19).replace('.', ':') +
						paramVal.substring(19);
					if (this.trace)
						trace("tsString is: "+tsString);
					ps.setTimestamp(i+1, java.sql.Timestamp.valueOf(tsString));
				} catch (java.lang.IllegalArgumentException e1) {
				// thrown by Timestamp.valueOf(...) for bad syntax...
					throw new SQLException(SQLState.LANG_DATE_SYNTAX_EXCEPTION,
						SQLState.LANG_DATE_SYNTAX_EXCEPTION.substring(0,5));
				} catch (java.lang.StringIndexOutOfBoundsException e2) {
				// can be thrown by substring(...) if syntax is invalid...
					throw new SQLException(SQLState.LANG_DATE_SYNTAX_EXCEPTION,
						SQLState.LANG_DATE_SYNTAX_EXCEPTION.substring(0,5));
				}
				break;
			}
			case DRDAConstants.DRDA_TYPE_NCHAR:
			case DRDAConstants.DRDA_TYPE_NVARCHAR:
			case DRDAConstants.DRDA_TYPE_NLONG:
// GemStone changes BEGIN
			case DRDAConstants.DRDA_TYPE_NMIX:
// GemStone changes END
			case DRDAConstants.DRDA_TYPE_NVARMIX:
			case DRDAConstants.DRDA_TYPE_NLONGMIX:
			{
				String paramVal = reader.readLDStringData(stmt.ccsidMBCEncoding);
				if (this.trace)
					trace("for type " + drdaType + " with encoding " + stmt.ccsidMBCEncoding +
					    " char/varchar parameter value is: "+paramVal);
				ps.setString(i+1, paramVal);
				break;
			}
			case  DRDAConstants.DRDA_TYPE_NFIXBYTE:
			{
				byte[] paramVal = reader.readBytes();
				if (this.trace) 
					trace("fix bytes parameter value is: "+ convertToHexString(paramVal));
				ps.setBytes(i+1, paramVal);
				break;
			}
			case DRDAConstants.DRDA_TYPE_NVARBYTE:
			case DRDAConstants.DRDA_TYPE_NLONGVARBYTE:
			{
				int length = reader.readNetworkShort();	//protocol control data always follows big endian
				if (this.trace)
					trace("===== binary param length is: " + length);
				byte[] paramVal = reader.readBytes(length);
				ps.setBytes(i+1, paramVal);
				break;
			}
			case DRDAConstants.DRDA_TYPE_NUDT:
			{
                Object paramVal = readUDT();
				ps.setObject(i+1, paramVal);
				break;
			}
			case DRDAConstants.DRDA_TYPE_NLOBBYTES:
			case DRDAConstants.DRDA_TYPE_NLOBCMIXED:
			case DRDAConstants.DRDA_TYPE_NLOBCSBCS:
			case DRDAConstants.DRDA_TYPE_NLOBCDBCS:
			 {
				 long length = readLobLength(paramLenNumBytes);
				 if (length != 0) //can be -1 for CLI if "data at exec" mode, see clifp/exec test
				 {
					stmt.addExtPosition(i);
				 }
				 else   /* empty */
				 {
					if (drdaType == DRDAConstants.DRDA_TYPE_NLOBBYTES)
						ps.setBytes(i+1, new byte[0]);
					else
						ps.setString(i+1, "");
				 }
				 break;
			 }
                        case DRDAConstants.DRDA_TYPE_NLOBLOC:
                        {
                            //read the locator value
                            int paramVal = reader.readInt(getByteOrder());
                            
                            if (this.trace)
                                trace("locator value is: "+paramVal);
                            
                            //Map the locator value to the Blob object in the
                            //Hash map.
                            java.sql.Blob blobFromLocator = (java.sql.Blob)
                            database.getConnection().getLOBMapping(paramVal);
                            
                            //set the PreparedStatement parameter to the mapped
                            //Blob object.
                            ps.setBlob(i+1, blobFromLocator);
                            break;
                        }
                        case DRDAConstants.DRDA_TYPE_NCLOBLOC:
                        {
                            //read the locator value.
                            int paramVal = reader.readInt(getByteOrder());
                            
                            if (this.trace)
                                trace("locator value is: "+paramVal);
                            
                            //Map the locator value to the Clob object in the
                            //Hash Map.
                            java.sql.Clob clobFromLocator = (java.sql.Clob)
                            database.getConnection().getLOBMapping(paramVal);
                            
                            //set the PreparedStatement parameter to the mapped
                            //Clob object.
                            ps.setClob(i+1, clobFromLocator);
                            break;
                        }
			default:
				{
				String paramVal = reader.readLDStringData(stmt.ccsidMBCEncoding);
				if (this.trace) 
					trace("default type parameter value is: "+paramVal);
				ps.setObject(i+1, paramVal);
			}
		}
	}

// GemStone changes BEGIN
    private ClassFactory cf = null;
// GemStone changes END
    /** Read a UDT from the stream */
    private Object readUDT() throws DRDAProtocolException
    {
        int length = reader.readNetworkShort();	//protocol control data always follows big endian
        if (SanityManager.DEBUG) { trace("===== udt param length is: " + length); }
        byte[] bytes = reader.readBytes(length);
        
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream( bytes );
// GemStone changes BEGIN
            if (this.cf == null) {
              this.cf = Misc.getMemStore().getDatabase().getClassFactory();
            }
            ApplicationObjectInputStream ois = new ApplicationObjectInputStream(
                bais, this.cf);
            /* (original code)
            ObjectInputStream ois = new ObjectInputStream( bais );
            */
// GemStone changes END

            return ois.readObject();
        }
        catch (Exception e)
        {
            markCommunicationsFailure
                ( e,"DRDAConnThread.readUDT()", "", e.getMessage(), "*" );
            return null;
        }
    }


	private long readLobLength(int extLenIndicator) 
		throws DRDAProtocolException
	{
		switch (extLenIndicator)
		{
			case 0x8002:
				return (long) reader.readNetworkShort();
			case 0x8004:
				return (long) reader.readNetworkInt();
			case 0x8006:
				return (long) reader.readNetworkSixByteLong();
			case 0x8008:
				return (long) reader.readNetworkLong();
			default:
				throwSyntaxrm(CodePoint.SYNERRCD_INCORRECT_EXTENDED_LEN, extLenIndicator);
				return 0L;
		}
		


	}
	

	private void readAndSetAllExtParams(final DRDAStatement stmt, final boolean streamLOB) 
		throws SQLException, DRDAProtocolException
	{
		final int numExt = stmt.getExtPositionCount();
		for (int i = 0; i < numExt; i++)
					{
						int paramPos = stmt.getExtPosition(i);
						// Only the last EXTDTA is streamed.  This is because all of 
						// the parameters have to be set before execution and are 
						// consecutive in the network server stream, so only the last
						// one can be streamed.
						final boolean doStreamLOB = (streamLOB && i == numExt -1);
						readAndSetExtParam(paramPos,
										   stmt,
										   stmt.getParamDRDAType(paramPos+1),
										   stmt.getParamLen(paramPos+1),
										   doStreamLOB);
						// Each extdta in it's own dss
						if (i < numExt -1)
						{
							correlationID = reader.readDssHeader();
							int codePoint = reader.readLengthAndCodePoint( true );
						}
					}

	}
	

	/**
	 * Read different types of input parameters and set them in PreparedStatement
	 * @param i			index of the parameter
	 * @param stmt			associated ps
	 * @param drdaType	drda type of the parameter
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void readAndSetExtParam( int i, DRDAStatement stmt,
									 int drdaType, int extLen, boolean streamLOB)
				throws DRDAProtocolException, SQLException
		{
			PreparedStatement ps = stmt.getPreparedStatement();
			drdaType = (drdaType & 0x000000ff); // need unsigned value
			boolean checkNullability = false;
			if (sqlamLevel >= MGRLVL_7 &&
				FdocaConstants.isNullable(drdaType))
				checkNullability = true;

			try {	
				final byte[] paramBytes;
				final String paramString;
				
				switch (drdaType)
				{
					case  DRDAConstants.DRDA_TYPE_LOBBYTES:
					case  DRDAConstants.DRDA_TYPE_NLOBBYTES:
						paramString = "";
						final boolean useSetBinaryStream = 
							stmt.getParameterMetaData().getParameterType(i+1)==Types.BLOB;
						
						if (streamLOB && useSetBinaryStream) {
							paramBytes = null;
							final EXTDTAReaderInputStream stream = 
								reader.getEXTDTAReaderInputStream(checkNullability);
                            // Save the streamed parameter so we can drain it if it does not get used
                            // by embedded when the statement is executed. DERBY-3085
                            stmt.setStreamedParameter(stream);
                            if( stream instanceof StandardEXTDTAReaderInputStream ){
                                
                                final StandardEXTDTAReaderInputStream stdeis = 
                                    (StandardEXTDTAReaderInputStream) stream ;
                                ps.setBinaryStream( i + 1, 
                                                    stdeis, 
                                                    (int) stdeis.getLength() );
                                
                            } else if( stream instanceof LayerBStreamedEXTDTAReaderInputStream ) {
                                
// GemStone changes BEGIN
                              ps.setBinaryStream(i + 1, stream);
                              /* (original code)
                                ( ( EnginePreparedStatement ) ps).setBinaryStream( i + 1, 
                                                                                   stream);
                              */
// GemStone changes END
                                
							} else if( stream == null ){
                                ps.setBytes(i+1, null);
                                
                            } else {
                                throw new IllegalStateException();
                            }
							
							if (this.trace) {
								if (stream==null) {
									trace("parameter value : NULL");
								} else {
									trace("parameter value will be streamed");
								}
							}
						} else {
                            final EXTDTAReaderInputStream stream = 
								reader.getEXTDTAReaderInputStream(checkNullability);
							
                            if ( stream == null ) {
								
                                ps.setBytes(i+1, 
                                            null );
                                
                                if (this.trace) {
									trace("parameter value : NULL");
                                }
                                
							} else {

                                ByteArrayInputStream bais = 
                                    convertAsByteArrayInputStream( stream );
                                
                                if (this.trace) {
									trace("parameter value is a LOB with length:" +
										  bais.available() );
								}
                                
								ps.setBinaryStream(i+1, 
                                                   bais,
												   bais.available() );
                                
							}
							
						}
						break;
					case DRDAConstants.DRDA_TYPE_LOBCSBCS:
					case DRDAConstants.DRDA_TYPE_NLOBCSBCS:
                        
                        setAsCharacterStream(stmt,
                                             i,
                                             checkNullability,
                                             reader,
                                             streamLOB,
                                             stmt.ccsidSBCEncoding );

						break;
					case DRDAConstants.DRDA_TYPE_LOBCDBCS:
					case DRDAConstants.DRDA_TYPE_NLOBCDBCS:
                        
                        setAsCharacterStream(stmt,
                                             i,
                                             checkNullability,
                                             reader,
                                             streamLOB,
                                             stmt.ccsidDBCEncoding);
                        
						break;
					case DRDAConstants.DRDA_TYPE_LOBCMIXED:
					case DRDAConstants.DRDA_TYPE_NLOBCMIXED:

                        setAsCharacterStream(stmt,
                                             i,
                                             checkNullability,
                                             reader,
                                             streamLOB,
                                             stmt.ccsidMBCEncoding);
                        
						break;
					default:
						paramBytes = null;
						paramString = "";

						invalidValue(drdaType);
				}
			     
			}
			catch (java.io.UnsupportedEncodingException e) {
				throw new SQLException (e.getMessage());
                
			} catch( IOException e ){
                throw new SQLException ( e.getMessage() );
                
            }
		}

	/**
	 * Parse EXCSQLIMM - Execute Immediate Statement
	 * Instance Variables
	 *  RDBNAM - relational database name - optional
	 *  PKGNAMCSN - RDB Package Name, Consistency Token and Section Number - required
	 *  RDBCMTOK - RDB Commit Allowed - optional
	 *  MONITOR - Monitor Events - optional
	 *
	 * Command Objects
	 *  TYPDEFNAM - Data Type Definition Name - optional
	 *  TYPDEFOVR - TYPDEF Overrides -optional
	 *  SQLSTT - SQL Statement -required
	 *
	 * @return update count
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private int parseEXCSQLIMM() throws DRDAProtocolException,SQLException
	{
		int codePoint;
		reader.markCollection();
		Pkgnamcsn pkgnamcsn = null;
		codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch (codePoint)
			{
				// optional
				case CodePoint.RDBNAM:
					setDatabase(CodePoint.EXCSQLIMM);
					break;
				// required
				case CodePoint.PKGNAMCSN:
				    pkgnamcsn = parsePKGNAMCSN();
					break;
				case CodePoint.RDBCMTOK:
					parseRDBCMTOK();
					break;
				//optional
				case CodePoint.MONITOR:
					parseMONITOR();
					break;
				default:
					invalidCodePoint(codePoint);

			}
			codePoint = reader.getCodePoint();
		}
		DRDAStatement drdaStmt =  database.getDefaultStatement(pkgnamcsn);
		// initialize statement for reuse
		drdaStmt.initialize();
		String sqlStmt = parseEXECSQLIMMobjects();
        Statement statement = drdaStmt.getStatement();
        statement.clearWarnings();
        if (pendingStatementTimeout >= 0) {
            statement.setQueryTimeout(pendingStatementTimeout);
            pendingStatementTimeout = -1;
        }
// GemStone changes BEGIN
		if (this.pendingSetSchema != null) {
		  drdaStmt.setSchema(this.pendingSetSchema);
		  this.pendingSetSchema = null;
		}
		drdaStmt.sqlText = sqlStmt;
		int updCount;
		try {
		  drdaStmt.setStatus("EXECUTING STATEMENT");
		  updCount = statement.executeUpdate(sqlStmt);
		  drdaStmt.setStatus("DONE");
		} finally {
		  // remove posdup flag if present
		  this.database.getConnection().setPossibleDuplicate(false);
		}
                /* (original code)
                int updCount = statement.executeUpdate(sqlStmt);
                */
// GemStone changes END
		return updCount;
	}

	/**
	 * Parse EXCSQLSET - Execute Set SQL Environment
	 * Instance Variables
	 *  RDBNAM - relational database name - optional
	 *  PKGNAMCT - RDB Package Name, Consistency Token  - optional
	 *  MONITOR - Monitor Events - optional
	 *
	 * Command Objects
	 *  TYPDEFNAM - Data Type Definition Name - required
	 *  TYPDEFOVR - TYPDEF Overrides - required
	 *  SQLSTT - SQL Statement - required (at least one; may be more)
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private boolean parseEXCSQLSET() throws DRDAProtocolException,SQLException
	{

		int codePoint;
		reader.markCollection();


		codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch (codePoint)
			{
				// optional
				case CodePoint.RDBNAM:
					setDatabase(CodePoint.EXCSQLSET);
					break;
				// optional
				case CodePoint.PKGNAMCT:
					// we are going to ignore this for EXCSQLSET
					// since we are just going to reuse an existing statement
					String pkgnamct = parsePKGNAMCT();
					break;
				// optional
				case CodePoint.MONITOR:
					parseMONITOR();
					break;
				// required
				case CodePoint.PKGNAMCSN:
					// we are going to ignore this for EXCSQLSET.
					// since we are just going to reuse an existing statement.
					// NOTE: This codepoint is not in the DDM spec for 'EXCSQLSET',
					// but since it DOES get sent by jcc1.2, we have to have
					// a case for it...
					Pkgnamcsn pkgnamcsn = parsePKGNAMCSN();
					break;
				default:
					invalidCodePoint(codePoint);

			}
			codePoint = reader.getCodePoint();
		}

// GemStone changes BEGIN
		return parseEXCSQLSETobjects();
		/* (original code)
		parseEXCSQLSETobjects();
		return true;
		*/
// GemStone changes END
	}

	/**
	 * Parse EXCSQLIMM objects
	 * Objects
	 *  TYPDEFNAM - Data type definition name - optional
	 *  TYPDEFOVR - Type defintion overrides
	 *  SQLSTT - SQL Statement required
	 *
	 * If TYPDEFNAM and TYPDEFOVR are supplied, they apply to the objects
	 * sent with the statement.  Once the statement is over, the default values
	 * sent in the ACCRDB are once again in effect.  If no values are supplied,
	 * the values sent in the ACCRDB are used.
	 * Objects may follow in one DSS or in several DSS chained together.
	 * 
	 * @return SQL Statement
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private String parseEXECSQLIMMobjects() throws DRDAProtocolException, SQLException
	{
		String sqlStmt = null;
		int codePoint;
		DRDAStatement stmt = database.getDefaultStatement();
		do
		{
			correlationID = reader.readDssHeader();
			while (reader.moreDssData())
			{
				codePoint = reader.readLengthAndCodePoint( false );
				switch(codePoint)
				{
					// optional
					case CodePoint.TYPDEFNAM:
						setStmtOrDbByteOrder(false, stmt, parseTYPDEFNAM());
						break;
					// optional
					case CodePoint.TYPDEFOVR:
						parseTYPDEFOVR(stmt);
						break;
					// required
					case CodePoint.SQLSTT:
						sqlStmt = parseEncodedString();
						if (this.trace) 
							trace("sqlStmt = " + sqlStmt);
						break;
					default:
						invalidCodePoint(codePoint);
				}
			}
		} while (reader.isChainedWithSameID());

		// SQLSTT is required
		if (sqlStmt == null)
			missingCodePoint(CodePoint.SQLSTT);
		return sqlStmt;
	}

	/**
	 * Parse EXCSQLSET objects
	 * Objects
	 *  TYPDEFNAM - Data type definition name - optional
	 *  TYPDEFOVR - Type defintion overrides - optional
	 *  SQLSTT - SQL Statement - required (a list of at least one)
	 *
	 * Objects may follow in one DSS or in several DSS chained together.
	 * 
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
// GemStone changes BEGIN
	private boolean parseEXCSQLSETobjects()
	/* (original code)
	private void parseEXCSQLSETobjects()
	*/
// GemStone changes END
		throws DRDAProtocolException, SQLException
	{

		boolean gotSqlStt = false;
		boolean hadUnrecognizedStmt = false;

// GemStone changes BEGIN
		boolean result = true;
// GemStone changes END
		String sqlStmt = null;
		int codePoint;
		DRDAStatement drdaStmt = database.getDefaultStatement();
		drdaStmt.initialize();

		do
		{
			correlationID = reader.readDssHeader();
			while (reader.moreDssData())
			{

				codePoint = reader.readLengthAndCodePoint( false );

// GemStone changes BEGIN
				result = true;
// GemStone changes END
				switch(codePoint)
				{
					// optional
					case CodePoint.TYPDEFNAM:
						setStmtOrDbByteOrder(false, drdaStmt, parseTYPDEFNAM());
						break;
					// optional
					case CodePoint.TYPDEFOVR:
						parseTYPDEFOVR(drdaStmt);
						break;
					// required
					case CodePoint.SQLSTT:
						sqlStmt = parseEncodedString();
						if (sqlStmt != null)
						// then we have at least one SQL Statement.
							gotSqlStt = true;

                        if (sqlStmt.startsWith(TIMEOUT_STATEMENT)) {
                            String timeoutString = sqlStmt.substring(TIMEOUT_STATEMENT.length());
                            pendingStatementTimeout = Integer.valueOf(timeoutString).intValue();
                            break;
                        }
// GemStone changes BEGIN
                        else if (sqlStmt.startsWith(SET_SCHEMA_STATEMENT)) {
                          this.pendingSetSchema = sqlStmt.substring(
                              SET_SCHEMA_STATEMENT.length());
                          // don't write back anything for this case
                          result = false;
                          break;
                        }
// GemStone changes END

						if (canIgnoreStmt(sqlStmt)) {
						// We _know_ Derby doesn't recognize this
						// statement; don't bother trying to execute it.
						// NOTE: at time of writing, this only applies
						// to "SET CLIENT" commands, and it was decided
						// that throwing a Warning for these commands
						// would confuse people, so even though the DDM
						// spec says to do so, we choose not to (but
						// only for SET CLIENT cases).  If this changes
						// at some point in the future, simply remove
						// the follwing line; we will then throw a
						// warning.
//							hadUnrecognizedStmt = true;
							break;
						}

						if (this.trace) 
							trace("sqlStmt = " + sqlStmt);

						// initialize statement for reuse
						drdaStmt.initialize();
						drdaStmt.getStatement().clearWarnings();
						try {
							drdaStmt.getStatement().executeUpdate(sqlStmt);
						} catch (SQLException e) {

							// if this is a syntax error, then we take it
							// to mean that the given SET statement is not
							// recognized; take note (so we can throw a
							// warning later), but don't interfere otherwise.
							if (e.getSQLState().equals(SYNTAX_ERR))
								hadUnrecognizedStmt = true;
							else
							// something else; assume it's serious.
								throw e;
						}
// GemStone changes BEGIN
						finally {
						  // remove posdup flag if present
						  this.database.getConnection()
						    .setPossibleDuplicate(false);
						}
// GemStone changes END
						break;
					default:
						invalidCodePoint(codePoint);
				}
			}

		} while (reader.isChainedWithSameID());

		// SQLSTT is required.
		if (!gotSqlStt)
			missingCodePoint(CodePoint.SQLSTT);

		// Now that we've processed all SET statements (assuming no
		// severe exceptions), check for warnings and, if we had any,
		// note this in the SQLCARD reply object (but DON'T cause the
		// EXCSQLSET statement to fail).
		if (hadUnrecognizedStmt) {
			SQLWarning warn = new SQLWarning("One or more SET statements " +
				"not recognized.", "01000");
			throw warn;
		} // end if.

// GemStone changes BEGIN
		return result;
		/* (original code)
		return;
		*/
// GemStone changes END
	}

	private boolean canIgnoreStmt(String stmt)
	{
		if (stmt.indexOf("SET CLIENT") != -1)
			return true;
		return false;
	}

	/**
	 * Write RDBUPDRM
	 * Instance variables
	 *  SVRCOD - Severity code - Information only - required
	 *  RDBNAM - Relational database name -required
	 *  SRVDGN - Server Diagnostic Information -optional
	 *
	 * @exception DRDAProtocolException
	 */
	private void writeRDBUPDRM() throws DRDAProtocolException
	{ 
		database.RDBUPDRM_sent = true;
		writer.createDssReply();
		writer.startDdm(CodePoint.RDBUPDRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, CodePoint.SVRCOD_INFO);
		writeRDBNAM(database.dbName);
		writer.endDdmAndDss();
	}


	private String parsePKGNAMCT() throws DRDAProtocolException
	{
		reader.skipBytes();
		return null;
	}

	/**
	 * Parse PKGNAMCSN - RDB Package Name, Consistency Token, and Section Number
	 * Instance Variables
	 *   NAMESYMDR - database name - not validated
	 *   RDBCOLID - RDB Collection Identifier
	 *   PKGID - RDB Package Identifier
	 *   PKGCNSTKN - RDB Package Consistency Token
	 *   PKGSN - RDB Package Section Number
	 *
	 * @return <code>Pkgnamcsn</code> value
	 * @throws DRDAProtocolException
	 */
	private Pkgnamcsn parsePKGNAMCSN() throws DRDAProtocolException
	{
		if (reader.getDdmLength() == CodePoint.PKGNAMCSN_LEN)
		{
			// This is a scalar object with the following fields
			reader.readString(rdbnam, CodePoint.RDBNAM_LEN, true);
			if (this.trace) 
// GemStone changes BEGIN
			  // always use "gemfirexd" as the DB name in GemFireXD
			  trace("rdbnam = " + rdbnam + "; actual = "
			      + Attribute.GFXD_DBNAME);
			if (!Attribute.GFXD_DBNAME.equals(rdbnam.toString())) {
			  rdbnam.setString(Attribute.GFXD_DBNAME);
			}
			/* (original code)
				trace("rdbnam = " + rdbnam);
			*/
// GemStone changes END
                        
            // A check that the rdbnam field corresponds to a database
            // specified in a ACCRDB term.
            // The check is not performed if the client is DNC_CLIENT
            // with version before 10.3.0 because these clients
            // are broken and send incorrect database name
            // if multiple connections to different databases
            // are created
                        
            // This check was added because of DERBY-1434
                        
            // check the client version first
            if ( appRequester.getClientType() != AppRequester.DNC_CLIENT
                 || appRequester.greaterThanOrEqualTo(10,3,0) ) {
                // check the database name
                if (!rdbnam.toString().equals(database.dbName))
                    rdbnamMismatch(CodePoint.PKGNAMCSN);
            }

			reader.readString(rdbcolid, CodePoint.RDBCOLID_LEN, true);
			if (this.trace) 
				trace("rdbcolid = " + rdbcolid);

			reader.readString(pkgid, CodePoint.PKGID_LEN, true);
			if (this.trace)
				trace("pkgid = " + pkgid);

			// we need to use the same UCS2 encoding, as this can be
			// bounced back to jcc (or keep the byte array)
			reader.readString(pkgcnstkn, CodePoint.PKGCNSTKN_LEN, false);
			if (this.trace) 
				trace("pkgcnstkn = " + pkgcnstkn);

			pkgsn = reader.readNetworkShort();
			if (this.trace)
				trace("pkgsn = " + pkgsn);
		}
		else	// extended format
		{
			int length = reader.readNetworkShort();
			if (length < CodePoint.RDBNAM_LEN || length > CodePoint.MAX_NAME)
				badObjectLength(CodePoint.RDBNAM);
			reader.readString(rdbnam, length, true);
			if (this.trace)
// GemStone changes BEGIN
			  // always use "gemfirexd" as the DB name in GemFireXD
			  trace("rdbnam = " + rdbnam + "; actual = "
			      + Attribute.GFXD_DBNAME);
			if (!Attribute.GFXD_DBNAME.equals(rdbnam.toString())) {
			  rdbnam.setString(Attribute.GFXD_DBNAME);
			}
			/* (original code)
				trace("rdbnam = " + rdbnam);
			*/
// GemStone changes END

            // A check that the rdbnam field corresponds to a database
            // specified in a ACCRDB term.
            // The check is not performed if the client is DNC_CLIENT
            // with version before 10.3.0 because these clients
            // are broken and send incorrect database name
            // if multiple connections to different databases
            // are created
                        
            // This check was added because of DERBY-1434
                        
            // check the client version first
            if ( appRequester.getClientType() != AppRequester.DNC_CLIENT
                 || appRequester.greaterThanOrEqualTo(10,3,0) ) {
                // check the database name
                if (!rdbnam.toString().equals(database.dbName))
                    rdbnamMismatch(CodePoint.PKGNAMCSN);
            }

			//RDBCOLID can be variable length in this format
			length = reader.readNetworkShort();
			reader.readString(rdbcolid, length, true);
			if (this.trace) 
				trace("rdbcolid = " + rdbcolid);

			length = reader.readNetworkShort();
			if (length != CodePoint.PKGID_LEN)
				badObjectLength(CodePoint.PKGID);
			reader.readString(pkgid, CodePoint.PKGID_LEN, true);
			if (this.trace) 
				trace("pkgid = " + pkgid);

			reader.readString(pkgcnstkn, CodePoint.PKGCNSTKN_LEN, false);
			if (this.trace) 
				trace("pkgcnstkn = " + pkgcnstkn);

			pkgsn = reader.readNetworkShort();
			if (this.trace) 
				trace("pkgsn = " + pkgsn);
		}
// GemStone changes BEGIN
                        if (Version.GFXD_20.compareTo(this.gfxdClientVersion) <= 0) {
                          int execSeq = reader.readNetworkShort();
                          // TODO: KN add logging
//                          System.out.println("KN: int read = " + execSeq + "  DRDAConnThread="
//                              + System.identityHashCode(this));
                          setExecutionSequence(execSeq);
                        }
// GemStone changes END
		// In most cases, the pkgnamcsn object is equal to the
		// previously returned object. To avoid allocation of a new
		// object in these cases, we first check to see if the old
		// object can be reused.
		if ((prevPkgnamcsn == null) ||
			rdbnam.wasModified() ||
			rdbcolid.wasModified() ||
			pkgid.wasModified() ||
			pkgcnstkn.wasModified() ||
			(prevPkgnamcsn.getPkgsn() != pkgsn))
		{
			// The byte array returned by pkgcnstkn.getBytes() might
			// be modified by DDMReader.readString() later, so we have
			// to create a copy of the array.
			byte[] token = new byte[pkgcnstkn.length()];
			System.arraycopy(pkgcnstkn.getBytes(), 0, token, 0, token.length);

			prevPkgnamcsn =
				new Pkgnamcsn(rdbnam.toString(), rdbcolid.toString(),
							  pkgid.toString(), pkgsn,
							  new ConsistencyToken(token));
		}

		return prevPkgnamcsn;
	}

  // GemStone changes BEGIN
  private void setExecutionSequence(int execSeq) {
    assert this.database != null : "database expected to be non null";
    EngineConnection conn = this.database.getConnection();
    conn.setExecutionSequence(execSeq);
  }
  // GemStone changes BEGIN

  /**
	 * Parse SQLSTT Dss
	 * @exception DRDAProtocolException
	 */
	private String parseSQLSTTDss() throws DRDAProtocolException
	{
		correlationID = reader.readDssHeader();
		int codePoint = reader.readLengthAndCodePoint( false );
		String strVal = parseEncodedString();
		if (this.trace) 
			trace("SQL Statement = " + strVal);
		return strVal;
	}

	/**
	 * Parse an encoded data string from the Application Requester
	 *
	 * @return string value
	 * @exception DRDAProtocolException
	 */
	private String parseEncodedString() throws DRDAProtocolException
	{
		if (sqlamLevel < 7)
			return parseVCMorVCS();
		else
			return parseNOCMorNOCS();
	}

	/**
	 * Parse variable character mixed byte or variable character single byte
	 * Format
	 *  I2 - VCM Length
	 *  N bytes - VCM value
	 *  I2 - VCS Length
	 *  N bytes - VCS value 
	 * Only 1 of VCM length or VCS length can be non-zero
	 *
	 * @return string value
	 */
	private String parseVCMorVCS() throws DRDAProtocolException
	{
		String strVal = null;
		int vcm_length = reader.readNetworkShort();
		if (vcm_length > 0)
			strVal = parseCcsidMBC(vcm_length);
		int vcs_length = reader.readNetworkShort();
		if (vcs_length > 0)
		{
			if (strVal != null)
				agentError ("Both VCM and VCS have lengths > 0");
			strVal = parseCcsidSBC(vcs_length);
		}
		return strVal;
	}
	/**
	 * Parse nullable character mixed byte or nullable character single byte
	 * Format
	 *  1 byte - null indicator
	 *  I4 - mixed character length
	 *  N bytes - mixed character string
	 *  1 byte - null indicator
	 *  I4 - single character length
	 *  N bytes - single character length string
	 *
	 * @return string value
	 * @exception DRDAProtocolException
	 */
	private String parseNOCMorNOCS() throws DRDAProtocolException
	{
		byte nocm_nullByte = reader.readByte();
		String strVal = null;
		int length;
		if (nocm_nullByte != NULL_VALUE)
		{
			length = reader.readNetworkInt();
			strVal = parseCcsidMBC(length);
		}
		byte nocs_nullByte = reader.readByte();
		if (nocs_nullByte != NULL_VALUE)
		{
			if (strVal != null)
				agentError("Both CM and CS are non null");
			length = reader.readNetworkInt();
			strVal = parseCcsidSBC(length);
		}
		return strVal;
	}
	/**
	 * Parse mixed character string
	 * 
	 * @return string value
	 * @exception DRDAProtocolException
	 */
	private String parseCcsidMBC(int length) throws DRDAProtocolException
	{
		String strVal = null;
		DRDAStatement  currentStatement;

		currentStatement = database.getCurrentStatement();
		if (currentStatement == null)
		{
			currentStatement = database.getDefaultStatement();
			currentStatement.initialize();
		}
		String ccsidMBCEncoding = currentStatement.ccsidMBCEncoding;

		if (length == 0)
			return null;
		byte [] byteStr = reader.readBytes(length);
		if (ccsidMBCEncoding != null)
		{
			try {
				strVal = new String(byteStr, 0, length, ccsidMBCEncoding);
			} catch (UnsupportedEncodingException e) {
				agentError("Unsupported encoding " + ccsidMBCEncoding +
					"in parseCcsidMBC");
			}
		}
		else
			agentError("Attempt to decode mixed byte string without CCSID being set");
		return strVal;
	}
	/**
	 * Parse single byte character string
	 * 
	 * @return string value
	 * @exception DRDAProtocolException
	 */
	private String parseCcsidSBC(int length) throws DRDAProtocolException
	{
		String strVal = null;
		DRDAStatement  currentStatement;
		
		currentStatement = database.getCurrentStatement();
		if (currentStatement == null)
		{
			currentStatement = database.getDefaultStatement();
			currentStatement.initialize();
		}
		String ccsidSBCEncoding = currentStatement.ccsidSBCEncoding;
		System.out.println("ccsidSBCEncoding - " + ccsidSBCEncoding);
		
		if (length == 0)
			return null;
		byte [] byteStr = reader.readBytes(length);
		if (ccsidSBCEncoding != null)
		{
			try {
				strVal = new String(byteStr, 0, length, ccsidSBCEncoding);
			} catch (UnsupportedEncodingException e) {
				agentError("Unsupported encoding " + ccsidSBCEncoding +
					"in parseCcsidSBC");
			}
		}
		else
			agentError("Attempt to decode single byte string without CCSID being set");
		return strVal;
	}
	/**
	 * Parse CLSQRY
	 * Instance Variables
	 *  RDBNAM - relational database name - optional
	 *  PKGNAMCSN - RDB Package Name, Consistency Token and Section Number - required
	 *  QRYINSID - Query Instance Identifier - required - level 7
	 *  MONITOR - Monitor events - optional.
	 *
	 * @return DRDAstatement being closed
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private DRDAStatement parseCLSQRY() throws DRDAProtocolException, SQLException
	{
		Pkgnamcsn pkgnamcsn = null;
		reader.markCollection();
		long qryinsid = 0;
		boolean gotQryinsid = false;

		int codePoint = reader.getCodePoint();
		while (codePoint != -1)
		{
			switch (codePoint)
			{
				// optional
				case CodePoint.RDBNAM:
					setDatabase(CodePoint.CLSQRY);
					break;
					// required
				case CodePoint.PKGNAMCSN:
					pkgnamcsn = parsePKGNAMCSN();
					break;
				case CodePoint.QRYINSID:
					qryinsid = reader.readNetworkLong();
					gotQryinsid = true;
					break;
				// optional
				case CodePoint.MONITOR:
					parseMONITOR();
					break;
				default:
					invalidCodePoint(codePoint);
			}
			codePoint = reader.getCodePoint();
		}
		// check for required variables
		if (pkgnamcsn == null) {
			missingCodePoint(CodePoint.PKGNAMCSN);
			// never reached
			return null;
		}
		if (sqlamLevel >= MGRLVL_7 && !gotQryinsid)
			missingCodePoint(CodePoint.QRYINSID);

		DRDAStatement stmt = database.getDRDAStatement(pkgnamcsn);
		if (stmt == null)
		{
			//XXX should really throw a SQL Exception here
			invalidValue(CodePoint.PKGNAMCSN);
			// never reached
			return null;
		}

		if (stmt.wasExplicitlyClosed())
		{
			// JCC still sends a CLSQRY even though we have
			// implicitly closed the resultSet.
			// Then complains if we send the writeQRYNOPRM
			// So for now don't send it
			// Also metadata calls seem to get bound to the same
			// PGKNAMCSN, so even for explicit closes we have
			// to ignore.
			//writeQRYNOPRM(CodePoint.SVRCOD_ERROR);
			pkgnamcsn = null;
		}

		stmt.CLSQRY();
	   
		return stmt;
	}

	/**
	 * Parse MONITOR
	 * DRDA spec says this is optional.  Since we
	 * don't currently support it, we just ignore.
	 */
	private void parseMONITOR() 
		throws DRDAProtocolException
	{

		// Just ignore it.
		reader.skipBytes();
		return;

	}
	
	private void writeSQLCARDs(SQLException e, int updateCount)
									throws DRDAProtocolException
	{
		writeSQLCARDs(e, updateCount, false);
	}

	private void writeSQLCARDs(SQLException e, int updateCount, boolean sendSQLERRRM)
									throws DRDAProtocolException
	{

		int severity = CodePoint.SVRCOD_INFO;
		if (e == null)
		{
			writeSQLCARD(e,severity, updateCount, 0);
			return;
		}

		// instead of writing a chain of sql error or warning, we send the first one, this is
		// jcc/db2 limitation, see beetle 4629

		// If it is a real SQL Error write a SQLERRRM first
		severity = getExceptionSeverity(e);
		if (severity > CodePoint.SVRCOD_ERROR)
		{
			// For a session ending error > CodePoint.SRVCOD_ERROR you cannot
			// send a SQLERRRM. A CMDCHKRM is required.  In XA if there is a
			// lock timeout it ends the whole session. I am not sure this 
			// is the correct behaviour but if it occurs we have to send 
			// a CMDCHKRM instead of SQLERRM
			writeCMDCHKRM(severity);
		}
		else if (sendSQLERRRM)
		{
			writeSQLERRRM(severity);
		}
		writeSQLCARD(e,severity, updateCount, 0);
	}

	private int getSqlCode(int severity)
	{
		if (severity == CodePoint.SVRCOD_WARNING)		// warning
			return 100;		//CLI likes it
		else if (severity == CodePoint.SVRCOD_INFO)             
			return 0;
		else
			return -1;
	}

	private void writeSQLCARD(SQLException e,int severity, 
		int updateCount, long rowCount ) throws DRDAProtocolException
	{
		writer.createDssObject();
		writer.startDdm(CodePoint.SQLCARD);
		writeSQLCAGRP(e, getSqlCode(severity), updateCount, rowCount);
		writer.endDdmAndDss();

		// If we have a shutdown exception, restart the server.
		if (e != null) {
			String sqlState = e.getSQLState();
			if (sqlState.regionMatches(0,
			  SQLState.CLOUDSCAPE_SYSTEM_SHUTDOWN, 0, 5)) {
			// then we're here because of a shutdown exception;
			// "clean up" by restarting the server.
				try {
					server.startNetworkServer();
				} catch (Exception restart)
				// any error messages should have already been printed,
				// so we ignore this exception here.
				{}
			}
		}

	}

	/**
	 * Write a null SQLCARD as an object
	 *
 	 * @exception DRDAProtocolException
	 */
	private void writeNullSQLCARDobject()
		throws DRDAProtocolException
	{
		writer.createDssObject();
		writer.startDdm(CodePoint.SQLCARD);
        writeSQLCAGRP(nullSQLState, 0, 0, 0);
		writer.endDdmAndDss();
	}
	/**
	 * Write SQLERRRM
	 *
	 * Instance Variables
	 * 	SVRCOD - Severity Code - required
 	 *
	 * @param	severity	severity of error
	 *
	 * @exception DRDAProtocolException
	 */
	private void writeSQLERRRM(int severity) throws DRDAProtocolException
	{
		writer.createDssReply();
		writer.startDdm(CodePoint.SQLERRRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, severity);
		writer.endDdmAndDss ();

	}

	/**
	 * Write CMDCHKRM
	 *
	 * Instance Variables
	 * 	SVRCOD - Severity Code - required 
 	 *
	 * @param	severity	severity of error
	 *
	 * @exception DRDAProtocolException
	 */
	private void writeCMDCHKRM(int severity) throws DRDAProtocolException
	{
		writer.createDssReply();
		writer.startDdm(CodePoint.CMDCHKRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, severity);
		writer.endDdmAndDss ();

	}

	/**
	 * Translate from Derby exception severity to SVRCOD
	 *
	 * @param e SQLException
	 */
	private int getExceptionSeverity (SQLException e)
	{
		int severity= CodePoint.SVRCOD_INFO;

		if (e == null)
			return severity;

		int ec = e.getErrorCode();
		switch (ec)
		{
			case ExceptionSeverity.STATEMENT_SEVERITY:
			case ExceptionSeverity.TRANSACTION_SEVERITY:
				severity = CodePoint.SVRCOD_ERROR;
				break;
			case ExceptionSeverity.WARNING_SEVERITY:
				severity = CodePoint.SVRCOD_WARNING;
				break;
			case ExceptionSeverity.SESSION_SEVERITY:
			case ExceptionSeverity.DATABASE_SEVERITY:
			case ExceptionSeverity.SYSTEM_SEVERITY:
				severity = CodePoint.SVRCOD_SESDMG;
				break;
			default:
				String sqlState = e.getSQLState();
				if (sqlState != null && sqlState.startsWith("01"))		// warning
					severity = CodePoint.SVRCOD_WARNING;
				else
					severity = CodePoint.SVRCOD_ERROR;
		}

		return severity;

	}
	/**
	 * Write SQLCAGRP
	 *
	 * SQLCAGRP : FDOCA EARLY GROUP
	 * SQL Communcations Area Group Description
	 *
	 * FORMAT FOR SQLAM <= 6
	 *   SQLCODE; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLSTATE; DRDA TYPE FCS; ENVLID 0x30; Length Override 5
	 *   SQLERRPROC; DRDA TYPE FCS; ENVLID 0x30; Length Override 8
	 *   SQLCAXGRP; DRDA TYPE N-GDA; ENVLID 0x52; Length Override 0
	 *
	 * FORMAT FOR SQLAM >= 7
	 *   SQLCODE; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLSTATE; DRDA TYPE FCS; ENVLID 0x30; Length Override 5
	 *   SQLERRPROC; DRDA TYPE FCS; ENVLID 0x30; Length Override 8
	 *   SQLCAXGRP; DRDA TYPE N-GDA; ENVLID 0x52; Length Override 0
	 *   SQLDIAGGRP; DRDA TYPE N-GDA; ENVLID 0x56; Length Override 0
	 *
	 * @param e 	SQLException encountered
	 * @param sqlcode	sqlcode
	 * 
	 * @exception DRDAProtocolException
	 */
	private void writeSQLCAGRP(SQLException e, int sqlcode, int updateCount,
			long rowCount) throws DRDAProtocolException
	{
        if (e == null) {
            // Forwarding to the optimized version when there is no
            // exception object
            writeSQLCAGRP(nullSQLState, sqlcode, updateCount, rowCount);
            return;
        }

		if (rowCount < 0 && updateCount < 0)
		{
			writer.writeByte(CodePoint.NULLDATA);
			return;
		}
			
        if (this.trace && sqlcode < 0) {
            trace("handle SQLException here");
            trace("reason is: "+e.getMessage());
            trace("SQLState is: "+e.getSQLState());
            trace("vendorCode is: "+e.getErrorCode());
            trace("nextException is: "+e.getNextException());
            server.consoleExceptionPrint(e);
            trace("wrapping SQLException into SQLCARD...");
        }
		
		//null indicator
		writer.writeByte(0);

		// SQLCODE
		writer.writeInt(sqlcode);

		// SQLSTATE
        writer.writeString(e.getSQLState());

		// SQLERRPROC
        // Write the byte[] constant rather than the string, for efficiency
        writer.writeBytes(server.prdIdBytes_);

		// SQLCAXGRP
        writeSQLCAXGRP(updateCount, rowCount, buildSqlerrmc(e), e.getNextException());
	}

    /**
     * Same as writeSQLCAGRP, but optimized for the case
         * when there is no real exception, i.e. the exception is null, or "End
         * of data"
     *
     * SQLCAGRP : FDOCA EARLY GROUP
     * SQL Communcations Area Group Description
     *
     * FORMAT FOR SQLAM <= 6
     *   SQLCODE; DRDA TYPE I4; ENVLID 0x02; Length Override 4
     *   SQLSTATE; DRDA TYPE FCS; ENVLID 0x30; Length Override 5
     *   SQLERRPROC; DRDA TYPE FCS; ENVLID 0x30; Length Override 8
     *   SQLCAXGRP; DRDA TYPE N-GDA; ENVLID 0x52; Length Override 0
     *
     * FORMAT FOR SQLAM >= 7
     *   SQLCODE; DRDA TYPE I4; ENVLID 0x02; Length Override 4
     *   SQLSTATE; DRDA TYPE FCS; ENVLID 0x30; Length Override 5
     *   SQLERRPROC; DRDA TYPE FCS; ENVLID 0x30; Length Override 8
     *   SQLCAXGRP; DRDA TYPE N-GDA; ENVLID 0x52; Length Override 0
     *   SQLDIAGGRP; DRDA TYPE N-GDA; ENVLID 0x56; Length Override 0
     *
     * @param sqlState     SQLState (already converted to UTF8)
     * @param sqlcode    sqlcode
         * @param updateCount
         * @param rowCount
     * 
     * @exception DRDAProtocolException
     */

    private void writeSQLCAGRP(byte[] sqlState, int sqlcode, 
                               int updateCount, long rowCount) throws DRDAProtocolException
    {
        if (rowCount < 0 && updateCount < 0) {
            writer.writeByte(CodePoint.NULLDATA);
            return;
        }
        
        //null indicator
        writer.writeByte(0);
        
        // SQLCODE
        writer.writeInt(sqlcode);

        // SQLSTATE
        writer.writeBytes(sqlState);

        // SQLERRPROC
        writer.writeBytes(server.prdIdBytes_);

        // SQLCAXGRP (Uses null as sqlerrmc since there is no error)
        writeSQLCAXGRP(updateCount, rowCount, null, null);
    }
	
	
	// Delimiters for SQLERRMC values.
    // The token delimiter value will be used to parse the MessageId from the 
	// SQLERRMC in MessageService.getLocalizedMessage and the MessageId will be
    // used to retrive the localized message. If this delimiter value is changed
    // please make sure to make appropriate changes in
    // MessageService.getLocalizedMessage that gets called from 
    // SystemProcedures.SQLCAMESSAGE
    // moved to ClientSharedData
	
	/**
	 * Create error message or message argements to return to client.  
	 * The SQLERRMC will normally be passed back  to the server in a call 
	 * to the SYSIBM.SQLCAMESSAGE but for severe exceptions the stored procedure 
	 * call cannot be made. So for Severe messages we will just send the message text.
	 * 
	 * This method will also truncate the value according the client capacity.
	 * CCC can only handle 70 characters.
	 * 
     * Server sends the sqlerrmc using UTF8 encoding to the client.
     * To get the message, client sends back information to the server
     * calling SYSIBM.SQLCAMESSAGE (see Sqlca.getMessage).  Several parameters 
     * are sent to this procedure including the locale, the sqlerrmc that the 
     * client received from the server. 
     * On server side, the procedure SQLCAMESSAGE in SystemProcedures then calls
     * the MessageService.getLocalizedMessage to retrieve the localized error message. 
     * In MessageService.getLocalizedMessage the sqlerrmc that is passed in, 
     * is parsed to retrieve the message id. The value it uses to parse the MessageId
     * is char value of 20, otherwise it uses the entire sqlerrmc as the message id. 
     * This messageId is then used to retrieve the localized message if present, to 
     * the client.
     * 
	 * @param se  SQLException to build SQLERRMC
	 *  
	 * @return  String which is either the message arguments to be passed to 
	 *          SYSIBM.SQLCAMESSAGE or just message text for severe errors.  
	 */
	private String buildSqlerrmc (SQLException se) 
	{
		boolean severe = (se.getErrorCode() >=  ExceptionSeverity.SESSION_SEVERITY);	
		String sqlerrmc = null;

		// get exception which carries Derby messageID and args, per DERBY-1178
		se = Util.getExceptionFactory().getArgumentFerry( se );
		
// GemStone changes BEGIN
		// if top-level args are null then use preformatted string since
		// it can happen when SQLException is received from remote node
		if (se instanceof DerbySQLException  && !severe
		    && ((DerbySQLException)se).getArguments() != null) {
		  sqlerrmc = buildTokenizedSqlerrmc(se);
		/* (original code)
		if (se instanceof EmbedSQLException  && ! severe)
		{
			sqlerrmc = buildTokenizedSqlerrmc((EmbedSQLException) se);
                */
// GemStone changes END
		}
		else {
			// If this is not an EmbedSQLException or is a severe excecption where
			// we have no hope of succussfully calling the SYSIBM.SQLCAMESSAGE send
			// preformatted message using the server locale
			sqlerrmc = buildPreformattedSqlerrmc(se);
			}
			// Truncate the sqlerrmc to a length that the client can support.
			int maxlen = (sqlerrmc == null) ? -1 : Math.min(sqlerrmc.length(),
					appRequester.supportedMessageParamLength());
			if ((maxlen >= 0) && (sqlerrmc.length() > maxlen))
			// have to truncate so the client can handle it.
			sqlerrmc = sqlerrmc.substring(0, maxlen);
			return sqlerrmc;
		}

	/**
	 * Build preformatted SQLException text 
	 * for severe exceptions or SQLExceptions that are not EmbedSQLExceptions.
	 * Just send the message text localized to the server locale.
	 * 
	 * @param se  SQLException for which to build SQLERRMC
	 * @return preformated message text 
	 * 			with messages separted by SQLERRMC_PREFORMATED_MESSAGE_DELIMITER
	 * 
	 */
	private String  buildPreformattedSqlerrmc(SQLException se) {
		if (se == null)
			return "";
		
		StringBuilder sb = new StringBuilder(); 
// GemStone changes BEGIN
		// prepend the server/thread information
		addServerInformation(sb);
// GemStone changes END
		 // String buffer to build up message
		do {
			sb.append(se.getLocalizedMessage());
			se = se.getNextException();
			if (se != null)
				sb.append(ClientSharedData.SQLERRMC_PREFORMATTED_MESSAGE_DELIMITER + 
						"SQLSTATE: " + se.getSQLState());
		} while (se != null);			
		return sb.toString();		
	}

	/**
	 * Build Tokenized SQLERRMC to just send the tokenized arguments to the client.
	 * for a Derby SQLException
	 * Message argument tokens are separated by SQLERRMC_TOKEN_DELIMITER 
	 * Multiple messages are separated by SystemProcedures.SQLERRMC_MESSAGE_DELIMITER
	 * 
	 *                 ...
	 * @param se   SQLException to print
	 * 
	 */
// GemStone changes BEGIN

  /** prepend the server/thread information */
  private final void addServerInformation(final StringBuilder sb) {
    final java.net.Socket sock = this.session.clientSocket;
    final java.net.InetAddress addr = sock.getLocalAddress();
    final String host = addr.getHostName();
    sb.append("(Server=").append(host != null ? host : addr.getHostAddress());
    sb.append('[').append(sock.getLocalPort()).append("],");
    sb.append(currentThread()).append(") ");
  }

  // Fix for UseCase8 ticket #5996
  private String buildTokenizedSqlerrmc(SQLException se) {
    final StringBuilder sqlerrmc = new StringBuilder();
    // prepend the server/thread information
    addServerInformation(sqlerrmc);
    sqlerrmc.append(ClientSharedData.SQLERRMC_SERVER_DELIMITER);
    do {
      if (se instanceof DerbySQLException) {
        DerbySQLException ese = (DerbySQLException)se;
        String messageId = ese.getMessageId();
        // arguments are variable part of a message
        Object[] args = ese.getArguments();
        for (int i = 0; args != null && i < args.length; i++) {
          sqlerrmc.append(args[i]).append(
              ClientSharedData.SQLERRMC_TOKEN_DELIMITER);
        }
        sqlerrmc.append(messageId);
      }
      se = se.getNextException();
      if (se != null && se instanceof DerbySQLException) {
        sqlerrmc.append(ClientSharedData.SQLERRMC_MESSAGE_DELIMITER)
            .append(se.getSQLState()).append(':');
      }
    } while (se != null);
    return sqlerrmc.toString();
  }
/* (original code)
	private String buildTokenizedSqlerrmc(EmbedSQLException se) {
		
		String sqlerrmc = "";
		do {
			String messageId = se.getMessageId();
			// arguments are variable part of a message
			Object[] args = se.getArguments();
			for (int i = 0; args != null &&  i < args.length; i++)
				sqlerrmc += args[i] + SQLERRMC_TOKEN_DELIMITER;
			sqlerrmc += messageId;
			se = (EmbedSQLException) se.getNextException();
			if (se != null)
			{
				sqlerrmc += SystemProcedures.SQLERRMC_MESSAGE_DELIMITER + se.getSQLState() + ":";				
			}
		} while (se != null);
		return sqlerrmc;
	}
*/
// GemStone changes END

	
	/**
	 * Write SQLCAXGRP
	 *
	 * SQLCAXGRP : EARLY FDOCA GROUP
	 * SQL Communications Area Exceptions Group Description
	 *
	 * FORMAT FOR SQLAM <= 6
	 *   SQLRDBNME; DRDA TYPE FCS; ENVLID 0x30; Length Override 18
	 *   SQLERRD1; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLERRD2; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLERRD3; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLERRD4; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLERRD5; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLERRD6; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLWARN0; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN1; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN2; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN3; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN4; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN5; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN6; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN7; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN8; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN9; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARNA; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLERRMSG_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 70
	 *   SQLERRMSG_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 70
	 *
	 * FORMAT FOR SQLAM >= 7
	 *   SQLERRD1; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLERRD2; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLERRD3; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLERRD4; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLERRD5; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLERRD6; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLWARN0; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN1; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN2; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN3; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN4; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN5; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN6; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN7; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN8; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARN9; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLWARNA; DRDA TYPE FCS; ENVLID 0x30; Length Override 1
	 *   SQLRDBNAME; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
	 *   SQLERRMSG_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 70
	 *   SQLERRMSG_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 70
	 * @param nextException SQLException encountered
	 * @param sqlerrmc sqlcode
	 * 
	 * @exception DRDAProtocolException
	 */
	private void writeSQLCAXGRP(int updateCount,  long rowCount, String sqlerrmc,
				SQLException nextException) throws DRDAProtocolException
	{
		writer.writeByte(0);		// SQLCAXGRP INDICATOR
		if (sqlamLevel < 7)
		{
			writeRDBNAM(database.dbName);
			writeSQLCAERRWARN(updateCount, rowCount);
		}
		else
		{
			// SQL ERRD1 - D6, WARN0-WARNA (35 bytes)
			writeSQLCAERRWARN(updateCount, rowCount);
			writer.writeShort(0);  //CCC on Win does not take RDBNAME
		}
		writeVCMorVCS(sqlerrmc);
		if (sqlamLevel >=7)
			writeSQLDIAGGRP(nextException);
	}

	/**
	 * Write the ERR and WARN part of the SQLCA
	 *
	 * @param updateCount
	 * @param rowCount 
	 */
	private void writeSQLCAERRWARN(int updateCount, long rowCount) 
	{
		// SQL ERRD1 - ERRD2 - row Count
		writer.writeInt((int)((rowCount>>>32))); 
		writer.writeInt((int)(rowCount & 0x0000000ffffffffL));
		// SQL ERRD3 - updateCount
		writer.writeInt(updateCount);
		// SQL ERRD4 - D6 (12 bytes)
        writer.writeBytes(errD4_D6); // byte[] constant
		// WARN0-WARNA (11 bytes)
        writer.writeBytes(warn0_warnA); // byte[] constant
	}

	/**
 	 * Write SQLDIAGGRP: SQL Diagnostics Group Description - Identity 0xD1
	 * Nullable Group
	 * SQLDIAGSTT; DRDA TYPE N-GDA; ENVLID 0xD3; Length Override 0
	 * SQLDIAGCN;  DRFA TYPE N-RLO; ENVLID 0xF6; Length Override 0
	 * SQLDIAGCI;  DRDA TYPE N-RLO; ENVLID 0xF5; Length Override 0
	 */
	private void writeSQLDIAGGRP(SQLException nextException) 
		throws DRDAProtocolException
	{
		// for now we only want to send ROW_DELETED and ROW_UPDATED warnings
		// as extended diagnostics
		// move to first ROW_DELETED or ROW_UPDATED exception. These have been
		// added to the end of the warning chain.
		while (
				nextException != null && 
				nextException.getSQLState() != SQLState.ROW_UPDATED &&
				nextException.getSQLState() != SQLState.ROW_DELETED) {
			nextException = nextException.getNextException();
		}

		if ((nextException == null) || 
				(diagnosticLevel == CodePoint.DIAGLVL0)) {
			writer.writeByte(CodePoint.NULLDATA);
			return;
		}
		writer.writeByte(0); // SQLDIAGGRP indicator

		writeSQLDIAGSTT();
		writeSQLDIAGCI(nextException);
		writeSQLDIAGCN();
	}

	/*
	 * writeSQLDIAGSTT: Write NULLDATA for now
	 */
	private void writeSQLDIAGSTT()
		throws DRDAProtocolException
	{
		writer.writeByte(CodePoint.NULLDATA);
		return;
	}

	/**
	 * writeSQLDIAGCI: SQL Diagnostics Condition Information Array - Identity 0xF5
	 * SQLNUMROW; ROW LID 0x68; ELEMENT TAKEN 0(all); REP FACTOR 1
	 * SQLDCIROW; ROW LID 0xE5; ELEMENT TAKEN 0(all); REP FACTOR 0(all)
	 */
	private void writeSQLDIAGCI(SQLException nextException)
		throws DRDAProtocolException
	{
		SQLException se = nextException;
		long rowNum = 1;

		/* Write the number of next exceptions to expect */
		writeSQLNUMROW(se);

		while (se != null)
		{
			String sqlState = se.getSQLState();

			// SQLCode > 0 -> Warning
			// SQLCode = 0 -> Info
			// SQLCode < 0 -> Error
			int severity = getExceptionSeverity(se);
			int sqlCode = -1;
			if (severity == CodePoint.SVRCOD_WARNING)
				sqlCode = 1;
			else if (severity == CodePoint.SVRCOD_INFO)
				sqlCode = 0;

			String sqlerrmc = "";
			if (diagnosticLevel == CodePoint.DIAGLVL1) {
				sqlerrmc = se.getLocalizedMessage();
			}

			// arguments are variable part of a message
			// only send arguments for diagnostic level 0
			if (diagnosticLevel == CodePoint.DIAGLVL0) {
// GemStone changes BEGIN
			  // we are only able to get arguments of DerbySQLException
			  if (se instanceof DerbySQLException) {
			    Object[] args = ((DerbySQLException)se).getArguments();
                            /* (original code)
				// we are only able to get arguments of EmbedSQLException
				if (se instanceof EmbedSQLException) {
					Object[] args = ((EmbedSQLException)se).getArguments();
			    */
// GemStone changes END
					for (int i = 0; args != null &&  i < args.length; i++)
						sqlerrmc += args[i].toString()
						  + ClientSharedData.SQLERRMC_TOKEN_DELIMITER;
				}
			}

			String dbname = null;
			if (database != null)
				dbname = database.dbName;

			writeSQLDCROW(rowNum++, sqlCode, sqlState, dbname, sqlerrmc);

			se = se.getNextException();
		}
			
		return;
	}

	/**
	 * writeSQLNUMROW: Writes SQLNUMROW : FDOCA EARLY ROW
	 * SQL Number of Elements Row Description
	 * FORMAT FOR SQLAM LEVELS
	 * SQLNUMGRP; GROUP LID 0x58; ELEMENT TAKEN 0(all); REP FACTOR 1
	 */
	private void writeSQLNUMROW(SQLException nextException)
		 throws DRDAProtocolException
	{
		writeSQLNUMGRP(nextException);
	}

	/**
	 * writeSQLNUMGRP: Writes SQLNUMGRP : FDOCA EARLY GROUP
	 * SQL Number of Elements Group Description
	 * FORMAT FOR ALL SQLAM LEVELS
	 * SQLNUM; DRDA TYPE I2; ENVLID 0x04; Length Override 2
	 */
	private void writeSQLNUMGRP(SQLException nextException)
		 throws DRDAProtocolException
	{
		int i=0;
		SQLException se;

		/* Count the number of chained exceptions to be sent */
		for (se = nextException; se != null; se = se.getNextException()) i++;
		writer.writeShort(i);
	}

	/**
	 * writeSQLDCROW: SQL Diagnostics Condition Row - Identity 0xE5
	 * SQLDCGRP; GROUP LID 0xD5; ELEMENT TAKEN 0(all); REP FACTOR 1
	 */
	private void writeSQLDCROW(long rowNum, int sqlCode, String sqlState, String dbname,
		 String sqlerrmc) throws DRDAProtocolException
	{
		writeSQLDCGRP(rowNum, sqlCode, sqlState, dbname, sqlerrmc);
	}

	/**
	 * writeSQLDCGRP: SQL Diagnostics Condition Group Description
	 * 
	 * SQLDCCODE; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 * SQLDCSTATE; DRDA TYPE FCS; ENVLID Ox30; Lengeh Override 5
	 * SQLDCREASON; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 * SQLDCLINEN; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 * SQLDCROWN; DRDA TYPE FD; ENVLID 0x0E; Lengeh Override 31
	 * SQLDCER01; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 * SQLDCER02; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 * SQLDCER03; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 * SQLDCER04; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 * SQLDCPART; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 * SQLDCPPOP; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 * SQLDCMSGID; DRDA TYPE FCS; ENVLID 0x30; Length Override 10
	 * SQLDCMDE; DRDA TYPE FCS; ENVLID 0x30; Length Override 8
	 * SQLDCPMOD; DRDA TYPE FCS; ENVLID 0x30; Length Override 5
	 * SQLDCRDB; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
	 * SQLDCTOKS; DRDA TYPE N-RLO; ENVLID 0xF7; Length Override 0
	 * SQLDCMSG_m; DRDA TYPE NVMC; ENVLID 0x3F; Length Override 32672
	 * SQLDCMSG_S; DRDA TYPE NVCS; ENVLID 0x33; Length Override 32672
	 * SQLDCCOLN_m; DRDA TYPE NVCM ; ENVLID 0x3F; Length Override 255
	 * SQLDCCOLN_s; DRDA TYPE NVCS; ENVLID 0x33; Length Override 255
	 * SQLDCCURN_m; DRDA TYPE NVCM; ENVLID 0x3F; Length Override 255
	 * SQLDCCURN_s; DRDA TYPE NVCS; ENVLID 0x33; Length Override 255
	 * SQLDCPNAM_m; DRDA TYPE NVCM; ENVLID 0x3F; Length Override 255
	 * SQLDCPNAM_s; DRDA TYPE NVCS; ENVLID 0x33; Length Override 255
	 * SQLDCXGRP; DRDA TYPE N-GDA; ENVLID 0xD3; Length Override 1
	 */
	private void writeSQLDCGRP(long rowNum, int sqlCode, String sqlState, String dbname,
		 String sqlerrmc) throws DRDAProtocolException
	{
		// SQLDCCODE
		writer.writeInt(sqlCode);

		// SQLDCSTATE
		writer.writeString(sqlState);


		writer.writeInt(0);						// REASON_CODE
		writer.writeInt(0);						// LINE_NUMBER
		writer.writeLong(rowNum);				// ROW_NUMBER

		byte[] byteArray = new byte[1];
		writer.writeScalarPaddedBytes(byteArray, 47, (byte) 0);

		writer.writeShort(0);					// CCC on Win does not take RDBNAME
		writer.writeByte(CodePoint.NULLDATA);	// MESSAGE_TOKENS
		writer.writeLDString(sqlerrmc);			// MESSAGE_TEXT

		writeVCMorVCS(null);					// COLUMN_NAME
		writeVCMorVCS(null);					// PARAMETER_NAME
		writeVCMorVCS(null);					// EXTENDED_NAME
		writer.writeByte(CodePoint.NULLDATA);	// SQLDCXGRP
	}

	/*
	 * writeSQLDIAGCN: Write NULLDATA for now
	 */
	private void writeSQLDIAGCN()
		throws DRDAProtocolException
	{
		writer.writeByte(CodePoint.NULLDATA);
		return;
	}
	
	private void writeSQLSTMTID(String statementID) throws DRDAProtocolException {
          writer.createDssObject();
          writer.startDdm(CodePoint.GETSTMTID);
//        writer.writeString(statementID);
          int length = 0;
          if (statementID != null) {
            byte[] buf = statementID.getBytes();
            length = buf.length;
            writer.writeInt(length);
            writer.writeBytes(buf, 0, length);
          } else {
            writer.writeInt(length);
          }
          writer.endDdmAndDss();
	}

	/** 
	 * Write SQLDARD
	 *
	 * SQLDARD : FDOCA EARLY ARRAY
	 * SQL Descriptor Area Row Description with SQL Communications Area
	 *
	 * FORMAT FOR SQLAM <= 6
	 *   SQLCARD; ROW LID 0x64; ELEMENT TAKEN 0(all); REP FACTOR 1
	 *   SQLNUMROW; ROW LID 0x68; ELEMENT TAKEN 0(all); REP FACTOR 1
	 *   SQLDAROW; ROW LID 0x60; ELEMENT TAKEN 0(all); REP FACTOR 0(all)
	 *
	 * FORMAT FOR SQLAM >= 7
	 *   SQLCARD; ROW LID 0x64; ELEMENT TAKEN 0(all); REP FACTOR 1
	 *   SQLDHROW; ROW LID 0xE0; ELEMENT TAKEN 0(all); REP FACTOR 1
	 *   SQLNUMROW; ROW LID 0x68; ELEMENT TAKEN 0(all); REP FACTOR 1
	 *
	 * @param stmt	prepared statement
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void writeSQLDARD(DRDAStatement stmt, boolean rtnOutput, SQLException e, boolean writeSingleHopInformation, boolean metadataChanged) throws DRDAProtocolException, SQLException
	{
// GemStone changes BEGIN
		Statement ps = stmt.getUnderlyingStatement();
		// do not try to get meta-data etc. if not required else
		// it can throw another SQLException (see bug #41719)
		ResultSetMetaData rsmeta = null;
		ParameterMetaData pmeta = null;
		int numElems = 0;
		if (e == null || e instanceof SQLWarning) {
		  // If we are writing an SQLDA because the metadata changed
		  // (due to ALTER TABLE)
		  // then get metadata directly from resultset, not preparedstatement's
		  // cached resultset
		  if ((!metadataChanged) && (stmt.ps != null)) {
		    rsmeta = stmt.ps.getMetaData();
		    pmeta = stmt.getParameterMetaData();
		  }
		  else {
		    ResultSet rs = ps.getResultSet();
		    if (rs != null) {
		      rsmeta = rs.getMetaData();
		    }
		  }
		/* (original code)
		PreparedStatement ps = stmt.getPreparedStatement();
		ResultSetMetaData rsmeta = ps.getMetaData();
		ParameterMetaData pmeta = stmt.getParameterMetaData();
		int numElems = 0;
		if (e == null || e instanceof SQLWarning)
		{
		*/
// GemStone changes END
			if (rtnOutput && (rsmeta != null))
				numElems = rsmeta.getColumnCount();
			else if ((! rtnOutput) && (pmeta != null))
				numElems = pmeta.getParameterCount();
		}

		writer.createDssObject();

		// all went well we will just write a null SQLCA
		writer.startDdm(CodePoint.SQLDARD);
		writeSQLCAGRP(e, getSqlCode(getExceptionSeverity(e)), 0, 0);

		if (sqlamLevel >= MGRLVL_7)
			writeSQLDHROW(ps.getResultSetHoldability());

		//SQLNUMROW
		if (this.trace) 
			trace("num Elements = " + numElems);
		writer.writeShort(numElems);

		for (int i=0; i < numElems; i++)
			writeSQLDAGRP (rsmeta, pmeta, i, rtnOutput);
		// Gemstone changes BEGIN
		//System.out.println("KN: stmt.needSendSingleHopInfo: " + stmt.needSendSingleHopInfo() + " stmnt txt: " + stmt.getSQLText());
    if (writeSingleHopInformation && stmt.needSendSingleHopInfo()) {
      /*
      SingleHopInformation shifn = new SingleHopInformation("MYREGION", (byte)1);
      */
      SingleHopInformation shifn = stmt.fillAndGetSingleHopInformation();
      if (SanityManager.TraceSingleHop) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
            "DRDAConnThread::writeSQLDARD write for stmnt: " + stmt.sqlText
                + " writing single hop information: " + shifn);
      }
      byte[] buffer = null;
      int length = 0;

      try {
        DynamicByteArrayOutputStream dbaos = new DynamicByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(dbaos);

        oos.writeObject(shifn);

        buffer = dbaos.getByteArray();
        length = dbaos.getUsed();
        writer.writeInt(length);
        writer.writeBytes(buffer, 0, length);
      } catch (IOException ioe) {
        handleException(ioe);
      }
      finally {
        stmt.setSendSingleHopInLccToFalse();
        stmt.setSendSingleHopInfo(false);
      }
    }
		// Gemstone changes END
		writer.endDdmAndDss();

	}
	/**
	 * Write QRYDSC - Query Answer Set Description
	 *
	 * @param stmt DRDAStatement we are working on
	 * @param FDODSConly	simply the FDODSC, without the wrap
	 *
	 * Instance Variables
	 *   SQLDTAGRP - required
	 * 
	 * Only 84 columns can be sent in a single QRYDSC.  If there are more columns
	 * they must be sent in subsequent QRYDSC.
	 * If the QRYDSC will not fit into the current block, as many columns as can
	 * fit are sent and then the remaining are sent in the following blocks.
	 * 
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void writeQRYDSC(DRDAStatement stmt, boolean FDODSConly)
		throws DRDAProtocolException, SQLException
	{
		ResultSet rs = null;
		ResultSetMetaData rsmeta = null;
		ParameterMetaData pmeta = null;
		if (!stmt.needsToSendParamData)
			rs = stmt.getResultSet();
		if (rs == null)		// this is a CallableStatement, use parameter meta data
			pmeta = stmt.getParameterMetaData();
		else
			rsmeta = rs.getMetaData();

	    int  numCols = (rsmeta != null ? rsmeta.getColumnCount() : pmeta.getParameterCount());
		int numGroups = 1;
		int colStart = 1;
		int colEnd = numCols;
		int blksize = stmt.getBlksize() > 0 ? stmt.getBlksize() : CodePoint.QRYBLKSZ_MAX;
		if (this.trace) {
		  trace("numCols = " + numCols);
		}

		// check for remaining space in current query block
		// Need to mod with blksize so remaining doesn't go negative. 4868
		int remaining = blksize - (writer.getDSSLength()  % blksize) - (3 + 
				FdocaConstants.SQLCADTA_SQLDTARD_RLO_SIZE);


		// calcuate how may columns can be sent in the current query block
		int firstcols = remaining/FdocaConstants.SQLDTAGRP_COL_DSC_SIZE;

		// check if it doesn't all fit into the first block and 
		//	under FdocaConstants.MAX_VARS_IN_NGDA
		if (firstcols < numCols || numCols > FdocaConstants.MAX_VARS_IN_NGDA)
		{
			// we are limited to FdocaConstants.MAX_VARS_IN_NGDA
			if (firstcols > FdocaConstants.MAX_VARS_IN_NGDA)
			{
				if (this.trace)
					SanityManager.ASSERT(numCols > FdocaConstants.MAX_VARS_IN_NGDA,
						"Number of columns " + numCols + 
						" is less than MAX_VARS_IN_NGDA");
				numGroups = numCols/FdocaConstants.MAX_VARS_IN_NGDA;
				// some left over
				if (FdocaConstants.MAX_VARS_IN_NGDA * numGroups < numCols)
					numGroups++;
				colEnd = FdocaConstants.MAX_VARS_IN_NGDA;
			}
			else
			{
				colEnd = firstcols;
				numGroups += (numCols-firstcols)/FdocaConstants.MAX_VARS_IN_NGDA;
				if (FdocaConstants.MAX_VARS_IN_NGDA * numGroups < numCols)
					numGroups++;
			}
		}

		if (! FDODSConly)
		{
			writer.createDssObject();
			writer.startDdm(CodePoint.QRYDSC);
		}

		for (int i = 0; i < numGroups; i++)
		{
			writeSQLDTAGRP(stmt, rsmeta, pmeta, colStart, colEnd, 
							(i == 0 ? true : false),
							!FDODSConly /* GemStoneAddition */);
			// Neeraj: If it is the last iteration then don't modify colStart and colEnd.
			// This is because there is a bug in derby where some data is not sent when
			// the num of columns is greater than 84 and the remaining space in the current
			// query block cannot fit all the remainder of (numCols % FdocaConstants.MAX_VARS_IN_NGDA)
			// Probably will contribute this to derby.
			if (i != numGroups - 1) {
			  colStart = colEnd + 1;
			  // 4868 - Limit range to MAX_VARS_IN_NGDA (used to have extra col)
			  colEnd = colEnd + FdocaConstants.MAX_VARS_IN_NGDA;
			  if (colEnd > numCols) {
			  	colEnd = numCols;
			  }
			}
		}
// GemStone changes BEGIN
		// Some more left to be sent
		if (colEnd < numCols) {
		  int left = numCols - colEnd;
		  if (this.trace) {
	                  trace("number of columns still not sent = " + left);
	          }
		  int remainder = left % FdocaConstants.MAX_VARS_IN_NGDA;
		  int remainingGroups = 0;
		  if (remainder == 0) {
		    remainingGroups = left / FdocaConstants.MAX_VARS_IN_NGDA;
		  }
		  else {
		    remainingGroups = (left / FdocaConstants.MAX_VARS_IN_NGDA) + 1;
		  }
		  colStart = colEnd + 1;
		  colEnd = colStart + FdocaConstants.MAX_VARS_IN_NGDA;
		  if (colEnd > numCols) {
		    colEnd = numCols;
		  }
		  for (int i = 0; i < remainingGroups; i++)
	                {
	                        writeSQLDTAGRP(stmt, rsmeta, pmeta, colStart, colEnd, 
	                                                        false,
	                                                        !FDODSConly /* GemStoneAddition */);
	                        colStart = colEnd + 1;
	                        // 4868 - Limit range to MAX_VARS_IN_NGDA (used to have extra col)
	                        colEnd = colEnd + FdocaConstants.MAX_VARS_IN_NGDA;
	                        if (colEnd > numCols)
	                                colEnd = numCols;
	                }
		}
// GemStone changes END
		writer.writeBytes(FdocaConstants.SQLCADTA_SQLDTARD_RLO);
		if (! FDODSConly)
			writer.endDdmAndDss();
	}
	/**
	 * Write SQLDTAGRP
	 * SQLDAGRP : Late FDOCA GROUP
	 * SQL Data Value Group Descriptor
	 *  LENGTH - length of the SQLDTAGRP
	 *  TRIPLET_TYPE - NGDA for first, CPT for following
	 *  ID - SQLDTAGRP_LID for first, NULL_LID for following
	 *  For each column
	 *    DRDA TYPE 
	 *	  LENGTH OVERRIDE
	 *	    For numeric/decimal types
	 *		  PRECISON
	 *		  SCALE
	 *	    otherwise
	 *		  LENGTH or DISPLAY_WIDTH
	 *
	 * @param stmt		drda statement
	 * @param rsmeta	resultset meta data
	 * @param pmeta		parameter meta data for CallableStatement
	 * @param colStart	starting column for group to send
	 * @param colEnd	end column to send
	 * @param first		is this the first group
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void writeSQLDTAGRP(DRDAStatement stmt, ResultSetMetaData rsmeta, 
								ParameterMetaData pmeta,
								int colStart, int colEnd, boolean first,
								boolean useIncomingLength /* GemStoneAddition */)
		throws DRDAProtocolException, SQLException
	{

		int length =  (FdocaConstants.SQLDTAGRP_COL_DSC_SIZE * 
					((colEnd+1) - colStart)) + 3;
		writer.writeByte(length);
		if (first)
		{

			writer.writeByte(FdocaConstants.NGDA_TRIPLET_TYPE);
			writer.writeByte(FdocaConstants.SQLDTAGRP_LID);
		}
		else
		{
			//continued
			writer.writeByte(FdocaConstants.CPT_TRIPLET_TYPE);
			writer.writeByte(FdocaConstants.NULL_LID);

		}

						   

		boolean hasRs = (rsmeta != null);	//  if don't have result, then we look at parameter meta

		for (int i = colStart; i <= colEnd; i++)
		{
			boolean nullable = (hasRs ? (rsmeta.isNullable(i) == rsmeta.columnNullable) :
												 (pmeta.isNullable(i) == JDBC30Translation.PARAMETER_NULLABLE));
			int colType = (hasRs ? rsmeta.getColumnType(i) : pmeta.getParameterType(i));
			int[] outlen = {-1};
			
			int drdaType = FdocaConstants.mapJdbcTypeToDrdaType( colType, nullable, appRequester, outlen );

			boolean isDecimal = ((drdaType | 1) == DRDAConstants.DRDA_TYPE_NDECIMAL);
			int precision = 0, scale = 0;
			if (hasRs)
			{
				precision = rsmeta.getPrecision(i);
				scale = rsmeta.getScale(i);
				stmt.setRsDRDAType(i,drdaType);			
				stmt.setRsPrecision(i, precision);
				stmt.setRsScale(i,scale);
			}

			else if (isDecimal)
			{
				if (stmt.isOutputParam(i))
				{
					precision = pmeta.getPrecision(i);
					scale = pmeta.getScale(i);
					((CallableStatement) stmt.ps).registerOutParameter(i,Types.DECIMAL,scale);
				  
				}

			}

// GemStone changes BEGIN
			int paramLen = -1;
			/* (original code)
			if (this.trace)
				trace("jdbcType=" + colType + "  \tdrdaType=" + Integer.toHexString(drdaType));
			*/
// GemStone changes END

			// Length or precision and scale for decimal values.
			writer.writeByte(drdaType);
			if (isDecimal)
			{
				writer.writeByte(precision);
				writer.writeByte(scale);
			}
// GemStone changes BEGIN
			else if (outlen[0] != -1) {
			  paramLen = outlen[0];
			  writer.writeShort(paramLen);
			}
			else if (hasRs) {
			  // for fixed width CHARs use the actual size since
			  // length is not encoded in the serialization (#45811)
			  if (colType == Types.CHAR) {
			    paramLen = rsmeta.getPrecision(i);
			  }
			  else {
			    paramLen = rsmeta.getColumnDisplaySize(i);
			  }
			  writer.writeShort(paramLen);
			}
			else if (useIncomingLength) {
			  paramLen = stmt.getParamLen(i);
			  writer.writeShort(paramLen);
			}
			else {
			  paramLen = pmeta.getPrecision(i);
			  writer.writeShort(paramLen);
			}
			/* (original code)
			else if (outlen[0] != -1)
				writer.writeShort(outlen[0]);
			else if (hasRs)
				writer.writeShort(rsmeta.getColumnDisplaySize(i));
			else
				writer.writeShort(stmt.getParamLen(i));
			*/
// GemStone changes BEGIN
			if (this.trace) {
			  trace("jdbcType=" + colType + "  \tdrdaType=0x"
			      + Integer.toHexString(drdaType) + " \tlen="
			      + paramLen);
			}
// GemStone changes END
		}
	}




    /**
     * Holdability passed in as it can represent the holdability of
     * the statement or a specific result set.
     * @param holdability HOLD_CURSORS_OVER_COMMIT or CLOSE_CURSORS_AT_COMMIT
     * @throws DRDAProtocolException
     * @throws SQLException
     */
	private void writeSQLDHROW(int holdability) throws DRDAProtocolException,SQLException
	{		
		if (JVMInfo.JDK_ID < 2) //write null indicator for SQLDHROW because there is no holdability support prior to jdk1.3
		{
			writer.writeByte(CodePoint.NULLDATA);
			return;
		}

		writer.writeByte(0);		// SQLDHROW INDICATOR

		//SQLDHOLD
		writer.writeShort(holdability);
		
		//SQLDRETURN
		writer.writeShort(0);
		//SQLDSCROLL
		writer.writeShort(0);
		//SQLDSENSITIVE
		writer.writeShort(0);
		//SQLDFCODE
		writer.writeShort(0);
		//SQLDKEYTYPE
		writer.writeShort(0);
		//SQLRDBNAME
		writer.writeShort(0);	//CCC on Windows somehow does not take any dbname
		//SQLDSCHEMA
		writeVCMorVCS(null);

	}

	/**
	 * Write QRYDTA - Query Answer Set Data
	 *  Contains some or all of the answer set data resulting from a query
	 *  If the client is not using rowset processing, this routine attempts
	 *  to pack as much data into the QRYDTA as it can. This may result in
	 *  splitting the last row across the block, in which case when the
	 *  client calls CNTQRY we will return the remainder of the row.
	 *
	 *  Splitting a QRYDTA block is expensive, for several reasons:
	 *  - extra logic must be run, on both client and server side
	 *  - more network round-trips are involved
	 *  - the QRYDTA block which contains the continuation of the split
	 *    row is generally wasteful, since it contains the remainder of
	 *    the split row but no additional rows.
	 *  Since splitting is expensive, the server makes some attempt to
	 *  avoid it. Currently, the server's algorithm for this is to
	 *  compute the length of the current row, and to stop trying to pack
	 *  more rows into this buffer if another row of that length would
	 *  not fit. However, since rows can vary substantially in length,
	 *  this algorithm is often ineffective at preventing splits. For
	 *  example, if a short row near the end of the buffer is then
	 *  followed by a long row, that long row will be split. It is possible
	 *  to improve this algorithm substantially:
	 *  - instead of just using the length of the previous row as a guide
	 *    for whether to attempt packing another row in, use some sort of
	 *    overall average row size computed over multiple rows (e.g., all
	 *    the rows we've placed into this QRYDTA block, or all the rows
	 *    we've process for this result set)
	 *  - when we discover that the next row will not fit, rather than
	 *    splitting the row across QRYDTA blocks, if it is relatively
	 *    small, we could just hold the entire row in a buffer to place
	 *    it entirely into the next QRYDTA block, or reset the result
	 *    set cursor back one row to "unread" this row.
	 *  - when splitting a row across QRYDTA blocks, we tend to copy
	 *    data around multiple times. Careful coding could remove some
	 *    of these copies.
	 *  However, it is important not to over-complicate this code: it is
	 *  better to be correct than to be efficient, and there have been
	 *  several bugs in the split logic already.
	 *
	 * Instance Variables
	 *   Byte string
	 *
	 * @param stmt	DRDA statement we are processing
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void writeQRYDTA (DRDAStatement stmt) 
		throws DRDAProtocolException, SQLException
	{
		boolean getMoreData = true;
		boolean sentExtData = false;
		int startLength = 0;
		writer.createDssObject();

		if (this.trace) 
			trace("Write QRYDTA");
		writer.startDdm(CodePoint.QRYDTA);
		// Check to see if there was leftover data from splitting
		// the previous QRYDTA for this result set. If there was, and
		// if we have now sent all of it, send any EXTDTA for that row
		// and increment the rowCount which we failed to increment in
		// writeFDODTA when we realized the row needed to be split.
		if (processLeftoverQRYDTA(stmt))
		{
			if (stmt.getSplitQRYDTA() == null)
			{
				stmt.rowCount += 1;
				if (stmt.getExtDtaObjects() != null)
					writeEXTDTA(stmt);
			}
			return;
		}

		while(getMoreData)
		{			
			sentExtData = false;
			getMoreData = writeFDODTA(stmt);

			if (stmt.getExtDtaObjects() != null &&
					stmt.getSplitQRYDTA() == null)
			{
				writer.endDdmAndDss();
				writeEXTDTA(stmt);
				getMoreData=false;
				sentExtData = true;
			}

			// if we don't have enough room for a row of the 
			// last row's size, don't try to cram it in.
			// It would get split up but it is not very efficient.
			if (getMoreData == true)
			{
				int endLength = writer.getDSSLength();
				int rowsize = endLength - startLength;
				if ((stmt.getBlksize() - endLength ) < rowsize)
					getMoreData = false;

				startLength = endLength;
			}

		}
		// If we sent extDta we will rely on
		// writeScalarStream to end the dss with the proper chaining.
		// otherwise end it here.
		if (! sentExtData)
			writer.endDdmAndDss();

		if (!stmt.hasdata()) {
			final boolean qryclsOnLmtblkprc =
				appRequester.supportsQryclsimpForLmtblkprc();
			if (stmt.isRSCloseImplicit(qryclsOnLmtblkprc)) {
				stmt.rsClose();
			}
		}
	}

	/**
	 * This routine places some data into the current QRYDTA block using
	 * FDODTA (Formatted Data Object DaTA rules).
	 *
	 * There are 3 basic types of processing flow for this routine:
	 * - In normal non-rowset, non-scrollable cursor flow, this routine
	 *   places a single row into the QRYDTA block and returns TRUE,
	 *   indicating that the caller can call us back to place another
	 *   row into the result set if he wishes. (The caller may need to
	 *   send Externalized Data, which would be a reason for him NOT to
	 *   place any more rows into the QRYDTA).
	 * - In ROWSET processing, this routine places an entire ROWSET of
	 *   rows into the QRYDTA block and returns FALSE, indicating that
	 *   the QRYDTA block is full and should now be sent.
	 * - In callable statement processing, this routine places the
	 *   results from the output parameters of the called procedure into
	 *   the QRYDTA block. This code path is really dramatically
	 *   different from the other two paths and shares only a very small
	 *   amount of common code in this routine.
	 *
	 * In all cases, it is possible that the data we wish to return may
	 * not fit into the QRYDTA block, in which case we call splitQRYDTA
	 * to split the data and remember the remainder data in the result set.
	 * Splitting the data is relatively rare in the normal cursor case,
	 * because our caller (writeQRYDTA) uses a coarse estimation
	 * technique to avoid calling us if he thinks a split is likely.
	 *
	 * The overall structure of this routine is implemented as two
	 * loops:
	 * - the outer "do ... while ... " loop processes a ROWSET, one row
	 *   at a time. For non-ROWSET cursors, and for callable statements,
	 *   this loop executes only once.
	 * - the inner "for ... i < numCols ..." loop processes each column
	 *   in the current row, or each output parmeter in the procedure.
	 *
	 * Most column data is written directly inline in the QRYDTA block.
	 * Some data, however, is written as Externalized Data. This is
	 * commonly used for Large Objects. In that case, an Externalized
	 * Data Pointer is written into the QRYDTA block, and the actual
	 * data flows in separate EXTDTA blocks which are returned
	 * after this QRYDTA block.
	 */
	private boolean writeFDODTA (DRDAStatement stmt) 
		throws DRDAProtocolException, SQLException
	{
		boolean hasdata = false;
		int blksize = stmt.getBlksize() > 0 ? stmt.getBlksize() : CodePoint.QRYBLKSZ_MAX;
		long rowCount = 0;
		ResultSet rs =null;
		boolean moreData = (stmt.getQryprctyp()
							== CodePoint.LMTBLKPRC);
		int  numCols;

                stmt.setStatus("SENDING RESULTS");
		if (!stmt.needsToSendParamData)
		{
			rs = stmt.getResultSet();
		}

// GemStone changes BEGIN
		final boolean isScrollable = stmt.isScrollable();
// GemStone changes END
		if (rs != null)
		{
			numCols = stmt.getNumRsCols();
// GemStone changes BEGIN
			if (isScrollable)
			/* (original code)
			if (stmt.isScrollable())
			*/
// GemStone changes END
				hasdata = positionCursor(stmt, rs);
			else
				hasdata = rs.next();
		}
		else	// it's for a CallableStatement
		{
			hasdata = stmt.hasOutputParams();
			numCols = stmt.getDrdaParamCount();
			if (hasdata) {
			  stmt.setStatus("SENDING OutputParams: " + numCols);
			}
		}


		do {
			if (!hasdata)
			{
// GemStone changes BEGIN
				doneData(stmt, rs, isScrollable);
				/* (original code)
				doneData(stmt, rs);
				*/
// GemStone changes END
				moreData = false;
				return moreData;
			}
			
			// Send ResultSet warnings if there are any
			SQLWarning sqlw = (rs != null)? rs.getWarnings(): null;
			if (rs != null) {
				rs.clearWarnings();
			}

// GemStone changes BEGIN
			final boolean candidateForChanges = rs != null &&
			    (!(rs instanceof EmbedResultSet) ||
			        ((EmbedResultSet)rs).candidateForChanges());
// GemStone changes END
			// for updatable, insensitive result sets we signal the
			// row updated condition to the client via a warning to be 
			// popped by client onto its rowUpdated state, i.e. this 
			// warning should not reach API level.
// GemStone changes BEGIN
			if (candidateForChanges && rs.rowUpdated()) {
			/* (original code)
			if (rs != null && rs.rowUpdated()) {
			*/
// GemStone changes END
				SQLWarning w = new SQLWarning("", SQLState.ROW_UPDATED,
						ExceptionSeverity.WARNING_SEVERITY);
				if (sqlw != null) {
					sqlw.setNextWarning(w);
				} else {
					sqlw = w;
				}
			}
			// Delete holes are manifest as a row consisting of a non-null
			// SQLCARD and a null data group. The SQLCARD has a warning
			// SQLSTATE of 02502
// GemStone changes BEGIN
			final boolean rowDeleted = candidateForChanges &&
			    rs.rowDeleted();
			if (rowDeleted) {
			/* (original code)
			if (rs != null && rs.rowDeleted()) {
			*/
// GemStone changes END
				SQLWarning w = new SQLWarning("", SQLState.ROW_DELETED,
						ExceptionSeverity.WARNING_SEVERITY);
				if (sqlw != null) {
					sqlw.setNextWarning(w);
				} else {
					sqlw = w;
				}
			}

			if (sqlw == null)
                writeSQLCAGRP(nullSQLState, 0, -1, -1);
			else
				writeSQLCAGRP(sqlw, sqlw.getErrorCode(), 1, -1);

			// if we were asked not to return data, mark QRYDTA null; do not
			// return yet, need to make rowCount right
			// if the row has been deleted return QRYDTA null (delete hole)
			boolean noRetrieveRS = (rs != null && 
// GemStone changes BEGIN
					(!stmt.getQryrtndta() || rowDeleted));
					/* (original code)
					(!stmt.getQryrtndta() || rs.rowDeleted()));
					*/
// GemStone changes END
			if (noRetrieveRS)
				writer.writeByte(0xFF);  //QRYDTA null indicator: IS NULL
			else
				writer.writeByte(0);  //QRYDTA null indicator: not null

			for (int i = 1; i <= numCols; i++)
			{
				if (noRetrieveRS)
					break;

				int drdaType;
				int ndrdaType;
				int precision;
				int scale;

				Object val = null;
				boolean valNull;
				if (rs != null)
				{
					drdaType =   stmt.getRsDRDAType(i) & 0xff;
					precision = stmt.getRsPrecision(i);
					scale = stmt.getRsScale(i);
					ndrdaType = drdaType | 1;

					if (this.trace)
						trace("!!drdaType = " + java.lang.Integer.toHexString(drdaType) + 
					 			 " precision=" + precision +" scale = " + scale);
					switch (ndrdaType)
					{
						case DRDAConstants.DRDA_TYPE_NLOBBYTES:
						case  DRDAConstants.DRDA_TYPE_NLOBCMIXED:
							EXTDTAInputStream extdtaStream=  
								EXTDTAInputStream.getEXTDTAStream(rs, i, drdaType);
							writeFdocaVal(i,extdtaStream, drdaType,
										  precision,scale,extdtaStream.isNull(),stmt);
							break;
						case DRDAConstants.DRDA_TYPE_NINTEGER:
							int ival = rs.getInt(i);
							valNull = rs.wasNull();
							if (this.trace)
								trace("====== writing int: "+ ival + " is null: " + valNull);
							writeNullability(drdaType,valNull);
							if (! valNull)
								writer.writeInt(ival);
							break;
						case DRDAConstants.DRDA_TYPE_NSMALL:
							short sval = rs.getShort(i);
							valNull = rs.wasNull();
							if (this.trace)
								trace("====== writing small: "+ sval + " is null: " + valNull);
							writeNullability(drdaType,valNull);
							if (! valNull)
								writer.writeShort(sval);
							break;
						case DRDAConstants.DRDA_TYPE_NINTEGER8:
							long lval = rs.getLong(i);
							valNull = rs.wasNull();
							if (this.trace)
								trace("====== writing long: "+ lval + " is null: " + valNull);
							writeNullability(drdaType,valNull);
							if (! valNull)
								writer.writeLong(lval);
							break;
						case DRDAConstants.DRDA_TYPE_NFLOAT4:
							float fval = rs.getFloat(i);
							valNull = rs.wasNull();
							if (this.trace)
								trace("====== writing float: "+ fval + " is null: " + valNull);
							writeNullability(drdaType,valNull);
							if (! valNull)
								writer.writeFloat(fval);
							break;
						case DRDAConstants.DRDA_TYPE_NFLOAT8:
							double dval = rs.getDouble(i);
							valNull = rs.wasNull();
							if (this.trace)
								trace("====== writing double: "+ dval + " is null: " + valNull);
							writeNullability(drdaType,valNull);
							if (! valNull)
								writer.writeDouble(dval);
							break;
						case DRDAConstants.DRDA_TYPE_NCHAR:
						case DRDAConstants.DRDA_TYPE_NVARCHAR:
// GemStone changes BEGIN
						case DRDAConstants.DRDA_TYPE_NMIX:
// GemStone changes END
						case DRDAConstants.DRDA_TYPE_NVARMIX:
						case DRDAConstants.DRDA_TYPE_NLONG:
						case DRDAConstants.DRDA_TYPE_NLONGMIX:
							String valStr = rs.getString(i);
							if (this.trace)
								trace("====== writing char/varchar/mix :"+ valStr + ":");
							writeFdocaVal(i, valStr, drdaType,
										  precision,scale,rs.wasNull(),stmt);
							break;
						default:
// GemStone changes BEGIN
							Object obj = rs.getObject(i);
							writeFdocaVal(i, obj, drdaType,
							    precision, scale,
							    obj == null || rs.wasNull(), stmt);
							/* (original code)
							writeFdocaVal(i, rs.getObject(i),drdaType,
										  precision,scale,rs.wasNull(),stmt);
							*/
// GemStone changes END
					}
				}
				else
				{
                                    
					drdaType =   stmt.getParamDRDAType(i) & 0xff;
					precision = stmt.getParamPrecision(i);
					scale = stmt.getParamScale(i);
					
					if (stmt.isOutputParam(i)) {
						int[] outlen = new int[1];
						drdaType = FdocaConstants.mapJdbcTypeToDrdaType( stmt.getOutputParamType(i), true, appRequester, outlen );
						precision = stmt.getOutputParamPrecision(i);
						scale = stmt.getOutputParamScale(i);
                                                
						if (this.trace)
							trace("***getting Object "+i);
                        val = getObjectForWriteFdoca(
                                (CallableStatement) stmt.ps, i, drdaType);
						valNull = (val == null);
						writeFdocaVal(i,val,drdaType,precision, scale, valNull,stmt);
					}
					else
						writeFdocaVal(i,null,drdaType,precision,scale,true,stmt);

				}
			}
			// does all this fit in one QRYDTA
			if (writer.getDSSLength() > blksize)
			{
				splitQRYDTA(stmt, blksize);
				return false;
			}

			if (rs == null) {
			        stmt.setStatus("DONE");
				return moreData;
			}

			//get the next row
			rowCount++;
			if (rowCount < stmt.getQryrowset())
			{
				hasdata = rs.next();
			}
			/*(1) scrollable we return at most a row set; OR (2) no retrieve data
			 */
// GemStone changes BEGIN
			else if (isScrollable || noRetrieveRS)
			/* (original code)
			else if (stmt.isScrollable() || noRetrieveRS)
			*/
// GemStone changes END
				moreData=false;

		} while (hasdata && rowCount < stmt.getQryrowset());

		// add rowCount to statement row count
		// for non scrollable cursors
// GemStone changes BEGIN
		if (!isScrollable) {
		/* (original code)
		if (!stmt.isScrollable())
		*/
// GemStone changes END
			stmt.rowCount += rowCount;
	                stmt.setStatus("SENDING RESULTS " + stmt.rowCount);
		}

		if (!hasdata)
		{
// GemStone changes BEGIN
			doneData(stmt, rs, isScrollable);
			/* (original code)
			doneData(stmt, rs);
			*/
// GemStone changes END
			moreData=false;
		}

// GemStone changes BEGIN
		if (!isScrollable)
		/* (original code)
		if (!stmt.isScrollable())
		*/
// GemStone changes END
			stmt.setHasdata(hasdata);
		return moreData;
	}

    /**
     * <p>
     * Get the value of an output parameter of the specified type from a
     * {@code CallableStatement}, in a form suitable for being writted by
     * {@link #writeFdocaVal}. For most types, this means just calling
     * {@code CallableStatement.getObject(int)}.
     * </p>
     *
     * <p>
     * This method should behave like the corresponding method for
     * {@code ResultSet}, and changes made to one of these methods, must be
     * reflected in the other method.
     * </p>
     *
     * @param cs the callable statement to fetch the object from
     * @param index the parameter index
     * @param drdaType the DRDA type of the object to fetch
     * @return an object with the value of the output parameter
     * @throws if a database error occurs while fetching the parameter value
     */
    private Object getObjectForWriteFdoca(CallableStatement cs,
                                          int index, int drdaType)
            throws SQLException {
        // convert to corresponding nullable type to reduce number of cases
        int ndrdaType = drdaType | 1;
        switch (ndrdaType) {
// GemStone changes BEGIN
            /* (original code)
            case DRDAConstants.DRDA_TYPE_NDATE:
                return cs.getDate(index, getGMTCalendar());
            case DRDAConstants.DRDA_TYPE_NTIME:
                return cs.getTime(index, getGMTCalendar());
            case DRDAConstants.DRDA_TYPE_NTIMESTAMP:
                return cs.getTimestamp(index, getGMTCalendar());
            */
// GemStone changes END
            case DRDAConstants.DRDA_TYPE_NLOBBYTES:
            case  DRDAConstants.DRDA_TYPE_NLOBCMIXED:
                return EXTDTAInputStream.getEXTDTAStream(cs, index, drdaType);
            default:
                return cs.getObject(index);
        }
    }

	/**
	 * Split QRYDTA into blksize chunks
	 *
	 * This routine is called if the QRYDTA data will not fit. It writes
	 * as much data as it can, then stores the remainder in the result
	 * set. At some later point, when the client returns with a CNTQRY,
	 * we will call processLeftoverQRYDTA to handle that data.
	 *
	 * The interaction between DRDAConnThread and DDMWriter is rather
	 * complicated here. This routine gets called because DRDAConnThread
	 * realizes that it has constructed a QRYDTA message which is too
	 * large. At that point, we need to reclaim the "extra" data and
	 * hold on to it. To aid us in that processing, DDMWriter provides
	 * the routines getDSSLength, copyDSSDataToEnd, and truncateDSS.
	 * For some additional detail on this complex sub-protocol, the
	 * interested reader should study bug DERBY-491 and 492 at:
	 * http://issues.apache.org/jira/browse/DERBY-491 and
	 * http://issues.apache.org/jira/browse/DERBY-492
	 *
	 * @param stmt DRDA statment
	 * @param blksize size of query block
	 * 
	 * @throws SQLException
     * @throws DRDAProtocolException
	 */
	private void splitQRYDTA(DRDAStatement stmt, int blksize) throws SQLException, 
			DRDAProtocolException
	{
		// make copy of extra data
		byte [] temp = writer.copyDSSDataToEnd(blksize);
		// truncate to end of blocksize
		writer.truncateDSS(blksize);
		if (temp.length == 0)
			agentError("LMTBLKPRC violation: splitQRYDTA was " +
				"called to split a QRYDTA block, but the " +
				"entire row fit successfully into the " +
				"current block. Server rowsize computation " +
				"was probably incorrect (perhaps an off-by-" +
				"one bug?). QRYDTA blocksize: " + blksize);
		stmt.setSplitQRYDTA(temp);
	}
	/**
	 * Process remainder data resulting from a split.
	 *
	 * This routine is called at the start of building each QRYDTA block.
	 * Normally, it observes that there is no remainder data from the
	 * previous QRYDTA block, and returns FALSE, indicating that there
	 * was nothing to do.
	 *
	 * However, if it discovers that the previous QRYDTA block was split,
	 * then it retrieves the remainder data from the result set, writes
	 * as much of it as will fit into the QRYDTA block (hopefully all of
	 * it will fit, but the row may be very long), and returns TRUE,
	 * indicating that this QRYDTA block has been filled with remainder
	 * data and should now be sent immediately.
	 */
	private boolean processLeftoverQRYDTA(DRDAStatement stmt)
		throws SQLException,DRDAProtocolException
	{
		byte []leftovers = stmt.getSplitQRYDTA();
		if (leftovers == null)
			return false;
		
		int blksize = stmt.getBlksize() > 0 ? stmt.getBlksize() : CodePoint.QRYBLKSZ_MAX;
		blksize = blksize - 10; //DSS header + QRYDTA and length
		if (leftovers.length < blksize)
		{
			writer.writeBytes(leftovers, 0, leftovers.length);
			stmt.setSplitQRYDTA(null);
		}
		else
		{
			writer.writeBytes(leftovers, 0, blksize);
			byte []newLeftovers = new byte[leftovers.length-blksize];
			for (int i = 0; i < newLeftovers.length; i++)
				newLeftovers[i] = leftovers[blksize+i];
			stmt.setSplitQRYDTA(newLeftovers);
		}
		// finish off query block and send
		writer.endDdmAndDss();
		return true;
	}


	/**
	 * Done data
	 * Send SQLCARD for the end of the data
	 * 
	 * @param stmt DRDA statement
	 * @param rs Result set
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
// GemStone changes BEGIN
	private void doneData(final DRDAStatement stmt, final ResultSet rs,
	    final boolean isScrollable)
	/* (original code)
	private void doneData(DRDAStatement stmt, ResultSet rs)
	*/
// GemStone changes END
			throws DRDAProtocolException, SQLException
	{
                stmt.setStatus("DONE RESULTS DATA");
		if (this.trace) 
			trace("*****NO MORE DATA!!");
		int blksize = stmt.getBlksize() > 0 ? stmt.getBlksize() : CodePoint.QRYBLKSZ_MAX;
		if (rs != null)
		{
// GemStone changes BEGIN
			if (isScrollable)
			/* (original code)
			if (stmt.isScrollable())
			*/
// GemStone changes END
			{
                                //keep isAfterLast and isBeforeFirst to be able 
                                //to reposition after counting rows
                                boolean isAfterLast = rs.isAfterLast();
                                boolean isBeforeFirst = rs.isBeforeFirst();
                                
				// for scrollable cursors - calculate the row count
				// since we may not have gone through each row
				rs.last();
				stmt.rowCount  = rs.getRow();

                                // reposition after last or before first
                                if (isAfterLast) {
                                    rs.afterLast();
                                }
                                if (isBeforeFirst) {
                                    rs.beforeFirst();
                                } 
			}
			else  // non-scrollable cursor
			{
				final boolean qryclsOnLmtblkprc =
					appRequester.supportsQryclsimpForLmtblkprc();
				if (stmt.isRSCloseImplicit(qryclsOnLmtblkprc)) {
					stmt.rsClose();
					stmt.rsSuspend();
				}
			 
			}
		}

		// For scrollable cursor's QRYSCRAFT, when we reach here, DRDA spec says sqlstate
		// is 00000, sqlcode is not mentioned.  But DB2 CLI code expects sqlcode to be 0.
		// We return sqlcode 0 in this case, as the DB2 server does.
		boolean isQRYSCRAFT = (stmt.getQryscrorn() == CodePoint.QRYSCRAFT);

        // Using sqlstate 00000 or 02000 for end of data.
                writeSQLCAGRP((isQRYSCRAFT ? eod00000 : eod02000),
                              (isQRYSCRAFT ? 0 : 100), 0, stmt.rowCount);
                
		writer.writeByte(CodePoint.NULLDATA);
		// does all this fit in one QRYDTA
		if (writer.getDSSLength() > blksize)
		{
			splitQRYDTA(stmt, blksize);
		}
	}
	/**
	 * Position cursor for insensitive scrollable cursors
	 *
	 * @param stmt	DRDA statement 
	 * @param rs	Result set
	 */
	private boolean positionCursor(DRDAStatement stmt, ResultSet rs) 
		throws SQLException, DRDAProtocolException
	{
		boolean retval = false;
		switch (stmt.getQryscrorn())
		{
			case CodePoint.QRYSCRREL:
                                int rows = (int)stmt.getQryrownbr();
                                if ((rs.isAfterLast() && rows > 0) || (rs.isBeforeFirst() && rows < 0)) {
                                    retval = false;
                                } else {
                                    retval = rs.relative(rows);
                                }
                                break;
			case CodePoint.QRYSCRABS:
				// JCC uses an absolute value of 0 which is not allowed in JDBC
				// We translate it into beforeFirst which seems to work.
				if (stmt.getQryrownbr() == 0)
				{
					rs.beforeFirst();
					retval = false;
				}
				else
				{
					retval = rs.absolute((int)stmt.getQryrownbr());
				}
				break;
			case CodePoint.QRYSCRAFT:
				rs.afterLast();
				retval = false;
				break;
			case CodePoint.QRYSCRBEF:
				rs.beforeFirst();
				retval = false;
				break;
			default:      
				agentError("Invalid value for cursor orientation "+ stmt.getQryscrorn());
		}
		return retval;
	}
	/**
	 * Write SQLDAGRP
	 * SQLDAGRP : EARLY FDOCA GROUP
	 * SQL Data Area Group Description
	 *
	 * FORMAT FOR SQLAM <= 6
	 *   SQLPRECISION; DRDA TYPE I2; ENVLID 0x04; Length Override 2
	 *   SQLSCALE; DRDA TYPE I2; ENVLID 0x04; Length Override 2
	 *   SQLLENGTH; DRDA TYPE I4; ENVLID 0x02; Length Override 4
	 *   SQLTYPE; DRDA TYPE I2; ENVLID 0x04; Length Override 2
	 *   SQLCCSID; DRDA TYPE FB; ENVLID 0x26; Length Override 2
	 *   SQLNAME_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 30
	 *   SQLNAME_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 30
	 *   SQLLABEL_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 30
	 *   SQLLABEL_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 30
	 *   SQLCOMMENTS_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 254
	 *   SQLCOMMENTS_m; DRDA TYPE VCS; ENVLID 0x32; Length Override 254
	 *
	 * FORMAT FOR SQLAM == 6
	 *   SQLPRECISION; DRDA TYPE I2; ENVLID 0x04; Length Override 2
	 *   SQLSCALE; DRDA TYPE I2; ENVLID 0x04; Length Override 2
	 *   SQLLENGTH; DRDA TYPE I8; ENVLID 0x16; Length Override 8
	 *   SQLTYPE; DRDA TYPE I2; ENVLID 0x04; Length Override 2
	 *   SQLCCSID; DRDA TYPE FB; ENVLID 0x26; Length Override 2
	 *   SQLNAME_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 30
	 *   SQLNAME_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 30
	 *   SQLLABEL_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 30
	 *   SQLLABEL_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 30
	 *   SQLCOMMENTS_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 254
	 *   SQLCOMMENTS_m; DRDA TYPE VCS; ENVLID 0x32; Length Override 254
	 *   SQLUDTGRP; DRDA TYPE N-GDA; ENVLID 0x51; Length Override 0
	 *
	 * FORMAT FOR SQLAM >= 7
	 *   SQLPRECISION; DRDA TYPE I2; ENVLID 0x04; Length Override 2
	 *   SQLSCALE; DRDA TYPE I2; ENVLID 0x04; Length Override 2
	 *   SQLLENGTH; DRDA TYPE I8; ENVLID 0x16; Length Override 8
	 *   SQLTYPE; DRDA TYPE I2; ENVLID 0x04; Length Override 2
	 *   SQLCCSID; DRDA TYPE FB; ENVLID 0x26; Length Override 2
	 *   SQLDOPTGRP; DRDA TYPE N-GDA; ENVLID 0xD2; Length Override 0
	 *
	 * @param rsmeta	resultset meta data
	 * @param pmeta		parameter meta data
	 * @param elemNum	column number we are returning (in case of result set), or,
	 *					parameter number (in case of parameter)
	 * @param rtnOutput	whether this is for a result set	
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void writeSQLDAGRP(ResultSetMetaData rsmeta,
							   ParameterMetaData pmeta,
							   int elemNum, boolean rtnOutput)
		throws DRDAProtocolException, SQLException
	{
		//jdbc uses offset of 1

		int jdbcElemNum = elemNum +1;
		// length to be retreived as output parameter
		int[]  outlen = {-1};  

		int elemType = rtnOutput ? rsmeta.getColumnType(jdbcElemNum) : pmeta.getParameterType(jdbcElemNum);

		int precision = rtnOutput ? rsmeta.getPrecision(jdbcElemNum) : pmeta.getPrecision(jdbcElemNum);
		if (precision > FdocaConstants.NUMERIC_MAX_PRECISION)
			precision = FdocaConstants.NUMERIC_MAX_PRECISION;

// GemStone changes BEGIN
		final int scale = (rtnOutput ? rsmeta.getScale(jdbcElemNum)
		    : pmeta.getScale(jdbcElemNum));
		// write precision/scale only for NUMERIC types
		if (elemType == Types.DECIMAL || elemType == Types.NUMERIC) {
		  writer.writeShort(precision);
		  writer.writeShort(scale);
		}
		else {
		  writer.writeShort(0);
		  writer.writeShort(0);
		}
		/* (original code)
		// 2-byte precision
		writer.writeShort(precision);
		// 2-byte scale
		int scale = (rtnOutput ? rsmeta.getScale(jdbcElemNum) : pmeta.getScale(jdbcElemNum));
		writer.writeShort(scale);
		*/
// GemStone changes END

		boolean nullable = rtnOutput ? (rsmeta.isNullable(jdbcElemNum) ==
										ResultSetMetaData.columnNullable) : 
			(pmeta.isNullable(jdbcElemNum) == JDBC30Translation.PARAMETER_NULLABLE);
		
		int sqlType = SQLTypes.mapJdbcTypeToDB2SqlType(elemType,
													   nullable, appRequester,
													   outlen);

		if (outlen[0] == -1) //some types not set
		{
			switch (elemType)
			{
				case Types.DECIMAL:
				case Types.NUMERIC:
// GemStone changes BEGIN
					/* (original code)
					scale = rtnOutput ? rsmeta.getScale(jdbcElemNum) : pmeta.getScale(jdbcElemNum);
					*/
// GeStone changes END
					outlen[0] = ((precision <<8) | (scale <<0));
					if (this.trace) 
						trace("\n\nprecision = " +precision +
						  " scale = " + scale);
					break;
				default:
					outlen[0] = Math.min(FdocaConstants.LONGVARCHAR_MAX_LEN,
										(rtnOutput ? rsmeta.getColumnDisplaySize(jdbcElemNum) :
												pmeta.getPrecision(jdbcElemNum)));
			}
		}

		switch (elemType)
		{
			case Types.BINARY:
			case Types.VARBINARY:
			case Types.LONGVARBINARY:
			case Types.BLOB:			//for CLI describe to be correct
			case Types.CLOB:
			case JDBC40Translation.JSON:
				outlen[0] = (rtnOutput ? rsmeta.getPrecision(jdbcElemNum) :
											pmeta.getPrecision(jdbcElemNum));
		}

		if (this.trace) 
// GemStone changes BEGIN
			trace("SQLDAGRP len = 0x" + Integer.toHexString(
			    outlen[0]) + " for type:" + elemType);
			/* (original code)
			trace("SQLDAGRP len = " + java.lang.Integer.toHexString(outlen[0]) + " for type:" + elemType);
			*/
// GemStone changes END

	   // 8 or 4 byte sqllength
		if (sqlamLevel >= MGRLVL_6)
			writer.writeLong(outlen[0]);
		else
			writer.writeInt(outlen[0]);

// GemStone changes BEGIN
		if (this.trace) {
		  String typeName = rtnOutput ? rsmeta.getColumnTypeName(
		      jdbcElemNum) : pmeta.getParameterTypeName(jdbcElemNum);
		  trace("jdbcType = " + typeName + " sqlType = "
		      + sqlType + " len = " + outlen[0]);
		/* (original code)
		String typeName = rtnOutput ? rsmeta.getColumnTypeName(jdbcElemNum) :
										pmeta.getParameterTypeName(jdbcElemNum);
		if (this.trace) 
			trace("jdbcType = " + typeName + " sqlType = " + sqlType + " len = " +outlen[0]);
		*/
		}
// GemStone changes END

		writer.writeShort(sqlType);

		// CCSID
		// CCSID should be 0 for Binary Types.
		
		if (elemType == java.sql.Types.CHAR ||
			elemType == java.sql.Types.VARCHAR
			|| elemType == java.sql.Types.LONGVARCHAR
			|| elemType == java.sql.Types.CLOB)
			writer.writeScalar2Bytes(1208);
		else
			writer.writeScalar2Bytes(0);

		if (sqlamLevel < MGRLVL_7) 
		{

			//SQLName
			writeVCMorVCS(rtnOutput ? rsmeta.getColumnName(jdbcElemNum) : null);
			//SQLLabel
			writeVCMorVCS(null);
			//SQLComments
			writeVCMorVCS(null);

			if (sqlamLevel == MGRLVL_6)
				writeSQLUDTGRP(rsmeta, pmeta, jdbcElemNum, rtnOutput);
		}
		else
		{
			writeSQLDOPTGRP(rsmeta, pmeta, jdbcElemNum, rtnOutput);
		}

	}

	/**
	 * Write variable character mixed byte or single byte
	 * The preference is to write mixed byte if it is defined for the server,
	 * since that is our default and we don't allow it to be changed, we always
	 * write mixed byte.
	 * 
	 * @param s	string to write
	 * @exception DRDAProtocolException
	 */
	private void writeVCMorVCS(String s)
		throws DRDAProtocolException
	{
		//Write only VCM and 0 length for VCS

		if (s == null)
		{
			writer.writeShort(0);
			writer.writeShort(0);
			return;
		}

		// VCM
		writer.writeLDString(s);
		// VCS
		writer.writeShort(0);
	}

  
	/**
	 * Write SQLUDTGRP (SQL Descriptor User-Defined Type Group Descriptor)
	 * 
	 * This is the format from the DRDA spec, Volume 1, section 5.6.4.10.
     * However, this format is not rich enough to carry the information needed
     * by JDBC. This format does not have a subtype code for JAVA_OBJECT and
     * this format does not convey the Java class name needed
     * by ResultSetMetaData.getColumnClassName().
	 *
	 *   SQLUDXTYPE; DRDA TYPE I4; ENVLID 0x02; Length Override 4
     *                        Constants which map to java.sql.Types constants DISTINCT, STRUCT, and REF.
     *                        But DRDA does not define a constant which maps to java.sql.Types.JAVA_OBJECT.
	 *   SQLUDTRDB; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
     *                       Database name.
	 *   SQLUDTSCHEMA_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 255
	 *   SQLUDTSCHEMA_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
     *                         Schema name. One of the above.
	 *   SQLUDTNAME_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 255
	 *   SQLUDTNAME_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
     *                         Unqualified UDT name. One of the above.
	 *
	 * Instead, we use the following format and only for communication between
     * Derby servers and Derby clients which are both at version 10.4 or higher.
     * For all other client/server combinations, we send null.
	 *
	 *   SQLUDTNAME_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 255
	 *   SQLUDTNAME_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
     *                         Fully qualified UDT name. One of the above.
	 *   SQLUDTCLASSNAME_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override FdocaConstants.LONGVARCHAR_MAX_LEN
	 *   SQLUDTCLASSNAME_s; DRDA TYPE VCS; ENVLID 0x32; Length Override FdocaConstants.LONGVARCHAR_MAX_LEN
     *                         Name of the Java class bound to the UDT. One of the above.
	 *
	 * @param rsmeta	resultset meta data
	 * @param pmeta		parameter meta data
	 * @param jdbcElemNum	column number we are returning (in case of result set), or,
	 *					parameter number (in case of parameter)
	 * @param rtnOutput	whether this is for a result set	
	 *
	 * @throws DRDAProtocolException
     * @throws SQLException
	 */
	private void writeSQLUDTGRP(ResultSetMetaData rsmeta,
								ParameterMetaData pmeta,
								int jdbcElemNum, boolean rtnOutput)
		throws DRDAProtocolException,SQLException
	{
        int jdbcType = rtnOutput ?
            rsmeta.getColumnType( jdbcElemNum) : pmeta.getParameterType( jdbcElemNum );

        if ( !(jdbcType == Types.JAVA_OBJECT) || !appRequester.supportsUDTs() )
        {
            writer.writeByte(CodePoint.NULLDATA);
            return;
        }
        
		String typeName = rtnOutput ?
            rsmeta.getColumnTypeName( jdbcElemNum ) : pmeta.getParameterTypeName( jdbcElemNum );
        String className = rtnOutput ?
            rsmeta.getColumnClassName( jdbcElemNum ) : pmeta.getParameterClassName( jdbcElemNum );
        
		writeVCMorVCS( typeName );
		writeVCMorVCS( className );
	}

	private void writeSQLDOPTGRP(ResultSetMetaData rsmeta,
								 ParameterMetaData pmeta,
								 int jdbcElemNum, boolean rtnOutput)
		throws DRDAProtocolException,SQLException
	{

		writer.writeByte(0);
		//SQLUNAMED
		writer.writeShort(0);
		//SQLName
		writeVCMorVCS(rtnOutput ? rsmeta.getColumnName(jdbcElemNum) : null);
		//SQLLabel
		writeVCMorVCS(null);
		//SQLComments
		writeVCMorVCS(null);
		//SQLDUDTGRP 
		writeSQLUDTGRP(rsmeta, pmeta, jdbcElemNum, rtnOutput);
		//SQLDXGRP
		writeSQLDXGRP(rsmeta, pmeta, jdbcElemNum, rtnOutput);
	}


	private void writeSQLDXGRP(ResultSetMetaData rsmeta,
							   ParameterMetaData pmeta,
							   int jdbcElemNum, boolean rtnOutput)
		throws DRDAProtocolException,SQLException
	{
		// Null indicator indicates we have data
		writer.writeByte(0);
		//   SQLXKEYMEM; DRDA TYPE I2; ENVLID 0x04; Length Override 2
		// Hard to get primary key info. Send 0 for now
// GemStone changes BEGIN
		// [sumedh] added primary key info for ADO.NET driver
		short pk = rtnOutput && rsmeta instanceof EmbedResultSetMetaData
		    ? ((EmbedResultSetMetaData)rsmeta).primaryKey(jdbcElemNum,
		        this.database.getConnection()) : 0;
		writer.writeShort(pk);
		//   SQLXUPDATEABLE; DRDA TYPE I2; ENVLID 0x04; Length Override 2
		writer.writeShort(rtnOutput ? !rsmeta.isReadOnly(jdbcElemNum)
		                            : false);
		/* (original code)
		writer.writeShort(0);
		//   SQLXUPDATEABLE; DRDA TYPE I2; ENVLID 0x04; Length Override 2
		writer.writeShort(rtnOutput ? rsmeta.isWritable(jdbcElemNum) : false);
		*/
// GemStone changes END

		//   SQLXGENERATED; DRDA TYPE I2; ENVLID 0x04; Length Override 2
		if (rtnOutput && rsmeta.isAutoIncrement(jdbcElemNum)) 
			writer.writeShort(2);
		else
			writer.writeShort(0);

		//   SQLXPARMMODE; DRDA TYPE I2; ENVLID 0x04; Length Override 2
		if (pmeta != null && !rtnOutput)
		{
			int mode = pmeta.getParameterMode(jdbcElemNum);
			if (mode ==  JDBC30Translation.PARAMETER_MODE_UNKNOWN)
			{
				// For old style callable statements. We assume in/out if it
				// is an output parameter.
				int type =  DRDAStatement.getOutputParameterTypeFromClassName(
																			  pmeta.getParameterClassName(jdbcElemNum));
				if (type != DRDAStatement.NOT_OUTPUT_PARAM)
					mode = JDBC30Translation.PARAMETER_MODE_IN_OUT;
			}
			writer.writeShort(mode);
		}
		else
		{
			writer.writeShort(0);
		}
	
		//   SQLXRDBNAM; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
		// JCC uses this as the catalog name so we will send null.
		writer.writeShort(0);

		//   SQLXCORNAME_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 255
		//   SQLXCORNAME_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
		writeVCMorVCS(null);

		//   SQLXBASENAME_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 255
		//   SQLXBASENAME_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
		writeVCMorVCS(rtnOutput ? rsmeta.getTableName(jdbcElemNum) : null);

		//   SQLXSCHEMA_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 255
		//   SQLXSCHEMA_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
		writeVCMorVCS(rtnOutput ? rsmeta.getSchemaName(jdbcElemNum): null);


		//   SQLXNAME_m; DRDA TYPE VCM; ENVLID 0x3E; Length Override 255
		//   SQLXNAME_s; DRDA TYPE VCS; ENVLID 0x32; Length Override 255
		writeVCMorVCS(rtnOutput ? rsmeta.getColumnName(jdbcElemNum): null);
		
	}

  /**
   * Write Fdoca Value to client 
   * @param index     Index of column being returned
   * @param val       Value to write to client
   * @param drdaType  FD:OCA DRDA Type from FdocaConstants
   * @param precision Precision
   * @param stmt       Statement being processed
   *
   * @exception DRDAProtocolException  
   * 
   * @exception SQLException
   *
   * @see FdocaConstants
   */

	protected void writeFdocaVal(int index, Object val, int drdaType,
								 int precision, int scale, boolean valNull,
								 
								 DRDAStatement stmt) throws DRDAProtocolException, SQLException
	{
		writeNullability(drdaType,valNull);

		if (! valNull)
		{
			int ndrdaType = drdaType | 1;
			long valLength = 0;
			switch (ndrdaType)
			{
			    case DRDAConstants.DRDA_TYPE_NSMALL:
 					// DB2 does not have a BOOLEAN java.sql.bit type,
					// so we need to send it as a small
 					if (val instanceof Boolean)
 					{
 						writer.writeShort(((Boolean) val).booleanValue());
 					}
 					else if (val instanceof Short)
 						writer.writeShort(((Short) val).shortValue());
 					else if (val instanceof Byte)
 						writer.writeShort(((Byte) val).byteValue());
					else
 						writer.writeShort(((Integer) val).shortValue());
					break;
				case  DRDAConstants.DRDA_TYPE_NINTEGER:
					writer.writeInt(((Integer) val).intValue());
					break;
				case DRDAConstants.DRDA_TYPE_NINTEGER8:
					writer.writeLong(((Long) val).longValue());
					break;
				case DRDAConstants.DRDA_TYPE_NFLOAT4:
					writer.writeFloat(((Float) val).floatValue());
					break;
				case DRDAConstants.DRDA_TYPE_NFLOAT8:
					writer.writeDouble(((Double) val).doubleValue());
					break;
				case DRDAConstants.DRDA_TYPE_NDECIMAL:
					if (precision == 0)
						precision = FdocaConstants.NUMERIC_DEFAULT_PRECISION;
					BigDecimal bd = (java.math.BigDecimal) val;
					writer.writeBigDecimal(bd,precision,scale);
					break;
				case DRDAConstants.DRDA_TYPE_NDATE:
					writer.writeString(((java.sql.Date) val).toString());
					break;
				case DRDAConstants.DRDA_TYPE_NTIME:
					writer.writeString(((java.sql.Time) val).toString());
					break;
				case DRDAConstants.DRDA_TYPE_NTIMESTAMP:
					// we need to send it in a slightly different format, and pad it
					// up to or truncate it into 26 chars
					String ts1 = ((java.sql.Timestamp) val).toString();
					String ts2 = ts1.replace(' ','-').replace(':','.');
					int tsLen = ts2.length();
					if (tsLen < 26)
					{
						for (int i = 0; i < 26-tsLen; i++)
							ts2 += "0";
					}
					else if (tsLen > 26)
						ts2 = ts2.substring(0,26);
					writer.writeString(ts2);
					break;
				case DRDAConstants.DRDA_TYPE_NCHAR:
// GemStone changes BEGIN
				case DRDAConstants.DRDA_TYPE_NMIX:
// GemStone changes END
					writer.writeString(((String) val).toString());
					break;
				case DRDAConstants.DRDA_TYPE_NVARCHAR:
				case DRDAConstants.DRDA_TYPE_NVARMIX:
				case DRDAConstants.DRDA_TYPE_NLONG:
				case DRDAConstants.DRDA_TYPE_NLONGMIX:
					//WriteLDString and generate warning if truncated
					// which will be picked up by checkWarning()
					writer.writeLDString(val.toString(), index);
					break;
				case DRDAConstants.DRDA_TYPE_NLOBBYTES:
				case DRDAConstants.DRDA_TYPE_NLOBCMIXED:

					// do not send EXTDTA for lob of length 0, beetle 5967
				    if( ! ((EXTDTAInputStream) val).isEmptyStream() ){
						stmt.addExtDtaObject(val, index);
				    
					//indicate externalized and size is unknown.
					writer.writeExtendedLength(0x8000);
					
				    }else{
					writer.writeExtendedLength(0);
					
				    }
				    
					break;
				    
				case  DRDAConstants.DRDA_TYPE_NFIXBYTE:
					writer.writeBytes((byte[]) val);
					break;
				case DRDAConstants.DRDA_TYPE_NVARBYTE:
				case DRDAConstants.DRDA_TYPE_NLONGVARBYTE:
						writer.writeLDBytes((byte[]) val, index);
					break;
				case DRDAConstants.DRDA_TYPE_NLOBLOC:
// GemStone changes BEGIN
					if (val instanceof EngineLOB) {
					  writer.writeInt(((EngineLOB)val).getLocator());
					}
					else {
					  WrapperEngineBLOB blob = new WrapperEngineBLOB(
					      database.getConnection(), (Blob)val);
					  writer.writeInt(blob.getLocator());
					}
					break;
				case DRDAConstants.DRDA_TYPE_NCLOBLOC:
					if (val instanceof EngineLOB) {
					  writer.writeInt(((EngineLOB)val).getLocator());
					}
					else {
					  if (val instanceof String) {
					    val = new HarmonySerialClob((String)val);
					  }
					  WrapperEngineCLOB clob = new WrapperEngineCLOB(
					      database.getConnection(), (Clob)val);
					  writer.writeInt(clob.getLocator());
					}
					break;
				/* (original code)
				case DRDAConstants.DRDA_TYPE_NCLOBLOC:
					writer.writeInt(((EngineLOB)val).getLocator());
					break;
				*/
// GemStone changes END
				case DRDAConstants.DRDA_TYPE_NUDT:
					writer.writeUDT( val, index );
					break;
				default:
					if (this.trace) 
						trace("ndrdaType is: "+ndrdaType);
					writer.writeLDString(val.toString(), index);
			}
		}
	}

	/**
	 * write nullability if this is a nullable drdatype and FDOCA null
	 * value if appropriate
	 * @param drdaType      FDOCA type
	 * @param valNull       true if this is a null value. False otherwise
	 * 
	 **/
	private void writeNullability(int drdaType, boolean valNull)
	{
		if(FdocaConstants.isNullable(drdaType))
		{
			if (valNull)
				writer.writeByte(FdocaConstants.NULL_DATA);
			else
			{
				writer.writeByte(FdocaConstants.INDICATOR_NULLABLE);
			}
		}
		
	}

	/**
	 * Methods to keep track of required codepoints
	 */
	/**
	 * Copy a list of required code points to template for checking
	 *
	 * @param req list of required codepoints
	 */
	private void copyToRequired(int [] req)
	{
		currentRequiredLength = req.length;
		if (currentRequiredLength > required.length)
			required = new int[currentRequiredLength];
		for (int i = 0; i < req.length; i++)
			required[i] = req[i];
	}
	/**
	 * Remove codepoint from required list
 	 *
	 * @param codePoint - code point to be removed
	 */
	private void removeFromRequired(int codePoint)
	{
		for (int i = 0; i < currentRequiredLength; i++)
			if (required[i] == codePoint)
				required[i] = 0;

	}
	/**
	 * Check whether we have seen all the required code points
	 *
	 * @param codePoint code point for which list of code points is required
	 */
	private void checkRequired(int codePoint) throws DRDAProtocolException
	{
		int firstMissing = 0;
		for (int i = 0; i < currentRequiredLength; i++)
		{
			if (required[i] != 0)
			{
				firstMissing = required[i];
				break;
			}
		}
		if (firstMissing != 0)
			missingCodePoint(firstMissing);
	}
	/**
	 * Error routines
	 */
	/**
	 * Seen too many of this code point
	 *
	 * @param codePoint  code point which has been duplicated
	 *
	 * @exception DRDAProtocolException
	 */
	private void tooMany(int codePoint) throws DRDAProtocolException
	{
		throwSyntaxrm(CodePoint.SYNERRCD_TOO_MANY, codePoint);
	}
	/**
	 * Object too big
	 *
	 * @param codePoint  code point with too big object
	 * @exception DRDAProtocolException
	 */
	private void tooBig(int codePoint) throws DRDAProtocolException
	{
		throwSyntaxrm(CodePoint.SYNERRCD_TOO_BIG, codePoint);
	}
	/**
	 * Object length not allowed
	 *
	 * @param codePoint  code point with bad object length
	 * @exception DRDAProtocolException
	 */
	private void badObjectLength(int codePoint) throws DRDAProtocolException
	{
		throwSyntaxrm(CodePoint.SYNERRCD_OBJ_LEN_NOT_ALLOWED, codePoint);
	}
	/**
	 * RDB not found
	 *
	 * @param rdbnam  name of database
	 * @exception DRDAProtocolException
	 */
	private void rdbNotFound(String rdbnam) throws DRDAProtocolException
	{
		Object[] oa = {rdbnam};
		throw new
			DRDAProtocolException(DRDAProtocolException.DRDA_Proto_RDBNFNRM,
								  this,0,
								  DRDAProtocolException.NO_ASSOC_ERRCD, oa);
	}
	/**
	 * Invalid value for this code point
	 *
	 * @param codePoint  code point value
	 * @exception DRDAProtocolException
	 */
	private void invalidValue(int codePoint) throws DRDAProtocolException
	{
		throwSyntaxrm(CodePoint.SYNERRCD_REQ_VAL_NOT_FOUND, codePoint);
	}
	/**
	 * Invalid codepoint for this command
	 *
	 * @param codePoint code point value
	 *
	 * @exception DRDAProtocolException
	 */
	protected void invalidCodePoint(int codePoint) throws DRDAProtocolException
	{
		throwSyntaxrm(CodePoint.SYNERRCD_INVALID_CP_FOR_CMD, codePoint);
	}
	/**
	 * Don't support this code point
	 *
	 * @param codePoint  code point value
	 * @exception DRDAProtocolException
	 */
	protected void codePointNotSupported(int codePoint) throws DRDAProtocolException
	{
		throw new
			DRDAProtocolException(DRDAProtocolException.DRDA_Proto_CMDNSPRM,
								  this,codePoint,
								  DRDAProtocolException.NO_ASSOC_ERRCD);
	}
	/**
	 * Don't support this value
	 *
	 * @param codePoint  code point value
	 * @exception DRDAProtocolException
	 */
	private void valueNotSupported(int codePoint) throws DRDAProtocolException
	{
		throw new
			DRDAProtocolException(DRDAProtocolException.DRDA_Proto_VALNSPRM,
								  this,codePoint,
								  DRDAProtocolException.NO_ASSOC_ERRCD);
	}
	/**
	 * Verify that the code point is the required code point
	 *
	 * @param codePoint code point we have
	 * @param reqCodePoint code point required at this time
 	 *
	 * @exception DRDAProtocolException
	 */
	private void verifyRequiredObject(int codePoint, int reqCodePoint)
		throws DRDAProtocolException
	{
		if (codePoint != reqCodePoint )
		{
			throwSyntaxrm(CodePoint.SYNERRCD_REQ_OBJ_NOT_FOUND,codePoint);
		}
	}
	/**
	 * Verify that the code point is in the right order
	 *
	 * @param codePoint code point we have
	 * @param reqCodePoint code point required at this time
 	 *
	 * @exception DRDAProtocolException
	 */
	private void verifyInOrderACCSEC_SECCHK(int codePoint, int reqCodePoint)
		throws DRDAProtocolException
	{
		if (codePoint != reqCodePoint )
		{
			throw
			    new DRDAProtocolException(DRDAProtocolException.DRDA_Proto_PRCCNVRM,
										  this, codePoint,
										  CodePoint.PRCCNVCD_ACCSEC_SECCHK_WRONG_STATE);
		}
	}

	/**
	 * Database name given under code point doesn't match previous database names
	 *
	 * @param codePoint codepoint where the mismatch occurred
 	 *
	 * @exception DRDAProtocolException
	 */
	private void rdbnamMismatch(int codePoint)
		throws DRDAProtocolException
	{

		throw new DRDAProtocolException(DRDAProtocolException.DRDA_Proto_PRCCNVRM,
										  this, codePoint,
										  CodePoint.PRCCNVCD_RDBNAM_MISMATCH);
	}
	/**
	 * Close the current session
	 */
	private void closeSession()
	{
		if (session == null)
			return;

        /* DERBY-2220: Rollback the current XA transaction if it is
           still associated with the connection. */
        if (xaProto != null)
            xaProto.rollbackCurrentTransaction();

		server.removeFromSessionTable(session.connNum);
		try {
			session.close();
		} catch (SQLException se)
		{
			// If something went wrong closing down the session.
			// Print an error to the console and close this 
			//thread. (6013)
			sendUnexpectedException(se);
			close();
		}
// GemStone changes BEGIN
		catch (Exception e) {
		  sendUnexpectedException(e);
		  close();
		}
// GemStone changes END
		finally {
			session = null;
			database = null;
			appRequester=null;
			sockis = null;
			sockos=null;
			databaseAccessException=null;
		}
	}

	/**
	 * Handle Exceptions - write error protocol if appropriate and close session
	 *	or thread as appropriate
	 */
	private void handleException(Exception e)
	{
	  if (SanityManager.TraceSingleHop) {
            SanityManager.DEBUG_PRINT(
                SanityManager.TRACE_SINGLE_HOP,
                "DRDAConnThread::handleException sending exception: " + e, e);
          }
		try {
			if (e instanceof DRDAProtocolException) {
				// protocol error - write error message
				sendProtocolException((DRDAProtocolException) e);
			} else {
				// something unexpected happened
				sendUnexpectedException(e);
				server.consoleExceptionPrintTrace(e);
			}
		} finally {
			// always close the session and stop the thread after handling
			// these exceptions
			closeSession();
			close();
		}
	}
	
	/**
	 * Notice the client about a protocol error.
	 *
	 * @param de <code>DRDAProtocolException</code> to be sent
	 */
	private void sendProtocolException(DRDAProtocolException de) {
		String dbname = null;
		if (database != null) {
			dbname = database.dbName;
		}

		try {
			println2Log(dbname, session.drdaID, de.getMessage());
			server.consoleExceptionPrintTrace(de);
			reader.clearBuffer();
			de.write(writer);
			finalizeChain();
		} catch (DRDAProtocolException ioe) {
			// There may be an IO exception in the write.
			println2Log(dbname, session.drdaID, de.getMessage());
			server.consoleExceptionPrintTrace(ioe);
		}
	}

	/**
	 * Send unpexpected error to the client
	 * @param e Exception to be sent
	 */
	private void sendUnexpectedException(Exception e)
	{

		DRDAProtocolException unExpDe;
		String dbname = null;
		try {
			if (database != null)
				dbname = database.dbName;
			println2Log(dbname,session.drdaID, e.getMessage());
			server.consoleExceptionPrintTrace(e);
			unExpDe = DRDAProtocolException.newAgentError(this,
														  CodePoint.SVRCOD_PRMDMG,  
														  dbname, e.getMessage());
		
			reader.clearBuffer();
			unExpDe.write(writer);
			finalizeChain();
		}
		catch (DRDAProtocolException nde) 
		{
			// we can't tell the client, but we tried.
		}
		
	}


	/**
	 * Test if DRDA connection thread is closed
	 *
	 * @return true if close; false otherwise
	 */
	private boolean closed()
	{
		synchronized (closeSync)
		{
			return close;
		}
	}
	/**
	 * Get whether connections are logged
	 *
	 * @return true if connections are being logged; false otherwise
	 */
	private boolean getLogConnections()
	{
		synchronized(logConnectionsSync) {
			return logConnections;
		}
	}
	/**
	 * Get time slice value for length of time to work on a session
	 *
	 * @return time slice
	 */
	private long getTimeSlice()
	{
		synchronized(timeSliceSync) {
			return timeSlice;
		}
	}
	/**
	 * Send string to console
	 *
	 * @param value - value to print on console
	 */
	protected  void trace(String value)
	{
		if (this.trace)
			server.consoleMessage(value);
	}

	/***
	 * Show runtime memory
	 *
	 ***/
// GemStone changes BEGIN
        @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="DM_GC")
// GemStone changes END
	public static void showmem() {
		Runtime rt = null;
		Date d = null;
		rt = Runtime.getRuntime();
		rt.gc();
		d = new Date();
		System.out.println("total memory: "
						   + rt.totalMemory()
						   + " free: "
						   + rt.freeMemory()
						   + " " + d.toString());

	}

	/**
	 * convert byte array to a Hex string
	 * 
	 * @param buf buffer to  convert
	 * @return hex string representation of byte array
	 */
	private String convertToHexString(byte [] buf)
	{
		StringBuilder str = new StringBuilder();
		str.append("0x");
		String val;
		int byteVal;
		for (int i = 0; i < buf.length; i++)
		{
			byteVal = buf[i] & 0xff;
			val = Integer.toHexString(byteVal);
			if (val.length() < 2)
				str.append("0");
			str.append(val);
		}
		return str.toString();
	}
	/**
	 * check that the given typdefnam is acceptable
	 * 
	 * @param typdefnam 
 	 *
 	 * @exception DRDAProtocolException
	 */
	private void checkValidTypDefNam(String typdefnam)
		throws DRDAProtocolException
	{
		if (typdefnam.equals("QTDSQL370"))
			return;
		if (typdefnam.equals("QTDSQL400"))
			return;
		if (typdefnam.equals("QTDSQLX86"))
			return;
		if (typdefnam.equals("QTDSQLASC"))
			return;
		if (typdefnam.equals("QTDSQLVAX"))
			return;
		if (typdefnam.equals("QTDSQLJVM"))
			return;
		invalidValue(CodePoint.TYPDEFNAM);
	}
	/**
	 * Check that the length is equal to the required length for this codepoint
	 *
	 * @param codepoint	codepoint we are checking
	 * @param reqlen	required length
	 * 
 	 * @exception DRDAProtocolException
	 */
	private void checkLength(int codepoint, int reqlen)
		throws DRDAProtocolException
	{
		long len = reader.getDdmLength();
		if (len < reqlen)
			badObjectLength(codepoint);
		else if (len > reqlen)
			tooBig(codepoint);
	}
	/**
	 * Read and check a boolean value
	 * 
	 * @param codepoint codePoint to be used in error reporting
	 * @return true or false depending on boolean value read
	 *
	 * @exception DRDAProtocolException
	 */
	private boolean readBoolean(int codepoint) throws DRDAProtocolException
	{
		checkLength(codepoint, 1);
		byte val = reader.readByte();
		if (val == CodePoint.TRUE)
			return true;
		else if (val == CodePoint.FALSE)
			return false;
		else
			invalidValue(codepoint);
		return false;	//to shut the compiler up
	}
	/**
	 * Add a database to the current session
	 *
	 */
	private void addDatabase(String dbname)
	{
		Database db;
		if (appRequester.isXARequester())
		{
			db = new XADatabase(dbname);
		}
		else
			db = new Database(dbname);
		session.addDatabase(db);
		session.database = db;
		database = db;
	}
	/**
	 * Set the current database
	 * 
	 * @param codePoint 	codepoint we are processing
	 *
	 * @exception DRDAProtocolException
	 */
	private void setDatabase(int codePoint) throws DRDAProtocolException
	{
		String rdbnam = parseRDBNAM();
		// using same database so we are done
		if (database != null && database.dbName.equals(rdbnam))
			return;
		Database d = session.getDatabase(rdbnam);
		if (d == null)
			rdbnamMismatch(codePoint);
		else
			database = d;
		session.database = d;
	}
	/**
	 * Write ENDUOWRM
	 * Instance Variables
	 *  SVCOD - severity code - WARNING - required
	 *  UOWDSP - Unit of Work Disposition - required
	 *  RDBNAM - Relational Database name - optional
	 *  SRVDGN - Server Diagnostics information - optional
	 *
	 * @param opType - operation type 1 - commit, 2 -rollback
	 */
	private void writeENDUOWRM(int opType)
	{
		writer.createDssReply();
		writer.startDdm(CodePoint.ENDUOWRM);
		writer.writeScalar2Bytes(CodePoint.SVRCOD, CodePoint.SVRCOD_WARNING);
		writer.writeScalar1Byte(CodePoint.UOWDSP, opType);
// GemStone changes BEGIN
		// KN: TODO place where new TXId can be sent//KNKNKNKNKN
		// Do similar thing in rollback
		if (SanityManager.TraceClientHA | SanityManager.TraceSingleHop) {
		        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
		            "DRDAConnThread:writeENDUOWRM gfxdClientVersion = " + this.gfxdClientVersion);
		      }
		if (this.gfxdClientVersion != null) {
		  if (!SEND_TX_ID_ON_COMMIT || !startNewTransactionAndWriteNewTXID()) {
		    writer.writeByte(ClientSharedData.CLIENT_TXID_NOT_WRITTEN);
		  }
		}
// GemStone changes END
    	writer.endDdmAndDss();
	}
	
// GemStone changes BEGIN
  // Temporary system props to not let other tests fail unless full failover and
  // client as txn co-ordinator and savepoint related work is done and tested.
  public static final boolean SEND_TX_ID_ON_COMMIT = SystemProperties
        .getServerInstance().getBoolean(
        "gemfirexd.send-tx-id-on-commit", false);
  
  public static final boolean ROLLBACK_IMPLEMENTED_FULLY = SystemProperties
      .getServerInstance().getBoolean("gemfirexd.rollback-implemented", false);

  private boolean startNewTransactionAndWriteNewTXID() {
    EngineConnection conn = database.getConnection();
    if (conn != null) {
      LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
      int isolationLevel = lcc.getCurrentIsolationLevel();
      GemFireTransaction gft = (GemFireTransaction)conn
          .getLanguageConnectionContext().getTransactionExecute();
      TXId txid = gft.getNewTXId(true);
      writer.writeByte(ClientSharedData.CLIENT_TXID_WRITTEN);
      writer.writeLong(txid.getMemberId());
      writer.writeLong(txid.getUniqId());
      if (SanityManager.TraceClientHA | SanityManager.TraceSingleHop) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
            "DRDAConnThread:startNewTransactionAndWriteNewTXID new txid sent: " + txid);
      }
      return true;
    }
    return false;
  }
// GemStone changes END

  void writeEXTDTA (DRDAStatement stmt) throws SQLException, DRDAProtocolException
  {
	  ArrayList extdtaValues = stmt.getExtDtaObjects();
    // build the EXTDTA data, if necessary
    if (extdtaValues == null) 
		return;
	boolean chainFlag, chainedWithSameCorrelator;
	boolean writeNullByte = false;

	for (int i = 0; i < extdtaValues.size(); i++) {
        // is this the last EXTDTA to be built?
        if (i != extdtaValues.size() - 1) { // no
			chainFlag = true;
			chainedWithSameCorrelator = true;
        }
        else { // yes
			chainFlag = false; //last blob DSS stream itself is NOT chained with the NEXT DSS
			chainedWithSameCorrelator = false;
        }

		
		if (sqlamLevel >= MGRLVL_7)
			if (stmt.isExtDtaValueNullable(i))
				writeNullByte = true;
		
		Object o  = extdtaValues.get(i);
        if (o instanceof EXTDTAInputStream) {
			EXTDTAInputStream stream = (EXTDTAInputStream) o;
                        
			try{
                        stream.initInputStream();
			writer.writeScalarStream (chainedWithSameCorrelator,
									  CodePoint.EXTDTA,
									  stream,
									  writeNullByte);
			
			}finally{
				// close the stream when done
			    closeStream(stream);
        }
			
		}
	}
	// reset extdtaValues after sending
	stmt.clearExtDtaObjects();

  }


	/**
	 * Check SQLWarning and write SQLCARD as needed.
	 * 
	 * @param conn 		connection to check
	 * @param stmt 		statement to check
	 * @param rs 		result set to check
	 * @param updateCount 	update count to include in SQLCARD
	 * @param alwaysSend 	whether always send SQLCARD regardless of
	 *						the existance of warnings
	 * @param sendWarn 	whether to send any warnings or not. 
	 *
	 * @exception DRDAProtocolException
	 */
	private void checkWarning(Connection conn, Statement stmt, ResultSet rs,
						  int updateCount, boolean alwaysSend, boolean sendWarn)
		throws DRDAProtocolException, SQLException
	{
// GemStone changes BEGIN
	  checkWarning(conn, stmt, rs, updateCount, null, alwaysSend, sendWarn);
	}

	private void checkWarning(Connection conn, Statement stmt, ResultSet rs,
            int updateCount, int[] updateCounts, boolean alwaysSend,
            boolean sendWarn) throws DRDAProtocolException, SQLException {
// GemStone changes END
		// instead of writing a chain of sql warning, we send the first one, this is
		// jcc/db2 limitation, see beetle 4629
		SQLWarning warning = null;
		SQLWarning reportWarning = null;
		try
		{
			if (stmt != null)
			{
				warning = stmt.getWarnings();
				if (warning != null)
				{
					stmt.clearWarnings();
					reportWarning = warning;
				}
			}
			if (rs != null)
			{
				warning = rs.getWarnings();
				if (warning != null)
				{
					rs.clearWarnings();
					if (reportWarning == null)
						reportWarning = warning;
				}
			}
			if (conn != null)
			{
				warning = conn.getWarnings();
				if (warning != null)
				{
					conn.clearWarnings();
					if (reportWarning == null)
						reportWarning = warning;
				}
			}
			
		}
		catch (SQLException se)
		{
			if (this.trace) 
				trace("got SQLException while trying to get warnings.");
		}


		if ((alwaysSend || reportWarning != null) && sendWarn)
// GemStone changes BEGIN
		{
			if (updateCounts == null) {
			  writeSQLCARDs(reportWarning, updateCount);
			}
			else {
			  final int lastIdx = updateCounts.length - 1;
			  if (lastIdx >= 0) {
			    final OutputStream os = getOutputStream();
			    for (int index = 0; index < lastIdx; index++) {
			      writeSQLCARDs(reportWarning, updateCounts[index]);
			      // need to finalize the chain but need to assume
			      // chained messages for last but one
			      writer.finalizeChain((byte)DssConstants.DSSCHAIN,
			          os);
			    }
			    writeSQLCARDs(reportWarning, updateCounts[lastIdx]);
			    final DRDAStatement curStmt =
			        database.getCurrentStatement();
			    if (curStmt != null) {
			      curStmt.rsSuspend();
			    }
			    writePBSD();
			    writer.finalizeChain(reader.getCurrChainState(),
			        os);
			  }
			}
		}
		/* (original code)
			writeSQLCARDs(reportWarning, updateCount);
		*/
// GemStone changes END
	}

    boolean hasSession() {
        return session != null;
    }
    
    long getBytesRead() {
        return reader.totalByteCount;
    }

    long getBytesWritten() {
        return writer.totalByteCount;
    }

	protected String buildRuntimeInfo(String indent, LocalizedResource localLangUtil )
	{
		String s ="";
		if (!hasSession())
			return s;
		else
			s += session.buildRuntimeInfo("", localLangUtil);
		s += "\n";
		return s;
	}


	/**
	 * Finalize the current DSS chain and send it if
	 * needed.
	 */
	private void finalizeChain() throws DRDAProtocolException {
// GemStone changes BEGIN
          EngineConnection econn = this.database != null ? this.database
              .getConnection() : null;
          if (econn != null) {
            LanguageConnectionContext lcc = econn.getLanguageConnectionContext();
            if (lcc != null) {
              // Setting this to null after every execution so that a bucket set of
              // previous
              // execution is not used by the next statement which will use this same
              // conn and hence lcc
              if (SanityManager.TraceSingleHop) {
                SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                    "DRDAConnThread::finalizeChain resetting bucketSet in lcc: "
                        + lcc + " to null and send single hop info to false");
              }
              lcc.setExecuteLocally(null, null, false, null);
              lcc.setSendSingleHopInformation(false);
            }
          }
// GemStone changes END
		writer.finalizeChain(reader.getCurrChainState(), getOutputStream());
		return;

	}

    /**
     *  Validate SECMEC_USRSSBPWD (Strong Password Substitute) can be used as
     *  DRDA security mechanism.
     *
     *	Here we check that the target server can support SECMEC_USRSSBPWD
     *	security mechanism based on the environment, application
     *	requester's identity (PRDID) and connection URL.
     *
     *	IMPORTANT NOTE:
     *	--------------
     *	SECMEC_USRSSBPWD is ONLY supported by the target server if:
     *	    - current authentication provider is Derby BUILTIN or
     *	      NONE. (database / system level) (Phase I)
     *		- Application requester is 'DNC' (Derby Network Client)
     *		  (Phase I)
     *
     *  @return security check code - 0 if everything O.K.
     */
    private int validateSecMecUSRSSBPWD() throws  DRDAProtocolException
    {
        String dbName = null;
        AuthenticationService authenticationService = null;
        com.pivotal.gemfirexd.internal.iapi.db.Database databaseObj = null;
        String srvrlslv = appRequester.srvrlslv;

        // Check if application requester is the Derby Network Client (DNC)
        //
        // We use a trick here - as the product ID is not yet available
        // since ACCRDB message is only coming later, we check the server
        // release level field sent as part of the initial EXCSAT message;
        // indeed, the product ID (PRDID) is prefixed to in the field.
        // Derby always sets it as part of the EXCSAT message so if it is
        // not available, we stop here and inform the requester that
        // SECMEC_USRSSBPWD cannot be supported for this connection.
        if ((srvrlslv == null) || (srvrlslv.length() == 0) ||
            (srvrlslv.length() < CodePoint.PRDID_MAX) ||
            (srvrlslv.indexOf(DRDAConstants.DERBY_DRDA_CLIENT_ID)
                    == -1))
            return CodePoint.SECCHKCD_NOTSUPPORTED; // Not Supported


        // Client product version is extracted from the srvrlslv field.
        // srvrlslv has the format <PRDID>/<ALTERNATE VERSION FORMAT>
        // typically, a known Derby client has a four part version number
        // with a pattern such as DNC10020/10.2.0.3 alpha. If the alternate
        // version format is not specified, clientProductVersion_ will just
        // be set to the srvrlslvl. Final fallback will be the product id.
        //
        // SECMEC_USRSSBPWD is only supported by the Derby engine and network
        // server code starting at version major '10' and minor '02'. Hence,
        // as this is the same for the derby client driver, we need to ensure
        // our DNC client is at version and release level of 10.2 at least.
        // We set the client version in the application requester and check
        // if it is at the level we require at a minimum.
        appRequester.setClientVersion(
                srvrlslv.substring(0, (int) CodePoint.PRDID_MAX));

        if (appRequester.supportsSecMecUSRSSBPWD() == false)
            return CodePoint.SECCHKCD_NOTSUPPORTED; // Not Supported

        dbName = database.shortDbName;
        // Check if the database is available (booted)
        // 
        // First we need to have the database name available and it should
        // have been set as part of the ACCSEC request (in the case of a Derby
        // 'DNC' client)
        if ((dbName == null) || (dbName.length() == 0))
        {
            // No database specified in the connection URL attributes
            //
            // In this case, we get the authentication service handle from the
            // local driver, as the requester may simply be trying to shutdown
            // the engine.
            authenticationService = ((InternalDriver)
              NetworkServerControlImpl.getDriver()).getAuthenticationService();
        }
        else
        {
            // We get the authentication service from the database as this
            // last one might have specified its own auth provider (at the
            // database level).
            // 
            // if monitor is never setup by any ModuleControl, getMonitor
            // returns null and no Derby database has been booted. 
            if (Monitor.getMonitor() != null)
                databaseObj = (com.pivotal.gemfirexd.internal.iapi.db.Database)
                    Monitor.findService(Property.DATABASE_MODULE, dbName);

            if (databaseObj == null)
            {
                // If database is not found, try connecting to it. 
                database.makeDummyConnection();

                // now try to find it again
                databaseObj = (com.pivotal.gemfirexd.internal.iapi.db.Database)
                    Monitor.findService(Property.DATABASE_MODULE, dbName);
            }

            // If database still could not be found, it means the database
            // does not exist - we just return security mechanism not
            // supported down below as we could not verify we can handle
            // it.
            try {
                if (databaseObj != null)
                    authenticationService =
                        databaseObj.getAuthenticationService();
            } catch (StandardException se) {
                println2Log(null, session.drdaID, se.getMessage());
                // Local security service non-retryable error.
                return CodePoint.SECCHKCD_0A;
            }

        }

        // Now we check if the authentication provider is NONE or BUILTIN
        if (authenticationService != null)
        {
            String authClassName = authenticationService.getClass().getName();
                
            if (!authClassName.equals(AUTHENTICATION_PROVIDER_BUILTIN_CLASS) &&
                !authClassName.equals(AUTHENTICATION_PROVIDER_NONE_CLASS))
                return CodePoint.SECCHKCD_NOTSUPPORTED; // Not Supported
        }

        // SECMEC_USRSSBPWD target initialization
        try {
            myTargetSeed = decryptionManager.generateSeed();
            database.secTokenOut = myTargetSeed;
        } catch (SQLException se) {
            println2Log(null, session.drdaID, se.getMessage());
            // Local security service non-retryable error.
           return CodePoint.SECCHKCD_0A;
        }
                                
        return 0; // SECMEC_USRSSBPWD is supported
    }
    
    private static int peekStream(EXTDTAInputStream is) throws IOException{
	
	is.mark(1);

	try{
	    return is.read();
	    
	}finally{
	    is.reset();
	}
	
    }
    
    
    private static void closeStream(InputStream stream){
	
	try{
	    if (stream != null)
		stream.close();
	    
	} catch (IOException e) {
	    Util.javaException(e);
	    
	}
	
    }
    
    
    private static ByteArrayInputStream 
        convertAsByteArrayInputStream( EXTDTAReaderInputStream stream )
        throws IOException {
        
        final int byteArrayLength = 
            stream instanceof StandardEXTDTAReaderInputStream ?
            (int) ( ( StandardEXTDTAReaderInputStream ) stream ).getLength() : 
            32;// default length
        
        PublicBufferOutputStream pbos = 
            new PublicBufferOutputStream( byteArrayLength );
        
        byte[] buffer = new byte[32 * 1024];
        
        int c = 0;
        
        while( ( c = stream.read( buffer,
                                  0,
                                  buffer.length ) ) > -1 ) {
            pbos.write( buffer, 0, c );
        }

        return new ByteArrayInputStream( pbos.getBuffer(),
                                         0, 
                                         pbos.getCount() );

    }
    
    
    private static class PublicBufferOutputStream extends ByteArrayOutputStream{
        
        PublicBufferOutputStream(int size){
            super(size);
        }
        
        public byte[] getBuffer(){
            return buf;
        }
        
        public int getCount(){
            return count;
        }
        
    }
    
    private static void setAsCharacterStream(DRDAStatement stmt,
                                             int i,
                                             boolean checkNullability,
                                             DDMReader reader,
                                             boolean streamLOB,
                                             String encoding) 
        throws DRDAProtocolException ,
               SQLException ,
               IOException {
        PreparedStatement ps = stmt.getPreparedStatement();
// GemStone changes BEGIN
        /* (original code)
        EnginePreparedStatement engnps = 
            ( EnginePreparedStatement ) ps;
        */
// GemStone changes END
        
        final EXTDTAReaderInputStream extdtastream = 
            reader.getEXTDTAReaderInputStream(checkNullability);
        // DERBY-3085. Save the stream so it can be drained later
        // if not  used.
        if (streamLOB)
            stmt.setStreamedParameter(extdtastream);
        
        final InputStream is = 
            streamLOB ?
            (InputStream) extdtastream :
            convertAsByteArrayInputStream( extdtastream );
        
        final InputStreamReader streamReader = 
            new InputStreamReader( is,
                                   encoding ) ;
        
// GemStone changes BEGIN
        ps.setCharacterStream(i + 1, streamReader);
        /* (original code)
        engnps.setCharacterStream( i + 1, 
                                   streamReader );
        */
// GemStone changes END
    }

// GemStone changes BEGIN
    // Returns how many bytes it read when matching magic bytes
    // (which will only happen successfully against GFXD client >= 1.3)
    private int checkPRDDTAForGFXDClientMagic(final int prdlen)
        throws DRDAProtocolException {
      final int lenOfMagic = ClientSharedData.BYTES_PREFIX_CLIENT_VERSION_LENGTH;
      if (prdlen < lenOfMagic) {
        return 0;
      }
      for (int i = 0; i < lenOfMagic; i++) {
        byte b = reader.readByte();
        if (b != ClientSharedData.BYTES_PREFIX_CLIENT_VERSION[i]) {
          return (i + 1);
        }
      }
      return lenOfMagic;
    }

    public long getAndResetBytesRead() {
      return readAndResetAtomicLong(this.reader.bytesRead);
    }

    public long getAndResetBytesWritten() {
      return readAndResetAtomicLong(this.writer.bytesWritten);
    }

    public synchronized void getAndResetConnActivityStats(final long[] stats) {

      assert stats.length == 5: "Atleast five statistics is to be returned.";

      stats[0] = beginWaitMillis;
      stats[1] = readAndResetAtomicLong(this.numTimesWaited);
      stats[2] = readAndResetAtomicLong(this.waitTime);
      stats[3] = readAndResetAtomicLong(this.numCmdsProcessed);
      stats[4] = readAndResetAtomicLong(this.processTime);
    }

    private long readAndResetAtomicLong(AtomicLong l) {
      long v;
      while (true) {
        v = l.get();
        if (l.compareAndSet(v, 0)) {
          break;
        }
      }
      return v;
    }
// GemStone changes END
}
