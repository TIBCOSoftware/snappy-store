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


import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.shared.Version;

/**
 * Abstract implementation class of RegionEntry interface.
 * This is adds Disk support behaviour
 *
 * @since 3.5.1
 *
 * @author Darrel Schneider
 *
 */
@SuppressWarnings("serial")
public abstract class AbstractOplogDiskRegionEntry
  extends AbstractDiskRegionEntry
{
  protected AbstractOplogDiskRegionEntry(RegionEntryContext context, Object value) {
    super(context, value);
  }

  /////////////////////////////////////////////////////////////
  /////////////////////////// fields //////////////////////////
  /////////////////////////////////////////////////////////////
  // Do not add any instance fields to this class.
  // Instead add them to the DISK section of LeafRegionEntry.cpp
  
  /////////////////////////////////////////////////////////////////////
  ////////////////////////// instance methods /////////////////////////
  /////////////////////////////////////////////////////////////////////

  protected abstract void setDiskId(RegionEntry oldRe);

  public final void setDiskIdForRegion(RegionEntry oldRe) {
    setDiskId(oldRe);
    if (GemFireCacheImpl.hasNewOffHeap()) {
      initDiskIdForOffHeap(null, getValueField());
    }
  }

  @Override
  public final void removePhase1(LocalRegion r, boolean isClear) throws RegionClearedException
  {
    synchronized (this) {
      Helper.removeFromDisk(this, r, isClear);
      _removePhase1(r);
    }
  }
  @Override
  public void removePhase2(LocalRegion r) {
    Object syncObj = getDiskId();
    if (syncObj == null) {
      syncObj = this;
    }
    synchronized (syncObj) {
      super.removePhase2(r);
    }
  }

  @Override
  public final boolean fillInValue(LocalRegion r,
      InitialImageOperation.Entry entry, DM mgr, Version targetVersion) {
    return Helper.fillInValue(this, entry, r, mgr, r, targetVersion);
  }

  @Override
  public final boolean isOverflowedToDisk(LocalRegion r,
      DistributedRegion.DiskPosition dp, boolean alwaysFetchPosition) {
    return Helper.isOverflowedToDisk(this, r.getDiskRegion(),
        dp, alwaysFetchPosition);
  }
  
  @Retained
  @Override
  public final Object getValue(RegionEntryContext context) {   
    return Helper.faultInValue(this, (LocalRegion) context);  // OFFHEAP returned to callers
  }
  
  @Override
  public final Object getValueInVMOrDiskWithoutFaultIn(LocalRegion owner) {
    return Helper.getValueOffHeapOrDiskWithoutFaultIn(this, owner, true);
  }
  @Retained
  @Override
  public Object getValueOffHeapOrDiskWithoutFaultIn(LocalRegion owner) {
    return Helper.getValueOffHeapOrDiskWithoutFaultIn(this, owner, false);
  }

  @Retained
  @Override
  public Object getValueOffHeapOrDiskWithoutFaultIn(LocalRegion owner, DiskRegion dr) {
    return Helper.getValueOffHeapOrDiskWithoutFaultIn(this, owner, dr, false);
  }

  @Override
  public final Object getValueOnDisk(LocalRegion r)
  throws EntryNotFoundException
  {
    return Helper.getValueOnDisk(this, r.getDiskRegion());
  }

  @Override
  public final Object getSerializedValueOnDisk(LocalRegion r)
    throws EntryNotFoundException
  {
    return Helper.getSerializedValueOnDisk(this, r.getDiskRegion());
  }

  @Override
  public final Object getValueOnDiskOrBuffer(LocalRegion r)
    throws EntryNotFoundException
  {
    // @todo darrel if value is Token.REMOVED || Token.DESTROYED throw
    // EntryNotFoundException
    return Helper.getValueOnDiskOrBuffer(this, r.getDiskRegion(), r);
  }

  
  public DiskEntry getPrev() {
    return getDiskId().getPrev();
  }
  public DiskEntry getNext() {
    return getDiskId().getNext();
  }
  public void setPrev(DiskEntry v) {
    getDiskId().setPrev(v);
  }
  public void setNext(DiskEntry v) {
    getDiskId().setNext(v);
  }

  /* 
   * If detected a conflict event, persist region needs to persist both the
   * golden copy and conflict tag
   */
  @Override
  public void persistConflictingTag(LocalRegion region, VersionTag tag) {
    // only persist region needs to persist conflict tag
    Helper.updateVersionOnly(this, region, tag);
    setRecentlyUsed();
  }
  
  /**
   * Process a version tag. This overrides AbtractRegionEntry so
   * we can check to see if the old value was recovered from disk.
   * If so, we don't check for conflicts.
   */
  @Override
  public void processVersionTag(EntryEvent cacheEvent) {
    DiskId did = getDiskId();
    boolean checkConflicts = true;
    if(did != null) {
      LocalRegion lr = (LocalRegion)cacheEvent.getRegion();
      if (lr != null && lr.getDiskRegion().isReadyForRecovery()) {
        synchronized(did) {
          checkConflicts = !EntryBits.isRecoveredFromDisk(did.getUserBits());
        }
      }
    }
    
    processVersionTag(cacheEvent, checkConflicts);
  }

  /**
   * Returns true if the DiskEntry value is equal to {@link Token#DESTROYED}, {@link Token#REMOVED_PHASE1}, or {@link Token#REMOVED_PHASE2}.
   */
  @Override
  public boolean isRemovedFromDisk() {
    return Token.isRemovedFromDisk(getValueAsToken());
  }
}
