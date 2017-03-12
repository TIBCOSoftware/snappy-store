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

package com.pivotal.gemfirexd.internal.client.net;

import java.sql.SQLException;

import com.gemstone.gemfire.internal.shared.FinalizeHolder;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.pivotal.gemfirexd.internal.client.am.Connection;
import com.pivotal.gemfirexd.internal.client.am.PreparedStatement;
import com.pivotal.gemfirexd.internal.client.net.NetConnection.DSConnectionInfo;

/**
 * @author kneeraj
 * 
 */
@SuppressWarnings("serial")
public class FinalizeSingleHopResultSet extends FinalizeObject {

  private final Connection[] internalConnections;
  private final DSConnectionInfo connInfo;

  public FinalizeSingleHopResultSet(SingleHopResultSet rs,
      DSConnectionInfo dsConnInfo) {
    super(rs, false);
    // This array should be non null and non-empty. Check before calling this
    // constructor.
    PreparedStatement[] prepStmntArray = rs.getPreparedStatements();
    this.internalConnections = new NetConnection[prepStmntArray.length];
    for (int i = 0; i < prepStmntArray.length; i++) {
      try {
        internalConnections[i] = (Connection)prepStmntArray[i].getConnection();
      } catch (SQLException e) {
        // ignore. Since this should never happen. If it does we can take care
        // of this in
        // the doFinalize method.
      }
    }
    this.connInfo = dsConnInfo;
  }

  @Override
  protected boolean doFinalize() throws Exception {
    synchronized (this) {
      for (int i = 0; i < internalConnections.length; i++) {
        NetConnection conn = (NetConnection)internalConnections[i];
        if (conn != null) {
          this.connInfo.returnConnection(conn);
          this.internalConnections[i] = null;
        }
      }
    }
    return true;
  }

  public void nullConnection(NetConnection conn) {
    if (this.internalConnections != null) {
      for (int i = 0; i < this.internalConnections.length; i++) {
        if (conn == this.internalConnections[i]) {
          this.internalConnections[i] = null;
          break;
        }
      }
    }
  }

  public void resetAndDoFinalize() {
    try {
      for (int i = 0; i < internalConnections.length; i++) {
        NetConnection conn = (NetConnection)internalConnections[i];
        if (conn != null) {
          conn.reset(conn.agent_.logWriter_);
          conn.gotException_ = false;
        }
      }
      doFinalize();
    } catch (Exception ex) {
      if (this.internalConnections != null) {
        for (int i = 0; i < this.internalConnections.length; i++) {
          if (this.internalConnections[i] != null) {
            try {
              this.connInfo
                  .removeConnection((NetConnection)this.internalConnections[i]);
            } catch (InterruptedException e) {
              // ignore. If the cleanup is not proper then the next sql
              // execution will get
              // handlable exception
            }
            break;
          }
        }
      }
    }
  }

  @Override
  public final FinalizeHolder getHolder() {
    return getClientHolder();
  }

  @Override
  protected void clearThis() {
  }
}
