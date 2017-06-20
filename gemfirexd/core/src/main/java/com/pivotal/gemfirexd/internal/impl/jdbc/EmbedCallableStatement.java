/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.jdbc.EmbedCallableStatement

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

package com.pivotal.gemfirexd.internal.impl.jdbc;



import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC30Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.HarmonySerialClob;
import com.pivotal.gemfirexd.internal.iapi.types.SQLChar;

import java.net.URL;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Date;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;
import java.sql.RowId;
import java.sql.NClob;
import java.sql.SQLXML;
import java.sql.ParameterMetaData;

import java.io.Reader;
import java.io.InputStream;


/**
 * Local implementation.
 *
 */
public abstract class EmbedCallableStatement extends EmbedPreparedStatement
	implements CallableStatement
{
	/*
	** True if we are of the form ? = CALL() -- i.e. true
	** if we have a return output parameter.
	*/
	private final boolean hasReturnOutputParameter;

	protected boolean	wasNull;

// GemStone changes BEGIN
	protected final boolean isNonCallableStatement;

	/**
	 * @exception SQLException thrown on failure
	 * 
	 * Set rootID=0 and statementLevel=0
	 */
	public EmbedCallableStatement (EmbedConnection conn, String sql,
								   int resultSetType,
								   int resultSetConcurrency,
								   int resultSetHoldability,
								   long id, short execFlags)
		throws SQLException
	{
	    super(conn, sql, false, 
			  resultSetType,
			  resultSetConcurrency,
			  resultSetHoldability,
			  Statement.NO_GENERATED_KEYS,
			  null,
			  null,  id, execFlags, null, 0, 0);
// GemStone changes END

		// mark our parameters as for a callable statement 
		ParameterValueSet pvs = getParms();

		// do we have a return parameter?
		hasReturnOutputParameter = pvs.hasReturnOutputParameter();

		this.isNonCallableStatement = !hasReturnOutputParameter &&
		    !this.preparedStatement.isCallableStatement();
	}

	protected void checkRequiresCallableStatement(Activation activation) {
	}

	protected final boolean executeStatement(Activation a,
                     boolean executeQuery, boolean executeUpdate,
                     boolean skipContextRestore /* GemStone addition */)
		throws SQLException
	{
		if (this.isNonCallableStatement) {
		  return super.executeStatement(a, executeQuery, executeUpdate,
		      skipContextRestore);
		}
		// need this additional check (it's also in the super.executeStatement
		// to ensure we have an activation for the getParams
		checkExecStatus();
		synchronized (getConnectionSynchronization())
		{
			wasNull = false;
			//Don't fetch the getParms into a local varibale
			//at this point because it is possible that the activation
			//associated with this callable statement may have become
			//stale. If the current activation is invalid, a new activation 
			//will be created for it in executeStatement call below. 
			//We should be using the ParameterValueSet associated with
			//the activation associated to the CallableStatement after
			//the executeStatement below. That ParameterValueSet is the
			//right object to hold the return value from the CallableStatement.
			try
			{
				getParms().validate();
			} catch (StandardException e)
			{
				throw EmbedResultSet.noStateChangeException(e,
				    null /* GemStoneAddition */);
			}

			/* KLUDGE - ? = CALL ... returns a ResultSet().  We
			 * need executeUpdate to be false in that case.
			 */
			boolean execResult = super.executeStatement(a, executeQuery,
				(executeUpdate && (! hasReturnOutputParameter)),
				false /* GemStone addition */);

			//Fetch the getParms into a local variable now because the
			//activation associated with a CallableStatement at this 
			//point(after the executStatement) is the current activation. 
			//We can now safely stuff the return value of the 
			//CallableStatement into the following ParameterValueSet object.
			ParameterValueSet pvs = getParms();

			/*
			** If we have a return parameter, then we
			** consume it from the returned ResultSet
			** reset the ResultSet set to null.
			*/
			if (hasReturnOutputParameter)
			{
				if (SanityManager.DEBUG)
				{
					SanityManager.ASSERT(results!=null, "null results even though we are supposed to have a return parameter");
				}
				boolean gotRow = results.next();
				if (SanityManager.DEBUG)
				{
					SanityManager.ASSERT(gotRow, "the return resultSet didn't have any rows");
				}

				try
				{
					DataValueDescriptor returnValue = pvs.getReturnValueForSet();
					returnValue.setValueFromResultSet(results, 1, true);
				} catch (StandardException e)
				{
					throw EmbedResultSet.noStateChangeException(e,
					    null /* GemStoneAddition */);
				}
				finally {
					results.close();
// GemStone changes BEGIN
					setResults(null);
					/* (original code)
					results = null;
					*/
// GemStone changes END
				}

				// This is a form of ? = CALL which current is not a procedure call.
				// Thus there cannot be any user result sets, so return false. execResult
				// is set to true since a result set was returned, for the return parameter.
				execResult = false;
			}
			return execResult;
		}
	}

	/*
	* CallableStatement interface
	* (the PreparedStatement part implemented by EmbedPreparedStatement)
	*/

	/**
	 * @see CallableStatement#registerOutParameter
	 * @exception SQLException NoOutputParameters thrown.
	 */
	public final void registerOutParameter(int parameterIndex, int sqlType)
		throws SQLException 
	{
		checkStatus();

		try {
			getParms().registerOutParameter(parameterIndex-1, sqlType, -1);
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
	 * @see CallableStatement#registerOutParameter
     * @exception SQLException NoOutputParameters thrown.
     */
    public final void registerOutParameter(int parameterIndex, int sqlType, int scale)
	    throws SQLException 
	{
		checkStatus();

		if (scale < 0)
			throw newSQLException(SQLState.BAD_SCALE_VALUE, scale);
		try {
			getParms().registerOutParameter(parameterIndex-1, sqlType, scale);
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}


	/**
	 * JDBC 2.0
	 *
	 * Registers the designated output parameter
	 *
	 * @exception SQLException if a database-access error occurs.
	 */
 	public void registerOutParameter(int parameterIndex, int sqlType, 
 									 String typeName) 
 		 throws SQLException
 	{
 		throw Util.notImplemented("registerOutParameter");
 	}
 		 
 

    /**
	 * @see CallableStatement#wasNull
     * @exception SQLException NoOutputParameters thrown.
     */
    public boolean wasNull() throws SQLException 
	{
		checkStatus();
		return wasNull;
	}

    /**
	 * @see CallableStatement#getString
     * @exception SQLException NoOutputParameters thrown.
     */
    public String getString(int parameterIndex) throws SQLException 
	{
		checkStatus();
		try {
			String v =  getParms().getParameterForGet(parameterIndex-1).getString();
			wasNull = (v == null);
			return v;

		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
	 * @see CallableStatement#getBoolean
     * @exception SQLException NoOutputParameters thrown.
     */
    public boolean getBoolean(int parameterIndex) throws SQLException 
	{
		checkStatus();
		try {
			DataValueDescriptor param = getParms().getParameterForGet(parameterIndex-1);
			boolean v = param.getBoolean();
			wasNull = (!v) && param.isNull();
			return v;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
	 * @see CallableStatement#getByte
     * @exception SQLException NoOutputParameters thrown.
     */
    public byte getByte(int parameterIndex) throws SQLException 
	{
		checkStatus();
		try {
			DataValueDescriptor param = getParms().getParameterForGet(parameterIndex-1);
			byte b = param.getByte();
			wasNull = (b == 0) && param.isNull();
			return b;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
	 * @see CallableStatement#getShort
     * @exception SQLException NoOutputParameters thrown.
     */
    public short getShort(int parameterIndex) throws SQLException 
	{
		checkStatus();
		try {
			DataValueDescriptor param = getParms().getParameterForGet(parameterIndex-1);
			short s = param.getShort();
			wasNull = (s == 0) && param.isNull();
			return s;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
	 * @see CallableStatement#getInt
     * @exception SQLException NoOutputParameters thrown.
     */
    public int getInt(int parameterIndex) throws SQLException 
	{
		checkStatus();

		try {
			DataValueDescriptor param = getParms().getParameterForGet(parameterIndex-1);
			int v = param.getInt();
			wasNull = (v == 0) && param.isNull();
			return v;

		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
	 * @see CallableStatement#getLong
     * @exception SQLException NoOutputParameters thrown.
     */
    public long getLong(int parameterIndex) throws SQLException 
	{
		checkStatus();
		try {
			DataValueDescriptor param = getParms().getParameterForGet(parameterIndex-1);
			long v = param.getLong();
			wasNull = (v == 0L) && param.isNull();
			return v;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
	 * @see CallableStatement#getFloat
     * @exception SQLException NoOutputParameters thrown.
     */
    public float getFloat(int parameterIndex) throws SQLException 
	{
		checkStatus();
		try {
			DataValueDescriptor param = getParms().getParameterForGet(parameterIndex-1);
			float v = param.getFloat();
			wasNull = (v == 0.0) && param.isNull();
			return v;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
	 * @see CallableStatement#getDouble
     * @exception SQLException NoOutputParameters thrown.
     */
    public double getDouble(int parameterIndex) throws SQLException 
	{
		checkStatus();
		try {
			DataValueDescriptor param = getParms().getParameterForGet(parameterIndex-1);
			double v = param.getDouble();
			wasNull = (v == 0.0) && param.isNull();
			return v;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
	 * @see CallableStatement#getBytes
     * @exception SQLException NoOutputParameters thrown.
     */
    public byte[] getBytes(int parameterIndex) throws SQLException 
	{
		checkStatus();
		try {
			byte[] v =  getParms().getParameterForGet(parameterIndex-1).getBytes();
			wasNull = (v == null);
			return v;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
	 * @see CallableStatement#getDate
     * @exception SQLException NoOutputParameters thrown.
     */
    public Date getDate(int parameterIndex) throws SQLException
	{
		checkStatus();
		try {
			Date v =  getParms().getParameterForGet(parameterIndex-1).getDate(getCal());
			wasNull = (v == null);
			return v;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
	 * @see CallableStatement#getTime
     * @exception SQLException NoOutputParameters thrown.
     */
	public Time getTime(int parameterIndex) throws SQLException 
	{
		checkStatus();
		try {
			Time v =  getParms().getParameterForGet(parameterIndex-1).getTime(getCal());
			wasNull = (v == null);
			return v;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
	 * @see CallableStatement#getTimestamp
     * @exception SQLException NoOutputParameters thrown.
     */
    public Timestamp getTimestamp(int parameterIndex)
	    throws SQLException 
	{
		checkStatus();
		try {
			Timestamp v =  getParms().getParameterForGet(parameterIndex-1).getTimestamp(getCal());
			wasNull = (v == null);
			return v;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}
    /**
     * Get the value of a SQL DATE parameter as a java.sql.Date object
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @return the parameter value; if the value is SQL NULL, the result is 
     * null
     * @exception SQLException if a database-access error occurs.
     */
    public java.sql.Date getDate(int parameterIndex, Calendar cal) 
      throws SQLException 
	{
		return getDate(parameterIndex);
	}

    /**
     * Get the value of a SQL TIME parameter as a java.sql.Time object.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @return the parameter value; if the value is SQL NULL, the result is 
	 * null
     * @exception SQLException if a database-access error occurs.
     */
    public java.sql.Time getTime(int parameterIndex, Calendar cal) 
      throws SQLException 
	{
		return getTime(parameterIndex);
	}

    /**
     * Get the value of a SQL TIMESTAMP parameter as a java.sql.Timestamp 
     * object.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @return the parameter value; if the value is SQL NULL, the result is 
     * null
     * @exception SQLException if a database-access error occurs.
     */
    public java.sql.Timestamp getTimestamp(int parameterIndex, Calendar cal) 
      throws SQLException 
	{
		return getTimestamp(parameterIndex);
	}

    /**
	 * @see CallableStatement#getObject
     * @exception SQLException NoOutputParameters thrown.
     */
	public final Object getObject(int parameterIndex) throws SQLException 
	{
		checkStatus();
		try {
			Object v = getParms().getParameterForGet(parameterIndex-1).getObject();
			wasNull = (v == null);
			return v;

		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}
	/**
	    * JDBC 3.0
	    *
	    * Retrieve the value of the designated JDBC DATALINK parameter as a java.net.URL object
	    *
	    * @param parameterIndex - the first parameter is 1, the second is 2
	    * @return a java.net.URL object that represents the JDBC DATALINK value used as
	    * the designated parameter
	    * @exception SQLException Feature not implemented for now.
		*/
		public URL getURL(int parameterIndex)
	    throws SQLException
		{
			throw Util.notImplemented();
		}

		/**
	    * JDBC 3.0
	    *
	    * Sets the designated parameter to the given java.net.URL object. The driver
	    * converts this to an SQL DATALINK value when it sends it to the database.
	    *
	    * @param parameterName - the name of the parameter
	    * @param val - the parameter value
	    * @exception SQLException Feature not implemented for now.
		*/
		public void setURL(String parameterName, URL val)
	    throws SQLException
		{
			throw Util.notImplemented();
		}

		/**
	    * JDBC 3.0
	    *
	    * Retrieves the value of a JDBC DATALINK parameter as a java.net.URL object
	    *
	    * @param parameterName - the name of the parameter
	    * @return the parameter value. If the value is SQL NULL, the result is null.
	    * @exception SQLException Feature not implemented for now.
		*/
		public URL getURL(String parameterName)
	    throws SQLException
		{
			throw Util.notImplemented();
		}

    /**
     * JDBC 2.0
     *
     * Get a BLOB OUT parameter.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @return an object representing a BLOB
     * @exception SQLException if a database-access error occurs.
     */
    public Blob getBlob (int parameterIndex) throws SQLException {
		checkStatus();
		try {
			DataValueDescriptor param = getParms().getParameterForGet(parameterIndex-1);
			Blob v = (Blob) param.getObject();
			wasNull = (v == null);
			return v;
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
    }

    /**
     * JDBC 2.0
     *
     * Get a CLOB OUT parameter.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @return an object representing a CLOB
     * @exception SQLException if a database-access error occurs.
     */
    public Clob getClob (int parameterIndex) throws SQLException {
		checkStatus();
		try {
			DataValueDescriptor param = getParms().getParameterForGet(parameterIndex-1);
// GemStone changes BEGIN
			// explicitly wrap in Clob as in EmbedResultSet.getClob
			if (param instanceof SQLChar) {
			  char[] chars = ((SQLChar)param).getCharArray(true);
			  if ((wasNull = (chars == null))) {
			    return null;
			  } else {
			    return HarmonySerialClob.wrapChars(chars);
			  }
			} else {
			  String str = param.getString();
			  if ((wasNull = (str == null))) {
			    return null;
			  } else {
			    return new HarmonySerialClob(str);
			  }
			}
			/* (original code)
			Clob v = (Clob) param.getObject();
			wasNull = (v == null);
			return v;
			*/
		} catch (StandardException e)
		{
			throw EmbedResultSet.noStateChangeException(e,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
    }
    
	public void addBatch() throws SQLException {

		checkStatus();
		ParameterValueSet pvs = getParms();

		int numberOfParameters = pvs.getParameterCount();

		for (int j=1; j<=numberOfParameters; j++) {

			switch (pvs.getParameterMode(j)) {
			case JDBC30Translation.PARAMETER_MODE_IN:
			case JDBC30Translation.PARAMETER_MODE_UNKNOWN:
				break;
			case JDBC30Translation.PARAMETER_MODE_OUT:
			case JDBC30Translation.PARAMETER_MODE_IN_OUT:
				throw newSQLException(SQLState.OUTPUT_PARAMS_NOT_ALLOWED);
			}
		}

		super.addBatch();
	}
  
  // GemStone changes BEGIN
  // JDBC 4.0 methods so will compile in JDK 1.6
  public Reader getCharacterStream(String parameterName)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public Reader getNCharacterStream(int parameterIndex)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public Reader getNCharacterStream(String parameterName)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public String getNString(int parameterIndex)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public String getNString(String parameterName)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setBlob(String parameterName, Blob x)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setClob(String parameterName, Clob x)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public RowId getRowId(int parameterIndex) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public RowId getRowId(String parameterName) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setRowId(String parameterName, RowId x) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  
  public void setNString(String parameterName, String value)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNCharacterStream(String parameterName, Reader value, long length)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNClob(String parameterName, NClob value) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setClob(String parameterName, Reader reader, long length)
  throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setBlob(String parameterName, InputStream inputStream, long length)
  throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNClob(String parameterName, Reader reader, long length)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public NClob getNClob(int i) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  
  public NClob getNClob(String parameterName) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
    
  }
  
  public SQLXML getSQLXML(int parameterIndex) throws SQLException {
    throw Util.notImplemented();
  }
  
  public SQLXML getSQLXML(String parametername) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
    
  public void setRowId(int parameterIndex, RowId x) throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNString(int index, String value) throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNCharacterStream(int parameterIndex, Reader value)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNCharacterStream(int index, Reader value, long length) throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNClob(int index, NClob value) throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNClob(int parameterIndex, Reader reader)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNClob(int parameterIndex, Reader reader, long length)
  throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }    
  
  public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }  
  
  public ParameterMetaData getParameterMetaData()
  throws SQLException
  {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public boolean isWrapperFor(Class<?> interfaces) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setAsciiStream(String parameterName, InputStream x)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setBinaryStream(String parameterName, InputStream x)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setBlob(String parameterName, InputStream inputStream)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setCharacterStream(String parameterName, Reader reader)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setClob(String parameterName, Reader reader)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNCharacterStream(String parameterName, Reader value)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setNClob(String parameterName, Reader reader)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public <T> T unwrap(java.lang.Class<T> interfaces) 
  throws SQLException{
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setAsciiStream(String parameterName, InputStream x, long length)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setBinaryStream(String parameterName, InputStream x, long length)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  public void setCharacterStream(String parameterName, Reader x, long length)
  throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.0");
  }
  
  // JDBC 4.1 methods so will compile in JDK 1.7
  public Object getObject(int parameterIndex, Map<String, Class<?>> map)
      throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }

  public Object getObject(String parameterName, Map<String, Class<?>> map)
      throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }

  public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }

  public <T> T getObject(String parameterName, Class<T> type)
      throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }

  public void closeOnCompletion() throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }

  public boolean isCloseOnCompletion() throws SQLException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }
  // GemStone changes END
}

