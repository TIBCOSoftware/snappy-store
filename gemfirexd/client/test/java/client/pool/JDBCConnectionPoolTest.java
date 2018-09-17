package client.pool;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.*;
import java.util.Properties;

public class JDBCConnectionPoolTest {


    private static final String JDBC_DRIVER = "io.snappydata.jdbc.ClientDriver";
    private static final String DB_URL = "jdbc:snappydata://localhost:27618/";
    private static final String TABLE_NAME = "AIRLINE";

    //  Database credentials
    private static final String USER = "APP";
    private static final String PASSWORD = "APP";

    private static final int THREAD_COUNT = 1;
    private static final int POOL_SIZE = 20;

    private static String aggregateQuery = "select avg(flightnum) from " + TABLE_NAME;
    private static String pointQuery = "select * from " + TABLE_NAME + " where flightnum=";
    private static Properties propertiesPoolEnabled = getPropertiesWithPool();

    private static Properties propertiesPoolDisabled = getPropertiesWithoutPool();

    private static Properties getPropertiesWithoutPool(){
        Properties properties = new Properties();
        properties.setProperty("url", DB_URL);
        properties.setProperty("driverClassName", JDBC_DRIVER);
        properties.setProperty("user", USER);
        properties.setProperty("password", PASSWORD);
        properties.setProperty("use-pool", "false");
        return properties;
    }

    private static Properties getPropertiesWithPool() {
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
        return properties;
    }

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

            sql = "CREATE TABLE " + TABLE_NAME + "(id INTEGER ," +
                    "col1 VARCHAR(255), col2 VARCHAR(255)," + " age INTEGER );";

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
            runMultipleConnectionTest(propertiesPoolDisabled);
            Thread.sleep(3000);
            assert (true);
        } catch (InterruptedException e) {
            e.printStackTrace();
            assert false;
        }
    }


    @Test
    public void withPool() {
        try {
            runMultipleConnectionTest(propertiesPoolEnabled);
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
            assert false;
        }
    }

    @Test
    public void aggregateQueryWithPool() {
        executeQuery(20, propertiesPoolEnabled, aggregateQuery);
    }

    @Test
    public void aggregateQueryWithoutPool() {
        executeQuery(20, propertiesPoolDisabled, aggregateQuery);
    }

    @Test
    public void pointQueryWithPool() {
        executeQuery(20, propertiesPoolEnabled, pointQuery);
    }

    @Test
    public void pointQueryWithoutPool() {
        executeQuery(20, propertiesPoolDisabled, pointQuery);
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
                        resultSet.next();
                        int result = resultSet.getInt(1);

                        assert (result == 1000000);

                        System.out.println(prop.getProperty("use-pool") + " + "+(System.currentTimeMillis() - startTime));
                        /*int count = 0;
                        while (resultSet.next()) {
                            count = 1;
                            break;
                        }*/

                        //assert (count > 0);

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

    private void executeQuery(int iterate, Properties prop,String query){

        int[] flightnumbers = {4194, 4874,842,1668,5951,826,5303,4623,340,6145,4801,4639,2955,5554,2421,
                2866,3854,1538,2494,7400,3};
        for (int i = 0; i < iterate; i++) {
            Connection conn = null;
            Statement stmt = null;

            try {
                long startTime = System.currentTimeMillis();
                conn = DriverManager.getConnection(DB_URL, prop);
                stmt = conn.createStatement();
                ResultSet resultSet = stmt.executeQuery(query+flightnumbers[iterate]);
                int count = 0;
                while(resultSet.next()){
                    count++;
                    break;
                }
                System.out.print((System.currentTimeMillis() - startTime)+",");
                assert (count > 0);

                Thread.sleep(500);

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