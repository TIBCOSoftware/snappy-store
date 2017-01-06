/*

   Derby - Class org.apache.derby.jdbc.ClientXADataSource40

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
 * <p>
 * This is SnappyData's network XADataSource for use with JDBC4.0.
 * </p>
 * An XADataSource is a factory for XAConnection objects.  It represents a
 * RM in a DTP environment.  An object that implements the XADataSource
 * interface is typically registered with a JNDI service provider.
 * <p>
 * <P>ClientXADataSource is serializable and referenceable.</p>
 * <p>
 * <P>See ClientDataSource for DataSource properties.</p>
 */
public class ClientXADataSource extends com.pivotal.gemfirexd.internal.jdbc.ClientXADataSource {

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
      throw new SqlException(null, new ClientMessageId(
          SQLState.UNABLE_TO_UNWRAP), interfaces).getSQLException(
          null /* GemStoneAddition */);
    }
  }
}
