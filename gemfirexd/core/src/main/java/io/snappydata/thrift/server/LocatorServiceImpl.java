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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.server;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.transaction.xa.XAException;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl;
import com.pivotal.gemfirexd.internal.shared.common.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import io.snappydata.thrift.HostAddress;
import io.snappydata.thrift.LocatorService;
import io.snappydata.thrift.ServerType;
import io.snappydata.thrift.SnappyDataService;
import io.snappydata.thrift.SnappyException;
import io.snappydata.thrift.SnappyExceptionData;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.common.ThriftUtils;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side implementation of thrift LocatorService (see snappydata.thrift).
 */
public class LocatorServiceImpl implements LocatorService.Iface {

  protected final String hostAddress;
  protected final int hostPort;
  private volatile boolean isActive;
  private final CancelCriterion stopper;

  final Logger logger = LoggerFactory.getLogger(getClass().getName());

  public LocatorServiceImpl(String address, int port) {
    this.hostAddress = address;
    this.hostPort = port;
    this.isActive = true;

    final GemFireStore store = Misc.getMemStoreBooting();
    this.stopper = new CancelCriterion() {

      @Override
      public RuntimeException generateCancelledException(Throwable t) {
        final RuntimeException ce;
        if ((ce = store.getAdvisee().getCancelCriterion()
            .generateCancelledException(t)) != null) {
          return ce;
        }
        return new CacheClosedException(MessageService.getCompleteMessage(
            SQLState.CLOUDSCAPE_SYSTEM_SHUTDOWN, null), t);
      }

      @Override
      public String cancelInProgress() {
        String cancel;
        if ((cancel = store.getAdvisee().getCancelCriterion()
            .cancelInProgress()) != null) {
          return cancel;
        }
        if (isActive()) {
          return null;
        }
        else {
          return MessageService.getCompleteMessage(
              SQLState.CLOUDSCAPE_SYSTEM_SHUTDOWN, null);
        }
      }
    };
  }

  /**
   * Get HostAddress of the preferred server w.r.t. load-balancing to connect to
   * from a thrift client. Client must use the returned server for the real data
   * connection to use the main {@link SnappyDataService.Iface} thrift API.
   * A list of servers to be excluded from consideration can be passed as a
   * comma-separated string (e.g. to ignore the failed server during failover).
   */
  @Override
  public final HostAddress getPreferredServer(Set<ServerType> serverTypes,
      Set<String> serverGroups, Set<HostAddress> failedServers)
      throws SnappyException {

    if (failedServers == null) {
      failedServers = Collections.emptySet();
    }

    final Set<String> intersectGroups;
    final int ntypes;
    if (serverTypes != null && (ntypes = serverTypes.size()) > 0) {
      if (ntypes == 1) {
        intersectGroups = Collections.singleton(serverTypes.iterator().next()
            .getServerGroupName());
      } else {
        @SuppressWarnings("unchecked")
        Set<String> igroups = new THashSet(ntypes);
        intersectGroups = igroups;
        for (ServerType serverType : serverTypes) {
          intersectGroups.add(serverType.getServerGroupName());
        }
      }
    } else {
      intersectGroups = null;
    }
    if (SanityManager.TraceClientHA) {
      logger.info("getPreferredServer(): getting preferred server for " +
          "typeGroups=" + intersectGroups + (serverGroups != null
          ? " serverGroups=" + serverGroups : ""));
    }

    ServerLocation prefServer;
    HostAddress prefHost;
    try {
      prefServer = GemFireXDUtils.getPreferredServer(serverGroups,
          intersectGroups, failedServers, null, true);
    } catch (Throwable t) {
      throw SnappyException(t);
    }
    if (prefServer != null && prefServer.getPort() > 0) {
      prefHost = ThriftUtils.getHostAddress(prefServer.getHostName(),
          prefServer.getPort());
    } else {
      // for consistency since some calls expect non-null result
      prefHost = HostAddress.NULL_ADDRESS;
    }
    return prefHost;
  }

  /**
   * Get two results: a HostAddress containing the preferred server w.r.t.
   * load-balancing to connect to from a thrift client (like in
   * {@link #getPreferredServer}), and all the thrift servers available in
   * the distributed system as HostAddresses. A list of servers to be excluded
   * from consideration can be passed as a comma-separated string (e.g. to
   * ignore the failed server during failover).
   * <p>
   * The returned list has the first element as the preferred server while the
   * remaining elements in the list are all the thrift servers available in the
   * distributed system.
   * <p>
   * This is primarily to avoid making two calls to the servers from the clients
   * during connection creation or failover.
   */
  @Override
  public final List<HostAddress> getAllServersWithPreferredServer(
      Set<ServerType> serverTypes, Set<String> serverGroups,
      Set<HostAddress> failedServers) throws SnappyException {
    HostAddress prefServer = getPreferredServer(serverTypes, serverGroups,
        failedServers);
    ArrayList<HostAddress> prefAndAllServers = new ArrayList<>();

    final Set<ServerType> allTypes;
    if (serverTypes == null || serverTypes.isEmpty()) {
      allTypes = null;
    }
    else {
      @SuppressWarnings("unchecked")
      Set<ServerType> types = new THashSet(serverTypes.size() * 2);
      allTypes = types;
      ServerType locatorType;

      allTypes.addAll(serverTypes);
      for (ServerType serverType : serverTypes) {
        if (serverType.isThriftSnappy()) {
          locatorType = serverType.getCorrespondingLocatorType();
          if (!serverTypes.contains(locatorType)) {
            allTypes.add(locatorType);
          }
        }
      }
    }
    prefAndAllServers.add(prefServer);
    try {
      GemFireXDUtils.getGfxdAdvisor().getAllThriftServers(allTypes,
          prefAndAllServers);
    } catch (Throwable t) {
      throw SnappyException(t);
    }
    if (SanityManager.TraceClientHA) {
      logger.info("getAllServersWithPreferredServer(): returning preferred " +
          "server and all hosts " + prefAndAllServers);
    }
    return prefAndAllServers;
  }

  public final CancelCriterion getCancelCriterion() {
    return this.stopper;
  }

  protected SnappyException SnappyException(Throwable t) {
    SQLException sqle;
    if (t instanceof SQLException) {
      sqle = (SQLException)t;
    } else if (t instanceof SnappyException) {
      return (SnappyException)t;
    } else if (t instanceof XAException) {
      XAException xae = (XAException)t;
      sqle = new SQLException(xae.getMessage(), null, xae.errorCode);
      if (xae.getCause() != null) {
        sqle.setNextException(
            TransactionResourceImpl.wrapInSQLException(xae.getCause()));
      }
    } else {
      if (t instanceof Error) {
        Error err = (Error)t;
        if (SystemFailure.isJVMFailureError(err)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable.
        SystemFailure.checkFailure();
        // If the above returns then send back error since this may be
        // assertion or some other internal code bug.
      }
      // check node going down
      String nodeFailure = getCancelCriterion().cancelInProgress();
      if (nodeFailure != null) {
        if (!GemFireXDUtils.nodeFailureException(t)) {
          t = getCancelCriterion().generateCancelledException(t);
        }
      } else {
        // print to server log
        logger.warn("Unexpected error in execution", t);
      }
      sqle = TransactionResourceImpl.wrapInSQLException(t);
    }

    SnappyExceptionData exData = new SnappyExceptionData(sqle.getMessage(),
        sqle.getErrorCode()).setSqlState(sqle.getSQLState());
    if (sqle instanceof BatchUpdateException) {
      int[] updates = ((BatchUpdateException)sqle).getUpdateCounts();
      List<Integer> updateCounts;
      if (updates != null && updates.length > 0) {
        updateCounts = new ArrayList<>(updates.length);
        for (int update : updates) {
          updateCounts.add(update);
        }
      } else {
        updateCounts = Collections.emptyList();
      }
      exData.setUpdateCounts(updateCounts);
    }
    ArrayList<SnappyExceptionData> nextExceptions = new ArrayList<>(4);
    SQLException next = sqle.getNextException();
    if (next != null) {
      nextExceptions = new ArrayList<>();
      do {
        nextExceptions.add(new SnappyExceptionData(next.getMessage(),
            next.getErrorCode()).setSqlState(next.getSQLState()));
      } while ((next = next.getNextException()) != null);
    }
    SnappyException se = new SnappyException(exData, getServerInfo());
    // append the server stack trace at the end
    final StringBuilder stack;
    if (t instanceof TException) {
      stack = new StringBuilder("Cause: ").append(
          ThriftExceptionUtil.getExceptionString(t)).append("; Server STACK: ");
    } else {
      stack = new StringBuilder("Server STACK: ");
    }
    SanityManager.getStackTrace(t, stack);
    nextExceptions.add(new SnappyExceptionData(stack.toString(),
        ExceptionSeverity.STATEMENT_SEVERITY)
        .setSqlState(SQLState.SNAPPY_SERVER_STACK_INDICATOR));
    se.setNextExceptions(nextExceptions);
    return se;
  }

  public SnappyException newSnappyException(String messageId, Object... args) {
    SnappyExceptionData exData = new SnappyExceptionData();
    exData.setSqlState(StandardException.getSQLStateFromIdentifier(messageId));
    exData.setErrorCode(StandardException.getSeverityFromIdentifier(messageId));
    exData.setReason(MessageService.getCompleteMessage(messageId, args));
    return new SnappyException(exData, getServerInfo());
  }

  protected String getServerInfo() {
    return "Locator=" + this.hostAddress + '[' + this.hostPort + "] Thread="
        + Thread.currentThread().getName();
  }

  public final boolean isActive() {
    return this.isActive;
  }

  public void stop() {
    this.isActive = false;
  }
}
