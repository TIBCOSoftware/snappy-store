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

/**
 * A <code>FabricServer</code> is a singleton that allows remote clients to connect to
 * the GemFireXD cluster(distributed system). It is a peer member of the
 * distributed system. A <code>FabricServer</code> is
 * started by invoking the {@link #start} method with configuration parameters
 * as described
 * <a href="#configuration">below</a>. Use <code>FabricServiceManager</code> to
 * get a reference to the <code>FabricServer</code> singleton instance.
 *
 * <P>
 * When a program calls {@link #start}, if <code>start-locator</code> is configured
 * then an embedded locator is started. Else, among other things a distribution
 * manager is started that will attempt to join the distributed system either using
 * the locators configuration parameter or using mcast (if mcast-port is set).
 * All connections that are configured to use the same multicast address/port and
 * the same locators are part of the same distributed FabricServer system.
 *
 * <P>
 * The current version supports only a single instance of <code>FabricServer</code>
 * in a virtual machine at a time.
 * Invoking {@link NetworkInterface} on the current virtual machine enables
 * other programs to connect to this virtual machine using the client JDBC driver.
 * Attempts to connect to multiple distributed systems (that is calling
 * {@link #start} multiple times with different configuration
 * <code>Properties</code>) will result in an {@link IllegalStateException}
 * being thrown. If <code>start</code> is invoked multiple times with equivalent
 * <code>Properties</code>, then no action is taken during subsequent calls.
 * Use {@link FabricServiceManager} to access the singleton
 * instance which can be stopped and reconnected using different
 * <code>Properties</code> for different distributed system connections.
 *
 * <P>
 * Note that the application can also start/stop the <code>FabricServer</code>
 * instance when creating the first JDBC connection with the requisite boot properties.
 * The properties used to bootstrap the server instance is same as the connection
 * properties.
 * <P>
 *
 * <CENTER> <B><a name="configuration">Configuration</a></B> </CENTER>
 *
 * <P>
 * Configuration properties are listed in the online documentation.
 * <P>
 * Example:
 * <pre>
 *    Properties p = new Properties();
 *    p.setProperty("mcast-port", "12444"); // All members in the cluster use the same mcast-port
 *    p.setProperty("conserve-sockets", "false"); // Don't conserve socket connections; Go fast
 *    p.setProperty("statistic-sampling-enabled", "true"); // Write out performance statistics
 *
 *    final FabricServer server = FabricServiceManager.getFabricServerInstance();
 *    server.start(p);
 *    // Start the DRDA network server and listen for client connections
 *    server.startNetworkServer(null,-1, null); // use defaults ; port 1527
 * </pre>
 *
 * <P>
 * Properties can be also configured in a file called 'gemfirexd.properties' or
 * defined as system properties. GemFireXD looks for this file in the current working
 * directory, followed by 'gemfirexd.system.home' directory and then in 'user.home' directory.
 * The file name can be overridden using the system property
 * -Dgemfirexd.properties=&lt;property file&gt;.
 * If this value is a relative file system path then the above search is done.
 * If it is an absolute file system path then that file must exist; no search for it is done.
 *
 * <P>
 * The actual configuration attribute values used to connect comes from the following sources:
   * <OL>
   * <LI>System properties. If a system property named
   *     "<code>gemfirexd.</code><em>propertyName</em>" is defined
   *     and its value is not an empty string
   *     then its value will be used for the named configuration attribute.
   *
   * <LI>Code properties. Otherwise if a property is defined in the <code>bootProperties</code>
   *     parameter object and its value is not an empty string
   *     then its value will be used for that configuration attribute.
   * <LI>File properties. Otherwise if a property is defined in a configuration property
   *     file found by this application and its value is not an empty string
   *     then its value will be used for that configuration attribute.
   *     A configuration property file may not exist.
   *     See the following section for how configuration property files are found.
   * <LI>Defaults. Otherwise a default value is used.
   * </OL>
   * <P>

 * <P>
 * The primary differences between booting a distributed system using a first
 * JDBC connection using boot properties vs. using the FabricServer API are the
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
 * @author Soubhik C
 */
public interface FabricServer extends FabricService {

  /**
   * Start the GemFireXD server singleton instance if not already started.
   * In case the server has already been started then the old instance is
   * first stopped and then started again with the new properties.
   * Initiates and establishes connections with all the other peer members.
   *
   * <P>
   * Properties can be also configured in a file called 'gemfirexd.properties' or
   * defined as system properties. GemFireXD looks for this file in 'gemfirexd.user.home' 
   * directory, if set, otherwise in the current working directory, followed by 
   * 'user.home' directory.
   * The file name can be overridden using the system property
   * -Dgemfirexd.properties=&lt;property file&gt;.
   * If this value is a relative file system path then the above search is done.
   * If it is an absolute file system path then that file must exist; no search for it is done.
   *
   * <P>
   * The actual configuration attribute values used to connect comes from the following sources:
   * <OL>
   * <LI>System properties. If a system property named
   *     "<code>gemfirexd.</code><em>propertyName</em>" is defined
   *     and its value is not an empty string
   *     then its value will be used for the named configuration attribute.
   *
   * <LI>Code properties. Otherwise if a property is defined in the <code>bootProperties</code>
   *     parameter object and its value is not an empty string
   *     then its value will be used for that configuration attribute.
   * <LI>File properties. Otherwise if a property is defined in a configuration property
   *     file found by this application and its value is not an empty string
   *     then its value will be used for that configuration attribute.
   *     A configuration property file may not exist.
   *     See the following section for how configuration property files are found.
   * <LI>Defaults. Otherwise a default value is used.
   * </OL>
   * <P>
   * If authentication is switched on, system user credentials must also be passed
   * to start the server
   *
   * @param bootProperties
   *          Driver boot properties. If non-null, overrides default properties in
   *          'gemfirexd.properties'.
   * @throws SQLException
   */
  void start(Properties bootProperties) throws SQLException;

  /**
   * Start the GemFireXD server singleton instance if not already started.
   * Initiates and establishes connections with all the other peer members.
   *
   * <P>
   * Properties can be also configured in a file called 'gemfirexd.properties' or
   * defined as system properties. GemFireXD looks for this file in 'gemfirexd.user.home' 
   * directory, if set, otherwise in the current working directory, followed by 
   * 'user.home' directory.
   * The file name can be overridden using the system property
   * -Dgemfirexd.properties=&lt;property file&gt;.
   * If this value is a relative file system path then the above search is done.
   * If it is an absolute file system path then that file must exist; no search for it is done.
   *
   * <P>
   * The actual configuration attribute values used to connect comes from the following sources:
   * <OL>
   * <LI>System properties. If a system property named
   *     "<code>gemfirexd.</code><em>propertyName</em>" is defined
   *     and its value is not an empty string
   *     then its value will be used for the named configuration attribute.
   *
   * <LI>Code properties. Otherwise if a property is defined in the <code>bootProperties</code>
   *     parameter object and its value is not an empty string
   *     then its value will be used for that configuration attribute.
   * <LI>File properties. Otherwise if a property is defined in a configuration property
   *     file found by this application and its value is not an empty string
   *     then its value will be used for that configuration attribute.
   *     A configuration property file may not exist.
   *     See the following section for how configuration property files are found.
   * <LI>Defaults. Otherwise a default value is used.
   * </OL>
   * <P>
   * If authentication is switched on, system user credentials must also be passed
   * to start the server
   *
   * @param bootProperties
   *          Driver boot properties. If non-null, overrides default properties in
   *          'gemfirexd.properties'.
   * @param ignoreIfStarted if true then reuse any previous active instance,
   *                        else stop any previous instance and start a new
   *                        one with given properties
   * @throws SQLException
   */
  void start(Properties bootProperties, boolean ignoreIfStarted)
      throws SQLException;

  /**
   * Returns the fabric server status.
   * 
   * @return {@linkplain FabricService.State}
   */
  FabricService.State status();
}
