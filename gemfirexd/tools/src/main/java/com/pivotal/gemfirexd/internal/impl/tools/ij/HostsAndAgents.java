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

package com.pivotal.gemfirexd.internal.impl.tools.ij;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;

import com.gemstone.gnu.trove.TIntArrayList;

/**
 * @author kneeraj
 * 
 */
public class HostsAndAgents extends ijResultImpl {

  private ArrayList<String> hosts;

  private TIntArrayList ports;

  private SQLException exception;

  private String exMsg;

  private String noAliasMsg;

  private String alias;

  private static int DEFAULT_NETAGENT_PORT = 20002;
  
  public SQLWarning getSQLWarnings() throws SQLException {
    return null;
  }

  public void clearSQLWarnings() throws SQLException {
  }

  public void addHostAndAgent(String host, String netAgentPort) {
    //System.out.println("KN: HostaAndAgents addHostAndAgent called with host: " + host + " and port: " + netAgentPort);
    if (this.hosts == null || this.ports == null) {
      this.hosts = new ArrayList<String>();
      this.ports = new TIntArrayList();
    }
    //System.out.println("KN: HostaAndAgents addHostAndAgent 1");
    if (this.hosts.contains(host)) {
      // instead of throwing error, ignore this and continue to use
      // the first one.
      return;
    }
    int port = -1;
    if (netAgentPort != null) {
      port = Integer.parseInt(netAgentPort);
    }
    else {
      port = DEFAULT_NETAGENT_PORT;
    }
    //System.out.println("KN: HostaAndAgents addHostAndAgent 2");
    this.hosts.add(host);
    this.ports.add(port);
    //System.out.println("KN: HostaAndAgents addHostAndAgent 3");
  }

  public ArrayList<String> getHosts() {
    return this.hosts;
  }

  public TIntArrayList getPorts() {
    return this.ports;
  }

  public boolean isException() {
    return this.exception == null ? false : true;
  }

  public SQLException getException() {
    return this.exception;
  }
  
  public void setImproperHostsSpecified(String hostAgentListStr) {
    this.exMsg = "Improper hosts specified: " + hostAgentListStr;
  }
  
  public void setNoAliasSpecified() {
    this.noAliasMsg = "No alias specified Exception";
  }
  
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for(int i=0; i<this.hosts.size(); i++) {
      if (i == 0) {
        sb.append("alias=");
        sb.append(this.alias);
        sb.append(";");
        sb.append(" hostports: ");
      }
      sb.append(this.hosts.get(i));
      sb.append(":");
      sb.append(this.ports.get(i));
      sb.append(" ");
    }
    return sb.toString();
  }
  
  public void setAlias(String alias) {
    this.alias = alias;
  }
}
