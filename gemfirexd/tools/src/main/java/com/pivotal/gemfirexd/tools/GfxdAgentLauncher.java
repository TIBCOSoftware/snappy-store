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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.admin.jmx.AgentConfig;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.OSProcess;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.FabricAgent;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.tools.internal.GfxdServerLauncher;

/**
 * An extension to {@link GfxdServerLauncher} for GemFireXD that is responsible
 * for administering a stand-alone GemFireXD JMX Agent.
 * 
 * @author soubhikc
 */
public class GfxdAgentLauncher extends GfxdServerLauncher {

  /** Should the launch command be printed? */
  private static final boolean PRINT_LAUNCH_COMMAND = Boolean
      .getBoolean(GfxdAgentLauncher.class.getSimpleName()
          + ".PRINT_LAUNCH_COMMAND");

  public GfxdAgentLauncher(String baseName) {
    super(baseName);
  }

  /**
   * Prints usage information of this program.
   */
  @Override
  protected void usage() throws IOException {
    final String script = LocalizedResource.getMessage("AGENT_SCRIPT");
    final String name = LocalizedResource.getMessage("AGENT_NAME");
    printUsage(LocalizedResource.getMessage("SERVER_HELP", script, name,
        LocalizedResource.getMessage("AGENT_ADDRESS_ARG"),
        LocalizedResource.getMessage("AGENT_EXTRA_HELP")),
        SanityManager.DEFAULT_MAX_OUT_LINES);
  }

  @Override
  protected final FabricAgent getFabricServiceInstance() throws Exception {
    return (FabricAgent)Class
        .forName("com.pivotal.gemfirexd.FabricServiceManager")
        .getMethod("getFabricAgentInstance").invoke(null);
  }

  @Override
  protected void startServerVM(Properties props) throws Exception {
    getFabricServiceInstance().start(props);
    this.bootProps = props;
  }

  @Override
  protected long getDefaultHeapSizeMB(boolean hostData) {
    return 1536L;
  }

  @Override
  protected long getDefaultSmallHeapSizeMB(boolean hostData) {
    return 768L;
  }

  @Override
  protected void processStartOption(String key, String value,
      Map<String, Object> m, List<String> vmArgs, Map<String, String> envArgs,
      Properties props) throws Exception {
    if (com.pivotal.gemfirexd.Attribute.AUTH_PROVIDER.equals(key)) {
      props.setProperty(com.pivotal.gemfirexd.Attribute.AUTH_PROVIDER, value);
    }
    else if (com.pivotal.gemfirexd.Attribute.SERVER_AUTH_PROVIDER.equals(key)) {
      props.setProperty(com.pivotal.gemfirexd.Attribute.SERVER_AUTH_PROVIDER, value);
    }
    else if (com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR.equalsIgnoreCase(key)
        && (value == null || value.length() == 0)) {
      readPassword(envArgs);
    }
    else if (WAIT_FOR_SYNC.equals(key)) {
      processWaitForSync(value);
    }
    else {
      processUnknownStartOption(key, value, m, vmArgs, props);
    }
  }

  @Override
  protected List<String> postProcessOptions(List<String> incomingVMArgs,
      final Map<String, Object> map) {

    if (processedDefaultGCParams) {
      return incomingVMArgs;
    }

    if (this.maxHeapSize == null && this.initialHeapSize == null) {
      return incomingVMArgs;
    }

    final ArrayList<String> vmArgs = new ArrayList<String>();
    if (jvmVendor != null
        && (jvmVendor.contains("Sun") || jvmVendor.contains("Oracle"))) {
      vmArgs.add("-XX:+UseParNewGC");
      vmArgs.add("-XX:+UseConcMarkSweepGC");
      vmArgs.add("-XX:CMSInitiatingOccupancyFraction=50");
    }

    vmArgs.addAll(incomingVMArgs);
    processedDefaultGCParams = true;

    return vmArgs;
  }

  @Override
  protected String getNetworkAddressArgName() {
    return null;
  }

  @Override
  protected String getNetworkPortArgName() {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void startAdditionalServices(Cache cache,
      Map<String, Object> options, Properties props) throws Exception {
  }

  @Override
  protected void redirectOutputToLogFile(InternalDistributedSystem system)
      throws Exception {
    final AgentConfig config = getFabricServiceInstance().getConfig();

    // see bug 43760
    if (config.getLogFile() == null || "".equals(config.getLogFile().trim())) {
      config.setLogFile(getBaseName(null) + ".log");
    }
    // redirect output to the configured log file
    OSProcess.redirectOutput(new File(config.getLogFile()));
  }

  /**
   * Bootstrap method to launch the GemFireXD JMX Agent process to monitor and
   * manage a GemFireXD Distributed System.
   * 
   * Main will read the arguments passed on the command line and dispatch the
   * command to the appropriate handler.
   */
  public static void main(String[] args) {
    // TODO is this only needed on 'agent server'? 'agent {start|stop|status}'
    // technically do no run any GemFire Cache
    // or DS code inside the current process.
    SystemFailure.loadEmergencyClasses();

    final GfxdAgentLauncher launcher = new GfxdAgentLauncher(
        "GemFireXD JMX Agent");
    launcher.run(args);
  }

  @Override
  protected String getBaseName(final String name) {
    return "gfxdagent";
  }

  @Override
  protected void addToServerCommand(List<String> cmds,
      Map<String, Object> options) {

    final ListWrapper<String> commandLineWrapper = new ListWrapper<String>(cmds);
    final Properties props = (Properties)options.get(PROPERTIES);

    for (final Object key : props.keySet()) {
      commandLineWrapper.add(key + "=" + props.getProperty(key.toString()));
    }

    if (props.getProperty(DistributionConfig.LOG_FILE_NAME) == null
        && isLoggingToStdOut()) {
      // Do not allow the cache server to log to stdout; override the logger
      // with #defaultLogFileName
      commandLineWrapper.add(DistributionConfig.LOG_FILE_NAME + "="
          + defaultLogFileName);
    }
  }

  @Override
  protected void printStartMessage(Map<String, Object> options,
      Properties props, int pid) throws Exception {
    // validate the GemFire properties
    final Properties configProps = new Properties();
    final Set<String> gfePropNames = getGFEPropNames();
    for (Map.Entry<Object, Object> entry : props.entrySet()) {
      String key = entry.getKey().toString();
      if (gfePropNames.contains(key)) {
        configProps.put(entry.getKey(), entry.getValue());
      }
    }
    printDiscoverySettings(options, configProps);
  }

  @Override
  protected Map<String, Object> getServerOptions(String[] args)
      throws Exception {
    final Map<String, Object> options = new HashMap<String, Object>();
    options.put(DIR, new File("."));
    workingDir = (File)options.get(DIR);

    final Properties props = new Properties();
    options.put(PROPERTIES, props);

    for (final String arg : args) {
      if (arg.equals("server")) {
        // expected
      }
      else if (arg.startsWith("-dir=")) {
        this.workingDir = processDirOption(options,
            arg.substring(arg.indexOf("=") + 1));
      }
      else if (arg.indexOf("=") > 1) {
        final int assignmentIndex = arg.indexOf("=");
        final String key = arg.substring(0, assignmentIndex);
        final String value = arg.substring(assignmentIndex + 1);

        if (key.startsWith("-")) {
          options.put(key.substring(1), value);
        }
        else {
          props.setProperty(key, value);
        }
      }
      else {
        throw new IllegalArgumentException(
            LocalizedStrings.CacheServerLauncher_UNKNOWN_ARGUMENT_0
                .toLocalizedString(arg));
      }
    }
    // any last minute processing of environment variables or otherwise
    processServerEnv(props);

    return options;
  }

  @Override
  protected boolean printLaunchCommand() {
    return PRINT_LAUNCH_COMMAND;
  }

  @Override
  protected void listAddOnArgs(boolean startsWithGemfire,
      boolean startsWithGemfirexd, boolean isPrefixHyphen) {
    // only applicable to start and if filter is not gemfire. or gemfirexd.
    if (!startsWithGemfire && !startsWithGemfirexd && isPrefixHyphen) {
      System.out.println("-" + CLASSPATH);
      System.out.println("-" + WAIT_FOR_SYNC);
    }
  }
}
