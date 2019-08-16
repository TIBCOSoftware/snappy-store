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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.shared.common;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Comparator;

import com.gemstone.gemfire.internal.shared.ClientResolverUtils;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import org.slf4j.Logger;

/**
 * Keeping here the core logic for resolvers and hashcode and computehashcode
 * logic so that can be used on both client and server side. 
 * More logic related to resolvers can be
 * pushed here if the client needs to use the same logic for single hop.
 *
 * @author kneeraj
 *
 */
public abstract class ResolverUtils extends ClientResolverUtils {

  protected ResolverUtils() {
    // no instance
  }

  public static Integer TOKEN_FOR_DB_SYNC = (int)Short.MAX_VALUE;

  public static Object TOK_ALL_NODES = new Object() {

    @Override
    public boolean equals(Object obj) {
      return obj == this;
    }

    @Override
    public String toString() {
      return "TOK_ALL_ROUTING_KEYS";
    }
  };

  private static final Constructor<?> bigIntCons;
  private static final long stringCharsOffset;

  static {
    Constructor<?> cons;
    Field strChars;
    try {
      cons = BigInteger.class.getDeclaredConstructor(int[].class, int.class);
      cons.setAccessible(true);
    } catch (Exception e) {
      cons = null;
    }
    try {
      strChars = String.class.getDeclaredField("value");
      strChars.setAccessible(true);
      // check if we really got the char[] field
      if (!char[].class.equals(strChars.getType())) {
        strChars = null;
      }
    } catch (Exception e) {
      strChars = null;
    }
    bigIntCons = cons;
    stringCharsOffset = strChars != null
        ? UnsafeHolder.getUnsafe().objectFieldOffset(strChars) : -1L;
  }

  private static final Integer MINUS_ONE = -1;
  private static final Integer ZERO = 0;
  private static final Integer PLUS_ONE = 1;

  /**
   * Create a new BigInteger given a magnitude and signum wrapping the given
   * magnitude if possible. Note that the magnitude argument is assumed to be
   * already stripped of leading zeros.
   */
  public static BigInteger newBigInteger(final int[] mag, final int signum) {
    // we try to avoid converting to byte[] if possible which will also save us
    // an object (one Object[] vs one byte[] + one internal int[]) apart from
    // avoiding the conversion
    if (bigIntCons != null) {
      try {
        final Integer sig;
        switch (signum) {
          case 0:
            sig = ZERO;
            break;
          case 1:
            sig = PLUS_ONE;
            break;
          case -1:
            sig = MINUS_ONE;
            break;
          // should never happen
          default:
            sig = signum;
            break;
        }
        return (BigInteger)bigIntCons.newInstance(mag, sig);
      } catch (Exception ex) {
        throw ClientSharedUtils.newRuntimeException("unexpected exception", ex);
      }
    }
    // fallback to converting to byte[] and invoke the public constructor
    final byte[] magnitude = new byte[mag.length << 2];
    int index = 0;
    for (int i = 0; i < mag.length; i++) {
      final int v = mag[i];
      magnitude[index++] = (byte)(v >>> 24);
      magnitude[index++] = (byte)(v >>> 16);
      magnitude[index++] = (byte)(v >>> 8);
      magnitude[index++] = (byte)(v);
    }
    return new BigInteger(signum, magnitude);
  }

  /**
   * Return true if it is possible to wrap the magnitude in a BigInteger and
   * false if a copy has to be made always.
   */
  public static final boolean hasBigIntegerWrapperConstructor() {
    return bigIntCons != null;
  }

  /**
   * {@link Comparator} implementation for checking if a value lies within a
   * {@link GfxdRange}.
   * 
   * @author Sumedh Wale
   * @since 6.0
   */
  public static class GfxdRangeComparator implements Comparator {

    /** SQL command string used for exception messages. */
    private final String command;

    /**
     * Constructor that takes the current SQL command for throwing proper
     * exception messages.
     */
    public GfxdRangeComparator(String command) {
      this.command = command;
    }

    /**
     * Check if the given object is within a {@link GfxdRange}. Either of the
     * arguments can be in any order so the check is done both ways (i.e.
     * whether first arg is an object that lies within second arg which is a
     * {@link GfxdRange}, and vice versa). When the object is in range then this
     * returns 0, if it is to the left of range then -1 and +1 if it is to the
     * right of the range.
     * 
     * If both args are {@link GfxdRange}s then it returns the result of
     * {@link GfxdRange#compareTo(Object)}.
     * 
     * @throws ClassCastException
     *           when one argument is not an object that can be checked to lie
     *           within other range argument which should be of type
     *           {@link GfxdRange}.
     */
    public int compare(Object objOrRange1, Object rangeOrObj2)
        throws ClassCastException {
      if (rangeOrObj2 instanceof GfxdRange) {
        if (objOrRange1 instanceof GfxdRange) {
          return ((GfxdRange)objOrRange1).compareTo(rangeOrObj2);
        }
        else {
          return -1 * ((GfxdRange)rangeOrObj2).inRange(
              (GfxdComparableFuzzy)objOrRange1);
        }
      }
      else if (objOrRange1 instanceof GfxdRange) {
        return ((GfxdRange)objOrRange1).inRange(
            (GfxdComparableFuzzy)rangeOrObj2);
      }
      else if (objOrRange1 instanceof GfxdComparableFuzzy) {
        return ((GfxdComparableFuzzy)objOrRange1).compareTo(rangeOrObj2);
      }
      throw new ClassCastException(this.command + ": Cannot compare object of "
          + objOrRange1.getClass() + " against object of "
          + rangeOrObj2.getClass());
    }
  }

  public static class GfxdComparableFuzzy implements Comparable,
      Serializable {
    private static final long serialVersionUID = -1475537216488457161L;

    /**
     * If object is null, the <code>isExclusive</code> true means -infinity
     * else +infinity
     */
    protected Comparable obj;

    protected int boundaryType;

    public static final int GT = 1;

    public static final int GE = 0;

    public static final int LE = 0;

    public static final int LT = -1;

    public GfxdComparableFuzzy(Comparable obj, int boundaryType) {
      this.obj = obj;
      this.boundaryType = boundaryType;
    }

    public GfxdComparableFuzzy(Object obj) {
      this.obj = (Comparable)obj;
      this.boundaryType = GE;
    }

    public int compareTo(Object other) throws ClassCastException {
      if (other instanceof GfxdComparableFuzzy) {
        GfxdComparableFuzzy otherObj = (GfxdComparableFuzzy)other;
        if (otherObj.obj == null) {
          if (this.obj == null) {
            /*
            return (this.isExclusive == otherObj.isExclusive ? 0
                : (this.isExclusive ? 1 : -1));
            */
            return otherObj.boundaryType - this.boundaryType;
          }
          else {
            return otherObj.boundaryType;
            //return (otherObj.isExclusive ? -1 : 1);
          }
        }
        else {
          if (this.obj == null) {
            //return (this.isExclusive ? 1 : -1);
            return -this.boundaryType;
          }
          else {
            int cmp = 0;
            if (this.obj.getClass() == Integer.class && otherObj.obj.getClass() == Short.class) {
              // Fix for bug #47062
              // Other incomparable types can also result in ClassCastException and that is ok
              // unless these java classes of corresponding native compatible types comes in picture
              cmp = this.obj.compareTo(Integer.valueOf((Short)otherObj.obj).intValue());
            } else {
              cmp = this.obj.compareTo(otherObj.obj);
            }
            if (cmp == 0) {
              return this.boundaryType - otherObj.boundaryType;
              /*
              return (this.isExclusive == otherObj.isExclusive ? 0
                  : (this.isExclusive ? 1 : -1));
              */
            }
            else {
              return cmp;
            }
          }
        }
      }
      throw new ClassCastException("Cannot compare GfxdComparableFuzzy"
          + " against object of " + obj.getClass());
    }

    public Class getObjectType() {
      return this.obj.getClass();
    }

    @Override
    public String toString() {
      return "GfxdComparableFuzzy: val = " + this.obj + ", boundaryType = "
          + this.boundaryType;
    }

    public Object getWrappedObject() {
      return this.obj;
    }
  }

  /**
   * Representation of a range having a start and end of same type, that are
   * {@link Comparable} and start <= end. If start is null then it stands for
   * -infinity, and end as null stands for +infinity, but both cannot be null.
   * 
   * @author Sumedh Wale
   * @since 6.0
   */
  public static class GfxdRange implements Comparable, Cloneable,
      Serializable {

    private static final long serialVersionUID = 4157382512604052723L;

    /** start of the range */
    private GfxdComparableFuzzy start;

    /** end of the range */
    protected GfxdComparableFuzzy end;

    /** the type of range start and end objects */
    private Class rangeType;

    /** SQL command string used for exception messages. */
    protected String command;

    /**
     * Constructor given the start and end objects. Both should be
     * {@link Comparable} and such that start < end.
     * 
     * Also requires the SQL command string that is prepended to the exception
     * messages.
     */
//     * @throws StandardException
//     *           if either of start or end is not {@link Comparable}, or both
//     *           are null or the two are part of completely different type
//     *           hierarchies
//     */
    public GfxdRange(String command, Object start, Object end)
        /*throws StandardException*/ {
      if (start != null) {
        if (!(start instanceof Comparable)) {
//          throw StandardException.newException(SQLState.LANG_NOT_COMPARABLE,
//              command, "START NOT COMPARABLE IN RANGE: " + start + " - " + end);
        }
        this.rangeType = start.getClass();
        if (end != null) {
          // TODO: [sumedh] Actually check if one is convertible to another
          // like Derby rather than strict equality.
          // Also check against the column type and convert the range start/end
          // to that type.
          if (!this.rangeType.equals(end.getClass())) {
//            throw StandardException.newException(SQLState.TYPE_MISMATCH,
//                command, "TYPE MISMATCH OF START AND END IN RANGE: " + start
//                    + " - " + end);
          }
        }
      }
      else if (end != null) {
        if (!(end instanceof Comparable)) {
//          throw StandardException.newException(SQLState.LANG_NOT_COMPARABLE,
//              command, "END NOT COMPARABLE IN RANGE: " + start + " - " + end);
        }
        this.rangeType = end.getClass();
      }
      else {
//        throw StandardException.newException(
//            SQLState.LANG_NULL_TO_PRIMITIVE_PARAMETER, command,
//            "NULL START AND END FOR RANGE");
      }
      this.command = command;
      // If start is null then to indicate that this is -infinity we
      // use the flag Exclusive. So exclusive = true means -infinity
      // and exclusive = false means +infinity
      Comparable startComp = (Comparable)start;
      Comparable endComp = (Comparable)end;
      if (start == null) {
        this.start = new GfxdComparableFuzzy(startComp, GfxdComparableFuzzy.GT);
      }
      else {
        this.start = new GfxdComparableFuzzy(startComp, GfxdComparableFuzzy.GE);
      }
      if (end == null) {
        this.end = new GfxdComparableFuzzy(endComp, GfxdComparableFuzzy.LT);
      }
      else {
        this.end = new GfxdComparableFuzzy(endComp, GfxdComparableFuzzy.LT);
      }
    }

    public GfxdRange(String command, GfxdComparableFuzzy start,
        GfxdComparableFuzzy end) /*throws StandardException*/ {
      this.command = command;
      this.start = start;
      this.end = end;
      if (start != null) {
        this.rangeType = start.getObjectType();
      }
      else if (end != null) {
        this.rangeType = end.getObjectType();
      }
      else {
        this.rangeType = null;
      }
    }

    /** returns the SQL command being used for exception messages */
    public String getCommand() {
      return this.command;
    }

    /** returns the SQL command being used for exception messages */
    public String setCommand(String cmd) {
      return this.command = cmd;
    }
    
    /** returns the start of this range */
    public Comparable rangeStart() {
      return this.start;
    }

    /** returns the end of this range */
    public Comparable rangeEnd() {
      return this.end;
    }

    /**
     * Invalidate this range so that any further {@link #inRange} calls
     * throw {@link ClassCastException}s.
     */
    public void invalidate() {
      this.rangeType = null;
    }

    public boolean isInvalid() {
      if (this.rangeType == null) {
        return true;
      }
      return false;
    }

    /**
     * Test if a given object lies within this range (start inclusive and end
     * exclusive for the range).
     * 
     * @param obj
     *          the object to be checked if it is within this range
     * @return 0 if the given object is within this range, -1 if it is to the
     *         left of this range and +1 if it is to the right
     * @throws ClassCastException
     *           if the given object is incompatible with this range
     */
    public int inRange(GfxdComparableFuzzy obj) throws ClassCastException {
      if (this.rangeType == null) {
        throw new ClassCastException(this.command
            + ": range object invalidated");
      }
      if (this.start.compareTo(obj) > 0) {
        return 1;
      }
      if (this.end.compareTo(obj) < 0) {
        return -1;
      }
      return 0;
    }

    /**
     * This compares non-overlapping ranges, and returns -1 if given range lies
     * to the left of this range, +1 if given range lies to the right and throws
     * a {@link ClassCastException} otherwise. Equal ranges are also not allowed
     * since that will be a duplicate, so this never returns 0.
     */
    public int compareTo(Object other) throws ClassCastException {
      // same key compare check happens in JDK7
      if (this == other) {
        return 0;
      }
      if (other instanceof GfxdRange) {
        GfxdRange otherRange = (GfxdRange)other;
        if (this.start.compareTo(otherRange.end) > 0) {
          return 1;
        }
        else if (otherRange.start.compareTo(this.end) > 0) {
          return -1;
        }
        throw new ClassCastException(this.command
            + ": Cannot have overlapping ranges: [" + this.toString() + "], ["
            + otherRange.toString() + ']');
      }
      throw new ClassCastException(this.command + "Cannot compare range ["
          + this.toString() + "] against object of " + other.getClass());
    }

    @Override
    public String toString() {
      return (this.start != null ? this.start.toString() : "-infinity") + " - "
          + (this.end != null ? this.end.toString() : "+infinity");
    }

    public final void getDDLString(StringBuilder sb) {
      if (this.start.obj != null) {
        sb.append(this.start.boundaryType == GfxdComparableFuzzy.GT ? '(' : '[');
        sb.append(this.start.obj);
      }
      else {
        sb.append("(-infinity");
      }
      sb.append(',');
      if (this.end.obj != null) {
        sb.append(this.end.obj);
        sb.append(this.end.boundaryType == GfxdComparableFuzzy.LT ? ')' : ']');
      }
      else {
        sb.append("+infinity)");
      }
    }

    @Override
    public Object clone() {
      //try {
        return new GfxdRange(this.command, this.start, this.end);
//      } catch (StandardException ex) {
//        throw new RuntimeException("GfxdRange.clone() unexpected exception", ex);
//      }
    }
    
    public GfxdComparableFuzzy getStart() {
      return this.start;
    }
    
    public GfxdComparableFuzzy getEnd() {
      return this.end;
    }
  }
  
  //The below methods decide what hash code method to use, based on the field type.
  //For integer type fields, we return the value of the integer, in order
  //to facilate nice good bucket distribution for integer fields.
  //All field types use the sbox method.
  public static int addLongToBucketHash(long val, int hash, int typeId) {
    switch (typeId) {
      case StoredFormatIds.SQL_LONGINT_ID:
      case StoredFormatIds.SQL_INTEGER_ID:
      case StoredFormatIds.SQL_SMALLINT_ID:
      case StoredFormatIds.SQL_TINYINT_ID:
      case StoredFormatIds.LONGINT_TYPE_ID:
      case StoredFormatIds.INT_TYPE_ID:
      case StoredFormatIds.SMALLINT_TYPE_ID:
      case StoredFormatIds.TINYINT_TYPE_ID:
        return addLongToChunkedHash(val, hash);
      default:
        return addLongToHash(val, hash);
    }
  }

  public static int addIntToBucketHash(final int val, int hash, int typeId) {
    switch (typeId) {
      case StoredFormatIds.SQL_LONGINT_ID:
      case StoredFormatIds.SQL_INTEGER_ID:
      case StoredFormatIds.SQL_SMALLINT_ID:
      case StoredFormatIds.SQL_TINYINT_ID:
      case StoredFormatIds.LONGINT_TYPE_ID:
      case StoredFormatIds.INT_TYPE_ID:
      case StoredFormatIds.SMALLINT_TYPE_ID:
      case StoredFormatIds.TINYINT_TYPE_ID:
        return addIntToChunkedHash(val, hash);
      default:
        return addIntToHash(val, hash);
    }
  }

  public static int addByteToBucketHash(final byte val, int hash, int typeId) {
    switch (typeId) {
      case StoredFormatIds.SQL_LONGINT_ID:
      case StoredFormatIds.SQL_INTEGER_ID:
      case StoredFormatIds.SQL_SMALLINT_ID:
      case StoredFormatIds.SQL_TINYINT_ID:
      case StoredFormatIds.LONGINT_TYPE_ID:
      case StoredFormatIds.INT_TYPE_ID:
      case StoredFormatIds.SMALLINT_TYPE_ID:
      case StoredFormatIds.TINYINT_TYPE_ID:
        return addByteToChunkedHash(val, hash);
      default:
        return addByteToHash(val, hash);
    }
  }

  public static int addBytesToBucketHash(final byte[] bytes, int hash,
      int typeId) {
    switch (typeId) {
      case StoredFormatIds.SQL_LONGINT_ID:
      case StoredFormatIds.SQL_INTEGER_ID:
      case StoredFormatIds.SQL_SMALLINT_ID:
      case StoredFormatIds.SQL_TINYINT_ID:
      case StoredFormatIds.LONGINT_TYPE_ID:
      case StoredFormatIds.INT_TYPE_ID:
      case StoredFormatIds.SMALLINT_TYPE_ID:
      case StoredFormatIds.TINYINT_TYPE_ID:
        return addBytesToChunkedHash(bytes, hash);
      default:
        return addBytesToHash(bytes, hash);
    }
  }

  public static int addBytesToBucketHash(final byte[] bytes, int offset,
      final int length, int hash, int typeId) {
    switch (typeId) {
      case StoredFormatIds.SQL_LONGINT_ID:
      case StoredFormatIds.SQL_INTEGER_ID:
      case StoredFormatIds.SQL_SMALLINT_ID:
      case StoredFormatIds.SQL_TINYINT_ID:
      case StoredFormatIds.LONGINT_TYPE_ID:
      case StoredFormatIds.INT_TYPE_ID:
      case StoredFormatIds.SMALLINT_TYPE_ID:
      case StoredFormatIds.TINYINT_TYPE_ID:
        return addBytesToChunkedHash(bytes, offset, length, hash);
      default:
        return addBytesToHash(bytes, offset, length, hash);
    }
  }

  public static final int addBytesToHash(final ByteBuffer bytes, int hash) {
    // read in longs to minimize ByteBuffer get() calls
    int pos = bytes.position();
    final int endPos = bytes.limit();
    // round off to nearest factor of 8 to read in longs
    final int endRound8Pos = ((endPos - pos) % 8) != 0 ? (endPos - 8) : endPos;
    if (bytes.order() == ByteOrder.BIG_ENDIAN) {
      while (pos < endRound8Pos) {
        // splitting into longs is faster than reading one byte at a time even
        // though it costs more operations (about 20% in micro-benchmarks)
        final long v = bytes.getLong(pos);
        hash ^= sbox[(int)((v >>> 56) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 48) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 40) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 32) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 24) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 16) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 8) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)(v & 0xff)];
        hash *= 3;

        pos += 8;
      }
    }
    else {
      while (pos < endRound8Pos) {
        // splitting into longs is faster than reading one byte at a time even
        // though it costs more operations (about 20% in micro-benchmarks)
        final long v = bytes.getLong(pos);
        hash ^= sbox[(int)(v & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 8) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 16) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 24) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 32) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 40) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 48) & 0xff)];
        hash *= 3;
        hash ^= sbox[(int)((v >>> 56) & 0xff)];
        hash *= 3;

        pos += 8;
      }
    }
    while (pos < endPos) {
      hash ^= sbox[bytes.get(pos) & 0xff];
      hash *= 3;
      pos++;
    }
    return hash;
  }

  /**
   * Set this to true to force using pre GemFireXD 1.3.0.2 release hashing
   * schema. This will be required if adding pre 1.3.0.2 servers in a newer
   * cluster. Other way around will be handled automatically by the product or
   * when recovering from old data files.
   */
  public final static String GFXD_USE_PRE1302_HASHCODE =
      "gemfirexd.use-pre1302-hashing";

  /**
   * This is used to determine whether this system should use old hashing for
   * integer columns and global indexes (#51381). System will automatically
   * switch to it if using a mix of pre GFXD 1.3.0.2 and new servers.
   * <p>
   * Not needed to be volatile since it is updated only by DDLConflatable when
   * the system is "locked" and no concurrent threads can be reading it.
   */
  private static boolean GEMFIREXD_USING_GFXD1302_HASHING = true;
  private static Boolean GEMFIREXD_GFXD1302_HASHING_STATE = null;

  /**
   * using a large prime in new scheme that will distribute the keys better
   * even when the spread of each column is small (#51381)
   */
  private static final int PRIME_FACTOR_FOR_CHUNKED_INT_HASH = 19845871;
  private static final int PRIME_FACTOR_FOR_CHUNKED_BYTE_HASH = 197;

  private static final int OLD_PRIME_FACTOR_FOR_CHUNKED_HASH = 31;

  // Hashing methods below use a simpler hash generation like by Arrays.hashCode
  // that leads to better distribution with modulo num buckets (see #43271)
  // Thes method takes hash integer at a time from the byte stream 
  // and computes 31^n * integer[0] + 31^n-1 integer[1] + ... integer[n]
  //
  // [sumedh] Now using larger prime factors (#51381)

  /**
   * Return true if using new hashing for global index and integer columns in
   * tables (#51381).
   */
  public static final boolean isUsingGFXD1302Hashing() {
    return GEMFIREXD_USING_GFXD1302_HASHING;
  }

  public static synchronized boolean isGFXD1302HashingStateSet() {
    return GEMFIREXD_GFXD1302_HASHING_STATE != null;
  }

  public static synchronized void setUseGFXD1302Hashing(boolean checkState) {
    if (GEMFIREXD_GFXD1302_HASHING_STATE == null) {
      GEMFIREXD_GFXD1302_HASHING_STATE = Boolean.TRUE;
      GEMFIREXD_USING_GFXD1302_HASHING = true;
    }
    else if (checkState && !GEMFIREXD_GFXD1302_HASHING_STATE.booleanValue()) {
      // cannot change hashing state once it is set
      throw new IllegalStateException("GemFireXD: already setup to use"
          + " pre GemFireXD 1.3.0.2 hashing scheme for tables but found a"
          + " new GemFireXD >= 1.3.0.2 table source. Use "
          + GFXD_USE_PRE1302_HASHCODE
          + " system property to force using pre 1.3.0.2 hashing but"
          + " consult documentation on upgrade path to use new hashing"
          + " scheme consistently for optimal performance.");
    }
  }

  public static synchronized void setUsePre1302Hashing(boolean checkState) {
    if (!GEMFIREXD_USING_GFXD1302_HASHING) {
      return;
    }
    if (checkState && GEMFIREXD_GFXD1302_HASHING_STATE != null
        && GEMFIREXD_GFXD1302_HASHING_STATE.booleanValue()) {
      throw new IllegalStateException("GemFireXD: already setup to use"
          + " new GemFireXD 1.3.0.2 hashing scheme for tables but found a"
          + " pre 1.3.0.2 table source. Use "
          + GFXD_USE_PRE1302_HASHCODE
          + " system property to force using pre 1.3.0.2 hashing but"
          + " consult documentation on upgrade path to use new hashing"
          + " scheme consistently for optimal performance.");
    }
    Logger logger = ClientSharedUtils.getLogger(ResolverUtils.class);
    if (logger != null) {
    logger.warn("Using non-optimal pre 1.3.0.2 hashing scheme "
        + "due to old members or data files in the distributed system (#51381)."
        + " Consult documentation on upgrade path for optimal performance.");
    }
    GEMFIREXD_GFXD1302_HASHING_STATE = Boolean.FALSE;
    GEMFIREXD_USING_GFXD1302_HASHING = false;
  }

  /**
   * Reset any static information. Not synchronized since it is expected to be
   * invoked from a single thread (or a static initialization block itself)
   */
  public static void reset() {
    GEMFIREXD_USING_GFXD1302_HASHING = true;
    GEMFIREXD_GFXD1302_HASHING_STATE = null;
  }

  public static int addIntToChunkedHash(final int val, int hash) {
    return GEMFIREXD_USING_GFXD1302_HASHING
        ? (PRIME_FACTOR_FOR_CHUNKED_INT_HASH * hash) + val
        : (OLD_PRIME_FACTOR_FOR_CHUNKED_HASH * hash) + val;
  }

  public static int addLongToChunkedHash(final long val, int hash) {
    // calculate in big-endian format to be consistent with DataOutput.writeLong
    hash = addIntToChunkedHash((int) (val >>> 32), hash);
    return addIntToChunkedHash((int) val, hash);
  }

  private static final int addByteToChunkedHash(final byte b, int hash) {
    return GEMFIREXD_USING_GFXD1302_HASHING
        ? (PRIME_FACTOR_FOR_CHUNKED_BYTE_HASH * hash) + (b & 0xFF)
        : (OLD_PRIME_FACTOR_FOR_CHUNKED_HASH * hash) + (b & 0xFF);
  }

  private static int addBytesToChunkedHash(final byte[] bytes, int hash) {
    return addBytesToChunkedHash(bytes, 0, bytes.length, hash);
  }

  private static int addBytesToChunkedHashPre1302(final byte[] bytes,
      int offset, final int length, int hash) {
    if (bytes != null) {
      final int endPos = (offset + length);
      while (offset < endPos) {
        int nextInt = 0;
        for (int i = 0; i < 4 && offset < endPos; i++) {
          nextInt <<= 8;
          nextInt += (bytes[offset] & 0xFF);
          offset++;
        }
        hash = (OLD_PRIME_FACTOR_FOR_CHUNKED_HASH * hash) + nextInt;
      }
    }
    return hash;
  }

  private static int addBytesToChunkedHash(final byte[] bytes, int offset,
      final int length, int hash) {
    if (GEMFIREXD_USING_GFXD1302_HASHING) {
      if (bytes != null) {
        final int endPos = (offset + length);
        while (offset < endPos) {
          int nextInt = 0;
          for (int i = 0; i < 4 && offset < endPos; i++) {
            nextInt <<= 8;
            nextInt += (bytes[offset] & 0xFF);
            offset++;
          }
          hash = (PRIME_FACTOR_FOR_CHUNKED_INT_HASH * hash) + nextInt;
        }
      }
      return hash;
    }
    else {
      return addBytesToChunkedHashPre1302(bytes, offset, length, hash);
    }
  }

  public static final long MAG_MASK = 0xFFFFFFFFL;

  /**
   * Compute the hashCode of a BigInteger using provided magnitude without
   * serializing the value to bytes. The value returned is the same as would be
   * obtained by serializing to bytes using
   * <code></code>InternalDataSerializer#serializeBigIntMagnitudeToBytes</code>
   * and then invoking {@link #addBytesToBucketHash} on the result.
   */
  public static int computeHashCode(final int[] magnitude, int hash, 
      int formatId) {
    final int size_1 = magnitude.length - 1;
    if (size_1 >= 0) {
      // Write in big-endian format to be compatible with
      // BigInteger(int,byte[]) constructor.
      // We don't write from the end to allow using the same strategy when
      // writing to DataOutput and since this may also be slightly more
      // efficient reading from memory in forward direction rather than reverse.
      final long firstInt = ((long)magnitude[0] & MAG_MASK);
      int numBytesInFirstInt = numBytesWithoutZeros(firstInt);
      // first calculate for actual number of bytes required for first integer
      while (numBytesInFirstInt-- > 0) {
        hash = ResolverUtils.addByteToBucketHash(
            (byte)(firstInt >>> (8 * numBytesInFirstInt)), hash, formatId);
      }
      // now calculate for remaining integers that will each require four bytes
      for (int index = 1; index <= size_1; ++index) {
        hash = ResolverUtils.addIntToBucketHash(magnitude[index], hash,
            formatId);
      }
    }
    return hash;
  }

  /**
   * Get the number of bytes occupied in the given unsigned integer value.
   */
  public static final int numBytesWithoutZeros(final long value) {
    if (value <= 0xFF) {
      return 1;
    }
    if (value <= 0xFFFF) {
      return 2;
    }
    if (value <= 0xFFFFFF) {
      return 3;
    }
    return 4;
  }

  /**
   * Get the char[] for this string, possibly the handle to the internal char
   * array, so use with extreme care.
   */
  public static char[] getInternalChars(final String s, final int slen) {
    if (stringCharsOffset != -1L) {
      try {
        final char[] chars = (char[])UnsafeHolder.getUnsafe().getObject(
            s, stringCharsOffset);
        if (chars != null && chars.length == slen) {
          return chars;
        }
      } catch (Exception ex) {
        throw ClientSharedUtils.newRuntimeException("unexpected exception", ex);
      }
    }
    return getChars(s, slen);
  }

  /**
   * Get the internal char[] handle for this string so use with extreme care.
   * Returns null if it cannot get the handle for some reason, or if the string
   * does not start at zero offset.
   */
  public static char[] getInternalCharsOnly(final String s, final int slen) {
    if (stringCharsOffset != -1L) {
      try {
        final char[] chars = (char[])UnsafeHolder.getUnsafe().getObject(
            s, stringCharsOffset);
        if (chars != null && chars.length == slen) {
          return chars;
        }
      } catch (Exception ex) {
        throw ClientSharedUtils.newRuntimeException("unexpected exception", ex);
      }
    }
    return null;
  }

  public static char[] getChars(final String s, final int slen) {
    final char[] result = new char[slen];
    s.getChars(0, slen, result, 0);
    return result;
  }

  public static int getComputeHashOfCharArrayData(int hash, int strlen,
      char[] data, int typeId) {
    for (int index = 0; index < strlen; ++index) {
      final int c = data[index];
      if ((c >= 0x0001) && (c <= 0x007F)) {
        hash = ResolverUtils
            .addByteToBucketHash((byte)(c & 0xFF), hash, typeId);
      }
      else if (c > 0x07FF) {
        hash = ResolverUtils.addByteToBucketHash(
            (byte)(0xE0 | ((c >> 12) & 0x0F)), hash, typeId);
        hash = ResolverUtils.addByteToBucketHash(
            (byte)(0x80 | ((c >> 6) & 0x3F)), hash, typeId);
        hash = ResolverUtils.addByteToBucketHash(
            (byte)(0x80 | ((c >> 0) & 0x3F)), hash, typeId);
      }
      else {
        hash = ResolverUtils.addByteToBucketHash(
            (byte)(0xC0 | ((c >> 6) & 0x1F)), hash, typeId);
        hash = ResolverUtils.addByteToBucketHash(
            (byte)(0x80 | ((c >> 0) & 0x3F)), hash, typeId);
      }
    }
    return hash;
  }

  public static int getHashCodeOfCharArrayData(char[] data, String value,
      int rawLength) {
    if (data != null && rawLength > 0) {
      // Find 1st non-blank from the right
      int index = rawLength - 1;
      // if there are no blank pads then reuse String's cached hashCode
      if (data[index] == ' ') {
        while (index > 0 && data[--index] == ' ')
          ;
      }
      else if (value != null) {
        return value.hashCode();
      }
      int h = 0;
      for (int i = 0; i <= index; ++i) {
        // use same hash calculation as JDK String.hashCode()
        h = 31 * h + data[i];
      }
      return h;
    }
    return 0;
  }
}
