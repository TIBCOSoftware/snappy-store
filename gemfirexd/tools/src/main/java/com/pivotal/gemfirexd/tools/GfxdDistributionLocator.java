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

package com.pivotal.gemfirexd.tools;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionConfigImpl;
import com.gemstone.gemfire.internal.DistributionLocator;
import com.gemstone.gemfire.internal.cache.CacheServerLauncher;
import com.gemstone.gemfire.internal.lang.StringUtils;
import com.pivotal.gemfirexd.FabricLocator;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceUtils;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.tools.internal.GfxdServerLauncher;

/**
 * An extension to {@link GfxdServerLauncher} for GemFireXD that starts a peer
 * client with an embedded locator and a GemFireXD Network Server by default. This
 * allows for running SQL DMLs by JDBC clients which would not work in normal
 * GFE locators.
 * 
 * @author swale
 */
public class GfxdDistributionLocator extends GfxdServerLauncher {

  private static final String LOC_ADDRESS_ARG = "peer-discovery-address";
  private static final String LOC_PORT_ARG = "peer-discovery-port";
  private static final String LWC_PORT_ARG = "client-port";
  private static final String JMX_MANAGER_ARG = "jmx-manager";

  /** the bind address for the locator */
  private String bindAddress;

  /** the port for the locator to bind */
  private int port;

  /** Should the launch command be printed? */
  private static final boolean PRINT_LAUNCH_COMMAND = Boolean
      .getBoolean(GfxdDistributionLocator.class.getSimpleName()
          + ".PRINT_LAUNCH_COMMAND");

  public GfxdDistributionLocator(String baseName) {
    super(baseName);
    this.bindAddress = FabricLocator.LOCATOR_DEFAULT_BIND_ADDRESS;
    this.port = FabricLocator.LOCATOR_DEFAULT_PORT;
    // don't wait for DD initialization on locators by default
    this.waitForData = false;
  }

  @Override
  protected void initKnownOptions() {
    super.initKnownOptions();
    knownOptions.add(LOC_ADDRESS_ARG);
    knownOptions.add(LOC_PORT_ARG);
    knownOptions.add(JMX_MANAGER_ARG);
  }

  @Override
  public boolean hostData() {
    return false;
  }

  /**
   * Prints usage information of this program.
   */
  @Override
  protected void usage() throws IOException {
    final String script = LocalizedResource.getMessage("LOC_SCRIPT");
    final String name = LocalizedResource.getMessage("LOC_NAME");
    printUsage(LocalizedResource.getMessage("SERVER_HELP", script, name,
        LocalizedResource.getMessage("LOC_ADDRESS_ARG"),
        LocalizedResource.getMessage("LOC_EXTRA_HELP")),
        SanityManager.DEFAULT_MAX_OUT_LINES);
  }

  @Override
  protected FabricService getFabricServiceInstance() throws Exception {
    return (FabricService)Class
        .forName("com.pivotal.gemfirexd.FabricServiceManager")
        .getMethod("getFabricLocatorInstance").invoke(null);
  }

  /**
   * @see GfxdServerLauncher#startServerVM(Properties)
   */
  @Override
  protected void startServerVM(Properties props) throws Exception {
    ((FabricLocator)getFabricServiceInstance()).start(this.bindAddress,
        this.port, props);
    this.bootProps = props;
  }

  @Override
  protected long getDefaultHeapSizeMB(boolean hostData) {
    return 1024L;
  }

  @Override
  protected long getDefaultSmallHeapSizeMB(boolean hostData) {
    return 512L;
  }

  @Override
  protected void processStartOption(String key, String value,
      Map<String, Object> m, List<String> vmArgs, Map<String, String> envArgs,
      Properties props) throws Exception {
    if (LOC_ADDRESS_ARG.equals(key)) {
      m.put(LOC_ADDRESS_ARG, value);
    }
    else if(JMX_MANAGER_ARG.equals(key)){
      m.put(JMX_MANAGER_ARG, value);
    }
    else if (LOC_PORT_ARG.equals(key)) {
      try {
        final int locPort = Integer.parseInt(value);
        if (locPort < 1 || locPort > 65535) {
          String msg = LocalizedResource.getMessage("SERVER_INVALID_PORT",
              value);
          throw new IllegalArgumentException(msg);
        }
        m.put(LOC_PORT_ARG, value);
      } catch (NumberFormatException nfe) {
        String msg = LocalizedResource.getMessage("SERVER_INVALID_PORT", value);
        throw new IllegalArgumentException(msg, nfe);
      }
    }
    else {
      super.processStartOption(key, value, m, vmArgs, envArgs, props);
    }
  }

  @Override
  protected DistributionConfig printDiscoverySettings(
      final Map<String, Object> options, Properties props) throws SQLException {
    final Object locAddressObj = options.get(LOC_ADDRESS_ARG);
    final Object locPortObj = options.get(LOC_PORT_ARG);
    final String locators = props.getProperty(DistributionConfig.LOCATORS_NAME);
    String locAddress, locPort;
    if (locAddressObj == null
        || (locAddress = (String)locAddressObj).length() == 0) {
      locAddress = FabricLocator.LOCATOR_DEFAULT_BIND_ADDRESS;
    }
    if (locPortObj == null || (locPort = (String)locPortObj).length() == 0) {
      locPort = String.valueOf(DistributionLocator.DEFAULT_LOCATOR_PORT);
    }
    // perform GemFireXD specific customizations
    props.setProperty(DistributionConfig.START_LOCATOR_NAME, locAddress + '['
        + locPort + ']');
    props = FabricServiceUtils.preprocessProperties(props, null, null, true);
    // TODO: KN: Soubhik, why hardcoded log-file name below??
    props = FabricServiceUtils.filterGemFireProperties(props,
        "gemfirexdlocatortemp.log");

    if (locators != null && locators.length() > 0) {
      System.out.println(LocalizedResource.getMessage(
          "LOC_START_MESSAGE_WITH_LOCATORS", this.baseName, locAddress + '['
              + locPort + ']', locators));
    }
    else {
      System.out.println(LocalizedResource.getMessage("LOC_START_MESSAGE",
          this.baseName, locAddress + '[' + locPort + ']'));
    }
    return new DistributionConfigImpl(props);
  }

  @Override
  protected String getNetworkPortArgName() {
    return LWC_PORT_ARG;
  }

  /**
   * Main method that parses the command line and performs an will start, stop,
   * or get the status of a GemFireXD locator. This main method is also the main
   * method of the launched GemFireXD locator VM.
   */
  public static void main(String[] args) {
    final GfxdDistributionLocator launcher = new GfxdDistributionLocator(
        "SnappyData Locator");
    launcher.run(args);
  }

  @Override
  protected String getBaseName(final String name) {
    if (!StringUtils.isBlank(System.getenv("SNAPPY_HOME")))
      return "snappylocator";
    else
      return "gfxdlocator";
  }

  /** @see CacheServerLauncher#addToServerCommand */
  @Override
  protected void addToServerCommand(List<String> cmds,
      Map<String, Object> options) {
    super.addToServerCommand(cmds, options);
    final StringBuilder locOption = new StringBuilder();
    String locAddress = (String)options.get(LOC_ADDRESS_ARG);
    if (locAddress != null) {
      locOption.append('-').append(LOC_ADDRESS_ARG).append('=')
          .append(locAddress);
      cmds.add(locOption.toString());
    }
    String locPort = (String)options.get(LOC_PORT_ARG);
    if (locPort != null) {
      locOption.setLength(0);
      locOption.append('-').append(LOC_PORT_ARG).append('=')
          .append(Integer.parseInt(locPort));
      cmds.add(locOption.toString());
    }
    
    String manager = (String) options.get(JMX_MANAGER_ARG);
    if (null == manager) {
      locOption.setLength(0);
      locOption.append(JMX_MANAGER_ARG).append('=').append("true");
      cmds.add(locOption.toString());
    }

    
  }

  /** @see CacheServerLauncher#getServerOptions(String[]) */
  @Override
  protected Map<String, Object> getServerOptions(String[] args) throws Exception {
    final Map<String, Object> options = super.getServerOptions(args);
    this.bindAddress = (String)options.get(LOC_ADDRESS_ARG);
    if (this.bindAddress == null) {
      this.bindAddress = FabricLocator.LOCATOR_DEFAULT_BIND_ADDRESS;
    }

    Properties prop = (Properties) options.get(PROPERTIES);
    if (prop.getProperty(DistributionConfig.DISABLE_AUTO_RECONNECT_NAME) == null &&
            prop.getProperty(DistributionConfig.GEMFIRE_PREFIX
                    + DistributionConfig.DISABLE_AUTO_RECONNECT_NAME) == null) {
      prop.put(DistributionConfig.DISABLE_AUTO_RECONNECT_NAME, "false");
    }

    final String locPort = (String)options.get(LOC_PORT_ARG);
    if (locPort != null) {
      this.port = Integer.parseInt(locPort);
    }
    else {
      this.port = DistributionLocator.DEFAULT_LOCATOR_PORT;
    }
    return options;
  }

  @Override
  protected boolean printLaunchCommand() {
    return PRINT_LAUNCH_COMMAND;
  }

  @Override
  protected void listAddOnArgs(boolean startsWithGemfire,
      boolean startsWithGemfirexd, boolean isPrefixHyphen) {
    super.listAddOnArgs(startsWithGemfire, startsWithGemfirexd, isPrefixHyphen);
    // only applicable to start and if filter is not gemfire. or gemfirexd.
    if (!startsWithGemfire && !startsWithGemfirexd && isPrefixHyphen) {
      System.out.println("-" + LOC_ADDRESS_ARG);
      System.out.println("-" + LOC_PORT_ARG);
      System.out.println("-" + LWC_PORT_ARG);
    }
  }
}
