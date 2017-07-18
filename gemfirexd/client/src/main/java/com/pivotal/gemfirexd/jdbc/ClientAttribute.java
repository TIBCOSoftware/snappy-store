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

package com.pivotal.gemfirexd.jdbc;

import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

/**
 * List of all the connection properties used by the client driver. Do not put
 * any other properties in here since some parts (e.g. ODBC driver) depend on
 * getting the full list of available properties by doing a reflection on this
 * interface. Client side system properties (starting with "gemfirexd.") are fine
 * here since those are ignored by the ODBC driver.
 *
 * @author swale
 * @since 7.0
 */
public interface ClientAttribute {

  /**
   * The attribute that is used to set the user name.
   */
  String USERNAME = com.pivotal.gemfirexd.Attribute.USERNAME_ATTR;

  /**
   * The attribute that is used to set the user name.
   */
  String USERNAME_ALT = com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR;

  /**
   * The attribute that is used to set the user password.
   */
  String PASSWORD = com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR;

  /**
   * Read timeout for the connection, in seconds. Only for thin client
   * connections.
   */
  String READ_TIMEOUT = com.pivotal.gemfirexd.Attribute.READ_TIMEOUT;

  /**
   * TCP KeepAlive IDLE timeout in seconds for the network server and client
   * sockets. This is the idle time after which a TCP KeepAlive probe is sent
   * over the socket to determine if the other side is alive or not.
   */
  String KEEPALIVE_IDLE = com.pivotal.gemfirexd.Attribute.KEEPALIVE_IDLE;

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
  String KEEPALIVE_INTVL = com.pivotal.gemfirexd.Attribute.KEEPALIVE_INTVL;

  /**
   * TCP KeepAlive COUNT for the network server and client sockets. This is the
   * number of TCP KeepAlive probes sent before declaring the other side to be
   * dead.
   *
   * Note that this may not be supported by all platforms (e.g. Solaris), in
   * which case this will be ignored and an info-level message logged that the
   * option could not be enabled on the socket.
   */
  String KEEPALIVE_CNT = com.pivotal.gemfirexd.Attribute.KEEPALIVE_CNT;

  /**
   * Input buffer size to use for client-server sockets.
   */
  String SOCKET_INPUT_BUFFER_SIZE =
      com.pivotal.gemfirexd.Attribute.SOCKET_INPUT_BUFFER_SIZE;

  /**
   * Output buffer size to use for client-server sockets.
   */
  String SOCKET_OUTPUT_BUFFER_SIZE =
      com.pivotal.gemfirexd.Attribute.SOCKET_OUTPUT_BUFFER_SIZE;

  /**
   * If set to false then no load-balancing is attempted for JDBC client
   * connection, else the default is to try and load-balance the new one.
   */
  String LOAD_BALANCE = "load-balance";

  /**
   * Any alternative locators/servers that have to be tried when connecting to a
   * distributed system. This affects only the initial connection semantics
   * while the list of all the servers available in the system is determined and
   * kept updated by the client driver.
   */
  String SECONDARY_LOCATORS = "secondary-locators";

  /**
   * The property used to specify the server groups to use for a connection.
   */
  String SERVER_GROUPS = "server-groups";

  /**
   * The attribute that is used to set client single hop property.
   */
  String SINGLE_HOP_ENABLED = "single-hop-enabled";

  /**
   * The attribute that is used to set the internal connection size per
   * network server.
   */
  String SINGLE_HOP_MAX_CONNECTIONS = "single-hop-max-connections";
  /**
   * A connection level property (i.e. needs to be set on each connection) which
   * when set to true will disable streaming of results on query node. This will
   * make the performance slower and require more memory for large query results
   * but with more predictable results in case a server happens to go down while
   * iterating over a ResultSet. When this is not set then an SQLException with
   * state <code>{@value SQLState#GFXD_NODE_SHUTDOWN_PREFIX}</code> will be
   * thrown in case a node happens to go down in the middle of ResultSet
   * iteration and application has to retry the query. With this property set,
   * GemFireXD will always wait for at least one result from all the nodes and
   * disable chunking of results from nodes, so will always transparently
   * failover in such a scenario.
   */
  String DISABLE_STREAMING = com.pivotal.gemfirexd.Attribute.DISABLE_STREAMING;

  /**
   * A connection level property if set to true, would skip invocation of
   * listeners/writers/DBSynchronizer/AsyncEventListener on server.
   */
  String SKIP_LISTENERS = com.pivotal.gemfirexd.Attribute.SKIP_LISTENERS;

  /**
   * Connection property to skip primary key, foreign key, unique and check
   * constraint checks. For the case of primary key, an insert is converted into
   * PUT DML so row will retain the last values without throwing a constraint
   * violation.
   */
  String SKIP_CONSTRAINT_CHECKS =
      com.pivotal.gemfirexd.Attribute.SKIP_CONSTRAINT_CHECKS;

  /**
   * A connection level property to wait for 2nd phase commit to complete on all
   * nodes instead of returning as soon as current server's 2nd phase commit is
   * done.
   */
  String TX_SYNC_COMMITS = com.pivotal.gemfirexd.Attribute.TX_SYNC_COMMITS;

  /**
   * System property to set {@link #TX_SYNC_COMMITS} for all connections.
   */
  String GFXD_TX_SYNC_COMMITS = Property.GFXD_TX_SYNC_COMMITS;

  /**
   * [SQLFire]: System property to set {@link #TX_SYNC_COMMITS} for all
   * connections for old SQLFire product.
   */
  String SQLF_TX_SYNC_COMMITS = Property.SQLF_TX_SYNC_COMMITS;

  /**
   * A connection level property to disable {@link java.sql.Statement#cancel()}
   * over thin client.
   */
  String DISABLE_THINCLIENT_CANCEL =
      com.pivotal.gemfirexd.Attribute.DISABLE_THINCLIENT_CANCEL;

  /**
   * System property to set to disable {@link java.sql.Statement#cancel()}
   * over thin client.
   */
  String GFXD_DISABLE_THINCLIENT_CANCEL = Property.GFXD_DISABLE_THINCLINT_CANCEL;

  /**
   * A connection level property to disable all batching in transactions,
   * flushing ops immediately to detect conflicts immediately. Note that this
   * can have a significant performance impact so turn this on only if it is
   * really required by application for some reason.
   */
  String DISABLE_TX_BATCHING = com.pivotal.gemfirexd.Attribute.DISABLE_TX_BATCHING;

  /**
   * System property to set {@link #DISABLE_TX_BATCHING} for all
   * connections.
   */
  String GFXD_DISABLE_TX_BATCHING = Property.GFXD_DISABLE_TX_BATCHING;

  /**
   * [SQLFire] System property to set {@link #DISABLE_TX_BATCHING} for
   * all connections for old SQLFire product.
   */
  String SQLF_DISABLE_TX_BATCHING = Property.SQLF_DISABLE_TX_BATCHING;

  /**
   * A connection level property. If set to true, data in HDFS can be queried.
   * Otherwise, only in-memory data is queried.
   */
  String QUERY_HDFS = com.pivotal.gemfirexd.Attribute.QUERY_HDFS;

  /**
   * A connection level property. If value greater than zero, rows will be
   * batched for NCJ Query. Otherwise, batching will be disabled.
   *
   * @see com.pivotal.gemfirexd.Attribute#NCJ_BATCH_SIZE
   */
  String NCJ_BATCH_SIZE = com.pivotal.gemfirexd.Attribute.NCJ_BATCH_SIZE;

  /**
   * A connection level property. If value greater than zero, rows will be
   * cached with limited size for NCJ Query. Otherwise, caching will be
   * disabled.
   *
   * @see com.pivotal.gemfirexd.Attribute#NCJ_CACHE_SIZE
   */
  String NCJ_CACHE_SIZE = com.pivotal.gemfirexd.Attribute.NCJ_CACHE_SIZE;

  /**
   * A connection level property to disable all table and datadictionary locking
   * for operations from the connection. Can only be used by the admin user.
   * <p>
   * WARNING: USE THIS WITH CARE SINCE IT CAN CAUSE INCONSISTENCIES IN THE
   * CLUSTER IF CONCURRENT DDL OPERATIONS ARE BEING EXECUTED FROM ANOTHER
   * CONNECTION INTO THE CLUSTER.
   */
  String SKIP_LOCKS = com.pivotal.gemfirexd.Attribute.SKIP_LOCKS;

  /**
   * Property to change the default schema to use for a connection.
   * The default schema is normally the user name but this allows changing it.
   */
  String DEFAULT_SCHEMA = com.pivotal.gemfirexd.Attribute.DEFAULT_SCHEMA;

  /**
   * A connection level property to disable query routing.
   */
  String ROUTE_QUERY = com.pivotal.gemfirexd.Attribute.ROUTE_QUERY;

  /**
   * The GemFireXD log file path property.
   *
   * added by GemStone
   */
  String LOG_FILE = com.pivotal.gemfirexd.Attribute.LOG_FILE;

  /**
   * The GemFireXD log level property.
   */
  String LOG_LEVEL = "log-level";

  /**
   * Log file path to which the initialization nano time is appended.
   */
  String LOG_FILE_STAMP = "log-file-ns";

  /**
   * securityMechanism sets the DRDA mechanism in-use for the client
   */
  String CLIENT_SECURITY_MECHANISM =
      com.pivotal.gemfirexd.Attribute.CLIENT_SECURITY_MECHANISM;

  /**
   * traceFile sets the client side trace file. Client driver attribute.
   */
  String CLIENT_TRACE_FILE = "traceFile";

  /**
   * traceDirectory sets the client side trace directory. Client driver
   * attribute.
   */
  String CLIENT_TRACE_DIRECTORY = "traceDirectory";

  /**
   * traceFileAppend. Client driver attribute.
   */
  String CLIENT_TRACE_APPEND = "traceFileAppend";

  /**
   * traceLevel. Client driver attribute.
   */
  String CLIENT_TRACE_LEVEL = "traceLevel";

  /**
   * retrieveMessageText. Client driver attribute.
   */
  String CLIENT_RETRIEVE_MESSAGE_TEXT = "retrieveMessageText";

  /**
   * The attribute that is used to set client SSL mode.
   */
  String SSL = "ssl";

  /**
   * System property to denote that the driver is invoked using ODBC API.
   */
  String USING_ODBC_DRIVER = com.pivotal.gemfirexd.Attribute.USING_ODBC_DRIVER;

  /**
   * If true then use <code>TBinaryProtocol</code> for the thrift server, else
   * the default is to use <code>TCompactProtocol</code>.
   * <p>
   * This property can be specified when specifying each thrift server startup
   * in <code>FabricService.startThriftServer</code>.
   * <p>
   * TODO: SW: allow for specifying different protocols for locator control
   * connection vs data connection so that locators can always be generic base
   * configuration while servers can be mixed as required (e.g. if the cluster
   * has to service a mix of SSL and non-SSL clients, then common configuration
   * for locators will help use them for all clients if client configuration has
   * separate options for locator)
   */
  String THRIFT_USE_BINARY_PROTOCOL = "binary-protocol";

  /**
   * If true then use <code>TFramedTransport</code> for the thrift client,
   * else the default is to use non-framed transport. This should be used
   * for only specialized needs since it is less efficient than the normal
   * transport mechanism.
   * <p>
   * Unlike binary-protocol or SSL properties, there is no support in thrift
   * API to search for specific servers/locators having the corresponding
   * framed transport enabled (using <code>thrift-framed-transport</code>)
   * so all locators/servers in the system must also have framed transport
   * enabled for this to work on clients.
   */
  String THRIFT_USE_FRAMED_TRANSPORT = "framed-transport";

  /**
   * A comma-separated SSL property key=value pairs that can be set for a thrift
   * client connection. The available property values are:
   *
   * <li>
   * protocol: Protocol level to use e.g. TLSv1.2, TLS. See
   * SSLContext.getInstance(protocol).</li>
   *
   * <li>
   * enabled-protocols: A colon (":") separated list of precise protocols to
   * enable for the socket. Note that the "protocol" property specifies a family
   * e.g. TLSv1.2 will usually also support TLSv1.1, TLSv1 etc, so this can be
   * used to further restrict the enabled protocols. See
   * SSLSocket.setEnabledProtocols.</li>
   *
   * <li>
   * cipher-suites: A colon (":") separated list of cipher suites to enable for
   * the connection e.g. TLS_RSA_WITH_AES_256_CBC_SHA256. See
   * SSLSocket.setEnabledCipherSuites.</li>
   *
   * <li>
   * keystore: Path to the keystore file.</li>
   *
   * <li>
   * keystore-type: Type of the keystore file e.g. JKS. See
   * KeyStore.getInstance(type).</li>
   *
   * <li>
   * keystore-password: Password to read the keystore.</li>
   *
   * <li>
   * keymanager-type: Type of the keymanager e.g. . See
   * KeyManagerFactory.getInstance(algorithm)</li>
   *
   * <li>
   * truststore: Path to the truststore file.</li>
   *
   * <li>
   * truststore-type: Type of the truststore file e.g. JKS. See
   * KeyStore.getInstance(type).</li>
   *
   * <li>
   * truststore-password: Password to read the truststore.</li>
   *
   * <li>
   * trustmanager-type: Type of the trustmanager e.g. . See
   * TrustManagerFactory.getInstance(algorithm)</li>
   *
   * <li>
   * client-auth: If true, then require client connections to be authenticated.
   * Only applicable for server side connections. See
   * SSLSocket.setNeedClientAuth.</li>
   *
   * <p>
   * If this is not specified then default java SSL properties as per
   * "-Djavax.net.ssl.*" or system defaults will be used. See JSSE reference
   * guide and JCA Providers Documentation for more details of above properties.
   * <p>
   * This property can be specified when specifying each thrift server startup
   * in <code>FabricService.startThriftServer</code>.
   */
  String THRIFT_SSL_PROPERTIES = "ssl-properties";

  /*
   * The security mechanism to use by the thrift driver. Supported values are:
   *
   * <li>PLAIN: User name and password sent in plaintext. In addition to user
   * and password, a unique random token is generated by server and returned
   * to client that is required to be passed to server in every request for
   * verification</li>
   *
   * <li>DIFFIE_HELLMAN: Use Diffie-Hellman key exchange to generate AES
   * symmetric encryption keys to encrypt user and password. In addition this
   * will also encrypt the random token and refresh at regular intervals.</li>
   */
  // String THRIFT_SECURITY_MECHANISM = "security-mechanism";

  /**
   * The chunk size to be used for fetching/sending BLOBs and CLOBs over
   * a connection when using the thrift based JDBC driver.
   */
  String THRIFT_LOB_CHUNK_SIZE = "lob-chunk-size";

  /**
   * Use direct ByteBuffers when reading BLOBs. This will provide higher
   * performance avoiding a copy but caller must take care to free the BLOB
   * after use else cleanup may happen only in a GC cycle which may be delayed
   * due to no particular GC pressure due to direct ByteBuffers.
   */
  String THRIFT_LOB_DIRECT_BUFFERS = "lob-direct-buffers";

  /**
   * Set this to true to force using pre GemFireXD 1.3.0.2 release hashing
   * schema. This can be used if client is using {@link #SINGLE_HOP_ENABLED} for
   * a connection to a cluster containing pre 1.3.0.2 servers or data files.
   */
  String GFXD_USE_PRE1302_HASHCODE = Property.GFXD_USE_PRE1302_HASHCODE;
}
