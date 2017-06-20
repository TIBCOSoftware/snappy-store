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

package com.gemstone.gemfire.internal.concurrent;

import java.util.AbstractMap;

/**
 * Represents a node in a skip list map. Now GemFireXD index keys implement this
 * directly so that those can be used as a node in the map themselves.
 * 
 * @author swale
 * @since gfxd 1.0
 */
public interface SkipListNode<K, V> {

  /**
   * Special value used to identify base-level header
   */
  public static final Object BASE_HEADER = new Object();

  /**
   * Returns the key contained in this node.
   */
  public K getMapKey();

  /**
   * Returns the raw value contained in this node (can be BASE_HEADER, for example).
   */
  public Object getMapValue();

  /**
   * Returns value if this node contains a valid key-value pair, else null.
   * 
   * @return this node's value if it isn't a marker or header or is deleted,
   *         else null.
   */
  public V getValidValue();

  /**
   * Returns the next node.
   */
  public SkipListNode<K, V> getNext();

  /**
   * Version of this node which is incremented on each update to its value. This
   * is used to determine if a node value has changed since the initial read
   * (e.g. while value is being read). The value returned this must be
   * monotonically increasing (and can rollover) and should represent the last
   * update instant which is <= the instantaneous update instant of a subsequent
   * get*Value methods. Implementations may choose to ignore this and return
   * some fixed value.
   */
  public int getVersion();

  /**
   * Set the next node. This should normally not be used since {@link #casNext}
   * will do the change atomically, but this method can be used for initial
   * population of data etc.
   */
  public void setNext(SkipListNode<K, V> next);

  /**
   * compareAndSet value field
   */
  public boolean casValue(Object cmp, Object val);

  /**
   * compareAndSet next field
   */
  public boolean casNext(SkipListNode<K, V> cmp, SkipListNode<K, V> val);

  /**
   * Returns true if this node is a marker. This method isn't actually called in
   * any current code checking for markers because callers will have already
   * read value field and need to use that read (not another done here) and so
   * directly test if value points to node.
   * 
   * @return true if this node is a marker node
   */
  public boolean isMarker();

  /**
   * Returns true if this node is the header of base-level list.
   * 
   * @return true if this node is header node
   */
  public boolean isBaseHeader();

  /**
   * Tries to append a deletion marker to this node.
   * 
   * @param f
   *          the assumed current successor of this node
   * @return true if successful
   */
  public boolean appendMarker(SkipListNode<K, V> f);

  /**
   * Helps out a deletion by appending marker or unlinking from predecessor.
   * This is called during traversals when value field seen to be null.
   * 
   * @param b
   *          predecessor
   * @param f
   *          successor
   */
  public void helpDelete(SkipListNode<K, V> b, SkipListNode<K, V> f);

  /**
   * Creates and returns a new SimpleImmutableEntry holding current mapping if
   * this node holds a valid value, else null.
   * 
   * @return new entry or null
   */
  public AbstractMap.SimpleImmutableEntry<K, V> createSnapshot();

  /**
   * Invoked when a node fails to get inserted in the map.
   */
  public void clear();
}
