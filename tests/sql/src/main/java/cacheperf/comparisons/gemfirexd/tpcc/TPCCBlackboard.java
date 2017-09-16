/*=========================================================================
 * Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *========================================================================
 */

package cacheperf.comparisons.gemfirexd.tpcc;

import hydra.blackboard.Blackboard;

public class TPCCBlackboard extends Blackboard {

  private static TPCCBlackboard blackboard;

  public TPCCBlackboard() {
  }

  public TPCCBlackboard(String name, String type) {
    super(name, type, TPCCBlackboard.class);
  }

  public static synchronized TPCCBlackboard getInstance() {
    if (blackboard == null) {
      blackboard = new TPCCBlackboard("TPCCBlackboard", "rmi");
    }
    return blackboard;
  }
}
