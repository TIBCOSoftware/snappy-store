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
package com.pivotal.gemfirexd.internal.iapi.types;

import java.io.Serializable;

import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXId;
import com.pivotal.gemfirexd.internal.engine.access.index.AbstractRowLocation;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.entry.GfxdTXEntryState;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;

/**
 * Keep below class as final since some code depends on getClass() to match.
 * 
 * @author kneeraj
 */
public final class WrapperRowLocationForTxn extends AbstractRowLocation {

  private final GfxdTXEntryState actualRowLocation;

  private Object indexKey;

  private GemFireContainer indexContainer;

  private final boolean createdForUpdateOperation;
  
  public WrapperRowLocationForTxn(GfxdTXEntryState rowLocationToBeWrapped,
      Object key, boolean forUpdateOp) {
    this.actualRowLocation = rowLocationToBeWrapped;
    this.indexKey = key;
    this.createdForUpdateOperation = forUpdateOp;
  }


  @Override
  public boolean equals(Object obj) {
    if (obj ==  this) {
      return true;
    } else if (obj instanceof WrapperRowLocationForTxn && this.actualRowLocation != null
        && ((WrapperRowLocationForTxn)obj).actualRowLocation != null) {
      WrapperRowLocationForTxn that = (WrapperRowLocationForTxn) obj;
      return  this.actualRowLocation.equals(that.actualRowLocation);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    int h = 17;
   // h = h * 37 + this.indexContainer.hashCode();
   // h = h * 37 + this.indexKey.hashCode();
    h = h * 37 + (this.actualRowLocation != null ? this.actualRowLocation.hashCode() : super.hashCode());
    return h;
  }

  public boolean wasCreatedForUpdateOp() {
    return this.createdForUpdateOperation;
  }
  
  @Override
  public Object getKey() {
    return this.actualRowLocation.getKey();
  }

  @Override
  public Object getKeyCopy() {
    return this.actualRowLocation.getKeyCopy();
  }

  public GfxdTXEntryState getWrappedRowLocation() {
    return this.actualRowLocation;
  }

  @Override
  public int getBucketID() {
    return this.actualRowLocation.getBucketID();
  }

  @Override
  public RegionEntry getRegionEntry() {
    return this.actualRowLocation.getUnderlyingRegionEntry();
  }

  @Override
  public ExtraTableInfo getTableInfo(GemFireContainer baseContainer) {
    return this.actualRowLocation.getTableInfo(baseContainer);
  }

  @Override
  public Object getValue(GemFireContainer baseContainer) {
    return this.actualRowLocation.getValue(baseContainer);
  }

  @Override
  public Object getValueWithoutFaultIn(GemFireContainer baseContainer) {
    return this.actualRowLocation.getValueWithoutFaultIn(baseContainer);
  }

  @Override
  public ExecRow getRow(GemFireContainer baseContainer)
      throws StandardException {
    return this.actualRowLocation.getRow(baseContainer);
  }

  @Override
  public ExecRow getRowWithoutFaultIn(GemFireContainer baseContainer)
      throws StandardException {
    return this.actualRowLocation.getRowWithoutFaultIn(baseContainer);
  }

  @Override
  public boolean isDestroyedOrRemoved() {
    return this.actualRowLocation.isDestroyedOrRemoved();
  }

  @Override
  public boolean isUpdateInProgress() {
    return this.actualRowLocation.isUpdateInProgress();
  }

  @Override
  public TXId getTXId() {
    return this.actualRowLocation.getTXId();
  }

  @Override
  public void checkHostVariable(int declaredLength) throws StandardException {
    this.actualRowLocation.checkHostVariable(declaredLength);
  }

  @Override
  public DataValueDescriptor coalesce(DataValueDescriptor[] list,
      DataValueDescriptor returnValue) throws StandardException {
    return this.actualRowLocation.coalesce(list, returnValue);
  }

  @Override
  public DataValueDescriptor getNewNull() {
    throw new AssertionError("not expected to be invoked");
  }

  @Override
  public Serializable getRoutingObject() {
    throw new AssertionError("not expected to be invoked");
  }

  public void setIndexContainer(GemFireContainer indexContainer) {
    this.indexContainer = indexContainer;
  }

  public GfxdTXEntryState needsToBeConsideredForSameTransaction() {
    if (this.indexContainer != null) {
      assert this.indexKey != null: "WrapperRowLocation: if index container "
          + "is non null then index key has to be non null";
      return this.actualRowLocation;
    }
    return null;
  }

  public GemFireContainer getIndexContainer() {
    return this.indexContainer;
  }

  public void setIndexKey(Object indexKey) {
    this.indexKey = indexKey;
  }

  public Object getIndexKey() {
    return this.indexKey;
  }

  @Override
  public String toString() {
    return "[WrapperRowLocationForTxn@0x"
        + Integer.toHexString(System.identityHashCode(this))
        + ";actualRowLocation=" + this.actualRowLocation + ";indexKey="
        + this.indexKey + ']';
  }

  @Override
  public void markDeleteFromIndexInProgress() {
    //NOOP
    
  }

  @Override
  public void unmarkDeleteFromIndexInProgress() {
  //NOOP
    
  }

  @Override
  public boolean useRowLocationForIndexKey() {
    
    return true;
  }

  @Override
  public void endIndexKeyUpdate() {
  //NOOP
    
  }
}
