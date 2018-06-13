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

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionFactory;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;

import com.gemstone.gemfire.cache30.CacheTestCase;
import com.gemstone.gemfire.distributed.LockNotHeldException;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.DistributionMessageObserver;
import com.gemstone.gemfire.internal.cache.CreateRegionProcessor.CreateRegionMessage;

import dunit.Host;
import dunit.SerializableRunnable;
import dunit.VM;

/**
 * 
 * @author asif
 * 
 */
@SuppressWarnings({ "deprecation", "rawtypes", "serial", "unchecked" })
public class Bug43522DUnit extends CacheTestCase {

  protected static final String REGION_NAME = "region";

  private static volatile boolean exceptionFound = false;

  /**
   * @param name
   */
  public Bug43522DUnit(String name) {
    super(name);
  }

  public void testBug1() throws Exception {
    // Create a replicated region , with accessor and one data store. Add 5
    // entries.
    // Now bring up a new data store, which does a GII from existing host.
    // Ensure that a 6th entry put
    // from accessor , reaches the new host , but does not get inserted, till
    // the GII is over
    // and the 6th entry reaches the new host via GII
    SerializableRunnable createRegionAcc = new SerializableRunnable(
        "Create non persistent empty region") {
      public void run() {
        getCache();
        RegionFactory rf = new RegionFactory();
        rf.setDataPolicy(DataPolicy.EMPTY);
        rf.setScope(Scope.DISTRIBUTED_ACK);
        rf.create(REGION_NAME);
      }
    };

    SerializableRunnable createRegionStore1 = new SerializableRunnable(
        "Create non persistent region") {
      public void run() {
        getCache();
        RegionFactory rf = new RegionFactory();
        rf.setDataPolicy(DataPolicy.REPLICATE);
        rf.setScope(Scope.DISTRIBUTED_ACK);
        rf.create(REGION_NAME);
      }
    };

    SerializableRunnable createRegionStore2 = new SerializableRunnable(
        "Create non persistent region") {
      public void run() {
        getCache();
        RegionFactory rf = new RegionFactory();
        rf.setDataPolicy(DataPolicy.REPLICATE);
        rf.setScope(Scope.DISTRIBUTED_ACK);
        LocalRegion lr = (LocalRegion)rf.create(REGION_NAME);
        ((AbstractRegionMap)lr.getRegionMap())
            .testOnlySetIndexUpdater(new IndexUpdater() {

              @Override
              public void unlockForIndexGII() throws LockNotHeldException {
              }

              @Override
              public void unlockForGII() throws LockNotHeldException {
              }

              @Override
              public void postEventCleanup(EntryEventImpl event) {
              }

              @Override
              public void postEvent(LocalRegion owner, EntryEventImpl event,
                  RegionEntry entry, boolean success) {
              }

              public void onEvent(LocalRegion owner, EntryEventImpl event,
                  RegionEntry entry) {
                // LogWriter logger = owner.getCache().getLogger();
                // logger.info("Invoked onEvent. Event ="+event);
                // throw new
                // EntryExistsException("The GfxdIndexManager.onEvent should not have been invoked","dummy");

                boolean throwEEE = false;

                Object oldVal = event.getOldValue();
                Object newVal = event.getNewValue();
                Long lastModifiedFromOrigin = (Long)event.getContextObject();
                if (lastModifiedFromOrigin == null
                    || lastModifiedFromOrigin.longValue() != entry
                        .getLastModified()) {
                  throwEEE = true;
                }
                else {
                  if ((oldVal instanceof byte[] && newVal instanceof byte[])
                      || (oldVal instanceof byte[][] && newVal instanceof byte[][])) {
                    if (oldVal instanceof byte[]) {
                      throwEEE = ((byte[])oldVal).length != ((byte[])newVal).length
                          || !Arrays.equals((byte[])oldVal, (byte[])newVal);
                    }
                    else {
                      if (((byte[][])oldVal).length == ((byte[][])newVal).length) {
                        int len = ((byte[][])oldVal).length;
                        for (int i = 0; i < len; ++i) {
                          throwEEE = !Arrays.equals(((byte[][])oldVal)[i],
                              ((byte[][])newVal)[i]);
                          if (throwEEE) {
                            break;
                          }
                        }
                      }
                      else {
                        throwEEE = true;
                      }
                    }

                  }
                  else {
                    throwEEE = true;
                  }
                }

                if (throwEEE) {
                  throw new EntryExistsException(event.getKey().toString(),
                      event.getOldValue());
                }

              }

              @Override
              public boolean needsRecovery() {
                return false;
              }

              @Override
              public boolean lockForIndexGII() throws TimeoutException {
                return false;
              }

              @Override
              public void lockForGII() throws TimeoutException {
              }

              @Override
              public boolean clearIndexes(LocalRegion region, DiskRegion dr,
                  boolean holdIndexLock, Iterator<?> bucketEntriesIter, int bucketId) {
                return false;
              }

              @Override
              public void releaseIndexLock(LocalRegion region) {
              }

              @Override
              public void onOverflowToDisk(RegionEntry entry) {
              }

              @Override
              public void onFaultInFromDisk(RegionEntry entry) {
              }

              @Override
              public boolean hasRemoteOperations(Operation op) {
                return false;
              }

              @Override
              public boolean avoidSerialExecutor(Operation op) {
                return false;
              }

              @Override
              public boolean handleSuspectEvents() {
                return false;
              }

              @Override
              public void unlockForGII(boolean forWrite)
                  throws LockNotHeldException {
                // TODO Auto-generated method stub
                
              }             
            });
      }
    };
    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);

    // insert five entries from accessor
    //Cache cache = getCache();

    // for (int i = 1; i < 6; i++) {
    // region.put(i, i);
    // }

    // Now set the Observer in another VM
    vm1.invoke(new SerializableRunnable("Add Message observer") {
      public void run() {
        disconnectFromDS();
        DistributionMessageObserver dmo = new DistributionMessageObserver() {
          @Override
          public void beforeProcessMessage(DistributionManager dm,
              DistributionMessage message) {
            if (message instanceof UpdateOperation.UpdateMessage) {
              UpdateOperation.UpdateMessage mssg = (UpdateOperation.UpdateMessage)message;

              LogWriter logger = getCache().getLogger();
              try {
                Cache cache = getGemfireCache();
                GemFireCacheImpl.setGFXDSystemForTests();
                LocalRegion lr = (LocalRegion)cache.getRegion(mssg.regionPath);
                AbstractRegionEntry entry = (AbstractRegionEntry)lr
                    .basicGetEntry(mssg.key);
                logger.info("last modified time of entry ="
                    + entry.getLastModified());
                logger.info("last modified time from message ="
                    + (mssg.lastModified - dm.getCacheTimeOffset()));
                assertEquals(entry.getLastModified(),
                    (mssg.lastModified - dm.getCacheTimeOffset()));
                logger.info("About to sleep");
                Thread.sleep(25000);
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
          }

          @Override
          public void afterProcessMessage(DistributionManager dm,
              DistributionMessage message) {

          }

          @Override
          public void beforeSendMessage(DistributionManager dm,
              DistributionMessage msg) {

          }
        };
        DistributionMessageObserver.setInstance(dmo);
        getCache();

      }
    });
    DistributionMessageObserver dmoClient = new DistributionMessageObserver() {
      @Override
      public void beforeProcessMessage(DistributionManager dm,
          DistributionMessage message) {
      }

      @Override
      public void afterProcessMessage(DistributionManager dm,
          DistributionMessage message) {
        if (message instanceof CreateRegionMessage) {
          // Profile has been updated, so now initiate a put from here

          Thread th = new Thread(new Runnable() {

            public void run() {
              Cache cache = getCache();
              final Region region = cache.getRegion(REGION_NAME);
              try {
                region.create(6, new byte[] { 1, -1, 122, 78, 65, -65, 71 });
              } catch (Exception e) {
                exceptionFound = true;
              }
            }
          });
          th.start();
          try {
            th.join();
          } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
        }

      }

      @Override
      public void beforeSendMessage(DistributionManager dm,
          DistributionMessage msg) {

      }
    };
    disconnectFromDS();
    DistributionMessageObserver.setInstance(dmoClient);

    // Thread th = new Thread(new Runnable() {
    // 
    // public void run() {
    // region.put(6, 6);
    // }
    // });
    // th.start();
    // Thread.sleep(4000);

    vm0.invoke(new SerializableRunnable("Add Message observer") {
      public void run() {
        disconnectFromDS();
        DistributionMessageObserver dmo = new DistributionMessageObserver() {
          @Override
          public void beforeProcessMessage(DistributionManager dm,
              DistributionMessage message) {
            if (message instanceof InitialImageOperation.RequestImageMessage) {
              try {
                Thread.sleep(10000);
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
            else if (message instanceof UpdateOperation.UpdateMessage) {
              GemFireCacheImpl.setGFXDSystemForTests();
            }
          }

          @Override
          public void afterProcessMessage(DistributionManager dm,
              DistributionMessage message) {

          }

          @Override
          public void beforeSendMessage(DistributionManager dm,
              DistributionMessage msg) {

          }
        };
        DistributionMessageObserver.setInstance(dmo);
        getCache();

      }
    });
    vm0.invoke(createRegionStore1);
    createRegionAcc.run();

    vm1.invoke(createRegionStore2);

    Thread.sleep(30000);
    assertFalse(exceptionFound);
    // th.join();

  }

  public void testBug2() throws Exception {
    // Create a replicated region , with accessor and one data store. Add 5
    // entries.
    // Now bring up a new data store, which does a GII from existing host.
    // Ensure that a 6th entry put
    // from accessor , reaches the new host , but does not get inserted, till
    // the GII is over
    // and the 6th entry reaches the new host via GII
    SerializableRunnable createRegionAcc = new SerializableRunnable(
        "Create non persistent empty region") {
      public void run() {
        getCache();
        RegionFactory rf = new RegionFactory();
        rf.setDataPolicy(DataPolicy.EMPTY);
        rf.setScope(Scope.DISTRIBUTED_ACK);
        rf.create(REGION_NAME);
      }
    };

    SerializableRunnable createRegionStore1 = new SerializableRunnable(
        "Create non persistent region") {
      public void run() {
        getCache();
        RegionFactory rf = new RegionFactory();
        rf.setDataPolicy(DataPolicy.REPLICATE);
        rf.setScope(Scope.DISTRIBUTED_ACK);
        rf.create(REGION_NAME);
      }
    };

    SerializableRunnable createRegionStore2 = new SerializableRunnable(
        "Create non persistent region") {
      public void run() {
        getCache();
        RegionFactory rf = new RegionFactory();
        rf.setDataPolicy(DataPolicy.REPLICATE);
        rf.setScope(Scope.DISTRIBUTED_ACK);
        LocalRegion lr = (LocalRegion)rf.create(REGION_NAME);
        ((AbstractRegionMap)lr.getRegionMap())
            .testOnlySetIndexUpdater(new IndexUpdater() {

              @Override
              public void unlockForIndexGII() throws LockNotHeldException {
              }

              @Override
              public void unlockForGII() throws LockNotHeldException {
              }

              @Override
              public void postEventCleanup(EntryEventImpl event) {
              }

              @Override
              public void postEvent(LocalRegion owner, EntryEventImpl event,
                  RegionEntry entry, boolean success) {
              }

              @Override
              public void onEvent(LocalRegion owner, EntryEventImpl event,
                  RegionEntry entry) {
                // LogWriter logger = owner.getCache().getLogger();
                // logger.info("Invoked onEvent. Event ="+event);
                // throw new
                // EntryExistsException("The GfxdIndexManager.onEvent should not have been invoked","dummy");

                boolean throwEEE = false;

                Object oldVal = event.getOldValue();
                Object newVal = event.getNewValue();
                Long lastModifiedFromOrigin = (Long)event.getContextObject();
                if (lastModifiedFromOrigin == null
                    || lastModifiedFromOrigin.longValue() != entry
                        .getLastModified()) {
                  throwEEE = true;
                }
                else {
                  if ((oldVal instanceof byte[] && newVal instanceof byte[])
                      || (oldVal instanceof byte[][] && newVal instanceof byte[][])) {
                    if (oldVal instanceof byte[]) {
                      throwEEE = ((byte[])oldVal).length != ((byte[])newVal).length
                          || !Arrays.equals((byte[])oldVal, (byte[])newVal);
                    }
                    else {
                      if (((byte[][])oldVal).length == ((byte[][])newVal).length) {
                        int len = ((byte[][])oldVal).length;
                        for (int i = 0; i < len; ++i) {
                          throwEEE = !Arrays.equals(((byte[][])oldVal)[i],
                              ((byte[][])newVal)[i]);
                          if (throwEEE) {
                            break;
                          }
                        }
                      }
                      else {
                        throwEEE = true;
                      }
                    }

                  }
                  else {
                    throwEEE = true;
                  }
                }

                if (throwEEE) {
                  throw new EntryExistsException(event.getKey().toString(),
                      event.getOldValue());
                }

              }

              @Override
              public boolean needsRecovery() {
                return false;
              }

              @Override
              public boolean lockForIndexGII() throws TimeoutException {
                return false;
              }

              @Override
              public void lockForGII() throws TimeoutException {
              }

              @Override
              public boolean clearIndexes(LocalRegion region, DiskRegion dr,
                  boolean holdIndexLock, Iterator<?> bucketEntriesIter, int bucketId) {
                return false;
              }

              @Override
              public void releaseIndexLock(LocalRegion region) {
              }

              @Override
              public void onOverflowToDisk(RegionEntry entry) {
              }

              @Override
              public void onFaultInFromDisk(RegionEntry entry) {
              }

              @Override
              public boolean hasRemoteOperations(Operation op) {
                return false;
              }

              @Override
              public boolean avoidSerialExecutor(Operation op) {
                return false;
              }

              @Override
              public boolean handleSuspectEvents() {
                return false;
              }

              @Override
              public void unlockForGII(boolean forWrite)
                  throws LockNotHeldException {
                // TODO Auto-generated method stub
                
              }            
            });
      }
    };
    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);

    // insert five entries from accessor
    //Cache cache = getCache();

    // for (int i = 1; i < 6; i++) {
    // region.put(i, i);
    // }

    // Now set the Observer in another VM
    vm1.invoke(new SerializableRunnable("Add Message observer") {
      public void run() {
        disconnectFromDS();
        DistributionMessageObserver dmo = new DistributionMessageObserver() {
          @Override
          public void beforeProcessMessage(DistributionManager dm,
              DistributionMessage message) {
            if (message instanceof UpdateOperation.UpdateMessage) {
              UpdateOperation.UpdateMessage mssg = (UpdateOperation.UpdateMessage)message;

              LogWriter logger = getCache().getLogger();
              try {
                Cache cache = getGemfireCache();
                GemFireCacheImpl.setGFXDSystemForTests();
                LocalRegion lr = (LocalRegion)cache.getRegion(mssg.regionPath);
                AbstractRegionEntry entry = (AbstractRegionEntry)lr
                    .basicGetEntry(mssg.key);
                logger.info("last modified time of entry ="
                    + entry.getLastModified());
                logger.info("last modified time from message ="
                    + (mssg.lastModified - dm.getCacheTimeOffset()));
                assertEquals(entry.getLastModified(),
                    (mssg.lastModified - dm.getCacheTimeOffset()));
                logger.info("About to sleep");
                Thread.sleep(25000);
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
          }

          @Override
          public void afterProcessMessage(DistributionManager dm,
              DistributionMessage message) {

          }

          @Override
          public void beforeSendMessage(DistributionManager dm,
              DistributionMessage msg) {

          }
        };
        DistributionMessageObserver.setInstance(dmo);
        getCache();

      }
    });
    DistributionMessageObserver dmoClient = new DistributionMessageObserver() {
      @Override
      public void beforeProcessMessage(DistributionManager dm,
          DistributionMessage message) {
      }

      @Override
      public void afterProcessMessage(DistributionManager dm,
          DistributionMessage message) {
        if (message instanceof CreateRegionMessage) {
          // Profile has been updated, so now initiate a put from here

          Thread th = new Thread(new Runnable() {

            public void run() {
              Cache cache = getCache();
              final Region region = cache.getRegion(REGION_NAME);
              try {
                region.create(6, new byte[][] {
                    { 1, -1, 122, 78, 65, -65, 71 },
                    { 0, -1, 122, 79, 65, -65, 71 },
                    { 1, -1, 121, 78, 65, -65, 71 } });
              } catch (Exception e) {
                exceptionFound = true;
              }
            }
          });
          th.start();
          try {
            th.join();
          } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
        }

      }

      @Override
      public void beforeSendMessage(DistributionManager dm,
          DistributionMessage msg) {

      }
    };
    disconnectFromDS();
    DistributionMessageObserver.setInstance(dmoClient);

    // Thread th = new Thread(new Runnable() {
    // 
    // public void run() {
    // region.put(6, 6);
    // }
    // });
    // th.start();
    // Thread.sleep(4000);

    vm0.invoke(new SerializableRunnable("Add Message observer") {
      public void run() {
        disconnectFromDS();
        DistributionMessageObserver dmo = new DistributionMessageObserver() {
          @Override
          public void beforeProcessMessage(DistributionManager dm,
              DistributionMessage message) {
            if (message instanceof InitialImageOperation.RequestImageMessage) {
              try {
                Thread.sleep(10000);
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
            else if (message instanceof UpdateOperation.UpdateMessage) {
              GemFireCacheImpl.setGFXDSystemForTests();
            }
          }

          @Override
          public void afterProcessMessage(DistributionManager dm,
              DistributionMessage message) {

          }

          @Override
          public void beforeSendMessage(DistributionManager dm,
              DistributionMessage msg) {

          }
        };
        DistributionMessageObserver.setInstance(dmo);
        getCache();

      }
    });
    vm0.invoke(createRegionStore1);
    createRegionAcc.run();

    vm1.invoke(createRegionStore2);

    Thread.sleep(30000);
    assertFalse(exceptionFound);
    // th.join();

  }
  
  public void testBugForPutAll() throws Exception {

    // Create a replicated region , with accessor and one data store. 
    // Now bring up a new data store, which does a GII from existing host.
    // Ensure that a 6th entry put
    // from accessor , reaches the new host , but does not get inserted, till
    // the GII is over
    // and the 6th entry reaches the new host via GII
    SerializableRunnable createRegionAcc = new SerializableRunnable(
        "Create non persistent empty region") {
      public void run() {
        getCache();
        RegionFactory rf = new RegionFactory();
        rf.setDataPolicy(DataPolicy.EMPTY);
        rf.setScope(Scope.DISTRIBUTED_ACK);
        rf.create(REGION_NAME);
      }
    };

    SerializableRunnable createRegionStore1 = new SerializableRunnable(
        "Create non persistent region") {
      public void run() {
        getCache();
        RegionFactory rf = new RegionFactory();
        rf.setDataPolicy(DataPolicy.REPLICATE);
        rf.setScope(Scope.DISTRIBUTED_ACK);
        rf.create(REGION_NAME);
      }
    };

    SerializableRunnable createRegionStore2 = new SerializableRunnable(
        "Create non persistent region") {
      public void run() {
        getCache();
        RegionFactory rf = new RegionFactory();
        rf.setDataPolicy(DataPolicy.REPLICATE);
        rf.setScope(Scope.DISTRIBUTED_ACK);
        LocalRegion lr = (LocalRegion)rf.create(REGION_NAME);
        ((AbstractRegionMap)lr.getRegionMap())
            .testOnlySetIndexUpdater(new IndexUpdater() {

              @Override
              public void unlockForIndexGII() throws LockNotHeldException {
              }

              @Override
              public void unlockForGII() throws LockNotHeldException {
              }

              @Override
              public void postEventCleanup(EntryEventImpl event) {
              }

              @Override
              public void postEvent(LocalRegion owner, EntryEventImpl event,
                  RegionEntry entry, boolean success) {
              }

              @Override
              public void onEvent(LocalRegion owner, EntryEventImpl event,
                  RegionEntry entry) {
                // LogWriter logger = owner.getCache().getLogger();
                // logger.info("Invoked onEvent. Event ="+event);
                // throw new
                // EntryExistsException("The GfxdIndexManager.onEvent should not have been invoked","dummy");

                boolean throwEEE = false;

                Object oldVal = event.getOldValue();
                Object newVal = event.getNewValue();
                Long lastModifiedFromOrigin = (Long)event.getContextObject();
                if (lastModifiedFromOrigin == null
                    || lastModifiedFromOrigin.longValue() != entry
                        .getLastModified()) {
                  throwEEE = true;
                }
                else {
                  if ((oldVal instanceof byte[] && newVal instanceof byte[])
                      || (oldVal instanceof byte[][] && newVal instanceof byte[][])) {
                    if (oldVal instanceof byte[]) {
                      throwEEE = ((byte[])oldVal).length != ((byte[])newVal).length
                          || !Arrays.equals((byte[])oldVal, (byte[])newVal);
                    }
                    else {
                      if (((byte[][])oldVal).length == ((byte[][])newVal).length) {
                        int len = ((byte[][])oldVal).length;
                        for (int i = 0; i < len; ++i) {
                          throwEEE = !Arrays.equals(((byte[][])oldVal)[i],
                              ((byte[][])newVal)[i]);
                          if (throwEEE) {
                            break;
                          }
                        }
                      }
                      else {
                        throwEEE = true;
                      }
                    }

                  }
                  else {
                    throwEEE = true;
                  }
                }

                if (throwEEE) {
                  throw new EntryExistsException(event.getKey().toString(),
                      event.getOldValue());
                }

              }

              @Override
              public boolean needsRecovery() {
                return false;
              }

              @Override
              public boolean lockForIndexGII() throws TimeoutException {
                return false;
              }

              @Override
              public void lockForGII() throws TimeoutException {
              }

              @Override
              public boolean clearIndexes(LocalRegion region, DiskRegion dr,
                  boolean holdIndexLock, Iterator<?> bucketEntriesIter, int bucketId) {
                return false;
              }

              @Override
              public void releaseIndexLock(LocalRegion region) {
              }

              @Override
              public void onOverflowToDisk(RegionEntry entry) {
              }

              @Override
              public void onFaultInFromDisk(RegionEntry entry) {
              }

              @Override
              public boolean hasRemoteOperations(Operation op) {
                return false;
              }

              @Override
              public boolean avoidSerialExecutor(Operation op) {
                return false;
              }

              @Override
              public boolean handleSuspectEvents() {
                return false;
              }

              @Override
              public void unlockForGII(boolean forWrite)
                  throws LockNotHeldException {
                // TODO Auto-generated method stub
                
              }

            });
      }
    };
    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);

    // insert five entries from accessor
    //Cache cache = getCache();

    // for (int i = 1; i < 6; i++) {
    // region.put(i, i);
    // }

    // Now set the Observer in another VM
    vm1.invoke(new SerializableRunnable("Add Message observer") {
      public void run() {
        disconnectFromDS();
        DistributionMessageObserver dmo = new DistributionMessageObserver() {
          @Override
          public void beforeProcessMessage(DistributionManager dm,
              DistributionMessage message) {
            if (message instanceof DistributedPutAllOperation.PutAllMessage) {
              //DistributedPutAllOperation.PutAllMessage mssg =
              //    (DistributedPutAllOperation.PutAllMessage)message;

              LogWriter logger = getCache().getLogger();
              try {
                GemFireCacheImpl.setGFXDSystemForTests();
                //Cache cache = getGemfireCache();
               // LocalRegion lr = (LocalRegion)cache.getRegion(mssg.regionPath);
             /*   AbstractRegionEntry entry = (AbstractRegionEntry)lr
                    .basicGetEntry(mssg.key);
                logger.info("last modified time of entry ="
                    + entry.getLastModified());
                logger.info("last modified time from message ="
                    + dm.getLocalSystemTimeMillis(mssg.lastModified));
                assertEquals(entry.getLastModified(), dm
                    .getLocalSystemTimeMillis(mssg.lastModified));*/
                logger.info("About to sleep");
                Thread.sleep(25000);
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
          }

          @Override
          public void afterProcessMessage(DistributionManager dm,
              DistributionMessage message) {

          }

          @Override
          public void beforeSendMessage(DistributionManager dm,
              DistributionMessage msg) {

          }
        };
        DistributionMessageObserver.setInstance(dmo);
        getCache();

      }
    });
    DistributionMessageObserver dmoClient = new DistributionMessageObserver() {
      @Override
      public void beforeProcessMessage(DistributionManager dm,
          DistributionMessage message) {
      }

      @Override
      public void afterProcessMessage(DistributionManager dm,
          DistributionMessage message) {
        if (message instanceof CreateRegionMessage) {
          // Profile has been updated, so now initiate a put from here

          Thread th = new Thread(new Runnable() {

            public void run() {
              Cache cache = getCache();
              Map map = new HashMap();
              byte[] data = new byte[]{1,2,3,4,5,6};
              for(int i =1; i < 10; ++i) {
                map.put(i, data);  
              }
              
              
              final Region region = cache.getRegion(REGION_NAME);
              try {
                 region.putAll(map);
              } catch (Exception e) {
                exceptionFound = true;
              }
            }
          });
          th.start();
          try {
            th.join();
          } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
        }

      }

      @Override
      public void beforeSendMessage(DistributionManager dm,
          DistributionMessage msg) {

      }
    };
    disconnectFromDS();
    DistributionMessageObserver.setInstance(dmoClient);

    // Thread th = new Thread(new Runnable() {
    // 
    // public void run() {
    // region.put(6, 6);
    // }
    // });
    // th.start();
    // Thread.sleep(4000);

    vm0.invoke(new SerializableRunnable("Add Message observer") {
      public void run() {
        disconnectFromDS();
        DistributionMessageObserver dmo = new DistributionMessageObserver() {
          @Override
          public void beforeProcessMessage(DistributionManager dm,
              DistributionMessage message) {
            if (message instanceof InitialImageOperation.RequestImageMessage) {
              try {
                Thread.sleep(10000);
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
            else if (message instanceof UpdateOperation.UpdateMessage) {
              GemFireCacheImpl.setGFXDSystemForTests();
            }
          }

          @Override
          public void afterProcessMessage(DistributionManager dm,
              DistributionMessage message) {

          }

          @Override
          public void beforeSendMessage(DistributionManager dm,
              DistributionMessage msg) {

          }
        };
        DistributionMessageObserver.setInstance(dmo);
        getCache();

      }
    });
    vm0.invoke(createRegionStore1);
    createRegionAcc.run();

    vm1.invoke(createRegionStore2);

    Thread.sleep(30000);
    assertFalse(exceptionFound);
    // th.join();

  
  }

}
