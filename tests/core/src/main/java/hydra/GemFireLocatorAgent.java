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

import com.gemstone.gemfire.*;
import com.gemstone.gemfire.distributed.Locator;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalLocator;
import java.io.*;
import java.rmi.*;
import java.rmi.server.*;
import java.util.*;
import util.TestHelper;

/**
 *
 * Instances of this class represent gemfire locator agents
 * started by master controller in hydra multiuser tests.  These
 * are always peer locators.
 *
 */
public class GemFireLocatorAgent
    extends UnicastRemoteObject
    implements GemFireLocatorAgentIF, Runnable { 

  //////////////////////////////////////////////////////////////////////////////
  ////    STATIC FIELDS                                                     ////
  //////////////////////////////////////////////////////////////////////////////

  public static final String LOCATOR_LOG_FILE_PREFIX = "locator_";

  /** When true, this is a master-managed locator VM */
  public static boolean MasterManagedLocator = false;

  /** The agent port */ 
  private static int MyPort = -1;

  /** The vm host */ 
  private static String MyHost = null;

  /** The vm process id */ 
  private static int MyPid = -1;

  /** The distributed system name */ 
  private static String MyDistributedSystem = null;

  /** The thread name */ 
  private static String MyThreadName = null;

  /** Absolute filename of system directory */
  private static String MySysDirName = null;

  /**  Stub reference to a remote master controller */
  public static MasterProxyIF Master = null;

  //////////////////////////////////////////////////////////////////////////////
  ////    CONSTRUCTORS                                                      ////
  //////////////////////////////////////////////////////////////////////////////

  public GemFireLocatorAgent() throws RemoteException { super(); }

  //////////////////////////////////////////////////////////////////////////////
  ////    STARTUP                                                           ////
  //////////////////////////////////////////////////////////////////////////////

  /**
   *  Starts peer locator and registers with the master controller. 
   */
  public void run() {
    Thread.currentThread().setName( MyThreadName );

    GemFireLocatorAgentRecord lar = new GemFireLocatorAgentRecord();
    lar.setDistributedSystem( MyDistributedSystem );
    lar.setGemFireLocatorAgent( this );
 
    // Get GemFireDescription for MyDistributedSystem
    GemFireDescription gfd = null;
    Map gfds = TestConfig.getInstance().getGemFireDescriptions();
    for ( Iterator i = gfds.values().iterator(); i.hasNext(); ) {
      gfd = (GemFireDescription) i.next();
      if ( (gfd.getDistributedSystem()).equals( MyDistributedSystem )) {
	break;
      }
    }
   
    // start the locator
    try {
      // config for locator should match gemfire system - except for security
      Properties props = gfd.getDistributedSystemProperties();
      // always set the distributed system id on locators
      GemFireVersionHelper.setDistributedSystemId(props,
                       gfd.getDistributedSystemId());

      //---- protocol ----//
      String addr = HostHelper.getHostAddress();
      if (Boolean.getBoolean(Prms.USE_IPV6_PROPERTY)) {
        props.setProperty(DistributionConfig.BIND_ADDRESS_NAME, addr);
        props.setProperty(DistributionConfig.SERVER_BIND_ADDRESS_NAME, addr);
      }
      
      GemFireDistributionLocator.start( MyDistributedSystem, MyPort, props,
        gfd.isServerLocator().booleanValue());
      // register the classIds for GemFireXD to allow processing of
      // GfxdConfigMessages
      try {
        Class.forName(
            "com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable")
            .getMethod("initTypes").invoke(null);
      } catch (ClassNotFoundException ex) {
        // ignore if the GemFireXD classes are not found in CLASSPATH
      }
      lar.setPort( MyPort );
      lar.setHostName( MyHost );
      lar.setHostAddress(addr);
      lar.setProcessId( MyPid );
      lar.setServerLocator(gfd.isServerLocator().booleanValue());
    } 
    catch (VirtualMachineError e) {
      SystemFailure.initiateFailure(e);
      throw e;
    }
    catch( Throwable t ) {
      log().severe( "Failed to start the GemFireDistributionLocator\n" + TestHelper.getStackTrace(t) );
      lar.setProcessId( -1 );
    }
    try {
      synchronized( Master ) {
        Master.registerGemFireLocatorAgent( lar );
      }
    } catch( RemoteException e ) {
      throw new HydraRuntimeException( MyThreadName + " unable to contact master", e );
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  ////    SHUTDOWN                                                          ////
  //////////////////////////////////////////////////////////////////////////////

  public void shutDownGemFireLocatorAgent() throws RemoteException {
    log().info( MyThreadName + " shutting down at master's request." );
    log().info( "Now terminating process." );
    HostDescription hd = TestConfig.getInstance().getMasterDescription()
                                   .getVmDescription().getHostDescription();
    Master.removePID(hd, MyPid);
    System.exit(0);
  }

  //////////////////////////////////////////////////////////////////////////////
  ////    MISC                                                              ////
  //////////////////////////////////////////////////////////////////////////////

  private static LogWriter log() {
    return Log.getLogWriter();
  }

  //////////////////////////////////////////////////////////////////////////////
  ////    MAIN                                                              ////
  //////////////////////////////////////////////////////////////////////////////

  /**
   *  The startup routine for this host agent.  Creates a log file, looks up
   *  the master controller, and starts a thread to wait for requests.
   */
  public static void main(String[] args) {
    MasterManagedLocator = true;

    // note the info for this vm
    MyHost = HostHelper.getLocalHost();
    MyPid = ProcessMgr.getProcessId();
    MyDistributedSystem = System.getProperty( GemFirePrms.DISTRIBUTED_SYSTEM_NAME_PROPERTY );
    MyThreadName = "locatoragent_" + MyDistributedSystem + "_" + MyHost + "_" + MyPid;
    MySysDirName = System.getProperty("user.dir")  + File.separator
                 + LOCATOR_LOG_FILE_PREFIX + MyDistributedSystem; 

    Thread.currentThread().setName( MyThreadName );

    // create the log
    LogWriter log = Log.createLogWriter( MyThreadName, MyThreadName, "all", System.getProperty( "user.dir" ), true );

    // log info for this vm
    log.info( ProcessMgr.processAndBuildInfoString() );

    // do RMI lookup of Master
    if ( Master == null )
      Master = RmiRegistryHelper.lookupMaster();

    // look up the agent port
    MyPort = TestConfig.getInstance().getMasterDescription()
                       .getLocatorPort( MyDistributedSystem );

    // create the locator agent
    GemFireLocatorAgent agent = null;
    try {
      agent = new GemFireLocatorAgent();
    } catch( RemoteException e ) {
      throw new HydraRuntimeException( "Unable to create GemFireLocatorAgent for " + MyDistributedSystem, e );
    }
    Thread t = new Thread(agent);
    t.start();
    try {
      t.join();
      Collection locators = Locator.getLocators();
      if (locators == null || locators.isEmpty()) {
        log.severe("no locators located");
      } else {
        InternalLocator locator = (InternalLocator)locators.iterator().next();
        locator.waitToStop();
      }
    } catch (InterruptedException e) {
      log.info("interrupted");
      return;
    }
  }

  /**
   * Returns the absolute filename of the system directory for this
   * master-managed locator.
   */
  protected static String getSysDirName() {
    return MySysDirName;
  }
}
