/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.jdbc.BrokeredConnection

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

package com.pivotal.gemfirexd.internal.iapi.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLClientInfoException;
import java.sql.NClob;
import java.sql.SQLXML;
import java.sql.Struct;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
//import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection40;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.CallableStatement;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;


import java.io.ObjectOutput;
import java.io.ObjectInput;

import java.lang.reflect.*;

import com.gemstone.gemfire.cache.TransactionFlag;
import com.gemstone.gemfire.internal.cache.Checkpoint;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.SQLWarningFactory;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

/**
 * This is a rudimentary connection that delegates
 * EVERYTHING to Connection.
 */
public abstract class BrokeredConnection implements EngineConnection
{
	
	// default for Derby
	int stateHoldability = ResultSet.HOLD_CURSORS_OVER_COMMIT;

	final BrokeredConnectionControl control;
	private boolean isClosed;
        private String connString;

	/**
		Maintain state as seen by this Connection handle, not the state
		of the underlying Connection it is attached to.
	*/
	private int stateIsolationLevel;
	private boolean stateReadOnly;
	private boolean stateAutoCommit;

	/////////////////////////////////////////////////////////////////////////
	//
	//	CONSTRUCTORS
	//
	/////////////////////////////////////////////////////////////////////////

	public	BrokeredConnection(BrokeredConnectionControl control)
	{
		this.control = control;
	}

	public final void setAutoCommit(boolean autoCommit) throws SQLException 
	{
		try {
			control.checkAutoCommit(autoCommit);

			getRealConnection().setAutoCommit(autoCommit);

			stateAutoCommit = autoCommit;
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}
	public final boolean getAutoCommit() throws SQLException 
	{
		try {
			return getRealConnection().getAutoCommit();
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}
	public final Statement createStatement() throws SQLException 
	{
		try {
			return control.wrapStatement(getRealConnection().createStatement());
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final PreparedStatement prepareStatement(String sql)
	    throws SQLException 
	{
		try {
			return control.wrapStatement(getRealConnection().prepareStatement(sql), sql, null);
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final CallableStatement prepareCall(String sql) throws SQLException 
	{
		try {
			return control.wrapStatement(getRealConnection().prepareCall(sql), sql);
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final String nativeSQL(String sql) throws SQLException
	{
		try {
			return getRealConnection().nativeSQL(sql);
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final void commit() throws SQLException 
	{
		try {
			control.checkCommit();
			getRealConnection().commit();
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final void rollback() throws SQLException 
	{
		try {
			control.checkRollback();
			getRealConnection().rollback();
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final void close() throws SQLException 
	{ 
		if (isClosed)
			return;

		try {
            control.checkClose();

			if (!control.closingConnection()) {
				isClosed = true;
				return;
			}

			isClosed = true;


			getRealConnection().close();
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final boolean isClosed() throws SQLException 
	{
		if (isClosed)
			return true;
		try {
			boolean realIsClosed = getRealConnection().isClosed();
			if (realIsClosed) {
				control.closingConnection();
				isClosed = true;
			}
			return realIsClosed;
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final SQLWarning getWarnings() throws SQLException 
	{
		try {
			return getRealConnection().getWarnings();
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final void clearWarnings() throws SQLException 
	{
		try {
			getRealConnection().clearWarnings();
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final DatabaseMetaData getMetaData() throws SQLException 
	{
		try {
			return getRealConnection().getMetaData();
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final void setReadOnly(boolean readOnly) throws SQLException 
	{
		try {
			getRealConnection().setReadOnly(readOnly);
			stateReadOnly = readOnly;
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final boolean isReadOnly() throws SQLException 
	{
		try {
			return getRealConnection().isReadOnly();
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final void setCatalog(String catalog) throws SQLException 
	{
		try {
			getRealConnection().setCatalog(catalog);
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final String getCatalog() throws SQLException 
	{
		try {
			return getRealConnection().getCatalog();
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final void setTransactionIsolation(int level) throws SQLException 
	{
		try {
			getRealConnection().setTransactionIsolation(level);
			stateIsolationLevel = level;
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

	public final int getTransactionIsolation() throws SQLException
	{
		try {
			return getRealConnection().getTransactionIsolation();
		} catch (SQLException sqle) {
			notifyException(sqle);
			throw sqle;
		}
	}

    public final Statement createStatement(int resultSetType, int resultSetConcurrency) 
      throws SQLException
	{
		try
		{
			return control.wrapStatement(getRealConnection().
				createStatement(resultSetType, resultSetConcurrency));
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}


	public final PreparedStatement prepareStatement(String sql, int resultSetType, 
					int resultSetConcurrency)
       throws SQLException
	{
		try
		{
			return control.wrapStatement(getRealConnection().
				prepareStatement(sql, resultSetType, resultSetConcurrency), sql, null);
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

    public final CallableStatement prepareCall(String sql, int resultSetType, 
				 int resultSetConcurrency) throws SQLException
	{
		try
		{
			return control.wrapStatement(getRealConnection().
				prepareCall(sql, resultSetType, resultSetConcurrency), sql);
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

    public java.util.Map getTypeMap() throws SQLException
	{
		try
		{
			return getRealConnection().getTypeMap();
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

    public final void setTypeMap(java.util.Map map) throws SQLException
	{
		try
		{
			getRealConnection().setTypeMap(map);
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

	/////////////////////////////////////////////////////////////////////////
	//
	//	MINIONS
	//
	/////////////////////////////////////////////////////////////////////////

	/**
	  *	A little indirection for getting the real connection. 
	  *
	  *	@return	the current connection
	  */
	final EngineConnection getRealConnection() throws SQLException {
		if (isClosed)
			throw Util.noCurrentConnection();

		return control.getRealConnection();
	}

	final void notifyException(SQLException sqle) {
		if (!isClosed)
			control.notifyException(sqle);
	}

	/**
		Sync up the state of the underlying connection
		with the state of this new handle.
	*/
	public void syncState() throws SQLException {
		EngineConnection conn = getRealConnection();

		stateIsolationLevel = conn.getTransactionIsolation();
		stateReadOnly = conn.isReadOnly();
		stateAutoCommit = conn.getAutoCommit();
        stateHoldability = conn.getHoldability(); 
	}

	/**
		Isolation level state in BrokeredConnection can get out of sync
		if the isolation is set using SQL rather than JDBC. In order to
		ensure correct state level information, this method is called
		at the start and end of a global transaction.
	*/
	public void getIsolationUptoDate() throws SQLException {
		if (control.isIsolationLevelSetUsingSQLorJDBC()) {
			stateIsolationLevel = getRealConnection().getTransactionIsolation();
			control.resetIsolationLevelFlag();
		}
	}
	/**
		Set the state of the underlying connection according to the
		state of this connection's view of state.

		@param complete If true set the complete state of the underlying
		Connection, otherwise set only the Connection related state (ie.
		the non-transaction specific state).


	*/
	public void setState(boolean complete) throws SQLException {
		Class[] CONN_PARAM = { Integer.TYPE };
// GemStone changes BEGIN
		// changed to use Integer.valueOf()
		Object[] CONN_ARG = { Integer.valueOf(stateHoldability)};
		/* (original code)
		Object[] CONN_ARG = { new Integer(stateHoldability)};
		*/
// GemStone changes END

		Connection conn = getRealConnection();

		if (complete) {
			conn.setTransactionIsolation(stateIsolationLevel);
			conn.setReadOnly(stateReadOnly);
			conn.setAutoCommit(stateAutoCommit);
			// make the underlying connection pick my holdability state
			// since holdability is a state of the connection handle
			// not the underlying transaction.
			// jdk13 does not have Connection.setHoldability method and hence using
			// reflection to cover both jdk13 and higher jdks
			try {
				Method sh = conn.getClass().getMethod("setHoldability", CONN_PARAM);
				sh.invoke(conn, CONN_ARG);
			} catch( Exception e)
			{
				throw PublicAPI.wrapStandardException( StandardException.plainWrapException( e));
			}
		}
	}

	public BrokeredStatement newBrokeredStatement(BrokeredStatementControl statementControl) throws SQLException {
		return new BrokeredStatement(statementControl);
	}
	public abstract BrokeredPreparedStatement
        newBrokeredStatement(BrokeredStatementControl statementControl,
                String sql, Object generatedKeys) throws SQLException;
	public abstract BrokeredCallableStatement
        newBrokeredStatement(BrokeredStatementControl statementControl,
                String sql) throws SQLException;

	/**
	 *  set the DrdaId for this connection. The drdaID prints with the 
	 *  statement text to the errror log
	 *  @param drdaID  drdaID to be used for this connection
	 *
	 */
	public final void setDrdaID(String drdaID)
	{
        try {
		    getRealConnection().setDrdaID(drdaID);
        } catch (SQLException sqle)
        {
            // connection is closed, just ignore drdaId
            // since connection cannot be used.
        }
	}

	/**
	 *  Set the internal isolation level to use for preparing statements.
	 *  Subsequent prepares will use this isoalation level
	 * @param level - internal isolation level 
	 * @throws SQLException
	 * See EmbedConnection#setPrepareIsolation
	 * 
	 */
	public final void setPrepareIsolation(int level) throws SQLException
	{
        getRealConnection().setPrepareIsolation(level);
	}

	/**
	 * get the isolation level that is currently being used to prepare 
	 * statements (used for network server)
	 * 
	 * @throws SQLException
	 * @return current prepare isolation level 
	 * See EmbedConnection#getPrepareIsolation
	 */
	public final int getPrepareIsolation() throws SQLException
	{
		return getRealConnection().getPrepareIsolation();
	}
    
    /**
     * Add a SQLWarning to this Connection object.
     * @throws SQLException 
     */
    public final void addWarning(SQLWarning w) throws SQLException
    {
        getRealConnection().addWarning(w);
    }
            
    /**
     * Checks if the connection is closed and throws an exception if
     * it is.
     *
     * @exception SQLException if the connection is closed
     */
    protected final void checkIfClosed() throws SQLException {
        if (isClosed()) {
            throw Util.noCurrentConnection();
        }
    }

    /**
     * Get the string representation for this connection.  Return
     * the class name/hash code and various debug information.
     * 
     * @return unique string representation for this connection
     */
    public String toString() 
    {
        if ( connString == null )
        {
            String wrappedString;
            try
            {
                wrappedString = getRealConnection().toString();
            }
            catch ( SQLException e )
            {
                wrappedString = "<none>";
            }
            
            connString = this.getClass().getName() + "@" + this.hashCode() +
                ", Wrapped Connection = " + wrappedString;
        }
        
        return connString;
    }

    /*
     * JDBC 3.0 methods that are exposed through EngineConnection.
     */
    
    /**
     * Prepare statement with explicit holdability.
     */
    public final PreparedStatement prepareStatement(String sql,
            int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
    	try {
            resultSetHoldability = statementHoldabilityCheck(resultSetHoldability);
    		
    		return control.wrapStatement(
    			getRealConnection().prepareStatement(sql, resultSetType,
                        resultSetConcurrency, resultSetHoldability), sql, null);
    	}
    	catch (SQLException se)
    	{
    		notifyException(se);
    		throw se;
    	}
    }

    /**
     * Get the holdability for statements created by this connection
     * when holdability is not passed in.
     */
    public final int getHoldability() throws SQLException {
    	try {
    		return getRealConnection().getHoldability();
    	}
    	catch (SQLException se)
    	{
    		notifyException(se);
    		throw se;
    	}
    }
    
    /*
    ** Methods private to the class.
    */
    
    /**
     * Check the result set holdability when creating a statement
     * object. Section 16.1.3.1 of JDBC 4.0 (proposed final draft)
     * says the driver may change the holdabilty and add a SQLWarning
     * to the Connection object.
     * 
     * This work-in-progress implementation throws an exception
     * to match the old behaviour just as part of incremental development.
     */
    final int statementHoldabilityCheck(int resultSetHoldability)
        throws SQLException
    {
        int holdability = control.checkHoldCursors(resultSetHoldability, true);
        if (holdability != resultSetHoldability) {
            SQLWarning w =
                 SQLWarningFactory.newSQLWarning(SQLState.HOLDABLE_RESULT_SET_NOT_AVAILABLE);
            
            addWarning(w);
        }
        
        return holdability;
        
    }
    
	/**
	* Clear the HashMap of all entries.
	* Called when a commit or rollback of the transaction
	* happens.
	*/
	public void clearLOBMapping() throws SQLException {
            //Forward the methods implementation to the implementation in the
            //underlying EmbedConnection object. 
            getRealConnection().clearLOBMapping();
	}

	/**
	* Get the LOB reference corresponding to the locator.
	* @param key the integer that represents the LOB locator value.
	* @return the LOB Object corresponding to this locator.
	*/
	public Object getLOBMapping(long key) throws SQLException {
            //Forward the methods implementation to the implementation in the
            //underlying EmbedConnection object. 
            return getRealConnection().getLOBMapping(key);
	}

    /**
     * Obtain the name of the current schema. Not part of the
     * java.sql.Connection interface, but is accessible through the
     * EngineConnection interface, so that the NetworkServer can get at the
     * current schema for piggy-backing
     * @return the current schema name
     * @throws java.sql.SQLException
     */
    public String getCurrentSchemaName() throws SQLException {
        try {
            return getRealConnection().getCurrentSchemaName();
        }
        catch (SQLException se) {
            notifyException(se);
            throw se;
        }
    }

    /**
     * @see com.pivotal.gemfirexd.internal.iapi.jdbc.EngineConnection
     */
    public void resetFromPool()
            throws SQLException {
        getRealConnection().resetFromPool();
    }

// GemStone changes BEGIN

    @Override
    public PreparedStatement prepareStatement(
        String sql, int resultSetType,
        int resultSetConcurrency, int resultSetHoldability,
        int autoGeneratedKeys) throws SQLException {
      try {
        return getRealConnection().prepareStatement(sql, resultSetType,
            resultSetConcurrency, resultSetHoldability, autoGeneratedKeys);
      } catch (SQLException sqle) {
        notifyException(sqle);
        throw sqle;
      }
    }

    @Override
    public PreparedStatement prepareStatement(
        String sql, int resultSetType,
        int resultSetConcurrency, int resultSetHoldability,
        int[] columnIndexes) throws SQLException {
      try {
        return getRealConnection().prepareStatement(sql, resultSetType,
            resultSetConcurrency, resultSetHoldability, columnIndexes);
      } catch (SQLException sqle) {
        notifyException(sqle);
        throw sqle;
      }
    }

    @Override
    public PreparedStatement prepareStatement(
        String sql, int resultSetType,
        int resultSetConcurrency, int resultSetHoldability,
        String[] columnNames) throws SQLException {
      try {
        return getRealConnection().prepareStatement(sql, resultSetType,
            resultSetConcurrency, resultSetHoldability, columnNames);
      } catch (SQLException sqle) {
        notifyException(sqle);
        throw sqle;
      }
    }

    @Override
    public final void setPossibleDuplicate(boolean dup) {
      try {
        getRealConnection().setPossibleDuplicate(dup);
      } catch (SQLException e) {
        // ignored
      }
    }

    @Override
    public final void setEnableStreaming(boolean enable) {
      try {
        getRealConnection().setEnableStreaming(enable);
      } catch (SQLException e) {
        // ignored
      }
    }

    @Override
    public final LanguageConnectionContext getLanguageConnectionContext() {
      try {
        return getRealConnection().getLanguageConnectionContext();
      } catch (SQLException e) {
        // ignored
        return null;
      }
    }

    @Override
    public final void internalRollback() throws SQLException {
      try {
        getRealConnection().internalRollback();
      } catch (SQLException sqle) {
        notifyException(sqle);
        throw sqle;
      }
    }

    @Override
    public final void internalClose() throws SQLException {
      try {
        getRealConnection().internalClose();
      } catch (SQLException sqle) {
        notifyException(sqle);
        throw sqle;
      }
    }

    @Override
    public final void forceClose() {
      try {
        getRealConnection().forceClose();
      } catch (SQLException sqle) {
        // ignored
      }
    }

    @Override
    public FinalizeObject getAndClearFinalizer() {
      try {
        return getRealConnection().getAndClearFinalizer();
      } catch (SQLException sqle) {
        // ignored
        return null;
      }
    }

    @Override
    public final boolean isActive() {
      try {
        return getRealConnection().isActive();
      } catch (SQLException sqle) {
        return false;
      }
    }

    public int addLOBMapping(Object lobReference) throws SQLException {
      return getRealConnection().addLOBMapping(lobReference);
    }

    public void removeLOBMapping(long key) throws SQLException {
      getRealConnection().removeLOBMapping(key);
    }

    public boolean hasLOBs() throws SQLException {
      return getRealConnection().hasLOBs();
    }

    public Object getConnectionSynchronization() throws SQLException {
      return getRealConnection().getConnectionSynchronization();
    }

    // JDBC 4.0 methods so will compile with JDK 1.6
    public Array createArrayOf(String typeName, Object[] elements)
          throws SQLException {    
      throw new AssertionError("unexpected call in JDBC 4.0");
    }
    
    public Blob createBlob() throws SQLException {
      throw new AssertionError("unexpected call in JDBC 4.0");
    }

    public Clob createClob() throws SQLException{
      throw new AssertionError("unexpected call in JDBC 4.0");
    }
    
    public NClob createNClob() throws SQLException{
      throw new AssertionError("unexpected call in JDBC 4.0");
    }
    
    public SQLXML createSQLXML() throws SQLException{
      throw new AssertionError("unexpected call in JDBC 4.0");
    }
    
    public Struct createStruct(String typeName, Object[] attributes)
          throws SQLException {
      throw new AssertionError("unexpected call in JDBC 4.0");
    }


    public boolean isValid(int timeout) throws SQLException{
      throw new AssertionError("unexpected call in JDBC 4.0");
    }
    
    
    public void setClientInfo(String name, String value)
    throws SQLClientInfoException{        
      throw new AssertionError("unexpected call in JDBC 4.0");
    }

    public void setClientInfo(Properties properties)
    throws SQLClientInfoException{
      throw new AssertionError("unexpected call in JDBC 4.0");
    }
    
    public String getClientInfo(String name)
    throws SQLException{
      throw new AssertionError("unexpected call in JDBC 4.0");
    }
    
    public Properties getClientInfo()
    throws SQLException{
      throw new AssertionError("unexpected call in JDBC 4.0");
    }
    
    public boolean isWrapperFor(Class<?> interfaces) throws SQLException {
      throw new AssertionError("unexpected call in JDBC 4.0");
    }
    
    public <T> T unwrap(java.lang.Class<T> interfaces) 
                            throws SQLException{
      throw new AssertionError("unexpected call in JDBC 4.0");
    }

  // jdbc 4.1 from jdk 1.7
  public void setSchema(String schema) throws SQLException {
    getRealConnection().setSchema(schema);
  }

  public String getSchema() throws SQLException {
    return getRealConnection().getSchema();
  }

  public void abort(Executor executor) throws SQLException {
    throw new AssertionError("unexpected call in JDBC 4.1");
  }

  public void setNetworkTimeout(Executor executor, int milliseconds)
      throws SQLException {
    throw new AssertionError("unexpected call in JDBC 4.1");
  }

  public int getNetworkTimeout() throws SQLException {
    throw new AssertionError("unexpected call in JDBC 4.1");
  }
  
  public void setExecutionSequence(int execSeq) {
    try {
      getRealConnection().setExecutionSequence(execSeq);
    } catch (SQLException e) {
      // Ignore this exception as if there is no real exception
      // then there is no use of setting execution sequence.
    }  
  }

  public EnumSet<TransactionFlag> getTransactionFlags() throws SQLException {
    return getRealConnection().getTransactionFlags();
  }

  public void setTransactionIsolation(int level,
      EnumSet<TransactionFlag> transactionFlags) throws SQLException {
    getRealConnection().setTransactionIsolation(level, transactionFlags);
  }

  public Checkpoint masqueradeAsTxn(TXId txid, int isolationLevel)
      throws SQLException {
    return getRealConnection().masqueradeAsTxn(txid, isolationLevel);
  }

  public void updateAffectedRegion(Bucket b) {
    try {
      getRealConnection().updateAffectedRegion(b);
    } catch (SQLException e) {
      // Ignore this exception as if there is no real exception
      // then there is no use of setting execution sequence.
    }
  }
  // GemStone changes END
}
