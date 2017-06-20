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
package com.pivotal.gemfirexd.internal.engine.management.impl;

import javax.management.JMRuntimeException;
import javax.management.NotificationBroadcasterSupport;

import com.gemstone.gemfire.management.internal.beans.MemberMBeanBridge;
import com.pivotal.gemfirexd.internal.engine.management.NetworkServerConnectionStats;
import com.pivotal.gemfirexd.internal.engine.management.NetworkServerNestedConnectionStats;
import com.pivotal.gemfirexd.internal.engine.management.GfxdMemberMXBean;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;

/**
*
* @author Abhishek Chaudhari, Ajay Pande
* @since gfxd 1.0
*/
public class GfxdMemberMBean extends NotificationBroadcasterSupport implements GfxdMemberMXBean, Cleanable {

  private GfxdMemberMBeanBridge gfxdbridge;
  @SuppressWarnings("unused")
  private MemberMBeanBridge     gemBridge;

  public GfxdMemberMBean(GfxdMemberMBeanBridge gfxdbridge, MemberMBeanBridge gemBridge) {
    this.gfxdbridge = gfxdbridge;
    this.gemBridge  = gemBridge;
  }

  @Override
  public String getName() {
    return this.gfxdbridge.getName();
  }

  @Override
  public String getId() {
    return this.gfxdbridge.getId();
  }

  @Override
  public String[] getGroups() {
    return this.gfxdbridge.getGroups();
  }

  @Override
  public boolean isDataStore() {
    return this.gfxdbridge.isDataStore();
  }

  @Override
  public boolean isLead() {
    return this.gfxdbridge.isLead();
  }

  @Override
  public boolean isLocator() {
    return this.gfxdbridge.isLocator();
  }

  @Override
  public NetworkServerConnectionStats getNetworkServerClientConnectionStats() {
    return this.gfxdbridge.listNetworkServerClientConnectionStats();
  }

  @Override
  public NetworkServerConnectionStats getNetworkServerPeerConnectionStats() {
    return this.gfxdbridge.listNetworkServerPeerConnectionStats();
  }

  @Override
  public NetworkServerNestedConnectionStats getNetworkServerNestedConnectionStats() {
    return this.gfxdbridge.listNetworkServerNestedConnectionStats();
  }

  @Override
  public NetworkServerNestedConnectionStats getNetworkServerInternalConnectionStats() {
    return this.gfxdbridge.listNetworkServerInternalConnectionStats();
  }

  @Override
  public int getProcedureCallsCompleted() {
    return this.gfxdbridge.getProcedureCallsCompleted();
  }

  @Override
  public int getProcedureCallsInProgress() {
    return this.gfxdbridge.getProcedureCallsInProgress();
  }

//  @Override
//  public TabularData getCachePerfStats() {
//    return this.gfxdbridge.getCachePerfStats();
//  }

  @Override
  public GfxdMemberMetaData fetchMetadata() {
    return this.gfxdbridge.fetchMetadata();
  }

  @Override
  public float fetchEvictionPercent() {
    return this.gfxdbridge.fetchEvictionPercent();
  }

  public void updateEvictionPercent(float newValue) {
    try {
      this.gfxdbridge.updateEvictionPercent(newValue);
    } catch (StandardException e) {
      throw new JMRuntimeException(e.getMessage());
    }
  }

  @Override
  public float fetchCriticalPercent() {
    return this.gfxdbridge.fetchCriticalPercent();
  }

  public void updateCriticalPercent(float newValue) {
    try {
      this.gfxdbridge.updateCriticalPercent(newValue);
    } catch (StandardException e) {
      throw new JMRuntimeException(e.getMessage());
    }
  }

//  @Override
//  public void enableStatementStats(boolean enableOrDisableStats, boolean enableOrDisableTimeStats) {
//    this.gfxdbridge.enableStatementStats(enableOrDisableStats, enableOrDisableTimeStats);
//  }

  @Override
  public String detectDeadlocks() {
    return this.gfxdbridge.detectDeadlocks();
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((gfxdbridge == null) ? 0 : gfxdbridge.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    GfxdMemberMBean other = (GfxdMemberMBean) obj;
    if (gfxdbridge == null) {
      if (other.gfxdbridge != null) {
        return false;
      }
    } else if (!gfxdbridge.equals(other.gfxdbridge)) {
      return false;
    }
    return true;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append(GfxdMemberMBean.class.getSimpleName()).append(" [bridge=").append(gfxdbridge).append("]");
    return builder.toString();
  }

  @Override
  public void cleanUp() {
    this.gfxdbridge.cleanUp();
  }
  


  @Override
  public String[] activeThreads() {
    return this.gfxdbridge.activeThreads();
  }



  @Override
  public String[] getStack(String Id) {
    return this.gfxdbridge.getStack(Id);
  }
}
