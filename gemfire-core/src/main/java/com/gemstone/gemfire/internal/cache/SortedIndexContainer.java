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

import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;

import com.gemstone.gemfire.cache.query.IndexMaintenanceException;

/**
 * An interface to describe a container for a local index that can be persisted
 * to and recovered from an Oplog.
 * 
 * @author swale
 * @since gfxd 1.0
 */
public interface SortedIndexContainer {

  /** Get the unique system-wide ID of the container */
  public String getUUID();

  /** Get the comparator to be used for sorting the index keys. */
  public Comparator<? super SortedIndexKey> getComparator();

  /** Get the base region that is indexed. */
  public LocalRegion getBaseRegion();

  /** Returns true if this is a unique index and false otherwise */
  public boolean isUniqueIndex();

  /**
   * Get an index key object that can be inserted into this index given the
   * serialized index key bytes and reference to disk entry.
   */
  public SortedIndexKey getIndexKey(byte[] indexKeyBytes, RegionEntry entry);

  /**
   * Get an index key object that can be inserted into this index given the
   * full value extracted from a RegionEntry, and reference to the RegionEntry.
   */
  public SortedIndexKey getIndexKey(Object val, RegionEntry entry);

  /**
   * Initialize the index with given set of sorted entries. The will fail if
   * there is any previous history of operations on the index. The given
   * iterator is required to provide key, value pairs in sorted order as would
   * be required by this index's comparator.
   */
  public void buildIndexFromSorted(
      Iterator<Map.Entry<SortedIndexKey, Object>> entryIterator);

  /**
   * Merge two values for same index key into the first key. The result value
   * can be gotten from {@link SortedIndexKey#getTransientValue()} method of the
   * first argument.
   * 
   * @throws IndexMaintenanceException
   *           if the index only allows unique values
   */
  public void mergeValuesForNonUnique(SortedIndexKey mergeInto,
      SortedIndexKey mergeFrom) throws IndexMaintenanceException;

  /**
   * New API added to account Index memory while index is getting loaded
   * @param cursorPosition for which we are trying to estimate memory
   * @param forceAccount whether to account irrespective of cursor position
   */
  public void accountMemoryForIndex(long cursorPosition, boolean forceAccount);
}
