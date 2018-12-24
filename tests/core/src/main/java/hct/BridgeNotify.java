
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

package hct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.query.QueryInvocationTargetException;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.cache.util.BridgeWriterException;
import cq.CQUtil;
import delta.DeltaValueHolder;
import hydra.*;
import util.*;
import vsphere.vijava.VMotionTestBase;

/**
 * Contains Hydra tasks and supporting methods for testing the GemFire
 * hierarchical cache (cache server) notify-all and notify-by-subscription
 * modes.
 *
 * @author Lynn Hughes-Godfrey
 * @since 5.1
 */
public class BridgeNotify {

  /* The singleton instance of BridgeNotify in this VM */
  static protected BridgeNotify bridgeClient;

  // Instance Variables
  protected boolean isSerialExecution;
  protected boolean useTransactions;
  public boolean isCarefulValidation = false; // true if this test does careful validation
  protected int numKeysInTest = TestConfig.tab().intAt( BridgeNotifyPrms.numKeys, 100 );
  protected long minTaskGranularitySec; // the task granularity in seconds
  protected long minTaskGranularityMS; // the task granularity in milliseconds
  protected RandomValues randomValues = null; // for creating random objects
  protected ArrayList originalKeyList = new ArrayList();
  protected static boolean isVMotionEnabled;

  // constants
  static protected final String REGION_NAME = "myRegion";

  // operations
  static protected final int ADD_OPERATION = 1;
  static protected final int UPDATE_OPERATION = 2;
  static protected final int INVALIDATE_OPERATION = 3;
  static protected final int DESTROY_OPERATION = 4;
  static protected final int READ_OPERATION = 5;
  static protected final int LOCAL_INVALIDATE_OPERATION = 6;
  static protected final int LOCAL_DESTROY_OPERATION = 7;
  static protected final int REGION_CLOSE_OPERATION = 8;
  static protected final int CLEAR_OPERATION = 9;
  static protected final int DESTROY_CREATE_OPERATION = 10;
	static protected final int CACHE_CLOSE_OPERATION = 11;
	static protected final int KILL_VM_OPERATION = 12;
	static protected final int PUTALL_ADD_OPERATION = 13;
	static protected final int PUTALL_UPDATE_OPERATION = 14;
  static protected final int PUT_IF_ABSENT_OPERATION = 15;
  static protected final int REMOVE_OPERATION = 16;
  static protected final int REPLACE_OPERATION = 17;
	
  // String prefixes for event callback object
  protected static final String createCallbackPrefix = "Create event originated in pid ";
  protected static final String updateCallbackPrefix = "Update event originated in pid ";
  protected static final String invalidateCallbackPrefix = "Invalidate event originated in pid ";
  protected static final String destroyCallbackPrefix = "Destroy event originated in pid ";
  
  final static String objectType = TestConfig.tab().stringAt(
      ValueHolderPrms.objectType, "util.ValueHolder");

  /**
   * Initializes a bridge server in this VM.
   */
  public static void initBridgeServer() {

    isVMotionEnabled = TestConfig.tab().booleanAt(
        vsphere.vijava.VIJavaPrms.vMotionEnabled, false);
    if (isVMotionEnabled) {
      VMotionTestBase.setvMotionDuringRegisterInterest();
    }
     PRObserver.installObserverHook();
     CacheHelper.createCache(ConfigPrms.getCacheConfig());
     Region aRegion = RegionHelper.createRegion(REGION_NAME, ConfigPrms.getRegionConfig());
     if (aRegion.getAttributes().getDataPolicy().withPartitioning()) {
        if (aRegion.getAttributes().getPartitionAttributes().getRedundantCopies() > 0) {
           Log.getLogWriter().info("Recovery is expected in this test if data stores are stopped");
           BridgeNotifyBB.getBB().getSharedMap().put("expectRecovery", new Boolean(true));
        }
     }
     CacheServer server =
             BridgeHelper.startBridgeServer(ConfigPrms.getBridgeConfig());
     // boolean notifyBySubscription = server.getNotifyBySubscription();
  }

  /**
   * Stops the bridge server in this VM.
   */
  public static void stopBridgeServer() {
    BridgeHelper.stopBridgeServer();
    CacheHelper.closeCache();
  }

  /**
   * Initializes a bridge server in this VM via XML.
   */
  public static void initBridgeServerWithXml() {

    // Connect with a cache.xml specification
    String cacheXmlFile = System.getProperty("user.dir") + "/"
                        + System.getProperty(ClientPrms.CLIENT_NAME_PROPERTY)
                        + ".xml";
    DistributedSystemHelper.connectWithXml(cacheXmlFile);

    // Generate the XML file
    CacheHelper.generateCacheXmlFile(ConfigPrms.getCacheConfig(),
                                     ConfigPrms.getRegionConfig(),
                                     ConfigPrms.getBridgeConfig(),
                                     ConfigPrms.getPoolConfig(),
                                     cacheXmlFile);

    // Create the cache using the XML file
    Cache cache = CacheHelper.createCacheFromXml(cacheXmlFile);
    Region region = RegionHelper.getRegion(REGION_NAME);
    Log.getLogWriter().info("Created region " + REGION_NAME + " with region attributes " + RegionHelper.regionAttributesToString(region.getAttributes()));
  }

  /**
   * Creates and initializes the singleton instance of BridgeNotify
   * in this VM.
   */
  public synchronized static void HydraTask_initialize() {
    if (bridgeClient == null) {
      bridgeClient = new BridgeNotify();
      bridgeClient.initialize();
    }
  }

  /**
   *  zeros out counters for event received by clients during
   *  test initialization
   *
   *  @see util.EventCountersBB
   */
  public static void clearEventCounters() {
      // Clear out any unwanted event counts
      EventCountersBB.getBB().zeroAllCounters();
  }

  /**
   * see HydraTask_initialize
   */
  public void initialize() {

    isSerialExecution = TestConfig.tab().booleanAt( hydra.Prms.serialExecution, false );
    useTransactions = getInitialImage.InitImagePrms.useTransactions();
    isCarefulValidation = isCarefulValidation || isSerialExecution;
    minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec);
    minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;

    randomValues = new RandomValues();

    // create the lists of original, even & odd keys
    ArrayList oddKeys = new ArrayList();
    ArrayList evenKeys = new ArrayList();
    for (int i = 1; i <= numKeysInTest; i++) {
       Object key = NameFactory.getObjectNameForCounter(i);
       originalKeyList.add(key);
       if ((i % 2) == 0) { // key has even number
          evenKeys.add(key);
       } else {
          oddKeys.add(key);
       }
    }

    // create cache/region with programmatic cache listener, register interest
    synchronized (BridgeNotify.class) {
      if (CacheHelper.getCache() == null) {
        CacheHelper.createCache(ConfigPrms.getCacheConfig());


        AttributesFactory factory = RegionHelper.getAttributesFactory(ConfigPrms.getRegionConfig());
        CacheListener myListener = BridgeNotifyPrms.getClientListener();
        if (myListener != null) {
          factory.setCacheListener( myListener );
        }
        Region aRegion = RegionHelper.createRegion(REGION_NAME, factory);

        // initialize CQService, if needed
        CQUtil.initialize();
        CQUtil.initializeCQService();
        
        
        registerInterest(aRegion, myListener, oddKeys, evenKeys);
        BridgeNotifyBB.getBB().getSharedCounters().incrementAndRead(BridgeNotifyBB.NUM_LISTENERS);
      }
    }
  }

  protected void registerInterest(Region aRegion, CacheListener myListener,
                                ArrayList oddKeys, ArrayList evenKeys) {

      String clientInterest = TestConfig.tasktab().stringAt( BridgeNotifyPrms.clientInterest, TestConfig.tab().stringAt( BridgeNotifyPrms.clientInterest, null) );
      String query = "select distinct * from " + aRegion.getFullPath();
      boolean receiveValuesAsInvalidates = false;
      try {
         if (clientInterest.equalsIgnoreCase("allKeys")) {
            aRegion.registerInterest( "ALL_KEYS", InterestResultPolicy.KEYS_VALUES );
            Log.getLogWriter().info("Registered interest in ALL_KEYS");

         } else if (clientInterest.equalsIgnoreCase("singleKey")) {
            long maxNames = NameFactory.getPositiveNameCounter();
            Object myKey = NameFactory.getObjectNameForCounter(TestConfig.tab().getRandGen().nextInt(1, (int)maxNames));
            query = "SELECT DISTINCT itr.value FROM " + aRegion.getFullPath() + ".entries itr where itr.key = '" + myKey + "'";;
            ((SingleKeyListener)myListener).setSingleKey( myKey );
            aRegion.registerInterest( myKey, InterestResultPolicy.KEYS_VALUES );
            Log.getLogWriter().info("Registered interest in a singleKey " + myKey);
            synchronized(  BridgeNotifyBB.class ) {
               List registeredKeys = (List)BridgeNotifyBB.getBB().getSharedMap().get( BridgeNotifyBB.SINGLE_KEYS_REGISTERED );
               registeredKeys.add( myKey );
               BridgeNotifyBB.getBB().getSharedMap().put( BridgeNotifyBB.SINGLE_KEYS_REGISTERED, registeredKeys );
            }
            // we should now have that singleKey (and only that key) in our local cache
            int numEntries = aRegion.keys().size();
            if (numEntries != 1) {
               throw new TestException("Registered interest in key, " + myKey + " and upon return from registerInterest, my local cache has " + numEntries + " keys");
            }
         } else if (clientInterest.equalsIgnoreCase("evenKeys")) {
            ((KeyListListener)myListener).setKeyList( evenKeys );
            aRegion.registerInterest( evenKeys, InterestResultPolicy.KEYS_VALUES );
            Log.getLogWriter().info("Registered interest in KeyList " + evenKeys);
            int numEntries = aRegion.keys().size();
            if (numEntries != evenKeys.size()) {
               throw new TestException("Registered interest in keyList with " + evenKeys.size() + " but after registerInterest, my local cache has " + numEntries + " keys");
            }
         } else if (clientInterest.equalsIgnoreCase("oddKeys")) {
            ((KeyListListener)myListener).setKeyList( oddKeys );
            aRegion.registerInterest( oddKeys, InterestResultPolicy.KEYS_VALUES );
            Log.getLogWriter().info("Registered interest in KeyList " + oddKeys);
            int numEntries = aRegion.keys().size();
            if (numEntries != oddKeys.size()) {
               throw new TestException("Registered interest in keyList with " + oddKeys.size() + " but after registerInterest, my local cache has " + numEntries + " keys");
            }
         } else if (clientInterest.equalsIgnoreCase("noInterest")) {
            Log.getLogWriter().info("Not registering interest in any keys");
         } else if (clientInterest.equalsIgnoreCase("receiveValuesAsInvalidates")) {
            aRegion.registerInterestRegex(".*", false, false);
            receiveValuesAsInvalidates = true;
            Log.getLogWriter().info("Registered interest to receiveValuesAsInvalidates");
         } else {
            throw new TestException("Invalid clientInterest " + clientInterest);
         }
     
         Log.getLogWriter().info("Setting BridgeNotifyBB.RECEIVE_VALUES_AS_INVALIDATES to :" + receiveValuesAsInvalidates + " on BridgeNotifyBB");
         BridgeNotifyBB.getBB().getSharedMap().put(BridgeNotifyBB.RECEIVE_VALUES_AS_INVALIDATES, new Boolean(receiveValuesAsInvalidates));

      } catch (CacheWriterException e) {
         throw new TestException(TestHelper.getStackTrace(e));
      }

      CQUtil.registerCQ(aRegion);
  }

  /**
   * Put an initial set of values into the cache
   */
  public static void HydraTask_populateRegion() {
    Region rootRegion = CacheHelper.getCache().getRegion(BridgeNotify.REGION_NAME);
    bridgeClient.populateRegion(rootRegion);
  }

  /**
   * @see #HydraTask_getEntriesFromServer
   */
  protected void populateRegion(Region aRegion) {
    for (int i=1; i <= numKeysInTest; i++) {
       String name = NameFactory.getNextPositiveObjectName();
       Object anObj = createObject(name, new RandomValues()) /*new ValueHolder(name, new RandomValues())*/;
       aRegion.put(name, anObj);
    }
    // sleep for 60 secs to allow distribution
    MasterController.sleepForMs(60000);
  }

  /**
   * bridgeClients with notifyBySubscription = false need to get all the 
   * entries after the region has been created/populated
   *
   * see HydraTask_populateRegion
   */
  public static void HydraTask_getEntriesFromServer() {
    Region rootRegion = CacheHelper.getCache().getRegion(BridgeNotify.REGION_NAME);
    bridgeClient.getEntriesFromServer(rootRegion);
  }

  protected void getEntriesFromServer(Region aRegion) {
    for (int i=1; i <= numKeysInTest; i++) {
       Object name = NameFactory.getObjectNameForCounter(i);
       try {
           aRegion.get(name, "ignore afterCreate on get()");
       } catch (Exception e) {
           throw new TestException(TestHelper.getStackTrace(e));
       }
    }
    Log.getLogWriter().info("Retrieved " + aRegion.keys().size() + " entries for region " + aRegion.getName());
  }

  /**
   * Perform random operation(s).
   */
  public static void HydraTask_doEntryOperations() {
    Region rootRegion = CacheHelper.getCache().getRegion(BridgeNotify.REGION_NAME);
    bridgeClient.doEntryOperations(rootRegion);
  }

  // methods to add/update/invalidate/destroy an object in a region

  /**
   * @see #HydraTask_doEntryOperations
   */
  protected void doEntryOperations(Region aRegion) {
      long startTime = System.currentTimeMillis();
      if (isSerialExecution) {
          logExecutionNumber();
      }

      boolean rolledback;

      do {
        TestHelper.checkForEventError(EventCountersBB.getBB());

        // reset the expected number of listeners to 0, in case we don't have
        // any keys in the region and we don't perform an op (serialBridgeNotifyMixedInterests)
        BridgeNotifyBB.getBB().getSharedMap().put(BridgeNotifyBB.numListeners, new Integer(0));
  
        rolledback = false;
        if (useTransactions) {
          TxHelper.begin();
        }

        try {

           // Note: bridge client's are not mirrored
           int whichOp = getOperation(BridgeNotifyPrms.entryOperations, false);
           switch (whichOp) {
             case ADD_OPERATION:
               addObject(aRegion, true);
               break;
             case PUTALL_ADD_OPERATION:
               addObjectViaPutAll(aRegion, true);
               break;
             case INVALIDATE_OPERATION:
               invalidateObject(aRegion, false);
               break;
             case DESTROY_OPERATION:
               destroyObject(aRegion, false);
               break;
             case DESTROY_CREATE_OPERATION:   // use to stay within initial keySet
               destroyAndCreateObject(aRegion, false);
               break;
             case UPDATE_OPERATION:
               updateObject(aRegion);
               break;
             case PUTALL_UPDATE_OPERATION:
                  updateObjectViaPutAll(aRegion);
               break;
             case READ_OPERATION:
               readObject(aRegion);
               break;
             case LOCAL_INVALIDATE_OPERATION:
               invalidateObject(aRegion, true);
               break;
             case LOCAL_DESTROY_OPERATION:
               destroyObject(aRegion, true);
                 break;
             case REGION_CLOSE_OPERATION:
               closeRegion(aRegion);
                 break;
	     case CACHE_CLOSE_OPERATION:
	         closeCache();
		 break;
	     case KILL_VM_OPERATION:
	         killVM();
		 break;
              case PUT_IF_ABSENT_OPERATION:
                 putIfAbsent(aRegion, true);
                 break;
              case REMOVE_OPERATION:
                 remove(aRegion);
                 break;
              case REPLACE_OPERATION:
                 replace(aRegion);
                 break;
             default: {
               throw new TestException("Unknown operation " + whichOp);
             }
           }
        } catch (TransactionDataNodeHasDepartedException e) {
          if (!useTransactions) {
            throw new TestException("Unexpected TransactionDataNodeHasDepartedException " + TestHelper.getStackTrace(e));
          } else {
          Log.getLogWriter().info("Caught TransactionDataNodeHasDepartedException.  Expected with concurrent execution, continuing test.");
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
        // no rebalancing in these tests (not a PR)
        throw new TestException("Unexpected Exception " + e + ".  " + TestHelper.getStackTrace(e));
      } 

      if (useTransactions && !rolledback) {
        try {
          TxHelper.commit();
        } catch (TransactionDataNodeHasDepartedException e) {
          Log.getLogWriter().info("Caught TransactionDataNodeHasDepartedException.  Expected with concurrent execution, continuing test.");
        } catch (TransactionDataRebalancedException e) {
          // not expected (no rebalancing in these tests/not a PR
          throw new TestException("Unexpected Exception " + e + ".  " + TestHelper.getStackTrace(e));
        } catch (TransactionInDoubtException e) {
          Log.getLogWriter().info("Caught TransactionInDoubtException.  Expected with concurrent execution, continuing test.");
        } catch (ConflictException e) {
          // can occur with concurrent execution
          Log.getLogWriter().info("Caught ConflictException. Expected with concurrent execution, continuing test.");
        }

        if (isSerialExecution) {
          TestHelper.checkForEventError(EventCountersBB.getBB());
          checkEventCounters();
        }
      }

    } while (System.currentTimeMillis() - startTime < minTaskGranularityMS);
  
    releaseThreadLocal(aRegion);
  }
  
  protected void releaseThreadLocal(Region aRegion) {
    ClientHelper.release(aRegion);
  }

    protected void query(Region aRegion, Object key, BaseValueHolder expectedValue) {

       // query will not look into tx state (so simply return if we're in a tx)
       if (TxHelper.exists()) return;

       SelectResults results = query(aRegion, key);
                                                                                                 
       if (isSerialExecution) {
          if (results.size() == 0) {
             if (expectedValue != null) {
               throw new TestException("ExpectedValue = <" + expectedValue + ">, but query ResultSet was empty");
             } else {
               Log.getLogWriter().info("Successful validation of empty result set (null)");
             }
                                                                                                 
          } else {
             BaseValueHolder result = (BaseValueHolder)(results.asList().get(0));
             if (!result.equals(expectedValue)) {
                throw new TestException("Expected value of "  + key + " to be <" + expectedValue + "> but query returned <" + result + ">");
             } else {
                Log.getLogWriter().info("Successful validation of query results");
             }
          }
       }
    }

    protected SelectResults query(Region aRegion, Object key) {
        String queryString = "SELECT DISTINCT itr.value FROM " + aRegion.getFullPath() + ".entries itr where itr.key = '" + key + "'";
        SelectResults results = null;
        try {
           results = aRegion.query(queryString);
           Log.getLogWriter().info("Query for Object <" + key + "> returned <" + results + ">");
        } catch (QueryInvocationTargetException te) {
           if (isSerialExecution) {
              // this shouldn't happen unless we simultaneously kill the server
              throw new TestException("Caught exception " + TestHelper.getStackTrace(te) + " during RemoteQuery execution");
           } else {
              // if server is killed during concurrent tests, we may get QITE
              // The Caused by field can be many things (CacheClosed, STE, ConnectExceptions, etc, for now, always allow
              Log.getLogWriter().info("Caught QueryInvocationTargetException, expected during concurrent execution with failover, continuing execution");
           }
        } catch (Exception e) {
           throw new TestException("Caught exception " + TestHelper.getStackTrace(e) + " during RemoteQuery execution");
        }
        return results;
    }

    protected void addObject(Region aRegion, boolean logAddition) {
        String name = NameFactory.getNextPositiveObjectName();
        addObject(aRegion, name, logAddition);
    }

    protected void addObjectViaPutAll(Region aRegion, boolean logAddition) {
        String name = NameFactory.getNextPositiveObjectName();
        addObjectViaPutAll(aRegion, name, logAddition);
    }

    protected void addObject(Region aRegion, String name, boolean logAddition) {
        Object anObj = getObjectToAdd(name);
        String callback = createCallbackPrefix + ProcessMgr.getProcessId();
        if (logAddition)
            Log.getLogWriter().info("addObject: calling put for name " + name + ", object " + TestHelper.toString(anObj) + " callback is " + callback + ", region is " + aRegion.getFullPath());
        aRegion.put(name, anObj, callback);
        long numPut = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_CREATE", BridgeNotifyBB.NUM_CREATE);

        // serialBridgeNotifyMixedInterests needs help knowing how many events to expect
        int numExpectedEvents = 1;               // for ALL_KEYS client
        if (originalKeyList.contains(name)) {    // 1 for odd/even keylist client
           numExpectedEvents++;
        }
        List singleKeyList = (List)BridgeNotifyBB.getBB().getSharedMap().get( BridgeNotifyBB.SINGLE_KEYS_REGISTERED );
        if (singleKeyList.contains(name)) {      // 1 if we selected the singleKey clients key
           numExpectedEvents++;
        }
        BridgeNotifyBB.getBB().getSharedMap().put( BridgeNotifyBB.numListeners, new Integer(numExpectedEvents) );
        query (aRegion, name, (BaseValueHolder)anObj);
    }

  /** 
   *  ConcurrentMap API testing
   */
   protected void putIfAbsent(Region aRegion, boolean logAddition) {
       String name = NameFactory.getNextPositiveObjectName();
       Object anObj = getObjectToAdd(name);

       if (logAddition) {
           Log.getLogWriter().info("putIfAbsent: calling putIfAbsent for key " + name + ", object " + TestHelper.toString(anObj) + ", region is " + aRegion.getFullPath() + ".");
       }

       aRegion.putIfAbsent(name, anObj);
       long numPut = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_CREATE", BridgeNotifyBB.NUM_CREATE);

       // serialBridgeNotifyMixedInterests needs help knowing how many events to expect
       int numExpectedEvents = 1;               // for ALL_KEYS client
       if (originalKeyList.contains(name)) {    // 1 for odd/even keylist client
          numExpectedEvents++;
       }
       List singleKeyList = (List)BridgeNotifyBB.getBB().getSharedMap().get( BridgeNotifyBB.SINGLE_KEYS_REGISTERED );
       if (singleKeyList.contains(name)) {      // 1 if we selected the singleKey clients key
          numExpectedEvents++;
       }
       BridgeNotifyBB.getBB().getSharedMap().put( BridgeNotifyBB.numListeners, new Integer(numExpectedEvents) );
       query (aRegion, name, (BaseValueHolder)anObj);
  }

    protected void addObjectViaPutAll(Region aRegion, String name, boolean logAddition) {
        Object anObj = getObjectToAdd(name);
        HashMap map = new HashMap();
        map.put(name, anObj);
//        String callback = createCallbackPrefix + ProcessMgr.getProcessId();
        if (logAddition)
            Log.getLogWriter().info("addObjectViaPutAll: calling putall for name " + name + ", object " + TestHelper.toString(anObj) + ", region is " + aRegion.getFullPath());
        aRegion.putAll(map);
        long numPut = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_PUTALL_CREATE", BridgeNotifyBB.NUM_PUTALL_CREATE);

        // serialBridgeNotifyMixedInterests needs help knowing how many events to expect
        int numExpectedEvents = 1;               // for ALL_KEYS client
        if (originalKeyList.contains(name)) {    // 1 for odd/even keylist client
           numExpectedEvents++;
        }
        List singleKeyList = (List)BridgeNotifyBB.getBB().getSharedMap().get( BridgeNotifyBB.SINGLE_KEYS_REGISTERED );
        if (singleKeyList.contains(name)) {      // 1 if we selected the singleKey clients key
           numExpectedEvents++;
        }
        BridgeNotifyBB.getBB().getSharedMap().put( BridgeNotifyBB.numListeners, new Integer(numExpectedEvents) );
        query (aRegion, name, (BaseValueHolder)anObj);
    }

    protected void invalidateObject(Region aRegion, boolean isLocalInvalidate) {
        Set aSet = aRegion.keys();
        if (aSet.size() == 0) {
            Log.getLogWriter().info("invalidateObject: No names in region");
            return;
        }

        Object name = null;
        for (Iterator it = aSet.iterator(); it.hasNext();) {
          Object potentialKey = it.next();
          if (aRegion.containsValueForKey(potentialKey)) {
             name = potentialKey;
             break;
          }
        }
        if (name == null) {
           Log.getLogWriter().info("invalidateObject: No entries with value in region");
            return;
        }

        boolean containsValue = aRegion.containsValueForKey(name);
        boolean alreadyInvalidated = !containsValue;
        Log.getLogWriter().info("containsValue for " + name + ": " + containsValue);
        Log.getLogWriter().info("alreadyInvalidated for " + name + ": " + alreadyInvalidated);
        try {
            String callback = invalidateCallbackPrefix + ProcessMgr.getProcessId();
            if (isLocalInvalidate) {
                Log.getLogWriter().info("invalidateObject: local invalidate for " + name + " callback is " + callback);
                aRegion.localInvalidate(name, callback);
                Log.getLogWriter().info("invalidateObject: done with local invalidate for " + name);
                if (!alreadyInvalidated) {
                    long numInvalidate = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_LOCAL_INVALIDATE", BridgeNotifyBB.NUM_LOCAL_INVALIDATE);
                }
            } else {
                Log.getLogWriter().info("invalidateObject: invalidating name " + name + " callback is " + callback);
                aRegion.invalidate(name, callback);
                Log.getLogWriter().info("invalidateObject: done invalidating name " + name);
                if (!alreadyInvalidated) {
                    long numInvalidate = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_INVALIDATE", BridgeNotifyBB.NUM_INVALIDATE);
                }
            }
            if (isCarefulValidation)
                verifyObjectInvalidated(aRegion, name);
        } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
            if (isCarefulValidation)
                throw new TestException(TestHelper.getStackTrace(e));
            else {
                Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
                return;
            }
//        } catch (CacheException e) {
//             throw new TestException(TestHelper.getStackTrace(e));
        }

        int numExpectedEvents = 1;  // for ALL_KEYS client
        if (originalKeyList.contains(name)) {    // 1 for odd/even keylist client
           numExpectedEvents++;
        }
        List singleKeyList = (List)BridgeNotifyBB.getBB().getSharedMap().get( BridgeNotifyBB.SINGLE_KEYS_REGISTERED );
        if (singleKeyList.contains(name)) {
           numExpectedEvents++;
        }

        BridgeNotifyBB.getBB().getSharedMap().put( BridgeNotifyBB.numListeners, new Integer(numExpectedEvents) );
         query(aRegion, name, (BaseValueHolder)null);
    }

    protected void destroyObject(Region aRegion, boolean isLocalDestroy) {
        Set aSet = aRegion.keys();
        Iterator iter = aSet.iterator();
        if (!iter.hasNext()) {
            Log.getLogWriter().info("destroyObject: No names in region");
            return;
        }
        try {
            Object name = iter.next();
            destroyObject(aRegion, name, isLocalDestroy);
        } catch (NoSuchElementException e) {
            throw new TestException("Bug 30171 detected: " + TestHelper.getStackTrace(e));
        }
    }

    private void destroyObject(Region aRegion, Object name, boolean isLocalDestroy) {
        try {
            String callback = destroyCallbackPrefix + ProcessMgr.getProcessId();
            if (isLocalDestroy) {
                Log.getLogWriter().info("destroyObject: local destroy for " + name + " callback is " + callback);
                aRegion.localDestroy(name, callback);
                Log.getLogWriter().info("destroyObject: done with local destroy for " + name);
                long numDestroy = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_LOCAL_DESTROY", BridgeNotifyBB.NUM_LOCAL_DESTROY);
            } else {
                Log.getLogWriter().info("destroyObject: destroying name " + name + " callback is " + callback);
                aRegion.destroy(name, callback);
                Log.getLogWriter().info("destroyObject: done destroying name " + name);
                long numDestroy = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_DESTROY", BridgeNotifyBB.NUM_DESTROY);
            }
        } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
            if (isCarefulValidation)
                throw new TestException(TestHelper.getStackTrace(e));
            else {
                Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
                return;
            }
        } catch (BridgeWriterException bwe) {
            if (isCarefulValidation)
               throw new TestException(TestHelper.getStackTrace(bwe));
            else {  // concurrent tests
              Throwable cause = bwe.getCause();
              if (cause == null)
                 throw new TestException(TestHelper.getStackTrace(bwe));

              if (cause.toString().startsWith("com.gemstone.gemfire.cache.EntryNotFoundException")) {
                 Log.getLogWriter().info("Caught " + bwe + " (expected with concurrent execution); continuing with test");
              } else {
                 throw new TestException(TestHelper.getStackTrace(bwe));
              }
            }
        } 

        // serialBridgeNotifyMixedInterests needs help knowing how many events to expect
        int numExpectedEvents = 1;  // for ALL_KEYS client
        if (originalKeyList.contains(name)) {    // 1 for odd/even keylist client
           numExpectedEvents++;
        }
        List singleKeyList = (List)BridgeNotifyBB.getBB().getSharedMap().get( BridgeNotifyBB.SINGLE_KEYS_REGISTERED );
        if (singleKeyList.contains(name)) {
           numExpectedEvents++;
        }
        BridgeNotifyBB.getBB().getSharedMap().put( BridgeNotifyBB.numListeners, new Integer(numExpectedEvents) );
        query(aRegion, name, (BaseValueHolder)null);
    }

   /**
    *  ConcurrentMap API testing
    **/
   protected void remove(Region aRegion) {
       Set aSet = aRegion.keys();
       Iterator iter = aSet.iterator();
       if (!iter.hasNext()) {
           Log.getLogWriter().info("remove: No names in region");
           return;
       }
       try {
           Object name = iter.next();
           Object oldVal = aRegion.get(name);
           remove(aRegion, name, oldVal);
       } catch (NoSuchElementException e) {
           throw new TestException("Bug 30171 detected: " + TestHelper.getStackTrace(e));
       }
   }

   private void remove(Region aRegion, Object name, Object oldVal) {
       boolean removed = false;
       try {
          Log.getLogWriter().info("remove: removing " + name + " with previous value " + oldVal + ".");
          removed = aRegion.remove(name, oldVal);
          Log.getLogWriter().info("remove: done removing " + name);
          if (removed) {
             long numDestroy = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_DESTROY", BridgeNotifyBB.NUM_DESTROY);
          }
       } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
          if (isCarefulValidation)
              throw new TestException("remove caught Unexpected Exception " + e + "\n" + TestHelper.getStackTrace(e));
          else {
              Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
              return;
          }
        } 

        // serialBridgeNotifyMixedInterests needs help knowing how many events to expect
        if (removed) {
           int numExpectedEvents = 1;  // for ALL_KEYS client
           if (originalKeyList.contains(name)) {    // 1 for odd/even keylist client
              numExpectedEvents++;
           }
           List singleKeyList = (List)BridgeNotifyBB.getBB().getSharedMap().get( BridgeNotifyBB.SINGLE_KEYS_REGISTERED );
           if (singleKeyList.contains(name)) {
              numExpectedEvents++;
           }
           BridgeNotifyBB.getBB().getSharedMap().put( BridgeNotifyBB.numListeners, new Integer(numExpectedEvents) );
           query(aRegion, name, (BaseValueHolder)null);
        }
   }

    protected void destroyAndCreateObject(Region aRegion, boolean isLocalDestroy) {
        Set aSet = aRegion.keys();
        Iterator iter = aSet.iterator();
        if (!iter.hasNext()) {
            Log.getLogWriter().info("destroyObject: No names in region");
            return;
        }
        String name = null;
        try {
            name = (String)iter.next();
            destroyObject(aRegion, name, isLocalDestroy);

            // lhughes - since we want to restore the destroyed entry (but we expect
            // all events (vs. collapsing this destroy/create combo), go ahead and
            // commit the destroy, then open a new tx for the create.
            if (TxHelper.exists()) {
               TxHelper.commit();
            }
        } catch (NoSuchElementException e) {
            throw new TestException("Bug 30171 detected: " + TestHelper.getStackTrace(e));
        }

        if (useTransactions) {
           TxHelper.begin();
        }
        addObject(aRegion, name, true);
        // this will be committed in doEntryOperations() if useTransactions is true
    }

    /**
     * Updates the "first" entry w/a value in a given region
     */

    protected void updateObject(Region aRegion) {
        Set aSet = aRegion.keys();
        Iterator iter = aSet.iterator();
        if (!iter.hasNext()) {
            Log.getLogWriter().info("updateObject: No names in region");
            return;
        }

        Object name = null;
        for (Iterator it = aSet.iterator(); it.hasNext();) {
          Object potentialKey = it.next();
          if (aRegion.containsValueForKey(potentialKey)) {
             name = potentialKey;
             break;
          }
        }
        if (name == null) {
           Log.getLogWriter().info("updateObject: No entries with value in region");
            return;
        }
        updateObject(aRegion, name);
    }

   /**
    * Replaces the "first" entry in a given region
    */
   protected void replace(Region aRegion) {
       Set aSet = aRegion.keys();
       Iterator iter = aSet.iterator();
       if (!iter.hasNext()) {
           Log.getLogWriter().info("replace: No names in region");
           return;
       }

       Object name = null;
       for (Iterator it = aSet.iterator(); it.hasNext();) {
         Object potentialKey = it.next();
         if (aRegion.containsValueForKey(potentialKey)) {
            name = potentialKey;
            break;
         }
       }
       if (name == null) {
          Log.getLogWriter().info("updateObject: No entries with value in region");
           return;
       }
       replace(aRegion, name);
   }
    
   /**
    * Replaces the entry with the given key (<code>name</code>) in the
    * given region.
    */
   protected void replace(Region aRegion, Object name) {
       boolean replaced = false;
       Object anObj = null;
       try {
           anObj = aRegion.get(name);
       } catch (CacheLoaderException e) {
           throw new TestException("replace caught unexpected Exception " + e + "\n" + TestHelper.getStackTrace(e));
       } catch (TimeoutException e) {
           throw new TestException("replace caught unexpected Exception " + e + "\n" + TestHelper.getStackTrace(e));
       }

       Object newObj = getUpdateObject((String)name);
       if (TestConfig.tab().getRandGen().nextBoolean()) { // use oldVal api
          Log.getLogWriter().info("replace: replacing name " + name + " with " + TestHelper.toString(newObj) + "; old value is " + TestHelper.toString(anObj) + ".");
          replaced = aRegion.replace(name, anObj, newObj);
       } else {  // use replace(name, newVal) api
          Log.getLogWriter().info("replace: replacing name " + name + " with " + TestHelper.toString(newObj) + ".");
          Object returnVal = aRegion.replace(name, newObj);
          if (returnVal != null) {
            replaced = true;
          }
       }
       Log.getLogWriter().info("Done with call to replace");

       long numUpdate = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_UPDATE", BridgeNotifyBB.NUM_UPDATE);

        // serialBridgeNotifyMixedInterests needs help knowing how many events to expect
        int numExpectedEvents = 1;  // for ALL_KEYS client
        if (originalKeyList.contains(name)) {    // 1 for odd/even keylist client
           numExpectedEvents++;
        }
        List singleKeyList = (List)BridgeNotifyBB.getBB().getSharedMap().get( BridgeNotifyBB.SINGLE_KEYS_REGISTERED );
        if (singleKeyList.contains(name)) {
           numExpectedEvents++;
        }
        BridgeNotifyBB.getBB().getSharedMap().put( BridgeNotifyBB.numListeners, new Integer(numExpectedEvents) );
        query(aRegion, name, (BaseValueHolder)newObj);
   }
       
    protected void updateObjectViaPutAll(Region aRegion) {
        Set aSet = aRegion.keys();
        Iterator iter = aSet.iterator();
        if (!iter.hasNext()) {
            Log.getLogWriter().info("updateObject: No names in region");
            return;
        }

        Object name = null;
        for (Iterator it = aSet.iterator(); it.hasNext();) {
          Object potentialKey = it.next();
          if (aRegion.containsValueForKey(potentialKey)) {
             name = potentialKey;
             break;
          }
        }
        if (name == null) {
           Log.getLogWriter().info("updateObject: No entries with value in region");
            return;
        }
        updateObjectViaPutAll(aRegion, name);
    }

    /**
     * Updates the entry with the given key (<code>name</code>) in the
     * given region.
     */
    protected void updateObject(Region aRegion, Object name) {
        Object anObj = null;
        try {
            anObj = aRegion.get(name);
        } catch (CacheLoaderException e) {
            throw new TestException(TestHelper.getStackTrace(e));
        } catch (TimeoutException e) {
            throw new TestException(TestHelper.getStackTrace(e));
        }
        Object newObj = getUpdateObject((String)name);
        String callback = updateCallbackPrefix + ProcessMgr.getProcessId();
        Log.getLogWriter().info("updateObject: replacing name " + name + " with " +
                TestHelper.toString(newObj) + "; old value is " + TestHelper.toString(anObj) +
                ", callback is " + callback);
        aRegion.put(name, newObj, callback);
        Log.getLogWriter().info("Done with call to put (update)");

        long numUpdate = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_UPDATE", BridgeNotifyBB.NUM_UPDATE);

        // serialBridgeNotifyMixedInterests needs help knowing how many events to expect
        int numExpectedEvents = 1;  // for ALL_KEYS client
        if (originalKeyList.contains(name)) {    // 1 for odd/even keylist client
           numExpectedEvents++;
        }
        List singleKeyList = (List)BridgeNotifyBB.getBB().getSharedMap().get( BridgeNotifyBB.SINGLE_KEYS_REGISTERED );
        if (singleKeyList.contains(name)) {
           numExpectedEvents++;
        }
        BridgeNotifyBB.getBB().getSharedMap().put( BridgeNotifyBB.numListeners, new Integer(numExpectedEvents) );
        query(aRegion, name, (BaseValueHolder)newObj);
    }

    protected void updateObjectViaPutAll(Region aRegion, Object name) {
        Object anObj = null;
        try {
            anObj = aRegion.get(name);
        } catch (CacheLoaderException e) {
            throw new TestException(TestHelper.getStackTrace(e));
        } catch (TimeoutException e) {
            throw new TestException(TestHelper.getStackTrace(e));
        }
        Object newObj = getUpdateObject((String)name);
        HashMap map = new HashMap();
        map.put(name, newObj);
//            String callback = updateCallbackPrefix + ProcessMgr.getProcessId();
        Log.getLogWriter().info("updateObjectViaPutAll: replacing name " + name + " with " +
                TestHelper.toString(newObj) + "; old value is " + TestHelper.toString(anObj));
        
        aRegion.putAll(map);
        Log.getLogWriter().info("Done with call to putall (update)");

        long numUpdate = BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_PUTALL_UPDATE", BridgeNotifyBB.NUM_PUTALL_UPDATE);

        // serialBridgeNotifyMixedInterests needs help knowing how many events to expect
        int numExpectedEvents = 1;  // for ALL_KEYS client
        if (originalKeyList.contains(name)) {    // 1 for odd/even keylist client
           numExpectedEvents++;
        }
        List singleKeyList = (List)BridgeNotifyBB.getBB().getSharedMap().get( BridgeNotifyBB.SINGLE_KEYS_REGISTERED );
        if (singleKeyList.contains(name)) {
           numExpectedEvents++;
        }
        BridgeNotifyBB.getBB().getSharedMap().put( BridgeNotifyBB.numListeners, new Integer(numExpectedEvents) );
        query(aRegion, name, (BaseValueHolder)newObj);
    }

    /**
     * Fetches (reads) the value of a randomly selected entry in the
     * given region.
     */
    protected void readObject(Region aRegion) {
        Set aSet = aRegion.keys();
        if (aSet.size() == 0) {
            Log.getLogWriter().info("readObject: No names in region");
            return;
        }
        long maxNames = NameFactory.getPositiveNameCounter();
        if (maxNames <= 0) {
            Log.getLogWriter().info("readObject: max positive name counter is " + maxNames);
            return;
        }
        Object name = NameFactory.getObjectNameForCounter(TestConfig.tab().getRandGen().nextInt(1, (int)maxNames));
        Log.getLogWriter().info("readObject: getting name " + name);
        try {
            Object anObj = aRegion.get(name);
            Log.getLogWriter().info("readObject: got value for name " + name + ": " + TestHelper.toString(anObj));
        } catch (CacheLoaderException e) {
            throw new TestException(TestHelper.getStackTrace(e));
        } catch (TimeoutException e) {
            throw new TestException(TestHelper.getStackTrace(e));
        }
        query(aRegion, name);
    }

    public static void HydraTask_createAllKeys() {
       Region rootRegion = CacheHelper.getCache().getRegion(BridgeNotify.REGION_NAME);
       bridgeClient.createAllKeys(rootRegion);
    }

    public static void HydraTask_createAllKeysViaPutAll() {
        Region rootRegion = CacheHelper.getCache().getRegion(BridgeNotify.REGION_NAME);
        bridgeClient.createAllKeysViaPutAll(rootRegion);
     }
  
    protected void createAllKeys(Region aRegion) {
       // We need to locally destroy all these keys if we truly want a create
       // and not an update
       for (int i=1; i <= numKeysInTest; i++) {
          Object name = NameFactory.getObjectNameForCounter(i);
          aRegion.localDestroy(name, "ignore localDestroy");
       }

       for (int i=1; i <= numKeysInTest; i++) {
          TestHelper.checkForEventError(EventCountersBB.getBB());
          String name = NameFactory.getObjectNameForCounter(i);
          addObject(aRegion, name, true);
          checkEventCounters();
       }
    }
    
    protected void createAllKeysViaPutAll(Region aRegion) {
      // We need to locally destroy all these keys if we truly want a create
      // and not an update
      for (int i=1; i <= numKeysInTest; i++) {
        Object name = NameFactory.getObjectNameForCounter(i);
        aRegion.localDestroy(name, "ignore localDestroy");
      }

      for (int i=1; i <= numKeysInTest; i++) {
        TestHelper.checkForEventError(EventCountersBB.getBB());
        String name = NameFactory.getObjectNameForCounter(i);
        addObjectViaPutAll(aRegion, name, true);
        checkEventCounters();
      }
    }

    public static void HydraTask_updateAllKeys() {
       Region rootRegion = CacheHelper.getCache().getRegion(BridgeNotify.REGION_NAME);
       bridgeClient.updateAllKeys(rootRegion);
    }

    public static void HydraTask_updateAllKeysViaPutAll() {
      Region rootRegion = CacheHelper.getCache().getRegion(BridgeNotify.REGION_NAME);
      bridgeClient.updateAllKeysViaPutAll(rootRegion);
    }
  
    protected void updateAllKeys(Region aRegion) {
       for (int i=1; i <= numKeysInTest; i++) {
          TestHelper.checkForEventError(EventCountersBB.getBB());
          Object name = NameFactory.getObjectNameForCounter(i);
          updateObject(aRegion, name);
          checkEventCounters();
       }
    }
 
    protected void updateAllKeysViaPutAll(Region aRegion) {
      for (int i=1; i <= numKeysInTest; i++) {
         TestHelper.checkForEventError(EventCountersBB.getBB());
         Object name = NameFactory.getObjectNameForCounter(i);
         updateObjectViaPutAll(aRegion, name);
         checkEventCounters();
      }
    }

    public static void HydraTask_destroyAllKeys() {
       Region rootRegion = CacheHelper.getCache().getRegion(BridgeNotify.REGION_NAME);
       bridgeClient.destroyAllKeys(rootRegion);
    }
      
    protected void destroyAllKeys(Region aRegion) {
       for (int i=1; i <= numKeysInTest; i++) {
          TestHelper.checkForEventError(EventCountersBB.getBB());
          Object name = NameFactory.getObjectNameForCounter(i);
          destroyObject(aRegion, name, false);
          checkEventCounters();
       }
    }

    // methods that can be overridden for a more customized test

    /**
     * Must overridden in a subclass
     */
    protected void checkEventCounters() {
        throw new TestException("checkEventCounters must be implemented in a subclass");
    }

    /**
     * Creates a new object with the given <code>name</code> to add to a
     * region.
     *
     * @see BaseValueHolder
     * @see RandomValues
     */
    protected Object getObjectToAdd(String name) {
        BaseValueHolder anObj = createObject(name, randomValues)/*new ValueHolder(name, randomValues)*/;
        return anObj;
    }

    /**
     * Returns the "updated" value of the object with the given
     * <code>name</code>.
     *
     * @see BaseValueHolder#getAlternateValueHolder
     */
    protected Object getUpdateObject(String name) {
        Region rootRegion = CacheHelper.getCache().getRegion(REGION_NAME);
        BaseValueHolder anObj = null;
        BaseValueHolder newObj = null;
        try {
          anObj = (BaseValueHolder)rootRegion.get(name);
        } catch (CacheLoaderException e) {
          throw new TestException(TestHelper.getStackTrace(e));
        } catch (TimeoutException e) {
          throw new TestException(TestHelper.getStackTrace(e));
        }
        newObj = (anObj == null) ? createObject(name, randomValues)/*new ValueHolder(name, randomValues)*/ :
          anObj.getAlternateValueHolder(randomValues);
        return newObj;
    }

    protected int getNumVMsWithListeners() {
        throw new TestException("getNumVMsWithListeners must be implemented in a subclass");
    }

    // ========================================================================
    // end task methods

    public static void HydraTask_printBB() throws Throwable {
        CacheBB.getBB().print();
        BridgeNotifyBB.getBB().print();

        EventCountersBB.getBB().print();
        TestHelper.checkForEventError(EventCountersBB.getBB());
    }

    public static void HydraTask_endTask() throws Throwable {
        TestHelper.checkForEventError(EventCountersBB.getBB());
        CacheBB.getBB().print();
        BridgeNotifyBB.getBB().print();
        EventCountersBB.getBB().print();

        bridgeClient = new BridgeNotify();
        bridgeClient.initialize();
        StringBuffer errStr = new StringBuffer();
        try {
            bridgeClient.checkEventCounters();
        } 
        catch (VirtualMachineError e) {
          SystemFailure.initiateFailure(e);
          throw e;
        }
        catch (Throwable e) {
            Log.getLogWriter().info(e.toString());
            errStr.append(e.toString());
        }
        if (errStr.length() > 0)
            throw new TestException(errStr.toString());
        TestHelper.checkForEventError(EventCountersBB.getBB());
    }

    // ========================================================================
    // other methods

    /** Return a random operation from the hydra parameter specified by whichPrm.
     *
     *  @param whichPrm - the hydra parameter that is a list of operations
     *  @param disallowLocalEntryOps - if true, then do not return localInvalidate
     *         or localDestroy. This is used for regions that are mirrored, since
     *         localInvalidate and localDestroy are disallowed for mirrored regions.
     */

    protected int getOperation(Long whichPrm, boolean disallowLocalEntryOps) {
        long limit = 60000;
        long startTime = System.currentTimeMillis();
        int op = 0;
        do {
            String operation = TestConfig.tab().stringAt(whichPrm);
            if (operation.equals("add"))
                op =  ADD_OPERATION;
            else if (operation.equals("update"))
                op =  UPDATE_OPERATION;
            else if (operation.equals("putalladd"))
                op =  PUTALL_ADD_OPERATION;
            else if (operation.equals("putallupdate"))
                op =  PUTALL_UPDATE_OPERATION;
            else if (operation.equals("invalidate"))
                op =  INVALIDATE_OPERATION;
            else if (operation.equals("destroy"))
                op =  DESTROY_OPERATION;
            else if (operation.equals("read"))
                op =  READ_OPERATION;
            else if (operation.equals("localInvalidate"))
                op =  LOCAL_INVALIDATE_OPERATION;
            else if (operation.equals("localDestroy"))
                op =  LOCAL_DESTROY_OPERATION;
            else if (operation.equals("close"))
                op = REGION_CLOSE_OPERATION;
            else if (operation.equals("clear"))
                op = CLEAR_OPERATION;
            else if (operation.equals("destroyCreate"))
                op = DESTROY_CREATE_OPERATION;
			     else if (operation.equals("cacheClose"))
				        op = CACHE_CLOSE_OPERATION;
			     else if (operation.equals("killVM"))
				        op = KILL_VM_OPERATION;
            else if (operation.equals("putIfAbsent"))
                op = PUT_IF_ABSENT_OPERATION;
            else if (operation.equals("remove"))
                op = REMOVE_OPERATION;
            else if (operation.equals("replace"))
                op = REPLACE_OPERATION;
            else
                throw new TestException("Unknown entry operation: " + operation);
            if (System.currentTimeMillis() - startTime > limit) {
                // could not find a suitable operation in the time limit; there may be none available
                throw new TestException("Could not find an operation in " + limit + " millis; disallowLocalEntryOps is " + true + "; check that the operations list has allowable choices");
            }
        } while (disallowLocalEntryOps && ((op == LOCAL_INVALIDATE_OPERATION) || (op == LOCAL_DESTROY_OPERATION)));
        return op;
    }

    static protected void logExecutionNumber() {
        long exeNum = BridgeNotifyBB.getBB().getSharedCounters().incrementAndRead(BridgeNotifyBB.EXECUTION_NUMBER);
        Log.getLogWriter().info("Beginning task with execution number " + exeNum);
    }

    /** Verify that the given key in the given region is invalid (has a null value).
     *  If not, then throw an error. Checking is done locally, without invoking a
     *  cache loader or doing a net search.
     *
     *  @param aRegion The region containing key
     *  @param key The key that should have a null value
     */
    protected void verifyObjectInvalidated(Region aRegion, Object key) {
        StringBuffer errStr = new StringBuffer();
        boolean containsKey = aRegion.containsKey(key);
        if (!containsKey)
            errStr.append("Unexpected containsKey " + containsKey + " for key " + key + " in region " +
                    TestHelper.regionToString(aRegion, false) + "\n");
        boolean containsValueForKey = aRegion.containsValueForKey(key);
        if (containsValueForKey)
            errStr.append("Unexpected containsValueForKey " + containsValueForKey + " for key " + key +
                    " in region " + TestHelper.regionToString(aRegion, false) + "\n");
        Region.Entry entry = aRegion.getEntry(key);
        if (entry == null)
            errStr.append("getEntry for key " + key + " in region " + TestHelper.regionToString(aRegion, false) +
                    " returned null\n");
        Object entryKey = entry.getKey();
        if (!entryKey.equals(key))
            errStr.append("getEntry.getKey() " + entryKey + " does not equal key " + key + " in region " +
                    TestHelper.regionToString(aRegion, false) + "\n");
        Object entryValue = entry.getValue();
        if (entryValue != null)
            errStr.append("Expected getEntry.getValue() " + TestHelper.toString(entryValue) + " to be null.\n");
        if (errStr.length() > 0)
            throw new TestException(errStr.toString());
    }

    /** Verify that the given key in the given region is destroyed (has no key/value).
     *  If not, then throw an error. Checking is done locally, without invoking a
     *  cache loader or doing a net search.
     *
     *  @param aRegion The region contains the destroyed key
     *  @param key The destroyed key
     */
    protected void verifyObjectDestroyed(Region aRegion, Object key) {
        StringBuffer errStr = new StringBuffer();
        boolean containsKey = aRegion.containsKey(key);
        if (containsKey)
            errStr.append("Unexpected containsKey " + containsKey + " for key " + key + " in region " + TestHelper.regionToString(aRegion, false) + "\n");
        boolean containsValueForKey = aRegion.containsValueForKey(key);
        if (containsValueForKey)
            errStr.append("Unexpected containsValueForKey " + containsValueForKey + " for key " + key +
                    " in region " + TestHelper.regionToString(aRegion, false) + "\n");
        Region.Entry entry = aRegion.getEntry(key);
        if (entry != null)
            errStr.append("getEntry for key " + key + " in region " + TestHelper.regionToString(aRegion, false) +
                    " returned was non-null; getKey is " + entry.getKey() + ", value is " +
                    TestHelper.toString(entry.getValue()) + "\n");
        if (errStr.length() > 0)
            throw new TestException(errStr.toString());
    }

  /**
   * A Hydra ENDTASK to validate events (expected vs. actual)
   */
  public static void HydraTask_validateEventsReceived() {
    bridgeClient.validateEventsReceived();
  }

  /**
   * see HydraTask_validateEventsReceived
   */
  protected void validateEventsReceived() {
    Log.getLogWriter().info("invoked validateEventReceived()");
    // todo@lhughes -- endtask code here
  }

  /**
   *  A Hydra TASK that will kill and restart random bridgeServers.
   */
  public static void HydraTask_recycleServer()
  throws ClientVmNotFoundException {
    List endpoints = BridgeHelper.getEndpoints();
    for (int i = 0; i < endpoints.size() - 1; i++) {
      // Kill the next server
      PRObserver.installObserverHook();
      PRObserver.initialize();
      BridgeHelper.Endpoint endpoint = (BridgeHelper.Endpoint)endpoints.get(i);
      ClientVmInfo target = new ClientVmInfo(endpoint);
      ClientVmMgr.stop("HydraTask_recycleServer: " + endpoint,
                        ClientVmMgr.NICE_EXIT, ClientVmMgr.ON_DEMAND, target);
      MasterController.sleepForMs(5000);
      ClientVmMgr.start("HydraTask_recycleServer, restarting", target);
      Object value = BridgeNotifyBB.getBB().getSharedMap().get("expectRecovery");
      if (value instanceof Boolean) {
         if (((Boolean)value).booleanValue()) {
            PRObserver.waitForRebalRecov(target, 1, 1, null, null, false);
         }
      }
    }
  }

	/**
	 * This function perform close() operation on the given region. 
	 * It also validates size of region before and after close operation.
	 * @param aRegion
	 */
	protected void closeRegion(Region aRegion) {

		Log.getLogWriter().info(
				"closeRegion : closing region  " + aRegion.getFullPath());
		BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_CLOSE",
				BridgeNotifyBB.NUM_CLOSE);
		int expectedSize = aRegion.entries(true).size();
		aRegion.close();
		Region newRegion = initRegion();
		int actualSize = newRegion.entries(true).size();
		if (actualSize != expectedSize) {
			String errStr = "Actual size " + actualSize + " of region "
					+ aRegion.getFullPath() + " is not equal to expected size "
					+ expectedSize;
			throw new TestException(errStr.toString());
		}

		Log.getLogWriter().info(
				"Expected size  : " + expectedSize + " Actual size : "
						+ actualSize);

	}

	/**
	 * This function perform cacheclose() operation. 
	 * It Creates new cache and region with same name which was before cache closing and it 
	 * validates size of region before and after cache close.
	 */
	protected void closeCache() {

		Log.getLogWriter().info("closing Cache");
		BridgeNotifyBB.incrementCounter("BridgeNotifyBB.NUM_CLOSE",
				BridgeNotifyBB.NUM_CLOSE);
		Region oldRegion = CacheHelper.getCache().getRegion(
				Region.SEPARATOR + REGION_NAME);
		int expectedSize = oldRegion.entries(true).size();
		CacheHelper.closeCache();
		initialize();
		BridgeNotifyBB.getBB().getSharedCounters().decrementAndRead(
				BridgeNotifyBB.NUM_LISTENERS);
		Region newRegion = CacheHelper.getCache().getRegion(
				Region.SEPARATOR + REGION_NAME);
		int actualSize = newRegion.entries(true).size();
		if (actualSize != expectedSize) {
			String errStr = "Actual size " + actualSize + " of region "
					+ newRegion.getFullPath()
					+ " is not equal to expected size " + expectedSize;
			throw new TestException(errStr.toString());
		}

		Log.getLogWriter().info(
				"Expected size  : " + expectedSize + " Actual size : "
						+ actualSize);
	}


	protected void killVM() {
          throw new UnsupportedOperationException("Not implemented yet");
	}

  protected Region initRegion() {

    isSerialExecution = TestConfig.tab().booleanAt( hydra.Prms.serialExecution, false );
    isCarefulValidation = isCarefulValidation || isSerialExecution;
    minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec);
    minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;

    //TODO:ASIF:CHECK THE SIGNIFICANMCE OF PREVIOUSLY boolean value passed in the constructor

    randomValues = new RandomValues();

    // create the lists of original, even & odd keys
    ArrayList oddKeys = new ArrayList();
    ArrayList evenKeys = new ArrayList();
    for (int i = 1; i <= numKeysInTest; i++) {
       Object key = NameFactory.getObjectNameForCounter(i);
       originalKeyList.add(key);
       if ((i % 2) == 0) { // key has even number
          evenKeys.add(key);
       } else {
          oddKeys.add(key);
       }
    }

    // create region with programmatic cache listener, register interest
    Region aRegion = null;
    synchronized (BridgeNotify.class) {
      if (CacheHelper.getCache() != null) {
        AttributesFactory factory = RegionHelper.getAttributesFactory(ConfigPrms.getRegionConfig());
        CacheListener myListener = BridgeNotifyPrms.getClientListener();
        if (myListener != null) {
          factory.setCacheListener( myListener );
        }
        aRegion = RegionHelper.createRegion(REGION_NAME, factory);
        registerInterest(aRegion, myListener, oddKeys, evenKeys);
        //BridgeNotifyBB.getBB().getSharedCounters().incrementAndRead(BridgeNotifyBB.NUM_LISTENERS);
      }
    }
    return aRegion;
  }
  
  protected BaseValueHolder createObject(Object key, RandomValues rdmVal) {
    if (objectType.equals("delta.DeltaValueHolder")) {
      return new DeltaValueHolder((String)key, rdmVal);
    }
    else {
      return new ValueHolder((String)key, rdmVal);
    }
  }
}
