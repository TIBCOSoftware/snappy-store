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
/**
 * 
 */
package sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.jdbc.ClientAttribute;

import sql.GFEDBManager.Isolation;
import sql.sqlutil.ResultSetHelper;
import sql.wan.SQLWanPrms;
import util.*;
import hydra.*;
import hydra.gemfirexd.FabricSecurityPrms;
import hydra.gemfirexd.NetworkServerHelper;
import hydra.gemfirexd.NetworkServerHelper.Endpoint;

/**
 * @author eshu
 *
 */
public class GFEDBClientManager {
  protected static String driver = "com.pivotal.gemfirexd.jdbc.ClientDriver";
  protected static String protocol = Attribute.DNC_PROTOCOL;
  protected static String snappyThriftProtocol = Attribute.SNAPPY_THRIFT_PROTOCOL;
  protected static String snappyProtocol = Attribute.SNAPPY_DNC_PROTOCOL;
  //private static String dbName = "";
  private static String userPrefix = "thr_";
  protected static boolean useGemFireXDHA = TestConfig.tab().booleanAt(SQLPrms.
      useGemFireXDHA, false);
  protected static boolean useGfxdConfig = TestConfig.tab().booleanAt(SQLPrms.
      useGfxdConfig, false);
  private static Endpoint fixedEndpoint = null;
  
  private static Map<String, ArrayList<Integer>> map;
  private static String[] hostNames;
  
  private static boolean isWanTest = TestConfig.tab().booleanAt(SQLWanPrms.isWanTest, false);
  public static boolean isSingleHop = TestConfig.tab().booleanAt(SQLPrms.isSingleHop, false);
  protected final static String singleHop = "single-hop-enabled";
  
  protected static final String LOG_FILE_NAME = "log-file";
  protected static final String connectionReadTimeout = "180";
  protected static final String repeatableReadTimeout = "1200";

  //load appropriate driver
  static {
    //default is embedded driver
    driver = TestConfig.tab().stringAt(SQLPrms.clientDriver, "com.pivotal.gemfirexd.jdbc.ClientDriver");
    try {
      Class.forName(driver).newInstance();
    } catch (ClassNotFoundException cnfe) {
      throw new TestException ("Unable to load the JDBC driver " + driver + ":" 
          + TestHelper.getStackTrace(cnfe));
    } catch (InstantiationException ie) {
      throw new TestException ("Unable to instantiate the JDBC driver " + driver + ":" 
          + TestHelper.getStackTrace(ie));
    } catch (IllegalAccessException iae) {
      throw new TestException ("Not allowed to access the JDBC driver " + driver + ":" 
          + TestHelper.getStackTrace(iae));
    }
    if (!useGfxdConfig) initAvailHostPort();
  }
  
  @SuppressWarnings("unchecked")
  public static synchronized void initAvailHostPort() {
    if (map == null)
      map = (HashMap<String, ArrayList<Integer>>)SQLBB.getBB().getSharedMap().get("serverPorts");
    hostNames = (String[])(map.keySet().toArray(new String[0]));
  }
  
  /**
   * get a thin client driver connection to gemfirexd network server 
   * after r50570, default to isolation read committed. test needs to be modified to  
   * accommodate the change
   * @return a connection to gemfirexd network server
   * @throws SQLException
   */
  public static synchronized Connection getConnection() throws SQLException {
    StringBuffer hostName = new StringBuffer();
    int[] port = new int[1];
    getInfo(hostName, port);
    Connection conn = getConnection(hostName.toString(), port[0]); //while create=true is not being ignored
    Log.getLogWriter().info("This connection connected to hostname " + hostName + " port: "  + port[0]);

    ResultSet rs = conn.createStatement().executeQuery("values dsid()");
    Log.getLogWriter().info("This connects to actual server " + 
        ResultSetHelper.listToString(ResultSetHelper.asList(rs, false)));

    return conn;
  }
  
  private static synchronized Connection getConnection(String hostName, int port) throws SQLException {
    Properties p = new Properties();
    return getConnection(hostName, port, p);
  }
  
  /**
   * get a thin client driver connection to gemfirexd network server with 
   * additional properties provided
   * after r50570, default to isolation read committed. test needs to be modified to  
   * accommodate the change
   * @param info additional properties used to acquire the connection
   * @return a thin client driver connection to gemfirexd network server
   * @throws SQLException
   */
  public static synchronized Connection getConnection(Properties info) throws SQLException {
    StringBuffer hostName = new StringBuffer();
    int[] port = new int[1];
    getInfo(hostName, port);
    return getConnection(hostName.toString(), port[0], info); //while create=true is not being ignored   
  }

  public static synchronized Connection getConnection(String hostName, int port, 
      Properties info) throws SQLException {
    if (isSingleHop) {
      info.setProperty(singleHop, "true");
      info.setProperty(ClientAttribute.READ_TIMEOUT, connectionReadTimeout); //read-timeout in second
    }
    
    info.setProperty(LOG_FILE_NAME, getLogFile());
    //Log.getLogWriter().info("Property set with " + LOG_FILE_NAME + getLogFile());
    //if (isSingleHop) Log.getLogWriter().info("set single hop enabled to true");
    Connection conn = DriverManager.getConnection(protocol + hostName + ":" + port, info); 
    conn.setAutoCommit(false);    
    //needed for comparison with derby
    return conn;
  }
  
  protected static synchronized String getLogFile() {
    return System.getProperty("user.dir") + "/vm_" + RemoteTestModule.getMyVmid()
    + "_" + RemoteTestModule.getMyClientName()
    + "_" + RemoteTestModule.getMyPid() + "_client.log";
  }

  /**
   * get connection with transaction isolation set currently as READ_COMMITTED
   * @return a Connection to a network server
   * @throws SQLException
   */
  public static synchronized Connection getTxConnection() throws SQLException {
    StringBuffer hostName = new StringBuffer();
    int[] port = new int[1];
    getInfo(hostName, port);
    return getTxConnection(hostName.toString(), port[0]); //while create=true is not being ignored   
  }
  
  protected static void getInfo(StringBuffer hostName, int[] port) {
    //if (useGemFireXDHA) {
    if (useGfxdConfig) {
      if (fixedEndpoint == null) fixedEndpoint = getEndpoint();
      if (SQLTest.random.nextBoolean()) {
      //during HA, it should automatically connect to next available server
        hostName.append(getHostNameFromEndpoint(fixedEndpoint));
        port[0] = getPortFromEndpoint(fixedEndpoint); 
      } else {
        Endpoint e = getEndpoint();
        hostName.append(getHostNameFromEndpoint(e));
        port[0] = getPortFromEndpoint(e);
      }
    } else {
      hostName.append(getHostName());
      port[0] = getPort(hostName.toString());
    }
    Log.getLogWriter().info("will connect to hostname " + hostName + " port: "  + port[0]);
  }
  
  @SuppressWarnings("unchecked")
  private static Endpoint getEndpoint() {
    if (!isWanTest) {
      if (isSingleHop) return (Endpoint) (NetworkServerHelper.getNetworkLocatorEndpoints()).get(0);
      List<Endpoint> endpoints = SQLTest.isHATest? //only use locator port for HA tests, indicated in #45889 comments
          NetworkServerHelper.getNetworkLocatorEndpoints() 
          : NetworkServerHelper.getNetworkServerEndpoints();
           
      return endpoints.get(SQLTest.random.nextInt(endpoints.size()));
    } else {
      List<Endpoint> endpoints = SQLTest.isHATest? //only use locator port for HA tests, indicated in #45889 comments
          NetworkServerHelper.getNetworkLocatorEndpointsInWanSite()
          : NetworkServerHelper.getNetworkServerEndpointsInWanSite();
      
      Log.getLogWriter().info("Endpoints of this wan site for this client is " + endpoints);
           
      return endpoints.get(SQLTest.random.nextInt(endpoints.size()));
    }
  }

  /**
   * get connection with transaction isolation set currently as READ_COMMITTED
   * @param hostName network server to be connected to
   * @param port network server port to be connected to
   * @return a Connection to a network server
   * @throws SQLException
   */

  private static synchronized Connection getTxConnection(String hostName, int port) throws SQLException {
    Properties p = new Properties();
    return getTxConnection(hostName, port, p);
  }

  
  /**
   * get connection with transaction isolation set currently as READ_COMMITTED
   * @param hostName network server to be connected to
   * @param port network server port to be connected to
   * @param info any additional props used for the connection
   * @return a Connection
   * @throws SQLException
   */
  private static synchronized Connection getTxConnection(String hostName, int port, 
      Properties info) throws SQLException {
    return getTxConnection(hostName, port, info, Isolation.READ_COMMITTED);
  }
  
  private static Connection getTxConnection(String hostName, int port, 
      Properties info, Isolation isolation) throws SQLException {
    if (isSingleHop) {
      info.setProperty(singleHop, "true"); 
      info.setProperty(ClientAttribute.READ_TIMEOUT, connectionReadTimeout);  //read-timeout in second
    }
    info.setProperty(LOG_FILE_NAME, getLogFile());
    
    Connection conn = DriverManager.getConnection(protocol + hostName + ":" + port, info); //while create=true is not being ignored     
    switch (isolation) {
    case NONE:
      conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
      return conn;
    case READ_COMMITTED:
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      break;
    case REPEATABLE_READ:
      conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      break;
    default: 
      throw new TestException ("test issue -- unknow Isolation lever");
    }
    
    conn.setAutoCommit(false);    
    return conn;
  
  }
  
  private static synchronized Connection getRRTxConnection(String hostName, int port, 
      Properties info) throws SQLException {  
    return getTxConnection(hostName, port, info, Isolation.REPEATABLE_READ);
  }
  
  
  
  protected static String getHostNameFromEndpoint(Endpoint e) {
    return e.getHost();
  }
  
  protected static int getPortFromEndpoint(Endpoint e) {
    return e.getPort();
  }
  
  /**
   * get get connection with transaction isolation set currently as READ_COMMITTED
   * @param info any props used for the connection
   * @return a Connection to a network server
   * @throws SQLException
   */
  public static synchronized Connection getTxConnection(Properties info) throws SQLException {
    StringBuffer hostName = new StringBuffer();
    int[] port = new int[1];
    getInfo(hostName, port);
    return getTxConnection(hostName.toString(), port[0], info); //while create=true is not being ignored    
  }
  
  public static String getDriver() {
    return driver;
  }

  public static String getProtocol() {
    if(SQLPrms.isSnappyMode())
      return snappyProtocol;
    return protocol;
  }

  public static String getSnappyThriftProtocol(){
      return snappyThriftProtocol;
  }

  public static String getDRDAProtocol() {
    return Attribute.DRDA_PROTOCOL;
  }

  public static void closeConnection(Connection conn) {
    try {
      conn.close();
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      throw new TestException ("Not able to release the connection " + TestHelper.getStackTrace(e));
    }
  }
  
  private static String getHostName() {
    if (hostNames != null) {
      //Log.getLogWriter().info("hostname size is " + hostNames.length);
      return hostNames[SQLTest.random.nextInt(hostNames.length)];
    } else {
      throw new TestException("no server bridge node available");
    }
  }
  
  private static int getPort(String name){
    ArrayList<Integer> ports = map.get(name);
    return ports.get(SQLTest.random.nextInt(ports.size()));
  }
  
  public static synchronized Connection getSuperUserConnection() throws SQLException {
    String user = TestConfig.tab().stringAt(FabricSecurityPrms.user, "superUser");
    String passwd = TestConfig.tab().stringAt(FabricSecurityPrms.password, "superUser");
    String superUser = ";user="+ user +";password="+passwd;
    Log.getLogWriter().info("get connection using this credential: " + superUser);
    Properties p = new Properties();
    p.setProperty("user", user);
    p.setProperty("password", passwd);
    return getConnection(p);
  }
  
  public static synchronized Connection getAuthConnection(int thrId) throws SQLException {
    String user = userPrefix + thrId;
    String passwd = user;
    String userAuth = ";user="+ user +";password="+passwd;
    Log.getLogWriter().info("current gfxd connection " +
        "use the following credential: " + userAuth);
    Properties p = new Properties();
    p.setProperty("user", user);
    p.setProperty("password", passwd);
    return getConnection(p);
  }
  
  public static synchronized Connection getInvalidAuthConnection(int thrId) throws SQLException {
    String user = userPrefix + thrId;
    String passwd = user+"x";
    String userAuth = ";user="+ user +";password="+passwd;
    Log.getLogWriter().info("current gfxd connection " +
        "use the following credential: " + userAuth);
    Properties p = new Properties();
    p.setProperty("user", user);
    p.setProperty("password", passwd);
    return getConnection(p);
  }
  
  public static synchronized Connection getNoneAuthConnection(int thrId) throws SQLException {
    Log.getLogWriter().info("current gfxd connection " +
        "use no credential");
    return getConnection();
  }
  
  public static synchronized Connection getAuthConnection(String user) throws SQLException {
    String passwd = user;
    String userAuth = ";user="+ user +";password="+passwd;
    Log.getLogWriter().info("current gfxd connection " +
        "use the following credential: " + userAuth);
    Properties p = new Properties();
    p.setProperty("user", user );
    p.setProperty("password", passwd);
    return getConnection(p);
  }
  
  public static synchronized Connection getAuthConnection(String user, String passwd) throws SQLException {
    String userAuth = ";user="+ user +";password="+passwd;
    Log.getLogWriter().info("current gfxd connection " +
        "use the following credential: " + userAuth);
    Properties p = new Properties();
    p.setProperty("user", user );
    p.setProperty("password", passwd);
    return getConnection(p);
  }
  
  public static synchronized Connection getTxConnection(Isolation isolation) throws SQLException {
    // default to partitioned tables for tests
    //not used for boot
    //System.setProperty("gemfirexd.table-default-partitioned", "true");
    switch (isolation) {
    case NONE:
      Connection conn = getConnection();
      conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
      return conn;
    case READ_COMMITTED:
      return getReadCommittedConnection();
    case REPEATABLE_READ:
      return getRepeatableReadConnection();
    default: 
      throw new TestException ("test issue -- unknow Isolation lever");
    }
  }
  
  public static Connection getReadCommittedConnection() throws SQLException {
    StringBuffer hostName = new StringBuffer();
    int[] port = new int[1];
    getInfo(hostName, port);
    Connection conn = getTxConnection(hostName.toString(), port[0]);
    
    if (SQLTest.random.nextInt(20) == 1) {
        //test auto upgrade to read committed
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
    } else {
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }
    
    conn.setAutoCommit(false);    
    return conn;
  }
  
  public static Connection getRepeatableReadConnection() throws SQLException {
    StringBuffer hostName = new StringBuffer();
    int[] port = new int[1];
    getInfo(hostName, port);
    Properties p = new Properties();
    p = new Properties(); 
    p.setProperty(ClientAttribute.READ_TIMEOUT, repeatableReadTimeout);

    Connection conn = getTxConnection(hostName.toString(), port[0], p);
    
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    
    conn.setAutoCommit(false);    
    return conn;
  }
  
  public static synchronized Connection getMyConnection() throws SQLException {
    StringBuffer hostName = new StringBuffer();
    int[] port = new int[1];
    getInfo(hostName, port);
    return getConnection(hostName.toString(), port[0]); //while create=true is not being ignored
  }
  
  //to get Connection to the gfxd
  public static synchronized Connection getRRTxConnection(Properties info) throws SQLException {
    StringBuffer hostName = new StringBuffer();
    int[] port = new int[1];
    getInfo(hostName, port);
    return getRRTxConnection(hostName.toString(), port[0], info);
  }
  
  public static Properties getExtraConnProp() {
    Properties p = new Properties();
    if (isSingleHop) {
      p.setProperty(singleHop, "true");

      p.setProperty(ClientAttribute.READ_TIMEOUT, connectionReadTimeout); //read-timeout in second

    }
    
    p.setProperty(LOG_FILE_NAME, getLogFile());
    return p;
  }
}
