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

import java.util.Collections;
import java.util.Properties;

import junit.framework.Assert;
import junit.framework.TestCase;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.admin.DistributedSystemConfig;
import com.gemstone.gemfire.cache.client.internal.ServerBlackList.BlackListListenerAdapter;
import com.gemstone.gemfire.cache.client.internal.ServerBlackList.FailureTracker;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gemfire.internal.LocalLogWriter;
import com.gemstone.gemfire.internal.LogWriterImpl;

import io.snappydata.test.dunit.DistributedTestBase;
import io.snappydata.test.dunit.DistributedTestBase.WaitCriterion;

/**
 * @author dsmith
 *
 */
public class ServerBlackListJUnitTest extends TestCase {
  
  private ScheduledExecutorService background;
  protected ServerBlackList blackList;

  public void setUp()  throws Exception {
    LogWriter logger = new LocalLogWriter(LogWriterImpl.FINEST_LEVEL, System.out);
    Properties properties = new Properties();
    properties.put(DistributedSystemConfig.MCAST_PORT_NAME, "0");
    properties.put(DistributedSystemConfig.LOCATORS_NAME, "");
    background = Executors.newSingleThreadScheduledExecutor();
    
    blackList = new ServerBlackList(logger.convertToLogWriterI18n(), 100);
    blackList.start(background);
  }
  
  public void tearDown() {
    background.shutdownNow();
  }
  
  public void testBlackListing()  throws Exception {
    ServerLocation location1 = new ServerLocation("localhost", 1);
    FailureTracker tracker1 = blackList.getFailureTracker(location1);
    tracker1.addFailure();
    tracker1.addFailure();
    Assert.assertEquals(Collections.EMPTY_SET,  blackList.getBadServers());
    tracker1.addFailure();
    Assert.assertEquals(Collections.singleton(location1),  blackList.getBadServers());
    WaitCriterion ev = new WaitCriterion() {
      public boolean done() {
        return blackList.getBadServers().size() == 0;
      }
      public String description() {
        return "blackList still has bad servers";
      }
    };
    DistributedTestBase.waitForCriterion(ev, 10 * 1000, 200, true);
    Assert.assertEquals(Collections.EMPTY_SET,  blackList.getBadServers());
  }
  
  public void testListener()  throws Exception {
    
    final AtomicInteger adds = new AtomicInteger();
    final AtomicInteger removes = new AtomicInteger();
    blackList.addListener(new BlackListListenerAdapter() {

      public void serverAdded(ServerLocation location) {
        adds.incrementAndGet();
      }

      public void serverRemoved(ServerLocation location) {
        removes.incrementAndGet();
      }
    });
    
    ServerLocation location1 = new ServerLocation("localhost", 1);
    FailureTracker tracker1 = blackList.getFailureTracker(location1);
    tracker1.addFailure();
    tracker1.addFailure();
    
    Assert.assertEquals(0, adds.get());
    Assert.assertEquals(0, removes.get());
    tracker1.addFailure();
    Assert.assertEquals(1, adds.get());
    Assert.assertEquals(0, removes.get());
    WaitCriterion ev = new WaitCriterion() {
      public boolean done() {
        return removes.get() != 0;
      }
      public String description() {
        return "removes still empty";
      }
    };
    DistributedTestBase.waitForCriterion(ev, 10 * 1000, 200, true);
    Assert.assertEquals(1, adds.get());
    Assert.assertEquals(1, removes.get());
  }
}
