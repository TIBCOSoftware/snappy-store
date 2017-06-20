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

import java.sql.SQLException;
import java.util.Properties;

import com.gemstone.gemfire.internal.DistributionLocator;

/**
 * A <code>FabricLocator</code> is a singleton that provides locator services in
 * the distributed system allowing peers to discover one another. It also allows
 * remote clients to connect to the GemFireXD cluster(distributed system) in a
 * load-balanced manner while also providing transparent failover capability, so
 * that the cluster appears as a single entity to a client. A
 * <code>FabricLocator</code> is started by invoking the {@link #start} method
 * with configuration parameters as described <a
 * href="#configuration">below</a>. Use
 * <code>FabricServiceManager.getFabricLocatorInstance</code> to get a reference
 * to the <code>FabricLocator</code> singleton instance.
 * 
 * <P>
 * When a program calls {@link #start}, if <code>locators</code> is configured
 * then those locators provide redundancy for the locator service. This
 * parameter must be uniform across all locators and peers in the cluster to
 * provide consistent behaviour. All peers that are configured to use the same
 * one of the locators in the cluster are part of the same distributed
 * GemFireXD system.
 * 
 * <P>
 * The current version supports only a single instance of
 * <code>FabricLocator</code> in a virtual machine at a time. Invoking
 * {@link NetworkInterface} on the current virtual machine enables other
 * programs to connect to this virtual machine using the client JDBC driver.
 * Attempts to connect to multiple distributed systems (that is calling
 * {@link #start} multiple times with different configuration
 * <code>Properties</code>) will result in an {@link IllegalStateException}
 * being thrown. If <code>start</code> is invoked multiple times with equivalent
 * <code>Properties</code>, then no action is taken during subsequent calls. Use
 * {@link FabricServiceManager#getFabricLocatorInstance} to access the singleton
 * instance which can be stopped and reconnected using different
 * <code>Properties</code> for different distributed system connections.
 * 
 * <P>
 * 
 * <CENTER> <B><a name="configuration">Configuration</a></B> </CENTER>
 * 
 * <P>
 * Configuration properties are listed in the online documentation. Note that the
 * properties that relate to data storage or exchange will have no affect since
 * no data is hosted on locators.
 * <P>
 * Example: <PRE>
 *    Properties p = new Properties();
 *    p.setProperty("conserve-sockets", "false"); // Don't conserve socket connections; Go fast
 * 
 *    final FabricLocator locator = FabricServiceManager.getFabricLocatorInstance();
 *    locator.start("serv1", 13000, p);
 *    // Start the DRDA network server and listen for client connections
 *    server.startNetworkServer(null,-1, null); // use defaults ; port 1527
 * </PRE>
 * 
 * <P>
 * Properties can be also configured in a file called 'gemfirexd.properties' or
 * defined as system properties. GemFireXD looks for this file in the current
 * working directory, followed by 'gemfirexd.user.home' directory and then in
 * 'user.home' directory. The file name can be overridden using the system
 * property -Dgemfirexd.properties=&lt;property file&gt;. If this value is a
 * relative file system path then the above search is done. If it is an absolute
 * file system path then that file must exist; no search for it is done.
 * 
 * <P>
 * The actual configuration attribute values used to connect comes from the
 * following sources:
 * <OL>
 * <LI>System properties. If a system property named "<code>gemfirexd.</code>
 * <em>propertyName</em>" is defined and its value is not an empty string then
 * its value will be used for the named configuration attribute.
 * 
 * <LI>Code properties. Otherwise if a property is defined in the
 * <code>bootProperties</code> parameter object and its value is not an empty
 * string then its value will be used for that configuration attribute.
 * <LI>File properties. Otherwise if a property is defined in a configuration
 * property file found by this application and its value is not an empty string
 * then its value will be used for that configuration attribute. A configuration
 * property file may not exist. See the following section for how configuration
 * property files are found.
 * <LI>Defaults. Otherwise a default value is used.
 * </OL>
 * <P>
 * 
 * <P>
 * The primary differences between booting a distributed system using a first
 * JDBC connection using boot properties vs. using the FabricLocator API are the
 * following:
 * 
 * <ul>
 * <li>
 * Distributed Member ownership is given to the boot user when started using
 * this API whereas boot-up using a Connection doesn't give ownership to the
 * user. This makes a difference if SQLAuthorization is turned on. Distributed
 * member owners have specific privileges that cannot be delegated to others
 * like creating distributed system level users, granting / revoking privileges
 * from other users, etc..</li>
 * <li>
 * By default, a network server can only be started by having network properties
 * in the first connection in a virtual machine. What this means is that every
 * process, whether it hosts data or not, is capable of starting up a network
 * listening port immediately without giving applications a chance to complete
 * their own initialization. For example, an application might want to populate
 * certain master tables before accepting network client's requests. The
 * start-up sequence cannot be controlled in this way when using a JDBC
 * Connection directly, i.e. this server will be active immediately after
 * joining the distributed system.</li>
 * </ul>
 * 
 * @author swale
 */
public interface FabricLocator extends FabricService {

  /** the default bind-address for locator */
  static final String LOCATOR_DEFAULT_BIND_ADDRESS = "0.0.0.0";

  /** the default port for locator */
  static final int LOCATOR_DEFAULT_PORT = DistributionLocator.DEFAULT_LOCATOR_PORT;

  /**
   * Start the stand-alone locator singleton instance if not already started.
   * In case the locator has already been started then the old instance is
   * first stopped and then started again with the new properties.
   * Initiates and establishes connections with all the other peer members and
   * provided locator services in the distributed system but will not host any
   * tables or data. Clients will be able to use this node for connections if a
   * network server ( {@link #startNetworkServer}) has been started on this
   * locator. Such a client will receive server load information from the
   * locator so that actual data connections to fabric servers will be created
   * in a load balanced manner behind the scenes. It also allows clients to
   * transparently failover and reconnect to another server if the current data
   * connection to the server fails.
   * 
   * <P>
   * An GemFireXD system requiring locator services for peer discovery (as
   * opposed to using mcast-port) needs to start at least one locator in the
   * distributed system either as a stand-alone instance using this API, or
   * embedded in a {@link FabricServer} by providing "start-locator" boot
   * property to the {@link FabricServer#start} API. For production systems it
   * is recommended to use locator rather than mcast-port for peer discovery,
   * and a stand-alone locator should be preferred due to lower load than an
   * embedded one.
   * 
   * <P>
   * Apart from the properties provided to this method, they can be also
   * configured in a file called 'gemfirexd.properties' or defined as system
   * properties. GemFireXD looks for this file in the current working
   * directory, followed by 'gemfirexd.user.home' directory and then in
   * 'user.home' directory. The file name can be overridden using the system
   * property -Dgemfirexd.properties=&lt;property file&gt;. If this value is a
   * relative file system path then the above search is done. If it is an
   * absolute file system path then that file must exist; no search for it is
   * done.
   * 
   * <P>
   * The actual configuration attribute values used to connect comes from the
   * following sources:
   * <OL>
   * <LI>System properties. If a system property named "<code>gemfirexd.</code>
   * <em>propertyName</em>" is defined and its value is not an empty string then
   * its value will be used for the named configuration attribute.
   * 
   * <LI>Code properties. Otherwise if a property is defined in the
   * <code>bootProperties</code> parameter object and its value is not an empty
   * string then its value will be used for that configuration attribute.
   * <LI>File properties. Otherwise if a property is defined in a configuration
   * property file found by this application and its value is not an empty
   * string then its value will be used for that configuration attribute. A
   * configuration property file may not exist. See the following section for
   * how configuration property files are found.
   * <LI>Defaults. Otherwise a default value is used.
   * </OL>
   * <P>
   * If authentication is switched on, system user credentials must also be
   * passed to start the locator.
   * 
   * @param bindAddress
   *          The host name or IP address to bind the locator port. If this is
   *          null then binds to {@link #LOCATOR_DEFAULT_BIND_ADDRESS}.
   * @param port
   *          The port to bind the locator. A value &lt;= 0 will cause this
   *          to use the default port {@link #LOCATOR_DEFAULT_PORT}.
   * @param bootProperties
   *          Driver boot properties. If non-null, overrides default properties in
   *          'gemfirexd.properties'.
   * @throws SQLException
   */
  void start(String bindAddress, int port, Properties bootProperties)
      throws SQLException;

  /**
   * Start the stand-alone locator singleton instance if not already started.
   * Initiates and establishes connections with all the other peer members and
   * provided locator services in the distributed system but will not host any
   * tables or data. Clients will be able to use this node for connections if a
   * network server ( {@link #startNetworkServer}) has been started on this
   * locator. Such a client will receive server load information from the
   * locator so that actual data connections to fabric servers will be created
   * in a load balanced manner behind the scenes. It also allows clients to
   * transparently failover and reconnect to another server if the current data
   * connection to the server fails.
   * 
   * <P>
   * An GemFireXD system requiring locator services for peer discovery (as
   * opposed to using mcast-port) needs to start at least one locator in the
   * distributed system either as a stand-alone instance using this API, or
   * embedded in a {@link FabricServer} by providing "start-locator" boot
   * property to the {@link FabricServer#start} API. For production systems it
   * is recommended to use locator rather than mcast-port for peer discovery,
   * and a stand-alone locator should be preferred due to lower load than an
   * embedded one.
   * 
   * <P>
   * Apart from the properties provided to this method, they can be also
   * configured in a file called 'gemfirexd.properties' or defined as system
   * properties. GemFireXD looks for this file in the current working
   * directory, followed by 'gemfirexd.user.home' directory and then in
   * 'user.home' directory. The file name can be overridden using the system
   * property -Dgemfirexd.properties=&lt;property file&gt;. If this value is a
   * relative file system path then the above search is done. If it is an
   * absolute file system path then that file must exist; no search for it is
   * done.
   * 
   * <P>
   * The actual configuration attribute values used to connect comes from the
   * following sources:
   * <OL>
   * <LI>System properties. If a system property named "<code>gemfirexd.</code>
   * <em>propertyName</em>" is defined and its value is not an empty string then
   * its value will be used for the named configuration attribute.
   * 
   * <LI>Code properties. Otherwise if a property is defined in the
   * <code>bootProperties</code> parameter object and its value is not an empty
   * string then its value will be used for that configuration attribute.
   * <LI>File properties. Otherwise if a property is defined in a configuration
   * property file found by this application and its value is not an empty
   * string then its value will be used for that configuration attribute. A
   * configuration property file may not exist. See the following section for
   * how configuration property files are found.
   * <LI>Defaults. Otherwise a default value is used.
   * </OL>
   * <P>
   * If authentication is switched on, system user credentials must also be
   * passed to start the locator.
   * 
   * @param bindAddress
   *          The host name or IP address to bind the locator port. If this is
   *          null then binds to {@link #LOCATOR_DEFAULT_BIND_ADDRESS}.
   * @param port
   *          The port to bind the locator. A value &lt;= 0 will cause this
   *          to use the default port {@link #LOCATOR_DEFAULT_PORT}.
   * @param bootProperties
   *          Driver boot properties. If non-null, overrides default properties in
   *          'gemfirexd.properties'.
   * @param ignoreIfStarted if true then reuse any previous active instance,
   *                        else stop any previous instance and start a new
   *                        one with given properties
   * @throws SQLException
   */
  void start(String bindAddress, int port, Properties bootProperties,
      boolean ignoreIfStarted) throws SQLException;

  /**
   * Returns the locator status.
   * 
   * @return {@linkplain FabricService.State}
   */
  FabricService.State status();
}
