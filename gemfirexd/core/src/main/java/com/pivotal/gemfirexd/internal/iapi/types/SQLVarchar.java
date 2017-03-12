/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.SQLVarchar

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

import java.text.RuleBasedCollator;

import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

/**
 * SQLVarchar represents a VARCHAR value with UCS_BASIC collation.
 *
 * SQLVarchar is mostly the same as SQLChar, so it is implemented as a
 * subclass of SQLChar.  Only those methods with different behavior are
 * implemented here.
 */
public class SQLVarchar
	extends SQLChar
{

	/*
	 * DataValueDescriptor interface.
	 *
	 */

	public String getTypeName()
	{
		return TypeId.VARCHAR_NAME;
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#getClone */
	public DataValueDescriptor getClone()
	{
		try
		{
			return new SQLVarchar(getString());
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
// GemStone changes EN
		}
	}

	/**
	 * @see DataValueDescriptor#getNewNull
	 *
	 */
	public DataValueDescriptor getNewNull()
	{
		return new SQLVarchar();
	}

	/** @see StringDataValue#getValue(RuleBasedCollator) */
	public StringDataValue getValue(RuleBasedCollator collatorForComparison)
	{
		if (collatorForComparison == null)
		{//null collatorForComparison means use UCS_BASIC for collation
		    return this;			
		} else {
			//non-null collatorForComparison means use collator sensitive
			//implementation of SQLVarchar
		     CollatorSQLVarchar s = new CollatorSQLVarchar(collatorForComparison);
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
		return StoredFormatIds.SQL_VARCHAR_ID;
	}

	/*
	 * constructors
	 */

	public SQLVarchar()
	{
	}

	public SQLVarchar(String val)
	{
		super(val);
	}

	/**
	 * Normalization method - this method may be called when putting
	 * a value into a SQLVarchar, for example, when inserting into a SQLVarchar
	 * column.  See NormalizeResultSet in execution.
	 *
	 * @param desiredType	The type to normalize the source column to
	 * @param source		The value to normalize
	 *
	 *
	 * @exception StandardException				Thrown for null into
	 *											non-nullable column, and for
	 *											truncation error
	 */

	public void normalize(
				DataTypeDescriptor desiredType,
				DataValueDescriptor source)
					throws StandardException
	{
		normalize(desiredType, source.getString());
	}

	protected void normalize(DataTypeDescriptor desiredType, String sourceValue)
		throws StandardException
	{

		int			desiredWidth = desiredType.getMaximumWidth();

		int sourceWidth = sourceValue.length();

		/*
		** If the input is already the right length, no normalization is
		** necessary.
		**
		** It's OK for a Varchar value to be shorter than the desired width.
		** This can happen, for example, if you insert a 3-character Varchar
		** value into a 10-character Varchar column.  Just return the value
		** in this case.
		*/

		if (sourceWidth > desiredWidth) {

			hasNonBlankChars(sourceValue, desiredWidth, sourceWidth);

			/*
			** No non-blank characters will be truncated.  Truncate the blanks
			** to the desired width.
			*/
			sourceValue = sourceValue.substring(0, desiredWidth);
		}

		setValue(sourceValue);
	}


	/*
	 * DataValueDescriptor interface
	 */

	/* @see DataValueDescriptor#typePrecedence */
	public int typePrecedence()
	{
		return TypeId.VARCHAR_PRECEDENCE;
	}
    
    /**
     * returns the reasonable minimum amount by 
     * which the array can grow . See readExternal. 
     * when we know that the array needs to grow by at least
     * one byte, it is not performant to grow by just one byte
     * instead this amount is used to provide a resonable growby size.
     * @return minimum reasonable growby size
     */
    protected final int growBy()
    {
        return RETURN_SPACE_THRESHOLD;  //seems reasonable for a varchar or clob 
    }

// GemStone changes BEGIN

  @Override
  public byte getTypeId() {
    return DSCODE.STRING;
  }

  static final String getAsString(final byte[] inBytes, final int offset,
      final int columnWidth) {
    final char[] chars = new char[columnWidth];
    final int strlen = readIntoCharsFromByteArray(inBytes, offset, columnWidth, chars);
    if (columnWidth == strlen) {
      // don't make a copy
      return ClientSharedUtils.newWrappedString(chars, 0, strlen);
    }
    else {
      // trim to required length
      assert columnWidth > strlen: "width=" + columnWidth + ", len=" + strlen;
      final char[] trimmed = new char[strlen];
      System.arraycopy(chars, 0, trimmed, 0, strlen);
      return ClientSharedUtils.newWrappedString(trimmed, 0, strlen);
    }
  }

  static final String getAsString(final long memOffset, final int columnWidth,
			final OffHeapByteSource bs) {
    final char[] chars = new char[columnWidth];
    final int strlen = readIntoCharsFromByteSource(UnsafeHolder.getUnsafe(),
				memOffset, columnWidth, bs, chars);
    if (columnWidth == strlen) {
      // don't make a copy
      return ClientSharedUtils.newWrappedString(chars, 0, strlen);
    }
    else {
      // trim to required length
      assert columnWidth > strlen: "width=" + columnWidth + ", len=" + strlen;
      final char[] trimmed = new char[strlen];
      System.arraycopy(chars, 0, trimmed, 0, strlen);
      return ClientSharedUtils.newWrappedString(trimmed, 0, strlen);
    }
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    // never called when value is null
    assert !isNull();
    final char[] data;
    try {
      data = getCharArray(true);
    } catch (StandardException se) {
      throw GemFireXDRuntimeException.newRuntimeException("unexpected exception",
          se);
    }
    int strlen = this.rawLength != -1 ? this.rawLength : data.length;
    int index = strlen - 1;
    while (index >= 0 && data[index] == ' ') {
      index--;
    }
    hash = ResolverUtils.getComputeHashOfCharArrayData(hash, index + 1, data,
        getTypeFormatId());
    return hash;
  }
// GemStone changes END
}
