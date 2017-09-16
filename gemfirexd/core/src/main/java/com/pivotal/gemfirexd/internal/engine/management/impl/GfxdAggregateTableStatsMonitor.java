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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.management.ObjectName;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.management.internal.FederationComponent;
import com.gemstone.gemfire.management.internal.MBeanJMXAdapter;
import com.gemstone.gemfire.management.internal.cli.CliUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.management.TableMXBean;

/**
 *
 * @author Ajay Pande
 * @since gfxd 1.0
 */

public class GfxdAggregateTableStatsMonitor {
  private static final String POLICY = "Policy";
  private long numberOfRows = 0;
  private double entrySize = 0;
  FederationComponent newState,oldState = null;
  ObjectName objectName = null;

  protected LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();

  public void aggregate(FederationComponent newState,
      FederationComponent oldState, ObjectName objectName) {
      this.newState = newState;
      this.oldState = oldState;
      this.objectName = objectName;
  }

  public GfxdAggregateTableStatsMonitor() {
  }



  public double getEntrySize() {
    updateStats();
    return this.entrySize;
  }

  public long getNumberOfRows() {
    updateStats();
    return numberOfRows;
  }

  private synchronized void updateStats() {    
    try {
      String tableName = this.objectName.getKeyProperty("table");
      String policyName = new String();
      List<String> servergroups = new ArrayList<String>();    
   
      
      servergroups.add("DEFAULT");     
      
      
      if (this.oldState != null) {
        policyName = (String) this.oldState.getValue(POLICY);
        String[] grps = (String[]) this.oldState.getValue("ServerGroups");
        for (String grp : grps) {
          servergroups.add(grp);
        }       
      } else if(this.newState != null) {
        policyName = (String) this.newState.getValue(POLICY);
        String[] grps = (String[]) this.newState.getValue("ServerGroups");
        for (String grp : grps) {
          servergroups.add(grp);
        }
      }
      
      GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
      if (cache != null && tableName != null && policyName!= null &&
          !cache.isClosed() && !cache.isCacheAtShutdownAll()) {
        //reset before aggregation starts
        numberOfRows = 0;
        entrySize = 0.0;
        Set<DistributedMember> dsMembers = CliUtil.getAllMembers(cache);
        
        Iterator<DistributedMember> it = dsMembers.iterator();
        while (it.hasNext()) {
          DistributedMember dsMember = it.next();
          
          for (String serverGroup : servergroups) {
            try {              
              ObjectName tableObjectName = ManagementUtils.getTableMBeanName(
                  serverGroup, MBeanJMXAdapter.getMemberNameOrId(dsMember),
                  tableName);

              TableMXBean mbean = (TableMXBean) (InternalManagementService
                  .getAnyInstance().getMBeanInstance(tableObjectName,
                  TableMXBean.class));

              if (mbean != null) {
                if (policyName.toLowerCase().contains(
                    DataPolicy.PARTITION.toString().toLowerCase()) == true) {
                  numberOfRows += mbean.getNumberOfRows();
                  entrySize += mbean.getEntrySize();
                } else {
                  if(servergroups.size() > 1 ){
                    if (!serverGroup.equals("DEFAULT")) {
                      numberOfRows = mbean.getNumberOfRows();
                      entrySize = mbean.getEntrySize();
                    }
                  } else {
                    numberOfRows = mbean.getNumberOfRows();
                    entrySize = mbean.getEntrySize();
                  }
                }
              }
            } catch (Exception e) {
              logger.warning(LocalizedStrings.DEBUG, "exception in updatestats", e);
              //continue for other members
              continue;
            }
          }
        }
      }
    } catch (Exception ex) {
      logger.warning(LocalizedStrings.DEBUG, "exception occured in updatestats", ex);
    }   
  }
}
