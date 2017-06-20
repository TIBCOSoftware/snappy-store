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

import com.gemstone.gemfire.LogWriter;

import java.io.*;
import java.rmi.*;
import java.rmi.registry.*;
import java.util.*;

/**
 * Helps create and access RMI registries.  Includes helper methods for use
 * by clients to access the master registry and the master proxy which is
 * bound there.
 */
public class RmiRegistryHelper {

  /** Seconds to wait for a new RMI registry to answer before timing out. */
  private static final int START_TIMEOUT_SEC = 120;

  //////////////////////////////////////////////////////////////////////////////
  ////    RMI REGISTRY OPERATIONS                                           ////
  //////////////////////////////////////////////////////////////////////////////

  /**
   * Starts an in-process RMI registry using the given name and port.
   * Waits until it is answering before returning.
   * @return the registry.
   * @throws HydraRuntimeException if an exception occurs.
   * @throws HydraTimeoutException if the registry fails to answer after
   *                               within {@link #START_TIMEOUT_SEC}.
   */
  public static Registry startRegistry(String name, int port) {
    String host = HostHelper.getLocalHost();
    String url = "rmi://" + host + ":" + port + "/";
    Log.getLogWriter().info("Starting RMI registry " + url);
    Registry registry = null;
    try {
      registry = LocateRegistry.createRegistry(port);
    } catch (RemoteException e) {
      String s = "Failed to start RMI registry " + url;
      throw new HydraRuntimeException(s, e);
    }
    // give it a couple of minutes to fully wake up
    Log.getLogWriter().info("Waiting " + START_TIMEOUT_SEC
       + " seconds for RMI registry " + url + " to respond");
    long timeout = System.currentTimeMillis() + START_TIMEOUT_SEC * 1000;
    do {
      if (isAnswering(host, port)) {
        Log.getLogWriter().info("Started RMI registry " + url);
        return registry;
      }
      MasterController.sleepForMs(1000);
    } while (System.currentTimeMillis() < timeout);
    String s = "Failed to start RMI registry " + name;
    throw new HydraTimeoutException(s);
  }

  /**
   *  Makes sure we can connect to the registry by looking for an
   *  arbitrary unbound object and watching for ConnectException.
   */
  private static boolean isAnswering(String host, int port) {
    try {
      Registry registry = LocateRegistry.getRegistry(host, port);
      if (registry == null) {
        String s = "Unable to locate RMI registry at " + host + ":" + port;
        throw new HydraRuntimeException(s);
      }
      registry.lookup( "test" );
      return true;
    } catch( NotBoundException e ) {
      Log.getLogWriter().info("Connected to RMI registry " + host + ":" + port);
      return true;
    } catch( ConnectException e ) {
      Log.getLogWriter().info("Waiting to connect to RMI registry " + host + ":" + port);
      return false;
    } catch( RemoteException e ) {
      throw new HydraRuntimeException("Cannot connect to RMI registry " + host + ":" + port, e);
    }
  }

  /**
   *  Binds a remote object into the given RMI registry using the
   *  specified key.  The registry must be local to the caller.
   *
   * @throws HydraRuntimeException
   *         Another object is bound to that name or if some other
   *         {@link RemoteException} occurs.
   */
  public static void bind(Registry registry, String key, Remote val) {
    try {
      registry.bind( key, val );
    } catch (AlreadyBoundException e) {
      String s = key + " already bound in RMI registry: " + registry;
      throw new HydraRuntimeException(s, e);
    } catch (RemoteException e) {
      String s = "Could not bind " + key + " in RMI registry: " + registry;
      throw new HydraRuntimeException(s, e);
    }
  }

  /**
   *  Looks up a remote object by url.
   */
  public static Remote lookup( String url ) {
    if ( Log.getLogWriter().finerEnabled() ) {
      Log.getLogWriter().finer("Looking up " + url);
    }
    final int MAX_RETRY_MS = 20000;
    long retryStartTime = 0;
    long attemptCount = 0;
    while (true) {
      try {
        attemptCount++;
        Remote obj = Naming.lookup(url);
        if (Log.getLogWriter().finerEnabled()) {
          Log.getLogWriter().finer("Lookup succeeded, got " + obj);
        }
        return obj;
      } catch (NotBoundException e) {
        return null;
      } catch (java.rmi.UnmarshalException e) {  // workaround for bug 52149 and 52150
        String failMessage = RmiRegistryHelper.detectKnownRMIBugs(e);
        if (failMessage != null) {
          Log.getLogWriter().info("Attempt to lookup " + url + " failed on attempt " + attemptCount +
             " with cause " + failMessage);
          if (retryStartTime == 0) {
            retryStartTime = System.currentTimeMillis();
          } else if (System.currentTimeMillis() - retryStartTime > MAX_RETRY_MS) {
            throw new HydraRuntimeException("Problem looking up " + url + " after " + attemptCount + " attempts", e);
          }
          MasterController.sleepForMs(100); // sleep so this is not a hot loop
        } else {
          throw new HydraRuntimeException("Problem looking up " + url, e);
        }
      } catch (Exception e) {
        throw new HydraRuntimeException("Problem looking up " + url, e);
      }
    }
  }

  /** Detect known RMI bugs, notably 52149 or 52150.
   *
   * @param unmarshalEx An UnmarshalException which was thrown by rmi.
   * @return String The failure message for the known RMI bug, or null if no
   *         bug was detected.
   */
  protected static String detectKnownRMIBugs(UnmarshalException unmarshalEx) {
    String failMessage = unmarshalEx.getMessage();
    if ((failMessage != null) && (failMessage.contains("Transport return code invalid"))) { // detected 52150
      return failMessage;
    } else { // look for 52149
      Throwable causedBy = unmarshalEx.getCause();
      if (causedBy instanceof java.io.InvalidClassException)  {
        failMessage = causedBy.getMessage();
        if ((failMessage != null) && (failMessage.contains("Not a proxy"))) { // detected 52149
          return failMessage;
        }
      }
    }
    return null;
  }
  
  /**
   * Returns the master RMI registry.
   */
  private static Registry getMasterRegistry() {
    String host = System.getProperty(MasterController.RMI_HOST_PROPERTY);
    int port = Integer.getInteger(MasterController.RMI_PORT_PROPERTY);

    Registry registry = null;
    try {
      registry = LocateRegistry.getRegistry(host, port);
    } catch( RemoteException e ) {
      String s = "Unable to locate master RMI registry at " + host + ":" + port;
      throw new HydraRuntimeException(s, e);
    }
    if (registry == null) {
      String s = "Unable to locate master RMI registry at " + host + ":" + port;
      throw new HydraRuntimeException(s);
    }
    Log.getLogWriter().info("Got master registry " + registry);
    return registry;
  }

  /**
   * Returns the URL for the master RMI registry.
   */
  public static String getMasterRegistryURL() {
    String host = System.getProperty(MasterController.RMI_HOST_PROPERTY);
    int port = Integer.getInteger(MasterController.RMI_PORT_PROPERTY);
    return "rmi://" + host + ":" + port + "/";
  }

  /**
   * Looks up the master proxy in the master RMI registry.
   */
  public static MasterProxyIF lookupMaster() {
    String url = getMasterRegistryURL() + MasterController.RMI_NAME;
    return (MasterProxyIF)lookup(url);
  }

  /**
   * Binds a remote object into the master RMI registry using the
   * specified key.  The registry must be local to the caller.
   *
   * @throws HydraRuntimeException
   *         Another object is bound to that name or if some other
   *         {@link RemoteException} occurs.
   */
  public static void bindInMaster(String key, Remote val) {
    Registry registry = getMasterRegistry();
    bind(registry, key, val);
  }

  /**
   * Looks up the specified object in the master RMI registry.
   */
  public static Remote lookupInMaster(String name) {
    String url = getMasterRegistryURL() + name;
    return lookup(url);
  }
}
