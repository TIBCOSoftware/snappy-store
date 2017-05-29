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

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.io.DataOutput;
import java.io.UTFDataFormatException;
import javax.annotation.Nonnull;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.unsafe.ChannelBufferUnsafeDataOutputStream;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import org.apache.spark.unsafe.Platform;

/**
 * An expanding byte[] based DataOutputStream. Best used as intermediate buffer
 * instead of <code>DataOutputStream</code> over a channel or something and if
 * this can be reused.
 * 
 * @author swale
 * @since gfxd 1.4
 */
public final class ByteArrayDataOutput extends ByteArrayOutput implements
    DataOutput {

  // to include this class in gemfirexd.jar
  static void init() {
  }

  public ByteArrayDataOutput() {
    super();
  }

  public ByteArrayDataOutput(final int allocSize) {
    super(allocSize);
  }

  @Override
  public final void writeBoolean(final boolean v) {
    super.write(v ? 1 : 0);
  }

  @Override
  public final void writeByte(final int v) {
    super.write(v);
  }

  @Override
  public final void writeShort(final int v) {
    int pos = ensureCapacity(2, this.bufferPos);

    final byte[] buffer = this.buffer;
    buffer[pos++] = (byte)((v >>> 8) & 0xff);
    buffer[pos++] = (byte)(v & 0xff);
    this.bufferPos = pos;
  }

  @Override
  public final void writeChar(final int v) {
    writeShort(v);
  }

  @Override
  public final void writeInt(final int v) {
    int pos = ensureCapacity(4, this.bufferPos);

    final byte[] buffer = this.buffer;
    buffer[pos++] = (byte)((v >>> 24) & 0xff);
    buffer[pos++] = (byte)((v >>> 16) & 0xff);
    buffer[pos++] = (byte)((v >>> 8) & 0xff);
    buffer[pos++] = (byte)(v & 0xff);
    this.bufferPos = pos;
  }

  @Override
  public final void writeLong(final long v) {
    int pos = ensureCapacity(8, this.bufferPos);

    final byte[] buffer = this.buffer;
    buffer[pos++] = (byte)((v >>> 56) & 0xff);
    buffer[pos++] = (byte)((v >>> 48) & 0xff);
    buffer[pos++] = (byte)((v >>> 40) & 0xff);
    buffer[pos++] = (byte)((v >>> 32) & 0xff);
    buffer[pos++] = (byte)((v >>> 24) & 0xff);
    buffer[pos++] = (byte)((v >>> 16) & 0xff);
    buffer[pos++] = (byte)((v >>> 8) & 0xff);
    buffer[pos++] = (byte)(v & 0xff);
    this.bufferPos = pos;
  }

  @Override
  public final void writeFloat(final float v) {
    writeInt(Float.floatToIntBits(v));
  }

  @Override
  public final void writeDouble(final double v) {
    writeLong(Double.doubleToLongBits(v));
  }

  /** assumes strlen == str.length */
  final void writeBytes(final char[] str, final int strlen) {
    int pos = ensureCapacity(strlen, this.bufferPos);

    final byte[] buffer = this.buffer;
    for (char c : str) {
      buffer[pos++] = (byte)c;
    }
    this.bufferPos = pos;
  }

  public final void writeBytes(final char[] str, int offset, final int end) {
    int pos = ensureCapacity(end - offset, this.bufferPos);

    final byte[] buffer = this.buffer;
    while (offset < end) {
      buffer[pos++] = (byte)str[offset++];
    }
    this.bufferPos = pos;
  }

  public final void writeBytes(final String str, final int strlen) {
    final char[] chars = ResolverUtils.getInternalCharsOnly(str, strlen);
    if (chars != null) {
      writeBytes(chars, strlen);
      return;
    }

    int pos = ensureCapacity(strlen, this.bufferPos);

    final byte[] buffer = this.buffer;
    for (int i = 0; i < strlen; i++) {
      buffer[pos++] = (byte)str.charAt(i);
    }
    this.bufferPos = pos;
  }

  @Override
  public final void writeBytes(@Nonnull final String str) {
    writeBytes(str, str.length());
  }

  @Override
  public final void writeChars(@Nonnull final String str) {
    final int strlen = str.length();
    int pos = ensureCapacity((strlen << 1), this.bufferPos);

    final byte[] buffer = this.buffer;
    for (int i = 0; i < strlen; i++) {
      final int c = str.charAt(i);
      buffer[pos++] = (byte)((c >>> 8) & 0xff);
      buffer[pos++] = (byte)(c & 0xff);
    }
    this.bufferPos = pos;
  }

  @Override
  public final void writeUTF(@Nonnull final String str)
      throws UTFDataFormatException {
    final int strLen = str.length();
    final int utfLen = ClientSharedUtils.getUTFLength(str, strLen);
    if (utfLen <= 65535) {
      ensureCapacity(2 + utfLen, this.bufferPos);
      writeShort(utfLen);
      ChannelBufferUnsafeDataOutputStream.writeUTFSegmentNoOverflow(str, 0,
          strLen, utfLen, this.buffer, Platform.BYTE_ARRAY_OFFSET + bufferPos);
      this.bufferPos += utfLen;
    } else {
      throw new UTFDataFormatException("ByteArrayDataOutput#writeUTF: "
          + "encoded string too long: " + utfLen + " bytes");
    }
  }
}
