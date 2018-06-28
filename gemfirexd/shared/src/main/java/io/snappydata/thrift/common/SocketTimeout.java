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

package io.snappydata.thrift.common;

import java.net.SocketException;

/**
 * Interface that defines timeout and various keep-alive settings on the socket.
 */
public interface SocketTimeout {

  /**
   * Get the socket read timeout.
   */
  int getSoTimeout() throws SocketException;

  /**
   * Get the timeout set by previous setters calls and don't read from socket.
   */
  int getRawTimeout();

  /**
   * Sets the socket read timeout.
   *
   * @param timeout read timeout (SO_TIMEOUT) in milliseconds
   */
  void setSoTimeout(int timeout) throws SocketException;

  /**
   * Return true if the connected client is on the same host.
   */
  boolean isSocketToSameHost();

  /**
   * Close this socket
   */
  void close();
}
