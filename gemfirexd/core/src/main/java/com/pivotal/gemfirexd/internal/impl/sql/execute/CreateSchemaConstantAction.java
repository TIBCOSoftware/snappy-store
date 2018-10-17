/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.execute.CreateSchemaConstantAction

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

package com.pivotal.gemfirexd.internal.impl.sql.execute;









/**
 *	This class describes actions that are ALWAYS performed for a
 *	CREATE SCHEMA Statement at Execution time.
 *
 */

// GemStone changes BEGIN
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.ServerGroupsTableAttribute;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDescriptorGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.TransactionManager;

public class CreateSchemaConstantAction extends DDLConstantAction
{
  /** the default server groups of this schema */
  private ServerGroupsTableAttribute defaultSGs = null;

  /**
   * Set the default server group for this schema.
   * 
   * @param defSGs the default server groups attributes
   */
  public void setDefaultSG(ServerGroupsTableAttribute defSGs) {
    this.defaultSGs = defSGs;
  }
/*
class CreateSchemaConstantAction extends DDLConstantAction
{
*/
//GemStone changes END

	private final String					aid;	// authorization id
	private final String					schemaName;
	

	// CONSTRUCTORS

	/**
	 * Make the ConstantAction for a CREATE SCHEMA statement.
	 * When executed, will set the default schema to the
	 * new schema if the setToDefault parameter is set to
	 * true.
	 *
	 *  @param schemaName	Name of table.
	 *  @param aid			Authorizaton id
	 */
// GemStone changes BEGIN
	public
// GemStone changes END
	CreateSchemaConstantAction(
								String			schemaName,
								String			aid)
	{
		this.schemaName = schemaName;
		this.aid = aid;
	}

	///////////////////////////////////////////////
	//
	// OBJECT SHADOWS
	//
	///////////////////////////////////////////////

	public	String	toString()
	{
		// Do not put this under SanityManager.DEBUG - it is needed for
		// error reporting.
		return "CREATE SCHEMA \"" + schemaName + '"';
	}

	// INTERFACE METHODS


	/**
	 *	This is the guts of the Execution-time logic for CREATE SCHEMA.
	 *
	 *	@see ConstantAction#executeConstantAction
	 *
	 * @exception StandardException		Thrown on failure
	 */
	public void	executeConstantAction( Activation activation )
						throws StandardException
	{
		TransactionController tc = activation.
			getLanguageConnectionContext().getTransactionExecute();

		executeConstantActionMinion(activation, tc);
	}

	/**
	 *	This is the guts of the Execution-time logic for CREATE SCHEMA.
	 *  This is variant is used when we to pass in a tc other than the default
	 *  used in executeConstantAction(Activation).
	 *
	 * @param activation current activation
	 * @param tc transaction controller
	 *
	 * @exception StandardException		Thrown on failure
	 */
	public void	executeConstantAction(Activation activation,
									  TransactionController tc)
			throws StandardException {

		executeConstantActionMinion(activation, tc);
	}

	private void executeConstantActionMinion(Activation activation,
											 TransactionController tc)
			throws StandardException {

		LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
		DataDictionary dd = lcc.getDataDictionary();
		DataDescriptorGenerator ddg = dd.getDataDescriptorGenerator();

		SchemaDescriptor sd = dd.getSchemaDescriptor(schemaName, lcc.getTransactionExecute(), false);

		//if the schema descriptor is an in-memory schema, we donot throw schema already exists exception for it.
		//This is to handle in-memory SESSION schema for temp tables
		// Special case INTERNAL schema
		if ((sd != null) && (sd.getUUID() != null))
		{
				throw StandardException.newException(SQLState.LANG_OBJECT_ALREADY_EXISTS, "Schema" , schemaName);
		}

		UUID tmpSchemaId = dd.getUUIDFactory().createUUID();

		/*
		** AID defaults to connection authorization if not 
		** specified in CREATE SCHEMA (if we had module
	 	** authorizations, that would be the first check
		** for default, then session aid).
		*/
		String thisAid = aid;
		if (thisAid == null)
		{
			thisAid = lcc.getAuthorizationId();
		}

		/*
		** Inform the data dictionary that we are about to write to it.
		** There are several calls to data dictionary "get" methods here
		** that might be done in "read" mode in the data dictionary, but
		** it seemed safer to do this whole operation in "write" mode.
		**
		** We tell the data dictionary we're done writing at the end of
		** the transaction.
		*/
		dd.startWriting(lcc);

		sd = ddg.newSchemaDescriptor(schemaName,
									thisAid,
									tmpSchemaId);
// GemStone changes BEGIN
    // Set the default server group for this schema
    sd.setDefaultServerGroups(this.defaultSGs != null ? this.defaultSGs
        .getServerGroupSet() : null);

    dd.addDescriptor(sd, null, DataDictionary.SYSSCHEMAS_CATALOG_NUM,
        false, tc);
    // Create schema in GemFireStore
    Misc.getMemStore().createSchemaRegion(this.schemaName,
        lcc.getTransactionExecute());
  }

  @Override
  public final boolean isReplayable() {
    return !GfxdConstants.SESSION_SCHEMA_NAME.equalsIgnoreCase(this.schemaName);
  }

  @Override
  public final String getSchemaName() {
    return this.schemaName;
  }

    /* (original derby code)
		dd.addDescriptor(sd, null, DataDictionary.SYSSCHEMAS_CATALOG_NUM, false, tc);
    */
// GemStone changes END
}
