/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.SQLBlob

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

import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.reference.MessageId;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import io.snappydata.thrift.common.BufferedBlob;

import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;

/**
 * SQLBlob satisfies the DataValueDescriptor,
 * interfaces (i.e., OrderableDataType). 
 * It uses the SQLLongVarbit implementation, which implements a String holder,
 * e.g. for storing a column value; it can be specified
 * when constructed to not allow nulls. Nullability cannot be changed
 * after construction.
 * <p>
 * Because LOB types are not orderable, we'll override those
 * methods...
 *
 */
public class SQLBlob extends SQLBinary
{

	/*
	 * constructors
	 */
	public SQLBlob()
        {
        }

// GemStone changes BEGIN
	private boolean wrapBytes;

	public final void setWrapBytesForSQLBlob(boolean wrapBytes) {
	  this.wrapBytes = wrapBytes;
	}
// GemStone changes END
	public SQLBlob(byte[] val)
        {
			super(val);
        }
	
	public SQLBlob(Blob val) throws StandardException
        {
			super(val);
        }
	
	public String getTypeName()
        {
			return TypeId.BLOB_NAME;
        }

	/**
	 * Return max memory usage for a SQL Blob
	 */
	int getMaxMemoryUsage()
	{
		return Limits.DB2_LOB_MAXWIDTH;
	}

    /**
     * @see DataValueDescriptor#getNewNull
     */
	public DataValueDescriptor getNewNull()
        {
			return new SQLBlob();
        }

     /**
      * Return a JDBC Blob. Originally implemented to support DERBY-2201.
      */
    public Object getObject()
        throws StandardException
    {
        // the generated code for the DERBY-2201 codepath expects to get a Blob
        // back.
        if ( _blobValue != null ) { return _blobValue; }
        else
        {
            byte[] bytes = getBytes();

            if ( bytes == null ) { return null; }
            else
            {
// GemStone changes BEGIN
              return this.wrapBytes ? HarmonySerialBlob.wrapBytes(bytes)
                  : new HarmonySerialBlob(bytes);
              /* (original code)
                try {
                    return new HarmonySerialBlob( bytes );
                } catch (SQLException se)
                {
                    throw StandardException.plainWrapException( se );
                }
              */
// GemStone changes END
            }
        }
    }
    
	/**
	 * Normalization method - this method may be called when putting
	 * a value into a SQLBit, for example, when inserting into a SQLBit
	 * column.  See NormalizeResultSet in execution.
	 *
	 * @param desiredType	The type to normalize the source column to
	 * @param source		The value to normalize
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
		setValue(source);
		setWidth(desiredType.getMaximumWidth(), 0, true);
	}

    // The method setWidth is only(?) used to adopt the value
    // to the casted domain/size. BLOBs behave different
    // from the BIT types in that a (CAST (X'01' TO BLOB(1024)))
    // does NOT pad the value to the maximal allowed datasize.
    // That it is done for BIT is understandable, however,
    // for BIT VARYING it is a bit confusing. Could be inheritence bug.
    // Anyhow, here we just ignore the call, since there is no padding to be done.
    // We do detect truncation, if the errorOnTrunc flag is set.
    // DB2 does return a WARNING on CAST and ERROR on INSERT.
	public void setWidth(int desiredWidth,  // ignored!
			int desiredScale,	// Ignored 
			boolean errorOnTrunc)
			throws StandardException
    {

		// Input is null, so there's nothing to do.
		if (isNull())
			return;

		// Input is a stream with unknown length. The length will be checked
		// while reading the stream.
		if (isLengthLess()) {
			return;
		}

		int sourceWidth = getLength();

        // need to truncate?
        if (sourceWidth > desiredWidth) {
            if (errorOnTrunc)
                throw StandardException.newException(SQLState.LANG_STRING_TRUNCATION, getTypeName(),
// GemStone changes BEGIN
                                                     MessageService.getTextMessage(
                                                         MessageId.BINARY_DATA_HIDDEN,
                                                         String.valueOf(sourceWidth)),
                                                     /* (original code)
                                                     "XXXX",
                                                     */
// GemStone changes END
                                                     String.valueOf(desiredWidth));
            else {
                /*
                 * Truncate to the desired width.
                 */
				

				byte[] shrunkData = new byte[desiredWidth];
				System.arraycopy(getBytes(), 0, shrunkData, 0, desiredWidth);
				dataValue = shrunkData;
            }
        }
    }

    /**
	   Return my format identifier.
           
	   @see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId
	*/
	public int getTypeFormatId()
        {
			return StoredFormatIds.SQL_BLOB_ID;
        }

	/** 
	 * @see DataValueDescriptor#setValueFromResultSet 
	 *
	 * @exception SQLException		Thrown on error
	 * @throws StandardException 
	 */
	public void setValueFromResultSet(ResultSet resultSet, int colNumber,
									  boolean isNullable)
		throws SQLException, StandardException
	{
        Blob blob = resultSet.getBlob(colNumber);
        if (blob == null)
            setToNull();
        else
            setObject(blob);
	}



	/*
	 * DataValueDescriptor interface
	 */
        
	/** @see DataValueDescriptor#typePrecedence */
	public int typePrecedence()
		{
			return TypeId.BLOB_PRECEDENCE; // not really used
		}

    public void setInto(PreparedStatement ps, int position)
		throws SQLException, StandardException
	{
		if (isNull()) {
			ps.setBlob(position, (Blob)null);    
			return;
		}
		if (_blobValue instanceof BufferedBlob) {
			ps.setBlob(position, _blobValue);
			return;
		}

		// This may cause problems for streaming blobs, by materializing the whole blob.
		ps.setBytes(position, getBytes());
    }
    
    /**
     * Set the value from an non-null object.
     */
    final void setObject(Object theValue)
        throws StandardException
    {
// GemStone changes BEGIN
      if (theValue instanceof byte[]) {
        setValue((byte[])theValue);
        return;
      }
      if (theValue instanceof BufferedBlob) {
        setValue((BufferedBlob)theValue);
        return;
      }
// GemStone changes END
        Blob vb = (Blob) theValue;
        
        try {
            long vbl = vb.length();
            if (vbl < 0L || vbl > Integer.MAX_VALUE)
                throw this.outOfRange();
            
            setValue(new RawToBinaryFormatStream(
                    vb.getBinaryStream(), (int) vbl),
                    (int) vbl);
            
        } catch (SQLException e) {
            throw dataTypeConversion("DAN-438-tmp");
       }
    }

    /**
     * Tell if this blob is length less.
     *
     * @return <code>true</code> if the length of the blob is not known,
     *      <code>false</code> otherwise
     */
    private final boolean isLengthLess() {
        return false;//(stream != null && streamValueLength < 0);
    } 
// GemStone changes BEGIN

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final byte[] inBytes, int offset,
      final int columnWidth) {
    assert columnWidth == inBytes.length: "columnWidth=" + columnWidth
        + ", bytesLength=" + inBytes.length;
    assert offset == 0;
    this.dataValue = inBytes;
    return columnWidth;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(long memOffset,
      final int columnWidth, final ByteSource bs) {
    final OffHeapByteSource obs = (OffHeapByteSource)bs;
    assert columnWidth == obs.getLength(): "columnWidth=" + columnWidth
        + ", bytesLength=" + obs.getLength();
    assert memOffset == obs.getUnsafeAddress(0, columnWidth);
    this.dataValue = obs.getRowBytes();
    return columnWidth;
  }

  /**
   * Optimized write to a byte array at specified offset
   *
   * @return number of bytes actually written
   */
  @Override
  public int writeBytes(byte[] outBytes, int offset, DataTypeDescriptor dtd) {
    // for BLOBs, this method is never called, getBytes() is called instead
    throw new AssertionError("unexpected call, expected getBytes() to be called instead");
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    assert !isNull();
    return ResolverUtils.addBytesToBucketHash(this.dataValue, hash,
        getTypeFormatId());
  }

  @Override
  public String toString() {
    // avoid creating huge strings in logging, for example
    final StringBuilder sb = new StringBuilder();
    sb.append("SQLBlob@0x").append(
        Integer.toHexString(System.identityHashCode(this)));
    try {
      sb.append(";length=").append(getLength());
    } catch (StandardException se) {
      // ignore
    }
    try {
      final byte[] bytes = getBytes();
      if (bytes != null) {
        sb.append(";hash=").append(
            ResolverUtils.addBytesToHash(bytes, 0, bytes.length, 0));
      }
      else {
        sb.append(";bytes is null");
      }
    } catch (StandardException se) {
      // ignore
    }
    return sb.toString();
  }
// GemStone changes END
}


