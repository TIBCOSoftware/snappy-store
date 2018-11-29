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

package resumeTx;

import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.TransactionDataNodeHasDepartedException;
import com.gemstone.gemfire.cache.TransactionDataRebalancedException;
import com.gemstone.gemfire.cache.TransactionId;
import com.gemstone.gemfire.cache.TransactionInDoubtException;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.distributed.DistributedMember;
import hydra.CacheHelper;
import hydra.Log;
import hydra.TestConfig;
import util.TestException;
import util.TestHelper;
import util.TxHelper;

/** CommitTx
 *  A Function to commit a resumeable transaction.
 *  This function is invoked with a TrannsactionId in the argList.  It resumes and commits the 
 *  given transaction id.
 *  
 * @author Lynn Hughes-Godfrey
 * @since 6.6
 */
public class CommitTx implements Function, Declarable {
  
  public void execute(FunctionContext context) {

    DistributedMember dm = CacheHelper.getCache().getDistributedSystem().getDistributedMember();
    boolean isHA = ResumeTxPrms.getHighAvailability();
    boolean isSerialExecution = TestConfig.tab().booleanAt(hydra.Prms.serialExecution);

    boolean committed = true;

    // Arguments are an ArrayList with a clientIdString, TransactionId
    ArrayList argumentList = (ArrayList) context.getArguments();
    String forDM = (String)argumentList.get(0);
    TransactionId txId = (TransactionId)argumentList.get(1);

    Log.getLogWriter().info("executing " + this.getClass().getName() + " in member " + dm + ", invoked from " + forDM + " on txId " + txId);

    if (TxHelper.tryResume(txId, 10L, TimeUnit.SECONDS)) {
       Log.getLogWriter().fine("CommitTx RESUMED " + txId);
       try {
          TxHelper.commit();
       } catch (ConflictException e) {
         committed = false;
         if (isSerialExecution) {
            // rethrow original Exception
            throw e;
         } else {
            Log.getLogWriter().info("Caught Exception " + e + " on commit.  Expected with concurrent execution, continuing test");
         }
       } catch (TransactionDataRebalancedException e) {
         if (isHA) {
            // re-throw original Exception
            throw e;
         } else {
            throw new TestException("Unexpected Exception " + e + " on commit of " + txId + ", " + TestHelper.getStackTrace(e));
         }
       } catch (TransactionDataNodeHasDepartedException e) {
         if (isHA) {
            // re-throw original Exception
            throw e;
         } else {
            throw new TestException("Unexpected Exception " + e + " on commit of " + txId + ", " + TestHelper.getStackTrace(e));
         }
       } catch (TransactionInDoubtException e) {
         if (isHA) {
            // re-throw original Exception
            throw e;
         } else {
            throw new TestException("Unexpected Exception " + e + " on commit of " + txId + ", " + TestHelper.getStackTrace(e));
         }
       }
    } else {
       // We are only targeting this function via onRegion(r).withFilter() ... in serialExecution we should
       // always end up on the target node or get a FunctionInvocationTargetException.  If we end up
       // here, it means the transaction no longer exists on the primary (an orphaned tx)
       // For concurrent execution, it could mean that we were not able to resume the tx before it was
       // committed by another thread (or just because it was still in use).
       if (isSerialExecution) {
          throw new TestException(txId + " is not suspended in this member, cannot commit, " + TestHelper.getStackTrace());
       } else {
          Log.getLogWriter().fine(txId + " is not suspended in this member with tryResume time limit, cannot commit.  Expected with concurrent execution, continuing test.");
       }
    }
    Log.getLogWriter().info("CommitTx returning " + committed + " for txId = " + txId);
    context.getResultSender().lastResult(committed);
  }

  public String getId() {
    return this.getClass().getName();
  }

  public boolean hasResult() {
    return true;
  }

  public boolean optimizeForWrite() {
    return true;
  }

  public void init(Properties props) {
  }

  public boolean isHA() {
    return false;
  }
}
