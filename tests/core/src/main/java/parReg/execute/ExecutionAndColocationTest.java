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
package parReg.execute;

import java.util.*;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.execute.Execution;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedLockService;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.BucketDump;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.execute.InternalExecution;
import com.gemstone.gemfire.internal.cache.execute.MemberMappedArgument;
import com.gemstone.gemfire.pdx.PdxInstance;
import getInitialImage.InitImageBB;
import hydra.*;
import parReg.KnownKeysTest;
import parReg.ParRegBB;
import parReg.ParRegPrms;
import parReg.ParRegUtil;
import parReg.colocation.Month;
import pdx.PdxTest;
import pdx.PdxTestVersionHelper;
import util.*;

public class ExecutionAndColocationTest extends KnownKeysTest {

  protected static final String KEY_LIST = "Key List";

  protected static final String PR_TOTAL_BUCKETS = "PR Total Buckets";

  protected static final String PR_TOTAL_DATASTORES = "PR Total DataStores";

  protected static final String DS_MEMBER_SET = "ds Member Set";

  protected static final String DS_SERVER_SET = "ds Server Set";

  /**
   * Initialize the single instance of this test class but not a region. If this
   * VM has already initialized its instance, then skip reinitializing.
   */
  public synchronized static void HydraTask_initialize() {
    if (testInstance == null) {
      testInstance = new ExecutionAndColocationTest();
      ((KnownKeysTest)testInstance).initInstance("clientRegion");
      if (isGatewayConfiguration) {
        String gatewayHubConfig = ConfigPrms.getGatewayHubConfig();
        GatewayHubHelper.createGatewayHub(gatewayHubConfig);
      }
    }
  }

  /**
   * Initialize the single instance of this test class but not a region. If this
   * VM has already initialized its instance, then skip reinitializing.
   */
  public synchronized static void HydraTask_initializeWithAccessor() {
    if (testInstance == null) {
      testInstance = new ExecutionAndColocationTest();
      long numOfAccessors = ParRegBB.getBB().getSharedCounters()
          .incrementAndRead(ParRegBB.numOfAccessors);
      if (numOfAccessors > TestConfig.tab().longAt(
          ParRegPrms.numberOfAccessors, 0)) {
        ((KnownKeysTest)testInstance).initInstance("clientRegion");
      }
      else {
        ((KnownKeysTest)testInstance).initInstance("clientAccessor");
      }
    }
  }

  /**
   * Initialize this test instance
   */
  @Override
  public void initInstance(String regDescriptName) {
    super.initInstance();
    highAvailability = TestConfig.tab().booleanAt(ParRegPrms.highAvailability,
        false);
    Cache myCache = null;
    if ((regDescriptName.equals("clientAccessor") ||
         regDescriptName.equals("accessorRegion")) && (TestConfig.tab().vecAt(hydra.CachePrms.names)).contains("accessorCache")) {
      myCache = CacheHelper.createCache("accessorCache");
    } else {
      myCache = CacheHelper.createCache("cache1");
    }

    ((ExecutionAndColocationTest)testInstance).registerFunctions();
    // used by rebalance tests only
    InternalResourceManager.ResourceObserver ro = ParRegPrms
        .getResourceObserver();
    if (ro != null) {
      InternalResourceManager rm = InternalResourceManager
          .getInternalResourceManager(myCache);
      rm.setResourceObserver(ro);
    }

    aRegion = RegionHelper.createRegion(regDescriptName);
    isBridgeConfiguration = (TestConfig.tab().stringAt(BridgePrms.names, null) != null);
    isGatewayConfiguration = (TestConfig.tab()
        .stringAt(GatewayPrms.names, null) != null);
    lockOperations = TestConfig.tab().booleanAt(ParRegPrms.lockOperations,
        false);
    if (lockOperations) {
      Log.getLogWriter().info("Creating lock service " + LOCK_SERVICE_NAME);
      distLockService = DistributedLockService.create(LOCK_SERVICE_NAME,
          DistributedSystemHelper.getDistributedSystem());
      Log.getLogWriter().info("Created lock service " + LOCK_SERVICE_NAME);
    }

  }

  /**
   * Initialize the single instance of this test class and an accessor
   * partitioned region.
   */
  public synchronized static void HydraTask_accessorInitialize() {
    if (testInstance == null) {
      testInstance = new ExecutionAndColocationTest();
      ((KnownKeysTest)testInstance).initInstance("accessorRegion");
      if (isBridgeConfiguration) {
        ParRegUtil.registerInterest(testInstance.aRegion);
      }
    }
  }

  public synchronized static void HydraTask_HA_accessorInitialize() {
    if (testInstance == null) {
      testInstance = new ExecutionAndColocationTest();
      ((KnownKeysTest)testInstance).initInstance("accessorRegion");
      if (isBridgeConfiguration) {
        ParRegUtil.registerInterest(testInstance.aRegion);
      }
    }
  }

  /**
   * Initialize the single instance of this test class and a dataStore
   * partitioned region.
   */
  public synchronized static void HydraTask_dataStoreInitialize() {
    if (testInstance == null) {
      testInstance = new ExecutionAndColocationTest();
      ((KnownKeysTest)testInstance).initInstance("dataStoreRegion");
      ParRegBB.getBB().getSharedMap().put(
          DataStoreVmStr + RemoteTestModule.getMyVmid(),
          new Integer(RemoteTestModule.getMyVmid()));
      if (isBridgeConfiguration) {
        BridgeHelper.startBridgeServer("bridge");
      }
      if (isGatewayConfiguration) {
        String gatewayHubConfig = ConfigPrms.getGatewayHubConfig();
        GatewayHubHelper.createGatewayHub(gatewayHubConfig);
      }
    }
  }

  public synchronized static void HydraTask_HA_dataStoreInitialize() {
    if (testInstance == null) {
      PRObserver.installObserverHook();
      PRObserver.initialize(RemoteTestModule.getMyVmid());
      testInstance = new ExecutionAndColocationTest();
      ((KnownKeysTest)testInstance).initInstance("dataStoreRegion");
      ParRegBB.getBB().getSharedMap().put(
          DataStoreVmStr + RemoteTestModule.getMyVmid(),
          new Integer(RemoteTestModule.getMyVmid()));
      RegionAttributes attr = testInstance.aRegion.getAttributes();
      PartitionAttributes prAttr = attr.getPartitionAttributes();
      if (prAttr == null) { // this is not a PR datastore, but probably a
                            // replicate; done in parReg/execute tests
        ParRegBB.getBB().getSharedMap().put("recoveryDelay", new Long(-1));
        ParRegBB.getBB().getSharedMap().put("startupRecoveryDelay",
            new Long(-1));
      }
      else {
        ParRegBB.getBB().getSharedMap().put("recoveryDelay",
            new Long(prAttr.getRecoveryDelay()));
        ParRegBB.getBB().getSharedMap().put("startupRecoveryDelay",
            new Long(prAttr.getStartupRecoveryDelay()));
      }
      if (isBridgeConfiguration) {
        BridgeHelper.startBridgeServer("bridge");
      }
    }
  }

  /**
   * For making the data stores do register function.
   */
  public synchronized static void HydraTask_initRegisterFunction() {
    ((ExecutionAndColocationTest)testInstance).registerFunctions();
  }

  /**
   * Hydra task to execute functions, then stop scheduling.
   */
  public static void HydraTask_doFunctionExecution() {
    PdxTest.initClassLoader();
    BitSet availableOps = new BitSet(operations.length);
    availableOps.flip(FIRST_OP, LAST_OP + 1);
    ((ExecutionAndColocationTest)testInstance)
        .doFunctionExecution(availableOps);
    if (availableOps.cardinality() == 0) {
      ParRegBB.getBB().getSharedCounters().increment(ParRegBB.TimeToStop);
      throw new StopSchedulingTaskOnClientOrder("Finished with ops");
    }
  }

  /**
   * Hydra task for executing functions on all keys for HA (so only on Region)
   */
  public static void HydraTask_executeFunctionAllKeysHA() {
    ((ExecutionAndColocationTest)testInstance).executeFunctionAllKeys();
    ((ExecutionAndColocationTest)testInstance).executeFunctionAllBuckets();
    ((ExecutionAndColocationTest)testInstance).executeFunctionMultipleNodes();
    if (!isBridgeConfiguration) {
      ((ExecutionAndColocationTest)testInstance)
          .executeRegionFunctionMemberArgs();
    }
  }

  /**
   * Hydra task for executing functions on all keys
   */
  public static void HydraTask_executeFunctionAllKeys() {
    PdxTest.initClassLoader();
    ((ExecutionAndColocationTest)testInstance).executeFunctionAllKeys();
    ((ExecutionAndColocationTest)testInstance).executeFunctionAllBuckets();
    ((ExecutionAndColocationTest)testInstance).executeFunctionMultipleNodes();
    ((ExecutionAndColocationTest)testInstance)
        .executeRegionFunctionMemberArgs();
    if (!isBridgeConfiguration) {
      ((ExecutionAndColocationTest)testInstance)
          .executeMemberFunctionMemberArgs();
    }
    else {
      ((ExecutionAndColocationTest)testInstance)
          .executeServerFunctionMemberArgs();
    }
  }

  /**
   * Hydra task for verifying functions on primary buckets
   */
  public static void HydraTask_executeFunctionPrimaryBuckets() {
    ((ExecutionAndColocationTest)testInstance)
        .executeFunctionPrimaryBucketsAllBuckets();
    ((ExecutionAndColocationTest)testInstance)
        .executeFunctionPrimaryBucketsMultipleNodes();
    ((ExecutionAndColocationTest)testInstance)
        .executeFunctionPrimaryBucketsSinlgeNode();
  }

  /**
   * Task for the data stores to register functions.
   */
  public void registerFunctions() {
    FunctionService.registerFunction(new RegionOperationsFunction());
    FunctionService.registerFunction(new KeysOperationsFunction());
    FunctionService.registerFunction(new PrimaryExecutionFunction());
    FunctionService.registerFunction(new MemberMappedArgsFunction());
  }

  /**
   * Do function execution to do operations on the REGION_NAME's keys using
   * keyIntervals to specify which keys get which operations. This will return
   * when all operations in all intervals have completed.
   * 
   * @param availableOps -
   *                Bits which are true correspond to the operations that should
   *                be executed.
   */
  public void doFunctionExecution(BitSet availableOps) {
    long minTaskGranularitySec = TestConfig.tab().longAt(
        TestHelperPrms.minTaskGranularitySec);
    long minTaskGranularityMS = minTaskGranularitySec
        * TestHelper.SEC_MILLI_FACTOR;
    long startTime = System.currentTimeMillis();

    // useTransactions() defaults to false
    boolean useTransactions = getInitialImage.InitImagePrms.useTransactions();
    if (useTransactions) {
       // TODO: TX: need to redo with new TX impl
       //TxHelper.recordClientTXOperations();
    }

    boolean rolledback;

    while ((availableOps.cardinality() != 0)
        && (System.currentTimeMillis() - startTime < minTaskGranularityMS)) {
      int whichOp = getOp(availableOps, operations.length);
      boolean doneWithOps = false;

      rolledback = false;
      if (useTransactions) {
        TxHelper.begin();
      }

      try {
        switch (whichOp) {
          case ADD_NEW_KEY:
            doneWithOps = addNewKeyFunction();
            break;
          case PUTALL_NEW_KEY:
            doneWithOps = putAllNewKeyFunction();
            break;
          case INVALIDATE:
            doneWithOps = invalidateFunction();
            break;
          case DESTROY:
            doneWithOps = destroyFunction();
            break;
          case UPDATE_EXISTING_KEY:
            doneWithOps = updateExistingKeyFunction();
            break;
          case GET:
            doneWithOps = getFunction();
            break;
          case LOCAL_INVALIDATE:
            doneWithOps = localInvalidateFunction();
            break;
          case LOCAL_DESTROY:
            doneWithOps = localDestroyFunction();
            break;
          default: {
            throw new TestException("Unknown operation " + whichOp);
          }
        }
      } catch (TransactionDataNodeHasDepartedException e) {
        if (!useTransactions) {
          throw new TestException("Unexpected TransactionDataNodeHasDepartedException " + TestHelper.getStackTrace(e));
        } else {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
          recordFailedOps(ParRegBB.FAILED_TXOPS);
          Log.getLogWriter().info("Rolling back transaction.");
          try {
            TxHelper.rollback();
            Log.getLogWriter().info("Done Rolling back Transaction");
          } catch (TransactionException te) {
            Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching TransactionDataNodeHasDeparted during tx ops.  Expected, continuing test.");
          }
          rolledback = true;
        }
      } catch (TransactionDataRebalancedException e) {
        if (!useTransactions) {
          throw new TestException("Unexpected Exception " + e + ". " + TestHelper.getStackTrace(e));
        } else {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
          recordFailedOps(ParRegBB.FAILED_TXOPS);
          Log.getLogWriter().info("Rolling back transaction.");
          try {
            TxHelper.rollback();
            Log.getLogWriter().info("Done Rolling back Transaction");
          } catch (TransactionException te) {
            Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching Exception " + e + " during tx ops.  Expected, continuing test.");
          }
          rolledback = true;
        }

      } catch (Exception e) {
        throw new TestException("Caught exception during test ", e);
      }

      if (useTransactions && !rolledback) {
        try {
          TxHelper.commit();
        } catch (TransactionDataNodeHasDepartedException e) {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
          recordFailedOps(ParRegBB.FAILED_TXOPS);
        } catch (TransactionDataRebalancedException e) {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
          recordFailedOps(ParRegBB.FAILED_TXOPS);
        } catch (TransactionInDoubtException e) {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
          recordFailedOps(ParRegBB.INDOUBT_TXOPS);
        } catch (ConflictException e) {
          // not expected (with only one op per tx)
          throw new TestException("Unexpected Exception " + e + ". " + TestHelper.getStackTrace(e));
        }
      }

      if (doneWithOps) {
        Log.getLogWriter().info("Done with operation " + whichOp);
        availableOps.clear(whichOp);
      }
    }
  }

  public static void HydraTask_waitForEventsReceival() {
    if (testInstance == null) {
      testInstance = new ExecutionAndColocationTest();
    }
    ((ExecutionAndColocationTest)testInstance).waitForEventsReceival();
  }

  /**
   * Add a new key to REGION_NAME. The method uses PR execute function for
   * addNewKey
   * 
   */
  protected boolean addNewKeyFunction() {

    Log.getLogWriter().info("Inside addNewKeyFunction()");

    long numNewKeysCreated = sc
        .incrementAndRead(InitImageBB.NUM_NEW_KEYS_CREATED);

    if (numNewKeysCreated > numNewKeys) {
      Log.getLogWriter().info(
          "All new keys created; returning from addNewKeyFunction()");
      return true;
    }

    Object key = NameFactory.getNextPositiveObjectName();

    if (TestConfig.tab().booleanAt(parReg.ParRegPrms.isWithRoutingResolver,
        false)) {
      Month callBackArg = Month.months[TestConfig.tab().getRandGen()
          .nextInt(11)];
      InitImageBB.getBB().getSharedMap().put(key, callBackArg);
    }

    if (aRegion.getAttributes().getDataPolicy() != DataPolicy.EMPTY) {
      checkContainsValueForKey(key, false, "before addNewKey");
    }

    Function addKeyFunction = new RegionOperationsFunction();
    FunctionService.registerFunction(addKeyFunction);
    Execution dataSet;
    dataSet = FunctionService.onRegion(aRegion);

    final Set keySet = new HashSet();
    keySet.add(key);

    ArrayList aList = new ArrayList();
    if (TestConfig.tab().getRandGen().nextBoolean()) {
      aList.add("putIfAbsent");
    } else {
      aList.add("addKey");
    }
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    try {
      ResultCollector drc = null;
      if (!TestConfig.tab().booleanAt(
          getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
        Log.getLogWriter().info("Going to do " + aList.get(0) + " execute");
        drc = dataSet.withFilter(keySet).withArgs(aList)
            .execute(addKeyFunction);
      } else {
        aList.set(0, "addKey");
        Log.getLogWriter().info("Going to do " + aList.get(0) + " execute");
        aList.add(key);
        // DistributedRegionFunctionExecutor myDataSet =
        // (DistributedRegionFunctionExecutor)dataSet;
        drc = dataSet.withArgs(aList).execute(
            addKeyFunction);
      }
      Object result = drc.getResult();
      Log.getLogWriter().info("Returned result " + result.toString());
    }
    catch (Exception e) {
      throw new TestException(
          "Got this exception during the onRegion function execution " + aList.get(0) + " ", e);
    }
    return (numNewKeysCreated >= numNewKeys);

  }

  /**
   * Add a new key (using region.putAll(Map))to REGION_NAME. The method uses PR
   * execute function for addNewKey
   * 
   */
  protected boolean putAllNewKeyFunction() {

    Log.getLogWriter().info("Inside putAllNewKeyFunction()");

    long numNewKeysCreated = sc
        .incrementAndRead(InitImageBB.NUM_NEW_KEYS_CREATED);

    if (numNewKeysCreated > numNewKeys) {
      Log.getLogWriter().info(
          "All new keys created; returning from putAllNewKeyFunction()");
      return true;
    }

    Object key = NameFactory.getNextPositiveObjectName();

    RandomValues randomValues = new RandomValues();
    Object value = createObject(key, randomValues);

    if (TestConfig.tab().booleanAt(parReg.ParRegPrms.isWithRoutingResolver,
        false)) {
      Month callBackArg = Month.months[TestConfig.tab().getRandGen()
          .nextInt(11)];
      InitImageBB.getBB().getSharedMap().put(key, callBackArg);
    }

    HashMap map = new HashMap();
    map.put(key, value);

    if (aRegion.getAttributes().getDataPolicy() != DataPolicy.EMPTY) {
      checkContainsValueForKey(key, false, "before addNewKey");
    }

    Function putAllFunction = new RegionOperationsFunction();
    FunctionService.registerFunction(putAllFunction);
    Execution dataSet;
    dataSet = FunctionService.onRegion(aRegion);

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList = new ArrayList();
    aList.add("putAll");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    aList.add(map);

    try {
      Log.getLogWriter().info("Going to do putAll execute with " + map);
      ResultCollector drc = null;
      if (!TestConfig.tab().booleanAt(
          getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
        drc = dataSet.withFilter(keySet).withArgs(aList).execute(
            putAllFunction);

      }
      else {
        drc = dataSet.withArgs(aList).execute(putAllFunction);
      }
      Object result = drc.getResult();
      Log.getLogWriter().info("Returned result " + result.toString());
    }
    catch (Exception e) {
      throw new TestException(
          "Got this exception during onRegion function execution for putAll operation ",
          e);
    }
    return (numNewKeysCreated >= numNewKeys);

  }

  /**
   * To invalidate a key to REGION_NAME. The method uses PR execute function for
   * invalidate
   * 
   */
  protected boolean invalidateFunction() {
    Log.getLogWriter().info("Inside invalidateFunction()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_INVALIDATE);
    if (!keyIntervals.keyInRange(KeyIntervals.INVALIDATE, nextKey)) {
      Log
          .getLogWriter()
          .info(
              "All existing keys invalidated; returning from invalidate Function execution");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);

    if (aRegion.getAttributes().getDataPolicy() != DataPolicy.EMPTY) {
      checkContainsValueForKey(key, true, "before invalidate");
    }

    Function invalidateFunction = new RegionOperationsFunction();
    FunctionService.registerFunction(invalidateFunction);
    Execution dataSet;
    dataSet = FunctionService.onRegion(aRegion);

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList = new ArrayList();
    aList.add("invalidate");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    try {
      Log.getLogWriter().info("Going to do invalidate execute");
      ResultCollector drc = null;
      if (!TestConfig.tab().booleanAt(
          getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
        drc = dataSet.withFilter(keySet).withArgs(aList)
            .execute(invalidateFunction.getId());
      }
      else {
        aList.add(key);
        drc = dataSet.withArgs(aList).execute(
            invalidateFunction.getId());
      }
      Object result = drc.getResult();
      Log.getLogWriter().info("Returned result " + result.toString());

    }
    catch (Exception e) {
      throw new TestException(
          "Got this exception during single key invalidate operation using onRegion function execution ",
          e);
    }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.INVALIDATE));

  }

  /**
   * Destroy key to REGION_NAME. The method uses PR execute function for destroy
   * Key
   * 
   */
  protected boolean destroyFunction() {

    Log.getLogWriter().info("Inside destroyFunction()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_DESTROY);
    if (!keyIntervals.keyInRange(KeyIntervals.DESTROY, nextKey)) {
      Log.getLogWriter().info("All destroys completed; returning from destroy");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);
    Log.getLogWriter().info("Destroying " + key);

    if (aRegion.getAttributes().getDataPolicy() != DataPolicy.EMPTY) {
      checkContainsValueForKey(key, true, "before destroy");
    }

    Function destroyFunction = new RegionOperationsFunction();
    FunctionService.registerFunction(destroyFunction);
    Execution dataSet;
    dataSet = FunctionService.onRegion(aRegion);

    final Set keySet = new HashSet();
    keySet.add(key);
    
    ArrayList aList = new ArrayList();
    if (TestConfig.tab().getRandGen().nextBoolean()) {
      aList.add("remove");
    } else {
      aList.add("destroy");
    }
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    try {
      Log.getLogWriter().info("Going to do " + aList.get(0) + " execute");
      ResultCollector drc = null;
      if (!TestConfig.tab().booleanAt(
          getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
        drc = dataSet.withFilter(keySet).withArgs(aList)
            .execute(destroyFunction.getId());
      }
      else {
        aList.set(0, "destroy");
        aList.add(key);
        drc = dataSet.withArgs(aList).execute(
            destroyFunction.getId());
      }
      Object result = drc.getResult();
      Log.getLogWriter().info("Returned result " + result.toString());

    }
    catch (Exception e) {
      throw new TestException(
          "Got this exception during single key " + aList.get(0) + " operation using onRegion function execution ",
          e);
    }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.DESTROY));
  }

  /**
   * Update existing key in REGION_NAME. The method uses PR execute function for
   * updating the value of the Key.
   * 
   */
  protected boolean updateExistingKeyFunction() {

    Log.getLogWriter().info("Inside update Function()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_UPDATE_EXISTING_KEY);
    if (!keyIntervals.keyInRange(KeyIntervals.UPDATE_EXISTING_KEY, nextKey)) {
      Log.getLogWriter().info(
          "All existing keys updated; returning from updateExistingKey");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);

    Function updateFunction = new RegionOperationsFunction();
    FunctionService.registerFunction(updateFunction);
    Execution dataSet;
    dataSet = FunctionService.onRegion(aRegion);

    Log.getLogWriter().info("Updating key " + key);

    final Set keySet = new HashSet();
    keySet.add(key);

    ArrayList aList = new ArrayList();
    if (TestConfig.tab().getRandGen().nextBoolean()) {
      aList.add("replace");
    } else {
      aList.add("update");
    }
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    try {
      Log.getLogWriter().info("Going to do " + aList.get(0) + " execute");
      ResultCollector drc = null;
      if (!TestConfig.tab().booleanAt(
          getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
        drc = dataSet.withFilter(keySet).withArgs(aList)
            .execute(updateFunction);
      }
      else {
        aList.set(0, "update");
        aList.add(key);
        drc = dataSet.withArgs(aList).execute(
            updateFunction);
      }
      Object result = drc.getResult();
      Log.getLogWriter().info("Returned result " + result.toString());

    }
    catch (Exception e) {
      throw new TestException(
          "Got this exception when executing " + aList.get(0) + " on single key with onRegion function execution ",
          e);
    }

    return (nextKey >= keyIntervals
        .getLastKey(KeyIntervals.UPDATE_EXISTING_KEY));
  }

  /**
   * get the value of key in REGION_NAME. The method uses PR execute function
   * for getting the value of the Key.
   * 
   */
  protected boolean getFunction() {
    Log.getLogWriter().info("Inside getFunction()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_GET);
    if (!keyIntervals.keyInRange(KeyIntervals.GET, nextKey)) {
      Log.getLogWriter().info("All gets completed; returning from get");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);

    Function getFunction = new RegionOperationsFunction();
    FunctionService.registerFunction(getFunction);
    Execution dataSet;
    dataSet = FunctionService.onRegion(aRegion);

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList = new ArrayList();
    aList.add("get");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    try {
      Log.getLogWriter().info("Going to do get execute");
      ResultCollector drc = null;
      if (!TestConfig.tab().booleanAt(
          getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
        drc = dataSet.withFilter(keySet)
            .withArgs(aList).execute(getFunction.getId());
      }
      else {
        Log.getLogWriter().info(
            "Going to do get execute - Non partitioned region");
        aList.add(key);
        drc = dataSet.withArgs(aList).execute(
            getFunction.getId());
      }
      Object result = drc.getResult();
      Log.getLogWriter().info("Returned result " + result.toString());

    }
    catch (Exception e) {
      throw new TestException(
          "Got this exception when doing a get on the key using onRegion function execution "
              + e.getMessage() + " Cause " + e.getCause(), e);
    }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.GET));
  }

  /**
   * To Locally invalidate a key to REGION_NAME. The method uses PR execute
   * function for local invalidate
   * 
   */
  protected boolean localInvalidateFunction() {
    Log.getLogWriter().info("Inside localInvalidateFunction()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_LOCAL_INVALIDATE);
    if (!keyIntervals.keyInRange(KeyIntervals.LOCAL_INVALIDATE, nextKey)) {
      Log.getLogWriter().info(
          "All local invalidates completed; returning from localInvalidate");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);

    Function localInvalidateFunction = new RegionOperationsFunction();
    FunctionService.registerFunction(localInvalidateFunction);
    Execution dataSet;
    dataSet = FunctionService.onRegion(aRegion);

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList = new ArrayList();
    aList.add("localinvalidate");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    try {
      Log.getLogWriter().info("Going to do localinvalidate execute");
      ResultCollector drc = null;
      if (!TestConfig.tab().booleanAt(
          getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
        drc = dataSet.withFilter(keySet).withArgs(
            aList).execute(localInvalidateFunction.getId());
      }
      else {
        aList.add(key);
        drc = dataSet.withArgs(aList).execute(
            localInvalidateFunction.getId());
      }
      Object result = drc.getResult();
      Log.getLogWriter().info("Returned result " + result.toString());

    }
    catch (Exception e) {
      throw new TestException(
          "Got this exception when doing local invalidate operation ", e);
    }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.LOCAL_INVALIDATE));

  }

  /**
   * Locally destroy key in REGION_NAME. The method uses PR execute function for
   * destroy Key
   * 
   */
  protected boolean localDestroyFunction() {

    Log.getLogWriter().info("Inside local destroyFunction()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_LOCAL_DESTROY);
    if (!keyIntervals.keyInRange(KeyIntervals.LOCAL_DESTROY, nextKey)) {
      Log.getLogWriter().info(
          "All local destroys completed; returning from localDestroy");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);
    // Log.getLogWriter().info("Locally destroying " + key);

    if (aRegion.getAttributes().getDataPolicy() != DataPolicy.EMPTY) {
      checkContainsValueForKey(key, true, "before localDestroy");
    }

    Function localDestroyFunction = new RegionOperationsFunction();
    FunctionService.registerFunction(localDestroyFunction);
    Execution dataSet;
    dataSet = FunctionService.onRegion(aRegion);

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList = new ArrayList();
    aList.add("localdestroy");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    try {
      Log.getLogWriter().info("Going to do localdestroy execute");
      ResultCollector drc = null;
      if (!TestConfig.tab().booleanAt(
          getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
        drc = dataSet.withFilter(keySet).withArgs(
            aList).execute(localDestroyFunction);
      }
      else {
        aList.add(key);
        drc = dataSet.withArgs(aList).execute(
            localDestroyFunction);
      }
      Object result = drc.getResult();
      Log.getLogWriter().info("Returned result " + result.toString());

    }
    catch (Exception e) {
      throw new TestException(
          "Got this exception during local destroy of single key using onRegion function execution ",
          e);
    }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.LOCAL_DESTROY));
  }

  /**
   * Task to verify custom partitioning.
   */
  public synchronized static void HydraTask_verifyCustomPartitioning() {
    ((ExecutionAndColocationTest)testInstance).verifyCustomPartitioning();
  }

  protected void verifyCustomPartitioning() {

    PartitionedRegion pr = (PartitionedRegion)aRegion;
    int totalBuckets = pr.getTotalNumberOfBuckets();
    RegionAttributes attr = aRegion.getAttributes();
    PartitionAttributes prAttr = attr.getPartitionAttributes();
    int redundantCopies = prAttr.getRedundantCopies();
    int expectedNumCopies = redundantCopies + 1;
    int verifyBucketCopiesBucketId = 0;

    while (true) {

      if (verifyBucketCopiesBucketId >= totalBuckets) {
        break; // we have verified all buckets
      }

      Log.getLogWriter().info(
          "Verifying data for bucket id " + verifyBucketCopiesBucketId
              + " out of " + totalBuckets + " buckets");
      List<BucketDump> listOfMaps = null;
      try {
        listOfMaps = pr.getAllBucketEntries(verifyBucketCopiesBucketId);
      }
      catch (ForceReattemptException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      }

      // check that we have the correct number of copies of each bucket
      // listOfMaps could be size 0; this means we have no entries in this
      // particular bucket
      int size = listOfMaps.size();
      if (size == 0) {
        Log.getLogWriter().info(
            "Bucket " + verifyBucketCopiesBucketId + " is empty");
        verifyBucketCopiesBucketId++;
        continue;
      }

      if (size != expectedNumCopies) {
        throw new TestException("For bucketId " + verifyBucketCopiesBucketId
            + ", expected " + expectedNumCopies + " bucket copies, but have "
            + listOfMaps.size());
      }
      else {
        Log.getLogWriter().info(
            "For bucketId " + verifyBucketCopiesBucketId + ", expected "
                + expectedNumCopies + " bucket copies, and have "
                + listOfMaps.size());
      }


      Log.getLogWriter().info(
          "Validating co-location for all the redundant copies of the bucket with Id : "
              + verifyBucketCopiesBucketId);
      // Check that all copies of the buckets have the same data
      for (int i = 0; i < listOfMaps.size(); i++) {
        BucketDump dump = listOfMaps.get(i);
        Map<Object, Object> map = dump.getValues();
        if (map.size() != 0) { // bucket not empty
           verifyCustomPartition(map, verifyBucketCopiesBucketId);
           verifyUniqueBucketForCustomPartioning(verifyBucketCopiesBucketId);
        }
      }

      verifyBucketCopiesBucketId++;
    }

  }

  protected void verifyCustomPartition(Map map, int bucketid) {

    Iterator iterator = map.entrySet().iterator();
    Map.Entry entry = null;
    Object key = null;

    while (iterator.hasNext()) {
      entry = (Map.Entry)iterator.next();
      key = entry.getKey();

      if (ParRegBB.getBB().getSharedMap().get(
          "RoutingObjectForBucketid:" + bucketid) == null) {
        Log.getLogWriter().info(
            "RoutingObject for the bucket id to be set in the BB");
        ParRegBB.getBB().getSharedMap().put(
            "RoutingObjectForBucketid:" + bucketid,
            InitImageBB.getBB().getSharedMap().get(key));
        ParRegBB.getBB().getSharedMap().put(
            "RoutingObjectKeyBucketid:" + bucketid, key);
        Log.getLogWriter().info("BB value set to " + key.toString());
      }
      else {
        Log.getLogWriter().info("Checking the value for the routing object ");
        String blackBoardRoutingObject = ((Month)ParRegBB.getBB()
            .getSharedMap().get("RoutingObjectForBucketid:" + bucketid))
            .toString();
        String keyRoutingObject = ((Month)InitImageBB.getBB().getSharedMap()
            .get(key)).toString();
        if (!keyRoutingObject.equalsIgnoreCase(blackBoardRoutingObject)) {
          throw new TestException(
              "Expected same routing objects for the entries in this bucket id "
                  + bucketid + "but got different values "
                  + blackBoardRoutingObject + " and " + keyRoutingObject);
        }
        else {
          Log.getLogWriter().info(
              "Got the expected values "
                  + blackBoardRoutingObject
                  + " and "
                  + keyRoutingObject
                  + " for the keys "
                  + ParRegBB.getBB().getSharedMap().get(
                      "RoutingObjectKeyBucketid:" + bucketid) + " and " + key);
        }
      }
    }
  }

  /**
   * Task to verify that there is only a single bucket id for a routing Object
   * 
   */
  protected void verifyUniqueBucketForCustomPartioning(int bucketId) {

    if (bucketId == 0) {
      Log
          .getLogWriter()
          .info(
              "This is the first bucket, so no validation required as there is no bucket to be compared");
      return;
    }
    else {
      for (int i = 0; i < bucketId; i++) {
        if (!(ParRegBB.getBB().getSharedMap().get(
            "RoutingObjectForBucketid:" + i) == null)) {
          String referenceValue = ((Month)ParRegBB.getBB().getSharedMap().get(
              "RoutingObjectForBucketid:" + i)).toString();
          String currentValue = ((Month)ParRegBB.getBB().getSharedMap().get(
              "RoutingObjectForBucketid:" + bucketId)).toString();
          Log.getLogWriter().info("currentValue: " + currentValue);
          Log.getLogWriter().info("referenceValue: " + referenceValue);

          if (currentValue.equalsIgnoreCase(referenceValue)) {
            throw new TestException("Two buckets with the id " + i + " and "
                + bucketId + " have the same routing Object " + referenceValue);
          }
          else {
            Log.getLogWriter().info(
                "As expected the bucket with ids " + i + " and " + bucketId
                    + " have the different routing Object " + currentValue
                    + " and " + referenceValue);
          }

        }

      }
    }

  }

  /**
   * Hydra task to verify PR overflow to disk
   */
  public synchronized static void HydraTask_verifyOverflowToDisk() {
    ((ExecutionAndColocationTest)testInstance).verifyOverflowToDisk();
  }

  /**
   * Hydra task to verify PR overflow to disk
   */
  protected void verifyOverflowToDisk() {
    PartitionedRegion pr = (PartitionedRegion)aRegion;

    if (pr.getLocalMaxMemory() == 0) {
      Log.getLogWriter().info(
          "This is an accessor and hence eviction need not be verified");
      return;
    }
    long numOverflowToDisk = pr.getDiskRegionStats().getNumOverflowOnDisk();

    long entriesInVm = pr.getDiskRegionStats().getNumEntriesInVM();
    long totalEntriesInBuckets = 0;
    Set bucketList = pr.getDataStore().getAllLocalBuckets();
    Iterator iterator = bucketList.iterator();
    while (iterator.hasNext()) {
      Map.Entry entry = (Map.Entry)iterator.next();
      BucketRegion localBucket = (BucketRegion)entry.getValue();
      if (localBucket != null) {

        totalEntriesInBuckets = totalEntriesInBuckets
            + localBucket.entryCount();

      }
    }
    if (bucketList.size() > 0) {
      if (numOverflowToDisk == 0) {
        throw new TestException("For the region " + pr.getName()
            + " no eviction happened ");
      }
      else {
        Log.getLogWriter().info(
            "For the region " + pr.getName() + " entries overflown to disk is "
                + numOverflowToDisk);
      }
    }
    Log.getLogWriter().info(
        "For the region " + pr.getName() + " entries in disk = "
            + numOverflowToDisk + " entries in vm = " + entriesInVm
            + "entries in bucket = " + totalEntriesInBuckets);

    if (totalEntriesInBuckets != (numOverflowToDisk + entriesInVm)) {
      throw new TestException(
          "Entries in bucket (actual value "
              + totalEntriesInBuckets
              + " ) is not the same as the sum of entries in disk and entries in vm ( "
              + numOverflowToDisk + " and " + entriesInVm + ")");

    }
  }

  /**
   * Task to execute functions on all keys
   */
  public void executeFunctionAllKeys() {
    Log.getLogWriter().info("executeFunctionAllKeys()");

    Function getAllKeysFunction = new KeysOperationsFunction();
    FunctionService.registerFunction(getAllKeysFunction);
    HashMapResultCollector resultCollectorMap = new HashMapResultCollector();
    Execution dataSet;
    Set keySet = null;
    Map myMap = null;
    int expectedSize = 0 ; 
    String scenario = "Function Execution done on multiple buckets (all the keys of the PR passed as filter) by using HashMapResultCollector \n";

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p functionexecution");
      keySet = ((PartitionedRegion)aRegion).keys();
      expectedSize = keySet.size();
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorMap);
      scenario += " Topology : peer to peer \n";
    }
    else {
      Log.getLogWriter().info("Inside client server functionexecution");
      ArrayList keyList = (ArrayList)parReg.ParRegBB.getBB().getSharedMap()
          .get(KEY_LIST);
      keySet = new HashSet();
      keySet.addAll(keyList);
      expectedSize = keySet.size();
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorMap);
      // wait for 30 seconds of client silence to allow everything to be
      // pushed to clients
      SilenceListener.waitForSilence(30, 5000);
      scenario += " Topology : client server \n";
    }

    ArrayList aList = new ArrayList();
    aList.add("getKeys");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    try {
      Log.getLogWriter().info("Fetching all keys/values");
      ResultCollector rc = dataSet.withFilter(keySet).withArgs(aList).execute(
          getAllKeysFunction.getId());// TODO Kishor replaced async
      myMap = (HashMap)rc.getResult();
      Log.getLogWriter().info("Returned result " + myMap.toString());
    }
    catch (Exception e) {
      throw new TestException("During " + scenario + " Got this exception ", e);
    }

    if (expectedSize != myMap.size()) {
      throw new TestException("For the scenario " + scenario
          + " for the function KeysOperationsFunction "
          + "Expected result collector returned map size to be " + expectedSize
          + ' ' + keySet + " (the filter set size passed) but got the value "
          + myMap.size() + ": " + myMap);
    }
    else {
      Log.getLogWriter().info(
          "Got the expected size for the map " + myMap.size());
    }

    Iterator iterator = myMap.entrySet().iterator();
    Map.Entry entry = null;
    Object key = null;
    Object value = null;
    Object referenceValue = null;
    while (iterator.hasNext()) {
      entry = (Map.Entry)iterator.next();
      key = entry.getKey();
      value = entry.getValue();

      if (aRegion instanceof PartitionedRegion) {
        referenceValue = ((PartitionedRegion)aRegion).get(key);
      }
      else {
        referenceValue = aRegion.get(key);
      }

      if (!(value == null || referenceValue == null)) {
        boolean valueIsPdxInstance = value instanceof PdxInstance;
        boolean referenceValueIsPdxInstance = referenceValue instanceof PdxInstance;
        if (valueIsPdxInstance != referenceValueIsPdxInstance) {
           value = PdxTestVersionHelper.toBaseObject(value);
           referenceValue = PdxTestVersionHelper.toBaseObject(referenceValue);
        }
        if (!value.equals(referenceValue)) {
          throw new TestException(
              "For the scenario "
                  + scenario
                  + " it is expected that the value returned by doing get through function execution match with the value in region \n"
                  + "For the key " + key + " the values found in map is "
                  + value + " and that in region is " + referenceValue);
        }
        else {
          Log.getLogWriter().info(
              "For the key " + key
                  + " the values found the expected values in map and region "
                  + value);
        }
      }
      else {
        if ((value == null && referenceValue == null)) {
          Log.getLogWriter().info("Got expected null for the key " + key);
        }
        else {
          throw new TestException(
              "For the scenario "
                  + scenario
                  + " it is expected that the value returned by doing get through function execution match with the value in region \n"
                  + "Expected value for the key " + key + " to be "
                  + referenceValue + " but got the value " + value);
        }
      }

    }

  }

  /**
   * Task to execute functions on all keys on multiple nodes and validates that
   * the execution happens only on one node
   */
  public void executeFunctionMultipleNodes() {
    Log.getLogWriter().info("executeFunctionMultipleNodes()");

    Function getAllKeysFunction = new KeysOperationsFunction();
    FunctionService.registerFunction(getAllKeysFunction);
    ArrayListResultCollector resultCollectorList = new ArrayListResultCollector();
    Execution dataSet;
    Set keySet = null;
    ArrayList myList = null;
    int expectedSize = 0 ;

    String scenario = "Function Execution done on multiple buckets (all the keys of the PR passed as filter) by using ArrayListResultCollector \n";

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p functionexecution");
      keySet = ((PartitionedRegion)aRegion).keys();
      expectedSize = keySet.size();
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorList).withArgs("MultiNode");
      scenario += " Topology : peer to peer \n";
    }
    else {
      Log.getLogWriter().info("Inside client server functionexecution");
      ArrayList keyList = (ArrayList)parReg.ParRegBB.getBB().getSharedMap()
          .get(KEY_LIST);
      keySet = new HashSet();
      keySet.addAll(keyList);
      expectedSize = keySet.size();
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorList).withArgs("MultiNode");
      scenario += " Topology : client server \n";
    }

    try {
      Log.getLogWriter().info("Fetching all keys/values");
      myList = (ArrayList)dataSet.withFilter(keySet).execute(
          getAllKeysFunction.getId()).getResult();
    }
    catch (Exception e) {
      throw new TestException("For the scenario " + scenario, e);
    }

    if (expectedSize != myList.size()) {
      throw new TestException("For the scenario " + scenario
          + " the function KeysOperationsFunction returns a result for each "
          + "filter object. Expected result collector returned list size "
          + "to be " + expectedSize + ' ' + keySet + " (the filter size) "
          + "but got the value " + myList.size() + ": " + myList);
    }
    else {
      Log.getLogWriter().info(
          "Got the expected size for the list " + myList.size());
    }
  }

  /**
   * Task to execute functions on all the buckets (No filter passed)
   */
  public void executeFunctionAllBuckets() {
    Log.getLogWriter().info("executeFunctionAllBuckets()");

    Function getAllKeysFunction = new KeysOperationsFunction();
    FunctionService.registerFunction(getAllKeysFunction);
    HashMapResultCollector resultCollectorMap = new HashMapResultCollector();
    Execution dataSet;

    String scenario = "Function Execution done on all buckets (without filter) by using HashMapResultCollector \n";

    Map myMap = null;
    Set keySet = null;
    int expectedSize = 0 ;

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p functionexecution");
      keySet = ((PartitionedRegion)aRegion).keys();
      expectedSize = keySet.size();
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorMap);
      //totalNumOfBuckets = ((PartitionedRegion)aRegion)
      //    .getTotalNumberOfBuckets();
      scenario += " Topology : peer to peer \n";
    }
    else {
      Log.getLogWriter().info("Inside client server functionexecution");
      ArrayList keyList = (ArrayList)parReg.ParRegBB.getBB().getSharedMap()
          .get(KEY_LIST);
      //totalNumOfBuckets = ((Integer)parReg.ParRegBB.getBB().getSharedMap().get(
      //    PR_TOTAL_BUCKETS)).intValue();
      keySet = new HashSet();
      keySet.addAll(keyList);
      expectedSize = keySet.size();
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorMap).withArgs("All Buckets");
      scenario += " Topology : client server \n";
    }

    try {
      Log.getLogWriter().info("Fetching all keys/values");
      myMap = (HashMap)dataSet.execute(getAllKeysFunction.getId()).getResult();
    }
    catch (Exception e) {
      throw new TestException("During the scenario " + " Got this exception ",
          e);
    }

    // Execute on all buckets, should return contents only of single copy of
    // bucket
    if (myMap.size() != expectedSize) {
      throw new TestException("During the scenario " + scenario
          + " the function KeysOperationsFunction returns entries from local "
          + "data set (with vm_id). Expected size of the map to be "
          + expectedSize + ' ' + keySet + " (number of keys in the region) "
          + "but it is " + myMap.size() + ": " + myMap);
    }
    else {
      Log.getLogWriter().info(
          "Got the expected size for the map " + expectedSize);
    }

    myMap.clear();

    // Doing an execute to verify that the execute with no filter, gets the keys
    // in primary
    // buckets, even if execute on primary is false

    try {
      Log.getLogWriter().info(
          "Fetching keys values through filter on all nodes");
      myMap = (HashMap)dataSet.withArgs("GetFilter").withCollector(
          new HashMapResultCollector()).execute(getAllKeysFunction).getResult();
    }
    catch (Exception e) {
      throw new TestException("During the scenario " + " Got this exception ",
          e);
    }

    expectedSize = keySet.size();

    if (myMap.size() != expectedSize) {
      throw new TestException("During the scenario " + scenario
          + " the function KeysOperationsFunction returns entries from local "
          + "data set (with vm_id). Expected size of the map to be "
          + expectedSize + ' ' + keySet + " (number of keys in the region) "
          + "but it is " + myMap.size() + ": " + myMap);
    }
    else {
      Log.getLogWriter().info(
          "Got the expected size for the map " + expectedSize);
    }

  }

  /**
   * Task to verify executeOnPrimaryBuckets in function execution (all buckets)
   */
  public void executeFunctionPrimaryBucketsAllBuckets() {
    Execution dataSet = FunctionService.onRegion(aRegion);

    if (!TestConfig.tab().booleanAt(
        getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
      Log
          .getLogWriter()
          .info(
              "Primary bucket execution need not be tested in case of non-PR tests");
    }

    hydra.Log.getLogWriter().info(
        "Checking optimizeForWrite for no filter executions");

    PrimaryExecutionFunction primaryExecuteFunction = new PrimaryExecutionFunction();

    HashMap map = new HashMap();

    try {
      map = (HashMap)dataSet.withCollector(new MemberResultsCollector())
          .execute(primaryExecuteFunction.getId()).getResult();
    }
    catch (Exception e) {
      throw new TestException(
          "Got the exception during execute on primary buckets ", e);
    }

    // Verifying the memberID of addResult
    Iterator iterator = map.entrySet().iterator();
    Map.Entry entry = null;
    String key = null;
    String value = null;

    while (iterator.hasNext()) {
      entry = (Map.Entry)iterator.next();
      key = (String)entry.getKey();
      value = (String)((ArrayList)entry.getValue()).get(0);
      if (!key.equalsIgnoreCase(value)) {
        throw new TestException(
            " The function PrimaryExecutionFunction verifies that the function execution"
                + " happened on primary buckets and returns the node id where the function got executed "
                + "For the memberID " + key
                + " the result received mentions the executed node ID as "
                + value);
      }
    }
  }

  /**
   * Task to verify executeOnPrimaryBuckets in function execution (multiple
   * nodes)
   */
  public void executeFunctionPrimaryBucketsMultipleNodes() {
    Execution dataSet = FunctionService.onRegion(aRegion);

    if (!TestConfig.tab().booleanAt(
        getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
      Log
          .getLogWriter()
          .info(
              "Primary bucket execution need not be tested in case of non-PR tests");
    }

    hydra.Log.getLogWriter().info(
        "Checking optimizeForWrite for multiple nodes executions");

    PrimaryExecutionFunction primaryExecuteFunction = new PrimaryExecutionFunction();

    HashMap map;
    Set keySet;

    if (aRegion instanceof PartitionedRegion) {
      keySet = ((PartitionedRegion)aRegion).keys();
    }
    else {
      ArrayList keyList = (ArrayList)parReg.ParRegBB.getBB().getSharedMap()
          .get(KEY_LIST);
      keySet = new HashSet();
      keySet.addAll(keyList);
    }

    try {
      map = (HashMap)dataSet.withFilter(keySet).withCollector(
          new MemberResultsCollector()).execute(primaryExecuteFunction.getId())
          .getResult();
    }
    catch (Exception e) {
      throw new TestException(
          "Got the exception during execute on primary buckets ", e);
    }

    // Verifying the memberID of addResult
    Iterator iterator = map.entrySet().iterator();
    Map.Entry entry = null;
    String key = null;
    String value = null;

    while (iterator.hasNext()) {
      entry = (Map.Entry)iterator.next();
      key = (String)entry.getKey();
      value = (String)((ArrayList)entry.getValue()).get(0);
      if (!key.equalsIgnoreCase(value)) {
        throw new TestException(
            " The function PrimaryExecutionFunction verifies that the function execution"
                + " happened on primary buckets and returns the node id where the function got executed "
                + "For the memberID " + key
                + " the result received mentions the executed node ID as "
                + value);
      }
    }

  }

  /**
   * Task to verify executeOnPrimaryBuckets in function execution (Single node)
   */
  public void executeFunctionPrimaryBucketsSinlgeNode() {
    Execution dataSet = FunctionService.onRegion(aRegion);

    if (!TestConfig.tab().booleanAt(
        getInitialImage.InitImagePrms.useReplicatedRegions, false)) {
      Log
          .getLogWriter()
          .info(
              "Primary bucket execution need not be tested in case of non-PR tests");
    }

    PrimaryExecutionFunction primaryExecuteFunction = new PrimaryExecutionFunction();

    HashMap map;
    Set keySet;

    if (aRegion instanceof PartitionedRegion) {
      keySet = ((PartitionedRegion)aRegion).keys();
    }
    else {
      ArrayList keyList = (ArrayList)parReg.ParRegBB.getBB().getSharedMap()
          .get(KEY_LIST);
      keySet = new HashSet();
      keySet.addAll(keyList);
    }

    hydra.Log.getLogWriter().info(
        "Checking optimizeForWrite for single node executions");

    for (Object key : keySet) {
      Set singleKeySet = new HashSet();
      singleKeySet.add(key);

      try {
        map = (HashMap)dataSet.withFilter(singleKeySet).withCollector(
            new MemberResultsCollector()).execute(
            primaryExecuteFunction.getId()).getResult();
      }
      catch (Exception e) {
        throw new TestException(
            "Got the exception during execute on primary buckets ", e);
      }

      // Verifying the memberID of addResult
      Iterator iterator = map.entrySet().iterator();
      Map.Entry entry = null;
      String mapKey = null;
      String value = null;

      while (iterator.hasNext()) {
        entry = (Map.Entry)iterator.next();
        mapKey = (String)entry.getKey();
        value = (String)((ArrayList)entry.getValue()).get(0);
        if (!mapKey.equalsIgnoreCase(value)) {
          throw new TestException(
              " The function PrimaryExecutionFunction verifies that the function execution"
                  + " happened on primary buckets and returns the node id where the function got executed "
                  + "For the memberID " + key
                  + " the result received mentions the executed node ID as "
                  + value);
        }
      }
      map.clear();
    }
  }

  /**
   * Task to verify execution with variable args for the members during function
   * execution
   */
  public void executeRegionFunctionMemberArgs() {
    Log.getLogWriter().info("executeRegionFunctionMemberArgs");

    HashMap memberArgs = new HashMap();
    Set allMemberSet;

    if (aRegion instanceof PartitionedRegion) {
      allMemberSet = ((PartitionedRegion)aRegion).getAllNodes();
    }
    else {
      allMemberSet = (Set)ParRegBB.getBB().getSharedMap().get(DS_MEMBER_SET);
    }

    Iterator iterator = allMemberSet.iterator();
    while (iterator.hasNext()) {
      boolean shouldRemove = TestConfig.tab().getRandGen().nextBoolean();
      InternalDistributedMember member = (InternalDistributedMember)iterator
          .next();
      if (!shouldRemove) {
        memberArgs.put(member.getId(), member);
      }
    }

    Log.getLogWriter().info(
        "The final member set is of size " + allMemberSet.size());
    Log.getLogWriter().info("Member args map is " + memberArgs);
    MemberMappedArgument argument = new MemberMappedArgument("Default Arg",
        memberArgs);

    InternalExecution dataSet = ((InternalExecution)FunctionService.onRegion(
        aRegion).withCollector(new ArrayListResultCollector()))
        .withMemberMappedArgument(argument);

    ArrayList list;

    try {
      list = (ArrayList)dataSet.execute(new MemberMappedArgsFunction().getId())
          .getResult();
    }
    catch (Exception e) {
      throw new TestException("Caught exception during function execute ", e);
    }

    if (!(list.size() <= allMemberSet.size() && list.size() > 0)) {
      throw new TestException(
          "For the onRegion function execution with member Args "
              + "Expected the size of the function execution result list to be between "
              + allMemberSet.size()
              + " (the number of all datastores) member list and 1 "
              + " but found " + list.size());
    }
    else {
      Log.getLogWriter().info(
          "Received the expected list size after function execution "
              + allMemberSet.size());
    }

    int defaultArgsCounter = 0;
    int memberArgsCounter = 0;

    Iterator listIterator = list.iterator();
    while (listIterator.hasNext()) {
      Boolean argResult = (Boolean)listIterator.next();
      if (argResult.equals(Boolean.TRUE)) {
        memberArgsCounter++;
      }
      else if (argResult.equals(Boolean.FALSE)) {
        defaultArgsCounter++;
      }
      else {
        throw new TestException("Unknown result after function execute");
      }
    }

    // The following validation remains same even in case of HA scenario
    if (memberArgsCounter > memberArgs.size()) {
      throw new TestException(
          "For the onRegion function execution with member Args - "
              + " Expected member args to get executed on less than or equal to "
              + memberArgs.size() + " (the total member args passed) nodes"
              + " but got executed on " + memberArgsCounter + " nodes");
    }

    if (defaultArgsCounter > (allMemberSet.size() - memberArgs.size())) {
      // Additional default execution can happen if a new datastore happens in
      // case of HA
      if (!TestConfig.tab()
          .booleanAt(parReg.ParRegPrms.highAvailability, false)) {
        throw new TestException(
            "For the onRegion function execution with member Args - "
                + " Expected default args to get executed on less than or equal to "
                + (allMemberSet.size() - memberArgs.size()) + " nodes"
                + " but got executed on " + defaultArgsCounter + " nodes");
      }
    }

    Log.getLogWriter().info(
        "The member args got executed on " + memberArgsCounter
            + " and default arg execution happened on " + defaultArgsCounter
            + " nodes (all expected nodes)");
  }

  /**
   * Task to verify execution with variable args for the members during function
   * execution
   */
  public void executeMemberFunctionMemberArgs() {

    Log.getLogWriter().info("executeMemberFunctionMemberArgs");
    Cache cache = CacheHelper.getCache();
    DistributedSystem ds = cache.getDistributedSystem();

    Set allMemberSet = new HashSet(((InternalDistributedSystem)ds)
        .getDistributionManager().getNormalDistributionManagerIds());

    Iterator iterator = allMemberSet.iterator();

    HashMap memberArgs = new HashMap();
    while (iterator.hasNext()) {
      boolean shouldRemove = TestConfig.tab().getRandGen().nextBoolean();
      InternalDistributedMember member = (InternalDistributedMember)iterator
          .next();
      if (!shouldRemove) {
        memberArgs.put(member.getId(), member);
      }
    }

    Log.getLogWriter().info(
        "The final member set is of size " + allMemberSet.size());
    Log.getLogWriter().info("Member args map is " + memberArgs);
    MemberMappedArgument argument = new MemberMappedArgument("Default Arg",
        memberArgs);

    InternalExecution dataSet = ((InternalExecution)FunctionService.onMembers(
        ds).withCollector(new ArrayListResultCollector()))
        .withMemberMappedArgument(argument);

    ArrayList list;

    try {
      list = (ArrayList)dataSet.execute(new MemberMappedArgsFunction().getId())
          .getResult();
    }
    catch (Exception e) {
      throw new TestException(
          "Caught exception during onMembers function execute with member args ",
          e);
    }

    if (!(list.size() == allMemberSet.size())) {
      throw new TestException(
          " After the onMembers() function execute with member args "
              + " the function MemberMappedArgsFunction returns one result per executed node. \n"
              + "Expected the size of the function execution result list to be "
              + allMemberSet.size()
              + " (total number of members in the ds) but found " + list.size());
    }
    else {
      Log.getLogWriter().info(
          "Received the expected list size after function execution "
              + allMemberSet.size());
    }

    int defaultArgsCounter = 0;
    int memberArgsCounter = 0;

    Iterator listIterator = list.iterator();
    while (listIterator.hasNext()) {
      Boolean argResult = (Boolean)listIterator.next();
      if (argResult.equals(Boolean.TRUE)) {
        memberArgsCounter++;
      }
      else if (argResult.equals(Boolean.FALSE)) {
        defaultArgsCounter++;
      }
      else {
        throw new TestException("Unknown result after function execute");
      }
    }

    if (memberArgsCounter != memberArgs.size()) {
      throw new TestException(
          " After the onMembers() function execute with member args "
              + " the function MemberMappedArgsFunction returns one result per executed node. \n"
              + "Expected member args to get executed on " + memberArgs.size()
              + " (member args nodes) nodes" + " but got executed on "
              + memberArgsCounter + " nodes");
    }

    if (defaultArgsCounter != (allMemberSet.size() - memberArgs.size())) {
      throw new TestException(
          " After the onMembers() function execute with member args "
              + " the function MemberMappedArgsFunction returns one result per executed node. \n"
              + "Expected default args to get executed on "
              + (allMemberSet.size() - memberArgs.size()) + " nodes"
              + " but got executed on " + defaultArgsCounter + " nodes");
    }

    Log.getLogWriter().info(
        "The member args got executed on " + memberArgsCounter
            + " and default arg execution happened on " + defaultArgsCounter
            + " nodes (all expected nodes)");
  }

  /**
   * Task to verify execution with variable args for the members during function
   * execution
   */
  public void executeServerFunctionMemberArgs() {

    Log.getLogWriter().info("executeServerFunctionMemberArgs");

    Pool pool = PoolHelper.getPool("edgeDescript");
    if (pool == null) {
      Log.getLogWriter().info("Pool is null");
      try {
        pool = PoolHelper.createPool("edgeDescript");
      }
      catch (Exception e) {
        if (e.getMessage().contains(
            "edgeDescript not found in hydra.PoolPrms-names")) {
          Log
              .getLogWriter()
              .warning(
                  "This test does not use pool and hence onServer() not to be tested");
          return;
        }
        else {
          throw new TestException(e.getMessage());
        }
      }
    }

    Set allMemberSet = (Set)ParRegBB.getBB().getSharedMap().get(DS_SERVER_SET);

    Iterator iterator = allMemberSet.iterator();

    HashMap memberArgs = new HashMap();
    while (iterator.hasNext()) {
      boolean shouldRemove = TestConfig.tab().getRandGen().nextBoolean();
      InternalDistributedMember member = (InternalDistributedMember)iterator
          .next();
      if (!shouldRemove) {
        memberArgs.put(member.getId(), member);
      }
    }

    Log.getLogWriter().info(
        "The final member set is of size " + allMemberSet.size());
    Log.getLogWriter().info("Member args map is " + memberArgs);
    MemberMappedArgument argument = new MemberMappedArgument("Default Arg",
        memberArgs);

    InternalExecution dataSet = ((InternalExecution)FunctionService.onServers(
        pool).withCollector(new ArrayListResultCollector()))
        .withMemberMappedArgument(argument);

    try {
      ResultCollector rc = (dataSet.execute(new MemberMappedArgsFunction().getId()));
      Object result = rc.getResult();
      Log.getLogWriter().info("Returned result " + result.toString());
    } catch (Exception e) {
      throw new TestException("Caught exception during function execute ", e);
    }

    // if (!(list.size() == ((PoolImpl)pool).getCurrentServerNames().size())) {
    // throw new TestException(
    // "Expected the size of the function execution result list to be "
    // + ((PoolImpl)pool).getCurrentServerNames().size() + " but found " +
    // list.size());
    // }
    // else {
    // Log.getLogWriter().info(
    // "Received the expected list size after function execution "
    // + ((PoolImpl)pool).getCurrentServerNames().size());
    // }

  }

  /**
   * Task to put the key set in BB
   */
  public void putKeySetInBB() {
    Set keySet;
    int totalNumOfBuckets;
    int totalDataStoreNodes;
    Set allMemberSet;
    Set allServerSet;
    if (aRegion instanceof PartitionedRegion) {
      keySet = ((PartitionedRegion)aRegion).keys();
      totalNumOfBuckets = ((PartitionedRegion)aRegion)
          .getTotalNumberOfBuckets();
      totalDataStoreNodes = ((PartitionedRegion)aRegion).getAllNodes().size();
      allMemberSet = ((PartitionedRegion)aRegion).getAllNodes();
      allServerSet = new HashSet((InternalDistributedSystem.getAnyInstance())
          .getDistributionManager().getNormalDistributionManagerIds());
    }
    else {
      keySet = aRegion.keySet();
      totalNumOfBuckets = 0;
      totalDataStoreNodes = 0;
      allMemberSet = null;
      allServerSet = null;
    }

    ArrayList keyList = new ArrayList();
    keyList.addAll(keySet);

    if (parReg.ParRegBB.getBB().getSharedMap().get(KEY_LIST) == null) {
      parReg.ParRegBB.getBB().getSharedMap().put(KEY_LIST, keyList);
      parReg.ParRegBB.getBB().getSharedMap().put(PR_TOTAL_BUCKETS,
          new Integer(totalNumOfBuckets));
      parReg.ParRegBB.getBB().getSharedMap().put(PR_TOTAL_DATASTORES,
          new Integer(totalDataStoreNodes));
      parReg.ParRegBB.getBB().getSharedMap().put(DS_MEMBER_SET, allMemberSet);
      parReg.ParRegBB.getBB().getSharedMap().put(DS_SERVER_SET, allServerSet);
    }
    else {
      Log.getLogWriter().info("Key set already kept in BB");
    }
  }

  public void waitForEventsReceival() {
    // wait for 50 seconds of client silence to allow everything to be
    // pushed to clients
    SilenceListener.waitForSilence(50, 5000);
  }

  /**
   * Task for HA peer to peer
   */
  public static void killVms() {
    try {
      hydra.MasterController.sleepForMs(5000);
      ClientVmMgr.stop("Killing the VM",
          ClientVmMgr.MEAN_KILL, ClientVmMgr.IMMEDIATE);
    }
    catch (ClientVmNotFoundException e) {
      Log.getLogWriter().warning(" Exception while killing client ", e);
    }
  }

  /**
   * Task for killing the vms
   */
  public static void HydraTask_killVms() {
    hydra.MasterController.sleepForMs(5000);
    ((ExecutionAndColocationTest)testInstance).killVms();
  }

  /**
   * Task to put the PR key set in the BB so they can be used by the client vms
   */
  public static void HydraTask_putKeySetInBB() {
    ((ExecutionAndColocationTest)testInstance).putKeySetInBB();
  }
  
}
