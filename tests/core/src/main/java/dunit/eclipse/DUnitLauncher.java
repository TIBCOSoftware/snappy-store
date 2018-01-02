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
package dunit.eclipse;

import hydra.GemFirePrms;
import hydra.Log;
import hydra.RemoteTestModule;
import hydra.RemoteTestModuleIF;
import hydra.TestConfig;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.rmi.AccessException;
import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;

import batterytest.greplogs.SuspectGrepOutputStream;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.distributed.Locator;
import com.gemstone.gemfire.internal.AvailablePortHelper;

import dunit.DUnitEnv;
import dunit.Host;
import dunit.SerializableCallable;

/**
 * A class to build a fake test configuration and launch some DUnit VMS.
 * 
 * For use within eclipse. This class completely skips hydra and just starts
 * some vms directly, creating a fake test configuration
 * 
 * Also, it's a good idea to set your working directory, because the test code
 * a lot of files that it leaves around.
 * 
 * @author dsmith
 * 
 */
public class DUnitLauncher {
  
  /**
   * 
   */
  private static final String LAUNCHED_PROPERTY = "gemfire.DUnitLauncher.LAUNCHED";
  static final String VM_NUM_PARAM = "gemfire.DUnitLauncher.VM_NUM";
  static final String RMI_PORT_PARAM = "gemfire.DUnitLauncher.RMI_PORT";
  private static final int NUM_VMS = Integer.getInteger("dunit.num.vms", 4);
  static final String MASTER_PARAM = "DUNIT_MASTER";
  private static final long STARTUP_TIMEOUT = 30 * 1000;
  public static final String DUNIT_DIR = "dunit";
  public static final String LOG_LEVEL = "fine";
  private static final File  OUTPUT_FILE = new File("dunit_output.log");
  public static final String WORKSPACE_DIR_PARAM = "WORKSPACE_DIR";
  
  private static boolean isLaunched = false;
  
//  public static final String LOG_LEVEL = "warning";
  static int locatorPort;
  private static SuspectGrepOutputStream suspectGrepper;

  private DUnitLauncher() {
    
  }
  
  /**
   * Launch Fake DUnit. Will only have any effect if this test was started as a
   * normal JUnitTest, rather than through DUnit.
   */
  public static void launchIfNeeded() {
    if(System.getProperties().contains(VM_NUM_PARAM)) {
      //where a dunit child vm, do nothing.
      return;
    }
    
    //this is the best test I can find right now
    //for whether we're inside hydra already
    //if we are, this method will return quickly.
    try {
      TestConfig.getInstance();
    } catch(Exception e) {
      try {
        synchronized(DUnitLauncher.class) {
          if(!isLaunched()) {
            System.err.println("redirecting output to " + OUTPUT_FILE);
            System.setOut(new PrintStream(OUTPUT_FILE));
            launch();
          }
        }
      } catch(Exception e2) {
        throw new RuntimeException("Unable to launch dunit VMS", e2);
      }
    }
  }
  
  /**
   * Test it see if the eclise dunit environment is launched.
   */
  public static boolean isLaunched() {
    return isLaunched;
  }
  
  public static String getLocatorString() {
    return "localhost[" + locatorPort + "]";
  }

  
  private static void launch() throws URISyntaxException, AlreadyBoundException, IOException, InterruptedException, NotBoundException  {
//    initialize the log writer that hydra uses
    LogWriter log = Log.createLogWriter( "dunit-master", LOG_LEVEL );

    locatorPort = AvailablePortHelper.getRandomAvailableTCPPort();
     
    //create an RMI registry and add an object to share our tests config
    int namingPort = AvailablePortHelper.getRandomAvailableTCPPort();
    Registry registry = LocateRegistry.createRegistry(namingPort);
    Master master = new Master();
    registry.bind(MASTER_PARAM, master);

    final ProcessManager processManager = new ProcessManager(NUM_VMS + 1, namingPort);

    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        processManager.killVMs();
      }
    });
    
    //launch the remote VMS
    processManager.launchVMs();
    
    //wait for the VMS to start up
    if(!master.waitForVMs(STARTUP_TIMEOUT)) {
      throw new RuntimeException("VMs did not start up with 30 seconds");
    }
    
    //populate the Host class with our stubs. The tests use this host class
    EclipseDUnitHost host = new EclipseDUnitHost(InetAddress.getLocalHost().getCanonicalHostName(), NUM_VMS);
    host.init(registry);

    initSystemProperties(log);
    
    startLocator(registry);
  }
  
  public static Properties getDistributedSystemProperties() {
    Properties p = new Properties();
    p.setProperty("locators", getLocatorString());
    p.setProperty("mcast-port", "0");
    p.setProperty("log-level", DUnitLauncher.LOG_LEVEL);
    return p;
  }
  
  private static void startLocator(Registry registry) throws IOException, NotBoundException {
    RemoteTestModuleIF remote = (RemoteTestModuleIF) registry.lookup("vm" + NUM_VMS);
    //TODO - I really want the locator to log to standard out, this kinda bites.
    final File locatorLogFile = new File("locator-" + locatorPort + ".log");
    remote.executeMethodOnObject(new SerializableCallable() {
      public Object call() throws IOException {
        Properties p = getDistributedSystemProperties();
        // I never want this locator to end up starting a jmx manager
        // since it is part of the unit test framework
        p.setProperty("jmx-manager", "false");
        Locator.startLocatorAndDS(locatorPort, locatorLogFile, p);
        return null;
      }
    }, "call");
  }

  public static void initSystemProperties(LogWriter log) {
    //fake out tests that are using a bunch of hydra stuff
    System.setProperty(GemFirePrms.GEMFIRE_NAME_PROPERTY, "gemfire1");
    String workspaceDir = System.getProperty(DUnitLauncher.WORKSPACE_DIR_PARAM) ;
    workspaceDir = workspaceDir == null ? new File(".").getAbsolutePath() : workspaceDir;
    System.setProperty("JTESTS", workspaceDir + "/tests");
    //Some of the gemfirexd dunits look for xml files in this directory.
    System.setProperty("EXTRA_JTESTS", workspaceDir + "/gemfirexd/GemFireXDTests");
    System.out.println("Using JTESTS is set to " + System.getProperty("JTESTS"));
    
    //These properties are set in build.xml when it starts dunit
    System.setProperty("gf.ldap.server", "ldap");
    System.setProperty("gf.ldap.basedn", "ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
    
    //indicate that this VM is controlled by the eclipse dunit.
    isLaunched = true;
    RemoteTestModule.Master = new FakeMaster();
    DUnitEnv.set(new EclipseDUnitEnv());
    suspectGrepper = new SuspectGrepOutputStream(System.out, "STDOUT", 5, "dunit", Charset.defaultCharset());
    System.setOut(new PrintStream(suspectGrepper));
  }
  
  public static void checkForSuspectStrings() {
    if(suspectGrepper != null) {
      String suspectString = suspectGrepper.getAndClearSuspects();
      if(suspectString != null) {
        Assert
            .fail("Suspicious strings were written to the log during this run. "
            	+ "Fix the strings or use "
            	+ "DistributedTestCase.addExpectedException to ignore.\n "
                + suspectString);
      }
    }
  }

  public interface MasterRemote extends Remote {
    public int getLocatorPort() throws RemoteException;
    public void signalVMReady() throws RemoteException;
    public void ping() throws RemoteException;
  }
  
  public static class Master extends UnicastRemoteObject implements MasterRemote {
    private static final long serialVersionUID = 1178600200232603119L;
    
    private CountDownLatch latch = new CountDownLatch(NUM_VMS + 1);


    public Master() throws RemoteException {
      super();
    }

    public int getLocatorPort()  throws RemoteException{
      return locatorPort;
    }
    
    public void signalVMReady() {
      latch.countDown();
    }
    
    public boolean waitForVMs(long timeout) throws InterruptedException {
      return latch.await(timeout, TimeUnit.MILLISECONDS);
    }

    public void ping() {
      //do nothing
    }
  }
  
  private static class EclipseDUnitHost extends Host {

    private static final long serialVersionUID = -8034165624503666383L;
    private int numVms;
    
    public EclipseDUnitHost(String hostName, int numVms) {
      super(hostName);
      this.numVms = numVms;
    }
    
    public void init(Registry registry) throws AccessException, RemoteException, NotBoundException {
      for(int i = 0; i < numVms; i++) {
        RemoteTestModuleIF remote = (RemoteTestModuleIF) registry.lookup("vm" + i);
        addVM(i, "vm" + i, remote);
      }
      
      //for ease of debugging, add another VM that is the local host.
      addVM(numVms, "vm" + numVms, new FakeRemoteTestModule(Log.getLogWriter()));
      addLocator(numVms+1, "vm" + numVms + 1, (RemoteTestModuleIF) registry.lookup("vm" + NUM_VMS));
      
      addHost(this);
    }

    @Override
    public int getVMCount() {
      //Don't count the debugging VM
      return super.getVMCount() - 1;
    }
    
  }

}
