/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.SQLClob

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

import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.RuleBasedCollator;
import java.util.Calendar;


/**
 * SQLClob represents a CLOB value with UCS_BASIC collation.
 * CLOB supports LIKE operator only for collation.
 */
public class SQLClob
	extends SQLVarchar
{
	/*
	 * DataValueDescriptor interface.
	 *
	 * These are actually all implemented in the super-class, but we need
	 * to duplicate some of them here so they can be called by byte-code
	 * generation, which needs to know the class the method appears in.
	 */

	public String getTypeName()
	{
		return TypeId.CLOB_NAME;
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#getClone */
	public DataValueDescriptor getClone()
	{
		try
		{
			return new SQLClob(getString());
		}
		catch (StandardException se)
		{
// GemStone changes BEGIN
		  throw GemFireXDRuntimeException.newRuntimeException(
		      "Unexpected exception", se);
		  /* (original code)
			if (SanityManager.DEBUG)
				SanityManager.THROWASSERT("Unexpected exception", se);
			return null;
		  */
// GemStone changes END
		}
	}

	/**
	 * @see DataValueDescriptor#getNewNull
	 *
	 */
	public DataValueDescriptor getNewNull()
	{
		return new SQLClob();
	}

	/** @see StringDataValue#getValue(RuleBasedCollator) */
	public StringDataValue getValue(RuleBasedCollator collatorForComparison)
	{
		if (collatorForComparison == null)
		{//null collatorForComparison means use UCS_BASIC for collation
		    return this;			
		} else {
			//non-null collatorForComparison means use collator sensitive
			//implementation of SQLClob
		     CollatorSQLClob s = new CollatorSQLClob(collatorForComparison);
		     s.copyState(this);
		     return s;
		}
	}

	/*
	 * Storable interface, implies Externalizable, TypedFormat
	 */

	/**
		Return my format identifier.

		@see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId
	*/
	public int getTypeFormatId() {
		return StoredFormatIds.SQL_CLOB_ID;
	}

	/*
	 * constructors
	 */

	public SQLClob()
	{
	}

	public SQLClob(String val)
	{
		super(val);
	}

	/*
	 * DataValueDescriptor interface
	 */

	/* @see DataValueDescriptor#typePrecedence */
	public int typePrecedence()
	{
		return TypeId.CLOB_PRECEDENCE;
	}

	/*
	** disable conversions to/from most types for CLOB.
	** TEMP - real fix is to re-work class hierachy so
	** that CLOB is towards the root, not at the leaf.
	*/

// GemStone changes BEGIN
	/**
	 * Return length of this value in bytes. This differs from SQLChar's
	 * method in not imposing a max width.
	 */
	@Override
	public int getLengthInBytes(final DataTypeDescriptor dtd)
	    throws StandardException {
	  final char[] data = getCharArray(true);
	  if (data != null) {
	    int strlen = this.rawLength != -1 ? this.rawLength : data.length;
	    final int maxWidth;;
	    if (dtd != null && strlen > (maxWidth = dtd.getMaximumWidth())) {
	      strlen = maxWidth;
	    }
	    int utflen = strlen;
	    for (int index = 0; index < strlen; ++index) {
	      int c = data[index];
	      if ((c >= 0x0001) && (c <= 0x007F)) {
	        // 1 byte for character
	      }
	      else if (c > 0x07FF) {
	        utflen += 2; // 3 bytes for character
	      }
	      else {
	        ++utflen; // 2 bytes for character
	      }
	    }
	    return utflen;
	  }
	  return 0;
	}

	// allow returning String for getObject
	/* (original code)
	public Object	getObject() throws StandardException
	{
		throw dataTypeConversion("java.lang.Object");
	}
	*/
// GemStone changes END

	public boolean	getBoolean() throws StandardException
	{
		throw dataTypeConversion("boolean");
	}

	public byte	getByte() throws StandardException
	{
		throw dataTypeConversion("byte");
	}

	public short	getShort() throws StandardException
	{
		throw dataTypeConversion("short");
	}

	public int	getInt() throws StandardException
	{
		throw dataTypeConversion("int");
	}

	public long	getLong() throws StandardException
	{
		throw dataTypeConversion("long");
	}

	public float	getFloat() throws StandardException
	{
		throw dataTypeConversion("float");
	}

	public double	getDouble() throws StandardException
	{
		throw dataTypeConversion("double");
	}
	public int typeToBigDecimal() throws StandardException
	{
		throw dataTypeConversion("java.math.BigDecimal");
	}
	public byte[]	getBytes() throws StandardException
	{
		throw dataTypeConversion("byte[]");
	}

	public Date	getDate(java.util.Calendar cal) throws StandardException
	{
		throw dataTypeConversion("java.sql.Date");
	}

	public Time	getTime(java.util.Calendar cal) throws StandardException
	{
		throw dataTypeConversion("java.sql.Time");
	}

	public Timestamp	getTimestamp(java.util.Calendar cal) throws StandardException
	{
		throw dataTypeConversion("java.sql.Timestamp");
	}
    
    /**
     * Gets a trace representation of the CLOB for debugging.
     *
     * @return a trace representation of the CLOB.
     */
    public final String getTraceString() throws StandardException {
        // Check if the value is SQL NULL.
        if (isNull()) {
            return "NULL";
        }

        // Check if we have a stream.
        if (getStream() != null) {
            return (getTypeName() + "(" + getStream().toString() + ")");
        }

        return (getTypeName() + "(" + getLength() + ")");
    }
    
    /**
     * Normalization method - this method may be called when putting
     * a value into a SQLClob, for example, when inserting into a SQLClob
     * column.  See NormalizeResultSet in execution.
     * Per the SQL standard ,if the clob column is not big enough to 
     * hold the value being inserted,truncation error will result
     * if there are trailing non-blanks. Truncation of trailing blanks
     * is allowed.
     * @param desiredType   The type to normalize the source column to
     * @param sourceValue   The value to normalize
     *
     *
     * @exception StandardException             Thrown for null into
     *                                          non-nullable column, and for
     *                                          truncation error
     */

    public void normalize(
                DataTypeDescriptor desiredType,
                DataValueDescriptor sourceValue)
                    throws StandardException
    {
        // if sourceValue is of type clob, and has a stream,
        // dont materialize it here (as the goal of using a stream is to
        // not have to materialize whole object in memory in the server), 
        // but instead truncation checks will be done when data is streamed in.
        // (see ReaderToUTF8Stream) 
        // if sourceValue is not a stream, then follow the same
        // protocol as varchar type for normalization
// GemStone changes BEGIN
        // TODO: SW: right now commenting this out since we do deserialize
        // the stream into memory and this causes the same stream to be read
        // twice in case of bulk DMLs
        /* (original code)
        if( sourceValue instanceof SQLClob)
        {
            SQLClob clob = (SQLClob)sourceValue;
            if (clob.stream != null)
            {
                copyState(clob);
                return;
            }
        }
        */
// GemStone changes END
        
        super.normalize(desiredType,sourceValue);
    }

	public void setValue(Time theValue, Calendar cal) throws StandardException
	{
		throwLangSetMismatch("java.sql.Time");
	}
	
	public void setValue(Timestamp theValue, Calendar cal) throws StandardException
	{
		throwLangSetMismatch("java.sql.Timestamp");
	}
	
	public void setValue(Date theValue, Calendar cal) throws StandardException
	{
		throwLangSetMismatch("java.sql.Date");
	}
	
	public void setBigDecimal(Number bigDecimal) throws StandardException
	{
		throwLangSetMismatch("java.math.BigDecimal");
	}

	public void setValue(int theValue) throws StandardException
	{
		throwLangSetMismatch("int");
	}

	public void setValue(double theValue) throws StandardException
	{
		throwLangSetMismatch("double");
	}

	public void setValue(float theValue) throws StandardException
	{
		throwLangSetMismatch("float");
	}
 
	public void setValue(short theValue) throws StandardException
	{
		throwLangSetMismatch("short");
	}

	public void setValue(long theValue) throws StandardException
	{
		throwLangSetMismatch("long");
	}


	public void setValue(byte theValue) throws StandardException
	{
		throwLangSetMismatch("byte");
	}

	public void setValue(boolean theValue) throws StandardException
	{
		throwLangSetMismatch("boolean");
	}

	public void setValue(byte[] theValue) throws StandardException
	{
		throwLangSetMismatch("byte[]");
	}
    
    /**
     * Set the value from an non-null Java.sql.Clob object.
     */
    void setObject(Object theValue)
        throws StandardException
    {
        Clob vc = (Clob) theValue;
        
        try {
            long vcl = vc.length();
            if (vcl < 0L || vcl > Integer.MAX_VALUE)
                throw this.outOfRange();
            
            setValue(new ReaderToUTF8Stream(vc.getCharacterStream(),
                    (int) vcl, 0, TypeId.CLOB_NAME), (int) vcl);
            
        } catch (SQLException e) {
            throw dataTypeConversion("DAN-438-tmp");
       }
    }

// GemStone changes BEGIN

    /**
     * Set the value of this DataValueDescriptor from given Clob.
     *
     * @param clob The Clob value to set this DataValueDescriptor to
     */
    public void setValue(Clob clob) throws StandardException {
      try {
        long len = clob.length();
        if (len >= 0L && len < Integer.MAX_VALUE) {
          setValue(new ReaderToUTF8Stream(clob.getCharacterStream(),
              (int)len, 0, TypeId.CLOB_NAME), (int)len);
        } else {
          throw this.outOfRange();
        }
      } catch (SQLException e) {
        throw dataTypeConversion("java.sql.Clob");
      }
    }

    @Override
    public byte getTypeId() {
      return DSCODE.HUGE_STRING_BYTES;
    }
  
    @Override
    public String toString() {
      // avoid creating huge strings in logging, for example
      final StringBuilder sb = new StringBuilder();
      sb.append("SQLClob@0x").append(
          Integer.toHexString(System.identityHashCode(this)));
      try {
        sb.append(";length=").append(getLength());
      } catch (StandardException se) {
        // ignore
      }
      try {
        final char[] chars = getCharArray(true);
        if (chars != null) {
          sb.append(";hash=").append(
              ResolverUtils.addCharsToHash(chars, 0, chars.length, 0));
        }
        else {
          sb.append(";chars is null");
        }
      } catch (StandardException se) {
        // ignore
      }
      return sb.toString();
    }
// GemStone changes END
}
