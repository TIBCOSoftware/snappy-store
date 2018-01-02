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

package hydra.blackboard;

import hydra.HydraInternalException;
import hydra.HydraRuntimeException;
import hydra.Log;
import hydra.RemoteTestModule;

import com.gemstone.gemfire.LogWriter;

import java.rmi.*;

/**
 * Facade over a transport-specific implementation of shared lock
 * ({@link RmiSharedLockImpl}).
 */

public class AnySharedLockImpl implements SharedLock {

  private String    name;
  private int       type = -1;
  private LogWriter log;

  private RmiSharedLock rmilock;

  /**
   * Create a facade with the specified name and transport type.
   * @param name the name of the shared lock.
   * @param type the transport type of the shared lock.
   */
  public AnySharedLockImpl(String name, int type) {
    this.name = name;
    this.type = type;
    this.log  = Log.getLogWriter();
  }
  protected void setRmiLock(RmiSharedLock rmil) {
    this.rmilock = rmil;
  }
  /**
   * Binds the shared lock in the location appropriate to the transport type
   * (rmiregistry for RMI).
   */
  public static SharedLock bind(String name, int type) {
    switch (type) {
      case Blackboard.RMI:
        try {
          RmiSharedLock rmil = new RmiSharedLockImpl();
          RmiSharedLockImpl.bind(name, rmil);
          AnySharedLockImpl ali = new AnySharedLockImpl(name, type);
          ali.setRmiLock(rmil);
          return ali;
        } catch (RemoteException e) {
          String s = "Unable to bind RMI lock: " + name;
          throw new HydraRuntimeException(s, e);
        }
      default:
        String s = "Illegal transport type: " + type;
        throw new HydraInternalException(s);
    }
  }
  /**
   * Looks up the shared lock in the location appropriate to the transport type
   * (rmiregistry for RMI).
   * @return the shared lock or <code>null</code> if not there.
   */
  public static SharedLock lookup(String name, int type) {
    switch (type) {
      case Blackboard.RMI:
        RmiSharedLock rmil = null;
        try {
          rmil = RmiSharedLockImpl.lookup(name);
        } catch (RemoteException e) {
          String s = "Unable to look up RMI lock: " + name;
          throw new HydraRuntimeException(s, e);
        }
        if (rmil == null)
          return null;
        else {
          AnySharedLockImpl ali = new AnySharedLockImpl(name, type);
          ali.setRmiLock(rmil);
          return ali;
        }
      default:
        String s = "Illegal transport type: " + type;
        throw new HydraInternalException(s);
    }
  }

//------------------------------------------------------------------------------
// SharedLock interface
//------------------------------------------------------------------------------

  /**
   * Implements {@link SharedLock#lock()}.
   */
  public void lock() {
    switch (this.type) {
      case Blackboard.RMI:
        try {
          String threadName = Thread.currentThread().getName();
          int vmid = RemoteTestModule.getMyVmid();
          RemoteTestModule mod = RemoteTestModule.getCurrentThread();
          long tid = (mod == null) ? Thread.currentThread().getId()
                                   : (long)mod.getThreadId();
          this.rmilock.lock(threadName, vmid, tid);
        }
        catch (RemoteException e) {
          String s = "Unable to access RMI lock: " + this.name;
          throw new HydraRuntimeException(s, e);
        }
        break;
      default:
        String s = "Illegal transport type: " + this.type;
        throw new HydraInternalException(s);
    }
  }

  /**
   * Implements {@link SharedLock#unlock()}.
   */
  public void unlock() {
    switch (this.type) {
      case Blackboard.RMI:
        try {
          String threadName = Thread.currentThread().getName();
          int vmid = RemoteTestModule.getMyVmid();
          RemoteTestModule mod = RemoteTestModule.getCurrentThread();
          long tid = (mod == null) ? Thread.currentThread().getId()
                                   : (long)mod.getThreadId();
          this.rmilock.unlock(threadName, vmid, tid);
        }
        catch (RemoteException e) {
          String s = "Unable to access RMI lock: " + this.name;
          throw new HydraRuntimeException(s, e);
        }
        break;
      default:
        String s = "Illegal transport type: " + this.type;
        throw new HydraInternalException(s);
    }
  }
  
  /**
   * Implements {@link SharedLock#getCondition(String)}.
   */
  public SharedCondition getCondition(String name) {
    switch (this.type) {
    case Blackboard.RMI:
      try {
        return new RmiSharedConditionWrapper(name, this.rmilock.getCondition(name));
      } catch (RemoteException e) {
        String s = "Unable to access RMI lock: " + this.name;
        throw new HydraRuntimeException(s, e);
      }
    default:
      String s = "Illegal transport type: " + this.type;
      throw new HydraInternalException(s);
  }
  }
  
  private static class RmiSharedConditionWrapper implements SharedCondition {

    private RmiSharedCondition rmiSharedCondition;
    private String name;

    public RmiSharedConditionWrapper(String name, RmiSharedCondition condition) {
      this.name = name;
      this.rmiSharedCondition = condition;
    }

    public void await() throws InterruptedException {
      try {
        String threadName = Thread.currentThread().getName();
        int vmid = RemoteTestModule.getMyVmid();
        RemoteTestModule mod = RemoteTestModule.getCurrentThread();
        long tid = (mod == null) ? Thread.currentThread().getId()
                                 : (long)mod.getThreadId();
        rmiSharedCondition.await(threadName, vmid, tid);
      }
      catch (RemoteException e) {
        String s = "Unable to access RMI condition: " + this.name;
        throw new HydraRuntimeException(s, e);
      }
    }

    public void signal() {
      try {
        String threadName = Thread.currentThread().getName();
        int vmid = RemoteTestModule.getMyVmid();
        RemoteTestModule mod = RemoteTestModule.getCurrentThread();
        long tid = (mod == null) ? Thread.currentThread().getId()
                                 : (long)mod.getThreadId();
        rmiSharedCondition.signal(threadName, vmid, tid);
      }
      catch (RemoteException e) {
        String s = "Unable to access RMI condition: " + this.name;
        throw new HydraRuntimeException(s, e);
      }
    }

    public void signalAll() {
      try {
        String threadName = Thread.currentThread().getName();
        int vmid = RemoteTestModule.getMyVmid();
        RemoteTestModule mod = RemoteTestModule.getCurrentThread();
        long tid = (mod == null) ? Thread.currentThread().getId()
                                 : (long)mod.getThreadId();
        rmiSharedCondition.signalAll(threadName, vmid, tid);
      }
      catch (RemoteException e) {
        String s = "Unable to access RMI condition: " + this.name;
        throw new HydraRuntimeException(s, e);
      }
    }
    
  }
}
