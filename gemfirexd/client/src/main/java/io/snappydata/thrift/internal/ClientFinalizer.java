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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package io.snappydata.thrift.internal;

import java.util.ArrayList;

import com.gemstone.gemfire.internal.shared.FinalizeHolder;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gnu.trove.TLinkedList;
import io.snappydata.thrift.EntityId;
import io.snappydata.thrift.SnappyException;
import io.snappydata.thrift.snappydataConstants;

/**
 * Efficient finalizer for client artifacts to clear server-side artifacts.
 */
@SuppressWarnings("serial")
public final class ClientFinalizer extends FinalizeObject implements
    FinalizeObject.BatchFinalize {

  private volatile long id;
  private ClientService service;
  HostConnection source;
  private final byte entityType;
  private TLinkedList batchedFinalizers;

  private static final int FINALIZER_LOCK_TIMEOUT_MS = 10000;

  ClientFinalizer(Object referent, ClientService service,
      byte entityType) {
    super(referent, false);
    this.service = service;
    this.entityType = entityType;
  }

  void updateReferentData(long id, HostConnection source) {
    this.id = id;
    this.source = source;
  }

  @Override
  public final FinalizeHolder getHolder() {
    return getClientHolder();
  }

  @Override
  protected void clearThis() {
    this.id = snappydataConstants.INVALID_ID;
    this.service = null;
    this.source = null;
    this.batchedFinalizers = null;
  }

  private void addBulkCloseArgs(ClientFinalizer finalizer,
      ArrayList<EntityId> entities, ArrayList<HostConnection> sources,
      ArrayList<ClientService> services,
      ArrayList<ClientService> closeServices) {

    final byte type = finalizer.entityType;
    final ClientService service = finalizer.service;
    final long id;
    final HostConnection source;
    if (type == snappydataConstants.BULK_CLOSE_CONNECTION) {
      if (service == null || service.isClosed() ||
          (source = service.getCurrentHostConnection()) == null) {
        return;
      }
      id = source.connId;
      closeServices.add(service);
    } else {
      id = finalizer.id;
      source = finalizer.source;
    }
    if (id == snappydataConstants.INVALID_ID || service == null ||
        source == null || service.isClosed()) {
      return;
    }

    entities.add(new EntityId(id, type, source.connId, source.token));
    sources.add(source);
    services.add(service);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected boolean doFinalize() throws Exception {
    // check for the case of bulk close
    final TLinkedList batchedFinalizers = this.batchedFinalizers;
    final int numFinalizers = batchedFinalizers != null ? batchedFinalizers
        .size() + 1 : 1;
    ArrayList<EntityId> entities = new ArrayList<>(numFinalizers);
    ArrayList<HostConnection> sources = new ArrayList<>(numFinalizers);
    ArrayList<ClientService> services = new ArrayList<>(numFinalizers);
    ArrayList<ClientService> closeServices = new ArrayList<>(numFinalizers);
    // first add self
    addBulkCloseArgs(this, entities, sources, services, closeServices);
    if (batchedFinalizers != null) {
      for (int i = 0; i < (numFinalizers - 1); i++) {
        addBulkCloseArgs((ClientFinalizer)batchedFinalizers.get(i), entities,
            sources, services, closeServices);
      }
    }
    if (closeServices.isEmpty()) {
      closeServices = null;
    }

    long start = System.currentTimeMillis();
    int numActiveServices = services.size();
    // try for a successful send on any one of the services
    while (true) {
      for (int i = numActiveServices - 1; i >= 0; i--) {
        ClientService service = services.get(i);
        boolean removeService = service.isClosed();
        try {
          if (!removeService && service.bulkClose(sources.get(i), entities,
              closeServices, 100L)) {
            if (batchedFinalizers != null) {
              batchedFinalizers.clear();
            }
            return true;
          }
        } catch (SnappyException se) {
          // connection could have closed/failed not necessarily the server,
          // so continue trying with other ClientServices till timeout
          removeService = true;
        }
        if ((System.currentTimeMillis() - start) >
            FINALIZER_LOCK_TIMEOUT_MS) {
          return false;
        } else if (removeService) {
          services.remove(i);
          sources.remove(i);
          numActiveServices--;
        }
      }
      // if all failed then assume the server itself has gone away
      if (numActiveServices <= 0) {
        // force close sockets etc for connections to be closed in any case
        if (closeServices != null) {
          for (ClientService service : closeServices) {
            if (service != null) {
              service.closeService();
            }
          }
        }
        return true;
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BatchFinalize merge(BatchFinalize o) {
    if (o instanceof ClientFinalizer) {
      ClientFinalizer other = (ClientFinalizer)o;
      final HostConnection hostConn = other.source;
      final HostConnection thisConn = this.source;
      // to maximise batch, group by target HostAddress i.e. for each distinct
      // SnappyDataServiceImpl, which means a connection/ClientService may send
      // bulkClose request for a different connection/ClientService on the same
      // server which is by design
      if (hostConn != null && thisConn != null
          && hostConn.hostAddr.equals(thisConn.hostAddr)) {
        if (this.batchedFinalizers == null) {
          this.batchedFinalizers = new TLinkedList();
        }
        this.batchedFinalizers.add(other);
        return this;
      }
    }
    return null;
  }
}
