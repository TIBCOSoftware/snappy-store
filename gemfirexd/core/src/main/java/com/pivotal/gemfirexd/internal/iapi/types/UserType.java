/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.UserType

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
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.NullDataOutputStream;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
// GemStone changes END

import com.pivotal.gemfirexd.internal.catalog.TypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassInspector;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.ObjectOutput;
import java.io.ObjectInput;
import java.io.IOException;

import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.Calendar;


/**
 * This contains an instance of a user-defined type, that is, a java object.
 *
 */

public class UserType extends DataType
						implements UserDataValue
{
	private Object	value;

	/*
	** DataValueDescriptor interface
	** (mostly implemented in DataType)
	*/

    private static final int BASE_MEMORY_USAGE = ClassSize.estimateBaseFromCatalog( UserType.class);

    public int estimateMemoryUsage()
    {
        int sz = BASE_MEMORY_USAGE;
        if( null != value)
        {
            // Probably an underestimate. Examining each field value would be expensive
            // and would produce an overestimate when fields reference shared objects
            sz += ClassSize.estimateAndCatalogBase( value.getClass());
        }
        
        return sz;
    } // end of estimateMemoryUsage

	public String getString()
	{
		if (! isNull())
		{
			return value.toString();

		}
		else
		{
			return null;
		}
	}

	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public boolean getBoolean() throws StandardException
	{
		if (! isNull())
			if (value instanceof Boolean) return ((Boolean)value).booleanValue();
		return super.getBoolean();
	}

	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public byte getByte() throws StandardException
	{
		if (! isNull())
			// REMIND: check for overflow and truncation
			if (value instanceof Number) return ((Number)value).byteValue();
		return super.getByte();
	}

	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public short getShort() throws StandardException
	{
		if (! isNull())
			// REMIND: check for overflow and truncation
			if (value instanceof Number) return ((Number)value).shortValue();
		return super.getShort();
	}

	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public int getInt() throws StandardException
	{
		if (! isNull())
			// REMIND: check for overflow and truncation
			if (value instanceof Number) return ((Number)value).intValue();
		return super.getInt();
	}

	/**
	 * @exception StandardException thrown on failure to convert
	 */

	public long getLong() throws StandardException
	{
		if (! isNull())
			// REMIND: check for overflow and truncation
			if (value instanceof Number) return ((Number)value).longValue();
		return super.getLong();
	}


	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public float getFloat() throws StandardException
	{
		if (! isNull())
			// REMIND: check for overflow
			if (value instanceof Number) return ((Number)value).floatValue();
		return super.getFloat();
	}


	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public double getDouble() throws StandardException
	{
		if (! isNull())
			// REMIND: check for overflow
			if (value instanceof Number) return ((Number)value).doubleValue();
		return super.getDouble();
	}

	/**
	 * @exception StandardException thrown on failure to convert
	 */
	public byte[] getBytes() throws StandardException
	{
// GemStone changes BEGIN
	  if (!isNull()) {
	    HeapDataOutputStream hdos = new HeapDataOutputStream();
	    try {
	      toDataForOptimizedResultHolder(hdos);
	    } catch (IOException ioe) {
	      throw GemFireXDRuntimeException.newRuntimeException(
	          "Exception in UserType.getBytes.", ioe);
	    }
	    byte[] buffer = new byte[hdos.size()];
	    hdos.sendTo(buffer, 0);
	    return buffer;
	  }
	  return null;
	  /* (original code)
		if (! isNull())
			if (value instanceof byte[]) return ((byte[])value);
		return super.getBytes();
	  */
// GemStone changes END
	}

	/**

		@exception StandardException thrown on failure
	 */
	public Date	getDate( Calendar cal) throws StandardException
	{
		if (! isNull())
		{
			if (value instanceof Date) 
				return ((Date)value);
			else if (value instanceof Timestamp)
				return (new SQLTimestamp((Timestamp)value).getDate(cal));
		}
		return super.getDate(cal);
	}

	/**
		@exception StandardException thrown on failure
	 */
	public Time	getTime( Calendar cal) throws StandardException
	{
		if (! isNull())
		{
			if (value instanceof Time) 
				return ((Time)value);
			else if (value instanceof Timestamp)
				return (new SQLTimestamp((Timestamp)value).getTime(cal));
		}
		return super.getTime(cal);
	}

	/**
		@exception StandardException thrown on failure
	 */
	public Timestamp	getTimestamp( Calendar cal) throws StandardException
	{
		if (! isNull())
		{
			if (value instanceof Timestamp) 
				return ((Timestamp)value);
			else if (value instanceof Date)
				return (new SQLDate((Date)value).getTimestamp(cal));
			else if (value instanceof Time)
				return (new SQLTime((Time)value).getTimestamp(cal));
		}
		return super.getTimestamp(cal);
	}

	void setObject(Object theValue)
    {
        setValue( theValue );
    }
    
	public Object getObject()
	{
		return value;
	}
		
	public int getLength()
	{
		return TypeDescriptor.MAXIMUM_WIDTH_UNKNOWN;
	}

	/* this is for DataType's error generator */
	public String getTypeName()
	{

		return isNull() ? "JAVA_OBJECT" : ClassInspector.readableClassName(value.getClass());
	}
	
	/**
	 * Get the type name of this value,  overriding
	 * with the passed in class name (for user/java types).
	 */
	String getTypeName(String className)
	{
		return className;
	}

	/*
	 * Storable interface, implies Externalizable, TypedFormat
	 */

	/**
		Return my format identifier.

		@see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId
	*/
	public int getTypeFormatId() {
		return StoredFormatIds.SQL_USERTYPE_ID_V3;
	}

	/** 
		@exception IOException error writing data

	*/
	public void writeExternal(ObjectOutput out) throws IOException {

		if (SanityManager.DEBUG)
			SanityManager.ASSERT(!isNull(), "writeExternal() is not supposed to be called for null values.");

			out.writeObject(value);
	}

	/**
	 * @see java.io.Externalizable#readExternal
	 *
	 * @exception IOException	Thrown on error reading the object
	 * @exception ClassNotFoundException	Thrown if the class of the object
	 *										is not found
	 */
	public void readExternal(ObjectInput in) 
        throws IOException, ClassNotFoundException
	{
		/* RESOLVE: Sanity check for right class */
		value = in.readObject();
	}
	public void readExternalFromArray(ArrayInputStream in) 
        throws IOException, ClassNotFoundException
	{
		/* RESOLVE: Sanity check for right class */
		value = in.readObject();
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#getClone */
	public DataValueDescriptor getClone()
	{
		// Call constructor with all of our info
		return new UserType(value);
	}

	/**
	 * @see DataValueDescriptor#getNewNull
	 */
	public DataValueDescriptor getNewNull()
	{
		return new UserType();
	}
	/**
	 * @see com.pivotal.gemfirexd.internal.iapi.services.io.Storable#restoreToNull
	 *
	 */

	public void restoreToNull()
	{
		value = null;
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** 
	 * @see DataValueDescriptor#setValueFromResultSet 
	 *
	 * @exception SQLException		Thrown on error
	 */
	public void setValueFromResultSet(ResultSet resultSet, int colNumber,
									  boolean isNullable)
		throws SQLException
	{
			value = resultSet.getObject(colNumber);
	}

	/**
	 * Orderable interface
	 *
	 *
	 * @see com.pivotal.gemfirexd.internal.iapi.types.Orderable
	 *
	 * @exception StandardException thrown on failure
	 */
	public int compare(DataValueDescriptor other)
		throws StandardException
	{
		/* Use compare method from dominant type, negating result
		 * to reflect flipping of sides.
		 */
		if (typePrecedence() < other.typePrecedence())
		{
			return -Integer.signum(other.compare(this));
		}

		boolean thisNull, otherNull;

		thisNull = this.isNull();
		otherNull = other.isNull();

		/*
		 * thisNull otherNull	return
		 *	T		T		 	0	(this == other)
		 *	F		T		 	-1 	(this < other)
		 *	T		F		 	1	(this > other)
		 */
		if (thisNull || otherNull)
		{
			if (!thisNull)		// otherNull must be true
				return -1;
			if (!otherNull)		// thisNull must be true
				return 1;
			return 0;
		}

		/*
			Neither are null compare them 
		 */

		int comparison;

		try
		{
			comparison = ((java.lang.Comparable) value).compareTo(other.getObject());
		}
		catch (ClassCastException cce)
		{
			throw StandardException.newException(SQLState.LANG_INVALID_COMPARE_TO, 
						getTypeName(),
						ClassInspector.readableClassName(other.getObject().getClass()));
		}
		/*
		** compareTo() can return any negative number if less than, and
		** any positive number if greater than.  Change to -1, 0, 1.
		*/
		if (comparison < 0)
			comparison = -1;
		else if (comparison > 0)
			comparison = 1;

		return comparison;
	}

	/**
		@exception StandardException thrown on error
	 */
	public boolean compare(int op,
						   DataValueDescriptor other,
						   boolean orderedNulls,
						   boolean unknownRV)
		throws StandardException
	{
		if (!orderedNulls)		// nulls are unordered
		{
			if (this.isNull() || other.isNull())
				return unknownRV;
		}

		/* For usertypes and equal do some special processing when
		 * neither value is null.  (Superclass will handle comparison
		 * if either value is null.)
		 */
		if ( (op == ORDER_OP_EQUALS) &&
			(! this.isNull()) && (! other.isNull()) ) 
		{
			// if this object implements java.lang.Comparable (JDK1.2)
			// then we let the compareTo method handle equality
			// if it doesn't then we use the equals() method
			Object o = getObject();

			if (!(o instanceof java.lang.Comparable)) 
			{
				return o.equals(other.getObject());
			}
		}
		

		/* Do the comparison */
		return super.compare(op, other, orderedNulls, unknownRV);
	}

	/*
	** Class interface
	*/

	/*
	** Constructors
	*/

	/** no-arg constructor required by Formattable */
	public UserType() { }

	public UserType(Object value)
	{
		this.value = value;
	}
	/**
	 * @see UserDataValue#setValue
	 *
	 */
	public void setValue(Object value)
	{
		this.value = value;
	}
	protected void setFrom(DataValueDescriptor theValue) throws StandardException {

		setValue(theValue.getObject());
	}

	/**
	 * @see UserDataValue#setValue
	 *
	 */
	public void setBigDecimal(Number theValue)
	{
		// needed to allow serializable BigDecimal
		setValue((Object) theValue);
	}

	public void setValue(String theValue)
	{
		if (theValue == null)
		{
			value = null;
		}
		else
		{
			// Higher levels must have performed type checking for us.
			value = theValue;
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
	 *
	 * @return	A SQL boolean value telling whether the two parameters are equal
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue equals(DataValueDescriptor left,
							DataValueDescriptor right)
			throws StandardException
	{
		return SQLBoolean.truthValue(left,
									 right,
									 left.compare(ORDER_OP_EQUALS, right, true, false));
	}

	/**
	 * The <> operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the <>
	 * @param right			The value on the right side of the <>
	 *
	 * @return	A SQL boolean value telling whether the two parameters
	 *			are not equal
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue notEquals(DataValueDescriptor left,
							DataValueDescriptor right)
			throws StandardException
	{
		return SQLBoolean.truthValue(left,
									 right,
									 !left.compare(ORDER_OP_EQUALS, right, true, false));
	}



	/*
	** String display of value
	*/

	public String toString()
	{
		if (isNull())
		{
			return "NULL";
		}
		else
		{
			return value.toString();
		}
	}

	/*
	 * Hash code
	 */
	public int hashCode()
	{
		if (isNull())
			return 0;
		return value.hashCode();
	}

	/** @see DataValueDescriptor#typePrecedence */
	public int	typePrecedence()
	{
		return TypeId.USER_PRECEDENCE;
	}

	/**
	 * Check if the value is null.  
	 *
	 * @return Whether or not value is logically null.
	 */
// GemStone changes BEGIN
        // (original code) public final boolean isNull()
	public boolean isNull()
	{
		return (value == null);
	}

// GemStone additions below

  private final HeapDataOutputStream.ExpansionExceptionGenerator
  DISALLOW_EXPANSION = new HeapDataOutputStream.ExpansionExceptionGenerator() {
    @Override
    public Error newExpansionException(String method) {
      return new InternalGemFireError(
          "writeBytes: unexpected overflow of user-defined type "
              + UserType.this.toString() + " in " + method);
    }
  };

  @Override
  public int writeBytes(byte[] outBytes, int offset, DataTypeDescriptor dtd) {
    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(!isNull(),
          "writeBytes() is not supposed to be called for null values.");
    }
    HeapDataOutputStream hdos = new HeapDataOutputStream(outBytes, offset,
        outBytes.length - offset);
    hdos.disallowExpansion(DISALLOW_EXPANSION);
    try {
      toDataForOptimizedResultHolder(hdos);
    } catch (IOException ioe) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "Exception in UserType.writeBytes.", ioe);
    }
    return hdos.size();
  }

  /**
   * Return length of this value in bytes.
   */
  @Override
  public int getLengthInBytes(final DataTypeDescriptor dtd)
      throws StandardException {
    if (!isNull()) {
      NullDataOutputStream ndos = new NullDataOutputStream();
      try {
        toDataForOptimizedResultHolder(ndos);
      } catch (IOException ioe) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "Exception in UserType.getLengthInBytes.", ioe);
      }
      return ndos.size();
    }
    return 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final byte[] inBytes, int offset,
      final int columnWidth) {
    if (columnWidth > 0) {
      try {
        final ByteArrayDataInput dis = new ByteArrayDataInput();
        dis.initialize(inBytes, offset, columnWidth, null);
        this.value = DataSerializer.readObject(dis);
      } catch (Exception ex) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "Exception in UserType.readBytes.", ex);
      }
     return columnWidth;
    }
    this.value = null;
    return 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(long memOffset,
      final int columnWidth, final ByteSource bs) {
    if (columnWidth > 0) {
      final OffHeapByteSource obs = (OffHeapByteSource)bs;
      try {
        final DataInput dis = obs.getDataInputStreamWrapper(
            (int)(memOffset - obs.getUnsafeAddress(0, columnWidth)),
            columnWidth);
        this.value = DataSerializer.readObject(dis);
      } catch (Exception ex) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "Exception in UserType.readBytes.", ex);
      }
     return columnWidth;
    }
    this.value = null;
    return 0;
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    // TODO: PERF: below should be optimized to have a DataOutput implementation
    // that will directly add the hash instead of below
    if (this.value != null) {
      HeapDataOutputStream hdos = new HeapDataOutputStream();
      try {
        toDataForOptimizedResultHolder(hdos);
      } catch (IOException ioe) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "Exception in UserType.writeBytes.", ioe);
      }
      byte[] bytes = hdos.toByteArray();
      return ResolverUtils.addBytesToHash(bytes, hash);
    }
    return hash;
  }

  @Override
  public final void toData(DataOutput out) throws IOException {
    if (!isNull()) {
      out.writeByte(getTypeId());
      toDataForOptimizedResultHolder(out);
    }
    else {
      this.writeNullDVD(out);
    }
  }

  @Override
  public final void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    fromDataForOptimizedResultHolder(in);
  }

  @Override
  public void toDataForOptimizedResultHolder(final java.io.DataOutput dos)
      throws IOException {
    assert !isNull();
    DataSerializer.writeObject(this.value, dos);
  }

  @Override
  public void fromDataForOptimizedResultHolder(final java.io.DataInput in)
      throws IOException, ClassNotFoundException {
    this.value = DataSerializer.readObject(in);
  }

  @Override
  public byte getTypeId() {
    return DSCODE.USER_DATA_SERIALIZABLE;
  }
// GemStone changes END
}
