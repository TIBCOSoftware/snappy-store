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
package com.gemstone.gemfire.internal.cache;

import com.gemstone.gemfire.cache.hdfs.internal.AbstractBucketRegionQueue;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventImpl;
import com.gemstone.gemfire.internal.cache.wan.serial.SerialGatewaySenderQueue;

/**
 * 
 * @author sbawaska
 *
 */
@SuppressWarnings("serial")
public abstract class AbstractDiskRegionEntry
  extends AbstractRegionEntry
  implements DiskEntry
{
  protected AbstractDiskRegionEntry(RegionEntryContext context, Object value) {
    super(context, (value instanceof RecoveredEntry ? null : value));
  }

  /////////////////////////////////////////////////////////////
  /////////////////////////// fields //////////////////////////
  /////////////////////////////////////////////////////////////
  // Do not add any instance fields to this class.
  // Instead add them to the DISK section of LeafRegionEntry.cpp
  
   protected abstract void initialize(RegionEntryContext context, Object value);

  @Override
  public  void setValue(RegionEntryContext context, Object v) throws RegionClearedException {
    Helper.update(this, (LocalRegion) context, v);
    setRecentlyUsed(); // fix for bug #42284 - entry just put into the cache is evicted
    initDiskIdForOffHeap(context, v);
  }

  /**
   * Sets the value with a {@link RegionEntryContext}.
   * @param context the value's context.
   * @param value an entry value.
   */
  @Override
  public void setValueWithContext(RegionEntryContext context, Object value) {
    _setValue(context, value);
    initDiskIdForOffHeap(context, value);
    if (value != null && context != null && context instanceof LocalRegion
        && ((LocalRegion)context).isThisRegionBeingClosedOrDestroyed()
        && isOffHeap()) {
      release();
      ((LocalRegion)context).checkReadiness();
    }
  }

  /**
   * Set the RegionEntry DiskId into SerializedDiskBuffer value, if present,
   * so that the value can access data from disk when required independently.
   */
  protected abstract void initDiskIdForOffHeap(RegionEntryContext context,
      Object value);

  @Override
  public void handleValueOverflow(RegionEntryContext context) {
    if (context instanceof AbstractBucketRegionQueue || context instanceof SerialGatewaySenderQueue.SerialGatewaySenderQueueMetaRegion) {
      GatewaySenderEventImpl.release(this._getValue());
    }else if(context instanceof LocalRegion) {
      LocalRegion lr = (LocalRegion)context;
      //DiskRegion dr = ((LocalRegion)context).getDiskRegion();
     // if(!dr.isSync()) {
        IndexUpdater indxUpdater = lr.getIndexUpdater();
        if(indxUpdater != null) {
          indxUpdater.onOverflowToDisk(this);
        }
      //}
    }
    // code from xd subclasses:
    /*
    this.markDeleteFromIndexInProgress();
    if (context instanceof LocalRegion) {
      DiskRegion dr = ((LocalRegion) context).getDiskRegion();
      if (!dr.isSync()) {        
          GfxdIndexManager indxUpdater = (GfxdIndexManager) ((LocalRegion)context).getIndexUpdater();
          if(indxUpdater != null) {
            indxUpdater.lockForIndexGII(false, null);
          }
      }
    }    
    super.handleValueOverflow(context);
    */
   }
  @Override
  public void afterValueOverflow(RegionEntryContext context) {
    //NO OP
    //Overridden in gfxd RegionEntry
    /*
    this.unmarkDeleteFromIndexInProgress();    
    if (context instanceof LocalRegion) {
      DiskRegion dr = ((LocalRegion) context).getDiskRegion();
      if (!dr.isSync()) {
        GfxdIndexManager indxUpdater = (GfxdIndexManager) ((LocalRegion)context).getIndexUpdater();
        if(indxUpdater != null) {
          indxUpdater.unlockForIndexGII(false, null);
        }
      }
    }
    */
  }
}
