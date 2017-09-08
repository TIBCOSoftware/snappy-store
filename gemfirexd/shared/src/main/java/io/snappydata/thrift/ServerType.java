/**
 * Autogenerated by Thrift Compiler (0.9.3)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

/*
 * Changes for GemFireXD distributed data platform.
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

package io.snappydata.thrift;

public enum ServerType implements org.apache.thrift.TEnum {
  DRDA(1) {
    @Override
    public String getProtocolString() {
      return "DRDA";
    }
  },

  THRIFT_LOCATOR_CP(2) {
    @Override
    public boolean isThriftLocator() {
      return true;
    }
    @Override
    public boolean isThriftWithDefaults() {
      return true;
    }
    @Override
    public String getProtocolString() {
      return "Compact Protocol";
    }
  },

  THRIFT_LOCATOR_BP(3) {
    @Override
    public boolean isThriftLocator() {
      return true;
    }
    @Override
    public boolean isThriftBinaryProtocol() {
      return true;
    }
    @Override
    public String getProtocolString() {
      return "Binary Protocol";
    }
  },

  THRIFT_LOCATOR_CP_SSL(4) {
    @Override
    public boolean isThriftLocator() {
      return true;
    }
    @Override
    public boolean isThriftSSL() {
      return true;
    }
    @Override
    public String getProtocolString() {
      return "Compact Protocol with SSL";
    }
  },

  THRIFT_LOCATOR_BP_SSL(5) {
    @Override
    public boolean isThriftLocator() {
      return true;
    }
    @Override
    public boolean isThriftBinaryProtocol() {
      return true;
    }
    @Override
    public boolean isThriftSSL() {
      return true;
    }
    @Override
    public String getProtocolString() {
      return "Binary Protocol with SSL";
    }
  },

  THRIFT_SNAPPY_CP(6) {
    @Override
    public boolean isThriftSnappy() {
      return true;
    }
    @Override
    public boolean isThriftWithDefaults() {
      return true;
    }
    @Override
    public ServerType getCorrespondingLocatorType() {
      return THRIFT_LOCATOR_CP;
    }
    @Override
    public String getProtocolString() {
      return "Compact Protocol";
    }
  },

  THRIFT_SNAPPY_BP(7) {
    @Override
    public boolean isThriftSnappy() {
      return true;
    }
    @Override
    public boolean isThriftBinaryProtocol() {
      return true;
    }
    @Override
    public ServerType getCorrespondingLocatorType() {
      return THRIFT_LOCATOR_BP;
    }
    @Override
    public String getProtocolString() {
      return "Binary Protocol";
    }
  },

  THRIFT_SNAPPY_CP_SSL(8) {
    @Override
    public boolean isThriftSnappy() {
      return true;
    }
    @Override
    public boolean isThriftSSL() {
      return true;
    }
    @Override
    public ServerType getCorrespondingLocatorType() {
      return THRIFT_LOCATOR_CP_SSL;
    }
    @Override
    public String getProtocolString() {
      return "Compact Protocol with SSL";
    }
  },

  THRIFT_SNAPPY_BP_SSL(9) {
    @Override
    public boolean isThriftSnappy() {
      return true;
    }
    @Override
    public boolean isThriftBinaryProtocol() {
      return true;
    }
    @Override
    public boolean isThriftSSL() {
      return true;
    }
    @Override
    public ServerType getCorrespondingLocatorType() {
      return THRIFT_LOCATOR_BP_SSL;
    }
    @Override
    public String getProtocolString() {
      return "Binary Protocol with SSL";
    }
  },
  ;

  public static final String NETSERVER_GROUP_PREFIX = "__SNAPPY_NETWORKSERVICE_";

  private final int value;
  private final String groupName;

  private ServerType(int value) {
    this.value = value;
    this.groupName = NETSERVER_GROUP_PREFIX + name();
  }

  /**
   * Get the integer value of this enum value, as defined in the Thrift IDL.
   */
  @Override
  public final int getValue() {
    return value;
  }

  /**
   * Find a the enum type by its integer value, as defined in the Thrift IDL.
   * 
   * @return null if the value is not found.
   */
  public static ServerType findByValue(int value) {
    switch (value) {
      case 1:
        return DRDA;
      case 2:
        return THRIFT_LOCATOR_CP;
      case 3:
        return THRIFT_LOCATOR_BP;
      case 4:
        return THRIFT_LOCATOR_CP_SSL;
      case 5:
        return THRIFT_LOCATOR_BP_SSL;
      case 6:
        return THRIFT_SNAPPY_CP;
      case 7:
        return THRIFT_SNAPPY_BP;
      case 8:
        return THRIFT_SNAPPY_CP_SSL;
      case 9:
        return THRIFT_SNAPPY_BP_SSL;
      default:
        return null;
    }
  }

  public final String getServerGroupName() {
    return this.groupName;
  }

  public final boolean isDRDA() {
    return this == DRDA;
  }

  public final boolean isThrift() {
    return this != DRDA;
  }

  public boolean isThriftLocator() {
    return false;
  }

  public boolean isThriftSnappy() {
    return false;
  }

  public boolean isThriftWithDefaults() {
    return false;
  }

  public boolean isThriftBinaryProtocol() {
    return false;
  }

  public boolean isThriftSSL() {
    return false;
  }

  /**
   * Get the ServerType given the various settings for the host.
   */
  public static ServerType getServerType(final boolean isServer,
      final boolean useBinaryProtocol, final boolean useSSL) {
    if (isServer) {
      if (useSSL) {
        return useBinaryProtocol ? ServerType.THRIFT_SNAPPY_BP_SSL
            : ServerType.THRIFT_SNAPPY_CP_SSL;
      }
      else {
        return useBinaryProtocol ? ServerType.THRIFT_SNAPPY_BP
            : ServerType.THRIFT_SNAPPY_CP;
      }
    }
    else if (useSSL) {
      return useBinaryProtocol ? ServerType.THRIFT_LOCATOR_BP_SSL
          : ServerType.THRIFT_LOCATOR_CP_SSL;
    }
    else {
      return useBinaryProtocol ? ServerType.THRIFT_LOCATOR_BP
          : ServerType.THRIFT_LOCATOR_CP;
    }
  }

  /**
   * For a server type running Thrift SnappyDataService, return the corresponding
   * LocatorService type i.e. having same settings for SSL, Compact/Binary
   * protocol etc. If this server type is not a Thrift SnappyDataService,
   * return null.
   */
  public ServerType getCorrespondingLocatorType() {
    return null;
  }

  public abstract String getProtocolString();

  public static final ServerType findByGroupName(String serverGroupName) {
    if (serverGroupName.startsWith(NETSERVER_GROUP_PREFIX)) {
      String serverType = serverGroupName.substring(NETSERVER_GROUP_PREFIX
          .length());
      return ServerType.valueOf(serverType);
    }
    else {
      return ServerType.DRDA;
    }
  }
}
