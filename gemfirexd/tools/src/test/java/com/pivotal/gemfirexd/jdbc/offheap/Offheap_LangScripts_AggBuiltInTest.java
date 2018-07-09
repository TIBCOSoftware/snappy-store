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
package com.pivotal.gemfirexd.jdbc.offheap;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.jdbc.LangScripts_AggBuiltInTest;

public class Offheap_LangScripts_AggBuiltInTest extends LangScripts_AggBuiltInTest {


  public static void main(String[] args) {
    TestRunner.run(new TestSuite(Offheap_LangScripts_AggBuiltInTest.class));
  }
  
  
  public Offheap_LangScripts_AggBuiltInTest(String name) {
    super(name); 
  }
  
  
  @Override
  public void setUp() throws Exception {
    super.setUp();
    System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
    System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,"true");
  }
  
  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
    System.clearProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
    System.clearProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY);
  }
  
  @Override
  protected String getOffHeapSuffix() {
    return " offheap ";
  }
}
