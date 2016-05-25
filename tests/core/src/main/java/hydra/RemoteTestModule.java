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

import hydra.log.LogPrms;
import hydra.timeserver.TimeClient;
import hydra.timeserver.TimeServerPrms;

import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;

import util.TestHelper;

/**
 *
 * Instances of this class represent remote clients
 * in hydra multiuser tests.
 *
 */
public class RemoteTestModule
    extends UnicastRemoteObject
    implements RemoteTestModuleIF, Runnable
{ 

/** The client mapping */
 private static List<List<String>> ClientMapping = null;

/** The master controller canonical host name (used for error handling) */ 
 private static String MasterHost;

/** want to make sure this class is loaded so its static init is done */
private static Class c = RMIHydraSocketFactory.class;

/** The master controller process id (used for error handling) */ 
 private static int MasterPid = -1;

/** The logical client name */
 protected static String MyClientName = null;

/** The logical host name */
 protected static String MyLogicalHost = null;

/** The actual vm host */
 public static String MyHost;

/** The canonical name of the host on which this VM runs */ 
 protected static String MyCanonicalHost;

/** The id for the client vm */
 protected static int MyVmid = -1;

/** The vm process id */ 
 public static int MyPid = -1;

/** The number of threads in the vm */ 
 protected static int MyNumThreads = -1;

/** The base thread id for the vm */ 
 public static int MyBaseThreadId = -1;

/**  Stub reference to a remote master controller */
 public static MasterProxyIF Master = null;

/** The time client for this vm */
 protected static TimeClient MyTimeClient = null;

/** The log file name for this vm */
 protected static String MyLogFileName;

/** The logger for this vm */
 protected static LogWriter log;

/** The base (parent) client thread associated with this instance */ 
 protected Thread baseClientThread;

/** Flag to signal that we're already busy with a task */
 protected boolean busy;

/** The current task */ 
 protected TestTask currentTask;

/** Id identifying the current client thread uniquely to the master */
 protected int myTid = -1;

/** Id identifying the current client thread's hydra threadgroup name */
 protected String myThreadGroupName = null;

/** Id identifying the current client thread uniquely to its threadgroup */
 protected int myThreadGroupId = -1;

/** Name identifying the current client thread uniquely */
 protected String myThreadName;

/** Hydra thread local variables */
 Map hydraThreadLocals;


  public RemoteTestModule() throws RemoteException { super(); }

  //////////////////////////////////////////////////////////////////////////////
  //    STARTUP
  //////////////////////////////////////////////////////////////////////////////

  /**
   *  Makes MyNumThreads new RemoteTestModules and fires off each in
   *  a separate execution thread.  Gives each a thread id unique in the test
   *  starting with MyBaseThreadId.
   */
  public static void makeAndStartThreadClients() {
    if ( MyNumThreads < 1 ) {
      return;
    }
    try {
      // if parent thread has owner set properly, all children will follow
      for ( int i = 0; i < MyNumThreads; i++ ) {

        RemoteTestModule mod = null;
        int retryCnt = 0;
        while(retryCnt < 20) {
          try {
            mod = new RemoteTestModule();
            break;
          } catch (RemoteException re) {
            MasterController.sleepForMs(500);
            if (retryCnt == 19) {
              throw re;
            }
            retryCnt++;
          }
        }
        MasterController.sleepForMs(300);

        int tid = MyBaseThreadId + i;
        mod.myTid = tid;
        mod.myThreadName = MasterController.getNameFor( MyVmid, tid,
                                                        MyClientName,
                                                        MyHost, MyPid );

        HydraThread t = new HydraThread( mod, mod.myThreadName );
        mod.baseClientThread = t;
        mod.busy = false;

        t.start();
      }
    } catch( Exception e ) {
      throw new HydraRuntimeException( "Unable to create and start client threads", e );
    }
  }

  /**
   *  Informs the master controller that the new client is ready for work and
   *  provides its instance of RemoteTestModule to establish communication.  In
   *  return, master provides the client with its threadgroup info.
   */
  public synchronized void run() {

    // Register existence with the master
    HydraThreadGroupInfo info = null;
    try {
      synchronized( Master ) {
        info = Master.registerClient( MyVmid, myTid, this );
      }
    } catch( RemoteException e ) {
      handleRemoteException( "Master.registerClient", e );
      if (info == null) {
        String s = "Failed to register successully because the test has already been declared hung and the hydra master is gone.  A possible cause is a problem starting this JVM or it opening its bgexec or vm log file.  Note that the RemoteException logged previously is a symptom of the master being gone.";
        Log.getLogWriter().severe(s);
        System.exit(1);
      }
    }

    // Assign this client its hydra thread group name and id
    myThreadGroupName = info.getThreadGroupName();
    myThreadGroupId = info.getThreadGroupId();
    hydra.Log.getLogWriter().info( "Alive and ready for work" );
  }

  //////////////////////////////////////////////////////////////////////////////
  //    RUNTIME
  //////////////////////////////////////////////////////////////////////////////

 /** 
  *  Called remotely by the master controller to cause the client to execute 
  *  the test task.  Spawns a new thread to execute the task so that RMI can
  *  return immediately and the task can execute asynchyronously.
  *  @param tsid the id of the scheduler who issued the assignment
  *  @param type the type of task to execute
  *  @param index the index into the tasks of that type
  */ 
  public void executeTask( int tsid, int type, int index )
  throws RemoteException {
    rmiMod.set( this );
    if ( busy ) {
      throw new HydraInternalException("!!!! Already busy !!!!" + myTid );
    }
    busy = true;
    currentTask = TestConfig.getInstance().getTask( type, index );
    executeTask( tsid, currentTask );
  }

  /**
   *  Spawns a new thread to execute the task.
   */
  protected synchronized void executeTask(final int tsid, final TestTask task) {
    HydraThread myThread = new HydraThread( this, this.myThreadName ) {
        public void run() {

          log.info( "Received task: " + task.toShortString() );

          while (RemoteTestModule.getWaitFlag() == true)
            MasterController.sleepForMs(200);

          // execute task
          TestTaskResult result = task.execute();

          // print result in client log
          ResultLogger.logTaskResult( task, result );

          // report result to master controller
          try {
            busy = false;
            Master.reportResult( tsid, MyVmid, myTid, result );
          } catch( RemoteException e ) { 
            handleRemoteException( "Master.reportResult", e, false );
          } finally {
            DistributedSystem.releaseThreadsSockets(); // see Bug 37708
          }
        }
    };
    myThread.start();
  }

 /** 
  * Called remotely by the master controller to cause the client to execute 
  * the instance method on the object.  Does this synchronously (does not spawn
  * a thread).  This method is used by the unit test framework, dunit.
  *
  * @param obj the object to execute the method on
  * @param methodName the name of the method to execute
  * @return the result of method execution
  */ 
  public MethExecutorResult executeMethodOnObject( Object obj, String methodName ) {
    String name = obj.getClass().getName() + "." + methodName + 
      " : " + obj;
    log.info("Received method: " + name);
    long start = System.currentTimeMillis();
    MethExecutorResult result = MethExecutor.executeObject( obj, methodName );
    long delta = System.currentTimeMillis() - start;
    if (result.getResult() == null) {
      log.info("returning from " + name + " (took " + delta + " ms)");
    } else {
      log.info( "Got result: " + result.toString().trim()  + " from " +
              name + " (took " + delta + " ms)");
    }
    return result;
  }

  /**
   * Executes a given instance method on a given object with the given
   * arguments. 
   */
  public MethExecutorResult executeMethodOnObject(Object obj,
                                                  String methodName,
                                                  Object[] args) {
    String name = obj.getClass().getName() + "." + methodName + 
             (args != null ? " with " + args.length + " args": "") +
      " : " + obj;
    log.info("Received method: " + name);
    long start = System.currentTimeMillis();
    MethExecutorResult result = 
      MethExecutor.executeObject(obj, methodName, args);
    long delta = System.currentTimeMillis() - start;
    if (result.getResult() == null) {
      log.info("returning from " + name + " (took " + delta + " ms)");
    } else {
      log.info( "Got result: " + result.toString().trim()  + " from " +
              name + " (took " + delta + " ms)");
    }
    return result;
  }

 /** 
  * Called remotely by the master controller to cause the client to execute 
  * the method on the class.  Does this synchronously (does not spawn a thread).
  * This method is used by the unit test framework, dunit.
  *
  * @param className the name of the class execute
  * @param methodName the name of the method to execute
  * @return the result of method execution
  */ 
  public MethExecutorResult executeMethodOnClass( String className, String methodName ) {
    String name = className + "." + methodName;
    log.info("Received method: " +  name);
    long start = System.currentTimeMillis();
    MethExecutorResult result = MethExecutor.execute( className, methodName );
    long delta = System.currentTimeMillis() - start;
    if (result.getResult() == null) {
      log.info("returning from " + name + " (took " + delta + " ms)");
    } else {
      log.info( "Got result: " + result.toString().trim()  + " from " +
              name + " (took " + delta + " ms)");
    }
    return result;
  }

  /**
   * Executes a given static method in a given class with the given
   * arguments. 
   */
  public MethExecutorResult executeMethodOnClass(String className,
                                                 String methodName,
                                                 Object[] args) {
    String name = className + "." + methodName + 
      (args != null ? " with " + args.length + " args": "");
    log.info("Received method: " + name);
    long start = System.currentTimeMillis();
    MethExecutorResult result = 
      MethExecutor.execute(className, methodName, args);
    long delta = System.currentTimeMillis() - start;
    if (result.getResult() == null) {
      log.info("returning from " + name + " (took " + delta + " ms)");
    } else {
      log.info( "Got result: " + result.toString().trim()  + " from " +
              name + " (took " + delta + " ms)");
    }
    return result;
  }

  /**
   * Each test task is executed in a HydraThread spawned by a 
   * RemoteTestModule instance (client).  This method, when invoked from
   * within some executing test task, returns that RemoteTestModule. 
   */
  public static RemoteTestModule getCurrentThread() {
    Thread currThread = Thread.currentThread();
    if ( currThread instanceof HydraThread ) {
      return ( (HydraThread) currThread).getRemoteMod();

    } else if ( rmiMod.get() != null ) { // rmi thread in client
      return (RemoteTestModule) rmiMod.get();

    } else { // no idea, could be master or anonymous client thread
      return null;
    }
  }

 /**
  *  Notifies a client that dynamic action with the specified id is complete.
  */
  public void notifyDynamicActionComplete( int actionId ) {
    DynamicActionUtil.notifyDynamicActionComplete( actionId );
  }

  /** A flag that can be set to tell us to pause before proceeding */
  private static boolean waitFlag = false;

  public static synchronized boolean getWaitFlag() {
    return waitFlag;
  }

  public static synchronized void setWaitFlag(boolean val) {
    waitFlag = val;
  }

  //////////////////////////////////////////////////////////////////////////////
  //    SHUTDOWN
  //////////////////////////////////////////////////////////////////////////////

  /**
   * Used by the master to tell this VM to disconnect.
   */
  public synchronized void disconnectVM() throws RemoteException {
    rmiMod.set(this);
    log.info(Thread.currentThread().getName() +
             " disconnecting vm_" + MyVmid + " at master's request.");
    Thread myThread = new HydraThread(this, this.myThreadName) {
      public void run() {
        // in case connection was obtained through hydra helper class...
        DistributedSystem ds = InternalDistributedSystem.getAnyInstance();
        if (ds != null) {
          log.info("Disconnecting from the distributed system: " + ds);
          ds.disconnect();
        }
	// notify the master that the disconnect is complete
        try {
          synchronized (Master) {
            Master.registerClientDisconnect(MyVmid);
          }
        } catch (RemoteException e) {
          handleRemoteException("Master.registerClientDisconnect", e);
        }
      }
    };
    myThread.start();
  }

  /**
   * Used by the master to tell this VM to quit.
   * @param disconnect whether to disconnect the VM from the distributed system
   *                   before shutting down.
   * @param runShutdownHook whether to run the client shutdown hook
   */
  public synchronized void shutDownVM( final boolean disconnect,
                                       final boolean runShutdownHook )
  throws RemoteException {
    rmiMod.set( this );
    if ( disconnect ) {
      log.info( Thread.currentThread().getName() +
               " disconnecting and shutting down vm_" + MyVmid +
               " at master's request." );
    } else {
      log.info( Thread.currentThread().getName() +
               " shutting down vm_" + MyVmid +
               " at master's request." );
    }
    Thread myThread = new HydraThread( this, this.myThreadName ) {
      public void run() {
        log.info( "Now terminating process." );

        if (runShutdownHook) {
          runShutdownHook();
        }
        if ( disconnect ) {
          log.info("Disconnecting from the distributed system, if connected");

          // in case connection was obtained through hydra helper class...
          DistributedSystem ds =
              InternalDistributedSystem.getAnyInstance();
          if ( ds != null ) {
            ds.disconnect();
          }
        }
        shutdown();
      }
    };
    myThread.start();
  }
  protected static ThreadLocal rmiMod = new ThreadLocal();
  protected void invokeShutdownHook(Long key, Vector shutdownHook) {
    if (shutdownHook.size() % 2 != 0) {
      String s = BasePrms.nameForKey(key)
               + " must be a list of class/method pairs";
      throw new HydraConfigException(s);
    }
    for (int i = 0; i < shutdownHook.size(); i+=2) {
      String cls = (String)shutdownHook.get(i);
      String mth = (String)shutdownHook.get(i+1);
      String hook = cls + "." + mth;
      log.info("Invoking client shutdown hook: " + hook);
      MethExecutorResult result = MethExecutor.execute(cls, mth);
      Throwable t = result.getException();
      if (t != null){
        String s = "Error invoking " + BasePrms.nameForKey(key) + "=" + hook;
        ResultLogger.reportErr(s, t);
      }
      log.info("Invoked client shutdown hook: " + hook);
    }
  }

  public void runShutdownHook( ) {
    Long shutdownHookKey = Prms.clientShutdownHook;
    Vector shutdownHook = tab().vecAt(shutdownHookKey, null);
    if (shutdownHook != null) {
      log.info("Invoking client shutdown hooks: " + shutdownHook);
      invokeShutdownHook(shutdownHookKey, shutdownHook);
      log.info("Invoked client shutdown hooks: " + shutdownHook);
    }
  }
  
  protected void shutdown() {
    log.info( "Shutting down from RemoteTestModule" );
    // sleep if asked
    int sleepTime = tab().intAt( Prms.finalClientSleepSec );
    if (sleepTime > 0) {
       log.info("Sleeping for " + sleepTime + " seconds.");
       MasterController.sleepForMs(sleepTime * 1000);
    }
    System.exit(0);
  }

  //////////////////////////////////////////////////////////////////////////////
  //    ACCESSORS
  //////////////////////////////////////////////////////////////////////////////

  public static List<List<String>> getClientMapping() {
    return ClientMapping;
  }
  public static String getMyHost() {
    return MyHost;
  }
  public static String getMyLogicalHost() {
    return MyLogicalHost;
  }
  public static int getMyPid() {
    return MyPid;
  }
  public static String getMyClientName() {
    return MyClientName;
  }
  public static int getMyVmid() {
    return MyVmid;
  }
  public int getThreadId() {
    return myTid;
  }
  public static int getMyBaseThreadId() {
    return MyBaseThreadId;
  }
  public static int getMyNumThreads() {
    return MyNumThreads;
  }
  public int getThreadGroupId() {
    return myThreadGroupId;
  }
  public String getThreadGroupName() {
    return myThreadGroupName;
  }
  public TestTask getCurrentTask() {
    return currentTask;
  }
  protected static ConfigHashtable tab() {
    return TestConfig.getInstance().getParameters();
  }
  public static long getClockSkew() {
    if (MyTimeClient != null) {
      return MyTimeClient.getClockSkew();
    } else {
      return 0;
    }
  }

  public static void openClockSkewStatistics() {
    if (MyTimeClient != null) {
      MyTimeClient.openStatistics();
    }
  }
  public static void closeClockSkewStatistics() {
    if (MyTimeClient != null) {
      MyTimeClient.closeStatistics();
    }
  }


  //////////////////////////////////////////////////////////////////////////////
  //    ERROR HANDLING
  //////////////////////////////////////////////////////////////////////////////

  /**
   *  Invokes RemoteException handler with the rethrow flag set to true.
   */
  protected static void handleRemoteException( String msg, RemoteException e ) {
    handleRemoteException( msg, e, true );
  }

  /**
   *  Reports meaningful RemoteExceptions to the test error file for easier
   *  viewing, and does away with bogus RemoteExceptions, for example when
   *  clients are left running after a hang and master goes away.  Rethrows
   *  as HydraRuntimeException to allow the acting thread to detect error
   *  without grokking RemoteException.
   */
  protected static void handleRemoteException( String msg, RemoteException e,
                                               boolean rethrow ) {
    String s = Thread.currentThread().getName()
             + " failed to connect at " + msg;
    try {
      if ( ProcessMgr.processExists( MasterHost, MasterPid )
      || ! ResultLogger.hasHangFile() ) {

        // treat error as real
        ResultLogger.reportAsErr( s, e );
        if ( rethrow ) {
          throw new HydraRuntimeException( s, e );
        }

      } else { // ignore since test is hung and master left the building
        Log.getLogWriter().info("safely ignoring " + e + "\n"
                               + TestHelper.getStackTrace(e));
      }

    } 
    catch (VirtualMachineError e2) {
      SystemFailure.initiateFailure(e2);
      throw e2;
    }
    catch( Throwable t ) { // unable to handle
      s = s + " (attempt to handle encountered " + t + ")";
      ResultLogger.reportAsErr( s, e );
      throw new HydraRuntimeException( s, e );
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  //    MAIN
  //////////////////////////////////////////////////////////////////////////////

  private static void setClientMapping() {
    try {
      ClientMapping = Master.getClientMapping();
    } catch (RemoteException e) {
      handleRemoteException("Master.setClientMapping", e);
    }
  }

  /**
   *  The startup routine for this remote test client.  Mainly does 
   *  registration and log initialization stuff.  Some info will be 
   *  shared among all thread clients in this process; other info
   *  will be stored away on a per thread (per RemoteTestModule 
   *  instance) basis. 
   */
  protected static void initialize() {

    // note the host and pid for this vm
    MyHost = HostHelper.getLocalHost();
    MyCanonicalHost = HostHelper.getCanonicalHostName();
    MyPid = ProcessMgr.getProcessId();

    // get the master canonical host and process id
    MasterHost = System.getProperty( Prms.MASTER_HOST_PROPERTY );
    MasterPid = Integer.getInteger( Prms.MASTER_PID_PROPERTY ).intValue();

    // get the logical client name
    MyClientName = System.getProperty( ClientPrms.CLIENT_NAME_PROPERTY );

    // get the logical host name
    MyLogicalHost = System.getProperty( HostPrms.HOST_NAME_PROPERTY );

    // note the id for this vm
    MyVmid = Integer.getInteger( "vmid" ).intValue();

    // set the main thread name
    Thread.currentThread().setName(
      MasterController.getNameFor( MyVmid, -1, MyClientName, MyHost, MyPid ) );

    // create the header log (hitch to the bgexec file)
    log = Log.createLogWriter( "client", "all" );

    // log info for this vm
    log.info( ProcessMgr.processAndBuildInfoString() );

    // do RMI lookup of Master
    if ( Master == null )
      Master = RmiRegistryHelper.lookupMaster();

    // read the hydra configuration file 
    TestConfig tc = TestConfig.getInstance();
    log.info( "Random seed = " + tc.getRandomSeed() );

    // get the client mapping
    setClientMapping();

    // set up the gemfire name system property for getting default connections
    GemFireDescription gfd =
        tc.getClientDescription( MyClientName ).getGemFireDescription();
    if ( gfd != null )
      System.setProperty( GemFirePrms.GEMFIRE_NAME_PROPERTY, gfd.getName() );

    // load classes used for configuration parameters
    log.info( "Loading test parameter classes..." );
    List<String> classnames = tc.getClassNames();
    String classpath = System.getProperty("java.class.path");
    boolean gemfirexdTest = false;
    try {
      Class c = Class.forName("com.pivotal.gemfirexd.internal.iapi.services.info.ProductVersionHolder");
      gemfirexdTest = true;
    } catch (ClassNotFoundException e) {
    }
    for (String classname : classnames) {
      if (classname.startsWith("hydra.gemfirexd.") && !gemfirexdTest) {
        continue;
      }
      try {
        Class cls = Class.forName( classname );
      } catch( ClassNotFoundException e ) {
        String s = "Parameter class " + classname + " not found";
        throw new HydraConfigException( s, e );
      }
    }

    // sleep if asked to allow someone to attach a debugger
    int sleepTime = tab().intAt( Prms.initialClientSleepSec );
    if (sleepTime > 0) {
       log.info("Sleeping for " + sleepTime + " seconds.");
       MasterController.sleepForMs(sleepTime * 1000);
    }

    // note the starting thread id for clients in this vm
    MyBaseThreadId = Integer.getInteger( "baseThreadId" ).intValue();

    // get the initial number of threads in this vm
    MyNumThreads = Integer.getInteger( "numThreads" ).intValue();

    // create the task log
    String purpose = System.getProperty( "purpose", "" );
    if ( ! purpose.equals( "" ) ) purpose = "_" + purpose;
    MyLogFileName = Thread.currentThread().getName() + purpose;
    log.info( "Switching to task log for the remainder of the run: "
             + MyLogFileName );
    Log.closeLogWriter();
    log = Log.createLogWriter( "clienttaskloop",
                               MyLogFileName,
                               tab().booleanAt( LogPrms.file_logging ),
                               tab().stringAt(  LogPrms.file_logLevel ),
                               tab().intAt(     LogPrms.file_maxKBPerVM ) );

    // re-log info for this vm
    log.info( ProcessMgr.processAndBuildInfoString() );

    // start the time client for this VM
    if (TimeServerPrms.clockSkewUpdateFrequencyMs() > 0) {
      int clockSkewUpdateFrequencyMs = TimeServerPrms.clockSkewUpdateFrequencyMs();
      int clockSkewMaxLatencyMs =TimeServerPrms.clockSkewMaxLatencyMs();
      int clockSkewSamplesToAverage = TimeServerPrms.clockSkewSamplesToAverage();
      try {
        MyTimeClient = new TimeClient(
            tc.getMasterDescription().getTimeServerHost(),
            tc.getMasterDescription().getTimeServerPort(),
            clockSkewUpdateFrequencyMs, 
            clockSkewMaxLatencyMs, 
            clockSkewSamplesToAverage);
        log.info("Starting the time client: " + MyTimeClient);
      } catch (IOException e) {
        throw new HydraRuntimeException("Unable to start the time client", e);
      }
      MyTimeClient.start();
      try {
        MyTimeClient.waitForInitialization();
      } catch (InterruptedException e) {
        log.error("Interrupted initializing time client");
      }
      log.info("Started time client: " + MyTimeClient + ". Initial clock skew " + MyTimeClient.getClockSkew());
    }
  }
  
  public static void main( String[] args ) {
    initialize();
    makeAndStartThreadClients();
  }
}
