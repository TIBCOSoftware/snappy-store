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
package com.pivotal.gemfirexd.internal.engine.management;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.management.internal.MBeanJMXAdapter;
import com.gemstone.gemfire.management.internal.ManagementConstants;
import com.gemstone.gemfire.management.internal.cli.CliUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.management.impl.InternalManagementService;
import com.pivotal.gemfirexd.internal.engine.management.impl.ManagementUtils;
import com.pivotal.gemfirexd.internal.engine.management.impl.GfxdManagementTestBase;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;

import io.snappydata.test.dunit.VM;


/**
*
* @author Ajay Pande
* @since Helios
*/


public class GfxdMemberMBeanDUnit extends GfxdManagementTestBase {

  private static final long serialVersionUID = 1L;
  static final String grp1 = "GRP1";
  static final String grp2 = "GRP2";
  private static List<String> grps = new ArrayList<String>(); 
  

  /** The <code>MBeanServer</code> for this application */
  public static MBeanServer mbeanServer = ManagementFactory
      .getPlatformMBeanServer();

  
  public GfxdMemberMBeanDUnit(String name) {
    super(name);
    grps.add(GfxdMemberMBeanDUnit.grp1);
    grps.add(GfxdMemberMBeanDUnit.grp2);
    grps.add("DEFAULT");
  }

 
  public void testMemberstats() throws Exception {
    try {

      Properties serverInfo = new Properties();
      serverInfo.setProperty("gemfire.enable-time-statistics", "true");
      serverInfo.setProperty("statistic-sample-rate", "100");
      serverInfo.setProperty("statistic-sampling-enabled", "true");

      startServerVMs(1, 0, GfxdMemberMBeanDUnit.grp1, serverInfo);

      serverInfo.setProperty("jmx-manager", "true");
      serverInfo.setProperty("jmx-manager-start", "true");
      serverInfo.setProperty("jmx-manager-port", "0");// No need to start an
                                                      // Agent for this test

      startServerVMs(1, 0, GfxdMemberMBeanDUnit.grp2, serverInfo);
      
      Properties info = new Properties();
      info.setProperty("host-data", "false");
      info.setProperty("gemfire.enable-time-statistics", "true");

      // start a client, register the driver.
      startClientVMs(1, 0, GfxdMemberMBeanDUnit.grp1, info);
      
      checkMemberMBeanStats();      
      stopVMNums(1, -1);
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
      
    }
  }

  private void checkMemberMBeanStats() throws Exception {
    final VM serverVM1 = this.serverVMs.get(1); // Server Started as a manager  
    serverVM1.invoke(this.getClass(), "verifyMemberMbeans");   
  }

  public static void verifyMemberMbeans() {
    final InternalManagementService service = InternalManagementService.getInstance(Misc.getMemStore());
    final ObjectName distrObjectName = MBeanJMXAdapter
        .getObjectName(ManagementConstants.OBJECTNAME__AGGREGATEMEMBER_MXBEAN);    
    
    //wait till mbeans are in proper state
    waitForCriterion(new WaitCriterion() {
      public String description() {
        return "Waiting for the testAggregateMemberstats to get reflected at managing node";
      }

      public boolean done() {
        AggregateMemberMXBean bean = service.getMBeanInstance(distrObjectName,
            AggregateMemberMXBean.class);
        
        //check that there are default 4 members
        return bean != null && bean.getMembers().length == 4;
      }

    }, ManagementConstants.REFRESH_TIME * 4, 500, true);
    
      Set<DistributedMember> dsMembers = CliUtil.getAllMembers(Misc.getGemFireCacheNoThrow());      
      Iterator<DistributedMember> it = dsMembers.iterator();
      InternalDistributedSystem system = Misc.getDistributedSystem();
      StatisticsType connectionStatsType = system.findType(ConnectionStats.name);
      
      long peerConnectionsOpened = 0 ;
      long peerConnectionsClosed = 0 ;
      long peerConnectionsAttempted = 0 ;
      long peerConnectionsFailed = 0 ;
      long peerConnectionsLifeTime = 0 ;    
      
      
      long clientConnectionsOpened = 0 ;
      long clientConnectionsClosed = 0 ;
      long clientConnectionsAttempted = 0 ;
      long clientConnectionsFailed = 0 ;
      long clientConnectionsLifeTime = 0 ;      
      long clientConnectionsIdle = 0 ;      
      
      long nestedConnectionsOpened = 0;
      long nestedConnectionsClosed = 0;
      long nestedConnectionsOpen = 0;
      
      long internalConnectionsOpened = 0;
      long internalConnectionsClosed = 0;
      long internalConnectionsOpen = 0;     
      
      if (connectionStatsType != null) {
        Statistics[] foundStatistics = system.findStatisticsByType(connectionStatsType);
        
         for (Statistics statistics : foundStatistics) {               
           peerConnectionsOpened = statistics.getLong("peerConnectionsOpened") ;           
           peerConnectionsClosed = statistics.getLong("peerConnectionsClosed");
           peerConnectionsAttempted = statistics.getLong("peerConnectionsAttempted");
           peerConnectionsFailed = statistics.getLong("peerConnectionsFailed") ;
           peerConnectionsLifeTime = statistics.getLong("peerConnectionsLifeTime") ;
           
           clientConnectionsOpened = statistics.getLong("clientConnectionsOpened");
           clientConnectionsClosed = statistics.getLong("clientConnectionsClosed");
           clientConnectionsAttempted = statistics.getLong("clientConnectionsAttempted");
           clientConnectionsFailed = statistics.getLong("clientConnectionsFailed");
           clientConnectionsLifeTime = statistics.getLong("clientConnectionsLifeTime");           
           clientConnectionsIdle = statistics.getLong("clientConnectionsIdle");
           
           nestedConnectionsOpened = statistics.getLong("nestedConnectionsOpened");
           nestedConnectionsClosed = statistics.getLong("nestedConnectionsClosed");
           nestedConnectionsOpen = statistics.getLong("nestedConnectionsOpen"); 
           
           internalConnectionsOpened = statistics.getLong("internalConnectionsOpened");
           internalConnectionsClosed = statistics.getLong("internalConnectionsClosed");
           internalConnectionsOpen= statistics.getLong("internalConnectionsOpen");           
        }
      } else {
        //fail the test
        fail("For member=" + system.getMemberId() + " connectionStatsType is null");
      }     
      while (it.hasNext()) {
        DistributedMember dsMember = it.next();
        
        if(!dsMember.getId().equals(system.getMemberId())){
          continue;
        }
        
        ObjectName memberMBeanName = ManagementUtils.getMemberMBeanName( MBeanJMXAdapter.getMemberNameOrId(dsMember), "DEFAULT");        
        GfxdMemberMXBean mbean = (GfxdMemberMXBean) (InternalManagementService.getAnyInstance().getMBeanInstance(memberMBeanName,GfxdMemberMXBean.class));
        if (mbean != null) {       
          
          NetworkServerConnectionStats clientStats              = mbean.getNetworkServerClientConnectionStats();
          NetworkServerNestedConnectionStats internalStats      = mbean.getNetworkServerInternalConnectionStats();
          NetworkServerNestedConnectionStats nestedStats        = mbean.getNetworkServerNestedConnectionStats();
          NetworkServerConnectionStats peerStats                = mbean.getNetworkServerPeerConnectionStats();
          
          assertEquals(true, clientStats != null ? true : false);
          assertEquals(true, internalStats != null ? true : false);
          assertEquals(true, nestedStats != null ? true : false);
          assertEquals(true, peerStats != null ? true : false);
          assertEquals(true, mbean.fetchMetadata() != null ? true : false);
          
          //verify client stats
          logInfo("for member = " + dsMember.getId() + " clientConnectionsLifeTime=" + clientConnectionsLifeTime +  " from clientStats ="+clientStats.getConnectionLifeTime() );         
          assertEquals(clientStats.getConnectionLifeTime() , clientConnectionsLifeTime );
          
          logInfo("for member = " + dsMember.getId() + " clientConnectionsAttempted=" + clientConnectionsAttempted +  " from clientStats ="+clientStats.getConnectionsAttempted() );
          assertEquals(clientStats.getConnectionsAttempted() , clientConnectionsAttempted );
          
          logInfo("for member = " + dsMember.getId() + " clientConnectionsClosed=" + clientConnectionsClosed +  " from clientStats ="+clientStats.getConnectionsClosed() );
          assertEquals( clientStats.getConnectionsClosed() , clientConnectionsClosed );
          
          logInfo("for member = " + dsMember.getId() + " clientConnectionsFailed=" + clientConnectionsFailed +  " from clientStats ="+clientStats.getConnectionsFailed() );
          assertEquals(clientStats.getConnectionsFailed() , clientConnectionsFailed );
          
          logInfo("for member = " + dsMember.getId() + " clientConnectionsOpened=" + clientConnectionsOpened +  " from clientStats ="+clientStats.getConnectionsOpened() );
          assertEquals(clientStats.getConnectionsOpened() , clientConnectionsOpened );
          
          logInfo("for member = " + dsMember.getId() + " clientConnectionsIdle=" + clientConnectionsIdle +  " from clientStats ="+clientStats.getConnectionsIdle() );
          assertEquals(clientStats.getConnectionsIdle() , clientConnectionsIdle );         

          
          //verify peer stats
          logInfo("for member = " + dsMember.getId() + " peerConnectionsLifeTime=" + peerConnectionsLifeTime +  " from peerStats ="+peerStats.getConnectionLifeTime() );         
          assertEquals(peerStats.getConnectionLifeTime() , peerConnectionsLifeTime);
          
          logInfo("for member = " + dsMember.getId() + " peerConnectionsAttempted=" + peerConnectionsAttempted +  " from peerStats ="+peerStats.getConnectionsAttempted() );
          assertEquals(peerStats.getConnectionsAttempted() , peerConnectionsAttempted);
          
          logInfo("for member = " + dsMember.getId() + " peerConnectionsClosed=" + peerConnectionsClosed +  " from peerStats ="+peerStats.getConnectionsClosed() );
          assertEquals( peerStats.getConnectionsClosed() , peerConnectionsClosed );
          
          logInfo("for member = " + dsMember.getId() + " peerConnectionsFailed=" + peerConnectionsFailed +  " from peerStats ="+peerStats.getConnectionsFailed() );
          assertEquals( peerStats.getConnectionsFailed() , peerConnectionsFailed );
          
          logInfo("for member = " + dsMember.getId() + " peerConnectionsOpened=" + peerConnectionsOpened +  " from peerStats ="+peerStats.getConnectionsOpened() );
          assertEquals( peerStats.getConnectionsOpened() , peerConnectionsOpened );         
          

          
          //verify internal stats
          logInfo("for member = " + dsMember.getId() + " internalConnectionsOpen=" + internalConnectionsOpen  +  " from internalStats ="+internalStats.getConnectionsOpened() );
          assertEquals( internalStats.getConnectionsActive() , internalConnectionsOpen );
          
          
          logInfo("for member = " + dsMember.getId() + " internalConnectionsClosed=" + internalConnectionsClosed  +  " from internalStats ="+internalStats.getConnectionsClosed() );
          assertEquals(internalStats.getConnectionsClosed() , internalConnectionsClosed );
          
          logInfo("for member = " + dsMember.getId() + " internalConnectionsOpened=" + internalConnectionsOpened +  " from internalStats ="+internalStats.getConnectionsOpened() );
          assertEquals( internalStats.getConnectionsOpened() ,  internalConnectionsOpened );
          
          
          
          
          //verify nestedStats
          logInfo("for member = " + dsMember.getId() + " nestedConnectionsOpen=" + nestedConnectionsOpen +  " from nestedStats ="+nestedStats.getConnectionsActive() );
          assertEquals(nestedStats.getConnectionsActive() , nestedConnectionsOpen );
          
          logInfo("for member = " + dsMember.getId() + " nestedConnectionsClosed=" + nestedConnectionsClosed +  " from nestedStats ="+nestedStats.getConnectionsClosed() );
          assertEquals( nestedStats.getConnectionsClosed() , nestedConnectionsClosed );
          
          logInfo("for member = " + dsMember.getId() + " nestedConnectionsOpened=" + nestedConnectionsOpened +  " from nestedStats ="+nestedStats.getConnectionsOpened() );
          assertEquals(nestedStats.getConnectionsOpened() , nestedConnectionsOpened );        
          
          
          //verify eviction
          float evictionPercentage = 45.5f;
          mbean.updateEvictionPercent(evictionPercentage);
          logInfo("mbean.fetchEvictionPercent=" + mbean.fetchEvictionPercent());
          assertEquals( mbean.fetchEvictionPercent() , evictionPercentage );


          //check grps
          logInfo("mbean.getGroups().length==" + mbean.getGroups().length);
          assertEquals(true, mbean.getGroups().length >= 1 ? true : false);
          
          for(String str : mbean.getGroups()){
            logInfo("member grp = " + str);
            assertEquals(true, grps.contains(str) == true ? true : false);
          }
          
          //check member name and id
          logInfo("mbean member name=" + mbean.getName() + " from DS="+dsMember.getName());         
          assertEquals(mbean.getName(), dsMember.getName());
          
          logInfo("mbean member id=" + mbean.getId() + " from DS="+dsMember.getId());
          assertEquals(mbean.getId(), dsMember.getId());
                    
          logInfo("mbean.isDataStore=" + mbean.isDataStore());          
          assertEquals(true,  mbean.isDataStore() == true ? true : (mbean.isDataStore()  == false ? true : false));
          
          logInfo("mbean.isLocator=" + mbean.isLocator());
          assertEquals(true,  mbean.isLocator() == true ? true : (mbean.isLocator()  == false ? true : false));         
          
          //verify critical percentage
          //note: critical percentage must be greater than eviction percentage
          float criticalPercentage = evictionPercentage + 0.5f;
          mbean.updateCriticalPercent(criticalPercentage);
          logInfo("mbean.fetchCriticalPercent=" + mbean.fetchCriticalPercent());
          assertEquals(mbean.fetchCriticalPercent() ,  criticalPercentage );
          
        }
      }
  }

  @Override
  public void tearDown2() throws Exception {
    super.tearDown2();
  }
}