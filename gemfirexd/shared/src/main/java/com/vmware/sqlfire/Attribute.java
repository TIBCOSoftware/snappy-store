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

package com.vmware.sqlfire;

import java.sql.Statement;

/**
 * List of all server connection (JDBC) attributes by the system.
 * 
 * @author soubhikc
 */
//SQLF:BC
public interface Attribute {

  /**
   * The default sqlfire protocol for data store or peer client.
   */
  String PROTOCOL = "jdbc:sqlfire:";

  /**
   * The dummy name used for sqlfire database.
   */
  String SQLF_DBNAME = "sqlfire";

  /**
   * The SQLJ protocol for getting the default connection for server side jdbc.
   */
  String SQLJ_NESTED = "jdbc:default:connection";

  String SQLJ_NESTED_SQLFIRE = "jdbc:default:sqlfire:connection";

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
   * state <code>SQLState.SQLF_NODE_SHUTDOWN</code> will be thrown in case a
   * node happens to go down in the middle of ResultSet iteration and
   * application has to retry the query. With this property set, sqlfire will
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
   * The sqlfire log file path property.
   * 
   * added by GemStone
   */
  String LOG_FILE = "log-file";

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
   * be dumped using <code>sqlfireQueryTimeStatistics</code>.
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
   * The sqlfire property used to specify whether this VM should host data or
   * not (i.e. whether data-store or an accessor).
   */
  String SQLF_HOST_DATA = "host-data";

  /** property name for enabling persistence of data dictionary */
  String SQLF_PERSIST_DD = "persist-dd";

  /**
   * TCP KeepAlive IDLE timeout in seconds for the network server and client
   * sockets. This is the idle time after which a TCP KeepAlive probe is sent
   * over the socket to determine if the other side is alive or not.
   */
  String KEEPALIVE_IDLE = "keepalive-idle";

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
  String KEEPALIVE_INTVL = "keepalive-interval";

  /**
   * TCP KeepAlive COUNT for the network server and client sockets. This is the
   * number of TCP KeepAlive probes sent before declaring the other side to be
   * dead.
   * 
   * Note that this may not be supported by all platforms (e.g. Solaris), in
   * which case this will be ignored and an info-level message logged that the
   * option could not be enabled on the socket.
   */
  String KEEPALIVE_CNT = "keepalive-count";

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
   * This property is used to configure sqlfire's root directory on HDFS. This
   * system configuration is provided by admin. All directories and files
   * created by sqlfire will be created relative to this directory. sqlfire JVM
   * owner must have read and write permission on this directory. The value must
   * not contain the namenode url. The root directory is ignored if hdfs
   * stores's home directory is configured as absolute path (starting with "/").
   * The sqlfire user must have read write permission on teh absolute provided
   * by the user.
   * 
   * Default: /user/SQLF
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
  String SQLF_PREFIX = "sqlfire.";

  /**
   * The protocol for Derby Network Client.
   */
  String DNC_PROTOCOL = "jdbc:sqlfire://";

  /**
   * User should use this prefix for the client attributes traceLevel and
   * traceDirectory when they are sending those attributes as JVM properties.
   * These 2 attributes can be sent through jdbc url directly (supported way) or
   * as JVM properties with the following prefix (undocumented way). DERBY-1275
   */
  String CLIENT_JVM_PROPERTY_PREFIX = "sqlfire.client.";

  /**
   * System property to denote that the driver is invoked using ODBC API.
   */
  String USING_ODBC_DRIVER = "sqlfire.driver.odbc";

  /**
   * System property to set {@link #ENABLE_TX_WAIT_MODE} for all connections.
   */
  String SQLF_ENABLE_TX_WAIT_MODE = SQLF_PREFIX + ENABLE_TX_WAIT_MODE;

  /**
   * System property to set {@link #DISABLE_TX_BATCHING} for all connections.
   */
  String SQLF_DISABLE_TX_BATCHING = SQLF_PREFIX + DISABLE_TX_BATCHING;

  /**
   * System property to set {@link #TX_SYNC_COMMITS} for all connections.
   */
  String SQLF_TX_SYNC_COMMITS = SQLF_PREFIX + TX_SYNC_COMMITS;

  /**
   * System property to set index persistence. By default for now it will be
   * true.
   */
  String SQLF_PERSIST_INDEXES = SQLF_PREFIX + PERSIST_INDEXES;

  /**
   * System property to force using IP addresses rather than hostnames for
   * clients.
   */
  String PREFER_NETSERVER_IP_ADDRESS = "prefer-netserver-ipaddress";

  /**
   * The attribute that is used for the database name, from the JDBC notion of
   * jdbc:<subprotocol>:<subname>
   */
  String DBNAME_ATTR = "databaseName";

  /**
   * The attribute that is used to request a database create.
   */
  String CREATE_ATTR = "create";

  // below are not directly supported by sqlfire

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
}
