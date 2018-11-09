/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package com.gemstone.gemfire.internal.shared;

import java.nio.ByteBuffer;

/**
 * Base class for explicit reference counted ByteBuffers.
 */
public abstract class ByteBufferReference {

  /**
   * Get the current reference count for this object.
   */
  public abstract int referenceCount();

  /**
   * Explicitly mark the buffer to be retained so it is not released until
   * a corresponding {@link #release()} has been invoked.
   *
   * @return True if the retain was on a valid buffer else false if the
   * underlying data has already been released (and will lead to empty writes).
   */
  public abstract boolean retain();

  /**
   * Return the data as a ByteBuffer after applying a {@link #retain()}.
   * Callers should preferably invoke {@link #release()} explicitly for eager
   * release of the buffer (if it is direct).
   */
  public abstract ByteBuffer getBufferRetain();

  /**
   * Return the data as a ByteBuffer. Should be invoked only after a
   * {@link #retain()} or {@link #getValueRetain} call.
   */
  public abstract ByteBuffer getBuffer();

  /**
   * Get a compressed/decompressed/original version of the underlying value
   * after a {@link #retain()}
   *
   * @param fetchRequest one of the {@link FetchRequest} values
   *
   * @return possibly transformed data as per @{@link FetchRequest}
   */
  public abstract ByteBufferReference getValueRetain(FetchRequest fetchRequest);

  /**
   * An optional explicit release of the underlying data. The buffer may no
   * longer be usable after this call and return empty data.
   * <p>
   * NOTE: Implementations should <b>never</b> require this call to be invoked
   * (along with {@link #retain()} and not lead to memory leaks if skipped.
   * Typically this means using NIO DirectByteBuffers for data which will
   * release automatically in the GC cycles when no references remain.
   */
  public abstract void release();

  /**
   * Returns true if explicit calls to {@link #release()} are required for this
   * object to be eagerly released else they can be skipped (e.g. heap buffers)
   */
  public abstract boolean needsRelease();

  /**
   * Size of the underlying data. If there are some callers who are doing
   * explicit retain/release calls, then callers of this should also use
   * the same consistently to ensure data does not get released prematurely.
   */
  public abstract int size();
}
