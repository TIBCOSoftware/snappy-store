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
package cq;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.GemFireCache;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TransactionDataNodeHasDepartedException;
import com.gemstone.gemfire.cache.TransactionDataRebalancedException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.TransactionInDoubtException;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.pdx.PdxInstance;
import hydra.*;
import pdx.PdxTest;
import pdx.PdxTestVersionHelper;
import util.CacheUtil;
import util.TestException;
import util.TestHelper;
import util.TestHelperPrms;
import util.TxHelper;

/**
 * @author lhughes
 * This class contains code that references classes (TransactionExceptions) which exist in 
 * 6.6 (and beyond) but are not available in pre-6.6 versions.
 *
 */

public class CQTestVersionHelper {

protected CQTest testInstance;  // The single CQTest instance in this VM 
protected boolean isClientCache = false;    // true when this VMs cache and regions are created via ClientCache and ClientRegionFactory methods, false otherwise.

// constructor
public CQTestVersionHelper(CQTest testInstance) {
   this.testInstance = testInstance;
}

/**
 *  Create a region with the given region description name.
 */
public static void setTxMgr() {
   if (getInitialImage.InitImagePrms.useTransactions()) {
      TxHelper.setTransactionManager();
   }
}
/**
 *  Get the Cache (using CacheFactory or ClientCacheFactory as needed)
 */
public GemFireCache getCache() {
   GemFireCache cache = (isClientCache) ? ClientCacheHelper.getCache() : CacheHelper.getCache();
   return cache;
}

/**
 *  Close the cache (using CacheFactory or ClientCacheFactory as needed)
 *
 */
public void closeCache() {
   if (isClientCache) {
      ClientCacheHelper.closeCache();
   } else {
      CacheHelper.closeCache();
   }
}

/**
 *  Create a region with the given region description name.
 *
 *  @param regDescriptName The name of a region description.
 */
public Region initializeRegion(String regDescriptName) {

   Vector clientCacheNames = TestConfig.tab().vecAt(ClientCachePrms.names, null);

   Vector bridgeNames = TestConfig.tab().vecAt(BridgePrms.names, null);
   boolean isBridgeConfiguration = bridgeNames != null;
   if (isBridgeConfiguration && regDescriptName.toLowerCase().startsWith("client")) {
     if (clientCacheNames != null) {
       isClientCache = true;
     }
   }
   String key = testInstance.VmIDStr + RemoteTestModule.getMyVmid();
   String xmlFile = key + ".xml";
   File aFile = new File(xmlFile);
   if (!aFile.exists()) {
      if (isClientCache) {
        ClientCacheHelper.createCache("clientCache");
        ClientCacheHelper.generateCacheXmlFile("clientCache", regDescriptName, xmlFile);
      } else {
        DiskStoreDescription dsd = RegionHelper.getRegionDescription(regDescriptName).getDiskStoreDescription();
        String diskStoreName = null;
        if (dsd != null) {
            diskStoreName = dsd.getName();
        }
        Log.getLogWriter().info("About to generate xml, diskStoreName is " + diskStoreName);
        CacheHelper.createCache("cache1");
        CacheHelper.generateCacheXmlFile("cache1", null, regDescriptName, null, null, null, diskStoreName, null, xmlFile);
      }
   }
   Region aRegion = null;
   if (isClientCache) {
     aRegion = CacheUtil.createClientRegion("clientCache", regDescriptName, xmlFile);
   } else {
     aRegion = CacheUtil.createRegion("cache1", regDescriptName, xmlFile);
   }

   Log.getLogWriter().info("After creating " + aRegion.getFullPath() + ", region is size " + aRegion.size());
   return aRegion;
}

public void initializeQueryService() {
   try {

      String usingPool = TestConfig.tab().stringAt(CQUtilPrms.QueryServiceUsingPool, "false");
      boolean queryServiceUsingPool = Boolean.valueOf(usingPool).booleanValue();
      if (isClientCache) {
        Pool pool = PoolHelper.createPool(CQUtilPrms.getQueryServicePoolName());
        testInstance.qService = ClientCacheHelper.getCache().getQueryService(pool.getName());
        Log.getLogWriter().info("Initializing QueryService using ClientCache and Pool: " + pool.getName());
      } else if (queryServiceUsingPool) {
        Pool pool = PoolHelper.createPool(CQUtilPrms.getQueryServicePoolName());
        testInstance.qService = pool.getQueryService();
        Log.getLogWriter().info("Initializing QueryService using Pool. PoolName: " + pool.getName());
      } else {
        testInstance.qService = CacheHelper.getCache().getQueryService();
        Log.getLogWriter().info("Initializing QueryService using Cache.");
      }
      Log.getLogWriter().info("Done creating QueryService");
   } catch (Exception e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
}

/** Do random transactional entry operations on the given region ending either with
 *  minTaskGranularityMS or numOpsPerTask.  (Since 6.6)
 *
 *  Uses CQUtilPrms.entryOperations to determine the operations to execute.
 */
protected void doEntryOperations(Region aRegion) {
   Log.getLogWriter().info("In doEntryOperations with " + aRegion.getFullPath());

   int numOpsPerTask = TestConfig.tab().intAt(CQUtilPrms.numOpsPerTask, Integer.MAX_VALUE);
   long minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec, Long.MAX_VALUE);
   long minTaskGranularityMS;
   if (minTaskGranularitySec == Long.MAX_VALUE) {
      minTaskGranularityMS = Long.MAX_VALUE;
   } else {
      minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;
   }
   boolean isSerialExecution = TestConfig.tab().booleanAt(hydra.Prms.serialExecution);
   boolean highAvailability = TestConfig.tab().booleanAt(CQUtilPrms.highAvailability, false);
   boolean isBridgeClient = (aRegion.getAttributes().getPoolName()!=null) ? true : false;

   long startTime = System.currentTimeMillis();
   int numOps = 0;

   // useTransactions() defaults to false
   boolean useTransactions = getInitialImage.InitImagePrms.useTransactions();
   boolean rolledback;

   // test hook to obtain operation (region, key and op) for current tx
   if (useTransactions && highAvailability && isBridgeClient) {
      // TODO: TX: need to redo with new TX impl
      //TxHelper.recordClientTXOperations();
   }

   do {

      rolledback = false;
      if (useTransactions) {
	TxHelper.begin();
        if (isBridgeClient && isSerialExecution) {
          testInstance.saveRegionSnapshot();
        }
      }

	      try {
		 testInstance.doRandomOp(aRegion);
	      } catch (TransactionDataNodeHasDepartedException e) {
		if (!useTransactions || (useTransactions && !highAvailability)) {
		  throw new TestException("Unexpected Exception " + e + ", " + TestHelper.getStackTrace(e));
		} else {
		  Log.getLogWriter().info("Caught Exception " + e + ".  Expected with HA, continuing test.");
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
		  Log.getLogWriter().info("Rolling back transaction.");
		  try {
		    TxHelper.rollback();
		    Log.getLogWriter().info("Done Rolling back Transaction");
		  } catch (TransactionException te) {
		    Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching TransactionDataNodeHasDeparted during tx ops.  Expected, continuing test.");
		  }
		  rolledback = true;
		}
	      }

	      if (useTransactions && !rolledback) {
		try {
		  TxHelper.commit();
		} catch (TransactionDataNodeHasDepartedException e) {
		  if (!highAvailability) {
		     throw new TestException("Unexpected Exception " + e + ", " + TestHelper.getStackTrace(e));
		  } else { // high availability test
		     Log.getLogWriter().info("Caught Exception " + e + " on commit.  Expected with HA, continuing test.");
		     if (isSerialExecution && isBridgeClient) {
			testInstance.restoreRegionSnapshot();
		     }
		  }
		} catch (TransactionDataRebalancedException e) {
		  if (highAvailability) {
		     Log.getLogWriter().info("Caught Exception " + e + ".  Expected with HA, continuing test.");
		     if (isSerialExecution && isBridgeClient) {
			testInstance.restoreRegionSnapshot();
		     }
		  }
        } catch (TransactionInDoubtException e) {
          if (!highAvailability) {
            Log.getLogWriter().info("Caught TransactionInDoubtException.  Expected with concurrent execution, continuing test.");
          } else {
             Log.getLogWriter().info("Caught Exception " + e + " on commit.  Expected with concurrent execution, continuing test.");
             if (isSerialExecution && isBridgeClient) {
               testInstance.restoreRegionSnapshot();
             }
             // Known to cause data inconsistency, keep track of keys involved in TxInDoubt transactions on the BB
             recordFailedOps(CQUtilBB.INDOUBT_TXOPS);
          }
        } catch (ConflictException e) {
          if (isSerialExecution) {  // only one tx active, so we should have no conflicts
            throw new TestException("Unexpected " + e + " " + TestHelper.getStackTrace(e));
          } else { // can occur with concurrent execution
            Log.getLogWriter().info("Caught Exception " + e + " on commit. Expected with concurrent execution, continuing test.");
          }
        }
      }

      numOps++;
      Log.getLogWriter().info("Completed op " + numOps + " for this task, region size is " + aRegion.size());
      CQGatherListener.checkForError();
   } while ((System.currentTimeMillis() - startTime < minTaskGranularityMS) &&
            (numOps < numOpsPerTask));
   Log.getLogWriter().info("Done in doEntryOperations with " + aRegion.getFullPath() + ", completed " +
       numOps + " ops in " + (System.currentTimeMillis() - startTime) + " millis");
}

/** Use TxHelper.getClientTXOperations() maintain a list of keys for transactions that failed with
 *  TransactionInDoubtExceptions
 *
 */
protected void recordFailedOps(String sharedMapKey) {
   // TODO: TX: need to redo with new TX impl
   /*
   List opList = TxHelper.getClientTXOperations();
   Iterator it = opList.iterator();
   while (it.hasNext()) {
     TransactionalOperation op = (TransactionalOperation)it.next();
     Log.getLogWriter().info("TranasctionalOperation = " + op.toString());
     if (op.getKey() != null) {
        CQUtilBB.getBB().addFailedOp(sharedMapKey, op.getKey());
     }
   }
   */
}

/** Put a region snapshot into the blackboard. If the values in the snapshot
 *  are PdxSerializables they cannot be put to the blackboard since hydra
 *  MasterController does not have them on the classPath. In this case 
 *  make an alternate map with values being Maps of field/field values.
 *  Note: either all values are PdxSerializables or all values are not.
 * @param snapshot The snapshot to write to the blackboard; this might contain
 *        PdxSerializables. 
 */
public static void putSnapshot(Map snapshot) {
  Map alteredSnapshot = new HashMap();
  for (Object key: snapshot.keySet()) {
    Object value = snapshot.get(key);
    if (value == null) {
       alteredSnapshot.put(key, value);
    } else if (value instanceof PdxInstance) {
      alteredSnapshot.put(key, PdxTestVersionHelper.toBaseObject(value));
    } else { 
      String className = value.getClass().getName();
      if (className.equals("util.PdxVersionedQueryObject") ||
          className.equals("util.VersionedQueryObject")) {
        Map fieldMap = PdxTest.getFieldMap(value);
        alteredSnapshot.put(key, fieldMap);
      } else {
        alteredSnapshot.put(key, value);
      }
    }
  }
  CQUtilBB.getBB().getSharedMap().put(CQUtilBB.RegionSnapshot, alteredSnapshot);
}

}
