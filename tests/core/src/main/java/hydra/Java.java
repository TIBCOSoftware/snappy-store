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

package hydra;

import hydra.HostHelper.OSType;
import hydra.log.LogPrms;
import java.io.*;
import java.util.*;
import java.rmi.RemoteException;

/**
*
* Instances of Java encode command line arguments and other info
* needed for starting an instance of a java interpreter.
*
*/
public class Java {

  /**
  *
  * Merge the given log files into a single txt file.
  *
  */
  public static int javaMergeLogFiles( String fn, List logFiles )
  {
    String sep = File.separator;
    String cmd = System.getProperty( "java.home" ) + sep + "bin" + sep + "java ";;
    cmd += "-classpath " + System.getProperty( "java.class.path" ) + " ";
    //cmd += "-Djava.library.path=" + System.getProperty( "java.library.path" ) + " ";
    cmd += "-Duser.dir=" + System.getProperty( "user.dir" ) + " ";
    int maxHeap = TestConfig.tab().intAt(LogPrms.mergeLogFilesMaxHeapMB, 512);
    cmd += "-Xmx" + maxHeap + "m ";

    // give the host and port of the master RMI registry
    cmd += "-D" + MasterController.RMI_HOST_PROPERTY + "="
        + System.getProperty(MasterController.RMI_HOST_PROPERTY) + " ";
    cmd += "-D" + MasterController.RMI_PORT_PROPERTY + "="
        + System.getProperty(MasterController.RMI_PORT_PROPERTY) + " ";

    String args = TestConfig.tab().stringAt(LogPrms.mergeLogFilesArgs, "-dirCount 1");
    cmd += "com.gemstone.gemfire.internal.MergeLogFiles " + args
        + " -mergeFile " + fn + " ";
    for ( Iterator i = logFiles.iterator(); i.hasNext(); ) {
      File logFile = (File) i.next();
      cmd += logFile.getAbsolutePath() + " ";
    }

    int pid = ProcessMgr.bgexec( cmd );

    // wait for it to complete
    String host = HostHelper.getLocalHost();
    int maxWaitSec = 300;
    if ( ! ProcessMgr.waitForDeath( host, pid, maxWaitSec ) ) {
      String err = "Waited more than " + maxWaitSec + " seconds for logs to be merged";
      boolean killed = ProcessMgr.killProcessWait( host, pid, 10 );
      if ( killed )
        throw new HydraRuntimeException( err + ", killed merge process" );
      else
        throw new HydraRuntimeException( err + ", tried (and failed) to kill merge process" );
    }
    return pid;
  }
  /**
  *
  * Start a gemfire locator agent for the specified distributed system on the
  * master host based on system properties.
  *
  */
  public static int javaGemFireLocatorAgent( String distributedSystemName, String vmArgs, String classpath )
  {
    HostDescription hd = TestConfig.getInstance().getMasterDescription()
                                   .getVmDescription().getHostDescription();

    String sep = File.separator;
    String cmd = System.getProperty( "java.home" ) + sep + "bin" + sep + "java ";;
    cmd += "-classpath " + classpath + " ";
    //cmd += "-Djava.library.path=" + System.getProperty( "java.library.path" ) + " ";
    cmd += "-Duser.dir=" + System.getProperty( "user.dir" ) + " ";
    cmd += "-D" + GemFirePrms.DISTRIBUTED_SYSTEM_NAME_PROPERTY + "=" + distributedSystemName + " ";
    cmd += "-DJTESTS=" + System.getProperty("JTESTS") + " ";
    String extraJtests = System.getProperty("EXTRA_JTESTS");
    if (extraJtests != null) {
      cmd += "-DEXTRA_JTESTS=" + extraJtests + " ";
    }

    // give the host and port of the master RMI registry
    cmd += "-D" + MasterController.RMI_HOST_PROPERTY + "="
        + System.getProperty(MasterController.RMI_HOST_PROPERTY) + " ";
    cmd += "-D" + MasterController.RMI_PORT_PROPERTY + "="
        + System.getProperty(MasterController.RMI_PORT_PROPERTY) + " ";

    String p = System.getProperty( "gemfire.home" );
    if ( p != null ) cmd += "-Dgemfire.home=" + p + " ";

    if (vmArgs != null) {
      cmd += vmArgs + " ";
    }

    cmd += "hydra.GemFireLocatorAgent";

    int pid = ProcessMgr.bgexec( cmd );
    Nuker.getInstance().recordPID(hd, pid);
    return pid;
  }
  
  /**
   * Start a GFMon/WindowTester VM on the master host based on system
   * properties.
   */
  public static int javaGFMon()
  {
    HostDescription hd = TestConfig.getInstance().getMasterDescription()
                                   .getVmDescription().getHostDescription();

    String sep = File.separator;
    String cmd = System.getProperty( "java.home" ) + sep + "bin" + sep + "java ";;
    
    String gemfire = System.getProperty("gemfire.home");
    if (gemfire == null) {
      String s = "gemfire.home system property not set";
      throw new HydraRuntimeException(s);
    }
    Vector bootpath = new Vector();
    bootpath.add(gemfire + sep + "lib" + sep + "gemfirexd-" +
            ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) + ".jar");
    bootpath.add(System.getProperty("JTESTS"));

    bootpath = EnvHelper.expandEnvVars(bootpath, hd);
    String bootpathStr = EnvHelper.asPath(bootpath, hd);
    cmd += "-Xbootclasspath/a:" + bootpathStr + " ";
    cmd += GFMonPrms.getExtraVMArgs() + " ";
    String resultDir = hd.getUserDir();
    String gfmonDir = resultDir + sep + GFMonMgr.GFMON_DIR;
    if (!FileUtil.exists(gfmonDir)) {
      FileUtil.mkdir(gfmonDir);
    }
    cmd += "-Dstats.archive-file=" + gfmonDir + sep + "statArchive.gfs ";
    cmd += "-D" + GFMonPrms.LOG_LEVEL_PROPERTY_NAME + "="
         + GFMonPrms.getLogLevel() + " ";
    if (hd.getExtraTestDir() != null) {
      cmd += "-DEXTRA_JTESTS=" + hd.getExtraTestDir() + " ";
    }

    // give the host and port of the master RMI registry
    cmd += "-D" + MasterController.RMI_HOST_PROPERTY + "="
        + System.getProperty(MasterController.RMI_HOST_PROPERTY) + " ";
    cmd += "-D" + MasterController.RMI_PORT_PROPERTY + "="
        + System.getProperty(MasterController.RMI_PORT_PROPERTY) + " ";

    String gfmon = System.getProperty("GFMON");
    if (gfmon == null) {
      String s = "GFMON system property not set";
      throw new HydraRuntimeException(s);
    }
    cmd += "-cp " + gfmon + sep + "eclipse" + sep + "startup.jar ";
    cmd += "org.eclipse.core.launcher.Main ";
    cmd += "-clean -noupdate -verbose -consolelog ";
    cmd += "-configuration " + gfmon + sep + "eclipse" + sep + "configuration ";
    cmd += "-data " + resultDir + sep + "wintest ";
    String testClassName = GFMonPrms.getTestClassName();
    cmd += "formatter=org.apache.tools.ant.taskdefs.optional.junit."
         + "XMLJUnitResultFormatter,"
         + resultDir + sep + "wintest" + sep + testClassName + "-result.xml ";
    cmd += "-application com.windowtester.runner.application ";
    cmd += "-testPluginName gfmonIIWtPro_test ";
    cmd += "-classname " + testClassName + " ";
    cmd += "-testApplication com.gemstone.gemfire.tools.application ";
    OSType os = HostHelper.getLocalHostOS();
    switch (os) {
      case unix:
        cmd += "-WS gtk";
        break;
      case windows:
        // this works around 32/64 bit library discovery issues on windows
        // and, if used at all, use this deprecated version to handle the above
        //cmd += "-WS win32";
        break;
      default:
        String s = "Unsupported O/S for GFMon WindowTester: " + os;
        throw new UnsupportedOperationException(s);
    }
    int pid = ProcessMgr.bgexec(cmd);
    Nuker.getInstance().recordPID(hd, pid);
    return pid;
  }
  
  /**
   * Starts a hostagent on a remote host based on the hostagent description.
   */
  public static int javaHostAgent(HostAgentDescription had) {
    HostDescription hd = had.getHostDescription();
    final char sep = hd.getFileSep();

    StringBuffer cmd = new StringBuffer();
    cmd.append(hd.getJavaHome()).append(sep)
       .append("bin").append(sep).append("java ")
       .append("-classpath ").append(had.getClassPath()).append(" ")
       .append("-DJTESTS=").append(hd.getTestDir()).append(" ")
       .append("-Duser.dir=").append(hd.getUserDir()).append(" ")
       .append("-D" + MasterController.RMI_HOST_PROPERTY + "="
          + System.getProperty(MasterController.RMI_HOST_PROPERTY)).append(" ")
       .append("-D" + MasterController.RMI_PORT_PROPERTY + "="
          + System.getProperty(MasterController.RMI_PORT_PROPERTY)).append(" ");

    if ( hd.getGemFireHome() != null ) {
      cmd.append("-Dgemfire.home=").append(hd.getGemFireHome()).append(" ");
    }
    cmd.append("-DarchiveStats=").append(had.getArchiveStats()).append(" " );
    cmd.append("hydra.HostAgent");

    OSType src = HostHelper.getLocalHostOS(); // master
    OSType dst = hd.getOSType();
    Log.getLogWriter().info("Starting hostagent from " + src + " on " + dst);
    int pid = -1;
    if (src == OSType.windows || dst == OSType.windows) {
      Log.getLogWriter().info("Using bootstrapper");
      String url = "rmi://" + hd.getHostName()
                 + ":" + hd.getBootstrapPort()
                 + "/" + Bootstrapper.RMI_NAME;
      Log.getLogWriter().info("Looking up bootstrapper at " + url);
      BootstrapperProxyIF bootstrapper =
          (BootstrapperProxyIF)RmiRegistryHelper.lookup(url);
      if (bootstrapper == null) {
        String s = "Bootstrapper is null";
        throw new HydraRuntimeException(s);
      }
      Log.getLogWriter().info("Found bootstrapper");
      try {
        String bootcmd = cmd.toString();
        Log.getLogWriter().info("Command is " + bootcmd);
        pid = bootstrapper.bgexec(bootcmd);
      } catch (RemoteException e) {
        String s = "Unable to run command on bootstrapper";
        throw new HydraRuntimeException(s, e);
      }

    } else if (HostHelper.isLocalHost(hd.getHostName())) {
      // this covers the case of archiving agent started for hdfs on localhost
      Log.getLogWriter().info("Not using ssh on localhost: " + hd.getHostName());
      Log.getLogWriter().info("Command is " + cmd);
      pid = ProcessMgr.bgexec(cmd.toString());
    } else {
      Log.getLogWriter().info("Using ssh");
      String sshcmd = Platform.SSH + " " + hd.getHostName() + " " + cmd;
      Log.getLogWriter().info("Command is " + sshcmd);
      pid = ProcessMgr.bgexec(sshcmd.toString());
    }
    // note that pid is recorded by HostAgentMgr
    return pid;
  }

  /**
  *
  * Start a client vm based on the client description and starting thread id.
  *
  */
  public static int javaRemoteTestModule( String masterHost, int masterPid,
         ClientVmRecord vm, String purpose )
  {
    ClientDescription cd = vm.getClientDescription();
    VmDescription vmd = cd.getVmDescription();
    JDKVersionDescription jvd = cd.getJDKVersionDescription();
    VersionDescription vd = cd.getVersionDescription();
    HostDescription hd = vmd.getHostDescription();
    String cmd = new String();

    String javaHome = vm.getJavaHome();

    if ( cd.getJProbeDescription() != null ) {
      cmd += cd.getJProbeDescription().getCommandLine( hd.getJProbeHome(), vmd.getType() );
    } else {
      cmd += javaHome + hd.getFileSep() + "bin" + hd.getFileSep() + "java ";
      if ( vmd.getType() != null ) {
        cmd += "-" + vmd.getType() + " ";
      }
    }

    // add extra vm arguments
    String extraVMArgs = vmd.getExtraVMArgs();
    if ( extraVMArgs != null )
      cmd += extraVMArgs + " ";

    // give the purpose
    if ( purpose != null )
      cmd += "-Dpurpose=" + purpose + " ";

    // give the master host
    cmd += "-D" + Prms.MASTER_HOST_PROPERTY + "=" + masterHost + " ";

    // give the master pid
    cmd += "-D" + Prms.MASTER_PID_PROPERTY + "=" + masterPid + " ";

    // give the host and port of the master RMI registry
    cmd += "-D" + MasterController.RMI_HOST_PROPERTY + "="
        + System.getProperty(MasterController.RMI_HOST_PROPERTY) + " ";
    cmd += "-D" + MasterController.RMI_PORT_PROPERTY + "="
        + System.getProperty(MasterController.RMI_PORT_PROPERTY) + " ";

    // give the logical host name
    String hostName = hd.getName();
    cmd += "-D" + HostPrms.HOST_NAME_PROPERTY + "=" + hostName + " ";

    // give the logical client name
    String clientName = cd.getName();
    cmd += "-D" + ClientPrms.CLIENT_NAME_PROPERTY + "=" + clientName + " ";

    // give the gemfire name, if any
    GemFireDescription gfd = cd.getGemFireDescription();
    if (gfd != null) {
      cmd += "-D" + GemFirePrms.GEMFIRE_NAME_PROPERTY + "=" + gfd.getName() + " ";
    }

    // initialize thread creation parameters
    cmd += "-DnumThreads=" + cd.getVmThreads() + " ";
    cmd += "-DbaseThreadId=" + vm.getBaseThreadId() + " ";

    // initialize the vm id
    cmd += "-Dvmid=" + vm.getVmid() + " ";

    // tell where the test directory can be found
    cmd += "-DJTESTS=" + hd.getTestDir() + " ";

    // tell where the extra test directory can be found
    if( hd.getExtraTestDir() != null ) {
      cmd += "-DEXTRA_JTESTS=" + hd.getExtraTestDir() + " ";
    }

    // give the user dir
    cmd += "-Duser.dir=" + hd.getUserDir() + " ";

    // give the ld library path of the appropriate version
    String libpath = null;
    if (jvd == null && vd == null) {
      libpath = vmd.getLibPath();
    } else if (vd != null) {
      libpath = vd.getLibPath(vmd, vm.getVersion(), javaHome);
    } else { // jvd != null
      libpath = jvd.getLibPath(vmd, javaHome);
    }
    if (libpath != null) {
      cmd += "-Djava.library.path=" + libpath + " ";
    }

    // give the classpath of the appropriate version
    String classpath = null;
    if (jvd == null && vd == null) {
      classpath = vmd.getClassPath();
    } else if (vd != null) {
      classpath = vd.getClassPath(vmd, vm.getVersion(), javaHome);
    } else { // jvd != null
      classpath = jvd.getClassPath(vmd, javaHome);
    }

    // Certain secure Hadoop tests require additional classpath for servers.
    // Hydra does not know who will and won't become a server so must add the
    // classpath to all hydra client JVMs.
    String path = HadoopDescription.getExtraClassPath(TestConfig.getInstance());
    if (path != null) {
      classpath = (classpath == null) ? classpath = path
                                      : path + hd.getPathSep() + classpath;
    }

    if (classpath != null) {
      cmd += "-classpath " + classpath + " ";
    }

    // give the gemfire home of the appropriate version
    String gemfireHome = (vd == null) ? hd.getGemFireHome()
                                      : vd.getGemFireHome(vmd, vm.getVersion());
    cmd += "-Dgemfire.home=" + gemfireHome + " ";

    cmd += "hydra.RemoteTestModule";

    int pid = ProcessMgr.bgexec( hd.getHostName(), cmd );
    Nuker.getInstance().recordPID(hd, pid);
    return pid;
  }

  /**
   * Starst a vm on localhost based on the client description and specified
   * main.
   */
  public static int java( ClientDescription cd, String main )
  {
    VmDescription vmd = cd.getVmDescription();
    HostDescription hd = vmd.getHostDescription();

    final String sep = File.separator;
    String cmd = new String();

    if ( cd.getJProbeDescription() != null ) {
      cmd += cd.getJProbeDescription().getCommandLine( hd.getJProbeHome(), vmd.getType() );
    } else {
      cmd += hd.getJavaHome() + sep + "bin" + sep + "java ";
      if ( vmd.getType() != null ) {
        cmd += "-" + vmd.getType() + " ";
      }
    }

    // add extra vm arguments
    String extraVMArgs = vmd.getExtraVMArgs();
    if ( extraVMArgs != null )
      cmd += extraVMArgs + " ";

    // give the logical client name
    String clientName = cd.getName();
    cmd += "-D" + ClientPrms.CLIENT_NAME_PROPERTY + "=" + clientName + " ";

    // tell what the random seed should be
    long randomSeed = tab().getRandGen().nextLong();
    cmd += "-DrandomSeed=" + randomSeed + " ";

    // tell where the test directory can be found
    cmd += "-DJTESTS=" + hd.getTestDir() + " ";

    // tell where the extra test directory can be found
    if( hd.getExtraTestDir() != null ) {
      cmd += "-DEXTRA_JTESTS=" + hd.getExtraTestDir() + " ";
    }

    // give the host and port of the master RMI registry
    cmd += "-D" + MasterController.RMI_HOST_PROPERTY + "="
        + System.getProperty(MasterController.RMI_HOST_PROPERTY) + " ";
    cmd += "-D" + MasterController.RMI_PORT_PROPERTY + "="
        + System.getProperty(MasterController.RMI_PORT_PROPERTY) + " ";

    // give the user dir
    cmd += "-Duser.dir=" + hd.getUserDir() + " ";

    // Create the user dir if it doesn't already exist.
    (new File(hd.getUserDir())).mkdirs();

    // give the ld library path
    //cmd += "-Djava.library.path=" + vmd.getLibPath() + " ";

    // give the classpath
    cmd += "-classpath " + vmd.getClassPath() + " ";

    // give the gemfire home
    cmd += "-Dgemfire.home=" + hd.getGemFireHome() + " ";

    cmd += main;

    int pid = ProcessMgr.bgexec( hd.getHostName(), cmd );
    if (RemoteTestModule.Master == null) {
      Nuker.getInstance().recordPIDNoDumps(hd, pid);
    } else {
      try {
        RemoteTestModule.Master.recordPIDNoDumps(hd, pid);
      } catch (RemoteException e) {
        String s = "Unable to record PID with master: " + pid;
        throw new HydraRuntimeException(s, e);
      }
    }
    return pid;
  }

  private static ConfigHashtable tab() {
    return TestConfig.tab();
  }
  
}
