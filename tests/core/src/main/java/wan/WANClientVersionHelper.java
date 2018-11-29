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

package wan;

import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TransactionDataNodeHasDepartedException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.TransactionInDoubtException;
import hydra.CacheHelper;
import hydra.DiskStoreHelper;
import hydra.GsRandom;
import hydra.Log;
import hydra.MasterController;
import hydra.RegionHelper;
import hydra.TestConfig;
import objects.ObjectHelper;
import pdx.PdxTest;
import util.BaseValueHolder;
import util.NameFactory;
import util.RandomValues;
import util.TestException;
import util.TestHelper;
import util.TxHelper;

/**
 * VersionHelper class for WANClient (TXInDoubtExceptions prevent backward compatibility in test code)
 *
 * @author Lynn Hughes-Godfrey
 * @since 6.6.2
 */
public class WANClientVersionHelper {

  protected static final String REGION_NAME
            = NameFactory.REGION_NAME_PREFIX + "GlobalVillage";

  static GsRandom rand = new GsRandom();
  static RandomValues rv = new RandomValues();

  protected static final int ITERATIONS = CacheClientPrms.getIterations();

  //============================================================================
  // TASKS
  //============================================================================

  /** Puts integers into the cache with a globally increasing shared counter as the key. Does 1000 updates on each key. */
  public static void putSequentialKeysTask(WANClient client) throws Exception {
    PdxTest.initClassLoader();
    int putAllOps = 5; //1 out of 5 chances to perform putAll operation
    client.startNoKillZone();
    //add putAll task
    Region region = RegionHelper.getRegion(REGION_NAME);    
    int sleepMs = CacheClientPrms.getSleepSec() * 1000;
    String objectType = CacheClientPrms.getObjectType();
    
    boolean useTransactions = getInitialImage.InitImagePrms.useTransactions();
    boolean rolledback;

    for (int j = 0; j < CacheClientPrms.getBatchSize(); j++) {

      rolledback = false;
      if (useTransactions) {
        TxHelper.begin();
      }

      try {
        int doPutAll = rand.nextInt(putAllOps);
        if (doPutAll == 1) {
          WANClient.putSequentialKeyUsingPutAll(region, sleepMs);
        } else {
          String key = ""+WANBlackboard.getInstance().getSharedCounters().incrementAndRead(WANBlackboard.currentEntry);
          //logger.info("WORKINGONKEY:"+key);
          for (int i = 1; i <= ITERATIONS; i++) {
            MasterController.sleepForMs(sleepMs);
            if (objectType == null) {
              region.put(key, new Integer(i));
            } else if ((objectType.equals("util.PdxVersionedValueHolder")) ||
                       (objectType.equals("util.VersionedValueHolder"))) {
              BaseValueHolder vh = PdxTest.getVersionedValueHolder(objectType, new Integer(i), rv);
              region.put(key, vh);
            } else {
              Object val = ObjectHelper.createObject(objectType, i);
              if (TestConfig.tab().getRandGen().nextBoolean()) {
                region.put(key, val);
              } else {
                Object v = region.replace(key, val);
                if (v == null) { // force replacement
                  region.put(key, val);
                }
              }
            }
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
      }

      if (useTransactions && !rolledback) {
        try {
          TxHelper.commit();
        } catch (TransactionDataNodeHasDepartedException e) {
          Log.getLogWriter().info("Caught TransactionDataNodeHasDepartedException.  Expected with concurrent execution, continuing test.");
        } catch (TransactionInDoubtException e) {
          Log.getLogWriter().info("Caught TransactionInDoubtException.  Expected with concurrent execution, continuing test.");
        } catch (ConflictException e) {
          // we don't expect this as each thread works on its own set of keys
          throw new TestException("Unexpected ConflictException " + e + " " + TestHelper.getStackTrace(e));
        }
      }
    }
    client.endNoKillZone();
  }

  /** Create the pdx disk store if one was specified.
   *
   */
  protected static void initPdxDiskStore() {
    if (CacheHelper.getCache().getPdxPersistent()) {
      String pdxDiskStoreName = TestConfig.tab().stringAt(hydra.CachePrms.pdxDiskStoreName, null);
      if (pdxDiskStoreName != null) {// pdx disk store name was specified
        if (CacheHelper.getCache().findDiskStore(pdxDiskStoreName) == null) {
          DiskStoreHelper.createDiskStore(pdxDiskStoreName);
        }
      }
    }
  }

  /** Randomly use put or (concurrentMap op) replace to update a value in the cache.
   *  This has been separated out to VersionHelper to allow with testing pre 6.5 versions for
   *  backward compatibility.
   */
  protected static void updateEntry(Region aRegion, Object key, Object value) {
    if (TestConfig.tab().getRandGen().nextBoolean()) {
      aRegion.put(key, value);
    } else {
      Object v = aRegion.replace(key, value);
      if (v == null) { // force update
        aRegion.put(key, value);
      }
    }
  }
}

