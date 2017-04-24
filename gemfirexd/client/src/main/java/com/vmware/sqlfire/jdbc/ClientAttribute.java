package com.vmware.sqlfire.jdbc;

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

import java.sql.Statement;

import com.vmware.sqlfire.Attribute;
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
  String USERNAME = com.vmware.sqlfire.Attribute.USERNAME_ATTR;

  /**
   * The attribute that is used to set the user name.
   */
  String USERNAME_ALT = com.vmware.sqlfire.Attribute.USERNAME_ALT_ATTR;

  /**
   * The attribute that is used to set the user password.
   */
  String PASSWORD = com.vmware.sqlfire.Attribute.PASSWORD_ATTR;

  /**
   * Read timeout for the connection, in milliseconds. Only for thin client
   * connections.
   */
  String READ_TIMEOUT = "read-timeout";

  /**
   * TCP KeepAlive IDLE timeout in seconds for the network server and client
   * sockets. This is the idle time after which a TCP KeepAlive probe is sent
   * over the socket to determine if the other side is alive or not.
   */
  String KEEPALIVE_IDLE = com.vmware.sqlfire.Attribute.KEEPALIVE_IDLE;

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
  String KEEPALIVE_INTVL = com.vmware.sqlfire.Attribute.KEEPALIVE_INTVL;

  /**
   * TCP KeepAlive COUNT for the network server and client sockets. This is the
   * number of TCP KeepAlive probes sent before declaring the other side to be
   * dead.
   * 
   * Note that this may not be supported by all platforms (e.g. Solaris), in
   * which case this will be ignored and an info-level message logged that the
   * option could not be enabled on the socket.
   */
  String KEEPALIVE_CNT = com.vmware.sqlfire.Attribute.KEEPALIVE_CNT;

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
  String DISABLE_STREAMING = com.vmware.sqlfire.Attribute.DISABLE_STREAMING;

  /**
   * A connection level property if set to true, would skip invocation of
   * listeners/writers/DBSynchronizer/AsyncEventListener on server.
   */
  String SKIP_LISTENERS = com.vmware.sqlfire.Attribute.SKIP_LISTENERS;

  /**
   * Connection property to skip primary key, foreign key, unique and check
   * constraint checks. For the case of primary key, an insert is converted into
   * PUT DML so row will retain the last values without throwing a constraint
   * violation.
   */
  String SKIP_CONSTRAINT_CHECKS =
      com.vmware.sqlfire.Attribute.SKIP_CONSTRAINT_CHECKS;

  /**
   * A connection level property to wait for 2nd phase commit to complete on all
   * nodes instead of returning as soon as current server's 2nd phase commit is
   * done.
   */
  String TX_SYNC_COMMITS = com.vmware.sqlfire.Attribute.TX_SYNC_COMMITS;

  /**
   * System property to set {@link Attribute#TX_SYNC_COMMITS} for all connections.
   */
  String SQLF_TX_SYNC_COMMITS = Property.SQLF_TX_SYNC_COMMITS;
  
  /**
   * A connection level property to disable {@link Statement#cancel()} 
   * over thin client.
   */
  String DISABLE_THINCLIENT_CANCEL = com.vmware.sqlfire.Attribute.DISABLE_THINCLIENT_CANCEL;
  
  /**
   * System property to set {@link Attribute#DISABLE_THINCLIENT_CANCEL} for 
   * all thin client connections.
   */
  String SQLF_THINCLIENT_CANCEL = Property.SQLF_DISABLE_THINCLINT_CANCEL;
  
  
  /**
   * A connection level property to disable all batching in transactions,
   * flushing ops immediately to detect conflicts immediately. Note that this
   * can have a significant performance impact so turn this on only if it is
   * really required by application for some reason.
   */
  String DISABLE_TX_BATCHING = com.vmware.sqlfire.Attribute.DISABLE_TX_BATCHING;
  
  /**
   * System property to set {@link Attribute#DISABLE_TX_BATCHING} for all connections.
   */
  String SQLF_DISABLE_TX_BATCHING = Property.SQLF_DISABLE_TX_BATCHING;

  /**
   * A connection level property. If set to true, data in HDFS can be queried.
   * Otherwise, only in-memory data is queried.
   */
  String QUERY_HDFS = com.vmware.sqlfire.Attribute.QUERY_HDFS;

  /**
   * The GemFireXD log file path property.
   * 
   * added by GemStone
   */
  String LOG_FILE = com.vmware.sqlfire.Attribute.LOG_FILE;

  /**
   * Log file path to which the initialization nano time is appended.
   */
  String LOG_FILE_STAMP = "log-file-ns";

  /**
   * securityMechanism sets the DRDA mechanism in-use for the client
   */
  String CLIENT_SECURITY_MECHANISM =
      com.vmware.sqlfire.Attribute.CLIENT_SECURITY_MECHANISM;

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
  String USING_ODBC_DRIVER = com.vmware.sqlfire.Attribute.USING_ODBC_DRIVER;

}
