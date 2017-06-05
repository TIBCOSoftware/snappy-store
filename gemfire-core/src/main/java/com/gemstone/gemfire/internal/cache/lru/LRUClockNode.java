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

package com.gemstone.gemfire.internal.cache.lru;

import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;

public interface LRUClockNode {

  public void setNextLRUNode( LRUClockNode next );
  public void setPrevLRUNode( LRUClockNode prev );
  
  public LRUClockNode nextLRUNode();
  public LRUClockNode prevLRUNode();
  
  /** compute the new entry size and return the delta from the previous entry size */
  public int updateEntrySize(EnableLRU ccHelper);
  /** compute the new entry size and return the delta from the previous entry size
   * @param value then entry's value
   */
  public int updateEntrySize(EnableLRU ccHelper, Object value);
  
  public int getEntrySize();
  
  public boolean testRecentlyUsed();
  public void setRecentlyUsed();
  public void unsetRecentlyUsed();

  public void setEvicted();
  public void unsetEvicted();
  public boolean testEvicted();

  /**
   * Returns the current value of synchronization state. This operation has
   * memory semantics of a <tt>volatile</tt> read.
   * 
   * Use the static methods in {@link ExclusiveSharedSynchronizer} like
   * {@link ExclusiveSharedSynchronizer#getLockModeFromState(int)}
   * {@link ExclusiveSharedSynchronizer#isExclusive(int)} to check for various
   * lock settings on this object.
   * 
   * @return current synchronization state value
   */
  int getState();

  /* no longer used in the new TX impl
  /**
   * Returns the number of transactions that are currently referencing
   * this node.
   *
  public int getRefCount();
  /**
   * Increments the number of transactions that are currently referencing
   * this node.
   *
  public void incRefCount();
  /**
   * Decrements the number of transactions that are currently referencing
   * this node.
   *
  public void decRefCount(NewLRUClockHand lruList);
  /** 
   * Clear the number of transactions that are currently referencing this node
   * and returns to LRU list
   *
  public void resetRefCount(NewLRUClockHand lruList);
  */

  /**
   * Is the in-memory value for this node is null
   * @return true or false
   */
  public boolean isValueNull();
}
