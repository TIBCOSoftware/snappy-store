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

package com.pivotal.gemfirexd.internal.shared.common;

/**
 * 
 * @author kneeraj
 * 
 */
public class ClientColumnResolver extends AbstractClientResolver {

  private final int[] typeIdArray;

  public ClientColumnResolver(int[] typeArray, boolean requiresSerializedHash) {
    this.typeIdArray = typeArray;
  }

  // TODO: KN right now it is catering to single columns only
  public Object getRoutingObject(RoutingObjectInfo rinfo,
      SingleHopInformation sinfo, boolean requiresSerializedHash) {
    assert rinfo instanceof AbstractRoutingObjectInfo;
    AbstractRoutingObjectInfo cRInfo = (AbstractRoutingObjectInfo)rinfo;
    int hashCode = cRInfo.computeHashCode(0, sinfo.getResolverByte(),
        requiresSerializedHash);

    return hashCode;
  }
}
