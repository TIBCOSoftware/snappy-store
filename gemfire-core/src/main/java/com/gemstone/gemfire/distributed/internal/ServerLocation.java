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
package com.gemstone.gemfire.distributed.internal;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.shared.HostLocationBase;

/**
 * Represents the location of a bridge server. This class is 
 * preferable to InetSocketAddress because it doesn't do
 * any name resolution.
 * 
 * @author dsmith
 *
 */
public class ServerLocation extends HostLocationBase<ServerLocation> implements
    DataSerializable, Serializable {

  private static final long serialVersionUID = -5850116974987640560L;

  /**
   * Used exclusively in case of single user authentication mode. Client sends
   * this userId to the server with each operation to identify itself at the
   * server.
   */
  private long userId = -1;

  /**
   * If its value is two, it lets the client know that it need not send the
   * security part (connection-id, user-id) with each operation to the server.
   * Also, that the client should not expect the security part in the server's
   * response.
   */
  private final AtomicInteger requiresCredentials = new AtomicInteger(INITIAL_REQUIRES_CREDENTIALS);

  public static final int INITIAL_REQUIRES_CREDENTIALS = 0;

  public static final int REQUIRES_CREDENTIALS = 1;

  public static final int REQUIRES_NO_CREDENTIALS = 2;

  /**
   * For DataSerializer
   */
  public ServerLocation() {
  }
  
  public ServerLocation(String hostName, int port) {
    super(hostName, port);
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    hostName = DataSerializer.readString(in);
    port = in.readInt();
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    DataSerializer.writeString(hostName, out);
    out.writeInt(port);
  }

  @Override
  public String toString() {
    return hostName + ":" + port;
  }

  public void setUserId(long id) {
    this.userId = id;
  }

  public long getUserId() {
    return this.userId;
  }

  public void compareAndSetRequiresCredentials(boolean bool) {
    int val = bool ? REQUIRES_CREDENTIALS : REQUIRES_NO_CREDENTIALS;
    this.requiresCredentials.compareAndSet(INITIAL_REQUIRES_CREDENTIALS, val);
  }

  public void setRequiresCredentials(boolean bool) {
    int val = bool ? REQUIRES_CREDENTIALS : REQUIRES_NO_CREDENTIALS;
    this.requiresCredentials.set(val);
  }

  public boolean getRequiresCredentials() {
    return this.requiresCredentials.get() == REQUIRES_CREDENTIALS ? true : false;
  }
  
}
