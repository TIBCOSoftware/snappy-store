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
package com.pivotal.gemfirexd.jdbc.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;

public class LocalIndexTxTest extends JdbcTestBase{

  public LocalIndexTxTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(LocalIndexTxTest.class));
  }

  /*
   * Disabled since it no longer holds with the new on-the-fly index maintenance
  public void testLocalIndexKeyImpactedByTxSingleColIndex() throws Exception {
    //Test local index impacted by tx insert

   String baseTable = "T1";
  
    try {
      java.sql.Connection conn = getConnection();
      String schema = TestUtil.getCurrentDefaultSchemaName();
      conn.setTransactionIsolation(getIsolationLevel());
      Statement st = conn.createStatement();
      st
          .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null,"
              + " c4 int not null , c5 int not null ," + " primary key(c1)) ");
      st.execute("create index i1 on t1 (c5)");
      conn.commit();
      PreparedStatement pstmt = conn
          .prepareStatement("insert into t1 values(?,?,?,?,?)");
      for (int i = 1; i <41; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();

      }    
      //Get GFT
      GemFireTransaction gft = getGFT((EmbedConnection)conn);     
      String regionName = Misc.getRegionPath(schema, baseTable, null);
      LocalRegion baseTableRegion = (LocalRegion)Misc.getRegion(regionName);
      GemFireContainer baseContainer = (GemFireContainer)baseTableRegion.getUserAttribute();
      GemFireContainer indexContainer = getIndexContainer(baseTableRegion, schema + ".I1:base-table:"+schema+".T1") ;        
      ExtraTableInfo eti = indexContainer.getExtraTableInfo();     
      FormatableBitSet indexCols = eti.getIndexedCols();
      assertTrue(gft.isLocalIndexImpacted(baseContainer, indexCols));      
      conn.commit();
      
      //Now the local index should not be impacted as commit happened
      assertFalse(gft.isLocalIndexImpacted(baseContainer, indexCols));
      //Now do bulk update  which modifies index column.  
      //this should impact local index      
      assertEquals(3,st.executeUpdate("update t1 set c5 = 10 where " +
      		"c1 > 3 and c1 <7 "));
      assertTrue(gft.isLocalIndexImpacted(baseContainer, indexCols));
      conn.commit();      
     //Check behaviour for PK based delete
      assertEquals(1,st.executeUpdate("delete from t1 where c1 = 31 "));
      assertTrue(gft.isLocalIndexImpacted(baseContainer, indexCols));
      conn.commit();
    //Check behaviour for bulk delete
      assertEquals(3,st.executeUpdate("delete from t1 where c3 > 31  and c3 < 35 "));
      assertTrue(gft.isLocalIndexImpacted(baseContainer, indexCols));
      conn.commit();
      
    //Now do Pk based update  which modifies index column.  
      //this should impact local index      
      assertEquals(1,st.executeUpdate("update t1 set c5 = 10 where c1 = 15 "));
      assertTrue(gft.isLocalIndexImpacted(baseContainer, indexCols));
      conn.commit();
      
      //Now the local index should not be impacted as commit happened
      assertFalse(gft.isLocalIndexImpacted(baseContainer, indexCols));      
      
      //Now do bulk update  which does not modify index column.  
      //this should not impact local index      
      assertEquals(3,st.executeUpdate("update t1 set c4 = 10 where " +
                "c1 > 3 and c1 <7 "));
      assertFalse(gft.isLocalIndexImpacted(baseContainer, indexCols));
      conn.commit();
      
    //Now do PK based update  which does not modify index column.  
      //this should not impact local index      
      assertEquals(1,st.executeUpdate("update t1 set c4 = 10 where c1 = 18 "));
      assertFalse(gft.isLocalIndexImpacted(baseContainer, indexCols));
      conn.commit();
      assertFalse(gft.isLocalIndexImpacted(baseContainer, indexCols));
      for (int i = 42; i <50; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();
      }
      assertTrue(gft.isLocalIndexImpacted(baseContainer, indexCols));
      assertEquals(3,st.executeUpdate("update t1 set c4 = 10 where " +
      "c1 > 3 and c1 <7 "));
      //follow it with update which is on different col, 
      //this should not impact local index affected
      assertTrue(gft.isLocalIndexImpacted(baseContainer, indexCols));
      conn.commit();
      
    }
    finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }    
  }
  */

  public void testTransactionalUpdateWithLocalIndexModified_EqualityCondition() throws Exception {

    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    Statement st = conn.createStatement();
    st .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
                " c4 int not null , c5 int not null ," +
                " primary key(c1)) ");
    st.execute("create index i1 on t1 (c5)");
    conn.commit();
    PreparedStatement pstmt = conn.prepareStatement("insert into t1 values(?,?,?,?,?)");
    for(int i = 1 ;i < 21;++i) {
      pstmt.setInt(1,i);
      pstmt.setInt(2,i);
      pstmt.setInt(3,i);
      pstmt.setInt(4,i);
      pstmt.setInt(5,i);
      pstmt.executeUpdate();
      
    }
    conn.commit();
   
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerCostForMemHeapScan(
          GemFireContainer gfContainer, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return 1;
      }
    };
    GemFireXDQueryObserverHolder.setInstance(observer);   
    assertEquals(1,st.executeUpdate("update t1 set c5 =5 where c5 =1"));
    //Two rows should get modified . one in commited c5 = 5 and the other in tx area c5 =1
    assertEquals(2,st.executeUpdate("update t1 set c2 = 8  where c5 = 5"));
    conn.commit();
    
    try {
      ResultSet rs = st.executeQuery("Select c2 from t1 where c5 =5");     
      assertTrue(rs.next());
      assertEquals(8,rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(8,rs.getInt(1));
      conn.commit();
    }
    finally {
      st.execute("drop table t1");
      GemFireXDQueryObserverHolder.clearInstance();
    }  
  }
  
  public void testTransactionalUpdateWithLocalIndexModified_Range_1() throws Exception {

    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    Statement st = conn.createStatement();
    st .execute("Create table t1 (c1 int  , c2 int , c3 int ," +
                " c4 int  , c5 int ," +
                " primary key(c1)) ");
    st.execute("create index i1 on t1 (c5)");
    conn.commit();
    PreparedStatement pstmt = conn.prepareStatement("insert into t1 values(?,?,?,?,?)");
    for(int i = 1 ;i < 31;++i) {
      pstmt.setInt(1,i);
      pstmt.setInt(2,i);
      pstmt.setInt(3,i);
      pstmt.setInt(4,i);
      pstmt.setInt(5,i);
      pstmt.executeUpdate();
      
    }
    conn.commit();
   
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerCostForMemHeapScan(
          GemFireContainer gfContainer, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return 1;
      }
    };
    GemFireXDQueryObserverHolder.setInstance(observer);   
    assertEquals(11,st.executeUpdate("update t1 set c5 = 25 where c5  > 9 and c5 < 21"));
    //Two rows should get modified . one in commited c5 = 5 and the other in tx area c5 =1
    assertEquals(21,st.executeUpdate("update t1 set c2 = 8  where c5 > 20"));
    conn.commit();
    try {
      ResultSet rs = st.executeQuery("Select c2 from t1 where c5 > 20");     
      for(int i = 1; i < 22; ++i) {
        assertTrue(rs.next());
        assertEquals(8,rs.getInt(1));
      }
      assertFalse(rs.next());
      conn.commit();
      GemFireXDQueryObserverHolder.clearInstance(); 
      assertEquals(4,st.executeUpdate("update t1 set c5 = 10  where c1 > 15 and c1 < 20"));
      GemFireXDQueryObserverHolder.setInstance(observer);
      assertEquals(4,st.executeUpdate("update t1 set c2 = 1   where c5 > 9 and c5 < 13"));
      conn.commit();
     
        rs = st.executeQuery("Select c2 from t1 where c5 > 9 and c5 < 13");     
        for(int i = 1; i < 5; ++i) {
          assertTrue(rs.next());
          assertEquals(1,rs.getInt(1));
        }
        assertFalse(rs.next());
    }
    finally {
      st.execute("drop table t1");
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testTransactionalUpdateWithLocalIndexModified_Range_DeferredUpdate()
      throws Exception {

    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    Statement st = conn.createStatement();
    st .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
                " c4 int not null , c5 int not null ," +
                " primary key(c1)) ");
    st.execute("create index i1 on t1 (c5)");
    conn.commit();
    PreparedStatement pstmt = conn.prepareStatement("insert into t1 values(?,?,?,?,?)");
    for(int i = 1 ;i < 31;++i) {
      pstmt.setInt(1,i);
      pstmt.setInt(2,i);
      pstmt.setInt(3,i);
      pstmt.setInt(4,i);
      pstmt.setInt(5,i);
      pstmt.executeUpdate();
      
    }
    conn.commit();
   
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerCostForMemHeapScan(
          GemFireContainer gfContainer, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return 1;
      }
    };
    GemFireXDQueryObserverHolder.setInstance(observer);   
    assertEquals(11,st.executeUpdate("update t1 set c5 = 25 where c5  > 9 and c5 < 21"));
    //Two rows should get modified . one in commited c5 = 5 and the other in tx area c5 =1
    assertEquals(21,st.executeUpdate("update t1 set c2 = 8  where c5 > 20"));
    conn.commit();
    try {
      ResultSet rs = st.executeQuery("Select c2 from t1 where c5 > 20");     
      for(int i = 1; i < 22; ++i) {
        assertTrue(rs.next());
        assertEquals(8,rs.getInt(1));
      }
      conn.commit();
    }
    finally {
      st.execute("drop table t1");
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testTxUpdateOnTxInsert_Bug42177() throws Exception {

    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    Statement st = conn.createStatement();
    st .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
                " c4 int not null , c5 int not null ," +
                " primary key(c1)) ");
    st.execute("create index i1 on t1 (c5)");
    conn.commit();
    PreparedStatement pstmt = conn.prepareStatement("insert into t1 values(?,?,?,?,?)");
    for(int i = 1 ;i < 10;++i) {
      pstmt.setInt(1,i);
      pstmt.setInt(2,i);
      pstmt.setInt(3,i);
      pstmt.setInt(4,i);
      pstmt.setInt(5,i);
      pstmt.executeUpdate();
      
    }    
    conn.commit();
    
    pstmt = conn.prepareStatement("insert into t1 values(?,?,?,?,?)");
    for(int i = 10 ;i < 21;++i) {
      pstmt.setInt(1,i);
      pstmt.setInt(2,i);
      pstmt.setInt(3,i);
      pstmt.setInt(4,i);
      pstmt.setInt(5,i);
      pstmt.executeUpdate();
      
    }    
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerCostForMemHeapScan(
          GemFireContainer gfContainer, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return 1;
      }
    };
    GemFireXDQueryObserverHolder.setInstance(observer);   
    assertEquals(1,st.executeUpdate("update t1 set c5 =5 where c5 =1"));
    //Two rows should get modified . one in commited c5 = 5 and the other in tx area c5 =1
    assertEquals(2,st.executeUpdate("update t1 set c2 = 8  where c5 = 5"));
    conn.commit();
    try {
      ResultSet rs = st.executeQuery("Select c2 from t1 where c5 =5");     
      assertTrue(rs.next());
      assertEquals(8,rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(8,rs.getInt(1));
      conn.commit();
    }
    finally {
      st.execute("drop table t1");
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testTxInsertUpdateDeleteCombo() throws Exception {

    try {
      java.sql.Connection conn = getConnection();
      conn.setTransactionIsolation(getIsolationLevel());
      Statement st = conn.createStatement();
      st
          .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null,"
              + " c4 int not null , c5 int not null , primary key(c1)) ");
      st.execute("create index i1 on t1 (c5)");
      conn.commit();
      PreparedStatement pstmt = conn
          .prepareStatement("insert into t1 values(?,?,?,?,?)");
      for (int i = 1; i < 6; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();

      }

      for (int i = 31; i < 41; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();

      }
      conn.commit();

      pstmt = conn.prepareStatement("insert into t1 values(?,?,?,?,?)");
      for (int i = 6; i < 31; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();

      }
      GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
        @Override
        public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
            OpenMemIndex memIndex, double optimzerEvalutatedCost)
        {
          return Double.MAX_VALUE;
        }

        @Override
        public double overrideDerbyOptimizerCostForMemHeapScan(
            GemFireContainer gfContainer, double optimzerEvalutatedCost)
        {
          return Double.MAX_VALUE;
        }

        @Override
        public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
            OpenMemIndex memIndex, double optimzerEvalutatedCost)
        {
          return 1;
        }
      };
      GemFireXDQueryObserverHolder.setInstance(observer);
      // Make c5 = 5 for commited and non commited insert
      assertEquals(10, st
          .executeUpdate("update t1 set c5 =5 where c5 > 0 and c5 < 11"));
      // c2 would be set to 8 for 1 to 10 and then 15 to 20
      assertEquals(16,st.executeUpdate("update t1 set c2 = 8 " +
                " where c5 = 5 or ( c5 > 14 and c5 < 21)"));
      // 22 rows should be deleted some txnl and some committed
      assertEquals(22,st.executeUpdate("delete from t1 where c2 = 8 or c2 > 34"));
      conn.commit();
      st.execute("drop table t1");
    }
    finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testTxDeleteImpactOnLocalIndex() throws Exception
  {

    try {
      java.sql.Connection conn = getConnection();
      conn.setTransactionIsolation(getIsolationLevel());
      Statement st = conn.createStatement();
      st
          .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null,"
              + " c4 int not null , c5 int not null ," + " primary key(c1)) ");
      st.execute("create index i1 on t1 (c5)");
      conn.commit();
      PreparedStatement pstmt = conn
          .prepareStatement("insert into t1 values(?,?,?,?,?)");
      for (int i = 1; i < 41; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();

      }
      
      conn.commit();
      GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
        @Override
        public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
            OpenMemIndex memIndex, double optimzerEvalutatedCost)
        {
          return Double.MAX_VALUE;
        }

        @Override
        public double overrideDerbyOptimizerCostForMemHeapScan(
            GemFireContainer gfContainer, double optimzerEvalutatedCost)
        {
          return Double.MAX_VALUE;
        }

        @Override
        public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
            OpenMemIndex memIndex, double optimzerEvalutatedCost)
        {
          return 1;
        }
      };
      GemFireXDQueryObserverHolder.setInstance(observer);
      assertEquals(6, st
          .executeUpdate("Delete from t1  where c5 > 14 and c5 <= 20"));
      // Make c5 = 5 for commited and non commited insert
      /*assertEquals(10, st
          .executeUpdate("update t1 set c5 =5 where c5 > 0 and c5 < 11"));*/
      // c2 would be set to 8 for 1 to 10 and then 15 to 20
      assertEquals(14,st.executeUpdate("update t1 set c2 = 8 " +
                " where c5 >= 1 and c5 < 21 "));     
      conn.commit();
      st.execute("drop table t1");
    }
    finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testTxPKDeleteImpactOnLocalIndex() throws Exception
  {

    try {
      java.sql.Connection conn = getConnection();
      conn.setTransactionIsolation(getIsolationLevel());
      Statement st = conn.createStatement();
      st
          .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null,"
              + " c4 int not null , c5 int not null ," + " primary key(c1)) ");
      st.execute("create index i1 on t1 (c5)");
      conn.commit();
      PreparedStatement pstmt = conn
          .prepareStatement("insert into t1 values(?,?,?,?,?)");
      for (int i = 1; i <31; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();

      }     
      conn.commit();
      
      assertEquals(1, st
          .executeUpdate("Delete from t1  where c1 = 10"));
      GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
        @Override
        public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
            OpenMemIndex memIndex, double optimzerEvalutatedCost)
        {
          return Double.MAX_VALUE;
        }

        @Override
        public double overrideDerbyOptimizerCostForMemHeapScan(
            GemFireContainer gfContainer, double optimzerEvalutatedCost)
        {
          return Double.MAX_VALUE;
        }

        @Override
        public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
            OpenMemIndex memIndex, double optimzerEvalutatedCost)
        {
          return 1;
        }
      };
      GemFireXDQueryObserverHolder.setInstance(observer);      
      // Make c5 = 5 for commited and non commited insert
      /*assertEquals(10, st
          .executeUpdate("update t1 set c5 =5 where c5 > 0 and c5 < 11"));*/
      // c2 would be set to 8 for 1 to 10 and then 15 to 20
      assertEquals(19,st.executeUpdate("update t1 set c2 = 8 " +
                " where c5 >= 1 and c5 < 21 "));
     
      conn.commit();
      st.execute("drop table t1");
    }
    finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testTxPKUpdateImpactOnLocalIndex() throws Exception
  {

    try {
      java.sql.Connection conn = getConnection();
      conn.setTransactionIsolation(getIsolationLevel());
      Statement st = conn.createStatement();
      st
          .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null,"
              + " c4 int not null , c5 int not null ," + " primary key(c1)) ");
      st.execute("create index i1 on t1 (c5)");
      conn.commit();
      PreparedStatement pstmt = conn
          .prepareStatement("insert into t1 values(?,?,?,?,?)");
      for (int i = 1; i <31; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();

      }     
      conn.commit();
      
      assertEquals(1, st
          .executeUpdate("update t1 set c5 = 20   where c1 = 15"));
      GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
        @Override
        public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
            OpenMemIndex memIndex, double optimzerEvalutatedCost)
        {
          return Double.MAX_VALUE;
        }

        @Override
        public double overrideDerbyOptimizerCostForMemHeapScan(
            GemFireContainer gfContainer, double optimzerEvalutatedCost)
        {
          return Double.MAX_VALUE;
        }

        @Override
        public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
            OpenMemIndex memIndex, double optimzerEvalutatedCost)
        {
          return 1;
        }
      };
      GemFireXDQueryObserverHolder.setInstance(observer);     
      // c2 would be set to 8 for 1 to 10 and then 15 to 20
      assertEquals(9,st.executeUpdate("update t1 set c2 = 8 " +
                " where c5 >= 18 and c5 < 26 "));

      conn.commit();
      st.execute("drop table t1");
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }


  public void testTxInsertUpdateDeleteCombo_PR() throws Exception {
    txInsertUpdateDeleteComboForBatching(true);
  }

  public void testTxInsertUpdateDeleteCombo_RR() throws Exception {
    txInsertUpdateDeleteComboForBatching(false);
  }

  protected int getIsolationLevel() {
    return Connection.TRANSACTION_READ_COMMITTED;
  }

  private void txInsertUpdateDeleteComboForBatching(boolean isPR)
      throws Exception {
    Connection conn = TestUtil.getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null, c2 int not null, "
        + "c3 int not null, c4 int not null, c5 int not null ,"
        + " primary key(c1)) " + (isPR ? "redundancy 2" : "replicate"));
    st.execute("create index i1 on t1 (c5)");
    st.execute("create index i2 on t1 (c2)");
    conn.commit();

    PreparedStatement pstmt = conn
        .prepareStatement("insert into t1 values(?,?,?,?,?)");
    for (int i = 1; i < 6; ++i) {
      pstmt.setInt(1, i);
      pstmt.setInt(2, i);
      pstmt.setInt(3, i);
      pstmt.setInt(4, i);
      pstmt.setInt(5, i);
      pstmt.executeUpdate();
    }

    for (int i = 31; i < 41; ++i) {
      pstmt.setInt(1, i);
      pstmt.setInt(2, i);
      pstmt.setInt(3, i);
      pstmt.setInt(4, i);
      pstmt.setInt(5, i);
      pstmt.executeUpdate();
    }
    conn.commit();

    pstmt = conn.prepareStatement("insert into t1 values(?,?,?,?,?)");
    for (int i = 6; i < 31; ++i) {
      pstmt.setInt(1, i);
      pstmt.setInt(2, i);
      pstmt.setInt(3, i);
      pstmt.setInt(4, i);
      pstmt.setInt(5, i);
      pstmt.executeUpdate();
    }

    st = conn.createStatement();
    // first put convertible updates
    assertEquals(1, st.executeUpdate("update t1 set c5 = 5 where c1 = 1"));
    assertEquals(1, st.executeUpdate("update t1 set c5 = 6 where c1 = 2"));
    assertEquals(1, st.executeUpdate("update t1 set c5 = 6 where c1 = 7"));
    assertEquals(1, st.executeUpdate("update t1 set c5 = 5 where c1 = 9"));
    // Make c5 = 5 for commited and non commited insert
    assertEquals(8,
        st.executeUpdate("update t1 set c5 = 5 where c1 > 2 and c1 < 11"));
    // more put convertible updates
    assertEquals(1, st.executeUpdate("update t1 set c2 = 8 where c1 = 1"));
    assertEquals(1, st.executeUpdate("update t1 set c2 = 8 where c1 = 2"));
    assertEquals(1, st.executeUpdate("update t1 set c2 = 8 where c1 = 7"));
    assertEquals(1, st.executeUpdate("update t1 set c2 = 8 where c1 = 9"));
    // c2 would be set to 8 for 1 to 10 and then 15 to 20 except c1==2
    assertEquals(15, st.executeUpdate("update t1 set c2 = 8 "
        + " where c5 = 5 or ( c5 > 14 and c5 < 21)"));
    // 22 rows should be deleted some txnl and some committed
    assertEquals(22, st.executeUpdate("delete from t1 where c2 = 8 or c2 > 34"));
    conn.commit();
    st.execute("drop table t1");
  }
}
