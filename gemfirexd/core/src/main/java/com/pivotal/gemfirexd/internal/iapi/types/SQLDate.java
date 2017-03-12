/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.SQLDate

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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Calendar;
import java.util.GregorianCalendar;

import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.ByteArrayDataOutput;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.LocaleFinder;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

/**
 * This contains an instance of a SQL Date.
 * <p>
 * The date is stored as int (year << 16 + month << 8 + day)
 * Null is represented by an encodedDate value of 0.
 * Some of the static methods in this class are also used by SQLTime and SQLTimestamp
 * so check those classes if you change the date encoding
 *
 * PERFORMANCE OPTIMIZATION:
 * The java.sql.Date object is only instantiated when needed
 * do to the overhead of Date.valueOf(), etc. methods.
 */

public final class SQLDate extends DataType
						implements DateTimeDataValue
{

	private int	encodedDate;	//year << 16 + month << 8 + day

	// The cached value.toString()
	private String	valueString;

    private static final int BASE_MEMORY_USAGE = ClassSize.estimateBaseFromCatalog( SQLDate.class);

    public int estimateMemoryUsage()
    {
        return BASE_MEMORY_USAGE + ClassSize.estimateMemoryUsage( valueString);
    } // end of estimateMemoryUsage

// GemStone changes BEGIN
    @Override
    public final int getEncodedTime() {
      return 0;
    }

    @Override
    public final int getNanos() {
      return 0;
    }

    @Override
    public final
// GemStone changes END
    int getEncodedDate()
    {
        return encodedDate;
    }
    
	/*
	** DataValueDescriptor interface
	** (mostly implemented in DataType)
	*/

	public String getString()
	{
		//format is [yyy]y-mm-dd e.g. 1-01-01, 9999-99-99
		if (!isNull())
		{
			if (valueString == null)
			{
				valueString = encodedDateToString(encodedDate);
			}
			return valueString;
		}
		else
		{
			if (SanityManager.DEBUG)
			{
				if (valueString != null)
				{
					SanityManager.THROWASSERT(
						"valueString expected to be null, not " +
						valueString);
				}
			}
			return null;
		}
	}

	/**
		getTimestamp returns a timestamp with the date value 
		time is set to 00:00:00.0
	*/
	public Timestamp getTimestamp( Calendar cal) 
	{
		if (isNull())
		{
			return null;
		}
        
        return new Timestamp(getTimeInMillis(cal));
    }

    /**
     * Convert the date into a milli-seconds since the epoch
     * with the time set to 00:00 based upon the passed in Calendar.
     */
    public long getTimeInMillis(Calendar cal)
    {
        if( cal == null)
            cal = ClientSharedData.getDefaultCalendar();
        cal.clear();
        
        SQLDate.setDateInCalendar(cal, encodedDate);
        
        return cal.getTimeInMillis();
    }
//  GemStone changes BEGIN    
    /**
     * Set the date portion of a date-time value into
     * the passed in Calendar object from its encodedDate
     * value. Only the YEAR, MONTH and DAY_OF_MONTH
     * fields are modified. The remaining
     * state of the Calendar is not modified.
     */
   public static void setDateInCalendar(Calendar cal, int encodedDate)
    {
        // Note Calendar uses 0 for January, Derby uses 1.
        cal.set(getYear(encodedDate),
                getMonth(encodedDate)-1, getDay(encodedDate));     
    }
// GemStone changes END
	/**
		getObject returns the date value

	 */
	public Object getObject()
	{
		return getDate( (Calendar) null);
	}
		
	public int getLength()
	{
		return 4;
	}

	/* this is for DataType's error generator */
	public String getTypeName()
	{
		return "DATE";
	}

	/*
	 * Storable interface, implies Externalizable, TypedFormat
	 */

	/**
		Return my format identifier.

		@see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId
	*/
	public int getTypeFormatId() {
		return StoredFormatIds.SQL_DATE_ID;
	}

	/** 
		@exception IOException error writing data

	*/
	public void writeExternal(ObjectOutput out) throws IOException {
    // GemStone changes BEGIN
    // support for null
    out.writeBoolean(isNull());
    if (isNull()) {
      return;
    }
    // GemStone changes END

		if (SanityManager.DEBUG)
			SanityManager.ASSERT(!isNull(), "writeExternal() is not supposed to be called for null values.");

		out.writeInt(encodedDate);
	}

	/**
	 * @see java.io.Externalizable#readExternal
	 *
	 * @exception IOException	Thrown on error reading the object
	 */
	public void readExternal(ObjectInput in) throws IOException
	{
    // GemStone changes BEGIN
    boolean isNull = in.readBoolean();
    if (isNull) {
      setToNull();
      return;
    }
    // GemStone changes END
    
		encodedDate = in.readInt();

		// reset cached string values
		valueString = null;
	}
	public void readExternalFromArray(ArrayInputStream in) throws IOException
	{
		encodedDate = in.readInt();

		// reset cached string values
		valueString = null;
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#getClone */
	public DataValueDescriptor getClone()
	{
		// Call constructor with all of our info
		return new SQLDate(encodedDate);
	}

	/**
	 * @see DataValueDescriptor#getNewNull
	 */
	public DataValueDescriptor getNewNull()
	{
		return new SQLDate();
	}
	/**
	 * @see com.pivotal.gemfirexd.internal.iapi.services.io.Storable#restoreToNull
	 *
	 */

	public void restoreToNull()
	{
		// clear encodedDate
		encodedDate = 0;

		// clear cached valueString
		valueString = null;
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
		throws SQLException, StandardException
	{
        setValue(resultSet.getDate(colNumber), (Calendar) null);
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
		/* get the comparison date values */
		int otherVal = 0;

		/* if the argument is another SQLDate
		 * get the encodedDate
		 */
		if (other instanceof SQLDate)
		{
			otherVal = ((SQLDate)other).encodedDate; 
		}
		else 
		{
			/* O.K. have to do it the hard way and calculate the numeric value
			 * from the value
			 */
			otherVal = SQLDate.computeEncodedDate(other.getDate(
			    ClientSharedData.getDefaultCleanCalendar()));
		}
		if (encodedDate > otherVal)
			comparison = 1;
		else if (encodedDate < otherVal)
			comparison = -1;
		else 
			comparison = 0;

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
	public SQLDate() {
	}

	public SQLDate(Date value) throws StandardException
	{
		parseDate(value);
	}
    
    private void parseDate( java.util.Date value) throws StandardException
	{
		encodedDate = computeEncodedDate(value);
	}

	private SQLDate(int encodedDate) {
		this.encodedDate = encodedDate;
	}

    /**
     * Construct a date from a string. The allowed date formats are:
     *<ol>
     *<li>ISO: yyyy-mm-dd
     *<li>IBM USA standard: mm/dd/yyyy
     *<li>IBM European standard: dd.mm.yyyy
     *</ol>
     * Trailing blanks may be included; leading zeros may be omitted from the month and day portions.
     *
     * @param dateStr
     * @param isJdbcEscape if true then only the JDBC date escape syntax is allowed
     * @param localeFinder
     *
     * @exception StandardException if the syntax is invalid or the value is out of range.
     */
    public SQLDate( String dateStr, boolean isJdbcEscape, LocaleFinder localeFinder)
        throws StandardException
    {
        parseDate( dateStr, isJdbcEscape, localeFinder, (Calendar) null);
    }

    /**
     * Construct a date from a string. The allowed date formats are:
     *<ol>
     *<li>ISO: yyyy-mm-dd
     *<li>IBM USA standard: mm/dd/yyyy
     *<li>IBM European standard: dd.mm.yyyy
     *</ol>
     * Trailing blanks may be included; leading zeros may be omitted from the month and day portions.
     *
     * @param dateStr
     * @param isJdbcEscape if true then only the JDBC date escape syntax is allowed
     * @param localeFinder
     *
     * @exception StandardException if the syntax is invalid or the value is out of range.
     */
    public SQLDate( String dateStr, boolean isJdbcEscape, LocaleFinder localeFinder, Calendar cal)
        throws StandardException
    {
        parseDate( dateStr, isJdbcEscape, localeFinder, cal);
    }

    static final char ISO_SEPARATOR = '-';
    private static final char[] ISO_SEPARATOR_ONLY = {ISO_SEPARATOR};
    private static final char IBM_USA_SEPARATOR = '/';
    private static final char[] IBM_USA_SEPARATOR_ONLY = {IBM_USA_SEPARATOR};
    private static final char IBM_EUR_SEPARATOR = '.';
    private static final char[] IBM_EUR_SEPARATOR_ONLY = {IBM_EUR_SEPARATOR};
    private static final char[] END_OF_STRING = {(char) 0};
    
    private void parseDate( String dateStr, boolean isJdbcEscape, LocaleFinder localeFinder, Calendar cal)
        throws StandardException
    {
        boolean validSyntax = true;
        DateTimeParser parser = new DateTimeParser( dateStr);
        int year = 0;
        int month = 0;
        int day = 0;
        StandardException thrownSE = null;

        try
        {
            switch( parser.nextSeparator())
            {
            case ISO_SEPARATOR:
                encodedDate = SQLTimestamp.parseDateOrTimestamp( parser, false)[0];
                valueString = parser.getTrimmedString();
                return;

            case IBM_USA_SEPARATOR:
                if( isJdbcEscape)
                {
                    validSyntax = false;
                    break;
                }
                month = parser.parseInt( 2, true, IBM_USA_SEPARATOR_ONLY, false);
                day = parser.parseInt( 2, true, IBM_USA_SEPARATOR_ONLY, false);
                year = parser.parseInt( 4, false, END_OF_STRING, false);
                break;

            case IBM_EUR_SEPARATOR:
                if( isJdbcEscape)
                {
                    validSyntax = false;
                    break;
                }
                day = parser.parseInt( 2, true, IBM_EUR_SEPARATOR_ONLY, false);
                month = parser.parseInt( 2, true, IBM_EUR_SEPARATOR_ONLY, false);
                year = parser.parseInt( 4, false, END_OF_STRING, false);
                break;

            default:
                validSyntax = false;
            }
        }
        catch( StandardException se)
        {
            validSyntax = false;
            thrownSE = se;
        }
        if( validSyntax)
        {
            valueString = parser.checkEnd();
            encodedDate = computeEncodedDate( year, month, day);
        }
        else
        {
            // See if it is a localized date or timestamp.
            dateStr = StringUtil.trimTrailing( dateStr);
            DateFormat dateFormat = null;
            if( localeFinder == null)
                dateFormat = DateFormat.getDateInstance();
            else if( cal == null)
                dateFormat = localeFinder.getDateFormat();
            else
                dateFormat = (DateFormat) localeFinder.getDateFormat().clone();
            if( cal != null)
                dateFormat.setCalendar( cal);
            try
            {
                encodedDate = computeEncodedDate( dateFormat.parse( dateStr), cal);
            }
            catch( ParseException pe)
            {
                // Maybe it is a localized timestamp
                try
                {
                    encodedDate = SQLTimestamp.parseLocalTimestamp( dateStr, localeFinder, cal)[0];
                }
                catch( ParseException pe2)
                {
                    if( thrownSE != null)
                        throw thrownSE;
                    throw StandardException.newException( SQLState.LANG_DATE_SYNTAX_EXCEPTION,dateStr);
                }
            }
            valueString = dateStr;
        }
    } // end of parseDate

	/**
	 * Set the value from a correctly typed Date object.
	 * @throws StandardException 
	 */
	void setObject(Object theValue) throws StandardException
	{
		setValue((Date) theValue);
	}

	protected void setFrom(DataValueDescriptor theValue) throws StandardException {

		// Same format means same type SQLDate
		if (theValue instanceof SQLDate) {
			restoreToNull();
			encodedDate = ((SQLDate) theValue).encodedDate;
		}
        else
        {
            GregorianCalendar cal = ClientSharedData.getDefaultCleanCalendar();
			setValue(theValue.getDate( cal), cal);
        }
	}

	/**
		@see DateTimeDataValue#setValue

	 */
	public void setValue(Date value, Calendar cal) throws StandardException
	{
		restoreToNull();
		encodedDate = computeEncodedDate((java.util.Date) value, cal);
	}

	/**
		@see DateTimeDataValue#setValue

	 */
	public void setValue(Timestamp value, Calendar cal) throws StandardException
	{
		restoreToNull();
		encodedDate = computeEncodedDate((java.util.Date) value, cal);
	}


	public void setValue(String theValue)
	    throws StandardException
	{
		restoreToNull();                

		if (theValue != null)
		{
                  //      GemStone changes BEGIN
                  //Asif
           /* DatabaseContext databaseContext = (DatabaseContext) ContextService.getContext(DatabaseContext.CONTEXT_ID);
            parseDate( theValue,
                       false,
                       (databaseContext == null) ? null : databaseContext.getDatabase(),
                       (Calendar) null);*/
                       
            parseDate( theValue,
                       false,
                       Misc.getMemStore().getDatabase(),
                       (Calendar) null);
            //      GemStone changes END
        }
	}

	/*
	** SQL Operators
	*/

    NumberDataValue nullValueInt() {
        return new SQLInteger();
    }

    
	/**
	 * @see DateTimeDataValue#getYear
	 * 
	 * @exception StandardException		Thrown on error
	 */
	public NumberDataValue getYear(NumberDataValue result)
        throws StandardException
	{
        if (isNull()) {
            return nullValueInt();
        } else {    
            return SQLDate.setSource(getYear(encodedDate), result);
        }
    }

	/**
	 * @see DateTimeDataValue#getMonth
	 * 
	 * @exception StandardException		Thrown on error
	 */
	public NumberDataValue getMonth(NumberDataValue result)
							throws StandardException
	{
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(getMonth(encodedDate), result);
        }
	}

	/**
	 * @see DateTimeDataValue#getDate
	 * 
	 * @exception StandardException		Thrown on error
	 */
	public NumberDataValue getDate(NumberDataValue result)
							throws StandardException
	{
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(getDay(encodedDate), result);
        }
	}

	/**
	 * @see DateTimeDataValue#getHours
	 * 
	 * @exception StandardException		Thrown on error
	 */
	public NumberDataValue getHours(NumberDataValue result)
							throws StandardException
	{
		throw StandardException.newException(SQLState.LANG_UNARY_FUNCTION_BAD_TYPE, 
						"getHours", "Date");
	}

	/**
	 * @see DateTimeDataValue#getMinutes
	 * 
	 * @exception StandardException		Thrown on error
	 */
	public NumberDataValue getMinutes(NumberDataValue result)
							throws StandardException
	{
		throw StandardException.newException(SQLState.LANG_UNARY_FUNCTION_BAD_TYPE, 
						"getMinutes", "Date");
	}

	/**
	 * @see DateTimeDataValue#getSeconds
	 * 
	 * @exception StandardException		Thrown on error
	 */
	public NumberDataValue getSeconds(NumberDataValue result)
							throws StandardException
	{
		throw StandardException.newException(SQLState.LANG_UNARY_FUNCTION_BAD_TYPE, 
						"getSeconds", "Date");
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
			return getDate( (Calendar) null).toString();
		}
	}

	/*
	 * Hash code
	 */
	public int hashCode()
	{
		return encodedDate;
	}

	/** @see DataValueDescriptor#typePrecedence */
	public int	typePrecedence()
	{
		return TypeId.DATE_PRECEDENCE;
	}

	/**
	 * Check if the value is null.  
	 * encodedDate is 0 if the value is null
	 *
	 * @return Whether or not value is logically null.
	 */
	public final boolean isNull()
	{
		return (encodedDate == 0);
	}

	/**
	 * Get the value field.  We instantiate the field
	 * on demand.
	 *
	 * @return	The value field.
	 */
	public Date getDate( Calendar cal)
	{
        if (isNull())
            return null;
        
        return new Date(getTimeInMillis(cal));
	}

	/**
	 * Get the year from the encodedDate.
	 *
	 * @param encodedDate	the encoded date
	 * @return	 			year value.
	 */
	static int getYear(int encodedDate)
	{
		return (encodedDate >>> 16);
	}

	/**
	 * Get the month from the encodedDate,
     * January is one.
	 *
	 * @param encodedDate	the encoded date
	 * @return	 			month value.
	 */
	static int getMonth(int encodedDate)
	{
		return ((encodedDate >>> 8) & 0x00ff);
	}

	/**
	 * Get the day from the encodedDate.
	 *
	 * @param encodedDate	the encoded date
	 * @return	 			day value.
	 */
	static int getDay(int encodedDate)
	{
		return (encodedDate & 0x00ff);
	}
	/**
	 *	computeEncodedDate extracts the year, month and date from
	 *	a Calendar value and encodes them as
	 *		year << 16 + month << 8 + date
	 *	Use this function will help to remember to add 1 to month
	 *  which is 0 based in the Calendar class
	 *	@param cal	the Calendar 
	 *	@return 		the encodedDate
     *
     *  @exception StandardException if the value is out of the DB2 date range
	 */
	static int computeEncodedDate(Calendar cal) throws StandardException
	{
		return computeEncodedDate(cal.get(Calendar.YEAR),
                                  cal.get(Calendar.MONTH) + 1,
                                  cal.get(Calendar.DATE));
	}

// GemStone changes BEGIN
    // made public for tests
    public
// GemStone changes END
    static int computeEncodedDate( int y, int m, int d) throws StandardException
    {
        int maxDay = 31;
        switch( m)
        {
        case 4:
        case 6:
        case 9:
        case 11:
            maxDay = 30;
            break;
                
        case 2:
            // leap years are every 4 years except for century years not divisble by 400.
            maxDay = ((y % 4) == 0 && ((y % 100) != 0 || (y % 400) == 0)) ? 29 : 28;
            break;
        }
        if( y < 1 || y > 9999
            || m < 1 || m > 12
            || d < 1 || d > maxDay)
        	//Gemstone changes BEGIN
        	// Give better message for range exception
            throw StandardException.newException( SQLState.LANG_DATE_RANGE_EXCEPTION, 
            		"Year = " + y + " Month = " + m + " Day = " + d);
            //Gemstone changes END
        return (y << 16) + (m << 8) + d;
    }

    /**
     * Convert a date to the JDBC representation and append it to a string buffer.
     *
     * @param year
     * @param month 1 based (January == 1)
     * @param day
     * @param sb The string representation is appended to this StringBuilder
     */
    static void dateToString( int year, int month, int day, StringBuilder sb)
    {
        String yearStr = Integer.toString( year);
        for( int i = yearStr.length(); i < 4; i++)
            sb.append( '0');
		sb.append(yearStr);
		sb.append(ISO_SEPARATOR);

		String monthStr = Integer.toString( month);
		String dayStr = Integer.toString( day);
		if (monthStr.length() == 1)
			sb.append('0');
		sb.append(monthStr);
		sb.append(ISO_SEPARATOR);
		if (dayStr.length() == 1)
			sb.append('0');
		sb.append(dayStr);
    } // end of dateToString
    
	/**
	 * Get the String version from the encodedDate.
	 *
	 * @return	 string value.
	 */
	static String encodedDateToString(int encodedDate)
	{
		StringBuilder vstr = new StringBuilder();
        dateToString( getYear(encodedDate), getMonth(encodedDate), getDay(encodedDate), vstr);
		return vstr.toString();
	}

	// International Support

	/**
	 * International version of getString(). Overrides getNationalString
	 * in DataType for date, time, and timestamp.
	 *
	 * @exception StandardException		Thrown on error
	 */
	protected String getNationalString(LocaleFinder localeFinder) throws StandardException
	{
		if (isNull())
		{
			return getString();
		}

		return localeFinder.getDateFormat().format(getDate(
		    ClientSharedData.getDefaultCleanCalendar()));
	}

	/**
		This helper routine tests the nullability of various parameters
		and sets up the result appropriately.

		If source is null, a new NumberDataValue is built. 

		@exception StandardException	Thrown on error
	 */
	static NumberDataValue setSource(int value,
										NumberDataValue source)
									throws StandardException {
		/*
		** NOTE: Most extract operations return int, so the generation of
		** a SQLInteger is here.  Those extract operations that return
		** something other than int must allocate the source NumberDataValue
		** themselves, so that we do not allocate a SQLInteger here.
		*/
		if (source == null)
			source = new SQLInteger();

		source.setValue(value);

		return source;
	}
	/**
     * Compute the encoded date given a date
	 *
	 */
	private static int computeEncodedDate(java.util.Date value) throws StandardException
	{
        return computeEncodedDate( value, null);
    }

    static int computeEncodedDate(java.util.Date value, Calendar currentCal) throws StandardException
    {
		if (value == null)
			return 0;			//encoded dates have a 0 value for null
        if( currentCal == null)
            currentCal = ClientSharedData.getDefaultCleanCalendar();
		currentCal.setTime(value);
		return SQLDate.computeEncodedDate(currentCal);
	}


        /**
         * Implement the date SQL function: construct a SQL date from a string, number, or timestamp.
         *
         * @param operand Must be a date or a string convertible to a date.
         * @param dvf the DataValueFactory
         *
         * @exception StandardException standard error policy
         */
    public static DateTimeDataValue computeDateFunction( DataValueDescriptor operand,
                                                         DataValueFactory dvf) throws StandardException
    {
        try
        {
            if( operand.isNull())
                return new SQLDate();
            if( operand instanceof SQLDate)
                return (SQLDate) operand.getClone();

            if( operand instanceof SQLTimestamp)
            {
                DateTimeDataValue retVal = new SQLDate();
                retVal.setValue( operand);
                return retVal;
            }
            if( operand instanceof NumberDataValue)
            {
                int daysSinceEpoch = operand.getInt();
                if( daysSinceEpoch <= 0 || daysSinceEpoch > 3652059)
                    throw StandardException.newException( SQLState.LANG_INVALID_FUNCTION_ARGUMENT,
                                                          operand.getString(), "date");
                GregorianCalendar cal = ClientSharedData.getDefaultCleanCalendar();
                cal.set(1970, 0, 1, 12, 0, 0);
                cal.add( Calendar.DATE, daysSinceEpoch - 1);
                return new SQLDate( computeEncodedDate( cal.get( Calendar.YEAR),
                                                        cal.get( Calendar.MONTH) + 1,
                                                        cal.get( Calendar.DATE)));
            }
            String str = operand.getString();
            if( str.length() == 7)
            {
                // yyyyddd where ddd is the day of the year
                int year = SQLTimestamp.parseDateTimeInteger( str, 0, 4);
                int dayOfYear = SQLTimestamp.parseDateTimeInteger( str, 4, 3);
                if( dayOfYear < 1 || dayOfYear > 366)
                    throw StandardException.newException( SQLState.LANG_INVALID_FUNCTION_ARGUMENT,
                                                          operand.getString(), "date");
                GregorianCalendar cal = ClientSharedData.getDefaultCleanCalendar();
                cal.set(year, 0, 1, 2, 0, 0);
                cal.add( Calendar.DAY_OF_YEAR, dayOfYear - 1);
                int y = cal.get( Calendar.YEAR);
                if( y != year)
                    throw StandardException.newException( SQLState.LANG_INVALID_FUNCTION_ARGUMENT,
                                                          operand.getString(), "date");
                return new SQLDate( computeEncodedDate( year,
                                                        cal.get( Calendar.MONTH) + 1,
                                                        cal.get( Calendar.DATE)));
            }
            // Else use the standard cast.
            return dvf.getDateValue( str, false);
        }
        catch( StandardException se)
        {
            if( SQLState.LANG_DATE_SYNTAX_EXCEPTION.startsWith( se.getSQLState()))
                throw StandardException.newException( SQLState.LANG_INVALID_FUNCTION_ARGUMENT,
                                                      operand.getString(), "date");
            throw se;
        }
    } // end of computeDateFunction

    /** Adding this method to ensure that super class' setInto method doesn't get called
      * that leads to the violation of JDBC spec( untyped nulls ) when batching is turned on.
      */     
    public void setInto(PreparedStatement ps, int position) throws SQLException, StandardException {

                  ps.setDate(position, getDate((Calendar) null));
     }


    /**
     * Add a number of intervals to a datetime value. Implements the JDBC escape TIMESTAMPADD function.
     *
     * @param intervalType One of FRAC_SECOND_INTERVAL, SECOND_INTERVAL, MINUTE_INTERVAL, HOUR_INTERVAL,
     *                     DAY_INTERVAL, WEEK_INTERVAL, MONTH_INTERVAL, QUARTER_INTERVAL, or YEAR_INTERVAL
     * @param intervalCount The number of intervals to add
     * @param currentDate Used to convert time to timestamp
     * @param resultHolder If non-null a DateTimeDataValue that can be used to hold the result. If null then
     *                     generate a new holder
     *
     * @return startTime + intervalCount intervals, as a timestamp
     *
     * @exception StandardException
     */
    public DateTimeDataValue timestampAdd( int intervalType,
                                           NumberDataValue intervalCount,
                                           java.sql.Date currentDate,
                                           DateTimeDataValue resultHolder)
        throws StandardException
    {
        return toTimestamp().timestampAdd( intervalType, intervalCount, currentDate, resultHolder);
    }

    private SQLTimestamp toTimestamp() throws StandardException
    {
        return new SQLTimestamp( getEncodedDate(), 0, 0);
    }
    
    /**
     * Finds the difference between two datetime values as a number of intervals. Implements the JDBC
     * TIMESTAMPDIFF escape function.
     *
     * @param intervalType One of FRAC_SECOND_INTERVAL, SECOND_INTERVAL, MINUTE_INTERVAL, HOUR_INTERVAL,
     *                     DAY_INTERVAL, WEEK_INTERVAL, MONTH_INTERVAL, QUARTER_INTERVAL, or YEAR_INTERVAL
     * @param time1
     * @param currentDate Used to convert time to timestamp
     * @param resultHolder If non-null a NumberDataValue that can be used to hold the result. If null then
     *                     generate a new holder
     *
     * @return the number of intervals by which this datetime is greater than time1
     *
     * @exception StandardException
     */
    public NumberDataValue timestampDiff( int intervalType,
                                          DateTimeDataValue time1,
                                          java.sql.Date currentDate,
                                          NumberDataValue resultHolder)
        throws StandardException
    {
        return toTimestamp().timestampDiff( intervalType, time1, currentDate, resultHolder);
    }
// GemStone changes BEGIN
  @Override
  public void toData(final DataOutput out) throws IOException {
    if (!isNull()) {
      out.writeByte(getTypeId());
      out.writeInt(encodedDate);
      return;
    }
    this.writeNullDVD(out);
  }

  @Override
  public final void fromDataForOptimizedResultHolder(final DataInput dis)
      throws IOException, ClassNotFoundException {
    this.encodedDate = dis.readInt();

    // reset cached string values
    this.valueString = null;
  }

  @Override
  public final void toDataForOptimizedResultHolder(final DataOutput dos)
      throws IOException {
    assert !isNull();
    dos.writeInt(this.encodedDate);
  }

  /**
   * Optimized write to a byte array at specified offset
   * 
   * @return number of bytes actually written
   */
  @Override
  public int writeBytes(byte[] outBytes, int offset, DataTypeDescriptor dtd) {
    // never called when value is null
    assert !isNull();
    return RowFormatter.writeInt(outBytes, this.encodedDate, offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final byte[] inBytes, int offset,
      final int columnWidth) {
    this.valueString = null;
    this.encodedDate = RowFormatter.readInt(inBytes, offset);
    return Integer.SIZE >>> 3;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(long memOffset,
			final int columnWidth, ByteSource bs) {
    this.valueString = null;
    this.encodedDate = RowFormatter.readInt(memOffset);
    return Integer.SIZE >>> 3;
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    assert !isNull();
    return ResolverUtils.addIntToBucketHash(this.encodedDate, hash,
        getTypeFormatId());
  }

  static final long getAsDateMillis(final byte[] bytes,
      final int offset, final Calendar cal) {
    final int encodedDate = RowFormatter.readInt(bytes, offset);
    if (encodedDate == 0) return 0L;
    cal.clear();
    SQLDate.setDateInCalendar(cal, encodedDate);
    return cal.getTimeInMillis();
  }

  static final java.sql.Date getAsDate(final byte[] bytes,
      final int offset, final Calendar cal) {
    final int encodedDate = RowFormatter.readInt(bytes, offset);
    if (encodedDate == 0) return null;
    cal.clear();
    SQLDate.setDateInCalendar(cal, encodedDate);
    return new Date(cal.getTimeInMillis());
  }

  static final long getAsDateMillis(final UnsafeWrapper unsafe,
      final long memOffset, final Calendar cal) {
    final int encodedDate = RowFormatter.readInt(memOffset);
    if (encodedDate == 0) return 0L;
    cal.clear();
    SQLDate.setDateInCalendar(cal, encodedDate);
    return cal.getTimeInMillis();
  }

  static final java.sql.Date getAsDate(final UnsafeWrapper unsafe,
      final long memOffset, final Calendar cal) {
    final int encodedDate = RowFormatter.readInt(memOffset);
    if (encodedDate == 0) return null;
    cal.clear();
    SQLDate.setDateInCalendar(cal, encodedDate);
    return new Date(cal.getTimeInMillis());
  }

  static final long getAsTimeStampMicros(final byte[] bytes,
      final int offset, final Calendar cal) {
    final int encodedDate = RowFormatter.readInt(bytes, offset);
    if (encodedDate == 0) return 0L;
    cal.clear();
    SQLDate.setDateInCalendar(cal, encodedDate);
    return cal.getTimeInMillis() * 1000L;
  }

  static final java.sql.Timestamp getAsTimeStamp(final byte[] bytes,
      final int offset, final Calendar cal) {
    final int encodedDate = RowFormatter.readInt(bytes, offset);
    if (encodedDate == 0) return null;
    cal.clear();
    SQLDate.setDateInCalendar(cal, encodedDate);
    return new Timestamp(cal.getTimeInMillis());
  }

  static final long getAsTimeStampMicros(final UnsafeWrapper unsafe,
      final long memOffset, final Calendar cal) {
    final int encodedDate = RowFormatter.readInt(memOffset);
    if (encodedDate == 0) return 0L;
    cal.clear();
    SQLDate.setDateInCalendar(cal, encodedDate);
    return cal.getTimeInMillis() * 1000L;
  }

  static final java.sql.Timestamp getAsTimeStamp(final UnsafeWrapper unsafe,
      final long memOffset, final Calendar cal) {
    final int encodedDate = RowFormatter.readInt(memOffset);
    if (encodedDate == 0) return null;
    cal.clear();
    SQLDate.setDateInCalendar(cal, encodedDate);
    return new Timestamp(cal.getTimeInMillis());
  }

  /** date string has fixed size of 10 chars */
  public static final int DATE_CHARS = 10;

  static String getAsString(final byte[] inBytes, final int offset) {
    final int encodedDate = RowFormatter.readInt(inBytes, offset);
    if (encodedDate == 0) return null;
    char[] str = new char[DATE_CHARS];
    SharedUtils.dateTimeToString(str, 0, SQLDate.getYear(encodedDate),
        SQLDate.getMonth(encodedDate), SQLDate.getDay(encodedDate), -1, -1, -1,
        -1, -1, false);
    return ClientSharedUtils.newWrappedString(str, 0, DATE_CHARS);
  }

  static String getAsString(final UnsafeWrapper unsafe, final long memOffset) {
    final int encodedDate = RowFormatter.readInt(memOffset);
    if (encodedDate == 0) return null;
    char[] str = new char[DATE_CHARS];
    SharedUtils.dateTimeToString(str, 0, SQLDate.getYear(encodedDate),
        SQLDate.getMonth(encodedDate), SQLDate.getDay(encodedDate), -1, -1, -1,
        -1, -1, false);
    return ClientSharedUtils.newWrappedString(str, 0, DATE_CHARS);
  }

  static void writeAsString(final byte[] inBytes, final int offset,
      final ByteArrayDataOutput buffer) {
    final int encodedDate = RowFormatter.readInt(inBytes, offset);
    if (encodedDate == 0) return;
    final int bufferPos = buffer.ensureCapacity(DATE_CHARS, buffer.position());
    SharedUtils.dateTimeToChars(buffer.getData(), bufferPos,
        SQLDate.getYear(encodedDate), SQLDate.getMonth(encodedDate),
        SQLDate.getDay(encodedDate), -1, -1, -1, -1, -1, false);
    buffer.advance(DATE_CHARS);
  }

  static void writeAsString(final long memOffset, ByteArrayDataOutput buffer) {
    final int encodedDate = RowFormatter.readInt(memOffset);
    if (encodedDate == 0) return;
    final int bufferPos = buffer.ensureCapacity(DATE_CHARS, buffer.position());
    SharedUtils.dateTimeToChars(buffer.getData(), bufferPos,
        SQLDate.getYear(encodedDate), SQLDate.getMonth(encodedDate),
        SQLDate.getDay(encodedDate), -1, -1, -1, -1, -1, false);
    buffer.advance(DATE_CHARS);
  }

  @Override
  public byte getTypeId() {
    return DSCODE.DATE;
  }
// GemStone changes END
}
