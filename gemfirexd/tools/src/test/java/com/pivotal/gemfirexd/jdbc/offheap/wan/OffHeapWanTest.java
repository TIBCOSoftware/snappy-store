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
package com.pivotal.gemfirexd.jdbc.offheap.wan;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.cache.CacheObserverHolder;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import com.pivotal.gemfirexd.jdbc.WanTest;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase.RegionMapClearDetector;

public class OffHeapWanTest extends WanTest {
  private RegionMapClearDetector rmcd = null;	
	 	
  public OffHeapWanTest(String name) {
    super(name);
  }

  @Override
  protected void setUp() throws Exception {
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,"true");
    System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    System.setProperty("gemfirexd.TEST_FLAG_OFFHEAP_ENABLE","true");
    super.setUp();
    LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = true;
    rmcd = new JdbcTestBase.RegionMapClearDetector();
    CacheObserverHolder.setInstance(rmcd);
    GemFireXDQueryObserverHolder.putInstance(rmcd);
   
  }    
  
  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;
	CacheObserverHolder.setInstance(null);
	GemFireXDQueryObserverHolder.clearInstance();
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,"false");    
  }

  @Override
  public void testGatewayReceivers() throws Exception {
    // skip in offheap since it is identical and can clash in parallel test runs
  }

  protected String getCreateTestTableSQL() {
    return super.getCreateTestTableSQL() + " OFFHEAP";
  }  
  
  protected String getCreateTestTableWithoutAsyncEventListenerSQL() {
    return super.getCreateTestTableWithoutAsyncEventListenerSQL() + " OFFHEAP";
  }
  
  @Override
  protected String getCreateAppSG1TestTableSQL() {
    return super.getCreateAppSG1TestTableSQL() + " OFFHEAP";
  }

  @Override
  protected String getCreateAppSG2TestTableSQL() {
    return super.getCreateAppSG2TestTableSQL() + " OFFHEAP";
  }
  
  @Override
  protected String getSQLSuffixClause() {
    return super.getSQLSuffixClause() + " OFFHEAP";
  }

  @Override
  public void testMultipleAsyncEventListeners_Bug43419() throws Exception {
    super.testMultipleAsyncEventListeners_Bug43419();
  }
  
  @Override
  public void waitTillAllClear() {
	try {  
    rmcd.waitTillAllClear();
	}catch(InterruptedException ie) {
	  Thread.currentThread().interrupt();
	  throw new GemFireXDRuntimeException(ie);
	}
  }

  public void testDBSynchronizerForSkipListenerPR() throws Exception {
    // do nothing .. // SNAP-1586
  }
}