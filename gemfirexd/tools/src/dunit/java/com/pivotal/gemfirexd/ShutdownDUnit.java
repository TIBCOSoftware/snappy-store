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
package com.pivotal.gemfirexd;

import java.util.Arrays;

import com.pivotal.gemfirexd.tools.GfxdSystemAdmin;

import io.snappydata.test.dunit.VM;

/**
 * Tests proper shutdown of a GemFireXD cluster.
 * @author sjigyasu
 *
 */

@SuppressWarnings("serial")
public class ShutdownDUnit extends DistributedSQLTestBase{

  public ShutdownDUnit(String name) {
    super(name);
  }

  public boolean verifyFabricServiceRunningStatus(boolean expectedRunning) {
    // Check that the FabricService is in RUNNING STATE on all servers
    int numServers = this.serverVMs.size();
    for (int i = 1; i <= numServers; i++) {
      VM executeVM = this.getServerVM(i);
      Object r = executeVM
          .invoke(ShutdownDUnit.class, "isFabricServiceRunning");
      if (!(r instanceof Boolean)) {
        return false;
      }
      Boolean running = (Boolean)r;
      if (!running.equals(expectedRunning)) {
        return false;
      }
    }
    return true;
  }

  public static Boolean isFabricServiceRunning() throws Exception {
    FabricService service = FabricServiceManager.currentFabricServiceInstance();
    // Return
    return service != null && service.status().equals(FabricService.State.RUNNING);
  }

  public void testBug45163() throws Exception{
    
    //Properties extraProps = new Properties();

    //extraProps.put("log-level", getGemFireLogLevel());
    startVMs(0, 3);

    assertTrue(verifyFabricServiceRunningStatus(true));

    new GfxdSystemAdmin().shutDownAll(
        "shut-down-all",
        Arrays.asList("-locators=" + getLocatorString(),
            "-log-level=fine"));

    waitForCriterion(new WaitCriterion() {
      @Override
      public boolean done() {
        // Check that the FabricService is not in RUNNING state on any server
        return verifyFabricServiceRunningStatus(false);
      }

      @Override
      public String description() {
        return "waiting for servers to stop";
      }
    }, 30000, 200, true);
  }
}
