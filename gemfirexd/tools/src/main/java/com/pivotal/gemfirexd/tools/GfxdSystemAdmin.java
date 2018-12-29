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

import java.io.Console;
import java.io.File;
import java.sql.SQLException;
import java.util.*;

import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionConfigImpl;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.GemFireTerminateError;
import com.gemstone.gemfire.internal.GemFireUtilLauncher;
import com.gemstone.gemfire.internal.SystemAdmin;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.FabricServer;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.internal.GemFireXDVersion;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdShutdownAllRequest;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.tools.internal.GfxdServerLauncher;

/**
 * This is a utility class to launch various sub-commands of the "gemfire"
 * script utility (using {@link SystemAdmin} class) directly using the "gfxd"
 * script (see bug #41469). It uses a {@link ThreadLocal} to store the current
 * command to be executed and invokes the {@link SystemAdmin} class overriding
 * methods as appropriate for GemFireXD.
 * 
 * @author swale
 */
public class GfxdSystemAdmin extends SystemAdmin {

  /** the default license file used for GemFireXD */
  final static File DEFAULT_LICENSE_FILE = new File("gemfirexdLicense.zip");

  /**
   * Commands from {@link SystemAdmin} that are not used in GemFireXD and have
   * been removed.
   */
  protected final static HashSet<String> removedCommands = new HashSet<String>();

  protected final static HashMap<String, String> modifiedUsageInfo =
    new HashMap<String, String>();

  protected final static HashMap<String, String> modifiedHelpInfo =
    new HashMap<String, String>();

  // keep the commands here in sorted order since Arrays.binarySearch is used
  final static String[] commandsWithDSProps = new String[] { "backup",
      "compact-all-disk-stores", "encrypt-password",
      "list-missing-disk-stores", "print-stacks", "revoke-missing-disk-store",
      "shut-down-all", "unblock-disk-store"
  };

  private static final String INCLUDE_ADMINS = "-include-admins";
  private static final String SKIP_ACCESSORS = "-skip-accessors";
  private static final String PASSWORD_FOR_EXTERNAL = "external";
  private static final String PASSWORD_TRANSFORM = "-transformation";
  private static final String PASSWORD_KEYSIZE = "-keysize";

  private final static List<String> noValArgs = Arrays.asList("-password",
      SKIP_ACCESSORS);

  protected String defaultLogFileName;

  // flags to shut-down-all to indicate skipping accessors or including all
  private boolean shutDownAllIncludeAdmins;
  private boolean shutDownAllSkipAccessors;

  private String encryptPasswordTransformation;
  private int encryptPasswordKeySize;

  static {
    GemFireCacheImpl.setGFXDSystem(true);
  }

  protected String UTIL_Tools_DSProps = "UTIL_GFXD_Tools_DSProps";
  protected String UTIL_DSProps_HelpPost = "UTIL_GFXD_Tools_DSProps_HelpPost";

  protected void initMapsForGFXD() {
    usageMap.put("encrypt-password", "encrypt-password [external]");
    helpMap.put("encrypt-password",
        LocalizedResource.getMessage("UTIL_encrypt_password_Help"));

    final ArrayList<String> allCommands = new ArrayList<String>();
    final ArrayList<String> aliases = new ArrayList<String>();
    for (String cmd : validCommands) {
      if (!cmd.contains("locator")) {
        allCommands.add(cmd);
      }
      else {
        removedCommands.add(cmd);
      }
    }
    for (String cmd : aliasCommands) {
      if (!cmd.contains("locator")) {
        aliases.add(cmd);
      }
    }

    final String dsPropsArgs = LocalizedResource
        .getMessage(UTIL_Tools_DSProps);
    final String dsPropsHelpPre = LocalizedResource
        .getMessage("UTIL_GFXD_Tools_DSProps_HelpPre");
    final String dsPropsHelpPost = LocalizedResource
        .getMessage(UTIL_DSProps_HelpPost);
    for (String cmd : commandsWithDSProps) {
      // append the system properties information
      final String usage = usageMap.get(cmd).toString();
      final String help = helpMap.get(cmd).toString();
      final String cmdKeyPrefix = "UTIL_" + cmd.replace('-', '_');
      final String extraUsage = LocalizedResource.getInstance()
          .getTextMessageOrNull(cmdKeyPrefix + "_ExtraArgs", null);
      final String extraHelp = LocalizedResource.getInstance()
          .getTextMessageOrNull(cmdKeyPrefix + "_ExtraArgsHelp", null);
      modifiedUsageInfo.put(cmd, usage
          + (extraUsage == null ? dsPropsArgs : (extraUsage + dsPropsArgs)));
      modifiedHelpInfo.put(cmd, help
          + (extraHelp == null ? (dsPropsHelpPre + dsPropsHelpPost)
              : (dsPropsHelpPre + extraHelp + dsPropsHelpPost)));
    }

    validCommands = allCommands.toArray(new String[allCommands.size()]);
    aliasCommands = aliases.toArray(new String[aliases.size()]);
  }

  public GfxdSystemAdmin() {
    GemFireCacheImpl.setGFXDSystem(true);
  }

  public static void main(String[] args) {
    try {
      GemFireXDVersion.loadProperties();

      final GfxdSystemAdmin admin = new GfxdSystemAdmin();
      admin.initHelpMap();
      admin.initUsageMap();
      admin.initMapsForGFXD();
      for (String removedCmd : removedCommands) {
        admin.usageMap.remove(removedCmd);
        admin.helpMap.remove(removedCmd);
      }
      for (Map.Entry<String, String> overrideUse : modifiedUsageInfo
          .entrySet()) {
        admin.usageMap.put(overrideUse.getKey(), overrideUse.getValue());
      }
      for (Map.Entry<String, String> overrideHelp : modifiedHelpInfo
          .entrySet()) {
        admin.helpMap.put(overrideHelp.getKey(), overrideHelp.getValue());
      }
      admin.invoke(args);
    } catch (GemFireTerminateError term) {
      System.exit(term.getExitCode());
    }
  }

  @Override
  public void invoke(String[] args) {
    this.defaultLogFileName = null;
    try {
      super.invoke(args);
      // close the cache if present
      GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache != null) {
        cache.close();
      }
      // disconnect connection to cluster if present
      InternalDistributedSystem ds = InternalDistributedSystem.getConnectedInstance();
      if (ds != null) {
        ds.disconnect();
      }
    } finally {
      // remove zero-sized log-file
      if (this.defaultLogFileName != null) {
        try {
          File logFile = new File(this.defaultLogFileName);
          if (logFile.exists() && logFile.isFile() && logFile.length() == 0) {
            logFile.delete();
          }
        } catch (Throwable t) {
          // ignore at this point
        }
      }
    }
  }

  /** get the path to the distribution locator class */
  @Override
  protected String getDistributionLocatorPath() {
    return "com.pivotal.gemfirexd.tools.GfxdDistributionLocator";
  }

  @Override
  protected String getUsageString(String cmd) {
    final StringBuilder result = new StringBuilder(80);
    result.append(GemFireUtilLauncher.SCRIPT_NAME).append(' ');
    result.append(this.usageMap.get(cmd.toLowerCase()));
    return result.toString();
  }

  @Override
  protected void printHelp(final String cmd) {
    System.err.println(LocalizedStrings.SystemAdmin_USAGE.toLocalizedString()
        + ": " + getUsageString(cmd));
    System.err.println();
    final List<String> lines = format(
        helpMap.get(cmd.toLowerCase()).toString(), 80);
    for (String line : lines) {
      System.err.println(line);
    }
    throw new GemFireTerminateError("exiting after printing help", 1);
  }

  @Override
  public void printStacks(String cmd, List<String> cmdLine,
      boolean allStacks) {
    // always print everything in GemXD
    super.printStacks(cmd, cmdLine, true);
  }

  @Override
  protected void checkDashArg(String cmd, String arg,
      @SuppressWarnings("rawtypes") Iterator it) {
    if (Arrays.binarySearch(commandsWithDSProps, cmd) >= 0) {
      if ((cmd != null && matchCmdArg(cmd, arg)) || matchCmdArg("gemfire", arg)) {
        it.remove();
      }
      // the check will be done later for these commands for valid DS props
    }
    else {
      super.checkDashArg(cmd, arg, it);
    }
  }

  /** returns the list of valid commands supported by {@link SystemAdmin} */
  public static String[] getValidCommands() {
    return SystemAdmin.getValidCommands();
  }

  @Override
  protected String upperCaseDiskStoreNameIfNeeded(String diskStoreName) {
    return diskStoreName.toUpperCase();
  }

  @Override
  protected void printProductDirectory() {
    System.out.println(LocalizedResource.getMessage(
        "UTIL_version_ProductDirectory", getProductDir()));
  }
  
  @Override
  public void upgradeDiskStore(List<String> args) {
    super.upgradeDiskStore(args);
    final String diskStoreName = args.get(0);
    if (diskStoreName.startsWith("SQLF-DD-")) {
      GemFireXDUtils.renameFiles(GemFireXDUtils.listFiles(diskStoreName,
          args.subList(1, args.size())));
    }
    else if (diskStoreName.startsWith("SQLF-DEFAULT-")) {
      GemFireXDUtils.renameFiles(GemFireXDUtils.listFiles(diskStoreName,
          args.subList(1, args.size())));
    }
  }

  @Override
  protected String encryptPassword(String cmd, List<?> cmdLine)
      throws Exception {

    boolean forExternal = false;
    if (cmdLine.size() > 0 && PASSWORD_FOR_EXTERNAL.equals(cmdLine.get(0))) {
      // this is the case of encrypting for external connections like
      // DBSynchronizer using DB private key
      cmdLine.remove(0);
      forExternal = true;
    }
    else if (cmdLine.size() > 0) {
      usage(cmd);
    }

    final Console cons = System.console();
    if (cons == null) {
      throw new IllegalStateException(
          "No console found for reading the password.");
    }
    String user = cons.readLine(LocalizedResource
        .getMessage("UTIL_user_Prompt"));
    final String pwd = new String(cons.readPassword(LocalizedResource
        .getMessage("UTIL_password_Prompt")));

    final String pwd2 = new String(cons.readPassword(LocalizedResource
        .getMessage("UTIL_reenter_password_Prompt")));

    if (!pwd.equals(pwd2)) {
      throw new IllegalStateException("Re-entered password mismatched");
    }

    final String encryptedString;
    if (forExternal) {
      encryptedString = encryptForExternal(cmd, cmdLine, user, pwd);
    }
    else {
      // encrypt for user definitions for BUILTIN authentication scheme
      encryptedString = AuthenticationServiceBase.encryptUserPassword(user,
          pwd, true, false, true);
    }

    System.out.println(LocalizedResource.getMessage(
        "UTIL_encrypt_password_Output", encryptedString));
    return encryptedString;
  }

  public String encryptForExternal(String cmd, List<?> cmdLine, String user,
      String pwd) throws Exception {
    // boot into the distributed system to get the secret key
    getAdminCnx(cmd, cmdLine);
    // ensure that at least one JVM with persisted datadictionary must be
    // present
    if (Misc.getMemStoreBooting().getDDLQueueNoThrow().getRegion()
        .getCacheDistributionAdvisor().adviseInitializedPersistentMembers()
        .size() == 0) {
      throw new IllegalStateException(
          "No member with persistent datadictionary found!");
    }
    String algo = this.encryptPasswordTransformation == null
        ? GfxdConstants.PASSWORD_PRIVATE_KEY_ALGO_DEFAULT : GemFireXDUtils
            .getPrivateKeyAlgorithm(this.encryptPasswordTransformation);
    GemFireXDUtils.initializePrivateKey(algo, this.encryptPasswordKeySize, null);
    return GemFireXDUtils.encrypt(pwd, this.encryptPasswordTransformation,
        GemFireXDUtils.getUserPasswordCipherKeyBytes(user,
            this.encryptPasswordTransformation, this.encryptPasswordKeySize));
  }

  @Override
  protected void checkNoArgs(String cmd,
      @SuppressWarnings("rawtypes") List cmdLine) {
    // nothing to do here; the check will be done in getAdminCnx
  }

  @Override
  protected void checkUnblockOrRevokeMissingDiskStoresArgs(String cmd,
      @SuppressWarnings("rawtypes") List cmdLine) {
    if (cmdLine.size() < 1) {
      // remaining checks will be done in getAdminCnx
      System.err.println("Expected a disk store id.");
      usage(cmd);
    }
  }

  @Override
  protected void checkShutDownAllArgs(String cmd, List<String> cmdLine) {
    // remaining checks will be done in getAdminCnx
  }

  @Override
  protected void checkBackupArgs(String cmd,
      @SuppressWarnings("rawtypes") List cmdLine) {
    if (cmdLine.size() < 1) {
      // remaining checks will be done in getAdminCnx
      usage(cmd);
    }
  }

  @Override
  protected long defaultShutdownAllWait() {
    return Long.getLong("gemfirexd.shutdown-all-wait", 300000);
  }

  /*
   * (sjigyasu)
   * Notes on shutdown:
   * Single member shutdown:
   * - FabricService.stop()
   * - Get an embedded JDBC connection while passing shutdown attribute
   *  All members shutdown:
   * - gfxd shut-down-all
   * - GfxdSystemAdmin.shutDownAll()
   *
   * A FabricService.stop() internally creates an embedded JDBC connection passing 
   * the shutdown attribute which goes through the Derby booting sequence where shutdown
   * attribute is checked.
   * 
   * BaseMonitor in the Derby layer as such could simply have done a GemFireStore.stop() 
   * (which is done under Topservice.shutdown()), 
   * but it has to do something more: before the topservice can be shutdown, 
   * Derby interrupts all active threads, among which are also GemFire threads.  
   * To avoid GemFire thread states to go hayware, it shuts down the Gemfire layer 
   * before the threads are interrupted. (GemFireCache.close() and IDS.disconnect) 
   * 
   * If only this member was shutting down, that's all that were needed.(GemFireCache.close())  
   * However, if this were a shutdown of all members, this member's individual 
   * partition region states have to be gracefully noted so that on recovery members don't 
   * hang on other dependent members.  (see GemFireCacheImpl.shutdownSubTreeGracefully) 
   * GemFireCache.shutdownAll() does this.  
   * 
   * The flag isShutDownAll at GemFireStore differentiates whether this call-to-action is 
   * as part of a shut-down-all or a individual shutdown.
   * 
   * In member shutdown, GemFireCache.close() also disconnects from the IDS.
   * In shutdown-all, disconnect from IDS is delayed until all messaging is finished
   * since, as noted above, the PRs need to sync.  
   * (see finally block of ShutdownAllRequest.createResponse). 
   * 
   */
  @Override
  protected Set<?> invokeShutDownAllMembers(
      final InternalDistributedSystem ads, long timeout) throws SQLException {
    // allow for skipping accessors and including all including locators/agents
    if (this.shutDownAllIncludeAdmins) {
      final DM dm = ads.getDistributionManager();
      @SuppressWarnings("unchecked")
      Set<?> recipients = new HashSet<Object>(
          dm.getDistributionManagerIdsIncludingAdmin());
      // remove self since it will be shut down separately at the end
      recipients.remove(dm.getDistributionManagerId());
      return new GfxdShutdownAllRequest().send(dm, recipients, timeout);
    }
    else if (this.shutDownAllSkipAccessors) {
      final GfxdDistributionAdvisor advisor = GemFireXDUtils.getGfxdAdvisor();
      final Set<DistributedMember> members = advisor.adviseDataStores(null);
      // remove self since it will be shut down separately at the end
      members.remove(ads.getDistributedMember());
      return new GfxdShutdownAllRequest().send(ads.getDistributionManager(),
          members, timeout);
    }
    else {
      final GfxdDistributionAdvisor advisor = GemFireXDUtils.getGfxdAdvisor();
      final Set<DistributedMember> members = advisor.adviseOperationNodes(null);
      // remove self since it will be shut down separately at the end
      members.remove(ads.getDistributedMember());
      return new GfxdShutdownAllRequest().send(ads.getDistributionManager(),
          members, timeout);
    }
  }

  @Override
  protected InternalDistributedSystem getAdminCnx(String cmd,
      @SuppressWarnings("rawtypes") List cmdLine) {

    this.shutDownAllIncludeAdmins = false;
    this.shutDownAllSkipAccessors = false;
    //InternalDistributedSystem.setCommandLineAdmin(true);
    Properties props = new Properties();
    props.setProperty(DistributionConfig.LOG_LEVEL_NAME, "warning");
    // assume the remaining arguments to be properties as "-<key>=<value>"
    // special check for additional args for shut-down-all
    for (int index = 0; index < cmdLine.size(); index++) {
      final String arg = (String)cmdLine.get(index);
      int eqIndex;
      if (arg.length() == 0 || arg.charAt(0) != '-'
          || ((eqIndex = arg.indexOf('=')) <= 0 && !noValArgs.contains(arg))) {
        usage(cmd);
        // never reached
        return null;
      }
      final String argValue;
      if (eqIndex >= 0) {
        argValue = arg.substring(eqIndex + 1);
      }
      else {
        argValue = "";
        eqIndex = arg.length(); // go till end
      }
      // check for system properties
      if (arg.startsWith("-J-D")) {
        System.setProperty(arg.substring(4, eqIndex), argValue);
      }
      // check for additional args for shut-down-all
      else if ("shut-down-all".equals(cmd)) {
        if (INCLUDE_ADMINS.equals(arg)) {
          this.shutDownAllIncludeAdmins = true;
        }
        else if (SKIP_ACCESSORS.equals(arg)) {
          this.shutDownAllSkipAccessors = true;
        }
        else {
          props.setProperty(arg.substring(1, eqIndex), argValue);
        }
      }
      // check for additional args for encrypt-password
      else if ("encrypt-password".equals(cmd)) {
        if (arg.startsWith(PASSWORD_TRANSFORM)) {
          this.encryptPasswordTransformation = argValue;
        }
        else if (arg.startsWith(PASSWORD_KEYSIZE)) {
          this.encryptPasswordKeySize = Integer.parseInt(argValue);
        }
        else {
          props.setProperty(arg.substring(1, eqIndex), argValue);
        }
      }
      else {
        props.setProperty(arg.substring(1, eqIndex), argValue);
      }
    }
    this.defaultLogFileName = "gemfirexd.log";
    if (cmd != null) {
      this.defaultLogFileName = cmd.replace('-', '_') + ".log";
    }
    Properties dsProps;
    try {
      props = FabricServiceUtils.preprocessProperties(props, null, null, false);
      dsProps = FabricServiceUtils.filterGemFireProperties(props,
          this.defaultLogFileName);
    } catch (SQLException e) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "Exception in admin connection", e);
    }

    // allow for passing in an empty password so it is prompted on console
    if (props.containsKey(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR)) {
      String passwd = props
          .getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR);
      if (passwd == null || passwd.length() == 0) {
        final Console cons = System.console();
        if (cons == null) {
          throw new IllegalStateException(
              "No console found for reading the password.");
        }
        final char[] pwd = cons.readPassword(LocalizedResource
            .getMessage("UTIL_password_Prompt"));
        if (pwd != null) {
          passwd = new String(pwd);
          props.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, passwd);
        }
        else {
          props.remove(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR);
        }
      }
    }

    DistributionConfigImpl dsc = new DistributionConfigImpl(dsProps);
    System.out.print("Connecting to distributed system:");
    if (!"".equals(dsc.getLocators())) {
      System.out.println(" locators=" + dsc.getLocators());
    } else if (GfxdUtilLauncher.isSnappyStore()) {
      throw new IllegalArgumentException("option -locators must be specified");
    } else {
      System.out.println(" mcast=" + dsc.getMcastAddress() + ":"
          + dsc.getMcastPort());
    }
    /*
    String logLevelStr = props.getProperty(DistributionConfig.LOG_LEVEL_NAME);
    if (logLevelStr == null) {
      logLevelStr = "warning";
    }
    final LocalLogWriter logger = new LocalLogWriter(
        LogWriterImpl.levelNameToCode(logLevelStr), System.out);
    final InternalDistributedSystem ds = (InternalDistributedSystem)
        InternalDistributedSystem.connectForAdmin(props, logger);
    */
    // boot this as a normal accessor VM so that SQL layer things work fine
    // like authentication, authz; accessor ensures that no license is required
    props.setProperty(com.pivotal.gemfirexd.Attribute.GFXD_HOST_DATA, "false");
    props = GfxdServerLauncher.processProperties(props, null, defaultLogFileName);
    // Set the internal property indicating that this is an admin JVM.
    props.put(Property.PROPERTY_GEMFIREXD_ADMIN, "true");

    try {
      FabricServer server = ((FabricServer)Class
          .forName("com.pivotal.gemfirexd.FabricServiceManager")
          .getMethod("getFabricServerInstance").invoke(null));
      if (server.status() != FabricService.State.RUNNING) {
        server.start(props);
      }
    } catch (Exception ex) {
      RuntimeException rte = new RuntimeException(ex);
      rte.setStackTrace(new StackTraceElement[0]);
      throw rte;
    }
    InternalDistributedSystem ds = InternalDistributedSystem
        .getConnectedInstance();

    final Set<?> existingMembers = ds.getDistributionManager()
        .getDistributionManagerIds();
    if (existingMembers.isEmpty()) {
      throw new RuntimeException(
          "There are no members in the distributed system");
    }
    return ds;
  }
}
