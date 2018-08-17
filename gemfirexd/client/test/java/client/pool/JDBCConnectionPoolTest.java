package client.pool;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.*;
import java.util.Properties;

public class JDBCConnectionPoolTest {


    private final String JDBC_DRIVER = "io.snappydata.jdbc.ClientDriver";
    private final String DB_URL = "jdbc:snappydata://localhost:24302/";
    private final String TABLE_NAME = "TEST";

    //  Database credentials
    private final String USER = "pradeep";
    private final String PASSWORD = "root";

    private final int THREAD_COUNT = 30;

    private final int POOL_SIZE = 20;


    @Before
    public void setup() {

        Connection conn = null;
        Statement stmt = null;
        PreparedStatement preparedStatement = null;
        try {
            Class.forName(JDBC_DRIVER);
            conn = DriverManager.getConnection(DB_URL, USER, PASSWORD);
            stmt = conn.createStatement();
            String sql = "DROP TABLE IF EXISTS " + TABLE_NAME;
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE " + TABLE_NAME +
                    "(id INTEGER , " +
                    " col1 VARCHAR(255), " +
                    " col2 VARCHAR(255), " +
                    " age INTEGER ) ";

            stmt.executeUpdate(sql);
            preparedStatement = conn.prepareStatement("insert into " + TABLE_NAME + " VALUES (?,?,?,?)");

            for (int i = 1; i < 100000; i++) {
                preparedStatement.setInt(1, i);
                preparedStatement.setString(2, "Col_1_Value_" + i);
                preparedStatement.setString(3, "Col_2_Value_" + i);
                preparedStatement.setInt(4, i);
                preparedStatement.execute();
            }

        } catch (SQLException se) {
            se.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null)
                    stmt.close();
            } catch (SQLException se) {
            }

            try {
                if (preparedStatement != null)
                    preparedStatement.close();
            } catch (SQLException se) {
            }

            try {
                if (conn != null)
                    conn.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
    }

    @Test
    public void withoutPool() {

        try {

            Properties properties = new Properties();
            properties.setProperty("url", DB_URL);
            properties.setProperty("driverClassName", JDBC_DRIVER);
            properties.setProperty("user", USER);
            properties.setProperty("password", PASSWORD);
            properties.setProperty("use-pool", "false");

            runMultipleConnectionTest(properties);

            Thread.sleep(5000);

            assert (true);

        } catch (InterruptedException e) {
            e.printStackTrace();
            assert false;

        }
    }


    @Test
    public void withPool() {

        Properties properties = new Properties();
        properties.setProperty("url", DB_URL);
        properties.setProperty("driverClassName", JDBC_DRIVER);
        properties.setProperty("use-pool", "true");
        properties.setProperty("maxTotal", String.valueOf(POOL_SIZE));
        properties.setProperty("maxIdle", "8");
        properties.setProperty("maxWaitMillis", "10000");
        properties.setProperty("user", USER);
        properties.setProperty("password", PASSWORD);
        properties.setProperty("removeAbandoned", "true");
        properties.setProperty("removeAbandonedTimeout", "300");


        runMultipleConnectionTest(properties);

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {

        }

    }

    private void runMultipleConnectionTest(Properties prop) {
        for (int i = 0; i < THREAD_COUNT; i++) {
            new Thread(new Runnable() {

                Connection conn = null;
                Statement stmt = null;

                @Override
                public void run() {
                    try {
                        long startTime = System.currentTimeMillis();
                        conn = DriverManager.getConnection(DB_URL, prop);
                        stmt = conn.createStatement();
                        ResultSet resultSet = stmt.executeQuery("select count(*) from " + TABLE_NAME);

                        int count = 0;
                        while (resultSet.next()) {
                            System.out.println(" Record " + resultSet.getString(1));
                            count = 1;
                            break;
                        }

                        assert (count > 0);

                    } catch (Exception e) {
                        e.printStackTrace();
                        assert false;
                    } finally {
                        try {
                            conn.close();
                        } catch (SQLException e) {

                        }
                    }
                }

            }).start();
        }
    }

    @After
    public void cleanup() {

        Connection conn = null;
        Statement stmt = null;
        try {
            Class.forName(JDBC_DRIVER);
            conn = DriverManager.getConnection(DB_URL, USER, PASSWORD);
            stmt = conn.createStatement();
            String sql = "DROP TABLE TEST";

        } catch (SQLException se) {
            se.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null)
                    stmt.close();
            } catch (SQLException se) {
            }

            try {
                if (conn != null)
                    conn.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
    }
}