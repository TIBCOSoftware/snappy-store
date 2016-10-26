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

package com.gemstone.gemfire.internal;

import java.io.DataInput;
import java.io.EOFException;
import java.io.IOException;

import com.gemstone.gemfire.internal.shared.Version;

/**
 * A reusable {@link DataInput} implementation that wraps a given byte array. It
 * also implements {@link VersionedDataStream} for a stream coming from a
 * different product version.
 * 
 * @author swale
 * @since 7.1
 */
public final class ByteArrayDataInput extends DataInputStreamBase implements
    VersionedDataStream {

  private byte[] bytes;
  private int nBytes;
  private Version version;

  /**
   * Create a {@link DataInput} whose contents are empty.
   */
  public ByteArrayDataInput() {
  }

  /**
   * Initialize this byte array stream with given byte array and version.
   * 
   * @param bytes
   *          the content of this stream. Note that this byte array will be read
   *          by this class (a copy is not made) so it should not be changed
   *          externally.
   * @param version
   *          the product version that serialized the object on given bytes
   */
  public final void initialize(byte[] bytes, Version version) {
    this.bytes = bytes;
    this.nBytes = bytes.length;
    this.pos = 0;
    this.version = version;
  }

  /**
   * Initialize this byte array stream with given byte array and version.
   * 
   * @param bytes
   *          the content of this stream. Note that this byte array will be read
   *          by this class (a copy is not made) so it should not be changed
   *          externally.
   * @param version
   *          the product version that serialized the object on given bytes
   */
  public final void initialize(byte[] bytes, int offset, int len,
      Version version) {
    if (offset < 0 || len < 0 || bytes.length < (offset + len)) {
      throw new IndexOutOfBoundsException();
    }

    this.bytes = bytes;
    this.nBytes = offset + len;
    this.pos = offset;
    this.version = version;
  }

  public byte[] array() {
    return this.bytes;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Version getVersion() {
    return this.version;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int read() throws IOException {
    if (this.pos < this.nBytes) {
      return (this.bytes[this.pos++] & 0xff);
    }
    else {
      return -1;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int read(byte[] b, int off, int len) {
    if (b == null) {
      throw new NullPointerException();
    }
    else if (off < 0 || len < 0 || b.length < (off + len)) {
      throw new IndexOutOfBoundsException();
    }

    final int capacity = (this.nBytes - this.pos);
    if (len > capacity) {
      if (capacity == 0 && len > 0) {
        // end-of-file reached
        return -1;
      }
      len = capacity;
    }
    if (len > 0) {
      System.arraycopy(this.bytes, this.pos, b, off, len);
      this.pos += len;
      return len;
    }
    else {
      return 0;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final long skip(long n) {
    return skipOver(n, this.nBytes);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int available() {
    return (this.nBytes - this.pos);
  }

  /**
   * Get the current position in the byte[].
   */
  public final int position() {
    return this.pos;
  }

  /**
   * Set the current position in the byte[].
   */
  public final void setPosition(int pos) {
    this.pos = pos;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void readFully(byte[] b) throws IOException {
    final int len = b.length;
    System.arraycopy(this.bytes, this.pos, b, 0, len);
    this.pos += len;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void readFully(byte[] b, int off, int len) throws IOException {
    if (len > 0) {
      if ((this.nBytes - this.pos) >= len) {
        System.arraycopy(this.bytes, this.pos, b, off, len);
        this.pos += len;
      }
      else {
        throw new EOFException();
      }
    }
    else if (len < 0) {
      throw new IndexOutOfBoundsException();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int skipBytes(int n) {
    return skipOver(n, this.nBytes);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean readBoolean() throws IOException {
    if (this.pos < this.nBytes) {
      return (this.bytes[this.pos++] != 0);
    }
    else {
      throw new EOFException();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final byte readByte() throws IOException {
    if (this.pos < this.nBytes) {
      return this.bytes[this.pos++];
    }
    else {
      throw new EOFException();
    }
  }

  public final byte peekByte() throws IOException {
    if (this.pos < this.nBytes) {
      return this.bytes[this.pos];
    }
    else {
      throw new EOFException();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int readUnsignedByte() throws IOException {
    if (this.pos < this.nBytes) {
      return (this.bytes[this.pos++] & 0xff);
    }
    else {
      throw new EOFException();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final short readShort() throws IOException {
    if ((this.pos + 1) < this.nBytes) {
      int result = (this.bytes[this.pos++] & 0xff);
      return (short)((result << 8) | (this.bytes[this.pos++] & 0xff));
    }
    else {
      throw new EOFException();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int readUnsignedShort() throws IOException {
    if ((this.pos + 1) < this.nBytes) {
      int result = (this.bytes[this.pos++] & 0xff);
      return ((result << 8) | (this.bytes[this.pos++] & 0xff));
    }
    else {
      throw new EOFException();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public char readChar() throws IOException {
    if ((this.pos + 1) < this.nBytes) {
      int result = this.bytes[this.pos++] << 8;
      return (char)(result | (this.bytes[this.pos++] & 0xff));
    }
    else {
      throw new EOFException();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readInt() throws IOException {
    if ((this.pos + 3) < this.nBytes) {
      int result = (this.bytes[this.pos++] & 0xff);
      result = (result << 8) | (this.bytes[this.pos++] & 0xff);
      result = (result << 8) | (this.bytes[this.pos++] & 0xff);
      return ((result << 8) | (this.bytes[this.pos++] & 0xff));
    }
    else {
      throw new EOFException();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final long readLong() throws IOException {
    if ((this.pos + 7) < this.nBytes) {
      long result = (this.bytes[this.pos++] & 0xff);
      result = (result << 8) | (this.bytes[this.pos++] & 0xff);
      result = (result << 8) | (this.bytes[this.pos++] & 0xff);
      result = (result << 8) | (this.bytes[this.pos++] & 0xff);
      result = (result << 8) | (this.bytes[this.pos++] & 0xff);
      result = (result << 8) | (this.bytes[this.pos++] & 0xff);
      result = (result << 8) | (this.bytes[this.pos++] & 0xff);
      return ((result << 8) | (this.bytes[this.pos++] & 0xff));
    }
    else {
      throw new EOFException();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final float readFloat() throws IOException {
    return Float.intBitsToFloat(readInt());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final double readDouble() throws IOException {
    return Double.longBitsToDouble(readLong());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final String readUTF() throws IOException {
    return super.readUTF(this.bytes, this.nBytes);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() {
    super.close();
    this.bytes = null;
    this.nBytes = 0;
    this.version = null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toString() {
    return this.version == null ? super.toString() : (super.toString() + " ("
        + this.version + ')');
  }
}
