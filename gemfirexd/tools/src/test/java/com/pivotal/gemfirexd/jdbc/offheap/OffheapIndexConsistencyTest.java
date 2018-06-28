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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.derbyTesting.junit.JDBC;

import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.cache.CacheObserverHolder;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.jdbc.IndexConsistencyTest;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase.RegionMapClearDetector;
/**
 * 
 * @author asif
 *
 */
public class OffheapIndexConsistencyTest extends IndexConsistencyTest {
  private RegionMapClearDetector rmcd = null;	
	  
  public OffheapIndexConsistencyTest(String name) {
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

  @Override
  public String getSuffix() {
    return " offheap ";
  }

  @Override
  protected void additionalTableAttributes(
      RegionAttributesCreation expectedAttrs) {
    expectedAttrs.setEnableOffHeapMemory(true);
  }

  public void testIndexKeysDelete() throws Exception {
    Connection conn = null;
    Statement s = null;
    // Start one client and one server

    String updateQuery = "update Child set sector_id2 = ? where id2 = ?";

    // Create the table and insert a row
    conn = getConnection();
    s = conn.createStatement();
    s.execute("create table INSTRUMENTS (id1 int primary key, "
        + "sector_id1 int, subsector_id1 int,  indexCol1 int)  replicate " + getSuffix());
    s.execute("create table Child ( id2 int primary key, "
        + "sector_id2 int, subsector_id2 int, foreign key (sector_id2) "
        + "references instruments (id1) ) replicate" + getSuffix());
    s.execute("create index index1 on instruments(indexCol1)");
    try {
      s.execute("insert into instruments values (1,1,1,1)");
      s.execute("insert into instruments values (2,2,2,1)");
      s.execute("delete from instruments where  id1 = 1");
      s.execute("delete from instruments where id1 = 2");
      
      s.execute("insert into instruments values (1,1,1,1)");
      s.execute("insert into instruments values (2,2,2,1)");
      s.execute("insert into instruments values (3,3,3,1)");
      s.execute("update  instruments set indexCol1 =2 where id1 = 1");
      s.execute("delete from instruments where id1 = 2");
      s.execute("delete from instruments where id1 = 3");
      s.execute("delete from instruments where id1 = 1");
      /*s.execute("insert into Child values (1,1,1)");
      TestUtil.setupConnection();
      EmbedPreparedStatement es = (EmbedPreparedStatement) TestUtil.jdbcConn
          .prepareStatement(updateQuery);
      es.setInt(1, 2);
      es.setInt(2, 1);
      try {
        es.executeUpdate();
        fail("Update should not have occured as foreign key violation would occur");
      } catch (SQLException sqle) {
        // Log.getLogWriter().info("Expected exception="+sqle.getMessage());
        assertEquals(sqle.toString(), "23503", sqle.getSQLState());
      }*/
    } finally {
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter());
      conn = getConnection();
      s = conn.createStatement();
      s.execute("Drop table Child ");
      this.waitTillAllClear();
      s.execute("Drop table INSTRUMENTS ");
      this.waitTillAllClear();
    }
  }
  
  public void testIndexKeysUpdate() throws Exception {
    Connection conn = null;
    Statement s = null;
    // Start one client and one server
    conn = getConnection();
    s = conn.createStatement();
    try {
    s.execute("create table INSTRUMENTS (id1 int primary key, "
        + "sector_id1 int, subsector_id1 int  )  replicate " + getSuffix());
    s.execute("create table Child ( id2 int primary key, "
        + "sector_id2 int, subsector_id2 int,  indexCol1 int,foreign key (sector_id2) "
        + "references instruments (id1) ) replicate" + getSuffix());
    s.execute("create index index1 on child(indexCol1)");
   
      s.execute("insert into instruments values (1,1,1)");
      s.execute("insert into child values (1,1,1,1)");
      s.execute("insert into child values (2,1,2,1)");
      
      s.execute("update  child set indexCol1 =2 where id2 = 1");
      
      ResultSet rs = s.executeQuery("select * from child where sector_id2 = 1");
      int count = 0;
      while(rs.next()) {
        rs.getInt(1);
        ++count;
      }
      assertEquals(2, count);
      s.execute("delete from child where  sector_id2 = 1");
      s.execute("delete from instruments where id1 = 1");
      
    }catch(SQLException sqle) {
      sqle.printStackTrace();
      throw sqle;
    }
    finally {
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter());
      conn = getConnection();
      s = conn.createStatement();
      s.execute("Drop table Child ");
      this.waitTillAllClear();
      s.execute("Drop table INSTRUMENTS ");
      this.waitTillAllClear();
    }
  }
  
  /**
   * The test checks if in between postEvent index maintenance & region entry destroy , would the address become invalid
   * such that the index key if using that adress will encounter problem. That race condition is not there because the
   * final freeing of destroyed address happens at the very exit of destroy function thru .EntryEventImpl.freeOffHeapResources
   * @throws Exception
   */
  public void testAnyRaceOnIndexKeyForDelete() throws Exception {

    // Bug #51842
    if (isTransactional) {
      return;
    }
    
    // Start one client and one server
     Connection conn = getConnection();
     Statement s = conn.createStatement();
    try {
      s.execute("create table INSTRUMENTS (id1 int primary key, "
          + "sector_id1 int, subsector_id1 int  )  replicate " + getSuffix());
      s.execute("create table Child ( id2 int primary key, "
          + "sector_id2 int, subsector_id2 int,  indexCol1 int,foreign key (sector_id2) "
          + "references instruments (id1) ) replicate" + getSuffix());
      s.execute("create index index1 on child(indexCol1)");

      s.execute("insert into instruments values (1,1,1)");
      s.execute("insert into child values (1,1,1,1)");
      s.execute("insert into child values (2,1,2,1)");
      final boolean[] proceed = { false };
      final boolean[] exceptionOccured = { false };
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void keyAndContainerBeforeLocalIndexDelete(Object key,
            Object rowLocation, GemFireContainer container) {
          synchronized (OffheapIndexConsistencyTest.this) {
            if (!proceed[0]) {
              proceed[0] = true;
              OffheapIndexConsistencyTest.this.notify();
              try {
                OffheapIndexConsistencyTest.this.wait();
              } catch (InterruptedException ie) {
                ie.printStackTrace();
                exceptionOccured[0] = true;
              }
            }
          }

        }
      });
      Thread th = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            Connection conn1 = getConnection();
            Statement stmt = conn1.createStatement();
            stmt.execute("delete from  child  where id2 = 1");
          } catch (SQLException e) {
            exceptionOccured[0] = true;
          }
        }
      });

      th.start();

      synchronized (this) {
        if (!proceed[0]) {
          this.wait();
        }
      }
      try {
        ResultSet rs = s
            .executeQuery("select * from child where sector_id2 = 1");
        int count = 0;
        while (rs.next()) {
          rs.getInt(1);
          ++count;
        }
        assertEquals(2, count);
      } finally {
        synchronized (this) {
          this.notify();
        }
      }

      s.execute("delete from child where  sector_id2 = 1");
      s.execute("delete from instruments where id1 = 1");

    } catch (SQLException sqle) {
      sqle.printStackTrace();
      throw sqle;
    } finally {
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter());
      s.execute("Drop table Child ");
      this.waitTillAllClear();
      s.execute("Drop table INSTRUMENTS ");
      this.waitTillAllClear();
    }

  }
  
 /** The test checks if in between postEvent index maintenance & region entry put , would the old address become invalid
  * such that the index key if using that address will encounter problem. That race condition is not there because the
  * final freeing of old  address happens at the very exit of update function thru .EntryEventImpl.freeOffHeapResources
  */
  public void testAnyRaceOnIndexKeyForUpdate() throws Exception {

    // Start one client and one server
     Connection conn = getConnection();
     Statement s = conn.createStatement();
    try {
      s.execute("create table INSTRUMENTS (id1 int primary key, "
          + "sector_id1 int, subsector_id1 int  )  replicate " + getSuffix());
      s.execute("create table Child ( id2 int primary key, "
          + "sector_id2 int, subsector_id2 int,  indexCol1 int,foreign key (sector_id2) "
          + "references instruments (id1) ) replicate" + getSuffix());
      s.execute("create index index1 on child(indexCol1)");

      s.execute("insert into instruments values (1,1,1)");
      s.execute("insert into instruments values (2,2,1)");
      
      s.execute("insert into child values (1,1,1,1)");
      s.execute("insert into child values (2,1,2,1)");
      final boolean[] proceed = { false };
      final boolean[] exceptionOccured = { false };
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void keyAndContainerBeforeLocalIndexDelete(Object key,
            Object rowLocation, GemFireContainer container) {
          synchronized (OffheapIndexConsistencyTest.this) {
            if (!proceed[0]) {
              proceed[0] = true;
              OffheapIndexConsistencyTest.this.notify();
              try {
                OffheapIndexConsistencyTest.this.wait();
              } catch (InterruptedException ie) {
                ie.printStackTrace();
                exceptionOccured[0] = true;
              }
            }
          }

        }
      });
      Thread th = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            Connection conn1 = getConnection();
            Statement stmt = conn1.createStatement();
            stmt.execute("update   child  set sector_id2 = 2 where id2 = 1");
          } catch (SQLException e) {
            exceptionOccured[0] = true;
          }
        }
      });

      th.start();

      synchronized (this) {
        if (!proceed[0]) {
          this.wait();
        }
      }
      try {
        ResultSet rs = s
            .executeQuery("select * from child where sector_id2 = 1");
        int count = 0;
        while (rs.next()) {
          rs.getInt(1);
          ++count;
        }
        assertTrue(count <=2 && count >=1);
      } finally {
        synchronized (this) {
          this.notify();
        }
      }

      s.execute("delete from child where  sector_id2 = 1");
      s.execute("delete from instruments where id1 = 1");

    } catch (SQLException sqle) {
      sqle.printStackTrace();
      throw sqle;
    } finally {
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter());
      s.execute("Drop table Child ");
      this.waitTillAllClear();
      s.execute("Drop table INSTRUMENTS ");
      this.waitTillAllClear();
    }

  }
  
  public void testOffHeapMemoryReleaseOnUniqueConstraintViolation()
      throws Exception {

    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null, "
        + " SECONDID int not null, THIRD int not null, FOURTH int not null, FIFTH int not null,"
        + " primary key (ID, SECONDID))" + " PARTITION BY COLUMN (ID)"
        + getSuffix());
    s.execute("create unique index third_index on EMP.PARTITIONTESTTABLE (THIRD)");
    // s.execute("create index fourth_index on EMP.PARTITIONTESTTABLE (FOURTH)");
    // s.execute("create unique index fifth_index on EMP.PARTITIONTESTTABLE (fifth)");

    s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(1,2,3,4, 10)");
    s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(4,5,6,4, 11)");
    // s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(7,8,9,4, 12)");
    addExpectedException(EntryExistsException.class);
    try { 
      s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(1,3,3,4,13)");
      fail("Exception is expected!");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    this.doOffHeapValidations();
    try {
      s.execute("UPDATE EMP.PARTITIONTESTTABLE SET third=6, fifth=20 where id=1 and secondid=2");
      fail("Exception is expected!");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    this.doOffHeapValidations();
    // drop the table
    s.execute("drop table EMP.PARTITIONTESTTABLE");
    this.waitTillAllClear();
    // drop schema and shutdown
    s.execute("drop schema EMP RESTRICT");
    this.waitTillAllClear();
    this.doOffHeapValidations();

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
