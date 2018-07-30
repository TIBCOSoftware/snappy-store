package client.pool;

import org.junit.Test;

import java.sql.*;
import java.util.Properties;

public class JDBCConnectionPoolTest {

    @Test
    public void withoutPool(){

        for(int i=0; i<20; i++) {
            Connection conn = null;
            Statement stmt = null;
            try {

                Class.forName("io.snappydata.jdbc.ClientDriver");
                conn = DriverManager.getConnection("jdbc:snappydata://localhost:24302/", "APP", "APP");
                stmt = conn.createStatement();
                ResultSet resultSet = stmt.executeQuery("select * from AIRLINE fetch first row only");

                while (resultSet.next()) {
                    //System.out.print(" " + resultSet.getString(1));
                }

                assert true;

            } catch (SQLException e) {
                e.printStackTrace();
                assert false;

            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                assert false;

            } finally {
                try {
                    conn.close();
                } catch (SQLException e) {
                    assert false;
                }
            }
        }
    }


    @Test
    public void withPool() {

        Connection conn = null;
        Statement stmt = null;
        try {
            Properties properties = new Properties();
            properties.setProperty("url", "jdbc:snappydata://localhost:24302/");
            properties.setProperty("driverClassName", "io.snappydata.jdbc.ClientDriver");
            properties.setProperty("use-pool", "true");
            properties.setProperty("maxTotal", "7");
            properties.setProperty("maxIdle", "8");
            properties.setProperty("maxWaitMillis","10000");
            properties.setProperty("username", "APP");
            properties.setProperty("password", "APP");
            properties.setProperty("removeAbandoned","true");
            properties.setProperty("removeAbandonedTimeout","300");
            conn = DriverManager.getConnection("jdbc:snappydata://localhost:24302/", properties);
            stmt = conn.createStatement();
            ResultSet resultSet = stmt.executeQuery("select * from AIRLINE fetch first row only");

            while(resultSet.next()){
                //System.out.println(resultSet.getString(1) +  " " +resultSet.getString(2));
                resultSet.getString(1);
            }
            conn.close();

            Thread.sleep(2000);

            runMultipleConnectionTest(properties);

            Thread.sleep(2000);
            assert true;

        } catch (SQLException e) {
            e.printStackTrace();
            assert false;

        } catch (InterruptedException e){
            e.printStackTrace();
            assert false;
        }
    }

    private void runMultipleConnectionTest(Properties prop){
       for(int i=0; i<9;  i++){
          new Thread(new Runnable() {

              Connection conn = null;
              Statement stmt = null;

              @Override
              public void run() {
                  try {
                      long startTime = System.currentTimeMillis();
                      conn = DriverManager.getConnection("jdbc:snappydata://localhost:24302/", prop );
                      stmt = conn.createStatement();
                      ResultSet resultSet = stmt.executeQuery("select count(*) from AIRLINE");

                      while (resultSet.next()) {
                         // System.out.println(" Thread  "+resultSet.getString(1) + " " /*+ resultSet.getString(2)*/);
                          resultSet.getString(1);
                      }

                  }catch (Exception e){
                      e.printStackTrace();
                  }finally{
                      try {
                          conn.close();
                      } catch (SQLException e) {

                      }
                  }
              }

          }).start();
       }
    }


    @Test
    public void maxWaitTimeTest() {

        long startTime = System.currentTimeMillis();
        Connection conn = null;
        Statement stmt = null;
        try {
            Properties properties = new Properties();
            properties.setProperty("url", "jdbc:snappydata://localhost:24302/");
            properties.setProperty("driverClassName", "io.snappydata.jdbc.ClientDriver");
            properties.setProperty("use-pool", "true");
            properties.setProperty("maxTotal", "7");
            properties.setProperty("maxIdle", "10");
            properties.setProperty("maxWaitMillis","5");
            properties.setProperty("username", "APP");
            properties.setProperty("password", "APP");
            properties.setProperty("removeAbandoned","true");
            properties.setProperty("removeAbandonedTimeout","300");
            conn = DriverManager.getConnection("jdbc:snappydata://localhost:24302/", properties);
            stmt = conn.createStatement();
            ResultSet resultSet = stmt.executeQuery("select count(*) from AIRLINE");

            while(resultSet.next()){
                //System.out.println(resultSet.getString(1) +  " " +resultSet.getString(2));
                resultSet.getString(1);
            }


            Thread.sleep(2000);

            runMultipleConnectionTest(properties);

            Thread.sleep(2000);
            assert true;

        } catch (SQLException e) {
            e.printStackTrace();
            assert false;

        } catch (InterruptedException e){
            e.printStackTrace();
            assert false;
        } finally {
            try {
                conn.close();
            } catch (SQLException e) {
                assert false;
            }
        }
    }


}