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

package com.gemstone.gemfire.internal.shared;

import java.io.Serializable;
import java.net.UnknownHostException;

/**
 * Common base methods for <code>ServerLocation</code>, <code>HostAddress</code>
 */
public abstract class HostLocationBase<T extends HostLocationBase<T>>
    implements Comparable<T>, Serializable {

  private static final long serialVersionUID = 299867375891198580L;

  protected String hostName;
  protected int port;

  protected HostLocationBase() {
  }

  public HostLocationBase(String hostName, int port) {
    this.hostName = hostName;
    this.port = port;
  }

  public final String getHostName() {
    return hostName;
  }

  public final int getPort() {
    return port;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    // result = prime * result + ((hostName == null) ? 0 : hostName.hashCode());
    result = prime * result + port;
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    return obj instanceof HostLocationBase && equals((HostLocationBase<?>)obj);
  }

  public boolean equals(HostLocationBase<?> other) {
    if (this == other) {
      return true;
    }
    if (other != null) {
      if (port != other.port) {
        return false;
      }
      return equalsHostName(other);
    }
    else {
      return false;
    }
  }

  protected final boolean equalsHostName(HostLocationBase<?> other) {
    if (hostName == null) {
      if (other.hostName != null) {
        return false;
      }
    }
    else if (other.hostName == null) {
      return false;
    }
    else if (!hostNamesEqual(this.hostName, other.hostName)) {
      String canonicalHostName;
      try {
        canonicalHostName = ClientSharedUtils.getLocalHost()
            .getCanonicalHostName();
      } catch (UnknownHostException e) {
        throw new IllegalStateException("getLocalHost failed with " + e);
      }
      if ("localhost".equals(hostName)) {
        if (!canonicalHostName.equals(other.hostName)) {
          return false;
        }
      }
      else if ("localhost".equals(other.hostName)) {
        if (!canonicalHostName.equals(hostName)) {
          return false;
        }
      }
      else {
        return false; // fix for bug 42040
      }
    }
    return true;
  }

  /**
   * Incoming failed URL string maybe of the form host[port] rather than
   * host/addr[port] (from GemFireXD).
   */
  public static boolean hostNamesEqual(String hostName1, String hostName2) {
    int addrStart;
    if ((addrStart = hostName1.indexOf('/')) == -1) {
      if ((addrStart = hostName2.indexOf('/')) != -1) {
        hostName2 = hostName2.substring(0, addrStart);
      }
    }
    else if (hostName2.indexOf('/') == -1) {
      hostName1 = hostName1.substring(0, addrStart);
    }
    return hostName1.equals(hostName2);
  }

  /**
   * {@inheritDoc}
   */
  public int compareTo(T other) {
    int difference = hostName.compareTo(other.hostName);
    if (difference != 0) {
      return difference;
    }
    return port - other.getPort();
  }

  @Override
  public String toString() {
    return this.hostName + '[' + this.port + ']';
  }
}
