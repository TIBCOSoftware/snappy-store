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
package sql.rollingUpgrade;

import hydra.blackboard.Blackboard;

public class SQLRollingUpgradeBB extends Blackboard {
  public static SQLRollingUpgradeBB bbInstance = null;

  // Blackboard creation variables
  static String BB_NAME = "SQLRollingUpgrade_Blackboard";
  static String BB_TYPE = "RMI";

  // sharedCounters
  public static int loadCoordinator;
  public static int pausing;
  public static int recycledAllVMs;
  public static int locatorUpgradeComplete;
  public static int accessorUpgradeComplete;
  public static int accessorUpgradeWaiting;
  public static int remainingStoreUpgradeWaiting;
  public static int storeUpgradeComplete;
  public static int recycling;

  /**
   * Get the blackboard instance
   */
  public static SQLRollingUpgradeBB getBB() {
    if (bbInstance == null) {
      synchronized (SQLRollingUpgradeBB.class) {
        if (bbInstance == null)
          bbInstance = new SQLRollingUpgradeBB(BB_NAME, BB_TYPE);
      }
    }
    return bbInstance;
  }

  /**
   * Zero-arg constructor for remote method invocations.
   */
  public SQLRollingUpgradeBB() {
    // TODO Auto-generated constructor stub
  }

  /**
   * Creates a sample blackboard using the specified name and transport type.
   */
  public SQLRollingUpgradeBB(String name, String type) {
    super(name, type, SQLRollingUpgradeBB.class);
  }

}
