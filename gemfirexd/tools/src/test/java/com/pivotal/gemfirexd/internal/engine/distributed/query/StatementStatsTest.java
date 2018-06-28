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
package com.pivotal.gemfirexd.internal.engine.distributed.query;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.SharedLibrary;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.jna.OSType;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;

/**
 * 
 * @author soubhikc
 *
 */
public class StatementStatsTest extends JdbcTestBase {

  public StatementStatsTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(StatementStatsTest.class));
  }
  
  @Override
  protected void setUp() throws Exception {
    System.setProperty(SharedLibrary.LOADLIBRARY_DEBUG_PROPERTY, "true");
    super.setUp();
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty(SharedLibrary.LOADLIBRARY_DEBUG_PROPERTY);
  }

  public void testBug44661() throws SQLException {
    Properties p = new Properties();
    p.setProperty("enable-stats", "true");
    Connection conn = getConnection(p);
    Statement st = conn.createStatement();
    DatabaseMetaData dbm = conn.getMetaData();
    ResultSet rs = dbm.getTables((String)null, null,
        "course".toUpperCase(), new String[] { "TABLE" });
    boolean found = rs.next();
    rs.close();

    if (found) {
      st.execute("drop table course ");
    }
    
    rs = dbm.getTables(null, null,
        "games".toUpperCase(), new String[] { "ROW TABLE" });
    found = rs.next();
    rs.close();

    if (found) {
      st.execute("drop table games ");
    }
    
    st.execute("CREATE TABLE GAMES ( " +
        "PLAYER_ID CHAR(8) NOT NULL," +
        "YEAR_NO      BIGINT NOT NULL," +
        "TEAM      CHAR(3) NOT NULL," +
        "WEEK      BIGINT NOT NULL," +
        "OPPONENT  CHAR(3) ," +
        "COMPLETES BIGINT ," +
        "ATTEMPTS  BIGINT ," +
        "PASSING_YARDS BIGINT ," +
        "PASSING_TD    BIGINT ," +
        "INTERCEPTIONS BIGINT ," +
        "RUSHES BIGINT ," +
        "RUSH_YARDS BIGINT ," +
        "RECEPTIONS BIGINT ," +
        "RECEPTIONS_YARDS BIGINT ," +
        "TOTAL_TD BIGINT" +
     ") " );
    
    ResultSet colM = conn.getMetaData().getColumns(null, null, "GAMES", "YEAR_NO");
    while(colM.next()) {
      String cname = colM.getString("COLUMN_NAME");
      int ctype = colM.getInt("DATA_TYPE");
      System.out.println(cname + " of type " + ctype );
    }
    
    st.execute("create table course (" + "course_id int, i int, course_name varchar(10), "
        + " primary key(course_id)"
        + ") partition by primary key ");
    
    st
    .execute("insert            into course values (1, 1, 'd')");
    
    st
    .execute("insert\n\n" +
    		"into course values (2, 2, 'd')");
    
    st
    .execute("insert into \"COURSE\" (\"COURSE_ID\", \"I\", \"COURSE_NAME\") values (3, 3, 'd')");
    
    ResultSet qRs = st
    .executeQuery("SELECT \"COURSE_ID\", \"I\", \"COURSE_NAME\" from -- GEMFIREXD-PROPERTIES statementAlias=\"QUOTE\"D_S\"ELECT_\"NO_\"SPACE\" \n \"COURSE\" ");
    int row = 0;
    while(qRs.next()) {
      row++;
    }
    assertEquals(row, 3);
    
    qRs = st
    .executeQuery("SELECT \"COURSE_ID\", \"I\", \"COURSE_NAME\" from -- GEMFIREXD-PROPERTIES statementAlias=\"\"QUOTED    SELECT\"\" \n \"COURSE\" ");
    row = 0;
    while(qRs.next()) {
      row++;
    }
    assertEquals(row, 3);
    
    qRs = st
    .executeQuery("SELECT \"COURSE_ID\", \"I\", \"COURSE_NAME\" from -- GEMFIREXD-PROPERTIES statementAlias=\"QUOTE\"D    S\"ELECT\" \n \"COURSE\" ");
    row = 0;
    while(qRs.next()) {
      row++;
    }
    assertEquals(row, 3);
    
    final InternalDistributedSystem dsys = Misc.getDistributedSystem();
    final StatisticsType statsType = dsys.findType(StatementStats.name);
    Statistics[] stats = dsys.findStatisticsByType(statsType);
    for(Statistics s : stats) {
      String statementDesc = s.getTextId();
      System.out.println(statementDesc);
      // ensure SYS schema queries ignored.
      assertFalse(statementDesc.contains("SYS.\"getTables"));
      
      if(! (statementDesc.contains("\"QUOTE\\\"D") || statementDesc.contains("\"QUOTED"))) {
        // ensure spaces are replaced except in the statementAlias.
        assertTrue(statementDesc + " contains space ", !statementDesc.contains(" "));
        
        // ensure no other white space characters exists or white spaces are in statementAlias
        assertTrue(statementDesc + " contains white space ", statementDesc.split("\\s+").length == 1);
      }

      // ensure quotes are escaped
      if( statementDesc.contains("\"") ) {
        assertTrue(statementDesc + " contains unescaped quotes ", statementDesc.startsWith("\"") && statementDesc.endsWith("\""));
        int escapedQuoteIndex = statementDesc.indexOf("\\\"");
        
        if(escapedQuoteIndex <= -1) {
          assertTrue(statementDesc + " contains no nested quotes ", statementDesc.indexOf("\"", 1) == statementDesc.lastIndexOf("\""));
        }
        else {
          // first escaped quote
          assertTrue(statementDesc, escapedQuoteIndex > -1);
          
          // second escaped quote
          escapedQuoteIndex = statementDesc.indexOf("\\\"", escapedQuoteIndex + 1);
          assertTrue(statementDesc + " contains unpaired unescaped quotes ", escapedQuoteIndex > -1 && escapedQuoteIndex != statementDesc.lastIndexOf("\"")-1);
        }
      }
    }
  }
  
  public void testNanoTime() throws Exception {

    // initialize NanoTimer appropriately.
    Connection conn = TestUtil.getConnection(); 
    conn.close();
    
    final Thread[] Ts = new Thread[10];
    TestUtil.assertTimerLibraryLoaded();
    
    Runnable _run = new Runnable() {

      @Override
      public void run() {
        long[] a = new long[1000];
        for (int i = 0; i < a.length; i++) {
          a[i] = NanoTimer.nativeNanoTime(NativeCalls.CLOCKID_THREAD_CPUTIME_ID, true);
        }

        long p = -1;
        long min = -1, max = 0, total = 0;
        for (long v : a) {
          if (p == -1) {
            p = v;
            min = p;
            continue;
          }

          long diff = (v-p);
          
          if(diff > max) {
            max = diff;
          }
          
          if (diff < min) {
            min = diff;
          }
          
          total += diff;
          
          if(NativeCalls.getInstance().getOSType() == OSType.GENERIC_POSIX) {
            assertTrue("nanoseconds difference must be greater than zero v=" + v + " p=" + p, diff > 0);
          }
          p = v;
        }
        
        getLogger().info("min=" + min + " max=" + max + " avg=" + (total/a.length) );
      }

    };

    for (int z = 0; z < Ts.length; z++) {
      Ts[z] = new Thread(_run, "Thread" + z);
    }

    for (Thread t : Ts) {
      t.start();
    }

    for (Thread t : Ts) {
      t.join();
    }
  }
  
  public void testNanoTimeWithLongPause() throws Exception {

    // initialize NanoTimer appropriately.
    Connection conn = TestUtil.getConnection(); 
    conn.close();
    
    final Thread[] Ts = new Thread[100];
    TestUtil.assertTimerLibraryLoaded();
    
    Runnable _run = new Runnable() {

      @Override
      public void run() {
        final int nestUpto = 80;
        final int numTimes = 5;
        long begin = XPLAINUtil.nanoTime();
        
        for(int i = 0; i < numTimes; i++) {
          populateNestedCalls(nestUpto);
        }
        
        long elapsed = XPLAINUtil.recordTiming(begin);
        getLogger().info(Thread.currentThread().getName() + " ended. took " + elapsed + " ns");
      }

    };

    for (int z = 0; z < Ts.length; z++) {
      Ts[z] = new Thread(_run, "Thread" + z);
    }

    for (Thread t : Ts) {
      t.start();
    }

    for (Thread t : Ts) {
      t.join();
    }
  }  
  
  private static int populateNestedCalls(int nestingLevel) {
    
    if ( nestingLevel < 0) {
      return nestingLevel;
    }
    
    long begin = XPLAINUtil.nanoTime();
    
    populateNestedCalls(nestingLevel-1);

    if (nestingLevel % 2 == 0) {
      try {
        Thread.sleep(nestingLevel*5, nestingLevel * 100);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    long elapsed = XPLAINUtil.recordTiming(begin);
    
    assertTrue(elapsed >= 0);
    
    return nestingLevel;
  }

}
