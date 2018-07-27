/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.SQLBoolean

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
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.io.Storable;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import org.apache.spark.unsafe.Platform;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.ObjectOutput;
import java.io.ObjectInput;
import java.io.IOException;

import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * SQLBoolean satisfies the DataValueDescriptor
 * interfaces (i.e., DataType). It implements a boolean column, 
 * e.g. for * storing a column value; it can be specified
 * when constructed to not allow nulls. Nullability cannot be changed
 * after construction, as it affects the storage size and mechanism.
 * <p>
 * Because DataType is a subtype of DataType,
 * SQLBoolean can play a role in either a DataType/Row
 * or a DataType/Row, interchangeably.
 * <p>
 * We assume the store has a flag for nullness of the value,
 * and simply return a 0-length array for the stored form
 * when the value is null.
 * <p>
 * PERFORMANCE: There are likely alot of performance improvements
 * possible for this implementation -- it new's Integer
 * more than it probably wants to.
 */
public final class SQLBoolean
	extends DataType implements BooleanDataValue
{
	/*
	 * DataValueDescriptor interface
	 * (mostly implemented in DataType)
	 */

	/*
	 * see if the integer value is null.
	 */
	public boolean isNull()
	{
		return isnull;
	}

	public boolean	getBoolean()
	{
		return value;
	}

	private static int makeInt(boolean b)
	{
		return (b?1:0);
	}

	/** 
	 * @see DataValueDescriptor#getByte 
	 */
	public byte	getByte() 
	{
		return (byte) makeInt(value);
	}

	/** 
	 * @see DataValueDescriptor#getShort 
	 */
	public short	getShort()
	{
		return (short) makeInt(value);
	}

	/** 
	 * @see DataValueDescriptor#getInt 
	 */
	public int	getInt()
	{
		return makeInt(value);
	}

	/** 
	 * @see DataValueDescriptor#getLong 
	 */
	public long	getLong()
	{
		return (long) makeInt(value);
	}

	/** 
	 * @see DataValueDescriptor#getFloat 
	 */
	public float	getFloat()
	{
		return (float) makeInt(value);
	}

	/** 
	 * @see DataValueDescriptor#getDouble 
	 */
	public double	getDouble()
	{
		return (double) makeInt(value);
	}

	/**
	 * Implementation for BOOLEAN type. Convert to a BigDecimal using long
	 */
	public int typeToBigDecimal()
	{
		return java.sql.Types.BIGINT;
	}
	public String	getString()
	{
		if (isNull())
			return null;
		else if (value == true)
			return "true";
		else
			return "false";
	}

	public Object	getObject()
	{
		if (isNull())
			return null;
		else
// GemStone changes BEGIN
		  return Boolean.valueOf(this.value);
			/* (original derby code) return new Boolean(value); */
// GemStone changes END
	}

	public int	getLength()
	{
		return BOOLEAN_LENGTH;
	}

	// this is for DataType's error generator
	public final String getTypeName()
	{
		return TypeId.BOOLEAN_NAME;
	}

    /**
     * Recycle this SQLBoolean object if possible. If the object is immutable,
     * create and return a new object.
     *
     * @return a new SQLBoolean if this object is immutable; otherwise, this
     * object with value set to null
     */
    public DataValueDescriptor recycle() {
        if (immutable) {
            return new SQLBoolean();
        }
        return super.recycle();
    }

	/*
	 * Storable interface, implies Externalizable, TypedFormat
	 */


	/**
		Return my format identifier.

		@see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId
	*/
	public int getTypeFormatId() {
		return StoredFormatIds.SQL_BOOLEAN_ID;
	}

	public void writeExternal(ObjectOutput out) throws IOException {

		// never called when value is null
		if (SanityManager.DEBUG)
			SanityManager.ASSERT(! isNull());

		out.writeBoolean(value);
	}

	/** @see java.io.Externalizable#readExternal */
	public void readExternal(ObjectInput in) throws IOException {

		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");

		value = in.readBoolean();
		isnull = false;
	}
	public void readExternalFromArray(ArrayInputStream in) throws IOException {

		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");

		value = in.readBoolean();
		isnull = false;
	}

	/**
	 * @see Storable#restoreToNull
	 *
	 */
	public void restoreToNull()
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");

		value = false;
		isnull = true;
	}

	/*
	 * Orderable interface
	 */

	/**
		@exception StandardException thrown on error
	 */
	public int compare(DataValueDescriptor other) throws StandardException
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
		 * thisNull otherNull thisValue thatValue return
		 *	T		T			X		X			0	(this == other)
		 *	F		T			X		X			1 	(this > other)
		 *	T		F			X		X			-1	(this < other)
		 *
		 *	F		F			T		T			0	(this == other)
		 *	F		F			T		F			1	(this > other)
		 *	F		F			F		T			-1	(this < other)
		 *	F		F			F		F			0	(this == other)
		 */
		if (thisNull || otherNull)
		{
			if (!thisNull)		// otherNull must be true
				return 1;
			if (!otherNull)		// thisNull must be true
				return -1;
			return 0;
		}

		/* neither are null, get the value */
		boolean thisValue;
		boolean otherValue = false;
		thisValue = this.getBoolean();

		otherValue = other.getBoolean();

		if (thisValue == otherValue)
			return 0;
		else if (thisValue && !otherValue)
			return 1;
		else
			return -1;
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
		/* Do the comparison */
		return super.compare(op, other, orderedNulls, unknownRV);
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#getClone */
	public DataValueDescriptor getClone()
	{
		return new SQLBoolean(value, isnull);
	}

	/**
	 * @see DataValueDescriptor#getNewNull
	 */
	public DataValueDescriptor getNewNull()
	{
		return new SQLBoolean();
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
			value = resultSet.getBoolean(colNumber);
			isnull = (isNullable && resultSet.wasNull());
	}
	/**
		Set the value into a PreparedStatement.

		@exception SQLException Error setting value in PreparedStatement
	*/
	public final void setInto(PreparedStatement ps, int position) throws SQLException {

		if (isNull()) {
			ps.setNull(position, java.sql.Types.BIT);
			return;
		}

		ps.setBoolean(position, value);
	}
	/*
	 * class interface
	 */

	/*
	 * constructors
	 */

	/* NOTE - other data types have both (type value) and (boolean nulls), 
	 * (value, nulls)
	 * We can't do both (boolean value) and (boolean nulls) here,
	 * so we'll skip over (boolean value) and have (Boolean value) so
	 * that we can support (boolean nulls).
	 */

	public SQLBoolean()
	{
		isnull = true;
	}

	public SQLBoolean(boolean val)
	{
		value = val;
	}
	public SQLBoolean(Boolean obj) {
		if (isnull = (obj == null))
			;
		else
			value = obj.booleanValue();
	}

	/* This constructor gets used for the getClone() method */
	private SQLBoolean(boolean val, boolean isnull)
	{
		value = val;
		this.isnull = isnull;
	}

	/** @see BooleanDataValue#setValue */
	public void setValue(boolean theValue)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");
		value = theValue;
		isnull = false;

	}

	public void setValue(Boolean theValue)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");
		if (theValue == null)
		{
			value = false;
			isnull = true;
		}
		else
		{
			value = theValue.booleanValue();
			isnull = false;
		}

	}

	// REMIND: do we need this, or is long enough?
	public void setValue(byte theValue)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");
		value = theValue != 0;
		isnull = false;

	}


	// REMIND: do we need this, or is long enough?
	public void setValue(short theValue)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");
		value = theValue != 0;
		isnull = false;

	}


	// REMIND: do we need this, or is long enough?
	public void setValue(int theValue)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");
		value = theValue != 0;
		isnull = false;

	}

	public void setValue(long theValue)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");
		value = theValue != 0;
		isnull = false;

	}

	// REMIND: do we need this, or is double enough?
	public void setValue(float theValue)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");
		value = theValue != 0;
		isnull = false;

	}

	public void setValue(double theValue)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");
		value = theValue != 0;
		isnull = false;

	}

	public void setBigDecimal(Number bigDecimal) throws StandardException
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");
		if (bigDecimal == null)
		{
			value = false;
			isnull = true;
		}
		else
		{
			DataValueDescriptor tempDecimal = NumberDataType.ZERO_DECIMAL.getNewNull();
			tempDecimal.setBigDecimal(bigDecimal);
			value = NumberDataType.ZERO_DECIMAL.compare(tempDecimal) != 0;
			isnull = false;
		}

	}

	/**
	 * Set the value of this BooleanDataValue to the given byte array value
	 *
	 * @param theValue	The value to set this BooleanDataValue to
	 */
	public void setValue(byte[] theValue)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");

		if (theValue != null)
		{
			isnull = false;
			int length = theValue.length;
	
			/*
			** Step through all bytes.  As soon
			** as we get one with something other
			** than 0, then we know we have a 'true'
			*/
			for (int i = 0; i < length; i++)
			{
				if (theValue[i] != 0)
				{
					value = true;
					return;
				}
			}
		}
		else
		{
			isnull = true;
		}
		value = false;

	}


	/**
	 * Set the value of this BooleanDataValue to the given String.
	 * String is trimmed and upcased.  If resultant string is not
	 * TRUE or FALSE, then an error is thrown.
	 *
	 * @param theValue	The value to set this BooleanDataValue to
	 *
	 * @exception StandardException Thrown on error
	 */
	public void setValue(String theValue)
		throws StandardException
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");
		if (theValue == null)
		{
			value = false;
			isnull = true;
		}
		else
		{
			/*
			** Note: cannot use getBoolean(String) here because
			** it doesn't trim, and doesn't throw exceptions.
			*/
// GemStone changes BEGIN
			this.value = getBoolean(theValue);
			this.isnull = false;
			/* (original code)
			String cleanedValue = StringUtil.SQLToUpperCase(theValue.trim());
			if (cleanedValue.equals("TRUE"))
			{
				value = true;
			}
			else if (cleanedValue.equals("FALSE"))
			{
				value = false;
			}
			else
			{ 
				throw invalidFormat();
			}
			*/
// GemStone changes END
			isnull = false;
		}

	}

	private void setValueCore(Number theValue)
	{
		if (SanityManager.DEBUG)
			SanityManager.ASSERT( ! immutable,
						"Attempt to set the value of an immutable SQLBoolean");

		if (theValue == null)
		{
			isnull = true;
			value = false;
		}
		else
		{
			value = (theValue.intValue() != 0);
			isnull = false;
		}
	}

	/**
	 * @see DataValueDescriptor#setValue
	 */	
	void setObject(Object theValue)
	{
		setValue((Boolean) theValue);
	}
	protected void setFrom(DataValueDescriptor theValue) throws StandardException {

		setValue(theValue.getBoolean());
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
		return truthValue(left,
							right,
							left.getBoolean() == right.getBoolean());
	}

	/**
	 * The <> operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the <>
	 * @param right			The value on the right side of the <>
	 *
	 * @return	A SQL boolean value telling whether the two parameters are
	 *			not equal
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue notEquals(DataValueDescriptor left,
							 DataValueDescriptor right)
				throws StandardException
	{
		return truthValue(left,
							right,
							left.getBoolean() != right.getBoolean());
	}

	/**
	 * The < operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the <
	 * @param right			The value on the right side of the <
	 *
	 * @return	A SQL boolean value telling whether the left operand is
	 *			less than the right operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue lessThan(DataValueDescriptor left,
							 DataValueDescriptor right)
				throws StandardException
	{
		/* We must call getBoolean() on both sides in order
		 * to catch any invalid casts.
		 */
		boolean leftBoolean = left.getBoolean();
		boolean rightBoolean = right.getBoolean();
		/* By convention, false is less than true */
		return truthValue(left,
							right,
							leftBoolean == false && rightBoolean == true);
	}

	/**
	 * The > operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the >
	 * @param right			The value on the right side of the >
	 *
	 * @return	A SQL boolean value telling whether the left operand is
	 *			greater than the right operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue greaterThan(DataValueDescriptor left,
							 DataValueDescriptor right)
				throws StandardException
	{
		/* We must call getBoolean() on both sides in order
		 * to catch any invalid casts.
		 */
		boolean leftBoolean = left.getBoolean();
		boolean rightBoolean = right.getBoolean();
		/* By convention, true is greater than false */
		return truthValue(left,
							right,
							leftBoolean == true && rightBoolean == false);
	}

	/**
	 * The <= operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the <=
	 * @param right			The value on the right side of the <=
	 *
	 * @return	A SQL boolean value telling whether the left operand is
	 *			less than or equal to the right operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue lessOrEquals(DataValueDescriptor left,
							 DataValueDescriptor right)
				throws StandardException
	{
		/* We must call getBoolean() on both sides in order
		 * to catch any invalid casts.
		 */
		boolean leftBoolean = left.getBoolean();
		boolean rightBoolean = right.getBoolean();
		/* By convention, false is less than true */
		return truthValue(left,
							right,
							leftBoolean == false || rightBoolean == true);
	}

	/**
	 * The >= operator as called from the language module, as opposed to
	 * the storage module.
	 *
	 * @param left			The value on the left side of the >=
	 * @param right			The value on the right side of the >=
	 *
	 * @return	A SQL boolean value telling whether the left operand is
	 *			greater than or equal to the right operand
	 *
	 * @exception StandardException		Thrown on error
	 */

	public BooleanDataValue greaterOrEquals(DataValueDescriptor left,
							 DataValueDescriptor right)
				throws StandardException
	{
		/* We must call getBoolean() on both sides in order
		 * to catch any invalid casts.
		 */
		boolean leftBoolean = left.getBoolean();
		boolean rightBoolean = right.getBoolean();
		/* By convention, true is greater than false */
		return truthValue(left,
							right,
							leftBoolean == true || rightBoolean == false);
	}

	/**
	 * The AND operator.  This implements SQL semantics for AND with unknown
	 * truth values - consult any standard SQL reference for an explanation.
	 *
	 * @param otherValue	The other boolean to AND with this one
	 *
	 * @return	this AND otherValue
	 *
	 */

	public BooleanDataValue and(BooleanDataValue otherValue)
	{
		/*
		** Catch those cases where standard SQL null semantics don't work.
		*/
		if (this.equals(false) || otherValue.equals(false))
		{
			return BOOLEAN_FALSE;
		}
		else
		{
			return truthValue(this,
							otherValue,
							this.getBoolean() && otherValue.getBoolean());
		}
	}

	/**
	 * The OR operator.  This implements SQL semantics for OR with unknown
	 * truth values - consult any standard SQL reference for an explanation.
	 *
	 * @param otherValue	The other boolean to OR with this one
	 *
	 * @return	this OR otherValue
	 *
	 */

	public BooleanDataValue or(BooleanDataValue otherValue)
	{
		/*
		** Catch those cases where standard SQL null semantics don't work.
		*/
		if (this.equals(true) || otherValue.equals(true))
		{
			return BOOLEAN_TRUE;
		}
		else
		{
			return truthValue(this,
							otherValue,
							this.getBoolean() || otherValue.getBoolean());
		}
	}

	/**
	 * The SQL IS operator - consult any standard SQL reference for an explanation.
	 *
	 *	Implements the following truth table:
	 *
	 *	         otherValue
	 *	        | TRUE    | FALSE   | UNKNOWN
	 *	this    |----------------------------
	 *	        |
	 *	TRUE    | TRUE    | FALSE   | FALSE
	 *	FALSE   | FALSE   | TRUE    | FALSE
	 *	UNKNOWN | FALSE   | FALSE   | TRUE
	 *
	 *
	 * @param otherValue	BooleanDataValue to compare to. May be TRUE, FALSE, or UNKNOWN.
	 *
	 * @return	whether this IS otherValue
	 *
	 */
	public BooleanDataValue is(BooleanDataValue otherValue)
	{
		if ( this.equals(true) && otherValue.equals(true) )
		{ return BOOLEAN_TRUE; }

		if ( this.equals(false) && otherValue.equals(false) )
		{ return BOOLEAN_TRUE; }

		if ( this.isNull() && otherValue.isNull() )
		{ return BOOLEAN_TRUE; }

		return BOOLEAN_FALSE;
	}

	/**
	 * Implements NOT IS. This reverses the sense of the is() call.
	 *
	 *
	 * @param otherValue	BooleanDataValue to compare to. May be TRUE, FALSE, or UNKNOWN.
	 *
	 * @return	NOT( this IS otherValue )
	 *
	 */
	public BooleanDataValue isNot(BooleanDataValue otherValue)
	{
		BooleanDataValue	isValue = is( otherValue );

		if ( isValue.equals(true) ) { return BOOLEAN_FALSE; }
		else { return BOOLEAN_TRUE; }
	}

	/**
	 * Throw an exception with the given SQLState if this BooleanDataValue
	 * is false. This method is useful for evaluating constraints.
	 *
	 * @param sqlState		The SQLState of the exception to throw if
	 *						this SQLBoolean is false.
	 * @param tableName		The name of the table to include in the exception
	 *						message.
	 * @param constraintName	The name of the failed constraint to include
	 *							in the exception message.
	 *
	 * @return	this
	 *
	 * @exception	StandardException	Thrown if this BooleanDataValue
	 *									is false.
	 */
	public BooleanDataValue throwExceptionIfFalse(
									String sqlState,
									String tableName,
									String constraintName)
							throws StandardException
	{
		if ( ( ! isNull() ) && (value == false) )
		{
			throw StandardException.newException(sqlState,
												tableName,
												constraintName);
		}

		return this;
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#typePrecedence */
	public int typePrecedence()
	{
		return TypeId.BOOLEAN_PRECEDENCE;
	}

	/*
	** Support functions
	*/

	/**
	 * Return the SQL truth value for a comparison.
	 *
	 * This method first looks at the operands - if either is null, it
	 * returns the unknown truth value.  This implements "normal" SQL
	 * null semantics, where if any operand is null, the result is null.
	 * Note that there are cases where these semantics are incorrect -
	 * for example, NULL AND FALSE is supposed to be FALSE, not NULL
	 * (the NULL truth value is the same as the UNKNOWN truth value).
	 *
	 * If neither operand is null, it returns a static final variable
	 * containing the SQLBoolean truth value.  It returns different values
	 * depending on whether the truth value is supposed to be nullable.
	 *
	 * This method always returns a pre-allocated static final SQLBoolean.
	 * This is practical because there are so few possible return values.
	 * Using pre-allocated values allows us to avoid constructing new
	 * SQLBoolean values during execution.
	 *
	 * @param leftOperand	The left operand of the binary comparison
	 * @param rightOperand	The right operand of the binary comparison
	 * @param truth			The truth value of the comparison
	 *
	 * @return	A SQLBoolean containing the desired truth value.
	 */

	public static SQLBoolean truthValue(
								DataValueDescriptor leftOperand,
								DataValueDescriptor rightOperand,
								boolean truth)
	{
		/* Return UNKNOWN if either operand is null */
		if (leftOperand.isNull() || rightOperand.isNull())
		{
			return unknownTruthValue();
		}

		/* Return the appropriate SQLBoolean for the given truth value */
		if (truth == true)
		{
			return BOOLEAN_TRUE;
		}
		else
		{
			return BOOLEAN_FALSE;
		}
	}

    /**
     * same as above, but takes a Boolean, if it is null, unknownTruthValue is returned
     */
	public static SQLBoolean truthValue(
								DataValueDescriptor leftOperand,
								DataValueDescriptor rightOperand,
								Boolean truth)
	{
		/* Return UNKNOWN if either operand is null */
		if (leftOperand.isNull() || rightOperand.isNull() || truth==null)
		{
			return unknownTruthValue();
		}

		/* Return the appropriate SQLBoolean for the given truth value */
		if (truth == Boolean.TRUE)
		{
			return BOOLEAN_TRUE;
		}
		else
		{
			return BOOLEAN_FALSE;
		}
	}

	/**
	 * Get a truth value.
	 *
	 * @param value	The value of the SQLBoolean
	 *
 	 * @return	A SQLBoolean with the given truth value
	 */
	public static SQLBoolean truthValue(boolean value)
	{
		/*
		** Return the non-nullable versions of TRUE and FALSE, since they
		** can never be null.
		*/
		if (value == true)
			return BOOLEAN_TRUE;
		else
			return BOOLEAN_FALSE;
	}

	/**
	 * Return an unknown truth value.  Check to be sure the return value is
	 * nullable.
	 *
	 * @return	A SQLBoolean representing the UNKNOWN truth value
	 */
	public static SQLBoolean unknownTruthValue()
	{
		return UNKNOWN;
	}

	/**
	 * Return a false truth value.
	 *
	 *
	 * @return	A SQLBoolean representing the FALSE truth value
	 */
	public static SQLBoolean falseTruthValue()
	{
		return BOOLEAN_FALSE;
	}

	/**
	 * Return a true truth value.
	 *
	 *
	 * @return	A SQLBoolean representing the TRUE truth value
	 */
	public static SQLBoolean trueTruthValue()
	{
		return BOOLEAN_TRUE;
	}
	
	/**
	 * Determine whether this SQLBoolean contains the given boolean value.
	 *
	 * This method is used by generated code to determine when to do
	 * short-circuiting for an AND or OR.
	 *
	 * @param val	The value to look for
	 *
	 * @return	true if the given value equals the value in this SQLBoolean,
	 *			false if not
	 */

	public boolean equals(boolean val)
	{
		if (isNull())
			return false;
		else
			return value == val;
	}
	
	/**
	 * Return an immutable BooleanDataValue with the same value as this.
	 * @return An immutable BooleanDataValue with the same value as this.
	 */
	public BooleanDataValue getImmutable()
	{
		if (isNull())
			return SQLBoolean.UNKNOWN;
		
		return value ? SQLBoolean.BOOLEAN_TRUE : SQLBoolean.BOOLEAN_FALSE;
	}

	/*
	 * String display of value
	 */

	public String toString()
	{
		if (isNull())
			return "NULL";
		else if (value == true)
			return "true";
		else
			return "false";
	}

	/*
	 * Hash code
	 */
	public int hashCode()
	{
		if (isNull())
		{
			return -1;
		}

		return (value) ? 1 : 0;
	}

	/*
	 * useful constants...
	 */
	static final int BOOLEAN_LENGTH		= 1;	// must match the number of bytes written by DataOutput.writeBoolean()

	private static final SQLBoolean BOOLEAN_TRUE = new SQLBoolean(true);
	private static final SQLBoolean BOOLEAN_FALSE = new SQLBoolean(false);
	static final SQLBoolean UNKNOWN = new SQLBoolean();

	/* Static initialization block */
	static
	{
		/* Mark all the static SQLBooleans as immutable */
		BOOLEAN_TRUE.immutable = true;
		BOOLEAN_FALSE.immutable = true;
		UNKNOWN.immutable = true;
	}

    private static final int BASE_MEMORY_USAGE = ClassSize.estimateBaseFromCatalog( SQLBoolean.class);

    public int estimateMemoryUsage()
    {
        return BASE_MEMORY_USAGE;
    }

	/*
	 * object state
	 */
	private boolean value;
	private boolean isnull;
	private boolean immutable;
// GemStone changes BEGIN

  @Override
  public final void toData(final DataOutput out) throws IOException {
    if (!isNull()) {
      out.writeByte(getTypeId());
      out.writeBoolean(this.value);
      return;
    }
    this.writeNullDVD(out);
  }
  @Override
  public final void fromDataForOptimizedResultHolder(final DataInput dis)
      throws IOException, ClassNotFoundException {
    this.value = dis.readBoolean();
    this.isnull = false;
  }

  @Override
  public final void toDataForOptimizedResultHolder(final DataOutput dos)
      throws IOException {
    assert !isNull();
    dos.writeBoolean(this.value);
  }

  /**
   * Optimized write to a byte array at specified offset
   * 
   * @return number of bytes actually written
   */
  @Override
  public final int writeBytes(final byte[] outBytes, final int offset,
      DataTypeDescriptor dtd) {
    outBytes[offset] = (this.value ? (byte)1 : 0);
    return 1;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final byte[] inBytes, int offset,
      final int columnWidth) {
    assert columnWidth == 1: columnWidth;
    this.value = (inBytes[offset] == 1);
    this.isnull = false;
    return 1;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(long memOffset, final int columnWidth, ByteSource bs) {
    assert columnWidth == 1: columnWidth;
    this.value = (Platform.getByte(null, memOffset) == 1);
    this.isnull = false;
    return 1;
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    assert !isNull();
    return ResolverUtils.addByteToBucketHash(this.value ? (byte)1 : 0, hash, 
        getTypeFormatId());
  }

  public static boolean getBoolean(String theValue) throws StandardException {
    String cleanedValue = theValue.trim();
    if (cleanedValue.equalsIgnoreCase("TRUE")) {
      return true;
    }
    else if (cleanedValue.equalsIgnoreCase("FALSE")) {
      return false;
    }
    else if (cleanedValue.length() == 1) {
      switch (cleanedValue.charAt(0)) {
        case '0':
          return false;
        case '1':
          return true;
        default:
          throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
              TypeId.BOOLEAN_NAME, (String)null);
      }
    }
    else {
      throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
          TypeId.BOOLEAN_NAME, (String)null);
    }
  }

  static final boolean getAsBoolean(final byte[] inBytes, final int offset) {
    return (inBytes[offset] == 1);
  }

  static final boolean getAsBoolean(final UnsafeWrapper unsafe,
      final long memOffset) {
    return (unsafe.getByte(memOffset) == 1);
  }

  @Override
  public  byte getTypeId() {
    return DSCODE.BOOLEAN;
  }  
  
// GemStone changes END
}
