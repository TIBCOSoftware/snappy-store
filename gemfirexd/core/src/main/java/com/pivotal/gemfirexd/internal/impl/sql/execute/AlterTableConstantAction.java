/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.execute.AlterTableConstantAction

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

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;


/**
 *	This class  describes actions that are ALWAYS performed for an
 *	ALTER TABLE Statement at Execution time.
 *
 */

// GemStone changes BEGIN
import java.util.Arrays;

import com.gemstone.gemfire.cache.AttributesMutator;
import com.gemstone.gemfire.cache.CacheLoader;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EvictionAttributesMutator;
import com.gemstone.gemfire.cache.ExpirationAttributes;
import com.gemstone.gemfire.cache.PartitionResolver;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionAttributesImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.pivotal.gemfirexd.internal.catalog.DependableFinder;
import com.pivotal.gemfirexd.internal.catalog.IndexDescriptor;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.catalog.types.ReferencedColumnsDescriptorImpl;
import com.pivotal.gemfirexd.internal.catalog.types.StatisticsImpl;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.heap.MemHeap;
import com.pivotal.gemfirexd.internal.engine.ddl.PersistIdentityStart;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdAttributesMutator;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdSingleResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.management.GfxdResourceEvent;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireRegionSizeResultSet.RegionSizeMessage;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.io.StreamStorable;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.StatementType;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.CheckConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDescriptorGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DefaultDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DependencyDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ForeignKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.GenericDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.IndexLister;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.IndexRowGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.KeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ReferencedKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.StatisticsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TriggerDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecIndexRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.store.access.GroupFetchScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.Qualifier;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowLocationRetRowSource;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowSource;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.SortController;
import com.pivotal.gemfirexd.internal.iapi.store.access.SortObserver;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.internal.impl.sql.GenericStatement;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.DDColumnDependableFinder;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ColumnDefinitionNode;

public class AlterTableConstantAction extends DDLSingleTableConstantAction
    implements RowLocationRetRowSource {
  /**
   * holds the changes required to be done to the underlying GemFire region
   * using AttributesMutator
   */
  private GfxdAttributesMutator mutator;

  /**
   * Set the {@link GfxdAttributesMutator} object that holds the changes
   * required to be done to the underlying GemFire region.
   * 
   * @param mutator
   *          the {@link GfxdAttributesMutator} object
   */
  public void setAttributesMutator(GfxdAttributesMutator mutator) {
    // set the mutators only if not runnig as gfxd loner for hadoop
    if (!Misc.getMemStoreBooting().isHadoopGfxdLonerMode())
      this.mutator = mutator;
  }
  /*
class AlterTableConstantAction extends DDLSingleTableConstantAction
 implements RowLocationRetRowSource
{
*/
// GemStone changes END

    // copied from constructor args and stored locally.
    private	    SchemaDescriptor			sd;
    private	    String						tableName;
    private	    UUID						schemaId;
    private	    int							tableType;
    private	    ColumnInfo[]				columnInfo;
    private	    ConstraintConstantAction[]	constraintActions;
    private	    char						lockGranularity;
    private	    long						tableConglomerateId;
    private	    boolean					    compressTable;
    private     int						    behavior;
    private	    boolean					    sequential;
    private     boolean                     truncateTable;



    // Alter table compress and Drop column
    private     boolean					    doneScan;
    private     boolean[]				    needToDropSort;
    private     boolean[]				    validRow;
    private	    int						    bulkFetchSize = 16;
    private	    int						    currentCompressRow;
    private     int						    numIndexes;
    private     int						    rowCount;
    private     long					    estimatedRowCount;
    private     long[]					    indexConglomerateNumbers;
    private	    long[]					    sortIds;
    private     FormatableBitSet			indexedCols;
    private     ConglomerateController	    compressHeapCC;
    private     ExecIndexRow[]			    indexRows;
    private     ExecRow[]				    baseRow;
    private     ExecRow					    currentRow;
    private	    GroupFetchScanController    compressHeapGSC;
    private     IndexRowGenerator[]		    compressIRGs;
    private	    DataValueDescriptor[][]		baseRowArray;
    private     RowLocation[]			    compressRL;
    private     SortController[]		    sorters;
    private     int						    droppedColumnPosition;
    private     ColumnOrdering[][]		    ordering;
    private     int[][]		                collation;

    private	TableDescriptor 		        td;

    private     int rowLevelSecurityAction = ROW_LEVEL_SECURITY_UNCHANGED;

    // CONSTRUCTORS
    private LanguageConnectionContext lcc;
    private DataDictionary dd;
    private DependencyManager dm;
    private TransactionController tc;
    private Activation activation;
    private boolean isSet;

    public final static int ROW_LEVEL_SECURITY_ENABLED = 2;
	  public final static int ROW_LEVEL_SECURITY_DISABLED = 1;
	  public final static int ROW_LEVEL_SECURITY_UNCHANGED = 0;

	/**
	 *	Make the AlterAction for an ALTER TABLE statement.
	 *
	 *  @param sd			        descriptor for the table's schema.
	 *  @param tableName	        Name of table.
	 *	@param tableId		        UUID of table
	 *	@param tableConglomerateId	heap conglomerate number of table
	 *  @param tableType	        Type of table (e.g., BASE).
	 *  @param columnInfo	        Information on all the columns in the table.
	 *  @param constraintActions	ConstraintConstantAction[] for constraints
	 *  @param lockGranularity	    The lock granularity.
	 *	@param compressTable	    Whether or not this is a compress table
	 *	@param behavior		        drop behavior for dropping column
	 *	@param sequential	        If compress table/drop column, 
     *	                            whether or not sequential
	 *  @param truncateTable	    Whether or not this is a truncate table
	 *  @param rowLevelSecurityAction	    If Row Level Security is modified
	 */

	AlterTableConstantAction(
    SchemaDescriptor            sd,
    String			            tableName,
    UUID			            tableId,
    long			            tableConglomerateId,
    int				            tableType,
    ColumnInfo[]	            columnInfo,
    ConstraintConstantAction[]  constraintActions,
    char			            lockGranularity,
    boolean			            compressTable,
    boolean                                 isSet,
    int				            behavior,
    boolean			            sequential,
    boolean                     truncateTable, int rowLevelSecurityAction)
	{
		super(tableId);
		this.sd                     = sd;
		this.tableName              = tableName;
		this.tableConglomerateId    = tableConglomerateId;
		this.tableType              = tableType;
		this.columnInfo             = columnInfo;
		this.rowLevelSecurityAction = rowLevelSecurityAction;
		// GemStone changes BEGIN
    if (Misc.getMemStoreBooting().isHadoopGfxdLonerMode()
        && constraintActions != null) {
      // execute only the primary key constraints. Having the primary key
      //constraint allows us to parse the key of an entry
      ArrayList<ConstraintConstantAction> pkConstraints = new ArrayList<ConstraintConstantAction>(1);
      for(ConstraintConstantAction constraint : constraintActions) {
        if(constraint.getConstraintType() == DataDictionary.PRIMARYKEY_CONSTRAINT) {
          pkConstraints.add(constraint);
        }
      }
      this.constraintActions = pkConstraints.toArray(new ConstraintConstantAction[pkConstraints.size()]);
    } else {
      this.constraintActions = constraintActions;
    }
    // GemStone changes END
    
		this.lockGranularity        = lockGranularity;
		this.compressTable          = compressTable;
                this.isSet                  = isSet;
		this.behavior               = behavior;
		this.sequential             = sequential;
		this.truncateTable          = truncateTable;
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(sd != null, "schema descriptor is null");
		}
	}

	// OBJECT METHODS

	public	String	toString()
	{
		// Do not put this under SanityManager.DEBUG - it is needed for
		// error reporting.

		// we don't bother trying to print out the
		// schema because we don't have it until execution
		if(truncateTable)
			return "TRUNCATE TABLE " + tableName;
		else
			return "ALTER TABLE " + tableName;
	}

	// INTERFACE METHODS

	/**
	 *	This is the guts of the Execution-time logic for ALTER TABLE.
	 *
	 *	@see ConstantAction#executeConstantAction
	 *
	 * @exception StandardException		Thrown on failure
	 */
	public void	executeConstantAction(
    Activation activation)
        throws StandardException
	{
		LanguageConnectionContext   lcc = 
            activation.getLanguageConnectionContext();
		DataDictionary              dd = lcc.getDataDictionary();
		DependencyManager           dm = dd.getDependencyManager();
		       tc = lcc.getTransactionExecute();

		int							numRows = 0;
        boolean						tableScanned = false;

// GemStone changes BEGIN
        // TODO: TX: currently we cannot alter table with transactional data
        // (see #43299 for similar issues with indexes)
        final GemFireTransaction parentTran = lcc
            .getParentOfNestedTransactionExecute();
        final TXStateInterface tx;
        if (parentTran != null && (tx = parentTran.getSuspendedTXState()) !=
            null && tx.getProxy().isDirty()) {
          throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
              "Cannot alter/truncate table in the middle of transaction "
                  + "that has data changes "
                  + "(commit or rollback the transaction first)");
        }
// GemStone changes END
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

		// now do the real work

		// get an exclusive lock of the heap, to avoid deadlock on rows of
		// SYSCOLUMNS etc datadictionary tables and phantom table
		// descriptor, in which case table shape could be changed by a
		// concurrent thread doing add/drop column.

		// older version (or at target) has to get td first, potential deadlock
		if (tableConglomerateId == 0)
		{
			td = dd.getTableDescriptor(tableId);
			if (td == null)
			{
				throw StandardException.newException(
					SQLState.LANG_TABLE_NOT_FOUND_DURING_EXECUTION, tableName);
			}
			tableConglomerateId = td.getHeapConglomerateId();
		}

// GemStone changes BEGIN
		// check for any open ResultSets
		if (td == null) {
		  td = dd.getTableDescriptor(tableId);
		}
		lcc.verifyNoOpenResultSets(null, td, truncateTable
		    ? DependencyManager.TRUNCATE_TABLE
		    : DependencyManager.ALTER_TABLE);
// GemStone changes END
		lockTableForDDL(tc, tableConglomerateId, true);

		td = dd.getTableDescriptor(tableId);
		if (td == null)
		{
			throw StandardException.newException(
				SQLState.LANG_TABLE_NOT_FOUND_DURING_EXECUTION, tableName);
		}
// GemStone changes BEGIN
    //boolean dropPartCol = false;
    final boolean isHadoopLoner = Misc.getMemStoreBooting()
        .isHadoopGfxdLonerMode();
    Region<?, ?> region = Misc.getRegionForTableByPath(
        Misc.getFullTableName(this.td, lcc), false);
    if (region == null) {
      throw StandardException.newException(
          SQLState.LANG_TABLE_NOT_FOUND_DURING_EXECUTION, this.td
              .getQualifiedName());
    }
    this.numBuckets = getNumBucketsOrSize(region, lcc);
    RegionAttributes<?, ?> attrs = region.getAttributes();
    PartitionAttributesImpl pattrs = (PartitionAttributesImpl)attrs
        .getPartitionAttributes();
    GfxdPartitionResolver spr = null;
    if (pattrs != null) {
      PartitionResolver<?, ?> pr = pattrs.getPartitionResolver();
      if (pr instanceof GfxdPartitionResolver) {
        spr = (GfxdPartitionResolver)pr;
      }
    }
// GemStone changes END

		if (truncateTable)
			dm.invalidateFor(td, DependencyManager.TRUNCATE_TABLE, lcc);
		else
			dm.invalidateFor(td, DependencyManager.ALTER_TABLE, lcc);

		// Save the TableDescriptor off in the Activation
		activation.setDDLTableDescriptor(td);

		/*
		** If the schema descriptor is null, then we must have just read 
        ** ourselves in.  So we will get the corresponding schema descriptor 
        ** from the data dictionary.
		*/
		if (sd == null)
		{
			sd = getAndCheckSchemaDescriptor(dd, schemaId, "ALTER TABLE");
		}
		
		/* Prepare all dependents to invalidate.  (This is there chance
		 * to say that they can't be invalidated.  For example, an open
		 * cursor referencing a table/view that the user is attempting to
		 * alter.) If no one objects, then invalidate any dependent objects.
		 */
		if(truncateTable)
			dm.invalidateFor(td, DependencyManager.TRUNCATE_TABLE, lcc);
		else
			dm.invalidateFor(td, DependencyManager.ALTER_TABLE, lcc);

		// Are we working on columns?
		if (columnInfo != null)
		{
            boolean tableNeedsScanning = false;

			/* NOTE: We only allow a single column to be added within
			 * each ALTER TABLE command at the language level.  However,
			 * this may change some day, so we will try to plan for it.
			 */
			/* for each new column, see if the user is adding a non-nullable
			 * column.  This is only allowed on an empty table.
			 */
			for (int ix = 0; ix < columnInfo.length; ix++)
			{

				/* Is this new column non-nullable?  
				 * If so, it can only be added to an
				 * empty table if it does not have a default value.	
				 * We need to scan the table to find out how many rows 
				 * there are.
				 */
				if ((columnInfo[ix].action == ColumnInfo.CREATE) &&
					!(columnInfo[ix].dataType.isNullable()) &&
					(columnInfo[ix].defaultInfo == null) &&
					(columnInfo[ix].autoincInc == 0)
					)
				{
					tableNeedsScanning = true;
				}
// GemStone changes BEGIN
        int colAction = this.columnInfo[ix].action;
        // column modification not currently supported
        if (colAction == ColumnInfo.MODIFY_COLUMN_TYPE
            || colAction == ColumnInfo.MODIFY_COLUMN_DEFAULT_VALUE
            || colAction == ColumnInfo.MODIFY_COLUMN_CONSTRAINT
            || colAction == ColumnInfo.MODIFY_COLUMN_CONSTRAINT_NOT_NULL) {
          throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
              "Column modification not yet supported.");
        }
        if (colAction == ColumnInfo.DROP
            || colAction == ColumnInfo.MODIFY_COLUMN_TYPE) {
          // disallow PK column drop/alter completely since it may lead to
          // FK constraint drop in another table which may require
          // updating the partitioning/colocation etc. in the other table
          ConstraintDescriptorList cdl = dd.getConstraintDescriptors(this.td);
          for (int index = 0; index < cdl.size(); ++index) {
            ConstraintDescriptor cd = cdl.elementAt(index);
            if (cd.getConstraintType() == DataDictionary.PRIMARYKEY_CONSTRAINT) {
              List<String> columnNames = Arrays.asList(cd
                  .getColumnDescriptors().getColumnNames());
              if (columnNames.contains(this.columnInfo[ix].name)) {
                throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "PK COLUMN drop/alter in ALTER TABLE");
              }
            }
          }
        }
        if (spr != null && spr.isUsedInPartitioning(columnInfo[ix].name)) {
          if (colAction == ColumnInfo.DROP) {
            // cannot drop a partitioning column of a table with or without data
            throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
                "COLUMN drop of partitioning column '" + columnInfo[ix].name
                    + "' in ALTER TABLE with data or data history");
            //dropPartCol = true;
          }
        }
// GemStone changes END
			}

			// Scan the table if necessary
			if (tableNeedsScanning)
			{
				numRows = getSemiRowCount(tc);
				// Don't allow add of non-nullable column to non-empty table
				if (numRows > 0)
				{
					throw StandardException.newException(
                        SQLState.LANG_ADDING_NON_NULL_COLUMN_TO_NON_EMPTY_TABLE,
                        td.getQualifiedName());
				}
				tableScanned = true;
			}

			// for each related column, stuff system.column
			for (int ix = 0; ix < columnInfo.length; ix++)
			{
				ColumnDescriptorList cdl = new ColumnDescriptorList();

				/* If there is a default value, use it, otherwise use null */
				
				// Are we adding a new column or modifying a default?
				
				if (columnInfo[ix].action == ColumnInfo.CREATE)
				{
					addNewColumnToTable(activation, lcc, dd, tc, ix);
				}
				else if (columnInfo[ix].action == 
						 ColumnInfo.MODIFY_COLUMN_DEFAULT_RESTART ||
						 columnInfo[ix].action == 
						 ColumnInfo.MODIFY_COLUMN_DEFAULT_INCREMENT ||
						 columnInfo[ix].action == 
						 ColumnInfo.MODIFY_COLUMN_DEFAULT_VALUE)
				{
					modifyColumnDefault(activation, ix);
				}
				else if (columnInfo[ix].action == 
						 ColumnInfo.MODIFY_COLUMN_TYPE)
				{
					modifyColumnType(activation, ix,
					    false /* GemStoneAddition */);
				}
				else if (columnInfo[ix].action == 
						 ColumnInfo.MODIFY_COLUMN_CONSTRAINT)
				{
				  // GemStone changes BEGIN
				  // no need to apply constraint when it is running in loner mode
				  if (isHadoopLoner)
				    continue;
				  // GemStone changes END
					modifyColumnConstraint(
                        activation, columnInfo[ix].name, true);
				}
				else if (columnInfo[ix].action == 
						 ColumnInfo.MODIFY_COLUMN_CONSTRAINT_NOT_NULL)
				{
				  // GemStone changes BEGIN
				  // no need to apply constraint when it is running in loner mode
          if (isHadoopLoner)
            continue;
          // GemStone changes END
          if (!tableScanned)
					{
						tableScanned = true;
						numRows = getSemiRowCount(tc);
					}

					// check that the data in the column is not null
					String colNames[]  = new String[1];
					colNames[0]        = columnInfo[ix].name;
					boolean nullCols[] = new boolean[1];

					/* note validateNotNullConstraint returns true if the
					 * column is nullable
					 */
					if (validateNotNullConstraint(
                            colNames, nullCols, numRows, lcc, 
                            SQLState.LANG_NULL_DATA_IN_NON_NULL_COLUMN))
					{
						/* nullable column - modify it to be not null
						 * This is O.K. at this point since we would have
						 * thrown an exception if any data was null
						 */
						modifyColumnConstraint(
                            activation, columnInfo[ix].name, false);
					}
				}
// GemStone changes BEGIN
				else if (columnInfo[ix].action ==
				    ColumnInfo.MODIFY_COLUMN_AUTOINCREMENT) {
				  final long maxValue;
				  // get the max from LCC context if possible
				  Object context = lcc.getContextObject();
				  if (context != null &&
				      context instanceof PersistIdentityStart) {
				    PersistIdentityStart id =
				        (PersistIdentityStart)context;
				    id.checkMatchingColumn(getSchemaName(),
				        getTableName(), columnInfo[ix].name);
				    maxValue = id.getStartValue();
				    // done with the context, so clear it
				    lcc.setContextObject(null);
				  }
				  else {
				    // this should be only on query node
				    if (lcc.isConnectionForRemote()) {
				      SanityManager.THROWASSERT("Alter column "
				          + "add identity: unexpected call to "
				          + "maxValue on remote node");
				    }
				    if (Misc.initialDDLReplayInProgress()) {
				      SanityManager.THROWASSERT("Alter column "
				          + "add identity: unexpected call to "
				          + "maxValue during DDL replay");
				    }
				    // use max+1 as the new start value
				    maxValue = getColumnMax(activation, td,
				        columnInfo[ix].name,
				        columnInfo[ix].autoincInc,
				        columnInfo[ix].autoincStart);
				    // set maxValue in LCC so it gets shipped
				    // to other nodes
				    lcc.setContextObject(new PersistIdentityStart(
				        getSchemaName(), getTableName(),
				        columnInfo[ix].name, maxValue));
				  }
				  if (maxValue > 0) {
				    columnInfo[ix].autoincStart = maxValue + 1;
				  }
				  columnInfo[ix].
				    autoinc_create_or_modify_Start_Increment =
				      ColumnDefinitionNode.CREATE_AUTOINCREMENT;
				  modifyColumnType(activation, ix, true);
				}
// GemStone changes END
				else if (columnInfo[ix].action == ColumnInfo.DROP)
				{
					dropColumnFromTable(activation, ix);
				}
				else if (SanityManager.DEBUG)
				{
					SanityManager.THROWASSERT(
							  "Unexpected action in AlterTableConstantAction");
				}
			}
		}

        // adjust dependencies on user defined types
        adjustUDTDependencies( lcc, dd, td, columnInfo, false );

		/* Create/Drop any constraints */
		if (constraintActions != null)
		{
			for (int conIndex = 0; 
                 conIndex < constraintActions.length; 
                 conIndex++)
			{
				ConstraintConstantAction cca = constraintActions[conIndex];

				if (cca instanceof CreateConstraintConstantAction)
				{
					int constraintType = cca.getConstraintType();

					/* Some constraint types require special checking:
					 *   Check		 - table must be empty, for now
					 *   Primary Key - table cannot already have a primary key
					 */
					switch (constraintType)
					{
						case DataDictionary.PRIMARYKEY_CONSTRAINT:

							// Check to see if a constraint of the same type 
                            // already exists
							ConstraintDescriptorList cdl = 
                                dd.getConstraintDescriptors(td);

							if (cdl.getPrimaryKey() != null)
							{
								throw StandardException.newException(
                                    SQLState.LANG_ADD_PRIMARY_KEY_FAILED1, 
                                    td.getQualifiedName());
							}

							if (!tableScanned)
							{
								tableScanned = true;
								numRows = getSemiRowCount(tc);
							}
// GemStone changes BEGIN
              // GemFireXD cannot handle PK create with data (#40712)
              if (this.numBuckets > 0) {
                numRows = getSemiRowCount(tc);
                if (numRows > 0) {
                  throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
                      "PRIMARY KEY create in ALTER TABLE with data");
                }
              }
              else {
                this.isPkAdd = true;
              }
// GemStone changes END

							break;

						case DataDictionary.CHECK_CONSTRAINT:

							if (!tableScanned)
							{
								tableScanned = true;
								numRows = getSemiRowCount(tc);
							}
							if (numRows > 0)
							{
								/*
								** We are assuming that there will only be one 
								** check constraint that we are adding, so it
								** is ok to do the check now rather than try
								** to lump together several checks.	
								*/
								ConstraintConstantAction.validateConstraint(
                                    cca.getConstraintName(),
                                    ((CreateConstraintConstantAction)cca).getConstraintText(),
                                    td,
                                    lcc, true);
							}
							break;
							
						case DataDictionary.FOREIGNKEY_CONSTRAINT:
						      if (region.getAttributes().getCustomEvictionAttributes() != null) {
						        //For cheetah, foreign key constraint not supported with custom eviction for HDFS table
						        //defect #49367
						        throw StandardException.newException(
						            SQLState.FOREIGN_KEY_CONSTRAINT_NOT_SUPPORTED_WITH_EVICTION);
						      }
					}
				}
				else
				{
					if (SanityManager.DEBUG)
					{
						if (!(cca instanceof DropConstraintConstantAction))
						{
							SanityManager.THROWASSERT(
                                "constraintActions[" + conIndex + 
                                "] expected to be instanceof " + 
                                "DropConstraintConstantAction not " +
                                cca.getClass().getName());
						}
					}
				}

				constraintActions[conIndex].executeConstantAction(activation);
			}
		}

		// Are we changing the lock granularity?
		if (lockGranularity != '\0')
		{
			if (SanityManager.DEBUG)
			{
				if (lockGranularity != 'T' &&
					lockGranularity != 'R')
				{
					SanityManager.THROWASSERT(
						"lockGranularity expected to be 'T'or 'R', not " + 
                        lockGranularity);
				}
			}

			// update the TableDescriptor
			td.setLockGranularity(lockGranularity);
			// update the DataDictionary
			dd.updateLockGranularity(td, sd, lockGranularity, tc);
		}

		// Are we doing a compress table?
		if (compressTable)
		{
			compressTable(activation);
		}

		if (this.rowLevelSecurityAction != ROW_LEVEL_SECURITY_UNCHANGED) {
			// update the TableDescriptor
			td.setRowLevelSecurityEnabledFlag(this.rowLevelSecurityAction
					== AlterTableConstantAction.ROW_LEVEL_SECURITY_ENABLED);
			// update the DataDictionary
			dd.updateLockGranularity(td, sd, lockGranularity, tc);
		}

		// Are we doing a truncate table?
		if (truncateTable)
		{
			truncateTable(activation);
		}
// GemStone changes BEGIN
    // refresh TableDescriptor since it is likely to have changed
    this.td = dd.getTableDescriptor(this.td.getUUID());
    // Change the region attributes using AttributesMutator
    // if specified
    // fix for bug #39499; two cases when this can be null
    // a) COMPRESS, b) truncate; these two call init() methods
    // with different parameters not having mutator object -- see
    // init methods in AlterTableNode
    final GemFireContainer gfc = GemFireContainer.getGemFireContainer(td,
        (GemFireTransaction)tc);
    if (this.mutator != null && !this.truncateTable) {
      CacheLoader<?, ?> ldr = attrs.getCacheLoader();
      if (ldr != null) {
        GfxdCacheLoader gfxdldr = (GfxdCacheLoader)ldr;
        gfxdldr.setTableDetails(td);
      }
      // [sumedh] Cannot change partitioning or add/change colocation
      // criteria if there are buckets around even if there is no data
      // since the buckets may violate the new colocation criteria.
      // However, the resulting behaviour will be hard for end-user to
      // understand as well as tricky to implement. So if there are buckets
      // around then disallow any change in partitioning or colocation.
      // So also do not allow the partitioning columns to be changed once
      // there are any buckets rather than depending on data in table.
      // Need to refresh the columnInfo in partitioning resolvers in any case
      // regardless of the buckets
      // [sumedh] Cannot base the decision to change colocation or partitioning
      // based on numBuckets which is dynamic. During initial DDL replay this
      // can very well be zero due to delayed region initializations.
      if (/* this.numBuckets > 0 && */ !isSet) {
        if (spr != null) {
          DistributionDescriptor distributionDesc = gfc
              .getDistributionDescriptor();
          // Update the distribution descriptor's column positions
          // even if there's data in the table
          distributionDesc.resolveColumnPositions(this.td);
          spr.setColumnInfo(this.td, activation);
          // for the special case of no-PK to PK constraint added with no data,
          // may need to refresh indexes since it may go from GLOBAL->LOCAL
          if (this.numBuckets == 0 && this.isPkAdd && distributionDesc
              .getPolicy() == DistributionDescriptor.PARTITIONBYGENERATEDKEY) {
            // update the partitioning columns in DistributionDescriptor as per
            // those in resolver
            String[] partCols = spr.getColumnNames();
            if (partCols != null && partCols.length > 0) {
              distributionDesc.setPartitionColumnNames(partCols);
            }
            refreshAllKeyConstraints(activation, dd, distributionDesc);
          }
        }
      }
      /*
      else if (!isSet) {
        if (spr != null) {
          DistributionDescriptor distributionDesc = spr
              .getDistributionDescriptor();
          // for default partitioning or drop of partitioning column
          // refresh the distribution descriptor
          if (dropPartCol || (spr instanceof GfxdPartitionByExpressionResolver &&
              ((GfxdPartitionByExpressionResolver)spr).isDefaultPartitioning())) {
            // when partitioning changes then the existing indexes may need
            // to be updated (e.g. type may change from LOCAL_HASH to GLOBAL)
            String[] oldCols = distributionDesc.getPartitionColumnNames();
            if (oldCols != null) {
              oldCols = oldCols.clone(); // don't change original
              Arrays.sort(oldCols);
            }
            DistributionDefinitionNode ddn = new DistributionDefinitionNode(
                distributionDesc);
            ddn.setPolicy(DistributionDescriptor.NONE);
            ddn.setTableDescriptor(this.td);
            ddn.setContextManager(lcc.getContextManager());
            spr.setMasterTable(null);
            distributionDesc = ddn.bind(this.td, dd);
            // re-fetch the partition resolver since it may have changed
            spr = (GfxdPartitionResolver)pattrs.getPartitionResolver();
            spr.setDistributionDescriptor(distributionDesc);
            this.td.setDistributionDescriptor(distributionDesc);
            String colocatedTable = distributionDesc.getColocateTableName();
            final PartitionedRegion pr = (PartitionedRegion)region;
            if (colocatedTable != null) {
              if (!colocatedTable.equals(pr.getColocatedWith())) {
                pr.setColocatedWith(colocatedTable);
              }
            }
            else if (pr.getColocatedWith() != null) {
              pr.setColocatedWith(null);
            }
            // refresh the indexes if the partitioning columns have changed
            // since the type of index (local/global) may need to change
            String[] newCols = distributionDesc.getPartitionColumnNames();
            if (newCols != null) {
              newCols = newCols.clone(); // don't change original
              Arrays.sort(newCols);
            }
            if (!Arrays.equals(oldCols, newCols)) {
              refreshAllKeyConstraints(activation, dd, distributionDesc);
            }
          }
          distributionDesc.resolveColumnPositions(this.td);
          spr.setTableDetails(this.td, null);
          spr.setColumnInfo(this.td, activation);
        }
      }
      */
      int evictionLimit = this.mutator.getEvictionMaximum();
      int expirationKind = this.mutator.getExpirationKind();
      if (evictionLimit >= 0
          || expirationKind != GfxdAttributesMutator.EXPIRE_NONE) {
        AttributesMutator<?, ?> attrsMutator = region.getAttributesMutator();
        ExpirationAttributes expirationAttrs = this.mutator
            .getExpirationAttributes();
        switch (expirationKind) {
          case GfxdAttributesMutator.EXPIRE_REGION_TIMETOLIVE:
            attrsMutator.setRegionTimeToLive(expirationAttrs);
            break;
          case GfxdAttributesMutator.EXPIRE_REGION_IDLETIME:
            attrsMutator.setRegionIdleTimeout(expirationAttrs);
            break;
          case GfxdAttributesMutator.EXPIRE_ENTRY_TIMETOLIVE:
            attrsMutator.setEntryTimeToLive(expirationAttrs);
            break;
          case GfxdAttributesMutator.EXPIRE_ENTRY_IDLETIME:
            attrsMutator.setEntryIdleTimeout(expirationAttrs);
            break;
        }
        if (evictionLimit >= 0) {
          EvictionAttributesMutator evictMutator = attrsMutator
              .getEvictionAttributesMutator();
          //while creating table, maximum memory for Eviction is reset to localMaxMemory of Partitioned table 
          //if certain conditions are met (details in AbstractRegion.setAttributes()). Same should happen here also. 
          //Fix for defect #50112.
          //TODO: Ideally this check should happen in EvictionAttributesImpl.setMaximum so the callers don't have to repeat it.
          if (region.getAttributes().getPartitionAttributes() != null 
        		  && region.getAttributes().getEvictionAttributes() != null 
        		  && region.getAttributes().getEvictionAttributes().getAlgorithm().isLRUMemory()
        		  && region.getAttributes().getPartitionAttributes().getLocalMaxMemory() != 0 
        		  && evictionLimit != region.getAttributes().getPartitionAttributes().getLocalMaxMemory()) {
        	  evictMutator.setMaximum(region.getAttributes().getPartitionAttributes().getLocalMaxMemory());
          } else {
        	  evictMutator.setMaximum(evictionLimit);
          }
        }
      }
      // [yogesh]
      if (this.mutator.isAlterGatewaySender()) {
        Iterator<ColumnDescriptor> itr = td.getColumnDescriptorList()
            .iterator();
        while (itr.hasNext()) {
          ColumnDescriptor cd = itr.next();
          if (cd.isAutoincAlways()
              && (cd.getType().getJDBCTypeId() == Types.INTEGER)) {
            throw StandardException.newException(SQLState.LANG_ADDING_GATEWAYSENDER_ON_INT_IDENTITY_COLUMN, td.getName(),
                cd.getColumnName());
          }
        }
    
        LocalRegion r = (LocalRegion)region;
        boolean sendRequiresNotification = false;
        Set<String> oldSenderIds = r.getGatewaySenderIds();
        Set<String> senderIds = this.mutator.getGatewaySenderIds();
        Set<String> activeSenderIds = new HashSet<String>();
        for (GatewaySender sender : r.getCache().getGatewaySenders()) {
          activeSenderIds.add(sender.getId());
        }
        // check for new senders
        for (String id : senderIds) {
          if (!oldSenderIds.contains(id)) {
            r.addGatewaySenderId(id);
            if (activeSenderIds.contains(id)) {
              sendRequiresNotification = true;
            }
          }
        }
        for (String id : oldSenderIds) {
          if (!senderIds.contains(id)) {
            r.removeGatewaySenderId(id);
            if (activeSenderIds.contains(id)) {
              sendRequiresNotification = true;
            }
          }
        }
        // Send notification that a sender has been added
        // The other members hosting this region are informed
        // that this member has a sender running for this region.
        if (sendRequiresNotification) {
          r.distributeUpdatedProfileOnSenderChange();
        }

        final GemFireContainer container = GemFireContainer
            .getGemFireContainer(td, (GemFireTransaction)tc);
        final GemFireCacheImpl cache = Misc.getGemFireCache();
        for (String id : container.getRegion().getGatewaySenderIds()) {
          if (cache.getGatewaySender(id) == null) {
            StringBuilder sb = new StringBuilder();
            for(com.gemstone.gemfire.cache.wan.GatewaySender s : cache
                .getAllGatewaySenders()) {
              sb.append(s.getId()).append(",");
            }
            // add warning if this is a datastore for the region
            if (!container.isAccessorForRegion()) {
              activation.addWarning(StandardException.newWarning(
                    SQLState.LANG_NO_LOCAL_GATEWAYSENDER_ASYNCEVENTLISTENER,
                    new Object[] { id, container.getQualifiedTableName(),
                    "GatewaySenders", sb.toString() }, null));
            }
          }
        }
      }

      if (this.mutator.isAlterAsyncEventListener()) {
        LocalRegion r = (LocalRegion)region;
        boolean sendRequiresNotification = false;
        Set<String> oldAsyncQueueIds = r.getAsyncEventQueueIds();
        Set<String> asyncQueueIds = this.mutator.getAsyncEventQueueIds();
        Set<String> activeAsyncQueueIds = new HashSet<String>();

        for (AsyncEventQueue asyncQueue : r.getCache().getAsyncEventQueues()) {
          activeAsyncQueueIds.add(asyncQueue.getId());
        }
        // check for new asyncQueues
        for (String id : asyncQueueIds) {
          if (!oldAsyncQueueIds.contains(id)) {
            r.addAsyncEventQueueId(id);
            if (activeAsyncQueueIds.contains(id)) {
              sendRequiresNotification = true;
            }
          }
        }
        for (String id : oldAsyncQueueIds) {
          if (!asyncQueueIds.contains(id)) {
            r.removeAsyncEventQueueId(id);
            if (activeAsyncQueueIds.contains(id)) {
              sendRequiresNotification = true;
            }
          }
        }
        // Send notification that a listener has been added
        // The other members hosting this region are informed
        // that this member has a listener running for this region.
        if (sendRequiresNotification) {
          r.distributeUpdatedProfileOnSenderChange();
        }

        final GemFireContainer container = GemFireContainer
            .getGemFireContainer(td, (GemFireTransaction)tc);
        final GemFireCacheImpl cache = Misc.getGemFireCache();
        for (Object id : container.getRegion().getAsyncEventQueueIds()) {
          if (cache.getAsyncEventQueue((String)id) == null) {
            StringBuilder sb = new StringBuilder();
            for(com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue q : cache
                .getAsyncEventQueues()) {
              sb.append(q.getId()).append(",");
            }
            // add warning if this is a datastore for the region
            if (!container.isAccessorForRegion()) {
              activation.addWarning(StandardException.newWarning(
                  SQLState.LANG_NO_LOCAL_GATEWAYSENDER_ASYNCEVENTLISTENER,
                  new Object[] { id, container.getQualifiedTableName(),
                    "AsyncEventQueues", sb.toString() }, null));
            }
          }
        }
      }

      if (this.mutator.isAlterCustomEviction()) {
        try {
          region.getAttributesMutator().setCustomEvictionAttributes(
              this.mutator.getCustomEvictionStart(),
              this.mutator.getCustomEvictionInterval());
        } catch (IllegalArgumentException iae) {
          throw StandardException.newException(
              SQLState.CUSTOM_EVICTION_NOT_SET_FOR_TABLE, iae,
              td.getQualifiedName());
        }
      }
//GemStone changes END
    }
    
  //mbean changes for alter table definition
    GemFireContainer container = GemFireContainer.getGemFireContainer(td, (GemFireTransaction) tc);
    if (container.isApplicationTable()) {          
      GfxdManagementService.handleEvent(GfxdResourceEvent.TABLE__ALTER, new Object[] {container});
    }
  }

  private boolean refreshAllDone;
  private int numBuckets;
  private boolean isPkAdd;

  private String getNewIndexName(String name) {
    if (name != null) {
      int delimIndex = name.lastIndexOf(GfxdConstants.INDEX_NAME_DELIMITER);
      int count = 0;
      if (delimIndex > 0) {
        try {
          count = Integer.parseInt(name.substring(delimIndex
              + GfxdConstants.INDEX_NAME_DELIMITER.length()));
          name = name.substring(0, delimIndex);
        } catch (NumberFormatException ex) {
          count = 0;
        }
      }
      name = name + GfxdConstants.INDEX_NAME_DELIMITER
          + Integer.toString(++count);
    }
    return name;
  }

  private void refreshAllKeyConstraints(Activation activation,
      DataDictionary dd, DistributionDescriptor distributionDesc)
      throws StandardException {
    if (this.refreshAllDone) {
      return;
    }
    this.refreshAllDone = true;
    ConstraintDescriptorList constraints = dd.getConstraintDescriptors(this.td);
    for (int index = 0; index < constraints.size(); ++index) {
      ConstraintDescriptor constraint = constraints.elementAt(index);
      int constraintType = constraint.getConstraintType();
      if (constraintType == DataDictionary.UNIQUE_CONSTRAINT
          || constraintType == DataDictionary.PRIMARYKEY_CONSTRAINT
          || constraintType == DataDictionary.FOREIGNKEY_CONSTRAINT) {
        ConglomerateDescriptor conglom = ((KeyConstraintDescriptor)constraint)
            .getIndexConglomerateDescriptor(dd);
        Properties createProps = new Properties();
        createProps.setProperty(GfxdConstants.PROPERTY_CONSTRAINT_TYPE, Integer
            .toString(constraintType));
        String name = conglom.getConglomerateName();
        name = getNewIndexName(name);
        createProps.setProperty(GfxdConstants.PROPERTY_TABLE_NAME, name);
        String fullIndexName = this.sd.getSchemaName() + "."
            + conglom.getConglomerateName();
        DropIndexConstantAction dropAction = new DropIndexConstantAction(
            fullIndexName, conglom.getConglomerateName(), this.td.getName(),
            this.sd.getSchemaName(), this.td.getUUID(),
            this.td.getHeapConglomerateId(), false);
        CreateIndexConstantAction createAction = new CreateIndexConstantAction(
            conglom, this.td, createProps);
        createAction.clearDroppedConglomerateNumber();
        createAction.skipLoadConglomerate();
        distributionDesc.resolveColumnPositions(this.td);
        activation.setDDLTableDescriptor(this.td);
        dropAction.executeConstantAction(activation);
        this.td = dd.getTableDescriptor(this.td.getUUID());
        activation.setDDLTableDescriptor(this.td);
        createAction.executeConstantAction(activation);
        UUID newIndexUUID = createAction.getCreatedUUID();
        // We need to update the Foreign Key constraint descriptor so that the
        // constraint is updated with the newly generated index UUID.
        // This is Fix for Bug 43006.
        if (constraintType == DataDictionary.FOREIGNKEY_CONSTRAINT) {
          ForeignKeyConstraintDescriptor fkcd = new ForeignKeyConstraintDescriptor(
              (ForeignKeyConstraintDescriptor)constraint, newIndexUUID);
          dd.dropConstraintDescriptor(constraint, tc);
          dd.addConstraintDescriptor(fkcd, tc);
        }
        this.td = dd.getTableDescriptor(this.td.getUUID());
      }
    }
  }
	/* } */
// GemStone changes END

	/**
	 * Workhorse for adding a new column to a table.
	 *
	 * @param   ix 			the index of the column specfication in the ALTER 
	 *						statement-- currently we allow only one.
	 * @exception StandardException 	thrown on failure.
	 */
	private void addNewColumnToTable(
    Activation                  activation, 
    LanguageConnectionContext   lcc,
    DataDictionary              dd,
    TransactionController       tc,
    int                         ix) 
	        throws StandardException
	{
		ColumnDescriptor columnDescriptor   = 
			td.getColumnDescriptor(columnInfo[ix].name);
		DataValueDescriptor storableDV;
		int                     colNumber   = td.getMaxColumnID() + ix;
		DataDescriptorGenerator ddg         = dd.getDataDescriptorGenerator();

		/* We need to verify that the table does not have an existing
		 * column with the same name before we try to add the new
		 * one as addColumnDescriptor() is a void method.
		 */
		if (columnDescriptor != null)
		{
			throw 
				StandardException.newException(
                   SQLState.LANG_OBJECT_ALREADY_EXISTS_IN_OBJECT,
                   columnDescriptor.getDescriptorType(),
                   columnInfo[ix].name,
                   td.getDescriptorType(),
                   td.getQualifiedName());
		}
// GemStone changes BEGIN
		// don't allow adding an autoincrement column if there is data
		// in the table
		if (columnInfo[ix].autoincInc != 0 && getSemiRowCount(tc) > 0) {
		  throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
		      "Autoincrement column add in table having data");
		}
// GemStone changes END

		if (columnInfo[ix].defaultValue != null)
			storableDV = columnInfo[ix].defaultValue;
		else
			storableDV = columnInfo[ix].dataType.getNull();

		// Add the column to the conglomerate.(Column ids in store are 0-based)
		tc.addColumnToConglomerate(
            td.getHeapConglomerateId(), 
            colNumber, 
            storableDV, 
            columnInfo[ix].dataType.getCollationType());

		UUID defaultUUID = columnInfo[ix].newDefaultUUID;

		/* Generate a UUID for the default, if one exists
		 * and there is no default id yet.
		 */
		if (columnInfo[ix].defaultInfo != null &&
			defaultUUID == null)
		{
			defaultUUID = dd.getUUIDFactory().createUUID();
		}

		// Add the column to syscolumns. 
		// Column ids in system tables are 1-based
		columnDescriptor = 
            new ColumnDescriptor(
                   columnInfo[ix].name,
                   colNumber + 1,
                   columnInfo[ix].dataType,
                   columnInfo[ix].defaultValue,
                   columnInfo[ix].defaultInfo,
                   td,
                   defaultUUID,
                   columnInfo[ix].autoincStart,
                   columnInfo[ix].autoincInc,
                   columnInfo[ix].isGeneratedByDefault
                   );

		dd.addDescriptor(columnDescriptor, td,
						 DataDictionary.SYSCOLUMNS_CATALOG_NUM, false, tc);

		// now add the column to the tables column descriptor list.
		td.getColumnDescriptorList().add(columnDescriptor);

// GemStone changes BEGIN
                // Add the default column value to ColumnDescriptor for use
		// by RowFormatter if the default value is as a DefaultInfo
		ColumnDescriptor cdWithDefault = columnDescriptor;
		if (columnDescriptor.getDefaultInfo() != null) {
		  cdWithDefault = columnDescriptor.cloneObject();
		  updateNewColumnToDefault(activation,
		      columnInfo[ix].name,
		      columnInfo[ix].defaultInfo.getDefaultText(),
		      lcc, cdWithDefault);
		}
		// already ensured that there is no data in table for
		// autoincrement column add
		/* (original code)
		if (columnDescriptor.isAutoincrement())
		{
			updateNewAutoincrementColumn(activation, columnInfo[ix].name,
										 columnInfo[ix].autoincStart,
										 columnInfo[ix].autoincInc);
		}

		// Update the new column to its default, if it has a non-null default
		if (columnDescriptor.hasNonNullDefault())
		{
			updateNewColumnToDefault(activation,
								columnInfo[ix].name,
								columnInfo[ix].defaultInfo.getDefaultText(),
								lcc);
		}
		*/	
// GemStone changes END

		// Update SYSCOLPERMS table which tracks the permissions granted
		// at columns level. The sytem table has a bit map of all the columns
		// in the user table to help determine which columns have the 
		// permission granted on them. Since we are adding a new column,
		// that bit map needs to be expanded and initialize the bit for it
		// to 0 since at the time of ADD COLUMN, no permissions have been
		// granted on that new column.
		//
		dd.updateSYSCOLPERMSforAddColumnToUserTable(td.getUUID(), tc);
// GemStone changes BEGIN
		// now for adding a column, need to update the schema version
		// creating a new tableInfo, update all the old tableInfos
		// with the new column information
		GemFireContainer gfc = GemFireContainer.getGemFireContainer(
		    td, (GemFireTransaction)tc);
		gfc.scheduleAddColumn(cdWithDefault, lcc);
// GemStone changes END
	}

	/**
	 * Workhorse for dropping a column from a table.
	 *
	 * This routine drops a column from a table, taking care
	 * to properly handle the various related schema objects.
	 * 
	 * The syntax which gets you here is:
	 * 
	 *   ALTER TABLE tbl DROP [COLUMN] col [CASCADE|RESTRICT]
	 * 
	 * The keyword COLUMN is optional, and if you don't
	 * specify CASCADE or RESTRICT, the default is CASCADE
	 * (the default is chosen in the parser, not here).
	 * 
	 * If you specify RESTRICT, then the column drop should be
	 * rejected if it would cause a dependent schema object
	 * to become invalid.
	 * 
	 * If you specify CASCADE, then the column drop should
	 * additionally drop other schema objects which have
	 * become invalid.
	 * 
	 * You may not drop the last (only) column in a table.
	 * 
	 * Schema objects of interest include:
	 *  - views
	 *  - triggers
	 *  - constraints
	 *    - check constraints
	 *    - primary key constraints
	 *    - foreign key constraints
	 *    - unique key constraints
	 *    - not null constraints
	 *  - privileges
	 *  - indexes
	 *  - default values
	 * 
	 * Dropping a column may also change the column position
	 * numbers of other columns in the table, which may require
	 * fixup of schema objects (such as triggers and column
	 * privileges) which refer to columns by column position number.
	 * 
	 * Indexes are a bit interesting. The official SQL spec
	 * doesn't talk about indexes; they are considered to be
	 * an imlementation-specific performance optimization.
	 * The current Derby behavior is that:
	 *  - CASCADE/RESTRICT doesn't matter for indexes
	 *  - when a column is dropped, it is removed from any indexes
	 *    which contain it.
	 *  - if that column was the only column in the index, the
	 *    entire index is dropped. 
	 *
     * @param   activation  the current activation
	 * @param   ix 			the index of the column specfication in the ALTER 
	 *						statement-- currently we allow only one.
	 * @exception StandardException 	thrown on failure.
	 */
	private void dropColumnFromTable(Activation activation,
									 int ix) 
	        throws StandardException
	{
		LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
		DataDictionary dd = lcc.getDataDictionary();
		DependencyManager dm = dd.getDependencyManager();
		TransactionController tc = lcc.getTransactionExecute();


		ColumnDescriptor columnDescriptor = 
			td.getColumnDescriptor(columnInfo[ix].name);

		// We already verified this in bind, but do it again
		if (columnDescriptor == null)
		{
			throw 
				StandardException.newException(
                    SQLState.LANG_COLUMN_NOT_FOUND_IN_TABLE, 
                    columnInfo[ix].name,
                    td.getQualifiedName());
		}

		DataDescriptorGenerator ddg = dd.getDataDescriptorGenerator();
		ColumnDescriptorList tab_cdl = td.getColumnDescriptorList();
		int size = tab_cdl.size();

		// can NOT drop a column if it is the only one in the table
		if (size == 1)
		{
			throw StandardException.newException(
                    SQLState.LANG_PROVIDER_HAS_DEPENDENT_OBJECT,
                    dm.getActionString(DependencyManager.DROP_COLUMN),
                    "THE *LAST* COLUMN " + columnInfo[ix].name,
                    "TABLE",
                    td.getQualifiedName() );
		}

		droppedColumnPosition = columnDescriptor.getPosition();
		boolean cascade = (behavior == StatementType.DROP_CASCADE);

		FormatableBitSet toDrop = new FormatableBitSet(size + 1);
		toDrop.set(droppedColumnPosition);
		td.setReferencedColumnMap(toDrop);

		dm.invalidateFor(td, 
                        (cascade ? DependencyManager.DROP_COLUMN
                                 : DependencyManager.DROP_COLUMN_RESTRICT),
                        lcc);
					
		// If column has a default we drop the default and any dependencies
		if (columnDescriptor.getDefaultInfo() != null)
		{
			dm.clearDependencies(
                lcc, columnDescriptor.getDefaultDescriptor(dd));
		}

		// need to deal with triggers if has referencedColumns
		GenericDescriptorList tdl = dd.getTriggerDescriptors(td);
		Enumeration descs = tdl.elements();
		while (descs.hasMoreElements())
		{
			TriggerDescriptor trd = (TriggerDescriptor) descs.nextElement();
			int[] referencedCols = trd.getReferencedCols();
			if (referencedCols == null)
				continue;
			int refColLen = referencedCols.length, j;
			boolean changed = false;
			for (j = 0; j < refColLen; j++)
			{
				if (referencedCols[j] > droppedColumnPosition)
                {
					changed = true;
                }
				else if (referencedCols[j] == droppedColumnPosition)
				{
					if (cascade)
					{
                        trd.drop(lcc);
						activation.addWarning(
							StandardException.newWarning(
                                SQLState.LANG_TRIGGER_DROPPED, 
                                trd.getName(), td.getName()));
					}
					else
					{	// we'd better give an error if don't drop it,
						// otherwsie there would be unexpected behaviors
						throw StandardException.newException(
                            SQLState.LANG_PROVIDER_HAS_DEPENDENT_OBJECT,
                            dm.getActionString(DependencyManager.DROP_COLUMN),
                            columnInfo[ix].name, "TRIGGER",
                            trd.getName() );
					}
					break;
				}
			}

			// change triggers to refer to columns in new positions
			if (j == refColLen && changed)
			{
				dd.dropTriggerDescriptor(trd, tc);
				for (j = 0; j < refColLen; j++)
				{
					if (referencedCols[j] > droppedColumnPosition)
						referencedCols[j]--;
				}
				dd.addDescriptor(trd, sd,
								 DataDictionary.SYSTRIGGERS_CATALOG_NUM,
								 false, tc);
			}
		}

		ConstraintDescriptorList csdl = dd.getConstraintDescriptors(td);
		int csdl_size = csdl.size();

		ArrayList newCongloms = new ArrayList();

		// we want to remove referenced primary/unique keys in the second
		// round.  This will ensure that self-referential constraints will
		// work OK.
		int tbr_size = 0;
		ConstraintDescriptor[] toBeRemoved = new ConstraintDescriptor[csdl_size];

		// let's go downwards, don't want to get messed up while removing
		for (int i = csdl_size - 1; i >= 0; i--)
		{
		  ConstraintDescriptor cd = csdl.elementAt(i);
		  // GemStone changes BEGIN
		  // Do not modify the in-memory referencedColumns of this descriptor for check constraints
		  // This is a reference to the ReferencedColumnsDescriptorImpl, if 
		  // it is changed, UNDO logic will no longer have the 'original' row for the coming DROP
		  // Only change the catalog tables with clones of the original row's data
		  int[] referencedColumns = (int[])cd.getReferencedColumns().clone();
		  // Original code int[] referencedColumns = cd.getReferencedColumns();
		  // GemStone changes END
		  int numRefCols = referencedColumns.length, j;
		  boolean changed = false;
		  for (j = 0; j < numRefCols; j++)
		  {
		    if (referencedColumns[j] > droppedColumnPosition)
		      changed = true;
		    if (referencedColumns[j] == droppedColumnPosition)
		      break;
		  }
		  if (j == numRefCols)			// column not referenced
		  {
                    // GemStone changes BEGIN
                    if (changed) {
                        // We are operating on a clone of the referencedColumns
                        // So can safely modify them before doing DROP/CREATE of check constraint
                        for (j = 0; j < numRefCols; j++) {
                          if (referencedColumns[j] > droppedColumnPosition)
                            referencedColumns[j]--;
                        }
                        if (cd instanceof CheckConstraintDescriptor)  {
                          dd.dropConstraintDescriptor(cd, tc);
                          ((CheckConstraintDescriptor)cd).setReferencedColumnsDescriptor(
                              new ReferencedColumnsDescriptorImpl(referencedColumns));
                          dd.addConstraintDescriptor(cd, tc);
                        }
                        else {
                          // Do not drop/create the PK/UK constraints
                          // Changing index conglomerate referencedcolumn bits is done
                          // in updateRefCols() later on
                          cd.setReferencedColumns(referencedColumns);
                          cd.refreshDescriptors(tc);
                        }
                    }
		    /*
			if ((cd instanceof CheckConstraintDescriptor) && changed)
			{
				dd.dropConstraintDescriptor(cd, tc);
				for (j = 0; j < numRefCols; j++)
				{
					if (referencedColumns[j] > droppedColumnPosition)
						referencedColumns[j]--;
				}
				((CheckConstraintDescriptor) cd).setReferencedColumnsDescriptor(new ReferencedColumnsDescriptorImpl(referencedColumns));
				dd.addConstraintDescriptor(cd, tc);
			}
		    */
		    // GemStone changes END
		    continue;
		  }

		  if (! cascade)
		  {
		    // Reject the DROP COLUMN, because there exists a constraint
		    // which references this column.
		    //
		    throw StandardException.newException(
                        SQLState.LANG_PROVIDER_HAS_DEPENDENT_OBJECT,
                        dm.getActionString(DependencyManager.DROP_COLUMN),
                        columnInfo[ix].name, "CONSTRAINT",
                        cd.getConstraintName() );
		  }

		  if (cd instanceof ReferencedKeyConstraintDescriptor)
		  {
		    // restrict will raise an error in invalidate if referenced
		    toBeRemoved[tbr_size++] = cd;
		    continue;
		  }

		  // drop now in all other cases
		  dm.invalidateFor(cd, DependencyManager.DROP_CONSTRAINT,
		      lcc);

			dropConstraint(cd, td, newCongloms, activation, lcc, true);
			activation.addWarning(
                  StandardException.newWarning(SQLState.LANG_CONSTRAINT_DROPPED,
				cd.getConstraintName(), td.getName()));
		}

		for (int i = tbr_size - 1; i >= 0; i--)
		{
			ConstraintDescriptor cd = toBeRemoved[i];
			dropConstraint(cd, td, newCongloms, activation, lcc, false);

			activation.addWarning(
                StandardException.newWarning(SQLState.LANG_CONSTRAINT_DROPPED,
                cd.getConstraintName(), td.getName()));

			if (cascade)
			{
				ConstraintDescriptorList fkcdl = dd.getForeignKeys(cd.getUUID());
				for (int j = 0; j < fkcdl.size(); j++)
				{
					ConstraintDescriptor fkcd = 
                        (ConstraintDescriptor) fkcdl.elementAt(j);

					dm.invalidateFor(fkcd,
									DependencyManager.DROP_CONSTRAINT,
									lcc);

					dropConstraint(fkcd, td,
						newCongloms, activation, lcc, true);

					activation.addWarning(
                        StandardException.newWarning(
                            SQLState.LANG_CONSTRAINT_DROPPED,
						    fkcd.getConstraintName(), 
                            fkcd.getTableDescriptor().getName()));
				}
			}

			dm.invalidateFor(cd, DependencyManager.DROP_CONSTRAINT, lcc);
			dm.clearDependencies(lcc, cd);
		}

// GemStone changes BEGIN
		// Update any indexes' referenced column bitmaps to note the
		// removed column.
		// Check constraints are already modified above, but
		// primary keys and unique constraints need to be changed here
		// This logic was previously in compressTable's call to
		// getAffectedIndexes() but all other logic from that function
		// has been moved elsewhere in ALTER TABLE constant action
		updateRefColsForIndexes(cascade, activation,
		    columnInfo[ix].name);
// GemStone changes END

		/* If there are new backing conglomerates which must be
		 * created to replace a dropped shared conglomerate
		 * (where the shared conglomerate was dropped as part
		 * of a "drop constraint" call above), then create them
		 * now.  We do this *after* dropping all dependent
		 * constraints because we don't want to waste time
		 * creating a new conglomerate if it's just going to be
		 * dropped again as part of another "drop constraint".
		 */
		createNewBackingCongloms(
			newCongloms, (long[])null, activation, dd);

        /*
         * The work we've done above, specifically the possible
         * dropping of primary key, foreign key, and unique constraints
         * and their underlying indexes, may have affected the table
         * descriptor. By re-reading the table descriptor here, we
         * ensure that the compressTable code is working with an
         * accurate table descriptor. Without this line, we may get
         * conglomerate-not-found errors and the like due to our
         * stale table descriptor.
         */
		td = dd.getTableDescriptor(tableId);

// GemStone changes BEGIN
    // refresh all indices if a KeyConstraint has been dropped since the
    // index types may need to change
    if (tbr_size > 0) {
      refreshAllKeyConstraints(activation, dd, this.td
          .getDistributionDescriptor());
    }
    // currently GemFireXD does not allow for data to be present in case of
    // drop column so no need to compress the table; the compressTable() method
    // does not work for GemFireXD in any case since it tries to create a new
    // table with same properties and drop old one which does not work since
    // new table needs to have the same region path as old one
		/* compressTable(activation); */

// GemStone changes END

		// drop the column from syscolumns 
		dd.dropColumnDescriptor(td.getUUID(), columnInfo[ix].name, tc);
		ColumnDescriptor[] cdlArray = 
            new ColumnDescriptor[size - columnDescriptor.getPosition()];

		// For each column in this table with a higher column position,
		// drop the entry from SYSCOLUMNS, but hold on to the column
		// descriptor and reset its position to adjust for the dropped
		// column. Then, re-add all those adjusted column descriptors
		// back to SYSCOLUMNS
		//
		for (int i = columnDescriptor.getPosition(), j = 0; i < size; i++, j++)
		{
			ColumnDescriptor cd = (ColumnDescriptor) tab_cdl.elementAt(i);
			dd.dropColumnDescriptor(td.getUUID(), cd.getColumnName(), tc);
			cd.setPosition(i);
			if (cd.isAutoincrement())
			{
				cd.setAutoinc_create_or_modify_Start_Increment(
						ColumnDefinitionNode.CREATE_AUTOINCREMENT);
			}
			cdlArray[j] = cd;
		}
		dd.addDescriptorArray(cdlArray, td,
							  DataDictionary.SYSCOLUMNS_CATALOG_NUM, false, tc);

		List deps = dd.getProvidersDescriptorList(td.getObjectID().toString());
		for (Iterator depsIterator = deps.listIterator(); 
             depsIterator.hasNext();)
		{
			DependencyDescriptor depDesc = 
                (DependencyDescriptor) depsIterator.next();

			DependableFinder finder = depDesc.getProviderFinder();
			if (finder instanceof DDColumnDependableFinder)
			{
				DDColumnDependableFinder colFinder = 
                    (DDColumnDependableFinder) finder;
				FormatableBitSet oldColumnBitMap = 
                    new FormatableBitSet(colFinder.getColumnBitMap());
				FormatableBitSet newColumnBitMap = 
                    new FormatableBitSet(oldColumnBitMap);
				newColumnBitMap.clear();
				int bitLen = oldColumnBitMap.getLength();
				for (int i = 0; i < bitLen; i++)
				{
					if (i < droppedColumnPosition && oldColumnBitMap.isSet(i))
						newColumnBitMap.set(i);
					if (i > droppedColumnPosition && oldColumnBitMap.isSet(i))
						newColumnBitMap.set(i - 1);
				}
				if (newColumnBitMap.equals(oldColumnBitMap))
					continue;
				dd.dropStoredDependency(depDesc, tc);
				colFinder.setColumnBitMap(newColumnBitMap.getByteArray());
				dd.addDescriptor(depDesc, null,
								 DataDictionary.SYSDEPENDS_CATALOG_NUM,
								 true, tc);
			}
		}
		// Adjust the column permissions rows in SYSCOLPERMS to reflect the
		// changed column positions due to the dropped column:
		dd.updateSYSCOLPERMSforDropColumn(td.getUUID(), tc, columnDescriptor);
// GemStone changes BEGIN
		// drop the column from container too if there is data
		GemFireContainer gfc = GemFireContainer.getGemFireContainer(
		    td, (GemFireTransaction)tc);
		gfc.scheduleDropColumn(columnDescriptor.getPosition(), lcc);
// GemStone changes END
	}

	private void modifyColumnType(Activation activation,
// GemStone changes BEGIN
								  int ix,
								  boolean setAutoInc)
								  /* (original code)
								  int ix)
								  */
// GemStone changes END
		throws StandardException						  
	{
		LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
		DataDictionary dd = lcc.getDataDictionary();
		TransactionController tc = lcc.getTransactionExecute();

		ColumnDescriptor columnDescriptor = 
			td.getColumnDescriptor(columnInfo[ix].name),
			newColumnDescriptor = null;
		newColumnDescriptor = 
			new ColumnDescriptor(columnInfo[ix].name,
									columnDescriptor.getPosition(),
									columnInfo[ix].dataType,
									columnDescriptor.getDefaultValue(),
									columnDescriptor.getDefaultInfo(),
									td,
									columnDescriptor.getDefaultUUID(),
								    columnInfo[ix].autoincStart,
								    columnInfo[ix].autoincInc,
								    columnInfo[ix].isGeneratedByDefault
									);
		

// GemStone changes BEGIN
		if (setAutoInc) {
		  newColumnDescriptor.setAutoinc_create_or_modify_Start_Increment(
		      (int)columnInfo[ix].autoinc_create_or_modify_Start_Increment);
		}
// GemStone changes END

		// Update the ColumnDescriptor with new default info
		dd.dropColumnDescriptor(td.getUUID(), columnInfo[ix].name, tc);
		dd.addDescriptor(newColumnDescriptor, td,
						 DataDictionary.SYSCOLUMNS_CATALOG_NUM, false, tc);
	}	

	/**
	 * Workhorse for modifying column level constraints. 
	 * Right now it is restricted to modifying a null constraint to a not null
	 * constraint.
	 */
	private void modifyColumnConstraint(Activation activation, 
										String colName,
										boolean nullability)
		throws StandardException								
	{
		LanguageConnectionContext lcc = 
            activation.getLanguageConnectionContext();

		DataDictionary dd = lcc.getDataDictionary();
		TransactionController tc = lcc.getTransactionExecute();

		ColumnDescriptor columnDescriptor = 
			td.getColumnDescriptor(colName),
			newColumnDescriptor = null;
        
        // Get the type and change the nullability
		DataTypeDescriptor dataType =
            columnDescriptor.getType().getNullabilityType(nullability);

        //check if there are any unique constraints to update
        ConstraintDescriptorList cdl = dd.getConstraintDescriptors(td);
        int columnPostion = columnDescriptor.getPosition();
        for (int i = 0; i < cdl.size(); i++) 
        {
            ConstraintDescriptor cd = cdl.elementAt(i);
            if (cd.getConstraintType() == DataDictionary.UNIQUE_CONSTRAINT) 
            {
                ColumnDescriptorList columns = cd.getColumnDescriptors();
                for (int count = 0; count < columns.size(); count++) 
                {
                    if (columns.elementAt(count).getPosition() != columnPostion)
                        break;

                    //get backing index
                    ConglomerateDescriptor desc = 
                        td.getConglomerateDescriptor(cd.getConglomerateId());

                    //check if the backing index was created when the column
                    //not null ie is backed by unique index
                    if (!desc.getIndexDescriptor().isUnique())
                        break;

                    // replace backing index with a unique when not null index.
                    recreateUniqueConstraintBackingIndexAsUniqueWhenNotNull(
                        desc, td, activation, lcc);
                }
            }
        }

		newColumnDescriptor = 
			 new ColumnDescriptor(colName,
									columnDescriptor.getPosition(),
									dataType,
									columnDescriptor.getDefaultValue(),
									columnDescriptor.getDefaultInfo(),
									td,
									columnDescriptor.getDefaultUUID(),
									columnDescriptor.getAutoincStart(),
									columnDescriptor.getAutoincInc(),
									columnDescriptor.isGeneratedByDefault());
        
		// Update the ColumnDescriptor with new default info
		dd.dropColumnDescriptor(td.getUUID(), colName, tc);
		dd.addDescriptor(newColumnDescriptor, td,
						 DataDictionary.SYSCOLUMNS_CATALOG_NUM, false, tc);		
	}
	/**
	 * Workhorse for modifying the default value of a column.
	 * 
	 * @param 		activation 		activation
	 * @param       ix 		the index of the column specfication in the ALTER 
	 *						statement-- currently we allow only one.
	 * @exception	StandardException, thrown on error.
	 */
	private void modifyColumnDefault(Activation activation,
									 int ix)
			throws StandardException						 
	{
		LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
		DataDictionary dd = lcc.getDataDictionary();
		DependencyManager dm = dd.getDependencyManager();
		TransactionController tc = lcc.getTransactionExecute();

		ColumnDescriptor columnDescriptor = 
			td.getColumnDescriptor(columnInfo[ix].name);
		DataDescriptorGenerator ddg = dd.getDataDescriptorGenerator();
		int columnPosition = columnDescriptor.getPosition();

		// Clean up after the old default, if non-null
		if (columnDescriptor.hasNonNullDefault())
		{
			// Invalidate off of the old default
			DefaultDescriptor defaultDescriptor = new DefaultDescriptor(dd, columnInfo[ix].oldDefaultUUID, 
										 td.getUUID(), columnPosition);

		
			dm.invalidateFor(defaultDescriptor, DependencyManager.MODIFY_COLUMN_DEFAULT, lcc);
		
			// Drop any dependencies
			dm.clearDependencies(lcc, defaultDescriptor);
		}

		UUID defaultUUID = columnInfo[ix].newDefaultUUID;

		/* Generate a UUID for the default, if one exists
		 * and there is no default id yet.
		 */
		if (columnInfo[ix].defaultInfo != null &&
			defaultUUID == null)
		{	
			defaultUUID = dd.getUUIDFactory().createUUID();
		}
		
		/* Get a ColumnDescriptor reflecting the new default */
		columnDescriptor = new ColumnDescriptor(
												   columnInfo[ix].name,
												   columnPosition,
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

		// Update the ColumnDescriptor with new default info
		dd.dropColumnDescriptor(td.getUUID(), columnInfo[ix].name, tc);
		dd.addDescriptor(columnDescriptor, td,
						 DataDictionary.SYSCOLUMNS_CATALOG_NUM, false, tc);
	
		if (columnInfo[ix].action == ColumnInfo.MODIFY_COLUMN_DEFAULT_INCREMENT)
		{
			// adding an autoincrement default-- calculate the maximum value 
			// of the autoincrement column.
		  /*(original code)
                  long maxValue = getColumnMax(activation, td, columnInfo[ix].name,
                      columnInfo[ix].autoincInc,
                      columnInfo[ix].autoincStart);
                  dd.setAutoincrementValue(tc, td.getUUID(), columnInfo[ix].name, maxValue, true);*/
                  
		// GemStone changes BEGIN		  
		        if(!columnInfo[ix].isGeneratedByDefault){
			long maxValue = getColumnMax(activation, td, columnInfo[ix].name,
										 columnInfo[ix].autoincInc,
										 columnInfo[ix].autoincStart);
	                     dd.setAutoincrementValue(tc, td.getUUID(), columnInfo[ix].name,
	                            maxValue, true);
		        }else {
		          dd.setAutoincrementValue(tc, td.getUUID(), columnInfo[ix].name,
                              columnInfo[ix].autoincStart, false);
		        }
		// GemStone changes END		        
		} else if (columnInfo[ix].action == ColumnInfo.MODIFY_COLUMN_DEFAULT_RESTART)
		{
			dd.setAutoincrementValue(tc, td.getUUID(), columnInfo[ix].name,
					 columnInfo[ix].autoincStart, false);
			// GemStone changes BEGIN
			// yogesh : restart the identity column value in case of alter column
			final GemFireTransaction tran = (GemFireTransaction)tc;
			GemFireContainer container = GemFireContainer
			    .getGemFireContainer(td, tran);
			container.removeIdentityRegionEntries(tran);
			// GemStone changes END
		} 
		// else we are simply changing the default value
	}



    /**
     * routine to process compress table or ALTER TABLE <t> DROP COLUMN <c>;
     * <p>
     * Uses class level variable "compressTable" to determine if processing
     * compress table or drop column:
     *     if (!compressTable)
     *         must be drop column.
     * <p>
     * Handles rebuilding of base conglomerate and all necessary indexes.
     **/
	private void compressTable(
    Activation activation)
		throws StandardException
	{
		long					newHeapConglom;
		Properties				properties = new Properties();
		RowLocation				rl;

		this.lcc        = activation.getLanguageConnectionContext();
		this.dd         = lcc.getDataDictionary();
		this.dm         = dd.getDependencyManager();
		this.tc         = lcc.getTransactionExecute();
		this.activation = activation;

		if (SanityManager.DEBUG)
		{
			if (lockGranularity != '\0')
			{
				SanityManager.THROWASSERT(
					"lockGranularity expected to be '\0', not " + 
                    lockGranularity);
			}
			SanityManager.ASSERT(! compressTable || columnInfo == null,
				"columnInfo expected to be null");
			SanityManager.ASSERT(constraintActions == null,
				"constraintActions expected to be null");
		}

		ExecRow emptyHeapRow  = td.getEmptyExecRow();
        int[]   collation_ids = td.getColumnCollationIds();

		compressHeapCC = 
            tc.openConglomerate(
                td.getHeapConglomerateId(),
                false,
                TransactionController.OPENMODE_FORUPDATE,
                TransactionController.MODE_TABLE,
                TransactionController.ISOLATION_SERIALIZABLE);

		// invalidate any prepared statements that depended on this table 
        // (including this one), this fixes problem with threads that start up 
        // and block on our lock, but do not see they have to recompile their 
        // plan.  We now invalidate earlier however they still might recompile
        // using the old conglomerate id before we commit our DD changes.
		//
		dm.invalidateFor(td, DependencyManager.COMPRESS_TABLE, lcc);

		rl = compressHeapCC.newRowLocationTemplate();

		// Get the properties on the old heap
		compressHeapCC.getInternalTablePropertySet(properties);
		compressHeapCC.close();
		compressHeapCC = null;

		// Create an array to put base row template
		baseRow = new ExecRow[bulkFetchSize];
		baseRowArray = new DataValueDescriptor[bulkFetchSize][];
		validRow = new boolean[bulkFetchSize];

		/* Set up index info */
		getAffectedIndexes(activation);

		// Get an array of RowLocation template
		compressRL = new RowLocation[bulkFetchSize];
		indexRows  = new ExecIndexRow[numIndexes];
		if (!compressTable)
		{
            // must be a drop column, thus the number of columns in the
            // new template row and the collation template is one less.
			ExecRow newRow = 
                activation.getExecutionFactory().getValueRow(
                    emptyHeapRow.nColumns() - 1);

            int[]   new_collation_ids = new int[collation_ids.length - 1];

			for (int i = 0; i < newRow.nColumns(); i++)
			{
				newRow.setColumn(
                    i + 1, 
                    i < droppedColumnPosition - 1 ?
                        emptyHeapRow.getColumn(i + 1) :
                        emptyHeapRow.getColumn(i + 1 + 1));

                new_collation_ids[i] = 
                    collation_ids[
                        (i < droppedColumnPosition - 1) ? i : (i + 1)];
			}

			emptyHeapRow = newRow;
			collation_ids = new_collation_ids;
		}
		setUpAllSorts(emptyHeapRow, rl);

		// Start by opening a full scan on the base table.
		openBulkFetchScan(td.getHeapConglomerateId());

		// Get the estimated row count for the sorters
		estimatedRowCount = compressHeapGSC.getEstimatedRowCount();

		// Create the array of base row template
		for (int i = 0; i < bulkFetchSize; i++)
		{
			// create a base row template
			baseRow[i] = td.getEmptyExecRow();
			baseRowArray[i] = baseRow[i].getRowArray();
			compressRL[i] = compressHeapGSC.newRowLocationTemplate();
		}


		newHeapConglom = 
            tc.createAndLoadConglomerate(
                "heap",
                emptyHeapRow.getRowArray(),
                null, //column sort order - not required for heap
                collation_ids,
                properties,
                TransactionController.IS_DEFAULT,
                this,
                (long[]) null);

		closeBulkFetchScan();

		// Set the "estimated" row count
		ScanController compressHeapSC = tc.openScan(
							newHeapConglom,
							false,
							TransactionController.OPENMODE_FORUPDATE,
							TransactionController.MODE_TABLE,
                            TransactionController.ISOLATION_SERIALIZABLE,
							(FormatableBitSet) null,
							(DataValueDescriptor[]) null,
							0,
							(Qualifier[][]) null,
							(DataValueDescriptor[]) null,
							0
// GemStone changes BEGIN 
							, null);
// GemStone changes END
		
		compressHeapSC.setEstimatedRowCount(rowCount);

		compressHeapSC.close();
		compressHeapSC = null; // RESOLVE DJD CLEANUP

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

		// Update all indexes
		if (compressIRGs.length > 0)
		{
			updateAllIndexes(newHeapConglom, dd);
		}

		/* Update the DataDictionary
		 * RESOLVE - this will change in 1.4 because we will get
		 * back the same conglomerate number
		 */
		// Get the ConglomerateDescriptor for the heap
		long oldHeapConglom       = td.getHeapConglomerateId();
		ConglomerateDescriptor cd = 
            td.getConglomerateDescriptor(oldHeapConglom);

		// Update sys.sysconglomerates with new conglomerate #
		dd.updateConglomerateDescriptor(cd, newHeapConglom, tc);

		// Drop the old conglomerate
		tc.dropConglomerate(oldHeapConglom);
		cleanUp();
	}
	
	/* 
	 * TRUNCATE TABLE  TABLENAME; (quickly removes all the rows from table and
	 * it's correctponding indexes).
	 * Truncate is implemented by dropping the existing conglomerates(heap,indexes) and recreating a
	 * new ones  with the properties of dropped conglomerates. Currently Store
	 * does not have support to truncate existing conglomerated until store
	 * supports it , this is the only way to do it.
	 * Error Cases: Truncate error cases same as other DDL's statements except
	 * 1)Truncate is not allowed when the table is references by another table.
	 * 2)Truncate is not allowed when there are enabled delete triggers on the table.
	 * Note: Because conglomerate number is changed during recreate process all the statements will be
	 * marked as invalide and they will get recompiled internally on their next
	 * execution. This is okay because truncate makes the number of rows to zero
	 * it may be good idea to recompile them becuase plans are likely to be
	 * incorrect. Recompile is done internally by Derby, user does not have
	 * any effect.
	 */
	private void truncateTable(Activation activation)
		throws StandardException
	{
		ExecRow					emptyHeapRow;
		long					newHeapConglom;
		Properties				properties = new Properties();
		RowLocation				rl;
		this.lcc = activation.getLanguageConnectionContext();
		this.dd = lcc.getDataDictionary();
		this.dm = dd.getDependencyManager();
		this.tc = lcc.getTransactionExecute();
		this.activation = activation;

		if (SanityManager.DEBUG)
		{
			if (lockGranularity != '\0')
			{
				SanityManager.THROWASSERT(
					"lockGranularity expected to be '\0', not " + lockGranularity);
			}
			SanityManager.ASSERT(columnInfo == null,
				"columnInfo expected to be null");
			SanityManager.ASSERT(constraintActions == null,
				 "constraintActions expected to be null");
		}

// GemStone changes BEGIN
		// skip truncate during DDL replay
		if (Misc.initialDDLReplayInProgress()) {
		  return;
		}
// GemStone changes END

		//truncate table is not allowed if there are any tables referencing it.
		//except if it is self referencing.
		ConstraintDescriptorList cdl = dd.getConstraintDescriptors(td);
		for(int index = 0; index < cdl.size(); index++)
		{
			ConstraintDescriptor cd = cdl.elementAt(index);
			if (cd instanceof ReferencedKeyConstraintDescriptor)
			{
				ReferencedKeyConstraintDescriptor rfcd = (ReferencedKeyConstraintDescriptor) cd;
// GemStone changes BEGIN
				// disallow truncate only if child has rows
				ForeignKeyConstraintDescriptor fkcd;
				// Get a full list of referencing keys,
				ConstraintDescriptorList fkcdl = rfcd.getForeignKeyConstraints(
				    ConstraintDescriptor.ENABLED);
				for (Object fcd : fkcdl) {
				  if (fcd instanceof ForeignKeyConstraintDescriptor) {
				    fkcd = (ForeignKeyConstraintDescriptor)fcd;
				    // skip self-referencing FKs
				    if(!fkcd.getTableId().equals(rfcd.getTableId())) {
				      GemFireContainer refContainer = ((MemHeap)
				          ((GemFireTransaction)tc).findExistingConglomerate(
				              fkcd.getTableDescriptor()
				              .getHeapConglomerateId())).getGemFireContainer();
				      if (refContainer.getTotalNumRows(lcc) > 0) {
				        throw StandardException.newException(SQLState
				            .LANG_NO_TRUNCATE_ON_FK_REFERENCE_TABLE,
				            td.getQualifiedName(),
				            fkcd.getTableDescriptor().getQualifiedName());
				      }
				    }
				  }
				}
				/* (original code)
				if(rfcd.hasNonSelfReferencingFK(ConstraintDescriptor.ENABLED))
				{
					throw StandardException.newException(SQLState.LANG_NO_TRUNCATE_ON_FK_REFERENCE_TABLE,td.getName());
				}
				*/
// GemStone changes END
			}
// GemStone changes BEGIN
			// also need to invalidate parent table, if any
			else if (cd instanceof ForeignKeyConstraintDescriptor) {
			  final ForeignKeyConstraintDescriptor fkcd =
			    (ForeignKeyConstraintDescriptor)cd;
			  this.dm.invalidateFor(fkcd.getReferencedConstraint()
			      .getTableDescriptor(),
			      DependencyManager.TRUNCATE_TABLE, this.lcc);
			}
// GemStone changes END
		}

		//truncate is not allowed when there are enabled DELETE triggers
		GenericDescriptorList tdl = dd.getTriggerDescriptors(td);
		Enumeration descs = tdl.elements();
		while (descs.hasMoreElements())
		{
			TriggerDescriptor trd = (TriggerDescriptor) descs.nextElement();
			if (trd.listensForEvent(TriggerDescriptor.TRIGGER_EVENT_DELETE) &&
				trd.isEnabled())
			{
				throw
					StandardException.newException(SQLState.LANG_NO_TRUNCATE_ON_ENABLED_DELETE_TRIGGERS,
												   td.getName(),trd.getName());	
			}
		}

// GemStone changes BEGIN
		// just clear the table and return
		GemFireTransaction tran = (GemFireTransaction)tc;
		final GemFireContainer container = ((MemHeap)tran
		    .findExistingConglomerate(this.td.getHeapConglomerateId()))
		    .getGemFireContainer();
		container.clear(lcc, tran);
		if (true) {
		  return;
		}
// GemStone changes END
		//gather information from the existing conglomerate to create new one.
		emptyHeapRow = td.getEmptyExecRow();
		compressHeapCC = tc.openConglomerate(
								td.getHeapConglomerateId(),
                                false,
                                TransactionController.OPENMODE_FORUPDATE,
                                TransactionController.MODE_TABLE,
                                TransactionController.ISOLATION_SERIALIZABLE);

		// invalidate any prepared statements that
		// depended on this table (including this one)
		// bug 3653 has threads that start up and block on our lock, but do
		// not see they have to recompile their plan.    We now invalidate earlier
		// however they still might recompile using the old conglomerate id before we
		// commit our DD changes.
		//
		dm.invalidateFor(td, DependencyManager.TRUNCATE_TABLE, lcc);

		rl = compressHeapCC.newRowLocationTemplate();
		// Get the properties on the old heap
		compressHeapCC.getInternalTablePropertySet(properties);
		compressHeapCC.close();
		compressHeapCC = null;
    
// GemStone changes BEGIN
    // Update the DataDictionary
    // Get the ConglomerateDescriptor for the heap
    long oldHeapConglom = td.getHeapConglomerateId();
    ConglomerateDescriptor cd = td.getConglomerateDescriptor(oldHeapConglom);
    boolean hasFk = false;
    for (int index = 0; index < cdl.size(); ++index) {
      ConstraintDescriptor csd = cdl.elementAt(index);
      if (csd.getConstraintType() == DataDictionary.FOREIGNKEY_CONSTRAINT) {
        hasFk = true;
      }
    }

    // Drop the old conglomerate
    tc.dropConglomerate(oldHeapConglom);
// GemStone changes END

		//create new conglomerate
		newHeapConglom = 
            tc.createConglomerate(
                "heap",
                emptyHeapRow.getRowArray(),
                null, //column sort order - not required for heap
                td.getColumnCollationIds(),
                properties,
                TransactionController.IS_DEFAULT);
		
		/* Set up index info to perform truncate on them*/
		getAffectedIndexes(activation);
		if(numIndexes > 0)
		{
			indexRows = new ExecIndexRow[numIndexes];
			ordering  = new ColumnOrdering[numIndexes][];
			collation = new int[numIndexes][];

			for (int index = 0; index < numIndexes; index++)
			{
				// create a single index row template for each index
				indexRows[index] = compressIRGs[index].getIndexRowTemplate();
				compressIRGs[index].getIndexRow(emptyHeapRow, 
											  rl, 
											  indexRows[index],
											  (FormatableBitSet) null);
				/* For non-unique indexes, we order by all columns + the RID.
				 * For unique indexes, we just order by the columns.
				 * No need to try to enforce uniqueness here as
				 * index should be valid.
				 */
				int[] baseColumnPositions = 
                    compressIRGs[index].baseColumnPositions();

				boolean[] isAscending = compressIRGs[index].isAscending();

				int numColumnOrderings;
				numColumnOrderings = baseColumnPositions.length + 1;
				ordering[index]    = new ColumnOrdering[numColumnOrderings];
                collation[index]   = new int[baseColumnPositions.length + 1];

				for (int ii =0; ii < numColumnOrderings - 1; ii++) 
				{
					ordering[index][ii] = 
                        new IndexColumnOrder(ii, isAscending[ii]);
				}
				ordering[index][numColumnOrderings - 1] = 
                    new IndexColumnOrder(numColumnOrderings - 1);
			}
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

		// truncate  all indexes
		if(numIndexes > 0)
		{
			long[] newIndexCongloms = new long[numIndexes];
			for (int index = 0; index < numIndexes; index++)
			{
				updateIndex(newHeapConglom, dd, index, newIndexCongloms);
			}
		}

// GemStone changes BEGIN
    // Update sys.sysconglomerates with new conglomerate #
    dd.updateConglomerateDescriptor(cd, newHeapConglom, tc);
    this.td = dd.getTableDescriptor(this.td.getUUID());
    // Setup the GemFireContainer to set various properties which also
    // sets up the GfxdIndexManager
    container.setup(this.td, this.dd, this.lcc, (GemFireTransaction)this.tc,
        hasFk, null, activation, null);
		/*
		// Update the DataDictionary
		// Get the ConglomerateDescriptor for the heap
		long oldHeapConglom = td.getHeapConglomerateId();
		ConglomerateDescriptor cd = td.getConglomerateDescriptor(oldHeapConglom);

		// Update sys.sysconglomerates with new conglomerate #
		dd.updateConglomerateDescriptor(cd, newHeapConglom, tc);
		// Drop the old conglomerate
		tc.dropConglomerate(oldHeapConglom);
		*/
// GemStone changes END
		cleanUp();
	}


	/**
	 * Update all of the indexes on a table when doing a bulk insert
	 * on an empty table.
	 *
	 * @exception StandardException					thrown on error
	 */
	private void updateAllIndexes(long newHeapConglom, 
								  DataDictionary dd)
		throws StandardException
    {
		long[] newIndexCongloms = new long[numIndexes];

		/* Populate each index (one at a time or all at once). */
		if (sequential)
		{
			// First sorter populated during heap compression
			if (numIndexes >= 1)
			{
				updateIndex(newHeapConglom, dd, 0, newIndexCongloms);
			}
			for (int index = 1; index < numIndexes; index++)
			{
				// Scan heap and populate next sorter
				openBulkFetchScan(newHeapConglom);
				while (getNextRowFromRowSource() != null)
				{
					objectifyStreamingColumns();
					insertIntoSorter(index, compressRL[currentCompressRow - 1]);
				}
				updateIndex(newHeapConglom, dd, index, newIndexCongloms);
				closeBulkFetchScan();
			}
		}
		else
		{
			for (int index = 0; index < numIndexes; index++)
			{
				updateIndex(newHeapConglom, dd, index, newIndexCongloms);
			}
		}
	}

	private void updateIndex(
    long            newHeapConglom, 
    DataDictionary  dd,
    int             index, 
    long[]          newIndexCongloms)
		throws StandardException
	{
		Properties properties = new Properties();

		// Get the ConglomerateDescriptor for the index
		ConglomerateDescriptor cd = 
            td.getConglomerateDescriptor(indexConglomerateNumbers[index]);

		// Build the properties list for the new conglomerate
		ConglomerateController indexCC = 
            tc.openConglomerate(
                indexConglomerateNumbers[index],
                false,
                TransactionController.OPENMODE_FORUPDATE,
                TransactionController.MODE_TABLE,
                TransactionController.ISOLATION_SERIALIZABLE);

		// Get the properties on the old index
		indexCC.getInternalTablePropertySet(properties);

		/* Create the properties that language supplies when creating the
		 * the index.  (The store doesn't preserve these.)
		 */
		int indexRowLength = indexRows[index].nColumns();
		properties.put("baseConglomerateId", Long.toString(newHeapConglom));
// GemStone changes BEGIN
    IndexRowGenerator indexDesc = cd.getIndexDescriptor();
    boolean unique = indexDesc.isUnique();
    boolean uniqueWithDuplicateNulls = indexDesc.isUniqueWithDuplicateNulls();
    String indexType = indexDesc.indexType();
    if (uniqueWithDuplicateNulls) {
      if (lcc.getDataDictionary().checkVersion(
          DataDictionary.DD_VERSION_DERBY_10_4, null)) {
        properties.put("uniqueWithDuplicateNulls", Boolean.toString(true));
      }
      else {
        // for lower version of DD there is no unique with nulls
        // index creating a unique index instead.
        unique = true;
      }
    }
    if (unique) {
      properties.put("nUniqueColumns", Integer.toString(indexRowLength - 1));
    }
    else {
      properties.put("nUniqueColumns", Integer.toString(indexRowLength));
    }
    properties.put(DataDictionary.MODULE, this.dd);
    // change index name so that a new one can be created while old one
    // still exists
    String oldName = properties.getProperty(GfxdConstants.PROPERTY_TABLE_NAME);
    String newName = null;
    if (oldName != null) {
      newName = getNewIndexName(oldName);
      properties.setProperty(GfxdConstants.PROPERTY_TABLE_NAME, newName);
    }
                /*
		if (cd.getIndexDescriptor().isUnique())
		{
			properties.put(
                "nUniqueColumns", Integer.toString(indexRowLength - 1));
		}
		else
		{
			properties.put(
                "nUniqueColumns", Integer.toString(indexRowLength));
		}
		*/
// GemStone changes END
		properties.put(
            "rowLocationColumn", Integer.toString(indexRowLength - 1));
		properties.put(
            "nKeyFields", Integer.toString(indexRowLength));

		indexCC.close();

		// We can finally drain the sorter and rebuild the index
		// Populate the index.
		
		RowLocationRetRowSource cCount           = null;
		boolean                 statisticsExist  = false;

		if (!truncateTable)
		{
			sorters[index].completedInserts();
			sorters[index] = null;

			if (td.statisticsExist(cd))
			{
				cCount = 
                    new CardinalityCounter(
                            tc.openSortRowSource(sortIds[index]));

				statisticsExist = true;
			}
			else
            {
				cCount = 
                    new CardinalityCounter(
                            tc.openSortRowSource(sortIds[index]));
            }

            newIndexCongloms[index] = 
                tc.createAndLoadConglomerate(
// GemStone changes BEGIN
                    indexType,
                    /* "BTREE", */
// GemStone changes END
                    indexRows[index].getRowArray(),
                    ordering[index],
                    collation[index],
                    properties,
                    TransactionController.IS_DEFAULT,
                    cCount,
                    (long[]) null);

			//For an index, if the statistics already exist, then drop them.
			//The statistics might not exist for an index if the index was
			//created when the table was empty.
            //
            //For all alter table actions, including ALTER TABLE COMPRESS,
			//for both kinds of indexes (ie. one with preexisting statistics 
            //and with no statistics), create statistics for them if the table 
            //is not empty. 
			if (statisticsExist)
				dd.dropStatisticsDescriptors(td.getUUID(), cd.getUUID(), tc);
			
			long numRows;
			if ((numRows = ((CardinalityCounter)cCount).getRowCount()) > 0)
			{
				long[] c = ((CardinalityCounter)cCount).getCardinality();
				for (int i = 0; i < c.length; i++)
				{
					StatisticsDescriptor statDesc =
						new StatisticsDescriptor(
                            dd, 
                            dd.getUUIDFactory().createUUID(), 
                            cd.getUUID(), 
                            td.getUUID(), 
                            "I", 
                            new StatisticsImpl(numRows, c[i]), 
                            i + 1);

					dd.addDescriptor(
                            statDesc, 
                            null,   // no parent descriptor
							DataDictionary.SYSSTATISTICS_CATALOG_NUM,
							true,   // no error on duplicate.
                            tc);	
				}
			}
		}
        else
		{
// GemStone changes BEGIN
            newIndexCongloms[index] = 
                tc.createConglomerate(
                    indexType,
                    /* "BTREE", */
// GemStone changes END
                    indexRows[index].getRowArray(),
                    ordering[index],
                    collation[index],
                    properties,
                    TransactionController.IS_DEFAULT);


			//on truncate drop the statistics because we know for sure 
			//rowscount is zero and existing statistic will be invalid.
			if (td.statisticsExist(cd))
				dd.dropStatisticsDescriptors(td.getUUID(), cd.getUUID(), tc);
		}

		/* Update the DataDictionary
		 *
		 * Update sys.sysconglomerates with new conglomerate #, we need to
		 * update all (if any) duplicate index entries sharing this same
		 * conglomerate.
		 */
// GemStone changes BEGIN
    // update the name and conglomId of the index
    if (oldName != null) {
      updateIndex(this.sd, this.td, this.activation, oldName, newName,
          newIndexCongloms[index]);
    }
    else {
      dd.updateConglomerateDescriptor(td
          .getConglomerateDescriptors(indexConglomerateNumbers[index]),
          newIndexCongloms[index], tc);
    }
    /* (original derby code)
		dd.updateConglomerateDescriptor(
            td.getConglomerateDescriptors(indexConglomerateNumbers[index]),
            newIndexCongloms[index], 
            tc);
    */
// GemStone changes END

		// Drop the old conglomerate
		tc.dropConglomerate(indexConglomerateNumbers[index]);
	}

// GemStone changes BEGIN
        private void updateRefColsForIndexes(boolean cascade,
            Activation activation, String columnName) throws StandardException {
          IndexLister indexLister = td.getIndexLister();

          // Iterate through each index in the table
          IndexRowGenerator[] irgs = indexLister.getIndexRowGenerators();
          long[] indexCongloms = indexLister.getIndexConglomerateNumbers();
          final LanguageConnectionContext lcc = activation
              .getLanguageConnectionContext();
          final DataDictionary dd = lcc.getDataDictionary();
          IndexRowGenerator irg;

          // check for restrict drop first
          if (!cascade) {
            for (int i = 0; i < irgs.length; i++) {
              irg = irgs[i];
              if (irg == null) {
                continue;
              }
              // If the base columns of this index are higher than the column id
              // that is dropped, move them down one
              // (These conglomerates will be modified in the data dictionary and
              // subject to undo logic in case of ALTER TABLE undo)
              int[] baseColumnPositions = irg.baseColumnPositions();
              int size = baseColumnPositions.length;
              for (int k = 0; k < size; k++) {
                if (baseColumnPositions[k] == droppedColumnPosition) {
                  ConglomerateDescriptor cd = td
                      .getConglomerateDescriptor(indexCongloms[i]);
                  // throw exception for restrict
                  throw StandardException.newException(
                      SQLState.LANG_PROVIDER_HAS_DEPENDENT_OBJECT,
                      dd.getDependencyManager().getActionString(
                          DependencyManager.DROP_COLUMN),
                      columnName, "INDEX", cd.getConglomerateName());
                }
              }
            }
          }
          for (int i = 0; i < irgs.length; i++) {
            irg = irgs[i];
            if (irg == null) {
              continue;
            }
            // If the base columns of this index are higher than the column id
            // that is dropped, move them down one
            // (These conglomerates will be modified in the data dictionary and
            // subject to undo logic in case of ALTER TABLE undo)
            int[] baseColumnPositions = irg.baseColumnPositions();
            int size = baseColumnPositions.length;
            for (int k = 0; k < size; k++) {
              if (baseColumnPositions[k] > droppedColumnPosition) {
                baseColumnPositions[k]--;
              }
              else if (baseColumnPositions[k] == droppedColumnPosition) {
                // need to drop the index for cascade
                assert cascade;
                ConglomerateDescriptor cd = td
                    .getConglomerateDescriptor(indexCongloms[i]);
                dropConglomerate(cd, td, activation, lcc);
                activation.addWarning(StandardException.newWarning(
                    SQLState.LANG_INDEX_DROPPED,
                    new Object[] { cd.getConglomerateName(), td.getName(),
                        "ALTER TABLE DROP COLUMN " + columnName },
                    (Throwable)null));
              }
            }
          }
        }
// GemStone changes END

	/**
	 * Get info on the indexes on the table being compressed. 
	 *
	 * @exception StandardException		Thrown on error
	 */
	private void getAffectedIndexes(Activation activation)
		throws StandardException
	{
		IndexLister	indexLister = td.getIndexLister( );

		/* We have to get non-distinct index row generaters and conglom numbers
		 * here and then compress it to distinct later because drop column
		 * will need to change the index descriptor directly on each index
		 * entry in SYSCONGLOMERATES, on duplicate indexes too.
		 */
		compressIRGs = indexLister.getIndexRowGenerators();
		numIndexes = compressIRGs.length;
		indexConglomerateNumbers = indexLister.getIndexConglomerateNumbers();

		ArrayList newCongloms = new ArrayList();
		if (! (compressTable || truncateTable))		// then it's drop column
		{
			for (int i = 0; i < compressIRGs.length; i++)
			{
				int[] baseColumnPositions = compressIRGs[i].baseColumnPositions();
				int j;
				for (j = 0; j < baseColumnPositions.length; j++)
					if (baseColumnPositions[j] == droppedColumnPosition) break;
				if (j == baseColumnPositions.length)	// not related
					continue;
					
				if (baseColumnPositions.length == 1 || 
					(behavior == StatementType.DROP_CASCADE && compressIRGs[i].isUnique()))
				{
					numIndexes--;
					/* get first conglomerate with this conglom number each time
					 * and each duplicate one will be eventually all dropped
					 */
					ConglomerateDescriptor cd = td.getConglomerateDescriptor
												(indexConglomerateNumbers[i]);

					dropConglomerate(cd, td, true, newCongloms, activation,
						activation.getLanguageConnectionContext());

					compressIRGs[i] = null;		// mark it
					continue;
				}
				// give an error for unique index on multiple columns including
				// the column we are to drop (restrict), such index is not for
				// a constraint, because constraints have already been handled
				if (compressIRGs[i].isUnique())
				{
					ConglomerateDescriptor cd = td.getConglomerateDescriptor
												(indexConglomerateNumbers[i]);
					throw StandardException.newException(SQLState.LANG_PROVIDER_HAS_DEPENDENT_OBJECT,
										dm.getActionString(DependencyManager.DROP_COLUMN),
										columnInfo[0].name, "UNIQUE INDEX",
										cd.getConglomerateName() );
				}
			}

			/* If there are new backing conglomerates which must be
			 * created to replace a dropped shared conglomerate
			 * (where the shared conglomerate was dropped as part
			 * of a "drop conglomerate" call above), then create
			 * them now.  We do this *after* dropping all dependent
			 * conglomerates because we don't want to waste time
			 * creating a new conglomerate if it's just going to be
			 * dropped again as part of another "drop conglomerate"
			 * call.
			 */
			createNewBackingCongloms(newCongloms,
				indexConglomerateNumbers, activation, dd);

			IndexRowGenerator[] newIRGs = new IndexRowGenerator[numIndexes];
			long[] newIndexConglomNumbers = new long[numIndexes];

			for (int i = 0, j = 0; i < numIndexes; i++, j++)
			{
				while (compressIRGs[j] == null)
					j++;

				int[] baseColumnPositions = compressIRGs[j].baseColumnPositions();
				newIRGs[i] = compressIRGs[j];
				newIndexConglomNumbers[i] = indexConglomerateNumbers[j];

				boolean[] isAscending = compressIRGs[j].isAscending();
				boolean reMakeArrays = false;
				int size = baseColumnPositions.length;
				for (int k = 0; k < size; k++)
				{
					if (baseColumnPositions[k] > droppedColumnPosition)
						baseColumnPositions[k]--;
					else if (baseColumnPositions[k] == droppedColumnPosition)
					{
						baseColumnPositions[k] = 0;		// mark it
						reMakeArrays = true;
					}
				}
				if (reMakeArrays)
				{
					size--;
					int[] newBCP = new int[size];
					boolean[] newIsAscending = new boolean[size];
					for (int k = 0, step = 0; k < size; k++)
					{
						if (step == 0 && baseColumnPositions[k + step] == 0)
							step++;
						newBCP[k] = baseColumnPositions[k + step];
						newIsAscending[k] = isAscending[k + step];
					}
					IndexDescriptor id = compressIRGs[j].getIndexDescriptor();
					id.setBaseColumnPositions(newBCP);
					id.setIsAscending(newIsAscending);
					id.setNumberOfOrderedColumns(id.numberOfOrderedColumns() - 1);
				}
			}
			compressIRGs = newIRGs;
			indexConglomerateNumbers = newIndexConglomNumbers;
		}

		/* Now we are done with updating each index descriptor entry directly
		 * in SYSCONGLOMERATES (for duplicate index as well), from now on, our
		 * work should apply ONLY once for each real conglomerate, so we
		 * compress any duplicate indexes now.
		 */
		Object[] compressIndexResult = 
            compressIndexArrays(indexConglomerateNumbers, compressIRGs);

		if (compressIndexResult != null)
		{
			indexConglomerateNumbers = (long[]) compressIndexResult[1];
			compressIRGs = (IndexRowGenerator[]) compressIndexResult[2];
			numIndexes = indexConglomerateNumbers.length;
		}

		indexedCols = new FormatableBitSet(compressTable || truncateTable ? td.getNumberOfColumns() + 1 :
												  td.getNumberOfColumns());
		for (int index = 0; index < numIndexes; index++)
		{
			int[] colIds = compressIRGs[index].getIndexDescriptor().baseColumnPositions();

			for (int index2 = 0; index2 < colIds.length; index2++)
			{
				indexedCols.set(colIds[index2]);
			}
		}
	}

	/**
	 * Iterate through the received list of CreateIndexConstantActions and
	 * execute each one, It's possible that one or more of the constant
	 * actions in the list has been rendered "unneeded" by the time we get
	 * here (because the index that the constant action was going to create
	 * is no longer needed), so we have to check for that.
	 *
	 * @param newConglomActions Potentially empty list of constant actions
	 *   to execute, if still needed
	 * @param ixCongNums Optional array of conglomerate numbers; if non-null
	 *   then any entries in the array which correspond to a dropped physical
	 *   conglomerate (as determined from the list of constant actions) will
	 *   be updated to have the conglomerate number of the newly-created
	 *   physical conglomerate.
	 */
	private void createNewBackingCongloms(ArrayList newConglomActions,
		long [] ixCongNums, Activation activation, DataDictionary dd)
		throws StandardException
	{
		int sz = newConglomActions.size();
		for (int i = 0; i < sz; i++)
		{
			CreateIndexConstantAction ca =
				(CreateIndexConstantAction)newConglomActions.get(i);

			if (dd.getConglomerateDescriptor(ca.getCreatedUUID()) == null)
			{
				/* Conglomerate descriptor was dropped after
				 * being selected as the source for a new
				 * conglomerate, so don't create the new
				 * conglomerate after all.  Either we found
				 * another conglomerate descriptor that can
				 * serve as the source for the new conglom,
				 * or else we don't need a new conglomerate
				 * at all because all constraints/indexes
				 * which shared it had a dependency on the
				 * dropped column and no longer exist.
				 */
				continue;
			}

			executeConglomReplacement(ca, activation);
			long oldCongNum = ca.getReplacedConglomNumber();
			long newCongNum = ca.getCreatedConglomNumber();

			/* The preceding call to executeConglomReplacement updated all
			 * relevant ConglomerateDescriptors with the new conglomerate
			 * number *WITHIN THE DATA DICTIONARY*.  But the table
			 * descriptor that we have will not have been updated.
			 * There are two approaches to syncing the table descriptor
			 * with the dictionary: 1) refetch the table descriptor from
			 * the dictionary, or 2) update the table descriptor directly.
			 * We choose option #2 because the caller of this method (esp.
			 * getAffectedIndexes()) has pointers to objects from the
			 * table descriptor as it was before we entered this method.
			 * It then changes data within those objects, with the
			 * expectation that, later, those objects can be used to
			 * persist the changes to disk.  If we change the table
			 * descriptor here the objects that will get persisted to
			 * disk (from the table descriptor) will *not* be the same
			 * as the objects that were updated--so we'll lose the updates
			 * and that will in turn cause other problems.  So we go with
			 * option #2 and just change the existing TableDescriptor to
			 * reflect the fact that the conglomerate number has changed.
			 */
			ConglomerateDescriptor [] tdCDs =
				td.getConglomerateDescriptors(oldCongNum);

			for (int j = 0; j < tdCDs.length; j++)
				tdCDs[j].setConglomerateNumber(newCongNum);

			/* If we received a list of index conglomerate numbers
			 * then they are the "old" numbers; see if any of those
			 * numbers should now be updated to reflect the new
			 * conglomerate, and if so, update them.
			 */
			if (ixCongNums != null)
			{
				for (int j = 0; j < ixCongNums.length; j++)
				{
					if (ixCongNums[j] == oldCongNum)
						ixCongNums[j] = newCongNum;
				}
			}
		}
	}

	/**
	 * Set up to update all of the indexes on a table when doing a bulk insert
	 * on an empty table.
	 *
	 * @exception StandardException					thrown on error
	 */
	private void setUpAllSorts(ExecRow sourceRow,
							   RowLocation rl)
		throws StandardException
    {
		ordering        = new ColumnOrdering[numIndexes][];
        collation       = new int[numIndexes][]; 
		needToDropSort  = new boolean[numIndexes];
		sortIds         = new long[numIndexes];

        int[] base_table_collation_ids = td.getColumnCollationIds();

		/* For each index, build a single index row and a sorter. */
		for (int index = 0; index < numIndexes; index++)
		{
			// create a single index row template for each index
			indexRows[index] = compressIRGs[index].getIndexRowTemplate();


			// Get an index row based on the base row
			// (This call is only necessary here because we need to pass a 
            // template to the sorter.)
			compressIRGs[index].getIndexRow(
                sourceRow, rl, indexRows[index], (FormatableBitSet) null);

            // Setup collation id array to be passed in on call to create index.
            collation[index] = 
                compressIRGs[index].getColumnCollationIds(
                    td.getColumnDescriptorList());

			/* For non-unique indexes, we order by all columns + the RID.
			 * For unique indexes, we just order by the columns.
			 * No need to try to enforce uniqueness here as
			 * index should be valid.
			 */
			int[]       baseColumnPositions = 
                compressIRGs[index].baseColumnPositions();
			boolean[]   isAscending         = 
                compressIRGs[index].isAscending();
			int         numColumnOrderings  = 
                baseColumnPositions.length + 1;

			/* We can only reuse the wrappers when doing an
			 * external sort if there is only 1 index.  Otherwise,
			 * we could get in a situation where 1 sort reuses a
			 * wrapper that is still in use in another sort.
			 */
			boolean reuseWrappers = (numIndexes == 1);

			SortObserver    sortObserver = 
                new BasicSortObserver(
                        false, false, indexRows[index], reuseWrappers);

			ordering[index] = new ColumnOrdering[numColumnOrderings];
			for (int ii =0; ii < numColumnOrderings - 1; ii++) 
			{
				ordering[index][ii] = new IndexColumnOrder(ii, isAscending[ii]);
			}
			ordering[index][numColumnOrderings - 1] = 
                new IndexColumnOrder(numColumnOrderings - 1);

			// create the sorters
			sortIds[index] = 
                tc.createSort(
                   (Properties)null, 
                    indexRows[index].getClone(),
                    ordering[index],
                    sortObserver,
                    false,			        // not in order
                    estimatedRowCount,		// est rows	
                    -1,				        // est row size, -1 means no idea
                    0 // no sort result limit
                    );
		}
	
        sorters = new SortController[numIndexes];

		// Open the sorts
		for (int index = 0; index < numIndexes; index++)
		{
			sorters[index] = tc.openSort(sortIds[index]);
			needToDropSort[index] = true;
		}
	}

	// RowSource interface
	
	/** 
	 * @see RowSource#getValidColumns
	 */
	public FormatableBitSet getValidColumns()
	{
		// All columns are valid
		return null;
	}
	
	/** 
	 * @see RowSource#getNextRowFromRowSource
	 * @exception StandardException on error
	 */
	public ExecRow getNextRowFromRowSource()
		throws StandardException
	{
		currentRow = null;
		// Time for a new bulk fetch?
		if ((! doneScan) &&
			(currentCompressRow == bulkFetchSize || !validRow[currentCompressRow]))
		{
			int bulkFetched = 0;

			bulkFetched = compressHeapGSC.fetchNextGroup(baseRowArray, compressRL);

			doneScan = (bulkFetched != bulkFetchSize);
			currentCompressRow = 0;
			rowCount += bulkFetched;
			for (int index = 0; index < bulkFetched; index++)
			{
				validRow[index] = true;
			}
			for (int index = bulkFetched; index < bulkFetchSize; index++)
			{
				validRow[index] = false;
			}
		}

		if (validRow[currentCompressRow])
		{
			if (compressTable)
            {
				currentRow = baseRow[currentCompressRow];
            }
			else
			{
				if (currentRow == null)
                {
					currentRow = 
                        activation.getExecutionFactory().getValueRow(
                            baseRowArray[currentCompressRow].length - 1);
                }

				for (int i = 0; i < currentRow.nColumns(); i++)
				{
					currentRow.setColumn(
                        i + 1, 
                        i < droppedColumnPosition - 1 ?
                            baseRow[currentCompressRow].getColumn(i+1) :
                            baseRow[currentCompressRow].getColumn(i+1+1));
				}
			}
			currentCompressRow++;
		}

 		if (currentRow != null)
		{
			/* Let the target preprocess the row.  For now, this
			 * means doing an in place clone on any indexed columns
			 * to optimize cloning and so that we don't try to drain
			 * a stream multiple times.
			 */
			if (compressIRGs.length > 0)
			{
				/* Do in-place cloning of all of the key columns */
				currentRow =  currentRow.getClone(indexedCols);
			}

			return currentRow;
		}

		return null;
	}

	/**
	 * @see RowSource#needsToClone
	 */
	public boolean needsToClone()
	{
		return(true);
	}

	/** 
	 * @see RowSource#closeRowSource
	 */
	public void closeRowSource()
	{
		// Do nothing here - actual work will be done in close()
	}


	// RowLocationRetRowSource interface

	/**
	 * @see RowLocationRetRowSource#needsRowLocation
	 */
	public boolean needsRowLocation()
	{
		// Only true if table has indexes
		return (numIndexes > 0);
	}

	/**
	 * @see RowLocationRetRowSource#rowLocation
	 * @exception StandardException on error
	 */
	public void rowLocation(RowLocation rl)
		throws StandardException
	{
		/* Set up sorters, etc. if 1st row and there are indexes */
		if (compressIRGs.length > 0)
		{
			objectifyStreamingColumns();

			/* Put the row into the indexes.  If sequential, 
			 * then we only populate the 1st sorter when compressing
			 * the heap.
			 */
			int maxIndex = compressIRGs.length;
			if (maxIndex > 1 && sequential)
			{
				maxIndex = 1;
			}
			for (int index = 0; index < maxIndex; index++)
			{
				insertIntoSorter(index, rl);
			}
		}
	}

	private void objectifyStreamingColumns()
		throws StandardException
	{
		// Objectify any the streaming columns that are indexed.
		for (int i = 0; i < currentRow.getRowArray().length; i++)
		{
			/* Object array is 0-based,
			 * indexedCols is 1-based.
			 */
			if (! indexedCols.get(i + 1))
			{
				continue;
			}

			if (currentRow.getRowArray()[i] instanceof StreamStorable)
			{
				((DataValueDescriptor) currentRow.getRowArray()[i]).getObject();
			}
		}
	}

	private void insertIntoSorter(int index, RowLocation rl)
		throws StandardException
	{
		// Get a new object Array for the index
		indexRows[index].getNewObjectArray();
		// Associate the index row with the source row
		compressIRGs[index].getIndexRow(currentRow, 
									    (RowLocation) rl.cloneObject(), 
										indexRows[index],
										(FormatableBitSet) null);

		// Insert the index row into the matching sorter
		sorters[index].insert(indexRows[index]);
	}

	/**
	 *
	 * @exception StandardException		Thrown on error
	 */
	private void	cleanUp() throws StandardException
	{
		if (compressHeapCC != null)
		{
			compressHeapCC.close();
			compressHeapCC = null;
		}

		if (compressHeapGSC != null)
		{
			closeBulkFetchScan();
		}

		// Close each sorter
		if (sorters != null)
		{
			for (int index = 0; index < compressIRGs.length; index++)
			{
				if (sorters[index] != null)
				{
					sorters[index].completedInserts();
				}
				sorters[index] = null;
			}
		}

		if (needToDropSort != null)
		{
			for (int index = 0; index < needToDropSort.length; index++)
			{
				if (needToDropSort[index])
				{
				 	tc.dropSort(sortIds[index]);
					needToDropSort[index] = false;
				}
			}
		}
	}

	// class implementation

	/**
	 * Return the "semi" row count of a table.  We are only interested in
	 * whether the table has 0, 1 or > 1 rows.
	 *
	 *
	 * @return Number of rows (0, 1 or > 1) in table.
	 *
	 * @exception StandardException		Thrown on failure
	 */
// GemStone changes BEGIN
  @Override
  public final String getSchemaName() {
    return this.sd.getSchemaName();
  }

  @Override
  public final String getTableName() {
    return this.tableName;
  }

  /* TODO: merge: below does not work for some reason
  @Override
  public String getObjectName() {
    // we do the conflation only for add/drop constraint
    // cannot do it for column since each add/drop column will increment
    // the schema version which is expected to be consistent on all nodes
    // including those that are currently active
    String constraintType = null;
    String constraintId = null;
    if (this.constraintActions != null) {
      for (ConstraintConstantAction cca : this.constraintActions) {
        if (cca == null) {
          continue;
        }
        if (constraintType != null) {
          // more than one constraint changed in a single ALTER TABLE?
          return null;
        }
        switch (cca.getConstraintType()) {
          case DataDictionary.PRIMARYKEY_CONSTRAINT:
            constraintType = "__GFXD_INTERNAL_PK";
            break;
          case DataDictionary.UNIQUE_CONSTRAINT:
            constraintType = "__GFXD_INTERNAL_UK";
            break;
          case DataDictionary.FOREIGNKEY_CONSTRAINT:
            constraintType = "__GFXD_INTERNAL_FK";
            break;
          case DataDictionary.CHECK_CONSTRAINT:
            constraintType = "__GFXD_INTERNAL_CK";
            break;
          default:
            // something unknown?
            return null;
        }
        constraintId = cca.getFullConstraintName();
      }
      if (constraintType != null) {
        return constraintType + '.' + constraintId;
      }
    }
    return null;
  }

  @Override
  public boolean isDropStatement() {
    // we do the conflation only for add/drop constraint
    // cannot do it for column since each add/drop column will increment
    // the schema version which is expected to be consistent on all nodes
    // including those that are currently active
    boolean hasConstraintType = false;
    boolean isDropStatement = false;
    if (this.constraintActions != null) {
      for (ConstraintConstantAction cca : this.constraintActions) {
        if (cca == null) {
          continue;
        }
        if (hasConstraintType) {
          // more than one constraint changed in a single ALTER TABLE?
          return false;
        }
        switch (cca.getConstraintType()) {
          case DataDictionary.PRIMARYKEY_CONSTRAINT:
          case DataDictionary.UNIQUE_CONSTRAINT:
          case DataDictionary.FOREIGNKEY_CONSTRAINT:
          case DataDictionary.CHECK_CONSTRAINT:
            hasConstraintType = true;
            break;
          default:
            // something unknown?
            return false;
        }
        isDropStatement = (cca instanceof DropConstraintConstantAction);
      }
      if (hasConstraintType) {
        return isDropStatement;
      }
    }
    return false;
  }
  */

  static int getNumBucketsOrSize(Region<?, ?> region,
      LanguageConnectionContext lcc) throws StandardException {
    // ignore the buckets in region for initial DDL replay (bug #40759)
    if (Misc.initialDDLReplayInProgress()) {
      return 0;
    }
    DataPolicy policy = region.getAttributes().getDataPolicy();
    if (policy.withPartitioning()) {
      PartitionedRegion pregion = (PartitionedRegion)region;
      return pregion.getRegionAdvisor().getCreatedBucketsCount();
    }
    else if (policy.withReplication() || !policy.withStorage()) {
      final RegionSizeMessage rmsg = new RegionSizeMessage(
          region.getFullPath(), new GfxdSingleResultCollector(),
          (LocalRegion)region, null, lcc);
      try {
        Object result = rmsg.executeFunction();
        assert result instanceof Integer;
        return ((Integer)result).intValue();
      } catch (SQLException sqle) {
        throw Misc.wrapRemoteSQLException(sqle, sqle, null);
      }
    }
    else {
      return region.size();
    }
  }

  private int getSemiRowCount(TransactionController tc)
      throws StandardException {
    switch (getRowCount(tc, this.td, this.numBuckets)) {
      case 0:
        return 0;
      case 1:
        return 1;
      default:
        return 2;
    }
  }

  public boolean isTruncateTable() {
    return this.truncateTable;
  }

  public static int getRowCount(TransactionController tc,
      TableDescriptor td, int numBuckets) throws StandardException {
    // ignore the rows if this is initial DDL replay [#40759]
    if (Misc.initialDDLReplayInProgress()) {
      return 0;
    }
    LanguageConnectionContext lcc = tc.getLanguageConnectionContext();
    Region<?, ?> region = Misc.getRegionForTableByPath(
        Misc.getFullTableName(td, lcc), false);
    DataPolicy policy = region.getAttributes().getDataPolicy();
    if (policy.withReplication() || !policy.withStorage()) {
      if (numBuckets > 0) {
        return numBuckets;
      }
      final RegionSizeMessage rmsg = new RegionSizeMessage(
          region.getFullPath(), new GfxdSingleResultCollector(),
          (LocalRegion)region, null, lcc);
      try {
        Object result = rmsg.executeFunction();
        assert result instanceof Integer;
        return ((Integer)result).intValue();
      } catch (SQLException sqle) {
        throw Misc.wrapRemoteSQLException(sqle, sqle, null);
      }
    }
    else {
      return region.size();
    }
	/* (original code)
	private int getSemiRowCount(TransactionController tc)
		throws StandardException
	{
		int			   numRows = 0;

		ScanController sc = tc.openScan(td.getHeapConglomerateId(),
						 false,	// hold
						 0,	    // open read only
                         TransactionController.MODE_TABLE,
                         TransactionController.ISOLATION_SERIALIZABLE,
						 RowUtil.EMPTY_ROW_BITSET, // scanColumnList
						 null,	// start position
						 ScanController.GE,      // startSearchOperation
						 null, // scanQualifier
						 null, //stop position - through last row
						 ScanController.GT);     // stopSearchOperation

		while (sc.next())
		{
			numRows++;

			// We're only interested in whether the table has 0, 1 or > 1 rows
			if (numRows == 2)
			{
				break;
			}
		}
		sc.close();

		return numRows;
		*/
// GemStone changes END
	}

	/**
	 * Update a new column with its default.
	 * We could do the scan ourself here, but
	 * instead we get a nested connection and
	 * issue the appropriate update statement.
	 *
	 * @param columnName		column name
	 * @param defaultText		default text
	 * @param lcc				the language connection context
	 *
	 * @exception StandardException if update to default fails
	 */
	private void updateNewColumnToDefault
	(
		Activation activation,
		String							columnName,
		String							defaultText,
		LanguageConnectionContext		lcc
// GemStone changes BEGIN
		, ColumnDescriptor cd
// GemStone changes END
	)
		throws StandardException
	{
		/* Need to use delimited identifiers for all object names
		 * to ensure correctness.
		 */
// GemStone changes BEGIN
	  // calculate and store the default DVD in the ColumnDescriptor;
	  // RowFormatter will use it to return the column default for
	  // previous schemas as required
	  String defaultQuery = "VALUES " + defaultText;
	  PreparedStatement ps = lcc.prepareInternalStatement(defaultQuery,
	      (byte)0);
	  ResultSet rs = ps.execute(lcc, true, 0L);
	  ExecRow row = rs.getNextRow();
	  if (row != null) {
	    DataValueDescriptor columnDefault = row.getColumn(1);
	    // do any required type conversion
	    if (columnDefault.getTypeFormatId() != cd.getType()
	        .getDVDTypeFormatId()) {
	      DataValueDescriptor dvd = cd.getType().getNull();
	      dvd.setValue(columnDefault);
	      columnDefault = dvd;
	    }
	    cd.setColumnDefault(columnDefault);
	  }
	  rs.close(false);
	  /* (original code)
        String updateStmt = "UPDATE " +
                IdUtil.mkQualifiedName(td.getSchemaName(), td.getName()) +
                " SET " + IdUtil.normalToDelimited(columnName) + "=" +
                defaultText;

		executeUpdate(lcc, updateStmt);
	  */
// GemStone changes END
	}

	private static void executeUpdate(LanguageConnectionContext lcc, String updateStmt) throws StandardException
	{
// GemStone changes BEGIN
	  /* only execute on local data
	   byte execFlags = 0x00;
	   execFlags = GemFireXDUtils.set(execFlags,GenericStatement.CREATE_QUERY_INFO);
	   */
	  PreparedStatement ps = lcc.prepareInternalStatement(updateStmt,
	      (byte)0);
// GemStone changes END

        // This is a substatement; for now, we do not set any timeout
        // for it. We might change this behaviour later, by linking
        // timeout to its parent statement's timeout settings.
		ResultSet rs = ps.execute(lcc, true, 0L);
		rs.close(false);
	}

	/**
	 * computes the minimum/maximum value in a column of a table.
	 */
	private long getColumnMax(Activation activation, TableDescriptor td, String columnName, 
							  long increment, long initial)
							  throws StandardException
	{
		String maxStr = (increment > 0) ? "MAX" : "MIN";
        String maxStmt = "SELECT  " + maxStr + "(" +
                IdUtil.normalToDelimited(columnName) + ") FROM " +
                IdUtil.mkQualifiedName(td.getSchemaName(), td.getName());

// GemStone changes BEGIN
		short execFlags = 0x00;
		execFlags = GemFireXDUtils.set(execFlags,GenericStatement.CREATE_QUERY_INFO);
		LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
		PreparedStatement ps = lcc.prepareInternalStatement(maxStmt, execFlags);
// GemStone changes END

        // This is a substatement, for now we do not set any timeout for it
        // We might change this later by linking timeout to parent statement
		ResultSet rs = ps.execute(lcc, false, 0L);
		DataValueDescriptor[] rowArray = rs.getNextRow().getRowArray();
		rs.close(false);
		rs.finish();

		return rowArray[0].getLong();
	}					

	private void dropAllColumnDefaults(UUID tableId, DataDictionary dd)
		throws StandardException
	{
		ColumnDescriptorList cdl = td.getColumnDescriptorList();
		int					 cdlSize = cdl.size();
		
		for(int index = 0; index < cdlSize; index++)
		{
			ColumnDescriptor cd = (ColumnDescriptor) cdl.elementAt(index);

			// If column has a default we drop the default and
			// any dependencies
			if (cd.getDefaultInfo() != null)
			{
				DefaultDescriptor defaultDesc = cd.getDefaultDescriptor(dd);
				dm.clearDependencies(lcc, defaultDesc);
			}
		}
	}

	private void openBulkFetchScan(long heapConglomNumber)
		throws StandardException
	{
		doneScan = false;
		compressHeapGSC = tc.openGroupFetchScan(
                            heapConglomNumber,
							false,	// hold
							0,	// open base table read only
                            TransactionController.MODE_TABLE,
                            TransactionController.ISOLATION_SERIALIZABLE,
							null,    // all fields as objects
							(DataValueDescriptor[]) null,	// startKeyValue
							0,		// not used when giving null start posn.
							null,	// qualifier
							(DataValueDescriptor[]) null,	// stopKeyValue
							0);		// not used when giving null stop posn.
	}

	private void closeBulkFetchScan()
		throws StandardException
	{
		compressHeapGSC.close();
		compressHeapGSC = null;
	}

	/**
	 * Update values in a new autoincrement column being added to a table.
	 * This is similar to updateNewColumnToDefault whereby we issue an
	 * update statement using a nested connection. The UPDATE statement 
	 * uses a static method in ConnectionInfo (which is not documented) 
	 * which returns the next value to be inserted into the autoincrement
	 * column.
	 *
	 * @param columnName autoincrement column name that is being added.
	 * @param initial    initial value of the autoincrement column.
	 * @param increment  increment value of the autoincrement column.
	 *
	 * @see #updateNewColumnToDefault
	 */
	private void updateNewAutoincrementColumn(Activation activation, String columnName, long initial,
											 long increment)
		throws StandardException
	{
		LanguageConnectionContext lcc = activation.getLanguageConnectionContext();

		// Don't throw an error in bind when we try to update the 
		// autoincrement column.
		lcc.setAutoincrementUpdate(true);

		lcc.autoincrementCreateCounter(td.getSchemaName(),
									   td.getName(),
									   columnName, new Long(initial),
									   increment, 0);
		// the sql query is.
		// UPDATE table 
		//  set ai_column = ConnectionInfo.nextAutoincrementValue(
		//							schemaName, tableName, 
		//							columnName)
        String updateStmt = "UPDATE " +
            IdUtil.mkQualifiedName(td.getSchemaName(), td.getName()) +
            " SET " + IdUtil.normalToDelimited(columnName) + "=" +
			"com.pivotal.gemfirexd.internal.iapi.db.ConnectionInfo::" + 
			"nextAutoincrementValue(" + 
            StringUtil.quoteStringLiteral(td.getSchemaName()) + "," +
            StringUtil.quoteStringLiteral(td.getName()) + "," +
            StringUtil.quoteStringLiteral(columnName) + ")";



		try
		{
			AlterTableConstantAction.executeUpdate(lcc, updateStmt);
		}
		catch (StandardException se)
		{
			if (se.getMessageId().equals(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE))
			{
				// If overflow, override with more meaningful message.
				throw StandardException.newException(SQLState.LANG_AI_OVERFLOW,
													 se,
													 td.getName(),
													 columnName);
			}
			throw se;
		}
		finally
		{
			// and now update the autoincrement value.
			lcc.autoincrementFlushCache(td.getUUID());
			lcc.setAutoincrementUpdate(false);		
		}

	} 
	/**
	 * Make sure that the columns are non null
	 * If any column is nullable, check that the data is null.
	 *
	 * @param	columnNames	names of columns to be checked
	 * @param	nullCols	true if corresponding column is nullable
	 * @param	numRows		number of rows in the table
	 * @param	lcc		language context
	 * @param	errorMsg	error message to use for exception
	 *
	 * @return true if any nullable columns found (nullable columns must have
	 *		all non null data or exception is thrown
	 * @exception StandardException on error 
	 */
	private boolean validateNotNullConstraint
	(
		String							columnNames[],
		boolean							nullCols[],
		int								numRows,
		LanguageConnectionContext		lcc,
		String							errorMsg
	)
		throws StandardException
	{
		boolean foundNullable = false;
		StringBuilder constraintText = new StringBuilder();

		/* 
		 * Check for nullable columns and create a constraint string which can
		 * be used in validateConstraint to check whether any of the
		 * data is null.  
		 */
		for (int colCtr = 0; colCtr < columnNames.length; colCtr++)
		{
			ColumnDescriptor cd = td.getColumnDescriptor(columnNames[colCtr]);

			if (cd == null)
			{
				throw StandardException.newException(SQLState.LANG_COLUMN_NOT_FOUND_IN_TABLE,
														columnNames[colCtr],
														td.getName());
			}

			if (cd.getType().isNullable())
			{
				if (numRows > 0)
				{
					// already found a nullable column so add "AND" 
					if (foundNullable)
						constraintText.append(" AND ");
					// Delimiting the column name is important in case the
					// column name uses lower case characters, spaces, or
					// other unusual characters.
					constraintText.append(
						IdUtil.normalToDelimited(columnNames[colCtr]) +
						" IS NOT NULL ");
				}
				foundNullable = true;
				nullCols[colCtr] = true;
			}
		}

		/* if the table has nullable columns and isn't empty 
		 * we need to validate the data
		 */
		if (foundNullable && numRows > 0)
		{
			if (!ConstraintConstantAction.validateConstraint(
									(String) null,
									constraintText.toString(),
									td,
									lcc,
									false))
			{	
				if (errorMsg.equals(SQLState.LANG_NULL_DATA_IN_PRIMARY_KEY))
				{	//alter table add primary key
					throw StandardException.newException(
						SQLState.LANG_NULL_DATA_IN_PRIMARY_KEY, 
						td.getQualifiedName());
				}
				else 
				{	//alter table modify column not null
					throw StandardException.newException(
						SQLState.LANG_NULL_DATA_IN_NON_NULL_COLUMN, 
						td.getQualifiedName(), columnNames[0]);
				}
			}
		}
		return foundNullable;
	}

	/**
	 * Get rid of duplicates from a set of index conglomerate numbers and
	 * index descriptors.
	 *
	 * @param	indexCIDS	array of index conglomerate numbers
	 * @param	irgs		array of index row generaters
	 *
	 * @return value:		If no duplicates, returns NULL; otherwise,
	 *						a size-3 array of objects, first element is an
	 *						array of duplicates' indexes in the input arrays;
	 *						second element is the compact indexCIDs; third
	 *						element is the compact irgs.
	 */
	private Object[] compressIndexArrays(
								long[] indexCIDS,
								IndexRowGenerator[] irgs)
	{
		/* An efficient way to compress indexes.  From one end of workSpace,
		 * we save unique conglom IDs; and from the other end we save
		 * duplicate indexes' indexes.  We save unique conglom IDs so that
		 * we can do less amount of comparisons.  This is efficient in
		 * space as well.  No need to use hash table.
		 */
		long[] workSpace = new long[indexCIDS.length];
		int j = 0, k = indexCIDS.length - 1;
		for (int i = 0; i < indexCIDS.length; i++)
		{
			int m;
			for (m = 0; m < j; m++)		// look up our unique set
			{
				if (indexCIDS[i] == workSpace[m])	// it's a duplicate
				{
					workSpace[k--] = i;		// save dup index's index
					break;
				}
			}
			if (m == j)
				workSpace[j++] = indexCIDS[i];	// save unique conglom id
		}
		if (j < indexCIDS.length)		// duplicate exists
		{
			long[] newIndexCIDS = new long[j];
			IndexRowGenerator[] newIrgs = new IndexRowGenerator[j];
			int[] duplicateIndexes = new int[indexCIDS.length - j];
			k = 0;
			// do everything in one loop
			for (int m = 0, n = indexCIDS.length - 1; m < indexCIDS.length; m++)
			{
				// we already gathered our indexCIDS and duplicateIndexes
				if (m < j)
					newIndexCIDS[m] = workSpace[m];
				else
					duplicateIndexes[indexCIDS.length - m - 1] = (int) workSpace[m];

				// stack up our irgs, indexSCOCIs, indexDCOCIs
				if ((n >= j) && (m == (int) workSpace[n]))
					n--;
				else
				{
					newIrgs[k] = irgs[m];
					k++;
				}
			}

			// construct return value
			Object[] returnValue = new Object[3]; // [indexSCOCIs == null ? 3 : 5];
			returnValue[0] = duplicateIndexes;
			returnValue[1] = newIndexCIDS;
			returnValue[2] = newIrgs;
			return returnValue;
		}
		else		// no duplicates
			return null;
	}

	// GemStone changes BEGIN    
	  @Override
	  public boolean isCancellable() {
	    return truncateTable ? false : true;
	  }
	// GemStone changes END    
	  
	  public ConstraintConstantAction[] getConstraintConstantActions() {
	    return this.constraintActions;
	  }
	  
}

