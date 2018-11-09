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

import java.util.HashSet;
import java.util.Set;

public abstract class AbstractClientResolver implements ClientResolver {

  protected int numberOfBuckets;
  private Set testSet;

  public void setNumBuckets(int noOfBuckets) {
    this.numberOfBuckets = noOfBuckets;
  }

  public void setTestSet(Set routingObjectSet) {
    this.testSet = routingObjectSet;
  }

  public HashSet getListOfRoutingObjects(AbstractRoutingObjectInfo rInfo,
      SingleHopInformation singleHopInformation) {
    assert rInfo instanceof ListRoutingObjectInfo;
    ColumnRoutingObjectInfo[] cinfos = ((ListRoutingObjectInfo)rInfo).getListOfInfos();
    HashSet<Integer> routingObjects = new HashSet<>();
    int len = cinfos.length;
    for (int i = 0; i < len; i++) {
      Integer robj = (Integer)this.getRoutingObject(cinfos[i],
          singleHopInformation, false);
      if (robj == null) {
        return null;
      }
      if (this.testSet != null) {
        this.testSet.add(robj);
      }
      int bid = Math.abs(robj % this.numberOfBuckets);
      routingObjects.add(bid);
    }
    return routingObjects;
  }
}
