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

import java.sql.Statement;

import com.gemstone.gemfire.internal.shared.LauncherBase;
import com.gemstone.gemfire.internal.shared.SystemProperties;

/**
 * List of all server connection (JDBC) attributes by the system.
 * 
 * @author soubhikc
 */
public interface Attribute {

  /**
   * The default GemFireXD protocol for data store or peer client.
   */
  String PROTOCOL = "jdbc:gemfirexd:";

  /**
   * [SQLFire] The default SQLFire protocol for data store or peer client for
   * old SQLFire product.
   */
  String SQLF_PROTOCOL = "jdbc:sqlfire:";

  /**
   * [SnappyData] The default SnappyData protocol for data store or peer client.
   */
  String SNAPPY_PROTOCOL = "jdbc:snappydata:";
  /**
   * [SnappyData] The default SnappyData protocol for data store or peer client.
   */
  String SNAPPY_POOL_PROTOCOL = "jdbc:snappydata:pool:";

  /**
   * The dummy name used for GemFireXD database.
   */
  String GFXD_DBNAME = "gemfirexd";

  /**
   * The dummy name used for GemFireXD booted with snappy url.
   */
  String SNAPPY_DBNAME = "snappydata";
  /**
   * [SQLFire] The dummy name used for old SQLFire database.
   */
  String SQLF_DBNAME = "sqlfire";

  /**
   * The SQLJ protocol for getting the default connection for server side jdbc.
   */
  String SQLJ_NESTED = "jdbc:default:connection";

  String SQLJ_NESTED_GEMFIREXD = "jdbc:default:gemfirexd:connection";

  String SQLJ_NESTED_SQLFIRE = "jdbc:default:sqlfire:connection";

  // @TODO why is SQLJ there? Just making it like above for now
  String SQLJ_NESTED_SNAPPYDATA = "jdbc:default:snappydata:connection";

  /**
   * The attribute that is used to request a shutdown.
   */
  String SHUTDOWN_ATTR = "shutdown";

  /**
   * The attribute that is used to set the user name.
   */
  String USERNAME_ATTR = "user";

  /**
   * Alternate attribute to set alternative 'UserName' connection attribute.
   */
  String USERNAME_ALT_ATTR = "UserName";

  /**
   * The attribute that is used to set the user password.
   */
  String PASSWORD_ATTR = "password";

  /**
   * Optional JDBC url attribute (at the database create time only) It can be
   * set to one of the following 2 values 1) UCS_BASIC (This means codepoint
   * based collation. This will also be the default collation used by Derby if
   * no collation attribute is specified on the JDBC url at the database create
   * time. This collation is what Derby 10.2 and prior have supported)
   * 2)TERRITORY_BASED (the collation will be based on language region specified
   * by the exisiting Derby attribute called territory. If the territory
   * attribute is not specified at the database create time, Derby will use
   * java.util.Locale.getDefault to determine the territory for the newly
   * created database.
   */
  String COLLATION = "collation";

  /**
   * The attribute that is used to set the connection's DRDA ID.
   */
  String DRDAID_ATTR = "drdaID";

  /**
   * Internal attributes. Mainly used by DRDA and Derby BUILTIN authentication
   * provider in some security mechanism context (SECMEC_USRSSBPWD).
   * 
   * DRDA_SECTKN_IN is the random client seed (RDs) DRDA_SECTKN_OUT is the
   * random server seed (RDr)
   */
  String DRDA_SECTKN_IN = "drdaSecTokenIn";

  String DRDA_SECTKN_OUT = "drdaSecTokenOut";

  /**
   * Internal attribute which holds the value of the securityMechanism attribute
   * specified by the client. Used for passing information about which security
   * mechanism to use from the network server to the embedded driver. Use
   * another name than "securityMechanism" in order to prevent confusion if an
   * attempt is made to establish an embedded connection with securityMechanism
   * specified (see DERBY-3025).
   */
  String DRDA_SECMEC = "drdaSecMec";

  // additional constants from shared.reference.Attribute to consolidate the two

  /**
   * A connection level property (i.e. needs to be set on each connection) which
   * when set to true will disable streaming of results on query node. This will
   * make the performance slower and require more memory for large query results
   * but with more predictable results in case a server happens to go down while
   * iterating over a ResultSet. When this is not set then an SQLException with
   * state <code>SQLState.GFXD_NODE_SHUTDOWN</code> will be thrown in case a
   * node happens to go down in the middle of ResultSet iteration and
   * application has to retry the query. With this property set, GemFireXD will
   * always wait for at least one result from all the nodes and disable chunking
   * of results from nodes, so will always transparently failover in such a
   * scenario.
   */
  String DISABLE_STREAMING = "disable-streaming";

  /**
   * A connection level property if set to true, would skip invocation of
   * listeners/writers/DBSynchronizer/AsyncEventListener on server.
   */
  String SKIP_LISTENERS = "skip-listeners";

  /**
   * A connection level property to use lock waiting mode for transactions
   * instead of the default "fail-fast" conflict mode. In the lock waiting mode,
   * the transaction commits will get serialized waiting for other ongoing
   * transactions instead of conflicting with them.
   */
  String ENABLE_TX_WAIT_MODE = "enable-tx-wait-mode";

  /**
   * A connection level property to disable all batching in transactions,
   * flushing ops immediately to detect conflicts immediately. Note that this
   * can have a significant performance impact so turn this on only if it is
   * really required by application for some reason.
   */
  String DISABLE_TX_BATCHING = "disable-tx-batching";

  /**
   * A connection level property to wait for 2nd phase commit to complete on all
   * nodes instead of returning as soon as local 2nd phase commit is done.
   */
  String TX_SYNC_COMMITS = "sync-commits";
  
  /**
   * A connection level property to disable {@link Statement#cancel()} 
   * over thin client.
   */
  String DISABLE_THINCLIENT_CANCEL = "disable-cancel";

  /**
   * The GemFireXD log file path property.
   */
  String LOG_FILE = LauncherBase.LOG_FILE;

  /**
   * The VM level property to specify the default initial capacity used for
   * underlying GFE regions on datastores.
   */
  String DEFAULT_INITIAL_CAPACITY_PROP = "default-initial-capacity";

  /**
   * A VM level property to specify the default recovery delay to be used (
   * <code>PartitionAttributesFactory.setRecoveryDelay(long)</code>. Can be
   * overridden for an individual table using the RECOVERYDELAY clause in CREATE
   * TABLE DDL. If not specified then the default recovery delay is
   * <code>PartitionAttributesFactory.RECOVERY_DELAY_DEFAULT</code>.
   */
  String DEFAULT_RECOVERY_DELAY_PROP = "default-recovery-delay";

  /**
   * Boot connection property used to denote that query time statistics should
   * be dumped using <code>GemFireXDQueryTimeStatistics</code>.
   */
  String DUMP_TIME_STATS_FREQ = "dump-time-stats-freq";

  /**
   * Connection property to enable collection of statistics at statement level.
   */
  String ENABLE_STATS = "enable-stats";

  /**
   * Connection property to enable collection of time statistics at statement
   * level.
   */
  String ENABLE_TIMESTATS = "enable-timestats";

  /**
   * The property used to specify the SQL scripts to be executed after VM
   * startup is complete including initial DDL replay.
   */
  String INIT_SCRIPTS = "init-scripts";

  /**
   * The property used to specify the server groups.
   */
  String SERVER_GROUPS = "server-groups";

  /**
   * The GemFireXD property used to specify whether this VM should host data or
   * not (i.e. whether data-store or an accessor).
   */
  String GFXD_HOST_DATA = LauncherBase.HOST_DATA;

  /** property name for enabling persistence of data dictionary */
  String GFXD_PERSIST_DD = "persist-dd";

  /**
   * Read timeout for the connection, in milliseconds. For network server and
   * client connections.
   */
  String READ_TIMEOUT = "read-timeout";

  /**
   * TCP KeepAlive IDLE timeout in seconds for the network server and client
   * sockets. This is the idle time after which a TCP KeepAlive probe is sent
   * over the socket to determine if the other side is alive or not.
   */
  String KEEPALIVE_IDLE = SystemProperties.KEEPALIVE_IDLE;

  /**
   * TCP KeepAlive INTERVAL timeout in seconds for the network server and client
   * sockets. This is the time interval between successive TCP KeepAlive probes
   * if there is no response to the previous probe ({@link #KEEPALIVE_IDLE}) to
   * determine if the other side is alive or not.
   * 
   * Note that this may not be supported by all platforms (e.g. Solaris), in
   * which case this will be ignored and an info-level message logged that the
   * option could not be enabled on the socket.
   */
  String KEEPALIVE_INTVL = SystemProperties.KEEPALIVE_INTVL;

  /**
   * TCP KeepAlive COUNT for the network server and client sockets. This is the
   * number of TCP KeepAlive probes sent before declaring the other side to be
   * dead.
   * 
   * Note that this may not be supported by all platforms (e.g. Solaris), in
   * which case this will be ignored and an info-level message logged that the
   * option could not be enabled on the socket.
   */
  String KEEPALIVE_CNT = SystemProperties.KEEPALIVE_CNT;

  /**
   * Input buffer size to use for client-server sockets.
   */
  String SOCKET_INPUT_BUFFER_SIZE = "socket-input-buffer-size";

  /**
   * Output buffer size to use for client-server sockets.
   */
  String SOCKET_OUTPUT_BUFFER_SIZE = "socket-output-buffer-size";

  /**
   * The default DataPolicy for tables is now replicated when no explicit
   * partitioning has been defined. The below property can be used to change
   * that default.
   */
  String TABLE_DEFAULT_PARTITIONED = "table-default-partitioned";

  /**
   * The property used to specify the base directory for Sql Fabric persistence
   * of Gateway Queues, Tables, Data Dictionary etc.
   */
  String SYS_PERSISTENT_DIR = "sys-disk-dir";

  /**
   * This property is used to configure Gemfirexd's root directory on HDFS. This
   * system configuration is provided by admin. All directories and files
   * created by GemfireXD will be created relative to this directory. Gemfirexd
   * JVM owner must have read and write permission on this directory. The value
   * must not contain the namenode url. The root directory is ignored if hdfs
   * stores's home directory is configured as absolute path (starting with "/").
   * The GemfireXD user must have read write permission on teh absolute provided
   * by the user.
   * 
   * Default: /user/gfxd
   */
  String SYS_HDFS_ROOT_DIR = "hdfs-root-dir";

  /**
   * Boot property to indicate that this is a stand-alone locator which is
   * really a kind of peer client with a network server running on it to enable
   * processing client locator related DML commands. However, a locator VM does
   * not have any tables or data.
   */
  String STAND_ALONE_LOCATOR = "standalone-locator";

  /**
   * Authentication scheme for client connections.
   */
  String AUTH_PROVIDER = "auth-provider";

  /**
   * Authentication scheme configuration for server-to-server communication.
   * 
   * Fully qualified java class name can be mentioned implementing
   * CredentialInitializer and UserAuthenticator interfaces
   */
  String SERVER_AUTH_PROVIDER = "server-auth-provider";

  /**
   * securityMechanism sets the DRDA mechanism in-use for the client
   */
  String CLIENT_SECURITY_MECHANISM = "securityMechanism";

  /**
   * property to set index persistence. By default for now it will be true.
   */
  String PERSIST_INDEXES = "persist-indexes";

  // system properties to set some of the above flags globally

  /**
   * Prefix when setting a boot/connection property as a system property.
   */
  String GFXD_PREFIX = "gemfirexd.";

  /**
   * [SQLFire] Prefix for old SQLFire product when setting a boot/connection
   * property as a system property.
   */
  String SQLF_PREFIX = "sqlfire.";

  /**
   * The protocol for Derby Network Client. This can be one of DRDA or Thrift
   * drivers depending on <code>ClientSharedUtils.USE_THRIFT_AS_DEFAULT</code>
   */
  String DNC_PROTOCOL = "jdbc:gemfirexd://";

  String SNAPPY_DNC_PROTOCOL = "jdbc:snappydata://";

  /**
   * The protocol for Derby DRDA Client.
   */
  String DRDA_PROTOCOL = "jdbc:gemfirexd:drda://";

  /**
   * The protocol for Derby DRDA Client.
   */
  String SNAPPY_DRDA_PROTOCOL = "jdbc:snappydata:drda://";

  /**
   * The protocol for GemFireXD Thrift Client.
   */
  String THRIFT_PROTOCOL = "jdbc:gemfirexd:thrift://";

  /**
   * The protocol for Derby DRDA Client.
   */
  String SNAPPY_THRIFT_PROTOCOL = "jdbc:snappydata:thrift://";

  /**
   * [SQLFire] The protocol for Derby Network Client for old SQLFire product.
   */
  String SQLF_DNC_PROTOCOL = "jdbc:sqlfire://";

  /**
   * User should use this prefix for the client attributes traceLevel and
   * traceDirectory when they are sending those attributes as JVM properties.
   * These 2 attributes can be sent through jdbc url directly (supported way) or
   * as JVM properties with the following prefix (undocumented way). DERBY-1275
   */
  String CLIENT_JVM_PROPERTY_PREFIX =
      SystemProperties.DEFAULT_GFXDCLIENT_PROPERTY_NAME_PREFIX;

  /**
   * System property to denote that the driver is invoked using ODBC API.
   */
  String USING_ODBC_DRIVER = "gemfirexd.driver.odbc";

  /**
   * System property to force using IP addresses rather than hostnames for
   * clients.
   */
  String PREFER_NETSERVER_IP_ADDRESS = "prefer-netserver-ipaddress";

  /**
   * System property to force using a different hostName/IP sent
   * to clients that will be exposed for external JDBC clients.
   */
  String HOSTNAME_FOR_CLIENTS = "hostname-for-clients";

  /**
   * The attribute that is used for the database name, from the JDBC notion of
   * jdbc:&lt;subprotocol&gt;:&lt;subname&gt;
   */
  String DBNAME_ATTR = "databaseName";

  /**
   * The attribute that is used to request a database create.
   */
  String CREATE_ATTR = "create";

  // below are not directly supported by GemFireXD

  /**
   * The attribute that is used to allow upgrade.
   */
  String UPGRADE_ATTR = "upgrade";

  /**
   * The protocol for the IBM Universal JDBC Driver
   * 
   */
  String JCC_PROTOCOL = "jdbc:derby:net:";

  /**
   * Connection property to enable access to HDFS data
   */
  String QUERY_HDFS = "query-HDFS";
  
  /**
   * Connection property to set batch size for NCJ
   */
  String NCJ_BATCH_SIZE = "ncj-batch-size";
  
  /**
   * Connection property to set cache size for NCJ
   */
  String NCJ_CACHE_SIZE = "ncj-cache-size";

  /**
   * Connection property to enable/disable query routing for Spark.
   */
  String ROUTE_QUERY = "route-query";

  String INTERNAL_CONNECTION = "internal-connection";

  /**
   * Embedded connection property to create tables as persistent by default
   * for the connection.
   */
  String DEFAULT_PERSISTENT = "default-persistent";

  /**
   * Property to enable bulk foreign keys checks for put all.
   */
  String ENABLE_BULK_FK_CHECKS = "enable-bulk-fk-checks";

  /**
   * Connection property to skip primary key, foreign key, unique and check
   * constraint checks. For the case of primary key, an insert is converted into
   * PUT DML so row will retain the last values without throwing a constraint
   * violation.
   */
  String SKIP_CONSTRAINT_CHECKS = "skip-constraint-checks";

  /**
   * A connection level property to set query timeout for statements executed
   * over the connection
   */
  String QUERY_TIMEOUT = "query-timeout";
  
  /**
   * Property to enable/disable Get/GetAll 
   * 
   * Only valid value is true
   */
  String DISABLE_GET_CONVERTIBLE = "disable-get-convertible";
  
  /**
   * Property to enable/disable GetAll on Local Index
   * 
   * Only valid value is true
   */
  String DISABLE_GETALL_LOCALINDEX = "disable-getall-local-index";
  
  /**
   * Property to enable/disable GetAll on Local Index in embedded gfe mode
   * 
   * Only valid value is true
   */
  String ENABLE_GETALL_LOCALINDEX_EMBED_GFE = "enable-getall-local-index-embed-gfe";

  /**
   * A connection level property to disable all table and datadictionary locking
   * for operations from the connection. Can only be used by the admin user.
   * <p>
   * WARNING: USE THIS WITH CARE SINCE IT CAN CAUSE INCONSISTENCIES IN THE
   * CLUSTER IF CONCURRENT DDL OPERATIONS ARE BEING EXECUTED FROM ANOTHER
   * CONNECTION INTO THE CLUSTER.
   */
  String SKIP_LOCKS = "skip-locks";

  /**
   * Property to change the default schema to use for a connection.
   * The default schema is normally the user name but this allows changing it.
   */
  String DEFAULT_SCHEMA = "default-schema";

  /**
   * Whether to use selector based server for the thrift server or
   * per connection thread server model. Use with care since it is not
   * fully tested yet. Default is false.
   */
  String THRIFT_SELECTOR_SERVER = "thrift-selector";

  /**
   * If true then use <code>TBinaryProtocol</code> for the thrift server, else
   * the default is to use <code>TCompactProtocol</code>.
   * <p>
   * This property can be specified when specifying each thrift server startup
   * in <code>FabricService.startThriftServer</code>.
   */
  String THRIFT_USE_BINARY_PROTOCOL = "thrift-binary-protocol";

  /**
   * If true then use <code>TFramedTransport</code> for the thrift server, else
   * the default is to use non-framed transport.
   * <p>
   * This property can be specified for each thrift server startup in
   * <code>FabricService.startThriftServer</code>.
   * <p>
   * WARNING: This is not recommended due to addional overheads and no gains,
   * and also not as well tested. Only use for external connectors that don't
   * support non-framed mode (reportedly elixir-thrift driver).
   */
  String THRIFT_USE_FRAMED_TRANSPORT = "thrift-framed-transport";

  /**
   * If true then use SSL sockets for thrift server.
   * <p>
   * This property can be specified when specifying each thrift server startup
   * in <code>FabricService.startThriftServer</code>.
   */
  String THRIFT_USE_SSL = "thrift-ssl";

  /**
   * A comma-separate SSL property key,value pairs. The available property
   * values are:
   *
   * <ul>
   * <li><i>protocol</i>: default "TLS", see
   * <a href="https://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#SSLContext">
   *   JCA docs</a></li>
   * <li><i>enabled-protocols</i>: enabled protocols separated by ":"</li>
   * <li><i>cipher-suites</i>: enabled cipher suites separated by ":", see
   * <a href="https://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#ciphersuites">
   *   JCA docs</a></li>
   * <li><i>client-auth=(true|false)</i>: if client also needs to be authenticated, see
   * <a href="https://docs.oracle.com/javase/7/docs/api/javax/net/ssl/SSLServerSocket.html#setNeedClientAuth(boolean)">
   *   JCA docs</a></li>
   * <li><i>keystore</i>: path to key store file</li>
   * <li><i>keystore-type</i>: the type of key-store (default "JKS"), see
   * <a href="https://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#KeyStore">
   *   JCA docs</a></li>
   * <li><i>keystore-password</i>: password for the key store file</li>
   * <li><i>keymanager-type</i>: the type of key manager factory, see
   * <a href="https://docs.oracle.com/javase/7/docs/technotes/guides/security/jsse/JSSERefGuide.html#KeyManagerFactory">
   *   JSSE docs</a></li>
   * <li><i>truststore</i>: path to trust store file</li>
   * <li><i>truststore-type</i>: the type of trust-store (default "JKS"), see
   * <a href="https://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#KeyStore">
   *   JCA docs</a></li>
   * <li><i>truststore-password</i>: password for the trust store file</li>
   * <li><i>trustmanager-type</i>: the type of trust manager factory, see
   * <a href="https://docs.oracle.com/javase/7/docs/technotes/guides/security/jsse/JSSERefGuide.html#TrustManagerFactory">
   *   JSSE docs</a></li>
   * </ul>
   * 
   * <p>
   * If this is not specified then default java SSL properties as per
   * "-Djavax.net.ssl.*" or system defaults will be used.
   * <p>
   * This property can be specified when specifying each thrift server startup
   * in <code>FabricService.startThriftServer</code>.
   */
  String THRIFT_SSL_PROPERTIES = "thrift-ssl-properties";

  /**
   * Property to enable metadata query prepare over the connection.
   */
  String ENABLE_METADATA_PREPARE = "enable-metadata-prepare";
  
  /**
   * List of users with read-only connection level authorization.
   */
  String AUTHZ_READ_ONLY_ACCESS_USERS = "authz-read-only-access-users";

  /**
   * List of users with full access connection level authorization.
   */
  String AUTHZ_FULL_ACCESS_USERS = "authz-full-access-users";
}
