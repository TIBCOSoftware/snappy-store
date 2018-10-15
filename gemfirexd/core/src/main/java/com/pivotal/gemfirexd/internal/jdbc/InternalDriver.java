
/*

   Derby - Class com.pivotal.gemfirexd.internal.jdbc.InternalDriver

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

package com.pivotal.gemfirexd.internal.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.logging.Logger;
import java.security.Permission;
import java.security.AccessControlException;

import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.db.Database;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.AuthenticationService;
import com.pivotal.gemfirexd.internal.iapi.jdbc.ConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.reference.MessageId;
import com.pivotal.gemfirexd.internal.iapi.reference.Module;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableProperties;
import com.pivotal.gemfirexd.internal.iapi.services.jmx.ManagementService;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleControl;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.*;
import com.pivotal.gemfirexd.internal.mbeans.JDBCMBean;
import com.pivotal.gemfirexd.internal.security.SystemPermission;


/**
	Abstract factory class and api for JDBC objects.
*/

public abstract class InternalDriver implements ModuleControl {
    
	private static final Object syncMe = new Object();
	private static InternalDriver activeDriver;
    
    private Object mbean;

	protected boolean active;
	private ContextService contextServiceFactory;
	private AuthenticationService	authenticationService;
	
	// GemStone changes BEGIN
	private volatile ConnectionStats connStats;
	
	public ConnectionStats getConnectionStats() {
		return connStats;
	}

	public void initialize(GemFireCacheImpl cache) {
	  this.connStats = new ConnectionStats(
	      cache.getDistributedSystem(), "ConnectionStats");
	}
	// GemStone changes END.

	public static final InternalDriver activeDriver()
	{
		return activeDriver;
	}

	public InternalDriver() {
		contextServiceFactory = ContextService.getFactory();
	}

	/*
	**	Methods from ModuleControl
	*/

	public void boot(boolean create, Properties properties) throws StandardException {

		synchronized (InternalDriver.syncMe)
		{
			InternalDriver.activeDriver = this;
		}

		active = true;
        
        mbean = ((ManagementService)
           Monitor.getSystemModule(Module.JMX)).registerMBean(
                   new JDBC(this),
                   JDBCMBean.class,
                   "type=JDBC");
	}

	public void stop() {

		synchronized (InternalDriver.syncMe)
		{
			InternalDriver.activeDriver = null;
		}
        
        ((ManagementService)
                Monitor.getSystemModule(Module.JMX)).unregisterMBean(
                        mbean);

		active = false;

		contextServiceFactory = null;
	}

	/*
	** Methods from java.sql.Driver
	*/
	public boolean acceptsURL(String url) {
		return active && embeddedDriverAcceptsURL( url );
	}

	/*
	** This method can be called by AutoloadedDriver so that we
	** don't accidentally boot Derby while answering the question "Can
	** you handle this URL?"
	*/
	public static	boolean embeddedDriverAcceptsURL(String url) {
		return
// GemStone changes BEGIN
		//	need to reject network driver's URL's
		!url.startsWith(Attribute.JCC_PROTOCOL) &&
		!url.startsWith(Attribute.DNC_PROTOCOL) &&
		!url.startsWith(Attribute.SNAPPY_DNC_PROTOCOL) &&
		!url.startsWith(Attribute.THRIFT_PROTOCOL) &&
		!url.startsWith(Attribute.DRDA_PROTOCOL) &&
		!url.startsWith(Attribute.SNAPPY_DRDA_PROTOCOL) &&
		!url.startsWith(Attribute.SNAPPY_THRIFT_PROTOCOL) &&
		!url.startsWith(Attribute.SNAPPY_POOL_PROTOCOL) &&
                // SQLF:BC
                !url.startsWith(Attribute.SQLF_DNC_PROTOCOL) &&
		(url.startsWith(Attribute.PROTOCOL)
		    || url.equals(Attribute.SQLJ_NESTED)
		    || url.equals(Attribute.SQLJ_NESTED_GEMFIREXD)
                    // SQLF:BC
		    || url.startsWith(Attribute.SQLF_PROTOCOL) 
                    || url.equals(Attribute.SQLJ_NESTED_SQLFIRE)
			|| url.startsWith(Attribute.SNAPPY_PROTOCOL)
			        || url.equals(Attribute.SQLJ_NESTED_SNAPPYDATA)
		 );
// GemStone changes END
	}
// GemStone changes BEGIN
    public Connection connect(String url, Properties info) throws SQLException {
      return connect(url, info, Connection.TRANSACTION_NONE);
    }

    public Connection connect(String url, Properties info, int isolationLevel)
    throws SQLException 
    {
      long connectionID = EmbedConnection.UNINITIALIZED;// GemFireXDUtils.getUniqueID();
      //return this.connect(url, info, connectionID);
      final ConnectionStats stats = this.connStats;
      if (stats != null) {
        // check for internal/client connections
        if (!(info != null && "true".equalsIgnoreCase(info
            .getProperty(Property.DRDA_CLIENT_CONNECTION)))
            && !(url.equals(Attribute.SQLJ_NESTED) ||
                url.equals(Attribute.SQLJ_NESTED_GEMFIREXD)
                 || url.equals(Attribute.SQLJ_NESTED_SNAPPYDATA)
                   // SQLF:BC
                   || url.equals(Attribute.SQLJ_NESTED_SQLFIRE)
                 )) {
          stats.incPeerConnectionsAttempt();
        }
      }

      long beginConnection = ConnectionStats.getStatTime();
      EmbedConnection conn = this.connect(url, info, connectionID, connectionID,
          false, isolationLevel);

      if (conn != null) {
        if (stats != null) {
          // nested connections have "tr" as null
          if (conn.isNestedConnection()) {
            stats.incNestedConnectionsOpen();
            stats.incNestedConnectionsOpened();
          }
          else if (!conn.isClientConnection()) {
            stats.incPeerConnectionsOpen();
            stats.incpeerConnectionsOpened();
            stats.incPeerConnectionsOpenTime(beginConnection);
          }
        }
        else {
          conn.setSkipCloseStats();
        }
      }
      return conn;
    }

	public EmbedConnection connect(String url, Properties info,
	    long connectionID, long incomingId, boolean isRemote, int isolationLevel)
// GemStone changes END
		 throws SQLException 
	{
		if (!acceptsURL(url)) { return null; }
		
        /**
         * If we are below the low memory watermark for obtaining
         * a connection, then don't even try. Just throw an exception.
         */
		if (EmbedConnection.memoryState.isLowMemory())
		{
			throw EmbedConnection.NO_MEM;
		}
        			
		/*
		** A url "jdbc:default:connection" means get the current
		** connection.  From within a method called from JSQL, the
		** "current" connection is the one that is running the
		** JSQL statement containing the method call.
		*/
		boolean current = url.equals(Attribute.SQLJ_NESTED)
		    || url.equals(Attribute.SQLJ_NESTED_GEMFIREXD)
		    // SQLF:BC
		    || url.equals(Attribute.SQLJ_NESTED_SQLFIRE)
			  || url.equals(Attribute.SQLJ_NESTED_SNAPPYDATA);

		/* If jdbc:default:connection, see if user already has a
		 * connection. All connection attributes are ignored.
		 */
		if (current) {

			ConnectionContext connContext = getConnectionContext();

			if (connContext != null) {
						
				return (EmbedConnection)connContext.getNestedConnection(false);
				
			}
			// there is no Derby connection, so
			// return null, as we are not the driver to handle this
			return null;
		}

		// convert the ;name=value attributes in the URL into
		// properties.
		FormatableProperties finfo = null;
        
		try {
            
            finfo = getAttributes(url, info);
            info = null; // ensure we don't use this reference directly again.

			/*
			** A property "shutdown=true" means shut the system or database down
			*/
			boolean shutdown = Boolean.valueOf(finfo.getProperty(Attribute.SHUTDOWN_ATTR)).booleanValue();
			
			if (shutdown) {				
				// If we are shutting down the system don't attempt to create
				// a connection; but we validate users credentials if we have to.
				// In case of datbase shutdown, we ask the database authentication
				// service to authenticate the user. If it is a system shutdown,
				// then we ask the Driver to do the authentication.
				//
// GemStone changes BEGIN
				//if (InternalDriver.getDatabaseName(url, finfo).length() == 0) {
				{
// GemStone changes END
					//
					// We need to authenticate the user if authentication is
					// ON. Note that this is a system shutdown.
					// check that we do have a authentication service
					// it is _always_ expected.
					if (this.getAuthenticationService() == null)
						throw Util.generateCsSQLException(
                        SQLState.LOGIN_FAILED, 
						MessageService.getTextMessage(MessageId.AUTH_NO_SERVICE_FOR_SYSTEM));
					

					// GemStone changes BEGIN
                                          final GemFireStore store = GemFireStore.getBootingInstance();
                                          String failure;
                                          if ((store == null || !store.isShutdownAll())
                                              && (failure = this.getAuthenticationService()
                                                  .authenticate((String)null, finfo)) != null) {
                                          /*(original code) if (!this.getAuthenticationService().authenticate((String) null, finfo)) {*/
					// GemStone changes END

						// not a valid user
						throw Util.generateCsSQLException(
                                    SQLState.NET_CONNECT_AUTH_FAILED,
                                    MessageService.
                                    getTextMessage(MessageId.AUTH_INVALID, failure));
					}

					// check for shutdown privileges
                    // Disabled until more of the patch can be applied.
					//final String user = IdUtil.getUserNameFromURLProps(finfo);
                    //checkShutdownPrivileges(user);

					Monitor.getMonitor().shutdown();

					throw Util.generateCsSQLException(
                                         SQLState.CLOUDSCAPE_SYSTEM_SHUTDOWN);
				}
			}
//                    GemStone changes Begin
                      EmbedConnection conn  =   getNewEmbedConnection(url, finfo,
                          connectionID, isRemote, incomingId);

//                    GemStone changes END
			// if this is not the correct driver a EmbedConnection
			// object is returned in the closed state.
			if (conn.isClosed()) {
				return null;
			}

			// support for "use:database" property to set default schema
			String defaultSchema = finfo.getProperty("use:database");
			if (defaultSchema != null) {
				finfo.setProperty(Attribute.DEFAULT_SCHEMA, defaultSchema);
			}
			// set defaults for Spark/SnappyData properties on the session
			if (Boolean.parseBoolean(finfo.getProperty(Attribute.ROUTE_QUERY))) {
				java.sql.Statement stmt = null;
				for (String p : finfo.stringPropertyNames()) {
					if (p.startsWith("spark.") || p.startsWith("snappydata.")) {
						String v = finfo.getProperty(p);
						if (v != null) {
							if (stmt == null) {
								stmt = conn.createStatement();
							}
							stmt.executeUpdate("set " + p + " = " + v);
						}
					}
				}
				if (stmt != null) {
					stmt.close();
				}
			}
			{
                          if (isolationLevel == Connection.TRANSACTION_NONE) {
                            conn.setAutoCommit(false, false);
                          }
                          else {
                            conn.setAutoCommit(true, false);
                          }
                          conn.setTransactionIsolation(isolationLevel);
                        }
			
			return conn;
		}
		catch (OutOfMemoryError noMemory)
		{
			EmbedConnection.memoryState.setLowMemory();
			throw EmbedConnection.NO_MEM;
		}
		finally {
			// break any link with the user's Properties set.
            if (finfo != null)
			    finfo.clearDefaults();
		}
	}

    /**
     * Checks for System Privileges.
     *
     * Abstract since some of the javax security classes are not available
     * on all platforms.
     *
     * @param user The user to be checked for having the permission
     * @param perm The permission to be checked
     * @throws AccessControlException if permissions are missing
     * @throws Exception if the privileges check fails for some other reason
     */
    abstract void checkSystemPrivileges(String user,
                                               Permission perm)
        throws Exception;

    /**
     * Checks for shutdown System Privileges.
     *
     * To perform this check the following policy grant is required
     * <ul>
     * <li> to run the encapsulated test:
     *      permission javax.security.auth.AuthPermission "doAsPrivileged";
     * </ul>
     * or a SQLException will be raised detailing the cause.
     * <p>
     * In addition, for the test to succeed
     * <ul>
     * <li> the given user needs to be covered by a grant:
     *      principal com.pivotal.gemfirexd.internal.authentication.SystemPrincipal "..." {}
     * <li> that lists a shutdown permission:
     *      permission com.pivotal.gemfirexd.internal.security.SystemPermission "shutdown";
     * </ul>
     * or it will fail with a SQLException detailing the cause.
     *
     * @param user The user to be checked for shutdown privileges
     * @throws SQLException if the privileges check fails
     */
    private void checkShutdownPrivileges(String user) throws SQLException {
        // approve action if not running under a security manager
        if (System.getSecurityManager() == null) {
            return;
        }

        // the check
        try {
            final Permission sp = new SystemPermission(
                SystemPermission.ENGINE, SystemPermission.SHUTDOWN);
            checkSystemPrivileges(user, sp);
        } catch (AccessControlException ace) {
            throw Util.generateCsSQLException(
				SQLState.AUTH_SHUTDOWN_MISSING_PERMISSION,
				user, (Object)ace); // overloaded method
        } catch (Exception e) {
            throw Util.generateCsSQLException(
				SQLState.AUTH_SHUTDOWN_MISSING_PERMISSION,
				user, (Object)e); // overloaded method
        }
    }

	public int getMajorVersion() {
		return Monitor.getMonitor().getEngineVersion().getMajorVersion();
	}
	
	public int getMinorVersion() {
		return Monitor.getMonitor().getEngineVersion().getMinorVersion();
	}

	public boolean jdbcCompliant() {
		return true;
	}

	/*
	** URL manipulation
	*/

	/**
		Convert all the attributes in the url into properties and
		combine them with the set provided. 
		<BR>
		If the caller passed in a set of attributes (info != null)
		then we set that up as the default of the returned property
		set as the user's set. This means we can easily break the link
		with the user's set, ensuring that we don't hang onto the users object.
		It also means that we don't add our attributes into the user's
		own property object.

		@exception SQLException thrown if URL form bad
	*/
	protected FormatableProperties getAttributes(String url, Properties info) 
		throws SQLException {

		// We use FormatableProperties here to take advantage
		// of the clearDefaults, method.
		FormatableProperties finfo = new FormatableProperties(info);
		info = null; // ensure we don't use this reference directly again.


		StringTokenizer st = new StringTokenizer(url, ";");
		st.nextToken(); // skip the first part of the url

		while (st.hasMoreTokens()) {

			String v = st.nextToken();

			int eqPos = v.indexOf('=');
			if (eqPos == -1)
				throw Util.generateCsSQLException(
                                            SQLState.MALFORMED_URL, url);

			//if (eqPos != v.lastIndexOf('='))
			//	throw Util.malformedURL(url);

			finfo.put((v.substring(0, eqPos)).trim(),
					 (v.substring(eqPos + 1)).trim()
					);
		}

		// now validate any attributes we can
		//
		// Boolean attributes -
		//  dataEncryption,create,createSource,convertToSource,shutdown,upgrade,current


		checkBoolean(finfo, com.pivotal.gemfirexd.internal.iapi.reference.Attribute.DATA_ENCRYPTION);
		checkBoolean(finfo, Attribute.CREATE_ATTR);
		checkBoolean(finfo, Attribute.SHUTDOWN_ATTR);
		checkBoolean(finfo, Attribute.UPGRADE_ATTR);

		return finfo;
	}

	private static void checkBoolean(Properties set, String attribute) throws SQLException
    {
        final String[] booleanChoices = {"true", "false"};
        checkEnumeration( set, attribute, booleanChoices);
	}


	private static void checkEnumeration(Properties set, String attribute, String[] choices) throws SQLException
    {
		String value = set.getProperty(attribute);
		if (value == null)
			return;

        for( int i = 0; i < choices.length; i++)
        {
            if( value.toUpperCase(java.util.Locale.ENGLISH).equals( choices[i].toUpperCase(java.util.Locale.ENGLISH)))
                return;
        }

        // The attribute value is invalid. Construct a string giving the choices for
        // display in the error message.
        String choicesStr = "{";
        for( int i = 0; i < choices.length; i++)
        {
            if( i > 0)
                choicesStr += "|";
            choicesStr += choices[i];
        }
        
		throw Util.generateCsSQLException(
                SQLState.INVALID_ATTRIBUTE, attribute, value, choicesStr + "}");
	}


	/**
		Get the database name from the url.
		Copes with three forms

		jdbc:derby:dbname
		jdbc:derby:dbname;...
		jdbc:derby:;subname=dbname

		@param url The url being used for the connection
		@param info The properties set being used for the connection, must include
		the properties derived from the attributes in the url

		@return a String containing the database name or an empty string ("") if
		no database name is present in the URL.
	*/
	public static String getDatabaseName(String url, Properties info) {
//GemStone Changes begin
		if (url.equals(Attribute.SQLJ_NESTED)
		    || url.equals(Attribute.SQLJ_NESTED_GEMFIREXD)
				|| url.equals(Attribute.SQLJ_NESTED_SNAPPYDATA)
                    // SQLF:BC
                    || url.equals(Attribute.SQLJ_NESTED_SQLFIRE)
		   )
		{
			return "";
		}
//GemStone Changes end
		
		// skip the jdbc:derby:
		int attributeStart = url.indexOf(';');
		//SQLF:BC
		int sqlfireProto = url.indexOf(Attribute.SQLF_PROTOCOL);
		String dbname;
		if (attributeStart == -1) {
		  //SQLF:BC
		  if (sqlfireProto == -1) {
			dbname = url.substring(Attribute.PROTOCOL.length());
		  }
		  else {
		        dbname = url.substring(Attribute.SQLF_PROTOCOL.length());
		  }
		}
		else {
		  //SQLF:BC
		  if (sqlfireProto == -1) {
			dbname = url.substring(Attribute.PROTOCOL.length(), attributeStart);
		  }
		  else {
		        dbname = url.substring(Attribute.SQLF_PROTOCOL.length(), attributeStart);
		  }
		}

		// For security reasons we rely on here an non-null string being
		// taken as the database name, before the databaseName connection
		// attribute. Specifically, even if dbname is blank we still we
		// to use it rather than the connection attribute, even though
		// it will end up, after the trim, as a zero-length string.
		// See EmbeddedDataSource.update()

		if (dbname.length() == 0) {
		    if (info != null)
				dbname = info.getProperty(Attribute.DBNAME_ATTR, dbname);
		}
		// Beetle 4653 - trim database name to remove blanks that might make a difference on finding the database
		// on unix platforms
		dbname = dbname.trim();

// GemStone changes BEGIN
                // currently return a fixed DB name for GemFireXD and expect
		// empty DB name; we change dbname currently in a way that it
		// will always fail to match against our provider other the
		// special name Attribute.GFXD_DBNAME (i.e. "gemfirexd") will
		// still match for our provider
                if (dbname.length() == 0
                    || dbname.equalsIgnoreCase(Attribute.GFXD_DBNAME)
                    //SQLF:BC
                    || dbname.equalsIgnoreCase(Attribute.SQLF_DBNAME)) {
                  return Attribute.GFXD_DBNAME;
                }
		// Try for snappydata if dbname.length == 0 or dbname = ":"
		if (dbname.length() == 0 || dbname.equals(":")) {
			int snappyProto = url.indexOf(Attribute.SNAPPY_PROTOCOL);
			if (snappyProto >= 0) {
				return Attribute.SNAPPY_DBNAME;
			}
		}

                return ":" + dbname;
		/* (original derby code) return dbname; */
// GemStone changes END
	}

	public final ContextService getContextServiceFactory() {
		return contextServiceFactory;
	}

	// returns the authenticationService handle
	public AuthenticationService getAuthenticationService() {
		//
		// If authenticationService handle not cached in yet, then
		// ask the monitor to find it for us and set it here in its
		// attribute.
		//
		if (this.authenticationService == null) {
                  // GemStone changes BEGIN
			/*(original code) this.authenticationService = (AuthenticationService)
				Monitor.findService(AuthenticationService.MODULE,
									"authentication"
								   );*/
                      try {
                        Database databaseObj = (Database)Monitor.findService(
                            Property.DATABASE_MODULE, Attribute.GFXD_DBNAME);
                        if (databaseObj == null) {
                          Properties props = new Properties();
                          authenticationService = (AuthenticationService)Monitor
                              .bootServiceModule(false, null, AuthenticationService.MODULE,
                                  GfxdConstants.AUTHENTICATION_SERVICE, props);
                        }
                        else {
                          authenticationService = databaseObj.getAuthenticationService();
                        }
                      } catch (StandardException ignore) {
                         // fall below to raise assertion error.
                      }
                  // GemStone changes END
		}

		// We should have a Authentication Service (always)
		//
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(this.authenticationService != null, 
				"Unexpected - There is no valid authentication service!");
		}
		return this.authenticationService;
	}

	/*
		Methods to be overloaded in sub-implementations such as
		a tracing driver.
	 */
//      GemStone changes BEGIN
	protected abstract EmbedConnection getNewEmbedConnection(String url, Properties info,
	    long id, boolean isRemote, long realConnectionID)
//      GemStone changes END
		 throws SQLException ;


	private ConnectionContext getConnectionContext() {

		/*
		** The current connection is the one in the current
		** connection context, so get the context.
		*/
		ContextManager	cm = getCurrentContextManager();

		ConnectionContext localCC = null;

		/*
			cm is null the very first time, and whenever
			we aren't actually nested.
		 */
		if (cm != null) {
			localCC = (ConnectionContext)
				(cm.getContext(ConnectionContext.CONTEXT_ID));
		}

		return localCC;
	}

	private ContextManager getCurrentContextManager() {
		return getContextServiceFactory().getCurrentContextManager();
	}


	/**
		Return true if this driver is active. Package private method.
	*/
	public boolean isActive() {
		return active;
	}

	/**
 	 * Get a new nested connection.
	 *
	 * @param conn	The EmbedConnection.
	 *
	 * @return A nested connection object.
	 *
	 */
	public abstract Connection getNewNestedConnection(EmbedConnection conn);

	/*
	** methods to be overridden by subimplementations wishing to insert
	** their classes into the mix.
	*
	* Set rootID=0 and statementLevel=0
	*/

	public java.sql.Statement newEmbedStatement(
				EmbedConnection conn,
				boolean forMetaData,
				int resultSetType,
				int resultSetConcurrency,
// GemStone changes BEGIN
				int resultSetHoldability, long id)
				    throws SQLException
// GemStone changes END
	{
		return new EmbedStatement(conn, forMetaData, resultSetType, resultSetConcurrency,
// GemStone changes BEGIN
		resultSetHoldability,id,0,0);
// GemStone changes END
	}
//      GemStone changes END
	/**
	 	@exception SQLException if fails to create statement
	 */
	public abstract java.sql.PreparedStatement newEmbedPreparedStatement(
				EmbedConnection conn,
				String stmt, 
				boolean forMetaData, 
				int resultSetType,
				int resultSetConcurrency,
				int resultSetHoldability,
				int autoGeneratedKeys,
				int[] columnIndexes,
// GemStone changes BEGIN
                                String[] columnNames, long id, short execFlags, THashMap ncjMetaData,
                                long rootID, int stmtLevel)
		throws SQLException;

	/**
	 	@exception SQLException if fails to create statement
	 */
	public abstract java.sql.CallableStatement newEmbedCallableStatement(
				EmbedConnection conn,
				String stmt, 
				int resultSetType,
				int resultSetConcurrency,
				int resultSetHoldability,
				long id,short execFlags
				)
		throws SQLException;
//       GemStone changes END
	/**
	 * Return a new java.sql.DatabaseMetaData instance for this implementation.
	 	@exception SQLException on failure to create.
	 */
	public DatabaseMetaData newEmbedDatabaseMetaData(EmbedConnection conn,
		String dbname) throws SQLException {
		return new EmbedDatabaseMetaData(conn,dbname);
	}

	/**
	 * Return a new java.sql.ResultSet instance for this implementation.
	 * @param conn Owning connection
	 * @param results Top level of language result set tree
	 * @param forMetaData Is this for meta-data
	 * @param statement The statement that is creating the SQL ResultSet
	 * @param isAtomic 
	 * @return a new java.sql.ResultSet
	 * @throws SQLException
	 */
	public abstract EmbedResultSet
		newEmbedResultSet(EmbedConnection conn, ResultSet results, boolean forMetaData, EmbedStatement statement, boolean isAtomic) throws SQLException;
        
        /**
         * Returns a new java.sql.ResultSetMetaData for this implementation
         *
         * @param columnInfo a ResultColumnDescriptor that stores information 
         *        about the columns in a ResultSet
         */
        public EmbedResultSetMetaData newEmbedResultSetMetaData
                           (ResultColumnDescriptor[] columnInfo) {
            return new EmbedResultSetMetaData(columnInfo);
        }
        
  // GemStone changes BEGIN
  // JDBC 4.1 methods since jdk 1.7
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }
  // GemStone changes END

}



