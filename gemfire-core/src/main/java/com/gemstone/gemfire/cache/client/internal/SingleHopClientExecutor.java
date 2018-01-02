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
package com.gemstone.gemfire.cache.client.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.client.ServerConnectivityException;
import com.gemstone.gemfire.cache.client.ServerOperationException;
import com.gemstone.gemfire.cache.client.internal.GetAllOp.GetAllOpImpl;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PutAllPartialResultException;
import com.gemstone.gemfire.internal.cache.execute.InternalFunctionInvocationTargetException;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

public class SingleHopClientExecutor {

  static final ExecutorService execService = Executors
      .newCachedThreadPool(new ThreadFactory() {
        AtomicInteger threadNum = new AtomicInteger();

        public Thread newThread(final Runnable r) {
          LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();
          Thread result = new Thread(LogWriterImpl.createThreadGroup(
              "FunctionExecutionThreadGroup", logger), r,
              "Function Execution Thread-" + threadNum.incrementAndGet());
          result.setDaemon(true);
          return result;
        }
      });

  static void submitAll(List callableTasks) {
    if (callableTasks != null && !callableTasks.isEmpty()) {
      List futures = null;
      try {
        futures = execService.invokeAll(callableTasks);
      }
      catch (RejectedExecutionException rejectedExecutionEx) {
        throw rejectedExecutionEx;
      }
      catch (InterruptedException e) {
        throw new InternalGemFireException(e.getMessage());
      }
      if (futures != null) {
        Iterator itr = futures.iterator();
        while (itr.hasNext() && !execService.isShutdown()
            && !execService.isTerminated()) {
          Future fut = (Future)itr.next();
          try {
            fut.get();
          }
          catch (InterruptedException e) {
            throw new InternalGemFireException(e.getMessage());
          }
          catch (ExecutionException ee) {
            if (ee.getCause() instanceof FunctionException) {
              throw (FunctionException)ee.getCause();
            }
            else if (ee.getCause() instanceof ServerOperationException) {
              throw (ServerOperationException)ee.getCause();
            }
            else if (ee.getCause() instanceof ServerConnectivityException) {
              throw (ServerConnectivityException)ee.getCause();
            }
            else {
              throw executionThrowable(ee.getCause());
            }
          }
        }
      }
    }
  }

  static boolean submitAllHA(List callableTasks, LocalRegion region,
      ResultCollector rc, Set<String> failedNodes) {

    LogWriterI18n logger = region.getLogWriterI18n();
    ClientMetadataService cms;
    boolean reexecute = false;

    if (callableTasks != null && !callableTasks.isEmpty()) {
      List futures = null;
      try {
        futures = execService.invokeAll(callableTasks);
      }
      catch (RejectedExecutionException rejectedExecutionEx) {
        throw rejectedExecutionEx;
      }
      catch (InterruptedException e) {
        throw new InternalGemFireException(e.getMessage());
      }
      if (futures != null) {
        Iterator futureItr = futures.iterator();
        Iterator taskItr = callableTasks.iterator();
        while (futureItr.hasNext() && !execService.isShutdown()
            && !execService.isTerminated()) {
          Future fut = (Future)futureItr.next();
          SingleHopOperationCallable task = (SingleHopOperationCallable)taskItr.next();
          ServerLocation server = task.getServer();
          try {
            fut.get();
            if (logger.fineEnabled()) {
              logger.fine("ExecuteRegionFunctionSingleHopOp#got result from "
                  + server);
            }
          }
          catch (InterruptedException e) {
            throw new InternalGemFireException(e.getMessage());
          }
          catch (ExecutionException ee) {
            if (ee.getCause() instanceof InternalFunctionInvocationTargetException) {
              logger
                  .fine("ExecuteRegionFunctionSingleHopOp#ExecutionException.InternalFunctionInvocationTargetException : "
                      + "Caused by :" + ee.getCause());
              try {
                cms = region.getCache().getClientMetadataService();
              }
              catch (CacheClosedException e) {
                return false;
              }
              cms.scheduleGetPRMetaData(region, false);
              cms.removeBucketServerLocation(server);
              reexecute = true;
              failedNodes.addAll(((InternalFunctionInvocationTargetException)ee
                  .getCause()).getFailedNodeSet());
              rc.clearResults();
            }
            else if (ee.getCause() instanceof FunctionException) {
              if (logger.fineEnabled()) {
                logger
                    .fine("ExecuteRegionFunctionSingleHopOp#ExecutionException.FunctionException : Caused by :"
                        + ee.getCause());
              }
              throw (FunctionException)ee.getCause();
            }
            else if (ee.getCause() instanceof ServerOperationException) {
              if (logger.fineEnabled()) {
                logger
                    .fine("ExecuteRegionFunctionSingleHopOp#ExecutionException.ServerOperationException : Caused by :"
                        + ee.getCause());
              }
              throw (ServerOperationException)ee.getCause();
            }
            else if (ee.getCause() instanceof ServerConnectivityException) {
              if (logger.fineEnabled()) {
                logger
                    .fine("ExecuteRegionFunctionSingleHopOp#ExecutionException.ServerConnectivityException : Caused by :"
                        + ee.getCause() + " The failed server is: " + server);
              }
              try {
                cms = region.getCache().getClientMetadataService();
              }
              catch (CacheClosedException e) {
                return false;
              }
              cms.removeBucketServerLocation(server);
              cms.scheduleGetPRMetaData(region, false);
              reexecute = true;
              rc.clearResults();
            }
            else {
              throw executionThrowable(ee.getCause());
            }
          }
        }
      }
    }
    return reexecute;
  }
  
  /**
   * execute putAll on multiple PR servers, returning a map of the results.
   * Results are either a VersionedObjectList or a PutAllPartialResultsException
   * @param callableTasks
   * @param cms
   * @param region
   * @param failedServers
   * @return the per-server results
   */
  static Map<ServerLocation, Object> submitPutAll(List callableTasks, ClientMetadataService cms, 
      LocalRegion region, Map<ServerLocation,RuntimeException> failedServers) {
    LogWriterI18n logger = region.getLogWriterI18n();
    if (callableTasks != null && !callableTasks.isEmpty()) {
      Map<ServerLocation, Object> resultMap = new HashMap<ServerLocation, Object>();
      boolean anyPartialResults = false;
      List futures = null;
      try {
        futures = execService.invokeAll(callableTasks);
      }
      catch (RejectedExecutionException rejectedExecutionEx) {
        throw rejectedExecutionEx;
      }
      catch (InterruptedException e) {
        throw new InternalGemFireException(e.getMessage());
      }
      if (futures != null) {
        Iterator futureItr = futures.iterator();
        Iterator taskItr = callableTasks.iterator();
        RuntimeException rte = null;
        while (futureItr.hasNext() && !execService.isShutdown()
            && !execService.isTerminated()) {
          Future fut = (Future)futureItr.next();
          SingleHopOperationCallable task = (SingleHopOperationCallable)taskItr
              .next();
          ServerLocation server = task.getServer();
          try {
            VersionedObjectList versions = (VersionedObjectList)fut.get();
            if (logger.fineEnabled()) {
              logger.fine("PutAllOp#got result from "
                  + server + ":" + versions);
            }
            resultMap.put(server, versions);
          }
          catch (InterruptedException e) {
            InternalGemFireException ige = new InternalGemFireException(e);
            // only to make this server as failed server, not to throw right now
            failedServers.put(server,  ige);
            if (rte == null) {
              rte = ige;
            }
          }
          catch (ExecutionException ee) {
            if (ee.getCause() instanceof ServerOperationException) {
              if (logger.fineEnabled()) {
                logger
                  .fine("PutAllOp#ExecutionException from server " + server, ee);
              }
              ServerOperationException soe = (ServerOperationException)ee.getCause();
              // only to make this server as failed server, not to throw right now
              failedServers.put(server, soe);
              if (rte == null) {
                rte = soe;
              }
            }
            else if (ee.getCause() instanceof ServerConnectivityException) {
              if (logger.fineEnabled()) {
                logger
                  .fine("PutAllOp#ExecutionException for server " + server, ee);
              }
              cms = region.getCache().getClientMetadataService();
              cms.removeBucketServerLocation(server);
              cms.scheduleGetPRMetaData(region, false);
              failedServers.put(server, (ServerConnectivityException)ee.getCause());
            }
            else {
              Throwable t = ee.getCause();
              if (t instanceof PutAllPartialResultException) {
                resultMap.put(server, t);
                anyPartialResults = true;
                failedServers.put(server, (PutAllPartialResultException)t);
              } else {
                RuntimeException other_rte = executionThrowable(ee.getCause());
                failedServers.put(server, other_rte);
                if (rte == null) {
                  rte = other_rte;
                }
              }
            }
          } // catch
        } // while
        // if there are any partial results we suppress throwing an exception
        // so the partial results can be processed
        if (rte != null && !anyPartialResults) {
          throw rte;
        }
      }
      return resultMap;
    }
    return null;
  }
  
  static Map<ServerLocation, Object> submitGetAll(
      Map<ServerLocation, HashSet> serverToFilterMap, List callableTasks,
      ClientMetadataService cms, LocalRegion region) {
    LogWriterI18n logger = region.getLogWriterI18n();

    if (callableTasks != null && !callableTasks.isEmpty()) {
      Map<ServerLocation, Object> resultMap = new HashMap<ServerLocation, Object>();
      List futures = null;
      try {
        futures = execService.invokeAll(callableTasks);
      }
      catch (RejectedExecutionException rejectedExecutionEx) {
        throw rejectedExecutionEx;
      }
      catch (InterruptedException e) {
        throw new InternalGemFireException(e.getMessage());
      }
      if (futures != null) {
        Iterator futureItr = futures.iterator();
        Iterator taskItr = callableTasks.iterator();
        while (futureItr.hasNext() && !execService.isShutdown()
            && !execService.isTerminated()) {
          Future fut = (Future)futureItr.next();
          SingleHopOperationCallable task = (SingleHopOperationCallable)taskItr
              .next();
          List keys = ((GetAllOpImpl)task.getOperation()).getKeyList();
          ServerLocation server = task.getServer();
          try {

            VersionedObjectList valuesFromServer = (VersionedObjectList)fut.get();
            valuesFromServer.setKeys(keys);

            for (VersionedObjectList.Iterator it=valuesFromServer.iterator(); it.hasNext(); ) {
              VersionedObjectList.Entry entry = it.next();
              Object key = entry.getKey();
              Object value = entry.getValue();
              if (!entry.isKeyNotOnServer()) {
                if (value instanceof Throwable) {
                  logger.warning(
                    LocalizedStrings.GetAll_0_CAUGHT_THE_FOLLOWING_EXCEPTION_ATTEMPTING_TO_GET_VALUE_FOR_KEY_1,
                    new Object[]{value, key}, (Throwable)value);
                } 
              }
            }
            if (logger.fineEnabled()) {
              logger.fine("GetAllOp#got result from " + server +": " + valuesFromServer);
            }
            resultMap.put(server, valuesFromServer);
          }
          catch (InterruptedException e) {
            throw new InternalGemFireException(e.getMessage());
          }
          catch (ExecutionException ee) {
            if (ee.getCause() instanceof ServerOperationException) {
              if (logger.fineEnabled()) {
                logger
                    .fine("GetAllOp#ExecutionException.ServerOperationException : Caused by :"
                        + ee.getCause());
              }
              throw (ServerOperationException)ee.getCause();
            }
            else if (ee.getCause() instanceof ServerConnectivityException) {
              if (logger.fineEnabled()) {
                logger
                    .fine("GetAllOp#ExecutionException.ServerConnectivityException : Caused by :"
                        + ee.getCause() + " The failed server is: " + server);
              }
              try {
                cms = region.getCache()
                    .getClientMetadataService();
              }
              catch (CacheClosedException e) {
                return null;
              }
              cms.removeBucketServerLocation(server);
              cms.scheduleGetPRMetaData((LocalRegion)region, false);
              resultMap.put(server, ee.getCause());
            }
            else {
              throw executionThrowable(ee.getCause());
            }
          }
        }
        return resultMap;
      }
    }
    return null;
  }
  
  static void submitTask(Runnable task) {
    execService.execute(task);
  }

  // Find out what exception to throw?
  private static RuntimeException executionThrowable(Throwable t) {
    if (t instanceof RuntimeException)
      return (RuntimeException)t;
    else if (t instanceof Error)
      throw (Error)t;
    else
      throw new IllegalStateException("Don't know", t);
  }
}
