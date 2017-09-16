/*

   Derby - Class com.pivotal.gemfirexd.jdbc.ClientDRDADriver

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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.jdbc;

import java.sql.SQLException;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.client.am.ClientJDBCObjectFactory;
import com.pivotal.gemfirexd.internal.client.am.SqlException;
import com.pivotal.gemfirexd.internal.client.am.Utils;
import com.pivotal.gemfirexd.internal.jdbc.ClientDataSource;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.error.ClientExceptionUtil;
import com.pivotal.gemfirexd.internal.shared.common.reference.MessageId;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.internal.ClientConfiguration;

public class ClientDRDADriver implements java.sql.Driver {
    private transient int traceFileSuffixIndex_ = 0;

    // support for older jdbc:sqlfire:// URL scheme has now been dropped
    public static String SNAPPY_PROTOCOL =  "jdbc:snappydata:";
    public static String GEMXD_PROTOCOL =  "jdbc:gemfirexd:";
    public static String  DRDA_CONNECTION_PROTOCOL = "DRDA_CONNECTION_PROTOCOL";

  protected final static String URL_PREFIX_REGEX = "(" + SNAPPY_PROTOCOL +
      "|" + GEMXD_PROTOCOL + ")";
    protected final static String URL_SUFFIX_REGEX =
        "//(([^:]+:[0-9]+)|([^\\[]+\\[[0-9]+\\]))(/(gemfirexd;|snappydata;)?;?(.*)?)?";

    private final static String DRDA_SUBPROTOCOL = "drda:";
    private final static Pattern DRDA_PROTOCOL_PATTERN = Pattern.compile(
        URL_PREFIX_REGEX + DRDA_SUBPROTOCOL + "//.*", Pattern.CASE_INSENSITIVE);
    private final static Pattern DRDA_URL_PATTERN = Pattern.compile(URL_PREFIX_REGEX
        + DRDA_SUBPROTOCOL + URL_SUFFIX_REGEX, Pattern.CASE_INSENSITIVE);

    private static ClientJDBCObjectFactory factoryObject = null;

    static protected SQLException exceptionsOnLoadDriver__ = null;
    // Keep track of the registered driver so that we can de-register it
    // if we're a stored proc.
    static protected ClientDRDADriver registeredDriver__ = null;

    static {
      try {
        registeredDriver__ = new ClientDRDADriver();
        java.sql.DriverManager.registerDriver(registeredDriver__);
      } catch (java.sql.SQLException e) {
        // A null log writer is passed, because jdbc 1 sql exceptions are
        // automatically traced
        exceptionsOnLoadDriver__ = ClientExceptionUtil.newSQLException(
            SQLState.JDBC_DRIVER_REGISTER, e);
      }
      // This may possibly hit the race-condition bug of java 1.1.
      // The Configuration static clause should execute before the following line
      // does.
      if (ClientConfiguration.exceptionsOnLoadResources != null) {
        exceptionsOnLoadDriver__ = Utils.accumulateSQLException(
            ClientConfiguration.exceptionsOnLoadResources,
            exceptionsOnLoadDriver__);
      }
    }

    public ClientDRDADriver() {
    }

    /**
     * {@inheritDoc}
     */
    public java.sql.Connection connect(String url,
                                       java.util.Properties properties) throws java.sql.SQLException {
        if (!acceptsURL(url)) {
          return null;
        }
        com.pivotal.gemfirexd.internal.client.net.NetConnection conn;
        try {    
            if (exceptionsOnLoadDriver__ != null) {
                throw exceptionsOnLoadDriver__;
            }

            if (properties == null) {
                properties = new java.util.Properties();
            }

            /*
            java.util.StringTokenizer urlTokenizer =
                    new java.util.StringTokenizer(url, "/:[]= \t\n\r\f", true);

            int protocol = tokenizeProtocol(url, urlTokenizer);
            if (protocol == 0) {
                return null; // unrecognized database URL prefix.
            }

            String slashOrNull = null;
            if (protocol == THRIFT_REMOTE_PROTOCOL ||
                protocol == DERBY_REMOTE_PROTOCOL) {
                try {
                    slashOrNull = urlTokenizer.nextToken(":/");
                } catch (java.util.NoSuchElementException e) {
                    // A null log writer is passed, because jdbc 1 sqlexceptions are automatically traced
                    throw new SqlException(null, 
                        new ClientMessageId(SQLState.MALFORMED_URL),
                        url, e);
                }
            }
            String server = tokenizeServerName(urlTokenizer, url);    // "/server"
            int port = tokenizeOptionalPortNumber(urlTokenizer, url); // "[:port]/"
            if (port == 0) {
                port = ClientDataSource.propertyDefault_portNumber;
            }

            // database is the database name and attributes.  This will be
            // sent to network server as the databaseName
            String database = tokenizeDatabase(urlTokenizer, url); // "database"
            java.util.Properties augmentedProperties = tokenizeURLProperties(url, properties);
// GemStone changes BEGIN
            // GemFireXD has no DB or DB properties in the name
            /* (original code)
            database = appendDatabaseAttributes(database,augmentedProperties);
            */
            ClientExceptionUtil.init();
            Matcher m = matchURL(url);
            if (!m.matches()) {
              throw ClientExceptionUtil.newSQLException(
                  SQLState.MALFORMED_URL, null, url);
            }
            final boolean thriftProtocol = useThriftProtocol(m);
            int[] port = new int[1];
            String server = getServer(m, port);
            // we already verified that there is a valid database name,
            // if any, in the URL, so just pass empty database name
            String database = "";
            java.util.Properties augmentedProperties = getURLProperties(m,
                properties);
            augmentedProperties.put(DRDA_CONNECTION_PROTOCOL, m.group(1));

            if (thriftProtocol) {
              // disable query routing for gemfirexd URL
              String protocol = m.group(1);
              if (protocol.equalsIgnoreCase(GEMXD_PROTOCOL)) {
                augmentedProperties.put(Attribute.ROUTE_QUERY, "false");
              }
              return createThriftConnection(server, port[0], augmentedProperties);
            }
// GemStone changes END

            int traceLevel;
            try {
                traceLevel = ClientDataSource.getTraceLevel(augmentedProperties);
            } catch (java.lang.NumberFormatException e) {
                // A null log writer is passed, because jdbc 1 sqlexceptions are automatically traced
                throw ClientExceptionUtil.newSQLException(
                    SQLState.LOGLEVEL_FORMAT_INVALID, e, e.getMessage());
            }

            // Jdbc 1 connections will write driver trace info on a
            // driver-wide basis using the jdbc 1 driver manager log writer.
            // This log writer may be narrowed to the connection-level
            // This log writer will be passed to the agent constructor.
            com.pivotal.gemfirexd.internal.client.am.LogWriter dncLogWriter =
                    ClientDataSource.computeDncLogWriterForNewConnection(java.sql.DriverManager.getLogWriter(),
                            ClientDataSource.getTraceDirectory(augmentedProperties),
                            ClientDataSource.getTraceFile(augmentedProperties),
                            ClientDataSource.getTraceFileAppend(augmentedProperties),
                            traceLevel,
                            "_driver",
                            traceFileSuffixIndex_++);

            conn = (com.pivotal.gemfirexd.internal.client.net.NetConnection)getFactory().
                    newNetConnection(
                    dncLogWriter,
                    java.sql.DriverManager.getLoginTimeout(),
                    server,
                    port[0],
                    database,
                    augmentedProperties);

        } catch(SqlException se) {
            throw se.getSQLException(null /* GemStoneAddition */);
        }
        
        if(conn.isConnectionNull())
            return null;
        
        return conn;
    }

    /*
    /**
     * Append attributes to the database name except for user/password 
     * which are sent as part of the protocol, and SSL which is used 
     * locally in the client.
     * Other attributes will  be sent to the server with the database name
     * Assumes augmentedProperties is not null
     * 
	 * @param database - Short database name
	 * @param augmentedProperties - Set of properties to append as attributes
	 * @return databaseName + attributes (e.g. mydb;create=true) 
	 *
	private String appendDatabaseAttributes(String database, Properties augmentedProperties) {
	
		StringBuilder longDatabase = new StringBuilder(database);
		for (Enumeration keys = augmentedProperties.propertyNames();
			 keys.hasMoreElements() ;)
		{
			String key = (String) keys.nextElement();
			if (key.equals(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR) ||
			    // GemStone changes BEGIN
			    key.equals(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) ||
			    // GemStone changes END
                key.equals(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR) ||
                key.equals(ClientAttribute.SSL))
				continue;
			longDatabase.append(";" + key + "=" + augmentedProperties.getProperty(key));
		}
		return longDatabase.toString();
	}
	*/

    /**
     * {@inheritDoc}
     */
    public boolean acceptsURL(String url) throws java.sql.SQLException {
      return (url != null && matchProtocol(url).matches());
    }

    /**
     * {@inheritDoc}
     */
    public java.sql.DriverPropertyInfo[] getPropertyInfo(String url,
                                                         java.util.Properties properties) throws java.sql.SQLException {
        java.sql.DriverPropertyInfo driverPropertyInfo[] = new java.sql.DriverPropertyInfo[2];

        // If there are no properties set already,
        // then create a dummy properties just to make the calls go thru.
        if (properties == null) {
            properties = new java.util.Properties();
        }

        // GemStone changes BEGIN
        /* (original code)
        driverPropertyInfo[0] =
                new java.sql.DriverPropertyInfo(Attribute.USERNAME_ATTR,
                        properties.getProperty(Attribute.USERNAME_ATTR, ClientDataSource.propertyDefault_user));
         */
        boolean isUserNameAttribute = false;
        String userName = properties.getProperty(Attribute.USERNAME_ATTR);
        if( userName == null) {
          userName = properties.getProperty(Attribute.USERNAME_ALT_ATTR);
          if(userName != null) {
            isUserNameAttribute = true;
          }
        }
        
        driverPropertyInfo[0] = new java.sql.DriverPropertyInfo(
            isUserNameAttribute ? Attribute.USERNAME_ALT_ATTR
                : Attribute.USERNAME_ATTR, userName);
        // GemStone changes END
        
        driverPropertyInfo[1] =
                new java.sql.DriverPropertyInfo(Attribute.PASSWORD_ATTR,
                        properties.getProperty(Attribute.PASSWORD_ATTR));

        driverPropertyInfo[0].description =
            SqlException.getMessageUtil().getTextMessage(
                MessageId.CONN_USERNAME_DESCRIPTION);
        driverPropertyInfo[1].description =
            SqlException.getMessageUtil().getTextMessage(
                MessageId.CONN_PASSWORD_DESCRIPTION);

        driverPropertyInfo[0].required = true;
        driverPropertyInfo[1].required = false; // depending on the security mechanism

        return driverPropertyInfo;
    }

    /**
     * {@inheritDoc}
     */
    public int getMajorVersion() {
        return ClientConfiguration.getProductVersionHolder().getMajorVersion();
    }

    /**
     * {@inheritDoc}
     */
    public int getMinorVersion() {
        return ClientConfiguration.getProductVersionHolder().getMinorVersion();
    }

    /**
     * {@inheritDoc}
     */
    public boolean jdbcCompliant() {
        return ClientConfiguration.jdbcCompliant;
    }

    // ----------------helper methods---------------------------------------------

    /*
    // Tokenize one of the following:
    //  "jdbc:derby:"
    // and return 0 if the protcol is unrecognized
    // return DERBY_PROTOCOL for "jdbc:derby"
    private static int tokenizeProtocol(String url, java.util.StringTokenizer urlTokenizer) throws SqlException {
        // Is this condition necessary, StringTokenizer constructor may do this for us
        if (url == null) {
            return 0;
        }

        if (urlTokenizer == null) {
            return 0;
        }

        try {
            String jdbc = urlTokenizer.nextToken(":");
            if (!jdbc.equals("jdbc")) {
                return 0;
            }
            if (!urlTokenizer.nextToken(":").equals(":")) {
                return 0; // Skip over the first colon in jdbc:derby:
            }
            String dbname = urlTokenizer.nextToken(":");
            int protocol = 0;
// GemStone changes BEGIN
            if ( (dbname.equals("gemfirexd") && (url.indexOf("gemfirexd://") != -1))
              //SQLF:BC
              || (dbname.equals("sqlfire") && (url.indexOf("sqlfire://") != -1))
               ) {
            /* if (dbname.equals("derby") && (url.indexOf("derby://") != -1)) { *
// GemStone changes END
                // For Derby AS need to check for // since jdbc:derby: is also the
                // embedded prefix
                protocol = DERBY_REMOTE_PROTOCOL;
            } else {
                return 0;
            }

            if (!urlTokenizer.nextToken(":").equals(":")) {
                return 0; // Skip over the second colon in jdbc:derby:
            }

            return protocol;
        } catch (java.util.NoSuchElementException e) {
            return 0;
        }
    }

    // tokenize "/server" from URL jdbc:derby://server:port/
    // returns server name
    private static String tokenizeServerName(java.util.StringTokenizer urlTokenizer,
                                             String url) throws SqlException {
        try {
            if (!urlTokenizer.nextToken("/").equals("/"))
            // A null log writer is passed, because jdbc 1 sqlexceptions are automatically traced
            {
                throw new SqlException(null, 
                    new ClientMessageId(SQLState.MALFORMED_URL), url);
            }
            return urlTokenizer.nextToken("/:[");
        } catch (java.util.NoSuchElementException e) {
            // A null log writer is passed, because jdbc 1 sqlexceptions are automatically traced
                throw new SqlException(null, 
                    new ClientMessageId(SQLState.MALFORMED_URL), url);
        }
    }

    // tokenize "[:portNumber]/" from URL jdbc:derby://server[:port]/
    // returns the portNumber or zero if portNumber is not specified.
    private static int tokenizeOptionalPortNumber(java.util.StringTokenizer urlTokenizer,
                                                  String url) throws SqlException {
        try {
            String firstToken = urlTokenizer.nextToken(":/");
            if (firstToken.equals(":")) {
                String port = urlTokenizer.nextToken("/");
// GemStone changes BEGIN
                // allow for no trailing slash since dbname is optional
                if (urlTokenizer.hasMoreTokens() &&
                    !urlTokenizer.nextToken("/").equals("/")) {
                /* if (!urlTokenizer.nextToken("/").equals("/")) { *
// GemStone changes END
                    // A null log writer is passed, because jdbc 1 sqlexceptions are automatically traced
                    throw new SqlException(null, 
                        new ClientMessageId(SQLState.MALFORMED_URL), url);
                }
                return Integer.parseInt(port);
            } else if (firstToken.equals("/")) {
                return 0;
            } else {
                // A null log writer is passed, because jdbc 1 sqlexceptions are automatically traced
                throw new SqlException(null, 
                    new ClientMessageId(SQLState.MALFORMED_URL), url);
            }
        } catch (java.util.NoSuchElementException e) {
            // A null log writer is passed, because jdbc 1 sqlexceptions are automatically traced
            throw new SqlException(null, 
                new ClientMessageId(SQLState.MALFORMED_URL), url, e);
        }
    }

    //return database name
    private static String tokenizeDatabase(java.util.StringTokenizer urlTokenizer,
                                           String url) throws SqlException {
        try {
        	// DERBY-618 - database name can contain spaces in the path
            String databaseName = urlTokenizer.nextToken("\t\n\r\f;");
// GemStone changes BEGIN
            if (databaseName != null && databaseName.length() > 0
                && databaseName.charAt(0) != ';'
                && !"gemfirexd".equalsIgnoreCase(databaseName)) {
              // DB name should not be present in the client-side URL
              throw new SqlException(null, new ClientMessageId(
                  SQLState.MALFORMED_URL), url + " => (unexpected DB name '"
                  + databaseName + "')");
            }
        } catch (java.util.NoSuchElementException ex) {
          // deliberately ignored for GemFireXD since it does not require
          // a database name
        }
        return "";
        /* (original derby code)
            return databaseName;
        } catch (java.util.NoSuchElementException e) {
            // A null log writer is passed, because jdbc 1 sqlexceptions are automatically traced
            throw new SqlException(null, 
                new ClientMessageId(SQLState.MALFORMED_URL), url, e);
        }
        *
// GemStone changes END
    }

    private static java.util.Properties tokenizeURLProperties(String url,
                                                              java.util.Properties properties)
            throws SqlException {
        String attributeString = null;
        int attributeIndex = -1;

        if ((url != null) &&
                ((attributeIndex = url.indexOf(";")) != -1)) {
            attributeString = url.substring(attributeIndex);
        }
        return ClientDataSource.tokenizeAttributes(attributeString, properties);
    }
    */

    /**
     *This method returns an Implementation
     *of ClientJDBCObjectFactory depending on
     *VM under use
     *Currently it returns either
     *ClientJDBCObjectFactoryImpl
     *(or)
     *ClientJDBCObjectFactoryImpl40
     */
    
    public static ClientJDBCObjectFactory getFactory() {
        if(factoryObject!=null)
            return factoryObject;
        if(ClientConfiguration.supportsJDBC40()) {
            factoryObject = createJDBC40FactoryImpl();
        } else {
            factoryObject = createDefaultFactoryImpl();
        }
        return factoryObject;
    }
    
    /**
     *Returns an instance of the ClientJDBCObjectFactoryImpl class
     */
    private static ClientJDBCObjectFactory createDefaultFactoryImpl() {
// GemStone changes BEGIN
      final String factoryName =
        "com.pivotal.gemfirexd.internal.client.net.ClientJDBCObjectFactoryImpl";
      try {
        return (ClientJDBCObjectFactory)Class.forName(factoryName)
            .newInstance();
      } catch (Exception e) {
        final Error err = new NoClassDefFoundError("unable to load JDBC "
            + "connection factory (ClientJDBCObjectFactoryImpl)");
        err.initCause(e);
        throw err;
      }
        /* (original code)
        return  new ClientJDBCObjectFactoryImpl();
        */
// GemStone changes END
    }
    
    /**
     *Returns an instance of the ClientJDBCObjectFactoryImpl40 class
     *If a ClassNotFoundException occurs then it returns an
     *instance of ClientJDBCObjectFactoryImpl
     *
     *If a future version of JDBC comes then
     *a similar method would be added say createJDBCXXFactoryImpl
     *in which if  the class is not found then it would
     *return the lower version thus having a sort of cascading effect
     *until it gets a valid instance
     */
    
    private static ClientJDBCObjectFactory createJDBC40FactoryImpl() {
        final String factoryName =
                "com.pivotal.gemfirexd.internal.client.net.ClientJDBCObjectFactoryImpl40";
        try {
            return (ClientJDBCObjectFactory)
            Class.forName(factoryName).newInstance();
        } catch (ClassNotFoundException cnfe) {
            return createDefaultFactoryImpl();
        } catch (InstantiationException ie) {
            return createDefaultFactoryImpl();
        } catch (IllegalAccessException iae) {
            return createDefaultFactoryImpl();
        }
    }
// GemStone changes BEGIN

    protected boolean useThriftProtocol(Matcher m) {
      return false;
    }

    protected String getServer(Matcher m, int[] port) {
      String serverGroup = m.group(3);
      if (serverGroup != null && serverGroup.length() > 0) {
        return SharedUtils.getHostPort(serverGroup, port);
      }
      else {
        return SharedUtils.getHostPort(m.group(4), port);
      }
    }

    protected java.util.Properties getURLProperties(Matcher m,
        java.util.Properties properties) throws SqlException {
      String propsGroup = m.group(8);
      if (propsGroup != null && propsGroup.length() > 0) {
        return ClientDataSource.tokenizeAttributes(propsGroup, properties);
      }
      else {
        return properties;
      }
    }

    protected Matcher matchURL(String url) {
      return DRDA_URL_PATTERN.matcher(url);
    }

    protected Matcher matchProtocol(String url) {
     return  DRDA_PROTOCOL_PATTERN.matcher(url);
    }

    protected java.sql.Connection createThriftConnection(String server, int port,
        java.util.Properties props) throws SQLException {
      throw new AssertionError(
          "ClientDRDADriver.createThriftConnection not expected to be invoked");
    }

    // JDBC 4.1 methods since jdk 1.7
    public Logger getParentLogger() {
      throw new AssertionError("should be overridden in JDBC 4.1");
    }
// GemStone changes END
}

