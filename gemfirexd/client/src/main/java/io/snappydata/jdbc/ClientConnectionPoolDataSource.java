/*

   Derby - Class com.pivotal.gemfirexd.internal.jdbc.ClientConnectionPoolDataSource40

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

import java.sql.SQLException;
import javax.sql.PooledConnection;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import io.snappydata.thrift.internal.ClientPooledConnection;

/**
 * ClientConnectionPoolDataSource is a factory for PooledConnection objects.
 * An object that implements this interface
 * will typically be registered with a naming service that is based on the
 * Java Naming and Directory Interface (JNDI). Use this factory
 * if your application runs under JDBC4.0.
 */
public class ClientConnectionPoolDataSource
    extends com.pivotal.gemfirexd.internal.jdbc.ClientConnectionPoolDataSource {

  /**
   * {@inheritDoc}
   */
  @Override
  public PooledConnection getPooledConnection() throws SQLException {
    if (ClientSharedUtils.isThriftDefault()) {
      return getPooledConnection(getUser(), getPassword());
    } else {
      return super.getPooledConnection();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PooledConnection getPooledConnection(String user, String password)
      throws SQLException {
    if (ClientSharedUtils.isThriftDefault()) {
      return new ClientPooledConnection(getServerName(), getPortNumber(),
          false, ClientDataSource.getThriftProperties(user, password, this),
          getLogWriter());
    } else {
      return super.getPooledConnection(user, password);
    }
  }
}
