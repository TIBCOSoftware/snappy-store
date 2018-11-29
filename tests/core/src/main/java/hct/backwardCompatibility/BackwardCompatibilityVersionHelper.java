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
package hct.backwardCompatibility;

import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TransactionDataNodeHasDepartedException;
import com.gemstone.gemfire.cache.TransactionDataRebalancedException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.TransactionInDoubtException;
import hydra.Log;
import hydra.TestConfig;
import util.TestException;
import util.TestHelper;
import util.TestHelperPrms;
import util.TxHelper;

/** VersionHelper class (for Transaction support) */
public class BackwardCompatibilityVersionHelper {

   /** Do random entry operations on the given region ending either with
    *  minTaskGranularityMS or numOpsPerTask.
    */
    protected void doEntryOperations(BackwardCompatibilityTest testInstance, Region aRegion) {
       Log.getLogWriter().info("In doEntryOperations with " + aRegion.getFullPath());

       long minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec, Long.MAX_VALUE);
       Long minTaskGranularityMS;
       if (minTaskGranularitySec == Long.MAX_VALUE) {
          minTaskGranularityMS = Long.MAX_VALUE;
       } else {
           minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;
       }

       int numOpsPerTask = BackwardCompatibilityPrms.numOpsPerTask();
       boolean useTransactions = BackwardCompatibilityPrms.useTransactions();

       Log.getLogWriter().info("minTaskGranularitySec " + minTaskGranularitySec + ", " +
                               "minTaskGranularityMS " + minTaskGranularityMS + ", " +
                               "numOpsPerTask " + numOpsPerTask + ", " +
                               "useTransactions " + useTransactions);

       long startTime = System.currentTimeMillis();
       int numOps = 0;
       boolean rolledback = false;
       
       // In transaction tests, package the operations into a single transaction
       if (useTransactions) {
         TxHelper.begin();
       }

       do {
          try {
             testInstance.doRandomOp(aRegion);
          } catch (TransactionDataNodeHasDepartedException e) {
            if (!useTransactions) {
              throw new TestException("Unexpected TransactionDataNodeHasDepartedException " + TestHelper.getStackTrace(e));
            } else {
              Log.getLogWriter().info("Caught TransactionDataNodeHasDepartedException.  Expected with concurrent execution, continuing test.");
              Log.getLogWriter().info("Rolling back transaction.");
              try {
                rolledback = true;
                TxHelper.rollback();
                Log.getLogWriter().info("Done Rolling back Transaction");
              } catch (TransactionException te) {
                Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching TransactionDataNodeHasDeparted during tx ops.  Expected, continuing test.");
              }
            }
          } catch (TransactionDataRebalancedException e) {
            if (!useTransactions) {
              throw new TestException("Unexpected Exception " + e + ". " + TestHelper.getStackTrace(e));
            } else {
              Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
              Log.getLogWriter().info("Rolling back transaction.");
              rolledback = true;
              TxHelper.rollback();
              Log.getLogWriter().info("Done Rolling back Transaction");
            }
         } 
         numOps++;
         Log.getLogWriter().info("Completed op " + numOps + " for this task");
      } while ((System.currentTimeMillis() - startTime < minTaskGranularityMS) &&
               (numOps < numOpsPerTask));

       // finish transactions (commit or rollback)
       if (useTransactions && !rolledback) {
          int n = 0;
          int commitPercentage = BackwardCompatibilityPrms.getCommitPercentage();
          n = TestConfig.tab().getRandGen().nextInt(1, 100);

          if (n <= commitPercentage) {
            try {
               TxHelper.commit();
            } catch (TransactionDataNodeHasDepartedException e) {
              Log.getLogWriter().info("Caught TransactionDataNodeHasDepartedException.  Expected with concurrent execution, continuing test.");
            } catch (TransactionInDoubtException e) {
              Log.getLogWriter().info("Caught TransactionInDoubtException.  Expected with concurrent execution, continuing test.");
            } catch (ConflictException e) {
               Log.getLogWriter().info("ConflictException " + e + " expected, continuing test");
            }
          } else {
              TxHelper.rollback();
          }
       }
       Log.getLogWriter().info("Done in doEntryOperations with " + aRegion.getFullPath());
    }
}
