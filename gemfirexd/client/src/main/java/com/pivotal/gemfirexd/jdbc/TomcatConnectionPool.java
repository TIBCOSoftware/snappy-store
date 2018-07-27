
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

import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;

import java.sql.Connection;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

class TomcatConnectionPool {

    private static TomcatConnectionPool connPoolInstance = null;
    private static DataSource datasource;

    private TomcatConnectionPool(Properties prop) {

        PoolProperties p = new PoolProperties();
        p.setUrl(prop.getProperty("url"));
        p.setDriverClassName(prop.getProperty("driver"));
        p.setUsername(prop.getProperty("user"));
        p.setPassword(prop.getProperty("password"));
        p.setInitialSize(Integer.parseInt(prop.getProperty("pool-size")));
        String waitTime = prop.getProperty("wait-time") == null ?
                "60" : prop.getProperty("wait-time");
        p.setMaxWait(Integer.parseInt(waitTime));

        StringBuffer buffer = new StringBuffer();
        Set<String> keys = prop.stringPropertyNames();
        String commaSeparatedProps = keys.stream()
                .map(i -> i.toString() +"="+ prop.getProperty(i.toString()))
                .collect(Collectors.joining(","));

        p.setConnectionProperties(commaSeparatedProps);

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

