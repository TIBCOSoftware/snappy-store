package com.pivotal.gemfirexd.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;

public class BatchInsertTest  extends JdbcTestBase {

  private GemFireCacheImpl cache;

  private boolean gotConflict = false;

  private volatile Throwable threadEx;

  public BatchInsertTest(String name) {
    super(name);
  }

  public void testBatchInsert() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null) "
        + "partition by column (c1) " + getSuffix());
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO t1 values(?,?)");

    for (int i = 0; i < 5; i++) {
      pstmt.setInt(1, i);
      pstmt.setInt(2, i * 10);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    ResultSet rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      System.out.println("---> " + rs.getInt(1));
      System.out.println("---> " + rs.getInt(2));
      numRows++;
    }
    assertEquals("ResultSet should contain 5 row ", 5, numRows);

    for (int i = 0; i < 15; i++) {
      pstmt.setInt(1, i);
      pstmt.setInt(2, i * 10);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    rs = st.executeQuery("Select * from t1");
    numRows = 0;
    while (rs.next()) {
      System.out.println("---> " + rs.getInt(1));
      System.out.println("---> " + rs.getInt(2));
      numRows++;
    }
    assertEquals("ResultSet should contain 20 row ", 20, numRows);

    for (int i = 0; i < 2; i++) {
      pstmt.setInt(1, i);
      pstmt.setInt(2, i * 10);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    rs = st.executeQuery("Select * from t1");
    numRows = 0;
    while (rs.next()) {
      System.out.println("---> " + rs.getInt(1));
      System.out.println("---> " + rs.getInt(2));
      numRows++;
    }
    assertEquals("ResultSet should contain 22 row ", 22, numRows);

    // Close connection, resultset etc...
    rs.close();
    st.close();
    conn.close();
  }

  public void testBatchInsertStmtExecute() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null) "
        + "partition by column (c1) " + getSuffix());
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO t1 values(?,?)");

    for (int i = 0; i < 5; i++) {
      pstmt.setInt(1, i);
      pstmt.setInt(2, i * 10);
      pstmt.addBatch();
    }
    pstmt.executeBatch();
   // pstmt.clearBatch();
    pstmt.setInt(1, 1);
    pstmt.setInt(2, 1 * 10);
    pstmt.execute();

    ResultSet rs = st.executeQuery("SELECT * FROM t1");
    int numRows = 0;
    while (rs.next()) {
      System.out.println("---> " + rs.getInt(1));
      System.out.println("---> " + rs.getInt(2));
      numRows++;
    }
    assertEquals("ResultSet should contain 6 row ", 6, numRows);
    // Close connection, resultset etc...
    rs.close();
    st.close();
    conn.close();
  }

  public void testBatchInsertStmt() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null) "
        + "partition by column (c1) " + getSuffix());

    for (int i = 0; i < 5; i++) {
      st.addBatch("INSERT INTO t1 values(1,2)");
      st.addBatch("INSERT INTO t1 values (2,2)");
      st.addBatch("INSERT INTO t1 values(3,2)");
      st.addBatch("INSERT INTO t1 values(4,2)");
    }

    st.executeBatch();
    int numRows = 0;
    ResultSet rs = st.executeQuery("SELECT * FROM t1");
    while (rs.next()) {
      System.out.println("---> " + rs.getInt(1));
      System.out.println("---> " + rs.getInt(2));
      numRows++;
    }
    assertEquals("ResultSet should contain 4 row ", 20, numRows);
    // Close connection, resultset etc...
    rs.close();
    st.close();
    conn.close();
  }

  protected String getSuffix() {
    return " ";
  }
}
