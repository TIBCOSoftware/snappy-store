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
package com.pivotal.gemfirexd.internal.engine.locks.impl;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.distributed.internal.deadlock.Dependency;
import com.gemstone.gemfire.distributed.internal.deadlock.DependencyMonitor;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLocalLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLocalLockService.DistributedLockOwner;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockable;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdReadWriteLock;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;

/**
 * Discovery any locks held or threads waiting on gemfirexd read write
 * locks.
 * 
 * @author dsmith
 *
 */
public class GfxdRWLockDependencyMonitor implements DependencyMonitor {


  public static final DependencyMonitor INSTANCE = new GfxdRWLockDependencyMonitor();

  public Set<Dependency<Thread, Serializable>> getBlockedThreads(
      Thread[] allThreads) {
    Set<Dependency<Thread, Serializable>> results = new HashSet<Dependency<Thread, Serializable>>();
    GemFireStore memStore = GemFireStore.getBootedInstance();
    if(memStore == null) {
      return results;
    }
    GfxdDRWLockService ddlService = memStore.getDDLLockService();
    if(ddlService == null) {
      return results;
    }
    GfxdLocalLockService localService = ddlService.getLocalLockService();
    
    //Get the list of threads in the local VM that are waiting for read for write
    //locks.
    
    //Note, we may actually be waiting for one of these write locks in a remote VM.
    //However, that remote VMs thread will have a thread parked in this local VM
    //the write lock, and we are already tracking the dependency between that remote
    //thread and the local thread with the message dependency monitor.
    synchronized(localService) {
      for(GfxdReadWriteLock lock : localService.values()) {
        Collection<Thread> blockedThreads = lock.getBlockedThreadsForDebugging();
        for(Thread blocked : blockedThreads) {
          if(lock.getLockName() instanceof Serializable) {
            results.add(new Dependency(blocked, new LockId((Serializable) lock
                .getLockName())));
          }
        }
      }
    }
    
    return results;
  }

  public Set<Dependency<Serializable, Thread>> getHeldResources(
      Thread[] allThreads) {
    final Set<Dependency<Serializable, Thread>> results = new HashSet<>();
    GemFireStore memStore = GemFireStore.getBootedInstance();
    if(memStore == null) {
      return results;
    }
    GfxdDRWLockService ddlService = memStore.getDDLLockService();
    if(ddlService == null) {
      return results;
    }
    GfxdLocalLockService localService = ddlService.getLocalLockService();
    
    //TODO - this code gets write lock holders by inspecting the locks themselves
    //Some of the gfxd code sets a lock owner, and other parts of the code don't
    //set an owner. That code is currently not instrumented here.

    synchronized(localService) {
      for(GfxdReadWriteLock lock : localService.values()) {
        Object owner = lock.getWriteLockOwner();
        Object name = lock.getLockName();
        if(name instanceof Serializable) {
          //Record a dependency if the owner is a thread object.
          if(owner instanceof Thread) {
            results.add(new Dependency<Serializable, Thread>(new LockId((Serializable) name), 
                (Thread) owner));
          }
          
          //Find the owning thread for locks using a DistributedLockOwner object
          if(owner instanceof DistributedLockOwner) {
            DistributedLockOwner dOwner = (DistributedLockOwner) owner;

            //Only record write locks that are held by the local VM
            if(dOwner.getOwnerMember().equals(GemFireStore.getMyId())) {

              //TODO there should be a better way to find the owning thread
              //than this, but this works.
              for(Thread thread: allThreads) {
                if(thread.getId() == dOwner.getOwnerThreadId()) {
                  results.add(new Dependency<Serializable, Thread>(new LockId((Serializable) lock
                      .getLockName()), thread));
                  break;
                }
              }
            }
          }
        }
      }
    }

    //Get the list of read locks that are held in this local VM
    //This code was stolen from GfxdLockService.dumpReadLocks.
    final GemFireXDUtils.Visitor<LanguageConnectionContext> gatherDeps =
        new GemFireXDUtils.Visitor<LanguageConnectionContext>() {
          @Override
          public boolean visit(LanguageConnectionContext lcc) {
            final TransactionController tc = lcc.getTransactionExecute();
            final GfxdLockSet lockSet;
            if (tc != null &&
                (lockSet = ((GemFireTransaction)tc).getLockSpace()) != null) {
              Thread thread = lcc.getContextManager().getActiveThread();
              if (thread != null) {
                Collection<GfxdLockable> readLocks =
                    lockSet.getReadLocksForDebugging();

                for (GfxdLockable lock : readLocks) {
                  if (lock.getName() instanceof Serializable) {
                    results.add(new Dependency<>(
                        (Serializable)lock.getName(), thread));
                  }
                }
              }
            }
            return true;
          }
        };
    GemFireXDUtils.forAllContexts(gatherDeps);

    return results;
  }

  /**
   * Private constructor for a singleton.
   */
  private GfxdRWLockDependencyMonitor() {
    
  }
  
  private static class LockId implements Serializable {
    private final Serializable tokenName;
    
    public LockId(Serializable tokenName) {
      this.tokenName = tokenName;
    }

    

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result
          + ((tokenName == null) ? 0 : tokenName.hashCode());
      return result;
    }



    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      LockId other = (LockId) obj;
      if (tokenName == null) {
        if (other.tokenName != null)
          return false;
      } else if (!tokenName.equals(other.tokenName))
        return false;
      return true;
    }



    @Override
    public String toString() {
      return "GFXDRWLock(" + tokenName + ")";
    }
  }
}
