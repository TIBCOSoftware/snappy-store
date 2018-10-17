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
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
package com.pivotal.gemfirexd.internal.hadoop.offheap;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.hadoop.EvictionByCriteria2Test;
import com.pivotal.gemfirexd.internal.hadoop.EvictionByCriteriaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public class OffHeapEvictionByCriteria2Test extends EvictionByCriteria2Test {

  @BeforeClass
  public static void createHDFSStore() throws Exception {
    EvictionByCriteriaTest.thisClass = OffHeapEvictionByCriteriaTest.class;
    System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
    System.setProperty("gemfire." + DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY, "true");
    EvictionByCriteriaTest.createHDFSStore();
  }

  @AfterClass
  public static void classTearDown() throws Exception {
    EvictionByCriteriaTest.classTearDown();
    System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
    System.clearProperty("gemfire." + DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
    System.clearProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY);
  }

  @Override
  protected String getOffHeapSuffix() {
    return " offheap ";
  }
}
