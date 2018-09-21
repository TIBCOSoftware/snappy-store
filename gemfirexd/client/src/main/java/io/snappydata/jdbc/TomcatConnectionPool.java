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
import java.util.*;
import java.util.stream.Collectors;

/**
 * A Connection Pool class internally uses the tomcat connection pooling
 * library.
 */
class TomcatConnectionPool {

    private static DataSource datasource;

    public static final String POOL_DRIVER_NAME = "pool-driverClassName";
    public static final String POOL_URL = "pool-url";

    public static final String POOL_USER = "pool-user";
    public static final String POOL_PASSWORD = "pool-password";
    public static final String POOL_INIT_SIZE = "pool-initialSize";
    public static final String POOL_MAX_ACTIVE = "pool-maxActive";
    public static final String POOL_MAX_IDLE = "pool-maxIdle";
    public static final String POOL_MAX_WAIT_MILLIS = "pool-maxWaitMillis";
    public static final String POOL_REMOVE_ABANDONED = "pool-removeAbandoned";
    public static final String POOL_REMOVE_ABANDONED_TIMEOUT = "pool-removeAbandonedTimeout";

    private static String[] poolPropKeys = {POOL_USER, POOL_PASSWORD, POOL_DRIVER_NAME, POOL_URL,
            POOL_INIT_SIZE, POOL_MAX_ACTIVE, POOL_MAX_IDLE, POOL_MAX_WAIT_MILLIS
            , POOL_REMOVE_ABANDONED, POOL_REMOVE_ABANDONED_TIMEOUT};


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
            System.out.println("Got exception while getting connection using tomcat connection " +
                    "pool");
        }
        return conn;
    }

    /**
     * Initializes the Object with passed on properties.
     *
     * @param prop
     */
    public TomcatConnectionPool(Properties prop) {

        List<String> listPoolPropKeys = Arrays.asList(poolPropKeys);

        PoolProperties poolProperties = initiatePoolProperties(prop);

        // Filtering out the pool properties and creating string of
        // connection properties to pass on.
        Set<String> keys = prop.stringPropertyNames();
        String connectionProperties = keys.stream().filter(x -> listPoolPropKeys.contains(x))
                .map(i -> i.toString() +"="+ prop.getProperty(i.toString()))
                .collect(Collectors.joining(";"));
        // This is important to differentiate
        connectionProperties = ClientPoolDriver.POOL_INITIALIZATION + "=true;" + connectionProperties;

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
    private PoolProperties initiatePoolProperties(Properties prop){

        PoolProperties poolProperties = new PoolProperties();

        String url = prop.getProperty(POOL_URL);
        poolProperties.setUrl(url);

        String driverClassName = prop.getProperty(POOL_DRIVER_NAME);
        poolProperties.setDriverClassName(driverClassName);

        String initSize = prop.getProperty(POOL_INIT_SIZE);
        if(!Util.isEmptyString(initSize)) {
            poolProperties.setInitialSize(Integer.parseInt(initSize));
        } else {
            poolProperties.setInitialSize(1);
        }

        String maxActive = prop.getProperty(POOL_MAX_ACTIVE);
        if(!Util.isEmptyString(maxActive)) {
            poolProperties.setMaxActive(Integer.parseInt(maxActive));
        }

        String maxIdle = prop.getProperty(POOL_MAX_IDLE);
        if(!Util.isEmptyString(maxIdle)){
            poolProperties.setMaxIdle(Integer.parseInt(maxIdle));
        }

        String waitTime = prop.getProperty(POOL_MAX_WAIT_MILLIS);
        if(!Util.isEmptyString(waitTime)) {
            poolProperties.setMaxWait(Integer.parseInt(waitTime));
        }

        String removeAbandoned = prop.getProperty(POOL_REMOVE_ABANDONED);
        if(!Util.isEmptyString(removeAbandoned)){
            poolProperties.setRemoveAbandoned(Boolean.parseBoolean(removeAbandoned));
        }

        String removeAbandonedTimeout = prop.getProperty(POOL_REMOVE_ABANDONED_TIMEOUT);
        if(!Util.isEmptyString(removeAbandonedTimeout)) {
            poolProperties.setRemoveAbandonedTimeout(Integer.parseInt(removeAbandonedTimeout));
        }

        String username = prop.getProperty(POOL_USER);
        if(!Util.isEmptyString(username)) {
            poolProperties.setUsername(username);
        }

        String password = prop.getProperty(POOL_PASSWORD);
        if(!Util.isEmptyString(password)) {
            poolProperties.setPassword(password);
        }

        return poolProperties;
    }

}

