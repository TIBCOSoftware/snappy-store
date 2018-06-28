/*=========================================================================
 * Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *========================================================================
 */

package cacheperf.comparisons.gemfirexd.tpcc;

public class ExpectedException extends hydra.HydraRuntimeException {

  public ExpectedException(String s) {
    super(s);
  }
  public ExpectedException(String s,Exception e) {
    super(s,e);
  }
}
