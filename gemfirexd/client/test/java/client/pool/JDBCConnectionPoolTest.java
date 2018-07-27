package client.pool;

import org.junit.Test;

import java.sql.*;
import java.util.Properties;

public class JDBCConnectionPoolTest {


    @Test
    public void poolTest() {

        long startTime = System.currentTimeMillis();
        Connection conn = null;
        Statement stmt = null;
        try {
            Properties properties = new Properties();
            properties.setProperty("url", "jdbc:snappydata://localhost:1527/");
            properties.setProperty("driver", "io.snappydata.jdbc.ClientDriver");
            properties.setProperty("dbtable", "AIRLINE");
            properties.setProperty("use-pool", "true");
            properties.setProperty("pool-size", "8");
            properties.setProperty("user", "APP");
            properties.setProperty("password", "APP");

            Class.forName("io.snappydata.jdbc.ClientDriver");
            conn = DriverManager.getConnection("jdbc:snappydata://localhost:1527/", properties);
            stmt = conn.createStatement();
            ResultSet resultSet = stmt.executeQuery("select count(*) from AIRLINE");

            while(resultSet.next()){
                //  System.out.println(resultSet.getString(1) +  " " /*+resultSet.getString(2)*/);
                resultSet.getString(1);
            }
            conn.close();
            System.out.println("Total Time "+ (System.currentTimeMillis() - startTime ));


            runMultipleConnectionTest(properties);

            Thread.sleep(2000);
            assert true;

        } catch (SQLException e) {
            e.printStackTrace();
            assert false;

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            assert false;
        } catch (InterruptedException e){
            e.printStackTrace();
            assert false;
        }
    }

   // @Test
    public void runMultipleConnectionTest(Properties prop){
       for(int i=0; i<5;  i++){
          new Thread(new Runnable() {

              Connection conn = null;
              Statement stmt = null;

              @Override
              public void run() {
                  try {
                      long startTime = System.currentTimeMillis();
                      conn = DriverManager.getConnection("jdbc:snappydata://localhost:1527/", prop );
                      stmt = conn.createStatement();
                      ResultSet resultSet = stmt.executeQuery("select count(*) from AIRLINE");

                      while (resultSet.next()) {
                         // System.out.println(" Thread  "+resultSet.getString(1) + " " /*+ resultSet.getString(2)*/);
                          resultSet.getString(1);
                      }

                      System.out.println("Total Time "+ (System.currentTimeMillis() - startTime ));
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
}