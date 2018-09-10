/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.execute.CreateTableConstantAction

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












import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Properties;

// GemStone changes BEGIN
import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.CustomEvictionAttributes;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EvictionAlgorithm;
import com.gemstone.gemfire.cache.EvictionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.internal.cache.EvictionAttributesImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.management.GfxdResourceEvent;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDescriptorGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecIndexRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.impl.sql.compile.CreateTableNode;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

// GemStone changes END
/**
 *	This class  describes actions that are ALWAYS performed for a
 *	CREATE TABLE Statement at Execution time.
 *
 */

// GemStone changes BEGIN
//made public
public final
// GemStone changes END
class CreateTableConstantAction extends DDLConstantAction
{

	private char					lockGranularity;
	private boolean					onCommitDeleteRows; //If true, on commit delete rows else on commit preserve rows of temporary table.
	private boolean					onRollbackDeleteRows; //If true, on rollback delete rows from temp table if it was logically modified in that UOW. true is the only supported value
	private String					tableName;
	private String					schemaName;
	private int						tableType;
	private ColumnInfo[]			columnInfo;
	private CreateConstraintConstantAction[]	constraintActions;
	private Properties				properties;
	//used only when queryExpression is not null i.e. for 'create table as select *' query 
	private String                  generatedSqlTextForCTAS;  

	/**
	 *	Make the ConstantAction for a CREATE TABLE statement.
	 *
	 *  @param schemaName	name for the schema that table lives in.
	 * @param tableName	Name of table.
	 * @param tableType	Type of table (e.g., BASE, global temporary table).
	 * @param columnInfo	Information on all the columns in the table.
	 *		 (REMIND tableDescriptor ignored)
	 * @param constraintActions	CreateConstraintConstantAction[] for constraints
	 * @param properties	Optional table properties
	 * @param lockGranularity	The lock granularity.
	 * @param onCommitDeleteRows	If true, on commit delete rows else on commit preserve rows of temporary table.
	 * @param onRollbackDeleteRows	If true, on rollback, delete rows from temp tables which were logically modified. true is the only supported value
	 * @param generatedSqlTextForCTAS If the query is of the from 'create table t1 as select * from t2' this will contain a SQL text 
	 *                                containing actual column info instead of the select clause 
	 */
	CreateTableConstantAction(
								String			schemaName,
								String			tableName,
								int				tableType,
								ColumnInfo[]	columnInfo,
								CreateConstraintConstantAction[] constraintActions,
								Properties		properties,
								char			lockGranularity,
								boolean			onCommitDeleteRows,
								boolean			onRollbackDeleteRows, 
								String          generatedSqlTextForCTAS)
	{
		this.schemaName = schemaName;
		this.tableName = tableName;
		this.tableType = tableType;
		this.columnInfo = columnInfo;
		// GemStone changes BEGIN
    // no constraints will be executed in the loner mode
    if (Misc.getMemStoreBooting().isHadoopGfxdLonerMode()
        && constraintActions != null) {
   // execute only the primary key constraints. Having the primary key
      //constraint allows us to parse the key of an entry
      ArrayList<CreateConstraintConstantAction> pkConstraints = new ArrayList<CreateConstraintConstantAction>(1);
      for(CreateConstraintConstantAction constraint : constraintActions) {
        if(constraint.getConstraintType() == DataDictionary.PRIMARYKEY_CONSTRAINT) {
          pkConstraints.add(constraint);
        }
      }
      this.constraintActions = pkConstraints.toArray(new CreateConstraintConstantAction[pkConstraints.size()]);
    } else {
      this.constraintActions = constraintActions;
    }
    // GemStone changes END
		this.properties = properties;
		this.lockGranularity = lockGranularity;
		this.onCommitDeleteRows = onCommitDeleteRows;
		this.onRollbackDeleteRows = onRollbackDeleteRows;
		this.generatedSqlTextForCTAS = generatedSqlTextForCTAS;

		if (SanityManager.DEBUG)
		{
			//GemStone changes BEGIN
			/* original code
			if (tableType == TableDescriptor.BASE_TABLE_TYPE && lockGranularity != TableDescriptor.TABLE_LOCK_GRANULARITY &&
			 */
			if ((tableType == TableDescriptor.BASE_TABLE_TYPE || tableType == TableDescriptor.COLUMN_TABLE_TYPE) && lockGranularity != TableDescriptor.TABLE_LOCK_GRANULARITY &&
			//GemStone changes END
				lockGranularity != TableDescriptor.ROW_LOCK_GRANULARITY)
			{
				SanityManager.THROWASSERT(
					"Unexpected value for lockGranularity = " + lockGranularity);
			}
			if (tableType == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE && onRollbackDeleteRows == false)
			{
				SanityManager.THROWASSERT(
					"Unexpected value for onRollbackDeleteRows = " + onRollbackDeleteRows);
			}
			SanityManager.ASSERT(schemaName != null, "SchemaName is null");
		}
	}

	// OBJECT METHODS

	public	String	toString()
	{
		if (tableType == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE)
			return constructToString("DECLARE GLOBAL TEMPORARY TABLE ", tableName);
		else
			return constructToString("CREATE TABLE ", tableName);
	}

	// INTERFACE METHODS


	/**
	 *	This is the guts of the Execution-time logic for CREATE TABLE.
	 *
	 *	@see ConstantAction#executeConstantAction
	 *
	 * @exception StandardException		Thrown on failure
	 */
	public void	executeConstantAction( Activation activation )
		throws StandardException
	{
		TableDescriptor 			td;
		UUID 						toid;
		SchemaDescriptor			schemaDescriptor;
		ColumnDescriptor			columnDescriptor;
		ExecRow						template;

		LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
		DataDictionary dd = lcc.getDataDictionary();
		DependencyManager dm = dd.getDependencyManager();
		TransactionController tc = lcc.getTransactionExecute();

		/* Mark the activation as being for create table */
		activation.setForCreateTable();

        // setup for create conglomerate call:
        //   o create row template to tell the store what type of rows this
        //     table holds.
        //   o create array of collation id's to tell collation id of each
        //     column in table.
		template            = RowUtil.getEmptyValueRow(columnInfo.length, lcc);
        int[] collation_ids = new int[columnInfo.length];

		for (int ix = 0; ix < columnInfo.length; ix++)
		{
            ColumnInfo  col_info = columnInfo[ix];

            // Get a template value for each column

			if (col_info.defaultValue != null)
            {
                /* If there is a default value, use it, otherwise use null */
				template.setColumn(ix + 1, col_info.defaultValue);
            }
			else
            {
				template.setColumn(ix + 1, col_info.dataType.getNull());
            }

            // get collation info for each column.

            collation_ids[ix] = col_info.dataType.getCollationType();

            // Gemfire changes BEGIN
            // Do not allow CHAR(0) to get through here
            // (CREATE TABLE ... AS SELECT '' FROM X) causes a CHAR(0) column to be created
            if ((col_info.dataType.getTypeId() == TypeId.CHAR_ID) && (col_info.dataType.getMaximumWidth()==0))
            {
            	// This column has a max width of zero! Throw error.
            	throw StandardException.newException(SQLState.LANG_INVALID_COLUMN_LENGTH, "0");
            }
            //Gemfire changes END
		}

// GemStone changes BEGIN
    if (this.properties == null) {
      this.properties = new Properties();
    }
    this.properties.put(DataDictionary.MODULE, dd);
    // get the DistributionDescriptor
    final DistributionDescriptor distributionDesc = (DistributionDescriptor)
        this.properties.get(GfxdConstants.DISTRIBUTION_DESCRIPTOR_KEY);
    if (this.tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE) {
      assert distributionDesc != null;
      dd.startWriting(lcc);
    }
    else {
      properties.put(GfxdConstants.PROPERTY_SCHEMA_NAME, this.schemaName);
      properties.put(GfxdConstants.PROPERTY_TABLE_NAME, tableName);
    }

    final boolean isHadoopLoner = Misc.getMemStoreBooting()
        .isHadoopGfxdLonerMode();
    if (isHadoopLoner) {
      // remove all the region attributes and only keep the neccessary ones for the loner mode
      RegionAttributes<?, ?> regionAttributes = (RegionAttributes<?, ?>)properties
          .get(GfxdConstants.REGION_ATTRIBUTES_KEY);
      AttributesFactory attribFactory = new AttributesFactory();
      attribFactory.setHDFSStoreName(regionAttributes.getHDFSStoreName());
      // remove local persistence policy
      DataPolicy dp = regionAttributes.getDataPolicy();
      if (dp == DataPolicy.HDFS_PERSISTENT_PARTITION) {
        dp = DataPolicy.HDFS_PARTITION;
      }
      attribFactory.setDataPolicy(dp);
      // if overflow is enabled, disable it. 
      EvictionAttributes ea = new EvictionAttributesImpl().
          setAlgorithm(EvictionAlgorithm.LRU_HEAP);
      attribFactory.setEvictionAttributes(ea);
      PartitionAttributes prAttributes = regionAttributes.getPartitionAttributes();
      if(prAttributes != null) {
        PartitionAttributesFactory paf = new PartitionAttributesFactory(prAttributes);
        paf.setColocatedWith(null);
        attribFactory.setPartitionAttributes(paf.create());
      }
      RegionAttributes<?, ?> updatedregionAttributes = attribFactory.create();
      properties.put(GfxdConstants.REGION_ATTRIBUTES_KEY, updatedregionAttributes);
    }

    RegionAttributes<?, ?> regionAttributes = (RegionAttributes<?, ?>)properties
        .get(GfxdConstants.REGION_ATTRIBUTES_KEY);
    if (regionAttributes != null) {
      DataPolicy dp = regionAttributes.getDataPolicy();
      if (!isHadoopLoner && dp == DataPolicy.HDFS_PARTITION
          && !distributionDesc.getPersistence()) {
        activation.addWarning(StandardException.newWarning(
            SQLState.HDFS_REGION_NOT_PERSISTENT, tableName));
      }
    }
// GemStone changes END
		/* create the conglomerate to hold the table's rows
		 * RESOLVE - If we ever have a conglomerate creator
		 * that lets us specify the conglomerate number then
		 * we will need to handle it here.
		 */
		long conglomId = tc.createConglomerate(
				"heap", // we're requesting a heap conglomerate
				template.getRowArray(), // row template
				null, //column sort order - not required for heap
                collation_ids,
				properties, // properties
				tableType == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE ?
                    (TransactionController.IS_TEMPORARY |
                     TransactionController.IS_KEPT) :
                        TransactionController.IS_DEFAULT);

		/*
		** Inform the data dictionary that we are about to write to it.
		** There are several calls to data dictionary "get" methods here
		** that might be done in "read" mode in the data dictionary, but
		** it seemed safer to do this whole operation in "write" mode.
		**
		** We tell the data dictionary we're done writing at the end of
		** the transaction.
		*/
// GemStone changes BEGIN
		// take DD lock before createConglomerate since former will
		// take table lock
		/*
		if ( tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE )
			dd.startWriting(lcc);
		*/
// GemStone changes END

		SchemaDescriptor sd;
		if (tableType == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE)
			sd = dd.getSchemaDescriptor(schemaName, tc, true);
		else
			sd = getSchemaDescriptorForCreate(dd, activation, schemaName);

		//
		// Create a new table descriptor.
		//
		DataDescriptorGenerator ddg = dd.getDataDescriptorGenerator();

		if ( tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE )
		{
			td = ddg.newTableDescriptor(tableName, sd, tableType, lockGranularity,
					TableDescriptor.DEFAULT_ROW_LEVEL_SECURITY_ENABLED);
// GemStone changes BEGIN
			// set the DistributionDescriptor before calling dd.addDescriptor()
			td.setDistributionDescriptor(distributionDesc);
// GemStone changes END
			dd.addDescriptor(td, sd, DataDictionary.SYSTABLES_CATALOG_NUM, false, tc);
		} else
		{
			td = ddg.newTableDescriptor(tableName, sd, tableType, onCommitDeleteRows, onRollbackDeleteRows);
// GemStone changes BEGIN
			// set the DistributionDescriptor before calling dd.addDescriptor()
			td.setDistributionDescriptor(distributionDesc);
// GemStone changes END
			td.setUUID(dd.getUUIDFactory().createUUID());
		}
		toid = td.getUUID();

		// Save the TableDescriptor off in the Activation
		activation.setDDLTableDescriptor(td);


		/* NOTE: We must write the columns out to the system
		 * tables before any of the conglomerates, including
		 * the heap, since we read the columns before the
		 * conglomerates when building a TableDescriptor.
		 * This will hopefully reduce the probability of
		 * a deadlock involving those system tables.
		 */

		// for each column, stuff system.column
		int index = 1;

		ColumnDescriptor[] cdlArray = new ColumnDescriptor[columnInfo.length];
		for (int ix = 0; ix < columnInfo.length; ix++)
		{
			UUID defaultUUID = columnInfo[ix].newDefaultUUID;

			/* Generate a UUID for the default, if one exists
			 * and there is no default id yet.
			 */
			if (columnInfo[ix].defaultInfo != null &&
				defaultUUID == null)
			{
				defaultUUID = dd.getUUIDFactory().createUUID();
			}

			if (columnInfo[ix].autoincInc != 0)//dealing with autoinc column
// GemStone changes BEGIN
			{
			  // add warning for non-default increment/start since
			  // it not implemented
			  /*
			  if (columnInfo[ix].hasAutoIncStart) {
			    activation.addWarning(StandardException.newWarning(
                                SQLState.AUTOINCREMENT_GIVEN_START_NOT_SUPPORTED,
                                columnInfo[ix].autoincStart, td.getQualifiedName()));
			  }
			  */
			  if (columnInfo[ix].hasAutoIncInc && !columnInfo[ix].isGeneratedByDefault) {
			    activation.addWarning(StandardException.newWarning(
			        SQLState.AUTOINCREMENT_GIVEN_INCREMENT_NOT_SUPPORTED,
			        columnInfo[ix].autoincInc, td.getQualifiedName()));
			  }
// GemStone changes END
			columnDescriptor = new ColumnDescriptor(
				                   columnInfo[ix].name,
								   index++,
								   columnInfo[ix].dataType,
								   columnInfo[ix].defaultValue,
								   columnInfo[ix].defaultInfo,
								   td,
								   defaultUUID,
								   columnInfo[ix].autoincStart,
								   columnInfo[ix].autoincInc,
								   columnInfo[ix].autoinc_create_or_modify_Start_Increment,
								   columnInfo[ix].isGeneratedByDefault
							   );
// GemStone changes BEGIN
			}
// GemStone changes END
			else
				columnDescriptor = new ColumnDescriptor(
		                   columnInfo[ix].name,
						   index++,
						   columnInfo[ix].dataType,
						   columnInfo[ix].defaultValue,
						   columnInfo[ix].defaultInfo,
						   td,
						   defaultUUID,
						   columnInfo[ix].autoincStart,
						   columnInfo[ix].autoincInc,
						   columnInfo[ix].isGeneratedByDefault
					   );

			cdlArray[ix] = columnDescriptor;
		}

		if ( tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE )
		{
			dd.addDescriptorArray(cdlArray, td,
							  DataDictionary.SYSCOLUMNS_CATALOG_NUM,
							  false, tc);
		}

		// now add the column descriptors to the table.
		ColumnDescriptorList cdl = td.getColumnDescriptorList();
		for (int i = 0; i < cdlArray.length; i++)
			cdl.add(cdlArray[i]);

		//
		// Create a conglomerate desciptor with the conglomId filled in and
		// add it.
		//
		// RESOLVE: Get information from the conglomerate descriptor which
		//          was provided.
		//
		ConglomerateDescriptor cgd =
                        //(original code) ddg.newConglomerateDescriptor(conglomId, null, false, null, false, null, toid,
			ddg.newConglomerateDescriptor(conglomId, schemaName+"."+tableName, false, null, false, null, toid,
										  sd.getUUID());
		if ( tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE )
		{
			dd.addDescriptor(cgd, sd, DataDictionary.SYSCONGLOMERATES_CATALOG_NUM,
						 false, tc);
		}

		// add the newly added conglomerate to the table descriptor
		ConglomerateDescriptorList conglomList = td.getConglomerateDescriptorList();
		conglomList.add(cgd);

// GemStone changes BEGIN
    if ( tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE ) {
      // resolve the column positions
      distributionDesc.resolveColumnPositions(td);
      // set extra table info into the container
      ExtraTableInfo.newExtraTableInfo(dd, td, lcc.getContextManager(),
          1 /* schema version starts from 1 */, null, true);
    }

    boolean hasFk = false;
// Gemstone changes -End


		/* Create any constraints */
		if (constraintActions != null)
		{
			/*
			** Do everything but FK constraints first,
			** then FK constraints on 2nd pass.
			*/
			for (int conIndex = 0; conIndex < constraintActions.length; conIndex++)
			{
				// skip fks
				if (!constraintActions[conIndex].isForeignKeyConstraint())
				{
// GemStone changes BEGIN
				  constraintActions[conIndex].setSkipPopulatingIndexes(true);
// GemStone changes END
					constraintActions[conIndex].executeConstantAction(activation);
				}
			}


			for (int conIndex = 0; conIndex < constraintActions.length; conIndex++)
			{
				// only foreign keys
				if (constraintActions[conIndex].isForeignKeyConstraint())
				{
// GemStone changes BEGIN
				  hasFk = true;
				  constraintActions[conIndex].setSkipPopulatingIndexes(true);
// GemStone changes END
					constraintActions[conIndex].executeConstantAction(activation);
				}
			}
		}

        //
        // The table itself can depend on the user defined types of its columns.
        //
        adjustUDTDependencies( lcc, dd, td, columnInfo, false );

		if ( tableType == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE )
		{
			lcc.addDeclaredGlobalTempTable(td, conglomId);
		}

// GemStone changes BEGIN

    if (tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE) {
      // setup the various properties of GemFireContainer;
      // this also sets up the GfxdIndexManager for global indexes while
      // local indexes are attached at commit time
      final GemFireTransaction tran = (GemFireTransaction)tc;
      final GemFireContainer container = GemFireContainer.getGemFireContainer(
          td, tran);
      final GfxdIndexManager indexManager = container.setup(td, dd, lcc, tran,
          hasFk, distributionDesc, activation, cdl);
      
      //defect #49367. FK constraint not supported with custom eviction for cheetah
      CustomEvictionAttributes customEvictionAttrs = container.getRegionAttributes().getCustomEvictionAttributes();
      if (customEvictionAttrs != null && hasFk) {
        throw StandardException.newException(SQLState.FOREIGN_KEY_CONSTRAINT_NOT_SUPPORTED_WITH_EVICTION);
      }

      // check if this table is colocated with another
      final PartitionAttributes<?, ?> pattrs;
      final String colocatedWith;
      if (container.getRegion() != null
          && (pattrs = container.getRegion().getPartitionAttributes()) != null
          && (colocatedWith = pattrs.getColocatedWith()) != null
          && colocatedWith.length() > 0) {
        this.colocatedWithTable = Misc
            .getFullTableNameFromRegionPath(colocatedWith);
      }
      // load the local indexes after region population to avoid missing data
      // loaded from disk (#42308, doing that via onEvent route is difficult
      // since LocalRegion is not available there and we will need dummy
      // EntryEvents)
      if (GemFireXDUtils.TraceIndex) {
        GfxdIndexManager.traceIndex("CreateTable::executeConstantAction "
            + "indexManager=%s, constraintAction=%s and skipRegionInit=%s "
            + "for table=%s", indexManager, constraintActions,
            lcc.skipRegionInitialization(), td.getQualifiedName());
      }
      if (indexManager != null && this.constraintActions != null) {
        if (lcc.skipRegionInitialization()) {
          // only lock for container GII and also refresh the index list
          // at commit when releasing the lock
          indexManager.invalidateFor(lcc);
        }
        else {
          boolean lockedForIndexes = false;
          Collection<LoadIndexData> loadData;
          for (CreateConstraintConstantAction action : this.constraintActions) {
            loadData = action.getLoadIndexData();
            if (loadData != null) {
              for (LoadIndexData load : loadData) {
                if (!lockedForIndexes) {
                  lockedForIndexes = true;

                  // next lock for container GII and also refresh the index list
                  // at commit when releasing the lock
                  indexManager.invalidateFor(lcc);
                }
                load.loadIndexConglomerate(lcc, tc, td, indexManager);
              }
              loadData.clear();
            }
          }
        }
      }
      // add dependency of this statement since GFXD properties like server
      // groups can change for a VM so its best to recompile after drop table
      dm.addDependency(activation.getPreparedStatement(), td, lcc
          .getContextManager());

      // Change added in r42445

      // skip these warnings on accessor (aka peer clients)
      if (!container.isAccessorForRegion()) {

      final GemFireCacheImpl cache = Misc.getGemFireCache();
      for (String id : container.getRegionAttributes().getGatewaySenderIds()) {
        if (cache.getGatewaySender(id) == null) {
          StringBuilder sb = new StringBuilder();
          for (com.gemstone.gemfire.cache.wan.GatewaySender s : cache
              .getAllGatewaySenders()) {
            sb.append(s.getId()).append(",");
          }
          activation.addWarning(StandardException.newWarning(
              SQLState.LANG_NO_LOCAL_GATEWAYSENDER_ASYNCEVENTLISTENER,
              new Object[] { id, container.getQualifiedTableName(),
                  "GatewaySenders", sb.toString() }, null));
        }
      }
      for (Object id : container.getRegionAttributes().getAsyncEventQueueIds()) {
        if (cache.getAsyncEventQueue((String)id) == null) {
          StringBuilder sb = new StringBuilder();
          for (com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue q : cache
              .getAsyncEventQueues()) {
            sb.append(q.getId()).append(",");
          }
          activation.addWarning(StandardException.newWarning(
              SQLState.LANG_NO_LOCAL_GATEWAYSENDER_ASYNCEVENTLISTENER,
              new Object[] { id, container.getQualifiedTableName(),
                  "AsyncEventListeners", sb.toString() }, null));
        }
      }

      }

      // Fix for #48445
      if (!container.getRegionAttributes().getGatewaySenderIds().isEmpty()) {
        Iterator<ColumnDescriptor> itr = td.getColumnDescriptorList()
            .iterator();
        while (itr.hasNext()) {
          ColumnDescriptor cd = itr.next();
          if (cd.isAutoincAlways()
              && (cd.getType().getJDBCTypeId() == Types.INTEGER)) {
            throw StandardException.newException(SQLState.LANG_AI_INVALID_TYPE_WITH_GATEWAYSENDER,
                cd.getColumnName());
          }
        }
      }

      // Create Table MBean now if management is not disabled
      // NOTE: We can(should?) actually use the DistributionDescriptor by
      // passing it to handleEvent here rather than taking it later via
      // GemFireContainer -> ExtraTableInfo -> TableDescriptor
      if (container.isApplicationTable()) {
        GfxdManagementService.handleEvent(GfxdResourceEvent.TABLE__CREATE, new Object[] {container});
      }      
    }
    // GemStone changes END


    if (!("SYSSTAT".equalsIgnoreCase(this.schemaName)
		|| Misc.isSnappyHiveMetaTable((this.schemaName))
        || GemFireXDUtils.TraceConglom)) {
      SanityManager.DEBUG_PRINT(
          "info:" + GfxdConstants.TRACE_CONGLOM,
          "Created table " + td.getQualifiedName() + " with UUID: "
              + td.getUUID());
    }
    if (GemFireXDUtils.TraceConglom) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
          "Created table with descriptor:\n" + td);
    }
  }

  /** if this table is colocated with another; used during DDL replay */
  private String colocatedWithTable;

  /** true if this table is a user created normal table */
  public boolean isReplayable() {
    return (this.tableType == TableDescriptor.BASE_TABLE_TYPE/*GemStone changes BEGIN*/ || this.tableType == TableDescriptor.COLUMN_TABLE_TYPE/*GemStone changes END*/);
  }

  @Override
  public final String getSchemaName() {
    return this.schemaName;
  }

  @Override
  public final String getTableName() {
    return this.tableName;
  }

  public final String getColocatedWithTable() {
    return this.colocatedWithTable;
  }
  
  /**
   * Returns the generated SQL text passed from
   * {@link CreateTableNode#makeConstantAction()} in case of DDL such as 
   * 'create table t2 as select * from t1'. The generated SQL text will 
   * contain actual column information instead of 'select' clause.
   * Returns null, if the DDL does not contain select clause    
   * <p>See {@link CreateTableNode#getSQLTextForCTAS(ColumnInfo[])}
   */
  public String getSQLTextForCTAS() {
    return generatedSqlTextForCTAS;
  }

  /**
   * Encapsulate data to load index at commit time.
   */
  public static final class LoadIndexData {

    private final CreateIndexConstantAction indexAction;

    private final FormatableBitSet zeroBasedBitSet;

    private final ExecRow[] baseRows;

    private final ExecIndexRow[] indexRows;

    private final RowLocation[] rl;

    public LoadIndexData(CreateIndexConstantAction indexAction,
        FormatableBitSet zeroBasedBitSet, ExecRow[] baseRows,
        ExecIndexRow[] indexRows, RowLocation[] rl) {
      this.indexAction = indexAction;
      this.zeroBasedBitSet = zeroBasedBitSet;
      this.baseRows = baseRows;
      this.indexRows = indexRows;
      this.rl = rl;
    }

    private final void loadIndexConglomerate(LanguageConnectionContext lcc,
        TransactionController tc, TableDescriptor td,
        GfxdIndexManager indexManager) throws StandardException {
      this.indexAction.loadIndexConglomerate(lcc, tc, td, indexManager,
          this.zeroBasedBitSet, this.baseRows, this.indexRows, this.rl, false);
    }
  }

// GemStone changes END
}
