/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

import com.sun.xml.internal.fastinfoset.stax.events.Util;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A Connection Pool class internally uses the tomcat connection pooling
 * library.
 */
class TomcatConnectionPool {

    private static DataSource datasource;

    static enum PoolProps {

        DRIVER_NAME("pool-driverClassName", ""), // Compulsory field user must provide
        URL("pool-url", ""), // Compulsory field user must provide
        USER("pool-user", "APP"),
        PASSWORD("pool-password", "APP"),
        INIT_SIZE("pool-initialSize", "10"),
        MAX_ACTIVE("pool-maxActive", "100"),
        MIN_IDLE("pool-minIdle", "10"),  // Default value is derived from initialSize:10
        MAX_IDLE("pool-maxIdle", "100"),  // Default value is maxActive:100
        MAX_WAIT_MILLIS("pool-maxWaitMillis", "30000"),
        REMOVE_ABANDONED("pool-removeAbandoned", "false"),
        REMOVE_ABANDONED_TIMEOUT("pool-removeAbandonedTimeout", "60"),
        TIME_BETWEEN_EVICTION_RUNS_MILLIS("pool-timeBetweenEvictionRunsMillis", "60000"),
        MIN_EVICTABLE_IDLE_TIME_MILLIS("pool-minEvictableIdleTimeMillis", "60000");

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

    /**
     * Initialize the Data Source with the Connection pool and returns the connection
     *
     * @return java.sql.Connection
     * @throws SQLException
     */
    public Connection getConnection() throws SQLException {
        Connection conn = null;
        try {
            conn = datasource.getConnection();
        } catch (Exception e) {
            System.out.println("Exception while creating connection connection pool");
        }
        return conn;
    }

    /**
     * Initializes the Object with passed on properties.
     *
     * @param prop
     */
    public TomcatConnectionPool(Properties prop) {


        List<String> listPoolPropKeys = PoolProps.getKeys();

        PoolProperties poolProperties = setPoolProperties(prop);

        // Filtering out the pool properties and creating string of
        // connection properties to pass on.
        Set<String> keys = prop.stringPropertyNames();
        String connectionProperties = keys.stream().filter(x -> listPoolPropKeys.contains(x))
                .map(i -> i.toString() + "=" + prop.getProperty(i.toString()))
                .collect(Collectors.joining(";"));

        poolProperties.setConnectionProperties(connectionProperties);
        datasource = new DataSource();
        datasource.setPoolProperties(poolProperties);
    }

    /**
     * Method responsible for collecting pooled properties from the
     * properties object passed to connection and creates PoolProperties
     * object by setting the pool properties into it.
     *
     * @param prop
     * @return
     */
    private PoolProperties setPoolProperties(Properties prop) {

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

        String initSize = prop.getProperty(PoolProps.INIT_SIZE.key, PoolProps.INIT_SIZE.defValue);
        poolProperties.setInitialSize(Integer.parseInt(initSize));

        String maxActive = prop.getProperty(PoolProps.MAX_ACTIVE.key, PoolProps.MAX_ACTIVE.defValue);
        poolProperties.setMaxActive(Integer.parseInt(maxActive));

        String maxIdle = prop.getProperty(PoolProps.MAX_IDLE.key, PoolProps.MAX_IDLE.defValue);
        poolProperties.setMaxIdle(Integer.parseInt(maxIdle));

        String minIdle = prop.getProperty(PoolProps.MIN_IDLE.key, PoolProps.MIN_IDLE.defValue);
        poolProperties.setMaxIdle(Integer.parseInt(minIdle));

        String waitTime = prop.getProperty(PoolProps.MAX_WAIT_MILLIS.key,
                PoolProps.MAX_WAIT_MILLIS.defValue);
        poolProperties.setMaxWait(Integer.parseInt(waitTime));

        String removeAbandoned = prop.getProperty(PoolProps.REMOVE_ABANDONED.key,
                PoolProps.REMOVE_ABANDONED.defValue);
        poolProperties.setRemoveAbandoned(Boolean.parseBoolean(removeAbandoned));

        String removeAbandonedTimeout = prop.getProperty(PoolProps.REMOVE_ABANDONED_TIMEOUT.key,
                PoolProps.REMOVE_ABANDONED_TIMEOUT.defValue);
        poolProperties.setRemoveAbandonedTimeout(Integer.parseInt(removeAbandonedTimeout));

        String evictionRunMillis = prop.getProperty(PoolProps.TIME_BETWEEN_EVICTION_RUNS_MILLIS.key,
                PoolProps.TIME_BETWEEN_EVICTION_RUNS_MILLIS.defValue);
        poolProperties.setTimeBetweenEvictionRunsMillis(Integer.parseInt(evictionRunMillis));

        String minEvictableIdleTimeMillis = prop.getProperty(PoolProps.MIN_EVICTABLE_IDLE_TIME_MILLIS.key,
                PoolProps.MIN_EVICTABLE_IDLE_TIME_MILLIS.defValue);
        poolProperties.setMinEvictableIdleTimeMillis(Integer.parseInt(minEvictableIdleTimeMillis));

        return poolProperties;
    }
}