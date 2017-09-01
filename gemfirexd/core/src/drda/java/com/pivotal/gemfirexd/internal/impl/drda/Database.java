/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.drda.Database

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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Properties;

import com.gemstone.gemfire.cache.TransactionFlag;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineConnection;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;

/**
	Database stores information about the current database
	It is used so that a session may have more than one database
*/
class Database
{

	protected String dbName;			// database name 
	protected String shortDbName;       // database name without attributes
	String attrString="";               // attribute string
	protected int securityMechanism;	// Security mechanism
	protected String userId;			// User Id
	protected String password;			// password
	protected String decryptedUserId;	// Decrypted User id
	protected String decryptedPassword;	// Decrypted password
    protected byte[] passwordSubstitute;// password substitute - SECMEC_USRSSBPWD
// GemStone changes BEGIN
        // now "allow updates" field also double for failover retry request
        public static final byte READ_ONLY = 0x0;
        public static final byte ALLOW_UPDATE = 0x01;
        public static final byte FAILOVER = 0x03;
        protected boolean rdbAllowUpdates = true;
        protected boolean failover = false;
        protected boolean disableStreaming = false;
        protected boolean skipListeners = false;
        protected boolean queryHDFS = false;
        protected boolean routeQuery = false;
        protected boolean skipConstraintChecks = false;
        protected boolean syncCommits = false;
        protected boolean disableTXBatching = false;
        protected boolean skipLocks = false;
        protected ConnectionStats connectionStats;
        protected long connectionBeginTime;
        protected Timestamp connectionBeginTimeStamp;
        protected int ncjBatchSize = 0;
        protected int ncjCacheSize = 0;
        /* (original code)
	protected boolean rdbAllowUpdates = true; // Database allows updates -default is true
	*/
// GemStone changes END
	protected int	accessCount;		// Number of times we have tried to
										// set up access to this database (only 1 
										// allowed)
    protected byte[] secTokenIn;		// Security token from app requester
    protected byte[] secTokenOut;		// Security token sent to app requester
	protected byte[] crrtkn;			// Correlation token
	protected String typDefNam;			// Type definition name
	protected int byteOrder;			//deduced from typDefNam, save String comparisons
	protected int ccsidSBC;				// Single byte CCSID
	protected int ccsidDBC;				// Double byte CCSID
	protected int ccsidMBC;				// Mixed byte CCSID
	protected String ccsidSBCEncoding;	// Encoding for single byte code page
	protected String ccsidDBCEncoding;	// Encoding for double byte code page
	protected String ccsidMBCEncoding;	// Encoding for mixed byte code page
	protected boolean RDBUPDRM_sent = false;	//We have sent that an update
											// occurred in this transaction
	protected boolean sendTRGDFTRT = false; // Send package target default value

    /**
     * Connection to the database in the embedded engine.
     */
	private EngineConnection conn;
	DRDAStatement defaultStatement;    // default statement used 
													   // for execute imm
	private DRDAStatement currentStatement; // current statement we are working on
	private Hashtable stmtTable;		// Hash table for storing statements

	boolean forXA = false;

	// constructor
	/**
	 * Database constructor
	 * 
	 * @param dbName	database name
	 */
	Database (String dbName)
	{
		if (dbName != null)
		{
			int attrOffset = dbName.indexOf(';');
			if (attrOffset != -1)
			{
				this.attrString = dbName.substring(attrOffset,dbName.length());
				this.shortDbName = dbName.substring(0,attrOffset);
			}
			else
				this.shortDbName = dbName;
		}

		this.dbName = dbName;
		this.stmtTable = new Hashtable();
		initializeDefaultStatement();
	}


	private void initializeDefaultStatement()
	{
		this.defaultStatement = new DRDAStatement(this);
	}

	/**
	 * Set connection and create the SQL statement for the default statement
	 *
	 * @param conn Connection
	 * @exception SQLException
	 */
	final void setConnection(EngineConnection conn)
		throws SQLException
	{
        if (this.conn != conn) {
            // Need to drop the pb session data when switching connections
            pbsd_ = null;
        }
		this.conn = conn;
		if(conn != null)
// GemStone changes BEGIN
		{
		  checkAndSetPossibleNCJBatchSizeDuplicate();
		  checkAndSetPossibleNCJCacheSizeDuplicate();
		  checkAndSetPossibleDuplicate();
		  this.defaultStatement.setStatement(conn);
		}
	}

        final void checkAndSetPossibleNCJBatchSizeDuplicate() {
          if (this.conn != null) {
            final LanguageConnectionContext lcc = this.conn
                .getLanguageConnectionContext();
            if (lcc != null) {
              if (SanityManager.TraceClientStatementHA) {
                SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                    "Set ncjBatchSize " + this.ncjBatchSize + " for connection: "
                        + this.conn);
              }
              lcc.setNcjBatchSize(this.ncjBatchSize);
            }
          }
        }
        
        final void checkAndSetPossibleNCJCacheSizeDuplicate() {
          if (this.conn != null) {
            final LanguageConnectionContext lcc = this.conn
                .getLanguageConnectionContext();
            if (lcc != null) {
              if (SanityManager.TraceClientStatementHA) {
                SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                    "Set ncjCacheSize " + this.ncjCacheSize + " for connection: "
                        + this.conn);
              }
              lcc.setNcjCacheSize(this.ncjCacheSize);
            }
          }
        }

	final void checkAndSetPossibleDuplicate() throws SQLException {
	  if (this.conn != null) {
	    if (this.failover) {
	      // For failover set the posdup flag for the first SQL
	      // statement that will be executed on this connection.
	      // The flag is reset in the finally blocks of all statement
	      // and prepared statement executions.
	      if (SanityManager.TraceClientStatementHA) {
	        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
	            "setting FAILOVER flag for connection: " + this.conn);
	      }
	      this.conn.setPossibleDuplicate(true);
	    }
	    else {
	      this.conn.setPossibleDuplicate(false);
	    }
	    if (this.disableStreaming) {
	      if (SanityManager.TraceClientStatementHA) {
	        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
	            "disabling STREAMING flag for connection: " + this.conn);
	      }
	    }
	    this.conn.setEnableStreaming(!this.disableStreaming);

	    final LanguageConnectionContext lcc = this.conn
                .getLanguageConnectionContext();
	    if (this.skipListeners) {
              if (SanityManager.TraceClientStatementHA) {
                SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                    "setting skip-listeners flag for connection: " + this.conn);
              }
	      if (lcc != null) {
	        lcc.setSkipListeners();
	      }
	    }
	    if (lcc != null) {
	      lcc.setQueryHDFS(this.queryHDFS);
        lcc.setQueryRoutingFlag(this.routeQuery);
	      if (this.skipConstraintChecks
	          && SanityManager.TraceClientStatementHA) {
	        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
	            "skip-constraint-checks=true for connection: " + this.conn);
	      }
	      lcc.setSkipConstraintChecks(this.skipConstraintChecks);
              if (this.skipLocks) {
                SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                    "setting skip-locks=true for connection: " + this.conn);
              }
              try {
                lcc.setSkipLocksForConnection(this.skipLocks);
              } catch (StandardException se) {
                throw Util.generateCsSQLException(se);
              }
	    }
	    EnumSet<TransactionFlag> txFlags = null;
	    if (this.syncCommits) {
	      if (SanityManager.TraceClientStatementHA) {
	        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
	            "enabling txn sync-commits for connection: " + this.conn);
	      }
	      txFlags = GemFireXDUtils.addTXFlag(TransactionFlag.SYNC_COMMITS,
	          TXManagerImpl.SYNC_COMMITS, txFlags);
	    }
	    if (this.disableTXBatching) {
	      if (SanityManager.TraceClientStatementHA) {
	        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
	            "disabling txn batching for connection: " + this.conn);
	      }
	      txFlags = GemFireXDUtils.addTXFlag(TransactionFlag.DISABLE_BATCHING,
	          TXManagerImpl.DISABLE_BATCHING, txFlags);
	    }
	    if (lcc != null) {
	      lcc.setTXFlags(txFlags);
	    }
	  }
	}
		/* (original code)
			defaultStatement.setStatement(conn);
	}
*/
// GemStone changes END
	/**
	 * Get the connection
	 *
	 * @return connection
	 */
	final EngineConnection getConnection()
	{
		return conn;
	}
	/**
	 * Get current DRDA statement 
	 *
	 * @return DRDAStatement
	 * @exception SQLException
	 */
	protected DRDAStatement getCurrentStatement() 
	{
		return currentStatement;
	}
	/**
	 * Get default statement for use in EXCIMM
	 *
	 * @return DRDAStatement
	 */
	protected DRDAStatement getDefaultStatement() 
	{
		currentStatement = defaultStatement;
		return defaultStatement;
	}

	/**
	 * Get default statement for use in EXCIMM with specified pkgnamcsn
	 * The pkgnamcsn has the encoded isolation level
	 *
	 * @param pkgnamcsn package/ section # for statement
	 * @return DRDAStatement
	 */
	protected DRDAStatement getDefaultStatement(Pkgnamcsn pkgnamcsn) 
	{
		currentStatement = defaultStatement;
		currentStatement.setPkgnamcsn(pkgnamcsn);
		return currentStatement;
	}

	/**
	 * Get a new DRDA statement and store it in the stmtTable if stortStmt is 
	 * true. If possible recycle an existing statement. When the server gets a
	 * new statement with a previously used pkgnamcsn, it means that 
	 * client-side statement associated with this pkgnamcsn has been closed. In 
	 * this case, server can re-use the DRDAStatement by doing the following:  
	 * 1) Retrieve the old DRDAStatement associated with this pkgnamcsn and
	 * close it.
	 * 2) Reset the DRDAStatement state for re-use.
	 * 
	 * @param pkgnamcsn  Package name and section
	 * @return DRDAStatement  
	 */
	protected DRDAStatement newDRDAStatement(Pkgnamcsn pkgnamcsn)
	throws SQLException
	{
		DRDAStatement stmt = getDRDAStatement(pkgnamcsn);
		if (stmt != null) {
			stmt.close();
			stmt.reset();
		}
		else
		{
			stmt = new DRDAStatement(this);
			stmt.setPkgnamcsn(pkgnamcsn);
			storeStatement(stmt);
		}
		return stmt;
	}

	/**
	 * Get DRDA statement based on pkgnamcsn
	 *
	 * @param pkgnamcsn - key to access statement
	 * @return DRDAStatement
	 */
	protected DRDAStatement getDRDAStatement(Pkgnamcsn pkgnamcsn) {
		DRDAStatement newStmt =
			(DRDAStatement) stmtTable.get(pkgnamcsn.getStatementKey());
		if (newStmt != null) {
			currentStatement = newStmt;
			currentStatement.setCurrentDrdaResultSet(pkgnamcsn);
		}
		return newStmt;
	}

	/**
	 * Make a new connection using the database name and set 
	 * the connection in the database
	 * @param p Properties for connection attributes to pass to connect
	 */
	void makeConnection(Properties p) throws SQLException
	{
// GemStone changes BEGIN
		connectionStats = InternalDriver.activeDriver().getConnectionStats();
		/*if(connectionStats == null){
			connectionStats = new ConnectionStats(Misc.getGemFireCache()
          .getDistributedSystem(), "ConnectionStats");
		}*/
		connectionStats.incClientConnectionsAttempt();
// GemStone changes END
		p.put(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, userId);
                
        // take care of case of SECMEC_USRIDONL
        if (password != null) 
		    p.put(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, password);
                
// GemStone changes BEGIN
        // check for GFXD boot before creating a connection otherwise it will
        // try to reboot the JVM failing later with arbitrary errors (#47367)
        try {
          Misc.getGemFireCache().getCancelCriterion()
              .checkCancelInProgress(null);
        } catch (Throwable t) {
          throw TransactionResourceImpl.wrapInSQLException(t);
        }
// GemStone changes BEGIN
        p.put(com.pivotal.gemfirexd.internal.iapi.reference.Property.DRDA_CLIENT_CONNECTION, "true");
        long beginConnection = ConnectionStats.getStatTime();
// GemStone changes END
        // Contract between network server and embedded engine
        // is that any connection returned implements EngineConnection.
//        EngineConnection conn = (EngineConnection)
//            NetworkServerControlImpl.getDriver().connect(com.pivotal.gemfirexd.Attribute.PROTOCOL
//                                                         + attrString, p);
// GemStone changes BEGIN
    boolean isStoreSnappy = Misc.getMemStore().isSnappyStore();
    String protocol = isStoreSnappy ? com.pivotal.gemfirexd.Attribute.SNAPPY_PROTOCOL : com.pivotal.gemfirexd.Attribute.PROTOCOL;

		EngineConnection conn = (EngineConnection)
				NetworkServerControlImpl.getDriver().connect(protocol
						+ attrString, p);
            /* (original code)
        EngineConnection conn = (EngineConnection)
            NetworkServerControlImpl.getDriver().connect(Attribute.PROTOCOL
							 + shortDbName + attrString, p);
            */
// GemStone changes END
		if (conn != null) {
// GemStone changes BEGIN
			connectionStats.incClientConnectionsOpenTime(beginConnection);
			connectionStats.incClientConnectionsOpened();
			connectionBeginTime = ConnectionStats.getStatTime();
			connectionBeginTimeStamp = new Timestamp(System.currentTimeMillis());
// GemStone changes END			
			conn.setAutoCommit(false);
		}
		setConnection(conn);
	}

    /**
     * This makes a dummy connection to the database in order to 
     * boot and/or create this last one. If database cannot
     * be found or authentication does not succeed, this will throw
     * a SQLException which we catch and do nothing. We don't pass a
     * userid and password here as we don't need to for the purpose
     * of this method - main goal is to cause the database to be
     * booted via a dummy connection.
     */
    void makeDummyConnection()
    {
        try {
            // Contract between network server and embedded engine
            // is that any connection returned implements EngineConnection.
					boolean isStoreSnappy = Misc.getMemStore().isSnappyStore();
          String protocol = isStoreSnappy ? com.pivotal.gemfirexd.Attribute.SNAPPY_PROTOCOL : com.pivotal.gemfirexd.Attribute.PROTOCOL;

					EngineConnection conn = (EngineConnection)
// GemStone changes BEGIN
                NetworkServerControlImpl.getDriver().connect(protocol
                    + attrString, new Properties());
                /* NetworkServerControlImpl.getDriver().connect(Attribute.PROTOCOL
                    + shortDbName + attrString, new Properties()); */
// GemStone changes END

            // If we succeeded in getting a connection, well just close it
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException se) {} // Simply do nothing
    }
    
	// Create string to pass to DataSource.setConnectionAttributes
	String appendAttrString(Properties p)
	{
		if (p == null)
			return null;
		
		Enumeration pKeys = p.propertyNames();
		while (pKeys.hasMoreElements()) 
		{
			String key = (String) pKeys.nextElement();
			attrString +=";" + key  + "=" + p.getProperty(key);
		}

		return attrString;
	}

	/**
	 * Store DRDA prepared statement
	 * @param  stmt	DRDA prepared statement
	 */
	protected void storeStatement(DRDAStatement stmt) throws SQLException
	{
		stmtTable.put(stmt.getPkgnamcsn().getStatementKey(), stmt);
	}

	protected void removeStatement(DRDAStatement stmt) throws SQLException
	{
		stmtTable.remove(stmt.getPkgnamcsn().getStatementKey());
		stmt.close();
	}
	
	/**
	 * Make statement the current statement
	 * @param stmt
	 *
	 */

	protected void setCurrentStatement(DRDAStatement stmt)
	{
		currentStatement = stmt;
	}

   
	protected void commit() throws SQLException
	{
		
		if (conn != null)
			conn.commit();
	}

	protected void rollback() throws SQLException
	{
		
		if (conn != null)
			conn.rollback();
	}
	/**
	  * Close the connection and clean up the statement table
	  * @throws SQLException on conn.close() error to be handled in DRDAConnThread.
	  */
	protected void close() throws SQLException
	{

		try {
			if (stmtTable != null)
			{
				for (Enumeration e = stmtTable.elements() ; e.hasMoreElements() ;) 
				{
					((DRDAStatement) e.nextElement()).close();
				}
			
			}
			if (defaultStatement != null)			
				defaultStatement.close();
			if ((conn != null) && !conn.isClosed())
			{
				if (! forXA)
				{
// GemStone changes BEGIN
				  this.conn.internalRollback();
				}
				this.conn.internalClose();
				/* (original code)
					conn.rollback();
				}
				conn.close();					
				*/
// GemStone changes END
			}
		}
		finally {
// GemStone changes BEGIN
		  if (conn != null) {
		    if (connectionStats != null) {
		    connectionStats.incClientConnectionsClosed();
		    connectionStats.incClientConnectionsLifeTime(connectionBeginTime);
		    }
			conn = null;
		  }
// GemStone changes END                 
			currentStatement = null;
			defaultStatement = null;
			stmtTable=null;
		}
	}

	final void setDrdaID(String drdaID)
	{
		if (conn != null)
			conn.setDrdaID(drdaID);
	}

	/**
	 *  Set the internal isolation level to use for preparing statements.
	 *  Subsequent prepares will use this isoalation level
	 * @param level internal isolation level 
	 *
	 * @throws SQLException
	 * @see EngineConnection#setPrepareIsolation
	 * 
	 */
	final void setPrepareIsolation(int level) throws SQLException
	{
		conn.setPrepareIsolation(level);
	}

	final int getPrepareIsolation() throws SQLException
	{
		return conn.getPrepareIsolation();
	}

	protected String buildRuntimeInfo(String indent, LocalizedResource localLangUtil)
	{	
	  
		String s = indent + 
		localLangUtil.getTextMessage("DRDA_RuntimeInfoDatabase.I") +
			dbName + "\n" +  
		localLangUtil.getTextMessage("DRDA_RuntimeInfoUser.I")  +
			userId +  "\n" +
		localLangUtil.getTextMessage("DRDA_RuntimeInfoNumStatements.I") +
			stmtTable.size() + "\n";
		s += localLangUtil.getTextMessage("DRDA_RuntimeInfoPreparedStatementHeader.I");
		for (Enumeration e = stmtTable.elements() ; e.hasMoreElements() ;) 
				{
					s += ((DRDAStatement) e.nextElement()).toDebugString(indent
																		 +"\t") +"\n";
				}
		return s;
	}
    
    private boolean locatorSupport = false;
    private boolean locatorSupportChecked = false;
    
    /**
     * Checks whether database can support locators.  This is done by
     * checking whether one of the stored procedures needed for
     * locators exists.  (If the database has been soft-upgraded from
     * an earlier version, the procedures will not exist).
     *
     * @throws SQLException if metadata call fails
     * @return <code>true</code> if locators are supported,
     *         <code>false</code otherwise
     */
    boolean supportsLocator() throws SQLException
    {
        if (!locatorSupportChecked) {
            // Check if locator procedures exist
            ResultSet rs = getConnection().getMetaData()
                    .getProcedures(null, "SYSIBM", "BLOBTRUNCATE");
            locatorSupport =  rs.next();  // True if procedure exists
            rs.close();
            locatorSupportChecked = true;
        }
        
        return locatorSupport;
    }
       

    /**
     * This method resets the state of this Database object so that it can
     * be re-used.
     * Note: currently this method resets the variables related to security
     * mechanisms that have been investigated as needing a reset.  
     * TODO: Investigate what all variables in this class need to be 
     * reset when this database object is re-used on a connection pooling or
     * transaction pooling. see DRDAConnThread.parseACCSEC (CodePoint.RDBNAM)
     * where database object is re-used on a connection reset.
     */
    public void reset()
    {
        // Reset variables for connection re-use. Currently only takes care
        // of reset the variables that affect EUSRIDPWD and USRSSBPWD
        // security mechanisms.  (DERBY-1080)
        decryptedUserId = null;
        decryptedPassword = null;
        passwordSubstitute = null;
        secTokenIn = null;
        secTokenOut = null;
        userId = null;
        password = null;
        securityMechanism = 0;
// GemStone changes BEGIN
        this.ncjBatchSize = 0;
        this.ncjCacheSize = 0;
        resetFlags();
    }

    void resetFlags() {
        rdbAllowUpdates = true;
        failover = false;
        disableStreaming = false;
        skipListeners = false;
        queryHDFS = false;
        skipConstraintChecks = false;
        syncCommits = false;
        disableTXBatching = false;
        skipLocks = false;
        // TODO: KN what to do for routeQuery property in reset
        // Leaving it to its previous state for now looks ok.
// GemStone changes END
    }

    /**
     * Piggy-backed session data. Null if no piggy-backing
     * has happened yet. Lazy initialization is acceptable since the client's
     * cache initially is empty so that any request made prior to the first
     * round of piggy-backing will trigger an actual request to the server.
     */
    private PiggyBackedSessionData pbsd_ = null;

    /**
     * Get a reference (handle) to the PiggyBackedSessionData object. Null will
     * be returned either if Database.conn is not a valid connection, or if the
     * create argument is false and no object has yet been created.
     * @param createOnDemand if true create the PiggyBackedSessionData on demand
     * @return a reference to the PBSD object or null
     * @throws java.sql.SQLException
     */
    public PiggyBackedSessionData getPiggyBackedSessionData(
            boolean createOnDemand) throws SQLException {
        pbsd_ = PiggyBackedSessionData.getInstance(pbsd_, conn, createOnDemand);
        return pbsd_;
    }
    
    public void incClientConnectionsFailed() {
      if (connectionStats != null) {
        connectionStats.incClientConnectionsFailed();
      }
    }
}
