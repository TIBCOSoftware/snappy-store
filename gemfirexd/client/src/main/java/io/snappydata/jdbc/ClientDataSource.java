/*
 
   Derby - Class org.apache.derby.jdbc.ClientDataSource40
 
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at
 
      http://www.apache.org/licenses/LICENSE-2.0
 
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 
 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

import java.sql.SQLException;

import com.pivotal.gemfirexd.internal.client.am.ClientMessageId;
import com.pivotal.gemfirexd.internal.client.am.SqlException;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

/**
 * ClientDataSource is a simple data source implementation
 * that can be used for establishing connections in a
 * non-pooling, non-distributed environment.
 * The class ClientConnectionPoolDataSource can be used in a connection pooling environment,
 * and the class ClientXADataSource can be used in a distributed, and pooling
 * environment.
 * <p>
 * <p>The example below registers a DNC data source object with a JNDI naming service.
 * <pre>
 * io.snappydata.jdbc.ClientDataSource dataSource = new io.snappydata.jdbc.ClientDataSource();
 * dataSource.setServerName ("my_database_server");
 * dataSource.setDatabaseName ("my_database_name");
 * javax.naming.Context context = new javax.naming.InitialContext();
 * context.bind ("jdbc/my_datasource_name", dataSource);
 * </pre>
 * The first line of code in the example creates a data source object.
 * The next two lines initialize the data source's
 * properties. Then a Java object that references the initial JNDI naming
 * context is created by calling the
 * InitialContext() constructor, which is provided by JNDI.
 * System properties (not shown) are used to tell JNDI the
 * service provider to use. The JNDI name space is hierarchical,
 * similar to the directory structure of many file
 * systems. The data source object is bound to a logical JNDI name
 * by calling Context.bind(). In this case the JNDI name
 * identifies a subcontext, "jdbc", of the root naming context
 * and a logical name, "my_datasource_name", within the jdbc
 * subcontext. This is all of the code required to deploy
 * a data source object within JNDI. This example is provided
 * mainly for illustrative purposes. We expect that developers
 * or system administrators will normally use a GUI tool to
 * deploy a data source object.
 * <p/>
 * Once a data source has been registered with JNDI,
 * it can then be used by a JDBC application, as is shown in the
 * following example.
 * <pre>
 * javax.naming.Context context = new javax.naming.InitialContext ();
 * javax.sql.DataSource dataSource = (javax.sql.DataSource) context.lookup ("jdbc/my_datasource_name");
 * java.sql.Connection connection = dataSource.getConnection ("user", "password");
 * </pre>
 * The first line in the example creates a Java object
 * that references the initial JNDI naming context. Next, the
 * initial naming context is used to do a lookup operation
 * using the logical name of the data source. The
 * Context.lookup() method returns a reference to a Java Object,
 * which is narrowed to a javax.sql.DataSource object. In
 * the last line, the DataSource.getConnection() method
 * is called to produce a database connection.
 * <p/>
 * This simple data source subclass of ClientBaseDataSource maintains
 * it's own private <code>password</code> property.
 * <p/>
 * The specified password, along with the user, is validated by database.
 * This property can be overwritten by specifing
 * the password parameter on the DataSource.getConnection() method call.
 * <p/>
 * This password property is not declared transient, and therefore
 * may be serialized to a file in clear-text, or stored
 * to a JNDI server in clear-text when the data source is saved.
 * Care must taken by the user to prevent security
 * breaches.
 * <p/>
 */
public class ClientDataSource extends com.pivotal.gemfirexd.internal.jdbc.ClientDataSource {

  public ClientDataSource() {
    super();
  }

  /**
   * Returns false unless <code>interfaces</code> is implemented
   *
   * @param interfaces a Class defining an interface.
   * @return true                   if this implements the interface or
   * directly or indirectly wraps an object
   * that does.
   * @throws java.sql.SQLException if an error occurs while determining
   *                               whether this is a wrapper for an object
   *                               with the given interface.
   */
// GemStone changes BEGIN
  // made non-generic so can override the method in base class so that can
  // be compiled with both JDK 1.6 and 1.4
  public boolean isWrapperFor(Class interfaces) throws SQLException {
    /* (original code)
    public boolean isWrapperFor(Class<?> interfaces) throws SQLException {
    */
// GemStone changes END
    return interfaces.isInstance(this);
  }

  /**
   * Returns <code>this</code> if this class implements the interface
   *
   * @param interfaces a Class defining an interface
   * @return an object that implements the interface
   * @throws java.sql.SQLException if no object if found that implements the
   *                               interface
   */
// GemStone changes BEGIN
  // made non-generic so can override the method in base class so that can
  // be compiled with both JDK 1.6 and 1.4
  public Object unwrap(java.lang.Class interfaces) throws SQLException {
    /* (original code)
    public <T> T unwrap(java.lang.Class<T> interfaces)
                                   throws SQLException {
    */
// GemStone changes END
    try {
      return interfaces.cast(this);
    } catch (ClassCastException cce) {
      throw new SqlException(null, new ClientMessageId(SQLState.UNABLE_TO_UNWRAP),
          interfaces).getSQLException(null /* GemStoneAddition */);
    }
  }
}
