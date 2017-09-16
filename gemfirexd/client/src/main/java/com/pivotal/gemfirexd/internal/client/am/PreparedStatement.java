/*

   Derby - Class com.pivotal.gemfirexd.internal.client.am.PreparedStatement

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
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

package com.pivotal.gemfirexd.internal.client.am;


import java.io.InputStream;
import java.io.Reader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.client.ClientPooledConnection;
import com.pivotal.gemfirexd.internal.client.net.FdocaConstants;
import com.pivotal.gemfirexd.internal.client.net.NetResultSet;
import com.pivotal.gemfirexd.internal.shared.common.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.jdbc.ClientDRDADriver;

// GemStone changes BEGIN
// made abstract to enable compilation with both JDK 1.4 and 1.6
abstract
// GemStone changes END
public class PreparedStatement extends Statement
        implements java.sql.PreparedStatement,
        PreparedStatementCallbackInterface {
    //---------------------navigational cheat-links-------------------------------
    // Cheat-links are for convenience only, and are not part of the conceptual model.
    // Warning:
    //   Cheat-links should only be defined for invariant state data.
    //   That is, the state data is set by the constructor and never changes.

    // Alias for downcast (MaterialPreparedStatementProxy) super.materialStatement.
    public MaterialPreparedStatement materialPreparedStatement_ = null;

    //-----------------------------state------------------------------------------

    public String sql_;

    // This variable is only used by Batch.
    // True if a call sql statement has an OUT or INOUT parameter registered.
    public boolean outputRegistered_ = false;

    // Parameter inputs are cached as objects so they may be sent on execute()
    public Object[] parameters_;

    boolean[] parameterSet_;
    boolean[] parameterRegistered_;
    
    void setInput(int parameterIndex, Object input) {
        parameters_[parameterIndex - 1] = input;
        parameterSet_[parameterIndex - 1] = true;
// GemStone changes BEGIN
        // clear the first execute parameter type list so flowExecute() will
        // re-initialize it correctly
        this.clientParamTypeAtFirstExecute = null;
// GemStone changes END
    }

    public ColumnMetaData parameterMetaData_; // type information for input sqlda
    
    private ArrayList parameterTypeList;


    // The problem with storing the scrollable ResultSet associated with cursorName in scrollableRS_ is
    // that when the PreparedStatement is re-executed, it has a new ResultSet, however, we always do
    // the reposition on the ResultSet that was stored in scrollableRS_, and we never update scrollableRS_
    // when PreparedStatement is re-execute.  So the new ResultSet that needs to be repositioned never
    // gets repositioned.
    // So instead of caching the scrollableRS_, we will cache the cursorName.  And re-retrieve the scrollable
    // result set from the map using this cursorName every time the PreparedStatement excutes.
    String positionedUpdateCursorName_ = null;
    
    // the ClientPooledConnection object used to notify of the events that occur
    // on this prepared statement object
    protected final ClientPooledConnection pooledConnection_;

    protected boolean superFlowExecuteCalled_;


    private void initPreparedStatement() {
// GemStone changes BEGIN
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "invoking initPreparedStatement1 for ps: " + this);
        }
// GemStone changes END
        materialPreparedStatement_ = null;
        sql_ = null;
        outputRegistered_ = false;
        parameters_ = null;
        parameterSet_ = null;
        parameterRegistered_ = null;
        parameterMetaData_ = null;
        parameterTypeList = null;
        isAutoCommittableStatement_ = true;
        isPreparedStatement_ = true;
    }

    protected void initResetPreparedStatement() {
// GemStone changes BEGIN
      if (SanityManager.TraceClientHA) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
            "invoking initResetPreparedStatement for ps: " + this);
      }
// GemStone changes END
        outputRegistered_ = false;
        isPreparedStatement_ = true;
        resetParameters();
    }

    public void reset(boolean fullReset) throws SqlException {
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "invoking reset with full reset: " + fullReset
              + " for ps: " + this);
        }
        if (fullReset) {
            connection_.resetPrepareStatement(this);
        } else {
            super.initResetPreparedStatement(fullReset);
// GemStone changes BEGIN
            // don't reset the full prepared statement else its parameters will
            // be lost (#44101); but do prepare the statement again since after
            // the old agent is lost, we cannot use the old prepared information
            if (SanityManager.TraceClientHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "in reset calling prepare again on " + this);
            }
            preparePostFailover();
            /* (original code)
            initResetPreparedStatement();
            */
// GemStone changes END
        }
    }

    /**
     * Resets the prepared statement for reuse in a statement pool.
     *
     * @throws SqlException if the reset fails
     * @see Statement#resetForReuse
     */
    void resetForReuse()
            throws SqlException {
        resetParameters();
        super.resetForReuse();
    }

    private void resetParameters() {
// GemStone changes BEGIN
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "resetting prepared statement parameters: "
                  + SingleHopPreparedStatement.returnParameters(parameters_)
                  + " for ps: " + this/*, new Throwable()*/);
        }
// GemStone changes END
        if (parameterMetaData_ != null) {
            if (SanityManager.TraceClientHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "resetParameters arrays.fill for ps: " + this
                  + " and parameters: " + parameters_ /*, new Throwable()*/);
            }
            if (!this.internalPreparedStatement) {
              Arrays.fill(parameters_, null);
              Arrays.fill(parameterSet_, false);
              Arrays.fill(parameterRegistered_, false);
            }
        }
    }

// Gemstone changes BEGIN
    public NetResultSet getSuperResultSet() throws SQLException {
      throw SQLExceptionFactory.notImplemented("getSuperResultSet");
    }

    protected boolean singleHopInfoAlreadyFetched_;
    
    protected int[] clientParamTypeAtFirstExecute;
    
    protected int[] setAndStoreClientParamType(int[] clientParamtype) {
      int [] ret = null;
      if (this.parameterMetaData_ != null) {
        int len = clientParamtype.length;
        if (SanityManager.TraceSingleHop) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
              "PreparedStatement::setAndStoreClientParamType client param type: " + getClientParamType(clientParamtype)) ;
        }
        ret = new int[len];
        System.arraycopy(clientParamtype, 0, ret, 0, len);
      }
      if (SanityManager.TraceSingleHop) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
            "PreparedStatement::setAndStoreClientParamType this.clientParamTypeBeforePrepare: " + getClientParamType(ret)) ;
      }
      return ret;
    }
   
    protected static int[] makeNewClientParamTypeFrom(int[] sourceClientParamType) {
      int[] ret = null;
      if (sourceClientParamType != null) {
        int len = sourceClientParamType.length; 
        ret = new int[len];
        System.arraycopy(sourceClientParamType, 0, ret, 0, len);
      }
      return ret;
    }
    
    protected String getClientParamType(int [] paramType) {
      StringBuilder sb = new StringBuilder();
      if (paramType == null) {
        sb.append("null");
        return sb.toString();
      }
      sb.append(paramType);
      sb.append(", len=" + paramType.length);
      for(int i=0; i<paramType.length; i++) {
        if (i ==0 ) {
          sb.append("[");
        }
        else {
          sb.append(", ");  
        }
        sb.append(paramType[i]);
        if (i == paramType.length - 1) {
          sb.append("]");
        }
      }
      return sb.toString();
    }
 // Gemstone changes END
    /**
     *
     * The PreparedStatement constructor used for JDBC 2 positioned update
     * statements. Called by material statement constructors.
     * It has the ClientPooledConnection as one of its parameters 
     * this is used to raise the Statement Events when the prepared
     * statement is closed
     *
     * @param agent The instance of NetAgent associated with this
     *              CallableStatement object.
     * @param connection The connection object associated with this
     *                   PreparedStatement Object.
     * @param sql        A String object that is the SQL statement to be sent
     *                   to the database.
     * @param section    Section
     * @param cpc The ClientPooledConnection wraps the underlying physical
     *            connection associated with this prepared statement.
     *            It is used to pass the Statement closed and the Statement
     *            error occurred events that occur back to the
     *            ClientPooledConnection.
     * @throws SqlException
     *
     */

    public PreparedStatement(Agent agent,
                             Connection connection,
                             String sql,
                             Section section,ClientPooledConnection cpc)
                             throws SqlException {
        super(agent, connection);
        // PreparedStatement is poolable by default
        isPoolable = true;
        initPreparedStatement(sql, section);
        pooledConnection_ = cpc;
    }
    
    public void resetPreparedStatement(Agent agent,
                                       Connection connection,
                                       String sql,
                                       Section section) throws SqlException {
        super.resetStatement(agent, connection);
        initPreparedStatement();
        initPreparedStatement(sql, section);
    }

    private void initPreparedStatement(String sql, Section section) throws SqlException {
// GemStone changes BEGIN
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "invoking initPreparedStatement2");
        }
// GemStone changes END
        sql_ = sql;
        isPreparedStatement_ = true;

        parseSqlAndSetSqlModes(sql_);
        section_ = section;
    }

    /**
     * The PreparedStatementConstructor used for jdbc 2 prepared statements 
     * with scroll attributes. Called by material statement constructors.
     * It has the ClientPooledConnection as one of its parameters 
     * this is used to raise the Statement Events when the prepared
     * statement is closed
     *
     * @param agent The instance of NetAgent associated with this
     *              CallableStatement object.
     * @param connection  The connection object associated with this
     *                    PreparedStatement Object.
     * @param sql         A String object that is the SQL statement
     *                    to be sent to the database.
     * @param type        One of the ResultSet type constants.
     * @param concurrency One of the ResultSet concurrency constants.
     * @param holdability One of the ResultSet holdability constants.
     * @param autoGeneratedKeys a flag indicating whether auto-generated
     *                          keys should be returned.
     * @param columnNames an array of column names indicating the columns that
     *                    should be returned from the inserted row or rows.
     * @param columnIndexes an array of column names indicating the columns that
     *                   should be returned from the inserted row.                   
     * @param cpc The ClientPooledConnection wraps the underlying physical
     *            connection associated with this prepared statement
     *            it is used to pass the Statement closed and the Statement
     *            error occurred events that occur back to the
     *            ClientPooledConnection.
     * @throws SqlException
     */
    public PreparedStatement(Agent agent,
                             Connection connection,
                             String sql,
                             int type, int concurrency, int holdability, 
                             int autoGeneratedKeys, String[] columnNames,
                             int[] columnIndexes,
                             ClientPooledConnection cpc) 
                             throws SqlException {
        super(agent, connection, type, concurrency, holdability, 
              autoGeneratedKeys, columnNames, columnIndexes);
        // PreparedStatement is poolable by default
        isPoolable = true;
        initPreparedStatement(sql);
        pooledConnection_ = cpc;
    }


    public void resetPreparedStatement(Agent agent,
                                       Connection connection,
                                       String sql,
                                       int type, int concurrency, int holdability, int autoGeneratedKeys, String[] columnNames,
                                       int[] columnIndexes) throws SqlException {
        super.resetStatement(agent, connection, type, concurrency, holdability, autoGeneratedKeys, 
                columnNames, columnIndexes);
// GemStone changes BEGIN
        // avoid full init since that will lose any parameters in failover
        // note that this is currently only invoked by failover code (#44101)
        /* (original code)
        initPreparedStatement();
        */
// GemStone changes END
        initPreparedStatement(sql);
    }

    private void initPreparedStatement(String sql) throws SqlException {
// GemStone changes BEGIN
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "invoking initPreparedStatement3"/*, new Throwable()*/);
        }
// GemStone changes END
        sql_ = super.escape(sql);
        parseSqlAndSetSqlModes(sql_);
        isPreparedStatement_ = true;

        // Check for positioned update statement and assign a section from the
        // same package as the corresponding query section.
        // Scan the sql for an "update...where current of <cursor-name>".
        String cursorName = null;
        if (sqlUpdateMode_ == isDeleteSql__ || sqlUpdateMode_ == isUpdateSql__) {
            String[] sqlAndCursorName = extractCursorNameFromWhereCurrentOf(sql_);
            if (sqlAndCursorName != null) {
                cursorName = sqlAndCursorName[0];
                sql_ = sqlAndCursorName[1];
            }
        }
        if (cursorName != null) {
            positionedUpdateCursorName_ = cursorName;
            // Get a new section from the same package as the query section
            section_ = agent_.sectionManager_.getPositionedUpdateSection(cursorName, false); // false means get a regular section

            if (section_ == null) {
                throw new SqlException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.CURSOR_INVALID_CURSOR_NAME), cursorName);
            }

            //scrollableRS_ = agent_.sectionManager_.getPositionedUpdateResultSet (cursorName);

            // if client's cursor name is set, and the cursor name in the positioned update
            // string is the same as the client's cursor name, replace client's cursor name
            // with the server's cursor name.
            // if the cursor name supplied in the sql string is different from the cursorName
            // set by setCursorName(), then server will return "cursor name not defined" error,
            // and no subsititution is made here.
            if (section_.getClientCursorName() != null && // cursor name is user defined
                    cursorName.compareTo(section_.getClientCursorName()) == 0)
            // client's cursor name is substituted with section's server cursor name
            {
                sql_ = substituteClientCursorNameWithServerCursorName(sql_, section_);
            }
        } else {
            // We don't need to analyze the sql text to determine if it is a query or not.
            // This is up to the server to decide, we just pass thru the sql on flowPrepare().
            section_ = agent_.sectionManager_.getDynamicSection(resultSetHoldability_);
        }
    }

    public void resetPreparedStatement(Agent agent,
                                       Connection connection,
                                       String sql,
                                       Section section,
                                       ColumnMetaData parameterMetaData,
                                       ColumnMetaData resultSetMetaData) throws SqlException {
        resetPreparedStatement(agent, connection, sql, section);
        initPreparedStatement(parameterMetaData, resultSetMetaData);
    }

    private void initPreparedStatement(ColumnMetaData parameterMetaData,
                                       ColumnMetaData resultSetMetaData) throws SqlException {
// GemStone changes BEGIN
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "invoking initPreparedStatement4"/*, new Throwable()*/);
        }
// GemStone changes END
        isPreparedStatement_ = true;
        parameterMetaData_ = parameterMetaData;
        resultSetMetaData_ = resultSetMetaData;
        if (parameterMetaData_ != null) {
            parameters_ = new Object[parameterMetaData_.columns_];
            //parameterSetOrRegistered_ = new boolean[parameterMetaData_.columns_];
            parameterSet_ = new boolean[parameterMetaData_.columns_];
            parameterRegistered_ = new boolean[parameterMetaData_.columns_];
        }
    }

    // called immediately after the constructor by Connection prepare*() methods
    void prepare() throws SqlException {
// GemStone changes BEGIN
        long cid = 0;
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          cid = SanityManager.getConnectionId(this.connection_);
          SanityManager.DEBUG_PRINT_COMPACT("prepare_S", sql_, cid,
              ns, true, null);
        }
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "prepare called on ps: " + this/*, new Throwable()*/);
        }
// GemStone changes END
        try {
            // flow prepare, no static initialization is needed
            flowPrepareDescribeInputOutput();
        } catch (SqlException e) {
// GemStone changes BEGIN
            // don't close for failover case since we will be retrying with the
            // same PreparedStatement
            if (connection_.doFailoverOnException(e.sqlstate_, e.errorcode_, e)
                == Connection.FailoverStatus.NONE)
// GemStone changes END
            this.markClosed();
            throw e;
        }
// GemStone changes BEGIN
        finally {
          if (SanityManager.TraceClientStatement) {
            final long ns = System.nanoTime();
            SanityManager.DEBUG_PRINT_COMPACT("prepare_E", sql_, cid,
                ns, false, null);
          }
        }
// GemStone changes END
    }

// GemStone changes BEGIN
    final void preparePostFailover() throws SqlException {
      prepare();
      if (this.clientParamTypeAtFirstExecute != null) {
        this.parameterMetaData_.clientParamtertype_ = 
          makeNewClientParamTypeFrom(this.clientParamTypeAtFirstExecute);
      }
    }
// GemStone changes END

    //------------------- Prohibited overrides from Statement --------------------

    public boolean execute(String sql) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceEntry(this, "execute", sql);
        }
        throw new SqlException(agent_.logWriter_,
            new ClientMessageId(SQLState.NOT_FOR_PREPARED_STATEMENT),
            "execute(String)").getSQLException(agent_ /* GemStoneAddition */);
    }

    public java.sql.ResultSet executeQuery(String sql) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceEntry(this, "executeQuery", sql);
        }
        throw new SqlException(agent_.logWriter_,
            new ClientMessageId(SQLState.NOT_FOR_PREPARED_STATEMENT),
            "executeQuery(String)").getSQLException(agent_ /* GemStoneAddition */);
    }

    public int executeUpdate(String sql) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceEntry(this, "executeUpdate", sql);
        }
        throw new SqlException(agent_.logWriter_,
            new ClientMessageId(SQLState.NOT_FOR_PREPARED_STATEMENT),
            "executeUpdate(String)").getSQLException(agent_ /* GemStoneAddition */);
    }
    // ---------------------------jdbc 1------------------------------------------

    public java.sql.ResultSet executeQuery() throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "executeQuery");
                }
                ResultSet resultSet = executeQueryX();
                // Gemstone changes BEGIN
                if (!this.superFlowExecuteCalled_) {
                  return singleHopPrepStmntExecuteQuery();
                }
                // Gemstone changes END
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceExit(this, "executeQuery", resultSet);
                }
                return resultSet;
            }
        }
        catch ( SqlException se ) {
            checkStatementValidity(se);
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

// GemStone changes BEGIN
    protected java.sql.ResultSet singleHopPrepStmntExecuteQuery()
        throws SQLException {
      throw new AssertionError(
          "PreparedStatement::singleHopPrepStmntExecuteQuery this method should not be called");
    }
// GemStone changes END
    
    // also called by some DBMD methods
    ResultSet executeQueryX() throws SqlException {
// GemStone changes BEGIN
/* original code
      flowExecute(executeQueryMethod__);
*/
        int currExecSeq = connection_.incrementAndGetExecutionSequence();
        flowExecute(executeQueryMethod__, currExecSeq);
// GemStone changes END
        return resultSet_;
    }


    public int executeUpdate() throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "executeUpdate");
                }
                int updateValue = executeUpdateX();
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceExit(this, "executeUpdate", updateValue);
                }
                return updateValue;
            }
        }
        catch ( SqlException se ) {
            checkStatementValidity(se);
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    private int executeUpdateX() throws SqlException {
// GemStone changes BEGIN
      /* original code
       flowExecute(executeUpdateMethod__);
       */
        int currExecSeq = connection_.incrementAndGetExecutionSequence();
        flowExecute(executeUpdateMethod__, currExecSeq);
// GemStone changes END
        return updateCount_;
    }

    public void setNull(int parameterIndex, int jdbcType) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setNull", parameterIndex, jdbcType);
                }

                checkForClosedStatement();

                // JDBC 4.0 requires us to throw
                // SQLFeatureNotSupportedException for certain target types if
                // they are not supported. Check for these types before
                // checking type compatibility.
                checkForSupportedDataType(jdbcType);
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);
                
// GemStone changes BEGIN
                // Types.NULL is set by Spring for unknown types
                // (http://communities.vmware.com/message/2007380)
                if (jdbcType == java.sql.Types.NULL) {
                  jdbcType = paramType;
                }
                else
// GemStone changes END
                if( ! PossibleTypes.getPossibleTypesForNull( paramType ).checkType( jdbcType )){
                    
                    //This exception mimic embedded behavior.
                    //see http://issues.apache.org/jira/browse/DERBY-1610#action_12432568
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      jdbcType,
                                                      paramType, parameterIndex);
                }
                
                setNullX(parameterIndex, jdbcType);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    // also used by DBMD methods
    void setNullX(int parameterIndex, int jdbcType) throws SqlException {
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] = jdbcType;

        if (!parameterMetaData_.nullable_[parameterIndex - 1]) {
            throw new SqlException(agent_.logWriter_, 
                new ClientMessageId(SQLState.LANG_NULL_INTO_NON_NULL),
                new Integer(parameterIndex));
        }
        setInput(parameterIndex, null);
    }

    public void setNull(int parameterIndex, int jdbcType, String typeName) throws SQLException {
        synchronized (connection_) {
            if (agent_.loggingEnabled()) {
                agent_.logWriter_.traceEntry(this, "setNull", parameterIndex,
                                             jdbcType, typeName);
            }
            setNull(parameterIndex, jdbcType);
        }
    }

    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setBoolean", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);

                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_GENERIC_SCALAR.checkType(paramType) ) {
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      java.sql.Types.BOOLEAN,
                                                      paramType, parameterIndex);
                    
                }
                
                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.BIT;
// GemStone changes BEGIN
                // changed to Short.valueOf() if possible
                setInput(parameterIndex, (short)(x ? 1 : 0));
                /* (original code)
                setInput(parameterIndex, new Short((short) (x ? 1 : 0)));
                */
// GemStone changes END
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setByte(int parameterIndex, byte x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setByte", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);
                
                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_GENERIC_SCALAR.checkType( paramType ) ){
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      java.sql.Types.TINYINT,
                                                      paramType, parameterIndex);
                    
                }
                
                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.TINYINT;
// GemStone changes BEGIN
                setInput(parameterIndex, (short)x);
                /* (original code)
                setInput(parameterIndex, new Short(x));
                */
// GemStone changes END
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setShort(int parameterIndex, short x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setShort", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);

                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_GENERIC_SCALAR.checkType(paramType) ){
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      java.sql.Types.SMALLINT,
                                                      paramType, parameterIndex);
                                                  

                }
                
                setShortX(parameterIndex, x);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    // also used by DBMD methods
    void setShortX(int parameterIndex, short x) throws SqlException {
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.SMALLINT;
// GemStone changes BEGIN
        setInput(parameterIndex, x);
        /* (original code)
        setInput(parameterIndex, new Short(x));
        */
// GemStone changes END

    }

    public void setInt(int parameterIndex, int x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setInt", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);

                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_GENERIC_SCALAR.checkType(paramType) ){
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      java.sql.Types.INTEGER,
                                                      paramType, parameterIndex);
                }
                
                setIntX(parameterIndex, x);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    // also used by DBMD methods
    void setIntX(int parameterIndex, int x) throws SqlException {
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.INTEGER;
// GemStone changes BEGIN
        setInput(parameterIndex, x);
        /* (original code)
        setInput(parameterIndex, new Integer(x));
        */
// GemStone changes END
    }


    public void setLong(int parameterIndex, long x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setLong", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);
                
                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_GENERIC_SCALAR.checkType(paramType) ){
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      java.sql.Types.INTEGER,
                                                      paramType, parameterIndex);
                }
                setLongX(parameterIndex, x);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    void setLongX(final int parameterIndex, final long x) 
    {
        // Column numbers starts at 1, clientParamtertype_[0] refers to column 1
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] 
                = java.sql.Types.BIGINT;
// GemStone changes BEGIN
        setInput(parameterIndex, x);
        /* (original code)
        setInput(parameterIndex, new Long(x));
        */
// GemStone changes END
    }

    public void setFloat(int parameterIndex, float x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setFloat", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);

                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_GENERIC_SCALAR.checkType(paramType) ){
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      java.sql.Types.FLOAT,
                                                      paramType, parameterIndex);

                }
                
                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.REAL;
                setInput(parameterIndex, new Float(x));
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setDouble(int parameterIndex, double x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setDouble", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);
                
                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_GENERIC_SCALAR.checkType(paramType) ){
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      java.sql.Types.DOUBLE,
                                                      paramType, parameterIndex);
                    
                }
                
                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.DOUBLE;
                setInput(parameterIndex, new Double(x));
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setBigDecimal(int parameterIndex, java.math.BigDecimal x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setBigDecimal", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);

                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_GENERIC_SCALAR.checkType( paramType ) ){
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      java.sql.Types.BIGINT,
                                                      paramType, parameterIndex);
                    
                }

                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.DECIMAL;
                if (x == null) {
                    setNull(parameterIndex, java.sql.Types.DECIMAL);
                    return;
                }
                int registerOutScale = 0;
                setInput(parameterIndex, x);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setDate(int parameterIndex, java.sql.Date x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setDate", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);
                
                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_DATE.checkType(paramType) ){
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_ ,
                                                      java.sql.Types.DATE,
                                                      paramType, parameterIndex);
                    
                }
                
                checkForClosedStatement();
                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.DATE;
                if (x == null) {
                    setNull(parameterIndex, java.sql.Types.DATE);
                    return;
                }
                setInput(parameterIndex, x);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setDate(int parameterIndex,
                        java.sql.Date x,
                        java.util.Calendar calendar) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setDate", parameterIndex, x, calendar);
                }
                checkForClosedStatement();
                if (calendar == null) {
                    throw new SqlException(agent_.logWriter_, 
                        new ClientMessageId(SQLState.INVALID_API_PARAMETER),
                        "null", "calendar", "setDate");
                }
                java.util.Calendar targetCalendar = java.util.Calendar.getInstance(calendar.getTimeZone());
                targetCalendar.clear();
                targetCalendar.setTime(x);
                java.util.Calendar defaultCalendar = java.util.Calendar.getInstance();
                defaultCalendar.clear();
                defaultCalendar.setTime(x);
                long timeZoneOffset =
                        targetCalendar.get(java.util.Calendar.ZONE_OFFSET) - defaultCalendar.get(java.util.Calendar.ZONE_OFFSET) +
                        targetCalendar.get(java.util.Calendar.DST_OFFSET) - defaultCalendar.get(java.util.Calendar.DST_OFFSET);
                java.sql.Date adjustedDate = ((timeZoneOffset == 0) || (x == null)) ? x : new java.sql.Date(x.getTime() + timeZoneOffset);
                setDate(parameterIndex, adjustedDate);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setTime(int parameterIndex, java.sql.Time x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setTime", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);

                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_TIME.checkType( paramType ) ){
                    
                    PossibleTypes.throw22005Exception( agent_.logWriter_,
                                                       java.sql.Types.TIME,
                                                       paramType, parameterIndex);
                }
                
                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.TIME;
                if (x == null) {
                    setNull(parameterIndex, java.sql.Types.TIME);
                    return;
                }
                setInput(parameterIndex, x);

            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setTime(int parameterIndex,
                        java.sql.Time x,
                        java.util.Calendar calendar) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setTime", parameterIndex, x, calendar);
                }
                checkForClosedStatement();
                if (calendar == null) {
                    throw new SqlException(agent_.logWriter_,
                        new ClientMessageId(SQLState.INVALID_API_PARAMETER),
                        "null", "calendar", "setTime()");
                }
                java.util.Calendar targetCalendar = java.util.Calendar.getInstance(calendar.getTimeZone());
                targetCalendar.clear();
                targetCalendar.setTime(x);
                java.util.Calendar defaultCalendar = java.util.Calendar.getInstance();
                defaultCalendar.clear();
                defaultCalendar.setTime(x);
                long timeZoneOffset =
                        targetCalendar.get(java.util.Calendar.ZONE_OFFSET) - defaultCalendar.get(java.util.Calendar.ZONE_OFFSET) +
                        targetCalendar.get(java.util.Calendar.DST_OFFSET) - defaultCalendar.get(java.util.Calendar.DST_OFFSET);
                java.sql.Time adjustedTime = ((timeZoneOffset == 0) || (x == null)) ? x : new java.sql.Time(x.getTime() + timeZoneOffset);
                setTime(parameterIndex, adjustedTime);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setTimestamp(int parameterIndex, java.sql.Timestamp x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setTimestamp", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);

                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_TIMESTAMP.checkType( paramType ) ) {
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      java.sql.Types.TIMESTAMP,
                                                      paramType, parameterIndex);
                    
                }
                
                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.TIMESTAMP;

                if (x == null) {
                    setNull(parameterIndex, java.sql.Types.TIMESTAMP);
                    return;
                }
                setInput(parameterIndex, x);
                // once the nanosecond field of timestamp is trim to microsecond for DERBY, should we throw a warning
                //if (getParameterType (parameterIndex) == java.sql.Types.TIMESTAMP && x.getNanos() % 1000 != 0)
                //  accumulateWarning (new SqlWarning (agent_.logWriter_, "DERBY timestamp can only store up to microsecond, conversion from nanosecond to microsecond causes rounding."));
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setTimestamp(int parameterIndex,
                             java.sql.Timestamp x,
                             java.util.Calendar calendar) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setTimestamp", parameterIndex, x, calendar);
                }
                checkForClosedStatement();
                if (calendar == null) {
                    throw new SqlException(agent_.logWriter_, 
                        new ClientMessageId(SQLState.INVALID_API_PARAMETER),
                        "null", "calendar", "setTimestamp()");
                }
                java.util.Calendar targetCalendar = java.util.Calendar.getInstance(calendar.getTimeZone());
                targetCalendar.clear();
                targetCalendar.setTime(x);
                java.util.Calendar defaultCalendar = java.util.Calendar.getInstance();
                defaultCalendar.clear();
                defaultCalendar.setTime(x);
                long timeZoneOffset =
                        targetCalendar.get(java.util.Calendar.ZONE_OFFSET) - defaultCalendar.get(java.util.Calendar.ZONE_OFFSET) +
                        targetCalendar.get(java.util.Calendar.DST_OFFSET) - defaultCalendar.get(java.util.Calendar.DST_OFFSET);
                java.sql.Timestamp adjustedTimestamp = ((timeZoneOffset == 0) || (x == null)) ? x : new java.sql.Timestamp(x.getTime() + timeZoneOffset);
                if (x != null) {
                    adjustedTimestamp.setNanos(x.getNanos());
                }
                setTimestamp(parameterIndex, adjustedTimestamp);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setString(int parameterIndex, String x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setString", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);

                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_STRING.checkType( paramType ) ){
                    PossibleTypes.throw22005Exception(agent_.logWriter_ ,
                                                      java.sql.Types.VARCHAR,
                                                      paramType, parameterIndex);
                }
                
                setStringX(parameterIndex, x);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    // also used by DBMD methods
    void setStringX(int parameterIndex, String x) throws SqlException {
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.LONGVARCHAR;
        if (x == null) {
            setNullX(parameterIndex, java.sql.Types.LONGVARCHAR);
            return;
        }
        setInput(parameterIndex, x);
    }

    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setBytes", parameterIndex, x);
                }
                
                final int paramType = 
                    getColumnMetaDataX().getColumnType(parameterIndex);
                
                if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_BYTES.checkType( paramType ) ){
                    
                    PossibleTypes.throw22005Exception(agent_.logWriter_,
                                                      java.sql.Types.VARBINARY,
                                                      paramType, parameterIndex);
                }
                
                setBytesX(parameterIndex, x);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    // also used by BLOB
    public void setBytesX(int parameterIndex, byte[] x) throws SqlException {
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.LONGVARBINARY;
        if (x == null) {
            setNullX(parameterIndex, java.sql.Types.LONGVARBINARY);
            return;
        }
        setInput(parameterIndex, x);

    }
    
    /**
     * sets the parameter to the  Binary Stream object
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @param length the number of bytes in the stream
     * @exception SQLException thrown on failure.
     */

    public void setBinaryStream(int parameterIndex,
                                java.io.InputStream x,
                                long length) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setBinaryStream", parameterIndex, "<input stream>", new Long(length));
                }
                
                checkTypeForSetBinaryStream(parameterIndex);

                 if(length > Integer.MAX_VALUE) {
                    throw new SqlException(agent_.logWriter_,
                        new ClientMessageId(SQLState.CLIENT_LENGTH_OUTSIDE_RANGE_FOR_DATATYPE),
                        new Long(length), new Integer(Integer.MAX_VALUE)).getSQLException(
                            agent_ /* GemStoneAddition */);
                }
                setBinaryStreamX(parameterIndex, x, (int)length);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    /**
     * sets the parameter to the  Binary Stream object
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @param length the number of bytes in the stream
     * @exception SQLException thrown on failure.
     */

    public void setBinaryStream(int parameterIndex,
                                java.io.InputStream x,
                                int length) throws SQLException {
        setBinaryStream(parameterIndex,x,(long)length);
    }

    protected void setBinaryStreamX(int parameterIndex,
                                 java.io.InputStream x,
                                 int length) throws SqlException {
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.BLOB;
        if (x == null) {
            setNullX(parameterIndex, java.sql.Types.BLOB);
            return;
        }
        Blob blob;
        if (length == -1) {
            // Create a blob of unknown length. This might cause an
            // OutOfMemoryError due to the temporary implementation in Blob.
            // The whole stream will be materialzied. See comments in Blob.
            blob = new Blob(agent_, x,
                this.parameterMetaData_.getColumnLabelX(parameterIndex) /* GemStoneAddition */);
        } else {
            blob = new Blob(agent_, x, length,
                this.parameterMetaData_.getColumnLabelX(parameterIndex) /* GemStoneAddition */);
        }
        setInput(parameterIndex, blob);
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

    public void setAsciiStream(int parameterIndex,
                               java.io.InputStream x,
                               long length) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setAsciiStream", parameterIndex, "<input stream>", new Long(length));
                }
                
                checkTypeForSetAsciiStream(parameterIndex);

                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.CLOB;
                if (x == null) {
                    setNull(parameterIndex, java.sql.Types.LONGVARCHAR);
                    return;
                }
                if(length > Integer.MAX_VALUE) {
                    throw new SqlException(agent_.logWriter_,
                        new ClientMessageId(SQLState.CLIENT_LENGTH_OUTSIDE_RANGE_FOR_DATATYPE),
                        new Long(length), new Integer(Integer.MAX_VALUE)).getSQLException(
                            agent_ /* GemStoneAddition */);
                }
                setInput(parameterIndex, new Clob(agent_, x, "ISO-8859-1", (int)length,
                    this.parameterMetaData_.getColumnLabelX(parameterIndex) /* GemStoneAddition */));
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
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
    public void setAsciiStream(int parameterIndex,
                               java.io.InputStream x,
                               int length) throws SQLException {
        setAsciiStream(parameterIndex,x,(long)length);
    }
    

    private void checkTypeForSetAsciiStream(int parameterIndex)
            throws SqlException, SQLException {
        int paramType = getColumnMetaDataX().getColumnType(parameterIndex);
        if ( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_ASCIISTREAM.checkType( paramType ) ) {
            
            PossibleTypes.throw22005Exception(agent_.logWriter_,
                                              java.sql.Types.LONGVARCHAR,
                                              paramType, parameterIndex);
            
            
        }
    }
    
    private void checkTypeForSetBinaryStream(int parameterIndex)
            throws SqlException, SQLException {
        int paramType = getColumnMetaDataX().getColumnType(parameterIndex);
        if (!PossibleTypes.POSSIBLE_TYPES_IN_SET_BINARYSTREAM.
                checkType(paramType)) {
            PossibleTypes.throw22005Exception(agent_.logWriter_,
                                              java.sql.Types.VARBINARY,
                                              paramType, parameterIndex);
        }
    }
    
    private void checkTypeForSetCharacterStream(int parameterIndex)
            throws SqlException, SQLException {
        int paramType = getColumnMetaDataX().getColumnType(parameterIndex);
        if (!PossibleTypes.POSSIBLE_TYPES_IN_SET_CHARACTERSTREAM.
                checkType(paramType)) {
            PossibleTypes.throw22005Exception(agent_.logWriter_,
                                              java.sql.Types.LONGVARCHAR,
                                              paramType, parameterIndex);
        }
    }

    private void checkTypeForSetBlob(int parameterIndex)
            throws SqlException, SQLException {
        int paramType = getColumnMetaDataX().getColumnType(parameterIndex);
        if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_BLOB.checkType( paramType ) ){
            
            PossibleTypes.throw22005Exception(agent_.logWriter_,
                                              java.sql.Types.BLOB,
                                              paramType, parameterIndex);
        }
    }
    
    
    private void checkTypeForSetClob(int parameterIndex)
            throws SqlException, SQLException {
        int paramType = getColumnMetaDataX().getColumnType(parameterIndex);
        if( ! PossibleTypes.POSSIBLE_TYPES_IN_SET_CLOB.checkType( paramType ) ){
                    
            PossibleTypes.throw22005Exception(agent_.logWriter_,
                                              java.sql.Types.CLOB,
                                              paramType, parameterIndex);
                    
        }
        
    }
    
    
    /**
     * Sets the specified parameter to the given input stream. Deprecated
     * in JDBC 3.0 and this method will always just throw a feature not
     * implemented exception.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the UNICODE parameter
     * value
     * @param length the number of bytes in the stream
     * @exception SQLException throws feature not implemented.
     * @deprecated
     */
    public void setUnicodeStream(int parameterIndex,
                                 java.io.InputStream x,
                                 int length) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceDeprecatedEntry(this, "setUnicodeStream",
                                                   parameterIndex,
                                                   "<input stream>", length);
        }

        throw SQLExceptionFactory.notImplemented ("setUnicodeStream");
    }

    /**
     * Sets the designated parameter to the given <code>Reader</code> object.
     * When a very large UNICODE value is input to a LONGVARCHAR parameter, it
     * may be more practical to send it via a <code>java.io.Reader</code>
     * object. The data will be read from the stream as needed until
     * end-of-file is reached. The JDBC driver will do any necessary conversion
     * from UNICODE to the database char format.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the <code>java.io.Reader</code> object that contains the
     *      Unicode data
     * @throws SQLException if a database access error occurs or this method is
     *      called on a closed <code>PreparedStatement</code>
     */
    public void setCharacterStream(int parameterIndex, Reader x)
            throws SQLException {
        synchronized (connection_) {
            if (agent_.loggingEnabled()) {
                agent_.logWriter_.traceEntry(this, "setCharacterStream",
                        parameterIndex, x);
            }
            try {
                checkTypeForSetCharacterStream(parameterIndex);
                parameterMetaData_.clientParamtertype_[parameterIndex -1] =
                    java.sql.Types.CLOB;
                if (x == null) {
                    setNull(parameterIndex, java.sql.Types.LONGVARCHAR);
                    return;
                }
                setInput(parameterIndex, new Clob(agent_, x,
                    this.parameterMetaData_.getColumnLabelX(parameterIndex) /* GemStoneAddition */));
            } catch (SqlException se) {
                throw se.getSQLException(agent_ /* GemStoneAddition */);
            }
        }
    }

     /**
     * Sets the designated parameter to the given Reader, which will have
     * the specified number of bytes.
     *
     * @param parameterIndex the index of the parameter to which this set
     *                       method is applied
     * @param x the java Reader which contains the UNICODE value
     * @param length the number of bytes in the stream
     * @exception SQLException thrown on failure.
     *
     */

    public void setCharacterStream(int parameterIndex,
                                   java.io.Reader x,
                                   long length) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setCharacterStream", parameterIndex, x, new Long(length));
                }
                checkTypeForSetCharacterStream(parameterIndex);
                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.CLOB;
                if (x == null) {
                    setNull(parameterIndex, java.sql.Types.LONGVARCHAR);
                    return;
                }
                if(length > Integer.MAX_VALUE) {
                    throw new SqlException(agent_.logWriter_,
                        new ClientMessageId(SQLState.CLIENT_LENGTH_OUTSIDE_RANGE_FOR_DATATYPE),
                        new Long(length), new Integer(Integer.MAX_VALUE)).getSQLException(
                            agent_ /* GemStoneAddition */);
                }
                setInput(parameterIndex, new Clob(agent_, x, (int)length,
                    this.parameterMetaData_.getColumnLabelX(parameterIndex) /* GemStoneAddition */));
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

     /**
     * Sets the designated parameter to the given Reader, which will have
     * the specified number of bytes.
     *
     * @param parameterIndex the index of the parameter to which this
     *                       set method is applied
     * @param x the java Reader which contains the UNICODE value
     * @param length the number of bytes in the stream
     * @exception SQLException thrown on failure.
     *
     */

    public void setCharacterStream(int parameterIndex,
                                   java.io.Reader x,
                                   int length) throws SQLException {
        setCharacterStream(parameterIndex,x,(long)length);
    }

    public void setBlob(int parameterIndex, java.sql.Blob x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setBlob", parameterIndex, x);
                }
                
                checkTypeForSetBlob(parameterIndex);
                setBlobX(parameterIndex, x);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    // also used by Blob
    public void setBlobX(int parameterIndex, java.sql.Blob x) throws SqlException {
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.BLOB;
        if (x == null) {
            setNullX(parameterIndex, java.sql.Types.BLOB);
            return;
        }
        setInput(parameterIndex, x);
    }

    public void setClob(int parameterIndex, java.sql.Clob x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setClob", parameterIndex, x);
                }
                checkTypeForSetClob(parameterIndex);
                setClobX(parameterIndex, x);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    // also used by Clob
    void setClobX(int parameterIndex, java.sql.Clob x) throws SqlException {
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.CLOB;
        if (x == null) {
            this.setNullX(parameterIndex, Types.CLOB);
            return;
        }
        setInput(parameterIndex, x);
    }


    public void setArray(int parameterIndex, java.sql.Array x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setArray", parameterIndex, x);
                }
                throw new SqlException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.JDBC_METHOD_NOT_IMPLEMENTED));
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public void setRef(int parameterIndex, java.sql.Ref x) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setRef", parameterIndex, x);
                }
                throw new SqlException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.JDBC_METHOD_NOT_IMPLEMENTED));
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }            
    }

    // The Java compiler uses static binding, so we must use instanceof
    // rather than to rely on separate setObject() methods for
    // each of the Java Object instance types recognized below.
    public void setObject(int parameterIndex, Object x) throws SQLException {
// GemStone changes BEGIN
      Class<?> cls;
      if (x != null) {
// GemStone changes END
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setObject", parameterIndex, x);
                }

                int paramType = getColumnMetaDataX().getColumnType(parameterIndex);

                if ( paramType == java.sql.Types.JAVA_OBJECT )
                {
                    setUDTX( parameterIndex, x );
                }
// GemStone changes BEGIN
                else if ((cls = x.getClass()) == String.class) {
                  setString(parameterIndex, (String) x);
                }
                else if (cls == Integer.class) {
                  setInt(parameterIndex, ((Integer) x).intValue());
                }
                else if (cls == Double.class) {
                  setDouble(parameterIndex, ((Double) x).doubleValue());
                }
                else if (cls == Float.class) {
                  setFloat(parameterIndex, ((Float) x).floatValue());
                }
                else if (cls == Boolean.class) {
                  setBoolean(parameterIndex, ((Boolean) x).booleanValue());
                }
                else if (cls == Long.class) {
                  setLong(parameterIndex, ((Long) x).longValue());
                }
                else if (cls == byte[].class) {
                  setBytes(parameterIndex, (byte[]) x);
                }
                else if (cls == java.math.BigDecimal.class) {
                  setBigDecimal(parameterIndex, (java.math.BigDecimal) x);
                }
                else if (cls == java.sql.Date.class) {
                  setDate(parameterIndex, (java.sql.Date) x);
                }
                else if (cls == java.sql.Time.class) {
                  setTime(parameterIndex, (java.sql.Time) x);
                }
                else if (cls == java.sql.Timestamp.class) {
                  setTimestamp(parameterIndex, (java.sql.Timestamp) x);
                }
                else if (x instanceof java.sql.Blob) {
                  setBlob(parameterIndex, (java.sql.Blob) x);
                }
                else if (x instanceof java.sql.Clob) {
                  setClob(parameterIndex, (java.sql.Clob) x);
                }
                else if (cls == Short.class) {
                  setShort(parameterIndex, ((Short) x).shortValue());
                }
                else if (cls == Byte.class) {
                  setByte(parameterIndex, ((Byte) x).byteValue());
                }
                else if (x instanceof java.math.BigDecimal) {
                  setBigDecimal(parameterIndex, (java.math.BigDecimal) x);
                }
                else if (x instanceof java.sql.Date) {
                  setDate(parameterIndex, (java.sql.Date) x);
                }
                else if (x instanceof java.sql.Time) {
                  setTime(parameterIndex, (java.sql.Time) x);
                }
                else if (x instanceof java.sql.Timestamp) {
                  setTimestamp(parameterIndex, (java.sql.Timestamp) x);
                /* (original code)
                else if (x instanceof String) {
                    setString(parameterIndex, (String) x);
                } else if (x instanceof Integer) {
                    setInt(parameterIndex, ((Integer) x).intValue());
                } else if (x instanceof Double) {
                    setDouble(parameterIndex, ((Double) x).doubleValue());
                } else if (x instanceof Float) {
                    setFloat(parameterIndex, ((Float) x).floatValue());
                } else if (x instanceof Boolean) {
                    setBoolean(parameterIndex, ((Boolean) x).booleanValue());
                } else if (x instanceof Long) {
                    setLong(parameterIndex, ((Long) x).longValue());
                } else if (x instanceof byte[]) {
                    setBytes(parameterIndex, (byte[]) x);
                } else if (x instanceof java.math.BigDecimal) {
                    setBigDecimal(parameterIndex, (java.math.BigDecimal) x);
                } else if (x instanceof java.sql.Date) {
                    setDate(parameterIndex, (java.sql.Date) x);
                } else if (x instanceof java.sql.Time) {
                    setTime(parameterIndex, (java.sql.Time) x);
                } else if (x instanceof java.sql.Timestamp) {
                    setTimestamp(parameterIndex, (java.sql.Timestamp) x);
                } else if (x instanceof java.sql.Blob) {
                    setBlob(parameterIndex, (java.sql.Blob) x);
                } else if (x instanceof java.sql.Clob) {
                    setClob(parameterIndex, (java.sql.Clob) x);
                */
                } else if (x instanceof java.sql.Array) {
                    setArray(parameterIndex, (java.sql.Array) x);
                } else if (x instanceof java.sql.Ref) {
                    setRef(parameterIndex, (java.sql.Ref) x);
                /* (original code)
                } else if (x instanceof Short) {
                    setShort(parameterIndex, ((Short) x).shortValue());
                } else if (x instanceof Byte) {
                    setByte(parameterIndex, ((Byte) x).byteValue());
                */
// GemStone changes END
                } else {
                    checkForClosedStatement();
                    checkForValidParameterIndex(parameterIndex);
                    throw new SqlException(agent_.logWriter_, 
                        new ClientMessageId(SQLState.UNSUPPORTED_TYPE));
                }
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
// GemStone changes BEGIN
      }
      else {
        setNull(parameterIndex, java.sql.Types.NULL);
      }
// GemStone changes END
    }

    /**
     * Set a UDT parameter to an object value.
     */
    private void setUDTX(int parameterIndex, Object x) throws SqlException, SQLException
    {
        int paramType = getColumnMetaDataX().getColumnType(parameterIndex);
        int expectedType = java.sql.Types.JAVA_OBJECT;
        
        if ( !( paramType == expectedType ) )
        {
            PossibleTypes.throw22005Exception
                (agent_.logWriter_, expectedType, paramType, parameterIndex);
        }
        
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] = expectedType;
        if (x == null) {
            setNullX(parameterIndex, expectedType );
            return;
        }

        //
        // Make sure that we are setting the parameter to an instance of the UDT.
        //
        
        Throwable problem = null;
        String sourceClassName = x.getClass().getName();
        String targetClassName = getColumnMetaDataX().getColumnClassName(parameterIndex);

        try {
            Class targetClass = Class.forName( targetClassName );
            if ( targetClass.isInstance( x ) )
            {
                setInput(parameterIndex, x);
                return;
            }
        }
        catch (ClassNotFoundException e) { problem = e; }
// GemStone changes BEGIN
        if (Boolean.TRUE.equals(ClientSharedUtils.ALLOW_THREADCONTEXT_CLASSLOADER.get())) {
          try {
            // also check with current thread context ClassLoader
            Class targetClass = Class.forName(targetClassName, true,
                Thread.currentThread().getContextClassLoader());
            if (targetClass.isInstance(x)) {
              setInput(parameterIndex, x);
              return;
            }
          } catch (ClassNotFoundException e) { problem = e; }
        }
//GemStone changes END

        throw new SqlException
            (
             agent_.logWriter_,
             new ClientMessageId( SQLState.NET_UDT_COERCION_ERROR ),
             new Object[] { sourceClassName, targetClassName },
             problem
             );
    }

    public void setObject(int parameterIndex, Object x, int targetJdbcType) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setObject", parameterIndex, x, targetJdbcType);
                }
                checkForClosedStatement();
                setObjectX(parameterIndex, x, targetJdbcType, 0);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */,
                parameterMetaData_.getColumnLabel(parameterIndex) /* GemStoneAddition */);
        }
    }

    public void setObject(int parameterIndex,
                          Object x,
                          int targetJdbcType,
                          int scale) throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "setObject", parameterIndex, x, targetJdbcType, scale);
                }
                checkForClosedStatement();
                setObjectX(parameterIndex, x, targetJdbcType, scale);
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */,
                parameterMetaData_.getColumnLabel(parameterIndex) /* GemStoneAddition */);
        }
    }

    private void setObjectX(int parameterIndex,
                            Object x,
                            int targetJdbcType,
                            int scale) throws SqlException {
        checkForValidParameterIndex(parameterIndex);
        checkForValidScale(scale);

        // JDBC 4.0 requires us to throw SQLFeatureNotSupportedException for
        // certain target types if they are not supported.
        checkForSupportedDataType(targetJdbcType);

        if (x == null) {
            setNullX(parameterIndex, targetJdbcType);
            return;
        }

        // JDBC Spec specifies that conversion should occur on the client if
        // the targetJdbcType is specified.

        int inputParameterType = CrossConverters.getInputJdbcType(targetJdbcType);
        parameterMetaData_.clientParamtertype_[parameterIndex - 1] = inputParameterType;
        x = agent_.crossConverters_.setObject(inputParameterType, x,
            this.parameterMetaData_.getColumnLabelX(parameterIndex) /* GemStoneAddition */);

        // Set to round down on setScale like embedded does in SQLDecimal
        try {
            if (targetJdbcType == java.sql.Types.DECIMAL || targetJdbcType == java.sql.Types.NUMERIC) {
                x = ((java.math.BigDecimal) x).setScale(scale, java.math.BigDecimal.ROUND_DOWN);
            }
        } catch (ArithmeticException ae) {
            // Any problems with scale should have already been caught by
            // checkForvalidScale
            throw new SqlException(agent_.logWriter_, 
                new ClientMessageId(SQLState.JAVA_EXCEPTION),
                new Object[] {ae.getClass().getName(), ae.getMessage()}, ae);
        }
        try { 
            setObject(parameterIndex, x);
        } catch ( SQLException se ) {
            throw new SqlException(se);
        }
    }

    // Since parameters are cached as objects in parameters_[],
    // java null may be used to represent SQL null.
    public void clearParameters() throws SQLException {
// GemStone changes BEGIN
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "clearing prepared statement parameters"/*, new Throwable()*/);
        }
// GemStone changes END
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "clearParameters");
                }
                checkForClosedStatement();
                if (parameterMetaData_ != null) {
                    if (SanityManager.TraceClientHA) {
                      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                          "clearParameters arrays.fill for ps: " + this
                          + " and parameters: " + parameters_/*, new Throwable()*/);
                    }
                    if (!this.internalPreparedStatement) {
                      Arrays.fill(parameters_, null);
                      Arrays.fill(parameterSet_, false);
                    }
                }
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public boolean execute() throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "execute");
                }
                boolean b = executeX();
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceExit(this, "execute", b);
                }
                return b;
            }
        }
        catch ( SqlException se ) {
            checkStatementValidity(se);
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    // also used by SQLCA
    boolean executeX() throws SqlException {
// GemStone changes BEGIN
      /* original code
       flowExecute(executeMethod__);
       */
        int currExecSeq = connection_.incrementAndGetExecutionSequence();
        flowExecute(executeMethod__, currExecSeq);
// GemStone changes END
        return resultSet_ != null;
    }

    //--------------------------JDBC 2.0-----------------------------

    public void addBatch() throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "addBatch");
                }
                checkForClosedStatement();
                checkThatAllParametersAreSet();
                
                if (parameterTypeList == null) {
                    parameterTypeList = new ArrayList();
                }

                // ASSERT: since OUT/INOUT parameters are not allowed, there should
                //         be no problem in sharing the JDBC Wrapper object instances
                //         since they will not be modified by the driver.

                // batch up the parameter values -- deep copy req'd

                if (parameterMetaData_ != null) {
                    Object[] inputsClone = new Object[parameters_.length];
                    System.arraycopy(parameters_, 0, inputsClone, 0, parameters_.length);

                    batch_.add(inputsClone);
                    
                    // Get a copy of the parameter type data and save it in a list
                    // which will be used later on at the time of batch execution.
                    parameterTypeList.add(parameterMetaData_.clientParamtertype_.clone());
                } else {
                    batch_.add(null);
                    parameterTypeList.add(null);
                }
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    // Batch requires that input types are exact, we perform no input cross conversion for Batch.
    // If so, this is an external semantic, and should go into the release notes
    public int[] executeBatch() throws SQLException, BatchUpdateException {
        try
        {
            synchronized (connection_) {
// GemStone changes BEGIN
              int currExecSeq = connection_.incrementAndGetExecutionSequence();
//GemStone changes END
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "executeBatch");
                }
                int[] updateCounts = null;
// GemStone changes BEGIN
                /* original code
                 updateCounts = executeBatchX(false
                 */
                updateCounts = executeBatchX(false, currExecSeq);
// GemStone changes END
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceExit(this, "executeBatch", updateCounts);
                }
                return updateCounts;
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    public java.sql.ResultSetMetaData getMetaData() throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "getMetaData");
                }
                ColumnMetaData resultSetMetaData = getMetaDataX();
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceExit(this, "getMetaData", resultSetMetaData);
                }
                return resultSetMetaData;
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    private ColumnMetaData getMetaDataX() throws SqlException {
        super.checkForClosedStatement();
        return resultSetMetaData_;
    }

    //------------------------- JDBC 3.0 -----------------------------------

    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceEntry(this, "execute", sql, autoGeneratedKeys);
        }
        throw new SqlException(agent_.logWriter_,
            new ClientMessageId(SQLState.NOT_FOR_PREPARED_STATEMENT),
            "execute(String, int)").getSQLException(agent_ /* GemStoneAddition */);
    }

    public boolean execute(String sql, String[] columnNames) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceEntry(this, "execute", sql, columnNames);
        }
        throw new SqlException(agent_.logWriter_,
            new ClientMessageId(SQLState.NOT_FOR_PREPARED_STATEMENT),
            "execute(String, String[])").getSQLException(agent_ /* GemStoneAddition */);
    }

    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceEntry(this, "execute", sql, columnIndexes);
        }
        throw new SqlException(agent_.logWriter_,
            new ClientMessageId(SQLState.NOT_FOR_PREPARED_STATEMENT),
            "execute(String, int[])").getSQLException(agent_ /* GemStoneAddition */);
    }

    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceEntry(this, "executeUpdate", autoGeneratedKeys);
        }
        throw new SqlException(agent_.logWriter_,
            new ClientMessageId(SQLState.NOT_FOR_PREPARED_STATEMENT),
            "executeUpdate(String, int)").getSQLException(agent_ /* GemStoneAddition */);
    }

    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceEntry(this, "executeUpdate", columnNames);
        }
        throw new SqlException(agent_.logWriter_,
            new ClientMessageId(SQLState.NOT_FOR_PREPARED_STATEMENT),
            "executeUpdate(String, String[])").getSQLException(agent_ /* GemStoneAddition */);
    }

    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceEntry(this, "executeUpdate", columnIndexes);
        }
        throw new SqlException(agent_.logWriter_,
            new ClientMessageId(SQLState.NOT_FOR_PREPARED_STATEMENT),
            "execute(String, int[])").getSQLException(agent_ /* GemStoneAddition */);
    }

    public void setURL(int parameterIndex, java.net.URL x) throws SQLException {
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceEntry(this, "setURL", parameterIndex, x);
        }
        jdbc3FeatureNotSupported(false);
    }

    public java.sql.ParameterMetaData getParameterMetaData() throws SQLException {
        try
        {
            synchronized (connection_) {
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceEntry(this, "getParameterMetaData");
                }
                Object parameterMetaData = getParameterMetaDataX();
                if (agent_.loggingEnabled()) {
                    agent_.logWriter_.traceExit(this, "getParameterMetaData", parameterMetaData);
                }
                return (java.sql.ParameterMetaData) parameterMetaData;
            }
        }
        catch ( SqlException se )
        {
            throw se.getSQLException(agent_ /* GemStoneAddition */);
        }
    }

    private ParameterMetaData getParameterMetaDataX() throws SqlException {
        ParameterMetaData pm = ClientDRDADriver.getFactory().newParameterMetaData
            ( getColumnMetaDataX() );
        return pm;
    }

    private ColumnMetaData getColumnMetaDataX() throws SqlException {
        checkForClosedStatement();
        return 
            parameterMetaData_ != null ?
            parameterMetaData_ : 
            ClientDRDADriver.getFactory().newColumnMetaData(
                agent_ /* GemStone change to use agent */, 0);
    }

    // ------------------------ box car and callback methods --------------------------------

    public void writeExecute(Section section,
                             ColumnMetaData parameterMetaData,
                             Object[] inputs,
                             int numInputColumns,
                             boolean outputExpected,
                             // This is a hint to the material layer that more write commands will follow.
                             // It is ignored by the driver in all cases except when blob data is written,
                             // in which case this boolean is used to optimize the implementation.
                             // Otherwise we wouldn't be able to chain after blob data is sent.
                             // Current servers have a restriction that blobs can only be chained with blobs
                             boolean chainedWritesFollowingSetLob) throws SqlException {
        materialPreparedStatement_.writeExecute_(section,
                parameterMetaData,
                inputs,
                numInputColumns,
                outputExpected,
                chainedWritesFollowingSetLob);
    }


    public void readExecute() throws SqlException {
        materialPreparedStatement_.readExecute_();
    }

    private void writeOpenQuery(Section section,
                               int fetchSize,
                               int resultSetType,
                               int numInputColumns,
                               ColumnMetaData parameterMetaData,
                               Object[] inputs) throws SqlException {
        materialPreparedStatement_.writeOpenQuery_(section,
                fetchSize,
                resultSetType,
                numInputColumns,
                parameterMetaData,
                inputs);
    }

    public void writeDescribeInput(Section section) throws SqlException {
        materialPreparedStatement_.writeDescribeInput_(section);
    }

    public void readDescribeInput() throws SqlException {
        materialPreparedStatement_.readDescribeInput_();
    }

    public void completeDescribeInput(ColumnMetaData parameterMetaData, Sqlca sqlca) {
        int sqlcode = super.completeSqlca(sqlca);
        if (sqlcode < 0) {
            return;
        }


        parameterMetaData_ = parameterMetaData;

        // The following code handles the case when
        // sqlxParmmode is not supported, in which case server will return 0 (unknown), and
        // this could clobber our guessed value for sqlxParmmode.  This is a problem.
        // We can solve this problem for Non-CALL statements, since the parmmode is always IN (1).
        // But what about CALL statements.  If CALLs are describable, then we have no
        // problem, we assume server won't return unknown.
        // If CALLs are not describable then nothing gets clobbered because we won't
        // parse out extended describe, so again  no problem.
        if (sqlMode_ != isCall__ && parameterMetaData_ != null) {
            // 1 means IN parameter
            Arrays.fill(parameterMetaData_.sqlxParmmode_, (short)1);
        }

        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceParameterMetaData(this, parameterMetaData_);
        }
    }

    public void writeDescribeOutput(Section section) throws SqlException {
        materialPreparedStatement_.writeDescribeOutput_(section);
    }

    public void readDescribeOutput() throws SqlException {
        materialPreparedStatement_.readDescribeOutput_();
    }

    public void completeDescribeOutput(ColumnMetaData resultSetMetaData, Sqlca sqlca) {
        int sqlcode = super.completeSqlca(sqlca);
        if (sqlcode < 0) {
            return;
        }
        resultSetMetaData_ = resultSetMetaData;
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceResultSetMetaData(this, resultSetMetaData);
        }
    }

    void writePrepareDescribeInputOutput() throws SqlException {
        // Notice that sql_ is passed in since in general ad hoc sql must be passed in for unprepared statements
// Gemstone changes BEGIN
      //System.out.println("KN: is single hop enabled: " + connection_.isSingleHopEnabled());
      section_.setSingleHopInfoRequired(connection_.isSingleHopEnabled()
          && !this.singleHopInfoAlreadyFetched_);
      boolean needStamentUUID = needStatementUUID() && !connection_.cancelDisabled();
// Gemstone changes END
        writePrepareDescribeOutput(sql_, section_, needStamentUUID);
        writeDescribeInput(section_);
    }

    void readPrepareDescribeInputOutput() throws SqlException {
        readPrepareDescribeOutput();
        readDescribeInput();
        completePrepareDescribe();
    }

    void writePrepareDescribeInput() throws SqlException {
        // performance will be better if we flow prepare with output enable vs. prepare then describe input for callable
        // Notice that sql_ is passed in since in general ad hoc sql must be passed in for unprepared statements
        writePrepare(sql_, section_);
        writeDescribeInput(section_);
    }

    void readPrepareDescribeInput() throws SqlException {
        readPrepare();
        readDescribeInput();
        completePrepareDescribe();
    }

    void completePrepareDescribe() {
        if (parameterMetaData_ == null) {
            return;
        }
        parameters_ = expandObjectArray(parameters_, parameterMetaData_.columns_);
        parameterSet_ = expandBooleanArray(parameterSet_, parameterMetaData_.columns_);
        parameterRegistered_ = expandBooleanArray(parameterRegistered_, parameterMetaData_.columns_);
    }

    private Object[] expandObjectArray(Object[] array, int newLength) {
        if (array == null) {
            Object[] newArray = new Object[newLength];
            return newArray;
        }
        if (array.length < newLength) {
            Object[] newArray = new Object[newLength];
            System.arraycopy(array, 0, newArray, 0, array.length);
            return newArray;
        }
        return array;
    }

    private boolean[] expandBooleanArray(boolean[] array, int newLength) {
        if (array == null) {
            boolean[] newArray = new boolean[newLength];
            return newArray;
        }
        if (array.length < newLength) {
            boolean[] newArray = new boolean[newLength];
            System.arraycopy(array, 0, newArray, 0, array.length);
            return newArray;
        }
        return array;
    }

    void writePrepareDescribeInputOutput(String sql,
                                         Section section) throws SqlException {
        // Notice that sql_ is passed in since in general ad hoc sql must be passed in for unprepared statements
        writePrepareDescribeOutput(sql, section, false);
        writeDescribeInput(section);
    }

    void flowPrepareDescribeInputOutput() throws SqlException {
// GemStone changes BEGIN
     // disable disconnect event that will close everything since we may be
     // able to failover and retry
     final boolean origDisableDisconnect = this.agent_.disableDisconnectEvent_;
     this.agent_.disableDisconnectEvent_ = true;
     try {
      java.util.Set failedUrls = null;
      for (;;) {
        try {
// GemStone changes END
        agent_.beginWriteChain(this);
// GemStone changes BEGIN
        final String setSchema = connection_.doSetSchema_;
        if (setSchema != null) {
          // write the special register section to set the schema
          this.specialRegisterArrayList.set(0,
              SET_SCHEMA_STATEMENT + setSchema);
          writeSetSpecialRegister(specialRegisterArrayList);
          connection_.doSetSchema_ = null;
        }
        if (sqlMode_ == isCall__) {
            writePrepareDescribeInput();
            agent_.flow(this);
            readPrepareDescribeInput();
            agent_.endReadChain();
        } else {
            writePrepareDescribeInputOutput();
            agent_.flow(this);
            // GemStone changes BEGIN
            if (needStatementUUID() && !connection_.cancelDisabled()) { 
              readStatementID();
            }
            // GemStone changes END
            readPrepareDescribeInputOutput();
            agent_.endReadChain();
        }
// GemStone changes BEGIN
        break;
        } catch (SqlException sqle) {
          failedUrls = connection_.handleFailover(failedUrls, sqle);
        }
      }
     } finally {
       this.agent_.disableDisconnectEvent_ = origDisableDisconnect;
     }
// GemStone changes END
    }

 // GemStone changes BEGIN
    static class FlowExecuteContext {
      private final ResultSet scrollableRS_;
      private final boolean chainAutoCommit_;
      private final boolean commitSubstituted_;
      private final boolean originalDisableDisconnect_;

      FlowExecuteContext(ResultSet scrollableRS, 
          boolean chainAutoCommit, boolean commitSubstituted, 
          boolean originalDisableDisconnect) {
        this.scrollableRS_ = scrollableRS;
        this.chainAutoCommit_ =  chainAutoCommit;
        this.commitSubstituted_ = commitSubstituted;
        this.originalDisableDisconnect_ = originalDisableDisconnect;
      }
    }

  // The below two methods are part 1 (write) and part 2 (read) of the
    // original flowExecute
  protected FlowExecuteContext flowExecuteWrite(int executeType, final int currExecSeq)
      throws SqlException {
    // GemStone changes BEGIN
    // disable disconnect event that will close everything since we may be
    // able to failover and retry
    final boolean origDisableDisconnect = this.agent_.disableDisconnectEvent_;
    this.agent_.disableDisconnectEvent_ = true;
    // try {
    // java.util.Set failedUrls = null;
    // for (;;) {
    // try {
    if (SanityManager.TraceClientHA | SanityManager.TraceSingleHop) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
          "flowexecuteWrite called sql: " + sql_ + " exec seq: " + currExecSeq);
    }
    // GemStone changes END
    checkForClosedStatement();
    checkAutoGeneratedKeysParameters();
    clearWarningsX();
    checkForAppropriateSqlMode(executeType, sqlMode_);
    checkThatAllParametersAreSet();

    if (sqlMode_ == isUpdate__) {
      updateCount_ = 0;
    }
    else {
      updateCount_ = -1;
    }

    // DERBY-1036: Moved check till execute time to comply with embedded
    // behavior. Since we check here and not in setCursorName, several
    // statements can have the same cursor name as long as their result
    // sets are not simultaneously open.

    if (sqlMode_ == isQuery__) {
      checkForDuplicateCursorName();
    }

    agent_.beginWriteChain(this);

    boolean piggybackedAutocommit = writeCloseResultSets(true); // true means
                                                                // permit
                                                                // auto-commits

    int numInputColumns;
    boolean outputExpected;
    try {
      numInputColumns = (parameterMetaData_ != null) ? parameterMetaData_
          .getColumnCount() : 0;
      outputExpected = (resultSetMetaData_ != null && resultSetMetaData_
          .getColumnCount() > 0);
    } catch (SQLException se) {
      // Generate a SqlException for this, we don't want to throw
      // SQLException in this internal method
      throw new SqlException(se);
    }
    boolean chainAutoCommit = false;
    boolean commitSubstituted = false;
    boolean repositionedCursor = false;
    boolean timeoutSent = false;
    ResultSet scrollableRS = null;

    if (doWriteTimeout) {
      specialRegisterArrayList.set(0, TIMEOUT_STATEMENT + timeout_);
      writeSetSpecialRegister(specialRegisterArrayList);
      doWriteTimeout = false;
      timeoutSent = true;
    }
    switch (sqlMode_) {
      case isUpdate__:
        if (positionedUpdateCursorName_ != null) {
          scrollableRS = agent_.sectionManager_
              .getPositionedUpdateResultSet(positionedUpdateCursorName_);
        }
        if (scrollableRS != null && !scrollableRS.isRowsetCursor_) {
          repositionedCursor = scrollableRS
              .repositionScrollableResultSetBeforeJDBC1PositionedUpdateDelete();
          if (!repositionedCursor) {
            scrollableRS = null;
          }
        }

        chainAutoCommit = connection_.willAutoCommitGenerateFlow()
            && isAutoCommittableStatement_;

        boolean chainOpenQueryForAutoGeneratedKeys = (sqlUpdateMode_ == isInsertSql__ && autoGeneratedKeys_ == RETURN_GENERATED_KEYS);
        writeExecute(section_, parameterMetaData_, parameters_,
            numInputColumns, outputExpected,
            (chainAutoCommit || chainOpenQueryForAutoGeneratedKeys)// chain flag
        ); // chain flag

        if (chainOpenQueryForAutoGeneratedKeys) {
          prepareAutoGeneratedKeysStatement();
          writeOpenQuery(preparedStatementForAutoGeneratedKeys_.section_,
              preparedStatementForAutoGeneratedKeys_.fetchSize_,
              preparedStatementForAutoGeneratedKeys_.resultSetType_);
        }

        if (chainAutoCommit) {
          // we have encountered an error in writing the execute, so do not
          // flow an autocommit
          if (agent_.accumulatedReadExceptions_ != null) {
            // currently, the only write exception we encounter is for
            // data truncation: SQLSTATE 01004, so we don't bother checking for
            // this
            connection_.writeCommitSubstitute_();
            commitSubstituted = true;
          }
          else {
            // there is no write error, so flow the commit
            connection_.writeCommit();
          }
        }
        break;

      case isQuery__:
        writeOpenQuery(section_, fetchSize_, resultSetType_, numInputColumns,
            parameterMetaData_, parameters_);
        break;

      case isCall__:
        writeExecuteCall(outputRegistered_, // if no out/inout parameter,
                                            // outputExpected = false
            null, section_, fetchSize_, false, // do not suppress ResultSets for
                                               // regular CALLs
            resultSetType_, parameterMetaData_, parameters_); // cross
                                                              // conversion
        break;
    }

    agent_.flow(this);

    super.readCloseResultSets(true); // true means permit auto-commits

    // turn inUnitOfWork_ flag back on and add statement
    // back on commitListeners_ list if they were off
    // by an autocommit chained to a close cursor.
    if (piggybackedAutocommit) {
      connection_.completeTransactionStart();
    }

    markResultSetsClosed(true); // true means remove from list of commit and
                                // rollback listeners

    if (timeoutSent) {
      readSetSpecialRegister(); // Read response to the EXCSQLSET
    }
    return new FlowExecuteContext(scrollableRS, chainAutoCommit,
        commitSubstituted, origDisableDisconnect);
  }

  protected final void flowExecuteRead(int executeType, FlowExecuteContext ctx)
      throws SqlException {
    if (SanityManager.TraceSingleHop) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
          " PreparedStatement flowExecuteRead called on: " + this
          + " with FlowExecuteContext: " + ctx + " and exec type: " + executeType);
    }
    final ResultSet scrollableRS = ctx.scrollableRS_;
    final boolean chainAutoCommit = ctx.chainAutoCommit_;
    final boolean commitSubstituted = ctx.commitSubstituted_;
    final boolean origDisableDisconnect = ctx.originalDisableDisconnect_;
    try {
    // java.util.Set failedUrls = null;
    // for (;;) {
    // try {
    switch (sqlMode_) {
      case isUpdate__:
        // do not need to reposition for a rowset cursor
        if (scrollableRS != null && !scrollableRS.isRowsetCursor_) {
          scrollableRS.readPositioningFetch_();
        }

        else {
          readExecute();

          if (sqlUpdateMode_ == isInsertSql__
              && autoGeneratedKeys_ == RETURN_GENERATED_KEYS) {
            readPrepareAutoGeneratedKeysStatement();
            preparedStatementForAutoGeneratedKeys_.readOpenQuery();
            generatedKeysResultSet_ = preparedStatementForAutoGeneratedKeys_.resultSet_;
            preparedStatementForAutoGeneratedKeys_.resultSet_ = null;
          }
        }

        if (chainAutoCommit) {
          if (commitSubstituted) {
            connection_.readCommitSubstitute_();
          }
          else {
            connection_.readCommit();
          }
        }
        break;

      case isQuery__:
        try {
          readOpenQuery();
        } catch (DisconnectException dise) {
          throw dise;
        } catch (SqlException e) {
          throw e;
        }
        // resultSet_ is null if open query failed.
        // check for null resultSet_ before using it.
        if (resultSet_ != null) {
          resultSet_.parseScrollableRowset();
          // if (resultSet_.scrollable_) resultSet_.getRowCount();

          // DERBY-1183: If we set it up earlier, the entry in
          // clientCursorNameCache_ gets wiped out by the closing of
          // result sets happening during readCloseResultSets above
          // because ResultSet#markClosed calls
          // Statement#removeClientCursorNameFromCache.
          setupCursorNameCacheAndMappings();
        }
        break;

      case isCall__:
        readExecuteCall();
        break;

    }

    try {
      agent_.endReadChain();
    } catch (SqlException e) {
      throw e;

    }

    if (sqlMode_ == isCall__) {
      parseStorProcReturnedScrollableRowset();
      checkForStoredProcResultSetCount(executeType);
      // When there are no result sets back, we will commit immediately when
      // autocommit is true.
      // make sure a commit is not performed when making the call to the sqlca
      // message procedure
      if (connection_.autoCommit_ && resultSet_ == null
          && resultSetList_ == null && isAutoCommittableStatement_) {
        connection_.flowAutoCommit();
      }
    }

    // The JDBC spec says that executeUpdate() should return 0
    // when no row count is returned.
    if (executeType == executeUpdateMethod__ && updateCount_ < 0) {
      updateCount_ = 0;
    }

    // Throw an exception if holdability returned by the server is different
    // from requested.
    if (resultSet_ != null
        && resultSet_.resultSetHoldability_ != resultSetHoldability_
        && sqlMode_ != isCall__) {
      throw new SqlException(agent_.logWriter_, new ClientMessageId(
          SQLState.UNABLE_TO_OPEN_RESULTSET_WITH_REQUESTED_HOLDABILTY),
          new Integer(resultSetHoldability_));
    }
    // GemStone changes BEGIN
    // break;
    // } catch (SqlException sqle) {
    // failedUrls = connection_.handleFailover(failedUrls, sqle);
    // // re-prepare the statement, if required, before retry
    // prepare();
    // }
    // }
     } finally {
       this.agent_.disableDisconnectEvent_ = origDisableDisconnect;
     }
    // GemStone changes END
  }

  // GemStone changes END
    
    protected void flowExecute(int executeType, final int currExecSeq) throws SqlException {
// GemStone changes BEGIN
      long cid = 0;
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        cid = SanityManager.getConnectionId(this.connection_);
        SanityManager.DEBUG_PRINT_COMPACT("PS_flowExecute_S", sql_, cid, ns,
            true, sql_.contains("SYSIBM.MetaData") ? new Throwable() : null);
      }
      if (this.clientParamTypeAtFirstExecute == null) {
        int[] clientParamtype = this.parameterMetaData_ != null ? this.parameterMetaData_.clientParamtertype_ : null;
        if (clientParamtype != null) {
          this.clientParamTypeAtFirstExecute = setAndStoreClientParamType(clientParamtype);
        }
      }
     // disable disconnect event that will close everything since we may be
     // able to failover and retry
      this.superFlowExecuteCalled_ = true;
      
      if (section_ != null) {
        section_.setExecutionSequence(currExecSeq);
      }
      if (SanityManager.TraceSingleHop) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
            " PreparedStatement flowExecute called on: " + this + ", sql is "
                + this.sql_ + " and parameters is: "
                + SingleHopPreparedStatement.returnParameters(parameters_)
                + " execution seq: " + currExecSeq);
      }
     final boolean origDisableDisconnect = this.agent_.disableDisconnectEvent_;
     this.agent_.disableDisconnectEvent_ = true;
     try {
      java.util.Set failedUrls = null;
      for (;;) {
        try {
// GemStone changes END
        checkForClosedStatement();
        checkAutoGeneratedKeysParameters();
        clearWarningsX();
        checkForAppropriateSqlMode(executeType, sqlMode_);
        checkThatAllParametersAreSet();

        if (sqlMode_ == isUpdate__) {
            updateCount_ = 0;
        } else {
            updateCount_ = -1;
        }

        // DERBY-1036: Moved check till execute time to comply with embedded
        // behavior. Since we check here and not in setCursorName, several
        // statements can have the same cursor name as long as their result
        // sets are not simultaneously open.

        if (sqlMode_ == isQuery__) {
            checkForDuplicateCursorName();
        }

            agent_.beginWriteChain(this);

            boolean piggybackedAutocommit = writeCloseResultSets(true);  // true means permit auto-commits

            int numInputColumns;
            boolean outputExpected;
            try
            {
                numInputColumns = (parameterMetaData_ != null) ? parameterMetaData_.getColumnCount() : 0;
                outputExpected = (resultSetMetaData_ != null && resultSetMetaData_.getColumnCount() > 0);
            }
            catch ( SQLException se )
            {
                // Generate a SqlException for this, we don't want to throw
                // SQLException in this internal method
                throw new SqlException(se);
            }
            boolean chainAutoCommit = false;
            boolean commitSubstituted = false;
            boolean repositionedCursor = false;
            boolean timeoutSent = false;
            ResultSet scrollableRS = null;

            if (doWriteTimeout) {
                specialRegisterArrayList.set(0, TIMEOUT_STATEMENT + timeout_);
                writeSetSpecialRegister(specialRegisterArrayList);
                doWriteTimeout = false;
                timeoutSent = true;
            }
            switch (sqlMode_) {
            case isUpdate__:
                if (positionedUpdateCursorName_ != null) {
                    scrollableRS = agent_.sectionManager_.getPositionedUpdateResultSet(positionedUpdateCursorName_);
                }
                if (scrollableRS != null && !scrollableRS.isRowsetCursor_) {
                    repositionedCursor =
                            scrollableRS.repositionScrollableResultSetBeforeJDBC1PositionedUpdateDelete();
                    if (!repositionedCursor) {
                        scrollableRS = null;
                    }
                }

                chainAutoCommit = connection_.willAutoCommitGenerateFlow() && isAutoCommittableStatement_;

                boolean chainOpenQueryForAutoGeneratedKeys = (sqlUpdateMode_ == isInsertSql__ && autoGeneratedKeys_ == RETURN_GENERATED_KEYS);
                writeExecute(section_,
                        parameterMetaData_,
                        parameters_,
                        numInputColumns,
                        outputExpected,
                        (chainAutoCommit || chainOpenQueryForAutoGeneratedKeys)// chain flag
                ); // chain flag

                if (chainOpenQueryForAutoGeneratedKeys) {
                    prepareAutoGeneratedKeysStatement();
                    writeOpenQuery(preparedStatementForAutoGeneratedKeys_.section_,
                            preparedStatementForAutoGeneratedKeys_.fetchSize_,
                            preparedStatementForAutoGeneratedKeys_.resultSetType_);
                }
                

                if (chainAutoCommit) {
                    // we have encountered an error in writing the execute, so do not
                    // flow an autocommit
                    if (agent_.accumulatedReadExceptions_ != null) {
                        // currently, the only write exception we encounter is for
                        // data truncation: SQLSTATE 01004, so we don't bother checking for this
                        connection_.writeCommitSubstitute_();
                        commitSubstituted = true;
                    } else {
                        // there is no write error, so flow the commit
                        connection_.writeCommit();
                    }
                }
                break;

            case isQuery__:
                writeOpenQuery(section_,
                        fetchSize_,
                        resultSetType_,
                        numInputColumns,
                        parameterMetaData_,
                        parameters_);
                break;

            case isCall__:
                writeExecuteCall(outputRegistered_, // if no out/inout parameter, outputExpected = false
                        null,
                        section_,
                        fetchSize_,
                        false, // do not suppress ResultSets for regular CALLs
                        resultSetType_,
                        parameterMetaData_,
                        parameters_); // cross conversion
                break;
            }

            agent_.flow(this);

            super.readCloseResultSets(true);  // true means permit auto-commits

            // turn inUnitOfWork_ flag back on and add statement
            // back on commitListeners_ list if they were off
            // by an autocommit chained to a close cursor.
            if (piggybackedAutocommit) {
                connection_.completeTransactionStart();
            }

            markResultSetsClosed(true); // true means remove from list of commit and rollback listeners

            if (timeoutSent) {
                readSetSpecialRegister(); // Read response to the EXCSQLSET
            }

            switch (sqlMode_) {
            case isUpdate__:
                // do not need to reposition for a rowset cursor
                if (scrollableRS != null && !scrollableRS.isRowsetCursor_) {
                    scrollableRS.readPositioningFetch_();
                }

                else {
                    readExecute();

                    if (sqlUpdateMode_ == isInsertSql__ && autoGeneratedKeys_ == RETURN_GENERATED_KEYS) {
                        readPrepareAutoGeneratedKeysStatement();
                        preparedStatementForAutoGeneratedKeys_.readOpenQuery();
                        generatedKeysResultSet_ = preparedStatementForAutoGeneratedKeys_.resultSet_;
                        preparedStatementForAutoGeneratedKeys_.resultSet_ = null;
                    }
                }

                if (chainAutoCommit) {
                    if (commitSubstituted) {
                        connection_.readCommitSubstitute_();
                    } else {
                        connection_.readCommit();
                    }
                }
                break;

            case isQuery__:
                try {
                    readOpenQuery();
                } catch (DisconnectException dise) {
                    throw dise;
                } catch (SqlException e) {
                    throw e;
                }
                // resultSet_ is null if open query failed.
                // check for null resultSet_ before using it.
                if (resultSet_ != null) {
                    resultSet_.parseScrollableRowset();
                    //if (resultSet_.scrollable_) resultSet_.getRowCount();

                    // DERBY-1183: If we set it up earlier, the entry in
                    // clientCursorNameCache_ gets wiped out by the closing of
                    // result sets happening during readCloseResultSets above
                    // because ResultSet#markClosed calls
                    // Statement#removeClientCursorNameFromCache.
                    setupCursorNameCacheAndMappings();
                }
                break;

            case isCall__:
                readExecuteCall();
                break;

            }


            try {
                agent_.endReadChain();
            } catch (SqlException e) {
                throw e;

            }

            if (sqlMode_ == isCall__) {
                parseStorProcReturnedScrollableRowset();
                checkForStoredProcResultSetCount(executeType);
                // When there are no result sets back, we will commit immediately when autocommit is true.
                // make sure a commit is not performed when making the call to the sqlca message procedure
                if (connection_.autoCommit_ && resultSet_ == null && resultSetList_ == null && isAutoCommittableStatement_) {
                    connection_.flowAutoCommit();
                }
            }

            // The JDBC spec says that executeUpdate() should return 0
            // when no row count is returned.
            if (executeType == executeUpdateMethod__ && updateCount_ < 0) {
                updateCount_ = 0;
            }

            // Throw an exception if holdability returned by the server is different from requested.
            if (resultSet_ != null && resultSet_.resultSetHoldability_ != resultSetHoldability_ && sqlMode_ != isCall__) {
                throw new SqlException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.UNABLE_TO_OPEN_RESULTSET_WITH_REQUESTED_HOLDABILTY),
                        new Integer(resultSetHoldability_));
            }
// GemStone changes BEGIN
            if (section_ != null) {
              section_.recordSuccessAndclearExecutionSequence(connection_, currExecSeq);
            }
          break;
        } catch (SqlException sqle) {
          failedUrls = connection_.handleFailover(failedUrls, sqle);
          // re-prepare the statement, if required, before retry
          preparePostFailover();
        }
      }
     } finally {
       this.agent_.disableDisconnectEvent_ = origDisableDisconnect;
       if (SanityManager.TraceClientStatement) {
         final long ns = System.nanoTime();
         SanityManager.DEBUG_PRINT_COMPACT("PS_flowExecute_E", sql_, cid,
             ns, false, null);
       }
     }
// GemStone changes END
    }

    public int[] executeBatchX(boolean supportsQueryBatchRequest, int currExecSeq) 
        throws SqlException, SQLException, BatchUpdateException {
        synchronized (connection_) {
            checkForClosedStatement(); // Per jdbc spec (see Statement.close() javadoc)
            clearWarningsX(); // Per jdbc spec 0.7, also see getWarnings() javadoc
            return executeBatchRequestX(supportsQueryBatchRequest, currExecSeq);
        }
    }


    private int[] executeBatchRequestX(boolean supportsQueryBatchRequest, final int currExecSeq)
            throws SqlException, BatchUpdateException {
// GemStone changes BEGIN
     long cid = 0;
     if (SanityManager.TraceClientStatement) {
       final long ns = System.nanoTime();
       cid = SanityManager.getConnectionId(this.connection_);
       SanityManager.DEBUG_PRINT_COMPACT("PS_execBatch_S", sql_, cid, ns,
           true, null);
     }
    if (SanityManager.TraceClientHA | SanityManager.TraceSingleHop) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
          "executeBatchRequestX called with sql: " + sql_
              + " and exec sequence: " + currExecSeq);
    }
     // disable disconnect event that will close everything since we may be
     // able to failover and retry
     final boolean origDisableDisconnect = this.agent_.disableDisconnectEvent_;
     this.agent_.disableDisconnectEvent_ = true;
     int batchSize = 0;
     try {
      java.util.Set failedUrls = null;
      for (;;) {
        SqlException chainBreaker = null;
        boolean doRetry = false;
        try {
        /* (original code)
        SqlException chainBreaker = null;
        */

// GemStone changes END
        batchSize = batch_.size();
        int[] updateCounts = new int[batchSize];
        int numInputColumns;
        try {
            numInputColumns = parameterMetaData_ == null ? 0 : parameterMetaData_.getColumnCount();
        } catch ( SQLException se ) {
            throw new SqlException(se);
        }
        Object[] savedInputs = null;  // used to save/restore existing parameters
        boolean timeoutSent = false;

        if (batchSize == 0) {
            if (SanityManager.TraceClientStatementHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "returning for batchSize=0");
            }
            return updateCounts;
        }
		// The network client has a hard limit of 65,534 commands in a single
		// DRDA request. This is because DRDA uses a 2-byte correlation ID,
		// and the values 0 and 0xffff are reserved as special values. So
		// that imposes an upper limit on the batch size we can support:
		if (batchSize > 65534)
            throw new BatchUpdateException(agent_.logWriter_, 
                new ClientMessageId(SQLState.TOO_MANY_COMMANDS_FOR_BATCH), 
                new Integer(65534), updateCounts);

        // Initialize all the updateCounts to indicate failure
        // This is done to account for "chain-breaking" errors where we cannot
        // read any more replies
        for (int i = 0; i < batchSize; i++) {
            updateCounts[i] = -3;
        }

        if (!supportsQueryBatchRequest && sqlMode_ == isQuery__) {
            throw new BatchUpdateException(agent_.logWriter_, 
                new ClientMessageId(SQLState.CANNOT_BATCH_QUERIES), updateCounts);
        }
        if (supportsQueryBatchRequest && sqlMode_ != isQuery__) {
            throw new BatchUpdateException(agent_.logWriter_, 
                new ClientMessageId(SQLState.QUERY_BATCH_ON_NON_QUERY_STATEMENT), 
                updateCounts);
        }

        resultSetList_ = null;


        if (sqlMode_ == isQuery__) {
            indexOfCurrentResultSet_ = -1; //reset ResultSetList
            resultSetList_ = new ResultSet[batchSize];
        }


        //save the current input set so it can be restored
        savedInputs = parameters_;

        agent_.beginBatchedWriteChain(this);
        boolean chainAutoCommit = connection_.willAutoCommitGenerateFlow() && isAutoCommittableStatement_;

        if (doWriteTimeout) {
            specialRegisterArrayList.set(0, TIMEOUT_STATEMENT + timeout_);
            writeSetSpecialRegister(specialRegisterArrayList);
            doWriteTimeout = false;
            timeoutSent = true;
        }

        for (int i = 0; i < batchSize; i++) {
            if (parameterMetaData_ != null) {
                parameterMetaData_.clientParamtertype_ = (int[]) parameterTypeList.get(i);
                parameters_ = (Object[]) batch_.get(i);
            }
            
            if (sqlMode_ != isCall__) {
                boolean outputExpected;
                try {
                    outputExpected = (resultSetMetaData_ != null && resultSetMetaData_.getColumnCount() > 0);
                } catch ( SQLException se ) {
                    throw new SqlException(se);
                }

// GemStone changes BEGIN
                materialPreparedStatement_.writeExecute_(section_,
                    parameterMetaData_, parameters_, numInputColumns,
                    outputExpected, true,
                    chainAutoCommit || (i != batchSize - 1)
                    /* more statements to chain */);
                /* (original code)
                writeExecute(section_,
                        parameterMetaData_,
                        parameters_,
                        numInputColumns,
                        outputExpected,
                        chainAutoCommit || (i != batchSize - 1));  // more statements to chain
                */
// GemStone changes END
            } else if (outputRegistered_) // make sure no output parameters are registered
            {
                throw new BatchUpdateException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.OUTPUT_PARAMS_NOT_ALLOWED),
                    updateCounts);
            } else {
                writeExecuteCall(false, // no output expected for batched CALLs
                        null, // no procedure name supplied for prepared CALLs
                        section_,
                        fetchSize_,
                        true, // suppress ResultSets for batch
                        resultSetType_,
                        parameterMetaData_,
                        parameters_);
            }
        }

        boolean commitSubstituted = false;
        if (chainAutoCommit) {
            // we have encountered an error in writing the execute, so do not
            // flow an autocommit
            if (agent_.accumulatedReadExceptions_ != null) {
                // currently, the only write exception we encounter is for
                // data truncation: SQLSTATE 01004, so we don't bother checking for this
                connection_.writeCommitSubstitute_();
                commitSubstituted = true;
            } else {
                // there is no write error, so flow the commit
                connection_.writeCommit();
            }
        }

        agent_.flowBatch(this, batchSize);

        if (timeoutSent) {
            readSetSpecialRegister(); // Read response to the EXCSQLSET
        }

        try {
            for (int i = 0; i < batchSize; i++) {
                agent_.setBatchedExceptionLabelIndex(i);
                parameters_ = (Object[]) batch_.get(i);
                if (sqlMode_ != isCall__) {
                    readExecute();
                } else {
                    readExecuteCall();
                }
                updateCounts[i] = updateCount_;

            }

            agent_.disableBatchedExceptionTracking(); // to prvent the following readCommit() from getting a batch label
            if (chainAutoCommit) {
                if (!commitSubstituted) {
                    connection_.readCommit();
                } else {
                    connection_.readCommitSubstitute_();
                }
            }
        }

                // for chain-breaking exception only, all read() methods do their own accumulation
                // this catches the entire accumulated chain, we need to be careful not to
                // reaccumulate it on the agent since the batch labels will be overwritten if
                // batch exception tracking is enabled.
        catch (SqlException e) { // for chain-breaking exception only
            chainBreaker = e;
            chainBreaker.setNextException(new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.BATCH_CHAIN_BREAKING_EXCEPTION)));
        }
        // We need to clear the batch before any exception is thrown from agent_.endBatchedReadChain().
// GemStone changes BEGIN
        //batch_.clear();
        //parameterTypeList = null;
// GemStone changes END
        // restore the saved input set, setting it to "current"
        parameters_ = savedInputs;

        agent_.endBatchedReadChain(updateCounts, chainBreaker);

        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "returning for batchSize=" + batchSize);
        }
        return updateCounts;

// GemStone changes BEGIN
        } catch (SqlException sqle) {
          failedUrls = connection_.handleFailover(failedUrls, sqle);
          // re-prepare the statement, if required, before retry
          preparePostFailover();
          doRetry = true;
        } catch (BatchUpdateException bue) {
          if (chainBreaker != null) {
            failedUrls = connection_.handleFailover(failedUrls, chainBreaker);
            // re-prepare the statement, if required, before retry
            preparePostFailover();
            doRetry = true;
          }
          else {
            throw bue;
          }
        } finally {
          if (!doRetry) {
            this.batch_.clear();
            this.parameterTypeList = null;
          }
        }
      }
     } finally {
       this.agent_.disableDisconnectEvent_ = origDisableDisconnect;
       if (SanityManager.TraceClientStatement) {
         final long ns = System.nanoTime();
         SanityManager.DEBUG_PRINT_COMPACT("PS_execBatch_E:" + batchSize, sql_,
             cid, ns, false, null);
       }
     }
// GemStone changes END
    }


    //------------------material layer event callbacks follow-----------------------

    boolean listenToUnitOfWork_ = false;

    public volatile boolean internalPreparedStatement;

    public void listenToUnitOfWork() {
        if (!listenToUnitOfWork_) {
            listenToUnitOfWork_ = true;
            connection_.CommitAndRollbackListeners_.put(this,null);
        }
    }

    public void completeLocalCommit(java.util.Iterator listenerIterator) {
        if (section_ != null) {
            openOnServer_ = false;
        }
        listenerIterator.remove();
        listenToUnitOfWork_ = false;
    }

    public void completeLocalRollback(java.util.Iterator listenerIterator) {
        if (section_ != null) {
            openOnServer_ = false;
        }
        listenerIterator.remove();
        listenToUnitOfWork_ = false;
    }

    //----------------------------internal use only helper methods----------------

    /**
     * Returns the name of the java.sql interface implemented by this class.
     * @return name of java.sql interface
     */
    protected String getJdbcStatementInterfaceName() {
        return "java.sql.PreparedStatement";
    }

    void checkForValidParameterIndex(int parameterIndex) throws SqlException {
        if (parameterMetaData_ == null) 
			throw new SqlException(agent_.logWriter_,
					new ClientMessageId(SQLState.NO_INPUT_PARAMETERS));

        if (parameterIndex < 1 || parameterIndex > parameterMetaData_.columns_) {
            throw new SqlException(agent_.logWriter_, 
                new ClientMessageId(SQLState.LANG_INVALID_PARAM_POSITION),
                new Integer(parameterIndex), 
                new Integer(parameterMetaData_.columns_));
        }
    }

    private void checkThatAllParametersAreSet() throws SqlException {
        if (parameterMetaData_ != null) {
            for (int i = 0; i < parameterMetaData_.columns_; i++) {
                if (!parameterSet_[i] && !parameterRegistered_[i]) {
// GemStone changes BEGIN
                    if (SanityManager.TraceClientStatementHA) {
                      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                          "unexpected missing parameter for 0-based index " + i);
                      StringBuilder paramSet = new StringBuilder();
                      paramSet.append('[');
                      for (int j = 0; j < this.parameterSet_.length; j++) {
                        if (j != 0) {
                          paramSet.append(',');
                        }
                        paramSet.append(this.parameterSet_[j]);
                      }
                      paramSet.append(']');
                      StringBuilder paramRegistered = new StringBuilder();
                      paramRegistered.append('[');
                      for (int j = 0; j < this.parameterRegistered_.length; j++) {
                        if (j != 0) {
                          paramRegistered.append(',');
                        }
                        paramRegistered.append(this.parameterRegistered_[j]);
                      }
                      paramRegistered.append(']');
                      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                          "full parameterSet=" + paramSet.toString()
                              + " full parameterRegistered="
                              + paramRegistered.toString());
                    }
                    throw new SqlException(agent_.logWriter_, 
                        new ClientMessageId(SQLState.LANG_MISSING_PARMS),
                        new Integer(i + 1),
                        this.sql_);
                    /* (original code)
                    throw new SqlException(agent_.logWriter_, 
                        new ClientMessageId(SQLState.LANG_MISSING_PARMS));
                    */
// GemStone changes END
                }
            }
        }
    }

    void checkForValidScale(int scale) throws SqlException {
// GemStone changes BEGIN
        if (scale < 0 || scale > FdocaConstants.NUMERIC_MAX_PRECISION) {
        /* (original code)
        if (scale < 0 || scale > 31) {
        */
// GemStone changes END
            throw new SqlException(agent_.logWriter_, 
                new ClientMessageId(SQLState.BAD_SCALE_VALUE),
                new Integer(scale));
        }
    }

    /**
     * Checks whether a data type is supported for
     * <code>setObject(int, Object, int)</code> and
     * <code>setObject(int, Object, int, int)</code>.
     *
     * @param dataType the data type to check
     * @exception SqlException if the type is not supported
     */
    private void checkForSupportedDataType(int dataType) throws SqlException {

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
        case java.sql.Types.ARRAY:
        case java.sql.Types.DATALINK:
        case JDBC40Translation.NCHAR:
        case JDBC40Translation.NCLOB:
        case JDBC40Translation.NVARCHAR:
        case JDBC40Translation.LONGNVARCHAR:
        case java.sql.Types.REF:
        case JDBC40Translation.ROWID:
        case JDBC40Translation.SQLXML:
        case java.sql.Types.STRUCT:
            throw new SqlException
                (agent_.logWriter_,
                 new ClientMessageId(SQLState.DATA_TYPE_NOT_SUPPORTED),
                 Types.getTypeString(dataType));
        }
    }

    void checkScaleForINOUTDecimal(int parameterIndex, int registerOutScale) throws SqlException {
        java.math.BigDecimal decimalInput = (java.math.BigDecimal) parameters_[parameterIndex - 1];
        if (decimalInput == null) {
            return;
        }
        // if the register out scale is greater than input scale, input scale is stored in sqlScale_
        if (registerOutScale > parameterMetaData_.sqlScale_[parameterIndex - 1]) {
            int inputLength = decimalInput.toString().length();
            int scaleDifference = registerOutScale - decimalInput.scale();
            if (decimalInput.signum() == -1) {
                inputLength--;
            }
            // if the new Decimal (with bigger scale) cannot fit into the DA
            if ((32 - scaleDifference) < inputLength) {
                // TODO - FINISH THIS
                throw new SqlException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.REGOUTPARAM_SCALE_DOESNT_MATCH_SETTER));
            }
            // if the new Decimal (with bigger scale) can fit
            else {
                parameters_[parameterIndex - 1] = decimalInput.setScale(registerOutScale);
                parameterMetaData_.sqlScale_[parameterIndex - 1] = registerOutScale;
            }
        }
        // if the register out sacle is smaller than input scale
        else if (registerOutScale < parameterMetaData_.sqlScale_[parameterIndex - 1]) {
            // remove 0's at the end of input
            try {
                // if the new Decimal (with smaller scale) can fit
                parameters_[parameterIndex - 1] = decimalInput.setScale(registerOutScale);
                parameterMetaData_.sqlScale_[parameterIndex - 1] = registerOutScale;
            } catch (ArithmeticException e) {
                // if the new Decimal (with smaller scale) cannot fit into the DA
                throw new SqlException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.REGOUTPARAM_SCALE_DOESNT_MATCH_SETTER));
            }
        }
    }

    /* (non-Javadoc)
     * @see com.pivotal.gemfirexd.internal.client.am.Statement#markClosed(boolean)
     */
    protected void markClosed(boolean removeListener){

        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "invoking markClosed"/*, new Throwable()*/);
        }
        if(pooledConnection_ != null)
            pooledConnection_.onStatementClose(this);
    	super.markClosed(removeListener);
    	
    	if (parameterMetaData_ != null) {
    	    if (!this.internalPreparedStatement) {
              parameterMetaData_.markClosed();
    	    }
            parameterMetaData_ = null;
        }
        sql_ = null;

        // Apparently, the JVM is not smart enough to traverse parameters_[] and null
        // out its members when the entire array is set to null (parameters_=null;).
        if (parameters_ != null) {
            if (SanityManager.TraceClientHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "markClosed arrays.fill for ps: " + this
                  + " and parameters: " + parameters_);
            }
            if (!this.internalPreparedStatement) {
              Arrays.fill(parameters_, null);
            }
        }
        else {
          if (SanityManager.TraceClientHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "markClosed arrays.fill for ps: " + this
                + " and null parameters");
          }
        }
        parameters_ = null;

        if(removeListener)
        	connection_.CommitAndRollbackListeners_.remove(this);
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
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the Java input stream that contains the ASCII parameter value
     * @throws SQLException if a database access error occurs or this method is
     *      called on a closed <code>PreparedStatement</code>
     */
    public void setAsciiStream(int parameterIndex, InputStream x)
            throws SQLException {
        synchronized (connection_) {
            if (agent_.loggingEnabled()) {
                agent_.logWriter_.traceEntry(this, "setAsciiStream",
                        parameterIndex, x);
            }
            try {
                checkTypeForSetAsciiStream(parameterIndex);
                parameterMetaData_.clientParamtertype_[parameterIndex - 1] = java.sql.Types.CLOB;
                if (x == null) {
                    setNull(parameterIndex, java.sql.Types.LONGVARCHAR);
                    return;
                }
                setInput(parameterIndex, new Clob(agent_, x, "ISO-8859-1",
                    this.parameterMetaData_.getColumnLabelX(parameterIndex) /* GemStoneAddition */));
            } catch (SqlException se) {
                throw se.getSQLException(agent_ /* GemStoneAddition */);
            }
        }
    }

    /**
     * Sets the designated parameter to the given input stream.
     * When a very large binary value is input to a <code>LONGVARBINARY</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.InputStream</code> object. The data will be read from the
     * stream as needed until end-of-file is reached.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @throws SQLException if a database access error occurs or this method is
     *      called on a closed <code>PreparedStatement</code>
     */
    public void setBinaryStream(int parameterIndex, InputStream x)
            throws SQLException {
        synchronized (connection_) {
            if (agent_.loggingEnabled()) {
                agent_.logWriter_.traceEntry(this, "setBinaryStream",
                        parameterIndex, x);
            }
            try {
                checkTypeForSetBinaryStream(parameterIndex);
                setBinaryStreamX(parameterIndex, x, -1);
            } catch (SqlException se) {
                throw se.getSQLException(agent_ /* GemStoneAddition */);
            }
        }
    }

    /**
     * Sets the designated parameter to a <code>Reader</code> object.
     *
     * @param parameterIndex index of the first parameter is 1, the second is 
     *      2, ...
     * @param reader an object that contains the data to set the parameter
     *      value to. 
     * @throws SQLException if parameterIndex does not correspond to a 
     *      parameter marker in the SQL statement; if a database access error
     *      occurs; this method is called on a closed PreparedStatementor if
     *      parameterIndex does not correspond to a parameter marker in the SQL
     *      statement
     */
    public void setClob(int parameterIndex, Reader reader)
            throws SQLException {
        synchronized (connection_) {
            if (agent_.loggingEnabled()) {
                agent_.logWriter_.traceEntry(this, "setClob",
                        parameterIndex, reader);
            }
            
            try {
                checkTypeForSetClob(parameterIndex);
                checkForClosedStatement();
// GemStone changes BEGIN
                setInput(parameterIndex, new Clob(agent_, reader,
                    this.parameterMetaData_.getColumnLabelX(parameterIndex)));
            } catch (SqlException se) {
                throw se.getSQLException(agent_);
            }
            /* (original code)
            } catch (SqlException se) {
                throw se.getSQLException();
            }
            setInput(parameterIndex, new Clob(agent_, reader));
            */
// GemStone changes END
        }
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
        synchronized (connection_) {
            if (agent_.loggingEnabled()) {
                agent_.logWriter_.traceEntry(this, "setClob",
                        parameterIndex, reader, new Long(length));
            }
            try {
                checkForClosedStatement();
// GemStone changes BEGIN
                if(length > Integer.MAX_VALUE)
                  throw new SqlException(agent_.logWriter_,
                      new ClientMessageId(SQLState.BLOB_TOO_LARGE_FOR_CLIENT),
                      new Long(length), new Integer(Integer.MAX_VALUE)).getSQLException(
                          agent_ /* GemStoneAddition */);
                else {
                  setInput(parameterIndex, new Clob(agent_, reader, (int)length,
                      this.parameterMetaData_.getColumnLabelX(parameterIndex)));
                }
            } catch (SqlException se) {
                throw se.getSQLException(agent_);
            }
            /* (original code)
            } catch (SqlException se) {
                throw se.getSQLException();
            }
            if(length > Integer.MAX_VALUE)
                throw new SqlException(agent_.logWriter_,
                    new ClientMessageId(SQLState.BLOB_TOO_LARGE_FOR_CLIENT),
                    new Long(length), new Integer(Integer.MAX_VALUE)).getSQLException();
            else
                setInput(parameterIndex, new Clob(agent_, reader, (int)length));
            */
// GemStone changes END
        }
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
        synchronized (connection_) {
            if (agent_.loggingEnabled()) {
                agent_.logWriter_.traceEntry(this, "setBlob", parameterIndex,
                        inputStream);
            }

            try {
                checkTypeForSetBlob(parameterIndex);
                setBinaryStreamX(parameterIndex, inputStream, -1);
            } catch (SqlException se) {
                throw se.getSQLException(agent_ /* GemStoneAddition */);
            }
        }
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
     *
     */
    
    public void setBlob(int parameterIndex, InputStream inputStream, long length)
    throws SQLException{
        synchronized (connection_) {
            if (agent_.loggingEnabled()) {
                agent_.logWriter_.traceEntry(this, "setBlob", parameterIndex,
                        inputStream, new Long(length));
            }
            if(length > Integer.MAX_VALUE)
                throw new SqlException(agent_.logWriter_,
                    new ClientMessageId(SQLState.BLOB_TOO_LARGE_FOR_CLIENT),
                    new Long(length), new Integer(Integer.MAX_VALUE)).getSQLException(
                        agent_ /* GemStoneAddition */);
            else {
                try {
                    checkTypeForSetBlob(parameterIndex);
                    setBinaryStreamX(parameterIndex, inputStream, (int)length);
                } catch(SqlException se){
                    throw se.getSQLException(agent_ /* GemStoneAddition */);
                }
            }
        }
    }    
 
        /*
         * Method calls onStatementError occurred on the 
         * BrokeredConnectionControl class after checking the 
         * SQLState of the SQLException thrown.
         * @param sqle SqlException
         * @throws java.sql.SQLException
         */
        
        private void checkStatementValidity(SqlException sqle)  
                                            throws SQLException {
            //check if the statement is already closed 
            //This might be caused because the connection associated
            //with this prepared statement has been closed marking 
            //its associated prepared statements also as
            //closed
            
            if(pooledConnection_!=null && isClosed()){
                pooledConnection_.onStatementErrorOccurred(this,
                    sqle.getSQLException(agent_ /* GemStoneAddition */));
            }
        }
    
    /**
     * PossibleTypes is information which is set of types.
     * A given type is evaluated as *possible* at checkType method if same type was found in the set.
     */
    private static class PossibleTypes{
        
        final private int[] possibleTypes;
        
        private PossibleTypes(int[] types){
            possibleTypes = types;
            Arrays.sort(possibleTypes);
        }
        
        /**
         * This is possibleTypes of variable which can be set by set method for generic scalar.
         */
        final public static PossibleTypes POSSIBLE_TYPES_IN_SET_GENERIC_SCALAR = 
            new PossibleTypes( new int[] { 
                java.sql.Types.BIGINT,
                java.sql.Types.LONGVARCHAR ,
                java.sql.Types.CHAR,
                java.sql.Types.DECIMAL,
                java.sql.Types.INTEGER,
                java.sql.Types.SMALLINT,
                java.sql.Types.REAL,
                java.sql.Types.DOUBLE,
                java.sql.Types.VARCHAR } );
        
        /**
         * This is possibleTypes of variable which can be set by setDate method.
         */
        final public static PossibleTypes POSSIBLE_TYPES_IN_SET_DATE = 
            new PossibleTypes( new int[] { 
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.VARCHAR,
                java.sql.Types.DATE,
                java.sql.Types.TIMESTAMP } );
        
        /**
         * This is possibleTypes of variable which can be set by setTime method.
         */
        final public static PossibleTypes POSSIBLE_TYPES_IN_SET_TIME = 
            new PossibleTypes( new int[] { 
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.VARCHAR,
                java.sql.Types.TIME } );
        
        /**
         * This is possibleTypes of variable which can be set by setTimestamp method.
         */
        final public static PossibleTypes POSSIBLE_TYPES_IN_SET_TIMESTAMP = 
            new PossibleTypes( new int[] { 
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.VARCHAR,
                java.sql.Types.DATE,
                java.sql.Types.TIME,
                java.sql.Types.TIMESTAMP } );
        
        /**
         * This is possibleTypes of variable which can be set by setString method.
         */
        final private static PossibleTypes POSSIBLE_TYPES_IN_SET_STRING = 
            new PossibleTypes( new int[] { 
                java.sql.Types.BIGINT,
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.DECIMAL,
                java.sql.Types.INTEGER,
                java.sql.Types.SMALLINT,
                java.sql.Types.REAL,
                java.sql.Types.DOUBLE,
                java.sql.Types.VARCHAR,
                java.sql.Types.DATE,
                java.sql.Types.TIME,
                java.sql.Types.TIMESTAMP,
                java.sql.Types.CLOB } );
        
        /**
         * This is possibleTypes of variable which can be set by setBytes method.
         */
        final public static PossibleTypes POSSIBLE_TYPES_IN_SET_BYTES = 
            new PossibleTypes( new int[] { 
                java.sql.Types.LONGVARBINARY,
                java.sql.Types.VARBINARY,
                java.sql.Types.BINARY,
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.VARCHAR,
                java.sql.Types.BLOB } );
        
        /**
         * This is possibleTypes of variable which can be set by setBinaryStream method.
         */
        final public static PossibleTypes POSSIBLE_TYPES_IN_SET_BINARYSTREAM = 
            new PossibleTypes( new int[] { 
                java.sql.Types.LONGVARBINARY,
                java.sql.Types.VARBINARY,
                java.sql.Types.BINARY,
                java.sql.Types.BLOB } );
        
        /**
         * This is possibleTypes of variable which can be set by setAsciiStream method.
         */
        final public static PossibleTypes POSSIBLE_TYPES_IN_SET_ASCIISTREAM = 
            new PossibleTypes( new int[]{ 
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.VARCHAR,
                java.sql.Types.CLOB } );
        
        /**
         * This is possibleTypes of variable which can be set by setCharacterStream method.
         */
        final public static PossibleTypes POSSIBLE_TYPES_IN_SET_CHARACTERSTREAM = 
            new PossibleTypes( new int[] { 
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.VARCHAR,
                java.sql.Types.CLOB } );
        
        /**
         * This is possibleTypes of variable which can be set by setBlob method.
         */
        final public static PossibleTypes POSSIBLE_TYPES_IN_SET_BLOB = 
            new PossibleTypes( new int[] { 
                java.sql.Types.BLOB } );
        
        /**
         * This is possibleTypes of variable which can be set by setClob method.
         */
        final public static PossibleTypes POSSIBLE_TYPES_IN_SET_CLOB = 
            new PossibleTypes( new int[] { 
                java.sql.Types.CLOB } );
        
        /**
         * This is possibleTypes of null value which can be assigned to generic scalar typed variable.
         */
        final public static PossibleTypes POSSIBLE_TYPES_FOR_GENERIC_SCALAR_NULL = 
            new PossibleTypes( new int[] { 
                java.sql.Types.BIT,
                java.sql.Types.TINYINT,
                java.sql.Types.BIGINT,
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.NUMERIC,
                java.sql.Types.DECIMAL,
                java.sql.Types.INTEGER,
                java.sql.Types.SMALLINT,
                java.sql.Types.FLOAT,
                java.sql.Types.REAL,
                java.sql.Types.DOUBLE,
                java.sql.Types.VARCHAR ,
// GemStone changes BEGIN
		//Fix for bug 41260
		java.sql.Types.BOOLEAN  
// GemStone changes END
            } );
        
        /**
         * This is possibleTypes of null value which can be assigned to generic character typed variable.
         */
        final public static PossibleTypes POSSIBLE_TYPES_FOR_GENERIC_CHARACTERS_NULL = 
            new PossibleTypes( new int[] {
                java.sql.Types.BIT,
                java.sql.Types.TINYINT,
                java.sql.Types.BIGINT,
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.NUMERIC,
                java.sql.Types.DECIMAL,
                java.sql.Types.INTEGER,
                java.sql.Types.SMALLINT,
                java.sql.Types.FLOAT,
                java.sql.Types.REAL,
                java.sql.Types.DOUBLE,
                java.sql.Types.VARCHAR,
                java.sql.Types.DATE,
                java.sql.Types.TIME,
                java.sql.Types.TIMESTAMP } );
        
        /**
         * This is possibleTypes of null value which can be assigned to VARBINARY typed variable.
         */
        final public static PossibleTypes POSSIBLE_TYPES_FOR_VARBINARY_NULL = 
            new PossibleTypes( new int[] { 
                java.sql.Types.VARBINARY,
                java.sql.Types.BINARY,
                java.sql.Types.LONGVARBINARY } );
        
        /**
         * This is possibleTypes of null value which can be assigned to BINARY typed variable.
         */
        final public static PossibleTypes POSSIBLE_TYPES_FOR_BINARY_NULL = 
            new PossibleTypes( new int[] { 
                java.sql.Types.VARBINARY,
                java.sql.Types.BINARY,
                java.sql.Types.LONGVARBINARY } );
        
        /**
         * This is possibleTypes of null value which can be assigned to LONGVARBINARY typed variable.
         */
        final public static PossibleTypes POSSIBLE_TYPES_FOR_LONGVARBINARY_NULL = 
            new PossibleTypes( new int[] {
                java.sql.Types.VARBINARY,
                java.sql.Types.BINARY,
                java.sql.Types.LONGVARBINARY } );
        
        /**
         * This is possibleTypes of null value which can be assigned to DATE typed variable.
         */
        final public static PossibleTypes POSSIBLE_TYPES_FOR_DATE_NULL = 
            new PossibleTypes( new int[] {
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.VARCHAR,
                java.sql.Types.DATE,
                java.sql.Types.TIMESTAMP } );
        
        /**
         * This is possibleTypes of null value which can be assigned to TIME typed variable.
         */
        final public static PossibleTypes POSSIBLE_TYPES_FOR_TIME_NULL = 
            new PossibleTypes( new int[] { 
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.VARCHAR,
                java.sql.Types.TIME,
                java.sql.Types.TIMESTAMP } );
        
        /**
         * This is possibleTypes of null value which can be assigned to TIMESTAMP typed variable.
         */
        final public static PossibleTypes POSSIBLE_TYPES_FOR_TIMESTAMP_NULL = 
            new PossibleTypes( new int[] {
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.VARCHAR,
                java.sql.Types.DATE,
                java.sql.Types.TIMESTAMP } );
        
        /**
         * This is possibleTypes of null value which can be assigned to CLOB typed variable.
         */
        final public static PossibleTypes POSSIBLE_TYPES_FOR_CLOB_NULL = 
            new PossibleTypes( new int[] { 
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.CHAR,
                java.sql.Types.VARCHAR,
                java.sql.Types.CLOB } );
        
        /**
         * This is possibleTypes of null value which can be assigned to BLOB typed variable.
         */
        final public static PossibleTypes POSSIBLE_TYPES_FOR_BLOB_NULL = 
            new PossibleTypes( new int[] {
                java.sql.Types.BLOB } );
        
        /**
         * This is possibleTypes of null value which can be assigned to other typed variable.
         */
        final public static PossibleTypes DEFAULT_POSSIBLE_TYPES_FOR_NULL = 
            new PossibleTypes( new int[] {
                java.sql.Types.BIT,
                java.sql.Types.TINYINT,
                java.sql.Types.BIGINT,
                java.sql.Types.LONGVARBINARY,
                java.sql.Types.VARBINARY,
                java.sql.Types.BINARY,
                java.sql.Types.LONGVARCHAR,
                java.sql.Types.NULL,
                java.sql.Types.CHAR,
                java.sql.Types.NUMERIC,
                java.sql.Types.DECIMAL,
                java.sql.Types.INTEGER,
                java.sql.Types.SMALLINT,
                java.sql.Types.FLOAT,
                java.sql.Types.REAL,
                java.sql.Types.DOUBLE, 
                java.sql.Types.VARCHAR,
                java.sql.Types.BOOLEAN,
                java.sql.Types.DATALINK,
                java.sql.Types.DATE,
                java.sql.Types.TIME,
                java.sql.Types.TIMESTAMP,
                java.sql.Types.OTHER,
                java.sql.Types.JAVA_OBJECT,
                java.sql.Types.DISTINCT,
                java.sql.Types.STRUCT,
                java.sql.Types.ARRAY,
                java.sql.Types.BLOB,
                java.sql.Types.CLOB,
                java.sql.Types.REF } );
        
        /**
         * This method return true if the type is possible.
         */
        boolean checkType(int type){
            
            if(SanityManager.DEBUG){
                
                for(int i = 0;
                    i < possibleTypes.length - 1;
                    i ++){
                    
                    SanityManager.ASSERT(possibleTypes[i] < possibleTypes[i + 1]);
                    
                }
            }
            
            return Arrays.binarySearch( possibleTypes,
                                        type ) >= 0;
            
        }
        
        static SqlException throw22005Exception( LogWriter logWriter, 
                                                 int valType,
                                                 int paramType, int parameterIndex)
            
            throws SqlException{
            
            throw new SqlException( logWriter,
                                    new ClientMessageId(SQLState.LANG_DATA_TYPE_GET_MISMATCH) ,
                                    new Object[]{ 
                                        Types.getTypeString(valType),
                                        Types.getTypeString(paramType), parameterIndex
                                    },
                                    (Throwable) null);
        }
        
        
        /**
         * This method return possibleTypes of null value in variable typed as typeOfVariable.
         */
        static PossibleTypes getPossibleTypesForNull(int typeOfVariable){
            
            switch(typeOfVariable){
                
            case java.sql.Types.SMALLINT:
                return POSSIBLE_TYPES_FOR_GENERIC_SCALAR_NULL;
                
            case java.sql.Types.INTEGER:
                return POSSIBLE_TYPES_FOR_GENERIC_SCALAR_NULL;
                
            case java.sql.Types.BIGINT:
                return POSSIBLE_TYPES_FOR_GENERIC_SCALAR_NULL;
                
            case java.sql.Types.REAL:
                return POSSIBLE_TYPES_FOR_GENERIC_SCALAR_NULL;
                
            case java.sql.Types.FLOAT:
                return POSSIBLE_TYPES_FOR_GENERIC_SCALAR_NULL;
                
            case java.sql.Types.DOUBLE:
                return POSSIBLE_TYPES_FOR_GENERIC_SCALAR_NULL;
                
            case java.sql.Types.DECIMAL:
                return POSSIBLE_TYPES_FOR_GENERIC_SCALAR_NULL;
                
            case java.sql.Types.CHAR:
                return POSSIBLE_TYPES_FOR_GENERIC_CHARACTERS_NULL;
                
            case java.sql.Types.VARCHAR:
                return POSSIBLE_TYPES_FOR_GENERIC_CHARACTERS_NULL;
                
            case java.sql.Types.LONGVARCHAR:
                return POSSIBLE_TYPES_FOR_GENERIC_CHARACTERS_NULL;
                
            case java.sql.Types.VARBINARY:
                return POSSIBLE_TYPES_FOR_VARBINARY_NULL;
                
            case java.sql.Types.BINARY:
                return POSSIBLE_TYPES_FOR_BINARY_NULL;
                
            case java.sql.Types.LONGVARBINARY:
                return POSSIBLE_TYPES_FOR_LONGVARBINARY_NULL;
                
            case java.sql.Types.DATE:
                return POSSIBLE_TYPES_FOR_DATE_NULL;
                
            case java.sql.Types.TIME:
                return POSSIBLE_TYPES_FOR_TIME_NULL;
                
            case java.sql.Types.TIMESTAMP:
                return POSSIBLE_TYPES_FOR_TIMESTAMP_NULL;
                
            case java.sql.Types.CLOB:
                return POSSIBLE_TYPES_FOR_CLOB_NULL;
                
            case java.sql.Types.BLOB:
                return POSSIBLE_TYPES_FOR_BLOB_NULL;
                
            }
        
            // as default, accept all type...
            return DEFAULT_POSSIBLE_TYPES_FOR_NULL;
        }
        
    }
}
