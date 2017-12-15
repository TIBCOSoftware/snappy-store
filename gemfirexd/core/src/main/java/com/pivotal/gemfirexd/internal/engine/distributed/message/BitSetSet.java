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

package com.pivotal.gemfirexd.internal.engine.distributed.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.util.ReuseFactory;

/**
 * This class is like {@link BitSet} and {@link FormatableBitSet} while also
 * implementing {@link Set} unlike those two classes and has a few more
 * optimizations thrown in for serialization/deserialization of sparse sets.
 * 
 * This class has a fixed maximum size determined at its construction time and
 * any attempt to add a value beyond that will result in an
 * {@link ArrayIndexOutOfBoundsException}.
 * 
 * @author swale
 */
public final class BitSetSet extends AbstractCollection<Integer> implements
    Set<Integer>, Serializable {

  private static final long serialVersionUID = 3340094932157368925L;

  static final int ADDRESS_BYTES = 3;

  static final int ADDRESS_SIZE = (1 << ADDRESS_BYTES);

  static final int ADDRESS_SIZE_1 = ADDRESS_SIZE - 1;

  private final byte[] bitMap;

  private int numUsedBits;

  static int udiv8(int i) {
    return (i >> ADDRESS_BYTES);
  }

  static int umod8(int i) {
    return (i & ADDRESS_SIZE_1);
  }

  static int umul8(int i) {
    return (i << ADDRESS_BYTES);
  }

  static int numBytes(int nbits) {
    return udiv8(nbits + ADDRESS_SIZE_1);
  }

  public BitSetSet(int nbits) {
    if (nbits != 0) {
      this.bitMap = new byte[numBytes(nbits)];
    }
    else {
      this.bitMap = ReuseFactory.getZeroLenByteArray();
    }
  }

  private BitSetSet(int nbytes, boolean useAsBytes) {
    if (nbytes != 0) {
      this.bitMap = new byte[nbytes];
    }
    else {
      this.bitMap = ReuseFactory.getZeroLenByteArray();
    }
  }

  @Override
  public boolean add(Integer val) {
    return addInt(val.intValue());
  }

  public boolean addInt(int val) {
    final int byteIndex = udiv8(val);
    final int posByteMask = (1 << umod8(val));
    if (((this.bitMap[byteIndex] & posByteMask) == 0)) {
      this.bitMap[byteIndex] |= posByteMask;
      this.numUsedBits++;
      return true;
    }
    else {
      return false;
    }
  }

  @Override
  public boolean contains(Object val) {
    return containsInt(((Integer)val).intValue());
  }

  public boolean containsInt(int val) {
    int byteIndex = udiv8(val);
    return (byteIndex < this.bitMap.length
        && (this.bitMap[byteIndex] & (1 << umod8(val))) != 0);
  }

  @Override
  public Iterator<Integer> iterator() {
    return new Itr();
  }

  @Override
  public boolean remove(Object val) {
    return removeInt(((Integer)val).intValue());
  }

  public boolean removeInt(final int pos) {
    int byteIndex = udiv8(pos);
    int posByte = (1 << umod8(pos));
    boolean isSet = ((this.bitMap[byteIndex] & posByte) != 0);
    this.bitMap[byteIndex] &= ~posByte;
    if (isSet) {
      --this.numUsedBits;
      return true;
    }
    return false;
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    boolean changed = false;
    for (Object val : c) {
      changed |= remove(val);
    }
    return changed;
  }

  @Override
  public void clear() {
    Arrays.fill(this.bitMap, (byte)0);
  }

  @Override
  public boolean isEmpty() {
    return (this.numUsedBits == 0);
  }

  @Override
  public int size() {
    return this.numUsedBits;
  }

  public static void toData(BitSetSet bss, DataOutput out) throws IOException {
    if (bss != null) {
      bss.toData(out);
    }
    else {
      InternalDataSerializer.writeSignedVL(0, out);
    }
  }

  public void toData(DataOutput out) throws IOException {
    final int nbytes = this.bitMap.length;
    if (nbytes > 0) {
      // some simplistic estimate beyond which we hope the serialization
      // of bitmap to be more efficient than serialization of individual
      // bytes; note that the value 32 is where each ID will start taking
      // three bytes so we reduce the limit for that case and beyond
      final int limitForArraySerialization = (nbytes <= 32
          ? ((nbytes * 3) / 5) : 1);
      if (this.numUsedBits <= limitForArraySerialization) {
        // serialize as an array of integers in this case
        // write a negative length to indicate this case
        InternalDataSerializer.writeSignedVL(-this.numUsedBits, out);
        // Start writing from the end so as to write in descending order.
        // This helps to find the max value during deserialization.
        for (int pos = prevSetBit(nbytes - 1, ADDRESS_SIZE_1); pos >= 0;
            pos = prevSetBit(pos - 1)) {
          InternalDataSerializer.writeUnsignedVL(pos, out);
        }
      }
      else {
        byte[] inUseByteMap = new byte[numBytes(nbytes)];
        for (int index = 0; index < nbytes; ++index) {
          if (this.bitMap[index] != 0) {
            inUseByteMap[udiv8(index)] |= (1 << umod8(index));
          }
        }
        final int inUseLen = inUseByteMap.length;
        InternalDataSerializer.writeSignedVL(inUseLen, out);
        out.write(inUseByteMap, 0, inUseLen);
        for (int index = 0; index < nbytes; ++index) {
          int currByte = this.bitMap[index];
          if (currByte != 0) {
            out.writeByte(currByte);
          }
        }
      }
    }
    else {
      InternalDataSerializer.writeSignedVL(0, out);
    }
  }

  public static BitSetSet fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    int inUseLen = (int)InternalDataSerializer.readSignedVL(in);
    if (inUseLen < 0) {
      // this is the case of normal array of integers serialization
      int maxVal = (int)InternalDataSerializer.readUnsignedVL(in);
      BitSetSet set = new BitSetSet(maxVal + 1);
      set.addInt(maxVal);
      final int numBits = -inUseLen;
      for (int index = 1; index < numBits; ++index) {
        set.addInt((int)InternalDataSerializer.readUnsignedVL(in));
      }
      return set;
    }
    else if (inUseLen > 0) {
      byte[] inUseByteMap = new byte[inUseLen];
      in.readFully(inUseByteMap, 0, inUseLen);
      // the below code can end-up allocating upto 7 extra bytes but we
      // can live with that instead of trying to calculate exact length
      BitSetSet set = new BitSetSet(umul8(inUseLen), true);
      for (int index = 0; index < inUseLen; ++index) {
        int inUseByte = inUseByteMap[index];
        int startPos = umul8(index);
        for (int pos = 0; pos < ADDRESS_SIZE; ++pos) {
          if ((inUseByte & (1 << pos)) != 0) {
            byte v = in.readByte();
            set.bitMap[startPos + pos] = v;

            // From Derby's FormatableBitSet#getNumBitsSet()

            // "Truth table", bits set in half-nibble (2 bits):
            //  A | A>>1 | A-=A>>1 | bits set
            // ------------------------------
            // 00 |  00  |    0    |    0
            // 01 |  00  |    1    |    1
            // 10 |  01  |    1    |    1
            // 11 |  01  |    2    |    2

            // Calculate bits set in each half-nibble in parallel
            //   |ab|cd|ef|gh|
            // - |>a|&c|&e|&g|>
            // ----------------
            // = |ij|kl|mn|op|
            v -= ((v >> 1) & 0x55);

            // Add the upper and lower half-nibbles together and store
            // in each nibble
            //  |&&|kl|&&|op|
            //+ |>>|ij|&&|mn|>>
            //-----------------
            //= |0q|rs|0t|uv|
            v = (byte)((v & 0x33) + ((v >> 2) & 0x33));

            // Add the nibbles together
            //  |&&&&|&tuv|
            //+ |>>>>|0qrs|>>>>
            //-----------------
            //= |0000|wxyz|
            v = (byte)((v & 0x7) + (v >> 4));
            set.numUsedBits += v;
          }
        }
      }
      return set;
    }
    return null;
  }

  public int nextSetBit(int pos) {
    return nextSetBit(udiv8(pos), umod8(pos));
  }

  /**
   * Return the position of next set bit starting at the given index and
   * position.
   */
  public int nextSetBit(int startIndex, int startPos) {
    final int bitMapLen = bitMap.length;
    while (startIndex < bitMapLen) {
      int currByte = bitMap[startIndex] >>> startPos;
      if (currByte != 0) {
        // the below uses a binary search algorithm for good overall
        // efficiency; first check the last four bytes then last two
        // or penultimate two, or first four and then last two or
        // penultimate two
        if ((currByte & 0x0f) != 0) {
          if ((currByte & 0x03) != 0) {
            startPos += ((currByte & 0x01) != 0 ? 0 : 1);
          }
          else {
            startPos += ((currByte & 0x04) != 0 ? 2 : 3);
          }
        }
        else {
          if ((currByte & 0x30) != 0) {
            startPos += ((currByte & 0x10) != 0 ? 4 : 5);
          }
          else {
            startPos += ((currByte & 0x40) != 0 ? 6 : 7);
          }
        }
        return (startPos + umul8(startIndex));
      }
      startPos = 0;
      ++startIndex;
    }
    return -1;
  }

  public int prevSetBit(int pos) {
    return prevSetBit(udiv8(pos), umod8(pos));
  }

  /**
   * Return the position of previous set bit starting at the given index and
   * position.
   */
  public int prevSetBit(int startIndex, int startPos) {
    while (startIndex >= 0) {
      int currByte = bitMap[startIndex];
      if (startPos >= 0) {
        currByte &= ((1 << (startPos + 1)) - 1);
      }
      if (currByte != 0) {
        // the below uses a binary search algorithm for good overall
        // efficiency; first check the first four bytes then first two
        // or last two, or last four and then first two or last two
        final int p;
        if ((currByte & 0xf0) != 0) {
          if ((currByte & 0xc0) != 0) {
            p = ((currByte & 0x80) != 0 ? 7 : 6);
          }
          else {
            p = ((currByte & 0x20) != 0 ? 5 : 4);
          }
        }
        else {
          if ((currByte & 0x0c) != 0) {
            p = ((currByte & 0x08) != 0 ? 3 : 2);
          }
          else {
            p = ((currByte & 0x02) != 0 ? 1 : 0);
          }
        }
        return (p + umul8(startIndex));
      }
      startPos = -1;
      --startIndex;
    }
    return -1;
  }

  private class Itr implements Iterator<Integer> {

    private int cursor;

    private int lastRet;

    Itr() {
      this.cursor = nextSetBit(0, 0);
      this.lastRet = -1;
    }

    public boolean hasNext() {
      return (this.cursor >= 0);
    }

    public Integer next() {
      if (this.cursor < 0) {
        throw new NoSuchElementException();
      }
      this.lastRet = this.cursor;
      this.cursor = nextSetBit(this.cursor + 1);
      return Integer.valueOf(this.lastRet);
    }

    public void remove() {
      if (this.lastRet == -1) {
        throw new IllegalStateException();
      }
      removeInt(this.lastRet);
      this.lastRet = -1;
    }
  }
}
