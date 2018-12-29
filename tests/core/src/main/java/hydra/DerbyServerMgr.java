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
import java.io.File;
import java.util.Iterator;
import java.util.Vector;

public class DerbyServerMgr
{
  private static String defaultDerbyNetJarPath;
  private static final String cpSep;
  
  static {
    HostDescription hd = TestConfig.getInstance().getMasterDescription()
            .getVmDescription().getHostDescription();
    if (HostHelper.getLocalHostOS() == OSType.windows) {
      defaultDerbyNetJarPath = hd.getTestDir() + hd.getFileSep() + ".." + hd.getFileSep() + ".." +
          hd.getFileSep() + ".." + hd.getFileSep() + "libs" + hd.getFileSep() + "snappydata-store-hydra-tests-" +
              ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) + "-all.jar";
      cpSep = ";";
    } else {
      defaultDerbyNetJarPath = hd.getTestDir() + hd.getFileSep() + ".." + hd.getFileSep() + ".." +
          hd.getFileSep() + ".." + hd.getFileSep() + "libs" + hd.getFileSep() + "snappydata-store-hydra-tests-" +
              ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) + "-all.jar";
      cpSep = ":";
    }
  }
  private static final String JAVA_CMD = System.getProperty("java.home")
        + File.separator + "bin" + File.separator + "java ";


  private static final int MAX_WAIT_SEC = 300;

  private static int Port;
  private static int PID;
  private static DerbyServerHelper.Endpoint DerbyServerEndpoint;

  /*
   * Builds a derby command line, including derbynet classpath
   */
  protected static String getDerbyCmd(HostDescription hd) {
    //String derbyNetJarPath = TestConfig.tab().stringAt(Prms.derbyServerClassPath, defaultDerbyNetJarPath);
    String derbyNetJarPath = defaultDerbyNetJarPath;
    // Comminting as derby jars are packed in hydra jar which is set in defaultDerbyNetJarPath.
    //Long key = Prms.derbyServerClassPath;
    Long key = null;
    Vector paths = TestConfig.tab().vecAt(key, null);
    if (paths != null) {
      for (Iterator it = paths.iterator(); it.hasNext();) {
        String path = TestConfig.tab().getString(key, it.next());
        if (path == null || path.equalsIgnoreCase(BasePrms.NONE)) {
          it.remove();
        }
      }

      if (paths.size() > 0) {
        paths = EnvHelper.expandEnvVars(paths, hd);
        derbyNetJarPath = EnvHelper.asPath(paths, hd);
      }
    }
    Log.getLogWriter().fine("derbynet.jar path: " + derbyNetJarPath);

    String derbyCmd = "-classpath " + derbyNetJarPath + cpSep
        + System.getProperty("JTESTS") + " " // for procedure call
        + "org.apache.derby.drda.NetworkServerControl ";
    return derbyCmd;
  }

  /**
   * Starts a derby server and waits for it to exist.
   */
  protected static void startDerbyServer()
  {
    HostDescription hd = TestConfig.getInstance().getMasterDescription()
                                   .getVmDescription().getHostDescription();
    String host = hd.getHostName();
    Port = PortHelper.getRandomPort();

    Log.getLogWriter().info("Starting derby server at "
                           + host + ":" + Port + "...");

    //String args = TestConfig.tab().stringAt(Prms.extraDerbyServerVMArgs, "");
    String args = getExtraDerbyServerVMArgs();
    if (args.length() > 0) args += " ";
    String cmd = JAVA_CMD + args + getDerbyCmd(hd)
               + "start -noSecurityManager -h " + host + " -p " + Port;
    PID = ProcessMgr.bgexec(cmd);
    Nuker.getInstance().recordPID(hd, PID);
    DerbyServerEndpoint = new DerbyServerHelper.Endpoint(host, Port, PID);
    ProcessMgr.waitForLife(host, PID, MAX_WAIT_SEC);

    Log.getLogWriter().info("Started derby server with pid=" + PID
                           + " at " + host + ":" + Port);
  }

  /**
   * Stops a derby server and waits for it to die.
   */
  protected static void stopDerbyServer()
  {
    HostDescription hd = TestConfig.getInstance().getMasterDescription()
                                   .getVmDescription().getHostDescription();
    String host = hd.getHostName();
    Log.getLogWriter().info("Stopping derby server at "
                           + host + ":" + Port + "...");
    String cmd = JAVA_CMD + getDerbyCmd(hd) + "shutdown -h " + host + " -p " + Port;
    if (TestConfig.tab().booleanAt(Prms.testSecurity, false))
      cmd += " -user superUser -password superUser "; //hardcoded for now
    ProcessMgr.fgexec(cmd, MAX_WAIT_SEC);
    ProcessMgr.waitForDeath(host, PID, MAX_WAIT_SEC);
    DerbyServerEndpoint = null;
    Nuker.getInstance().removePID(hd, PID);

    Log.getLogWriter().info("Stopped derby server with pid=" + PID
                           + " at " + host + ":" + Port);
  }

  /**
   * Returns the derby server endpoint, if any.
   */
  protected static DerbyServerHelper.Endpoint getEndpoint()
  {
    return DerbyServerEndpoint;
  }
  
  @SuppressWarnings("unchecked")
	protected static String getExtraDerbyServerVMArgs() {
    Vector<String> args = TestConfig.tab().vecAt(Prms.extraDerbyServerVMArgs, null);
    if (args == null) return "";
    else {
      StringBuilder str = new StringBuilder();
      for (Iterator it = args.iterator(); it.hasNext();) {
        str.append(it.next());
      }
      return str.toString();
    }
  }
}
