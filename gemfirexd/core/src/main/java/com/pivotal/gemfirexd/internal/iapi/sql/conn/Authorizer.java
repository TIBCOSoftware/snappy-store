/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer

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

package com.pivotal.gemfirexd.internal.iapi.sql.conn;

import java.util.List;

import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.StatementPermission;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
/**
  The Authorizer verifies a connected user has the authorization 
  to perform a requested database operation using the current
  connection.

  <P>
  Today no object based authorization is supported.
  */
public interface Authorizer
{
	/** SQL write (insert,update,delete) operation */
	public static final int SQL_WRITE_OP = 0;
	/** SQL SELECT  operation */
	public static final int	SQL_SELECT_OP = 1;
	/** Any other SQL operation	*/
	public static final int	SQL_ARBITARY_OP = 2;
	/** SQL CALL/VALUE  operation */
	public static final int	SQL_CALL_OP = 3;
	/** SQL DDL operation */
	public static final int SQL_DDL_OP   = 4;
	/** database property write operation */
	public static final int PROPERTY_WRITE_OP = 5;
	/**  database jar write operation */	
	public static final int JAR_WRITE_OP = 6;
// GemStone changes BEGIN
	public static final int SQL_SKIP_OP = 7;
// GemStone changes END
	
	/* Privilege types for SQL standard (grant/revoke) permissions checking. */
	public static final int NULL_PRIV = -1;
	public static final int SELECT_PRIV = 0;
	public static final int UPDATE_PRIV = 1;
	public static final int REFERENCES_PRIV = 2;
	public static final int INSERT_PRIV = 3;
	public static final int DELETE_PRIV = 4;
	public static final int TRIGGER_PRIV = 5;
	public static final int EXECUTE_PRIV = 6;
// ALTER_PRIV added for #48314
	public static final int ALTER_PRIV = 7;
	public static final int PRIV_TYPE_COUNT = 8;

	/* Used to check who can create schemas or who can modify objects in schema */
	public static final int CREATE_SCHEMA_PRIV = 16;
	public static final int MODIFY_SCHEMA_PRIV = 17;
	public static final int DROP_SCHEMA_PRIV = 18;

    /* Check who can create and drop roles */
	public static final int CREATE_ROLE_PRIV = 19;
	public static final int DROP_ROLE_PRIV = 20;

	/**
	 * The system authorization ID is defined by the SQL2003 spec as the grantor
	 * of privileges to object owners.
	 */
	public static final String SYSTEM_AUTHORIZATION_ID = "_SYSTEM";

	/**
	 * The public authorization ID is defined by the SQL2003 spec as implying all users.
	 */
	public static final String PUBLIC_AUTHORIZATION_ID = "PUBLIC";

	/**
	  Verify the connected user is authorized to perform the requested
	  operation.

	  This variation should only be used with operations that do not use tables
	  or routines. If the operation involves tables or routines then use the
	  variation of the authorize method that takes an Activation parameter. The
	  activation holds the table, column, and routine lists.

	  @param operation the enumeration code for the requsted operation.

	  @exception StandardException Thrown if the operation is not allowed
	 */
	public void authorize( int operation) throws StandardException;
    
	/**
	  Verify the connected user is authorized to perform the requested
	  operation.

	  @param activation holds the list of tables, columns, and routines used.
	  @param operation the enumeration code for the requsted operation.

	  @exception StandardException Thrown if the operation is not allowed
	*/
	public void authorize(Activation activation, int operation)
				throws StandardException;

// GemStone changes BEGIN
	public void authorize(Activation activation, ExecPreparedStatement eps,
	    List<StatementPermission> perms, int operation) throws StandardException;
// GemStone changes END
    /**
	  Get the Authorization ID for this Authorizer.
	  */
   public String getAuthorizationId();

   /**
	 Get the readOnly status for this authorizer's connection.
	 */
   public boolean isReadOnlyConnection();

   /**
	 Set the readOnly status for this authorizer's connection.
	 @param on true means set the connection to read only mode,
	           false means set the connection to read wrte mode.
	 @param authorize true means to verify the caller has authority
	        to set the connection and false means do not check. 
	 @exception StandardException Oops not allowed.
	 */
   public void setReadOnlyConnection(boolean on, boolean authorize)
		 throws StandardException;

   /**
	 Refresh this authorizer to reflect a change in the database
	 permissions.
	 
	 @exception AuthorizerSessionException Connect permission gone.
	 @exception StandardException Oops.
	 */
   public void refresh() throws StandardException;  
}
