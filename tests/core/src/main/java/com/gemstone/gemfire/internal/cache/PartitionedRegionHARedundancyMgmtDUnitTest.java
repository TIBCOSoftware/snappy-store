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

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.admin.AdminDistributedSystem;
import com.gemstone.gemfire.admin.AdminDistributedSystemFactory;
import com.gemstone.gemfire.admin.AdminException;
import com.gemstone.gemfire.admin.Alert;
import com.gemstone.gemfire.admin.AlertLevel;
import com.gemstone.gemfire.admin.DistributedSystemConfig;
import com.gemstone.gemfire.admin.internal.AdminDistributedSystemImpl;
import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.PartitionedRegionStorageException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache30.CacheSerializableRunnable;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.PartitionedRegionSystemMemberRegionListenerDUnitTest.TestAlertListener;

import dunit.AsyncInvocation;
import dunit.DistributedTestCase;
import dunit.Host;
import dunit.SerializableCallable;
import dunit.SerializableRunnable;
import dunit.VM;

/**
 * This class tests redundancy management in partition region.
 * @author gthombar, modified by Tushar (for bug#35713)
 * @author Mitch Thomas (Alert notification and redundancy stats)
 * 
 */
public class PartitionedRegionHARedundancyMgmtDUnitTest extends
    PartitionedRegionDUnitTestCase
{
  private static final int TOTAL_NUM_BUCKETS = 11;

  static AtomicReference singleToneAdmin = new AtomicReference();
  
  /** constructor */
  public PartitionedRegionHARedundancyMgmtDUnitTest(String name) {
    super(name);
  }

  public void testNoResourceAlertThrottle() throws Exception {
    final Host host = Host.getHost(0);
    final VM accessor = host.getVM(0);
//    final VM dataStore = host.getVM(1);
    final VM adminVM = host.getVM(3);
    final String prPrefix = getUniqueName();
    final int redundancy = 0;
    long throttleTimeNanos = PRHARedundancyProvider.INSUFFICIENT_LOGGING_THROTTLE_TIME;
    final Runnable assertOneWarning = createWarningAlertRunnable(1);
    ExpectedException ee = null;
    AsyncInvocation slowPuts = null; 
    try {
      Boolean b = (Boolean)adminVM.invoke(createAdminVMCreationCallable());
      assertTrue(b.booleanValue());
      accessor.invoke(createPRWithRetryTimeoutPropSet(prPrefix, redundancy, 0,
          PartitionedRegionHelper.DEFAULT_TOTAL_WAIT_RETRY_ITERATION));

      ee = addExpectedException(PartitionedRegionStorageException.class.getName());
      slowPuts = accessor.invokeAsync(new CacheSerializableRunnable("Slow failing put") {
        public void run2() throws CacheException {
          Cache cache = getCache();
          Region pr = cache.getRegion(prPrefix);
          for(;;)
            try {
              Thread.sleep(200);
              pr.put("key", "val");
            } 
            catch (PartitionedRegionStorageException expected) {
            }
            catch (InterruptedException unexpected) {
              fail("interrupted");
            }
            catch (CacheClosedException quit) {
              break;
            }
            catch (RegionDestroyedException quit) {
              break;
            }
          }
      });
      
      adminVM.invoke(assertOneWarning);
      long start = NanoTimer.getTime();
      adminVM.invoke(assertOneWarning);
      long delta = NanoTimer.getTime() - start; 
      delta = TimeUnit.NANOSECONDS.toMillis(delta) ;
      throttleTimeNanos = TimeUnit.NANOSECONDS.toMillis(throttleTimeNanos);
      if (delta < throttleTimeNanos && throttleTimeNanos-delta <10) { //tolerable limit is 10 ms see bug 41402       
        fail("Alert throttle failed, delta=" + delta 
            + "ns, throttletime=" + throttleTimeNanos + "ns");
      }
    } finally {
      // Create datastore to allow slow putter to stop
      if (slowPuts != null) {
        accessor.invoke(new CacheSerializableRunnable("destroy " + prPrefix) {
          public void run2() throws CacheException {
            getCache().getRegion(prPrefix).destroyRegion();
          }
        });
        DistributedTestCase.join(slowPuts, 5 * 1000, getLogWriter());
      }

      if (ee != null) {
        ee.remove();
      }
      adminVM.invoke(createCleanUpAdminDS());
    }
  }

  public void testInsufficientResourceAlerts() throws Exception {
    runInsufficientResourceAlerts(false);
  }

  public void testInsufficientResourceAlertsWithTimeoutProperty() throws Exception {
    runInsufficientResourceAlerts(true);
  }

  private void runInsufficientResourceAlerts(final boolean useTimeout) throws Exception {
    final Host host = Host.getHost(0);
    final VM accessor = host.getVM(0);
    final VM datastore1 = host.getVM(1);
    final VM datastore2 = host.getVM(2);
    final VM adminVM = host.getVM(3);
    // Adding expected exceptions because setting the test mode will yield severe log messages
    // which are really not severe at all, but used to flag the admin listener
    ExpectedException ee = null;
    try {
      final String prPrefix = getUniqueName();
      final SerializableCallable assertBucketCreation =
        createAssertBucketCreation(prPrefix);
      // Define a PR that requires two VMs to satisfy redundancy only start one VM
      final int retryTime = 10 * 60 * 1000;
      final int redundancy = 1;
      CacheSerializableRunnable createAccessor =
        createPRWithRetryTimeoutPropSet(prPrefix, redundancy, 0, retryTime);
      final CacheSerializableRunnable createPR =
        createPRWithRetryTimeoutPropSet(prPrefix, redundancy, 10, retryTime);
      ee = runNoDSAndOneDSTest(adminVM, accessor, datastore1, createAccessor, createPR, assertBucketCreation, useTimeout);

      // Allow the bucket to regain redundancy
      datastore2.invoke(createPR);
      long begin = System.currentTimeMillis();
      while(true) {
        try {
          datastore2.invoke(assertBucketCreation);
          break;
        } catch(RuntimeException e) {
          if(System.currentTimeMillis() - begin < 30000) {
            Thread.sleep(100);
          } else {
            throw e;
          }
        }
      }
      

      // Assert redundancy regained
      SerializableCallable assertNoRedundancyRisk = new SerializableCallable("assertNoRedundancyAtRisk") {
        public Object call() throws Exception {
          Cache c = getCache();
          PartitionedRegion pr = (PartitionedRegion) c.getRegion(prPrefix);
          assertEquals(0, pr.getPrStats().getLowRedundancyBucketCount());
          return Boolean.TRUE;
        }
      };
      accessor.invoke(assertNoRedundancyRisk);
      datastore1.invoke(assertNoRedundancyRisk);
      datastore2.invoke(assertNoRedundancyRisk);
    } finally { 
      // Clean up test mode
      if (ee != null) {
        ee.remove();
      }
      try {
        unsetTestMode();
      } finally {
        adminVM.invoke(createCleanUpAdminDS());
      }
    }
  }

  public void testInsufficientResourceAlertsWithEnforceUniqueStorageAllocation() throws Exception {
    final Host host = Host.getHost(0);
    final VM accessor = host.getVM(0);
    final VM datastore1 = host.getVM(1);
    final VM datastore2 = host.getVM(2);
    final VM adminVM = host.getVM(3);
    // Adding expected exceptions because setting the test mode will yield severe log messages
    // which are really not severe at all, but used to flag the admin listener
    ExpectedException ee = null;
    try {
      CacheSerializableRunnable enforceUniqueStorage =
        new CacheSerializableRunnable("setEnforceUniqueHostStorageAllocation") {
        public void run2() throws CacheException {
          Properties props = new Properties();
          props.setProperty(DistributionConfig.ENFORCE_UNIQUE_HOST_NAME, "true");
          getSystem(props);
        }
      };
      accessor.invoke(enforceUniqueStorage);
      datastore1.invoke(enforceUniqueStorage);
      datastore2.invoke(enforceUniqueStorage);

      final String prPrefix = getUniqueName();
      final SerializableCallable assertBucketCreation =
        createAssertBucketCreation(prPrefix);
      // Define a PR that requires two VMs to satisfy redundancy only start one VM
      final int retryTime = 10 * 60 * 1000;
      final int redundancy = 1;
      CacheSerializableRunnable createAccessor =
        createPRWithRetryTimeoutPropSet(prPrefix, redundancy, 0, retryTime);
      final CacheSerializableRunnable createPR =
        createPRWithRetryTimeoutPropSet(prPrefix, redundancy, 10, retryTime);
      ee = runNoDSAndOneDSTest(adminVM, accessor, datastore1, createAccessor, createPR, assertBucketCreation, false);

      // Assert that adding a new VM doesn't allow the bucket to regain redundancy
      datastore2.invoke(createPR);
      datastore2.invoke(createAssertNoBucketCreation(prPrefix));

      // Assert redundancy regained
      SerializableCallable assertRedundancyRisk = createAssertRedundancyRisk(prPrefix);
      accessor.invoke(assertRedundancyRisk);
      datastore1.invoke(assertRedundancyRisk);
      datastore2.invoke(assertRedundancyRisk);
    } finally {
      CacheSerializableRunnable allowNonUniqueStorage =
        new CacheSerializableRunnable("unsetEnforceUniqueHostStorageAllocation") {
        public void run2() throws CacheException {
          disconnectFromDS();
        }
      };
      accessor.invoke(allowNonUniqueStorage);
      datastore1.invoke(allowNonUniqueStorage);
      datastore2.invoke(allowNonUniqueStorage);
      // Clean up test mode
      if (ee != null) {
        ee.remove();
      }
      try {
        unsetTestMode();
      } finally {
        adminVM.invoke(createCleanUpAdminDS());
      }
    }
  }

  /**
   * Use accessor to attempt bucket creation when 0 and 1 datastores are available.
   * Use AdminVM to assert proper warning messages/alerts are generated
   * @param adminVM
   * @param accessor
   * @param datastore1
   * @param createAccessor
   * @param createPR
   * @param assertBucketCreation
   * @param testDataStoreRecoveryTimeout
   * @return
   * @throws Exception
   */
  private ExpectedException runNoDSAndOneDSTest(final VM adminVM, final VM accessor,
      VM datastore1, Runnable createAccessor,
      Runnable createPR, SerializableCallable assertBucketCreation, final boolean testDataStoreRecoveryTimeout)
    throws Exception
  {
    setTestMode();

    Boolean b = (Boolean)adminVM.invoke(createAdminVMCreationCallable());
    assertTrue(b.booleanValue());

    final String prPrefix = getUniqueName();
    accessor.invoke(createAccessor);
    ExpectedException ee = addExpectedException(PRHARedundancyProvider.SUFFICIENT_STORES_MSG.toLocalizedString());
    // Create a bucket without the required resources
    CacheSerializableRunnable createABucket = new CacheSerializableRunnable("create a bucket") {
      public void run2() throws CacheException {
        Cache c = getCache();
        Region r = c.getRegion(prPrefix);
        assertNotNull(r);
        try {
          final Long dataStoreRecoveryTimeout = new Long(300); // milliseconds
          final long startTime = NanoTimer.getTime();
          if (testDataStoreRecoveryTimeout) {
            PRHARedundancyProvider.DATASTORE_DISCOVERY_TIMEOUT_MILLISECONDS = dataStoreRecoveryTimeout;
          }

          try {
            r.put(new Integer(1), Boolean.TRUE);
          } catch (PartitionedRegionStorageException boom) {
            if (testDataStoreRecoveryTimeout) {
              final long duration = TimeUnit.NANOSECONDS.toMillis(NanoTimer.getTime() - startTime);
              assertTrue("Duration is too long: " + duration + " ms",
                  duration <= (dataStoreRecoveryTimeout.longValue() * 2));
              getLogWriter().info("Duration is: " + TimeUnit.NANOSECONDS.toMillis(duration)
                  + " ms when recovery timeout is set to " + dataStoreRecoveryTimeout.longValue());
            }
            throw boom;
          }

          if (testDataStoreRecoveryTimeout) {
            final long duration = NanoTimer.getTime() - startTime;
            assertTrue(duration >= dataStoreRecoveryTimeout.longValue());
          }
        } 
        finally {
          if (testDataStoreRecoveryTimeout) {
            PRHARedundancyProvider.DATASTORE_DISCOVERY_TIMEOUT_MILLISECONDS = null;
          }
        }
      }
    };
    
    Runnable checkForWarningAlert = createWarningAlertRunnable(1);
    AsyncInvocation getAlert = adminVM.invokeAsync(checkForWarningAlert); 
    pause(30 * 1000); // TODO make sure the listener has started...
    
    // Start the process
    AsyncInvocation pendingBucketCreation = accessor.invokeAsync(createABucket);

    // ... and while that thread is trying to create the bucket
    // assert that alerts are generated and received
    DistributedTestCase.join(getAlert, 30 * 1000, getLogWriter());
    assertTrue(!getAlert.isAlive());
    assertTrue(!getAlert.exceptionOccurred());

    // Check bucket creation thread for expected exception
    DistributedTestCase.join(pendingBucketCreation, 30 * 1000, getLogWriter());
    assertTrue(pendingBucketCreation.exceptionOccurred());
    Throwable t = pendingBucketCreation.getException();
    assertEquals(PartitionedRegionStorageException.class, t.getClass());
    {
      String msg = t.getMessage();
      assertTrue(msg.lastIndexOf(PRHARedundancyProvider.INSUFFICIENT_STORES_MSG.toLocalizedString()) >= 0);
    }

    // Create a data store to allow bucket creation
    datastore1.invoke(createPR);
    b = (Boolean) datastore1.invoke(createAssertNoBucketCreation(prPrefix));
    assertTrue(b.booleanValue());

    getAlert = adminVM.invokeAsync(checkForWarningAlert);
    
    // Create a bucket
    accessor.invoke(createABucket);

    datastore1.invoke(assertBucketCreation);

    SerializableCallable assertRedundancyRisk = createAssertRedundancyRisk(prPrefix);
    assertTrue(((Boolean)accessor.invoke(assertRedundancyRisk)).booleanValue());
    assertTrue(((Boolean)datastore1.invoke(assertRedundancyRisk)).booleanValue());

    // Assert that a warning alert was generated indicating insufficient resources
    DistributedTestCase.join(getAlert, 30 * 1000, getLogWriter());
    assertTrue(!getAlert.isAlive());
    assertTrue(!getAlert.exceptionOccurred());
    return ee;
  }

  /**
   * @return
   */
  private Runnable createWarningAlertRunnable(final int expectedAlerts) {
    return new SerializableRunnable("checkForWarningAlert") {
      public void run()
      {
        AdminDistributedSystem ads = (AdminDistributedSystem) singleToneAdmin.get();
        assertNotNull(ads);
        // Validate that a warning alert (via the warning log message) is generated
        TestAlertListener tal = TestAlertListener.getInstance();
        try {
          tal.waitForEventsWithSeverity(expectedAlerts, 
              com.gemstone.gemfire.internal.admin.Alert.WARNING);
        }
        catch (InterruptedException e) {
          fail("interrupted");
        }
        Alert[] al = tal.getAlerts();
        int numMatches = 0;
        LogWriter l = getLogWriter();
        for (int i=0; i<al.length; i++) {
          String msg = al[i].getMessage();
          l.info("Alert found with message: " + msg);
          if (msg != null &&
              (msg.lastIndexOf(PRHARedundancyProvider.INSUFFICIENT_STORES_MSG.toLocalizedString()) >= 0)) {
            numMatches++;
          }
        }
        assertEquals(expectedAlerts, numMatches);
        tal.resetList();
      }
    };
  }

  private SerializableCallable createAdminVMCreationCallable() {
    return new SerializableCallable("createAdminVM") {
      public Object call() throws Exception
      {
        AdminDistributedSystem adminDS = createAdminDistributedSystem(AlertLevel.WARNING);
        TestAlertListener tal = TestAlertListener.getInstance();
        tal.resetList();
        adminDS.addAlertListener(tal);
        assertEquals(0, tal.getAlertCount());
        singleToneAdmin.set(adminDS);
        return Boolean.TRUE;
      }
    };
  }

  private SerializableCallable createCleanUpAdminDS() {
    return new SerializableCallable("cleanUpAdminDS") {
      public Object call() throws Exception
      {
        TestAlertListener tal = TestAlertListener.getInstance();
        if (tal != null) {
          tal.resetList();
        }
        AdminDistributedSystem ads = (AdminDistributedSystem) singleToneAdmin.get();
        if (ads != null) {
          ads.disconnect();
        }
        DistributionManager.isDedicatedAdminVM = false;
        disconnectFromDS();
        return Boolean.TRUE;
      }
    };
  }

  private SerializableCallable createAssertRedundancyRisk(final String prPrefix) {
    return new SerializableCallable("assertRedundancyAtRisk") {
      public Object call() throws Exception {
        Cache c = getCache();
        PartitionedRegion pr = (PartitionedRegion) c.getRegion(prPrefix);
        assertEquals(1, pr.getPrStats().getLowRedundancyBucketCount());
        return Boolean.TRUE;
      }
    };
  }

  private SerializableCallable createAssertNoBucketCreation(final String prPrefix) {
    // Assert that a bucket was not created
    return new SerializableCallable("assertNoBucketCreation") {
      public Object call() throws Exception
      {
        Cache c = getCache();
        PartitionedRegion pr = (PartitionedRegion) c.getRegion(prPrefix);
        assertNotNull(pr);

        assertEquals(0, pr.getDataStore().getBucketsManaged());
        return Boolean.TRUE;
      }
    };
  }

  private SerializableCallable createAssertBucketCreation(final String prPrefix) {
    return new SerializableCallable("assertBucketCreation") {
      public Object call() throws Exception
      {
        Cache c = getCache();
        PartitionedRegion pr = (PartitionedRegion) c.getRegion(prPrefix);
        assertEquals(1, pr.getDataStore().getBucketsManaged());
        return Boolean.TRUE;
      }
    };
  }

  private void setTestMode() {
    invokeInEveryVM(new SerializableCallable("setTestMode") {
      public Object call() throws Exception {
        PRHARedundancyProvider.TEST_MODE = true;
        return Boolean.TRUE;
      }
    });
  }
  
  private void unsetTestMode() {
    invokeInEveryVM(new SerializableCallable("unsetTestMode") {
      public Object call() throws Exception {
        PRHARedundancyProvider.TEST_MODE = false;
        return Boolean.TRUE;
      }
    });
  }

  /**
   * Confirm that a Partitioned Region with redundantCopies=1 will
   * refuse creating buckets when there is not enough resources and then
   * accept creating bucket when new resources have been added.
   */
  public void testRedundancyMgmtInPartitionedRegion() throws Throwable
  {
    AdminDistributedSystem adminDS = null;
    try {
      final Host host = Host.getHost(0);
      final VM dataStore0 = host.getVM(0);
      final VM dataStore1 = host.getVM(1);
      final VM dataStore2 = host.getVM(2);

      adminDS = createAdminDistributedSystem(AlertLevel.WARNING);
      TestAlertListener tal = TestAlertListener.getInstance();
      tal.resetList();
      adminDS.addAlertListener(tal);
      assertEquals(0, tal.getAlertCount());

      /** Prefix will be used for naming the partition Region */
      final String prPrefix = getUniqueName();

      /** Start index for key */
      final int startIndexForKey = 0;
      /** End index for key */
      final int endIndexForKey = TOTAL_NUM_BUCKETS;

      // creating multiple partition regions on 1 node with 
      final int redundancy = 1;
      final int localMaxMemory = 200;
      final int smallRetryTimeout = 20000;

      dataStore0.invoke(createPRWithRetryTimeoutPropSet(
          prPrefix, redundancy, localMaxMemory, smallRetryTimeout));

      // Bucket creation should succeed w/ only one VM
      putInPRFromOneVm(dataStore0,
          startIndexForKey, endIndexForKey, prPrefix);

      // Validate that a warning alert (via the warning log message) is generated 
      tal.waitForEventsWithSeverity(TOTAL_NUM_BUCKETS, com.gemstone.gemfire.internal.admin.Alert.WARNING);
      Alert[] al = tal.getAlerts();
      int numMatches = 0;
      LogWriter l = getLogWriter();
      for (int i=0; i<al.length; i++) {
        String msg = al[i].getMessage();
        l.info("Alert found with message: " + msg);
        if (msg != null &&
          (msg.lastIndexOf(PRHARedundancyProvider.INSUFFICIENT_STORES_MSG.toLocalizedString()) >= 0)) {
          numMatches++;
        }
      }
      assertEquals(TOTAL_NUM_BUCKETS, numMatches);

      // validating number of buckets
      dataStore0.invoke(validateBuckets(TOTAL_NUM_BUCKETS, prPrefix));

      dataStore1.invoke(createPRWithRetryTimeoutPropSet(
          prPrefix, redundancy, localMaxMemory,
          PartitionedRegionHelper.DEFAULT_TOTAL_WAIT_RETRY_ITERATION));

      // checking number of buckets on vm1 and vm2
      dataStore0.invoke(validateBuckets(TOTAL_NUM_BUCKETS, prPrefix));
      dataStore1.invoke(validateBuckets(TOTAL_NUM_BUCKETS, prPrefix));

      // validate content of buckets
      dataStore0.invoke(new SerializableRunnable("validate contents") {
        public void run()
        {
          PartitionedRegion pr = (PartitionedRegion)getCache().getRegion(Region.SEPARATOR + prPrefix);
          assertNotNull("This region cannot be null", pr);
          for (int j = pr.getRedundantCopies()-1 ; j >= 0; --j) {
            List<BucketDump> be = null;
            try {
              be = pr.getAllBucketEntries(j);
            }
            catch (ForceReattemptException e) {
              fail("surprise exception", e);
            }
            BucketDump e1 = null, e2 = null;
            for (Iterator<BucketDump> beI = be.iterator(); beI.hasNext(); e1 =  beI.next()) {
              // getLogWriter().info("Entries for iter " + beit++ + " = " + e1);
              if (e2 != null && e1 != null && ! e1.equals(e2) && ! e2.equals(e1)) {
                Map.Entry me;
                StringBuffer sb = new StringBuffer("e1 contents:\n");
                for (Iterator e1i = e1.getValues().entrySet().iterator(); e1i.hasNext(); ) {
                  me = (Map.Entry) e1i.next();
                  sb.append(me.getKey() + "=>" + me.getValue() + "\n");
                }
                sb.append("e2 contents:\n");
                for (Iterator e2i = e2.getValues().entrySet().iterator(); e2i.hasNext(); ) {
                  me = (Map.Entry) e2i.next();
                  sb.append(me.getKey() + "=>" + me.getValue() + "\n");
                }
                getLogWriter().info(sb.toString());

                assertTrue("e1  " + e1 + " not equal to " + e2, false);
              }
              e2 = e1;
            }
          }
        }
      });

      dataStore2.invoke(createPRWithRetryTimeoutPropSet(
          prPrefix, redundancy, localMaxMemory,
          PartitionedRegionHelper.DEFAULT_TOTAL_WAIT_RETRY_ITERATION));
      dataStore2.invoke(validateBuckets(0, prPrefix));

      dataStore0.invoke(new CacheSerializableRunnable("destroyRegion") {
        public void run2() throws CacheException
        {
          Cache cache = getCache();
          PartitionedRegion pr = (PartitionedRegion)cache.getRegion(Region.SEPARATOR + prPrefix);
          assertNotNull(pr);
          pr.destroyRegion();
        }
      });
    } finally {
      if (adminDS != null) {
        adminDS.disconnect();
      }
      TestAlertListener tal = TestAlertListener.getInstance();
      if (tal != null) {
        tal.resetList();
      }
      DistributionManager.isDedicatedAdminVM = false;      
      disconnectFromDS();
    }
  }

  AdminDistributedSystem createAdminDistributedSystem(final AlertLevel alertLevel) throws AdminException  {
    DistributionConfig config2 = getSystem().getConfig();
    assertFalse(config2.getMcastPort() == DistributionConfig.DEFAULT_MCAST_PORT);
    String address = hydra.HostHelper.getHostAddress(config2.getMcastAddress());
    int port = config2.getMcastPort();
    String locators = DistributedSystemConfig.DEFAULT_LOCATORS;
    if (config2.getLocators().length() > 0){
      locators = config2.getLocators();
      getLogWriter().info("Using Locators: " + locators);
    } 
    disconnectFromDS();
    boolean setupFailed = true;
    try {
      DistributionManager.isDedicatedAdminVM = true;

      assertFalse(isConnectedToDS());

      final AdminDistributedSystemImpl ret;
      DistributedSystemConfig config =
        AdminDistributedSystemFactory.defineDistributedSystem();
      config.setMcastAddress(address);
      config.setMcastPort(port); 
      config.setLocators(locators);
      config.setRemoteCommand(DistributedSystemConfig.DEFAULT_REMOTE_COMMAND);
      ret = (AdminDistributedSystemImpl) AdminDistributedSystemFactory.getDistributedSystem(config);
      ret.connect(getLogWriter().convertToLogWriterI18n());
      if ( ! ret.getAlertLevel().equals(alertLevel)) {
        ret.setAlertLevel(alertLevel);
      }


      // Wait for the DistributedSystem's connection to the distributed
      // system to be initialized.
      WaitCriterion ev = new WaitCriterion() {
        public boolean done() {
          return ret.isConnected();
        }
        public String description() {
          return null;
        }
      };
      DistributedTestCase.waitForCriterion(ev, 20 * 1000, 200, true);

      // Make sure that the admin alert listener is recognized in all other 
      // members before proceeding
      final DistributedMember adminMemberId = ret.getDistributedMember();
      CacheSerializableRunnable verifyRegistration = new CacheSerializableRunnable("Verify listener for " + getName()) {
        public void run2() throws CacheException
        {
          InternalDistributedSystem ds = getSystem();
          final int waitTime = 100;
          final long maxWaitFail = 60000;
          final long startTime = System.currentTimeMillis();
          while (! ds.hasAlertListenerFor(adminMemberId, alertLevel.getSeverity())) {
            if ((System.currentTimeMillis() - startTime) > maxWaitFail) {
              fail("Waited over " + maxWaitFail + " ms for " + this + " to complete");
            }
            try {
              Thread.sleep(waitTime);
            } catch (InterruptedException ie) {
              fail("Interrupted waiting in " + this);
            }
          } 
        }
      };
      invokeInEveryVM(verifyRegistration);
      setupFailed = false;
      return ret; 
    } finally {
      if (setupFailed) {
        disconnectAllFromDS();
      }
    }

  }

  private CacheSerializableRunnable validateBuckets(
      final int exptNumBuckets, final String prefix)
  {
    return new CacheSerializableRunnable(
        "validateBucketNumbers") {
      public void run2()
      {
        Cache cache = getCache();
        PartitionedRegion pr = (PartitionedRegion)cache
        .getRegion(Region.SEPARATOR + prefix);
        assertNotNull("This region cannot be null", pr);
        long start = System.currentTimeMillis();
        while(exptNumBuckets != pr.getDataStore().localBucket2RegionMap.size()) {
          if(System.currentTimeMillis() - start > 30000) {
            fail("Found unexpected number of buckets " + pr.getDataStore().localBucket2RegionMap.size()
                + ", expected " + exptNumBuckets
                + " buckets are: " + pr.getDataStore().localBucket2RegionMap.keySet());
          } else {
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              fail("Interrupted");
            }
          }
        }
      }
    };
  }

  /**
   * This function performs put() operation from the single vm in multiple
   * partition regions.
   * 
   * @param vm0
   * @param startIndexForKey
   * @param endIndexForKey
   * @param rName region name
   */

  private void putInPRFromOneVm(VM vm0,
      final int startIndexForKey, final int endIndexForKey, final String rName) throws Throwable
  {
    int AsyncInvocationArrSize = 2;
    AsyncInvocation[] async = new AsyncInvocation[AsyncInvocationArrSize];
    async[0] = vm0.invokeAsync(putFromOneVm(startIndexForKey, endIndexForKey,
        rName));
    async[1] = vm0.invokeAsync(putFromOneVm(startIndexForKey + TOTAL_NUM_BUCKETS,
        endIndexForKey + TOTAL_NUM_BUCKETS, rName));

    /** main thread is waiting for the other threads to complete */
    for (int count = 0; count < AsyncInvocationArrSize; count++) {
        DistributedTestCase.join(async[count], 30 * 1000, getLogWriter());
    }
    
    for (int count = 0; count < AsyncInvocationArrSize; count++) {
      if (async[count].exceptionOccurred()) {
        Throwable t = async[count].getException();
        if (t.getClass().equals(PartitionedRegionStorageException.class)) {
          throw t;
        }
        fail("putInPRFromOneVM has exception in async run " + count, t);
      }
    }
  }

  /**
   * This function performs put() in multiple partition regions for the given
   * node.
   * 
   * @param startIndexForKey
   * @param endIndexForKey
   * @param prefix PR prefix name
   * @return
   */
  private CacheSerializableRunnable putFromOneVm(final int startIndexForKey,
      final int endIndexForKey, final String prefix)
  {
    CacheSerializableRunnable putFromVm = new CacheSerializableRunnable(
        "putFromOneVm") {
      public void run2()
      {
        Cache cache = getCache();
        final String expectedExceptions = PartitionedRegionStorageException.class.getName();
        getCache().getLogger().info("<ExpectedException action=add>" + 
                expectedExceptions + "</ExpectedException>");
        getLogWriter().info("<ExpectedException action=add>" + 
                expectedExceptions + "</ExpectedException>");
        Region pr = cache.getRegion(prefix);
        assertNotNull("This region is null : " + pr.getName(), pr);
        for (int k = startIndexForKey; k < endIndexForKey; k++) {
          Integer key = new Integer(k);
          pr.put(key, prefix + k);
        }
        getLogWriter().info("<ExpectedException action=remove>" + 
                expectedExceptions + "</ExpectedException>");
        getCache().getLogger().info("<ExpectedException action=remove>" + 
                expectedExceptions + "</ExpectedException>");
      }
    };
    return putFromVm;
  }
  
  CacheSerializableRunnable createPRWithRetryTimeoutPropSet(
      final String prPrefix, final int redundancy, final int maxMemory, final int retryTimeout)
  {
    return new CacheSerializableRunnable("createPRWithTotalNumBucketPropSet") {
      public void run2() throws CacheException
      {
        Cache cache = getCache();
        System.setProperty(PartitionedRegion.RETRY_TIMEOUT_PROPERTY,
            Integer.toString(retryTimeout));
        Region partitionedregion = cache.createRegion(prPrefix,
            createRegionAttrs(redundancy, maxMemory));
        assertNotNull(partitionedregion);
        assertFalse(partitionedregion.isDestroyed());
        System.setProperty(PartitionedRegion.RETRY_TIMEOUT_PROPERTY,
            Integer.toString(PartitionedRegionHelper.DEFAULT_TOTAL_WAIT_RETRY_ITERATION));
      }
    };
  }

  protected RegionAttributes createRegionAttrs(int red, int localMaxMem) {
    AttributesFactory attr = new AttributesFactory();
    attr.setDataPolicy(DataPolicy.PARTITION);
    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    PartitionAttributes prAttr = paf.setRedundantCopies(red)
        .setLocalMaxMemory(localMaxMem)
        .setTotalNumBuckets(TOTAL_NUM_BUCKETS)
        .create();
    attr.setPartitionAttributes(prAttr);
    return attr.create();
  }
}
