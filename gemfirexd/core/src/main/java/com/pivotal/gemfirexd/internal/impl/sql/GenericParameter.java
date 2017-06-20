/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.GenericParameter

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

package com.pivotal.gemfirexd.internal.impl.sql;

import java.sql.Types;

import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC30Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;

/**
 * A parameter.  Originally lifted from ParameterValueSet.
 *
 */
//Gemstone changes Begin
final public class GenericParameter
//Gemstone changes End
{

	// These defaults match the Network Server/ JCC max precision and
	// The JCC "guessed" scale. They are used as the defaults for 
	// Decimal out params.
// GemStone changes BEGIN
	private static int DECIMAL_PARAMETER_DEFAULT_PRECISION =
	  Limits.DB2_DEFAULT_DECIMAL_PRECISION;
	/* (original code)
	private static int DECIMAL_PARAMETER_DEFAULT_PRECISION = 31;
	*/
// GemStone changes END
	private static int DECIMAL_PARAMETER_DEFAULT_SCALE = 15;


	/*
	** The parameter set we are part of
	*/
	private final GenericParameterValueSet		pvs;

	/**
	** Our value
	*/
	private DataValueDescriptor				value;

	/**
		Compile time JDBC type identifier.
	*/
	int								jdbcTypeId;

	/**
		Compile time Java class name.
	*/
	String							declaredClassName;

	/**
		Mode of the parameter, from ParameterMetaData
	*/
	short							parameterMode;

	/*
	** If we are set
	*/
	boolean							isSet;

	/*
	** Output parameter values
 	*/
	private final boolean					isReturnOutputParameter;

	/**
		Type that has been registered.
	*/
	int	registerOutType = Types.NULL;
	/**
		Scale that has been registered.
	*/
	int registerOutScale = -1;

	/**
	 * When a decimal output parameter is registered we give it a 
	 * precision
	 */

	int registerOutPrecision = -1;

	/**
	 * Constructor for a Parameter
	 *
	 * @param pvs the parameter set that this is part of
	 * @param isReturnOutputParameter true if this is a return output parameter
	 */
	GenericParameter
	(
		GenericParameterValueSet	pvs,
		boolean						isReturnOutputParameter
	)
	{
		this.pvs = pvs;
		parameterMode = (this.isReturnOutputParameter = isReturnOutputParameter)
			? (short) JDBC30Translation.PARAMETER_MODE_OUT : (short) JDBC30Translation.PARAMETER_MODE_IN;
	}

	/**
	 * Clone myself.  It is a shallow copy for everything but
	 * the underlying data wrapper and its value -- e.g. for
	 * everything but the underlying SQLInt and its int.
	 *
	 * @param pvs the parameter value set
	 *
	 * @return a new generic parameter.
	 */
	public GenericParameter getClone(GenericParameterValueSet pvs)
	{
		GenericParameter gpClone = new GenericParameter(pvs, isReturnOutputParameter);
		gpClone.initialize(this.getValue().getClone(), jdbcTypeId, declaredClassName);
		gpClone.isSet = true;

		return gpClone;
	}
	//Gemstone changes Begin
	/**
	 * Set the DataValueDescriptor and type information for this parameter
	 *
	 */
	public void initialize(DataValueDescriptor value, int jdbcTypeId, String className)
	{
	//Gemstone changes end
		this.value = value;
		this.jdbcTypeId = jdbcTypeId;
		this.declaredClassName = className;
	}
	

	/**
	 * Clear the parameter, unless it is a return
	 * output parameter
	 */
	// GemStone changes BEGIN
	public void clear()
	{
		isSet = false;
		final DataValueDescriptor value = this.value;
		if (value != null) {
		  value.setToNull();
		}
	// GemStone changes END
	}


	/**
	 * Get the parameter value.  Doesn't check to
	 * see if it has been initialized or not.
	 *
	 * @return the parameter value, may return null
	 */
	//Gemstone changes begin
	public DataValueDescriptor getValue()
	{
	//Gemstone changes end
		return value;
	}


	//////////////////////////////////////////////////////////////////
	//
	// CALLABLE STATEMENT
	//
	//////////////////////////////////////////////////////////////////

	/**
	 * Mark the parameter as an output parameter.
	 *
	 * @param sqlType	A type from java.sql.Types
	 * @param scale		scale, -1 if no scale arg
	 *
	 * @exception StandardException on error
	 */
	void setOutParameter(int sqlType, int scale)
		throws StandardException
	{
		// fast case duplicate registrations.
		if (registerOutType == sqlType) {
			if (scale == registerOutScale)
				return;
		}

		switch (parameterMode) {
		case JDBC30Translation.PARAMETER_MODE_IN:
		case JDBC30Translation.PARAMETER_MODE_UNKNOWN:
		default:
			throw StandardException.newException(SQLState.LANG_NOT_OUT_PARAM, getJDBCParameterNumberStr());

		case JDBC30Translation.PARAMETER_MODE_IN_OUT:
		case JDBC30Translation.PARAMETER_MODE_OUT:
			// Declared/Java procedure parameter.
			if (!DataTypeDescriptor.isJDBCTypeEquivalent(jdbcTypeId, sqlType))
				throw throwInvalidOutParamMap(sqlType);
			break;

		}

		registerOutType = sqlType;
		
	}

	private StandardException throwInvalidOutParamMap(int sqlType) {

		//TypeId typeId = TypeId.getBuiltInTypeId(sqlType);
		// String sqlTypeName = typeId == null ? "OTHER" : typeId.getSQLTypeName();


		String jdbcTypesName = com.pivotal.gemfirexd.internal.impl.jdbc.Util.typeName(sqlType);

		TypeId typeId = TypeId.getBuiltInTypeId(jdbcTypeId);
		String thisTypeName = typeId == null ? declaredClassName : typeId.getSQLTypeName();
				
		StandardException e = StandardException.newException(SQLState.LANG_INVALID_OUT_PARAM_MAP,
					getJDBCParameterNumberStr(),
					jdbcTypesName, thisTypeName);

		return e;
	}



	/**
	 * Validate the parameters.  This is done for situations where
	 * we cannot validate everything in the setXXX() calls.  In
	 * particular, before we do an execute() on a CallableStatement,
	 * we need to go through the parameters and make sure that
	 * all parameters are set up properly.  The motivator for this
	 * is that setXXX() can be called either before or after
	 * registerOutputParamter(), we cannot be sure we have the types
	 * correct until we get to execute().
	 *
	 * @exception StandardException if the parameters aren't valid
	 */
	void validate() throws StandardException
	{
		switch (parameterMode) {
		case JDBC30Translation.PARAMETER_MODE_UNKNOWN:
			break;
		case JDBC30Translation.PARAMETER_MODE_IN:
			break;
		case JDBC30Translation.PARAMETER_MODE_IN_OUT:
		case JDBC30Translation.PARAMETER_MODE_OUT:
			if (registerOutType == Types.NULL) {
				throw StandardException.newException(SQLState.NEED_TO_REGISTER_PARAM,
					getJDBCParameterNumberStr(),
					 com.pivotal.gemfirexd.internal.catalog.types.RoutineAliasInfo.parameterMode(parameterMode));
			}
			break;
		}
	}

	/**
	 * Return the scale of the parameter.
	 *
	 * @return scale
	 */
//Gemstone changes Begin	
	public int getScale()
//Gemstone changes End	
	{
		//when the user doesn't pass any scale, the registerOutScale gets set to -1
		return (registerOutScale == -1 ? 0 : registerOutScale);
	}

//Gemstone changes Begin
	public int getPrecision()
//Gemstone changes End	
	{
		return registerOutPrecision;
		
	}
// GemStone changes BEGIN
	public boolean isSet() {
	 return this.isSet; 
	}
	
	public short getParameterMode() {
	  return this.parameterMode;
	}
	
	public void setParameterMode(short mode) {
	  this.parameterMode = mode;
	}
	
	public int getRegisterOutputType() {
          return this.registerOutType;
        }
	public int getRegisterOutScale() {
          return this.registerOutScale;
        }
	public boolean getIsSet() {
          return this.isSet;
        }
	
// GemStone changes END

	////////////////////////////////////////////////////
	//
	// CLASS IMPLEMENTATION
	//
	////////////////////////////////////////////////////

	/**
	 * get string for param number
	 */
	String getJDBCParameterNumberStr()
	{
		return Integer.toString(pvs.getParameterNumber(this));
	}

	public String toString()
	{
		/* This method is used for debugging.
		 * It is called when gemfirexd.language.logStatementText=true,
		 * so there is no check of SanityManager.DEBUG.
		 * Anyway, we need to call value.getString() instead of
		 * value.toString() because the user may have done a
		 * a setStream() on the parameter.  (toString() could get
		 * an assertion failure in that case as it would be in an
		 * unexpected state since this is a very weird codepath.)
		 * getString() can throw an exception which we eat and
		 * and reflect in the returned string.
		 */
		if (value == null)
		{
			return "null";
		}
		else
		{
			try
			{
				return value.getTraceString();
			}
			catch (StandardException se)
			{
				return "unexpected exception from getTraceString() - " + se;
			}
		}
	}
	
        //GemStone changes BEGIN
        /**
         * Set the constant DataValueDescriptor
         *
         */
        public void initialize(DataValueDescriptor value)
        {
          this.value = value;
        }
        
        public int getJDBCTypeId() {
          return this.jdbcTypeId;
        }
        //GemStone changes END
}
