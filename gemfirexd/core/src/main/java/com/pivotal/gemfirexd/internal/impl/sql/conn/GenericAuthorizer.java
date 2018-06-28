/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.conn.GenericAuthorizer

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

package com.pivotal.gemfirexd.internal.impl.sql.conn;

import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.catalog.types.RoutineAliasInfo;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.property.PersistentSet;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.StatementPermission;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;

import java.util.List;
import java.util.Iterator;

final class GenericAuthorizer
implements Authorizer
{
	//
	//Enumerations for user access levels.
	private static final int NO_ACCESS = 0;
	private static final int READ_ACCESS = 1;
	private static final int FULL_ACCESS = 2;
	
	//
	//Configurable userAccessLevel - derived from Database level
	//access control lists + database boot time controls.
	private int userAccessLevel;

	//
	//Connection's readOnly status
    boolean readOnlyConnection;

	private final LanguageConnectionContext lcc;
	
	private final String authorizationId; //the userName after parsing by IdUtil 
	
	GenericAuthorizer(String authorizationId, 
						     LanguageConnectionContext lcc)
		 throws StandardException
	{
		this.lcc = lcc;
		this.authorizationId = authorizationId;

		refresh();
	}

	/*
	  Return true if the connection must remain readOnly
	  */
	private boolean connectionMustRemainReadOnly()
	{
		if (lcc.getDatabase().isReadOnly() ||
			(userAccessLevel==READ_ACCESS))
			return true;
		else
			return false;
	}

	/**
	  Used for operations that do not involve tables or routines.
     
	  @see Authorizer#authorize
	  @exception StandardException Thrown if the operation is not allowed
	*/
	public void authorize( int operation) throws StandardException
	{
// GemStone changes BEGIN
	  authorize(null, null, null, operation);
	  /* (original code)
		authorize( (Activation) null, operation);
	  */
// GemStone changes END
	}

	/**
	  @see Authorizer#authorize
	  @exception StandardException Thrown if the operation is not allowed
	 */
// GemStone changes BEGIN
	public final void authorize(final Activation activation,
	    final int operation) throws StandardException {
	  authorize(activation, activation != null ? activation
	      .getPreparedStatement() : null, null, operation);
	}

	public final void authorize(final Activation activation,
	    ExecPreparedStatement ps, List<StatementPermission> perms,
	    final int operation) throws StandardException
	/* (original code)
	public void authorize( Activation activation, int operation) throws StandardException
	*/
	{
                // Skipping Authorization if not originated from here.
                if (this.lcc.isConnectionForRemote()) {
                  if (GemFireXDUtils.TraceAuthentication) {
                    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                        "skipping remote connection authorization for " +
                        "authorizationId=" + this.authorizationId);
                  }
                  return;
                }
                checkAccess();
                int sqlAllowed = RoutineAliasInfo.NO_SQL;
                StatementContext ctx = null;
                if (perms != null && perms.size() > 0) {
                  // direct call from GfxdSystemProcedures.authorizeColumnTableScan
                  sqlAllowed = RoutineAliasInfo.READS_SQL_DATA;
                }
                else if ((ctx = this.lcc.getStatementContext()) != null) {
		              sqlAllowed = ctx.getSQLAllowed();
                }
                /* (original code)
		int sqlAllowed = lcc.getStatementContext().getSQLAllowed();
		*/
// GemStone changes END

		switch (operation)
		{
		case Authorizer.SQL_ARBITARY_OP:
		case Authorizer.SQL_CALL_OP:
			if (sqlAllowed == RoutineAliasInfo.NO_SQL)
				throw externalRoutineException(operation, sqlAllowed);
			break;
		case Authorizer.SQL_SELECT_OP:
			if (sqlAllowed > RoutineAliasInfo.READS_SQL_DATA)
				throw externalRoutineException(operation, sqlAllowed);
			break;

		// SQL write operations
		case Authorizer.SQL_WRITE_OP:
		case Authorizer.PROPERTY_WRITE_OP:
			if (isReadOnlyConnection())
				throw StandardException.newException(SQLState.AUTH_WRITE_WITH_READ_ONLY_CONNECTION);
			if (sqlAllowed > RoutineAliasInfo.MODIFIES_SQL_DATA)
				throw externalRoutineException(operation, sqlAllowed);
			break;

		// SQL DDL operations
		case Authorizer.JAR_WRITE_OP:
		case Authorizer.SQL_DDL_OP:
 			if (isReadOnlyConnection())
				throw StandardException.newException(SQLState.AUTH_DDL_WITH_READ_ONLY_CONNECTION);

			if (sqlAllowed > RoutineAliasInfo.MODIFIES_SQL_DATA)
				throw externalRoutineException(operation, sqlAllowed);
			break;

		default:
// GemStone changes BEGIN
		  if (operation >= Authorizer.SQL_SKIP_OP) {
		    // no sqlAllowed checking
		    switch (operation - Authorizer.SQL_SKIP_OP) {
		      case Authorizer.SQL_SELECT_OP:
		        break;
		      // SQL write operations
		      case Authorizer.SQL_WRITE_OP:
		        if (isReadOnlyConnection()) {
		          throw StandardException.newException(SQLState
		              .AUTH_WRITE_WITH_READ_ONLY_CONNECTION);
		        }
		        break;
		      // SQL DDL operations
		      case Authorizer.SQL_DDL_OP:
		        if (isReadOnlyConnection()) {
		          throw StandardException.newException(SQLState
		              .AUTH_DDL_WITH_READ_ONLY_CONNECTION);
		        }
		        break;
		      default:
		        SanityManager.THROWASSERT("Bad operation code " + operation);
		    }
		  }
		  else
		    /* (original code)
			if (SanityManager.DEBUG)
		    */
// GemStone changes END
				SanityManager.THROWASSERT("Bad operation code "+operation);
		}
// GemStone changes BEGIN
	if (perms != null || (activation != null && (ps != null
	    || (ps = activation.getPreparedStatement()) != null))) {
	/* (original code)
        if( activation != null)
        {
        */
            DataDictionary dd = this.lcc.getDataDictionary();
            int ddMode = dd.startReading(this.lcc);
            boolean nestedTX = false;
            try {
              // check if ps is uptodate
              if (activation != null) activation.checkStatementValidity();
              List requiredPermissionsList = perms != null ? perms : ps.getRequiredPermissionsList();
              /*[originally]
                List requiredPermissionsList = activation.getPreparedStatement().getRequiredPermissionsList();
                DataDictionary dd = lcc.getDataDictionary();
               */

              if (GemFireXDUtils.TraceAuthentication) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                    "authorizing for authorizationId=" + authorizationId
                    + " activation " + activation
                    + " distributed member owner = "
                    + dd.getAuthorizationDatabaseOwner()
                    + " requiredPermissionList=" + requiredPermissionsList);
              }
// GemStone changes END
              
            // Database Owner can access any object. Ignore 
            // requiredPermissionsList for Database Owner
            if( requiredPermissionsList != null    && 
                !requiredPermissionsList.isEmpty() && 
				!authorizationId.equals(dd.getAuthorizationDatabaseOwner()))
            {
                 // GemStone changes BEGIN
                 // moved up before list of permissions.
                 // int ddMode = dd.startReading(lcc);
                 // GemStone changes END
              
                 /*
                  * The system may need to read the permission descriptor(s) 
                  * from the system table(s) if they are not available in the 
                  * permission cache.  So start an internal read-only nested 
                  * transaction for this.
                  * 
                  * The reason to use a nested transaction here is to not hold
                  * locks on system tables on a user transaction.  e.g.:  when
                  * attempting to revoke an user, the statement may time out
                  * since the user-to-be-revoked transaction may have acquired 
                  * shared locks on the permission system tables; hence, this
                  * may not be desirable.  
                  * 
                  * All locks acquired by StatementPermission object's check()
                  * method will be released when the system ends the nested 
                  * transaction.
                  * 
                  * In Derby, the locks from read nested transactions come from
                  * the same space as the parent transaction; hence, they do not
                  * conflict with parent locks.
                  */  
                lcc.beginNestedTransaction(true);
          // GemStone changes BEGIN
                nestedTX = true;
            	
                /* [originally] moved to outer.
                try 
                {
                    try 
                    {
                */                      
          // GemStone changes END
                    	// perform the permission checking
                        for (Iterator iter = requiredPermissionsList.iterator(); 
                            iter.hasNext();) 
                        {
                            StatementPermission perm = (StatementPermission)iter.next();
                            if (GemFireXDUtils.TraceAuthentication) {
                              SanityManager.DEBUG_PRINT(
                                  GfxdConstants.TRACE_AUTHENTICATION,
                                  "authorizationId=" + authorizationId
                                      + " StatementPermission=" + perm);
                            }
                            perm.check(lcc, authorizationId, false);
                        }
            }
// GemStone changes BEGIN
                /*[originally] kept only one finally block.
                    }
                    finally 
                    {
                        dd.doneReading(ddMode, lcc);
                    }
                }
                finally 
                {
                	// make sure we commit; otherwise, we will end up with 
                	// mismatch nested level in the language connection context.
                    lcc.commitNestedTransaction();
                }
                */
          } finally {
            dd.doneReading(ddMode, this.lcc);
            if (nestedTX) {
              this.lcc.commitNestedTransaction();
            }
          }
        }
// GemStone changes END
    }

	private static StandardException externalRoutineException(int operation, int sqlAllowed) {

		String sqlState;
		if (sqlAllowed == RoutineAliasInfo.READS_SQL_DATA)
			sqlState = SQLState.EXTERNAL_ROUTINE_NO_MODIFIES_SQL;
		else if (sqlAllowed == RoutineAliasInfo.CONTAINS_SQL)
		{
			switch (operation)
			{
			case Authorizer.SQL_WRITE_OP:
			case Authorizer.PROPERTY_WRITE_OP:
			case Authorizer.JAR_WRITE_OP:
			case Authorizer.SQL_DDL_OP:
				sqlState = SQLState.EXTERNAL_ROUTINE_NO_MODIFIES_SQL;
				break;
			default:
				sqlState = SQLState.EXTERNAL_ROUTINE_NO_READS_SQL;
				break;
			}
		}
		else
			sqlState = SQLState.EXTERNAL_ROUTINE_NO_SQL;

		return StandardException.newException(sqlState);
	}
	

	/**
	  @see Authorizer#getAuthorizationId
	  */
	public String getAuthorizationId()
	{
		return authorizationId;
	}

	private void getUserAccessLevel() throws StandardException
	{
		userAccessLevel = NO_ACCESS;
		if (userOnAccessList(com.pivotal.gemfirexd.Property.AUTHZ_FULL_ACCESS_USERS) ||
		    userOnAccessList(Attribute.AUTHZ_FULL_ACCESS_USERS))
			userAccessLevel = FULL_ACCESS;

		if (userAccessLevel == NO_ACCESS &&
			(userOnAccessList(com.pivotal.gemfirexd.Property.AUTHZ_READ_ONLY_ACCESS_USERS)
			    || userOnAccessList(Attribute.AUTHZ_READ_ONLY_ACCESS_USERS)))
			userAccessLevel = READ_ACCESS;

		if (userAccessLevel == NO_ACCESS)
			userAccessLevel = getDefaultAccessLevel();
	}

	private int getDefaultAccessLevel() throws StandardException
	{
		PersistentSet tc = lcc.getTransactionExecute();

		String modeS = (String)
			PropertyUtil.getServiceProperty(
									tc,
									com.pivotal.gemfirexd.Property.AUTHZ_DEFAULT_CONNECTION_MODE);
		if (modeS == null)
			return FULL_ACCESS;
		else if(StringUtil.SQLEqualsIgnoreCase(modeS, Property.NO_ACCESS))
			return NO_ACCESS;
		else if(StringUtil.SQLEqualsIgnoreCase(modeS, Property.READ_ONLY_ACCESS))
			return READ_ACCESS;
		else if(StringUtil.SQLEqualsIgnoreCase(modeS, Property.FULL_ACCESS))
			return FULL_ACCESS;
		else
		{
			if (SanityManager.DEBUG)
				SanityManager.THROWASSERT("Invalid value for property "+
										  com.pivotal.gemfirexd.Property.AUTHZ_DEFAULT_CONNECTION_MODE+
										  " "+
										  modeS);
 			return FULL_ACCESS;
		}
	}

	private boolean userOnAccessList(String listName) throws StandardException
	{
		PersistentSet tc = lcc.getTransactionExecute();
		String listS = (String)
			PropertyUtil.getServiceProperty(tc, listName);
		return IdUtil.idOnList(authorizationId,listS); 
	}

	/**
	  @see Authorizer#isReadOnlyConnection
	 */
	public boolean isReadOnlyConnection()
	{
		return readOnlyConnection;
	}

	/**
	  @see Authorizer#isReadOnlyConnection
	  @exception StandardException Thrown if the operation is not allowed
	 */
	public void setReadOnlyConnection(boolean on, boolean authorize)
		 throws StandardException
	{
		if (authorize && !on) {
			if (connectionMustRemainReadOnly())
				throw StandardException.newException(SQLState.AUTH_CANNOT_SET_READ_WRITE);
		}
		readOnlyConnection = on;
	}

	/**
	  @see Authorizer#refresh
	  @exception StandardException Thrown if the operation is not allowed
	  */
	public void refresh() throws StandardException
	{
		getUserAccessLevel();
		checkAccess();
	}

	private void checkAccess() throws StandardException {
		readOnlyConnection = connectionMustRemainReadOnly();

		// Is a connection allowed.
		if (userAccessLevel == NO_ACCESS)
			throw StandardException.newException(SQLState.AUTH_DATABASE_CONNECTION_REFUSED);
	}
}
