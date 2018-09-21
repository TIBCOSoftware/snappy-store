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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.client.am.Utils;
import com.pivotal.gemfirexd.internal.shared.common.error.ClientExceptionUtil;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.jdbc.ClientDRDADriver;
import io.snappydata.thrift.internal.ClientConfiguration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client Driver with the capability to maintain connection
 * pool inside it and return connections from the pool.
 */
public class ClientPoolDriver extends ClientDriver {

    private final static String SUBPROTOCOL = "(thrift:pool:|pool:)+";
    private final static Pattern PROTOCOL_PATTERN = Pattern.compile(URL_PREFIX_REGEX +
            SUBPROTOCOL + "//.*", Pattern.CASE_INSENSITIVE);
    private final static Pattern URL_PATTERN = Pattern.compile(URL_PREFIX_REGEX +
            SUBPROTOCOL + URL_SUFFIX_REGEX, Pattern.CASE_INSENSITIVE);

    private static Map<Properties, TomcatConnectionPool> poolMap =
            new ConcurrentHashMap<Properties, TomcatConnectionPool>();

    public final static String POOL_INITIALIZATION = "init-pool";

    static {
        try {
            final ClientDRDADriver driver = registeredDriver__;
            registeredDriver__ = new ClientPoolDriver();
            java.sql.DriverManager.registerDriver(registeredDriver__);
            if (driver != null) {
                java.sql.DriverManager.deregisterDriver(driver);
            }
        } catch (SQLException e) {
            // A null log writer is passed, because jdbc 1 sql exceptions are
            // automatically traced
            exceptionsOnLoadDriver__ = ClientExceptionUtil.newSQLException(
                    SQLState.JDBC_DRIVER_REGISTER, e);
        }
        // This may possibly hit the race-condition bug of java 1.1.
        // The Configuration static clause should execute before the following line
        // does.
        if (ClientConfiguration.exceptionsOnLoadResources != null) {
            exceptionsOnLoadDriver__ = Utils.accumulateSQLException(
                    ClientConfiguration.exceptionsOnLoadResources,
                    exceptionsOnLoadDriver__);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean acceptsURL(String url) throws SQLException {
        return (url != null && matchProtocol(url).matches());
    }

    @Override
    protected boolean useThriftProtocol(Matcher m) {
        String subProtocolGroup = m.group(2);
        String protocol = m.group(1);
        // if USE_THRIFT_AS_DEFAULT_PROP has been set explicitly then use that
        // for default value else use the protocol string (jdbc:snappydata://
        //   defaults to thrift while jdbc:gemfirexd:// defaults to old DRDA)
        return subProtocolGroup == null || subProtocolGroup.length() == 0
                ? ClientSharedUtils.isUsingThrift(
                protocol.equalsIgnoreCase(SNAPPY_PROTOCOL))
                : "thrift:pool:".equalsIgnoreCase(subProtocolGroup)
                || "pool:".equalsIgnoreCase(subProtocolGroup);
    }

    @Override
    protected Matcher matchURL(String url) {
        return URL_PATTERN.matcher(url);
    }

    @Override
    protected Matcher matchProtocol(String url) {
        return PROTOCOL_PATTERN.matcher(url);
    }

    @Override
    public Connection connect(String url, Properties properties) throws SQLException {

        if (!acceptsURL(url)) {
            System.out.println("Returning null connection From ClientPoolDriver ");
            return null;
        }

        properties = (properties == null) ? getPoolProperties(url) : properties;
        properties.setProperty(TomcatConnectionPool.POOL_URL, url);
        properties.setProperty(TomcatConnectionPool.POOL_DRIVER_NAME, ClientPoolDriver.class.getName());

        boolean creatingPool = Boolean.parseBoolean(properties.getProperty(POOL_INITIALIZATION));
        if (creatingPool) {
            return super.connect(url, properties);
        }

        TomcatConnectionPool p = poolMap.get(properties);
        if (p != null) {
            return p.getConnection();
        } else {
            TomcatConnectionPool pool = poolMap.get(properties);
            if (pool == null) {
                synchronized (ClientPoolDriver.class) {
                    pool = poolMap.get(properties);
                    if (pool == null) {
                        pool = new TomcatConnectionPool(properties);
                        poolMap.put(properties, pool);
                    }
                }
            }
            Connection conn = pool.getConnection();
            return conn;
        }
    }

    private Properties getPoolProperties(String url) {
        Properties properties = new Properties();
        properties.setProperty(TomcatConnectionPool.POOL_DRIVER_NAME, ClientPoolDriver.class.getName());
        properties.setProperty(TomcatConnectionPool.POOL_URL, url);
        properties.setProperty(TomcatConnectionPool.POOL_INIT_SIZE, "10");
        properties.setProperty(TomcatConnectionPool.POOL_MAX_ACTIVE, "10");
        properties.setProperty(TomcatConnectionPool.POOL_MAX_IDLE, "5");
        properties.setProperty(TomcatConnectionPool.POOL_MAX_WAIT_MILLIS, "10000");
        properties.setProperty(TomcatConnectionPool.POOL_REMOVE_ABANDONED, "true");
        properties.setProperty(TomcatConnectionPool.POOL_REMOVE_ABANDONED_TIMEOUT, "300");
        return properties;
    }

}