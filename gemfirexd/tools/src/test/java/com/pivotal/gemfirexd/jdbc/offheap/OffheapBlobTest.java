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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Iterator;

import org.junit.Assert;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.cache.CacheObserverHolder;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.OffHeapRegionEntry;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.jdbc.BlobTest;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase.RegionMapClearDetector;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

public class OffheapBlobTest extends BlobTest{

  private RegionMapClearDetector rmcd = null;	
  public static void main(String[] args) {
    TestRunner.run(new TestSuite(OffheapBlobTest.class));
  }

  public OffheapBlobTest(String name) {
    super(name);
  }

  
  @Override
  public void setUp() throws Exception {
    super.setUp();
    System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
    System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,"true");
    LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = true;
    rmcd = new JdbcTestBase.RegionMapClearDetector();
    CacheObserverHolder.setInstance(rmcd);
    GemFireXDQueryObserverHolder.putInstance(rmcd);
  }
  
  @Override
  public void tearDown() throws Exception {
    LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;
    CacheObserverHolder.setInstance(null);
	GemFireXDQueryObserverHolder.clearInstance();     
    super.tearDown();
    System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
    System.clearProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
    System.clearProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY);
  }
  
  public void testGfxdByteSourceBlobSerializationDeserialization()
      throws Exception {

    setupConnection();
    final Connection conn = jdbcConn;
    final String tableDDL = "create table X (id int , bytes1 blob, bytes2 blob ) replicate"
        + getSuffix();

    final Statement stmt = conn.createStatement();
    stmt.execute(tableDDL);
    try {
      // perform some inserts into the table
      final byte[] bytes1 = new byte[100];
      for (int i = 0; i < bytes1.length; ++i) {
        bytes1[i] = (byte) (i % 127);
      }
      final byte[] bytes2 = new byte[200];
      for (int i = 0; i < bytes2.length; ++i) {
        bytes2[i] = (byte) (i % 127);
      }
      
      PreparedStatement ps = conn.prepareStatement("insert into X values(?,?,?)");
      ps.setInt(1, 1);
      ps.setBytes(2, bytes1);
      ps.setBytes(3, bytes2);
      ps.executeUpdate();
      LocalRegion lr = (LocalRegion)Misc.getRegionForTable("APP.X", true);
      Iterator<Object> itr = lr.keySet().iterator();
      assertTrue(itr.hasNext());
     
      OffHeapRegionEntry ohe = (OffHeapRegionEntry)lr.basicGetEntry(itr.next());
      long address = ohe.getAddress();
      OffHeapRowWithLobs obs = (OffHeapRowWithLobs)OffHeapRegionEntryHelper
          .addressToObject(address, false);
      final byte[] byte1_ = obs.getGfxdBytes(1);
      final byte[] byte2_ = obs.getGfxdBytes(2);

      Assert.assertArrayEquals(bytes1, byte1_);
      Assert.assertArrayEquals(bytes2, byte2_);

      obs.getSerializedValue();
    } finally {

      // now do the same for partitioned table on non-pk
      stmt.execute("drop table X");
    }

  }
  @Override
  protected void doEndOffHeapValidations() throws Exception {
    //TODO:Asif: Remove this once the LOBS are correctly accounted for 
  }

  @Override
  public String getSuffix() {
    return " offheap ";
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
}
