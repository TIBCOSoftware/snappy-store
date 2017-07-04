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

package com.gemstone.gemfire.internal.cache;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.server.ServerLoad;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisee;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.InternalDataSerializer;


/**
 * Used to give advise to a bridge server.
 * Bridge server currently need to know about controller's
 * @author darrel
 *
 */
public class BridgeServerAdvisor extends GridAdvisor {
  
  /** Creates a new instance of BridgeServerAdvisor */
  protected BridgeServerAdvisor(DistributionAdvisee server) {
    super(server);
  }

  public static BridgeServerAdvisor createBridgeServerAdvisor(DistributionAdvisee server) {
    BridgeServerAdvisor advisor = new BridgeServerAdvisor(server);
    advisor.initialize();
    return advisor;
  }

  @Override
  public String toString() {
    return "BridgeServerAdvisor for " + getAdvisee().getFullPath();
  }

  /** Instantiate new distribution profile for this member */
  @Override
  protected Profile instantiateProfile(
      InternalDistributedMember memberId, int version) {
    return new BridgeServerProfile(memberId, version);
  }

  @Override
  public Set adviseProfileRemove() {
    Set results = super.adviseProfileRemove();
    // Add other members as recipients which are neither locators nor running netservers,
    // if this is a snappy node. This could be helpful if a Spark Driver process is
    // connected as a peer to this DS in split-cluster mode. SNAP-737
    if (GemFireCacheImpl.getInternalProductCallbacks().isSnappyStore()) {
      results.addAll(getDistributionManager().getOtherNormalDistributionManagerIds());
    }
    return results;
  }

  /**
   * Describes a bridge server for distribution purposes.
   */
  public static class BridgeServerProfile extends GridAdvisor.GridProfile {
    private String[] groups;
    private int maxConnections;
    private ServerLoad initialLoad;
    private long loadPollInterval;

    /** for internal use, required for DataSerializer.readObject */
    public BridgeServerProfile() {
    }

    public BridgeServerProfile(InternalDistributedMember memberId, int version) {
      super(memberId, version);
    }

    public BridgeServerProfile(BridgeServerProfile toCopy) {
      super(toCopy);
      this.groups = toCopy.groups;
    }

    /** don't modify the returned array! */
    public String[] getGroups() {
      return this.groups;
    }
    public void setGroups(String[] groups) {
      this.groups = groups;
    }
    
    public ServerLoad getInitialLoad() {
      return initialLoad;
    }
    
    public int getMaxConnections() {
      return maxConnections;
    }
    
    public void setMaxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
    }

    public void setInitialLoad(ServerLoad initialLoad) {
      this.initialLoad = initialLoad;
    }
    public long getLoadPollInterval() {
      return this.loadPollInterval;
    }
    public void setLoadPollInterval(long v) {
      this.loadPollInterval = v;
    }

    /**
     * Used to process an incoming bridge server profile. Any controller in this
     * vm needs to be told about this incoming new bridge server. The reply
     * needs to contain any controller(s) that exist in this vm.
     * 
     * @since 5.7
     */
    @Override
    public void processIncoming(DistributionManager dm, String adviseePath,
        boolean removeProfile, boolean exchangeProfiles,
        final List<Profile> replyProfiles, LogWriterI18n logger) {
      // tell local controllers about this bridge server
      tellLocalControllers(removeProfile, exchangeProfiles, replyProfiles);
      // for QRM messaging we need bridge servers to know about each other
      tellLocalBridgeServers(removeProfile, exchangeProfiles, replyProfiles);
    }

    @Override
    public int getDSFID() {
      return BRIDGE_SERVER_PROFILE;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeStringArray(this.groups, out);
      out.writeInt(maxConnections);
      InternalDataSerializer.invokeToData(initialLoad, out);
      out.writeLong(getLoadPollInterval());
    }

    @Override
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.groups = DataSerializer.readStringArray(in);
      this.maxConnections = in.readInt();
      this.initialLoad = new ServerLoad();
      InternalDataSerializer.invokeFromData(initialLoad, in);
      setLoadPollInterval(in.readLong());
    }

    @Override
    public StringBuilder getToStringHeader() {
      return new StringBuilder("BridgeServerProfile");
    }

    @Override
    public void fillInToString(StringBuilder sb) {
      super.fillInToString(sb);
      if (this.groups != null) {
        sb.append("; groups=" + Arrays.asList(this.groups));
        sb.append("; maxConnections=" + maxConnections);
        sb.append("; initialLoad=" + initialLoad);
        sb.append("; loadPollInterval=" + getLoadPollInterval());
      }
    }
  }
}
