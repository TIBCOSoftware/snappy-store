/*


   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.RowLocation

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
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

package com.pivotal.gemfirexd.internal.iapi.types;

// GemStone changes BEGIN
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXEntryId;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
// GemStone changes END
/**

  Holds the location of a row within a given conglomerate.
  A row location is not valid except in the conglomerate
  from which it was obtained.  They are used to identify
  rows for fetches, deletes, and updates through a 
  conglomerate controller.
  <p>
  See the conglomerate implementation specification for
  information about the conditions under which a row location
  remains valid.

**/

public interface RowLocation extends DataValueDescriptor, CloneableObject,
    TXEntryId {
// GemStone changes BEGIN

  /**
   * Get the underlying key object in the GemFire region for this
   * {@link RowLocation}.
   */
  public Object getKey();

  /**
   * Get a copy of the underlying key object in the GemFire region for this
   * {@link RowLocation} in case the region key is a RegionEntry itself (which
   * is the case for GemFireXD entries).
   */
  public Object getKeyCopy();

  /**
   * Return the raw underlying key object (rather than a wrapper like
   * CompactCompositeKey or RegionEntry).
   */
  public Object getRawKey();

  /**
   * If this RowLocation points to an entry in a partitioned table, then return
   * the bucket ID of the bucket where the entry lives.
   */
  public int getBucketID();

  // Asif: attempt to remove RegionEntry altogether from GemFireXD.
  // Right now not doing because of GlobalExecRowLocation.
  // [sumedh] I think it is important to have RegionEntry for efficiency
  // and low-level operations, so does not seem possible to remove it.
  // Moreover, GemFireXD RegionEntries now themselves implement RowLocation,
  // so does not make sense to remove this. Alternative can be to have
  // RowLocation extend RegionEntry, but there are some RowLocations that
  // should not implement RegionEntry themselves (GlobalRowLocation,
  // DummyRowLocation).
  /**
   * Return a {@link RegionEntry} for current RowLocation that will allow
   * reading key or value.
   */
  public RegionEntry getRegionEntry();

  /**
   * Return the underlying {@link RegionEntry} for current RowLocation in the
   * Region that will allow reading key or value. The difference from
   * {@link #getRegionEntry()} is that for transactional entries this will still
   * return the region's RegionEntry while former can return the transactional
   * entry itself if part of current transaction.
   */
  public RegionEntry getUnderlyingRegionEntry();

  /**
   * Return the raw value for the location (reading from disk faulting it into
   * memory LRU list if required), or null if not found.
   * 
   * @param container
   *          the container to get the row
   */
  @Retained
  public Object getValue(GemFireContainer baseContainer)
      throws StandardException;

  /**
   * Return the raw value for the location (reading from disk without fault-in
   * if required), or null if not found.
   * 
   * @param container
   *          the container to get the row
   */
  @Retained
  public Object getValueWithoutFaultIn(GemFireContainer baseContainer) throws StandardException;
      
  @Retained
  public Object getValueWithoutFaultInOrOffHeapEntry(LocalRegion owner)   ;
  
  
  public Object getValueOrOffHeapEntry(LocalRegion owner)   ;
  
  /**
   * Return the ExecRow (reading from disk faulting it into memory LRU list if
   * required), or null if not found.
   * 
   * @param baseContainer
   *          the container of this row
   */
  public ExecRow getRow(GemFireContainer baseContainer)
      throws StandardException;

  /**
   * Return the ExecRow (reading from disk without fault-in if required), or
   * null if not found.
   * 
   * @param baseContainer
   *          the container of this row
   */
  public ExecRow getRowWithoutFaultIn(GemFireContainer baseContainer)
      throws StandardException;

  /**
   * Return the ExtraTableInfo for the table of this row or null if not stored
   * as byte array.
   * 
   * @param baseContainer
   *          the container of this row
   */
  public ExtraTableInfo getTableInfo(GemFireContainer baseContainer);

  /**
   * Returns true if the underlying entry has been removed from table.
   */
  public boolean isDestroyedOrRemoved();

  /**
   * RowLocation is underUpdate as soon as lock is held by an update thread to
   * put a new value for an existing key. This is used during query on the
   * region to verify index entry with current RowLocation.
   * 
   * @return true if RowLocation is under update during cache put operation.
   */
  public boolean isUpdateInProgress();

  /**
   * 
   * @return Returns the raw value ( bye[], byte[][], DVD as the case may be). For OffHeapEntries 
   * it returns  the StoredObject ( Chunk ) or byte[] , increasing the use count by 1.
   */
  public Object getRawValue();

  public boolean isValueNull();

  public void markDeleteFromIndexInProgress();
  public void unmarkDeleteFromIndexInProgress();
  public boolean useRowLocationForIndexKey();
  public void endIndexKeyUpdate();

// GemStone changes END
}
