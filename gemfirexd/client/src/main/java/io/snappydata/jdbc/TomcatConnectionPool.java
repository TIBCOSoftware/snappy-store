/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package io.snappydata.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.pivotal.gemfirexd.Attribute;
import com.sun.xml.internal.fastinfoset.stax.events.Util;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.apache.tomcat.jdbc.pool.interceptor.StatementFinalizer;

/**
 * A class holds map of connection pools. Each pool is represented accessed
 * via unique key which is the connection Properties object.
 * <p>
 * Also, class support the jdbcInterceptor which resets the autocommit, readOnly
 * and isolation level value to default whenever a connection  borrowed from the pool.
 */
public class TomcatConnectionPool {

  private static final Integer MAX_POOL_SIZE = Math.max(256,
      Runtime.getRuntime().availableProcessors() * 8);

  public static final ThreadLocal<Connection> CURRENT_CONNECTION =
      new ThreadLocal<>();

  enum PoolProps {

    DRIVER_NAME("pool.driverClassName", ClientDriver.class.getName()),
    URL("pool.url", null), // Compulsory field user must provide
    USER(Attribute.USERNAME_ATTR, "APP"),
    PASSWORD(Attribute.PASSWORD_ATTR, "APP"),
    INIT_SIZE("pool.initialSize", "4"),
    MAX_ACTIVE("pool.maxActive", MAX_POOL_SIZE.toString()),
    MAX_IDLE("pool.maxIdle", MAX_POOL_SIZE.toString()),
    MIN_IDLE("pool.minIdle", "4"),
    MAX_WAIT("pool.maxWait", "30"),
    REMOVE_ABANDONED("pool.removeAbandoned", "true"),
    REMOVE_ABANDONED_TIMEOUT("pool.removeAbandonedTimeout", "60"),
    TIME_BETWEEN_EVICTION_RUNS_MILLIS("pool.timeBetweenEvictionRunsMillis", "30000"),
    MIN_EVICTABLE_IDLE_TIME_MILLIS("pool.minEvictableIdleTimeMillis", "60000"),
    TEST_ON_BORROW("pool.testOnBorrow", "true"),
    TEST_ON_RETURN("pool.testOnReturn", "true"),
    VALIDATION_INTERVAL("pool.validationInterval", "10000"),

    JDBC_INTERCEPTOR("pool.jdbcInterceptor", "org.apache.tomcat.jdbc.pool.interceptor.ConnectionState;" +
        getRequiredInterceptor()),
    // Default transaction values which will be resetted, when connection returned to the pool.
    DEFAULT_AUTO_COMMIT("pool.defaultAutoCommit", "false"),
    DEFAULT_READ_ONLY("pool.defaultReadOnly", "false"),
    DEFAULT_TRANSACTION_ISOLATION("pool.defaultTransactionIsolation",
        String.valueOf(Connection.TRANSACTION_NONE));

    final String key;
    final String defValue;

    PoolProps(String propKey, String defValue) {
      this.key = propKey;
      this.defValue = defValue;
    }

    public static List<String> getKeys() {
      PoolProps[] props = PoolProps.values();
      List<String> keys = new ArrayList<>(props.length);
      for (PoolProps prop : props) {
        keys.add(prop.key);
      }
      return keys;
    }
  }

  private DataSource datasource;

  private static final ConcurrentMap<Properties, TomcatConnectionPool> poolMap =
      new ConcurrentHashMap<>();

  public static final class ClearConnection extends StatementFinalizer {
    @Override
    public void closeInvoked() {
      super.closeInvoked();
      CURRENT_CONNECTION.set(null);
    }
  }

  private static String getRequiredInterceptor() {
    return ClearConnection.class.getName();
  }

  /**
   * Initialize the Data Source with the Connection pool and returns the connection
   *
   * @return java.sql.Connection
   */
  private Connection getConnection() throws SQLException {
    return datasource.getConnection();
  }

  public static Connection getConnection(Properties properties) throws SQLException {
    TomcatConnectionPool pool = poolMap.computeIfAbsent(properties,
        TomcatConnectionPool::new);
    Connection connection = pool.getConnection();
    CURRENT_CONNECTION.set(connection);
    return connection;
  }

  /**
   * Initializes the Object with passed on properties.
   */
  private TomcatConnectionPool(Properties prop) {

    List<String> listPoolPropKeys = PoolProps.getKeys();

    PoolProperties poolProperties = getPoolProperties(prop);

    // Filtering out the pool properties and creating string of
    // connection properties to pass on.
    Set<String> keys = prop.stringPropertyNames();
    String connectionProperties = keys.stream().filter(x -> !listPoolPropKeys.contains(x))
        .map(i -> i + "=" + prop.getProperty(i))
        .collect(Collectors.joining(";"));
    poolProperties.setConnectionProperties(connectionProperties);

    datasource = new DataSource();
    datasource.setPoolProperties(poolProperties);
  }

  /**
   * Method responsible for collecting pooled properties from the
   * properties object passed to connection and creates PoolProperties
   * object by setting the pool properties into it.
   */
  private PoolProperties getPoolProperties(Properties prop) {

    PoolProperties poolProperties = new PoolProperties();
    String url = prop.getProperty(PoolProps.URL.key);
    poolProperties.setUrl(url);
    String driverClassName = prop.getProperty(PoolProps.DRIVER_NAME.key);
    poolProperties.setDriverClassName(driverClassName);

    String username = prop.getProperty(PoolProps.USER.key);
    if (!Util.isEmptyString(username)) {
      poolProperties.setUsername(username);
    }

    String password = prop.getProperty(PoolProps.PASSWORD.key);
    if (!Util.isEmptyString(password)) {
      poolProperties.setPassword(password);
    }

    String initSize = prop.getProperty(PoolProps.INIT_SIZE.key,
        PoolProps.INIT_SIZE.defValue);
    poolProperties.setInitialSize(Integer.parseInt(initSize));

    String maxActive = prop.getProperty(PoolProps.MAX_ACTIVE.key,
        PoolProps.MAX_ACTIVE.defValue);
    poolProperties.setMaxActive(Integer.parseInt(maxActive));

    String maxIdle = prop.getProperty(PoolProps.MAX_IDLE.key,
        PoolProps.MAX_IDLE.defValue);
    poolProperties.setMaxIdle(Integer.parseInt(maxIdle));

    String minIdle = prop.getProperty(PoolProps.MIN_IDLE.key,
        PoolProps.MIN_IDLE.defValue);
    poolProperties.setMinIdle(Integer.parseInt(minIdle));

    String waitTime = prop.getProperty(PoolProps.MAX_WAIT.key,
        PoolProps.MAX_WAIT.defValue);
    poolProperties.setMaxWait(Integer.parseInt(waitTime));

    String removeAbandoned = prop.getProperty(PoolProps.REMOVE_ABANDONED.key,
        PoolProps.REMOVE_ABANDONED.defValue);
    poolProperties.setRemoveAbandoned(Boolean.parseBoolean(removeAbandoned));

    String removeAbandonedTimeout =
        prop.getProperty(PoolProps.REMOVE_ABANDONED_TIMEOUT.key,
            PoolProps.REMOVE_ABANDONED_TIMEOUT.defValue);
    poolProperties.setRemoveAbandonedTimeout(Integer.parseInt(removeAbandonedTimeout));

    String evictionRunMillis =
        prop.getProperty(PoolProps.TIME_BETWEEN_EVICTION_RUNS_MILLIS.key,
            PoolProps.TIME_BETWEEN_EVICTION_RUNS_MILLIS.defValue);
    poolProperties.setTimeBetweenEvictionRunsMillis(Integer.parseInt(evictionRunMillis));

    String minEvictableIdleTimeMillis =
        prop.getProperty(PoolProps.MIN_EVICTABLE_IDLE_TIME_MILLIS.key,
            PoolProps.MIN_EVICTABLE_IDLE_TIME_MILLIS.defValue);
    poolProperties.setMinEvictableIdleTimeMillis(
        Integer.parseInt(minEvictableIdleTimeMillis));

    String testOnBorrow =
        prop.getProperty(PoolProps.TEST_ON_BORROW.key,
            PoolProps.TEST_ON_BORROW.defValue);
    poolProperties.setTestOnBorrow(Boolean.parseBoolean(testOnBorrow));

    String testOnReturn =
        prop.getProperty(PoolProps.TEST_ON_RETURN.key,
            PoolProps.TEST_ON_RETURN.defValue);
    poolProperties.setTestOnReturn(Boolean.parseBoolean(testOnReturn));

    String validationInterval =
        prop.getProperty(PoolProps.VALIDATION_INTERVAL.key,
            PoolProps.VALIDATION_INTERVAL.defValue);
    poolProperties.setValidationInterval(Long.parseLong(validationInterval));

    // DEFAULT TRANSACTION PROPERTIES - START
    boolean defaultAutoCommit = Boolean.valueOf(
        prop.getProperty(PoolProps.DEFAULT_AUTO_COMMIT.key,
            PoolProps.DEFAULT_AUTO_COMMIT.defValue));
    poolProperties.setDefaultAutoCommit(defaultAutoCommit);

    boolean defaultReadOnly = Boolean.valueOf(
        prop.getProperty(PoolProps.DEFAULT_READ_ONLY.key,
            PoolProps.DEFAULT_READ_ONLY.defValue));
    poolProperties.setDefaultReadOnly(defaultReadOnly);

    int defaultTransactionIsolation = Integer.parseInt(
        prop.getProperty(PoolProps.DEFAULT_TRANSACTION_ISOLATION.key,
            PoolProps.DEFAULT_TRANSACTION_ISOLATION.defValue));
    poolProperties.setDefaultTransactionIsolation(defaultTransactionIsolation);
    // DEFAULT TRANSACTION PROPERTIES - START

    // the connection is reset to the desired state each time its borrowed from the pool.
    // In this case above three, 1. autoCommit, 2. readOnly & 3. transactionIsolation level
    // is reset with the default value.
    String jdbcInterceptor =
        prop.getProperty(PoolProps.JDBC_INTERCEPTOR.key,
            PoolProps.JDBC_INTERCEPTOR.defValue);
    // force add interceptor for clearing things on connection close else it can be a leak
    String requiredInterceptor = getRequiredInterceptor();
    if (!jdbcInterceptor.contains(requiredInterceptor)) {
      jdbcInterceptor = jdbcInterceptor + ";" + requiredInterceptor;
    }
    poolProperties.setJdbcInterceptors(jdbcInterceptor);

    return poolProperties;
  }
}
