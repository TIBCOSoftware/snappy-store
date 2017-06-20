/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement

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

import java.util.ArrayList;
import java.util.Calendar;
/*
 We would import these, but have name-overlap
import java.sql.PreparedStatement;
import java.sql.ResultSet;
*/
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Date;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Clob;
import java.sql.Blob;
import java.sql.RowId;
import java.sql.NClob;
import java.sql.SQLXML;
import java.sql.ParameterMetaData;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Types;

import com.gemstone.gemfire.GemFireException;
import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.execute.PrepStatementSnappyActivation;
import com.pivotal.gemfirexd.internal.engine.sql.execute.SnappyActivation;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.BrokeredConnectionControl;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineParameterMetaData;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EnginePreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RawToBinaryFormatStream;
import com.pivotal.gemfirexd.internal.iapi.types.ReaderToUTF8Stream;
import com.pivotal.gemfirexd.internal.iapi.types.VariableSizeDataValue;
import com.pivotal.gemfirexd.internal.impl.sql.GenericActivationHolder;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericStatement;
import com.pivotal.gemfirexd.internal.shared.common.SingleHopInformation;
import io.snappydata.thrift.common.BufferedBlob;

/**
 *
 * EmbedPreparedStatement is a local JDBC statement.
  <P><B>Supports</B>
   <UL>
   <LI> JSR169
   </UL>
 */
public abstract class EmbedPreparedStatement
	extends EmbedStatement
	implements EnginePreparedStatement
{

	//Moving jdbc2.0 batch related code in this class because callableStatement in jdbc 20 needs
	//this code too and it doesn't derive from prepared statement in jdbc 20 in our implementation.

	protected ResultSetMetaData rMetaData;
	//bug 4579-If the prepared statement was revalidated after doing getMetaData(), we
	//should get the metadata info again on next getMetaData(). We store the generated
	//class name in following variable during getMetaData() call. If it differs from the
	//current generated class name, then that indicates a refetch of metadata is required.
	private String			gcDuringGetMetaData;

	protected PreparedStatement	preparedStatement;
//	private Activation			activation;
        
        private BrokeredConnectionControl bcc=null;

	/**
		Constructor assumes caller will setup context stack
		and restore it.
	 */
// GemStone changes BEGIN

        @Override
        protected FinalizePreparedStatement newFinalizer(
            final EmbedStatement ps) {
          return new FinalizePreparedStatement(ps);
        }

        public EmbedPreparedStatement(EmbedConnection conn, String sql,
            boolean forMetaData, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability, int autoGeneratedKeys, int[] columnIndexes,
            String[] columnNames, long id, short execFlags, THashMap ncjMetaData,
            long rootID, int stmtLevel) throws SQLException {
                super(conn, forMetaData, resultSetType, resultSetConcurrency,
                    resultSetHoldability, id, rootID, stmtLevel);
		// PreparedStatement is poolable by default
		isPoolable = true;

		// if the sql string is null, raise an error
		if (sql == null)
  			throw newSQLException(SQLState.NULL_SQL_TEXT);

			// set up the SQLText in EmbedStatement
			SQLText = sql;
                       
                        if(resultSetConcurrency == java.sql.ResultSet.CONCUR_READ_ONLY) {
                          execFlags = GemFireXDUtils.set(execFlags, GenericStatement.IS_READ_ONLY);
                        }
                        execFlags = GemFireXDUtils.set(execFlags, GenericStatement.IS_PREP_STMT);
                        if (lcc.getQueryHDFS()) {
                          execFlags = (short) GemFireXDUtils.set(execFlags,
                              GenericStatement.QUERY_HDFS, true);
                        }
			try {
			    preparedStatement = lcc.prepareInternalStatement
				(lcc.getDefaultSchema(), sql,                        
                        forMetaData, execFlags, null, ncjMetaData);
			    
			    addWarning(preparedStatement.getCompileTimeWarnings());

			    activation = preparedStatement.getActivation(lcc,
                        resultSetType == java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE, null);
                        activation.setIsPrepStmntQuery(
                            true /* The Activation object refers to a PreparedStatement*/);
                        activation.setStatementID(this.statementID);
                        activation.setRootID(this.rootID);
                        activation.setStatementLevel(this.statementLevel);
                  	checkRequiresCallableStatement(activation);

                  	// GemFireXD: set the StatementStats object
                        this.stats = lcc.statsEnabled() ? this.preparedStatement
                            .getStatementStats() : null;
                        ((FinalizePreparedStatement)this.finalizer).activation =
                          this.activation;
			//bug 4838 - save the auto-generated key information in activation. keeping this
			//information in lcc will not work work as it can be tampered by a nested trasaction
  				if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS)
  					activation.setAutoGeneratedKeysResultsetInfo(columnIndexes, columnNames);

			} catch (Throwable t) {
		    throw handleException(t);
			}
	}

	public Activation getActivation() {
	  return this.activation;
	}

	// now using FinalizePreparedStatement instead of finalize()
	/* (original code)
	/**
		JDBC states that a Statement is closed when garbage collected.

		@exception Throwable Allows any exception to be thrown during finalize
	*
	protected void finalize() throws Throwable {
		super.finalize();

		/*
		** We mark the activation as not being used and
	 	** that is it.  We rely on the connection to sweep
		** through the activations to find the ones that
		** aren't in use, and to close them.  We cannot
	 	** do a activation.close() here because there are
		** synchronized methods under close that cannot
		** be called during finalization.
		*
		if (activation != null) 
		{
			activation.markUnused();
		}
	}
	*/
// GemStone changes END

	/*
	 * Statement interface
		we override all Statement methods that take a SQL
		string as they must thrown an exception in a PreparedStatement.
		See the JDBC 3.0 spec.
	 */
	public final boolean execute(String sql) throws SQLException {
		throw newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, "execute(String)");
	}
	public final boolean execute(String sql, int autoGenKeys) throws SQLException {
		throw newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, "execute(String, int)");
	}
	public final boolean execute(String sql, int[] columnIndexes) throws SQLException {
		throw newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, "execute(String, int[])");
	}
	public final boolean execute(String sql, String[] columnNames) throws SQLException {
		throw newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, "execute(String, String[])");
	}
	public final java.sql.ResultSet executeQuery(String sql) throws SQLException {
		throw newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, "executeQuery(String)");
	}
	public final int executeUpdate(String sql) throws SQLException {
		throw newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, "executeUpdate(String)");
	}
	public final int executeUpdate(String sql, int autoGenKeys) throws SQLException {
		throw newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, "executeUpdate(String, int)");
	}
	public final int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
		throw newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, "executeUpdate(String, int[])");
	}
	public final int executeUpdate(String sql, String[] columnNames) throws SQLException {
		throw newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, "executeUpdate(String, String[])");
	}
	public final void addBatch(String sql) throws SQLException {
		throw newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, "addBatch(String)");
	}


	/**
	 * Additional close to close our activation.
	 * In the case that a XAConnection is involved in the creation of this
	 * PreparedStatement for e.g in the following case
	 *
	 *      <code>
	 *      XAConnection xaconn = xadatasource.getXAConnection();//where xadatasource is an object of XADataSource
	 *      Connection conn = xaconnection.getConnection();
	 *      PreparedStatement ps = conn.preparedStatement("values 1");
	 *      </code>
	 *
	 * In the above case the PreparedStatement will actually be a 
	 * BrokeredPreparedStatement40 object. Hence when we call 
	 * bcc.onStatementClose and pass the PreparedStatement that caused it
	 * applicationStatement will be the appropriate choice since it will 
	 * contain the appropriate instance of PreparedStatement in each case
	 *
	 * @throws SQLException upon failure
	 *
	 */
	void closeActions() throws SQLException {

		if (bcc!=null) {
			java.sql.PreparedStatement ps_app = 
				(java.sql.PreparedStatement)applicationStatement;
			bcc.onStatementClose(ps_app);
		}
		//we release the resource for preparedStatement
		preparedStatement = null;

		try{
			setupContextStack(false);
		} catch (SQLException se) {
			//we may have already committed the transaction in which case
			//setupContextStack will fail, the close should just return
			return;
		}
		try
		{
// GemStone changes BEGIN
		  //The distributed close needs to be send after close actions otherwise offheap memory wont be freed
      //as offheap memory is released via closeActions
    // send in sync block is okay since we do not wait for results
    
		  if (this.activation != null) {
		    this.activation.close();
		    executeDistributedClose();
		    this.activation = null;
		  }
// GemStone changes END
			
		} catch (Throwable t)
		{
			throw handleException(t);
		} finally {
		    restoreContextStack();
		}
	}
	
	/*
	 * PreparedStatement interface; we have inherited from
	 * EmbedStatement to get the Statement interface for
	 * EmbedPreparedStatement (needed by PreparedStatement)
	 * These are the JDBC interface comments, so we know
	 * what to do.
	 */

	/**
     * A prepared SQL query is executed and its ResultSet is returned.
     *
     * @return a ResultSet that contains the data produced by the
     * query; never null
	 * @exception SQLException thrown on failure.
     */
	public final java.sql.ResultSet executeQuery() throws SQLException {
            try {
                executeStatement(activation, true, false,
                    false /* GemStone addition */);
            } catch(SQLException sqle) {
                checkStatementValidity(sqle);
            }
            
            if (SanityManager.DEBUG) {
                if (results == null)
                    SanityManager.THROWASSERT("no results returned on executeQuery()");
            }
            
            return results;
        }
// GemStone changes BEGIN
	public final java.sql.ResultSet executeQueryByPassQueryInfo() throws SQLException {
	  try {
	    executeStatement(activation, true, false, true);
	  } catch(SQLException sqle) {
	    checkStatementValidity(sqle);
	  }

	  if (SanityManager.DEBUG) {
	    if (results == null) {
	      SanityManager.THROWASSERT(
	          "no results returned on executeQueryByPassQueryInfo()");
	    }
	  }
	  return results;
	}

        @Override
        public int getStatementType() {
          return ((GenericPreparedStatement)this.preparedStatement).getStatementType();
        }

        public DataTypeDescriptor[] getParameterTypes() {
          return this.preparedStatement.getParameterTypes();
        }
// GemStone changes END

    /**
     * Execute a SQL INSERT, UPDATE or DELETE statement. In addition,
     * SQL statements that return nothing such as SQL DDL statements
     * can be executed.
     *
     * @return either the row count for INSERT, UPDATE or DELETE; or 0
     * for SQL statements that return nothing
	 * @exception SQLException thrown on failure.
     */
        public final int executeUpdate() throws SQLException {
            try {
                executeStatement(activation, false, true,
                    false /* GemStone addition */);
            } catch(SQLException sqle) {
                checkStatementValidity(sqle);
            }
            return updateCount;
        }

    /**
     * Set a parameter to SQL NULL.
     *
     * <P><B>Note:</B> You must specify the parameter's SQL type.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param sqlType SQL type code defined by java.sql.Types
	 * @exception SQLException thrown on failure.
     */
    public void setNull(int parameterIndex, int sqlType) throws SQLException {

		checkForSupportedDataType(sqlType);
		checkStatus();

		int jdbcTypeId = getParameterJDBCType(parameterIndex);
		
// GemStone changes BEGIN
		// Types.NULL is set by Spring for unknown types
		// (http://communities.vmware.com/message/2007380)
		if (sqlType == java.sql.Types.NULL) {
		  sqlType = jdbcTypeId;
		}
		else
// GemStone changes END
		if (!DataTypeDescriptor.isJDBCTypeEquivalent(jdbcTypeId, sqlType)) {

			throw dataTypeConversion(parameterIndex, Util.typeName(sqlType));
		}
		
		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setToNull();
		} catch (StandardException t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
     * Set a parameter to a Java boolean value.  According to the JDBC API spec,
	 * the driver converts this to a SQL BIT value when it sends it to the
	 * database. But we don't have to do this, since the database engine
	 * supports a boolean type.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
	 * @exception SQLException thrown on failure.
     */
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
		
		checkStatus();
		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x);

		} catch (StandardException t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
     * Set a parameter to a Java byte value.  The driver converts this
     * to a SQL TINYINT value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
	 * @exception SQLException thrown on failure.
     */
    public void setByte(int parameterIndex, byte x) throws SQLException {

		checkStatus();
		try {

			getParms().getParameterForSet(parameterIndex - 1).setValue(x);

		} catch (Throwable t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
     * Set a parameter to a Java short value.  The driver converts this
     * to a SQL SMALLINT value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
	 * @exception SQLException thrown on failure.
     */
    public void setShort(int parameterIndex, short x) throws SQLException {

		checkStatus();
		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x);

		} catch (Throwable t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
     * Set a parameter to a Java int value.  The driver converts this
     * to a SQL INTEGER value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
	 * @exception SQLException thrown on failure.
     */
    public void setInt(int parameterIndex, int x) throws SQLException {
		checkStatus();

		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x);
		} catch (Throwable t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
     * Set a parameter to a Java long value.  The driver converts this
     * to a SQL BIGINT value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
	 * @exception SQLException thrown on failure.
     */
    public void setLong(int parameterIndex, long x) throws SQLException {
		checkStatus();
		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x);

		} catch (Throwable t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
     * Set a parameter to a Java float value.  The driver converts this
     * to a SQL FLOAT value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
	 * @exception SQLException thrown on failure.
     */
    public void setFloat(int parameterIndex, float x) throws SQLException {
		checkStatus();
		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x);

		} catch (Throwable t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
     * Set a parameter to a Java double value.  The driver converts this
     * to a SQL DOUBLE value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
	 * @exception SQLException thrown on failure.
     */
    public void setDouble(int parameterIndex, double x) throws SQLException {
		checkStatus();

		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x);

		} catch (Throwable t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
     * Set a parameter to a Java String value.  The driver converts this
     * to a SQL VARCHAR or LONGVARCHAR value (depending on the arguments
     * size relative to the driver's limits on VARCHARs) when it sends
     * it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
	 * @exception SQLException thrown on failure.
     */
    public void setString(int parameterIndex, String x) throws SQLException {
		checkStatus();		
		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x);

		} catch (Throwable t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
     * Set a parameter to a Java array of bytes.  The driver converts
     * this to a SQL VARBINARY or LONGVARBINARY (depending on the
     * argument's size relative to the driver's limits on VARBINARYs)
     * when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value 
	 * @exception SQLException thrown on failure.
     */
    public void setBytes(int parameterIndex, byte x[]) throws SQLException {
		checkStatus();

		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x);

		} catch (Throwable t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}

	}

    /**
     * Set a parameter to a java.sql.Date value.  The driver converts this
     * to a SQL DATE value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
	 * @exception SQLException thrown on failure.
     */
    public void setDate(int parameterIndex, Date x) throws SQLException {
// GemStone changes BEGIN
      setDate(parameterIndex, x, getCal());
      /* (original code)
        setDate( parameterIndex, x, (Calendar) null);
      */
// GemStone changes END
	}

    /**
     * Set a parameter to a java.sql.Time value.  The driver converts this
     * to a SQL TIME value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
	 * @exception SQLException thrown on failure.
     */
    public void setTime(int parameterIndex, Time x) throws SQLException {
// GemStone changes BEGIN
      setTime(parameterIndex, x, getCal());
      /* (original code)
        setTime( parameterIndex, x, (Calendar) null);
      */
// GemStone changes END
	}

    /**
     * Set a parameter to a java.sql.Timestamp value.  The driver
     * converts this to a SQL TIMESTAMP value when it sends it to the
     * database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value 
	 * @exception SQLException thrown on failure.
     */
    public void setTimestamp(int parameterIndex, Timestamp x)
	    throws SQLException {
// GemStone changes BEGIN
      setTimestamp(parameterIndex, x, getCal());
      /* (original code)
        setTimestamp( parameterIndex, x, (Calendar) null);
      */
// GemStone changes END
	}

    /**
	 * We do this inefficiently and read it all in here. The target type
	 * is assumed to be a String.
     * 
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the ASCII parameter value
     * @param length the number of bytes in the stream 
	 * @exception SQLException thrown on failure.
     */
    public final void setAsciiStream(int parameterIndex, InputStream x, long length)
	    throws SQLException {
        checkAsciiStreamConditions(parameterIndex);
		java.io.Reader r = null;

		if (x != null)
		{
			// Use ISO-8859-1 and not US-ASCII as JDBC seems to define
			// ASCII as 8 bits. US-ASCII is 7.
			try {
				r = new java.io.InputStreamReader(x, "ISO-8859-1");
			} catch (java.io.UnsupportedEncodingException uee) {
				throw new SQLException(uee.getMessage());
			}
		}

        setCharacterStreamInternal(parameterIndex, r, false, length);
	}

    /**
     * We do this inefficiently and read it all in here. The target type
     * is assumed to be a String.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the ASCII parameter value
     * @param length the number of bytes in the stream
     * @exception SQLException thrown on failure.
     */

    public final void setAsciiStream(int parameterIndex, InputStream x, int length)
    throws SQLException {
        setAsciiStream(parameterIndex,x,(long)length);
    }


    /**
		Deprecated in JDBC 3.0
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the
     * UNICODE parameter value
     * @param length the number of bytes in the stream
	 * @exception SQLException thrown on failure.
     * @deprecated
     */
    public void setUnicodeStream(int parameterIndex, InputStream x, int length)
	    throws SQLException
	{
		throw Util.notImplemented("setUnicodeStream");
	}

    /**
     * When a very large UNICODE value is input to a LONGVARCHAR
     * parameter, it may be more practical to send it via a
     * java.io.Reader. JDBC will read the data from the stream
     * as needed, until it reaches end-of-file.  The JDBC driver will
     * do any necessary conversion from UNICODE to the database char format.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param reader the java reader which contains the UNICODE data
     * @param length the number of characters in the stream
     * @exception SQLException if a database-access error occurs.
     */
    public final void setCharacterStream(int parameterIndex,
       			  java.io.Reader reader,
			  long length) throws SQLException {
        checkCharacterStreamConditions(parameterIndex);
        setCharacterStreamInternal(parameterIndex, reader, false, length);
	}

    /**
     * When a very large UNICODE value is input to a LONGVARCHAR
     * parameter, it may be more practical to send it via a
     * java.io.Reader. JDBC will read the data from the stream
     * as needed, until it reaches end-of-file.  The JDBC driver will
     * do any necessary conversion from UNICODE to the database char format.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param reader the java reader which contains the UNICODE data
     * @param length the number of characters in the stream
     * @exception SQLException if a database-access error occurs.
     */
    public final void setCharacterStream(int parameterIndex,
        java.io.Reader reader,
        int length) throws SQLException {
        setCharacterStream(parameterIndex,reader,(long)length);
    }

    /**
     * Check general preconditions for setCharacterStream methods.
     *
     * @param parameterIndex 1-based index of the parameter.
     */
    private final void checkCharacterStreamConditions(int parameterIndex)
            throws SQLException {
        checkStatus();
        int jdbcTypeId = getParameterJDBCType(parameterIndex);
        if (!DataTypeDescriptor.isCharacterStreamAssignable(jdbcTypeId)) {
          throw dataTypeConversion(parameterIndex, "java.io.Reader");
        }
    }

    /**
     * Check general preconditions for setAsciiStream methods.
     *
     * @param parameterIndex 1-based index of the parameter.
     */
    private final void checkAsciiStreamConditions(int parameterIndex)
            throws SQLException {
        checkStatus();
        int jdbcTypeId = getParameterJDBCType(parameterIndex);
        if (!DataTypeDescriptor.isAsciiStreamAssignable(jdbcTypeId)) {
          throw dataTypeConversion(parameterIndex,
              "java.io.InputStream(ASCII)");
        }
    }

    /**
     * Set the given character stream for the specified parameter.
     *
     * If <code>lengthLess</code> is <code>true</code>, the following
     * conditions are either not checked or verified at the execution time
     * of the prepared statement:
     * <ol><li>If the stream length is negative.
     *     <li>If the stream's actual length equals the specified length.</ol>
     * The <code>lengthLess</code> variable was added to differentiate between
     * streams with invalid lengths and streams without known lengths.
     *
     * @param parameterIndex the 1-based index of the parameter to set.
     * @param reader the data.
     * @param lengthLess tells whether we know the length of the data or not.
     * @param length the length of the data. Ignored if <code>lengthLess</code>
     *          is <code>true</code>.
     */
    private void setCharacterStreamInternal(int parameterIndex,
                                            Reader reader,
                                            final boolean lengthLess,
                                            long length)
	    throws SQLException
	{
        // Check for negative length if length is specified.
        if (!lengthLess && length < 0)
            throw newSQLException(SQLState.NEGATIVE_STREAM_LENGTH);

	    int jdbcTypeId = getParameterJDBCType(parameterIndex);


        if (reader == null)  {
             setNull(parameterIndex, jdbcTypeId);
             return;
        }

        /*
           The value stored should not exceed the maximum value that can be 
           stored in an integer 
           This checking needs to be done because currently derby does not
           support Clob sizes greater than 2G-1 
        */
        if (!lengthLess && length > Integer.MAX_VALUE)
               throw newSQLException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE,
                                     getParameterSQLType(parameterIndex), parameterIndex);

        try {
            ReaderToUTF8Stream utfIn;
            ParameterValueSet pvs = getParms();
            // Need column width to figure out if truncation is needed
            DataTypeDescriptor dtd[] = preparedStatement
                    .getParameterTypes();
            int colWidth = dtd[parameterIndex - 1].getMaximumWidth();
            // Default to max column width. This will be used to limit the
            // amount of data read when operating on "lengthless" streams.
            int usableLength = colWidth;

            if (!lengthLess) {
                // We cast the length from long to int. This wouldn't be
                // appropriate if the limit of 2G-1 is decided to be increased
                // at a later stage.
                int intLength = (int)length;
                int truncationLength = 0;

                usableLength = intLength;

                // Currently long varchar does not allow for truncation of
                // trailing blanks.
                // For char and varchar types, current mechanism of
                // materializing when using streams seems fine given their max
                // limits.
                // This change is fix for DERBY-352: Insert of clobs using
                // streams should not materialize the entire stream into memory
                // In case of clobs, the truncation of trailing blanks is
                // factored in when reading from the stream without
                // materializing the entire stream, and so the special casing
                // for clob below.
                if (jdbcTypeId == Types.CLOB)
                {

                    // It is possible that the length of the stream passed in
                    // is greater than the column width, in which case the data
                    // from the stream needs to be truncated.
                    // usableLength is the length of the data from stream that
                    // can be inserted which is min(colWidth,length) provided
                    // length - colWidth has trailing blanks only
                    // we have used intLength into which the length variable had
                    // been cast to an int and stored
                    if (intLength > colWidth) {
                        usableLength = colWidth;
                        truncationLength = intLength - usableLength;
                    }
                }
                // Create a stream with truncation.
                utfIn = new ReaderToUTF8Stream(reader, usableLength,
                        truncationLength, getParameterSQLType(parameterIndex));
            } else {
                // Create a stream without exactness checks,
                // but with a maximum limit.
                utfIn = new ReaderToUTF8Stream(reader, colWidth,
                                getParameterSQLType(parameterIndex));
            }

            // JDBC is one-based, DBMS is zero-based.
            // Note that for lengthless stream, usableLength will be
            // the maximum length for the column. 
            // This is okay, based on the observation that
            // setValue does not use the value for anything at all.
            pvs.getParameterForSet(
                parameterIndex - 1).setValue(utfIn, usableLength);

		} catch (StandardException t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
     * Sets the designated parameter to the given input stream.
     * When a very large binary value is input to a <code>LONGVARBINARY</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.InputStream</code> object. The data will be read from the
     * stream as needed until end-of-file is reached.
     *
     * <em>Note:</em> This stream object can either be a standard Java stream
     * object or your own subclass that implements the standard interface.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @throws SQLException if a database access error occurs or this method is
     *      called on a closed <code>PreparedStatement</code>
     */
    public void setBinaryStream(int parameterIndex, InputStream x)
            throws SQLException {
        checkBinaryStreamConditions(parameterIndex);
        setBinaryStreamInternal(parameterIndex, x, true, -1);
    }

    /**
     * sets the parameter to the Binary stream
     * 
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @param length the number of bytes in the stream 
	 * @exception SQLException thrown on failure.
     */
    public final void setBinaryStream(int parameterIndex, InputStream x, long length)
	    throws SQLException {
        checkBinaryStreamConditions(parameterIndex);
        setBinaryStreamInternal(parameterIndex, x, false, length);
	}

    /**
     * sets the parameter to the binary stream
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @param length the number of bytes in the stream
     * @exception SQLException thrown on failure.
     */
    public final void setBinaryStream(int parameterIndex, InputStream x, int length)
    throws SQLException {
        setBinaryStream(parameterIndex,x,(long)length);
    }

    /**
     * Set the given stream for the specified parameter.
     *
     * If <code>lengthLess</code> is <code>true</code>, the following
     * conditions are either not checked or verified at the execution time
     * of the prepared statement:
     * <ol><li>If the stream length is negative.
     *     <li>If the stream's actual length equals the specified length.</ol>
     * The <code>lengthLess</code> variable was added to differentiate between
     * streams with invalid lengths and streams without known lengths.
     *
     * @param parameterIndex the 1-based index of the parameter to set.
     * @param x the data.
     * @param lengthLess tells whether we know the length of the data or not.
     * @param length the length of the data. Ignored if <code>lengthLess</code>
     *          is <code>true</code>.
     */
    private void setBinaryStreamInternal(int parameterIndex, InputStream x,
                                         final boolean lengthLess, long length)
	    throws SQLException
	{

        if ( !lengthLess && length < 0 )
            throw newSQLException(SQLState.NEGATIVE_STREAM_LENGTH);
        
		int jdbcTypeId = getParameterJDBCType(parameterIndex);
		if (x == null) {
			setNull(parameterIndex, jdbcTypeId);
           	return;
		}
        
        // max number of bytes that can be set to be inserted 
        // in Derby is 2Gb-1 (ie Integer.MAX_VALUE). 
        // (e.g into a blob column).
        // For now, we cast the length from long to int as a result.
        // If we ever decide to increase these limits for lets say blobs, 
        // in that case the cast to int would not be appropriate.
        if ( !lengthLess && length > Integer.MAX_VALUE ) {
            throw newSQLException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE,
               getEmbedParameterSetMetaData().getParameterTypeName(
                   parameterIndex), parameterIndex);
        }

        try {
            RawToBinaryFormatStream rawStream;
            if (lengthLess) {
                // Force length to -1 for good measure.
                length = -1;
                DataTypeDescriptor dtd[] = 
                    preparedStatement.getParameterTypes();
                rawStream = new RawToBinaryFormatStream(x,
                        dtd[parameterIndex -1].getMaximumWidth(),
                        dtd[parameterIndex -1].getTypeName());
            } else {
                rawStream = new RawToBinaryFormatStream(x, (int)length);
            }
            getParms().getParameterForSet(parameterIndex - 1).setValue(
                    rawStream, (int)length);

		} catch (StandardException t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
     * Check general preconditions for setBinaryStream methods.
     *
     * @param parameterIndex 1-based index of the parameter.
     */
    private final void checkBinaryStreamConditions(int parameterIndex)
            throws SQLException {
        checkStatus();
        int jdbcTypeId = getParameterJDBCType(parameterIndex);
        if (!DataTypeDescriptor.isBinaryStreamAssignable(jdbcTypeId)) {
            throw dataTypeConversion(parameterIndex, "java.io.InputStream");
        }
    }

	/////////////////////////////////////////////////////////////////////////
	//
	//	JDBC 2.0	-	New public methods
	//
	/////////////////////////////////////////////////////////////////////////

    /**
     *
     * JDBC 2.0
     *
     * Sets the designated parameter to SQL <code>NULL</code>.
     * This version of the method <code>setNull</code> should
     * be used for user-defined types and REF type parameters.  Examples
     * of user-defined types include: STRUCT, DISTINCT, JAVA_OBJECT, and
     * named array types.
     *
     * @param paramIndex the first parameter is 1, the second is 2, ...
     * @param sqlType a value from <code>java.sql.Types</code>
     * @param typeName the fully-qualified name of an SQL user-defined type;
     *  ignored if the parameter is not a user-defined type or REF
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @exception java.sql.SQLFeatureNotSupportedException if <code>sqlType</code> is
     * a <code>ARRAY</code>, <code>BLOB</code>, <code>CLOB</code>,
     * <code>DATALINK</code>, <code>JAVA_OBJECT</code>, <code>NCHAR</code>,
     * <code>NCLOB</code>, <code>NVARCHAR</code>, <code>LONGNVARCHAR</code>,
     *  <code>REF</code>, <code>ROWID</code>, <code>SQLXML</code>
     * or  <code>STRUCT</code> data type and the JDBC driver does not support
     * this data type or if the JDBC driver does not support this method
     *
     */
    public void setNull(int paramIndex,
        int sqlType,
        String typeName)
        throws SQLException {
        setNull(paramIndex,sqlType);
    }

    /**
     * JDBC 2.0
     *
     * Add a set of parameters to the batch.
     * 
     * @exception SQLException if a database-access error occurs.
     */
    public void addBatch() throws SQLException {
	  checkStatus();

	  // need to synchronized to ensure that two threads
	  // don't both create a Vector at the same time. This
	  // would lead to one of the set of parameters being thrown
	  // away
  	  synchronized (getConnectionSynchronization()) {
            if (batchStatements == null)
              // GemStone changes BEGIN
            {
              batchStatements = new ArrayList<Object>();
            }
  			/* (original code)
  				batchStatements = new Vector();
  			*/
          //get a clone of the parameterValueSet and save it in the vector
          //which will be used later on at the time of batch execution.
          //This way we will get a copy of the current statement's parameter
          //values rather than a pointer to the statement's parameter value
          //set which will change with every new statement in the batch.
  	  try {
            //batchStatements.add(getParms().getClone());
            if (executeBatchInProgress == 0) {
              batchStatements.add(getParms().getClone());
            } else {
              // copy into the already existing params
              ParameterValueSet temp = (ParameterValueSet)getParms();
              int numberOfParameters = temp.getParameterCount();

              if (batchStatementCurrentIndex < batchStatements.size()) {
                for (int j = 0; j < numberOfParameters; j++) {
                  ((ParameterValueSet)batchStatements.get(batchStatementCurrentIndex)).getParameter(j)
                    .setValue(temp.getParameter(j));
                }
              } else {
                batchStatements.add(getParms().getClone());
              }
            }
            batchStatementCurrentIndex++;
          } catch (Throwable t) {
  	    throw TransactionResourceImpl.wrapInSQLException(t);
  	  }
          /* (original code)
          batchStatements.addElement(getParms().getClone());
          */
// GemStone changes END
          clearParameters();
  	  }
    }

	boolean executeBatchElement(Object batchElement) throws SQLException, StandardException {
		
		ParameterValueSet temp = (ParameterValueSet) batchElement;

		int numberOfParameters = temp.getParameterCount();

		for (int j=0; j<numberOfParameters; j++) {
			temp.getParameter(j).setInto(this, j + 1);
		}
// GemStone changes BEGIN
		// Asif: Verify the correctness of not creating query info
		// sb: Now queryInfo flag is passed according to the compilation decision instead of hard coded.
                return super
                    .executeStatement(
                        activation,
                        false,
                        true,
                        ((GenericPreparedStatement)this.preparedStatement)
                            .createQueryInfo() /* create query info*/,
                        false /*context not set */,
                        false /* skip context stack restore */,
                        true /* is prepared batch */);
	}

	protected void postBatchExecution() throws StandardException,
	    SQLException {
	 final ResultSet rs = this.activation.getResultSet();
	  if (rs != null) {
	    // flush the batch
            final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
            if (observer != null) {
              observer.beforeFlushBatch(rs, lcc);
            }
            try {
              rs.flushBatch();
            }
            finally {
              rs.closeBatch();
	      resetBatch(); // with this, if .clearBatch is skipped, we will resuse the objects already created.
            }
            if (observer != null) {
              observer.afterFlushBatch(rs, lcc);
            }
	    if (rs.hasAutoGeneratedKeysResultSet()) {
	      final ResultSet autoRS = rs.getAutoGeneratedKeysResultset();
	      autoRS.open();
	      this.autoGeneratedKeysResultSet = this.factory.newEmbedResultSet(
	          getEmbedConnection(), autoRS, false, this,
	          this.activation.getPreparedStatement().isAtomic());
	    }
	  }
	}
// GemStone changes END

    /**
     * <P>In general, parameter values remain in force for repeated use of a
     * Statement. Setting a parameter value automatically clears its
     * previous value.  However, in some cases it is useful to immediately
     * release the resources used by the current parameter values; this can
     * be done by calling clearParameters.
	 * @exception SQLException thrown on failure.
     */
    public void clearParameters() throws SQLException {
// GemStone changes BEGIN
		ParameterValueSet pvs = getParms();
		if (pvs != null)
			pvs.clearParameters();
		ParameterValueSet pvs2 = this.pvs;
		if (pvs != pvs2 && pvs2 != null) {
		  pvs2.clearParameters();
		}

		checkStatus();
// GemStone changes END
	}

    /**
	 * JDBC 2.0
	 *
     * The number, types and properties of a ResultSet's columns
     * are provided by the getMetaData method.
     *
     * @return the description of a ResultSet's columns
     * @exception SQLException Feature not implemented for now.
     */
    public java.sql.ResultSetMetaData getMetaData() throws SQLException
	{
		checkExecStatus();
		synchronized (getConnectionSynchronization())
		{
			//reason for casting is getActivationClass is not available on PreparedStatement
			ExecPreparedStatement execp = (ExecPreparedStatement)preparedStatement;
// Gemstone changes BEGIN
			synchronized(preparedStatement) {
                            try {
                              if (!preparedStatement.upToDate()) {
                                while (((GenericPreparedStatement)preparedStatement)
                                    .compilingStatement()) {
                                  preparedStatement.wait();
                                }
                              }
                            } catch (StandardException e) {
                              if (SanityManager.TraceSingleHop) {
                                SanityManager.DEBUG_PRINT(
                                    SanityManager.TRACE_SINGLE_HOP,
                                    "EmbedPreparedStatement::getMetaData got standard exception: " + e, e);
                              }
                              throw Util.generateCsSQLException(e);
                            } catch (InterruptedException e) {
                              if (SanityManager.TraceSingleHop) {
                                SanityManager.DEBUG_PRINT(
                                    SanityManager.TRACE_SINGLE_HOP,
                                    "EmbedPreparedStatement::getMetaData got interrupted exception: " + e, e);
                              }
                              try {
                              Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(e);
                              }
                              catch(GemFireException ge) {
                                throw Util.generateCsSQLException(Misc.processGemFireException(ge, ge, "getMetadata", true));
                              }
                              throw Util.javaException(e);
                            }
// Gemstone changes END
			setupContextStack(true); // make sure there's context

			try {
				//bug 4579 - if the statement is invalid, regenerate the metadata info
				if (preparedStatement.isValid() == false)
				{
					//need to revalidate the statement here, otherwise getResultDescription would
					//still have info from previous valid statement
					preparedStatement.rePrepare(lcc, this.getActivation());
					rMetaData = null;
				}
                                // Gemstone changes BEGIN
				if (SanityManager.TraceSingleHop) {
                                  SanityManager.DEBUG_PRINT(
                                      SanityManager.TRACE_SINGLE_HOP,
                                      "EmbedPreparedStatement::getMetaData execp is: " + execp + " and source is: " + execp.getStatement().getQueryStringForParse(lcc));
                                }
				
                                if (SanityManager.TraceSingleHop) {
                                  SanityManager.DEBUG_PRINT(
                                      SanityManager.TRACE_SINGLE_HOP,
                                      "EmbedPreparedStatement::getMetaData_ONE  gcDuringGetMetaData: "
                                          + gcDuringGetMetaData
                                          + ", excep: "
                                          + execp
                                          + " ac: "
                                          + ((execp.getActivationClass() == null) ? null : execp
                                              .getActivationClass()) + " acname: " + execp.getActivationClass().getName() + " source is: " + execp.getStatement().getSource());
                                }
                                // Gemstone changes END
				//bug 4579 - gcDuringGetMetaData will be null if this is the first time
				//getMetaData call is made.
				//Second check - if the statement was revalidated since last getMetaData call,
				//then gcDuringGetMetaData wouldn't match with current generated class name
				if (gcDuringGetMetaData == null || gcDuringGetMetaData.equals(execp.getActivationClass().getName()) == false)
				{
					rMetaData = null;
					// Gemstone changes BEGIN
					if (SanityManager.TraceSingleHop) {
	                                  SanityManager.DEBUG_PRINT(
	                                      SanityManager.TRACE_SINGLE_HOP,
	                                      "EmbedPreparedStatement::getMetaData_TWO  gcDuringGetMetaData: "
	                                          + gcDuringGetMetaData
	                                          + ", excep: "
	                                          + execp
	                                          + " ac: "
	                                          + ((execp.getActivationClass() == null) ? null : execp
	                                              .getActivationClass()) + " acname: " + execp.getActivationClass().getName() + " statement is: " + execp.getStatement());
	                                }
					// Gemstone changes END
					gcDuringGetMetaData = execp.getActivationClass().getName();
				}
				Activation a = null;
				if (this.getActivation() != null) {
					if (this.getActivation() instanceof GenericActivationHolder) {
						a = ((GenericActivationHolder)this.getActivation()).getActivation();
					} else if (this.getActivation() instanceof Activation) {
						a = this.getActivation();
					}
				}

				if (rMetaData == null && !(a instanceof SnappyActivation || a instanceof PrepStatementSnappyActivation))
				{
					Activation act = null;
					if (this.getActivation() != null) {
						if (this.getActivation() instanceof GenericActivationHolder) {
							act = ((GenericActivationHolder)this.getActivation()).getActivation();
						} else if (this.getActivation() instanceof Activation) {
							act = this.getActivation();
						}
					}
					if (act instanceof PrepStatementSnappyActivation || act instanceof  SnappyActivation) {
						rMetaData = null;
					} else {
						ResultDescription resd = preparedStatement.getResultDescription();
						if (resd != null) {
							// Internally, the result description has information
							// which is used for insert, update and delete statements
							// Externally, we decided that statements which don't
							// produce result sets such as insert, update and delete
							// should not return ResultSetMetaData.  This is enforced
							// here
							String statementType = resd.getStatementType();
							if (statementType.equals("INSERT") ||
									statementType.equals("UPDATE") ||
									statementType.equals("DELETE"))
								rMetaData = null;
							else
								rMetaData = newEmbedResultSetMetaData(resd);
						}
					}
				}
			} catch (Throwable t) {
				throw handleException(t);
			}	finally {
				restoreContextStack();
			}
// Gemstone changes BEGIN
		} // end of synchronization on prepared statement
// Gemstone changes END
		}
		return rMetaData;
	}

    //----------------------------------------------------------------------
    // Advanced features:

    /**
	 * The interface says that the type of the Object parameter must
	 * be compatible with the type of the targetSqlType. We check that,
	 * and if it flies, we expect the underlying engine to do the
	 * required conversion once we pass in the value using its type.
	 * So, an Integer converting to a CHAR is done via setInteger()
	 * support on the underlying CHAR type.
     *
     * <p>If x is null, it won't tell us its type, so we pass it on to setNull
     *
     * @param parameterIndex The first parameter is 1, the second is 2, ...
     * @param x The object containing the input parameter value
     * @param targetSqlType The SQL type (as defined in java.sql.Types) to be 
     * sent to the database. The scale argument may further qualify this type.
     * @param scale For java.sql.Types.DECIMAL or java.sql.Types.NUMERIC types
     *          this is the number of digits after the decimal.  For all other
     *          types this value will be ignored,
	 * @exception SQLException thrown on failure.
     */
    public final void setObject(int parameterIndex, Object x, int targetSqlType, int scale)
            throws SQLException {

		if (x == null) {
			setNull(parameterIndex, targetSqlType);
			return;
		}

		checkForSupportedDataType(targetSqlType);

		int paramJDBCType = getParameterJDBCType(parameterIndex);

		if (paramJDBCType != java.sql.Types.JAVA_OBJECT) {
			if (!DataTypeDescriptor.isJDBCTypeEquivalent(paramJDBCType, targetSqlType)) {
				throw dataTypeConversion(parameterIndex, Util.typeName(targetSqlType));
			}
		}

		setObject(parameterIndex, x);
				
		/*
		* If the parameter type is DECIMAL or NUMERIC, then
		* we need to set the correct scale  or set it 
		* to the default which is zero for setObject.
		*/
		if ((paramJDBCType == Types.DECIMAL) || 
			 (paramJDBCType == Types.NUMERIC))
		{
			setScale(parameterIndex, scale);
		}	
	}

    /**
      * This method is like setObject above, but assumes a scale of zero.
	 * @exception SQLException thrown on failure.
      */
    public final void setObject(int parameterIndex, Object x, int targetSqlType)
		throws SQLException {
		setObject(parameterIndex, x, targetSqlType, 0);
	}

    /**
     * <p>Set the value of a parameter using an object; use the
     * java.lang equivalent objects for integral values.
     *
     * <p>The JDBC specification specifies a standard mapping from
     * Java Object types to SQL types.  The given argument java object
     * will be converted to the corresponding SQL type before being
     * sent to the database.
     *
     * <p>Note that this method may be used to pass datatabase
     * specific abstract data types, by using a Driver specific Java
     * type.
     *
     * @param parameterIndex The first parameter is 1, the second is 2, ...
     * @param x The object containing the input parameter value 
	 * @exception SQLException thrown on failure.
     */
    public final void setObject(int parameterIndex, Object x) throws SQLException {
		checkStatus();	


		int colType = getParameterJDBCType(parameterIndex);

		// JDBC Tutorial and Reference books states in the PreparedStatement
		// overview, that passing a untyped null into setObject() is not allowed.
		// JCC disallows this, basically SQL can not handle a untyped NULL.
		// Section 25.1.6 (Third edition), 24.1.5 (Second Edition)

		if (x == null) {
// GemStone changes BEGIN
		  // now allowing this
			setNull(parameterIndex, colType);
			return;
			/* (original code)
			throw dataTypeConversion(parameterIndex, "null");
			*/
// GemStone changes END
		}
		
		if (colType == Types.JAVA_OBJECT) {
			try {
				/* JDBC is one-based, DBMS is zero-based */
				getParms().setParameterAsObject(parameterIndex - 1, x);
				return;

			} catch (Throwable t) {
				throw EmbedResultSet.noStateChangeException(t,
				    "parameter index " + parameterIndex /* GemStoneAddition */);
			}
		}


		// Need to do instanceof checks here so that the behaviour
		// for these calls is consistent with the matching setXXX() value.

		// These are the supported setObject conversions from JDBC 3.0 table B5
		// Byte and Short were added to the table in JDBC 4.0.

		// TODO: PERF: change instanceofs below to use getClass()
		if (x instanceof String) {
			setString(parameterIndex, (String) x);
			return;
		}

		if (x instanceof Boolean) {
			setBoolean(parameterIndex, ((Boolean) x).booleanValue());
			return;
		}
		if (x instanceof Byte) {
			setByte(parameterIndex, ((Byte) x).byteValue());
			return;
		}
		if (x instanceof Short) {
			setShort(parameterIndex, ((Short) x).shortValue());
			return;
		}
		if (x instanceof Integer) {
			setInt(parameterIndex, ((Integer) x).intValue());
			return;
		}
		if (x instanceof Long) {
			setLong(parameterIndex, ((Long) x).longValue());
			return;
		}

		if (x instanceof Float) {
			setFloat(parameterIndex, ((Float) x).floatValue());
			return;
		}
		if (x instanceof Double) {
			setDouble(parameterIndex, ((Double) x).doubleValue());
			return;
		}

		if (x instanceof byte[]) {
			setBytes(parameterIndex, (byte[]) x);
			return;
		}

		if (x instanceof Date) {
			setDate(parameterIndex, (Date) x);
			return;
		}
		if (x instanceof Time) {
			setTime(parameterIndex, (Time) x);
			return;
		}
		if (x instanceof Timestamp) {
			setTimestamp(parameterIndex, (Timestamp) x);
			return;
		}

		if (x instanceof Blob) {
			setBlob(parameterIndex, (Blob) x);
			return;
		}
		if (x instanceof Clob) {
			setClob(parameterIndex, (Clob) x);
			return;
		}

		if (setObjectConvert(parameterIndex, x))
			return;

		
		throw dataTypeConversion(parameterIndex, x.getClass().getName());

	}

	/**
		Allow explict setObject conversions by sub-classes for classes
		not supported by this variant. E.g. BigDecimal
		This top-level implementation always returns false.

		@return true if the object was set successfully, false if no valid
		conversion exists.

		@exception SQLException value could not be set.
	*/
	boolean setObjectConvert(int parameterIndex, Object x) throws SQLException
	{
		return false;
	}

    /**
     * @see java.sql.Statement#execute
	 * @exception SQLException thrown on failure.
     */
        public final boolean execute() throws SQLException {
            boolean ret=false;
            try{
                ret = executeStatement(activation, false, false,
                    false /* GemStone addition */);
            } catch(SQLException sqle) {
                checkStatementValidity(sqle);
            }
            return ret;
        }
    /**
     * Set a parameter to a java.sql.Date value.  The driver converts this
     * to a SQL DATE value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException Feature not implemented for now.
     */
    public final void setDate(int parameterIndex, java.sql.Date x, Calendar cal)
	    throws SQLException 
	{
		checkStatus();
		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x, cal);

		} catch (Throwable t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
     * Set a parameter to a java.sql.Time value.  The driver converts this
     * to a SQL TIME value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException Feature not implemented for now.
     */
    public final void setTime(int parameterIndex, java.sql.Time x, Calendar cal)
	    throws SQLException 
	{
		checkStatus();
		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x, cal);

		} catch (Throwable t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}

    /**
     * Set a parameter to a java.sql.Timestamp value.  The driver
     * converts this to a SQL TIMESTAMP value when it sends it to the
     * database.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value 
     * @exception SQLException Feature not implemented for now.
     */
    public final void setTimestamp(int parameterIndex, java.sql.Timestamp x, Calendar cal)
	    throws SQLException 
	{
		checkStatus();
		try {
			/* JDBC is one-based, DBMS is zero-based */
			getParms().getParameterForSet(parameterIndex - 1).setValue(x, cal);

		} catch (StandardException t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}


    /**
     * JDBC 2.0
     *
     * Set a BLOB parameter.
     *
     * @param i the first parameter is 1, the second is 2, ...
     * @param x an object representing a BLOB
     */
    public void setBlob (int i, Blob x)
        throws SQLException
    {
        checkBlobConditions(i);
		if (x == null)
			setNull(i, Types.BLOB);
		else
        {
// GemStone changes BEGIN
            if (x instanceof BufferedBlob) {
              try {
                getParms().getParameterForSet(i - 1).setValue(x);
                return;
              } catch (StandardException t) {
                throw EmbedResultSet.noStateChangeException(t,
                    "parameter index " + i);
              }
            }
// GemStone changes END
            // Note, x.length() needs to be called before retrieving the
            // stream using x.getBinaryStream() because EmbedBlob.length()
            // will read from the stream and drain some part of the stream 
            // Hence the need to declare this local variable - streamLength
            long streamLength = x.length();
            setBinaryStreamInternal(i, x.getBinaryStream(), false,
                    streamLength);
        }
	}

    /**
     * Check general (pre)conditions for setClob methods.
     *
     * @param parameterIndex 1-based index of the parameter.
     */
    private final void checkClobConditions(int parameterIndex)
        throws SQLException {
      checkStatus();

      int colType = getParameterJDBCType(parameterIndex);
      switch (colType) {
      case Types.CLOB:
      case JDBC40Translation.JSON:
        break;
      default:
        throw dataTypeConversion(parameterIndex, "java.sql.Clob");
      }
    }

    /**
     * JDBC 2.0
     *
     * Set a CLOB parameter.
     *
     * @param i the first parameter is 1, the second is 2, ...
     * @param x an object representing a CLOB
     */
    public void setClob (int i, Clob x)
        throws SQLException
    {
        checkClobConditions(i);
		if (x == null)
			setNull(i, Types.CLOB);
		else
        {
            // 1. max number of characters that can be inserted into a clob column
            // is 2Gb-1 which is Integer.MAX_INT.
            // This means that we do not allow any inserts of clobs where
            // clob.length() > Integer.MAX_INT. For now, we cast the x.length()
            // to int as a result. This will work ok for valid clob values that
            // derby supports. If we ever decide to increase these limits for clobs, in that
            // case the cast of x.Length() to int would not be appropriate.
            // 2. Note, x.length() needs to be called before retrieving the
            // stream using x.getCharacterStream() because EmbedClob.length()
            // will read from the stream and drain the stream. 
            // Hence the need to declare this local variable - streamLength
            long streamLength = x.length();

            setCharacterStreamInternal(i, x.getCharacterStream(),
                                       false, streamLength);
        }
        
	}

	/**
	 * Get the ParameterValueSet from the activation.
	 * 
	 * The caller of this method should be aware that the
	 * activation associated with a Statement can change
	 * and hence the ParameterValueSet returned by this
	 * call should not be hold onto. An example of this
	 * can be seen in EmbedCallableStatement.executeStatement
	 * where at the beginning of the method, we check the
	 * validity of the parameters. But we donot keep the
	 * parameters in a local variable to use later. The reason
	 * for this is that the next call in the method, 
	 * super.executeStatement can recompile the statement and 
	 * create a new activation if the statement plan has been 
	 * invalidated. To account for this possibility, 
	 * EmbedCallableStatement.executeStatement makes 
	 * another call to get the ParameterValueSet before stuffing 
	 * the output parameter value into the ParameterValueSet
	 * object.
	 *
	 *
	 * @return	The ParameterValueSet for the activation
	 *
	 */
	public final ParameterValueSet getParms() {

		return activation.getParameterValueSet();
	}


	/**
		Check the parameterINdex is in range and return the
		array of type descriptors.

		@exception SQLException parameter is out of range
	*/
	protected final DataTypeDescriptor[] getTypes(int parameterIndex)
		throws SQLException {

		DataTypeDescriptor[] types = preparedStatement.getParameterTypes();

		if (types == null) {
			throw newSQLException(SQLState.NO_INPUT_PARAMETERS);
		}

		/* Check that the parameterIndex is in range. */
		if (parameterIndex < 1 ||
				parameterIndex > types.length) {

			/* This message matches the one used by the DBMS */
			throw newSQLException(SQLState.LANG_INVALID_PARAM_POSITION, 
            new Integer(parameterIndex), new Integer(types.length));
		}
		return types;
	}

	/**
		Get the target JDBC type for a parameter. Will throw exceptions
		if the parameter index is out of range

		@exception SQLException parameter is out of range
	*/
	protected int getParameterJDBCType(int parameterIndex)
		throws SQLException {

		DataTypeDescriptor[] types = getTypes(parameterIndex);

		int type = types[parameterIndex -1] == null ? 
			Types.OTHER :
			types[parameterIndex - 1].getTypeId().getJDBCTypeId();

		if (SanityManager.DEBUG) {
			//int pmType = getEmbedParameterSetMetaData().getParameterType(parameterIndex);
			//if (type != pmType) {
				//SanityManager.THROWASSERT("MISMATCH PARAMETER META DATA param " + parameterIndex + " types " + type + " != " + pmType + "\n" + SQLText);
			//}
		}

		return type;
	}

    /**
     * Return the SQL type name for the parameter.
     *
     * @param parameterIndex the 1-based index of the parameter
     * @return SQL name of the parameter
     * @throws SQLException if parameter is out of range
     */
    protected final String getParameterSQLType(int parameterIndex)
            throws SQLException {
        DataTypeDescriptor[] pTypes = getTypes(parameterIndex);
        return pTypes[parameterIndex-1].getTypeName();
    }

    /**
     * Set the scale of a parameter.
     *
     * @param parameterIndex The first parameter is 1, the second is 2, ...
     * @param scale	The scale
	 * @exception SQLException thrown on failure.
     */
    private void setScale(int parameterIndex, int scale)
		throws SQLException 
	{
		checkStatus();

		if (scale < 0)
			throw newSQLException(SQLState.BAD_SCALE_VALUE, new Integer(scale));
		
		try {

			ParameterValueSet pvs = getParms();

			/* JDBC is one-based, DBMS is zero-based */
			DataValueDescriptor value = pvs.getParameter(parameterIndex - 1);


			int origvaluelen = value.getLength();
			((VariableSizeDataValue)
						value).setWidth(VariableSizeDataValue.IGNORE_PRECISION, 
							scale, 
							false);

			if (value.getLength() < origvaluelen)
			{
				activation.addWarning(StandardException.newWarning(SQLState.LANG_VALUE_TRUNCATED, value.getString()));
			}

		} catch (StandardException t) {
			throw EmbedResultSet.noStateChangeException(t,
			    "parameter index " + parameterIndex /* GemStoneAddition */);
		}
	}


	/**
    * Immitate the function in JDBC 3.0
    *
    * Retrieves the number, types and properties of this PreparedStatement
    * object's parameters.
    *
    * @return a EngineParameterMetaData object that contains information about the
    * number, types and properties of this PreparedStatement object's parameters.
    * @exception SQLException if a database access error occurs
	*/
	private EngineParameterMetaData getEmbedParameterSetMetaData()
    	throws SQLException
	{
	  checkExecStatus();
	  return new EmbedParameterSetMetaData(
				getParms(), preparedStatement.getParameterTypes());

	}
	/**
    * JDBC 3.0
    *
    * Sets the designated parameter to the given java.net.URL value. The driver
    * converts this to an SQL DATALINK value when it sends it to the database.
    *
    * @param parameterIndex - the first parameter is 1, the second is 2, ...
    * @param x - the java.net.URL object to be set
    * @exception SQLException Feature not implemented for now.
	*/
	public final void setURL(int parameterIndex, java.net.URL x)
    throws SQLException
	{
		throw Util.notImplemented();
	}

	//
	// methods to be overridden in subimplementations
	// that want to stay within their subimplementation.
	//
	protected EmbedResultSetMetaData newEmbedResultSetMetaData(ResultDescription resultDesc) {

		return factory.newEmbedResultSetMetaData(resultDesc.getColumnInfo());
	}

	public String toString() {

		if (activation != null)
// GemStone changes BEGIN
		  return this.getClass().getSimpleName() + '@'
		      + Integer.toHexString(System.identityHashCode(this)) + ", "
		      + activation.getPreparedStatement().getSource()
		      + ", UUIDString: " + activation.getPreparedStatement()
		          .getObjectName();
		/* (original code)
			return activation.getPreparedStatement().getSource() + " UUIDString : "
                                 + activation.getPreparedStatement().getObjectName();
                */
// GemStone changes END

		return super.toString();
	}

	public void transferParameters(EmbedPreparedStatement newStatement) throws SQLException {

		try {
			newStatement.activation.setParameters(getParms(), preparedStatement.getParameterTypes());
		} catch (StandardException se) {
			throw EmbedResultSet.noStateChangeException(se,
			    null /* GemStoneAddition */);
		}
	}

	boolean executeStatement(Activation a,
                     boolean executeQuery, boolean executeUpdate,
                     boolean skipContextRestore /* GemStone addition */)
                     throws SQLException {
		// GemStone changes BEGIN
		checkExecStatus();
		checkIfInMiddleOfBatch();
		clearResultSets();
// GemStone changes BEGIN

		return super.executeStatement(a, executeQuery, executeUpdate,
		    ((GenericPreparedStatement)preparedStatement).createQueryInfo(),
		    false /* is Context set */,
		    skipContextRestore, false /* is prepared batch */);
// GemStone changes END
	}

	final SQLException dataTypeConversion(int column, String sourceType)
		throws SQLException {
// GemStone changes BEGIN
	  return Util.generateCsSQLException(SQLState.LANG_DATA_TYPE_GET_MISMATCH,
	      getEmbedParameterSetMetaData().getParameterTypeName(column),
              sourceType, column);
	  /* (original code)
		SQLException se = newSQLException(SQLState.LANG_DATA_TYPE_GET_MISMATCH, getEmbedParameterSetMetaData().getParameterTypeName(column),
			sourceType);
		return se;
	  */
// GemStone changes END
	}
        /**
         * This method is used to initialize the BrokeredConnectionControl 
         * variable with its implementation. This method will be called in the  
         * BrokeredConnectionControl class 
         *
         * @param control used to call the onStatementClose and 
         * onStatementErrorOccurred methods that have logic to 
         * raise StatementEvents for the close and error events
         * on the PreparedStatement
         *
         */
        public void setBrokeredConnectionControl(BrokeredConnectionControl control) {
            bcc = control;
        }
        
// GemStone changes BEGIN
     
  private void executeDistributedClose() {

    // Distribute the prepared statement.close to other nodes
    // close the resultset first
   
    this.activation.distributeClose();
  }
// GemStone changes END
        
        /**
         * Method calls onStatementError occurred on the 
         * BrokeredConnectionControl class after checking the 
         * SQLState of the SQLException thrown.
         *
         * In the case that a XAConnection is involved in the creation of this
         * PreparedStatement for e.g in the following case
         *
         *      <code>
         *      XAConnection xaconn = xadatasource.getXAConnection();//where xadatasource is an object of XADataSource
         *      Connection conn = xaconnection.getConnection();
         *      PreparedStatement ps = conn.preparedStatement("values 1");
         *      </code>
         *
         * In the above case the PreparedStatement will actually be a 
         * BrokeredPreparedStatement40 object. Hence when we call 
         * bcc.onStatementClose and pass the PreparedStatement that caused it
         * applicationStatement will be the appropriate choice since it will 
         * contain the appropriate instance of PreparedStatement in each case
         *
         */
        
        private void checkStatementValidity(SQLException sqle) throws SQLException {
            /*
             * Check if the exception has occurred because the connection
             * associated with the PreparedStatement has been closed
             */
            if(bcc != null && isClosed()) {
                //call the BrokeredConnectionControl interface method
                //onStatementErrorOccurred
                bcc.onStatementErrorOccurred((java.sql.PreparedStatement)
                                                applicationStatement,sqle);
            }
            throw sqle;
        }

    /**
     * Checks whether a data type is supported for
     * <code>setObject(int, Object, int)</code> and
     * <code>setObject(int, Object, int, int)</code>.
     *
     * @param dataType the data type to check
     * @exception SQLException if the type is not supported
     */
    private void checkForSupportedDataType(int dataType) throws SQLException {

        // JDBC 4.0 javadoc for setObject() says:
        //
        // Throws: (...) SQLFeatureNotSupportedException - if
        // targetSqlType is a ARRAY, BLOB, CLOB, DATALINK,
        // JAVA_OBJECT, NCHAR, NCLOB, NVARCHAR, LONGNVARCHAR, REF,
        // ROWID, SQLXML or STRUCT data type and the JDBC driver does
        // not support this data type
        //
        // Of these types, we only support BLOB, CLOB and
        // (sort of) JAVA_OBJECT.

        switch (dataType) {
        case Types.ARRAY:
        case Types.DATALINK:
        case JDBC40Translation.NCHAR:
        case JDBC40Translation.NCLOB:
        case JDBC40Translation.NVARCHAR:
        case JDBC40Translation.LONGNVARCHAR:
        case Types.REF:
        case JDBC40Translation.ROWID:
        case JDBC40Translation.SQLXML:
        case Types.STRUCT:
            throw newSQLException(SQLState.DATA_TYPE_NOT_SUPPORTED,
                                  Util.typeName(dataType));
        }
    }

   //jdbc 4.0 methods

    /**
     * Sets the designated parameter to the given input stream.
     * When a very large ASCII value is input to a <code>LONGVARCHAR</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.InputStream</code>. Data will be read from the stream as
     * needed until end-of-file is reached. The JDBC driver will do any
     * necessary conversion from ASCII to the database char format.
     *
     * <em>Note:</em> This stream object can either be a standard Java stream
     * object or your own subclass that implements the standard interface.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the Java input stream that contains the ASCII parameter value
     * @throws SQLException if a database access error occurs or this method is
     *      called on a closed <code>PreparedStatement</code>
     */
    public void setAsciiStream(int parameterIndex, InputStream x)
            throws SQLException {
        checkAsciiStreamConditions(parameterIndex);
        java.io.Reader asciiStream = null;

        if (x != null) {
            // Use ISO-8859-1 and not US-ASCII as JDBC seems to define
            // ASCII as 8 bits. US-ASCII is 7.
            try {
                asciiStream = new java.io.InputStreamReader(x, "ISO-8859-1");
            } catch (java.io.UnsupportedEncodingException uee) {
                throw new SQLException(uee.getMessage());
            }
        }

        setCharacterStreamInternal(parameterIndex, asciiStream, true, -1);
    }

    /**
     * Sets the designated parameter to the given <code>Reader</code> object.
     * When a very large UNICODE value is input to a LONGVARCHAR parameter, it
     * may be more practical to send it via a <code>java.io.Reader</code>
     * object. The data will be read from the stream as needed until
     * end-of-file is reached. The JDBC driver will do any necessary conversion
     * from UNICODE to the database char format.
     *
     * <em>Note:</em> This stream object can either be a standard Java stream
     * object or your own subclass that implements the standard interface.
     *
     * Using this lengthless overload is not less effective than using one
     * where the stream length is specified, but since there is no length
     * specified, the exact length check will not be performed.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param reader the <code>java.io.Reader</code> object that contains the
     *      Unicode data
     * @throws SQLException if a database access error occurs or this method is
     *      called on a closed <code>PreparedStatement</code>
     */
    public void setCharacterStream(int parameterIndex, Reader reader)
            throws SQLException {
        checkCharacterStreamConditions(parameterIndex);
        setCharacterStreamInternal(parameterIndex, reader,
                                   true, -1);
    }

    /**
     * Sets the designated parameter to a <code>Reader</code> object.
     * This method differs from the <code>setCharacterStream(int,Reader)</code>
     * method because it informs the driver that the parameter value should be
     * sent to the server as a <code>CLOB</code>. When the
     * <code>setCharacterStream</code> method is used, the driver may have to
     * do extra work to determine whether the parameter data should be sent to
     * the server as a <code>LONGVARCHAR</code> or a <code>CLOB</code>.
     *
     * @param parameterIndex index of the first parameter is 1, the second is
     *      2, ...
     * @param reader an object that contains the data to set the parameter
     *      value to.
     * @throws SQLException if a database access error occurs, this method is
     *      called on a closed PreparedStatementor if parameterIndex does not
     *      correspond to a parameter marker in the SQL statement
     */
    public void setClob(int parameterIndex, Reader reader)
            throws SQLException {
        checkClobConditions(parameterIndex);
        setCharacterStreamInternal(parameterIndex, reader, true, -1);
    }

    /**
     * Sets the designated parameter to a Reader object.
     *
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param reader An object that contains the data to set the parameter value to.
     * @param length the number of characters in the parameter data.
     * @throws SQLException if parameterIndex does not correspond to a parameter
     * marker in the SQL statement, or if the length specified is less than zero.
     *
     */

    
    public void setClob(int parameterIndex, Reader reader, long length)
    throws SQLException{
        checkClobConditions(parameterIndex);
        setCharacterStreamInternal(parameterIndex, reader, false, length);
    }

    /**
     * Sets the designated parameter to a <code>InputStream</code> object.
     * This method differs from the <code>setBinaryStream(int, InputStream)
     * </code>  method because it informs the driver that the parameter value
     * should be sent to the server as a <code>BLOB</code>. When the
     * <code>setBinaryStream</code> method is used, the driver may have to do
     * extra work to determine whether the parameter data should be sent to the
     * server as a <code>LONGVARBINARY</code> or a <code>BLOB</code>
     *
     * @param parameterIndex index of the first parameter is 1, the second is
     *      2, ...
     * @param inputStream an object that contains the data to set the parameter
     *      value to.
     * @throws SQLException if a database access error occurs, this method is
     *      called on a closed <code>PreparedStatement</code> or if
     *      <code>parameterIndex</code> does not correspond to a parameter
     *      marker in the SQL statement
     */
    public void setBlob(int parameterIndex, InputStream inputStream)
            throws SQLException {
        checkBlobConditions(parameterIndex);
        setBinaryStreamInternal(parameterIndex, inputStream, true, -1);
    }

    /**
     * Sets the designated parameter to a InputStream object.
     *
     * @param parameterIndex index of the first parameter is 1,
     * the second is 2, ...
     * @param inputStream An object that contains the data to set the parameter
     * value to.
     * @param length the number of bytes in the parameter data.
     * @throws SQLException if parameterIndex does not correspond
     * to a parameter marker in the SQL statement,  if the length specified
     * is less than zero or if the number of bytes in the inputstream does not match
     * the specfied length.
     */

    
    public void setBlob(int parameterIndex, InputStream inputStream, long length)
    throws SQLException{
        checkBlobConditions(parameterIndex);
        setBinaryStreamInternal(parameterIndex, inputStream, false, length);
    }

    /**
     * Check general (pre)conditions for setBlob methods.
     *
     * @param parameterIndex 1-based index of the parameter.
     */
    private final void checkBlobConditions(int parameterIndex)
            throws SQLException {
        checkStatus();
        if (getParameterJDBCType(parameterIndex) != Types.BLOB) {
            throw dataTypeConversion(parameterIndex, "java.sql.Blob");
        }
    }
    
    public final long getVersionCounter() throws SQLException {
        return preparedStatement.getVersionCounter();
    }
    
// GemStone changes BEGIN

  @Override
  public boolean isPrepared() {
    return true;
  }

  // Dummy methods for JDBC 4.0 so can be compiled with JDK 1.6
  public void setRowId(int parameterIndex, RowId x) throws SQLException{
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }
  
  public void setNString(int index, String value) throws SQLException{
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }
  
  public void setNCharacterStream(int parameterIndex, Reader value)
  throws SQLException {
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }
  
  public void setNCharacterStream(int index, Reader value, long length) throws SQLException{
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }
  
  public void setNClob(int parameterIndex, Reader reader)
  throws SQLException {
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }
  
  public void setNClob(int index, NClob value) throws SQLException{
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }    
  
  public void setNClob(int parameterIndex, Reader reader, long length)
  throws SQLException{
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }
  
  public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException{
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }
  
  public ParameterMetaData getParameterMetaData()
  throws SQLException
  {
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }
  
  public boolean isWrapperFor(Class<?> interfaces) throws SQLException {
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }
  
  public <T> T unwrap(java.lang.Class<T> interfaces) 
  throws SQLException{
    throw new AssertionError("should have been overridden in JDBC 4.0");
  }

  // jdbc 4.1 from jdk 1.7
  public void closeOnCompletion() throws SQLException {
    throw new AssertionError("should have been overridden in JDBC 4.1");
  }

  public boolean isCloseOnCompletion() throws SQLException {
    throw new AssertionError("should have been overridden in JDBC 4.1");
  }
  
  public SingleHopInformation fillAndGetSingleHopInformation() {
    if (this.preparedStatement != null && this.preparedStatement instanceof GenericPreparedStatement) {
      return ((GenericPreparedStatement)this.preparedStatement).fillAndGetSingleHopInformation();
    }
    return null;
  }
  
  public GenericPreparedStatement getGPS() {
    if (this.preparedStatement != null && this.preparedStatement instanceof GenericPreparedStatement) {
      return ((GenericPreparedStatement)this.preparedStatement);
    }
    return null;
  }

  @SuppressWarnings("serial")
  protected static final class FinalizePreparedStatement extends
      FinalizeStatement {

    private Activation activation;

    public FinalizePreparedStatement(final EmbedStatement ps) {
      super(ps);
    }

    @Override
    protected void clearThis() {
      this.activation = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected final boolean doFinalize() {
      super.doFinalize();

      /*
       ** We mark the activation as not being used and
       ** that is it.  We rely on the connection to sweep
       ** through the activations to find the ones that
       ** aren't in use, and to close them.  We cannot
       ** do a activation.close() here because there are
       ** synchronized methods under close that cannot
       ** be called during finalization.
       */
      final Activation act = this.activation;
      if (act != null) {
        act.markUnused();
        this.activation = null;
      }
      return true;
    }
  }

// GemStone changes END
}
