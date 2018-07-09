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

package com.gemstone.gemfire.internal.cache;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionConfigImpl;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.OSProcess;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.process.StartupStatus;
import com.gemstone.gemfire.internal.process.StartupStatusListener;
import com.gemstone.gemfire.internal.shared.LauncherBase;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import io.snappydata.collection.OpenHashSet;
import com.gemstone.gemfire.internal.util.JavaCommandBuilder;

import static com.gemstone.gemfire.internal.cache.Status.*;

/**
 * Launcher program to start a cache server.
 *
 * @author Sudhir Menon
 * @author Barry Oglesby
 * @author David Whitlock
 * @author John Blum
 *
 * @since 2.0.2
 */
public class CacheServerLauncher extends LauncherBase {

  /** Is this VM a dedicated Cache Server?  This value is used mainly by the admin API. */
  public static boolean isDedicatedCacheServer = Boolean.getBoolean("gemfire.isDedicatedServer");

  public static boolean ASSIGN_BUCKETS = Boolean.getBoolean("gemfire.CacheServerLauncher.assignBucketsToPartitions");

  //default is to exit if property not defined
  public static boolean DONT_EXIT_AFTER_LAUNCH = Boolean.getBoolean("gemfire.CacheServerLauncher.dontExitAfterLaunch");

  /** Should the launch command be printed? */
  public static final boolean PRINT_LAUNCH_COMMAND = Boolean.getBoolean(
    CacheServerLauncher.class.getSimpleName() + ".PRINT_LAUNCH_COMMAND");

  protected File workingDir = null;
  protected PrintStream oldOut = System.out;
  protected PrintStream oldErr = System.err;
  protected LogWriterI18n logger = null;
  protected String offHeapSize;
  protected volatile String serverStartupMessage;
  protected final OpenHashSet<String> knownOptions;

  protected static CacheServerLauncher instance;

  public CacheServerLauncher(final String baseName) {
    super(baseName, null);
    assert baseName != null : "The base name used for the cache server launcher files cannot be null!";
    knownOptions = new OpenHashSet<>();
    initKnownOptions();
  }

  protected Path getWorkingDirPath() {
    return this.workingDir.toPath().toAbsolutePath(); // see bug 32548
  }

  /**
   * Prints usage information about this program.
   */
  protected void usage() throws IOException {
    PrintStream out = System.out;
    out.println("cacheserver start [-J<vmarg>]* [<attName>=<attValue>]* [-dir=<workingdir>] [-classpath=<classpath>] [-disable-default-server] [-rebalance] [-lock-memory] [-server-port=<server-port>] [-server-bind-address=<server-bind-address>] [-critical-heap-percentage=<critical-heap-percentage>] [-eviction-heap-percentage=<eviction-heap-percentage>] [-critical-off-heap-percentage=<critical-off-heap-percentage>] [-eviction-off-heap-percentage=<eviction-off-heap-percentage>]\n" );
    out.println("\t" + LocalizedStrings.CacheServerLauncher_STARTS_A_GEMFIRE_CACHESERVER_VM.toLocalizedString() );
    out.println("\t" + LocalizedStrings.CacheServerLauncher_VMARG.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_DIR.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_CLASSPATH.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_ATTNAME.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_REBALANCE.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_DISABLE_DEFAULT_SERVER.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_SERVER_PORT.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_SERVER_BIND_ADDRESS.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_CRITICAL_HEAP_PERCENTAGE.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_EVICTION_HEAP_PERCENTAGE.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_CRITICAL_OFF_HEAP_PERCENTAGE.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_EVICTION_OFF_HEAP_PERCENTAGE.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_LOCK_MEMORY.toLocalizedString());

    out.println();
    out.println( "cacheserver stop [-dir=<workingdir>]" );
    out.println("\t" + LocalizedStrings.CacheServerLauncher_STOPS_A_GEMFIRE_CACHESERVER_VM.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_DIR.toLocalizedString());
    out.println();
    out.println( "cacheserver status [-dir=<workingdir>]" );
    out.println( "\t" + LocalizedStrings.CacheServerLauncher_STATUS.toLocalizedString());
    out.println("\t" + LocalizedStrings.CacheServerLauncher_DIR.toLocalizedString());
  }

  /**
   * Prints the status of the cache server running the configured
   * working directory.
   */
  protected void status(final String[] args) throws Exception {
    workingDir = (File) getStopOptions(args).get(DIR);
    Status s = getStatus();
    if (args.length >= 1 && args[1].equalsIgnoreCase("verbose")) {
      System.out.println(s);
    }
    else {
      System.out.println(s.shortStatus());
    }
    if (DONT_EXIT_AFTER_LAUNCH) {
      return;
    }
    System.exit(0);
  }

  protected String getHostNameAndDir() {
    return workingDir != null ? this.hostName + '(' + workingDir.getName() + ')'
        : this.hostName;
  }

  /**
   * Wait for the member's status to become "running".
   */
  protected void waitForRunning(final String[] args) throws Exception {
    workingDir = (File)getStopOptions(args).get(DIR);

    // go for wait only if the status is not already SHUTDOWN
    final Status status = getStatus();
    if (status == null) {
      throw new Exception(
          LocalizedStrings.CacheServerLauncher_NO_AVAILABLE_STATUS
              .toLocalizedString(this.statusName));
    }
    if (status.state != SHUTDOWN) {
      if (status.state == SHUTDOWN_PENDING) {
        pollCacheServerForShutdown(getStatusPath());
      }
      else {
        System.exit(waitForRunning((String)null));
      }
    }
    else {
      System.out.println(LocalizedStrings.CacheServerLauncher_0_STOPPED
          .toLocalizedString(this.baseName, getHostNameAndDir()));
    }
    System.exit(0);
  }

  public static CacheServerLauncher getCurrentInstance() {
    return instance;
  }

  /**
   * Main method that parses the command line and performs an
   * will start, stop, or get the status of a cache server.  This main
   * method is also the main method of the launched cacher server VM
   * ("server" mode).
   */
  public static void main(final String[] args) {
    final CacheServerLauncher launcher = new CacheServerLauncher("CacheServer");
    instance = launcher;
    boolean inServer = false;

    try {
      if (args.length > 0) {
        if (args[0].equalsIgnoreCase("start")) {
          launcher.start(args);
        }
        else if (args[0].equalsIgnoreCase("server")) {
          inServer = true;
          launcher.server(args);
        }
        else if (args[0].equalsIgnoreCase("stop")) {
          launcher.stop(args);
        }
        else if (args[0].equalsIgnoreCase("status")) {
          launcher.status(args);
        }
        else {
          launcher.usage();
          System.exit(1);
        }
      }
      else {
        launcher.usage();
        System.exit(1);
      }

      throw new Exception(LocalizedStrings.CacheServerLauncher_INTERNAL_ERROR_SHOULDNT_REACH_HERE.toLocalizedString());
    }
    catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      // Whenever you catch Error or Throwable, you must also
      // check for fatal JVM error (see above).  However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      SystemFailure.checkFailure();
      t.printStackTrace();
      if (inServer) {
        launcher.setServerError(LocalizedStrings.CacheServerLauncher_ERROR_STARTING_SERVER_PROCESS
          .toLocalizedString(), t);
      }
      launcher.restoreStdOut();
      if (launcher.logger != null) {
        launcher.logger.severe(LocalizedStrings.CacheServerLauncher_CACHE_SERVER_ERROR, t);

      }
      else {
        System.out.println(LocalizedStrings.CacheServerLauncher_ERROR_0.toLocalizedString(t.getMessage()));
      }
      System.exit(1);
    } finally {
      instance = null;
    }
  }

  protected void restoreStdOut( ) {
    System.setErr( oldErr );
    System.setOut( oldOut );
  }

  protected static final String PROPERTIES = "properties";
  public static final String REBALANCE = "rebalance";
  public static final String SERVER_PORT = "server-port";
  public static final String SERVER_BIND_ADDRESS = "server-bind-address";
  public static final String DISABLE_DEFAULT_SERVER = "disable-default-server";
  protected static final String LOCK_MEMORY = "lock-memory";

  protected void initKnownOptions() {
    knownOptions.add(REBALANCE);
    knownOptions.add(SERVER_PORT);
    knownOptions.add(SERVER_BIND_ADDRESS);
    knownOptions.add(DISABLE_DEFAULT_SERVER);
    knownOptions.add(CRITICAL_HEAP_PERCENTAGE);
    knownOptions.add(EVICTION_HEAP_PERCENTAGE);
    knownOptions.add(CRITICAL_OFF_HEAP_PERCENTAGE);
    knownOptions.add(EVICTION_OFF_HEAP_PERCENTAGE);
    knownOptions.add(LOCK_MEMORY);
  }

  protected final File processDirOption(final Map<String, Object> options, final String dirValue) throws FileNotFoundException {
    final File inputWorkingDirectory = new File(dirValue);

    if (!inputWorkingDirectory.exists()) {
      throw new FileNotFoundException(LocalizedStrings.CacheServerLauncher_THE_INPUT_WORKING_DIRECTORY_DOES_NOT_EXIST_0
        .toLocalizedString(dirValue));
    }

    options.put(DIR, inputWorkingDirectory);

    return inputWorkingDirectory;
  }

  /**
   * Populates a map that maps the name of the start options such as {@link #DIR} to its value on the command line.
   * If no value is specified on the command line, a default one is provided.
   */
  public Map<String, Object> getStartOptions(String[] args) throws Exception {
    final Map<String, Object> options = new HashMap<String, Object>();
    options.put(DIR, new File(System.getProperty("user.dir")));

    List<String> vmArgs = new ArrayList<String>();
    
    final Map<String, String> envArgs = new HashMap<String, String>();
    options.put(ENVARGS, envArgs);

    final Properties props = new Properties();
    options.put(PROPERTIES, props);

    for (final String arg : args) {
      if(arg == null) {
        continue;
      }
      if (arg.equals("start")) {
        // expected
      }
      else if (arg.startsWith("-classpath=")) {
        options.put(CLASSPATH, arg.substring(arg.indexOf("=") + 1));
      }
      else if (arg.startsWith("-dir=")) {
        processDirOption(options, arg.substring(arg.indexOf("=") + 1));
      }
      else if (arg.startsWith("-disable-default-server")) {
        options.put(DISABLE_DEFAULT_SERVER, arg);
      }
      else if (arg.startsWith("-lock-memory")) {
        if (System.getProperty("os.name").indexOf("Windows") >= 0) {
          throw new IllegalArgumentException("Unable to lock memory on this operating system");
        }
        props.put(DistributionConfig.LOCK_MEMORY_NAME, "true");
      }
      else if (arg.startsWith("-rebalance")) {
        options.put(REBALANCE, Boolean.TRUE);
      }
      else if (arg.startsWith("-server-port")) {
        options.put(SERVER_PORT, arg);
      }
      else if (arg.startsWith("-" + CRITICAL_HEAP_PERCENTAGE) ) {
        options.put(CRITICAL_HEAP_PERCENTAGE, arg);
      }
      else if (arg.startsWith("-" + EVICTION_HEAP_PERCENTAGE) ) {
        options.put(EVICTION_HEAP_PERCENTAGE, arg);
      }
      else if (arg.startsWith("-" + CRITICAL_OFF_HEAP_PERCENTAGE) ) {
        options.put(CRITICAL_OFF_HEAP_PERCENTAGE, arg);
      }
      else if (arg.startsWith("-" + EVICTION_OFF_HEAP_PERCENTAGE) ) {
        options.put(EVICTION_OFF_HEAP_PERCENTAGE, arg);
      }
      else if (arg.startsWith("-server-bind-address")) {
        options.put(SERVER_BIND_ADDRESS, arg);
      }
      else if (arg.startsWith("-J")) {
        processVMArg(arg.substring(2), vmArgs);
      }
      else if (arg.length() > 0) {
        // moved this default block down so that "-J" like options can have '=' in them.
        // an 'indexOf' the assignment operator with greater than 0 ensures a non-empty String key value
        if (arg.indexOf("=") > 0) {
          final int assignmentIndex = arg.indexOf("=");
          final String key = arg.substring(0, assignmentIndex);
          final String value = arg.substring(assignmentIndex + 1);
          if (key.startsWith("-")) {
            if (key.substring(1).startsWith("-")) {
              throw new IllegalArgumentException(
                  LocalizedStrings.CacheServerLauncher_UNKNOWN_ARGUMENT_0
                      .toLocalizedString(arg));
            }   
            processStartOption(key.substring(1), value, options, vmArgs, envArgs, props);
          }
          else {
            processStartArg(key, value, options, vmArgs, props);
          }
        }
        else if (arg.equalsIgnoreCase("-password")) {
          processStartOption(arg.substring(1), null, options, vmArgs, envArgs, props);
        }
        else {
          throw new IllegalArgumentException(
              LocalizedStrings.CacheServerLauncher_UNKNOWN_ARGUMENT_0
                  .toLocalizedString(arg));
        }
      }
      else {
        throw new IllegalArgumentException(LocalizedStrings.CacheServerLauncher_UNKNOWN_ARGUMENT_0
          .toLocalizedString(arg));
      }
    }

    // -J-Djava.awt.headless=true has been added for Mac platform where it
    // causes an icon to appear for gfxd launched procs
    // TODO: check which library/GemFire code causes awt to be touched
    vmArgs.add("-Djava.awt.headless=true");

    // configure commons-logging to use Log4J logging
    vmArgs.add("-Dorg.apache.commons.logging.Log=" +
        "org.apache.commons.logging.impl.Log4JLogger");

    vmArgs = postProcessOptions(vmArgs, options);
    options.put(VMARGS, vmArgs);
    return options;
  }

  /**
   * Process a command-line options of the form "key=value".
   */
  protected void processStartArg(final String key,
                                 final String value,
                                 final Map<String, Object> options,
                                 final List<String> vmArgs,
                                 final Properties props)
    throws Exception
  {
    props.setProperty(key, value);
  }

  /**
   * Process a command-line option of the form "-key=value".
   * @param envArgs environment variables in the form of 'name=value'. 
   */
  protected void processStartOption(final String key,
                                    final String value,
                                    final Map<String, Object> options,
                                    final List<String> vmArgs,
                                    final Map<String, String> envArgs,
                                    final Properties props)
    throws Exception
  {
    processUnknownStartOption(key, value, options, vmArgs, props);
  }

  /**
   * Process a command-line option of the form "-key=value" unknown to the base class.
   */
  protected void processUnknownStartOption(final String key,
                                           final String value,
                                           final Map<String, Object> options,
                                           final List<String> vmArgs,
                                           final Properties props) {
    throw new IllegalArgumentException(LocalizedStrings.CacheServerLauncher_UNKNOWN_ARGUMENT_0.toLocalizedString(key));
  }

  /**
   * Extracts configuration information used when launching the cache server VM.
   */
  protected Map<String, Object> getServerOptions(final String[] args) throws Exception {
    final Map<String, Object> options = new HashMap<String, Object>();
    options.put(DIR, new File("."));
    workingDir = (File) options.get(DIR);

    final Properties props = new Properties();
    options.put(PROPERTIES, props);

    for (final String arg : args) {
      if (arg.equals("server")) {
        // expected
      }
      else if (arg.startsWith("-dir=")) {
        this.workingDir = processDirOption(options, arg.substring(arg.indexOf("=") + 1));
      }
      else if (arg.startsWith("-rebalance")) {
        options.put(REBALANCE, Boolean.TRUE);
      }
      else if (arg.startsWith("-disable-default-server")) {
        options.put(DISABLE_DEFAULT_SERVER, Boolean.TRUE);
      }
      else if (arg.startsWith("-lock-memory")) {
        props.put(DistributionConfig.LOCK_MEMORY_NAME, "true");
      }
      else if (arg.indexOf("=") > 1) {
        final int assignmentIndex = arg.indexOf("=");
        final String key = arg.substring(0, assignmentIndex);
        final String value = arg.substring(assignmentIndex + 1);

        if (key.charAt(0) == '-') {
          processServerOption(key.substring(1), value, options, props);
        }
        else {
          props.setProperty(key, value);
        }
      }
      else {
        throw new IllegalArgumentException(LocalizedStrings.CacheServerLauncher_UNKNOWN_ARGUMENT_0.toLocalizedString(arg));
      }
    }
    // any last minute processing of environment variables or otherwise
    processServerEnv(props);

    return options;
  }

  protected void processServerOption(String key, String value,
      Map<String, Object> options, Properties props) {
    options.put(key, value);
  }

  protected void processServerEnv(Properties props) throws Exception {
  }

  /**
   * Extracts configuration information for stopping a cache server based on the contents of the command-line.
   * This method can also be used with getting the status of a cache server.
   */
  protected Map<String, Object> getStopOptions(final String[] args) throws Exception {
    final Map<String, Object> options = new HashMap<String, Object>();
    options.put(DIR, new File("."));

    for (final String arg : args) {
      if (arg.equals("stop") || arg.equals("status") || arg.equals("wait")
              || arg.equals("verbose")) {
        // expected
      }
      else if (arg.startsWith("-dir=")) {
        processDirOption(options, arg.substring(arg.indexOf("=") + 1));
      }
      else {
        throw new IllegalArgumentException(LocalizedStrings.CacheServerLauncher_UNKNOWN_ARGUMENT_0
          .toLocalizedString(arg));
      }
    }

    return options;
  }

  protected List<String> postProcessOptions(List<String> vmArgs,
      final Map<String, Object> map) {
    return vmArgs;
  }

  /**
   * Configures and spawns a VM that hosts a cache server.  Any output
   * generated while starting the VM will be placed in a special
   * {@linkplain #startLogFileName log file}.
   *
   * See #getStartOptions
   * @see OSProcess#bgexec(String[], File, File, boolean, Map)
   */
  public void start(final String[] args) throws Exception {
    final Map<String, Object> options = getStartOptions(args);

    workingDir = (File) options.get(DIR);

    // Complain if a cache server is already running in the specified working directory.
    // See bug 32574.
    String msg = verifyAndClearStatus();
    if (msg != null) {
      System.err.println(msg);
      System.exit(1);
    }

    final Properties props = (Properties)options.get(PROPERTIES);
    // start the GemFire Cache Server proces...
    runCommandLine(options, props, buildCommandLine(options));

    // wait for status.state == RUNNING
    int exitCode = waitForRunning(getLogFilePath(props));

    if (DONT_EXIT_AFTER_LAUNCH) {
      return;
    }

    System.exit(exitCode);
  }

  private String[] buildCommandLine(final Map<String, Object> options) {
    final List<String> commandLine = JavaCommandBuilder.buildCommand(this.getClass().getName(),
      (String) options.get(CLASSPATH), null, (List<String>) options.get(VMARGS));

    commandLine.add("server");
    addToServerCommand(commandLine, options);

    return commandLine.toArray(new String[commandLine.size()]);
  }

  protected void printCommandLine(final String[] commandLine,
      final Map<String, String> env) throws Exception {
    if (printLaunchCommand()) {
      System.out.println("Starting " + this.baseName + " with command:");
      for (final String command : commandLine) {
        System.out.print(command);
        System.out.print(" ");
      }
      System.out.println();
    }
  }

  public void printCommandLine(LogWriterI18n logger) {
    // nothing in GemFire
  }

  private int runCommandLine(final Map<String, Object> options,
      Properties props, final String[] commandLine) throws Exception {
    final File startLogFile = new File(workingDir, startLogFileName).getAbsoluteFile(); // see bug 32548

    if (startLogFile.exists() && !startLogFile.delete()) {
      throw new IOException("Unable to delete start log file (" + startLogFile.getAbsolutePath() + ")!");
    }
    final File pidFile = new File(workingDir, pidFileName).getAbsoluteFile(); // see bug 32548

    if (pidFile.exists() && !pidFile.delete()) {
      throw new IOException("Unable to delete pid file (" + pidFile.getAbsolutePath() + ")!");
    }
    Map<String, String> env = (Map<String, String>)options.get(ENVARGS);
    if (env == null) {
      env = new HashMap<String, String>();
    }
    // read the passwords from command line
    SocketCreator.readSSLProperties(env);

    printCommandLine(commandLine, env);

    final int pid = OSProcess.bgexec(commandLine, workingDir, startLogFile,
        false, env);
    printStartMessage(options, props, pid);

    return pid;
  }

  protected void printStartMessage(final Map<String, Object> options,
      final Properties props, final int pid) throws Exception {
    System.out
        .println(LocalizedStrings.CacheServerLauncher_STARTING_0_WITH_PID_1
            .toLocalizedString(this.baseName, pid));
  }

  /**
   * Sets the status of the cache server to be {@link Status#RUNNING}.
   */
  public void running(final InternalDistributedSystem system,
      int stateIfWaiting) {
    Status stat = this.status;
    if (stat == null) {
      stat = this.status = createStatus(RUNNING, getProcessId());
    }
    else {
      if (stat.state == WAITING) {
        stat.dsMsg = null;
        stat.state = stateIfWaiting;
      } else if (stat.state == RUNNING) {
        if (stat.dsMsg != null) {
          return;
        }
      } else {
        stat.state = RUNNING;
      }
    }
    try {
      setRunningStatus(stat, system);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  protected void setRunningStatus(final Status stat,
      final InternalDistributedSystem system) throws Exception {
    writeStatus(stat);
  }

  public void setWaitingStatus(String regionPath,
      Set<PersistentMemberID> membersToWaitFor, Set<Integer> missingBuckets,
      PersistentMemberID myId, String message) {
    try {
      Status stat = this.status;
      if (stat == null) {
        stat = this.status = createStatus(WAITING, getProcessId());
      }
      else {
        stat.state = WAITING;
      }
      stat.dsMsg = message;
      writeStatus(stat);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public String getServerStartupMessage() {
    return this.serverStartupMessage;
  }

  public void setServerStartupMessage(String message) {
    this.serverStartupMessage = message;
  }

  public static ThreadLocal<Integer> serverPort = new ThreadLocal<Integer>();

  public static ThreadLocal<String> serverBindAddress = new ThreadLocal<String>();

  public static Integer getServerPort() {
    return serverPort.get();
  }

  public static String getServerBindAddress() {
    return serverBindAddress.get();
  }

  public static ThreadLocal<Boolean> disableDefaultServer = new ThreadLocal<Boolean>();

  public static Boolean getDisableDefaultServer() {
    return disableDefaultServer.get();
  }

  /**
   * The method that does the work of being a cache server.  It is
   * invoked in the VM spawned by the {@link #start} method.
   * Basically, it creates a GemFire {@link Cache} based on
   * configuration passed in from the command line.  (It will also
   * take <code>gemfire.properties</code>, etc. into account, just
   * like an application cache.)
   *
   * <P>
   *
   * After creating the cache and setting the server's status to {@link
   * Status#RUNNING}, it periodically monitors the status, waiting for it to
   * change to {@link Status#SHUTDOWN_PENDING} (see {@link #stop}).  When
   * the status does change, it closes the <code>Cache</code> and sets
   * the status to be {@link Status#SHUTDOWN}.
   *
   * @param args Configuration options passed in from the command line
   */
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
  public void server(final String[] args) throws Exception {
    isDedicatedCacheServer = true;

    // make the process a UNIX daemon if possible
    String errMsg = makeDaemon();

    SystemFailure.setExitOK(true);

    final Map<String, Object> options = getServerOptions(args);

    final String serverPortString = (String) options.get(SERVER_PORT);

    if (serverPortString != null) {
      serverPort.set(Integer.parseInt(serverPortString));
    }

    serverBindAddress.set((String) options.get(SERVER_BIND_ADDRESS));
    disableDefaultServer.set((Boolean) options.get(DISABLE_DEFAULT_SERVER));
    workingDir = new File(System.getProperty("user.dir"));

    // Say that we're starting...
    Status originalStatus = createStatus(STARTING, getProcessId());
    status = originalStatus;
    status.msg = errMsg;
    writeStatus(status);

    // Connect to the distributed system.  The properties will
    // properly configure logging, the declarative caching file, etc.
    final Properties props = (Properties) options.get(PROPERTIES);

    if (props.getProperty(DistributionConfig.LOG_FILE_NAME) == null && CacheServerLauncher.isLoggingToStdOut()) {
      // Check First if the gemfire.properties set the log-file. If they do, we shouldn't override that default
      final Properties gemfireProperties = new Properties();

      DistributionConfigImpl.loadGemFireProperties(gemfireProperties);

      if (gemfireProperties.get(DistributionConfig.LOG_FILE_NAME) == null) {
        // Do not allow the cache server to log to stdout, override the logger with #defaultLogFileName
        props.setProperty(DistributionConfig.LOG_FILE_NAME, defaultLogFileName);
      }
    }

    installLogListener();    
    final AsyncServerLauncher launcher = this.new AsyncServerLauncher(this, props, options); 
    final Thread serverConnector = new Thread( launcher, "serverConnector");
    
    serverConnector.start();
    
    while(true) {
      try {
        serverConnector.join(500);
      } catch (InterruptedException ie) {
        //ignore
      }
      if (serverConnector.isAlive()) {
        Status s = readStatus();
        if (s.state == SHUTDOWN_PENDING || s.state == SHUTDOWN) {
          InternalDistributedSystem ids = InternalDistributedSystem.getConnectedInstance();
          if (ids != null) {
            ids.disconnect();
          }
          serverConnector.interrupt();
          s.state = SHUTDOWN;
          writeStatus(s);
          System.exit(0);
        } else {
          Thread.sleep( 250 );
        }
      }
      else {
        break;
      }
    }
    
    InternalDistributedSystem system = launcher.getSystem();
    logger = system.getLogWriter().convertToLogWriterI18n();

    // redirect output to the log file
    redirectOutputToLogFile(system);

    Cache cache = this.createCache(system, options);
    cache.setIsServer(true);

    startAdditionalServices(cache, options, props);

    system = (InternalDistributedSystem)cache.getDistributedSystem();
    this.running(system, RUNNING);

    clearLogListener();

    if (ASSIGN_BUCKETS) {
      for (PartitionedRegion region : ((GemFireCacheImpl) cache).getPartitionedRegions()) {
        PartitionRegionHelper.assignBucketsToPartitions(region);
      }
    }
    // start rebalancing if specified
    startRebalanceFactory(cache, options);

    long lastModified, oldModified = getLastModifiedStatusNanos();
    // Every FORCE_STATUS_FILE_READ_ITERATION_COUNT iterations, read the status file despite the modification time
    // to catch situations where the file is modified quicker than the file timestamp's resolution.
    short count = 0;
    boolean externalShutDown = true;
    boolean loggedWarning = false;
    while(true) {
      lastModified = getLastModifiedStatusNanos();
      if (lastModified != oldModified || count++ == FORCE_STATUS_FILE_READ_ITERATION_COUNT) {
        count = 0;
        try {
          status = readStatus();
        } catch (FileNotFoundException ignored) {
          // See bug 44627.
          // The cache server used to just shutdown at this point. Instead,
          // recreate the status file if possible and continue.
          status = createStatus(RUNNING, originalStatus.pid);
          try {
            setRunningStatus(status, system);
          } catch (FileNotFoundException e) {
            if (!loggedWarning) {
              logger.warning(LocalizedStrings.CacheServerLauncher_CREATE_STATUS_EXCEPTION_0,
                  e.toString());
              loggedWarning = true;
            }
          }
        }
        oldModified = lastModified;
        if (status.state == SHUTDOWN_PENDING) {
            logger.info(LocalizedStrings.DEBUG,
                "CacheServerLauncher shutdown started.");
            stopAdditionalServices();
            this.disconnect(cache);
            status.state = SHUTDOWN;
            writeStatus(status);
            // No point reconnecting if explicit stop has been called
            system.stopReconnecting();
            waitTillReconnectStopped(system);
            externalShutDown = false;
        } else {
          Thread.sleep(150);
        }
      } else {
        Thread.sleep(150);
      }
      if (!system.isConnected()) {
//        System.out.println("System is disconnected.  isReconnecting = " + system.isReconnecting());
        boolean reconnected = false;
        if (system.isReconnecting()) {
          while (true) {
            // Timed check is there so that if explicit stop has been called then
            // we stop faster rather waiting for the entire reconnect attempt to get over
            reconnected = system.waitUntilReconnected(150, TimeUnit.MILLISECONDS);
            status = readStatus();
            if (status.state == SHUTDOWN_PENDING) {
              // No point reconnecting if explicit stop has been called
              system.stopReconnecting();
              waitTillReconnectStopped(system);
              externalShutDown = false;
            }

            if (reconnected) {
              system = (InternalDistributedSystem) system.getReconnectedSystem();
              cache = GemFireCacheImpl.getInstance();
              break;
            }
            if (status.state == SHUTDOWN_PENDING) {
              break;
            }
          }
        }
        if (!reconnected) {
          // shutdown-all disconnected the DS
          if (externalShutDown) {
            //delete the status file
            deleteStatus();
          }
          System.exit(0);
        }
      }
    }
  }

  private void waitTillReconnectStopped(InternalDistributedSystem system) throws Exception {
    while (true) {
      if (system.isReconnecting()) {
        Thread.sleep(150);
      }
      else {
        break;
      }
    }
  }
  
  class AsyncServerLauncher implements Runnable {
    
    private CacheServerLauncher _this;
    private Properties props;
    private Map<String, Object> options;
    private InternalDistributedSystem system;
    private Throwable exception;
    AsyncServerLauncher(CacheServerLauncher cs, Properties props, Map<String, Object> options) {
      _this = cs;
      this.props = props;
      this.options = options;
    }

    @Override
    public void run() {
      try {
        system = _this.connect(props, options);
      }
      catch(Throwable t) {
        exception = t;
      }
    }
    
    public InternalDistributedSystem getSystem() {
      if (exception != null) {
        throw new RuntimeException(exception);
      }
      return system;
    }
  }

  protected void redirectOutputToLogFile(InternalDistributedSystem system)
      throws Exception {
    DistributionConfig config = system.getConfig();
    OSProcess.redirectOutput(config.getLogFile());
  }

  protected String makeDaemon() {
    try {
      NativeCalls.getInstance().daemonize(null);
    } catch (UnsupportedOperationException e) {
      return "WARNING: " + e.getMessage();
    } catch (IllegalStateException ignored) {
    }
    return null;
  }

  private void installLogListener() {
    MainLogReporter reporter = new MainLogReporter(this.status);
    StartupStatus.setListener(reporter);
    reporter.setDaemon(true);
    reporter.start();
  }

  private void clearLogListener() {
    MainLogReporter mainLogListener = (MainLogReporter)StartupStatus
        .getStartupListener();
    if(mainLogListener != null) {
      mainLogListener.shutdown();
      StartupStatus.clearListener();
    }
  }

  protected InternalDistributedSystem connect(Properties props,
      Map<String, Object> options) {
    return (InternalDistributedSystem)DistributedSystem.connect(props);
  }

  protected static float getCriticalHeapPercent(Map<String, Object> options) {
    if (options != null) {
      String criticalHeapThreshold = (String)options
          .get(CRITICAL_HEAP_PERCENTAGE);
      if (criticalHeapThreshold != null) {
        return Float.parseFloat(criticalHeapThreshold
            .substring(criticalHeapThreshold.indexOf("=") + 1));
      }
    }
    return -1.0f;
  }
  
  protected static float getEvictionHeapPercent(Map<String, Object> options) {
    if (options != null) {
      String evictionHeapThreshold = (String)options
          .get(EVICTION_HEAP_PERCENTAGE);
      if (evictionHeapThreshold != null) {
        return Float.parseFloat(evictionHeapThreshold
            .substring(evictionHeapThreshold.indexOf("=") + 1));
      }
    }
    return -1.0f;
  }
  
  protected static float getCriticalOffHeapPercent(Map<String, Object> options) {
    if (options != null) {
      String criticalOffHeapThreshold = (String)options
          .get(CRITICAL_OFF_HEAP_PERCENTAGE);
      if (criticalOffHeapThreshold != null) {
        return Float.parseFloat(criticalOffHeapThreshold
            .substring(criticalOffHeapThreshold.indexOf("=") + 1));
      }
    }
    return -1.0f;
  }
  
  protected static float getEvictionOffHeapPercent(Map<String, Object> options) {
    if (options != null) {
      String evictionOffHeapThreshold = (String)options
          .get(EVICTION_OFF_HEAP_PERCENTAGE);
      if (evictionOffHeapThreshold != null) {
        return Float.parseFloat(evictionOffHeapThreshold
            .substring(evictionOffHeapThreshold.indexOf("=") + 1));
      }
    }
    return -1.0f;
  }
  
  protected Cache createCache(InternalDistributedSystem system,
      Map<String, Object> options) throws IOException {
    Cache cache = CacheFactory.create(system);

    float threshold = getCriticalHeapPercent(options);
    if (threshold > 0.0f) {
      cache.getResourceManager().setCriticalHeapPercentage(threshold);
    }
    threshold = getEvictionHeapPercent(options);
    if (threshold > 0.0f) {
      cache.getResourceManager().setEvictionHeapPercentage(threshold);
    }
    
    threshold = getCriticalOffHeapPercent(options);
    getCriticalOffHeapPercent(options);
    if (threshold > 0.0f) {
      cache.getResourceManager().setCriticalOffHeapPercentage(threshold);
    }
    threshold = getEvictionOffHeapPercent(options);
    if (threshold > 0.0f) {
      cache.getResourceManager().setEvictionOffHeapPercentage(threshold);
    }


    // Create and start a default cache server
    // If (disableDefaultServer is not set or it is set but false) AND (the number of cacheservers is 0)
    Boolean disable = disableDefaultServer.get();
    if ((disable == null || !disable) && cache.getCacheServers().size() == 0) {
      // Create and add a cache server
      CacheServer server = cache.addCacheServer();

      // Set its port if necessary
      Integer serverPort = CacheServerLauncher.getServerPort();
      if (serverPort != null) {
        server.setPort(serverPort);
      }

      // Set its bind address if necessary
      String serverBindAddress = getServerBindAddress();
      if (serverBindAddress != null) {
        server.setBindAddress(serverBindAddress.trim());
      }

      // Start it
      server.start();
    }

    return cache;
  }

  protected void disconnect(Cache cache) {
    DistributedSystem dsys = cache.getDistributedSystem();
    cache.close();
    dsys.disconnect();
  }

  protected void startRebalanceFactory(final Cache cache,
      final Map<String, Object> options) {
    Boolean rebalanceOnStartup = (Boolean)options.get(REBALANCE);
    if (rebalanceOnStartup != null && rebalanceOnStartup.booleanValue()) {
      cache.getResourceManager().createRebalanceFactory().start();
    }
  }

  /**
   * Stops a cache server (which is running in a different VM) by setting its
   * status to {@link Status#SHUTDOWN_PENDING}.
   * Waits for the cache server to actually shut down.
   */
  public void stop(final String[] args) throws Exception {
    this.workingDir = (File) getStopOptions(args).get(DIR);

    // determine the current state of the Cache Server process...
    final Path statusPath = getStatusPath();
    int exitStatus = 1;

    if (Files.exists(statusPath)) {
      this.status = spinReadStatus(statusPath);

      if (status == null) {
        throw new Exception(
            LocalizedStrings.CacheServerLauncher_NO_AVAILABLE_STATUS
                .toLocalizedString(this.statusName));
      }

      // upon reading the status file, request the Cache Server to shutdown if it has not already...
      if (this.status.state != SHUTDOWN) {
        // copy server PID and not use own PID; see bug #39707
        this.status = createStatus(SHUTDOWN_PENDING, this.status.pid);
        writeStatus(this.status);
      }
     ;

      // poll the Cache Server for a response to our shutdown request (passes through if the Cache Server
      // has already shutdown)...
      pollCacheServerForShutdown(statusPath);

      // after polling, determine the status of the Cache Server one last time and determine how to exit...
      if (this.status.state == SHUTDOWN) {
        System.out.println(LocalizedStrings.CacheServerLauncher_0_STOPPED
            .toLocalizedString(this.baseName, getHostNameAndDir()));
        deleteStatus();
        exitStatus = 0;
      }
      else {
        System.out.println(LocalizedStrings.CacheServerLauncher_TIMEOUT_WAITING_FOR_0_TO_SHUTDOWN_STATUS_IS_1
          .toLocalizedString(this.baseName, this.hostName, this.status));
      }
    }
    else {
      System.out.println(LocalizedStrings.CacheServerLauncher_THE_SPECIFIED_WORKING_DIRECTORY_0_CONTAINS_NO_STATUS_FILE
        .toLocalizedString(this.workingDir, this.hostName));
    }

    if (DONT_EXIT_AFTER_LAUNCH) {
      return;
    }

    System.exit(exitStatus);
  }

  /**
   * Notes that an error has occurred in the cache server and that it
   * has shut down because of it.
   */
  protected void setServerError(final String msg, final Throwable t) {
    try {
      writeStatus(createStatus(SHUTDOWN, getProcessId(), msg, t));
    }
    catch (Exception e) {
      if (logger != null) {
        logger.severe(e);
      }
      else {
        e.printStackTrace();
      }
      System.exit(1);
    }
  }

  protected int getProcessId() {
    return NativeCalls.getInstance().getProcessId();
  }

  /**
   * Sets the status of a cache server by serializing a <code>Status</code>
   * instance to a file in the server's working directory.
   */
  public void writeStatus(final Status s) throws IOException {
    s.write();
  }

  /**
   * Reads a cache server's status from a file in its working directory.
   */
  protected Status readStatus() throws InterruptedException, IOException {
    Status status = spinReadStatus(getStatusPath());
    if (status != null) {
      return status;
    } else {
      return Status.read(this.baseName, getStatusPath());
    }
  }

  protected String getLogFilePath(final Properties props) {
    String logFilePath = props.getProperty(LOG_FILE);
    if (logFilePath == null || logFilePath.length() == 0) {
      logFilePath = this.defaultLogFileName;
    }
    return getWorkingDirPath().resolve(logFilePath).toString();
  }

  /**
   * Reads {@link DistributedSystem#PROPERTY_FILE} and determines if the
   * {@link DistributionConfig#LOG_FILE_NAME} property is set to stdout
   * @return true if the logging would go to stdout
   */
  protected static boolean isLoggingToStdOut() {
    Properties gfprops = new Properties();
    URL url = DistributedSystem.getPropertyFileURL();
    if (url != null) {
      try {
        gfprops.load(url.openStream());
      } catch (IOException io) {
        //throw new GemFireIOException("Failed reading " + url, io);
        System.out.println("Failed reading " + url);
        System.exit( 1 );
      }
      final String logFile = gfprops.getProperty(DistributionConfig.LOG_FILE_NAME);
      if ( logFile == null || logFile.length() == 0 ) {
        return true;
      }
    } else {
      //Didnt find a property file, assuming the default is to log to stdout
      return true;
    }
    return false;
  }

  /**
   * Process information contained in the options map and add to the command
   * line of the subprocess as needed.
   */
  protected void addToServerCommand(final List<String> commandLine, final Map<String, Object> options) {
    final ListWrapper<String> commandLineWrapper = new ListWrapper<String>(commandLine);

    if (Boolean.TRUE.equals(options.get(REBALANCE))) {
      commandLineWrapper.add("-rebalance");
    }
    String criticalHeapThreshold = (String)options.get(CRITICAL_HEAP_PERCENTAGE);
    if (criticalHeapThreshold != null) {
      commandLineWrapper.add(criticalHeapThreshold);
    }
    String evictionHeapThreshold = (String)options
        .get(EVICTION_HEAP_PERCENTAGE);
    if (evictionHeapThreshold != null) {
      commandLineWrapper.add(evictionHeapThreshold);
    }
    
    String criticalOffHeapThreshold = (String)options.get(CRITICAL_OFF_HEAP_PERCENTAGE);
    if (criticalOffHeapThreshold != null) {
      commandLineWrapper.add(criticalOffHeapThreshold);
    }
    String evictionOffHeapThreshold = (String)options
        .get(EVICTION_OFF_HEAP_PERCENTAGE);
    if (evictionOffHeapThreshold != null) {
      commandLineWrapper.add(evictionOffHeapThreshold);
    }

    commandLineWrapper.add((String) options.get(DISABLE_DEFAULT_SERVER));
    commandLineWrapper.add((String) options.get(SERVER_PORT));
    commandLineWrapper.add((String) options.get(SERVER_BIND_ADDRESS));

    final Properties props = (Properties) options.get(PROPERTIES);

    for (final Object key : props.keySet()) {
      commandLineWrapper.add(key + "=" + props.getProperty(key.toString()));
    }

    if (props.getProperty(DistributionConfig.LOG_FILE_NAME) == null && CacheServerLauncher.isLoggingToStdOut()) {
      // Do not allow the cache server to log to stdout; override the logger with #defaultLogFileName
      commandLineWrapper.add(DistributionConfig.LOG_FILE_NAME + "=" + defaultLogFileName);
    }
  }

  /**
   * This method is called immediately following cache creation in the spawned
   * process, but prior to setting the RUNNING flag in the status file. So the
   * spawning process will block until this method completes.
   */
  protected void startAdditionalServices(Cache cache,
      Map<String, Object> options, Properties props) throws Exception {
  }

  /**
   * This method is called prior to DistributedSytstem.disconnect(). Care should
   * be taken not to take too long in this method or else
   * #CacheServerLauncher.stop may timeout.
   */
  protected void stopAdditionalServices() throws Exception {
  }

  protected boolean printLaunchCommand() {
    return PRINT_LAUNCH_COMMAND;
  }

  /**
   * A List implementation that disallows null values.
   * @param <E> the Class type for the List elements.
   */
  protected static class ListWrapper<E> extends AbstractList<E>  {

    private static final ThreadLocal<Boolean> addResult = new ThreadLocal<Boolean>();

    private final List<E> list;

    public ListWrapper(final List<E> list) {
      assert list != null : "The List cannot be null!";
      this.list = list;
    }

    @Override
    public boolean add(final E e) {
      final boolean localAddResult = super.add(e);
      return (localAddResult && addResult.get());
    }

    @Override
    public void add(final int index, final E element) {
      if (element != null) {
        list.add(index, element);
      }
      addResult.set(element != null);
    }

    @Override
    public E get(final int index) {
      return this.list.get(index);
    }

    @Override
    public E remove(final int index) {
      return list.remove(index);
    }

    @Override
    public E set(final int index, final E element) {
      return (element != null ? list.set(index, element) : list.get(index));
    }

    @Override
    public int size() {
      return list.size();
    }
  }

  private class MainLogReporter extends Thread implements StartupStatusListener {
    private volatile String lastLogMessage;
    private final Status status;
    boolean running = true;

    public MainLogReporter(Status status) {
      this.status = status;
    }

    public synchronized void shutdown() {
      this.running = false;
      this.status.dsMsg = null;
      this.notifyAll();
    }

    @Override
    public void setStatus(String status) {
      lastLogMessage = status;
    }

    @Override
    public synchronized void run() {
      while(running) {
        try {
          wait(1000);
        } catch (InterruptedException e) {
          //this should not happen.
          break;
        }
        if(running && lastLogMessage != status.dsMsg
            // don't overwrite if someone else has already written a message
            // for WAITING status
            && !(status.state == WAITING && status.dsMsg != null)) {
          status.dsMsg = lastLogMessage;
          try {
            writeStatus(status);
          } catch (IOException e) {
            //this could happen if there was a concurrent write to the file
            //eg a stop.
            continue;
          }
        }
      }
    }
  }
}
