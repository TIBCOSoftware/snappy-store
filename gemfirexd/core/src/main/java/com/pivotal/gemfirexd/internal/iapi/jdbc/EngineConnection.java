/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.jdbc.EngineConnection

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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.EnumSet;

import com.gemstone.gemfire.cache.TransactionFlag;
import com.gemstone.gemfire.internal.cache.Checkpoint;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;


/**
 * Additional methods the embedded engine exposes on its Connection object
 * implementations. An internal api only, mainly for the network
 * server. Allows consistent interaction between EmbedConnections
 * and BrokeredConnections.
 * 
 */
public interface EngineConnection extends Connection {

    /**
     * Set the DRDA identifier for this connection.
     */
    public void setDrdaID(String drdaID);

    /** 
     * Set the transaction isolation level that will be used for the 
     * next prepare.  Used by network server to implement DB2 style 
     * isolation levels.
     * Note the passed in level using the Derby constants from
     * ExecutionContext and not the JDBC constants from java.sql.Connection.
     * @param level Isolation level to change to.  level is the DB2 level
     *               specified in the package names which happen to correspond
     *               to our internal levels. If 
     *               level == ExecutionContext.UNSPECIFIED_ISOLATION,
     *               the statement won't be prepared with an isolation level.
     * 
     * 
     */
    public void setPrepareIsolation(int level) throws SQLException;

    /**
     * Return prepare isolation 
     */
    public int getPrepareIsolation()
        throws SQLException;

    /**
     * Get the holdability of the connection. 
     * Identical to JDBC 3.0 method, to allow holdabilty
     * to be supported in JDK 1.3 by the network server,
     * e.g. when the client is jdk 1.4 or above.
     * Can be removed once JDK 1.3 is no longer supported.
     */
    public int getHoldability() throws SQLException;
    
    /**
     * Add a SQLWarning to this Connection object.
     * @param newWarning Warning to be added, will be chained to any
     * existing warnings.
     */
    public void addWarning(SQLWarning newWarning)
        throws SQLException;

    /**
    * Clear the HashTable of all entries.
    * Called when a commit or rollback of the transaction
    * happens.
    */
    public void clearLOBMapping() throws SQLException;

    /**
    * Get the LOB reference corresponding to the locator.
    * @param key the integer that represents the LOB locator value.
    * @return the LOB Object corresponding to this locator.
    */
    public Object getLOBMapping(long key) throws SQLException;

    /**
     * Obtain the name of the current schema, so that the NetworkServer can
     * use it for piggy-backing
     * @return the current schema name
     * @throws java.sql.SQLException
     */
    public String getCurrentSchemaName() throws SQLException;

    /**
     * Resets the connection before it is returned from a PooledConnection
     * to a new application request (wrapped by a BrokeredConnection).
     * <p>
     * Note that resetting the transaction isolation level is not performed as
     * part of this method. Temporary tables, IDENTITY_VAL_LOCAL and current
     * schema are reset.
     */
    public void resetFromPool() throws SQLException;
// GemStone changes BEGIN

    // prepareStatement extensions that accept ResultSet flags along-with
    // those for auto-generated keys

    public java.sql.PreparedStatement prepareStatement(
        String sql, int resultSetType,
        int resultSetConcurrency, int resultSetHoldability,
        int autoGeneratedKeys) throws SQLException;
    public java.sql.PreparedStatement prepareStatement(
        String sql, int resultSetType,
        int resultSetConcurrency, int resultSetHoldability,
        int[] columnIndexes) throws SQLException;
    public java.sql.PreparedStatement prepareStatement(
        String sql, int resultSetType,
        int resultSetConcurrency, int resultSetHoldability,
        String[] columnNames) throws SQLException;

    /** set the posdup flag for the next statement to be executed */
    public void setPossibleDuplicate(boolean isDup);

    /** set the streaming flag for the connection */
    public void setEnableStreaming(boolean enable);

    public LanguageConnectionContext getLanguageConnectionContext();

    /** rollback from network server session close */
    public void internalRollback() throws SQLException;

    /** close from network server session close */
    public void internalClose() throws SQLException;

    /** force close the connection without lock and ignoring errors */
    public void forceClose();

    /** get the finalizer for connection, if any */
    public FinalizeObject getAndClearFinalizer();

    /**
     * Return true if the connection is still active and false otherwise. Unlike
     * {@link #isClosed()} it avoids checking for database/monitor etc. for
     * validity and so is a much faster alternative where checking for latter is
     * not strictly required.
     */
    public boolean isActive();

    // from JDK 4.1 now also used internally
    public void setSchema(String schema) throws SQLException;

    public String getSchema() throws SQLException;

    public int addLOBMapping(Object lobReference) throws SQLException;

    public void removeLOBMapping(long key) throws SQLException;

    public boolean hasLOBs() throws SQLException;

    public Object getConnectionSynchronization() throws SQLException;

    public EnumSet<TransactionFlag> getTransactionFlags() throws SQLException;

    public void setTransactionIsolation(int level,
        EnumSet<TransactionFlag> transactionFlags) throws SQLException;

    /**
     * This execution sequence is send by the thin client to be used by the
     * server side as an implicit savepoint to use in the TXState so that client
     * failover can use this to rollback implicitly to a proper state till this
     * point if this point is the point which the client thinks that was the last
     * successful execution point. 
     */
    public void setExecutionSequence(int execSeq);

    public Checkpoint masqueradeAsTxn(TXId txid, int isolationLevel)
        throws SQLException;

    public void updateAffectedRegion(Bucket b);
// GemStone changes END
}
