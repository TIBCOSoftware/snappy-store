/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
package com.pivotal.gemfirexd;

/**
 * List of properties that can only be listed as System Properties apart from
 * <code>gemfirexd.</code> prefixed {@link Attribute} properties.
 * 
 * @author soubhikc
 * 
 */
public interface Property {

  /**
   * Can optionally start with "ldap://" or "ldap:" or "ldaps://" or "ldaps:" as
   * ldap server url.<BR>
   * <BR>
   * 
   * Define {gemfirexd.auth-ldap-search-base} property for limiting the
   * search space during ldap verification of the user login id. <BR>
   * <BR>
   * By default anonymous user is used to bind with the ldap server for
   * searching if property {gemfirexd.auth-ldap-search-base} is
   * configured. <BR>
   * <BR>
   * If ldap server doesn't allow anonymous bind, define
   * {gemfirexd.auth-ldap-search-dn} and
   * {gemfirexd.auth-ldap-search-pw} properties, which will be
   * preferred over anonymous user while binding to ldap server for searching.
   * 
   */
  // TODO mention about searchFilter and gemfirexd.user. prefix mapping of DN.
  // before that need to test it.
  public static final String AUTH_LDAP_SERVER = "gemfirexd.auth-ldap-server";

  //
  // GemFireXD LDAP Configuration properties
  //
  public static final String AUTH_LDAP_SEARCH_BASE = "gemfirexd.auth-ldap-search-base";

  public static final String AUTH_LDAP_SEARCH_FILTER = "gemfirexd.auth-ldap-search-filter";

  public static final String AUTH_LDAP_SEARCH_DN = "gemfirexd.auth-ldap-search-dn";

  public static final String AUTH_LDAP_SEARCH_PW = "gemfirexd.auth-ldap-search-pw";

// GemStone changes BEGIN
  public static final String AUTH_LDAP_GROUP_SEARCH_BASE =
      "gemfirexd.group-ldap-search-base";
  public static final String AUTH_LDAP_GROUP_SEARCH_FILTER =
      "gemfirexd.group-ldap-search-filter";
  public static final String AUTH_LDAP_GROUP_MEMBER_ATTRIBUTES =
      "gemfirexd.group-ldap-member-attributes";
// GemStone changes END
  /*
  	Property to enable Grant & Revoke SQL authorization. Introduced in Derby 10.2
  	release. New databases and existing databases (in Derby 10.2) still use legacy
  	authorization by default and by setting this property to true could request for
  	SQL standard authorization model.
   */
  public static final String SQL_AUTHORIZATION = "gemfirexd.sql-authorization";

  /**
   * Default connection level authorization, set to one of NO_ACCESS,
   * READ_ONLY_ACCESS or FULL_ACCESS. Defaults to FULL_ACCESS if not set.
   */
  public static final String AUTHZ_DEFAULT_CONNECTION_MODE = "gemfirexd.authz-default-connection-mode";

  /**
   * List of users with read-only connection level authorization.
   */
  public static final String AUTHZ_READ_ONLY_ACCESS_USERS = "gemfirexd.authz-read-only-access-users";

  /**
   * List of users with full access connection level authorization.
   */
  public static final String AUTHZ_FULL_ACCESS_USERS = "gemfirexd.authz-full-access-users";

  /**
   * Name of the file that contains system wide properties. Has to be located
   * in ${gemfirexd.system.home} if set, otherwise ${user.dir}
   */
  public static final String PROPERTIES_FILE = "gemfirexd.properties";

  /**
   * [SQLFire] Name of the file that contains system wide properties for old
   * SQLFire product. Has to be located in ${sqlfire.system.home} if set,
   * otherwise ${user.dir}
   */
  public static final String SQLF_PROPERTIES_FILE = "sqlfire.properties";

  /**
   * gemfirexd.drda.securityMechanism
   *<BR>
   * This property can be set to one of the following values
   * USER_ONLY_SECURITY
   * CLEAR_TEXT_PASSWORD_SECURITY
   * ENCRYPTED_USER_AND_PASSWORD_SECURITY
   * STRONG_PASSWORD_SUBSTITUTE_SECURITY
   * <BR>
   * if gemfirexd.drda.securityMechanism is set to a valid mechanism, then
   * the Network Server accepts only connections which use that
   * security mechanism. No other types of connections are accepted.
   * <BR>
   * if the gemfirexd.drda.securityMechanism is not set at all, then the
   * Network Server accepts any connection which uses a valid
   * security mechanism.
   * <BR> 
   * E.g gemfirexd.drda.securityMechanism=USER_ONLY_SECURITY
   * This property is static. Server must be restarted for the property to take effect.
   * Default value for this property is as though it is not set - in which case
   * the server will allow clients with supported security mechanisms to connect
   */
  public final static String DRDA_PROP_SECURITYMECHANISM = "gemfirexd.drda.securityMechanism";

  /**
   * gemfirexd.drda.sslMode
   * <BR>
   * This property may be set to one of the following three values
   * off: No Wire encryption
   * basic:  Encryption, but no SSL client authentication
   * peerAuthentication: Encryption and with SSL client
   * authentication 
   */
  
  public final static String DRDA_PROP_SSL_MODE = "gemfirexd.drda.sslMode";

  /**
   * gemfirexd.drda.portNumber
   *<BR>
   * The port number used by the network server.
   */
      public final static String DRDA_PROP_PORTNUMBER = "gemfirexd.drda.portNumber";

  public final static String DRDA_PROP_HOSTNAME = "gemfirexd.drda.host";

  /**
   * gemfirexd.drda.streamOutBufferSize
   * size of buffer used when stream out for client.
   *
   */
  public final static String DRDA_PROP_STREAMOUTBUFFERSIZE = "gemfirexd.drda.streamOutBufferSize";

  /**
   * gemfirexd.drda.keepAlive
   *
   *<BR>
   * client socket setKeepAlive value
   */
  public final static String DRDA_PROP_KEEPALIVE = "gemfirexd.drda.keepAlive";

  /**
   * gemfirexd.drda.traceDirectory
   *<BR>
   * The directory used for network server tracing files.
   *<BR>
   * Default: if the gemfirexd.system.home property has been set,
   * it is the default. Otherwise, the default is the current directory.
   */
      public final static String DRDA_PROP_TRACEDIRECTORY = "gemfirexd.drda.traceDirectory";

  /**
   * gemfirexd.drda.traceAll
   *<BR>
   * Turns tracing on for all sessions.
   *<BR>
   * Default: false
   */
      public final static String DRDA_PROP_TRACEALL = "gemfirexd.drda.traceAll";

  public final static String DRDA_PROP_TRACE = "gemfirexd.drda.trace";

  /**
   * gemfirexd.drda.logConnections
   *<BR>
   * Indicates whether to log connections and disconnections.
   *<BR>
   * Default: false
   */
  public final static String DRDA_PROP_LOGCONNECTIONS = "gemfirexd.drda.logConnections";

  /**
   * gemfirexd.drda.minThreads
   * 
   * <BR>
   * 
   * Use the gemfirexd.drda.minThreads property to set the minimum number of
   * connection threads that Network Server will allocate. By default,
   * connection threads are allocated as needed.
   * 
   * <BR>
   * Default: 1
   */
  public final static String DRDA_PROP_MINTHREADS = "gemfirexd.drda.minThreads";

  /**
   * gemfirexd.drda.maxThreads
   * 
   * <BR>
   * 
   * Use the gemfirexd.drda.maxThreads property to set a maximum number of
   * connection threads that Network Server will allocate. If all of the
   * connection threads are currently being used and the Network Server has
   * already allocated the maximum number of threads, the threads will be shared
   * by using the <code>gemfirexd.drda.timeslice</code> property to determine when
   * sessions will be swapped. A value of 0 indicates unlimited number of
   * threads.
   * 
   * <BR>
   * 
   * Default: 0
   */
  public final static String DRDA_PROP_MAXTHREADS = "gemfirexd.drda.maxThreads";

  /**
   * gemfirexd.drda.timeSlice
   * 
   * <BR>
   * 
   * Use the gemfirexd.drda.timeSlice property to set the number of milliseconds
   * that each connection will use before yielding to another connection. This
   * property is relevant only if the gemfirexd.drda.maxThreads property is set to
   * a value greater than zero.
   * 
   * <BR>
   * 
   * Default: 0
   */
  public final static String DRDA_PROP_TIMESLICE = "gemfirexd.drda.timeSlice";

  /**
   * gemfirexd.drda.maxIdleTime
   * 
   * <BR>
   * 
   * The maximum time in milliseconds for a server thread to wait for a new
   * client session before terminating due to lack of activity. This helps
   * reduce the number of idle server side threads when the system is not busy.
   * Note that new threads will be created as required as per
   * {@value #DRDA_PROP_MAXTHREADS}, {@value #DRDA_PROP_TIMESLICE} properties.
   * 
   * <BR>
   * 
   * Default: 30000
   */
  public final static String DRDA_PROP_THREAD_MAXIDLETIME =
      "gemfirexd.drda.maxIdleTime";

  /**
   * System property to allow for node to be started even if there is a failure
   * in startup when replicating the DDLs executed in the cluster (e.g. if
   * external DB no longer available for a CREATE ASYNCEVENTLISTENER ...
   * DBSynchronizer).
   */
  public final static String DDLREPLAY_ALLOW_RESTART_WITH_ERRORS =
      "gemfirexd.datadictionary.allow-startup-errors";

  /**
   * gemfirexd.signals.notlogged
   *
   * <BR>
   *
   * When debug logging is enabled, Gemfirexd will record OS-level signals
   * received. This property adds to the list of default signals which are
   * <b>not</b> logged. If the wildcard '*' is specified, no signals will
   * be logged.
   *
   * <BR>
   *
   * Default: WINCH, CHLD, CONT, CLD, BUS
   */
  public final static String SIGNALS_NOT_LOGGED = "gemfirexd.signals.notlogged";
  
  /**
   * gemfire.nativelibrary.usedebugversion
   * 
   * <BR>
   * 
   * Use debug build of the shared library instead of optimized build. This should be tried before disabling the shared library
   * completely.
   * 
   */
  public final static String SHARED_LIBRARY_DEBUG_VERSION = com.gemstone.gemfire.internal.SharedLibrary.USE_DEBUG_VERSION;
  
  /**
   * gemfire.disable.sharedlibrary
   * 
   * <BR>
   * 
   * Disable shared library loading on supported platforms. This turns off attempt to
   * initialize libgemfirexd native library.
   * 
   */
  public final static String DISABLE_SHARED_LIBRARY = com.gemstone.gemfire.internal.SharedLibrary.DISABLE_NATIVE_LIBRARY;
  

}
