/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.execute.CreateIndexConstantAction

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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager.Index;
import com.pivotal.gemfirexd.internal.engine.access.index.GlobalExecRowLocation;
import com.pivotal.gemfirexd.internal.engine.access.index.SortedMap2Index;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDescriptorGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.IndexRowGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecIndexRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.AccessFactoryGlobals;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.store.access.GroupFetchScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowLocationRetRowSource;
import com.pivotal.gemfirexd.internal.iapi.store.access.SortObserver;
import com.pivotal.gemfirexd.internal.iapi.store.access.StaticCompiledOpenConglomInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * ConstantAction to create an index either through
 * a CREATE INDEX statement or as a backing index to
 * a constraint.
 */

// GemStone changes BEGIN
//made public
public final
// GemStone changes END
class CreateIndexConstantAction extends IndexConstantAction
{
    /**
     * Is this for a CREATE TABLE, i.e. it is
     * for a constraint declared in a CREATE TABLE
     * statement that requires a backing index.
     */
    private final boolean forCreateTable;

	private boolean			unique;
	private boolean			uniqueWithDuplicateNulls;
	private String			indexType;
	private String[]		columnNames;
	private boolean[]		isAscending;
	private boolean			isConstraint;
	private UUID			conglomerateUUID;
	private Properties		properties;

	private ExecRow indexTemplateRow;

	/** Conglomerate number for the conglomerate created by this
	 * constant action; -1L if this constant action has not been
	 * executed.  If this constant action doesn't actually create
	 * a new conglomerate--which can happen if it finds an existing
	 * conglomerate that satisfies all of the criteria--then this
	 * field will hold the conglomerate number of whatever existing
	 * conglomerate was found.
	 */
	private long conglomId;

	/** Conglomerate number of the physical conglomerate that we
	 * will "replace" using this constant action.  That is, if
	 * the purpose of this constant action is to create a new physical
	 * conglomerate to replace a dropped physical conglomerate, then
	 * this field holds the conglomerate number of the dropped physical
	 * conglomerate. If -1L then we are not replacing a conglomerate,
	 * we're simply creating a new index (and backing physical
	 * conglomerate) as normal.
	 */
	private long droppedConglomNum;

	// GemStone changes BEGIN
        private boolean createIRF;

        private GemFireContainer indexContainer;
        
        public GemFireContainer getIndexContainer() {
          return this.indexContainer;
        }
        // GemStone changes END
        
	// CONSTRUCTORS
	/**
     * 	Make the ConstantAction to create an index.
     * 
     * @param forCreateTable                Being executed within a CREATE TABLE
     *                                      statement
     * @param unique		                True means it will be a unique index
     * @param uniqueWithDuplicateNulls      True means index check and disallow
     *                                      any duplicate key if key has no 
     *                                      column with a null value.  If any 
     *                                      column in the key has a null value,
     *                                      no checking is done and insert will
     *                                      always succeed.
     * @param indexType	                    type of index (BTREE, for example)
     * @param schemaName	                schema that table (and index) 
     *                                      lives in.
     * @param indexName	                    Name of the index
     * @param tableName	                    Name of table the index will be on
     * @param tableId		                UUID of table
     * @param columnNames	                Names of the columns in the index, 
     *                                      in order
     * @param isAscending	                Array of booleans telling asc/desc 
     *                                      on each column
     * @param isConstraint	                TRUE if index is backing up a 
     *                                      constraint, else FALSE
     * @param conglomerateUUID	            ID of conglomerate
     * @param properties	                The optional properties list 
     *                                      associated with the index.
     */
	CreateIndexConstantAction(
            boolean         forCreateTable,
            boolean			unique,
            boolean			uniqueWithDuplicateNulls,
            String			indexType,
            String			schemaName,
            String			indexName,
            String			tableName,
            UUID			tableId,
            String[]		columnNames,
            boolean[]		isAscending,
            boolean			isConstraint,
            UUID			conglomerateUUID,
            Properties		properties)
	{
		super(tableId, indexName, tableName, schemaName);
// GemStone changes BEGIN
// [sjigyasu] Moved the following from IndexConstantAction's constructor 
              if (SanityManager.DEBUG)
              {
                      SanityManager.ASSERT(schemaName != null, "Schema name is null");
              }
// GemStone changes END
        this.forCreateTable             = forCreateTable;
		this.unique                     = unique;
		this.uniqueWithDuplicateNulls   = uniqueWithDuplicateNulls;
		this.indexType                  = indexType;
		this.columnNames                = columnNames;
		this.isAscending                = isAscending;
		this.isConstraint               = isConstraint;
		this.conglomerateUUID           = conglomerateUUID;
		this.properties                 = properties;
		this.conglomId                  = -1L;
		this.droppedConglomNum          = -1L;
	}

	/**
	 * Make a ConstantAction that creates a new physical conglomerate
	 * based on index information stored in the received descriptors.
	 * Assumption is that the received ConglomerateDescriptor is still
	 * valid (meaning it has corresponding entries in the system tables
	 * and it describes some constraint/index that has _not_ been
	 * dropped--though the physical conglomerate underneath has).
	 *
	 * This constructor is used in cases where the physical conglomerate
	 * for an index has been dropped but the index still exists. That
	 * can happen if multiple indexes share a physical conglomerate but
	 * then the conglomerate is dropped as part of "drop index" processing
	 * for one of the indexes. (Note that "indexes" here includes indexes
	 * which were created to back constraints.) In that case we have to
	 * create a new conglomerate to satisfy the remaining sharing indexes,
	 * so that's what we're here for.  See ConglomerateDescriptor.drop()
	 * for details on when that is necessary.
	 */
	CreateIndexConstantAction(ConglomerateDescriptor srcCD,
		TableDescriptor td, Properties properties)
	{
		super(td.getUUID(),
			srcCD.getConglomerateName(), td.getName(), td.getSchemaName());

		this.forCreateTable = false;

		/* We get here when a conglomerate has been dropped and we
		 * need to create (or find) another one to fill its place.
		 * At this point the received conglomerate descriptor still
		 * references the old (dropped) conglomerate, so we can
		 * pull the conglomerate number from there.
		 */
		this.droppedConglomNum = srcCD.getConglomerateNumber();

		/* Plug in the rest of the information from the received
		 * descriptors.
		 */
		IndexRowGenerator irg = srcCD.getIndexDescriptor();
		this.unique = irg.isUnique();
		this.uniqueWithDuplicateNulls = irg.isUniqueWithDuplicateNulls();
		this.indexType = irg.indexType();
// GemStone changes BEGIN
		// These column names are not reliable in case of multiple
		// add/drop constraints/columns etc.
		this.columnNames = null;
		// if index name explicitly specified then use that
		final String name = properties.getProperty(
		    GfxdConstants.PROPERTY_TABLE_NAME);
		if (name != null && name.length() > 0) {
		  this.indexName = name;
		}
		/* (original code) this.columnNames = srcCD.getColumnNames(); */
// GemStone changes END
		this.isAscending = irg.isAscending();
		this.isConstraint = srcCD.isConstraint();
		this.conglomerateUUID = srcCD.getUUID();
		this.properties = properties;
		this.conglomId = -1L;

		/* The ConglomerateDescriptor may not know the names of
		 * the columns it includes.  If that's true (which seems
		 * to be the more common case) then we have to build the
		 * list of ColumnNames ourselves.
		 */
		if (columnNames == null)
		{
			int [] baseCols = irg.baseColumnPositions();
			columnNames = new String[baseCols.length];
			ColumnDescriptorList colDL = td.getColumnDescriptorList();
			for (int i = 0; i < baseCols.length; i++)
			{
				columnNames[i] =
					colDL.elementAt(baseCols[i]-1).getColumnName();
			}
		}
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
		return "CREATE INDEX " + indexName;
	}

	// INTERFACE METHODS


	/**
	 *	This is the guts of the Execution-time logic for 
     *  creating an index.
     *
     *  <P>
     *  A index is represented as:
     *  <UL>
     *  <LI> ConglomerateDescriptor.
     *  </UL>
     *  No dependencies are created.
   	 *
     *  @see ConglomerateDescriptor
     *  @see SchemaDescriptor
	 *	@see ConstantAction#executeConstantAction
	 *
	 * @exception StandardException		Thrown on failure
	 */
	public void	executeConstantAction( Activation activation )
						throws StandardException
	{
		TableDescriptor 			td;
		UUID 						toid;
		ColumnDescriptor			columnDescriptor;
		int[]						baseColumnPositions;
		IndexRowGenerator			indexRowGenerator = null;
		ExecRow[]					baseRows;
		ExecIndexRow[]				indexRows;
		ExecRow[]					compactBaseRows;
		GroupFetchScanController    scan = null;
		RowLocationRetRowSource	    rowSource;
		long						sortId;
		int							maxBaseColumnPosition = -1;               
		LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
		DataDictionary dd = lcc.getDataDictionary();
		DependencyManager dm = dd.getDependencyManager();
		TransactionController tc = lcc.getTransactionExecute();

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

		/*
		** If the schema descriptor is null, then
		** we must have just read ourselves in.  
		** So we will get the corresponding schema
		** descriptor from the data dictionary.
		*/
		SchemaDescriptor sd = dd.getSchemaDescriptor(schemaName, tc, true) ;


		/* Get the table descriptor. */
		/* See if we can get the TableDescriptor 
		 * from the Activation.  (Will be there
		 * for backing indexes.)
		 */
		td = activation.getDDLTableDescriptor();

		if (td == null)
		{
			/* tableId will be non-null if adding an index to
			 * an existing table (as opposed to creating a
			 * table with a constraint with a backing index).
			 */
			if (tableId != null)
			{
				td = dd.getTableDescriptor(tableId);
			}
			else
			{
				td = dd.getTableDescriptor(tableName, sd, tc);
			}
		}

		if (td == null)
		{
			throw StandardException.newException(SQLState.LANG_CREATE_INDEX_NO_TABLE, 
						indexName, tableName);
		}

		if (td.getTableType() == TableDescriptor.SYSTEM_TABLE_TYPE)
		{
			throw StandardException.newException(SQLState.LANG_CREATE_SYSTEM_INDEX_ATTEMPTED, 
						indexName, tableName);
		}

		/* Get a shared table lock on the table. We need to lock table before
		 * invalidate dependents, otherwise, we may interfere with the
		 * compilation/re-compilation of DML/DDL.  See beetle 4325 and $WS/
		 * docs/language/SolutionsToConcurrencyIssues.txt (point f).
		 */
// GemStone changes BEGIN
    // check for any open ResultSets
    if (!forCreateTable) {
      lcc.verifyNoOpenResultSets(null, td, DependencyManager.CREATE_INDEX);
    }
    // Take the lock in exclusive mode to avoid deadlocks.
    // Also refresh the TableDescriptor since the table may have changed while
    // the lock was being granted (also see Derby's comments in
    // DropIndexConstantAction where similar approach has been used so this
    // looks like a bug in Derby)
    // skip the lock and refresh if this is part of constraint since the
    // relevant table lock will be taken by alter table/create table
    if (!this.isConstraint) {
      lockTableForDDL(tc, td.getHeapConglomerateId(), true);
      // refresh the table descriptor since it may have changed
      if (this.tableId != null) {
        td = dd.getTableDescriptor(this.tableId);
        if (td == null) {
          throw StandardException.newException(
              SQLState.LANG_CREATE_INDEX_NO_TABLE, indexName, tableName);
        }
        // check that the table should have at least one datastore available
        final DistributionDescriptor desc = td.getDistributionDescriptor();
        DistributionDescriptor.checkAvailableDataStore(lcc,
            desc.getServerGroups(),
            "CREATE INDEX for table " + Misc.getFullTableName(td, lcc));
      }
    }
		/* lockTableForDDL(tc, td.getHeapConglomerateId(), false); */
    // GemFireXD cannot handle Index persistence optimization and on the 
    // fly index creation as of the 1.0.3 hot fix release.
    // will re-visit this later. For now if there is data in the table
    // then disable
    // Neeraj: Handling the above constraint now for cheetah
    final GemFireStore memStore = Misc.getMemStoreBooting();
    final boolean persistIndexes = memStore.isPersistIndexes();
    if (GemFireXDUtils.TraceIndex || GemFireXDUtils.TracePersistIndex) {
      GfxdIndexManager.traceIndex("CreateIndexConstantAction::"
          + "executeConstantAction persist-indexes=%s, this.forCreateTable=%s",
          persistIndexes, this.createIRF);
    }
    if (persistIndexes) {
      Region<?, ?> region = Misc.getRegionForTableByPath(
          Misc.getFullTableName(td, lcc), false);
      if (region == null) {
        if (!this.forCreateTable) {
          throw new IllegalStateException("region cannot be null here");
        }
      }
      else {
        int numBuckets = AlterTableConstantAction.getNumBucketsOrSize(region,
            lcc);
        if (numBuckets > 0) {
          this.createIRF = true;
        }
        if (GemFireXDUtils.TraceIndex || GemFireXDUtils.TracePersistIndex) {
          GfxdIndexManager.traceIndex("CreateIndexConstantAction::"
              + "executeConstantAction numBuckets=%s, createIRF=%s",
              numBuckets, this.createIRF);
        }
      }
    }
// GemStone changes END

		// invalidate any prepared statements that
		// depended on this table (including this one)
		if (! forCreateTable)
		{
			dm.invalidateFor(td, DependencyManager.CREATE_INDEX, lcc);
		}

		// Translate the base column names to column positions
		baseColumnPositions = new int[columnNames.length];
		for (int i = 0; i < columnNames.length; i++)
		{
			// Look up the column in the data dictionary
			columnDescriptor = td.getColumnDescriptor(columnNames[i]);
			if (columnDescriptor == null)
			{
				throw StandardException.newException(SQLState.LANG_COLUMN_NOT_FOUND_IN_TABLE, 
															columnNames[i],
															tableName);
			}

			TypeId typeId = columnDescriptor.getType().getTypeId();

			// Don't allow a column to be created on a non-orderable type
			ClassFactory cf = lcc.getLanguageConnectionFactory().getClassFactory();
			boolean isIndexable = typeId.orderable(cf);

			if (isIndexable && typeId.userType()) {
				String userClass = typeId.getCorrespondingJavaTypeName();

				// Don't allow indexes to be created on classes that
				// are loaded from the database. This is because recovery
				// won't be able to see the class and it will need it to
				// run the compare method.
				try {
					if (cf.isApplicationClass(cf.loadApplicationClass(userClass)))
						isIndexable = false;
				} catch (ClassNotFoundException cnfe) {
					// shouldn't happen as we just check the class is orderable
					isIndexable = false;
				}
			}

			if (!isIndexable) {
				throw StandardException.newException(SQLState.LANG_COLUMN_NOT_ORDERABLE_DURING_EXECUTION, 
					typeId.getSQLTypeName());
			}

			// Remember the position in the base table of each column
			baseColumnPositions[i] = columnDescriptor.getPosition();

			if (maxBaseColumnPosition < baseColumnPositions[i])
				maxBaseColumnPosition = baseColumnPositions[i];
		}

		/* The code below tries to determine if the index that we're about
		 * to create can "share" a conglomerate with an existing index.
		 * If so, we will use a single physical conglomerate--namely, the
		 * one that already exists--to support both indexes. I.e. we will
		 * *not* create a new conglomerate as part of this constant action.
		 */ 

		// check if we have similar indices already for this table
		ConglomerateDescriptor[] congDescs = td.getConglomerateDescriptors();
		boolean shareExisting = false;
// GemStone changes BEGIN
    if (this.isConstraint || this.unique || this.uniqueWithDuplicateNulls) {
      this.indexType = this.getConstraintIndexType(lcc, td, baseColumnPositions,
          properties);
    }
    // don't allow creating local indexes on HDFS tables using eviction
    // that is not EVICTION BY CRITERIA
    if (this.indexType.equals(GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE)) {
      GemFireContainer baseContainer = memStore.getContainer(ContainerKey
          .valueOf(ContainerHandle.TABLE_SEGMENT, td.getHeapConglomerateId()));
      RegionAttributes<?, ?> rattrs = baseContainer.getRegionAttributes();
      if (rattrs.getDataPolicy().withHDFS()) {
        if (rattrs.getEvictionAttributes() != null
            && !rattrs.getEvictionAttributes().getAlgorithm().isNone()
            && !rattrs.getEvictionAttributes().getAction().isOverflowToDisk()) {
          throw StandardException.newException(
              SQLState.INDEX_ON_HDFS_DATA_NOT_SUPPORTED_WITH_EVICTION,
              this.indexName, td.getQualifiedName(), rattrs
                  .getEvictionAttributes().getAction());
        }
      }
    }
// GemStone changes END
		
		for (int i = 0; i < congDescs.length; i++)
		{
			ConglomerateDescriptor cd = congDescs[i];

                       
                      
			if ( ! cd.isIndex())
			  continue;

// GemStone changes BEGIN
      /*
       * skip the duplicate index check if two indexes having the same
       * key fields but different implementations
       * @author yjing
       */
      // [sumedh] Don't skip the duplicate check if FK is being created
      // on the same fields as PK, for example, since the latter's index
      // can be safely used by FK. In general, a "stronger" index can
      // override a "weaker" one e.g. "LOCALHASH1" will prevail over
      // "GLOBALHASH"
      String otherIndexType = cd.getIndexDescriptor().indexType();
      boolean existsStrongerIndex = false;
      if (otherIndexType.equals(this.indexType)) {
        existsStrongerIndex = true;
      }
      else if (this.indexType.equals(GfxdConstants.GLOBAL_HASH_INDEX_TYPE)) {
        existsStrongerIndex = true;
      }
      if (!existsStrongerIndex) {
        continue;
      }
// GemStone changes END			
			if (droppedConglomNum == cd.getConglomerateNumber())
			{
				/* We can't share with any conglomerate descriptor
				 * whose conglomerate number matches the dropped
				 * conglomerate number, because that descriptor's
				 * backing conglomerate was dropped, as well.  If
				 * we're going to share, we have to share with a
				 * descriptor whose backing physical conglomerate
				 * is still around.
				 */
				continue;
			}

			IndexRowGenerator irg = cd.getIndexDescriptor();
			int[] bcps = irg.baseColumnPositions();
			boolean[] ia = irg.isAscending();
			int j = 0;

			/* The conditions which allow an index to share an existing
			 * conglomerate are as follows:
			 *
			 * 1. the set of columns (both key and include columns) and their 
			 *  order in the index is the same as that of an existing index AND 
			 *
			 * 2. the ordering attributes are the same AND 
			 *
			 * 3. one of the following is true:
			 *    a) the existing index is unique, OR
			 *    b) the existing index is non-unique with uniqueWhenNotNulls
			 *       set to TRUE and the index being created is non-unique, OR
			 *    c) both the existing index and the one being created are
			 *       non-unique and have uniqueWithDuplicateNulls set to FALSE.
			 */ 
			boolean possibleShare = (irg.isUnique() || !unique) &&
			    (bcps.length == baseColumnPositions.length);

			//check if existing index is non unique and uniqueWithDuplicateNulls
			//is set to true (backing index for unique constraint)
			if (possibleShare && !irg.isUnique ())
			{
				/* If the existing index has uniqueWithDuplicateNulls set to
				 * TRUE it can be shared by other non-unique indexes; otherwise
				 * the existing non-unique index has uniqueWithDuplicateNulls
				 * set to FALSE, which means the new non-unique conglomerate
				 * can only share if it has uniqueWithDuplicateNulls set to
				 * FALSE, as well.
				 */
				possibleShare = (irg.isUniqueWithDuplicateNulls() ||
								! uniqueWithDuplicateNulls);
			}

// GemStone changes BEGIN
			// [sumedh] Don't skip the duplicate check if FK is being created
			// on the same fields as PK, for example, since the latter's index
			// can be safely used by FK. In general, a "stronger" index can
			// override a "weaker" one e.g. "LOCALHASH1" will prevail over
			// "LOCALSORTEDMAP"
			if (possibleShare && existsStrongerIndex)
			/* if (possibleShare && indexType.equals(irg.indexType())) */
// GemStone changes END
			{
				for (; j < bcps.length; j++)
				{
					if ((bcps[j] != baseColumnPositions[j]) || (ia[j] != isAscending[j]))
						break;
				}
			}

			if (j == baseColumnPositions.length)	// share
			{
				/*
				 * Don't allow users to create a duplicate index. Allow if being done internally
				 * for a constraint
				 */
				if (!isConstraint)
				{
					activation.addWarning(
							StandardException.newWarning(
								SQLState.LANG_INDEX_DUPLICATE,
								cd.getConglomerateName()));

					return;
				}

				/* Sharing indexes share the physical conglomerate
				 * underneath, so pull the conglomerate number from
				 * the existing conglomerate descriptor.
				 */
				conglomId = cd.getConglomerateNumber();

				/* We create a new IndexRowGenerator because certain
				 * attributes--esp. uniqueness--may be different between
				 * the index we're creating and the conglomerate that
				 * already exists.  I.e. even though we're sharing a
				 * conglomerate, the new index is not necessarily
				 * identical to the existing conglomerate. We have to
				 * keep track of that info so that if we later drop
				 * the shared physical conglomerate, we can figure out
				 * what this index (the one we're creating now) is
				 * really supposed to look like.
				 */
				indexRowGenerator =
					new IndexRowGenerator(
						indexType, unique, uniqueWithDuplicateNulls,
						baseColumnPositions,
						isAscending,
						baseColumnPositions.length);

				//DERBY-655 and DERBY-1343  
				// Sharing indexes will have unique logical conglomerate UUIDs.
				conglomerateUUID = dd.getUUIDFactory().createUUID();
				shareExisting = true;
				break;
			}
		}

		/* If we have a droppedConglomNum then the index we're about to
		 * "create" already exists--i.e. it has an index descriptor and
		 * the corresponding information is already in the system catalogs.
		 * The only thing we're missing, then, is the physical conglomerate
		 * to back the index (because the old conglomerate was dropped).
		 */
		boolean alreadyHaveConglomDescriptor = (droppedConglomNum > -1L);

		/* If this index already has an essentially same one, we share the
		 * conglomerate with the old one, and just simply add a descriptor
		 * entry into SYSCONGLOMERATES--unless we already have a descriptor,
		 * in which case we don't even need to do that.
		 */
		DataDescriptorGenerator ddg = dd.getDataDescriptorGenerator();
		if (shareExisting && !alreadyHaveConglomDescriptor)
		{
		  ConglomerateDescriptor cgd =
				ddg.newConglomerateDescriptor(conglomId, indexName, true,
										  indexRowGenerator, isConstraint,
										  conglomerateUUID, td.getUUID(), sd.getUUID() );
			dd.addDescriptor(cgd, sd, DataDictionary.SYSCONGLOMERATES_CATALOG_NUM, false, tc);
			// add newly added conglomerate to the list of conglomerate 
			// descriptors in the td.
			ConglomerateDescriptorList cdl = 
				td.getConglomerateDescriptorList();
			cdl.add(cgd);

			// can't just return yet, need to get member "indexTemplateRow"
			// because create constraint may use it
		}

		// Describe the properties of the index to the store using Properties
		// RESOLVE: The following properties assume a BTREE index.
		Properties	indexProperties;
		
		if (properties != null)
		{
			indexProperties = properties;
		}
		else
		{
			indexProperties = new Properties();
		}

		// Tell it the conglomerate id of the base table
		indexProperties.put("baseConglomerateId",
							Long.toString(td.getHeapConglomerateId()));
        
		if (uniqueWithDuplicateNulls) 
        {
			if (lcc.getDataDictionary().checkVersion(
				DataDictionary.DD_VERSION_DERBY_10_4, null)) 
            {
				indexProperties.put(
                    "uniqueWithDuplicateNulls", Boolean.toString(true));
			}
			else 
            {
				// for lower version of DD there is no unique with nulls 
                // index creating a unique index instead.
				if (uniqueWithDuplicateNulls) 
                {
					unique = true;
				}
			}
		}

		// All indexes are unique because they contain the RowLocation.
		// The number of uniqueness columns must include the RowLocation
		// if the user did not specify a unique index.
		indexProperties.put("nUniqueColumns",
					Integer.toString(unique ? baseColumnPositions.length :
												baseColumnPositions.length + 1)
							);
		// By convention, the row location column is the last column
		indexProperties.put("rowLocationColumn",
							Integer.toString(baseColumnPositions.length));

		// For now, all columns are key fields, including the RowLocation
		indexProperties.put("nKeyFields",
							Integer.toString(baseColumnPositions.length + 1));
		
	//	System.out.println("XXXXCreateIndexConstantAction:"+indexType);

		// For now, assume that all index columns are ordered columns
		if (! shareExisting)
		{
			if (lcc.getDataDictionary().checkVersion(
					DataDictionary.DD_VERSION_DERBY_10_4, null)) 
            {
                indexRowGenerator = new IndexRowGenerator(
                                            indexType, 
                                            unique, 
                                            uniqueWithDuplicateNulls,
                                            baseColumnPositions,
                                            isAscending,
                                            baseColumnPositions.length);
			}
			else 
            {
				indexRowGenerator = new IndexRowGenerator(
                                            indexType, 
                                            unique,
                                            baseColumnPositions,
                                            isAscending,
                                            baseColumnPositions.length);
			}
		}

		/* Now add the rows from the base table to the conglomerate.
		 * We do this by scanning the base table and inserting the
		 * rows into a sorter before inserting from the sorter
		 * into the index.  This gives us better performance
		 * and a more compact index.
		 */

		rowSource = null;
		sortId = 0;
		boolean needToDropSort = false;	// set to true once the sorter is created

		/* bulkFetchSIze will be 16 (for now) unless
		 * we are creating the table in which case it
		 * will be 1.  Too hard to remove scan when
		 * creating index on new table, so minimize
		 * work where we can.
		 */
		int bulkFetchSize = (forCreateTable) ? 1 : 16;	
		int numColumns = td.getNumberOfColumns();
		int approximateRowSize = 0;

		// Create the FormatableBitSet for mapping the partial to full base row
		FormatableBitSet bitSet = new FormatableBitSet(numColumns+1);
		for (int index = 0; index < baseColumnPositions.length; index++)
		{
			bitSet.set(baseColumnPositions[index]);
		}
		FormatableBitSet zeroBasedBitSet = RowUtil.shift(bitSet, 1);

		// Start by opening a full scan on the base table.
// GemStone changes BEGIN
		// region may be uninitialized when merging CREATE TABLE with
		// ALTER TABLEs during initial DDL replay
		final boolean regionInitialized =
		    !lcc.skipRegionInitialization();
		if (regionInitialized)
// GemStone changes END
		scan = tc.openGroupFetchScan(
                            td.getHeapConglomerateId(),
							false,	// hold
							0,	// open base table read only
                            TransactionController.MODE_TABLE,
                            TransactionController.ISOLATION_SERIALIZABLE,
							zeroBasedBitSet,    // all fields as objects
							(DataValueDescriptor[]) null,	// startKeyValue
							0,		// not used when giving null start posn.
							null,	// qualifier
							(DataValueDescriptor[]) null,	// stopKeyValue
							0);		// not used when giving null stop posn.

		// Create an array to put base row template
		baseRows = new ExecRow[bulkFetchSize];
		indexRows = new ExecIndexRow[bulkFetchSize];
		compactBaseRows = new ExecRow[bulkFetchSize];
// GemStone changes BEGIN
		RowLocation[] rl = null;
// GemStone changes END
		try
		{
			// Create the array of base row template
			for (int i = 0; i < bulkFetchSize; i++)
			{
				// create a base row template
				baseRows[i] = activation.getExecutionFactory().getValueRow(maxBaseColumnPosition);

				// create an index row template
				indexRows[i] = indexRowGenerator.getIndexRowTemplate();

				// create a compact base row template
				compactBaseRows[i] = activation.getExecutionFactory().getValueRow(
													baseColumnPositions.length);
			}

			indexTemplateRow = indexRows[0];

			// Fill the partial row with nulls of the correct type
			ColumnDescriptorList cdl = td.getColumnDescriptorList();
			int					 cdlSize = cdl.size();
			for (int index = 0, numSet = 0; index < cdlSize; index++)
			{
				if (! zeroBasedBitSet.get(index))
				{
					continue;
				}
				numSet++;
				ColumnDescriptor cd = (ColumnDescriptor) cdl.elementAt(index);
				DataTypeDescriptor dts = cd.getType();


				for (int i = 0; i < bulkFetchSize; i++)
				{
					// Put the column in both the compact and sparse base rows
					baseRows[i].setColumn(index + 1,
								  dts.getNull());
					compactBaseRows[i].setColumn(numSet,
								  baseRows[i].getColumn(index + 1));
				}

				// Calculate the approximate row size for the index row
				approximateRowSize += dts.getTypeId().getApproximateLengthInBytes(dts);
			}
                        
                        
			// Get an array of RowLocation template
// GemStone changes BEGIN
			rl = new RowLocation[bulkFetchSize];
// GemStone changes END
			for (int i = 0; i < bulkFetchSize; i++)
			{
// GemStone changes BEGIN
			  if (this.indexType.equals(GfxdConstants.GLOBAL_HASH_INDEX_TYPE)) {
			    rl[i] = new GlobalExecRowLocation();
			  }
			  else {
			    rl[i] = scan != null ? scan.newRowLocationTemplate()
			        : DataValueFactory.DUMMY;
			  }
// GemStone changes END

				// Get an index row based on the base row
				indexRowGenerator.getIndexRow(compactBaseRows[i], rl[i], indexRows[i], bitSet);
			}

			/* now that we got indexTemplateRow, done for sharing index
			 */
			if (shareExisting)
				return;

			/* For non-unique indexes, we order by all columns + the RID.
			 * For unique indexes, we just order by the columns.
			 * We create a unique index observer for unique indexes
			 * so that we can catch duplicate key.
			 * We create a basic sort observer for non-unique indexes
			 * so that we can reuse the wrappers during an external
			 * sort.
			 */
// GemStone changes BEGIN
			int             numColumnOrderings = 0;
// GemStone changes END
			SortObserver    sortObserver   = null;
            Properties      sortProperties = null;
			if (unique || uniqueWithDuplicateNulls)
			{
// GemStone changes BEGIN
			  if (!indexType.equals(GfxdConstants.GLOBAL_HASH_INDEX_TYPE))
              {
// GemStone changes END
				// if the index is a constraint, use constraintname in 
                // possible error message
				String indexOrConstraintName = indexName;
				if  (conglomerateUUID != null)
				{
					ConglomerateDescriptor cd = 
                        dd.getConglomerateDescriptor(conglomerateUUID);
					if ((isConstraint) && 
                        (cd != null && cd.getUUID() != null && td != null))
					{
						ConstraintDescriptor conDesc = 
                            dd.getConstraintDescriptor(td, cd.getUUID());
						indexOrConstraintName = conDesc.getConstraintName();
					}
				}

				if (unique) 
				{
                    numColumnOrderings = baseColumnPositions.length;

					sortObserver = 
                        new UniqueIndexSortObserver(
                                true, 
                                isConstraint, 
                                indexOrConstraintName,
                                indexTemplateRow,
                                true,
                                td.getName());
				}
				else 
                {
                    // unique with duplicate nulls allowed.

					numColumnOrderings = baseColumnPositions.length + 1;

                    // tell transaction controller to use the unique with 
                    // duplicate nulls sorter, when making createSort() call.
					sortProperties = new Properties();
					sortProperties.put(
                        AccessFactoryGlobals.IMPL_TYPE, 
                        AccessFactoryGlobals.SORT_UNIQUEWITHDUPLICATENULLS_EXTERNAL);
					//use sort operator which treats nulls unequal
					sortObserver = 
                        new UniqueWithDuplicateNullsIndexSortObserver(
                                true, 
                                isConstraint, 
                                indexOrConstraintName,
                                indexTemplateRow,
                                true,
                                td.getName());
				}
// GemStone changes BEGIN
			  }
// GemStone changes END
			}
			else
			{
				numColumnOrderings = baseColumnPositions.length + 1;
				sortObserver = new BasicSortObserver(true, false, 
													 indexTemplateRow,
													 true);
			}

			ColumnOrdering[]	order = new ColumnOrdering[numColumnOrderings];
			for (int i=0; i < numColumnOrderings; i++) 
			{
				order[i] = 
                    new IndexColumnOrder(
                        i, 
                        unique || i < numColumnOrderings - 1 ? 
                            isAscending[i] : true);
			}

// GemStone changes BEGIN
      indexProperties.put(DataDictionary.MODULE, dd);
      indexProperties.setProperty(GfxdConstants.PROPERTY_SCHEMA_NAME,
          this.schemaName);
      indexProperties.setProperty(GfxdConstants.PROPERTY_TABLE_NAME,
          this.indexName);
      indexProperties.put(GfxdConstants.PROPERTY_INDEX_BASE_COL_POS,
          baseColumnPositions);
      indexProperties.put(GfxdConstants.PROPERTY_INDEX_BASE_TABLE_DESC, td);
      // indexProperties.put("IndexName", this.indexName);
      // System.out.println("IndexName="+this.indexName+" indexType="+this.indexType);
      // create index conglomerate
      conglomId = tc
          .createConglomerate(indexType,
              indexTemplateRow.getRowArray(), // index row template
              order, // colums sort order
              indexRowGenerator.getColumnCollationIds(td
                  .getColumnDescriptorList()), indexProperties,
              TransactionController.IS_DEFAULT // not temporary
          );
      if (GemFireXDUtils.TraceIndex || GemFireXDUtils.TracePersistIndex) {
        GfxdIndexManager.traceIndex("CreateIndexConstantAction::"
            + "executeConstantAction persist-indexes=%s, "
            + "this.throughConstraint=%s, conglomId=%s",
            persistIndexes, this.throughConstraint, conglomId);
      }
      if (persistIndexes && this.throughConstraint) {
        StaticCompiledOpenConglomInfo scoci = lcc.getTransactionCompile()
            .getStaticCompiledConglomInfo(conglomId);
        DataValueDescriptor tmpdvd = scoci.getConglom();
        if (GemFireXDUtils.TraceIndex || GemFireXDUtils.TracePersistIndex) {
          GfxdIndexManager.traceIndex("CreateIndexConstantAction::"
              + "executeConstantAction tmpdvd=%s", tmpdvd);
        }
        this.indexContainer = tmpdvd instanceof SortedMap2Index ? ((SortedMap2Index)tmpdvd)
            .getGemFireContainer() : null;
      }
// GemStone Changes END 

		}
		finally
		{

			/* close the table scan */
			if (scan != null)
				scan.close();

			/* close the sorter row source before throwing exception */
			if (rowSource != null)
				rowSource.closeRowSource();

			/*
			** drop the sort so that intermediate external sort run can be
			** removed from disk
			*/
			if (needToDropSort)
			 	tc.dropSort(sortId);
		}

		ConglomerateController indexController =
			tc.openConglomerate(
                conglomId, false, 0, TransactionController.MODE_TABLE,
                TransactionController.ISOLATION_SERIALIZABLE);

		// Check to make sure that the conglomerate can be used as an index
		if ( ! indexController.isKeyed())
		{
			indexController.close();
			throw StandardException.newException(SQLState.LANG_NON_KEYED_INDEX, indexName,
														   indexType);
		}
		indexController.close();

		//
		// Create a conglomerate descriptor with the conglomId filled
		// in and add it--if we don't have one already.
		//
		if (!alreadyHaveConglomDescriptor)
		{
			ConglomerateDescriptor cgd =
				ddg.newConglomerateDescriptor(
					conglomId, indexName, true,
					indexRowGenerator, isConstraint,
					conglomerateUUID, td.getUUID(), sd.getUUID() );

			dd.addDescriptor(cgd, sd,
				DataDictionary.SYSCONGLOMERATES_CATALOG_NUM, false, tc);

			// add newly added conglomerate to the list of conglomerate
			// descriptors in the td.
			ConglomerateDescriptorList cdl = td.getConglomerateDescriptorList();
			cdl.add(cgd);

			/* Since we created a new conglomerate descriptor, load
			 * its UUID into the corresponding field, to ensure that
			 * it is properly set in the StatisticsDescriptor created
			 * below.
			 */
			conglomerateUUID = cgd.getUUID();
		}

// GemStone changes BEGIN
    ConglomerateDescriptor cd = dd
        .getConglomerateDescriptor(this.conglomerateUUID);
    try {
      final com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager
          indexManager = com.pivotal.gemfirexd.internal.engine.access.index
              .GfxdIndexManager.getGfxdIndexManager(td, lcc);
      if (GemFireXDUtils.TraceIndex || GemFireXDUtils.TracePersistIndex) {
        GfxdIndexManager
            .traceIndex(
                "CreateIndexConstantAction::executeConstantAction "
                    + " skipLoadConglom=%s and regionInitialized=%s for table=%s and implicit indexContainer created=%s",
                skipLoadConglom,
                regionInitialized,
                td.getQualifiedName(),
                    (this.indexContainer != null ? this.indexContainer
                        .getQualifiedTableName() : "null"));
      }
      boolean ddlReplayInProgress = memStore.initialDDLReplayInProgress();
      if (GemFireXDUtils.TracePersistIndex) {
        GfxdIndexManager.traceIndex(
            "CreateIndexConstantAction::executeConstantAction "
                + " replay in progress=%s, is connection for remote ddl=%s",
            ddlReplayInProgress, lcc.isConnectionForRemoteDDL());
      }
      if (!this.skipLoadConglom || !regionInitialized) {
        if (!(ddlReplayInProgress && this.indexType
            .equals(GfxdConstants.GLOBAL_HASH_INDEX_TYPE))) {
          if (regionInitialized) {
            loadIndexConglomerate(lcc, tc, td, indexManager, zeroBasedBitSet,
                baseRows, indexRows, rl, true);
          }
          else if (indexManager != null) {
            indexManager.invalidateFor(lcc);
          }
        }
      }
      else if (this.indexType.equals(GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE)
          && regionInitialized) {
        // record the relevant data to enable loading the index later (after
        //   table creation)
        if (GemFireXDUtils.TraceConglomUpdate) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
              "For table " + td.getQualifiedName()
                  + " deferring index load after region's: " + this.indexName
                  + ", columns: " + RowUtil.toString(this.columnNames));
        }
        this.loadIndexData.add(new CreateTableConstantAction.LoadIndexData(
            this, zeroBasedBitSet, baseRows, indexRows, rl));
      }
    } finally {
      if (GemFireXDUtils.TraceConglom) {
        final MemConglomerate conglom = memStore.findConglomerate(ContainerKey
            .valueOf(ContainerHandle.TABLE_SEGMENT, this.conglomId));
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
            "CreateIndex: created indexConglom: " + conglom
                + ", with indexContainer: " + conglom.getGemFireContainer());
      }
      com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager
          .setIndexInitialized(cd);
    }
    if ((!("SYSSTAT".equalsIgnoreCase(this.schemaName)
		|| Misc.isSnappyHiveMetaTable(this.schemaName))
        || GemFireXDUtils.TraceConglom)) {
      SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_CONGLOM,
          "Created index with descriptor: " + cd);
    }
  }
	/* } */

  /**
   * This function determines which implementation is used for backing indexes
   * while table creation. There are several cases: I) The partition key is the
   * primary key: 1) create a Hash1Index for the primary key. 2) create a
   * GlobalHashIndex for the unique key. II) The partition key is a unique key
   * 1) create a Hash1Index for the unique key 2) create a GlobalHashIndex for
   * the primary key III) The partition key is a subset of the primary (or
   * unique) key create global hash index for primary key and unique key
   * 
   * Note: only two kinds of Index types (except the sorted map) are applied
   * here.
   * 
   * In case of hdfs enabled table with unique index we have to create global
   * index so that we don't violate unique constraint. 
   * 
   * @author yjing
   * @todo efficient?
   */
  String getConstraintIndexType(LanguageConnectionContext lcc, TableDescriptor td, int[] baseColumnPositions,
      Properties properties) throws StandardException {
    
    assert (this.isConstraint == true || this.unique);
    DistributionDescriptor distributionDesc = td.getDistributionDescriptor();
    assert distributionDesc != null;

    // constraint type
    int constraintType = -1;
    if (properties != null) {
      String value = properties.getProperty(
          GfxdConstants.PROPERTY_CONSTRAINT_TYPE, "-1");
      constraintType = Integer.parseInt(value);
    }
    
    if (constraintType == -1 && this.unique) {
      constraintType = DataDictionary.UNIQUE_CONSTRAINT;
    }
    
    int policy = distributionDesc.getPolicy();
    if (policy == DistributionDescriptor.REPLICATE
        || policy == DistributionDescriptor.NONE) {
      // [sumedh] use LocalHash1Index, if possible, even for local and
      // replicated tables
      if (constraintType == DataDictionary.PRIMARYKEY_CONSTRAINT) {
        // sort the baseColumnPositions due to serialized form in row
        GemFireXDUtils.sortColumns(baseColumnPositions, this.columnNames);
        return GfxdConstants.LOCAL_HASH1_INDEX_TYPE;
      }
      else {
        return GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE;
      }
    }

    int[] partitionColumnPositions = distributionDesc
        .getColumnPositionsSorted();
    switch (constraintType) {
      case DataDictionary.PRIMARYKEY_CONSTRAINT: {
        // sort the baseColumnPositions due to serialized form in row
        GemFireXDUtils.sortColumns(baseColumnPositions, this.columnNames);
        int val = GemFireXDUtils.setCompare(baseColumnPositions,
            partitionColumnPositions);
        if (val >= 0) {
          return GfxdConstants.LOCAL_HASH1_INDEX_TYPE;
        }
        else {
          return GfxdConstants.GLOBAL_HASH_INDEX_TYPE;
        }
      }
      case DataDictionary.UNIQUE_CONSTRAINT: {
        // [sumedh] use a local SortedMap index if unique key columns are
        // a superset of partitioning columns since that is enough to both
        // enforce uniqueness and get the routing object (latter will happen
        // automatically due to the partitioning columns being the same)
        int val = GemFireXDUtils.setCompare(baseColumnPositions,
            partitionColumnPositions);
        if (val >= 0) {
          // check if the table is hdfs and it has eviction criteria set
          
          if(isGlobalIndexRequiredForHDFS(td)) {
             return GfxdConstants.GLOBAL_HASH_INDEX_TYPE;
          }
          else {
            return GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE;            
          }
        }
        else {
          return GfxdConstants.GLOBAL_HASH_INDEX_TYPE;
        }
      }
      case DataDictionary.FOREIGNKEY_CONSTRAINT: {
        return GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE;
      }
    }
    SanityManager.THROWASSERT("unknown constraintType=" + constraintType);
    // never reached
    return null;
  }

// GemStone changes END
	// CLASS METHODS
	
	// /////////////////////////////////////////////////////////////////////
	//
	//	GETTERs called by CreateConstraint
	//
	///////////////////////////////////////////////////////////////////////
	ExecRow getIndexTemplateRow()
	{
		return indexTemplateRow;
	}

	/**
	 * Get the conglomerate number for the conglomerate that was
	 * created by this constant action.  Will return -1L if the
	 * constant action has not yet been executed.  This is used
	 * for updating conglomerate descriptors which share a
	 * conglomerate that has been dropped, in which case those
	 * "sharing" descriptors need to point to the newly-created
	 * conglomerate (the newly-created conglomerate replaces
	 * the dropped one).
	 */
	long getCreatedConglomNumber()
	{
		if (SanityManager.DEBUG)
		{
			if (conglomId == -1L)
			{
				SanityManager.THROWASSERT(
					"Called getCreatedConglomNumber() on a CreateIndex" +
					"ConstantAction before the action was executed.");
			}
		}

		return conglomId;
	}

	/**
	 * If the purpose of this constant action was to "replace" a
	 * dropped physical conglomerate, then this method returns the
	 * conglomerate number of the dropped conglomerate.  Otherwise
	 * this method will end up returning -1.
	 */
	long getReplacedConglomNumber()
	{
		return droppedConglomNum;
	}

	/**
	 * Get the UUID for the conglomerate descriptor that was created
	 * (or re-used) by this constant action.
	 */
	UUID getCreatedUUID()
	{
		return conglomerateUUID;
	}

// GemStone changes BEGIN
        /* (original code)
	/**
	 * Do necessary clean up (close down controllers, etc.) before throwing
	 * a statement exception.
	 *
	 * @param scan				ScanController for the heap
	 * @param indexController	ConglomerateController for the index
	 *
	private void statementExceptionCleanup(
					ScanController scan, 
					ConglomerateController indexController)
        throws StandardException
	{
		if (indexController != null)
		{
			indexController.close();
		}
		if (scan != null)
		{
			scan.close();
		}
	}

	/**
	 * Scan the base conglomerate and insert the keys into a sorter,
	 * returning a rowSource on the sorter. 
	 *
	 * @return RowSource on the sorted index keys.
	 *
	 * @exception StandardException					thrown on error
	 *
	private RowLocationRetRowSource loadSorter(ExecRow[] baseRows,
								               ExecIndexRow[] indexRows, 
								               TransactionController tc,
								               GroupFetchScanController scan,
								               long sortId,
								               RowLocation rl[])
		throws StandardException
	{
		SortController		sorter;
		long				rowCount = 0;

		sorter = tc.openSort(sortId);

		try
		{
			// Step through all the rows in the base table
			// prepare an array or rows for bulk fetch
			int bulkFetchSize = baseRows.length;

			if (SanityManager.DEBUG)
			{
				SanityManager.ASSERT(bulkFetchSize == indexRows.length, 
					"number of base rows and index rows does not match");
				SanityManager.ASSERT(bulkFetchSize == rl.length,
					"number of base rows and row locations does not match");
			}

			DataValueDescriptor[][] baseRowArray = new DataValueDescriptor[bulkFetchSize][];

			for (int i = 0; i < bulkFetchSize; i++)
				baseRowArray[i] = baseRows[i].getRowArray();

			// rl[i] and baseRowArray[i] and indexRows[i] are all tied up
			// beneath the surface.  Fetching the base row and row location
			// from the table scan will automagically set up the indexRow
			// fetchNextGroup will return how many rows are actually fetched.
			int bulkFetched = 0;

			while ((bulkFetched = scan.fetchNextGroup(baseRowArray, rl)) > 0)
			{
				for (int i = 0; i < bulkFetched; i++)
				{
					sorter.insert(indexRows[i]);
					rowCount++;
				}
			}

			/*
			** We've just done a full scan on the heap, so set the number
			** of rows so the optimizer will have an accurate count.
			*
			scan.setEstimatedRowCount(rowCount);
		}
		finally
		{
			sorter.completedInserts();
		}

		return new CardinalityCounter(tc.openSortRowSource(sortId));
	}
	*/

  public final void loadIndexConglomerate(LanguageConnectionContext lcc,
      final TransactionController tc, final TableDescriptor td,
      final GfxdIndexManager indexManager,
      final FormatableBitSet zeroBasedBitSet, final ExecRow[] baseRows,
      final ExecIndexRow[] indexRows, final RowLocation[] rl,
      boolean refreshIndexList) throws StandardException {
    if (GemFireXDUtils.TraceIndex) {
      GfxdIndexManager.traceIndex("CreateIndexConstantAction::"
          + "loadIndexConglomerate called for table=%s and index=%s",
          td.getQualifiedName(), this.indexName);
    }
    if (indexManager != null) {
      // populate from the base table

      if (refreshIndexList) {
        // Neeraj: block Index updates through GII path so that there is no
        // miss due to the gap between loading and refreshing of index list.
        // [sumedh] this is now done inside invalidateFor() method itself
        //indexManager.lockForIndexGII(true);

        // acquire the container GII lock for index before opening the scan;
        // invalidateFor() will refresh the index list at commit and also
        // release the locks at commit
        indexManager.invalidateFor(lcc);
      }

      // nothing else to be done for Hash1Index (#47204)
      if (this.indexType.equals(GfxdConstants.LOCAL_HASH1_INDEX_TYPE)) {
        return;
      }

      // for partitioned region use multiple threads to load into index
      // if the number of rows is large
      // don't use multiple threads for disk regions since disk contention will
      // actually lower the performance
      final GemFireContainer container = indexManager.getContainer();
      final GemFireTransaction tran = (GemFireTransaction)tc;
      boolean needLogging = false;
      try {
        // disable logging which will create MemOperations
        needLogging = tran.needLogging();
        tran.disableLogging();
        // Open a full scan on the base table again to avoid missing any
        // buckets that may have been GIIed before refreshIndexList was
        // invoked

        final boolean isLocalIndex = indexType
            .equals(GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE);
        // Use FULLSCAN mode for local indexes only
        // Use mode 0 (i.e. only fetch from primaries) for global indexes
        // as creating unique global index should not contain duplicate
        // values from secondary buckets (#47098)
        final GroupFetchScanController scan = tran.openGroupFetchScan(
            td.getHeapConglomerateId(),
            false, // hold
            (isLocalIndex ? GfxdConstants.SCAN_OPENMODE_FOR_FULLSCAN : 0),
            TransactionController.MODE_TABLE,
            TransactionController.ISOLATION_SERIALIZABLE, zeroBasedBitSet,
            (DataValueDescriptor[])null, // startKeyValue
            0, // not used when giving null start position
            null, // qualifier
            (DataValueDescriptor[])null, // stopKeyValue
            0); // not used when giving null stop position
        loadIndexConglomerate(baseRows, indexRows, lcc, tran, scan, conglomId,
            rl, container, indexManager);
      } catch (Throwable t) {
        SanityManager.DEBUG_PRINT("error:" + GfxdConstants.TRACE_INDEX,
            "unexpected exception in index load", t);
        if (t instanceof StandardException) {
          throw (StandardException)t;
        }
        else if (t instanceof RuntimeException) {
          throw (RuntimeException)t;
        }
        else if (t instanceof Error) {
          throw (Error)t;
        }
        else {
          throw StandardException.plainWrapException(t);
        }
      } finally {
        if (tran != null && needLogging) {
          tran.enableLogging();
        }
      }
    }
  }

  private void loadIndexConglomerate(ExecRow[] baseRows,
      ExecIndexRow[] indexRows, LanguageConnectionContext lcc,
      TransactionController tc, GroupFetchScanController scan, long conglomId,
      RowLocation[] rlArray, GemFireContainer container,
      GfxdIndexManager indexManager) throws StandardException {

    long rowCount = 0;
    final boolean byteStore = container.isByteArrayStore();

    final ConglomerateController cc = byteStore ? null : tc.openConglomerate(
        conglomId, false, TransactionController.OPENMODE_FORUPDATE,
        TransactionController.MODE_TABLE,
        TransactionController.ISOLATION_READ_COMMITTED);

    try {
      // Step through all the rows in the base table
      // prepare an array or rows for bulk fetch
      final int bulkFetchSize = baseRows.length;

      if (SanityManager.DEBUG) {
        SanityManager.ASSERT(bulkFetchSize == indexRows.length,
            "number of base rows and index rows does not match");
        SanityManager.ASSERT(bulkFetchSize == rlArray.length,
            "number of base rows and row locations does not match");
      }

      ExecRow[] baseRowArray = new ExecRow[bulkFetchSize];

      for (int i = 0; i < bulkFetchSize; i++) {
        if (byteStore) {
          baseRowArray[i] = container.newTemplateRow();
        }
        else {
          // by using ValueRows here, we force the DataValueDescriptors to be
          // filled in with setValue rather than replaced. This is necessary
          // for the "automagic" to happen (see next comment)
          baseRowArray[i] = baseRows[i]/*.getRowArray()*/;
        }
      }

      // rl[i] and baseRowArray[i] and indexRows[i] are all tied up
      // beneath the surface. Fetching the base row and row location
      // from the table scan will automagically set up the indexRow
      // fetchNextGroup will return how many rows are actually fetched.
      final boolean isLocalIndex = this.indexType
          .equals(GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE);
      int bulkFetched = 0;
      if (byteStore) {
        final MemConglomerate conglom = Misc.getMemStoreBooting()
            .findConglomerate(ContainerKey.valueOf(
                ContainerHandle.TABLE_SEGMENT, conglomId));
        final GemFireContainer indexContainer = conglom.getGemFireContainer();
        final boolean isPutDML = lcc != null && lcc.isSkipConstraintChecks();

        boolean loadIndex = true;
        final LocalRegion baseRegion = indexManager.getContainer().getRegion();
        if (GemFireXDUtils.TracePersistIndex) {
          GfxdIndexManager.traceIndex("CreateIndexConstantAction::"
              + "loadIndexConglomerate: create IRF = " + this.createIRF
              + ", region is: " + baseRegion);
        }
        if (isLocalIndex && !indexContainer.isGlobalIndex()) {
          if (this.createIRF && baseRegion.getDataPolicy().withPersistence()) {
            DiskStoreImpl dsImpl = baseRegion.getDiskStore();
            if (GemFireXDUtils.TracePersistIndex) {
              GfxdIndexManager.traceIndex("CreateIndexConstantAction::"
                  + "loadIndexConglomerate: for table: " + this.tableName
                  + " diskstore is: " + dsImpl + ", region: " + baseRegion);
            }
            if (dsImpl == null) {
              SanityManager.THROWASSERT("unexpected null disk store for "
                  + "persistent region: " + baseRegion);
            }
            rowCount = indexManager
                .writeNewIndexRecords(indexContainer, dsImpl);
            // index loaded in the above call itself
            loadIndex = false;
          }
          else {
            // for this case we just need to do parallel load and no writing
            // of index records to disk
            if (GemFireXDUtils.TraceQuery || GemFireXDUtils.TraceIndex) {
              GfxdIndexManager.traceIndex("CreateIndexConstantAction::"
                  + "loadIndexConglomerate: for table: " + this.tableName
                  + ", region: " + baseRegion);
            }
            rowCount = indexManager.loadLocalIndexRecords(indexContainer);
            loadIndex = false;
          }
        }

        if (loadIndex) {
          // just a template EntryEvent which is only used to get operation in
          // indexManager.insertIntoIndex; global index also uses the EventID to
          // get the "entryHash" in the index but for this case we will continue
          // to use entry's hash instead of EventID's hash
          final EntryEventImpl event = EntryEventImpl.create(null,
              Operation.CREATE, null, null, null, false, null);
          event.disallowOffHeapValues();
          final LocalRegion[] owners = new LocalRegion[bulkFetchSize];
          RowLocation rl;
          while ((bulkFetched = scan.fetchNextGroup(baseRowArray, rlArray,
              null, null, null, owners)) > 0) {
            int i = 0;

            for (; i < bulkFetched; i++) {

              rl = rlArray[i];
              if (!isLocalIndex && rl instanceof GlobalExecRowLocation) {
                rl = (RowLocation)((GlobalExecRowLocation)rl).getRegionEntry();
              }

              indexManager.insertIntoIndex(null, null, owners[i], event, false,
                  false, rl, baseRowArray[i], null, null, rl.getBucketID(),
                  indexContainer, null, isPutDML, Index.BOTH, false);

              rowCount++;
            }
            Misc.checkIfCacheClosing(null);
          }
        }
      }
      else {
        while ((bulkFetched = scan.fetchNextGroup(baseRowArray, rlArray, null,
            null, null, null)) > 0) {
          if (isLocalIndex) {
            // We need to transfer the RowLocation objects from row location
            // array to IndexRows array
            for (int i = 0; i < bulkFetched; ++i) {
              indexRows[i].setColumn(indexRows[i].nColumns(), rlArray[i]);
            }
          }
          for (int i = 0; i < bulkFetched; i++) {
            // we don't check for ROWISDUPLICATE here and expect the index
            // to throw back a proper exception
            if (GemFireXDUtils.TraceIndex) {
              GfxdIndexManager.traceIndex("CreateIndexConstantAction::"
                  + "loadIndexConglomerate inserting=%s for %s",
                  indexRows[i].getRowArray(), this);
            }
            cc.insert(indexRows[i].getRowArray());
            rowCount++;
          }
        }
      }

      /*
       * * We've just done a full scan on the heap, so set the number * of rows
       * so the optimizer will have an accurate count.
       */
      scan.setEstimatedRowCount(rowCount);
      if (!("SYSSTAT".equalsIgnoreCase(this.schemaName)
		  || Misc.isSnappyHiveMetaTable((this.schemaName))
          || GemFireXDUtils.TraceConglom)) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
            "CreateIndex: loaded " + rowCount + " rows in index: "
                + this.indexName);
      }
    } finally {
      if (cc != null) {
        cc.close();
      }
    }
  }

  @Override
  public String getSchemaName() {
    return this.schemaName;
  }

  @Override
  public String getTableName() {
    return this.tableName;
  }

  @Override
  public String getObjectName() {
    return this.indexName;
  }

  public void clearDroppedConglomerateNumber() {
    this.droppedConglomNum = -1L;
  }

  private boolean skipLoadConglom = false;

  private final Collection<CreateTableConstantAction.LoadIndexData> loadIndexData =
    new ArrayList<CreateTableConstantAction.LoadIndexData>();

  public void skipLoadConglomerate() {
    this.skipLoadConglom = true;
  }

  public final Collection<CreateTableConstantAction.LoadIndexData>
      getLoadIndexData() {
    return this.loadIndexData;
  }

  public void setThroughConstraint() {
    this.throughConstraint = true;
  }

  private boolean throughConstraint;
  
  private boolean isGlobalIndexRequiredForHDFS(TableDescriptor td) throws StandardException {
    GemFireContainer baseContainer = Misc.getMemStoreBooting().getContainer(
        ContainerKey.valueOf(ContainerHandle.TABLE_SEGMENT,
            td.getHeapConglomerateId()));
    RegionAttributes<?, ?> rattrs = baseContainer.getRegionAttributes();
    if (rattrs.getDataPolicy().withHDFS()) {
      LogWriterI18n log = InternalDistributedSystem.getLoggerI18n();
      boolean skip = rattrs.getCustomEvictionAttributes() == null
          && td.getDistributionDescriptor().getPersistence()
          && Version.GFXD_13.compareTo(GemFireXDUtils.getCurrentDDLVersion()) < 0;
          
      if (log.fineEnabled() && skip) {
        log.fine("Creating local index instead of global index for table " + td.getName());
      }
      return !skip;
    }
    return false;
  }
    
// GemStone changes END
}

