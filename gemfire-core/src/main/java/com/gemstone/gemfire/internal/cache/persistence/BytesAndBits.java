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
package com.gemstone.gemfire.internal.cache.persistence;

import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.store.SerializedDiskBuffer;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.unsafe.DirectBufferAllocator;
import com.gemstone.gemfire.internal.shared.Version;

/**
 * Used to fetch a record's raw bytes and user bits.
 *
 * @author Darrel Schneider
 * @since prPersistSprint1
 */
public class BytesAndBits {
  private ByteBuffer data;
  private final byte userBits;
  private Version version;

  public BytesAndBits(ByteBuffer data, byte userBits) {
    // expect buffer position to be at the start to avoid complications
    // in dealing with non-zero position everywhere
    if (data.position() != 0) {
      throw new IllegalStateException(
          "Expected buffer position to be 0 but is = " + data.position());
    }
    this.data = data;
    this.userBits = userBits;
  }

  public final byte getBits() {
    return this.userBits;
  }

  public void setVersion(Version v) {
    this.version = v;
  }

  public Version getVersion() {
    return this.version;
  }

  public int size() {
    return this.data.limit();
  }

  public Object deserialize() {
    if (this.data.limit() == 0) {
      throw new IllegalStateException(
          "Unexpected invocation to deserialize empty/token buffer");
    }
    Object result = EntryEventImpl.deserializeBuffer(this.data, this.version);
    // if result is a SerializedDiskBuffer then ownership of data has been lost
    if (result instanceof SerializedDiskBuffer) {
      this.data = null;
    } else {
      // rewind back to original position for further reads if required
      this.data.rewind();
    }
    return result;
  }

  public byte[] toBytes() {
    return ClientSharedUtils.toBytes(this.data);
  }

  public void release() {
    final ByteBuffer data = this.data;
    if (data != null && data.isDirect()) {
      this.data = null;
      DirectBufferAllocator.instance().release(data);
    }
  }

  @Override
  public String toString() {
    return ClientSharedUtils.toString(this.data);
  }
}
