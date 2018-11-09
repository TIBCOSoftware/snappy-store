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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;

import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils.GfxdRange;
import com.pivotal.gemfirexd.internal.shared.common.SingleHopInformation.PlainRangeValueHolder;

/**
 * 
 * @author kneeraj
 * 
 */
public class ClientRangeResolver extends AbstractClientResolver {

  private int typeId;

  private ArrayList rangeValueHolderList;

  protected SortedMap rangeMap;

  public ClientRangeResolver(int type, ArrayList rangeValueHolderList) {
    this.typeId = type;
    this.rangeValueHolderList = rangeValueHolderList;
    initRangeMap();
  }

  private void initRangeMap() {
    Iterator itr = this.rangeValueHolderList.iterator();
    this.rangeMap = new TreeMap(new ResolverUtils.GfxdRangeComparator(
        "PARTITION BY RANGE"));
    while (itr.hasNext()) {
      PlainRangeValueHolder prvh = (PlainRangeValueHolder)itr.next();
      Object lowerBound = prvh.getLowerBound();
      Object upperBound = prvh.getUpperBound();
      Integer routingObject = prvh.getRoutingVal();
      GfxdRange range = new GfxdRange(null, lowerBound, upperBound);
      Object[] value = new Object[2];
      value[0] = range;
      value[1] = routingObject;
      this.rangeMap.put(range, value);
    }
  }

  public Object getRoutingObject(RoutingObjectInfo rinfo,
      SingleHopInformation sinfo, boolean requiresSerializedHash) {
    assert rinfo instanceof ColumnRoutingObjectInfo;
    ColumnRoutingObjectInfo cRInfo = (ColumnRoutingObjectInfo)rinfo;

    Object value = cRInfo.getActualValue();

    if (value != null) {
      final Object[] routingKey = (Object[])this.rangeMap
          .get(new ResolverUtils.GfxdComparableFuzzy((Comparable)value,
              ResolverUtils.GfxdComparableFuzzy.GE));
      if (routingKey != null && routingKey[1] != null) {
        return routingKey[1];
      }
    }

    return cRInfo.computeHashCode(0, sinfo.getResolverByte(), false);
  }
}
