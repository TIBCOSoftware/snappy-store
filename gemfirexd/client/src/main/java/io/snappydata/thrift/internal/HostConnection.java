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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.internal;

import java.nio.ByteBuffer;

import io.snappydata.thrift.HostAddress;

/**
 * Encapsulates a connection to a specific host.
 */
public final class HostConnection {

  public final HostAddress hostAddr;
  public final long connId;
  public final ByteBuffer token;

  public HostConnection(HostAddress host, long connId, ByteBuffer token) {
    this.hostAddr = host;
    this.connId = connId;
    this.token = token;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int hashCode() {
    int h = (int)(connId ^ (connId >>> 32)) + hostAddr.hashCode();
    if (token != null) {
      h += token.hashCode();
    }
    return h;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean equals(Object other) {
    return this == other ||
        (other instanceof HostConnection && equals((HostConnection)other));
  }

  public boolean equals(final HostConnection other) {
    return this == other || (connId == other.connId
        && (hostAddr == other.hostAddr || hostAddr.equals(other.hostAddr))
        && (token == other.token ||
        (token != null && token.equals(other.token))));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toString() {
    return "[" + hostAddr.toString() + ",connId=" + connId + "]";
  }
}
