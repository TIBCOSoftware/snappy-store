/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.SQLBinary

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

// GemStone changes BEGIN
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
// GemStone changes END
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.MessageId;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.io.DerbyIOException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatIdInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import io.snappydata.thrift.common.BufferedBlob;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.ObjectOutput;
import java.io.ObjectInput;
import java.io.IOException;
import java.io.InputStream;

import java.sql.Blob;
import java.sql.SQLException;
import java.sql.PreparedStatement;

/**
 * SQLBinary is the abstract class for the binary datatypes.
 * <UL>
 * <LI> CHAR FOR BIT DATA
 * <LI> VARCHAR FOR BIT DATA
 * <LI> LONG VARCHAR
 * <LI> BLOB
 * </UL>

  <P>
  Format : <encoded length><raw data>
  <BR>
  Length is encoded to support Cloudscape 5.x databases where the length was stored as the number of bits.
  The first bit of the first byte indicates if the format is an old (Cloudscape 5.x) style or a new Derby style.
  Derby then uses the next two bits to indicate how the length is encoded.
  <BR>
  <encoded length> is one of N styles.
  <UL>
  <LI> (5.x format zero) 4 byte Java format integer value 0 - either <raw data> is 0 bytes/bits  or an unknown number of bytes.
  <LI> (5.x format bits) 4 byte Java format integer value >0 (positive) - number of bits in <raw data>, number of bytes in <raw data>
  is the minimum number of bytes required to store the number of bits.
  <LI> (Derby format) 1 byte encoded length (0 <= L <= 31) - number of bytes of <raw data> - encoded = 0x80 & L
  <LI> (Derby format) 3 byte encoded length (32 <= L < 64k) - number of bytes of <raw data> - encoded = 0xA0 <L as Java format unsigned short>
  <LI> (Derby format) 5 byte encoded length (64k <= L < 2G) - number of bytes of <raw data> - encoded = 0xC0 <L as Java format integer>
  <LI> (future) to be determined L >= 2G - encoded 0xE0 <encoding of L to be determined>
  (0xE0 is an esacape to allow any number of arbitary encodings in the future).
  </UL>
  <BR>
  When the value was written from a byte array the Derby encoded byte
  length format was always used from Derby 10.0 onwards (ie. all open
  source versions).
  <BR>
  When the value was written from a stream (e.g. PreparedStatement.setBinaryStream)
  then the Cloudscape '5.x format zero' was used by 10.0 and 10.1.
  The was due to the class RawToBinaryFormatStream always writing
  four zero bytes for the length before the data.
  <BR>
  The Cloudscape '5.x format bits' format I think was never used by Derby.
 */
abstract class SQLBinary
	extends DataType implements BitDataValue
{

	static final byte PAD = (byte) 0x20;

    private static final int BASE_MEMORY_USAGE = ClassSize.estimateBaseFromCatalog( SQLBinary.class);

    public int estimateMemoryUsage()
    {
        if (dataValue == null) {
          return BASE_MEMORY_USAGE;
          /*
            if (streamValueLength>=0) {
                return BASE_MEMORY_USAGE + streamValueLength;
            } else {
                return getMaxMemoryUsage();
            }
          */
        } else {
            return BASE_MEMORY_USAGE + dataValue.length;
        }
    } // end of estimateMemoryUsage
	  
	  
	/**
	 * Return max memory usage for a SQL Binary
	 */
	abstract int getMaxMemoryUsage();

	 /*
	 * value as a blob
	 */
    Blob _blobValue;
    
	 /*
	 * object state
	 */
	byte[] dataValue;

// GemStone changes BEGIN
	// now we always store the stream upfront else it tries to re-read
	// in HA retries for example
	// TODO: optimize this by reading it once on first access and storing
	// somewhere in temp area, for example
	/* (original code)
	/**
	 * Value as a stream, this stream represents the on-disk
     * format of the value. That is it has length information
     * encoded in the first fe bytes.
	 *
	InputStream stream;

	/**
		Length of the value in bytes when this value
        is set as a stream. Represents the length of the
        value itself and not the length of the stream
        which contains this length encoded as the first
        few bytes. If the value of the stream is unknown
        then this will be set to -1. If this value is
        not set as a stream then this value should be ignored.
	*
	int streamValueLength;
        */
// GemStone changes END

	/**
		Create a binary value set to NULL
	*/
	SQLBinary()
	{
	}

	SQLBinary(byte[] val)
	{
		dataValue = val;
	}

	SQLBinary(Blob val) throws StandardException
	{
		setValue( val );
	}

   

	public final void setValue(byte[] theValue)
	{
		dataValue = theValue;
        _blobValue = null;
		//stream = null;
		//streamValueLength = -1;
	}

	public final void setValue(Blob theValue) throws StandardException
	{
		dataValue = null;
        _blobValue = theValue;
		//stream = null;
		//streamValueLength = -1;
	}

	/**
	 * Used by JDBC -- string should not contain
	 * SQL92 formatting.
	 *
	 * @exception StandardException		Thrown on error
	 */
	public final String	getString() throws StandardException
	{
		if (getValue() == null)
			return null;
		else if (dataValue.length * 2 < 0)  //if converted to hex, length exceeds max int
		{
			throw StandardException.newException(SQLState.LANG_STRING_TRUNCATION, getTypeName(),
// GemStone changes BEGIN
									MessageService.getTextMessage(
									    MessageId.BINARY_DATA_HIDDEN,
									    String.valueOf(dataValue.length)),
									/* (original code)
									"",
									*/
// GemStone changes END
									String.valueOf(Integer.MAX_VALUE));
		}
		else 
		{
			return com.pivotal.gemfirexd.internal.iapi.util.StringUtil.toHexString(dataValue, 0, dataValue.length);
		}
	}


	/**
	 * @exception StandardException		Thrown on error
	 */
	public final InputStream	getStream() throws StandardException
	{
		return null;//(stream);
	}

	/**
	 *
	 * @exception StandardException		Thrown on error
	 */
	public final byte[]	getBytes() throws StandardException
	{
		return getValue();
	}

// GemStone changes BEGIN
	public Object getObject() throws StandardException {
	  return getValue();
	}

	final byte[] getValue() throws StandardException {
	  try {
	    return getValueAsBytes();
	  } catch (SQLException sqle) {
	    throw Misc.wrapSQLException(sqle, sqle);
	  }
	}

	final byte[] getValueAsBytes() throws SQLException {
	/* (original code)
	byte[] getValue() throws StandardException
	{
		try
		{
	*/
// GemStone changes END
			if ((dataValue == null) && (_blobValue != null) )
            {
                dataValue = _blobValue.getBytes( 1L,  getBlobLength() );
                
                _blobValue = null;
 				//stream = null;
				//streamValueLength = -1;
            }
// GemStone changes BEGIN
		/* (original code)
			else if ((dataValue == null) && (stream != null) )
            {
				if (stream instanceof FormatIdInputStream) {
					readExternal((FormatIdInputStream) stream);
				}
				else {
					readExternal(new FormatIdInputStream(stream));
				}
                _blobValue = null;
 				stream = null;
				streamValueLength = -1;

			}
		}
		catch (IOException ioe)
		{
			throwStreamingIOException(ioe);
		}
		catch (SQLException se) { throw StandardException.plainWrapException( se ); }
		*/
// GemStone changes END

		return dataValue;
	}

	/**
	 * length in bytes
	 *
	 * @exception StandardException		Thrown on error
	 */
	public final int	getLength() throws StandardException
	{
// GemStone changes BEGIN
	  try {
	    if (_blobValue != null ) {
	      return getBlobLength();
	    }
	  } catch (SQLException sqle) {
	    throw Misc.wrapSQLException(sqle, sqle);
	  }
        /* (original code)
        if ( _blobValue != null ) { return getBlobLength(); }
		else if (stream != null) {
			if (streamValueLength != -1)
				return streamValueLength;
			else if (stream instanceof Resetable){
				try {
					// If we have the stream length encoded.
					// just read that.
					streamValueLength = readBinaryLength((ObjectInput) stream);
					if (streamValueLength != 0)
						return streamValueLength;
					// Otherwise we will have to read the whole stream.
					for (;;) {
						long skipsize = stream.skip(Integer.MAX_VALUE);
						streamValueLength += skipsize;
						if (stream.read() == -1)
							break;
						else
							streamValueLength++;
					}
					return streamValueLength;
				}
				catch (IOException ioe) {
					throwStreamingIOException(ioe);
				}
				finally {
					try {
						((Resetable) stream).resetStream();
					} catch (IOException ioe) {
						throwStreamingIOException(ioe);
					}
				}
				
			}
		}
		*/
// GemStone changes END
		byte[] bytes = getBytes();
		return (bytes == null) ? 0 : bytes.length;
		
	}


	private void throwStreamingIOException(IOException ioe) throws StandardException {
// GemStone changes BEGIN
	  DerbyIOException dioe;
	  if (ioe instanceof DerbyIOException
	      && (dioe = (DerbyIOException)ioe).getSQLState() != null) {
	    throw StandardException.newPreLocalizedException(dioe.getSQLState(),
	        dioe, dioe.getLocalizedMessage());
	  }
// GemStone changes END
		throw StandardException.
			newException(SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION,
						 ioe, getTypeName());
	}

	/*
	 * Storable interface, implies Externalizable, TypedFormat
	 */


	/**
	 * see if the Bit value is null.
	 * @see com.pivotal.gemfirexd.internal.iapi.services.io.Storable#isNull
	 */
	public final boolean isNull()
	{
		return (dataValue == null) /*&& (stream == null)*/ && (_blobValue == null);
	}

	/** 
		Write the value out from the byte array (not called if null)
		using the 8.1 encoding.

	 * @exception IOException		io exception
	 */
	public final void writeExternal(ObjectOutput out) throws IOException
	{
        if ( _blobValue != null )
        {
            writeBlob(  out );
            return;
        }
        int len = dataValue.length;

        writeLength( out, len );
		out.write(dataValue, 0, dataValue.length);
	}

	/** 
		Serialize a blob using the 8.1 encoding. Not called if null.

	 * @exception IOException		io exception
	 */
	private void writeBlob(ObjectOutput out) throws IOException
	{
        try {
            int                 len = getBlobLength();
            InputStream         is = _blobValue.getBinaryStream();
            
            writeLength( out, len );

            for ( int i = 0; i < len; i++ )
            {
                out.write( is.read() );
            }
        }
// GemStone changes BEGIN
        /* (original code)
        catch (StandardException se) { throw new IOException( se.getMessage() ); }
        */
// GemStone changes END
        catch (SQLException se) { throw new IOException( se.getMessage() ); }
    }
    
	/** 
		Write the length if
		using the 8.1 encoding.

	 * @exception IOException		io exception
	 */
    private void writeLength( ObjectOutput out, int len ) throws IOException
    {
		if (len <= 31)
		{
			out.write((byte) (0x80 | (len & 0xff)));
		}
		else if (len <= 0xFFFF)
		{
			out.write((byte) 0xA0);
			out.writeShort((short) len);
		}
		else
		{
			out.write((byte) 0xC0);
			out.writeInt(len);

		}
    }

	/** 
	 * delegated to bit 
	 *
	 * @exception IOException			io exception
	 * @exception ClassNotFoundException	class not found
	*/
	public final void readExternal(ObjectInput in) throws IOException
	{
		// need to clear stream first, in case this object is reused, and
		// stream is set by previous use.  Track 3794.
		//stream = null;
		//streamValueLength = -1;
        _blobValue = null;


		int len = SQLBinary.readBinaryLength(in);

		if (len != 0)
		{
			dataValue = new byte[len];
			in.readFully(dataValue);
		}
		else
		{
			readFromStream((InputStream) in);
		}
	}
	public final void readExternalFromArray(ArrayInputStream in) throws IOException
	{
		// need to clear stream first, in case this object is reused, and
		// stream is set by previous use.  Track 3794.
		//stream = null;
		//streamValueLength = -1;
        _blobValue = null;

		int len = SQLBinary.readBinaryLength(in);

		if (len != 0)
		{
			dataValue = new byte[len];
			in.readFully(dataValue);
		}
		else
		{
			readFromStream(in);
		}
	}

    /**
     * Read the encoded length of the value from the on-disk format.
     * 
     * @see SQLBinary
    */
	private static int readBinaryLength(ObjectInput in) throws IOException {
		
		int bl = in.read();
		if (bl == -1)
			throw new java.io.EOFException();
        
        byte li = (byte) bl;

        int len;
		if ((li & ((byte) 0x80)) != 0)
		{
			if (li == ((byte) 0xC0))
			{             
				len = in.readInt();
 			}
			else if (li == ((byte) 0xA0))
			{
				len = in.readUnsignedShort();
			}
			else
			{
				len = li & 0x1F;
			}
		}
		else
		{
            
			// old length in bits
			int v2 = in.read();
			int v3 = in.read();
			int v4 = in.read();
			if (v2 == -1 || v3 == -1 || v4 == -1)
				throw new java.io.EOFException();
            int lenInBits = (((bl & 0xff) << 24) | ((v2 & 0xff) << 16) | ((v3 & 0xff) << 8) | (v4 & 0xff));

			len = lenInBits / 8;
			if ((lenInBits % 8) != 0)
				len++;
 		}
		return len;
	}

    /**
     * Read the value from an input stream. The length
     * encoded in the input stream has already been read
     * and determined to be unknown.
     */
    private void readFromStream(InputStream in) throws IOException {

		dataValue = null;	// allow gc of the old value before the new.
		byte[] tmpData = new byte[32 * 1024];

		int off = 0;
		for (;;) {

			int len = in.read(tmpData, off, tmpData.length - off);
			if (len == -1)
				break;
			off += len;

			int available = Math.max(1, in.available());
			int extraSpace = available - (tmpData.length - off);
			if (extraSpace > 0)
			{
				// need to grow the array
				int size = tmpData.length * 2;
				if (extraSpace > tmpData.length)
					size += extraSpace;

				byte[] grow = new byte[size];
				System.arraycopy(tmpData, 0, grow, 0, off);
				tmpData = grow;
			}
		}

		dataValue = new byte[off];
		System.arraycopy(tmpData, 0, dataValue, 0, off);
	}

	/**
	 * @see com.pivotal.gemfirexd.internal.iapi.services.io.Storable#restoreToNull
	 */
	public final void restoreToNull()
	{
		dataValue = null;
        _blobValue = null;
		//stream = null;
		//streamValueLength = -1;
	}

	/**
		@exception StandardException thrown on error
	 */
	public final boolean compare(int op,
						   DataValueDescriptor other,
						   boolean orderedNulls,
						   boolean unknownRV)
		throws StandardException
	{
		if (!orderedNulls)		// nulls are unordered
		{
			if (SanityManager.DEBUG)
			{
                int otherTypeFormatId = other.getTypeFormatId();
				if (!((StoredFormatIds.SQL_BIT_ID == otherTypeFormatId)
                      || (StoredFormatIds.SQL_VARBIT_ID == otherTypeFormatId)
                      || (StoredFormatIds.SQL_LONGVARBIT_ID == otherTypeFormatId)

                      || (StoredFormatIds.SQL_CHAR_ID == otherTypeFormatId)
                      || (StoredFormatIds.SQL_VARCHAR_ID == otherTypeFormatId)
                      || (StoredFormatIds.SQL_LONGVARCHAR_ID == otherTypeFormatId)

                      || ((StoredFormatIds.SQL_BLOB_ID == otherTypeFormatId)
                          && (StoredFormatIds.SQL_BLOB_ID == getTypeFormatId()))
                        ))
				SanityManager.THROWASSERT(
									"Some fool passed in a "+ other.getClass().getName() + ", "
                                    + otherTypeFormatId  + " to SQLBinary.compare()");
			}
			String otherString = other.getString();
			if (this.getString() == null  || otherString == null)
				return unknownRV;
		}
		/* Do the comparison */
		return super.compare(op, other, orderedNulls, unknownRV);
	}

	/**
		@exception StandardException thrown on error
	 */
	public final int compare(DataValueDescriptor other) throws StandardException
	{

		/* Use compare method from dominant type, negating result
		 * to reflect flipping of sides.
		 */
		if (typePrecedence() < other.typePrecedence())
		{
			return -Integer.signum(other.compare(this));
		}

		/*
		** By convention, nulls sort High, and null == null
		*/
		if (this.isNull() || other.isNull())
		{
			if (!isNull())
				return -1;
			if (!other.isNull())
				return 1;
			return 0;							// both null
		}

		return SQLBinary.compare(getBytes(), other.getBytes());
	}

	/*
	 * CloneableObject interface
	 */

	/** From CloneableObject
	 *	Shallow clone a StreamStorable without objectifying.  This is used to avoid
	 *	unnecessary objectifying of a stream object.  The only difference of this method
	 *  from getClone is this method does not objectify a stream.  beetle 4896
	 */
	public final Object cloneObject()
	{
        if ( _blobValue != null )
        {
            SQLBinary self = (SQLBinary) getNewNull();
            self._blobValue = _blobValue;
            self.dataValue = null;
            return self;
        }
// GemStone changes BEGIN
        return getClone();
        /* (original code)
		if (stream == null) { return getClone(); }
        
		SQLBinary self = (SQLBinary) getNewNull();
		self.setValue(stream, streamValueLength);
		return self;
	*/
// GemStone changes END
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#getClone */
	public final DataValueDescriptor getClone()
	{
		try
		{
			DataValueDescriptor cloneDVD = getNewNull();
			if (_blobValue != null) {
				cloneDVD.setValue(_blobValue);
			} else {
				cloneDVD.setValue(dataValue);
			}
			return cloneDVD;
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

	/*
	 * DataValueDescriptor interface
	 */

	/*
	 * StreamStorable interface : 
	 */
	public final InputStream returnStream()
	{
		return null;//stream;
	}

    /**
     * Set me to the value represented by this stream.
     * The format of the stream is the on-disk format
     * described in this class's javadoc. That is the
     * length is encoded in the first few bytes of the
     * stream.
     */
	public final void setStream(InputStream newStream)
	    throws StandardException
	{
		this.dataValue = null;
        _blobValue = null;
// GemStone changes BEGIN
          try {
            if (newStream instanceof FormatIdInputStream) {
              readExternal((FormatIdInputStream)newStream);
            }
            else {
              readExternal(new FormatIdInputStream(newStream));
            }
          } catch (IOException ioe) {
            throwStreamingIOException(ioe);
          }
          /* (original code)
		this.stream = newStream;
		streamValueLength = -1;
	  */
// GemStone changes END
	}

	public final void loadStream() throws StandardException
	{
		getValue();
	}

	/*
	 * class interface
	 */

    boolean objectNull(Object o) 
	{
		if (o == null) 
		{
			setToNull();
			return true;
		}
		return false;
	}

	/**
     * Set the value from the stream which is in the on-disk format.
     * @param theStream On disk format of the stream
     * @param valueLength length of the logical value in bytes.
	 */
	public final void setValue(InputStream theStream, int valueLength)
	    throws StandardException
	{
// GemStone changes BEGIN
	  setStream(theStream);
	  /* (original code)
		dataValue = null;
        _blobValue = null;
		stream = theStream;
		this.streamValueLength = valueLength;
	  */
// GemStone changes END
	}

	protected final void setFrom(DataValueDescriptor theValue) throws StandardException {

		if (theValue instanceof SQLBinary)
		{
			SQLBinary theValueBinary = (SQLBinary) theValue;
// GemStone changes BEGIN
			Blob blob = theValueBinary._blobValue;
			if (blob != null && !(blob instanceof BufferedBlob)) {
			  setValue(theValueBinary.getBytes());
			  return;
			}
// GemStone changes END
			dataValue = theValueBinary.dataValue;
            _blobValue = theValueBinary._blobValue;
			//stream = theValueBinary.stream;
			//streamValueLength = theValueBinary.streamValueLength;
		}
		else
		{
			setValue(theValue.getBytes());
		}
	}

	/*
	** SQL Operators
	*/

	/**
	 * The = operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the =
	 * @param right			The value on the right side of the =
	 *						is not.
	 * @return	A SQL boolean value telling whether the two parameters are equal
	 *
	 * @exception StandardException		Thrown on error
	 */

	public final BooleanDataValue equals(DataValueDescriptor left,
							 DataValueDescriptor right)
								throws StandardException
	{
		boolean isEqual;

		if (left.isNull() || right.isNull())
		{
			isEqual = false;
		}
		else
		{	
			isEqual = SQLBinary.compare(left.getBytes(), right.getBytes()) == 0;
		}

		return SQLBoolean.truthValue(left,
									 right,
									 isEqual);
	}

	/**
	 * The <> operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the <>
	 * @param right			The value on the right side of the <>
	 *
	 * @return	A SQL boolean value telling whether the two parameters
	 * are not equal
	 *
	 * @exception StandardException		Thrown on error
	 */

	public final BooleanDataValue notEquals(DataValueDescriptor left,
							 DataValueDescriptor right)
								throws StandardException
	{
		boolean isNotEqual;

		if (left.isNull() || right.isNull())
		{
			isNotEqual = false;
		}
		else
		{	
			isNotEqual = SQLBinary.compare(left.getBytes(), right.getBytes()) != 0;
		}

		return SQLBoolean.truthValue(left,
									 right,
									 isNotEqual);
	}

	/**
	 * The < operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the <
	 * @param right			The value on the right side of the <
	 *
	 * @return	A SQL boolean value telling whether the first operand is
	 *			less than the second operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public final BooleanDataValue lessThan(DataValueDescriptor left,
							 DataValueDescriptor right)
								throws StandardException
	{
		boolean isLessThan;

		if (left.isNull() || right.isNull())
		{
			isLessThan = false;
		}
		else
		{	
			isLessThan = SQLBinary.compare(left.getBytes(), right.getBytes()) < 0;
		}

		return SQLBoolean.truthValue(left,
									 right,
									 isLessThan);
	}

	/**
	 * The > operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the >
	 * @param right			The value on the right side of the >
	 *
	 * @return	A SQL boolean value telling whether the first operand is
	 *			greater than the second operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public final BooleanDataValue greaterThan(DataValueDescriptor left,
							 DataValueDescriptor right)
								throws StandardException
	{
		boolean isGreaterThan = false;

		if (left.isNull() || right.isNull())
		{
			isGreaterThan = false;
		}
		else
		{	
			isGreaterThan = SQLBinary.compare(left.getBytes(), right.getBytes()) > 0;
		}

		return SQLBoolean.truthValue(left,
									 right,
									 isGreaterThan);
	}

	/**
	 * The <= operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the <=
	 * @param right			The value on the right side of the <=
	 *
	 * @return	A SQL boolean value telling whether the first operand is
	 *			less than or equal to the second operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public final BooleanDataValue lessOrEquals(DataValueDescriptor left,
							 DataValueDescriptor right)
								throws StandardException
	{
		boolean isLessEquals = false;

		if (left.isNull() || right.isNull())
		{
			isLessEquals = false;
		}
		else
		{	
			isLessEquals = SQLBinary.compare(left.getBytes(), right.getBytes()) <= 0;
		}

		return SQLBoolean.truthValue(left,
									 right,
									 isLessEquals);
	}

	/**
	 * The >= operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the >=
	 * @param right			The value on the right side of the >=
	 *
	 * @return	A SQL boolean value telling whether the first operand is
	 *			greater than or equal to the second operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public final BooleanDataValue greaterOrEquals(DataValueDescriptor left,
							 DataValueDescriptor right)
								throws StandardException
	{
		boolean isGreaterEquals = false;

		if (left.isNull() || right.isNull())
		{
			isGreaterEquals = false;
		}
		else
		{	
			isGreaterEquals = SQLBinary.compare(left.getBytes(), right.getBytes()) >= 0;
		}

		return SQLBoolean.truthValue(left,
									 right,
									 isGreaterEquals);
	}


	/**
	 *
	 * This method implements the char_length function for bit.
	 *
	 * @param result	The result of a previous call to this method, null
	 *					if not called yet
	 *
	 * @return	A SQLInteger containing the length of the char value
	 *
	 * @exception StandardException		Thrown on error
	 *
	 * @see ConcatableDataValue#charLength
	 */

	public final NumberDataValue charLength(NumberDataValue result)
							throws StandardException
	{
		if (result == null)
		{
			result = new SQLInteger();
		}

		if (this.isNull())
		{
			result.setToNull();
			return result;
		}


		result.setValue(getValue().length);
		return result;
	}

	/**
	 * @see BitDataValue#concatenate
	 *
	 * @exception StandardException		Thrown on error
	 */
	public final BitDataValue concatenate(
				BitDataValue left,
				BitDataValue right,
				BitDataValue result)
		throws StandardException
	{
		if (left.isNull() || right.isNull())
		{
			result.setToNull();
			return result;
		}

		byte[] leftData = left.getBytes();
		byte[] rightData = right.getBytes();

		byte[] concatData = new byte[leftData.length + rightData.length];

		System.arraycopy(leftData, 0, concatData, 0, leftData.length);
		System.arraycopy(rightData, 0, concatData, leftData.length, rightData.length);


		result.setValue(concatData);
		return result;
	}

  
	/**
	 * The SQL substr() function.
	 *
	 * @param start		Start of substr
	 * @param length	Length of substr
	 * @param result	The result of a previous call to this method,
	 *					null if not called yet.
	 * @param maxLen	Maximum length of the result
	 *
	 * @return	A ConcatableDataValue containing the result of the substr()
	 *
	 * @exception StandardException		Thrown on error
	 */
	public final ConcatableDataValue substring(
				NumberDataValue start,
				NumberDataValue length,
				ConcatableDataValue result,
				int maxLen)
		throws StandardException
	{
		int startInt;
		int lengthInt;
		BitDataValue varbitResult;

		if (result == null)
		{
			result = new SQLVarbit();
		}

		varbitResult = (BitDataValue) result;

		/* The result is null if the receiver (this) is null or if the length is negative.
		 * Oracle docs don't say what happens if the start position or the length is a usernull.
		 * We will return null, which is the only sensible thing to do.
		 * (If the user did not specify a length then length is not a user null.)
		 */
		if (this.isNull() || start.isNull() || (length != null && length.isNull()))
		{
			varbitResult.setToNull();
			return varbitResult;
		}

		startInt = start.getInt();

		// If length is not specified, make it till end of the string
		if (length != null)
		{
			lengthInt = length.getInt();
		}
		else lengthInt = getLength() - startInt + 1;

		/* DB2 Compatibility: Added these checks to match DB2. We currently enforce these
		 * limits in both modes. We could do these checks in DB2 mode only, if needed, so
		 * leaving earlier code for out of range in for now, though will not be exercised
		 */
		if ((startInt <= 0 || lengthInt < 0 || startInt > getLength() ||
				lengthInt > getLength() - startInt + 1))
			throw StandardException.newException(SQLState.LANG_SUBSTR_START_OR_LEN_OUT_OF_RANGE);
			
		// Return null if length is non-positive
		if (lengthInt < 0)
		{
			varbitResult.setToNull();
			return varbitResult;
		}

		/* If startInt < 0 then we count from the right of the string */
		if (startInt < 0)
		{
			startInt += getLength();
			if (startInt < 0)
			{
				lengthInt += startInt;
				startInt = 0;
			}
			if (lengthInt + startInt > 0)
			{
				lengthInt += startInt;
			}
			else
			{
				lengthInt = 0;
			}
		}
		else if (startInt > 0)
		{
			/* java substr() is 0 based */
			startInt--;
		}

		/* Oracle docs don't say what happens if the window is to the
		 * left of the string.  Return "" if the window
		 * is to the left or right or if the length is 0.
		 */
		if (lengthInt == 0 ||
			lengthInt <= 0 - startInt ||
			startInt > getLength())
		{
			varbitResult.setValue(new byte[0]);
			return varbitResult;
		}

		if (lengthInt >= getLength() - startInt)
		{
			byte[] substring = new byte[dataValue.length - startInt];
			System.arraycopy(dataValue, startInt, substring, 0, substring.length);
			varbitResult.setValue(substring);
		}
		else
		{
			byte[] substring = new byte[lengthInt];
			System.arraycopy(dataValue, startInt, substring, 0, substring.length);
			varbitResult.setValue(substring);
		}

		return varbitResult;
	}

	/**
		Host variables are rejected if their length is
		bigger than the declared length, regardless of
		if the trailing bytes are the pad character.

		@exception StandardException Variable is too big.
	*/
	public final void checkHostVariable(int declaredLength) throws StandardException
	{
		// stream length checking occurs at the JDBC layer
		int variableLength = -1;
        if ( _blobValue != null ) { variableLength = -1; }
		else /* if (stream == null) */
		{
			if (dataValue != null)
				variableLength = dataValue.length;
		}
// GemStone changes BEGIN
		/* (original code)
		else
		{
			variableLength = streamValueLength;
		}
		*/
// GemStone changes END

		if (variableLength != -1 && variableLength > declaredLength)
				throw StandardException.newException(SQLState.LANG_STRING_TRUNCATION, getTypeName(), 
							MessageService.getTextMessage(
								MessageId.BINARY_DATA_HIDDEN,
								String.valueOf(variableLength)),
							String.valueOf(declaredLength));
	}

	/*
	 * String display of value
	 */

	public String toString()
	{
		if (dataValue == null)
		{
			if (/* (stream == null) && */ (_blobValue == null) )
			{
				return "NULL";
			}
			else
			{
				// GemStone changes BEGIN
				// provide a real string here instead of failing an assertion.
				// Throw an assertion only if loading the stream fails
				/*
				if (SanityManager.DEBUG)
					SanityManager.THROWASSERT(
						"value is null, stream or blob is not null");
				return "";
				*/
				try {
					loadStream();
					return StringUtil.toHexString(this.dataValue, 0, this.dataValue.length);
				} catch (StandardException e) {
				  throw GemFireXDRuntimeException.newRuntimeException(
				      null, e);
				}
				// GemStone changes END
			}
		}
		else
		{
			return com.pivotal.gemfirexd.internal.iapi.util.StringUtil.toHexString(dataValue, 0, dataValue.length);
		}
	}

	/*
	 * Hash code
	 */
	public final int hashCode()
	{
		try {
			if (getValue() == null)
				{
					return 0;
				}
		}
		catch (StandardException se)
		{
			if (SanityManager.DEBUG)
				SanityManager.THROWASSERT("Unexpected exception", se);
			return 0;
		}

		/* Hash code is simply the sum of all of the bytes */
		byte[] bytes = dataValue;
		int hashcode = 0;

		// Build the hash code
		for (int index = 0 ; index < bytes.length; index++)
		{
			byte bv = bytes[index];
			if (bv != SQLBinary.PAD)
				hashcode += bytes[index];
		}

		return hashcode;
	}
	private static int compare(byte[] left, byte[] right) {

		int minLen = left.length;
		byte[] longer = right;
		if (right.length < minLen) {
			minLen = right.length;
			longer = left;
		}

		for (int i = 0; i < minLen; i++) {

			int lb = left[i] & 0xff;
			int rb = right[i] & 0xff;

			if (lb == rb)
				continue;

			return lb - rb;
		}

		// complete match on all the bytes for the smallest value.

		// if the longer value is all pad characters
		// then the values are equal.
		for (int i = minLen; i < longer.length; i++) {
			byte nb = longer[i];
			if (nb == SQLBinary.PAD)
				continue;

			// longer value is bigger.
			if (left == longer)
				return 1;
			return -1;
		}

		return 0;

	}

      /** Adding this method to ensure that super class' setInto method doesn't get called
      * that leads to the violation of JDBC spec( untyped nulls ) when batching is turned on.
      */
     public void setInto(PreparedStatement ps, int position) throws SQLException, StandardException {

                  ps.setBytes(position, getBytes());
     }

    /**
     * Gets a trace representation for debugging.
     *
     * @return a trace representation of this SQL DataType.
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

        return (getTypeName() + ":Length=" + getLength());
    }

// GemStone changes BEGIN

    private int getBlobLength() throws SQLException {
      final long length = _blobValue.length();
      if (length > Integer.MAX_VALUE) {
        throw Util.generateCsSQLException(SQLState.BLOB_TOO_LARGE_FOR_CLIENT,
            Long.toString(length), Long.toString(Integer.MAX_VALUE));
      }
      return (int)length;
    }

    /* (original code)
    private int getBlobLength() throws StandardException
    {
        try {
            long   maxLength = Integer.MAX_VALUE;
            long   length = _blobValue.length();
            if ( length > Integer.MAX_VALUE )
            {
                throw StandardException.newException
                    ( SQLState.BLOB_TOO_LARGE_FOR_CLIENT, Long.toString( length ), Long.toString( maxLength ) );
            }

            return (int) length;
        }
        catch (SQLException se) { throw StandardException.plainWrapException( se ); }
    }
    */

  @Override
  public final void toData(final DataOutput out) throws IOException {
    if (!isNull()) {
      final byte[] value;
      try {
        value = getValueAsBytes();
      } catch (SQLException sqle) {
        // wrap in RuntimeException
        throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
      }
      out.writeByte(DSCODE.BYTE_ARRAY);
      out.writeShort(getTypeFormatId());
      DataSerializer.writeByteArray(value, value.length, out);
      return;
    }
    out.writeByte(DSCODE.NULL);
    out.writeByte(DSCODE.BYTE_ARRAY);
    out.writeShort(getTypeFormatId());
  }

  public final void fromDataForOptimizedResultHolder(final DataInput dis)
      throws IOException, ClassNotFoundException {
    // need to clear stream first, in case this object is reused, and
    // stream is set by previous use. Track 3794.
    //this.stream = null;
    //this.streamValueLength = -1;
    this.dataValue = DataSerializer.readByteArray(dis);
  }

  public final void toDataForOptimizedResultHolder(final DataOutput dos)
      throws IOException {
    assert !isNull();
    final byte[] value;
    try {
      value = getValueAsBytes();
    } catch (SQLException sqle) {
      // wrap in RuntimeException
      throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
    }
    DataSerializer.writeByteArray(value, value.length, dos);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final byte[] inBytes, int offset,
      final int columnWidth) {
    this.dataValue = new byte[columnWidth];
    System.arraycopy(inBytes, offset, this.dataValue, 0, columnWidth);
    return columnWidth;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(long memOffset, final int columnWidth, ByteSource bs) {
    this.dataValue = new byte[columnWidth];
    UnsafeMemoryChunk.readUnsafeBytes(memOffset, this.dataValue, columnWidth);
    return columnWidth;
  }

  /**
   * Optimized write to a byte array at specified offset
   * 
   * @return number of bytes actually written
   */
  @Override
  public int writeBytes(byte[] outBytes, int offset, DataTypeDescriptor dtd) {
    final byte[] value;
    try {
      value = getValueAsBytes();
    } catch (SQLException sqle) {
      // wrap in RuntimeException
      throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
    }
    System.arraycopy(value, 0, outBytes, offset, value.length);
    return value.length;
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    assert !isNull();
    final byte[] value;
    try {
      value = getValueAsBytes();
    } catch (SQLException sqle) {
      // wrap in RuntimeException
      throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
    }
    return ResolverUtils.addBytesToBucketHash(value, hash, getTypeFormatId());
  }

  static final byte[] getAsBytes(final byte[] inBytes, final int offset,
    final int columnWidth) {
    if (offset == 0 && columnWidth == inBytes.length) {
      // byte[]s in GemFireXD are immutable so can safely return them
      return inBytes;
    }
    else {
      final byte[] data = new byte[columnWidth];
      System.arraycopy(inBytes, offset, data, 0, columnWidth);
      return data;
    }  
  }

  static final byte[] getAsBytes(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth) {
    // off-heap always has to create a new byte[] so avoid extra calls of
    // getLength by just calling readBytes
    final byte[] data = new byte[columnWidth];
    UnsafeMemoryChunk.readUnsafeBytes(memOffset, data, columnWidth);
    return data;
  }

// GemStone changes END
}
