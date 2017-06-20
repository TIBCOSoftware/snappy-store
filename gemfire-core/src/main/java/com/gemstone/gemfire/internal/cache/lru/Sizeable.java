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

import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;

/**
 * An interface that allows an object to define its own size.<br>
 *
 *<b>Sample Implementation</b><br>
 *<code>public int getSizeInBytes(){</code><br>
    // The sizes of the primitive as well as object instance variables are calculated:<br>
    
    <code>int size = 0;</code><br>
    
    // Add overhead for this instance.<br>
    <code>size += Sizeable.PER_OBJECT_OVERHEAD;</code><br>

    // Add object references (implements Sizeable)<br>
    // value reference = 4 bytes <br>
    
    <code>size += 4;</code><br>
    
    // Add primitive instance variable size<br>
    // byte bytePr = 1 byte<br>
    // boolean flag = 1 byte<br>
    
    <code>size += 2;</code><br>
    
    // Add individual object size<br> 
    <code>size += (value.getSizeInBytes());</code><br> 
     
    <code>return size;</code><br>
  }<br>
 *
 * @author David Whitlock
 *
 * @since 3.2
 */
public interface Sizeable {

  /**
   * The overhead of an object in the VM in bytes
   */
  int PER_OBJECT_OVERHEAD = Math.max(8, ReflectionSingleObjectSizer.OBJECT_SIZE);

  /**
   * Returns the size (in bytes) of this object including the {@link
   * #PER_OBJECT_OVERHEAD}.
   */
  int getSizeInBytes();
}
