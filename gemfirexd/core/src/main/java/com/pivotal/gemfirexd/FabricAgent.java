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

import com.gemstone.gemfire.admin.jmx.Agent;

/**
 *  
 * @author soubhikc
 */
public interface FabricAgent extends FabricService, Agent {

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
