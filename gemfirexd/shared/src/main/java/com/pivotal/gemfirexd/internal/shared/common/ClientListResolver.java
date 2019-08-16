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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

import java.util.Map;

/**
 * 
 * @author kneeraj
 * 
 */
public class ClientListResolver extends AbstractClientResolver {

  private int typeId;

  private Map mapOfListValues;

  public ClientListResolver(int type, Map mapOfListValues) {
    this.typeId = type;
    this.mapOfListValues = mapOfListValues;
  }

  public Object getRoutingObject(RoutingObjectInfo rinfo,
      SingleHopInformation sinfo, boolean requiresSerializedHash) {
    assert rinfo instanceof ColumnRoutingObjectInfo;
    ColumnRoutingObjectInfo cRInfo = (ColumnRoutingObjectInfo)rinfo;

    Object value = cRInfo.getActualValue();

    if (value != null) {
      Object robj = this.mapOfListValues.get(value);
      if (robj != null) {
        return robj;
      }
    }
    return cRInfo.computeHashCode(0, sinfo.getResolverByte(), false);
  }
}
