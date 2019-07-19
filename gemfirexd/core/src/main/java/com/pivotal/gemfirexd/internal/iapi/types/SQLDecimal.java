/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.SQLDecimal

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.iapi.types;

import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.ByteArrayDataOutput;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.io.Storable;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import org.apache.spark.unsafe.Platform;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.lang.Math;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.ObjectOutput;
import java.io.ObjectInput;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * SQLDecimal satisfies the DataValueDescriptor
 * interfaces (i.e., OrderableDataType). It implements a numeric/decimal column, 
 * e.g. for * storing a column value; it can be specified
 * when constructed to not allow nulls. Nullability cannot be changed
 * after construction, as it affects the storage size and mechanism.
 * <p>
 * Because OrderableDataType is a subtype of DataType,
 * SQLDecimal can play a role in either a DataType/Row
 * or a OrderableDataType/Row, interchangeably.
 * <p>
 * We assume the store has a flag for nullness of the value,
 * and simply return a 0-length array for the stored form
 * when the value is null.
 *
 */
public final class SQLDecimal extends NumberDataType implements VariableSizeDataValue
{
	static final BigDecimal ZERO = BigDecimal.valueOf(0L);
	static final BigDecimal ONE = BigDecimal.valueOf(1L);
	static final BigDecimal MAXLONG_PLUS_ONE = BigDecimal.valueOf(Long.MAX_VALUE).add(ONE);
	static final BigDecimal MINLONG_MINUS_ONE = BigDecimal.valueOf(Long.MIN_VALUE).subtract(ONE);



	/**
	 * object state.  Note that scale and precision are 
	 * always determined dynamically from value when
	 * it is not null.

       The field value can be null without the data value being null.
	   In this case the value is stored in rawData and rawScale. This
	   is to allow the minimal amount of work to read a SQLDecimal from disk.
	   Creating the BigDecimal is expensive as it requires allocating
	   three objects, the last two are a waste in the case the row does
	   not qualify or the row will be written out by the sorter before being
	   returned to the application.
		<P>
		This means that this field must be accessed for read indirectly through
		the getBigDecimal() method, and when setting it the rawData field must
		be set to null.

	 */
	private BigDecimal	value;

	/**
		See comments for value
	*/
	private byte[]		rawData;
// GemStone changes BEGIN
	private byte rawSig;
// GemStone changes END

	/**
		See comments for value
	*/
// GemStone changes BEGIN
	private byte rawScale;
	/* (original code)
	private int			rawScale;
	*/

    private static final int BASE_MEMORY_USAGE = ClassSize.estimateBaseFromCatalog( SQLDecimal.class);
    private static final int BIG_DECIMAL_MEMORY_USAGE = ClassSize.estimateBaseFromCatalog( BigDecimal.class);

    /**
     * Static cache containing quotient+remainder for 2^(32*N),... by 10^9
     * and methods for fast SQLDecimal.toString conversions.
     * <p>
     * The conversion routine divides the entire BigInteger value represented
     * as base2 int[] (not using long[] since we then need to multiply
     * long*long which can overflow long) by 10^9, writes remainder and stores
     * back quotient, then again by 10^9, writes the remainder and so on.
     * Using 10^9 since it is the max power of 10 that can fit in an integer.
     * <p>
     * All storage of in[] is done in big-endian format (i.e. most significant
     * integer is at 0th position) to be consistent with BigInteger and to be
     * able to use its internal int[] "magnitude" for string conversion.
     */
    static final class Base10Conversion {
      static final double LOG10_2;
      /**
       * max number of integers required to represent
       * <code>Limits.DB2_MAX_DECIMAL_PRECISION_SCALE</code> decimal digits
       * (the max decimal precision supported by GemXD) */
      static final int MAX_INT_DIGITS;
      /** quotients of 2^(32*(N-1)), 2^(32*(N-2)),...,2^32 by 10^9 */
      static final long[][] POW2_QUOTIENTS;
      /** remainders of 2^(32*(N-1)), 2^(32*(N-2)),...,2^32 by 10^9 */
      static final int[] POW2_REMAINDERS;
      static final int BILLION_INT = 1000000000;
      static final long MAX_UINT = 0xffffffffL;

      static {
        // number of ints (N) when BigInteger is represented as int[] will be
        //     2^(32*N) = 10^MAX_PRECISION
        //  => N = MAX_PRECISION/(log10(2)*32)
        LOG10_2 = Math.log10(2.0);
        MAX_INT_DIGITS = (int)Math.ceil(Limits.DB2_MAX_DECIMAL_PRECISION_SCALE
            / (LOG10_2 * Integer.SIZE));
        POW2_QUOTIENTS = new long[MAX_INT_DIGITS][];
        POW2_REMAINDERS = new int[MAX_INT_DIGITS];

        final BigInteger billion = BigInteger.valueOf(BILLION_INT);
        for (int index = 0; index < MAX_INT_DIGITS; index++) {
          // get the quotient and remainder of 2^(32*index)/10^9
          // using BigInteger for simplicity and since this is to be done
          // only once
          byte[] mag = new byte[(index * 4) + 1];
          mag[0] = 1; // set the bit left of MSB to get 2^(32*intDigits)
          BigInteger pow2 = new BigInteger(mag);
          BigInteger[] quotientAndRemainder = pow2.divideAndRemainder(billion);
  
          final byte[] qmag = quotientAndRemainder[0].toByteArray();
          POW2_QUOTIENTS[index] = covertIntsToUnsignedLongs(
              convertBytesToIntegerMagnitude(qmag, 0, qmag.length));
          // remainder will be less than billion
          POW2_REMAINDERS[index] = quotientAndRemainder[1].intValue();
        }
      }

      static long[] covertIntsToUnsignedLongs(final int[] a) {
        final int alen = a.length;
        final long[] l = new long[alen];
        for (int index = 0; index < alen; index++) {
          l[index] = (a[index] & MAX_UINT);
        }
        return l;
      }

      /** convert given byte[] +ve magnitude to long */
      static long convertBytesToLong(final byte[] mag, int offset,
          final int endOffset) {
        assert (endOffset - offset) <= 8: "endOffset=" + endOffset + " offset="
            + offset;

        // skip leading zeros
        while (offset < endOffset && mag[offset] == 0) {
          offset++;
        }
        final int numQs = ((endOffset - offset) + 3) >>> 2;
        long result;
        int bend = endOffset, shift;

        // at least one byte will exist which does not need any shifting
        int res = (mag[--bend] & 0xff);
        shift = 8;
        int bstart = Math.max(offset, bend - 3);
        while (bend > bstart) {
          res |= ((mag[--bend] & 0xff) << shift);
          shift += 8;
        }
        result = (res & MAX_UINT);

        if (numQs > 1) {
          // at least one byte will exist which does not need any shifting
          res = (mag[--bend] & 0xff);
          shift = 8;
          bstart = Math.max(offset, bend - 3);
          while (bend > bstart) {
            res |= ((mag[--bend] & 0xff) << shift);
            shift += 8;
          }
          result |= ((res & MAX_UINT) << 32);
        }
        return result;
      }

      /** convert given offheap byte[] +ve magnitude to long */
      static long convertBytesToLong(final UnsafeWrapper unsafe, long memOffset,
          final long memEnd) {
        assert (memEnd - memOffset) <= 8: "memEnd=" + memEnd + " memOffset="
            + memOffset;

        // skip leading zeros
        while (memOffset < memEnd && unsafe.getByte(memOffset) == 0) {
          memOffset++;
        }
        final int numQs = ((int)(memEnd - memOffset) + 3) >>> 2;
        long result;
        long bend = memEnd;
        int shift;

        // at least one byte will exist which does not need any shifting
        int res = (unsafe.getByte(--bend) & 0xff);
        shift = 8;
        long bstart = Math.max(memOffset, bend - 3);
        while (bend > bstart) {
          res |= ((unsafe.getByte(--bend) & 0xff) << shift);
          shift += 8;
        }
        result = (res & MAX_UINT);

        if (numQs > 1) {
          // at least one byte will exist which does not need any shifting
          res = (unsafe.getByte(--bend) & 0xff);
          shift = 8;
          bstart = Math.max(memOffset, bend - 3);
          while (bend > bstart) {
            res |= ((unsafe.getByte(--bend) & 0xff) << shift);
            shift += 8;
          }
          result |= ((res & MAX_UINT) << 32);
        }
        return result;
      }

      /** convert given byte[] +ve magnitude to int[] in little-endian format */
      static int[] convertBytesToIntegerMagnitude(final byte[] mag, int offset,
          final int endOffset) {
        // skip leading zeros
        while (offset < endOffset && mag[offset] == 0) {
          offset++;
        }
        final int numQs = ((endOffset - offset) + 3) >>> 2;
        final int[] result = new int[numQs];
        int bend = endOffset, shift;

        for (int i = 0; i < numQs; i++) {
          // at least one byte will exist which does not need any shifting
          int res = (mag[--bend] & 0xff);
          shift = 8;
          final int bstart = Math.max(offset, bend - 3);
          while (bend > bstart) {
            res |= ((mag[--bend] & 0xff) << shift);
            shift += 8;
          }
          result[i] = res;
        }
        return result;
      }

      /**
       * convert given offheap byte[] +ve magnitude to int[] in little-endian
       * format
       */
      static int[] convertBytesToIntegerMagnitude(final UnsafeWrapper unsafe,
          long memOffset, final long memEnd) {
        // skip leading zeros
        while (memOffset < memEnd && unsafe.getByte(memOffset) == 0) {
          memOffset++;
        }
        final int numQs = ((int)(memEnd - memOffset) + 3) >>> 2;
        final int[] result = new int[numQs];
        long bend = memEnd;
        int shift;

        for (int i = 0; i < numQs; i++) {
          // at least one byte will exist which does not need any shifting
          int res = (unsafe.getByte(--bend) & 0xff);
          shift = 8;
          final long bstart = Math.max(memOffset, bend - 3);
          while (bend > bstart) {
            res |= ((unsafe.getByte(--bend) & 0xff) << shift);
            shift += 8;
          }
          result[i] = res;
        }
        return result;
      }

      /**
       * Divide given big-integer in big-endian format by billion and
       * return the remainder. Quotient is stored back into the passed
       * "result" array.
       * <p>
       * Result array must have at least maglen size. Indexes which are not
       * affected in the result will not be touched, so caller has to take
       * care of clearing the array if required.
       */
      static long divideByBillion(final int[] mag, final int maglen,
          int[] result) {
        // already have quotients+remainders of 2^(32*N) by billion, so
        // multiply the quotient and remainder by the corresponding integer
        // in "mag" and keep collecting the results + carry over as required

        if (maglen > 2 || (maglen == 2 && mag[1] < 0)) {
          final long[][] POW2_QUOTIENTS_ = POW2_QUOTIENTS;
          final int[] POW2_REMAINDERS_ = POW2_REMAINDERS;
          // start from the most significant integer and keep shifting to left
          int remainder = 0, resultLen = 0;
          boolean resultInitialized = false;
          int i = maglen;
          while (--i >= 0) {
            final long v = (mag[i] & MAX_UINT);
            // remainder of < billion * (unsigned int max) will be < signed long
            final long r = (POW2_REMAINDERS_[i] * v);
            // collect into remainder which itself can exceed billion so will
            // take remainder of that later
            remainder += (r % BILLION_INT);
  
            // next multiply quotient of (2^N/10^9) by "v"
            final long[] pow2q = POW2_QUOTIENTS_[i];
            // carry cannot exceed UINT_MAX; start with leftover of r+remainder
            long carry = (r / BILLION_INT);
            if (remainder >= BILLION_INT) {
              carry += (remainder / BILLION_INT);
              remainder %= BILLION_INT;
            }
            final int pow2qLen = pow2q.length;
            // Start multiplication from LSB, carrying over to next digits like
            // the basic multiplication. Don't need to use advanced algorithms
            // like Karatsuba or Schonhage since those provide a benefit only
            // when the number of digits in both big-integers is large while
            // here it will be restricted to a single digit
            if (resultInitialized) {
              for (int j = 0; j < pow2qLen; j++) {
                // below can overflow signed long but not unsigned long
                // ULONG_MAX >= (UINT_MAX * UINT_MAX + UINT_MAX + UINT_MAX)
                final long m = (v * pow2q[j]) + carry + (result[j] & MAX_UINT);
                result[j] = (int)m;
                carry = (m >>> 32);
              }
              if (carry != 0) {
                result[pow2qLen] += (int)carry;
              }
            }
            else {
              for (int j = 0; j < pow2qLen; j++) {
                // below can overflow signed long but not unsigned long
                // ULONG_MAX > (UINT_MAX * UINT_MAX + UINT_MAX)
                final long m = (v * pow2q[j]) + carry;
                result[j] = (int)m;
                carry = (m >>> 32);
              }
              // first round has the MSBs, so will determine resultLen
              if (carry != 0) {
                result[pow2qLen] = (int)carry;
                resultLen = (pow2qLen + 1);
              }
              else {
                resultLen = pow2qLen;
              }
              resultInitialized = true;
            }
          }
          return (((long)resultLen << 32) | remainder);
        }
        // optimized paths for maglen=1 and maglen=2
        else if (maglen == 1) {
          final int mag0;
          if ((mag0 = mag[0]) >= 0) {
            if (mag0 >= BILLION_INT) {
              result[0] = (mag0 / BILLION_INT);
              return (1L << 32) | (mag0 % BILLION_INT);
            }
            else {
              return mag0;
            }
          }
          else {
            // convert to unsigned long
            final long v = (mag0 & MAX_UINT);
            result[0] = (int)(v / BILLION_INT);
            return (1L << 32) | (v % BILLION_INT);
          }
        }
        else {
          // don't have unsigned longs in java, so restrict long range to +ve
          final long v = (((long)mag[1]) << 32) | (mag[0] & MAX_UINT);
          final long q = (v / BILLION_INT);
          if (q <= MAX_UINT) {
            result[0] = (int)q;
            return (1L << 32) | (v % BILLION_INT);
          }
          else {
            result[0] = (int)(q & MAX_UINT);
            result[1] = (int)(q >>> 32);
            return (2L << 32) | (v % BILLION_INT);
          }
        }
      }
    }

    public int estimateMemoryUsage()
    {
        int sz = BASE_MEMORY_USAGE;
        if( null != value)
            sz += BIG_DECIMAL_MEMORY_USAGE + (value.unscaledValue().bitLength() + 8)/8;
        if( null != rawData)
            sz += rawData.length;
        return sz;
    }
// GemStone changes END


	////////////////////////////////////////////////////////////////////
	//
	// CLASS INTERFACE
	//
	////////////////////////////////////////////////////////////////////
	/** no-arg constructor, required by Formattable */
	public SQLDecimal() 
	{
	}

	public SQLDecimal(BigDecimal val)
	{
		value = val;
	}

	public SQLDecimal(BigDecimal val, int nprecision, int scale)
			throws StandardException
	{
		
		value = val;
		if ((value != null) && (scale >= 0))
		{
			value = value.setScale(scale, 
							BigDecimal.ROUND_DOWN);
		}
	}

	public SQLDecimal(String val) 
	{
		value = new BigDecimal(val);
	}

	public SQLDecimal(char[] val) {
	  value = new BigDecimal(val);
	}

	/*
	 * DataValueDescriptor interface
	 * (mostly implemented in DataType)
	 *
	 */


	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public int	getInt() throws StandardException
	{
		if (isNull())
			return 0;

		try {
			long lv = getLong();

			if ((lv >= Integer.MIN_VALUE) && (lv <= Integer.MAX_VALUE))
				return (int) lv;

		} catch (StandardException se) {
		}

		throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER", (String)null);
	}

	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public byte	getByte() throws StandardException
	{
		if (isNull())
			return (byte)0;

		try {
			long lv = getLong();

			if ((lv >= Byte.MIN_VALUE) && (lv <= Byte.MAX_VALUE))
				return (byte) lv;

		} catch (StandardException se) {
		}

		throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT", (String)null);
	}
        
        

	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public short	getShort() throws StandardException
	{
		if (isNull())
			return (short)0;

		try {
			long lv = getLong();

			if ((lv >= Short.MIN_VALUE) && (lv <= Short.MAX_VALUE))
				return (short) lv;

		} catch (StandardException se) {
		}

		throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT", (String)null);
	}

	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public long	getLong() throws StandardException
	{
		BigDecimal localValue = getBigDecimal();
		return getLong(localValue);
	}
        
        static long     getLong(BigDecimal localValue) throws StandardException
        {
                
                if (localValue == null)
                        return (long)0;

                // Valid range for long is
                //   greater than Long.MIN_VALUE - 1
                // *and*
                //   less than Long.MAX_VALUE + 1
                //
                // This ensures that DECIMAL values with an integral value
                // equal to the Long.MIN/MAX_VALUE round correctly to those values.
                // e.g. 9223372036854775807.1  converts to 9223372036854775807
                // this matches DB2 UDB behaviour

                if (   (localValue.compareTo(SQLDecimal.MINLONG_MINUS_ONE) == 1)
                        && (localValue.compareTo(SQLDecimal.MAXLONG_PLUS_ONE) == -1)) {

                        return localValue.longValue();
                }

                throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "BIGINT", (String)null);
        }

	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public float getFloat() throws StandardException
	{
		BigDecimal localValue = getBigDecimal();
		if (localValue == null)
			return (float)0;

		// If the BigDecimal is out of range for the float
		// then positive or negative infinity is returned.
		float value = NumberDataType.normalizeREALAllowNaN(localValue.floatValue());

		return value;
	}

	/**
	 * 
	 * If we have a value that is greater than the maximum double,
	 * exception is thrown.  Otherwise, ok.  If the value is less
	 * than can be represented by a double, ti will get set to
	 * the smallest double value.
	 *
	 * @exception StandardException thrown on failure to convert
	 */
	public double getDouble() throws StandardException
	{
		BigDecimal localValue = getBigDecimal();
		return getDouble(localValue);
	}
        
	static double getDouble(BigDecimal localValue)throws StandardException
	{
	  if (localValue == null)
	    return (double)0.0;

	  // If the BigDecimal is out of range for double
	  // then positive or negative infinity is returned.
	  double value = NumberDataType.normalizeDOUBLEAllowNaN(localValue.doubleValue());
	  return value;
	}

	public final BigDecimal getBigDecimal()	{
// GemStone changes BEGIN
	  if (this.value != null) {
	    return this.value;
	  }
          if (this.rawData != null) {
            this.value = new BigDecimal(new BigInteger(this.rawSig,
                this.rawData), this.rawScale);
            this.rawData = null;
          }
	  /* (original code)
		if ((value == null) && (rawData != null)) 
		{
			value = new BigDecimal(new BigInteger(rawData), rawScale);
		}
	  */
// GemStone changes END

		return value;
	}
	
	/**
	 * DECIMAL implementation. Convert to a BigDecimal using getObject
	 * which will return a BigDecimal
	 */
	public int typeToBigDecimal()
	{
		return java.sql.Types.DECIMAL;
	}

    // 0 or null is false, all else is true
	public boolean	getBoolean()
	{

		BigDecimal localValue = getBigDecimal();
		if (localValue == null)
			return false;

		return localValue.compareTo(ZERO) != 0;
	}

	public String	getString()
	{
// GemStone changes BEGIN
	  final BigDecimal value = this.value;
	  if (value != null) {
	    // try to extract from internal magnitude
	    int scale = value.scale();
	    int signum = value.signum();
	    if (signum != 0) {
	      BigInteger bi = value.unscaledValue();
	      int[] mag = getInternalMagnitude(bi);
	      if (mag != null) {
	        // convert to little-endian and use the optimized toString
	        final int maglen = mag.length;
	        if (maglen > 0) {
	          final int[] rmag = new int[maglen];
	          for (int i = 0; i < maglen; i++) {
	            rmag[maglen - 1 - i] = mag[i];
	          }
	          return getAsString(signum, scale, rmag, null);
	        }
	        else {
	          return getZeroString(scale, null);
	        }
	      }
	    }
	    else {
	      return getZeroString(scale, null);
	    }
	  }
	  final byte[] rawData = this.rawData;
	  if (rawData != null) {
	    return getAsString(rawData, 0, rawData.length,
	        this.rawSig, this.rawScale);
	  }
	  return null;
	}
	/* (original code)
		BigDecimal localValue = getBigDecimal();
		if (localValue == null)
			return null;
		else if (toPlainString == null)
			return localValue.toString();
        else
        {
            // use reflection so we can still compile using JDK1.4
            // if we are prepared to require 1.5 to compile then this can be a direct call
            try {
                return (String) toPlainString.invoke(localValue, null);
            } catch (IllegalAccessException e) {
                // can't happen based on the JDK spec
                throw new IllegalAccessError("toPlainString");
            } catch (InvocationTargetException e) {
                Throwable t = e.getTargetException();
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                } else if (t instanceof Error) {
                    throw (Error) t;
                } else {
                    // can't happen
                    throw new IncompatibleClassChangeError("toPlainString");
                }
            }
        }
	}

    private static final Method toPlainString;
    private static final Method bdPrecision;
    static {
        Method m;
        try {
            m = BigDecimal.class.getMethod("toPlainString", null);
        } catch (NoSuchMethodException e) {
            m = null;
        }
        toPlainString = m;
        try {
            m = BigDecimal.class.getMethod("precision", null);
        } catch (NoSuchMethodException e) {
            m = null;
        }
        bdPrecision = m;
    }
    */
// GemStone changes END

	public Object	getObject()
	{
		/*
		** BigDecimal is immutable
		*/
		return getBigDecimal();
	}

	/**
	 * Set the value from a correctly typed BigDecimal object.
	 * @throws StandardException 
	 */
	void setObject(Object theValue) throws StandardException
	{
		setValue((BigDecimal) theValue);
	}
	
	protected void setFrom(DataValueDescriptor theValue) throws StandardException {

		setCoreValue(SQLDecimal.getBigDecimal(theValue));
	}

	public int	getLength()
	{
		return getDecimalValuePrecision();
	}

	// this is for DataType's error generator
	public String getTypeName()
	{
		return TypeId.DECIMAL_NAME;
	}

	/*
	 * Storable interface, implies Externalizable, TypedFormat
	 */

	/**
		Return my format identifier.

		@see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId
	*/
	public int getTypeFormatId() 
	{
		return StoredFormatIds.SQL_DECIMAL_ID;
	}

	/*
	 * see if the decimal value is null.
	 */
	/** @see Storable#isNull */
	public boolean isNull()
	{
		return (value == null) && (rawData == null);
	}

	/** 
	 * Distill the BigDecimal to a byte array and
	 * write out: <UL>
	 *	<LI> scale (zero or positive) as a byte </LI>
	 *	<LI> length of byte array as a byte</LI>
	 *	<LI> the byte array </LI> </UL>
	 *
	 */
	public void writeExternal(ObjectOutput out) throws IOException 
	{
		// never called when value is null
		if (SanityManager.DEBUG)
			SanityManager.ASSERT(! isNull());

		int scale;
// GemStone changes BEGIN
		int[] mag = null;
		final int signum;
		/* (original code)
		byte[] byteArray;
		*/
// GemStone changes END

		if (value != null) {
			scale = value.scale();
			
			// J2SE 5.0 introduced negative scale value for BigDecimals.
			// In previouse Java releases a negative scale was not allowed
			// (threw an exception on setScale and the constructor that took
			// a scale).
			//
			// Thus the Derby format for DECIMAL implictly assumed a
			// positive or zero scale value, and thus now must explicitly
			// be positive. This is to allow databases created under J2SE 5.0
			// to continue to be supported under JDK 1.3/JDK 1.4, ie. to continue
			// the platform independence, independent of OS/cpu and JVM.
			//
			// If the scale is negative set the scale to be zero, this results
			// in an unchanged value with a new scale. A BigDecimal with a
			// negative scale by definition is a whole number.
			// e.g. 1000 can be represented by:
			//    a BigDecimal with scale -3 (unscaled value of 1)
			// or a BigDecimal with scale 0 (unscaled value of 1000)
			
			if (scale < 0) {			
				scale = 0;
				value = value.setScale(0);
			}

			BigInteger bi = value.unscaledValue();
// GemStone changes BEGIN
			mag = getInternalMagnitude(bi);
			signum = bi.signum();
		}
		else {
		  scale = this.rawScale;
		  signum = this.rawSig;
			/* (original code)
			byteArray = bi.toByteArray();
		} else {
			scale = rawScale;
			byteArray = rawData;
			*/
// GemStone changes END
		}
		
		if (SanityManager.DEBUG)
		{
			if (scale < 0)
				SanityManager.THROWASSERT("DECIMAL scale at writeExternal is negative "
					+ scale + " value " + toString());
		}

		out.writeByte(scale);
// GemStone changes BEGIN
		out.writeByte(signum);
		if (mag != null) {
		  InternalDataSerializer.serializeBigIntMagnitude(mag, out);
		}
		else {
		  InternalDataSerializer.writeByteArray(this.rawData, out);
		}
		/* (original code)
		out.writeByte(byteArray.length);
		out.write(byteArray);
		*/
// GemStone changes END
	}

	/** 
	 * Note the use of rawData: we reuse the array if the
	 * incoming array is the same length or smaller than
	 * the array length.  
	 * 
	 * @see java.io.Externalizable#readExternal 
	 */
	public void readExternal(ObjectInput in) throws IOException 
	{
		// clear the previous value to ensure that the
		// rawData value will be used
		value = null;

// GemStone changes BEGIN
		this.rawScale = in.readByte();
		this.rawSig = in.readByte();
		assert ((int)this.rawSig) == 0 || ((int)this.rawSig) == -1
		    || ((int)this.rawSig) == 1 : ((int)this.rawSig) + " byte=" + this.rawSig; 
		this.rawData = InternalDataSerializer.readByteArray(in);
		/* (original code)
		rawScale = in.readUnsignedByte();
		int size = in.readUnsignedByte();

		/*
		** Allocate a new array if the data to read
		** is larger than the existing array, or if
		** we don't have an array yet.

        Need to use readFully below and NOT just read because read does not
        guarantee getting size bytes back, whereas readFully does (unless EOF).
        *
		if ((rawData == null) || size != rawData.length)
		{
			rawData = new byte[size];
		}
		in.readFully(rawData);
		*/
// GemStone changes END

	}
	public void readExternalFromArray(ArrayInputStream in) throws IOException 
	{
		// clear the previous value to ensure that the
		// rawData value will be used
		value = null;

// GemStone changes BEGIN
		this.rawScale = in.readByte();
		this.rawSig = in.readByte();
		assert ((int)this.rawSig) == 0 || ((int)this.rawSig) == -1
		    || ((int)this.rawSig) == 1 : ((int)this.rawSig) + " byte=" + this.rawSig; 
                this.rawData = InternalDataSerializer.readByteArray(in);
		/* (original code)
		rawScale = in.readUnsignedByte();
		int size = in.readUnsignedByte();

		/*
		** Allocate a new array if the data to read
		** is larger than the existing array, or if
		** we don't have an array yet.

        Need to use readFully below and NOT just read because read does not
        guarantee getting size bytes back, whereas readFully does (unless EOF).
        *
		if ((rawData == null) || size != rawData.length)
		{
			rawData = new byte[size];
		}
		in.readFully(rawData);
		*/
// GemStone changes END
	}

	/**
	 * @see Storable#restoreToNull
	 *
	 */
	public void restoreToNull()
	{
		value = null;
		rawData = null;
	}


	/** @exception StandardException		Thrown on error */
	protected int typeCompare(DataValueDescriptor arg) throws StandardException
	{
		BigDecimal otherValue = SQLDecimal.getBigDecimal(arg);
		return getBigDecimal().compareTo(otherValue);
	}

	/*
	 * DataValueDescriptor interface
	 */

	/**
	 * <B> WARNING </B> clone is a shallow copy
 	 * @see DataValueDescriptor#getClone 
	 */
	public DataValueDescriptor getClone()
	{
		return new SQLDecimal(getBigDecimal());
	}

	/**
	 * @see DataValueDescriptor#getNewNull
	 */
	public DataValueDescriptor getNewNull()
	{
		return new SQLDecimal();
	}

	/** 
	 * @see DataValueDescriptor#setValueFromResultSet 
	 *
	 * @exception SQLException		Thrown on error
	 */
	public void setValueFromResultSet(ResultSet resultSet, int colNumber,
									  boolean isNullable)
		throws SQLException
	{
			value = resultSet.getBigDecimal(colNumber);
			rawData = null;
	}
	/**
		Set the value into a PreparedStatement.

		@exception SQLException Error setting value in PreparedStatement
	*/
	public final void setInto(PreparedStatement ps, int position) throws SQLException {

		if (isNull()) {
			ps.setNull(position, java.sql.Types.DECIMAL);
			return;
		}

		ps.setBigDecimal(position, getBigDecimal());
	}
	
	/**
	 *
	 * <B> WARNING </B> there is no checking to make sure
	 * that theValue doesn't exceed the precision/scale of
	 * the current SQLDecimal.  It is just assumed that the
	 * SQLDecimal is supposed to take the precision/scale of
	 * the BigDecimalized String.
	 *
	 * @exception StandardException throws NumberFormatException
	 *		when the String format is not recognized.
	 */
	public void setValue(String theValue) throws StandardException
	{
		rawData = null;

		if (theValue == null)
		{
			value = null;
		}
		else
		{
		    try 
			{
				theValue = theValue.trim();
		        value = new BigDecimal(theValue);
				rawData = null;
			} catch (NumberFormatException nfe) 
			{
			    throw invalidFormat();
			}
		}
	}

	/**
	 * @see NumberDataValue#setValue
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void setValue(double theValue) throws StandardException
	{
		setCoreValue(NumberDataType.normalizeDOUBLEAllowNaN(theValue));
	}

	/**
	 * @see NumberDataValue#setValue
	 *
	 */
	public void setValue(float theValue)
		throws StandardException
	{
		setCoreValue((double)NumberDataType.normalizeREALAllowNaN(theValue));
	}

	/**
	 * @see NumberDataValue#setValue
	 *
	 */
	public void setValue(long theValue)
	{
		value = BigDecimal.valueOf(theValue);
		rawData = null;
	}

	/**
	 * @see NumberDataValue#setValue
	 *
	 */
	public void setValue(int theValue)
	{
		setValue((long)theValue);
	}

	/**
		Only to be called when the application sets a value using BigDecimal
		through setBigDecimal calls.
	*/
	public void setBigDecimal(Number theValue) throws StandardException
	{
		setCoreValue((BigDecimal) theValue);
	}

	/**
		Called when setting a DECIMAL value internally or from
		through a procedure or function.
		Handles long in addition to BigDecimal to handle
		identity being stored as a long but returned as a DECIMAL.
	*/
	public void setValue(Number theValue) throws StandardException
	{
		if (SanityManager.ASSERT)
		{
			if (theValue != null &&
				!(theValue instanceof java.math.BigDecimal) &&
				!(theValue instanceof java.lang.Long))
				SanityManager.THROWASSERT("SQLDecimal.setValue(Number) passed a " + theValue.getClass());
		}

		if (theValue instanceof BigDecimal || theValue == null)
			setCoreValue((BigDecimal) theValue);
		else
			setValue(theValue.longValue());
	}

	/**
	 * @see NumberDataValue#setValue
	 *
	 */
	public void setValue(boolean theValue)
	{
		setCoreValue(theValue ? ONE : ZERO);
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#typePrecedence */
	public int typePrecedence()
	{
		return TypeId.DECIMAL_PRECEDENCE;
	}
    // END DataValueDescriptor interface

	private void setCoreValue(BigDecimal theValue)
	{
		value = theValue;
		rawData = null;
	}

	private void setCoreValue(double theValue) {
		value = new BigDecimal(Double.toString(theValue));
		rawData = null;
	}

	/**
	 * Normalization method - this method may be called when putting
	 * a value into a SQLDecimal, for example, when inserting into a SQLDecimal
	 * column.  See NormalizeResultSet in execution.
	 * <p>
	 * Note that truncation is allowed on the decimal portion
	 * of a numeric only.	
	 *
	 * @param desiredType	The type to normalize the source column to
	 * @param source		The value to normalize
	 *
	 * @throws StandardException				Thrown for null into
	 *											non-nullable column, and for
	 *											truncation error
	 */
	public void normalize(
				DataTypeDescriptor desiredType,
				DataValueDescriptor source)
						throws StandardException
	{
		int desiredScale = desiredType.getScale();
		int desiredPrecision = desiredType.getPrecision();

		setFrom(source);
		setWidth(desiredPrecision, desiredScale, true);
	}


	/*
	** SQL Operators
	*/


	/**
	 * This method implements the + operator for DECIMAL.
	 *
	 * @param addend1	One of the addends
	 * @param addend2	The other addend
	 * @param result	The result of a previous call to this method, null
	 *					if not called yet
	 *
	 * @return	A SQLDecimal containing the result of the addition
	 *
	 * @exception StandardException		Thrown on error
	 */

	public NumberDataValue plus(NumberDataValue addend1,
							NumberDataValue addend2,
							NumberDataValue result)
				throws StandardException
	{
		if (result == null)
		{
			result = new SQLDecimal();
		}

		if (addend1.isNull() || addend2.isNull())
		{
			result.setToNull();
			return result;
		}

		result.setBigDecimal(SQLDecimal.getBigDecimal(addend1).add(SQLDecimal.getBigDecimal(addend2)));
		return result;
	}

	/**
	 * This method implements the - operator for "decimal - decimal".
	 *
	 * @param left	The value to be subtracted from
	 * @param right	The value to be subtracted
	 * @param result	The result of a previous call to this method, null
	 *					if not called yet
	 *
	 * @return	A SQLDecimal containing the result of the subtraction
	 *
	 * @exception StandardException		Thrown on error
	 */

	public NumberDataValue minus(NumberDataValue left,
							NumberDataValue right,
							NumberDataValue result)
				throws StandardException
	{
		if (result == null)
		{
			result = new SQLDecimal();
		}

		if (left.isNull() || right.isNull())
		{
			result.setToNull();
			return result;
		}

		result.setBigDecimal(SQLDecimal.getBigDecimal(left).subtract(SQLDecimal.getBigDecimal(right)));
		return result;
	}

	/**
	 * This method implements the * operator for "double * double".
	 *
	 * @param left	The first value to be multiplied
	 * @param right	The second value to be multiplied
	 * @param result	The result of a previous call to this method, null
	 *					if not called yet
	 *
	 * @return	A SQLDecimal containing the result of the multiplication
	 *
	 * @exception StandardException		Thrown on error
	 */

	public NumberDataValue times(NumberDataValue left,
							NumberDataValue right,
							NumberDataValue result)
				throws StandardException
	{
		if (result == null)
		{
			result = new SQLDecimal();
		}

		if (left.isNull() || right.isNull())
		{
			result.setToNull();
			return result;
		}

		result.setBigDecimal(SQLDecimal.getBigDecimal(left).multiply(SQLDecimal.getBigDecimal(right)));
		return result;
	}

	/**
	 * This method implements the / operator for BigDecimal/BigDecimal
	 *
	 * @param dividend	The numerator
	 * @param divisor	The denominator
	 * @param result	The result of a previous call to this method, null
	 *					if not called yet
	 *
	 * @return	A SQLDecimal containing the result of the division
	 *
	 * @exception StandardException		Thrown on error
	 */

	public NumberDataValue divide(NumberDataValue dividend,
							 NumberDataValue divisor,
							 NumberDataValue result)
				throws StandardException
	{
		return divide(dividend, divisor, result, -1);
	}

	/**
	 * This method implements the / operator for BigDecimal/BigDecimal
	 *
	 * @param dividend	The numerator
	 * @param divisor	The denominator
	 * @param result	The result of a previous call to this method, null
	 *					if not called yet
	 * @param scale		The result scale, if < 0, calculate the scale according
	 *					to the actual values' sizes
	 *
	 * @return	A SQLDecimal containing the result of the division
	 *
	 * @exception StandardException		Thrown on error
	 */

	public NumberDataValue divide(NumberDataValue dividend,
							 NumberDataValue divisor,
							 NumberDataValue result,
							 int scale)
				throws StandardException
	{
		if (result == null)
		{
			result = new SQLDecimal();
		}

		if (dividend.isNull() || divisor.isNull())
		{
			result.setToNull();
			return result;
		}

		BigDecimal divisorBigDecimal = SQLDecimal.getBigDecimal(divisor);

		if (divisorBigDecimal.compareTo(ZERO) == 0)
		{
			throw  StandardException.newException(SQLState.LANG_DIVIDE_BY_ZERO);
		}
		BigDecimal dividendBigDecimal = SQLDecimal.getBigDecimal(dividend);
		
		/*
		** Set the result scale to be either the passed in scale, whcih was
		** calculated at bind time to be max(ls+rp-rs+1, 4), where ls,rp,rs
		** are static data types' sizes, which are predictable and stable
		** (for the whole result set column, eg.); otherwise dynamically
		** calculates the scale according to actual values.  Beetle 3901
		*/
		result.setBigDecimal(dividendBigDecimal.divide(
									divisorBigDecimal,
									scale > -1 ? scale :
									Math.max((dividendBigDecimal.scale() + 
											SQLDecimal.getWholeDigits(divisorBigDecimal) +
											1), 
										NumberDataValue.MIN_DECIMAL_DIVIDE_SCALE),
									BigDecimal.ROUND_DOWN));
		
		return result;
	}

	/**
	 * This method implements the unary minus operator for double.
	 *
	 * @param result	The result of a previous call to this method, null
	 *					if not called yet
	 *
	 * @return	A SQLDecimal containing the result of the division
	 *
	 * @exception StandardException		Thrown on error
	 */

	public NumberDataValue minus(NumberDataValue result)
									throws StandardException
	{
		if (result == null)
		{
			result = new SQLDecimal();
		}

		if (this.isNull())
		{
			result.setToNull();
			return result;
		}

		result.setBigDecimal(getBigDecimal().negate());
		return result;
	}

    /**
     * This method implements the isNegative method.
     * 
     * @return  A boolean.  If this.value is negative, return true.
     *          For positive values or null, return false.
     */

    protected boolean isNegative()
    {
        return !isNull() && (getBigDecimal().compareTo(ZERO) == -1);
    }
    
	/*
	 * String display of value
	 */
	public String toString()
	{
		if (isNull())
			return "NULL";
		else
			return getString();
	}

	/*
	 * Hash code
	 */
	public int hashCode()
	{
		long longVal;
		BigDecimal localValue = getBigDecimal();

		double doubleVal = (localValue != null) ? localValue.doubleValue() : 0;

		if (Double.isInfinite(doubleVal))
		{
			/*
			 ** This loses the fractional part, but it probably doesn't
			 ** matter for numbers that are big enough to overflow a double -
			 ** it's probably rare for numbers this big to be different only in
			 ** their fractional parts.
			 */
			longVal = localValue.longValue();
		}
		else
		{
			longVal = (long) doubleVal;
			if (longVal != doubleVal)
			{
				longVal = Double.doubleToLongBits(doubleVal);
			}
		}

		return (int) (longVal ^ (longVal >> 32));
	}

	///////////////////////////////////////////////////////////////////////////////
	//
	// VariableSizeDataValue interface
	//
	///////////////////////////////////////////////////////////////////////////////

	/**
	 * Set the precision/scale of the to the desired values. 
	 * Used when CASTing.  Ideally we'd recycle normalize(), but
	 * the use is different.  
	 *
	 * @param desiredPrecision	the desired precision -- IGNORE_PREICISION
	 *					if it is to be ignored.
	 * @param desiredScale	the desired scale 
	 * @param errorOnTrunc	throw error on truncation (ignored -- 
	 *		always thrown if we truncate the non-decimal part of
	 *		the value)
	 *
	 * @exception StandardException		Thrown on non-zero truncation
	 *		if errorOnTrunc is true	
	 */
	public void setWidth(int desiredPrecision, 
			int desiredScale,
			boolean errorOnTrunc)
			throws StandardException
	{
		if (isNull())
			return;
			
		if (desiredPrecision != IGNORE_PRECISION &&
			((desiredPrecision - desiredScale) <  SQLDecimal.getWholeDigits(getBigDecimal())))
		{
			throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, 
									("DECIMAL/NUMERIC("+desiredPrecision+","+desiredScale+") - " +
									    getBigDecimal()), (String)null);
		}
		value = value.setScale(desiredScale, BigDecimal.ROUND_DOWN);
		rawData = null;
	}

	/**
	 * Return the SQL scale of this value, number of digits after the
	 * decimal point, or zero for a whole number. This does not match the
	 * return from BigDecimal.scale() since in J2SE 5.0 onwards that can return
	 * negative scales.
	 */
	public int getDecimalValuePrecision()
	{
		if (isNull())
			return 0;
			
		BigDecimal localValue = getBigDecimal();

		return SQLDecimal.getWholeDigits(localValue) + getDecimalValueScale();
	}

	/**
	 * Return the SQL scale of this value, number of digits after the
	 * decimal point, or zero for a whole number. This does not match the
	 * return from BigDecimal.scale() since in J2SE 5.0 onwards that can return
	 * negative scales.
	 */
	public int getDecimalValueScale()
	{
		if (isNull())
			return 0;
		
		if (value == null)
			return rawScale;
	
		int scale = value.scale();
		if (scale >= 0)
			return scale;
		
		// BigDecimal scale is negative, so number must have no fractional
		// part as its value is the unscaled value * 10^-scale
		return 0;
	}
	
	/**
	 * Get a BigDecimal representing the value of a DataValueDescriptor
	 * @param value Non-null value to be converted
	 * @return BigDecimal value
	 * @throws StandardException Invalid conversion or out of range.
	 */
	public static BigDecimal getBigDecimal(DataValueDescriptor value) throws StandardException
	{
		if (SanityManager.DEBUG)
		{
			if (value.isNull())
				SanityManager.THROWASSERT("NULL value passed to SQLDecimal.getBigDecimal");
		}
		
		switch (value.typeToBigDecimal())
		{
		case Types.DECIMAL:
			return (BigDecimal) value.getObject();
		case Types.CHAR:
			try {
				return new BigDecimal(value.getString().trim());
			} catch (NumberFormatException nfe) {
				throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION, "java.math.BigDecimal", (String)null);
			}
		case Types.BIGINT:
			return BigDecimal.valueOf(value.getLong());
		default:
			if (SanityManager.DEBUG)
				SanityManager.THROWASSERT("invalid return from " + value.getClass() + ".typeToBigDecimal() " + value.typeToBigDecimal());
			return null;
		}
	}

	/**
	 * Calculate the number of digits to the left of the decimal point
	 * of the passed in value.
	 * @param decimalValue Value to get whole digits from, never null.
	 * @return number of whole digits.
	 */
	private static int getWholeDigits(BigDecimal decimalValue)
	{
        /**
         * if ONE > abs(value) then the number of whole digits is 0
         */
        decimalValue = decimalValue.abs();
        if (ONE.compareTo(decimalValue) == 1)
        {
            return 0;
        }
        
// GemStone changes BEGIN
	  return decimalValue.precision() - decimalValue.scale();
	}
	/* (original code)
        if (bdPrecision != null)
		{
	        // use reflection so we can still compile using JDK1.4
			// if we are prepared to require 1.5 to compile then this can be a
			// direct call
			try {
				// precision is the number of digits in the unscaled value,
				// subtracting the scale (positive or negative) will give the
				// number of whole digits.
				int precision = ((Integer) bdPrecision.invoke(decimalValue,
						null)).intValue();
				return precision - decimalValue.scale();
			} catch (IllegalAccessException e) {
				// can't happen based on the JDK spec
				throw new IllegalAccessError("precision");
			} catch (InvocationTargetException e) {
				Throwable t = e.getTargetException();
				if (t instanceof RuntimeException) {
					throw (RuntimeException) t;
				} else if (t instanceof Error) {
					throw (Error) t;
				} else {
					// can't happen
					throw new IncompatibleClassChangeError("precision");
				}
			}
            
		}
   
		String s = decimalValue.toString();
        return (decimalValue.scale() == 0) ? s.length() : s.indexOf('.');
	*/

  static final boolean getBoolean(final BigDecimal bd) {
    if (bd != null) {
      return (bd.compareTo(ZERO) != 0);
    }
    else {
      return false;
    }
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    if (!isNull()) {
      out.writeByte(getTypeId());
      toDataForOptimizedResultHolder(out);
      return;
    }
    this.writeNullDVD(out);
  }

  @Override
  public final void fromDataForOptimizedResultHolder(final DataInput in)
      throws IOException, ClassNotFoundException {
    // clear the previous value to ensure that the rawData value will be used
    this.value = null;

    this.rawScale = in.readByte();
    this.rawSig = in.readByte();
    // assert ((int)this.rawSig) == 0 || ((int)this.rawSig) == -1
    // || ((int)this.rawSig) == 1: ((int)this.rawSig) + " byte=" + this.rawSig;
    this.rawData = InternalDataSerializer.readByteArray(in);
  }

  @Override
  public final void toDataForOptimizedResultHolder(final DataOutput out)
      throws IOException {
    assert !isNull();
    int scale;
    final int signum;
    final int[] mag;
    if (this.value != null) {
      scale = adjustScale();
      final BigInteger bi = this.value.unscaledValue();
      mag = getInternalMagnitude(bi);
      signum = bi.signum();
    }
    else {
      mag = null;
      scale = this.rawScale;
      signum = this.rawSig;
    }

    assert (signum == 0 || signum == -1 || signum == 1): signum + " byte="
        + signum;
    out.writeByte(scale);
    out.writeByte(signum);
    if (mag != null) {
      InternalDataSerializer.serializeBigIntMagnitude(mag, out);
    }
    else {
      InternalDataSerializer.writeByteArray(this.rawData, out);
    }
  }

  /**
   * Return length of this value in bytes.
   */
  @Override
  public int getLengthInBytes(DataTypeDescriptor dtd) {
    if (this.rawData == null) {
      if (this.value != null) {
        adjustScale();
        final int[] mag = getInternalMagnitude(this.value.unscaledValue());
        if (mag != null) {
          return ClientSharedUtils.getBigIntMagnitudeSizeInBytes(mag)
              + 2 /* scale + sig */;
        }
      }
      else {
        return 0; // 0 width for null value
      }
    }
    return this.rawData.length + 2 /* scale + sig */;
  }

  /**
   * Optimized write to a byte array at specified offset
   * 
   * @return number of bytes actually written
   */
  @Override
  public final int writeBytes(final byte[] outBytes, int offset,
      DataTypeDescriptor dtd) {
    // never called when value is null
    assert !isNull();

    final byte scale;
    final byte signum;
    if (this.value != null) {
      scale = (byte)adjustScale();
      final BigInteger bi = this.value.unscaledValue();
      final int[] magnitude = getInternalMagnitude(bi);
      // if we cannot get the internal magnitude efficiently, then get
      // the byte[] and keep it in rawData format
      if (magnitude != null) {
        signum = (byte)bi.signum();
        // first write scale and signum
        outBytes[offset++] = scale;
        outBytes[offset++] = signum;
        return (InternalDataSerializer.serializeBigIntMagnitudeToBytes(
            magnitude, outBytes, offset) + 2);
      }
    }
    assert this.rawData != null;
    final int numBytes = this.rawData.length;
    // first write scale and signum
    outBytes[offset++] = this.rawScale;
    outBytes[offset++] = this.rawSig;
    System.arraycopy(this.rawData, 0, outBytes, offset, numBytes);
    return numBytes + 2;
  }

  /** keep compatible with derby serialization */
  private final int adjustScale() {
    final int scale = this.value.scale();
    if (scale >= 0) {
      return scale;
    }
    this.value = this.value.setScale(0);
    return 0;
  }

  /**
   * Either get the internal int[] magnitude field of BigInteger to avoid byte[]
   * conversion, or if not possible then convert to byte[] and store in
   * rawData() nulling out this.value.
   */
  private final int[] getInternalMagnitude(final BigInteger bi) {
    final int[] magnitude = ClientSharedUtils.getBigIntInternalMagnitude(bi);
    // if we cannot get the internal magnitude efficiently, then get
    // the byte[] and keep it in rawData format
    if (magnitude != null) {
      return magnitude;
    }
    else {
      // assuming scale is already adjusted to zero if required
      assert this.value.scale() >= 0: "unexpected -ve scale";

      this.rawScale = (byte)this.value.scale();
      this.rawSig = (byte)bi.signum();
      assert ((int)this.rawSig) == 0 || ((int)this.rawSig) == -1
          || ((int)this.rawSig) == 1: ((int)this.rawSig) + " byte="
          + this.rawSig;
      this.rawData = bi.abs().toByteArray();
      this.value = null;
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final byte[] inBytes, int offset,
      final int columnWidth) {
    final int numBytes = columnWidth - 2;
    // clear the previous value to ensure that the rawData value will be used
    this.value = null;
    this.rawData = new byte[numBytes];
    this.rawScale = inBytes[offset++];
    this.rawSig = inBytes[offset++];
    System.arraycopy(inBytes, offset, this.rawData, 0, numBytes);
    assert ((int)this.rawSig) == 0 || ((int)this.rawSig) == -1
        || ((int)this.rawSig) == 1: ((int)this.rawSig) + " byte=" + this.rawSig;
    return columnWidth;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(long memOffset, final int columnWidth, ByteSource bs) {
    final int numBytes = columnWidth - 2;
    // clear the previous value to ensure that the rawData value will be used
    this.value = null;
    this.rawData = new byte[numBytes];
    this.rawScale = Platform.getByte(null, memOffset++);
    this.rawSig = Platform.getByte(null, memOffset++);
    UnsafeMemoryChunk.readUnsafeBytes(memOffset, this.rawData, numBytes);
    assert ((int)this.rawSig) == 0 || ((int)this.rawSig) == -1
        || ((int)this.rawSig) == 1: ((int)this.rawSig) + " byte=" + this.rawSig;
    return columnWidth;
  }

  @Override
  public final int computeHashCode(int maxWidth, int hash) {
    // never called when value is null
    assert !isNull();

    final int formatId = getTypeFormatId();
    if (this.value != null) {
      final byte scale = (byte)adjustScale();
      final BigInteger bi = this.value.unscaledValue();
      // compute for scale and signum, then magnitude
      final int[] magnitude = getInternalMagnitude(bi);
      if (magnitude != null) {
        hash = ResolverUtils.addByteToBucketHash(scale, hash, formatId);
        hash = ResolverUtils.addByteToBucketHash((byte)bi.signum(), hash,
            formatId);
        return ResolverUtils.computeHashCode(magnitude, hash, formatId);
      }
    }
    assert this.rawData != null;
    // compute for scale and signum, then magnitude
    hash = ResolverUtils.addByteToBucketHash(this.rawScale, hash, formatId);
    hash = ResolverUtils.addByteToBucketHash(this.rawSig, hash, formatId);
    return ResolverUtils.addBytesToBucketHash(this.rawData, hash, formatId);
  }

  @Override
  public final void setRegionContext(LocalRegion region) {
    // initialize the BigDecimal and clear raw bytes to avoid concurrent get
    // issues since the key will live in the region and will be shared
    final BigDecimal val = this.value;
    if (val == null && this.rawData != null) {
      this.value = new BigDecimal(new BigInteger(this.rawSig, this.rawData),
          this.rawScale);
      this.rawData = null;
    }
  }

  public static final BigDecimal getAsBigDecimal(final byte[] inBytes,
      int offset, final int columnWidth) {
    final int scale, signum;
    scale = inBytes[offset++];
    signum = inBytes[offset++];
    // we need to create intermediate byte[] since the package private
    // constructor of BigInteger that directly takes int[] is only available
    // in Sun JDK >= 1.6.0_20
    final int numBytes = columnWidth - 2;
    final byte[] bytes = new byte[numBytes];
    System.arraycopy(inBytes, offset, bytes, 0, numBytes);
    return new BigDecimal(new BigInteger(signum, bytes), scale);
  }

  static final BigDecimal getAsBigDecimal(long memOffset, final int columnWidth) {
    final int scale, signum;
    scale = Platform.getByte(null, memOffset++);
    signum = Platform.getByte(null, memOffset++);
    // we need to create intermediate byte[] since the package private
    // constructor of BigInteger that directly takes int[] is only available
    // in Sun JDK >= 1.6.0_20
    final int numBytes = columnWidth - 2;
    final byte[] bytes = new byte[numBytes];
    UnsafeMemoryChunk.readUnsafeBytes(memOffset, bytes, numBytes);
    return new BigDecimal(new BigInteger(signum, bytes), scale);
  }

  /**
   * add leading decimal point, zeros (if required),& sign for string conversion
   */
  private static int addDecimalPointAndSign(final int signum, int scale,
      final char[] s, int endPos) {
    if (scale > 0) {
      while (--scale > 0) {
        s[--endPos] = '0';
      }
      s[--endPos] = '.';
      s[--endPos] = '0';
    }
    if (signum < 0) {
      s[--endPos] = '-';
    }
    return endPos;
  }

  /**
   * get the string representation of DECIMALs whose magnitude can be
   * represented as a +ve long
   */
  private static String getAsString(final int signum, int scale, final long v,
      final ByteArrayDataOutput buffer) {
    final int size = Math.max(scale, 19) + 3;
    final char[] s = new char[size];
    int endPos;
    if (scale > 0 && scale < 20) {
      final int decimalPos = size - 1 - scale;
      endPos = DataTypeUtilities.toStringUnsignedWithDecimalPoint(v, s, size,
          decimalPos);
    }
    else {
      endPos = DataTypeUtilities.toStringUnsigned(v, s, size);
    }
    scale -= (size - endPos - 1);

    // add leading zeros and decimal point if required
    endPos = addDecimalPointAndSign(signum, scale, s, endPos);
    if (buffer == null) {
      return new String(s, endPos, size - endPos);
    }
    else {
      buffer.writeBytes(s, endPos, size);
      return null;
    }
  }

  /**
   * get the string representation of DECIMALs whose magnitude is represented as
   * an array of integers in little-endian format (i.e. LSB to MSB)
   */
  private static String getAsString(final int signum, int scale, int[] mag,
      final ByteArrayDataOutput buffer) {
    int maglen = mag.length;
    int[] result = new int[maglen];
    // keep dividing by billion and writing remainders; add the decimal point
    // at the appropriate place
    // max size of string can be precision in decimal digits + decimal point
    // + sign + possible leading zero (in case scale == precision)
    // precision in decimal digits is (number of binary digits) * log10(2.0)
    final int size = Math.max((int)(0.51 + (Base10Conversion.LOG10_2
        * (maglen << 5))), scale) + 3;
    final char[] s = new char[size];
    int endPos = size;

    assert maglen > 0: Integer.toString(maglen);

    while (true) {
      final long lenAndRemainder = Base10Conversion.divideByBillion(mag,
          maglen, result);
      int newEnd = endPos - 9;
      // remainder by billion will fit into int without going negative
      // check if decimal point has to be inserted in this round
      if (scale > 0 && scale < 9) {
        final int decimalPos = endPos - 1 - scale;
        endPos = DataTypeUtilities.toStringUnsignedWithDecimalPoint(
            (int)(lenAndRemainder & Base10Conversion.MAX_UINT), s,
            endPos, decimalPos);
        maglen = (int)(lenAndRemainder >>> 32);
        // check for end of iteration in which case there is no need to
        // prepend zeros to make it 9 digits
        if (maglen == 0) {
          // set scale to remaining places till decimalPos so as to write
          // leading zeros if required
          scale = (endPos - decimalPos);
          break;
        }
        // always write exactly 9 decimal digits for remainder by billion
        // need to check if decimal point lies within the zeros to be added
        newEnd--; // for decimal point
        if (endPos < decimalPos) {
          // decimal point already written
          for (int i = newEnd; i < endPos; i++) {
            s[i] = '0';
          }
        }
        else {
          int i;
          for (i = newEnd; i < decimalPos; i++) {
            s[i] = '0';
          }
          s[decimalPos] = '.';
          for (i++; i < endPos; i++) {
            s[i] = '0';
          }
        }
        // indicate that decimal point has been written
        scale = 0;
      }
      else {
        final int initEndPos = endPos;
        endPos = DataTypeUtilities.toStringUnsigned((int)(lenAndRemainder &
            Base10Conversion.MAX_UINT), s, endPos);
        maglen = (int)(lenAndRemainder >>> 32);
        // check for end of iteration in which case there is no need to
        // prepend zeros to make it 9 digits
        if (maglen == 0) {
          if (scale > 0) {
            // reduce scale appropriately before breaking out
            scale -= (initEndPos - endPos - 1);
          }
          break;
        }
        // always write exactly 9 decimal digits for remainder by billion
        for (int i = newEnd; i < endPos; i++) {
          s[i] = '0';
        }
        if (scale > 0 && (scale -= 9) == 0) {
          // write a leading decimal point
          s[--newEnd] = '.';
        }
      }
      if (endPos > newEnd) {
        endPos = newEnd;
      }
      final int[] tmp = result;
      // swap result and mag for next round
      result = mag;
      mag = tmp;
    }
    // add leading zeros and decimal point if required
    endPos = addDecimalPointAndSign(signum, scale, s, endPos);
    if (buffer == null) {
      return new String(s, endPos, size - endPos);
    }
    else {
      buffer.writeBytes(s, endPos, size);
      return null;
    }
  }

  private static String getZeroString(final int scale,
      final ByteArrayDataOutput buffer) {
    if (scale > 0) {
      final int size = scale + 2;
      final char[] s = new char[size];
      s[0] = '0';
      s[1] = '.';
      for (int i = 2; i < size; i++) {
        s[i] = '0';
      }
      if (buffer == null) {
        return ClientSharedUtils.newWrappedString(s, 0, size);
      }
      else {
        buffer.writeBytes(s, 0, size);
        return null;
      }
    }
    else if (buffer == null) {
      return "0";
    }
    else {
      buffer.write('0');
      return null;
    }
  }

  private static String getAsString(final byte[] inBytes, int offset,
      final int bytesLen, final int signum, final int scale) {
    // use optimized conversion from byte[] to its string format instead of
    // using BigDecimal.toPlainString()

    if (signum != 0 && bytesLen > 0) {

      // optimization for upto 8 bytes that can be stored in +ve long
      if (bytesLen < 8 || (bytesLen == 8 && inBytes[offset] >= 0)) {
        final long v = Base10Conversion.convertBytesToLong(inBytes, offset,
            bytesLen + offset);
        return getAsString(signum, scale, v, null);
      }
      else {
        final int[] mag = Base10Conversion.convertBytesToIntegerMagnitude(
            inBytes, offset, bytesLen + offset);
        return getAsString(signum, scale, mag, null);
      }
    }
    else {
      return getZeroString(scale, null);
    }
  }

  public static String getAsString(final byte[] inBytes, int offset,
      final int columnWidth) {
    // use optimized conversion from byte[] to its string format instead of
    // using BigDecimal.toPlainString()

    final int scale = inBytes[offset++];
    final int signum = inBytes[offset++];
    return getAsString(inBytes, offset, columnWidth - 2, signum, scale);
  }

  static String getAsString(final UnsafeWrapper unsafe, long memOffset,
      final int columnWidth) {
    // use optimized conversion from byte[] to its string format instead of
    // using BigDecimal.toPlainString()
    final long endBytesAddr = memOffset + columnWidth;
    final int scale = unsafe.getByte(memOffset++);
    final int signum = unsafe.getByte(memOffset++);

    if (signum != 0 && columnWidth > 2) {

      // optimization for upto 8 bytes that can be stored in +ve long
      if (columnWidth < 10
          || (columnWidth == 10 && unsafe.getByte(memOffset) >= 0)) {
        final long v = Base10Conversion.convertBytesToLong(unsafe, memOffset,
            endBytesAddr);
        return getAsString(signum, scale, v, null);
      }
      else {
        final int[] mag = Base10Conversion.convertBytesToIntegerMagnitude(
            unsafe, memOffset, endBytesAddr);
        return getAsString(signum, scale, mag, null);
      }
    }
    else {
      return getZeroString(scale, null);
    }
  }

  static void writeAsString(final byte[] inBytes, int offset,
      final int columnWidth, final ByteArrayDataOutput buffer) {
    // use optimized conversion from byte[] to its string format instead of
    // using BigDecimal.toPlainString()

    final int scale = inBytes[offset++];
    final int signum = inBytes[offset++];
    if (signum != 0 && columnWidth > 2) {

      // optimization for upto 8 bytes that can be stored in +ve long
      if (columnWidth < 10 || (columnWidth == 10 && inBytes[offset] >= 0)) {
        final long v = Base10Conversion.convertBytesToLong(inBytes, offset,
            columnWidth - 2 + offset);
        getAsString(signum, scale, v, buffer);
      }
      else {
        final int[] mag = Base10Conversion.convertBytesToIntegerMagnitude(
            inBytes, offset, columnWidth - 2 + offset);
        getAsString(signum, scale, mag, buffer);
      }
    }
    else {
      getZeroString(scale, buffer);
    }
  }

  static void writeAsString(final UnsafeWrapper unsafe, long memOffset,
      final int columnWidth, final ByteArrayDataOutput buffer) {
    // use optimized conversion from byte[] to its string format instead of
    // using BigDecimal.toPlainString()
    final long endBytesAddr = memOffset + columnWidth;
    final int scale = unsafe.getByte(memOffset++);
    final int signum = unsafe.getByte(memOffset++);

    if (signum != 0 && columnWidth > 2) {

      // optimization for upto 8 bytes that can be stored in +ve long
      if (columnWidth < 10
          || (columnWidth == 10 && unsafe.getByte(memOffset) >= 0)) {
        final long v = Base10Conversion.convertBytesToLong(unsafe, memOffset,
            endBytesAddr);
        getAsString(signum, scale, v, buffer);
      }
      else {
        final int[] mag = Base10Conversion.convertBytesToIntegerMagnitude(
            unsafe, memOffset, endBytesAddr);
        getAsString(signum, scale, mag, buffer);
      }
    }
    else {
      getZeroString(scale, buffer);
    }
  }

  @Override
  public byte getTypeId() {
    return DSCODE.DOUBLE_TYPE;
  }
// GemStone changes END
}
