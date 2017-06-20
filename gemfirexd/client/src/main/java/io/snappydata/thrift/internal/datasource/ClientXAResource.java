/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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
package io.snappydata.thrift.internal.datasource;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.SnappyException;
import io.snappydata.thrift.SnappyExceptionData;
import io.snappydata.thrift.TransactionXid;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.internal.ClientService;
import io.snappydata.thrift.internal.HostConnection;

/**
 * {@link XAResource} implementation for a thrift based connection.
 */
public class ClientXAResource extends ReentrantLock implements XAResource {

  private final ClientService clientService;
  private int timeoutInSeconds;
  private HostConnection txHost;

  ClientXAResource(ClientService service) {
    this.clientService = service;
  }

  private XAException newXAException(int xaCode, String sqlState,
      Object... args) {
    XAException xae = new XAException(xaCode);
    if (sqlState != null) {
      xae.initCause(ThriftExceptionUtil.newSQLException(sqlState, null, args));
    }
    return xae;
  }

  private XAException newXAException(SnappyException se) {
    XAException xae;
    SnappyExceptionData data = se.getExceptionData();
    final String sqlState = data.getSqlState();
    if (sqlState == null || sqlState.isEmpty()) {
      String message = data.getReason();
      if (message != null) {
        xae = new XAException(message);
        xae.errorCode = data.getErrorCode();
      } else {
        xae = new XAException(data.errorCode);
      }
    } else {
      xae = new XAException(XAException.XAER_RMERR);
    }
    xae.initCause(ThriftExceptionUtil.newSQLException(se));
    return xae;
  }

  private void checkClosedConnection(ClientService service)
      throws XAException {
    if (service.isClosed()) {
      throw newXAException(XAException.XAER_RMFAIL,
          SQLState.NO_CURRENT_CONNECTION);
    }
  }

  private void initTXHost(ClientService service) {
    if (service != null) {
      this.txHost = service.getCurrentHostConnection();
    } else {
      this.txHost = null;
    }
  }

  private HostConnection getTXHost() throws XAException {
    final HostConnection txHost = this.txHost;
    if (txHost != null) {
      return txHost;
    } else {
      throw new XAException(XAException.XAER_PROTO);
    }
  }

  private TransactionXid transactionXid(Xid xid) {
    return new TransactionXid(xid.getFormatId(),
        ByteBuffer.wrap(xid.getGlobalTransactionId()),
        ByteBuffer.wrap(xid.getBranchQualifier()));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean setTransactionTimeout(int seconds) throws XAException {
    if (seconds < 0) {
      // throw an exception if invalid value was specified
      throw new XAException(XAException.XAER_INVAL);
    }
    this.timeoutInSeconds = seconds;
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getTransactionTimeout() {
    return this.timeoutInSeconds;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSameRM(XAResource xaResource) throws XAException {
    checkClosedConnection(this.clientService);
    if (xaResource instanceof ClientXAResource) {
      ClientXAResource otherResource = (ClientXAResource)xaResource;
      if (this.clientService == otherResource.clientService) {
        return true;
      }
      HostConnection otherConn = otherResource.txHost;
      HostConnection conn = this.txHost;
      if (otherConn == null) {
        otherConn = otherResource.clientService.getCurrentHostConnection();
      }
      if (conn == null) {
        conn = this.clientService.getCurrentHostConnection();
      }
      return (conn != null && conn.equals(otherConn));
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start(Xid xid, int flags) throws XAException {
    final ClientService service = this.clientService;
    super.lock();
    try {
      checkClosedConnection(service);
      service.startXATransaction(transactionXid(xid),
          this.timeoutInSeconds, flags);
      initTXHost(service);
    } catch (SnappyException se) {
      throw newXAException(se);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int prepare(Xid xid) throws XAException {
    final HostConnection txHost = getTXHost();
    final ClientService service = this.clientService;
    super.lock();
    try {
      checkClosedConnection(service);
      return service.prepareXATransaction(txHost, transactionXid(xid));
    } catch (SnappyException se) {
      throw newXAException(se);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void commit(Xid xid, boolean onePhase) throws XAException {
    final HostConnection txHost = getTXHost();
    final ClientService service = this.clientService;
    super.lock();
    try {
      checkClosedConnection(service);
      service.commitXATransaction(txHost, transactionXid(xid), onePhase);
    } catch (SnappyException se) {
      throw newXAException(se);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rollback(Xid xid) throws XAException {
    final HostConnection txHost = getTXHost();
    final ClientService service = this.clientService;
    super.lock();
    try {
      checkClosedConnection(service);
      service.rollbackXATransaction(txHost, transactionXid(xid));
    } catch (SnappyException se) {
      throw newXAException(se);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void end(Xid xid, int flags) throws XAException {
    final HostConnection txHost = getTXHost();
    final ClientService service = this.clientService;
    super.lock();
    try {
      checkClosedConnection(service);
      service.endXATransaction(txHost, transactionXid(xid), flags);
    } catch (SnappyException se) {
      throw newXAException(se);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void forget(Xid xid) throws XAException {
    final HostConnection txHost = getTXHost();
    final ClientService service = this.clientService;
    super.lock();
    try {
      checkClosedConnection(service);
      service.forgetXATransaction(txHost, transactionXid(xid));
    } catch (SnappyException se) {
      throw newXAException(se);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Xid[] recover(int flag) throws XAException {
    final HostConnection txHost = getTXHost();
    final ClientService service = this.clientService;
    super.lock();
    try {
      checkClosedConnection(service);
      List<TransactionXid> xidList = service.recoverXATransaction(txHost, flag);
      final Xid[] resultXids = new Xid[xidList.size()];
      for (int index = 0; index < resultXids.length; index++) {
        resultXids[index] = new ClientXid(xidList.get(index));
      }
      return resultXids;
    } catch (SnappyException se) {
      throw newXAException(se);
    } finally {
      super.unlock();
    }
  }
}
