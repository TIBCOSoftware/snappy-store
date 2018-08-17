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

package com.pivotal.gemfirexd.jdbc;

import com.sun.xml.internal.fastinfoset.stax.events.Util;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;

import java.sql.Connection;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

class TomcatConnectionPool {

    private static TomcatConnectionPool connPoolInstance = null;
    private static DataSource datasource;

    private static final String USER = "user";
    private static final String PASSWORD = "password";
    private static final String DRIVER_NAME = "driverClassName";
    private static final String URL = "url";
    private static final String MAX_TOTAL = "maxTotal";
    private static final String MAX_IDLE = "maxIdle";
    private static final String MAX_WAIT_MILLIS = "maxWaitMillis";
    private static final String REMOVE_ABANDONED = "removeAbandoned";
    private static final String REMOVE_ABANDONED_TIMEOUT = "removeAbandonedTimeout";


    private TomcatConnectionPool(Properties prop) {

        PoolProperties p = new PoolProperties();
        String url = prop.getProperty(URL);
        if(!Util.isEmptyString(url)) {
            p.setUrl(url);
        }

        String driverClassName = prop.getProperty(DRIVER_NAME);
        if(!Util.isEmptyString(driverClassName)) {
            p.setDriverClassName(driverClassName);
        }

        String maxTotal = (prop.getProperty(MAX_TOTAL));
        if(!Util.isEmptyString(maxTotal)) {
            p.setInitialSize(Integer.parseInt(maxTotal));
        }

        String maxIdle = prop.getProperty(MAX_IDLE);
        if(!Util.isEmptyString(maxIdle)){
            p.setMaxIdle(Integer.parseInt(maxIdle));
        }

        String waitTime = prop.getProperty(MAX_WAIT_MILLIS);
        if(!Util.isEmptyString(waitTime)) {
            p.setMaxWait(Integer.parseInt(waitTime));
        }

        String removeAbandoned = prop.getProperty(REMOVE_ABANDONED);
        if(!Util.isEmptyString(removeAbandoned)){
            p.setRemoveAbandoned(Boolean.parseBoolean(removeAbandoned));
        }

        String removeAbandonedTimeout = prop.getProperty(REMOVE_ABANDONED_TIMEOUT);
        if(!Util.isEmptyString(removeAbandonedTimeout)) {
            p.setRemoveAbandonedTimeout(Integer.parseInt(removeAbandonedTimeout));
        }

        String username = prop.getProperty(USER);
        if(!Util.isEmptyString(username)) {
            p.setUsername(username);
        }

        String password = prop.getProperty(PASSWORD);
        if(!Util.isEmptyString(password)) {
            p.setPassword(password);
        }

        StringBuffer buffer = new StringBuffer();
        Set<String> keys = prop.stringPropertyNames();
        String userProperties = keys.stream()
                .map(i -> i.toString() +"="+ prop.getProperty(i.toString()))
                .collect(Collectors.joining(","));

        p.setConnectionProperties(userProperties);

        datasource = new DataSource();
        datasource.setPoolProperties(p);
    }

    static TomcatConnectionPool getInstance(Properties prop) {
        synchronized (TomcatConnectionPool.class) {
            if (connPoolInstance == null) {
                connPoolInstance = new TomcatConnectionPool(prop);
            }
        }
        return connPoolInstance;
    }

    synchronized Connection getConnection() {
        Connection conn = null;
        try {
            conn = datasource.getConnection();
        } catch (Exception e) {
            System.out.println("Got exception while getting connection using tomcat connection " +
                    "pool");
        }
        return conn;
    }

}

